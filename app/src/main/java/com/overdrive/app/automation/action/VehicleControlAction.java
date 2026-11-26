package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationQueue;
import com.overdrive.app.automation.ExpressionEvaluator;
import com.overdrive.app.automation.TextInterpolator;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.mqtt.VehicleControlCatalog;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class VehicleControlAction extends DeclaredAction {
    private static final String TYPE = "vehicle-control";

    /**
     * An action to send a vehicle control event
     * The variables passed in to this will be concatenated for the payload
     *
     * This is deprecated in favour of the ApiAction
     * Some actions will still use this for backward compatibility
     *
     * @param label       The label for this notification with an id and display name
     * @param description The description for this action
     * @param variables   The variables to concatenate for the payload
     */
    @Deprecated
    public VehicleControlAction(Label label, String description, Type... variables) {
        super(TYPE, label, description, List.of(variables));
    }

    /**
     * Resolve a payload that references live state, so a control value can be COMPUTED from the
     * vehicle instead of hardcoded — e.g. a charge cap of {@code MIN(${signal:batteryLevel},50)}
     * ("hold at where I am now, but never above 50"), which previously had to be a fixed number.
     *
     * <p>Two stages, both fail-safe:
     * <ol>
     *   <li>{@link TextInterpolator} substitutes {@code ${var:…}} / {@code ${signal:…}}. An
     *       unresolvable reference is left LITERAL (its documented behaviour).</li>
     *   <li>If the result still looks arithmetic, {@link ExpressionEvaluator} evaluates it and
     *       formats the number without a trailing {@code .0}, so a downstream int parse works.</li>
     * </ol>
     *
     * <p>A payload with no {@code ${} and no arithmetic is returned byte-identical, so every
     * existing automation is completely unaffected. If either stage fails the ORIGINAL text is
     * returned and the entity's own parser decides — never a silently substituted 0, which on a
     * charge cap or a mode select would command something the user did not ask for.
     */
    private static String resolvePayload(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String text = raw;
        try {
            if (text.indexOf("${") >= 0) text = TextInterpolator.interpolate(text);
        } catch (Throwable t) {
            logger.warn("Payload interpolation failed for '" + raw + "', using it literally");
            return raw;
        }
        // Only attempt arithmetic when the text actually contains an operator or a function
        // call. A bare word ("eco", "at_current") or a plain number must never reach the
        // evaluator — and words like "on"/"off" must not be coerced to a number.
        if (!looksArithmetic(text)) return text;
        Double value = ExpressionEvaluator.evaluate(text);
        if (value == null) {
            logger.warn("Payload expression '" + text + "' did not evaluate — passing it through literally");
            return text;
        }
        return ExpressionEvaluator.format(value);
    }

    /** Whether the text is worth handing to the arithmetic evaluator (operator or function call). */
    private static boolean looksArithmetic(String s) {
        boolean sawDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '+' || c == '*' || c == '/' || c == '%') return true;
            // '-' only counts as an operator between operands, so a negative literal
            // ("-5") and an identifier-ish word ("force-ev") are not treated as arithmetic.
            if (c == '-' && sawDigit) return true;
            if (c >= '0' && c <= '9') sawDigit = true;
        }
        return false;
    }

    /**
     * Trigger a vehicle control action
     * The variables stored will be concatenated and sent as the control payload
     * <p>
     * This method needs to be updated to implement the sub variable to allow more vehicle controls
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    public void trigger(AutomationAction automationAction) {
        triggerWithResult(automationAction);
    }

    @Override
    public boolean triggerWithResult(AutomationAction automationAction) {
        return triggerInternal(automationAction, false);
    }

    /**
     * Reconciliation entry point. It resolves the command normally but executes it only when it
     * belongs to the explicit idempotent setter allowlist.
     */
    public boolean triggerLatestStateSetterWithResult(
            AutomationAction automationAction) {
        return triggerInternal(automationAction, true);
    }

    private boolean triggerInternal(
            AutomationAction automationAction, boolean stateSetterOnly) {
        VehicleControlCatalog.ControlEntity entity = null;
        if (automationAction.getVariables().size() > 1) {
            // Add variables other than the key to the vehicle control id to allow seat_heat_driver and others to be built with variables
            String suffix = super.getVariables().stream()
                    .filter(variable -> !"payload".equals(variable.getLabel().getId()))
                    .map(variable -> Objects.requireNonNullElse(automationAction.getVariables().get(variable.getLabel().getId()), "").toString())
                    .filter(variable -> !variable.isBlank())
                    .collect(Collectors.joining("_"));
            logger.info("Built entity with variables: " + getLabel().getId() + "_" + suffix);
            if (!suffix.isBlank()) {
                entity = VehicleControlCatalog.get(getLabel().getId() + "_" + suffix);
            }
        }
        if (entity == null) entity = VehicleControlCatalog.get(getLabel().getId());
        if (entity == null) {
            logger.error("Entity for vehicle control automation missing: " + getLabel().getId());
            return false;
        }
        // Vehicle control currently only uses a single variable for an action
        String payload = Objects.requireNonNullElse(automationAction.getVariables().get("payload"), "").toString();
        payload = resolvePayload(payload);
        // TODO: Add other variables in to sub for actions like climate control
        // Pass the latest vehicle snapshot so composite commands can preserve sibling state (e.g. a
        // seat_heat_driver command reads the other zones' current levels from the snapshot; a null
        // snapshot would reset them all to 0). getData() may still be null before the first collect,
        // which the catalog handlers already tolerate.
        BydVehicleData snapshot = BydDataCollector.getInstance().getData();
        VehicleControlCatalog.ControlAction action = entity.toAction(null, payload, snapshot);
        if (action == null || action.command == null) {
            logger.error("Action for vehicle control automation entity missing: " + getLabel().getId());
            return false;
        }

        final String resolvedPayload = payload;
        AutomationQueue.CompensableStateSetter setter =
                compensableStateSetter(action.command);
        if (stateSetterOnly && setter == null) {
            return true;
        }
        AutomationQueue.LatestStateCompensationToken compensationToken = null;
        if (setter != null) {
            compensationToken = AutomationQueue.registerLatestStateCompensation(
                    setter,
                    () -> executeControlAction(
                            getLabel().getId(), resolvedPayload, action));
        }
        boolean successful = false;
        try {
            successful =
                    executeControlAction(getLabel().getId(), resolvedPayload, action);
            return successful;
        } finally {
            AutomationQueue.completeLatestStateCompensation(
                    compensationToken, successful);
        }
    }

    /** The complete latest-state compensation allowlist. */
    private static AutomationQueue.CompensableStateSetter compensableStateSetter(
            VehicleCommandRouter.VehicleCommand command) {
        if (command instanceof VehicleCommandRouter.OperationModeCommand) {
            return AutomationQueue.CompensableStateSetter.OPERATION_MODE;
        }
        if (command instanceof VehicleCommandRouter.EnergyModeCommand) {
            return AutomationQueue.CompensableStateSetter.ENERGY_MODE;
        }
        if (command instanceof VehicleCommandRouter.WirelessChargingCommand) {
            return AutomationQueue.CompensableStateSetter.WIRELESS_CHARGING_GLOBAL;
        }
        if (command instanceof VehicleCommandRouter.WirelessChargingPadCommand) {
            int pad = ((VehicleCommandRouter.WirelessChargingPadCommand) command).pad;
            if (pad == BydDataCollector.WIRELESS_PAD_LEFT) {
                return AutomationQueue.CompensableStateSetter.WIRELESS_CHARGING_LEFT;
            }
            if (pad == BydDataCollector.WIRELESS_PAD_RIGHT) {
                return AutomationQueue.CompensableStateSetter.WIRELESS_CHARGING_RIGHT;
            }
        }
        return null;
    }

    private static boolean executeControlAction(
            String entityId, String payload, VehicleControlCatalog.ControlAction action) {
        VehicleCommandRouter.CommandResult result =
                VehicleCommandRouter.getInstance().execute(action.command);
        // Record what we commanded for a blind-toggle entity once it reached the vehicle, but
        // not when it was refused before getting there (see ControlAction.commitIfAttempted).
        if (result != null) action.commitIfAttempted(result.outcome);
        if (result != null && result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
            logger.warn("Vehicle control '" + entityId + "' payload='" + payload
                    + "' -> " + action.command.name() + " " + result.outcome
                    + " (" + result.displayMessage + ")");
        }
        return result != null
                && result.outcome == VehicleCommandRouter.Outcome.SUCCESS;
    }
}
