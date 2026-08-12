package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StoragePeriodicMaintenancePolicyTest {

    @Test
    public void idleHealthyStorageSkipsThirtySecondMaintenance() {
        assertFalse(StorageManager.shouldRunPeriodicMaintenance(
                false, false, false, false));
    }

    @Test
    public void eachActionableReasonArmsMaintenance() {
        assertTrue(StorageManager.shouldRunPeriodicMaintenance(
                true, false, false, false));
        assertTrue(StorageManager.shouldRunPeriodicMaintenance(
                false, true, false, false));
        assertTrue(StorageManager.shouldRunPeriodicMaintenance(
                false, false, true, false));
        assertTrue(StorageManager.shouldRunPeriodicMaintenance(
                false, false, false, true));
    }

    @Test
    public void integrityPassIsHourlyAndTreatsMissingStampAsDue() {
        assertTrue(StorageManager.isPeriodicIntegrityDue(1L, 0L));
        assertFalse(StorageManager.isPeriodicIntegrityDue(3_600_000L, 1L));
        assertTrue(StorageManager.isPeriodicIntegrityDue(3_600_001L, 1L));
    }

    @Test
        public void deferredOrUnresolvedFallbackWorkStopsAfterLightweightPhase() {
        assertFalse(StorageManager.shouldRunFullPeriodicMaintenance(
            false, false, false));
        assertTrue(StorageManager.shouldRunFullPeriodicMaintenance(
            true, false, false));
        assertTrue(StorageManager.shouldRunFullPeriodicMaintenance(
            false, true, false));
        assertTrue(StorageManager.shouldRunFullPeriodicMaintenance(
            false, false, true));
        }

        @Test
        public void orphanSweepRequiresIntegrityOrEmergency() {
        assertFalse(StorageManager.shouldSweepOrphanTempFiles(false, false));
        assertTrue(StorageManager.shouldSweepOrphanTempFiles(true, false));
        assertTrue(StorageManager.shouldSweepOrphanTempFiles(false, true));
        }

        @Test
        public void schedulerUsesCompletionRelativeDelay() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");
        String start = methodBody(source, "public void startPeriodicCleanup()");

        assertTrue(start.contains("scheduleWithFixedDelay"));
        assertFalse(start.contains("scheduleAtFixedRate"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openingBrace, i + 1);
            }
        }
        throw new AssertionError("Unbalanced method body for " + signature);
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
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}