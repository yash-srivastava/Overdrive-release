package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * SOTA: Contention-aware camera coordinator for BYD platform.
 *
 * DESIGN PHILOSOPHY: Never yield the camera preemptively. Only yield when we
 * detect ACTUAL contention — our pipeline causing the native app to lose signal.
 *
 * Detection methods:
 * 1. AVMCamera event callbacks (-10086, 8) — HAL error, immediate restart
 * 2. Frame stall (2s no frames) + native app active check = contention → yield
 * 3. Frame stall without native app = HAL issue → restart camera
 *
 * Native app detection: Poll IBYDCameraService.getCurrentCameraUser() to check
 * if another app has registered with the camera service. This is more reliable
 * than waiting for callbacks which may not fire for the native AVM app.
 *
 * Cleanup order (always): disablePreviewCallback → stopPreview → close
 */
public class BydCameraCoordinator {

    private static final String TAG = "BydCameraCoordinator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // IBYDCameraService
    private Object cameraServiceProxy;
    private Method getCurrentCameraUserMethod;
    private boolean serviceAvailable = false;

    // Event callback state
    private boolean eventCallbackSet = false;

    // Native app activity tracking
    private volatile boolean nativeAppActive = false;

    // Yield state
    private volatile boolean yielded = false;
    private volatile long yieldTimestamp = 0;
    private static final long REACQUIRE_DELAY_MS = 500;

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

    // ==================== IBYDCameraService Setup ====================

    /**
     * Connects to IBYDCameraService for getCurrentCameraUser() polling.
     * We don't register as a user — we just query who else is using the camera.
     */
    public void register() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, "bydcameramanager");

            if (binder == null) {
                logger.warn("IBYDCameraService not available");
                return;
            }

            Class<?> stubClass = Class.forName("android.hardware.IBYDCameraService$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface",
                Class.forName("android.os.IBinder"));
            asInterface.setAccessible(true);
            cameraServiceProxy = asInterface.invoke(null, binder);

            if (cameraServiceProxy == null) {
                logger.warn("IBYDCameraService.asInterface returned null");
                return;
            }

            // Cache getCurrentCameraUser method for polling
            try {
                getCurrentCameraUserMethod = cameraServiceProxy.getClass()
                    .getDeclaredMethod("getCurrentCameraUser");
                getCurrentCameraUserMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                logger.warn("getCurrentCameraUser not found on service");
            }

            serviceAvailable = true;
            logger.info("IBYDCameraService connected — native app detection via polling");

        } catch (ClassNotFoundException e) {
            logger.info("IBYDCameraService not found — native app detection unavailable");
        } catch (Exception e) {
            logger.warn("IBYDCameraService setup failed: " + e.getMessage());
        }
    }

    /**
     * Checks if another app currently holds the camera via IBYDCameraService.
     * Called by the watchdog thread periodically and during frame stall detection.
     *
     * @return true if another camera user is active (native AVM app)
     */
    public boolean checkNativeAppActive() {
        if (!serviceAvailable || getCurrentCameraUserMethod == null) {
            return false;
        }

        try {
            Object currentUser = getCurrentCameraUserMethod.invoke(cameraServiceProxy);
            if (currentUser != null) {
                // Someone else has the camera — check if it's us or another app
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
            // Don't spam logs — polling errors are expected occasionally
            return nativeAppActive;  // Keep last known state
        }
    }

    // ==================== Native App State ====================

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

    // ==================== Contention-Triggered Yield ====================

    /**
     * Called by the frame stall detector when no frames arrive for 2+ seconds.
     * Checks native app status first, then decides: yield or restart.
     */
    public boolean onFrameStallDetected() {
        // Fresh check — don't rely on stale polling data
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
        serviceAvailable = false;
        cameraServiceProxy = null;
        getCurrentCameraUserMethod = null;
    }

    // ==================== State Queries ====================

    public boolean isNativeAppActive() { return nativeAppActive; }
    public boolean isYielded() { return yielded; }
    public boolean isRegistered() { return serviceAvailable; }
    public boolean isEventCallbackActive() { return eventCallbackSet; }
    public void resetEventCallbackState() { eventCallbackSet = false; }
}
