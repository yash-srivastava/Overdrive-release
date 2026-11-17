package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.BydVehicleData;

import org.json.JSONObject;

/**
 * Immutable snapshot of vehicle state received from BYD cloud MQTT push.
 * Thread-safe — created via Builder, read from any thread.
 */
public final class VehicleCloudSnapshot {

    // Staleness thresholds
    public static final long LOCK_STATE_MAX_AGE_MS = 5 * 60 * 1000;       // 5 min
    public static final long TELEMETRY_MAX_AGE_MS = 10 * 60 * 1000;       // 10 min
    public static final long CONNECTION_HEALTH_MAX_AGE_MS = 2 * 60 * 1000; // 2 min

    // BYD cloud sentinel values
    private static final int SENTINEL_INT = -1;
    private static final double SENTINEL_TEMP = -129.0;

    // Lock state enum (matches SDK: 1=UNLOCKED, 2=LOCKED)
    public static final int LOCK_UNKNOWN = -1;
    public static final int LOCK_UNAVAILABLE = 0;
    public static final int LOCK_UNLOCKED = 1;
    public static final int LOCK_LOCKED = 2;

    // Online state
    public static final int ONLINE_UNKNOWN = -1;
    public static final int ONLINE = 1;
    public static final int OFFLINE = 2;

    // ── Locks ───────────────────────────────────────────────────────────
    public final int leftFrontDoorLock;
    public final int rightFrontDoorLock;
    public final int leftRearDoorLock;
    public final int rightRearDoorLock;

    // ── Battery / Energy (mergeable) ────────────────────────────────────
    public final double socPercent;          // elecPercent
    public final int elecRangeKm;            // enduranceMileage / enduranceMileageV2
    public final int fuelRangeKm;            // oilEndurance (PHEV)
    public final double fuelPercent;         // oilPercent (PHEV)
    public final int totalMileageKm;         // totalMileage / totalMileageV2

    // ── Charging (mergeable) ────────────────────────────────────────────
    public final int chargingState;          // chargeState (cloud enum)
    public final int remainingHours;
    public final int remainingMinutes;

    // ── Seat climate (cloud composite command source) ──────────────────
    // Raw cloud status values: front seats 1=off/2=low/3=high; steering
    // wheel -1=on/1=off. Other values mean the cloud did not supply state.
    public final int mainSeatHeatState;
    public final int mainSeatVentilationState;
    public final int copilotSeatHeatState;
    public final int copilotSeatVentilationState;
    /**
     * Steering-wheel heat, raw cloud domain -1=on / 1=off. Absent state is
     * {@link #WHEEL_HEAT_UNKNOWN}, NOT the shared -1 sentinel: -1 is a valid
     * "on" here, so reusing it would make a car that never reported the wheel
     * indistinguishable from one reporting the heater on.
     */
    public final int steeringWheelHeatState;

    /** Absent steering-wheel state. 0 is outside the cloud's {-1, 1} domain. */
    public static final int WHEEL_HEAT_UNKNOWN = 0;

    /** Traction-battery preconditioning: 0=off, >0=heating, SENTINEL when not reported. */
    public final int batteryHeatState;

    // ── Temperature (mergeable) ─────────────────────────────────────────
    public final double insideTempC;         // tempInCar
    public final double outsideTempC;        // tempOutCar

    // ── Air Quality (mergeable) ─────────────────────────────────────────
    public final int pm25Inside;
    public final int pm25Outside;

    // ── Doors (snapshot only, not merged) ───────────────────────────────
    public final int leftFrontDoor;          // 0=CLOSED, 1=OPEN
    public final int rightFrontDoor;
    public final int leftRearDoor;
    public final int rightRearDoor;
    public final int trunkLid;

    // ── Windows (snapshot only, not merged) ─────────────────────────────
    public final int leftFrontWindow;        // 1=CLOSED, 2=OPEN
    public final int rightFrontWindow;
    public final int leftRearWindow;
    public final int rightRearWindow;
    public final int skylight;

