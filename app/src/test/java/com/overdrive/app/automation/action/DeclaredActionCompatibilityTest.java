package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DeclaredActionCompatibilityTest {
    private static final String DESCRIPTION = "automation.send_notification_description";

    @Test
    public void constructorMetadataRetainsTypeAndOrderedFieldIds() throws Exception {
        Label label = new Label("customId", "automation.send_notification");
        Type field = new IntType(new Label("amount", "automation.value"), 1, 10);
        assertMetadata(new ActionGroupAction(label, DESCRIPTION), label, "actionGroup", "groupId");
        assertMetadata(new ApiAction(label, DESCRIPTION, "POST", "/example", "", field),
                label, "api", "amount");
        assertMetadata(new AutomationControlAction(label, DESCRIPTION), label,
                "automationControl", "target", "action");
        assertMetadata(new CaptureVariableAction(label, DESCRIPTION), label,
                "captureVariable", "source", "name");
        assertMetadata(new ComputeVariableAction(label, DESCRIPTION), label,
                "computeVariable", "name", "expression");
        assertMetadata(new IfAction(label, DESCRIPTION), label, "if", "event", "comparator", "value");
        assertMetadata(new IncrementVariableAction(label, DESCRIPTION), label,
                "incrementVariable", "name", "amount");
        assertMetadata(new LoopAction(label, DESCRIPTION), label,
                "loop", "mode", "count", "event", "comparator", "value");
        assertMetadata(new ManualClipAction(label, DESCRIPTION), label,
                "manualClip", "beforeSeconds", "afterSeconds");
        assertMetadata(new MqttPublishAction(label, DESCRIPTION), label,
                "mqttPublish", "topic", "payload", "retain");
        assertMetadata(new NotificationAction(label, DESCRIPTION), label, "notification", "message");
        assertMetadata(new PauseAction(label, DESCRIPTION), label, "pause", "milliseconds");
        assertMetadata(new RadioAction(label, DESCRIPTION), label, "radio", "radio", "state");
        assertMetadata(new SafeKeepAction(label, DESCRIPTION), label, "safeKeep", "command", "minutes");
        assertMetadata(new SetVariableAction(label, DESCRIPTION), label, "setVariable", "name", "value");
        assertMetadata(new ShellAction(label, DESCRIPTION), label, "shell", "command");
        assertMetadata(new VehicleControlAction(label, DESCRIPTION, field), label,
                "vehicle-control", "amount");
        assertMetadata(new WaitUntilAction(label, DESCRIPTION), label,
                "waitUntil", "event", "comparator", "value", "timeout");
        assertMetadata(new WaitUntilStateAction(label, DESCRIPTION), label,
                "waitUntilState", "event", "state", "timeout");

        GenAiInsightAction insight = new GenAiInsightAction();
        assertEquals("genAiInsight", insight.getLabel().getId());
        assertEquals("genAiInsight", insight.getType());
        assertEquals(Messages.get("automation.generate_ai_insight_description"), insight.getDescription());
        assertFields(insight, "mode", "delivery", "prompt");
    }

    @Test
    public void schemaKeepsControlFlowFlagsAndOptionalNameField() throws Exception {
        Label label = new Label("test", "test");
        JSONObject branch = new IfAction(label, DESCRIPTION).toJson();
        assertTrue(branch.getBoolean("hasChildActions"));
        assertTrue(branch.getBoolean("hasElseActions"));
        assertEquals("name", branch.getJSONObject("nameField").getString("id"));
        JSONObject loop = new LoopAction(label, DESCRIPTION).toJson();
        assertTrue(loop.getBoolean("hasChildActions"));
        assertFalse(loop.has("hasElseActions"));
        assertFalse(loop.has("nameField"));
        assertFalse(new PauseAction(label, DESCRIPTION).toJson().has("hasChildActions"));
    }

    @Test
    public void apiActionStillUsesCallerFieldsAndRejectsMissingOrInvalidValues() throws Exception {
        Label label = new Label("customId", "test");
        Type field = new IntType(new Label("amount", "test"), 1, 10);
        ApiAction action = new ApiAction(label, DESCRIPTION, "POST", "/example", "", field);
        assertSame(field, action.getVariables().get(0));
        AutomationAction parsed = action.fromJson(new JSONObject("{\"variables\":{\"amount\":5}}"));
        assertNotNull(parsed);
        assertEquals("customId", parsed.getType());
        assertEquals(5, parsed.getVariables().get("amount"));
        assertNull(action.fromJson(new JSONObject("{\"variables\":{}}")));
        assertNull(action.fromJson(new JSONObject("{\"variables\":{\"amount\":11}}")));
    }

    @Test
    public void variableListsKeepTheirOriginalPerInstanceStorage() {
        Label label = new Label("test", "test");
        SafeKeepAction first = new SafeKeepAction(label, DESCRIPTION);
        SafeKeepAction second = new SafeKeepAction(label, DESCRIPTION);
        Type original = second.getVariables().get(0);
        Type replacement = new IntType(new Label("replacement", "test"), 0, 1);
        first.getVariables().set(0, replacement); // Existing Arrays.asList contract.
        assertSame(replacement, first.getVariables().get(0));
        assertSame(original, second.getVariables().get(0));
    }

    private static void assertMetadata(BaseAction action, Label label, String type, String... ids)
            throws Exception {
        assertSame(label, action.getLabel());
        assertEquals(type, action.getType());
        assertEquals(Messages.get(DESCRIPTION), action.getDescription());
        assertFields(action, ids);
    }

    private static void assertFields(BaseAction action, String... ids) throws Exception {
        assertEquals(ids.length, action.getVariables().size());
        JSONArray fields = action.toJson().getJSONArray("variables");
        assertEquals(ids.length, fields.length());
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], action.getVariables().get(i).getLabel().getId());
            assertEquals(ids[i], fields.getJSONObject(i).getString("id"));
        }
    }
}
