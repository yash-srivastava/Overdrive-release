package com.overdrive.app.automation.action;

import com.overdrive.app.automation.type.AppType;
import com.overdrive.app.automation.type.AudioType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.DynamicColourType;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.SavedSeatPositionType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.light.LightConstants;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class Actions {
    // Create LinkedHashMap to maintain the insertion order of actions to display in the frontend
    private final Map<String, Action> actions = new LinkedHashMap<>();

    /**
     * Preset "switch the AC off again after…" windows, in MINUTES, shared by the {@code setAc}
     * (AC power) and {@code acAutoOff} (standalone timer) actions so both offer the identical list.
     *
     * <p>An enum (not an IntType) because these are the only windows worth offering and a picker
     * cannot produce a nonsense value. The ids are the minute counts the daemon consumes
     * directly — the RHS is substituted into {@code "autoOffMinutes":${autoOffMinutes}} as a bare
     * JSON number, so the id MUST stay numeric text.
     *
     * <p>{@code "0"} = "stay on", which is both the default for automations saved before this
     * variable existed and the way to cancel a window.
     */
    static final EnumType AC_AUTO_OFF_MINUTES = new EnumType(
            new Label("autoOffMinutes", "automation.ac_auto_off"),
            new Label("0", "automation.ac_auto_off_never"),
            new Label("10", "automation.ac_auto_off_10"),
            new Label("15", "automation.ac_auto_off_15"),
            new Label("30", "automation.ac_auto_off_30"),
            new Label("45", "automation.ac_auto_off_45"),
            new Label("60", "automation.ac_auto_off_60"),
            new Label("90", "automation.ac_auto_off_90"),
            new Label("120", "automation.ac_auto_off_120"));

    /** The five fixed OPENAIR/BOOKINGAIR session lengths supported by BYD cloud. */
    static final EnumType REMOTE_CLIMATE_DURATIONS = new EnumType(
            new Label("remoteDurationMinutes", "automation.remote_climate_duration"),
            new Label("10", "automation.remote_climate_duration_10"),
            new Label("15", "automation.remote_climate_duration_15"),
            new Label("20", "automation.remote_climate_duration_20"),
            new Label("25", "automation.remote_climate_duration_25"),
            new Label("30", "automation.remote_climate_duration_30"));

    /**
     * Initialize actions list with actions that can be selected
     */
    public Actions() {
        addAction(new NotificationAction(new Label("notification", "automation.send_notification"), "automation.send_notification_description"));
        addAction(new GenAiInsightAction());
        // ── Composite vehicle commands (parity with key mapping) ──────────
        // lock / unlock / flash / find-car route through the dedicated
        // /api/vehicle/<action> endpoints (cloud-first on this generation). These
        // are the same commands the keymap "vehicle" kind exposes; the endpoints
        // are already inside the automation /api/vehicle/ allowlist.
        addAction(new ApiAction(
                new Label("lock", "automation.lock"), "automation.lock_description",
                "POST", "/api/vehicle/lock", ""));
        addAction(new ApiAction(
                new Label("unlock", "automation.unlock"), "automation.unlock_description",
                "POST", "/api/vehicle/unlock", ""));
        addAction(new ApiAction(
                new Label("flash", "automation.flash"), "automation.flash_description",
                "POST", "/api/vehicle/flash", ""));
        addAction(new ApiAction(
                new Label("findCar", "automation.find_car"), "automation.find_car_description",
                "POST", "/api/vehicle/find-car", ""));
        addAction(new ApiAction(
                new Label("powerOn", "automation.power_on"), "automation.power_on_description",
                "POST", "/api/vehicle/power-on", ""));
        addAction(new ApiAction(
                new Label("powerOff", "automation.power_off"), "automation.power_off_description",
                "POST", "/api/vehicle/power-off", ""));
        addAction(new VehicleControlAction(
                new Label("adas_slw", "automation.set_slw"), "automation.set_slw_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // ESP / Electronic Stability Control — label id MUST equal the catalog key
        // "esp_control" so VehicleControlAction.trigger resolves it. SAFETY control.
        addAction(new VehicleControlAction(
                new Label("esp_control", "automation.set_esp"), "automation.set_esp_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // iTAC (Intelligent Torque Adaption Control) — label id MUST equal the catalog
        // key "itac". Performance/traction feature, not a safety interlock.
        addAction(new VehicleControlAction(
                new Label("itac", "automation.set_itac"), "automation.set_itac_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Fold / unfold exterior mirrors — label id MUST equal the catalog key
        // "mirror_fold". on=fold in, off=unfold. Set-only (no fold-state getter).
        addAction(new VehicleControlAction(
                new Label("mirror_fold", "automation.set_mirror_fold"), "automation.set_mirror_fold_description",
                new EnumType(new Label("payload", "automation.action"), new Label("on", "automation.mirror_fold"), new Label("off", "automation.mirror_unfold"))));
        // Persistent vehicle setting — enable it while the vehicle is awake and the OEM controls
        // folding/unfolding at subsequent power transitions. This is deliberately not another
        // immediate mirror-fold action, because that bodywork command is unavailable at ACC off.
        addAction(new VehicleControlAction(
                new Label("mirror_auto_follow_up", "automation.set_mirror_auto_follow_up"),
                "automation.set_mirror_auto_follow_up_description",
                new EnumType(new Label("payload", "automation.action"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"))));
        addAction(new ApiAction(
                new Label("cpd", "automation.set_cpd"),
                "automation.set_cpd_description",
                "POST",
                "/api/vehicle/setting",
                "{\"target\":\"childPresenceDetection\",\"value\":${action}}",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("2", "automation.off"),
                        new Label("1", "automation.on"),
                        new Label("3", "automation.cpd_delay"))));
        addAction(new VehicleControlAction(
                new Label("drl", "automation.set_drl"), "automation.set_drl_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // OEM four-position exterior-light selector. The label id MUST equal the shared
        // catalog key so automations and key mappings execute the identical routed command.
        addAction(new VehicleControlAction(
                new Label("headlight_mode", "automation.set_headlight_mode"),
                "automation.set_headlight_mode_description",
                new EnumType(
                        new Label("payload", "automation.headlight_mode"),
                        new Label("off", "automation.off"),
                        new Label("auto", "automation.headlight_auto"),
                        new Label("parking", "automation.parking_lights"),
                        new Label("low_beam", "automation.headlight_on_low_beam"))));
        // Hazard (double-flash) lights on/off — catalog key "hazard". The SET is
        // unconfirmed on this platform (see VehicleControlCatalog/HazardCommand); the
        // action reports failure honestly if the HAL refuses the write.
        addAction(new VehicleControlAction(
                new Label("hazard", "automation.set_hazard"), "automation.set_hazard_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // ── Expanded ADAS matrix (catalog keys must match VehicleControlCatalog) ──
        // Warning/info toggles (on/off). Feature ids/polarity unverified per trim —
        // verify via GET /api/vehicle/adas. The description keys note when a control is
        // a safety intervention.
        for (String[] a : new String[][]{
                {"adas_bsd",  "automation.set_bsd"},
                {"adas_tsr",  "automation.set_tsr"},
                {"adas_rcta", "automation.set_rcta"},
                {"adas_fcta", "automation.set_fcta"},
                {"adas_tla",  "automation.set_tla"},
                {"adas_dow",  "automation.set_dow"},
                {"adas_rcw",  "automation.set_rcw"},
                {"adas_islc", "automation.set_islc"},
        }) {
            addAction(new VehicleControlAction(
                    new Label(a[0], a[1]), a[1] + "_description",
                    new EnumType(new Label("payload", "automation.action"),
                            new Label("off", "automation.off"), new Label("on", "automation.on"))));
        }
        // Safety interventions (disabling reduces protection) — same on/off shape, but
        // their _description strings warn about the safety impact.
        for (String[] a : new String[][]{
                {"adas_elka", "automation.set_elka"},
                {"adas_rctb", "automation.set_rctb"},
                {"adas_fctb", "automation.set_fctb"},
        }) {
            addAction(new VehicleControlAction(
                    new Label(a[0], a[1]), a[1] + "_description",
                    new EnumType(new Label("payload", "automation.action"),
                            new Label("off", "automation.off"), new Label("on", "automation.on"))));
        }
        // Forward Collision Warning sensitivity LEVEL (multi-mode, not on/off).
        addAction(new VehicleControlAction(
                new Label("adas_fcw", "automation.set_fcw"), "automation.set_fcw_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("0", "automation.off"),
                        new Label("1", "automation.fcw_low"),
                        new Label("2", "automation.fcw_medium"),
                        new Label("3", "automation.fcw_high"))));
        // Automatic Emergency Braking — SAFETY-CRITICAL: ENABLE-ONLY. The only option
        // is "on" so an automation can re-arm AEB but never silently disable it.
        addAction(new VehicleControlAction(
                new Label("adas_aeb", "automation.set_aeb"), "automation.set_aeb_description",
                new EnumType(new Label("payload", "automation.action"), new Label("on", "automation.on"))));
        // ── Climate breadth: AC auto / sync / fan-only / steering-wheel heater ──
        // Each maps the on/off enum into a distinct /api/vehicle/climate action string.
        addAction(new ApiAction(
                new Label("acAuto", "automation.set_ac_auto"),
                "automation.set_ac_auto_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"auto_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("acSync", "automation.set_ac_sync"),
                "automation.set_ac_sync_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"sync_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // A standalone "switch the AC off in N minutes" step, so a window can be armed (or
        // cancelled, with "0") without also re-issuing a power command — e.g. extend the window
        // when the cabin is still too warm, or cancel it when someone gets in.
        addAction(new ApiAction(
                new Label("acAutoOff", "automation.set_ac_auto_off"),
                "automation.set_ac_auto_off_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"auto_off_timer\",\"autoOffMinutes\":${autoOffMinutes}}",
                AC_AUTO_OFF_MINUTES));
        addAction(new ApiAction(
                new Label("fanOnly", "automation.set_fan_only"),
                "automation.set_fan_only_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"fan_only_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("maxCool", "automation.set_max_cool"),
                "automation.set_max_cool_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"max_cool_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("airPurify", "automation.set_air_purify"),
                "automation.set_air_purify_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"purify_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("rainCloseWindows", "automation.set_rain_close_windows"),
                "automation.set_rain_close_windows_description",
                "POST",
                "/api/vehicle/window",
                "{\"action\":\"rain_close_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("steeringHeat", "automation.set_steering_heat"),
                "automation.set_steering_heat_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"steering_heat_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Front / rear windscreen defrost (demist) on/off — AC-device feature writes, same
        // /api/vehicle/climate routing as the other breadth toggles.
        addAction(new ApiAction(
                new Label("frontDefrost", "automation.set_front_defrost"),
                "automation.set_front_defrost_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"defrost_front_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("rearDefrost", "automation.set_rear_defrost"),
                "automation.set_rear_defrost_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"defrost_rear_${action}\"}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Air intake: Recirculate (recycle cabin air) vs Fresh air (draw outside air).
        // recirculate_on = RECIRCULATION, recirculate_off = FRESH_AIR (AC cycle axis).
        addAction(new ApiAction(
                new Label("recirculation", "automation.set_recirculation"),
                "automation.set_recirculation_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"recirculate_${mode}\"}",
                new EnumType(new Label("mode", "automation.air_intake"),
                        new Label("on", "automation.recirculate"),
                        new Label("off", "automation.fresh_air"))));
        // ── Lighting breadth: welcome / reading / ambient-music (on/off), headlight level ──
        // Lights use target + enable(bool); the enum value maps to true/false in the body.
        addAction(new ApiAction(
                new Label("welcomeLight", "automation.set_welcome_light"),
                "automation.set_welcome_light_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"welcomeLight\",\"enable\":${action}}",
                new EnumType(new Label("action", "automation.action"), new Label("false", "automation.off"), new Label("true", "automation.on"))));
        addAction(new ApiAction(
                new Label("turnSignalLeft", "automation.set_turn_signal_left"),
                "automation.set_turn_signal_left_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"turnSignalLeft\",\"enable\":${action}}",
                new EnumType(new Label("action", "automation.action"), new Label("false", "automation.off"), new Label("true", "automation.on"))));
        addAction(new ApiAction(
                new Label("turnSignalRight", "automation.set_turn_signal_right"),
                "automation.set_turn_signal_right_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"turnSignalRight\",\"enable\":${action}}",
                new EnumType(new Label("action", "automation.action"), new Label("false", "automation.off"), new Label("true", "automation.on"))));
        addAction(new ApiAction(
                new Label("readingLight", "automation.set_reading_light"),
                "automation.set_reading_light_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"readingLight\",\"enable\":${action}}",
                new EnumType(new Label("action", "automation.action"), new Label("false", "automation.off"), new Label("true", "automation.on"))));
        addAction(new ApiAction(
                new Label("ambientMusic", "automation.set_ambient_music"),
                "automation.set_ambient_music_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"ambientMusic\",\"enable\":${action}}",
                new EnumType(new Label("action", "automation.action"), new Label("false", "automation.off"), new Label("true", "automation.on"))));
        addAction(new ApiAction(
                new Label("headlightLevel", "automation.set_headlight_level"),
                "automation.set_headlight_level_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"headlightLevel\",\"value\":${level}}",
                new IntType(new Label("level", "automation.headlight_level_value"), 1, 11)));
        // The all windows option can't be done to a specific percentage so not including it
        addAction(new ApiAction(
                new Label("windows", "automation.open_windows"),
                "automation.open_windows_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":${area},\"targetPercent\":${percent}}",
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("1", "automation.area_lf"),
                        new Label("2", "automation.area_rf"),
                        new Label("3", "automation.area_lr"),
                        new Label("4", "automation.area_rr"),
                        new Label("5", "automation.area_sunroof"),
                        new Label("6", "automation.area_sunshade")),
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Window position PRESET — friendlier than a raw percentage. Maps a named
        // position (Closed/Vent/Half/Full) to a target percent via the same
        // /api/vehicle/window closed-loop positioning path (moveWindowToPercent).
        // Per-area, since only individual windows support percent positioning (the
        // all-windows path is open/close/stop only). The preset value IS the percent
        // (0/15/50/100) so it substitutes straight into targetPercent.
        addAction(new ApiAction(
                new Label("windowsPreset", "automation.window_preset"),
                "automation.window_preset_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":${area},\"targetPercent\":${preset}}",
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("1", "automation.area_lf"),
                        new Label("2", "automation.area_rf"),
                        new Label("3", "automation.area_lr"),
                        new Label("4", "automation.area_rr"),
                        new Label("5", "automation.area_sunroof"),
                        new Label("6", "automation.area_sunshade")),
                new EnumType(
                        new Label("preset", "automation.window_position"),
                        new Label("0", "automation.window_closed"),
                        new Label("15", "automation.window_vent"),
                        new Label("50", "automation.window_half"),
                        new Label("100", "automation.window_full"))));
        // All-windows open/close/stop (parity with keymap windows_all). Routes to
        // /api/vehicle/window with area=0; the handler picks the cloud close-all path
        // for close and the SDK path for open/stop. command: 1=open, 2=close, 3=stop.
        addAction(new ApiAction(
                new Label("windowsAll", "automation.windows_all"),
                "automation.windows_all_description",
                "POST",
                "/api/vehicle/window",
                "{\"area\":0,\"command\":${command}}",
                new EnumType(
                        new Label("command", "automation.action"),
                        new Label("1", "automation.open"),
                        new Label("2", "automation.close"),
                        new Label("3", "automation.stop"))));
        // Vent is a local 15% target while awake and OPENWINDOW remotely, not the
        // full-open operation above. Keep it distinct so a rule never claims it
        // fully opened the windows.
        addAction(new ApiAction(
                new Label("windowsVent", "automation.vent_windows"),
                "automation.vent_windows_description",
                "POST",
                "/api/vehicle/window",
                "{\"action\":\"vent\"}"));
        // Tailgate / trunk open/close/stop (parity with keymap tailgate).
        addAction(new ApiAction(
                new Label("tailgate", "automation.tailgate"),
                "automation.tailgate_description",
                "POST",
                "/api/vehicle/trunk",
                "{\"action\":\"${action}\"}",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("open", "automation.open"),
                        new Label("close", "automation.close"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunshade", "automation.set_sunshade"),
                "automation.set_sunshade_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        addAction(new VehicleControlAction(
                new Label("sunroof", "automation.set_sunroof"),
                "automation.set_sunroof_description",
                new EnumType(
                        new Label("payload", "automation.action"),
                        new Label("close", "automation.close"),
                        new Label("open", "automation.open"),
                        new Label("stop", "automation.stop"))));
        // AC power, with an optional "then switch off after N minutes" window. The duration is
        // an ENUM of preset intervals rather than a free-text number: these are the windows a
        // user actually wants (a quick cabin airing through to an hour of pre-conditioning),
        // and a picker can't produce a typo'd 600. "0" = stay on indefinitely, preserving the
        // behaviour of every automation saved before this variable existed (see
        // BaseAction.RETROFITTED_DEFAULTS, which defaults it for those rows).
        //
        // The window is honoured only for action=on. The daemon arms a single cancellable timer
        // (AcAutoOffTimer) instead of blocking, because the automation worker is one thread and
        // sleeping it for an hour would stall every other rule.
        addAction(new ApiAction(
                new Label("setAc", "automation.set_ac"),
                "automation.set_ac_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"power_${action}\",\"temp\":${temp},"
                        + "\"remoteDurationMinutes\":${remoteDurationMinutes},"
                        + "\"autoOffMinutes\":${autoOffMinutes}}",
                new EnumType(new Label("action", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on")),
                new IntType(new Label("temp", "automation.temperature"), 15, 33),
                REMOTE_CLIMATE_DURATIONS,
                AC_AUTO_OFF_MINUTES));
        // BOOKINGAIR has no local SDK equivalent. This creates a cloud remote-climate booking
        // for a known future epoch-second time; capability discovery and terminal confirmation
        // remain in the normal router path behind the API handler.
        addAction(new ApiAction(
                new Label("remoteClimateSchedule", "automation.remote_climate_schedule"),
                "automation.remote_climate_schedule_description",
                "POST",
                "/api/vehicle/climate-schedule",
                "{\"action\":\"create\",\"bookingTime\":\"${bookingTime}\",\"temp\":${temp},"
                        + "\"durationMinutes\":${durationMinutes}}",
                new StringType(new Label("bookingTime", "automation.remote_climate_booking_time"), 10),
                new IntType(new Label("temp", "automation.temperature"), 15, 31),
                new EnumType(new Label("durationMinutes", "automation.remote_climate_duration"),
                        new Label("10", "automation.remote_climate_duration_10"),
                        new Label("15", "automation.remote_climate_duration_15"),
                        new Label("20", "automation.remote_climate_duration_20"),
                        new Label("25", "automation.remote_climate_duration_25"),
                        new Label("30", "automation.remote_climate_duration_30"))));
        // Booking IDs returned by BYD are 64-bit. Keep them as decimal text through the
        // automation editor so JavaScript cannot round their low bits before the API parser.
        addAction(new ApiAction(
                new Label("remoteClimateScheduleUpdate", "automation.remote_climate_schedule_update"),
                "automation.remote_climate_schedule_update_description",
                "POST",
                "/api/vehicle/climate-schedule",
                "{\"action\":\"update\",\"bookingId\":\"${bookingId}\","
                        + "\"bookingTime\":\"${bookingTime}\",\"temp\":${temp},"
                        + "\"durationMinutes\":${durationMinutes}}",
                new StringType(new Label("bookingId", "automation.remote_climate_booking_id"), 19),
                new StringType(new Label("bookingTime", "automation.remote_climate_booking_time"), 10),
                new IntType(new Label("temp", "automation.temperature"), 15, 31),
                new EnumType(new Label("durationMinutes", "automation.remote_climate_duration"),
                        new Label("10", "automation.remote_climate_duration_10"),
                        new Label("15", "automation.remote_climate_duration_15"),
                        new Label("20", "automation.remote_climate_duration_20"),
                        new Label("25", "automation.remote_climate_duration_25"),
                        new Label("30", "automation.remote_climate_duration_30"))));
        addAction(new ApiAction(
                new Label("remoteClimateScheduleDelete", "automation.remote_climate_schedule_delete"),
                "automation.remote_climate_schedule_delete_description",
                "POST",
                "/api/vehicle/climate-schedule",
                "{\"action\":\"delete\",\"bookingId\":\"${bookingId}\"}",
                new StringType(new Label("bookingId", "automation.remote_climate_booking_id"), 19)));
        // Zone: which dial the write targets. 0 = both, 1 = driver, 2 = passenger — values
        // accepted directly by /api/vehicle/climate. Offered on both the
        // absolute set and the relative step so a rule can warm just the driver's side.
        addAction(new ApiAction(
                new Label("setAcTemp", "automation.set_ac_temp"),
                "automation.set_ac_temp_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_temp\",\"zone\":${zone},\"temp\":${temperature}}",
                new IntType(new Label("temperature", "automation.temperature"), 17, 33),
                new EnumType(new Label("zone", "automation.ac_zone"),
                        new Label("0", "automation.ac_zone_both"),
                        new Label("1", "automation.ac_zone_driver"),
                        new Label("2", "automation.ac_zone_passenger"))));
        // RELATIVE temperature step — "one degree warmer/cooler than it is now", the thing an
        // absolute setpoint can't express. Reads the chosen dial, adds the delta, clamps to the
        // dial range for the CURRENT display unit (so ±1 is one notch on a °C or °F car alike).
        // Only "zone" is sent; the handler derives which dial to READ from it (driver/both → the
        // driver dial, passenger → the passenger dial), so a driver-only step reads the driver dial.
        addAction(new ApiAction(
                new Label("stepAcTemp", "automation.step_ac_temp"),
                "automation.step_ac_temp_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"step_temp\",\"delta\":${delta},\"zone\":${zone}}",
                new EnumType(
                        new Label("delta", "automation.step_direction"),
                        new Label("1", "automation.step_warmer"),
                        new Label("-1", "automation.step_cooler")),
                new EnumType(new Label("zone", "automation.ac_zone"),
                        new Label("0", "automation.ac_zone_both"),
                        new Label("1", "automation.ac_zone_driver"),
                        new Label("2", "automation.ac_zone_passenger"))));
        addAction(new ApiAction(
                new Label("setAcFan", "automation.set_ac_fan"),
                "automation.set_ac_fan_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"set_fan\",\"fan\":${speed}}",
                new IntType(new Label("speed", "automation.speed"), 1, 7)));
        // RELATIVE fan step — one level faster/slower than it is now, clamped to 1..7. Refused
        // while the AC is off, so a rule cannot start the climate by nudging the fan.
        addAction(new ApiAction(
                new Label("stepAcFan", "automation.step_ac_fan"),
                "automation.step_ac_fan_description",
                "POST",
                "/api/vehicle/climate",
                "{\"action\":\"step_fan\",\"delta\":${delta}}",
                new EnumType(
                        new Label("delta", "automation.step_direction"),
                        new Label("1", "automation.step_faster"),
                        new Label("-1", "automation.step_slower"))));
        addAction(new VehicleControlAction(
                new Label("seat", "automation.seat_climate"),
                "automation.set_seat_climate_description",
                new EnumType(new Label("type", "automation.type"), new Label("heat", "automation.heat"), new Label("vent", "automation.cool")),
                new EnumType(
                        new Label("area", "automation.area"),
                        new Label("driver", "automation.driver"),
                        new Label("passenger", "automation.passenger")),
                // Seat climate is 3-level hardware (off/low/high); the SDK write path collapses any
                // "medium" onto high, so exposing only these three keeps the action honest and
                // symmetric with the seatClimate condition enum.
                new EnumType(
                        new Label("payload", "automation.state"),
                        new Label("off", "automation.off"),
                        new Label("low", "automation.low"),
                        new Label("high", "automation.high"))));
        addAction(new ApiAction(
                new Label("seatPosition", "automation.set_seat_position"),
                "automation.set_seat_position_description",
                "POST",
                "/api/vehicle/seat",
                "{\"action\":\"position\",\"position\":${position}}",
                new EnumType(
                        new Label("position", "automation.action"),
                        new Label("1", "automation.position_1"),
                        new Label("2", "automation.position_2"))));
        // Save current driver-seat position into a memory slot (parity with keymap
        // seat_save; mirror of the recall above).
        addAction(new ApiAction(
                new Label("seatSave", "automation.save_seat_position"),
                "automation.save_seat_position_description",
                "POST",
                "/api/vehicle/seat",
                "{\"action\":\"save\",\"position\":${position}}",
                new EnumType(
                        new Label("position", "automation.action"),
                        new Label("1", "automation.position_1"),
                        new Label("2", "automation.position_2"))));
        // Recall a saved seat AND mirror position from OverDrive's own store. This is a
        // different memory system from seatPosition/seatSave above: those drive the car's
        // hardware ECU banks. On the Seal this was developed against, recalling a bank moves
        // the seat to a position the owner never set — consistent with the banks holding
        // defaults because nothing on that car writes them, though their contents were never
        // read to confirm it. Which models expose those banks (door buttons) and which only
        // have the profile system is unknown. This action replays the absolute geometry the
        // DiLink profile system uses, mirrors included.
        //
        // The position list is per-device and changes at runtime (user entries come and go,
        // captured entries differ per signed-in profile), so the variable is a live picker
        // (SavedSeatPositionType -> dropdown fed by GET /api/positions), not a baked EnumType.
        // The stored value is the position id; the frontend resolves it to a name for display.
        //
        // Routes through POST /api/positions/apply, which is allowlisted by EXACT path in
        // HttpServer.AUTOMATION_ALLOWED_PREFIXES — deliberately not the /api/positions/ prefix,
        // which would also hand automations create/save/delete.
        addAction(new ApiAction(
                new Label("applySeatPosition", "automation.apply_seat_position"),
                "automation.apply_seat_position_description",
                "POST",
                "/api/positions/apply",
                "{\"id\":\"${id}\"}",
                new SavedSeatPositionType(new Label("id", "automation.position"))));
        // Child lock on/off (parity with keymap child_lock). label id "child_lock"
        // matches the VehicleControlCatalog key.
        addAction(new VehicleControlAction(
                new Label("child_lock", "automation.child_lock"), "automation.child_lock_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Phone wireless charger on/off (parity with keymap wireless_charging).
        addAction(new VehicleControlAction(
                new Label("wireless_charging", "automation.wireless_charging"), "automation.wireless_charging_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        // Per-pad wireless charger (dual-pad trims). label id MUST equal the catalog key.
        addAction(new VehicleControlAction(
                new Label("wireless_charging_left", "automation.wireless_charging_left"), "automation.wireless_charging_left_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new VehicleControlAction(
                new Label("wireless_charging_right", "automation.wireless_charging_right"), "automation.wireless_charging_right_description",
                new EnumType(new Label("payload", "automation.action"), new Label("off", "automation.off"), new Label("on", "automation.on"))));
        addAction(new ApiAction(
                new Label("setAmbient", "automation.set_ambient"),
                "automation.set_ambient_description",
                "POST",
                "/api/vehicle/lights",
                "{\"target\":\"ambientColour\",\"value\":${colour},\"zone\":\"${zone}\"}",
                new DynamicColourType(new Label("colour", "automation.colour"), LightConstants.AMBIENT_COLOURS),
                ambientZoneType()));
        // Media volume (STREAM_MUSIC) as an ABSOLUTE step 0-40 — the same scale as the
        // car's own volume button. Routes through the allowlisted /api/vehicle/media
        // (AudioManager on the daemon context; the index is clamped to the stream max).
        addAction(new ApiAction(
                new Label("mediaVolume", "automation.set_media_volume"),
                "automation.set_media_volume_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"volume\",\"value\":${level}}",
                new DynamicIntType(new Label("level", "automation.volume_level"), 0, 40)));
        // Infotainment screen brightness as a 0-100 percentage (setting HAL).
        addAction(new ApiAction(
                new Label("screenBrightness", "automation.set_screen_brightness"),
                "automation.set_screen_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Driver-cluster (instrument display) brightness as a 0-100 percentage
        // (dedicated setDriverDisplayBrightness setter on the setting HAL).
        addAction(new ApiAction(
                new Label("clusterBrightness", "automation.set_cluster_brightness"),
                "automation.set_cluster_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"cluster_brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // Head-up-display brightness as a 0-100 percentage (dedicated setHUDBrightness
        // setter). There is no HUD on/off switch on this platform — 0 dims it out.
        addAction(new ApiAction(
                new Label("hudBrightness", "automation.set_hud_brightness"),
                "automation.set_hud_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"hud_brightness\",\"value\":${percent}}",
                new IntType(new Label("percent", "automation.percent"), 0, 100)));
        // HUD on/off — the DEDICATED HUD power switch (SET_HUD_SWITCH_SET, 1=on/2=off on
        // the setting HAL), distinct from brightness. The daemon maps value 0 → off and any
        // value > 0 → on (see hud_power handling → setHudPower, actuated in the app process).
        addAction(new ApiAction(
                new Label("hudPower", "automation.set_hud_power"),
                "automation.set_hud_power_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"hud_power\",\"value\":${state}}",
                new EnumType(
                        new Label("state", "automation.action"),
                        new Label("0", "automation.off"),
                        new Label("100", "automation.on"))));
        // Infotainment (centre) screen on/off. Uses the proven backlight path
        // (PowerManager.turnBacklightOn/Off → BYDAutoSettingDevice → shell
        // WAKEUP/SLEEP keyevent) — NOT goToSleep, which the car's ACC-on
        // keep-awake logic fights. value=0 → off, value=1 → on.
        addAction(new ApiAction(
                new Label("screenPower", "automation.set_screen_power"),
                "automation.set_screen_power_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"screen_power\",\"value\":${state}}",
                new EnumType(
                        new Label("state", "automation.action"),
                        new Label("0", "automation.off"),
                        new Label("1", "automation.on"))));
        // Lane assist (Lane Departure Warning / Prevention) — BYDAutoADASDevice
        // setLKSMode. Multi-mode (not on/off): Off / LDW / LDP / LDW+LDP. Catalog key
        // "lane_assist" must match the VehicleControlCatalog entity.
        addAction(new VehicleControlAction(
                new Label("lane_assist", "automation.set_lane_assist"), "automation.set_lane_assist_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("0", "automation.off"),
                        new Label("1", "automation.lane_ldw"),
                        new Label("2", "automation.lane_ldp"),
                        new Label("3", "automation.lane_ldw_ldp"))));
        // Media volume on a chosen audio channel (media/navigation/voice/phone/…).
        // Routes through the same allowlisted /api/vehicle/media handler, which maps
        // the channel to an AudioManager stream.
        addAction(new ApiAction(
                new Label("channelVolume", "automation.set_channel_volume"),
                "automation.set_channel_volume_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"volume\",\"channel\":\"${channel}\",\"value\":${level}}",
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("phone", "automation.channel_phone"),
                        new Label("system", "automation.channel_system"),
                        new Label("alarm", "automation.channel_alarm")),
                // Absolute step 0-40 (car's own volume scale), clamped to the chosen
                // channel's real stream max daemon-side.
                new DynamicIntType(new Label("level", "automation.volume_level"), 0, 40)));
        // Brake feel (comfort vs sport/strong) — BYDAutoADASDevice brake foot-sense.
        // Catalog key "brake_feel" must match the VehicleControlCatalog entity.
        addAction(new VehicleControlAction(
                new Label("brake_feel", "automation.set_brake_feel"), "automation.set_brake_feel_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.brake_comfort"),
                        new Label("sport", "automation.brake_sport"),
                        new Label("toggle", "automation.toggle"))));
        // Media transport (play/pause/next/prev) via AudioManager.dispatchMediaKeyEvent
        // — controls whatever media app currently holds the session (radio, BT, etc.).
        addAction(new ApiAction(
                new Label("mediaControl", "automation.media_control"),
                "automation.media_control_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"media_key\",\"key\":\"${key}\"}",
                new EnumType(
                        new Label("key", "automation.media_key"),
                        new Label("play_pause", "automation.media_play_pause"),
                        new Label("play", "automation.media_play"),
                        new Label("pause", "automation.media_pause"),
                        new Label("next", "automation.media_next"),
                        new Label("previous", "automation.media_previous"))));
        // Volume up / down — one relative step on a chosen channel (value carries the
        // direction: +1 up / -1 down; the daemon clamps to the stream max).
        addAction(new ApiAction(
                new Label("volumeStep", "automation.volume_step"),
                "automation.volume_step_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"volume_step\",\"channel\":\"${channel}\",\"value\":${direction}}",
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("phone", "automation.channel_phone")),
                new EnumType(
                        new Label("direction", "automation.direction"),
                        new Label("1", "automation.volume_up"),
                        new Label("-1", "automation.volume_down"))));
        // Ambient (interior atmosphere) light brightness 0-100, per zone (front/rear/both).
        // The daemon converts the percent to the SDK's 0-5 level and writes the chosen IAL area.
        addAction(new ApiAction(
                new Label("ambientBrightness", "automation.set_ambient_brightness"),
                "automation.set_ambient_brightness_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"ambient_brightness\",\"value\":${percent},\"zone\":\"${zone}\"}",
                new DynamicIntType(new Label("percent", "automation.percent"), 0, 100),
                ambientZoneType()));
        // Ambient light on/off, per zone (front/rear/both). "Both" uses the real global main
        // switch (a three-tier chain); a single zone has no dedicated switch on this platform, so
        // "off" zeroes that zone's brightness and "on" restores the level it had BEFORE the off
        // (not full — see setAmbientLightEnabledZoned). The enum value IS what the daemon writes
        // (0=off / 100=on).
        addAction(new ApiAction(
                new Label("ambientPower", "automation.set_ambient_power"),
                "automation.set_ambient_power_description",
                "POST",
                "/api/vehicle/media",
                "{\"target\":\"ambient_power\",\"value\":${state},\"zone\":\"${zone}\"}",
                new EnumType(
                        new Label("state", "automation.action"),
                        new Label("0", "automation.off"),
                        new Label("100", "automation.on")),
                ambientZoneType()));
        // Play an uploaded sound (MP3/WAV/MP4) through the daemon MediaPlayer on a
        // chosen channel — AUDIO ONLY (an MP4's picture is not shown; use Play Video
        // for that). The sound is chosen from the audio library via a live dropdown
        // (AudioType → GET /api/audio/library) — the same upload-once / pick-later
        // model the Screen Deterrent uses for its image. Routes through the allowlisted
        // /api/vehicle/play-audio (the daemon resolves the name to the library path and
        // validates it). display=speakers is baked in so the picture never appears.
        addAction(new ApiAction(
                new Label("playAudio", "automation.play_audio"),
                "automation.play_audio_description",
                "POST",
                "/api/vehicle/play-audio",
                "{\"name\":\"${name}\",\"channel\":\"${channel}\",\"display\":\"speakers\",\"loop\":${loop}}",
                new AudioType(new Label("name", "automation.audio_file")),
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("alarm", "automation.channel_alarm")),
                // Loop the sound until Stop Audio (or a new Play). "false"/"true"
                // substitute as JSON booleans into the body.
                new EnumType(
                        new Label("loop", "automation.audio_loop"),
                        new Label("false", "automation.audio_loop_off"),
                        new Label("true", "automation.audio_loop_on"))));
        // Play an uploaded VIDEO (MP4) with its picture shown on the head-unit screen.
        // Video audio is deliberately fixed to Media: this TextureView player must retain
        // the default media attributes or this DiLink build can play audio without video
        // frames. Play Audio remains available when a non-media channel is required.
        addAction(new ApiAction(
                new Label("playVideo", "automation.play_video"),
                "automation.play_video_description",
                "POST",
                "/api/vehicle/play-audio",
                "{\"name\":\"${name}\",\"display\":\"screen\",\"loop\":${loop}}",
                new AudioType(new Label("name", "automation.video_file")),
                new EnumType(
                        new Label("loop", "automation.audio_loop"),
                        new Label("false", "automation.audio_loop_off"),
                        new Label("true", "automation.audio_loop_on"))));
        // Stop any audio or video started by Play Audio / Play Video.
        addAction(new ApiAction(
                new Label("stopAudio", "automation.stop_audio"),
                "automation.stop_audio_description",
                "POST",
                "/api/vehicle/stop-audio",
                ""));
        // Speak a spoken message aloud via TextToSpeech (e.g. "Charging complete").
        // Free text + a channel. Runs in the app process (TTS can't run in the daemon),
        // routed through the allowlisted /api/vehicle/speak. The ${text} is JSON-escaped
        // by the variable substitution so quotes/newlines in the message stay valid.
        addAction(new ApiAction(
                new Label("speak", "automation.speak"),
                "automation.speak_description",
                "POST",
                "/api/vehicle/speak",
                "{\"text\":\"${text}\",\"channel\":\"${channel}\"}",
                new StringType(new Label("text", "automation.speak_text"), 200),
                new EnumType(
                        new Label("channel", "automation.audio_channel"),
                        new Label("voice", "automation.channel_voice"),
                        new Label("media", "automation.channel_media"),
                        new Label("navigation", "automation.channel_navigation"),
                        new Label("alarm", "automation.channel_alarm"))));
        // Show a brief on-screen TOAST (auto-dismissing pill). Renders as an app-process
        // overlay that floats over the current app WITHOUT stealing focus (safe while
        // driving). User sets the message, how long it shows, where, and a severity tint.
        addAction(new ApiAction(
                new Label("showToast", "automation.show_toast"),
                "automation.show_toast_description",
                "POST",
                "/api/vehicle/message",
                "{\"kind\":\"toast\",\"message\":\"${message}\",\"duration\":\"${duration}\",\"position\":\"${position}\",\"severity\":\"${severity}\"}",
                new StringType(new Label("message", "automation.message_text"), 200),
                new EnumType(
                        new Label("duration", "automation.toast_duration"),
                        new Label("short", "automation.toast_duration_short"),
                        new Label("long", "automation.toast_duration_long")),
                new EnumType(
                        new Label("position", "automation.toast_position"),
                        new Label("bottom", "automation.toast_position_bottom"),
                        new Label("center", "automation.toast_position_center"),
                        new Label("top", "automation.toast_position_top")),
                new EnumType(
                        new Label("severity", "automation.message_severity"),
                        new Label("info", "automation.severity_info"),
                        new Label("warning", "automation.severity_warning"),
                        new Label("alert", "automation.severity_alert"))));
        // Show an on-screen DIALOG (title + body + OK button). Same non-focus-stealing
        // overlay; holds until the user taps OK (or the scrim), or auto-closes after the
        // optional timeout so a driver who ignores it isn't left with a stuck card.
        addAction(new ApiAction(
                new Label("showDialog", "automation.show_dialog"),
                "automation.show_dialog_description",
                "POST",
                "/api/vehicle/message",
                "{\"kind\":\"dialog\",\"title\":\"${title}\",\"message\":\"${message}\",\"button\":\"${button}\",\"severity\":\"${severity}\",\"timeoutSec\":${timeoutSec}}",
                new StringType(new Label("title", "automation.dialog_title"), 80),
                new StringType(new Label("message", "automation.message_text"), 300),
                new StringType(new Label("button", "automation.dialog_button"), 30),
                new EnumType(
                        new Label("severity", "automation.message_severity"),
                        new Label("info", "automation.severity_info"),
                        new Label("warning", "automation.severity_warning"),
                        new Label("alert", "automation.severity_alert")),
                // 0 = stay until OK; otherwise auto-dismiss after N seconds (max 5 min).
                new IntType(new Label("timeoutSec", "automation.dialog_timeout"), 0, 300)));
        // Drive / energy modes — same catalog entities the keymap and MQTT use.
        // The Label id must match the VehicleControlCatalog key so
        // VehicleControlAction.trigger resolves it.
        addAction(new VehicleControlAction(
                new Label("drive_mode", "automation.set_drive_mode"), "automation.set_drive_mode_description",
                // Stable config axis: NORMAL=1, ECO=2, SPORT=3, SNOW/RAIN=4.
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("normal", "automation.mode_normal"),
                        new Label("eco", "automation.mode_eco"),
                        new Label("sport", "automation.mode_sport"),
                        new Label("snow", "automation.mode_snow"))));
        // Only field-validated energy writes are offered. Conditions still decode the complete SDK
        // enum so modes already reported by the vehicle remain observable.
        addAction(new VehicleControlAction(
                new Label("powertrain_mode", "automation.set_powertrain_mode"), "automation.set_powertrain_mode_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("ev", "automation.mode_ev"),
                        new Label("hev", "automation.mode_hev"))));
        // One-tap "switch to HEV". Does NOT actually hold the pack — on a SeaLion 6 DM-i
        // the ICE starts and recharges it. The separate battery_hold action
        // below is the real SOC-hold pair.
        addAction(new VehicleControlAction(
                new Label("hold_battery", "automation.hold_battery"), "automation.hold_battery_description",
                new EnumType(new Label("payload", "automation.action"),
                        new Label("on", "automation.on"))));
        // The REAL SOC hold, as a pair: the percent says WHERE to hold, the switch says whether
        // to hold. Unlike hold_battery (an alias for energy-mode HEV, which starts the ICE and
        // recharges the pack) this is the setSOCTarget + setSocSaveSwitch lever the OEM drives for
        // its DM-i city / long-distance presets. Both have an off value, so a rule can undo it.
        // One-tap battery hold — writes BOTH legs of the SOC pair (target + switch) the way the
        // OEM does, so a preset needs no second action and no arithmetic in the rule:
        //   at_current = hold min(SOC,50) → the "Highway" preset
        //   at_floor   = deplete to the trim floor → the "City" preset
        addAction(new VehicleControlAction(
                new Label("battery_hold", "automation.battery_hold"),
                "automation.battery_hold_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("at_current", "automation.battery_hold_at_current"),
                        new Label("at_target", "automation.battery_hold_at_target"),
                        new Label("at_floor", "automation.battery_hold_at_floor"),
                        new Label("off", "automation.off"))));
        addAction(new VehicleControlAction(
                new Label("target_soc", "automation.set_target_soc"),
                "automation.set_target_soc_description",
                new IntType(new Label("payload", "automation.percent"),
                        com.overdrive.app.byd.BydDataCollector.SOC_TARGET_MIN,
                        com.overdrive.app.byd.BydDataCollector.SOC_TARGET_MAX)));
        addAction(new VehicleControlAction(
                new Label("charge_cap_percent", "automation.soc_hold_percent"),
                "automation.soc_hold_percent_description",
                new IntType(new Label("payload", "automation.percent"), 50, 100)));
        addAction(new VehicleControlAction(
                new Label("charge_cap_enabled", "automation.soc_hold_enabled"),
                "automation.soc_hold_enabled_description",
                new EnumType(new Label("payload", "automation.action"),
                        new Label("1", "automation.on"),
                        new Label("0", "automation.off"))));
        addAction(new ApiAction(
                new Label("acChargeCurrentLimit", "automation.ac_charge_current_limit"),
                "automation.ac_charge_current_limit_description",
                "POST",
                "/api/vehicle/ac-charge-current-limit",
                "{\"state\":${state}}",
                new EnumType(new Label("state", "automation.ac_charge_current"),
                        new Label("1", "automation.ac_charge_current_6a"),
                        new Label("2", "automation.ac_charge_current_8a"),
                        new Label("3", "automation.ac_charge_current_10a"),
                        new Label("4", "automation.ac_charge_current_16a"),
                        new Label("5", "automation.ac_charge_current_max"))));
        // Smart-charge controls have no local SDK primitive and therefore deliberately use the
        // capability-gated cloud endpoints. The master toggle remains separate from a schedule
        // save so a rule can pause/resume the existing OEM schedule without overwriting it.
        addAction(new ApiAction(
                new Label("smartCharging", "automation.smart_charging"),
                "automation.smart_charging_description",
                "POST",
                "/api/vehicle/charging-schedule",
                "{\"enabled\":${enabled}}",
                new EnumType(new Label("enabled", "automation.action"),
                        new Label("true", "automation.on"),
                        new Label("false", "automation.off"))));
        addAction(new ApiAction(
                new Label("chargingSchedule", "automation.charging_schedule"),
                "automation.charging_schedule_description",
                "POST",
                "/api/vehicle/charging-schedule",
                "{\"startChargeTime\":\"${startChargeTime}\",\"endChargeTime\":\"${endChargeTime}\","
                        + "\"chargeWay\":\"${chargeWay}\",\"enabled\":${enabled}}",
                new StringType(new Label("startChargeTime", "automation.charge_start_time"), 5),
                new StringType(new Label("endChargeTime", "automation.charge_end_time"), 5),
                new StringType(new Label("chargeWay", "automation.charge_schedule_days"), 13),
                new EnumType(new Label("enabled", "automation.action"),
                        new Label("true", "automation.on"),
                        new Label("false", "automation.off"))));
        addAction(new ApiAction(
                new Label("startChargingNow", "automation.start_charging"),
                "automation.start_charging_description",
                "POST",
                "/api/vehicle/start-charging",
                ""));
        // regen / steering / brake feel expose a "toggle" option that CYCLES to the
        // next mode (the daemon tracks the last-commanded value — no HAL readback
        // exists), so an automation can flip the mode without knowing the current one.
        addAction(new VehicleControlAction(
                new Label("regen_level", "automation.set_regen"), "automation.set_regen_description",
                new EnumType(new Label("payload", "automation.level"),
                        new Label("standard", "automation.regen_standard"),
                        new Label("high", "automation.regen_high"),
                        new Label("toggle", "automation.toggle"))));
        addAction(new VehicleControlAction(
                new Label("steering_mode", "automation.set_steering"), "automation.set_steering_description",
                new EnumType(new Label("payload", "automation.mode"),
                        new Label("comfort", "automation.steering_comfort"),
                        new Label("sport", "automation.steering_sport"),
                        new Label("toggle", "automation.toggle"))));
        addAction(new ApiAction(
                new Label("surveillance", "automation.set_surveillance"),
                "automation.set_surveillance_description",
                "POST",
                "/api/surveillance/${action}",
                "",
                new EnumType(
                        new Label("action", "automation.action"),
                        new Label("disable", "automation.off"),
                        new Label("enable", "automation.on"))));
        addAction(new ApiAction(
                new Label("operatingMode", "automation.set_operating_mode"),
                "automation.set_operating_mode_description",
                "POST",
                "/api/surveillance/config",
                "{\"operatingMode\":\"${mode}\",\"applyCurrentAccState\":true}",
                new EnumType(
                        new Label("mode", "automation.mode"),
                        new Label("onOnly", "automation.operating_mode_on_only"),
                        new Label("onAndOff", "automation.operating_mode_on_and_off"))));
        addAction(new ApiAction(
                new Label("recording", "automation.set_recording"),
                "automation.set_recording_description",
                "POST",
                "/api/recording/mode",
                "{\"mode\":\"${mode}\"}",
                new EnumType(
                        new Label("mode", "automation.mode"),
                        new Label("NONE", "automation.none"),
                        new Label("CONTINUOUS", "automation.continuous"),
                        new Label("DRIVE_MODE", "automation.drive_mode"),
                        new Label("PROXIMITY_GUARD", "automation.proximity_guard"))));
        // Save an instant replay from the encoded camera ring. Manual-only
        // recording path: a dedicated in-process action (NOT the /api allowlist)
        // so replay stays off the automation HTTP surface, matching key mapping.
        // Fires only while the camera pipeline is already running (an ACC-on
        // recording mode active) — it never keeps the pipeline alive on its own.
        addAction(new ManualClipAction(
                new Label("manualClip", "automation.save_replay"),
                "automation.save_replay_description"));
        // SafeKeep (own-storage backup): sync now / pause / resume / upload latest event.
        // In-process + detached like manualClip; refused (logged) while SafeKeep is off.
        addAction(new SafeKeepAction(
                new Label("safeKeep", "automation.safekeep"),
                "automation.safekeep_description"));
        // Open app — launch a user-selected installed app. The package is chosen
        // from a live picker (AppType → dropdown fed by GET /api/apps/list). Routes
        // through the allowlisted POST /api/apps/launch (shared with key mapping).
        addAction(new ApiAction(
                new Label("openApp", "automation.open_app"),
                "automation.open_app_description",
                "POST",
                "/api/apps/launch",
                "{\"package\":\"${package}\"}",
                new AppType(new Label("package", "automation.app"))));
        // Open app in split-screen — docks the chosen app to one half of the
        // screen (windowingMode 3) so it shares the display. Same picker + launch
        // endpoint as openApp, with the split flag set.
        addAction(new ApiAction(
                new Label("openAppSplit", "automation.open_app_split"),
                "automation.open_app_split_description",
                "POST",
                "/api/apps/launch",
                "{\"package\":\"${package}\",\"split\":true}",
                new AppType(new Label("package", "automation.app"))));
        // Deterministic split pair. Kept separate from openAppSplit so existing
        // saved rules retain their original "current foreground app + selected app"
        // behaviour and do not gain a newly-required variable.
        addAction(new ApiAction(
                new Label("openAppsSplit", "automation.open_apps_split"),
                "automation.open_apps_split_description",
                "POST",
                "/api/apps/launch",
                "{\"package\":\"${primaryPackage}\",\"secondaryPackage\":\"${secondaryPackage}\",\"split\":true}",
                new AppType(new Label("primaryPackage", "automation.primary_app")),
                new AppType(new Label("secondaryPackage", "automation.secondary_app"))) {
            @Override
            public com.overdrive.app.automation.AutomationAction fromJson(JSONObject input) {
                com.overdrive.app.automation.AutomationAction parsed = super.fromJson(input);
                if (parsed == null) return null;
                java.util.Map<String, Object> values = parsed.getVariables();
                return java.util.Objects.equals(
                        values.get("primaryPackage"), values.get("secondaryPackage"))
                        ? null : parsed;
            }
        });
        // ── System UI navigation + screenshot (daemon shell as UID 2000) ─────────
        // Home / Back / Recents use input keyevents. Screenshots route through the daemon's
        // SurfaceControl capture so the virtual driver-cluster layer stack is included.
        addAction(new ApiAction(
                new Label("uiNav", "automation.ui_nav"),
                "automation.ui_nav_description",
                "POST",
                "/api/vehicle/system",
                "{\"target\":\"${target}\"}",
                new EnumType(
                        new Label("target", "automation.action"),
                        new Label("home", "automation.nav_home"),
                        new Label("back", "automation.nav_back"),
                        new Label("recents", "automation.nav_recents"))));
        addAction(new ApiAction(
                new Label("screenshot", "automation.screenshot"),
                "automation.screenshot_description",
                "POST",
                "/api/vehicle/system",
                "{\"target\":\"screenshot\",\"display\":${display}}",
                new EnumType(
                        new Label("display", "automation.display"),
                        new Label("0", "automation.display_head_unit"),
                        new Label("1", "automation.display_cluster"))));
        // Dedicated destructive action: the exact endpoint is allowlisted, but the handler
        // still requires live Park and persists a five-minute boot-loop/cooldown guard.
        addAction(new ApiAction(
                new Label("iviReboot", "automation.ivi_reboot"),
                "automation.ivi_reboot_description",
                "POST",
                "/api/system/ivi-reboot",
                "{\"confirm\":\"REBOOT\"}"));
        // Centre-screen orientation through the same catalog entity used by key mapping.
        // Toggle alternates horizontal/vertical using the catalog's readback-less select cache.
        addAction(new VehicleControlAction(
                new Label("infotainment_rotation", "automation.rotate_infotainment"),
                "automation.rotate_infotainment_description",
                new EnumType(
                        new Label("payload", "automation.orientation"),
                        new Label("horizontal", "automation.orientation_horizontal"),
                        new Label("vertical", "automation.orientation_vertical"),
                        new Label("toggle", "automation.toggle"))));
        // Switch the view inside the OEM native panorama application. These
        // AUTO_VIDEO_BUTTON choices are distinct from OverDrive's camera overlay.
        addAction(new VehicleControlAction(
                new Label("native_camera_view", "automation.native_camera_view"),
                "automation.native_camera_view_description",
                new EnumType(
                        new Label("payload", "automation.camera"),
                        new Label("front", "automation.camera_front"),
                        new Label("front_wide", "automation.camera_front_wide"),
                        new Label("rear", "automation.camera_rear"),
                        new Label("rear_wide", "automation.camera_rear_wide"),
                        new Label("left", "automation.camera_left"),
                        new Label("right", "automation.camera_right"),
                        new Label("left_right", "automation.camera_left_right"))));
        // Move an app onto a chosen display (head-unit ↔ cluster). App from the live
        // picker; the daemon resolves its launcher component and `am start --display`s it.
        // For the CLUSTER (display 1) the daemon opens the OEM projection first (the
        // fission display doesn't exist until then) and holds it open; use "Stop cluster
        // cast" below (or move back to the head unit) to restore the gauges.
        addAction(new ApiAction(
                new Label("moveAppToDisplay", "automation.move_app_display"),
                "automation.move_app_display_description",
                "POST",
                "/api/vehicle/system",
                "{\"target\":\"move_display\",\"package\":\"${package}\",\"display\":${display}}",
                new AppType(new Label("package", "automation.app")),
                new EnumType(
                        new Label("display", "automation.display"),
                        new Label("0", "automation.display_head_unit"),
                        new Label("1", "automation.display_cluster"))));
        // Stop casting an app to the driver cluster — releases the OEM projection so the
        // gauges are restored (no-op if nothing is cast). Pairs with the cluster branch
        // of "Move app to display".
        addAction(new ApiAction(
                new Label("stopClusterCast", "automation.stop_cluster_cast"),
                "automation.stop_cluster_cast_description",
                "POST",
                "/api/vehicle/system",
                "{\"target\":\"cluster_cast_stop\"}"));
        // Intent-based camera actions. The normal on-demand feed uses the saved
        // camera-view position/size and supports a bounded display duration.
        addAction(new ApiAction(
                new Label("showCameraFeed", "automation.show_camera_feed"),
                "automation.show_camera_feed_description",
                "POST",
                "/api/camview/show?cam=${camera}&target=${target}&autoHide=${duration}",
                "",
                new EnumType(
                        new Label("camera", "automation.camera_feed"),
                        new Label("all", "automation.camera_all"),
                        new Label("front", "automation.camera_front"),
                        new Label("rear", "automation.camera_rear"),
                        new Label("left", "automation.camera_left"),
                        new Label("right", "automation.camera_right")),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster")),
                new EnumType(
                        new Label("duration", "automation.camera_duration"),
                        new Label("5", "automation.duration_5_seconds"),
                        new Label("10", "automation.duration_10_seconds"),
                        new Label("30", "automation.duration_30_seconds"),
                        new Label("0", "automation.duration_until_hidden"))));
        // Resize the ordinary on-demand camera view without changing its selected
        // camera, display target, or saved corner. If it is currently visible on the
        // chosen display, the native layer is resized live; otherwise this is used the
        // next time a camera feed is shown there.
        addAction(new ApiAction(
                new Label("setCameraViewSize", "automation.set_camera_view_size"),
                "automation.set_camera_view_size_description",
                "POST",
                "/api/camview/geometry/size/${size}?target=${target}",
                "",
                new IntType(new Label("size", "automation.camera_size"), 15, 90),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster"))));
        // The indicator-driven Blind Spot feature already shows and hides with the
        // indicators through blindSpotEnable below. This action adjusts the same
        // persistent RoadSense overlay size without changing its target or per-side
        // positions, so an automation can choose a size for a driving context.
        addAction(new ApiAction(
                new Label("setBlindSpotOverlaySize", "automation.set_bs_overlay_size"),
                "automation.set_bs_overlay_size_description",
                "POST",
                "/api/bs/geometry/size/${size}?target=${target}",
                "",
                new IntType(new Label("size", "automation.camera_size"), 15, 90),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster"))));
        // Kept for existing manual composite-camera automations. It is intentionally
        // not the ordinary Blind Spot control: the latter is indicator-driven and is
        // enabled/disabled with blindSpotEnable below.
        addAction(new ApiAction(
                new Label("showBlindSpotCameraFeed", "automation.show_blind_spot_camera_feed"),
                "automation.show_blind_spot_camera_feed_description",
                "POST",
                "/api/camview/show?cam=side_rear_${side}&target=${target}&autoHide=${duration}",
                "",
                new EnumType(
                        new Label("side", "automation.camera_side"),
                        new Label("left", "automation.camera_left"),
                        new Label("right", "automation.camera_right")),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster")),
                new EnumType(
                        new Label("duration", "automation.camera_duration"),
                        new Label("5", "automation.duration_5_seconds"),
                        new Label("10", "automation.duration_10_seconds"),
                        new Label("30", "automation.duration_30_seconds"),
                        new Label("0", "automation.duration_until_hidden"))));
        // Show camera view — a native camera feed (front/rear/left/right/all-4) on
        // the SAME SurfaceControl lane the blind-spot feature uses, with position +
        // target. Routes through the allowlisted /api/camview/show; the query params
        // (${cam}/${target}/${position}) are substituted by ApiAction. Blind-spot
        // has priority: while BS is armed and showing (turn signal), it owns the lane.
        addAction(new ApiAction(
                new Label("showCameraView", "automation.show_camera_view"),
                "automation.show_camera_view_description",
                "POST",
                "/api/camview/show?cam=${cam}&target=${target}&preset=${size}/${position}",
                "",
                new EnumType(
                        new Label("cam", "automation.camera"),
                        new Label("all", "automation.camera_all"),
                        new Label("front", "automation.camera_front"),
                        new Label("rear", "automation.camera_rear"),
                        new Label("left", "automation.camera_left"),
                        new Label("right", "automation.camera_right"),
                        // Composite views: the same rear+side render the blind-spot card
                        // uses, on demand and with no turn signal needed. They follow the
                        // shared blind-spot settings (cameras shown, fisheye, rotation),
                        // so "Side only"/"Rear only" there reshapes these too.
                        new Label("side_rear_left", "automation.camera_side_rear_left"),
                        new Label("side_rear_right", "automation.camera_side_rear_right")),
                new EnumType(
                        new Label("target", "automation.display"),
                        new Label("head_unit", "automation.display_headunit"),
                        new Label("cluster", "automation.display_cluster")),
                // Size and position are now SEPARATE pickers (the daemon accepts
                // preset=sizePct/corner). Size is the on-screen width %; the two combine
                // into ?preset=${size}/${position}. Fullscreen ignores the corner.
                new EnumType(
                        new Label("size", "automation.camera_size"),
                        // Width as a % of the panel. Small is a compact floating card
                        // (~25% ≈ 480px on a 1920 panel); the daemon floor is 15%.
                        new Label("25", "automation.size_small"),
                        new Label("45", "automation.size_medium"),
                        new Label("70", "automation.size_large"),
                        new Label("90", "automation.size_full")),
                new EnumType(
                        new Label("position", "automation.position"),
                        new Label("center", "automation.pos_center"),
                        new Label("tr", "automation.pos_tr"),
                        new Label("tl", "automation.pos_tl"),
                        new Label("br", "automation.pos_br"),
                        new Label("bl", "automation.pos_bl"))));
        // Hide the camera view.
        addAction(new ApiAction(
                new Label("hideCameraView", "automation.hide_camera_view"),
                "automation.hide_camera_view_description",
                "POST",
                "/api/camview/hide",
                ""));
        // ── Blind-spot card tweaks ───────────────────────────────────────────
        // The side/rear view knobs from the Road Sense settings page, reachable from a
        // rule so the card can adapt itself (straighten the fisheye on the motorway,
        // swap to the rear camera when towing, rotate for a reversing manoeuvre).
        // All three persist AND apply live to a showing card; /api/bs/tweak validates
        // and rejects the whole call on a bad value.
        //
        // Fisheye: how hard to straighten the lens barrel on the SINGLE-camera views.
        // Only shapes side/rear ("Cameras shown" below) — the merged rear+side view is
        // dewarped by the projection itself and ignores this.
        addAction(new ApiAction(
                new Label("blindSpotFisheye", "automation.bs_fisheye"),
                "automation.bs_fisheye_description",
                "POST",
                "/api/bs/tweak?fisheye=${level}",
                "",
                new IntType(new Label("level", "automation.bs_fisheye_level"), 0, 100)));
        // Step the fisheye up/down by a fixed amount — for a rule (or key) that nudges
        // it repeatedly rather than jumping to one absolute level. Clamped to 0..100.
        addAction(new ApiAction(
                new Label("blindSpotFisheyeStep", "automation.bs_fisheye_step"),
                "automation.bs_fisheye_step_description",
                "POST",
                "/api/bs/tweak?fisheye=${direction}",
                "",
                new EnumType(
                        new Label("direction", "automation.action"),
                        new Label("up", "automation.bs_fisheye_more"),
                        new Label("down", "automation.bs_fisheye_less"))));
        // Which camera(s) fill the card: the merged rear+side panorama, or one camera
        // on its own at full field of view.
        addAction(new ApiAction(
                new Label("blindSpotCameras", "automation.bs_cameras"),
                "automation.bs_cameras_description",
                "POST",
                "/api/bs/tweak?cameras=${cameras}",
                "",
                new EnumType(
                        new Label("cameras", "automation.bs_cameras_label"),
                        new Label("both", "automation.bs_cameras_both"),
                        new Label("side", "automation.bs_cameras_side"),
                        new Label("rear", "automation.bs_cameras_rear"))));
        // On-screen rotation of the card, per side. The left-turn and right-turn
        // cameras are mirror-imaged, so an angle that reads upright on one reads wrong
        // on the other — hence the side picker. 40° and 310° are not plain angles: each
        // applies a whole ready-made blind-spot framing (crop, mirror, zoom) so an
        // automation can select the view in one step. "Auto" tracks direction of travel
        // (flips 180° in reverse) and stays quarter-turn only. Single-camera views only.
        addAction(new ApiAction(
                new Label("blindSpotRotation", "automation.bs_rotation"),
                "automation.bs_rotation_description",
                "POST",
                "/api/bs/tweak?rotation=${rotation}&side=${side}",
                "",
                new EnumType(
                        new Label("rotation", "automation.bs_rotation_label"),
                        new Label("0", "automation.bs_rotation_0"),
                        new Label("40", "automation.bs_rotation_40"),
                        new Label("90", "automation.bs_rotation_90"),
                        new Label("180", "automation.bs_rotation_180"),
                        new Label("270", "automation.bs_rotation_270"),
                        new Label("310", "automation.bs_rotation_310"),
                        new Label("auto", "automation.bs_rotation_auto")),
                new EnumType(
                        new Label("side", "automation.bs_side"),
                        new Label("both", "automation.bs_side_both"),
                        new Label("left", "automation.bs_side_left"),
                        new Label("right", "automation.bs_side_right"))));
        // Turn the blind-spot feature itself on or off. Writes blindspot.enabled and
        // arms/disarms the native lane, reconciling the camera profile either way —
        // the same work the settings toggle does. Id is blindSpotEnable, NOT blindSpot:
        // that id already belongs to the OEM ADAS blind-spot warning condition, and the
        // two are unrelated features.
        addAction(new ApiAction(
                new Label("blindSpotEnable", "automation.bs_enable"),
                "automation.bs_enable_description",
                "POST",
                "/api/bs/tweak?enabled=${enabled}",
                "",
                new EnumType(
                        new Label("enabled", "automation.action"),
                        new Label("true", "automation.on"),
                        new Label("false", "automation.off"))));
        // Dismiss a card that is showing right now, WITHOUT disabling the feature —
        // the next turn signal shows it again.
        addAction(new ApiAction(
                new Label("blindSpotDismiss", "automation.bs_dismiss"),
                "automation.bs_dismiss_description",
                "POST",
                "/api/bs/hide",
                ""));
        // ── Control-flow steps ───────────────────────────────────────────
        // Pause N milliseconds before the next action. Blocks only this automation's
        // worker (sequential action chain), never telemetry/other automations.
        addAction(new PauseAction(
                new Label("pause", "automation.pause"), "automation.pause_description"));
        // Wait until a scalar signal satisfies a comparison (bounded by a timeout).
        // Same worker-thread-only blocking as pause; polls the live automation state.
        addAction(new WaitUntilAction(
                new Label("waitUntil", "automation.wait_until"), "automation.wait_until_description"));
        // Wait until an ON/OFF signal reaches a state — the on/off counterpart of the
        // scalar Wait Until above. Covers indicators (left/right/either), lights, AC,
        // etc., so a chain can pause on "wait until indicators = off".
        addAction(new WaitUntilStateAction(
                new Label("waitUntilState", "automation.wait_until_state"), "automation.wait_until_state_description"));
        // Set a named variable / flag — a marker the user sets and reads to coordinate
        // automations (a mutex that stops re-triggering while running, a mode flag other
        // automations key off). Pairs with the "variable" trigger/condition.
        addAction(new SetVariableAction(
                new Label("setVariable", "automation.set_variable"), "automation.set_variable_description"));
        // Add/subtract a number to a variable (counters + simple arithmetic).
        addAction(new IncrementVariableAction(
                new Label("incrementVariable", "automation.increment_variable"),
                "automation.increment_variable_description"));
        // Store an arithmetic EXPRESSION in a variable — the general form of the above,
        // with live ${signal:…}/${var:…} operands (e.g. a tap coordinate derived from SOC).
        addAction(new ComputeVariableAction(
                new Label("computeVariable", "automation.compute_variable"),
                "automation.compute_variable_description"));
        // Save a snapshot of a value that is useful to restore or compare later, such
        // as media volume before ducking it or SOC at the start of a drive phase.
        addAction(new CaptureVariableAction(
                new Label("captureVariable", "automation.capture_variable"),
                "automation.capture_variable_description"));
        // Loop — run nested actions N times, or while/until a signal condition holds.
        addAction(new LoopAction(
                new Label("loop", "automation.loop"), "automation.loop_description"));
        // Inline if/else — run nested "then" actions when a condition holds, else the
        // "else" actions. Mid-sequence branch, distinct from the whole-automation else.
        addAction(new IfAction(
                new Label("if", "automation.if"), "automation.if_description"));
        // Run a reusable action group by id (call-by-reference; editing the group
        // updates every caller). Cycle-guarded.
        addAction(new ActionGroupAction(
                new Label("actionGroup", "automation.action_group_run"),
                "automation.action_group_run_description"));
        // Enable / disable / toggle ANOTHER automation (arm/disarm routines from a rule).
        addAction(new AutomationControlAction(
                new Label("automationControl", "automation.control_automation"),
                "automation.control_automation_description"));
        // Toggle a device radio (WiFi / Bluetooth / mobile-data). A WiFi-off also sets
        // the keep-alive suppression flag so the watchdog doesn't auto-re-enable it.
        addAction(new RadioAction(
                new Label("radio", "automation.radio"),
                "automation.radio_description"));
        // Publish an MQTT message (notify Home Assistant / any broker). In-process fan-out
        // to every active connection; /api/mqtt is deliberately off the API allowlist.
        addAction(new MqttPublishAction(
                new Label("mqttPublish", "automation.mqtt_publish"),
                "automation.mqtt_publish_description"));

        // Shell command — the free-text StringType variable is defined inside
        // ShellAction. Autonomous exec, so it self-gates on the dedicated
        // automation.allowShell flag (toggle on the Automations page) at fire
        // time (off → logged no-op). Registered last so curated actions lead.
        addAction(new ShellAction(
                new Label("shell", "automation.run_shell"), "automation.run_shell_description"));

    }

    /**
     * The shared front/rear/both zone selector for the ambient-light actions. "both" is the
     * default (first option) so a user who ignores the field gets the whole cabin — matching the
     * pre-zoning behaviour. The stored value is the zone token the daemon maps to a BYD IAL area
     * (front=1 / rear=2 / both=3); a fresh EnumType per action keeps each schema independent.
     */
    private static EnumType ambientZoneType() {
        return new EnumType(
                new Label("zone", "automation.area"),
                new Label("both", "automation.area_all"),
                new Label("front", "automation.ambient_zone_front"),
                new Label("rear", "automation.ambient_zone_rear"));
    }

    /**
     * Add an action to the map
     * Stored as a map to prevent duplicates
     *
     * @param action The Action to store
     */
    private void addAction(Action action) {
        actions.put(action.getLabel().getId(), action);
    }

    /**
     * Get a stored action
     *
     * @param key The id of the action
     * @return The requested action
     */
    public Action getAction(String key) {
        return actions.get(key);
    }

    /**
     * A JSON representation of the primary actions schema ("Then").
     * All automations require at least 1 action so required is 1.
     *
     * @return A JSON representation of the Actions schema
     */
    public JSONObject toJson() {
        return sectionJson("actions", "Then", "automation.actions_description", 1);
    }

    /**
     * A JSON representation of the OPTIONAL else-actions schema ("Else"). Same
     * option catalog as the primary actions, but required 0 — an automation with
     * no else branch is valid, which is why every pre-existing automation keeps
     * working. Rendered by the same schema-driven form code as the primary section
     * (it is an options list with a distinct id), so no bespoke UI is needed.
     *
     * @return A JSON representation of the else-actions schema
     */
    public JSONObject elseToJson() {
        return sectionJson("elseActions", "Else", "automation.else_actions_description", 0);
    }

    /**
     * Build one actions-schema section with the given id/label/description/required,
     * sharing the single action catalog. Extracted so the primary and else sections
     * cannot drift in their option set.
     */
    private JSONObject sectionJson(String id, String fallbackLabel, String descriptionKey, int required) {
        JSONObject section = new Label(id, fallbackLabel).toJson();

        try {
            section.put("description", Messages.get(descriptionKey));
            JSONArray actionsList = new JSONArray();
            boolean includeHybridOnly =
                    com.overdrive.app.automation.AutomationCategories
                            .supportsHybridOnlyItemsOnCurrentVehicle();
            for (Action action : this.actions.values()) {
                if (!includeHybridOnly
                        && com.overdrive.app.automation.AutomationCategories.isHybridOnly(
                                action.getLabel().getId())) {
                    continue;
                }
                JSONObject opt = action.toJson();
                // Cosmetic grouping tag (see AutomationCategories). Never stored/resolved.
                opt.put("category", com.overdrive.app.automation.AutomationCategories.forId(
                        action.getLabel().getId()));
                actionsList.put(opt);
            }
            section.put("options", actionsList);
            section.put("required", required);
            // The engine's control-flow nesting cap, so the web editor stops offering
            // If/Loop at this depth instead of letting a user build a tree that would be
            // rejected on save. Read from the schema → client + engine can never drift.
            section.put("maxActionDepth", com.overdrive.app.automation.Automation.MAX_ACTION_DEPTH);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return section;
    }
}
