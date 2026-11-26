package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventCondition {
    private static final String TYPE = "event";

    private final Label label;
    private final String description;
    private final Type value;
    private final List<EnumType> variables;

    /**
     * A condition based on an event happening
     * This does not have an interface as all conditions should be event driven
     * The actions should not hold up other code as they are pulled from a queue and actioned in a thread
     * The variables have been restricted to Enum types to prevent a large state map which may degrade performance
     *
     * @param label       The label for this condition with an id and display name
     * @param description The description for this condition
     * @param value       The type with constraints for the potential state values
     * @param variables   The variables that will also be set in the state. This allows extra options such as area for windows
     */
    public EventCondition(Label label, String description, Type value, EnumType... variables) {
        this.label = label;
        this.description = description;
        this.value = value;
        this.variables = List.of(variables);
    }

    /**
     * The label that was stored when this Condition was initialized
     *
     * @return The Label for this condition
     */
    public Label getLabel() {
        return label;
    }

    /**
     * The description for this condition
     * Will be translated using the language files
     *
     * @return The description for this condition
     */
    public String getDescription() {
        return Messages.get(description);
    }

    /**
     * The value type for this condition
     * It will have restrictions such as min and max for numbers or options for an enum
     *
     * @return The value type for this condition
     */
    public Type getValue() {
        return value;
    }

    /**
     * The variables for this condition
     *
     * @return These will be enums and need to match events passed in to Automations.update
     */
    public List<EnumType> getVariables() {
        return variables;
    }

    /**
     * Create a JSON object with the fields required for the frontend to display
     *
     * @return JSON representation of this condition
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", TYPE);
            JSONArray variables = new JSONArray();
            for (Type variable : schemaVariables()) {
                variables.put(variable.toJson());
            }
            json.put("variables", variables);
            json.put("description", getDescription());
            // The MQTT telemetry key carrying the same fact, when one exists — a read-only
            // cross-reference for users who wire automations and Home Assistant against the
            // same car. Cosmetic: never stored, never resolved against. Omitted (not null)
            // when the signal has no twin, so the UI can simply test for presence.
            String mqtt = mqttSchemaKey();
            if (mqtt != null) json.put("mqtt", mqtt);
            json.put("comparator", getValue().getComparators().toJson());
            json.put("value", getValue().toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    protected List<? extends Type> schemaVariables() {
        return getVariables();
    }

    protected String mqttSchemaKey() {
        return SignalMqttMap.forId(getLabel().getId());
    }

    /**
     * Create an EventData instance by validating it with the config for this condition
     * This can be used for triggers as it has no comparator or value
     *
     * @return EventData instance
     */
    public EventData eventData(JSONObject input) {
        try {
            String type = getLabel().getId();
            Map<String, String> variables = new HashMap<>();
            JSONObject variablesJson = input.optJSONObject("variables");
            for (EnumType variable : getVariables()) {
                String key = variable.getLabel().getId();
                String value = variablesJson.getString(key);
                if (variable.isValid(value)) {
                    variables.put(key, value);
                } else {
                    return null;
                }
            }

            return new EventData(type, variables);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Create an AutomationCondition instance by validating it with the config for this condition
     * This will create the EventData that is also used for a trigger and add the comparator and value
     *
     * @return AutomationCondition instance
     */
    public AutomationCondition automationCondition(JSONObject input) {
        try {
            EventData eventData = eventData(input);
            if (eventData == null) return null;

            String comparator = input.getString("comparator");
            if (!getValue().isValidComparator(comparator)) return null;

            Object value = input.get("value");
            // Accept a DYNAMIC reference token (${var:…} / ${signal:…}) as the value even
            // for a typed (e.g. Int) condition — it's resolved to a live value at compare
            // time (see AutomationCondition.resolveDynamic), so it can't be validated
            // against the type's own constant constraints here. Bounded so a hand-edited
            // config can't store an unbounded string. Otherwise the value must satisfy the
            // type's normal validation (unchanged path for every existing automation).
            if (!isAcceptableConditionValue(value)) return null;

            return new AutomationCondition(eventData, comparator, value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a stored condition value is acceptable: either a bounded DYNAMIC reference
     * token ({@code ${…}}, resolved live at compare time) or a value that passes this
     * condition's own type validation. Shared with the free-text subclasses
     * ({@link VariableCondition}, {@link MqttTriggerCondition}) so a dynamic RHS is
     * accepted uniformly. Additive: a non-token value takes the identical
     * {@code getValue().isValid} path as before.
     *
     * @param value the raw value from the stored config
     * @return true if the value may be stored on the condition
     */
    protected boolean isAcceptableConditionValue(Object value) {
        if (AutomationCondition.isDynamicRef(value)) {
            // Bound the token length so a hand-edited/imported config can't store an
            // unbounded string as a "reference".
            return ((String) value).length() <= 128;
        }
        return getValue().isValid(value);
    }
}
