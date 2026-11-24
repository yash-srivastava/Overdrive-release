package com.overdrive.app.camera;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Vendor preview adapter used exclusively by the SurfaceTexture ingestion mode.
 * The Surface and its SurfaceTexture remain owned by PanoramicCameraGpu.
 */
public final class AvmSurfaceTextureBackend {
    private final Object camera;
    private final int cameraId;
    private final String acquisition;
    private Object attachedSurface;
    private Method removeSurface;
    private int previewIndex;
    private boolean closed;
    private static boolean bridgeBootstrapped;
    private static final String[] VENDOR_CAMERA_TAGS = {"front", "rear", "rvs", "rf", "dms", "oms",
            "face", "avm", "pano", "pano_h", "pano_l", "apa", "byd_apa"};
    private static volatile int unlistedPanoId = -1;
    private static final java.util.Map<Integer, String> UNLISTED_NOT_PANO =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AvmSurfaceTextureBackend(Object camera, int cameraId, String acquisition) {
        this.camera = camera;
        this.cameraId = cameraId;
        this.acquisition = acquisition;
    }

    /** The bridge is optional on firmware where AVMCamera loads its own JNI. */
    public static synchronized void bootstrapVendorBridge() throws Exception {
        if (bridgeBootstrapped) return;
        Class<?> loader = optionalClass("android.hardware.JNILoader");
        if (loader != null) invoke(method(loader, "load"), null);
        bridgeBootstrapped = true;
    }

    public static AvmSurfaceTextureBackend open(int cameraId) throws Exception {
        bootstrapVendorBridge();
        Class<?> info = optionalClass("android.hardware.BmmCameraInfo");
        if (info != null) {
            Method valid = optionalMethod(info, "isValidCamera", int.class);
            if (valid != null && Boolean.FALSE.equals(invoke(valid, null, cameraId))) {
                Class<?> avm = optionalClass("android.hardware.AVMCamera");
                if (avm != null && listsNoCamera(info)) {
                    return openUnlisted(cameraId, avm);
                }
                throw new IllegalArgumentException(
                        "BmmCameraInfo rejected panoramic camera id " + cameraId);
            }
        }
        return open(cameraId, optionalClass("android.hardware.AVMCamera"),
                optionalClass("android.hardware.NormalCamera"));
    }

    /** No tag resolves when the shell daemon cannot read the vehicle camera list. */
    private static boolean listsNoCamera(Class<?> info) {
        Method byTag = optionalMethod(info, "getCameraId", String.class);
        if (byTag == null) return false;
        try {
            for (String tag : VENDOR_CAMERA_TAGS) {
                Object id = invoke(byTag, null, tag);
                if (!(id instanceof Integer) || (Integer) id >= 0) return false;
            }
            return true;
        } catch (Exception | Error unknown) {
            return false;
        }
    }

