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
 * 20 Oct snappy locks, UI and wiring half: the phones' Unlock trigger is an Advanced control that
 * defaults to When near with the new Away → Near hint; the Advanced switch "Phone locking waits for the
 * key search"; the Keyfob locking window defaults to 5 s and the new "Absence results required" select
 * (1-3, default 1); the script loads, saves, resets and validates them; the English catalog carries the
 * texts; the runbook has the "Snappy defaults" section; and the handler's two sanctioned hunks (the
 * canLock call site and the presence-evaluator construction in reset()) are pinned. Style of
 * {@link RemoteAccessSnappyProximityAssetTest}.
 */
public class RemoteAccessSnappyLocksAssetTest {
    private static final String SCRIPT = "app/src/main/assets/web/shared/remote-access.js";
    private static final String PAGE = "app/src/main/assets/web/local/remote-access.html";
    private static final String EN = "app/src/main/assets/web/i18n/en.json";
    private static final String DOCS = "docs/remote-access.md";
    private static final String HANDLER = "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";
    private static final String AWAY_TO_NEAR_HINT = "Slower after installs, restarts and new phones: the phone must "
            + "first be seen leaving before a return can unlock. Protects against a phone left inside the car "
            + "unlocking it on signal fluctuation.";
    private static final String SAMPLES_HINT = "1 = lock at the first search that finds no key inside (fastest). "
            + "2-3 = require consecutive results; protects against a single missed read locking a key left inside.";

    @Test
    public void unlockTriggerIsAnAdvancedControlThatDefaultsToNear() throws IOException {
        String html = readRepositoryFile(PAGE);
        int block = html.indexOf("<div class=\"ra-unlock-trigger ra-advanced\">");
        int end = html.indexOf("</div>", block);
        assertTrue(block > 0 && end > block);
        String row = html.substring(block, end);
        assertTrue(row.contains("<option value=\"near\" data-i18n=\"remote_access.unlock_trigger_near\" selected>When near</option>"));
        assertTrue(row.contains("<option value=\"away_to_near\" data-i18n=\"remote_access.unlock_trigger_away_to_near\">Away → Near</option>"));
        assertEquals(1, count(row, " selected"));
        assertTrue(row.contains("<p class=\"ra-hint\" id=\"raUnlockTriggerHint\" data-i18n=\"remote_access.unlock_trigger_near_hint\">Unlock after a stable Near signal, "
                + "including first connection or reconnection. No prior Away signal is required.</p>"));
        assertEquals(0, count(html, "<div class=\"ra-unlock-trigger\">"));
        assertEquals(1, count(html, "id=\"raUnlockTrigger\""));
        assertEquals(1, count(html, "remote-access.js?v=28"));
        String script = readRepositoryFile(SCRIPT);
        // Advanced-only and only with Automatic unlock on; Advanced on restores the custom choice, off resets.
        assertTrue(script.contains("el('raUnlockTrigger').disabled = !state.ready || state.saving || !el('raUnlockOnArrival').checked ||\n"
                + "            !el('raAdvancedMode').checked;"));
        assertTrue(script.contains("el('raUnlockTrigger').value = custom.unlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';"));
        assertTrue(script.contains("            el('raUnlockTrigger').value = 'near';\n"));
        // Normal mode keeps the saved custom trigger for later; the server forces near.
        assertTrue(script.contains("config.unlockTrigger = custom.unlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';"));
        assertEquals(2, count(script, "unlockTrigger: el('raUnlockTrigger').value === 'near' ? 'near' : 'away_to_near',"));
        assertTrue(script.contains("tr('unlock_trigger_away_to_near_hint', '" + AWAY_TO_NEAR_HINT + "')"));
        assertFalse(script.contains("Confirm Away first, then unlock after a stable return to Near."));
    }

