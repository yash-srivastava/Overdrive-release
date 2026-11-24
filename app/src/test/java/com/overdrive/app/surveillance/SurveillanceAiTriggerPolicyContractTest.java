package com.overdrive.app.surveillance;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class SurveillanceAiTriggerPolicyContractTest {

    @Test
    public void aiRequiresCurrentNonBaselineEvidenceAndProximityUsesIt()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");

        int salienceOverride = source.indexOf("Motion-salience override:");
        int finalGate = source.indexOf(
                "if (aiAvailable && !aiRecentlyConfirmed && !salienceExempts)");
        int triggerDecision = source.indexOf("if (shouldSuppress)", finalGate);
        assertTrue(salienceOverride >= 0);
        assertTrue(finalGate > salienceOverride);
        assertTrue(finalGate >= 0);
        assertTrue(triggerDecision > finalGate);
        // The final gate has exactly one exemption: the opt-in salience channel,
        // carried by salienceExempts with all of its brakes. No unconditional
        // form of the gate may remain, and the timeout/proximity overrides must
        // still not be able to bypass it.
        assertTrue(source.indexOf("if (aiAvailable && !aiRecentlyConfirmed) {") < 0);
        assertTrue(source.contains("boolean salienceExempts = salienceMayTrigger()\n"
                + "                            && !brightnessEventSalienceScope\n"
                + "                            && !deterrentActive\n"
                + "                            && salientQuadrantStillCoherent(results);"));
        assertTrue(source.contains("triggerSpaceDets.add(baselineSpaceDets != null"));
        assertTrue(source.contains("if (triggerEvidenceFound)"));
        assertTrue(source.contains(
                "lastAiConfirmationElapsedMs = detectionObservationElapsedMs"));
        assertTrue(source.contains(
                "lastAiConfirmationElapsedMs >= firstMotionElapsedMs"));
        assertTrue(source.contains("if (baselineSpaceDets != null) {\n"
                + "                                    triggerSpaceDets.add"));
        assertTrue(source.contains(
                "DistanceEstimator.fromYoloDetections(\n"
                + "                                    pub.triggerDetections"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
