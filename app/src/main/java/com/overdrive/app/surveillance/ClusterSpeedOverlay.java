package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Surface;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.DaemonFonts;

import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-owned current-speed badge for the OEM driver-cluster projection.
 *
 * <p>The IC projection is a FULL takeover (see {@link ClusterProjectionController}):
 * opening it replaces the native gauges — including the native speedometer — for its
 * duration, and on this firmware the residual OEM speed band on the left edge is
 * clipped by our fullscreen content. This overlay restores a current-speed readout
 * that is INDEPENDENT of what is projected underneath (the nav map, the blind-spot
 * card, or any future projected surface), so the driver always sees their speed.
 * Opt-in via {@link #CONFIG_KEY} (default off).
 *
 * <h3>How it composites</h3>
 * A uid-2000 {@code SurfaceControl} buffer layer tagged {@code layerStack=1} (the OEM
 * "fission" cluster display) — the SAME mechanism {@link BsNativeLayer} uses and that
 * is validated live on this firmware. Z-order is {@link #Z_ORDER} = one BELOW the
 * blind-spot card ({@code Integer.MAX_VALUE}) so a turn-signal blind-spot view is
 * never occluded by the speed badge. The badge is a small translucent "glass" rounded
 * rectangle, vertically centred on the LEFT of the panel, painted with
 * {@link Surface#lockCanvas} (no GL/EGL — it's just text at 2 Hz).
 *
 * <h3>Lifecycle</h3>
 * Driven entirely by {@link ClusterProjectionController}: {@link #start()} on
 * projection-ready ({@code commitReady}), {@link #stop()} on every teardown
 * (forceClose / shutdown). A SIGKILL'd daemon's layer dies with its process, so there
 * is no boot-recovery path to add (unlike the gauge-restore opcodes).
 *
 * <h3>Speed source</h3>
 * {@link com.overdrive.app.byd.BydDataCollector#readCurrentSpeedKmh()} — the BYD SDK
 * instrument speed (ACC-gated, so live exactly while projecting during a drive). When
 * the SDK value is unavailable the badge shows "--" rather than a fabricated/estimated
 * number — the persistent glass badge stays, but never lies.
 *
 * <p>Threading: all SurfaceControl + Canvas work runs on the single-thread
 * {@link #exec} so the {@link Surface} is only ever touched from one thread.
 */
public final class ClusterSpeedOverlay {

    private static final String TAG = "ClusterSpeedOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** {@code surveillance.clusterSpeedEnabled}; absent = off. */
    public static final String CONFIG_KEY = "clusterSpeedEnabled";

    // TOPMOST on the cluster — the speed readout must never be occluded by anything
    // projected there (the nav map, and crucially the blind-spot card, which composites
    // on the SAME cluster layerStack). Integer.MAX_VALUE is the SurfaceFlinger z ceiling;
    // the blind-spot card sits one BELOW it (BsNativeLayer.Z_ORDER = MAX_VALUE - 1) so a
    // turn-signal BS view still draws over the map but the small centre-left speed badge
    // stays visible on top of it.
    private static final int Z_ORDER = Integer.MAX_VALUE;

    // Cluster (fission) layerStack — resolved live; fall back to 1 (the proven Seal
    // value) only when the fission block was found but the stack couldn't be parsed.
    private static final int CLUSTER_LAYER_STACK_FALLBACK = 1;

    // Refresh cadence: 2 Hz is smooth enough for a speed digit without hammering the
    // SDK getter; we only re-render when the displayed value actually changes.
    private static final long POLL_INTERVAL_MS = 500;

    // Idle keep-alive for the worker thread. Once a projection closes, stopOnExec
    // cancels the tick (queue empties), so after this idle window the single worker
    // thread reaps itself — leaving ZERO resident resources (no thread, no layer, no
    // tick) when no projection is up. It transparently respawns on the next start().
    private static final long EXEC_KEEPALIVE_MS = 5000;

    // Badge geometry, as fractions of the cluster panel (robust across cluster sizes).
    // The badge is a SHORT single-line strip ("88 KM/H" on one row): height is small and
    // the width is wide enough to hold the number + unit side by side. It sits just ABOVE
    // the app-drawn TBT banner (RoadSenseMapActivity), left-aligned with it. The banner
    // pins its TOP edge at panel.y/2, so the badge's BOTTOM is placed at panel.y/2 − gap
    // (see createAndShow) — the two must agree on panel.y/2 as the shared seam, which they
    // do since both read the same physical cluster panel height.
    private static final double BADGE_H_FRAC = 0.13;   // ~94px on a 720-tall panel (single line)
    private static final double BADGE_ASPECT = 2.7;    // w / h — wide short strip; fits "888 KM/H" on one line
    private static final int LEFT_MARGIN_PX = 56;      // gap from the left edge (== banner marginStart)
    // Gap (px) between the badge's BOTTOM and the TBT banner's TOP (which sits at panel.y/2).
    // MUST stay in sync with the app's stack seam (RoadSenseMapActivity pins the banner top
    // at panel.y/2, so the badge ends this many px above it).
    private static final int BADGE_BOTTOM_GAP_PX = 12;

    private static volatile ClusterSpeedOverlay instance;

    // True while a head-unit VIEW-MIRROR session ({@link ClusterViewMirrorService}) is compositing
    // the cluster onto the Projection screen. SurfaceFlinger mirrors the WHOLE cluster layer stack
    // with no per-layer exclusion, so this badge — a layer on that same stack — is otherwise
    // re-captured into the head-unit preview (the "speed overlay in the resize box" report). We
    // therefore HIDE the badge layer for the duration of a mirror session and re-show it the
    // instant the mirror detaches. The trade-off is honest and bounded: while the user is at the
    // head unit adjusting the cast (the only time the view-mirror is attached) the badge is not on
    // the cluster either, but it returns immediately on leaving the Projection screen / onPause.
    // Static + volatile so it is authoritative regardless of whether the badge or the mirror
    // arms first (either ordering converges: createAndShow reads it; setMirrorActive reconciles).
    private static volatile boolean sMirrorActive = false;

    private final ScheduledExecutorService exec;
    private Object surfaceControl;     // android.view.SurfaceControl (reflected) — exec thread only
    private Surface surface;           // exec thread only
    private int bufferW;
    private int bufferH;
    // ALL lifecycle state below is touched ONLY on the single exec thread (start/stop
    // just POST commands onto it), so no field here needs to be volatile and there are
    // no cross-thread races — the prior three-writer `active` race is gone by design.
    private boolean running = false;                  // a session is live (layer up + ticking)
    private java.util.concurrent.ScheduledFuture<?> tickFuture;   // the 2 Hz tick, cancellable
    private int lastShownValue = Integer.MIN_VALUE;   // last rendered integer speed
    private boolean lastShownMiles = false;
    private boolean lastWasUnknown = true;            // first frame shows "--" until a real read

    // Pre-built paints/geometry — badge dims are session-stable, so build once in
    // createAndShow() and reuse every render (no per-frame heap churn on the exec thread).
    private RectF badgeRect;
    private android.graphics.Path badgeClip;
    private float badgeRadius;
    private float numBaselineY;
    private float unitBaselineY;
    private float badgeCx;
    private Paint pFill, pSheen, pRim, pNum, pUnit;

    private ClusterSpeedOverlay() {
        // Single-thread scheduled executor whose worker thread REAPS ITSELF after
        // EXEC_KEEPALIVE_MS idle, so when no projection is up there is no resident
        // thread (the badge + tick are already released in stopOnExec). allowCoreThreadTimeOut
        // lets the lone core thread time out; it respawns automatically on the next
        // posted task (start()/stop()). Daemon thread so it never blocks VM exit.
        java.util.concurrent.ScheduledThreadPoolExecutor e =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(r, "ClusterSpeedOverlay");
                    t.setDaemon(true);
                    return t;
                });
        e.setKeepAliveTime(EXEC_KEEPALIVE_MS, TimeUnit.MILLISECONDS);
        e.allowCoreThreadTimeOut(true);
        // Don't keep cancelled periodic tasks (the cancelled tickFuture) sitting in the
        // delay queue — remove them on cancel so the queue genuinely empties and the
        // worker can time out.
        e.setRemoveOnCancelPolicy(true);
        exec = e;
    }

    public static ClusterSpeedOverlay getInstance() {
        if (instance == null) {
            synchronized (ClusterSpeedOverlay.class) {
                if (instance == null) instance = new ClusterSpeedOverlay();
            }
        }
        return instance;
    }

    /** Stop-helper that does NOT construct the singleton (mirrors
     *  {@link ClusterProjectionController#shutdownIfActive}). Skips entirely on a
     *  daemon that never showed the overlay. */
    public static void stopIfActive() {
        ClusterSpeedOverlay i = instance;
        if (i != null) i.stop();
    }

    /**
     * Tell the badge whether a head-unit view-mirror session is live. Called by
     * {@link ClusterViewMirrorService} on attach (true) and detach (false). While true the badge
     * is torn down so the mirror preview doesn't re-capture it; on false the badge re-arms if a
     * projection is still open (the common case: the mirror stopped but the cast is still up).
     *
     * <p>Idempotent + any-thread: it just records the flag and posts a reconcile onto the badge's
     * own exec. Ordering-independent — if the mirror arms before the badge, {@link #startOnExec}'s
     * flag check keeps the badge down; if the badge is already up, the posted stop retires it.
     */
    public static void setMirrorActive(boolean active) {
        sMirrorActive = active;
        ClusterSpeedOverlay i = instance;
        if (i == null) {
            // No badge instance yet. If a mirror just detached we may need to arm the badge, but
            // only the controller's projection-open path constructs+starts it; nothing to do here
            // for the arm case (the controller re-drives start() on its own events). For the
            // suppress case there is likewise no layer to hide. So a null instance is a safe no-op.
            return;
        }
        if (active) {
            i.stop();               // hide the badge for the mirror's duration
        } else {
            i.start();              // re-arm; start()'s own gates no-op if projection is closed
        }
    }

    /** Whether a head-unit view-mirror is currently capturing the cluster stack. */
    static boolean isMirrorActive() { return sMirrorActive; }

    /** Applies a saved {@link #CONFIG_KEY} change to an already-open projection. Any thread. */
    public static void onEnabledChanged() {
        ClusterSpeedOverlay i = instance;
        if (i == null) return;   // only the controller's open path constructs it: nothing is open
        int epoch = i.currentControllerEpoch();
        i.exec.execute(() -> {
            if (i.enabledInConfig()) i.startOnExec(epoch); else i.stopOnExec();
        });
    }

    /** Sentinel epoch meaning "no supersession check" — for callers that have no
     *  open-sequence epoch to pin against (none today; the overload is the live path). */
    private static final int EPOCH_NONE = Integer.MIN_VALUE;

    /**
     * Show the badge + begin the 2 Hz speed refresh. Idempotent. No-op unless the
     * user enabled it ({@code surveillance.clusterSpeedEnabled=true}).
     * Safe to call from any thread — it just posts {@link #startOnExec} onto the
     * single {@link #exec}, where ALL lifecycle state is mutated (no cross-thread
     * races, no flag a foreign thread can clobber).
     */
    public void start() {
        start(EPOCH_NONE);
    }

    /**
     * Epoch-pinned start. {@code armEpoch} is the {@link ClusterProjectionController}
     * open-sequence epoch ({@code currentSeqEpoch()}) captured at the moment the
     * controller decided to arm the badge. {@link #startOnExec} declines if that epoch
     * no longer matches the controller's current epoch — i.e. a close/re-open has
     * SUPERSEDED the open this start was armed for. This closes the show-after-close
     * window that {@link #projectionOpen()} alone cannot: forceClose bumps the epoch in
     * the same synchronized block as {@code projState=ST_CLOSING}, so an epoch-pinned
     * start carrying the open-time value deterministically loses to the close, whereas a
     * stale-visible volatile {@code projState} read could still see {@code ST_OPEN}.
     */
    public void start(int armEpoch) {
        exec.execute(() -> startOnExec(armEpoch));
    }

    /** Hide + release the badge and stop the refresh. Idempotent, any thread. */
    public void stop() {
        exec.execute(this::stopOnExec);
    }

    // ── exec-thread lifecycle (single-threaded — no locks needed) ────────────────────

    private void startOnExec(int armEpoch) {
        if (running) return;                       // already live — idempotent
        // Epoch supersession gate (the AUTHORITATIVE close-race guard). start()/stop()
        // are posts with no happens-before vs the controller's state writes, so a bare
        // volatile projState read in projectionOpen() can still observe a stale ST_OPEN
        // and arm the badge AFTER a foreign-thread forceClose began closing — a
        // show-after-close. forceClose bumps seqEpoch in the SAME synchronized block as
        // projState=ST_CLOSING, so when this start carries the open-time epoch and it no
        // longer matches the controller's current epoch, the open was superseded by a
        // close/re-open: drop the arm. (EPOCH_NONE skips the check for epoch-less callers.)
        if (armEpoch != EPOCH_NONE && armEpoch != currentControllerEpoch()) {
            logger.info("speed overlay start dropped — epoch superseded (armed="
                    + armEpoch + ")");
            return;
        }
        // Belt-and-braces: also tie to the projection's OPEN state. This re-check runs on
        // the exec thread AFTER both the start- and stop-posts have serialized, so it
        // sees the final state and drops a superseded start. Kept alongside the epoch
        // gate so an epoch-less start() (EPOCH_NONE) still can't arm over a closed
        // projection, and so a re-open that lands a NEW epoch but is not yet ST_OPEN is
        // also rejected.
        if (!projectionOpen()) {
            logger.info("speed overlay start dropped — projection not open");
            return;
        }
        if (!enabledInConfig()) {
            logger.debug("speed overlay not enabled (surveillance." + CONFIG_KEY + ")");
            return;
        }
        // Do NOT show the badge while a head-unit view-mirror is capturing the cluster stack: the
        // whole-stack mirror would re-capture the badge into the Projection preview. It re-arms via
        // setMirrorActive(false) when the mirror detaches (the controller also re-drives start()).
        if (sMirrorActive) {
            logger.info("speed overlay start dropped — head-unit mirror active (avoids re-capture)");
            return;
        }
        if (!createAndShow()) return;              // unsupported trim / failure — stay stopped
        running = true;
        tickFuture = exec.scheduleWithFixedDelay(
                this::tick, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("speed overlay started");
    }

    private void stopOnExec() {
        if (tickFuture != null) { tickFuture.cancel(false); tickFuture = null; }
        if (!running && surfaceControl == null) return;   // nothing to tear down
        releaseLayer();
        running = false;
        lastShownValue = Integer.MIN_VALUE;
        lastShownMiles = false;
        lastWasUnknown = true;
        logger.info("speed overlay stopped");
    }

    // ── exec-thread work ─────────────────────────────────────────────────────────

    /** Create + show the layer. Returns true on success (caller then arms the tick),
     *  false on an unsupported trim / failure (caller stays stopped). Exec thread only. */
    private boolean createAndShow() {
        try {
            Context ctx = resolveContext();
            if (ctx == null) { logger.warn("createAndShow: no daemon context"); return false; }

            // Resolve the cluster panel size + compositing stack (authoritative dumpsys
            // parse, reused from BsNativeLayer — the daemon's DisplayManager cache is
            // unreliable for the foreign uid-1000 fission display). Resolve ONCE and derive
            // BOTH the size and the stack from that single descriptor: two independent
            // resolves can straddle a per-re-open layerStack change and size the badge from
            // one display while compositing it onto another's stack.
            BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
            Point panel = BsNativeLayer.clusterDisplaySize(ctx, fd);
            int stack = BsNativeLayer.clusterLayerStack(fd, CLUSTER_LAYER_STACK_FALLBACK);
            // Decline on UNRESOLVED (no fission display) OR stack 0. Per the layerStack
            // contract, 0 == HEAD UNIT — a cluster projection is never legitimately
            // stack 0, and applyGeometry's `!= 0` guard would skip setLayerStack and
            // strand this translucent badge OVER THE INFOTAINMENT SCREEN. So a 0 here
            // means we can't safely target the cluster: show nothing rather than paint
            // over the head unit. (Unlike BsNativeLayer, where stack 0 is a valid
            // head-unit target, this overlay must ALWAYS land on the cluster.)
            if (stack == BsNativeLayer.STACK_UNRESOLVED || stack == 0) {
                logger.warn("createAndShow: cluster stack " + stack
                        + " (unresolved/head-unit) — skipping speed overlay (won't paint over head unit)");
                return false;
            }

            bufferH = (int) Math.round(panel.y * BADGE_H_FRAC);
            bufferW = (int) Math.round(bufferH * BADGE_ASPECT);
            // Width derives from panel HEIGHT, so on a tall/narrow (portrait) cluster the
            // badge could overflow the panel WIDTH. Clamp it to fit (preserving aspect),
            // mirroring the BS card's clampBsRect. No-op on the proven 1920×720 panel.
            int maxW = panel.x - 2 * LEFT_MARGIN_PX;
            if (maxW > 8 && bufferW > maxW) {
                bufferW = maxW;
                bufferH = (int) Math.round(bufferW / BADGE_ASPECT);
            }
            if (bufferW < 8 || bufferH < 8) { bufferW = 254; bufferH = 94; }   // short-strip fallback (matches BADGE_ASPECT)

            if (!createLayer("ClusterSpeed", bufferW, bufferH)) return false;

            // Build the reusable paints/geometry now that the badge size is known.
            buildPaints(bufferW, bufferH);

            // Clamp x too so the badge always sits fully on-panel (left side).
            int x = Math.max(0, Math.min(LEFT_MARGIN_PX, panel.x - bufferW));
            // Stack the badge just ABOVE the TBT banner: the app pins the banner's TOP at
            // panel.y/2, so the badge's BOTTOM sits BADGE_BOTTOM_GAP_PX above that seam.
            // Clamp to >=0 so a very tall badge can't push off the top edge.
            int y = Math.max(0, panel.y / 2 - BADGE_BOTTOM_GAP_PX - bufferH);
            // Arm geometry HIDDEN, post the first "--" buffer, THEN show — so the layer is
            // never composited before a buffer exists (mirrors BsNativeLayer's
            // setGeometryHidden→render→show; avoids a one-transaction empty-layer flicker).
            applyGeometry(surfaceControl, x, y, bufferW, bufferH, Z_ORDER, false, bufferW, bufferH, stack);
            renderNow();   // seeds "--" (lastWasUnknown defaults true) — never the int sentinel
            applyShow(surfaceControl);
            logger.info("speed overlay shown on stack " + stack + " (" + bufferW + "x" + bufferH
                    + ") at (" + x + "," + y + ")");
            return true;
        } catch (Throwable t) {
            logger.warn("createAndShow failed: " + t.getMessage());
            // Release any layer/Surface already allocated by createLayer() before the
            // throw (e.g. buildPaints OOM), so a failed arm leaves a clean state rather
            // than an orphaned, never-shown layer that lingers until the next teardown.
            // releaseLayer() is null-safe, so it's a no-op if nothing was allocated.
            releaseLayer();
            return false;
        }
    }

    /** 2 Hz tick (exec thread): mid-session disable retires the badge; else read speed
     *  + re-render only when the displayed value changes. */
    private void tick() {
        if (!running || surface == null) return;
        // Self-heal: if the projection closed without our stop() landing (a missed/raced
        // teardown post), retire the badge so it never lingers over restored gauges or
        // ticks forever while parked.
        if (!projectionOpen()) { stopOnExec(); return; }
        // Mid-session disable: if the user turned the badge off via UCM while a
        // (possibly whole-drive) projection is held open, retire it now.
        if (!enabledInConfig()) { stopOnExec(); return; }
        // A head-unit mirror armed AFTER the badge was already showing (user opened the
        // Projection screen mid-drive): retire the badge so it's not re-captured. It re-arms on
        // the mirror's detach via setMirrorActive(false).
        if (sMirrorActive) { logger.info("speed overlay retired — head-unit mirror armed"); stopOnExec(); return; }
        try {
            double kmh = readSpeedKmh();
            boolean miles = unitsMiles();
            boolean unknown = Double.isNaN(kmh) || kmh < 0;
            int value = unknown ? 0 : (int) Math.round(miles ? kmh * 0.621371 : kmh);
            // Skip the lockCanvas when nothing visible changed.
            if (!unknown && value == lastShownValue && miles == lastShownMiles && !lastWasUnknown) return;
            if (unknown && lastWasUnknown && miles == lastShownMiles) return;
            lastShownValue = value;
            lastShownMiles = miles;
            lastWasUnknown = unknown;
            renderNow();
        } catch (Throwable t) {
            logger.debug("tick error: " + t.getMessage());
        }
    }

    private void renderNow() {
        Surface s = surface;
        if (s == null) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            if (c == null) return;
            drawGlassBadge(c,
                    lastWasUnknown ? "--" : String.valueOf(lastShownValue),
                    lastShownMiles ? "mph" : "km/h");
        } catch (Throwable t) {
            logger.debug("renderNow error: " + t.getMessage());
        } finally {
            if (c != null) try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
        }
    }

    // ── Glass badge drawing ────────────────────────────────────────────────────────

    /**
     * Build the reusable Paints, clip Path, gradient + cached geometry for the glass
     * badge ONCE per session (the badge dimensions are fixed for the session). Reused by
     * every {@link #drawGlassBadge} so there is no per-frame heap churn on the exec
     * thread. Exec thread only (called from createAndShow before the first render).
     */
    private void buildPaints(int w, int h) {
        float inset = h * 0.05f;
        badgeRect = new RectF(inset, inset, w - inset, h - inset);
        badgeRadius = h * 0.30f;   // rounder corners for the short strip
        badgeCx = w / 2f;
        // Single-line layout: number + unit share ONE baseline, drawn as a centred pair
        // (see drawGlassBadge). One baseline near the vertical centre of the short strip.
        numBaselineY = h * 0.68f;
        unitBaselineY = numBaselineY;   // same line as the number now

        badgeClip = new android.graphics.Path();
        badgeClip.addRoundRect(badgeRect, badgeRadius, badgeRadius, android.graphics.Path.Direction.CW);

        pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(0x6B, 0x10, 0x14, 0x18));   // ~42% alpha translucent near-black

        pSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSheen.setShader(new LinearGradient(0, badgeRect.top, 0, badgeRect.top + badgeRect.height() * 0.55f,
                Color.argb(0x33, 0xFF, 0xFF, 0xFF), Color.argb(0x00, 0xFF, 0xFF, 0xFF),
                Shader.TileMode.CLAMP));

        pRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        pRim.setStyle(Paint.Style.STROKE);
        pRim.setStrokeWidth(Math.max(1.5f, h * 0.012f));
        pRim.setColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF));

        // Number + unit are drawn side by side on one line and positioned as a centred
        // group (drawGlassBadge measures the total run width), so both are LEFT-aligned
        // here and the group is offset to centre it. Text sizes scale with the (now
        // shorter) strip height; the number fills most of the row, the unit is smaller.
        pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
        pNum.setColor(Color.WHITE);
        DaemonFonts.apply(pNum, Typeface.BOLD);
        pNum.setTextAlign(Paint.Align.LEFT);
        pNum.setTextSize(h * 0.62f);

        pUnit = new Paint(Paint.ANTI_ALIAS_FLAG);
        pUnit.setColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF));
        DaemonFonts.apply(pUnit, Typeface.NORMAL);
        pUnit.setTextAlign(Paint.Align.LEFT);
        pUnit.setTextSize(h * 0.34f);
    }

    /**
     * Paint a translucent "frosted glass" rounded-rectangle speed badge into the
     * transparent buffer using the pre-built paints: a semi-transparent dark fill (the
     * map shows through), a soft top sheen gradient, a thin light rim, the big speed
     * numerals, and a small unit label. No real backdrop blur is possible from a
     * separate SurfaceControl layer, so the sheen + translucency emulate glass. Only the
     * two text strings change per frame.
     */
    private void drawGlassBadge(Canvas c, String value, String unit) {
        if (pFill == null) return;   // paints not built yet (shouldn't happen post-createAndShow)
        // Clear to fully transparent first (RGBA_8888 buffer) so the corners + the
        // translucent fill let the projected content behind show through.
        c.drawColor(0, PorterDuff.Mode.CLEAR);

        // 1) Translucent glass fill.
        c.drawRoundRect(badgeRect, badgeRadius, badgeRadius, pFill);

        // 2) Top sheen, clipped to the rounded rect.
        int sc = c.save();
        c.clipPath(badgeClip);
        c.drawRect(badgeRect.left, badgeRect.top, badgeRect.right,
                badgeRect.top + badgeRect.height() * 0.55f, pSheen);
        c.restoreToCount(sc);

        // 3) Thin light rim.
        c.drawRoundRect(badgeRect, badgeRadius, badgeRadius, pRim);

        // 4)+5) Speed numerals and unit on ONE line, drawn as a horizontally-centred group:
        // "88 KM/H". Measure the number, a spacer, and the unit, then start the run so the
        // whole thing is centred in the badge (both paints are LEFT-aligned). The unit
        // baseline is nudged up slightly so its smaller cap-height looks optically aligned
        // with the number's midline rather than sitting on the same baseline.
        //
        // Guard: on a BSP with an unusable font system, measureText/drawText
        // would abort the daemon process natively (gDefaultTypeface == null).
        // The glass badge itself already drew above; skip only the numerals so
        // the daemon survives. Healthy devices always take this path.
        if (!DaemonFonts.canDrawText()) return;
        String unitStr = unit.toUpperCase(Locale.US);
        float gap = pNum.getTextSize() * 0.22f;          // space between number and unit
        float numW = pNum.measureText(value);
        float unitW = pUnit.measureText(unitStr);
        float totalW = numW + gap + unitW;
        float startX = badgeCx - totalW / 2f;
        c.drawText(value, startX, numBaselineY, pNum);
        c.drawText(unitStr, startX + numW + gap, unitBaselineY - pNum.getTextSize() * 0.06f, pUnit);
    }

    // ── Speed + units ───────────────────────────────────────────────────────────────

    /** True while the OEM cluster projection is genuinely OPEN. The overlay only makes
     *  sense over a live projection; gating start + each tick on this ties the badge's
     *  lifecycle to the projection regardless of start/stop post ordering. */
    private boolean projectionOpen() {
        try {
            return ClusterProjectionController.getInstance().isOpen();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The controller's current open/close-sequence epoch, for the supersession gate in
     *  {@link #startOnExec}. On any failure return a value that can never equal a real
     *  armed epoch (so the start is dropped — fail-safe toward NOT arming over a possibly
     *  closed projection). */
    private int currentControllerEpoch() {
        try {
            return ClusterProjectionController.getInstance().currentSeqEpoch();
        } catch (Throwable t) {
            return EPOCH_NONE;
        }
    }

    private double readSpeedKmh() {
        try {
            return com.overdrive.app.byd.BydDataCollector.getInstance().readCurrentSpeedKmh();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** True when the user's distance unit is miles — reuses the SAME preference the
     *  map/Trips use (tripAnalytics.distanceUnit), via MapNetworking. */
    private boolean unitsMiles() {
        try {
            return com.overdrive.app.navmap.nav.MapNetworking.INSTANCE.getUseMiles();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opt-in, default off. One key for the Projection screen and RoadSense switches. */
    private boolean enabledInConfig() {
        try {
            org.json.JSONObject s = UnifiedConfigManager.getSurveillance();
            return s != null && s.optBoolean(CONFIG_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── SurfaceControl reflection (mirrors BsNativeLayer's proven path) ──────────────

    private boolean createLayer(String name, int w, int h) {
        if (surfaceControl != null) return true;
        try {
            Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setName", String.class).invoke(builder, name);
            b.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            // RGBA_8888 + non-opaque so the translucent glass + corners are honoured
            // (the SurfaceControl.Builder default is OPAQUE, which discards alpha —
            // the exact "black rectangle" gotcha BsNativeLayer documents).
            try { b.getMethod("setFormat", int.class).invoke(builder, PixelFormat.RGBA_8888); } catch (NoSuchMethodException ignored) {}
            try { b.getMethod("setOpaque", boolean.class).invoke(builder, false); } catch (NoSuchMethodException ignored) {}
            surfaceControl = b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("createLayer failed: " + t.getMessage());
            return false;
        }
        if (surfaceControl == null) return false;
        try {
            surface = new Surface((android.view.SurfaceControl) surfaceControl);
        } catch (Throwable t) {
            logger.warn("new Surface(SurfaceControl) failed: " + t.getMessage());
            releaseLayer();
            return false;
        }
        return true;
    }

    private static void applyGeometry(Object sc, int x, int y, int w, int h, int z, boolean show,
                                      int bufW, int bufH, int layerStack) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sc, z); } catch (Throwable ignored) {}
            if (layerStack != 0) {
                try { txCls.getMethod("setLayerStack", scCls, int.class).invoke(tx, sc, layerStack); } catch (Throwable ignored) {}
            }
            try { txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sc, 1.0f); } catch (Throwable ignored) {}
            boolean geom = false;
            try {
                Rect src = new Rect(0, 0, bufW, bufH);
                Rect dst = new Rect(x, y, x + w, y + h);
                txCls.getMethod("setGeometry", scCls, Rect.class, Rect.class, int.class)
                        .invoke(tx, sc, src, dst, 0);
                geom = true;
            } catch (Throwable ignored) {}
            if (!geom) {
                try {
                    txCls.getMethod("setPosition", scCls, float.class, float.class)
                            .invoke(tx, sc, (float) x, (float) y);
                } catch (Throwable ignored) {}
            }
            if (show) try { txCls.getMethod("show", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            applyAndClose(txCls, tx);
        } catch (Throwable t) {
            logger.warn("applyGeometry failed: " + t.getMessage());
        }
    }

    /** One-shot transaction to show the layer (used after the first buffer is posted,
     *  so the layer is never composited before it has content). */
    private static void applyShow(Object sc) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("show", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            applyAndClose(txCls, tx);
        } catch (Throwable t) {
            logger.warn("applyShow failed: " + t.getMessage());
        }
    }

    /** apply() the transaction, then close() it so its native object is freed
     *  DETERMINISTICALLY (SurfaceControl.Transaction is Closeable on API 29+; apply()
     *  commits but does NOT free the handle). Reflective close is best-effort. */
    private static void applyAndClose(Class<?> txCls, Object tx) throws Exception {
        txCls.getMethod("apply").invoke(tx);
        try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
    }

    private void releaseLayer() {
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        if (surfaceControl != null) {
            try {
                Class<?> scCls = Class.forName("android.view.SurfaceControl");
                Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
                Object tx = txCls.getDeclaredConstructor().newInstance();
                try { txCls.getMethod("hide", scCls).invoke(tx, surfaceControl); } catch (Throwable ignored) {}
                try { txCls.getMethod("reparent", scCls, scCls).invoke(tx, surfaceControl, null); } catch (Throwable ignored) {}
                // Each step independently guarded so a reflective apply() failure can't
                // skip the explicit release() below — the native layer must ALWAYS be
                // released deterministically when projection stops, not left to GC.
                try { applyAndClose(txCls, tx); } catch (Throwable ignored) {}
                try { scCls.getMethod("release").invoke(surfaceControl); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                logger.debug("releaseLayer failed: " + t.getMessage());
            }
            surfaceControl = null;
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