    @Test
    public void lockWaitSwitchIsAnAdvancedKeyfobRuleWiredThroughTheScript() throws Exception {
        String html = readRepositoryFile(PAGE);
        JSONObject remoteAccess = new JSONObject(readRepositoryFile(EN)).getJSONObject("remote_access");
        String label = remoteAccess.getString("lock_waits_for_key_absence");
        String desc = remoteAccess.getString("lock_waits_for_key_absence_desc");
        assertEquals("Phone locking waits for the key search", label);
        int lockRule = html.indexOf("<label class=\"ra-rule ra-keyfob-lock-rule\" id=\"raKeyFobLockRule\">");
        int waitRule = html.indexOf("<label class=\"ra-rule ra-lock-waits-rule ra-advanced\" id=\"raLockWaitsForKeyRule\">", lockRule);
        int window = html.indexOf("<div class=\"ra-keyfob-window ra-advanced\">", waitRule);
        assertTrue(lockRule > 0 && waitRule > lockRule && window > waitRule);
        String row = html.substring(waitRule, window);
        assertTrue(row.contains("<strong data-i18n=\"remote_access.lock_waits_for_key_absence\">" + label + "</strong>"));
        assertTrue(row.contains("<span id=\"raLockWaitsForKeyDetail\" data-i18n=\"remote_access.lock_waits_for_key_absence_desc\">"
                + desc + "</span>"));
        assertTrue(row.contains("<input type=\"checkbox\" id=\"raLockWaitsForKeyAbsence\" aria-label=\"" + label
                + "\" data-i18n-attr=\"aria-label:remote_access.lock_waits_for_key_absence\" aria-describedby=\"raLockWaitsForKeyDetail\" disabled>"));
        assertFalse(row.contains(" checked"));
        assertTrue(desc.startsWith("Off: phones lock as soon as every active phone is away"));
        assertTrue(desc.contains("Protects against a key left in the car"));
        String script = readRepositoryFile(SCRIPT);
        assertTrue(script.contains("        'raKeyFobLockSamples', 'raLockWaitsForKeyAbsence',\n"));
        assertTrue(script.contains("el('raLockWaitsForKeyAbsence').checked = config.lockWaitsForKeyAbsence === true;"));
        assertTrue(script.contains("el('raLockWaitsForKeyAbsence').checked = custom.lockWaitsForKeyAbsence === true;"));
        assertTrue(script.contains("            el('raLockWaitsForKeyAbsence').checked = false;\n"));
        assertEquals(2, count(script, "lockWaitsForKeyAbsence: el('raLockWaitsForKeyAbsence').checked,"));
        assertTrue(script.contains("config.lockWaitsForKeyAbsence = custom.lockWaitsForKeyAbsence === true;"));
        assertTrue(script.contains("el('raLockWaitsForKeyAbsence').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobLock;"));
        // The lock rule text follows the switch.
        assertTrue(script.contains("var waits = el('raAdvancedMode').checked && el('raLockWaitsForKeyAbsence').checked;"));
        String nowait = remoteAccess.getString("lock_keyfob_priority_phone_nowait_detail");
        assertTrue(script.contains("tr('lock_keyfob_priority_phone_nowait_detail', '" + nowait + "')"));
        assertTrue(nowait.contains("a fresh keyfob detection or a key found inside blocks it"));
    }

