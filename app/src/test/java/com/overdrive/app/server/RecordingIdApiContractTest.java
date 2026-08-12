package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RecordingIdApiContractTest {

    @Test
    public void exactIdRoutesPrecedeLegacyFilenameRoutes() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java");
        String handle = methodBody(source, "public static boolean handle(");
        String range = methodBody(source, "public static boolean handleWithRange(");

        assertBefore(handle, "path.startsWith(\"/thumb/id/\")",
                "path.startsWith(\"/thumb/\")");
        assertBefore(handle, "path.startsWith(\"/video/id/\")",
                "path.startsWith(\"/video/\")");
        assertBefore(handle, "path.startsWith(\"/api/recordings/id/\")",
                "path.startsWith(\"/api/recordings/\")");
        assertBefore(range, "path.startsWith(\"/video/id/\")",
                "path.startsWith(\"/video/\")");
        assertTrue(source.contains("RecordingsIndex.getInstance().resolveById(recordingId)"));
    }

    @Test
    public void knownPathMutationSitesNeverRemoveByFilename() throws IOException {
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java");
        String watcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/RecordingsIndexFileWatcher.java");
        String storage = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");

        assertTrue(methodBody(api, "public static void invalidateRecordingCache")
                .contains("removeByPath(absMp4Path)"));
        assertFalse(methodBody(api, "private static void deleteSidecars")
                .contains("remove(filename)"));
        assertTrue(watcher.contains("removeByPath(new File(dir, path).getAbsolutePath())"));
        assertFalse(storage.contains("remove(file.getName())"));
        assertTrue(methodBody(api, "private static void deleteSidecars")
                .contains("RecordingIdentity.fromPath(mp4File.getAbsolutePath()).recordingId"));
        assertTrue(methodBody(api, "private static void batchDeleteRecordings")
                .contains("request.optJSONArray(\"ids\")"));
        assertTrue(methodBody(api, "private static void batchDeleteRecordings")
                .contains("int batchSize = idCount + filenameCount"));
        assertTrue(methodBody(api, "private static void serveEventTimeline")
                .contains("filename.substring(0, filename.length() - \".mp4\".length())"));
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(first + " must precede " + second,
                firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex);
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