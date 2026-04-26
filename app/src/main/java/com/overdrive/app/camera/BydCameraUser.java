package com.overdrive.app.camera;

import android.hardware.IBYDCameraUser;
import com.overdrive.app.logging.DaemonLogger;

/**
 * IBYDCameraUser implementation for cooperative camera sharing with BYD native apps.
 *
 * DESIGN: Don't proactively yield on onPreOpenCamera. The BYD camera HAL supports
 * multiple preview surfaces via addPreviewSurface, so both apps CAN share the camera.
 * We only yield if the native app actually can't get frames (detected via frame stall
 * + onOpenCamera notification).
 *
 * Registration with IBYDCameraService serves two purposes:
 * 1. The service knows we exist — it can coordinate camera access properly
 * 2. We get onCloseCamera callbacks — instant reacquire when native app exits
 *    (replaces blind delays and polling)
 *
 * Yield only happens when:
 * - onOpenCamera fires AND our frames stall (HAL can't serve both surfaces)
 * - The existing frame stall watchdog detects contention
 *
 * This maximizes recording uptime — no unnecessary gaps.
 */
public class BydCameraUser extends IBYDCameraUser.Stub {

    private static final String TAG = "BydCameraUser";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private final int cameraId;
    private final String packageName;
    private volatile boolean yielded = false;
    private volatile boolean nativeAppHoldsCamera = false;
    private volatile CameraYieldListener listener;

    /**
     * Callback interface for camera yield/reacquire events.
     */
    public interface CameraYieldListener {
        /** Called when we must yield the camera (frame stall + native app active). */
        void onYieldRequired();
        /** Called when the native app released the camera. Safe to reopen if yielded. */
        void onCameraAvailable();
        /** Called when native app opens camera (informational — don't yield yet). */
        void onNativeAppOpened(String packageName);
    }

    public BydCameraUser(int cameraId, String packageName) {
        this.cameraId = cameraId;
        this.packageName = packageName;
    }

    public void setListener(CameraYieldListener listener) {
        this.listener = listener;
    }

    /**
     * Checks if the requester is a different app requesting our camera.
     */
    private boolean isOtherAppOnMyCamera(IBYDCameraUser requester, int camId) {
        if (camId != this.cameraId) return false;
        try {
            String requesterPkg = requester.getPackageName();
            return requesterPkg != null && !packageName.equals(requesterPkg);
        } catch (Exception e) {
            return true;
        }
    }

    // ==================== IBYDCameraUser callbacks ====================

    /**
     * Called BEFORE another app opens the camera.
     *
     * We return true (allow) but do NOT yield. The HAL supports multiple preview
     * surfaces — both apps can share. We only yield later if frames actually stall.
     * This keeps our recording running without unnecessary gaps.
     */
    @Override
    public boolean onPreOpenCamera(IBYDCameraUser requester, int camId) {
        if (isOtherAppOnMyCamera(requester, camId)) {
            String requesterPkg = "unknown";
            try { requesterPkg = requester.getPackageName(); } catch (Exception ignored) {}
            logger.info("onPreOpenCamera: allowing " + requesterPkg +
                " to open camera " + camId + " (NOT yielding — sharing via addPreviewSurface)");
            nativeAppHoldsCamera = true;
        }
        return true;  // Always allow — don't block the native app
    }

    /**
     * Called AFTER another app has opened the camera.
     *
     * The native app now has the camera. We note this but don't yield yet.
     * If the HAL can serve both surfaces, our recording continues uninterrupted.
     * If it can't, our frame stall watchdog will detect it and trigger yield.
     */
    @Override
    public boolean onOpenCamera(IBYDCameraUser requester, int camId) {
        if (isOtherAppOnMyCamera(requester, camId)) {
            String requesterPkg = "unknown";
            try { requesterPkg = requester.getPackageName(); } catch (Exception ignored) {}
            logger.info("onOpenCamera: " + requesterPkg + " opened camera " + camId +
                " — monitoring for frame stalls (NOT yielding proactively)");
            nativeAppHoldsCamera = true;
            if (listener != null) {
                listener.onNativeAppOpened(requesterPkg);
            }
        }
        return false;
    }

    /**
     * Called when another app closes the camera.
     *
     * If we had yielded (due to frame stall), this is our signal to reopen.
     * If we never yielded (sharing worked), this is just informational.
     */
    @Override
    public boolean onCloseCamera(IBYDCameraUser requester, int camId) {
        if (isOtherAppOnMyCamera(requester, camId)) {
            String requesterPkg = "unknown";
            try { requesterPkg = requester.getPackageName(); } catch (Exception ignored) {}
            logger.info("onCloseCamera: " + requesterPkg + " released camera " + camId);
            nativeAppHoldsCamera = false;

            if (yielded) {
                logger.info("Was yielded — triggering camera reacquire");
                yielded = false;
                if (listener != null) {
                    listener.onCameraAvailable();
                }
            } else {
                logger.info("Was NOT yielded — sharing worked, recording continued uninterrupted");
            }
        }
        return false;
    }

    @Override
    public boolean onError(String error, int code) {
        logger.warn("onError: " + error + " code=" + code);
        return false;
    }

    @Override
    public int getCameraId() {
        return cameraId;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getProperty(String key) {
        if ("native".equals(key)) return "true";
        if ("camera_type".equals(key)) return "android";
        return null;
    }

    // ==================== Yield control ====================

    /**
     * Called by the frame stall watchdog when frames stop AND native app holds camera.
     * This is the ONLY path that triggers yield — not onPreOpenCamera.
     */
    public void yieldDueToContention() {
        if (!yielded && nativeAppHoldsCamera) {
            yielded = true;
            logger.info("Yielding due to contention (frame stall + native app active)");
            if (listener != null) {
                listener.onYieldRequired();
            }
        }
    }

    public boolean isYielded() {
        return yielded;
    }

    public boolean isNativeAppHoldingCamera() {
        return nativeAppHoldsCamera;
    }

    /**
     * Manually clear the yielded flag (e.g., on unregister or shutdown).
     */
    public void clearYielded() {
        yielded = false;
        nativeAppHoldsCamera = false;
    }
}
