package com.overdrive.app.automation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Purely-presentational category tagging for the automation action / trigger /
 * condition pickers (and, mirrored, the key-mapping picker). Maps each item's stable
 * {@code id} to a category key so the web {@code <select>} can group options under
 * {@code <optgroup>} headings ("Climate", "Lighting", "ADAS & Safety", …).
 *
 * <p><b>Cosmetic only — never affects storage or resolution.</b> A saved automation
 * stores an action/condition by its {@code id} string and re-resolves by that id
 * ({@code Actions.getAction} / the condition map), never by list order or category. So
 * adding this tag, reordering the catalog, or recategorizing an item cannot change or
 * break any existing automation. An id with no mapping falls into {@link #OTHER}, so a
 * newly-added action that someone forgets to categorize still shows up (under "Other")
 * rather than disappearing.
 *
 * <p>The category KEY is stamped into the schema; the web layer localizes it to a
 * heading via the {@code automation.category_*} i18n keys. Keeping the key→label
 * mapping in i18n (not here) means headings translate with everything else.
 */
public final class AutomationCategories {

    private AutomationCategories() {}

    // Category keys (stable; the web maps these to localized headings). Ordered by the
    // sequence we want the <optgroup>s to appear in the picker.
    public static final String CLIMATE       = "climate";
    public static final String WINDOWS_BODY  = "windows_body";
    public static final String LIGHTING      = "lighting";
    public static final String ADAS_SAFETY   = "adas_safety";
    public static final String DRIVE         = "drive";        // drive/powertrain dynamics
    public static final String MEDIA         = "media";        // audio/video/volume
    public static final String DISPLAYS      = "displays";     // brightness/screen/HUD
    public static final String SYSTEM        = "system";       // apps/shell/notification
    public static final String SURVEILLANCE  = "surveillance"; // recording/camera
    public static final String ADVANCED      = "advanced";     // calibration / legacy detail controls
    public static final String FLOW          = "flow";         // pause/wait/variables
    public static final String VEHICLE       = "vehicle";      // lock/flash/find-car
    public static final String SENSORS       = "sensors";      // read-only trigger/condition signals
    public static final String OTHER         = "other";

    /**
     * The display order of categories in the picker. The web sorts <optgroup>s by this;
     * anything not listed sorts last (before OTHER, which is always last).
     */
    public static final String[] ORDER = {
        VEHICLE, CLIMATE, WINDOWS_BODY, LIGHTING, ADAS_SAFETY, DRIVE, MEDIA,
        DISPLAYS, SENSORS, SURVEILLANCE, ADVANCED, SYSTEM, FLOW, OTHER,
    };

    // id → category. Shared by actions, triggers, and conditions (an id like "drl" or
    // "speed" means the same feature whether set or sensed). Missing → OTHER.
    private static final Map<String, String> CAT = new HashMap<>();
    private static final Set<String> HYBRID_ONLY = new HashSet<>();
    static {
        // ── Vehicle composite (remote-ish) ──
        put(VEHICLE, "lock", "unlock", "flash", "findCar", "find_car", "powerOn", "powerOff",
                "unlockSource");
        // ── Climate ──
        put(CLIMATE, "setAc", "setAcTemp", "stepAcTemp", "setAcFan", "stepAcFan", "ac", "temperature", "outsideTemp",
                "seat", "seatClimate", "seat_heat_driver", "seat_heat_passenger",
                "seat_vent_driver", "seat_vent_passenger",
                "acAuto", "acSync", "acAutoOff", "fanOnly", "steeringHeat", "recirculation",
                "frontDefrost", "rearDefrost", "maxCool", "airPurify", "acFanLevel");
        // ── Windows / body / openings ──
        put(WINDOWS_BODY, "windows", "windowsAll", "windowsPreset", "windowState",
                "windowOpenPercent", "sunroof", "sunshade", "tailgate", "mirror_fold",
                "mirror_auto_follow_up", "child_lock", "doorState", "wireless_charging", "rainCloseWindows",
                "wireless_charging_state", "wireless_charging_left", "wireless_charging_right",
                // Seat/mirror GEOMETRY — physically moving the body, unlike the seat
                // heating/venting ids above, which are climate.
                "seatPosition", "seatSave", "applySeatPosition", "easyEntry");
        // ── Lighting ──
        put(LIGHTING, "drl", "headlight_mode", "hazard", "setAmbient", "ambient_colour", "ambientBrightness",
                "turnSignalLeft", "turnSignalRight", "turn_signal_left", "turn_signal_right",
                // "ambientPower" = the on/off ACTION's id, "ambient" = the on/off CONDITION's id.
                // Only action/condition ids belong in this map — the MQTT telemetry field name
                // (ambient_enabled) is never looked up here and would be dead weight.
                "ambientPower", "ambient",
                "lights", "autoLights", "turnSignal",
                "welcomeLight", "readingLight", "ambientMusic", "headlightLevel");
        // ── ADAS / safety ──
        put(ADAS_SAFETY, "adas_slw", "slw", "esp_control", "esp_state", "itac",
                "lane_assist", "cpd", "adas_cpd", "tyrePressureWarn",
                "tyreLeakWarn", "seatbelt", "occupant", "radarNearest", "blindSpot",
                "rearCrossTraffic", "doorOpenWarning",
                // Expanded ADAS matrix
                "adas_bsd", "adas_tsr", "adas_rcta", "adas_fcta", "adas_tla", "adas_dow",
                "adas_rcw", "adas_islc", "adas_elka", "adas_rctb", "adas_fctb",
                "adas_fcw", "adas_aeb");
        // ── Drive / powertrain dynamics ──
        put(DRIVE, "drive_mode", "driveMode", "powertrain_mode", "powertrainMode",
                "hold_battery", "regen_level", "energyRegen", "steering_mode", "brake_feel",
                "gear", "speed", "accelerator", "brake", "steeringAngle", "slope",
                // The real SOC-hold pair, alongside hold_battery so users comparing the two
                // find them together (hold_battery is only an energy-mode HEV alias).
                "charge_cap_enabled", "charge_cap_percent", "battery_hold", "target_soc",
                "acChargeCurrentLimit");
        // ── Media / audio ──
        put(MEDIA, "mediaVolume", "channelVolume", "volumeStep", "mediaControl",
                "playAudio", "playVideo", "stopAudio", "speak");
        // ── Displays ──
        put(DISPLAYS, "screenBrightness", "clusterBrightness", "hudBrightness",
                "hudPower", "screenPower");
        // ── System / apps / notification ──
        put(SYSTEM, "notification", "showToast", "showDialog", "openApp", "openAppSplit",
                "openAppsSplit", "shell", "radio",
                "mqttPublish", "mqttTrigger",
                "wifiState", "wifiSsid", "btState", "btDeviceName", "locationZone",
                "boot", "power", "time", "day", "dayOfMonth", "month", "sunPhase",
                "uiNav", "screenshot", "iviReboot", "moveAppToDisplay", "stopClusterCast");
        // ── Surveillance / recording / camera ──
        put(SURVEILLANCE, "surveillance", "operatingMode", "recording", "manualClip",
                "showCameraFeed", "setCameraViewSize", "setBlindSpotOverlaySize", "hideCameraView",
                "surveillanceArmed", "surveillanceThreat", "surveillanceObject",
                // Blind-spot CARD actions (OverDrive's own rear+side view). The OEM ADAS
                // blind-spot WARNING condition keeps the "blindSpot" id under ADAS above —
                // different feature, hence the distinct blindSpotEnable id here.
                "blindSpotEnable", "blindSpotDismiss",
                "safeKeep", "safeKeepState", "safeKeepBacklog", "safeKeepLastError", "safeKeepClipSaved");
        // ── Advanced calibration / legacy detail controls ──
        // Existing action ids remain supported exactly as before; this only keeps
        // calibration knobs out of the intent-based Surveillance picker.
        put(ADVANCED, "showCameraView", "showBlindSpotCameraFeed", "blindSpotFisheye",
                "blindSpotFisheyeStep", "blindSpotCameras", "blindSpotRotation");
        // ── Flow control / variables ──
        put(FLOW, "pause", "waitUntil", "waitUntilState", "setVariable", "incrementVariable",
                "computeVariable", "captureVariable", "variable", "loop", "if", "actionGroup",
                "automationControl");
        // ── Sensor-only signals (triggers/conditions with no matching action) ──
        put(SENSORS, "batteryLevel", "targetSoc", "estimatedRange", "chargingState", "chargeGun",
                "chargingPower", "chargeTimeToFull", "tyrePressureLowest",
                "batterySoh", "keyBattery", "aux12vBattery", "fuelLevel", "pm25",
                "autoWiper", "wiperActive", "rainProbability", "callState");

        // Keep existing saved automations resolvable, but omit engine-dependent items from new
        // pickers once the collector has positively identified the current car as BEV.
        java.util.Collections.addAll(HYBRID_ONLY,
                "powertrainMode", "powertrain_mode", "hold_battery", "battery_hold",
                "charge_cap_enabled", "charge_cap_percent", "target_soc");
    }

    private static void put(String category, String... ids) {
        for (String id : ids) CAT.put(id, category);
    }

    /** Category key for an item id, or {@link #OTHER} if untagged. Never null. */
    public static String forId(String id) {
        if (id == null) return OTHER;
        String c = CAT.get(id);
        return c != null ? c : OTHER;
    }

    public static boolean isHybridOnly(String id) {
        return id != null && HYBRID_ONLY.contains(id);
    }

    /**
     * Whether fuel-capable-hybrid items should be advertised for the current vehicle.
     * Unknown startup state stays permissive so a slow HAL probe cannot hide valid controls.
     */
    public static boolean supportsHybridOnlyItemsOnCurrentVehicle() {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            return !collector.isInitialized() || collector.isFuelCapableHybridPublic();
        } catch (Throwable unavailable) {
            return true;
        }
    }
}
