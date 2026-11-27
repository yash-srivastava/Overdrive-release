package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.mqtt.VehicleControlCatalog;

import org.json.JSONObject;
import org.junit.Test;

import java.util.UUID;

public class TargetSocDynamicPayloadTest {

    private final VehicleControlAction action =
            (VehicleControlAction) new Actions().getAction("target_soc");
    private final VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get("target_soc");
    private final String variable = "socProbe_" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    public void targetSocFieldOffersTheVariablePicker() throws Exception {
        assertTrue(action.getVariables().get(0) instanceof DynamicIntType);
        JSONObject field = action.toJson().getJSONArray("variables").getJSONObject(0);
        assertEquals("int", field.getString("type"));
        assertTrue(field.getBoolean("dynamic"));
        assertEquals(15, field.getInt("min"));
        assertEquals(70, field.getInt("max"));
        assertNotNull(action.fromJson(new JSONObject().put("variables",
                new JSONObject().put("payload", "${var:" + variable + "}"))));
        assertNull("a constant is still range-checked on save", action.fromJson(new JSONObject()
                .put("variables", new JSONObject().put("payload", 85))));
    }

    @Test
    public void variableValueResolvesAndIsFittedToTheTargetRange() {
        assertEquals(42, commandPercent(withVariable("42")));
        assertEquals(70, commandPercent(withVariable("85")));
        assertEquals(48, commandPercent(withVariable("47.6")));
        assertEquals(48, commandPercent(withVariable("47.5")));
        assertEquals(15, commandPercent(withVariable("10")));
    }

    @Test
    public void arithmeticPayloadIsFittedToTheTargetRange() {
        assertEquals(70, commandPercent(action.controlPayload("40*2", entity)));
        assertEquals(15, commandPercent(action.controlPayload("MIN(5,9)", entity)));
    }

    @Test
    public void unresolvedOrNonNumericDynamicPayloadIsRejected() {
        String unresolved = action.controlPayload("${var:" + variable + "}", entity);
        assertNull(entity.toAction(null, unresolved, null));
        assertNull(entity.toAction(null, action.controlPayload("${var:" + variable + "}+1", entity), null));
        assertNull(entity.toAction(null, action.controlPayload("${signal:noSuchSignal}", entity), null));
        assertNull(entity.toAction(null, withVariable("high"), null));
        assertNull(entity.toAction(null, withVariable(""), null));
        assertNull("an empty variable must not fold into the arithmetic", entity.toAction(
                null, action.controlPayload("${var:" + variable + "}+1", entity), null));
        assertNull(entity.toAction(null, withVariable("high"), null));
        assertNull(entity.toAction(null, action.controlPayload("${var:" + variable + "}+1", entity), null));
    }

    @Test
    public void constantPayloadKeepsStrictRejection() {
        assertEquals("85", action.controlPayload("85", entity));
        assertNull(entity.toAction(null, action.controlPayload("85", entity), null));
        assertNull(entity.toAction(null, action.controlPayload("10", entity), null));
        assertNull(entity.toAction(null, action.controlPayload("47.6", entity), null));
        assertEquals(42, commandPercent(action.controlPayload("42", entity)));
        assertNull("MQTT ingress stays strict", entity.toAction(null, "85", null));
    }

    @Test
    public void fittingSnapsToStepAndLeavesNonNumbersAlone() {
        VehicleControlCatalog.ControlEntity chargeCap = VehicleControlCatalog.get("charge_cap_percent");
        assertEquals("55", VehicleControlAction.fitDynamicNumber("53", chargeCap));
        assertEquals("100", VehicleControlAction.fitDynamicNumber("120", chargeCap));
        assertEquals("${var:x}", VehicleControlAction.fitDynamicNumber("${var:x}", entity));
        assertEquals("eco", VehicleControlAction.fitDynamicNumber("eco", entity));
        assertFalse(VehicleControlAction.isDynamicPayload("85"));
        assertTrue(VehicleControlAction.isDynamicPayload("${signal:batteryLevel}"));
    }

    private String withVariable(String value) {
        Automations.update(SetVariableAction.variableEvent(variable), value, true);
        return action.controlPayload("${var:" + variable + "}", entity);
    }

    private int commandPercent(String payload) {
        VehicleControlCatalog.ControlAction control = entity.toAction(null, payload, null);
        assertNotNull("payload " + payload + " was rejected", control);
        assertTrue(control.command instanceof VehicleCommandRouter.SocTargetPercentCommand);
        return ((VehicleCommandRouter.SocTargetPercentCommand) control.command).percent;
    }
}
