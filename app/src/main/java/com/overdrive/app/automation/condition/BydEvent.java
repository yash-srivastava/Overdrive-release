package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.AutomationQueue;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.monitor.GearMonitor;

import java.util.Map;

public class BydEvent {
    private static final com.overdrive.app.logging.DaemonLogger logger =
            com.overdrive.app.logging.DaemonLogger.getInstance("Automations");
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
    public static final EventData TARGET_SOC = new EventData("targetSoc");
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
    public static final EventData ENGINE_SOUND_STATE = new EventData("engineSoundState");
    public static final EventData CPD = new EventData("cpd");
    // Drive mode (normal/eco/sport/snow) normalized from the live Energy/Setting axes, and the
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
    // "fob" for 2 minutes after a key-fob unlock press, and for the rest of a drive started in that
    // time; otherwise "none". DiLink 3 only (BydDataCollector key fob channel).
    public static final EventData UNLOCK_SOURCE = new EventData("unlockSource");
    // Driver-seat easy-entry motion after a fob unlock: idle, moving, settled (held 1 minute).
    public static final EventData EASY_ENTRY = new EventData("easyEntry");
    // Energy-recuperation / regen-braking strength (standard/high/max), read locally via
    // BYDAutoSettingDevice.getEnergyFeedback (app-level 0/1/2) on the telemetry snapshot.
    // A trigger fires "when regen → max"; a condition gates "only if regen is standard".
    public static final EventData ENERGY_REGEN = new EventData("energyRegen");
    public static final EventData SEAT_HEAT_DRIVER = new EventData("seatClimate", Map.of("type", "heat", "area", "driver"));
    public static final EventData SEAT_HEAT_PASSENGER = new EventData("seatClimate", Map.of("type", "heat", "area", "passenger"));
    public static final EventData SEAT_COOL_DRIVER = new EventData("seatClimate", Map.of("type", "cool", "area", "driver"));
    public static final EventData SEAT_COOL_PASSENGER = new EventData("seatClimate", Map.of("type", "cool", "area", "passenger"));
    public static final EventData STEERING_HEAT = new EventData("steeringHeat");
    public static final EventData AC = new EventData("ac");
    // Measured CABIN temperature, parked as well as driving. Never substitute the exterior
    // sensor or AC dial setpoint: those are separate automation signals below.
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
    // Dynamic-driving inputs, read directly by DynamicsEvent at 250ms only while a rule
    // references them. The regular snapshot still ingests the same getters for telemetry, but
    // does not own automation publication because its five-second cadence is too slow for a
    // pedal, steering, or speed threshold.
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
    // OEM radar warning families, kept distinct so rear cross traffic or door-open
    // warnings cannot fire a saved blind-spot automation.
    public static final EventData BLIND_SPOT_LEFT = new EventData("blindSpot", Map.of("side", "left"));
    public static final EventData BLIND_SPOT_RIGHT = new EventData("blindSpot", Map.of("side", "right"));
    // either side: also what a car that doesn't report the side (DiLink 3.0's BSD state) can say
    public static final EventData BLIND_SPOT_ANY = new EventData("blindSpot", Map.of("side", "any"));
    public static final EventData REAR_CROSS_TRAFFIC_LEFT =
            new EventData("rearCrossTraffic", Map.of("side", "left"));
    public static final EventData REAR_CROSS_TRAFFIC_RIGHT =
            new EventData("rearCrossTraffic", Map.of("side", "right"));
    public static final EventData DOOR_OPEN_WARNING_LEFT =
            new EventData("doorOpenWarning", Map.of("side", "left"));
    public static final EventData DOOR_OPEN_WARNING_RIGHT =
            new EventData("doorOpenWarning", Map.of("side", "right"));
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
    // SafeKeep (own-storage backup) state, published by safekeep/SafeKeepSignals only while
    // SafeKeep is on: engine state, queue size, last error class, and each saved clip.
    public static final EventData SAFEKEEP_STATE = new EventData("safeKeepState");
    public static final EventData SAFEKEEP_BACKLOG = new EventData("safeKeepBacklog");
    public static final EventData SAFEKEEP_LAST_ERROR = new EventData("safeKeepLastError");
    public static final EventData SAFEKEEP_CLIP_SAVED = new EventData("safeKeepClipSaved");
    // surveillanceThreat: the worst severity classified in the just-recorded event —
    // notice / alert / critical. Fires once per event as the .mp4 finalizes.
    public static final EventData SURVEILLANCE_THREAT = new EventData("surveillanceThreat");
    // surveillanceObject: the headline object class seen in the event —
    // person / vehicle / bike / animal (or "none" when motion recorded with no
    // classified actor). Ranked person > bike > vehicle > animal.
    public static final EventData SURVEILLANCE_OBJECT = new EventData("surveillanceObject");
    // ── Safety / ADAS events ─────────────────────────────────────────────
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
    // The 12V rail as a real VOLTAGE, alongside the low/normal enum above. Two events on
    // purpose: the enum's source (BYDAutoBodyworkDevice.getBatteryVoltageLevel) returns
    // INVALID on trims that never populate it — so the enum published nothing at all — while
    // BYDAutoOtaDevice.getBatteryPowerVoltage() is the reading the 12V performance graph has
    // always charted (BatteryPowerMonitor → BatteryPowerData.voltageVolts). Exposing volts
    // also enables the rule the enum cannot express: "12V below 12.2 → warn me".
    public static final EventData AUX_BATTERY_12V_VOLTS = new EventData("aux12vVoltage");
    /** Resolved charging power into the pack, whole kW; 0 when not charging. */
    public static final EventData CHARGING_POWER = new EventData("chargingPower");
    /** Minutes the vehicle reports until charging completes. Published only while charging. */
    public static final EventData CHARGE_TIME_TO_FULL = new EventData("chargeTimeToFull");
    /** Lowest valid tyre pressure across the four corners, kPa. */
    public static final EventData TYRE_PRESSURE_LOWEST = new EventData("tyrePressureLowest");
    /** AC blower level as reported by the climate device. */
    public static final EventData AC_FAN_LEVEL = new EventData("acFanLevel");
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
    // Automatic-wiper MODE and actual blade ACTIVITY are separate signals. AUTO_WIPER
    // does not assert that rain is currently detected or that the blades are moving.
    public static final EventData AUTO_WIPER = new EventData("autoWiper");
    public static final EventData WIPER_ACTIVE = new EventData("wiperActive");
    // Automatic-headlight MODE switch. It is not a darkness sensor; actual lamp output
    // is represented by the low/high-beam events.
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
    // Pushed once by NetworkEvent after saved configuration loads for this daemon process.
    public static final EventData BOOT = new EventData("boot");

    // Turn-indicator blink off-debounce (ms). The lamp toggles ~1.5 Hz, so a single
    // "signalling" gesture would otherwise strobe the on/off event. Hold "on" until
    // the lamp has been continuously dark for this long.
    //
    // SIZING: this must exceed the SAMPLING interval AND the blink off-phase (~330ms at
    // 1.5Hz). Turn signals are now sampled by the dedicated TurnSignalEvent poll at
    // 250ms (see pollTurnSignals) — far tighter than the old ~5s stationary snapshot
    // cadence that forced a 6s debounce. At 1s the window spans ~4 fast polls and
    // comfortably bridges a blink off-phase, so a still-signalling lamp never reports a
    // spurious "off", while the genuine OFF edge now lags only ~1s after cancel
    // (was ~6s) — the responsiveness the "long delay" report was about, on both edges.
    private static final long TURN_OFF_DEBOUNCE_MS = 1000L;
    // Per-side MONOTONIC clock (see monotonicMs) of the last observed on-phase, for the blink
    // debounce above. Static; the single-threaded TurnSignalEvent poll is the sole writer —
    // 0 means "never seen lit", and is also how a released hold is re-armed.
    private static final java.util.concurrent.atomic.AtomicLong lastLeftOnMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong lastRightOnMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong lastHazardOnMs = new java.util.concurrent.atomic.AtomicLong(0);

