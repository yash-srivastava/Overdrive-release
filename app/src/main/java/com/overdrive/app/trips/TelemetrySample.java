package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Immutable value object for a single telemetry reading.
 * Thread-safe by design — all fields are final.
 *
 * Serialized to compact JSON keys for storage efficiency in .jsonl.gz files.
 */
public class TelemetrySample {

    public final long timestampMs;
    public final int speedKmh;
    public final int accelPedalPercent;   // 0-100
    public final int brakePedalPercent;   // 0-100
    public final boolean brakePedalPressed;
    public final int gearMode;            // 1=P, 2=R, 3=N, 4=D, 5=M, 6=S
    public final double lat;
    public final double lon;
    public final double altitude;
    /** Reported 1-sigma GPS vertical accuracy in metres; 0.0 = unreported
     *  (older telemetry files, or a HAL that doesn't populate it). Used to
     *  gate elevation accumulation (see ElevationEstimator). */
    public final double verticalAccuracyM;
    /** True when {@link #altitude} is mean-sea-level (geoid-corrected) rather
     *  than WGS84-ellipsoidal. The two differ by the local geoid undulation
     *  (tens of metres); a mid-trip source flip must reset elevation deltas. */
    public final boolean altitudeIsMsl;
    /** Where {@link #speedKmh} came from. Only DI5 writes the non-vehicle values: a tick without a
     *  vehicle speed records a checked live GPS speed ({@link #SPEED_GPS}) or 0 ({@link #SPEED_NONE}). */
    public final int speedSource;
    /** DI5: the speed is a vehicle reading but the pedals were unknown on this tick. */
    public final boolean pedalsUnknown;
    /** DI5: the position is not from a live fix (disk cache, stale or inaccurate), so distance skips it. */
    public final boolean gpsNotLive;
    /** DI5: a vehicle speed of 0 the car reported as valid; older DI5 journals hold only unreported zeros. */
    public final boolean stopConfirmed;
    /** DI5 GPS rows: the speed for the trip's peak (the lower of two fixes) when it differs from
     *  {@link #speedKmh}, the fix's own speed that distance integrates; -1 when the same. */
    public final int peakSpeedKmh;
    /** DI5: the fix's horizontal accuracy in metres; 0 when unreported (older rows). */
    public final float horizontalAccuracyM;
    /** DI5: metres a slow GPS-only stretch landed at this row (counted once, here); 0 otherwise. */
    public final float distanceLandedM;

