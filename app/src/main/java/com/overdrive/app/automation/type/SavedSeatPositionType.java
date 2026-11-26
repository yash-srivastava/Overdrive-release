package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

/**
 * A saved seat/mirror position selector. The value is a position id (String) from
 * OverDrive's own position store, not one of the car's hardware memory banks.
 *
 * <p>Modelled on {@link AppType}: the option set is NOT baked into the schema, because
 * positions are created and deleted by the user at runtime and captured entries differ
 * per signed-in DiLink profile. The frontend renders it as a dropdown populated live
 * from {@code GET /api/positions} and resolves the stored id back to the position's
 * name for display.
 *
 * <p>Storing the id rather than the name is deliberate. A captured entry's name is
 * rebuilt from the car on every capture ("&lt;nickName&gt; - &lt;slot name&gt;"), so a
 * name-keyed automation would silently stop matching after a rename in the car. The id
 * is the store's upsert key and survives both re-capture and rename.
 *
 * <p>Reuses {@link StringValue} as its backing value so no new Value type or
 * {@code Automations.update} overload is needed (same trick as AppType/ColourType).
 */
public class SavedSeatPositionType extends StringSchemaType {
    private static final String TYPE = "savedSeatPosition";

    /**
     * @param label An id and display name for this position selector
     */
    public SavedSeatPositionType(Label label) {
        super(label, TYPE);
    }

    /**
     * A position id is valid if it is a non-empty, bounded string over the charset the store
     * actually produces: {@code sanitize(profile) + "-slot-" + N} for captured entries and
     * {@code "user-" + sanitize(name)} for user-created ones, both of which are lowercase
     * alphanumerics plus '_' and '-'.
     *
     * <p>Restricting the charset here is defensive, exactly as in AppType: the value is
     * substituted into the JSON body {@code {"id":"&lt;value&gt;"}} of the apply action, and a
     * hand-crafted or imported automation could otherwise smuggle a {@code "} that breaks or
     * injects into that JSON. The picker only ever offers ids that exist, so this never
     * rejects a legitimately-chosen position.
     */
    public boolean isValidValue(String value) {
        return isBoundedToken(value, 128, "_-");
    }
}
