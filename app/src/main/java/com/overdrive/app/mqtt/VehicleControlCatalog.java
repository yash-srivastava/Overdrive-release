package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of <b>controllable</b> vehicle entities exposed over MQTT / Home Assistant.
 *
 * Each entry knows two things:
 *   1. how to render itself as a Home Assistant discovery component (with its
 *      command/state topics, value domain, icon, …); and
 *   2. how to turn an inbound MQTT command payload into a
 *      {@link VehicleCommand} (always dispatched <b>SDK-only</b> — never the BYD cloud)
 *      plus an optional optimistic state echo.
 *
 * Read-back state topics reuse the existing per-field telemetry topics where the value
 * is already published ({@code ac_fan}, {@code light_drl}, {@code speed_limit_warning},
 * …). Where no telemetry field exists (e.g. the climate setpoint), the command echoes
 * the commanded value to a synthetic state topic so HA stays in sync.
 *
 * Only Tier-1 (already-implemented, SDK-backed) commands are registered here initially;
 * Tier 2/3 add entries following the same factories.
 */
public final class VehicleControlCatalog {

    private VehicleControlCatalog() {}

    /** Result of turning a payload into an action: the SDK command + optional optimistic echo. */
    public static final class ControlAction {
        public final VehicleCommand command;
        public final String echoKey;    // state-topic suffix to publish optimistically (nullable)
        public final String echoValue;  // value to publish (nullable)
        /** Deferred "remember what we commanded" hook for blind-toggle entities that have no
         *  readback (see {@link ControlEntity#toAction}). Null when there is nothing to
         *  remember. Callers MUST invoke {@link #commitToggleState()} only after the command
         *  actually succeeded — committing at build time made a REFUSED write still advance
         *  the cache, so the next blind "toggle" flipped away from a value the car never
         *  reached and the button appeared to need two presses. */
        private final Runnable toggleCommit;
        ControlAction(VehicleCommand c, String k, String v) { this(c, k, v, null); }
        ControlAction(VehicleCommand c, String k, String v, Runnable commit) {
            command = c; echoKey = k; echoValue = v; toggleCommit = commit;
        }
        public static ControlAction of(VehicleCommand c) { return new ControlAction(c, null, null); }
        public static ControlAction echo(VehicleCommand c, String k, String v) { return new ControlAction(c, k, v); }
        /** Record the just-commanded value so the next blind toggle flips from it. Idempotent
         *  and a no-op for entities with live readback.
         *
         *  <p>Call this when the command was actually ATTEMPTED against the vehicle — see
         *  {@link #commitIfAttempted}, which is what all callers use. Committing only on a
         *  confirmed SUCCESS sounds safer but breaks the feature outright on any HAL that
         *  cannot confirm: the cache never advances, so every "toggle" press re-sends the
         *  identical value and the opposite action becomes unreachable. A blind toggle has no
         *  readback by definition, so tracking "what we last commanded" is the only coherent
         *  semantics — the alternative is a control that only ever does one thing. */
        public void commitToggleState() {
            if (toggleCommit != null) toggleCommit.run();
        }
        /**
         * Commit the blind-toggle value iff the command actually reached the vehicle.
         *
         * <p>{@code SUCCESS} and {@code FAILED} both mean "we asked the car" — for a set-only
         * switch with no readback the resulting state is unknown either way, so the next press
         * must still alternate, otherwise the control freezes on one value forever on any HAL
         * that cannot confirm a write.
         *
         * <p>Everything else means nothing was commanded, so committing would silently swap
         * what the NEXT press does: {@code BLOCKED_DRIVING} (refused by the motion-safety
         * gate), {@code AUTH_REQUIRED}, {@code RATE_LIMITED}, and — importantly —
         * {@code NOT_SUPPORTED}, which {@code VehicleCommandRouter} returns from its
         * capability guards BEFORE invoking any leg (no SDK path / no cloud path / no legs at
         * all), not only after a real attempt.
         */
        public void commitIfAttempted(VehicleCommandRouter.Outcome outcome) {
            if (outcome == null) return;
            if (outcome == VehicleCommandRouter.Outcome.SUCCESS
                    || outcome == VehicleCommandRouter.Outcome.FAILED) {
                commitToggleState();
            }
        }
        ControlAction withToggleCommit(Runnable commit) {
            return new ControlAction(command, echoKey, echoValue, commit);
        }
    }

    /** Builds a command from (sub-key, payload, current snapshot). Return null to ignore. */
    public interface CommandFn {
        ControlAction build(String sub, String payload, BydVehicleData snap);
    }

    /** Optional capability gate — only advertise the entity when this returns true. */
    public interface AvailableFn {
        boolean available(BydVehicleData snap);
    }

    /**
     * Optional reader of an entity's current ON/OFF state from a live snapshot, used
     * to resolve a {@code "toggle"} payload into a concrete on/off before the command
     * is built. Returns null when the state is unknown (never reported) so the toggle
     * can fall back to a safe default. Only meaningful for on/off switch entities.
     */
    public interface StateFn {
        Boolean isOn(BydVehicleData snap);
    }

    /**
     * Optional reader of a SELECT entity's current option INDEX (0-based, into its
     * {@code options} list), used to resolve a {@code "toggle"}/cycle press by reading
     * the live mode and advancing from it — the same read-then-flip the OEM firmware
     * does. Returns -1 when the state is unavailable (getter absent on this trim), so
     * the cycle falls back to the last-commanded cache / default. Distinct from
     * {@link StateFn} (which is on/off for switches).
     */
    public interface SelectStateFn {
        int currentIndex();
    }

