package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.TimeType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Conditions {
    // Create LinkedHashMap to maintain the insertion order of conditions to display in the frontend
    private final Map<String, EventCondition> conditions = new LinkedHashMap<>();
    private final Set<String> hiddenConditions = new HashSet<>();

    /**
     * Initialize conditions list with possible events
     */
    public Conditions() {
        addCondition(new EventCondition(
                new Label("power", "automation.power"), "automation.power_description", new EnumType(
                new Label("state", "automation.state"),
                new Label("off", "automation.off"),
                new Label("acc", "automation.acc"),
                new Label("on", "automation.on"))));
        addCondition(new EventCondition(
                new Label("gear", "automation.gear"), "automation.gear_description", new EnumType(
                new Label("gear", "automation.gear"),
                new Label("p", "automation.p_gear"),
                new Label("r", "automation.r_gear"),
                new Label("n", "automation.n_gear"),
                new Label("d", "automation.d_gear"),
                new Label("m", "automation.m_gear"),
                new Label("s", "automation.s_gear"))));
        addCondition(new EventCondition(
                new Label("windowOpenPercent", "automation.window_open_percent"),
                "automation.window_open_percent_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("lf", "automation.area_lf"),
                        new Label("rf", "automation.area_rf"),
                        new Label("lr", "automation.area_lr"),
                        new Label("rr", "automation.area_rr"),
                        new Label("sunroof", "automation.area_sunroof"),
                        new Label("sunshade", "automation.area_sunshade"))));
        addCondition(new EventCondition(
                new Label("windowState", "automation.window_state"),
                "automation.window_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("open", "automation.open"), new Label("closed", "automation.closed")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("all", "automation.area_all"),
                        new Label("lf", "automation.area_lf"),
                        new Label("rf", "automation.area_rf"),
                        new Label("lr", "automation.area_lr"),
                        new Label("rr", "automation.area_rr"),
                        new Label("sunroof", "automation.area_sunroof"),
                        new Label("sunshade", "automation.area_sunshade"))));
        // Dedicated sunshade condition for rules such as "open only if closed".
        // The generic Window State condition also supports area=sunshade; this shortcut
        // makes the state discoverable without choosing a window area.
        addCondition(new EventCondition(
                new Label("sunshadeState", "automation.sunshade_state"),
                "automation.sunshade_state_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("open", "automation.open"),
                        new Label("closed", "automation.closed"))));
        addCondition(new EventCondition(
                new Label("batteryLevel", "automation.battery_level"),
                "automation.battery_level_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        addCondition(new EventCondition(
                new Label("targetSoc", "automation.target_soc"),
                "automation.target_soc_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        addCondition(new EventCondition(
                new Label("estimatedRange", "automation.estimated_range"),
                "automation.estimated_range_description",
                new IntType(new Label("range", "automation.estimated_range"), 0, 1000)));
        addCondition(new EventCondition(
                new Label("lights", "automation.lights"),
                "automation.lights_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("lowBeam", "automation.lights_lowbeam"),
                        new Label("highBeam", "automation.lights_highbeam"),
                        new Label("hazard", "automation.lights_hazard"),
                        new Label("drl", "automation.lights_drl"))));
        // Interior ambient light on/off. Fires only on a vehicle that actually reports the
        // main-switch state (see BydEvent.AMBIENT_STATE).
        addCondition(new EventCondition(
                new Label("ambient", "automation.ambient_state"),
                "automation.ambient_state_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"))));
        addCondition(new EventCondition(
                new Label("slw", "automation.slw"),
                "automation.slw_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        addCondition(new EventCondition(
                new Label("cpd", "automation.cpd"),
                "automation.cpd_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"),
                        new Label("delay", "automation.cpd_delay"))));
        // Drive mode as a trigger (on-change) and condition. The state IS the mode word
        // (normal/eco/sport/snow) published by BydEvent from the drive-config axis. On
        // trims without the drive-config getter only eco/sport are seen (energy-getter
        // fallback) — the value is real when present, so no misfire, just fewer modes.
        addCondition(new EventCondition(
                new Label("driveMode", "automation.drive_mode"),
                "automation.drive_mode_condition_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("normal", "automation.mode_normal"),
                        new Label("eco", "automation.mode_eco"),
                        new Label("sport", "automation.mode_sport"),
                        new Label("snow", "automation.mode_snow"))));
        // Powertrain EV/HEV (fuel-capable hybrids only). HEV and PHEV are intentionally treated
        // as one capability because the available vehicle evidence cannot distinguish them.
        addCondition(new EventCondition(
                new Label("powertrainMode", "automation.powertrain_mode"),
                "automation.powertrain_mode_condition_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        // All FIVE modes the energy device can report, matching
                        // BydDataCollector.energyModeName and the set_powertrain_mode ACTION's
                        // own option list. Offering only ev/hev was a silent killer: the
                        // publisher emits force_ev/fuel/keep verbatim for modes 2/4/5, so a PHEV
                        // running on the engine published "fuel", which no dropdown entry could
                        // match — the condition compares lexically, so the rule never fired and
                        // the live-value hint showed a word the user could not select. Folding
                        // them onto ev/hev instead would have made "set fuel, then check we are
                        // in fuel" impossible.
                        new Label("ev", "automation.mode_ev"),
                        new Label("force_ev", "automation.mode_force_ev"),
                        new Label("hev", "automation.mode_hev"),
                        new Label("fuel", "automation.mode_fuel"),
                        new Label("keep", "automation.mode_keep"))));
        // Central lock as a trigger (on-change) and condition. The state IS the word
        // (locked/unlocked) published by CameraDaemon.applyLockEvent from the OTA-device
        // SDK read (BCM-cached, works parked) with cloud fallback. Delivered only on
        // definite readings, so it never manufactures a spurious edge.
        addCondition(new EventCondition(
                new Label("lock", "automation.lock_state"),
                "automation.lock_condition_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("locked", "automation.locked"),
                        new Label("unlocked", "automation.unlocked"))));
        addCondition(new EventCondition(
                new Label("unlockSource", "automation.unlock_source"),
                "automation.unlock_source_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("fob", "automation.unlock_source_fob"),
                        new Label("none", "automation.unlock_source_none"))));
        addCondition(new EventCondition(
                new Label("easyEntry", "automation.easy_entry"),
                "automation.easy_entry_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("idle", "automation.easy_entry_idle"),
                        new Label("moving", "automation.easy_entry_moving"),
                        new Label("settled", "automation.easy_entry_settled"))));
        // Energy-recuperation (regen) level as a trigger (on-change) and condition. The
        // state IS the word (standard/high/max) published by BydEvent from the SDK
        // getEnergyFeedback read, self-gated so it costs nothing unless a regen rule exists.
        addCondition(new EventCondition(
                new Label("energyRegen", "automation.energy_regen"),
                "automation.energy_regen_condition_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("standard", "automation.regen_standard"),
                        new Label("high", "automation.regen_high"),
                        new Label("max", "automation.regen_max"))));
        addCondition(new EventCondition(
                new Label("seatClimate", "automation.seat_climate"),
                "automation.seat_climate_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high")),
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("cool", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger"))));
        addCondition(new EventCondition(
                new Label("steeringHeat", "automation.steering_heat"),
                "automation.steering_heat_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"))));
        addCondition(new EventCondition(
                new Label("ac", "automation.ac"),
                "automation.ac_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        // Cabin temperature. The range must cover SUB-ZERO: a parked cabin in winter genuinely goes
        // below 0, so a 0..100 floor made "cabin below freezing" impossible to express. The signal
        // becomes unavailable if the vehicle does not report a measured cabin sensor; outside air
        // and the AC dial remain separate signals. The ceiling stays above habitable because a car parked in the
        // sun reaches 60-70 C.
        //
        // The ceiling stays at 100, NOT the sensor band's 90: only the LOWER bound moves. An
        // existing automation saved with a threshold of 91..100 must keep validating — IntType
        // rejects an out-of-range stored value, and the editor marks such a field .invalid,
        // which disables Save for the whole form (a global '#formGrid .invalid' gate) and would
        // leave that automation uneditable. Widening only downward is therefore strictly
        // backward-compatible.
        addCondition(new EventCondition(
                new Label("temperature", "automation.temperature"),
                "automation.temperature_description",
                new IntType(new Label("celsius", "automation.celsius"), -40, 100)));
        // Outside/ambient temperature — same sub-zero rationale (frost automations).
        addCondition(new EventCondition(
                new Label("outsideTemp", "automation.outside_temperature"),
                "automation.outside_temperature_description",
                new IntType(new Label("celsius", "automation.celsius"), -40, 60)));
        // AC dial SETPOINT — what the climate control was ASKED for, not what the cabin
        // measures. Always CELSIUS (BydEvent normalizes a Fahrenheit dial), so a rule means the
        // same thing on any car and the range is the Celsius dial band.
        addCondition(new EventCondition(
                new Label("acSetpoint", "automation.ac_setpoint"),
                "automation.ac_setpoint_description",
                new IntType(new Label("celsius", "automation.celsius"),
                        com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_C,
                        com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_C)));
        // Rain likelihood (%) over the next few hours (Open-Meteo by GPS).
        addCondition(new EventCondition(
                new Label("rainProbability", "automation.rain_probability"),
                "automation.rain_probability_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Phone call state — relayed from the app process (daemon has no telephony).
        // "ringing" = incoming or outgoing call alerting; "offhook" = active call;
        // "idle" = no call. Enables "mute media when a call comes in".
        addCondition(new EventCondition(
                new Label("callState", "automation.call_state"),
                "automation.call_state_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("idle", "automation.call_idle"),
                        new Label("ringing", "automation.call_ringing"),
                        new Label("offhook", "automation.call_active"))));
        addCondition(new EventCondition(
                new Label("speed", "automation.speed"),
                "automation.speed_description",
                new IntType(new Label("speed", "automation.speed"), 0, 200),
                new EnumType(
                        new Label("units", "automation.units"),
                        new Label("kmph", "automation.kmph"),
                        new Label("mph", "automation.mph"))));
        // Accelerator / brake pedal deepness (0-100%). Already ingested every
        // snapshot; the RoadSense fast poll (250ms) keeps them fresh when running.
        addCondition(new EventCondition(
                new Label("accelerator", "automation.accelerator"),
                "automation.accelerator_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        addCondition(new EventCondition(
                new Label("brake", "automation.brake"),
                "automation.brake_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Steering-wheel angle in signed degrees (negative = left, positive = right).
        // ±540 covers 1.5 turns each way — wider than typical driving, narrower than
        // the SDK's ±780 hard limit, so the slider stays usable.
        addCondition(new EventCondition(
                new Label("steeringAngle", "automation.steering_angle"),
                "automation.steering_angle_description",
                new IntType(new Label("degrees", "automation.degrees"), -540, 540)));
        // Turn indicators, per side, as an on/off edge (blink-debounced). The `side`
        // sub-variable selects left vs right so one condition schema serves both.
        addCondition(new EventCondition(
                new Label("turnSignal", "automation.turn_signal"),
                "automation.turn_signal_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("side", "automation.side"),
                        new Label("left", "automation.turn_left"),
                        new Label("right", "automation.turn_right"))));
        addCondition(new EventCondition(
                new Label("time", "automation.time"),
                "automation.time_description",
                new TimeType(new Label("time", "automation.time"))));
        // Sun phase (day/night) — flips at local sunrise/sunset from GPS. "at sunset" =
        // trigger on sunPhase becoming "night"; gate "only in daylight" = eq day.
        // Published only with a GPS fix (see TimeEvent.publishSunPhase).
        addCondition(new EventCondition(
                new Label("sunPhase", "automation.sun_phase"),
                "automation.sun_phase_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("day", "automation.sun_day"),
                        new Label("night", "automation.sun_night"))));
        // Day of month (1-31) — date automations ("on the 1st, remind service").
        addCondition(new EventCondition(
                new Label("dayOfMonth", "automation.day_of_month"),
                "automation.day_of_month_description",
                new IntType(new Label("day", "automation.day_of_month"), 1, 31)));
        // Month (1-12) — seasonal automations. Enum of month names keeps the picker
        // readable while the stored value is the month number the event publishes.
        addCondition(new EventCondition(
                new Label("month", "automation.month"),
                "automation.month_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("1", "automation.month_jan"),
                        new Label("2", "automation.month_feb"),
                        new Label("3", "automation.month_mar"),
                        new Label("4", "automation.month_apr"),
                        new Label("5", "automation.month_may"),
                        new Label("6", "automation.month_jun"),
                        new Label("7", "automation.month_jul"),
                        new Label("8", "automation.month_aug"),
                        new Label("9", "automation.month_sep"),
                        new Label("10", "automation.month_oct"),
                        new Label("11", "automation.month_nov"),
                        new Label("12", "automation.month_dec"))));
        // WiFi connection state + SSID. wifiState is an on/off edge; wifiSsid lets a
        // condition match a specific network name (the "connect to <name>" pattern).
        addCondition(new EventCondition(
                new Label("wifiState", "automation.wifi_state"),
                "automation.wifi_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.connected"), new Label("off", "automation.disconnected"))));
        addCondition(new EventCondition(
                new Label("wifiSsid", "automation.wifi_ssid"),
                "automation.wifi_ssid_description",
                new StringType(new Label("ssid", "automation.wifi_ssid"), 64)));
        // Bluetooth connection state + connected-device name. Mirrors WiFi: btState is
        // an on/off edge; btDeviceName lets a condition match a specific phone by name.
        addCondition(new EventCondition(
                new Label("btState", "automation.bt_state"),
                "automation.bt_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.connected"), new Label("off", "automation.disconnected"))));
        addCondition(new EventCondition(
                new Label("btDeviceName", "automation.bt_device_name"),
                "automation.bt_device_name_description",
                new StringType(new Label("name", "automation.bt_device_name"), 64)));
        // Location zone — fires when the car enters/leaves a geofence. The value is a
        // zone NAME (as defined in Safe Locations, map-picked with a radius down to
        // 15m), or "none" when outside every zone. So "location = Home" matches while
        // inside the Home zone, and a trigger on this event fires on any enter/leave.
        addCondition(new EventCondition(
                new Label("locationZone", "automation.location_zone"),
                "automation.location_zone_description",
                new StringType(new Label("zone", "automation.location_zone"), 64)));
        // Legacy compatibility only. The underlying raw enum reports the anti-theft/armed
        // system on this firmware, not a collision or emergency. Keep its schema so an old
        // saved rule still parses, but do not advertise or publish it as an incident trigger.
        addHiddenCondition(new EventCondition(
                new Label("emergencyAlarm", "automation.emergency_alarm"),
                "automation.emergency_alarm_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        // ── Safety / ADAS events ─────────────────────────────────────────
        // OEM radar alerts, split by warning family so one feature cannot masquerade as another.
        addCondition(new EventCondition(
                new Label("blindSpot", "automation.blind_spot"),
                "automation.blind_spot_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("side", "automation.side"),
                        new Label("left", "automation.side_left"),
                        new Label("right", "automation.side_right"),
                        new Label("any", "automation.side_any"))));
        addCondition(new EventCondition(
                new Label("rearCrossTraffic", "automation.rear_cross_traffic"),
                "automation.rear_cross_traffic_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("side", "automation.side"),
                        new Label("left", "automation.side_left"),
                        new Label("right", "automation.side_right"))));
        addCondition(new EventCondition(
                new Label("doorOpenWarning", "automation.door_open_warning"),
                "automation.door_open_warning_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off")),
                new EnumType(
                        new Label("side", "automation.side"),
                        new Label("left", "automation.side_left"),
                        new Label("right", "automation.side_right"))));
        // Tyre pressure warning (worst wheel): normal / under / over.
        addCondition(new EventCondition(
                new Label("tyrePressureWarn", "automation.tyre_pressure_warn"),
                "automation.tyre_pressure_warn_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("normal", "automation.tyre_normal"),
                        new Label("under", "automation.tyre_under"),
                        new Label("over", "automation.tyre_over"))));
        // Tyre air-leak warning (worst wheel): normal / slow / fast.
        addCondition(new EventCondition(
                new Label("tyreLeakWarn", "automation.tyre_leak_warn"),
                "automation.tyre_leak_warn_description",
                new EnumType(
                        new Label("state", "automation.state"),
                        new Label("normal", "automation.tyre_normal"),
                        new Label("slow", "automation.tyre_leak_slow"),
                        new Label("fast", "automation.tyre_leak_fast"))));
        // ── Surveillance / sentry (parked-guard) events ──
        // Armed/disarmed — gate a rule on "while the car is being guarded".
        addCondition(new EventCondition(
                new Label("surveillanceArmed", "automation.surveillance_armed"),
                "automation.surveillance_armed_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"))));
        // Threat severity of the last recorded sentry event: notice / alert / critical.
        // Fires once per event as the clip finalizes. Pair with surveillanceObject for
        // "person detected" style rules.
        addCondition(new EventCondition(
                new Label("surveillanceThreat", "automation.surveillance_threat"),
                "automation.surveillance_threat_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("notice", "automation.surveillance_notice"),
                        new Label("alert", "automation.surveillance_alert"),
                        new Label("critical", "automation.surveillance_critical"))));
        // Headline object class seen in the last sentry event.
        addCondition(new EventCondition(
                new Label("surveillanceObject", "automation.surveillance_object"),
                "automation.surveillance_object_description",
                new EnumType(new Label("object", "automation.surveillance_object_kind"),
                        new Label("person", "automation.surveillance_obj_person"),
                        new Label("vehicle", "automation.surveillance_obj_vehicle"),
                        new Label("bike", "automation.surveillance_obj_bike"),
                        new Label("animal", "automation.surveillance_obj_animal"),
                        new Label("none", "automation.surveillance_obj_none"))));
        // System boot — a one-shot event published shortly after a genuine device
        // boot. Only "on" is ever published (see NetworkEvent), so this is meant as a
        // trigger; a condition can still gate on it being "on".
        addCondition(new EventCondition(
                new Label("boot", "automation.boot"),
                "automation.boot_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"))));
        addCondition(new EventCondition(
                new Label("day", "automation.day"),
                "automation.day_description",
                new EnumType(
                        new Label("day", "automation.day"),
                        new Label("monday", "automation.monday"),
                        new Label("tuesday", "automation.tuesday"),
                        new Label("wednesday", "automation.wednesday"),
                        new Label("thursday", "automation.thursday"),
                        new Label("friday", "automation.friday"),
                        new Label("saturday", "automation.saturday"),
                        new Label("sunday", "automation.sunday"))));
        // Door / lid open or closed, per opening plus an "any" convenience. Fed by the
        // event-driven DoorEvent (raw bodywork open/close edges), so a trigger fires the
        // instant a door opens. The `area` sub-variable picks which opening; "any"
        // matches when any door/lid is open.
        addCondition(new EventCondition(
                new Label("doorState", "automation.door_state"),
                "automation.door_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("open", "automation.open"), new Label("closed", "automation.closed")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("any", "automation.area_any"),
                        new Label("driver", "automation.door_driver"),
                        new Label("passenger", "automation.door_passenger"),
                        new Label("rearLeft", "automation.door_rear_left"),
                        new Label("rearRight", "automation.door_rear_right"),
                        new Label("hood", "automation.door_hood"),
                        new Label("trunk", "automation.door_trunk"),
                        new Label("fuelCap", "automation.door_fuel_cap"))));
        // ── Charging ─────────────────────────────────────────────────────
        // Fused charging verdict (on/off) + physical gun-connected edge.
        addCondition(new EventCondition(
                new Label("chargingState", "automation.charging_state"),
                "automation.charging_state_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.charging_on"), new Label("off", "automation.charging_off"))));
        addCondition(new EventCondition(
                new Label("chargeGun", "automation.charge_gun"),
                "automation.charge_gun_description",
                new EnumType(new Label("state", "automation.state"), new Label("connected", "automation.connected"), new Label("disconnected", "automation.disconnected"))));
        // ── Battery health + auxiliary batteries (percent) ──
        addCondition(new EventCondition(
                new Label("batterySoh", "automation.battery_soh"),
                "automation.battery_soh_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Key-fob + 12V battery are LOW/NORMAL enums (not percents — the SDK reports a
        // 2-state level, not a gauge), so compare against low/normal.
        addCondition(new EventCondition(
                new Label("keyBattery", "automation.key_battery"),
                "automation.key_battery_description",
                new EnumType(new Label("state", "automation.state"), new Label("low", "automation.battery_low_state"), new Label("normal", "automation.battery_normal_state"))));
        addCondition(new EventCondition(
                new Label("aux12vBattery", "automation.aux_12v_battery"),
                "automation.aux_12v_battery_description",
                new EnumType(new Label("state", "automation.state"), new Label("low", "automation.battery_low_state"), new Label("normal", "automation.battery_normal_state"))));
        // The same rail as a NUMBER, in TENTHS of a volt (135 = 13.5V) — the resolution a
        // "12V is getting low" rule needs, which the low/normal enum above cannot express.
        // Range spans BatteryPowerData's validity window (9.0-16.0V).
        addCondition(new EventCondition(
                new Label("aux12vVoltage", "automation.aux_12v_voltage"),
                "automation.aux_12v_voltage_description",
                new IntType(new Label("decivolts", "automation.decivolts"), 90, 160)));
        // ── Charging and tyres as numbers (the enum/flag versions above stay unchanged) ──
        addCondition(new EventCondition(
                new Label("chargingPower", "automation.charging_power"),
                "automation.charging_power_description",
                new IntType(new Label("kw", "automation.kilowatts"), 0, 500)));
        addCondition(new EventCondition(
                new Label("chargeTimeToFull", "automation.charge_time_to_full"),
                "automation.charge_time_to_full_description",
                new IntType(new Label("minutes", "automation.minutes"), 0, 1440)));
        addCondition(new EventCondition(
                new Label("tyrePressureLowest", "automation.tyre_pressure_lowest"),
                "automation.tyre_pressure_lowest_description",
                new IntType(new Label("kpa", "automation.kilopascals"), 0, 600)));
        addCondition(new EventCondition(
                new Label("acFanLevel", "automation.ac_fan_level"),
                "automation.ac_fan_level_description",
                new IntType(new Label("level", "automation.level"), 0, 7)));
        // ── Fuel (PHEV) percent ──
        addCondition(new EventCondition(
                new Label("fuelLevel", "automation.fuel_level"),
                "automation.fuel_level_description",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // ── Air quality PM2.5 (µg/m³), inside + outside via the `area` sub-variable ──
        addCondition(new EventCondition(
                new Label("pm25", "automation.pm25"),
                "automation.pm25_description",
                new IntType(new Label("value", "automation.pm25_value"), 0, 1000),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("inside", "automation.pm25_inside"),
                        new Label("outside", "automation.pm25_outside"))));
        // ── Road slope / incline (signed degrees) ──
        addCondition(new EventCondition(
                new Label("slope", "automation.slope"),
                "automation.slope_description",
                new IntType(new Label("degrees", "automation.degrees"), -60, 60)));
        // ── Parking-radar nearest obstacle (cm) ──
        addCondition(new EventCondition(
                new Label("radarNearest", "automation.radar_nearest"),
                "automation.radar_nearest_description",
                new IntType(new Label("cm", "automation.centimetres"), 0, 155)));
        // ── Seatbelts (buckled/unbuckled), per seat ──
        addCondition(new EventCondition(
                new Label("seatbelt", "automation.seatbelt"),
                "automation.seatbelt_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.buckled"), new Label("off", "automation.unbuckled")),
                new EnumType(
                        new Label("seat", "automation.seat"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger"))));
        // ── Tier-2 sensors ──
        // Automatic rain-sensing MODE enabled/disabled; not current rain or blade movement.
        addCondition(new EventCondition(
                new Label("autoWiper", "automation.auto_wiper"),
                "automation.auto_wiper_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        // Wipers active (any speed).
        addCondition(new EventCondition(
                new Label("wiperActive", "automation.wiper_active"),
                "automation.wiper_active_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        // Automatic-headlight MODE enabled/disabled; not darkness or actual lamp output.
        addCondition(new EventCondition(
                new Label("autoLights", "automation.auto_lights"),
                "automation.auto_lights_description",
                new EnumType(new Label("state", "automation.state"), new Label("on", "automation.on"), new Label("off", "automation.off"))));
        // Seat occupancy (someone sitting).
        //  - PASSENGER: getPassengerStatus area 1 when available; otherwise a disclosed
        //    belt/reminder estimate (buckled/reminder=occupied, unbuckled=empty).
        //  - DRIVER: no sensor exists; presence is INFERRED from the seatbelt-reminder mask
        //    (bit 0) + the driver belt, and is POSITIVE-ONLY — it publishes "occupied" and never
        //    "empty" (an unbuckled-but-present driver reads identically to an empty seat; see
        //    BydDataCollector.readDriverOccupancyNow).
        // The schema carries ONE value enum per condition, so "empty" is still offered for the
        // driver seat in the picker. A driver+empty rule parses and loads, but can only ever be
        // satisfied while the state is unseeded — it is documented as unsupported in the
        // description rather than being silently dropped at load, which would delete the user's
        // whole automation (see Automation.fromJson).
        addCondition(new EventCondition(
                new Label("occupant", "automation.occupant"),
                "automation.occupant_description",
                new EnumType(new Label("state", "automation.state"), new Label("occupied", "automation.occupied"), new Label("empty", "automation.empty")),
                new EnumType(
                        new Label("seat", "automation.seat"),
                        new Label("passenger", "automation.passenger"),
                        new Label("driver", "automation.driver"))));
        // User variable / flag — free-text name + string value (eq/neq). Set by the
        // "Set Variable" action; lets an automation gate on its own or another's marker
        // ("if Parking_Mode != true"). VariableCondition handles the free-text name (not
        // an enum), so it's a dedicated subclass rather than a plain EventCondition.
        addCondition(new VariableCondition(
                new Label("variable", "automation.variable"), "automation.variable_description"));
        // Inbound MQTT channel — free-text channel name + string value. Fired by an
        // external broker message on <base>/automation/<channel> (Home Assistant, etc.)
        // via Automations.publishMqttTrigger. Dedicated subclass for the free-text channel
        // (mirrors VariableCondition), keyed identically to BydEvent.mqttTrigger.
        addCondition(new MqttTriggerCondition(
                new Label("mqttTrigger", "automation.mqtt_trigger"), "automation.mqtt_trigger_description"));
        // ── SafeKeep backup signals (published only while SafeKeep is on) ──
        addCondition(new EventCondition(
                new Label("safeKeepState", "automation.safekeep_state"),
                "automation.safekeep_state_description",
                new EnumType(new Label("state", "automation.state"),
                        new Label("idle", "automation.safekeep_idle"),
                        new Label("uploading", "automation.safekeep_uploading"),
                        new Label("paused", "automation.safekeep_paused"),
                        new Label("off", "automation.safekeep_off"))));
        addCondition(new EventCondition(
                new Label("safeKeepBacklog", "automation.safekeep_backlog"),
                "automation.safekeep_backlog_description",
                new IntType(new Label("count", "automation.safekeep_count"), 0, 100000)));
        addCondition(new EventCondition(
                new Label("safeKeepLastError", "automation.safekeep_last_error"),
                "automation.safekeep_last_error_description",
                new EnumType(new Label("error", "automation.safekeep_error"),
                        new Label("none", "automation.safekeep_err_none"),
                        new Label("auth", "automation.safekeep_err_auth"),
                        new Label("quota", "automation.safekeep_err_quota"),
                        new Label("network", "automation.safekeep_err_network"),
                        new Label("other", "automation.safekeep_err_other"))));
        addCondition(new EventCondition(
                new Label("safeKeepClipSaved", "automation.safekeep_clip_saved"),
                "automation.safekeep_clip_saved_description",
                new StringType(new Label("file", "automation.safekeep_file"), 128)));
    }

    /**
     * Add a condition to the map
     * Stored as a map to prevent duplicates
     *
     * @param condition The EventCondition to store
     */
    private void addCondition(EventCondition condition) {
        conditions.put(condition.getLabel().getId(), condition);
    }

    private void addHiddenCondition(EventCondition condition) {
        addCondition(condition);
        hiddenConditions.add(condition.getLabel().getId());
    }

    boolean isAdvertised(String id) {
        return conditions.containsKey(id) && !hiddenConditions.contains(id);
    }

    /**
     * Get a stored condition
     *
     * @param key The id of the condition
     * @return The requested condition
     */
    public EventCondition getCondition(String key) {
        return conditions.get(key);
    }

    /**
     * A JSON representation of the schema for conditions and triggers
     * All automations require at least 1 trigger
     * The triggers are the same as the conditions but without a value or comparator
     *
     * @return A JSON array representation of the Triggers and actions schema
     */
    public JSONArray toJson() {
        JSONArray json = new JSONArray();
        JSONObject triggers = new Label("triggers", "On Change").toJson();
        JSONObject conditions = new Label("conditions", "If").toJson();

        try {
            triggers.put("description", Messages.get("automation.triggers_description"));
            conditions.put("description", Messages.get("automation.conditions_description"));
            JSONArray triggersList = new JSONArray();
            JSONArray conditionsList = new JSONArray();
            boolean includeHybridOnly =
                    com.overdrive.app.automation.AutomationCategories
                            .supportsHybridOnlyItemsOnCurrentVehicle();
            for (EventCondition condition : this.conditions.values()) {
                if (!isAdvertised(condition.getLabel().getId())) continue;
                if (!includeHybridOnly
                        && com.overdrive.app.automation.AutomationCategories.isHybridOnly(
                                condition.getLabel().getId())) {
                    continue;
                }
                String category = com.overdrive.app.automation.AutomationCategories.forId(
                        condition.getLabel().getId());
                JSONObject triggerJson = condition.toJson();
                triggerJson.remove("comparator");
                triggerJson.remove("value");
                triggerJson.put("category", category); // cosmetic grouping only
                triggersList.put(triggerJson);

                // Requires a new copy as removing keys will mutate the object
                JSONObject conditionJson = condition.toJson();
                conditionJson.put("category", category);
                conditionsList.put(conditionJson);
            }
            triggers.put("options", triggersList);
            conditions.put("options", conditionsList);
            triggers.put("required", 1);
            // Conditions are not required as an automation may need to be run when the value changes overall
            conditions.put("required", 0);
            // Advertise the optional AND/OR combining toggle for the conditions
            // section. The form reads formData.conditionLogic ("AND" default / "OR");
            // an automation that never sets it keeps the pre-existing AND behaviour.
            JSONObject logic = new JSONObject();
            logic.put("field", "conditionLogic");
            logic.put("default", "AND");
            JSONArray logicOptions = new JSONArray();
            logicOptions.put(new JSONObject().put("value", "AND").put("label", Messages.get("automation.logic_and")));
            logicOptions.put(new JSONObject().put("value", "OR").put("label", Messages.get("automation.logic_or")));
            logic.put("options", logicOptions);
            conditions.put("logic", logic);
            // Advertise nested-group support: the form may render "add condition group"
            // (each group = its own AND/OR + condition rows), combined with the flat
            // conditions under conditionLogic. Older UIs ignore this flag and render the
            // flat conditions only — still correct, just no group editor.
            conditions.put("groups", true);
            json.put(triggers);
            json.put(conditions);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
