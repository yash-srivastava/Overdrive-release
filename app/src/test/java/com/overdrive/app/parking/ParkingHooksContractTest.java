package com.overdrive.app.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Pins the daemon-side integration of Parking Intelligence:
 * <ul>
 *   <li>every hook site is a {@link ParkingHooks} static call (null check when off);</li>
 *   <li>the feature never writes to the detector / baseline / trigger / recorder
 *       (FP-neutral: DETECTION-INVARIANTS);</li>
 *   <li>the notification registry, Telegram sink and i18n catalogs carry the two
 *       parking categories consistently.</li>
 * </ul>
 */
public class ParkingHooksContractTest {

    @Test
    public void daemonHookSitesUseTheStaticNullCheckedEntryPoints() throws Exception {
        String daemon = read("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("ParkingHooks.onAccOff(transitionGeneration)"));
        assertTrue(daemon.contains("ParkingHooks.onAccOn(transitionGeneration)"));
        assertTrue(daemon.contains("ParkingHooks.onUnlockWhileParked()"));
        assertTrue("attach(), never start(), at daemon boot", daemon.contains("parking.attach()"));
        assertTrue(daemon.contains("parking.detach()"));
        // The ACC hook must precede the pipeline-null early return so a deferred
        // replay re-enters it (the controller dedups by generation).
        int hook = daemon.indexOf("ParkingHooks.onAccOff(transitionGeneration)");
        int nullCheck = daemon.indexOf("if (gpuPipeline == null || recordingModeManager == null)", hook);
        assertTrue(hook > 0 && nullCheck > hook);
        // Unlock hook fires BEFORE the surveillance teardown.
        int unlockHook = daemon.indexOf("ParkingHooks.onUnlockWhileParked()");
        int disable = daemon.indexOf("disableSurveillance();", unlockHook);
        assertTrue(unlockHook > 0 && disable > unlockHook && disable - unlockHook < 200);

        String engine = read("app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");
        assertTrue(engine.contains("ParkingHooks.onEventFinalized("));
        String collector = read("app/src/main/java/com/overdrive/app/surveillance/EventTimelineCollector.java");
        assertTrue(collector.contains("ParkingHooks.currentSessionId()"));
        assertTrue(collector.contains("\"parkingSessionId\""));
    }

    @Test
    public void featureIsReadOnlyTowardsDetection() throws Exception {
        String[] files = {
                "app/src/main/java/com/overdrive/app/parking/ParkingController.java",
                "app/src/main/java/com/overdrive/app/parking/ParkingNeighbourObserver.java",
                "app/src/main/java/com/overdrive/app/parking/ParkingCropHarvester.java",
                "app/src/main/java/com/overdrive/app/parking/DaemonParkingEnvironment.java",
        };
        String[] forbidden = {
                "DetectionBaseline.getInstance", "seedFromDetections", "updateFromEventEnd",
                "promoteStaticActor", "startRecording(", "stopRecording(", "setSensitivity",
                "setConfidenceThreshold", "enableSurveillance(", "disableSurveillance(",
                "ThumbnailBuffer", "MotionTrigger", ".triggerEvent(",
        };
        for (String f : files) {
            String src = read(f);
            for (String token : forbidden) {
                assertFalse(f + " must not reference " + token, src.contains(token));
            }
        }
        String baseline = read("app/src/main/java/com/overdrive/app/surveillance/DetectionBaseline.java");
        assertTrue("observer is fire-and-forget", baseline.contains("try { l.onEntryAdded(new EntrySnapshot(e), source); } catch (Throwable ignored) {}"));
    }

    @Test
    public void notificationRegistryCarriesBothParkingCategories() throws Exception {
        JSONObject reg = new JSONObject(read("app/src/main/assets/notifications-categories.json"));
        JSONArray cats = reg.getJSONArray("categories");
        Set<String> ids = new HashSet<>();
        JSONObject started = null, ended = null;
        for (int i = 0; i < cats.length(); i++) {
            JSONObject c = cats.getJSONObject(i);
            assertTrue("duplicate id " + c.getString("id"), ids.add(c.getString("id")));
            if (ParkingNotifier.CATEGORY_STARTED.equals(c.getString("id"))) started = c;
            if (ParkingNotifier.CATEGORY_ENDED.equals(c.getString("id"))) ended = c;
        }
        for (JSONObject c : new JSONObject[] {started, ended}) {
            assertTrue(c != null);
            assertEquals("Parking", c.getString("group"));
            assertEquals("info", c.getString("severity"));
            assertTrue(c.getBoolean("defaultEnabled"));
            assertEquals("/parking.html", c.getString("defaultClickUrl"));
        }
    }

    @Test
    public void telegramPathHasItsOwnToggleAndPhotoLane() throws Exception {
        String sink = read("app/src/main/java/com/overdrive/app/notifications/sinks/TelegramSink.java");
        assertTrue(sink.contains("startsWith(\"parking.\")"));
        assertTrue(sink.contains("isParkingMessages()"));
        int parkingBranch = sink.indexOf("startsWith(\"parking.\")");
        int infoDrop = sink.indexOf("event.severity == NotificationEvent.Severity.INFO && !userAuthored");
        assertTrue("parking branch must run before the INFO drop", parkingBranch > 0 && infoDrop > parkingBranch);

        String bot = read("app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java");
        assertTrue(bot.contains("case \"notifyParking\""));
        assertTrue(bot.contains("\"notifyMotionFinalized\".equals(action) || \"notifyParking\".equals(action)"));
        assertTrue(bot.contains("case \"notifyParking\":\n                return true;")
                || bot.contains("case \"notifyParking\":\r\n                return true;"));
        assertTrue(bot.contains("addFormDataPart(\"reply_markup\", replyMarkupJson)"));

        String cfg = read("app/src/main/java/com/overdrive/app/telegram/config/UnifiedTelegramConfig.java");
        assertTrue(cfg.contains("K_PARKING_MESSAGES = \"parkingMessages\""));
        String api = read("app/src/main/java/com/overdrive/app/server/TelegramApiHandler.java");
        assertTrue(api.contains("\"parkingMessages\""));
        String page = read("app/src/main/assets/web/local/notifications.html");
        assertTrue(page.contains("tgPrefParkingMessages"));
        assertTrue(page.contains("parkingMessages:     pending.tgPrefParkingMessages"));

        String router = read("app/src/main/java/com/overdrive/app/daemon/telegram/CommandRouter.java");
        assertTrue(router.contains("new WhereCommandHandler()"));
        String ipc = read("app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        assertTrue(ipc.contains("case \"PARKING_STATUS\""));
    }

