package com.overdrive.app.surveillance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contract guarding against fixed, resolution-incompatible codec levels. */
public class HardwareEventRecorderGpuCodecNegotiationContractTest {

    @Test
    public void encoderUsesSelectedComponentCapabilitiesInsteadOfFixedLevels() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");

        assertTrue(source.contains("applyCodecCapabilityHints(encoder, finalFormat, finalCodecMimeType)"));
        assertTrue(source.contains("AvcLevelSelector.selectAdvertisedBaselineLevel"));
        assertTrue(source.contains("areSizeAndRateSupported(width, height, (double) fps)"));
        assertTrue(source.contains("HevcLevelSelector.selectAdvertisedMainTierLevel"));
        assertTrue(source.contains("omitting both profile and level hints"));
        assertFalse(source.contains("AVCLevel31"));
        assertFalse(source.contains("HEVCMainTierLevel4"));
    }

    @Test
    public void actualOutputFormatAndAvcSpsAreLogged() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");

        assertTrue(source.contains("logNegotiatedEncoderFormat(format)"));
        assertTrue(source.contains("Actual AVC SPS:"));
        assertTrue(source.contains("MediaFormat.KEY_PROFILE"));
        assertTrue(source.contains("MediaFormat.KEY_LEVEL"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
