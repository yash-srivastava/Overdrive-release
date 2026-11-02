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
 * 17 Oct cloud fallback actuator: the Proximity page's switch, its i18n keys, the new outcome labels
 * and the runbook paragraph. Style of {@link RemoteAccessCollectorStateAssetTest}.
 */
public class RemoteAccessCloudFallbackAssetTest {
    private static final String SCRIPT = "app/src/main/assets/web/shared/remote-access.js";
    private static final String PAGE = "app/src/main/assets/web/local/remote-access.html";
    private static final String EN = "app/src/main/assets/web/i18n/en.json";
    private static final String DOCS = "docs/remote-access.md";
    private static final String HINT = "If the car does not confirm a local proximity lock or unlock command, send it "
            + "through the BYD cloud as well. The safety check before the command and the confirmation after it "
            + "still use the car's local state.";

    @Test
    public void proximityPageHasTheSwitchInsideTheRulesCardBeforeTheKeyfobRule() throws IOException {
        String html = readRepositoryFile(PAGE);
        int card = html.indexOf("<section class=\"card ra-rules-card\"");
        int hint = html.indexOf("id=\"raUnlockTriggerHint\"", card);
        int rule = html.indexOf("<label class=\"ra-rule ra-cloud-fallback-rule\">", hint);
        int keyfob = html.indexOf("<label class=\"ra-rule ra-keyfob-rule\">", rule);
        assertTrue(card > 0 && hint > card && rule > hint && keyfob > rule);
        String row = html.substring(rule, keyfob);
        assertTrue(row.contains("<strong data-i18n=\"remote_access.cloud_fallback\">Cloud fallback for unconfirmed commands</strong>"));
        assertTrue(row.contains("<span data-i18n=\"remote_access.cloud_fallback_desc\">" + HINT + "</span>"));
        assertTrue(row.contains("<input type=\"checkbox\" id=\"raCloudFallback\" aria-label="));
        assertFalse(row.contains("disabled"));
        assertFalse(row.contains("checked"));
        assertEquals(1, count(html, "id=\"raCloudFallback\""));
        // Same markup pattern as the other rule rows; the cache-buster is pinned in RemoteAccessCollectorStateAssetTest.
        assertTrue(row.contains("<span class=\"ra-rule-icon\">") && row.contains("<span class=\"ra-rule-copy\">")
                && row.contains("<span class=\"ra-switch\">"));
        assertEquals(1, count(html, "remote-access.js?v="));
    }

    @Test
    public void scriptWiresTheSwitchIntoLoadSaveAndDirtyTrackingAndLabelsTheNewOutcome() throws IOException {
        String script = readRepositoryFile(SCRIPT);
        // The field list drives markDirty and busy/disabled handling.
        assertTrue(script.contains("'raKeyFobLockSeconds', 'raRestrictLockSafeZones', 'raRestrictUnlockSafeZones', 'raCloudFallback'"));
        // Loaded as on when absent (older daemons), off only on an explicit false; saved from the switch.
        assertTrue(script.contains("        el('raCloudFallback').checked = config.cloudFallback !== false;"));
        assertTrue(script.contains("        config.cloudFallback = el('raCloudFallback').checked;"));
        assertEquals(1, count(script, "config.cloudFallback = "));
        // The applyConfig site sits next to advancedMode, the save site inside the payload builder.
        assertTrue(script.indexOf("el('raAdvancedMode').checked = !!config.advancedMode;")
                < script.indexOf("el('raCloudFallback').checked = config.cloudFallback !== false;"));
        assertTrue(script.indexOf("config.advancedMode = el('raAdvancedMode').checked;")
                < script.indexOf("config.cloudFallback = el('raCloudFallback').checked;"));
        // Outcome labels: the event list and the lock-state line know cloud_acknowledged.
        assertTrue(script.contains("cloud_acknowledged: 'Cloud command acknowledged · no local confirmation',"));
        assertTrue(script.contains("cloud_acknowledged: '{action} sent · cloud acknowledged, unconfirmed locally',"));
        assertTrue(script.indexOf("unconfirmed: '{action} sent · unconfirmed',")
                < script.indexOf("cloud_acknowledged: '{action} sent ·"));
        // The existing labels are untouched.
        assertTrue(script.contains("unconfirmed: 'Command sent · no state confirmation',"));
        assertTrue(script.contains("blocked: 'Action blocked', failed: 'Action failed' };"));
    }

    @Test
    public void englishCatalogCarriesTheSwitchTextsVerbatim() throws Exception {
        JSONObject en = new JSONObject(readRepositoryFile(EN));
        JSONObject remoteAccess = en.getJSONObject("remote_access");
        assertEquals("Cloud fallback for unconfirmed commands", remoteAccess.getString("cloud_fallback"));
        assertEquals(HINT, remoteAccess.getString("cloud_fallback_desc"));
        // Neighbouring keys are still there (the insertion did not swallow a line).
        assertTrue(remoteAccess.has("keyfob_lock_window_suffix"));
        assertTrue(remoteAccess.has("keyfob_presence_search"));
    }

    @Test
    public void runbookDocumentsTheActuatorItsSettingLabelsBudgetAndLimitation() throws IOException {
        String docs = readRepositoryFile(DOCS);
        for (String needle : new String[] {
                "**Cloud fallback actuator** (17 Oct).", "`cloudFallback`", "cloud_acknowledged", "`OPENDOOR`",
                "`LOCKDOOR`", "VehicleCommandRouter.executeCloudOnly", "30 s", "revocation", "allowsIgnoringObservationAge",
                "decisionStillCurrent", "LEFT-front door", "log_NFKBZ3KU", "log_YSP3SY3Y", "log_W5CFRJFW",
                "Close all doors, hood and trunk: trunk open", "front-right door unknown",
                "delivered\nas an *unconfirmed* notification", "RemoteKeyControl"})
            assertTrue(needle, docs.contains(needle));
        // The actuator section sits before the rail-set runbook and after the Confirmation paragraph.
        int confirmation = docs.indexOf("**Confirmation.**");
        int actuator = docs.indexOf("**Cloud fallback actuator** (17 Oct).", confirmation);
        int railSet = docs.indexOf("**Changing the rail set**", actuator);
        assertTrue(confirmation > 0 && actuator > confirmation && railSet > actuator);
        // The old lease statement is gone: the cloud leg is not held to the 5 s observation lease.
        assertFalse(docs.contains("held to the ORIGINAL 5 s"));
        assertFalse(docs.contains("42 s"));
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
