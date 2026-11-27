package com.overdrive.app.trips;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

/**
 * Persistent configuration for Trip Analytics.
 *
 * Uses UnifiedConfigManager to store config in the "tripAnalytics" section
 * of /data/local/tmp/overdrive_config.json. Storage settings (storageType,
 * storageLimitMb) are delegated to StorageManager — this class only manages
 * the enabled toggle.
 */
public class TripConfig {

    private static final String TAG = "TripConfig";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String SECTION = "tripAnalytics";

    // Default ON. Trip logging is a passive, local-only recorder: with it off,
    // initComponents() never runs, so there is no database, no detector and no
    // recorder — the Trips page can only ever show "No trips recorded yet", and
    // the sole enable switch lives on the Storage tab where users never find it.
    // Trip logging remains enabled by default with
    // no toggle at all.
    //
    // PRIVACY CAVEAT — be honest about this. load() honours an explicitly
    // persisted value, so an opt-out made AFTER this build lands is respected.
    // But the one-shot migration in UnifiedConfigManager.applyDefaults cannot
    // distinguish a pre-existing `enabled:false` that was merely the old DEFAULT
    // from one the user deliberately chose — no marker existed before this
    // change. So on upgrade, a deliberate pre-upgrade opt-out IS overridden,
    // exactly once. That matters because trips record GPS coordinates plus 5 Hz
    // telemetry. Flagged as a release-note item rather than silently papered
    // over; the alternative (leaving every existing unit inert) is what the fix
    // exists to correct.
    private static final boolean DEFAULT_ENABLED = true;

    private boolean enabled = DEFAULT_ENABLED;
    private double electricityRate = 0;  // Cost per kWh
    private String currency = "";        // Currency symbol (₹, $, €, £)
    // Distance unit preference: "km" (default) or "mi".
    // When "mi", the backend applies MILES_TO_KM conversion on BYD SDK values
    // and the frontend converts km→miles for display. This setting overrides
    // the auto-detected getMileageUnit() from the instrument cluster.
    private String distanceUnit = "km";

    // ── PHEV cost inputs ──────────────────────────────────────────────────
    // tankCapacityL: nominal fuel-tank size in litres. BYD HAL exposes only
    // fuelPercent (0-100) and fuelRangeKm; neither yields litres directly,
    // so the user supplies this once per vehicle. 0 ⇒ fuel leg disabled,
    // electric cost only — keeps the UI honest about what's tracked.
    // fuelPricePerL: price per litre in the currently-selected currency
    // (always SI internally; gallon entry is converted on save).
    // fuelUnit: display preference for fuel volume ("L" or "gal"). Math is
    // always litres; this only controls how we read user input and render.
    private double tankCapacityL = 0;
    private double fuelPricePerL = 0;
    private String fuelUnit = "L";

    public TripConfig() {
        this.enabled = DEFAULT_ENABLED;
    }

    /**
     * Load configuration from UnifiedConfigManager.
     *
     * @return true if the section was read successfully, false otherwise
     */
    public boolean load() {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (section != null) {
                // An explicitly persisted value always wins (including false —
                // a user who turned this off must stay off). Only an ABSENT key
                // falls back to the new default.
                enabled = section.optBoolean("enabled", DEFAULT_ENABLED);
                setElectricityRate(section.optDouble("electricityRate", 0));
                currency = section.optString("currency", "");
                distanceUnit = section.optString("distanceUnit", "km");
                tankCapacityL = section.optDouble("tankCapacityL", 0);
                fuelPricePerL = section.optDouble("fuelPricePerL", 0);
                fuelUnit = section.optString("fuelUnit", "L");
                logger.info("Config loaded: enabled=" + enabled + " rate=" + electricityRate + " " + currency
                        + " unit=" + distanceUnit + " tankL=" + tankCapacityL + " fuelPrice=" + fuelPricePerL
                        + " fuelUnit=" + fuelUnit);
                return true;
            } else {
                logger.info("No tripAnalytics section in UnifiedConfigManager, using defaults");
                return false;
            }
        } catch (Exception e) {
            logger.error("Config load error: " + e.getMessage());
            enabled = false;
            return false;
        }
    }

    /**
     * Save current configuration to UnifiedConfigManager.
     *
     * @return true if the config was written successfully, false otherwise
     */
    public boolean save() {
        try {
            JSONObject section = new JSONObject();
            section.put("enabled", enabled);
            section.put("electricityRate", electricityRate);
            section.put("currency", currency);
            section.put("distanceUnit", distanceUnit);
            section.put("tankCapacityL", tankCapacityL);
            section.put("fuelPricePerL", fuelPricePerL);
            section.put("fuelUnit", fuelUnit);
            boolean success = UnifiedConfigManager.updateSection(SECTION, section);
            if (success) {
                logger.info("Config saved to UnifiedConfigManager: enabled=" + enabled);
            }
            return success;
        } catch (Exception e) {
            logger.error("Config save error: " + e.getMessage());
            return false;
        }
    }

    // ==================== GETTERS ====================

    public boolean isEnabled() {
        return enabled;
    }

    public double getElectricityRate() {
        return electricityRate;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDistanceUnit() {
        return distanceUnit;
    }

    public double getTankCapacityL() {
        return tankCapacityL;
    }

    public double getFuelPricePerL() {
        return fuelPricePerL;
    }

    public String getFuelUnit() {
        return fuelUnit;
    }

    // ==================== SETTERS ====================

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Use the same admissible rate range as ChargingConfig; zero means no configured rate. */
    public void setElectricityRate(double rate) {
        this.electricityRate = (rate > 0 && rate < 100000) ? rate : 0;
    }

    public void setCurrency(String currency) {
        this.currency = currency != null ? currency : "";
    }

    public void setDistanceUnit(String unit) {
        this.distanceUnit = ("mi".equals(unit)) ? "mi" : "km";
    }

    public void setTankCapacityL(double litres) {
        this.tankCapacityL = (litres > 0 && litres < 200) ? litres : 0;
    }

    public void setFuelPricePerL(double price) {
        this.fuelPricePerL = (price > 0 && price < 100000) ? price : 0;
    }

    public void setFuelUnit(String unit) {
        this.fuelUnit = ("gal".equals(unit)) ? "gal" : "L";
    }

    // ==================== UTILITY ====================

    /**
     * Serialize configuration to a JSONObject for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("enabled", enabled);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency);
            json.put("distanceUnit", distanceUnit);
            json.put("tankCapacityL", tankCapacityL);
            json.put("fuelPricePerL", fuelPricePerL);
            json.put("fuelUnit", fuelUnit);
        } catch (Exception e) {
            logger.error("toJson error: " + e.getMessage());
        }
        return json;
    }

    @Override
    public String toString() {
        return "TripConfig{enabled=" + enabled + '}';
    }
}