    // Keys owned by a fast poller, which publishes them from a LIVE HAL read. The snapshot
    // path (bydEvent) must NOT publish these: toBuilder() carries every field forward, and the
    // ~59 partial-rebuild sites (snapshot.set(current.toBuilder().socPercent(x).build())) each
    // fire bydEvent with a STALE value for every field they didn't refresh. A stale republish
    // racing the live poller makes Automations.update see a transition each way, so an
    // automation with an ELSE branch alternates THEN/ELSE until the next full collectAll()
    // resyncs the snapshot. See docs/AUTOMATION-PUBLISH-INVARIANTS.md.
    //
    // Ownership is STATIC, never conditional on the poller's isEventReferenced gate: when the
    // gate is shut nothing reads the key either, and falling back to the snapshot would
    // reintroduce the race exactly when a rule is added mid-session.
    private static final java.util.Set<EventData> FAST_POLL_OWNED = java.util.Set.of(
            SEATBELT_DRIVER, SEATBELT_PASSENGER,   // SeatbeltEvent   (500ms) + HAL belt callback
            OCCUPANT_PASSENGER, OCCUPANT_DRIVER,   // SeatbeltEvent   (500ms)
            TURN_LEFT, TURN_RIGHT, LIGHTS_HAZARD,  // TurnSignalEvent (250ms, blink-debounced)
            GEAR,                                  // GearEvent       (250ms)
            DRIVE_MODE,                            // DriveModeEvent  (1s)
            SEAT_COOL_DRIVER, SEAT_COOL_PASSENGER, // ClimateEvent    (500ms)
            SEAT_HEAT_DRIVER, SEAT_HEAT_PASSENGER, // ClimateEvent    (500ms)
            STEERING_HEAT,                         // ClimateEvent    (500ms)
            LIGHTS_LOW_BEAM, LIGHTS_HIGH_BEAM,     // ClimateEvent    (500ms)
            LIGHTS_DRL, AUTO_LIGHTS,               // ClimateEvent    (500ms)
            AUTO_WIPER, WIPER_ACTIVE,              // ClimateEvent    (500ms)
            AC, AC_SETPOINT,                       // ClimateEvent    (500ms)
            SPEED_KMPH, SPEED_MPH,                 // DynamicsEvent   (250ms)
            ACCELERATOR, BRAKE, STEERING_ANGLE,    // DynamicsEvent   (250ms)
            CHARGING_POWER);                       // ChargingPowerEvent (10s)

    /** Whether a fast poller owns this key, so the snapshot path must not republish it. */
    private static boolean ownedByFastPoll(EventData key) {
        return FAST_POLL_OWNED.contains(key);
    }

    /**
     * Publish from the SNAPSHOT path. Drops keys owned by a fast poller (see
     * {@link #FAST_POLL_OWNED}) so a carried-forward field can't fight the live reading.
     * Every {@code Automations.update} inside {@link #bydEvent} must route through here.
     */
    private static void publishFromSnapshot(EventData key, String value) {
        if (ownedByFastPoll(key)) return;
        // A null word means "not a real reading" (see seatClimateToString): skip it. update()
        // guards a null Value, not a null String, so this would otherwise store StringValue(null).
        if (value == null) return;
        // forceStore only while the editor is seeding (nothing enabled): update() otherwise
        // returns before storing, which left the editor's live-value hints blank. Storing is not
        // firing — a first value is a silent seed either way (Invariant 0).
        Automations.update(key, value);
    }

    /** Integer-valued {@link #publishFromSnapshot}. */
    private static void publishFromSnapshot(EventData key, Integer value) {
        if (ownedByFastPoll(key)) return;
        // new IntValue(...) explicitly, matching what update(key, Integer) did — including for a
        // null Integer, so this stays a pure forceStore change with no behaviour difference.
        Automations.update(key, new com.overdrive.app.automation.value.IntValue(value));
    }

    // The ON/OFF side of the last ACC edge published by publishPowerEdge, or null before the
    // first one. Owned here (not read off BydDataCollector.isAccOn) so the suppression arms
    // ATOMICALLY with the publish it has to protect: the edge handler can publish, or decide not
    // to, and the snapshot path's view can never disagree with what was actually published.
    private static volatile Boolean lastPublishedPowerOn = null;
    // Whether the currently-latched edge occurrence actually reached triggers. Paired with
    // lastPublishedPowerOn and cleared wherever that is, so it can never outlive its occurrence.
    // It is what makes a RETAINED PUBLICATION REPLAY (re-run on any config change mid-publication,
    // e.g. saving a rule) idempotent, without suppressing the replay of an edge that was published
    // while automations were disabled and so never fired.
    private static volatile boolean powerEdgeDelivered = false;
    // MONOTONIC clock (System.nanoTime) of the last edge publish, for the suppression grace
    // window below. nanoTime deliberately, not currentTimeMillis: an NTP/RTC backward jump
    // would make a wall-clock age negative and fail the window open, letting a stale snapshot
    // republish fire a false power flip — a wrong action, which is worse than a late one.
    // Written only inside the POWER latest-state mutation, same as the latch it accompanies.
    private static volatile long lastPowerEdgeAtNanos = 0;

    // How long a contradicting SNAPSHOT publish is dropped after an ACC edge. The stale-field
    // window the suppression exists for is bounded: after an edge, partial toBuilder().build()
    // republishes carry the PRE-edge powerLevel only until the next full collect refreshes it
    // (≤5s driving, ≤90s parked). Past this window a snapshot that still contradicts the latch
    // is not a stale republish — it is ground truth the edge pipeline failed to deliver (edge
    // publication admission can be rejected and its retry can starve; 2026-08 field report:
    // "power on never fires"). Then the latch must YIELD, or the power signal is wrong for the
    // rest of the process lifetime and every power rule/condition is stuck on the old side.
    // 150s > the 90s parked collect cadence with margin.
    private static final long POWER_EDGE_SUPPRESS_GRACE_MS = 150_000L;

    // How long after an ACC edge a level on the SAME side but with a different word (i.e. ACC
    // under an off edge) is treated as the key still rotating, rather than as a state the driver
    // has selected. A key turn passes through ACC in well under a second, so 5s is generous.
    //
    // This must be MUCH shorter than POWER_EDGE_SUPPRESS_GRACE_MS. That window exists for a
    // CONTRADICTING level (a stale pre-edge value carried forward by a partial rebuild until the
    // next full collect). An AGREEING-side level cannot be that stale value — for an off edge the
    // carried-forward value is "on", which the contradiction branch already handles — so "acc"
    // here is a FRESH reading. Holding it for the full 150s would swallow a genuine accessory
    // selection: ACC is ACC-off across the stack, so the edge that fires for it says "off" and
    // only this snapshot can report the finer "acc" (audit 2026-08).
    private static final long POWER_EDGE_SETTLE_MS = 5_000L;

    /** The {@code power} vocabulary word for a raw bodywork level (off/acc/on). Single source of
     *  truth for both publishers, so the edge and the snapshot always speak the same words. */
    private static String powerLevelWord(int powerLevel) {
        return BodyworkConstants.powerLevelToString(powerLevel).toLowerCase();
    }

    /** The word an ACC edge publishes. The edge is boolean, so it only ever says off or on —
     *  never acc, which only the snapshot's wider vocabulary can report. */
    private static String powerEdgeWord(boolean accOn) {
        return powerLevelWord(accOn
                ? BodyworkConstants.POWER_LEVEL_ON
                : BodyworkConstants.POWER_LEVEL_OFF);
    }

    /**
     * Whether a raw bodywork level is one of the three levels the {@code power} condition
     * vocabulary can express (OFF/ACC/ON).
     *
     * <p>The HAL also returns OK(3), FAKE_OK(4) and INVALID(255) — the "HAL is bluffing" values
     * that {@code AccMonitor} and {@code RecordingModeManager} already refuse to trust.
     * {@code powerLevelToString} maps all of them to {@code unknown(N)}, which no dropdown option
     * can match, so publishing one makes every {@code power} condition read false. Worse, 3/4/255
     * are all {@code >= POWER_LEVEL_ON}, so they pass the ON-side staleness check and would be
     * stored verbatim under an ON latch — silently breaking "when power turns on" for the whole
     * accessory session. Skip them and leave the last good value in place.
     */
    private static boolean isRealPowerLevel(int powerLevel) {
        return powerLevel == BodyworkConstants.POWER_LEVEL_OFF
                || powerLevel == BodyworkConstants.POWER_LEVEL_ACC
                || powerLevel == BodyworkConstants.POWER_LEVEL_ON;
    }

