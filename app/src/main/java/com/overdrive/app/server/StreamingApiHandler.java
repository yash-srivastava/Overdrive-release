package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Streaming API Handler - manages WebSocket streaming configuration and control.
 * 
 * Endpoints:
 * - POST /api/stream/enable - Enable WebSocket streaming
 * - POST /api/stream/disable - Disable WebSocket streaming
 * - GET /api/stream/status - Get streaming status
 * - GET /api/stream/quality - Get available quality presets
 * - POST /api/stream/quality/{preset} - Set streaming quality
 * - POST /api/stream/view/{mode} - Set view mode (0=Mosaic, 1-4=AVM quadrant, 6=OEM Dashcam)
 * - GET /api/stream/view - Get current view mode
 */
public class StreamingApiHandler {

    private static String streamingQuality = "LOW";  // Default to LOW for better performance

    // Last view-mode the user explicitly picked, persisted across scaler
    // teardown / WS idle-shutdown / reconnect cycles. The scaler's own
    // currentViewMode field is cleared every time disableStreaming nulls
    // the scaler — pipeline.getStreamViewMode() returns -1 in that window.
    // Mobile browsers naturally hit this: backgrounding the tab >15s fires
    // the WS idle-shutdown → next WS open finds savedViewMode==-1 → fresh
    // scaler defaults to view 0, even though the user is still on DVR view.
    // This static survives the teardown so HttpServer's WS-open path can
    // re-apply the correct view (and re-route OEM for view 6).
    // -1 = never set; 0..6 valid mode values.
    //
    // Blind-spot views 7/8 are INTENTIONALLY excluded (clamp stays <=6): they are
    // session-only, owned by BlindSpotOverlayService which re-issues
    // /api/stream/view/{7|8} on every reveal AND that path re-applies the saved
    // calibration (handleStreamViewMode). Persisting 7/8 here would restore a
    // blind-spot view on a bare WS reconnect WITHOUT re-applying calibration —
    // worse than reverting to a normal view, which self-heals on the next signal.
    private static volatile int lastDesiredViewMode = -1;
    public static int getLastDesiredViewMode() { return lastDesiredViewMode; }
    public static void setLastDesiredViewMode(int mode) {
        if (mode >= -1 && mode <= 6) lastDesiredViewMode = mode;
    }

