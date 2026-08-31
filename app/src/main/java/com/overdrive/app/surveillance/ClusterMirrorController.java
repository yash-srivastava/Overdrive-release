package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.view.Surface;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-owned LIVE MIRROR of the driver-cluster projection onto the head-unit
 * display, for the "Projection" screen.
 *
 * <h3>Why this exists</h3>
 * Once an app (or the nav map) is projected onto the cluster it is a full takeover of a
 * NON-TOUCH panel, so the driver can neither see it from the head unit nor interact with
 * it. This controller paints a live copy of whatever SurfaceFlinger is compositing on the
 * cluster onto a head-unit SurfaceControl layer positioned inside the Projection screen's
 * mirror pane, and a sibling relay ({@link ClusterInputRelay}) forwards taps back.
 *
 * <h3>How the mirror works — codec-free, GL-free (the whole point)</h3>
 * The cluster is the OEM "fission" PRESENTATION virtual display; its SurfaceFlinger
 * compositing "layer stack" is resolved LIVE per projection-open (never hardcoded — see
 * {@link BsNativeLayer#resolveFissionDisplay()}). We ask SurfaceFlinger to composite that
 * SAME layer stack a SECOND time onto an output surface WE own:
 * <ol>
 *   <li>Create a head-unit {@link BsNativeLayer} buffer layer (layerStack 0) sized to the
 *       cluster panel; its {@link Surface} is a BufferQueue producer endpoint.</li>
 *   <li>Reflectively create our own SurfaceFlinger virtual display
 *       ({@code SurfaceControl.createDisplay}), set its output surface to that layer's
 *       Surface, and set its layer stack to the live fission stack. SurfaceFlinger then
 *       drives composited cluster frames straight into the layer's BufferQueue at vsync —
 *       NO MediaCodec, NO WebSocket, NO decoder, and crucially NO per-frame CPU/GPU work
 *       in this process. Latency is ~1-2 frames.</li>
 *   <li>{@link BsNativeLayer#setGeometry} scales that panel-sized buffer into the mirror
 *       pane rect on the head unit (one transaction, no re-render). Drag/resize the pane =
 *       another setGeometry, free.</li>
 * </ol>
 *
 * <h3>Capture is device-gated — fail SAFE</h3>
 * {@code createDisplay}/{@code setDisplayLayerStack} on a foreign virtual display is
 * unverified on some firmware. Before committing the direct path we run a ONE-SHOT probe
 * (a throwaway virtual display feeding a small {@link ImageReader}); if it yields a real
 * (non-black) frame we commit the direct path, otherwise we fall back to a low-rate
 * still-poll ({@code screencap -d <liveFissionId>} decoded and drawn onto the same layer).
 * If BOTH fail the screen reports the mirror unsupported on this vehicle — the cast,
 * open/close and (attempted) interaction still work. We NEVER blindly tag onto stack 0
 * (head unit) or an unresolved stack, so the mirror can never paint over the infotainment.
 *
 * <h3>No leak / bounded compute</h3>
 * All state is touched ONLY on a single self-reaping worker thread (mirrors
 * {@link ClusterSpeedOverlay}): when idle the thread times out so a stopped mirror leaves
 * ZERO resident resources (no thread, no layer, no virtual display, no ImageReader).
 * Direct mode does no periodic work at all (SurfaceFlinger owns the cadence); only the
 * still-poll fallback ticks, and it is capped and torn down on stop. Every exit path —
 * {@link #stop()} (screen left), {@link #forceCloseIfActive} (ACC-off),
 * {@link #shutdownIfActive} (daemon exit) — destroys the virtual display and releases the
 * layer + ImageReader deterministically.
 */
public final class ClusterMirrorController {

    private static final String TAG = "ClusterMirror";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Head-unit z: above the app window (so the pane is visible) but well below the
     *  parked screen-deterrent ({@code Integer.MAX_VALUE}) and cluster overlays, which
     *  never contend (deterrent is ACC-off, cluster overlays are layerStack 1). */
    private static final int Z_ORDER = Integer.MAX_VALUE - 50;

    private static final String HOST_LAYER_NAME = "ClusterMirror";
    private static final String PROBE_DISPLAY_NAME = "ClusterMirrorProbe";
    private static final String MIRROR_DISPLAY_NAME = "ClusterMirrorOut";

    // Probe budget: how long to wait for the throwaway virtual display to deliver its
    // first frame before deciding the direct path is unsupported on this firmware.
    private static final long PROBE_TIMEOUT_MS = 1500;
    private static final int PROBE_W = 320;   // small — we only sample it for black/non-black
    private static final int PROBE_H = 180;

    // Still-poll fallback cadence (only used when the direct path is unsupported). Low by
    // design — it is a degraded preview, not the primary experience — so parked/idle cost
    // stays negligible. Capped, single-thread, torn down on stop.
    private static final long STILL_POLL_MS = 500;   // 2 Hz
    private static final long EXEC_KEEPALIVE_MS = 5000;
    private static final String SCREENCAP_DIR = ScratchPaths.path(".overdrive/mirror");

    /** Mode after {@link #startOnExec}. Reported to the UI so it can show the right state. */
    public static final int MODE_STOPPED       = 0;
    public static final int MODE_DIRECT        = 1;   // SurfaceFlinger direct composite (best)
    public static final int MODE_STILL         = 2;   // screencap still-poll fallback
    public static final int MODE_NO_PROJECTION = 3;   // nothing is projected — cast first
    public static final int MODE_UNSUPPORTED   = 4;   // capture denied/black on this vehicle

    /** Scaling mode — how the fixed panel-sized cluster buffer maps into the user's pane
     *  rect. Mirrors the video-player / CSS object-fit SOTA (ExoPlayer RESIZE_MODE_*).
     *  Applied ENTIRELY in the consumer layer's setGeometry (src crop + dest rect); the
     *  virtual display is NEVER re-projected (that revives the FIX-1/FIX-2 black-frame
     *  bugs + races the ACC-off teardown). MUST match the UI constants in
     *  ProjectionFragment / ProjectionBoundsView. */
    public static final int SCALE_FIT  = 0;   // letterbox/contain (default; whole cluster, bars)
    public static final int SCALE_FILL = 1;   // stretch to pane (aspect broken)
    public static final int SCALE_ZOOM = 2;   // crop-to-cover (aspect kept, edges cropped)

    private static volatile ClusterMirrorController instance;

    private final ScheduledThreadPoolExecutor exec;

    // ── All fields below are touched ONLY on the exec thread (no locks needed) ──────
    private BsNativeLayer hostLayer;      // head-unit display layer (the mirror pane)
    private Object mirrorDisplayToken;    // android.os.IBinder (reflected SurfaceControl display)
    private int mode = MODE_STOPPED;
    private int panelW, panelH;           // resolved cluster panel size (buffer size)
    private int fissionStack = BsNativeLayer.STACK_UNRESOLVED;
    private int fissionDisplayId = -1;
    private Rect paneRect = new Rect(0, 0, 0, 0);   // head-unit dest rect (screen px)
    private int scaleMode = SCALE_FIT;              // FIT/FILL/ZOOM (from the UI POST)
    private ScheduledFuture<?> stillFuture;         // still-poll tick (fallback only)
    private Paint stillPaint;                        // reused in still mode (no per-frame alloc)

    private ClusterMirrorController() {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ClusterMirror");
            t.setDaemon(true);
            return t;
        });
        e.setKeepAliveTime(EXEC_KEEPALIVE_MS, TimeUnit.MILLISECONDS);
        e.allowCoreThreadTimeOut(true);
        e.setRemoveOnCancelPolicy(true);
        exec = e;
    }

    public static ClusterMirrorController getInstance() {
        if (instance == null) {
            synchronized (ClusterMirrorController.class) {
                if (instance == null) instance = new ClusterMirrorController();
            }
        }
        return instance;
    }

    // ── Null-safe static lifecycle helpers (mirror ClusterProjectionController) ─────

    /** Daemon-exit teardown. No-op if the mirror was never started (instance null).
     *  SYNCHRONOUS so the mirror's virtual display is fully unbound + destroyed before the
     *  VM exits / the OEM projection close runs. */
    public static void shutdownIfActive() {
        ClusterMirrorController i = instance;
        if (i != null) i.stopSync();
    }

    /** ACC-off / projection-teardown reconcile. No-op if never started.
     *  SYNCHRONOUS + must run BEFORE ClusterProjectionController.forceCloseIfActive so our
     *  virtual display (the CONSUMER of the fission layerStack) is torn down before the OEM
     *  fission SOURCE display is closed — eliminating the ACC-off SurfaceFlinger teardown
     *  race that otherwise crashes the daemon + app. */
    public static void forceCloseIfActive(String reason) {
        ClusterMirrorController i = instance;
        if (i != null) {
            logger.info("forceClose(" + reason + ")");
            i.stopSync();
        }
    }

    // ── Public API (posted onto the single exec thread) ─────────────────────────────

    /** Start (or restart) the mirror into {@code rect} (head-unit screen px). Idempotent
     *  restart: a running mirror is torn down and re-armed at the new rect. Any thread. */
    public void start(int x, int y, int w, int h) {
        start(x, y, w, h, scaleMode);
    }

    /** Start (or restart) the mirror into {@code rect} with an explicit scaling mode
     *  (FIT/FILL/ZOOM). The mode is captured on the exec thread before arming so the
     *  first frame already uses it. Any thread. */
    public void start(int x, int y, int w, int h, int mode) {
        final Rect r = new Rect(x, y, x + Math.max(1, w), y + Math.max(1, h));
        final int m = normalizeScaleMode(mode);
        exec.execute(() -> { this.scaleMode = m; startOnExec(r); });
    }

    /** Move/resize the mirror pane (drag/resize on the head unit). Cheap transaction. */
    public void setRect(int x, int y, int w, int h) {
        final Rect r = new Rect(x, y, x + Math.max(1, w), y + Math.max(1, h));
        exec.execute(() -> setRectOnExec(r));
    }

    /** Change the scaling mode live (FIT/FILL/ZOOM) and re-present at the current pane in
     *  a single transaction. Cheap; no re-capture, no VD change. Any thread. */
    public void setScaleMode(int mode) {
        final int m = normalizeScaleMode(mode);
        exec.execute(() -> {
            if (this.scaleMode == m) return;
            this.scaleMode = m;
            setRectOnExec(this.paneRect);
        });
    }

    private static int normalizeScaleMode(int mode) {
        return (mode == SCALE_FILL || mode == SCALE_ZOOM) ? mode : SCALE_FIT;
    }

    /** Tear everything down. Idempotent, any thread. */
    public void stop() {
        exec.execute(this::stopOnExec);
    }

    /** Tear everything down and BLOCK until the exec-thread teardown actually completes
     *  (bounded). Used on the ACC-off / daemon-exit paths so the mirror's virtual display
     *  is guaranteed unbound + destroyed before the OEM fission source display is closed
     *  and before the head-unit panel power-gates — the ordering that prevents the
     *  SurfaceFlinger teardown-race crash. Never throws; a timeout just proceeds (the
     *  detach/destroy is still best-effort correct). Safe from any thread; if called ON the
     *  exec thread it runs inline to avoid self-deadlock. */
    public void stopSync() {
        if (Thread.currentThread().getName() != null
                && Thread.currentThread().getName().startsWith("ClusterMirror")) {
            // Already on the exec thread — run inline (awaiting our own thread would deadlock).
            stopOnExec();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            exec.execute(() -> { try { stopOnExec(); } finally { latch.countDown(); } });
        } catch (Throwable t) {
            // Executor already shut down / rejected — nothing running to await.
            return;
        }
        try { latch.await(2500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }

    /** Snapshot of the current state for the UI status endpoint. Volatile-free read of
     *  exec-thread state is acceptable here (status is advisory, refreshed by polling). */
    public int currentMode() { return mode; }
    public int currentFissionDisplayId() { return fissionDisplayId; }
    public int panelWidth() { return panelW; }
    public int panelHeight() { return panelH; }
    public int currentScaleMode() { return scaleMode; }

    /**
     * One-shot RESIZE DIAGNOSTIC (device debugging only — no behaviour change). Resizes the
     * mirror pane to {@code (w×h)} at the current top-left, then captures the mirror layer's
     * actual composited geometry from {@code dumpsys SurfaceFlinger} into a text file so we
     * can compare the REQUESTED dest rect against what SurfaceFlinger actually composited —
     * the crux of why on-device resize hasn't visibly worked (three prior fixes) is whether a
     * VirtualDisplay-output buffer layer honours its own setGeometry destFrame or re-asserts
     * the buffer/display size. Returns the file path (or an error string). Called only from
     * the debug endpoint; never on any normal path.
     */
    public String captureResizeDiag(final int w, final int h) {
        final java.util.concurrent.atomic.AtomicReference<String> result =
                new java.util.concurrent.atomic.AtomicReference<>("diag: not run");
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            exec.execute(() -> {
                try {
                    if (hostLayer == null || !hostLayer.isCreated()
                            || (mode != MODE_DIRECT && mode != MODE_STILL)) {
                        result.set("diag: mirror not running (mode=" + mode + ")");
                        return;
                    }
                    Rect target = new Rect(paneRect.left, paneRect.top,
                            paneRect.left + Math.max(1, w), paneRect.top + Math.max(1, h));
                    // Apply the resize through the SAME production path.
                    setRectOnExec(target);
                    // Give SF a couple of vsyncs to composite the new geometry.
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    String dump = grepSurfaceFlinger(HOST_LAYER_NAME);
                    String path = writeDiag(w, h, target, dump);
                    result.set(path);
                } catch (Throwable t) {
                    result.set("diag: error " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            return "diag: exec rejected " + t.getMessage();
        }
        try { latch.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result.get();
    }

    private String grepSurfaceFlinger(String layerName) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c",
                    "dumpsys SurfaceFlinger | grep -iA25 " + layerName)
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line; int cap = 0;
            while ((line = r.readLine()) != null && cap < 4000) { sb.append(line).append('\n'); cap += line.length(); }
            p.waitFor(3, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Throwable t) {
            return "grep failed: " + t.getMessage();
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private String writeDiag(int w, int h, Rect target, String dump) {
        try {
            java.io.File dir = new java.io.File(SCREENCAP_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "resize-diag.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== cluster mirror resize diagnostic ===\n");
            sb.append("mode=").append(mode).append(" scaleMode=").append(scaleMode).append('\n');
            sb.append("panel=").append(panelW).append('x').append(panelH).append('\n');
            sb.append("requested pane=").append(target.left).append(',').append(target.top)
                    .append(' ').append(target.width()).append('x').append(target.height()).append('\n');
            Rect fitted = fitPreserveAspect(target);
            sb.append("fit dest=").append(fitted.left).append(',').append(fitted.top)
                    .append(' ').append(fitted.width()).append('x').append(fitted.height()).append('\n');
            sb.append("--- dumpsys SurfaceFlinger (layer '").append(HOST_LAYER_NAME).append("') ---\n");
            sb.append(dump);
            java.io.FileWriter fw = new java.io.FileWriter(f, false);
            fw.write(sb.toString()); fw.flush(); fw.close();
            logger.info("resize-diag written: " + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "diag write failed: " + t.getMessage();
        }
    }

    // ── exec-thread lifecycle ────────────────────────────────────────────────────────

    private void startOnExec(Rect rect) {
        // Full restart semantics: drop any prior session first so a re-arm at a new rect
        // never leaves a stale display/layer.
        teardownResources();
        this.paneRect = rect;

        Context ctx = resolveContext();
        if (ctx == null) { logger.warn("start: no daemon context"); mode = MODE_STOPPED; return; }

        // Resolve the LIVE fission panel size + compositing stack (authoritative dumpsys
        // parse — the daemon's DisplayManager cache misses the foreign uid-1000 display).
        // Resolve ONCE and size from the SAME descriptor: a second resolveFissionDisplay()
        // (what the 1-arg clusterDisplaySize does internally) could straddle a layerStack/id
        // change across a projection re-open and pair one display's size with another's stack.
        BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
        Point panel = BsNativeLayer.clusterDisplaySize(ctx, fd);
        this.panelW = Math.max(1, panel.x);
        this.panelH = Math.max(1, panel.y);
        this.fissionStack = fd.layerStack;
        this.fissionDisplayId = fd.displayId;

        // Nothing is being projected (no live fission display) → there is nothing to
        // mirror. Report so the UI can prompt the user to cast an app / open the map.
        // NEVER proceed on an unresolved or head-unit (0) stack — a mirror there would be
        // meaningless or paint over the infotainment.
        if (this.fissionStack == BsNativeLayer.STACK_UNRESOLVED || this.fissionStack == 0) {
            logger.info("start: no live cluster projection (stack=" + this.fissionStack + ")");
            mode = MODE_NO_PROJECTION;
            return;
        }

        // Pin the projection open for the duration of the mirror so a linger/max-cap
        // auto-close can't blank the source mid-view. Acquire ONLY now that we've
        // confirmed it is already open (requestOpen inside acquireSustained is a no-op
        // when open), so the mirror never itself forces a gauge-takeover. Released in
        // teardownResources(). The gauge-restore safety net (ACC-off/shutdown) is
        // unaffected — those clear ALL holders regardless.
        try { ClusterProjectionController.getInstance().acquireSustained("mirror"); }
        catch (Throwable t) { logger.warn("start: acquireSustained failed: " + t.getMessage()); }

        // Create the head-unit host layer (layerStack 0 = default, so no setLayerStack op
        // — the layer composites on the head unit exactly like the proven BS head-unit
        // path). Buffer is panel-sized so the mirrored frames land 1:1 before setGeometry
        // scales them into the pane.
        hostLayer = new BsNativeLayer(panelW, panelH, HOST_LAYER_NAME, Z_ORDER);
        if (!hostLayer.create()) {
            logger.warn("start: host layer create failed");
            teardownResources();
            mode = MODE_STOPPED;
            return;
        }

        // Decide the capture path with a one-shot probe. If the SurfaceFlinger direct
        // path yields real pixels, use it (best). Else try the still-poll fallback.
        if (probeDirectCapture()) {
            if (beginDirectMirror()) {
                mode = MODE_DIRECT;
                logger.info("mirror DIRECT on stack " + fissionStack + " (" + panelW + "x" + panelH
                        + ") pane=" + paneRect.toShortString());
                return;
            }
            logger.warn("start: direct mirror setup failed after probe passed — trying still");
        }
        if (beginStillMirror()) {
            mode = MODE_STILL;
            logger.info("mirror STILL-poll fallback on displayId " + fissionDisplayId);
            return;
        }

        logger.warn("start: mirror unsupported on this vehicle (capture denied/black)");
        // Keep the host layer released; report unsupported so the UI shows the message.
        teardownResources();
        mode = MODE_UNSUPPORTED;
    }

    private void setRectOnExec(Rect rect) {
        this.paneRect = rect;
        if (hostLayer == null || !hostLayer.isCreated()) {
            logger.info("setRect ignored — no host layer (mode=" + mode + ")");
            return;
        }
        if (mode == MODE_DIRECT || mode == MODE_STILL) {
            // BOTH capture modes present via the layer's setGeometry destFrame — the proven
            // blind-spot scaling path. In DIRECT the VD is held at native size (identity, set
            // once in beginDirectMirror) and NEVER re-projected on resize; in STILL we
            // lockCanvas the native-sized bitmap in. On-screen size + scaling is governed
            // entirely here by the layer geometry, identically for both modes.
            presentScaled(rect, true);
        } else {
            logger.info("setRect ignored — mode=" + mode);
        }
    }

    /**
     * Present the cluster buffer into {@code pane} at the active {@link #scaleMode}, in ONE
     * SurfaceControl transaction. This is the single scaling authority shared by every path
     * (initial arm + live resize + mode change) so DIRECT and STILL behave identically.
     *
     * <ul>
     *   <li>FIT — letterbox: src = full buffer, dest = aspect-fitted rect inside the pane.</li>
     *   <li>FILL — stretch: src = full buffer, dest = the raw pane (non-uniform scale).</li>
     *   <li>ZOOM — crop-to-cover: dest = raw pane, src = a centred sub-rect of the buffer
     *       whose aspect equals the pane's, so the uniform scale fills the pane with no bars
     *       and the overflow is cropped.</li>
     * </ul>
     *
     * The virtual display is NEVER touched here (capture stays decoupled from presentation),
     * so no black-frame / ACC-off-teardown regression.
     */
    private void presentScaled(Rect pane, boolean show) {
        if (hostLayer == null) return;
        if (scaleMode == SCALE_FILL) {
            logger.info("present FILL pane(" + pane.width() + "x" + pane.height() + ")");
            if (show) hostLayer.setGeometry(pane.left, pane.top, pane.width(), pane.height());
            else hostLayer.setGeometryHidden(pane.left, pane.top, pane.width(), pane.height());
        } else if (scaleMode == SCALE_ZOOM) {
            Rect src = coverCrop(pane);
            logger.info("present ZOOM pane(" + pane.width() + "x" + pane.height()
                    + ") src=" + src.left + "," + src.top + " " + src.width() + "x" + src.height());
            if (show) hostLayer.setGeometry(src, pane.left, pane.top, pane.width(), pane.height());
            else hostLayer.setGeometryHidden(src, pane.left, pane.top, pane.width(), pane.height());
        } else { // SCALE_FIT (default)
            Rect fitted = fitPreserveAspect(pane);
            logger.info("present FIT pane(" + pane.width() + "x" + pane.height()
                    + ") → fitted(" + fitted.left + "," + fitted.top + " "
                    + fitted.width() + "x" + fitted.height() + ")");
            if (show) hostLayer.setGeometry(fitted.left, fitted.top, fitted.width(), fitted.height());
            else hostLayer.setGeometryHidden(fitted.left, fitted.top, fitted.width(), fitted.height());
        }
    }

    /**
     * Centred cover-crop of the panel buffer to the pane's aspect (for ZOOM): the returned
     * source sub-rect has the SAME aspect as the pane, so scaling it uniformly fills the pane
     * with no bars and no distortion; the overflow on the long axis is cropped. Clamped to a
     * valid, ≥1px sub-rect of the buffer.
     */
    private Rect coverCrop(Rect pane) {
        int pw = Math.max(1, pane.width());
        int ph = Math.max(1, pane.height());
        double srcAr = (double) panelW / Math.max(1, panelH);
        double paneAr = (double) pw / ph;
        int cw, ch;
        if (paneAr > srcAr) {
            // Pane wider than the panel → keep full width, crop top/bottom.
            cw = panelW;
            ch = (int) Math.round(panelW / paneAr);
        } else {
            // Pane taller/narrower → keep full height, crop left/right.
            ch = panelH;
            cw = (int) Math.round(panelH * paneAr);
        }
        cw = Math.max(1, Math.min(cw, panelW));
        ch = Math.max(1, Math.min(ch, panelH));
        int cx = (panelW - cw) / 2;
        int cy = (panelH - ch) / 2;
        return new Rect(cx, cy, cx + cw, cy + ch);
    }

    /** Fit the native cluster aspect ({@link #panelW}:{@link #panelH}) INSIDE {@code pane},
     *  centered (letterbox/pillarbox) — so setGeometry never STRETCHES the 1920×720 buffer
     *  into an off-aspect pane. The head-unit bounds box is aspect-locked to the panel, so
     *  the pane is normally already the right shape and this is a near-identity; but it makes
     *  the mirror distortion-proof against rounding / a mismatched pane. */
    private Rect fitPreserveAspect(Rect pane) {
        int pw = Math.max(1, pane.width());
        int ph = Math.max(1, pane.height());
        double srcAr = (double) panelW / Math.max(1, panelH);
        double paneAr = (double) pw / ph;
        int w, h;
        if (paneAr > srcAr) {          // pane too WIDE → pillarbox (limit by height)
            h = ph;
            w = (int) Math.round(h * srcAr);
        } else {                        // pane too TALL → letterbox (limit by width)
            w = pw;
            h = (int) Math.round(w / srcAr);
        }
        int x = pane.left + (pw - w) / 2;
        int y = pane.top + (ph - h) / 2;
        return new Rect(x, y, x + Math.max(1, w), y + Math.max(1, h));
    }

    private void stopOnExec() {
        if (mode == MODE_STOPPED && hostLayer == null && mirrorDisplayToken == null) return;
        teardownResources();
        mode = MODE_STOPPED;
        logger.info("mirror stopped");
    }

    // ── Direct SurfaceFlinger mirror (primary) ──────────────────────────────────────

    /** Point our own virtual display's output at the host layer's Surface + the live
     *  fission stack, then present it via the layer's setGeometry — the SAME proven path
     *  the blind-spot card and MODE_STILL use.
     *
     *  <p>WHY THIS SHAPE (after two failed fixes): the host layer's BufferQueue has a FIXED
     *  {@code setBufferSize(panelW×panelH)} default (BsNativeLayer.createBufferLayer). The
     *  virtual display must produce buffers at THAT native size, so we hold the VD at
     *  IDENTITY (setDisplayProjection src==dest==native, setDisplaySize==native) and NEVER
     *  change it on resize. On-screen SIZE is then governed entirely by the CONSUMER layer's
     *  {@code setGeometry(srcRect=full buffer, destRect=pane)} — which SCALES the native
     *  buffer into the pane, exactly like the blind-spot card (a proven-working buffer layer
     *  on this firmware). FIX-1 tried destFrame but paired it with a non-native VD; FIX-2
     *  scaled the VD's setDisplaySize but the consumer's pinned buffer size defeated it and
     *  the layer had NO destFrame (setPosition only) → always native-size. Decoupling
     *  capture (VD fixed at native) from presentation (layer geometry) is the fix. */
    private boolean beginDirectMirror() {
        try {
            Surface out = hostLayer.getSurface();
            if (out == null) return false;
            mirrorDisplayToken = createDisplay(MIRROR_DISPLAY_NAME);
            if (mirrorDisplayToken == null) return false;
            // Bind the VD at NATIVE size (identity projection) — matches the layer's pinned
            // buffer size so SF can dequeue; the VD size is set ONCE and never changes.
            if (!applyDisplay(mirrorDisplayToken, out, fissionStack, panelW, panelH,
                    panelW, panelH)) {
                destroyDisplaySafe(mirrorDisplayToken);
                mirrorDisplayToken = null;
                return false;
            }
            // Present: scale the native buffer into the pane at the active scaling mode via
            // the proven layer setGeometry path (VD stays at identity — never re-projected).
            presentScaled(paneRect, true);
            return true;
        } catch (Throwable t) {
            logger.warn("beginDirectMirror failed: " + t.getMessage());
            if (mirrorDisplayToken != null) { destroyDisplaySafe(mirrorDisplayToken); mirrorDisplayToken = null; }
            return false;
        }
    }

    /**
     * One-shot go/no-go probe for the direct path: spin up a THROWAWAY virtual display
     * feeding a small {@link ImageReader} tied to the live fission stack, wait for the
     * first frame, and decide if it carries real (non-black) content. Always tears down
     * the probe display + reader before returning. A {@code SecurityException} (capture
     * denied) or a timeout / all-black frame → false (caller falls back).
     */
    private boolean probeDirectCapture() {
        ImageReader reader = null;
        Object probeToken = null;
        // The ImageReader callback needs a Looper. Our exec thread is a plain worker with
        // NO Looper, so passing a null Handler here would make ImageReader try to use the
        // (absent) current-thread Looper and THROW — which would silently kill the direct
        // path and force the still fallback forever. Spin a short-lived HandlerThread for
        // the callback and quit it in finally so nothing leaks.
        android.os.HandlerThread cbThread = new android.os.HandlerThread("ClusterMirrorProbe");
        cbThread.start();
        android.os.Handler cbHandler = new android.os.Handler(cbThread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] gotFrame = { false };
        try {
            reader = ImageReader.newInstance(PROBE_W, PROBE_H, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null && !gotFrame[0]) {
                        gotFrame[0] = !isImageBlack(img);
                        latch.countDown();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }, cbHandler);

            probeToken = createDisplay(PROBE_DISPLAY_NAME);
            if (probeToken == null) { logger.info("probe: createDisplay unavailable"); return false; }
            // Map the full cluster (src) down into the small probe ImageReader (dest).
            if (!applyDisplay(probeToken, reader.getSurface(), fissionStack,
                    panelW, panelH, PROBE_W, PROBE_H)) {
                logger.info("probe: display transaction failed");
                return false;
            }
            boolean signalled = latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            logger.info("probe: signalled=" + signalled + " nonBlack=" + gotFrame[0]);
            return gotFrame[0];
        } catch (SecurityException se) {
            logger.warn("probe: capture DENIED (SecurityException) — " + se.getMessage());
            return false;
        } catch (Throwable t) {
            logger.warn("probe failed: " + t.getMessage());
            return false;
        } finally {
            if (probeToken != null) destroyDisplaySafe(probeToken);
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
            try { cbThread.quitSafely(); } catch (Throwable ignored) {}
        }
    }

    /** True if the image is (near) uniformly black — a coarse sample of the RGB channels.
     *  CRITICAL: an RGBA_8888 plane is [R,G,B,A,R,G,B,A,...]; a black-but-OPAQUE frame has
     *  A=0xFF, so summing raw bytes (incl. alpha) would read ~64 avg and wrongly call it
     *  non-black. We sample ONLY the R/G/B bytes of each pixel (skip alpha) using the
     *  plane's pixel/row strides so a genuinely black frame is detected and the caller
     *  falls back. */
    private static boolean isImageBlack(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return true;
            Image.Plane plane = planes[0];
            ByteBuffer buf = plane.getBuffer();
            if (buf == null) return true;
            int cap = buf.capacity();
            if (cap <= 0) return true;
            int pixelStride = Math.max(1, plane.getPixelStride());   // 4 for RGBA_8888
            long sum = 0; int samples = 0;
            // Step by whole pixels so we always land on an R byte, then read R,G,B (skip A).
            int pixStep = Math.max(pixelStride, (cap / pixelStride / 256) * pixelStride);
            if (pixStep <= 0) pixStep = pixelStride;
            for (int i = 0; i + 2 < cap; i += pixStep) {
                sum += (buf.get(i) & 0xFF);       // R
                sum += (buf.get(i + 1) & 0xFF);   // G
                sum += (buf.get(i + 2) & 0xFF);   // B
                samples += 3;
            }
            double avg = samples > 0 ? (double) sum / samples : 0;
            return avg < 4.0;   // essentially black
        } catch (Throwable t) {
            return true;   // unreadable → treat as black (fail safe → fallback)
        }
    }

    // ── Still-poll fallback (screencap of the live fission display) ─────────────────

    /** Verify the screencap path can grab the fission display at all (one capture), then
     *  arm the low-rate poll that decodes each PNG onto the host layer via lockCanvas. */
    private boolean beginStillMirror() {
        if (fissionDisplayId < 0) return false;   // no addressable display id → can't screencap
        Bitmap first = captureStill();
        if (first == null) return false;
        stillPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Arm geometry at the active scaling mode + draw the first frame, then show (avoids
        // an empty-layer flash). drawStill always fills the FULL panel-sized buffer, so the
        // same src-crop/dest logic as DIRECT applies here — presentScaled(show=false) arms
        // the geometry hidden, then show() reveals it once the buffer holds a real frame.
        presentScaled(paneRect, false);
        drawStill(first);
        try { first.recycle(); } catch (Throwable ignored) {}
        hostLayer.show();
        stillFuture = exec.scheduleWithFixedDelay(this::stillTick, STILL_POLL_MS, STILL_POLL_MS,
                TimeUnit.MILLISECONDS);
        return true;
    }

    private void stillTick() {
        if (mode != MODE_STILL || hostLayer == null) return;
        Bitmap bmp = captureStill();
        if (bmp == null) return;
        try { drawStill(bmp); } finally { try { bmp.recycle(); } catch (Throwable ignored) {} }
    }

    /** {@code screencap -d <liveFissionId>} to a file, decode, return the bitmap or null. */
    private Bitmap captureStill() {
        java.io.File f = new java.io.File(SCREENCAP_DIR, "m.png");
        String cmd = "mkdir -p " + SCREENCAP_DIR + " && screencap -d " + fissionDisplayId
                + " -p " + f.getAbsolutePath();
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            if (!f.exists() || f.length() == 0) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            logger.debug("captureStill failed: " + t.getMessage());
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private void drawStill(Bitmap bmp) {
        Surface s = (hostLayer != null) ? hostLayer.getSurface() : null;
        if (s == null || bmp == null) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            if (c == null) return;
            c.drawColor(0, PorterDuff.Mode.CLEAR);
            // Scale the captured frame to the panel-sized buffer (setGeometry then scales
            // the buffer into the pane). src = full bitmap, dst = full buffer.
            Rect dst = new Rect(0, 0, panelW, panelH);
            c.drawBitmap(bmp, null, dst, stillPaint);
        } catch (Throwable t) {
            logger.debug("drawStill error: " + t.getMessage());
        } finally {
            if (c != null) try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
        }
    }

    // ── Teardown (deterministic — never leaves a display/layer/reader/thread) ───────

    private void teardownResources() {
        if (stillFuture != null) { try { stillFuture.cancel(false); } catch (Throwable ignored) {} stillFuture = null; }
        if (mirrorDisplayToken != null) {
            // CRITICAL (ACC-off crash fix): UNBIND the virtual display's OUTPUT surface and
            // its SOURCE layerStack BEFORE destroying it. Our VD (createDisplay) reads the
            // live fission cluster layerStack and outputs into the head-unit BufferQueue
            // layer (hostLayer). If we destroyDisplay while it is still bound — racing the
            // OEM fission-source display being closed AND the head-unit panel power-gating
            // at ACC-off — SurfaceFlinger faults on the dangling display/producer and the
            // native crash cascades to kill BOTH the daemon (an SF client) and the app.
            // Detaching first makes SF stop compositing the foreign stack into our VD and
            // drop its producer ref to hostLayer's BufferQueue, so the subsequent
            // destroyDisplay + hostLayer.release() can't fault. Best-effort; guarded.
            detachDisplaySafe(mirrorDisplayToken);
            destroyDisplaySafe(mirrorDisplayToken);
            mirrorDisplayToken = null;
        }
        if (hostLayer != null) { try { hostLayer.release(); } catch (Throwable ignored) {} hostLayer = null; }
        stillPaint = null;
        // Release our projection hold LAST so the controller can restore the gauges when
        // no other consumer (cast app / map / blind-spot) still wants the projection.
        try { ClusterProjectionController.getInstance().releaseSustained("mirror"); }
        catch (Throwable ignored) {}
    }

    // ── SurfaceControl virtual-display reflection ───────────────────────────────────
    // createDisplay/destroyDisplay are static on SurfaceControl; the display-state ops
    // (setDisplaySurface/LayerStack/Projection/Size) are on the Transaction. All present
    // on API 29. This is the same mechanism the platform screen-record path uses.

    private static Object createDisplay(String name) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            return scCls.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, name, false);   // secure=false
        } catch (Throwable t) {
            logger.warn("createDisplay failed: " + t.getMessage());
            return null;
        }
    }

    private static void destroyDisplaySafe(Object token) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            scCls.getMethod("destroyDisplay", IBinder.class).invoke(null, token);
        } catch (Throwable t) {
            logger.debug("destroyDisplay failed: " + t.getMessage());
        }
    }

    /** Unbind a virtual display's OUTPUT surface (setDisplaySurface(token,null)) so
     *  SurfaceFlinger drops its producer reference to the head-unit BufferQueue and stops
     *  compositing the fission source into it, BEFORE the display is destroyed and before
     *  the output layer's BufferQueue is released. This closes the ACC-off native-crash
     *  window (dangling display/producer while the source display closes + the panel
     *  power-gates). Applied synchronously; best-effort + fully guarded. */
    private static void detachDisplaySafe(Object token) {
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(tx, token, (Surface) null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("detachDisplay failed: " + t.getMessage());
        }
    }

    /**
     * Apply the display-state transaction that makes SurfaceFlinger composite the fission
     * layer stack onto {@code out}. Prefers the {@code SurfaceControl.Transaction} instance
     * methods (the API-29 form the rest of this codebase uses); on any missing method it
     * catches and the mirror simply reports unsupported. Returns false on failure so the
     * caller can fall back / clean up.
     */
    private static boolean applyDisplay(Object token, Surface out, int stack,
                                        int srcW, int srcH, int destW, int destH) {
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(tx, token, out);
            txCls.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(tx, token, stack);
            // Map the FULL cluster layer-stack region (src) onto a dest sized to the pane
            // (dest). SurfaceFlinger scales the cluster into the dest and produces
            // dest-sized buffers into our output Surface, so the on-screen size = dest.
            Rect src = new Rect(0, 0, srcW, srcH);
            Rect dest = new Rect(0, 0, destW, destH);
            try {
                txCls.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                        .invoke(tx, token, 0, src, dest);
            } catch (Throwable ignored) { /* projection is best-effort */ }
            try {
                txCls.getMethod("setDisplaySize", IBinder.class, int.class, int.class)
                        .invoke(tx, token, destW, destH);
            } catch (Throwable ignored) { /* size is best-effort */ }
            txCls.getMethod("apply").invoke(tx);
            try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            logger.warn("applyDisplay failed: " + t.getMessage());
            return false;
        }
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
