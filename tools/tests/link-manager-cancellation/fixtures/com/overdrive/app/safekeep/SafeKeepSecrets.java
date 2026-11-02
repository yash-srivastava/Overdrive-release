package com.overdrive.app.safekeep;

import org.json.JSONObject;

/** In-memory observation boundary; no credentials are written to disk. */
final class SafeKeepSecrets {
    static JSONObject get(String id) { return new JSONObject(); }
    static boolean put(String id, JSONObject value) {
        LinkManagerCancellationTest.recordStorage("secrets.put", value);
        return true;
    }
    static boolean remove(String id) { throw new AssertionError("Unexpected storage rollback"); }
}