    @Test
    public void keyfobWindowDefaultsToFiveSecondsAndTheResultCountSelectIsAdvanced() throws Exception {
        String html = readRepositoryFile(PAGE);
        JSONObject remoteAccess = new JSONObject(readRepositoryFile(EN)).getJSONObject("remote_access");
        int window = html.indexOf("<div class=\"ra-keyfob-window ra-advanced\">");
        int status = html.indexOf("<div class=\"ra-keyfob-status\"", window);
        assertTrue(window > 0 && status > window);
        String block = html.substring(window, status);
        assertTrue(block.contains("id=\"raKeyFobLockSeconds\" min=\"5\" max=\"300\" step=\"5\" value=\"5\""));
        assertTrue(block.contains("<span data-i18n=\"remote_access.keyfob_lock_samples\">Absence results required</span>"));
        assertTrue(block.contains("<select id=\"raKeyFobLockSamples\" aria-describedby=\"raKeyFobLockSamplesHint\" disabled>"));
        assertTrue(block.contains("<option value=\"1\" selected>1</option>"));
        assertTrue(block.contains("<option value=\"2\">2</option>"));
        assertTrue(block.contains("<option value=\"3\">3</option>"));
        assertFalse(block.contains("<option value=\"4\""));
        assertEquals("Absence results required", remoteAccess.getString("keyfob_lock_samples"));
        assertEquals(SAMPLES_HINT, remoteAccess.getString("keyfob_lock_samples_hint"));
        for (String key : new String[] {"keyfob_lock_samples_hint", "keyfob_lock_window_hint"})
            assertTrue(key, block.contains("data-i18n=\"remote_access." + key + "\">" + remoteAccess.getString(key) + "<"));
        assertTrue(remoteAccess.getString("keyfob_lock_window_hint").endsWith("Normal mode uses 5 seconds."));
        assertEquals(1, count(html, "id=\"raKeyFobLockSamples\""));
        String script = readRepositoryFile(SCRIPT);
        assertTrue(script.contains("var KEYFOB_LOCK_DEFAULTS = { windowMs: 5000, samples: 1 };"));
        assertTrue(script.contains("el('raKeyFobLockSeconds').value = (config.keyFobLockWindowMs || KEYFOB_LOCK_DEFAULTS.windowMs) / 1000;"));
        assertTrue(script.contains("el('raKeyFobLockSamples').value = String(lockSamplesValue(config));"));
        assertTrue(script.contains("el('raKeyFobLockSamples').value = String(lockSamplesValue(custom));"));
        assertTrue(script.contains("            el('raKeyFobLockSeconds').value = 5;\n"));
        assertTrue(script.contains("            el('raKeyFobLockSamples').value = '1';\n"));
        assertEquals(2, count(script, "keyFobLockSamples: Number(el('raKeyFobLockSamples').value),"));
        assertTrue(script.contains("config.keyFobLockSamples = lockSamplesValue(custom);"));
        assertTrue(script.contains("config.keyFobLockWindowMs = custom.keyFobLockWindowMs || KEYFOB_LOCK_DEFAULTS.windowMs;"));
        assertTrue(script.contains("if ([1, 2, 3].indexOf(config.keyFobLockSamples) < 0)\n"
                + "            throw new Error(tr('error_keyfob_samples', 'Choose 1, 2 or 3 keyfob absence results.'));"));
        assertTrue(script.contains("el('raKeyFobLockSamples').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobLock;"));
        assertTrue(script.contains("' ' + (el('raAdvancedMode').checked ? el('raKeyFobLockSeconds').value || '5' : '5') +"));
        assertTrue(script.contains("tr('keyfob_lock_absence_priority_desc', '"
                + remoteAccess.getString("keyfob_lock_absence_priority_desc") + "')"));
        // The armed-absence progress counts completed searches against the required count.
        assertTrue(script.contains("presence.sampleCount + '/' + presence.requiredSamples :"));
        // The old 10 s keyfob defaults are gone from the script.
        for (String old : new String[] {"keyFobLockWindowMs || 10000", "value || '10' : '10'", "el('raKeyFobLockSeconds').value = 10;",
                "presence.windowMs || 10000"})
            assertEquals(old, 0, count(script, old));
    }

    @Test
    public void englishCatalogCarriesTheNewAndUpdatedTexts() throws Exception {
        JSONObject remoteAccess = new JSONObject(readRepositoryFile(EN)).getJSONObject("remote_access");
        assertEquals(AWAY_TO_NEAR_HINT, remoteAccess.getString("unlock_trigger_away_to_near_hint"));
        assertEquals("Unlock after a stable Near signal, including first connection or reconnection. No prior Away signal is required.",
                remoteAccess.getString("unlock_trigger_near_hint"));
        assertTrue(remoteAccess.getString("keyfob_lock_absence_priority_desc").contains("(by default the first one)"));
        assertTrue(remoteAccess.getString("keyfob_lock_absence_priority_desc").contains("Searches run every 5 seconds"));
        assertFalse(remoteAccess.getString("keyfob_lock_window_hint").contains("10 seconds. Normal mode uses 10 seconds"));
        for (String key : new String[] {"keyfob_lock_samples", "keyfob_lock_samples_hint", "lock_waits_for_key_absence",
                "lock_waits_for_key_absence_desc", "lock_keyfob_priority_phone_nowait_detail",
                "lock_keyfob_priority_phone_detail", "keyfob_lock_window", "unlock_trigger"})
            assertTrue(key, remoteAccess.has(key));
    }

