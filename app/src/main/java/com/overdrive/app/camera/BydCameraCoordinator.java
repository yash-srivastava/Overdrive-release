package com.overdrive.app.camera;

import android.hardware.IBYDCameraService;
import android.hardware.IBYDCameraUser;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Cooperative camera coordinator for BYD platform.
 *
 * PRIMARY MECHANISM: Registers with IBYDCameraService as a camera user via
 * IBYDCameraUser.Stub. This gives us event-driven callbacks:
 *   - onPreOpenCamera: native app wants camera → yield immediately
 *   - onCloseCamera: native app released camera → reopen
 *
 * FALLBACK: If registerUser fails (older firmware, missing API), falls back to
 * polling getCurrentCameraUser() + frame stall detection.
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

    // Camera user registration (primary mechanism)
    private BydCameraUser cameraUser;
    private boolean registeredAsUser = false;

    // Event callback state (AVMCamera.IEventCallback — separate from IBYDCameraUser)
    private boolean eventCallbackSet = false;

    // Native app activity tracking (for polling fallback)
    private volatile boolean nativeAppActive = false;

    // Yield state
    private volatile boolean yielded = false;
    private volatile long yieldTimestamp = 0;
    private static final long REACQUIRE_DELAY_MS = 200;  // Native app fully closed by onCloseCamera

    // Callback to PanoramicCameraGpu
    public interface CameraYieldCallback {
        void onYieldCamera();
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

    // ==================== IBYDCameraService Connection ====================

    /**
     * Connects to IBYDCameraService and registers as a camera user.
     *
     * Tries typed AIDL stubs first (for registerUser). If that fails,
     * falls back to reflection-based polling (getCurrentCameraUser).
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

            // Run API discovery FIRST to log all available methods
            // This runs before registerCameraUser so we always get the method dump
            // even if registration fails unexpectedly
            discoverCameraServiceApi();

            // Try to register as camera user (primary mechanism)
            registerCameraUser();

        } catch (ClassNotFoundException e) {
            logger.info("IBYDCameraService not found — camera arbitration unavailable");
        } catch (Exception e) {
            logger.warn("IBYDCameraService setup failed: " + e.getMessage());
        }
    }

    // ==================== Camera User Registration ====================

    /**
     * Registers with IBYDCameraService as a camera user.
     * This is the primary mechanism — gives us event-driven yield/reacquire callbacks.
     */
    private void registerCameraUser() {
        if (registeredAsUser) {
            logger.info("Already registered as camera user");
            return;
        }

        // Create our camera user implementation
        cameraUser = new BydCameraUser(activeCameraId, "com.overdrive.app");
        cameraUser.setListener(new BydCameraUser.CameraYieldListener() {
            @Override
            public void onYieldRequired() {
                // Only called from yieldDueToContention() — frame stall + native app active
                logger.info("IBYDCameraUser: yield required (frame stall contention) — notifying pipeline");
                yielded = true;
                nativeAppActive = true;
                yieldTimestamp = System.currentTimeMillis();
                if (yieldCallback != null) {
                    yieldCallback.onYieldCamera();
                }
            }

            @Override
            public void onCameraAvailable() {
                // Native app closed camera — reacquire if we had yielded
                logger.info("IBYDCameraUser: camera available — notifying pipeline");
                yielded = false;
                nativeAppActive = false;
                long yieldDuration = System.currentTimeMillis() - yieldTimestamp;
                logger.info("Camera was yielded for " + yieldDuration + "ms");

                if (yieldCallback != null) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(REACQUIRE_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }

                        if (!yielded && !nativeAppActive) {
                            yieldCallback.onReacquireCamera();
                        }
                    }, "CameraReacquire").start();
                }
            }

            @Override
            public void onNativeAppOpened(String packageName) {
                // Informational — native app opened camera but we're NOT yielding.
                // If sharing works (both get frames), recording continues uninterrupted.
                // If sharing fails, frame stall watchdog will detect it and call
                // onFrameStallDetected() → yieldDueToContention().
                logger.info("Native app opened camera: " + packageName +
                    " — monitoring for frame stalls (recording continues)");
                nativeAppActive = true;
            }
        });

        // Try registerUser via typed proxy
        if (typedServiceProxy != null) {
            try {
                boolean result = typedServiceProxy.registerUser(cameraUser);
                registeredAsUser = true;
                logger.info("Registered as camera user via typed AIDL (camera " +
                    activeCameraId + ", result=" + result + ") — event-driven yield active");
                return;
            } catch (Throwable e) {
                // Catches both Exception and Error (NoSuchMethodError when runtime
                // IBYDCameraService doesn't match our compile-time stub)
                logger.warn("registerUser via typed proxy failed: " + e.getMessage());
                typedServiceProxy = null;  // Don't try typed proxy again
            }
        }

        // Try registerUser via reflection
        if (reflectionServiceProxy != null) {
            try {
                Method registerMethod = reflectionServiceProxy.getClass()
                    .getDeclaredMethod("registerUser", IBYDCameraUser.class);
                registerMethod.setAccessible(true);
                registerMethod.invoke(reflectionServiceProxy, cameraUser);
                registeredAsUser = true;
                logger.info("Registered as camera user via reflection (camera " +
                    activeCameraId + ") — event-driven yield active");
                return;
            } catch (NoSuchMethodException e) {
                logger.warn("registerUser(IBYDCameraUser) not found on service");
            } catch (Throwable e) {
                logger.warn("registerUser via reflection failed: " + e.getMessage());
            }
        }

        // Try with android.os.IBinder parameter type (some firmware versions)
        if (reflectionServiceProxy != null) {
            try {
                Method registerMethod = reflectionServiceProxy.getClass()
                    .getDeclaredMethod("registerUser", android.os.IBinder.class);
                registerMethod.setAccessible(true);
                registerMethod.invoke(reflectionServiceProxy, cameraUser.asBinder());
                registeredAsUser = true;
                logger.info("Registered as camera user via IBinder overload (camera " +
                    activeCameraId + ") — event-driven yield active");
                return;
            } catch (NoSuchMethodException e) {
                // Expected if this overload doesn't exist
            } catch (Throwable e) {
                logger.warn("registerUser IBinder overload failed: " + e.getMessage());
            }
        }

        logger.warn("Camera user registration failed — using polling fallback");
    }

    /**
     * Unregisters from IBYDCameraService.
     */
    private void unregisterCameraUser() {
        if (!registeredAsUser || cameraUser == null) return;

        boolean unregistered = false;
        if (typedServiceProxy != null) {
            try {
                typedServiceProxy.unregisterUser(cameraUser);
                unregistered = true;
                logger.info("Unregistered camera user via typed AIDL");
            } catch (Exception e) {
                logger.warn("unregisterUser failed (service may still hold reference): " + e.getMessage());
            }
        } else if (reflectionServiceProxy != null) {
            try {
                Method unregisterMethod = reflectionServiceProxy.getClass()
                    .getDeclaredMethod("unregisterUser", IBYDCameraUser.class);
                unregisterMethod.setAccessible(true);
                unregisterMethod.invoke(reflectionServiceProxy, cameraUser);
                unregistered = true;
                logger.info("Unregistered camera user via reflection");
            } catch (Exception e) {
                logger.warn("unregisterUser via reflection failed: " + e.getMessage());
            }
        }

        if (!unregistered) {
            logger.warn("Could not confirm unregister — clearing local state to allow re-registration");
        }
        registeredAsUser = false;
        cameraUser.clearYielded();
    }

    // ==================== Yield State Query ====================

    /**
     * Checks if the camera is currently yielded due to contention.
     * Returns false if native app opened but sharing is working (no frame stall).
     */
    public boolean isCameraYielded() {
        if (registeredAsUser && cameraUser != null) {
            return cameraUser.isYielded();
        }
        return yielded;
    }

    /**
     * Whether we're using event-driven registration (true) or polling fallback (false).
     */
    public boolean isRegisteredAsUser() {
        return registeredAsUser;
    }

    // ==================== Polling Fallback ====================

    /**
     * Checks if another app currently holds the camera via getCurrentCameraUser() polling.
     * Only used when registerUser is not available.
     *
     * @return true if another camera user is active (native AVM app)
     */
    public boolean checkNativeAppActive() {
        if (!serviceAvailable || getCurrentCameraUserMethod == null) {
            return false;
        }

        // If registered as user, the callbacks handle this — no need to poll
        if (registeredAsUser) {
            return nativeAppActive;
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
     * With registerUser: checks if native app holds camera (via callback state).
     *   If yes → yield via yieldDueToContention (this is the ONLY yield trigger).
     *   If no → genuine HAL issue, not contention.
     *
     * Without registerUser: falls back to polling getCurrentCameraUser.
     */
    public boolean onFrameStallDetected() {
        if (registeredAsUser && cameraUser != null) {
            // With registerUser, we know if native app holds camera from onOpenCamera callback
            if (cameraUser.isNativeAppHoldingCamera()) {
                logger.warn("CONTENTION: Frame stall + native app holds camera — yielding now");
                cameraUser.yieldDueToContention();
                return true;
            } else {
                logger.warn("Frame stall but native app NOT holding camera — HAL issue");
                return false;
            }
        }

        // Polling fallback
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
                        handleCameraEvent(eventType);
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

    private void handleCameraEvent(int eventType) {
        if (eventType == -10086 || eventType == 8) {
            logger.error("CAMERA HAL ERROR: event=" + eventType);
            if (yieldCallback != null) {
                yieldCallback.onCameraError(eventType);
            }
        } else if (eventType != 0 && eventType != 1001) {
            logger.info("Camera event: " + eventType);
        }
    }

    // ==================== Proper Camera Cleanup ====================

    public static void closeCamera(Object cameraObj, int channelId) {
        if (cameraObj == null) return;

        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

            try {
                Method m = avmClass.getDeclaredMethod("disablePreviewCallback", int.class);
                m.setAccessible(true);
                m.invoke(cameraObj, channelId);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                logger.warn("disablePreviewCallback failed: " + e.getMessage());
            }

            try {
                Method m = avmClass.getDeclaredMethod("stopPreview");
                m.setAccessible(true);
                m.invoke(cameraObj);
            } catch (Exception e) {
                logger.warn("stopPreview failed: " + e.getMessage());
            }

            try {
                Method m = avmClass.getDeclaredMethod("close");
                m.setAccessible(true);
                m.invoke(cameraObj);
            } catch (Exception e) {
                logger.warn("close failed: " + e.getMessage());
            }

        } catch (ClassNotFoundException e) {
            logger.error("AVMCamera class not found");
        }
    }

    // ==================== Lifecycle ====================

    public void unregister() {
        unregisterCameraUser();
        serviceAvailable = false;
        typedServiceProxy = null;
        reflectionServiceProxy = null;
        getCurrentCameraUserMethod = null;
    }

    // ==================== State Queries ====================

    public boolean isNativeAppActive() { return nativeAppActive; }
    public boolean isYielded() { return yielded || (cameraUser != null && cameraUser.isYielded()); }
    public boolean isRegistered() { return serviceAvailable; }
    public boolean isEventCallbackActive() { return eventCallbackSet; }
    public void resetEventCallbackState() { eventCallbackSet = false; }

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

        logger.info("=== Discovery complete (registeredAsUser=" + registeredAsUser + ") ===");
    }
}
