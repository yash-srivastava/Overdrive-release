package com.overdrive.app.camera;

import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Binder-based camera backend that talks to bydcameramanager/bmmcameraserver.
 * 
 * Unlike the direct AVMCamera approach, this goes through BYD's camera service
 * which manages camera sharing between apps. This prevents glitching native
 * camera apps (reverse camera, dashcam, AVM parking view) because the service
 * knows about our session and can arbitrate buffer queues properly.
 * 
 * Transaction codes from BYD AutoCommander decompilation:
 *   1  = openCamera(cameraId)
 *   2  = closeCamera()
 *   3  = startPreview()
 *   4  = stopPreview()
 *   5  = setPreviewSurface(Surface)
 *   6  = setPreviewTarget(Surface)
 *   7  = setDisplayOrientation(degrees)
 *   8  = setPreviewSize(width, height)
 *   9  = registerCallback(IBinder)
 *   10 = unregisterCallback
 *   11 = setPreviewTexture
 *   12 = connect()
 *   13 = disconnect()
 *   14 = enableRendering
 *   15 = setFrameRate(fps)
 * 
 * Camera IDs (from BYDCameraServiceBinder):
 *   0 = FRONT, 2 = REAR, 4 = LEFT, 5 = RIGHT, 6 = PANORAMA_LEFT, 7 = PANORAMA_RIGHT
 * 
 * Camera IDs (from BYDAVMCameraAccess — may differ per model):
 *   0 = FRONT, 1 = REAR, 2 = LEFT, 3 = RIGHT, 4 = PANORAMA_LEFT, 5 = PANORAMA_RIGHT
 */
public class BinderCameraBackend {

    private static final String TAG = "BinderCameraBackend";
    private final DaemonLogger logger;

    // Service names (try primary, then fallback)
    private static final String SERVICE_PRIMARY = "bydcameramanager";
    private static final String SERVICE_FALLBACK = "bmmcameraserver";

    // Transaction codes
    private static final int TX_OPEN_CAMERA = 1;
    private static final int TX_CLOSE_CAMERA = 2;
    private static final int TX_START_PREVIEW = 3;
    private static final int TX_STOP_PREVIEW = 4;
    private static final int TX_SET_PREVIEW_SURFACE = 5;
    private static final int TX_SET_PREVIEW_TARGET = 6;
    private static final int TX_SET_DISPLAY_ORIENTATION = 7;
    private static final int TX_SET_PREVIEW_SIZE = 8;
    private static final int TX_REGISTER_CALLBACK = 9;
    private static final int TX_UNREGISTER_CALLBACK = 10;
    private static final int TX_SET_PREVIEW_TEXTURE = 11;
    private static final int TX_CONNECT = 12;
    private static final int TX_DISCONNECT = 13;
    private static final int TX_ENABLE_RENDERING = 14;
    private static final int TX_SET_FRAME_RATE = 15;

    // State
    private IBinder cameraService;
    private String interfaceDescriptor;
    private boolean connected = false;
    private boolean cameraOpen = false;
    private boolean previewStarted = false;
    private int currentCameraId = -1;
    private String serviceName;

    public BinderCameraBackend() {
        this.logger = DaemonLogger.getInstance(TAG, null);
    }

    // ==================== Connection ====================

