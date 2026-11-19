package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Static contracts; actual sm/pm calls must be proved on DI3/DI5 hardware. */
public class SdCardFormatterContractTest {
    private static String read(String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
    }

    @Test public void backgroundWorkerRemountsAndNeverLearnsAnUnprovenUuid() throws Exception {
        String source = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(source.contains("worker.execute(() ->"));
        assertTrue(source.contains("command(FORMAT_TIMEOUT_MS, \"sm\", \"format\", target.volumeId)"));
        assertTrue(source.contains("command(COMMAND_TIMEOUT_MS, \"sm\", \"mount\", target.volumeId)"));
        assertTrue(source.contains("!target.uuid.equals(current.uuid)"));
        assertTrue(source.contains("\"mounted\".equals(current.state)"));
        assertTrue(source.contains("target.fingerprint.equals(mediaFingerprint(current.id))"));
        assertTrue(source.contains("storage.rememberFormattedSdUuid(current.uuid)"));
        assertTrue(source.contains("cleaner.pauseForSdFormat()"));
        assertTrue(source.contains("cleaner.resumeAfterSdFormat()"));
        String cleaner = read("src/main/java/com/overdrive/app/storage/ExternalStorageCleaner.java");
        assertTrue(cleaner.contains("synchronized (cleanupLock)"));
        assertTrue(cleaner.contains("sdFormatPaused.get()"));
        assertFalse(source.contains("new File(process, \"fd\").listFiles() == null"));
    }

    @Test public void shellCdrGateAndRecoveryAreFailClosed() throws Exception {
        String source = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(source.contains("command(COMMAND_TIMEOUT_MS, \"ps\", \"-A\")"));
        assertTrue(source.contains("com.byd.cdr:"));
        assertTrue(source.contains("if (!otherUserVisible)"));
        assertTrue(source.contains("\"cwd\""));
        assertTrue(source.contains("\"maps\""));
        assertTrue(source.contains("\"/mnt/user/\""));
        assertTrue(source.contains("\"/mnt/pass_through/\""));
        assertTrue(source.contains("\"pm\", \"disable-user\""));
        assertTrue(source.contains("\"pm\", \"enable\""));
        assertTrue(source.contains("recoverAtDaemonStart()"));
        String storage = read("src/main/java/com/overdrive/app/storage/StorageManager.java");
        assertTrue(storage.contains("BARE_PUBLIC_VOLUME_ID"));
        assertTrue(source.contains("StorageManager.isPublicVolumeId(fields[0])"));
    }

    @Test public void originAndWebStatusContractsAreLocalized() throws Exception {
        String server = read("src/main/java/com/overdrive/app/server/HttpServer.java");
        assertTrue(server.contains("storageFormatApi"));
        assertTrue(server.contains("GenAiRequestSecurity.isAllowedOrigin"));
        assertTrue(server.contains("HttpResponse.sendJsonNoCors(out, 403"));
        assertTrue(server.contains("|| storageFormatApi"));
        String js = read("src/main/assets/web/shared/storage.js");
        assertTrue(js.contains("/api/storage/external/format/status"));
        assertTrue(js.contains("target.capacityBytes"));
        assertTrue(js.contains("target.usedBytes"));
        assertTrue(js.contains("sd_format_poll_retry"));
        assertTrue(js.contains("sd_format_reason_"));
        assertFalse(js.contains("Date.now() - started < 210000"));
        String html = read("src/main/assets/web/local/storage.html");
        assertTrue(html.contains("storage.js?v=5"));
        for (String lang : new String[] {"en", "he"}) {
            String i18n = read("src/main/assets/web/i18n/" + lang + ".json");
            assertTrue(i18n.contains("sd_format_success"));
            assertTrue(i18n.contains("sd_format_reason_cdr_running"));
            assertTrue(i18n.contains("sd_format_reason_unverified"));
        }
    }
}