    /**
     * Unlisted cameras are reachable only through the constructor and open(). A camera is kept
     * only if it declares a 360 strip; any other is closed before its preview starts.
     */
    static AvmSurfaceTextureBackend openUnlisted(int preferredId, Class<?> avmClass)
            throws Exception {
        if (preferredId < 0) throw new IllegalArgumentException("Negative camera id");
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        int known = unlistedPanoId;
        if (known >= 0) {
            ids.add(known);
        } else {
            ids.add(preferredId);
            for (int id = 0; id <= DiLink100Platform.MAX_PANO_SEARCH_ID; id++) ids.add(id);
        }
        StringBuilder tried = new StringBuilder();
        for (int id : ids) {
            String seen = UNLISTED_NOT_PANO.get(id);
            if (seen != null) {
                tried.append(" id=").append(id).append(':').append(seen);
                continue;
            }
            Object candidate = null;
            boolean keep = false;
            try {
                candidate = constructUnlisted(avmClass, id);
                Method open = method(candidate.getClass(), "open");
                Object accepted = invoke(open, candidate);
                if (open.getReturnType() != void.class && !Boolean.TRUE.equals(accepted)) {
                    tried.append(" id=").append(id).append(":open=").append(accepted);
                    continue;
                }
                Object w = invoke(method(candidate.getClass(), "getPreviewWidth"), candidate);
                Object h = invoke(method(candidate.getClass(), "getPreviewHeight"), candidate);
                int width = w instanceof Integer ? (Integer) w : -1;
                int height = h instanceof Integer ? (Integer) h : -1;
                if (!DiLink100Platform.isPanoSuperFrame(width, height)) {
                    String size = width + "x" + height;
                    if (id != preferredId && width > 0 && height > 0) UNLISTED_NOT_PANO.put(id, size);
                    tried.append(" id=").append(id).append(':').append(size);
                    continue;
                }
                keep = true;
                unlistedPanoId = id;
                return new AvmSurfaceTextureBackend(candidate, id, avmClass.getSimpleName()
                        + " constructor + open(), unlisted, " + width + "x" + height
                        + (id == preferredId ? "" : ", configured id " + preferredId));
            } catch (Exception | Error failure) {
                Throwable shown = failure instanceof InvocationTargetException
                        && failure.getCause() != null ? failure.getCause() : failure;
                tried.append(" id=").append(id).append(':').append(shown.getClass().getSimpleName());
            } finally {
                if (!keep && candidate != null) {
                    try { invoke(method(candidate.getClass(), "close"), candidate); }
                    catch (Exception | Error ignored) {}
                }
            }
        }
        throw new IllegalStateException(
                "BmmCameraInfo lists no camera; no AVMCamera id opened as a 360 strip:" + tried);
    }

