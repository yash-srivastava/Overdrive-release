package com.overdrive.app.voice.daemon;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.byd.routing.VehicleCommandRouter.Outcome;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;
import com.overdrive.app.genai.GenAiRoutineLearner;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.recording.ManualClipService;
import com.overdrive.app.recording.ManualClipWindow;
import com.overdrive.app.server.StepUpGate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /api/voice/intent}: one catalog intent in, one routed result out. Every vehicle write
 * goes through {@link VehicleCommandRouter}; nothing here throws.
 */
public final class VoiceIntentExecutor {

    private static final DaemonLogger logger = DaemonLogger.getInstance("VoiceIntent");

    public static final String DONE = "done";
    public static final String NEEDS_CONFIRMATION = "needs_confirmation";
    public static final String BLOCKED_MOVING = "blocked_moving";
    public static final String REFUSED = "refused";
    public static final String UNSUPPORTED = "unsupported";
    public static final String FAILED = "failed";
    public static final String DUPLICATE = "duplicate";

    private static final int DEDUP_MAX = 64;
    private static final long DEDUP_TTL_MS = 5 * 60_000L;
    private static final double DEFAULT_ON_CELSIUS = 22;
    private static final int DEFAULT_REMOTE_MINUTES = 20;
    private static final int FAN_MIN = 1;
    private static final int FAN_MAX = 7;
    private static final int CLIP_DEFAULT_BEFORE_S = 30;
    static final String STEP_UP_REFUSAL =
            "For safety, use Vehicle Control for that. It needs Face ID or fingerprint.";
    static final String STEP_UP_REFUSAL_CAR =
            "For safety, use Vehicle Control on the car screen for that.";
    // Seats a person may name that the car has no voice control for (the matcher reports "rear").
    private static final Set<String> UNSUPPORTED_SEATS = new HashSet<>(Arrays.asList(
            "rear", "back", "left", "right", "all"));

    private static final Map<String, Seen> SEEN = new LinkedHashMap<>();

    /** Intents beyond the local catalog's: media, navigation, phone and the rest of the car (I21). */
    static final Set<String> EXTENDED = new HashSet<>(Arrays.asList(
            "media.pause", "media.play", "media.next", "media.previous", "media.search",
            "volume.step", "volume.set", "nav.go", "phone.call",
            "vehicle.lock", "vehicle.unlock", "vehicle.power_off", "trunk.open", "trunk.close",
            "windows.open_all", "window.set", "windows.rain_close", "sunroof", "child_lock",
            "mirrors.fold", "lights.headlights", "lights.drl", "lights.hazard", "lights.reading",
            "ambient.colour", "ambient.brightness", "climate.purify", "climate.fan_only",
            "climate.sync", "battery.heat", "display.hud", "display.hud_brightness",
            "display.cluster_brightness", "charging.start", "charging.limit",
            "charging.limit_enable", "charging.current", "charging.smart", "battery.hold",
            "battery.hold_target", "drive.mode", "drive.power", "drive.regen", "drive.steering",
            "drive.brake", "adas.on"));
    /** Always parked plus a spoken yes; window.set and child_lock only for the risky direction. */
    static final Set<String> RISKY = new HashSet<>(Arrays.asList(
            "vehicle.unlock", "vehicle.power_off", "trunk.open", "trunk.close",
            "windows.open_all", "adas.on"));
    /** A window opened further than this is "opening it fully" and needs the confirmation. */
    static final int WINDOW_CONFIRM_ABOVE_PERCENT = 50;

    private static final class Seen {
        final long atMs;
        JSONObject response;

        Seen(long atMs) {
            this.atMs = atMs;
        }
    }

    /** A step's routed outcome, collected into {@code applied}. */
    private static final class Plan {
        final JSONArray applied = new JSONArray();
        String code = DONE;
        String message = "";
        /** The slot a refused call left out, so the hands-free model fills it instead of giving up. */
        String missing;

        /** Runs one router command; returns true on SUCCESS. */
        boolean run(VehicleCommand cmd) {
            if (cmd.wakeVehicleIfOff() && needsStepUp(StepUpGate.POWER_ON)) {
                note(cmd.name(), REFUSED);
                return false;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            record(cmd.name(), r);
            return r != null && r.outcome == Outcome.SUCCESS;
        }

        void record(String command, CommandResult r) {
            JSONObject step = new JSONObject();
            try {
                step.put("command", command);
                step.put("outcome", r == null ? "failed" : r.outcome.name().toLowerCase(Locale.ROOT));
                step.put("path", r == null ? "none" : r.pathString());
                if (r != null && !r.displayMessage.isEmpty()) step.put("message", r.displayMessage);
            } catch (Exception ignored) {
            }
            applied.put(step);
        }

        void note(String command, String outcome) {
            JSONObject step = new JSONObject();
            try {
                step.put("command", command);
                step.put("outcome", outcome);
            } catch (Exception ignored) {
            }
            applied.put(step);
        }

        /** Code from the last recorded router step; done when it succeeded. */
        void finishFrom(CommandResult r, String doneMessage) {
            code = codeFor(r);
            message = DONE.equals(code) ? doneMessage : messageFor(r);
        }
    }

    private VoiceIntentExecutor() {}

    public static JSONObject execute(JSONObject request) {
        String utteranceId = request == null ? "" : request.optString("utteranceId", "").trim();
        String intent = request == null ? "" : request.optString("intent", "").trim();
        if (!utteranceId.isEmpty()) {
            JSONObject earlier = claim(utteranceId);
            if (earlier != null) return earlier;
        }
        JSONObject response;
        try {
            response = run(intent, request == null ? null : request.optJSONObject("slots"),
                    VoiceConfirmations.sessionOf(utteranceId),
                    request == null ? "" : request.optString("confirmToken", ""));
        } catch (Throwable t) {
            logger.warn("Voice intent '" + intent + "' failed: " + t);
            response = response(false, FAILED, tierOf(intent), new JSONArray(),
                    "The command could not be completed.");
        }
        if (!utteranceId.isEmpty()) remember(utteranceId, response);
        logger.info("Voice intent '" + intent + "' source=" + (request == null ? ""
                : request.optString("source", "")) + " -> " + outcomeLog(response));
        return response;
    }

    // ── Dispatch ────────────────────────────────────────────────────────

