package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.monitor.GearMonitor;

import java.util.Map;

public class BydEvent {
    // Stored as static variables to prevent the EventData objects being created repeatedly
    public static final EventData POWER = new EventData("power");
    public static final EventData GEAR = new EventData("gear");
    public static final EventData WINDOW_LF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lf"));
    public static final EventData WINDOW_RF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rf"));
    public static final EventData WINDOW_LR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "lr"));
    public static final EventData WINDOW_RR_PERCENT = new EventData("windowOpenPercent", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE_PERCENT = new EventData("windowOpenPercent", Map.of("area", "sunshade"));
    public static final EventData WINDOW_LF = new EventData("windowState", Map.of("area", "lf"));
    public static final EventData WINDOW_RF = new EventData("windowState", Map.of("area", "rf"));
    public static final EventData WINDOW_LR = new EventData("windowState", Map.of("area", "lr"));
    public static final EventData WINDOW_RR = new EventData("windowState", Map.of("area", "rr"));
    public static final EventData WINDOW_SUNROOF = new EventData("windowState", Map.of("area", "sunroof"));
    public static final EventData WINDOW_SUNSHADE = new EventData("windowState", Map.of("area", "sunshade"));
    public static final EventData WINDOW_ALL = new EventData("windowState", Map.of("area", "all"));
    public static final EventData BATTERY_LEVEL = new EventData("batteryLevel");
    public static final EventData ESTIMATED_RANGE = new EventData("estimatedRange");
    public static final EventData LIGHTS_LOW_BEAM = new EventData("lights", Map.of("area", "lowBeam"));
    public static final EventData LIGHTS_HIGH_BEAM = new EventData("lights", Map.of("area", "highBeam"));
    public static final EventData LIGHTS_HAZARD = new EventData("lights", Map.of("area", "hazard"));
    public static final EventData LIGHTS_DRL = new EventData("lights", Map.of("area", "drl"));
    // Interior ambient (atmosphere) light main switch. A separate event rather than another
    // "lights" area because it is the only one of that group with a tri-state source: it is
    // published ONLY when the vehicle really reports the switch, so a trim that cannot read it
    // never fires either edge (instead of a permanent, wrong "off").
    public static final EventData AMBIENT_STATE = new EventData("ambient", Map.of());
    public static final EventData SLW = new EventData("slw");
    public static final EventData CPD = new EventData("cpd");
    // Drive mode (normal/eco/sport/snow) on the SETTING drive-config axis, and the
    // EV/HEV powertrain mode — both already on every telemetry snapshot (collectEnergy)
    // and MQTT-published (op_mode / energy_mode). Published as words so a trigger can
    // fire "when drive mode → sport" and a condition can gate "only if in eco".
    public static final EventData DRIVE_MODE = new EventData("driveMode");
    public static final EventData POWERTRAIN_MODE = new EventData("powertrainMode");
    // Central-lock state (locked/unlocked), sourced from the SAME SDK read the sentry
    // arm-gate uses — BYDAutoOtaDevice.getLFDoorLockState (the OTA device caches the LF
    // lock signal even with the BCM asleep, ~1.5s latency), with the BYD-cloud snapshot
    // as fallback. Published by CameraDaemon.applyLockEvent (the single funnel every lock
    // source converges through) so a trigger can fire "when the car locks" and a
    // condition can gate "only while locked". NOT read from the dead BYDAutoDoorLockDevice.
    public static final EventData LOCK = new EventData("lock");
    // Energy-recuperation / regen-braking strength (standard/high/max), read locally via
    // BYDAutoSettingDevice.getEnergyFeedback (app-level 0/1/2) on the telemetry snapshot.
    // A trigger fires "when regen → max"; a condition gates "only if regen is standard".
    public static final EventData ENERGY_REGEN = new EventData("energyRegen");
    public static final EventData SEAT_HEAT_DRIVER = new EventData("seatClimate", Map.of("type", "heat", "area", "driver"));
    public static final EventData SEAT_HEAT_PASSENGER = new EventData("seatClimate", Map.of("type", "heat", "area", "passenger"));
    public static final EventData SEAT_COOL_DRIVER = new EventData("seatClimate", Map.of("type", "cool", "area", "driver"));
    public static final EventData SEAT_COOL_PASSENGER = new EventData("seatClimate", Map.of("type", "cool", "area", "passenger"));
    public static final EventData AC = new EventData("ac");
    // CABIN temperature, parked as well as driving (ambient only as a stopped-sensor fallback).
    public static final EventData TEMPERATURE = new EventData("temperature");
    // Outside/ambient temperature as a first-class event, distinct from TEMPERATURE. This one is
    // ALWAYS the ambient reading (outside cluster sensor, then weather by GPS) so an automation can
    // key off "how cold is it outside" without the cabin sensor ever leaking in.
    public static final EventData OUTSIDE_TEMPERATURE = new EventData("outsideTemp");
    // The AC dial SETPOINT (what was asked for) — a third, distinct axis from the two measured
    // temperatures above. Lets a rule read the dial (${signal:acSetpoint}) or gate on it ("only
    // warm up if the dial is below 20"). Published in CELSIUS like every other temperature event
    // here: a rule written as "setpoint > 20" must not silently mean 20 °F on a Fahrenheit car,
    // and an automation is portable between vehicles in a way a display unit is not.
    public static final EventData AC_SETPOINT = new EventData("acSetpoint");
    // Mean precipitation probability (%) over the next few hours, from Open-Meteo by
    // GPS (same fetch as the weather temperature). Drives "rain likely soon" automations
    // — distinct from the reactive autoWiper "raining now" proxy. Only published with a
    // location fix + a successful fetch, so it never manufactures a false 0%.
    public static final EventData RAIN_PROBABILITY = new EventData("rainProbability");
    // Phone call state (idle/ringing/offhook), relayed from the app process (the daemon
    // has no telephony access). Enables "mute media when a call comes in" etc. Published
    // via Automations.publishExternalEvent from CallStateMonitor.
    public static final EventData CALL_STATE = new EventData("callState");
    // The same "speed" event is stored twice under different units so a condition can
    // pick either without any runtime unit conversion — the km/h value is the canonical
    // BydVehicleData.speedKmh, the mph value is derived once here.
    public static final EventData SPEED_KMPH = new EventData("speed", Map.of("units", "kmph"));
    public static final EventData SPEED_MPH = new EventData("speed", Map.of("units", "mph"));
    // Dynamic-driving inputs, already ingested on every snapshot (accelPercent /
    // brakePercent via BYDAutoSpeedDevice.getAccelerateDeepness/getBrakeDeepness,
    // steeringAngleDegrees via BYDAutoBodyworkDevice.getSteeringWheelValue). These
    // update at the telemetry cadence (5s ACC-on); for sub-second gestures the
    // RoadSense fast-dynamics poll (250ms) refreshes accel/brake, but automations
    // key off value transitions so a 5s floor is fine for "pedal past X%" rules.
    public static final EventData ACCELERATOR = new EventData("accelerator");
    public static final EventData BRAKE = new EventData("brake");
    // Steering angle is signed (SDK: negative = left, positive = right, ±780° range).
    // Published as-is so a condition can gate on magnitude (abs handled UI-side by
    // offering a symmetric range) or direction.
    public static final EventData STEERING_ANGLE = new EventData("steeringAngle");
    // Turn indicators. Sourced from the reliable combined getTurnLightFlashState
    // (readTurnNow), NOT the per-side getTurnLightState which is dead on this
    // firmware. Published on/off per side (see updateTurnSignals).
    public static final EventData TURN_LEFT = new EventData("turnSignal", Map.of("side", "left"));
    public static final EventData TURN_RIGHT = new EventData("turnSignal", Map.of("side", "right"));
    // Radar blind-spot / lane-change / cross-traffic ALERT, per side. Distinct
    // from the blind-spot camera overlay: this is the OEM radar warning. Driven by
    // BlindSpotEvent (instant ADAS event + fast poll), with an alert hold so a
    // momentary pulse becomes a stable on/off edge.
    public static final EventData BLIND_SPOT_LEFT = new EventData("blindSpot", Map.of("side", "left"));
    public static final EventData BLIND_SPOT_RIGHT = new EventData("blindSpot", Map.of("side", "right"));
    // Pushed once a minute by TimeEvent (not from a vehicle-data snapshot).
    public static final EventData TIME = new EventData("time");
    public static final EventData DAY = new EventData("day");
    // Calendar + solar signals published by TimeEvent alongside TIME/DAY. dayOfMonth
    // (1-31) and month (1-12) enable date/monthly automations; sunPhase (day/night)
    // flips at local sunrise/sunset computed from GPS — the trigger fires on the
    // transition, so "at sunset" = sunPhase becomes "night".
    public static final EventData DAY_OF_MONTH = new EventData("dayOfMonth");
    public static final EventData MONTH = new EventData("month");
    public static final EventData SUN_PHASE = new EventData("sunPhase");
    // Pushed by NetworkEvent on a low-cadence poll (not from a vehicle-data snapshot).
    // wifiState is on/off; wifiSsid is the connected network name (or "" when off) so a
    // "bluetooth-by-name"-style condition can match a specific WiFi SSID.
    public static final EventData WIFI_STATE = new EventData("wifiState");
    public static final EventData WIFI_SSID = new EventData("wifiSsid");
    // Relayed from the app-process BluetoothStateMonitor via Automations.publishExternalEvent
    // (the daemon can't reliably read BT from UID 2000 — see that class). btState is
    // connected/disconnected; btDeviceName is the connected device's friendly name (or ""
    // when disconnected) so a "connect to <name>" condition can match a specific phone —
    // the BT analogue of WIFI_SSID.
    public static final EventData BT_STATE = new EventData("btState");
    public static final EventData BT_DEVICE_NAME = new EventData("btDeviceName");
    // Published by SafeLocationManager on a geofence transition: the name of the
    // zone the car is currently inside, or "none" when outside every zone. Lets an
    // automation trigger on entering/leaving a map-picked location (the same zones
    // the Safe Locations editor manages), e.g. "when location = Home → …". Reuses
    // the existing zone list + Haversine, so the user picks locations exactly like
    // safe zones (down to a 15m radius).
    public static final EventData LOCATION_ZONE = new EventData("locationZone");
    // Inbound MQTT: an external broker message (e.g. from Home Assistant) published to
    // <base>/automation/<channel> becomes an automation signal keyed by channel, so a
    // rule can trigger on "HA published X to channel Y". Built per-channel at publish
    // time (mqttTrigger + {channel: <name>}); see Automations.publishMqttTrigger and the
    // MqttPublisherService subscribe/messageArrived seam.
    public static EventData mqttTrigger(String channel) {
        return new EventData("mqttTrigger", java.util.Map.of("channel", channel));
    }
    // ── Surveillance / sentry events ──────────────────────────────────────
    // Published by the surveillance engine (SurveillanceEngineGpu) — the daemon's
    // parked-guard verdicts, so an automation can react to what the sentry sees
    // (e.g. "when a person is detected while parked → flash lights"). These are
    // published from the COLD per-event path (publishMotionFinal / arm / disarm),
    // never the hot GL frame loop, and mirror LOCATION_ZONE's "daemon class calls
    // Automations.update, no local latch" pattern. All are inherently gated by the
    // sentry being armed (the pipeline only runs ACC-off + armed), which is the
    // correct "while parked" semantics.
    //
    // surveillanceArmed: on when the sentry is armed/watching, off when disarmed —
    // lets a rule gate on "while the car is being guarded".
    public static final EventData SURVEILLANCE_ARMED = new EventData("surveillanceArmed");
    // surveillanceThreat: the worst severity classified in the just-recorded event —
    // notice / alert / critical. Fires once per event as the .mp4 finalizes.
    public static final EventData SURVEILLANCE_THREAT = new EventData("surveillanceThreat");
    // surveillanceObject: the headline object class seen in the event —
    // person / vehicle / bike / animal (or "none" when motion recorded with no
    // classified actor). Ranked person > bike > vehicle > animal.
    public static final EventData SURVEILLANCE_OBJECT = new EventData("surveillanceObject");
    // ── Safety / ADAS events ─────────────────────────────────────────────
    // Emergency alarm (BODYWORK_EMERGENCY_ALARM). The closest signal this HAL
    // exposes to an "incident/accident" event — no true collision/airbag event
    // exists on this firmware (the HAL surfaces no such readable signal), so this
    // is the best available proxy. Published on/off from a raw non-zero transition.
    public static final EventData EMERGENCY_ALARM = new EventData("emergencyAlarm");
    // Tyre safety warnings — genuine "warning fired" states from the TPMS. Per
    // wheel: pressure (normal/under/over) and air-leak (normal/slow/fast). An
    // "any wheel abnormal" convenience is also published so a user can trigger on
    // "any tyre warning" without wiring four conditions.
    public static final EventData TYRE_PRESSURE_WARN = new EventData("tyrePressureWarn");
    public static final EventData TYRE_LEAK_WARN = new EventData("tyreLeakWarn");
    // ── Charging ──────────────────────────────────────────────────────────
    // Charging on/off — the FUSED ChargingDetector verdict (BMS + charge power +
    // gun state + plug), not a raw BMS int, so it matches what the app shows.
    // "gun connected" is a separate physical-plug edge (a plugged-in car isn't
    // necessarily charging yet — e.g. scheduled/delayed charging).
    public static final EventData CHARGING_STATE = new EventData("chargingState");
    public static final EventData CHARGE_GUN = new EventData("chargeGun");
    // ── Battery health / auxiliary batteries ─────────────────────────────
    public static final EventData BATTERY_SOH = new EventData("batterySoh");
    public static final EventData KEY_BATTERY = new EventData("keyBattery");
    public static final EventData AUX_BATTERY_12V = new EventData("aux12vBattery");
    // ── Fuel (PHEV) ───────────────────────────────────────────────────────
    public static final EventData FUEL_LEVEL = new EventData("fuelLevel");
    // ── Air quality (PM2.5, µg/m³) inside + outside ───────────────────────
    public static final EventData PM25_INSIDE = new EventData("pm25", Map.of("area", "inside"));
    public static final EventData PM25_OUTSIDE = new EventData("pm25", Map.of("area", "outside"));
    // ── Road slope / incline (signed degrees) ─────────────────────────────
    public static final EventData SLOPE = new EventData("slope");
    // ── Seatbelts — per seat, buckled/unbuckled. Areas verified against the OEM
    // firmware (see collectSafetyBelt); index 1=driver, 2=front passenger. Value
    // is sanitized to on(buckled)/off(unbuckled), failure codes dropped. ──────
    public static final EventData SEATBELT_DRIVER = new EventData("seatbelt", Map.of("seat", "driver"));
    public static final EventData SEATBELT_PASSENGER = new EventData("seatbelt", Map.of("seat", "passenger"));
    // ── Parking-radar nearest-obstacle distance (cm). Worst (closest) of all
    // zones so a single "obstacle within X" trigger works without wiring 8 zones. ─
    public static final EventData RADAR_NEAREST = new EventData("radarNearest");
    // ── Tier-2 sensors (wired from previously-unused SDK getters) ─────────
    // Auto-wiper engaged — the closest "it's raining" proxy this platform has (no
    // rain-intensity sensor exists). Wiper activity is a separate on/off.
    public static final EventData AUTO_WIPER = new EventData("autoWiper");
    public static final EventData WIPER_ACTIVE = new EventData("wiperActive");
    // Auto-headlights engaged (the light sensor turned the lamps on) — the usable
    // "it's dark" proxy; there is no lux value on this platform.
    public static final EventData AUTO_LIGHTS = new EventData("autoLights");
    // Seat occupancy (someone sitting). The PASSENGER seat has a real occupancy sensor
    // (getPassengerStatus area 1) and publishes both occupied and empty.
    public static final EventData OCCUPANT_PASSENGER = new EventData("occupant", Map.of("seat", "passenger"));
    // The DRIVER seat has NO occupancy sensor, so its presence is INFERRED from the
    // seatbelt-reminder mask (bit 0) and the driver belt — see
    // BydDataCollector.readDriverOccupancyNow. POSITIVE-ONLY: publishes "occupied" and never
    // "empty", because an unbuckled-but-present driver is indistinguishable from an empty seat.
    public static final EventData OCCUPANT_DRIVER = new EventData("occupant", Map.of("seat", "driver"));
    // User variables / flags (see SetVariableAction + VariableCondition). Not a vehicle
    // signal — a named marker the user sets and reads to coordinate automations (mutex,
    // mode flags). The state key is EventData("variable", {name}); this is just the
    // shared "variable" type string both the setter and the condition key by.
    public static final String VARIABLE_TYPE = "variable";
    // Pushed once by NetworkEvent shortly after the daemon starts. "on" is only
    // published when this is a genuine device boot (see NetworkEvent.publishBoot),
    // never on a mere daemon restart, so boot automations don't re-fire on every relaunch.
    public static final EventData BOOT = new EventData("boot");

    // Turn-indicator blink off-debounce (ms). The lamp toggles ~1.5 Hz, so a single
    // "signalling" gesture would otherwise strobe the on/off event. Hold "on" until
    // the lamp has been continuously dark for this long.
    //
    // SIZING: this must exceed the SAMPLING interval AND the blink off-phase (~330ms at
    // 1.5Hz). Turn signals are now sampled by the dedicated TurnSignalEvent poll at
    // 500ms (see pollTurnSignals) — far tighter than the old ~5s stationary snapshot
    // cadence that forced a 6s debounce. At 1.5s the window spans ~3 fast polls and
    // comfortably bridges a blink off-phase, so a still-signalling lamp never reports a
    // spurious "off", while the genuine OFF edge now lags only ~1.5s after cancel
    // (was ~6s) — the responsiveness the "long delay" report was about, on both edges.
    private static final long TURN_OFF_DEBOUNCE_MS = 1500L;
    // Per-side clock of the last observed on-phase, for the blink debounce above.
    // Static; the single-threaded TurnSignalEvent poll is the sole writer — 0 means
    // "never seen lit".
    private static final java.util.concurrent.atomic.AtomicLong lastLeftOnMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong lastRightOnMs = new java.util.concurrent.atomic.AtomicLong(0);

    private BydEvent() {}

    /**
     * This class is created to make gathering events easier
     * As the BydVehicleData is not updated often, this does not affect app performance
     * If this changes in the future, Automations.update should be called directly when an event is triggered
     * This would allow it to update a single variable instead of updating all variables when a single value changes
     *
     * @param data The current BydVehicleData with the vehicle state
     */
    public static void bydEvent(BydVehicleData data) {
        // Do nothing when no automations enabled
        if (Automations.isDisabled()) return;

        Automations.update(POWER, BodyworkConstants.powerLevelToString(data.powerLevel).toLowerCase());
        Automations.update(GEAR, GearMonitor.gearToString(data.gearMode).toLowerCase());
        // windowOpenPercent is nullable (defaults null; only populated when the bodywork HAL device is
        // present, and may be left null if the HAL reflection threw), and the HAL fills unavailable /
        // unreadable slots with a negative sentinel (-1). Guard the whole block so the telemetry poll
        // loop (build sites are not wrapped in try/catch) never NPEs, index every slot defensively by
        // length, and skip sentinel slots — otherwise a -1 would map to "open" (since -1 != 0) and
        // false-fire a "window open" automation, and would keep WINDOW_ALL from ever reporting "closed".
        int[] win = data.windowOpenPercent;
        if (win != null) {
            if (win.length > 0) updateWindow(WINDOW_LF_PERCENT, WINDOW_LF, win[0]);
            if (win.length > 1) updateWindow(WINDOW_RF_PERCENT, WINDOW_RF, win[1]);
            if (win.length > 2) updateWindow(WINDOW_LR_PERCENT, WINDOW_LR, win[2]);
            if (win.length > 3) updateWindow(WINDOW_RR_PERCENT, WINDOW_RR, win[3]);
            if (win.length > 4) updateWindow(WINDOW_SUNROOF_PERCENT, WINDOW_SUNROOF, win[4]);
            if (win.length > 5) updateWindow(WINDOW_SUNSHADE_PERCENT, WINDOW_SUNSHADE, win[5]);

            // WINDOW_ALL is a convenience shortcut ("are all windows shut?"). Only meaningful once we
            // have at least one real reading: "closed" iff every available (non-negative) slot is 0.
            boolean anyKnown = false, anyOpen = false;
            for (int percent : win) {
                if (percent < 0) continue; // unavailable slot — ignore
                anyKnown = true;
                if (percent != 0) anyOpen = true;
            }
            if (anyKnown) Automations.update(WINDOW_ALL, anyOpen ? "open" : "closed");
        }
        if (!Double.isNaN(data.socPercent)) Automations.update(BATTERY_LEVEL, (int) data.socPercent);
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.elecRangeKm);
        } else if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.bodyworkRangeKm);
        }
        Automations.update(LIGHTS_LOW_BEAM, data.lowBeam ? "on" : "off");
        Automations.update(LIGHTS_HIGH_BEAM, data.highBeam ? "on" : "off");
        Automations.update(LIGHTS_HAZARD, data.hazard ? "on" : "off");
        Automations.update(LIGHTS_DRL, data.dayTimeLight ? "on" : "off");
        // Ambient main switch — only on a real reading (UNAVAILABLE → publish nothing, so an
        // unreadable switch cannot fire a spurious "off" trigger on every poll).
        if (data.ambientEnabled != BydVehicleData.UNAVAILABLE) {
            Automations.update(AMBIENT_STATE, data.ambientEnabled == 1 ? "on" : "off");
        }
        Automations.update(SLW, data.speedLimitWarning ? "on" : "off");
        Automations.update(CPD, cpdToString(data.childPresenceDetection));
        if (data.seatHeat != null) {
            if (data.seatHeat.length > 0) Automations.update(SEAT_HEAT_DRIVER, seatClimateToString(data.seatHeat[0]));
            if (data.seatHeat.length > 1) Automations.update(SEAT_HEAT_PASSENGER, seatClimateToString(data.seatHeat[1]));
        }
        if (data.seatCool != null) {
            if (data.seatCool.length > 0) Automations.update(SEAT_COOL_DRIVER, seatClimateToString(data.seatCool[0]));
            if (data.seatCool.length > 1) Automations.update(SEAT_COOL_PASSENGER, seatClimateToString(data.seatCool[1]));
        }
        boolean poweredOn = data.powerLevel >= 2;
        Automations.update(AC, (poweredOn && data.acStartState == 1) ? "on" : "off");
        // Temperature: the CABIN sensor, parked as well as driving (the outside cluster
        // sensor, then the Open-Meteo weather API by last-known GPS, are used only if the
        // cabin sensor stops answering). The weather read is CACHED + refreshed on a
        // background thread — never a network call on this telemetry-poll thread. See
        // WeatherTemperature for the ACC-off-blackout-tolerant fetch model, and
        // updateTemperature for the freshness/hold rules.
        updateTemperature(data);
        // speedKmh is canonical km/h (already scaled by distanceToKmFactor at ingestion),
        // so the mph value is a straight km→mi conversion. Guard NaN (unreadable speed).
        if (!Double.isNaN(data.speedKmh)) {
            Automations.update(SPEED_KMPH, (int) Math.round(data.speedKmh));
            Automations.update(SPEED_MPH, (int) Math.round(data.speedKmh * 0.621371));
        }
        // Pedal deepness (0-100). UNAVAILABLE (Integer.MIN_VALUE) until the speed
        // device first reports; skip the sentinel so a condition never sees a bogus
        // huge-negative value and the event keeps its last real reading.
        if (data.accelPercent != BydVehicleData.UNAVAILABLE) {
            Automations.update(ACCELERATOR, data.accelPercent);
        }
        if (data.brakePercent != BydVehicleData.UNAVAILABLE) {
            Automations.update(BRAKE, data.brakePercent);
        }
        // Steering angle (signed degrees). NaN until the bodywork device reports.
        if (!Double.isNaN(data.steeringAngleDegrees)) {
            Automations.update(STEERING_ANGLE, (int) Math.round(data.steeringAngleDegrees));
        }
        // Turn indicators are NOT sampled here: this snapshot path runs only every ~5s
        // while stationary, which lagged the trigger by up to that long (a turn signal
        // is most often used while stopped). They're published by the dedicated
        // fast-cadence TurnSignalEvent poll (self-gated on a real turn-signal automation)
        // via pollTurnSignals() below, so the on-edge is caught within one blink.
        updateSafetyEvents(data);
        updateExtendedSignals(data);
    }

    /**
     * Publish the Tier-1/Tier-2 extended signals: charging, battery health, fuel, air
     * quality, slope, aux/key batteries, seatbelts, radar proximity, and the wired
     * wiper/auto-light/occupancy sensors. Each guards its own sentinel so an
     * unavailable field stays unseeded (no spurious edge). All values are already on
     * the snapshot by the time bydEvent runs.
     */
    private static void updateExtendedSignals(BydVehicleData data) {
        // ── Charging (fused verdict, not raw BMS) + gun-connected edge ──
        try {
            boolean charging = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            Automations.update(CHARGING_STATE, charging ? "on" : "off");
        } catch (Throwable ignored) { /* detector not ready — skip this tick */ }
        // Gun connected: BYD gun states 2..5 are physically plugged (3=DC fast, 5=V2L).
        // UNAVAILABLE until reported → leave unseeded.
        if (data.chargingGunState != BydVehicleData.UNAVAILABLE) {
            boolean connected = data.chargingGunState >= 2 && data.chargingGunState <= 5;
            Automations.update(CHARGE_GUN, connected ? "connected" : "disconnected");
        }
        // ── Battery health + auxiliary batteries (percent) ──
        // Use the same canonical OEM-first resolver as every UI/API/integration. During
        // the first collector build the new snapshot is not published until after this
        // callback, so fall back to this build's validated OEM field for that one tick.
        double canonicalSoh = -1;
        try {
            com.overdrive.app.abrp.SohEstimator estimator =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (estimator != null) canonicalSoh = estimator.getDisplaySoh();
        } catch (Throwable ignored) {}
        if (canonicalSoh <= 0 && !Double.isNaN(data.sohPercent)
                && data.sohPercent > 0 && data.sohPercent <= 100) {
            canonicalSoh = data.sohPercent;
        }
        if (canonicalSoh > 0) {
            Automations.update(BATTERY_SOH, (int) Math.round(canonicalSoh));
        }
        // Key-fob battery is a 2-state ENUM (0=low, 1=normal), NOT a percent — publish
        // low/normal so a "key battery low" trigger is meaningful. Any other value is
        // not a real reading → skip.
        if (data.keyBatteryLevel == 0) Automations.update(KEY_BATTERY, "low");
        else if (data.keyBatteryLevel == 1) Automations.update(KEY_BATTERY, "normal");
        // 12V battery is a LOW/NORMAL/INVALID enum (0=low, 1=normal, 255=invalid) — same
        // treatment; INVALID and anything else are dropped rather than published.
        if (data.battery12vLevel == 0) Automations.update(AUX_BATTERY_12V, "low");
        else if (data.battery12vLevel == 1) Automations.update(AUX_BATTERY_12V, "normal");
        // ── Fuel (PHEV), percent — double with NaN = unavailable ──
        if (!Double.isNaN(data.fuelPercent) && data.fuelPercent >= 0) {
            Automations.update(FUEL_LEVEL, (int) Math.round(data.fuelPercent));
        }
        // ── Air quality PM2.5 (µg/m³), inside + outside ──
        if (data.pm25Inside != BydVehicleData.UNAVAILABLE && data.pm25Inside >= 0) {
            Automations.update(PM25_INSIDE, data.pm25Inside);
        }
        if (data.pm25Outside != BydVehicleData.UNAVAILABLE && data.pm25Outside >= 0) {
            Automations.update(PM25_OUTSIDE, data.pm25Outside);
        }
        // ── Road slope (signed degrees). NaN until the sensor reports. ──
        if (!Double.isNaN(data.slopeDegrees)) {
            Automations.update(SLOPE, (int) Math.round(data.slopeDegrees));
        }
        // ── Parking-radar nearest obstacle (cm): the minimum non-sentinel zone. ──
        updateRadarNearest(data);
        // ── Seatbelts (sanitized on/off per seat). ──
        updateSeatbelts(data);
        // ── Tier-2 sensors ──
        // Auto-wiper engaged (rain proxy): 1=on.
        if (data.autoWiperState != BydVehicleData.UNAVAILABLE) {
            Automations.update(AUTO_WIPER, data.autoWiperState == 1 ? "on" : "off");
        }
        // Wiper active: any non-zero raw wiper state = wiping. UNAVAILABLE → unseeded.
        if (data.wiperState != BydVehicleData.UNAVAILABLE) {
            Automations.update(WIPER_ACTIVE, data.wiperState != 0 ? "on" : "off");
        }
        // Auto-headlights engaged ("it's dark" proxy): 1=on.
        if (data.lightAutoStatus != BydVehicleData.UNAVAILABLE) {
            Automations.update(AUTO_LIGHTS, data.lightAutoStatus == 1 ? "on" : "off");
        }
        // Seat occupancy per seat: 1=someone, 0=nobody, UNAVAILABLE→skip.
        // passengerDetection is a 1-slot front-passenger array (see readOccupantsNow) —
        // its shape is frozen at one slot because it is serialized positionally to MQTT/JSON.
        int[] occ = data.passengerDetection;
        if (occ != null && occ.length > 0) {
            publishOccupant(OCCUPANT_PASSENGER, occ[0]);
        }
        // Inferred DRIVER presence from the snapshot's already-sanitized driver belt (slot 0):
        // buckled ⇒ someone is there. Deliberately NO HAL read here — this runs inside every
        // build(), so the reminder-mask tier stays on the fast poller (pollDriverOccupant).
        // That asymmetry is safe BECAUSE the event is positive-only and monotonic: both paths
        // can only ever publish "occupied", so a subset of tiers here can never contradict the
        // poller — it only means presence may be established a tick later on this path.
        int[] belts = data.seatbeltStatus;
        if (belts != null && belts.length > 0 && belts[0] == 1) {
            publishOccupant(OCCUPANT_DRIVER, 1);
        }
        // Drive mode on the config axis (1=normal..4=snow); only publish an in-band
        // value so a trim without the drive-config getter (which can only see eco/sport
        // via the energy fallback) never manufactures a spurious "normal"/"snow" edge.
        String driveMode = driveModeToString(data.operationMode);
        if (driveMode != null) Automations.update(DRIVE_MODE, driveMode);
        // Powertrain EV/HEV (PHEV only). Raw SDK energy-mode int: ENERGY_MODE_EV=1,
        // ENERGY_MODE_HEV=3 (0=STOP, NOT ev — matches the MQTT powertrain_mode mapping).
        // Anything else (incl. STOP and the unavailable sentinel) is dropped.
        if (data.energyMode == 1) Automations.update(POWERTRAIN_MODE, "ev");
        else if (data.energyMode == 3) Automations.update(POWERTRAIN_MODE, "hev");

        // Energy-recuperation (regen) level is published by the dedicated fast poller
        // EnergyRegenEvent (1s, self-gated on isEventReferenced) — NOT here. Reading it on
        // the ~5s snapshot lagged a regen change 2-4s (the reported delay); the fast poller
        // catches it within ~1s. Kept off the snapshot so build() does no regen SDK read.
    }

    /** Map the drive-config axis to a word, or null if not an in-band reading. */
    private static String driveModeToString(int mode) {
        switch (mode) {
            case 1:  return "normal";
            case 2:  return "eco";
            case 3:  return "sport";
            case 4:  return "snow";
            default: return null; // 0/-1/unset → unseeded (no spurious edge)
        }
    }

    /** Publish one seat's occupancy: 1→occupied, 0→empty, else skip (unseeded). */
    private static void publishOccupant(EventData key, int v) {
        if (v == 1) Automations.update(key, "occupied");
        else if (v == 0) Automations.update(key, "empty");
    }

    /**
     * Publish the closest parking-radar obstacle distance across all zones (cm), so a
     * single "obstacle within X" trigger works. radarDistances is per-zone; zones with
     * no reading use a sentinel we skip. Publishes nothing when no zone has a reading.
     */
    private static void updateRadarNearest(BydVehicleData data) {
        int[] zones = data.radarDistances;
        if (zones == null || zones.length == 0) return;
        int nearest = Integer.MAX_VALUE;
        for (int d : zones) {
            // Skip sentinels: negative, 0 (no-object on this HAL), and the >=155 "clear"
            // ceiling the SDK reports when nothing is in range.
            if (d > 0 && d < 155 && d < nearest) nearest = d;
        }
        if (nearest != Integer.MAX_VALUE) {
            Automations.update(RADAR_NEAREST, nearest);
        }
    }

    /**
     * Publish per-seat seatbelt buckled/unbuckled. The collector
     * ({@link com.overdrive.app.byd.BydDataCollector#collectSafetyBelt}) already
     * sanitizes to a clean {@code 0 = unbuckled}, {@code 1 = buckled}, or UNAVAILABLE,
     * reading the instrument device's dedicated {@code getSafetyBeltStatus(int)} getter
     * (the same live path the telemetry-recording overlay uses). seatbeltStatus is a
     * 2-slot array: index 0 = driver, index 1 = front passenger.
     */
    private static void updateSeatbelts(BydVehicleData data) {
        int[] belts = data.seatbeltStatus;
        if (belts == null) return;
        if (belts.length > 0) publishSeatbelt(SEATBELT_DRIVER, belts[0]);
        if (belts.length > 1) publishSeatbelt(SEATBELT_PASSENGER, belts[1]);
    }

    /** Publish one seat's already-sanitized belt state: 1→on(buckled), 0→off(unbuckled),
     *  UNAVAILABLE→skip (unseeded). */
    private static void publishSeatbelt(EventData key, int v) {
        if (v == 1) Automations.update(key, "on");
        else if (v == 0) Automations.update(key, "off");
        // UNAVAILABLE / anything else → publish nothing (no false reading)
    }

    /**
     * Sample + publish per-seat seatbelt state NOW (called by the fast {@link
     * com.overdrive.app.automation.condition.SeatbeltEvent} poll). Reads the live
     * {@code getSafetyBeltStatus(area)} getter directly and publishes each seat via the
     * same {@link #publishSeatbelt} (→ Automations.update fires On Change on a change).
     * This is the belt equivalent of {@link #pollTurnSignals}: without it the belt state
     * was sampled only on the 5s telemetry poll, so a buckle took up to ~5s (avg 2-3s) to
     * fire its automation. Automations.update itself dedups, so this is a true no-op when
     * the value hasn't changed since the last sample.
     */
    public static void pollSeatbelts() {
        if (Automations.isDisabled()) return;
        int[] belts;
        try {
            belts = com.overdrive.app.byd.BydDataCollector.getInstance().readSeatbeltsNow();
        } catch (Throwable t) {
            return; // instrument device unreachable this tick — leave the events untouched
        }
        if (belts == null) return; // no real reading → unseeded (no false publish)
        if (belts.length > 0) publishSeatbelt(SEATBELT_DRIVER, belts[0]);
        if (belts.length > 1) publishSeatbelt(SEATBELT_PASSENGER, belts[1]);
    }

    /**
     * Sample + publish per-seat OCCUPANCY now (called by the fast {@link SeatbeltEvent} poll).
     * Reads the live {@code getPassengerStatus(area)} getter via the same collector helper the
     * 5s snapshot uses and publishes through the same {@link #publishOccupant} path, so the
     * edge/dedup semantics are identical — just sampled faster. Without this, occupancy rode
     * only the telemetry snapshot (~5s driving, 90s parked), and getting in/out of a parked car
     * is exactly the parked case — so the trigger lagged by up to 90s.
     */
    public static void pollOccupants() {
        if (Automations.isDisabled()) return;
        int[] occ;
        try {
            occ = com.overdrive.app.byd.BydDataCollector.getInstance().readOccupantsNow();
        } catch (Throwable t) {
            return; // safety-belt device unreachable this tick — leave the events untouched
        }
        if (occ == null || occ.length < 1) return; // no real reading → unseeded (no false publish)
        publishOccupant(OCCUPANT_PASSENGER, occ[0]);
    }

    /**
     * Sample + publish inferred DRIVER presence now (called by the fast {@link SeatbeltEvent}
     * poll, separately gated so a driver rule doesn't pay for the passenger reads or vice versa).
     *
     * <p>Publishes ONLY "occupied" — {@link com.overdrive.app.byd.BydDataCollector#readDriverOccupancyNow}
     * returns UNAVAILABLE rather than 0 when there is no positive evidence, and
     * {@link #publishOccupant} skips UNAVAILABLE. So this event goes {@code unseeded → occupied}
     * and then stays there for the rest of the process; it never reports "empty".
     *
     * <p><b>Consequence, by design:</b> a rule triggered on {@code occupant{seat:driver}} fires
     * once when presence is first established, not on every entry/exit. Use
     * {@code seatbelt{seat:driver}} for buckle/unbuckle edges. Withholding the "empty" edge is
     * deliberate — see readDriverOccupancyNow for why a 0 would be a fabricated reading.
     */
    public static void pollDriverOccupant() {
        if (Automations.isDisabled()) return;
        int occ;
        try {
            occ = com.overdrive.app.byd.BydDataCollector.getInstance().readDriverOccupancyNow();
        } catch (Throwable t) {
            return; // instrument/safety-belt device unreachable this tick — leave the event untouched
        }
        publishOccupant(OCCUPANT_DRIVER, occ);
    }

    /**
     * Sample + publish the turn indicators NOW (called by the fast {@link
     * com.overdrive.app.automation.condition.TurnSignalEvent} poll). Reads the live
     * combined lamp getter and applies the same debounce as the old snapshot path —
     * only the cadence changed (faster), not the edge semantics.
     */
    public static void pollTurnSignals() {
        // The fast poll already gates on Automations.isEventReferenced, but re-guard on
        // isDisabled so a race that disables the last automation mid-tick is a no-op
        // (mirrors Automations.update's own guard; keeps this a true no-op when off).
        if (Automations.isDisabled()) return;
        updateTurnSignals();
    }

    /**
     * Publish the current drive mode from a LIVE read, for the fast {@link DriveModeEvent}
     * poller — so a "when drive mode → sport" trigger fires within the fast cadence instead
     * of lagging up to the ~5s (driving) / longer (parked) telemetry-snapshot interval it
     * was previously sampled on (via {@code bydEvent}'s collectEnergy path). Reads the same
     * config axis ({@link com.overdrive.app.byd.BydDataCollector#getDriveConfigMode()}) and
     * publishes through the same {@link #DRIVE_MODE} + {@link #driveModeToString} path, so the
     * edge/dedup semantics are identical — just sampled faster. Only an in-band 1..4 reading
     * publishes; -1/unset is skipped so a trim without the getter never manufactures a
     * spurious edge (Automations.update is transition-gated, so a repeat is a no-op).
     */
    public static void pollDriveMode() {
        if (Automations.isDisabled()) return;
        int mode = com.overdrive.app.byd.BydDataCollector.getInstance().getDriveConfigMode();
        String word = driveModeToString(mode);
        if (word != null) Automations.update(DRIVE_MODE, word);
    }

    /**
     * Publish the current GEAR from a LIVE read, for the fast {@link GearEvent} poller — so a
     * "when gear → R" / "only while in P" trigger fires promptly instead of lagging up to the
     * ~5s telemetry snapshot (gear rides {@code bydEvent}'s collectGearbox, which runs only on
     * the periodic poll and only while ACC is on). Reads the SAME getter the 5s poll uses
     * ({@link com.overdrive.app.byd.BydDataCollector#readGearNow()} → getGearboxAutoModeType)
     * — NOT the crashing learningEPB() listener path that forced the gearbox HAL listener to
     * be disabled — and publishes through the same {@link #GEAR} + {@link
     * com.overdrive.app.monitor.GearMonitor#gearToString} path, so the edge/dedup semantics
     * are identical to the snapshot path, just sampled faster. UNAVAILABLE is skipped so a
     * trim without the getter never manufactures a spurious edge.
     */
    public static void pollGear() {
        if (Automations.isDisabled()) return;
        int gear = com.overdrive.app.byd.BydDataCollector.getInstance().readGearNow();
        if (gear == BydVehicleData.UNAVAILABLE) return;
        Automations.update(GEAR, GearMonitor.gearToString(gear).toLowerCase());
    }

    /**
     * Publish per-seat seat-heat / seat-cool AND high/low beam from LIVE reads, for the fast
     * {@link ClimateEvent} poller — so a "when driver seat cooling turns off" (the reported
     * seat-cooling ELSE) or "when high beam on" trigger fires promptly instead of riding the
     * ~5s telemetry snapshot ({@code bydEvent}'s collectSettings / collectLight path). Each
     * signal is read via its dedicated collector getter and published through the SAME
     * {@link #SEAT_HEAT_DRIVER}/{@link #SEAT_COOL_DRIVER}/… + {@link #seatClimateToString} and
     * {@link #LIGHTS_HIGH_BEAM}/{@link #LIGHTS_LOW_BEAM} paths, so edge/dedup semantics match
     * the snapshot path exactly. UNAVAILABLE reads are skipped (no spurious edge). Each seat /
     * beam is gated on its OWN {@link Automations#isEventReferenced} so a rule using only one
     * of them never reads the others' SDK getters.
     */
    public static void pollClimate() {
        if (Automations.isDisabled()) return;
        com.overdrive.app.byd.BydDataCollector collector =
                com.overdrive.app.byd.BydDataCollector.getInstance();
        // Seat cooling (ventilation) — driver + passenger.
        if (Automations.isEventReferenced(SEAT_COOL_DRIVER)) {
            int v = collector.readSeatClimateNow(false, 1);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_COOL_DRIVER, seatClimateToString(v));
        }
        if (Automations.isEventReferenced(SEAT_COOL_PASSENGER)) {
            int v = collector.readSeatClimateNow(false, 2);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_COOL_PASSENGER, seatClimateToString(v));
        }
        // Seat heating — driver + passenger.
        if (Automations.isEventReferenced(SEAT_HEAT_DRIVER)) {
            int v = collector.readSeatClimateNow(true, 1);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_HEAT_DRIVER, seatClimateToString(v));
        }
        if (Automations.isEventReferenced(SEAT_HEAT_PASSENGER)) {
            int v = collector.readSeatClimateNow(true, 2);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_HEAT_PASSENGER, seatClimateToString(v));
        }
        // AC dial setpoint — only read when a rule actually references it (same gate as every
        // other entry here), so a car whose dial is unreadable pays nothing.
        if (Automations.isEventReferenced(AC_SETPOINT)) {
            int v = collector.readAcSetpointNow(
                    com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_DRIVER);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(AC_SETPOINT, setpointCelsius(v));
        }
        // High / low beam.
        if (Automations.isEventReferenced(LIGHTS_HIGH_BEAM)) {
            int v = collector.readBeamNow(true);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(LIGHTS_HIGH_BEAM, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(LIGHTS_LOW_BEAM)) {
            int v = collector.readBeamNow(false);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(LIGHTS_LOW_BEAM, v == 1 ? "on" : "off");
        }
    }

    /**
     * Publish the safety/ADAS events. This HAL exposes no true collision/accident
     * event (no such readable signal exists on this firmware), so we surface the
     * genuine "something fired" signals it DOES have: the emergency alarm (best
     * incident proxy) and the TPMS pressure/leak warnings.
     */
    private static void updateSafetyEvents(BydVehicleData data) {
        // Emergency alarm: raw enum, UNAVAILABLE until reported. Any non-zero value
        // is an active alarm → "on"; 0 → "off". Skip the sentinel so a never-reported
        // alarm stays unseeded (never a spurious "off" at boot before the first read).
        if (data.emergencyAlarmState != BydVehicleData.UNAVAILABLE) {
            Automations.update(EMERGENCY_ALARM, data.emergencyAlarmState != 0 ? "on" : "off");
        }
        // Tyre pressure: worst state across the four wheels (0=normal,1=under,2=over).
        // Publishing the worst gives a single "tyre pressure" trigger that fires on any
        // wheel going abnormal; the UI condition offers normal/under/over.
        String pressure = worstTyrePressure(data.tyrePressureState);
        if (pressure != null) Automations.update(TYRE_PRESSURE_WARN, pressure);
        // Tyre air leak: worst across wheels (0=normal,1=slow,2=fast).
        String leak = worstTyreLeak(data.tyreAirLeakState);
        if (leak != null) Automations.update(TYRE_LEAK_WARN, leak);
    }

    /** Worst tyre-pressure state across wheels → "over"/"under"/"normal", or null if
     *  no wheel has a valid reading (leave the event unseeded). Over ranks above under
     *  so an over+under mix reports the more urgent over; both rank above normal. */
    private static String worstTyrePressure(int[] states) {
        if (states == null) return null;
        boolean any = false, under = false, over = false;
        for (int s : states) {
            if (s < 0) continue; // unavailable slot
            any = true;
            if (s == 2) over = true;
            else if (s == 1) under = true;
        }
        if (!any) return null;
        return over ? "over" : under ? "under" : "normal";
    }

    /** Worst tyre air-leak state across wheels → "fast"/"slow"/"normal", or null. */
    private static String worstTyreLeak(int[] states) {
        if (states == null) return null;
        boolean any = false, slow = false, fast = false;
        for (int s : states) {
            if (s < 0) continue;
            any = true;
            if (s == 2) fast = true;
            else if (s == 1) slow = true;
        }
        if (!any) return null;
        return fast ? "fast" : slow ? "slow" : "normal";
    }

    /**
     * Publish the left/right turn-indicator on/off state.
     *
     * <p>Sourced from {@link com.overdrive.app.byd.BydDataCollector#readTurnNow()},
     * which reads the reliable COMBINED {@code getTurnLightFlashState()} enum (the
     * same getter the blind-spot overlay trusts, packed bit0=left / bit1=right) — NOT
     * the per-side {@code getTurnLightState(1/2)} that backs
     * {@code BydVehicleData.leftTurnState}, which returns 0 even while blinking on
     * this firmware. {@code readTurnNow()} returns -1 when the light device is
     * unavailable; we skip that so the event stays unseeded until a real reading.
     *
     * <p>The indicator lamp blinks (~1.5 Hz), so the raw reading toggles on/off
     * within a single "signalling" gesture. Publishing that raw flicker would fire a
     * "turn signal on" automation repeatedly. We therefore treat ANY recent on-phase
     * as "on" and only report "off" once the lamp has been continuously dark for
     * {@link #TURN_OFF_DEBOUNCE_MS} — the standard blind-spot off-debounce, applied
     * here per side so the event is a stable on/off edge, not a strobe.
     *
     * <p>Best-effort and non-blocking: {@code readTurnNow()} is a single live SDK
     * getter on the light device (no scheduler, no I/O), and the whole block is
     * wrapped so a HAL hiccup never disrupts the telemetry poll.
     */
    private static void updateTurnSignals() {
        int packed;
        try {
            packed = com.overdrive.app.byd.BydDataCollector.getInstance().readTurnNow();
        } catch (Throwable t) {
            return; // light device unreachable this tick — leave the events untouched
        }
        if (packed < 0) return; // -1 = light device unavailable → unseeded
        long now = System.currentTimeMillis();
        publishTurn(TURN_LEFT, (packed & 0x1) != 0, lastLeftOnMs, now);
        publishTurn(TURN_RIGHT, (packed & 0x2) != 0, lastRightOnMs, now);
    }

    /**
     * Debounce + publish one side's indicator. {@code litNow} is whether the lamp is
     * currently lit for this side; {@code lastOn} is the per-side clock of the last
     * observed on-phase. Publishes "on" immediately on any lit reading and holds "on"
     * through the blink off-phase until the lamp has been dark for
     * {@link #TURN_OFF_DEBOUNCE_MS}.
     */
    private static void publishTurn(EventData key, boolean litNow, java.util.concurrent.atomic.AtomicLong lastOn, long now) {
        if (litNow) {
            lastOn.set(now);
            Automations.update(key, "on");
            return;
        }
        long since = lastOn.get();
        // Still within the blink off-phase window → hold "on" (no update, so no
        // spurious off→on strobe). Only publish "off" once genuinely dark long enough.
        if (since != 0 && (now - since) < TURN_OFF_DEBOUNCE_MS) return;
        Automations.update(key, "off");
    }

    /**
     * Publish the two temperature events, which are now cleanly separated by WHAT they measure
     * rather than by the car's power state:
     *
     * <ul>
     *   <li>{@link #TEMPERATURE} ("Temperature") — the CABIN sensor, parked as well as driving.
     *       Ambient is used only as a last resort, when the cabin sensor has stopped answering
     *       (see {@code isCabinTempFresh}).</li>
     *   <li>{@link #OUTSIDE_TEMPERATURE} ("Outside Temperature") — always ambient: the exterior
     *       cluster sensor, then the Open-Meteo weather API by last-known GPS. Never the cabin.</li>
     * </ul>
     *
     * <p>Previously TEMPERATURE switched to ambient whenever the car was powered off, which made a
     * parked cabin rule (the pet/child heat alert people actually want this for) compare against
     * outside air. That gate existed to avoid a STALE cabin value, not because the sensor was
     * unreadable — {@code collectAc} polls the AC device with ACC off too. Freshness is now
     * decided directly via {@code insideTempReadAt}, so the power state no longer matters.
     *
     * <p>The weather value is served from cache and refreshed on a background thread
     * (WeatherTemperature), so this never blocks the telemetry poll. When no source yields a value
     * we publish nothing — the event keeps its last value (no spurious "dropped to 0" transition).
     * All sources use Math.round for a consistent integer so a source switch never adds a phantom
     * 1° step.
     */
    /** A dial setpoint in display units → whole Celsius (the disjoint bands identify the scale). */
    private static int setpointCelsius(int setpoint) {
        if (setpoint >= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_F
                && setpoint <= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_F) {
            return (int) Math.round((setpoint - 32) * 5.0 / 9.0);
        }
        return setpoint;
    }

    private static void updateTemperature(BydVehicleData data) {
        // Compute the ambient (outside) temperature once: outside cluster sensor first,
        // then weather by last-known GPS. Reused for both the ambient-only
        // OUTSIDE_TEMPERATURE event and as the fallback tail of the smart TEMPERATURE event.
        double ambient = ambientTemperature(data);
        if (!Double.isNaN(ambient)) {
            Automations.update(OUTSIDE_TEMPERATURE, (int) Math.round(ambient));
        }

        // AC dial setpoint — published only when the dial actually answered (UNAVAILABLE on a
        // miss), so a trim that doesn't expose getTemprature leaves the event unseeded rather
        // than fabricating a value a comparison would silently match against.
        if (data.acSetpointDriver != BydVehicleData.UNAVAILABLE) {
            Automations.update(AC_SETPOINT, setpointCelsius(data.acSetpointDriver));
        }

        // Rain-likely probability rides the SAME Open-Meteo fetch ambientTemperature()
        // just kicked (getCachedPrecipProbability reads that shared cache). Publish only
        // a real reading (>=0); -1 means not-yet-fetched/stale → leave unseeded.
        int rain = com.overdrive.app.weather.WeatherTemperature.getCachedPrecipProbability();
        if (rain >= 0) {
            Automations.update(RAIN_PROBABILITY, rain);
        }

        // TEMPERATURE is the CABIN temperature — parked as well as driving. It used to switch to
        // ambient whenever the car was powered off, which made the one case users actually want it
        // for impossible: a "cabin above 40 → alert" rule for a pet or child evaluated against
        // OUTSIDE air precisely when the car was parked in the sun.
        //
        // The reason it was power-gated was staleness, not power: insideTempC is carried forward by
        // toBuilder() and never reset to NaN, so a drove-then-parked value would pin this event to
        // a frozen cabin reading forever. That is now decided properly — collectAc polls the AC
        // device on the always-alive loop (ACC on AND off, explicitly for parked temperature
        // alerts), and insideTempReadAt records when the sensor last actually answered. So we use
        // the cabin value whenever it is FRESH, whatever the power state, and fall back to ambient
        // only when the sensor has genuinely stopped answering.
        if (isCabinTempFresh(data)) {
            cabinTempSourceActive = true;
            Automations.update(TEMPERATURE, (int) Math.round(data.insideTempC));
            return;
        }
        // Cabin read has aged out. Do NOT switch straight to ambient: this method runs on every
        // snapshot build, and the ~40 LISTENER-driven rebuilds (charging power, SOC, speed, …) call
        // build() → bydEvent() while carrying the previous cabin stamp forward unrefreshed. So a
        // rebuild landing after the freshness window but before the next poll would publish ambient,
        // and the next poll would publish cabin again — flapping the event between two very
        // different numbers (a sun-baked cabin at 55 vs 35 outside). Every flip is a real edge to
        // Automations.update, so a "cabin above 40 → notify" rule would re-fire on every cycle: a
        // notification loop, in exactly the pet/child scenario this event exists for.
        //
        // Only hand the event over to ambient once the cabin sensor is confirmed gone, using a
        // GIVE-UP threshold well beyond one poll cycle rather than a poll/listener flag (there is
        // one bydEvent entry point, shared by both, so no such flag exists to test). Between the
        // freshness window and the give-up threshold the event simply holds its last cabin value —
        // no publish, so no edge, so no flapping. Past the threshold the sensor has missed many
        // consecutive polls and ambient is genuinely the better answer.
        if (cabinTempSourceActive && cabinTempAge(data) < CABIN_TEMP_GIVE_UP_MS) {
            return;
        }
        cabinTempSourceActive = false;
        if (!Double.isNaN(ambient)) {
            Automations.update(TEMPERATURE, (int) Math.round(ambient));
        }
    }

    /**
     * Whether the TEMPERATURE event is currently sourced from the cabin sensor. Used to keep a
     * listener-driven rebuild from flipping the source mid-cycle (see updateTemperature). Written
     * only from the telemetry/listener publish path, which is effectively single-threaded per
     * snapshot build; volatile so a read from another publisher thread sees the latest value.
     */
    private static volatile boolean cabinTempSourceActive = false;

    /**
     * Whether {@code insideTempC} came from a recent AC-device read rather than being carried
     * forward from an older snapshot.
     *
     * <p>The window MUST be sized against the PARKED poll cadence, not the driving one: the
     * collector polls every 5s with ACC on but only every 90s with ACC off
     * ({@code BydDataCollector.POLL_INTERVAL_PARKED_MS}). A window shorter than that would make a
     * parked cabin reading look stale on essentially every evaluation, so the event would fall back
     * to ambient permanently — silently reintroducing the very bug this replaced. 4 missed parked
     * polls (6 min) tolerates a busy HAL or a couple of transient device errors while still ageing
     * out a sensor that has genuinely stopped answering.
     */
    private static boolean isCabinTempFresh(BydVehicleData data) {
        if (Double.isNaN(data.insideTempC)) return false;
        if (data.insideTempReadAt <= 0L) return false;   // never read
        return cabinTempAge(data) <= CABIN_TEMP_MAX_AGE_MS;
    }

    /** Age of the last cabin read in ms, or Long.MAX_VALUE if there has never been one. A clock jump
     *  backwards yields a negative age, which every caller treats as fresh — better than spuriously
     *  falling back to ambient. */
    private static long cabinTempAge(BydVehicleData data) {
        if (data.insideTempReadAt <= 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - data.insideTempReadAt;
    }

    /** How old a cabin read may be and still drive the TEMPERATURE event. Derived from the PARKED
     *  poll interval (90s) — see isCabinTempFresh for why the driving interval is the wrong basis. */
    private static final long CABIN_TEMP_MAX_AGE_MS = 6 * 60_000L;   // 6 min ≈ 4 parked polls

    /** Age at which the cabin sensor is declared gone and TEMPERATURE reverts to ambient. Beyond
     *  CABIN_TEMP_MAX_AGE_MS so the gap between them is a quiet hold rather than a flap. */
    private static final long CABIN_TEMP_GIVE_UP_MS = 30 * 60_000L;   // 30 min ≈ 20 parked polls

    /**
     * Best-effort ambient (outside) temperature: the outside cluster sensor when
     * available, otherwise the cached Open-Meteo value by last-known GPS. The weather
     * value is served from cache and refreshed on a background thread
     * (WeatherTemperature), so this never blocks the telemetry poll. Returns NaN when
     * no source yields a value — callers publish nothing so the event keeps its last
     * value (no spurious "dropped to 0" transition).
     */
    private static double ambientTemperature(BydVehicleData data) {
        if (!Double.isNaN(data.outsideTempC)) {
            return data.outsideTempC;
        }
        try {
            com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (gps != null && gps.hasLocation()) {
                double lat = gps.getLatitude(), lon = gps.getLongitude();
                // Non-blocking: refresh in the background, return whatever's cached now.
                com.overdrive.app.weather.WeatherTemperature.refreshAsync(lat, lon);
                return com.overdrive.app.weather.WeatherTemperature.getCached();
            }
        } catch (Throwable ignored) {
            // Weather is a best-effort fallback; never let it disrupt the telemetry loop.
        }
        return Double.NaN;
    }

    /**
     * Seed the percent and open/closed state events for one window slot, skipping unavailable slots.
     *
     * @param percentKey The event key for the raw open percentage
     * @param stateKey   The event key for the derived open/closed state
     * @param percent    The slot's open percentage, or a negative sentinel if unavailable
     */
    private static void updateWindow(EventData percentKey, EventData stateKey, int percent) {
        if (percent < 0) return; // unavailable/unreadable slot — leave the state unseeded
        Automations.update(percentKey, percent);
        Automations.update(stateKey, percent == 0 ? "closed" : "open");
    }

    private static String seatClimateToString(int level) {
        switch (level) {
            case 0:
                return "off";
            case 1:
                return "low";
            case 2:
                return "high";
            default:
                return "unknown";
        }
    }

    private static String cpdToString(int value) {
        switch (value) {
            case 1:
                return "on";
            case 2:
                return "off";
            case 3:
                return "delay";
            default:
                return "unknown";
        }
    }
}