    // ── Vehicle state (snapshot only) ───────────────────────────────────
    public final int onlineState;
    public final int vehicleState;
    public final int powerGear;

    // ── Timestamps ──────────────────────────────────────────────────────
    public final long vehicleInfoTimestamp;   // normalized 'time' field from vehicleInfo (epoch ms)
    public final long receivedAt;             // System.currentTimeMillis() when processed
    public final long insideTempObservedAt;   // source observation time, independent of receipt

    private VehicleCloudSnapshot(Builder b) {
        this.leftFrontDoorLock = b.leftFrontDoorLock;
        this.rightFrontDoorLock = b.rightFrontDoorLock;
        this.leftRearDoorLock = b.leftRearDoorLock;
        this.rightRearDoorLock = b.rightRearDoorLock;
        this.socPercent = b.socPercent;
        this.elecRangeKm = b.elecRangeKm;
        this.fuelRangeKm = b.fuelRangeKm;
        this.fuelPercent = b.fuelPercent;
        this.totalMileageKm = b.totalMileageKm;
        this.chargingState = b.chargingState;
        this.remainingHours = b.remainingHours;
        this.remainingMinutes = b.remainingMinutes;
        this.mainSeatHeatState = b.mainSeatHeatState;
        this.mainSeatVentilationState = b.mainSeatVentilationState;
        this.copilotSeatHeatState = b.copilotSeatHeatState;
        this.copilotSeatVentilationState = b.copilotSeatVentilationState;
        this.steeringWheelHeatState = b.steeringWheelHeatState;
        this.batteryHeatState = b.batteryHeatState;
        this.insideTempC = b.insideTempC;
        this.outsideTempC = b.outsideTempC;
        this.pm25Inside = b.pm25Inside;
        this.pm25Outside = b.pm25Outside;
        this.leftFrontDoor = b.leftFrontDoor;
        this.rightFrontDoor = b.rightFrontDoor;
        this.leftRearDoor = b.leftRearDoor;
        this.rightRearDoor = b.rightRearDoor;
        this.trunkLid = b.trunkLid;
        this.leftFrontWindow = b.leftFrontWindow;
        this.rightFrontWindow = b.rightFrontWindow;
        this.leftRearWindow = b.leftRearWindow;
        this.rightRearWindow = b.rightRearWindow;
        this.skylight = b.skylight;
        this.onlineState = b.onlineState;
        this.vehicleState = b.vehicleState;
        this.powerGear = b.powerGear;
        this.vehicleInfoTimestamp = b.vehicleInfoTimestamp;
        this.receivedAt = b.receivedAt;
        this.insideTempObservedAt = b.insideTempObservedAt;
    }

    // ── Freshness checks ────────────────────────────────────────────────

    public boolean isLockStateFresh() {
        return (System.currentTimeMillis() - receivedAt) < LOCK_STATE_MAX_AGE_MS;
    }

    public boolean isTelemetryFresh() {
        return (System.currentTimeMillis() - receivedAt) < TELEMETRY_MAX_AGE_MS;
    }

    public boolean isConnectionHealthy() {
        return (System.currentTimeMillis() - receivedAt) < CONNECTION_HEALTH_MAX_AGE_MS;
    }

    /**
     * Window state is usable only while the vehicle is online and the source observation is fresh.
     */
    public boolean isWindowStateFresh() {
        if (!isOnline()) return false;
        long observedAt = vehicleInfoTimestamp > 0
                ? Math.min(vehicleInfoTimestamp, receivedAt) : receivedAt;
        long ageMs = System.currentTimeMillis() - observedAt;
        return ageMs >= 0 && ageMs < CONNECTION_HEALTH_MAX_AGE_MS;
    }

    public boolean isOnline() {
        return onlineState == ONLINE;
    }

    // ── Lock state helpers ──────────────────────────────────────────────

    public boolean isAllLocked() {
        return leftFrontDoorLock == LOCK_LOCKED
                && rightFrontDoorLock == LOCK_LOCKED
                && leftRearDoorLock == LOCK_LOCKED
                && rightRearDoorLock == LOCK_LOCKED;
    }