    private static JSONObject run(String intent, JSONObject slots, String session, String confirmToken) {
        if (slots == null) slots = new JSONObject();
        slots = withDefaults(intent, slots);
        int tier = tierOf(intent);
        if (tier == 3) {
            return response(false, REFUSED, 3, new JSONArray(),
                    "adas.off".equals(intent) ? "Driver assistance can only be turned on by voice."
                            : "That command is not available by voice.");
        }
        // Ahead of the spoken confirmation, so nobody is asked to confirm a command voice won't run.
        String gated = gatedActionOf(intent);
        if (gated != null && needsStepUp(gated)) {
            return response(false, REFUSED, tier, new JSONArray(), stepUpRefusalText());
        }
        if (needsConfirmation(intent, slots)) {
            if (DrivingSafetyGuard.isMovementBlocked()) {
                return response(false, BLOCKED_MOVING, tier, new JSONArray(),
                        "Only when the car is parked.");
            }
            if (!VoiceConfirmations.consume(confirmToken, session, intent, slots)) {
                JSONObject ask = response(false, NEEDS_CONFIRMATION, tier, new JSONArray(),
                        confirmationQuestion(intent, slots));
                try {
                    ask.put("confirmToken", VoiceConfirmations.issue(session, intent, slots));
                } catch (Exception ignored) {
                }
                return ask;
            }
        }
        if (intent.startsWith("assistant.")) {
            return response(false, UNSUPPORTED, 1, new JSONArray(),
                    "Assistant control words are handled on the head unit.");
        }
        if (isCabinClimateWithoutWake(intent) && DrivingSafetyGuard.isAccConfirmedOff()) {
            // The HVAC accepts these writes with the car off and ignores them.
            return response(false, FAILED, tier, new JSONArray(), "Switch the car on first.");
        }
        Plan plan = new Plan();
        switch (intent) {
            case "climate.power": climatePower(plan, slots); break;
            case "climate.set_temp": setTemp(plan, slots); break;
            case "climate.step_temp": stepTemp(plan, slots); break;
            case "climate.fan": fan(plan, slots); break;
            case "climate.recirculation":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.AcRecirculationCommand(on),
                        "Recirculation on.", "Fresh air on.");
                break;
            case "climate.max_cool":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.AcMaxCoolingCommand(on),
                        "Max cooling on.", "Max cooling off.");
                break;
            case "climate.auto":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.AcAutoModeCommand(on),
                        "Auto climate on.", "Auto climate off.");
                break;
            case "climate.defrost_front":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.FrontDefrostCommand(on),
                        "Front defrost on.", "Front defrost off.");
                break;
            case "climate.defrost_rear":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.RearDefrostCommand(on),
                        "Rear defrost on.", "Rear defrost off.");
                break;
            case "climate.goal": ClimateGoalPlanner.run(plan, slots); break;
            case "seat.heat": seat(plan, slots, false); break;
            case "seat.vent": seat(plan, slots, true); break;
            case "steering.heat":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.SteeringWheelHeatCommand(on),
                        "Steering wheel heating on.", "Steering wheel heating off.");
                break;
            case "sunshade": sunshade(plan, slots); break;
            case "windows.close_all":
                single(plan, new VehicleCommandRouter.CloseAllWindowsCommand(), "Closing the windows.");
                break;
            case "windows.vent_all":
                single(plan, new VehicleCommandRouter.VentAllWindowsCommand(), "Venting the windows.");
                break;
            case "ambient.power": ambientPower(plan, slots); break;
            case "recording.save_clip": saveClip(plan); break;
            default:
                if (!runExtended(plan, intent, slots)) {
                    return response(false, REFUSED, 3, new JSONArray(),
                            "That command is not available by voice.");
                }
        }
        JSONObject out = response(DONE.equals(plan.code), plan.code, tier, plan.applied, plan.message);
        if (plan.missing != null) {
            try {
                out.put("missing", plan.missing);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** I6 tiers. Tier 2 runs immediately (no confirmations); tier 3 is refused. */
    static int tierOf(String intent) {
        if (intent == null) return 3;
        switch (intent) {
            case "climate.power":
            case "climate.set_temp":
            case "climate.step_temp":
            case "climate.fan":
            case "climate.recirculation":
            case "climate.max_cool":
            case "climate.auto":
            case "climate.defrost_front":
            case "climate.defrost_rear":
            case "climate.goal":
            case "seat.heat":
            case "seat.vent":
            case "steering.heat":
            case "sunshade":
            case "windows.close_all":
            case "ambient.power":
            case "recording.save_clip":
            case "assistant.cancel":
            case "assistant.yes":
            case "assistant.no":
                return 1;
            case "windows.vent_all":
                return 2;
            case "adas.off":
                return 3;
            default:
                return EXTENDED.contains(intent) ? (RISKY.contains(intent) ? 2 : 1) : 3;
        }
    }

    /**
     * Slots a spoken command leaves implicit, filled before the confirmation token is bound to them:
     * a window opened without a figure opens fully, as its confirmation question says.
     */
    static JSONObject withDefaults(String intent, JSONObject slots) {
        if (!"window.set".equals(intent) || slots.has("percent")) return slots;
        try {
            return new JSONObject(slots.toString()).put("percent", 100);
        } catch (Exception e) {
            return slots;
        }
    }

    static boolean needsConfirmation(String intent, JSONObject slots) {
        if (RISKY.contains(intent)) return true;
        if ("window.set".equals(intent)) {
            Integer percent = integer(slots, "percent");
            return percent == null || percent > WINDOW_CONFIRM_ABOVE_PERCENT;
        }
        return "child_lock".equals(intent) && Boolean.FALSE.equals(bool(slots, "on"));
    }

    /** The step-up action a spoken intent is; climate writes that can wake the car are gated where sent. */
    static String gatedActionOf(String intent) {
        switch (intent) {
            case "vehicle.unlock": return StepUpGate.UNLOCK;
            case "trunk.open": return StepUpGate.TRUNK_OPEN;
            case "vehicle.power_off": return StepUpGate.POWER_OFF;
            default: return null;
        }
    }

    // In the car the screen still works; from outside, the action needs the phone's approval.
    private static String stepUpRefusalText() {
        try {
            return StepUpGate.callerExempt() ? STEP_UP_REFUSAL_CAR : STEP_UP_REFUSAL;
        } catch (Throwable t) {
            return STEP_UP_REFUSAL;
        }
    }

    // While the lock covers an action, voice never performs it: audio played into the cabin
    // (remote speech, sounds, push-to-talk) could otherwise speak the command for someone.
    private static boolean needsStepUp(String action) {
        try {
            if (!StepUpGate.isGated(action)) return false;
            // Only a car confirmed on can't be woken; unknown counts as off.
            return !StepUpGate.POWER_ON.equals(action) || !DrivingSafetyGuard.isAccConfirmedOn();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void refuseForStepUp(Plan plan) {
        plan.code = REFUSED;
        plan.message = stepUpRefusalText();
    }

    static String confirmationQuestion(String intent, JSONObject slots) {
        switch (intent) {
            case "vehicle.unlock": return "Unlock the car?";
            case "vehicle.power_off": return "Switch the car off?";
            case "trunk.open": return "Open the trunk?";
            case "trunk.close": return "Close the trunk?";
            case "windows.open_all": return "Open all the windows?";
            case "window.set": {
                Integer percent = integer(slots, "percent");
                return "Open the " + windowName(slots.optString("window", "all")) + " to "
                        + (percent == null ? "full" : percent + " percent") + "?";
            }
            case "child_lock": return "Turn the child lock off?";
            case "adas.on": return "Turn on " + featureName(slots.optString("feature", "")) + "?";
            default: return "Are you sure?";
        }
    }

    private static boolean isCabinClimateWithoutWake(String intent) {
        switch (intent) {
            case "climate.step_temp":
            case "climate.fan":
            case "climate.recirculation":
            case "climate.max_cool":
            case "climate.auto":
            case "climate.defrost_front":
            case "climate.defrost_rear":
                return true;
            default:
                return false;
        }
    }

    // ── Climate ─────────────────────────────────────────────────────────

    private static void climatePower(Plan plan, JSONObject slots) {
        Boolean on = bool(slots, "on");
        if (on == null) {
            unsupported(plan, "Say whether to turn the air conditioning on or off.");
            return;
        }
        BydDataCollector c = BydDataCollector.getInstance();
        int ac = c.readAcPowerNow();
        if (on) {
            // start() refuses an AC that is already on.
            if (ac == 1) {
                plan.note("climate-on", "already_on");
                plan.message = "The air conditioning is already on.";
                return;
            }
            Double setpoint = currentSetpointCelsius(c);
            double t = setpoint != null ? setpoint : DEFAULT_ON_CELSIUS;
            ensureClimateOn(plan, t, "Air conditioning on.");
            return;
        }
        if (ac == 0 && !VehicleCommandRouter.getInstance().isRemoteClimateActive()) {
            plan.note("climate-off", "already_off");
            plan.message = "The air conditioning is already off.";
            return;
        }
        single(plan, new VehicleCommandRouter.ClimateOffCommand(), "Air conditioning off.");
    }

    /** ClimateOn sets the dial then powers on (and wakes a car that is off). */
    static boolean ensureClimateOn(Plan plan, double celsius, String doneMessage) {
        if (needsStepUp(StepUpGate.POWER_ON)) {
            refuseForStepUp(plan);
            return false;
        }
        VehicleCommandRouter.ClimateOnCommand cmd =
                new VehicleCommandRouter.ClimateOnCommand(celsius, DEFAULT_REMOTE_MINUTES);
        CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        plan.record(cmd.name(), r);
        if (r != null && r.outcome != Outcome.SUCCESS && r.outcome != Outcome.BLOCKED_DRIVING
                && BydDataCollector.getInstance().readAcPowerNow() == 1) {
            // start() refuses an AC that came on meanwhile; the cabin is in the asked-for state.
            plan.code = DONE;
            plan.message = doneMessage;
            return true;
        }
        plan.finishFrom(r, doneMessage);
        return DONE.equals(plan.code);
    }

    private static void setTemp(Plan plan, JSONObject slots) {
        Double celsius = number(slots, "celsius");
        if (celsius == null || !isDialCelsius(celsius)) {
            unsupported(plan, "The temperature can be set from "
                    + BydDataCollector.AC_SETPOINT_MIN_C + " to "
                    + BydDataCollector.AC_SETPOINT_MAX_C + " degrees.");
            return;
        }
        double t = celsius;
        String done = "Temperature set to " + formatDegrees(t) + ".";
        boolean ok;
        if (BydDataCollector.getInstance().readAcPowerNow() == 0) {
            // A setpoint alone changes nothing while the AC is off; ClimateOn writes it and powers on.
            ok = ensureClimateOn(plan, t, done);
        } else {
            ok = single(plan, new VehicleCommandRouter.ClimateSetTempCommand(0, t), done);
        }
        if (ok) GenAiRoutineLearner.recordClimate(0, t);
    }

    private static void stepTemp(Plan plan, JSONObject slots) {
        Integer delta = integer(slots, "delta");
        int maxDelta = BydDataCollector.AC_SETPOINT_MAX_F - BydDataCollector.AC_SETPOINT_MIN_F;
        if (delta == null || delta == 0 || delta < -maxDelta || delta > maxDelta) {
            unsupported(plan, "Say warmer or cooler.");
            return;
        }
        BydDataCollector c = BydDataCollector.getInstance();
        int acBefore = c.readAcPowerNow();
        // With the AC off the step ends in a ClimateOn; refuse before the dial moves.
        if (acBefore == 0 && needsStepUp(StepUpGate.POWER_ON)) {
            refuseForStepUp(plan);
            return;
        }
        VehicleCommandRouter.ClimateStepTempCommand step =
                new VehicleCommandRouter.ClimateStepTempCommand(
                        0, BydDataCollector.AC_TEMP_AREA_DRIVER, delta);
        CommandResult r = VehicleCommandRouter.getInstance().execute(step);
        plan.record(step.name(), r);
        int setpoint = step.resultSetpoint;
        String done = setpoint == BydVehicleData.UNAVAILABLE ? "Temperature changed."
                : "Temperature set to " + formatDegrees(dialToCelsius(setpoint)) + ".";
        plan.finishFrom(r, done);
        if (!DONE.equals(plan.code) || acBefore != 0 || setpoint == BydVehicleData.UNAVAILABLE) {
            return;
        }
        // The AC was off: a dial change alone would do nothing.
        ensureClimateOn(plan, dialToCelsius(setpoint), done);
    }

    private static void fan(Plan plan, JSONObject slots) {
        Integer level = integer(slots, "level");
        Integer delta = integer(slots, "delta");
        int target;
        if (level != null) {
            if (level < FAN_MIN || level > FAN_MAX) {
                unsupported(plan, "Fan speed goes from 1 to 7.");
                return;
            }
            target = level;
        } else if (delta != null && delta != 0) {
            BydVehicleData d = BydDataCollector.getInstance().getData();
            int current = d == null ? BydVehicleData.UNAVAILABLE : d.acFanLevel;
            if (current >= FAN_MIN && current <= FAN_MAX) {
                target = Math.max(FAN_MIN, Math.min(FAN_MAX, current + delta));
                if (target == current) {
                    plan.note("climate-fan", "at_limit");
                    plan.message = "The fan is already at " + (delta > 0 ? "maximum." : "minimum.");
                    return;
                }
            } else if (current == 0 && delta > 0) {
                target = Math.min(FAN_MAX, delta);
            } else {
                unsupported(plan, "The current fan speed is unknown; say a fan speed from 1 to 7.");
                return;
            }
        } else {
            unsupported(plan, "Say a fan speed from 1 to 7.");
            return;
        }
        single(plan, new VehicleCommandRouter.ClimateSetFanCommand(target), "Fan speed " + target + ".");
    }

    // ── Seats, body, lights ─────────────────────────────────────────────

    /** Mirrors VehicleControlApiHandler.handleSeat: a fresh full snapshot seeds the cloud fallback. */
    private static void seat(Plan plan, JSONObject slots, boolean ventilation) {
        String seat = slots.optString("seat", "driver").trim().toLowerCase(Locale.ROOT);
        int position = "passenger".equals(seat) ? 2 : "driver".equals(seat) ? 1 : -1;
        if (position < 0 && UNSUPPORTED_SEATS.contains(seat)) {
            // A real seat the car has no control for: refuse plainly rather than guess a front seat.
            plan.code = REFUSED;
            plan.message = "Only the driver and passenger seats can be controlled by voice.";
            return;
        }
        Integer level = integer(slots, "level");
        if (position < 0 || level == null || level < 0 || level > 2) {
            unsupported(plan, "Seat levels are off, low and high for the driver or passenger.");
            return;
        }
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        boolean fresh = VehicleCommandRouter.hasFreshCompleteSeatState(snap);
        int[] s = fresh ? seatStateWithTarget(snap.seatHeat, snap.seatCool, ventilation, position, level)
                : new int[] {0, 0, 0, 0};
        long at = fresh ? snap.seatClimateAtMs : 0L;
        VehicleCommand cmd = ventilation
                ? new VehicleCommandRouter.SeatVentCommand(position, level, s[0], s[1], s[2], s[3], true, at)
                : new VehicleCommandRouter.SeatHeatCommand(position, level, s[0], s[1], s[2], s[3], true, at);
        String who = position == 1 ? "Driver" : "Passenger";
        String what = ventilation ? " seat ventilation " : " seat heating ";
        String how = level == 0 ? "off." : level == 1 ? "low." : "high.";
        single(plan, cmd, who + what + how);
    }

    private static int[] seatStateWithTarget(int[] heat, int[] cool, boolean ventilation,
                                             int position, int level) {
        int[] state = {heat[0], cool[0], heat[1], cool[1]};
        int index = position == 1 ? (ventilation ? 1 : 0) : (ventilation ? 3 : 2);
        state[index] = level;
        return state;
    }

    private static void sunshade(Plan plan, JSONObject slots) {
        Boolean open = bool(slots, "open");
        if (open == null) {
            unsupported(plan, "Say open or close the sunshade.");
            return;
        }
        boolean ok = single(plan, new VehicleCommandRouter.SunshadeCommand(open ? 1 : 2),
                open ? "Opening the sunshade." : "Closing the sunshade.");
        if (ok) GenAiRoutineLearner.setSunshade(open ? "open" : "close");
    }

    // ── Recording ───────────────────────────────────────────────────────

    /** Same call as the Key Mapping / automation instant replay, sized to what the ring holds. */
    private static void saveClip(Plan plan) {
        int[] window = clipWindow();
        ManualClipService.RequestResult r =
                ManualClipService.getInstance().requestClip(window[0], window[1]);
        plan.note("manual-clip", r.status.name().toLowerCase(Locale.ROOT));
        switch (r.status) {
            case ACCEPTED:
                plan.code = DONE;
                plan.message = "Saving the clip.";
                break;
            case PIPELINE_NOT_READY:
                plan.code = UNSUPPORTED;
                plan.message = "The cameras are not recording.";
                break;
            default:
                plan.code = FAILED;
                plan.message = r.message == null || r.message.isEmpty()
                        ? "The clip could not be saved." : r.message;
                break;
        }
    }

    /** The user's own Key Mapping replay window, else 30 s of history, shrunk to fit the ring. */
    private static int[] clipWindow() {
        int before = CLIP_DEFAULT_BEFORE_S;
        int after = 0;
        try {
            JSONObject keymap = com.overdrive.app.config.UnifiedConfigManager.getKeymap();
            JSONArray bindings = keymap.optBoolean("enabled", false)
                    ? keymap.optJSONArray("bindings") : null;
            for (int i = 0; bindings != null && i < bindings.length(); i++) {
                JSONObject b = bindings.optJSONObject(i);
                JSONObject a = b == null || !b.optBoolean("enabled", true) ? null : b.optJSONObject("action");
                if (a == null || !"manualClip".equals(a.optString("kind", ""))) continue;
                ManualClipWindow w = ManualClipWindow.create(
                        a.optInt("beforeSeconds", -1), a.optInt("afterSeconds", -1));
                before = w.getBeforeSeconds();
                after = w.getAfterSeconds();
                break;
            }
        } catch (Throwable ignored) {
            // Keep the default window.
        }
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            com.overdrive.app.surveillance.HardwareEventRecorderGpu encoder =
                    pipeline == null ? null : pipeline.getEncoder();
            // requestClip refuses (RESTART_REQUIRED) a window the history ring cannot hold.
            while (encoder != null && before + after > 1
                    && !encoder.canRetainManualClip(before + after)) {
                if (before > 0) before--; else after--;
            }
        } catch (Throwable ignored) {
            // requestClip reports the pipeline state itself.
        }
        return new int[] {before, after};
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    private interface CommandFor {
        VehicleCommand build(boolean on);
    }

    private static void toggle(Plan plan, JSONObject slots, String key, CommandFor factory,
                               String onMessage, String offMessage) {
        Boolean on = bool(slots, key);
        if (on == null) {
            unsupported(plan, "Say on or off.");
            plan.missing = key;
            return;
        }
        single(plan, factory.build(on), on ? onMessage : offMessage);
    }

    /**
     * The interior-ambient main switch (whole cabin). The write walks the collector's three
     * tiers; when every tier refuses it, the readback tells a car that does not report ambient
     * lights at all (unsupported here, like the dashboard's unavailable switch) from a car that
     * reports them but refused the write (a write error), so the log and the reply say which.
     */
    private static void ambientPower(Plan plan, JSONObject slots) {
        toggle(plan, slots, "on", on -> new VehicleCommandRouter.AmbientPowerCommand(on, "both"),
                "Ambient lights on.", "Ambient lights off.");
        if (!FAILED.equals(plan.code)) return;
        Boolean reported = ambientStateReported();
        plan.code = ambientFailureCode(reported);
        plan.message = ambientFailureMessage(reported);
    }

    /** Whether the car reports an ambient main-switch state through either readback source. */
    private static Boolean ambientStateReported() {
        try {
            return BydDataCollector.getInstance().getAmbientLightEnabled() != BydVehicleData.UNAVAILABLE;
        } catch (Throwable t) {
            return null;
        }
    }

    static final long DRL_CONFIRM_MS = 1500;

    /**
     * A request for the state the lights are already in is reported as such, not as done: on
     * 2026-10-14 a misheard "turn off" switched them "on" and the model told the driver it worked.
     */
    private static boolean drlAlready(Plan plan, Boolean on) {
        if (on == null) return false;
        int state;
        try {
            state = BydDataCollector.getInstance().readDrlNow();
        } catch (Throwable t) {
            return false;
        }
        if (state == BydVehicleData.UNAVAILABLE || state != (on ? 1 : 0)) return false;
        plan.code = DUPLICATE;
        plan.message = "The daytime running lights are already " + (on ? "on." : "off.");
        plan.note("drl", "already");
        return true;
    }

    /**
     * The DRL switch accepts writes it does not apply (code 0, switch unchanged), so a done is kept
     * only once the car reports the requested state; a car that reports nothing keeps the done.
     */
    private static void confirmDrl(Plan plan, boolean on) {
        long deadline = android.os.SystemClock.elapsedRealtime() + DRL_CONFIRM_MS;
        int state = BydVehicleData.UNAVAILABLE;
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            while (true) {
                state = collector.readDrlNow();
                if (state == BydVehicleData.UNAVAILABLE || state == (on ? 1 : 0)
                        || android.os.SystemClock.elapsedRealtime() >= deadline) {
                    break;
                }
                Thread.sleep(150);
            }
        } catch (Throwable t) {
            state = BydVehicleData.UNAVAILABLE;
        }
        int gear = BydVehicleData.UNAVAILABLE;
        try {
            gear = BydDataCollector.getInstance().readGearNow();
        } catch (Throwable ignored) {
        }
        logger.info("Voice DRL " + (on ? "on" : "off") + ": car reports "
                + (state == BydVehicleData.UNAVAILABLE ? "nothing" : state == 1 ? "on" : "off")
                + " gear=" + (gear == BydVehicleData.UNAVAILABLE ? "?" : String.valueOf(gear)));
        if (state == BydVehicleData.UNAVAILABLE || state == (on ? 1 : 0)) return;
        plan.code = FAILED;
        plan.message = "The car accepted the command but kept its daytime running lights "
                + (state == 1 ? "on." : "off.");
        // The car's own settings switch the DRL only in P (gearbox auto mode 1).
        if (gear != BydVehicleData.UNAVAILABLE && gear != 1) plan.message += " It changes them only in Park.";
    }

    /** Unsupported only when the car positively reports no ambient state; null (readback failed) stays failed. */
    static String ambientFailureCode(Boolean reported) {
        return Boolean.FALSE.equals(reported) ? UNSUPPORTED : FAILED;
    }

    static String ambientFailureMessage(Boolean reported) {
        if (Boolean.FALSE.equals(reported)) {
            return "This car does not report ambient lights, so they cannot be switched by voice.";
        }
        return "The car refused the ambient light switch on every path.";
    }

    static boolean single(Plan plan, VehicleCommand cmd, String doneMessage) {
        if (cmd.wakeVehicleIfOff() && needsStepUp(StepUpGate.POWER_ON)) {
            refuseForStepUp(plan);
            return false;
        }
        CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        plan.record(cmd.name(), r);
        plan.finishFrom(r, doneMessage);
        return DONE.equals(plan.code);
    }

    private static void unsupported(Plan plan, String message) {
        plan.code = UNSUPPORTED;
        plan.message = message;
    }

    static String codeFor(CommandResult r) {
        if (r == null) return FAILED;
        switch (r.outcome) {
            case SUCCESS: return DONE;
            case BLOCKED_DRIVING: return BLOCKED_MOVING;
            case NOT_SUPPORTED:
            case AUTH_REQUIRED: return UNSUPPORTED;
            default: return FAILED;
        }
    }

    /** The code, plus the reason when the command did not complete, so the field log says why. */
    static String outcomeLog(JSONObject response) {
        String code = response == null ? FAILED : response.optString("code", FAILED);
        String message = response == null ? "" : response.optString("message", "").trim();
        if (DONE.equals(code) || message.isEmpty()) return code;
        return code + ": " + message.replace('\r', ' ').replace('\n', ' ');
    }

    static String messageFor(CommandResult r) {
        if (r == null) return "The command could not be completed.";
        if (r.outcome == Outcome.BLOCKED_DRIVING) return "Not available while driving.";
        if (!r.displayMessage.isEmpty()) return r.displayMessage;
        return r.outcome == Outcome.NOT_SUPPORTED ? "This car does not support that."
                : "The command could not be completed.";
    }

    /** The driver dial in Celsius (bands are disjoint, so the reading names its unit), or null. */
    static Double currentSetpointCelsius(BydDataCollector c) {
        int raw = c.readAcSetpointNow(BydDataCollector.AC_TEMP_AREA_DRIVER);
        if (raw == BydVehicleData.UNAVAILABLE) return null;
        double celsius = dialToCelsius(raw);
        return isDialCelsius(celsius) ? celsius : null;
    }

    static double dialToCelsius(int dial) {
        if (dial >= BydDataCollector.AC_SETPOINT_MIN_F && dial <= BydDataCollector.AC_SETPOINT_MAX_F) {
            return (dial - 32) * 5.0 / 9.0;
        }
        return dial;
    }

    static boolean isDialCelsius(double c) {
        return !Double.isNaN(c) && !Double.isInfinite(c)
                && c >= BydDataCollector.AC_SETPOINT_MIN_C && c <= BydDataCollector.AC_SETPOINT_MAX_C;
    }

    private static String formatDegrees(double celsius) {
        return Math.round(celsius) + " degrees";
    }

    private static Boolean bool(JSONObject slots, String key) {
        Object v = slots.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(s) || "on".equals(s) || "open".equals(s)) return Boolean.TRUE;
            if ("false".equals(s) || "off".equals(s) || "close".equals(s)) return Boolean.FALSE;
        }
        return null;
    }

    private static Double number(JSONObject slots, String key) {
        Object v = slots.opt(key);
        double d;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else if (v instanceof String) {
            try {
                d = Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static Integer integer(JSONObject slots, String key) {
        Double d = number(slots, key);
        if (d == null || d != Math.rint(d) || Math.abs(d) > 1000) return null;
        return (int) Math.round(d);
    }

    private static JSONObject response(boolean ok, String code, int tier, JSONArray applied,
                                       String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok);
            out.put("code", code);
            out.put("tier", tier);
            out.put("applied", applied);
            out.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
        }
        return out;
    }

    // ── Dedup (I7) ──────────────────────────────────────────────────────

    /** Null when this id is new (and now claimed); otherwise the duplicate reply. */
    private static JSONObject claim(String utteranceId) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (SEEN) {
            Iterator<Map.Entry<String, Seen>> it = SEEN.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Seen> e = it.next();
                if (now - e.getValue().atMs <= DEDUP_TTL_MS && SEEN.size() <= DEDUP_MAX) break;
                it.remove();
            }
            Seen seen = SEEN.get(utteranceId);
            if (seen == null) {
                SEEN.put(utteranceId, new Seen(now));
                return null;
            }
            JSONObject earlier = seen.response;
            JSONObject dup = response(false, DUPLICATE,
                    earlier == null ? 1 : earlier.optInt("tier", 1),
                    earlier == null ? new JSONArray() : earlier.optJSONArray("applied"),
                    "Already handled.");
            try {
                dup.put("originalCode", earlier == null ? "pending" : earlier.optString("code"));
            } catch (Exception ignored) {
            }
            return dup;
        }
    }

    private static void remember(String utteranceId, JSONObject response) {
        synchronized (SEEN) {
            Seen seen = SEEN.get(utteranceId);
            if (seen != null) seen.response = response;
        }
    }

    // ── Extended intents (I21) ──────────────────────────────────────────

    /** False when the intent is not one of these; the caller then refuses it. */
    private static boolean runExtended(Plan plan, String intent, JSONObject slots) {
        switch (intent) {
            case "media.pause": media(plan, "pause"); return true;
            case "media.play": media(plan, "play"); return true;
            case "media.next": media(plan, "next"); return true;
            case "media.search": mediaSearch(plan, slots); return true;
            case "media.previous": media(plan, "previous"); return true;
            case "volume.step": volumeStep(plan, slots); return true;
            case "volume.set": volumeSet(plan, slots); return true;
            case "nav.go": result(plan, VoiceNavigator.navigate(slots.optString("query", ""))); return true;
            case "phone.call":
                result(plan, VoicePhone.call(slots.optString("query", ""), slots.optString("number", "")));
                return true;
            case "vehicle.lock": single(plan, new VehicleCommandRouter.LockCommand(), "Locking the car."); return true;
            case "vehicle.unlock": single(plan, new VehicleCommandRouter.UnlockCommand(), "Unlocking the car."); return true;
            case "vehicle.power_off":
                single(plan, new VehicleCommandRouter.VehiclePowerOffCommand(), "Switching the car off.");
                return true;
            case "trunk.open": single(plan, new VehicleCommandRouter.TrunkOpenCommand(), "Opening the trunk."); return true;
            case "trunk.close": single(plan, new VehicleCommandRouter.TrunkCloseCommand(), "Closing the trunk."); return true;
            case "windows.open_all":
                single(plan, new VehicleCommandRouter.OpenAllWindowsCommand(), "Opening the windows.");
                return true;
            case "window.set": windowSet(plan, slots); return true;
            case "windows.rain_close":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.RainCloseWindowCommand(on),
                        "Windows will close when it rains.", "Rain closing is off.");
                return true;
            case "sunroof": sunroof(plan, slots); return true;
            case "child_lock":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.ChildLockCommand(on),
                        "Child lock on.", "Child lock off.");
                return true;
            case "mirrors.fold":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.MirrorFoldCommand(on),
                        "Folding the mirrors.", "Unfolding the mirrors.");
                return true;
            case "lights.headlights": headlights(plan, slots); return true;
            case "lights.drl":
                if (drlAlready(plan, bool(slots, "on"))) return true;
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.LightsCommand(on),
                        "Daytime running lights on.", "Daytime running lights off.");
                if (DONE.equals(plan.code)) confirmDrl(plan, Boolean.TRUE.equals(bool(slots, "on")));
                return true;
            case "lights.hazard":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.HazardCommand(on),
                        "Hazard lights on.", "Hazard lights off.");
                return true;
            case "lights.reading":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.ReadingLightCommand(on),
                        "Reading light on.", "Reading light off.");
                return true;
            case "ambient.colour": ambientColour(plan, slots); return true;
            case "ambient.brightness":
                percentCommand(plan, slots, 0, 100,
                        p -> new VehicleCommandRouter.AmbientBrightnessCommand(p, "both"), "Ambient brightness ");
                return true;
            case "climate.purify":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.AirPurifyCommand(on),
                        "Air purifier on.", "Air purifier off.");
                return true;
            case "climate.fan_only":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.FanOnlyModeCommand(on),
                        "Fan only, compressor off.", "Fan-only mode off.");
                return true;
            case "climate.sync":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.AcTemperatureSyncCommand(on),
                        "Both zones follow the driver.", "Separate temperature zones.");
                return true;
            case "battery.heat":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.BatteryHeatCommand(on),
                        "Battery heating on.", "Battery heating off.");
                return true;
            case "display.hud":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.HudPowerCommand(on),
                        "Head-up display on.", "Head-up display off.");
                return true;
            case "display.hud_brightness":
                percentCommand(plan, slots, 0, 100,
                        p -> new VehicleCommandRouter.HudBrightnessCommand(p), "Head-up display brightness ");
                return true;
            case "display.cluster_brightness":
                percentCommand(plan, slots, 0, 100,
                        p -> new VehicleCommandRouter.DriverDisplayBrightnessCommand(p), "Instrument brightness ");
                return true;
            case "charging.start":
                single(plan, new VehicleCommandRouter.StartChargingNowCommand(), "Starting the charge.");
                return true;
            case "charging.limit":
                percentCommand(plan, slots, 50, 100,
                        p -> new VehicleCommandRouter.ChargeCapPercentCommand(p), "Charge limit ");
                return true;
            case "charging.limit_enable":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.ChargeCapToggleCommand(on),
                        "Charge limit on.", "Charge limit off.");
                return true;
            case "charging.current": chargingCurrent(plan, slots); return true;
            case "charging.smart":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.SmartChargingToggleCommand(on),
                        "Smart charging on.", "Smart charging off.");
                return true;
            case "battery.hold":
                toggle(plan, slots, "on", on -> new VehicleCommandRouter.SocHoldToggleCommand(on),
                        "Holding the battery level.", "Battery hold off.");
                return true;
            case "battery.hold_target":
                percentCommand(plan, slots, 15, 70,
                        p -> new VehicleCommandRouter.SocTargetPercentCommand(p), "Battery hold target ");
                return true;
            case "drive.mode":
                modeCommand(plan, slots, new String[] {"normal", "eco", "sport", "snow"}, new int[] {1, 2, 3, 4},
                        m -> new VehicleCommandRouter.OperationModeCommand(m), "Drive mode ");
                return true;
            case "drive.power":
                modeCommand(plan, slots, new String[] {"ev", "hev"}, new int[] {1, 3},
                        m -> new VehicleCommandRouter.EnergyModeCommand(m), "Power mode ");
                return true;
            case "drive.regen":
                modeCommand(plan, slots, new String[] {"standard", "high", "max"}, new int[] {0, 1, 2},
                        m -> new VehicleCommandRouter.EnergyFeedbackCommand(m), "Regeneration ");
                return true;
            case "drive.steering":
                modeCommand(plan, slots, new String[] {"comfort", "sport"}, new int[] {1, 2},
                        m -> new VehicleCommandRouter.SteerAssistCommand(m), "Steering ");
                return true;
            case "drive.brake":
                modeCommand(plan, slots, new String[] {"comfort", "sport"}, new int[] {0, 1},
                        m -> new VehicleCommandRouter.BrakeFeelCommand(m), "Brake feel ");
                return true;
            case "adas.on": adasOn(plan, slots); return true;
            default: return false;
        }
    }

    private interface IntCommand {
        VehicleCommand build(int value);
    }

    private static void result(Plan plan, VoiceNavigator.Result r) {
        plan.code = r.code;
        plan.message = r.message;
        plan.note("voice-helper", r.code);
    }

    private static void percentCommand(Plan plan, JSONObject slots, int min, int max, IntCommand factory,
                                       String what) {
        Integer percent = integer(slots, "percent");
        if (percent == null || percent < min || percent > max) {
            unsupported(plan, "Say a value from " + min + " to " + max + " percent.");
            return;
        }
        single(plan, factory.build(percent), what + percent + " percent.");
    }

    private static void modeCommand(Plan plan, JSONObject slots, String[] names, int[] values,
                                    IntCommand factory, String what) {
        String mode = slots.optString("mode", "").trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(mode)) {
                single(plan, factory.build(values[i]), what + mode + ".");
                return;
            }
        }
        unsupported(plan, "Say " + String.join(", ", names) + ".");
    }

    /** Media through the daemon's own /api/vehicle/media route (session controls, then keys). */
    private static void media(Plan plan, String action) {
        // A spoken pause is confirmed by the player itself (verify), not only by its session.
        JSONObject reply = vehicleMedia(new JSONObjectBuilder()
                .with("target", "media_session").with("action", action)
                .with("verify", "pause".equals(action)).json);
        // A play the daemon could watch counts only once something is seen playing.
        String confirmedBy = "pause".equals(action) ? "paused"
                : "play".equals(action) && reply != null && reply.optBoolean("verified", false) ? "playing"
                : "sent";
        boolean ok = reply != null && reply.optBoolean("success", false)
                && reply.optBoolean(confirmedBy, false);
        plan.note("media-" + action, ok ? "done" : "failed");
        if (ok) {
            plan.code = DONE;
            plan.message = "pause".equals(action) ? "Paused." : "play".equals(action) ? "Playing."
                    : "next".equals(action) ? "Next track." : "Previous track.";
        } else if ("pause".equals(action) && reply != null
                && !"none".equals(reply.optString("method", "none"))) {
            plan.code = FAILED;
            plan.message = "The music did not stop.";
        } else {
            plan.code = "pause".equals(action) ? UNSUPPORTED : FAILED;
            plan.message = "pause".equals(action) ? "Nothing is playing."
                    : "play".equals(action) ? "The music did not start." : "The music did not respond.";
        }
    }

    /** "Play <song / artist / playlist>": the player's own search, confirmed by a new track playing. */
    private static void mediaSearch(Plan plan, JSONObject slots) {
        String query = slots.optString("query", "").trim();
        String kind = slots.optString("kind", "");
        if (query.isEmpty() && !"liked".equals(kind)) {
            unsupported(plan, "Say what to play.");
            return;
        }
        JSONObject reply = vehicleMedia(new JSONObjectBuilder().with("target", "media_session")
                .with("action", "search").with("query", query).with("kind", kind)
                .with("artist", slots.optString("artist", "").trim()).json);
        boolean ok = reply != null && reply.optBoolean("success", false) && reply.optBoolean("playing", false);
        plan.note("media-search", ok ? "done" : "failed");
        if (ok) {
            String title = reply.optString("title", "");
            String by = reply.optString("artist", "");
            plan.code = DONE;
            plan.message = title.isEmpty() ? "Playing."
                    : "Playing " + title + (by.isEmpty() ? "" : " by " + by) + ".";
        } else if (reply != null && "unsupported".equals(reply.optString("reason", ""))) {
            plan.code = UNSUPPORTED;
            plan.message = "The music app does not take search requests.";
        } else if (reply != null && "no_player".equals(reply.optString("reason", ""))) {
            plan.code = FAILED;
            plan.message = "Open the music app first.";
        } else {
            plan.code = FAILED;
            plan.message = "The music app did not play it.";
        }
    }

    private static void volumeStep(Plan plan, JSONObject slots) {
        String channel = volumeChannel(slots);
        Integer delta = integer(slots, "delta");
        if (channel == null || delta == null || delta == 0) {
            unsupported(plan, "Say louder or quieter.");
            return;
        }
        int[] now = streamVolume(channel);
        if (now == null) {
            plan.code = FAILED;
            plan.message = "The volume could not be read.";
            return;
        }
        setVolume(plan, channel, Math.max(0, Math.min(now[1], now[0] + delta)), now[1]);
    }

    private static void volumeSet(Plan plan, JSONObject slots) {
        String channel = volumeChannel(slots);
        Integer level = integer(slots, "level");
        Integer percent = integer(slots, "percent");
        int[] now = channel == null ? null : streamVolume(channel);
        if (channel == null || now == null || (level == null && percent == null)) {
            unsupported(plan, "Say a volume level.");
            return;
        }
        int target = percent != null && level == null
                ? Math.round(Math.max(0, Math.min(100, percent)) * now[1] / 100f) : level;
        setVolume(plan, channel, Math.max(0, Math.min(now[1], target)), now[1]);
    }

    private static void setVolume(Plan plan, String channel, int target, int max) {
        JSONObject reply = vehicleMedia(new JSONObjectBuilder().with("target", "volume")
                .with("channel", channel).with("value", target).json);
        boolean ok = reply != null && reply.optBoolean("success", false);
        plan.note("volume-" + channel, ok ? "done" : "failed");
        plan.code = ok ? DONE : FAILED;
        plan.message = ok ? "Volume " + target + " of " + max + "." : "The volume could not be changed.";
    }

    /** media (default), navigation, voice or phone; null for anything else. */
    private static String volumeChannel(JSONObject slots) {
        String c = slots.optString("channel", "media").trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) c = "media";
        switch (c) {
            case "media":
            case "navigation":
            case "voice":
            case "phone":
                return c;
            default:
                return null;
        }
    }

    /** {current, max} of the channel's stream in the daemon, or null. */
    private static int[] streamVolume(String channel) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.media.AudioManager am = ctx == null ? null
                    : (android.media.AudioManager) ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return null;
            int stream = "navigation".equals(channel) ? 14 : "voice".equals(channel) ? 16
                    : "phone".equals(channel) ? android.media.AudioManager.STREAM_VOICE_CALL
                    : android.media.AudioManager.STREAM_MUSIC;
            int max = am.getStreamMaxVolume(stream);
            return max > 0 ? new int[] {am.getStreamVolume(stream), max} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject vehicleMedia(JSONObject body) {
        try {
            com.overdrive.app.server.HttpServer server = com.overdrive.app.daemon.CameraDaemon.getHttpServer();
            if (server == null) return null;
            return VoiceNavigator.jsonBody(server.automationApiRequest("POST", "/api/vehicle/media", body.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class JSONObjectBuilder {
        final JSONObject json = new JSONObject();

        JSONObjectBuilder with(String key, Object value) {
            try {
                json.put(key, value);
            } catch (Exception ignored) {
            }
            return this;
        }
    }

    private static void windowSet(Plan plan, JSONObject slots) {
        Integer percent = integer(slots, "percent");
        int area = windowArea(slots.optString("window", "all"));
        if (percent == null || percent < 0 || percent > 100 || area < 0) {
            unsupported(plan, "Say which window and how far, from 0 to 100 percent.");
            return;
        }
        String name = windowName(slots.optString("window", "all"));
        String done = percent == 0 ? "Closing the " + name + "." : "Moving the " + name + " to " + percent + " percent.";
        if (area != 0) {
            single(plan, new VehicleCommandRouter.WindowMoveCommand(area, 0, percent), done);
            return;
        }
        // A target position exists per window only, so "all" moves the four side windows.
        CommandResult last = null;
        boolean all = true;
        for (int side = 1; side <= 4; side++) {
            VehicleCommandRouter.WindowMoveCommand cmd = new VehicleCommandRouter.WindowMoveCommand(side, 0, percent);
            last = VehicleCommandRouter.getInstance().execute(cmd);
            plan.record(cmd.name() + "-" + side, last);
            all &= last != null && last.outcome == Outcome.SUCCESS;
        }
        if (all) {
            plan.code = DONE;
            plan.message = done;
        } else {
            plan.finishFrom(last != null && last.outcome == Outcome.SUCCESS ? null : last, done);
        }
    }

    static int windowArea(String window) {
        switch (window == null ? "" : window.trim().toLowerCase(Locale.ROOT)) {
            case "":
            case "all": return 0;
            case "driver": return 1;
            case "passenger": return 2;
            case "rear_left": return 3;
            case "rear_right": return 4;
            default: return -1;
        }
    }

    static String windowName(String window) {
        switch (window == null ? "" : window.trim().toLowerCase(Locale.ROOT)) {
            case "driver": return "driver's window";
            case "passenger": return "passenger window";
            case "rear_left": return "rear left window";
            case "rear_right": return "rear right window";
            default: return "windows";
        }
    }

    private static void sunroof(Plan plan, JSONObject slots) {
        String mode = slots.optString("mode", "").trim().toLowerCase(Locale.ROOT);
        Boolean open = bool(slots, "open");
        if (mode.isEmpty() && open != null) mode = open ? "open" : "close";
        int code = "open".equals(mode) ? 1 : "close".equals(mode) ? 2 : "stop".equals(mode) ? 3
                : "half".equals(mode) ? 4 : "tilt".equals(mode) ? 5 : -1;
        if (code < 0) {
            unsupported(plan, "Say open, close, stop, half or tilt for the sunroof.");
            return;
        }
        single(plan, new VehicleCommandRouter.SunroofCommand(code),
                code == 2 ? "Closing the sunroof." : code == 3 ? "Stopping the sunroof." : "Opening the sunroof.");
    }

    private static void headlights(Plan plan, JSONObject slots) {
        String mode = slots.optString("mode", "").trim().toLowerCase(Locale.ROOT);
        int code = "off".equals(mode) ? 1 : "auto".equals(mode) ? 2 : "parking".equals(mode) ? 3
                : "low".equals(mode) ? 4 : -1;
        if (code < 0) {
            unsupported(plan, "Say auto, low beam, parking lights or off.");
            return;
        }
        single(plan, new VehicleCommandRouter.HeadlightModeCommand(code), "Headlights " + mode + ".");
    }

    private static void ambientColour(Plan plan, JSONObject slots) {
        Integer rgb = colourRgb(slots.optString("colour", ""));
        if (rgb == null) {
            unsupported(plan, "Say a colour such as red, blue, green, purple or white.");
            return;
        }
        single(plan, VehicleCommandRouter.AmbientColourCommand.rgb(rgb, "both"),
                "Ambient lights " + slots.optString("colour", "").trim().toLowerCase(Locale.ROOT) + ".");
    }

    static Integer colourRgb(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
            case "red": return 0xFF0000;
            case "orange": return 0xFF7F00;
            case "amber": return 0xFFBF00;
            case "yellow": return 0xFFFF00;
            case "green": return 0x00FF00;
            case "cyan":
            case "turquoise":
            case "teal": return 0x00FFFF;
            case "blue": return 0x0000FF;
            case "purple":
            case "violet": return 0x8000FF;
            case "pink":
            case "magenta": return 0xFF00FF;
            case "white": return 0xFFFFFF;
            default: return null;
        }
    }

    private static void chargingCurrent(Plan plan, JSONObject slots) {
        Integer amps = integer(slots, "level");
        String mode = slots.optString("mode", "").trim().toLowerCase(Locale.ROOT);
        int state = "max".equals(mode) ? 5 : amps == null ? -1
                : amps == 6 ? 1 : amps == 8 ? 2 : amps == 10 ? 3 : amps == 16 ? 4 : -1;
        if (state < 0) {
            unsupported(plan, "AC charging current can be 6, 8, 10 or 16 amps, or maximum.");
            return;
        }
        single(plan, new VehicleCommandRouter.AcChargeCurrentLimitCommand(state),
                state == 5 ? "Charging current maximum." : "Charging current " + amps + " amps.");
    }

    /** Driver assistance, ON only; unknown lane/FCW values would switch them off, so none is sent. */
    private static void adasOn(Plan plan, JSONObject slots) {
        String feature = slots.optString("feature", "").trim().toLowerCase(Locale.ROOT);
        VehicleCommand cmd;
        switch (feature) {
            case "esp": cmd = new VehicleCommandRouter.AdasEspCommand(true); break;
            case "itac": cmd = new VehicleCommandRouter.AdasItacCommand(true); break;
            case "lane_assist": cmd = new VehicleCommandRouter.AdasLaneAssistCommand(3); break;
            case "blind_spot": cmd = new VehicleCommandRouter.AdasBlindSpotCommand(true); break;
            case "traffic_sign": cmd = new VehicleCommandRouter.AdasTrafficSignCommand(true); break;
            case "rear_cross_traffic": cmd = new VehicleCommandRouter.AdasRearCrossTrafficCommand(true); break;
            case "front_cross_traffic": cmd = new VehicleCommandRouter.AdasFrontCrossTrafficCommand(true); break;
            case "traffic_light": cmd = new VehicleCommandRouter.AdasTrafficLightAttentionCommand(true); break;
            case "open_door_warning": cmd = new VehicleCommandRouter.AdasOpenDoorWarningCommand(true); break;
            case "rear_collision_warning": cmd = new VehicleCommandRouter.AdasRearCollisionWarningCommand(true); break;
            case "speed_limit_control": cmd = new VehicleCommandRouter.AdasSpeedLimitControlCommand(true); break;
            case "speed_limit_warning": cmd = new VehicleCommandRouter.AdasSpeedLimitWarningCommand(true); break;
            case "emergency_lane_keep": cmd = new VehicleCommandRouter.AdasEmergencyLaneKeepCommand(true); break;
            case "rear_cross_brake": cmd = new VehicleCommandRouter.AdasRearCrossBrakeCommand(true); break;
            case "front_cross_brake": cmd = new VehicleCommandRouter.AdasFrontCrossBrakeCommand(true); break;
            case "forward_collision_warning": cmd = new VehicleCommandRouter.AdasFcwLevelCommand(2); break;
            case "emergency_braking": cmd = new VehicleCommandRouter.AdasEmergencyBrakingCommand(true); break;
            default:
                unsupported(plan, "Say which driver assistance feature to turn on.");
                return;
        }
        single(plan, cmd, featureName(feature) + " on.");
    }

    static String featureName(String feature) {
        switch (feature == null ? "" : feature.trim().toLowerCase(Locale.ROOT)) {
            case "esp": return "stability control";
            case "itac": return "traction control";
            case "lane_assist": return "lane assist";
            case "blind_spot": return "blind spot detection";
            case "traffic_sign": return "traffic sign recognition";
            case "rear_cross_traffic": return "rear cross traffic alert";
            case "front_cross_traffic": return "front cross traffic alert";
            case "traffic_light": return "traffic light alert";
            case "open_door_warning": return "door opening warning";
            case "rear_collision_warning": return "rear collision warning";
            case "speed_limit_control": return "speed limit control";
            case "speed_limit_warning": return "speed limit warning";
            case "emergency_lane_keep": return "emergency lane keeping";
            case "rear_cross_brake": return "rear cross traffic braking";
            case "front_cross_brake": return "front cross traffic braking";
            case "forward_collision_warning": return "forward collision warning";
            case "emergency_braking": return "automatic emergency braking";
            default: return "that feature";
        }
    }

    // ── climate.goal ────────────────────────────────────────────────────

    /**
     * Deterministic cool/warm/defog/fresh plans from live cabin, outside and dial readings. The AC is
     * powered first (that also wakes a car that is off); the remaining HVAC writes are skipped if the
     * car still reads off, since it would ignore them.
     */
    static final class ClimateGoalPlanner {
        private ClimateGoalPlanner() {}

        static void run(Plan plan, JSONObject slots) {
            String goal = slots.optString("goal", "").trim().toLowerCase(Locale.ROOT);
            BydDataCollector c = BydDataCollector.getInstance();
            BydVehicleData d = c.getData();
            Double cabin = d != null && d.hasFreshCabinTemperature() ? d.insideTempC : null;
            Double outside = d != null && !Double.isNaN(d.outsideTempC) ? d.outsideTempC : null;
            Double dial = currentSetpointCelsius(c);
            switch (goal) {
                case "cool": cool(plan, c, cabin, outside, dial); break;
                case "warm": warm(plan, c, cabin, dial); break;
                case "defog": defog(plan, c, dial); break;
                case "fresh": fresh(plan, d); break;
                default: unsupported(plan, "Say cool, warm, defog or fresh air.");
            }
        }

        static double coolTarget(Double cabin, Double dial) {
            double t = cabin == null ? 21 : cabin >= 30 ? 18 : cabin >= 26 ? 20 : 21;
            return dial != null ? Math.min(t, Math.round(dial)) : t;
        }

        static double warmTarget(Double cabin, Double dial) {
            double t = cabin == null ? 24 : cabin <= 10 ? 26 : cabin <= 16 ? 25 : 24;
            return dial != null ? Math.max(t, Math.round(dial)) : t;
        }

        private static void cool(Plan plan, BydDataCollector c, Double cabin, Double outside,
                                 Double dial) {
            double t = coolTarget(cabin, dial);
            if (!acOnAt(plan, c, t, "Cooling the cabin to " + formatDegrees(t) + ".")) return;
            if (!carOn(plan)) return;
            // Recirculate unless the outside air is clearly cooler than the cabin.
            boolean recirculate = !(cabin != null && outside != null && outside < cabin - 2);
            optional(plan, new VehicleCommandRouter.AcRecirculationCommand(recirculate));
            if (cabin != null && cabin >= 26) {
                optional(plan, new VehicleCommandRouter.ClimateSetFanCommand(cabin >= 30 ? 6 : 5));
            }
        }

        private static void warm(Plan plan, BydDataCollector c, Double cabin, Double dial) {
            double t = warmTarget(cabin, dial);
            if (!acOnAt(plan, c, t, "Warming the cabin to " + formatDegrees(t) + ".")) return;
            if (cabin != null && cabin <= 12) {
                BydVehicleData snap = BydDataCollector.getInstance().getData();
                boolean fresh = VehicleCommandRouter.hasFreshCompleteSeatState(snap);
                int[] s = fresh ? seatStateWithTarget(snap.seatHeat, snap.seatCool, false, 1, 2)
                        : new int[] {0, 0, 0, 0};
                optional(plan, new VehicleCommandRouter.SeatHeatCommand(1, 2, s[0], s[1], s[2], s[3],
                        true, fresh ? snap.seatClimateAtMs : 0L));
                optional(plan, new VehicleCommandRouter.SteeringWheelHeatCommand(true));
            }
        }

        private static void defog(Plan plan, BydDataCollector c, Double dial) {
            double t = dial != null ? dial : DEFAULT_ON_CELSIUS;
            // The compressor dries the air; power it first, then aim at the glass with fresh air.
            if (!acOnAt(plan, c, t, "Defogging the windshield.")) return;
            if (!carOn(plan)) return;
            CommandResult r = VehicleCommandRouter.getInstance().execute(
                    new VehicleCommandRouter.FrontDefrostCommand(true));
            plan.record("defrost-front", r);
            plan.finishFrom(r, "Defogging the windshield.");
            if (!DONE.equals(plan.code)) return;
            optional(plan, new VehicleCommandRouter.AcRecirculationCommand(false));
        }

        private static void fresh(Plan plan, BydVehicleData d) {
            if (DrivingSafetyGuard.isAccConfirmedOff()) {
                plan.code = FAILED;
                plan.message = "Switch the car on first.";
                return;
            }
            if (!single(plan, new VehicleCommandRouter.AcRecirculationCommand(false),
                    "Bringing in fresh air.")) {
                return;
            }
            int fan = d == null ? BydVehicleData.UNAVAILABLE : d.acFanLevel;
            if (fan >= FAN_MIN && fan < 3) optional(plan, new VehicleCommandRouter.ClimateSetFanCommand(3));
        }

        /** AC on at [t] (ClimateOn), or just the dial when it is already on. */
        private static boolean acOnAt(Plan plan, BydDataCollector c, double t, String doneMessage) {
            if (c.readAcPowerNow() == 1) {
                return single(plan, new VehicleCommandRouter.ClimateSetTempCommand(0, t), doneMessage);
            }
            return ensureClimateOn(plan, t, doneMessage);
        }

        private static boolean carOn(Plan plan) {
            if (!DrivingSafetyGuard.isAccConfirmedOff()) return true;
            plan.note("hvac-extras", "skipped_car_off");
            return false;
        }

        /** Secondary steps never change the overall code; their outcome is in {@code applied}. */
        private static void optional(Plan plan, VehicleCommand cmd) {
            plan.run(cmd);
        }
    }
}
