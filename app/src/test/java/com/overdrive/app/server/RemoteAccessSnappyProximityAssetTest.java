package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 18 Oct snappy proximity, UI half: the Proximity page's Advanced timing fields carry the new
 * defaults and floors, the two grace fields and the confirmation switch exist (Advanced only), the
 * script saves/loads/validates them and labels the {@code sent} outcome, the English catalog carries
 * the texts, and the runbook has the "Response time and confirmations" section. Style of
 * {@link RemoteAccessCloudFallbackAssetTest}.
 */
public class RemoteAccessSnappyProximityAssetTest {
    private static final String SCRIPT = "app/src/main/assets/web/shared/remote-access.js";
    private static final String PAGE = "app/src/main/assets/web/local/remote-access.html";
    private static final String EN = "app/src/main/assets/web/i18n/en.json";
    private static final String DOCS = "docs/remote-access.md";

    @Test
    public void advancedTimingFieldsCarryTheNewDefaultsAndFloors() throws IOException {
        String html = readRepositoryFile(PAGE);
        assertTrue(html.contains("id=\"raNearWindowSeconds\" min=\"2\" max=\"60\" step=\"1\" value=\"2\" required"));
        assertTrue(html.contains("id=\"raNearDwellSeconds\" min=\"1\" max=\"30\" step=\"1\" value=\"1\" required"));
        assertTrue(html.contains("id=\"raMedianSeconds\" min=\"4\" max=\"60\" step=\"1\" value=\"6\" required"));
        assertTrue(html.contains("id=\"raDwellSeconds\" min=\"1\" max=\"30\" step=\"1\" value=\"2\" required"));
        assertTrue(html.contains("id=\"raCooldownSeconds\" min=\"5\" max=\"300\" step=\"1\" value=\"10\" required"));
        assertTrue(html.contains("id=\"raRelockGraceSeconds\" min=\"3\" max=\"30\" step=\"1\" value=\"10\" required"));
        assertTrue(html.contains("id=\"raPostLockQuietSeconds\" min=\"3\" max=\"30\" step=\"1\" value=\"10\" required"));
        // All of them sit in the Advanced-only settings grid.
        int grid = html.indexOf("<div class=\"ra-settings-grid ra-advanced\">");
        int rules = html.indexOf("<section class=\"card ra-rules-card\"", grid);
        assertTrue(grid > 0 && rules > grid);
        for (String id : new String[] {"raNearWindowSeconds", "raNearDwellSeconds", "raMedianSeconds", "raDwellSeconds",
                "raCooldownSeconds", "raRelockGraceSeconds", "raPostLockQuietSeconds"}) {
            int at = html.indexOf("id=\"" + id + "\"");
            assertTrue(id, at > grid && at < rules);
            assertEquals(id, 1, count(html, "id=\"" + id + "\""));
        }
        // Two-reading floor in the hint, no "three consecutive" left; the defaults hint names every default.
        assertTrue(html.contains("data-i18n=\"remote_access.unlock_confirmation_hint\">Unlock requires at least two fresh Near readings"));
        assertFalse(html.contains("three consecutive"));
        assertTrue(html.contains("data-i18n=\"remote_access.timing_defaults_hint\">Defaults: 2 s unlock window, 1 s unlock confirmation, 6 s departure window, 2 s lock confirmation, 10 s cooldown."));
        assertTrue(html.contains("data-i18n=\"remote_access.grace_quiet_hint\">"));
        assertEquals(1, count(html, "remote-access.js?v=28")); // 18 Oct bumped to 24; 19 Oct (fob trigger switch) to 25; 20 Oct (snappy locks) to 26; 22 Oct (car-on pause) to 27; 2 Nov (Hebrew i18n) to 28
    }

    @Test
    public void confirmationSwitchIsAnAdvancedRuleBeforeTheCloudFallbackRule() throws IOException {
        String html = readRepositoryFile(PAGE);
        int card = html.indexOf("<section class=\"card ra-rules-card\"");
        int hint = html.indexOf("id=\"raUnlockTriggerHint\"", card);
        int confirm = html.indexOf("<label class=\"ra-rule ra-confirm-vehicle-rule ra-advanced\">", hint);
        int cloud = html.indexOf("<label class=\"ra-rule ra-cloud-fallback-rule\">", confirm);
        assertTrue(card > 0 && hint > card && confirm > hint && cloud > confirm);
        String row = html.substring(confirm, cloud);
        assertTrue(row.contains("<strong data-i18n=\"remote_access.confirm_with_vehicle\">Confirm with the car's lock state</strong>"));
        assertTrue(row.contains("<span data-i18n=\"remote_access.confirm_with_vehicle_desc\">"));
        assertTrue(row.contains("also disables the cloud fallback"));
        assertTrue(row.contains("<input type=\"checkbox\" id=\"raConfirmWithVehicle\" aria-label="));
        assertFalse(row.contains("disabled"));
        assertFalse(row.contains("checked"));
        assertEquals(1, count(html, "id=\"raConfirmWithVehicle\""));
    }