    /** One controllable entity. */
    public static final class ControlEntity {
        public final String key;            // topic key + unique_id suffix
        public final String platform;       // switch/number/select/button/lock/cover/climate
        public final String name;
        public final String icon;
        public final String category;       // "config"/"diagnostic"/null
        public final boolean sensitive;     // windows/sunroof/locks etc.
        public final String stateKey;       // existing telemetry key for state_topic; null = command-only/optimistic
        // select-only: Jinja value_template applied on the state topic to map an
        // enum int (as published by telemetry) onto an option word; null = none.
        public final String stateValueTemplate;
        // platform extras
        public final double min, max, step;
        public final String unit;
        public final List<String> options;
        public final String deviceClass;
        public final String onVal, offVal;
        public final CommandFn cmd;
        public final AvailableFn avail;
        // Optional current-state reader; when set, a "toggle" payload is resolved to
        // the opposite of this before the command is built. null → no toggle support.
        public final StateFn state;
        // Optional SELECT current-index reader; when set, a "toggle"/cycle press reads
        // the live option index and advances from it (parity with the OEM read-then-flip
        // toggle). null → cycle falls back to last-commanded cache / default.
        public final SelectStateFn selectState;

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, null, null, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, stateValueTemplate, null, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate, StateFn state) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, stateValueTemplate, state, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate, StateFn state,
                      SelectStateFn selectState) {
            this.key = key; this.platform = platform; this.name = name; this.icon = icon;
            this.category = category; this.sensitive = sensitive; this.stateKey = stateKey;
            this.min = min; this.max = max; this.step = step; this.unit = unit; this.options = options;
            this.deviceClass = deviceClass; this.onVal = onVal; this.offVal = offVal;
            this.cmd = cmd; this.avail = avail; this.stateValueTemplate = stateValueTemplate;
            this.state = state;
            this.selectState = selectState;
        }

        public boolean isAvailable(BydVehicleData snap) {
            try { return avail == null || avail.available(snap); } catch (Exception e) { return true; }
        }

        /** Whether this entity supports a "toggle" payload (has a state reader). */
        public boolean supportsToggle() { return state != null; }

        public ControlAction toAction(String sub, String payload, BydVehicleData snap) {
            try {
                // The only catalog entity with subtopics is climate. Covers advertise a
                // position topic for HA compatibility, but this catalog deliberately has no
                // position command; accepting OPEN on /position/set would turn a malformed
                // position publish into a physical movement.
                if ("climate".equals(platform)) {
                    if (!"mode".equals(sub) && !"temperature".equals(sub)
                            && !"fan_mode".equals(sub)) {
                        return null;
                    }
                } else if (sub != null) {
                    return null;
                }

                // AEB is enable-only. In particular, a blind "toggle" can resolve to OFF
                // after a previously committed ON, so it must not reach the generic toggle
                // resolver below.
                if ("adas_aeb".equals(key) && payload != null
                        && "toggle".equalsIgnoreCase(payload.trim())) {
                    return null;
                }

                if (payload != null && "toggle".equalsIgnoreCase(payload.trim())) {
                    // Two toggle strategies:
                    //  (a) SWITCH with a live state reader → flip the reported on/off.
                    //  (b) SELECT with a fixed option list but NO telemetry readback
                    //      (regen/steering/brake feel — the HAL exposes no getter) →
                    //      CYCLE to the next option, tracked by a last-commanded cache
                    //      so repeated presses walk the list (comfort→sport→comfort…).
                    if (state != null) {
                        Boolean on = null;
                        try { on = state.isOn(snap); } catch (Exception ignored) {}
                        if (on == null
                                && ("drl".equals(key) || "hazard".equals(key))) {
                            return null;
                        }
                        // Other unknown state readers retain the historical first-press ON
                        // behavior; light toggles above fail closed because stale telemetry
                        // can otherwise invert a safety-visible command.
                        boolean next = (on == null) ? true : !on;
                        payload = next ? "on" : "off";
                    } else if (options != null && !options.isEmpty()) {
                        payload = nextCyclePayload();
                    } else if (onVal != null) {
                        // (c) SET-ONLY SWITCH with no state reader and no option list (mirror
                        // fold). No live readback exists, so flip off the last-commanded value
                        // (default OFF → first press turns ON, i.e. folds). This is the "blind
                        // flip" the keymap/automation UI advertises; without it "toggle" fell
                        // through and truthy("toggle")==false made every press UNFOLD.
                        String last = LAST_SWITCH_PAYLOAD.get(key);
                        boolean wasOn = last != null && last.equalsIgnoreCase(onVal);
                        payload = wasOn ? offVal : onVal;
                    }
                    // Anything else: "toggle" falls through to the builder unchanged
                    // (which treats the unknown payload via its own default).
                }
                payload = normalizePayload(sub, payload);
                if (payload == null) return null;
                ControlAction action = cmd.build(sub, payload, snap);
                // Remember the last concrete payload we commanded for a cycle-capable
                // select, so the NEXT "toggle" advances from here even without readback.
                // This is deferred until dispatch; a blocked or unsupported write
                // must not advance a state the car never received.
                if (action != null && options != null && !options.isEmpty()
                        && payload != null && !"toggle".equalsIgnoreCase(payload.trim())) {
                    final String committed = payload;
                    final String k = key;
                    action = action.withToggleCommit(() -> LAST_SELECT_PAYLOAD.put(k, committed));
                }
                // Same for a set-only switch (mirror fold): record the concrete on/off value
                // just commanded so the next "toggle" flips from it. DEFERRED, not applied
                // here: this runs at BUILD time, before the command is executed, so writing the
                // cache unconditionally would advance it even for a press the motion-safety
                // gate refuses outright — silently swapping what the NEXT press does. The
                // caller applies it via ControlAction.commitIfAttempted(outcome) once the
                // command has actually reached the vehicle (see that method for why "only on
                // SUCCESS" is the wrong bar for a control with no readback).
                if (action != null && state == null && (options == null || options.isEmpty())
                        && onVal != null && payload != null
                        && !"toggle".equalsIgnoreCase(payload.trim())) {
                    final String committed = truthy(payload) ? onVal : offVal;
                    final String k = key;
                    action = action.withToggleCommit(() -> LAST_SWITCH_PAYLOAD.put(k, committed));
                }
                return action;
            } catch (Exception e) { return null; }
        }

        /**
         * Reject malformed ingress before a command builder can interpret it as a
         * default. MQTT, key mappings, and automations all use this boundary.
         */
        private String normalizePayload(String sub, String payload) {
            String value = payload == null ? "" : payload.trim();
            if ("switch".equals(platform)) {
                Boolean enabled = strictBoolean(value);
                if (enabled == null) return null;
                // AEB is exposed enable-only. Reject a direct MQTT/keymap "off"
                // before it reaches the lower SDK guard.
                if ("adas_aeb".equals(key) && !enabled.booleanValue()) return null;
                return enabled.booleanValue()
                        ? (onVal != null ? onVal : "on")
                        : (offVal != null ? offVal : "off");
            }
            if ("cover".equals(platform)) {
                if ("OPEN".equalsIgnoreCase(value)) return "OPEN";
                if ("CLOSE".equalsIgnoreCase(value)) return "CLOSE";
                if ("STOP".equalsIgnoreCase(value)) return "STOP";
                return null;
            }
            if ("select".equals(platform)) {
                // Older keymap/automation payloads used a four-level seat scale. The HAL and
                // cloud both have three levels, so retain "medium" as a high alias without
                // advertising it or allowing the old raw "3" value through.
                if (isSeatClimateControl() && "medium".equalsIgnoreCase(value)) return "high";
                if (options == null) return null;
                for (String option : options) {
                    if (option.equalsIgnoreCase(value)) return option;
                }
                return null;
            }
            if ("number".equals(platform)) {
                // ambient_colour also takes a strict #RRGGBB, matched to the nearest palette colour.
                if ("ambient_colour".equals(key) && value.startsWith("#")
                        && com.overdrive.app.byd.light.AmbientPalette.parseRgb(value) > 0) {
                    return value;
                }
                try {
                    int number = Integer.parseInt(value);
                    if (number < min || number > max) return null;
                    // HA's min/max/step declaration is also the ingress contract. Without
                    // this check, e.g. a charge cap of 51 bypassed the advertised 5% steps.
                    if (step > 0d) {
                        double steps = (number - min) / step;
                        if (Math.abs(steps - Math.rint(steps)) > 0.000001d) return null;
                    }
                    return String.valueOf(number);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            if ("climate".equals(platform)) {
                if ("mode".equals(sub)) {
                    if ("off".equalsIgnoreCase(value)) return "off";
                    if ("auto".equalsIgnoreCase(value)) return "auto";
                    return null;
                }
                if ("temperature".equals(sub)) {
                    try {
                        double temperature = Double.parseDouble(value);
                        if (Double.isNaN(temperature) || Double.isInfinite(temperature)
                                || temperature < min || temperature > max) {
                            return null;
                        }
                        if (step > 0d) {
                            double steps = (temperature - min) / step;
                            if (Math.abs(steps - Math.rint(steps)) > 0.000001d) return null;
                        }
                        return value;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                if ("fan_mode".equals(sub)) {
                    try {
                        int fan = Integer.parseInt(value);
                        return fan >= 1 && fan <= 7 ? String.valueOf(fan) : null;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return null;
            }
            if ("button".equals(platform)) {
                return "PRESS".equalsIgnoreCase(value) ? "PRESS" : null;
            }
            if ("text".equals(platform)) {
                return value.isEmpty() ? null : value;
            }
            if ("lock".equals(platform)) {
                if ("LOCK".equalsIgnoreCase(value)) return "LOCK";
                if ("UNLOCK".equalsIgnoreCase(value)) return "UNLOCK";
                return null;
            }
            // New catalog platforms must define an explicit payload domain before they can
            // become remotely controllable.
            return null;
        }

        private boolean isSeatClimateControl() {
            return "seat_heat_driver".equals(key) || "seat_heat_passenger".equals(key)
                    || "seat_vent_driver".equals(key) || "seat_vent_passenger".equals(key);
        }

        /**
         * Advance a select entity to its next option for a "toggle"/cycle press.
         *
         * <p>Current-index resolution, best-to-worst — mirroring how the OEM firmware
         * toggles (it reads the live mode back):
         *   1. LIVE READBACK (selectState): the SDK getter for this mode
         *      (getBrakeFootSense / getEnergyFeedback / getSteerAssist). This is the
         *      robust source — it reflects a mode changed from the car's own menu and
         *      survives a daemon restart. Returns the app-level option index, or -1 if
         *      the getter is unavailable on this trim.
         *   2. LAST-COMMANDED cache: what we last set (process-lifetime).
         *   3. DEFAULT assumption: the HAL boots these at option[0] (standard/comfort),
         *      so with nothing known we assume idx 0 and advance to option[1] — the
         *      first press then visibly CHANGES the mode instead of re-commanding the
         *      default and appearing to do nothing.
         * Cycles option[i] → option[i+1] (wrapping), case-insensitive.
         */
        private String nextCyclePayload() {
            int idx = -1;
            // 1. Live readback (parity with the OEM firmware's read-then-flip toggle).
            if (selectState != null) {
                try {
                    int live = selectState.currentIndex();
                    if (live >= 0 && live < options.size()) idx = live;
                } catch (Exception ignored) {}
            }
            // 2. Last-commanded cache.
            if (idx < 0) {
                String last = LAST_SELECT_PAYLOAD.get(key);
                if (last != null) {
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i).equalsIgnoreCase(last)) { idx = i; break; }
                    }
                }
            }
            // 3. Default: assume booted at option[0] so the first press flips to [1].
            if (idx < 0) idx = 0;
            int nextIdx = (idx + 1) % options.size();
            return options.get(nextIdx);
        }

        /** Build the Home Assistant discovery component (with topics injected). */
        public JSONObject component(String baseTopic, String node) {
            try {
                JSONObject c = new JSONObject();
                c.put("p", platform);
                c.put("name", name);
                c.put("unique_id", node + "_ctl_" + key);
                if (icon != null) c.put("icon", icon);
                if (category != null) c.put("entity_category", category);
                String cmdBase = baseTopic + "/" + key;

                switch (platform) {
                    case "climate": {
                        c.put("modes", new JSONArray(java.util.Arrays.asList("off", "auto")));
                        c.put("mode_command_topic", cmdBase + "/mode/set");
                        c.put("mode_state_topic", baseTopic + "/ac_on");
                        c.put("mode_state_template", "{% if (value | int(0)) > 0 %}auto{% else %}off{% endif %}");
                        c.put("temperature_command_topic", cmdBase + "/temperature/set");
                        c.put("temperature_state_topic", baseTopic + "/climate_setpoint");
                        c.put("min_temp", min); c.put("max_temp", max); c.put("temp_step", step);
                        JSONArray fans = new JSONArray();
                        for (int i = 1; i <= 7; i++) fans.put(String.valueOf(i));
                        c.put("fan_modes", fans);
                        c.put("fan_mode_command_topic", cmdBase + "/fan_mode/set");
                        c.put("fan_mode_state_topic", baseTopic + "/ac_fan");
                        c.put("current_temperature_topic", baseTopic + "/cabin_temp");
                        break;
                    }
                    case "cover": {
                        if (deviceClass != null) c.put("device_class", deviceClass);
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_open", "OPEN");
                        c.put("payload_close", "CLOSE");
                        c.put("payload_stop", "STOP");
                        if (stateKey != null) {
                            c.put("position_topic", baseTopic + "/" + stateKey);
                            c.put("set_position_topic", cmdBase + "/position/set");
                            c.put("position_open", 100);
                            c.put("position_closed", 0);
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "lock": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_lock", "LOCK");
                        c.put("payload_unlock", "UNLOCK");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            c.put("state_locked", onVal != null ? onVal : "2");
                            c.put("state_unlocked", offVal != null ? offVal : "1");
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "number": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) c.put("state_topic", baseTopic + "/" + stateKey);
                        c.put("min", min); c.put("max", max); c.put("step", step);
                        if (unit != null) c.put("unit_of_measurement", unit);
                        c.put("mode", "slider");
                        break;
                    }
                    case "select": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            // Telemetry publishes the enum as a raw int; map it onto the
                            // option word so the HA select accepts the state. The `else value`
                            // passthrough leaves an already-word-valued echo untouched.
                            if (stateValueTemplate != null) c.put("value_template", stateValueTemplate);
                        }
                        c.put("options", new JSONArray(options));
                        break;
                    }
                    case "button": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_press", "PRESS");
                        break;
                    }
                    case "text": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("mode", "text");
                        break;
                    }
                    case "switch":
                    default: {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                        } else {
                            c.put("optimistic", true);
                        }
                        c.put("payload_on", onVal != null ? onVal : "1");
                        c.put("payload_off", offVal != null ? offVal : "0");
                        c.put("state_on", onVal != null ? onVal : "1");
                        c.put("state_off", offVal != null ? offVal : "0");
                        break;
                    }
                }
                return c;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== registry ====================

    private static final Map<String, ControlEntity> ENTITIES = new LinkedHashMap<>();

    // Last concrete payload commanded per readback-less SELECT entity (regen/steering/
    // brake feel), so a "toggle"/cycle press can advance to the next option without any
    // telemetry getter. Written from key-map / MQTT / automation dispatch threads →
    // concurrent map. Process-lifetime cache; a daemon restart just resets to "start
    // from first option", which is fine (the first press picks a deterministic option).
    private static final Map<String, String> LAST_SELECT_PAYLOAD = new java.util.concurrent.ConcurrentHashMap<>();

    // Last concrete payload commanded per readback-less SWITCH entity (mirror fold — a
    // set-only on/off switch with no fold-state getter). Lets a "toggle" press flip off the
    // last-commanded value, which is the "blind flip" the keymap/automation UI promises.
    // Same lifecycle/concurrency notes as LAST_SELECT_PAYLOAD.
    private static final Map<String, String> LAST_SWITCH_PAYLOAD = new java.util.concurrent.ConcurrentHashMap<>();

    private static void register(ControlEntity e) { ENTITIES.put(e.key, e); }

    public static Collection<ControlEntity> all() { return ENTITIES.values(); }
    public static ControlEntity get(String key) { return ENTITIES.get(key); }

    /**
     * Generic charge-stop controls are dynamically exposed in HA only after the
     * matching SDK backend has a complete, verified readback.
     */
    static boolean isGenericChargeCapControl(String key) {
        return "charge_cap_enabled".equals(key) || "charge_cap_percent".equals(key);
    }

    // ── factories ───────────────────────────────────────────────────────
    static ControlEntity sw(String key, String name, String icon, String category, String stateKey,
                            String onVal, String offVal, CommandFn cmd) {
        return new ControlEntity(key, "switch", name, icon, category, false, stateKey,
                0, 0, 0, null, null, null, onVal, offVal, cmd, null);
    }
    /** Switch with a live state reader → supports a "toggle" payload (flip current). */
    static ControlEntity sw(String key, String name, String icon, String category, String stateKey,
                            String onVal, String offVal, CommandFn cmd, StateFn state) {
        return new ControlEntity(key, "switch", name, icon, category, false, stateKey,
                0, 0, 0, null, null, null, onVal, offVal, cmd, null, null, state);
    }
    static ControlEntity number(String key, String name, String icon, String category, String stateKey,
                                double min, double max, double step, String unit, CommandFn cmd) {
        return new ControlEntity(key, "number", name, icon, category, false, stateKey,
                min, max, step, unit, null, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, String stateValueTemplate, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null, stateValueTemplate);
    }
    /** Select with a live current-index reader → a "toggle"/cycle press reads the live
     *  mode and advances from it (parity with the OEM read-then-flip toggle). */
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, CommandFn cmd, SelectStateFn selectState) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null, null, null, selectState);
    }
    static ControlEntity cover(String key, String name, String icon, String deviceClass, boolean sensitive,
                               String stateKey, CommandFn cmd) {
        return new ControlEntity(key, "cover", name, icon, null, sensitive, stateKey,
                0, 0, 0, null, null, deviceClass, null, null, cmd, null);
    }
    static ControlEntity climate(CommandFn cmd) {
        return new ControlEntity("climate", "climate", "Climate", "mdi:air-conditioner", null, false, null,
                17, 33, 1, null, null, null, null, null, cmd, null);
    }
    static ControlEntity text(String key, String name, String icon, String category, CommandFn cmd) {
        return new ControlEntity(key, "text", name, icon, category, false, null,
                0, 0, 0, null, null, null, null, null, cmd, null);
    }

    // ── helpers ─────────────────────────────────────────────────────────
    static int pInt(String s, int dflt) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; } }
    static double pDouble(String s, double dflt) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return dflt; } }
    static boolean truthy(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.equals("1") || t.equalsIgnoreCase("on") || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("ON");
    }
    private static Boolean strictBoolean(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if ("1".equals(normalized) || "on".equalsIgnoreCase(normalized)
                || "true".equalsIgnoreCase(normalized)) return Boolean.TRUE;
        if ("0".equals(normalized) || "off".equalsIgnoreCase(normalized)
                || "false".equalsIgnoreCase(normalized)) return Boolean.FALSE;
        return null;
    }
    private static Integer chargeCapPercent(String value) {
        try {
            int percent = Integer.parseInt(value.trim());
            return percent >= 50 && percent <= 100 ? Integer.valueOf(percent) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
    /** Build a parameterized remote-climate start from a HA text JSON payload. */
    private static ControlAction remoteClimateStartAction(String payload) {
        try {
            JSONObject request = new JSONObject(payload);
            Object rawTemp = request.opt("temp");
            Object rawDuration = request.opt("durationMinutes");
            if (!(rawTemp instanceof Number) || !(rawDuration instanceof Number)) return null;
            double temp = ((Number) rawTemp).doubleValue();
            double duration = ((Number) rawDuration).doubleValue();
            if (Double.isNaN(temp) || Double.isInfinite(temp) || temp != Math.rint(temp)
                    || temp < 15D || temp > 31D || duration != Math.rint(duration)
                    || duration < Integer.MIN_VALUE || duration > Integer.MAX_VALUE) {
                return null;
            }
            int minutes = (int) duration;
            if (minutes != 10 && minutes != 15 && minutes != 20
                    && minutes != 25 && minutes != 30) {
                return null;
            }
            return ControlAction.of(new VehicleCommandRouter.ClimateOnCommand(temp, minutes));
        } catch (Exception ignored) {
            return null;
        }
    }
    /**
     * Build a BOOKINGAIR lifecycle command from one HA text payload. Booking IDs and times may
     * be canonical decimal strings so 64-bit identifiers are never rounded by a JSON client.
     */
    private static ControlAction remoteClimateScheduleAction(String payload) {
        try {
            JSONObject request = new JSONObject(payload);
            Object rawAction = request.opt("action");
            if (!(rawAction instanceof String)) return null;
            String action = (String) rawAction;
            boolean create = "create".equals(action);
            boolean update = "update".equals(action);
            boolean delete = "delete".equals(action);
            if (!create && !update && !delete) return null;

            Long bookingId = request.has("bookingId")
                    ? positiveJsonLong(request, "bookingId") : null;
            if (request.has("bookingId") && bookingId == null) return null;
            if ((update || delete) && bookingId == null) return null;
            if (delete) {
                return ControlAction.of(new VehicleCommandRouter.ClimateScheduleCommand(
                        VehicleCommandRouter.ClimateScheduleCommand.REMOVE,
                        bookingId, null, null, null));
            }

            Long bookingTime = positiveJsonLong(request, "bookingTime");
            long nowSeconds = System.currentTimeMillis() / 1000L;
            if (bookingTime == null || bookingTime.longValue() <= nowSeconds + 30L) return null;

            double temp = 22D;
            if (request.has("temp")) {
                Object rawTemp = request.opt("temp");
                if (!(rawTemp instanceof Number)) return null;
                temp = ((Number) rawTemp).doubleValue();
            }
            if (Double.isNaN(temp) || Double.isInfinite(temp) || temp != Math.rint(temp)
                    || temp < 15D || temp > 31D) {
                return null;
            }

            int duration = 20;
            if (request.has("durationMinutes")) {
                Object rawDuration = request.opt("durationMinutes");
                if (!(rawDuration instanceof Number)) return null;
                double raw = ((Number) rawDuration).doubleValue();
                if (Double.isNaN(raw) || Double.isInfinite(raw) || raw != Math.rint(raw)
                        || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
                    return null;
                }
                duration = (int) raw;
            }
            if (!isValidRemoteClimateDuration(duration)) return null;

            return ControlAction.of(new VehicleCommandRouter.ClimateScheduleCommand(
                    create ? VehicleCommandRouter.ClimateScheduleCommand.CREATE
                            : VehicleCommandRouter.ClimateScheduleCommand.MODIFY,
                    bookingId, bookingTime, Double.valueOf(temp), Integer.valueOf(duration)));
        } catch (Exception ignored) {
            return null;
        }
    }
    /** Positive integral JSON value or canonical positive decimal text, preserving 64-bit IDs. */
    private static Long positiveJsonLong(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object raw = request.opt(key);
        long value;
        try {
            if (raw instanceof String) {
                String decimal = (String) raw;
                if (!decimal.matches("[1-9][0-9]*")) return null;
                value = Long.parseLong(decimal);
            } else if (raw instanceof Byte || raw instanceof Short
                    || raw instanceof Integer || raw instanceof Long) {
                value = ((Number) raw).longValue();
            } else if (raw instanceof Number) {
                double number = ((Number) raw).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number)
                        || number != Math.rint(number)
                        || number > 9_007_199_254_740_991D
                        || number < 1D) {
                    return null;
                }
                value = (long) number;
            } else {
                return null;
            }
        } catch (NumberFormatException invalid) {
            return null;
        }
        return value > 0L ? Long.valueOf(value) : null;
    }
    private static boolean isValidRemoteClimateDuration(int minutes) {
        return minutes == 10 || minutes == 15 || minutes == 20
                || minutes == 25 || minutes == 30;
    }
    /** Build a cloud smart-charge schedule from the JSON accepted by the HA text entity. */
    private static ControlAction smartChargeScheduleAction(String payload) {
        try {
            JSONObject schedule = new JSONObject(payload);
            Object rawStart = schedule.opt("startChargeTime");
            Object rawEnd = schedule.opt("endChargeTime");
            Object rawWay = schedule.opt("chargeWay");
            if (!(rawStart instanceof String) || !(rawEnd instanceof String)
                    || !(rawWay instanceof String)) {
                return null;
            }
            String start = (String) rawStart;
            String end = (String) rawEnd;
            String way = (String) rawWay;
            if (!isValidChargingTime(start, false) || !isValidChargingTime(end, true)
                    || !isValidChargeWay(way)) {
                return null;
            }
            boolean enabled = true;
            if (schedule.has("enabled")) {
                Object rawEnabled = schedule.opt("enabled");
                if (!(rawEnabled instanceof Boolean)) return null;
                enabled = ((Boolean) rawEnabled).booleanValue();
            }
            return ControlAction.of(new VehicleCommandRouter.ChargeScheduleCommand(
                    start, end, way, enabled));
        } catch (Exception ignored) {
            return null;
        }
    }
    private static boolean isValidChargingTime(String value, boolean allowFull) {
        if (allowFull && "full".equals(value)) return true;
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return false;
        int hour = (value.charAt(0) - '0') * 10 + value.charAt(1) - '0';
        int minute = (value.charAt(3) - '0') * 10 + value.charAt(4) - '0';
        return hour <= 23 && minute <= 59;
    }
    private static boolean isValidChargeWay(String value) {
        if ("s".equals(value) || "e".equals(value)) return true;
        if (value == null || value.isEmpty()) return false;
        String[] days = value.split(",", -1);
        boolean[] seen = new boolean[7];
        for (String day : days) {
            if (day.length() != 1 || day.charAt(0) < '0' || day.charAt(0) > '6') return false;
            int index = day.charAt(0) - '0';
            if (seen[index]) return false;
            seen[index] = true;
        }
        return true;
    }
    private static int seatHeat(BydVehicleData s, int idx) {
        return (s != null && s.seatHeat != null && s.seatHeat.length > idx) ? s.seatHeat[idx] : 0;
    }
    private static int seatCool(BydVehicleData s, int idx) {
        return (s != null && s.seatCool != null && s.seatCool.length > idx) ? s.seatCool[idx] : 0;
    }
    /**
     * Local seat telemetry may seed the composite cloud command only when all
     * sibling levels are current. The router can otherwise use a fresh cloud
     * vehicle snapshot and fails closed when neither source is complete.
     */
    private static boolean hasCurrentCompleteSeatState(BydVehicleData s) {
        return VehicleCommandRouter.hasFreshCompleteSeatState(s);
    }
    private static final List<String> SEAT_LEVELS = java.util.Arrays.asList("off", "low", "high");
    private static int seatLevel(String payload) {
        if ("medium".equalsIgnoreCase(payload.trim())) return 2;
        return SEAT_LEVELS.indexOf(payload.trim().toLowerCase());
    }
    private static long seatSnapshotAtMs(BydVehicleData s, boolean fresh) {
        return fresh ? s.seatClimateAtMs : 0L;
    }
    private static final List<String> INFOTAINMENT_ROTATIONS =
            java.util.Arrays.asList("horizontal", "vertical");
    private static final List<String> HEADLIGHT_MODES =
            java.util.Arrays.asList("off", "auto", "parking", "low_beam");
    static int headlightModeValue(String payload) {
        if (payload == null) return BydVehicleData.UNAVAILABLE;
        switch (payload.trim().toLowerCase()) {
            case "off": return com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_OFF;
            case "auto": return com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_AUTO;
            case "parking": return com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_PARKING;
            case "low_beam": return com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_LOW_BEAM;
            default: return BydVehicleData.UNAVAILABLE;
        }
    }
    private static int infotainmentRotationValue(String payload) {
        if ("horizontal".equalsIgnoreCase(payload)) {
            return com.overdrive.app.byd.BydDataCollector.PAD_ROTATION_HORIZONTAL;
        }
        if ("vertical".equalsIgnoreCase(payload)) {
            return com.overdrive.app.byd.BydDataCollector.PAD_ROTATION_VERTICAL;
        }
        return -1;
    }
    private static final List<String> NATIVE_CAMERA_VIEWS = java.util.Arrays.asList(
            "front", "front_wide", "rear", "rear_wide", "left", "right", "left_right");
    static int nativeCameraViewCode(String payload) {
        if (payload == null) return -1;
        switch (payload.trim().toLowerCase()) {
            case "front": return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_FRONT;
            case "front_wide":
                return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_FRONT_WIDE;
            case "rear": return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_REAR;
            case "rear_wide":
                return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_REAR_WIDE;
            case "left": return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_LEFT;
            case "right": return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_RIGHT;
            case "left_right":
                return com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_LEFT_RIGHT;
            default: return -1;
        }
    }
    // User-facing config-axis values map to Energy operation NORMAL/ECO/SPORT/SNOW = 3/1/2/4.
    private static final List<String> DRIVE_MODES = java.util.Arrays.asList("normal", "eco", "sport", "snow");
    // Stable OverDrive config axis (see BydDataCollector.setDriveConfigMode):
    // NORMAL=1, ECO=2, SPORT=3, SNOW=4. This is not the energy setter's public numbering.
    private static int driveModeValue(String payload) {
        String p = payload.trim().toLowerCase();
        if ("normal".equals(p)) return 1;
        if ("eco".equals(p) || "economy".equals(p)) return 2;
        if ("sport".equals(p)) return 3;
        if ("snow".equals(p)) return 4;
        return pInt(payload, 1);
    }

    /**
     * User-writable powertrain option word to SDK energy-mode int (ev=1, hev=3).
     * Modes 2/4/5 remain decodable as telemetry but are not field-validated writes.
     */
    static Integer powertrainModeValue(String payload) {
        String p = payload == null ? "" : payload.trim().toLowerCase();
        switch (p) {
            case "ev":                            return com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_EV;
            case "hev": case "auto":              return com.overdrive.app.byd.BydDataCollector.ENERGY_MODE_HEV;
            default:                              return null;
        }
    }

    /** Config-axis drive-mode int → option word (inverse of driveModeValue). */
    private static String driveModeWord(int v) {
        switch (v) {
            case 1: return "normal";
            case 2: return "eco";
            case 3: return "sport";
            case 4: return "snow";
            default: return "normal";
        }
    }

    static {
        // ── Climate (composite) ─────────────────────────────────────────
        register(climate((sub, payload, snap) -> {
            if ("mode".equals(sub)) {
                if ("off".equalsIgnoreCase(payload.trim())) {
                    return ControlAction.echo(new VehicleCommandRouter.ClimateOffCommand(), "ac_on", "0");
                }
                double setpoint = 22;
                return ControlAction.echo(new VehicleCommandRouter.ClimateOnCommand(setpoint), "ac_on", "1");
            }
            if ("temperature".equals(sub)) {
                double t = pDouble(payload, 22);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetTempCommand(1, t),
                        "climate_setpoint", String.valueOf(t));
            }
            if ("fan_mode".equals(sub)) {
                int f = pInt(payload, 0);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetFanCommand(f), "ac_fan", String.valueOf(f));
            }
            return null;
        }));
        // The normal HA climate entity keeps its standard in-car 17..33 C controls.
        // This cloud-only text entity supplies OPENAIR's exact 15..31 C / 10..30 minute
        // shape for off-car automations without repurposing a temperature write into a start.
        register(text("remote_climate_start", "Remote Climate Start", "mdi:air-conditioner",
                "config", (sub, payload, snap) -> remoteClimateStartAction(payload)));
        // BOOKINGAIR has no SDK leg, but the command is capability-gated and terminally
        // confirmed by the normal router. A single JSON text control covers create/update/delete.
        register(text("remote_climate_schedule", "Remote Climate Schedule", "mdi:calendar-clock",
                "config", (sub, payload, snap) -> remoteClimateScheduleAction(payload)));

        // ── Windows (all) — cover, command-only (per-window + position: Tier 2) ──
        register(cover("windows_all", "Windows", "mdi:car-door", "window", true, null, (sub, payload, snap) -> {
            // CLOSE-all routes to the dedicated CloseAllWindowsCommand (SDK_FIRST with
            // cloud fallback), NOT the bare local setAllWindowState(2,2,2,2). On this
            // generation the local all-windows CLOSE is unreliable (anti-pinch / the HAL
            // often ignores a simultaneous 4-window raise), which is exactly why the
            // composite cloud CLOSEWINDOW command exists — that's the path that actually
            // raises the windows. OPEN/STOP keep the direct local move (they work locally
            // and must stay instant/offline). This fixes "windows up (close) mapping does
            // nothing" while "down (open) works".
            if ("CLOSE".equalsIgnoreCase(payload)) {
                return ControlAction.of(new VehicleCommandRouter.CloseAllWindowsCommand());
            }
            if ("OPEN".equalsIgnoreCase(payload)) {
                return ControlAction.of(new VehicleCommandRouter.OpenAllWindowsCommand());
            }
            return ControlAction.of(new VehicleCommandRouter.WindowMoveCommand(0, 3, null));
        }));
        // Vent is never a full-drop: it uses local 15% positioning while awake
        // and OPENWINDOW remotely. Keep it separate from the cover so Home
        // Assistant cannot mark a successful vent as a full-open command.
        register(new ControlEntity("windows_vent", "button", "Vent Windows", "mdi:car-door",
                null, true, null, 0, 0, 0, null, null, null, null, null,
                (sub, payload, snap) -> "PRESS".equalsIgnoreCase(payload)
                        ? ControlAction.of(new VehicleCommandRouter.VentAllWindowsCommand()) : null,
                null));

        // ── Tailgate — cover (open is cloud-safe when the keymap allows fallback) ──
        register(cover("tailgate", "Tailgate", "mdi:car-back", "door", true, null, (sub, payload, snap) -> {
            if ("CLOSE".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkCloseCommand());
            if ("STOP".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkStopCommand());
            return ControlAction.of(new VehicleCommandRouter.TrunkOpenCommand());
        }));

        // ── Seat heating (driver/passenger) — select off/low/high ───────
        register(select("seat_heat_driver", "Driver Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_driver", SEAT_LEVELS, (sub, payload, snap) -> {
            int lvl = seatLevel(payload);
            boolean fresh = hasCurrentCompleteSeatState(snap);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(1, lvl,
                    lvl, seatCool(snap, 0), seatHeat(snap, 1), seatCool(snap, 1),
                    true, seatSnapshotAtMs(snap, fresh));
            return ControlAction.echo(c, "seat_heat_driver", SEAT_LEVELS.get(lvl));
        }));
        register(select("seat_heat_passenger", "Passenger Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_passenger", SEAT_LEVELS, (sub, payload, snap) -> {
            int lvl = seatLevel(payload);
            boolean fresh = hasCurrentCompleteSeatState(snap);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), lvl, seatCool(snap, 1),
                    true, seatSnapshotAtMs(snap, fresh));
            return ControlAction.echo(c, "seat_heat_passenger", SEAT_LEVELS.get(lvl));
        }));

        // ── Seat ventilation (driver/passenger) — select ────────────────
        register(select("seat_vent_driver", "Driver Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_driver", SEAT_LEVELS, (sub, payload, snap) -> {
            int lvl = seatLevel(payload);
            boolean fresh = hasCurrentCompleteSeatState(snap);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(1, lvl,
                    seatHeat(snap, 0), lvl, seatHeat(snap, 1), seatCool(snap, 1),
                    true, seatSnapshotAtMs(snap, fresh));
            return ControlAction.echo(c, "seat_vent_driver", SEAT_LEVELS.get(lvl));
        }));
        register(select("seat_vent_passenger", "Passenger Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_passenger", SEAT_LEVELS, (sub, payload, snap) -> {
            int lvl = seatLevel(payload);
            boolean fresh = hasCurrentCompleteSeatState(snap);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), seatHeat(snap, 1), lvl,
                    true, seatSnapshotAtMs(snap, fresh));
            return ControlAction.echo(c, "seat_vent_passenger", SEAT_LEVELS.get(lvl));
        }));

        // ── Steering-wheel heating — switch (live readback where reported) ─
        // SDK-first with the composite cloud fallback (SteeringWheelHeatCommand sends the
        // full front-seat snapshot plus the explicit wheel target atomically, and opts
        // into allowCloudFallbackFromMqtt), so this works from HA even while the car is
        // asleep — same reach as the vehicle-control page tile. State: the
        // steering_wheel_heat telemetry key (setting-HAL readback normalized to 1/0,
        // published only on a confident read — see MqttConnectionManager); the command
        // ALSO echoes optimistically so a cloud-executed press on a trim with no local
        // readback still updates HA instantly. The StateFn makes "toggle" flip the live
        // state where the getter answers.
        register(sw("steering_heat", "Steering Wheel Heating", "mdi:steering", null,
                "steering_wheel_heat", "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.SteeringWheelHeatCommand(truthy(payload)),
                        "steering_wheel_heat", truthy(payload) ? "1" : "0"),
                snap -> {
                    // Raw setting-HAL domain: 2=on, 1=off; anything else = never reported.
                    if (snap == null) return null;
                    if (snap.steeringWheelHeat == 2) return Boolean.TRUE;
                    if (snap.steeringWheelHeat == 1) return Boolean.FALSE;
                    return null;
                }));

        // ── Seat memory recall — buttons ────────────────────────────────
        register(new ControlEntity("seat_memory_driver", "button", "Recall Driver Seat", "mdi:seat-recline-extra",
                null, false, null, 0, 0, 0, null, null, null, null, null,
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.SeatMemoryCommand(1)), null));

        // ── Daytime running lights — switch (real state, toggle-capable) ─
        register(sw("drl", "Daytime Running Lights", "mdi:car-light-dimmed", null, "light_drl", "1", "0",
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.LightsCommand(truthy(payload))),
                snap -> snap == null
                        || !snap.isLightKnown(BydVehicleData.LIGHT_KNOWN_DRL)
                        ? null : snap.dayTimeLight));

        // ── OEM exterior-light selector — four-state Instrument HAL control ──
        // This is not the DRL switch or beam-height adjustment. It mirrors CarSetting's
        // selector exactly: 1=off, 2=auto, 3=parking lights, 4=low beam. OFF is Park-gated
        // by HeadlightModeCommand; all modes are local-only and cannot wake the vehicle.
        register(select("headlight_mode", "Headlight Mode", "mdi:car-light-high", null,
                "headlight_mode", HEADLIGHT_MODES,
                (sub, payload, snap) -> {
                    int mode = headlightModeValue(payload);
                    if (mode == BydVehicleData.UNAVAILABLE) return null;
                    return ControlAction.echo(
                            new VehicleCommandRouter.HeadlightModeCommand(mode),
                            "headlight_mode", payload);
                },
                () -> {
                    int mode = com.overdrive.app.byd.BydDataCollector.getInstance()
                            .readHeadlightModeNow();
                    return mode >= com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_OFF
                            && mode <= com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_LOW_BEAM
                            ? mode - com.overdrive.app.byd.BydDataCollector.HEADLIGHT_MODE_OFF
                            : -1;
                }));

        // ── Hazard (double-flash) lights — switch ────────────────────────────
        // State published to light_hazard (getLightStatus(8) → snap.hazard). The READBACK is
        // ALSO unverified, not just the write: the SDK's light-type table has no hazard entry
        // and position 8 is LIGHT_FOOT (the footwell lamp), so this switch's reported state may
        // actually track the courtesy lighting. See BydDataCollector.collectLight.
        // The SET (double-flash COMMAND feature) is UNCONFIRMED on this firmware — no
        // reference-app precedent and an inferred feature id — so the write may be refused by
        // the HAL; setHazardLights returns false in that case. Validate actuation via
        // GET /api/debug/light/fire?candidate=A before relying on it.
        register(sw("hazard", "Hazard Lights", "mdi:car-light-alert", null, "light_hazard", "1", "0",
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.HazardCommand(truthy(payload))),
                snap -> snap == null
                        || !snap.isLightKnown(
                                BydVehicleData.LIGHT_KNOWN_TURN_HAZARD)
                        ? null : snap.hazard));

        // ── Ambient lights colour — number (real state, 1-based palette index) ──
        // A strict #RRGGBB payload picks the nearest palette colour instead of an index.
        register(number("ambient_colour", "Ambient Lights Colour", "mdi:format-color-fill", "config",
                "ambient_colour", 1, 31, 1, "", (sub, payload, snap) -> {
                    int rgb = payload != null && payload.trim().startsWith("#")
                            ? com.overdrive.app.byd.light.AmbientPalette.parseRgb(payload) : -1;
                    return ControlAction.of(rgb > 0
                            ? VehicleCommandRouter.AmbientColourCommand.rgb(rgb, "both")
                            : new VehicleCommandRouter.AmbientColourCommand(pInt(payload, 1)));
                }));

        // ── Ambient lights main switch — switch (whole cabin) ────────────────
        // State published to ambient_enabled ONLY when the vehicle actually reports it (the
        // Light-device status feature or the atmosphere_lamp flag); a trim that reports neither
        // leaves this unavailable rather than showing a wrong "off". The write walks all three
        // tiers — see BydDataCollector.setAmbientLightEnabled.
        //
        // The live reader gives this switch a real "toggle" (flip the reported state). On a trim
        // that reports NO state the reader returns null, so "toggle" follows the documented
        // default and turns ON every press (see ControlEntity.toAction strategy (a)) — explicit
        // on/off payloads still work either way, which is what HA itself sends.
        register(sw("ambient_power", "Ambient Lights", "mdi:track-light", "config", "ambient_enabled",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AmbientPowerCommand(truthy(payload))),
                snap -> snap == null || snap.ambientEnabled == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        ? null : snap.ambientEnabled == 1));

        // ── Cabin (reading) light — switch; state only when the trim reports it ──
        register(sw("reading_light", "Cabin Light", "mdi:ceiling-light", "config", "reading_light",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.ReadingLightCommand(truthy(payload))),
                snap -> snap == null || snap.readingLight == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        ? null : snap.readingLight == 1));

        // ── Ambient lights brightness — number, whole cabin, 0-100% ──────────
        // Optimistic (echo): the SDK exposes a 0..5 LEVEL per zone, not a whole-cabin percent,
        // so there is no single field to read back — the collector publishes no ambient
        // brightness telemetry. Echoing the commanded value keeps the HA slider in step.
        register(number("ambient_brightness", "Ambient Lights Brightness", "mdi:brightness-6", "config",
                "ambient_brightness", 0, 100, 1, "%", (sub, payload, snap) -> {
                    int v = Math.max(0, Math.min(100, pInt(payload, 0)));
                    return ControlAction.echo(
                            new VehicleCommandRouter.AmbientBrightnessCommand(v), "ambient_brightness",
                            String.valueOf(v));
                }));

        // ── ADAS speed-limit warning — switch (real state, toggle-capable) ─
        register(sw("adas_slw", "Speed Limit Warning", "mdi:speedometer-slow", "config", "speed_limit_warning",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(truthy(payload))),
                snap -> snap == null
                        || (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                            && !snap.speedLimitWarningKnown)
                        ? null : snap.speedLimitWarning));

        // ── Electronic Stability Program (ESP/ESC) — switch ──────────────
        // SAFETY control. State published to esp_state (1=on/0=off); the ESP feature
        // id is a resolveOrFallback guess (unconfirmed on this firmware) — verify via
        // GET /api/vehicle/adas before relying on it. No "problem" device_class: ESP
        // ON is the desired/normal state.
        register(sw("esp_control", "Stability Control (ESP)", "mdi:car-traction-control", "config", "esp_state",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AdasEspCommand(truthy(payload)))));

        // ── iTAC (Intelligent Torque Adaption Control) — switch ──────────
        // Performance/traction feature (NOT the ESP stability interlock). No telemetry
        // state field is published, so the state is optimistic (echo the commanded
        // value). The iTAC feature ids are decoded from the DiLink APK — verify via
        // GET /api/vehicle/adas (itac block) before relying on it.
        register(sw("itac", "iTAC (Torque Control)", "mdi:car-cog", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasItacCommand(truthy(payload)),
                        "itac", truthy(payload) ? "1" : "0")));

        // ── ADAS lane assist — select (Off/LDW/LDP/LDW+LDP), live readback ───
        // Multi-mode via BYDAutoADASDevice.setLKSMode. The payload IS the app-level
        // mode int ("0".."3"); a "toggle"/cycle press advances to the next option using
        // the live getLaneAssistMode readback (parity with the OEM read-then-flip).
        register(select("lane_assist", "Lane Assist", "mdi:road-variant", "config", null,
                java.util.Arrays.asList("0", "1", "2", "3"),
                (sub, payload, snap) -> ControlAction.of(
                        new VehicleCommandRouter.AdasLaneAssistCommand(pInt(payload, 0))),
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getLaneAssistMode()));

        // ── ADAS child presence detection — switch (real state) ──────────────
        // State is published as 1/0 to child_presence_detection (see MqttConnectionManager +
        // TelemetryFieldCatalog): the raw SDK value 1=on/2=off/3=delay is normalized there, so
        // state_on="1"/state_off="0" here match the wire value. Command maps on→1, off→2.
        register(sw("adas_cpd", "Child Presence Detection", "mdi:car-child-seat", "config", "child_presence_detection",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.SettingChildPresenceDetectionCommand(truthy(payload) ? 1 : 2)),
                // Raw childPresenceDetection: 1=on, 2=off, 3=delay. "on" iff == 1.
                snap -> snap == null
                        || snap.childPresenceDetection < 1
                        || snap.childPresenceDetection > 3
                        ? null : (snap.childPresenceDetection == 1)));

        // ── Expanded ADAS matrix ─────────────────────────────────────────────
        // All route to adasDevice via BydDataCollector (feature-id or reflection). No
        // telemetry state field is published for these, so state is optimistic (echo).
        // Feature ids / polarity are per the OEM SDK and UNVERIFIED on every trim —
        // verify via GET /api/vehicle/adas before relying on any given one. The
        // auto-brake / lane-keep entries are SAFETY controls (labelled at the action
        // layer); AEB is enable-only there.
        register(sw("adas_bsd", "Blind Spot Detection", "mdi:car-side", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasBlindSpotCommand(truthy(payload)),
                        "adas_bsd", truthy(payload) ? "1" : "0")));
        register(sw("adas_tsr", "Traffic Sign Recognition", "mdi:sign-real-estate", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasTrafficSignCommand(truthy(payload)),
                        "adas_tsr", truthy(payload) ? "1" : "0")));
        register(sw("adas_rcta", "Rear Cross Traffic Alert", "mdi:car-back", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCrossTrafficCommand(truthy(payload)),
                        "adas_rcta", truthy(payload) ? "1" : "0")));
        register(sw("adas_fcta", "Front Cross Traffic Alert", "mdi:car", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFrontCrossTrafficCommand(truthy(payload)),
                        "adas_fcta", truthy(payload) ? "1" : "0")));
        register(sw("adas_tla", "Traffic Light Attention", "mdi:traffic-light", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasTrafficLightAttentionCommand(truthy(payload)),
                        "adas_tla", truthy(payload) ? "1" : "0")));
        register(sw("adas_dow", "Door Open Warning", "mdi:car-door", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasOpenDoorWarningCommand(truthy(payload)),
                        "adas_dow", truthy(payload) ? "1" : "0")));
        register(sw("adas_rcw", "Rear Collision Warning", "mdi:car-back", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCollisionWarningCommand(truthy(payload)),
                        "adas_rcw", truthy(payload) ? "1" : "0")));
        register(sw("adas_islc", "Speed Limit Control", "mdi:speedometer", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasSpeedLimitControlCommand(truthy(payload)),
                        "adas_islc", truthy(payload) ? "1" : "0")));
        register(sw("adas_elka", "Emergency Lane Keeping", "mdi:road-variant", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasEmergencyLaneKeepCommand(truthy(payload)),
                        "adas_elka", truthy(payload) ? "1" : "0")));
        register(sw("adas_rctb", "Rear Cross Traffic Brake", "mdi:car-brake-alert", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCrossBrakeCommand(truthy(payload)),
                        "adas_rctb", truthy(payload) ? "1" : "0")));
        register(sw("adas_fctb", "Front Cross Traffic Brake", "mdi:car-brake-alert", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFrontCrossBrakeCommand(truthy(payload)),
                        "adas_fctb", truthy(payload) ? "1" : "0")));
        register(sw("adas_aeb", "Automatic Emergency Braking", "mdi:car-brake-abs", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasEmergencyBrakingCommand(truthy(payload)),
                        "adas_aeb", truthy(payload) ? "1" : "0")));
        register(select("adas_fcw", "Forward Collision Warning", "mdi:car-emergency", "config", null,
                java.util.Arrays.asList("0", "1", "2", "3"),
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFcwLevelCommand(pInt(payload, 0)),
                        "adas_fcw", String.valueOf(pInt(payload, 0)))));

        // ── Charge cap (BEV) — switch + number, verified state ──────────
        register(sw("charge_cap_enabled", "Charge Limit", "mdi:battery-charging-100", "config",
                "charge_cap_enabled", "1", "0",
                (sub, payload, snap) -> {
                    Boolean enabled = strictBoolean(payload);
                    return enabled == null ? null : ControlAction.echo(
                            new VehicleCommandRouter.ChargeCapToggleCommand(enabled.booleanValue()),
                            "charge_cap_enabled", enabled.booleanValue() ? "1" : "0");
                }));
        register(number("charge_cap_percent", "Charge Limit %", "mdi:battery-charging-80", "config",
                "charge_cap_percent", 50, 100, 5, "%", (sub, payload, snap) -> {
            Integer percent = chargeCapPercent(payload);
            return percent == null ? null : ControlAction.echo(
                    new VehicleCommandRouter.ChargeCapPercentCommand(percent.intValue()),
                    "charge_cap_percent", String.valueOf(percent));
        }));
        // Smart charging is cloud-only, but it is explicitly safe for the MQTT router's
        // normal route: each command is capability-gated and terminally confirmed.
        register(sw("smart_charging", "Smart Charging", "mdi:battery-clock", "config",
                null, "1", "0", (sub, payload, snap) -> {
                    Boolean enabled = strictBoolean(payload);
                    return enabled == null ? null : ControlAction.echo(
                            new VehicleCommandRouter.SmartChargingToggleCommand(
                                    enabled.booleanValue()),
                            "smart_charging", enabled.booleanValue() ? "1" : "0");
                }));
        register(new ControlEntity("start_charging_now", "button", "Start Charging Now",
                "mdi:battery-charging", null, false, null, 0, 0, 0,
                null, null, null, null, null,
                (sub, payload, snap) -> "PRESS".equalsIgnoreCase(payload)
                        ? ControlAction.of(new VehicleCommandRouter.StartChargingNowCommand())
                        : null,
                null));
        register(text("smart_charge_schedule", "Smart Charging Schedule",
                "mdi:calendar-clock", "config",
                (sub, payload, snap) -> smartChargeScheduleAction(payload)));

        // ── Tier 2: sunroof / sunshade (covers) + child lock + wireless charger ──
        register(cover("sunroof", "Sunroof", "mdi:window-shutter-open", "window", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunroofCommand(cmd));
        }));
        register(cover("sunshade", "Sunshade", "mdi:blinds", "shade", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunshadeCommand(cmd));
        }));
        register(sw("child_lock", "Child Lock", "mdi:car-door-lock", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.ChildLockCommand(truthy(payload)),
                        "child_lock", truthy(payload) ? "1" : "0")));
        // Mirror fold/unfold — set-only (no fold-state getter on this platform), so
        // like child_lock it echoes the commanded value to the last-command cache;
        // a "toggle" press flips off that cache (blind toggle). on=fold, off=unfold.
        register(sw("mirror_fold", "Fold Mirrors", "mdi:car-side", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.MirrorFoldCommand(truthy(payload)),
                        "mirror_fold", truthy(payload) ? "1" : "0")));
        // Persistent OEM preference, distinct from mirror_fold's immediate bodywork command.
        // The vehicle owns the actual fold/unfold when its power state changes, so this setting
        // can be enabled while awake even on a trim that rejects manual mirror commands.
        register(sw("mirror_auto_follow_up", "Auto Fold / Unfold Mirrors", "mdi:car-side", "config",
                null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.MirrorAutoFollowUpCommand(truthy(payload)),
                        "mirror_auto_follow_up", truthy(payload) ? "1" : "0")));
        register(sw("wireless_charging", "Phone Wireless Charger", "mdi:battery-charging-wireless", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.WirelessChargingCommand(truthy(payload)),
                        "wireless_charging", truthy(payload) ? "1" : "0")));
        // Per-pad wireless charging for dual-pad trims. Independent of the global switch above.
        // These controls are optimistic; the same-named telemetry fields report charging activity,
        // not switch position, so a command must not overwrite them with its desired state.
        // left=pad 0, right=pad 1.
        register(sw("wireless_charging_left", "Wireless Charger (Left)", "mdi:battery-charging-wireless", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.of(
                        new VehicleCommandRouter.WirelessChargingPadCommand(
                                com.overdrive.app.byd.BydDataCollector.WIRELESS_PAD_LEFT, truthy(payload)))));
        register(sw("wireless_charging_right", "Wireless Charger (Right)", "mdi:battery-charging-wireless", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.of(
                        new VehicleCommandRouter.WirelessChargingPadCommand(
                                com.overdrive.app.byd.BydDataCollector.WIRELESS_PAD_RIGHT, truthy(payload)))));

        // ── Infotainment orientation + OEM native panorama view ─────────
        // These are command-only selects: the connected SDK exposes a setter but no reliable
        // orientation getter, and the native camera receiver publishes no selected-view state.
        // Key mapping may additionally send "toggle" to the rotation select; the generic
        // readback-less select cycle alternates horizontal ↔ vertical from its command cache.
        register(select("infotainment_rotation", "Infotainment Orientation",
                "mdi:screen-rotation", "config", null, INFOTAINMENT_ROTATIONS,
                (sub, payload, snap) -> {
                    int rotation = infotainmentRotationValue(payload);
                    return rotation < 0 ? null : ControlAction.of(
                            new VehicleCommandRouter.InfotainmentRotationCommand(rotation));
        }));
        // OEM camera-view codes. This controls the native panorama app, not OverDrive's
        // /api/camview SurfaceControl overlay. It sends a view command only and never opens
        // the native panorama UI.
        register(select("native_camera_view", "Native Camera View", "mdi:camera-switch",
                null, null, NATIVE_CAMERA_VIEWS, (sub, payload, snap) -> {
                    int viewCode = nativeCameraViewCode(payload);
                    return viewCode < 0 ? null : ControlAction.of(
                            new VehicleCommandRouter.NativeCameraViewCommand(viewCode));
                }));

        // ── Drive / energy modes (BYDAutoEnergyDevice / SettingDevice) ────
        // select entities: HA renders a dropdown, keymap binds one option, the
        // automation UI exposes the same option set. Payloads are readable words
        // mapped to the BYD SDK int enum; pInt() also accepts a raw int.
        // The collector resolves all drive-mode sources to Normal=1, Eco=2, Sport=3, Snow=4.
        // A confirmed command echo uses the corresponding option word on the same topic.
        register(select("drive_mode", "Drive Mode", "mdi:car-shift-pattern", null, "op_mode",
                DRIVE_MODES,
                "{% set m = value | int(-1) %}{{ 'normal' if m == 1 else 'eco' if m == 2 else 'sport' if m == 3 else 'snow' if m == 4 else value }}",
                (sub, payload, snap) -> {
                    int m = driveModeValue(payload);
                    return ControlAction.echo(new VehicleCommandRouter.OperationModeCommand(m),
                            "op_mode", driveModeWord(m));
                }));
        // powertrain_mode: energy_mode telemetry is the raw SDK energy-mode int
        // (ENERGY_MODE_EV=1, ENERGY_MODE_HEV=3; NOTE 0=ENERGY_MODE_STOP, NOT ev).
        // The old code sent setEnergyMode(0) for EV — which is STOP — so EV never
        // engaged. Map the words to the real SDK ints and mirror them on the
        // state topic.
        // Only field-validated writes are exposed. Raw telemetry still decodes the complete SDK
        // enum so a vehicle already reporting 2/4/5 does not lose observability.
        final List<String> POWERTRAIN = java.util.Arrays.asList("ev", "hev");
        register(select("powertrain_mode", "Powertrain Mode", "mdi:engine", null, "energy_mode",
                POWERTRAIN,
                "{% set m = value | int(-1) %}{{ 'ev' if m == 1 else 'force_ev' if m == 2 else "
                        + "'hev' if m == 3 else 'fuel' if m == 4 else 'keep' if m == 5 else value }}",
                (sub, payload, snap) -> {
                    Integer m = powertrainModeValue(payload);
                    if (m == null) return null;
                    return ControlAction.echo(new VehicleCommandRouter.EnergyModeCommand(m),
                            "energy_mode", com.overdrive.app.byd.BydDataCollector.energyModeName(m));
                }));
        // hold_battery: a friendly alias for "switch to HEV". NOTE this does NOT hold the
        // pack at its current level — field-reported on a SeaLion 6 DM-i: selecting HEV
        // starts the ICE and RECHARGES the battery. Renamed to say what it actually does;
        // the key is unchanged so existing automations/keymaps keep working. Use
        // battery_hold below for a genuine hold. Any payload commands HEV (3).
        register(select("hold_battery", "Engine Mode (HEV)", "mdi:engine", null, "energy_mode",
                java.util.Arrays.asList("on"),
                "{% set m = value | int(-1) %}{{ 'on' if m == 3 else 'off' }}",
                (sub, payload, snap) ->
                    ControlAction.echo(new VehicleCommandRouter.EnergyModeCommand(3), "energy_mode", "hev")));
        // battery_hold: THE REAL SOC-hold lever, as a single one-tap control. Writes both legs of
        // the pair — the hold switch first, then the target level (see applySocHold for why that
        // order, which deliberately differs from the OEM's):
        //   at_current → switch 2 + min(SOC,50) — "keep the charge I have" (the Highway preset)
        //   at_target  → switch 2, preserving the configured target_soc
        //   at_floor   → switch 1 + this trim's floor — "let it deplete" (the City preset)
        //   off        → switch 0, target untouched
        // No telemetry field reports the hold state (getSocSaveSwitch does not exist in the SDK
        // or on any trim), so this is set-only and reports no state.
        // OPTION ORDER IS DELIBERATE: off → at_current → at_target → at_floor.
        // intervention. No UI offers "toggle" for this entity (it is declared per-entry in the
        // keymap catalog and the automation picker, and HA is sent only these three options), but
        // a hand-published MQTT "toggle" still reaches toAction's no-readback cycle, which
        // defaults to index 0 and advances — so the first such press must land on the MILDEST
        // change. With this order that is at_current ("keep what I have"); the previous order put
        // at_floor there, silently permitting the pack to run down to the reserve.
        register(select("battery_hold", "Battery Hold", "mdi:battery-lock", null, null,
                java.util.Arrays.asList("off", "at_current", "at_target", "at_floor"),
                (sub, payload, snap) -> {
                    String p = payload == null ? "" : payload.trim().toLowerCase();
                    if ("off".equals(p)) {
                        return ControlAction.of(new VehicleCommandRouter.SocHoldToggleCommand(false));
                    }
                    if ("at_target".equals(p)) {
                        return ControlAction.of(new VehicleCommandRouter.SocHoldToggleCommand(true));
                    }
                    // "toggle" never arrives here — toAction rewrites it to a concrete option
                    // first. Anything unrecognised → at_current, the intent behind the name.
                    return ControlAction.of(new VehicleCommandRouter.SocHoldPresetCommand(!"at_floor".equals(p)));
                }));
        register(number("target_soc", "Target SOC", "mdi:battery-sync", "config",
                "target_soc", BydDataCollector.SOC_TARGET_MIN, BydDataCollector.SOC_TARGET_MAX,
                1, "%", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.SocTargetPercentCommand(
                                pInt(payload, BydDataCollector.SOC_TARGET_MIN)))));
        // regen_level: normalized user level fed to BydDataCollector.setEnergyFeedback,
        // which maps 0..2 -> MCU 2..4. standard = 0 (SETTING_ENERGY_FEEDBACK_STANDARD),
        // high = 1 (SETTING_ENERGY_FEEDBACK_LARGE) — per the OEM firmware convention.
        // (Previously sent 1/2, which the setter forwarded raw: 1 was below the valid
        // MCU range and 2 was the HAL's *standard*, so standard no-op'd and high set
        // standard.)
        register(select("regen_level", "Energy Recuperation", "mdi:battery-charging-medium", null, null,
                java.util.Arrays.asList("standard", "high"),
                (sub, payload, snap) -> {
                    int lvl = "high".equalsIgnoreCase(payload.trim()) ? 1   // SETTING_ENERGY_FEEDBACK_LARGE
                            : "standard".equalsIgnoreCase(payload.trim()) ? 0 // SETTING_ENERGY_FEEDBACK_STANDARD
                            : pInt(payload, 0);
                    return ControlAction.of(new VehicleCommandRouter.EnergyFeedbackCommand(lvl));
                },
                // Live readback for toggle: getEnergyFeedback returns app-level 0/1/2;
                // options are [standard(0), high(1)], so clamp a "max"(2) read to high(1).
                () -> {
                    int lvl = com.overdrive.app.byd.BydDataCollector.getInstance().getEnergyFeedback();
                    return lvl < 0 ? -1 : Math.min(lvl, 1);
                }));
        // steering_mode: SET_DR_ST_ASSIS_COMFORT = 1, SET_DR_ST_ASSIS_SPORT = 2
        // (there is no 0). Old code sent 0/1 → the HAL rejected 0.
        register(select("steering_mode", "Steering Assist", "mdi:steering", null, null,
                java.util.Arrays.asList("comfort", "sport"),
                (sub, payload, snap) -> {
                    int m = "sport".equalsIgnoreCase(payload.trim()) ? 2    // SET_DR_ST_ASSIS_SPORT
                          : "comfort".equalsIgnoreCase(payload.trim()) ? 1  // SET_DR_ST_ASSIS_COMFORT
                          : pInt(payload, 1);
                    return ControlAction.of(new VehicleCommandRouter.SteerAssistCommand(m));
                },
                // Live readback for toggle: getSteerAssist returns app-level 0=comfort/1=sport.
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getSteerAssist()));
        // brake_feel: brake-pedal feel comfort vs sport/strong (BYDAutoADASDevice
        // setBrakeFootSenseState). App-level 0=comfort/1=sport; the collector maps to
        // the HAL value (comfort→2, sport→0). No telemetry state field, so optimistic.
        register(select("brake_feel", "Brake Feel", "mdi:car-brake-alert", null, null,
                java.util.Arrays.asList("comfort", "sport"),
                (sub, payload, snap) -> {
                    int lvl = "sport".equalsIgnoreCase(payload.trim()) ? 1
                            : "comfort".equalsIgnoreCase(payload.trim()) ? 0
                            : pInt(payload, 0);
                    return ControlAction.of(new VehicleCommandRouter.BrakeFeelCommand(lvl));
                },
                // Live readback for toggle: getBrakeFootSense returns app-level 0=comfort/1=sport.
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getBrakeFootSense()));

        // ── Tier 3: curated CAN-backed car settings (local carsettings provider) ──
        for (com.overdrive.app.byd.BydCarSettings.CarSetting s : com.overdrive.app.byd.BydCarSettings.registry()) {
            final String key = s.key;
            final String stateKey = "setting_" + s.key;
            final int sMin = s.min, sMax = s.max, sStep = s.step;
            final int[] sOpts = s.options;
            switch (s.kind) {
                case BOOL: {
                    register(sw(stateKey, s.name, s.icon, "config", stateKey, "1", "0",
                            (sub, payload, snap) -> ControlAction.echo(
                                    new VehicleCommandRouter.CarSettingCommand(key, truthy(payload) ? 1 : 0),
                                    stateKey, truthy(payload) ? "1" : "0")));
                    break;
                }
                case INT_RANGE: {
                    register(number(stateKey, s.name, s.icon, "config", stateKey, sMin, sMax, sStep, s.unit,
                            (sub, payload, snap) -> {
                                int v = Math.max(sMin, Math.min(sMax, pInt(payload, sMin)));
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
                case INT_ENUM: {
                    java.util.List<String> opts = new java.util.ArrayList<>();
                    for (int o : sOpts) opts.add(String.valueOf(o));
                    register(select(stateKey, s.name, s.icon, "config", stateKey, opts,
                            (sub, payload, snap) -> {
                                int v = pInt(payload, sOpts.length > 0 ? sOpts[0] : 0);
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
            }
        }
    }
}
