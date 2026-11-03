package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.junit.Test;

/** Pins the display-only translation codes to the English catalog the page renders them with. */
public class RemoteAccessMessageCodesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    @Test
    public void everyEnglishServerTemplateClassifiesBackToItsKey() throws Exception {
        JSONObject server = catalog("en").getJSONObject("remote_access").getJSONObject("server");
        int checked = 0;
        for (Iterator<String> keys = server.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (key.startsWith("door_")) continue;
            String template = server.getString(key);
            if (key.startsWith("phrase_")) {
                assertEquals(template, key, RemoteAccessMessageCodes.phraseKey(template));
            } else {
                String sample = fill(template);
                RemoteAccessMessageCodes.Code code = RemoteAccessMessageCodes.classify(sample);
                assertNotNull(sample, code);
                assertEquals(sample, key, code.key);
            }
            checked++;
        }
        assertTrue(checked > 100);
    }

    @Test
    public void hebrewCatalogHasEveryServerKeyWithTheSamePlaceholders() throws Exception {
        JSONObject en = catalog("en").getJSONObject("remote_access").getJSONObject("server");
        JSONObject he = catalog("he").getJSONObject("remote_access").getJSONObject("server");
        for (Iterator<String> keys = en.keys(); keys.hasNext(); ) {
            String key = keys.next();
            assertTrue(key, he.has(key));
            assertEquals(key, placeholders(en.getString(key)), placeholders(he.getString(key)));
        }
    }

    @Test
    public void nestedPhrasesAndDetailAreReported() throws Exception {
        RemoteAccessMessageCodes.Code code = RemoteAccessMessageCodes.classify(
                "Local command failed (bus error); cloud command not sent: settings changed (path=a rail=b)");
        assertNotNull(code);
        assertEquals("local_failed_cloud_not_sent", code.key);
        assertEquals("bus error", code.args.get("failure"));
        assertEquals("settings changed", code.args.get("skipped"));
        assertEquals(" (path=a rail=b)", code.args.get("detail"));
        assertEquals("phrase_settings_changed", code.argKeys.get("skipped"));
        assertFalse(code.argKeys.containsKey("failure"));

        code = RemoteAccessMessageCodes.classify("Local command unconfirmed; cloud command failed: "
                + DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET);
        assertNotNull(code);
        assertEquals("", code.args.get("detail"));
        assertEquals("phrase_local_at_target", code.argKeys.get("cloud"));
    }

    @Test
    public void annotateAddsFieldsOnlyForKnownTexts() throws Exception {
        JSONObject known = RemoteAccessMessageCodes.annotate(new JSONObject().put("reason", "Cooldown active"),
                "reason", "Cooldown active");
        assertEquals("Cooldown active", known.getString("reason"));
        assertEquals("cooldown_active", known.getString("reasonKey"));
        assertFalse(known.has("reasonArgs"));

        JSONObject relock = RemoteAccessMessageCodes.annotate(new JSONObject(), "message",
                "Relock grace: a door must open or 45 s must pass after the proximity unlock");
        assertEquals("relock_grace", relock.getString("messageKey"));
        assertEquals("45", relock.getJSONObject("messageArgs").getString("seconds"));

        JSONObject unknown = RemoteAccessMessageCodes.annotate(new JSONObject(), "error", "Rate limited, retry in 3 s");
        assertEquals(0, unknown.length());
        assertNull(RemoteAccessMessageCodes.classify(null));
        assertNull(RemoteAccessMessageCodes.annotate(null, "error", "Cooldown active"));
    }

    private static String fill(String template) {
        return template.replace("{detail}", " (rail=r1)").replace("{seconds}", "10").replace("{minutes}", "3")
                .replace("{name}", "Pixel 8").replace("{doors}", "hood open, trunk unknown")
                .replace("{outcome}", "collector started/refreshed; active=true").replace("{failure}", "bus error")
                .replace("{cloud}", "timeout").replace("{skipped}", "cloud fallback switched off");
    }

    private static String placeholders(String text) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return names.toString();
    }

    private static JSONObject catalog(String locale) throws Exception {
        String relative = "app/src/main/assets/web/i18n/" + locale + ".json";
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path candidate : new Path[] {current.resolve(relative), current.resolve(relative.substring(4))}) {
                if (Files.isRegularFile(candidate)) {
                    return new JSONObject(new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8));
                }
            }
            current = current.getParent();
        }
        throw new java.io.FileNotFoundException(relative);
    }
}
