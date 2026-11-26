package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.SignalAddressType;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;

import java.util.List;
import java.util.Map;

/**
 * A loop that runs its nested child actions repeatedly: a fixed count, WHILE a scalar
 * signal satisfies a comparison, or UNTIL it does. The loop body is the action's
 * {@code childActions} (nested action list), run through {@link Automations#runActionList}
 * so anything valid in an automation is valid in the loop, bounded by the same depth
 * guard.
 *
 * <p><b>Cannot park the worker.</b> Runs on the single AutomationQueue worker (like
 * {@link PauseAction}/{@link WaitUntilAction}), so it only delays THIS automation's
 * chain. Every iteration is bounded three ways: a hard {@link #MAX_ITERATIONS} cap, a
 * wall-clock {@link #MAX_WALL_MS} ceiling (critical — a child {@code waitUntil} is
 * itself up to 10 min, so count alone isn't enough), and an interrupt check (a
 * disable-all still kills the worker mid-loop). while/until re-evaluate the condition
 * against the SAME live state ({@link Automations#getStateValue}) every iteration.
 */
public class LoopAction extends DeclaredAction {
    private static final String TYPE = "loop";

    private static final int MAX_ITERATIONS = 1000;
    private static final long MAX_WALL_MS = 15 * 60 * 1000L; // 15 min total ceiling

    public LoopAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new EnumType(new Label("mode", "automation.loop_mode"),
                        new Label("count", "automation.loop_count_mode"),
                        new Label("while", "automation.loop_while"),
                        new Label("until", "automation.loop_until")),
                // Iteration count (count mode). Clamped to [1, MAX_ITERATIONS] at run time.
                new IntType(new Label("count", "automation.loop_iterations"), 1, MAX_ITERATIONS),
                // while/until: the scalar signal + comparison, mirroring WaitUntilAction.
                // LHS = an ADDRESS into the shared condition catalog (all ~58 conditions,
                // attributed ones included) instead of a hardcoded signal list. Legacy stored
                // ids still resolve — see AutomationCondition.resolveSignalAddress.
                new SignalAddressType(new Label("event", "automation.wait_signal")),
                IntValue.COMPARATORS,
                new DynamicIntType(new Label("value", "automation.value"), -540, 1000)));
    }

    /** This action carries a nested loop-body action list. */
    @Override public boolean hasChildActions() { return true; }

    /**
     * Resolve the stored LHS address to the state key it names. Delegates to
     * {@link AutomationCondition#resolveSignalAddress}, the single home of the address
     * grammar (shared with the condition RHS), so this action covers the whole condition
     * catalog — attributed signals included — with no local mapping table, and the
     * pre-catalog ids saved automations still hold resolve to the same signals as before.
     */
    private static EventData resolveEvent(String id) {
        return AutomationCondition.resolveSignalAddress(id);
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String mode = str(vars.get("mode"));
        if (mode == null) mode = "count";
        List<AutomationAction> body = automationAction.getChildActions();
        if (body.isEmpty()) return; // nothing to loop

        int cap = clamp(toInt(vars.get("count"), 1), 1, MAX_ITERATIONS);
        // while/until condition parts (ignored in count mode). The RHS `value` may be a
        // constant OR a dynamic ${var:…}/${signal:…} token — kept raw and evaluated
        // through the shared condition path each iteration.
        EventData event = resolveEvent(str(vars.get("event")));
        String comparator = str(vars.get("comparator"));
        Object target = vars.get("value");
        boolean condMode = "while".equals(mode) || "until".equals(mode);
        if (condMode && (event == null || comparator == null || target == null)) {
            logger.warn("LoopAction: " + mode + " needs event/comparator/value — skipping");
            return;
        }

        // {wall-clock deadline, iterations admitted}. A queued run resuming after a yielded
        // wait replays its admitted iterations without re-checking either bound.
        Object recorded = Automations.queuedFlowRecord(automationAction);
        long[] progress = recorded instanceof long[]
                ? (long[]) recorded
                : new long[] {System.currentTimeMillis() + MAX_WALL_MS, 0L};
        Automations.recordQueuedFlow(automationAction, progress);
        int i = 0;
        while (i < cap) {
            if (Thread.currentThread().isInterrupted()) return; // worker teardown
            if (i >= progress[1]) {
                if (System.currentTimeMillis() >= progress[0]) {
                    logger.info("LoopAction: wall-clock cap hit after " + i + " iterations");
                    return;
                }
                if (condMode) {
                    // Re-evaluate against live state (supports dynamic RHS + coercion).
                    boolean held = AutomationCondition.evaluate(event, comparator, target);
                    // while: stop when the condition stops holding; until: stop once it holds.
                    if ("while".equals(mode) && !held) break;
                    if ("until".equals(mode) && held) break;
                }
                progress[1] = i + 1;
            }
            Automations.runActionList(body); // re-entrant; depth-guarded
            if (Automations.queuedDeferralPending()) return;
            i++;
            // A Wait Until inside the body that timed out aborts the whole run — stop looping
            // rather than re-entering the body (runActionList would skip every action anyway,
            // spinning up to `cap` empty iterations).
            if (Automations.chainAborted()) {
                logger.info("LoopAction: stopping after " + i + " iterations (wait timed out)");
                return;
            }
        }
        logger.info("LoopAction: ran " + i + " iterations (mode=" + mode + ")");
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return def; }
    }
}
