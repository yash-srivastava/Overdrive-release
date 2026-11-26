package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/** The opt-in is off by default, saved like the other rules and wired on the page. */
public class ParkedPhoneAudioSettingsTest {
    @Test public void defaultsOffAndRoundTrips() throws Exception {
        JSONObject defaults = RemoteAccessSettings.defaults();
        assertFalse(RemoteAccessSettings.parkedPhoneAudioOff(defaults));
        JSONObject on = RemoteAccessSettings.merge(defaults,
                new JSONObject().put(RemoteAccessSettings.PARKED_PHONE_AUDIO_OFF_KEY, true));
        assertTrue(RemoteAccessSettings.parkedPhoneAudioOff(on));
        JSONObject persisted = RemoteAccessSettings.fromPersisted(on);
        assertTrue(RemoteAccessSettings.parkedPhoneAudioOff(persisted));
        assertFalse(RemoteAccessSettings.parkedPhoneAudioOff(null));
    }

    @Test public void rejectsANonBoolean() throws Exception {
        try {
            RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                    new JSONObject().put(RemoteAccessSettings.PARKED_PHONE_AUDIO_OFF_KEY, "yes"));
            fail("non-boolean accepted");
        } catch (IllegalArgumentException expected) {
            // Same validation as every other rule switch.
        }
    }

    @Test public void pageSavesTheSwitchWithTheRules() throws IOException {
        String page = read("app/src/main/assets/web/local/remote-access.html");
        String script = read("app/src/main/assets/web/shared/remote-access.js");
        String strings = read("app/src/main/assets/web/i18n/en.json");
        assertTrue(page.contains("id=\"raParkedPhoneAudioOff\""));
        assertTrue(script.contains("parkedPhoneAudioOff: el('raParkedPhoneAudioOff').checked,"));
        assertTrue(script.contains("el('raParkedPhoneAudioOff').checked = config.parkedPhoneAudioOff === true;"));
        assertTrue(script.contains("changes.parkedPhoneAudioOff = pending.parkedPhoneAudioOff;"));
        assertTrue(strings.contains("\"parked_phone_audio_off\":"));
        assertTrue(strings.contains("\"parked_phone_audio_off_desc\":"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path candidate : new Path[]{current.resolve(relativePath),
                    current.resolve(relativePath.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(candidate))
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Missing " + relativePath);
    }
}
