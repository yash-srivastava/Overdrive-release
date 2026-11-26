package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;

import org.json.JSONException;
import org.json.JSONObject;

public class IntType extends SchemaType<Integer> {
    private static final String TYPE = "int";

    private final int min;
    private final int max;

    /**
     * An int representation
     * Will take a min and max value to restrict the possible options
     *
     * @param label An id and display name for this int
     * @param min   The minimum value this int can be (inclusive)
     * @param max   The maximum value this int can be (inclusive)
     */
    public IntType(Label label, int min, int max) {
        super(label, TYPE);
        this.min = min;
        this.max = max;
    }

    /**
     * The minimum value this int can be (inclusive)
     *
     * @return The minimum value this int can be (inclusive)
     */
    public int getMin() {
        return min;
    }

    /**
     * The maximum value this int can be (inclusive)
     *
     * @return The maximum value this int can be (inclusive)
     */
    public int getMax() {
        return max;
    }

    /**
     * The comparators for this int
     *
     * @return The comparators for this int
     */
    public EnumType getComparators() {
        return IntValue.COMPARATORS;
    }

    /**
     * Check if the value is between the min and max for this type
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value.compareTo(getMin()) >= 0 && value.compareTo(getMax()) <= 0;
    }

    @Override
    protected void appendSchemaFields(JSONObject json) throws JSONException {
        json.put("min", getMin());
        json.put("max", getMax());
    }
}
