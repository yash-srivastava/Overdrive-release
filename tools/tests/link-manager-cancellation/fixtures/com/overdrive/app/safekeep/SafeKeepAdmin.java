package com.overdrive.app.safekeep;

import org.json.JSONObject;

/** In-memory destination storage observation only. */
final class SafeKeepAdmin {
    static boolean addDestination(String id, String type, String name, JSONObject params, String proxy) {
        LinkManagerCancellationTest.recordStorage("admin.addDestination", null);
        return LinkManagerCancellationTest.adminResult();
    }
    static boolean updateDestination(String id, JSONObject patch) {
        LinkManagerCancellationTest.recordUpdate(patch);
        LinkManagerCancellationTest.recordStorage("admin.updateDestination", null);
        return LinkManagerCancellationTest.adminResult();
    }
}
