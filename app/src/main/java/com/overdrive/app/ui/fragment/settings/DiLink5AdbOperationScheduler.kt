package com.overdrive.app.ui.fragment.settings

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Process-lifetime admission owner for user-triggered DiLink 5 ADB operations. */
internal class DiLink5AdbOperationScheduler(
    private val workerExecutor: Executor,
    private val completionExecutor: Executor,
) {
    private val running = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun <T> submit(
        operation: () -> T,
        onComplete: (T?) -> Unit,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        notifyRunning()
        return try {
            workerExecutor.execute {
                val result = try {
                    operation()
                } catch (_: Throwable) {
                    null
                }
                try {
                    completionExecutor.execute {
                        try {
                            onComplete(result)
                        } finally {
                            running.set(false)
                            notifyRunning()
                        }
                    }
                } catch (_: Throwable) {
                    running.set(false)
                    notifyRunning()
                }
            }
            true
        } catch (_: Throwable) {
            running.set(false)
            notifyRunning()
            false
        }
    }

    fun isRunning(): Boolean = running.get()

    fun observeRunning(observer: (Boolean) -> Unit): AutoCloseable {
        observers += observer
        observer(running.get())
        return AutoCloseable { observers -= observer }
    }

    private fun notifyRunning() {
        observers.forEach { observer ->
            try {
                observer(running.get())
            } catch (_: Throwable) {
            }
        }
    }
}
