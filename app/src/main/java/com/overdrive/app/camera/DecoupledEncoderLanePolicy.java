package com.overdrive.app.camera;

import org.json.JSONObject;

/**
 * Effective value of {@code camera.decoupledEncoderLane}.
 *
 * <p>An explicit setting always wins. When the user has never set it, BYD100F DiLink 100 units on
 * the AVMCamera path default to on: there the car's own camera view shares the camera service
 * with our recorder, and an encoder stall that holds the camera's buffers freezes the car's view
 * and its infotainment (#1001). Every other platform keeps the shipped default (off).
 */
public final class DecoupledEncoderLanePolicy {

    public static final String KEY = "decoupledEncoderLane";

    private DecoupledEncoderLanePolicy() {
    }

    /** {@code cam} is the {@code camera} config section, or null. */
    public static boolean resolve(JSONObject cam) {
        return resolve(cam, platformDefault());
    }

    static boolean resolve(JSONObject cam, boolean platformDefault) {
        if (cam != null && cam.has(KEY)) return cam.optBoolean(KEY, false);
        return platformDefault;
    }

    /** True when an unset flag means "on" on this head unit. */
    public static boolean platformDefault() {
        try {
            return DiLink100Platform.isAvmPathActive();
        } catch (Throwable t) {
            return false;
        }
    }
}
