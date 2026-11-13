package com.overdrive.app.surveillance;

import android.content.Context;
import android.os.IBinder;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.launcher.DiLink5ClusterCast;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-owned lifecycle controller for the OEM driver-cluster projection, used
 * ONLY when the blind-spot target is "cluster". It opens the OEM cluster
 * projection (so the cluster's {@code fission} PRESENTATION VirtualDisplay,
 * layerStack 1, materialises and our already-rendered blind-spot SurfaceControl
 * layer can be retargeted onto it), and — critically — guarantees the live gauges
 * are RESTORED on every exit path.
 *
 * <h3>Why this is safe to drive from here</h3>
 * The daemon runs as uid 2000 (shell), which is platform-signed. The OEM
 * container service's send-permission gate is a signature check
 * ({@code checkSignatures(selfUid, callingUid) == 0 → allowed}); a platform-signed
 * caller bypasses the package allowlist. Verified on-device: the projection
 * opcodes (size-profile / fullscreen-on / di4-mode / close / refresh) are accepted
 * from this uid (no SecurityException) and a uid-2000 SurfaceControl layer tagged
 * layerStack=1 composites onto the cluster.
 *
 * <h3>Projection is a FULL takeover</h3>
 * There is no partial/overlay cluster projection on this firmware — opening the
 * projection replaces the native gauges for its duration. To bound that, the
 * timing model is <b>lazy-open-on-first-signal + linger-close</b>: open on the
 * first turn-signal while armed for the cluster, stay open across the rapid blink
 * on/off phases, and close (restore gauges) after {@link #lingerCloseMs} of no
 * signal, or a hard {@link #maxProjectionMs} cap, or disarm/disable/shutdown.
 *
 * <h3>Gauge-restore guarantee (the whole point)</h3>
 * Every path that could leave the gauges blanked has a force-restore:
 * <ul>
 *   <li>blink-off → linger close (18→0)</li>
 *   <li>signal stuck-on → {@link #maxProjectionMs} cap force-closes</li>
 *   <li>ACC-off / disable / target→head-unit → {@link #forceClose}</li>
 *   <li>open-sequence error / ready-timeout → {@link #forceClose}</li>
 *   <li>daemon SIGTERM/normal exit → {@link #shutdown} fires 18→0 synchronously</li>
 *   <li>daemon SIGKILL → on respawn {@link #clearStaleGateAtBoot} reads the leaked
 *       UCM gate flag and blind-fires 18→0 (the opcode path is stateless)</li>
 * </ul>
 * NEVER sends opcode 1 (disconnects Qt entirely / destroys the display).
 *
 * <p>All opcode sends + sleeps run on a dedicated thread so the 250 ms blind-spot
 * turn loop and the GL thread are never blocked.
 */
public final class ClusterProjectionController {

    private static final String TAG = "ClusterProjection";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // ── Projection opcodes (sendInfo type = CLUSTER_TYPE, infoInt = opcode) ──────
    private static final int CLUSTER_TYPE     = 1000;
    // Cluster size profile: 29=8.8", 30=12.3", 31=10.25" native. 31 is correct for
    // this cluster (confirmed on-device — 30 gave a wrong/stretched aspect). UCM
    // surveillance.clusterSizeProfile overrides; 0 skips the size step (just 16→35).
    private static final int OP_SIZE_PROFILE  = 31;  // 10.25" native (was 30=12.3", wrong aspect)
    private static final int OP_FULLSCREEN_ON = 16;  // full-screen projection ON
    private static final int OP_DI4_MODE      = 35;  // Di4.0 mode → triggers VD creation
    private static final int OP_CLOSE         = 18;  // close projection
    private static final int OP_REFRESH       = 0;   // refresh/restore native gauge render
    private static final String AUTO_CONTAINER_SERVICE = "AutoContainer";
    private static final String[] DI5_AUTO_CONTAINER_SERVICES = {
            AUTO_CONTAINER_SERVICE, "auto_container"
    };
    private static final String SYSTEM_SERVICE_BIN = "/system/bin/service";
    private static final String SYSTEM_CMD_BIN = "/system/bin/cmd";
    private static final String SYSTEM_AM_BIN = "/system/bin/am";
    private static final String XDJA_CONTAINER_PACKAGE =
            "com.xdja.containerservice";
    private static final String BYD_CONTAINER_PACKAGE =
            "com.byd.containerservice";
    private static final String XDJA_DISPLAY_SERVICE_COMPONENT =
            "com.xdja.containerservice/"
                    + "com.xdja.containerservice.AutoDisplayService";
    private static final long DI5_CONTAINER_BOOTSTRAP_TIMEOUT_MS = 2500L;
    private static final long DI5_CONTAINER_BOOTSTRAP_THROTTLE_MS = 5000L;
    private static final Object DI5_CONTAINER_BOOTSTRAP_LOCK = new Object();
    private static long lastDiLink5ContainerBootstrapMs = Long.MIN_VALUE;
    // NOTE: opcode 1 (disconnect Qt entirely / destroy display) is deliberately
    // NOT defined and NEVER sent — it poisoned a prior teardown test.

    static final int CLUSTER_LAYER_STACK = 1;
    private static final int CLUSTER_DISPLAY_ID = 1;

    // ── Timing (UCM-tunable via surveillance.*) ─────────────────────────────────
    // The open sequence has TWO inter-step gaps with DIFFERENT requirements:
    //   • size→16 (sizeStepMs): the size-profile (31) just configures geometry, it
    //     settles fast — a short gap suffices.
    //   • 16→35 (openStepDelayMs): fullscreen-on (16) must actually establish the
    //     projection surface + wire it to the panel before Di4-mode (35) materializes
    //     the VirtualDisplay. ON-DEVICE PROVEN: 1000ms works (video shows, ~3s load);
    //     <=500ms regresses to NO VISIBLE VIDEO (display materializes + SF composites
    //     our layer, but Qt hasn't wired the fission surface to the panel yet).
    // Both UCM-tunable: surveillance.clusterSizeStepMs / surveillance.clusterOpenStepMs.
    private long sizeStepMs = DEFAULT_SIZE_STEP_MS;
    private long openStepDelayMs = DEFAULT_OPEN_STEP_DELAY_MS;
    // Both default 1000ms (the proven-working value). 200ms for the size step was
    // tried but suspected too short (the size profile may need to settle before 16);
    // keeping them equal at 1s avoids that risk. Total open ≈ 2×1s + settle ≈ 3s.
    private static final long DEFAULT_SIZE_STEP_MS = 1000;
    private static final long DEFAULT_OPEN_STEP_DELAY_MS = 1000;
    private static final long CLOSE_STEP_DELAY_MS = 1000;  // between 18→0
    private static final long READY_POLL_MS = 120;
    private static final long READY_TIMEOUT_MS = 8000;
    // The cluster VirtualDisplay materializes ~280ms after OP_DI4_MODE. We poll
    // DisplayManager as a fast early-exit, but the daemon's app_process Context has
    // a DisplayManagerGlobal cache that does NOT receive add-callbacks for the
    // foreign (uid-1000) fission display, so getDisplay(1) can return null forever
    // even though the display exists (confirmed on-device: dumpsys shows it, the
    // daemon's getDisplay(1) does not). So after this settle window we mark ready
    // UNCONDITIONALLY rather than timing out + force-closing (the latter caused an
    // open→timeout→close→reopen thrash that kept re-asserting the size-profile
    // opcode and reflowing the head-unit). Compositing onto layerStack 1 is the
    // proven path and is harmless even in the impossible case the display is absent
    // (the layer is simply invisible, never a crash).
    private static final long READY_SETTLE_MS = 900;   // past the ~280ms materialize + margin
    private static final long DEFAULT_LINGER_CLOSE_MS = 8000;
    private static final long DEFAULT_MAX_PROJECTION_MS = 90000;

    // ── Bounded present-edge re-notify (cold-open show-retry) ────────────────────
    // commitReady declares ready at READY_SETTLE_MS even when the fission display is
    // NOT yet present ("Committing anyway" — see pollReady) to avoid open/close
    // thrash. On a COLD (first-after-idle) open the fission VirtualDisplay actually
    // materializes ~1-3.5s AFTER OP_DI4_MODE — i.e. AFTER our ready edge. The
    // pipeline's clusterShowWhenReady() re-resolves the cluster layerStack via
    // dumpsys and, finding it UNRESOLVED, defers the show. The ONLY existing retry is
    // bsTurnTick's 250ms loop while the turn signal is PHYSICALLY ON; once the signal
    // clears the card is never re-driven, so on a cold open the BS card (and the
    // ClusterSpeedOverlay, which shares the resolver) never appear even though the
    // gauges stay blanked for the whole linger.
    //
    // Fix: when commitReady commits WITHOUT the display being present, keep polling
    // genuine display presence on projThread (the same thread that owns dumpsys, so
    // the 250ms BS loop and the GL thread are never blocked) for a bounded window.
    // On the present-edge — and for a few re-drives after it — re-call
    // notifyPipelineReady() so onClusterProjectionReady() re-runs clusterShowWhenReady
    // with the now-resolvable stack. Signal-INDEPENDENT (driven by projThread, not the
    // turn loop) so the card lands even if the signal already cleared and the
    // projection is merely lingering. Every poll re-checks the supersession epoch +
    // shuttingDown + ST_OPEN, so it can never re-drive a superseded/closed projection.
    private static final long PRESENT_POLL_MS = 250;     // re-resolve cadence (matches BS loop feel)
    private static final long PRESENT_POLL_WINDOW_MS = 4000;  // covers the worst cold materialize (~3.5s)
    private static final int  PRESENT_REDRIVE_TICKS = 2;  // extra re-drives after the present-edge
    private static final long DEPENDENT_DETACH_RETRY_MS = 250L;

    // UCM gate flags (under "surveillance") for SIGKILL recovery — mirror the
    // screenDeterrent* pattern so a respawned daemon can restore the gauges.
    private static final String GATE_FORCE_STOP = "clusterProjectionForceStop";
    private static final String GATE_ACTIVE_UNTIL = "clusterProjectionActiveUntilMs";
    private static final String GATE_COMMAND_INDETERMINATE =
            "clusterProjectionCommandIndeterminate";
    private static final String GATE_COMMAND_BOOT_ID =
            "clusterProjectionCommandBootId";

    private static final int ST_CLOSED  = 0;
    private static final int ST_OPENING = 1;
    private static final int ST_OPEN    = 2;
    private static final int ST_CLOSING = 3;

    private static volatile ClusterProjectionController instance;
    private static final Object BOOT_RESTORE_LOCK = new Object();
    private static final Object LEGACY_COMMAND_DISPATCH_LOCK = new Object();
    private static volatile BootRestoreTicket sBootRestoreTicket;
    private static final java.util.concurrent.atomic.AtomicInteger sRestoreDispatches =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean sLegacyBootRestoreBlocksAdmission;
    private static final long BOOT_RESTORE_WAIT_TIMEOUT_MS = 20_000L;
    private static final int BOOT_RESTORE_MAX_ATTEMPTS = 3;
    private static final long[] BOOT_RESTORE_RETRY_DELAYS_MS =
            new long[] {500L, 1_500L};
    // DI3 sendInfo plus its durable marker writes routinely takes 2-6 s; only a
    // wedged service may reach the indeterminate path.
    private static final long LEGACY_COMMAND_WAIT_TIMEOUT_MS = 20_000L;

    private static final class BootRestoreTicket {
        final CountDownLatch completion = new CountDownLatch(1);
        volatile LegacyBootRecoveryResult result =
                LegacyBootRecoveryResult.RETRYABLE_FAILURE;
    }

    enum LegacyCommandResult {
        ACCEPTED,
        REJECTED,
        INDETERMINATE,
        /**
         * No AutoContainer endpoint answered the legacy transport: the direct
         * Binder path had no service/stub AND {@code service call} reported
         * the service as absent. Definitive (nothing was dispatched), but
         * distinct from {@link #REJECTED} so recovery can tell "no compositor
         * service on this firmware" from "the service refused the opcode".
         */
        UNAVAILABLE
    }

    public enum LegacyBootRecoveryResult {
        RECOVERED,
        RETRYABLE_FAILURE,
        SAME_BOOT_INDETERMINATE,
        /**
         * Terminal, non-recovered: the OEM service answered every restore
         * attempt with a definitive rejection of the legacy protocol (or the
         * legacy endpoint is absent while a DI5-only alias exists). Nothing a
         * retry can change on this firmware. The durable marker is retained
         * as proof, but projection admission is released so the fence does
         * not outlive the recovery it protected.
         */
        UNSUPPORTED;

        public boolean isRecovered() {
            return this == RECOVERED;
        }

        public boolean isSameBootIndeterminate() {
            return this == SAME_BOOT_INDETERMINATE;
        }

        /** Terminal verdicts end the bounded retry ladder. */
        public boolean isTerminal() {
            return this == RECOVERED || this == UNSUPPORTED;
        }

        public boolean isUnsupported() {
            return this == UNSUPPORTED;
        }

        /**
         * Only an unresolved same-boot command keeps admission blocked after a
         * restore pass has FINISHED: a command that may still land must never
         * be reordered against a fresh open. Every other outcome either
         * restored the gauges or cannot be improved by refusing new sessions
         * (a later successful close performs the same 18→0→profile restore).
         */
        public boolean blocksAdmissionAfterRestore() {
            return this == SAME_BOOT_INDETERMINATE;
        }
    }

    // ── Legacy protocol verdict (diagnostics for HTTP/UI, never load-bearing) ──
    public static final String LEGACY_VERDICT_NONE = "";
    public static final String LEGACY_VERDICT_RECOVERED = "recovered";
    public static final String LEGACY_VERDICT_UNAVAILABLE = "unavailable";
    public static final String LEGACY_VERDICT_UNSUPPORTED = "unsupported";
    public static final String LEGACY_VERDICT_INDETERMINATE = "indeterminate";
    public static final String LEGACY_VERDICT_RETRYABLE = "retryable";
    private static volatile String sLegacyVerdict = LEGACY_VERDICT_NONE;
    private static volatile String sLegacyVerdictDetail = "";
    private static volatile String sLastLegacyShellOutput = "";

    /** Last legacy-protocol verdict ({@code LEGACY_VERDICT_*}), for status/UI. */
    public static String legacyProtocolVerdict() {
        return sLegacyVerdict;
    }

    /** Human-readable detail for {@link #legacyProtocolVerdict()}. */
    public static String legacyProtocolVerdictDetail() {
        return sLegacyVerdictDetail;
    }

    private static void recordLegacyVerdict(String verdict, String detail) {
        sLegacyVerdict = verdict == null ? LEGACY_VERDICT_NONE : verdict;
        sLegacyVerdictDetail = detail == null ? "" : detail;
    }

    private static String compactShellOutput(String output) {
        if (output == null) return "<empty>";
        String compact = output.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) return "<empty>";
        return compact.length() > 240
                ? compact.substring(0, 240) + "..."
                : compact;
    }

    private enum LegacyTransportResult {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE,
        INDETERMINATE
    }

    private enum LegacyCommandMarkerResult {
        MARKED,
        FAILED_BEFORE_DISPATCH,
        SAME_BOOT_INDETERMINATE
    }

    private static final class LegacyCommandAttempt {
        final CountDownLatch completion = new CountDownLatch(1);
        volatile LegacyTransportResult result =
                LegacyTransportResult.INDETERMINATE;
    }

    static final class LegacyGateSnapshot {
        final boolean readable;
        final boolean forceStop;
        final long activeUntilMs;
        final boolean commandIndeterminate;
        final String commandBootId;

        LegacyGateSnapshot(
                boolean readable,
                boolean forceStop,
                long activeUntilMs,
                boolean commandIndeterminate,
                String commandBootId) {
            this.readable = readable;
            this.forceStop = forceStop;
            this.activeUntilMs = activeUntilMs;
            this.commandIndeterminate = commandIndeterminate;
            this.commandBootId = commandBootId == null ? "" : commandBootId;
        }

        boolean hasRecoveryOwnership() {
            return forceStop
                    || activeUntilMs > 0L
                    || commandIndeterminate;
        }
    }

    private final android.os.HandlerThread projThread;
    private final android.os.Handler projHandler;     // opcode sends + sleeps + ready-poll
    private final android.os.Handler watchdogHandler; // linger + max-cap timers

    private volatile int projState = ST_CLOSED;
    private volatile boolean ready = false;
    private volatile long openedAtMs = 0;
    private volatile long lastSignalMs = 0;
    // noteSignal() only (the blind-spot turn loop); sustained re-acquires never refresh it.
    private volatile long lastTurnSignalMs = 0;
    // Sequence epoch: bumped (under the synchronized state-transition block) every
    // time an open or close sequence is started. Each posted opcode-step lambda
    // captures the epoch at post time and bails if it no longer matches — so a
    // superseded sequence (e.g. a close that started, then a re-open flipped the
    // state) can never land a trailing opcode (16/35 or 0) on the wire after the
    // flip. Without this, rapid signal/target toggling could interleave open/close
    // opcodes and strand the projection physically OPEN while projState==CLOSED
    // (disarming every restore path → gauges blanked with no recovery).
    private volatile int seqEpoch = 0;
    // Terminal latch: set once at shutdown() and NEVER cleared. The epoch guard only
    // invalidates already-posted steps of a SUPERSEDED sequence — it cannot stop a
    // brand-new requestOpen() (which is itself the newest epoch) from starting a
    // fresh open AFTER shutdown's restore already ran and cleared the recovery flags.
    // The BS turn loop keeps ticking during daemon teardown (it's stopped later, in
    // gpuPipeline.stop()), so without this a turn signal could re-enter projection
    // post-restore with boot-recovery disarmed. Once true, requestOpen is a no-op.
    private volatile boolean shuttingDown = false;

    private long lingerCloseMs = DEFAULT_LINGER_CLOSE_MS;
    private long maxProjectionMs = DEFAULT_MAX_PROJECTION_MS;
    // Size-profile opcode sent before 16→35. Selects the cluster projection size:
    // 29=8.8", 30=12.3", 31=10.25" native. The WRONG value gives a wrong-aspect /
    // stretched cluster projection. UCM-tunable (surveillance.clusterSizeProfile) so
    // the correct profile for this cluster can be dialled in live without a rebuild;
    // 0 = skip the size-profile step entirely (just 16→35).
    private int sizeProfileOpcode = OP_SIZE_PROFILE;   // default 31=10.25" (OP_SIZE_PROFILE)
    // Optional OVERRIDE for the on-close restore profile. Normally close re-sends
    // sizeProfileOpcode (the user's "Cluster layout" choice = the car's native size),
    // which automatically returns the gauges to the model's original layout. This
    // override is only for the rare case where the PROJECTION profile must differ
    // from the car's native gauge profile; 0 = use sizeProfileOpcode (the default).
    private int restoreProfileOpcode = 0;

    // When the 90s max cap fires, lock out re-open until the indicator GENUINELY
    // clears (a sustained/forgotten signal would otherwise re-open on the very next
    // 250ms tick — ST_CLOSING falls through requestOpen's guard — defeating the cap
    // and flashing the gauges every 90s instead of restoring them). Cleared on the
    // first indicator-off tick via notifySignalCleared().
    private volatile boolean maxCapLockout = false;
    private final Runnable maxCapTask = () -> { maxCapLockout = true; forceClose("max-cap"); };
    private final Runnable lingerTask = this::maybeLingerClose;

    // ── Sustained holder (map-on-cluster) ───────────────────────────────────────
    // The projection has TWO kinds of consumer:
    //   • TRANSIENT (blind-spot turn-signal): open on signal, linger-close after 8s,
    //     hard max-cap at 90s. This is the original, unchanged behaviour.
    //   • SUSTAINED (the nav map projected onto the cluster): held OPEN for the whole
    //     session until the user/ACC explicitly ends it. While a sustained holder is
    //     active, the two AUTO-close paths (linger + max-cap) are suppressed so the
    //     map doesn't get torn down mid-drive — but EVERY explicit/safety restore
    //     path (forceClose on ACC-off/disable, shutdown on SIGTERM, clearStaleGateAtBoot
    //     on SIGKILL) is UNCHANGED and still fires regardless of holders. The sustained
    //     flag is cleared by releaseSustained() (which then closes if no transient
    //     consumer wants it) and unconditionally by forceClose/shutdown.
    //
    // OWNERSHIP: there can be MORE THAN ONE sustained consumer (the nav map AND the
    // camera-view feature). A single boolean would let one consumer's release close
    // the other's projection, and a stuck flag would disarm the transient auto-close.
    // So hold a TOKEN SET: sustainedHeld() is true iff ANY holder remains. acquire adds
    // a token, release removes ITS token and only closes when the set is empty. All
    // internal gates read sustainedHeld() (derived). Concurrent (foreign threads call
    // acquire/release) → synchronized set.
    private final java.util.Set<String> sustainedHolders =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private boolean sustainedHeld() { return !sustainedHolders.isEmpty(); }

    private ClusterProjectionController() {
        projThread = new android.os.HandlerThread("ClusterProjection");
        projThread.start();
        projHandler = new android.os.Handler(projThread.getLooper());
        watchdogHandler = new android.os.Handler(projThread.getLooper());
    }

    public static ClusterProjectionController getInstance() {
        if (instance == null) {
            synchronized (ClusterProjectionController.class) {
                if (instance == null) instance = new ClusterProjectionController();
            }
        }
        return instance;
    }

    /** Shutdown-hook helper that does NOT construct the singleton. If instance is
     *  null, this daemon never opened a projection (the HandlerThread was never
     *  spawned), so there is nothing to restore — skip entirely. Avoids spawning
     *  the ClusterProjection thread on a head-unit-only daemon's exit. The
     *  defensive UCM flag-clear in the shutdown hook + boot-time
     *  clearStaleGateAtBoot still cover any leaked-flag case independently. */
    public static void shutdownIfActive() {
        ClusterProjectionController i = instance;
        if (i != null) i.shutdown();
    }

    /**
     * ACC-off edge helper (called from {@code AccMonitor.notifyAccEdge}) that
     * force-closes the projection + restores the gauges WITHOUT constructing the
     * singleton. If {@code instance} is null this daemon never opened a projection
     * (the HandlerThread was never spawned, target was head-unit), so there is
     * nothing blanked to restore — skip entirely. Mirrors {@link #shutdownIfActive}.
     *
     * <p>This closes BOTH consumer kinds: {@link #forceClose} drops the sustained
     * map hold AND tears down a transient blind-spot projection. It is the path
     * that makes the gauge-restore on ACC-off IMMEDIATE for the transient case —
     * without it, a turn signal held ON at the instant of ACC-off would leave the
     * gauges blanked until the 8s linger / 90s max-cap fired. (Not terminal like
     * {@link #shutdown}: a later ACC-on can legitimately re-open.)
     */
    public static void forceCloseIfActive(String reason) {
        ClusterProjectionController i = instance;
        if (i != null) i.forceClose(reason);
    }

    // ── Public API (called from the BS turn loop / pipeline) ────────────────────

    /** Cheap volatile reads for the show-gate in the pipeline. */
    public boolean isOpen()  {
        return !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && projState == ST_OPEN;
    }
    public boolean isReady() {
        return !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && ready && projState == ST_OPEN;
    }

    /** True when the calling thread IS projThread (the dumpsys-owning thread). The
     *  pipeline's clusterShowWhenReady uses this to keep its layerStack-resolving
     *  dumpsys ON projThread (I9): when called from the 250ms BsTurnTrigger loop it
     *  must NOT spawn dumpsys inline — it defers to {@link #requestShowRedrive()}. */
    public boolean isOnProjThread() {
        return android.os.Looper.myLooper() == projHandler.getLooper();
    }

    /** Re-drive the pipeline show on projThread (I9-safe). Posts a re-notify so
     *  {@code onClusterProjectionReady → clusterShowWhenReady} re-runs on projThread,
     *  where the layerStack-resolving dumpsys is legal. Called from the BsTurnTrigger
     *  loop when the BS card needs (re)showing but the stack must be resolved off that
     *  thread. Re-checks ST_OPEN at exec time (under the epoch the re-notify carries
     *  nothing — notifyPipelineReady itself is a no-op when the pipeline declines), so
     *  a close that lands between the post and exec simply hides the layer instead.
     *  Cheap: one post; the dumpsys runs at most once per posted re-drive on projThread.
     *  No-op if the projection isn't open/ready. */
    public void requestShowRedrive() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) return;
        if (shuttingDown) return;
        if (projState != ST_OPEN) return;   // not open → nothing to re-drive
        projHandler.post(() -> {
            if (shuttingDown) return;
            if (projState != ST_OPEN) return;   // closed underneath us
            notifyPipelineReady();              // re-runs onClusterProjectionReady on projThread
        });
    }

    /**
     * Current open/close-sequence epoch (the same {@link #seqEpoch} that every
     * opcode-step lambda + the present-edge poll capture-and-compare). Exposed so the
     * speed-overlay {@code start()} carrying the epoch of the open it was armed for can
     * decline on the exec thread when a close/re-open has SUPERSEDED that open.
     *
     * <p>This closes the {@code startOnExec} show-after-close window that the bare
     * {@code projectionOpen()} ({@code projState==ST_OPEN}) re-check cannot: forceClose
     * bumps this epoch in the SAME synchronized block as {@code projState=ST_CLOSING}
     * (the documented supersession marker), so a {@code start()} carrying the open-time
     * epoch deterministically loses to the close — whereas a stale-visible volatile
     * {@code projState} read could still see {@code ST_OPEN} and arm the badge after the
     * projection began closing. A plain cheap volatile read; not a reflected entrypoint. */
    int currentSeqEpoch() { return seqEpoch; }

    public static boolean isLegacyProjectionAdmissionBlocked() {
        return sLegacyBootRestoreBlocksAdmission
                || DiLink5ClusterCast
                        .isLegacyProjectionBlockedByDiLink5Recovery();
    }

    /**
     * True only when {@code surveillance} positively records an unresolved legacy command from
     * this kernel boot. Unreadable markers and unknown boot ids answer false.
     */
    public static boolean isLegacyCommandUnresolvedThisBoot(
            org.json.JSONObject surveillance) {
        try {
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(surveillance);
            if (!marker.readable || !marker.commandIndeterminate) return false;
            String origin = canonicalBootIdOrNull(marker.commandBootId);
            return origin != null && origin.equals(readCurrentBootId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String describeReflectedCause(Throwable failure) {
        try {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException
                    ? failure.getCause() : null;
            return cause == null ? ""
                    : " (cause " + cause.getClass().getName() + ": " + cause.getMessage() + ")";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** True while a close sequence or gauge restore is still running and will settle admission. */
    public static boolean isLegacyRestoreInFlight() {
        ClusterProjectionController i = instance;
        return (i != null && i.projState == ST_CLOSING)
                || sRestoreDispatches.get() > 0
                || sBootRestoreTicket != null
                || DiLink5ClusterCast.isRecovering();
    }

    /**
     * Request the projection be open (idempotent). Called from the cluster branch
     * of the BS turn loop on signal-on. Cheap from the 250 ms loop: a volatile
     * read + at most one post. Also refreshes the linger timer.
     */
    public boolean requestOpen() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return false;
        }
        if (isLegacyProjectionAdmissionBlocked()) {
            return false;
        }
        // Terminal: once the daemon is shutting down, NEVER (re)enter projection —
        // shutdown() runs the authoritative restore and clears the recovery flags,
        // and a re-open here would undo it with boot-recovery disarmed.
        if (shuttingDown) return false;
        lastSignalMs = System.currentTimeMillis();
        // After a max-cap close, refuse to re-open until the indicator has cycled
        // off at least once (notifySignalCleared). A normal blink reaches off
        // between flashes long before 90s, so this only bites a truly stuck signal.
        if (maxCapLockout) return false;
        int st = projState;
        if (st == ST_OPEN || st == ST_OPENING) return true;
        final int epoch;
        synchronized (this) {
            if (shuttingDown) return false;
            if (isLegacyProjectionAdmissionBlocked()) {
                return false;
            }
            if (projState == ST_OPEN || projState == ST_OPENING) {
                return true;
            }
            // Only re-open from a FULLY-CLOSED state. Admitting ST_CLOSING here let a
            // racing reopen (BS turn-trigger / nav-map thread) supersede a safety
            // forceClose (retarget/disable/relayout) mid-close — bumping seqEpoch so
            // the close's OP_REFRESH(0) gauge-restore step bailed on the epoch guard,
            // leaving the gauges blanked until the linger backstop self-healed (~8s).
            // Requiring ST_CLOSED means a still-active turn signal simply re-opens on
            // the next 250ms tick AFTER the close genuinely reaches ST_CLOSED — the
            // restore always completes first. Strengthens the gauge-restore net.
            if (projState != ST_CLOSED) return false;
            projState = ST_OPENING;
            ready = false;
            epoch = ++seqEpoch;   // supersede any in-flight close sequence
        }
        loadTuning();
        projHandler.post(() -> doOpenSequence(epoch));
        return true;
    }

    /**
     * Acquire the projection as a SUSTAINED holder (the nav map). Opens it if not
     * already open (reusing the same open sequence as the transient path) and marks
     * it held so the linger + max-cap AUTO-close paths are suppressed for the
     * duration. Idempotent. Safety restores (forceClose/shutdown/boot) are unaffected
     * and will still tear it down + restore the gauges. No-op while shutting down.
     */
    /** Back-compat: the nav map's sustained hold (token "map"). */
    public boolean acquireSustained() {
        return acquireSustained("map");
    }

    /**
     * Acquire the projection as a SUSTAINED holder identified by {@code token} (e.g.
     * "map" for the nav map, "camview" for the camera-view feature). Multiple distinct
     * tokens can hold concurrently; the auto-close paths are suppressed while ANY token
     * is held. Re-acquiring the same token is idempotent. See {@link #releaseSustained(String)}.
     */
    public boolean acquireSustained(String token) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            logger.warn("legacy cluster projection is unavailable on the DI5 "
                    + "cluster transport");
            return false;
        }
        if (isLegacyProjectionAdmissionBlocked()) {
            logger.warn("legacy cluster projection is blocked by unresolved "
                    + "cluster recovery ownership");
            return false;
        }
        String holderToken = token != null ? token : "default";
        synchronized (this) {
            // A hard close must win over a concurrent cast/map start. Adding a holder while
            // ST_CLOSING would strand the token because requestOpen intentionally refuses to
            // supersede the gauge-restore sequence.
            if (shuttingDown ||
                    projState == ST_CLOSING ||
                    isLegacyProjectionAdmissionBlocked()) {
                return false;
            }
            sustainedHolders.add(holderToken);
        }
        if (!requestOpen()) {
            sustainedHolders.remove(holderToken);
            return false;
        }
        // Cancel any pending auto-close left over from a prior transient session.
        watchdogHandler.removeCallbacks(lingerTask);
        watchdogHandler.removeCallbacks(maxCapTask);
        maxCapLockout = false;
        // RACE FIX ("projection ends on its own" at exactly 90s): acquireSustained
        // runs on a FOREIGN thread (ClusterMapLaunch / IPC / HTTP), but commitReady
        // — which posts the 90s maxCapTask gated on `!sustainedHeld` — runs on
        // projThread. If a TRANSIENT blind-spot open is in flight (ST_OPENING) when
        // the map acquires, commitReady's sustainedHeld read can land BEFORE this
        // method sets it true, while this removeCallbacks(maxCapTask) lands BEFORE
        // commitReady's postDelayed — so the cap is posted on a now-sustained map
        // and never removed, force-closing it at 90s. The removeCallbacks above
        // cannot win that interleave (different threads, no happens-before). Re-issue
        // the removal ON projThread so it serialises in-order with commitReady's post
        // (same looper) and cannot land in its read→post gap. Cheap idempotent no-op
        // when no cap is pending. (commitReady additionally re-checks sustainedHeld
        // after its post — belt and braces — but the in-order removal is the real fix.)
        projHandler.post(() -> {
            if (sustainedHeld()) {
                watchdogHandler.removeCallbacks(maxCapTask);
                watchdogHandler.removeCallbacks(lingerTask);
            }
        });
        return true;
    }

    /** Back-compat: release the nav map's sustained hold (token "map"). */
    public void releaseSustained() { releaseSustained("map"); }

    /**
     * Release the sustained hold for {@code token}. If OTHER sustained holders remain
     * (e.g. releasing camera-view while the map still holds), the projection stays
     * open and nothing else changes. Only when the LAST sustained holder releases do
     * we decide: if a transient consumer (blind-spot turn signal) still wants it
     * (fresh within the linger window) it stays up and reverts to the normal linger
     * lifecycle; otherwise it force-closes + restores the gauges.
     */
    public void releaseSustained(String token) {
        releaseSustained(token, false);
    }

    /** Cast stop / preview detach: lingers only for a genuine turn signal, not a re-acquire. */
    public void releaseSustainedUnlessSignalled(String token) {
        releaseSustained(token, true);
    }

    private void releaseSustained(String token, boolean turnSignalOnly) {
        sustainedHolders.remove(token != null ? token : "default");
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            // DI5 direct projection has its own owner/lifecycle. A release from a
            // legacy blind-spot/camera cleanup must never turn into a stray 18→0
            // against the active shared-display session.
            return;
        }
        if (sustainedHeld()) {
            // Another sustained consumer still needs the projection — leave it open.
            return;
        }
        // Last sustained holder gone. If a turn signal is currently active (fresh
        // within the linger window) keep it up and hand back to the transient
        // lifecycle; else close now.
        long sinceSignal = System.currentTimeMillis()
                - (turnSignalOnly ? lastTurnSignalMs : lastSignalMs);
        if (sinceSignal < lingerCloseMs) {
            requestCloseLingered();   // transient takes over; closes after linger
        } else {
            forceClose("sustained-release");
        }
    }

    /**
     * Null-safe static "no legacy projection is physically live" check. Used by
     * the cluster-transport settings endpoint to decide whether a transport
     * change may be applied live: covers TRANSIENT blind-spot opens too, which
     * hold no sustained token. Never constructs the singleton.
     */
    public static boolean isProjectionIdleStatic() {
        ClusterProjectionController i = instance;
        return i == null || i.projState == ST_CLOSED;
    }

    /** Snapshot for optional overlay work; never constructs the projection singleton. */
    public static ClusterAdasLifecycle.Source clusterAdasState() {
        ClusterProjectionController i = instance;
        boolean recovering = sLegacyBootRestoreBlocksAdmission
                || sRestoreDispatches.get() > 0 || sBootRestoreTicket != null;
        if (i == null) return new ClusterAdasLifecycle.Source(0L, false, recovering);
        synchronized (i) {
            return new ClusterAdasLifecycle.Source(i.seqEpoch, i.projState == ST_OPEN,
                    i.projState != ST_CLOSED || recovering);
        }
    }

    /** True while ANY consumer holds the projection sustained (gates BS coexistence). */
    public boolean isSustainedHeld() {
        return !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && sustainedHeld();
    }

    /** Null-safe static read of {@link #isSustainedHeld()} that does NOT construct the
     *  singleton (mirrors {@link #forceCloseIfActive}/{@link #shutdownIfActive}). If
     *  {@code instance} is null this daemon never opened a projection, so nothing is held.
     *  Used by the {@code ClusterMapProjector} keep-alive watchdog to confirm the
     *  projection is still held FOR THE MAP before relaunching the Activity — so a
     *  projection torn down via a direct {@link #forceClose} (bs-disable / relayout /
     *  retarget / ACC-off) is never repainted over the restored gauges. */
    public static boolean isSustainedHeldStatic() {
        ClusterProjectionController i = instance;
        return !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && i != null && i.sustainedHeld();
    }

    /** Null-safe static check of whether a SPECIFIC {@code token} still holds the
     *  projection (mirrors {@link #isSustainedHeldStatic()} but scoped to one holder).
     *  Returns false if this daemon never opened a projection. Used by {@link
     *  com.overdrive.app.launcher.ClusterCast} to reconcile its own active flag when a
     *  direct {@link #forceClose} (relayout / bs-disable / retarget / ACC-off) cleared
     *  ALL holders — so a torn-down cast reports inactive without a keep-alive loop. */
    public static boolean holdsTokenStatic(String token) {
        ClusterProjectionController i = instance;
        return !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && i != null && token != null && i.sustainedHolders.contains(token);
    }

    /** Bump the signal timestamp (called every tick while a turn signal is active). */
    public void noteSignal() {
        long now = System.currentTimeMillis();
        lastSignalMs = now;
        lastTurnSignalMs = now;
    }

    /** Called on the first indicator-off tick. Lifts a max-cap lockout so a fresh
     *  indicator after the cap can re-open normally. No-op if not locked out. */
    public void notifySignalCleared() { maxCapLockout = false; }

    /**
     * Schedule a lingered close: after {@link #lingerCloseMs} of no fresh signal,
     * restore the gauges. Re-arming is cheap (removeCallbacks + postDelayed).
     */
    public void requestCloseLingered() {
        watchdogHandler.removeCallbacks(lingerTask);
        watchdogHandler.postDelayed(lingerTask, lingerCloseMs);
    }

    private void maybeLingerClose() {
        // Any sustained holder (map / camera-view) keeps the projection open — never linger-close.
        if (sustainedHeld()) return;
        long since = System.currentTimeMillis() - lastSignalMs;
        if (since >= lingerCloseMs - 50) {
            forceClose("linger");
        } else {
            // Signal came back inside the window — re-arm for the remainder.
            watchdogHandler.postDelayed(lingerTask, lingerCloseMs - since);
        }
    }

    /**
     * Hard close + gauge restore. Idempotent and harmless when already closed.
     * Marks the projection non-open first, synchronously detaches every dependent mirror,
     * then retains the durable recovery marker until every restore opcode has succeeded.
     * Public so disable / disarm / target-flip / errors can all force the gauges back.
     */
    public void forceClose(String reason) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && projState == ST_CLOSED) {
            // The legacy controller owns no physical projection. Retire only its
            // bookkeeping; do not detach the DI5 mirror and do not emit 18→0.
            // Preserve any durable legacy marker: DI5 boot recovery consumes it
            // only after the shared DI5 compositor has been retired safely.
            sustainedHolders.clear();
            watchdogHandler.removeCallbacks(maxCapTask);
            watchdogHandler.removeCallbacks(lingerTask);
            return;
        }
        final int epoch;
        synchronized (this) {
            if (projState == ST_CLOSED) {
                epoch = -1;
            } else {
                projState = ST_CLOSING;
                ready = false;
                epoch = ++seqEpoch;   // supersede any in-flight open sequence
            }
        }

        // Retire the Android Auto video layer before the fission display can
        // disappear. This callback must not release a token: forceClose owns the
        // gauge-restore sequence and clears all sustained holders itself.
        try { com.overdrive.app.navmap.AndroidAutoClusterBridge
                .onProjectionForceClosed(reason); }
        catch (Throwable t) {
            logger.warn("Android Auto cluster pre-close cleanup failed: " + t.getMessage());
        }

        // A direct controller close must invalidate a pending map launch before
        // the fission display can disappear. Several safety paths do not route
        // through ClusterMapProjector.stop().
        try { com.overdrive.app.navmap.ClusterMapProjector
                .onLegacyProjectionForceClosed(); }
        catch (Throwable t) {
            logger.warn("map pre-close invalidation failed: " + t.getMessage());
        }

        // The app-view mirror owns a second SF display that reads the fission layer stack.
        // Unbind/destroy it synchronously before the close sequence can destroy that source.
        // This also closes hard-error paths, not only the explicit ACC-off/relayout callers.
        boolean dependentsDetached =
                detachProjectionDependents(reason);

        // An explicit/safety close always drops ALL sustained holds. Clear after the mirror
        // detach so an attach already in flight cannot leave its viewmirror lease stranded.
        sustainedHolders.clear();
        watchdogHandler.removeCallbacks(maxCapTask);
        watchdogHandler.removeCallbacks(lingerTask);
        if (epoch < 0) return;
        sLegacyBootRestoreBlocksAdmission = true;
        if (!dependentsDetached) {
            logger.warn("projection source close deferred until every mirror "
                    + "consumer confirms detach");
            projHandler.postDelayed(
                    () -> continueForceCloseAfterDependentDetach(
                            epoch, reason),
                    DEPENDENT_DETACH_RETRY_MS);
            return;
        }
        completeForceCloseAfterDependentDetach(epoch, reason);
    }

    private boolean detachProjectionDependents(String reason) {
        boolean viewDetached = false;
        boolean clusterDetached = false;
        try {
            viewDetached =
                    ClusterViewMirrorService
                            .detachBeforeProjectionClose(reason);
        } catch (Throwable t) {
            logger.warn("view mirror pre-close detach failed: "
                    + t.getMessage());
        }
        try {
            clusterDetached =
                    ClusterMirrorController
                            .detachBeforeProjectionClose(reason);
        } catch (Throwable t) {
            logger.warn("cluster mirror pre-close detach failed: "
                    + t.getMessage());
        }
        return viewDetached && clusterDetached;
    }

    private void continueForceCloseAfterDependentDetach(
            int epoch, String reason) {
        if (!isCurrentCloseEpoch(epoch)) return;
        if (!detachProjectionDependents(reason)) {
            projHandler.postDelayed(
                    () -> continueForceCloseAfterDependentDetach(
                            epoch, reason),
                    DEPENDENT_DETACH_RETRY_MS);
            return;
        }
        completeForceCloseAfterDependentDetach(epoch, reason);
    }

    private void completeForceCloseAfterDependentDetach(
            int epoch, String reason) {
        synchronized (this) {
            // A concurrent forceClose may have superseded this one while the synchronous
            // mirror teardown was running. Its newer epoch owns the physical close.
            if (seqEpoch != epoch || projState != ST_CLOSING) return;
            projState = ST_CLOSING;
        }
        // Retire the speed badge AFTER projState is ST_CLOSING (every close path —
        // linger, max-cap, ACC-off, disable, error, retarget — funnels through here).
        // Posting the stop only once projState != ST_OPEN means a racing commitReady's
        // startOnExec re-check (projectionOpen()) deterministically fails, so the badge
        // can't be armed after this stop — no reliance on the per-tick self-heal.
        try { ClusterSpeedOverlay.stopIfActive(); } catch (Throwable ignored) {}
        logger.info("forceClose(" + reason + ")");
        // Tell the pipeline to hide the BS layer + drop the render gate NOW (every
        // close path goes through here: linger, max-cap, disarm, retarget). Without
        // this, PASS 1C keeps drawing into the layer after the projection's display
        // is gone → GPU pinned high after the turn signal stops.
        notifyPipelineClosed();
        projHandler.post(() -> doCloseSequence(epoch));
    }

    // ── Open / close sequences (run on projThread) ──────────────────────────────

    private void doOpenSequence(int epoch) {
        try {
            if (shuttingDown) return;        // terminal — never issue open opcodes during teardown
            if (epoch != seqEpoch) return;   // superseded before we started
            if (!writeGateFlags(
                    System.currentTimeMillis() + maxProjectionMs)) {
                logger.error("projection open refused: durable recovery "
                        + "marker could not be committed");
                forceClose("open-marker-failed");
                return;
            }
            synchronized (this) {
                if (epoch != seqEpoch
                        || projState != ST_OPENING
                        || shuttingDown) {
                    // A concurrent close owns the durable marker and will
                    // consume it only after its restore is confirmed.
                    return;
                }
            }
            final int sizeOp = sizeProfileOpcode;
            logger.info("projection open: " + (sizeOp != 0 ? sizeOp + "→" : "") + OP_FULLSCREEN_ON + "→" + OP_DI4_MODE);
            // Size-profile step is optional (0 = skip). Wrong profile = wrong cluster aspect.
            if (sizeOp != 0
                    && !sendOpenInfo(
                            sizeOp, epoch, "open-size-failed")) {
                return;
            }
            // First gap: short — size profile settles fast (0 if we skipped it).
            final long firstGap = (sizeOp != 0) ? sizeStepMs : 0;
            // Second gap: the critical one — 16 must establish the surface before 35.
            final long openGap = openStepDelayMs;
            projHandler.postDelayed(() -> {
                if (epoch != seqEpoch) return;   // a close/re-open superseded us
                if (!sendOpenInfo(
                        OP_FULLSCREEN_ON,
                        epoch,
                        "open-fullscreen-failed")) {
                    return;
                }
                projHandler.postDelayed(() -> {
                    if (epoch != seqEpoch) return;
                    if (!sendOpenInfo(
                            OP_DI4_MODE,
                            epoch,
                            "open-di4-failed")) {
                        return;
                    }
                    pollReady(0, epoch);
                }, openGap);
            }, firstGap);
        } catch (Throwable t) {
            logger.warn("doOpenSequence error: " + t.getMessage());
            forceClose("open-exception");
        }
    }

    private boolean sendOpenInfo(
            int op, int epoch, String rejectionReason) {
        LegacyCommandResult result = sendInfoResult(op);
        if (result == LegacyCommandResult.ACCEPTED) return true;
        if (result == LegacyCommandResult.INDETERMINATE) {
            synchronized (this) {
                if (epoch != seqEpoch || projState != ST_OPENING) {
                    return false;
                }
                projState = ST_CLOSED;
                ready = false;
            }
            sLegacyBootRestoreBlocksAdmission = true;
            watchdogHandler.removeCallbacks(maxCapTask);
            watchdogHandler.removeCallbacks(lingerTask);
            notifyPipelineClosed();
            logger.error("projection open opcode " + op
                    + " is indeterminate; refusing same-boot close/reopen "
                    + "and retaining durable ownership until a verified OS reboot");
            return false;
        }
        // Definitive refusal of an OPEN opcode: remember why for the status
        // endpoint (the cast UI otherwise only sees "cast failed"). The close
        // that forceClose issues next re-derives the boot-recovery verdict.
        recordLegacyVerdict(
                result == LegacyCommandResult.UNAVAILABLE
                        ? LEGACY_VERDICT_UNAVAILABLE
                        : LEGACY_VERDICT_UNSUPPORTED,
                "legacy open opcode " + op + " " + result + " "
                        + compactShellOutput(sLastLegacyShellOutput));
        forceClose(rejectionReason);
        return false;
    }

    private void pollReady(long elapsed, int epoch) {
        if (shuttingDown) return;               // terminal — abandon a pending open
        if (epoch != seqEpoch) return;          // superseded by a newer sequence
        if (projState != ST_OPENING) return;    // cancelled / closed underneath us
        // Fast path: DisplayManager already shows the cluster display. (Often null
        // from the daemon's stale cache — see READY_SETTLE_MS — so this is a bonus,
        // not the contract.)
        if (displayPresent()) { commitReady(epoch, "display present", true); return; }
        // Settle path: the display materializes ~280ms after OP_DI4_MODE but the
        // daemon's DisplayManager cache may never report it. Once past the settle
        // window, commit ready anyway (compositing is the proven path). NEVER
        // force-close here — a timeout-close caused the open/close thrash that
        // reflowed the head-unit. If the fission display is STILL absent at settle,
        // the model likely doesn't support cluster projection (e.g. a small
        // Atto-3-class non-fission cluster) — log it loudly so "nothing happens" on
        // those models is diagnosable; we still commit (harmless — the layer just
        // composites onto nothing).
        if (elapsed >= READY_SETTLE_MS) {
            boolean present = displayPresent();
            if (!present) {
                logger.warn("cluster projection: fission display NOT present at settle — "
                    + "model may not support projection (small/non-fission cluster?). "
                    + "Committing anyway; if no video, this trim is unsupported.");
            }
            commitReady(epoch, present ? "settle+present" : "settle+ABSENT", present);
            return;
        }
        projHandler.postDelayed(() -> pollReady(elapsed + READY_POLL_MS, epoch), READY_POLL_MS);
    }

    /** Atomically claim ST_OPEN (re-checking epoch+state under the lock so a racing
     *  forceClose wins cleanly), then arm the max-cap and notify the pipeline to
     *  retarget the layer onto the cluster.
     *  @param presentAtCommit true if the fission display was genuinely present when
     *         we committed. When FALSE we committed on the settle-timeout (cold open):
     *         the display will materialize ~1-3.5s later, so arm the bounded
     *         present-edge re-notify so the pipeline re-drives the show once the
     *         display+stack resolve. */
    private void commitReady(int epoch, String why, boolean presentAtCommit) {
        synchronized (this) {
            if (epoch != seqEpoch || projState != ST_OPENING) return;
            openedAtMs = System.currentTimeMillis();
            ready = true;
            projState = ST_OPEN;
        }
        watchdogHandler.removeCallbacks(maxCapTask);
        // Suppress the hard max-cap auto-close while the map holds the projection;
        // the map is meant to stay up for the whole drive. The gate flag still
        // carries activeUntil for SIGKILL recovery, and every EXPLICIT restore
        // (ACC-off/disable/release/SIGTERM) still fires — only the timed auto-tear
        // is suppressed. Transient (blind-spot) sessions keep the cap.
        if (!sustainedHeld()) {
            watchdogHandler.postDelayed(maxCapTask, maxProjectionMs);
            // Re-check after the post: acquireSustained may have added a holder on a
            // foreign thread between the read above and this post (the narrow
            // max-cap-on-sustained race). If so, pull the cap back off — a sustained
            // hold must never auto-close at 90s. acquireSustained ALSO posts a removal
            // onto this same (projThread) looper, so the two cannot both miss.
            if (sustainedHeld()) watchdogHandler.removeCallbacks(maxCapTask);
        }
        logger.info("projection OPEN + ready (" + why + (sustainedHeld() ? ", sustained" : "") + ")");
        notifyPipelineReady();
        // Show the current-speed glass badge over whatever is projected (map / BS /
        // future content). Independent of the consumer; gated + drawn entirely inside
        // ClusterSpeedOverlay (no-op when disabled via UCM). Cheap: posts onto its own
        // thread. Hidden again on every close path (forceClose / shutdown). Pass the
        // open's epoch so a forceClose that supersedes this open between the post and
        // the overlay exec thread running startOnExec deterministically drops the arm
        // (the bare projectionOpen() re-check can read a stale-visible ST_OPEN — see
        // currentSeqEpoch()).
        try { ClusterSpeedOverlay.getInstance().start(epoch); } catch (Throwable t) {
            logger.debug("speed overlay start failed: " + t.getMessage());
        }
        // COLD-OPEN show-retry: we just committed ready but the fission display was
        // NOT actually present yet (settle-timeout commit). The pipeline's first
        // clusterShowWhenReady() will resolve STACK_UNRESOLVED and defer the show, and
        // the only existing retry (bsTurnTick) stops the moment the turn signal clears.
        // Arm a bounded, signal-INDEPENDENT poll on THIS thread (projThread owns
        // dumpsys — never the GL/turn loop) that re-notifies the pipeline on the
        // present-edge so the card + speed badge land once the display materializes.
        // When the display WAS present at commit there is nothing to wait for.
        if (!presentAtCommit) {
            // Seed with the full re-drive budget: if the display has ALREADY
            // materialized between the displayPresent() check in pollReady/commitReady
            // and this first pollPresentEdge tick (a real race — each check spawns a
            // dumpsys-display process, so there is latency between them), the present
            // branch fires on tick 0 and must still issue PRESENT_REDRIVE_TICKS more
            // re-drives to cover the layerStack line trailing the display add. Passing
            // 0 here would short-circuit at "redrivesLeft <= 0" after a single show,
            // matching the bug where the card + speed badge never appear because the
            // first overlay start() landed before the stack resolved.
            pollPresentEdge(0, false, PRESENT_REDRIVE_TICKS, epoch);
        }
    }

    /**
     * Bounded present-edge re-notify, run entirely on projThread (so the dumpsys in
     * {@link #displayPresent()} never blocks the 250ms BS turn loop or the GL thread).
     *
     * <p>Armed by {@link #commitReady} ONLY when ready was committed on the
     * settle-timeout (the fission display was not yet present — a cold open). Polls
     * genuine display presence every {@link #PRESENT_POLL_MS}; on the first present
     * tick (and {@link #PRESENT_REDRIVE_TICKS} more after it, in case the layerStack
     * parse trails the display add) it re-calls {@link #notifyPipelineReady()}, which
     * re-runs {@code onClusterProjectionReady → clusterShowWhenReady} with the now
     * resolvable stack. Gives up after {@link #PRESENT_POLL_WINDOW_MS} (covers the
     * worst cold materialize; an unsupported trim simply never shows, as before).
     *
     * <p>Every tick re-validates the lifecycle so a superseded/closed/teardown
     * projection is NEVER re-driven:
     * <ul>
     *   <li>{@code shuttingDown} → abandon (terminal; restore already ran).</li>
     *   <li>{@code epoch != seqEpoch} → a close/re-open superseded this open; abandon
     *       (the new sequence owns its own re-notify if it cold-opens).</li>
     *   <li>{@code projState != ST_OPEN} → forceClose/linger/max-cap closed it; abandon
     *       (re-notify after close would risk a show-after-close).</li>
     * </ul>
     * Cheap: at most {@code PRESENT_POLL_WINDOW_MS / PRESENT_POLL_MS} dumpsys spawns,
     * only on a cold open, and it short-circuits the instant the display resolves +
     * the re-drives are spent.
     */
    private void pollPresentEdge(long elapsed, boolean sawPresent, int redrivesLeft, int epoch) {
        if (shuttingDown) return;              // terminal — projection torn down
        if (epoch != seqEpoch) return;         // superseded by a newer open/close
        if (projState != ST_OPEN) return;      // closed underneath us (linger/cap/forceClose)
        boolean present = sawPresent || displayPresent();
        if (present) {
            // (Re-)drive the pipeline show now that the display is resolvable. The
            // pipeline decides whether to actually paint the BS card itself: it shows
            // for a transient (BS-driven) open even after the turn signal cleared —
            // that is the whole point of this cold-open re-notify, the projection is
            // LINGERING for the card — but suppresses the card on a sustained-map-only
            // open with no active BS signal (so this never spuriously paints the card
            // over the nav map). The extra re-drives cover the case where the display
            // add is visible to dumpsys a beat before its layerStack line is parseable.
            logger.info("present-edge re-notify (cold open, display materialized, redrivesLeft="
                    + redrivesLeft + ")");
            notifyPipelineReady();
            // Re-drive the speed badge too. commitReady's single start() (above) runs
            // BEFORE the display materialized on a cold open, so createAndShow()
            // declined (stack UNRESOLVED) and startOnExec scheduled NO retry — the
            // badge silently no-shows for the whole first projection (confirmed in the
            // on-device log: "createAndShow: cluster stack -1 ... skipping speed
            // overlay" on the first open, success only from the second). notifyPipelineReady
            // re-drives ONLY the BS card, never the overlay, so re-issue start() here.
            // Idempotent + race-safe by construction: startOnExec re-checks running /
            // projectionOpen() / enabledInConfig() on the overlay's OWN exec thread, so
            // its dumpsys never touches projThread and a superseded/disabled start is
            // dropped. We already passed shuttingDown/epoch/ST_OPEN above; passing the
            // epoch lets startOnExec drop a start that a forceClose supersedes between
            // this post and the overlay exec thread (closes the stale-projState window).
            try { ClusterSpeedOverlay.getInstance().start(epoch); } catch (Throwable t) {
                logger.debug("present-edge speed overlay start failed: " + t.getMessage());
            }
            if (redrivesLeft <= 0) return;     // present + re-drives spent → done
            projHandler.postDelayed(
                () -> pollPresentEdge(elapsed + PRESENT_POLL_MS, true, redrivesLeft - 1, epoch),
                PRESENT_POLL_MS);
            return;
        }
        if (elapsed >= PRESENT_POLL_WINDOW_MS) {
            // Never materialized within the window — likely an unsupported/non-fission
            // cluster trim. Stop polling (the projection stays up under its normal
            // linger/cap restore paths; nothing here changes the gauge-restore net).
            logger.warn("present-edge re-notify: fission display still absent after "
                    + PRESENT_POLL_WINDOW_MS + "ms — giving up (trim may not support projection)");
            return;
        }
        projHandler.postDelayed(
            () -> pollPresentEdge(elapsed + PRESENT_POLL_MS, false,
                    PRESENT_REDRIVE_TICKS, epoch),
            PRESENT_POLL_MS);
    }

    private void doCloseSequence(int epoch) {
        try {
            if (!isCurrentCloseEpoch(epoch)) return;
            final int restoreProfile =
                    restoreProfileOpcode != 0
                            ? restoreProfileOpcode
                            : sizeProfileOpcode;
            logger.info("projection close: " + OP_CLOSE + "→" + OP_REFRESH);
            LegacyCommandResult closeResult =
                    sendInfoResult(OP_CLOSE);
            if (closeResult != LegacyCommandResult.ACCEPTED) {
                completeCloseSequence(
                        epoch,
                        false,
                        "opcode " + OP_CLOSE + " " + closeResult);
                return;
            }
            projHandler.postDelayed(() -> {
                if (!isCurrentCloseEpoch(epoch)) return;
                LegacyCommandResult refreshResult =
                        sendInfoResult(OP_REFRESH);
                if (refreshResult != LegacyCommandResult.ACCEPTED) {
                    completeCloseSequence(
                            epoch,
                            false,
                            "opcode " + OP_REFRESH + " "
                                    + refreshResult);
                    return;
                }
                // Re-assert the car's native size profile before consuming the
                // durable recovery marker. A crash between refresh and this
                // persistent profile restore must remain boot-recoverable.
                if (restoreProfile != 0) {
                    projHandler.postDelayed(() -> {
                        if (!isCurrentCloseEpoch(epoch)) return;
                        LegacyCommandResult profileResult =
                                sendInfoResult(restoreProfile);
                        boolean profileRestored =
                                profileResult
                                        == LegacyCommandResult.ACCEPTED;
                        completeCloseSequence(
                                epoch,
                                profileRestored,
                                profileRestored
                                        ? ""
                                        : "restore profile "
                                                + restoreProfile
                                                + " "
                                                + profileResult);
                    }, CLOSE_STEP_DELAY_MS);
                } else {
                    completeCloseSequence(epoch, true, "");
                }
            }, CLOSE_STEP_DELAY_MS);
        } catch (Throwable t) {
            logger.warn("doCloseSequence error: " + t.getMessage());
            completeCloseSequence(
                    epoch, false, "close exception: " + t.getMessage());
        }
    }

    private boolean isCurrentCloseEpoch(int epoch) {
        synchronized (this) {
            return epoch == seqEpoch && projState == ST_CLOSING;
        }
    }

    private void completeCloseSequence(
            int epoch,
            boolean restoreCommandsSucceeded,
            String failureReason) {
        final boolean fullyRestored;
        synchronized (this) {
            if (epoch != seqEpoch || projState != ST_CLOSING) return;
            // Keep the epoch stable through the durable clear. Otherwise a
            // concurrent newer forceClose could supersede this close after the
            // check but before the disk write, letting this stale completion
            // erase the newer close's crash-recovery marker.
            boolean markerCleared =
                    restoreCommandsSucceeded && clearGateFlagsStatic();
            fullyRestored =
                    restoreCommandsSucceeded && markerCleared;
            projState = ST_CLOSED;
            ready = false;
        }
        watchdogHandler.removeCallbacks(maxCapTask);
        sLegacyBootRestoreBlocksAdmission = !fullyRestored;
        if (fullyRestored) {
            logger.info("projection CLOSED + gauges restore confirmed");
            return;
        }
        logger.warn("projection close incomplete; durable recovery marker "
                + "retained"
                + (failureReason == null || failureReason.isEmpty()
                        ? ""
                        : " (" + failureReason + ")"));
        clearStaleGateAtBoot();
    }

    // ── SIGKILL / SIGTERM recovery ──────────────────────────────────────────────

    /**
     * Called from {@code CameraDaemon.main()} top. If a prior daemon was SIGKILL'd
     * mid-projection it could not run its shutdown hook, leaving the gauges blanked.
     * The leaked UCM gate flag tells us to blind-fire the close sequence (18→0) on a
     * throwaway thread — the opcode path is stateless, so it restores the gauges
     * with no live display handle, and is harmless if the projection was already
     * closed. Static so it needs no live instance.
     */
    public static void clearStaleGateAtBoot() {
        sRestoreDispatches.incrementAndGet();
        try {
            new Thread(() -> {
                try {
                    clearStaleGateAtBootSynchronously();
                } finally {
                    sRestoreDispatches.decrementAndGet();
                }
            }, "ClusterProjBootRestoreDispatch").start();
        } catch (RuntimeException | Error dispatchFailure) {
            sRestoreDispatches.decrementAndGet();
            throw dispatchFailure;
        }
    }

    public static boolean clearStaleGateAtBootSynchronously() {
        return clearStaleGateAtBootResultSynchronously(false)
                .isRecovered();
    }

    public static boolean
            clearStaleGateAfterDiLink5RecoverySynchronously() {
        return clearStaleGateAfterDiLink5RecoveryResultSynchronously()
                .isRecovered();
    }

    public static LegacyBootRecoveryResult
            clearStaleGateAfterDiLink5RecoveryResultSynchronously() {
        return clearStaleGateAtBootResultSynchronously(true);
    }

    private static LegacyBootRecoveryResult
            clearStaleGateAtBootResultSynchronously(
            boolean allowDiLink5RecoveryFence) {
        BootRestoreTicket ticket;
        boolean ownsRestore = false;
        synchronized (BOOT_RESTORE_LOCK) {
            if (sBootRestoreTicket != null) {
                ticket = sBootRestoreTicket;
            } else {
                final LegacyGateSnapshot marker;
                try {
                    org.json.JSONObject root =
                            UnifiedConfigManager
                                    .readDurableConfigStrict();
                    marker = parseLegacyGateSnapshot(
                            root.optJSONObject("surveillance"));
                } catch (Throwable readFailure) {
                    sLegacyBootRestoreBlocksAdmission = true;
                    logger.warn("legacy projection recovery flags are "
                            + "unreadable; admission remains blocked");
                    return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
                }
                if (!marker.readable) {
                    sLegacyBootRestoreBlocksAdmission = true;
                    logger.warn("legacy projection recovery fields are "
                            + "malformed; admission remains blocked");
                    return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
                }
                if (!marker.hasRecoveryOwnership()) {
                    sLegacyBootRestoreBlocksAdmission = false;
                    return LegacyBootRecoveryResult.RECOVERED;
                }
                if (legacyIndeterminateCommandMayStillComplete(
                        marker.commandIndeterminate,
                        marker.commandBootId,
                        readCurrentBootId())) {
                    sLegacyBootRestoreBlocksAdmission = true;
                    logger.warn("legacy projection command may still complete "
                            + "in this OS boot; refusing an unordered 18→0 "
                            + "restore until a verified reboot");
                    return LegacyBootRecoveryResult
                            .SAME_BOOT_INDETERMINATE;
                }
                if (com.overdrive.app.camera.dilink5.ClusterTransport
                        .isDiLink5()
                        && !allowDiLink5RecoveryFence) {
                    // Legacy and DI5 share the cluster compositor. Never erase
                    // proof of an interrupted legacy projection while DI5 may
                    // still own that compositor. DiLink5ClusterCast first
                    // retires its own durable ownership, then re-enters this
                    // method with explicit cross-mode recovery authority.
                    sLegacyBootRestoreBlocksAdmission = true;
                    logger.warn("deferring stale legacy gauge restore until "
                            + "DI5 compositor recovery completes");
                    return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
                }
                if (!allowDiLink5RecoveryFence
                        && DiLink5ClusterCast
                                .isLegacyProjectionBlockedByDiLink5Recovery()) {
                    logger.warn("deferring legacy projection gate restore "
                            + "until DI5 ownership recovery completes");
                    return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
                }
                if (!ensureLegacyBootRecoveryOwnership(marker)) {
                    sLegacyBootRestoreBlocksAdmission = true;
                    logger.warn("stale legacy projection ownership could not "
                            + "be promoted to a full recovery gate; refusing "
                            + "command dispatch");
                    return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
                }
                logger.warn("stale cluster-projection gate at boot "
                        + "(forceStop=" + marker.forceStop
                        + ", activeUntil=" + marker.activeUntilMs
                        + ") — restoring gauges");
                sLegacyBootRestoreBlocksAdmission = true;
                ticket = new BootRestoreTicket();
                sBootRestoreTicket = ticket;
                ownsRestore = true;
            }
        }

        if (ownsRestore) {
            try {
                Thread worker = new Thread(
                        () -> runBootGaugeRestore(ticket),
                        "ClusterProjBootRestore");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable dispatchFailure) {
                logger.warn("unable to dispatch legacy boot gauge restore: "
                        + dispatchFailure.getMessage());
                finishBootGaugeRestore(
                        ticket,
                        LegacyBootRecoveryResult.RETRYABLE_FAILURE);
            }
        }
        try {
            if (!ticket.completion.await(
                    BOOT_RESTORE_WAIT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                logger.warn("timed out waiting for legacy boot gauge restore");
                return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return LegacyBootRecoveryResult.RETRYABLE_FAILURE;
        }
        return ticket.result;
    }

    private static void runBootGaugeRestore(
            BootRestoreTicket ticket) {
        LegacyBootRecoveryResult recoveryResult =
                LegacyBootRecoveryResult.RETRYABLE_FAILURE;
        // Definitive "the legacy protocol has no working endpoint here"
        // answers for opcode 18. When EVERY attempt is such an answer the
        // outcome is terminal (UNSUPPORTED) — no retry ladder can change what
        // the firmware exposes, and leaving the fence up forever merely
        // blocked blind-spot / nav-map / cast on that model (field log
        // 2026-09-23: 4 × 3 REJECTED at every boot, then a permanent block).
        int definitiveRejections = 0;
        boolean serviceAbsentGateCleared = false;
        String lastCloseResult = "";
        try {
            int restoreProfile = readLegacyRestoreProfileOpcode();
            for (int attempt = 0;
                    attempt < BOOT_RESTORE_MAX_ATTEMPTS;
                    attempt++) {
                LegacyCommandResult closeResult =
                        sendInfoStaticResult(OP_CLOSE);
                if (closeResult == LegacyCommandResult.INDETERMINATE) {
                    recoveryResult =
                            LegacyBootRecoveryResult
                                    .SAME_BOOT_INDETERMINATE;
                    break;
                }
                lastCloseResult = closeResult + " "
                        + compactShellOutput(sLastLegacyShellOutput);
                if (closeResult == LegacyCommandResult.UNAVAILABLE) {
                    // The legacy endpoint ("AutoContainer") is not registered.
                    // Every legacy command — including the open that wrote
                    // this marker — is addressed to that exact name, so no
                    // legacy opcode can ever have reached the compositor and
                    // there is nothing to restore: the same conclusion the
                    // DI5 recovery draws for UNAVAILABLE
                    // (DiLink5ClusterCast.powerDownCompositorOnce). A DI5-only
                    // alias (auto_container) may still exist; that compositor
                    // is owned and recovered by the DI5 markers, never by this
                    // one. Treating absence as a failure kept the durable
                    // marker (and a 3-attempt restore pass at every boot)
                    // alive forever on such firmware.
                    boolean diLink5AliasPresent = hasDiLink5ContainerService();
                    if (clearGateFlagsStatic()) {
                        logger.info("legacy boot gauge restore: no AutoContainer "
                                + "endpoint on this firmware (" + lastCloseResult
                                + (diLink5AliasPresent
                                        ? "; a DI5 container alias answers instead"
                                        : "")
                                + "); nothing to restore — releasing the legacy "
                                + "recovery gate");
                        recordLegacyVerdict(
                                LEGACY_VERDICT_UNAVAILABLE,
                                "no AutoContainer endpoint on this firmware: "
                                        + lastCloseResult);
                        serviceAbsentGateCleared = true;
                        recoveryResult =
                                LegacyBootRecoveryResult.RECOVERED;
                    } else {
                        logger.warn("legacy boot gauge restore: no AutoContainer "
                                + "endpoint, but the durable gate could not be "
                                + "cleared");
                    }
                    break;
                } else if (closeResult == LegacyCommandResult.REJECTED) {
                    definitiveRejections++;
                }
                boolean closeSent =
                        closeResult == LegacyCommandResult.ACCEPTED;
                boolean refreshSent = false;
                boolean profileSent = restoreProfile == 0;
                if (closeSent
                        && sleepForLegacyRestore(CLOSE_STEP_DELAY_MS)) {
                    LegacyCommandResult refreshResult =
                            sendInfoStaticResult(OP_REFRESH);
                    if (refreshResult
                            == LegacyCommandResult.INDETERMINATE) {
                        recoveryResult =
                                LegacyBootRecoveryResult
                                        .SAME_BOOT_INDETERMINATE;
                        break;
                    }
                    refreshSent =
                            refreshResult == LegacyCommandResult.ACCEPTED;
                    if (refreshSent && restoreProfile != 0
                            && sleepForLegacyRestore(
                                    CLOSE_STEP_DELAY_MS)) {
                        LegacyCommandResult profileResult =
                                sendInfoStaticResult(restoreProfile);
                        if (profileResult
                                == LegacyCommandResult.INDETERMINATE) {
                            recoveryResult =
                                    LegacyBootRecoveryResult
                                            .SAME_BOOT_INDETERMINATE;
                            break;
                        }
                        profileSent =
                                profileResult
                                        == LegacyCommandResult.ACCEPTED;
                    }
                }
                if (closeSent
                        && refreshSent
                        && profileSent
                        && clearGateFlagsStatic()) {
                    recoveryResult =
                            LegacyBootRecoveryResult.RECOVERED;
                    break;
                }
                if (attempt < BOOT_RESTORE_RETRY_DELAYS_MS.length) {
                    if (!sleepForLegacyRestore(
                            BOOT_RESTORE_RETRY_DELAYS_MS[attempt])) break;
                }
            }
            if (recoveryResult == LegacyBootRecoveryResult.RETRYABLE_FAILURE
                    && definitiveRejections >= BOOT_RESTORE_MAX_ATTEMPTS) {
                // Every attempt was a definitive "no" for opcode 18 from a live
                // endpoint: the legacy protocol is not accepted on this
                // firmware. Retain the durable marker (it is still proof that
                // a legacy open was attempted) but stop treating the outcome
                // as retryable — DiLink5ClusterCast ends its ladder and both
                // admission fences are released by finishBootGaugeRestore.
                recoveryResult = LegacyBootRecoveryResult.UNSUPPORTED;
                logger.warn("legacy boot gauge restore: opcode " + OP_CLOSE
                        + " definitively refused on all "
                        + BOOT_RESTORE_MAX_ATTEMPTS + " attempts ("
                        + lastCloseResult + "); legacy cluster protocol is "
                        + "unsupported on this firmware — releasing projection "
                        + "admission, durable marker retained");
                recordLegacyVerdict(
                        LEGACY_VERDICT_UNSUPPORTED,
                        "AutoContainer refused legacy opcode " + OP_CLOSE
                                + " on every attempt: " + lastCloseResult);
            } else if (recoveryResult
                    == LegacyBootRecoveryResult.RECOVERED) {
                if (!serviceAbsentGateCleared) {
                    recordLegacyVerdict(LEGACY_VERDICT_RECOVERED, "");
                }
            } else if (recoveryResult
                    == LegacyBootRecoveryResult.SAME_BOOT_INDETERMINATE) {
                recordLegacyVerdict(
                        LEGACY_VERDICT_INDETERMINATE,
                        "a legacy command may still complete in this OS boot");
            } else {
                recordLegacyVerdict(
                        LEGACY_VERDICT_RETRYABLE,
                        "restore incomplete: " + lastCloseResult);
            }
        } catch (Throwable failure) {
            logger.warn("legacy boot gauge restore failed: "
                    + failure.getMessage());
        } finally {
            finishBootGaugeRestore(ticket, recoveryResult);
        }
    }

    private static boolean sleepForLegacyRestore(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void finishBootGaugeRestore(
            BootRestoreTicket ticket,
            LegacyBootRecoveryResult recoveryResult) {
        ticket.result = recoveryResult;
        // A finished pass blocks admission only while a same-boot command may
        // still land (ordering hazard). RETRYABLE/UNSUPPORTED keep the durable
        // marker, so the next boot re-runs this restore and any later
        // successful close performs the same 18→0→profile; refusing new
        // sessions in the meantime never restored a single gauge.
        sLegacyBootRestoreBlocksAdmission =
                recoveryResult.blocksAdmissionAfterRestore();
        ticket.completion.countDown();
        synchronized (BOOT_RESTORE_LOCK) {
            if (sBootRestoreTicket == ticket) {
                sBootRestoreTicket = null;
            }
        }
    }

    /**
     * Called by {@code DiLink5ClusterCast} when its bounded recovery ladder is
     * exhausted for a NON-indeterminate reason. Releases the legacy admission
     * block unless a restore pass is still in flight (its own finish decides)
     * or the durable marker proves a same-boot command may still complete.
     *
     * @return true when admission is no longer blocked by this controller
     */
    public static boolean releaseLegacyBootAdmissionAfterExhaustedRecovery() {
        synchronized (BOOT_RESTORE_LOCK) {
            if (sBootRestoreTicket != null) {
                return false;
            }
            try {
                org.json.JSONObject root =
                        UnifiedConfigManager.readDurableConfigStrict();
                LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                        root.optJSONObject("surveillance"));
                if (!marker.readable) {
                    return false;
                }
                if (legacyIndeterminateCommandMayStillComplete(
                        marker.commandIndeterminate,
                        marker.commandBootId,
                        readCurrentBootId())) {
                    return false;
                }
            } catch (Throwable readFailure) {
                return false;
            }
            sLegacyBootRestoreBlocksAdmission = false;
            logger.warn("legacy projection admission released after bounded "
                    + "boot recovery retries were exhausted; durable recovery "
                    + "marker retained for the next restore");
            return true;
        }
    }

    /**
     * Called from the daemon shutdown hook (SIGTERM / normal exit). Force-close
     * SYNCHRONOUSLY so 18→0 lands before the VM dies, blocking on a latch.
     *
     * <p>Two safety properties vs a naive close:
     * <ul>
     *   <li>Bumps seqEpoch + sets ST_CLOSING (like {@link #forceClose}) so any
     *       in-flight open-sequence step lambda bails on the epoch mismatch and
     *       cannot re-issue OP_FULLSCREEN_ON/OP_DI4_MODE AFTER our restore.</li>
     *   <li>Clears the UCM gate flags ONLY after the full 18→0→native-profile
     *       restore has been confirmed (inside the close runnable), NOT up-front. If the latch times
     *       out before the close completes (a wedged/busy projThread), the flags
     *       stay SET, so the next respawn's {@link #clearStaleGateAtBoot} re-fires
     *       the restore — boot recovery is preserved as the backstop.</li>
     * </ul>
     * The latch budget covers the bounded shell calls and inter-step waits.
     */
    public void shutdown() {
        watchdogHandler.removeCallbacks(maxCapTask);
        watchdogHandler.removeCallbacks(lingerTask);
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && projState == ST_CLOSED) {
            synchronized (this) {
                shuttingDown = true;
                ready = false;
                sustainedHolders.clear();
            }
            // A legacy marker belongs to cross-mode recovery, not this inactive
            // controller. Preserve it until DI5 cleanup authorizes 18→0→profile.
            return;
        }
        final boolean alreadyClosed;
        synchronized (this) {
            shuttingDown = true;   // terminal — blocks any future requestOpen re-entry
            alreadyClosed = projState == ST_CLOSED;
            if (!alreadyClosed) {
                projState = ST_CLOSING;
                ready = false;
                ++seqEpoch;   // supersede any in-flight open sequence
            }
        }
        // Preserve the same source-before-dependent ordering as forceClose even if shutdown()
        // is invoked outside the daemon hook's normal pre-detach sequence.
        try { com.overdrive.app.navmap.AndroidAutoClusterBridge
                .onProjectionForceClosed("daemon shutdown"); }
        catch (Throwable t) {
            logger.warn("Android Auto cluster shutdown cleanup failed: " + t.getMessage());
        }
        try { com.overdrive.app.navmap.ClusterMapProjector
                .onLegacyProjectionForceClosed(); }
        catch (Throwable t) {
            logger.warn("map shutdown invalidation failed: " + t.getMessage());
        }
        boolean dependentsDetached =
                detachProjectionDependents("shutdown");
        sustainedHolders.clear();   // teardown drops ALL holds; restore proceeds normally
        if (alreadyClosed) {
            // Nothing open in memory. Preserve any durable marker left by an
            // earlier incomplete close so the next boot can recover it.
            // Still retire the badge in case a stray arm slipped in (idempotent).
            try { ClusterSpeedOverlay.stopIfActive(); } catch (Throwable ignored) {}
            return;
        }
        sLegacyBootRestoreBlocksAdmission = true;
        if (!dependentsDetached) {
            logger.warn("shutdown left the durable projection marker set "
                    + "because mirror detach was not confirmed");
            return;
        }
        // Tear down the speed badge on daemon exit (SIGTERM / normal). Posted AFTER
        // projState→ST_CLOSING + ++seqEpoch (mirrors forceClose's ordering) so a racing
        // commitReady's startOnExec projectionOpen() re-check deterministically fails and
        // can't re-arm the badge after this stop. (shuttingDown also makes requestOpen a
        // no-op, but the ordering closes the in-flight-commitReady window.) A SIGKILL'd
        // daemon's layer dies with the process, so no boot-recovery path is needed.
        try { ClusterSpeedOverlay.stopIfActive(); } catch (Throwable ignored) {}
        final CountDownLatch latch = new CountDownLatch(1);
        projHandler.post(() -> {
            boolean restored = false;
            try {
                int restoreProfile =
                        restoreProfileOpcode != 0
                                ? restoreProfileOpcode
                                : sizeProfileOpcode;
                boolean closeSent =
                        sendInfoResult(OP_CLOSE)
                                == LegacyCommandResult.ACCEPTED;
                boolean refreshSent = closeSent
                        && sleepForLegacyRestore(CLOSE_STEP_DELAY_MS)
                        && sendInfoResult(OP_REFRESH)
                                == LegacyCommandResult.ACCEPTED;
                boolean profileSent = restoreProfile == 0
                        || (refreshSent
                                && sleepForLegacyRestore(
                                        CLOSE_STEP_DELAY_MS)
                                && sendInfoResult(restoreProfile)
                                        == LegacyCommandResult.ACCEPTED);
                restored = closeSent
                        && refreshSent
                        && profileSent
                        && clearGateFlagsStatic();
            } catch (Throwable failure) {
                logger.warn("shutdown projection restore failed: "
                        + failure.getMessage());
            } finally {
                projState = ST_CLOSED;
                ready = false;
                sLegacyBootRestoreBlocksAdmission = !restored;
                latch.countDown();
            }
        });
        try {
            latch.await(9_500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ── UCM gate flags ──────────────────────────────────────────────────────────

    /**
     * A command-only marker from an older boot is valid evidence that the
     * compositor may need recovery, but it is not sufficient ownership for
     * dispatching a new command: every physical 18→0→profile step must retain
     * {@code forceStop=true} until the complete restore is confirmed.
     */
    static boolean legacyBootRecoveryOwnershipNeedsPromotion(
            LegacyGateSnapshot marker) {
        return marker != null
                && marker.readable
                && marker.hasRecoveryOwnership()
                && (!marker.forceStop || marker.commandIndeterminate);
    }

    /**
     * Normalize legacy/partial recovery evidence into the full crash-safe gate
     * before any boot-restore opcode is dispatched. The caller has already
     * proved that an indeterminate command belongs to an older OS boot, so its
     * worker cannot still complete and the command fields can be retired in
     * the same atomic config commit that establishes {@code forceStop}.
     */
    private static boolean ensureLegacyBootRecoveryOwnership(
            LegacyGateSnapshot marker) {
        if (marker == null
                || !marker.readable
                || !marker.hasRecoveryOwnership()) {
            return false;
        }
        if (!legacyBootRecoveryOwnershipNeedsPromotion(marker)) {
            return marker.forceStop;
        }

        Map<String, Object> ownership = new HashMap<>();
        ownership.put(GATE_FORCE_STOP, true);
        ownership.put(GATE_ACTIVE_UNTIL, 0L);
        ownership.put(GATE_COMMAND_INDETERMINATE, false);
        ownership.put(GATE_COMMAND_BOOT_ID, "");
        try {
            if (!UnifiedConfigManager.updateValues(
                    "surveillance", ownership)) {
                return false;
            }
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot promoted = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            boolean confirmed = promoted.readable
                    && promoted.forceStop
                    && promoted.activeUntilMs == 0L
                    && !promoted.commandIndeterminate
                    && promoted.commandBootId.isEmpty();
            if (confirmed) {
                logger.warn("promoted stale legacy projection ownership to "
                        + "a full recovery gate before ordered restore");
            }
            return confirmed;
        } catch (Throwable failure) {
            logger.warn("failed to promote legacy projection recovery "
                    + "ownership: " + failure.getMessage());
            return false;
        }
    }

    private boolean writeGateFlags(long activeUntilMs) {
        Map<String, Object> m = new HashMap<>();
        m.put(GATE_FORCE_STOP, true);
        m.put(GATE_ACTIVE_UNTIL, activeUntilMs);
        m.put(GATE_COMMAND_INDETERMINATE, false);
        m.put(GATE_COMMAND_BOOT_ID, "");
        try {
            if (!UnifiedConfigManager.updateValues(
                    "surveillance", m)) {
                return false;
            }
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            return marker.readable
                    && marker.forceStop
                    && marker.activeUntilMs == activeUntilMs
                    && !marker.commandIndeterminate;
        } catch (Throwable failure) {
            logger.warn("failed to persist legacy projection ownership "
                    + "marker: " + failure.getMessage());
            return false;
        }
    }

    private boolean clearGateFlags() { return clearGateFlagsStatic(); }

    private static boolean clearGateFlagsStatic() {
        // Retiring the full recovery gate must be serialized with
        // mark+dispatch. Otherwise this method could read C=false, a command
        // could then durably mark+verify C=true, and this method's later write
        // could erase both F and C immediately before the physical dispatch.
        synchronized (LEGACY_COMMAND_DISPATCH_LOCK) {
            return clearGateFlagsUnderCommandLock();
        }
    }

    private static boolean clearGateFlagsUnderCommandLock() {
        // Read-before-write: skip the full-config disk rewrite when the flags are
        // already clear. This makes the common cases free — the head-unit-default
        // disableBlindSpot() forceClose("bs-disabled") (projection never opened) and
        // any forceClose that early-returns at ST_CLOSED — and is the single
        // authoritative clear. Physical close paths retain the marker until
        // 18→0→native-profile restoration has been confirmed.
        try {
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            if (!marker.readable) return false;
            if (!marker.hasRecoveryOwnership()) {
                return true;
            }
            if (legacyIndeterminateCommandMayStillComplete(
                    marker.commandIndeterminate,
                    marker.commandBootId,
                    readCurrentBootId())) {
                logger.warn("refusing to clear same-boot indeterminate "
                        + "legacy projection ownership");
                return false;
            }
        } catch (Throwable ignored) {}
        Map<String, Object> m = new HashMap<>();
        m.put(GATE_FORCE_STOP, false);
        m.put(GATE_ACTIVE_UNTIL, 0L);
        m.put(GATE_COMMAND_INDETERMINATE, false);
        m.put(GATE_COMMAND_BOOT_ID, "");
        try {
            if (!UnifiedConfigManager.updateValues(
                    "surveillance", m)) {
                return false;
            }
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            return marker.readable
                    && !marker.hasRecoveryOwnership();
        } catch (Throwable failure) {
            logger.warn("failed to persist legacy projection gate clear: "
                    + failure.getMessage());
            return false;
        }
    }

    private static LegacyCommandMarkerResult
            markLegacyCommandInFlight() {
        String bootId = readCurrentBootId();
        if (bootId.isEmpty()) {
            logger.warn("cannot mark legacy projection command in flight: "
                    + "kernel boot id is unavailable");
            return LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH;
        }
        try {
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot existing = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            if (!existing.readable) {
                logger.warn("cannot mark legacy projection command in "
                        + "flight: durable ownership is malformed");
                return LegacyCommandMarkerResult
                        .FAILED_BEFORE_DISPATCH;
            }
            if (legacyIndeterminateCommandMayStillComplete(
                    existing.commandIndeterminate,
                    existing.commandBootId,
                    bootId)) {
                sLegacyBootRestoreBlocksAdmission = true;
                logger.warn("refusing a second legacy projection command "
                        + "while the prior same-boot outcome is unknown");
                return LegacyCommandMarkerResult
                        .SAME_BOOT_INDETERMINATE;
            }
            if (!existing.forceStop) {
                sLegacyBootRestoreBlocksAdmission = true;
                logger.warn("cannot mark legacy projection command in "
                        + "flight: full recovery ownership is absent");
                return LegacyCommandMarkerResult
                        .FAILED_BEFORE_DISPATCH;
            }
        } catch (Throwable readFailure) {
            logger.warn("cannot verify legacy projection command ownership: "
                    + readFailure.getMessage());
            return LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH;
        }
        Map<String, Object> markerValues = new HashMap<>();
        markerValues.put(GATE_COMMAND_INDETERMINATE, true);
        markerValues.put(GATE_COMMAND_BOOT_ID, bootId);
        try {
            if (!UnifiedConfigManager.updateValues(
                    "surveillance", markerValues)) {
                return LegacyCommandMarkerResult
                        .FAILED_BEFORE_DISPATCH;
            }
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            if (marker.readable
                    && marker.forceStop
                    && marker.commandIndeterminate
                    && bootId.equals(marker.commandBootId)) {
                return LegacyCommandMarkerResult.MARKED;
            }
            logger.warn("legacy command marker verification failed before "
                    + "dispatch; rolling it back");
            return rollbackLegacyCommandMarkerBeforeDispatch()
                    ? LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH
                    : LegacyCommandMarkerResult
                            .SAME_BOOT_INDETERMINATE;
        } catch (Throwable failure) {
            logger.warn("failed to persist legacy command in-flight marker: "
                    + failure.getMessage());
            return rollbackLegacyCommandMarkerBeforeDispatch()
                    ? LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH
                    : LegacyCommandMarkerResult
                            .SAME_BOOT_INDETERMINATE;
        }
    }

    /**
     * No physical command was dispatched, so rollback only needs to prove that
     * the command fields were retired. Requiring {@code forceStop} here caused
     * the DI3 boot regression: a successfully cleared command-only legacy
     * marker was falsely reported as an indeterminate physical command.
     */
    private static boolean rollbackLegacyCommandMarkerBeforeDispatch() {
        return clearLegacyCommandMarker(false);
    }

    /**
     * A command may have reached the OEM service. Clear its in-flight fields
     * while atomically re-asserting full sequence ownership for the rest of the
     * ordered restore. This matters for a late command completion: if another
     * cleanup raced while the command was unresolved, the command that just
     * completed must reclaim the recovery gate before scheduling 18→0→profile.
     */
    private static boolean clearLegacyCommandInFlightAfterDispatch() {
        return clearLegacyCommandMarker(true);
    }

    private static boolean clearLegacyCommandMarker(
            boolean retainRecoveryOwnership) {
        Map<String, Object> markerValues = new HashMap<>();
        if (retainRecoveryOwnership) {
            markerValues.put(GATE_FORCE_STOP, true);
        }
        markerValues.put(GATE_COMMAND_INDETERMINATE, false);
        markerValues.put(GATE_COMMAND_BOOT_ID, "");
        try {
            if (!UnifiedConfigManager.updateValues(
                    "surveillance", markerValues)) {
                return false;
            }
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            LegacyGateSnapshot marker = parseLegacyGateSnapshot(
                    root.optJSONObject("surveillance"));
            return isLegacyCommandMarkerClearVerified(
                    marker, retainRecoveryOwnership);
        } catch (Throwable failure) {
            logger.warn("failed to clear legacy command in-flight marker: "
                    + failure.getMessage());
            return false;
        }
    }

    static boolean isLegacyCommandMarkerClearVerified(
            LegacyGateSnapshot marker,
            boolean requireRecoveryOwnership) {
        return marker != null
                && marker.readable
                && !marker.commandIndeterminate
                && marker.commandBootId.isEmpty()
                && (!requireRecoveryOwnership || marker.forceStop);
    }

    static LegacyGateSnapshot parseLegacyGateSnapshot(
            org.json.JSONObject surveillance) {
        if (surveillance == null) {
            return new LegacyGateSnapshot(
                    true, false, 0L, false, "");
        }
        boolean hasForceStop = surveillance.has(GATE_FORCE_STOP);
        boolean hasActiveUntil = surveillance.has(GATE_ACTIVE_UNTIL);
        if (hasForceStop != hasActiveUntil) {
            return unreadableLegacyGateSnapshot();
        }
        boolean forceStop = false;
        long activeUntil = 0L;
        if (hasForceStop) {
            Object forceValue = surveillance.opt(GATE_FORCE_STOP);
            Object activeValue = surveillance.opt(GATE_ACTIVE_UNTIL);
            if (!(forceValue instanceof Boolean)
                    || !(activeValue instanceof Number)) {
                return unreadableLegacyGateSnapshot();
            }
            double activeDouble =
                    ((Number) activeValue).doubleValue();
            activeUntil = ((Number) activeValue).longValue();
            if (!Double.isFinite(activeDouble)
                    || activeDouble != (double) activeUntil
                    || activeUntil < 0L) {
                return unreadableLegacyGateSnapshot();
            }
            forceStop = (Boolean) forceValue;
        }

        boolean hasIndeterminate =
                surveillance.has(GATE_COMMAND_INDETERMINATE);
        boolean hasBootId =
                surveillance.has(GATE_COMMAND_BOOT_ID);
        if (hasIndeterminate != hasBootId) {
            return unreadableLegacyGateSnapshot();
        }
        boolean indeterminate = false;
        String bootId = "";
        if (hasIndeterminate) {
            Object indeterminateValue =
                    surveillance.opt(GATE_COMMAND_INDETERMINATE);
            Object bootValue =
                    surveillance.opt(GATE_COMMAND_BOOT_ID);
            if (!(indeterminateValue instanceof Boolean)
                    || !(bootValue instanceof String)) {
                return unreadableLegacyGateSnapshot();
            }
            indeterminate = (Boolean) indeterminateValue;
            bootId = (String) bootValue;
            if (indeterminate) {
                if (canonicalBootIdOrNull(bootId) == null) {
                    return unreadableLegacyGateSnapshot();
                }
            } else if (!bootId.isEmpty()) {
                return unreadableLegacyGateSnapshot();
            }
        }
        return new LegacyGateSnapshot(
                true,
                forceStop,
                activeUntil,
                indeterminate,
                bootId);
    }

    private static LegacyGateSnapshot unreadableLegacyGateSnapshot() {
        return new LegacyGateSnapshot(
                false, false, 0L, false, "");
    }

    static boolean legacyIndeterminateCommandMayStillComplete(
            boolean indeterminate,
            String originBootId,
            String currentBootId) {
        if (!indeterminate) return false;
        String origin = canonicalBootIdOrNull(originBootId);
        String current = canonicalBootIdOrNull(currentBootId);
        return origin == null || current == null || origin.equals(current);
    }

    private static String readCurrentBootId() {
        try (java.io.BufferedReader reader =
                new java.io.BufferedReader(
                        new java.io.FileReader(
                                "/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            String canonical = canonicalBootIdOrNull(
                    value == null ? "" : value.trim());
            return canonical == null ? "" : canonical;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String canonicalBootIdOrNull(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            String canonical =
                    java.util.UUID.fromString(value).toString();
            return canonical.equals(value) ? canonical : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void loadTuning() {
        try {
            org.json.JSONObject s = UnifiedConfigManager.getSurveillance();
            if (s != null) {
                long l = s.optLong("clusterLingerMs", DEFAULT_LINGER_CLOSE_MS);
                long mx = s.optLong("clusterMaxProjectionMs", DEFAULT_MAX_PROJECTION_MS);
                lingerCloseMs = Math.max(2000, Math.min(l, 60000));
                maxProjectionMs = Math.max(10000, Math.min(mx, 300000));
                // Size-profile opcode (29=8.8" / 30=12.3" / 31=10.25" / 0=skip).
                // Primary source is the blindspot section (where the web UI writes it
                // via the layout dropdown, alongside target); fall back to the
                // surveillance key (live-tuning) then a MODEL-FINGERPRINT default seed
                // (so a fresh install on a non-Seal trim gets the right aspect without
                // the user touching the dropdown — the dropdown still overrides).
                int seedDefault = fingerprintSizeProfile();
                int sizeFromSurv = s.optInt("clusterSizeProfile", seedDefault);
                org.json.JSONObject bs = UnifiedConfigManager.getBlindSpot();
                sizeProfileOpcode = (bs != null) ? bs.optInt("clusterSizeProfile", sizeFromSurv) : sizeFromSurv;
                // Safe-range guard: only 0 (skip) or the known size opcodes 29/30/31
                // are valid here. A bad value (typo / stale config / out-of-range) would
                // send a wrong opcode into the OEM container — clamp to the seed default.
                // (NEVER opcode 1, and never outside the documented size set.)
                if (sizeProfileOpcode != 0
                        && !isKnownLegacySizeProfile(sizeProfileOpcode)) {
                    logger.warn("clusterSizeProfile " + sizeProfileOpcode
                            + " out of range — using fingerprint default " + seedDefault);
                    sizeProfileOpcode = seedDefault;
                }
                // Native profile to restore on close (29/30/31), for models whose
                // native cluster size differs from the projection profile. 0 = none.
                int rpSurv = s.optInt("clusterRestoreProfile", 0);
                restoreProfileOpcode = (bs != null) ? bs.optInt("clusterRestoreProfile", rpSurv) : rpSurv;
                if (restoreProfileOpcode != 0
                        && !isKnownLegacySizeProfile(
                                restoreProfileOpcode)) {
                    logger.warn("clusterRestoreProfile "
                            + restoreProfileOpcode
                            + " out of range — using configured native "
                            + "size profile");
                    restoreProfileOpcode = 0;
                }
                // Open-sequence inter-step delay (first-load latency). Clamp 100..3000.
                long os = s.optLong("clusterOpenStepMs", DEFAULT_OPEN_STEP_DELAY_MS);
                openStepDelayMs = Math.max(100, Math.min(os, 3000));
                long ss = s.optLong("clusterSizeStepMs", DEFAULT_SIZE_STEP_MS);
                sizeStepMs = Math.max(0, Math.min(ss, 3000));
            }
        } catch (Throwable ignored) {}
    }

    private static int readLegacyRestoreProfileOpcode() {
        int seedDefault = fingerprintSizeProfile();
        try {
            org.json.JSONObject root =
                    UnifiedConfigManager.readDurableConfigStrict();
            org.json.JSONObject surveillance =
                    root.optJSONObject("surveillance");
            org.json.JSONObject blindspot =
                    root.optJSONObject("blindspot");
            int sizeProfile = surveillance != null
                    ? surveillance.optInt(
                            "clusterSizeProfile", seedDefault)
                    : seedDefault;
            if (blindspot != null) {
                sizeProfile = blindspot.optInt(
                        "clusterSizeProfile", sizeProfile);
            }
            if (sizeProfile != 0
                    && !isKnownLegacySizeProfile(sizeProfile)) {
                sizeProfile = seedDefault;
            }
            int restoreProfile = surveillance != null
                    ? surveillance.optInt(
                            "clusterRestoreProfile", 0)
                    : 0;
            if (blindspot != null) {
                restoreProfile = blindspot.optInt(
                        "clusterRestoreProfile", restoreProfile);
            }
            if (restoreProfile != 0
                    && !isKnownLegacySizeProfile(restoreProfile)) {
                restoreProfile = 0;
            }
            return restoreProfile != 0
                    ? restoreProfile
                    : sizeProfile;
        } catch (Throwable failure) {
            logger.warn("legacy restore profile read failed: "
                    + failure.getMessage());
            return seedDefault;
        }
    }

    private static boolean isKnownLegacySizeProfile(int opcode) {
        return opcode == 29 || opcode == 30 || opcode == 31;
    }

    /**
     * Default size-profile opcode seeded from the vehicle model fingerprint, so a
     * fresh install picks the right cluster aspect without the user touching the
     * layout dropdown. Only a SEED — the UCM/web value always overrides. Conservative
     * mapping (ro.product.model substrings): Atto/Yuan-class small cluster → 29 (8.8"),
     * larger Han/Tang/Song/Seal-U/DM-i → 30 (12.3"), else the Seal 10.25" native → 31.
     * Resolution-bucket guessing is deliberately NOT used (it mis-maps the 1920×720
     * fission panel). Unknown → OP_SIZE_PROFILE (31, the verified-good Seal default).
     */
    private static int fingerprintSizeProfile() {
        String m;
        try {
            m = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "");
        } catch (Throwable t) { m = ""; }
        if (m == null) m = "";
        String lo = m.toLowerCase(java.util.Locale.US);
        if (lo.contains("atto") || lo.contains("yuan")) return 29;       // 8.8"
        if (lo.contains("han") || lo.contains("tang") || lo.contains("song")
                || lo.contains("seal-u") || lo.contains("seal u") || lo.contains("dm-i")
                || lo.contains("dmi")) return 30;                         // 12.3"
        return OP_SIZE_PROFILE;                                          // 31 (10.25" Seal native)
    }

    // ── Cluster display presence ────────────────────────────────────────────────

    private boolean displayPresent() {
        try {
            Context ctx = resolveContext();
            if (ctx != null) {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    if (dm.getDisplay(CLUSTER_DISPLAY_ID) != null) return true;
                    android.view.Display[] ds = dm.getDisplays();
                    if (ds != null) {
                        for (android.view.Display d : ds) {
                            String n = d.getName();
                            if (n != null && n.toLowerCase(java.util.Locale.US).contains("fission")) return true;
                        }
                    }
                }
            }
            // The daemon's DisplayManager cache is unreliable for the foreign uid-1000
            // fission display — fall back to the AUTHORITATIVE `dumpsys display` (the
            // same source clusterLayerStack uses; it reflects reality even when the
            // cache is stale). This also tells us, on a model where projection is
            // unsupported (e.g. a small non-fission cluster), that the display truly
            // never appears — see clusterDisplayPresentViaDumpsys + the ready logging.
            return clusterDisplayPresentViaDumpsys();
        } catch (Throwable ignored) {}
        return false;
    }

    /** Authoritative "is the fission cluster display live" via `dumpsys display`.
     *  Returns false when the model never materializes a fission display (e.g. an
     *  Atto-3-class small cluster that may not support projection at all).
     *
     *  <p>PREDICATE-UNIFY FIX (root cause of "no video after 3-4 attempts"): this now
     *  delegates to the SAME authoritative parser the show path already trusts —
     *  {@link BsNativeLayer#resolveFissionDisplay()}.present() (true iff a displayId
     *  OR a layerStack was parsed from a "fission" display block). The OLD predicate
     *  here required "fission" AND "type VIRTUAL" AND "state ON" to all appear on a
     *  SINGLE dumpsys line; on this firmware's layout those tokens sit on SEPARATE
     *  lines, so it returned FALSE forever — even while {@link ClusterSpeedOverlay}
     *  (which uses resolveFissionDisplay) successfully composited on the live stack.
     *  That blindness made displayPresent() never true, so {@code pollReady} always
     *  committed "settle+ABSENT" and the cold-open backstop {@code pollPresentEdge}
     *  polled a predicate that could never go true → it "gave up after 4000ms" and
     *  never re-drove the show → the warm BS layer was never re-tagged onto the live
     *  (incrementing) cluster layerStack → black. Delegating to the proven resolver
     *  makes presence detection consistent across ALL callers. Unsupported-trim
     *  safety is preserved: present() is FALSE when no fission block parses at all
     *  (no displayId, no layerStack), exactly as the old strict predicate intended —
     *  and it does NOT go true on stray/partial "fission" diagnostic text, because it
     *  requires a genuinely parsed id or stack. Still one `dumpsys display` spawn per
     *  call, still only invoked on projThread (I9). */
    private boolean clusterDisplayPresentViaDumpsys() {
        try {
            return BsNativeLayer.resolveFissionDisplay().present();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── Pipeline ready callback (reflection, avoids surveillance→pipeline import cycle) ──

    private void notifyPipelineReady() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Method getPipe = cd.getMethod("getGpuPipeline");
            Object pipe = getPipe.invoke(null);
            if (pipe != null) {
                Method m = pipe.getClass().getMethod("onClusterProjectionReady");
                m.invoke(pipe);
            }
        } catch (Throwable t) {
            // INFO not debug: a reflection failure here (e.g. R8 renaming getGpuPipeline /
            // onClusterProjectionReady without a keep rule) silently kills the cluster
            // re-tag → BS card black. Make it visible at INFO so it never hides again.
            logger.info("notifyPipelineReady FAILED (reflection): " + t.getMessage());
        }
    }

    private void notifyPipelineClosed() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Method getPipe = cd.getMethod("getGpuPipeline");
            Object pipe = getPipe.invoke(null);
            if (pipe != null) {
                Method m = pipe.getClass().getMethod("onClusterProjectionClosed");
                m.invoke(pipe);
            }
        } catch (Throwable t) {
            logger.info("notifyPipelineClosed FAILED (reflection): " + t.getMessage());
        }
    }

    // ── AutoContainer sendInfo ──────────────────────────────────────────────────

    /**
     * Send a DI3 projection opcode through the firmware-compatible direct
     * {@code IAutoContainer} Binder path first. The synchronous vendor call runs
     * on an isolated daemon thread, so a stall cannot pin the projection owner
     * thread. If the wait budget expires, the worker is deliberately left alive:
     * a later definitive reply clears its own durable in-flight marker and
     * schedules an ordered 18→0 recovery. Only a failure that is proven to occur
     * before Binder dispatch may fall back to the strict {@code service call}
     * transport.
     */
    private LegacyCommandResult sendInfoResult(int op) {
        return sendInfoMarkedResult(AUTO_CONTAINER_SERVICE, op);
    }

    private static LegacyCommandResult sendInfoStaticResult(int op) {
        return sendInfoMarkedResult(AUTO_CONTAINER_SERVICE, op);
    }

    private static LegacyCommandResult sendInfoMarkedResult(
            String service, int op) {
        synchronized (LEGACY_COMMAND_DISPATCH_LOCK) {
            sLastLegacyShellOutput = "";
            LegacyCommandMarkerResult markerResult =
                    markLegacyCommandInFlight();
            if (markerResult
                    == LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH) {
                return LegacyCommandResult.REJECTED;
            }
            if (markerResult
                    == LegacyCommandMarkerResult
                            .SAME_BOOT_INDETERMINATE) {
                return legacyCommandResult(
                        LegacyTransportResult.INDETERMINATE);
            }

            LegacyCommandAttempt attempt = new LegacyCommandAttempt();
            try {
                Thread worker = new Thread(
                        () -> runLegacyBinderAttempt(
                                attempt, service, op),
                        "ClusterProjBinder-" + op);
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable dispatchFailure) {
                logger.warn("unable to dispatch direct legacy Binder command "
                        + op + ": " + dispatchFailure.getMessage());
                if (!rollbackLegacyCommandMarkerBeforeDispatch()) {
                    return legacyCommandResult(
                            LegacyTransportResult.INDETERMINATE);
                }
                return sendInfoShellMarkedResult(service, op);
            }

            try {
                if (!attempt.completion.await(
                        LEGACY_COMMAND_WAIT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS)) {
                    logger.warn("direct legacy Binder command timed out: op="
                            + op + "; worker retained for late resolution");
                    watchLateLegacyBinderAttempt(attempt, op);
                    return legacyCommandResult(
                            LegacyTransportResult.INDETERMINATE);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                watchLateLegacyBinderAttempt(attempt, op);
                return legacyCommandResult(
                        LegacyTransportResult.INDETERMINATE);
            }

            if (attempt.result == LegacyTransportResult.UNAVAILABLE) {
                return sendInfoShellMarkedResult(service, op);
            }
            return legacyCommandResult(attempt.result);
        }
    }

    private static void runLegacyBinderAttempt(
            LegacyCommandAttempt attempt,
            String service,
            int op) {
        try {
            LegacyTransportResult result =
                    sendInfoBinderTransportResult(service, op);
            if (result != LegacyTransportResult.INDETERMINATE
                    && !clearLegacyCommandInFlightAfterDispatch()) {
                result = LegacyTransportResult.INDETERMINATE;
            }
            attempt.result = result;
        } finally {
            attempt.completion.countDown();
        }
    }

    private static LegacyTransportResult
            sendInfoBinderTransportResult(String service, int op) {
        final Object iface;
        final Method send;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class)
                    .invoke(null, service);
            if (!(binder instanceof IBinder)) {
                return LegacyTransportResult.UNAVAILABLE;
            }
            Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
            iface = stub.getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (iface == null) {
                return LegacyTransportResult.UNAVAILABLE;
            }
            send = iface.getClass().getMethod(
                    "sendInfo", int.class, int.class, String.class);
        } catch (Throwable unavailable) {
            logger.debug("direct legacy Binder unavailable for op " + op
                    + ": " + unavailable.getMessage());
            return LegacyTransportResult.UNAVAILABLE;
        }

        try {
            send.invoke(iface, CLUSTER_TYPE, op, "");
            return LegacyTransportResult.ACCEPTED;
        } catch (Throwable dispatchFailure) {
            // Once Method.invoke begins, a reflected exception cannot prove
            // whether the remote service applied the command before replying.
            logger.warn("direct legacy Binder command " + op
                    + " completed ambiguously: "
                    + dispatchFailure.getClass().getSimpleName()
                    + ": " + dispatchFailure.getMessage()
                    + describeReflectedCause(dispatchFailure));
            return LegacyTransportResult.INDETERMINATE;
        }
    }

    private static void watchLateLegacyBinderAttempt(
            LegacyCommandAttempt attempt, int op) {
        startLateLegacyWatcher(
                "ClusterProjBinderLate-" + op,
                () -> {
                    awaitUninterruptibly(attempt.completion);
                    if (attempt.result
                            == LegacyTransportResult.INDETERMINATE) {
                        logger.warn("late legacy Binder command " + op
                                + " remained indeterminate; durable recovery "
                                + "ownership retained");
                        return;
                    }
                    logger.info("late legacy Binder command " + op
                            + " resolved as " + attempt.result
                            + "; scheduling ordered gauge recovery");
                    scheduleRecoveryAfterLateLegacyCommand();
                });
    }

    private static LegacyCommandResult legacyCommandResult(
            LegacyTransportResult result) {
        switch (result) {
            case ACCEPTED:
                return LegacyCommandResult.ACCEPTED;
            case REJECTED:
                return LegacyCommandResult.REJECTED;
            case UNAVAILABLE:
                // Preserved (not collapsed into REJECTED): boot recovery must
                // be able to tell "no compositor service on this firmware"
                // from "the service refused the opcode".
                return LegacyCommandResult.UNAVAILABLE;
            case INDETERMINATE:
            default:
                sLegacyBootRestoreBlocksAdmission = true;
                return LegacyCommandResult.INDETERMINATE;
        }
    }

    private static void startLateLegacyWatcher(
            String name, Runnable watcher) {
        try {
            Thread thread = new Thread(watcher, name);
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable failure) {
            logger.warn("unable to monitor late legacy projection command: "
                    + failure.getMessage());
        }
    }

    private static void awaitUninterruptibly(CountDownLatch completion) {
        boolean interrupted = false;
        while (true) {
            try {
                completion.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void scheduleRecoveryAfterLateLegacyCommand() {
        ClusterProjectionController current = instance;
        if (current == null) {
            DiLink5ClusterCast
                    .retryRecoveryAfterLegacyCommandResolution();
            return;
        }
        try {
            boolean posted = current.projHandler.post(() -> {
                if (current.projState == ST_CLOSING) {
                    logger.info("active close sequence owns late-command "
                            + "gauge recovery");
                    return;
                }
                DiLink5ClusterCast
                        .retryRecoveryAfterLegacyCommandResolution();
            });
            if (!posted) {
                DiLink5ClusterCast
                        .retryRecoveryAfterLegacyCommandResolution();
            }
        } catch (Throwable postFailure) {
            logger.warn("unable to serialize late-command recovery: "
                    + postFailure.getMessage());
            DiLink5ClusterCast
                    .retryRecoveryAfterLegacyCommandResolution();
        }
    }

    public enum DiLink5CommandResult {
        ACCEPTED,
        REJECTED,
        INDETERMINATE,
        TRANSPORT_FAILURE,
        UNAVAILABLE
    }

    private enum DiLink5EndpointResult {
        ACCEPTED,
        REJECTED,
        INDETERMINATE,
        TRANSPORT_FAILURE,
        UNAVAILABLE
    }

    public static boolean sendDiLink5ContainerInfo(int op) {
        return sendDiLink5ContainerInfoResult(op) == DiLink5CommandResult.ACCEPTED;
    }

    /**
     * Preserve the native DI5 verdict instead of collapsing every non-success
     * into false. On trinket firmware the type=1000 command returns -1 even
     * though the OEM shared projection display is the actual pixel-routing
     * primitive, so callers must be able to continue display discovery after a
     * genuine native rejection. UNAVAILABLE means no service path answered.
     */
    public static DiLink5CommandResult sendDiLink5ContainerInfoResult(int op) {
        return sendDiLink5ContainerInfoResult(op, false);
    }

    /**
     * Cleanup-only authority for a daemon that has just committed away from
     * DI5 but still has durable evidence that the previous DI5 session owns
     * the cluster compositor. This entry point can send only 18/0; it can
     * never enable projection or bootstrap the OEM service.
     */
    public static DiLink5CommandResult
            sendDiLink5ContainerCleanupResult(int op) {
        return sendDiLink5ContainerInfoResult(op, true);
    }

    private static DiLink5CommandResult sendDiLink5ContainerInfoResult(
            int op, boolean allowInactiveCleanup) {
        boolean cleanupOpcode = isAllowedDiLink5CleanupOpcode(op);
        // The DI5 container protocol is admitted whenever the DI5 cluster
        // transport is selected (dilink5 camera mode, config override, or
        // DiLink 5 firmware identity); other transports only get cleanup
        // authority (18/0) for stale DI5 ownership.
        if (!com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                && !(allowInactiveCleanup && cleanupOpcode)) {
            return DiLink5CommandResult.UNAVAILABLE;
        }
        if (!isAllowedDiLink5ContainerOpcode(op)) {
            return DiLink5CommandResult.UNAVAILABLE;
        }
        if (allowInactiveCleanup && !cleanupOpcode) {
            return DiLink5CommandResult.UNAVAILABLE;
        }

        DiLink5CommandResult result =
                sendDiLink5ContainerInfoWithoutBootstrap(op);
        if (shouldBootstrapDiLink5ContainerService(op, result)
                && bootstrapDiLink5ContainerService()) {
            logger.info("DI5 container service registered after bootstrap; "
                    + "retrying sendInfo(1000," + op + ")");
            result = sendDiLink5ContainerInfoWithoutBootstrap(op);
        }
        logger.info("DI5 sendInfo(1000," + op + ") finalResult=" + result);
        return result;
    }

    private static DiLink5CommandResult
            sendDiLink5ContainerInfoWithoutBootstrap(int op) {
        // Run the synchronous Binder transaction inside Android's standalone
        // `service` process. A vendor Binder stall can then be killed with
        // that child instead of permanently pinning a daemon thread and
        // disabling every later 18/0 cleanup command in this process. This is
        // also the exact command form used by the upstream BYD implementation.
        for (String service : DI5_AUTO_CONTAINER_SERVICES) {
            DiLink5EndpointResult result =
                    sendDiLink5InfoShell(service, op);
            if (result != DiLink5EndpointResult.UNAVAILABLE) {
                // AutoContainer and auto_container are aliases on different
                // firmware builds. Once one endpoint answers, its native
                // verdict is authoritative; never duplicate the opcode.
                return toDiLink5CommandResult(result);
            }
        }
        return DiLink5CommandResult.UNAVAILABLE;
    }

    private static DiLink5CommandResult toDiLink5CommandResult(
            DiLink5EndpointResult result) {
        switch (result) {
            case ACCEPTED:
                return DiLink5CommandResult.ACCEPTED;
            case REJECTED:
                return DiLink5CommandResult.REJECTED;
            case INDETERMINATE:
                return DiLink5CommandResult.INDETERMINATE;
            case TRANSPORT_FAILURE:
                return DiLink5CommandResult.TRANSPORT_FAILURE;
            case UNAVAILABLE:
            default:
                return DiLink5CommandResult.UNAVAILABLE;
        }
    }

    static boolean isAllowedDiLink5ContainerOpcode(int op) {
        return op == OP_REFRESH
                || op == OP_FULLSCREEN_ON
                || op == OP_CLOSE;
    }

    static boolean isAllowedDiLink5CleanupOpcode(int op) {
        return op == OP_REFRESH || op == OP_CLOSE;
    }

    static boolean shouldBootstrapDiLink5ContainerService(
            int op, DiLink5CommandResult result) {
        return op == OP_FULLSCREEN_ON
                && result == DiLink5CommandResult.UNAVAILABLE;
    }

    private static boolean bootstrapDiLink5ContainerService() {
        synchronized (DI5_CONTAINER_BOOTSTRAP_LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastDiLink5ContainerBootstrapMs != Long.MIN_VALUE
                    && now - lastDiLink5ContainerBootstrapMs
                            < DI5_CONTAINER_BOOTSTRAP_THROTTLE_MS) {
                boolean available = hasDiLink5ContainerService();
                logger.info("DI5 container bootstrap throttled; "
                        + "serviceAvailable=" + available);
                return available;
            }
            lastDiLink5ContainerBootstrapMs = now;

            ShellCommandResult xdjaPath = runShellCommand(
                    2000L,
                    SYSTEM_CMD_BIN,
                    "package",
                    "path",
                    XDJA_CONTAINER_PACKAGE);
            if (!isPackagePathResult(xdjaPath.exitCode, xdjaPath.output)) {
                ShellCommandResult bydPath = runShellCommand(
                        2000L,
                        SYSTEM_CMD_BIN,
                        "package",
                        "path",
                        BYD_CONTAINER_PACKAGE);
                logger.warn("DI5 container bootstrap unavailable: known XDJA "
                        + "package is not installed; xdja="
                        + compactCommandOutput(xdjaPath)
                        + ", byd=" + compactCommandOutput(bydPath)
                        + ", services=" + readDiLink5ContainerServiceInventory());
                return false;
            }

            logger.info("DI5 container bootstrap: installed package confirmed: "
                    + compactCommandOutput(xdjaPath));
            ShellCommandResult start = runShellCommand(
                    3000L,
                    SYSTEM_AM_BIN,
                    "startservice",
                    "--user",
                    "0",
                    "-n",
                    XDJA_DISPLAY_SERVICE_COMPONENT);
            if (!isSuccessfulServiceStartResult(
                    start.exitCode, start.timedOut, start.output)) {
                logger.warn("DI5 container bootstrap start failed: "
                        + compactCommandOutput(start)
                        + "; services=" + readDiLink5ContainerServiceInventory());
                return false;
            }

            long deadline = android.os.SystemClock.elapsedRealtime()
                    + DI5_CONTAINER_BOOTSTRAP_TIMEOUT_MS;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (hasDiLink5ContainerService()) {
                    logger.info("DI5 container bootstrap succeeded; services="
                            + readDiLink5ContainerServiceInventory());
                    return true;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    logger.warn("DI5 container bootstrap interrupted");
                    return false;
                }
            }
            logger.warn("DI5 container bootstrap timed out waiting for binder; "
                    + "start=" + compactCommandOutput(start)
                    + ", services=" + readDiLink5ContainerServiceInventory());
            return false;
        }
    }

    private static boolean hasDiLink5ContainerService() {
        for (String service : DI5_AUTO_CONTAINER_SERVICES) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object resolved = sm.getMethod("getService", String.class)
                        .invoke(null, service);
                if (resolved instanceof IBinder) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean isPackagePathResult(int exitCode, String output) {
        return exitCode == 0
                && output != null
                && output.trim().startsWith("package:");
    }

    static boolean isSuccessfulServiceStartResult(
            int exitCode, boolean timedOut, String output) {
        if (timedOut || exitCode != 0) return false;
        String normalized = output == null
                ? ""
                : output.toLowerCase(java.util.Locale.US);
        return !normalized.contains("error:")
                && !normalized.contains("exception")
                && !normalized.contains("not found")
                && !normalized.contains("does not exist")
                && !normalized.contains("not allowed");
    }

    private static String readDiLink5ContainerServiceInventory() {
        ShellCommandResult inventory = runShellCommand(
                2000L, SYSTEM_SERVICE_BIN, "list");
        if (inventory.timedOut || inventory.exitCode != 0) {
            return compactCommandOutput(inventory);
        }
        StringBuilder relevant = new StringBuilder();
        for (String line : inventory.output.split("\\r?\\n")) {
            String normalized = line.toLowerCase(java.util.Locale.US);
            if (!normalized.contains("container")
                    && !normalized.contains("xdja")) {
                continue;
            }
            if (relevant.length() > 0) relevant.append(" | ");
            relevant.append(line.trim());
            if (relevant.length() >= 700) break;
        }
        return relevant.length() == 0
                ? "<no container service registered>"
                : relevant.toString();
    }

    private static ShellCommandResult runShellCommand(
            long timeoutMs, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return new ShellCommandResult(-1, true, "");
            }
            return new ShellCommandResult(
                    process.exitValue(),
                    false,
                    readProcessOutput(process));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ShellCommandResult(-1, true, "interrupted");
        } catch (Throwable t) {
            return new ShellCommandResult(
                    -1,
                    false,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String compactCommandOutput(ShellCommandResult result) {
        if (result == null) return "<no result>";
        String compact = result.output == null
                ? ""
                : result.output.replaceAll("\\s+", " ").trim();
        if (compact.length() > 512) {
            compact = compact.substring(0, 512) + "...";
        }
        return "exit=" + result.exitCode
                + ", timeout=" + result.timedOut
                + ", output=" + (compact.isEmpty() ? "<empty>" : compact);
    }

    private static final class ShellCommandResult {
        final int exitCode;
        final boolean timedOut;
        final String output;

        ShellCommandResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }
    }

    private static DiLink5EndpointResult sendDiLink5InfoShell(
            String service, int op) {
        Process p = null;
        try {
            String[] cmd = {SYSTEM_SERVICE_BIN, "call", service, "2",
                    "i32", String.valueOf(CLUSTER_TYPE), "i32", String.valueOf(op), "s16", ""};
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(2, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                logger.warn("DI5 service call timed out: service=" + service
                        + ", op=" + op
                        + "; refusing alias retry because transaction outcome "
                        + "is indeterminate");
                return DiLink5EndpointResult.INDETERMINATE;
            }
            String output = readProcessOutput(p);
            if (isDiLink5ServiceUnavailableOutput(output, service)) {
                logger.info("DI5 service call unavailable: service=" + service
                        + ", op=" + op + ", exit=" + p.exitValue()
                        + ", output=" + output.replaceAll("\\s+", " ").trim());
                return DiLink5EndpointResult.UNAVAILABLE;
            }
            if (p.exitValue() != 0) {
                logger.warn("DI5 service call exited ambiguously: service="
                        + service + ", op=" + op + ", exit=" + p.exitValue()
                        + ", output=" + output.replaceAll("\\s+", " ").trim()
                        + "; refusing alias retry");
                return DiLink5EndpointResult.INDETERMINATE;
            }
            Integer result = parseDiLink5ServiceCallResult(output);
            if (result == null) {
                logger.warn("sendDiLink5InfoShell(" + service + ", " + op
                        + ") returned no parseable native result: " + output.trim());
                Integer status = parseDiLink5ServiceCallStatus(output);
                if (status != null) {
                    return status == 0
                            ? DiLink5EndpointResult.INDETERMINATE
                            : DiLink5EndpointResult.INDETERMINATE;
                }
                return DiLink5EndpointResult.INDETERMINATE;
            }
            boolean accepted = isAcceptedDiLink5ContainerResult(result);
            logger.info("sendDiLink5InfoShell(" + service + ", " + op
                    + ") nativeResult=" + result + " accepted=" + accepted);
            return accepted
                    ? DiLink5EndpointResult.ACCEPTED
                    : DiLink5EndpointResult.REJECTED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warn("sendDiLink5InfoShell(" + service + ", " + op
                    + ") interrupted; refusing alias retry");
            return DiLink5EndpointResult.INDETERMINATE;
        } catch (Throwable t) {
            logger.warn("sendDiLink5InfoShell(" + service + ", " + op
                    + ") failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return p == null
                    ? DiLink5EndpointResult.TRANSPORT_FAILURE
                    : DiLink5EndpointResult.INDETERMINATE;
        } finally {
            if (p != null) {
                try {
                    if (p.isAlive()) {
                        p.destroyForcibly();
                    } else {
                        p.destroy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static boolean isDiLink5ServiceUnavailableOutput(
            String output, String service) {
        if (output == null || service == null) return false;
        String normalized =
                output.toLowerCase(java.util.Locale.US)
                        .replaceAll("\\s+", " ")
                        .trim();
        String normalizedService =
                service.toLowerCase(java.util.Locale.US);
        return normalized.contains(
                        "service " + normalizedService + " does not exist")
                || normalized.contains(
                        "service " + normalizedService + ": not found")
                || normalized.contains(
                        "service " + normalizedService + " not found");
    }

    static boolean isAcceptedDiLink5ContainerResult(int result) {
        return result >= 0;
    }

    static Integer parseDiLink5ServiceCallResult(String output) {
        Integer status = parseDiLink5ServiceCallStatus(output);
        if (status == null || status != 0) return null;
        return parseDiLink5ServiceCallWord(output, 2);
    }

    static Integer parseDiLink5ServiceCallStatus(String output) {
        return parseDiLink5ServiceCallWord(output, 1);
    }

    private static Integer parseDiLink5ServiceCallWord(
            String output, int requestedWord) {
        if (output == null || output.isEmpty()) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?i)\\b([0-9a-f]{8})\\b").matcher(output);
        int parcelWord = 0;
        while (matcher.find()) {
            try {
                int value = (int) Long.parseLong(matcher.group(1), 16);
                parcelWord++;
                if (parcelWord == requestedWord) return value;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String readProcessOutput(Process process) {
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) > 0 && output.length() < 4096) {
                output.append(buffer, 0, Math.min(count, 4096 - output.length()));
            }
        } catch (Throwable ignored) {
        }
        return output.toString();
    }

    private static LegacyCommandResult sendInfoShellMarkedResult(
            String service, int op) {
        LegacyCommandMarkerResult markerResult =
                markLegacyCommandInFlight();
        if (markerResult
                == LegacyCommandMarkerResult.FAILED_BEFORE_DISPATCH) {
            return LegacyCommandResult.REJECTED;
        }
        if (markerResult
                == LegacyCommandMarkerResult.SAME_BOOT_INDETERMINATE) {
            return legacyCommandResult(
                    LegacyTransportResult.INDETERMINATE);
        }
        Process process;
        try {
            String[] command = {
                    SYSTEM_SERVICE_BIN,
                    "call",
                    service,
                    "2",
                    "i32",
                    String.valueOf(CLUSTER_TYPE),
                    "i32",
                    String.valueOf(op),
                    "s16",
                    ""
            };
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (Throwable launchFailure) {
            logger.warn("sendInfoShell(" + service + ", " + op
                    + ") could not start: " + launchFailure.getMessage());
            return rollbackLegacyCommandMarkerBeforeDispatch()
                    ? LegacyCommandResult.REJECTED
                    : legacyCommandResult(
                            LegacyTransportResult.INDETERMINATE);
        }

        try {
            if (!process.waitFor(
                    LEGACY_COMMAND_WAIT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                logger.warn("sendInfoShell(" + service + ", " + op
                        + ") timed out; child retained for late resolution");
                watchLateLegacyShellCommand(process, service, op);
                return legacyCommandResult(
                        LegacyTransportResult.INDETERMINATE);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            watchLateLegacyShellCommand(process, service, op);
            return legacyCommandResult(
                    LegacyTransportResult.INDETERMINATE);
        } catch (Throwable waitFailure) {
            logger.warn("sendInfoShell(" + service + ", " + op
                    + ") wait failed: " + waitFailure.getMessage());
            watchLateLegacyShellCommand(process, service, op);
            return legacyCommandResult(
                    LegacyTransportResult.INDETERMINATE);
        }

        int exitCode = process.exitValue();
        String output = readProcessOutput(process);
        LegacyCommandResult result =
                classifyLegacyServiceCallResult(
                        exitCode,
                        false,
                        output);
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
        sLastLegacyShellOutput = "exit=" + exitCode + " "
                + compactShellOutput(output);
        if (result != LegacyCommandResult.ACCEPTED) {
            // Log the raw reply: without it a field log cannot distinguish
            // "Service AutoContainer does not exist" from a Binder exception
            // status word or a native -1 verdict (all used to read REJECTED).
            logger.warn("sendInfoShell(" + service + ", " + op
                    + ") result=" + result + " exit=" + exitCode
                    + " output=" + compactShellOutput(output));
        }
        if (result == LegacyCommandResult.INDETERMINATE) {
            return legacyCommandResult(
                    LegacyTransportResult.INDETERMINATE);
        }
        return clearLegacyCommandInFlightAfterDispatch()
                ? result
                : legacyCommandResult(
                        LegacyTransportResult.INDETERMINATE);
    }

    private static void watchLateLegacyShellCommand(
            Process process, String service, int op) {
        startLateLegacyWatcher(
                "ClusterProjShellLate-" + op,
                () -> {
                    boolean interrupted = false;
                    try {
                        while (true) {
                            try {
                                process.waitFor();
                                break;
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                        LegacyCommandResult result =
                                classifyLegacyServiceCallResult(
                                        process.exitValue(),
                                        false,
                                        readProcessOutput(process));
                        if (result
                                == LegacyCommandResult.INDETERMINATE) {
                            logger.warn("late sendInfoShell(" + service
                                    + ", " + op
                                    + ") remained indeterminate; durable "
                                    + "recovery ownership retained");
                            return;
                        }
                        if (!clearLegacyCommandInFlightAfterDispatch()) {
                            logger.warn("late sendInfoShell(" + service
                                    + ", " + op
                                    + ") resolved but its durable marker "
                                    + "could not be cleared");
                            return;
                        }
                        logger.info("late sendInfoShell(" + service
                                + ", " + op + ") resolved as " + result
                                + "; scheduling ordered gauge recovery");
                        scheduleRecoveryAfterLateLegacyCommand();
                    } catch (Throwable failure) {
                        logger.warn("late sendInfoShell(" + service
                                + ", " + op + ") watcher failed: "
                                + failure.getMessage());
                    } finally {
                        try {
                            process.destroy();
                        } catch (Throwable ignored) {
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
    }

    static boolean isAcceptedLegacyServiceCallResult(
            int exitCode,
            boolean timedOut,
            String output) {
        return classifyLegacyServiceCallResult(
                exitCode, timedOut, output)
                == LegacyCommandResult.ACCEPTED;
    }

    static LegacyCommandResult classifyLegacyServiceCallResult(
            int exitCode,
            boolean timedOut,
            String output) {
        if (timedOut) return LegacyCommandResult.INDETERMINATE;
        if (exitCode != 0) {
            // `service` exits non-zero with "Service X does not exist" / "not
            // found" when no such Binder is registered: nothing was dispatched.
            // Any other non-zero exit is ambiguous (the transaction may have
            // reached the service before the tool failed).
            return output != null
                    && (output.contains("does not exist")
                            || output.contains("not found"))
                    ? LegacyCommandResult.UNAVAILABLE
                    : LegacyCommandResult.INDETERMINATE;
        }
        Integer status = parseDiLink5ServiceCallStatus(output);
        if (status == null) return LegacyCommandResult.INDETERMINATE;
        if (status != 0) return LegacyCommandResult.REJECTED;
        // Some IAutoContainer builds expose sendInfo as void (status word
        // only); others append an integer native verdict. Accept a normal
        // Binder return, but reject an explicit negative native result.
        Integer nativeResult = parseDiLink5ServiceCallResult(output);
        return nativeResult == null
                || isAcceptedDiLink5ContainerResult(nativeResult)
                ? LegacyCommandResult.ACCEPTED
                : LegacyCommandResult.REJECTED;
    }

    private static Context resolveContext() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Object ctx = cd.getMethod("getAppContext").invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return null;
    }
}
