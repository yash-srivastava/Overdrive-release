package com.overdrive.app.battery;

import com.overdrive.app.byd.BydVehicleData;

import org.json.JSONObject;

/**
 * One shared, read-only classification for charging-session evidence.
 *
 * <p>The underlying history row is deliberately never rewritten. Consumers receive
 * metadata that says whether the row may be used as power/energy evidence and whether
 * the completed SOC span is an LFP calibration charge.</p>
 */
public final class ChargingSessionQuality {
    public static final double CALIBRATION_START_MAX_SOC = 10.0;
    public static final double CALIBRATION_END_MIN_SOC = 99.0;
    public static final double POISONED_AC_PEAK_MIN_KW = 25.0;
    private static final double NMC_CELL_VOLTAGE_MIN = 3.80;

    private ChargingSessionQuality() {}

    public static JSONObject enrich(JSONObject session, boolean isPhev, double highCellVoltage,
                                    String declaredChemistry)
            throws org.json.JSONException {
        if (session == null) return null;

        boolean completed = isCompleted(session);
        boolean poisoned = isPoisonedPower(session);
        String chemistry = chemistry(isPhev, highCellVoltage, declaredChemistry);
        boolean calibration = completed
                && "lfp".equals(chemistry)
                && session.optDouble("startSoc", Double.NaN) <= CALIBRATION_START_MAX_SOC
                && session.optDouble("endSoc", Double.NaN) >= CALIBRATION_END_MIN_SOC;

        session.put("powerDataQuality", poisoned ? "poisoned" :
                (completed ? "valid" : "unknown"));
        session.put("energyUsable", completed && !poisoned
                && isFinitePositive(session.optDouble("energyAdded", Double.NaN)));

        JSONObject metadata = new JSONObject();
        metadata.put("qualified", calibration);
        metadata.put("readOnly", true);
        metadata.put("chemistry", chemistry);
        metadata.put("reason", calibration ? "lfp_full_span" :
                calibrationReason(session, completed, chemistry));
        metadata.put("chargingEnergyExcluded", poisoned);
        session.put("calibration", metadata);
        return session;
    }

    public static boolean isCompleted(JSONObject session) {
        if (session == null || session.optBoolean("inProgress", false)) return false;
        long start = session.optLong("startTime", 0L);
        long end = session.optLong("endTime", 0L);
        return start > 0 && end > start && !session.isNull("endSoc")
                && Double.isFinite(session.optDouble("endSoc", Double.NaN));
    }

    /** Existing historical poison verdict, now owned by the server metadata. */
    public static boolean isPoisonedPower(JSONObject session) {
        if (session == null || session.isNull("isDc") || session.optBoolean("isDc", true)) {
            return false;
        }
        double peak = session.optDouble("peakPower", Double.NaN);
        return Double.isFinite(peak) && peak >= POISONED_AC_PEAK_MIN_KW;
    }

    /**
     * Resolve chemistry from affirmative vehicle-model metadata.
     *
     * <p>Cell voltage is deliberately not positive LFP evidence: an NMC cell can
     * also sit below 3.8 V through much of its discharge. It is retained only as
     * a contradiction gate, because a value at or above 3.8 V cannot be reconciled
     * with the declared Blade/LFP pack. Missing model metadata therefore stays
     * unknown and can never produce an LFP calibration badge.</p>
     */
    public static String chemistry(boolean isPhev, double highCellVoltage,
                                   String declaredChemistry) {
        if (isPhev) return "unsupported_phev";
        String declared = declaredChemistry == null
                ? "" : declaredChemistry.trim().toLowerCase(java.util.Locale.US);
        if ("nmc".equals(declared)) {
            return "unsupported_nmc";
        }
        if (!"lfp".equals(declared)) return "unknown";
        if (Double.isFinite(highCellVoltage) && highCellVoltage >= NMC_CELL_VOLTAGE_MIN) {
            return "chemistry_conflict";
        }
        return "lfp";
    }

    private static String calibrationReason(JSONObject session, boolean completed, String chemistry) {
        if (!completed) return "session_incomplete";
        if (!"lfp".equals(chemistry)) return chemistry;
        double startSoc = session.optDouble("startSoc", Double.NaN);
        double endSoc = session.optDouble("endSoc", Double.NaN);
        if (!Double.isFinite(startSoc) || startSoc > CALIBRATION_START_MAX_SOC) {
            return "start_soc_above_10";
        }
        if (!Double.isFinite(endSoc) || endSoc < CALIBRATION_END_MIN_SOC) {
            return "end_soc_below_99";
        }
        return "not_qualified";
    }

    private static boolean isFinitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
