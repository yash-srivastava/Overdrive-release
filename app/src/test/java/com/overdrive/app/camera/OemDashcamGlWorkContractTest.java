package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level guards for optional OEM overlay setup and the encoder hot path. */
public class OemDashcamGlWorkContractTest {

    @Test
    public void disabledOverlayDoesNotCompileOrAllocateDuringCameraStartup()
            throws IOException {
        String source = readSource();
        String init = between(source, "private void initEglAndEncoder() throws Exception {",
                "private void initOverlayResourcesOnGlThread() {");
        assertTrue(init.contains("if (overlayEnabled) {\n"
                + "                    initOverlayResourcesOnGlThread();"));
        assertFalse(init.contains("createProgram(VS_OVERLAY, FS_OVERLAY)"));
        assertFalse(init.contains("new OverlayBitmapRenderer()"));

        String resources = between(source,
                "private void initOverlayResourcesOnGlThread() {",
                "/** GLES external-OES texture target.");
        assertTrue(resources.contains("if (overlayGlInitAttempted) return;"));
        assertTrue(resources.contains("createProgram(VS_OVERLAY, FS_OVERLAY)"));
        assertTrue(resources.contains("new OverlayBitmapRenderer()"));
        assertTrue(resources.contains("reconcileTelemetryHold();"));
        assertTrue(source.contains("overlayGlInitAttempted = false;"));
    }

    @Test
    public void renderLoopLazilyEnablesOverlayWithoutClearingEveryFrame()
            throws IOException {
        String source = readSource();
        String draw = between(source, "private void drawPassthrough() {",
                "/** Surveillance-owned clips are named");
        assertTrue(draw.contains("if (overlayEnabled && recording.get() && telemetryCollector != null"));
        assertTrue(draw.contains("&& !overlayGlInitAttempted) {"));
        assertTrue(draw.contains("initOverlayResourcesOnGlThread();"));

        // A full-screen opaque camera draw needs no clear, while the shader
        // failure fallback must keep the existing black-frame behavior.
        assertTrue(source.contains("-1f, -1f,  1f, -1f,  -1f,  1f,  1f,  1f"));
        String unavailable = "if (passthroughProgram == 0) {\n"
                + "            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);\n"
                + "            return;\n"
                + "        }";
        assertTrue(draw.contains(unavailable));
        assertFalse(draw.substring(draw.indexOf(unavailable) + unavailable.length())
                .contains("GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing section: " + start, from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String readSource() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(
                    "app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java");
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("Cannot locate OemDashcamPipeline.java");
    }
}
