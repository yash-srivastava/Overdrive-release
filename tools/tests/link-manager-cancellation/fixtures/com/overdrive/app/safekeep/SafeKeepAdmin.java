package com.overdrive.app.safekeep;

import org.json.JSONObject;

/** In-memory destination storage observation only. */
final class SafeKeepAdmin {
    static boolean addDestination(String id, String type, String name, JSONObject params, String proxy) {
        LinkManagerCancellationTest.recordStorage("admin.addDestination", null);
        return true;
    }
    static boolean updateDestination(String id, JSONObject patch) {
        throw new AssertionError("Relink is outside this new-destination fixture");
    }
}
