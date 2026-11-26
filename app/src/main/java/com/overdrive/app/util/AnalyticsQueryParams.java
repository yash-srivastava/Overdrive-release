package com.overdrive.app.util;

import java.util.Map;

/** Shared raw query handling for the trip and charging analytics APIs. */
public final class AnalyticsQueryParams {

    private AnalyticsQueryParams() {}

    /** Merge raw query pairs into the supplied map; later pairs replace earlier ones. */
    public static void parseQueryParams(String queryString, Map<String, String> params) {
        if (queryString == null || queryString.isEmpty()) return;
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = eq < pair.length() - 1 ? pair.substring(eq + 1) : "";
                params.put(key, value);
            }
        }
    }

    /** Return the integer parameter, or the caller's default if missing or invalid. */
    public static int getIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Return the long parameter (including epoch milliseconds), or the caller's default. */
    public static long getLongParam(Map<String, String> params, String key, long defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
