package com.overdrive.app.mqtt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Declarative metadata for every telemetry field emitted by
 * {@link MqttConnectionManager#getLatestTelemetry()}.
 *
 * One table drives two things:
 *   1. Home Assistant MQTT discovery — component type, device_class, state_class,
 *      unit, icon, entity_category (diagnostic) and friendly name for each entity.
 *   2. Change detection — the per-key quantization step ("deadband") used by
 *      {@link TelemetryDiffer} to decide whether a value has meaningfully changed.
 *
 * Keys not registered here fall back to a generic diagnostic sensor with a
 * prettified name and value-type-based change detection, so nothing ever breaks
 * if the Java payload gains a field before this catalog is updated.
 */
public final class TelemetryFieldCatalog {

    /** Components. */
    public static final String SENSOR = "sensor";
    public static final String BINARY = "binary_sensor";
    /** Sentinel: present in the payload but never mapped to an HA entity (e.g. arrays, timestamps). */
    public static final String NONE = null;

    /** Time / monotonic fields: never trigger a "change" and never get an HA entity. */
    public static final Set<String> EXCLUDED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("utc", "vd_timestamp", "gps_utc")));

    /** Immutable metadata for a single telemetry key. */
    public static final class Field {
        public final String key;
        public final String name;          // HA friendly name
        public final String component;     // SENSOR / BINARY / NONE
        public final String deviceClass;   // HA device_class or null
        public final String stateClass;    // measurement / total / total_increasing or null
        public final String unit;          // unit_of_measurement or null
        public final String icon;          // mdi:... or null
        public final boolean diagnostic;   // entity_category = diagnostic
        public final double precision;     // deadband step; <=0 means exact / type-based

        Field(String key, String name, String component, String deviceClass, String stateClass,
              String unit, String icon, boolean diagnostic, double precision) {
            this.key = key;
            this.name = name;
            this.component = component;
            this.deviceClass = deviceClass;
            this.stateClass = stateClass;
            this.unit = unit;
            this.icon = icon;
            this.diagnostic = diagnostic;
            this.precision = precision;
        }

        public boolean isBinary() { return BINARY.equals(component); }
        public boolean isDiscoverable() { return component != null; }
    }

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    private static void add(String key, String name, String component, String deviceClass,
                            String stateClass, String unit, String icon, boolean diag, double precision) {
        FIELDS.put(key, new Field(key, name, component, deviceClass, stateClass, unit, icon, diag, precision));
    }

    static {
        final String MEAS = "measurement";
        final String TOTI = "total_increasing";

        // ---------- Core driving / energy ----------
        add("soc",        "State of Charge", SENSOR, "battery",     MEAS, "%",    "mdi:battery",            false, 0.1);
        // "Motor Power", not "Power": this is drive-motor kW. The automation signal named
        // `power` is the IGNITION level (off/acc/on), published here as `power_level` —
        // two unrelated facts that both read as "power". See SignalMqttMap.
        add("power",      "Motor Power",     SENSOR, "power",       MEAS, "kW",   "mdi:flash",              false, 0.1);
        add("target_soc", "Target SOC",      SENSOR, "battery",     MEAS, "%",    "mdi:battery-sync",       false, 1);
        add("charge_power","Charge Power",   SENSOR, "power",       MEAS, "kW",   "mdi:battery-charging",   false, 0.1);
        add("speed",      "Speed",           SENSOR, "speed",       MEAS, "km/h", "mdi:speedometer",        false, 0.1);
        add("lat",        "Latitude",        SENSOR, null,          MEAS, "°",    "mdi:latitude",           true,  0.00001);
        add("lon",        "Longitude",       SENSOR, null,          MEAS, "°",    "mdi:longitude",          true,  0.00001);
        add("elevation",  "Elevation",       SENSOR, "distance",    MEAS, "m",    "mdi:image-filter-hdr",   true,  1);
        add("heading",    "Heading",         SENSOR, null,          MEAS, "°",    "mdi:compass",            true,  1);
        add("gear",       "Gear",            SENSOR, "enum",        null, null,   "mdi:car-shift-pattern",  false, 0);
        // 0.1 deadband, matching the finest resolution the odometer register offers. At the
        // previous 1 km step a decimal change was not a "change", so in HA change-only mode the
        // decimals only refreshed on a whole-km crossing or the heartbeat.
        add("odometer",   "Odometer",        SENSOR, "distance",    TOTI, "km",   "mdi:counter",            false, 0.1);

        // ---------- Charging ----------
        add("is_charging",          "Charging",            BINARY, "battery_charging", null, null, null,                 false, 0);
        add("is_dcfc",              "DC Fast Charging",    BINARY, null,               null, null, "mdi:ev-station",     false, 0);
        add("is_parked",            "Parked",              BINARY, null,               null, null, "mdi:car-brake-parking", false, 0);
        add("charging_pct",         "Charging Progress",   SENSOR, "battery",          MEAS, "%",  "mdi:battery-charging", false, 1);
        add("charging_eta_hours",   "Charging ETA (h)",    SENSOR, "duration",         null, "h",  "mdi:timer-sand",     false, 0);
        add("charging_eta_minutes", "Charging ETA (min)",  SENSOR, "duration",         null, "min","mdi:timer-sand",     false, 0);
        add("charging_capacity_kwh","Charging Capacity",   SENSOR, "energy",           TOTI, "kWh","mdi:battery-charging-high", false, 0.1);
        add("charging_capacity_incomplete", "Charging Energy Incomplete", BINARY, null, null, null, "mdi:alert-circle-outline", true, 0);
        add("charging_capacity_estimated",  "Charging Energy Estimated",  BINARY, null, null, null, "mdi:approximately-equal", true, 0);
        add("charging_capacity_source",     "Charging Energy Source",     SENSOR, "enum", null, null, "mdi:source-branch", true, 0);
        add("charging_v2l",         "V2L Active",          BINARY, null,               null, null, "mdi:home-lightning-bolt", false, 0);
        // Published for the controllable charge-limit entities' state topics;
        // do not also create duplicate read-only sensor entities.
        add("charge_cap_enabled",   "Charge Limit State",   NONE, null,                 null, null, null,                 true,  0);
        add("charge_cap_percent",   "Charge Limit Percent", NONE, null,                 null, null, null,                 true,  0);
        add("charging_state",       "Charging State",      SENSOR, "enum",             null, null, "mdi:battery-charging", true, 0);
        add("charger_state",        "Charger State",       SENSOR, "enum",             null, null, "mdi:ev-station",     true,  0);
        add("charging_mode",        "Charging Mode",       SENSOR, "enum",             null, null, "mdi:ev-station",     true,  0);
        add("charging_gun",         "Charging Gun",        SENSOR, "enum",             null, null, "mdi:power-plug",     true,  0);
        add("charging_type",        "Charging Type",       SENSOR, "enum",             null, null, "mdi:power-plug",     true,  0);
        add("wireless_charging_left",  "Wireless Charging Left",  SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);
        add("wireless_charging_right", "Wireless Charging Right", SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);
        add("wireless_charging_status","Wireless Charging Status",SENSOR, "enum", null, null, "mdi:battery-charging-wireless", true, 0);

        // ---------- Range / consumption / trip ----------
        add("ev_range_km",        "EV Range",           SENSOR, "distance", null, "km", "mdi:map-marker-distance", false, 1);
        add("fuel_range_km",      "Fuel Range",         SENSOR, "distance", null, "km", "mdi:gas-station",         true,  1);
        add("bodywork_range_km",  "Bodywork Range",     SENSOR, "distance", null, "km", "mdi:map-marker-distance", true,  1);
        add("ev_mileage_km",      "EV Mileage",         SENSOR, "distance", TOTI, "km", "mdi:counter",             true,  1);
        add("fuel_pct",           "Fuel Level",         SENSOR, null,       MEAS, "%",  "mdi:gas-station",         false, 1);
        add("trip_km",            "Trip Distance",      SENSOR, "distance", null, "km", "mdi:map-marker-path",     false, 0.1);
        add("trip_hours",         "Trip Time",          SENSOR, "duration", null, "h",  "mdi:timer",               false, 0);
        add("trip_kwh",           "Trip Energy",        SENSOR, "energy",   null, "kWh","mdi:lightning-bolt",      false, 0.1);
        add("consumption_50km",   "Consumption (50km)", SENSOR, null,       MEAS, "kWh/100 km", "mdi:lightning-bolt", false, 0.1);
        add("driving_time_hours", "Driving Time",       SENSOR, "duration", null, "h",  "mdi:timer",               true,  0);
        add("total_elec_con",     "Total Electricity",  SENSOR, "energy",   TOTI, "kWh","mdi:lightning-bolt",      true,  0.1);
        add("total_fuel_con",     "Total Fuel",         SENSOR, null,       TOTI, "L",  "mdi:gas-station",         true,  0.1);
        add("energy_mode",        "Energy Mode",        SENSOR, "enum",     null, null, "mdi:leaf",                true,  0);
        add("op_mode",            "Operation Mode",     SENSOR, "enum",     null, null, "mdi:cog",                 true,  0);

        // ---------- Temperatures ----------
        add("ext_temp",            "Outside Temperature", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",        false, 0.1);
        add("batt_temp",           "Battery Temperature", SENSOR, "temperature", MEAS, "°C", "mdi:battery-heart-variant", false, 0.1);
        add("cabin_temp",          "Cabin Temperature",   SENSOR, "temperature", MEAS, "°C", "mdi:home-thermometer",   false, 0.1);
        add("inside_temp",         "Inside Temperature",  SENSOR, "temperature", MEAS, "°C", "mdi:home-thermometer",   true,  0.1);
        add("coolant_temp",        "Coolant Temperature", SENSOR, "temperature", MEAS, "°C", "mdi:coolant-temperature",true,  0.1);
        add("bodywork_batt_temp",  "Bodywork Batt Temp",  SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",       true,  0.1);
        add("cell_t_max",          "Cell Temp Max",       SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-high",  true,  0.1);
        add("cell_t_min",          "Cell Temp Min",       SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-low",   true,  0.1);
        add("cell_t_avg",          "Cell Temp Avg",       SENSOR, "temperature", MEAS, "°C", "mdi:thermometer",       true,  0.1);
        add("cell_t_delta",        "Cell Temp Delta",     SENSOR, "temperature", MEAS, "°C", "mdi:thermometer-lines", true,  0.1);

        // ---------- HV battery / cells / SOH ----------
        add("soh",        "Battery Health",       SENSOR, "battery", MEAS, "%",  "mdi:battery-heart",     false, 0.1);
        add("soh_oem",    "Battery Health (OEM)", SENSOR, "battery", MEAS, "%",  "mdi:battery-heart",     true,  0.1);
        add("capacity",   "Usable Capacity",      SENSOR, "energy_storage", MEAS, "kWh", "mdi:battery",   false, 0.1);
        add("capacity_ah", "Capacity",            SENSOR, null,      MEAS, "Ah", "mdi:battery",           true,  0.1);
        add("hv_pack_v",  "HV Pack Voltage",      SENSOR, "voltage", MEAS, "V",  "mdi:flash",             true,  0.1);
        add("cell_v_max", "Cell Voltage Max",     SENSOR, "voltage", MEAS, "V",  "mdi:flash",             true,  0.001);
        add("cell_v_min", "Cell Voltage Min",     SENSOR, "voltage", MEAS, "V",  "mdi:flash-outline",     true,  0.001);
        add("cell_v_delta","Cell Voltage Delta",  SENSOR, "voltage", MEAS, "V",  "mdi:sine-wave",         true,  0.001);
        add("soc_hev",    "HEV State of Charge",  SENSOR, "battery", MEAS, "%",  "mdi:battery-50",        true,  0.1);

        // ---------- 12V system ----------
        add("volt_12v",        "12V Battery",       SENSOR, "voltage", MEAS, "V", "mdi:car-battery", false, 0.1);
        add("volt_12v_level",  "12V Level",         SENSOR, "enum",    null, null,"mdi:car-battery", true,  0);
        add("batt_12v_level",  "12V Level (body)",  SENSOR, "enum",    null, null,"mdi:car-battery", true,  0);

        // ---------- Drivetrain ----------
        add("motor_front_rpm",   "Front Motor RPM",  SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("motor_rear_rpm",    "Rear Motor RPM",   SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("motor_front_torque","Front Motor Torque",SENSOR,null, MEAS, "Nm",  "mdi:engine",        true, 1);
        add("engine_rpm",        "Engine RPM",       SENSOR, null, MEAS, "rpm", "mdi:engine",        true, 1);
        add("accel_pct",         "Accelerator",      SENSOR, null, MEAS, "%",   "mdi:car-cruise-control", true, 1);
        add("brake_pct",         "Brake",            SENSOR, null, MEAS, "%",   "mdi:car-brake-alert",    true, 1);
        add("steering_deg",      "Steering Angle",   SENSOR, null, MEAS, "°",   "mdi:steering",      true, 1);
        add("slope_deg",         "Road Slope",       SENSOR, null, MEAS, "°",   "mdi:angle-acute",   true, 0.5);

        // ---------- Tyres ----------
        add("tyre_p_fl", "Tyre Pressure FL", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_fr", "Tyre Pressure FR", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_rl", "Tyre Pressure RL", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_p_rr", "Tyre Pressure RR", SENSOR, "pressure", MEAS, "kPa", "mdi:car-tire-alert", false, 1);
        add("tyre_t_fl", "Tyre Temp FL", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_fr", "Tyre Temp FR", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_rl", "Tyre Temp RL", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_t_rr", "Tyre Temp RR", SENSOR, "temperature", MEAS, "°C", "mdi:thermometer", true, 1);
        add("tyre_system_state", "TPMS System State", SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);
        add("tyre_temp_state",   "TPMS Temp State",   SENSOR, "enum", null, null, "mdi:car-tire-alert", true, 0);

        // ---------- Lights (booleans) ----------
        add("light_low_beam",  "Low Beam",   BINARY, "light", null, null, "mdi:car-light-dimmed", true, 0);
        add("light_high_beam", "High Beam",  BINARY, "light", null, null, "mdi:car-light-high",   true, 0);
        add("light_rear_fog",  "Rear Fog",   BINARY, "light", null, null, "mdi:car-light-fog",    true, 0);
        add("light_front_fog", "Front Fog",  BINARY, "light", null, null, "mdi:car-light-fog",    true, 0);
        add("light_hazard",    "Hazards",    BINARY, "light", null, null, "mdi:car-light-alert",  true, 0);
        add("light_drl",       "Daytime Running Lights", BINARY, "light", null, null, "mdi:car-light-dimmed", true, 0);
        add("ambient_colour",  "Ambient Lights Colour",  SENSOR, null,   MEAS, null, "mdi:format-color-fill", true, 0);
        // Ambient main-switch state (1=on/0=off). Published only when the vehicle reports it —
        // see MqttConnectionManager — so this stays unavailable on a trim that cannot read it
        // rather than reporting a false "off". The controllable twin is the ambient_power switch.
        add("ambient_enabled", "Ambient Lights State",   BINARY, "light", null, null, "mdi:track-light", true, 0);
        // Cabin (reading) light state, published only when the trim reports it; the reading_light switch controls it.
        add("reading_light",   "Cabin Light State",      BINARY, "light", null, null, "mdi:ceiling-light", true, 0);
        add("light_left_turn", "Left Turn Signal",  SENSOR, "enum", null, null, "mdi:arrow-left-bold", true, 0);
        add("light_right_turn","Right Turn Signal", SENSOR, "enum", null, null, "mdi:arrow-right-bold", true, 0);

        // ---------- Climate ----------
        add("ac_on",     "A/C",          SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_cycle",  "A/C Cycle",    SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_wind",   "A/C Wind Mode",SENSOR, "enum", null, null, "mdi:air-conditioner", true, 0);
        add("ac_fan",    "A/C Fan Level",SENSOR, null,   MEAS, null, "mdi:fan",             true, 0);
        add("temp_unit", "Temp Unit",    SENSOR, "enum", null, null, "mdi:temperature-celsius", true, 0);
        // Published (normalized 1/0) for the steering_heat control switch's state topic;
        // do not also create a duplicate read-only sensor entity.
        add("steering_wheel_heat", "Steering Wheel Heating State", NONE, null, null, null, null, true, 0);

        // ---------- Bodywork ----------
        add("wiper_state",   "Wipers",        SENSOR, "enum", null, null, "mdi:wiper",          true, 0);
        add("sunroof_state", "Sunroof State", SENSOR, "enum", null, null, "mdi:window-shutter", true, 0);
        add("sunroof_pos",   "Sunroof Position", SENSOR, null, MEAS, "%", "mdi:window-shutter", true, 1);
        add("sunshade_pct",  "Sunshade",      SENSOR, null,   MEAS, "%",  "mdi:blinds",         true, 1);
        add("drift_mode",    "Drift Mode",    BINARY, null,   null, null, "mdi:car-sports",     true, 0);

        // ---------- Engine (PHEV) ----------
        add("engine_coolant_level", "Engine Coolant Level", SENSOR, "enum", null, null, "mdi:coolant-temperature", true, 0);
        add("oil_level",            "Oil Level",            SENSOR, null,   MEAS, null, "mdi:oil-level", true, 1);
        add("engine_code",          "Engine Code",          SENSOR, null,   null, null, "mdi:engine",    true, 0);

        // ---------- Safety / ADAS ----------
        add("speed_limit_warning", "Speed Limit Warning", BINARY, "problem", null, null, "mdi:speedometer-slow", true, 0);
        // Child Presence Detection setting state (on/off). Published as 1/0 so the adas_cpd
        // control switch's state_topic (child_presence_detection) reflects real state, matching
        // the speed_limit_warning pattern. No "problem" device_class — CPD-on is the desired state.
        add("child_presence_detection", "Child Presence Detection", BINARY, null, null, null, "mdi:car-child-seat", true, 0);
        add("emergency_alarm",     "Emergency Alarm",     SENSOR, "enum", null, null, "mdi:alarm-light",     true, 0);
        // Ignition/accessory level (off/acc/on) — the twin of the automation `power` signal.
        // Named "Vehicle Power State" to keep it distinct from `power` (drive-motor kW) above.
        add("power_level",         "Vehicle Power State", SENSOR, "enum", null, null, "mdi:power",           true, 0);
        add("mcu_status",          "MCU Status",          SENSOR, "enum", null, null, "mdi:chip",            true, 0);

        // ---------- Air quality ----------
        add("pm25_inside",  "PM2.5 Inside",  SENSOR, "pm25", MEAS, "µg/m³", "mdi:air-filter", false, 1);
        add("pm25_outside", "PM2.5 Outside", SENSOR, "pm25", MEAS, "µg/m³", "mdi:weather-hazy", false, 1);

        // ---------- Key / identity ----------
        add("key_battery",            "Key Battery",        SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_start_state",        "Key Start State",    SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_missing",            "Key Missing",        SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("key_bt_low_power",       "Key BT Low Power",   SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("key_power_low",          "Key Power Low",      SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("key_detection_reminder", "Key Detection",      SENSOR, "enum", null, null, "mdi:key-wireless", true, 0);
        add("smart_key_warn",         "Smart Key Warning",  SENSOR, "enum", null, null, "mdi:key-alert",    true, 0);
        add("vin",                    "VIN",                SENSOR, null,   null, null, "mdi:identifier",   true, 0);

        // ---------- Doors / locks ----------
        // door_open: any door, the hood or the trunk. locked: 1 = locked (payloads inverted for
        // HA's lock class in HomeAssistantDiscovery).
        add("door_open", "Doors", BINARY, "door", null, null, null, false, 0);
        add("locked",    "Lock",  BINARY, "lock", null, null, null, false, 0);

        // ---------- Arrays / time: present but not mapped to entities ----------
        add("door_lock",            "Door Lock",            NONE, null, null, null, null, true, 0);
        add("window_open",          "Window Open",          NONE, null, null, null, null, true, 0);
        add("seatbelt",             "Seatbelt",             NONE, null, null, null, null, true, 0);
        add("seat_heat",            "Seat Heat",            NONE, null, null, null, null, true, 0);
        add("seat_cool",            "Seat Cool",            NONE, null, null, null, null, true, 0);
        add("passenger_detection",  "Passenger Detection",  NONE, null, null, null, null, true, 0);
        add("radar_distances",      "Radar Distances",      NONE, null, null, null, null, true, 0);
        add("utc",                  "UTC",                  NONE, null, null, null, null, true, 0);
        add("vd_timestamp",         "Snapshot Timestamp",   NONE, null, null, null, null, true, 0);

        // ---------- Per-slot views of the arrays (HA discovery mode, unavailable slots omitted) ----------
        add("window_open_lf",       "Window Front Left",    SENSOR, null, MEAS, "%", "mdi:car-door", false, 1);
        add("window_open_rf",       "Window Front Right",   SENSOR, null, MEAS, "%", "mdi:car-door", false, 1);
        add("window_open_lr",       "Window Rear Left",     SENSOR, null, MEAS, "%", "mdi:car-door", false, 1);
        add("window_open_rr",       "Window Rear Right",    SENSOR, null, MEAS, "%", "mdi:car-door", false, 1);
        add("window_open_sunroof",  "Sunroof Open",         SENSOR, null, MEAS, "%", "mdi:window-shutter-open", false, 1);
        add("window_open_sunshade", "Sunshade Open",        SENSOR, null, MEAS, "%", "mdi:blinds-open", false, 1);
        add("window_open_any",      "Windows",              BINARY, "window", null, null, null, false, 0);
        add("seatbelt_driver",      "Seatbelt Driver",      BINARY, null, null, null, "mdi:seatbelt", false, 0);
        add("seatbelt_passenger",   "Seatbelt Passenger",   BINARY, null, null, null, "mdi:seatbelt", false, 0);
        add("seat_heat_level_driver", "Seat Heat Driver",     SENSOR, null, null, null, "mdi:car-seat-heater", false, 0);
        add("seat_heat_level_passenger", "Seat Heat Passenger",  SENSOR, null, null, null, "mdi:car-seat-heater", false, 0);
        add("seat_cool_level_driver", "Seat Cool Driver",     SENSOR, null, null, null, "mdi:car-seat-cooler", false, 0);
        add("seat_cool_level_passenger", "Seat Cool Passenger",  SENSOR, null, null, null, "mdi:car-seat-cooler", false, 0);
        add("passenger_occupied",   "Passenger Seat",       BINARY, "occupancy", null, null, null, false, 0);
    }

    private TelemetryFieldCatalog() {}

    /** Registered field, or a generic diagnostic-sensor fallback for unknown keys. */
    public static Field get(String key) {
        Field f = FIELDS.get(key);
        if (f != null) return f;
        return new Field(key, prettify(key), SENSOR, null, null, null, null, true, 0);
    }

    /** Deadband step for change detection; <=0 means type-based / exact. */
    public static double precisionFor(String key) {
        Field f = FIELDS.get(key);
        return f != null ? f.precision : 0;
    }

    /** True if this key should produce a read-only HA sensor (registered, mappable, non-time). */
    public static boolean isDiscoverable(String key) {
        if (EXCLUDED.contains(key)) return false;
        // setting_* keys are surfaced as controllable entities (switch/select/number) via
        // VehicleControlCatalog, not as read-only sensors — skip the sensor component.
        if (key.startsWith("setting_")) return false;
        Field f = FIELDS.get(key);
        // Unknown keys: discoverable by default (generic diagnostic sensor) — arrays are
        // filtered at publish time by value type, so this stays safe.
        return f == null || f.component != null;
    }

    /**
     * True if this key's value should be published to its per-field state topic.
     * Broader than {@link #isDiscoverable}: a key can carry state (e.g. {@code setting_*}
     * read-back, or a control's state) without also generating a read-only sensor entity.
     * Only time/monotonic keys are withheld.
     */
    public static boolean isPublishable(String key) {
        return !EXCLUDED.contains(key);
    }

    /** "front_motor_rpm" -> "Front Motor Rpm" */
    static String prettify(String key) {
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : key;
    }
}
