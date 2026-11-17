package com.overdrive.app.voice.daemon;

import com.overdrive.app.automation.AutomationAction;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daemon-owned allowlist deciding whether a saved automation may be run hands-free.
 *
 * <p>A spoken "run my evening routine" must not become a back door around the per-intent
 * tiers of the local voice path: an automation can hide shell commands, door locks or an
 * IVI reboot behind a friendly name. The walker therefore refuses the WHOLE automation unless
 * every action it can reach (primary list, if/loop bodies, action groups) is an allowlisted
 * key. Fail closed: null, unknown, a group cycle or excessive nesting all refuse.
 * Pure Java (no logging, no Android) so it is JVM-testable; the caller logs.
 */
public final class VoiceExecutionPolicy {

    /** Resolves an action group's body by id; production passes {@code ActionGroups::getActions}. */
    public interface GroupResolver {
        List<AutomationAction> actionsOf(String groupId);
    }

    public static final class Verdict {
        public final boolean allowed;
        public final String offendingType;
        public final String message;

        private Verdict(boolean allowed, String offendingType, String message) {
            this.allowed = allowed;
            this.offendingType = offendingType;
            this.message = message;
        }

        static Verdict allowed() {
            return new Verdict(true, null, null);
        }

        static Verdict refused(String offendingType) {
            return new Verdict(false, offendingType,
                    "That automation includes an action that can't be run by voice ("
                            + offendingType + ").");
        }
    }

    /** Same cap as Automations.MAX_RUN_DEPTH; deeper chains would not run anyway. */
    static final int MAX_DEPTH = 16;

    private static final String TYPE_IF = "if";
    private static final String TYPE_LOOP = "loop";
    private static final String TYPE_ACTION_GROUP = "actionGroup";
    private static final String TYPE_WINDOWS_ALL = "windowsAll";
    private static final String WINDOWS_CLOSE = "2";

    /**
     * Action keys (Label ids) that map onto tier 1-2 voice intents or are benign UI.
     *
     * <p>App launching (incl. split screen), media keys/volume, library audio playback,
     * daytime running lights and HUD/screen power are allowed too: none of them unlock,
     * move or power the vehicle, run shell/HTTP, or reach another automation. Headlight
     * level, Play Video, locks, power and the shell/API escape hatches stay refused.
     */
    static final Set<String> ALLOWLIST = new HashSet<>(Arrays.asList(
            "setAc", "setAcTemp", "stepAcTemp", "setAcFan", "stepAcFan", "recirculation", "airflow", "maxCool",
            "acAuto", "frontDefrost", "rearDefrost",
            "seat", "steeringHeat",
            "sunshade", "windowsVent", TYPE_WINDOWS_ALL,
            "ambientPower", "manualClip",
            "openApp", "openAppSplit", "openAppsSplit",
            "mediaControl", "mediaVolume", "channelVolume", "engineSound", "playAudio", "stopAudio",
            "drl", "hudPower", "screenPower",
            "notification", "showToast", "showDialog", "speak", "pause",
            "setVariable", "incrementVariable", "computeVariable", "captureVariable"));

    private VoiceExecutionPolicy() {}

    public static Verdict check(List<AutomationAction> primary, GroupResolver groups) {
        return walk(primary, groups, new HashSet<>(), 0);
    }

    private static Verdict walk(List<AutomationAction> actions, GroupResolver groups,
                                Set<String> visitedGroups, int depth) {
        if (depth > MAX_DEPTH) return Verdict.refused("depth");
        if (actions == null) return Verdict.allowed();
        for (AutomationAction action : actions) {
            if (action == null) return Verdict.refused("null");
            String type = action.getType();
            if (type == null) return Verdict.refused("null");
            if (TYPE_IF.equals(type) || TYPE_LOOP.equals(type)) {
                Verdict body = walk(action.getChildActions(), groups, visitedGroups, depth + 1);
                if (!body.allowed) return body;
                Verdict otherwise = walk(action.getElseChildActions(), groups, visitedGroups,
                        depth + 1);
                if (!otherwise.allowed) return otherwise;
                continue;
            }
            if (TYPE_ACTION_GROUP.equals(type)) {
                Verdict group = walkGroup(action, groups, visitedGroups, depth + 1);
                if (!group.allowed) return group;
                continue;
            }
            if (!ALLOWLIST.contains(type)) return Verdict.refused(type);
            if (TYPE_WINDOWS_ALL.equals(type) && !closesWindows(action)) {
                return Verdict.refused(type);
            }
        }
        return Verdict.allowed();
    }

    private static Verdict walkGroup(AutomationAction action, GroupResolver groups,
                                     Set<String> visitedGroups, int depth) {
        Map<String, Object> variables = action.getVariables();
        Object raw = variables == null ? null : variables.get("groupId");
        String groupId = raw == null ? "" : raw.toString().trim();
        if (groupId.isEmpty()) return Verdict.refused(TYPE_ACTION_GROUP);
        if (!visitedGroups.add(groupId)) return Verdict.refused("cycle");
        try {
            List<AutomationAction> body = groups == null ? null : groups.actionsOf(groupId);
            return walk(body, groups, visitedGroups, depth);
        } finally {
            // A group may legitimately appear twice in sequence; only re-entry on the
            // current path is a cycle.
            visitedGroups.remove(groupId);
        }
    }

    /** windowsAll is only safe hands-free as "close" (command 2); open and stop are refused. */
    private static boolean closesWindows(AutomationAction action) {
        Map<String, Object> variables = action.getVariables();
        Object command = variables == null ? null : variables.get("command");
        return command != null && WINDOWS_CLOSE.equals(command.toString().trim());
    }
}
