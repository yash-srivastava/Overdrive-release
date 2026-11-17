package com.overdrive.app.launcher.dilink5.enable

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Correlates one unresolved worker operation per process and journals late completions separately. */
internal class DiLink5WorkerResultDispatcher<T>(
    private val timeoutMs: Long,
) {
    private enum class WaitState { WAITING, COMPLETED, TIMED_OUT }

    private val unresolved = ConcurrentHashMap<String, Long>()

    fun dispatch(
        workerProcess: String,
        operationId: Long,
        onLateResult: (T) -> Unit = {},
        start: (complete: (Long, T?) -> Unit) -> Boolean,
    ): T? {
        if (unresolved.putIfAbsent(workerProcess, operationId) != null) return null
        val latch = CountDownLatch(1)
        val lock = Any()
        var waitState = WaitState.WAITING
        val result = AtomicReference<T?>()
        val complete: (Long, T?) -> Unit = complete@{ returnedId, value ->
            if (returnedId != operationId) return@complete
            val late = synchronized(lock) {
                if (waitState == WaitState.WAITING) {
                    result.set(value)
                    waitState = WaitState.COMPLETED
                    false
                } else {
                    waitState == WaitState.TIMED_OUT
                }
            }
            try {
                if (late && value != null) onLateResult(value)
            } finally {
                unresolved.remove(workerProcess, operationId)
                latch.countDown()
            }
        }
        if (!start(complete)) {
            synchronized(lock) { waitState = WaitState.COMPLETED }
            unresolved.remove(workerProcess, operationId)
            return null
        }
        return try {
            val signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val completed = synchronized(lock) {
                if (!signalled && waitState == WaitState.WAITING) {
                    waitState = WaitState.TIMED_OUT
                }
                waitState == WaitState.COMPLETED
            }
            if (completed) result.get() else null
        } catch (_: InterruptedException) {
            synchronized(lock) {
                if (waitState == WaitState.WAITING) waitState = WaitState.TIMED_OUT
            }
            Thread.currentThread().interrupt()
            null
        }
    }
}
