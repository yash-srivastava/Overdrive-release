package com.overdrive.app.surveillance;

import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.PanoramicCameraGpu;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * THROWAWAY de-risk spike for the native SurfaceControl blind-spot path.
 *
 * <p>Proves (or disproves) three firmware capabilities that the native path
 * depends on and that NOTHING in the codebase currently validates — ScreenDeterrent
 * only ever creates a FULLSCREEN, Canvas-fed SurfaceControl layer and sets z/alpha/
 * show; it never positions a non-fullscreen layer nor feeds one via GL/EGL:
 *
 * <ol>
 *   <li><b>Non-fullscreen compositing</b>: does a daemon-owned SurfaceControl
 *       buffer layer smaller than the panel actually appear on screen?</li>
 *   <li><b>GL-on-SurfaceControl</b>: does {@link EGLCore#createWindowSurface}
 *       succeed on a {@code new Surface(surfaceControl)} (vs the MediaCodec input
 *       surface it's used for today), and can we swap a GL frame to it?</li>
 *   <li><b>Positioning</b>: do {@code SurfaceControl$Transaction.setGeometry /
 *       setPosition / setSize / setMatrix} exist on this firmware so the overlay
 *       can be dragged/resized?</li>
 * </ol>
 *
 * <p>It runs on the camera GL thread (EGL surfaces are GL-thread-bound), clears
 * the layer to solid RED, swaps, positions it at (100,100) sized 400x300 at
 * z=MAX-2 (below ScreenDeterrent's z=MAX), holds it visible for a few seconds so
 * a human can look, then releases. Returns a JSON capability report so the result
 * is readable over loopback without depending on (device-invisible) app logs.
 *
 * <p>Reflection mirrors ScreenDeterrent's proven path. Delete this file once the
 * native path is committed (or abandoned).
 */
public final class BsSurfaceControlSpike {

    private static final String TAG = "BsScSpike";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int LAYER_W = 400;
    private static final int LAYER_H = 300;
    private static final int POS_X = 100;
    private static final int POS_Y = 100;
    private static final int HOLD_MS = 6000;   // keep visible long enough to eyeball

    private BsSurfaceControlSpike() {}

    /** Run the spike on the GL thread; returns a JSON capability report. */
    public static JSONObject run() {
        JSONObject r = new JSONObject();
        try {
            PanoramicCameraGpu cam = camera();
            if (cam == null) { return err(r, "no camera/pipeline (start streaming or pano first)"); }
            final EGLCore egl = cam.getEglCore();
            final Handler gl = cam.getGlHandler();
            if (egl == null || gl == null) { return err(r, "camera GL not ready (eglCore/glHandler null)"); }

            // ── capability probe: which Transaction positioning methods exist? ──
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            r.put("hasSetGeometry", hasMethod(txCls, "setGeometry"));
            r.put("hasSetPosition", hasMethod(txCls, "setPosition"));
            r.put("hasSetSize", hasMethod(txCls, "setBufferSize") || hasMethod(txCls, "setSize"));
            r.put("hasSetMatrix", hasMethod(txCls, "setMatrix"));
            r.put("hasSetCrop", hasMethod(txCls, "setCrop") || hasMethod(txCls, "setWindowCrop"));

            // ── create a non-fullscreen buffer layer ──
            final Object[] scHolder = new Object[1];
            scHolder[0] = createBufferLayer("BsScSpike", LAYER_W, LAYER_H);
            if (scHolder[0] == null) { return err(r, "createBufferLayer returned null"); }
            r.put("layerCreated", true);

            // position + show at z=MAX-2 (below deterrent). Try every positioning
            // method; record which actually applied without throwing.
            JSONObject pos = positionLayer(txCls, scCls, scHolder[0], POS_X, POS_Y, LAYER_W, LAYER_H,
                    Integer.MAX_VALUE - 2);
            r.put("position", pos);

            // ── GL-render RED into the layer's Surface on the GL thread ──
            final Object scLayer = scHolder[0];
            final boolean[] eglOk = {false};
            final String[] eglErr = {null};
            final CountDownLatch latch = new CountDownLatch(1);
            boolean posted = gl.post(() -> {
                Surface surf = null;
                EGLSurface es = null;
                try {
                    surf = new Surface((android.view.SurfaceControl) scLayer);
                    es = egl.createWindowSurface(surf);     // THE unvalidated EGL bind
                    egl.makeCurrent(es);
                    GLES20.glClearColor(1f, 0f, 0f, 1f);    // solid red
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    egl.swapBuffers(es);
                    eglOk[0] = true;
                } catch (Throwable t) {
                    eglErr[0] = t.getClass().getSimpleName() + ": " + t.getMessage();
                } finally {
                    // Keep es/surf alive for the hold; release after.
                    final EGLSurface esF = es; final Surface surfF = surf;
                    new Thread(() -> {
                        try { Thread.sleep(HOLD_MS); } catch (InterruptedException ignored) {}
                        gl.post(() -> {
                            try { if (esF != null) egl.destroySurface(esF); } catch (Throwable ignored) {}
                            try { if (surfF != null) surfF.release(); } catch (Throwable ignored) {}
                            try { releaseSurface(scLayer); } catch (Throwable ignored) {}
                        });
                    }, "BsScSpike-hold").start();
                    latch.countDown();
                }
            });
            if (!posted) { releaseSurface(scLayer); return err(r, "GL handler rejected post"); }
            latch.await(3, TimeUnit.SECONDS);
            r.put("eglRenderOk", eglOk[0]);
            if (eglErr[0] != null) r.put("eglError", eglErr[0]);

            r.put("success", true);
            r.put("verdict", buildVerdict(r));
            r.put("note", "Look at the screen NOW for ~" + (HOLD_MS / 1000) + "s: a RED " + LAYER_W + "x" + LAYER_H
                    + " box at (" + POS_X + "," + POS_Y + ")? If red box visible at that position → native path viable. "
                    + "If red fills whole screen → positioning not applied. If nothing → non-fullscreen SC layer not composited.");
            logger.info("spike report: " + r);
            return r;
        } catch (Throwable t) {
            return err(r, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String buildVerdict(JSONObject r) {
        boolean egl = r.optBoolean("eglRenderOk", false);
        boolean anyPos = r.optBoolean("hasSetGeometry", false) || r.optBoolean("hasSetPosition", false)
                || r.optBoolean("hasSetMatrix", false);
        if (!egl) return "EGL-on-SurfaceControl FAILED — GL cannot render into a SC layer surface; native path blocked.";
        if (!anyPos) return "EGL works but NO positioning API present — only a fullscreen SC layer is possible (consider fullscreen-native + GL sub-rect).";
        return "EGL works AND a positioning API exists — confirm the RED box appears at (100,100) on screen, then native path is viable.";
    }

    // ── reflection helpers (mirror ScreenDeterrent's proven path) ──────────────

    private static boolean hasMethod(Class<?> cls, String name) {
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    private static Object createBufferLayer(String name, int w, int h) {
        try {
            Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setName", String.class).invoke(builder, name);
            b.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            try { b.getMethod("setOpaque", boolean.class).invoke(builder, true); } catch (NoSuchMethodException ignored) {}
            return b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("createBufferLayer failed: " + t.getMessage());
            return null;
        }
    }

    /** Try setLayer + show (proven) then every positioning variant; report which applied. */
    private static JSONObject positionLayer(Class<?> txCls, Class<?> scCls, Object sc,
                                            int x, int y, int w, int h, int z) {
        JSONObject out = new JSONObject();
        try {
            Object tx = txCls.getDeclaredConstructor().newInstance();
            tryInvoke(out, "setLayer", () -> txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sc, z));
            tryInvoke(out, "setAlpha", () -> txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sc, 1.0f));
            tryInvoke(out, "setPosition", () -> txCls.getMethod("setPosition", scCls, float.class, float.class).invoke(tx, sc, (float) x, (float) y));
            // setGeometry(SurfaceControl, Rect source, Rect dest, int rotation)
            tryInvoke(out, "setGeometry", () -> {
                android.graphics.Rect src = new android.graphics.Rect(0, 0, w, h);
                android.graphics.Rect dst = new android.graphics.Rect(x, y, x + w, y + h);
                return txCls.getMethod("setGeometry", scCls, android.graphics.Rect.class, android.graphics.Rect.class, int.class)
                        .invoke(tx, sc, src, dst, 0);
            });
            tryInvoke(out, "show", () -> txCls.getMethod("show", scCls).invoke(tx, sc));
            txCls.getMethod("apply").invoke(tx);
            out.put("applyOk", true);
        } catch (Throwable t) {
            try { out.put("applyError", t.getMessage()); } catch (Throwable ignored) {}
        }
        return out;
    }

    private interface ThrowingCall { Object call() throws Throwable; }
    private static void tryInvoke(JSONObject out, String key, ThrowingCall c) {
        try { c.call(); out.put(key, true); }
        catch (Throwable t) { try { out.put(key, false); } catch (Throwable ignored) {} }
    }

    private static void releaseSurface(Object sc) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("hide", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            try { txCls.getMethod("reparent", scCls, scCls).invoke(tx, sc, null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { scCls.getMethod("release").invoke(sc); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("releaseSurface failed: " + t.getMessage());
        }
    }

    private static PanoramicCameraGpu camera() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            return p != null ? p.getCamera() : null;
        } catch (Throwable t) { return null; }
    }

    private static JSONObject err(JSONObject r, String msg) {
        try { r.put("success", false); r.put("error", msg); } catch (Throwable ignored) {}
        logger.warn("spike error: " + msg);
        return r;
    }
}
