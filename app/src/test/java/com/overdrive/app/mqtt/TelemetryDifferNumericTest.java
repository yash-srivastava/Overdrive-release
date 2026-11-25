package com.overdrive.app.mqtt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetryDifferNumericTest {
    @Test
    public void numericBucketsRetainDeadbandsAndExactIntegers() throws Exception {
        TelemetryDiffer differ = new TelemetryDiffer();
        JSONObject original = new JSONObject().put("soc", 12.31)
                .put("unknown", Long.MAX_VALUE).put("other", 1.251);
        differ.markAllSent(original, 1);
        JSONObject same = new JSONObject().put("soc", 12.34)
                .put("unknown", Long.MAX_VALUE).put("other", 1.254);
        assertTrue(differ.changedKeys(same).isEmpty());
        JSONObject changed = new JSONObject().put("soc", 12.36)
                .put("unknown", Long.MAX_VALUE - 1).put("other", 1.256);
        assertEquals(new HashSet<>(Arrays.asList("soc", "unknown", "other")),
                differ.changedKeys(changed));
    }

    @Test
    public void preservesLegacyCrossTypeAndNullSemantics() throws Exception {
        TelemetryDiffer differ = new TelemetryDiffer();
        JSONObject numeric = new JSONObject().put("soc", 1).put("unknown", 3L)
                .put("fraction", 1.0).put("cabin_temp", JSONObject.NULL)
                .put("array", new JSONArray().put(1).put(2));
        JSONObject text = new JSONObject().put("soc", "q10").put("unknown", "3")
                .put("fraction", "q100").put("cabin_temp", "∅").put("array", "[1,2]");
        differ.markAllSent(numeric, 1);
        assertTrue(differ.changedKeys(text).isEmpty());
        differ.markAllSent(text, 2);
        assertTrue(differ.changedKeys(numeric).isEmpty());
        assertTrue(differ.changedKeys(new JSONObject().put("fraction", 1)).contains("fraction"));
        assertTrue(differ.changedKeys(new JSONObject().put("cabin_temp", 20)).contains("cabin_temp"));
        differ.markAllSent(new JSONObject().put("cabin_temp", 20), 3);
        assertTrue(differ.changedKeys(new JSONObject().put("cabin_temp", JSONObject.NULL)).contains("cabin_temp"));
        assertFalse(differ.changedKeys(new JSONObject()).contains("cabin_temp"));
    }

    @Test
    public void randomizedSnapshotsMatchLegacyStringReference() throws Exception {
        TelemetryDiffer differ = new TelemetryDiffer();
        Map<String, String> sent = new HashMap<>();
        String[] keys = {"soc", "lat", "cell_v_max", "gear", "unknown", "other",
                "cabin_temp", "utc", "vd_timestamp", "gps_utc"};
        Object[] values = {JSONObject.NULL, true, false, "q10", "3", "∅", "[1,2]", "", 1,
                1L, 1.0, -1, -1.0, Long.MAX_VALUE, Long.MIN_VALUE, (short) 3, (byte) 3,
                new JSONArray().put(1).put(2), new JSONArray().put(2).put(1)};
        Random random = new Random(93701);
        for (int i = 1; i <= 5000; i++) {
            JSONObject snapshot = new JSONObject();
            Set<String> expected = new HashSet<>();
            for (String key : keys) {
                if (random.nextInt(5) == 0) continue;
                Object value = random.nextBoolean() ? values[random.nextInt(values.length)]
                        : (random.nextInt(20001) - 10000) / 1000.0;
                snapshot.put(key, value);
                String previous = sent.get(key);
                if (!TelemetryFieldCatalog.EXCLUDED.contains(key)
                        && (previous == null || !previous.equals(legacy(key, value)))) {
                    expected.add(key);
                }
            }
            assertEquals("cycle " + i, expected, differ.changedKeys(snapshot));
            switch (random.nextInt(4)) {
                case 0:
                    differ.markAllSent(snapshot, i);
                    for (Iterator<String> it = snapshot.keys(); it.hasNext(); ) {
                        String key = it.next();
                        sent.put(key, legacy(key, snapshot.opt(key)));
                    }
                    assertEquals(i, differ.lastSendTimeMs());
                    break;
                case 1:
                    Set<String> subset = new HashSet<>(Arrays.asList("soc", "other", "cabin_temp"));
                    differ.markKeysSent(snapshot, subset, i);
                    for (String key : subset) sent.put(key, legacy(key, snapshot.opt(key)));
                    assertEquals(i, differ.lastSendTimeMs());
                    break;
                case 2:
                    if (i % 7 == 0) {
                        differ.reset();
                        sent.clear();
                        assertEquals(0, differ.lastSendTimeMs());
                    }
                    break;
                default:
                    // Unsent snapshots must not advance the last-sent state.
                    break;
            }
        }
    }

    private static String legacy(String key, Object value) {
        if (value == null || value == JSONObject.NULL) return "∅";
        if (value instanceof Number) {
            Number number = (Number) value;
            double d = number.doubleValue();
            double step = TelemetryFieldCatalog.precisionFor(key);
            if (step > 0) return "q" + Math.round(d / step);
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                return Long.toString(number.longValue());
            }
            return "q" + Math.round(d * 100);
        }
        return value.toString();
    }
}
