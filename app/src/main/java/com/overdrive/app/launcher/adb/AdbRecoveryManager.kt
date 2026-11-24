package com.overdrive.app.launcher.adb

import android.Manifest
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.dilink5.enable.BydManualAdbModeAuthority
import java.util.EnumSet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * App-lifetime local recovery in every supported mode, with the existing DiLink 5
 * coordinator owning all wireless work. No local write acquires a wireless setting journal.
 */
class AdbRecoveryManager internal constructor(
    private val gateway: Gateway,
    private val wireless: WirelessRecovery,
    private val worker: Executor,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val defer: ((() -> Unit), Long) -> Unit = { action, delay ->
        executor.schedule(action, delay, TimeUnit.MILLISECONDS)
    },
) {
    internal interface Gateway : LocalAdbFlagRestorer.Gateway {
        fun modeAuthority(): String?
        fun automaticRestoreEnabled(): Boolean
        fun automaticRecoveryAllowed(): Boolean
        fun observeAdbEnabled(onChange: () -> Unit): AutoCloseable
        fun classicAuthenticated(): Boolean
        /** Called only after a live authenticated classic probe, then verifies the grant. */
        fun seedWriteSecureSettings(): Boolean
        fun tlsRecoveryAvailable(): Boolean
        fun startHelpers()
        fun report(result: Result) {}
    }

    internal interface WirelessRecovery {
        fun setAutomaticRestore(enabled: Boolean): Boolean
        fun triggerAutomatic()
        fun cancelAutomatic()
        fun recoverManualAsync(
            isCallerCurrent: () -> Boolean,
            callback: (DiLink5AdbRecoveryCoordinator.Result) -> Unit,
        ): Boolean
        fun cancelManual(): Boolean
        fun hasManualRecoveryOwner(): Boolean
    }

    internal enum class Trigger { STARTUP, CONFIGURATION, FLAG_CHANGE, CONNECTION, LIFECYCLE }
    enum class Outcome { AUTHENTICATED, SETTING_ENABLED, MISSING_PERMISSION, SETTING_FAILED, CANCELLED, MODE_UNAVAILABLE }

    data class Result(
        val outcome: Outcome,
        val settingEnabled: Boolean = false,
        val settingWriteAttempted: Boolean = false,
        val authenticated: Boolean = false,
        val wirelessResult: DiLink5AdbRecoveryCoordinator.Result? = null,
    )

    private class ManualRequest(
        val authority: String,
        val callbacks: MutableList<(Result) -> Unit>,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        var dispatched: Boolean = false,
        @Volatile var wirelessDispatched: Boolean = false,
    )

    private class DeferredRetry(val epoch: Long)

    private val lock = Any()
    private val scheduled = AtomicBoolean(false)
    private val automaticEpoch = AtomicLong(0)
    private val deferredRetry = AtomicReference<DeferredRetry?>(null)
    private val pendingTriggers = EnumSet.noneOf(Trigger::class.java)
    private val restorer = LocalAdbFlagRestorer(gateway)
    @Volatile private var manual: ManualRequest? = null
    @Volatile private var observer: AutoCloseable? = null
    @Volatile private var lastAutomaticEnabled: Boolean? = null
    @Volatile private var lastAutomaticAuthority: String? = null
    @Volatile private var latestResult: Result? = null
    private var nextAutomaticWriteMs = Long.MIN_VALUE

    fun lastResult(): Result? = latestResult

    /** Nonblocking: connection callbacks may be dispatched while an ADB command is finishing. */
    fun triggerAutomatic() = trigger(Trigger.LIFECYCLE)

    internal fun trigger(reason: Trigger) {
        synchronized(lock) { pendingTriggers.add(reason) }
        schedule()
    }

    /** Settings calls this on its I/O executor; the switch is acknowledged only after persistence. */
    fun setAutomaticRestore(enabled: Boolean): Boolean {
        if (!enabled) cancelAutomatic()
        val saved = try { wireless.setAutomaticRestore(enabled) } catch (_: Exception) { false }
        trigger(Trigger.CONFIGURATION)
        return saved
    }

    fun cancelAutomatic() {
        automaticEpoch.incrementAndGet()
        deferredRetry.set(null)
        synchronized(lock) { pendingTriggers.clear() }
        wireless.cancelAutomatic()
    }

    fun recoverManualAsync(authority: String, callback: (Result) -> Unit): Boolean {
        synchronized(lock) {
            val prior = manual
            if (prior != null) {
                if (prior.authority != authority || prior.cancelled.get()) return false
                prior.callbacks.add(callback)
                return true
            }
            manual = ManualRequest(authority, mutableListOf(callback))
        }
        schedule()
        return true
    }

    /** Cancellation stops future local writes; it never switches a user's adb_enabled back off. */
    fun cancelManual(): Boolean {
        val target = synchronized(lock) { manual?.also { it.cancelled.set(true) } }
        val wirelessOwned = target?.wirelessDispatched == true || wireless.hasManualRecoveryOwner()
        val cleaned = if (wirelessOwned) wireless.cancelManual() else target != null
        schedule()
        return cleaned
    }

    fun hasManualRecoveryOwner(): Boolean = manual != null || wireless.hasManualRecoveryOwner()

    fun hasLocalManualRequest(): Boolean = manual != null

    private fun schedule() {
        val runnable = synchronized(lock) {
            manual?.let { !it.dispatched || Trigger.CONFIGURATION in pendingTriggers }
                ?: pendingTriggers.isNotEmpty()
        }
        if (!runnable || !scheduled.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    while (true) {
                        val request = synchronized(lock) {
                            manual?.takeUnless { it.dispatched }?.also { it.dispatched = true }
                        }
                        if (request != null) {
                            runManual(request)
                            continue
                        }
                        val reasons = synchronized(lock) {
                            if (manual != null) {
                                if (pendingTriggers.remove(Trigger.CONFIGURATION)) setOf(Trigger.CONFIGURATION)
                                else null
                            } else if (pendingTriggers.isEmpty()) null
                            else EnumSet.copyOf(pendingTriggers).also { pendingTriggers.clear() }
                        } ?: break
                        runAutomatic(reasons)
                    }
                } catch (_: Exception) {
                    // A transient provider/transport failure must not lose the observer
                    // that can deliver the next recoverable flag change.
                    latestResult = Result(Outcome.SETTING_FAILED)
                    manual?.let { completeManual(it, Result(Outcome.SETTING_FAILED)) }
                } finally {
                    scheduled.set(false)
                    schedule()
                }
            }
        } catch (_: Exception) {
            scheduled.set(false)
            manual?.let { completeManual(it, Result(Outcome.SETTING_FAILED)) }
        }
    }

    private fun runAutomatic(reasons: Set<Trigger>) {
        val enabled = try { gateway.automaticRestoreEnabled() } catch (_: Exception) {
            automaticEpoch.incrementAndGet()
            deferredRetry.set(null)
            lastAutomaticEnabled = null
            closeObserver()
            wireless.cancelAutomatic()
            return
        }
        val authority = try { gateway.modeAuthority() } catch (_: Exception) { null }
        if (reasons == setOf(Trigger.CONFIGURATION) &&
            enabled == lastAutomaticEnabled && authority == lastAutomaticAuthority
        ) {
            // Retry a failed unregister even when the durable preference stayed off.
            if (!enabled) closeObserver()
            return
        }
        if (enabled != lastAutomaticEnabled || authority != lastAutomaticAuthority) {
            automaticEpoch.incrementAndGet()
        }
        lastAutomaticEnabled = enabled
        lastAutomaticAuthority = authority
        manual?.let { request ->
            if (request.authority != authority) {
                request.cancelled.set(true)
                if (request.wirelessDispatched) wireless.cancelManual()
            }
        }
        if (!enabled || !gateway.automaticRecoveryAllowed() || authority == null) {
            closeObserver()
            wireless.cancelAutomatic()
            return
        }
        if (manual != null) return
        if (observer == null) {
            observer = try {
                gateway.observeAdbEnabled { trigger(Trigger.FLAG_CHANGE) }
            } catch (_: Exception) { null }
        }
        val epoch = automaticEpoch.get()
        val current = {
            // Policy reads can wait for storage. Check the captured authority and
            // cancellation epoch after those reads, immediately before each action.
            val optedIn = gateway.automaticRestoreEnabled()
            val allowed = gateway.automaticRecoveryAllowed()
            optedIn && allowed && gateway.modeAuthority() == authority &&
                epoch == automaticEpoch.get() && manual == null
        }
        // Our putInt(1) can notify the observer. Its readback must not launch a second
        // transport attempt or a TLS retry wave merely because the flag is already 1.
        val flagOnly = reasons == setOf(Trigger.FLAG_CHANGE)
        val remaining = nextAutomaticWriteMs - nowMs()
        val withinWriteInterval = nextAutomaticWriteMs != Long.MIN_VALUE && remaining > 0
        val recentFlag = if (withinWriteInterval) try {
            gateway.readAdbEnabled()
        } catch (_: Exception) { null } else 1
        if (withinWriteInterval && recentFlag != 1) {
            if (recentFlag == null) {
                latestResult = Result(Outcome.SETTING_FAILED)
                report(requireNotNull(latestResult))
            }
            // A provider may notify synchronously for our own write, or firmware may
            // immediately turn the flag off again. Coalesce those edges without spinning.
            val prior = deferredRetry.get()
            if (prior?.epoch != epoch) {
                val reservation = DeferredRetry(epoch)
                if (deferredRetry.compareAndSet(prior, reservation)) {
                    try {
                        defer({
                            if (deferredRetry.compareAndSet(reservation, null) &&
                                epoch == automaticEpoch.get()
                            ) trigger(Trigger.FLAG_CHANGE)
                        }, remaining)
                    } catch (_: Exception) {
                        deferredRetry.compareAndSet(reservation, null)
                    }
                }
            }
            return
        }
        val result = recoverLocal(current, verifyAlreadyEnabled = !flagOnly)
        latestResult = result
        report(result)
        if (result.settingWriteAttempted) nextAutomaticWriteMs = nowMs() + AUTOMATIC_WRITE_INTERVAL_MS
        if (result.outcome == Outcome.CANCELLED || !safeCurrent(current)) return
        if (flagOnly && !result.settingWriteAttempted) return
        if (result.authenticated && safeCurrent(current)) {
            try { gateway.startHelpers() } catch (_: Exception) {}
        }
        if (gateway.tlsRecoveryAvailable()) wireless.triggerAutomatic()
    }

    private fun runManual(request: ManualRequest) {
        // A NEEDS_DIALOG result retires this facade request while the wireless
        // coordinator retains its owner. Keep authority alive without retaining
        // the request's delivered UI callbacks.
        val admittedAuthority = request.authority
        val cancelled = request.cancelled
        val callerCurrent = {
            gateway.modeAuthority() == admittedAuthority && !cancelled.get()
        }
        val current = {
            safeCurrent(callerCurrent) && manual === request
        }
        if (!safeCurrent(current)) {
            completeManual(request, Result(Outcome.CANCELLED))
            return
        }
        val result = recoverLocal(current, verifyAlreadyEnabled = true)
        if (!safeCurrent(current) || result.outcome == Outcome.CANCELLED) {
            completeManual(request, result.copy(outcome = Outcome.CANCELLED, authenticated = false))
            return
        }
        val tlsAvailable = gateway.tlsRecoveryAvailable()
        // An earlier NEEDS_DIALOG result can retain wireless ownership even after
        // classic ADB becomes usable. Let T finish that exact owner on this retry.
        if (result.authenticated && !(tlsAvailable && wireless.hasManualRecoveryOwner())) {
            if (safeCurrent(current)) try { gateway.startHelpers() } catch (_: Exception) {}
            completeManual(request, result)
            return
        }
        if (!tlsAvailable) {
            completeManual(request, result)
            return
        }
        request.wirelessDispatched = true
        val accepted = wireless.recoverManualAsync(callerCurrent) { recovered ->
            completeManual(
                request,
                result.copy(
                    outcome = when {
                        recovered.outcome == DiLink5AdbRecoveryCoordinator.Outcome.CANCELLED -> Outcome.CANCELLED
                        recovered.authenticated -> Outcome.AUTHENTICATED
                        result.outcome == Outcome.AUTHENTICATED ->
                            if (result.settingEnabled) Outcome.SETTING_ENABLED else Outcome.SETTING_FAILED
                        else -> result.outcome
                    },
                    authenticated = recovered.authenticated,
                    wirelessResult = recovered,
                ),
            )
        }
        if (!accepted) completeManual(request, result.copy(
            wirelessResult = DiLink5AdbRecoveryCoordinator.Result(DiLink5AdbRecoveryCoordinator.Outcome.BUSY),
        ))
        // The wireless worker carries the same fence before admission and mutation.
        // Cancellation here also reconciles any already-owned wireless setting.
        if (!safeCurrent(callerCurrent)) {
            wireless.cancelManual()
        }
    }

    private fun recoverLocal(current: () -> Boolean, verifyAlreadyEnabled: Boolean): Result {
        var local = restorer.restore(current)
        if (local.outcome == LocalAdbFlagRestorer.Outcome.CANCELLED) return localResult(local)
        var authenticated = false
        if (local.outcome == LocalAdbFlagRestorer.Outcome.MISSING_PERMISSION ||
            verifyAlreadyEnabled || local.writeAttempted
        ) {
            if (!safeCurrent(current)) return localResult(local).copy(outcome = Outcome.CANCELLED)
            authenticated = try { gateway.classicAuthenticated() } catch (_: Exception) { false }
            if (!safeCurrent(current)) {
                return localResult(local).copy(outcome = Outcome.CANCELLED, authenticated = false)
            }
            // A grant can lag the executor's CONNECTED event. Seed it through this
            // verified connection and immediately re-run the local phase after readback.
            val permission = try { gateway.hasWriteSecureSettings() } catch (_: Exception) { false }
            if (authenticated && !permission) {
                val granted = safeCurrent(current) && try {
                    gateway.seedWriteSecureSettings()
                } catch (_: Exception) { false }
                if (!safeCurrent(current)) {
                    return localResult(local).copy(outcome = Outcome.CANCELLED, authenticated = false)
                }
                if (granted) {
                    val afterGrant = restorer.restore(current)
                    local = afterGrant.copy(writeAttempted = local.writeAttempted || afterGrant.writeAttempted)
                    if (afterGrant.writeAttempted) {
                        // The newly permitted write can restart adbd. A probe made
                        // before that write cannot prove the resulting transport.
                        authenticated = safeCurrent(current) && try {
                            gateway.classicAuthenticated()
                        } catch (_: Exception) { false }
                        if (!safeCurrent(current)) {
                            return localResult(local).copy(outcome = Outcome.CANCELLED, authenticated = false)
                        }
                    }
                }
            }
        }
        val result = localResult(local)
        return if (authenticated && safeCurrent(current)) result.copy(
            outcome = if (result.settingEnabled) Outcome.AUTHENTICATED else result.outcome,
            authenticated = true,
        ) else result
    }

    private fun localResult(local: LocalAdbFlagRestorer.Result): Result = Result(
        outcome = when (local.outcome) {
            LocalAdbFlagRestorer.Outcome.ALREADY_ENABLED,
            LocalAdbFlagRestorer.Outcome.ENABLED -> Outcome.SETTING_ENABLED
            LocalAdbFlagRestorer.Outcome.MISSING_PERMISSION -> Outcome.MISSING_PERMISSION
            LocalAdbFlagRestorer.Outcome.CANCELLED -> Outcome.CANCELLED
            else -> Outcome.SETTING_FAILED
        },
        settingEnabled = local.settingEnabled,
        settingWriteAttempted = local.writeAttempted,
    )

    private fun completeManual(request: ManualRequest, proposed: Result) {
        val result = if (!safeCurrent {
            gateway.modeAuthority() == request.authority && !request.cancelled.get()
        }) proposed.copy(outcome = Outcome.CANCELLED, authenticated = false) else proposed
        val callbacks = synchronized(lock) {
            if (manual !== request) return
            manual = null
            request.callbacks.toList().also { request.callbacks.clear() }
        }
        latestResult = result
        report(result)
        callbacks.forEach { callback -> try { callback(result) } catch (_: Exception) {} }
        schedule()
    }

    private fun closeObserver() {
        val prior = observer ?: return
        try {
            prior.close()
            if (observer === prior) observer = null
        } catch (_: Exception) {
            // Keep the handle until unregister succeeds. Losing it could register a
            // duplicate observer on the next enable, unlike the reference's ownership.
        }
    }

    private fun safeCurrent(current: () -> Boolean): Boolean =
        try { current() } catch (_: Exception) { false }

    private fun report(result: Result) {
        try { gateway.report(result) } catch (_: Exception) {}
    }

    private class AndroidGateway(private val context: Context) : Gateway {
        private val store = DiLink5AdbRecoveryStateStore()
        private val shell = AdbShellExecutor(context, automaticRecoveryRequestsAllowed = false)
        override fun modeAuthority(): String? = BydManualAdbModeAuthority.currentScope()?.authority()
        override fun automaticRestoreEnabled() = store.load().automaticRestore
        override fun automaticRecoveryAllowed(): Boolean =
            Application.getProcessName() == context.packageName && DiLink5AdbAutomaticRecoveryGate.allowed()
        override fun readAdbEnabled(): Int? =
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        override fun hasWriteSecureSettings() = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        override fun writeAdbEnabled() =
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        override fun observeAdbEnabled(onChange: () -> Unit): AutoCloseable {
            val observed = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange()
            }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observed,
            )
            return AutoCloseable { context.contentResolver.unregisterContentObserver(observed) }
        }
        override fun classicAuthenticated(): Boolean = try {
            val marker = "OD_ADB_LOCAL_PROBE"
            val response = shell.executeSync("printf '$marker'", 3_000)
            response.exitCode == 0 && response.output.trim() == marker
        } catch (_: Exception) { false }
        override fun seedWriteSecureSettings(): Boolean = try {
            shell.executeSync(
                "pm grant ${com.overdrive.app.util.ShellUser.flag()} ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", 5_000,
            )
            hasWriteSecureSettings()
        } catch (_: Exception) { false }
        override fun tlsRecoveryAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            BydManualAdbModeAuthority.committedDiLink5RecoveryAvailable()
        override fun startHelpers() = com.overdrive.app.ui.daemon.DaemonStartupManager.startOnBoot(context)
        override fun report(result: Result) {
            Log.i(TAG, "local=${result.outcome}, flag=${result.settingEnabled}, " +
                "write=${result.settingWriteAttempted}, authenticated=${result.authenticated}, " +
                "wireless=${result.wirelessResult?.outcome}")
        }
    }

    private class AndroidWireless(private val context: Context) : WirelessRecovery {
        private val coordinator get() = DiLink5AdbRecoveryCoordinator.get(context)
        override fun setAutomaticRestore(enabled: Boolean) = coordinator.setAutomaticRestore(enabled)
        override fun triggerAutomatic() = coordinator.triggerAutomatic()
        override fun cancelAutomatic() = coordinator.cancelAutomatic()
        override fun recoverManualAsync(
            isCallerCurrent: () -> Boolean,
            callback: (DiLink5AdbRecoveryCoordinator.Result) -> Unit,
        ) = coordinator.recoverManualAsync(isCallerCurrent, callback)
        override fun cancelManual() = coordinator.cancelManual()
        override fun hasManualRecoveryOwner() = coordinator.hasManualRecoveryOwner()
    }

    companion object {
        private const val TAG = "AdbRecovery"
        private const val AUTOMATIC_WRITE_INTERVAL_MS = 2_000L
        @Volatile private var instance: AdbRecoveryManager? = null
        @Volatile private var lifecycleInstalled = false
        private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AdbLocalRecovery").apply { isDaemon = true }
        }

        @JvmStatic
        fun get(context: Context): AdbRecoveryManager = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                AdbRecoveryManager(AndroidGateway(app), AndroidWireless(app), executor)
                    .also { instance = it }
            }
        }

        /** Main process only; account-service workers and the OEM warning process stay untouched. */
        @JvmStatic
        fun start(context: Context) {
            if (Application.getProcessName() != context.packageName) return
            val manager = get(context)
            synchronized(this) {
                if (lifecycleInstalled) return
                lifecycleInstalled = true
                AdbShellExecutor.addConnectionStateListener { state, _ ->
                    if (state == AdbShellExecutor.ConnectionState.CONNECTED ||
                        state == AdbShellExecutor.ConnectionState.DISCONNECTED
                    ) manager.trigger(Trigger.CONNECTION)
                }
                UnifiedConfigManager.addListener(object : UnifiedConfigManager.ConfigChangeListener {
                    override fun onConfigChanged(section: String, config: org.json.JSONObject) {
                        // The listener runs under the config lock. Perform every read and write
                        // on the dedicated worker, and ignore unrelated section notifications.
                        if (section == "all" || section == "camera" ||
                            section == DiLink5AdbRecoveryStateStore.SECTION
                        ) manager.trigger(Trigger.CONFIGURATION)
                    }
                })
            }
            manager.trigger(Trigger.STARTUP)
            Log.i(TAG, "Main-process ADB recovery lifecycle installed")
        }
    }
}
