package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONObject;
import org.junit.Test;

/** Regression coverage for Czech locale selection and catalogs shipped by the app module. */
public class CzechLocaleSupportTest {

    @Test
    public void czechIsSelectableAndAvailableAcrossAppSurfaces() throws Exception {
        assertTrue(LocaleManager.isSupported("cs"));
        assertEquals("cs", LocaleManager.resolve("cs-CZ"));
        assertEquals("cs", LocaleManager.fromAcceptLanguage(
                "sk;q=1.0,cs-CZ;q=0.9,en;q=0.8"));

        JSONObject web = readJson("app/src/main/assets/web/i18n/cs.json");
        assertEquals("cs", web.getJSONObject("_meta").getString("lang"));
        String dashboard = web.getJSONObject("nav").getString("dashboard");
        assertFalse(dashboard.isEmpty());
        assertFalse("Dashboard".equals(dashboard));

        JSONObject server = readJson("app/src/main/assets/server-i18n/cs.json");
        String detail = server.getJSONObject("errors")
                .getString("invalid_request_with_detail");
        assertTrue(detail.contains("{0}"));

        String nativeStrings = readRepositoryFile(
                "app/src/main/res/values-cs/strings.xml");
        assertTrue(nativeStrings.contains(
                "name=\"language_picker_title\">Jazyk</string>"));

        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");
        assertTrue(core.contains("Čeština"));
        assertTrue(core.contains("n === i && i >= 2 && i <= 4"));

        String appLocaleConfig = readRepositoryFile(
                "app/src/main/res/xml/locales_config.xml");
        assertTrue(appLocaleConfig.contains("android:name=\"cs\""));
        // The separately versioned companion's unchanged contracts live under
        // tools/tests/companion-launcher-locales and require its actual checkout.
    }

    private static JSONObject readJson(String relativePath) throws Exception {
        return new JSONObject(readRepositoryFile(relativePath));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
