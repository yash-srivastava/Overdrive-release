package com.overdrive.app.services

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.overdrive.app.launcher.adb.DiLink5AdbRecoveryCoordinator
import com.overdrive.app.launcher.adb.DiLink5AdbRecoveryDiscovery

/** Handles only the opted-in SystemUI wireless-debugging authorization dialog. */
class WifiDebuggingDialogController internal constructor(
    private val scheduler: Scheduler,
    private val environment: Environment,
) : AutoCloseable {
    data class SessionKey(val generation: Long, val networkId: String)

    fun interface Cancellable { fun cancel() }
    fun interface Scheduler { fun postDelayed(delayMs: Long, action: Runnable): Cancellable }
    fun interface OwnerInvalidationListener { fun onInvalidated(generation: Long) }

    interface Node : AutoCloseable {
        fun isChecked(): Boolean
        fun click(): Boolean
    }

    interface DialogWindow : AutoCloseable {
        fun findNode(viewId: String): Node?
    }

    interface Environment {
        fun eligibleSession(): SessionKey?
        fun isEligible(session: SessionKey): Boolean
        fun findWindow(windowId: Int): DialogWindow?
        fun onDialogActionAttempted(generation: Long)
        fun setOwnerInvalidationListener(listener: OwnerInvalidationListener?)
    }

    private data class Lookup(
        val windowId: Int,
        val session: SessionKey,
        val lifecycleGeneration: Long,
    ) {
        @Volatile var cancellable: Cancellable? = null
    }

    class Lifecycle(
        private val factory: () -> WifiDebuggingDialogController,
    ) {
        private var controller: WifiDebuggingDialogController? = null

        fun attach() {
            if (controller == null) controller = factory()
        }

        fun onAccessibilityEvent(event: AccessibilityEvent?) {
            controller?.onAccessibilityEvent(event)
        }

        fun unbind() {
            controller?.cancelPending()
        }

        fun destroy() {
            controller?.close()
            controller = null
        }
    }

    private val lock = Any()
    private var pending: Lookup? = null
    private var handled: Lookup? = null
    private var lifecycleGeneration = 0L
    private var closed = false
    private val ownerInvalidationListener = OwnerInvalidationListener { generation ->
        synchronized(lock) {
            if (pending?.session?.generation == generation || handled?.session?.generation == generation) {
                lifecycleGeneration++
            }
            if (pending?.session?.generation == generation) {
                pending?.cancellable?.cancel()
                pending = null
            }
            if (handled?.session?.generation == generation) handled = null
        }
    }

    init {
        environment.setOwnerInvalidationListener(ownerInvalidationListener)
    }

    constructor(service: AccessibilityService, handler: Handler) : this(
        HandlerScheduler(handler),
        AndroidEnvironment(service),
    )

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!matches(event)) return
        onDialogWindow(event!!.packageName.toString(), event.className.toString(), event.windowId)
    }

    fun onDialogWindow(packageName: String, className: String, windowId: Int) {
        if (!matches(packageName, className, windowId)) return
        val session = environment.eligibleSession() ?: return
        synchronized(lock) {
            if (closed) return
            val lookup = Lookup(windowId, session, lifecycleGeneration)
            if (pending.sameWindow(lookup) || handled.sameWindow(lookup)) return
            pending?.cancellable?.cancel()
            pending = lookup
            lookup.cancellable = scheduler.postDelayed(LOOKUP_DELAY_MS, Runnable { perform(lookup) })
        }
    }

    private fun Lookup?.sameWindow(other: Lookup): Boolean =
        this != null && windowId == other.windowId && session == other.session

    private fun perform(lookup: Lookup) {
        synchronized(lock) {
            if (closed || pending !== lookup || lookup.lifecycleGeneration != lifecycleGeneration) return
            pending = null
            handled = lookup
        }
        if (!isCurrentAndEligible(lookup)) return
        val window = environment.findWindow(lookup.windowId) ?: return
        window.use {
            if (!isCurrentAndEligible(lookup)) return
            val checkbox = it.findNode(CHECKBOX_ID) ?: return
            val button = it.findNode(BUTTON_ID) ?: run {
                checkbox.close()
                return
            }
            checkbox.use { checkboxNode ->
                button.use { buttonNode ->
                    if (!isCurrentAndEligible(lookup)) return
                    val checkboxChecked = checkboxNode.isChecked()
                    if (!checkboxChecked) {
                        if (!isCurrentAndEligible(lookup) || !checkboxNode.click()) return
                    }
                    if (!isCurrentAndEligible(lookup) || !buttonNode.click()) return
                    if (!isCurrentAndEligible(lookup)) return
                    environment.onDialogActionAttempted(lookup.session.generation)
                }
            }
        }
    }

    private fun isCurrentAndEligible(lookup: Lookup): Boolean {
        fun current() = synchronized(lock) {
            !closed && handled === lookup && lookup.lifecycleGeneration == lifecycleGeneration
        }
        if (!current() || !environment.isEligible(lookup.session)) return false
        // The external eligibility read can wait for storage while cancellation,
        // unbind or owner invalidation retires this lookup.
        return current()
    }

    fun cancelPending() {
        synchronized(lock) {
            lifecycleGeneration++
            pending?.cancellable?.cancel()
            pending = null
            handled = null
        }
    }

    override fun close() {
        synchronized(lock) {
            lifecycleGeneration++
            closed = true
            pending?.cancellable?.cancel()
            pending = null
            handled = null
        }
        environment.setOwnerInvalidationListener(null)
    }

    private class HandlerScheduler(private val handler: Handler) : Scheduler {
        override fun postDelayed(delayMs: Long, action: Runnable): Cancellable {
            handler.postDelayed(action, delayMs)
            return Cancellable { handler.removeCallbacks(action) }
        }
    }

    private class AndroidEnvironment(private val service: AccessibilityService) : Environment {
        private var registeredInvalidationListener:
            DiLink5AdbRecoveryCoordinator.SessionInvalidationListener? = null
        private fun coordinator() = DiLink5AdbRecoveryCoordinator.get(service.applicationContext)

        override fun eligibleSession(): SessionKey? {
            val session = coordinator().snapshot() ?: return null
            val network = DiLink5AdbRecoveryDiscovery(service.applicationContext).currentNetwork() ?: return null
            val key = SessionKey(session.generation, network.id)
            return key.takeIf { coordinator().isEligible(it.generation, it.networkId) }
        }

        override fun isEligible(session: SessionKey): Boolean =
            coordinator().isEligible(session.generation, session.networkId)

        override fun findWindow(windowId: Int): DialogWindow? {
            val window = try { service.windows.firstOrNull { it.id == windowId } } catch (_: Throwable) { null }
                ?: return null
            val root = try { window.root } catch (_: Throwable) { null } ?: return null
            return AndroidDialogWindow(root)
        }

        override fun onDialogActionAttempted(generation: Long) {
            coordinator().onDialogActionAttempted(generation)
        }

        override fun setOwnerInvalidationListener(listener: OwnerInvalidationListener?) {
            registeredInvalidationListener?.let { coordinator().removeSessionInvalidationListener(it) }
            registeredInvalidationListener = listener?.let { target ->
                DiLink5AdbRecoveryCoordinator.SessionInvalidationListener { generation ->
                    target.onInvalidated(generation)
                }.also { coordinator().addSessionInvalidationListener(it) }
            }
        }
    }

    private class AndroidDialogWindow(private val root: AccessibilityNodeInfo) : DialogWindow {
        override fun findNode(viewId: String): Node? {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Throwable) { null }
                ?: return null
            val first = nodes.firstOrNull()
            nodes.drop(1).forEach { recycle(it) }
            return first?.let { AndroidNode(it) }
        }

        override fun close() = recycle(root)
    }

    private class AndroidNode(private val node: AccessibilityNodeInfo) : Node {
        override fun isChecked(): Boolean = node.isChecked
        override fun click(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        override fun close() = recycle(node)
    }

    companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val DIALOG_CLASS = "WifiDebuggingActivity"
        const val CHECKBOX_ID = "com.android.internal:id/alwaysUse"
        const val BUTTON_ID = "android:id/button1"
        const val LOOKUP_DELAY_MS = 300L

        @JvmStatic
        fun matches(event: AccessibilityEvent?): Boolean = event != null &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            matches(event.packageName?.toString(), event.className?.toString(), event.windowId)

        @JvmStatic
        fun matches(packageName: String?, className: String?, windowId: Int): Boolean =
            packageName == SYSTEM_UI_PACKAGE &&
                className != null &&
                (className == DIALOG_CLASS || className.endsWith(".$DIALOG_CLASS")) &&
                windowId >= 0

        private fun recycle(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try { node.recycle() } catch (_: Throwable) {}
        }
    }
}
