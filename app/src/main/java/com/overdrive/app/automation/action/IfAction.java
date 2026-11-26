package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.automation.type.SignalAddressType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Inline IF: evaluates a condition at trigger time and runs its {@code childActions}
 * (the "then" branch) when the condition holds, else its {@code elseChildActions}. This
 * is a MID-SEQUENCE branch, distinct from the whole-automation else branch (which fires
 * when the automation's OWN conditions fail at trigger time). Both child lists run
 * through {@link Automations#runActionList}, so they're depth-guarded and can nest.
 *
 * <p>The left-hand operand is either a live SIGNAL (speed/battery/…) or a user
 * VARIABLE (see {@link SetVariableAction}) — chosen via the {@code event} picker. When
 * {@code event == "variable"} a free-text {@code name} field names the variable, keyed to
 * the SAME state {@link EventData} {@link SetVariableAction#variableEvent} writes, so a
 * "set" and an "if" line up. A variable's live value is a string, so it compares with
 * eq/neq (the numeric gt/lt comparators are meaningless against it and would evaluate
 * false); the frontend swaps the comparator list to eq/neq when the variable LHS is
 * chosen. The right-hand side is the shared dynamic value ({@code ${var:…}}/{@code
 * ${signal:…}}/constant), so an If can compare a signal or variable against a constant,
 * another variable, or another signal.
 */
public class IfAction extends BaseAction {
    private static final String TYPE = "if";

    private final Label label;
    private final String description;
    private final List<Type> variables;

    // The optional free-text variable-name field, shown by the frontend only when the
    // LHS "event" picker is set to "variable". Handled BESPOKE (emitted in toJson, read in
    // fromJson) rather than via getVariables(), because BaseAction.fromJson requires EVERY
    // getVariables() entry to be present on load — adding "name" there would drop every
    // pre-existing If automation (which has no name key). Mirrors VariableCondition.
    private static final StringType NAME_TYPE =
            new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME);

    public IfAction(Label label, String description) {
        this.label = label;
        this.description = description;
        this.variables = List.of(
                // The LHS is an ADDRESS into the shared condition catalog, so an If can test
                // ANY of the ~58 conditions (including attributed ones like a specific door,
                // seat or light) instead of the 9 signals this action used to hardcode. The
                // legacy ids remain readable — see AutomationCondition.resolveSignalAddress.
                // "variable" still addresses a user variable via the bespoke "name" field.
                new SignalAddressType(new Label("event", "automation.wait_signal")),
                IntValue.COMPARATORS,
                new DynamicIntType(new Label("value", "automation.value"), -540, 1000));
    }

    public String getType() { return TYPE; }
    public Label getLabel() { return label; }
    public String getDescription() { return Messages.get(description); }
    public List<Type> getVariables() { return variables; }

    /** Carries then/else nested action lists. */
    @Override public boolean hasChildActions() { return true; }

    /**
     * Schema JSON: the standard action fields plus the bespoke {@code name} string input
     * so the frontend can show a "Variable name" field when the LHS is a variable. Additive
     * — the base fields are unchanged, and older UIs ignore the extra {@code nameField}.
     */
    @Override
    public JSONObject toJson() {
        JSONObject json = super.toJson();
        try {
            // Advertised as a discrete field (not inside the required "variables" array) so
            // the form renders it conditionally without the base all-required contract.
            json.put("nameField", NAME_TYPE.toJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }

    /**
     * Build the {@link AutomationAction}, tolerating the optional {@code name} field. Runs
     * the base validation over the required variables (event/comparator/value), then — only
     * when present — copies a valid {@code name} into the variable map so {@link #trigger}
     * can resolve a variable LHS. A missing {@code name} is fine (signal LHS); a present but
     * invalid one is rejected (mirrors the base validator's all-or-nothing contract).
     */
    @Override
    public AutomationAction fromJson(JSONObject input) {
        AutomationAction base = super.fromJson(input);
        if (base == null) return null;
        try {
            JSONObject variablesJson = input.optJSONObject("variables");
            if (variablesJson != null && variablesJson.has("name")) {
                Object nameVal = variablesJson.get("name");
                // Empty name is acceptable (StringType.isValidValue accepts ""), so a
                // variable LHS with a not-yet-typed name doesn't crash the load; trigger
                // treats a blank name as "no LHS" (event resolves null → runs nothing).
                if (!NAME_TYPE.isValid(nameVal)) return null;
                base.getVariables().put("name", nameVal);
            }
        } catch (Exception e) {
            return null;
        }
        return base;
    }

    /**
     * Resolve the stored LHS to the state key it names.
     *
     * <p>Delegates to {@link AutomationCondition#resolveSignalAddress} — the one place the
     * address grammar lives, shared with the condition RHS — so this action gets the whole
     * condition catalog (attributed signals included) with no per-action mapping table, and
     * the pre-catalog ids saved automations still hold keep resolving to the same signals.
     * "variable" is the one address that needs local handling, because it is keyed by the
     * free-text name in the bespoke "name" field rather than by a fixed type.
     */
    private static EventData resolveEvent(String id, String name) {
        if (id == null) return null;
        if ("variable".equals(id.trim())) {
            // Blank name → no LHS (trigger runs the else branch).
            if (name == null || name.trim().isEmpty()) return null;
            return SetVariableAction.variableEvent(name.trim());
        }
        return AutomationCondition.resolveSignalAddress(id);
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String eventId = str(vars.get("event"));
        EventData event = resolveEvent(eventId, str(vars.get("name")));
        String comparator = str(vars.get("comparator"));
        Object target = vars.get("value");   // constant (Integer/String) OR ${var:…}/${signal:…}
        if (event == null || comparator == null || target == null) {
            logger.warn("IfAction: missing/invalid condition — skipping (running nothing)");
            return;
        }
        // Evaluate through the shared condition path so a constant, a ${var:NAME}, or a
        // ${signal:TYPE} RHS all compare identically to a real condition (incl. numeric
        // coercion, and string eq/neq when the LHS is a variable). Fail-safe: unknown/
        // unresolved → false → the else branch runs.
        // A queued run resuming after a yielded wait keeps the branch it already took.
        Object recorded = Automations.queuedFlowRecord(automationAction);
        boolean held = recorded instanceof Boolean
                ? (Boolean) recorded
                : AutomationCondition.evaluate(event, comparator, target);
        Automations.recordQueuedFlow(automationAction, held);
        // then = childActions, else = elseChildActions. Either may be empty.
        Automations.runActionList(held
                ? automationAction.getChildActions()
                : automationAction.getElseChildActions());
        if (Automations.queuedDeferralPending()) return;
        logger.info("IfAction: " + event.getType() + " " + comparator + " " + target + " → " + (held ? "then" : "else"));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
