package com.overdrive.app.network

import java.util.concurrent.atomic.AtomicInteger

/**
 * Reserves a relay slot before an accepted connection is queued for a worker.
 * Unlike a check followed by an increment in a worker, this atomic admission
 * gate cannot oversubscribe the relay under connection bursts.
 */
internal class RelayAdmissionGate(private val limit: Int) {
    private val active = AtomicInteger(0)

    init { require(limit > 0) }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= limit) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = active.decrementAndGet()
        check(remaining >= 0) { "relay slot released more than once" }
    }

    fun activeCount(): Int = active.get()
}
