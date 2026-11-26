package com.overdrive.app.server;

import java.net.URI;

/** Browser-origin checks for the credential-bearing GenAI HTTP/WebSocket surface. */
final class GenAiRequestSecurity {
    static final int MAX_CLIENT_FRAME_BYTES = 128 * 1024;

    private GenAiRequestSecurity() {
    }

    static boolean isGenAiPath(String path) {
        return path != null && (path.startsWith("/api/genai/")
                || "/ws/genai".equals(path)
                || "/ws/genai/chat".equals(path));
    }

    /**
     * Native callers do not send Origin. Browser callers must be same-origin
     * with the effective public Host (X-Forwarded-Host when tunneled).
     */
    static boolean isAllowedOrigin(
            String origin, String host, String forwardedHost) {
        if (origin == null || origin.trim().isEmpty()) return true;
        if ("null".equalsIgnoreCase(origin.trim())) return false;
        try {
            URI source = new URI(origin.trim());
            String scheme = source.getScheme();
            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (source.getUserInfo() != null || source.getHost() == null
                    || (source.getPath() != null
                    && !source.getPath().isEmpty()
                    && !"/".equals(source.getPath()))
                    || source.getQuery() != null
                    || source.getFragment() != null) {
                return false;
            }

            String effective = firstHost(
                    forwardedHost == null || forwardedHost.trim().isEmpty()
                            ? host : forwardedHost);
            if (effective.isEmpty()) return false;
            URI target = new URI(scheme + "://" + effective);
            return source.getHost().equalsIgnoreCase(target.getHost())
                    && normalizedPort(source) == normalizedPort(target);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String firstHost(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int comma = trimmed.indexOf(',');
        return (comma >= 0 ? trimmed.substring(0, comma) : trimmed).trim();
    }

    private static int normalizedPort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
