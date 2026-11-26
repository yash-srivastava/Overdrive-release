package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Adds a (possibly negative) amount to a NUMERIC user variable — counters and simple
 * arithmetic that {@link SetVariableAction} (which only sets a fixed value) can't do.
 * E.g. "count door opens": increment {@code door_count} by 1 each trigger.
 *
 * <p>Reuses the exact same variable state as {@link SetVariableAction}: the variable is
 * an {@link com.overdrive.app.automation.condition.EventData} of type {@code variable}
 * keyed by name, stored as a string. This action reads the current value, parses it as
 * a number (missing/non-numeric → 0, so a brand-new counter starts at 0), applies the
 * signed delta, and writes it back with {@link Automations#update} — which transitions
 * the variable and fires any automation that triggers on it, identical to a plain set.
 * The written value is normalised (no trailing ".0" for whole numbers) so a downstream
 * string {@code eq} comparison against "5" still matches.
 */
public class IncrementVariableAction extends DeclaredAction {
    private static final String TYPE = "incrementVariable";

    public IncrementVariableAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME),
                // Signed step. IntType range is generous but bounded so a hand-edited config
                // can't request an absurd value; the stored result is still bounded below.
                new IntType(new Label("amount", "automation.variable_amount"), -1000000, 1000000)));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String name = vars.get("name") == null ? null : vars.get("name").toString();
        if (name == null || name.trim().isEmpty()) {
            logger.warn("IncrementVariableAction: missing variable name, skipping");
            return;
        }
        name = name.trim();
        double delta = toDouble(vars.get("amount"), 0);

        // Read the current value from the shared state (may be absent / non-numeric).
        double current = 0;
        Value cur = Automations.getStateValue(SetVariableAction.variableEvent(name));
        if (cur != null) current = toDouble(cur.toString(), 0);

        double result = current + delta;
        // Normalise: whole numbers as "5", not "5.0", so string eq comparisons match.
        String out = (result == Math.rint(result) && !Double.isInfinite(result))
                ? Long.toString((long) result)
                : Double.toString(result);
        if (out.length() > SetVariableAction.MAX_VALUE) out = out.substring(0, SetVariableAction.MAX_VALUE);

        Automations.update(SetVariableAction.variableEvent(name), out);
        logger.info("IncrementVariableAction: " + name + " " + current + " + " + delta + " = " + out);
    }

    /** Parse an Object/String to double, returning {@code def} on null/blank/non-numeric. */
    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        try { return Double.parseDouble(o.toString().trim()); }
        catch (Exception e) { return def; }
    }
}
