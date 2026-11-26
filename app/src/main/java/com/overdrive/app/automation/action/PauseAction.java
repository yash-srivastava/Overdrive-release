package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.value.Label;

import java.util.List;

/**
 * Automation action that pauses for a fixed number of milliseconds before the next
 * action in the list runs.
 *
 * <p>Actions run sequentially on the single {@link com.overdrive.app.automation.AutomationQueue}
 * worker thread, so a bounded {@code Thread.sleep} here delays only the rest of THIS
 * automation's action chain — it never touches the telemetry thread, the HTTP
 * threads, or any other automation's timing (a second triggered automation just
 * waits its turn in the queue, exactly as it already does for a slow action).
 *
 * <p>The pause is bounded to {@link #MAX_MS} so a hand-edited config can't park the
 * worker indefinitely, and the sleep re-asserts the interrupt on teardown (mirrors
 * {@link ShellAction}) so a disable-all can still kill the worker mid-pause.
 */
public class PauseAction extends DeclaredAction {
    private static final String TYPE = "pause";
    // Ceiling for a single pause. 5 minutes is generous for "wait a beat between two
    // vehicle commands" while bounding a pathological value; the worker is a daemon
    // thread and a longer wall-clock wait belongs in the whole-automation delay.
    private static final int MAX_MS = 300_000;

    public PauseAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new IntType(new Label("milliseconds", "automation.pause_ms"), 0, MAX_MS)));
    }

    /**
     * Sleep for the bound number of milliseconds (clamped to [0, MAX_MS]). Runs on
     * the AutomationQueue worker; an interrupt (worker teardown) is re-asserted so the
     * worker's blocking {@code take()} throws and the thread exits as designed.
     */
    public void trigger(AutomationAction automationAction) {
        Object v = automationAction.getVariables().get("milliseconds");
        int ms;
        try {
            ms = v == null ? 0 : ((Number) v).intValue();
        } catch (ClassCastException e) {
            // Value came through as a String (hand-edited config) — parse defensively.
            try { ms = Integer.parseInt(v.toString().trim()); } catch (Exception ex) { ms = 0; }
        }
        ms = Math.max(0, Math.min(MAX_MS, ms));
        if (ms == 0) return;
        if (Automations.deferQueuedAction(automationAction, ms, true)) {
            logger.info("PauseAction: deferred " + ms + "ms");
            return;
        }
        try {
            Thread.sleep(ms);
            logger.info("PauseAction: waited " + ms + "ms");
        } catch (InterruptedException ie) {
            // Worker is being torn down — re-assert so take() unwinds the thread.
            Thread.currentThread().interrupt();
        }
    }
}
