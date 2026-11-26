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
 * Automation action that blocks the action chain until a chosen scalar vehicle
 * signal satisfies a comparison, or a timeout elapses.
 *
 * <p>Example: "when I park (gear = P) → wait until speed = 0 → close windows". The
 * wait runs on the single {@link com.overdrive.app.automation.AutomationQueue} worker
 * thread, so — like {@link PauseAction} — it delays only the rest of THIS
 * automation's chain, never the telemetry/HTTP threads or other automations.
 *
 * <p>It polls the SAME live automation state ({@link Automations#getStateValue}) that
 * triggers and conditions evaluate against, so it observes exactly the values the
 * telemetry/network/bluetooth events publish. Polling (not event-driven blocking) is
 * deliberate: the worker must stay simple and killable, and a short poll interval is
 * cheap for a bounded wait.
 *
 * <p>Scope is intentionally the SCALAR signals for which "wait until &lt;x&gt;
 * &lt;compare&gt; &lt;n&gt;" is meaningful (speed, pedals, steering, battery,
 * temperature, range). Enum/string signals (gear, lights, wifi) are better expressed
 * as a trigger+condition; keeping this to numeric signals avoids the multi-variable
 * sub-key plumbing and keeps the form a flat, self-explanatory row.
 */
public class WaitUntilAction extends DeclaredAction {
    private static final String TYPE = "waitUntil";

    // Poll cadence + absolute ceiling. The worker sleeps POLL_MS between state reads;
    // the wait can never exceed the user's timeout (clamped to MAX_TIMEOUT_S) so a
    // never-satisfied condition can't park the worker forever.
    private static final long POLL_MS = 250L;
    private static final int MAX_TIMEOUT_S = 600; // 10 min hard ceiling
    private static final int DEFAULT_TIMEOUT_S = 30;

    public WaitUntilAction(Label label, String description) {
        // event: which scalar signal to watch. The ids MUST match the EventData keys
        // published by BydEvent so resolveEvent() can map them back. comparator/value
        // reuse the IntValue comparators + a 0..1000 int (wide enough for range/temp/
        // speed/percent; signed steering handled by allowing negatives via min).
        super(TYPE, label, description, List.of(
                // LHS = an ADDRESS into the shared condition catalog (all ~58 conditions,
                // attributed ones included) instead of a hardcoded signal list. Legacy stored
                // ids still resolve — see AutomationCondition.resolveSignalAddress.
                new SignalAddressType(new Label("event", "automation.wait_signal")),
                // Reuse the shared IntValue comparator set (eq/neq/gt/lt/gte/lte).
                cloneComparators(),
                // RHS: a constant OR a dynamic ${var:…}/${signal:…} token (resolved live).
                new DynamicIntType(new Label("value", "automation.value"), -540, 1000),
                // min 0, not 1: 0 means "wait the full ceiling" (see clampTimeout). A 1s
                // timeout is almost always a mistake — the chain continues either way, so a
                // too-short wait silently behaves as if the wait weren't there.
                new IntType(new Label("timeout", "automation.wait_timeout"), 0, MAX_TIMEOUT_S)));
    }

    /** A comparator picker backed by the same enum IntValue exposes to conditions. */
    private static EnumType cloneComparators() {
        return IntValue.COMPARATORS;
    }

    /**
     * Map the picker's event id to the published {@link EventData}. Returns null for
     * an unknown id (defensive — a hand-edited config), which makes trigger() a no-op.
     */
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

    /**
     * Block until the chosen signal satisfies (comparator, value) or the timeout
     * elapses. Polls the shared automation state every {@link #POLL_MS}. Returns
     * immediately if the condition is already met. Interrupt (worker teardown) is
     * re-asserted so the worker unwinds cleanly.
     */
    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        EventData event = resolveEvent(str(vars.get("event")));
        String comparator = str(vars.get("comparator"));
        Object target = vars.get("value");   // constant OR ${var:…}/${signal:…}
        int timeoutS = clampTimeout(toInt(vars.get("timeout")));

        if (event == null || comparator == null || target == null) {
            logger.warn("WaitUntilAction: missing/invalid parameters, skipping");
            return;
        }

        long queuedDeadline = Automations.queuedWaitDeadlineNanos(
                automationAction, timeoutS * 1000L);
        boolean queuedWait = queuedDeadline != 0L;
        long deadline = queuedWait ? queuedDeadline : System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutS);
        // Poll the shared state, comparing through the shared condition path so a dynamic
        // RHS is resolved fresh each tick (a variable another action sets during the wait,
        // or a live signal, is honoured).
        while (true) {
            if (AutomationCondition.evaluate(event, comparator, target)) {
                Automations.clearQueuedWait(automationAction);
                logger.info("WaitUntilAction: satisfied (" + event.getType() + " " + comparator + " " + target + ")");
                return;
            }
            if (System.nanoTime() >= deadline) {
                // The condition never became true, so the precondition this wait guards was
                // NOT satisfied - stop the chain instead of running the remaining actions.
                Automations.clearQueuedWait(automationAction);
                logger.info("WaitUntilAction: timed out after " + timeoutS + "s waiting for "
                        + event.getType() + " " + comparator + " " + target
                        + " - stopping the remaining actions");
                Automations.abortChain();
                return;
            }
            long remainingNanos = Math.max(1L, deadline - System.nanoTime());
            long pollMs = Math.max(1L, Math.min(POLL_MS,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            if (queuedWait) {
                if (Automations.deferQueuedAction(automationAction, pollMs, false)) return;
                Automations.clearQueuedWait(automationAction);
                queuedWait = false;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ie) {
                // Worker teardown - re-assert and bail so take() unwinds the thread.
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Seconds to wait. {@code 0} (or a negative, from a hand-edited config) means "as long as
     * allowed" and yields the {@link #MAX_TIMEOUT_S} ceiling rather than an unbounded wait:
     * every automation's actions run on the SINGLE queue worker, so a genuinely infinite wait
     * would park that thread and stall every other automation indefinitely. The ceiling is the
     * honest maximum, and the timeout log says the condition was never met.
     */
    private static int clampTimeout(Integer t) {
        if (t == null) return DEFAULT_TIMEOUT_S;
        if (t <= 0) return MAX_TIMEOUT_S;
        return Math.min(MAX_TIMEOUT_S, t);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
