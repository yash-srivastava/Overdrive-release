package com.overdrive.app.camera;

import android.os.Build;

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
        } catch (JSONException ignored) {
            // JSONObject only receives primitive values and nested JSON here.
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