    @Test
    public void serverCatalogsCarryTheNotifierKeysInEnglishAndHebrew() throws Exception {
        for (String lang : new String[] {"en", "he"}) {
            JSONObject cat = new JSONObject(read("app/src/main/assets/server-i18n/" + lang + ".json"));
            JSONObject notify = cat.getJSONObject("parking").getJSONObject("notify");
            for (String k : new String[] {"started_title", "ended_title", "ended_body", "nothing_critical",
                    "no_place", "gps_fresh", "gps_recent", "gps_stale", "gps_unknown", "sentry_off",
                    "sentry_safe_zone", "sentry_schedule", "sentry_disabled", "sentry_vehicle_on_only",
                    "sentry_pipeline_down", "sentry_unknown", "btn_walk"}) {
                assertTrue(lang + " missing parking.notify." + k, notify.has(k));
            }
            JSONObject where = cat.getJSONObject("telegram").getJSONObject("parking").getJSONObject("where");
            for (String k : new String[] {"unavailable", "disabled", "none", "title_open", "title_last",
                    "parked_for", "was_away", "counts", "gps_fresh", "gps_recent", "gps_stale", "gps_unknown"}) {
                assertTrue(lang + " missing telegram.parking.where." + k, where.has(k));
            }
            assertTrue(cat.getJSONObject("telegram").getJSONObject("help").getString("text").contains("/where"));
        }
        for (String lang : new String[] {"en", "he"}) {
            JSONObject web = new JSONObject(read("app/src/main/assets/web/i18n/" + lang + ".json"));
            assertTrue(web.getJSONObject("nav").has("parking"));
            assertTrue(web.getJSONObject("parking").has("settings_enabled"));
            assertTrue(web.getJSONObject("telegram").has("parking_messages"));
            assertTrue(web.getJSONObject("dashboard").has("cmd_parking"));
        }
    }

    @Test
    public void signageOcrModelsAreBundledWithTheApk() throws Exception {
        Path det = repositoryPath("app/src/main/assets/models/signage_det.tflite");
        Path rec = repositoryPath("app/src/main/assets/models/signage_rec.tflite");
        Path dict = repositoryPath("app/src/main/assets/models/signage_charset.txt");
        assertTrue("detector must be a real TFLite flatbuffer", Files.size(det) > 500_000L);
        assertTrue("recogniser must be a real TFLite flatbuffer", Files.size(rec) > 2_000_000L);
        byte[] magic = new byte[8];
        try (java.io.InputStream in = Files.newInputStream(det)) { assertEquals(8, in.read(magic)); }
        assertEquals("TFL3", new String(magic, 4, 4, StandardCharsets.US_ASCII));
        try (java.io.InputStream in = Files.newInputStream(rec)) { assertEquals(8, in.read(magic)); }
        assertEquals("TFL3", new String(magic, 4, 4, StandardCharsets.US_ASCII));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String l : Files.readAllLines(dict, StandardCharsets.UTF_8)) if (!l.isEmpty()) lines.add(l);
        // PaddleOCR ppocr_keys_v1: 6623 symbols; the model has 6625 classes = blank + 6623 + space.
        assertEquals(6623, lines.size());
        String[] cs = com.overdrive.app.parking.signage.CtcDecoder.charsetFromLines(lines, 6625);
        assertEquals("", cs[0]);
        assertEquals("'", cs[1]);
        assertEquals(" ", cs[6624]);
        // The loader's asset names must match what ships.
        String backend = read("app/src/main/java/com/overdrive/app/parking/signage/TfliteTextOcrBackend.java");
        assertTrue(backend.contains("DET_FILE = \"signage_det.tflite\""));
        assertTrue(backend.contains("REC_FILE = \"signage_rec.tflite\""));
        assertTrue(backend.contains("CHARSET_FILE = \"signage_charset.txt\""));
        assertTrue(backend.contains("ASSET_PREFIX = \"models/\""));
    }

    @Test
    public void httpSurfaceIsWired() throws Exception {
        String http = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");
        assertTrue(http.contains("path.startsWith(\"/api/parking\") || path.startsWith(\"/parking/asset/\")"));
        assertTrue(http.contains("\"local/parking.html\""));
        String auth = read("app/src/main/java/com/overdrive/app/server/AuthMiddleware.java");
        assertTrue(auth.contains("path.startsWith(\"/parking/asset/\")"));
        assertTrue(Files.exists(repositoryPath("app/src/main/assets/web/local/parking.html")));
        assertTrue(Files.exists(repositoryPath("app/src/main/assets/web/shared/parking.js")));
        String shell = read("app/src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("href: 'parking.html'"));
    }

    // ---------------------------------------------------------------- helpers

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(repositoryPath(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
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