    /*
     * A browser can send a newer camera choice before an older request has
     * reached the daemon. Keep a small per-page sequence ledger so that an
     * out-of-order DVR request cannot re-route a newer AVM selection.
     */
    private static final Object viewSelectionLock = new Object();
    private static final int MAX_VIEW_SELECTION_CLIENTS = 32;
    private static final java.util.LinkedHashMap<String, Long> latestViewSelections =
        new java.util.LinkedHashMap<String, Long>(MAX_VIEW_SELECTION_CLIENTS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > MAX_VIEW_SELECTION_CLIENTS;
            }
        };

    private static final class ViewRequest {
        private final String clientId;
        private final long selection;

        ViewRequest(String clientId, long selection) {
            this.clientId = clientId;
            this.selection = selection;
        }

        private boolean isSequenced() {
            return clientId != null;
        }

        boolean applyIntent(int mode) {
            if (!isSequenced()) {
                setLastDesiredViewMode(mode);
                return true;
            }
            synchronized (viewSelectionLock) {
                Long latest = latestViewSelections.get(clientId);
                if (latest == null || latest.longValue() != selection) return false;
                setLastDesiredViewMode(mode);
                return true;
            }
        }

        boolean isCurrent() {
            if (!isSequenced()) return true;
            synchronized (viewSelectionLock) {
                Long latest = latestViewSelections.get(clientId);
                return latest != null && latest.longValue() == selection;
            }
        }
    }

    private static ViewRequest parseViewRequest(String path) {
        int queryStart = path.indexOf('?');
        if (queryStart < 0) return new ViewRequest(null, 0L);
        String clientId = null;
        String selectionValue = null;
        String query = path.substring(queryStart + 1);
        for (String parameter : query.split("&")) {
            int equals = parameter.indexOf('=');
            if (equals <= 0) continue;
            String name = parameter.substring(0, equals);
            String value = parameter.substring(equals + 1);
            if ("client".equals(name)) clientId = value;
            else if ("selection".equals(name)) selectionValue = value;
        }
        if (clientId == null || selectionValue == null || clientId.length() > 48
                || !clientId.matches("[A-Za-z0-9_-]+")) {
            return new ViewRequest(null, 0L);
        }
        try {
            long selection = Long.parseLong(selectionValue);
            if (selection <= 0L) return new ViewRequest(null, 0L);
            synchronized (viewSelectionLock) {
                Long latest = latestViewSelections.get(clientId);
                if (latest == null || selection >= latest.longValue()) {
                    latestViewSelections.put(clientId, selection);
                    return new ViewRequest(clientId, selection);
                }
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the stale marker below.
        }
        return new ViewRequest(clientId, -1L);
    }

    private static void sendSupersededViewResponse(OutputStream out, int viewMode) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("cancelled", true);
        response.put("viewMode", viewMode);
        HttpResponse.sendJson(out, response.toString());
    }

    private static boolean ensureCurrentViewRequest(
            OutputStream out, int viewMode, ViewRequest request) throws Exception {
        if (request.isCurrent()) return true;
        sendSupersededViewResponse(out, viewMode);
        return false;
    }

    private static void restoreLatestViewAfterSupersededOemRoute(
            GpuSurveillancePipeline pipeline) {
        int latestMode = getLastDesiredViewMode();
        if (pipeline == null || latestMode == 6) return;
        try {
            pipeline.reattachOwnStreamCallback();
            if (latestMode >= 0) pipeline.setStreamViewMode(latestMode);
        } catch (Throwable t) {
            CameraDaemon.log("restoreLatestViewAfterSupersededOemRoute: " + t.getMessage());
        }
    }

    // ── Blind-spot dedicated stream profile ─────────────────────────────────
    // NOTE: the blind-spot view (7/8) no longer rides the shared live-view
    // encoder — it has its own DEDICATED pipeline (GpuSurveillancePipeline.
    // enableBlindSpot, own encoder+scaler+WS on port BS_WS_PORT, driven by the
    // /api/bs/* endpoints). The shared stream's quality is just the user's pick.

    // Cold-start dedup. The first DVR / view-set click on a fresh daemon
    // takes ~4-9s to warm AVC HAL + open AVMCamera + EGL setup. Without
    // dedup, every retry click re-queues the same expensive work onto the
    // HTTP worker pool and floods CameraDaemon's lifecycle lock. The flag
    // is flipped true the moment we spawn the warm-up worker, cleared in
    // the worker's finally; intermediate clicks short-circuit to
    // {success:false, starting:true} so the JS poll loop just waits.
    private static final java.util.concurrent.atomic.AtomicBoolean panoStartInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Kick a pano cold-start asynchronously if the pipeline isn't running
     * yet and no warmup is currently in flight. Returns true iff the
     * pipeline is already warm; false signals "starting" and the caller
     * should respond with starting=true so the client polls.
     */
    // ── Camera-view arm retry ────────────────────────────────────────────────────
    // Bounded, self-converging retry for a /api/camview/show that arrived before the
    // lane could be armed. Validity is SESSION-scoped, not generation-scoped: the
    // loop serves exactly one user request (its showSession) and exits the moment
    // that session is superseded. Every cancel path — hide, newer show, auto-hide
    // timeout, real teardown, blind-spot takeover — bumps the session
    // (noteCamViewShowRequest / hideCamView / endCamViewSessionLocked), so no
    // separate cancellation token exists to disagree with it. The old global
    // generation was exactly such a disagreement: it recorded CALL order while the
    // session records INTENT order, so a STALE show handler starting its retry
    // late bumped the generation and killed the NEWER show's valid loop — the
    // stale loop then failed session validation and NEITHER armed. Concurrent
    // loops are harmless now: a stale loop exits on its first session check
    // without driving the pano cold start, and ensurePanoStartedNonBlocking is
    // deduped anyway.
    // 30s, matching BlindSpotControl.REARM_DEADLINE_MS: the retry has to outlast a full
    // pano cold start (AvcHalWarmup + camera open), not just a brief in-flight lane build.
    private static final long CAMVIEW_ARM_DEADLINE_MS = 30_000L;
    private static final long CAMVIEW_ARM_BACKOFF_MAX_MS = 1_500L;

    // Serializes a show's WHOLE mutation transaction: session mint + geometry
    // persist + intent write + override install are one atomic unit with respect
    // to other shows. Without it, session order and config-write order could
    // TEAR: an older show's handler, resuming after a newer show completed,
    // wrote ITS geometry/override into the config the newer session's transition
    // tick then resolved — the new view rendered at the old request's position.
    // The arm (enableCamView) stays OUTSIDE this monitor (it can block seconds
    // on a lane build); the session check under bsLifecycleLock covers it.
    // Lock order: camViewShowMutationLock → bsLifecycleLock (noteCamViewShowRequest
    // takes the lane lock briefly); nothing acquires them in reverse — the
    // pipeline never calls back into this handler on camview paths.
    // The hide route deliberately does NOT take this monitor (its teardown can
    // block ~2s on a GL quiesce); a hide interleaving a show transaction leaves
    // only the documented safe-direction config drift (enabled=true persisted,
    // nothing shows — no path re-shows from config).
    private static final Object camViewShowMutationLock = new Object();

    /** Current value of the pipeline's sticky auto-hide latch, or false when there is no
     *  pipeline. Snapshotted at retry start so the loop can tell "MY session timed out"
     *  from "some earlier session did". */
    private static boolean camViewAutoHideLatched() {
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            return p != null && p.camViewAutoHideConsumed();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Is the deferred camera-view arm for {@code showSession} still wanted? Every bail
     * condition in one cheap, side-effect-free predicate, so the retry loop can
     * re-check it immediately before arming as well as at the top of a pass.
     *
     * <p>Cancellation is the SESSION, nothing else. Every cancel path bumps it
     * (hide, newer show, auto-hide timeout, teardown, blind-spot takeover), and the
     * arm itself re-validates it under bsLifecycleLock — this unlocked read is only
     * the early exit. Two former gates are deliberately GONE: the global generation
     * (recorded call order, not intent order — a stale show's late retry start
     * killed a newer show's valid loop) and the persisted camview.enabled re-read
     * (the show route ignores a FALSE return from the intent write, so a failed
     * persist left enabled=false and this gate killed the retry on pass 1 —
     * silently dropping the key press the retry exists to rescue; every real
     * cancel that clears enabled also bumps the session, so the session gate
     * already covers user intent without the config dependency).
     *
     * <p>Deliberately does NO config file I/O (an earlier version's forceReload
     * blocked the 250ms blind-spot arbiter tick behind a peer daemon's write).
     */
    private static boolean camViewArmStillWanted(GpuSurveillancePipeline p,
            long showSession, boolean autoHideAtStart) {
        if (p == null) return false;
        // Superseded by a newer show, or cancelled by a hide / real teardown.
        if (p.getCamViewSessionId() != showSession) return false;
        if (p.isCamViewActive()) return false;          // already armed by someone else
        // Auto-hide: bail only when the flag flipped DURING this loop. It is a sticky
        // process-wide latch cleared only inside enableCamView, so an absolute test would
        // read a PREVIOUS session's timeout — and the pano-cold-start deferral never
        // enters enableCamView, so that stale true would kill the retry on pass 1 and
        // silently drop the key press this whole mechanism exists to rescue.
        try {
            if (!autoHideAtStart && p.camViewAutoHideConsumed()) return false;
        } catch (Throwable ignored) {}
        try {
            // A camera view is only meaningful with ACC on — RMM's own
            // camViewKeepWarmActive() requires accIsOn, so this matches the existing
            // contract rather than inventing one. Parked (sentry) counts as off, because
            // the authoritative flag is set only by AccSentryDaemon IPC while the hardware
            // probe sets accOn/inSentryMode WITHOUT it — an authoritative-only guard would
            // let this loop cold-start the camera while parked.
            //
            // BUT inSentryMode is a LATCHED derivative with no "unknown": the
            // untrustworthy-probe path returns "ACC ON, safe default" WITHOUT rewriting
            // it, so a stale true can survive into a genuine ignition-on before the first
            // IPC — and bailing on that would abandon exactly the cold-start key press
            // this retry exists to rescue. Honour the sentry latch only when a real signal
            // backs it: an authoritative IPC, or a clean (trustworthy) hardware probe.
            // wasLastProbeTrustworthy() is the flag AccMonitor already maintains for this
            // distinction — the ACC-ON disarm watchdog gates on it for the same reason.
            boolean accKnown = com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                || com.overdrive.app.monitor.AccMonitor.wasLastProbeTrustworthy();
            if (accKnown && !com.overdrive.app.monitor.AccMonitor.isAccOn()) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * Bounded background retry converging a deferred camera-view show.
     *
     * <p>{@code showSession} is the session the API show route minted for the USER
     * request this retry serves. Every arm pass MUST reuse it via the
     * session-validated {@code enableCamView(mode, target, autoHide, session)}
     * entry point — the arm side re-checks it under bsLifecycleLock, so a hide (or
     * newer show) landing at ANY point, including between this loop's unlocked
     * still-wanted check and the arm itself, invalidates the session and the arm
     * rejects itself. The old shape (self-minting 3-arg entry) re-MINTED a session
     * on each pass: a hide racing the final check was post-dated by the fresh
     * session and the dismissed view re-armed — the hide race. A false return
     * means superseded: stop retrying, the request is cancelled.
     */
    private static void startCamViewArmRetry(int mode, String target, int autoHide,
            long showSession) {
        // Don't spawn a loop for a request that is ALREADY superseded (a stale
        // handler reaching this line late). Best-effort — the loop's own session
        // checks are the authoritative gate.
        {
            GpuSurveillancePipeline pl = CameraDaemon.getGpuPipeline();
            if (pl == null || pl.getCamViewSessionId() != showSession) return;
        }
        // Snapshot the sticky auto-hide latch AT START. The loop then bails only if it
        // flips during our wait — an absolute test would inherit a previous session's
        // timeout (the latch is cleared only inside enableCamView, which the pano
        // cold-start deferral never reaches) and kill this request on pass 1.
        final boolean autoHideAtStart = camViewAutoHideLatched();
        Thread t = new Thread(() -> {
            // elapsedRealtime, NOT currentTimeMillis: the head unit syncs wall clock from
            // GPS/network after boot, and a backward jump would extend this loop by the
            // size of the correction — thousands of extra passes re-driving the camera
            // cold start. Every other deadline in this feature uses the monotonic clock.
            long deadline = android.os.SystemClock.elapsedRealtime() + CAMVIEW_ARM_DEADLINE_MS;
            long delay = 300L;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(delay); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                delay = Math.min(delay * 2, CAMVIEW_ARM_BACKOFF_MAX_MS);
                GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
                if (!camViewArmStillWanted(p, showSession, autoHideAtStart)) return;
                // Keep driving the pano cold start ONLY while it is legitimately coming up.
                // ensurePanoStartedNonBlocking is async + deduped, so re-calling is cheap;
                // without it the loop would spin against a pipeline nobody is starting.
                if (!ensurePanoStartedNonBlocking(p)) continue;
                // FINAL re-check immediately before arming. The gate above is separated
                // from the arm by ensurePanoStartedNonBlocking, and a hide/newer-show
                // landing in that window would otherwise be overridden — resurrecting a
                // view the user just dismissed, or arming the PREVIOUS camera. Cheap
                // (volatile reads), so re-checking costs nothing.
                if (!camViewArmStillWanted(p, showSession, autoHideAtStart)) return;
                try {
                    // Session-validated arm — the ONLY safe entry point here. The
                    // unlocked still-wanted check above is best-effort; the session
                    // check inside (under bsLifecycleLock) is the authoritative gate.
                    if (p.enableCamView(mode, target, autoHide, showSession)) {
                        CameraDaemon.log("camview arm retry: armed after deferral");
                    } else {
                        CameraDaemon.log("camview arm retry: superseded by a hide or"
                            + " newer show — cancelled");
                    }
                    return;
                } catch (GpuSurveillancePipeline.BlindSpotNotReadyException nr) {
                    // still not ready — keep trying until the deadline
                } catch (Throwable other) {
                    CameraDaemon.log("camview arm retry aborted: " + other.getMessage());
                    return;
                }
            }
            CameraDaemon.log("camview arm retry: gave up after "
                + CAMVIEW_ARM_DEADLINE_MS + "ms (lane never became armable)");
        }, "CamViewArmRetry");
        t.setDaemon(true);
        t.start();
    }

    private static boolean ensurePanoStartedNonBlocking(GpuSurveillancePipeline pano) {
        if (pano == null) return false;
        if (pano.isRunning()) return true;
        // Spawn a single warm-up worker. Re-entrant clicks see the flag
        // and short-circuit without enqueueing duplicate work.
        if (panoStartInFlight.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    com.overdrive.app.camera.AvcHalWarmup warmup =
                        new com.overdrive.app.camera.AvcHalWarmup();
                    warmup.warmupAndWait();
                    pano.start();
                    // This cold-start ran OUTSIDE RecordingModeManager (it's the
                    // blind-spot arm path), so the camera is now up at the
                    // pipeline's full default profile. Tell RMM to reconcile: if
                    // BS is the sole consumer (no recording mode active), it drops
                    // the H.265 recorder lane and parks global camera fps at the
                    // BS idle rate. No-op if a recording mode owns the camera.
                    try {
                        com.overdrive.app.recording.RecordingModeManager rmm =
                            CameraDaemon.getRecordingModeManager();
                        if (rmm != null) rmm.onPipelineStartedExternally();
                    } catch (Throwable t) {
                        CameraDaemon.log("ensurePanoStartedNonBlocking reconcile: " + t.getMessage());
                    }
                    // BS-DEFECT-A: do NOT self-arm the blind-spot lane here.
                    // This worker runs concurrently with the app's re-arm loop
                    // (BlindSpotControl.armWithRetry), which hammers /api/bs/enable →
                    // handleBsEnable → pano.enableBlindSpot() until the lane reports
                    // enabled. A self-arm enableBlindSpot() here sets bsEnabling=true
                    // and then RELEASES the lifecycle lock during enableBlindSpotInternal's
                    // GL-init wait; a concurrent handleBsEnable() acquiring the lock in
                    // that window hits enableBlindSpot's `if (bsEnabling) return;`
                    // early-return — a VOID return that handleBsEnable cannot distinguish
                    // from success (it only catches exceptions), so it reports
                    // {success:true} while blindSpotEnabled is still false. The app then
                    // sees a false-armed lane and stops driving / its WS reconnect-storms
                    // a dead 8889. Cold-start ONLY warms pano here; ALL arming routes
                    // through handleBsEnable so the app's convergent re-poll wins the race
                    // cleanly (single arming owner, no bsEnabling self-collision).
                } catch (Throwable t) {
                    CameraDaemon.log("ensurePanoStartedNonBlocking: " + t.getMessage());
                } finally {
                    panoStartInFlight.set(false);
                }
            }, "PanoColdStart").start();
        }
        return false;
    }
    
    /**
     * Handle streaming API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/stream/enable") && method.equals("POST")) {
            handleEnableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/disable") && method.equals("POST")) {
            handleDisableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/status") && method.equals("GET")) {
            sendStreamStatus(out);
            return true;
        }
        if (path.equals("/api/stream/quality") && method.equals("GET")) {
            sendStreamQualityOptions(out);
            return true;
        }
        if (path.startsWith("/api/stream/quality/") && method.equals("POST")) {
            String quality = path.substring(20).toUpperCase();
            handleSetStreamQuality(out, quality);
            return true;
        }
        if (path.startsWith("/api/stream/view-status/") && method.equals("GET")) {
            String statusPath = path.substring(24);
            int queryStart = statusPath.indexOf('?');
            String modeString = queryStart >= 0
                ? statusPath.substring(0, queryStart)
                : statusPath;
            int viewMode = Integer.parseInt(modeString);
            handleStreamViewStatus(out, viewMode, parseViewRequest(path));
            return true;
        }
        if (path.startsWith("/api/stream/view/")) {
            String viewPath = path.substring(17);
            int queryStart = viewPath.indexOf('?');
            String modeString = queryStart >= 0 ? viewPath.substring(0, queryStart) : viewPath;
            int viewMode = Integer.parseInt(modeString);
            handleStreamViewMode(out, viewMode, parseViewRequest(path));
            return true;
        }
        // Blind-spot (view 7/8) LIVE stitch tuning, used by the RoadSense Blind
        // Spot debug editor's slider preview. In-memory only (not persisted) —
        // the debug editor's Save writes the 'blindspot' UCM section separately.
        if (path.startsWith("/api/stream/bs/")) {
            handleBlindSpotParams(out, path.substring(15));
            return true;
        }
        if (path.equals("/api/stream/view") && method.equals("GET")) {
            sendStreamViewMode(out);
            return true;
        }
        if (path.equals("/api/stream/turn") && method.equals("GET")) {
            sendTurnState(out);
            return true;
        }
        return false;
    }

    /**
     * Dedicated blind-spot pipeline API (/api/bs/*). Completely separate from
     * the /api/stream/* live-view stream: drives GpuSurveillancePipeline's
     * second scaler+encoder+WS (port {@link GpuSurveillancePipeline#BS_WS_PORT}),
     * locked to views 7/8 at the fixed BS profile (1280×960@15). Never touches
     * lastDesiredViewMode / streamingQuality, so a live-view WS reconnect can
     * never hijack the blind-spot view, and vice-versa.
     */
    public static boolean handleBlindSpot(String method, String path, String body, OutputStream out) throws Exception {
        // Method-agnostic: all loopback, all idempotent or clearly-scoped. The
        // overlay drives enable/disable via POST but view-select via the GET-based
        // httpGetSucceeded helper, and /api/stream/view never gated on method
        // either — so accept whatever verb arrives rather than 404 a GET into a
        // non-JSON body the caller then fails to parse.
        if (path.equals("/api/bs/enable")) {
            handleBsEnable(out);
            return true;
        }
        if (path.equals("/api/bs/disable")) {
            handleBsDisable(out);
            return true;
        }
        if (path.equals("/api/bs/hide")) {
            handleBsHide(out);
            return true;
        }
        if (path.equals("/api/bs/status")) {
            handleBsStatus(out);
            return true;
        }
        if (path.startsWith("/api/bs/view/")) {
            int mode;
            try { mode = Integer.parseInt(path.substring(13)); }
            catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "invalid view"); return true; }
            handleBsView(out, mode);
            return true;
        }
        if (path.startsWith("/api/bs/geometry")) {
            handleBsGeometry(out, path);
            return true;
        }
        if (path.startsWith("/api/bs/target/")) {
            handleBsTarget(out, path.substring("/api/bs/target/".length()));
            return true;
        }
        if (path.startsWith("/api/bs/tweak")) {
            handleBsTweak(out, path);
            return true;
        }
        // ── Camera-view (shares the BS lane; blind-spot priority) ──────────────
        if (path.startsWith("/api/camview/")) {
            return handleCamView(path, out);
        }
        return false;
    }

    /**
     * Camera-view routes. An on-demand camera view (front/rear/left/right/all-4)
     * on the SAME native SurfaceControl lane the blind-spot feature uses.
     *   POST /api/camview/show?cam=front&target=head_unit&preset=60/center&autoHide=0
     *     (cam ∈ all|front|right|rear|left OR a raw int 0-4; target ∈ head_unit|cluster;
     *      preset=sizePct/corner OR geometry=x/y/w/h; autoHide seconds, 0=until hidden)
     *   POST /api/camview/geometry/size/{sizePct}?target=head_unit|cluster
     *     (updates only the saved size; preserves the camera view's corner)
     *   POST /api/camview/hide
     *   GET  /api/camview/status
     */
    private static boolean handleCamView(String path, OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        int q = path.indexOf('?');
        String clean = q >= 0 ? path.substring(0, q) : path;
        String query = q >= 0 ? path.substring(q + 1) : "";

        if (clean.equals("/api/camview/status")) {
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("active", pipeline != null && pipeline.isCamViewActive());
            if (pipeline != null) {
                r.put("mode", pipeline.getCamViewMode());
                r.put("target", pipeline.getCamViewTargetString());
                // `active` means REQUESTED, not on-screen: blind-spot can hold the shared
                // lane (calibration preview, or a turn signal its gate allows) while a
                // camview request stands. Report both so an automation polling this can
                // tell "up" from "waiting behind blind-spot" instead of assuming success.
                r.put("rendering", pipeline.isCamViewRendering());
                r.put("maskedByBlindSpot", pipeline.isCamViewMaskedByBlindSpot());
            }
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (clean.startsWith("/api/camview/geometry/")) {
            // Geometry is persisted configuration, so it remains useful while the
            // pipeline is down. The next normal camera-view session then picks it up.
            handleCamViewGeometry(clean, query, pipeline, out);
            return true;
        }
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return true;
        }
        if (clean.equals("/api/camview/hide")) {
            // v40 contract: every hide source closes the current camera view.
            // hideCamView (NOT the lenient disableCamView) — it invalidates the show
            // SESSION even when the view never armed, so a straight-through show still
            // in flight outside the mutation lock rejects itself instead of arming
            // after this hide and reopening the camera behind the success we report.
            // The session bump is ALSO what stops any deferred-arm retry loop: retry
            // validity is session-scoped (see startCamViewArmRetry) — there is no
            // separate cancellation token to race with.
            pipeline.hideCamView();
            // Same false-return contract as the geometry persist: a failed write here
            // means a daemon restart re-shows the view the user just dismissed. The
            // hide itself already happened (session invalidated), so log — don't fail
            // the hide — but leave a trace for exactly that re-appearance report.
            try {
                if (!com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(
                        java.util.Collections.singletonMap("enabled", false))) {
                    CameraDaemon.log("camview hide: enabled=false persist returned false"
                        + " — a daemon restart may re-show the view");
                }
            } catch (Throwable ignored) {}
            JSONObject r = new JSONObject(); r.put("success", true); r.put("active", false);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (clean.equals("/api/camview/show")) {
            java.util.Map<String, String> p = parseQuery(query);
            int mode = parseCamMode(p.get("cam"));
            String target = "cluster".equals(p.get("target")) ? "cluster" : "head_unit";
            // Mint the session FIRST — before ANY config I/O. Everything below the
            // mint (autoHide config read, geometry persist, intent write) can block
            // on the cross-process config file lock for hundreds of ms; with the mint
            // BELOW those writes, a hide completing in that window was post-dated by
            // this (older) request's fresh session and the view REOPENED behind the
            // hide's reported success. Minting at entry pins this request's authority
            // to its arrival order: any hide (or newer show) landing after this line
            // bumps the session and every arm attempt for THIS request self-rejects
            // under bsLifecycleLock. The residual is confined to config drift in the
            // safe direction (enabled=true persisted while nothing shows — no boot
            // path re-shows from config; its only reader is the retry's intent gate).
            // The retry must never re-mint (a fresh session would post-date the hide
            // and defeat its invalidation), which is also why the pipeline has no
            // self-minting enableCamView convenience wrapper. The mint itself is what
            // cancels any older show's retry loop (retry validity is session-scoped).
            //
            // The WHOLE mutation transaction — mint + autoHide read + geometry
            // persist + intent write + override install — is serialized against
            // other shows (camViewShowMutationLock): with unserialized writes, an
            // OLDER show's handler resuming after a newer show completed would
            // overwrite the config/override the newer session's transition tick
            // resolves, rendering the new view at the old request's position.
            // Inside the monitor, session order == config-write order == override
            // order, so the LAST transaction owns all three consistently.
            final long showSession;
            int autoHide = 0;
            JSONObject failedGeo = null;
            synchronized (camViewShowMutationLock) {
                showSession = pipeline.noteCamViewShowRequest(null);
                // Fall back to the PERSISTED autoHideSec when the caller doesn't pass
                // one. The automation action and every key-mapping binding build their
                // URL without an autoHide param, so an explicit-query-only read meant
                // the user's configured auto-hide was ignored on exactly the paths
                // that matter and the view stayed up forever. 0 (either source) still
                // means "until explicitly hidden".
                try { autoHide = com.overdrive.app.config.UnifiedConfigManager.getCamViewAutoHideSec(); }
                catch (Throwable ignored) {}
                try { if (p.containsKey("autoHide")) autoHide = Integer.parseInt(p.get("autoHide")); }
                catch (NumberFormatException ignored) {}
                // v40 contract: persist the requested rect first, then resolve it from
                // the same config path the live lane has always used. A non-null return
                // means the persist FAILED — the requested geometry must then ride this
                // session as the fail-open override (installed below), or the view
                // renders at the stale persisted position while this API reports success.
                failedGeo = persistCamViewGeometry(p, target);
                try {
                    java.util.Map<String, Object> cvVals = new java.util.HashMap<>();
                    cvVals.put("enabled", true);
                    cvVals.put("mode", mode);
                    cvVals.put("target", target);
                    if (autoHide > 0) cvVals.put("autoHideSec", autoHide);
                    // False-return contract (updateValues): a failed intent persist no
                    // longer kills the deferred retry (its validity is session-scoped,
                    // not config-scoped), but leave a trace — the persisted state is
                    // stale until the next successful show/hide write.
                    if (!com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(cvVals)) {
                        CameraDaemon.log("camview show: intent persist returned false"
                            + " — proceeding (retry is session-scoped), config stale");
                    }
                } catch (Throwable ignored) {}
                // Fail-open contract: when the atomic geometry persist failed, install
                // the REQUESTED geometry as this session's override so the resolver
                // renders where the caller asked. Otherwise clear any stale override so
                // the resolver reads the (fresh) persisted config. The override is
                // session-scoped — endCamViewSessionLocked clears it on hide/takeover.
                if (failedGeo != null) {
                    pipeline.setCamViewGeometryOverride(target, failedGeo);
                } else {
                    pipeline.clearCamViewGeometryOverride();
                }
            }
            // Cold-start pano if needed (same async dedup as BS). Moved BELOW the intent
            // persist and wired to the SAME retry as the deferral case below: this branch
            // used to return "pano_starting" with the identical never-honoured re-poll
            // contract, so a key press during a cold start was the very same silent
            // no-op. Now the request converges once the pipeline comes up.
            if (!ensurePanoStartedNonBlocking(pipeline)) {
                startCamViewArmRetry(mode, target, autoHide, showSession);
                JSONObject pending = new JSONObject();
                pending.put("success", false); pending.put("starting", true);
                pending.put("error", "Pipeline starting — retrying");
                pending.put("errorCode", "pano_starting");
                pending.put("retrying", true);
                HttpResponse.sendJson(out, pending.toString());
                return true;
            }
            boolean armed;
            try {
                armed = pipeline.enableCamView(mode, target, autoHide, showSession);
            } catch (GpuSurveillancePipeline.BlindSpotNotReadyException nr) {
                // The lane isn't armable YET (pano still cold-starting, or another
                // program's build is in flight). The contract was "caller must re-poll",
                // but NO caller does: the automation action and every key-mapping binding
                // are single-shot API calls, so a key press landing in this window did
                // nothing at all — silently, with no view, no ✕ and no toast. Since the
                // request is already persisted above, converge it here on a bounded
                // background retry so a single fire-and-forget call still lands.
                startCamViewArmRetry(mode, target, autoHide, showSession);
                JSONObject pending = new JSONObject();
                pending.put("success", false); pending.put("starting", true);
                pending.put("error", "Camera-view lane arming — retrying");
                pending.put("errorCode", "camview_starting");
                // Tell callers a retry is already running so they don't need to poll.
                pending.put("retrying", true);
                HttpResponse.sendJson(out, pending.toString());
                return true;
            }
            if (!armed) {
                // This request's session was superseded between the mint above and the
                // arm (a hide or a newer show won). Report it as CANCELLED — reading
                // isCamViewActive() here would attribute the NEWER session's state to
                // this request.
                JSONObject sup = new JSONObject();
                sup.put("success", false);
                sup.put("superseded", true);
                sup.put("error", "Request superseded by a newer show or hide");
                HttpResponse.sendJson(out, sup.toString());
                return true;
            }
            JSONObject r = new JSONObject();
            r.put("success", pipeline.isCamViewActive());
            r.put("active", pipeline.isCamViewActive());
            r.put("mode", mode); r.put("target", target);
            // A camera request remains active while an allowed blind-spot card owns the
            // shared lane. Return the immediate render state so callers can distinguish
            // a valid deferred request from a failed request without polling first.
            r.put("rendering", pipeline.isCamViewRendering());
            r.put("maskedByBlindSpot", pipeline.isCamViewMaskedByBlindSpot());
            // Honest persistence report: when a geometry was requested but the UCM
            // write failed, THIS session still renders it (fail-open override), but
            // the saved position is stale — the next boot/daemon restart resolves the
            // old rect. Callers that care (settings UI) can re-issue or surface it.
            if (p.containsKey("preset") || p.containsKey("geometry")) {
                r.put("geometryPersisted", failedGeo == null);
            }
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        HttpResponse.sendJsonError(out, "unknown camview endpoint");
        return true;
    }

    /**
     * Persist a size-only normal camera-view geometry update, retaining the existing
     * corner (or the centered default). This is deliberately separate from
     * {@code /api/camview/show}: a resize binding must not select a new camera, reset
     * its timeout, or re-show a view the user has hidden.
     */
    private static void handleCamViewGeometry(String clean, String query,
                                               GpuSurveillancePipeline pipeline,
                                               OutputStream out) throws Exception {
        final String prefix = "/api/camview/geometry/size/";
        if (!clean.startsWith(prefix) || clean.length() == prefix.length()) {
            HttpResponse.sendJsonError(out, "geometry must be /size/{percent}");
            return;
        }
        String rawSize = clean.substring(prefix.length());
        if (rawSize.indexOf('/') >= 0) {
            HttpResponse.sendJsonError(out, "geometry must be /size/{percent}");
            return;
        }
        int sizePct;
        try {
            sizePct = Integer.parseInt(rawSize);
        } catch (NumberFormatException e) {
            HttpResponse.sendJsonError(out, "size must be a whole number between 15 and 90");
            return;
        }
        if (sizePct < 15 || sizePct > 90) {
            HttpResponse.sendJsonError(out, "size must be between 15 and 90 percent");
            return;
        }

        java.util.Map<String, String> params = parseQuery(query);
        String target;
        try {
            target = com.overdrive.app.config.UnifiedConfigManager.getCamViewTarget();
        } catch (Throwable ignored) {
            target = "head_unit";
        }
        if (!"cluster".equals(target)) target = "head_unit";
        if (params.containsKey("target")) {
            String requested = params.get("target");
            if (!"head_unit".equals(requested) && !"cluster".equals(requested)) {
                HttpResponse.sendJsonError(out, "target must be head_unit or cluster");
                return;
            }
            target = requested;
        }

        String geometryKey = "cluster".equals(target) ? "geometryCluster" : "geometry";
        org.json.JSONObject geometry;
        try {
            org.json.JSONObject camView =
                    com.overdrive.app.config.UnifiedConfigManager.getCamView();
            org.json.JSONObject existing =
                    camView != null ? camView.optJSONObject(geometryKey) : null;
            geometry = existing != null
                    ? new org.json.JSONObject(existing.toString()) : new org.json.JSONObject();
            geometry.put("sizePct", sizePct);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "failed to prepare camera-view geometry");
            return;
        }

        if (!com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(
                java.util.Collections.singletonMap(geometryKey, (Object) geometry))) {
            HttpResponse.sendJsonError(out, "failed to persist camera-view geometry");
            return;
        }

        // A size-only update is safe to apply immediately only for the normal camera
        // program on this target. The pipeline leaves blind-spot's shared rect alone
        // while it owns the lane, then adopts the persisted size on handoff.
        if (pipeline != null) pipeline.setCamViewGeometrySize(sizePct, target);

        org.json.JSONObject response = new org.json.JSONObject();
        response.put("success", true);
        response.put("sizePct", sizePct);
        response.put("target", target);
        HttpResponse.sendJson(out, response.toString());
    }

    /** Map a cam token to a scaler view mode: all/mosaic=0, front=1, right=2,
     *  rear=3, left=4, plus the blind-spot composite views 7 (left) / 8 (right).
     *  A raw int 0-4 or 7-8 is accepted; default 0 (all-4).
     *
     *  <p>7/8 render through the SAME shader path the blind-spot card uses, so they
     *  honour blindspot.mergeMode (rear+side stitch / side-only / rear-only), the
     *  fisheye dewarp and the per-side rotation — one shared settings set, no camview
     *  duplicates. View 5/6 stay unreachable here: 5 is a debug raw passthrough and 6
     *  is the OEM-dashcam texture, neither of which is a camera view. */
    private static int parseCamMode(String cam) {
        if (cam == null) return 0;
        String c = cam.trim().toLowerCase();
        switch (c) {
            case "all": case "mosaic": case "all4": case "0": return 0;
            case "front": case "1": return 1;
            case "right": case "2": return 2;
            case "rear": case "3": return 3;
            case "left": case "4": return 4;
            // Blind-spot composite, explicit side (no turn-signal dependency).
            case "side_rear_left": case "bs_left": case "7": return 7;
            case "side_rear_right": case "bs_right": case "8": return 8;
            default:
                try {
                    int v = Integer.parseInt(c);
                    return isCamViewMode(v) ? v : 0;
                }
                catch (NumberFormatException e) { return 0; }
        }
    }

    /** Scaler view modes a camera view may select: the plain feeds (0-4) and the
     *  blind-spot composites (7/8). Single source of truth for every clamp. */
    public static boolean isCamViewMode(int mode) {
        return (mode >= 0 && mode <= 4) || mode == 7 || mode == 8;
    }

    /**
     * The on-screen anchors a preset may name. Anything else is not a position, and must be
     * REJECTED rather than stored: the corner decoders derive left/right and top/bottom by
     * {@code endsWith("r")} / {@code startsWith("b")}, so an unrecognised string silently reads as
     * neither-right-nor-bottom — i.e. TOP-LEFT. That is how a "top right" selection rendered top
     * left (field report, fixed 2026-08). Kept as the single source of truth for every ingest path.
     */
    private static final java.util.Set<String> VALID_CORNERS =
        java.util.Set.of("center", "tl", "tr", "bl", "br");

    /**
     * Normalise a corner token from a query/path, or null when it names no known anchor.
     * Tolerant of case and surrounding whitespace (a hand-written URL or an automation payload may
     * carry "TR"); strict about the vocabulary itself, so a typo can't become a silent top-left.
     */
    static String normalizeCorner(String raw) {
        if (raw == null) return null;
        String c = raw.trim().toLowerCase();
        return VALID_CORNERS.contains(c) ? c : null;
    }

    /** The corner currently persisted under {@code geomKey} in the camview section, canonicalised,
     *  or null when none is stored. Used to carry a position forward across a preset write that
     *  replaces the whole geometry object. */
    private static String storedCamViewCorner(String geomKey) {
        try {
            JSONObject cv = com.overdrive.app.config.UnifiedConfigManager.getCamView();
            JSONObject g = (cv != null) ? cv.optJSONObject(geomKey) : null;
            return (g != null) ? normalizeCorner(g.optString("corner", null)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Persist camview geometry from the query (preset=sizePct/corner OR
     *  geometry=x/y/w/h) into the per-target geometry key. No-op if neither given.
     *
     *  @return the requested geometry object when it could NOT be persisted — the
     *          caller must install it as the session's fail-open override
     *          ({@code setCamViewGeometryOverride}), or the session silently renders
     *          the STALE persisted position while the API reports success. Null when
     *          no geometry was requested, the request was unparseable, or the
     *          persist succeeded (the resolver then reads the fresh config). */
    private static JSONObject persistCamViewGeometry(java.util.Map<String, String> p, String target) {
        String geomKey = "cluster".equals(target) ? "geometryCluster" : "geometry";
        JSONObject geo = null;
        try {
            if (p.containsKey("preset")) {
                String[] parts = p.get("preset").split("/");
                if (parts.length >= 1) {
                    geo = new JSONObject();
                    geo.put("sizePct", Integer.parseInt(parts[0].trim()));
                    // Store only a RECOGNISED corner — an unknown token would later decode as
                    // top-left (see VALID_CORNERS).
                    //
                    // On an unknown token we must CARRY FORWARD the stored corner, not omit the
                    // key: `geo` is a fresh object and setCamViewValues replaces the whole
                    // geometry object, so omitting it DELETES the user's saved position. That
                    // showed up as a delayed, unattributable move — the view kept its place this
                    // boot (from the in-memory field) and silently jumped to centre after the
                    // next daemon restart.
                    if (parts.length >= 2) {
                        String corner = normalizeCorner(parts[1]);
                        if (corner == null) {
                            CameraDaemon.log("camview preset: unknown corner '"
                                + parts[1].trim() + "' (expected center|tl|tr|bl|br) — keeping"
                                + " the stored position");
                            corner = storedCamViewCorner(geomKey);
                        }
                        if (corner != null) geo.put("corner", corner);
                    } else {
                        // Size-only preset: preserve the stored corner for the same reason.
                        String kept = storedCamViewCorner(geomKey);
                        if (kept != null) geo.put("corner", kept);
                    }
                }
            } else if (p.containsKey("geometry")) {
                String[] parts = p.get("geometry").split("/");
                if (parts.length == 4) {
                    geo = new JSONObject();
                    geo.put("x", Integer.parseInt(parts[0].trim()));
                    geo.put("y", Integer.parseInt(parts[1].trim()));
                    geo.put("w", Integer.parseInt(parts[2].trim()));
                    geo.put("h", Integer.parseInt(parts[3].trim()));
                    // Keep the stored corner even though this write is absolute: the object is
                    // REPLACED wholesale, so dropping it meant a later size-only write resolved
                    // with no corner and fell back to the centred default, silently moving a card
                    // the user had placed. sizePct is deliberately not carried — an absolute rect
                    // supersedes the preset, and the resolver prefers sizePct when present.
                    String kept = storedCamViewCorner(geomKey);
                    if (kept != null) geo.put("corner", kept);
                }
            }
        } catch (Throwable t) {
            // Unparseable request — nothing valid to persist OR to fail-open on.
            CameraDaemon.log("persistCamViewGeometry: parse: " + t.getMessage());
            return null;
        }
        if (geo == null) return null;
        try {
            // setCamViewValues reports failure by RETURNING FALSE (updateValues), not
            // by throwing — a catch-only guard silently swallowed every real persist
            // failure and the fail-open contract never engaged.
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(
                java.util.Collections.singletonMap(geomKey, (Object) geo));
            if (ok) return null;   // persisted — the resolver reads the fresh config
            CameraDaemon.log("persistCamViewGeometry: persist returned false"
                + " — caller must fail-open on the requested geometry");
            return geo;
        } catch (Throwable t) {
            CameraDaemon.log("persistCamViewGeometry: persist FAILED (" + t.getMessage()
                + ") — caller must fail-open on the requested geometry");
            return geo;
        }
    }

    /** Minimal query-string parser (k=v&k2=v2) → map, percent-decoding each value.
     *  Decoding matters for {@code preset=25%2Ftr}: a client that correctly escapes the
     *  separator would otherwise leave the corner glued to the size and lose the position.
     *  A malformed escape is kept verbatim rather than dropping the pair. */
    private static java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                if (v.indexOf('%') >= 0 || v.indexOf('+') >= 0) {
                    try {
                        v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Throwable ignored) { /* not a valid escape — use the raw value */ }
                }
                m.put(k, v);
            }
        }
        return m;
    }

    /** POST /api/bs/target/{head_unit|cluster} — set the blind-spot display target.
     *  Persists to UCM blindspot.target and live-retargets the layer (restoring the
     *  gauges if flipping away from the cluster). The web UI also writes target via
     *  the unified-settings POST; this route is for native/instant retarget. */
    private static void handleBsTarget(OutputStream out, String raw) throws Exception {
        String t = raw;
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (!"head_unit".equals(t) && !"cluster".equals(t)) {
            HttpResponse.sendJsonError(out, "target must be head_unit or cluster");
            return;
        }
        com.overdrive.app.config.UnifiedConfigManager.setBlindSpotTarget(t);
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null && pipeline.isBlindSpotEnabled()) pipeline.retargetBlindSpot();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("target", t);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bs/tweak?fisheye=40&cameras=side&rotation=90&side=left — adjust the
     * blind-spot card's view knobs from an automation or a hardware key.
     *
     * <p>Every parameter is optional and applied independently, so one call can set any
     * subset. All of them persist to the {@code blindspot} UCM section AND push live to
     * the running lane, exactly as the settings page does — the card changes mid-signal
     * with no ACC cycle.
     * <ul>
     *   <li>{@code fisheye} 0..100 — lens dewarp for the single-camera views. Also
     *       accepts {@code up}/{@code down} to step by {@link #BS_FISHEYE_STEP}, so one
     *       button can nudge it repeatedly.</li>
     *   <li>{@code cameras} both|side|rear — which camera(s) fill the card.</li>
     *   <li>{@code rotation} 0|90|180|270|auto — on-screen quarter turn, with
     *       {@code side} = left|right|both choosing which mirror-imaged camera it
     *       applies to (default both). Only honoured in the single-camera modes; the
     *       daemon gates that, same as the settings UI.</li>
     *   <li>{@code enabled} true|false — the feature master switch. Persists
     *       {@code blindspot.enabled} and arms/disarms the native lane, reconciling the
     *       camera profile either way (an enable that makes BS the sole consumer drops
     *       to the BS-only profile; a disable restores the no-owner baseline).</li>
     * </ul>
     *
     * <p>Rejects the WHOLE request on an unparseable value rather than silently
     * applying the half it understood — a binding that sets "side + 90°" must not land
     * as a bare mode switch and leave the card sideways.
     */
    private static void handleBsTweak(OutputStream out, String path) throws Exception {
        int q = path.indexOf('?');
        java.util.Map<String, String> p = parseQuery(q >= 0 ? path.substring(q + 1) : "");
        java.util.Map<String, Object> delta = new java.util.LinkedHashMap<>();

        // ── Fisheye: absolute 0..100, or a relative up/down step off the STORED value ──
        // A relative step is resolved LATER, inside the config lock, so two fast presses
        // can't both read the same value and lose a step. 0 = absolute, ±1 = step.
        int fisheyeStepDir = 0;
        Integer fisheye = null;
        String rawFish = p.get("fisheye");
        if (rawFish != null) {
            String f = rawFish.trim().toLowerCase();
            if ("up".equals(f) || "down".equals(f)) {
                fisheyeStepDir = "up".equals(f) ? 1 : -1;
            } else {
                try { fisheye = Integer.parseInt(f); }
                catch (NumberFormatException e) {
                    HttpResponse.sendJsonError(out, "fisheye must be 0-100, 'up' or 'down'");
                    return;
                }
                if (fisheye < 0 || fisheye > 100) {
                    HttpResponse.sendJsonError(out, "fisheye must be 0-100, 'up' or 'down'");
                    return;
                }
                delta.put("rectifyStrength", fisheye);
            }
        }

        // ── Cameras shown (merge mode) ──
        String cameras = p.get("cameras");
        if (cameras != null) {
            cameras = cameras.trim().toLowerCase();
            if (!"both".equals(cameras) && !"side".equals(cameras) && !"rear".equals(cameras)) {
                HttpResponse.sendJsonError(out, "cameras must be both, side or rear");
                return;
            }
            delta.put("mergeMode", cameras);
        }

        // ── Per-side card rotation. "auto" stores the string the daemon's resolver
        //    already understands; a fixed angle must be an exact quarter turn. ──
        String rotation = p.get("rotation");
        if (rotation != null) {
            String r = rotation.trim().toLowerCase();
            String sideSel = p.containsKey("side") ? p.get("side").trim().toLowerCase() : "both";
            if (!"left".equals(sideSel) && !"right".equals(sideSel) && !"both".equals(sideSel)) {
                HttpResponse.sendJsonError(out, "side must be left, right or both");
                return;
            }
            Object rotVal;
            if ("auto".equals(r)) {
                rotVal = "auto";
            } else {
                int deg;
                try { deg = Integer.parseInt(r); }
                catch (NumberFormatException e) {
                    HttpResponse.sendJsonError(out, "rotation must be 0, 90, 180, 270 or 'auto'");
                    return;
                }
                if (deg != 0 && deg != 90 && deg != 180 && deg != 270) {
                    HttpResponse.sendJsonError(out, "rotation must be 0, 90, 180, 270 or 'auto'");
                    return;
                }
                rotVal = deg;
            }
            // Write the PER-SIDE keys only. The legacy global "rotation" is just the
            // fallback resolveBsRotation uses when a side key is absent, so touching it
            // would silently change the other side too.
            if (!"right".equals(sideSel)) delta.put("rotationLeft", rotVal);
            if (!"left".equals(sideSel))  delta.put("rotationRight", rotVal);
        }

        // ── Feature master switch ──
        Boolean enable = null;
        String rawEnabled = p.get("enabled");
        if (rawEnabled != null) {
            String e = rawEnabled.trim().toLowerCase();
            // Strict parse: Boolean.parseBoolean maps every typo to false, which would
            // silently DISABLE a safety view a binding meant to turn on.
            if ("true".equals(e) || "1".equals(e) || "on".equals(e)) enable = Boolean.TRUE;
            else if ("false".equals(e) || "0".equals(e) || "off".equals(e)) enable = Boolean.FALSE;
            else {
                HttpResponse.sendJsonError(out, "enabled must be true or false");
                return;
            }
            delta.put("enabled", enable);
        }

        if (delta.isEmpty() && fisheyeStepDir == 0) {
            HttpResponse.sendJsonError(out,
                "no recognised parameter (fisheye, cameras, rotation, enabled)");
            return;
        }

        // Persist first (single merge on the section), then push live. Persisting first
        // means a lane that isn't up yet still picks the values up on its next enable
        // via applyBlindSpotCalibration.
        //
        // A relative fisheye step reads-and-writes INSIDE the config lock (the lock is
        // per-thread reentrant, so setBlindSpotValues' own acquire just nests), so two
        // overlapping presses serialise into two real steps instead of both reading the
        // same value and landing on one. The resolved value is published for the response
        // and the live push below.
        final int stepDir = fisheyeStepDir;
        final Integer[] resolvedFisheye = new Integer[1];
        boolean saved;
        if (stepDir != 0) {
            saved = Boolean.TRUE.equals(
                com.overdrive.app.config.UnifiedConfigManager.runUnderConfigLock(() -> {
                    int cur = 0;
                    try {
                        cur = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot()
                                .optInt("rectifyStrength", 0);
                    } catch (Throwable ignored) {}
                    int stepped = Math.max(0, Math.min(100,
                        cur + stepDir * BS_FISHEYE_STEP));
                    resolvedFisheye[0] = stepped;
                    delta.put("rectifyStrength", stepped);
                    return com.overdrive.app.config.UnifiedConfigManager.setBlindSpotValues(delta);
                }));
        } else {
            saved = com.overdrive.app.config.UnifiedConfigManager.setBlindSpotValues(delta);
        }
        if (!saved) {
            HttpResponse.sendJsonError(out, "failed to persist blind-spot settings");
            return;
        }

        // Each push is independently guarded, mirroring the unified-settings dispatch: a
        // shared try would let one knob's failure skip the remaining pushes. A knob that
        // throws is NAMED in pushFailed and reported, so the caller never reads "applied"
        // for a value that only reached the config file.
        java.util.List<String> pushFailed = new java.util.ArrayList<>();
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null) {
            if (delta.containsKey("rectifyStrength")) {
                try {
                    pipeline.setBlindSpotRectifyStrength((Integer) delta.get("rectifyStrength"));
                } catch (Throwable t) {
                    CameraDaemon.log("handleBsTweak fisheye push: " + t.getMessage());
                    pushFailed.add("fisheye");
                }
            }
            if (delta.containsKey("mergeMode")) {
                try {
                    pipeline.setBlindSpotMergeMode(
                        "side".equals(cameras) ? 1 : ("rear".equals(cameras) ? 2 : 0));
                } catch (Throwable t) {
                    CameraDaemon.log("handleBsTweak merge-mode push: " + t.getMessage());
                    pushFailed.add("cameras");
                }
            }
            // A merge-mode flip changes whether the stored angle applies at all, so
            // refresh rotation for that too — not just for an explicit rotation edit.
            if (delta.containsKey("mergeMode") || delta.containsKey("rotationLeft")
                    || delta.containsKey("rotationRight")) {
                try {
                    pipeline.refreshBlindSpotRotation();
                } catch (Throwable t) {
                    CameraDaemon.log("handleBsTweak rotation push: " + t.getMessage());
                    pushFailed.add("rotation");
                }
            }
        }

        // Arm/disarm LAST, so a call that both enables the feature and sets its view
        // knobs has the knobs already persisted when the lane comes up and reads them.
        // Mirrors the unified-settings enable dispatch: resolveBlindSpotLifecycle() to
        // arm (idempotent; cold-starts pano itself), disableBlindSpot() to tear down,
        // then reconcile the camera profile either way so the camera isn't stranded at
        // a lane-OFF/~1fps profile with no owner.
        if (enable != null) {
            try {
                if (enable) {
                    resolveBlindSpotLifecycle();
                } else if (pipeline != null) {
                    pipeline.disableBlindSpot();
                }
            } catch (Throwable t) {
                CameraDaemon.log("handleBsTweak enable dispatch: " + t.getMessage());
                pushFailed.add("enabled");
            }
            // Camera-profile reconcile is a SEPARATE try and never marks the request
            // failed: the arm/disarm above is what the caller asked for, and reporting
            // "could not apply" for a lane that is up would have the UI refuse to paint
            // a state that did take effect (and any retry re-toggle a working lane).
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    CameraDaemon.getRecordingModeManager();
                if (rmm != null) rmm.onPipelineStartedExternally();
            } catch (Throwable t) {
                CameraDaemon.log("handleBsTweak reconcile: " + t.getMessage());
            }
        }

        // Values are persisted either way (they apply on the lane's next enable), but a
        // failed live push is NOT "applied" — report it so a binding/rule and the web UI
        // don't paint a change the running view never took.
        JSONObject response = new JSONObject();
        response.put("success", pushFailed.isEmpty());
        for (java.util.Map.Entry<String, Object> e : delta.entrySet()) {
            response.put(e.getKey(), e.getValue());
        }
        if (!pushFailed.isEmpty()) {
            response.put("persisted", true);
            response.put("pushFailed", new JSONArray(pushFailed));
            response.put("error", "saved, but could not apply live: "
                + String.join(", ", pushFailed));
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Step size for the relative {@code fisheye=up|down} tweak, in slider units. */
    private static final int BS_FISHEYE_STEP = 10;

    private static void handleBsEnable(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        // Cold-start the pano pipeline async (same dedup as the stream path);
        // the overlay re-polls /api/bs/enable until running.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        try {
            // NATIVE path: arming the lane creates the daemon SurfaceControl layer
            // + repoints the BS scaler onto it (GPU → screen). The daemon's own
            // turn-trigger loop shows/hides it; no app-process decoder/WS involved.
            pipeline.enableBlindSpot(pipeline.getBlindSpotViewMode());
            // BS-DEFECT-A: enableBlindSpot() can return VOID without arming the lane
            // when a concurrent enable is mid-flight (its `if (bsEnabling) return;`
            // early-return). A void return is indistinguishable from success to the
            // try/catch above, so reporting success here would falsely tell the app's
            // re-arm loop the lane is up — it stops driving while blindSpotEnabled is
            // still false and 8889 is dead (the observed NO-VIDEO flap). Re-check the
            // lane state and only claim success when it ACTUALLY armed; otherwise reply
            // {starting:true} so BlindSpotControl.armWithRetry keeps re-POSTing enable
            // until the in-flight enable commits the lane. Convergent: no false success.
            if (!pipeline.isBlindSpotEnabled()) {
                CameraDaemon.log("handleBsEnable: enable returned but lane not armed yet"
                    + " (concurrent enable in flight) — reporting starting so caller re-polls");
                JSONObject pending = new JSONObject();
                pending.put("success", false);
                pending.put("starting", true);
                pending.put("error", "Blind-spot lane arming — try again in a few seconds");
                pending.put("errorCode", "bs_starting");
                HttpResponse.sendJson(out, pending.toString());
                return;
            }
            // BS just armed. If the pano was ALREADY running (so the cold-start
            // path's onPipelineStartedExternally did NOT fire), the camera may
            // still be at a full recording/idle profile that doesn't reflect the
            // new BS-only consumer. Reconcile now so a BS-enable while the pipeline
            // is up (e.g. a live-view stream kept it warm, mode=NONE) drops the
            // recorder lane / parks fps at the BS profile. Idempotent + no-op when
            // a recording mode owns the camera. (Covers audit finding: runtime
            // BS-enable while pano already running skipped reconcile.)
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    CameraDaemon.getRecordingModeManager();
                if (rmm != null) rmm.onPipelineStartedExternally();
            } catch (Throwable t) {
                CameraDaemon.log("handleBsEnable reconcile: " + t.getMessage());
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("native", true);
            response.put("view", pipeline.getBlindSpotViewMode());
            response.put("width", 1280);
            response.put("height", 960);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("handleBsEnable: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    private static void handleBsDisable(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null) {
            try { pipeline.disableBlindSpot(); } catch (Throwable t) {
                CameraDaemon.log("handleBsDisable: " + t.getMessage());
            }
            // Reconcile the camera profile now. If BS was the SOLE consumer and
            // its view was already hidden, disableBlindSpot() fires no visibility
            // EDGE (bsLayerVisible was already false), so the camera would
            // otherwise be stranded at the BS-only profile (recorder lane OFF,
            // global fps ~1) with no owner — a live view opened afterward would
            // render at 1fps. Driving a reconcile here lands the camera in the
            // no-owner baseline (lane ON + recording/stream fps). Idempotent +
            // no-op when a recording mode owns the camera. (The blindspot.enabled
            // flag is already cleared by the app before this POST, so
            // bsKeepWarmActive() is false and reconcile won't re-park it as BS.)
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    CameraDaemon.getRecordingModeManager();
                if (rmm != null) rmm.onPipelineStartedExternally();
            } catch (Throwable t) {
                CameraDaemon.log("handleBsDisable reconcile: " + t.getMessage());
            }
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bs/hide — DISMISS the currently-showing blind-spot card (the floating
     * ✕ tap). Unlike {@link #handleBsDisable} this does NOT disable the feature: the
     * lane stays armed and the next turn signal shows the card again. Scoped to the
     * current display session; a calibration preview is turned off instead.
     *
     * <p>{@code dismissed} reports whether a card was actually showing. The overlay uses
     * it to decide whether to restore its ✕ (a tap that raced the card auto-hiding gets
     * dismissed:false and the reconcile removes the button on the next poll anyway).
     */
    private static void handleBsHide(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        boolean dismissed = false;
        if (pipeline != null) {
            try { dismissed = pipeline.dismissBlindSpotCard(); }
            catch (Throwable t) { CameraDaemon.log("handleBsHide: " + t.getMessage()); }
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("dismissed", dismissed);
        HttpResponse.sendJson(out, response.toString());
    }

    // ── Blind-spot daemon-side self-arm ─────────────────────────────────────
    // The app arms the BS lane by POSTing /api/bs/enable (BlindSpotControl.sync,
    // fired from MainActivity / BootReceiver on the com.byd.action.ACC_ON
    // broadcast EDGE). On a HARD REBOOT the car's ACC is already ON, so that
    // broadcast edge fired before the app's receiver existed — the app never
    // gets it, never calls sync(), and the lane stays un-armed until the user
    // manually opens the debug-preview menu (the only other sync() trigger).
    // The daemon, however, learns ACC=ON from its own hardware probe + the
    // AccSentry IPC, so it CAN self-arm. This mirrors the OEM-dashcam
    // edge-only-lifecycle fix (pano-ready hook + 30s self-heal ticker): the
    // resolver is idempotent (no-op once the lane is live), so re-driving it is
    // safe. Gated on blindspot.enabled so a disabled feature never arms.
    private static final long BS_SELF_HEAL_INTERVAL_MS = 30_000L;
    private static volatile Thread bsSelfHealThread;
    private static volatile boolean bsSelfHealRunning;

    /**
     * Bring the BS lane to its desired state from the daemon side, independent
     * of any app-side POST. Arms iff blindspot.enabled is set AND ACC is on AND
     * the pano pipeline is running; idempotent (enableBlindSpot no-ops once the
     * lane is live). Safe to call from any thread — enableBlindSpot serializes
     * on its own lifecycle lock. Never disables: app-driven disable (feature
     * toggled off) flows through handleBsDisable, and gating the arm on the
     * enabled flag means we simply don't re-arm a feature the user turned off.
     */
    public static void resolveBlindSpotLifecycle() {
        try {
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            boolean enabled = bs.optBoolean("enabled", false)
                || bs.optBoolean("debugPreview", false);
            if (!enabled) return;
            if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) return;
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null) return;
            if (pipeline.isBlindSpotEnabled()) return;   // already armed — no-op
            if (!pipeline.isRunning()) {
                // Pano not up yet. Kick a cold-start; the pano-ready hook in
                // GpuSurveillancePipeline.start() re-drives this resolver once
                // running=true, and the 30s ticker is the backstop.
                ensurePanoStartedNonBlocking(pipeline);
                return;
            }
            CameraDaemon.log("BS self-arm: enabled+accOn+pano-running but lane not armed — arming");
            pipeline.enableBlindSpot(pipeline.getBlindSpotViewMode());
        } catch (Throwable t) {
            CameraDaemon.log("BS self-arm: resolve failed: " + t.getMessage());
        }
    }

    /**
     * Start the periodic BS self-heal ticker. Idempotent — a second call while
     * the thread is alive is a no-op. Call once at daemon boot. Bounds the
     * cold-reboot arm latency to ~30s even if every edge is missed.
     */
    public static synchronized void startBsSelfHealTicker() {
        if (bsSelfHealThread != null && bsSelfHealThread.isAlive()) return;
        bsSelfHealRunning = true;
        bsSelfHealThread = new Thread(() -> {
            CameraDaemon.log("BS self-heal ticker started (" + (BS_SELF_HEAL_INTERVAL_MS / 1000) + "s interval)");
            while (bsSelfHealRunning) {
                try {
                    Thread.sleep(BS_SELF_HEAL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    if (!bsSelfHealRunning) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                if (!bsSelfHealRunning) return;
                resolveBlindSpotLifecycle();
            }
        }, "BlindSpotSelfHeal");
        bsSelfHealThread.setDaemon(true);
        bsSelfHealThread.start();
    }

    private static void handleBsStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        JSONObject response = new JSONObject();
        response.put("success", true);
        boolean enabled = pipeline != null && pipeline.isBlindSpotEnabled();
        response.put("enabled", enabled);
        response.put("running", pipeline != null && pipeline.isRunning());
        response.put("view", pipeline != null ? pipeline.getBlindSpotViewMode() : 7);
        response.put("native", true);
        // Report the PERSISTED target, not the pipeline's in-memory bsTarget field
        // (which stays at its head_unit default until the lane is enabled and
        // resolveBsGeometry runs — so it would diverge from UCM in the pre-enable
        // window). bsTarget is just a cache of blindspot.target; UCM is authoritative.
        // forceReload honors cross-UID freshness (web/app write, daemon reads).
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        response.put("target", com.overdrive.app.config.UnifiedConfigManager.getBlindSpotTarget());
        // Conditional-display gate (speed window / reverse). "gateAllowed" is the live
        // verdict as of the last turn tick; "gateReason" names the blocking condition so
        // the settings page can explain a card that legitimately isn't appearing.
        if (pipeline != null) {
            response.put("gateAllowed", pipeline.isBsGateAllowed());
            response.put("gateReason", pipeline.getBsGateReason());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET/POST /api/bs/geometry[/x/y/w/h] — set the on-screen rect (panel pixels)
     * of the native SurfaceControl blind-spot layer. SurfaceControl layers have no
     * input channel, so position/size are config-driven (RoadSense settings UI),
     * not finger-drag. Persists to UCM blindspot.geometry so it survives restart.
     */
    private static void handleBsGeometry(OutputStream out, String path) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        // Forms: /api/bs/geometry/{x}/{y}/{w}/{h}  (absolute px)
        //   or:  /api/bs/geometry/preset/{sizePct}/{corner}  (size + position)
        //   or:  /api/bs/geometry/size/{sizePct}  (size only; preserves positions)
        // Optional ?target=head_unit|cluster scopes which target's geometry is set;
        // defaults to the currently-active target.
        String target = com.overdrive.app.config.UnifiedConfigManager.getBlindSpotTarget();
        int q = path.indexOf("?target=");
        if (q >= 0) {
            String tq = path.substring(q + "?target=".length());
            int amp = tq.indexOf('&'); if (amp >= 0) tq = tq.substring(0, amp);
            if ("cluster".equals(tq) || "head_unit".equals(tq)) target = tq;
            path = path.substring(0, q);
        }
        boolean cluster = "cluster".equals(target);
        String geomKey = cluster ? "geometryCluster" : "geometry";
        String tail = path.length() > 16 ? path.substring(16) : "";
        if (tail.startsWith("/")) tail = tail.substring(1);
        if (tail.startsWith("size/")) {
            try {
                String[] p = tail.substring("size/".length()).split("/");
                if (p.length != 1) throw new IllegalArgumentException("only a percentage is allowed");
                int pct = Integer.parseInt(p[0]);
                if (pct < 15 || pct > 90) {
                    HttpResponse.sendJsonError(out, "size must be between 15 and 90 percent");
                    return;
                }
                // Keep the RoadSense-selected per-side corners intact. A size-only
                // automation must not unexpectedly move the left/right cards.
                org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                org.json.JSONObject existing = bs != null ? bs.optJSONObject(geomKey) : null;
                org.json.JSONObject geometry = existing != null
                    ? new org.json.JSONObject(existing.toString()) : new org.json.JSONObject();
                geometry.put("sizePct", pct);
                if (!com.overdrive.app.config.UnifiedConfigManager.updateSection("blindspot",
                        new org.json.JSONObject().put(geomKey, geometry))) {
                    HttpResponse.sendJsonError(out, "failed to persist blind-spot geometry");
                    return;
                }
                if (pipeline != null) pipeline.setBsGeometrySize(pct, target);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "size must be a whole number between 15 and 90");
                return;
            }
        } else if (tail.startsWith("preset/")) {
            try {
                String[] p = tail.substring("preset/".length()).split("/");
                int pct = Integer.parseInt(p[0]);
                // Reject an unknown corner instead of letting it decode as top-left (see
                // VALID_CORNERS). Absent is still the documented "tr" default.
                String corner = "tr";
                if (p.length > 1) {
                    corner = normalizeCorner(p[1]);
                    if (corner == null) {
                        HttpResponse.sendJsonError(out,
                            "corner must be one of center, tl, tr, bl, br");
                        return;
                    }
                }
                if (pipeline != null) pipeline.setBsGeometryPreset(pct, corner, target);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "preset must be /preset/{pct}/{corner}: " + e.getMessage());
                return;
            }
        } else if (!tail.isEmpty()) {
            try {
                String[] p = tail.split("/");
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int w = Integer.parseInt(p[2]);
                int h = Integer.parseInt(p[3]);
                // Persist to the target's geometry key so the daemon restores it on
                // the next enable. Shallow per-key merge — never clobbers the other
                // target's key.
                //
                // CARRY FORWARD the existing keys. updateSection replaces this whole geometry
                // object, so writing a bare {x,y,w,h} DELETED the stored corner*/sizePct. The
                // resolver prefers sizePct, so a later size-only write (which re-adds sizePct)
                // then found no corner and fell back to "tr": a user who chose bottom-left,
                // dragged the card, then nudged the size slider got a top-right card.
                org.json.JSONObject g;
                try {
                    org.json.JSONObject bsNow =
                        com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                    org.json.JSONObject existing =
                        (bsNow != null) ? bsNow.optJSONObject(geomKey) : null;
                    g = (existing != null)
                        ? new org.json.JSONObject(existing.toString()) : new org.json.JSONObject();
                } catch (Throwable t) {
                    g = new org.json.JSONObject();
                }
                // An absolute rect supersedes the preset: drop sizePct so resolveBsGeometry takes
                // the absolute branch, but KEEP the corner choices for the next preset write.
                g.remove("sizePct");
                g.put("x", x); g.put("y", y); g.put("w", w); g.put("h", h);
                com.overdrive.app.config.UnifiedConfigManager.updateSection("blindspot",
                    new org.json.JSONObject().put(geomKey, g));
                // Apply live only when editing the active target.
                if (pipeline != null && cluster == com.overdrive.app.config.UnifiedConfigManager.isBlindSpotCluster()) {
                    pipeline.setBsGeometry(x, y, w, h);
                }
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "geometry must be /x/y/w/h ints: " + e.getMessage());
                return;
            }
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        // Return the current (clamped) rect so a settings UI can reflect it.
        if (pipeline != null) {
            int[] r = pipeline.getBsGeometry();
            response.put("x", r[0]); response.put("y", r[1]);
            response.put("w", r[2]); response.put("h", r[3]);
        }
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleBsView(OutputStream out, int mode) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null || (mode != 7 && mode != 8)) {
            HttpResponse.sendJsonError(out, "blind-spot view must be 7 or 8");
            return;
        }
        // CRITICAL (DEFECT-B): never report success while the BS lane is still
        // disabled. Gate BEFORE setBlindSpotViewMode() — that setter is a no-op
        // until enableBlindSpot() has allocated bsScaler + bound the WS server on
        // BS_WS_PORT (it just snapshots a null bsScaler and returns). If the pano
        // is still cold-starting, the initial /api/bs/enable returned
        // starting:true and enableBlindSpot was never called, so the lane is
        // disabled and port 8889 is dead. Returning success here would let the
        // overlay commit streamWarmedView=mode and STOP re-driving the arming
        // loop, leaving WsH264Client reconnect-storming a dead port forever while
        // the lane stays un-armed (the observed NO-VIDEO flap). Mirror
        // handleBsEnable's cold-pano contract: reply {starting:true} so
        // confirmBsLaneAndConnect keeps re-POSTing /api/bs/enable and the overlay
        // keeps retrying selectView until the lane genuinely commits, then it
        // connects to a LIVE 8889.
        if (!pipeline.isBlindSpotEnabled()) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("view", mode);
            pending.put("error", "Blind-spot lane not yet armed — try again in a few seconds");
            pending.put("errorCode", "bs_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        pipeline.setBlindSpotViewMode(mode);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("view", mode);
        HttpResponse.sendJson(out, response.toString());
    }

    // One-time warning latch so we log the degraded-latency fallback once per
    // daemon lifetime instead of spamming logcat at the overlay's 250ms tick.
    private static volatile boolean turnFallbackWarned = false;

    /**
     * Live turn-indicator state for the blind-spot overlay. The overlay runs in
     * the APP process, which has no BYD device handles (BydDataCollector.init()
     * is daemon-only), so it can't read the turn lamps directly — it polls this
     * loopback endpoint instead. We read from the daemon's own collector (which
     * owns lightDevice) via one inline readTurnNow() per request — the overlay's
     * own 250ms tick drives the cadence; there is NO background scheduler here.
     * Returns {left,right} as ints (>0 = lamp on); -1 when state is unknown.
     *
     * FALLBACK LATENCY: on trims whose light device is unavailable, readTurnNow()
     * returns -1 and we fall back to the ~5s main snapshot (BydVehicleData). That
     * value is stale by up to 5 seconds — orders of magnitude older than the
     * 250ms poll — so on those trims the blind-spot overlay's turn trigger may
     * miss or lag a newly-activated indicator. This is a hard HAL limitation on
     * that trim (no fast turn-lamp read path exists), not a bug here. We log the
     * degradation once (turnFallbackWarned) so it's diagnosable from logs.
     */
    private static void sendTurnState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        int left = -1;
        int right = -1;
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector.isInitialized()) {
                // One inline HAL read per request — no background scheduler. The
                // overlay's own 250ms tick drives the cadence; the daemon just
                // answers each read. Packed bit0=L, bit1=R; -1 if unavailable.
                int packed = collector.readTurnNow();
                if (packed >= 0) {
                    left = (packed & 0x1) != 0 ? 1 : 0;
                    right = (packed & 0x2) != 0 ? 1 : 0;
                } else {
                    // Light device unavailable on this trim — fall back to the
                    // ~5s main snapshot's last-known lamp state. This value can
                    // be up to ~5s stale (vs the overlay's 250ms poll), so the
                    // blind-spot turn trigger has degraded latency on this trim.
                    // Warn once so the degradation is diagnosable from logs
                    // without spamming at the 250ms tick.
                    if (!turnFallbackWarned) {
                        turnFallbackWarned = true;
                        CameraDaemon.log("sendTurnState: readTurnNow() unavailable on this"
                            + " trim — blind-spot turn trigger falling back to the ~5s"
                            + " snapshot (up to ~5s latency vs the normal 250ms poll)");
                    }
                    com.overdrive.app.byd.BydVehicleData d = collector.getData();
                    if (d != null) {
                        int un = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
                        if (d.leftTurnState != un) left = d.leftTurnState;
                        if (d.rightTurnState != un) right = d.rightTurnState;
                    }
                }
            }
        } catch (Throwable t) {
            CameraDaemon.log("sendTurnState error: " + t.getMessage());
        }
        response.put("success", true);
        response.put("left", left);
        response.put("right", right);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleEnableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        CameraDaemon.log("handleEnableStreaming: pipeline=" + (pipeline != null) + 
                        ", running=" + (pipeline != null && pipeline.isRunning()));
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_initialized"));
            return;
        }
        
        // Auto-start pipeline if not running. Cold-start runs on a worker
        // thread (warmup + AVMCamera open is ~4-9s) and we return
        // starting=true so the HTTP worker thread isn't blocked. Client
        // re-polls until pipelineRunning flips true.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        
        if (pipeline.isStreamingEnabled()) {
            CameraDaemon.log("handleEnableStreaming: already enabled");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_already_enabled"));
            response.put("wsPort", 8887);
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
            CameraDaemon.log("handleEnableStreaming: quality=" + q.displayName);
            // enableStreaming fires the pipeline's streamStateListener →
            // RMM.reconcileCameraProfile, which floors the global camera fps at
            // the stream fps if the camera was parked at the BS-only idle rate.
            pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);

            CameraDaemon.log("handleEnableStreaming: success");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_enabled"));
            response.put("wsPort", 8887);
            response.put("quality", q.name());
            response.put("resolution", q.width + "x" + q.height);
            response.put("fps", q.fps);
            response.put("bitrate", q.bitrate);
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("handleEnableStreaming: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleDisableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }

        // disableStreaming fires the pipeline's streamStateListener →
        // RMM.reconcileCameraProfile, so the global camera fps drops back from the
        // stream floor to the BS idle / recording rate once the stream is gone.
        // (This path covers BOTH the HTTP DELETE here and the WS idle auto-close
        // inside the pipeline — neither strands the fps at the stream rate.)
        pipeline.disableStreaming();
        setLastDesiredViewMode(-1);

        // Once the WS pipe goes dark, the OEM-stream "keep warm" reason
        // disappears. Re-evaluate so we tear down OEM if no recording
        // mode is asking for it.
        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.streaming_disabled"));
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("pipelineRunning", pipeline != null && pipeline.isRunning());
        response.put("streamingEnabled", pipeline != null && pipeline.isStreamingEnabled());
        response.put("wsPort", 8887);
        
        if (pipeline != null && pipeline.isStreamingEnabled()) {
            response.put("viewMode", pipeline.getStreamViewMode());
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
            int vm = pipeline.getStreamViewMode();
            response.put("viewName", vm >= 0 && vm < modeNames.length ? modeNames[vm] : "Unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamQualityOptions(OutputStream out) throws Exception {
        CameraDaemon.log("sendStreamQualityOptions: current=" + streamingQuality);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("current", streamingQuality);
        
        JSONArray options = new JSONArray();
        for (GpuPipelineConfig.StreamingQuality q : GpuPipelineConfig.StreamingQuality.values()) {
            JSONObject opt = new JSONObject();
            opt.put("id", q.name());
            opt.put("name", q.displayName);
            opt.put("width", q.width);
            opt.put("height", q.height);
            opt.put("fps", q.fps);
            opt.put("bitrate", q.bitrate);
            opt.put("bitrateKbps", q.bitrate / 1000);
            options.put(opt);
        }
        response.put("options", options);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleSetStreamQuality(OutputStream out, String quality) throws Exception {
        GpuPipelineConfig.StreamingQuality newQuality = GpuPipelineConfig.StreamingQuality.fromString(quality);

        streamingQuality = newQuality.name();
        CameraDaemon.setStreamingQuality(quality);

        // Persist to UnifiedConfigManager (streaming.quality) so the choice
        // survives daemon restart. Mirrors the recording-side flow where
        // QualitySettingsApiHandler.persistSettings is the single canonical
        // writer for both recording and streaming sections — without this
        // call the in-memory `streamingQuality` field is the only record of
        // the user's pick, and a kill/restart silently reverts to whatever
        // the on-disk default seeded (MEDIUM).
        QualitySettingsApiHandler.persistSettings();

        // Save quality preference — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        // The /ws handler applies the quality when the client reconnects.
        CameraDaemon.log("Streaming quality set to: " + newQuality.displayName + " (persisted)");
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("quality", newQuality.name());
        response.put("displayName", newQuality.displayName);
        response.put("width", newQuality.width);
        response.put("height", newQuality.height);
        response.put("fps", newQuality.fps);
        response.put("bitrate", newQuality.bitrate);
        HttpResponse.sendJson(out, response.toString());
    }
    
    // Blind-spot (view 7/8) LIVE tuning (in-memory; debug editor). tail is up to
    // 10 opaque scalars: "{p0}/{p1}/{p2}/{p3}/{p4}/{p5}/{p6}/{p7}/{p8}/{p9}"
    // (trailing values optional; each defaults to its identity value).
    private static void handleBlindSpotParams(OutputStream out, String tail) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        float hfov = 1.66f, sideHFov = 1.98f, yaw = 1.23f, roll = 0.25f,
              feather = 0.38f, projExp = 1.0f, vscale = 1.0f, pitch = -0.275f,
              rearRoll = 0.0f, rearPitch = 0.0f;
        try {
            String[] p = tail.split("/");
            if (p.length > 0 && !p[0].isEmpty()) hfov      = Float.parseFloat(p[0]);
            if (p.length > 1 && !p[1].isEmpty()) sideHFov  = Float.parseFloat(p[1]);
            if (p.length > 2 && !p[2].isEmpty()) yaw       = Float.parseFloat(p[2]);
            if (p.length > 3 && !p[3].isEmpty()) roll      = Float.parseFloat(p[3]);
            if (p.length > 4 && !p[4].isEmpty()) feather   = Float.parseFloat(p[4]);
            if (p.length > 5 && !p[5].isEmpty()) projExp   = Float.parseFloat(p[5]);
            if (p.length > 6 && !p[6].isEmpty()) vscale    = Float.parseFloat(p[6]);
            if (p.length > 7 && !p[7].isEmpty()) pitch     = Float.parseFloat(p[7]);
            if (p.length > 8 && !p[8].isEmpty()) rearRoll  = Float.parseFloat(p[8]);
            if (p.length > 9 && !p[9].isEmpty()) rearPitch = Float.parseFloat(p[9]);
        } catch (NumberFormatException e) {
            HttpResponse.sendJsonError(out, "Bad blind-spot params: " + tail);
            return;
        }
        pipeline.setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                                    rearRoll, rearPitch);
        JSONObject ok = new JSONObject();
        ok.put("success", true);
        ok.put("hfov", hfov);
        ok.put("sideHFov", sideHFov);
        ok.put("yaw", yaw);
        ok.put("roll", roll);
        ok.put("feather", feather);
        ok.put("projExp", projExp);
        ok.put("vscale", vscale);
        ok.put("pitch", pitch);
        ok.put("rearRoll", rearRoll);
        ok.put("rearPitch", rearPitch);
        HttpResponse.sendJson(out, ok.toString());
    }

    private static void handleStreamViewMode(
            OutputStream out, int viewMode, ViewRequest request) throws Exception {
        // Live-view stream accepts only modes 0-6. Blind-spot views 7/8 are
        // NOT valid here — they belong to the dedicated BS pipeline on port
        // 8889 and must route through /api/bs/view/{mode} (validated at
        // handleBsView). Letting 7/8 through here would call
        // pipeline.setStreamViewMode() on the SHARED live-view scaler and
        // hijack the live-view stream.
        if (viewMode < 0 || viewMode > 6) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_invalid_view_mode"));
            return;
        }
        int previousDesiredView = getLastDesiredViewMode();
        if (!request.applyIntent(viewMode)) {
            sendSupersededViewResponse(out, viewMode);
            return;
        }
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }

        // The requested mode is persisted before any cold-start work. A
        // reconnect or lifecycle callback must see the newer intent even when
        // this request returns starting=true.
        // View mode 6 = OEM Dashcam (separate forward sensor pipeline).
        // View 5 stays the legacy raw passthrough (pano strip debug) so
        // existing tooling that pokes /api/stream/view/5 keeps working.
        // Routes the WebSocket stream to OemDashcamPipeline's encoder
        // bitstream instead of the AVM mosaic. The OEM pipeline must be
        // started separately by RecordingModeManager / Settings; we do NOT
        // auto-start it here because (a) it requires a configured
        // oemDashcamCameraId, and (b) on single-AVM-client HALs starting
        // it would yield the pano pipeline.
        if (viewMode == 6) {
            handleOemDashcamView(out, request);
            return;
        }

        // Idempotency short-circuit: if pipeline is already running,
        // streaming is already enabled, and view is already at the
        // requested mode, return success without re-running any
        // side-effecting work. Repeated identical GETs from the JS
        // poll loop should be cheap.
        if (pipeline.isRunning() && pipeline.isStreamingEnabled()
                && pipeline.getStreamViewMode() == viewMode) {
            String[] modeNamesIdem = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam",
                                      "BlindSpot L", "BlindSpot R"};
            JSONObject ok = new JSONObject();
            ok.put("success", true);
            ok.put("viewMode", viewMode);
            ok.put("viewName", viewMode < modeNamesIdem.length ? modeNamesIdem[viewMode] : "Unknown");
            HttpResponse.sendJson(out, ok.toString());
            return;
        }

        // Auto-start pipeline if not running. Cold-start is async — the
        // 4-9s warmup runs on a dedup'd worker thread and we report
        // starting=true so the JS poll loop just waits.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", viewMode);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        if (!ensureCurrentViewRequest(out, viewMode, request)) return;

        // Enable streaming first if not enabled. enableStreaming is
        // synchronous (allocates encoder + scaler on the GL thread) and
        // typically returns under 200ms, so we keep it inline.
        if (!pipeline.isStreamingEnabled()) {
            try {
                CameraDaemon.log("Enabling streaming before setting view mode");
                GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, Messages.get("errors.streaming_enable_failed_with_detail", e.getMessage()));
                return;
            }
        }
        if (!ensureCurrentViewRequest(out, viewMode, request)) return;

        // Capture the prior view BEFORE we change it so the OEM lifecycle
        // recalc only fires on transitions in/out of view 6. Pre-fix every
        // AVM quadrant click triggered a recalc, which on smart-mode arms
        // would warm the OEM camera unnecessarily for no consumer.
        int prevView = pipeline.getStreamViewMode();

        pipeline.setStreamViewMode(viewMode);
        // Blind-spot views (7=Rear+Left, 8=Right+Rear): apply the user's SAVED
        // panorama calibration from the 'blindspot' UCM section so the stitch
        // looks right without the debug editor open. forceReload first — the web
        // (different UID) just wrote it. The live /api/stream/bs path still
        // overrides this in-memory for the debug editor's slider preview.
        if (viewMode == 7 || viewMode == 8) {
            try {
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
                org.json.JSONObject bs =
                    com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                if (bs != null && bs.length() > 0) {
                    pipeline.setBlindSpotParams(
                        (float) bs.optDouble("rearFov", 1.66),
                        (float) bs.optDouble("sideFov", 1.98),
                        (float) bs.optDouble("yaw",     1.23),
                        (float) bs.optDouble("roll",    0.25),
                        (float) bs.optDouble("feather", 0.38),
                        (float) bs.optDouble("projExp", 1.0), 1.0f,
                        (float) bs.optDouble("pitch",  -0.275),
                        (float) bs.optDouble("rearRoll",  0.0),
                        (float) bs.optDouble("rearPitch", 0.0));
                    // Merge mode (both/side/rear) — apply alongside the stitch calib so
                    // a browser switching the live stream to 7/8 matches the persisted
                    // selection (and the on-car overlay), not the scaler's "both" default.
                    String mm = bs.optString("mergeMode", "both");
                    pipeline.setBlindSpotMergeMode(
                        "side".equals(mm) ? 1 : ("rear".equals(mm) ? 2 : 0));
                }
            } catch (Throwable t) {
                CameraDaemon.log("blindspot calib apply failed: " + t.getMessage());
            }
        }

        // If a prior view-6 selection swapped the WS sink to the OEM encoder,
        // restore pano now so view 0..5 actually delivers pano frames again.
        // Direct call — reflection here would have the same R8-rename failure
        // mode as the original routeStreamToOemDashcam bug (the surveillance
        // package members aren't preserved by name, so a getMethod() lookup
        // throws NoSuchMethodException in release builds).
        try {
            pipeline.reattachOwnStreamCallback();
        } catch (Throwable t) {
            CameraDaemon.log("reattachOwnStreamCallback failed: " + t.getMessage());
        }

        // Re-evaluate the OEM lifecycle ONLY on view-6 boundary crossings.
        // Switching from view 0 → view 1 doesn't change OEM's required
        // state (no streaming viewer either way) and used to spuriously
        // boot the pipeline when smart mode was armed.
        if (prevView == 6 || previousDesiredView == 6 || viewMode == 6) {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        }

        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam",
                              "BlindSpot L", "BlindSpot R"};
        CameraDaemon.log("Stream view mode set to: " + (viewMode < modeNames.length ? modeNames[viewMode] : "Unknown"));
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamViewMode(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        int viewMode = (pipeline != null) ? pipeline.getStreamViewMode() : -1;
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode >= 0 && viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Read-only readiness check used after the initial mutating DVR selection.
     * The OEM lifecycle worker owns camera start, source binding and first-frame
     * activation; browser polling must never repeat those mutations.
     */
    private static void handleStreamViewStatus(
            OutputStream out, int viewMode, ViewRequest request) throws Exception {
        if (viewMode != 6) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_invalid_view_mode"));
            return;
        }
        if (!request.isCurrent() || getLastDesiredViewMode() != 6) {
            sendSupersededViewResponse(out, viewMode);
            return;
        }

        GpuSurveillancePipeline pano = CameraDaemon.getGpuPipeline();
        if (pano == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        if (!pano.isRunning()) {
            sendOemViewPending(out, "pano_starting",
                    "Pipeline starting - try again in a few seconds");
            return;
        }
        if (!pano.isStreamingEnabled()) {
            sendOemViewPending(out, "stream_starting",
                    "Streaming starting - try again in a few seconds");
            return;
        }
        if (pano.getStreamViewMode() == 6 && pano.isOemStreamSourceActive()) {
            sendOemViewSuccess(out);
            return;
        }
        if (sendOemTerminalErrorIfAny(out)) return;

        com.overdrive.app.camera.OemDashcamPipeline oem =
            CameraDaemon.getOemDashcamPipeline();
        if (oem == null || !oem.isRouteReady() || !pano.isOemStreamSourceActive()) {
            sendOemViewPending(out, "oem_starting",
                    "OEM Dashcam starting - try again in a few seconds");
            return;
        }
        sendOemViewPending(out, "oem_starting",
                "OEM Dashcam waiting for its first frame - try again in a few seconds");
    }

    /**
     * Handle a stream view mode = 6 request: route the WebSocket stream to
     * the OEM Dashcam pipeline's encoder bitstream. Returns starting=true
     * while the pano + OEM pipelines come up so the JS poll loop just
     * waits — no blocking on the HTTP worker thread. View 5 stays the
     * legacy raw debug passthrough.
     */
    private static void handleOemDashcamView(
            OutputStream out, ViewRequest request) throws Exception {
        int oemCamId = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
        if (oemCamId < 0) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("viewMode", 6);
            err.put("error", "OEM Dashcam is not configured or not installed on this vehicle");
            HttpResponse.sendJson(out, err.toString());
            return;
        }
        GpuSurveillancePipeline pano = CameraDaemon.getGpuPipeline();
        // Async-warm pano if needed; return starting=true while it's coming up.
        if (!ensurePanoStartedNonBlocking(pano)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "pano_starting");
            pending.put("error", "Pipeline starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        if (!ensureCurrentViewRequest(out, 6, request)) return;
        // Pano is up but streaming isn't enabled yet. enableStreaming is
        // synchronous + cheap (~100-200ms), so inline is fine.
        if (pano != null && !pano.isStreamingEnabled()) {
            try {
                GpuPipelineConfig.StreamingQuality q =
                    GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pano.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                CameraDaemon.log("handleOemDashcamView: pano.enableStreaming failed: " + e.getMessage());
            }
        }
        if (!ensureCurrentViewRequest(out, 6, request)) return;
        // Defensive — if streaming still didn't come up (enableStreaming
        // threw, or a concurrent disable just nulled the scaler), the route
        // call below will fail opaquely. Surface it as starting=true so the
        // client retries with the next poll instead of seeing an
        // unrecoverable "stream routing not yet available".
        if (pano == null || !pano.isStreamingEnabled()) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "stream_starting");
            pending.put("error", "Streaming starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }

        // Repeated view-6 requests are a no-op once the source and shader view
        // are both active. This is intentionally before any route/lifecycle
        // work so reconnect noise cannot re-bind a healthy DVR feed.
        if (pano.getStreamViewMode() == 6 && pano.isOemStreamSourceActive()) {
            sendOemViewSuccess(out);
            return;
        }

        com.overdrive.app.camera.OemDashcamPipeline oem = CameraDaemon.getOemDashcamPipeline();
        // Gate on isRouteReady (camera texture + SurfaceTexture allocated)
        // not just isRunning. The pipeline's running flag flips true at the
        // top of start() before initEglAndEncoder allocates the EXTERNAL_OES
        // texture; a view-6 click arriving in that window would otherwise
        // skip the oem_starting path and fall straight into a
        // routeStreamToOemDashcam() call that finds cameraTextureId=0 and
        // surfaces "OEM Dashcam stream routing not yet available" — which
        // the user can't recover from without retrying.
        if (oem == null || !oem.isRouteReady()) {
            if (sendOemTerminalErrorIfAny(out)) return;
            // NOTE: the "warm but on an independent GL context while recording" case is
            // no longer surfaced as a terminal error. startPipeline() now ACTIVELY brings
            // pano up and waits for its EGLCore before creating OEM's context, so OEM
            // starts in pano's share group in the first place and DVR just works. If it
            // still lands unshared (pano genuinely broken), the ordinary "starting" path
            // below keeps polling and the self-heal restart repairs it as soon as no
            // recording is open — a transient wait, not a dead end the user must decode.
            // resolveOemDashcamId honours the XOR-of-pano default, so on
            // every install (even fresh, no manual override) we get back
            // a candidate id and ATTEMPT a start. Only after the start
            // throws (e.g. validateHalDimsOrReject on hardware with no
            // separate forward sensor) does UCM hold a `lastStartError`.
            // If we see one — and we haven't successfully started this
            // pipeline since — surface it as a real terminal failure so
            // the JS poll loop stops retrying. Without this short-circuit
            // the user sits on an "OEM Dashcam starting…" toast for ~30s
            // until the poll geometric backoff exhausts, and even then
            // gets the original opaque message.
            // Streaming-only kick — we never flip recordingMode in UCM.
            // applyTriggerLifecycleFromUcm sees isAnyStreamingViewerActive()
            // (view 6 intent persisted just below) and brings the camera + EGL up
            // without flipping recording on. Re-run lifecycle even when an
            // existing pipeline is unshared: its guarded EGL self-heal needs
            // this wakeup to rebuild in pano's share group.
            //
            // DO NOT set the scaler to view 6 here. The shader's OEM branch is gated
            // on `uViewMode == 6 && uOemActive == 1`; uOemActive only goes 1 after
            // bindOemSource + the first matrix publish, which cannot have happened yet
            // on this not-ready path. With uViewMode=6 and uOemActive=0 the gate FALLS
            // THROUGH into the uApaMode branches and renders the 4-cam AVM mosaic —
            // the "DVR shows the 4-cam strip" bug. Leaving the view mode alone keeps
            // the previous (correct) feed on screen while OEM warms; view 6 is applied
            // only on the success path below, once the bind actually landed.
            // isAnyStreamingViewerActive() reads the persisted intent rather
            // than the scaler, so the lifecycle kick still sees view 6 while
            // the source warms.
            if (!ensureCurrentViewRequest(out, 6, request)) return;
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "oem_starting");
            pending.put("error", "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        // Route the existing WebSocket stream sink to the OEM encoder. The
        // pano pipeline keeps running but its stream callback is detached
        // for the duration; switching back to view 0..4 reattaches. The
        // routing returns false when the GPU pipeline hasn't yet exposed
        // the attachExternalStreamCallback hook (Phase-9 plumbing) — in
        // that case we MUST tell the client the switch did not actually
        // take effect, otherwise the UI flips to "OEM Dashcam" while the
        // WS continues to deliver AVM mosaic frames.
        boolean routed = CameraDaemon.routeStreamToOemDashcam();
        if (!ensureCurrentViewRequest(out, 6, request)) {
            if (routed) restoreLatestViewAfterSupersededOemRoute(pano);
            return;
        }
        boolean viewActivated = routed && pano.activateOemStreamViewWhenReady();
        if (!ensureCurrentViewRequest(out, 6, request)) {
            if (routed) restoreLatestViewAfterSupersededOemRoute(pano);
            return;
        }
        JSONObject response = new JSONObject();
        if (!routed || !viewActivated) {
            // Re-kick the lifecycle so a missed-edge race between
            // isRouteReady() flipping true and attachExternalStreamCallback's
            // own gates (streamingEnabled / streamScaler / oemTextureId)
            // gets retried on the next poll instead of stranding the user
            // on a permanent toast.
            try {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            } catch (Throwable ignored) {}
            response.put("success", false);
            response.put("viewMode", 6);
            response.put("starting", true);
            response.put("errorCode", "oem_starting");
            response.put("error", routed
                ? "OEM Dashcam waiting for its first frame — try again in a few seconds"
                : "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        response.put("success", true);
        response.put("viewMode", 6);
        response.put("viewName", "OEM Dashcam");
        HttpResponse.sendJson(out, response.toString());
    }

    private static void sendOemViewSuccess(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", 6);
        response.put("viewName", "OEM Dashcam");
        HttpResponse.sendJson(out, response.toString());
    }

    private static void sendOemViewPending(
            OutputStream out, String errorCode, String error) throws Exception {
        JSONObject pending = new JSONObject();
        pending.put("success", false);
        pending.put("viewMode", 6);
        pending.put("starting", true);
        pending.put("errorCode", errorCode);
        pending.put("error", error);
        HttpResponse.sendJson(out, pending.toString());
    }

    /**
     * Surface configuration and recent HAL-start failures consistently from
     * both the mutating selection and the read-only readiness endpoint.
     */
    private static boolean sendOemTerminalErrorIfAny(OutputStream out) throws Exception {
        int resolved = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
        if (resolved < 0) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("viewMode", 6);
            err.put("errorCode", "oem_disabled");
            err.put("error", "OEM Dashcam disabled on this vehicle");
            HttpResponse.sendJson(out, err.toString());
            return true;
        }
        try {
            org.json.JSONObject oemCfg =
                com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
            if (oemCfg.has("lastStartError") && !oemCfg.isNull("lastStartError")) {
                String reason = oemCfg.optString("lastStartError", "");
                long lastAt = oemCfg.optLong("lastStartErrorAt", 0L);
                long ageMs = System.currentTimeMillis() - lastAt;
                // Recent failures are terminal for this selection. Older ones
                // are retried because transient HAL warm-up errors can recover.
                if (!reason.isEmpty() && lastAt > 0 && ageMs < 60_000L) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("viewMode", 6);
                    err.put("errorCode", "oem_unsupported");
                    err.put("error", "OEM Dashcam unavailable: " + reason);
                    HttpResponse.sendJson(out, err.toString());
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
    
    // Static getters/setters for cross-component access
    public static String getStreamingQuality() { return streamingQuality; }
    
    public static void setStreamingQuality(String quality) {
        if (quality == null) return;
        String q = quality.toUpperCase();
        // Mirror the StreamingQuality enum (GpuPipelineConfig). SMOOTH and MAX
        // are recent additions; the cold-start loader (QualitySettingsApiHandler.
        // loadPersistedSettings) calls this with whatever tag is on disk, so if
        // we silently reject MAX here the persisted user pick decays to the
        // hard-coded default after every daemon restart.
        switch (q) {
            case "ULTRA_LOW":
            case "LOW":
            case "MEDIUM":
            case "HIGH":
            case "ULTRA_HIGH":
            case "SMOOTH":
            case "MAX":
            case "LQ":
            case "HQ":
                streamingQuality = q;
                break;
            default:
                // Unknown tag — leave previous value intact.
                break;
        }
    }
}
