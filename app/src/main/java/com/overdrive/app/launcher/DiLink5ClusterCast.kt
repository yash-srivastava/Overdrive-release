package com.overdrive.app.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.overdrive.app.camera.dilink5.ClusterTransport
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.customlayouts.model.Panel
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.launcher.dilink5.cluster.AaosNavigationPresentation
import com.overdrive.app.launcher.dilink5.cluster.DiLink5HostedProjectionBridge
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionOwnership
import com.overdrive.app.launcher.dilink5.cluster.HostedProjectionGeometry
import com.overdrive.app.launcher.dilink5.cluster.Sealion7HdmiProjection
import com.overdrive.app.monitor.AccMonitor
import com.overdrive.app.navmap.cluster.DiLink5InstrumentFullSession
import com.overdrive.app.ui.view.AaosMapAreaCheck
import com.overdrive.app.surveillance.ClusterMirrorController
import com.overdrive.app.surveillance.ClusterProjectionController
import com.overdrive.app.surveillance.ClusterViewMirrorService
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val DI5_MIN_WINDOW_PX = 200
private const val DI5_SHARED_DISPLAY_FULL =
    "shared_fission_bg_XDJAScreenProjection_0"
private const val DI5_SHARED_DISPLAY_STANDARD =
    "shared_fission_bg_XDJAScreenProjection_1"
private const val DI5_DISPLAY_FULL = "XDJAScreenProjection_0"
private const val DI5_DISPLAY_STANDARD = "XDJAScreenProjection_1"
private const val DI5_MAP_PACKAGE = "com.overdrive.app"
private const val DI5_MAP_COMPONENT =
    "com.overdrive.app/.navmap.RoadSenseClusterMapActivity"
private const val DI5_DEBUG_DISPLAY = "fission_bg_XDJAScreenProjection"
// Custom Layouts finishes itself on its next heartbeat reply (1 s cadence).
private const val CUSTOM_FINISH_GRACE_MS = 1_500L

/**
 * DI5 app projection through the OEM's own cluster display. On the UK Seal the
 * unnumbered fission display feeds a background behind the native instruments.
 * A task on that display can render successfully while the instrument panel shows only arrows.
 */
object DiLink5ClusterCast {
    private val logger = DaemonLogger.getInstance("DiLink5ClusterCast")

    private const val OPERATION_TIMEOUT_MS = 20_000L
    private const val VERIFY_ATTEMPTS = 2
    private const val VERIFY_POLL_MS = 300L
    private const val START_FOREGROUND_POLL_MS = 500L
    private const val START_FOREGROUND_STABLE_MS = 3_000L
    private const val START_FOREGROUND_OBSERVE_MS = 6_500L
    private const val START_CORRECTION_COOLDOWN_MS = 750L
    private const val CONTAINER_SETTLE_MS = 250L
    private const val PROJECTION_DISPLAY_TIMEOUT_MS = 8_000L
    private const val PROJECTION_DISPLAY_POLL_MS = 200L
    private const val CONTAINER_CLOSE_GAP_MS = 1_000L
    private const val INDETERMINATE_ENABLE_FENCE_MS = 3_000L
    private const val ENABLE_FENCE_POLL_MS = 200L
    private const val MAP_FINISH_GRACE_MS = 650L
    private const val DEFERRED_REHOME_DELAY_MS = 1_500L
    private const val TASK_GUARD_POLL_MS = 1_000L
    private const val TASK_GUARD_INITIAL_MS = 30_000L
    private const val TASK_GUARD_AFTER_REANCHOR_MS = 15_000L
    private const val TASK_GUARD_MAX_MS = 90_000L
    private const val TASK_GUARD_UNKNOWN_RETRY_COUNT = 3
    private const val TASK_GUARD_FORCE_RESTART_COUNT = 5
    private const val TASK_GUARD_MAX_FOREGROUND_TAKEOVERS = 5
    private const val LIFECYCLE_GUARD_POLL_MS = 5_000L
    private const val LIFECYCLE_GUARD_FAILURES = 2
    private const val MARKER_PENDING = "di5ClusterProjectionPending"
    private const val MARKER_POWERED = "di5ClusterProjectionPowered"
    private const val MARKER_APP_MOVED = "di5ClusterProjectionAppMoved"
    private const val MARKER_ENABLE_INDETERMINATE =
        "di5ClusterProjectionEnableIndeterminate"
    private const val MARKER_ENABLE_BOOT_ID =
        "di5ClusterProjectionEnableBootId"
    private const val MARKER_TEARDOWN_INDETERMINATE =
        "di5ClusterProjectionTeardownIndeterminate"
    private const val MARKER_TEARDOWN_BOOT_ID =
        "di5ClusterProjectionTeardownBootId"
    private const val MARKER_PACKAGE = "di5ClusterProjectionPackage"
    private const val PREFER_FULL_KEY = "di5PreferFullCluster"
    /** projection.<key>: the shared display that last survived an OEM takeover
     *  fallback (empty = none learned). First choice on later casts. */
    private const val SHARED_DISPLAY_PREFERENCE_KEY = "di5SharedDisplayPreference"
    /** How long a display reclaimed by an OEM cluster app is skipped when an
     *  alternative shared display exists. Long enough to cover a drive; short
     *  enough that a once-off OEM re-front does not pin us to the other surface. */
    private const val RECLAIMED_DISPLAY_MEMORY_MS = 30 * 60_000L
    private const val LEGACY_RECOVERY_RESUME_POLL_MS = 250L
    private val RECOVERY_RETRY_DELAYS_MS =
        longArrayOf(1_000L, 3_000L, 8_000L)

