package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level contracts for a destructive operation (no device formatting). */
public class SdCardFormatterContractTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void formatUsesPublicVolumeAndVerifiesNewMountedUuid() throws Exception {
        String formatter = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(formatter.contains("command(\"sm\", \"format\", target.volumeId)"));
        assertTrue(formatter.contains("!target.uuid.equals(current.uuid)"));
        assertTrue(formatter.contains("\"mounted\".equals(current.state)"));
        assertTrue(formatter.contains("System.nanoTime() >= deadline"));
        assertTrue(formatter.contains("checkOpenCardFiles(target.uuid)"));
        assertTrue(formatter.contains("Files.readSymbolicLink"));
        assertFalse(formatter.contains("\"vdc\""));
    }

    @Test
    public void destructiveRouteNeedsExplicitOriginAndJwt() throws Exception {
        String server = read("src/main/java/com/overdrive/app/server/HttpServer.java");
        int route = server.indexOf("boolean destructiveStorageFormatRequest");
        assertTrue(route >= 0);
        int check = server.indexOf("originHeader == null", route);
        int auth = server.indexOf("AuthMiddleware.checkJwtOnly(path, cookieHeader, authHeader, out)", check);
        assertTrue(check > route && auth > check);
        assertTrue(server.substring(route, auth).contains("GenAiRequestSecurity.isAllowedOrigin"));
        String handler = read("src/main/java/com/overdrive/app/server/ExternalStorageApiHandler.java");
        assertTrue(handler.contains("request.getString(\"expectedUuid\")"));
        assertTrue(handler.contains("request.optBoolean(\"confirmed\", false)"));
    }

    @Test
    public void confirmationNamesCardAndHebrewContainsAllFormatStrings() throws Exception {
        String js = read("src/main/assets/web/shared/storage.js");
        assertTrue(js.contains("{ card: target.label }"));
        assertTrue(js.contains("expectedUuid: target.uuid"));
        String en = read("src/main/assets/web/i18n/en.json");
        String he = read("src/main/assets/web/i18n/he.json");
        String[] keys = { "sd_format_title", "sd_format_name", "sd_format_desc", "sd_format_warning",
                "sd_format_button", "sd_format_confirm_title", "sd_format_confirm_body",
                "sd_format_confirm", "sd_format_progress", "sd_format_failed",
                "sd_format_request_failed", "sd_format_target_failed" };
        for (String key : keys) {
            assertTrue("English missing " + key, en.contains("\"" + key + "\""));
            assertTrue("Hebrew missing " + key, he.contains("\"" + key + "\""));
        }
    }
}
