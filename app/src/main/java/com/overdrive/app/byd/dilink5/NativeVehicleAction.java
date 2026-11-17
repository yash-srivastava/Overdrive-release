package com.overdrive.app.byd.dilink5;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named, closed registry of native vehicle writes available to the manual profile. */
public enum NativeVehicleAction {
    TAILGATE_OPEN("tailgate-open", 1001, 1125122080, 1, Safety.TAILGATE_OPEN),
    TAILGATE_CLOSE("tailgate-close", 1001, 1125122080, 3, Safety.PARKED),
    AC_ON("ac-on", 1000, 501219364, 1, Safety.NONE),
    AC_OFF("ac-off", 1000, 501219364, 0, Safety.NONE),
    SUNROOF_OPEN("sunroof-open", 1001, 1125122056, 1, Safety.SUNROOF_OPENING),
    SUNROOF_CLOSE("sunroof-close", 1001, 1125122056, 2, Safety.NONE),
    SUNROOF_TILT("sunroof-tilt", 1001, 1125122056, 3, Safety.SUNROOF_OPENING),
    SUNROOF_STOP("sunroof-stop", 1001, 1125122056, 4, Safety.NONE),
    SUNROOF_UPDIP("sunroof-updip", 1001, 1125122056, 5, Safety.SUNROOF_OPENING),
    SUNROOF_COMFORT("sunroof-comfort", 1001, 1125122056, 6, Safety.SUNROOF_OPENING),
    SUNSHADE_OPEN("sunshade-open", 1001, 1125122060, 1, Safety.NONE),
    SUNSHADE_CLOSE("sunshade-close", 1001, 1125122060, 2, Safety.NONE),
    HAZARDS_ON("hazards-on", 1004, 871366669, 1, Safety.NONE),
    HAZARDS_OFF("hazards-off", 1004, 871366669, 0, Safety.NONE);

    public enum Safety { NONE, PARKED, TAILGATE_OPEN, SUNROOF_OPENING }

    public final String id;
    public final int device;
    public final int featureId;
    public final int value;
    public final Safety safety;

    private static final Map<String, NativeVehicleAction> BY_TUPLE;
    private static final Map<String, NativeVehicleAction> BY_ID;

    static {
        Map<String, NativeVehicleAction> tuples = new LinkedHashMap<>();
        Map<String, NativeVehicleAction> ids = new LinkedHashMap<>();
        for (NativeVehicleAction action : values()) {
            if (tuples.put(tupleKey(action.device, action.featureId, action.value), action) != null
                    || ids.put(action.id, action) != null) {
                throw new IllegalStateException("duplicate native vehicle action");
            }
        }
        BY_TUPLE = Collections.unmodifiableMap(tuples);
        BY_ID = Collections.unmodifiableMap(ids);
    }

    NativeVehicleAction(String id, int device, int featureId, int value, Safety safety) {
        this.id = id;
        this.device = device;
        this.featureId = featureId;
        this.value = value;
        this.safety = safety;
    }

    public static NativeVehicleAction fromTuple(int device, int featureId, int value) {
        return BY_TUPLE.get(tupleKey(device, featureId, value));
    }

    public static NativeVehicleAction fromId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public boolean opensSunroof() {
        return safety == Safety.SUNROOF_OPENING;
    }

    private static String tupleKey(int device, int featureId, int value) {
        return device + ":" + featureId + ":" + value;
    }
}
