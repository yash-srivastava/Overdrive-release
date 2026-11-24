package com.overdrive.app.byd;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Front electric vents (per-side mode, per-vent direction) and airflow mode names. A mode is
 * selected by writing 1 to its own flag; the car clears the others. Direction is one paired
 * write of 128 + degrees. Electric vents exist when the self-study marker is 2, 3 or 4.
 */
public final class AcVentControl {

    /** Minimal HAL surface, so the read/write logic is testable without a car. */
    public interface Hal {
        /** Integer read of one feature id; {@link Integer#MIN_VALUE} when it did not answer. */
        int get(int featureId);
        /** Single-value write; SDK result code, 0 accepted. */
        int set(int featureId, int value);
        /** Paired write in one {@code set()} call; SDK result code, 0 accepted. */
        int setPair(int[] featureIds, int[] values);
    }

    public static final int SIDE_DRIVER = 1;
    public static final int SIDE_PASSENGER = 2;

    public static final String MODE_FREE = "free";
    public static final String MODE_FOCUS = "focus";
    public static final String MODE_AVOID = "avoid";
    public static final String MODE_SWING = "swing";
    public static final String MODE_OFF = "off";

    /** Vents in the OEM's index order: driver left, driver right, passenger left, passenger right. */
    public static final int VENT_COUNT = 4;
    public static final String[] VENT_NAMES =
            {"driver_left", "driver_right", "passenger_left", "passenger_right"};

    static final int ANGLE_OFFSET = 128;
    /** Readbacks further than this from straight ahead are not positions (raw 38 = vent not open). */
    static final int MAX_PLAUSIBLE_DEGREES = 60;

    /** {hMin, hMax, vMin, vMax} in degrees, by physical position from the left of the dash. */
    private static final int[][] PHYSICAL_RANGES = {
            {-18, 20, -30, 5},
            {-23, 3, -40, 15},
            {-10, 18, -40, 10},
            {-20, 18, -30, 10},
    };

    private static final String[] WIND_MODE_NAMES = {
            null, "face", "face_foot", "foot", "foot_defrost", "defrost",
            "face_foot_defrost", "face_defrost"};

    private AcVentControl() {}

    // ==================== ids ====================