    /**
     * Connect to the BYD camera service.
     * Tries bydcameramanager first, falls back to bmmcameraserver.
     * 
     * @return true if connected successfully
     */
    public boolean connect() {
        if (connected && cameraService != null) {
            return true;
        }

        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getMethod("getService", String.class);

            // Try primary service
            logger.info("Connecting to " + SERVICE_PRIMARY + "...");
            Object binder = getService.invoke(null, SERVICE_PRIMARY);
            if (binder instanceof IBinder) {
                cameraService = (IBinder) binder;
                serviceName = SERVICE_PRIMARY;
            }

            // Fallback to secondary
            if (cameraService == null) {
                logger.info(SERVICE_PRIMARY + " not found, trying " + SERVICE_FALLBACK + "...");
                binder = getService.invoke(null, SERVICE_FALLBACK);
                if (binder instanceof IBinder) {
                    cameraService = (IBinder) binder;
                    serviceName = SERVICE_FALLBACK;
                }
            }

            if (cameraService == null) {
                logger.error("No camera service found");
                return false;
            }

            interfaceDescriptor = cameraService.getInterfaceDescriptor();
            logger.info("Connected to " + serviceName + " (interface: " + interfaceDescriptor + ")");

            // Send connect transaction
            boolean connectResult = transactNoData(TX_CONNECT);
            logger.info("Connect transaction: " + (connectResult ? "OK" : "failed (non-fatal)"));

            connected = true;
            return true;

        } catch (Exception e) {
            logger.error("Failed to connect to camera service: " + e.getMessage());
            return false;
        }
    }

    /**
     * Disconnect from the camera service.
     */
    public void disconnect() {
        if (!connected) return;

        try {
            if (previewStarted) stopPreview();
            if (cameraOpen) closeCamera();
            transactNoData(TX_DISCONNECT);
            logger.info("Disconnected from " + serviceName);
        } catch (Exception e) {
            logger.warn("Error during disconnect: " + e.getMessage());
        } finally {
            connected = false;
            cameraService = null;
            interfaceDescriptor = null;
        }
    }

    // ==================== Camera Operations ====================

    /**
     * Open a camera by ID.
     * 
     * @param cameraId Camera ID (0=front, 1/2=rear, 4/6=pano_left, 5/7=pano_right depending on model)
     * @return true if opened successfully
     */
    public boolean openCamera(int cameraId) {
        if (!ensureConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                data.writeInt(cameraId);

                boolean result = cameraService.transact(TX_OPEN_CAMERA, data, reply, 0);
                if (result) {
                    reply.readException();
                    int returnCode = -1;
                    try { returnCode = reply.readInt(); } catch (Exception ignored) {}
                    logger.info("Camera " + cameraId + " opened (returnCode=" + returnCode + ")");
                    cameraOpen = true;
                    currentCameraId = cameraId;
                    return true;
                } else {
                    logger.error("openCamera transact returned false");
                    return false;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.error("openCamera failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Close the current camera.
     */
    public boolean closeCamera() {
        if (!connected || !cameraOpen) return true;

        try {
            if (previewStarted) stopPreview();
            boolean result = transactNoData(TX_CLOSE_CAMERA);
            logger.info("Camera closed: " + result);
            cameraOpen = false;
            currentCameraId = -1;
            return result;
        } catch (Exception e) {
            logger.error("closeCamera failed: " + e.getMessage());
            cameraOpen = false;
            return false;
        }
    }

    /**
     * Set the preview surface. Frames will be delivered to this surface.
     * Use this with your SurfaceTexture-backed Surface for GPU pipeline integration.
     * 
     * @param surface The Surface to receive camera frames
     * @return true if set successfully
     */
    public boolean setPreviewSurface(Surface surface) {
        if (!ensureConnected() || !cameraOpen) return false;

        // Try TX_SET_PREVIEW_SURFACE first, then TX_SET_PREVIEW_TARGET as fallback
        boolean result = setPreviewSurfaceInternal(TX_SET_PREVIEW_SURFACE, surface);
        if (!result) {
            logger.info("setPreviewSurface (TX 5) failed, trying setPreviewTarget (TX 6)...");
            result = setPreviewSurfaceInternal(TX_SET_PREVIEW_TARGET, surface);
        }
        return result;
    }

    private boolean setPreviewSurfaceInternal(int txCode, Surface surface) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                surface.writeToParcel(data, 0);

                boolean result = cameraService.transact(txCode, data, reply, 0);
                if (result) {
                    reply.readException();
                    logger.info("Preview surface set via TX " + txCode);
                    return true;
                }
                return false;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("setPreviewSurface TX " + txCode + " failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the preview size.
     * 
     * @param width Desired width
     * @param height Desired height
     * @return true if set successfully
     */
    public boolean setPreviewSize(int width, int height) {
        if (!ensureConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                data.writeInt(width);
                data.writeInt(height);

                boolean result = cameraService.transact(TX_SET_PREVIEW_SIZE, data, reply, 0);
                if (result) {
                    reply.readException();
                    logger.info("Preview size set: " + width + "x" + height);
                }
                return result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("setPreviewSize failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the camera frame rate.
     * This tells the camera HAL how fast to deliver frames.
     * 
     * @param fps Desired frames per second
     * @return true if set successfully
     */
    public boolean setFrameRate(int fps) {
        if (!ensureConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                data.writeInt(fps);

                boolean result = cameraService.transact(TX_SET_FRAME_RATE, data, reply, 0);
                if (result) {
                    reply.readException();
                    logger.info("Frame rate set: " + fps + " FPS");
                }
                return result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("setFrameRate failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set display orientation.
     * 
     * @param degrees Rotation in degrees (0, 90, 180, 270)
     * @return true if set successfully
     */
    public boolean setDisplayOrientation(int degrees) {
        if (!ensureConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                data.writeInt(degrees);

                boolean result = cameraService.transact(TX_SET_DISPLAY_ORIENTATION, data, reply, 0);
                if (result) {
                    reply.readException();
                    logger.info("Display orientation set: " + degrees + "°");
                }
                return result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("setDisplayOrientation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enable or disable rendering.
     * 
     * @return true if set successfully
     */
    public boolean enableRendering() {
        if (!ensureConnected()) return false;
        return transactNoData(TX_ENABLE_RENDERING);
    }

    /**
     * Start preview. Frames will begin arriving on the configured surface.
     * 
     * @return true if started successfully
     */
    public boolean startPreview() {
        if (!ensureConnected() || !cameraOpen) return false;

        try {
            boolean result = transactNoData(TX_START_PREVIEW);
            if (result) {
                previewStarted = true;
                logger.info("Preview started");
            }
            return result;
        } catch (Exception e) {
            logger.error("startPreview failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop preview.
     * 
     * @return true if stopped successfully
     */
    public boolean stopPreview() {
        if (!connected) return true;

        try {
            boolean result = transactNoData(TX_STOP_PREVIEW);
            previewStarted = false;
            logger.info("Preview stopped: " + result);
            return result;
        } catch (Exception e) {
            logger.warn("stopPreview failed: " + e.getMessage());
            previewStarted = false;
            return false;
        }
    }

    /**
     * Register a callback binder for camera events (frame available, error, open/close).
     * 
     * @param callbackBinder The IBinder for the callback stub
     * @return true if registered successfully
     */
    public boolean registerCallback(IBinder callbackBinder) {
        if (!ensureConnected()) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                data.writeStrongBinder(callbackBinder);

                boolean result = cameraService.transact(TX_REGISTER_CALLBACK, data, reply, 0);
                if (result) {
                    reply.readException();
                    logger.info("Callback registered");
                }
                return result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("registerCallback failed: " + e.getMessage());
            return false;
        }
    }

    // ==================== Full Camera Start (convenience) ====================

    /**
     * Full camera startup sequence matching the commander pattern.
     * Opens camera, sets surface, configures size/fps, starts preview.
     * 
     * @param cameraId Camera ID to open
     * @param surface Surface to receive frames (from SurfaceTexture)
     * @param width Desired preview width (0 to skip)
     * @param height Desired preview height (0 to skip)
     * @param fps Desired frame rate (0 to skip)
     * @return true if all steps succeeded
     */
    public boolean startCamera(int cameraId, Surface surface, int width, int height, int fps) {
        logger.info("Starting camera via binder: id=" + cameraId + 
            ", size=" + width + "x" + height + ", fps=" + fps);

        if (!connect()) {
            logger.error("Failed to connect to camera service");
            return false;
        }

        if (!openCamera(cameraId)) {
            logger.error("Failed to open camera " + cameraId);
            return false;
        }

        if (!setPreviewSurface(surface)) {
            logger.error("Failed to set preview surface");
            closeCamera();
            return false;
        }

        if (width > 0 && height > 0) {
            setPreviewSize(width, height);  // Non-fatal if fails
        }

        if (fps > 0) {
            setFrameRate(fps);  // Non-fatal if fails
        }

        if (!startPreview()) {
            logger.error("Failed to start preview");
            closeCamera();
            return false;
        }

        logger.info("Camera started successfully via binder path");
        return true;
    }

    /**
     * Full camera stop sequence.
     */
    public void stopCamera() {
        logger.info("Stopping camera via binder");
        stopPreview();
        closeCamera();
        // Don't disconnect — keep the service connection alive for quick restart
    }

    // ==================== State Queries ====================

    public boolean isConnected() { return connected && cameraService != null; }
    public boolean isCameraOpen() { return cameraOpen; }
    public boolean isPreviewStarted() { return previewStarted; }
    public int getCurrentCameraId() { return currentCameraId; }
    public String getServiceName() { return serviceName; }

    // ==================== Internal Helpers ====================

    private boolean ensureConnected() {
        if (connected && cameraService != null) return true;
        return connect();
    }

    /**
     * Send a transaction with no data payload (just interface token).
     */
    private boolean transactNoData(int txCode) {
        if (cameraService == null || interfaceDescriptor == null) return false;

        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(interfaceDescriptor);
                boolean result = cameraService.transact(txCode, data, reply, 0);
                if (result) {
                    reply.readException();
                }
                return result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            logger.warn("transact TX " + txCode + " failed: " + e.getMessage());
            return false;
        }
    }
}