    public static final int SPEED_VEHICLE = 0;
    public static final int SPEED_GPS = 1;
    public static final int SPEED_NONE = 2;
    /** The GPS fix's own wall-clock time (epoch ms); 0 = unreported. Distinct from
     *  {@link #timestampMs}, which is when the sample was RECORDED. A fix arrives
     *  already ~0.7s old, and seconds old through turns, so anything aligning this
     *  track against another recording needs when the position was TAKEN. */
    public final long gpsFixTimeUtc;

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl) {
        this(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent, brakePedalPressed,
                gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                SPEED_VEHICLE, false);
    }

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl,
                           int speedSource, boolean pedalsUnknown) {
        this(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent, brakePedalPressed,
                gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                speedSource, pedalsUnknown, false, false, -1, 0f);
    }

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl,
                           int speedSource, boolean pedalsUnknown, boolean gpsNotLive,
                           boolean stopConfirmed, int peakSpeedKmh, float horizontalAccuracyM) {
        this(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent, brakePedalPressed,
                gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                speedSource, pedalsUnknown, gpsNotLive, stopConfirmed, peakSpeedKmh, horizontalAccuracyM, 0f);
    }

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl,
                           int speedSource, boolean pedalsUnknown, boolean gpsNotLive,
                           boolean stopConfirmed, int peakSpeedKmh, float horizontalAccuracyM,
                           float distanceLandedM) {
        this(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent, brakePedalPressed,
                gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                speedSource, pedalsUnknown, gpsNotLive, stopConfirmed, peakSpeedKmh, horizontalAccuracyM,
                distanceLandedM, 0L);
    }

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl,
                           int speedSource, boolean pedalsUnknown, boolean gpsNotLive,
                           boolean stopConfirmed, int peakSpeedKmh, float horizontalAccuracyM,
                           float distanceLandedM, long gpsFixTimeUtc) {
        this.timestampMs = timestampMs;
        this.speedKmh = speedKmh;
        this.accelPedalPercent = accelPedalPercent;
        this.brakePedalPercent = brakePedalPercent;
        this.brakePedalPressed = brakePedalPressed;
        this.gearMode = gearMode;
        this.lat = lat;
        this.lon = lon;
        this.altitude = altitude;
        this.verticalAccuracyM = verticalAccuracyM;
        this.altitudeIsMsl = altitudeIsMsl;
        this.speedSource = speedSource;
        this.pedalsUnknown = pedalsUnknown;
        this.gpsNotLive = gpsNotLive;
        this.stopConfirmed = stopConfirmed;
        this.peakSpeedKmh = peakSpeedKmh;
        this.horizontalAccuracyM = horizontalAccuracyM;
        this.distanceLandedM = distanceLandedM;
        this.gpsFixTimeUtc = gpsFixTimeUtc;
    }

    /** DI5: the row's speed is a reading, vehicle or GPS. A vehicle 0 without {@link #stopConfirmed}
     *  is an older DI5 journal's unreported zero, not a stop. */
    public boolean hasDiLink5Speed() {
        return speedSource == SPEED_GPS
                || (speedSource == SPEED_VEHICLE && (speedKmh > 0 || stopConfirmed));
    }

    /** DI5: usable by the score engine: a vehicle reading with known pedals. */
    public boolean isDiLink5ScoringEligible() {
        return isScoringEligible() && (speedKmh > 0 || stopConfirmed);
    }

    /** DI5: the row shows the car moving (any vehicle speed, as the collector quarantines one frozen
     *  in P, or a checked GPS 10 km/h), which a P read off the unchecked gear signal cannot overrule. */
    public boolean isDiLink5Moving() {
        if (!hasDiLink5Speed()) return false;
        return speedSource == SPEED_GPS ? peakKmh() >= 10 : speedKmh > 0;
    }

    /** DI5: in P and not shown clearly moving, so the car is parked whatever the speed reads. */
    public boolean isDiLink5Parked() {
        return gearMode == 1 && !isDiLink5Moving();
    }

    /** The same row at another time, with no landed distance (a stand-in for a 5 Hz sample). */
    public TelemetrySample atTimestamp(long timestampMs) {
        return new TelemetrySample(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent,
                brakePedalPressed, gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                speedSource, pedalsUnknown, gpsNotLive, stopConfirmed, peakSpeedKmh, horizontalAccuracyM, 0f,
                gpsFixTimeUtc);
    }

    /** The same row with another landed distance. */
    public TelemetrySample withDistanceLandedM(float landedM) {
        return new TelemetrySample(timestampMs, speedKmh, accelPedalPercent, brakePedalPercent,
                brakePedalPressed, gearMode, lat, lon, altitude, verticalAccuracyM, altitudeIsMsl,
                speedSource, pedalsUnknown, gpsNotLive, stopConfirmed, peakSpeedKmh, horizontalAccuracyM,
                landedM, gpsFixTimeUtc);
    }

    /** DI5: the row shows the car standing: a valid vehicle 0 or a GPS 0 the positions confirmed. */
    public boolean isDiLink5Stopped() {
        return hasDiLink5Speed() && (speedSource == SPEED_GPS ? peakKmh() : speedKmh) == 0;
    }

    /** DI5 GPS rows: the speed that may set the trip's peak. */
    public int peakKmh() {
        return peakSpeedKmh >= 0 ? peakSpeedKmh : speedKmh;
    }

    /** A vehicle speed reading (every non-DI5 sample is one). */
    public boolean hasVehicleSpeed() {
        return speedSource == SPEED_VEHICLE;
    }

    /** Usable by the score engine: a vehicle speed with known pedals. */
    public boolean isScoringEligible() {
        return speedSource == SPEED_VEHICLE && !pedalsUnknown;
    }

    /**
     * Serialize to JSON with compact keys for storage.
     * Keys: t, s, a, b, bp, g, la, lo, al, va, am, ss, pu, nl, sz, sp, ha, dl
     * "va"/"am"/"ss"/"pu"/"nl"/"sz"/"sp"/"ha"/"dl" are written only when informative (va, ha, dl > 0 /
     * ss != 0 / sp >= 0 / the rest true)
     * to keep the common-case line size unchanged; fromJson() defaults cover absence.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("t", timestampMs);
            json.put("s", speedKmh);
            json.put("a", accelPedalPercent);
            json.put("b", brakePedalPercent);
            json.put("bp", brakePedalPressed);
            json.put("g", gearMode);
            json.put("la", lat);
            json.put("lo", lon);
            json.put("al", altitude);
            if (verticalAccuracyM > 0) json.put("va", verticalAccuracyM);
            // Omitted when unreported so existing files stay byte-identical in shape.
            if (gpsFixTimeUtc > 0) json.put("gt", gpsFixTimeUtc);
            if (altitudeIsMsl) json.put("am", true);
            if (speedSource != SPEED_VEHICLE) json.put("ss", speedSource);
            if (pedalsUnknown) json.put("pu", true);
            if (gpsNotLive) json.put("nl", true);
            if (stopConfirmed) json.put("sz", true);
            if (peakSpeedKmh >= 0) json.put("sp", peakSpeedKmh);
            if (horizontalAccuracyM > 0) json.put("ha", horizontalAccuracyM);
            if (distanceLandedM > 0) json.put("dl", distanceLandedM);
        } catch (Exception e) {
            // JSONObject.put only throws on null key, which won't happen here
        }
        return json;
    }

    /**
     * Deserialize from compact JSON.
     */
    public static TelemetrySample fromJson(JSONObject json) {
        return new TelemetrySample(
                json.optLong("t", 0),
                json.optInt("s", 0),
                json.optInt("a", 0),
                json.optInt("b", 0),
                json.optBoolean("bp", false),
                json.optInt("g", 1),
                json.optDouble("la", 0.0),
                json.optDouble("lo", 0.0),
                json.optDouble("al", 0.0),
                json.optDouble("va", 0.0),
                json.optBoolean("am", false),
                json.optInt("ss", SPEED_VEHICLE),
                json.optBoolean("pu", false),
                json.optBoolean("nl", false),
                json.optBoolean("sz", false),
                json.optInt("sp", -1),
                (float) json.optDouble("ha", 0.0),
                (float) json.optDouble("dl", 0.0),
                json.optLong("gt", 0L)
        );
    }
}
