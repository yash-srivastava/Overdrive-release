package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

import org.json.JSONException;
import org.json.JSONObject;

/** Shared label and frontend schema envelope for built-in automation types. */
abstract class SchemaType<T> extends BaseType<T> {
    private final Label label;
    private final String type;

    SchemaType(Label label, String type) {
        this.label = label;
        this.type = type;
    }

    @Override
    public Label getLabel() {
        return label;
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();
        try {
            json.put("type", type);
            appendSchemaFields(json);
        } catch (Exception ignored) {
            // Preserve the partial schema on serialization failure.
        }
        return json;
    }

    protected void appendSchemaFields(JSONObject json) throws JSONException {
    }
}
