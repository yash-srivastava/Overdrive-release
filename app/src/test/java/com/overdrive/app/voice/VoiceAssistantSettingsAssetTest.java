package com.overdrive.app.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the opt-in "direct connection when the proxy is unreachable" toggle on the Assistant
 * settings page: the row exists, the script that binds it is cache-busted, the binding saves
 * the right config field, and every web catalog carries both of its strings.
 */
public class VoiceAssistantSettingsAssetTest {

    private static final String HTML = "src/main/assets/web/local/assistant.html";
    private static final String SCRIPT = "src/main/assets/web/shared/voice-assistant.js";
    private static final String I18N = "src/main/assets/web/i18n/";
    private static final String[] CATALOGS = {
            "ar.json", "cs.json", "de.json", "en.json", "es.json", "fr.json", "he.json",
            "hi.json", "it.json", "ja.json", "ko.json", "nb.json", "nl.json", "pt-BR.json",
            "ru.json", "th.json", "tr.json", "vi.json", "zh-CN.json", "zh-TW.json",
    };

    @Test
    public void settingsPageHasTheToggleAndLoadsTheNewScript() throws Exception {
        String html = readProjectFile(HTML);
        assertTrue(html.contains("id=\"voiceRealtimeDirectFallback\""));
        assertTrue(html.contains("voice_assistant.direct_fallback_title"));
        assertTrue(html.contains("voice_assistant.direct_fallback_desc"));
        // v=9: confirm before forgetting voices (v=8 a trained voice per person, v=7 training over
        // music, v=6 wake-word training, v=5 resume-media/location toggles, v=4 pause-media + mic select).
        assertTrue(html.contains("../shared/voice-assistant.js?v=11"));
        assertFalse(html.contains("voice-assistant.js?v=2"));
        assertFalse(html.contains("voice-assistant.js?v=3"));
        assertFalse(html.contains("voice-assistant.js?v=4"));
        assertFalse(html.contains("voice-assistant.js?v=5"));
        assertFalse(html.contains("voice-assistant.js?v=6"));
        assertFalse(html.contains("voice-assistant.js?v=7"));
        assertFalse(html.contains("voice-assistant.js?v=8"));
    }

    @Test
    public void scriptBindsAndRendersTheDirectFallback() throws Exception {
        String js = readProjectFile(SCRIPT);
        assertTrue(js.contains("$('voiceRealtimeDirectFallback')"));
        assertTrue(js.contains("realtimeDirectFallback: !!this.checked"));
        assertTrue(js.contains("config.realtimeDirectFallback"));
        assertTrue(js.contains("direct_fallback_title:"));
        assertTrue(js.contains("direct_fallback_desc:"));
    }

    @Test
    public void everyCatalogCarriesBothStrings() throws Exception {
        assertTrue(CATALOGS.length == 20);
        for (String name : CATALOGS) {
            JSONObject root = new JSONObject(readProjectFile(I18N + name));
            JSONObject voice = root.getJSONObject("voice_assistant");
            assertTrue(name, voice.optString("direct_fallback_title", "").trim().length() > 0);
            assertTrue(name, voice.optString("direct_fallback_desc", "").trim().length() > 0);
        }
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
