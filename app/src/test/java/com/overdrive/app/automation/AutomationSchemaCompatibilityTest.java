package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.MqttTriggerCondition;
import com.overdrive.app.automation.condition.VariableCondition;
import com.overdrive.app.automation.type.ActionGroupType;
import com.overdrive.app.automation.type.AppType;
import com.overdrive.app.automation.type.AudioType;
import com.overdrive.app.automation.type.AutomationType;
import com.overdrive.app.automation.type.ColourType;
import com.overdrive.app.automation.type.DynamicColourType;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.automation.type.DynamicStringType;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.SavedSeatPositionType;
import com.overdrive.app.automation.type.SignalAddressType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.TimeType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;
import org.junit.Test;

public class AutomationSchemaCompatibilityTest {
    private static final Label FIELD = new Label("field", "Field");

    @Test
    public void selectorsKeepTheirSchemaAndValidationRules() throws Exception {
        Type[] selectors = {new AppType(FIELD), new AudioType(FIELD), new ActionGroupType(FIELD),
                new AutomationType(FIELD), new SavedSeatPositionType(FIELD)};
        String[] kinds = {"app", "audio", "actionGroup", "automationRef", "savedSeatPosition"};
        for (int i = 0; i < selectors.length; i++) {
            Type selector = selectors[i];
            JSONObject json = selector.toJson();
            assertEquals(3, json.length());
            assertEquals("field", json.getString("id"));
            assertEquals(FIELD.getLabel(), json.getString("label"));
            assertEquals(kinds[i], json.getString("type"));
            assertSame(FIELD, selector.getLabel());
            assertSame(StringValue.COMPARATORS, selector.getComparators());
            assertFalse(selector.isValid(null));
            assertFalse(selector.isValid(""));
            assertFalse(selector.isValid(42));
            assertFalse(selector.isValid("a\".mp3"));
            assertFalse(selector.isValid("é.mp3"));
        }
        assertTrue(selectors[0].isValid(new StringValue("com.Example_app")));
        assertTrue(selectors[0].isValid("a".repeat(255)));
        assertFalse(selectors[0].isValid("a".repeat(256)));
        assertFalse(selectors[0].isValid("com.example-app"));
        for (String extension : new String[]{"MP3", "wav", "mp4", "m4a", "aac", "ogg"}) {
            assertTrue(selectors[1].isValid("Alert 1_2-3." + extension));
        }
        assertTrue(selectors[1].isValid("a".repeat(76) + ".mp3"));
        assertFalse(selectors[1].isValid("a".repeat(77) + ".mp3"));
        assertFalse(selectors[1].isValid(".mp3"));
        assertFalse(selectors[1].isValid("a.exe"));
        assertFalse(selectors[1].isValid("folder/a.mp3"));
        for (int i = 2; i < selectors.length; i++) {
            int max = i == 4 ? 128 : 64;
            assertTrue(selectors[i].isValid("Ab_9-"));
            assertTrue(selectors[i].isValid("a".repeat(max)));
            assertFalse(selectors[i].isValid("a".repeat(max + 1)));
            assertFalse(selectors[i].isValid("a.b"));
        }
    }

    @Test
    public void specializedSchemaFieldsAndComparatorsAreRetained() throws Exception {
        IntType integer = new IntType(FIELD, -2, 4);
        assertEquals("int", integer.toJson().getString("type"));
        assertEquals(-2, integer.toJson().getInt("min"));
        assertEquals(4, integer.toJson().getInt("max"));
        assertSame(IntValue.COMPARATORS, integer.getComparators());
        assertTrue(integer.isValid(new IntValue(-2)));
        assertFalse(integer.isValid(5));
        TimeType time = new TimeType(FIELD);
        assertEquals("time", time.toJson().getString("type"));
        assertSame(IntValue.COMPARATORS, time.getComparators());
        assertTrue(time.isValid(1439));
        assertFalse(time.isValid(1440));
        ColourType colour = new ColourType(FIELD, "#ffffff", "#000000");
        assertEquals("colour", colour.toJson().getString("type"));
        assertEquals("#ffffff", colour.toJson().getJSONArray("colourCodes").getString(0));
        assertNull(colour.getComparators());
        assertTrue(colour.isValid(2));
        assertFalse(colour.isValid(0));
        EnumType choice = new EnumType(FIELD, new Label("b", "B"), new Label("a", "A"));
        assertEquals("enum", choice.toJson().getString("type"));
        assertEquals("b", choice.toJson().getJSONArray("options").getJSONObject(0).getString("id"));
        assertTrue(choice.isValid("a"));
        assertFalse(choice.isValid("A"));
        StringType string = new StringType(FIELD, 5, "automation.variable_name");
        assertEquals("string", string.toJson().getString("type"));
        assertEquals(5, string.toJson().getInt("maxLength"));
        assertEquals(Messages.get("automation.variable_name"), string.toJson().getString("warning"));
        assertFalse(new StringType(FIELD, 5).toJson().has("warning"));
        assertTrue(string.isValid(""));
        assertFalse(string.isValid("abcdef"));
        SignalAddressType address = new SignalAddressType(FIELD);
        assertEquals("signalAddress", address.toJson().getString("type"));
        assertTrue(address.toJson().getBoolean("signalAddress"));
        assertTrue(address.isValid("unknownLegacySignal"));
        assertFalse(address.isValid("  "));
        assertTrue(new DynamicStringType(FIELD, 64).toJson().getBoolean("dynamic"));
        assertTrue(new DynamicIntType(FIELD, 0, 5).toJson().getBoolean("dynamic"));
        assertTrue(new DynamicColourType(FIELD, "#ffffff").toJson().getBoolean("dynamic"));
    }

