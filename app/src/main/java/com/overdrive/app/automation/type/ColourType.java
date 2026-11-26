package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class ColourType extends SchemaType<Integer> {
    private static final String TYPE = "colour";
    private final List<String> colourCodes;

    /**
     * A colour representation.
     * This is a very specific type to work with the BYD api which is not likely to be used more than once.
     * Takes a list of colours and expects the return value to be the 1-based index of that colour.
     *
     * @param label       An id and display name for this colour
     * @param colourCodes The options for which colours this could be
     */
    public ColourType(Label label, String... colourCodes) {
        super(label, TYPE);
        this.colourCodes = Arrays.asList(colourCodes);
    }

    /**
     * The list of colours this type represents
     *
     * @return The colour codes this type can hold
     */
    public List<String> getColourCodes() {
        return colourCodes;
    }

    /**
     * The comparators for this type.
     * ColourType is only used as an ACTION variable (an index selection), never as a
     * condition, so comparators are never requested — actions call isValid() only.
     * Returning null here is safe for that use; do NOT reuse this type in a condition.
     *
     * @return null
     */
    public EnumType getComparators() {
        return null;
    }

    /**
     * Check if the value is valid.
     * The value should be a position in the colour codes list but with a 1-based index.
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value > 0 && value <= getColourCodes().size();
    }

    @Override
    protected void appendSchemaFields(JSONObject json) throws JSONException {
        json.put("colourCodes", new JSONArray(getColourCodes()));
    }
}
