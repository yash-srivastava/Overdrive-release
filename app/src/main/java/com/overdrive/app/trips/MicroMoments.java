package com.overdrive.app.trips;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Container for micro-moment analysis extracted from trip telemetry.
 * Includes launch profiles, coast-before-brake events, and pedal smoothness windows.
 */
public class MicroMoments {

    // ==================== Inner Classes ====================

    /**
     * A launch event: speed transitions from 0 to >5 km/h.
     * Records the accel pedal curve for the first 10 seconds.
     */
    public static class LaunchProfile {
        public long startTime;
        public int peakAccelPercent;
        public int[] accelCurve;       // Accel pedal % samples over first 10s

        public LaunchProfile() {
            this.accelCurve = new int[0];
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("startTime", startTime);
                json.put("peakAccelPercent", peakAccelPercent);
                JSONArray curve = new JSONArray();
                for (int v : accelCurve) {
                    curve.put(v);
                }
                json.put("accelCurve", curve);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static LaunchProfile fromJson(JSONObject json) {
            LaunchProfile lp = new LaunchProfile();
            lp.startTime = json.optLong("startTime", 0);
            lp.peakAccelPercent = json.optInt("peakAccelPercent", 0);
            JSONArray curve = json.optJSONArray("accelCurve");
            if (curve != null) {
                lp.accelCurve = new int[curve.length()];
                for (int i = 0; i < curve.length(); i++) {
                    lp.accelCurve[i] = curve.optInt(i, 0);
                }
            }
            return lp;
        }
    }

    /**
     * A coast-before-brake event: accel pedal drops to 0, then brake rises above 0.
     * Records the coast gap duration and speed at brake application.
     */
    public static class CoastBrakeEvent {
        public long coastGapMs;
        public int speedAtBrake;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("coastGapMs", coastGapMs);
                json.put("speedAtBrake", speedAtBrake);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static CoastBrakeEvent fromJson(JSONObject json) {
            CoastBrakeEvent ev = new CoastBrakeEvent();
            ev.coastGapMs = json.optLong("coastGapMs", 0);
            ev.speedAtBrake = json.optInt("speedAtBrake", 0);
            return ev;
        }
    }

    /**
     * A pedal smoothness measurement window.
     * Records the standard deviation of accel pedal % over a 10-second window.
     */
    public static class PedalSmoothnessWindow {
        public long startTime;
        public double stdDev;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("startTime", startTime);
                json.put("stdDev", stdDev);
            } catch (Exception e) {
                // ignore
            }
            return json;
        }

        public static PedalSmoothnessWindow fromJson(JSONObject json) {
            PedalSmoothnessWindow w = new PedalSmoothnessWindow();
            w.startTime = json.optLong("startTime", 0);
            w.stdDev = json.optDouble("stdDev", 0.0);
            return w;
        }
    }

    // ==================== Fields ====================

    public List<LaunchProfile> launches;
    public List<CoastBrakeEvent> coastBrakeEvents;
    public List<PedalSmoothnessWindow> smoothnessWindows;

    public MicroMoments() {
        this.launches = new ArrayList<>();
        this.coastBrakeEvents = new ArrayList<>();
        this.smoothnessWindows = new ArrayList<>();
    }

    // ==================== Aggregate Metrics ====================

    /**
     * Average peak accel pedal % across all launch profiles.
     */
    public double getAvgLaunchAggressiveness() {
        if (launches.isEmpty()) return 0.0;
        double sum = 0;
        for (LaunchProfile lp : launches) {
            sum += lp.peakAccelPercent;
        }
        return sum / launches.size();
    }

    /**
     * Average coast gap in seconds across all coast-brake events.
     */
    public double getAvgCoastGapSeconds() {
        if (coastBrakeEvents.isEmpty()) return 0.0;
        double sum = 0;
        for (CoastBrakeEvent ev : coastBrakeEvents) {
            sum += ev.coastGapMs;
        }
        return (sum / coastBrakeEvents.size()) / 1000.0;
    }

    /**
     * Average pedal smoothness (stddev) across all windows.
     */
    public double getAvgPedalSmoothness() {
        if (smoothnessWindows.isEmpty()) return 0.0;
        double sum = 0;
        for (PedalSmoothnessWindow w : smoothnessWindows) {
            sum += w.stdDev;
        }
        return sum / smoothnessWindows.size();
    }

    // ==================== Serialization ====================

    /**
     * Serialize to JSON.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("launches", toJsonArray(launches, LaunchProfile::toJson));
            json.put("coastBrakeEvents", toJsonArray(coastBrakeEvents, CoastBrakeEvent::toJson));
            json.put("smoothnessWindows", toJsonArray(smoothnessWindows, PedalSmoothnessWindow::toJson));
        } catch (Exception e) {
            // ignore
        }
        return json;
    }

    /**
     * Deserialize from JSON string.
     */
    public static MicroMoments fromJson(String jsonStr) {
        MicroMoments mm = new MicroMoments();
        if (jsonStr == null || jsonStr.isEmpty()) return mm;
        try {
            JSONObject json = new JSONObject(jsonStr);

            appendFromJsonArray(json.optJSONArray("launches"), mm.launches, LaunchProfile::fromJson);
            appendFromJsonArray(json.optJSONArray("coastBrakeEvents"),
                    mm.coastBrakeEvents, CoastBrakeEvent::fromJson);
            appendFromJsonArray(json.optJSONArray("smoothnessWindows"),
                    mm.smoothnessWindows, PedalSmoothnessWindow::fromJson);
        } catch (Exception e) {
            // Retain entries decoded before the failure.
        }
        return mm;
    }

    private static <T> JSONArray toJsonArray(List<T> items, Function<T, JSONObject> encode) {
        JSONArray array = new JSONArray();
        for (T item : items) {
            array.put(encode.apply(item));
        }
        return array;
    }

    private static <T> void appendFromJsonArray(JSONArray array, List<T> target,
                                               Function<JSONObject, T> decode) throws JSONException {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            target.add(decode.apply(array.getJSONObject(i)));
        }
    }
}
