package com.overdrive.app.genai;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent AI insight inbox plus the preset schedule bridge.
 *
 * <p>Generation is always explicit: an HTTP request or an existing automation
 * action starts one bounded call. There is no private timer or resident worker;
 * daily/weekly presets are ordinary OverDrive time automations and disappear
 * while the GenAI master switch is off.
 */
public final class GenAiInsights {

    public static final String DELIVERY_DASHBOARD = "dashboard";
    public static final String DELIVERY_NOTIFICATION = "notification";

    static final String MANAGED_AUTOMATION_ID =
            "overdrive.genai.insights.schedule";
    static final String HOME_PROPERTY = "overdrive.genai.home";

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("GenAiInsights");
    private static final Object STORE_LOCK = new Object();
    private static final AtomicBoolean GENERATING = new AtomicBoolean(false);
    private static final int MAX_ITEMS = 50;
    private static final int MAX_TEXT_CHARS = 12_000;
    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_PROMPT_CHARS = 600;

    private static final String INSIGHT_INSTRUCTIONS =
            "Write one concise OverDrive insight for a dashboard inbox. "
            + "Return a short title and plain-text body with no Markdown. "
            + "Start with the most useful conclusion, "
            + "then give short evidence-backed details and at most three practical "
            + "next steps. Omit unavailable sections, separate observations from "
            + "hypotheses, and never claim a vehicle action was performed.";

    private static boolean loaded;
    private static final List<JSONObject> items = new ArrayList<>();

    private GenAiInsights() {
    }

    public static boolean isGenerating() {
        return GENERATING.get();
    }

    public static JSONObject generate(
            GenAiRuntime runtime, String requestedMode, String prompt,
            boolean notify, String source, String language)
            throws GenAiRuntime.GenAiException {
        if (!GENERATING.compareAndSet(false, true)) {
            throw new GenAiRuntime.GenAiException(
                    409, "insight_generation_busy",
                    "Another AI insight is already being generated.");
        }
        try {
            return generateLocked(
                    runtime, requestedMode, prompt, notify, source,
                    language);
        } finally {
            GENERATING.set(false);
        }
    }

