package com.overdrive.app.monitor;

import android.os.SystemClock;

import com.overdrive.app.trips.ElevationEstimator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Live altitude, road grade and the elevation profile behind the car, for the RoadSense widget.
 *
 * GPS altitude is the noisiest GNSS axis, so: fixes with a poor vertical accuracy are dropped, a
 * short moving median smooths the rest, distance comes from successive fixes while moving, and the
 * grade is the least-squares slope of the smoothed altitude against distance over the last stretch
 * of road. A datum flip (MSL ↔ ellipsoid) or a long gap starts the altitude track afresh, so a step
 * in the datum is never shown as a slope. Fed from {@link GpsMonitor} on its update thread.
 */
public final class TerrainTracker {

    private static final TerrainTracker INSTANCE = new TerrainTracker();

    public static TerrainTracker get() { return INSTANCE; }

    /** Altitude samples with a worse 1-sigma vertical accuracy (m) are left out. */
    private static final double MAX_VERTICAL_ACCURACY_M = ElevationEstimator.MAX_VERTICAL_ACCURACY_M;
    private static final long MEDIAN_MS = 5_000;
    /** Longer than this without a fix (a tunnel, a park) starts afresh. */
    private static final long GAP_RESET_MS = 30_000;
    /** Profile spacing along the road, and how much of it is kept (2 km). */
    private static final double STEP_M = 25.0;
    private static final int PROFILE_POINTS = 80;
    /** The grade is fitted over this much road, from at least this much. */
    private static final double GRADE_WINDOW_M = 150.0;
    private static final double GRADE_MIN_SPAN_M = 90.0;
    private static final double MAX_GRADE_PCT = 30.0;
    /** Below this GPS speed (m/s) the car counts as stopped: no distance, no grade. */
    private static final float MOVING_MS = 2.5f;
    /** Nothing is shown once the car hasn't moved for this long. */
    private static final long FRESH_MS = 60_000;

    // Same time-window median as trip scoring, with the terrain-specific duration.
    private final ElevationEstimator.MedianWindow recent = new ElevationEstimator.MedianWindow(MEDIAN_MS);
    // (distance m, smoothed altitude) every STEP_M of road
    private final ArrayDeque<double[]> profile = new ArrayDeque<>();
    private double distM;
    private double nextStepAt;
    private double smoothed = Double.NaN;
    private double gradePct = Double.NaN;
    private boolean msl;
    private boolean seeded;
    private double lastLat;
    private double lastLon;
    private long lastFixAt;
    private long movedAt;
    private boolean moving;

    private TerrainTracker() {}

    public synchronized void onFix(GpsMonitor.GpsFixSnapshot f) {
        if (f == null || f.loadedFromCache || !f.hasLocation()) return;
        long now = SystemClock.elapsedRealtime();
        if (seeded && now - lastFixAt > GAP_RESET_MS) reset();
        // a datum flip is a step in altitude, not a slope
        if (seeded && f.altitudeIsMsl != msl) resetAltitude();
        moving = f.speed >= MOVING_MS;
        if (moving) movedAt = now;
        if (seeded && moving) {
            double d = metres(lastLat, lastLon, f.latitude, f.longitude);
            double dt = Math.max(0.2, (now - lastFixAt) / 1000.0);
            // a jump the speed can't explain is a bad fix, not distance driven
            if (d <= f.speed * dt * 2.0 + 30.0) distM += d;
        }
        seeded = true;
        lastLat = f.latitude; lastLon = f.longitude; lastFixAt = now;
        msl = f.altitudeIsMsl;

        double alt = f.altitude;
        boolean altKnown = !Double.isNaN(alt) && Math.abs(alt) < 9000.0 && !(alt == 0.0 && f.verticalAccuracy == 0f);
        boolean accurate = f.verticalAccuracy <= 0f || f.verticalAccuracy <= MAX_VERTICAL_ACCURACY_M;
        if (!altKnown || !accurate) return;
        recent.add(now, alt, 0.0);
        smoothed = recent.medianAltitude();

        if (profile.isEmpty() || (moving && distM >= nextStepAt)) {
            profile.addLast(new double[] { distM, smoothed });
            nextStepAt = distM + STEP_M;
            while (profile.size() > PROFILE_POINTS) profile.removeFirst();
            gradePct = fitGrade();
        }
    }

    // least-squares slope of altitude over the last stretch of road, as a percentage
    private double fitGrade() {
        double sx = 0, sy = 0, sxx = 0, sxy = 0, first = Double.NaN;
        int n = 0;
        Iterator<double[]> it = profile.descendingIterator();
        while (it.hasNext()) {
            double[] p = it.next();
            if (distM - p[0] > GRADE_WINDOW_M) break;
            sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1];
            first = p[0];
            n++;
        }
        if (n < 4 || distM - first < GRADE_MIN_SPAN_M) return Double.NaN;
        double den = n * sxx - sx * sx;
        if (Math.abs(den) < 1e-6) return Double.NaN;
        double slope = (n * sxy - sx * sy) / den;
        return Math.max(-MAX_GRADE_PCT, Math.min(MAX_GRADE_PCT, slope * 100.0));
    }

    private void resetAltitude() {
        recent.clear();
        profile.clear();
        smoothed = Double.NaN;
        gradePct = Double.NaN;
        nextStepAt = distM;
    }

    private void reset() {
        resetAltitude();
        distM = 0;
        nextStepAt = 0;
        seeded = false;
    }

    /**
     * {altM, msl, gradePct (null unless moving with enough road behind), distM, stepM, profile} with
     * the profile as altitudes in metres oldest first; null when there's nothing recent to show.
     */
    public synchronized JSONObject toJson() {
        long now = SystemClock.elapsedRealtime();
        if (!seeded || Double.isNaN(smoothed) || movedAt == 0 || now - movedAt > FRESH_MS || now - lastFixAt > GAP_RESET_MS) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("altM", Math.round(smoothed));
            o.put("msl", msl);
            boolean live = moving && now - movedAt < 5_000 && !Double.isNaN(gradePct);
            o.put("gradePct", live ? Math.round(gradePct * 10.0) / 10.0 : JSONObject.NULL);
            o.put("distM", Math.round(distM));
            o.put("stepM", STEP_M);
            JSONArray p = new JSONArray();
            for (double[] s : profile) p.put(Math.round(s[1] * 10.0) / 10.0);
            o.put("profile", p);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
