package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.launcher.ClusterLayouts;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-side touch relay: forwards a tap / swipe captured on the head-unit mirror pane
 * into the app that is projected on the NON-TOUCH driver-cluster ("fission") display,
 * restoring interactivity to a cast app.
 *
 * <h3>Why the daemon does the injection</h3>
 * {@code INJECT_EVENTS} is a signature|privileged permission granted to the shell uid out
 * of the box; the daemon runs as uid 2000 (shell) and already injects system input
 * ({@code input keyevent} for home/back/recents, wake/sleep). The app process (uid 1000,
 * not platform-signed) cannot inject, so the app POSTs normalized coordinates to the
 * daemon and this class targets the cluster display.
 *
 * <h3>Coordinates are NORMALIZED (0..1)</h3>
 * The app sends the tap position as fractions of the mirror pane (0..1 in each axis) so it
 * never has to know the cluster panel resolution (which is resolved live and varies by
 * trim). We multiply by the live fission panel size here. This keeps the transform correct
 * even if the pane is dragged/resized on the head unit.
 *
 * <h3>Safety — never clobber the head unit</h3>
 * The live fission {@code displayId} is never hardcoded — SurfaceFlinger assigns it per
 * projection-open and it is NOT reliably 1. DI5 uses the validated display snapshot retained by
 * the active mirror session (avoiding a multi-second dumpsys on every tap); legacy mode keeps its
 * existing live resolver. We HARD-REFUSE to inject unless the id is {@code > 0} (0 == head unit):
 * a stale/absent id must never fall through to display 0 and actuate the infotainment screen.
 *
 * <h3>Two injection strategies</h3>
 * Primary is the fork-free reflected {@code InputManager.injectInputEvent} +
 * {@code MotionEvent.setDisplayId(fissionId)} (no per-tap process spawn). If reflection is
 * unavailable on this build we fall back to the shell {@code input -d <id> tap|swipe}
 * (the {@code -d} display flag exists on API 29; same exec form the keymap replay uses).
 * A tap that lands on a window the projected app marks non-touchable simply no-ops — that
 * is the app's choice, identical to a head-unit tap, not a failure of this relay.
 */
public final class ClusterInputRelay {

    private static final String TAG = "ClusterInputRelay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Injected-event mode ASYNC = 0 (INJECT_INPUT_EVENT_MODE_ASYNC). Kept as a literal
    // so we don't reflect a constant; the numeric contract is stable on API 29.
    private static final int INJECT_MODE_ASYNC = 0;
    private static final int DEFAULT_SWIPE_MS = 200;
    private static final int MAX_SWIPE_MS = 3000;

    // Cached reflected injectInputEvent handle (resolved once; null = use shell fallback).
    private static volatile Method injectMethod;      // IInputManager#injectInputEvent(InputEvent,int)
    private static volatile Object inputManagerService;
    private static volatile boolean injectResolved = false;
    private static volatile Method setDisplayIdMethod; // MotionEvent#setDisplayId(int)

    private ClusterInputRelay() {}

    /** True only when a complete live event stream can be injected without shell replay. */
    public static boolean supportsStreaming() { return ensureInjectResolved(); }

    /**
     * One mirror-owned gesture. The Binder service serializes access and cancels this
     * before replacing/detaching its Surface. Coordinates stay bound to one projection:
     * a display or geometry change cancels instead of moving a pressed finger elsewhere.
     */
    public static final class TouchStream {
        private final ClusterTouchSequence topology = new ClusterTouchSequence();
        private int[] mapping;
        private ClusterLayoutInputTarget layoutTarget;
        private int[] lastIds;
        private float[] lastXs, lastYs;
        private long downTime, lastEventTime;