    public static boolean requestAsync(
            String requestedMode, String prompt, boolean notify,
            String source) {
        GenAiConfig config = GenAiConfig.fromUnifiedConfig();
        GenAiRuntime runtime = CameraDaemon.getGenAiRuntime();
        if (!config.enabled || !config.isConfigured() || runtime == null
                || !GENERATING.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(() -> {
            try {
                generateLocked(
                        runtime, requestedMode, prompt, notify, source,
                        LocaleManager.get());
            } catch (Throwable t) {
                logger.warn("Insight generation failed: " + t.getMessage());
            } finally {
                GENERATING.set(false);
            }
        }, "GenAiInsight");
        thread.setDaemon(true);
        try {
            thread.start();
            return true;
        } catch (Throwable t) {
            GENERATING.set(false);
            return false;
        }
    }

    private static JSONObject generateLocked(
            GenAiRuntime runtime, String requestedMode, String prompt,
            boolean notify, String source, String requestedLanguage)
            throws GenAiRuntime.GenAiException {
        GenAiConfig config = GenAiConfig.fromUnifiedConfig();
        if (!config.enabled) {
            throw new GenAiRuntime.GenAiException(
                    409, "genai_disabled", "GenAI is disabled.");
        }
        if (!config.isConfigured()) {
            throw new GenAiRuntime.GenAiException(
                    409, "genai_not_configured",
                    "GenAI provider settings are incomplete.");
        }
        if (runtime == null) {
            throw new GenAiRuntime.GenAiException(
                    503, "runtime_unavailable",
                    "GenAI runtime is not ready.");
        }
        try {
            String mode = normalizeMode(requestedMode);
            String focus = cleanPrompt(prompt);
            String language = normalizeLanguage(requestedLanguage);
            GenAiContext.Snapshot snapshot =
                    GenAiContext.build(mode, focus);
            JSONArray messages = new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", focus.isEmpty()
                            ? defaultPrompt(mode)
                            : defaultPrompt(mode)
                                    + "\nUser focus: " + focus));
            JSONObject provider = runtime.completeStructured(
                    messages, snapshot.context,
                    INSIGHT_INSTRUCTIONS + "\n"
                            + "Write both title and body entirely in the "
                            + "user language identified by BCP-47 tag \""
                            + language + "\". Keep product names, model IDs, "
                            + "vehicle enum tokens, and units unchanged.\n"
                            + snapshot.instructions,
                    "overdrive_dashboard_insight",
                    responseSchema());
            JSONObject generated = GenAiAutomation.extractObject(
                    provider.optString("text", ""));
            String generatedText = generated == null
                    ? provider.optString("text", "")
                    : generated.optString("text", "");
            String generatedTitle = generated == null
                    ? title(mode)
                    : generated.optString("title", title(mode));
            generatedText = bound(generatedText);
            if (generatedText.isEmpty()) {
                throw new GenAiRuntime.GenAiException(
                        503, "empty_provider_response",
                        "The provider returned no insight text.");
            }

            JSONObject item = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("createdAt", System.currentTimeMillis())
                    .put("mode", mode)
                    .put("language", language)
                    .put("title", boundShort(
                            generatedTitle, MAX_TITLE_CHARS))
                    .put("text", generatedText)
                    .put("source", cleanSource(source))
                    .put("provider",
                            provider.optString("provider", ""))
                    .put("model", provider.optString("model", ""))
                    .put("notified", notify);
            append(item);
            if (notify) publish(item);

            return new JSONObject()
                    .put("success", true)
                    .put("item",
                            new JSONObject(item.toString()));
        } catch (GenAiRuntime.GenAiException e) {
            throw e;
        } catch (Exception e) {
            throw new GenAiRuntime.GenAiException(
                    500, "insight_generation_failed",
                    "Could not build the AI insight.");
        }
    }

    public static JSONObject listJson(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_ITEMS));
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            try {
                JSONArray out = new JSONArray();
                for (int i = 0;
                     i < items.size() && i < limit; i++) {
                    out.put(new JSONObject(items.get(i).toString()));
                }
                return new JSONObject()
                        .put("success", true)
                        .put("generating", GENERATING.get())
                        .put("items", out);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public static JSONObject latestJson(String requestedLanguage) {
        String language = normalizeLanguage(requestedLanguage);
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            for (JSONObject item : items) {
                if (language.equals(item.optString(
                        "language", "en"))) {
                    try {
                        return new JSONObject(item.toString());
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
            return null;
        }
    }

    public static boolean clear() {
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            items.clear();
            return saveLocked();
        }
    }

    /**
     * Create/update the single transparent preset automation, or remove it
     * while disabled/off. Custom event-driven schedules use the same action
     * directly in the normal automation editor.
     */
    public static boolean syncSchedule(GenAiConfig config) {
        try {
            if (config == null || !config.enabled
                    || GenAiConfig.INSIGHT_SCHEDULE_OFF.equals(
                            config.insightSchedule)) {
                Automations.deleteAutomation(MANAGED_AUTOMATION_ID);
                return true;
            }
            Automation scheduled = buildScheduledAutomation(config);
            if (scheduled == null) return false;
            JSONObject intended = scheduled.toJson();
            JSONObject current =
                    Automations.toJson().optJSONObject(
                            MANAGED_AUTOMATION_ID);
            if (current != null) {
                current = new JSONObject(current.toString());
                current.remove("lastTriggered");
                current.remove("triggerCount");
                if (current.toString().equals(
                        intended.toString())) {
                    return true;
                }
            }
            return Automations.updateAutomation(
                    MANAGED_AUTOMATION_ID, scheduled);
        } catch (Throwable t) {
            logger.warn("Could not synchronize insight schedule: "
                    + t.getMessage());
            return false;
        }
    }

    public static void syncScheduleAsync(GenAiConfig config) {
        Thread thread = new Thread(
                // Read at execution time so rapid enable/disable changes cannot
                // let an older queued sync recreate a schedule after the kill switch.
                () -> syncSchedule(GenAiConfig.fromUnifiedConfig()),
                "GenAiInsightSchedule");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            logger.warn("Could not start insight schedule sync: "
                    + t.getMessage());
        }
    }

    static Automation buildScheduledAutomation(GenAiConfig config) {
        if (config == null
                || GenAiConfig.INSIGHT_SCHEDULE_OFF.equals(
                        config.insightSchedule)) {
            return null;
        }
        List<AutomationCondition> conditions = new ArrayList<>();
        conditions.add(new AutomationCondition(
                BydEvent.TIME, "eq",
                config.insightHour * 60 + config.insightMinute));
        if (GenAiConfig.INSIGHT_SCHEDULE_WEEKLY.equals(
                config.insightSchedule)) {
            conditions.add(new AutomationCondition(
                    BydEvent.DAY, "eq", dayName(config.insightDay)));
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("mode", normalizeMode(config.insightMode));
        variables.put("delivery", config.insightNotifications
                ? DELIVERY_NOTIFICATION : DELIVERY_DASHBOARD);
        variables.put("prompt", "");

        Automation automation = new Automation(
                List.of(BydEvent.TIME),
                conditions,
                0,
                List.of(new AutomationAction(
                        "genAiInsight", variables)),
                false);
        automation.setName(scheduleName(config));
        return automation;
    }

    static String normalizeMode(String requested) {
        String value = requested == null
                ? "" : requested.trim().toLowerCase(
                        java.util.Locale.US);
        return GenAiContext.isInsightMode(value)
                ? value : GenAiContext.OVERVIEW;
    }

    static String normalizeLanguage(String requested) {
        String value = requested == null ? "" : requested.trim();
        return value.isEmpty()
                ? LocaleManager.get()
                : LocaleManager.resolve(value);
    }

    static JSONObject responseSchema() {
        try {
            return new JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", new JSONArray()
                            .put("title")
                            .put("text"))
                    .put("properties", new JSONObject()
                            .put("title", new JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", MAX_TITLE_CHARS))
                            .put("text", new JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", MAX_TEXT_CHARS)));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String cleanPrompt(String prompt)
            throws GenAiRuntime.GenAiException {
        String value = prompt == null ? "" : prompt.trim();
        if (value.length() > MAX_PROMPT_CHARS) {
            throw new GenAiRuntime.GenAiException(
                    400, "insight_prompt_too_long",
                    "Insight focus is too long.");
        }
        return value;
    }

    private static String cleanSource(String source) {
        String value = source == null ? "" : source.trim();
        if ("schedule".equals(value)
                || "automation".equals(value)) {
            return value;
        }
        return "manual";
    }

    private static String defaultPrompt(String mode) {
        if (GenAiContext.CURRENT_VEHICLE.equals(mode)) {
            return "Summarize the current vehicle state.";
        }
        if (GenAiContext.LATEST_TRIP.equals(mode)) {
            return "Summarize and explain the latest trip.";
        }
        if (GenAiContext.TRIP_COMPARISON.equals(mode)) {
            return "Compare the latest trip with similar completed trips and explain supported reasons for higher or lower energy use.";
        }
        if (GenAiContext.RECENT_EVENTS.equals(mode)) {
            return "Summarize recent recording and surveillance events.";
        }
        if (GenAiContext.ROADSENSE.equals(mode)) {
            return "Summarize nearby RoadSense hazards.";
        }
        if (GenAiContext.CHARGING.equals(mode)) {
            return "Summarize recent charging and useful trends.";
        }
        if (GenAiContext.DIAGNOSTICS.equals(mode)) {
            return "Summarize current diagnostics and low-risk next checks.";
        }
        return "Create a useful overview of the vehicle, latest trip, recent events, RoadSense, and charging.";
    }

    private static String title(String mode) {
        if (GenAiContext.CURRENT_VEHICLE.equals(mode)) {
            return "Vehicle state";
        }
        if (GenAiContext.LATEST_TRIP.equals(mode)) {
            return "Latest trip";
        }
        if (GenAiContext.TRIP_COMPARISON.equals(mode)) {
            return "Trip consumption";
        }
        if (GenAiContext.RECENT_EVENTS.equals(mode)) {
            return "Recent events";
        }
        if (GenAiContext.ROADSENSE.equals(mode)) return "RoadSense";
        if (GenAiContext.CHARGING.equals(mode)) return "Charging";
        if (GenAiContext.DIAGNOSTICS.equals(mode)) {
            return "Diagnostics";
        }
        return "Vehicle brief";
    }

    private static String scheduleName(GenAiConfig config) {
        String prefix =
                GenAiConfig.INSIGHT_SCHEDULE_WEEKLY.equals(
                        config.insightSchedule)
                        ? "Weekly" : "Daily";
        return "AI Insights · " + prefix + " "
                + String.format(java.util.Locale.US, "%02d:%02d",
                config.insightHour, config.insightMinute);
    }

    private static String dayName(int isoDay) {
        return DayOfWeek.of(Math.max(1, Math.min(7, isoDay)))
                .name().toLowerCase(java.util.Locale.US);
    }

    private static void publish(JSONObject item) {
        try {
            String text = item.optString("text", "");
            String body = text.length() > 600
                    ? text.substring(0, 597) + "..." : text;
            NotificationBus.get().publish(new NotificationEvent(
                    "genai.insight",
                    NotificationEvent.Severity.INFO,
                    item.optString(
                            "title", "OverDrive insight"),
                    body,
                    "genai-insight-"
                            + item.optString("source", "manual"),
                    "/assistant",
                    new JSONObject()
                            .put("insightId",
                                    item.optString("id", ""))
                            .put("mode",
                                    item.optString("mode", ""))));
        } catch (Exception e) {
            logger.warn("Could not publish insight notification: "
                    + e.getMessage());
        }
    }

    private static void append(JSONObject item)
            throws GenAiRuntime.GenAiException {
        synchronized (STORE_LOCK) {
            ensureLoadedLocked();
            items.add(0, sanitizeItem(item));
            while (items.size() > MAX_ITEMS) {
                items.remove(items.size() - 1);
            }
            if (!saveLocked()) {
                throw new GenAiRuntime.GenAiException(
                        500, "insight_persist_failed",
                        "The insight was generated but could not be saved.");
            }
        }
    }

    private static JSONObject sanitizeItem(JSONObject source) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", source.optString(
                    "id", UUID.randomUUID().toString()));
            out.put("createdAt", source.optLong(
                    "createdAt", System.currentTimeMillis()));
            out.put("mode", normalizeMode(
                    source.optString("mode", "")));
            out.put("language", normalizeLanguage(
                    source.optString("language", "en")));
            out.put("title", boundShort(source.optString(
                    "title", "Vehicle brief"), 120));
            out.put("text", bound(
                    source.optString("text", "")));
            out.put("source", cleanSource(
                    source.optString("source", "")));
            out.put("provider", boundShort(
                    source.optString("provider", ""), 80));
            out.put("model", boundShort(
                    source.optString("model", ""), 200));
            out.put("notified",
                    source.optBoolean("notified", false));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String bound(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= MAX_TEXT_CHARS
                ? clean : clean.substring(0, MAX_TEXT_CHARS);
    }

    private static String boundShort(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max
                ? clean : clean.substring(0, max);
    }

    private static void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        items.clear();
        makeOwnerOnly(home(), true);
        makeOwnerOnly(storeFile(), false);
        makeOwnerOnly(backupFile(), false);
        JSONObject root = readStore(storeFile());
        if (root == null) root = readStore(backupFile());
        JSONArray stored = root == null
                ? null : root.optJSONArray("items");
        if (stored == null) return;
        for (int i = 0;
             i < stored.length() && items.size() < MAX_ITEMS; i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item != null
                    && !item.optString("text", "").trim().isEmpty()) {
                items.add(sanitizeItem(item));
            }
        }
    }

    private static JSONObject readStore(File file) {
        if (!file.isFile() || file.length() <= 0
                || file.length() > 2 * 1024 * 1024) {
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
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean saveLocked() {
        File home = home();
        if (!home.exists() && !home.mkdirs()) return false;
        makeOwnerOnly(home, true);
        File file = storeFile();
        File tmp = tempFile();
        File backup = backupFile();
        try {
            JSONArray stored = new JSONArray();
            for (JSONObject item : items) stored.put(item);
            byte[] bytes = new JSONObject()
                    .put("version", 1)
                    .put("items", stored)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
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
            return true;
        } catch (Exception e) {
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
                HOME_PROPERTY, ScratchPaths.path(".genai")));
    }

    private static File storeFile() {
        return new File(home(), "insights.json");
    }

    private static File backupFile() {
        return new File(home(), "insights.json.bak");
    }

    private static File tempFile() {
        return new File(home(), "insights.json.tmp");
    }
}