    private static int[] modeIds(int side, boolean write) {
        if (side == SIDE_DRIVER) {
            return write ? new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_FREE_WIND_BUTTON_STATUS_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_BLOWS_MODE_SIGNAL_TO_PEOPLE_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_SIDE_TO_AVOID_BLOWING_MODE_SIGNAL_TO_PEOPLE_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_AND_RIGHT_SWEEP_SIGNAL_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_TUYERE_CLOSING_SIGNAL_SET,
            } : new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_FREE_WIND_BUTTON_STATUS,
                    BydFeatureIds.AC_MAIN_DRIVE_BLOWS_MODE_SIGNAL_TO_PEOPLE,
                    BydFeatureIds.AC_MAIN_DRIVE_SIDE_TO_AVOID_BLOWING_MODE_SIGNAL_TO_PEOPLE,
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_AND_RIGHT_SWEEP_SIGNAL,
                    BydFeatureIds.AC_MAIN_DRIVE_TUYERE_CLOSING_SIGNAL,
            };
        }
        return write ? new int[] {
                BydFeatureIds.AC_PASSENGER_FREE_WIND_BUTTON_STATUS_SET,
                BydFeatureIds.AC_PASSENGER_BLOWS_MODE_SIGNAL_TO_PEOPLE_SET,
                BydFeatureIds.AC_PASSENGER_SIDE_TO_AVOID_BLOWING_MODE_SIGNAL_TO_PEOPLE_SET,
                BydFeatureIds.AC_PASSENGER_LEFT_AND_RIGHT_SWEEP_SIGNAL_SET,
                BydFeatureIds.AC_PASSENGER_TUYERE_CLOSING_SIGNAL_SET,
        } : new int[] {
                BydFeatureIds.AC_PASSENGER_FREE_WIND_BUTTON_STATUS,
                BydFeatureIds.AC_PASSENGER_BLOWS_MODE_SIGNAL_TO_PEOPLE,
                BydFeatureIds.AC_PASSENGER_SIDE_TO_AVOID_BLOWING_MODE_SIGNAL_TO_PEOPLE,
                BydFeatureIds.AC_PASSENGER_LEFT_AND_RIGHT_SWEEP_SIGNAL,
                BydFeatureIds.AC_PASSENGER_TUYERE_CLOSING_SIGNAL,
        };
    }

    /** Index into {@link #modeIds}: free, focus, avoid, swing, off. */
    private static int modeIndex(String mode) {
        switch (mode == null ? "" : mode) {
            case MODE_FREE: return 0;
            case MODE_FOCUS: return 1;
            case MODE_AVOID: return 2;
            case MODE_SWING: return 3;
            case MODE_OFF: return 4;
            default: return -1;
        }
    }

    /** {left-right id, up-down id} of one vent. */
    private static int[] directionIds(int vent, boolean write) {
        switch (vent) {
            case 0: return write ? new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL_SET}
                    : new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL,
                    BydFeatureIds.AC_MAIN_DRIVE_LEFT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL};
            case 1: return write ? new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_RIGHT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL_SET,
                    BydFeatureIds.AC_MAIN_DRIVE_RIGHT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL_SET}
                    : new int[] {
                    BydFeatureIds.AC_MAIN_DRIVE_RIGHT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL,
                    BydFeatureIds.AC_MAIN_DRIVE_RIGHT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL};
            case 2: return write ? new int[] {
                    BydFeatureIds.AC_PASSENGER_LEFT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL_SET,
                    BydFeatureIds.AC_PASSENGER_LEFT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL_SET}
                    : new int[] {
                    BydFeatureIds.AC_PASSENGER_LEFT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL,
                    BydFeatureIds.AC_PASSENGER_LEFT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL};
            case 3: return write ? new int[] {
                    BydFeatureIds.AC_PASSENGER_RIGHT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL_SET,
                    BydFeatureIds.AC_PASSENGER_RIGHT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL_SET}
                    : new int[] {
                    BydFeatureIds.AC_PASSENGER_RIGHT_TUYERE_LEFT_AND_RIGHT_ADJUSTMENT_SIGNAL,
                    BydFeatureIds.AC_PASSENGER_RIGHT_TUYERE_UP_AND_DOWN_ADJUSTMENT_SIGNAL};
            default: return null;
        }
    }

    private static boolean allResolved(int[] ids) {
        if (ids == null) return false;
        for (int id : ids) {
            if (!BydFeatureIds.isResolved(id)) return false;
        }
        return true;
    }

    // ==================== pure mappings ====================

    public static boolean isValidSide(int side) {
        return side == SIDE_DRIVER || side == SIDE_PASSENGER;
    }

    public static boolean isValidMode(String mode) {
        return modeIndex(mode) >= 0;
    }

    public static boolean isValidVent(int vent) {
        return vent >= 0 && vent < VENT_COUNT;
    }

    /** Vent index from its name in {@link #VENT_NAMES}; -1 when unknown. */
    public static int ventIndex(String name) {
        if (name == null) return -1;
        for (int i = 0; i < VENT_NAMES.length; i++) {
            if (VENT_NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    public static boolean isElectricVentMarker(int marker) {
        return marker >= 2 && marker <= 4;
    }

    /** Physical position (0 = leftmost) of an OEM vent index. */
    static int physicalPosition(int vent, boolean rightHandDrive) {
        return rightHandDrive ? (vent + 2) % VENT_COUNT : vent;
    }

    /** {hMin, hMax, vMin, vMax} for one vent. */
    public static int[] angleRange(int vent, boolean rightHandDrive) {
        return PHYSICAL_RANGES[physicalPosition(vent, rightHandDrive)].clone();
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static int encodeAngle(int degrees) {
        return degrees + ANGLE_OFFSET;
    }

    /** Degrees from a raw readback, or null when the raw value is not a position. */
    static Integer decodeAngle(int raw) {
        if (raw == Integer.MIN_VALUE) return null;
        int degrees = raw - ANGLE_OFFSET;
        return Math.abs(degrees) <= MAX_PLAUSIBLE_DEGREES ? Integer.valueOf(degrees) : null;
    }

    /**
     * Mode from the four readback flags (1 = on), in the OEM's priority order. Null when none of
     * the reads answered, so a dead HAL never reads as "off".
     */
    static String modeFromFlags(int free, int focus, int avoid, int swing) {
        if (!isFlag(free) && !isFlag(focus) && !isFlag(avoid) && !isFlag(swing)) return null;
        if (focus == 1) return MODE_FOCUS;
        if (avoid == 1) return MODE_AVOID;
        if (swing == 1) return MODE_SWING;
        if (free == 1) return MODE_FREE;
        return MODE_OFF;
    }

    private static boolean isFlag(int raw) {
        return raw >= 0 && raw <= 2;
    }

    /** AC_WINDMODE name for 1..7, or null. */
    public static String windModeName(int mode) {
        return mode >= 1 && mode < WIND_MODE_NAMES.length ? WIND_MODE_NAMES[mode] : null;
    }

    /** AC_WINDMODE value for a name, or -1. */
    public static int windModeFromName(String name) {
        if (name == null) return -1;
        for (int i = 1; i < WIND_MODE_NAMES.length; i++) {
            if (WIND_MODE_NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    /**
     * Whether the car accepts an airflow mode. A car reporting 5 modes has only 1..5 (face to
     * defrost); 6 and 7 combine defrost with face. Any other count (including no answer) allows
     * the full OEM range and leaves the decision to the car.
     */
    public static boolean isValidWindMode(int mode, int modeCount) {
        if (mode < 1 || mode > 7) return false;
        return modeCount != 5 || mode <= 5;
    }

    // ==================== HAL operations ====================

    /** Whether this framework publishes the vent ids and the car reports electric vents. */
    public static boolean isSupported(Hal hal) {
        return Boolean.TRUE.equals(supportState(hal));
    }

    /** TRUE / FALSE when the car answered (or the framework has no vent ids), null when it did not. */
    public static Boolean supportState(Hal hal) {
        if (!BydFeatureIds.isResolved(BydFeatureIds.AC_FRONT_ELECTRIC_TUYERE_SELF_STUDY_MARKER)) {
            return Boolean.FALSE;
        }
        int marker = hal.get(BydFeatureIds.AC_FRONT_ELECTRIC_TUYERE_SELF_STUDY_MARKER);
        if (isElectricVentMarker(marker)) return Boolean.TRUE;
        return marker == 0 || marker == 1 ? Boolean.FALSE : null;
    }

    /** Select a vent mode on one side: write 1 to that mode's flag. */
    public static boolean setMode(Hal hal, int side, String mode) {
        int index = modeIndex(mode);
        if (!isValidSide(side) || index < 0) return false;
        int id = modeIds(side, true)[index];
        if (!BydFeatureIds.isResolved(id)) return false;
        return hal.set(id, 1) == 0;
    }

    /**
     * Point one vent. Degrees are clamped to that vent's range; the clamped pair is returned so
     * the caller can report what was actually sent. Null when the write was refused.
     */
    public static int[] setDirection(Hal hal, int vent, int hDegrees, int vDegrees,
            boolean rightHandDrive) {
        if (!isValidVent(vent)) return null;
        return setDirection(hal, directionIds(vent, true), vent, hDegrees, vDegrees, rightHandDrive);
    }

    static int[] setDirection(Hal hal, int[] ids, int vent, int hDegrees, int vDegrees,
            boolean rightHandDrive) {
        if (!isValidVent(vent) || !allResolved(ids)) return null;
        int[] r = angleRange(vent, rightHandDrive);
        int h = clamp(hDegrees, r[0], r[1]);
        int v = clamp(vDegrees, r[2], r[3]);
        int code = hal.setPair(ids, new int[] {encodeAngle(h), encodeAngle(v)});
        return code == 0 ? new int[] {h, v} : null;
    }

    public static String readMode(Hal hal, int side) {
        if (!isValidSide(side)) return null;
        int[] ids = modeIds(side, false);
        return modeFromFlags(read(hal, ids[0]), read(hal, ids[1]),
                read(hal, ids[2]), read(hal, ids[3]));
    }

    private static int read(Hal hal, int id) {
        return BydFeatureIds.isResolved(id) ? hal.get(id) : Integer.MIN_VALUE;
    }

    /** Vent block {@code {supported, mode{driver,passenger}, direction[]}}; null when the car did not answer. */
    public static JSONObject readState(Hal hal, boolean rightHandDrive) throws JSONException {
        Boolean state = supportState(hal);
        if (state == null) return null;
        JSONObject vents = new JSONObject();
        vents.put("supported", state.booleanValue());
        if (!state.booleanValue()) return vents;

        JSONObject mode = new JSONObject();
        putOrNull(mode, "driver", readMode(hal, SIDE_DRIVER));
        putOrNull(mode, "passenger", readMode(hal, SIDE_PASSENGER));
        vents.put("mode", mode);

        JSONArray direction = new JSONArray();
        for (int vent = 0; vent < VENT_COUNT; vent++) {
            int[] ids = directionIds(vent, false);
            JSONObject d = new JSONObject();
            d.put("vent", VENT_NAMES[vent]);
            putOrNull(d, "h", decodeAngle(read(hal, ids[0])));
            putOrNull(d, "v", decodeAngle(read(hal, ids[1])));
            int[] r = angleRange(vent, rightHandDrive);
            d.put("range", new JSONArray().put(r[0]).put(r[1]).put(r[2]).put(r[3]));
            direction.put(d);
        }
        vents.put("direction", direction);
        return vents;
    }

    private static void putOrNull(JSONObject o, String key, Object value) throws JSONException {
        o.put(key, value == null ? JSONObject.NULL : value);
    }
}
