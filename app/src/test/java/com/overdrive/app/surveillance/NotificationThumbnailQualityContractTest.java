package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the native-resolution, high-quality JPEG contract shared by push and Telegram. */
public class NotificationThumbnailQualityContractTest {

    @Test
    public void heroPreservesNativeCropGeometryAndUsesHighJpegQuality() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/ThumbnailBuffer.java");

        assertTrue(source.contains("private static final int JPEG_QUALITY = 95;"));
        assertTrue(source.contains("Do not upscale small mosaic crops"));
        assertTrue(source.contains("float scaleX = 1f;"));
        assertTrue(source.contains("float scaleY = 1f;"));
        assertFalse(source.contains("Bitmap.createScaledBitmap(bmp"));
    }

    @Test
    public void fallbackNotificationFrameAlsoUsesHighJpegQuality() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");

        assertTrue(source.contains(
                "frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, fos);"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path path = Paths.get(relativePath);
        if (!Files.exists(path)) {
            path = Paths.get("..").resolve(relativePath);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
