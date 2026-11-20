package com.overdrive.app.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards ScratchRoot sentinel cleanup syntax in AppUpdater and UpdateLifecycle. */
public class UpdaterDisabledSentinelContractTest {

    @Test
    public void cleanupLoopsKeepSemicolonOutsideScratchRootPath() throws IOException {
        String updater = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String lifecycle = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");

        assertFalse(updater.contains("ScratchRoot.path(\"acc_sentry_daemon.disabled;\")"));
        assertFalse(lifecycle.contains("ScratchRoot.path(\"acc_sentry_daemon.disabled;\")"));
        assertTrue(updater.contains(
                "ScratchRoot.path(\"acc_sentry_daemon.disabled\") + \"; do \""));
        assertTrue(lifecycle.contains(
                "ScratchRoot.path(\"acc_sentry_daemon.disabled\") + \"; do\\n\""));
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