    private static Object constructUnlisted(Class<?> avmClass, int id) throws Exception {
        Constructor<?> constructor;
        Object[] args;
        try {
            constructor = avmClass.getDeclaredConstructor(int.class, int.class);
            args = new Object[]{id, 0};
        } catch (NoSuchMethodException missingTwoArgument) {
            constructor = avmClass.getDeclaredConstructor(int.class);
            args = new Object[]{id};
        }
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    /** Explicit classes allow the vendor protocol to be exercised without a car. */
    static AvmSurfaceTextureBackend open(
            int cameraId, Class<?> avmClass, Class<?> normalClass) throws Exception {
        if (cameraId < 0) throw new IllegalArgumentException("Negative camera id");
        boolean factoryAvailable = false;
        for (Class<?> type : new Class<?>[]{avmClass, normalClass}) {
            if (type == null) continue;
            Method factory = optionalMethod(type, "open", int.class);
            if (factory == null || !Modifier.isStatic(factory.getModifiers())) continue;
            factoryAvailable = true;
            Object opened = invoke(factory, null, cameraId);
            if (opened != null) {
                return new AvmSurfaceTextureBackend(
                        opened, cameraId, type.getSimpleName() + ".open(int)");
            }
        }
        // Do not probe arbitrary IDs or continue after a factory throws.
        if (factoryAvailable || avmClass == null) {
            throw new IllegalStateException(
                    "Vendor factories could not open panoramic camera id " + cameraId);
        }
        Constructor<?> constructor;
        Object candidate;
        try {
            constructor = avmClass.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            candidate = constructor.newInstance(cameraId);
        } catch (NoSuchMethodException missingSingleArgument) {
            constructor = avmClass.getDeclaredConstructor(int.class, int.class);
            constructor.setAccessible(true);
            candidate = constructor.newInstance(cameraId, 0);
        }
        try {
            requireAccepted(method(candidate.getClass(), "open"), candidate);
            return new AvmSurfaceTextureBackend(
                    candidate, cameraId, avmClass.getSimpleName() + " constructor");
        } catch (Exception | Error failure) {
            try { invoke(method(candidate.getClass(), "close"), candidate); }
            catch (Exception | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public Object getCamera() {
        return camera;
    }

    public int getCameraId() {
        return cameraId;
    }

    public boolean owns(Object candidate) {
        return camera == candidate;
    }

    public String describe() {
        return camera.getClass().getName() + " id=" + cameraId + " via " + acquisition;
    }

    /** Size/FPS refusals are diagnostic; real delivery is proven by frame callbacks. */
    public String configurePreview(int fps, int width, int height) {
        return "fps=" + fps + " setFps="
                + optionalCall("setCameraFps", new Class<?>[]{int.class}, fps)
                + " requested=" + width + "x" + height + " setSize="
                + optionalCall("setPreviewSize",
                        new Class<?>[]{int.class, int.class}, width, height)
                + " declared="
                + optionalCall("getPreviewWidth", new Class<?>[0])
                + "x" + optionalCall("getPreviewHeight", new Class<?>[0]);
    }

    public synchronized void attachAndStart(
            Object surface, Class<?> surfaceType, int index) throws Exception {
        if (closed) throw new IllegalStateException("Camera is already closed");
        if (surface == null || !surfaceType.isInstance(surface)) {
            throw new IllegalArgumentException("Missing preview Surface");
        }
        if (attachedSurface != null) {
            throw new IllegalStateException("A preview Surface is already attached");
        }
        Method add = optionalMethod(
                camera.getClass(), "addPreviewSurface", surfaceType, int.class);
        if (add != null) {
            removeSurface = optionalMethod(
                    camera.getClass(), "rmPreviewSurface", surfaceType, int.class);
        } else {
            add = method(camera.getClass(), "addPreviewSurface", surfaceType);
            removeSurface = optionalMethod(
                    camera.getClass(), "rmPreviewSurface", surfaceType);
        }
        previewIndex = index;
        // Retain the exact attempted binding even if the HAL refuses attachment.
        // Cleanup must never detach a replacement consumer.
        attachedSurface = surface;
        if (add.getParameterTypes().length == 2) {
            requireAccepted(add, camera, surface, index);
        } else {
            requireAccepted(add, camera, surface);
        }
        requireAccepted(method(camera.getClass(), "startPreview"), camera);
    }

    /** Stop/close still run if surface removal or callback cleanup fails. */
    public synchronized boolean close() {
        if (closed) return true;
        Object surface = attachedSurface;
        attachedSurface = null;
        if (surface != null && removeSurface != null) {
            try {
                if (removeSurface.getParameterTypes().length == 2) {
                    invoke(removeSurface, camera, surface, previewIndex);
                } else {
                    invoke(removeSurface, camera, surface);
                }
            } catch (Exception | Error ignored) {
                // Vendor close is the authoritative ownership-release operation.
            }
        }
        clearCallback("setPreviewCallback");
        clearCallback("setEventCallback");
        optionalCall("disablePreviewCallback", new Class<?>[]{int.class}, previewIndex);
        optionalCall("stopPreview", new Class<?>[0]);
        try {
            requireAccepted(method(camera.getClass(), "close"), camera);
            closed = true;
            return true;
        } catch (Exception | Error failure) {
            return false;
        }
    }

    private void clearCallback(String name) {
        for (Method candidate : camera.getClass().getMethods()) {
            if (name.equals(candidate.getName())
                    && candidate.getParameterTypes().length == 1
                    && !candidate.getParameterTypes()[0].isPrimitive()) {
                try { invoke(candidate, camera, new Object[]{null}); }
                catch (Exception | Error ignored) {}
                return;
            }
        }
    }

    private String optionalCall(String name, Class<?>[] parameters, Object... args) {
        try {
            Method target = optionalMethod(camera.getClass(), name, parameters);
            if (target == null) return "unsupported";
            Object result = invoke(target, camera, args);
            return target.getReturnType() == void.class ? "void" : String.valueOf(result);
        } catch (Exception | Error failure) {
            return "error(" + failure.getClass().getSimpleName() + ")";
        }
    }

    private static void requireAccepted(
            Method target, Object receiver, Object... args) throws Exception {
        Object result = invoke(target, receiver, args);
        if (target.getReturnType() == void.class) return;
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException(target.getName() + " returned " + result);
        }
    }

    private static Object invoke(
            Method target, Object receiver, Object... args) throws Exception {
        try {
            target.setAccessible(true);
            return target.invoke(receiver, args);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Vendor call failed", cause);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException missingPublic) {
            return type.getDeclaredMethod(name, parameters);
        }
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try { return method(type, name, parameters); }
        catch (NoSuchMethodException missing) { return null; }
    }

    private static Class<?> optionalClass(String name) throws ClassNotFoundException {
        try { return Class.forName(name); }
        catch (ClassNotFoundException missing) { return null; }
    }
}
