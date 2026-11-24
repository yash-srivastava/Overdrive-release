package com.overdrive.app.camera;

import android.hardware.IBYDCameraService;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Cooperative camera coordinator for BYD platform.
 *
 * NOTE: IBYDCameraUser callback registration is disabled. The DI4 panoramic
 * recorder follows an uninterrupted model:
 * one AVMCamera producer stays open while the native camera UI co-consumes it.
 * Legacy contention detection remains polling-based.
 *
 * Cleanup order (always): disablePreviewCallback → stopPreview → close
 */
public class BydCameraCoordinator {

    private static final String TAG = "BydCameraCoordinator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // IBYDCameraService — typed proxy (preferred) or reflection proxy (fallback)
    private IBYDCameraService typedServiceProxy;
    private Object reflectionServiceProxy;  // Fallback if typed stub doesn't match runtime
    private Method getCurrentCameraUserMethod;  // For polling fallback
    private boolean serviceAvailable = false;

    // Event callback state (AVMCamera.IEventCallback — separate from IBYDCameraUser)
    private boolean eventCallbackSet = false;
    private volatile Object surfaceTextureEventOwner;
    private Object surfaceTextureEventProxy;
    // DI4 only: append the HAL's arg1/arg2 so field logs can tell event causes apart.
    private volatile boolean logEventArgs = false;

    // Native app activity tracking (for polling fallback)
    private volatile boolean nativeAppActive = false;

    // Yield state
    private volatile boolean yielded = false;
    private volatile long yieldTimestamp = 0;
    private static final long REACQUIRE_DELAY_MS = 200;  // Native app fully closed by onCloseCamera

    // Callback to PanoramicCameraGpu
    public interface CameraYieldCallback {
        /**
         * Release the active AVMCamera ownership.
         *
         * @return true once this process no longer owns the camera handle.
         */
        boolean onYieldCamera();
        void onReacquireCamera();
        void onCameraError(int eventType);
    }

    private CameraYieldCallback yieldCallback;
    private volatile int activeCameraId = 1;

    public BydCameraCoordinator() {
    }

    public void setYieldCallback(CameraYieldCallback callback) {
        this.yieldCallback = callback;
    }

    public void setActiveCameraId(int cameraId) {
        this.activeCameraId = cameraId;
    }

    public void setLogEventArgs(boolean logEventArgs) {
        this.logEventArgs = logEventArgs;
    }

    // ==================== IBYDCameraService Connection ====================