    /**
     * Publish {@code power} for an observed ACC edge, and take ownership of the signal so a stale
     * snapshot can't contradict it. Idempotent — {@code Automations.update} is edge-triggered, so
     * a repeated edge of the same polarity is a no-op.
     *
     * @param accOn true when the key is on, false when parked
     */
    public static void publishPowerEdge(boolean accOn) {
        AutomationQueue.runLatestStateMutation(
                AutomationQueue.LatestStateStream.POWER,
                () -> {
                    // IDEMPOTENT PER EDGE OCCURRENCE. A retained POWER publication is REPLAYED
                    // whenever the config changes mid-publication (saving a rule), which re-runs
                    // this whole method. The engine's delivered-value dedup normally absorbs that,
                    // but it cannot once the snapshot has legitimately moved the mark in between:
                    // park at t=0 delivers "off", an accessory dwell delivers "acc" at t=8s, and a
                    // replay at t=20s then looks like a fresh acc→off transition and runs
                    // "when power turns off" a SECOND time with the key untouched (audit 2026-08).
                    //
                    // Suppress only when THIS latched occurrence has already reached triggers.
                    // Two signals are needed and neither suffices alone:
                    //  - the LATCH still naming this side means no yield/retraction has happened,
                    //    so a re-run is the same occurrence rather than a new event. (The engine's
                    //    delivery mark alone cannot tell: the snapshot may have legitimately
                    //    delivered "acc" in between, which hides the duplicate.)
                    //  - powerEdgeDelivered records whether that occurrence actually FIRED. An edge
                    //    published while automations were disabled did not, so its replay after the
                    //    user enables them must still fire — the documented recovery in
                    //    Automations.update.
                    // Both are cleared by the grace-window yield and by resetPowerEdge, so an
                    // occurrence that has been superseded never suppresses a genuine later edge.
                    Boolean latched = lastPublishedPowerOn;
                    if (latched != null && latched.booleanValue() == accOn && powerEdgeDelivered) {
                        return;
                    }
                    lastPublishedPowerOn = accOn;
                    lastPowerEdgeAtNanos = System.nanoTime();
                    // OBSERVED-EDGE publish, not a sampled one: the ACC dispatch watched this
                    // transition happen (and dedups repeats + admits one publication per
                    // transition generation), so it must fire even when it is the first value
                    // after a daemon start or a racing snapshot already seeded the same side.
                    // A plain update() made boot-time power rules a race between this handler
                    // and the telemetry seed (see Automations.updateObservedEdge).
                    String word = powerEdgeWord(accOn);
                    Automations.updateObservedEdge(POWER, word);
                    // Did this occurrence actually reach triggers? Ask the engine rather than
                    // assuming: it is false while automations are disabled, and false when a
                    // racing sampler had already delivered the same value.
                    powerEdgeDelivered = Automations.isLastDelivered(POWER, word);
                });
    }

    /**
     * Forget the last published ACC edge, handing {@code power} back to the snapshot path. Used
     * when an edge is retracted rather than applied (the cold-boot drain discards a queued edge
     * that the hardware probe contradicts) — otherwise the retracted value would stay latched and
     * keep suppressing the true state.
     */
    public static void resetPowerEdge(boolean expectedAccOn) {
        AutomationQueue.runLatestStateMutation(
                AutomationQueue.LatestStateStream.POWER,
                () -> {
                    Boolean edge = lastPublishedPowerOn;
                    if (edge != null && edge.booleanValue() == expectedAccOn) {
                        lastPublishedPowerOn = null;
                        // The occurrence is retracted, so its delivery record must go with it —
                        // the same side may legitimately be published again once the true state is
                        // known, and a stale flag would suppress it.
                        powerEdgeDelivered = false;
                    }
                });
    }

