package com.overdrive.app.monitor;

/**
 * Shared AC/DC charging-session verdict.
 *
 * <p>Gun state is the primary evidence, but a measured-power sanity floor prevents a misreported
 * DC gun from pricing a normal AC session at the DC tariff. With a combo or unavailable gun,
 * power alone can prove DC only above the supported AC charging domain. No-connection and V2L
 * states are never charging evidence.
 */
public final class ChargingTypeClassifier {
    public static final int UNKNOWN = -1;
    public static final int AC = 0;
    public static final int DC = 1;

    public static final double DC_GUN_MIN_PEAK_KW = 15.0;
    public static final double DC_POWER_ONLY_MIN_PEAK_KW = 25.0;

    private ChargingTypeClassifier() {}

    public static int classify(int gunState, double measuredPeakKw) {
        if (gunState == 2) return AC;

        double peakKw = Double.isFinite(measuredPeakKw) && measuredPeakKw > 0
                ? measuredPeakKw : 0;

        // gunState == 1 is documented elsewhere (SocHistoryDatabase.
        // isTerminalChargingStateNow) as "the gun is out" — i.e. not
        // connected. But a session that measured real sustained power could
        // not physically have happened with no gun connected, so any real
        // power reading here directly falsifies that claim (observed: ~1.4 kW
        // sessions pinned at gunState=1 for their whole duration — a portable/
        // Level-1 charger whose control-pilot signal doesn't flip this
        // property the way a proper wallbox does). With the claim disproven,
        // classify from power using the same confirm floor as an explicit DC
        // gun: nothing in this fleet charges DC below that floor, so power
        // below it is AC by elimination. Zero measured power leaves the
        // "not connected" claim uncontradicted, so it stays UNKNOWN.
        if (gunState == 1) {
            if (peakKw <= 0) return UNKNOWN;
            return peakKw >= DC_GUN_MIN_PEAK_KW ? DC : AC;
        }
        if (gunState == 5) return UNKNOWN;
        if (gunState == 3) {
            return peakKw >= DC_GUN_MIN_PEAK_KW ? DC : UNKNOWN;
        }
        if (gunState == 4 || gunState < 0) {
            return peakKw >= DC_POWER_ONLY_MIN_PEAK_KW ? DC : UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Let a high inferred rate corroborate an explicit DC gun without treating inferred power as a
     * measured sample. Unknown/combo guns still require measured power, and the higher power-only
     * floor keeps nominal low-rate estimates from reviving the known false-DC gun report.
     */
    public static int classifyWithCorroboratingPower(
            int gunState, double measuredPeakKw, double corroboratingPowerKw) {
        int measuredVerdict = classify(gunState, measuredPeakKw);
        if (measuredVerdict != UNKNOWN) return measuredVerdict;
        return gunState == 3
                && Double.isFinite(corroboratingPowerKw)
                && corroboratingPowerKw >= DC_POWER_ONLY_MIN_PEAK_KW
                ? DC : UNKNOWN;
    }

    /**
     * Prefer a verdict already proved by the currently-open session's peak.
     *
     * <p>Instantaneous power can fall below the DC sanity floor during taper. Once the same physical
     * session has crossed that floor, reverting to unknown makes downstream telemetry forget a
     * genuine DC session. An absent/unknown session verdict still falls back to current evidence.
     */
    public static int classifyLive(
            int gunState, double measuredPowerKw, int openSessionVerdict) {
        if (openSessionVerdict == AC || openSessionVerdict == DC) {
            return openSessionVerdict;
        }
        return classify(gunState, measuredPowerKw);
    }

    /** Binary telemetry value, or {@code null} when AC/DC has not been proved. */
    public static Integer toBinaryFlag(int verdict) {
        if (verdict == DC) return Integer.valueOf(1);
        if (verdict == AC) return Integer.valueOf(0);
        return null;
    }
}
