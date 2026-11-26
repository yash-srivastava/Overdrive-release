package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

import java.util.List;

/** An event with one free-text key instead of a fixed enum attribute. */
abstract class TextKeyCondition extends EventCondition {
    private final StringType keyType;

    TextKeyCondition(Label label, String description, StringType value, StringType keyType) {
        super(label, description, value);
        this.keyType = keyType;
    }

    @Override
    public EventData eventData(JSONObject input) {
        try {
            JSONObject variables = input.optJSONObject("variables");
            if (variables == null) return null;
            String key = variables.optString(keyType.getLabel().getId(), "").trim();
            if (key.isEmpty() || !keyType.isValidValue(key)) return null;
            return eventForKey(key);
        } catch (Exception e) {
            return null;
        }
    }

    protected abstract EventData eventForKey(String key);

    @Override
    protected List<? extends Type> schemaVariables() {
        return List.of(keyType);
    }

    @Override
    protected String mqttSchemaKey() {
        // Neither free-text event advertises a vehicle telemetry twin.
        return null;
    }
}
