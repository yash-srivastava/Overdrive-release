package com.overdrive.app.automation.type;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.value.Label;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The left-hand-side signal picker for the FLOW actions (if/else, wait-until,
 * wait-until-signal, loop): a reference to any signal in the condition catalog, stored as an
 * ADDRESS string.
 *
 * <p><b>Why this type exists.</b> Each flow action used to declare its own small
 * {@code EnumType} of ~9 comparable signals plus a private {@code resolveEvent} switch, so the
 * four lists drifted from each other and from the 58-condition catalog — an "if" could not test
 * gear, a door, a seatbelt, or any of the 10 attributed signals. The address grammar
 * ({@code TYPE} or {@code TYPE:k=v,…}) already exists as the condition RHS's
 * {@code ${signal:…}} token, so pointing these actions at it makes every condition usable
 * without a per-action mapping table.
 *
 * <p><b>Frontend contract.</b> {@link #toJson()} marks this field {@code signalAddress}, which
 * the editor renders with the SAME picker the condition RHS uses: a categorised list of every
 * condition plus a selector for each attribute the chosen signal declares (side, area, seat,
 * units…), emitting {@code ${signal:TYPE:k=v}}. So the option list is the catalog by
 * construction and cannot fall behind it.
 *
 * <p><b>Validation is deliberately shape-only.</b> {@link #isValid(Object)} accepts any bounded
 * non-blank string rather than checking membership of the catalog. This is load-bearing for
 * saved automations: {@code BaseAction.fromJson} rejects an action whose stored variable fails
 * validation and that drops the WHOLE automation, so a signal that a firmware/trim doesn't
 * expose — or a legacy id — must still load. A wrong address resolves to no state at compare
 * time and the action fails safe (condition not met), which is the same outcome the actions
 * already had for an unknown id, without the data loss.
 */
public class SignalAddressType extends StringSchemaType {

    private static final String TYPE = "signalAddress";

    /** Bound so a hand-edited or imported config can't store an unbounded string. */
    private static final int MAX_LEN = 128;

    public SignalAddressType(Label label) {
        super(label, TYPE);
    }

    @Override
    public boolean isValidValue(String value) {
        if (value == null) return false;
        String s = value.trim();
        // Shape only — see the class note on why membership is NOT checked here.
        return !s.isEmpty() && s.length() <= MAX_LEN;
    }

    /** Does this address name a resolvable signal right now? Diagnostics only, never validation. */
    public static boolean isResolvable(String address) {
        return AutomationCondition.resolveSignalAddress(address) != null;
    }

    @Override
    protected void appendSchemaFields(JSONObject json) throws JSONException {
        // Render the shared signal picker rather than a plain text input.
        json.put("signalAddress", true);
    }
}
