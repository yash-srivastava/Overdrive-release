package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Parsed, per-VIN cloud command capabilities.
 *
 * <p>BYD publishes coarse function numbers through getLatestConfig and may
 * publish narrower per-vehicle flags through vehicleFunLearnInfo. The router
 * refreshes this data before dispatching a feature-bearing cloud command.
 */
public final class CloudCapabilities {

    public enum Feature {
        NONE,
        LOCK,
        UNLOCK,
        FIND_CAR,
        FLASH_LIGHTS,
        CLIMATE,
        WINDOWS_CLOSE,
        WINDOWS_OPEN_VENT,
        TRUNK_OPEN,
        TRUNK_CLOSE,
        BATTERY_HEAT,
        SEAT_DRIVER,
        SEAT_PASSENGER,
        SEAT_STEERING_WHEEL,
        SMART_CHARGING
    }

    private final String vin;
    private final Set<String> functionNos;
    private final Map<String, Integer> learnInfo;
    private final boolean learnInfoKnown;
    private final long fetchedAtMs;

    private CloudCapabilities(String vin, Set<String> functionNos,
                              Map<String, Integer> learnInfo, boolean learnInfoKnown,
                              long fetchedAtMs) {
        this.vin = vin != null ? vin : "";
        this.functionNos = Collections.unmodifiableSet(new HashSet<>(functionNos));
        this.learnInfo = Collections.unmodifiableMap(new HashMap<>(learnInfo));
        this.learnInfoKnown = learnInfoKnown;
        this.fetchedAtMs = fetchedAtMs;
    }

    public long getFetchedAtMs() {
        return fetchedAtMs;
    }

    public boolean isForVin(String candidateVin) {
        return vin.equals(candidateVin);
    }

    public boolean supports(Feature feature) {
        switch (feature) {
            case LOCK:
                return hasFunction("1005");
            case UNLOCK:
                return hasFunction("1006");
            case FIND_CAR:
                return hasFunction("1007");
            case FLASH_LIGHTS:
                return hasFunction("1008");
            case CLIMATE:
                return hasAnyFunction("1001", "10300001", "1015");
            case WINDOWS_CLOSE:
                return hasFunction("1026");
            case WINDOWS_OPEN_VENT:
                // Match pyBYD's compatibility gate. Function 1026 is sufficient
                // when vehicleFunLearnInfo is missing or empty; when BYD does
                // return learn-info, an explicit 0/0 must still block OPENWINDOW.
                return hasFunction("1026")
                        && (!hasLearnInfoData()
                            || positiveLearnInfo("openWindowLearnInfo")
                            || positiveLearnInfo("openWindow499LearnInfo"));
            case TRUNK_OPEN:
                return hasFunction("1020");
            case TRUNK_CLOSE:
                return hasFunction("1021");
            case BATTERY_HEAT:
                return hasFunction("10300002");
            case SEAT_DRIVER:
                // pyBYD gates chairType=1 (driver) against either specific
                // heat/vent functions or the shared front-seat capability.
                return hasAnyFunction("10030001", "10030002", "10300003");
            case SEAT_PASSENGER:
                // pyBYD gates chairType=2 (copilot) independently.
                return hasAnyFunction("10030004", "10030005", "10300003");
            case SEAT_STEERING_WHEEL:
                // chairType=5: wheel heat has its own capability family and
                // must never inherit a front-seat capability.
                return hasAnyFunction("10030010", "10300004");
            case SMART_CHARGING:
                return hasFunction("1012");
            case NONE:
            default:
                return true;
        }
    }

    public boolean hasFunction(String functionNo) {
        return functionNos.contains(functionNo);
    }

    /** Sanitized diagnostic for the OPENWINDOW capability decision. */
    public String windowVentGateSummary() {
        return "function1026=" + hasFunction("1026")
                + ", learnInfoKnown=" + learnInfoKnown
                + ", learnInfoPresent=" + hasLearnInfoData()
                + ", openWindow=" + positiveLearnInfo("openWindowLearnInfo")
                + ", openWindow499=" + positiveLearnInfo("openWindow499LearnInfo")
                + ", supported=" + supports(Feature.WINDOWS_OPEN_VENT);
    }

    private boolean hasLearnInfoData() {
        return learnInfoKnown && !learnInfo.isEmpty();
    }

    private boolean hasAnyFunction(String... values) {
        for (String value : values) {
            if (hasFunction(value)) return true;
        }
        return false;
    }

    private boolean positiveLearnInfo(String key) {
        Integer value = learnInfo.get(key);
        return value != null && value.intValue() > 0;
    }

    /**
     * Parse getLatestConfig's per-VIN object and vehicleFunLearnInfo from the
     * matching getAllListByUserId vehicle entry.
     */
    public static CloudCapabilities fromResponses(String vin, JSONObject latestConfig,
                                                  JSONObject vehicleRecord, long fetchedAtMs) {
        Set<String> functionNos = new HashSet<>();
        collectFunctionNos(latestConfig, functionNos);

        Map<String, Integer> learnInfo = new HashMap<>();
        boolean learnInfoKnown = false;
        if (vehicleRecord != null) {
            JSONObject source = vehicleRecord.optJSONObject("vehicleFunLearnInfo");
            if (source != null) {
                learnInfoKnown = true;
                Iterator<String> keys = source.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = source.opt(key);
                    Integer number = parseInt(value);
                    if (number != null) learnInfo.put(key, number);
                }
            }
        }
        return new CloudCapabilities(vin, functionNos, learnInfo, learnInfoKnown, fetchedAtMs);
    }

    private static void collectFunctionNos(Object node, Set<String> destination) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String functionNo = object.optString("functionNo", "");
            if (!functionNo.isEmpty()) destination.add(functionNo);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectFunctionNos(object.opt(keys.next()), destination);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectFunctionNos(array.opt(i), destination);
            }
        }
    }

    private static Integer parseInt(Object value) {
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        if (value != null) {
            try {
                return Integer.valueOf(Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // A non-numeric learn-info flag is not a confirmed capability.
            }
        }
        return null;
    }
}