    @Test
    public void scriptLoadsSavesValidatesTheNewSettingsAndLabelsTheSentOutcome() throws IOException {
        String script = readRepositoryFile(SCRIPT);
        // Field list (dirty tracking / busy handling) keeps the pinned tail and gains the three controls.
        assertTrue(script.contains("'raKeyFobLockSeconds', 'raRestrictLockSafeZones', 'raRestrictUnlockSafeZones', 'raCloudFallback',\n"
                + "        'raConfirmWithVehicle', 'raRelockGraceSeconds', 'raPostLockQuietSeconds'"));
        // One source of truth for the defaults; the old literals are gone.
        assertTrue(script.contains("var TIMING_DEFAULTS = { windowMs: 6000, dwellMs: 2000, nearWindowMs: 2000, nearDwellMs: 1000,\n"
                + "        cooldownMs: 10000, relockGraceMs: 10000, postLockQuietMs: 10000 };"));
        for (String old : new String[] {"|| 15000", "|| 30000", "!== 15000", "!== 4000", "value = 15;", "value = 30;",
                "'/3 fresh Near readings'"})
            assertEquals(old, 0, count(script, old));
        // Load, normal-mode reset, fast-unlock preset, save payload.
        assertTrue(script.contains("el('raRelockGraceSeconds').value = (config.relockGraceMs || TIMING_DEFAULTS.relockGraceMs) / 1000;"));
        assertTrue(script.contains("el('raPostLockQuietSeconds').value = (config.postLockQuietMs || TIMING_DEFAULTS.postLockQuietMs) / 1000;"));
        assertTrue(script.contains("el('raConfirmWithVehicle').checked = config.confirmWithVehicle !== false;"));
        assertTrue(script.contains("el('raMedianSeconds').value = 6; el('raDwellSeconds').value = 2; el('raCooldownSeconds').value = 10;\n"
                + "            el('raNearWindowSeconds').value = 2; el('raNearDwellSeconds').value = 1;\n"
                + "            el('raRelockGraceSeconds').value = 10; el('raPostLockQuietSeconds').value = 10;\n"
                + "            el('raConfirmWithVehicle').checked = true;"));
        assertTrue(script.contains("el('raNearWindowSeconds').value = TIMING_DEFAULTS.nearWindowMs / 1000;\n"
                + "        el('raNearDwellSeconds').value = TIMING_DEFAULTS.nearDwellMs / 1000;"));
        assertTrue(script.contains("            relockGraceMs: Number(el('raRelockGraceSeconds').value) * 1000,\n"
                + "            postLockQuietMs: Number(el('raPostLockQuietSeconds').value) * 1000,\n"
                + "            confirmWithVehicle: el('raConfirmWithVehicle').checked,"));
        assertEquals(2, count(script, "confirmWithVehicle: el('raConfirmWithVehicle').checked,"));
        // Normal mode still retains the custom Advanced values for later while the server runs the defaults.
        assertTrue(script.contains("config.relockGraceMs = custom.relockGraceMs || TIMING_DEFAULTS.relockGraceMs;"));
        assertTrue(script.contains("config.confirmWithVehicle = custom.confirmWithVehicle !== false;"));
        // Client range checks mirror the server floors and ceilings.
        assertTrue(script.contains("config.dwellMs < 1000 || config.dwellMs > 30000"));
        assertTrue(script.contains("config.nearWindowMs < 2000 || config.nearWindowMs > 60000"));
        assertTrue(script.contains("config.nearDwellMs < 1000 || config.nearDwellMs > 30000"));
        assertTrue(script.contains("config.cooldownMs < 5000 || config.cooldownMs > 300000"));
        assertTrue(script.contains("throw new Error(tr('error_timing_range', 'Use a 2–60 second unlock window, a 4–60 second departure window, 1–30 second confirmations, and a 5–300 second cooldown.'));"));
        assertTrue(script.contains("config.relockGraceMs < 3000 || config.relockGraceMs > 30000"));
        assertTrue(script.contains("config.postLockQuietMs < 3000 || config.postLockQuietMs > 30000"));
        assertTrue(script.contains("throw new Error(tr('error_grace_range', 'Use a 3–30 second relock grace and a 3–30 second keyfob quiet period.'));"));
        // Diagnostics show the two-reading floor; the arrival median stays the last three readings.
        assertTrue(script.contains("{count}/2 fresh Near readings"));
        assertTrue(script.contains("tr('filter_unlock', 'Unlock · last 3 / {n}s', { n: arrivalWindow / 1000 })"));
        // The sent outcome (confirmation off) in the event list and the lock-state line.
        assertTrue(script.contains("sent: 'Command sent · vehicle confirmation off',"));
        assertTrue(script.contains("sent: '{action} sent · vehicle confirmation off',"));
        // Existing cloud fallback wiring untouched.
        assertEquals(1, count(script, "config.cloudFallback = "));
        assertTrue(script.contains("cloud_acknowledged: 'Cloud command acknowledged · no local confirmation',"));
    }

