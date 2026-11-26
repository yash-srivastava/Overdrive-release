package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;

import org.json.JSONException;
import org.json.JSONObject;

public class StringType extends StringSchemaType {
    private static final String TYPE = "string";

    private final int maxLength;
    // Optional i18n key for a cautionary note rendered under the field (e.g. the
    // shell-command action's "runs unattended" warning). null = no warning.
    private final String warningKey;

    /**
     * A string representation
     * Will take a maxLength to limit the size of the string
     *
     * @param label     An id and display name for this int
     * @param maxLength The maximum length this string can be (inclusive)
     */
    public StringType(Label label, int maxLength) {
        this(label, maxLength, null);
    }

    /**
     * A string variable with an optional cautionary warning rendered beneath the
     * input in the automation form (amber box, matching the key-mapping shell UI).
     *
     * @param label      An id and display name for this string
     * @param maxLength  The maximum length this string can be (inclusive)
     * @param warningKey i18n key for a warning note, or null for none
     */
    public StringType(Label label, int maxLength, String warningKey) {
        super(label, TYPE);
        this.maxLength = maxLength;
        this.warningKey = warningKey;
    }

    /**
     * The maximum length this string can be (inclusive)
     *
     * @return The maximum length this string can be (inclusive)
     */
    public int getMaxLength() {
        return maxLength;
    }

    /**
     * Check if the value length is less than maxLength
     *
     * @param value The value to check
     * @return true if valid, false otherwise
     */
    public boolean isValidValue(String value) {
        if (value == null) return false;

        return value.length() <= getMaxLength();
    }

    @Override
    protected void appendSchemaFields(JSONObject json) throws JSONException {
        json.put("maxLength", getMaxLength());
        if (warningKey != null) {
            // Resolve the warning server-side, just like labels.
            json.put("warning", com.overdrive.app.server.Messages.get(warningKey));
        }
    }
}
