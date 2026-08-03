package com.overdrive.app.battery;

import org.json.JSONObject;

import java.net.URL;
import java.util.Locale;

/**
 * Fail-closed resolver for manufacturer-sourced battery chemistry metadata.
 *
 * <p>A bare chemistry label is not sufficient evidence. The selected model's
 * manifest entry must bind that label to an official BYD source, the represented
 * nominal-capacity configuration, and a manufacturer-defined battery technology.
 * This prevents an old or independently updated model manifest from accidentally
 * turning a generic vehicle render choice into an LFP calibration claim.</p>
 */
public final class BatteryChemistryMetadata {
    private static final double NOMINAL_KWH_TOLERANCE = 0.11;

    private BatteryChemistryMetadata() {}

    public static String resolve(JSONObject manifest, JSONObject model) {
        if (manifest == null || model == null) return "unknown";

        String chemistry = normalized(model.optString("batteryChemistry", ""));
        if (!"lfp".equals(chemistry) && !"nmc".equals(chemistry)) return "unknown";

        JSONObject evidence = model.optJSONObject("batteryChemistryEvidence");
        if (!validEvidence(evidence, true)) return "unknown";

        double modelNominalKwh = model.optDouble("nominalKwh", Double.NaN);
        double evidenceNominalKwh = evidence.optDouble("nominalKwh", Double.NaN);
        if (!Double.isFinite(modelNominalKwh) || !Double.isFinite(evidenceNominalKwh)
                || Math.abs(modelNominalKwh - evidenceNominalKwh) > NOMINAL_KWH_TOLERANCE) {
            return "unknown";
        }

        String technology = normalized(evidence.optString("technology", ""));
        JSONObject definitions = manifest.optJSONObject("batteryChemistryDefinitions");
        JSONObject definition = definitions == null ? null : definitions.optJSONObject(technology);
        if (!validEvidence(definition, false)
                || !chemistry.equals(normalized(definition.optString("chemistry", "")))) {
            return "unknown";
        }

        return chemistry;
    }

    private static boolean validEvidence(JSONObject evidence, boolean requireConfiguration) {
        if (evidence == null || !"BYD".equals(evidence.optString("authority", "").trim())) {
            return false;
        }
        if (!isOfficialBydUrl(evidence.optString("sourceUrl", ""))) return false;
        String verifiedOn = evidence.optString("verifiedOn", "").trim();
        if (!verifiedOn.matches("\\d{4}-\\d{2}-\\d{2}")) return false;

        // Model evidence carries a human-readable configuration scope. Definitions
        // do not, because they describe a battery technology rather than a vehicle.
        return !requireConfiguration
                || !evidence.optString("configuration", "").trim().isEmpty();
    }

    static boolean isOfficialBydUrl(String raw) {
        try {
            URL url = new URL(raw);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
            String host = url.getHost().toLowerCase(Locale.US);
            return "byd.com".equals(host) || host.endsWith(".byd.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