    private val stateLock = Any()
    /** Serializes task/density/freeform dispatch with stop's session invalidation. */
    private val taskMutationLock = TaskMutationAuthority()
    private val operations = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiLink5ClusterProjection").apply { isDaemon = true }
    }
    private val deferredCleanup =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DiLink5ClusterDeferredCleanup").apply {
                isDaemon = true
            }
        }

    @Volatile private var activePackage: String? = null
    @Volatile private var activeKind: SessionKind? = null
    @Volatile private var generation = 0L
    @Volatile private var stopping = false
    @Volatile private var session: Session? = null
    @Volatile private var phase = SessionPhase.IDLE
    @Volatile private var lastFailureReason = ""
    @Volatile private var pendingStart: StartTicket? = null
    @Volatile private var poweredGeneration = 0L
    @Volatile private var enableIndeterminateGeneration = 0L
    @Volatile private var teardownIndeterminateGeneration = 0L
    @Volatile private var teardownIndeterminateBootId = ""
    @Volatile private var appMovedGeneration = 0L
    @Volatile private var cleanupUncertain = false
    @Volatile private var activeCleanup: CleanupTicket? = null
    private var lastCustomCleanup: CleanupTicket? = null // stateLock, retained for a racing owner release
    @Volatile private var legacyProjectionRecoveryBlocked = false
    @Volatile private var legacyRecoveryResumeScheduled = false
    @Volatile private var recoveryRetryTicket = 0L
    private val hostedResourceSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val uncertainHostedResources = HashMap<Long, MutableSet<Long>>()
    /** Unpublished replacements; retired predecessors remain in cleanup ownership only. */
    private val pendingHostedReplacements = HashSet<Long>()
    private val lifecycleGuardians = HashSet<String>()
    /** Shared display name (lowercase) → elapsedRealtime when an OEM cluster app
     *  last reclaimed its foreground from our session. Guarded by [stateLock]. */
    private val reclaimedDisplays = HashMap<String, Long>()

    /** MAP and CUSTOM are OverDrive activities. APP may be re-homed to the head unit on stop. */
    private enum class SessionKind { APP, MAP, CUSTOM }

    /** Live Custom Layouts session; [displayId] is -1 until the start picked a display. */
    data class CustomLayoutsTarget(
        val generation: Long,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val band: Boolean,
        val active: Boolean,
    )
    private enum class SessionPhase {
        IDLE, PREPARING, ACTIVE, STOPPING, RECOVERING, FAILED
    }

    private data class LaunchTarget(
        val pkg: String,
        val component: String,
        val kind: SessionKind,
        val customOwner: Long = 0L,
        val previousCustomGeneration: Long = 0L,
        val automatic: Boolean = false,
    )

    private data class StartTicket(
        val generation: Long,
        val completion: CountDownLatch = CountDownLatch(1),
        val succeeded: AtomicBoolean = AtomicBoolean(false)
    )

    private data class CleanupTicket(
        val completion: CountDownLatch = CountDownLatch(1),
        val succeeded: AtomicBoolean = AtomicBoolean(false),
        val customGeneration: Long = 0L,
    )

    private data class CleanupResult(
        val rehomed: Boolean,
        val taskCleanupSatisfied: Boolean,
        val poweredDown: Boolean,
        val appStillMoved: Boolean,
        val targetDisplayId: Int
    ) {
        val complete: Boolean get() = taskCleanupSatisfied && poweredDown
    }

    internal data class LegacyBootRecoveryOutcome(
        val recovered: Boolean,
        val sameBootIndeterminate: Boolean,
        /**
         * The gauge restore reached a definitive verdict (recovered, or the
         * legacy protocol is unsupported/absent on this firmware). Terminal
         * outcomes end the bounded retry ladder AND release the cross-mode
         * legacy fence: the fence exists to order recovery against new
         * projections, not to encode "this firmware can't do it" forever.
         */
        val terminal: Boolean,
        val unsupported: Boolean = false
    )

    @JvmStatic
    internal fun combineLegacyBootRecoveryOutcome(
        taskRecovered: Boolean,
        gaugeRecovery:
            ClusterProjectionController.LegacyBootRecoveryResult
    ): LegacyBootRecoveryOutcome {
        return LegacyBootRecoveryOutcome(
            recovered = taskRecovered && gaugeRecovery.isRecovered,
            sameBootIndeterminate =
                gaugeRecovery.isSameBootIndeterminate,
            // The stranded-task half must also have settled: an unsupported
            // gauge verdict with an unrecovered task still ends the ladder
            // (the task sweep is idempotent and re-runs at the next boot),
            // but a plain task failure with a recovered gauge stays retryable.
            terminal = gaugeRecovery.isTerminal &&
                (taskRecovered || gaugeRecovery.isUnsupported),
            unsupported = gaugeRecovery.isUnsupported
        )
    }

    private data class Session(
        val generation: Long,
        val pkg: String,
        val component: String,
        val kind: SessionKind,
        /** OEM panel identity used for mirror/compositor ownership. */
        val physicalDisplayId: Int,
        /** Display that owns the launched task; differs from the panel in hosted mode. */
        val targetDisplayId: Int,
        val targetDisplayName: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val projectionTransport: String = "direct",
        val hostedResourceGeneration: Long = 0L,
        val hostedGeometry: HostedProjectionGeometry? = null,
        val factoryMiniMap: Boolean = false,
        val aaosMapArea: Boolean = false,
        val sealion7Hdmi: Boolean = false,
    ) {
        val usesHdmiPanel: Boolean get() = aaosMapArea || sealion7Hdmi
        val requestedCustomMiniMapBounds = if (factoryMiniMap && kind == SessionKind.CUSTOM) {
            persistedBounds(pkg, displayWidth, displayHeight) ?: factoryMiniMapDefaultBounds(displayWidth, displayHeight)
        } else null
        @Volatile var miniMapBounds: DiLink5ProjectionBounds? = null

        fun customLayoutsPanel() = diLink5CustomLayoutsPanel(
            targetDisplayName, displayWidth, displayHeight, hostedGeometry, miniMapBounds ?: requestedCustomMiniMapBounds,
            sealion7Hdmi = sealion7Hdmi,
        )
    }

    private data class HostedSurfaceLoss(
        val resourceGeneration: Long,
        val pendingOwner: DiLink5ProjectionOwnership?,
        val cleanupClaimed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class ProjectionTarget(
        /** Physical OEM panel. */
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        /** Task destination. Equal to [displayId] in direct mode. */
        val contentDisplayId: Int = displayId,
        val transport: String = "direct",
        val hostedResourceGeneration: Long = 0L,
        val deferCompositorEnable: Boolean = false,
        val hostedGeometry: HostedProjectionGeometry? = null,
        val aaosMapArea: Boolean = false,
        val sealion7Hdmi: Boolean = false,
    )

    private data class RecoveryMarker(
        val pending: Boolean,
        val powered: Boolean,
        val appMoved: Boolean,
        val enableIndeterminate: Boolean,
        val enableBootId: String,
        val teardownIndeterminate: Boolean,
        val teardownBootId: String,
        val pkg: String
    )

    private data class RecoveryMarkerRead(
        val marker: RecoveryMarker,
        val failed: Boolean
    )

    private data class ForegroundVerification(
        val stable: Boolean,
        val stateKnown: Boolean,
        val lastForeignPackage: String?,
        /** An OEM cluster app re-fronted itself AGAIN after we had already
         *  corrected once: on this surface the OEM wins by design. */
        val oemReclaimedAfterCorrection: Boolean = false
    )

    private data class LaunchVerification(
        val succeeded: Boolean,
        val reason: String = "",
        /** True only when command rejection or observed task/foreground state proves direct failed. */
        val definiteFailure: Boolean = false,
        /** Package that kept taking the target display's foreground, when that
         *  is why verification failed (drives the shared-display fallback). */
        val foreignPackage: String? = null
    )

    @JvmStatic
    @JvmOverloads
    fun start(pkg: String?, explicitSelection: Boolean = true): Boolean {
        val target = resolveAppTarget(pkg) ?: return false
        return startTarget(target.copy(automatic = !explicitSelection), preempt = explicitSelection) != null
    }

    @JvmStatic
    fun startAndAwait(pkg: String?, timeoutMs: Long): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val target = resolveAppTarget(pkg) ?: return false
        if (!awaitSettledBeforeStart(minOf(timeoutMs, OPERATION_TIMEOUT_MS + 2_500L))) {
            lastFailureReason = "projection start cancelled"
            return false
        }
        val ticket = startTarget(target) ?: return false
        val remainingMs =
            timeoutMs - (android.os.SystemClock.elapsedRealtime() - startedAt)
        val completed = try {
            ticket.completion.await(remainingMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            logger.warn("DI5 cluster cast: start timed out for ${target.pkg}")
            lastFailureReason = "projection start timed out"
            Thread({
                stopIfSession(ticket.generation)
            }, "DiLink5ProjectionTimeoutCleanup").apply {
                isDaemon = true
                start()
            }
            return false
        }
        return ticket.succeeded.get()
    }

    /**
     * A request-driven start waits out a previous stop's cleanup or recovery instead of failing.
     * @return false only when interrupted, i.e. the caller cancelled the start.
     */
    private fun awaitSettledBeforeStart(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        var logged = false
        while (true) {
            val cleanup: CleanupTicket?
            val recovering: Boolean
            synchronized(stateLock) {
                cleanup = activeCleanup
                recovering = phase == SessionPhase.RECOVERING
            }
            if (cleanup == null && !recovering) return true
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) return true
            if (!logged) {
                logged = true
                logger.info("DI5 cluster cast: start waiting for the previous cleanup to settle")
            }
            try {
                if (cleanup != null) {
                    cleanup.completion.await(minOf(remaining, 250L), TimeUnit.MILLISECONDS)
                } else {
                    Thread.sleep(minOf(remaining, 200L))
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun resolveAppTarget(pkg: String?): LaunchTarget? {
        if (!ClusterTransport.usesHostedProjectionService()) return null
        val normalized = pkg?.trim().orEmpty()
        if (!AppLauncher.isValidPackageName(normalized)) {
            logger.warn("DI5 cluster cast: missing or invalid package")
            lastFailureReason = "missing or invalid package"
            return null
        }
        if (isFactoryMapProvider(normalized)) {
            lastFailureReason = "Factory navigation must stay on the head unit"
            return null
        }
        if (normalized == DI5_MAP_PACKAGE) {
            logger.warn("DI5 cluster cast: self-cast is reserved for the cluster map")
            lastFailureReason = "self-cast is reserved for the cluster map"
            return null
        }
        val component = AppLauncher.resolveLauncherComponent(normalized)
        if (component == null) {
            logger.warn("DI5 cluster cast: launcher component not found for $normalized")
            lastFailureReason = "launcher component not found"
            return null
        }
        try {
            if (com.overdrive.app.navmap.ClusterMapProjector.isActive() &&
                !com.overdrive.app.navmap.ClusterMapProjector.stop()
            ) {
                logger.warn("DI5 cluster cast: active map projection did not stop cleanly")
                lastFailureReason = "active map projection did not stop cleanly"
                return null
            }
        } catch (t: Throwable) {
            logger.warn("DI5 cluster cast: active map projection stop failed: ${t.message}")
            lastFailureReason = "active map projection stop failed"
            return null
        }
        return LaunchTarget(normalized, component, SessionKind.APP)
    }

    @JvmStatic
    fun startMap(): Boolean = startMap(explicitSelection = true)

    @JvmStatic
    fun startMap(explicitSelection: Boolean): Boolean {
        if (!ClusterTransport.usesHostedProjectionService()) return false
        return startTarget(
            LaunchTarget(DI5_MAP_PACKAGE, DI5_MAP_COMPONENT, SessionKind.MAP, automatic = !explicitSelection),
            preempt = explicitSelection,
        ) != null
    }

    @JvmStatic
    fun supportsCustomLayouts(): Boolean =
        ClusterTransport.isDiLink5() ||
            (ClusterTransport.isAaosHdmi() &&
                CameraDaemon.getAppContext()?.let(Sealion7HdmiProjection::isEnabled) == true)

    /** Custom Layouts session; returns its generation (the activity's launch token), 0 if refused. */
    @JvmStatic
    fun startCustomLayouts(owner: Long): Long {
        if (owner <= 0L || !supportsCustomLayouts()) return 0L
        return startTarget(
            LaunchTarget(
                DI5_MAP_PACKAGE,
                com.overdrive.app.customlayouts.daemon.projection.ClusterActivityLauncher.COMPONENT,
                SessionKind.CUSTOM,
                customOwner = owner,
            )
        )?.generation ?: 0L
    }

    /** Automatic starts and guardian migrations cannot turn off the user's Custom Layouts selection. */
    private fun startTarget(target: LaunchTarget, preempt: Boolean = true): StartTicket? {
        val admission = com.overdrive.app.launcher.layout.LayoutModeGate.enterStandard()
            ?: run {
                lastFailureReason = "Stop Layouts before starting a single app or full cluster map"
                return null
            }
        return try {
            if (preempt && target.kind != SessionKind.CUSTOM) {
                // A user-started map or app takes the cluster from Custom Layouts (and turns it off).
                try {
                    com.overdrive.app.customlayouts.daemon.CustomLayoutsController.onOtherOwnerStarted(
                        if (target.kind == SessionKind.MAP) "map" else "castapp"
                    )
                } catch (t: Throwable) {
                    logger.warn("DI5 cluster cast: Custom Layouts hand-off failed: ${t.message}")
                }
            }
            startTargetAdmitted(target)
        } finally {
            admission.close()
        }
    }

    private fun startTargetAdmitted(target: LaunchTarget): StartTicket? {
        if (!selectionStillAllows(target)) return null
        if (!prepareHostedOwnershipForAdmission()) return null
        synchronized(stateLock) {
            if (activeCleanup != null) {
                lastFailureReason =
                    "previous DI5 projection cleanup is still in progress"
                logger.warn(
                    "DI5 cluster cast: refusing start while cleanup is active"
                )
                return null
            }
            if (phase == SessionPhase.RECOVERING) {
                lastFailureReason =
                    "previous DI5 projection task recovery is still in progress"
                logger.warn(
                    "DI5 cluster cast: refusing start while task recovery is active"
                )
                return null
            }
        }
        val unresolvedCleanup = synchronized(stateLock) {
            activePackage == null &&
                phase == SessionPhase.FAILED &&
                (poweredGeneration > 0L ||
                        enableIndeterminateGeneration > 0L ||
                        teardownIndeterminateGeneration > 0L ||
                        appMovedGeneration > 0L ||
                        cleanupUncertain)
        }
        if (unresolvedCleanup) {
            // Re-run the orphaned-state recovery instead of demanding a daemon
            // restart: it re-homes (or force-stops) the stranded task and powers
            // the compositor down with the same marker-guarded steps the
            // shutdown path uses. Only an unrecoverable residue still refuses.
            logger.warn(
                "DI5 cluster cast: recovering unresolved cleanup state before start"
            )
            val recovered = try {
                recoverOrphanedStateSynchronously()
            } catch (t: Throwable) {
                logger.warn(
                    "DI5 cluster cast: pre-start recovery failed: ${t.message}"
                )
                false
            }
            if (!recovered) {
                synchronized(stateLock) {
                    lastFailureReason =
                        "previous DI5 projection cleanup is incomplete; restart required"
                }
                logger.warn("DI5 cluster cast: refusing start with unresolved cleanup state")
                return null
            }
        }
        synchronized(stateLock) {
            if (phase == SessionPhase.FAILED &&
                (poweredGeneration > 0L ||
                        enableIndeterminateGeneration > 0L ||
                        teardownIndeterminateGeneration > 0L ||
                        appMovedGeneration > 0L ||
                        cleanupUncertain)
            ) {
                lastFailureReason =
                    "previous DI5 projection cleanup is incomplete; restart required"
                logger.warn("DI5 cluster cast: refusing start with unresolved cleanup state")
                return null
            }
        }
        val (previous, previousGeneration) = synchronized(stateLock) {
            if (target.automatic && activeKind == SessionKind.CUSTOM) return null
            activePackage to generation
        }
        // A migration only reclaims an empty transport. A user may have chosen a new projection
        // during the asynchronous stop of the old surface.
        if (target.previousCustomGeneration > 0 && previous != null) return null
        if (target.kind != SessionKind.CUSTOM && previous == target.pkg && activeKind == target.kind && !stopping) {
            pendingStart?.takeIf { it.generation == generation }?.let { return it }
            if (phase == SessionPhase.ACTIVE) {
                val live = session
                if (live?.usesHdmiPanel == true) {
                    val context = CameraDaemon.getAppContext()
                    if (context == null || !hdmiPanelReady(context, live)) {
                        DiLink5HostedProjectionBridge.withdrawMapArea(
                            live.hostedResourceGeneration, live.sealion7Hdmi,
                        )
                        lastFailureReason = "The active cluster map area is no longer confirmed"
                        return null
                    }
                }
                if (live != null &&
                    live.generation == generation &&
                    ClusterCast.resumedPackageOnDisplay(live.targetDisplayId) == target.pkg
                ) {
                    return StartTicket(generation).also {
                        it.succeeded.set(true)
                        it.completion.countDown()
                    }
                }
                logger.warn(
                    "DI5 cluster cast: state said active but ${target.pkg} no longer " +
                            "owned the display foreground; replacing the stale session"
                )
            }
        }
        if (!selectionStillAllows(target)) return null
        if (previous != null &&
            !stopInternal(previousGeneration, rehome = true, foreground = false, expectedKind = null)
        ) {
            return null
        }

        val nextGeneration: Long
        val ticket: StartTicket
        synchronized(stateLock) {
            if (target.automatic && !selectionStillAllows(target)) return null
            if (stopping ||
                activePackage != null ||
                activeCleanup != null ||
                phase == SessionPhase.STOPPING ||
                phase == SessionPhase.RECOVERING
            ) {
                lastFailureReason =
                    "DI5 projection state changed while start was being admitted"
                return null
            }
            nextGeneration = if (target.kind == SessionKind.CUSTOM) {
                maxOf(nextGenerationLocked(), android.os.SystemClock.elapsedRealtime())
            } else nextGenerationLocked()
            // Reserve even a cancelled ticket's number. An old finish broadcast must never
            // match the next logical session, including after a daemon restart on this boot.
            generation = nextGeneration
            if (target.kind == SessionKind.CUSTOM &&
                !com.overdrive.app.customlayouts.daemon.CustomLayoutsController.claimDi5Generation(
                    target.customOwner, target.previousCustomGeneration, nextGeneration,
                )
            ) return null
            if (!writeMarker(
                    pending = true,
                    powered = false,
                    appMoved = false,
                    enableIndeterminate = false,
                    enableBootId = "",
                    pkg = target.pkg
                )
            ) {
                logger.warn("DI5 cluster cast: recovery marker could not be persisted")
                lastFailureReason = "recovery marker could not be persisted"
                if (target.kind == SessionKind.CUSTOM) notifyCustomLayoutsEnded(nextGeneration, lastFailureReason)
                return null
            }
            ticket = StartTicket(nextGeneration)
            activePackage = target.pkg
            activeKind = target.kind
            phase = SessionPhase.PREPARING
            lastFailureReason = ""
            pendingStart = ticket
            poweredGeneration = 0L
            enableIndeterminateGeneration = 0L
            teardownIndeterminateGeneration = 0L
            teardownIndeterminateBootId = ""
            appMovedGeneration = 0L
            cleanupUncertain = false
        }
        operations.execute {
            startSession(nextGeneration, target)
        }
        return ticket
    }

    private fun selectionStillAllows(target: LaunchTarget): Boolean = when {
        target.automatic ->
            !com.overdrive.app.customlayouts.daemon.CustomLayoutsController.claimsAutomaticProjection()
        target.kind == SessionKind.CUSTOM ->
            com.overdrive.app.customlayouts.daemon.CustomLayoutsController.wantsDi5Generation(
                target.customOwner, target.previousCustomGeneration,
            )
        else -> true
    }

    @JvmStatic
    fun stop(): Boolean =
        stopInternal(
            0L, rehome = true, foreground = false, expectedKind = SessionKind.APP
        )

    @JvmStatic
    fun stopForAccOff(): Boolean =
        stopInternal(
            0L, rehome = false, foreground = false, expectedKind = SessionKind.APP
        )

    @JvmStatic
    fun stopIfSession(expectedGeneration: Long): Boolean =
        if (expectedGeneration > 0L) {
            stopInternal(
                expectedGeneration,
                rehome = true,
                foreground = false,
                expectedKind = SessionKind.APP
            )
        } else true

    @JvmStatic
    fun stop(rehomeToHeadUnit: Boolean): Boolean =
        stopInternal(
            0L,
            rehome = true,
            foreground = rehomeToHeadUnit,
            expectedKind = SessionKind.APP
        )

    @JvmStatic
    fun stopMap(): Boolean =
        stopInternal(
            0L, rehome = false, foreground = false, expectedKind = SessionKind.MAP
        )

    /** A late release may join its own guardian's cleanup, but cannot stop a successor. */
    @JvmStatic
    fun stopCustomLayouts(expectedGeneration: Long): Boolean {
        if (expectedGeneration <= 0L) return true
        return stopInternal(
            expectedGeneration, rehome = false, foreground = false,
            expectedKind = SessionKind.CUSTOM
        )
    }

    /** Lock-free snapshot of a preparing or active Custom Layouts session, or null. */
    @JvmStatic
    fun customLayoutsTarget(): CustomLayoutsTarget? {
        val gen = generation
        val currentPhase = phase
        if (activePackage == null || activeKind != SessionKind.CUSTOM || stopping ||
            (currentPhase != SessionPhase.PREPARING && currentPhase != SessionPhase.ACTIVE)
        ) return null
        val live = session?.takeIf { it.generation == gen && it.kind == SessionKind.CUSTOM }
        val panel = live?.customLayoutsPanel()
        return CustomLayoutsTarget(
            gen,
            live?.targetDisplayId ?: -1,
            panel?.w ?: 0,
            panel?.h ?: 0,
            panel?.band == true,
            live != null && currentPhase == SessionPhase.ACTIVE && generation == gen
        )
    }

    /** Watchdog relaunch inside the live Custom Layouts session; false when none is live. */
    @JvmStatic
    fun relaunchCustomLayouts(expectedGeneration: Long): Boolean {
        val live = session?.takeIf { it.kind == SessionKind.CUSTOM && it.generation == expectedGeneration }
            ?: return false
        val target = LaunchTarget(live.pkg, live.component, live.kind)
        return withLiveTaskMutation(live, false) {
            launchTarget(target, live.targetDisplayId, live.generation)
        }
    }

    private fun customLayoutsLaunchExtras(displayId: Int, launchGeneration: Long): List<String>? {
        val live = session?.takeIf {
            it.kind == SessionKind.CUSTOM && it.generation == launchGeneration && it.targetDisplayId == displayId
        } ?: return null
        val panel = live.customLayoutsPanel()
        return com.overdrive.app.customlayouts.daemon.projection.ClusterActivityLauncher.extras(
            panel.mode, panel.w, panel.h, launchGeneration,
        )
    }

    @JvmStatic
    fun isActive(): Boolean =
        activePackage != null &&
                activeKind == SessionKind.APP &&
                phase == SessionPhase.ACTIVE &&
                !stopping

    @JvmStatic
    fun isProjectionSourceActive(): Boolean =
        activePackage != null &&
                session?.generation == generation &&
                phase == SessionPhase.ACTIVE &&
                !stopping

    /** Physical APP/MAP state for optional overlay work; no probing or hardware commands. */
    @JvmStatic
    fun clusterAdasState(): com.overdrive.app.surveillance.ClusterAdasLifecycle.Source =
        synchronized(stateLock) {
            val active = activePackage != null &&
                    session?.generation == generation &&
                    phase == SessionPhase.ACTIVE && !stopping
            val busy = phase == SessionPhase.PREPARING ||
                    phase == SessionPhase.STOPPING || phase == SessionPhase.RECOVERING ||
                    activeCleanup != null || poweredGeneration != 0L ||
                    enableIndeterminateGeneration != 0L || teardownIndeterminateGeneration != 0L ||
                    legacyProjectionRecoveryBlocked
            com.overdrive.app.surveillance.ClusterAdasLifecycle.Source(generation, active, busy)
        }

    /** OEM display that owns the projected task; input must target this id, not the plain debug id. */
    @JvmStatic
    fun currentTargetDisplayId(): Int =
        session
            ?.takeIf {
                it.generation == generation &&
                        phase == SessionPhase.ACTIVE &&
                        !stopping
            }
            ?.physicalDisplayId
            ?: -1

    @JvmStatic
    fun isFactoryMiniMapActive(): Boolean = session?.let {
        it.factoryMiniMap && it.generation == generation && phase == SessionPhase.ACTIVE && !stopping
    } == true

    @JvmStatic
    fun currentContentDisplayId(): Int =
        session?.takeIf {
            it.generation == generation && phase == SessionPhase.ACTIVE && !stopping
        }?.targetDisplayId ?: -1

    /**
     * Input target plus physical-panel rectangle and content-buffer dimensions.
     * Returned as contentId,left,top,width,height,bufferWidth,bufferHeight.
     */
    @JvmStatic
    fun currentInputMapping(expectedGeneration: Long): IntArray? {
        val live = session?.takeIf {
            it.generation == expectedGeneration && generation == expectedGeneration &&
                phase == SessionPhase.ACTIVE && !stopping
        } ?: return null
        val hosted = live.hostedGeometry
        return if (live.projectionTransport == "hosted" && hosted != null) {
            intArrayOf(
                live.targetDisplayId,
                hosted.overlay.left,
                hosted.overlay.top,
                hosted.overlay.width(),
                hosted.overlay.height(),
                hosted.bufferWidth,
                hosted.bufferHeight,
            )
        } else {
            intArrayOf(
                live.targetDisplayId,
                0,
                0,
                live.displayWidth,
                live.displayHeight,
                live.displayWidth,
                live.displayHeight,
            )
        }
    }

    @JvmStatic
    fun currentProjectionTransport(): String =
        session?.takeIf { it.generation == generation }?.projectionTransport ?: "none"

    /** Called by the app-overlay owner after an unexpected Surface/service teardown. */
    @JvmStatic
    fun onHostedSurfaceLost(resourceGeneration: Long) {
        // Resize records its possible task movement before dispatch. Surface loss must reclaim
        // that pending replacement even while the old host is still the published Session.
        val pendingOwner = try {
            DiLink5ProjectionOwnership.load(resourceGeneration)
        } catch (_: Throwable) { null }
        val loss = HostedSurfaceLoss(resourceGeneration, pendingOwner)
        val lost = synchronized(stateLock) {
            hostedSurfaceLossScopeLocked(loss)
        } ?: return
        Thread({
            completeHostedSurfaceLoss(
                isMap = lost.second == SessionKind.MAP,
                stop = {
                    stopInternal(
                        lost.first,
                        rehome = lost.second == SessionKind.APP,
                        foreground = false,
                        expectedKind = lost.second,
                        hostedSurfaceLoss = loss,
                    )
                },
                onMapFailure = {
                    if (loss.cleanupClaimed.get()) {
                        com.overdrive.app.navmap.ClusterMapProjector.onDiLink5ProjectionFailed()
                    }
                },
            )
            if (lost.second == SessionKind.CUSTOM && ClusterTransport.isAaosHdmi() && loss.cleanupClaimed.get()) {
                notifyCustomLayoutsEnded(lost.first, "The Sealion 7 cluster window was withdrawn")
            }
        }, "DiLink5HostedSurfaceCleanup").apply {
            isDaemon = true
            start()
        }
    }

    /** Caller holds stateLock, including stop's final claim against resize publication. */
    private fun hostedSurfaceLossScopeLocked(loss: HostedSurfaceLoss): Pair<Long, SessionKind>? {
        if (stopping) return null
        val published = session?.takeIf {
            it.projectionTransport == "hosted" && it.generation == generation &&
                it.hostedResourceGeneration == loss.resourceGeneration
        }
        val resourceRegistered =
            uncertainHostedResources[generation]?.contains(loss.resourceGeneration) == true
        if (!hostedSurfaceLossNeedsCleanup(
                resourceGeneration = loss.resourceGeneration,
                publishedResourceGeneration = published?.hostedResourceGeneration ?: 0L,
                registeredForCleanup = resourceRegistered,
                preparing = phase == SessionPhase.PREPARING,
                active = phase == SessionPhase.ACTIVE,
                pendingReplacement = loss.resourceGeneration in pendingHostedReplacements,
                taskMayBeMoved = loss.pendingOwner?.taskMayBeMoved == true,
                packageMatches = loss.pendingOwner?.packageName == activePackage,
            )
        ) return null
        val kind = published?.kind ?: activeKind?.takeIf { activePackage != null } ?: return null
        return generation to kind
    }

    @JvmStatic
    fun currentTargetDisplayWidth(): Int =
        session?.takeIf {
            it.generation == generation && phase == SessionPhase.ACTIVE && !stopping
        }?.displayWidth ?: 0

    @JvmStatic
    fun currentTargetDisplayHeight(): Int =
        session?.takeIf {
            it.generation == generation && phase == SessionPhase.ACTIVE && !stopping
        }?.displayHeight ?: 0

    /** Physical overlay bounds; content buffer pixels are deliberately separate. */
    @JvmStatic
    fun currentHostedProjectionBounds(): IntArray? = synchronized(stateLock) {
        session?.takeIf {
            it.generation == generation && it.projectionTransport == "hosted" &&
                !stopping && phase == SessionPhase.ACTIVE
        }?.hostedGeometry?.overlay?.let {
            intArrayOf(it.left, it.top, it.right, it.bottom)
        }
    }

    @JvmStatic
    fun currentAppProjectionSnapshot(): JSONObject = synchronized(stateLock) {
        val appPackage = activePackage?.takeIf {
            activeKind == SessionKind.APP && !stopping &&
                phase != SessionPhase.IDLE && phase != SessionPhase.FAILED
        }.orEmpty()
        val live = session?.takeIf {
            it.generation == generation && it.kind == SessionKind.APP &&
                it.pkg == appPackage && !stopping
        }
        JSONObject()
            .put("castPackage", appPackage)
            .put("castSessionGeneration", if (appPackage.isNotEmpty()) generation else 0L)
            .put("projectionPathActive", live?.projectionTransport ?: "none")
            .put("panelW", live?.displayWidth ?: 0)
            .put("panelH", live?.displayHeight ?: 0)
            .put("factoryMiniMap", live?.factoryMiniMap == true)
            .put("miniMapProjectionBounds", live?.takeIf { phase == SessionPhase.ACTIVE }
                ?.miniMapBounds?.let {
                    JSONObject().put("l", it.left).put("t", it.top)
                        .put("r", it.right).put("b", it.bottom)
                } ?: JSONObject.NULL)
            .put("hostedProjectionBounds", live?.takeIf {
                it.projectionTransport == "hosted" && phase == SessionPhase.ACTIVE
            }?.hostedGeometry?.overlay?.let {
                JSONObject().put("l", it.left).put("t", it.top)
                    .put("r", it.right).put("b", it.bottom)
            } ?: JSONObject.NULL)
    }

    @JvmStatic
    fun isMapActive(): Boolean =
        activePackage != null &&
                activeKind == SessionKind.MAP &&
                (phase == SessionPhase.PREPARING || phase == SessionPhase.ACTIVE) &&
                !stopping

    @JvmStatic
    fun getCastPackage(): String? =
        if (activeKind == SessionKind.APP &&
            phase != SessionPhase.IDLE &&
            phase != SessionPhase.FAILED &&
            !stopping
        ) activePackage else null

    @JvmStatic
    fun currentSessionGeneration(): Long =
        if (getCastPackage() != null) generation else 0L

    @JvmStatic
    fun getPhase(): String = phase.name.lowercase()

    @JvmStatic
    fun getLastFailureReason(): String = lastFailureReason

    private fun awaitCleanupCompletion(
        ticket: CleanupTicket,
        reason: String
    ): Boolean {
        val completed = try {
            ticket.completion.await(
                OPERATION_TIMEOUT_MS + 2_500L,
                TimeUnit.MILLISECONDS
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            logger.warn("DI5 cluster cast: timed out waiting for $reason")
        }
        return completed
    }

    @JvmStatic
    @JvmOverloads
    fun resize(
        left: Int, top: Int, right: Int, bottom: Int, commit: Boolean,
        expectedPackage: String? = null, expectedGeneration: Long = 0L,
    ): Boolean {
        if (!ClusterTransport.usesHostedProjectionService()) return false
        val live = synchronized(taskMutationLock.lock) {
            synchronized(stateLock) {
                session?.takeIf {
                    it.kind == SessionKind.APP && activeKind == SessionKind.APP &&
                        isLiveSession(it) && diLink5ResizeOwnershipMatches(
                            expectedPackage, expectedGeneration, it.pkg, it.generation,
                        )
                }
            }
        } ?: return false
        val bounds = clampDiLink5ProjectionBounds(
            left, top, right, bottom, live.displayWidth, live.displayHeight
        ) ?: return false
        if (live.usesHdmiPanel) {
            val context = CameraDaemon.getAppContext() ?: return false
            if (!hdmiPanelReady(context, live)) {
                DiLink5HostedProjectionBridge.withdrawMapArea(live.hostedResourceGeneration, live.sealion7Hdmi)
                return false
            }
            return commit && isLiveSession(live) &&
                live.hostedGeometry?.overlay == HostedProjectionGeometry.Bounds(left, top, right, bottom)
        }
        if (live.projectionTransport == "hosted") {
            return resizeHosted(live, bounds, commit)
        }
        val applied = synchronized(taskMutationLock.lock) {
            if (!isLiveSession(live)) return@synchronized false
            // Full clears the package override. Use the same factory default now and after restart.
            val placement = if (live.factoryMiniMap && bounds.left == 0 && bounds.top == 0 &&
                bounds.right == live.displayWidth && bounds.bottom == live.displayHeight
            ) factoryMiniMapDefaultBounds(live.displayWidth, live.displayHeight) else bounds
            val resized = applyDirectBounds(live, placement, commit)
            if (resized && commit && isLiveSession(live)) {
                ClusterCast.saveWindowFractions(
                    live.pkg,
                    bounds.left / live.displayWidth.toDouble(),
                    bounds.top / live.displayHeight.toDouble(),
                    bounds.right / live.displayWidth.toDouble(),
                    bounds.bottom / live.displayHeight.toDouble()
                )
            }
            resized
        }
        return applied
    }

    private fun resizeHosted(
        live: Session,
        bounds: DiLink5ProjectionBounds,
        commit: Boolean
    ): Boolean {
        // Live drag is deferred. Hosted resize replaces two owned resources and is
        // committed only on release, preventing a Surface/VD allocation backlog.
        if (!commit || !isLiveSession(live)) return false
        val context = CameraDaemon.getAppContext() ?: return false
        val base = try {
            DiLink5ProjectionConfig.from(
                UnifiedConfigManager.loadConfig().optJSONObject("projection")
            )
        } catch (_: Throwable) { return false }
        val remainingW = (live.displayWidth - bounds.width()).coerceAtLeast(0)
        val remainingH = (live.displayHeight - bounds.height()).coerceAtLeast(0)
        val replacementConfig = base.copy(
            widthPercent = (bounds.width() * 100.0 / live.displayWidth).roundToInt()
                .coerceIn(20, 100),
            heightPercent = (bounds.height() * 100.0 / live.displayHeight).roundToInt()
                .coerceIn(20, 100),
            xOffsetPercent = if (remainingW == 0) 0 else
                (bounds.left * 100.0 / remainingW).roundToInt().coerceIn(0, 100),
            yOffsetPercent = if (remainingH == 0) 0 else
                (bounds.top * 100.0 / remainingH).roundToInt().coerceIn(0, 100)
        )
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return false
        val physical = manager.getDisplay(live.physicalDisplayId)?.takeIf { it.isValid }
            ?: return false
        val nativeDensity = ClusterAppDensity.nativeDensityForDisplay(live.physicalDisplayId)
            ?: return false
        val geometry = HostedProjectionGeometry.calculate(
            live.displayWidth, live.displayHeight, nativeDensity, replacementConfig
        ) ?: return false
        synchronized(taskMutationLock.lock) {
            if (!isLiveSession(live) ||
                !UnifiedConfigManager.updateValues("projection", replacementConfig.toValues())
            ) return false
        }
        val resourceGeneration = nextHostedResourceGeneration()
        val replacementTarget = ProjectionTarget(
            live.physicalDisplayId,
            live.targetDisplayName,
            live.displayWidth,
            live.displayHeight
        )
        val pendingOwner = projectionOwnership(
            resourceGeneration, replacementTarget, live.physicalDisplayId,
            overlayOwned = true, virtualDisplayOwned = true,
            taskMayBeMoved = false, uncertain = true
        ).copy(
            packageName = live.pkg,
            densityOwner = "di5:${live.generation}:resize:$resourceGeneration",
        )
        if (!DiLink5ProjectionOwnership.persist(pendingOwner)) return false
        val allocation = startHostedAllocation(
            register = {
                synchronized(stateLock) {
                    if (!isLiveSession(live)) false else {
                        uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                            .add(resourceGeneration)
                        pendingHostedReplacements.add(resourceGeneration)
                        true
                    }
                }
            },
            start = {
                DiLink5HostedProjectionBridge.start(
                    context,
                    resourceGeneration,
                    live.physicalDisplayId,
                    live.targetDisplayName,
                    geometry,
                )
            },
            current = { isLiveSession(live) },
        )
        if (!allocation.registered) {
            DiLink5ProjectionOwnership.clear(resourceGeneration, true, readCurrentBootId())
            return false
        }
        if (!allocation.publicationAllowed) {
            // STOP may already have attempted cleanup while allocation was blocked. Republish the
            // exact late resource before a final release attempt so failure remains recoverable.
            reconcileLateHostedAllocation(
                republish = {
                    allocation.value?.let { late ->
                        DiLink5ProjectionOwnership.persist(pendingOwner.copy(
                            contentDisplayId = late.contentDisplayId,
                            virtualDisplayName = late.virtualDisplayName,
                        ))
                    } ?: true
                },
                release = {
                    DiLink5HostedProjectionBridge.release(resourceGeneration) &&
                        clearHostedOwnershipIfPresent(resourceGeneration)
                },
                updateRegistration = { released ->
                    synchronized(stateLock) {
                        val resources = uncertainHostedResources
                            .getOrPut(live.generation) { LinkedHashSet() }
                        if (released) {
                            resources.remove(resourceGeneration)
                            pendingHostedReplacements.remove(resourceGeneration)
                            if (resources.isEmpty()) uncertainHostedResources.remove(live.generation)
                        } else {
                            resources.add(resourceGeneration)
                        }
                    }
                },
            )
            return false
        }
        val replacement = allocation.value?.takeIf {
            DiLink5HostedProjectionBridge.isSurfaceAlive(resourceGeneration)
        } ?: run {
            releaseUnmovedHostedReplacement(live, resourceGeneration)
            return false
        }
        val registeredForCleanup = synchronized(stateLock) {
            if (!isLiveSession(live)) {
                false
            } else {
                uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                    .add(resourceGeneration)
                true
            }
        }
        if (!registeredForCleanup) {
            val released = DiLink5HostedProjectionBridge.release(resourceGeneration) &&
                DiLink5ProjectionOwnership.clear(
                    resourceGeneration,
                    true,
                    readCurrentBootId(),
                )
            if (!released) {
                synchronized(stateLock) {
                    uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                        .add(resourceGeneration)
                }
            } else {
                synchronized(stateLock) { pendingHostedReplacements.remove(resourceGeneration) }
            }
            return false
        }
        return synchronized(taskMutationLock.lock) {
        val location = ClusterFreeformWindow.findTaskLocation(
            live.pkg, live.targetDisplayId
        )
        if (!location.known || location.taskId <= 0) {
            releaseUnmovedHostedReplacement(live, resourceGeneration)
            return false
        }
        val densityOwner = "di5:${live.generation}"
        val replacementDensityOwner = "$densityOwner:resize:$resourceGeneration"
        if (ClusterAppDensity.prepareHostedReplacement(
                live.pkg,
                replacement.contentDisplayId,
                densityOwner,
                replacementDensityOwner,
            ) { isLiveSession(live) } == HostedDensityPreparation.FAILED
        ) {
            releaseUnmovedHostedReplacement(live, resourceGeneration)
            return false
        }
        val replacementDensityOwned = ClusterAppDensity.hasLease(replacementDensityOwner)
        val moveOwnerPersisted = synchronized(stateLock) {
            isLiveSession(live) && DiLink5ProjectionOwnership.persist(pendingOwner.copy(
                contentDisplayId = replacement.contentDisplayId,
                virtualDisplayName = replacement.virtualDisplayName,
                taskId = location.taskId,
                taskMayBeMoved = true,
                densityOwned = replacementDensityOwned,
                densityOwner = if (replacementDensityOwned) replacementDensityOwner else "",
            ))
        }
        if (!moveOwnerPersisted) {
            releaseUnmovedHostedReplacement(live, resourceGeneration)
            return false
        }
        val moveResult = ClusterFreeformWindow.moveTaskToDisplayResult(
            location.taskId,
            replacement.contentDisplayId,
        ) { dispatch ->
            synchronized(stateLock) {
                DiLink5HostedProjectionBridge.withLiveSurfaceGeneration(resourceGeneration) {
                    runHostedMoveDispatch(
                        current = { isLiveSession(live) },
                        register = {
                            uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                                .add(resourceGeneration)
                        },
                        dispatch = dispatch,
                    )
                } ?: false
            }
        }
        when (hostedResizeMoveDisposition(moveResult, isLiveSession(live))) {
            HostedResizeMoveDisposition.RELEASE_UNMOVED -> {
                releaseUnmovedHostedReplacement(live, resourceGeneration)
                return false
            }
            HostedResizeMoveDisposition.RETAIN_UNCERTAIN -> return false
            HostedResizeMoveDisposition.CONTINUE_VERIFICATION -> Unit
        }
        var verified = false
        repeat(VERIFY_ATTEMPTS) {
            if (!isLiveSession(live) ||
                !DiLink5HostedProjectionBridge.isSurfaceAlive(resourceGeneration)
            ) return false
            val current = ClusterFreeformWindow.findTaskLocation(
                live.pkg, replacement.contentDisplayId
            )
            if (current.known && current.taskId == location.taskId &&
                current.displayId == replacement.contentDisplayId
            ) {
                verified = true
                return@repeat
            }
            try { Thread.sleep(VERIFY_POLL_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (!verified) {
            // Movement was dispatched but not verified. Retain both resources for
            // owner-scoped stop/recovery rather than destroying a possible task host.
            synchronized(stateLock) {
                uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                    .add(resourceGeneration)
            }
            return false
        }
        if (!ClusterAppDensity.restoreForSession(densityOwner) ||
            !ClusterAppDensity.promoteHostedReplacement(
                densityOwner,
                replacementDensityOwner,
            )
        ) {
            synchronized(stateLock) {
                uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                    .add(resourceGeneration)
                if (live.hostedResourceGeneration > 0L) {
                    uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                        .add(live.hostedResourceGeneration)
                }
            }
            return false
        }
        val canonicalDensityOwned = ClusterAppDensity.hasLease(densityOwner)
        val replacementLive = synchronized(stateLock) publicationState@{
            if (!isLiveSession(live)) {
                uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                    .add(resourceGeneration)
                return@publicationState null
            }
            DiLink5HostedProjectionBridge.withLiveSurfaceGeneration(resourceGeneration) publication@{
                if (!DiLink5ProjectionOwnership.persist(pendingOwner.copy(
                        packageName = live.pkg,
                        contentDisplayId = replacement.contentDisplayId,
                        virtualDisplayName = replacement.virtualDisplayName,
                        taskId = location.taskId,
                        taskMayBeMoved = true,
                        densityOwned = canonicalDensityOwned,
                        densityOwner = if (canonicalDensityOwned) densityOwner else "",
                        compositorOwned = true,
                        uncertain = false
                    ))) {
                    uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                        .add(resourceGeneration)
                    return@publication null
                }
                uncertainHostedResources[live.generation]?.let { resources ->
                    resources.remove(resourceGeneration)
                    if (resources.isEmpty()) uncertainHostedResources.remove(live.generation)
                }
                pendingHostedReplacements.remove(resourceGeneration)
                if (live.hostedResourceGeneration > 0L) {
                    uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                        .add(live.hostedResourceGeneration)
                }
                live.copy(
                    targetDisplayId = replacement.contentDisplayId,
                    hostedResourceGeneration = resourceGeneration,
                    hostedGeometry = geometry,
                ).also { session = it }
            }
        } ?: return false
        if (DiLink5HostedProjectionBridge.release(live.hostedResourceGeneration) &&
            DiLink5ProjectionOwnership.clear(
                live.hostedResourceGeneration,
                true,
                readCurrentBootId(),
            )
        ) {
            synchronized(stateLock) {
                uncertainHostedResources[live.generation]?.let { resources ->
                    resources.remove(live.hostedResourceGeneration)
                    if (resources.isEmpty()) uncertainHostedResources.remove(live.generation)
                }
            }
        } else {
            synchronized(stateLock) {
                uncertainHostedResources.getOrPut(live.generation) { LinkedHashSet() }
                    .add(live.hostedResourceGeneration)
            }
        }
        startTaskGuardian(
            replacementLive,
            LaunchTarget(replacementLive.pkg, replacementLive.component, replacementLive.kind),
        )
        ClusterCast.saveWindowFractions(
            live.pkg,
            bounds.left / live.displayWidth.toDouble(),
            bounds.top / live.displayHeight.toDouble(),
            bounds.right / live.displayWidth.toDouble(),
            bounds.bottom / live.displayHeight.toDouble()
        )
        isLiveSession(replacementLive) &&
            DiLink5HostedProjectionBridge.isSurfaceAlive(resourceGeneration)
        }
    }

    /** Release only while this resize still owns the live session. Once stop wins the state
     * lock, the replacement stays registered for that stop/recovery pass instead. */
    private fun releaseUnmovedHostedReplacement(
        live: Session,
        resourceGeneration: Long,
    ): Boolean {
        val releaseHere = synchronized(stateLock) {
            isLiveSession(live) &&
                uncertainHostedResources[live.generation]?.contains(resourceGeneration) == true
        }
        if (!releaseHere) return false
        return completeHostedReplacementCleanup(
            restoreDensity = {
                ClusterAppDensity.restoreForSession(
                    "di5:${live.generation}:resize:$resourceGeneration"
                )
            },
            releaseResources = {
                DiLink5HostedProjectionBridge.release(resourceGeneration) &&
                    DiLink5ProjectionOwnership.clear(
                        resourceGeneration,
                        true,
                        readCurrentBootId(),
                    )
            },
            clearRegistration = {
                synchronized(stateLock) {
                    uncertainHostedResources[live.generation]?.let { resources ->
                        resources.remove(resourceGeneration)
                        if (resources.isEmpty()) uncertainHostedResources.remove(live.generation)
                    }
                    pendingHostedReplacements.remove(resourceGeneration)
                }
            },
        )
    }

    @JvmStatic
    fun recoverAtBoot() {
        recoverAtBoot(allowInactiveCleanup = false)
    }

    @JvmStatic
    fun recoverAtBoot(allowInactiveCleanup: Boolean) {
        recoverAtBootAttempt(
            allowInactiveCleanup,
            retryAttempt = 0,
            reservedRetryTicket = null
        )
    }

    /**
     * A bounded legacy command can return after its caller timed out. Once that
     * late reply has definitively cleared the legacy in-flight marker, resume
     * the cross-mode recovery owner even when its prior same-boot result left
     * the state machine in FAILED. This is not a blind retry: the legacy worker
     * invokes it only after a definitive Binder reply.
     */
    @JvmStatic
    fun retryRecoveryAfterLegacyCommandResolution() {
        var scheduleResumePoll = false
        val ticket: Long? = synchronized(stateLock) {
            if (activePackage != null ||
                activeCleanup != null
            ) {
                return
            }
            if (phase == SessionPhase.RECOVERING) {
                if (!legacyRecoveryResumeScheduled) {
                    legacyRecoveryResumeScheduled = true
                    scheduleResumePoll = true
                }
                null
            } else if (phase == SessionPhase.IDLE ||
                phase == SessionPhase.FAILED
            ) {
                legacyRecoveryResumeScheduled = false
                recoveryRetryTicket++
                phase = SessionPhase.RECOVERING
                recoveryRetryTicket
            } else {
                return
            }
        }
        if (ticket == null) {
            if (scheduleResumePoll) {
                scheduleLegacyRecoveryResumePoll()
            }
            return
        }
        try {
            deferredCleanup.execute {
                recoverAtBootAttempt(
                    allowInactiveCleanup = true,
                    retryAttempt = 0,
                    reservedRetryTicket = ticket
                )
            }
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: late legacy recovery dispatch failed: " +
                        "${t.message}; running inline"
            )
            recoverAtBootAttempt(
                allowInactiveCleanup = true,
                retryAttempt = 0,
                reservedRetryTicket = ticket
            )
        }
    }

    private fun scheduleLegacyRecoveryResumePoll() {
        try {
            deferredCleanup.schedule({
                val action = synchronized(stateLock) {
                    when {
                        !legacyRecoveryResumeScheduled -> 0
                        activePackage != null ||
                            activeCleanup != null -> {
                            legacyRecoveryResumeScheduled = false
                            0
                        }
                        phase == SessionPhase.RECOVERING -> 1
                        else -> {
                            legacyRecoveryResumeScheduled = false
                            2
                        }
                    }
                }
                when (action) {
                    1 -> scheduleLegacyRecoveryResumePoll()
                    2 -> retryRecoveryAfterLegacyCommandResolution()
                }
            }, LEGACY_RECOVERY_RESUME_POLL_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            synchronized(stateLock) {
                legacyRecoveryResumeScheduled = false
            }
            logger.warn(
                "DI5 cluster cast: unable to schedule late legacy " +
                        "recovery resume: ${t.message}"
            )
        }
    }

    private fun recoverAtBootAttempt(
        allowInactiveCleanup: Boolean,
        retryAttempt: Int,
        reservedRetryTicket: Long?
    ) {
        recoverPersistedHostedOwnership()
        if (!ClusterTransport.usesHostedProjectionService() && !allowInactiveCleanup) return
        // `allowInactiveCleanup` is also the explicit cross-mode recovery
        // authority while DI5 itself is selected. A stale legacy marker was
        // already deferred by ClusterProjectionController; after DI5 ownership
        // is clear we must consume that marker before leaving RECOVERING.
        val blockingLegacyProjection = allowInactiveCleanup
        if (blockingLegacyProjection) {
            legacyProjectionRecoveryBlocked = true
        }
        val probeTicket = synchronized(stateLock) {
            if (reservedRetryTicket != null) {
                if (reservedRetryTicket != recoveryRetryTicket ||
                    activePackage != null ||
                    activeCleanup != null ||
                    phase != SessionPhase.RECOVERING
                ) {
                    return
                }
                reservedRetryTicket
            } else {
                if (activePackage != null ||
                    activeCleanup != null ||
                    phase != SessionPhase.IDLE
                ) {
                    return
                }
                recoveryRetryTicket++
                phase = SessionPhase.RECOVERING
                recoveryRetryTicket
            }
        }
        val instrumentRestored = recoverInstrumentModeIfIdle()
        val markerRead = readRecoveryMarker()
        if (markerRead.failed) {
            synchronized(stateLock) {
                if (probeTicket == recoveryRetryTicket &&
                    phase == SessionPhase.RECOVERING
                ) {
                    cleanupUncertain = true
                    phase = SessionPhase.FAILED
                    lastFailureReason =
                        "DI5 projection recovery marker could not be read"
                }
            }
            logger.warn(
                "DI5 cluster cast: recovery marker unreadable; refusing new " +
                        "projection until persisted ownership can be verified"
            )
            scheduleBootRecoveryRetry(
                allowInactiveCleanup,
                retryAttempt,
                sameBootIndeterminate = false,
                reason = "recovery marker unreadable"
            )
            return
        }
        val pending = markerRead.marker.pending
        val powered = markerRead.marker.powered
        val enableIndeterminate =
            markerRead.marker.enableIndeterminate
        val enableBootId = markerRead.marker.enableBootId
        val teardownIndeterminate =
            markerRead.marker.teardownIndeterminate
        val teardownBootId = markerRead.marker.teardownBootId
        val currentBootId = readCurrentBootId()
        val enableMayStillComplete =
            diLink5IndeterminateCommandMayStillComplete(
                enableIndeterminate,
                enableBootId,
                currentBootId
            )
        val teardownMayStillComplete =
            diLink5IndeterminateCommandMayStillComplete(
                teardownIndeterminate,
                teardownBootId,
                currentBootId
            )
        val mayBePowered =
            mayOwnDiLink5ProjectionCompositor(
                pending,
                powered,
                enableIndeterminate,
                teardownIndeterminate
            )
        val pkg = markerRead.marker.pkg
        val appMoved = markerRead.marker.appMoved
        if (instrumentRestored && !pending &&
            !powered &&
            !appMoved &&
            !enableIndeterminate &&
            !teardownIndeterminate
        ) {
            if (blockingLegacyProjection) {
                val legacyRecovery =
                    completeLegacyBootRecoveryAfterDiLink5()
                val legacyRecovered = legacyRecovery.recovered
                val legacySettled = legacyRecovery.terminal
                synchronized(stateLock) {
                    if (probeTicket == recoveryRetryTicket &&
                        phase == SessionPhase.RECOVERING
                    ) {
                        // A terminal-but-unsupported verdict is NOT cleanup
                        // uncertainty: there is no DI5 residue in this branch
                        // and the legacy verdict is definitive. Holding
                        // phase=FAILED here permanently refused DI5 starts
                        // (and the fence blocked every legacy consumer) on
                        // firmware whose AutoContainer simply rejects the
                        // legacy protocol — the 2026-09-23 field wedge.
                        cleanupUncertain = !legacySettled
                        phase = if (legacySettled) {
                            SessionPhase.IDLE
                        } else {
                            SessionPhase.FAILED
                        }
                        lastFailureReason = when {
                            legacyRecovered -> ""
                            legacyRecovery.unsupported ->
                                "legacy cluster protocol unsupported on " +
                                    "this firmware"
                            else ->
                                "legacy projection boot recovery incomplete"
                        }
                        legacyProjectionRecoveryBlocked = !legacySettled
                        if (legacySettled) {
                            recoveryRetryTicket++
                        }
                    }
                }
                if (legacyRecovered) {
                    retryClusterAutoProjectionAfterRecovery()
                } else if (legacySettled) {
                    logger.warn(
                        "DI5 cluster cast: legacy boot recovery ended with " +
                            "a terminal unsupported verdict; releasing the " +
                            "cross-mode projection fence (marker retained)"
                    )
                } else {
                    scheduleBootRecoveryRetry(
                        allowInactiveCleanup,
                        retryAttempt,
                        sameBootIndeterminate =
                            legacyRecovery.sameBootIndeterminate,
                        reason = "legacy projection boot recovery incomplete"
                    )
                }
            } else {
                synchronized(stateLock) {
                    if (probeTicket == recoveryRetryTicket &&
                        phase == SessionPhase.RECOVERING
                    ) {
                        cleanupUncertain = false
                        phase = SessionPhase.IDLE
                        lastFailureReason = ""
                        recoveryRetryTicket++
                    }
                }
            }
            return
        }
        val cleanupTicket = CleanupTicket()
        val recoveryGeneration = synchronized(stateLock) {
            if (probeTicket != recoveryRetryTicket ||
                activePackage != null ||
                phase != SessionPhase.RECOVERING ||
                activeCleanup != null
            ) {
                return
            }
            val next = nextGenerationLocked()
            generation = next
            lastFailureReason = "recovering stale DI5 projection state"
            poweredGeneration = if (mayBePowered) next else 0L
            enableIndeterminateGeneration =
                if (mayBePowered && enableMayStillComplete) next else 0L
            teardownIndeterminateGeneration =
                if (teardownMayStillComplete) next else 0L
            teardownIndeterminateBootId =
                if (teardownMayStillComplete) teardownBootId else ""
            appMovedGeneration = if (appMoved) next else 0L
            activeCleanup = cleanupTicket
            next
        }
        Thread({
            var rehomed = false
            var poweredDown = false
            var recovered = false
            var retryBlockedBySameBootIndeterminate =
                enableMayStillComplete || teardownMayStillComplete
            try {
                rehomed = !appMoved ||
                        pkg == DI5_MAP_PACKAGE ||
                        !AppLauncher.isValidPackageName(pkg) ||
                        rehomeTask(pkg, foreground = false)
                if (!rehomed &&
                    pkg != DI5_MAP_PACKAGE &&
                    AppLauncher.isValidPackageName(pkg) &&
                    AppLauncher.forceStopPackage(pkg)
                ) {
                    // Confirmed-absent only: a failed dumpsys lookup must not
                    // pass for force-stop proof (TASK_LOOKUP_FAILED ≠ gone).
                    rehomed = AppLauncher.taskConfirmedAbsent(pkg)
                }
                poweredDown = !mayBePowered || powerDownCompositor(
                    ownershipGeneration = recoveryGeneration,
                    retainIndeterminateOwnership =
                        enableMayStillComplete,
                    teardownMayStillComplete =
                        teardownMayStillComplete
                )
                val markerWritten = if (rehomed && poweredDown) {
                    writeMarker(
                        pending = false,
                        powered = false,
                        appMoved = false,
                        enableIndeterminate = false,
                        enableBootId = "",
                        pkg = ""
                    )
                } else {
                    val retainEnable =
                        enableMayStillComplete && !poweredDown
                    writeMarker(
                        pending = false,
                        powered = mayBePowered && !poweredDown,
                        appMoved = appMoved && !rehomed,
                        enableIndeterminate = retainEnable,
                        enableBootId = if (retainEnable) {
                            enableBootId.ifEmpty { currentBootId }
                        } else {
                            ""
                        },
                        pkg = pkg
                    )
                }
                recovered = rehomed && poweredDown && markerWritten && instrumentRestored
                if (recovered) {
                    logger.info(
                        "DI5 cluster cast: recovered stale projection state"
                    )
                } else {
                    logger.warn(
                        "DI5 cluster cast: stale projection recovery incomplete"
                    )
                }
            } catch (t: Throwable) {
                logger.warn(
                    "DI5 cluster cast: stale recovery failed: ${t.message}"
                )
            } finally {
                var fullyRecovered = recovered
                synchronized(stateLock) {
                    retryBlockedBySameBootIndeterminate =
                        retryBlockedBySameBootIndeterminate ||
                                enableIndeterminateGeneration ==
                                recoveryGeneration ||
                                teardownIndeterminateGeneration ==
                                recoveryGeneration
                    if (generation == recoveryGeneration &&
                        activePackage == null &&
                        phase == SessionPhase.RECOVERING
                    ) {
                        if (poweredDown &&
                            poweredGeneration == recoveryGeneration
                        ) {
                            poweredGeneration = 0L
                        }
                        if (poweredDown &&
                            enableIndeterminateGeneration ==
                            recoveryGeneration
                        ) {
                            enableIndeterminateGeneration = 0L
                        }
                        if (poweredDown &&
                            teardownIndeterminateGeneration ==
                            recoveryGeneration
                        ) {
                            teardownIndeterminateGeneration = 0L
                            teardownIndeterminateBootId = ""
                        }
                        if (rehomed &&
                            appMovedGeneration == recoveryGeneration
                        ) {
                            appMovedGeneration = 0L
                        }
                        cleanupUncertain = !recovered
                        phase = if (recovered &&
                            blockingLegacyProjection
                        ) {
                            SessionPhase.RECOVERING
                        } else if (recovered) {
                            SessionPhase.IDLE
                        } else {
                            SessionPhase.FAILED
                        }
                        lastFailureReason = if (recovered) {
                            ""
                        } else {
                            "stale DI5 projection recovery incomplete"
                        }
                    }
                    if (activeCleanup === cleanupTicket) {
                        activeCleanup = null
                    }
                    if (blockingLegacyProjection) {
                        legacyProjectionRecoveryBlocked = true
                    }
                    if (recovered && !blockingLegacyProjection) {
                        recoveryRetryTicket++
                    }
                }
                var legacySettled = false
                var legacyUnsupported = false
                if (blockingLegacyProjection && recovered) {
                    val legacyRecovery =
                        completeLegacyBootRecoveryAfterDiLink5()
                    fullyRecovered = legacyRecovery.recovered
                    legacySettled = legacyRecovery.terminal
                    legacyUnsupported = legacyRecovery.unsupported
                    retryBlockedBySameBootIndeterminate =
                        retryBlockedBySameBootIndeterminate ||
                                legacyRecovery.sameBootIndeterminate
                    synchronized(stateLock) {
                        if (probeTicket == recoveryRetryTicket &&
                            generation == recoveryGeneration &&
                            activePackage == null &&
                            phase == SessionPhase.RECOVERING
                        ) {
                            // The DI5 residue is already proven clean here
                            // (recovered==true); an unsupported legacy verdict
                            // is definitive, not cleanup uncertainty.
                            cleanupUncertain = !legacySettled
                            phase = if (legacySettled) {
                                SessionPhase.IDLE
                            } else {
                                SessionPhase.FAILED
                            }
                            lastFailureReason = when {
                                fullyRecovered -> ""
                                legacyUnsupported ->
                                    "legacy cluster protocol unsupported " +
                                        "on this firmware"
                                else ->
                                    "legacy projection boot recovery " +
                                        "incomplete"
                            }
                            legacyProjectionRecoveryBlocked = !legacySettled
                            if (legacySettled) {
                                recoveryRetryTicket++
                            }
                        }
                    }
                    if (fullyRecovered) {
                        retryClusterAutoProjectionAfterRecovery()
                    } else if (legacySettled) {
                        logger.warn(
                            "DI5 cluster cast: legacy boot recovery ended " +
                                "with a terminal unsupported verdict; " +
                                "releasing the cross-mode projection fence " +
                                "(marker retained)"
                        )
                    }
                }
                // The ticket reports the DI5-side cleanup: a terminal legacy
                // verdict leaves nothing for a retry to do, so the cleanup is
                // settled even when the gauges were not confirmed restored.
                cleanupTicket.succeeded.set(fullyRecovered ||
                        (recovered && legacySettled))
                cleanupTicket.completion.countDown()
                if (!fullyRecovered && !(recovered && legacySettled)) {
                    scheduleBootRecoveryRetry(
                        allowInactiveCleanup,
                        retryAttempt,
                        retryBlockedBySameBootIndeterminate,
                        "stale projection recovery incomplete"
                    )
                }
            }
        }, "DiLink5ClusterRecovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun completeLegacyBootRecoveryAfterDiLink5():
        LegacyBootRecoveryOutcome {
        val taskRecovered = try {
            ClusterCast.reparentLegacyStrandedCastAtBootSynchronously()
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: deferred legacy task recovery failed: " +
                        "${t.message}"
            )
            false
        }
        val gaugeRecovery = try {
            ClusterProjectionController
                .clearStaleGateAfterDiLink5RecoveryResultSynchronously()
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: deferred legacy gauge recovery failed: " +
                        "${t.message}"
            )
            ClusterProjectionController.LegacyBootRecoveryResult
                .RETRYABLE_FAILURE
        }
        return combineLegacyBootRecoveryOutcome(
            taskRecovered, gaugeRecovery
        )
    }

    private fun retryClusterAutoProjectionAfterRecovery() {
        try {
            com.overdrive.app.monitor.AccMonitor
                .retryClusterAutoProjectionAfterRecovery()
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: deferred ACC auto-projection retry failed: " +
                        "${t.message}"
            )
        }
    }

    @JvmStatic
    fun recoveryRetryDelayMs(
        retryAttempt: Int,
        sameBootIndeterminate: Boolean
    ): Long {
        if (sameBootIndeterminate ||
            retryAttempt < 0 ||
            retryAttempt >= RECOVERY_RETRY_DELAYS_MS.size
        ) {
            return -1L
        }
        return RECOVERY_RETRY_DELAYS_MS[retryAttempt]
    }

    private fun scheduleBootRecoveryRetry(
        allowInactiveCleanup: Boolean,
        retryAttempt: Int,
        sameBootIndeterminate: Boolean,
        reason: String
    ) {
        val delayMs = recoveryRetryDelayMs(
            retryAttempt, sameBootIndeterminate
        )
        if (delayMs < 0L) {
            if (sameBootIndeterminate) {
                logger.warn(
                    "DI5 cluster cast: preserving same-boot indeterminate " +
                            "recovery lockout; no command retry is safe"
                )
            } else {
                logger.warn(
                    "DI5 cluster cast: bounded recovery retries exhausted " +
                            "($reason)"
                )
                releaseLegacyFenceAfterExhaustedRecovery()
            }
            return
        }
        val ticket = synchronized(stateLock) {
            recoveryRetryTicket++
            recoveryRetryTicket
        }
        logger.warn(
            "DI5 cluster cast: retrying $reason in ${delayMs}ms"
        )
        try {
            deferredCleanup.schedule({
                val retryOwned = synchronized(stateLock) {
                    if (ticket != recoveryRetryTicket ||
                        activePackage != null ||
                        activeCleanup != null ||
                        phase != SessionPhase.FAILED
                    ) {
                        false
                    } else {
                        phase = SessionPhase.RECOVERING
                        true
                    }
                }
                if (retryOwned) {
                    recoverAtBootAttempt(
                        allowInactiveCleanup,
                        retryAttempt + 1,
                        reservedRetryTicket = ticket
                    )
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: unable to schedule recovery retry: " +
                        "${t.message}"
            )
        }
    }

    @JvmStatic
    fun isLegacyProjectionBlockedByDiLink5Recovery(): Boolean =
        legacyProjectionRecoveryBlocked

    @JvmStatic
    fun isRecovering(): Boolean = phase == SessionPhase.RECOVERING

    @JvmStatic
    fun isTaskReclamationInFlight(): Boolean = synchronized(stateLock) {
        phase == SessionPhase.RECOVERING || activeCleanup != null
    }

    @JvmStatic
    fun hasUnresolvedTaskReclamation(): Boolean = synchronized(stateLock) {
        phase == SessionPhase.RECOVERING || activeCleanup != null ||
            appMovedGeneration > 0L || cleanupUncertain
    }

    /**
     * The bounded recovery ladder ended without a same-boot indeterminate
     * lockout. The cross-mode fence exists to order an ACTIVE recovery
     * against new legacy projections; once no retry is scheduled it can only
     * deny service (the 2026-09-23 field wedge: every cluster consumer
     * permanently refused on firmware whose AutoContainer rejects the legacy
     * protocol). Release the fence and the controller-side admission block;
     * the durable marker stays for the next boot's restore, and a definitive
     * REJECTED never arms the late-resolution watchers, so no deferred 18→0
     * can fire against a projection admitted after this release.
     */
    private fun releaseLegacyFenceAfterExhaustedRecovery() {
        val released = synchronized(stateLock) {
            if (!legacyProjectionRecoveryBlocked) {
                false
            } else {
                legacyProjectionRecoveryBlocked = false
                true
            }
        }
        if (!released) return
        val admissionReleased = try {
            ClusterProjectionController
                .releaseLegacyBootAdmissionAfterExhaustedRecovery()
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: legacy admission release failed: " +
                        "${t.message}"
            )
            false
        }
        logger.warn(
            "DI5 cluster cast: released cross-mode legacy projection fence " +
                    "after exhausted recovery (admissionReleased=" +
                    "$admissionReleased, durable marker retained)"
        )
    }

    @JvmStatic
    fun shutdownIfActive(): Boolean {
        if (!ClusterTransport.usesHostedProjectionService()) return true
        synchronized(stateLock) { activeCleanup }?.let {
            if (!awaitCleanupCompletion(it, "active projection cleanup")) {
                return false
            }
        }
        if (activePackage != null) {
            stopInternal(
                0L, rehome = false, foreground = false, expectedKind = null
            )
        }
        return recoverOrphanedStateSynchronously()
    }

    /**
     * A failed start can clear [activePackage] while leaving the durable
     * powered/app-moved marker behind. Daemon shutdown must still restore that
     * state synchronously; checking only the live package silently skipped the
     * exact failure residue the marker exists to recover.
     */
    private fun recoverOrphanedStateSynchronously(): Boolean {
        synchronized(stateLock) { activeCleanup }?.let {
            if (!awaitCleanupCompletion(it, "projection cleanup before recovery")) {
                return false
            }
        }
        recoverUnpublishedHostedResources()
        recoverPersistedHostedOwnership()
        val instrumentRestored = recoverInstrumentModeIfIdle()
        val hostedOwnershipUnresolved = DiLink5ProjectionOwnership.loadAll()
            .any { it.transport == "hosted" } || synchronized(stateLock) {
                uncertainHostedResources.isNotEmpty()
            }
        val markerRead = readRecoveryMarker()
        if (markerRead.failed) {
            synchronized(stateLock) {
                cleanupUncertain = true
                phase = SessionPhase.FAILED
                lastFailureReason =
                    "DI5 projection recovery marker could not be read"
            }
            logger.warn(
                "DI5 cluster cast: refusing to report cleanup success while " +
                        "the persisted recovery marker is unreadable"
            )
            return false
        }
        val marker = markerRead.marker
        val currentBootId = readCurrentBootId()
        val markerEnableMayStillComplete =
            diLink5IndeterminateCommandMayStillComplete(
                marker.enableIndeterminate,
                marker.enableBootId,
                currentBootId
            )
        val enableMayStillComplete =
            markerEnableMayStillComplete ||
                    enableIndeterminateGeneration > 0L
        val markerTeardownMayStillComplete =
            diLink5IndeterminateCommandMayStillComplete(
                marker.teardownIndeterminate,
                marker.teardownBootId,
                currentBootId
            )
        val teardownMayStillComplete =
            markerTeardownMayStillComplete ||
                    teardownIndeterminateGeneration > 0L
        val needsPowerDown = mayOwnDiLink5ProjectionCompositor(
            marker.pending,
            marker.powered || poweredGeneration > 0L,
            marker.enableIndeterminate || enableIndeterminateGeneration > 0L,
            marker.teardownIndeterminate || teardownIndeterminateGeneration > 0L,
        )
        val taskMayBeMoved =
            marker.appMoved || appMovedGeneration > 0L || session != null
        // A failed task/host cleanup still takes the serialized recovery path and clears
        // FAILED on success. It cannot establish ownership of the driver's factory compositor.
        if (instrumentRestored && !needsPowerDown && !taskMayBeMoved && !cleanupUncertain) {
            if (!marker.pending) return !hostedOwnershipUnresolved
            val cleared = writeMarker(
                pending = false,
                powered = false,
                appMoved = false,
                enableIndeterminate = false,
                enableBootId = "",
                pkg = ""
            )
            if (!cleared || hostedOwnershipUnresolved) {
                synchronized(stateLock) {
                    cleanupUncertain = true
                    phase = SessionPhase.FAILED
                    lastFailureReason = if (!cleared) {
                        "stale DI5 projection pending marker could not be cleared"
                    } else {
                        "hosted projection ownership cleanup remains incomplete"
                    }
                }
            }
            return cleared && !hostedOwnershipUnresolved
        }

        val recoveryPkg = marker.pkg.ifEmpty {
            session?.pkg.orEmpty()
        }
        val cleanupTicket = CleanupTicket()
        val recoveryGeneration = synchronized(stateLock) {
            if (activePackage != null ||
                activeCleanup != null ||
                stopping ||
                phase == SessionPhase.STOPPING ||
                phase == SessionPhase.RECOVERING
            ) return false
            val next = nextGenerationLocked()
            generation = next
            stopping = true
            phase = SessionPhase.STOPPING
            lastFailureReason = "recovering orphaned DI5 projection state"
            poweredGeneration = if (needsPowerDown) next else 0L
            enableIndeterminateGeneration =
                if (enableMayStillComplete) next else 0L
            teardownIndeterminateGeneration =
                if (teardownMayStillComplete) next else 0L
            teardownIndeterminateBootId =
                if (teardownMayStillComplete) {
                    marker.teardownBootId.ifEmpty {
                        teardownIndeterminateBootId
                    }
                } else {
                    ""
                }
            activeCleanup = cleanupTicket
            next
        }

        var recovered = false
        try {
            try {
                ClusterViewMirrorService.forceDetachIfActive(
                    "di5-shutdown-orphan-recovery"
                )
            } catch (_: Throwable) {
            }
            var rehomed = !taskMayBeMoved ||
                    recoveryPkg == DI5_MAP_PACKAGE ||
                    !AppLauncher.isValidPackageName(recoveryPkg) ||
                    rehomeTask(recoveryPkg, foreground = false)
            if (!rehomed &&
                recoveryPkg != DI5_MAP_PACKAGE &&
                AppLauncher.isValidPackageName(recoveryPkg) &&
                AppLauncher.forceStopPackage(recoveryPkg)
            ) {
                // Confirmed-absent only (TASK_LOOKUP_FAILED ≠ gone).
                rehomed = AppLauncher.taskConfirmedAbsent(recoveryPkg)
            }
            ClusterFreeformWindow.invalidateTaskCache()
            val poweredDown = !needsPowerDown || powerDownCompositor(
                ownershipGeneration = recoveryGeneration,
                retainIndeterminateOwnership =
                    enableMayStillComplete,
                teardownMayStillComplete =
                    teardownMayStillComplete
            )
            val retainEnable =
                enableMayStillComplete && !poweredDown
            val markerWritten = writeMarker(
                pending = false,
                powered = needsPowerDown && !poweredDown,
                appMoved = taskMayBeMoved && !rehomed,
                enableIndeterminate = retainEnable,
                enableBootId = if (retainEnable) {
                    marker.enableBootId.ifEmpty { currentBootId }
                } else {
                    ""
                },
                pkg = if (taskMayBeMoved && !rehomed) recoveryPkg else ""
            )
            recovered = rehomed && poweredDown && markerWritten &&
                !hostedOwnershipUnresolved && instrumentRestored
            if (!recovered) {
                logger.warn(
                    "DI5 cluster cast: orphaned shutdown recovery incomplete"
                )
            }
            return recovered
        } finally {
            synchronized(stateLock) {
                if (generation == recoveryGeneration &&
                    activePackage == null &&
                    phase == SessionPhase.STOPPING
                ) {
                    if (recovered) {
                        poweredGeneration = 0L
                        enableIndeterminateGeneration = 0L
                        teardownIndeterminateGeneration = 0L
                        teardownIndeterminateBootId = ""
                        appMovedGeneration = 0L
                        cleanupUncertain = false
                        session = null
                    } else {
                        cleanupUncertain = true
                    }
                    stopping = false
                    phase = if (recovered) {
                        SessionPhase.IDLE
                    } else {
                        SessionPhase.FAILED
                    }
                    lastFailureReason = if (recovered) {
                        ""
                    } else {
                        "orphaned DI5 projection cleanup incomplete"
                    }
                }
                if (activeCleanup === cleanupTicket) {
                    activeCleanup = null
                }
            }
            cleanupTicket.succeeded.set(recovered)
            cleanupTicket.completion.countDown()
        }
    }

    private fun readRecoveryMarker(): RecoveryMarkerRead {
        val surveillance = try {
            UnifiedConfigManager.readDurableConfigStrict()
                .optJSONObject("surveillance")
        } catch (_: Throwable) {
            return failedRecoveryMarkerRead()
        }
        if (surveillance == null) {
            return failedRecoveryMarkerRead()
        }
        val pending = strictMarkerBoolean(
            surveillance, MARKER_PENDING, false
        ) ?: return failedRecoveryMarkerRead()
        val powered = strictMarkerBoolean(
            surveillance, MARKER_POWERED, false
        ) ?: return failedRecoveryMarkerRead()
        val enableIndeterminate =
            if (surveillance.has(MARKER_ENABLE_INDETERMINATE)) {
                strictMarkerBoolean(
                    surveillance,
                    MARKER_ENABLE_INDETERMINATE, false
                ) ?: return failedRecoveryMarkerRead()
            } else {
                // Legacy pending markers predate the explicit in-flight
                // opcode-16 bit. Conservatively require one OS reboot before
                // they can be cleared.
                pending
            }
        val enableBootId = strictMarkerBootId(
            surveillance, MARKER_ENABLE_BOOT_ID
        ) ?: return failedRecoveryMarkerRead()
        val teardownIndeterminate = strictMarkerBoolean(
            surveillance, MARKER_TEARDOWN_INDETERMINATE, false
        ) ?: return failedRecoveryMarkerRead()
        val teardownBootId = strictMarkerBootId(
            surveillance, MARKER_TEARDOWN_BOOT_ID
        ) ?: return failedRecoveryMarkerRead()
        val pkg = strictMarkerString(
            surveillance, MARKER_PACKAGE, ""
        ) ?: return failedRecoveryMarkerRead()
        val appMoved = if (surveillance.has(MARKER_APP_MOVED)) {
            strictMarkerBoolean(
                surveillance, MARKER_APP_MOVED, false
            ) ?: return failedRecoveryMarkerRead()
        } else {
            powered && AppLauncher.isValidPackageName(pkg)
        }
        return RecoveryMarkerRead(
            RecoveryMarker(
                pending,
                powered,
                appMoved,
                enableIndeterminate,
                enableBootId,
                teardownIndeterminate,
                teardownBootId,
                pkg
            ),
            failed = false
        )
    }

    private fun failedRecoveryMarkerRead(): RecoveryMarkerRead =
        RecoveryMarkerRead(
            RecoveryMarker(
                false, false, false, false, "",
                false, "", ""
            ),
            failed = true
        )

    internal fun strictMarkerBoolean(
        source: JSONObject,
        key: String,
        defaultValue: Boolean
    ): Boolean? {
        if (!source.has(key)) return defaultValue
        return source.opt(key) as? Boolean
    }

    internal fun strictMarkerString(
        source: JSONObject,
        key: String,
        defaultValue: String
    ): String? {
        if (!source.has(key)) return defaultValue
        return source.opt(key) as? String
    }

    internal fun strictMarkerBootId(
        source: JSONObject,
        key: String
    ): String? {
        val raw = strictMarkerString(source, key, "") ?: return null
        if (raw.isEmpty()) return ""
        return canonicalDiLink5BootIdOrNull(raw)
    }

    private fun startSession(expectedGeneration: Long, target: LaunchTarget) {
        val pkg = target.pkg
        try {
            if (!isCurrent(expectedGeneration, pkg)) return
            val context = CameraDaemon.getAppContext()
                ?: throw IllegalStateException("daemon context unavailable")

            val config = readProjectionConfig()
            val factoryMiniMap = !ClusterTransport.isAaosHdmi() &&
                config.transport == DiLink5ProjectionConfig.Transport.FACTORY_MINI
            val projectionTarget = enableAndAwaitProjectionDisplay(
                context, expectedGeneration, pkg, target.kind, config, factoryMiniMap
            )
            if (projectionTarget.width <= 1 || projectionTarget.height <= 1) {
                throw IllegalStateException("invalid projection display size")
            }
            val physicalDisplayId = projectionTarget.displayId
            val targetDisplayId = projectionTarget.contentDisplayId
            if (physicalDisplayId <= Display.DEFAULT_DISPLAY ||
                targetDisplayId <= Display.DEFAULT_DISPLAY
            ) {
                throw IllegalStateException(
                    "invalid projection display identity physical=$physicalDisplayId content=$targetDisplayId"
                )
            }
            if (!isCurrent(expectedGeneration, pkg)) {
                if (projectionTarget.hostedResourceGeneration > 0 &&
                    DiLink5HostedProjectionBridge.release(
                        projectionTarget.hostedResourceGeneration, projectionTarget.sealion7Hdmi,
                    )) {
                    DiLink5ProjectionOwnership.clear(
                        projectionTarget.hostedResourceGeneration, true, readCurrentBootId()
                    )
                }
                return
            }

            var activeTarget = projectionTarget
            var live = Session(
                expectedGeneration,
                pkg,
                target.component,
                target.kind,
                physicalDisplayId,
                targetDisplayId,
                projectionTarget.name,
                projectionTarget.width,
                projectionTarget.height,
                projectionTarget.transport,
                projectionTarget.hostedResourceGeneration,
                projectionTarget.hostedGeometry,
                factoryMiniMap = factoryMiniMap,
                aaosMapArea = projectionTarget.aaosMapArea,
                sealion7Hdmi = projectionTarget.sealion7Hdmi,
            )
            session = live
            if (live.usesHdmiPanel) watchHdmiPanel(context, live)
            ClusterFreeformWindow.invalidateTaskCache()
            if (activeTarget.transport == "direct" && !factoryMiniMap) {
                ClusterFreeformWindow.beginDirectPlacementAttempt()
            }

            var launch = launchAndVerify(
                target, targetDisplayId, expectedGeneration
            )
            if (shouldFallbackToHostedAfterDirectFailure(
                    activeTarget.transport,
                    config.transport == DiLink5ProjectionConfig.Transport.AUTO,
                    launch.succeeded,
                    launch.definiteFailure,
                ) && isCurrent(expectedGeneration, pkg)
            ) {
                val hosted = if (ClusterFreeformWindow.prepareHostedFreeformState()) {
                    configureHostedProjectionTarget(
                        context,
                        activeTarget,
                        expectedGeneration,
                        config,
                    )
                } else {
                    null
                }
                if (hosted != null) {
                    activeTarget = hosted
                    live = live.copy(
                        targetDisplayId = hosted.contentDisplayId,
                        displayWidth = hosted.width,
                        displayHeight = hosted.height,
                        projectionTransport = "hosted",
                        hostedResourceGeneration = hosted.hostedResourceGeneration,
                        hostedGeometry = hosted.hostedGeometry,
                    )
                    synchronized(stateLock) {
                        if (!isCurrent(expectedGeneration, pkg)) return
                        session = live
                    }
                    if (!finishPreviousCustomPlacement(target, expectedGeneration)) return
                    ClusterFreeformWindow.invalidateTaskCache()
                    launch = launchAndVerify(target, hosted.contentDisplayId, expectedGeneration)
                }
            }
            if (!factoryMiniMap && !launch.succeeded && launch.foreignPackage != null &&
                activeTarget.transport == "direct"
            ) {
                // An OEM cluster app keeps re-fronting itself on this shared
                // surface (DiLink 5.0 Atto 3: com.byd.sr on _0 every ~2-9 s).
                // Fighting it fails by design after a bounded number of
                // takeovers; instead move to the sibling shared surface the
                // container service also exposes, and remember what worked.
                markDisplayReclaimed(activeTarget.name)
                val alternative = if (isCurrent(expectedGeneration, pkg)) {
                    resolveAlternativeSharedDisplay(
                        context.getSystemService(Context.DISPLAY_SERVICE)
                            as DisplayManager,
                        config.target == DiLink5ProjectionConfig.Target.FULL,
                        activeTarget.name
                    )
                } else {
                    null
                }
                if (alternative != null &&
                    alternative.displayId > Display.DEFAULT_DISPLAY &&
                    alternative.width > 1 && alternative.height > 1
                ) {
                    logger.warn(
                        "DI5 cluster cast: ${activeTarget.name} (display " +
                                "${activeTarget.displayId}) was reclaimed by " +
                                "${launch.foreignPackage}; retrying $pkg on " +
                                "${alternative.name} (display ${alternative.displayId})"
                    )
                    val deferredEnable = activeTarget.deferCompositorEnable
                    val alternativeAppVisible = if (deferredEnable) {
                        DiLink5HostedProjectionBridge.isAppDisplayVisible(
                            context, alternative.displayId,
                        )
                    } else false
                    if (!isCurrent(expectedGeneration, pkg)) return
                    val preparedAlternative = alternative.copy(
                        deferCompositorEnable = prepareDiLink5AlternativeCompositor(
                            deferredEnable,
                            alternativeAppVisible,
                        ) {
                            enableProjectionCompositorForCurrentSession(expectedGeneration, pkg)
                        },
                    )
                    if (target.kind == SessionKind.APP) {
                        // A running task keeps its old display affinity through
                        // `am start --display`; start it fresh on the new surface.
                        AppLauncher.forceStopPackage(pkg)
                        Thread.sleep(CONTAINER_SETTLE_MS)
                    }
                    if (!isCurrent(expectedGeneration, pkg)) return
                    activeTarget = preparedAlternative
                    live = live.copy(
                        physicalDisplayId = preparedAlternative.displayId,
                        targetDisplayId = preparedAlternative.contentDisplayId,
                        targetDisplayName = preparedAlternative.name,
                        displayWidth = preparedAlternative.width,
                        displayHeight = preparedAlternative.height,
                        projectionTransport = preparedAlternative.transport,
                        hostedResourceGeneration = preparedAlternative.hostedResourceGeneration,
                        hostedGeometry = preparedAlternative.hostedGeometry,
                    )
                    synchronized(stateLock) {
                        if (!isCurrent(expectedGeneration, pkg)) return
                        session = live
                    }
                    if (!finishPreviousCustomPlacement(target, expectedGeneration)) return
                    ClusterFreeformWindow.invalidateTaskCache()
                    ClusterFreeformWindow.beginDirectPlacementAttempt()
                    launch = launchAndVerify(
                        target, preparedAlternative.displayId, expectedGeneration
                    )
                    if (!launch.succeeded && launch.foreignPackage != null) {
                        markDisplayReclaimed(alternative.name)
                    }
                } else {
                    logger.warn(
                        "DI5 cluster cast: ${activeTarget.name} was reclaimed by " +
                                "${launch.foreignPackage} and no alternative shared " +
                                "display is available on this firmware"
                    )
                }
            }
            if (!launch.succeeded) {
                throw IllegalStateException(launch.reason)
            }
            if (!ClusterAppDensity.restoreForSessionExceptDisplay(
                    "di5:$expectedGeneration",
                    activeTarget.contentDisplayId,
                ) { isCurrent(expectedGeneration, pkg) }
            ) {
                throw IllegalStateException("previous display density could not be restored after task placement")
            }
            if (activeTarget.deferCompositorEnable &&
                !enableProjectionCompositorForCurrentSession(expectedGeneration, pkg)) {
                throw IllegalStateException("compositor enable failed after verified direct placement")
            }
            if (activeTarget.transport == "hosted") {
                val location = ClusterFreeformWindow.findTaskLocation(pkg, activeTarget.contentDisplayId)
                val currentOwner = DiLink5ProjectionOwnership.load(
                    activeTarget.hostedResourceGeneration
                ) ?: throw IllegalStateException("hosted ownership record is missing")
                if (currentOwner.generation != activeTarget.hostedResourceGeneration ||
                    !DiLink5ProjectionOwnership.persist(currentOwner.copy(
                        packageName = pkg,
                        taskId = location.taskId,
                        taskMayBeMoved = location.known && location.taskId > 0,
                        compositorOwned = poweredGeneration == expectedGeneration ||
                            enableIndeterminateGeneration == expectedGeneration,
                        uncertain = !location.known
                    ))) {
                    throw IllegalStateException("hosted ownership commit could not be persisted")
                }
            }
            if (!factoryMiniMap && !live.usesHdmiPanel) try {
                rememberStableSharedDisplay(
                    context.getSystemService(Context.DISPLAY_SERVICE)
                        as DisplayManager,
                    config.target == DiLink5ProjectionConfig.Target.FULL,
                    activeTarget.name,
                )
            } catch (_: Throwable) {
            }
            if (live.factoryMiniMap || (target.kind == SessionKind.APP && live.projectionTransport != "hosted")) {
                val placed = synchronized(taskMutationLock.lock) {
                    if (!isCurrent(expectedGeneration, pkg)) return
                    applyInitialDirectBounds(live)
                }
                if (live.factoryMiniMap && !placed) {
                    throw IllegalStateException("Could not verify the task's position in the factory mini-map window")
                }
            }
            if (activeTarget.transport == "hosted") {
                val owner = DiLink5ProjectionOwnership.load(
                    activeTarget.hostedResourceGeneration,
                ) ?: throw IllegalStateException("hosted ownership disappeared after placement")
                if (!DiLink5ProjectionOwnership.persist(owner.copy(
                        densityOwned = ClusterAppDensity.hasLease("di5:$expectedGeneration"),
                        densityOwner = if (ClusterAppDensity.hasLease("di5:$expectedGeneration"))
                            "di5:$expectedGeneration" else "",
                        freeformOwned = ClusterFreeformWindow.hasFreeformSupportOwnership(),
                    ))
                ) {
                    throw IllegalStateException("hosted post-placement ownership could not be persisted")
                }
            }
            // The panel can remain Off until the hosted task has a verified foreground and journal.
            if (live.sealion7Hdmi) {
                synchronized(taskMutationLock.lock) {
                    if (!isCurrent(expectedGeneration, pkg)) return
                    check(Sealion7HdmiProjection.isEnabled(context) &&
                        DiLink5HostedProjectionBridge.isSurfaceAlive(live.hostedResourceGeneration) &&
                        DiLink5InstrumentFullSession.start(context, expectedGeneration, readCurrentBootId()) {
                            isCurrent(expectedGeneration, pkg)
                        }
                    ) { "Could not activate the Sealion 7 instrument display" }
                }
            }
            synchronized(stateLock) {
                if (!isCurrent(expectedGeneration, pkg)) return
                val compositorPowered =
                    poweredGeneration == expectedGeneration
                val enableIndeterminate =
                    enableIndeterminateGeneration == expectedGeneration
                fun publishActive(): Boolean {
                    if (!writeMarker(
                            pending = false,
                            powered = compositorPowered,
                            appMoved = true,
                            enableIndeterminate = enableIndeterminate,
                            enableBootId =
                                if (enableIndeterminate) readCurrentBootId() else "",
                            pkg = pkg
                        )
                    ) {
                        throw IllegalStateException(
                            "active-state marker could not be persisted"
                        )
                    }
                    phase = SessionPhase.ACTIVE
                    return true
                }
                if (live.projectionTransport == "hosted") {
                    if (DiLink5HostedProjectionBridge.withLiveSurfaceGeneration(
                            live.hostedResourceGeneration,
                            ::publishActive,
                        ) != true
                    ) {
                        throw IllegalStateException(
                            "hosted projection surface was lost before publication"
                        )
                    }
                } else {
                    publishActive()
                }
            }
            completeStart(expectedGeneration, success = true, reason = "")
            logger.info(
                "DI5 cluster cast: $pkg remained foreground on OEM display " +
                        "${live.targetDisplayId} via ${activeTarget.name}"
            )
            startTaskGuardian(live, target)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (isCurrent(expectedGeneration, pkg)) {
                failCurrent(expectedGeneration, pkg, "projection start interrupted")
            }
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            logger.warn("DI5 cluster cast failed: $reason")
            if (isCurrent(expectedGeneration, pkg)) {
                failCurrent(expectedGeneration, pkg, reason)
            }
        }
    }

    /** A singleInstance activity left behind on the previous display can absorb the new launch. */
    private fun finishPreviousCustomPlacement(target: LaunchTarget, expectedGeneration: Long): Boolean {
        if (target.kind == SessionKind.CUSTOM) {
            com.overdrive.app.customlayouts.daemon.projection.ClusterActivityLauncher.requestFinish(expectedGeneration)
            Thread.sleep(CUSTOM_FINISH_GRACE_MS)
        }
        return isCurrent(expectedGeneration, target.pkg)
    }

    private fun launchAndVerify(
        target: LaunchTarget,
        displayId: Int,
        expectedGeneration: Long
    ): LaunchVerification {
        if (target.kind == SessionKind.APP) {
            val densityDecision = densityAdoptionDecision(
                ClusterFreeformWindow.packageTaskOnDisplay(target.pkg, displayId)
            )
            if (densityDecision == DensityAdoptionDecision.APPLY_FOR_NEW_TASK) {
                // Restoring an old lease is itself a display mutation. Do it only after the
                // complete task inventory proves this launch is not adopting an on-target task.
                if (!ClusterAppDensity.restoreForDisplay(displayId) {
                        isCurrent(expectedGeneration, target.pkg)
                    }) {
                    return LaunchVerification(
                        false,
                        "Previous cluster density could not be restored",
                        definiteFailure = true,
                    )
                }
                ClusterAppDensity.applyForLaunch(
                    target.pkg, displayId, "di5:$expectedGeneration"
                ) { isCurrent(expectedGeneration, target.pkg) }
            } else if (densityDecision == DensityAdoptionDecision.DEFER_UNCERTAIN) {
                logger.warn(
                    "DI5 cluster cast: task inventory was incomplete; preserving display density"
                )
            }
        } else {
            if (!ClusterAppDensity.restoreForDisplay(displayId) {
                    isCurrent(expectedGeneration, target.pkg)
                }) {
                return LaunchVerification(
                    false,
                    "Previous cluster density could not be restored",
                    definiteFailure = true,
                )
            }
        }
        if (!markAppMayMove(expectedGeneration, target.pkg)) {
            return LaunchVerification(
                false,
                lastFailureReason.ifEmpty {
                    "app-move recovery marker could not be persisted"
                },
                definiteFailure = true,
            )
        }
        var stateKnown = false
        var launchCommandRejected = false
        var lastForeignPackage: String? = null

        fun remember(result: ForegroundVerification): Boolean {
            stateKnown = stateKnown || result.stateKnown
            if (!result.lastForeignPackage.isNullOrEmpty()) {
                lastForeignPackage = result.lastForeignPackage
            }
            return result.stable
        }

        repeat(VERIFY_ATTEMPTS) {
            if (!isCurrent(expectedGeneration, target.pkg)) {
                return LaunchVerification(false, "projection start superseded")
            }
            if (!launchTarget(target, displayId, expectedGeneration)) launchCommandRejected = true
            val verification = awaitStableTargetForeground(
                target, displayId, expectedGeneration
            )
            if (remember(verification)) {
                return LaunchVerification(true)
            }
            if (verification.oemReclaimedAfterCorrection) {
                // Definitive: the OEM owns this surface. A second attempt and
                // the stale-task restart below would only flicker the cluster
                // again; hand the takeover straight to the shared-display
                // fallback in startSession.
                return LaunchVerification(
                    false,
                    foregroundFailureReason(
                        target.pkg, displayId, stateKnown, lastForeignPackage
                    ),
                    definiteFailure = true,
                    foreignPackage = lastForeignPackage
                )
            }
        }

        // An already-running navigation task can retain its display-0 affinity even
        // when `am start --display` is accepted. Only after normal launch and the
        // task-specific move both failed, restart that third-party package and create
        // a fresh task directly on the OEM target. The stable-foreground loop already
        // performs that exact move. If the task is on the target and another package owns
        // the foreground, force-stopping our app cannot solve the OEM takeover and is skipped.
        // Never force-stop our own map process.
        val restartLocation =
            if (target.kind == SessionKind.APP) {
                ClusterFreeformWindow.findTaskLocation(
                    target.pkg, displayId
                )
            } else {
                null
            }
        val staleOffDisplayTask = shouldForceRestartDiLink5Task(
            restartLocation?.known == true,
            restartLocation?.taskId ?: -1,
            restartLocation?.displayId ?: -1,
            displayId
        )
        if (target.kind == SessionKind.APP &&
            staleOffDisplayTask &&
            isCurrent(expectedGeneration, target.pkg) &&
            AppLauncher.forceStopPackage(target.pkg)
        ) {
            Thread.sleep(CONTAINER_SETTLE_MS)
            if (!launchTarget(target, displayId, expectedGeneration)) launchCommandRejected = true
            val finalVerification = awaitStableTargetForeground(
                target, displayId, expectedGeneration
            )
            if (remember(finalVerification)) {
                logger.warn(
                    "DI5 cluster cast: recovered ${target.pkg} by restarting " +
                            "its stale off-display task"
                )
                return LaunchVerification(true)
            }
        }
        return LaunchVerification(
            false,
            foregroundFailureReason(
                target.pkg,
                displayId,
                stateKnown,
                lastForeignPackage
            ),
            definiteFailure = stateKnown || launchCommandRejected,
            foreignPackage = lastForeignPackage?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Do not accept a single RESUMED sample as proof. The DI5 OEM projection service has been
     * observed re-fronting its own cluster map roughly 1.1–2 seconds after a foreign app launch,
     * while leaving the foreign task on the correct display. Require an uninterrupted foreground
     * interval longer than that window and, for app casts, refocus the exact task when the OEM
     * covers it on the same display.
     */
    private fun awaitStableTargetForeground(
        target: LaunchTarget,
        displayId: Int,
        expectedGeneration: Long
    ): ForegroundVerification {
        val started = android.os.SystemClock.elapsedRealtime()
        val deadline = started + START_FOREGROUND_OBSERVE_MS
        var stableSince = -1L
        var stateKnown = false
        var lastForeignPackage: String? = null
        var lastObservedForeground: String? = null
        var nextCorrectionAt = started
        // Corrections issued while an OEM cluster app covered us. One correction
        // is legitimate (the OEM re-fronts its meter once ~1-2 s after a foreign
        // launch and then yields); if the OEM takes the surface back AGAIN after
        // that, it owns this surface and every further refocus/relaunch is one
        // more visible flicker for the driver (field log 2026-09-24 03:01:46-57:
        // five flickers on shared _0 before the fallback to _1 was reached).
        var oemCorrections = 0

        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(START_FOREGROUND_POLL_MS)
            if (!isCurrent(expectedGeneration, target.pkg)) {
                return ForegroundVerification(
                    false, stateKnown, lastForeignPackage
                )
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val foreground =
                ClusterCast.resumedPackageOnDisplay(displayId)
            if (foreground != null) stateKnown = true

            if (foreground == target.pkg) {
                if (stableSince < 0L) stableSince = now
                if (now - stableSince >= START_FOREGROUND_STABLE_MS) {
                    return ForegroundVerification(
                        true, true, lastForeignPackage
                    )
                }
                lastObservedForeground = foreground
                continue
            }

            stableSince = -1L
            if (!foreground.isNullOrEmpty()) {
                lastForeignPackage = foreground
                if (foreground != lastObservedForeground) {
                    logger.warn(
                        "DI5 cluster cast: display $displayId foreground changed " +
                                "from ${lastObservedForeground ?: "unknown"} to " +
                                "$foreground while starting ${target.pkg}"
                    )
                    if (oemCorrections > 0 && isOemClusterForeground(foreground)) {
                        logger.warn(
                            "DI5 cluster cast: $foreground reclaimed display " +
                                    "$displayId again after a correction; this " +
                                    "surface belongs to the OEM — stop fighting it"
                        )
                        return ForegroundVerification(
                            false, stateKnown, foreground,
                            oemReclaimedAfterCorrection = true
                        )
                    }
                }
            }
            lastObservedForeground = foreground

            if (target.kind != SessionKind.APP || now < nextCorrectionAt) {
                continue
            }
            val location = ClusterFreeformWindow.findTaskLocation(
                target.pkg, displayId
            )
            val corrected = when {
                !location.known || location.taskId <= 0 -> false
                location.displayId == displayId ->
                    ClusterFreeformWindow.focusTask(
                        location.taskId, displayId
                    )
                location.displayId >= 0 ->
                    ClusterFreeformWindow.moveTaskToDisplay(
                        location.taskId, displayId
                    )
                else -> false
            }
            if (corrected) {
                logger.info(
                    "DI5 cluster cast: startup foreground recovery " +
                            "task=${location.taskId} from=${location.displayId} " +
                            "to=$displayId coveredBy=${foreground ?: "unknown"}"
                )
                if (!foreground.isNullOrEmpty() && isOemClusterForeground(foreground)) {
                    oemCorrections++
                }
            }
            nextCorrectionAt = now + START_CORRECTION_COOLDOWN_MS
        }
        return ForegroundVerification(
            false, stateKnown, lastForeignPackage
        )
    }

    private fun foregroundFailureReason(
        pkg: String,
        displayId: Int,
        stateKnown: Boolean,
        lastForeignPackage: String?
    ): String {
        if (!lastForeignPackage.isNullOrEmpty()) {
            return if (isOemClusterForeground(lastForeignPackage)) {
                "OEM projection service repeatedly reclaimed display " +
                        "$displayId with $lastForeignPackage while projecting $pkg"
            } else {
                "display $displayId foreground was repeatedly taken by " +
                        "$lastForeignPackage while projecting $pkg"
            }
        }
        return if (!stateKnown) {
            "could not verify the resumed foreground activity on OEM display " +
                    displayId
        } else {
            "$pkg did not remain foreground on OEM display $displayId"
        }
    }

    private fun isOemClusterForeground(pkg: String): Boolean {
        val normalized = pkg.lowercase(Locale.US)
        // The shared cluster displays are owned by BYD's container service; any
        // com.byd.* app fronting there is an OEM cluster owner. Seen in the field:
        // com.byd.launchermap (nav projection) and com.byd.sr (the ADAS "SR"
        // rendering view on DiLink 5.0 Atto 3 units, which re-fronts itself on
        // shared display _0 every few seconds).
        return normalized == "com.byd.launchermap" ||
                normalized == "com.byd.sr" ||
                normalized == "com.example.amapservice" ||
                normalized.startsWith("com.byd.")
    }

    private fun markAppMayMove(expectedGeneration: Long, pkg: String): Boolean {
        synchronized(stateLock) {
            if (!isCurrent(expectedGeneration, pkg)) return false
            if (appMovedGeneration == expectedGeneration) return true
            // Persist conservatively before launching. If AMS accepts the launch and
            // the daemon dies before verification, boot recovery must still re-home it.
            val compositorPowered =
                poweredGeneration == expectedGeneration
            val enableIndeterminate =
                enableIndeterminateGeneration == expectedGeneration
            if (!writeMarker(
                    pending = true,
                    powered = compositorPowered,
                    appMoved = true,
                    enableIndeterminate = enableIndeterminate,
                    enableBootId =
                        if (enableIndeterminate) readCurrentBootId() else "",
                    pkg = pkg
                )
            ) {
                lastFailureReason = "app-move marker could not be persisted"
                return false
            }
            appMovedGeneration = expectedGeneration
            return true
        }
    }

    /** Android 12 can size a mini-map task that starts in freeform on the selected factory display. */
    private fun launchTarget(target: LaunchTarget, displayId: Int, launchGeneration: Long = 0L): Boolean {
        val factoryMiniMap = session?.let {
            it.generation == launchGeneration && it.targetDisplayId == displayId && it.factoryMiniMap
        } == true
        if (target.kind == SessionKind.APP && !factoryMiniMap) {
            return AppLauncher.launchOnDisplay(target.pkg, displayId)
        }
        var process: Process? = null
        return try {
            val command = arrayListOf(
                "/system/bin/am", "start",
                "--user", "0",
                "--display", displayId.toString(),
                "--windowingMode", if (factoryMiniMap) "5" else "1",
                "-f", "0x10000000",
            )
            if (target.kind == SessionKind.CUSTOM) {
                command += customLayoutsLaunchExtras(displayId, launchGeneration) ?: return false
            } else if (target.kind == SessionKind.MAP) {
                command += listOf("--ez", "cluster", "true")
            }
            command += listOf("-n", target.component)
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (t: Throwable) {
            logger.warn("DI5 cluster map launch failed: ${t.message}")
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Guard both DI5 failure modes after startup:
     *
     *  1. a navigation app creates/rebounds a task onto display 0 several seconds later;
     *  2. the OEM leaves our task on the shared display but re-fronts its own MeterActivity.
     *
     * Placement alone cannot detect (2), so every verdict also requires our package to own the
     * per-display resumed foreground. Repeated same-display takeovers fail the session explicitly
     * instead of fighting the OEM indefinitely or leaving the API in a false-active state.
     */
    private fun startTaskGuardian(live: Session, target: LaunchTarget) {
        Thread({
            val started = android.os.SystemClock.elapsedRealtime()
            val hardDeadline = started + TASK_GUARD_MAX_MS
            var completionAt = started + TASK_GUARD_INITIAL_MS
            var unknownTaskPolls = 0
            var unknownForegroundPolls = 0
            var consecutivePlacementFailures = 0
            var consecutiveForeignPolls = 0
            var foregroundTakeovers = 0
            var placedTaskId = 0
            val densityLossEpisodes = DirectDensityLossEpisodes(live.generation)
            var lastObservedForeground: String? = live.pkg
            var lastForeignPackage: String? = null
            while (android.os.SystemClock.elapsedRealtime() < hardDeadline) {
                try {
                    Thread.sleep(TASK_GUARD_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                if (!isLiveSession(live)) return@Thread

                val foreground =
                    ClusterCast.resumedPackageOnDisplay(live.targetDisplayId)
                val foregroundKnown = foreground != null
                val foregroundOurs = foreground == live.pkg
                if (foregroundKnown) {
                    unknownForegroundPolls = 0
                } else {
                    unknownForegroundPolls++
                }
                if (!foreground.isNullOrEmpty() && !foregroundOurs) {
                    consecutiveForeignPolls++
                    if (foreground != lastObservedForeground) {
                        foregroundTakeovers++
                        logger.warn(
                            "DI5 task guardian: display ${live.targetDisplayId} " +
                                    "foreground changed from " +
                                    "${lastObservedForeground ?: "unknown"} to " +
                                    "$foreground; ours=${live.pkg}"
                        )
                    }
                    lastForeignPackage = foreground
                } else if (foregroundOurs) {
                    consecutiveForeignPolls = 0
                }
                lastObservedForeground = foreground

                var taskLocation: ClusterFreeformWindow.TaskLocation? = null
                var placementNeedsCorrection = false
                if (live.kind == SessionKind.APP) {
                    taskLocation = ClusterFreeformWindow.findTaskLocation(
                        live.pkg, live.targetDisplayId
                    )
                    when {
                        !taskLocation.known -> unknownTaskPolls++
                        taskLocation.taskId <= 0 -> {
                            unknownTaskPolls = 0
                            placementNeedsCorrection = true
                        }
                        taskLocation.displayId >= 0 -> {
                            unknownTaskPolls = 0
                            placementNeedsCorrection =
                                taskLocation.displayId != live.targetDisplayId
                            if (!placementNeedsCorrection) {
                                densityLossEpisodes.confirmTaskReturn(
                                    taskLocation, live.targetDisplayId,
                                )
                                placedTaskId = taskLocation.taskId
                            }
                        }
                        else -> unknownTaskPolls++
                    }
                }

                var onTarget = foregroundOurs && !placementNeedsCorrection
                val foregroundNeedsCorrection = !foregroundOurs &&
                        (foregroundKnown ||
                                unknownForegroundPolls >=
                                TASK_GUARD_UNKNOWN_RETRY_COUNT)
                val taskStateNeedsCorrection =
                    placementNeedsCorrection ||
                            (live.kind == SessionKind.APP &&
                                    unknownTaskPolls >=
                                    TASK_GUARD_UNKNOWN_RETRY_COUNT &&
                                    !foregroundOurs)
                val shouldCorrect =
                    foregroundNeedsCorrection || taskStateNeedsCorrection

                if (!foreground.isNullOrEmpty() &&
                    !foregroundOurs &&
                    (consecutiveForeignPolls >=
                            TASK_GUARD_MAX_FOREGROUND_TAKEOVERS ||
                            foregroundTakeovers >=
                            TASK_GUARD_MAX_FOREGROUND_TAKEOVERS)
                ) {
                    val reason = foregroundFailureReason(
                        live.pkg,
                        live.targetDisplayId,
                        true,
                        foreground
                    )
                    failGuardedSession(live, reason, foreground, target)
                    return@Thread
                }

                if (shouldCorrect) {
                    if (placementNeedsCorrection) {
                        consecutivePlacementFailures++
                        val densityLossReason = if (live.projectionTransport == "direct" &&
                            taskLocation != null && taskLocation.known &&
                            ClusterFreeformWindow.packageTaskOnDisplay(
                                live.pkg,
                                live.targetDisplayId,
                            ) == false
                        ) directDensityLossReason(
                                placedTaskId,
                                taskLocation.taskId,
                                taskLocation.displayId,
                                live.targetDisplayId,
                                taskLocation.windowingMode,
                            ) else null
                        val resetFailed = densityLossReason != null && withLiveTaskMutation(live, false) {
                            ClusterAppDensity.recordPostLaunchLoss(
                                live.pkg,
                                "di5:${live.generation}",
                                densityLossEpisodes.episodeForLoss(placedTaskId),
                                densityLossReason,
                            ) && !ClusterAppDensity.resetDirectScaleToNativeForRetry(
                                "di5:${live.generation}",
                            ) { isLiveSession(live) }
                        }
                        if (resetFailed) {
                            failGuardedSession(
                                live,
                                "cluster density could not reset before native retry",
                                foreground,
                                target,
                            )
                            return@Thread
                        }
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    completionAt = maxOf(
                        completionAt,
                        minOf(
                            hardDeadline,
                            now + TASK_GUARD_AFTER_REANCHOR_MS
                        )
                    )
                    val moved = taskLocation != null &&
                            taskLocation.known &&
                            taskLocation.taskId > 0 &&
                            taskLocation.displayId >= 0 &&
                            taskLocation.displayId != live.targetDisplayId &&
                            withLiveTaskMutation(live, false) {
                                ClusterFreeformWindow.moveTaskToDisplay(
                                    taskLocation.taskId, live.targetDisplayId
                                )
                            }
                    val focused = taskLocation != null &&
                            taskLocation.known &&
                            taskLocation.taskId > 0 &&
                            taskLocation.displayId == live.targetDisplayId &&
                            !foregroundOurs &&
                            withLiveTaskMutation(live, false) {
                                ClusterFreeformWindow.focusTask(
                                    taskLocation.taskId, live.targetDisplayId
                                )
                            }
                    var relaunched = false
                    val corrected: Boolean
                    if (live.kind == SessionKind.APP &&
                        placementNeedsCorrection &&
                        consecutivePlacementFailures >=
                        TASK_GUARD_FORCE_RESTART_COUNT
                    ) {
                        relaunched = withLiveTaskMutation(live, false) {
                            val stopped = AppLauncher.forceStopPackage(live.pkg)
                            if (stopped && live.projectionTransport == "direct" && !live.factoryMiniMap) {
                                ClusterFreeformWindow.beginDirectPlacementAttempt()
                            }
                            stopped && launchTarget(target, live.targetDisplayId, live.generation)
                        }
                        corrected = relaunched
                        consecutivePlacementFailures = 0
                    } else {
                        if (moved || focused) {
                            corrected = true
                        } else {
                            relaunched = withLiveTaskMutation(live, false) {
                                if (live.projectionTransport == "direct" && !live.factoryMiniMap) {
                                    ClusterFreeformWindow.beginDirectPlacementAttempt()
                                }
                                launchTarget(target, live.targetDisplayId, live.generation)
                            }
                            corrected = relaunched
                        }
                    }
                    if (relaunched) {
                        placedTaskId = 0
                    }
                    if (corrected &&
                        (live.kind == SessionKind.APP || live.factoryMiniMap) &&
                        live.projectionTransport != "hosted" &&
                        (moved || relaunched)
                    ) {
                        val placed = withLiveTaskMutation(live, false) {
                            applyInitialDirectBounds(live)
                        }
                        if (live.factoryMiniMap && !placed) {
                            failGuardedSession(live, "The task did not return to the factory mini-map window", target = target)
                            return@Thread
                        }
                    }
                    logger.info(
                        "DI5 task guardian: ${live.pkg} " +
                                "task=${taskLocation?.taskId ?: -1} " +
                                "from=${taskLocation?.displayId ?: -1} " +
                                "to=${live.targetDisplayId} " +
                                "foreground=${foreground ?: "unknown"} " +
                                "moved=$moved focused=$focused " +
                                "relaunched=$relaunched " +
                                "corrected=$corrected"
                    )
                    if (corrected) {
                        try {
                            Thread.sleep(VERIFY_POLL_MS)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                    onTarget = corrected && isTaskForegroundOnTarget(live) { returnedTask ->
                        if (densityLossEpisodes.confirmTaskReturn(
                                returnedTask, live.targetDisplayId,
                            )
                        ) placedTaskId = returnedTask.taskId
                    }
                } else if (onTarget) {
                    consecutivePlacementFailures = 0
                }

                if (onTarget &&
                    android.os.SystemClock.elapsedRealtime() >= completionAt
                ) {
                    logger.info(
                        "DI5 task guardian: stable ${live.pkg} on display " +
                                "${live.targetDisplayId}"
                    )
                    startLifecycleGuardian(live, target)
                    return@Thread
                }
            }

            if (!isLiveSession(live)) return@Thread
            val finalOnTarget = isTaskForegroundOnTarget(live)
            if (!finalOnTarget) {
                val reason = if (!lastForeignPackage.isNullOrEmpty()) {
                    foregroundFailureReason(
                        live.pkg,
                        live.targetDisplayId,
                        true,
                        lastForeignPackage
                    )
                } else if (unknownForegroundPolls > 0) {
                    "could not verify the resumed foreground activity on OEM " +
                            "display ${live.targetDisplayId}"
                } else {
                    "projected app repeatedly left OEM display " +
                            live.targetDisplayId
                }
                failGuardedSession(
                    live,
                    reason,
                    lastForeignPackage?.takeIf { it.isNotEmpty() },
                    target
                )
            } else {
                startLifecycleGuardian(live, target)
            }
        }, "DiLink5TaskGuardian-${live.generation}").apply {
            isDaemon = true
            start()
        }
    }

    private fun hdmiPanelReady(context: Context, live: Session): Boolean {
        if (live.sealion7Hdmi) {
            if (!AccMonitor.isAccStateAuthoritative() || !AccMonitor.isAccOn() ||
                Sealion7HdmiProjection.findDisplay(context)?.displayId != live.physicalDisplayId
            ) return false
            return if (phase == SessionPhase.ACTIVE) {
                DiLink5InstrumentFullSession.isSelectionCurrent(context, live.generation)
            } else {
                DiLink5InstrumentFullSession.isSupportedSelection()
            }
        }
        val area = live.hostedGeometry?.overlay ?: return false
        return AaosNavigationPresentation.isOpen(context, live.physicalDisplayId) &&
            DiLink5HostedProjectionBridge.probeAppDisplay(context, live.physicalDisplayId, aaosMapArea = true)
                ?.confirmedMapBounds == area
    }

    private fun watchHdmiPanel(context: Context, live: Session) {
        Thread({
            while (isCurrent(live.generation, live.pkg) && session === live) {
                if (!hdmiPanelReady(context, live)) {
                    if (isCurrent(live.generation, live.pkg) && session === live) {
                        DiLink5HostedProjectionBridge.withdrawMapArea(
                            live.hostedResourceGeneration, live.sealion7Hdmi,
                        )
                    }
                    return@Thread
                }
                try {
                    Thread.sleep(TASK_GUARD_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "AaosClusterMapArea-${live.generation}").apply {
            isDaemon = true
            start()
        }
    }

    private fun startLifecycleGuardian(live: Session, target: LaunchTarget) {
        val key = "${live.generation}:${live.targetDisplayId}:${live.hostedResourceGeneration}"
        synchronized(stateLock) {
            if (!lifecycleGuardians.add(key)) return
        }
        Thread({
            var missingDisplay = 0
            var missingTask = 0
            try {
                while (isLiveSession(live)) {
                    try {
                        Thread.sleep(LIFECYCLE_GUARD_POLL_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (!isLiveSession(live)) return@Thread
                    if (live.projectionTransport == "hosted" &&
                        !DiLink5HostedProjectionBridge.isSurfaceAlive(live.hostedResourceGeneration)
                    ) {
                        onHostedSurfaceLost(live.hostedResourceGeneration)
                        return@Thread
                    }
                    val context = CameraDaemon.getAppContext()
                    val manager = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    var displayDumpLoaded = false
                    var displayDump: String? = null
                    fun displayPresent(id: Int): Boolean? {
                        if (id <= 0) return false
                        manager?.getDisplay(id)?.let { return it.isValid }
                        if (!displayDumpLoaded) {
                            displayDump = ClusterDisplayReader.readDisplayDump()
                            displayDumpLoaded = true
                        }
                        val dump = displayDump ?: return null
                        return ClusterDisplaySnapshot.find(dump, id) != null
                    }
                    val displayEvidence = listOf(
                        displayPresent(live.physicalDisplayId),
                        displayPresent(live.targetDisplayId),
                    )
                    when {
                        displayEvidence.any { it == false } -> missingDisplay++
                        displayEvidence.all { it == true } -> missingDisplay = 0
                        else -> Unit
                    }

                    when (ClusterFreeformWindow.packageTaskOnDisplay(
                        live.pkg,
                        live.targetDisplayId,
                    )) {
                        true -> missingTask = 0
                        false -> missingTask++
                        null -> Unit
                    }
                    if (missingDisplay >= LIFECYCLE_GUARD_FAILURES ||
                        missingTask >= LIFECYCLE_GUARD_FAILURES
                    ) {
                        failGuardedSession(
                            live,
                            if (missingDisplay >= LIFECYCLE_GUARD_FAILURES) {
                                "projection display disappeared after startup"
                            } else {
                                "projected task exited after startup"
                            },
                            target = target,
                        )
                        return@Thread
                    }
                }
            } finally {
                synchronized(stateLock) { lifecycleGuardians.remove(key) }
            }
        }, "DiLink5LifecycleGuardian-$key").apply {
            isDaemon = true
            start()
        }
    }

    private fun <T> withLiveTaskMutation(
        live: Session,
        staleResult: T,
        mutation: () -> T,
    ): T = synchronized(taskMutationLock.lock) {
        if (!isLiveSession(live)) staleResult else mutation()
    }

    private fun isLiveSession(live: Session): Boolean =
        session === live &&
                generation == live.generation &&
                activePackage == live.pkg &&
                phase == SessionPhase.ACTIVE &&
                !stopping

    private fun isTaskForegroundOnTarget(
        live: Session,
        observeTask: ((ClusterFreeformWindow.TaskLocation) -> Unit)? = null,
    ): Boolean {
        if (ClusterCast.resumedPackageOnDisplay(live.targetDisplayId) != live.pkg) {
            return false
        }
        if (live.kind != SessionKind.APP) return true
        val location = ClusterFreeformWindow.findTaskLocation(
            live.pkg, live.targetDisplayId
        )
        observeTask?.invoke(location)
        return (location.known &&
                location.taskId > 0 &&
                location.displayId == live.targetDisplayId) ||
                !location.known ||
                location.displayId < 0
    }

    private fun failGuardedSession(
        live: Session,
        reason: String,
        foreignPackage: String? = null,
        target: LaunchTarget? = null
    ) {
        if (!isLiveSession(live)) return
        logger.warn("DI5 task guardian failed: $reason")
        // An OEM cluster app reclaimed the surface AFTER the start had verified
        // stable (com.byd.sr re-fronts on _0 every few seconds on DiLink 5.0
        // Atto 3 units). Resolve the sibling shared surface BEFORE tearing down
        // so the restart below is steered onto it; the reclaimed one is skipped
        // for RECLAIMED_DISPLAY_MEMORY_MS, which bounds this to one migration
        // per surface instead of an endless ping-pong.
        var migrateTo: ProjectionTarget? = null
        if (!live.factoryMiniMap && !live.usesHdmiPanel &&
            !foreignPackage.isNullOrEmpty() && target != null
        ) {
            markDisplayReclaimed(live.targetDisplayName)
            migrateTo = try {
                val context = CameraDaemon.getAppContext()
                if (context == null) {
                    null
                } else {
                    resolveAlternativeSharedDisplay(
                        context.getSystemService(Context.DISPLAY_SERVICE)
                            as DisplayManager,
                        readPreferFullCluster(),
                        live.targetDisplayName
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (live.kind == SessionKind.CUSTOM) {
            // Its blind-spot card must be handed back before this projection goes.
            try {
                com.overdrive.app.customlayouts.daemon.CustomLayoutsController.onDi5SessionStopping(live.generation)
            } catch (t: Throwable) {
                logger.warn("DI5 cluster cast: Custom Layouts stop notice failed: ${t.message}")
            }
        }
        val stopped = stopInternal(
            live.generation,
            rehome = true,
            foreground = false,
            expectedKind = live.kind
        )
        if (stopped && migrateTo != null && target != null) {
            val idle = synchronized(stateLock) {
                activePackage == null &&
                        activeCleanup == null &&
                        phase == SessionPhase.IDLE
            }
            if (idle) {
                logger.warn(
                    "DI5 cluster cast: migrating ${live.pkg} from " +
                            "${live.targetDisplayName} to ${migrateTo.name} " +
                            "(display ${migrateTo.displayId}) after " +
                            "$foreignPackage reclaimed the foreground"
                )
                if (live.kind == SessionKind.APP) {
                    // The re-homed task keeps its display-0 affinity through
                    // `am start --display`; a fresh task lands on the new surface.
                    AppLauncher.forceStopPackage(live.pkg)
                    try {
                        Thread.sleep(CONTAINER_SETTLE_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                val replacement = if (live.kind == SessionKind.CUSTOM) {
                    target.copy(previousCustomGeneration = live.generation)
                } else target.copy(automatic = true)
                if (startTarget(replacement, preempt = false) != null) return
                logger.warn("DI5 cluster cast: migration start was not admitted")
            }
        }
        synchronized(stateLock) {
            if (activePackage == null &&
                phase == SessionPhase.IDLE
            ) {
                phase = SessionPhase.FAILED
                lastFailureReason = if (stopped) reason
                else "$reason; cleanup incomplete"
            }
        }
        if (live.kind == SessionKind.MAP) {
            com.overdrive.app.navmap.ClusterMapProjector
                .onDiLink5ProjectionFailed()
        } else if (live.kind == SessionKind.CUSTOM) {
            notifyCustomLayoutsEnded(live.generation, if (stopped) reason else "$reason; cleanup incomplete")
        }
    }

    private fun notifyCustomLayoutsEnded(generation: Long, reason: String) {
        try {
            com.overdrive.app.customlayouts.daemon.CustomLayoutsController.onDi5SessionEnded(generation, reason)
        } catch (t: Throwable) {
            logger.warn("DI5 cluster cast: Custom Layouts end notice failed: ${t.message}")
        }
    }

    private fun failCurrent(expectedGeneration: Long, pkg: String, reason: String) {
        val failedKind: SessionKind?
        val appMayBeMoved: Boolean
        val compositorPowered: Boolean
        val compositorEnableIndeterminate: Boolean
        val compositorTeardownIndeterminate: Boolean
        val cleanupTicket: CleanupTicket
        synchronized(stateLock) {
            if (generation != expectedGeneration || activePackage != pkg) return
            failedKind = activeKind
            cleanupTicket = CleanupTicket(customGeneration = if (failedKind == SessionKind.CUSTOM) expectedGeneration else 0L)
            if (failedKind == SessionKind.CUSTOM) lastCustomCleanup = cleanupTicket
            appMayBeMoved = appMovedGeneration == expectedGeneration
            compositorPowered = poweredGeneration == expectedGeneration
            compositorEnableIndeterminate =
                enableIndeterminateGeneration == expectedGeneration
            compositorTeardownIndeterminate =
                teardownIndeterminateGeneration == expectedGeneration
            activePackage = null
            activeKind = null
            stopping = true
            phase = SessionPhase.STOPPING
            lastFailureReason = reason
            cleanupUncertain = true
            activeCleanup = cleanupTicket
        }
        var rehomed = false
        var densityRestored = false
        var hostedReleased = false
        var instrumentRestored = false
        var poweredDown = false
        var markerWritten = false
        try {
            try {
                ClusterViewMirrorService.forceDetachIfActive("di5-start-failure")
            } catch (_: Throwable) {
            }
            val live = session?.takeIf { it.generation == expectedGeneration }
            instrumentRestored = releaseInstrumentMode(live)
            if (appMayBeMoved && failedKind == SessionKind.APP) {
                try {
                    ClusterFreeformWindow.restoreFullscreen(
                        pkg, live?.targetDisplayId ?: -1
                    )
                } catch (_: Throwable) {
                }
            }
            rehomed = !appMayBeMoved ||
                    failedKind == SessionKind.MAP ||
                    failedKind == SessionKind.CUSTOM ||
                    rehomeTask(pkg, foreground = false)
            if (!rehomed && failedKind == SessionKind.APP) {
                // Same last resort boot recovery already uses: the app was launched
                // by us seconds ago and never became a usable session, so removing
                // its task is strictly better than leaving the cluster-affinity
                // residue that made every following start refuse with "cleanup
                // incomplete; restart required" until the daemon was restarted.
                rehomed = forceStopAndConfirmAbsent(pkg)
            }
            if (rehomed) {
                densityRestored = ClusterAppDensity.restoreForSessionFamily("di5:$expectedGeneration")
                hostedReleased = completeHostedGenerationCleanup(
                    densityRestored,
                    ClusterAppDensity.hasLeaseFamily("di5:$expectedGeneration"),
                ) { releaseHostedResources(expectedGeneration, live?.hostedResourceGeneration ?: 0L) }
            } else {
                ClusterAppDensity.deferRestoration("di5:$expectedGeneration")
            }
            if (live != null) session = null
            ClusterFreeformWindow.invalidateTaskCache()
            poweredDown = !compositorPowered || powerDownCompositor(
                ownershipGeneration = expectedGeneration,
                retainIndeterminateOwnership =
                    compositorEnableIndeterminate,
                teardownMayStillComplete =
                    compositorTeardownIndeterminate
            )
            markerWritten = if (rehomed && densityRestored && hostedReleased && poweredDown) {
                writeMarker(
                    pending = false,
                    powered = false,
                    appMoved = false,
                    enableIndeterminate = false,
                    enableBootId = "",
                    pkg = ""
                )
            } else {
                val retainEnable =
                    compositorEnableIndeterminate && !poweredDown
                writeMarker(
                    pending = false,
                    powered = compositorPowered && !poweredDown,
                    appMoved = appMayBeMoved && !rehomed,
                    enableIndeterminate = retainEnable,
                    enableBootId =
                        if (retainEnable) readCurrentBootId() else "",
                    pkg = pkg
                )
            }
        } finally {
            val cleanupComplete = rehomed && densityRestored && hostedReleased &&
                poweredDown && markerWritten && instrumentRestored
            val finalReason = if (cleanupComplete) {
                reason
            } else {
                "$reason; cleanup incomplete"
            }
            synchronized(stateLock) {
                if (generation == expectedGeneration &&
                    activePackage == null &&
                    activeCleanup === cleanupTicket
                ) {
                    if (poweredDown &&
                        poweredGeneration == expectedGeneration
                    ) {
                        poweredGeneration = 0L
                    }
                    if (poweredDown &&
                        enableIndeterminateGeneration == expectedGeneration
                    ) {
                        enableIndeterminateGeneration = 0L
                    }
                    if (rehomed &&
                        appMovedGeneration == expectedGeneration
                    ) {
                        appMovedGeneration = 0L
                    }
                    stopping = false
                    phase = SessionPhase.FAILED
                    cleanupUncertain = !cleanupComplete
                    lastFailureReason = finalReason
                    activeCleanup = null
                }
            }
            completeStart(
                expectedGeneration,
                success = false,
                reason = finalReason
            )
            cleanupTicket.succeeded.set(cleanupComplete)
            cleanupTicket.completion.countDown()
            if (failedKind == SessionKind.MAP) {
                operations.execute {
                    com.overdrive.app.navmap.ClusterMapProjector
                        .onDiLink5ProjectionFailed()
                }
            } else if (failedKind == SessionKind.CUSTOM) {
                notifyCustomLayoutsEnded(expectedGeneration, finalReason)
            }
        }
    }

    private fun stopInternal(
        expectedGeneration: Long,
        rehome: Boolean,
        foreground: Boolean,
        expectedKind: SessionKind?,
        hostedSurfaceLoss: HostedSurfaceLoss? = null,
    ): Boolean {
        val scopedCustomStop = expectedKind == SessionKind.CUSTOM && expectedGeneration > 0L
        while (true) {
            val inFlight = synchronized(stateLock) {
                if (scopedCustomStop && (generation != expectedGeneration || activeKind != expectedKind)) {
                    lastCustomCleanup?.takeIf { it.customGeneration == expectedGeneration } ?: return true
                } else activeCleanup
            } ?: break
            if (!awaitCleanupCompletion(
                    inFlight, "previous projection cleanup"
                )
            ) {
                return false
            }
            val success = inFlight.succeeded.get()
            if (!success || (scopedCustomStop && inFlight.customGeneration == expectedGeneration)) return success
        }
        val persistedHostedCleanup = DiLink5ProjectionOwnership.loadAll()
            .any { it.transport == "hosted" }
        val instrumentCleanup = Sealion7HdmiProjection.isTestedFirmware() && DiLink5InstrumentFullSession.hasPendingRestore()
        val orphanedCleanup = synchronized(stateLock) {
            if (hostedSurfaceLoss != null && hostedSurfaceLossScopeLocked(hostedSurfaceLoss) == null) {
                return true
            }
            activePackage == null &&
                    (poweredGeneration > 0L ||
                            enableIndeterminateGeneration > 0L ||
                            teardownIndeterminateGeneration > 0L ||
                            appMovedGeneration > 0L ||
                            cleanupUncertain ||
                            uncertainHostedResources.isNotEmpty() ||
                            instrumentCleanup || persistedHostedCleanup ||
                            session != null)
        }
        if (orphanedCleanup && !scopedCustomStop) {
            return recoverOrphanedStateSynchronously()
        }
        val pkg: String
        val stoppingGeneration: Long
        val stoppingKind: SessionKind?
        val compositorPowered: Boolean
        val compositorEnableIndeterminate: Boolean
        val compositorTeardownIndeterminate: Boolean
        val appMayBeMoved: Boolean
        val startTicket: StartTicket?
        val cleanupGeneration: Long
        val cleanupTicket: CleanupTicket
        synchronized(taskMutationLock.lock) {
            synchronized(stateLock) {
                if (activeCleanup != null) return false
            val current = activePackage ?: return true
            if (expectedGeneration > 0L && generation != expectedGeneration) return true
            if (expectedKind != null && activeKind != expectedKind) return true
            if (hostedSurfaceLoss != null && !claimHostedSurfaceLoss(
                    current = { hostedSurfaceLossScopeLocked(hostedSurfaceLoss) != null },
                    claim = {
                        hostedSurfaceLoss.cleanupClaimed.set(true)
                        lastFailureReason = "hosted projection surface was lost"
                    },
                )
            ) return true
            pkg = current
            stoppingGeneration = generation
            stoppingKind = activeKind
            cleanupTicket = CleanupTicket(customGeneration = if (stoppingKind == SessionKind.CUSTOM) stoppingGeneration else 0L)
            if (stoppingKind == SessionKind.CUSTOM) lastCustomCleanup = cleanupTicket
            compositorPowered = poweredGeneration == stoppingGeneration
            compositorEnableIndeterminate =
                enableIndeterminateGeneration == stoppingGeneration
            compositorTeardownIndeterminate =
                teardownIndeterminateGeneration == stoppingGeneration
            appMayBeMoved = appMovedGeneration == stoppingGeneration
            startTicket = pendingStart?.takeIf { it.generation == stoppingGeneration }
            activePackage = null
            activeKind = null
            generation = nextGenerationLocked()
            cleanupGeneration = generation
            stopping = true
            phase = SessionPhase.STOPPING
            pendingStart = null
            cleanupUncertain = false
            activeCleanup = cleanupTicket
            }
        }
        startTicket?.completion?.countDown()
        val cleanupAbandoned = AtomicBoolean(false)
        var cleanupFuture: java.util.concurrent.Future<CleanupResult>? = null
        var cleaned = false
        var deferredRehome = false
        var deferredDisplayId = -1
        try {
            cleanupFuture = operations.submit<CleanupResult> {
                val live = session?.takeIf {
                    it.generation == stoppingGeneration
                }
                val targetDisplayId = live?.targetDisplayId ?: -1
                if (!cleanupStillOwned(
                        cleanupGeneration, cleanupAbandoned
                    )
                ) {
                    return@submit CleanupResult(
                        rehomed = false,
                        taskCleanupSatisfied = false,
                        poweredDown = false,
                        appStillMoved = appMayBeMoved,
                        targetDisplayId = targetDisplayId
                    )
                }
                val instrumentRestored = releaseInstrumentMode(live)
                if (stoppingKind == SessionKind.MAP || stoppingKind == SessionKind.CUSTOM) {
                    try {
                        Thread.sleep(
                            if (stoppingKind == SessionKind.CUSTOM) CUSTOM_FINISH_GRACE_MS
                            else MAP_FINISH_GRACE_MS
                        )
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@submit CleanupResult(
                            rehomed = false,
                            taskCleanupSatisfied = false,
                            poweredDown = false,
                            appStillMoved = appMayBeMoved,
                            targetDisplayId = targetDisplayId
                        )
                    }
                }
                // Density restoration is deliberately deferred until the task is confirmed
                // reclaimed or absent. ACC-off keeps the lease for the later recovery owner.
                if (!rehome) {
                    ClusterAppDensity.deferRestoration("di5:$stoppingGeneration")
                }
                if (!cleanupStillOwned(
                        cleanupGeneration, cleanupAbandoned
                    )
                ) {
                    return@submit CleanupResult(
                        false, false, false, appMayBeMoved, targetDisplayId
                    )
                }
                try {
                    ClusterViewMirrorService.forceDetachIfActive("di5-cast-stop")
                } catch (_: Throwable) {
                }
                if (!cleanupStillOwned(
                        cleanupGeneration, cleanupAbandoned
                    )
                ) {
                    return@submit CleanupResult(
                        false, false, false, appMayBeMoved, targetDisplayId
                    )
                }
                if (rehome &&
                    appMayBeMoved &&
                    stoppingKind == SessionKind.APP
                ) {
                    try {
                        ClusterFreeformWindow.restoreFullscreen(
                            pkg, live?.targetDisplayId ?: -1
                        )
                    } catch (_: Throwable) {
                    }
                }
                var rehomed = !appMayBeMoved ||
                        stoppingKind == SessionKind.MAP ||
                        stoppingKind == SessionKind.CUSTOM ||
                        (rehome && rehomeTask(pkg, foreground))
                if (!rehomed && rehome && stoppingKind == SessionKind.APP) {
                    // The deferred (ACC-off) recovery path already accepts a
                    // force-stop when the task cannot be moved; apply the same
                    // last resort inline so a failed move never strands the
                    // state machine in "cleanup incomplete; restart required".
                    rehomed = forceStopAndConfirmAbsent(pkg)
                }
                val baseTaskCleanupSatisfied = !rehome || rehomed
                val densityOwner = "di5:$stoppingGeneration"
                val densityCleanupSatisfied = if (rehome && rehomed) {
                    ClusterAppDensity.restoreForSessionFamily(densityOwner)
                } else {
                    !rehome
                }
                // Never retire a VirtualDisplay while its density restoration journal still
                // depends on that exact display. Retain both owners for the recovery pass.
                val hostedCleanupSatisfied = baseTaskCleanupSatisfied &&
                    completeHostedGenerationCleanup(
                        densityCleanupSatisfied,
                        ClusterAppDensity.hasLeaseFamily(densityOwner),
                    ) {
                        releaseHostedResources(stoppingGeneration, live?.hostedResourceGeneration ?: 0L)
                    }
                val taskCleanupSatisfied = baseTaskCleanupSatisfied &&
                    densityCleanupSatisfied && hostedCleanupSatisfied && instrumentRestored
                val appStillMoved = appMayBeMoved && !rehomed
                if (!cleanupStillOwned(
                        cleanupGeneration, cleanupAbandoned
                    )
                ) {
                    return@submit CleanupResult(
                        rehomed,
                        false,
                        false,
                        appStillMoved,
                        targetDisplayId
                    )
                }
                val poweredDown = !compositorPowered ||
                        powerDownCompositor(
                            ownershipGeneration = cleanupGeneration,
                            retainIndeterminateOwnership =
                                compositorEnableIndeterminate,
                            teardownMayStillComplete =
                                compositorTeardownIndeterminate
                        ) {
                            cleanupStillOwned(
                                cleanupGeneration, cleanupAbandoned
                            )
                        }
                CleanupResult(
                    rehomed,
                    taskCleanupSatisfied,
                    poweredDown,
                    appStillMoved,
                    targetDisplayId
                )
            }
            val result = cleanupFuture.get(
                OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS
            )

            if (!cleanupStillOwned(
                    cleanupGeneration, cleanupAbandoned
                )
            ) {
                throw java.util.concurrent.CancellationException(
                    "projection cleanup was superseded"
                )
            }
            deferredDisplayId = result.targetDisplayId
            if (session?.generation == stoppingGeneration) {
                session = null
            }
            // ACC-off deliberately postpones AMS work until AFTER the
            // mirror/source teardown. Preserve the task cache for that
            // deferred fullscreen restore; all other paths can drop it now.
            if (!result.appStillMoved || stoppingKind != SessionKind.APP) {
                ClusterFreeformWindow.invalidateTaskCache()
            }
            val retainEnable =
                compositorEnableIndeterminate && !result.poweredDown
            val markerWritten = writeMarker(
                pending = false,
                powered = compositorPowered && !result.poweredDown,
                appMoved = result.appStillMoved,
                enableIndeterminate = retainEnable,
                enableBootId =
                    if (retainEnable) readCurrentBootId() else "",
                pkg = if (result.appStillMoved) pkg else ""
            )
            cleaned = result.complete && markerWritten
            deferredRehome = result.poweredDown && markerWritten &&
                    result.appStillMoved &&
                    stoppingKind == SessionKind.APP &&
                    !rehome
            synchronized(stateLock) {
                if (generation == cleanupGeneration &&
                    phase == SessionPhase.STOPPING &&
                    result.poweredDown &&
                    poweredGeneration == stoppingGeneration
                ) {
                    poweredGeneration = 0L
                }
                if (generation == cleanupGeneration &&
                    phase == SessionPhase.STOPPING &&
                    result.poweredDown &&
                    enableIndeterminateGeneration == stoppingGeneration
                ) {
                    enableIndeterminateGeneration = 0L
                }
                if (generation == cleanupGeneration &&
                    phase == SessionPhase.STOPPING &&
                    result.rehomed &&
                    appMovedGeneration == stoppingGeneration
                ) {
                    appMovedGeneration = 0L
                }
            }
        } catch (t: Throwable) {
            cleanupAbandoned.set(true)
            cleanupFuture?.cancel(true)
            cleanupUncertain = true
            logger.warn("DI5 cluster cast stop incomplete: ${t.message}")
        } finally {
            synchronized(stateLock) {
                if (generation == cleanupGeneration &&
                    phase == SessionPhase.STOPPING
                ) {
                    stopping = false
                    phase = when {
                        deferredRehome -> SessionPhase.RECOVERING
                        cleaned -> SessionPhase.IDLE
                        else -> SessionPhase.FAILED
                    }
                    if (cleaned && !deferredRehome) {
                        cleanupUncertain = false
                        lastFailureReason = ""
                    } else if (deferredRehome) {
                        cleanupUncertain = false
                        lastFailureReason =
                            "moving projected task safely back to the head unit"
                    } else {
                        cleanupUncertain = true
                        lastFailureReason = "DI5 projection cleanup incomplete"
                    }
                }
                if (!deferredRehome &&
                    activeCleanup === cleanupTicket
                ) {
                    activeCleanup = null
                }
            }
            if (!deferredRehome) {
                cleanupTicket.succeeded.set(cleaned)
                cleanupTicket.completion.countDown()
            }
        }
        if (deferredRehome) {
            scheduleDeferredTaskRecovery(
                stoppingGeneration, pkg, deferredDisplayId, cleanupTicket
            )
        }
        return cleaned
    }

    private fun clearHostedOwnershipIfPresent(resourceGeneration: Long): Boolean {
        val recorded = DiLink5ProjectionOwnership.loadAll()
            .any { it.generation == resourceGeneration }
        val cleared = !recorded || DiLink5ProjectionOwnership.clear(
            resourceGeneration,
            true,
            readCurrentBootId(),
        )
        if (cleared) synchronized(stateLock) { pendingHostedReplacements.remove(resourceGeneration) }
        return cleared
    }

    private fun recoverUnpublishedHostedResources() {
        val resources = synchronized(stateLock) {
            uncertainHostedResources.values.flatMap { it }.distinct()
        }
        resources.forEach { resourceGeneration ->
            val ownership = DiLink5ProjectionOwnership.loadAll()
                .firstOrNull { it.generation == resourceGeneration }
            if (ownership != null && !canReleaseUnpublishedHostedResource(
                    ownership.taskMayBeMoved,
                    ownership.densityOwned,
                )
            ) {
                return@forEach
            }
            if (releaseHostedResource(resourceGeneration)) {
                synchronized(stateLock) {
                    uncertainHostedResources.entries.removeAll { entry ->
                        entry.value.remove(resourceGeneration)
                        entry.value.isEmpty()
                    }
                }
            }
        }
    }

    private fun recoverPersistedHostedOwnership() {
        val currentBoot = readCurrentBootId()
        CameraDaemon.getAppContext()?.let(DiLink5HostedProjectionBridge::attachDaemonContext)
        DiLink5ProjectionOwnership.loadAll().forEach { ownership ->
            if (ownership.bootId.isNotEmpty() && currentBoot.isNotEmpty() &&
                ownership.bootId != currentBoot) {
                if (ownership.transport == "hosted" &&
                    !ClusterAppDensity.restoreForOwnedHostedDisplay(ownership)
                ) return@forEach
                DiLink5ProjectionOwnership.clear(ownership.generation, false, currentBoot)
                return@forEach
            }
            if (ownership.transport != "hosted") return@forEach

            val taskRecovered = !ownership.taskMayBeMoved ||
                (AppLauncher.isValidPackageName(ownership.packageName) &&
                    rehomeTask(ownership.packageName, foreground = false))
            if (!taskRecovered) return@forEach

            val densityRecovered = ClusterAppDensity.restoreForOwnedHostedDisplay(ownership)
            if (!densityRecovered) return@forEach

            if (releaseHostedResource(ownership.generation)) {
                synchronized(stateLock) {
                    uncertainHostedResources.entries.removeAll { entry ->
                        entry.value.remove(ownership.generation)
                        entry.value.isEmpty()
                    }
                }
            }
        }
        ClusterFreeformWindow.recoverFreeformSupportOwnership()
    }

    private fun releaseHostedResource(resourceGeneration: Long): Boolean {
        val owned = DiLink5ProjectionOwnership.load(resourceGeneration)
        if (owned != null && !ClusterAppDensity.restoreForOwnedHostedDisplay(owned)) return false
        if (!DiLink5HostedProjectionBridge.release(resourceGeneration, owned?.sealion7Hdmi == true)) return false
        return clearHostedOwnershipIfPresent(resourceGeneration)
    }

    /** Failed allocations also own resources even though there is no published Session. */
    private fun releaseHostedResources(
        sessionGeneration: Long,
        currentResourceGeneration: Long = 0L,
    ): Boolean {
        val registered = synchronized(stateLock) {
            uncertainHostedResources[sessionGeneration]?.toList().orEmpty()
        }
        val resources = (registered + listOf(sessionGeneration, currentResourceGeneration))
            .filter { it > 0L }.distinct().filter { resourceGeneration ->
                resourceGeneration in registered ||
                    resourceGeneration == currentResourceGeneration ||
                    DiLink5ProjectionOwnership.load(resourceGeneration)?.transport == "hosted"
            }
        return finishHostedResourceCleanup(
            resources,
            release = ::releaseHostedResource,
            clearRegistration = { resourceGeneration ->
                synchronized(stateLock) {
                    pendingHostedReplacements.remove(resourceGeneration)
                    uncertainHostedResources[sessionGeneration]?.let { pending ->
                        pending.remove(resourceGeneration)
                        if (pending.isEmpty()) uncertainHostedResources.remove(sessionGeneration)
                    }
                }
            },
        )
    }

    private fun cleanupStillOwned(
        cleanupGeneration: Long,
        abandoned: AtomicBoolean
    ): Boolean {
        if (abandoned.get() || Thread.currentThread().isInterrupted) {
            return false
        }
        return synchronized(stateLock) {
            generation == cleanupGeneration &&
                    activePackage == null &&
                    stopping &&
                    phase == SessionPhase.STOPPING
        }
    }

    private fun scheduleDeferredTaskRecovery(
        stoppedGeneration: Long,
        pkg: String,
        targetDisplayId: Int,
        cleanupTicket: CleanupTicket
    ) {
        try {
            deferredCleanup.schedule({
                var completed = false
                try {
                    val ownsRecovery = synchronized(stateLock) {
                        activeCleanup === cleanupTicket &&
                                activePackage == null &&
                                appMovedGeneration == stoppedGeneration &&
                                phase == SessionPhase.RECOVERING
                    }
                    if (!ownsRecovery) return@schedule

                    try {
                        ClusterFreeformWindow.restoreFullscreen(
                            pkg, targetDisplayId
                        )
                    } catch (_: Throwable) {
                    }
                    var recovered = rehomeTask(pkg, foreground = false)
                    if (!recovered &&
                        AppLauncher.forceStopPackage(pkg)
                    ) {
                        // Confirmed-absent only (TASK_LOOKUP_FAILED ≠ gone).
                        recovered = AppLauncher.taskConfirmedAbsent(pkg)
                    }
                    ClusterFreeformWindow.invalidateTaskCache()

                    val densityRecovered = recovered &&
                        ClusterAppDensity.restoreForSessionFamily("di5:$stoppedGeneration")
                    val hostedRecovered = completeHostedGenerationCleanup(
                        densityRecovered,
                        ClusterAppDensity.hasLeaseFamily("di5:$stoppedGeneration"),
                    ) { releaseHostedResources(stoppedGeneration) }
                    val instrumentRestored = recoverInstrumentModeIfIdle()
                    val markerCleared = instrumentRestored && recovered && densityRecovered && hostedRecovered && writeMarker(
                        pending = false,
                        powered = false,
                        appMoved = false,
                        enableIndeterminate = false,
                        enableBootId = "",
                        pkg = ""
                    )
                    synchronized(stateLock) {
                        if (recovered &&
                            appMovedGeneration == stoppedGeneration
                        ) {
                            appMovedGeneration = 0L
                        }
                        completed = recovered && densityRecovered && hostedRecovered &&
                                markerCleared &&
                                poweredGeneration == 0L
                        if (activePackage == null &&
                            phase == SessionPhase.RECOVERING
                        ) {
                            phase = if (completed) {
                                SessionPhase.IDLE
                            } else {
                                SessionPhase.FAILED
                            }
                            cleanupUncertain = !completed
                            lastFailureReason = when {
                                completed -> ""
                                !recovered ->
                                    "projected task could not be recovered from OEM display"
                                !densityRecovered || !hostedRecovered ->
                                    "hosted projection restoration is pending confirmed ACC-on and display cleanup"
                                !markerCleared ->
                                    "projected task recovered but its marker could not be cleared"
                                else ->
                                    "DI5 compositor cleanup remains incomplete"
                            }
                        }
                    }
                    if (completed) {
                        logger.info(
                            "DI5 cluster cast: deferred task recovery completed " +
                                    "for $pkg"
                        )
                    } else {
                        logger.warn(
                            "DI5 cluster cast: deferred task recovery incomplete " +
                                    "for $pkg"
                        )
                    }
                } finally {
                    synchronized(stateLock) {
                        if (activeCleanup === cleanupTicket) {
                            activeCleanup = null
                        }
                    }
                    cleanupTicket.succeeded.set(completed)
                    cleanupTicket.completion.countDown()
                }
            }, DEFERRED_REHOME_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            synchronized(stateLock) {
                if (activeCleanup === cleanupTicket) {
                    activeCleanup = null
                }
                phase = SessionPhase.FAILED
                cleanupUncertain = true
                lastFailureReason =
                    "could not schedule projected task recovery"
            }
            cleanupTicket.succeeded.set(false)
            cleanupTicket.completion.countDown()
            logger.warn(
                "DI5 cluster cast: deferred recovery scheduling failed: " +
                        t.message
            )
        }
    }

    private fun enableAndAwaitProjectionDisplay(
        context: Context,
        expectedGeneration: Long,
        pkg: String,
        kind: SessionKind,
        config: DiLink5ProjectionConfig,
        factoryMiniMap: Boolean,
    ): ProjectionTarget {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        if (ClusterTransport.isAaosHdmi()) {
            if (Sealion7HdmiProjection.isEnabled(context)) {
                val panel = Sealion7HdmiProjection.findDisplay(context)
                    ?: throw IllegalStateException("The Sealion 7 cluster display is unavailable")
                check(DiLink5InstrumentFullSession.isSupportedSelection(expectedGeneration)) {
                    "Select Off or Full in the vehicle instrument navigation settings"
                }
                val size = displaySize(panel)
                val target = ProjectionTarget(panel.displayId, panel.name, size.x, size.y, sealion7Hdmi = true)
                return configureHostedProjectionTarget(context, target, expectedGeneration, config)
                    ?: throw IllegalStateException("Could not create the Sealion 7 cluster window")
            }
            check(kind != SessionKind.CUSTOM) { "Custom Layouts cannot use the HDMI map area" }
            val panel = manager.displays.singleOrNull { AaosMapAreaCheck.isTarget(context, it) }
                ?: throw IllegalStateException("The supported HDMI cluster display is unavailable")
            check(AaosNavigationPresentation.isOpen(context, panel.displayId)) {
                "Open the factory navigation map on the instrument cluster"
            }
            val size = displaySize(panel)
            val direct = ProjectionTarget(panel.displayId, panel.name, size.x, size.y, aaosMapArea = true)
            return configureHostedProjectionTarget(context, direct, expectedGeneration, config)
                ?: throw IllegalStateException("Could not create the hosted window on the confirmed cluster map area")
        }
        if (factoryMiniMap) {
            // The native mini-map is already routed by the driver. Switching the
            // container here can replace the widget that is consuming these pixels.
            val displays = manager.displays.filter {
                it.displayId > 0 && it.name.equals(DI5_SHARED_DISPLAY_STANDARD, ignoreCase = true) &&
                    isUsableDiLink5ProjectionDisplay(it.isValid, it.state)
            }
            val target = displays.firstNotNullOfOrNull {
                val size = displaySize(it)
                if (size.x > 1 && size.y > 1) ProjectionTarget(it.displayId, it.name, size.x, size.y)
                else null
            } ?: resolveFactoryMiniMapDisplay(readAuthoritativeDisplayLines().orEmpty())?.let {
                ProjectionTarget(it.displayId, it.name, it.width, it.height)
            } ?: throw IllegalStateException("Open the factory mini-map on the cluster. Its shared display is unavailable.")
            logger.info("DI5 factory mini-map: direct display ${target.displayId} (${target.name}), ${target.width}x${target.height}")
            return target
        }
        val preferFull = config.target == DiLink5ProjectionConfig.Target.FULL
        val propertyProduct = readDiLink5SystemProperty("ro.product.name")
        val propertyDevice = readDiLink5SystemProperty("ro.product.device")
        val propertyFingerprint =
            readDiLink5SystemProperty("ro.build.fingerprint")
        val displayNames = manager.displays.map { it.name }
        val authoritativeLines = readAuthoritativeDisplayLines()
        val authoritativeNames = authoritativeLines
            ?.let(::parseDiLink5DisplayNames)
            .orEmpty()
        val combinedNames = (displayNames + authoritativeNames).distinct()
        val fissionSingleOs =
            readDiLink5SystemProperty(
                "ro.build.system.fission_single_os"
            ) ?: readDiLink5SystemPropertyViaShell(
                "ro.build.system.fission_single_os"
            )
        val buildCharacteristics =
            readDiLink5SystemProperty("ro.build.characteristics")
                ?: readDiLink5SystemPropertyViaShell(
                    "ro.build.characteristics"
                )
        val packageManagerAutomotiveFeature = try {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE
            )
        } catch (_: Throwable) {
            false
        }
        val automotiveFeature =
            packageManagerAutomotiveFeature || (
                    buildCharacteristics
                        ?.split(',')
                        ?.any { it.trim().equals("automotive", true) }
                        == true
                    )
        val identityRequiresShared = requiresSharedDiLink5ProjectionDisplay(
            Build.PRODUCT,
            Build.DEVICE,
            Build.FINGERPRINT
        ) || requiresSharedDiLink5ProjectionDisplay(
            propertyProduct,
            propertyDevice,
            propertyFingerprint
        )
        val topology = classifyDiLink5ProjectionTopology(
            product = propertyProduct ?: Build.PRODUCT,
            device = propertyDevice ?: Build.DEVICE,
            fingerprint = propertyFingerprint ?: Build.FINGERPRINT,
            automotiveFeature = automotiveFeature,
            fissionSingleOs = fissionSingleOs,
            displayNames = combinedNames
        )
        val requireShared =
            identityRequiresShared ||
                    topology == DiLink5ProjectionTopology.SHARED_FISSION
        logger.info(
            "DI5 projection identity: Build.PRODUCT=" +
                    projectionIdentityValue(Build.PRODUCT) +
                    ", Build.DEVICE=" +
                    projectionIdentityValue(Build.DEVICE) +
                    ", Build.FINGERPRINT=" +
                    projectionIdentityValue(Build.FINGERPRINT) +
                    ", ro.product.name=" +
                    projectionIdentityValue(propertyProduct) +
                    ", ro.product.device=" +
                    projectionIdentityValue(propertyDevice) +
                    ", ro.build.fingerprint=" +
                    projectionIdentityValue(propertyFingerprint) +
                    ", fission_single_os=" +
                    projectionIdentityValue(fissionSingleOs) +
                    ", characteristics=" +
                    projectionIdentityValue(buildCharacteristics) +
                    ", topology=$topology, displays=" +
                    combinedNames.joinToString()
        )
        when (topology) {
            DiLink5ProjectionTopology.AAOS_HDMI_CLOSED -> {
                val inventory = manager.displays.joinToString {
                    "${it.displayId}:${it.name}"
                }
                logger.warn(
                    "DI5 cluster cast: AAOS HDMI topology has no app-projection " +
                            "surface; visible=$inventory, authoritative=" +
                            authoritativeNames.joinToString()
                )
                throw IllegalStateException(
                    "BYD AAOS HDMI cluster exposes OEM navigation only; " +
                            "app projection is unavailable on this firmware"
                )
            }
            DiLink5ProjectionTopology.SINGLE_OS_CLOSED -> {
                logger.warn(
                    "DI5 cluster cast: fission_single_os=1 exposes no " +
                            "Android app-projection surface"
                )
                throw IllegalStateException(
                    "BYD single-OS cluster has no Android app-projection " +
                            "surface on this firmware"
                )
            }
            else -> Unit
        }
        val preferredName = if (requireShared) readSharedDisplayPreference() else null
        val reclaimed = if (requireShared) recentlyReclaimedDisplayNames() else emptySet()
        if (requireShared && (preferredName != null || reclaimed.isNotEmpty())) {
            logger.info(
                "DI5 cluster cast: shared display steering preferred=" +
                    (preferredName ?: "<none>") + " reclaimed=" +
                    reclaimed.joinToString().ifEmpty { "<none>" }
            )
        }
        val preEnableCandidate = if (requireShared) {
            var selected: ProjectionTarget? = null
            for (excluded in listOf(reclaimed, emptySet())) {
                selected = resolveDaemonVisibleProjectionDisplay(
                    manager, preferFull, true, preferredName, excluded
                ) ?: resolveAuthoritativeProjectionDisplay(
                    manager, preferFull, true, preferredName, excluded
                )
                if (selected != null || excluded.isEmpty()) break
            }
            selected
        } else {
            resolveDaemonVisibleProjectionDisplay(
                manager, preferFull, false
            ) ?: resolveAuthoritativeProjectionDisplay(manager, preferFull, false)
        }
        if (!canDispatchDiLink5ContainerEnable(preEnableCandidate?.displayId ?: -1)) {
            if (topology == DiLink5ProjectionTopology.TRINKET_DEBUG_ONLY &&
                Build.MODEL.orEmpty().startsWith("D50F_LC", ignoreCase = true)) {
                throw IllegalStateException(
                    "OverDrive found the cluster background. A visible app or map display is needed " +
                        "to cast. For turn arrows, send a RoadSense destination to car navigation."
                )
            }
            throw IllegalStateException(
                "DI5 projection target was not resolved before container enable"
            )
        }
        val preEnableTarget = requireNotNull(preEnableCandidate)
        val appVisible = DiLink5HostedProjectionBridge.isAppDisplayVisible(
            context,
            preEnableTarget.displayId,
        )
        if (!appVisible) {
            logger.info(
                "DI5 cluster cast: using direct transport for shell-only target; " +
                    "deferring compositor enable until verified placement"
            )
            return configureProjectionTarget(
                context, preEnableTarget, expectedGeneration, config, appDisplayVisible = false,
            ).copy(deferCompositorEnable = true)
        }
        val commandResult = synchronized(stateLock) {
            if (!isCurrent(expectedGeneration, pkg)) {
                throw InterruptedException("projection start superseded")
            }
            // Persist the in-flight transaction before entering Binder. If
            // this process dies or the child times out, recovery can never
            // erase the possibility of a late opcode 16 merely because a
            // local timer elapsed.
            markCompositorEnableInFlight(expectedGeneration, pkg)
            val result =
                ClusterProjectionController.sendDiLink5ContainerInfoResult(16)
            if (shouldTrackDiLink5CompositorOwnership(result)) {
                // A timed-out transaction can still complete after our caller
                // returns. Persist ownership only for accepted or genuinely
                // indeterminate commands; a native -1 is a rejection, not
                // evidence that this process changed compositor state.
                markCompositorPowered(
                    expectedGeneration,
                    pkg,
                    enableIndeterminate =
                        result ==
                        ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
                )
            } else {
                clearCompositorEnableInFlight(expectedGeneration, pkg)
            }
            result
        }
        logger.info(
            "DI5 cluster cast: sendInfo(1000,16)=$commandResult; " +
                    "topology=$topology; targetPolicy=" +
                    (if (requireShared) "OEM-shared-only" else "DI5-numbered")
        )
        Thread.sleep(CONTAINER_SETTLE_MS)
        if (!isCurrent(expectedGeneration, pkg)) {
            throw InterruptedException("projection start superseded")
        }

        if (requireShared) {
            logger.info(
                "DI5 cluster cast: using preflight-selected OEM shared projection " +
                    "display ${preEnableTarget.displayId} (${preEnableTarget.name}); " +
                    "sendInfo16=$commandResult"
            )
            return configureProjectionTarget(context, preEnableTarget, expectedGeneration, config)
        }

        if (commandResult ==
            ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE
        ) {
            val inventory = manager.displays.joinToString {
                "${it.displayId}:${it.name}"
            }
            logger.warn(
                "DI5 cluster cast: no projection display after container " +
                        "bootstrap; inventory=$inventory"
            )
            throw IllegalStateException(
                "DI5 OEM container service unavailable and no production " +
                "projection display exists"
            )
        }
        if (commandResult ==
            ClusterProjectionController.DiLink5CommandResult.TRANSPORT_FAILURE
        ) {
            throw IllegalStateException(
                "DI5 container command transport failed locally; refusing " +
                        "projection without a cleanup-capable control path"
            )
        }
        if (!requireShared &&
            commandResult !=
                ClusterProjectionController.DiLink5CommandResult.ACCEPTED
        ) {
            throw IllegalStateException(
                "DI5 container enable was $commandResult; refusing a " +
                        "pre-existing numbered projection display"
            )
        }

        return configureProjectionTarget(context, preEnableTarget, expectedGeneration, config)
    }

    private fun readProjectionConfig(): DiLink5ProjectionConfig = try {
        DiLink5ProjectionConfig.from(
            UnifiedConfigManager.loadConfig().optJSONObject("projection")
        )
    } catch (_: Throwable) {
        DiLink5ProjectionConfig.from(null)
    }

    private fun configureProjectionTarget(
        context: Context,
        direct: ProjectionTarget,
        expectedGeneration: Long,
        config: DiLink5ProjectionConfig,
        appDisplayVisible: Boolean = true,
    ): ProjectionTarget {
        if (config.initialTransport(appDisplayVisible) == DiLink5ProjectionConfig.Transport.DIRECT) {
            return direct
        }
        if (!ClusterFreeformWindow.prepareHostedFreeformState()) {
            throw IllegalStateException("hosted projection freeform restoration was not confirmed")
        }
        return configureHostedProjectionTarget(context, direct, expectedGeneration, config)
            ?: throw IllegalStateException("hosted projection resources unavailable")
    }

    private fun configureHostedProjectionTarget(
        context: Context,
        direct: ProjectionTarget,
        expectedGeneration: Long,
        config: DiLink5ProjectionConfig,
    ): ProjectionTarget? {
        val appDisplay = DiLink5HostedProjectionBridge.probeAppDisplay(
            context,
            direct.displayId,
            aaosMapArea = direct.aaosMapArea,
            sealion7Hdmi = direct.sealion7Hdmi,
        ) ?: if (direct.aaosMapArea) {
            throw IllegalStateException("Enable the overlay permission and confirm both parked map-area checks before projecting")
        } else return null
        val geometry = if (direct.sealion7Hdmi) {
            Sealion7HdmiProjection.geometry(appDisplay) ?: return null
        } else if (direct.aaosMapArea) {
            val area = appDisplay.confirmedMapBounds ?: return null
            HostedProjectionGeometry(
                appDisplay.width, appDisplay.height, appDisplay.densityDpi,
                area, area.width(), area.height(),
            )
        } else {
            val nativeDensity = ClusterAppDensity.nativeDensityForDisplay(direct.displayId) ?: return null
            HostedProjectionGeometry.calculate(appDisplay.width, appDisplay.height, nativeDensity, config) ?: return null
        }
        val pendingOwner = projectionOwnership(
            generation = expectedGeneration,
            target = direct,
            contentDisplayId = direct.displayId,
            overlayOwned = true,
            virtualDisplayOwned = true,
            taskMayBeMoved = false,
            uncertain = true
        )
        if (!DiLink5ProjectionOwnership.persist(pendingOwner)) {
            if (direct.sealion7Hdmi) {
                logger.warn("Sealion 7 hosted create generation=$expectedGeneration could not persist pending ownership")
            }
            return null
        }
        val allocation = startHostedAllocation(
            register = {
                synchronized(stateLock) {
                    if (!isCurrent(expectedGeneration, activePackage.orEmpty())) false else {
                        uncertainHostedResources.getOrPut(expectedGeneration) { LinkedHashSet() }
                            .add(expectedGeneration)
                        true
                    }
                }
            },
            start = {
                DiLink5HostedProjectionBridge.start(
                    context, expectedGeneration, direct.displayId, direct.name, geometry,
                    aaosMapArea = direct.aaosMapArea,
                    sealion7Hdmi = direct.sealion7Hdmi,
                )
            },
            current = { isCurrent(expectedGeneration, activePackage.orEmpty()) },
        )
        if (!allocation.registered) {
            DiLink5ProjectionOwnership.clear(expectedGeneration, true, readCurrentBootId())
            return null
        }
        if (!allocation.publicationAllowed) {
            val late = allocation.value
            if (late != null) {
                DiLink5ProjectionOwnership.persist(pendingOwner.copy(
                    contentDisplayId = late.contentDisplayId,
                    virtualDisplayName = late.virtualDisplayName,
                ))
            }
            val released = DiLink5HostedProjectionBridge.release(expectedGeneration, direct.sealion7Hdmi) &&
                clearHostedOwnershipIfPresent(expectedGeneration)
            if (released) {
                synchronized(stateLock) {
                    uncertainHostedResources[expectedGeneration]?.let { resources ->
                        resources.remove(expectedGeneration)
                        if (resources.isEmpty()) uncertainHostedResources.remove(expectedGeneration)
                    }
                }
            }
            return null
        }
        val hosted = allocation.value?.takeIf {
            DiLink5HostedProjectionBridge.isSurfaceAlive(expectedGeneration)
        } ?: run {
            if (direct.sealion7Hdmi) {
                logger.warn("Sealion 7 hosted create generation=$expectedGeneration could not obtain a live surface")
            }
            val released = DiLink5HostedProjectionBridge.release(expectedGeneration, direct.sealion7Hdmi) &&
                DiLink5ProjectionOwnership.clear(expectedGeneration, true, readCurrentBootId())
            if (released) {
                synchronized(stateLock) {
                    uncertainHostedResources[expectedGeneration]?.let { resources ->
                        resources.remove(expectedGeneration)
                        if (resources.isEmpty()) uncertainHostedResources.remove(expectedGeneration)
                    }
                }
            }
            return null
        }
        val allocatedOwner = pendingOwner.copy(
            contentDisplayId = hosted.contentDisplayId,
            virtualDisplayName = hosted.virtualDisplayName
        )
        if (!DiLink5ProjectionOwnership.persist(allocatedOwner)) {
            val released = DiLink5HostedProjectionBridge.release(expectedGeneration, direct.sealion7Hdmi) &&
                clearHostedOwnershipIfPresent(expectedGeneration)
            if (released) {
                synchronized(stateLock) {
                    uncertainHostedResources[expectedGeneration]?.let { resources ->
                        resources.remove(expectedGeneration)
                        if (resources.isEmpty()) uncertainHostedResources.remove(expectedGeneration)
                    }
                }
            }
            return null
        }
        logger.info(
            "DI5 cluster cast: hosted panel=${direct.displayId} " +
                    "content=${hosted.contentDisplayId}"
        )
        return direct.copy(
            width = appDisplay.width,
            height = appDisplay.height,
            contentDisplayId = hosted.contentDisplayId,
            transport = "hosted",
            hostedResourceGeneration = expectedGeneration,
            deferCompositorEnable = false,
            hostedGeometry = geometry,
        )
    }

    private fun projectionOwnership(
        generation: Long,
        target: ProjectionTarget,
        contentDisplayId: Int,
        overlayOwned: Boolean,
        virtualDisplayOwned: Boolean,
        taskMayBeMoved: Boolean,
        uncertain: Boolean,
        taskId: Int = -1
    ): DiLink5ProjectionOwnership {
        val display = (CameraDaemon.getAppContext()
            ?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(target.displayId)
        return DiLink5ProjectionOwnership(
            generation = generation,
            bootId = readCurrentBootId(),
            transport = if (overlayOwned) "hosted" else "direct",
            packageName = activePackage.orEmpty(),
            physicalDisplayId = target.displayId,
            physicalDisplayName = target.name,
            physicalUniqueId = try {
                display?.let {
                    Display::class.java.getDeclaredMethod("getUniqueId").invoke(it) as? String
                }.orEmpty()
            } catch (_: Throwable) { "" },
            contentDisplayId = contentDisplayId,
            virtualDisplayName = if (overlayOwned) "OverDriveCluster-$generation" else "",
            taskId = taskId,
            overlayOwned = overlayOwned,
            virtualDisplayOwned = virtualDisplayOwned,
            taskMayBeMoved = taskMayBeMoved,
            densityOwned = false,
            freeformOwned = false,
            compositorOwned = poweredGeneration == generation || enableIndeterminateGeneration == generation,
            uncertain = uncertain,
            sealion7Hdmi = target.sealion7Hdmi,
        )
    }

    private fun enableProjectionCompositorForCurrentSession(
        expectedGeneration: Long,
        pkg: String
    ): Boolean = synchronized(stateLock) {
        if (!isCurrent(expectedGeneration, pkg)) return@synchronized false
        markCompositorEnableInFlight(expectedGeneration, pkg)
        val result = ClusterProjectionController.sendDiLink5ContainerInfoResult(16)
        if (shouldTrackDiLink5CompositorOwnership(result)) {
            markCompositorPowered(
                expectedGeneration,
                pkg,
                result == ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
            )
        } else {
            clearCompositorEnableInFlight(expectedGeneration, pkg)
        }
        result == ClusterProjectionController.DiLink5CommandResult.ACCEPTED
    }

    /** Caller holds [stateLock]. */
    private fun markCompositorEnableInFlight(
        expectedGeneration: Long,
        pkg: String
    ) {
        val bootId = readCurrentBootId()
        if (!writeMarker(
                pending = true,
                powered = false,
                appMoved = appMovedGeneration == expectedGeneration,
                enableIndeterminate = true,
                enableBootId = bootId,
                pkg = pkg
            )
        ) {
            throw IllegalStateException(
                "in-flight enable marker could not be persisted"
            )
        }
        enableIndeterminateGeneration = expectedGeneration
    }

    /** Caller holds [stateLock]. */
    private fun clearCompositorEnableInFlight(
        expectedGeneration: Long,
        pkg: String
    ) {
        if (!writeMarker(
                pending = true,
                powered = false,
                appMoved = appMovedGeneration == expectedGeneration,
                enableIndeterminate = false,
                enableBootId = "",
                pkg = pkg
            )
        ) {
            throw IllegalStateException(
                "completed enable marker could not be persisted"
            )
        }
        if (enableIndeterminateGeneration == expectedGeneration) {
            enableIndeterminateGeneration = 0L
        }
    }

    /** Caller holds [stateLock]. */
    private fun markCompositorPowered(
        expectedGeneration: Long,
        pkg: String,
        enableIndeterminate: Boolean
    ) {
        poweredGeneration = expectedGeneration
        enableIndeterminateGeneration =
            if (enableIndeterminate) expectedGeneration else 0L
        if (!writeMarker(
                pending = true,
                powered = true,
                appMoved = appMovedGeneration == expectedGeneration,
                enableIndeterminate = enableIndeterminate,
                enableBootId =
                    if (enableIndeterminate) readCurrentBootId() else "",
                pkg = pkg
            )
        ) {
            throw IllegalStateException(
                "powered-state marker could not be persisted"
            )
        }
    }

    // ── OEM takeover memory + learned shared-display preference ─────────────

    private fun markDisplayReclaimed(displayName: String) {
        if (displayName.isBlank()) return
        synchronized(stateLock) {
            reclaimedDisplays[displayName.lowercase(Locale.US)] =
                android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun recentlyReclaimedDisplayNames(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            reclaimedDisplays.entries.removeAll { (_, at) ->
                now - at > RECLAIMED_DISPLAY_MEMORY_MS
            }
            return reclaimedDisplays.keys.toSet()
        }
    }

    private fun readPreferFullCluster(): Boolean =
        readProjectionConfig().target == DiLink5ProjectionConfig.Target.FULL

    private fun readSharedDisplayPreference(): String? =
        try {
            UnifiedConfigManager.loadConfig()
                .optJSONObject("projection")
                ?.optString(SHARED_DISPLAY_PREFERENCE_KEY, "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && isSharedDiLink5ProjectionDisplayName(it) }
        } catch (_: Throwable) {
            null
        }

    private fun persistSharedDisplayPreference(displayName: String) {
        try {
            val persisted = UnifiedConfigManager.updateValues(
                "projection",
                mapOf<String, Any>(SHARED_DISPLAY_PREFERENCE_KEY to displayName)
            )
            logger.info(
                "DI5 cluster cast: shared display preference " +
                        (if (displayName.isEmpty()) "cleared" else "= $displayName") +
                        " (persisted=$persisted)"
            )
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster cast: shared display preference not persisted: ${t.message}"
            )
        }
    }

    /**
     * After a session on [chosenName] proved stable, remember it when it is not
     * the default-ranked surface (so the next cast starts there directly), and
     * forget a stale preference when the default surface worked again.
     */
    private fun rememberStableSharedDisplay(
        manager: DisplayManager,
        preferFull: Boolean,
        chosenName: String,
    ) {
        if (!isSharedDiLink5ProjectionDisplayName(chosenName)) return
        val defaultName = try {
            selectDiLink5ProjectionDisplayName(
                manager.displays
                    .filter { isUsableDiLink5ProjectionDisplay(it.isValid, it.state) }
                    .map { it.name },
                preferFull,
                requireShared = true
            )
        } catch (_: Throwable) {
            null
        }
        val current = readSharedDisplayPreference()
        val desired =
            if (defaultName != null && defaultName.equals(chosenName, ignoreCase = true)) {
                ""
            } else {
                chosenName
            }
        if ((current ?: "") != desired) {
            persistSharedDisplayPreference(desired)
        }
    }

    /**
     * A shared display other than [excludeName] (and any recently reclaimed one)
     * that a projection can move to. Null when this firmware exposes no usable
     * alternative — the caller then reports the OEM takeover as the failure.
     */
    private fun resolveAlternativeSharedDisplay(
        manager: DisplayManager,
        preferFull: Boolean,
        excludeName: String
    ): ProjectionTarget? {
        val excluded = recentlyReclaimedDisplayNames() + excludeName.lowercase(Locale.US)
        return resolveDaemonVisibleProjectionDisplay(
            manager, preferFull, true, null, excluded
        ) ?: resolveAuthoritativeProjectionDisplay(
            manager, preferFull, true, null, excluded
        )
    }

    /** DisplayManager visibility in the daemon/shell process; app-overlay visibility is
     * independently proved by DiLink5HostedProjectionService before hosted allocation. */
    private fun resolveDaemonVisibleProjectionDisplay(
        manager: DisplayManager,
        preferFull: Boolean,
        requireShared: Boolean,
        preferredName: String? = null,
        excludedNames: Set<String> = emptySet()
    ): ProjectionTarget? {
        val displays = manager.displays
        val usableDisplays = displays.filter {
            isUsableDiLink5ProjectionDisplay(it.isValid, it.state)
        }
        val selected = selectDiLink5ProjectionDisplayName(
            usableDisplays.map { it.name },
            preferFull,
            requireShared,
            preferredName,
            excludedNames
        ) ?: return null
        val target = usableDisplays.firstOrNull {
            it.name.equals(selected, ignoreCase = true)
        } ?: return null
        val targetSize = displaySize(target)
        val panelSize = if (targetSize.x > 1 && targetSize.y > 1) {
            targetSize
        } else {
            usableDisplays.firstOrNull {
                it.name.equals(DI5_DEBUG_DISPLAY, ignoreCase = true)
            }?.let(::displaySize)
        } ?: Point()
        if (panelSize.x <= 1 || panelSize.y <= 1) return null
        return ProjectionTarget(
            target.displayId,
            target.name,
            panelSize.x,
            panelSize.y
        )
    }

    private fun resolveAuthoritativeProjectionDisplay(
        manager: DisplayManager,
        preferFull: Boolean,
        requireShared: Boolean,
        preferredName: String? = null,
        excludedNames: Set<String> = emptySet()
    ): ProjectionTarget? {
        val lines = readAuthoritativeDisplayLines() ?: return null
        val refs = parseDiLink5ProjectionDisplays(lines)
        val selectedName = selectDiLink5ProjectionDisplayName(
            refs.map { it.name },
            preferFull,
            requireShared,
            preferredName,
            excludedNames
        ) ?: return null
        val selected = refs.firstOrNull {
            it.name.equals(selectedName, ignoreCase = true)
        } ?: return null
        val displayId = selected.displayId
        if (displayId <= Display.DEFAULT_DISPLAY) return null

        val panelSize = resolveDiLink5ProjectionPanelSize(refs, selected)
        var width = panelSize?.first ?: 0
        var height = panelSize?.second ?: 0
        if (width <= 1 || height <= 1) {
            manager.getDisplay(displayId)?.let { visible ->
                if (visible.name.equals(selected.name, ignoreCase = true) &&
                    isUsableDiLink5ProjectionDisplay(
                        visible.isValid, visible.state
                    )
                ) {
                    val size = displaySize(visible)
                    width = size.x
                    height = size.y
                }
            }
        }
        if (width <= 1 || height <= 1) {
            logger.warn(
                "DI5 cluster cast: OEM target ${selected.name} has no " +
                        "usable panel geometry (${selected.width}x${selected.height})"
            )
            return null
        }
        // Do not require a Display object here. On trinket the uid-1000 shared
        // display can be absent from this process's DisplayManager cache even
        // while dumpsys proves its live id/name/geometry. `am --display` needs
        // only that authoritative id.
        return ProjectionTarget(displayId, selected.name, width, height)
    }

    private fun readAuthoritativeDisplayLines(): List<String>? {
        val resolved = AtomicReference<List<String>?>(null)
        val process = AtomicReference<Process?>()
        val scan = Thread({
            try {
                var local: Process? = null
                try {
                    local = ProcessBuilder(
                        "/system/bin/dumpsys", "display"
                    ).redirectErrorStream(true).start()
                    process.set(local)
                    val lines = local.inputStream.bufferedReader().use {
                        it.readLines()
                    }
                    val completed =
                        local.waitFor(500L, TimeUnit.MILLISECONDS)
                    if (completed && local.exitValue() == 0) {
                        resolved.set(lines)
                    } else {
                        logger.warn(
                            "DI5 cluster cast: authoritative display scan " +
                                    "did not exit cleanly"
                        )
                    }
                } finally {
                    try {
                        if (local?.isAlive == true) {
                            local.destroyForcibly()
                        } else {
                            local?.destroy()
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }, "DiLink5ProjectionDisplayScan").apply {
            isDaemon = true
            start()
        }
        try {
            scan.join(1_500L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (scan.isAlive) {
            try {
                process.get()?.destroyForcibly()
            } catch (_: Throwable) {
            }
            return null
        }
        return resolved.get()
    }

    private fun displaySize(display: Display): Point {
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point
    }

    private fun applyInitialDirectBounds(live: Session): Boolean {
        val customWindow = if (live.kind == SessionKind.CUSTOM) live.miniMapBounds ?: live.requestedCustomMiniMapBounds else null
        val bounds = customWindow ?: persistedBounds(live.pkg, live.displayWidth, live.displayHeight)
            ?: if (live.factoryMiniMap) factoryMiniMapDefaultBounds(live.displayWidth, live.displayHeight) else return true
        return applyDirectBounds(live, bounds, true)
    }

    private fun applyDirectBounds(live: Session, bounds: DiLink5ProjectionBounds, commit: Boolean): Boolean {
        val requested = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (!live.factoryMiniMap) {
            return ClusterFreeformWindow.applyBounds(live.pkg, requested, commit, live.targetDisplayId)
        }
        val actual = ClusterFreeformWindow.resizeFactoryMiniMapTask(
            live.pkg, live.targetDisplayId, requested, commit
        ) ?: return false
        if (commit) {
            live.miniMapBounds = DiLink5ProjectionBounds(actual.left, actual.top, actual.right, actual.bottom)
        }
        return true
    }

    private fun persistedBounds(
        pkg: String,
        width: Int,
        height: Int
    ): DiLink5ProjectionBounds? {
        return try {
            val rect = UnifiedConfigManager.loadConfig()
                .optJSONObject("projection")
                ?.optJSONObject("windows")
                ?.optJSONObject(pkg)
                ?: return null
            val left = Math.round(rect.optDouble("l", 0.0) * width).toInt()
            val top = Math.round(rect.optDouble("t", 0.0) * height).toInt()
            val right = Math.round(rect.optDouble("r", 1.0) * width).toInt()
            val bottom = Math.round(rect.optDouble("b", 1.0) * height).toInt()
            val bounds = clampDiLink5ProjectionBounds(
                left, top, right, bottom, width, height
            ) ?: return null
            if (bounds.left <= 0 &&
                bounds.top <= 0 &&
                bounds.right >= width &&
                bounds.bottom >= height
            ) null else bounds
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Last-resort task cleanup shared by the failed-start, stop and recovery
     * paths: remove the third-party task whose cluster-display affinity could
     * not be moved back, then require a PARSED proof that it is gone
     * (TASK_LOOKUP_FAILED never passes for absence). Never touches our own map.
     */
    private fun forceStopAndConfirmAbsent(pkg: String): Boolean {
        if (pkg == DI5_MAP_PACKAGE || isFactoryMapProvider(pkg) || !AppLauncher.isValidPackageName(pkg)) {
            return false
        }
        if (!AppLauncher.forceStopPackage(pkg)) return false
        val absent = AppLauncher.taskConfirmedAbsent(pkg)
        logger.warn(
            "DI5 cluster cast: task re-home fell back to force-stop for $pkg " +
                    "(confirmedAbsent=$absent)"
        )
        return absent
    }

    private fun isFactoryMapProvider(pkg: String): Boolean =
        pkg == AaosNavigationPresentation.PACKAGE && ClusterTransport.isAaosHdmi()

    private fun rehomeTask(pkg: String, foreground: Boolean): Boolean =
        try {
            if (isFactoryMapProvider(pkg)) {
                // No legitimate AAOS session ever owns the factory task, even if a stale marker names it.
                true
            } else if (AppLauncher.taskConfirmedAbsent(pkg)) {
                // No live task ⇒ nothing stranded; also skip the foreground
                // launch — "move back to the head unit" must not cold-start a
                // dead app. A FAILED lookup falls through to reparentToDisplay0,
                // which re-checks and keeps recovery pending on lookup failure.
                true
            } else {
                AppLauncher.reparentToDisplay0(pkg) &&
                        (!foreground || AppLauncher.launchOnDisplay(pkg, Display.DEFAULT_DISPLAY))
            }
        } catch (t: Throwable) {
            logger.warn("DI5 cluster cast: task re-home failed: ${t.message}")
            false
        }

    private fun powerDownCompositor(
        ownershipGeneration: Long,
        retainIndeterminateOwnership: Boolean = false,
        teardownMayStillComplete: Boolean = false,
        shouldContinue: (() -> Boolean)? = null
    ): Boolean {
        // A legacy DI5 marker may survive a restore. This platform has no DI5 compositor.
        if (ClusterTransport.isAaosHdmi()) return true
        if (!detachProjectionConsumersBeforeCompositorClose()) {
            logger.warn(
                "DI5 cluster cast: refusing compositor close until every " +
                        "mirror consumer confirms detach"
            )
            return false
        }
        if (teardownMayStillComplete) {
            logger.warn(
                "DI5 cluster cast: refusing same-boot compositor restore " +
                        "because an earlier teardown transaction may still complete"
            )
            return false
        }
        if (!powerDownCompositorOnce(
                ownershipGeneration, shouldContinue
            )
        ) return false
        if (!retainIndeterminateOwnership) return true

        // A timed-out opcode 16 can continue in the vendor Binder server after
        // its standalone client has been killed. Place a second best-effort
        // restore after a quiet period, but do NOT treat elapsed time as proof
        // that the old transaction is dead. Durable ownership remains until a
        // changed kernel boot ID proves the transaction cannot still finish.
        logger.warn(
            "DI5 cluster cast: waiting for indeterminate enable fence before " +
                    "a second compositor restore; ownership will remain durable"
        )
        if (!waitForEnableFence(shouldContinue)) return false
        if (!powerDownCompositorOnce(
                ownershipGeneration, shouldContinue
            )
        ) return false
        logger.warn(
            "DI5 cluster cast: compositor restored after an indeterminate " +
                    "enable, but recovery ownership is retained until the " +
                    "next verified OS boot"
        )
        return false
    }

    private fun detachProjectionConsumersBeforeCompositorClose(): Boolean {
        val viewDetached = try {
            ClusterViewMirrorService.detachBeforeProjectionClose(
                "di5-compositor-close"
            )
        } catch (t: Throwable) {
            logger.warn(
                "DI5 view-mirror detach failed: ${t.message}"
            )
            false
        }
        val clusterDetached = try {
            ClusterMirrorController.detachBeforeProjectionClose(
                "di5-compositor-close"
            )
        } catch (t: Throwable) {
            logger.warn(
                "DI5 cluster-mirror detach failed: ${t.message}"
            )
            false
        }
        return viewDetached && clusterDetached
    }

    private fun waitForEnableFence(
        shouldContinue: (() -> Boolean)?
    ): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() +
                INDETERMINATE_ENABLE_FENCE_MS
        while (true) {
            if (shouldContinue?.invoke() == false) return false
            val remaining =
                deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                return shouldContinue?.invoke() != false
            }
            try {
                Thread.sleep(minOf(ENABLE_FENCE_POLL_MS, remaining))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun powerDownCompositorOnce(
        ownershipGeneration: Long,
        shouldContinue: (() -> Boolean)?
    ): Boolean {
        if (shouldContinue?.invoke() == false) return false
        val close = sendMarkedTeardownCommand(
            18, ownershipGeneration, shouldContinue
        )
        if (shouldContinue?.invoke() == false) return false
        if (close ==
                ClusterProjectionController.DiLink5CommandResult.UNAVAILABLE
        ) {
            // UNAVAILABLE is strict: no AutoContainer service alias answered
            // ("Service does not exist" on every endpoint) — this firmware
            // simply does not expose the compositor service. No opcode 18 was
            // ever accepted server-side, so there is nothing to order the
            // dependent refresh after and nothing left to power down; the
            // refresh (opcode 0) would be equally UNAVAILABLE and is skipped.
            // Treating this as "retain ownership" permanently wedged every
            // recovery on AutoContainer-less ROMs (log_2MEH8B86:7210 →
            // "stale projection recovery incomplete" until retries exhausted).
            // Transient transport problems surface as TRANSPORT_FAILURE, and
            // an endpoint that answered ambiguously as INDETERMINATE — both
            // still retain ownership below. The in-flight teardown marker was
            // already cleared by sendMarkedTeardownCommand for this result.
            logger.info(
                "DI5 cluster cast: compositor close 18=$close — no " +
                        "AutoContainer service on this firmware; treating " +
                        "power-down as complete"
            )
            return true
        }
        if (close ==
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE ||
            close ==
                ClusterProjectionController.DiLink5CommandResult.TRANSPORT_FAILURE
        ) {
            // Refresh depends on close being ordered first. A killed Binder
            // client cannot prove that an indeterminate server-side opcode 18
            // has stopped, so issuing opcode 0 now could be overtaken by a
            // late close and leave the cluster gauges unrestored.
            logger.warn(
                "DI5 cluster cast: compositor close 18=$close; deferring " +
                        "dependent refresh and retaining recovery ownership"
            )
            return false
        }
        try {
            Thread.sleep(CONTAINER_CLOSE_GAP_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (shouldContinue?.invoke() == false) return false
        val refresh = sendMarkedTeardownCommand(
            0, ownershipGeneration, shouldContinue
        )
        logger.info(
            "DI5 cluster cast: compositor close 18=$close refresh 0=$refresh"
        )
        return close == ClusterProjectionController.DiLink5CommandResult.ACCEPTED &&
                refresh == ClusterProjectionController.DiLink5CommandResult.ACCEPTED
    }

    private fun sendMarkedTeardownCommand(
        opcode: Int,
        ownershipGeneration: Long,
        shouldContinue: (() -> Boolean)?
    ): ClusterProjectionController.DiLink5CommandResult =
        executeDiLink5MarkedTeardownCommand(
            markInFlight = {
                val bootId = readCurrentBootId()
                synchronized(stateLock) {
                    if (teardownIndeterminateGeneration != 0L &&
                        teardownIndeterminateGeneration != ownershipGeneration
                    ) {
                        return@synchronized false
                    }
                    val written = writeTeardownMarker(
                        indeterminate = true,
                        bootId = bootId
                    )
                    if (written) {
                        teardownIndeterminateGeneration =
                            ownershipGeneration
                        teardownIndeterminateBootId = bootId
                    }
                    written
                }
            },
            shouldSend = {
                shouldContinue?.invoke() != false
            },
            send = {
                ClusterProjectionController
                    .sendDiLink5ContainerCleanupResult(opcode)
            },
            clearInFlight = {
                synchronized(stateLock) {
                    if (teardownIndeterminateGeneration !=
                        ownershipGeneration
                    ) {
                        return@synchronized false
                    }
                    val written = writeTeardownMarker(
                        indeterminate = false,
                        bootId = ""
                    )
                    if (written) {
                        teardownIndeterminateGeneration = 0L
                        teardownIndeterminateBootId = ""
                    }
                    written
                }
            }
        )

    private fun writeTeardownMarker(
        indeterminate: Boolean,
        bootId: String
    ): Boolean =
        try {
            UnifiedConfigManager.updateValues(
                "surveillance",
                mapOf(
                    MARKER_TEARDOWN_INDETERMINATE to indeterminate,
                    MARKER_TEARDOWN_BOOT_ID to bootId
                )
            )
        } catch (_: Throwable) {
            false
        }

    private fun writeMarker(
        pending: Boolean,
        powered: Boolean,
        appMoved: Boolean,
        enableIndeterminate: Boolean,
        enableBootId: String,
        pkg: String
    ): Boolean =
        try {
            UnifiedConfigManager.updateValues(
                "surveillance",
                mapOf(
                    MARKER_PENDING to pending,
                    MARKER_POWERED to powered,
                    MARKER_APP_MOVED to appMoved,
                    MARKER_ENABLE_INDETERMINATE to enableIndeterminate,
                    MARKER_ENABLE_BOOT_ID to enableBootId,
                    MARKER_PACKAGE to pkg
                )
            )
        } catch (_: Throwable) {
            false
        }

    private fun readCurrentBootId(): String =
        try {
            canonicalDiLink5BootIdOrNull(
                java.io.File("/proc/sys/kernel/random/boot_id")
                .readText()
                .trim()
            ) ?: ""
        } catch (_: Throwable) {
            ""
        }

    private fun completeStart(expectedGeneration: Long, success: Boolean, reason: String) {
        val ticket: StartTicket?
        synchronized(stateLock) {
            ticket = pendingStart?.takeIf { it.generation == expectedGeneration }
            if (ticket != null) {
                ticket.succeeded.set(success)
                pendingStart = null
                if (!success && reason.isNotEmpty()) lastFailureReason = reason
            }
        }
        ticket?.completion?.countDown()
    }

    private fun isCurrent(expectedGeneration: Long, pkg: String): Boolean =
        !stopping && generation == expectedGeneration && activePackage == pkg

    private fun releaseInstrumentMode(live: Session?): Boolean {
        if (live?.sealion7Hdmi != true) return true
        val context = CameraDaemon.getAppContext() ?: return false
        return DiLink5InstrumentFullSession.release(context, live.generation, readCurrentBootId())
    }

    private fun recoverInstrumentModeIfIdle(): Boolean {
        if (!Sealion7HdmiProjection.isTestedFirmware()) return true
        synchronized(taskMutationLock.lock) {
            if (activePackage != null || !DiLink5InstrumentFullSession.hasPendingRestore()) return true
            val context = CameraDaemon.getAppContext() ?: return false
            return DiLink5InstrumentFullSession.recover(context, readCurrentBootId())
        }
    }

    /** A same-boot ACC-off leaves the generation journal until vehicle communication is available. */
    @JvmStatic
    fun onInstrumentAccOn() {
        operations.execute {
            if (!recoverInstrumentModeIfIdle()) logger.warn("Sealion 7 instrument restoration remains pending")
        }
    }

    private fun prepareHostedOwnershipForAdmission(): Boolean {
        synchronized(stateLock) {
            if (activePackage != null) return true
            if (activeCleanup != null || stopping ||
                phase == SessionPhase.STOPPING || phase == SessionPhase.RECOVERING
            ) {
                lastFailureReason = "previous hosted projection cleanup is still in progress"
                return false
            }
        }
        // Reattempt retained failures on every inactive admission, not only after daemon restart.
        recoverUnpublishedHostedResources()
        recoverPersistedHostedOwnership()
        if (!recoverInstrumentModeIfIdle()) {
            lastFailureReason = "Previous Sealion 7 instrument mode is awaiting restoration"
            return false
        }
        val currentBoot = readCurrentBootId()
        val unresolved = DiLink5ProjectionOwnership.loadAll().filter { ownership ->
            ownership.bootId.isEmpty() || currentBoot.isEmpty() || ownership.bootId == currentBoot
        }
        if (unresolved.isNotEmpty()) {
            lastFailureReason = "previous hosted projection ownership is unresolved"
            logger.warn("DI5 cluster cast: refusing admission with ${unresolved.size} unresolved hosted owner(s)")
            return false
        }
        val durableFloor = DiLink5ProjectionOwnership.loadAll()
            .maxOfOrNull { it.generation } ?: 0L
        synchronized(stateLock) {
            generation = maxOf(generation, durableFloor)
            hostedResourceSequence.set(maxOf(hostedResourceSequence.get(), generation))
        }
        return true
    }

    private fun nextHostedResourceGeneration(): Long {
        val durableFloor = DiLink5ProjectionOwnership.loadAll()
            .maxOfOrNull { it.generation } ?: 0L
        while (true) {
            val observed = hostedResourceSequence.get()
            val floor = maxOf(observed, generation, durableFloor)
            val next = nextPositiveGeneration(floor)
            if (hostedResourceSequence.compareAndSet(observed, next)) return next
        }
    }

    private fun nextGenerationLocked(): Long {
        val floor = maxOf(generation, hostedResourceSequence.get())
        return nextPositiveGeneration(floor)
    }
}

internal fun nextPositiveGeneration(floor: Long): Long =
    if (floor in 0 until Long.MAX_VALUE) floor + 1L else 1L

internal fun diLink5ResizeOwnershipMatches(
    expectedPackage: String?,
    expectedGeneration: Long,
    actualPackage: String,
    actualGeneration: Long,
): Boolean = (expectedPackage.isNullOrEmpty() || expectedPackage == actualPackage) &&
    (expectedGeneration == 0L || expectedGeneration == actualGeneration) &&
    expectedGeneration >= 0L && actualGeneration > 0L

internal fun completeHostedGenerationCleanup(
    densityRestored: Boolean,
    densityLeasePending: Boolean,
    releaseResources: () -> Boolean,
): Boolean = (densityRestored || !densityLeasePending) &&
    !densityLeasePending && releaseResources()

internal fun finishHostedResourceCleanup(
    resources: Iterable<Long>,
    release: (Long) -> Boolean,
    clearRegistration: (Long) -> Unit,
): Boolean {
    var complete = true
    resources.distinct().forEach {
        if (release(it)) clearRegistration(it) else complete = false
    }
    return complete
}

internal data class HostedAllocationStart<T>(
    val registered: Boolean,
    val value: T?,
    val publicationAllowed: Boolean = registered,
)

internal fun <T> startHostedAllocation(
    register: () -> Boolean,
    start: () -> T?,
    current: () -> Boolean = { true },
): HostedAllocationStart<T> {
    if (!register()) return HostedAllocationStart(false, null, false)
    val value = start()
    return HostedAllocationStart(true, value, value == null || current())
}

internal class TaskMutationAuthority {
    val lock = Any()

    inline fun <T> mutate(block: () -> T): T = synchronized(lock, block)
}

internal fun canReleaseUnpublishedHostedResource(
    taskMayBeMoved: Boolean,
    densityOwned: Boolean,
): Boolean = !taskMayBeMoved && !densityOwned

internal fun reconcileLateHostedAllocation(
    republish: () -> Boolean,
    release: () -> Boolean,
    updateRegistration: (Boolean) -> Unit,
): Boolean {
    val persisted = republish()
    val released = release()
    updateRegistration(released)
    return persisted && released
}

internal fun runHostedMoveDispatch(
    current: () -> Boolean,
    register: () -> Unit,
    dispatch: Runnable,
): Boolean {
    if (!current()) return false
    register()
    dispatch.run()
    return true
}

internal fun completeHostedReplacementCleanup(
    restoreDensity: () -> Boolean,
    releaseResources: () -> Boolean,
    clearRegistration: () -> Unit,
): Boolean {
    if (!restoreDensity()) return false
    if (!releaseResources()) return false
    clearRegistration()
    return true
}

internal fun completeHostedSurfaceLoss(
    isMap: Boolean,
    stop: () -> Boolean,
    onMapFailure: () -> Unit,
): Boolean {
    val cleanupComplete = stop()
    if (isMap) onMapFailure()
    return cleanupComplete
}

internal fun pendingHostedSurfaceLossNeedsCleanup(
    preparing: Boolean,
    active: Boolean,
    pendingReplacement: Boolean,
    taskMayBeMoved: Boolean,
    packageMatches: Boolean,
): Boolean = preparing || (active && pendingReplacement && taskMayBeMoved && packageMatches)

internal fun hostedSurfaceLossNeedsCleanup(
    resourceGeneration: Long,
    publishedResourceGeneration: Long,
    registeredForCleanup: Boolean,
    preparing: Boolean,
    active: Boolean,
    pendingReplacement: Boolean,
    taskMayBeMoved: Boolean,
    packageMatches: Boolean,
): Boolean = resourceGeneration > 0L &&
    (resourceGeneration == publishedResourceGeneration ||
        (registeredForCleanup && pendingHostedSurfaceLossNeedsCleanup(
            preparing, active, pendingReplacement, taskMayBeMoved, packageMatches,
        )))

/** Called under the same task/state locks as publication; a queued loss is rechecked here. */
internal fun claimHostedSurfaceLoss(current: () -> Boolean, claim: () -> Unit): Boolean {
    if (!current()) return false
    claim()
    return true
}

internal fun executeDiLink5MarkedTeardownCommand(
    markInFlight: () -> Boolean,
    shouldSend: () -> Boolean,
    send: () -> ClusterProjectionController.DiLink5CommandResult,
    clearInFlight: () -> Boolean
): ClusterProjectionController.DiLink5CommandResult {
    if (!shouldSend()) {
        return ClusterProjectionController.DiLink5CommandResult
            .TRANSPORT_FAILURE
    }
    if (!markInFlight()) {
        return ClusterProjectionController.DiLink5CommandResult
            .INDETERMINATE
    }
    if (!shouldSend()) {
        return if (clearInFlight()) {
            ClusterProjectionController.DiLink5CommandResult
                .TRANSPORT_FAILURE
        } else {
            ClusterProjectionController.DiLink5CommandResult
                .INDETERMINATE
        }
    }
    val result = try {
        send()
    } catch (_: Throwable) {
        return ClusterProjectionController.DiLink5CommandResult
            .INDETERMINATE
    }
    if (result ==
        ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
    ) {
        return result
    }
    return if (clearInFlight()) {
        result
    } else {
        ClusterProjectionController.DiLink5CommandResult.INDETERMINATE
    }
}

internal data class DiLink5ProjectionBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

internal data class DiLink5ProjectionDisplayRef(
    val displayId: Int,
    val name: String,
    val width: Int = 0,
    val height: Int = 0
)

/** Owner-tested right-hand window on the Sea Lion 07's current _1 display. */
internal fun factoryMiniMapDefaultBounds(width: Int, height: Int): DiLink5ProjectionBounds {
    val windowWidth = (width * 0.32).roundToInt().coerceIn(1, width)
    return DiLink5ProjectionBounds(width - windowWidth, 0, width, height)
}

/** Logical panel passed to the Custom Layouts renderer inside the projection window. */
internal fun diLink5CustomLayoutsPanel(
    displayName: String,
    width: Int,
    height: Int,
    hosted: HostedProjectionGeometry?,
    miniMap: DiLink5ProjectionBounds?,
    sealion7Hdmi: Boolean,
): Panel {
    if (miniMap != null) return Panel("full", miniMap.width(), miniMap.height())
    if (sealion7Hdmi) return Panel("band", Sealion7HdmiProjection.WIDTH, 480)
    val spansPanel = hosted == null || (hosted.overlay.width() == hosted.panelWidth &&
        hosted.overlay.height() == hosted.panelHeight)
    val mode = if (spansPanel && displayName.endsWith("_1", ignoreCase = true)) "band" else "full"
    return Panel(mode, hosted?.bufferWidth ?: width, hosted?.bufferHeight ?: height)
}

internal enum class DiLink5ProjectionTopology {
    SHARED_FISSION,
    NUMBERED_FISSION,
    TRINKET_DEBUG_ONLY,
    FISSION_DEBUG_ONLY,
    AAOS_HDMI_CLOSED,
    SINGLE_OS_CLOSED,
    DISCOVERY_REQUIRED
}

internal fun classifyDiLink5ProjectionTopology(
    product: String?,
    device: String?,
    fingerprint: String?,
    automotiveFeature: Boolean,
    fissionSingleOs: String?,
    displayNames: List<String>
): DiLink5ProjectionTopology {
    val hasSharedFission = displayNames.any {
        it.contains("shared_", ignoreCase = true) &&
                it.contains(
                    "xdjascreenprojection",
                    ignoreCase = true
                ) &&
                (it.endsWith("_0", ignoreCase = true) ||
                        it.endsWith("_1", ignoreCase = true))
    }
    if (hasSharedFission) {
        return DiLink5ProjectionTopology.SHARED_FISSION
    }

    val hasNumberedFission = displayNames.any {
        !it.contains("shared_", ignoreCase = true) &&
                it.contains(
                    "xdjascreenprojection",
                    ignoreCase = true
                ) &&
                (it.endsWith("_0", ignoreCase = true) ||
                        it.endsWith("_1", ignoreCase = true))
    }
    if (hasNumberedFission) {
        return DiLink5ProjectionTopology.NUMBERED_FISSION
    }

    if (fissionSingleOs?.trim() == "1") {
        return DiLink5ProjectionTopology.SINGLE_OS_CLOSED
    }

    val hasPlainDebugFission = displayNames.any {
        it.equals(DI5_DEBUG_DISPLAY, ignoreCase = true)
    }
    if (hasPlainDebugFission) {
        return if (requiresSharedDiLink5ProjectionDisplay(
                product, device, fingerprint
            )
        ) {
            DiLink5ProjectionTopology.TRINKET_DEBUG_ONLY
        } else {
            DiLink5ProjectionTopology.FISSION_DEBUG_ONLY
        }
    }

    if (isClosedAaosProjectionTopology(
            product, automotiveFeature, displayNames
        )
    ) {
        return DiLink5ProjectionTopology.AAOS_HDMI_CLOSED
    }
    return DiLink5ProjectionTopology.DISCOVERY_REQUIRED
}

internal fun shouldForceRestartDiLink5Task(
    locationKnown: Boolean,
    taskId: Int,
    currentDisplayId: Int,
    targetDisplayId: Int
): Boolean =
    locationKnown &&
            taskId > 0 &&
            currentDisplayId >= 0 &&
            targetDisplayId > 0 &&
            currentDisplayId != targetDisplayId

internal fun resolveDiLink5ProjectionPanelSize(
    refs: List<DiLink5ProjectionDisplayRef>,
    selected: DiLink5ProjectionDisplayRef
): Pair<Int, Int>? {
    if (selected.width > 1 && selected.height > 1) {
        return selected.width to selected.height
    }
    return refs.firstOrNull {
        it.name.equals(DI5_DEBUG_DISPLAY, ignoreCase = true) &&
                it.width > 1 &&
                it.height > 1
    }?.let { it.width to it.height }
}

internal fun isSharedDiLink5ProjectionDisplayName(name: String): Boolean =
    name.contains("shared_", ignoreCase = true) &&
            name.contains("xdjascreenprojection", ignoreCase = true) &&
            (name.endsWith("_0", ignoreCase = true) ||
                    name.endsWith("_1", ignoreCase = true))

/**
 * @param preferredName a shared display that previously survived an OEM
 *   takeover (learned, persisted); wins when present and not excluded.
 * @param excludedNames displays an OEM cluster app recently reclaimed; they are
 *   skipped entirely, so a caller wanting an ALTERNATIVE gets null rather than
 *   the same reclaimed surface again.
 */
internal fun selectDiLink5ProjectionDisplayName(
    names: List<String>,
    preferFull: Boolean,
    requireShared: Boolean = false,
    preferredName: String? = null,
    excludedNames: Set<String> = emptySet()
): String? {
    val candidates = if (excludedNames.isEmpty()) {
        names
    } else {
        names.filterNot { name ->
            excludedNames.any { it.equals(name, ignoreCase = true) }
        }
    }
    if (!preferredName.isNullOrBlank()) {
        candidates.firstOrNull { it.equals(preferredName, ignoreCase = true) }
            ?.let { return it }
    }
    return selectDefaultDiLink5ProjectionDisplayName(
        candidates, preferFull, requireShared
    )
}

private fun selectDefaultDiLink5ProjectionDisplayName(
    names: List<String>,
    preferFull: Boolean,
    requireShared: Boolean
): String? {
    // OEM BydProjectionService explicitly launches its meter activity on shared ..._0;
    // upstream field evidence also ranks _0 above _1. Keep that physical-routing
    // primitive deterministic regardless of an old/manual size preference. `_1`
    // remains a fallback when `_0` is genuinely absent — or, via the caller's
    // exclusion set, when an OEM cluster app keeps reclaiming `_0` (DiLink 5.0
    // Atto 3: com.byd.sr re-fronts on _0 every few seconds while _1 carries the
    // container service's 1920x480 app-projection override).
    val preferredShared = if (preferFull) {
        listOf(DI5_SHARED_DISPLAY_FULL, DI5_SHARED_DISPLAY_STANDARD)
    } else {
        listOf(DI5_SHARED_DISPLAY_STANDARD, DI5_SHARED_DISPLAY_FULL)
    }
    preferredShared.forEach { expected ->
        names.firstOrNull { it.equals(expected, ignoreCase = true) }?.let { return it }
    }

    val sharedFamily = names.filter {
        it.contains("shared_", ignoreCase = true) &&
                it.contains("xdjascreenprojection", ignoreCase = true) &&
                (it.endsWith("_0", ignoreCase = true) ||
                        it.endsWith("_1", ignoreCase = true))
    }
    if (sharedFamily.isNotEmpty()) {
        return sharedFamily.minWithOrNull(
            compareBy<String>(
                {
                    when {
                        preferFull && it.endsWith("_0", ignoreCase = true) -> 0
                        !preferFull && it.endsWith("_1", ignoreCase = true) -> 0
                        it.endsWith("_0", ignoreCase = true) ||
                            it.endsWith("_1", ignoreCase = true) -> 1
                        else -> 2
                    }
                }
            )
        )
    }

    // On trinket, a task on the unnumbered background can be captured even
    // while the native instrument layout covers it. Require a known visible target.
    if (requireShared) return null

    // Older DI5.0 builds expose the production targets without the shared_
    // prefix. They are still numbered _0/_1. Never accept the unnumbered
    // fission_bg_XDJAScreenProjection debug surface.
    val preferredSuffix = if (preferFull) "_0" else "_1"
    val alternateSuffix = if (preferFull) "_1" else "_0"
    val numberedFamily = names.filter {
        !it.contains("shared_", ignoreCase = true) &&
                it.contains("xdjascreenprojection", ignoreCase = true) &&
                (it.endsWith("_0", ignoreCase = true) ||
                        it.endsWith("_1", ignoreCase = true))
    }
    if (numberedFamily.isNotEmpty()) {
        return numberedFamily.minWithOrNull(
            compareBy<String> {
                when {
                    it.endsWith(preferredSuffix, ignoreCase = true) -> 0
                    it.endsWith(alternateSuffix, ignoreCase = true) -> 1
                    else -> 2
                }
            }
        )
    }
    return null
}

internal fun isClosedAaosProjectionTopology(
    product: String?,
    automotiveFeature: Boolean,
    displayNames: List<String>
): Boolean {
    val normalizedProduct = product?.lowercase(Locale.US).orEmpty()
    val exactBydAaosProduct = normalizedProduct == "dx_byd_auto"
    val explicitBydAaos =
        exactBydAaosProduct ||
                normalizedProduct.contains("dx_byd_auto") ||
                normalizedProduct.contains("dx-byd-auto")
    val hasHdmi = displayNames.any {
        it.contains("hdmi", ignoreCase = true)
    }
    val hasAppProjectionSurface = displayNames.any {
        it.contains("fission", ignoreCase = true) ||
                it.contains("xdjascreenprojection", ignoreCase = true)
    }
    return hasHdmi &&
            !hasAppProjectionSurface &&
            (automotiveFeature || explicitBydAaos || exactBydAaosProduct)
}

@Suppress("UNUSED_PARAMETER")
internal fun mayOwnDiLink5ProjectionCompositor(
    pending: Boolean,
    powered: Boolean,
    enableIndeterminate: Boolean = false,
    teardownIndeterminate: Boolean = false
): Boolean =
    powered || enableIndeterminate || teardownIndeterminate

internal fun diLink5IndeterminateEnableMayStillComplete(
    enableIndeterminate: Boolean,
    originBootId: String,
    currentBootId: String
): Boolean =
    diLink5IndeterminateCommandMayStillComplete(
        enableIndeterminate,
        originBootId,
        currentBootId
    )

internal fun diLink5IndeterminateCommandMayStillComplete(
    indeterminate: Boolean,
    originBootId: String,
    currentBootId: String
): Boolean {
    if (!indeterminate) return false
    val origin = canonicalDiLink5BootIdOrNull(originBootId)
        ?: return true
    val current = canonicalDiLink5BootIdOrNull(currentBootId)
        ?: return true
    return origin == current
}

internal fun canonicalDiLink5BootIdOrNull(value: String): String? {
    if (value.length != 36) return null
    val parsed = try {
        java.util.UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val canonical = parsed.toString()
    return if (canonical == value) canonical else null
}

internal fun shouldTrackDiLink5CompositorOwnership(
    result: ClusterProjectionController.DiLink5CommandResult
): Boolean =
    result == ClusterProjectionController.DiLink5CommandResult.ACCEPTED ||
            result ==
                ClusterProjectionController.DiLink5CommandResult.INDETERMINATE

internal fun isUsableDiLink5ProjectionDisplay(
    valid: Boolean,
    state: Int
): Boolean =
    valid && state == Display.STATE_ON

internal fun requiresSharedDiLink5ProjectionDisplay(
    product: String?,
    device: String?,
    fingerprint: String?
): Boolean =
    listOf(product, device, fingerprint).any { value ->
        val normalized = value?.lowercase(Locale.US).orEmpty()
        normalized.contains("trinket") || normalized.contains("d50f")
    }

internal fun readDiLink5SystemProperty(key: String): String? =
    try {
        val properties = Class.forName("android.os.SystemProperties")
        val value = properties.getMethod(
            "get",
            String::class.java,
            String::class.java
        ).invoke(null, key, "") as? String
        value?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

internal fun readDiLink5SystemPropertyViaShell(key: String): String? {
    if (!key.matches(Regex("""[A-Za-z0-9._-]+"""))) return null
    var process: Process? = null
    return try {
        process = ProcessBuilder(
            "/system/bin/getprop",
            key
        ).redirectErrorStream(true).start()
        if (!process.waitFor(500L, TimeUnit.MILLISECONDS) ||
            process.exitValue() != 0
        ) {
            null
        } else {
            process.inputStream.bufferedReader().use {
                it.readLine()
            }?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Throwable) {
        null
    } finally {
        try {
            if (process?.isAlive == true) {
                process?.destroyForcibly()
            } else {
                process?.destroy()
            }
        } catch (_: Throwable) {
        }
    }
}

internal fun canDispatchDiLink5ContainerEnable(resolvedDisplayId: Int): Boolean =
    resolvedDisplayId > Display.DEFAULT_DISPLAY

internal fun shouldFallbackToHostedAfterDirectFailure(
    transport: String,
    automatic: Boolean,
    succeeded: Boolean,
    definiteFailure: Boolean,
): Boolean = transport == "direct" && automatic && !succeeded && definiteFailure

private fun projectionIdentityValue(value: String?): String {
    val compact = value
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        .orEmpty()
    if (compact.isEmpty()) return "<empty>"
    return if (compact.length <= 180) compact else compact.take(180) + "..."
}

internal fun parseDiLink5DisplayNames(lines: List<String>): List<String> {
    val headerPattern = Regex(
        """(?i)(?:DisplayInfo|DisplayDeviceInfo)\{"([^"]+)"""
    )
    val embeddedDisplayId = Regex(
        """(?i),\s*displayId\s*[= ]+\s*\d+\s*$"""
    )
    val names = LinkedHashSet<String>()
    lines.forEach { line ->
        val header =
            headerPattern.find(line)?.groupValues?.getOrNull(1)
                ?: return@forEach
        embeddedDisplayId.replace(header, "").trim()
            .takeIf { it.isNotEmpty() }
            ?.let(names::add)
    }
    return names.toList()
}

internal fun parseDiLink5ProjectionDisplays(
    lines: List<String>
): List<DiLink5ProjectionDisplayRef> {
    val headerPattern = Regex(
        """(?i)(?:DisplayInfo|DisplayDeviceInfo)\{"([^"]+)"""
    )
    val embeddedDisplayId = Regex(
        """(?i),\s*displayId\s*[= ]+\s*\d+\s*$"""
    )
    val idPattern = Regex("""(?i)\bdisplayId\s*[= ]+\s*(\d+)""")
    val found = LinkedHashMap<String, DiLink5ProjectionDisplayRef>()
    for (line in lines) {
        val low = line.lowercase(Locale.US)
        if (!Regex("""\bstate[ =]+on\b""").containsMatchIn(low)) {
            continue
        }
        val header = headerPattern.find(line)?.groupValues?.getOrNull(1) ?: continue
        // Android has emitted both of these forms on BYD firmware:
        //   DisplayInfo{"name", displayId 3", ...}
        //   DisplayInfo{"name, displayId 3", ...}
        // Keep display identity out of the name before applying the exact target policy.
        val name = embeddedDisplayId.replace(header, "").trim()
        val known = name.equals(DI5_DEBUG_DISPLAY, ignoreCase = true) ||
                (name.contains("xdjascreenprojection", ignoreCase = true) &&
                        (name.endsWith("_0", ignoreCase = true) ||
                                name.endsWith("_1", ignoreCase = true)))
        if (!known) continue
        val displayId = idPattern.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: continue
        if (displayId <= Display.DEFAULT_DISPLAY) continue
        val size = parseDiLink5ProjectionSize(line)
        val key = "$displayId:${name.lowercase(Locale.US)}"
        val candidate = DiLink5ProjectionDisplayRef(
            displayId,
            name,
            size?.first ?: 0,
            size?.second ?: 0
        )
        val existing = found[key]
        // dumpsys prints base before override. Preserve the base panel geometry; the
        // override can contain a square rotation envelope even though the panel is
        // physically 1920x720. Only replace an unusable first record with a usable one.
        if (existing == null ||
            ((existing.width <= 1 || existing.height <= 1) &&
                    candidate.width > 1 && candidate.height > 1)
        ) {
            found[key] = candidate
        }
    }
    return found.values.toList()
}

internal fun resolveFactoryMiniMapDisplay(lines: List<String>): DiLink5ProjectionDisplayRef? {
    // WindowManager can override the 1920x720 panel to a 1920x660 logical map.
    // Use that window space when sizing the app. Both records must name the actual mini-map.
    val mapLines = lines.filter { it.contains(DI5_SHARED_DISPLAY_STANDARD, ignoreCase = true) }
    val (overrides, others) = mapLines.partition { it.contains("mOverrideDisplayInfo=") }
    return parseDiLink5ProjectionDisplays(overrides.ifEmpty { others }).firstOrNull {
        it.name.equals(DI5_SHARED_DISPLAY_STANDARD, ignoreCase = true) && it.width > 1 && it.height > 1
    }
}

private fun parseDiLink5ProjectionSize(line: String): Pair<Int, Int>? {
    // Prefer the actual panel size. In the exact trinket dump, the same override
    // line also contains "largest app 1920 x 1920"; choosing the largest pair
    // turns a 1920x720 panel into a false square.
    for (label in listOf("real", "app", "logical")) {
        val match = Regex(
            """(?i)(?:^|,\s*)$label\s+(\d+)\s*x\s*(\d+)"""
        ).find(line) ?: continue
        val width = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
        val height = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
        if (width in 2..8192 && height in 2..8192) {
            return width to height
        }
    }
    return null
}

internal fun clampDiLink5ProjectionBounds(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    width: Int,
    height: Int
): DiLink5ProjectionBounds? {
    if (width <= 1 || height <= 1 || right <= left || bottom <= top) return null
    val minWidth = DI5_MIN_WINDOW_PX.coerceAtMost(width)
    val minHeight = DI5_MIN_WINDOW_PX.coerceAtMost(height)
    val requestedWidth = (right - left).coerceIn(minWidth, width)
    val requestedHeight = (bottom - top).coerceIn(minHeight, height)
    val clampedLeft = left.coerceIn(0, width - requestedWidth)
    val clampedTop = top.coerceIn(0, height - requestedHeight)
    return DiLink5ProjectionBounds(
        clampedLeft,
        clampedTop,
        clampedLeft + requestedWidth,
        clampedTop + requestedHeight
    )
}

internal enum class DensityAdoptionDecision {
    PRESERVE_ADOPTED,
    APPLY_FOR_NEW_TASK,
    DEFER_UNCERTAIN,
}

internal fun densityAdoptionDecision(taskOnTarget: Boolean?): DensityAdoptionDecision = when (taskOnTarget) {
    true -> DensityAdoptionDecision.PRESERVE_ADOPTED
    false -> DensityAdoptionDecision.APPLY_FOR_NEW_TASK
    null -> DensityAdoptionDecision.DEFER_UNCERTAIN
}

internal enum class HostedResizeMoveDisposition {
    RELEASE_UNMOVED,
    RETAIN_UNCERTAIN,
    CONTINUE_VERIFICATION,
}

internal fun hostedResizeMoveDisposition(
    result: ClusterFreeformWindow.MoveTaskResult,
    sessionCurrent: Boolean,
): HostedResizeMoveDisposition = when {
    !sessionCurrent -> HostedResizeMoveDisposition.RETAIN_UNCERTAIN
    result == ClusterFreeformWindow.MoveTaskResult.NOT_DISPATCHED ->
        HostedResizeMoveDisposition.RELEASE_UNMOVED
    result == ClusterFreeformWindow.MoveTaskResult.DISPATCHED_UNCONFIRMED ->
        HostedResizeMoveDisposition.RETAIN_UNCERTAIN
    else -> HostedResizeMoveDisposition.CONTINUE_VERIFICATION
}

internal fun densityLossEpisode(
    generation: Long,
    episode: Long,
    placedTaskId: Int,
): String = "$generation:$episode:$placedTaskId"

/** One unresolved loss keeps its token until a known task returns to the exact target. */
internal class DirectDensityLossEpisodes(private val generation: Long) {
    private var episode = 0L
    private var unresolvedLoss: String? = null

    fun episodeForLoss(placedTaskId: Int): String =
        unresolvedLoss ?: densityLossEpisode(generation, episode, placedTaskId).also {
            unresolvedLoss = it
        }

    fun confirmTaskReturn(
        task: ClusterFreeformWindow.TaskLocation,
        targetDisplayId: Int,
    ): Boolean {
        if (!task.known || task.taskId <= 0 || task.displayId != targetDisplayId) return false
        if (unresolvedLoss != null) {
            unresolvedLoss = null
            episode++
        }
        return true
    }
}

/** A living freeform task can move without a density loss; a fullscreen reset remains a loss. */
internal fun isDirectDensityLoss(
    placedTaskId: Int,
    observedTaskId: Int,
    observedDisplayId: Int,
    targetDisplayId: Int,
    observedWindowingMode: Int?,
): Boolean = directDensityLossReason(
    placedTaskId, observedTaskId, observedDisplayId, targetDisplayId, observedWindowingMode,
) != null

internal fun directDensityLossReason(
    placedTaskId: Int,
    observedTaskId: Int,
    observedDisplayId: Int,
    targetDisplayId: Int,
    observedWindowingMode: Int?,
): DirectDensityLossReason? {
    if (observedTaskId <= 0) return DirectDensityLossReason.MISSING_TASK
    if (observedDisplayId == targetDisplayId) return null
    val movedLivingFreeformTask = observedWindowingMode == 5 &&
        (placedTaskId <= 0 || observedTaskId == placedTaskId)
    if (movedLivingFreeformTask) return null
    return if (placedTaskId > 0 && observedTaskId != placedTaskId)
        DirectDensityLossReason.RECREATED_TASK else DirectDensityLossReason.TASK_MODE_RESET
}

/**
 * Preserve a shell-only enable obligation across target replacement. An app-visible replacement
 * enables before placement; a shell-only replacement keeps that enable deferred until verification.
 */
internal fun prepareDiLink5AlternativeCompositor(
    enableDeferred: Boolean,
    appDisplayVisible: Boolean,
    enableResolvedTarget: () -> Boolean,
): Boolean {
    if (!enableDeferred) return false
    if (!appDisplayVisible) return true
    check(enableResolvedTarget()) { "compositor enable failed before alternative direct placement" }
    return false
}
