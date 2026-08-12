package com.overdrive.app.ui.adapter

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class InFlightLoadCoordinator<K : Any, V>(
    private val scope: CoroutineScope,
    maxConcurrent: Int
) {
    private val permits = Semaphore(maxConcurrent)
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
    }

    fun load(key: K, loader: suspend () -> V): Deferred<V> {
        while (true) {
            inFlight[key]?.let { existing ->
                existing.start()
                return existing
            }

            val created = scope.async(start = CoroutineStart.LAZY) {
                permits.withPermit { loader() }
            }
            val winner = inFlight.putIfAbsent(key, created)
            if (winner != null) {
                created.cancel()
                winner.start()
                return winner
            }

            // Register only after insertion. If the parent scope was already
            // cancelled, invokeOnCompletion runs immediately and can now remove
            // the inserted cancelled Deferred instead of leaving a stale entry.
            created.invokeOnCompletion {
                inFlight.remove(key, created)
            }
            created.start()
            return created
        }
    }

    internal fun inFlightCount(): Int = inFlight.size
}