    public boolean isAnyUnlocked() {
        return leftFrontDoorLock == LOCK_UNLOCKED
                || rightFrontDoorLock == LOCK_UNLOCKED
                || leftRearDoorLock == LOCK_UNLOCKED
                || rightRearDoorLock == LOCK_UNLOCKED;
    }

    public boolean hasValidLockState() {
        return leftFrontDoorLock > LOCK_UNAVAILABLE
                || rightFrontDoorLock > LOCK_UNAVAILABLE
                || leftRearDoorLock > LOCK_UNAVAILABLE
                || rightRearDoorLock > LOCK_UNAVAILABLE;
    }

    // ── Mergeable field validity checks ─────────────────────────────────
    // Each check validates against BYD sentinels AND online state

    public boolean hasSoc() {
        // BYD sends 0 when it has no reading (e.g. head-unit Wi-Fi dropped the cloud link), even
        // while onlineState says online (#467), so a cloud 0 is never a battery level.
        return socPercent > 0 && socPercent <= 100;
    }

    public boolean hasElecRange() {
        // A 0 km next to a missing SoC is the same no-reading payload; a PHEV on its buffer
        // still reports 0 km alongside a real SoC.
        return elecRangeKm > 0 || (elecRangeKm == 0 && isOnline() && hasSoc());
    }

    public boolean hasFuelRange() {
        return fuelRangeKm >= 0 && fuelRangeKm != SENTINEL_INT;
    }

    public boolean hasFuelPercent() {
        return fuelPercent >= 0 && fuelPercent != SENTINEL_INT;
    }

    public boolean hasTotalMileage() {
        return totalMileageKm > 0;
    }

    public boolean hasChargingState() {
        return chargingState != SENTINEL_INT;
    }

    public boolean hasRemainingHours() {
        return remainingHours >= 0;
    }

    public boolean hasRemainingMinutes() {
        return remainingMinutes >= 0;
    }

    /** True when every front-seat channel needed by VENTILATIONHEATING is known. */
    public boolean hasCompleteFrontSeatClimateState() {
        return isSeatStatus(mainSeatHeatState)
                && isSeatStatus(mainSeatVentilationState)
                && isSeatStatus(copilotSeatHeatState)
                && isSeatStatus(copilotSeatVentilationState);
    }

    /** Returns front seat state in the app's UI scale (off=0, low=1, high=2). */
    public int[] frontSeatClimateUiState() {
        if (!hasCompleteFrontSeatClimateState()) return null;
        return new int[] {
                mainSeatHeatState - 1,
                mainSeatVentilationState - 1,
                copilotSeatHeatState - 1,
                copilotSeatVentilationState - 1
        };
    }

    /** The cloud reports steering-wheel heat as -1=on and 1=off. */
    public boolean hasSteeringWheelHeatState() {
        return steeringWheelHeatState == -1 || steeringWheelHeatState == 1;
    }

    /** True when the cloud reported traction-battery preconditioning at all. */
    public boolean hasBatteryHeatState() {
        return batteryHeatState >= 0;
    }

    /** Return the VENTILATIONHEATING wire value (1=on, 3=off), or -1 if unknown. */
    public int steeringWheelHeatWireState() {
        if (steeringWheelHeatState == -1) return 1;
        if (steeringWheelHeatState == 1) return 3;
        return -1;
    }

    /** Cloud MQTT/REST state may safely seed a remote composite for two minutes. */
    public boolean isSeatClimateFresh() {
        return System.currentTimeMillis() - receivedAt < CONNECTION_HEALTH_MAX_AGE_MS;
    }

    private static boolean isSeatStatus(int value) {
        return value >= 1 && value <= 3;
    }

    /**
     * Shared cloud cabin-temperature validity contract. The lower bound matches the reference
     * readers, the upper bound rejects positive CAN sentinels, and zero is unavailable only when
     * the payload explicitly reports the vehicle offline. Unknown online state must not erase a
     * legitimate 0 C observation.
     */
    public static boolean isValidCabinTempC(double value, int onlineState) {
        return Double.isFinite(value) && value > -128.0 && value <= 90.0
                && (value != 0.0 || onlineState != OFFLINE);
    }

