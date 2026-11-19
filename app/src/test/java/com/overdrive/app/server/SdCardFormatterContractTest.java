package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level guards; device acceptance is deliberately separate. */
public class SdCardFormatterContractTest {
    private static String read(String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
    }

    @Test public void backgroundFormatChecksMountedNewUuidAndRestartsCleaner() throws Exception {
        String source = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(source.contains("worker.execute(() ->"));
        assertTrue(source.contains("command(FORMAT_TIMEOUT_MS, "sm", "format", target.volumeId)"));
        assertTrue(source.contains("command(COMMAND_TIMEOUT_MS, "sm", "mount", target.volumeId)"));
        assertTrue(source.contains("!target.uuid.equals(current.uuid)"));
        assertTrue(source.contains(""mounted".equals(current.state)"));
        assertTrue(source.contains("storage.rememberFormattedSdUuid(current.uuid)"));
        assertTrue(source.contains("cleaner.pauseForSdFormat()"));
        assertTrue(source.contains("cleaner.resumeAfterSdFormat()"));
        String cleaner = read("src/main/java/com/overdrive/app/storage/ExternalStorageCleaner.java");
        assertTrue(cleaner.contains("synchronized (cleanupLock)"));
        assertTrue(cleaner.contains("sdFormatPaused.get()"));
        assertFalse(source.contains("new File(process, "fd").listFiles() == null"));
    }

    @Test public void shellCdrGateCoversAccessiblePathsAndOriginPolicy() throws Exception {
        String source = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(source.contains("command(COMMAND_TIMEOUT_MS, "ps", "-A")"));
        assertTrue(source.contains("com.byd.cdr:"));
        assertTrue(source.contains("/mnt/user/"));
        assertTrue(source.contains("/mnt/pass_through/"));
        assertTrue(source.contains(""cwd""));
        assertTrue(source.contains(""maps""));
        assertTrue(source.contains("public:[0-9]+[:,][0-9]+"));
        String server = read("src/main/java/com/overdrive/app/server/HttpServer.java");
        assertTrue(server.contains("storageFormatApi"));
        assertTrue(server.contains("GenAiRequestSecurity.isAllowedOrigin"));
        assertTrue(server.contains("HttpResponse.sendJsonNoCors(out, 403"));
        assertTrue(server.contains("|| storageFormatApi"));
    }

    @Test public void webUsesAsyncStatusAndLocalizedCapacity() throws Exception {
        String js = read("src/main/assets/web/shared/storage.js");
        assertTrue(js.contains("/api/storage/external/format/status"));
        assertTrue(js.contains("target.capacityBytes"));
        assertTrue(js.contains("target.usedBytes"));
        assertTrue(js.contains("sd_format_success"));
        String html = read("src/main/assets/web/local/storage.html");
        assertTrue(html.contains("storage.js?v=4"));
        for (String lang : new String[] {"en", "he"}) {
            String i18n = read("src/main/assets/web/i18n/" + lang + ".json");
            assertTrue(i18n.contains("sd_format_success"));
            assertTrue(i18n.contains("sd_format_capacity"));
            assertFalse(i18n.contains("sd_format_target_failed"));
        }
    }
}
