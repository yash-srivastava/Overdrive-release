package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression coverage for the run-2 hardening: every HTML/JSON response must
 * carry a CSP, X-Frame-Options: DENY and nosniff (the events.js XSS executed
 * because no CSP was emitted anywhere).
 */
public class SecurityHeadersTest {

    @Test
    public void sendHtmlCarriesCspXfoNosniff() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendHtml(out, "<html><body>hi</body></html>");
        String resp = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(resp.contains("Content-Security-Policy: default-src 'self'"));
        assertTrue(resp.contains("object-src 'none'"));
        assertTrue(resp.contains("frame-ancestors 'none'"));
        assertTrue(resp.contains("X-Frame-Options: DENY"));
        assertTrue(resp.contains("X-Content-Type-Options: nosniff"));
    }

    @Test
    public void sendJsonCarriesSecurityHeaders() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendJson(out, "{\"ok\":true}");
        String resp = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(resp.contains("Content-Security-Policy:"));
        assertTrue(resp.contains("X-Frame-Options: DENY"));
    }

    @Test
    public void staticFileServingAddsHeaders() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/server/HttpServer.java")), StandardCharsets.UTF_8);
        assertTrue(s.contains("HttpResponse.SECURITY_HEADERS"));
        assertFalse("no response may lack the frame/nosniff headers",
                s.contains("headers.append(\"Connection: close\\r\\n\\r\\n\")") && !s.contains("X-Frame-Options: DENY\\r\\n\")"));
    }
}
