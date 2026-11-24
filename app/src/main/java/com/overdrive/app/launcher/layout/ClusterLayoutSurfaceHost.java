package com.overdrive.app.launcher.layout;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.ClusterDisplayReader;
import com.overdrive.app.launcher.ClusterDisplaySnapshot;
import com.overdrive.app.navmap.cluster.ClusterGuidanceDaemon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * DI3 slot surfaces only; task launch and projection ownership belong to ClusterLayouts.
 * Overlay/virtual-display protocol follows DashCast SurfaceDaemon (MIT, copyright 2026
 * Cedric Carre); see THIRD_PARTY_NOTICES-dashcast.txt. A compositor buffer layer supplies
 * the output when the OEM display is hidden from DisplayManager. No mirror is created here.
 */
public final class ClusterLayoutSurfaceHost {
    private static final long CREATE_TIMEOUT_MS = 4_000, RELEASE_TIMEOUT_MS = 2_000;
    private static final int SHELL_UID = 2000;
    private static final String SHELL_PACKAGE = "com.android.shell";
    // PRESENTATION | SUPPORTS_TOUCH | DESTROY_ON_REMOVAL, optionally TRUSTED.
    public static final int PRIVATE_FLAGS = 322, TRUSTED_PRIVATE_FLAGS = 1346;
    private static final AtomicLong NAMES = new AtomicLong();

    private ClusterLayoutSurfaceHost() {}

