package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;

public class TimeType extends SchemaType<Integer> {
    private static final String TYPE = "time";
    private static final int MINUTES_PER_DAY = 60 * 24;

    /**
     * A time-of-day representation.
     * Expects the value to be the number of minutes since the start of the day (0..1439).
     * Comparisons are numeric, so midnight is the smallest time and 23:59 the largest.
     *
     * @param label An id and display name for this time
     */
    public TimeType(Label label) {
        super(label, TYPE);
    }

    /**
     * The comparators for this type.
     * Time is compared numerically, so it reuses the integer comparators.
     *
     * @return The comparators for this type
     */
    public EnumType getComparators() {
        return IntValue.COMPARATORS;
    }

    /**
     * Check if the value is valid.
     * The value should not exceed the number of minutes in a day.
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(Integer value) {
        if (value == null) return false;

        return value >= 0 && value < MINUTES_PER_DAY;
    }
}
