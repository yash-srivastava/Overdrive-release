package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class DecoupledEncoderLanePolicyTest {

    @Test
    public void unsetFlagFollowsThePlatformDefault() {
        assertTrue(DecoupledEncoderLanePolicy.resolve(new JSONObject(), true));
        assertFalse(DecoupledEncoderLanePolicy.resolve(new JSONObject(), false));
        assertTrue(DecoupledEncoderLanePolicy.resolve(null, true));
        assertFalse(DecoupledEncoderLanePolicy.resolve(null, false));
    }

    @Test
    public void explicitFalseWinsOverAnOnDefault() throws Exception {
        JSONObject cam = new JSONObject().put(DecoupledEncoderLanePolicy.KEY, false);
        assertFalse(DecoupledEncoderLanePolicy.resolve(cam, true));
    }

    @Test
    public void explicitTrueWinsOverAnOffDefault() throws Exception {
        JSONObject cam = new JSONObject().put(DecoupledEncoderLanePolicy.KEY, true);
        assertTrue(DecoupledEncoderLanePolicy.resolve(cam, false));
    }

    @Test
    public void everyReaderUsesThePolicy() throws Exception {
        String gpu = new String(java.nio.file.Files.readAllBytes(repo(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java")), "UTF-8");
        String ui = new String(java.nio.file.Files.readAllBytes(repo(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt")), "UTF-8");
        assertFalse(gpu.contains("optBoolean(\"decoupledEncoderLane\""));
        assertFalse(ui.contains("\"decoupledEncoderLane\", false"));
        assertTrue(ui.contains("DecoupledEncoderLanePolicy.resolve(cameraSection)"));
    }

    private static java.nio.file.Path repo(String path) {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (java.nio.file.Files.exists(p)) return p;
        return java.nio.file.Paths.get("..").resolve(path);
    }
}
