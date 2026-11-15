package com.overdrive.app.automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/** Small in-memory, privacy-safe history of automation trigger and execution outcomes. */
final class AutomationExecutionHistory {
    private static final int CAPACITY = 100;
    private static final int MAX_DETAIL_LENGTH = 240;
    private static final Deque<JSONObject> entries = new ArrayDeque<>();

    private AutomationExecutionHistory() { }

    static synchronized void record(
            String automationId, String stage, String status,
            long durationMs, String detail) {
        JSONObject item = new JSONObject();
        try {
            item.put("timestamp", System.currentTimeMillis());
            item.put("automationId", safe(automationId));
            item.put("stage", safe(stage));
            item.put("status", safe(status));
            item.put("durationMs", Math.max(0L, durationMs));
            if (detail != null && !detail.isEmpty()) {
                item.put("detail", detail.substring(0,
                        Math.min(detail.length(), MAX_DETAIL_LENGTH)));
            }
            entries.addFirst(item);
            while (entries.size() > CAPACITY) entries.removeLast();
        } catch (Exception ignored) {
            // Diagnostics must never affect automation execution.
        }
    }

    static synchronized JSONArray snapshot(int limit) {
        JSONArray result = new JSONArray();
        int count = 0;
        for (JSONObject item : entries) {
            if (count++ >= Math.max(0, Math.min(limit, CAPACITY))) break;
            try {
                result.put(new JSONObject(item.toString()));
            } catch (Exception ignored) {
                // Skip a malformed diagnostic record rather than affecting the caller.
            }
        }
        return result;
    }

    static synchronized void clearForTest() {
        entries.clear();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= MAX_DETAIL_LENGTH
                ? value : value.substring(0, MAX_DETAIL_LENGTH);
    }
}
