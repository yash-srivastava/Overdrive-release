package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RecordingsReconcileOwnershipTest {

    @Test
    public void asynchronousRepairSourcesUseCentralRequestOwner() throws IOException {
        String index = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsIndex.java");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java");
        String storage = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");

        assertTrue(index.contains("CoalescingTaskRunner reconcileRequests"));
        assertFalse(index.contains("reconcileAfterReconnectRunning"));
        assertFalse(api.contains("RECONCILE_IN_FLIGHT"));
        assertTrue(methodBody(api, "private static boolean kickBackgroundReconcile")
            .contains("return idx.requestReconcile"));
        assertFalse(methodBody(storage, "private void notifyRecordingsIndexOfStorageChange")
                .contains("new Thread"));
        assertTrue(methodBody(storage, "private void notifyRecordingsIndexOfStorageChange")
                .contains("requestReconcile"));
    }

    @Test
    public void directPassesAreSerializedSeparatelyFromDatabaseMonitor() throws IOException {
        String index = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsIndex.java");
        String reconcile = methodBody(index, "public void reconcile()");

        assertTrue(reconcile.contains("synchronized (reconcileExecutionLock)"));
        assertTrue(reconcile.contains("reconcileInternal()"));
    }

    @Test
    public void storageHandlersDelegateIndexMaintenanceToStorageManager() throws IOException {
        assertNoHandlerOwnedReconcile(
                "app/src/main/java/com/overdrive/app/server/QualitySettingsApiHandler.java");
        assertNoHandlerOwnedReconcile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        assertNoHandlerOwnedReconcile(
                "app/src/main/java/com/overdrive/app/server/TcpCommandServer.java");
    }

    private static void assertNoHandlerOwnedReconcile(String relativePath) throws IOException {
        String source = readRepositoryFile(relativePath);
        assertFalse(source.contains("RecordingsIndexFileWatcher.getInstance().refresh()"));
        assertFalse(source.contains("RecordingsIndex.getInstance().reconcile()"));
        assertFalse(source.contains("RecordingsIndexStorageSwitchReconcile"));
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