    public static boolean isSupported() {
        if (ClusterTransport.isDiLink5()) return false;
        String firmware = "";
        try {
            firmware = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "apps.setting.product.inswver", "");
        } catch (Throwable ignored) {}
        return ClusterGuidanceDaemon.isDi3Identity(
                Build.PRODUCT + " " + Build.DEVICE + " " + Build.FINGERPRINT + " " + firmware);
    }

    /** No default-display fallback: absence or identity uncertainty fails before allocation. */
    public static Rect physicalDisplayBounds(int displayId) throws CreateException {
        if (displayId <= 0) throw new CreateException("physical_display_must_be_positive", null);
        DisplayManager manager = manager(shellContext());
        Display display = visibleDisplay(manager, displayId);
        if (display != null) return bounds(display);
        ClusterDisplaySnapshot target = nativeTarget(displayId);
        return new Rect(0, 0, target.width, target.height);
    }

    public static SlotResource create(long generation, int physicalClusterDisplayId,
            Rect slotRect, int dpi, BooleanSupplier live) throws CreateException {
        return create(generation, "slot", physicalClusterDisplayId, slotRect, dpi, live);
    }

    /** Must be called on a worker: the main looper delivers the SurfaceHolder callbacks. */
    public static SlotResource create(long generation, String slotId, int physicalClusterDisplayId,
            Rect slotRect, int dpi, BooleanSupplier live) throws CreateException {
        if (generation <= 0 || physicalClusterDisplayId <= 0 || slotRect == null
                || slotRect.width() <= 0 || slotRect.height() <= 0 || dpi < 72 || dpi > 640
                || live == null) throw new CreateException("invalid_slot_geometry_or_density", null);
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) throw new CreateException("daemon_main_looper_unavailable", null);
        if (Looper.myLooper() == mainLooper)
            throw new CreateException("slot_create_requires_worker", null);
        if (!safeLive(live)) throw new CreateException("slot_generation_cancelled", null);
        Context context = shellContext();
        DisplayManager manager = manager(context);
        Display target = visibleDisplay(manager, physicalClusterDisplayId);
        ClusterDisplaySnapshot nativeTarget = target == null ? nativeTarget(physicalClusterDisplayId) : null;
        Rect panel = target != null ? bounds(target)
                : new Rect(0, 0, nativeTarget.width, nativeTarget.height);
        Rect copied = new Rect(slotRect);
        if (!panel.contains(copied))
            throw new CreateException("slot_outside_physical_display", null);
        Allocation allocation = new Allocation(generation, physicalClusterDisplayId, copied, dpi,
                live, new Handler(mainLooper), context, manager, target, nativeTarget,
                "overdrive_layout_" + generation + "_" + android.os.Process.myPid()
                        + "_" + safeName(slotId) + "_" + NAMES.incrementAndGet());
        if (!allocation.main.post(allocation::attach)) {
            allocation.cancelled.set(true);
            allocation.cleaned = true; // No runnable or resource was admitted.
            throw new CreateException("slot_attach_dispatch_rejected", new SlotResource(allocation));
        }
        boolean completed = await(allocation.ready, CREATE_TIMEOUT_MS);
        if (!completed || allocation.failure != null || !allocation.admitted()
                || allocation.virtualDisplayId <= 0 || allocation.cancelled.get()) {
            String reason = !completed ? "slot_create_timeout" : allocation.failure != null
                    ? allocation.failure : "slot_generation_cancelled";
            SlotResource partial = new SlotResource(allocation);
            allocation.cancelled.set(true);
            partial.release();
            throw new CreateException(reason, partial);
        }
        return new SlotResource(allocation);
    }

    /**
     * Staging uses window alpha zero while the holder remains VISIBLE. All committed windows
     * are revealed in one main-loop action; failed/cancelled reveals hide the entire set again.
     * Window updates are framework calls, so this does not promise an atomic compositor frame.
     */
    public static boolean showAll(List<SlotResource> slots, BooleanSupplier live) {
        if (slots == null || slots.isEmpty() || live == null) return false;
        List<SlotResource> copied = new ArrayList<>(slots);
        for (SlotResource slot : copied) if (slot == null) return false;
        for (SlotResource slot : copied) {
            if (slot.generation != copied.get(0).generation
                    || slot.physicalDisplayId != copied.get(0).physicalDisplayId) return false;
        }
        Handler main = copied.get(0).allocation.main;
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean shown = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Runnable reveal = () -> {
            boolean success = false;
            try {
                List<ClusterLayoutNativeSurface> nativeSlots = new ArrayList<>();
                for (SlotResource slot : copied) {
                    Allocation state = slot.allocation;
                    if (aborted.get() || !safeLive(live) || !state.admitted()
                            || state.cancelled.get() || state.cleaned || state.vd == null
                            || (state.nativeTarget == null && (state.window == null || state.view == null))
                            || (state.nativeTarget != null && state.nativeSurface == null))
                        throw new IllegalStateException("slot_reveal_cancelled");
                }
                for (SlotResource slot : copied) {
                    Allocation state = slot.allocation;
                    if (aborted.get() || !safeLive(live) || !state.admitted())
                        throw new IllegalStateException("slot_reveal_cancelled");
                    if (state.nativeSurface != null) nativeSlots.add(state.nativeSurface);
                    else {
                        state.params.alpha = 1f;
                        state.window.updateViewLayout(state.view, state.params);
                    }
                }
                success = ClusterLayoutNativeSurface.showAll(nativeSlots,
                        () -> !aborted.get() && safeLive(live));
            } catch (Throwable error) {
                for (SlotResource slot : copied) slot.allocation.failure = reason("slot_reveal", error);
            } finally {
                if (!success) hideOnMain(copied);
                shown.set(success);
                done.countDown();
            }
        };
        if (Looper.myLooper() == main.getLooper()) reveal.run();
        else if (!main.post(reveal)) return false;
        if (!await(done, RELEASE_TIMEOUT_MS)) {
            aborted.set(true);
            main.post(() -> hideOnMain(copied));
            return false;
        }
        return shown.get();
    }

    private static void hideOnMain(List<SlotResource> slots) {
        for (SlotResource slot : slots) {
            Allocation state = slot.allocation;
            if (state.nativeSurface != null) {
                try { state.nativeSurface.hide(); }
                catch (Throwable error) { state.failure = reason("slot_native_hide", error); }
            }
            if (state.window == null || state.view == null || state.params == null) continue;
            try {
                state.params.alpha = 0f;
                state.window.updateViewLayout(state.view, state.params);
            } catch (Throwable error) { state.failure = reason("slot_hide", error); }
        }
    }

    /** 1 current, 0 changed, -1 unreadable. The caller retries unreadable evidence on its worker. */
    public static int nativeTargetStatus(List<SlotResource> slots) {
        ClusterDisplaySnapshot current = null;
        for (SlotResource slot : slots) {
            ClusterDisplaySnapshot expected = slot.allocation.nativeTarget;
            if (expected == null) continue;
            if (current == null) {
                current = ClusterDisplayReader.read(expected.displayId);
                if (current == null) return -1;
            }
            if (!expected.sameCompositorTarget(current)) return 0;
        }
        return 1;
    }

    public static final class SlotResource implements AutoCloseable {
        public final long generation;
        public final int physicalDisplayId, virtualDisplayId, displayId, width, height, dpi, density;
        public final String displayName, virtualDisplayUniqueId;
        public final String surfaceBackend;
        private final Rect slotBounds;
        private final Allocation allocation;

        private SlotResource(Allocation state) {
            allocation = state;
            generation = state.generation;
            physicalDisplayId = state.physicalDisplayId;
            virtualDisplayId = state.virtualDisplayId;
            displayId = virtualDisplayId;
            displayName = state.name;
            virtualDisplayUniqueId = state.virtualDisplayUniqueId;
            slotBounds = new Rect(state.rect);
            width = slotBounds.width();
            height = slotBounds.height();
            dpi = state.dpi;
            density = dpi;
            surfaceBackend = state.nativeTarget == null ? "overlay" : "compositor";
        }

        public Rect bounds() { return new Rect(slotBounds); }
        public boolean isReleased() { return allocation.cleaned; }
        public String lastFailure() { return allocation.failure == null ? "" : allocation.failure; }

        /** Never launches/reparents tasks or changes display density; false retains retryable ownership. */
        public boolean release() {
            allocation.cancelled.set(true);
            if (allocation.cleaned) return true;
            CountDownLatch completed = new CountDownLatch(1);
            Runnable cleanup = () -> {
                try { allocation.cleanupOnMain(); }
                finally { completed.countDown(); }
            };
            if (Looper.myLooper() == allocation.main.getLooper()) cleanup.run();
            else if (!allocation.main.post(cleanup)) {
                allocation.failure = "slot_cleanup_dispatch_rejected";
                return false;
            }
            if (!await(completed, RELEASE_TIMEOUT_MS)) {
                allocation.failure = "slot_cleanup_timeout";
                return false;
            }
            return allocation.cleaned;
        }

        @Override public void close() {
            if (!release()) throw new IllegalStateException("slot_cleanup_unconfirmed: " + lastFailure());
        }
    }

    /** Partial allocation is retained whenever cleanup could not be confirmed. */
    public static final class CreateException extends Exception {
        public final SlotResource resource;
        public final boolean cleanupConfirmed;

        private CreateException(String message, SlotResource partial) {
            super(message);
            resource = partial;
            cleanupConfirmed = partial == null || partial.isReleased();
        }
    }

    /** Resource handles are confined to the main looper; cancellation is safe from any thread. */
    private static final class Allocation {
        final long generation;
        final int physicalDisplayId, dpi;
        final Rect rect;
        final BooleanSupplier live;
        final Handler main;
        final Context context;
        final DisplayManager manager;
        final Display target;
        final ClusterDisplaySnapshot nativeTarget;
        final String name;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean cleaned;
        volatile String failure;
        volatile int virtualDisplayId = -1;
        volatile String virtualDisplayUniqueId = "";
        SurfaceView view;
        WindowManager window;
        WindowManager.LayoutParams params;
        VirtualDisplay vd;
        ClusterLayoutNativeSurface nativeSurface;
        SurfaceHolder.Callback holderCallback;
        DisplayManager.DisplayListener displayListener;
        boolean windowAttempted, listenerRegistered, creatingDisplay, attaching;

        Allocation(long generation, int physicalDisplayId, Rect rect, int dpi, BooleanSupplier live,
                Handler main, Context context, DisplayManager manager, Display target,
                ClusterDisplaySnapshot nativeTarget, String name) {
            this.generation = generation;
            this.physicalDisplayId = physicalDisplayId;
            this.rect = new Rect(rect);
            this.dpi = dpi;
            this.live = live;
            this.main = main;
            this.context = context;
            this.manager = manager;
            this.target = target;
            this.nativeTarget = nativeTarget;
            this.name = name;
        }

        boolean admitted() { return !cancelled.get() && isSupported() && safeLive(live); }

        void attach() {
            if (!admitted()) { fail("slot_generation_cancelled"); return; }
            attaching = true;
            try {
                if (nativeTarget != null) {
                    nativeSurface = new ClusterLayoutNativeSurface(name, rect, nativeTarget);
                    nativeSurface.create();
                    surfaceReady(nativeSurface.surface());
                    if (!admitted()) { fail("slot_generation_cancelled"); return; }
                    registerDisplayListener();
                    return;
                }
                Context displayContext = context.createDisplayContext(target);
                verifyShellIdentity(displayContext);
                window = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
                if (window == null) throw new IllegalStateException("display_window_manager_unavailable");
                view = new SurfaceView(displayContext);
                view.getHolder().setFixedSize(rect.width(), rect.height());
                holderCallback = new SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady(holder); }
                    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                        surfaceReady(holder);
                    }
                    @Override public void surfaceDestroyed(SurfaceHolder holder) {
                        if (!cancelled.get()) fail("slot_output_surface_destroyed");
                    }
                };
                view.getHolder().addCallback(holderCallback);
                params = new WindowManager.LayoutParams(rect.width(), rect.height(), 2006,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.OPAQUE);
                params.setTitle(name);
                params.packageName = SHELL_PACKAGE;
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = rect.left;
                params.y = rect.top;
                params.alpha = 0f;
                if (!admitted()) { fail("slot_generation_cancelled"); return; }
                windowAttempted = true;
                window.addView(view, params);
                if (!admitted()) { fail("slot_generation_cancelled"); return; }
                registerDisplayListener();
                // Some firmware dispatches a valid holder before addView returns.
                surfaceReady(view.getHolder());
            } catch (Throwable error) {
                fail(reason(nativeTarget == null ? "slot_overlay_attach" : "slot_native_attach", error));
            }
            finally {
                attaching = false;
                if (cancelled.get()) cleanupOnMain();
                else publishReady();
            }
        }

        void registerDisplayListener() {
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {}
                @Override public void onDisplayRemoved(int id) {
                    if (id == physicalDisplayId || id == virtualDisplayId)
                        fail("slot_display_removed");
                }
                @Override public void onDisplayChanged(int id) {
                    if (id != physicalDisplayId || cancelled.get() || nativeTarget != null) return;
                    Display display = manager.getDisplay(id);
                    if (display == null || !bounds(display).contains(rect))
                        fail("slot_physical_display_changed");
                }
            };
            manager.registerDisplayListener(displayListener, main);
            listenerRegistered = true;
        }

        void surfaceReady(SurfaceHolder holder) {
            surfaceReady(holder.getSurface());
        }

        void surfaceReady(Surface surface) {
            if (!admitted()) { fail("slot_generation_cancelled"); return; }
            if (surface == null || !surface.isValid() || creatingDisplay) return;
            try {
                if (vd != null) { vd.setSurface(surface); return; }
                creatingDisplay = true;
                VirtualDisplay.Callback callback = new VirtualDisplay.Callback() {
                    @Override public void onStopped() {
                        // A stop callback confirms this display's native lifecycle has ended.
                        vd = null;
                        if (!cancelled.get()) fail("slot_virtual_display_stopped");
                        else cleanupOnMain();
                    }
                };
                try {
                    vd = manager.createVirtualDisplay(name, rect.width(), rect.height(), dpi,
                            surface, TRUSTED_PRIVATE_FLAGS, callback, main);
                } catch (Exception rejected) {
                    if (!admitted()) { fail("slot_generation_cancelled"); return; }
                }
                if (vd == null && admitted()) {
                    vd = manager.createVirtualDisplay(name, rect.width(), rect.height(), dpi,
                            surface, PRIVATE_FLAGS, callback, main);
                }
                if (vd == null) throw new IllegalStateException("slot_virtual_display_unavailable");
                Display display = vd.getDisplay();
                virtualDisplayId = display == null ? -1 : display.getDisplayId();
                if (virtualDisplayId <= 0 || virtualDisplayId == physicalDisplayId)
                    throw new IllegalStateException("unsafe_slot_virtual_display_id");
                try {
                    Object uniqueId = Display.class.getMethod("getUniqueId").invoke(display);
                    if (uniqueId instanceof String) virtualDisplayUniqueId = (String) uniqueId;
                } catch (Throwable ignored) {}
                if (!admitted()) { fail("slot_generation_cancelled"); return; }
            } catch (Throwable error) { fail(reason("slot_virtual_display_create", error)); }
            finally {
                creatingDisplay = false;
                if (cancelled.get()) cleanupOnMain();
                else publishReady();
            }
        }

        void publishReady() {
            if (!attaching && !creatingDisplay && virtualDisplayId > 0 && vd != null && admitted())
                ready.countDown();
        }

        void fail(String message) {
            if (failure == null) failure = message;
            cancelled.set(true);
            ready.countDown();
            cleanupOnMain();
        }

        void cleanupOnMain() {
            cancelled.set(true);
            if (cleaned) return;
            // SurfaceHolder callbacks may run reentrantly inside framework allocation. A late
            // addView/createVirtualDisplay return owns its cleanup; no early success is reported.
            if (attaching || creatingDisplay) return;
            if (vd != null) {
                try { vd.release(); vd = null; }
                catch (Throwable error) { failure = reason("slot_virtual_display_release", error); }
            }
            if (listenerRegistered) {
                try { manager.unregisterDisplayListener(displayListener); listenerRegistered = false; }
                catch (Throwable error) { failure = reason("slot_display_listener_release", error); }
            }
            if (view != null && holderCallback != null) {
                try { view.getHolder().removeCallback(holderCallback); holderCallback = null; }
                catch (Throwable error) { failure = reason("slot_holder_listener_release", error); }
            }
            if (windowAttempted && view != null && window != null) {
                try { window.removeViewImmediate(view); windowAttempted = false; }
                catch (IllegalArgumentException absent) {
                    // WMS already detached this view (e.g. target display removal).
                    if (!view.isAttachedToWindow() && view.getParent() == null) windowAttempted = false;
                    else failure = reason("slot_overlay_release", absent);
                } catch (Throwable error) { failure = reason("slot_overlay_release", error); }
            }
            if (vd == null && nativeSurface != null) {
                try {
                    if (nativeSurface.release()) nativeSurface = null;
                } catch (Throwable error) { failure = reason("slot_native_release", error); }
            }
            cleaned = vd == null && !windowAttempted && !listenerRegistered
                    && holderCallback == null && nativeSurface == null;
            if (cleaned) { view = null; window = null; params = null; }
        }
    }

    private static Context shellContext() throws CreateException {
        if (!isSupported()) throw new CreateException("layouts_unavailable_on_current_platform", null);
        return shellContext(CameraDaemon.getAppContext(), false);
    }

    /** Package/UID attribution for daemon-created private virtual displays. */
    public static Context shellDisplayContext(Context base) throws CreateException {
        return shellContext(base, true);
    }

    private static Context shellContext(Context base, boolean forNavigator) throws CreateException {
        if (android.os.Process.myUid() != SHELL_UID)
            throw new CreateException("layouts_require_shell_daemon_uid", null);
        if (base == null) throw new CreateException("daemon_context_unavailable", null);
        try {
            Context context = base.createPackageContext(SHELL_PACKAGE, forNavigator ? 0 : Context.CONTEXT_IGNORE_SECURITY);
            if (forNavigator) verifyShellPackage(context);
            if (forNavigator && !shellOwnsAttribution(context)) {
                Context app = context.getApplicationContext();
                if (app != null && SHELL_PACKAGE.equals(app.getPackageName()) && shellOwnsAttribution(app)) {
                    context = app;
                } else {
                    context = shellApplicationContext(context);
                }
            }
            verifyShellIdentity(context);
            return context;
        } catch (Throwable error) { throw new CreateException(reason("shell_context", error), null); }
    }

    private static void verifyShellIdentity(Context context) throws Exception {
        verifyShellPackage(context);
        if (!shellOwnsAttribution(context)) throw new SecurityException("shell_op_package_mismatch");
    }

    private static void verifyShellPackage(Context context) throws Exception {
        if (context == null || android.os.Process.myUid() != SHELL_UID
                || !SHELL_PACKAGE.equals(context.getPackageName())
                || context.getApplicationInfo().uid != SHELL_UID
                || context.getPackageManager().getApplicationInfo(SHELL_PACKAGE, 0).uid != SHELL_UID)
            throw new SecurityException("shell_package_uid_mismatch");
    }

    private static boolean shellOwnsAttribution(Context context) throws Exception {
        return SHELL_PACKAGE.equals(Context.class.getMethod("getOpPackageName").invoke(context));
    }

    /**
     * Android 10 createPackageContext() inherits the creator's op package under systemMain().
     * Build the shell application's root context as LoadedApk does, using its already-verified
     * package and the framework's default attribution. Does not instantiate the shell Application.
     */
    private static Context shellApplicationContext(Context shell) throws Exception {
        Class<?> impl = Class.forName("android.app.ContextImpl");
        java.lang.reflect.Field packageField = impl.getDeclaredField("mPackageInfo");
        packageField.setAccessible(true);
        Object shellPackage = packageField.get(shell);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object currentThread = thread.getMethod("currentActivityThread").invoke(null);
        java.lang.reflect.Method create = impl.getDeclaredMethod("createAppContext", thread, packageField.getType());
        create.setAccessible(true);
        return (Context) create.invoke(null, currentThread, shellPackage);
    }

    private static DisplayManager manager(Context context) throws CreateException {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) throw new CreateException("display_manager_unavailable", null);
        return manager;
    }

    private static Display visibleDisplay(DisplayManager manager, int id) {
        try {
            Display display = manager.getDisplay(id);
            return display != null && display.getDisplayId() == id && id > 0 && display.isValid()
                    ? display : null;
        } catch (RuntimeException inaccessible) { return null; }
    }

    private static ClusterDisplaySnapshot nativeTarget(int id) throws CreateException {
        ClusterDisplaySnapshot snapshot = ClusterDisplayReader.read(id);
        if (snapshot == null || !snapshot.usableFission())
            throw new CreateException("physical_cluster_display_not_verified", null);
        return snapshot;
    }

    private static Rect bounds(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    private static boolean safeLive(BooleanSupplier live) {
        try { return live.getAsBoolean(); } catch (Throwable ignored) { return false; }
    }

    private static boolean await(CountDownLatch latch, long timeoutMs) {
        try { return latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static String safeName(String name) {
        String value = name == null ? "slot" : name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return value.substring(0, Math.min(40, value.length()));
    }

    private static String reason(String operation, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return operation + "_" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
