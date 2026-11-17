package com.overdrive.app.launcher.layout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ClusterLayoutStoreTest {
    private JSONObject preset() throws Exception {
        return new JSONObject().put("version", 1).put("id", "drive").put("name", "Drive")
                .put("slots", new JSONArray()
                        .put(new JSONObject().put("id", "map").put("package", "com.overdrive.app")
                                .put("x", 0).put("y", 0).put("width", 5000).put("height", 10000).put("dpi", 160))
                        .put(new JSONObject().put("id", "music").put("package", "com.example.music")
                                .put("x", 5000).put("y", 0).put("width", 5000).put("height", 10000).put("dpi", 200)));
    }

    @Test public void savedDefinitionsRoundTripWithoutLosingDisplayGeometry() throws Exception {
        LayoutPreset original = ClusterLayoutStore.fromJson(preset());
        LayoutPreset restored = ClusterLayoutStore.fromJson(ClusterLayoutStore.toJson(original));
        assertEquals("drive", restored.id);
        assertEquals("Drive", restored.name);
        assertEquals(2, restored.slots.size());
        assertEquals("com.example.music", restored.slots.get(1).packageName);
        assertEquals(200, restored.slots.get(1).dpi);
        assertArrayEquals(new int[] {640, 0, 1280, 720}, restored.slots.get(1).pixelBounds(1280, 720));
    }

    @Test public void numbersAndIdentitiesAreNotCoercedFromStringsOrBooleans() throws Exception {
        JSONObject numberString = preset();
        numberString.getJSONArray("slots").getJSONObject(0).put("x", "0");
        rejected(numberString);
        rejected(preset().put("name", 123));
        rejected(preset().put("version", true));
        rejected(preset().put("version", "1"));
    }

    @Test public void fractionalAndOverflowingCoordinatesAreRejected() throws Exception {
        for (Object invalid : new Object[] {0.5, 4_294_967_296L, JSONObject.NULL}) {
            JSONObject request = preset();
            request.getJSONArray("slots").getJSONObject(0).put("x", invalid);
            rejected(request);
        }
    }

    @Test public void deserializedPresetsStillEnforceNonOverlappingUniqueApps() throws Exception {
        JSONObject overlap = preset();
        overlap.getJSONArray("slots").getJSONObject(1).put("x", 4000);
        rejected(overlap);
        JSONObject duplicate = preset();
        duplicate.getJSONArray("slots").getJSONObject(1).put("package", "com.overdrive.app");
        rejected(duplicate);
        JSONObject unsafePackage = preset();
        unsafePackage.getJSONArray("slots").getJSONObject(1).put("package", "com.example.music;reboot");
        rejected(unsafePackage);
    }

    private void rejected(JSONObject request) throws Exception {
        try {
            ClusterLayoutStore.fromJson(request);
            fail("Unsafe preset was accepted: " + request);
        } catch (IllegalArgumentException expected) {
            // The public request boundary must reject the definition before any display work.
        }
    }
}