    @Test
    public void englishCatalogCarriesTheNewTextsVerbatim() throws Exception {
        JSONObject en = new JSONObject(readRepositoryFile(EN));
        JSONObject remoteAccess = en.getJSONObject("remote_access");
        assertEquals("Confirm with the car's lock state", remoteAccess.getString("confirm_with_vehicle"));
        assertTrue(remoteAccess.getString("confirm_with_vehicle_desc").contains("also disables the cloud fallback"));
        assertEquals("Relock grace after unlock", remoteAccess.getString("relock_grace"));
        assertEquals("Keyfob quiet period after lock", remoteAccess.getString("post_lock_quiet"));
        assertTrue(remoteAccess.getString("grace_quiet_hint").contains("Normal mode uses 10 s for both."));
        assertTrue(remoteAccess.getString("timing_defaults_hint").startsWith("Defaults: 2 s unlock window, 1 s unlock confirmation, 6 s departure window, 2 s lock confirmation, 10 s cooldown."));
        assertTrue(remoteAccess.getString("unlock_confirmation_hint").startsWith("Unlock requires at least two fresh Near readings"));
        assertFalse(remoteAccess.getString("unlock_confirmation_hint").contains("three"));
        // The page's inline English equals the catalog (the page is the fallback when the catalog is not loaded).
        String html = readRepositoryFile(PAGE);
        for (String key : new String[] {"confirm_with_vehicle", "confirm_with_vehicle_desc", "relock_grace", "post_lock_quiet",
                "grace_quiet_hint", "timing_defaults_hint", "unlock_confirmation_hint"})
            assertTrue(key, html.contains("data-i18n=\"remote_access." + key + "\">" + remoteAccess.getString(key) + "<"));
        // Neighbouring keys are still there.
        assertTrue(remoteAccess.has("cloud_fallback_desc"));
        assertTrue(remoteAccess.has("fast_unlock_defaults"));
    }

    @Test
    public void runbookHasTheResponseTimeSectionWithDefaultsFloorJobAndMigration() throws IOException {
        String docs = readRepositoryFile(DOCS);
        int section = docs.indexOf("**Response time and confirmations** (18 Oct).");
        int railSet = docs.indexOf("**Changing the rail set**", section);
        int actuator = docs.indexOf("**Cloud fallback actuator** (17 Oct).");
        assertTrue(actuator > 0 && section > actuator && railSet > section);
        String text = docs.substring(section, railSet);
        for (String needle : new String[] {"`nearWindowMs` 2 s", "`nearDwellMs` 1 s", "`windowMs` 6 s", "`dwellMs` 2 s",
                "`cooldownMs` 10 s", "`relockGraceMs` 10 s", "`postLockQuietMs` 10 s", "`confirmWithVehicle` on",
                "MIN_CONFIRMING_SAMPLES = 2", "two Near readings", "two Away readings", "`proximity-confirm`",
                "Command already in progress", "immediately leave", "`timingExplicit`", "15000/4000/4000/2000/30000",
                "Proximity: confirm action=", "no longer delays the next decision", "Relock grace after unlock",
                "Keyfob quiet period after lock", "with the car's lock state", "Command sent; vehicle",
                "supersedes the job", "Floors:", "ceilings are unchanged"})
            assertTrue(needle, text.contains(needle));
        assertTrue(text.contains("runs on a single-use daemon thread named `proximity-confirm`"));
        // The older default descriptions were updated.
        assertFalse(docs.contains("Departure retains a 15-second median window"));
        assertFalse(docs.contains("Arrival requires three consecutive fresh Near readings"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
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