    /** Compatibility overload for callers that have a definitive binary online state. */
    public static boolean isValidCabinTempC(double value, boolean online) {
        return isValidCabinTempC(value, online ? ONLINE : OFFLINE);
    }

    public boolean hasInsideTemp() {
        return isValidCabinTempC(insideTempC, onlineState);
    }

    public boolean hasFreshInsideTemp() {
        if (!hasInsideTemp() || insideTempObservedAt <= 0L) return false;
        long ageMs = System.currentTimeMillis() - insideTempObservedAt;
        return ageMs >= 0L && ageMs <= BydVehicleData.CABIN_TEMP_MAX_AGE_MS;
    }

    public boolean hasOutsideTemp() {
        return !Double.isNaN(outsideTempC) && !(outsideTempC == 0.0 && !isOnline());
    }

    public boolean hasPm25Inside() {
        return pm25Inside > 0 || (pm25Inside == 0 && isOnline());
    }

    public boolean hasPm25Outside() {
        return pm25Outside > 0 || (pm25Outside == 0 && isOnline());
    }

    // ── Charging state translation ──────────────────────────────────────
    // Cloud: -1=UNKNOWN, 0=NOT_CHARGING, 1=CHARGING, 15=CONNECTED
    // SDK:   0=ready, 1=charging, 2=finished, 3=discharging

