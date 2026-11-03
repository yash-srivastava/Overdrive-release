package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Ensures a cloud-account lifecycle cannot leave a prior remote HVAC session visible. */
public class BydCloudApiHandlerContractTest {

    @Test
    public void credentialReplacementAndClearResetRemoteClimateState() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java");

        int setupSave = source.indexOf("BydCloudConfig.saveCredentials(username");
        int clear = source.indexOf("private static void handleClear");
        assertTrue(setupSave >= 0);
        assertTrue(clear >= 0);
        assertTrue(source.substring(0, setupSave).contains(
                "VehicleCommandRouter.getInstance().clearRemoteClimateSession()"));
        assertTrue(source.substring(clear).contains(
                "VehicleCommandRouter.getInstance().clearRemoteClimateSession()"));
    }

    @Test
    public void cloudMergeWriteReportsPersistenceFailure() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java");

        int settings = source.indexOf("private static void handleSettings");
        int setup = source.indexOf("private static void handleSetup", settings);
        assertTrue(settings >= 0);
        assertTrue(setup > settings);
        String body = source.substring(settings, setup);
        assertTrue(body.contains(
                "boolean persisted = UnifiedConfigManager.updateSection(\"bydCloud\", delta);"));
        assertTrue(body.contains("JSONObject delta = new JSONObject();"));
        assertTrue(body.contains("if (!persisted)"));
        assertTrue(body.contains("Messages.get(\"errors.bydcloud_settings_save_failed\")"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
