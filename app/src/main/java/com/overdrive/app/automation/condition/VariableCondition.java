package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

/**
 * A trigger/condition on a named user VARIABLE (see {@link SetVariableAction}).
 *
 * <p>Unlike every other {@link EventCondition} — whose differentiating sub-variables
 * are a fixed {@link com.overdrive.app.automation.type.EnumType} set (window area,
 * light zone, …) — a variable is differentiated by a FREE-TEXT name the user types
 * ("Parking_Mode", "Sport_Mode"). That name can't be an enum, so this subclass
 * uses the shared free-text event parser and schema to
 * carry a {@code name} string field instead of the enum-variable machinery. The state
 * key it produces ({@code EventData("variable",{name})}) is identical to the one
 * {@link SetVariableAction#variableEvent} writes, so a set and a read line up.
 *
 * <p>Value comparison is string eq/neq (via the base {@link StringType} value), so
 * {@code Parking_Mode != true} works exactly as written; the name field is emitted in
 * the schema as its own string input the form renders alongside the value.
 */
public class VariableCondition extends TextKeyCondition {
    // Keep the persisted variable-name bound.
    private static final StringType NAME_TYPE =
            new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME);

    public VariableCondition(Label label, String description) {
        super(label, description, new StringType(new Label("value", "automation.variable_value"),
                SetVariableAction.MAX_VALUE), NAME_TYPE);
    }

    @Override
    protected EventData eventForKey(String key) {
        return SetVariableAction.variableEvent(key);
    }
}