    /**
     * Connects to IBYDCameraService for legacy polling only.
     *
     * Resolves the typed service first, then prepares reflection-based
     * getCurrentCameraUser polling where supported.
     */
    public void register() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, "bydcameramanager");

            if (binder == null) {
                logger.warn("IBYDCameraService not available (binder is null)");
                return;
            }

            serviceAvailable = true;

            // Try BYD-custom zero-arg asInterface() first.
            // The real implementation in bmmcamera.jar gets the binder from ServiceManager internally.
            try {
                typedServiceProxy = IBYDCameraService.Stub.asInterface();
                if (typedServiceProxy != null) {
                    logger.info("IBYDCameraService connected via zero-arg asInterface");
                } else {
                    logger.info("Zero-arg asInterface returned null — trying IBinder overload");
                }
            } catch (Throwable e) {
                logger.warn("Zero-arg asInterface failed: " + e.getMessage());
                typedServiceProxy = null;
            }

            // Fallback: standard asInterface(IBinder) if zero-arg didn't work
            if (typedServiceProxy == null) {
                try {
                    typedServiceProxy = IBYDCameraService.Stub.asInterface(
                        (android.os.IBinder) binder);
                    if (typedServiceProxy != null) {
                        logger.info("IBYDCameraService connected via asInterface(IBinder)");
                    }
                } catch (Throwable e) {
                    logger.warn("asInterface(IBinder) failed: " + e.getMessage());
                    typedServiceProxy = null;
                }
            }

            // Also set up reflection proxy as fallback for polling
            try {
                Class<?> stubClass = Class.forName("android.hardware.IBYDCameraService$Stub");
                Method asInterface = stubClass.getDeclaredMethod("asInterface",
                    Class.forName("android.os.IBinder"));
                asInterface.setAccessible(true);
                reflectionServiceProxy = asInterface.invoke(null, binder);

                if (reflectionServiceProxy != null) {
                    try {
                        getCurrentCameraUserMethod = reflectionServiceProxy.getClass()
                            .getDeclaredMethod("getCurrentCameraUser");
                        getCurrentCameraUserMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        logger.warn("getCurrentCameraUser not found on service");
                    }
                }
            } catch (Exception e) {
                logger.warn("Reflection proxy setup failed: " + e.getMessage());
            }

            // Do not register IBYDCameraUser. In particular, the DI4 pano
            // recorder must not enter the ordinary-DVR release protocol.

        } catch (ClassNotFoundException e) {
            logger.info("IBYDCameraService not found — camera arbitration unavailable");
        } catch (Exception e) {
            logger.warn("IBYDCameraService setup failed: " + e.getMessage());
        }
    }

    // ==================== Yield State Query ====================

    /**
     * Checks if the camera is currently yielded due to contention.
     * Returns false if native app opened but sharing is working (no frame stall).
     */
    public boolean isCameraYielded() {
        return yielded;
    }

    /** Event-driven camera-user registration is intentionally disabled. */
    public boolean isRegisteredAsUser() {
        return false;
    }

    // ==================== Polling Fallback ====================

    /**
     * Queries the current camera user's package name.
     * Returns the package name of the app currently holding the camera,
     * or null if no other app has it (or if we are the holder).
     *
     * Used at camera open time to decide PRIMARY vs SECONDARY mode.
     */
    public String queryCurrentCameraUser() {
        if (getCurrentCameraUserMethod != null && reflectionServiceProxy != null) {
            try {
                Object currentUser = getCurrentCameraUserMethod.invoke(reflectionServiceProxy);
                if (currentUser != null) {
                    Method getPkg = currentUser.getClass().getMethod("getPackageName");
                    String pkg = (String) getPkg.invoke(currentUser);
                    if (pkg != null && !"com.overdrive.app".equals(pkg)) return pkg;
                }
            } catch (Exception e) { /* ignore */ }
        }
        return null;
    }

    /**
     * Checks if another app currently holds the camera via
     * getCurrentCameraUser() polling.
     *
     * @return true if another camera user is active (native AVM app)
     */
    public boolean checkNativeAppActive() {
        if (!serviceAvailable || getCurrentCameraUserMethod == null) {
            return false;
        }

        try {
            Object currentUser = getCurrentCameraUserMethod.invoke(reflectionServiceProxy);
            if (currentUser != null) {
                String pkg = null;
                try {
                    Method getPkg = currentUser.getClass().getMethod("getPackageName");
                    pkg = (String) getPkg.invoke(currentUser);
                } catch (Exception ignored) {}

                boolean isUs = "com.overdrive.app".equals(pkg);
                boolean wasActive = nativeAppActive;
                nativeAppActive = !isUs && pkg != null;

                if (nativeAppActive && !wasActive) {
                    logger.info("Native app detected via polling: " + pkg);
                } else if (!nativeAppActive && wasActive) {
                    logger.info("Native app released camera (polling)");
                    handleNativeAppClosed();
                }

                return nativeAppActive;
            } else {
                if (nativeAppActive) {
                    logger.info("No current camera user (polling) — native app released");
                    nativeAppActive = false;
                    handleNativeAppClosed();
                }
                return false;
            }
        } catch (Exception e) {
            return nativeAppActive;
        }
    }

    private void handleNativeAppClosed() {
        nativeAppActive = false;

        if (yielded) {
            yielded = false;
            long yieldDuration = System.currentTimeMillis() - yieldTimestamp;
            logger.info("Re-acquiring camera after contention yield (yielded for " +
                yieldDuration + "ms)");

            if (yieldCallback != null) {
                new Thread(() -> {
                    try {
                        Thread.sleep(REACQUIRE_DELAY_MS);
                    } catch (InterruptedException ignored) {}

                    if (!yielded && !nativeAppActive) {
                        yieldCallback.onReacquireCamera();
                    }
                }, "CameraReacquire").start();
            }
        }
    }

    // ==================== Contention Detection (Fallback) ====================

    /**
     * Called by the frame stall detector when no frames arrive for 2+ seconds.
     *
     * Registration is disabled, so only the legacy polling path runs.
     */
    public boolean onFrameStallDetected() {
        checkNativeAppActive();

        if (nativeAppActive) {
            logger.warn("CONTENTION DETECTED: Frame stall + native app active — yielding");
            yielded = true;
            yieldTimestamp = System.currentTimeMillis();

            if (yieldCallback != null) {
                yieldCallback.onYieldCamera();
            }
            return true;
        } else {
            logger.warn("Frame stall but native app NOT active — HAL issue");
            return false;
        }
    }

    // ==================== AVMCamera Event Callback ====================

    public void setupEventCallback(Object cameraObj) {
        if (cameraObj == null) return;
        if (cameraObj instanceof com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend) return;
        if (cameraObj instanceof com.overdrive.app.camera.camera2.Camera2AvmBackend) return;
        if (AvmSurfaceTexturePlatform.isSelected()) {
            setupSurfaceTextureEventCallback(cameraObj);
            return;
        }
        if (eventCallbackSet) return;  // already wired; resetEventCallbackState clears

        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

            Class<?> eventCallbackClass = null;
            for (Class<?> inner : avmClass.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("IEventCallback")) {
                    eventCallbackClass = inner;
                    break;
                }
            }

            if (eventCallbackClass == null) {
                logger.warn("AVMCamera.IEventCallback not found");
                return;
            }

            Object eventProxy = Proxy.newProxyInstance(
                eventCallbackClass.getClassLoader(),
                new Class<?>[]{ eventCallbackClass },
                (proxy, method, args) -> {
                    if (method.getName().startsWith("on")) {
                        int eventType = 0;
                        if (args != null && args.length >= 2 && args[1] instanceof Integer) {
                            eventType = (Integer) args[1];
                        }
                        String argSuffix = "";
                        if (logEventArgs && args != null && args.length >= 4) {
                            argSuffix = " (arg1=" + args[2] + ", arg2=" + args[3] + ")";
                        }
                        handleCameraEvent(eventType, argSuffix);
                    }
                    return null;
                }
            );

            Method setEventCallback = avmClass.getDeclaredMethod("setEventCallback",
                eventCallbackClass);
            setEventCallback.setAccessible(true);
            setEventCallback.invoke(cameraObj, eventProxy);

            eventCallbackSet = true;
            logger.info("AVMCamera event callback registered");

        } catch (ClassNotFoundException e) {
            logger.info("AVMCamera.IEventCallback not available");
        } catch (NoSuchMethodException e) {
            logger.warn("setEventCallback not found: " + e.getMessage());
        } catch (Exception e) {
            logger.warn("Event callback setup failed: " + e.getMessage());
        }
    }

    /** The opt-in factory may return NormalCamera or an inherited camera API. */
    void setupSurfaceTextureEventCallback(Object camera) {
        if (surfaceTextureEventOwner == camera && eventCallbackSet) return;
        resetEventCallbackState();
        try {
            Method setter = null;
            for (Method[] methods : new Method[][]{
                    camera.getClass().getMethods(), camera.getClass().getDeclaredMethods()}) {
                for (Method candidate : methods) {
                    if ("setEventCallback".equals(candidate.getName())
                            && candidate.getParameterTypes().length == 1
                            && candidate.getParameterTypes()[0].isInterface()) {
                        setter = candidate;
                        break;
                    }
                }
                if (setter != null) break;
            }
            if (setter == null) {
                logger.info("SurfaceTexture vendor camera has no event callback API");
                return;
            }
            Class<?> callbackClass = setter.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(
                callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (callback, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "hashCode": return System.identityHashCode(callback);
                            case "equals": return args != null && args.length == 1
                                && callback == args[0];
                            case "toString": return "SurfaceTexture camera event callback";
                        }
                    }
                    if (surfaceTextureEventOwner != camera) return null;
                    if (method.getName().startsWith("on")
                            && args != null && args.length >= 2
                            && args[1] instanceof Integer) {
                        handleCameraEvent((Integer) args[1], "");
                    }
                    return null;
                });
            surfaceTextureEventProxy = proxy;
            surfaceTextureEventOwner = camera;
            setter.setAccessible(true);
            Object accepted = setter.invoke(camera, proxy);
            if (setter.getReturnType() != void.class && !Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("setEventCallback returned " + accepted);
            }
            if (surfaceTextureEventOwner == camera) {
                eventCallbackSet = true;
                logger.info("SurfaceTexture vendor camera event callback registered");
            }
        } catch (Exception | LinkageError failure) {
            retireSurfaceTextureEventCallback(camera);
            logger.warn("SurfaceTexture event callback unavailable: " + failure.getMessage());
        }
    }

    void retireSurfaceTextureEventCallback(Object camera) {
        if (surfaceTextureEventOwner == camera) resetEventCallbackState();
    }

    private void handleCameraEvent(int eventType, String argSuffix) {
        if (eventType == 1003) {
            // EVT_TYPE_FIRST_FRAME — HAL confirmed first frame delivered
            logger.info("Camera HAL: first frame delivered (event 1003)" + argSuffix);
        } else if (eventType == -10086 || eventType == 8) {
            logger.error("CAMERA HAL ERROR: event=" + eventType + argSuffix);
            if (yieldCallback != null) {
                yieldCallback.onCameraError(eventType);
            }
        } else if (eventType == 1002) {
            // EVT_TYPE_SERVER_DIED — camera server process died
            logger.error("CAMERA HAL: server died (event 1002)" + argSuffix);
            if (yieldCallback != null) {
                yieldCallback.onCameraError(eventType);
            }
        } else if (eventType == 1000) {
            // EVT_TYPE_ERR — generic camera error
            logger.warn("Camera HAL error event: " + eventType + argSuffix);
        } else if (eventType != 0 && eventType != 1001) {
            logger.info("Camera event: " + eventType + argSuffix);
        }
    }

    // ==================== Proper Camera Cleanup ====================

    /**
     * Notify IBYDCameraService before opening camera.
     *
     * <p>Intentionally a no-op. Panoramic recording opens AVMCamera
     * directly and does not join IBYDCameraUser arbitration.
     */
    public void notifyPreOpenCamera() {
        // Open directly without announcing an ownership transition.
    }

    /**
     * Notify IBYDCameraService after closing camera.
     *
     * <p>Intentionally a no-op for the same direct-open model as
     * {@link #notifyPreOpenCamera()}.
     */
    public void notifyPosCloseCamera() {
        // No camera-user registration exists to notify.
    }

    /**
     * Close an AVMCamera and report whether the vendor close call completed.
     *
     * <p>disablePreviewCallback/stopPreview are best-effort cleanup. Native
     * ownership is considered released only after close() returns normally.
     */
    public static boolean closeCamera(Object cameraObj, int channelId) {
        if (cameraObj == null) return true;

        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

            try {
                Method m = avmClass.getDeclaredMethod("disablePreviewCallback", int.class);
                m.setAccessible(true);
                m.invoke(cameraObj, channelId);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable e) {
                logger.warn("disablePreviewCallback failed: " + e.getMessage());
            }

            try {
                Method m = avmClass.getDeclaredMethod("stopPreview");
                m.setAccessible(true);
                m.invoke(cameraObj);
            } catch (Throwable e) {
                logger.warn("stopPreview failed: " + e.getMessage());
            }

            try {
                Method m = avmClass.getDeclaredMethod("close");
                m.setAccessible(true);
                m.invoke(cameraObj);
                return true;
            } catch (Throwable e) {
                logger.warn("close failed: " + e.getMessage());
                return false;
            }

        } catch (Throwable e) {
            logger.error("AVMCamera class unavailable: " + e.getMessage());
            return false;
        }
    }

    // ==================== Lifecycle ====================

    public void unregister() {
        serviceAvailable = false;
        typedServiceProxy = null;
        reflectionServiceProxy = null;
        getCurrentCameraUserMethod = null;
        yielded = false;
        nativeAppActive = false;
    }

    // ==================== State Queries ====================

    public boolean isNativeAppActive() { return nativeAppActive; }
    public boolean isYielded() { return isCameraYielded(); }
    public boolean isRegistered() { return serviceAvailable; }
    public boolean isEventCallbackActive() { return eventCallbackSet; }
    public void resetEventCallbackState() {
        surfaceTextureEventOwner = null;
        surfaceTextureEventProxy = null;
        eventCallbackSet = false;
    }

    /**
     * audit avc-yield (round 3, finding 9): clear sticky yielded /
     * nativeAppActive flags from the error-recovery path. Required because
     * checkNativeAppActive's catch branch returns the cached nativeAppActive
     * value on transient binder errors (line 411), so a missed
     * active→inactive edge can leave both flags stuck true forever — and
     * startCamera()'s isCameraYielded() gate then early-returns silently on
     * every subsequent restart attempt. Caller (restartCameraAfterError) is
     * actively closing+reopening, so by definition we are no longer yielded;
     * if the native app is genuinely still holding the camera, the next
     * polling tick re-sets the flags correctly.
     */
    public void clearYieldedForRestart() {
        boolean wasYielded = yielded;
        boolean wasActive = nativeAppActive;
        yielded = false;
        nativeAppActive = false;
        if (wasYielded || wasActive) {
            logger.warn("clearYieldedForRestart: cleared yielded="
                + wasYielded + " nativeAppActive=" + wasActive
                + " (error-recovery path)");
        }
    }

    // ==================== AIDL Discovery ====================

    /**
     * Discovers the full IBYDCameraService and IBYDCameraUser AIDL interfaces
     * by enumerating methods via reflection. Logs everything for debugging.
     */
    public void discoverCameraServiceApi() {
        logger.info("=== IBYDCameraService API Discovery ===");

        // Enumerate service methods
        Object serviceProxy = typedServiceProxy != null ? typedServiceProxy : reflectionServiceProxy;
        if (serviceProxy != null) {
            logger.info("--- Service proxy methods ---");
            try {
                for (Method m : serviceProxy.getClass().getDeclaredMethods()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(m.getName()).append("(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getName());
                    }
                    sb.append(")");
                    logger.info(sb.toString());
                }
            } catch (Exception e) {
                logger.warn("Failed to enumerate service methods: " + e.getMessage());
            }
        }

        // Check for IBYDCameraUser
        String[] candidates = {
            "android.hardware.IBYDCameraUser",
            "android.hardware.IBYDCameraUser$Stub",
        };
        for (String name : candidates) {
            try {
                Class<?> cls = Class.forName(name);
                logger.info("FOUND: " + name);
                for (Method m : cls.getDeclaredMethods()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(m.getName()).append("(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getName());
                    }
                    sb.append(")");
                    logger.info(sb.toString());
                }
            } catch (ClassNotFoundException e) {
                logger.info("NOT FOUND: " + name);
            } catch (Exception e) {
                logger.warn("Error probing " + name + ": " + e.getMessage());
            }
        }

        // Log transaction codes from Stub classes
        String[] stubs = {
            "android.hardware.IBYDCameraService$Stub",
            "android.hardware.IBYDCameraUser$Stub",
        };
        for (String name : stubs) {
            try {
                Class<?> cls = Class.forName(name);
                logger.info("--- " + name + " fields ---");
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(null);
                        logger.info("  " + f.getName() + " = " + val);
                    } catch (Exception e) {
                        logger.info("  " + f.getName() + " (type=" + f.getType().getSimpleName() + ")");
                    }
                }
            } catch (ClassNotFoundException e) {
                // Already logged above
            } catch (Exception e) {
                logger.warn("Stub field scan failed for " + name + ": " + e.getMessage());
            }
        }

        logger.info("=== Discovery complete (camera-user registration disabled) ===");
    }
}
