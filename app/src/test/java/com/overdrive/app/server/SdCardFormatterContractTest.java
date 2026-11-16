package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression contracts for the destructive SD-card format route. */
public class SdCardFormatterContractTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void formatterUsesStorageManagerAndAcceptsDashcamUnmountedVolumes() throws Exception {
        String formatter = read("src/main/java/com/overdrive/app/storage/SdCardFormatter.java");
        assertTrue(formatter.contains("command(\"sm\", \"format\", volumeId)"));
        assertTrue(formatter.contains("\"unmounted\".equals(fields[1])"));
        assertFalse(formatter.contains("\"vdc\""));
        assertFalse(formatter.contains("\"volume\", \"format\""));
    }

    @Test
    public void destructiveFormatRequiresJwtOnlyAuthentication() throws Exception {
        String server = read("src/main/java/com/overdrive/app/server/HttpServer.java");
        int route = server.indexOf("boolean destructiveStorageFormatRequest");
        assertTrue(route >= 0);
        int auth = server.indexOf("AuthMiddleware.checkJwtOnly(path, cookieHeader, authHeader, out)", route);
        assertTrue(auth > route);
        assertTrue(server.substring(route, auth).contains("/api/storage/external/format"));
    }
}
