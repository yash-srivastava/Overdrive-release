package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GenAiAutomationTest {

    @Test
    public void extractsJsonWithoutTrustingProviderFormatting() {
        JSONObject parsed = GenAiAutomation.extractObject(
                "Here is the draft:\n```json\n"
                        + "{\"summary\":\"brace } inside string\",\"automation\":null}"
                        + "\n```");
        assertNotNull(parsed);
        assertEquals("brace } inside string",
                parsed.optString("summary"));
    }

    @Test
    public void forbiddenActionsAreRejectedAtAnyDepth() throws Exception {
        JSONObject safe = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "notification")));
        assertFalse(GenAiAutomation.containsForbiddenAction(safe));

        JSONObject nestedShell = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "if")
                        .put("childActions", new JSONArray()
                                .put(new JSONObject().put("type", "shell")))));
        assertTrue(GenAiAutomation.containsForbiddenAction(nestedShell));

        JSONObject group = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "actionGroup")));
        assertTrue(GenAiAutomation.containsForbiddenAction(group));
    }

    @Test
    public void validatedAutomationsAreForcedToManualMode() throws Exception {
        JSONObject proposed = new JSONObject()
                .put("name", "AI draft")
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "callState")
                        .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "setVariable")
                        .put("variables", new JSONObject()
                                .put("name", "ai_draft_test")
                                .put("value", "created"))))
                .put("disabled", false);

        Automation validated = GenAiAutomation.validateForSave(proposed);

        assertEquals(Automation.MODE_MANUAL, validated.getMode());
        assertTrue(validated.toJson().optBoolean("disabled"));
        assertTrue(validated.toJson().optBoolean("manualOnly"));
    }

    @Test
    public void clarificationQuestionsPreventPrematureAutoSave()
            throws Exception {
        JSONObject wrapper = new JSONObject()
                .put("summary", "I need one detail.")
                .put("questions", new JSONArray()
                        .put("Which seat should be heated?"))
                .put("automation", new JSONObject()
                        .put("name", "Incomplete"));

        GenAiAutomation.Draft draft =
                GenAiAutomation.parseProviderDraft(wrapper.toString());

        assertTrue(draft.needsInput());
        assertEquals(1, draft.questions.length());
    }

    @Test
    public void structuredAutomationJsonIsParsedBeforeValidation()
            throws Exception {
        JSONObject automation = new JSONObject()
                .put("name", "AI manual action")
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "callState")
                        .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "setVariable")
                        .put("variables", new JSONObject()
                                .put("name", "ai_structured_test")
                                .put("value", "ok"))))
                .put("disabled", false);
        GenAiAutomation.Draft draft =
                GenAiAutomation.parseProviderDraft(
                        new JSONObject()
                                .put("summary", "Ready")
                                .put("questions", new JSONArray())
                                .put("automationJson",
                                        automation.toString())
                                .toString());

        assertFalse(draft.needsInput());
        assertEquals("AI manual action",
                draft.automation.optString("name"));
    }

    @Test
    public void automationCatalogFitsTheProviderContextBudget() throws Exception {
        JSONObject context = GenAiAutomation.schemaContext();
        JSONObject catalog = context.optJSONObject("catalog");
        assertNotNull(catalog);
        assertNotNull(catalog.optJSONArray("events"));
        assertNotNull(catalog.optJSONArray("actions"));
        // Unit tests see raw i18n keys as labels, so measure the payload every shipped
        // catalog really produces against the cap GenAiRuntime truncates at.
        java.lang.reflect.Field cap = GenAiRuntime.class.getDeclaredField("MAX_CONTEXT_CHARS");
        cap.setAccessible(true);
        int limit = cap.getInt(null);
        java.io.File dir = new java.io.File("src/main/assets/server-i18n");
        java.util.Map<String, String> english = flatten(new JSONObject(read(new java.io.File(dir, "en.json"))));
        java.io.File[] catalogs = dir.listFiles((d, name) -> name.endsWith(".json"));
        assertNotNull(catalogs);
        int worst = 0;
        String worstLocale = "";
        for (java.io.File file : catalogs) {
            java.util.Map<String, String> labels = flatten(new JSONObject(read(file)));
            int length = String.valueOf(translate(context, english, labels)).length();
            if (length > worst) {
                worst = length;
                worstLocale = file.getName();
            }
        }
        assertTrue(catalogs.length > 1);
        assertTrue("largest automation context " + worstLocale + "=" + worst + " > " + limit,
                worst <= limit);
    }

    private static String read(java.io.File file) throws java.io.IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static java.util.Map<String, String> flatten(JSONObject object) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        flatten("", object, out);
        return out;
    }

    private static void flatten(String prefix, JSONObject object, java.util.Map<String, String> out) {
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof JSONObject) flatten(prefix + key + ".", (JSONObject) value, out);
            else if (value instanceof String) out.put(prefix + key, (String) value);
        }
    }

    private static Object translate(Object value, java.util.Map<String, String> english,
            java.util.Map<String, String> labels) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject in = (JSONObject) value;
            JSONObject out = new JSONObject();
            java.util.Iterator<String> keys = in.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                out.put(key, translate(in.get(key), english, labels));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray in = (JSONArray) value;
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(translate(in.get(i), english, labels));
            return out;
        }
        if (value instanceof String && english.containsKey(value)) {
            String label = labels.get(value);
            return label != null ? label : english.get(value);
        }
        return value;
    }

    @Test
    public void compactCatalogKeepsEverySupportedEventAndAction() {
        JSONObject catalog = GenAiAutomation.schemaContext()
                .optJSONObject("catalog");
        JSONArray schema = Automations.schemaJson();
        int expectedEvents = 0;
        int expectedActions = 0;
        for (int i = 0; i < schema.length(); i++) {
            JSONObject section = schema.optJSONObject(i);
            if (section == null) continue;
            JSONArray options = section.optJSONArray("options");
            if (options == null) continue;
            if ("conditions".equals(section.optString("id"))) {
                expectedEvents = options.length();
            } else if ("actions".equals(section.optString("id"))) {
                for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.optJSONObject(j);
                    String id = option == null
                            ? "" : option.optString("id", "")
                                    .toLowerCase();
                    if (!id.contains("shell")
                            && !"actiongroup".equals(id)) {
                        expectedActions++;
                    }
                }
            }
        }

        assertEquals(expectedEvents,
                catalog.optJSONArray("events").length());
        assertEquals(expectedActions,
                catalog.optJSONArray("actions").length());
    }
}
