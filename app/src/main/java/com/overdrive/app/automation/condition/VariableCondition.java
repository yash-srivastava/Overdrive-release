package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;


/**
 * A trigger/condition on a named user VARIABLE (see {@link SetVariableAction}).
 *
 * <p>Unlike every other {@link EventCondition} — whose differentiating sub-variables
 * are a fixed {@link com.overdrive.app.automation.type.EnumType} set (window area,
 * light zone, …) — a variable is differentiated by a FREE-TEXT name the user types
 * ("Parking_Mode", "Sport_Mode"). That name can't be an enum, so this subclass
 * overrides {@link #eventData} / {@link #automationCondition} / {@link #toJson} to
 * carry a {@code name} string field instead of the enum-variable machinery. The state
 * key it produces ({@code EventData("variable",{name})}) is identical to the one
 * {@link SetVariableAction#variableEvent} writes, so a set and a read line up.
 *
 * <p>Value comparison is string eq/neq (via the base {@link StringType} value), so
 * {@code Parking_Mode != true} works exactly as written; the name field is emitted in
 * the schema as its own string input the form renders alongside the value.
 */
public class VariableCondition extends EventCondition {
    // Reused for validating both the name and the value strings.
    private static final StringType NAME_TYPE =
            new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME);

    public VariableCondition(Label label, String description) {
        // Value is a free-text string compared with eq/neq. No enum variables — the
        // name is handled bespoke below rather than through the enum-variable list.
        super(label, description, new StringType(new Label("value", "automation.variable_value"),
                SetVariableAction.MAX_VALUE));
    }

    /**
     * Build the {@link EventData} for the referenced variable. Reads the free-text
     * {@code name} from the config's {@code variables.name} and keys the event by it —
     * matching {@link SetVariableAction#variableEvent}. Returns null on a missing/blank
     * or over-long name so a malformed config is rejected (mirrors the base validator).
     */
    @Override
    public EventData eventData(JSONObject input) {
        try {
            JSONObject variablesJson = input.optJSONObject("variables");
            if (variablesJson == null) return null;
            String name = variablesJson.optString("name", "").trim();
            if (name.isEmpty() || !NAME_TYPE.isValidValue(name)) return null;
            return SetVariableAction.variableEvent(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the {@link AutomationCondition}: the variable event + a string
     * comparator/value. Mirrors the base method but sources the event from our
     * name-based {@link #eventData} rather than the enum path.
     */
    @Override
    public AutomationCondition automationCondition(JSONObject input) {
        try {
            EventData eventData = eventData(input);
            if (eventData == null) return null;

            String comparator = input.getString("comparator");
            if (!getValue().isValidComparator(comparator)) return null;

            Object value = input.get("value");
            // Accept a dynamic ${…} reference (resolved live at compare time) or a value
            // that passes this condition's own type validation. Shared base helper.
            if (!isAcceptableConditionValue(value)) return null;

            return new AutomationCondition(eventData, comparator, value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Schema JSON. Emits a single free-text {@code name} variable (the variable's
     * name) plus the standard comparator + string value the form already renders. The
     * base class would emit the (empty) enum-variable list; we replace it with the
     * name string input so the frontend shows a "Variable name" text field.
     */
    @Override
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();
        try {
            json.put("type", "event");
            org.json.JSONArray variables = new org.json.JSONArray();
            variables.put(NAME_TYPE.toJson());
            json.put("variables", variables);
            json.put("description", getDescription());
            json.put("comparator", getValue().getComparators().toJson());
            json.put("value", getValue().toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
