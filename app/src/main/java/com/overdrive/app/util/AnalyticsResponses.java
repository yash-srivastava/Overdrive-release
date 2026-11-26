package com.overdrive.app.util;

import org.json.JSONObject;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared JSON envelope handling for trip and charging analytics. */
public final class AnalyticsResponses {

    private AnalyticsResponses() {}

    /** Invoke a section, stripping transport fields and returning an error stub on failure. */
    public static JSONObject section(Supplier<JSONObject> handler, Consumer<String> warn) {
        JSONObject section;
        try {
            section = handler.get();
        } catch (Exception e) {
            warn.accept("Bootstrap section failed: " + e.getMessage());
            return sectionError(e.getMessage() != null ? e.getMessage() : "section failed");
        }
        if (section == null) return sectionError("empty section");
        section.remove("success");
        section.remove("_status");
        return section;
    }

    private static JSONObject sectionError(String message) {
        JSONObject err = new JSONObject();
        try { err.put("error", message); } catch (Exception ignored) {}
        return err;
    }

    /** Keep any already-published envelope fields and attach the bootstrap failure. */
    public static void completeBootstrapFailure(JSONObject response, JSONObject bootstrap,
                                                Exception failure) {
        try {
            if (!response.has("success")) response.put("success", false);
            if (!response.has("bootstrap")) response.put("bootstrap", bootstrap);
            response.put("error", failure.getMessage() != null ? failure.getMessage() : "bootstrap failed");
        } catch (Exception ignored) {}
    }

    /** Build the API error envelope with its internal HTTP status hint. */
    public static JSONObject error(String message, int status, BiConsumer<String, Throwable> logError) {
        JSONObject response = new JSONObject();
        try {
            response.put("success", false);
            response.put("error", message);
            response.put("_status", status);
        } catch (Exception e) {
            logError.accept("Error building error response", e);
        }
        return response;
    }
}
