package com.overdrive.app.genai;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Local, deterministic routine learner for explicitly eligible successful
 * climate and sunshade actions.
 *
 * <p>This class never reads telemetry and never calls a GenAI provider. It
 * groups explicit observations by action target and local 30-minute time slot,
 * then offers a manual-only daily time automation after enough repetition.
 */
public final class GenAiRoutineLearner {

    private static final Object STORE_LOCK = new Object();
    private static final int STORE_VERSION = 1;
    static final int MAX_OBSERVATIONS = 128;
    private static final int MAX_DECISIONS = 64;
    private static final int MAX_STORE_BYTES = 1024 * 1024;
    private static final int MIN_OBSERVATIONS = 4;
    private static final int MIN_DATES = 3;
    private static final int MAX_SUGGESTIONS = 2;
    private static final long SNOOZE_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final String KIND_CLIMATE = "climate";
    private static final String KIND_SUNSHADE = "sunshade";
    private static final String STATE_SAVED = "saved";
    private static final String STATE_SNOOZED = "snoozed";
    private static final String STATE_DISMISSED = "dismissed";

    private static boolean loaded;
    private static final List<JSONObject> observations = new ArrayList<>();
    private static final LinkedHashMap<String, JSONObject> decisions =
            new LinkedHashMap<>();

    private static BooleanSupplier enabledSupplier =
            GenAiRoutineLearner::configuredEnabled;
    private static Clock clock = Clock.systemDefaultZone();
    private static AutomationSaver automationSaver =
            GenAiRoutineLearner::saveAutomation;

    private GenAiRoutineLearner() {
    }

    /**
     * Record an explicitly eligible, successfully applied climate setpoint.
     *
     * @param zone         existing setAcTemp zone: 0=both, 1=driver, 2=passenger
     * @param temperatureC whole-degree Celsius target supported by setAcTemp
     * @return true only when the observation was durably stored
     */
    public static boolean recordClimate(int zone, double temperatureC) {
        return recordClimate(zone, temperatureC, true, true);
    }

    /**
     * Conditional form for command call sites that already know eligibility
     * and final command success.
     */
    public static boolean recordClimate(
            int zone, double temperatureC,
            boolean eligible, boolean successful) {
        if (!eligible || !successful || zone < 0 || zone > 2
                || !Double.isFinite(temperatureC)) {
            return false;
        }
        int temperature = (int) Math.round(temperatureC);
        if (Math.abs(temperatureC - temperature) > 0.001
                || temperature < 17 || temperature > 33) {
            return false;
        }
        synchronized (STORE_LOCK) {
            JSONObject observation = baseObservationLocked(KIND_CLIMATE);
            try {
                observation.put("zone", zone);
                observation.put("temperatureC", temperature);
            } catch (Exception ignored) {
                return false;
            }
            return recordLocked(observation);
        }
    }

    /**
     * Record an explicitly eligible, successfully applied sunshade operation.
     * Only endpoint operations are learned; stop is intentionally ignored.
     */
    public static boolean setSunshade(String operation) {
        return setSunshade(operation, true, true);
    }

    /**
     * Conditional form for command call sites that already know eligibility
     * and final command success.
     */
    public static boolean setSunshade(
            String operation, boolean eligible, boolean successful) {
        String normalized = operation == null
                ? "" : operation.trim().toLowerCase(Locale.US);
        if (!eligible || !successful
                || (!"open".equals(normalized)
                && !"close".equals(normalized))) {
            return false;
        }
        synchronized (STORE_LOCK) {
            JSONObject observation =
                    baseObservationLocked(KIND_SUNSHADE);
            try {
                observation.put("operation", normalized);
            } catch (Exception ignored) {
                return false;
            }
            return recordLocked(observation);
        }
    }

