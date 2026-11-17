package com.overdrive.app.launcher.layout;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Surface;

import com.overdrive.app.launcher.ClusterDisplaySnapshot;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A slot's buffer layer on the verified DI3 cluster stack. It owns no display or input channel.
 * Uses the same daemon SurfaceControl capability as native cluster camera overlays.
 */
final class ClusterLayoutNativeSurface {
    // Ordinary app content stays below the existing camera and speed layers.
    private static final int LAYOUT_Z = 1_000_000;
    private final String name;
    private final Rect rect;
    private final ClusterDisplaySnapshot target;
    private Object control;
    private Surface surface;
    private boolean removed;

    ClusterLayoutNativeSurface(String name, Rect rect, ClusterDisplaySnapshot target) {
        this.name = name;
        this.rect = new Rect(rect);
        this.target = target;
    }

    void create() throws Exception {
        if (target == null || !target.usableFission()
                || !new Rect(0, 0, target.width, target.height).contains(rect))
            throw new IllegalArgumentException("unverified_native_cluster_target");
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
        Object builder = builderClass.getDeclaredConstructor().newInstance();
        builderClass.getMethod("setName", String.class).invoke(builder, name);
        builderClass.getMethod("setBufferSize", int.class, int.class)
                .invoke(builder, rect.width(), rect.height());
        builderClass.getMethod("setFormat", int.class).invoke(builder, PixelFormat.RGBA_8888);
        control = builderClass.getMethod("build").invoke(builder);
        if (control == null) throw new IllegalStateException("native_slot_layer_unavailable");
        Class<?> controlClass = Class.forName("android.view.SurfaceControl");
        Object transaction = transaction();
        try {
            Class<?> transactionClass = transaction.getClass();
            transactionClass.getMethod("setLayerStack", controlClass, int.class)
                    .invoke(transaction, control, target.layerStack);
            transactionClass.getMethod("setLayer", controlClass, int.class)
                    .invoke(transaction, control, LAYOUT_Z);
            transactionClass.getMethod("setPosition", controlClass, float.class, float.class)
                    .invoke(transaction, control, (float) rect.left, (float) rect.top);
            transactionClass.getMethod("setWindowCrop", controlClass, Rect.class)
                    .invoke(transaction, control, new Rect(0, 0, rect.width(), rect.height()));
            transactionClass.getMethod("hide", controlClass).invoke(transaction, control);
            apply(transaction);
        } finally { close(transaction); }
        surface = (Surface) Surface.class.getConstructor(controlClass).newInstance(control);
        if (!surface.isValid()) throw new IllegalStateException("native_slot_surface_unavailable");
    }

    Surface surface() { return surface; }

    /** A single compositor transaction reveals all native slots; cancellation hides them again. */
    static boolean showAll(List<ClusterLayoutNativeSurface> slots, BooleanSupplier live) throws Exception {
        if (!live.getAsBoolean()) return false;
        if (slots.isEmpty()) return true;
        Object transaction = transaction();
        try {
            Class<?> controlClass = Class.forName("android.view.SurfaceControl");
            for (ClusterLayoutNativeSurface slot : slots) {
                if (!live.getAsBoolean() || slot.control == null || slot.surface == null
                        || !slot.surface.isValid()) return false;
                transaction.getClass().getMethod("show", controlClass).invoke(transaction, slot.control);
            }
            if (!live.getAsBoolean()) return false;
            apply(transaction);
        } finally { close(transaction); }
        if (live.getAsBoolean()) return true;
        for (ClusterLayoutNativeSurface slot : slots) slot.hide();
        return false;
    }

    void hide() throws Exception {
        if (control == null || removed) return;
        Object transaction = transaction();
        try {
            transaction.getClass().getMethod("hide", Class.forName("android.view.SurfaceControl"))
                    .invoke(transaction, control);
            apply(transaction);
        } finally { close(transaction); }
    }

    /** Retains each unreleased handle after a failed cleanup so its owner can retry. */
    boolean release() throws Exception {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (control != null) {
            if (!removed) {
                Object transaction = transaction();
                try {
                    transaction.getClass().getMethod("remove", Class.forName("android.view.SurfaceControl"))
                            .invoke(transaction, control);
                    apply(transaction);
                    removed = true;
                } finally { close(transaction); }
            }
            control.getClass().getMethod("release").invoke(control);
            control = null;
        }
        return surface == null && control == null;
    }

    private static Object transaction() throws Exception {
        return Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
    }

    private static void apply(Object transaction) throws Exception {
        transaction.getClass().getMethod("apply").invoke(transaction);
    }

    private static void close(Object transaction) {
        try { transaction.getClass().getMethod("close").invoke(transaction); }
        catch (Exception ignored) {}
    }
}
