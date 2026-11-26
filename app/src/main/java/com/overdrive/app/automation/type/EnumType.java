package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class EnumType extends StringSchemaType {
    private static final String TYPE = "enum";

    private final Set<Label> options;
    private final Set<String> ids;

    /**
     * An enum representation
     * Can take any number of options with an id and display value to show on the frontend
     * Will probably be displayed as a drop-down with a placeholder for the enum label
     *
     * @param label   An id and display name for this enum
     * @param options A list of options which can be selected
     */
    public EnumType(Label label, Label... options) {
        super(label, TYPE);
        // Maintain the order of the options but ensure they are unique
        this.options = new LinkedHashSet<>(Arrays.asList(options));
        // Maintain a set of ids for quick lookup instead of having to make a label to lookup the options set
        this.ids = this.options.stream()
                .map(Label::getId)
                .collect(Collectors.toSet());
    }

    /**
     * The options that are stored for this enum
     *
     * @return The options that are stored for this enum
     */
    public Set<Label> getOptions() {
        return options;
    }

    /**
     * Lookup the value in the ids set to see if this enum accepts this option
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(String value) {
        return this.ids.contains(value);
    }

    @Override
    protected void appendSchemaFields(JSONObject json) throws JSONException {
        JSONArray opt = new JSONArray();
        for (Label option : getOptions()) {
            opt.put(option.toJson());
        }
        json.put("options", opt);
    }
}
