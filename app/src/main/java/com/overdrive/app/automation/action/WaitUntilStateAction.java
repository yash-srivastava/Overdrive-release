package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.SignalAddressType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Automation action that blocks the action chain until a chosen ON/OFF vehicle signal
 * reaches a target state, or a timeout elapses. The on/off counterpart to
 * {@link WaitUntilAction} (which handles numeric signals): together they let a chain
 * pause on either "speed &lt; 5" or "left indicator = off".
 *
 * <p>Example the user asked for: "when I signal to change lane → wait until indicators
 * = off → log/notify". Scope is the on/off (and combined-indicator) signals for which
 * "wait until &lt;x&gt; is on/off" reads naturally; multi-value enums (gear P/R/N/D)
 * are intentionally left to trigger+condition, matching {@link WaitUntilAction}'s
 * scalar-only rationale, so the form stays a flat, self-explanatory row.
 *
 * <p>Like {@link WaitUntilAction} and {@link PauseAction} the wait runs on the single
 * {@link com.overdrive.app.automation.AutomationQueue} worker thread, so it delays only
 * THIS automation's remaining actions — never telemetry, HTTP, or other automations —
 * and is bounded by a timeout so a never-satisfied state can't park the worker forever.
 * It polls the SAME live automation state the triggers/conditions evaluate against.
 */
public class WaitUntilStateAction extends DeclaredAction {
    private static final String TYPE = "waitUntilState";

    private static final long POLL_MS = 250L;
    private static final int MAX_TIMEOUT_S = 600; // 10 min hard ceiling
    private static final int DEFAULT_TIMEOUT_S = 30;

    // Combined-indicator sentinel: satisfied when BOTH turn signals match "off", or
    // EITHER matches "on" (i.e. "indicators are off" vs "an indicator is on").
    private static final String EVENT_TURN_ANY = "turnAny";

    /** Bound for the awaited state word (longest catalog vocabulary word is well under this). */
    private static final int MAX_STATE_LEN = 32;

    public WaitUntilStateAction(Label label, String description) {
        // event: which on/off signal to watch. ids MUST match the resolveEvent switch.
        // state: the target on/off value. timeout: bounded wait ceiling.
        super(TYPE, label, description, List.of(
                // LHS = an ADDRESS into the shared condition catalog, so this can wait on ANY
                // condition (a specific door, seat, gear, window…), not just the 12 on/off
                // signals it used to hardcode. Legacy stored ids — turnLeft, lowBeam, … — still
                // resolve (see AutomationCondition.resolveSignalAddress), and the combined
                // turnAny sentinel is still handled locally in stateMet.
                new SignalAddressType(new Label("event", "automation.wait_signal")),
                // The awaited state is FREE-FORM, not a hard on/off pair: the catalog's signals
                // publish their own vocabularies (open/closed, occupied/empty, P/R/N/D, on/off),
                // and stateMet compares with "eq" against whatever is stored — so a bounded
                // string covers all of them, where the old two-option enum could only ever
                // match the on/off ones. Existing automations store "on"/"off", which remain
                // valid values of this type.
                new StringType(new Label("state", "automation.state"), MAX_STATE_LEN),
                // 0 = wait the full ceiling (see clampTimeout), matching WaitUntilAction.
                new IntType(new Label("timeout", "automation.wait_timeout"), 0, MAX_TIMEOUT_S)));
    }

    /**
     * Resolve a stored LHS address to its state key. Delegates to the shared grammar
     * ({@link AutomationCondition#resolveSignalAddress}) so the whole condition catalog is
     * reachable and the pre-catalog ids saved automations hold keep resolving unchanged.
     * Returns null for the combined-indicator sentinel, which {@link #stateMet} handles.
     */
    private static EventData resolveEvent(String id) {
        if (id == null || EVENT_TURN_ANY.equals(id.trim())) return null;
        return AutomationCondition.resolveSignalAddress(id);
    }