    /**
     * Publish {@code power} from the snapshot, unless the carried-forward level contradicts the
     * ACC edge already published.
     *
     * <p>{@code powerLevel} is refreshed only by collectBodywork, so between an ACC edge and the
     * next full collect (up to 90s while parked) every partial {@code toBuilder().build()} in the
     * tree republishes the PREVIOUS level. Since the edge is published the instant it fires, that
     * stale republish would fight it and strobe the rule (Invariant 1/2c).
     *
     * <p>Dropping the publish — rather than rewriting the snapshot field — keeps this free of side
     * effects: correcting the field would need a {@code build()}, which re-fires this whole method
     * and would flip the powerLevel-gated AC publish below.
     *
     * <p>The ON/OFF SIDE decides STALENESS: a level on the other side of the last edge is a
     * carried-forward pre-edge value and is dropped. But agreeing on the side does not make the
     * snapshot's publish a NEW EVENT — the two publishers have different vocabulary widths. The
     * edge is boolean (off/on only); the snapshot reports off/acc/on. Accessory mode is ACC-off
     * across the stack, so {@code acc} agrees with an OFF edge and used to pass this gate as an
     * ordinary off→acc transition, which FIRED. One key turn traverses several stored states
     * (off→acc→off as the level settles), so every {@code power} rule ran two or three times per
     * cycle; field report: "car power on is triggered more than once".
     *
     * <p>So for {@link #POWER_EDGE_SETTLE_MS} after an edge, a same-side level is treated as the key
     * still rotating toward the position the edge already reported: the edge's own word is RESTATED
     * (stored, fires nothing, delivery mark untouched) and any other word — {@code acc} on the way
     * through — is DROPPED. Outside that window the snapshot publishes normally, because it is then
     * the only witness to a state the driver actually selected.
     *
     * <p>Three bounds, each one an audit finding from an earlier attempt (2026-08):
     * <ul>
     *   <li>Drop {@code acc}, do NOT store-without-firing. Storing it makes it the current value,
     *       so every later republish of level 1 is a non-transition and can never fire — which
     *       swallowed a genuine key-to-ACC outright. Dropping leaves the edge's word stored, so the
     *       first republish past the window is still a real transition.</li>
     *   <li>Bound the special-casing by this SHORT window, not the 150s stale-value grace. That
     *       grace exists for a CONTRADICTING level; an agreeing-side level cannot be the stale
     *       carried-forward value, so {@code acc} here is a fresh reading.</li>
     *   <li>Bound the RESTATEMENT too, not just the drop. Unbounded, a driver who sat in accessory
     *       mode (delivering {@code acc}) and then turned the key fully off had that {@code off}
     *       restated instead of delivered — "when power turns off" never ran for the rest of the
     *       parked session, and the stale mark then killed {@code acc} as well.</li>
     *   <li>Do NOT special-case ACC under an ON latch. It looks asymmetric (the other side, so the
     *       150s grace rather than the 5s settle), but rotating the key ON→ACC fires a real ACC-OFF
     *       edge — accOn is {@code powerLevel >= POWER_LEVEL_ON} — which re-latches to the off side,
     *       after which the level agrees and takes the ordinary settle path. An ACC level under a
     *       LIVE ON latch is therefore a stale mid-sweep reading, which is precisely what the grace
     *       suppresses. A "confirm window plus latch release" tried here on the opposite premise
     *       re-fired "power turns on" on every rule save while the key sat in accessory, because the
     *       release cleared the state the replay guard reads.</li>
     * </ul>
     *
     * <p>Only a level in the real OFF/ACC/ON vocabulary is published — see
     * {@link #isRealPowerLevel}. That covers {@link BydVehicleData#UNAVAILABLE} (bodywork device
     * absent, or its getter returned a non-Number) AND the HAL's OK(3)/FAKE_OK(4)/INVALID(255)
     * bluffs: all stringify to {@code unknown(N)}, which no condition can match, and 3/4/255 also
     * pass the ON-side staleness check, so they would be stored under an ON latch and break every
     * "power turns on" rule. Leave the last good value in place instead.
     */
    private static void publishPower(int powerLevel) {
        if (!isRealPowerLevel(powerLevel)) return;
        AutomationQueue.runLatestStateMutation(
                AutomationQueue.LatestStateStream.POWER,
                () -> {
                    Boolean edge = lastPublishedPowerOn;
                    // NOTE on ACC and the ON latch: a level of ACC can never linger under a LIVE
                    // ON latch, because ACC-on is derived as `powerLevel >= POWER_LEVEL_ON`
                    // (AccMonitor), so rotating the key ON→ACC flips accOn true→false and fires a
                    // real ACC-OFF edge. That edge re-latches this signal to the off side, after
                    // which the level agrees with the latch and takes the ordinary settle path —
                    // the same 5s bound as any other same-side level, with no special case.
                    //
                    // An earlier fix added a 12s "confirm" window plus a latch release here, on the
                    // premise that ACC publishes no edge of its own. That premise was wrong, and the
                    // release then cleared the state the replay guard depends on: a retained
                    // publication replay (any rule save) re-fired "power turns on" while the key sat
                    // in accessory. Reverted 2026-08 — an ACC level under an ON latch is a genuinely
                    // STALE reading (the ≤5s collector caught the key mid-sweep), which is exactly
                    // what the grace window below exists to suppress.
                    if (edge != null
                            && (powerLevel >= BodyworkConstants.POWER_LEVEL_ON)
                            != edge.booleanValue()) {
                        // Stale-republish window: a partial rebuild can carry the pre-edge level
                        // for up to one full collect (≤90s parked). Inside the grace window the
                        // edge wins. PAST it, a snapshot that still contradicts the latch means
                        // the edge pipeline missed a real transition (rejected admission /
                        // starved retry) — then the collector is ground truth and the latch must
                        // yield, or `power` stays wrong for the whole process and every power
                        // rule/condition is stuck (field report: "power on never fires").
                        long edgeAgeMs = (System.nanoTime() - lastPowerEdgeAtNanos) / 1_000_000L;
                        if (edgeAgeMs < POWER_EDGE_SUPPRESS_GRACE_MS) {
                            return;
                        }
                        logger.warn("power: snapshot level " + powerLevel
                                + " contradicts the last published edge (on=" + edge
                                + ", " + (edgeAgeMs / 1000) + "s old) past the grace window — "
                                + "edge publication was missed; yielding to the snapshot");
                        lastPublishedPowerOn = null;
                        // The latched occurrence is superseded, so its delivery record goes too.
                        // Leaving it set stranded the replay guard: after this yield delivered the
                        // opposite value, the next GENUINE edge of the yielded-from side was
                        // suppressed as a duplicate and the latch was never re-armed (audit
                        // 2026-08 — the starved-edge-retry case this yield exists for).
                        powerEdgeDelivered = false;
                        edge = null;   // yielded: this publish is now a normal sampled one
                    }
                    String word = powerLevelWord(powerLevel);
                    // A level on the edge's own side but with a DIFFERENT word — "acc" under an
                    // off edge — is the key still rotating through accessory mode on its way to
                    // the position the edge reported. DROP it for the brief settle window: the
                    // edge already delivered this event, and a key turn passes through ACC in
                    // under a second.
                    //
                    // DROP, not store-without-firing: storing "acc" makes it the current value,
                    // so every later republish of level 1 is a non-transition and can never fire.
                    // That silently swallowed a genuine accessory selection — the edge that fires
                    // for it can only say "off" (accOn is `powerLevel >= POWER_LEVEL_ON`), so this
                    // snapshot is the only publisher that can report the finer "acc" (audit
                    // 2026-08). Dropping leaves the edge's word stored, so the first republish past
                    // the settle window is still a real transition and delivers normally.
                    //
                    // The settle-back leg (returning to the edge's own word) needs nothing here:
                    // the engine's delivered-value dedup in Automations.update suppresses it, and
                    // that also covers retained replays and heartbeats.
                    long edgeAgeMs = (System.nanoTime() - lastPowerEdgeAtNanos) / 1_000_000L;
                    // BOTH special cases below are bounded by the SETTLE window. Outside it the
                    // snapshot is the only witness to what the driver has selected, so it must take
                    // the ordinary path — an unbounded restatement swallowed the key's ACC→OFF turn
                    // for the whole parked session (audit 2026-08).
                    if (edge != null && edgeAgeMs < POWER_EDGE_SETTLE_MS) {
                        if (word.equals(powerEdgeWord(edge))) {
                            // The edge's OWN word, re-published while the key is still settling.
                            // The edge already delivered it, so restate: store the level, fire
                            // nothing, and leave the delivery mark exactly as it was.
                            Automations.updateEdgeRestatement(POWER, word);
                        }
                        // Otherwise (same side, different word — i.e. acc on the way to the edge's
                        // position) drop it entirely. Storing it would make it the current value and
                        // every later republish a non-transition, which swallowed a genuine
                        // key-to-ACC outright.
                        //
                        // Cost of the drop: for these few seconds `power` reads the edge's word, so
                        // a condition / waitUntil / ${signal:power} sees off (or on) rather than
                        // acc. Bounded by the settle window rather than the collect cadence —
                        // bydEvent runs on EVERY Builder.build(), so one of the ~59 partial-rebuild
                        // sites re-publishes the level as soon as the window closes.
                        return;
                    }
                    Automations.update(POWER, word);
                });
    }

    private BydEvent() {}

    /**
     * This class is created to make gathering events easier
     * As the BydVehicleData is not updated often, this does not affect app performance
     * If this changes in the future, Automations.update should be called directly when an event is triggered
     * This would allow it to update a single variable instead of updating all variables when a single value changes
     *
     * @param data The current BydVehicleData with the vehicle state
     */
    /**
     * Seed EVERY signal the editor can show, for the live-value hints, when no automation is
     * enabled (so nothing is publishing yet).
     *
     * <p>Two distinct groups, and the split is why the snapshot alone was not enough:
     * <ul>
     *   <li>Snapshot-published keys come from {@link #bydEvent}.</li>
     *   <li>{@link #FAST_POLL_OWNED} keys are DROPPED by the snapshot path (Invariant 1 — a
     *       fast poller owns them), and their pollers only run while an enabled rule references
     *       the key. So gear/driveMode/seatbelt/turn/climate/dynamics had NO publisher at all in
     *       the editor and read "not reported yet on this car" no matter what the car was doing —
     *       the reported bug. Their live pollers are invoked directly here instead, which is the
     *       legitimate owner reading the HAL, so ownership is not violated.</li>
     * </ul>
     *
     * <p>Publishing is store-only while disabled ({@code Automations.update} sets
     * {@code fire = false}), so this can never run a rule. Each call is individually guarded: one
     * unreadable HAL getter must not stop the rest of the signals from seeding.
     */
    public static void seedForEditor(BydVehicleData data) {
        if (data != null) {
            try { bydEvent(data); } catch (Throwable t) { logger.warn("seed snapshot: " + t.getMessage()); }
        }
        // FAST_POLL_OWNED keys, from their live pollers (the snapshot path drops these).
        try { pollGear(); }           catch (Throwable t) { logger.warn("seed gear: " + t.getMessage()); }
        try { pollDriveMode(); }      catch (Throwable t) { logger.warn("seed driveMode: " + t.getMessage()); }
        try { pollSeatbelts(); }      catch (Throwable t) { logger.warn("seed seatbelts: " + t.getMessage()); }
        try { pollOccupants(); }      catch (Throwable t) { logger.warn("seed occupants: " + t.getMessage()); }
        try { pollDriverOccupant(); } catch (Throwable t) { logger.warn("seed driverOccupant: " + t.getMessage()); }
        try { pollTurnSignals(); }    catch (Throwable t) { logger.warn("seed turnSignals: " + t.getMessage()); }
        try { pollClimate(); }        catch (Throwable t) { logger.warn("seed climate: " + t.getMessage()); }
    }

