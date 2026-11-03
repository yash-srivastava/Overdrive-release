package com.overdrive.app.remoteaccess;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable translation codes for the English texts the proximity API reports (Activity events, the
 * last action's reason and request errors).
 *
 * <p>Display only. The English text stays the source of truth: it is still recorded, logged,
 * de-duplicated and sent in the existing {@code message}/{@code reason}/{@code error} fields
 * exactly as before. {@link #annotate} only ADDS {@code <field>Key} (a key under the web catalog's
 * {@code remote_access.server} section), {@code <field>Args} (named values for its {@code {name}}
 * placeholders) and {@code <field>ArgKeys} (a catalog key for an argument that is itself a known
 * phrase). A text with no code gets no extra fields, so the page shows the English text as today.
 * No decision, guard, log line or notification reads these fields.
 */
public final class RemoteAccessMessageCodes {

    /** A classified text: catalog key, named arguments and the optional catalog key of an argument. */
    public static final class Code {
        public final String key;
        public final Map<String, String> args;
        public final Map<String, String> argKeys;

        Code(String key, Map<String, String> args, Map<String, String> argKeys) {
            this.key = key;
            this.args = Collections.unmodifiableMap(args);
            this.argKeys = Collections.unmodifiableMap(argKeys);
        }
    }

    private static final class Rule {
        final Pattern pattern;
        final String key;
        final String[] names;

        Rule(String regex, String key, String... names) {
            this.pattern = Pattern.compile(regex);
            this.key = key;
            this.names = names;
        }
    }

    /** Technical suffix of DI5 texts, " (rail=…)" or " (path=… rail=…)", kept verbatim as {detail}. */
    private static final String DETAIL = "((?: \\((?:rail|path)=[^)]*\\))?)";
    /** A nested phrase (cloud leg result or skip reason) up to the optional technical suffix. */
    private static final String PHRASE = "(.+?)";

    private static final List<Rule> RULES = new ArrayList<>();
    /** Exact short phrases that appear inside other texts (cloud leg results, skip reasons, outcomes). */
    private static final Map<String, String> PHRASES = new HashMap<>();

    private static void rule(String regex, String key, String... names) {
        RULES.add(new Rule("^" + regex + "$", key, names));
    }

    private static void exact(String text, String key) {
        rule(Pattern.quote(text), key);
    }

    static {
        // Settings events.
        rule("Shared parked Bluetooth on \\((.*)\\)", "shared_bluetooth_on", "outcome");
        rule("Shared parked Bluetooth off \\((.*)\\)", "shared_bluetooth_off", "outcome");
        exact("Monitoring could not start; master switch is off", "monitoring_start_failed");
        exact("Proximity monitoring enabled", "monitoring_enabled");
        exact("Master switch off", "master_switch_off");

        // Arrival / departure transitions.
        exact("Stable arrival", "stable_arrival");
        exact("Stable departure", "stable_departure");
        exact("Fresh physical key fob arrival", "keyfob_arrival");
        exact("Fresh physical key fob departure", "keyfob_departure");
        exact("Confirmed physical key fob absence", "keyfob_absence");

        // Interrupted local actions.
        exact("Local action interrupted or unavailable", "local_action_interrupted");
        exact("Local key fob action interrupted or unavailable", "keyfob_action_interrupted");
        exact("Local physical key search action interrupted or unavailable", "key_search_action_interrupted");

        // Blocked actions.
        exact("Automatic unlock is disabled", "auto_unlock_disabled");
        exact("Automatic lock is disabled", "auto_lock_disabled");
        exact("Safe-zone restriction paused this action", "safe_zone_paused");
        exact("Safe-zone selection or geometry changed before the command", "safe_zone_changed");
        exact("Automatic action paused inside a selected safe zone", "safe_zone_inside");
        exact("Automatic action paused near a safe-zone boundary", "safe_zone_boundary");
        exact("A selected safe zone was removed; review the safe-zone selection", "safe_zone_missing");
        exact("Selected safe-zone geometry is unavailable or invalid", "safe_zone_invalid");
        exact("Choose a safe zone before restricting automatic actions", "safe_zone_none_selected");
        exact("Automatic action paused because the GPS fix is stale", "gps_fix_stale");
        exact("Automatic action paused because GPS accuracy is insufficient", "gps_accuracy_poor");
        exact("Proximity is disabled", "proximity_disabled");
        exact("Automatic action paused until a fresh, reliable GPS position is available", "gps_unavailable");
        exact("Driver door is already locked; the key fob search is paused while locked", "keyfob_search_paused_locked");
        exact("Every active Bluetooth phone must confirm Away; a nearby key prevents locking", "phones_must_confirm_away");
        exact("Fresh confirmed keyfob departure is required", "keyfob_departure_required");
        exact("Newer signal revoked this transition", "newer_signal_revoked");
        exact("Learning mode: no vehicle command sent", "learning_mode_no_command");
        exact("Approach reading expired before the command", "approach_expired");
        exact("Vehicle must be confirmed off", "vehicle_must_be_off");
        exact("Local lock state is unknown", "lock_state_unknown");
        exact("Signal expired during driver-door readback", "signal_expired_readback");
        rule("Driver door is already unlocked" + DETAIL, "already_unlocked", "detail");
        rule("Driver door is already locked" + DETAIL, "already_locked", "detail");
        exact("Cooldown active", "cooldown_active");
        rule("Relock grace: a door must open or (\\d+) s must pass after the proximity unlock", "relock_grace", "seconds");
        exact("Door and lid state unavailable", "doors_unavailable");
        rule("Close all doors, hood and trunk: (.+)", "close_all_doors", "doors");
        exact("Signal or vehicle state changed before command", "state_changed_before_command");
        exact("Signal or lock state expired before command", "lock_state_expired_before_command");
        exact("Insufficient lease left for local actuation", "insufficient_lease");
        exact("Command already in progress", "command_in_progress");

        // Command outcomes.
        rule("Local command accepted; awaiting driver-door readback" + DETAIL, "local_accepted_awaiting", "detail");
        exact("Command sent; vehicle confirmation off", "sent_confirmation_off");
        exact("Driver-door lock state confirmed", "lock_state_confirmed");
        exact("Command accepted; driver-door change could not be confirmed", "accepted_unconfirmed");
        exact("Driver door still locked; local command re-sent", "still_locked_resent");
        rule("Cloud command accepted; awaiting driver-door readback" + DETAIL, "cloud_accepted_awaiting", "detail");
        rule("Driver-door lock state confirmed via local command; cloud command not sent" + DETAIL,
                "confirmed_local_cloud_not_sent", "detail");
        rule("Driver-door lock state confirmed without a command \\(local command failed: (.*)\\); cloud command not sent" + DETAIL,
                "confirmed_without_command_cloud_not_sent", "failure", "detail");
        rule("Driver-door lock state confirmed via cloud command" + DETAIL, "confirmed_via_cloud", "detail");
        rule("Driver-door lock state confirmed via local command; cloud command failed: " + PHRASE + DETAIL,
                "confirmed_local_cloud_failed", "cloud", "detail");
        rule("Driver-door lock state confirmed without a command \\(local command failed: (.*)\\); cloud command failed: " + PHRASE + DETAIL,
                "confirmed_without_command_cloud_failed", "failure", "cloud", "detail");
        rule("Cloud command acknowledged; driver-door change could not be confirmed locally" + DETAIL,
                "cloud_acknowledged_unconfirmed", "detail");
        rule("Command accepted; driver-door change could not be confirmed; cloud command not sent: " + PHRASE + DETAIL,
                "accepted_unconfirmed_cloud_not_sent", "skipped", "detail");
        rule("Local command failed \\((.*)\\); cloud command not sent: " + PHRASE + DETAIL,
                "local_failed_cloud_not_sent", "failure", "skipped", "detail");
        rule("Local command unconfirmed; cloud command failed: " + PHRASE + DETAIL,
                "local_unconfirmed_cloud_failed", "cloud", "detail");
        rule("Local command failed \\((.*)\\); cloud command failed: " + PHRASE + DETAIL,
                "local_failed_cloud_failed", "failure", "cloud", "detail");
        exact("Cloud command acknowledged; local confirmation stopped by a newer lock decision", "cloud_acknowledged_superseded_lock");
        exact("Cloud command acknowledged; local confirmation stopped by a newer unlock decision", "cloud_acknowledged_superseded_unlock");
        exact("Command accepted; confirmation stopped by a newer lock decision", "accepted_superseded_lock");
        exact("Command accepted; confirmation stopped by a newer unlock decision", "accepted_superseded_unlock");
        rule("Local command failed \\((.*)\\); cloud command abandoned for a newer lock decision", "local_failed_superseded_lock", "failure");
        rule("Local command failed \\((.*)\\); cloud command abandoned for a newer unlock decision", "local_failed_superseded_unlock", "failure");

        // Request errors shown on the page.
        exact("The car app is unavailable. Open OverDrive and retry.", "car_app_unavailable");
        exact("Remote access is temporarily unavailable", "temporarily_unavailable");
        exact("Select an enabled trusted phone", "select_enabled_phone");
        exact("Enable Advanced mode to capture calibration values", "calibration_needs_advanced");
        exact("Choose near or away", "choose_near_or_away");
        exact("Wait for at least three fresh connected readings", "calibration_needs_readings");
        exact("Near threshold must be higher than away threshold", "near_above_away");
        exact("Key fob locking window must use 5-second steps", "keyfob_window_steps");
        exact("Enable key fob detection before key fob locking", "keyfob_detection_first");
        exact("Select at least one safe zone for the proximity restriction", "select_safe_zone");
        exact("Select a valid paired Bluetooth device", "select_paired_device");
        exact("Selected phone must be in the trusted list", "phone_not_trusted");
        exact("Enable key fob detection or add and enable a trusted paired phone", "needs_keyfob_or_phone");
        exact("Select a trusted phone for Bluetooth readings", "select_reading_phone");
        exact("At most eight trusted phones are supported", "too_many_phones");
        exact("Each trusted phone needs a unique valid Bluetooth address", "phone_address_invalid");
        exact("Phone name is too long", "phone_name_too_long");
        exact("Each phone's near threshold must exceed away within [-127,20]", "phone_thresholds_invalid");
        exact("At most ten proximity safe zones are supported", "too_many_safe_zones");
        exact("Select unique valid safe zone IDs", "safe_zone_ids_invalid");
        exact("Open the car app before enabling monitoring", "open_car_app_first");
        exact("Every enabled trusted phone must be paired with the car", "phones_must_be_paired");

        // Named-phone lock vetoes. Last: the phone name is free text.
        rule("(.+) has not connected since (\\d+) min; bring it to the car once or disable it",
                "phone_never_connected_min", "name", "minutes");
        rule("(.+) has not connected since (\\d+) s; bring it to the car once or disable it",
                "phone_never_connected_s", "name", "seconds");
        rule("(.+) is connected but not confirmed away", "phone_connected_not_away", "name");
        rule("(.+) is disconnected but not confirmed away", "phone_disconnected_not_away", "name");
        rule("(.+) is near", "phone_is_near", "name");

        // Nested phrases: cloud leg results, cloud skip reasons and collector outcomes.
        PHRASES.put("cloud not configured", "phrase_cloud_not_configured");
        PHRASES.put("cloud leg not available for this command", "phrase_cloud_not_available");
        PHRASES.put("blocked: vehicle not confirmed parked", "phrase_not_parked");
        PHRASES.put("vehicle not confirmed parked", "phrase_not_parked");
        PHRASES.put("acknowledged", "phrase_acknowledged");
        PHRASES.put("timeout", "phrase_timeout");
        PHRASES.put("car offline in BYD cloud", "phrase_car_offline");
        PHRASES.put("settings changed", "phrase_settings_changed");
        PHRASES.put("cloud fallback switched off", "phrase_cloud_fallback_off");
        PHRASES.put("Learning mode", "phrase_learning_mode");
        PHRASES.put("decision revoked", "phrase_decision_revoked");
        PHRASES.put("vehicle no longer off", "phrase_vehicle_not_off");
        PHRASES.put("door and lid state unavailable", "phrase_doors_unavailable");
        PHRASES.put(DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET, "phrase_local_at_target");
        PHRASES.put("decision budget exhausted", "phrase_budget_exhausted");
        PHRASES.put("master switch off", "phrase_master_switch_off");
        PHRASES.put("collector started/refreshed; active=true", "phrase_collector_active");
        PHRASES.put("collector started/refreshed; active=false", "phrase_collector_inactive");
        PHRASES.put("collector not started: car app unavailable", "phrase_collector_no_app");
    }

    private RemoteAccessMessageCodes() {}

    /** The code of {@code message}, or null when it has none (unknown or free text). */
    public static Code classify(String message) {
        if (message == null || message.isEmpty()) return null;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(message);
            if (!matcher.matches()) continue;
            Map<String, String> args = new LinkedHashMap<>();
            Map<String, String> argKeys = new LinkedHashMap<>();
            for (int i = 0; i < rule.names.length; i++) {
                String value = matcher.group(i + 1);
                args.put(rule.names[i], value == null ? "" : value);
                String phrase = phraseKey(value);
                if (phrase != null) argKeys.put(rule.names[i], phrase);
            }
            return new Code(rule.key, args, argKeys);
        }
        return null;
    }

    /** Key of a nested phrase: a known short phrase, or a full text with a code and no arguments. */
    static String phraseKey(String value) {
        if (value == null || value.isEmpty()) return null;
        String phrase = PHRASES.get(value);
        if (phrase != null) return phrase;
        for (Rule rule : RULES) {
            if (rule.names.length == 0 && rule.pattern.matcher(value).matches()) return rule.key;
        }
        return null;
    }

    /**
     * Adds {@code <field>Key}, {@code <field>Args} and {@code <field>ArgKeys} for {@code message}
     * to {@code target} when it has a code; otherwise leaves {@code target} untouched. Never throws.
     */
    public static JSONObject annotate(JSONObject target, String field, String message) {
        if (target == null) return null;
        try {
            Code code = classify(message);
            if (code == null) return target;
            target.put(field + "Key", code.key);
            if (!code.args.isEmpty()) target.put(field + "Args", new JSONObject(code.args));
            if (!code.argKeys.isEmpty()) target.put(field + "ArgKeys", new JSONObject(code.argKeys));
        } catch (Exception ignored) {
            // Display metadata only: the English text alone is still a complete answer.
        }
        return target;
    }
}