    /**
     * Block until the chosen signal reaches {@code state} (on/off) or the timeout
     * elapses. Polls the shared automation state every {@link #POLL_MS}. Interrupt
     * (worker teardown) is re-asserted so the worker unwinds cleanly.
     */
    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String eventId = str(vars.get("event"));
        String target = str(vars.get("state"));
        int timeoutS = clampTimeout(toInt(vars.get("timeout")));

        if (eventId == null || target == null) {
            logger.warn("WaitUntilStateAction: missing/invalid parameters, skipping");
            return;
        }

        long queuedDeadline = Automations.queuedWaitDeadlineNanos(
                automationAction, timeoutS * 1000L);
        boolean queuedWait = queuedDeadline != 0L;
        long deadline = queuedWait ? queuedDeadline : System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutS);
        while (true) {
            if (stateMet(eventId, target)) {
                Automations.clearQueuedWait(automationAction);
                logger.info("WaitUntilStateAction: satisfied (" + eventId + " = " + target + ")");
                return;
            }
            if (System.nanoTime() >= deadline) {
                // An unmet wait is a failed precondition, so skip the remaining actions.
                Automations.clearQueuedWait(automationAction);
                logger.info("WaitUntilStateAction: timed out after " + timeoutS + "s waiting for "
                        + eventId + " = " + target + " - stopping the remaining actions");
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
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Whether the live state of {@code eventId} equals {@code target} ("on"/"off"). The
     * combined-indicator sentinel is satisfied for "off" only when BOTH turn signals
     * read off, and for "on" when EITHER reads on. A never-fired signal (null state) is
     * treated as "not yet met" so the wait keeps polling until it appears or times out.
     */
    private static boolean stateMet(String eventId, String target) {
        if (EVENT_TURN_ANY.equals(eventId)) {
            Value left = Automations.getStateValue(BydEvent.TURN_LEFT);
            Value right = Automations.getStateValue(BydEvent.TURN_RIGHT);
            // NEITHER side observed yet → not met, for BOTH targets. Treating an unseeded signal as
            // "not on" made a "wait until indicators = off" satisfy on the FIRST poll while the lamp
            // was still blinking, because both keys read null. That is the unsafe direction: it
            // reports a state that was never measured. Waiting instead lets the poller seed the keys
            // (isEventReferenced now sees turnAny, see resolveEvent's note) or times out honestly.
            if (left == null && right == null) return false;
            boolean anyOn = isOn(left) || isOn(right);
            return "on".equalsIgnoreCase(target) ? anyOn : !anyOn;
        }
        EventData event = resolveEvent(eventId);
        if (event == null) return false;
        // Compare through the shared condition engine rather than current.compare(target,"eq")
        // directly. The awaited state is a STRING, but this action's LHS is the whole condition
        // catalog (SignalAddressType), so it can name a NUMERIC signal (speed, battery, window
        // percent). Handing a String to an IntValue throws ClassCastException inside
        // BaseValue.compare, which swallows it and returns null → "not met" on every poll → the
        // wait could NEVER be satisfied and always burned its full timeout (up to MAX_TIMEOUT_S
        // with the worker parked). AutomationCondition.evaluate applies coerceConstantForLhs, so
        // "0" against a numeric signal now compares numerically. A string/enum LHS keeps the
        // identical equalsIgnoreCase eq path, so on/off waits behave exactly as before.
        return AutomationCondition.evaluate(event, "eq", target);
    }

    /** True iff this state value reads "on". Unknown (null) → false. */
    private static boolean isOn(Value v) {
        return v != null && Boolean.TRUE.equals(v.compare("on", "eq"));
    }

    /** As {@link WaitUntilAction#clampTimeout}: 0 (or negative) = the {@link #MAX_TIMEOUT_S}
     *  ceiling, never an unbounded wait — the single queue worker runs every automation. */
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