    @Test
    public void runbookHasTheSnappyDefaultsSectionAndNoOldDefaultSentences() throws IOException {
        String docs = readRepositoryFile(DOCS);
        int migration = docs.indexOf("*Migration of saved timing.*");
        int section = docs.indexOf("**Snappy defaults** (20 Oct).");
        int railSet = docs.indexOf("**Changing the rail set**", section);
        assertTrue(migration > 0 && section > migration && railSet > section);
        String text = docs.substring(section, railSet);
        for (String row : new String[] {"| Phone unlock |", "| Phone departure by disconnection |",
                "| Phone lock and the key search |", "| Absent roster phones |", "| Fob unlock |", "| Fob lock |"})
            assertEquals(row, 1, count(text, row));
        for (String needle : new String[] {"| Rail | Default | Advanced option | What the option protects against |",
                "`unlockTrigger` `near`", "Away → Near", "DISCONNECTION_CONFIRM_READS", "DISCONNECTION_CONFIRM_SPAN_MS",
                "2 not-connected native reads at least 2 s apart", "`lockWaitsForKeyAbsence` off",
                "**Phone locking waits for the key search**", "ABSENT_PHONE_GRACE_MS", "after 15 s",
                "`keyFobUnlockTrigger` `near`", "**Unlock as soon as the key is detected** off",
                "`keyFobLockSamples` 1", "`keyFobLockWindowMs` 5000", "**Absence results required** 2–3",
                "**Keyfob locking window**", "`unlockTriggerExplicit`", "`keyFobLockExplicit`",
                "A phone left inside the car unlocking it on signal fluctuation",
                "A single missed read locking a key left inside", "relock grace and keyfob quiet period", "`phoneVetoesLock`"})
            assertTrue(needle, text.contains(needle));
        // Sentences that described the old defaults were updated.
        for (String old : new String[] {"at least three distinct fresh native reads",
                "running for at least 60 seconds", "active physical-key searches must confirm absence before a phone departure may",
                "The default confirmation window is ten seconds", "Normal mode uses ten seconds",
                "two at the default ten seconds", "**Away → Near**\nretains the existing behavior"})
            assertFalse(old, docs.contains(old));
        assertTrue(docs.contains("**Near**,\nthe default since 20 Oct"));
        assertTrue(docs.contains("at least two distinct fresh native reads spanning the\ngreater of two seconds"));
        assertTrue(docs.contains("**Absence results required** (`keyFobLockSamples`, 1–3, default 1, 20 Oct)"));
    }

    @Test
    public void handlerChangesOnlyTheCanLockCallSiteAndThePresenceConstruction() throws IOException {
        String handler = readRepositoryFile(HANDLER);
        String consensus = method(handler, "    private static boolean lockConsensus(boolean phoneDeparture, long now) {");
        int veto = consensus.indexOf("if (fobDetection && (hasFreshKeyFobApproach(now) || hasFreshKeyFobPresence(now))) return false;");
        int call = consensus.indexOf("return MultiPhoneProximityPolicy.canLock(phoneDeparture,\n"
                + "                !phoneDeparture || (fobConfigured && RemoteAccessSettings.lockWaitsForKeyAbsence(effectiveConfig)),\n"
                + "                fobConfigured && keyFobPresence.allowsLock(now), phoneDepartureStates(), now,\n"
                + "                effectiveConfig.optLong(\"staleMs\"));");
        // The fresh key approach/presence veto stays, before the consensus.
        assertTrue(veto > 0 && call > veto);
        assertEquals(1, count(handler, "RemoteAccessSettings.lockWaitsForKeyAbsence("));
        String reset = method(handler, "    private static void reset(JSONObject next) throws Exception {");
        assertTrue(reset.contains("            keyFobPresence = new KeyFobPresenceEvaluator(effectiveConfig.optLong(\"revision\"),\n"
                + "                    effectiveConfig.optLong(\"keyFobLockWindowMs\", 10_000), RemoteAccessSettings.keyFobLockSamples(effectiveConfig));"));
        assertEquals(1, count(handler, "keyFobLockSamples"));
        assertEquals(1, count(handler, "new KeyFobPresenceEvaluator("));
        // The fob-near hunk of reset() is untouched.
        assertTrue(reset.contains("            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(\n"
                + "                    effectiveConfig.optString(\"keyFobUnlockTrigger\", \"near\")), effectiveConfig.optLong(\"revision\"),\n"
                + "                    effectiveConfig.optBoolean(\"keyFobLockEnabled\"));"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        return source.substring(start, source.indexOf("\n    }\n", start));
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
