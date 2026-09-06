package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Daemon-owned SurfaceControl buffer layer for the native blind-spot overlay.
 *
 * <p>The blind-spot stitched view (GpuStreamScaler view 7/8, libod) is rendered
 * by the daemon's GL pipeline (PanoramicCameraGpu PASS 1C) straight into this
 * layer's {@link Surface} via {@code EGLCore.createWindowSurface} — GPU → screen
 * with NO encoder, NO WebSocket, NO MediaCodec decoder. Everything here runs in
 * the daemon (UID 2000), which owns the GL context and the hidden-API
 * SurfaceControl reflection (validated on this firmware by BsSurfaceControlSpike:
 * non-fullscreen layers composite, EGL-on-SurfaceControl works, and
 * setGeometry/setPosition exist).
 *
 * <p>Reflection mirrors {@link ScreenDeterrent}'s proven path. SurfaceControl
 * layers have no InputChannel, so position/size come from config (UCM
 * blindspot.geometry) via {@link #setGeometry}, not finger drag.
 *
 * <p>Threading: {@link #create}/{@link #release} touch the SurfaceControl handle
 * (cheap, any thread — SurfaceFlinger serializes transactions). The returned
 * {@link Surface} must be wrapped in an EGLSurface ON THE GL THREAD by the caller
 * (EGL surfaces are GL-thread-bound). {@link #setGeometry} applies a transaction
 * (no re-render) and is safe from any thread.
 */
public final class BsNativeLayer {

    private static final String TAG = "BsNativeLayer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Above all app windows / system chrome, so the safety overlay is never occluded
    // while driving — but ONE BELOW the cluster speed badge (ClusterSpeedOverlay.Z_ORDER
    // = Integer.MAX_VALUE) so the small centre-left speed readout stays visible on top of
    // the BS card on the cluster (both composite on the same cluster layerStack). Still
    // safely above the map/content. ScreenDeterrent uses Integer.MAX_VALUE too but never
    // contends: it only fires ACC-OFF (parked/sentry) on the head-unit stack, while the
    // blind-spot overlay is ACC-ON + signaling on the cluster stack.
    private static final int Z_ORDER = Integer.MAX_VALUE - 1;

    private final int bufferW;
    private final int bufferH;
    // SurfaceControl layer name (for dumpsys SurfaceFlinger identification) and its
    // z-order. Configurable so this generic buffer-layer primitive can back a second
    // on-screen layer (e.g. video playback) at a DIFFERENT z without contending with
    // the blind-spot card. Defaults preserve the original blind-spot behaviour exactly.
    private final String layerName;
    private final int zOrder;
    private Object surfaceControl;     // android.view.SurfaceControl (reflected)
    private Surface surface;           // wraps surfaceControl, fed to EGL
    private volatile boolean shown = false;

    // Target display's layerStack. 0 = head-unit (the shipping default — the layer
    // composites onto the primary/center screen exactly as before). 1 = the driver
    // cluster (the OEM "fission" PRESENTATION VirtualDisplay, layerStack 1), reached
    // only while an OEM cluster projection is open. CRITICAL: when this is 0 we emit
    // NO setLayerStack op at all, so the head-unit transaction is byte-for-byte the
    // pre-feature behaviour. setLayerStack(SurfaceControl,int) was validated live on
    // this firmware (a UID-2000 layer tagged layerStack=1 composited onto the cluster).
    private volatile int layerStack = 0;

    // Buffer rotation applied when the fixed bufferW×bufferH buffer is composited
    // into the on-screen dest rect: 0/90/180/270 degrees. Used by the single-view
    // blind-spot rotation option (side/rear only). Stored here so every geometry
    // transaction (setGeometry / show) carries it without threading a param through
    // the many call sites; the daemon sets it via setBufferRotation and pairs it
    // with a dest rect whose aspect it already swapped for the 90/270 cases, so the
    // scale stays uniform (no stretch of the baked rounded corners).
    private volatile int bufferRotation = 0;

    public BsNativeLayer(int bufferW, int bufferH) {
        this(bufferW, bufferH, "BlindSpot", Z_ORDER);
    }

    /**
     * Full constructor: choose the SurfaceControl layer name and z-order. Use this to
     * back a second on-screen buffer layer (e.g. video playback) that must sit at a
     * different z than the blind-spot card. The 2-arg constructor keeps the original
     * blind-spot name/z.
     */
    public BsNativeLayer(int bufferW, int bufferH, String layerName, int zOrder) {
        this.bufferW = bufferW;
        this.bufferH = bufferH;
        this.layerName = layerName;
        this.zOrder = zOrder;
    }

    /**
     * Retarget the layer to a display by its layerStack (0 = head-unit, 1 = cluster).
     * Cheap no-op if unchanged. If the layer already exists and is shown, the stack
     * change is re-asserted immediately via a one-shot transaction so a mid-session
     * flip lands without waiting for the next setGeometry/show. The actual placement
     * (and where it composites) is still governed by setGeometry's dest rect.
     */
    public synchronized void setLayerStack(int stack) {
        // Defense-in-depth: a negative stack (e.g. the STACK_UNRESOLVED sentinel) must
        // NEVER be tagged onto the layer — setLayerStack(sc, -1) orphans it onto a
        // dead stack = black. Callers should gate on STACK_UNRESOLVED; ignore here too.
        if (stack < 0) {
            logger.warn("setLayerStack: ignoring negative stack " + stack);
            return;
        }
        if (stack == this.layerStack) return;
        this.layerStack = stack;
        if (surfaceControl != null) {
            // Re-assert stack on the live handle. Re-uses applyGeometry's transaction
            // path with the current visibility so we don't flash or move the card.
            applyLayerStack(surfaceControl, stack);
        }
    }

    /** Current target layerStack (0 head-unit / 1 cluster). */
    public synchronized int getLayerStack() { return layerStack; }

    /**
     * Set the buffer rotation (0/90/180/270 degrees) applied by the next geometry
     * transaction. Any non-multiple-of-90 value is snapped to the nearest 90° step
     * and normalised to [0,270]. Cheap store; the caller re-issues setGeometry (with
     * an aspect-swapped dest rect for 90/270) to make it take effect on screen.
     */
    public synchronized void setBufferRotation(int degrees) {
        int d = ((degrees % 360) + 360) % 360;      // normalise to [0,360)
        this.bufferRotation = (Math.round(d / 90f) * 90) % 360;
    }

    /** Current buffer rotation in degrees (0/90/180/270). */
    public synchronized int getBufferRotation() { return bufferRotation; }

    // Native transform codes for SurfaceControl.Transaction.setGeometry's orientation
    // arg. These are the android_transform_t / NATIVE_WINDOW_TRANSFORM_* bit values,
    // NOT the Surface.ROTATION_* ordinals (0/1/2/3). See rotationConst below.
    private static final int TRANSFORM_IDENTITY = 0;   // no-op
    private static final int TRANSFORM_ROT_90   = 4;   // HAL_TRANSFORM_ROT_90  = 1<<2
    private static final int TRANSFORM_ROT_180  = 3;   // HAL_TRANSFORM_ROT_180 = FLIP_H|FLIP_V
    private static final int TRANSFORM_ROT_270  = 7;   // HAL_TRANSFORM_ROT_270 = ROT_180|ROT_90

    /** Map a 0/90/180/270 degree rotation to the transform code that
     *  {@code SurfaceControl.Transaction.setGeometry(...,orientation)} actually
     *  consumes.
     *
     *  <p>TRAP: the framework annotates that arg {@code @Surface.Rotation} and the
     *  obvious thing is to pass {@code Surface.ROTATION_*} (0/1/2/3). That is WRONG.
     *  The Java {@code setGeometry} forwards the value UNCONVERTED through
     *  {@code nativeSetGeometry} into {@code Transaction::setGeometry}
     *  (SurfaceComposerClient.cpp), whose switch keys on the HAL transform bitmask
     *  ({@code NATIVE_WINDOW_TRANSFORM_*}), not the Surface rotation ordinal. Those
     *  numberings disagree: ordinal 1(ROTATION_90)=FLIP_H, 2(ROTATION_180)=FLIP_V,
     *  3(ROTATION_270)=ROT_180. So passing the ordinals turned the card's 90° into a
     *  horizontal mirror, 180° into a vertical mirror, and 270° into a plain 180° turn
     *  — the "distorted / mirror-image" blind-spot rotation bug (all models). The
     *  anamorphic stretch rode along because {@link #applyGeometry}'s caller pre-swaps
     *  the dest rect to 3:4 for a quarter turn (expecting an axis-transposing rotation),
     *  but FLIP_H/FLIP_V do NOT transpose axes, so a 4:3 buffer was scaled into a 3:4
     *  dest with unequal x/y scale. Emitting the correct ROT_90/270 codes (which DO
     *  transpose) makes that 3:4 dest match again → uniform scale, clean rotation.
     *  0° stays identity (why the un-rotated card always looked right). */
    private static int rotationConst(int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90:  return TRANSFORM_ROT_90;
            case 180: return TRANSFORM_ROT_180;
            case 270: return TRANSFORM_ROT_270;
            default:  return TRANSFORM_IDENTITY;
        }
    }

    /** Create the buffer layer (does NOT show it yet). Returns false on failure. */
    public synchronized boolean create() {
        if (surfaceControl != null) return true;
        surfaceControl = createBufferLayer(layerName, bufferW, bufferH);
        if (surfaceControl == null) {
            logger.warn("create: SurfaceControl buffer layer creation failed");
            return false;
        }
        try {
            surface = new Surface((android.view.SurfaceControl) surfaceControl);
        } catch (Throwable t) {
            logger.warn("create: new Surface(SurfaceControl) failed: " + t.getMessage());
            releaseScOnly();
            return false;
        }
        logger.info("BS native layer created (" + bufferW + "x" + bufferH + ")");
        return true;
    }

    /** The Android Surface to render into (wrap in EGLSurface on the GL thread). */
    public synchronized Surface getSurface() { return surface; }


    public synchronized boolean isCreated() { return surfaceControl != null; }
    public boolean isShown() { return shown; }

    /**
     * Place the layer on screen at (x,y) sized w×h at the BS z-order, and show it.
     * The buffer is always {@link #bufferW}×{@link #bufferH}; setGeometry scales it
     * to the on-screen dest rect, so resize is a transaction — no GL re-init.
     */
    public synchronized void setGeometry(int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        applyGeometry(surfaceControl, 0, 0, bufferW, bufferH, x, y, w, h, zOrder, true,
                bufferW, bufferH, layerStack, bufferRotation);
        shown = true;
    }

    /**
     * Position + show the layer, scaling a SUB-RECT of the buffer (source crop) into the
     * on-screen dest rect. Used ONLY by the cluster-mirror ZOOM (crop-to-cover) scaling
     * mode; the default {@link #setGeometry(int,int,int,int)} scales the FULL buffer and
     * is byte-for-byte unchanged, so the blind-spot card / speed overlay (which rely on
     * src == full buffer, since libod bakes rounded corners + margins into the whole
     * buffer) are untouched by this overload. The src rect is clamped to the buffer.
     */
    public synchronized void setGeometry(Rect src, int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        Rect s = clampSrc(src);
        applyGeometry(surfaceControl, s.left, s.top, s.width(), s.height(), x, y, w, h,
                zOrder, true, bufferW, bufferH, layerStack, bufferRotation);
        shown = true;
    }

    /** Position the layer WITHOUT showing it (single transaction, show=false).
     *  Used at enable to arm the geometry while the card is still hidden, avoiding
     *  a show-then-hide one-frame flash of an unrendered layer. */
    public synchronized void setGeometryHidden(int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        applyGeometry(surfaceControl, 0, 0, bufferW, bufferH, x, y, w, h, zOrder, false,
                bufferW, bufferH, layerStack, bufferRotation);
        // shown stays false
    }

    /** Source-cropped variant of {@link #setGeometryHidden} for the ZOOM mode's initial
     *  arm (avoids an empty-layer flash). See {@link #setGeometry(Rect,int,int,int,int)}. */
    public synchronized void setGeometryHidden(Rect src, int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        Rect s = clampSrc(src);
        applyGeometry(surfaceControl, s.left, s.top, s.width(), s.height(), x, y, w, h,
                zOrder, false, bufferW, bufferH, layerStack, bufferRotation);
        // shown stays false
    }

    /** Clamp a requested source crop to a valid, non-empty sub-rect of the buffer so a
     *  degenerate crop (zero/negative/oversized) can never reach SurfaceFlinger (which
     *  would fault or composite black). Defensive; the callers already compute in-bounds. */
    private Rect clampSrc(Rect src) {
        if (src == null) return new Rect(0, 0, bufferW, bufferH);
        int l = Math.max(0, Math.min(src.left, bufferW - 1));
        int t = Math.max(0, Math.min(src.top, bufferH - 1));
        int r = Math.max(l + 1, Math.min(src.right, bufferW));
        int b = Math.max(t + 1, Math.min(src.bottom, bufferH));
        return new Rect(l, t, r, b);
    }

    /** Hide the layer (keeps it allocated for a fast re-show). */
    public synchronized void hide() {
        if (surfaceControl == null || !shown) return;
        applyVisibility(surfaceControl, false);
        shown = false;
    }

    /** Re-show at the last geometry (cheap transaction). */
    public synchronized void show() {
        if (surfaceControl == null || shown) return;
        applyVisibility(surfaceControl, true);
        shown = true;
    }

    /** Hide, remove from hierarchy, reparent-null, and release the layer + Surface. */
    public synchronized void release() {
        // Critical: release and remove the SurfaceControl from SurfaceFlinger's layer
        // hierarchy BEFORE destroying the client Surface / BufferQueue. Destroying the
        // buffer producer while SurfaceFlinger's layer tree still holds the GraphicBuffer
        // leads to SIGSEGV in Qualcomm's Adreno driver (validate_resource_memory_layout_metadata)
        // during concurrent snapshot composition (e.g. captureScreenCommon).
        if (surfaceControl != null) {
            releaseSurfaceControl(surfaceControl);
            surfaceControl = null;
        }
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        shown = false;
    }

    private void releaseScOnly() {
        if (surfaceControl != null) {
            releaseSurfaceControl(surfaceControl);
            surfaceControl = null;
        }
    }

    /** Full-panel size for sizing/clamping geometry. */
    public static Point displaySize(Context ctx) {
        Point p = new Point(1920, 1080);
        try {
            android.view.WindowManager wm =
                (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        } catch (Throwable t) {
            logger.debug("displaySize failed: " + t.getMessage());
        }
        return p;
    }

    // ── SurfaceControl reflection (mirrors ScreenDeterrent's proven path) ──────

    private static Object createBufferLayer(String name, int w, int h) {
        try {
            Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setName", String.class).invoke(builder, name);
            b.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            // CRITICAL: SurfaceControl.Builder defaults mFormat = PixelFormat.OPAQUE
            // on this firmware (Android 10), which builds an RGBx_8888 layer that
            // SurfaceFlinger composites with isOpaque=1 — DISCARDING the alpha
            // channel. Our card has TRANSPARENT rounded corners + a transparent
            // margin band + transparent regions the projection doesn't cover (the
            // shader emits premultiplied alpha=0 there), so with the opaque default
            // every alpha<1 pixel composites as solid BLACK — the "black rectangle"
            // around/below the video. PROVEN on-device: dumpsys SurfaceFlinger
            // showed `defaultPixelFormat=RGBx_8888, isOpaque=1` while the GPU buffer
            // was RGBA_8888. Fix: force the layer format to RGBA_8888 (==1) so the
            // alpha channel is honored. setOpaque(false) alone does NOT fix it
            // because isOpaque is derived from the opaque FORMAT.
            try { b.getMethod("setFormat", int.class).invoke(builder, android.graphics.PixelFormat.RGBA_8888); } catch (NoSuchMethodException ignored) {}
            // Belt-and-braces: also clear the opaque flag.
            try { b.getMethod("setOpaque", boolean.class).invoke(builder, false); } catch (NoSuchMethodException ignored) {}
            return b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("createBufferLayer failed: " + t.getMessage());
            return null;
        }
    }

    private static void applyGeometry(Object sc, int srcX, int srcY, int srcW, int srcH,
                                      int x, int y, int w, int h, int z, boolean show,
                                      int bufW, int bufH, int layerStack, int rotationDeg) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sc, z); } catch (Throwable ignored) {}
            // Retarget to the cluster's layerStack ONLY when non-zero. When 0 (head-unit
            // default) we emit nothing here, so the transaction is byte-for-byte the
            // pre-feature head-unit path. setLayerStack(SurfaceControl,int) proven live.
            if (layerStack != 0) {
                try { txCls.getMethod("setLayerStack", scCls, int.class).invoke(tx, sc, layerStack); } catch (Throwable ignored) {}
            }
            try { txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sc, 1.0f); } catch (Throwable ignored) {}
            // setGeometry(sc, sourceCrop, destFrame, orientation) — validated present
            // on this firmware. Scales the source-crop sub-rect of the buffer into the
            // dest rect, rotating it by `orientation` (a NATIVE_WINDOW_TRANSFORM_* /
            // HAL transform code from rotationConst — NOT a Surface.ROTATION_* ordinal;
            // see rotationConst's javadoc for why that distinction is load-bearing).
            // The source crop is the FULL buffer for every caller except the cluster
            // mirror's ZOOM mode; the caller supplies a dest rect already sized for the
            // rotated buffer (w/h swapped for 90/270), so the scale stays uniform.
            int orientation = rotationConst(rotationDeg);
            boolean geom = false;
            try {
                Rect src = new Rect(srcX, srcY, srcX + srcW, srcY + srcH);
                Rect dst = new Rect(x, y, x + w, y + h);
                txCls.getMethod("setGeometry", scCls, Rect.class, Rect.class, int.class)
                        .invoke(tx, sc, src, dst, orientation);
                geom = true;
            } catch (Throwable ignored) {}
            if (!geom) {
                // Fallback: position + scale via setPosition + setMatrix. setMatrix is a
                // 2×2 (dsdx,dtdx,dsdy,dtdy); compose the rotation into it so a device
                // lacking the 4-arg setGeometry still honours the rotation option.
                // The dest rect is now ALWAYS the source's 4:3 aspect (the caller no
                // longer swaps to 3:4 for quarter turns — see the primary path's note),
                // and the buffer is itself 4:3, so a SINGLE uniform scale s = w/bufW
                // (== h/bufH) keeps square pixels for every angle; only the rotation
                // signs differ. This matches the native setGeometry scale (which also
                // scales by dstW/srcW and rotates after), so a device on the fallback
                // rotates the same direction as one on the primary path.
                // NOTE: setMatrix rotates about the buffer ORIGIN and cannot express a
                // pivot offset, so on a device that lacks the 4-arg setGeometry the
                // rotated card is placed best-effort (may be offset from the exact dest
                // corner) — never stretched or mirrored. The primary setGeometry path is
                // the one used on this firmware (validated present on API 29).
                try {
                    txCls.getMethod("setPosition", scCls, float.class, float.class)
                            .invoke(tx, sc, (float) x, (float) y);
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Method setMatrix = txCls.getMethod("setMatrix",
                            scCls, float.class, float.class, float.class, float.class);
                    int d = ((rotationDeg % 360) + 360) % 360;
                    // Uniform scale (dst is 4:3 like the buffer, so both axes agree).
                    float s = (float) w / (float) Math.max(1, bufW);
                    if (d == 90) {
                        setMatrix.invoke(tx, sc, 0f, s, -s, 0f);
                    } else if (d == 180) {
                        setMatrix.invoke(tx, sc, -s, 0f, 0f, -s);
                    } else if (d == 270) {
                        setMatrix.invoke(tx, sc, 0f, -s, s, 0f);
                    } else {
                        setMatrix.invoke(tx, sc, s, 0f, 0f, s);
                    }
                } catch (Throwable ignored) {}
            }
            if (show) try { txCls.getMethod("show", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyGeometry failed: " + t.getMessage());
        }
    }

    private static void applyVisibility(Object sc, boolean show) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            String m = show ? "show" : "hide";
            try { txCls.getMethod(m, scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyVisibility failed: " + t.getMessage());
        }
    }

    /** One-shot transaction to (re)assign the layer's layerStack on the live handle.
     *  Used by {@link #setLayerStack} for a mid-session target flip. A no-op stack of
     *  0 still issues the call to MOVE the layer back to the head-unit if it was on
     *  the cluster — callers only invoke this when the value actually changed. */
    private static void applyLayerStack(Object sc, int layerStack) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayerStack", scCls, int.class).invoke(tx, sc, layerStack); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyLayerStack failed: " + t.getMessage());
        }
    }

    /** Resolve the live descriptor and return the corresponding cluster's real size. */
    public static Point clusterDisplaySize(Context ctx) {
        return clusterDisplaySize(ctx, resolveFissionDisplay());
    }

    /**
     * Real size of the driver-cluster display (the OEM "fission" VirtualDisplay).
     * The supplied descriptor lets callers use the SAME identity for display routing and
     * sizing. This matters when stale fission entries or another PRESENTATION display exist:
     * choosing by name or category first can combine one display's id with another's size.
     *
     * <p>Resolution order is exact positive displayId via DisplayManager/getRealSize, the
     * real size parsed from that descriptor's dumpsys line, then an identity-matched dumpsys
     * retry. A name lookup is allowed only when no id was resolved. An arbitrary presentation
     * display is never accepted as the cluster.
     */
    public static Point clusterDisplaySize(Context ctx, FissionDisplay fission) {
        Point p = new Point(1920, 720);
        try {
            if (ctx == null) throw new IllegalStateException("no context");
            android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) throw new IllegalStateException("no DisplayManager");
            android.view.Display chosen = null;
            android.view.Display[] displays = dm.getDisplays();
            if (displays != null) {
                // 1) Exact id from the authoritative live fission DisplayInfo line.
                if (fission != null && fission.displayId > 0) {
                    for (android.view.Display d : displays) {
                        if (d.getDisplayId() == fission.displayId) {
                            chosen = d;
                            break;
                        }
                    }
                }
                // 2) Name fallback only when dumpsys did not resolve an identity.
                if (chosen == null && (fission == null || fission.displayId < 0)) {
                    for (android.view.Display d : displays) {
                        String n = d.getName();
                        if (d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY && n != null
                                && n.toLowerCase(java.util.Locale.US).contains("fission")) {
                            chosen = d;
                            break;
                        }
                    }
                }
            }
            if (chosen != null) {
                Point got = new Point();
                chosen.getRealSize(got);
                if (got.x > 0 && got.y > 0) return got;
                // Some A10 builds return 0×0 for getRealSize on a non-default Display
                // without a display-bound Context — retry via a display Context.
                try {
                    Context dctx = ctx.createDisplayContext(chosen);
                    android.view.WindowManager wm =
                        (android.view.WindowManager) dctx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        Point got2 = new Point();
                        wm.getDefaultDisplay().getRealSize(got2);
                        if (got2.x > 0 && got2.y > 0) return got2;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            logger.debug("clusterDisplaySize failed: " + t.getMessage());
        }
        if (fission != null && fission.width > 0 && fission.height > 0) {
            return new Point(fission.width, fission.height);
        }
        // DisplayManager couldn't surface the fission display (its cache misses the
        // foreign uid-1000 display on many models). Retry dumpsys, tied to the same id.
        int expectedId = fission != null ? fission.displayId : -1;
        Point fromDump = clusterDisplaySizeViaDumpsys(expectedId);
        if (fromDump != null && fromDump.x > 0 && fromDump.y > 0) {
            logger.info("clusterDisplaySize: resolved " + fromDump.x + "x" + fromDump.y
                    + " from dumpsys (DisplayManager cache missed the fission display)");
            return fromDump;
        }
        // Only now fall back to the fixed 1920×720. Warn loudly: on a model whose real
        // cluster panel differs AND whose dumpsys layout we couldn't parse, this
        // mis-sizes the projection.
        logger.warn("clusterDisplaySize: fission panel not found via DisplayManager OR "
                + "identity-matched dumpsys — using fixed 1920x720 fallback"
                + " (displayId=" + expectedId + ", may mis-size on non-Seal clusters)");
        return p;
    }

    /**
     * Parse the fission cluster display's REAL physical resolution from
     * {@code dumpsys display}, robust to per-model output layout. Returns null if no
     * fission block with a parseable {@code W x H} is found.
     *
     * <p>The logical-display DisplayInfo line for the fission display inlines its real
     * size, e.g.
     *   {@code DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", real 1920 x 720, ...}}
     * or {@code ... 1920 x 720, ...} / {@code ... 1920x720 ...}. We scan for a line
     * containing {@code "fission"} and extract the first {@code <W> x <H>} (or
     * {@code <W>x<H>}) integer pair on it. Same uid-2000/shell dumpsys access + same
     * "fission" keying as {@link #resolveFissionDisplay()}, so it works wherever that
     * does. Bounds-checked (1..8192) so a stray small pair (e.g. a density "1 x 1")
     * can't win. Best-effort: any failure returns null and the caller uses its fallback.
     */
    private static Point clusterDisplaySizeViaDumpsys(int expectedDisplayId) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            Point best = null;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(java.util.Locale.US);
                if (!low.contains("fission")
                        || low.matches(".*\\bstate[ =]+(off|unknown)\\b.*")) {
                    continue;
                }
                int lineId = extractDisplayIdOnLine(line);
                if (expectedDisplayId > 0 && lineId != expectedDisplayId) continue;
                Point candidate = extractLargestSizeOnLine(line);
                if (candidate != null) {
                    best = candidate; // Prefer the last live Base/Override DisplayInfo line.
                    logger.info("clusterDisplaySizeViaDumpsys raw: " + line.trim());
                }
            }
            return best;
        } catch (Throwable t) {
            logger.debug("clusterDisplaySizeViaDumpsys failed: " + t.getMessage());
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Parse the fission display's REAL physical size from a {@code dumpsys display} line.
     *
     * <p>The DisplayInfo line inlines SEVERAL {@code W x H} pairs, e.g.
     * {@code ... app 1920 x 720, real 1920 x 720, overscan (80,50,80,50), largest app 1920 x 1920,
     * smallest app 720 x 720, ...}. The {@code real}/{@code app} pair is the authoritative panel
     * size (1920×720 = 8:3 on the Seal); {@code largest app}/{@code smallest app} are AMS's
     * rotation/overscan envelope bounds (here 1920×1920 and 720×720) and are NOT the panel — a
     * plain "largest area wins" scan wrongly picked {@code 1920 x 1920}, giving a 1:1 aspect that
     * made the projection box render SQUARE (confirmed on-car: cluster-mirror-status reported
     * panelH=1920). So we prefer, in order: the {@code real} pair, then the {@code app} pair (but
     * NOT {@code largest app}/{@code smallest app}), and only if neither token is present do we
     * fall back to the first valid pair on the line (covers the DisplayDeviceInfo line, whose bare
     * leading {@code 1920 x 720} carries no envelope pairs).
     */
    /** {@link Point} wrapper over the pure {@link #parseSizeFromDumpsysLine} parser. Kept because
     *  {@code new Point(w,h)} only stores its args on a real device — under the plain-JVM unit test
     *  the {@code android.graphics.Point} stub is a no-op, so ALL parsing is done in int[] space
     *  and the Point is built ONLY here (never exercised by the test). */
    private static Point extractLargestSizeOnLine(String line) {
        int[] wh = parseSizeFromDumpsysLine(line);
        return wh == null ? null : new Point(wh[0], wh[1]);
    }

    /**
     * Pure size parse (returns {@code [w, h]} or null) — the testable core of
     * {@link #extractLargestSizeOnLine}. Package-private so the unit test can pin it against real
     * on-car {@code dumpsys display} lines without a device (and without the Point stub).
     */
    static int[] parseSizeFromDumpsysLine(String line) {
        // 1) Authoritative: "real <W> x <H>".
        int[] real = matchLabeledSize(line, "real");
        if (real != null) return real;
        // 2) Base app size: "app <W> x <H>", but reject the envelope pairs whose "app" is preceded
        //    by "largest"/"smallest".
        int[] app = matchAppSize(line);
        if (app != null) return app;
        // 3) No labeled size on this line — take the FIRST valid pair (not the largest; the bogus
        //    square envelope, if present, would otherwise win an area contest).
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})")
                .matcher(line);
        while (mt.find()) {
            int[] p = validSize(mt.group(1), mt.group(2));
            if (p != null) return p;
        }
        return null;
    }

    /** First {@code <label> <W> x <H>} pair on the line (e.g. label="real"), or null. */
    private static int[] matchLabeledSize(String line, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + label + "\\s+(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})")
                .matcher(line);
        return m.find() ? validSize(m.group(1), m.group(2)) : null;
    }

    /** The base {@code app <W> x <H>} pair, EXCLUDING {@code largest app}/{@code smallest app}. */
    private static int[] matchAppSize(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(largest |smallest )?app\\s+(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})")
                .matcher(line);
        while (m.find()) {
            if (m.group(1) != null) continue;   // skip "largest app" / "smallest app"
            int[] p = validSize(m.group(2), m.group(3));
            if (p != null) return p;
        }
        return null;
    }

    /** Bounds-checked (200..8192) {@code [w, h]}, or null. */
    private static int[] validSize(String ws, String hs) {
        int w = parseIntSafe(ws), h = parseIntSafe(hs);
        if (w >= 200 && w <= 8192 && h >= 200 && h <= 8192) return new int[] { w, h };
        return null;
    }

    /** Resolved cluster (fission) display descriptor from {@code dumpsys display}.
     *  displayId / layerStack are -1 when not parsed. */
    public static final class FissionDisplay {
        public final int displayId;
        public final int layerStack;
        public final int width;
        public final int height;

        FissionDisplay(int displayId, int layerStack) {
            this(displayId, layerStack, 0, 0);
        }

        FissionDisplay(int displayId, int layerStack, int width, int height) {
            this.displayId = displayId;
            this.layerStack = layerStack;
            this.width = width;
            this.height = height;
        }
        /** True when we positively identified the fission display (id and/or stack). */
        public boolean present() { return displayId >= 0 || layerStack >= 0; }
    }

    /**
     * Resolve the cluster (fission) display's displayId AND layerStack from
     * {@code dumpsys display}, robust to per-model output layout.
     *
     * <p>WHY the old same-line grep was model-fragile (root cause of "BS cluster
     * black on some models"): it only captured a layerStack when the literal
     * substrings {@code "fission"} and {@code "layerStack"} appeared on the SAME
     * physical line. On the Seal the {@code mBaseDisplayInfo=DisplayInfo{...}} line
     * happens to inline both; on other trims {@code mLayerStack=N} sits on its OWN
     * line (the {@code "fission"} token is on the neighbouring
     * {@code mPrimaryDisplayDevice=} / DisplayInfo-name line), so the grep ALWAYS
     * missed and silently returned the hardcoded fallback (1). On a model whose
     * real cluster stack ≠ 1 the BS SurfaceControl layer was then tagged onto a
     * dead stack → composited to nothing = black, even though the gauges (opened
     * by independent opcodes) and the map (addressed by displayId) both worked.
     *
     * <p>This parses the Logical Displays section block-by-block (each block keyed
     * by an {@code mDisplayId=N} line), captures that block's {@code mLayerStack=}
     * (or a same-line {@code layerStack N/=N} from its DisplayInfo), and flags the
     * block as the fission one if ANY line in it contains {@code "fission"}. So the
     * id + stack always come from the SAME display and don't depend on the two
     * tokens sharing a line.
     *
     * <p>The daemon's DisplayManager cache is unreliable for the foreign uid-1000
     * fission display (it never gets the add-callback), so dumpsys is the
     * authoritative source (uid 2000 / shell can run it).
     */
    public static FissionDisplay resolveFissionDisplay() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            // AUTHORITATIVE source = a line that mentions "fission" AND carries a
            // same-line layerStack. That is the fission logical-display DisplayInfo
            // line (mBaseDisplayInfo/mOverrideDisplayInfo):
            //   ...DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...,
            //                  layerStack 1, ...}
            // Its "layerStack N" is the SF COMPOSITING stack — the value the BS
            // layer must be tagged with. PROVEN on the Seal across size profiles
            // (stacks 1/2/4). We do NOT trust the bare `mLayerStack=`/
            // `mCurrentLayerStack=` lines that sit a few lines ABOVE it: on the Seal
            // those read 0 (the device-level default), and a block parser that grabs
            // the first bare mLayerStack=0 then sees "fission" on a later line
            // returns 0 → the layer composites onto a dead stack → BLACK/no-video.
            // (That block-parser was a regression — this restores the same-line read.)
            // The displayId is on the SAME fission line ("displayId 1"), so it comes
            // for free. Prefer the LAST such line (Override after Base; identical N).
            java.util.regex.Pattern sameLineStack =
                java.util.regex.Pattern.compile("(?i)layerstack[ =]+(\\d+)");
            String line;
            int foundId = -1, foundStack = -1, foundW = 0, foundH = 0;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(java.util.Locale.US);
                if (!low.contains("fission")) continue;
                // LIVENESS GATE (root cause of "BS card no video + layout fails to
                // restore" on a trim whose fission display never wires to the panel):
                // accept a fission line's displayId/layerStack ONLY when that SAME line
                // also reports the display is ON. The authoritative fission DisplayInfo
                // line on this firmware inlines all three tokens, e.g.
                //   ...DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...,
                //                  layerStack 1, ..., type VIRTUAL, state ON, ...}
                // so a same-line "state on" / "state=on" is the correct, model-robust
                // discriminator (no block parsing — same rationale as reading layerStack
                // same-line). WHY THIS MATTERS: SurfaceFlinger's layerStack counter is
                // monotonic and process-global (survives daemon restarts), so a fresh
                // daemon can read an already-high stack (observed: 5→6 on a just-restarted
                // daemon). The OLD lenient grep accepted ANY "fission"+layerStack line —
                // including a stale/transient/not-yet-wired entry — so the show path
                // tagged the BS SurfaceControl layer (and the speed badge) onto a stack
                // with NO live, panel-wired surface → SurfaceFlinger composited them onto
                // nothing = BLACK/no-video, and the projection-close gauge restore looked
                // "failed" because the takeover never had a healthy surface. Gating on
                // state-on means a non-live fission line yields (-1,-1) → present()=false
                // and clusterLayerStack()=STACK_UNRESOLVED → clusterShowWhenReady DEFERS
                // (the "trim may not support projection" path) instead of painting a dead
                // stack. On a model where the display IS live, its line carries state ON,
                // so this passes and behaviour is unchanged. Strictly safer: it can only
                // make resolution MORE conservative, never paint a card it wouldn't have.
                // (Liveness gate below is now fail-safe: reject only on a POSITIVE dead
                // signal, never require an affirmative live token — see the comment at
                // the gate. Word boundaries avoid matching "state offset"/etc.)
                // DIAGNOSTIC: log the RAW fission DisplayInfo line(s) we parse, so the
                // next on-car capture shows this trim's EXACT dumpsys token format
                // (layerStack / state) — the one datum missing from every prior log that
                // left the live-vs-fallback-stack question unanswerable.
                logger.info("resolveFissionDisplay raw: " + line.trim());
                // LIVENESS GATE — FAIL-SAFE (inverted from the over-strict v26.8 form).
                // v26.8 REQUIRED a same-line "state on"/"state=on" before accepting the
                // line; if THIS trim prints the state token in a different form (e.g.
                // "mState=ON", or state on a neighbouring line), that gate WRONGLY
                // rejected a genuinely live fission line → resolveFissionDisplay returned
                // nothing usable → clusterLayerStack fell through to the FALLBACK CONSTANT
                // 1 (a non-resolved stack) → BS card tagged onto the wrong stack = BLACK,
                // which matches the video(v25.4 lenient)→black(v26.8 gate) crossover on
                // byd-48eafd47. Invert to fail-safe: only REJECT a line that POSITIVELY
                // reports the display is NOT live (state off / state unknown). A line
                // with no parseable state token is ACCEPTED (v25.4 lenient behaviour),
                // so we never over-reject a trim whose format we don't recognise; we
                // still skip a genuinely dead/destroyed entry that says "state off".
                if (low.matches(".*\\bstate[ =]+(off|unknown)\\b.*")) continue;
                // Capture id + stack ATOMICALLY from the authoritative DisplayInfo line
                // (the one that actually carries layerStack), not as independent halves
                // accumulated across lines. A live fission display prints multiple
                // matching lines — DisplayDeviceInfo (state ON, NO layerStack token),
                // mBaseDisplayInfo + mOverrideDisplayInfo (state ON + "displayId N" +
                // "layerStack N") — all for the SAME display, so on this firmware the
                // halves agree. But pairing the layerStack with the displayId from the
                // SAME physical line makes the parse robust if a future/secondary fission
                // entry ever appears: we only adopt the stack together with that line's
                // own id, so a stack can never be mismatched to a different display's id.
                // The id-bearing layerStack line is the contract (DisplayInfo inlines
                // both); fall back to a same-line id with no stack only when no
                // layerStack line was seen yet. "Prefer LAST" still holds (Override after
                // Base; identical N).
                java.util.regex.Matcher m = sameLineStack.matcher(line);
                int id = extractDisplayIdOnLine(line);
                Point size = extractLargestSizeOnLine(line);
                if (m.find() && id >= 0) {
                    boolean sameDisplay = foundId == id;
                    foundStack = parseIntSafe(m.group(1));
                    foundId = id;                       // id paired with THIS stack's line
                    if (size != null) {
                        foundW = size.x;
                        foundH = size.y;
                    } else if (!sameDisplay) {
                        // Never carry dimensions from a different fission entry into this id.
                        foundW = 0;
                        foundH = 0;
                    }
                } else if (id >= 0 && foundStack < 0) {
                    boolean sameDisplay = foundId == id;
                    foundId = id;                        // id-only line, no stack seen yet
                    if (size != null) {
                        foundW = size.x;
                        foundH = size.y;
                    } else if (!sameDisplay) {
                        foundW = 0;
                        foundH = 0;
                    }
                }
            }
            // DIAGNOSTIC: log the RESOLVED result so the next on-car log shows whether
            // this trim yielded a parsed displayId/layerStack at all (vs nothing usable
            // → clusterLayerStack falls to the FALLBACK CONSTANT). This + the per-line
            // "resolveFissionDisplay raw:" above are the two datums every prior log
            // lacked, which left code-vs-environment unanswerable.
            logger.info("resolveFissionDisplay result: displayId=" + foundId
                    + " layerStack=" + foundStack + " real=" + foundW + "x" + foundH);
            return new FissionDisplay(foundId, foundStack, foundW, foundH);
        } catch (Throwable t) {
            logger.warn("resolveFissionDisplay parse failed: " + t.getMessage());
            return new FissionDisplay(-1, -1);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line,
     *  or -1. Mirrors ClusterMapProjector.extractDisplayIdOnLine — the displayId is
     *  embedded in the fission DisplayInfo name string ("fission..., displayId 1"). */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(java.util.Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            while (i < low.length() && (low.charAt(i) == '=' || low.charAt(i) == ' ')) i++;
            int start = i;
            while (i < low.length() && Character.isDigit(low.charAt(i))) i++;
            if (i > start) {
                int v = parseIntSafe(low.substring(start, i));
                if (v >= 0) return v;
            }
            idx = low.indexOf("displayid", idx + 1);
        }
        return -1;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /**
     * Discover the CURRENT layerStack of the cluster (fission) display.
     *
     * <p>Returns the live parsed layerStack, or {@code fallback} ONLY when the
     * fission block was found but carried no parseable stack. When the fission
     * display can't be identified at all (e.g. it hasn't materialised yet), returns
     * {@link #STACK_UNRESOLVED} (-1) so the caller can DECLINE to show rather than
     * blindly tag the layer onto the fallback stack (the old behaviour that went
     * black on models where the real stack ≠ fallback).
     */
    public static final int STACK_UNRESOLVED = -1;

    public static int clusterLayerStack(int fallback) {
        FissionDisplay fd = resolveFissionDisplay();
        // DIAGNOSTIC: log WHICH branch we return so the next on-car log says definitively
        // whether the card's stack is the PARSED-LIVE value (branch=live) or the
        // FALLBACK CONSTANT (branch=fallback, meaning the real stack was never parsed —
        // a non-resolved stack the card gets wrongly tagged onto = the suspected black).
        // This is the one fact that separates a code bug (fallback) from environment
        // (live stack but OEM never panel-composites it).
        if (fd.layerStack >= 0) {
            logger.info("clusterLayerStack: branch=live stack=" + fd.layerStack);
        } else if (fd.displayId >= 0) {
            logger.info("clusterLayerStack: branch=FALLBACK stack=" + fallback
                    + " (displayId=" + fd.displayId + " but layerStack unparsed)");
        } else {
            logger.info("clusterLayerStack: branch=UNRESOLVED (no fission display)");
        }
        return clusterLayerStack(fd, fallback);
    }

    /**
     * Resolve the cluster layerStack from an ALREADY-resolved {@link FissionDisplay} so a
     * caller that ALSO needs the display id or panel size uses ONE consistent descriptor for
     * all three, instead of parsing {@code dumpsys display} a second time. Two back-to-back
     * parses can straddle a layerStack change across a projection re-open (the stack is a
     * process-global counter that increments per re-open), pairing one display's stack with
     * another's size/id — the exact hazard the descriptor-carry refactor removes. Same
     * resolution order as {@link #clusterLayerStack(int)}: the live parsed stack, else the
     * {@code fallback} when the fission display was seen but its stack was unparsed, else
     * {@link #STACK_UNRESOLVED} (no fission display → caller must not show).
     */
    public static int clusterLayerStack(FissionDisplay fd, int fallback) {
        if (fd == null) return STACK_UNRESOLVED;
        if (fd.layerStack >= 0) return fd.layerStack;   // live, authoritative
        if (fd.displayId >= 0) return fallback;          // fission seen, stack unparsed
        return STACK_UNRESOLVED;                          // no fission display → don't show
    }

    private static void releaseSurfaceControl(Object sc) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("hide", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            try { txCls.getMethod("reparent", scCls, scCls).invoke(tx, sc, null); } catch (Throwable ignored) {}
            // Explicitly remove from SurfaceFlinger's layer hierarchy so snapshot compositors
            // (TaskSnapshotController/captureScreenCommon) do not attempt to draw an orphaned layer.
            try { txCls.getMethod("remove", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { scCls.getMethod("release").invoke(sc); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("releaseSurfaceControl failed: " + t.getMessage());
        }
    }
}