    /**
     * Current local learner state and at most two deterministic suggestions.
     */
    public static JSONObject statusJson() {
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            boolean enabled = enabledLocked();
            boolean storageOk = true;
            if (!enabled) storageOk = clearObservationsLocked();

            JSONArray suggestions = new JSONArray();
            if (enabled) {
                for (Candidate candidate : candidatesLocked(false)) {
                    if (suggestions.length() >= MAX_SUGGESTIONS) break;
                    JSONObject suggestion = suggestionJson(candidate);
                    if (suggestion != null) suggestions.put(suggestion);
                }
            }
            try {
                return new JSONObject()
                        .put("success", true)
                        .put("enabled", enabled)
                        .put("storageOk", storageOk)
                        .put("observationCount", observations.size())
                        .put("suggestions", suggestions);
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    /**
     * Apply save, snooze, dismiss, or reset to a suggestion.
     *
     * <p>Save regenerates the automation server-side, validates it through
     * {@link GenAiAutomation}, and persists it under a deterministic UUID.
     */
    public static JSONObject decision(
            String suggestionId, String requestedDecision) {
        String action = requestedDecision == null
                ? "" : requestedDecision.trim().toLowerCase(Locale.US);
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            if ("reset".equals(action)) return resetLocked();

            if (!enabledLocked()) {
                clearObservationsLocked();
                return error("routine_learning_disabled",
                        "Routine learning is disabled.");
            }
            if (!"save".equals(action)
                    && !"snooze".equals(action)
                    && !"dismiss".equals(action)) {
                return error("invalid_decision",
                        "Decision must be save, snooze, dismiss, or reset.");
            }

            Candidate candidate = candidateLocked(suggestionId);
            if (candidate == null) {
                return error("suggestion_not_found",
                        "Routine suggestion was not found.");
            }
            JSONObject current = decisions.get(candidate.id);
            String currentState = current == null
                    ? "" : current.optString("state", "");
            if (STATE_SAVED.equals(currentState)
                    || STATE_DISMISSED.equals(currentState)) {
                return error("suggestion_unavailable",
                        "Routine suggestion is no longer available.");
            }

            if ("save".equals(action)) return saveLocked(candidate);

            LinkedHashMap<String, JSONObject> previous =
                    new LinkedHashMap<>(decisions);
            long now = clock.millis();
            JSONObject record = new JSONObject();
            try {
                record.put("id", candidate.id);
                record.put("state", "snooze".equals(action)
                        ? STATE_SNOOZED : STATE_DISMISSED);
                record.put("atMs", now);
                if ("snooze".equals(action)) {
                    record.put("untilMs", now + SNOOZE_MS);
                }
            } catch (Exception ignored) {
                return error("decision_failed",
                        "Could not apply the routine decision.");
            }
            putDecisionLocked(record);
            if (!saveStoreLocked()) {
                decisions.clear();
                decisions.putAll(previous);
                return error("routine_persist_failed",
                        "Could not save the routine decision.");
            }
            try {
                JSONObject response = new JSONObject()
                        .put("success", true)
                        .put("suggestionId", candidate.id)
                        .put("decision", action);
                if ("snooze".equals(action)) {
                    response.put("untilMs", now + SNOOZE_MS);
                }
                return response;
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private static boolean recordLocked(JSONObject observation) {
        ensureLoadedLocked();
        if (!enabledLocked()) {
            clearObservationsLocked();
            return false;
        }
        String key = observationKey(observation);
        if (key.isEmpty()) return false;
        JSONObject priorDecision = decisions.get(suggestionId(key));
        String state = priorDecision == null
                ? "" : priorDecision.optString("state", "");
        if (STATE_SAVED.equals(state) || STATE_DISMISSED.equals(state)) {
            return false;
        }

        List<JSONObject> previous = new ArrayList<>(observations);
        observations.add(0, observation);
        while (observations.size() > MAX_OBSERVATIONS) {
            observations.remove(observations.size() - 1);
        }
        if (saveStoreLocked()) return true;
        observations.clear();
        observations.addAll(previous);
        return false;
    }

    private static JSONObject baseObservationLocked(String kind) {
        ZonedDateTime local = ZonedDateTime.now(clock);
        int minute = local.getHour() * 60 + local.getMinute();
        JSONObject observation = new JSONObject();
        try {
            observation.put("atMs", clock.millis());
            observation.put("date", local.toLocalDate().toString());
            observation.put("slot", minute / 30);
            observation.put("kind", kind);
        } catch (Exception ignored) {
        }
        return observation;
    }

    private static JSONObject saveLocked(Candidate candidate) {
        final Automation automation;
        try {
            automation = buildAutomation(candidate);
        } catch (Exception e) {
            return error("invalid_automation",
                    "Could not build a valid routine automation.");
        }
        String automationId = automationId(candidate.key);
        if (!automationSaver.save(automationId, automation)) {
            return error("automation_persist_failed",
                    "The routine automation could not be saved.");
        }

        observations.removeIf(
                observation -> candidate.key.equals(
                        observationKey(observation)));
        JSONObject record = new JSONObject();
        try {
            record.put("id", candidate.id);
            record.put("state", STATE_SAVED);
            record.put("atMs", clock.millis());
            record.put("automationId", automationId);
        } catch (Exception ignored) {
        }
        putDecisionLocked(record);
        boolean statePersisted = saveStoreLocked();
        try {
            return new JSONObject()
                    .put("success", true)
                    .put("suggestionId", candidate.id)
                    .put("decision", "save")
                    .put("automationId", automationId)
                    .put("executionMode", Automation.MODE_MANUAL)
                    .put("statePersisted", statePersisted)
                    .put("automation", automation.toJson());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static JSONObject resetLocked() {
        List<JSONObject> previousObservations =
                new ArrayList<>(observations);
        LinkedHashMap<String, JSONObject> previousDecisions =
                new LinkedHashMap<>(decisions);
        observations.clear();
        decisions.clear();
        if (!saveStoreLocked()) {
            observations.addAll(previousObservations);
            decisions.putAll(previousDecisions);
            return error("routine_persist_failed",
                    "Could not reset routine learning.");
        }
        try {
            return new JSONObject()
                    .put("success", true)
                    .put("decision", "reset");
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static Automation buildAutomation(Candidate candidate)
            throws GenAiAutomation.ValidationException {
        Map<String, Object> variables = new LinkedHashMap<>();
        String name;
        if (KIND_CLIMATE.equals(candidate.kind)) {
            variables.put("temperature", candidate.temperatureC);
            variables.put("zone", Integer.toString(candidate.zone));
            name = "Routine · Set cabin to " + candidate.temperatureC
                    + "°C at " + candidate.timeLabel();
        } else {
            variables.put("payload", candidate.operation);
            name = "Routine · "
                    + ("open".equals(candidate.operation)
                    ? "Open" : "Close")
                    + " sunshade at " + candidate.timeLabel();
        }

        Automation draft = new Automation(
                List.of(BydEvent.TIME),
                List.of(new AutomationCondition(
                        BydEvent.TIME, "eq", candidate.minuteOfDay())),
                0,
                List.of(new AutomationAction(
                        KIND_CLIMATE.equals(candidate.kind)
                                ? "setAcTemp" : "sunshade",
                        variables)),
                true);
        draft.setName(name);
        return GenAiAutomation.validateForSave(draft.toJson());
    }

    private static JSONObject suggestionJson(Candidate candidate) {
        try {
            Automation automation = buildAutomation(candidate);
            JSONObject action = automation.getActions().get(0).toJson();
            return new JSONObject()
                    .put("id", candidate.id)
                    .put("kind", candidate.kind)
                    .put("time", candidate.timeLabel())
                    .put("minuteOfDay", candidate.minuteOfDay())
                    .put("observationCount", candidate.count)
                    .put("distinctDates", candidate.dates.size())
                    .put("action", action)
                    .put("automation", automation.toJson());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Candidate candidateLocked(String requestedId) {
        if (requestedId == null || requestedId.trim().isEmpty()) return null;
        for (Candidate candidate : candidatesLocked(true)) {
            if (candidate.id.equals(requestedId.trim())) return candidate;
        }
        return null;
    }

    private static List<Candidate> candidatesLocked(boolean includeSuppressed) {
        Map<String, Candidate> grouped = new HashMap<>();
        for (JSONObject observation : observations) {
            String key = observationKey(observation);
            if (key.isEmpty()) continue;
            Candidate candidate = grouped.get(key);
            if (candidate == null) {
                candidate = Candidate.from(key, observation);
                if (candidate == null) continue;
                grouped.put(key, candidate);
            }
            candidate.add(observation);
        }

        long now = clock.millis();
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : grouped.values()) {
            if (candidate.count < MIN_OBSERVATIONS
                    || candidate.dates.size() < MIN_DATES) {
                continue;
            }
            JSONObject decision = decisions.get(candidate.id);
            if (!includeSuppressed && decision != null) {
                String state = decision.optString("state", "");
                if (STATE_SAVED.equals(state)
                        || STATE_DISMISSED.equals(state)
                        || (STATE_SNOOZED.equals(state)
                        && decision.optLong("untilMs", 0L) > now)) {
                    continue;
                }
            }
            eligible.add(candidate);
        }
        eligible.sort(Comparator
                .comparingInt((Candidate c) -> c.count).reversed()
                .thenComparing(
                        Comparator.comparingInt(
                                (Candidate c) -> c.dates.size())
                                .reversed())
                .thenComparing(
                        Comparator.comparingLong(
                                (Candidate c) -> c.latestAt)
                                .reversed())
                .thenComparing(c -> c.id));
        return eligible;
    }

    private static String observationKey(JSONObject observation) {
        if (observation == null) return "";
        String kind = observation.optString("kind", "");
        int slot = observation.optInt("slot", -1);
        if (slot < 0 || slot > 47) return "";
        if (KIND_CLIMATE.equals(kind)) {
            int zone = observation.optInt("zone", -1);
            int temperature = observation.optInt("temperatureC", -1);
            if (zone < 0 || zone > 2
                    || temperature < 17 || temperature > 33) {
                return "";
            }
            return KIND_CLIMATE + "|" + slot + "|"
                    + zone + "|" + temperature;
        }
        if (KIND_SUNSHADE.equals(kind)) {
            String operation = observation.optString("operation", "");
            if (!"open".equals(operation) && !"close".equals(operation)) {
                return "";
            }
            return KIND_SUNSHADE + "|" + slot + "|" + operation;
        }
        return "";
    }

    private static String suggestionId(String key) {
        return deterministicUuid("suggestion|" + key);
    }

    private static String automationId(String key) {
        return deterministicUuid("automation|" + key);
    }

    private static String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(
                ("overdrive.genai.routine|" + value)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static boolean clearObservationsLocked() {
        if (observations.isEmpty()) return true;
        observations.clear();
        // Disabling is a privacy boundary: keep memory clear even if storage is
        // temporarily unwritable. The next load retries the same purge.
        return saveStoreLocked();
    }

    private static boolean configuredEnabled() {
        try {
            return GenAiConfig.fromUnifiedConfig().routineLearningEnabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean enabledLocked() {
        try {
            return enabledSupplier.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean saveAutomation(
            String id, Automation automation) {
        return Automations.updateAutomation(id, automation);
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject()
                    .put("success", false)
                    .put("error", code)
                    .put("message", message);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void putDecisionLocked(JSONObject decision) {
        String id = decision.optString("id", "");
        if (id.isEmpty()) return;
        decisions.remove(id);
        decisions.put(id, decision);
        while (decisions.size() > MAX_DECISIONS) {
            String oldest = decisions.keySet().iterator().next();
            decisions.remove(oldest);
        }
    }

    private static void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        observations.clear();
        decisions.clear();
        makeOwnerOnly(home(), true);
        makeOwnerOnly(storeFile(), false);
        makeOwnerOnly(backupFile(), false);

        JSONObject root = readStore(storeFile());
        if (root == null) root = readStore(backupFile());
        if (root == null
                || root.optInt("version", STORE_VERSION) != STORE_VERSION) {
            return;
        }

        JSONArray storedObservations =
                root.optJSONArray("observations");
        if (storedObservations != null) {
            for (int i = 0;
                 i < storedObservations.length()
                         && observations.size() < MAX_OBSERVATIONS;
                 i++) {
                JSONObject sanitized = sanitizeObservation(
                        storedObservations.optJSONObject(i));
                if (sanitized != null) observations.add(sanitized);
            }
        }

        JSONArray storedDecisions = root.optJSONArray("decisions");
        if (storedDecisions != null) {
            for (int i = 0;
                 i < storedDecisions.length()
                         && decisions.size() < MAX_DECISIONS;
                 i++) {
                JSONObject sanitized = sanitizeDecision(
                        storedDecisions.optJSONObject(i));
                if (sanitized != null) putDecisionLocked(sanitized);
            }
        }
    }

    private static JSONObject sanitizeObservation(JSONObject source) {
        if (source == null) return null;
        String kind = source.optString("kind", "");
        int slot = source.optInt("slot", -1);
        String date = source.optString("date", "");
        try {
            LocalDate.parse(date);
        } catch (Exception ignored) {
            return null;
        }
        if (slot < 0 || slot > 47) return null;

        JSONObject out = new JSONObject();
        try {
            out.put("atMs", Math.max(0L,
                    source.optLong("atMs", 0L)));
            out.put("date", date);
            out.put("slot", slot);
            out.put("kind", kind);
            if (KIND_CLIMATE.equals(kind)) {
                int zone = source.optInt("zone", -1);
                int temperature =
                        source.optInt("temperatureC", -1);
                if (zone < 0 || zone > 2
                        || temperature < 17 || temperature > 33) {
                    return null;
                }
                out.put("zone", zone);
                out.put("temperatureC", temperature);
            } else if (KIND_SUNSHADE.equals(kind)) {
                String operation =
                        source.optString("operation", "");
                if (!"open".equals(operation)
                        && !"close".equals(operation)) {
                    return null;
                }
                out.put("operation", operation);
            } else {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return observationKey(out).isEmpty() ? null : out;
    }

    private static JSONObject sanitizeDecision(JSONObject source) {
        if (source == null) return null;
        String id = source.optString("id", "");
        try {
            UUID.fromString(id);
        } catch (Exception ignored) {
            return null;
        }
        String state = source.optString("state", "");
        if (!STATE_SAVED.equals(state)
                && !STATE_SNOOZED.equals(state)
                && !STATE_DISMISSED.equals(state)) {
            return null;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            out.put("state", state);
            out.put("atMs", Math.max(
                    0L, source.optLong("atMs", 0L)));
            if (STATE_SNOOZED.equals(state)) {
                out.put("untilMs", Math.max(
                        0L, source.optLong("untilMs", 0L)));
            }
            String automationId =
                    source.optString("automationId", "");
            if (!automationId.isEmpty()) {
                UUID.fromString(automationId);
                out.put("automationId", automationId);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject readStore(File file) {
        if (!file.isFile() || file.length() <= 0
                || file.length() > MAX_STORE_BYTES) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     (int) file.length())) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) out.write(buffer, 0, count);
            }
            return new JSONObject(new String(
                    out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean saveStoreLocked() {
        File home = home();
        if (!home.exists() && !home.mkdirs()) return false;
        makeOwnerOnly(home, true);
        File file = storeFile();
        File tmp = tempFile();
        File backup = backupFile();
        try {
            JSONArray storedObservations = new JSONArray();
            for (JSONObject observation : observations) {
                storedObservations.put(observation);
            }
            JSONArray storedDecisions = new JSONArray();
            for (JSONObject decision : decisions.values()) {
                storedDecisions.put(decision);
            }
            byte[] bytes = new JSONObject()
                    .put("version", STORE_VERSION)
                    .put("observations", storedObservations)
                    .put("decisions", storedDecisions)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STORE_BYTES) return false;

            if (tmp.exists() && !tmp.delete()) return false;
            if (!tmp.createNewFile()) return false;
            makeOwnerOnly(tmp, false);
            try (FileOutputStream out =
                         new FileOutputStream(tmp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) return false;
            if (file.exists() && !file.renameTo(backup)) return false;
            if (!tmp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file);
                return false;
            }
            makeOwnerOnly(file, false);
            makeOwnerOnly(backup, false);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void makeOwnerOnly(File file, boolean directory) {
        if (file == null || !file.exists()) return;
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (directory) file.setExecutable(true, true);
    }

    private static File home() {
        return new File(System.getProperty(
                GenAiInsights.HOME_PROPERTY,
                com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/.genai")));
    }

    private static File storeFile() {
        return new File(home(), "routine-learning.json");
    }

    private static File backupFile() {
        return new File(home(), "routine-learning.json.bak");
    }

    private static File tempFile() {
        return new File(home(), "routine-learning.json.tmp");
    }

    interface AutomationSaver {
        boolean save(String id, Automation automation);
    }

    static void installTestDependencies(
            BooleanSupplier testEnabledSupplier,
            Clock testClock,
            AutomationSaver testAutomationSaver) {
        synchronized (STORE_LOCK) {
            enabledSupplier = testEnabledSupplier;
            clock = testClock;
            automationSaver = testAutomationSaver;
            loaded = false;
            observations.clear();
            decisions.clear();
        }
    }

    static void reloadForTest() {
        synchronized (STORE_LOCK) {
            loaded = false;
            observations.clear();
            decisions.clear();
        }
    }

    static void restoreProductionDependenciesForTest() {
        synchronized (STORE_LOCK) {
            enabledSupplier = GenAiRoutineLearner::configuredEnabled;
            clock = Clock.systemDefaultZone();
            automationSaver = GenAiRoutineLearner::saveAutomation;
            loaded = false;
            observations.clear();
            decisions.clear();
        }
    }

    static File storeFileForTest() {
        return storeFile();
    }

    private static final class Candidate {
        final String key;
        final String id;
        final String kind;
        final int slot;
        final int zone;
        final int temperatureC;
        final String operation;
        final Set<String> dates = new HashSet<>();
        int count;
        long latestAt;

        private Candidate(
                String key, String kind, int slot,
                int zone, int temperatureC, String operation) {
            this.key = key;
            this.id = suggestionId(key);
            this.kind = kind;
            this.slot = slot;
            this.zone = zone;
            this.temperatureC = temperatureC;
            this.operation = operation;
        }

        static Candidate from(String key, JSONObject observation) {
            String kind = observation.optString("kind", "");
            int slot = observation.optInt("slot", -1);
            if (KIND_CLIMATE.equals(kind)) {
                return new Candidate(
                        key, kind, slot,
                        observation.optInt("zone", -1),
                        observation.optInt("temperatureC", -1),
                        "");
            }
            if (KIND_SUNSHADE.equals(kind)) {
                return new Candidate(
                        key, kind, slot, -1, -1,
                        observation.optString("operation", ""));
            }
            return null;
        }

        void add(JSONObject observation) {
            count++;
            dates.add(observation.optString("date", ""));
            latestAt = Math.max(
                    latestAt, observation.optLong("atMs", 0L));
        }

        int minuteOfDay() {
            return slot * 30;
        }

        String timeLabel() {
            int minute = minuteOfDay();
            return String.format(
                    Locale.US, "%02d:%02d",
                    minute / 60, minute % 60);
        }
    }
}
