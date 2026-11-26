package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

/**
 * A target-automation selector for the "Control Automation" action. The value is an
 * automation id (UUID) stored as a string; the frontend renders a live dropdown from
 * {@code GET /api/automations/picker} showing each automation's name (or a generated
 * fallback). Mirrors {@link ActionGroupType} / {@link AudioType} — the option set is
 * live, not baked into the schema, since automations change per device.
 *
 * <p>Reuses {@link StringValue} as its backing value. The daemon resolves the id at
 * fire time and no-ops on an unknown/deleted target.
 */
public class AutomationType extends StringSchemaType {
    private static final String TYPE = "automationRef";

    public AutomationType(Label label) {
        super(label, TYPE);
    }

    /** A UUID-ish id: non-empty, bounded, hex + hyphen + token charset. Syntactic only —
     *  the daemon checks the id actually resolves to an automation at fire time. */
    public boolean isValidValue(String value) {
        return isBoundedToken(value, 64, "-_");
    }
}
