package com.overdrive.app.launcher.adb

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.launcher.AdbShellExecutor
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Process-wide owner for manual and opted-in automatic TLS recovery. */
class DiLink5AdbRecoveryCoordinator internal constructor(
    private val gateway: Gateway,
    private val store: AdbRecoveryStateStore,
    private val clock: Clock = SystemClock,
    private val quiesceTimeoutMs: Long = TRANSPORT_QUIESCE_TIMEOUT_MS,
    private val dispatchExecutor: java.util.concurrent.Executor = executor,
    private val beforeWaitingTransfer: () -> Unit = {},
) {
    enum class Owner { MANUAL, AUTOMATIC }
    enum class Outcome {
        CLASSIC_USABLE,
        UNSUPPORTED_API,
        MODE_UNAVAILABLE,
        MISSING_PERMISSION,
        NO_VALIDATED_WIFI,
        NEEDS_DIALOG,
        NO_LISTENER,
        TLS_FAILURE,
        AUTHORIZATION_REJECTED,
        TRANSPORT_FAILURE,
        RESTORED,
        CANCELLED,
        BUSY,
        TRUST_LOST,
    }

    data class Result(
        val outcome: Outcome,
        val transportRestored: Boolean = false,
        val authenticated: Boolean = false,
        val helperStartRequested: Boolean = false,
        val helperHealthy: Boolean = false,
        val settingOwned: Boolean = false,
        val rollbackPartial: Boolean = false,
        val retryVerdict: String? = null,
    )

    data class ActiveSession(val owner: Owner, val generation: Long, val networkId: String?)

    fun interface SessionInvalidationListener {
        fun onSessionInvalidated(generation: Long)
    }

    interface Gateway {
        fun apiLevel(): Int
        fun automaticRecoveryAllowed(): Boolean
        fun modeGeneration(): String?
        fun classicUsable(): Boolean
        fun identityPreviouslyAuthenticated(): Boolean
        fun hasWriteSecureSettings(): Boolean
        fun seedWriteSecureSettings(): Boolean
        fun networkIdentity(): String?
        fun readWirelessSetting(): Int?
        fun writeWirelessSetting(value: Int): WriteResult
        fun discover(cancelled: AtomicBoolean, includeProperty: Boolean): DiLink5AdbRecoveryDiscovery.Result
        fun openTls(endpoint: DiLink5AdbRecoveryDiscovery.Endpoint, cancelled: AtomicBoolean): AdbTlsTransport.Result
        fun proveFreshClassic(
            timeoutMs: Int,
            publishIfCurrent: ((() -> Unit) -> Boolean),
        ): Boolean
        fun startHelpers(): Boolean
        fun helpersHealthy(): Boolean
        fun enableAccessibilityHelper(isCurrent: () -> Boolean): Boolean
    }

    enum class WriteResult { CONFIRMED, REJECTED, UNCERTAIN }

    interface Clock {
        fun wallTimeMs(): Long
        fun elapsedTimeMs(): Long
        fun sleep(ms: Long, cancelled: AtomicBoolean): Boolean
    }

    private data class Session(
        val owner: Owner,
        val generation: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val isCallerCurrent: () -> Boolean = { true },
        val finished: CountDownLatch = CountDownLatch(1),
        val invalidationNotified: AtomicBoolean = AtomicBoolean(false),
        val mutationLock: Any = Any(),
        @Volatile var networkId: String? = null,
        @Volatile var admittedModeGeneration: String? = null,
        @Volatile var wroteSetting: Boolean = false,
        @Volatile var waitingForDialog: Boolean = false,
    )

    private data class HelperBootstrap(
        val requested: Boolean,
        val healthy: Boolean,
    )

    private data class PendingManualRequest(
        val requestId: Long,
        val callbacks: MutableList<(Result) -> Unit>,
        val isCallerCurrent: () -> Boolean,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        var dispatched: Boolean = false,
    )

    private data class WaitingReplacement(
        val session: Session? = null,
        val settingOwned: Boolean = false,
        val rollbackPartial: Boolean = false,
    )

    private val ownerLock = Any()
    // UI enqueue/coalescing must not share the mutation lock held during durable admission.
    private val manualPendingLock = Any()
    private val generation = AtomicLong(SecureRandom().nextLong() and Long.MAX_VALUE)
    private val automaticPendingLock = Any()
    private val automaticPendingScopes = LinkedHashSet<String>()
    private val automaticDrainScheduled = AtomicBoolean(false)
    private val invalidationListeners =
        java.util.concurrent.CopyOnWriteArrayList<SessionInvalidationListener>()
    @Volatile private var active: Session? = null
    @Volatile private var pendingManual: PendingManualRequest? = null
    // UI observation only. Unknown state keeps explicit cleanup available; admission always
    // reads the durable journal independently. Never wait for storage or ownerLock on the UI.
    @Volatile private var observedJournalMayBeOwned = true
    private val manualRequestIds = AtomicLong(0L)
    private val unscopedManualCaller: () -> Boolean = { true }

    fun addSessionInvalidationListener(listener: SessionInvalidationListener) {
        invalidationListeners.addIfAbsent(listener)
    }

    fun removeSessionInvalidationListener(listener: SessionInvalidationListener) {
        invalidationListeners.remove(listener)
    }

    private fun notifyInvalidated(session: Session) {
        if (!session.invalidationNotified.compareAndSet(false, true)) return
        invalidationListeners.forEach { listener ->
            try { listener.onSessionInvalidated(session.generation) } catch (_: Exception) {}
        }
    }

    fun snapshot(): ActiveSession? = active?.let { ActiveSession(it.owner, it.generation, it.networkId) }

    internal fun pendingAutomaticScopes(): Set<String> =
        synchronized(automaticPendingLock) { automaticPendingScopes.toSet() }

    internal fun automaticDrainIdle(): Boolean = !automaticDrainScheduled.get()

    fun isEligible(generation: Long, networkId: String): Boolean {
        val current = active ?: return false
        if (!dialogScopeCurrent(current, generation, networkId)) return false
        val state = try { loadState() } catch (_: Exception) { return false }
        return state.dialogAutoConfirm &&
            state.dialogAutoConfirmNetwork == networkId &&
            (current.owner == Owner.MANUAL ||
                (state.automaticRestore && gateway.automaticRecoveryAllowed())) &&
            dialogScopeCurrent(current, generation, networkId)
    }

    private fun dialogScopeCurrent(session: Session, generation: Long, networkId: String): Boolean = try {
        active === session && session.generation == generation && !session.cancelled.get() &&
            callerCurrent(session.isCallerCurrent) && session.networkId == networkId &&
            session.admittedModeGeneration != null &&
            gateway.modeGeneration() == session.admittedModeGeneration &&
            gateway.networkIdentity() == networkId
    } catch (_: Exception) { false }

    fun recoverManual(): Result = recoverManual(null)

    private fun recoverManual(request: PendingManualRequest?): Result = try {
        recoverManualAttempt(request)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        admissionBlockedResult().copy(outcome = Outcome.CANCELLED)
    } catch (_: Exception) {
        admissionBlockedResult().copy(outcome = Outcome.TRANSPORT_FAILURE)
    }

    private fun recoverManualAttempt(request: PendingManualRequest?): Result {
        if (!manualRequestCurrent(request)) {
            return admissionBlockedResult().copy(outcome = Outcome.CANCELLED)
        }
        if (active == null && persistedOwnershipPending()) return admissionBlockedResult()
        // An explicit Settings retry starts a fresh T8/handoff attempt. Passive failures retain
        // exhaustion, but the user action must not remain suppressed by an earlier automatic wave.
        store.clearExhaustion()
        val automatic = active?.takeIf { it.owner == Owner.AUTOMATIC }
        if (automatic != null && !cancelSession(automatic, awaitQuiescence = true)) {
            return Result(
                Outcome.BUSY,
                settingOwned = automatic.wroteSetting,
                rollbackPartial = automatic.wroteSetting,
            )
        }
        if (!manualRequestCurrent(request)) {
            return admissionBlockedResult().copy(outcome = Outcome.CANCELLED)
        }
        var waiting = active?.takeIf { it.owner == Owner.MANUAL && it.waitingForDialog }
        if (waiting != null && !sessionFresh(waiting)) {
            val reconciled = cancelSession(waiting, awaitQuiescence = true)
            if (!reconciled) {
                return Result(
                    Outcome.BUSY,
                    settingOwned = waiting.wroteSetting,
                    rollbackPartial = waiting.wroteSetting,
                )
            }
            waiting = null
        }
        val session = if (waiting != null) {
            beforeWaitingTransfer()
            val replacement = replaceWaitingSession(
                waiting, request?.cancelled, request?.isCallerCurrent,
            )
            replacement.session ?: return Result(
                if (!manualRequestCurrent(request)) Outcome.CANCELLED else Outcome.BUSY,
                settingOwned = replacement.settingOwned,
                rollbackPartial = replacement.rollbackPartial,
            )
        } else {
            beginManual(request) ?: return if (!manualRequestCurrent(request)) {
                admissionBlockedResult().copy(outcome = Outcome.CANCELLED)
            } else {
                admissionBlockedResult()
            }
        }
        return executeSession(session, allowRetryWave = false)
    }

    fun recoverManualAsync(onComplete: (Result) -> Unit): Boolean =
        recoverManualAsync(unscopedManualCaller, onComplete)

    /** Carry the parent's cancellation and full mode authority through the asynchronous handoff. */
    fun recoverManualAsync(isCallerCurrent: () -> Boolean, onComplete: (Result) -> Unit): Boolean {
        val request = synchronized(manualPendingLock) {
            pendingManual?.let {
                if (it.isCallerCurrent !== isCallerCurrent || !manualRequestCurrent(it)) return false
                it.callbacks += onComplete
                return true
            }
            PendingManualRequest(
                nextManualRequestId(),
                mutableListOf(onComplete),
                isCallerCurrent,
            ).also { pendingManual = it }
        }
        return try {
            dispatchExecutor.execute {
                synchronized(manualPendingLock) {
                    if (pendingManual === request) request.dispatched = true
                }
                val result = if (!manualRequestCurrent(request)) {
                    admissionBlockedResult().copy(outcome = Outcome.CANCELLED)
                } else {
                    recoverManual(request)
                }
                val callbacks = synchronized(manualPendingLock) {
                    if (pendingManual === request) pendingManual = null
                    request.callbacks.toList().also { request.callbacks.clear() }
                }
                callbacks.forEach { callback ->
                    try { callback(result) } catch (_: Throwable) {}
                }
            }
            true
        } catch (_: Throwable) {
            synchronized(manualPendingLock) {
                if (pendingManual === request) pendingManual = null
            }
            false
        }
    }

    fun triggerAutomatic() {
        if (!gateway.automaticRecoveryAllowed()) return
        enqueueAutomaticScope(gateway.networkIdentity() ?: AUTOMATIC_SCOPE_ANY)
        scheduleAutomaticDrain()
    }

    /** Cancels lifecycle-owned automatic work without blocking the service main thread. */
    fun cancelAutomatic() {
        synchronized(automaticPendingLock) { automaticPendingScopes.clear() }
        val target = active?.takeIf { it.owner == Owner.AUTOMATIC } ?: return
        if (target.cancelled.compareAndSet(false, true)) notifyInvalidated(target)
        // Target the captured generation. An owner created later must not be cancelled by
        // this queued lifecycle cleanup.
        executor.execute { cancelSession(target, awaitQuiescence = true) }
    }

    private fun scheduleAutomaticDrain() {
        if (!automaticDrainScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val requestedScope = takeAutomaticScope() ?: break
                    if (!gateway.automaticRecoveryAllowed()) continue
                    val automaticEnabled = loadState().automaticRestore
                    if (automaticEnabled && !automaticScopeMatchesCurrent(requestedScope)) {
                        enqueueAutomaticScope(requestedScope)
                        return@execute
                    }
                    val currentAutomatic = active?.takeIf { it.owner == Owner.AUTOMATIC }
                    val session = when {
                        currentAutomatic == null -> null
                        automaticEnabled && automaticScopeMatchesCurrent(requestedScope) &&
                            currentAutomatic.waitingForDialog && sessionFresh(currentAutomatic) -> {
                            // A routine trigger may be the first event after the user approves the
                            // system dialog. Transfer the exact setting owner into the continuation;
                            // cancelling here would restore the approved setting to its prior value.
                            replaceWaitingSession(currentAutomatic).session
                        }
                        else -> {
                            if (!cancelSession(currentAutomatic, awaitQuiescence = true)) {
                                enqueueAutomaticScope(requestedScope)
                                return@execute
                            }
                            null
                        }
                    }
                    if (!automaticEnabled) continue
                    if (!automaticScopeMatchesCurrent(requestedScope)) {
                        enqueueAutomaticScope(requestedScope)
                        if (session != null) {
                            // A network edge raced replacement publication. Always execute/finalize
                            // the published continuation; never leave its completion latch orphaned.
                            executeSession(session, allowRetryWave = true)
                            continue
                        }
                        return@execute
                    }
                    val next = session ?: begin(Owner.AUTOMATIC)
                    if (next == null) {
                        enqueueAutomaticScope(requestedScope)
                        return@execute
                    }
                    executeSession(next, allowRetryWave = true)
                }
            } finally {
                automaticDrainScheduled.set(false)
                if (canDrainAutomaticNow()) scheduleAutomaticDrain()
            }
        }
    }

    private fun enqueueAutomaticScope(scope: String) {
        synchronized(automaticPendingLock) { automaticPendingScopes.add(scope) }
    }

    private fun takeAutomaticScope(): String? {
        val current = gateway.networkIdentity()
        return synchronized(automaticPendingLock) {
            val selected = when {
                current != null && current in automaticPendingScopes -> current
                AUTOMATIC_SCOPE_ANY in automaticPendingScopes -> AUTOMATIC_SCOPE_ANY
                else -> automaticPendingScopes.firstOrNull()
            } ?: return@synchronized null
            automaticPendingScopes.remove(selected)
            selected
        }
    }

    private fun automaticScopeMatchesCurrent(scope: String): Boolean =
        scope == AUTOMATIC_SCOPE_ANY || gateway.networkIdentity() == scope

    private fun automaticScopeCanRunNow(): Boolean {
        val current = gateway.networkIdentity()
        return synchronized(automaticPendingLock) {
            AUTOMATIC_SCOPE_ANY in automaticPendingScopes ||
                (current != null && current in automaticPendingScopes)
        }
    }

    fun cancelManual(): Boolean {
        val pending = synchronized(manualPendingLock) {
            pendingManual?.also { it.cancelled.set(true) }
        }
        val target = synchronized(ownerLock) {
            active?.takeIf {
                it.owner == Owner.MANUAL || (it.cancelled.get() && it.wroteSetting)
            }
                ?: if (active == null) recoverOrphanedOwnerForCancellation() else null
        }
        val restored = when {
            target != null -> cancelSession(target, awaitQuiescence = true)
            pending != null -> !persistedOwnershipPending()
            else -> false
        }
        if (restored && canDrainAutomaticNow()) scheduleAutomaticDrain()
        return restored
    }

    /** Advisory, memory-only observation for UI controls; it never authorizes a mutation. */
    fun hasManualRecoveryOwner(): Boolean {
        val current = active
        return pendingManual != null || current?.owner == Owner.MANUAL ||
            (current?.cancelled?.get() == true && current.wroteSetting) ||
            (current == null && observedJournalMayBeOwned)
    }

    /** Refresh on a worker thread because the strict durable reader may wait for a file lock. */
    fun recoverySettingsSnapshot(): DiLink5AdbRecoveryStateStore.Snapshot? =
        try { loadState() } catch (_: Exception) { null }

    private fun loadState(): DiLink5AdbRecoveryStateStore.Snapshot = try {
        store.load().also { observedJournalMayBeOwned = it.hasSettingOwnershipJournal }
    } catch (failure: Exception) {
        observedJournalMayBeOwned = true
        throw failure
    }

    private fun persistedOwnershipPending(): Boolean = try {
        loadState().hasSettingOwnershipJournal
    } catch (_: Exception) {
        // An unreadable journal is not evidence that a new settings owner may replace it.
        true
    }

    private fun canDrainAutomaticNow(): Boolean =
        active == null && automaticScopeCanRunNow() && !persistedOwnershipPending()

    private fun admissionBlockedResult(): Result {
        val journal = try { loadState() } catch (_: Exception) { null }
        val owned = active?.wroteSetting == true || journal?.hasSettingOwnershipJournal == true
        return Result(
            Outcome.BUSY,
            settingOwned = owned,
            rollbackPartial = journal == null ||
                (owned && (active == null || active?.cancelled?.get() == true)),
        )
    }

    /** A process-dead journal authorizes only explicit cleanup, never a new transport attempt. */
    private fun recoverOrphanedOwnerForCancellation(): Session? {
        val journal = try { loadState() } catch (_: Exception) { return null }
        if (!journal.hasSettingOwnershipJournal || journal.ownershipJournalInvalid) return null
        val ownerGeneration = journal.settingOwnerGeneration ?: return null
        val ownerNetwork = journal.settingOwnerNetwork ?: return null
        if (journal.priorSettingValue == null) return null
        return Session(Owner.MANUAL, ownerGeneration).apply {
            networkId = ownerNetwork
            wroteSetting = true
            cancelled.set(true)
            finished.countDown()
        }.also { active = it }
    }

    fun setAutomaticRestore(enabled: Boolean): Boolean {
        val saved = try { store.setAutomaticRestore(enabled) } catch (_: Exception) { false }
        if (enabled && saved) {
            try { store.clearExhaustion() } catch (_: Exception) {}
        }
        if (!enabled) {
            synchronized(automaticPendingLock) { automaticPendingScopes.clear() }
            cancel(Owner.AUTOMATIC)
        }
        return saved
    }

    fun setDialogAutoConfirm(enabled: Boolean): Boolean {
        val networkId = if (enabled) gateway.networkIdentity() else null
        if (enabled && (networkId == null || networkId.startsWith(EPHEMERAL_NETWORK_PREFIX))) {
            return false
        }
        return try { store.setDialogAutoConfirm(enabled, networkId) } catch (_: Exception) { false }
    }

    fun isDialogAutoConfirmEnabledForCurrentNetwork(): Boolean {
        val networkId = gateway.networkIdentity() ?: return false
        val state = try { loadState() } catch (_: Exception) { return false }
        return state.dialogAutoConfirm && state.dialogAutoConfirmNetwork == networkId
    }

    fun onDialogActionAttempted(ownerGeneration: Long) {
        executor.execute {
            val current = active ?: return@execute
            val network = current.networkId ?: return@execute
            if (current.generation != ownerGeneration || !current.waitingForDialog ||
                !isEligible(current.generation, network)
            ) return@execute
            val replacement = replaceWaitingSession(current).session ?: return@execute
            // Replacement publication and execution share this one executor turn, so an older
            // automatic drain cannot cancel a continuation queued behind itself.
            executeSession(replacement, allowRetryWave = replacement.owner == Owner.AUTOMATIC)
        }
    }

    private fun replaceWaitingSession(
        current: Session,
        replacementCancellation: AtomicBoolean? = null,
        replacementCallerCurrent: (() -> Boolean)? = null,
    ): WaitingReplacement {
        var invalidated: Session? = null
        val replacement = synchronized(current.mutationLock) {
            synchronized(ownerLock) {
                if (active !== current || current.cancelled.get() || !current.waitingForDialog) {
                    return@synchronized WaitingReplacement()
                }
                if (replacementCancellation?.get() == true ||
                    !callerCurrent(replacementCallerCurrent ?: current.isCallerCurrent)) {
                    return@synchronized WaitingReplacement(
                        settingOwned = current.wroteSetting,
                        rollbackPartial = current.wroteSetting,
                    )
                }
                // Re-read both admission dimensions while holding the ownership mutation locks.
                // The earlier caller check is advisory; Wi-Fi or mode can change before transfer.
                if (!sessionFresh(current)) {
                    current.cancelled.set(true)
                    val restored = !current.wroteSetting || rollbackOwnedSetting(current)
                    if (restored) {
                        active = null
                    } else {
                        current.waitingForDialog = false
                    }
                    invalidated = current
                    return@synchronized WaitingReplacement(
                        settingOwned = current.wroteSetting && !restored,
                        rollbackPartial = current.wroteSetting && !restored,
                    )
                }
                val next = Session(
                    current.owner,
                    generation.incrementAndGet(),
                    cancelled = replacementCancellation ?: AtomicBoolean(false),
                    isCallerCurrent = replacementCallerCurrent ?: current.isCallerCurrent,
                ).apply {
                    networkId = current.networkId
                    admittedModeGeneration = current.admittedModeGeneration
                    wroteSetting = current.wroteSetting
                }
                if (current.wroteSetting &&
                    !store.transferOwnership(current.generation, next.generation)
                ) {
                    current.cancelled.set(true)
                    val restored = rollbackOwnedSetting(current)
                    if (restored) {
                        active = null
                    } else {
                        current.waitingForDialog = false
                    }
                    invalidated = current
                    return@synchronized WaitingReplacement(
                        settingOwned = current.wroteSetting && !restored,
                        rollbackPartial = current.wroteSetting && !restored,
                    )
                }
                current.waitingForDialog = false
                current.cancelled.set(true)
                active = next
                invalidated = current
                WaitingReplacement(session = next)
            }
        }
        invalidated?.let(::notifyInvalidated)
        return replacement
    }

    private fun cancel(owner: Owner, awaitQuiescence: Boolean = true): Boolean {
        val target = active?.takeIf { it.owner == owner } ?: return true
        return cancelSession(target, awaitQuiescence)
    }

    private fun cancelSession(target: Session, awaitQuiescence: Boolean): Boolean {
        target.cancelled.set(true)
        notifyInvalidated(target)
        if (!awaitQuiescence) return true
        val quiesced = try {
            target.finished.await(quiesceTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!quiesced) return false

        // A NEEDS_DIALOG owner has completed execution but is deliberately retained. Retire that
        // exact generation here. Running sessions retire themselves in finalizeSession().
        val stillActive = synchronized(ownerLock) { active === target }
        if (!stillActive) return !target.wroteSetting
        return synchronized(target.mutationLock) {
            val restored = rollbackOwnedSetting(target)
            if (restored) {
                synchronized(ownerLock) {
                    if (active === target) active = null
                }
            } else {
                // Keep the exact cancelled owner as an admission fence. A request on another
                // network must not overwrite unresolved durable ownership from this one.
                target.waitingForDialog = false
            }
            restored
        }
    }

    private fun begin(owner: Owner): Session? = synchronized(ownerLock) {
        if (active != null || persistedOwnershipPending()) return null
        Session(owner, generation.incrementAndGet()).also { active = it }
    }

    private fun beginManual(request: PendingManualRequest?): Session? = synchronized(ownerLock) {
        if (!manualRequestCurrent(request) || active != null || persistedOwnershipPending()) return null
        // Cancel can mark the independently locked ticket while the durable read waits.
        if (!manualRequestCurrent(request)) return null
        Session(
            Owner.MANUAL,
            generation.incrementAndGet(),
            cancelled = request?.cancelled ?: AtomicBoolean(false),
            isCallerCurrent = request?.isCallerCurrent ?: unscopedManualCaller,
        ).also { active = it }
    }

    private fun callerCurrent(check: () -> Boolean): Boolean =
        try { check() } catch (_: Exception) { false }

    private fun manualRequestCurrent(request: PendingManualRequest?): Boolean =
        request == null || (!request.cancelled.get() && callerCurrent(request.isCallerCurrent))

    private fun nextManualRequestId(): Long {
        while (true) {
            val value = manualRequestIds.incrementAndGet()
            if (value > 0L) return value
            manualRequestIds.compareAndSet(value, 0L)
        }
    }

    private fun executeSession(session: Session, allowRetryWave: Boolean): Result {
        return try {
            val result = try {
                run(session, allowRetryWave)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                session.cancelled.set(true)
                Result(Outcome.CANCELLED)
            } catch (_: Exception) {
                if (session.cancelled.get()) Result(Outcome.CANCELLED)
                else Result(Outcome.TRANSPORT_FAILURE)
            }
            finalizeSession(session, result)
        } finally {
            session.finished.countDown()
            if (canDrainAutomaticNow()) scheduleAutomaticDrain()
        }
    }

    private fun finalizeSession(session: Session, proposed: Result): Result =
        synchronized(session.mutationLock) {
            val stillActive = synchronized(ownerLock) { active === session }
            val dialogRetryVerdict = if (proposed.outcome == Outcome.NEEDS_DIALOG) {
                try { loadState().retryVerdict } catch (_: Exception) { null }
            } else null
            val canRetainForDialog = proposed.outcome == Outcome.NEEDS_DIALOG &&
                stillActive && sessionFresh(session)
            if (canRetainForDialog) {
                session.waitingForDialog = true
                return@synchronized proposed.copy(
                    settingOwned = session.wroteSetting,
                    retryVerdict = dialogRetryVerdict,
                )
            }

            val staleOrCancelled = proposed.outcome == Outcome.CANCELLED ||
                session.cancelled.get() || !callerCurrent(session.isCallerCurrent) || !stillActive ||
                (session.admittedModeGeneration != null && !ownerCurrent(session))
            val shouldRollback = session.wroteSetting &&
                (staleOrCancelled ||
                    (proposed.outcome != Outcome.RESTORED &&
                        proposed.outcome != Outcome.CLASSIC_USABLE))
            val ownershipReleased = when {
                !session.wroteSetting -> true
                shouldRollback -> rollbackOwnedSetting(session)
                clearSessionOwnership(session) -> {
                    session.wroteSetting = false
                    true
                }
                else -> false
            }
            if (!staleOrCancelled && proposed.outcome in setOf(
                    Outcome.RESTORED,
                    Outcome.CLASSIC_USABLE,
                )
            ) {
                // Retry bookkeeping cannot prevent owner retirement or hide a failed journal
                // clear after transport succeeded. Preserve the actual transport/cleanup result.
                try { store.clearExhaustion() } catch (_: Exception) {}
            }
            val retryVerdict = if (proposed.outcome == Outcome.NEEDS_DIALOG) dialogRetryVerdict
                else try { loadState().retryVerdict } catch (_: Exception) { null }
            // Journal cleanup and retry bookkeeping can wait for storage. Sample
            // authority after those reads before publishing any success or retiring.
            val cancelledAtCompletion = staleOrCancelled || session.cancelled.get() ||
                !callerCurrent(session.isCallerCurrent) ||
                (session.admittedModeGeneration != null && !ownerCurrent(session))
            val retainFailedOwnership = !ownershipReleased && stillActive
            if (retainFailedOwnership) {
                session.cancelled.set(true)
                session.waitingForDialog = false
            } else {
                synchronized(ownerLock) {
                    if (active === session) active = null
                }
            }
            if (stillActive) notifyInvalidated(session)
            val outcome = if (cancelledAtCompletion) Outcome.CANCELLED else proposed.outcome
            proposed.copy(
                outcome = outcome,
                transportRestored = proposed.transportRestored && !cancelledAtCompletion,
                authenticated = proposed.authenticated && !cancelledAtCompletion,
                helperHealthy = proposed.helperHealthy && !cancelledAtCompletion,
                settingOwned = session.wroteSetting && !ownershipReleased,
                rollbackPartial = proposed.rollbackPartial || !ownershipReleased,
                retryVerdict = retryVerdict,
            )
        }

    private fun run(session: Session, allowRetryWave: Boolean): Result {
        if (session.cancelled.get() || !callerCurrent(session.isCallerCurrent)) return Result(Outcome.CANCELLED)
        if (gateway.apiLevel() < Build.VERSION_CODES.R) return Result(Outcome.UNSUPPORTED_API)
        val currentGeneration = gateway.modeGeneration() ?: return Result(Outcome.MODE_UNAVAILABLE)
        // A dialog continuation inherits the scope of its setting journal. Transferring the
        // generation does not authorize that write on a replacement network or runtime mode.
        // Keep the inherited scope so cancellation can still restore it when its network returns.
        val admittedGeneration = session.admittedModeGeneration ?: currentGeneration.also {
            session.admittedModeGeneration = it
            session.networkId = gateway.networkIdentity()
        }
        val inheritedNetwork = session.networkId
        if (!modeAndNetworkFresh(session, admittedGeneration, inheritedNetwork)) {
            return Result(Outcome.CANCELLED)
        }
        if (gateway.classicUsable()) {
            if (!modeAndNetworkFresh(session, admittedGeneration, inheritedNetwork)) {
                return Result(Outcome.CANCELLED)
            }
            if (!gateway.hasWriteSecureSettings()) {
                if (!modeAndNetworkFresh(session, admittedGeneration, inheritedNetwork)) {
                    return Result(Outcome.CANCELLED)
                }
                if (!gateway.seedWriteSecureSettings()) {
                    return Result(Outcome.MISSING_PERMISSION, authenticated = true)
                }
            }
            val helper = bootstrapHelpersForCurrentNetwork(session)
            if (!modeAndNetworkFresh(session, admittedGeneration, inheritedNetwork)) {
                return Result(Outcome.CANCELLED)
            }
            return Result(
                Outcome.CLASSIC_USABLE,
                transportRestored = true,
                authenticated = true,
                helperStartRequested = helper.requested,
                helperHealthy = helper.healthy,
            )
        }
        if (!gateway.hasWriteSecureSettings()) return Result(Outcome.MISSING_PERMISSION)
        if (!gateway.identityPreviouslyAuthenticated()) return Result(Outcome.TRUST_LOST)
        val network = inheritedNetwork ?: return Result(Outcome.NO_VALIDATED_WIFI)
        if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)

        val state = loadState()
        val setting = gateway.readWirelessSetting() ?: return Result(Outcome.TRANSPORT_FAILURE)
        if (setting != 1) {
            val lastWrite = state.networkWriteTimes[network]
            val cooldownSuppressed = session.owner == Owner.AUTOMATIC && lastWrite != null &&
                clock.wallTimeMs() - lastWrite in 0 until COOLDOWN_MS
            if (cooldownSuppressed) {
                return Result(Outcome.NEEDS_DIALOG, retryVerdict = state.retryVerdict)
            }
            synchronized(session.mutationLock) {
                if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
                if (!store.recordWrite(network, clock.wallTimeMs(), session.generation, setting)) {
                    return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = true)
                }
                session.wroteSetting = true
                if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
                when (gateway.writeWirelessSetting(1)) {
                    WriteResult.REJECTED -> {
                        if (clearSessionOwnership(session)) session.wroteSetting = false
                        return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = session.wroteSetting)
                    }
                    WriteResult.UNCERTAIN -> {
                        reconcileOwnedSetting(session)
                        return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = session.wroteSetting)
                    }
                    WriteResult.CONFIRMED -> Unit
                }
            }
            if (!clock.sleep(SETTING_SETTLE_MS, session.cancelled)) {
                return Result(Outcome.CANCELLED)
            }
            if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
            val settled = gateway.readWirelessSetting()
                ?: return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = session.wroteSetting)
            if (settled != 1) {
                val needsDialog = Result(
                    Outcome.NEEDS_DIALOG,
                    settingOwned = session.wroteSetting,
                    retryVerdict = state.retryVerdict,
                )
                val result = if (allowRetryWave && state.exhaustedNetwork != network) {
                    retryWave(session, admittedGeneration, network, needsDialog)
                } else {
                    needsDialog
                }
                return result.copy(
                    settingOwned = session.wroteSetting,
                    retryVerdict = loadState().retryVerdict,
                )
            }
        }

        val result = attemptTransport(session)
        return result.copy(
            settingOwned = session.wroteSetting,
            retryVerdict = loadState().retryVerdict,
        )
    }

    private fun attemptTransport(session: Session): Result {
        if (!sessionFresh(session)) return Result(Outcome.CANCELLED)
        val first = gateway.discover(session.cancelled, includeProperty = true)
        if (first is DiLink5AdbRecoveryDiscovery.Result.Cancelled) return Result(Outcome.CANCELLED)
        if (first is DiLink5AdbRecoveryDiscovery.Result.NoValidatedWifi) return Result(Outcome.NO_VALIDATED_WIFI)
        var tlsResult: AdbTlsTransport.Result? = null
        if (first is DiLink5AdbRecoveryDiscovery.Result.Found &&
            first.endpoint.networkId == session.networkId && sessionFresh(session)) {
            tlsResult = gateway.openTls(first.endpoint, session.cancelled)
            val mapped = mapTls(session, tlsResult)
            if (mapped.outcome == Outcome.RESTORED || mapped.outcome == Outcome.AUTHORIZATION_REJECTED ||
                mapped.outcome == Outcome.CANCELLED || tlsResult.openDispatched) return mapped
        }
        val fallback = gateway.discover(session.cancelled, includeProperty = false)
        if (fallback !is DiLink5AdbRecoveryDiscovery.Result.Found ||
            fallback.endpoint.networkId != session.networkId || !sessionFresh(session)) {
            return if (tlsResult == null) Result(Outcome.NO_LISTENER) else mapTls(session, tlsResult)
        }
        return mapTls(session, gateway.openTls(fallback.endpoint, session.cancelled))
    }

    private fun mapTls(session: Session, tls: AdbTlsTransport.Result): Result {
        if (tls is AdbTlsTransport.Result.Cancelled) return Result(Outcome.CANCELLED)
        if (tls === AdbTlsTransport.Result.AuthorizationRejected) {
            return Result(Outcome.AUTHORIZATION_REJECTED)
        }
        if (tls.openDispatched) return completePostOpen(session)
        return when (tls) {
            is AdbTlsTransport.Result.TlsFailure -> Result(Outcome.TLS_FAILURE)
            is AdbTlsTransport.Result.Timeout,
            is AdbTlsTransport.Result.TransportFailure -> Result(Outcome.TRANSPORT_FAILURE)
            else -> Result(Outcome.TRANSPORT_FAILURE)
        }
    }

    private fun completePostOpen(session: Session): Result {
        if (!sessionFresh(session)) return Result(Outcome.CANCELLED)
        if (!proveFreshClassicAfterRestart(session)) return Result(Outcome.TRANSPORT_FAILURE)
        if (!sessionFresh(session)) return Result(Outcome.CANCELLED)
        val helper = bootstrapHelpersForCurrentNetwork(session)
        if (!sessionFresh(session)) return Result(Outcome.CANCELLED)
        return Result(
            Outcome.RESTORED,
            transportRestored = true,
            authenticated = true,
            helperStartRequested = helper.requested,
            helperHealthy = helper.healthy,
        )
    }

    private fun proveFreshClassicAfterRestart(session: Session): Boolean {
        val started = clock.elapsedTimeMs()
        repeat(CLASSIC_POLL_ATTEMPTS) {
            if (!sessionFresh(session)) return false
            if (!clock.sleep(CLASSIC_POLL_INTERVAL_MS, session.cancelled)) return false
            val remaining = CLASSIC_POLL_BUDGET_MS - (clock.elapsedTimeMs() - started)
            if (remaining <= 0L) return false
            val proved = gateway.proveFreshClassic(
                minOf(CLASSIC_PROBE_TIMEOUT_MS, remaining).coerceAtLeast(1L).toInt(),
            ) { publish ->
                synchronized(session.mutationLock) {
                    if (!sessionFresh(session)) false
                    else {
                        publish()
                        true
                    }
                }
            }
            if (proved) return true
        }
        return false
    }

    private fun ownerCurrent(session: Session): Boolean {
        val admitted = session.admittedModeGeneration ?: return false
        return try {
            val allowed = session.owner == Owner.MANUAL ||
                (loadState().automaticRestore && gateway.automaticRecoveryAllowed())
            allowed && modeAndNetworkFresh(session, admitted, session.networkId)
        } catch (_: Exception) {
            false
        }
    }

    private fun modeAndNetworkFresh(
        session: Session,
        admittedGeneration: String,
        admittedNetwork: String?,
    ): Boolean = !session.cancelled.get() && callerCurrent(session.isCallerCurrent) && active === session &&
        gateway.modeGeneration() == admittedGeneration &&
        gateway.networkIdentity() == admittedNetwork

    private fun bootstrapHelpersForCurrentNetwork(session: Session): HelperBootstrap {
        if (!ownerCurrent(session)) return HelperBootstrap(false, false)
        val requested = gateway.startHelpers()
        if (!requested || !ownerCurrent(session)) return HelperBootstrap(requested, false)
        val network = session.networkId
        val state = loadState()
        if (network != null && gateway.networkIdentity() == network && state.dialogAutoConfirm &&
            state.dialogAutoConfirmNetwork == network && ownerCurrent(session)
        ) {
            gateway.enableAccessibilityHelper {
                loadState().let {
                        it.dialogAutoConfirm && it.dialogAutoConfirmNetwork == network
                    } && ownerCurrent(session) && gateway.networkIdentity() == network
            }
        }
        var healthy = ownerCurrent(session) && gateway.helpersHealthy()
        repeat(HELPER_HEALTH_ATTEMPTS - 1) {
            if (healthy || !ownerCurrent(session) ||
                !clock.sleep(HELPER_HEALTH_POLL_MS, session.cancelled)
            ) return@repeat
            healthy = ownerCurrent(session) && gateway.helpersHealthy()
        }
        return HelperBootstrap(requested, healthy)
    }

    private fun retryWave(
        session: Session,
        admittedGeneration: String,
        network: String,
        initial: Result,
    ): Result {
        val started = clock.elapsedTimeMs()
        var lastResult = initial
        var lastWriteElapsed = started
        var delayIndex = 0
        while (true) {
            val delay = if (delayIndex < RETRY_INITIAL_DELAYS_MS.size) {
                RETRY_INITIAL_DELAYS_MS[delayIndex]
            } else {
                RETRY_WRITE_SPACING_MS
            }
            delayIndex++
            if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
            val elapsed = (clock.elapsedTimeMs() - started).coerceAtLeast(0L)
            val remainingBudget = (RETRY_BUDGET_MS - elapsed).coerceAtLeast(0L)
            if (delay > remainingBudget ||
                !clock.sleep(delay.coerceAtLeast(0L), session.cancelled)
            ) {
                break
            }
            val currentSetting = gateway.readWirelessSetting()
                ?: return Result(Outcome.TRANSPORT_FAILURE)
            if (currentSetting == 1) {
                val transport = attemptTransport(session)
                if (transport.outcome != Outcome.NEEDS_DIALOG) {
                    store.recordRetry(network, transport.outcome.name, false)
                    return transport
                }
                lastResult = transport
                continue
            }
            // Poll on the documented 2/5/10/15-second cadence, but never write the
            // setting more often than once every 15 seconds.
            if (clock.elapsedTimeMs() - lastWriteElapsed < RETRY_WRITE_SPACING_MS) continue
            synchronized(session.mutationLock) {
                if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
                if (!store.recordWrite(network, clock.wallTimeMs(), session.generation, currentSetting)) {
                    return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = true)
                }
                session.wroteSetting = true
                if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
                when (gateway.writeWirelessSetting(1)) {
                    WriteResult.CONFIRMED -> {
                        lastWriteElapsed = clock.elapsedTimeMs()
                    }
                    WriteResult.UNCERTAIN -> {
                        reconcileOwnedSetting(session)
                        return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = session.wroteSetting)
                    }
                    WriteResult.REJECTED -> {
                        if (clearSessionOwnership(session)) session.wroteSetting = false
                        val rejected = Result(
                            Outcome.TRANSPORT_FAILURE,
                            rollbackPartial = session.wroteSetting,
                        )
                        store.recordRetry(network, rejected.outcome.name, false)
                        return rejected
                    }
                }
            }
            if (!clock.sleep(SETTING_SETTLE_MS, session.cancelled)) return Result(Outcome.CANCELLED)
            if (!fresh(session, admittedGeneration, network)) return Result(Outcome.CANCELLED)
            val settledSetting = gateway.readWirelessSetting()
                ?: return Result(Outcome.TRANSPORT_FAILURE, rollbackPartial = session.wroteSetting)
            if (settledSetting != 1) {
                lastResult = Result(Outcome.NEEDS_DIALOG, settingOwned = session.wroteSetting)
                continue
            }
            val transport = attemptTransport(session)
            if (transport.outcome != Outcome.NEEDS_DIALOG) {
                store.recordRetry(network, transport.outcome.name, false)
                return transport
            }
            lastResult = transport
        }
        store.recordRetry(network, lastResult.outcome.name, true)
        return lastResult
    }

    private fun sessionFresh(session: Session): Boolean {
        val admitted = session.admittedModeGeneration ?: return false
        val network = session.networkId ?: return false
        return fresh(session, admitted, network)
    }

    private fun fresh(session: Session, admittedGeneration: String, network: String): Boolean = try {
        val allowed = session.owner == Owner.MANUAL ||
            (loadState().automaticRestore && gateway.automaticRecoveryAllowed())
        allowed && modeAndNetworkFresh(session, admittedGeneration, network)
    } catch (_: Exception) {
        false
    }

    private fun reconcileOwnedSetting(session: Session): Boolean {
        if (!session.wroteSetting) return true
        val state = loadState()
        if (state.ownershipJournalInvalid || state.settingOwnerGeneration != session.generation ||
            state.settingOwnerNetwork != session.networkId ||
            gateway.networkIdentity() != session.networkId) return false
        val observed = gateway.readWirelessSetting() ?: return false
        val prior = state.priorSettingValue ?: return false
        if (gateway.networkIdentity() != session.networkId) return false
        if (observed == prior && clearSessionOwnership(session)) {
            session.wroteSetting = false
            return true
        }
        return observed == 1
    }

    private fun rollbackOwnedSetting(session: Session): Boolean {
        if (!session.wroteSetting) return true
        return try {
            val state = loadState()
            if (state.ownershipJournalInvalid || session.networkId.isNullOrBlank() ||
                state.settingOwnerGeneration != session.generation ||
                state.settingOwnerNetwork != session.networkId ||
                gateway.networkIdentity() != session.networkId) return false
            val prior = state.priorSettingValue ?: return false
            val current = gateway.readWirelessSetting() ?: return false
            if (gateway.networkIdentity() != session.networkId) return false
            if (current == prior) {
                if (!clearSessionOwnership(session)) return false
                session.wroteSetting = false
                return true
            }
            if (current != 1) return false
            val restored = gateway.writeWirelessSetting(prior) == WriteResult.CONFIRMED
            if (!restored || gateway.networkIdentity() != session.networkId ||
                gateway.readWirelessSetting() != prior ||
                gateway.networkIdentity() != session.networkId
            ) return false
            if (!clearSessionOwnership(session)) return false
            session.wroteSetting = false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearSessionOwnership(session: Session): Boolean = try {
        store.clearOwnership(session.generation).also { cleared ->
            observedJournalMayBeOwned = !cleared
        }
    } catch (_: Exception) {
        observedJournalMayBeOwned = true
        false
    }

    private class AndroidGateway(private val context: Context) : Gateway {
        private val discovery = DiLink5AdbRecoveryDiscovery(context)
        // Coordinator probes must never recursively enqueue another recovery attempt.
        private val executor = AdbShellExecutor(context, automaticRecoveryRequestsAllowed = false)
        private val identityResult by lazy { AdbIdentityProvider.forFilesDirectory(context.filesDir).load() }
        private val certificate by lazy {
            AdbCertificateStore(context.filesDir).loadOrCreate(identityResult.identity).certificate
        }

        override fun apiLevel() = Build.VERSION.SDK_INT
        override fun automaticRecoveryAllowed(): Boolean =
            DiLink5AdbAutomaticRecoveryGate.allowed()
        override fun modeGeneration(): String? {
            DiLink5Platform.refreshActiveModeFromCommittedMarker()
            return DiLink5Platform.currentActiveModeSnapshot()
                ?.takeIf { it.mode == "dilink5" }?.generation
        }
        override fun classicUsable(): Boolean = try {
            val marker = "OD_ADB_RECOVERY_PROBE"
            val result = executor.executeSync("printf '$marker'", 3_000)
            result.exitCode == 0 && result.output.contains(marker)
        } catch (_: Exception) {
            false
        }
        override fun identityPreviouslyAuthenticated() =
            AdbIdentityProvider.forFilesDirectory(context.filesDir).load().previouslyAuthenticated
        override fun hasWriteSecureSettings() = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        override fun seedWriteSecureSettings(): Boolean = try {
            executor.executeSync("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", 5_000)
            hasWriteSecureSettings()
        } catch (_: Exception) {
            false
        }
        override fun networkIdentity() = discovery.currentNetwork()?.id
        override fun readWirelessSetting(): Int? = try {
            Settings.Global.getInt(context.contentResolver, WIRELESS_SETTING, 0)
        } catch (_: Exception) { null }
        override fun writeWirelessSetting(value: Int): WriteResult = try {
            if (!Settings.Global.putInt(context.contentResolver, WIRELESS_SETTING, value)) WriteResult.REJECTED
            else if (readWirelessSetting() == value) WriteResult.CONFIRMED else WriteResult.UNCERTAIN
        } catch (_: SecurityException) { WriteResult.REJECTED }
        catch (_: Exception) { WriteResult.UNCERTAIN }
        override fun discover(cancelled: AtomicBoolean, includeProperty: Boolean) =
            discovery.discover(DISCOVERY_TIMEOUT_MS, cancelled, includeProperty)
        override fun openTls(
            endpoint: DiLink5AdbRecoveryDiscovery.Endpoint,
            cancelled: AtomicBoolean,
        ): AdbTlsTransport.Result {
            if (!identityResult.previouslyAuthenticated) {
                return AdbTlsTransport.Result.AuthorizationRejected
            }
            val transport = AdbTlsTransport(identityResult.identity, certificate)
            val complete = AtomicBoolean(false)
            val cancellationWatcher = Thread({
                while (!complete.get() && !cancelled.get()) {
                    try { Thread.sleep(25) } catch (_: InterruptedException) { return@Thread }
                }
                if (cancelled.get()) transport.close()
            }, "AdbTlsCancel").apply { isDaemon = true }
            cancellationWatcher.start()
            return try {
                transport.use { it.openClassicListener(endpoint.address, endpoint.port) }
            } finally {
                complete.set(true)
                cancellationWatcher.interrupt()
            }
        }
        override fun proveFreshClassic(
            timeoutMs: Int,
            publishIfCurrent: ((() -> Unit) -> Boolean),
        ) = executor.installFreshAuthenticatedConnection(
            timeoutMs = timeoutMs,
            publishIfCurrent = publishIfCurrent,
        )

        override fun startHelpers(): Boolean = try {
            com.overdrive.app.ui.daemon.DaemonStartupManager.startOnBoot(context)
            true
        } catch (_: Exception) {
            false
        }

        override fun helpersHealthy(): Boolean = try {
            val result = executor.executeSync("pidof byd_cam_daemon", 3_000)
            result.exitCode == 0 && result.output.trim().split(Regex("\\s+"))
                .any { it.toIntOrNull()?.let { pid -> pid > 0 } == true }
        } catch (_: Exception) {
            false
        }

        override fun enableAccessibilityHelper(isCurrent: () -> Boolean): Boolean {
            if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) return true
            if (!isCurrent() || !accessibilityAttempted.compareAndSet(false, true)) return false

            val resolver = context.contentResolver
            val full = "${context.packageName}/${context.packageName}.services.KeepAliveAccessibilityService"
            val short = "${context.packageName}/.services.KeepAliveAccessibilityService"
            val priorRaw: String?
            val priorEnabled: Int
            try {
                priorRaw = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                priorEnabled = Settings.Secure.getInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                )
            } catch (_: Throwable) {
                return false
            }

            val plan = AccessibilityServiceList.plan(priorRaw, full, short)
            val keptRaw = plan.withoutTarget
            val nextRaw = plan.withTarget

            fun restoreIfOwned(expected: String) {
                try {
                    val current = Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ).orEmpty()
                    if (current == expected) {
                        Settings.Secure.putString(
                            resolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            priorRaw,
                        )
                        Settings.Secure.putInt(
                            resolver,
                            Settings.Secure.ACCESSIBILITY_ENABLED,
                            priorEnabled,
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            if (!isCurrent()) return false
            val removed = try {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    keptRaw,
                )
            } catch (_: Throwable) {
                false
            }
            if (!removed) return false

            val settleDeadline = android.os.SystemClock.elapsedRealtime() + 200L
            while (android.os.SystemClock.elapsedRealtime() < settleDeadline) {
                if (!isCurrent()) {
                    restoreIfOwned(keptRaw)
                    return false
                }
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    restoreIfOwned(keptRaw)
                    return false
                }
            }
            if (!isCurrent()) {
                restoreIfOwned(keptRaw)
                return false
            }

            val added = try {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    nextRaw,
                ) && Settings.Secure.putInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1,
                )
            } catch (_: Throwable) {
                false
            }
            if (!added || !isCurrent()) {
                restoreIfOwned(nextRaw)
                return false
            }
            val confirmed = try {
                val readback = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                val globallyEnabled = Settings.Secure.getInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                ) == 1
                AccessibilityServiceList.isEnabled(
                    readback,
                    globallyEnabled,
                    full,
                    short,
                ) && isCurrent()
            } catch (_: Throwable) {
                false
            }
            if (!confirmed) restoreIfOwned(nextRaw)
            return confirmed
        }
    }

    private object SystemClock : Clock {
        override fun wallTimeMs() = java.lang.System.currentTimeMillis()
        override fun elapsedTimeMs() = android.os.SystemClock.elapsedRealtime()
        override fun sleep(ms: Long, cancelled: AtomicBoolean): Boolean {
            if (ms <= 0L) return !cancelled.get()
            val started = elapsedTimeMs()
            while (!cancelled.get()) {
                val elapsed = (elapsedTimeMs() - started).coerceAtLeast(0L)
                val remaining = (ms - elapsed).coerceAtLeast(0L)
                if (remaining == 0L) break
                try { Thread.sleep(minOf(100L, remaining)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return !cancelled.get()
        }
    }

    companion object {
        private const val WIRELESS_SETTING = "adb_wifi_enabled"
        private const val AUTOMATIC_SCOPE_ANY = "<current-network>"
        private const val EPHEMERAL_NETWORK_PREFIX = "ephemeral:"
        private const val SETTING_SETTLE_MS = 1_500L
        private const val DISCOVERY_TIMEOUT_MS = 45_000L
        private const val CLASSIC_POLL_ATTEMPTS = 15
        private const val CLASSIC_POLL_INTERVAL_MS = 1_000L
        private const val CLASSIC_POLL_BUDGET_MS = 30_000L
        private const val CLASSIC_PROBE_TIMEOUT_MS = 2_000L
        private const val HELPER_HEALTH_ATTEMPTS = 4
        private const val HELPER_HEALTH_POLL_MS = 250L
        private const val COOLDOWN_MS = 10L * 60 * 1000
        private const val RETRY_WRITE_SPACING_MS = 15_000L
        private const val RETRY_BUDGET_MS = 5L * 60 * 1000
        private const val TRANSPORT_QUIESCE_TIMEOUT_MS = 35_000L
        private val RETRY_INITIAL_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 15_000)
        private val accessibilityAttempted = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AdbRecovery").apply { isDaemon = true }
        }
        @Volatile private var instance: DiLink5AdbRecoveryCoordinator? = null

        @JvmStatic
        fun get(context: Context): DiLink5AdbRecoveryCoordinator = instance
            ?: synchronized(this) {
                instance ?: DiLink5AdbRecoveryCoordinator(
                    AndroidGateway(context.applicationContext),
                    DiLink5AdbRecoveryStateStore(),
                ).also { instance = it }
            }
    }
}

internal object AccessibilityServiceList {
    data class Plan(val withoutTarget: String, val withTarget: String)

    fun plan(raw: String?, fullComponent: String, shortComponent: String): Plan {
        val preserved = LinkedHashSet(
            raw.orEmpty().split(':').map(String::trim).filter(String::isNotEmpty)
                .filterNot { it == fullComponent || it == shortComponent },
        )
        return Plan(
            preserved.joinToString(":"),
            (preserved + fullComponent).joinToString(":"),
        )
    }

    fun containsTarget(raw: String?, fullComponent: String, shortComponent: String): Boolean =
        raw.orEmpty().split(':').map(String::trim).any {
            it == fullComponent || it == shortComponent
        }

    fun isEnabled(
        raw: String?,
        globallyEnabled: Boolean,
        fullComponent: String,
        shortComponent: String,
    ): Boolean = globallyEnabled && containsTarget(raw, fullComponent, shortComponent)
}

internal object DiLink5AdbAutomaticRecoveryGate {
    fun allowed(): Boolean = try {
        !java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists() ||
            com.overdrive.app.ui.daemon.DaemonStartupManager.recoveryInProgress
    } catch (_: Throwable) {
        false
    }
}
