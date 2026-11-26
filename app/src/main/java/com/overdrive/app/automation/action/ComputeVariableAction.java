package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.ExpressionEvaluator;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

import java.util.List;
import java.util.Map;

/**
 * Stores the result of an arithmetic EXPRESSION in a user variable — the general form of
 * {@link IncrementVariableAction}, which can only add a constant.
 *
 * <p>The expression may mix constants, live signals and other variables:
 * {@code 500 + (${signal:batteryLevel} - 20) * 3.75}. Operands use the same
 * {@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}} grammar as a condition's right-hand
 * side, so there is one way to address state across the whole engine (see
 * {@link com.overdrive.app.automation.AutomationCondition}).
 *
 * <p>Writes through {@link Automations#update} like every other variable write, so the
 * result transitions the variable and can itself trigger automations. An expression that
 * can't be evaluated (unset reference, non-numeric value, divide-by-zero, syntax error)
 * leaves the variable UNCHANGED rather than writing a wrong or zero value — a stale
 * reading is recoverable, a silently-wrong one drives a bad ADB tap.
 */
public class ComputeVariableAction extends DeclaredAction {
    private static final String TYPE = "computeVariable";

    public ComputeVariableAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME),
                // Plain StringType, not DynamicStringType: the whole point is a free-text
                // expression that may EMBED several ${…} tokens, so the single-token picker
                // would be the wrong control here. The syntax is documented in this action's
                // description rather than the StringType warning slot, which renders an amber
                // caution box meant for genuine hazards (the shell field).
                new StringType(new Label("expression", "automation.variable_expression"),
                        ExpressionEvaluator.MAX_LENGTH)));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String name = vars.get("name") == null ? null : vars.get("name").toString();
        if (name == null || name.trim().isEmpty()) {
            logger.warn("ComputeVariableAction: missing variable name, skipping");
            return;
        }
        name = name.trim();
        if (name.length() > SetVariableAction.MAX_NAME) {
            name = name.substring(0, SetVariableAction.MAX_NAME);
        }

        String expression = vars.get("expression") == null ? "" : vars.get("expression").toString();
        Double result = ExpressionEvaluator.evaluate(expression);
        if (result == null) {
            // Logged with the expression so "why didn't my variable update?" is answerable
            // from logcat — the usual cause is a signal that hasn't fired since boot.
            logger.warn("ComputeVariableAction: could not evaluate '" + expression
                    + "' for variable '" + name + "'; leaving it unchanged");
            return;
        }

        String out = ExpressionEvaluator.format(result);
        if (out.length() > SetVariableAction.MAX_VALUE) {
            out = out.substring(0, SetVariableAction.MAX_VALUE);
        }
        Automations.update(SetVariableAction.variableEvent(name), out);
        logger.info("ComputeVariableAction: " + name + " = " + out + "  [" + expression + "]");
    }
}
