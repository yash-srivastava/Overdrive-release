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
 * 19 Oct key fob unlock trigger, UI half: the Proximity page's keyfob section carries the Advanced
 * switch "Unlock as soon as the key is detected" (on = {@code keyFobUnlockTrigger: near}, the
 * default), the script loads/saves/resets it and derives the keyfob hints from it instead of the
 * phones' trigger, the English catalog carries the texts verbatim, and the runbook documents the
 * default, the Away→Near option and the {@code unarmed_detection} reason. Style of
 * {@link RemoteAccessSnappyProximityAssetTest}.
 */
public class RemoteAccessKeyFobUnlockTriggerAssetTest {
    private static final String SCRIPT = "app/src/main/assets/web/shared/remote-access.js";
    private static final String PAGE = "app/src/main/assets/web/local/remote-access.html";
    private static final String EN = "app/src/main/assets/web/i18n/en.json";
    private static final String DOCS = "docs/remote-access.md";
    private static final String LABEL = "Unlock as soon as the key is detected";
    private static final String HINT = "Off: the car must first report the key as absent (after a lock) before a "
            + "detection can unlock — slower after installs, restarts and remote power cycles.";

    @Test
    public void keyFobTriggerSwitchIsAnAdvancedRuleBetweenDetectionAndDepartureLocking() throws IOException {
        String html = readRepositoryFile(PAGE);
        int detection = html.indexOf("<label class=\"ra-rule ra-keyfob-rule\">");
        int trigger = html.indexOf("<label class=\"ra-rule ra-keyfob-unlock-rule ra-advanced\" id=\"raKeyFobUnlockRule\">", detection);
        int locking = html.indexOf("<label class=\"ra-rule ra-keyfob-lock-rule\" id=\"raKeyFobLockRule\">", trigger);
        assertTrue(detection > 0 && trigger > detection && locking > trigger);
        String row = html.substring(trigger, locking);
        assertTrue(row.contains("<strong data-i18n=\"remote_access.keyfob_unlock_on_detection\">" + LABEL + "</strong>"));
        assertTrue(row.contains("<span id=\"raKeyFobUnlockDetail\" data-i18n=\"remote_access.keyfob_unlock_on_detection_desc\">" + HINT + "</span>"));
        assertTrue(row.contains("<input type=\"checkbox\" id=\"raKeyFobUnlockNear\" aria-label=\"" + LABEL
                + "\" data-i18n-attr=\"aria-label:remote_access.keyfob_unlock_on_detection\" aria-describedby=\"raKeyFobUnlockDetail\" disabled>"));
        assertTrue(row.contains("<span class=\"ra-rule-icon\">") && row.contains("<span class=\"ra-rule-copy\">")
                && row.contains("<span class=\"ra-switch\">"));
        // Not pre-checked in markup: the script sets it from the saved config (default near = on).
        assertFalse(row.contains(" checked"));
        assertEquals(1, count(html, "id=\"raKeyFobUnlockNear\""));
        // The surrounding keyfob rows and the script tag are as before.
        assertEquals(1, count(html, "id=\"raKeyFobEnabled\""));
        assertEquals(1, count(html, "id=\"raKeyFobLockEnabled\""));
        assertEquals(1, count(html, "remote-access.js?v="));
    }

