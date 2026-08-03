package com.overdrive.app.abrp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/** Prevents a UI/API/integration from bypassing the canonical SOH resolver again. */
public class CanonicalSohConsumerAssetTest {

    @Test
    public void noConsumerPublishesRawMovingEstimate() throws Exception {
        Path mainRoot = findRepositoryFile("app/src/main/java");
        List<String> bypasses = new ArrayList<>();

        try (Stream<Path> files = Files.walk(mainRoot)) {
            files.filter(path -> path.toString().endsWith(".java")
                            || path.toString().endsWith(".kt"))
                    .filter(path -> !path.getFileName().toString().equals("SohEstimator.java"))
                    .forEach(path -> {
                        try {
                            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            if (source.contains(".getCurrentSoh()")
                                    || source.contains("getProperty(\"soh_percent\")")) {
                                bypasses.add(mainRoot.relativize(path).toString());
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertTrue("Raw SOH estimate bypasses: " + bypasses, bypasses.isEmpty());
    }

    @Test
    public void statusAndMajorExportsUseCanonicalValue() throws Exception {
        String estimator = read("app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");
        String abrp = read("app/src/main/java/com/overdrive/app/abrp/AbrpTelemetryService.java");
        String mqtt = read("app/src/main/java/com/overdrive/app/mqtt/MqttConnectionManager.java");
        String history = read("app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
        String http = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String automations = read("app/src/main/java/com/overdrive/app/automation/condition/BydEvent.java");
        String mainActivity = read("app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String diagnostics = read("app/src/main/java/com/overdrive/app/ui/fragment/DiagnosticsFragment.kt");
        String dashboard = read("app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String performance = read("app/src/main/assets/web/shared/performance.js");

        assertTrue(estimator.contains("status.put(\"soh\", resolvedSoh.percent > 0"));
        assertTrue(estimator.contains("status.put(\"displaySoh\", resolvedSoh.percent > 0"));
        assertTrue(abrp.contains("sohEstimator.getDisplaySoh()"));
        assertTrue(mqtt.contains("sohEstimator.getDisplaySoh()"));
        assertTrue(history.contains("sohEst.getDisplaySoh()"));
        assertTrue(http.contains("sohEst.getDisplaySoh()"));
        assertTrue(automations.contains("estimator.getDisplaySoh()"));
        assertTrue(mainActivity.contains("json.optDouble(\"displaySoh\", -1.0)"));
        assertTrue(diagnostics.contains("json.optDouble(\"displaySoh\", -1.0)"));
        assertTrue(dashboard.contains("finalDisplaySource == \"oem\""));
        assertTrue(performance.contains("!nominalSet && displaySource !== 'oem'"));
        assertFalse(estimator.contains("preferOem = phev"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(findRepositoryFile(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path findRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
