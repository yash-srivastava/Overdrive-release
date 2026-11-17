package com.overdrive.app.camera;

import android.os.Build;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.util.Size;
import android.hardware.camera2.CameraAccessException;
import com.overdrive.app.daemon.CameraDaemon;
import org.json.JSONArray;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Passive, read-only camera service inventory. No camera is opened or probed.
 *
 * ServiceManager is a hidden API on some firmware. An inaccessible API is
 * reported as unknown, never as evidence that a camera service is absent.
 */
public final class CameraHardwareInventory {
    private static final String[] SERVICES = {
            "bmmcameraserver", "bydcameramanager", "media.camera", "media.camera.proxy"
    };

    private CameraHardwareInventory() {}

    public static JSONObject snapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("source", "android-runtime");
            result.put("androidSdk", Build.VERSION.SDK_INT);
            result.put("hardware", Build.HARDWARE);
            result.put("board", Build.BOARD);
            result.put("model", Build.MODEL);
            JSONObject services = new JSONObject();
            for (String name : SERVICES) {
                services.put(name, serviceStatus(name));
            }
            result.put("services", services);
            result.put("androidCamera2", camera2Inventory());
        } catch (JSONException ignored) {
            // JSONObject only receives primitive values and nested JSON here.
        }
        return result;
    }

    /**
     * Passive Camera2 enumeration. This does not open a camera, and Android
     * camera IDs must never be interpreted as BYD AVM/APA camera IDs or roles.
     * Permission and service failures are explicit, not an empty camera list.
     */
    private static JSONObject camera2Inventory() {
        JSONObject result = new JSONObject();
        try {
            Context context = CameraDaemon.getAppContext();
            if (context == null) {
                result.put("status", "context_unavailable");
                return result;
            }
            Object service = context.getSystemService(Context.CAMERA_SERVICE);
            if (!(service instanceof CameraManager)) {
                result.put("status", "service_unavailable");
                return result;
            }
            String[] ids = ((CameraManager) service).getCameraIdList();
            if (ids == null) {
                result.put("status", "unknown");
                return result;
            }
            JSONArray visibleIds = new JSONArray();
            // Bound diagnostic output even if a vendor camera service is broken.
            for (int i = 0; i < ids.length && i < 32; i++) {
                if (ids[i] != null) visibleIds.put(ids[i]);
            }
            result.put("status", "available");
            result.put("ids", visibleIds);
            result.put("reportedCount", ids.length);
            result.put("truncated", ids.length > 32);
            JSONObject capabilities = new JSONObject();
            CameraManager manager = (CameraManager) service;
            for (int i = 0; i < ids.length && i < 32; i++) {
                String id = ids[i];
                if (id == null) continue;
                capabilities.put(id, describeCamera(manager, id));
            }
            result.put("capabilities", capabilities);
        } catch (CameraAccessException | RuntimeException | LinkageError e) {
            try { result.put("status", "unknown"); } catch (JSONException ignored) {}
        } catch (JSONException ignored) {
            // Primitive JSON operations are expected to succeed.
        }
        return result;
    }

    /**
     * Read-only metadata lookup. No camera device or capture session is opened.
     * Values omitted by the HAL stay unknown; do not manufacture defaults.
     */
    private static JSONObject describeCamera(CameraManager manager, String id) {
        JSONObject result = new JSONObject();
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                String label = "unknown";
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) label = "front";
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) label = "back";
                else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) label = "external";
                result.put("lensFacing", label);
            }
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) result.put("sensorOrientationDegrees", orientation);
            Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (level != null) result.put("hardwareLevel", level);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null) {
                    JSONArray reported = new JSONArray();
                    for (int i = 0; i < sizes.length && i < 32; i++) {
                        if (sizes[i] == null) continue;
                        JSONObject size = new JSONObject();
                        size.put("width", sizes[i].getWidth());
                        size.put("height", sizes[i].getHeight());
                        reported.put(size);
                    }
                    result.put("jpegOutputSizes", reported);
                    result.put("jpegOutputSizeCount", sizes.length);
                    result.put("jpegOutputSizesTruncated", sizes.length > 32);
                }
            }
            result.put("status", "available");
        } catch (CameraAccessException | RuntimeException | LinkageError e) {
            try { result.put("status", "unknown"); } catch (JSONException ignored) {}
        } catch (JSONException ignored) {
            // JSON metadata construction is best effort.
        }
        return result;
    }

    private static String serviceStatus(String name) {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method check = manager.getDeclaredMethod("checkService", String.class);
            Object binder = check.invoke(null, name);
            return binder == null ? "not_registered" : "registered";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "unknown";
        }
    }
}
