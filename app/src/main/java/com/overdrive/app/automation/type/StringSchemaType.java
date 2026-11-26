package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

/** String-backed types share the eq/neq comparator set. */
abstract class StringSchemaType extends SchemaType<String> {
    StringSchemaType(Label label, String type) {
        super(label, type);
    }

    @Override
    public EnumType getComparators() {
        return StringValue.COMPARATORS;
    }

    /** Non-empty bounded ASCII alphanumerics plus the selector's allowed punctuation. */
    protected static boolean isBoundedToken(String value, int maxLength, String punctuation) {
        if (value == null || value.isEmpty() || value.length() > maxLength) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || punctuation.indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }
}