    public static void bydEvent(BydVehicleData data) {
        // Do nothing when no automations enabled — EXCEPT while the editor is asking for live
        // values. With nothing enabled, Automations.update() short-circuits and stores nothing,
        // so the whole state map was empty and every signal in the editor read "not reported yet
        // on this car" — including on the very first automation a user ever builds, which is
        // exactly when the hint matters most. The store itself is allowed by the matching gate in
        // Automations.update (one central place, since most publish sites call plain update); this
        // gate only stops the poller returning before it ever gets there. Storing is never
        // firing — update() forces fire=false while disabled.
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;

        publishPower(data.powerLevel);
        publishFromSnapshot(GEAR, GearMonitor.gearToString(data.gearMode).toLowerCase());
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
        if (data.socTargetPercent >= 0 && data.socTargetPercent <= 100) {
            Automations.update(TARGET_SOC, data.socTargetPercent);
        }
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.elecRangeKm);
        } else if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) {
            Automations.update(ESTIMATED_RANGE, data.bodyworkRangeKm);
        }
        if (data.isLightKnown(BydVehicleData.LIGHT_KNOWN_LOW_BEAM)) {
            publishFromSnapshot(LIGHTS_LOW_BEAM, data.lowBeam ? "on" : "off");
        }
        if (data.isLightKnown(BydVehicleData.LIGHT_KNOWN_HIGH_BEAM)) {
            publishFromSnapshot(LIGHTS_HIGH_BEAM, data.highBeam ? "on" : "off");
        }
        if (data.isLightKnown(BydVehicleData.LIGHT_KNOWN_TURN_HAZARD)) {
            publishFromSnapshot(LIGHTS_HAZARD, data.hazard ? "on" : "off");
        }
        if (data.isLightKnown(BydVehicleData.LIGHT_KNOWN_DRL)) {
            publishFromSnapshot(LIGHTS_DRL, data.dayTimeLight ? "on" : "off");
        }
        // Ambient main switch — only on a real reading (UNAVAILABLE → publish nothing, so an
        // unreadable switch cannot fire a spurious "off" trigger on every poll).
        if (data.ambientEnabled != BydVehicleData.UNAVAILABLE) {
            Automations.update(AMBIENT_STATE, data.ambientEnabled == 1 ? "on" : "off");
        }
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                || data.speedLimitWarningKnown) {
            Automations.update(SLW, data.speedLimitWarning ? "on" : "off");
        }
        // Read-only engine-sound simulator telemetry. Never publish a fabricated
        // "off" when the MCU capability or state read is unavailable.
        if (Automations.isEventReferenced(ENGINE_SOUND_STATE)) {
            Boolean engineSound = com.overdrive.app.byd.AvasController.readEngineSoundState();
            if (engineSound != null) {
                publishFromSnapshot(ENGINE_SOUND_STATE, engineSound ? "on" : "off");
            }
        }
        // Skip a non-reading: cpdToString returns null outside the three real states, and
        // update(key, String) would wrap it in a StringValue(null) — non-null to the guard in
        // update(), so it would store a null and publish a value no condition can match.
        String cpdWord = cpdToString(data.childPresenceDetection);
        if (cpdWord != null) Automations.update(CPD, cpdWord);
        if (data.seatHeat != null) {
            if (data.seatHeat.length > 0) publishFromSnapshot(SEAT_HEAT_DRIVER, seatClimateToString(data.seatHeat[0]));
            if (data.seatHeat.length > 1) publishFromSnapshot(SEAT_HEAT_PASSENGER, seatClimateToString(data.seatHeat[1]));
        }
        if (data.seatCool != null) {
            if (data.seatCool.length > 0) publishFromSnapshot(SEAT_COOL_DRIVER, seatClimateToString(data.seatCool[0]));
            if (data.seatCool.length > 1) publishFromSnapshot(SEAT_COOL_PASSENGER, seatClimateToString(data.seatCool[1]));
        }
        publishFromSnapshot(STEERING_HEAT,
                steeringHeatStateToString(data.steeringWheelHeat));
        // AC power is owned by ClimateEvent's live getter. Do not gate it on vehicle power:
        // remote/parked preconditioning legitimately reports AC=on while the head unit is below
        // POWER_LEVEL_ON. Also do not turn an invalid/sentinel reading into a confident "off".
        publishFromSnapshot(AC, acStateToString(data.acStartState));
        // Cabin and exterior temperature are deliberately independent. updateTemperature()
        // publishes the measured cabin sensor only when a valid cabin reading exists; it never
        // substitutes the exterior sensor or weather estimate for a missing cabin measurement.
        updateTemperature(data);
        // speedKmh is canonical km/h (already scaled by the speed-specific factor at ingestion),
        // so the mph value is a straight km→mi conversion. Guard NaN (unreadable speed).
        if (!Double.isNaN(data.speedKmh)) {
            publishFromSnapshot(SPEED_KMPH, (int) Math.round(data.speedKmh));
            publishFromSnapshot(SPEED_MPH,
                    (int) Math.round(data.speedKmh * 0.621371));
        }
        // Pedal deepness (0-100). UNAVAILABLE (Integer.MIN_VALUE) until the speed
        // device first reports; skip the sentinel so a condition never sees a bogus
        // huge-negative value and the event keeps its last real reading.
        if (data.accelPercent != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(ACCELERATOR, data.accelPercent);
        }
        if (data.brakePercent != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(BRAKE, data.brakePercent);
        }
        // Steering angle (signed degrees). NaN until the bodywork device reports.
        if (!Double.isNaN(data.steeringAngleDegrees)) {
            publishFromSnapshot(STEERING_ANGLE, (int) Math.round(data.steeringAngleDegrees));
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
            // Rest time can stay latched after the gun comes out, so it is only a reading
            // while the fused verdict says a charge is actually running.
            if (charging) {
                int minutes = chargeRestMinutes(data);
                if (minutes >= 0) publishFromSnapshot(CHARGE_TIME_TO_FULL, minutes);
            }
        } catch (Throwable ignored) { /* detector not ready — skip this tick */ }
        int lowestKpa = lowestTyreKpa(data.tyrePressure);
        if (lowestKpa > 0) publishFromSnapshot(TYRE_PRESSURE_LOWEST, lowestKpa);
        if (data.acFanLevel != BydVehicleData.UNAVAILABLE
                && data.acFanLevel >= 0 && data.acFanLevel <= 7) {
            publishFromSnapshot(AC_FAN_LEVEL, data.acFanLevel);
        }
        // Gun connected: BYD gun states 2..5 are physically plugged (3=DC fast, 5=V2L).
        // UNAVAILABLE until reported → leave unseeded.
        if (data.chargingGunState != BydVehicleData.UNAVAILABLE) {
            boolean connected = data.chargingGunState >= 2 && data.chargingGunState <= 5;
            Automations.update(CHARGE_GUN, connected ? "connected" : "disconnected");
        }
        // ── Battery health + auxiliary batteries (percent) ──
        // Prefer this snapshot's validated vehicle reading, including during collector startup.
        // The canonical resolver supplies the retained estimate fallback when it is unavailable.
        double canonicalSoh = Double.isFinite(data.sohPercent)
                && data.sohPercent > 0 && data.sohPercent <= 100 ? data.sohPercent : -1;
        if (canonicalSoh <= 0) {
            try {
                com.overdrive.app.monitor.SocHistoryDatabase socDb =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
                com.overdrive.app.abrp.SohEstimator estimator =
                    socDb != null ? socDb.getSohEstimator() : null;
                canonicalSoh = com.overdrive.app.abrp.SohEstimator.getDisplaySohOrOem(estimator);
            } catch (Throwable ignored) {}
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
        else publishAux12vFromVoltage();   // enum unavailable → derive it from the real volts
        // The 12V rail as VOLTS, from the same source the performance graph charts.
        publishAux12vVoltage();
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
        if (data.autoWiperState != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(AUTO_WIPER, data.autoWiperState == 1 ? "on" : "off");
        }
        if (data.wiperState != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(WIPER_ACTIVE, data.wiperState != 0 ? "on" : "off");
        }
        if (data.lightAutoStatus != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(AUTO_LIGHTS, data.lightAutoStatus == 1 ? "on" : "off");
        }
        // Seat occupancy per seat: 1=someone, 0=nobody, UNAVAILABLE→skip.
        // passengerDetection is a 1-slot front-passenger array (see readOccupantsNow) —
        // its shape is frozen at one slot because it is serialized positionally to MQTT/JSON.
        int[] occ = data.passengerDetection;
        if (occ != null && occ.length > 0 && !ownedByFastPoll(OCCUPANT_PASSENGER)) {
            publishOccupant(OCCUPANT_PASSENGER, occ[0]);
        }
        // Inferred DRIVER presence from the snapshot's already-sanitized driver belt (slot 0):
        // buckled ⇒ someone is there. Deliberately NO HAL read here — this runs inside every
        // build(), so the reminder-mask tier stays on the fast poller (pollDriverOccupant).
        // That asymmetry is safe BECAUSE the event is positive-only and monotonic: both paths
        // can only ever publish "occupied", so a subset of tiers here can never contradict the
        // poller — it only means presence may be established a tick later on this path.
        int[] belts = data.seatbeltStatus;
        if (belts != null && belts.length > 0 && belts[0] == 1 && !ownedByFastPoll(OCCUPANT_DRIVER)) {
            publishOccupant(OCCUPANT_DRIVER, 1);
        }
        // Drive mode on the config axis (1=normal..4=snow); only publish an in-band value.
        // The collector normalizes the setting-device and energy-device getter encodings.
        String driveMode = driveModeToString(data.operationMode);
        if (driveMode != null) publishFromSnapshot(DRIVE_MODE, driveMode);
        // Powertrain mode applies to fuel-capable hybrids. HEV and PHEV are deliberately one
        // bucket; the important boundary is BEV, where some firmware returns the SDK default HEV
        // value even though no engine exists.
        boolean supportsPowertrain =
                com.overdrive.app.automation.AutomationCategories
                        .supportsHybridOnlyItemsOnCurrentVehicle();
        String powertrain = powertrainModeToString(data.energyMode, supportsPowertrain);
        if (powertrain != null) {
            Automations.update(POWERTRAIN_MODE, powertrain);
        } else if (!supportsPowertrain) {
            Automations.expireState(POWERTRAIN_MODE);
        }

        // Energy-recuperation (regen) level is published by the dedicated fast poller
        // EnergyRegenEvent (1s, self-gated on isEventReferenced) — NOT here. Reading it on
        // the ~5s snapshot lagged a regen change 2-4s (the reported delay); the fast poller
        // catches it within ~1s. Kept off the snapshot so build() does no regen SDK read.
    }

    /**
     * Map the raw energy-mode axis to a word, or null when it is not a user-selectable mode
     * (stop=0, the UNAVAILABLE sentinel, anything outside the SDK enum) so no spurious edge
     * is manufactured. Delegates to the collector so the word set cannot drift from the one
     * the write path and the MQTT/HA state template use.
     */
    static String powertrainModeToString(int mode, boolean supportsPowertrain) {
        if (!supportsPowertrain) return null;
        if (mode < com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_EV
                || mode > com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_KEEP) {
            return null;
        }
        return com.overdrive.app.byd.BydDataCollector.energyModeName(mode);
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

    /**
     * The live 12V rail in volts, or NaN when unavailable. Reads the SAME source the 12V
     * performance graph charts — {@code VehicleDataMonitor.getBatteryPower()}, backed by
     * {@code BYDAutoOtaDevice.getBatteryPowerVoltage()} — rather than the bodywork device's
     * LOW/NORMAL/INVALID enum, which returns INVALID on trims that never populate it.
     * Bounds-checked with the data class's own validity window (9–16V) so a garbage read
     * cannot publish a nonsense voltage.
     */
    private static double read12vVolts() {
        try {
            com.overdrive.app.monitor.VehicleDataMonitor monitor =
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            if (monitor == null) return Double.NaN;
            com.overdrive.app.monitor.BatteryPowerData d = monitor.getBatteryPower();
            if (d == null || !d.isValidRange()) return Double.NaN;
            return d.voltageVolts;
        } catch (Throwable t) {
            return Double.NaN;   // monitor not up yet (early boot) — stay unseeded
        }
    }

    /**
     * Publish the 12V rail in TENTHS of a volt (135 = 13.5V), when a real reading exists.
     *
     * <p>Tenths, not volts, because every numeric condition in this engine is an
     * {@code IntType} — there is no decimal value type — and whole volts would be far too
     * coarse for the one rule this signal exists for ("below 12.2V"). The UI labels the field
     * as tenths so the user picks 122, not 12.2.
     */
    private static void publishAux12vVoltage() {
        double v = read12vVolts();
        if (Double.isNaN(v)) return;
        Automations.update(AUX_BATTERY_12V_VOLTS, (int) Math.round(v * 10.0));
    }

    /** Hours + minutes the vehicle reports to full, or -1 when neither half is a reading. */
    static int chargeRestMinutes(BydVehicleData data) {
        int h = data.chargingRestTimeHours, m = data.chargingRestTimeMinutes;
        boolean hOk = h != BydVehicleData.UNAVAILABLE && h >= 0;
        boolean mOk = m != BydVehicleData.UNAVAILABLE && m >= 0 && m <= 59;
        if (!hOk && !mOk) return -1;
        return (hOk ? h * 60 : 0) + (mOk ? m : 0);
    }

    /** Minimum plausible corner pressure (same 1..600 kPa band MQTT publishes), or -1. */
    static int lowestTyreKpa(int[] kpa) {
        if (kpa == null) return -1;
        int lowest = -1;
        for (int i = 0; i < kpa.length && i < 4; i++) {
            int p = kpa[i];
            if (p > 0 && p <= 600 && (lowest < 0 || p < lowest)) lowest = p;
        }
        return lowest;
    }

    /**
     * Publish the resolved charging power for {@link ChargingPowerEvent}. Uses the same
     * detector-stable snapshot and the same trust gates as MQTT's {@code charge_power}: an
     * estimated placeholder or out-of-domain figure is not a reading, and V2L is not charging.
     * While a charge runs without a trustworthy figure nothing is published, so a threshold
     * rule never sees a manufactured drop to 0.
     */
    public static void pollChargingPower() {
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        if (monitor == null) return;
        com.overdrive.app.monitor.VehicleDataMonitor.ChargingSnapshot snap =
                monitor.getChargingSnapshot();
        if (snap == null) return;
        com.overdrive.app.monitor.ChargingStateData cs = snap.getChargingState();
        BydVehicleData vd = snap.getVehicleData();
        if (cs == null) return;
        boolean v2l = vd != null && vd.chargingGunState == 5;
        boolean charging = !v2l
                && (cs.status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.CHARGING
                    || cs.isTaperCharging);
        if (!charging) {
            Automations.update(CHARGING_POWER, 0);
            return;
        }
        double kw = cs.chargingPowerKW;
        if (cs.isEstimated || !Double.isFinite(kw) || kw <= 0.1 || kw > 500.0) return;
        Automations.update(CHARGING_POWER, (int) Math.round(kw));
    }

    /**
     * Derive the low/normal enum from the real voltage, for trims whose bodywork enum reads
     * INVALID. Uses BatteryPowerData's own warning threshold so the word and the graph's
     * "warning" shading can never disagree.
     */
    private static void publishAux12vFromVoltage() {
        double v = read12vVolts();
        if (Double.isNaN(v)) return;
        Automations.update(AUX_BATTERY_12V,
                v < com.overdrive.app.monitor.BatteryPowerData.WARNING_THRESHOLD_VOLTS
                        ? "low" : "normal");
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
     *
     * <p>Both belt keys are {@link #FAST_POLL_OWNED}, so this is a no-op today: the belts are
     * published only by the live {@code pollSeatbelts} path. Republishing the carried-forward
     * snapshot value from the ~59 partial-rebuild {@code build()} sites is what made a
     * seatbelt automation with an ELSE branch alternate THEN/ELSE every ~500ms after a real
     * buckle. The ownership check is kept here (rather than deleting the call) so the snapshot
     * path stays the single fallback if belt ownership is ever handed back.
     */
    private static void updateSeatbelts(BydVehicleData data) {
        int[] belts = data.seatbeltStatus;
        if (belts == null) return;
        if (belts.length > 0 && !ownedByFastPoll(SEATBELT_DRIVER)) publishSeatbelt(SEATBELT_DRIVER, belts[0]);
        if (belts.length > 1 && !ownedByFastPoll(SEATBELT_PASSENGER)) publishSeatbelt(SEATBELT_PASSENGER, belts[1]);
    }

    /** Publish one seat's already-sanitized belt state: 1→on(buckled), 0→off(unbuckled),
     *  UNAVAILABLE→skip (unseeded). */
    private static void publishSeatbelt(EventData key, int v) {
        if (v == 1) Automations.update(key, "on");
        else if (v == 0) Automations.update(key, "off");
        // UNAVAILABLE / anything else → publish nothing (no false reading)
    }

    /**
     * Publish a typed front-passenger belt callback as an observed edge.
     *
     * <p>Unlike the getter poll, the callback witnessed an actual state transition. Routing it
     * through {@link Automations#updateObservedEdge} ensures the first callback after daemon
     * startup fires instead of becoming a silent sampled seed. The normal delivered-value dedup
     * suppresses a racing poll or duplicate callback of the same state.
     */
    public static void publishPassengerSeatbeltEdge(int state) {
        if (state == 1) {
            Automations.updateObservedEdge(SEATBELT_PASSENGER, "on");
        } else if (state == 0) {
            Automations.updateObservedEdge(SEATBELT_PASSENGER, "off");
        }
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        try {
            com.overdrive.app.byd.BydDataCollector.getInstance().pollPassengerOccupancyEvent();
        } catch (Throwable t) {
            return; // safety-belt device unreachable this tick — leave the events untouched
        }
    }

    /** Publish one validated front-passenger callback state through the normal dedup path. */
    public static void publishPassengerOccupancy(int occupancy) {
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        publishOccupant(OCCUPANT_PASSENGER, occupancy);
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        int gear = com.overdrive.app.byd.BydDataCollector.getInstance().readGearNow();
        if (gear == BydVehicleData.UNAVAILABLE) return;
        // Publish only in-band selector values (P=1 … S=6). During head-unit boot / HAL init the
        // getter can return an out-of-band transient (0, sentinel); gearToString maps that to
        // "unknown(x)", which is a real string transition BOTH ways (…→unknown→p) and re-fires
        // every gear-triggered automation right at power-on. An unknown reading is a non-reading:
        // skip it and keep the last good gear (Automations.update dedups repeats anyway).
        if (gear < GearMonitor.GEAR_P || gear > GearMonitor.GEAR_S) return;
        Automations.update(GEAR, GearMonitor.gearToString(gear).toLowerCase());
    }

    /**
     * Publish speed from a single live HAL read. The speed-device listener does not push
     * {@code onSpeedChanged} on every supported firmware, so relying on that callback leaves the
     * regular five-second snapshot as the fallback. DynamicsEvent calls this at 250 ms only while
     * an enabled rule references speed.
     */
    public static void pollSpeed() {
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        double speedKmh =
                com.overdrive.app.byd.BydDataCollector.getInstance().readSpeedNowKmh();
        publishSpeedKmh(speedKmh);
    }

    /** Publish one canonical km/h speed observation from either the live getter or HAL callback. */
    public static void publishSpeedKmh(double speedKmh) {
        if (Double.isNaN(speedKmh) || Double.isInfinite(speedKmh) || speedKmh < 0.0) return;
        Automations.update(SPEED_KMPH, (int) Math.round(speedKmh));
        Automations.update(SPEED_MPH, (int) Math.round(speedKmh * 0.621371));
    }

    /**
     * Publish per-seat seat-heat / seat-cool, steering-wheel heat, and high/low beam from LIVE reads, for the fast
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
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        com.overdrive.app.byd.BydDataCollector collector =
                com.overdrive.app.byd.BydDataCollector.getInstance();
        // AC system power. This must be a live read: while parked, the regular telemetry
        // snapshot runs at a much lower cadence, but remote preconditioning is exactly when an
        // AC on/off automation is most useful.
        if (Automations.isEventReferenced(AC) || Automations.editorSeedActive()) {
            String state = acStateToString(collector.readAcPowerNow());
            if (state != null) Automations.update(AC, state);
        }
        // Seat cooling (ventilation) — driver + passenger.
        if (Automations.isEventReferenced(SEAT_COOL_DRIVER) || Automations.editorSeedActive()) {
            int v = collector.readSeatClimateNow(false, 1);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_COOL_DRIVER, seatClimateToString(v));
        }
        if (Automations.isEventReferenced(SEAT_COOL_PASSENGER) || Automations.editorSeedActive()) {
            int v = collector.readSeatClimateNow(false, 2);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_COOL_PASSENGER, seatClimateToString(v));
        }
        // Seat heating — driver + passenger.
        if (Automations.isEventReferenced(SEAT_HEAT_DRIVER) || Automations.editorSeedActive()) {
            int v = collector.readSeatClimateNow(true, 1);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_HEAT_DRIVER, seatClimateToString(v));
        }
        if (Automations.isEventReferenced(SEAT_HEAT_PASSENGER) || Automations.editorSeedActive()) {
            int v = collector.readSeatClimateNow(true, 2);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(SEAT_HEAT_PASSENGER, seatClimateToString(v));
        }
        if (Automations.isEventReferenced(STEERING_HEAT) || Automations.editorSeedActive()) {
            String state = steeringHeatStateToString(
                    collector.getSteeringWheelHeatingState());
            if (state != null) Automations.update(STEERING_HEAT, state);
        }
        // AC dial setpoint — only read when a rule actually references it (same gate as every
        // other entry here), so a car whose dial is unreadable pays nothing.
        if (Automations.isEventReferenced(AC_SETPOINT) || Automations.editorSeedActive()) {
            int v = collector.readAcSetpointNow(
                    com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_DRIVER);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(AC_SETPOINT, setpointCelsius(v));
        }
        // High / low beam.
        if (Automations.isEventReferenced(LIGHTS_HIGH_BEAM) || Automations.editorSeedActive()) {
            int v = collector.readBeamNow(true);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(LIGHTS_HIGH_BEAM, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(LIGHTS_LOW_BEAM) || Automations.editorSeedActive()) {
            int v = collector.readBeamNow(false);
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(LIGHTS_LOW_BEAM, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(LIGHTS_DRL) || Automations.editorSeedActive()) {
            int v = collector.readDrlNow();
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(LIGHTS_DRL, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(AUTO_LIGHTS) || Automations.editorSeedActive()) {
            int v = collector.readAutoHeadlightNow();
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(AUTO_LIGHTS, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(AUTO_WIPER) || Automations.editorSeedActive()) {
            int v = collector.readAutoWiperNow();
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(AUTO_WIPER, v == 1 ? "on" : "off");
        }
        if (Automations.isEventReferenced(WIPER_ACTIVE) || Automations.editorSeedActive()) {
            int v = collector.readWiperActiveNow();
            if (v != BydVehicleData.UNAVAILABLE) Automations.update(WIPER_ACTIVE, v == 1 ? "on" : "off");
        }
    }

    /**
     * Publish documented safety/ADAS states. The raw bodywork "emergency alarm" enum is
     * deliberately excluded: on this firmware it reflects anti-theft/armed state, not an
     * accident or emergency event.
     */
    private static void updateSafetyEvents(BydVehicleData data) {
        // Tyre pressure: worst state across the four wheels (0=normal,1=over,2=under).
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
            if (BydVehicleData.isTyreOverpressureState(s)) over = true;
            else if (BydVehicleData.isTyreUnderpressureState(s)) under = true;
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
            expireTurnHolds();
            return;
        }
        if (packed < 0) {
            expireTurnHolds();
            return;
        }
        applyTurnSample(packed, monotonicMs());
    }

    /** Apply one validated packed turn-lamp sample. Kept separate from the HAL read so the
     * hazard/indicator exclusivity and blink debounce can be tested deterministically. */
    static void applyTurnSample(int packed, long now) {
        boolean left = (packed & 0x1) != 0;
        boolean right = (packed & 0x2) != 0;
        boolean hazard = left && right;
        if (hazard) {
            publishTurn(LIGHTS_HAZARD, true, lastHazardOnMs, now);
            forceTurnOff(TURN_LEFT, lastLeftOnMs);
            forceTurnOff(TURN_RIGHT, lastRightOnMs);
            return;
        }

        // Seeing either individual side is positive proof that hazard mode is no longer active,
        // so release it immediately. With no lamp lit, retain the normal blink debounce.
        if (left || right) {
            forceTurnOff(LIGHTS_HAZARD, lastHazardOnMs);
        } else {
            publishTurn(LIGHTS_HAZARD, false, lastHazardOnMs, now);
        }
        publishTurn(TURN_LEFT, left, lastLeftOnMs, now);
        publishTurn(TURN_RIGHT, right, lastRightOnMs, now);
    }

    /**
     * Release a debounce hold when the light device stops answering. Without this, a side
     * published "on" stays "on" for the life of the process — only a successful read publishes
     * "off", so an unreadable device (parked with the hazards last seen lit) would leave the key
     * stuck. Once the hold has aged past the debounce window the last on-phase is no longer
     * evidence of a live lamp, so report "off"; inside the window keep holding, exactly as a
     * blink off-phase does.
     */
    private static void expireTurnHolds() {
        long now = monotonicMs();
        expireTurnHold(TURN_LEFT, lastLeftOnMs, now);
        expireTurnHold(TURN_RIGHT, lastRightOnMs, now);
        expireTurnHold(LIGHTS_HAZARD, lastHazardOnMs, now);
    }

    private static void expireTurnHold(
            EventData key, java.util.concurrent.atomic.AtomicLong lastOn, long now) {
        long since = lastOn.get();
        if (since == 0) return; // never seen lit → nothing to release, stay unseeded
        if ((now - since) < TURN_OFF_DEBOUNCE_MS) return;
        lastOn.set(0L);
        Automations.update(key, "off");
    }

    /**
     * Monotonic milliseconds for the blink debounce. Deliberately NOT currentTimeMillis: a
     * head unit boots on a stale RTC and NTP/GPS corrects it seconds later, and a backward jump
     * makes {@code now - since} negative — which reads as "inside the off-phase window" and pins
     * the indicator "on" for the whole size of the jump.
     */
    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
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

    private static void forceTurnOff(
            EventData key, java.util.concurrent.atomic.AtomicLong lastOn) {
        lastOn.set(0L);
        Automations.update(key, "off");
    }

    /** A dial setpoint in display units → whole Celsius (the disjoint bands identify the scale). */
    private static int setpointCelsius(int setpoint) {
        if (setpoint >= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_F
                && setpoint <= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_F) {
            return (int) Math.round((setpoint - 32) * 5.0 / 9.0);
        }
        return setpoint;
    }

    /**
     * Publish temperature events according to what they measure, not the car's power state.
     * {@link #TEMPERATURE} is measured cabin air and remains unavailable when this vehicle does
     * not expose that sensor. {@link #OUTSIDE_TEMPERATURE} is ambient air from the exterior sensor
     * or weather fallback, while {@link #AC_SETPOINT} is the climate dial.
     */
    private static void updateTemperature(BydVehicleData data) {
        // Compute the ambient (outside) temperature once: outside cluster sensor first,
        // then weather by last-known GPS. It belongs only to OUTSIDE_TEMPERATURE.
        double ambient = ambientTemperature(data);
        if (!Double.isNaN(ambient)) {
            Automations.update(OUTSIDE_TEMPERATURE, (int) Math.round(ambient));
        }

        // AC dial setpoint — published only when the dial actually answered (UNAVAILABLE on a
        // miss), so a trim that doesn't expose getTemprature leaves the event unseeded rather
        // than fabricating a value a comparison would silently match against.
        if (data.acSetpointDriver != BydVehicleData.UNAVAILABLE) {
            publishFromSnapshot(AC_SETPOINT, setpointCelsius(data.acSetpointDriver));
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
        // the cabin value until its bounded automation-retention deadline, whatever the power
        // state. If the sensor stops answering, TEMPERATURE expires instead of being silently
        // replaced by outside air.
        Integer cabinTemperature = retainedCabinTemperatureForAutomation(data);
        if (cabinTemperature != null) {
            // Re-publish against the original observation deadline. This reconstructs the same
            // retained state when the editor opens after automations were previously disabled;
            // it never extends a stale parked reading.
            Automations.updateExpiring(
                    TEMPERATURE,
                    cabinTemperature,
                    data.insideTempReadAt + CABIN_TEMP_GIVE_UP_MS);
            return;
        }
        // Do not publish another source under the same signal. The expiration overlay hides the
        // last measured value after the give-up deadline; outside air and the HVAC setpoint remain
        // separate signals.
    }

    /** Fresh measured cabin temperature for the automation signal, or null when unavailable. */
    static Integer freshCabinTemperatureForAutomation(BydVehicleData data) {
        if (!isCabinTempFresh(data)) return null;
        return (int) Math.round(data.insideTempC);
    }

    /** A measured cabin value still inside the automation retention window, or null. */
    static Integer retainedCabinTemperatureForAutomation(BydVehicleData data) {
        if (data == null || Double.isNaN(data.insideTempC)
                || cabinTempAge(data) >= CABIN_TEMP_GIVE_UP_MS) {
            return null;
        }
        return (int) Math.round(data.insideTempC);
    }

    /**
     * Whether {@code insideTempC} came from a recent AC-device read rather than being carried
     * forward from an older snapshot.
     *
     * <p>The window MUST be sized against the PARKED poll cadence, not the driving one: the
     * collector polls every 5s with ACC on but only every 90s with ACC off
     * ({@code BydDataCollector.POLL_INTERVAL_PARKED_MS}). A window shorter than that would make a
     * parked cabin reading look stale on essentially every evaluation. 4 missed parked
     * polls (6 min) tolerates a busy HAL or a couple of transient device errors while still ageing
     * out a sensor that has genuinely stopped answering.
     */
    private static boolean isCabinTempFresh(BydVehicleData data) {
        return data.hasFreshCabinTemperature();
    }

    /** Age of the last cabin read in ms, or Long.MAX_VALUE if there has never been one. A clock jump
     *  backwards yields a negative age, which every caller treats as fresh — better than spuriously
     *  expiring the last measured value. */
    private static long cabinTempAge(BydVehicleData data) {
        if (data.insideTempReadAt <= 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - data.insideTempReadAt;
    }

    /** Age at which the cabin sensor is declared gone and TEMPERATURE becomes unavailable. Beyond
     *  {@link BydVehicleData#CABIN_TEMP_MAX_AGE_MS} so the gap between them is a quiet hold. */
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

    /**
     * Seat heat/cool level word, or null when the slot is not a real 0/1/2 reading. Null, not
     * "unknown": the condition's vocabulary is {off, low, high}, so an "unknown" would be a word
     * no rule could ever match (the same silent-killer the cpd publish had).
     */
    private static String seatClimateToString(int level) {
        switch (level) {
            case 0:
                return "off";
            case 1:
                return "low";
            case 2:
                return "high";
            default:
                return null;
        }
    }

    /** Raw {@code getAcStartState()} value to the automation vocabulary, or null on a miss. */
    static String acStateToString(int state) {
        if (state == 0) return "off";
        if (state == 1) return "on";
        return null;
    }

    /** Raw steering-wheel heater value to the automation vocabulary, or null on a miss. */
    static String steeringHeatStateToString(int state) {
        if (state == 1) return "off";
        if (state == 2) return "on";
        return null;
    }

    /**
     * CPD state word, or null when the reading is not one of the three real states.
     *
     * <p>Returns null rather than "unknown" (which it used to) because "unknown" is not in the
     * condition's declared vocabulary {on, off, delay}. The builder field has no initializer, so
     * it defaults to 0 on any trim whose CPD feature never answers — the collector deliberately
     * rejects the 0 / 65535 rails — and publishing "unknown" pinned a word the editor cannot
     * select: every cpd rule compared lexically against it and could never match. Mirrors
     * EnergyRegenEvent.regenWord, which returns null for the same reason.
     */
    private static String cpdToString(int value) {
        switch (value) {
            case 1:
                return "on";
            case 2:
                return "off";
            case 3:
                return "delay";
            default:
                return null;
        }
    }
}