        public boolean event(int[] snapshot, int action, int index, long eventTime,
                             int[] ids, float[] xs, float[] ys) {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) cancel();
            if (!validSnapshot(snapshot) || ids == null || xs == null || ys == null
                    || xs.length != ids.length || ys.length != ids.length
                    || eventTime > now + 50L || eventTime < now - 250L
                    || (mapping != null && (!Arrays.equals(mapping, snapshot)
                        || eventTime < lastEventTime))) {
                cancel();
                return false;
            }
            if (mapping != null && (layoutTarget == null
                    ? ClusterLayouts.isActive()
                    : !layoutTarget.isCurrent(ClusterLayouts.isActive(), ClusterLayouts.generation()))) {
                cancel();
                return false;
            }
            for (int i = 0; i < ids.length; i++) {
                if (!Float.isFinite(xs[i]) || !Float.isFinite(ys[i])) {
                    cancel();
                    return false;
                }
            }
            if (!topology.accept(action, index, ids)) {
                cancel();
                return false;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                // Presses in a FIT letterbox are inert, rather than clamped to the app edge.
                if (xs[0] < snapshot[9] || ys[0] < snapshot[10]
                        || xs[0] >= snapshot[9] + snapshot[11]
                        || ys[0] >= snapshot[10] + snapshot[12]) {
                    topology.reset();
                    return false;
                }
                if (snapshot.length >= 19) {
                    float panelX = (float) ProjectionGeometry.destinationToSource(
                            xs[0], snapshot[5], snapshot[7], snapshot[9], snapshot[11]);
                    float panelY = (float) ProjectionGeometry.destinationToSource(
                            ys[0], snapshot[6], snapshot[8], snapshot[10], snapshot[12]);
                    if (panelX < snapshot[13] || panelY < snapshot[14]
                            || panelX >= snapshot[13] + snapshot[15]
                            || panelY >= snapshot[14] + snapshot[16]) {
                        topology.reset();
                        return false;
                    }
                }
                mapping = snapshot.clone();
                downTime = eventTime;
                if (ClusterLayouts.isActive()) {
                    float clusterX = (float) ProjectionGeometry.destinationToSource(
                            xs[0], mapping[5], mapping[7], mapping[9], mapping[11]);
                    float clusterY = (float) ProjectionGeometry.destinationToSource(
                            ys[0], mapping[6], mapping[8], mapping[10], mapping[12]);
                    layoutTarget = layoutTargetAt(clusterX, clusterY);
                    if (layoutTarget == null) {
                        clear();
                        return false;
                    }
                }
            } else if (mapping == null) {
                topology.reset();
                return false;
            }
            float[] mappedX = new float[ids.length];
            float[] mappedY = new float[ids.length];
            for (int i = 0; i < ids.length; i++) {
                float panelX = clampToPanel(ProjectionGeometry.destinationToSource(
                        xs[i], mapping[5], mapping[7], mapping[9], mapping[11]), mapping[1]);
                float panelY = clampToPanel(ProjectionGeometry.destinationToSource(
                        ys[i], mapping[6], mapping[8], mapping[10], mapping[12]), mapping[2]);
                if (mapping.length >= 19) {
                    mappedX[i] = mapPanelToInput(
                            panelX, mapping[13], mapping[15], mapping[17]);
                    mappedY[i] = mapPanelToInput(
                            panelY, mapping[14], mapping[16], mapping[18]);
                } else {
                    mappedX[i] = panelX;
                    mappedY[i] = panelY;
                }
                if (layoutTarget != null) {
                    mappedX[i] = layoutTarget.mapX(mappedX[i]);
                    mappedY[i] = layoutTarget.mapY(mappedY[i]);
                }
            }
            int encoded = action;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                encoded |= index << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            }
            if (layoutTarget == null ? ClusterLayouts.isActive()
                    : !layoutTarget.isCurrent(ClusterLayouts.isActive(), ClusterLayouts.generation())) {
                cancel();
                return false;
            }
            int inputDisplay = layoutTarget == null ? mapping[4] : layoutTarget.displayId;
            if (!injectMulti(encoded, downTime, eventTime, ids, mappedX, mappedY, inputDisplay)) {
                cancel();
                return false;
            }
            lastEventTime = eventTime;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                clear();
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                int n = ids.length - 1;
                lastIds = new int[n]; lastXs = new float[n]; lastYs = new float[n];
                for (int from = 0, to = 0; from < ids.length; from++) {
                    if (from == index) continue;
                    lastIds[to] = ids[from]; lastXs[to] = mappedX[from]; lastYs[to++] = mappedY[from];
                }
            } else {
                lastIds = ids.clone(); lastXs = mappedX; lastYs = mappedY;
            }
            return true;
        }

        public void cancel() {
            if (mapping != null && lastIds != null) {
                injectMulti(MotionEvent.ACTION_CANCEL, downTime,
                        Math.max(lastEventTime, SystemClock.uptimeMillis()),
                        lastIds, lastXs, lastYs,
                        layoutTarget == null ? mapping[4] : layoutTarget.displayId);
            }
            clear();
        }

        private void clear() {
            topology.reset();
            mapping = null; layoutTarget = null; lastIds = null; lastXs = null; lastYs = null;
            downTime = 0L; lastEventTime = 0L;
        }
    }

    private static boolean validSnapshot(int[] s) {
        if (s == null || (s.length != 13 && s.length != 19)
                || s[0] != ClusterViewMirrorService.STATE_ACTIVE
                || s[4] <= 0 || s[1] <= 0 || s[2] <= 0
                || s[5] < 0 || s[6] < 0 || s[7] <= 0 || s[8] <= 0
                || (long) s[5] + s[7] > s[1] || (long) s[6] + s[8] > s[2]
                || s[11] <= 0 || s[12] <= 0) {
            return false;
        }
        return s.length == 13 || (s[13] >= 0 && s[14] >= 0 && s[15] > 0 && s[16] > 0
                && (long) s[13] + s[15] <= s[1]
                && (long) s[14] + s[16] <= s[2]
                && s[17] > 0 && s[18] > 0);
    }

    private static boolean injectMulti(int action, long downTime, long eventTime,
                                        int[] ids, float[] xs, float[] ys, int displayId) {
        if (displayId <= 0 || !ensureInjectResolved()) return false;
        MotionEvent ev = null;
        try {
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
            for (int i = 0; i < ids.length; i++) {
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = ids[i]; props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = xs[i]; coords[i].y = ys[i];
                coords[i].pressure = 1f; coords[i].size = 1f;
            }
            ev = MotionEvent.obtain(downTime, eventTime, action, ids.length, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            setDisplayIdMethod.invoke(ev, displayId);
            Object result = injectMethod.invoke(inputManagerService, ev, INJECT_MODE_ASYNC);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            logger.debug("stream injection failed: " + t.getMessage());
            return false;
        } finally {
            if (ev != null) ev.recycle();
        }
    }

    /** Cluster-scoped navigation/media keys; unsupported routing never defaults to display 0. */
    public static boolean key(int[] snapshot, int keyCode) {
        if (!validSnapshot(snapshot) || (keyCode != KeyEvent.KEYCODE_BACK
                && keyCode != KeyEvent.KEYCODE_HOME && keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) return false;
        int displayId = snapshot[4];
        if (ClusterLayouts.isActive()) {
            ClusterLayouts.InputSlot slot = ClusterLayouts.selectedInputSlot();
            if (slot == null || slot.displayId <= 0 || slot.generation != ClusterLayouts.generation()) {
                return false;
            }
            displayId = slot.displayId;
        }
        if (ensureInjectResolved()) {
            boolean pressed = false;
            KeyEvent release = null;
            Method setDisplay = null;
            try {
                setDisplay = KeyEvent.class.getMethod("setDisplayId", int.class);
                long now = SystemClock.uptimeMillis();
                KeyEvent press = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
                release = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
                setDisplay.invoke(press, displayId);
                setDisplay.invoke(release, displayId);
                Object result = injectMethod.invoke(inputManagerService, press, INJECT_MODE_ASYNC);
                if (result instanceof Boolean && !(Boolean) result) return false;
                pressed = true;
                result = injectMethod.invoke(inputManagerService, release, INJECT_MODE_ASYNC);
                if (result instanceof Boolean && !(Boolean) result) return false;
                pressed = false;
                return true;
            } catch (Throwable ignored) {
                // Once DOWN was accepted, avoid duplicating the key through shell fallback.
                if (pressed) return false;
            } finally {
                if (pressed && release != null && setDisplay != null) {
                    try { injectMethod.invoke(inputManagerService, release, INJECT_MODE_ASYNC); }
                    catch (Throwable ignored) {}
                }
            }
        }
        return runShell("input -d " + displayId + " keyevent " + keyCode);
    }

    /** Relay a single tap at a mirror-SURFACE px coordinate. Inverts through the live
     *  projection mapping to a cluster px and injects it. Returns true if dispatched. */
    public static boolean tap(double sx, double sy) {
        return tap(sx, sy, 0L);
    }

    public static boolean tap(double sx, double sy, long expectedLayoutGeneration) {
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        ProjectionMapping mapping = currentProjectionMapping();
        if (mapping == null || !Double.isFinite(sx) || !Double.isFinite(sy)) return false;
        int[] panel = resolvePanel(mapping);
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        int x = clampToPanel(mapping.mapX(sx), w);
        int y = clampToPanel(mapping.mapY(sy), h);
        if (!mapping.containsInputPanel(x, y)) return false;
        if (expectedLayoutGeneration > 0L) {
            if (!mapping.containsSurface(sx, sy)) return false;
            ClusterLayoutInputTarget slot = layoutTargetAt((float) mapping.mapX(sx),
                    (float) mapping.mapY(sy));
            if (slot == null || slot.generation != expectedLayoutGeneration) {
                return false;
            }
            final int slotX = Math.round(slot.mapX(x)), slotY = Math.round(slot.mapY(y));
            return ClusterViewMirrorService.getInstance().withLayoutInput(expectedLayoutGeneration,
                    () -> ClusterLayoutGesture.tap(slot.displayId, slotX, slotY,
                            () -> ClusterLayouts.ownsGeneration(expectedLayoutGeneration),
                            SystemClock::uptimeMillis, ClusterInputRelay::injectLayoutMotion));
        }
        int[] inputPoint = mapping.mapInputPoint(x, y);
        int inputX = inputPoint[0];
        int inputY = inputPoint[1];
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        if (injectTap(id, inputX, inputY)) return true;
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        return shellTap(id, inputX, inputY);
    }

    /** Relay a swipe/scroll between two mirror-SURFACE px coordinates over {@code durationMs}. */
    public static boolean swipe(double sx1, double sy1, double sx2, double sy2, int durationMs) {
        return swipe(sx1, sy1, sx2, sy2, durationMs, 0L);
    }

    public static boolean swipe(double sx1, double sy1, double sx2, double sy2, int durationMs,
                                long expectedLayoutGeneration) {
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        ProjectionMapping mapping = currentProjectionMapping();
        if (mapping == null || !Double.isFinite(sx1) || !Double.isFinite(sy1)
                || !Double.isFinite(sx2) || !Double.isFinite(sy2)) {
            return false;
        }
        int[] panel = resolvePanel(mapping);
        if (panel == null) return false;
        int id = panel[0], w = panel[1], h = panel[2];
        int x1 = clampToPanel(mapping.mapX(sx1), w);
        int y1 = clampToPanel(mapping.mapY(sy1), h);
        int x2 = clampToPanel(mapping.mapX(sx2), w);
        int y2 = clampToPanel(mapping.mapY(sy2), h);
        if (!mapping.containsInputPanel(x1, y1) || !mapping.containsInputPanel(x2, y2)) {
            return false;
        }
        if (expectedLayoutGeneration > 0L) {
            if (!mapping.containsSurface(sx1, sy1)) return false;
            ClusterLayoutInputTarget slot = layoutTargetAt((float) mapping.mapX(sx1),
                    (float) mapping.mapY(sy1));
            if (slot == null || slot.generation != expectedLayoutGeneration) {
                return false;
            }
            final int slotX1 = Math.round(slot.mapX(x1)), slotY1 = Math.round(slot.mapY(y1));
            final int slotX2 = Math.round(slot.mapX(x2)), slotY2 = Math.round(slot.mapY(y2));
            final int duration = durationMs <= 0 ? DEFAULT_SWIPE_MS : Math.min(durationMs, MAX_SWIPE_MS);
            return ClusterViewMirrorService.getInstance().withLayoutInput(expectedLayoutGeneration,
                    () -> ClusterLayoutGesture.swipe(slot.displayId, slotX1, slotY1, slotX2, slotY2,
                            duration, () -> ClusterLayouts.ownsGeneration(expectedLayoutGeneration),
                            SystemClock::uptimeMillis, ClusterInputRelay::injectLayoutMotion));
        }
        int dur = durationMs <= 0 ? DEFAULT_SWIPE_MS : Math.min(durationMs, MAX_SWIPE_MS);
        int[] inputStart = mapping.mapInputPoint(x1, y1);
        int[] inputEnd = mapping.mapInputPoint(x2, y2);
        int inputX1 = inputStart[0];
        int inputY1 = inputStart[1];
        int inputX2 = inputEnd[0];
        int inputY2 = inputEnd[1];
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        if (injectSwipe(id, inputX1, inputY1, inputX2, inputY2, dur)) return true;
        if (expectedLayoutGeneration != currentLayoutGeneration()) return false;
        return shellSwipe(id, inputX1, inputY1, inputX2, inputY2, dur);
    }

    /**
     * Take one validated live projection snapshot for a complete tap/swipe. Re-reading the
     * mapping independently for each axis or endpoint lets a concurrent resize combine old
     * and new rectangles into a diagonal jump. A missing mapping is a hard refusal: falling
     * back to raw surface coordinates can inject into the wrong cluster location after
     * teardown.
     */
    private static ProjectionMapping currentProjectionMapping() {
        try {
            ClusterViewMirrorService m = ClusterViewMirrorService.getInstance();
            int[] snapshot = m.inputSnapshot();
            if (!validSnapshot(snapshot)) return null;
            int panelW = snapshot[1];
            int panelH = snapshot[2];
            int mode = snapshot[3];
            int inputDisplayId = snapshot[4];
            int[] src = new int[] {
                    snapshot[5], snapshot[6], snapshot[7], snapshot[8]
            };
            int[] dst = new int[] {
                    snapshot[9], snapshot[10], snapshot[11], snapshot[12]
            };
            if (mode != ProjectionGeometry.SCALE_FILL
                    && !aspectsCompatible(src[2], src[3], dst[2], dst[3])) {
                logger.debug("relay refused inconsistent projection src=" + src[2] + "x"
                        + src[3] + " dst=" + dst[2] + "x" + dst[3] + " mode=" + mode);
                return null;
            }
            int[] inputPanel = snapshot.length == 19
                    ? new int[] { snapshot[13], snapshot[14], snapshot[15], snapshot[16] }
                    : new int[] { 0, 0, panelW, panelH };
            int inputBufferW = snapshot.length == 19 ? snapshot[17] : panelW;
            int inputBufferH = snapshot.length == 19 ? snapshot[18] : panelH;
            return new ProjectionMapping(
                    src, dst, panelW, panelH, inputDisplayId,
                    inputPanel, inputBufferW, inputBufferH);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean aspectsCompatible(int aw, int ah, int bw, int bh) {
        long delta = Math.abs((long) aw * bh - (long) ah * bw);
        long tolerance = Math.max(Math.max(aw, ah), Math.max(bw, bh));
        return delta <= tolerance;
    }

    private static ClusterLayoutInputTarget layoutTargetAt(float x, float y) {
        ClusterLayouts.InputSlot slot = ClusterLayouts.inputSlotAt(x, y);
        if (slot == null || !ClusterLayouts.selectInputSlot(slot.displayId, slot.generation)) return null;
        try {
            ClusterLayoutInputTarget target = new ClusterLayoutInputTarget(slot.displayId,
                    slot.left, slot.top, slot.width, slot.height,
                    slot.inputWidth, slot.inputHeight, slot.generation);
            return target.contains(x, y) ? target : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static long currentLayoutGeneration() {
        return ClusterLayouts.isActive() ? ClusterLayouts.generation() : 0L;
    }

    private static boolean injectLayoutMotion(int action, long down, long time,
                                              int x, int y, int displayId) {
        return injectMulti(action, down, time, new int[] { 0 },
                new float[] { x }, new float[] { y }, displayId);
    }

    private static final class ProjectionMapping {
        final int srcX;
        final int srcY;
        final int srcW;
        final int srcH;
        final int dstX;
        final int dstY;
        final int dstW;
        final int dstH;
        final int panelW;
        final int panelH;
        final int inputDisplayId;
        final int inputPanelX;
        final int inputPanelY;
        final int inputPanelW;
        final int inputPanelH;
        final int inputBufferW;
        final int inputBufferH;

        ProjectionMapping(
                int[] src, int[] dst, int panelW, int panelH,
                int inputDisplayId, int[] inputPanel,
                int inputBufferW, int inputBufferH) {
            this.srcX = src[0];
            this.srcY = src[1];
            this.srcW = src[2];
            this.srcH = src[3];
            this.dstX = dst[0];
            this.dstY = dst[1];
            this.dstW = dst[2];
            this.dstH = dst[3];
            this.panelW = panelW;
            this.panelH = panelH;
            this.inputDisplayId = inputDisplayId;
            this.inputPanelX = inputPanel[0];
            this.inputPanelY = inputPanel[1];
            this.inputPanelW = inputPanel[2];
            this.inputPanelH = inputPanel[3];
            this.inputBufferW = inputBufferW;
            this.inputBufferH = inputBufferH;
        }

        double mapX(double surfaceX) {
            return ProjectionGeometry.destinationToSource(
                    surfaceX, srcX, srcW, dstX, dstW);
        }

        double mapY(double surfaceY) {
            return ProjectionGeometry.destinationToSource(
                    surfaceY, srcY, srcH, dstY, dstH);
        }

        boolean containsInputPanel(double x, double y) {
            return x >= inputPanelX && y >= inputPanelY
                    && x < (long) inputPanelX + inputPanelW
                    && y < (long) inputPanelY + inputPanelH;
        }

        int[] mapInputPoint(float panelX, float panelY) {
            return mapPanelPointToInput(
                    panelX, panelY, inputPanelX, inputPanelY,
                    inputPanelW, inputPanelH, inputBufferW, inputBufferH);
        }

        float mapInputX(float panelX) {
            return mapPanelToInput(panelX, inputPanelX, inputPanelW, inputBufferW);
        }

        float mapInputY(float panelY) {
            return mapPanelToInput(panelY, inputPanelY, inputPanelH, inputBufferH);
        }

        boolean containsSurface(double x, double y) {
            return x >= dstX && y >= dstY && x < (long) dstX + dstW && y < (long) dstY + dstH;
        }
    }

    // ── Panel/display resolution + safety gate ──────────────────────────────────────

    /** {@code [fissionDisplayId, panelW, panelH]}, or null if no safe cluster target.
     *  HARD-REFUSES id <= 0 (0 = head unit) so injection can never hit the infotainment. */
    private static int[] resolvePanel(ProjectionMapping mapping) {
        if (ClusterLayouts.isActive()) {
            int id = ClusterLayouts.physicalDisplayId();
            int w = ClusterLayouts.panelWidth(), h = ClusterLayouts.panelHeight();
            if (id <= 0 || id != mapping.inputDisplayId
                    || w != mapping.panelW || h != mapping.panelH) return null;
            return new int[] { id, w, h };
        }
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            int targetId = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayId();
            int w = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayWidth();
            int h = com.overdrive.app.launcher.DiLink5ClusterCast
                    .currentTargetDisplayHeight();
            int id = mapping.inputDisplayId;
            if (targetId <= 0 || id <= 0 || w <= 0 || h <= 0) {
                logger.warn("relay refused — no active DI5 OEM target (id="
                        + targetId + " composed=" + id + " size="
                        + w + "x" + h + ")");
                return null;
            }
            if (w != mapping.panelW || h != mapping.panelH) {
                logger.warn("relay refused — DI5 target size " + w + "x" + h
                        + " differs from projection " + mapping.panelW + "x"
                        + mapping.panelH);
                return null;
            }
            // DI5 uses two different endpoints by design: the app task lives on
            // shared_fission..._0/_1 (targetId), while input and panel composition
            // are owned by the exact plain fission display (id). Injecting into the
            // shadow target is accepted but never reaches the visible app.
            return new int[] { id, w, h };
        }
        BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
        if (fd.displayId <= 0) {
            logger.warn("relay refused — no positive fission displayId (id=" + fd.displayId + ")");
            return null;
        }
        Context ctx = resolveContext();
        Point panel = BsNativeLayer.clusterDisplaySize(ctx, fd);
        int w = Math.max(1, panel.x), h = Math.max(1, panel.y);
        if (w != mapping.panelW || h != mapping.panelH) {
            logger.warn("relay refused — live display size " + w + "x" + h
                    + " differs from projection " + mapping.panelW + "x" + mapping.panelH);
            return null;
        }
        return new int[] { fd.displayId, w, h };
    }

    static int[] mapPanelPointToInput(
            float panelX, float panelY,
            int offsetX, int offsetY, int panelWidth, int panelHeight,
            int bufferWidth, int bufferHeight) {
        return new int[] {
                Math.round(mapPanelToInput(panelX, offsetX, panelWidth, bufferWidth)),
                Math.round(mapPanelToInput(panelY, offsetY, panelHeight, bufferHeight))
        };
    }

    static float mapPanelToInput(float panelPx, int panelOffset, int panelExtent, int bufferExtent) {
        if (panelExtent <= 0 || bufferExtent <= 0 || Float.isNaN(panelPx)) return 0f;
        double mapped = (panelPx - panelOffset) * bufferExtent / (double) panelExtent;
        return clampToPanel(mapped, bufferExtent);
    }

    /** Clamp an ABSOLUTE cluster-px coordinate into {@code [0, size-1]}. */
    private static int clampToPanel(double clusterPx, int size) {
        if (Double.isNaN(clusterPx)) clusterPx = 0;
        int v = (int) Math.round(clusterPx);
        if (v < 0) v = 0;
        if (v > size - 1) v = size - 1;
        return v;
    }

    // ── Primary: fork-free reflected injectInputEvent with setDisplayId ─────────────

    private static boolean injectTap(int displayId, int x, int y) {
        long now = SystemClock.uptimeMillis();
        boolean down = injectMotion(MotionEvent.ACTION_DOWN, now, now, x, y, displayId);
        if (!down) return false;
        // Small dwell so the target registers a press, then up at the same point.
        boolean up = injectMotion(MotionEvent.ACTION_UP, now, now + 60, x, y, displayId);
        return up;
    }

    private static boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        long down = SystemClock.uptimeMillis();
        if (!injectMotion(MotionEvent.ACTION_DOWN, down, down, x1, y1, displayId)) return false;
        int steps = Math.max(1, Math.min(durMs / 16, 64));   // ~60fps of moves, capped
        for (int i = 1; i <= steps; i++) {
            // Keep MOVE timestamps before the final UP. Sending a MOVE and UP at the same
            // endpoint/time can collapse the end of short swipes on InputDispatcher.
            float t = (float) i / (steps + 1);
            int mx = Math.round(x1 + (x2 - x1) * t);
            int my = Math.round(y1 + (y2 - y1) * t);
            long ev = down + (long) (durMs * t);
            if (!injectMotion(MotionEvent.ACTION_MOVE, down, ev, mx, my, displayId)) {
                // A move failed mid-swipe — still emit the UP so we don't strand a
                // pressed pointer on the target.
                injectMotion(MotionEvent.ACTION_UP, down, down + durMs, mx, my, displayId);
                return false;
            }
        }
        return injectMotion(MotionEvent.ACTION_UP, down, down + durMs, x2, y2, displayId);
    }

    /** Build a single-pointer MotionEvent tagged with {@code displayId} and inject it.
     *  Returns false (and disables the reflected path) if reflection is unavailable. */
    private static boolean injectMotion(int action, long downTime, long eventTime,
                                        int x, int y, int displayId) {
        if (!ensureInjectResolved()) return false;
        MotionEvent ev = null;
        try {
            ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            // Route to the cluster display. If setDisplayId is missing the event would go
            // to the default display → refuse rather than tap the head unit.
            if (setDisplayIdMethod == null) return false;
            setDisplayIdMethod.invoke(ev, displayId);
            Object ret = injectMethod.invoke(inputManagerService, ev, INJECT_MODE_ASYNC);
            return !(ret instanceof Boolean) || (Boolean) ret;
        } catch (Throwable t) {
            logger.debug("injectMotion failed: " + t.getMessage());
            // Disable the reflected path for subsequent calls; shell fallback takes over.
            injectMethod = null;
            return false;
        } finally {
            if (ev != null) try { ev.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** Resolve IInputManager + injectInputEvent + MotionEvent.setDisplayId ONCE. */
    private static boolean ensureInjectResolved() {
        if (injectResolved) return injectMethod != null;
        synchronized (ClusterInputRelay.class) {
            if (injectResolved) return injectMethod != null;
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, Context.INPUT_SERVICE);
                Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                Object iim = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                Method m = iim.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                Method sd = MotionEvent.class.getMethod("setDisplayId", int.class);
                inputManagerService = iim;
                injectMethod = m;
                setDisplayIdMethod = sd;
                logger.info("input relay: reflected injectInputEvent resolved");
            } catch (Throwable t) {
                injectMethod = null;
                logger.info("input relay: reflected inject unavailable (" + t.getMessage()
                        + ") — using `input -d` shell fallback");
            }
            injectResolved = true;
        }
        return injectMethod != null;
    }

    // ── Fallback: shell `input -d <id> tap|swipe` ────────────────────────────────────

    private static boolean shellTap(int displayId, int x, int y) {
        return runShell("input -d " + displayId + " tap " + x + " " + y);
    }

    private static boolean shellSwipe(int displayId, int x1, int y1, int x2, int y2, int durMs) {
        return runShell("input -d " + displayId + " swipe " + x1 + " " + y1 + " "
                + x2 + " " + y2 + " " + durMs);
    }

    private static boolean runShell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("input relay shell failed: " + t.getMessage());
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
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
