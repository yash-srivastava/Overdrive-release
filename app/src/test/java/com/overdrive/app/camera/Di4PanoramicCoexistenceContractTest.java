package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Structural guard for continuous DI4 pano recording. */
public class Di4PanoramicCoexistenceContractTest {

    @Test
    public void di4DoesNotRegisterOrReleaseForTheNativeCameraUi()
            throws IOException {
        String gpu = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "PanoramicCameraGpu.java");
        String coordinator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "BydCameraCoordinator.java");

        assertOrdered(gpu,
                "if (!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH) {",
                "cameraCoordinator.register();",
                "} else if (USE_DILINK4_AVM_PATH) {",
                "skipping IBYDCameraService registration",
                "continuous panoramic co-consumer");
        assertTrue(gpu.contains(
                "Ignoring camera-yield callback on DiLink 4"));
        assertTrue(gpu.contains(
                "keeping the single AVMCamera"));

        assertFalse(gpu.contains("enableDiLink4Arbitration"));
        assertFalse(gpu.contains("yieldDiLink4CameraForNativeApp"));
        assertFalse(gpu.contains("diLink4NativeSourceOnlyYield"));
        assertFalse(gpu.contains("DILINK4_NATIVE_HANDOFF_TIMEOUT_MS"));
        assertFalse(coordinator.contains("registerUser("));
        assertFalse(gpu.contains("registerUser("));
        assertFalse(coordinator.contains("new BydCameraUser("));
        assertFalse(gpu.contains("new BydCameraUser("));
        assertFalse(coordinator.contains("proactiveYield"));
        assertFalse(coordinator.contains("requestProactiveYield"));
        assertFalse(gpu.contains("proactiveYield"));
        assertFalse(gpu.contains("requestProactiveYield"));
    }

    @Test
    public void di4SelectionMatchesApaTagAndPreviewOrder()
            throws IOException {
        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "AvmCameraHelper.java");
        String resolver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "CameraConfigResolver.java");

        assertOrdered(helper,
                "{\"pano_h\", \"pano_l\"}",
                "{\"apa\", \"byd_apa\"}",
                "{\"pano_h\", \"pano_l\", \"byd_apa\", \"apa\"}",
                "new PanoCameraSelection(id, 0, tag)",
                "new PanoCameraSelection(id, 1, tag)");
        assertOrdered(helper,
                "public static int discoverPanoCameraId()",
                "for (String tag : LEGACY_PANO_TAGS)");
        assertOrdered(resolver,
                "if (dilink4) {",
                "discoverDi4PanoCameraSelection()",
                "panoCameraId = halSelection.getCameraId();",
                "panoSurfaceMode = halSelection.getPreviewIndex();");
        assertFalse(resolver.contains("pinsProfileCamera"));
        assertFalse(resolver.contains("repairPinnedDi4ProfileConfig"));
    }

    @Test
    public void samePhysicalIdCannotBeOpenedAsASecondDi4Camera()
            throws IOException {
        String gpu = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "PanoramicCameraGpu.java");
        String policy = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "Di4CameraSafetyPolicy.java");

        assertOrdered(gpu,
                "if (USE_DILINK4_AVM_PATH) {",
                "Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(",
                "DiLink 4 windshield open blocked");
        assertTrue(policy.contains(
                "primaryCameraId != secondaryCameraId"));
        assertTrue(policy.contains(
                "concurrentAvmSupported == 1"));
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("missing or out of order: " + needle, next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