    @Test
    public void ordinaryEventStillAdvertisesEnumAttributesAndMqttTwin() throws Exception {
        EventCondition condition = new EventCondition(new Label("speed", "Speed"), "Speed",
                new IntType(FIELD, 0, 200), new EnumType(new Label("units", "Units"),
                new Label("kmph", "km/h")));
        JSONObject schema = condition.toJson();
        assertEquals("speed", schema.getString("mqtt"));
        assertEquals("enum", schema.getJSONArray("variables").getJSONObject(0).getString("type"));
        JSONObject input = new JSONObject().put("variables", new JSONObject().put("units", "kmph"))
                .put("comparator", "gt").put("value", 10);
        AutomationCondition parsed = condition.automationCondition(input);
        assertNotNull(parsed);
        assertEquals("kmph", parsed.getEventData().getVariables().get("units"));
        assertEquals(10, parsed.getValue());
    }

    @Test
    public void textKeyConditionsRetainPublisherKeysAndLegacyLoading() throws Exception {
        EventCondition[] conditions = {
                new VariableCondition(new Label("variable", "Variable"), "Variable"),
                new MqttTriggerCondition(new Label("mqttTrigger", "MQTT"), "MQTT")};
        String[] keys = {"name", "channel"};
        int[] keyLimits = {SetVariableAction.MAX_NAME, 64};
        int[] valueLimits = {SetVariableAction.MAX_VALUE, 256};
        for (int i = 0; i < conditions.length; i++) {
            EventCondition condition = conditions[i];
            String key = keys[i];
            JSONObject schema = condition.toJson();
            assertEquals("event", schema.getString("type"));
            assertFalse(schema.has("mqtt"));
            assertTrue(condition.getVariables().isEmpty());
            assertEquals(1, schema.getJSONArray("variables").length());
            JSONObject field = schema.getJSONArray("variables").getJSONObject(0);
            assertEquals(key, field.getString("id"));
            assertEquals("string", field.getString("type"));
            assertEquals(keyLimits[i], field.getInt("maxLength"));
            assertEquals(valueLimits[i], schema.getJSONObject("value").getInt("maxLength"));
            JSONObject vars = new JSONObject().put(key, " legacy key ");
            JSONObject input = new JSONObject().put("variables", vars)
                    .put("comparator", "eq").put("value", "${var:Current}");
            AutomationCondition parsed = condition.automationCondition(input);
            assertNotNull(parsed);
            assertEquals(i == 0 ? SetVariableAction.variableEvent("legacy key")
                    : BydEvent.mqttTrigger("legacy key"), parsed.getEventData());
            assertEquals("${var:Current}", parsed.getValue());
            assertNull(condition.automationCondition(input.put("comparator", "gt")));
            input.put("comparator", "eq").put("value", "a".repeat(valueLimits[i] + 1));
            assertNull(condition.automationCondition(input));
            input.put("value", "${var:" + "a".repeat(128) + "}");
            assertNull(condition.automationCondition(input));
            assertNull(condition.eventData(new JSONObject()));
            vars.put(key, " ");
            assertNull(condition.eventData(input));
            vars.put(key, "a".repeat(keyLimits[i] + 1));
            assertNull(condition.eventData(input));
            vars.put(key, "a".repeat(keyLimits[i]));
            assertNotNull(condition.eventData(input));
        }
    }
}