    public int getChargingStateAsSdk() {
        switch (chargingState) {
            case 0:  return 0; // NOT_CHARGING → ready
            case 1:  return 1; // CHARGING → charging
            case 15: return 0; // CONNECTED (not charging) → ready
            default: return -1;
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    public static Builder fromVehicleInfo(JSONObject vi) {
        return fromVehicleInfo(vi, null);
    }

    /**
     * Parse a vehicleInfo JSON into a Builder.
     * Optionally merges HVAC fields from a separate JSON.
     * All sentinel values are preserved as-is — validity checks happen at read time.
     */
    public static Builder fromVehicleInfo(JSONObject vi, JSONObject hvac) {
        Builder b = new Builder();
        if (vi == null) return b;

        // Locks
        b.leftFrontDoorLock = vi.optInt("leftFrontDoorLock", LOCK_UNKNOWN);
        b.rightFrontDoorLock = vi.optInt("rightFrontDoorLock", LOCK_UNKNOWN);
        b.leftRearDoorLock = vi.optInt("leftRearDoorLock", LOCK_UNKNOWN);
        b.rightRearDoorLock = vi.optInt("rightRearDoorLock", LOCK_UNKNOWN);

        // Battery / Energy
        b.socPercent = vi.optDouble("elecPercent", SENTINEL_INT);
        int rangeV2 = vi.optInt("enduranceMileageV2", SENTINEL_INT);
        b.elecRangeKm = rangeV2 != SENTINEL_INT ? rangeV2 : vi.optInt("enduranceMileage", SENTINEL_INT);
        b.fuelRangeKm = vi.optInt("oilEndurance", SENTINEL_INT);
        b.fuelPercent = vi.optDouble("oilPercent", SENTINEL_INT);
        int mileV2 = vi.optInt("totalMileageV2", 0);
        b.totalMileageKm = mileV2 > 0 ? mileV2 : vi.optInt("totalMileage", 0);

        // Charging
        int cs = vi.optInt("chargeState", SENTINEL_INT);
        b.chargingState = cs != SENTINEL_INT ? cs : vi.optInt("chargingState", SENTINEL_INT);
        b.remainingHours = vi.optInt("remainingHours", SENTINEL_INT);
        b.remainingMinutes = vi.optInt("remainingMinutes", SENTINEL_INT);
        b.mainSeatHeatState = cloudStatusInt(vi, hvac, "mainSeatHeatState");
        b.mainSeatVentilationState = cloudStatusInt(vi, hvac, "mainSeatVentilationState");
        b.copilotSeatHeatState = cloudStatusInt(vi, hvac, "copilotSeatHeatState");
        b.copilotSeatVentilationState = cloudStatusInt(vi, hvac, "copilotSeatVentilationState");
        // Absent must NOT collapse onto -1 here — that is this field's "on".
        b.steeringWheelHeatState = cloudStatusIntOrDefault(WHEEL_HEAT_UNKNOWN, vi, hvac,
                "steeringWheelHeatState", "stearingWheelHeatState");
        b.batteryHeatState = cloudStatusInt(vi, hvac, "batteryHeatState");

        // Temperature — from vehicleInfo or HVAC
        b.insideTempC = vi.optDouble("tempInCar", SENTINEL_TEMP);
        b.outsideTempC = vi.optDouble("tempOutCar", Double.NaN);
        if (hvac != null) {
            double hvacIn = hvac.optDouble("tempInCar", SENTINEL_TEMP);
            int onlineState = vi.optInt("onlineState", ONLINE_UNKNOWN);
            if (isValidCabinTempC(hvacIn, onlineState)
                    && !isValidCabinTempC(b.insideTempC, onlineState)) {
                b.insideTempC = hvacIn;
            }
            double hvacOut = hvac.optDouble("tempOutCar", Double.NaN);
            if (!Double.isNaN(hvacOut) && Double.isNaN(b.outsideTempC)) b.outsideTempC = hvacOut;
        }

        // Air quality
        b.pm25Inside = vi.optInt("pm", SENTINEL_INT);
        b.pm25Outside = vi.optInt("pm25StateOutCar", SENTINEL_INT);
        if (hvac != null) {
            if (b.pm25Inside == SENTINEL_INT) b.pm25Inside = hvac.optInt("pm", SENTINEL_INT);
            if (b.pm25Outside == SENTINEL_INT) b.pm25Outside = hvac.optInt("pm25StateOutCar", SENTINEL_INT);
        }

        // Doors
        b.leftFrontDoor = vi.optInt("leftFrontDoor", SENTINEL_INT);
        b.rightFrontDoor = vi.optInt("rightFrontDoor", SENTINEL_INT);
        b.leftRearDoor = vi.optInt("leftRearDoor", SENTINEL_INT);
        b.rightRearDoor = vi.optInt("rightRearDoor", SENTINEL_INT);
        b.trunkLid = vi.optInt("trunkLid", SENTINEL_INT);

        // Windows
        b.leftFrontWindow = vi.optInt("leftFrontWindow", SENTINEL_INT);
        b.rightFrontWindow = vi.optInt("rightFrontWindow", SENTINEL_INT);
        b.leftRearWindow = vi.optInt("leftRearWindow", SENTINEL_INT);
        b.rightRearWindow = vi.optInt("rightRearWindow", SENTINEL_INT);
        b.skylight = vi.optInt("skylight", SENTINEL_INT);

        // Vehicle state
        b.onlineState = vi.optInt("onlineState", ONLINE_UNKNOWN);
        b.vehicleState = vi.optInt("vehicleState", SENTINEL_INT);
        b.powerGear = vi.optInt("powerGear", SENTINEL_INT);

        // Keep source observation time separate from receipt time. BYD responses have used both
        // epoch seconds and epoch milliseconds; reject other magnitudes and future outliers.
        b.receivedAt = System.currentTimeMillis();
        b.vehicleInfoTimestamp = normalizeSourceTimestampMs(
                vi.optLong("time", 0L), b.receivedAt);
        b.insideTempObservedAt = b.vehicleInfoTimestamp > 0L
                ? Math.min(b.vehicleInfoTimestamp, b.receivedAt)
                : b.receivedAt;

        return b;
    }

    private static long normalizeSourceTimestampMs(long rawTimestamp, long receivedAtMs) {
        if (rawTimestamp < 1_000_000_000L) return 0L;

        long normalized;
        if (rawTimestamp < 100_000_000_000L) {
            if (rawTimestamp > Long.MAX_VALUE / 1000L) return 0L;
            normalized = rawTimestamp * 1000L;
        } else {
            normalized = rawTimestamp;
        }

        // A future source time cannot safely participate in ordering: retaining it as the
        // provider's maximum would reject every real update until wall time caught up. Treat it
        // as timestamp-less instead, so request sequence orders it and receipt time records the
        // observation.
        return normalized <= receivedAtMs ? normalized : 0L;
    }

    /** Read a status field from a direct payload, statusNow wrapper, or HVAC companion payload. */
    private static int cloudStatusInt(JSONObject vehicleInfo, JSONObject hvac, String... keys) {
        return cloudStatusIntOrDefault(SENTINEL_INT, vehicleInfo, hvac, keys);
    }

    /**
     * As {@link #cloudStatusInt} but with a caller-chosen absent value, for fields whose
     * real domain includes the shared -1 sentinel.
     */
    private static int cloudStatusIntOrDefault(int absentValue, JSONObject vehicleInfo,
                                               JSONObject hvac, String... keys) {
        for (JSONObject source : new JSONObject[] { vehicleInfo, statusNow(vehicleInfo), hvac,
                statusNow(hvac) }) {
            if (source == null) continue;
            for (String key : keys) {
                if (source.has(key) && !source.isNull(key)) {
                    Object raw = source.opt(key);
                    if (raw instanceof Number) return ((Number) raw).intValue();
                    try {
                        return Integer.parseInt(String.valueOf(raw));
                    } catch (NumberFormatException ignored) {
                        // Continue to an alternate key/payload.
                    }
                }
            }
        }
        return absentValue;
    }

    private static JSONObject statusNow(JSONObject source) {
        return source == null ? null : source.optJSONObject("statusNow");
    }

    public static final class Builder {
        int leftFrontDoorLock = LOCK_UNKNOWN;
        int rightFrontDoorLock = LOCK_UNKNOWN;
        int leftRearDoorLock = LOCK_UNKNOWN;
        int rightRearDoorLock = LOCK_UNKNOWN;

        double socPercent = SENTINEL_INT;
        int elecRangeKm = SENTINEL_INT;
        int fuelRangeKm = SENTINEL_INT;
        double fuelPercent = SENTINEL_INT;
        int totalMileageKm = 0;

        int chargingState = SENTINEL_INT;
        int remainingHours = SENTINEL_INT;
        int remainingMinutes = SENTINEL_INT;

        int mainSeatHeatState = SENTINEL_INT;
        int mainSeatVentilationState = SENTINEL_INT;
        int copilotSeatHeatState = SENTINEL_INT;
        int copilotSeatVentilationState = SENTINEL_INT;
        int steeringWheelHeatState = WHEEL_HEAT_UNKNOWN;
        int batteryHeatState = SENTINEL_INT;

        double insideTempC = SENTINEL_TEMP;
        double outsideTempC = Double.NaN;

        int pm25Inside = SENTINEL_INT;
        int pm25Outside = SENTINEL_INT;

        int leftFrontDoor = SENTINEL_INT;
        int rightFrontDoor = SENTINEL_INT;
        int leftRearDoor = SENTINEL_INT;
        int rightRearDoor = SENTINEL_INT;
        int trunkLid = SENTINEL_INT;

        int leftFrontWindow = SENTINEL_INT;
        int rightFrontWindow = SENTINEL_INT;
        int leftRearWindow = SENTINEL_INT;
        int rightRearWindow = SENTINEL_INT;
        int skylight = SENTINEL_INT;

        int onlineState = ONLINE_UNKNOWN;
        int vehicleState = SENTINEL_INT;
        int powerGear = SENTINEL_INT;

        long vehicleInfoTimestamp = 0;
        long receivedAt = System.currentTimeMillis();
        long insideTempObservedAt = 0;

        Builder insideTempObservedAt(long observedAtMs) {
            insideTempObservedAt = observedAtMs;
            return this;
        }

        public VehicleCloudSnapshot build() {
            return new VehicleCloudSnapshot(this);
        }
    }
}