    @Test
    public void scriptLoadsSavesResetsAndHintsFromTheKeyFobTrigger() throws IOException {
        String script = readRepositoryFile(SCRIPT);
        // Dirty tracking / busy handling: the pinned tail of the field list gains the switch.
        assertTrue(script.contains("'raConfirmWithVehicle', 'raRelockGraceSeconds', 'raPostLockQuietSeconds',\n"
                + "        'raKeyFobUnlockNear'\n"
                + "    ];"));
        // Load: anything but an explicit away_to_near is the default (on).
        assertTrue(script.contains("el('raKeyFobUnlockNear').checked = config.keyFobUnlockTrigger !== 'away_to_near';"));
        // Save payload (Advanced draft snapshot and the save itself): the switch maps to the two values only.
        assertEquals(2, count(script, "keyFobUnlockTrigger: el('raKeyFobUnlockNear').checked ? 'near' : 'away_to_near'"));
        // Advanced on restores the retained custom choice; Advanced off resets the control to the default.
        assertTrue(script.contains("el('raKeyFobUnlockNear').checked = custom.keyFobUnlockTrigger !== 'away_to_near';"));
        // (20 Oct: the normal-mode keyfob window default is 5 s.)
        assertTrue(script.contains("el('raKeyFobLockSeconds').value = 5;\n"
                + "            el('raKeyFobUnlockNear').checked = true;"));
        // Normal mode keeps the custom value in the saved object for the next Advanced session (server forces near).
        assertTrue(script.contains("config.keyFobUnlockTrigger = custom.keyFobUnlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';"));
        // Advanced-only control, usable only with keyfob detection drafted on.
        assertTrue(script.contains("el('raKeyFobUnlockNear').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobDraft;"));
        // The keyfob hints follow the fob trigger (default in normal mode), not the phones' trigger.
        assertTrue(script.contains("function keyFobUnlocksOnDetection() {\n"
                + "        return !el('raAdvancedMode').checked || el('raKeyFobUnlockNear').checked;\n"
                + "    }"));
        assertTrue(script.contains("var fobHint = fobNear ?\n"
                + "            tr('keyfob_near_hint',"));
        assertTrue(script.contains("if (unlock && fobEnabled) label += ' ' + (fobNear ?"));
        assertTrue(script.contains("if (unlock && fobOnly) label = (fobNear ?"));
        assertFalse(script.contains("var fobHint = near ?"));
        assertFalse(script.contains("if (unlock && fobEnabled) label += ' ' + (el('raUnlockTrigger').value === 'near' ?"));
        // The phones' trigger select and its save are untouched.
        assertTrue(script.contains("unlockTrigger: el('raUnlockTrigger').value === 'near' ? 'near' : 'away_to_near',"));
        assertTrue(script.contains("el('raUnlockTrigger').value = config.unlockTrigger === 'near' ? 'near' : 'away_to_near';"));
    }

    @Test
    public void englishCatalogCarriesTheTextsVerbatimAndThePageMatches() throws Exception {
        JSONObject remoteAccess = new JSONObject(readRepositoryFile(EN)).getJSONObject("remote_access");
        assertEquals(LABEL, remoteAccess.getString("keyfob_unlock_on_detection"));
        assertEquals(HINT, remoteAccess.getString("keyfob_unlock_on_detection_desc"));
        String html = readRepositoryFile(PAGE);
        assertTrue(html.contains("data-i18n=\"remote_access.keyfob_unlock_on_detection\">" + LABEL + "<"));
        assertTrue(html.contains("data-i18n=\"remote_access.keyfob_unlock_on_detection_desc\">" + HINT + "<"));
        // Neighbouring keyfob keys are still there.
        assertTrue(remoteAccess.has("keyfob_lock_window_hint"));
        assertTrue(remoteAccess.has("keyfob_near_hint"));
        assertTrue(remoteAccess.has("keyfob_return_hint"));
    }

    @Test
    public void runbookDocumentsTheDefaultTheOptionAndTheUnarmedReason() throws IOException {
        String docs = readRepositoryFile(DOCS);
        int section = docs.indexOf("**Fob unlock trigger** (`keyFobUnlockTrigger`, 19 Oct).");
        assertTrue(section > 0);
        String text = docs.substring(section, docs.indexOf("The page displays old values", section));
        for (String needle : new String[] {"`near`", "Unlock as soon as the key is detected", "first\nfresh detection frame",
                "no prior absence frame (`4`)", "`postLockQuietMs`", "Advanced-only", "`away_to_near`",
                "reason=unarmed_detection", "19 Oct 21:18:38", "key locked inside the\ncar", "unverified",
                "install", "restart", "settings reset", "remote power cycle", "17 Oct note"})
            assertTrue(needle, text.contains(needle));
        // The defaults list of the snappy section names the new default, and the older phrasing is gone.
        assertTrue(docs.contains("`confirmWithVehicle` on, `keyFobUnlockTrigger`\n`near` (19 Oct"));
        assertFalse(docs.contains("The normal **Unlock when** setting applies to both sources."));
        assertFalse(docs.contains("the default Away→Near rule therefore needs a new Away event before Near."));
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
