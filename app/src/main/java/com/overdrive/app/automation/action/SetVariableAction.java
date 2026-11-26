package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.DynamicStringType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

import java.util.List;
import java.util.Map;

/**
 * Sets a named user VARIABLE (a "flag" / marker) in the shared automation state, so
 * automations can coordinate — e.g. a mutex that stops an automation re-triggering
 * while it's still running, or a mode flag another automation keys off.
 *
 * <p><b>How it fits the existing machinery.</b> A variable is just an
 * {@link EventData} of type {@code "variable"} keyed by its name
 * ({@code variables={name: "Parking_Mode"}}), stored in the same
 * {@link Automations} state map as every vehicle event. Setting it via
 * {@link Automations#update} therefore reuses the whole trigger/condition/dedup path
 * for free: writing a variable transitions its state, which fires any automation that
 * triggers on that variable, and its value is readable by any {@code variable}
 * condition (see {@link com.overdrive.app.automation.condition.VariableCondition}) and
 * by Wait Until.
 *
 * <p>The value is a short free-text string ("true"/"false", a mode name, a number as
 * text — the user's choice), compared with string eq/neq, so
 * {@code if Parking_Mode <> true} works exactly as written.
 */
public class SetVariableAction extends DeclaredAction {
    private static final String TYPE = "setVariable";
    // Bound both fields so a hand-edited config can't bloat the state map. Public so
    // VariableCondition (a different package) validates the name/value identically.
    public static final int MAX_NAME = 40;
    public static final int MAX_VALUE = 64;

    public SetVariableAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new StringType(new Label("name", "automation.variable_name"), MAX_NAME),
                // Value is dynamic-capable: a constant, or ${signal:TYPE}/${var:NAME} captured
                // live at fire time ("Set variable from state"). Resolved in trigger().
                new DynamicStringType(new Label("value", "automation.variable_value"), MAX_VALUE)));
    }

    /**
     * Build the {@link EventData} for a variable of the given name. Package-private so
     * {@link com.overdrive.app.automation.condition.VariableCondition} and any seeding
     * path key the state map identically (the name is the sole differentiator).
     */
    public static EventData variableEvent(String name) {
        return new EventData(BydEvent.VARIABLE_TYPE, Map.of("name", name));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String name = str(vars.get("name"));
        String value = str(vars.get("value"));
        if (name == null || name.trim().isEmpty()) {
            logger.warn("SetVariableAction: missing variable name, skipping");
            return;
        }
        name = name.trim();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
        if (value == null) value = "";
        // "Set variable FROM STATE": when the value is a dynamic reference token
        // (${signal:TYPE} or ${var:NAME}) resolve it to the live value NOW and store that
        // snapshot, so "set DRL_State = ${signal:drl}" captures the current DRL on/off. A
        // token that can't be resolved (never-fired signal / unknown) stores an empty
        // string rather than the literal "${…}", so a downstream compare sees "unset", not
        // a bogus literal. A plain constant takes the identical path as before.
        if (com.overdrive.app.automation.AutomationCondition.isDynamicRef(value)) {
            String resolved = com.overdrive.app.automation.AutomationCondition.resolveDynamicToString(value);
            value = (resolved != null) ? resolved : "";
        }
        if (value.length() > MAX_VALUE) value = value.substring(0, MAX_VALUE);
        // Publish into the shared state — this transitions the variable and fires any
        // automation triggered on it, exactly like a vehicle event.
        Automations.update(variableEvent(name), value);
        logger.info("SetVariableAction: " + name + " = '" + value + "'");
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
