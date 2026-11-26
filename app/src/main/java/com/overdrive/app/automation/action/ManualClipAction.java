package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.recording.ManualClipService;
import com.overdrive.app.recording.ManualClipWindow;

import org.json.JSONObject;

import java.util.List;

/**
 * Automation action that saves a manual instant replay from the encoded camera
 * ring — the automation counterpart of the Key Mapping "manualClip" action.
 *
 * <p>Deliberately NOT an {@link ApiAction}: instant replay is a manual-only
 * recording path that must never be reachable through the automation HTTP
 * allowlist ({@code automationApiRequest}). This action calls
 * {@link ManualClipService} in-process instead, mirroring how
 * {@link NotificationAction} and {@link ShellAction} invoke a service directly.
 *
 * <p>Like the key-mapping path the request is fire-and-forget: it only snapshots
 * the trigger PTS and queues export work. If the camera pipeline is not running
 * (no ACC-on recording mode active), the request is refused and logged — an
 * automation binding never keeps the pipeline alive on its own.
 */
public class ManualClipAction extends DeclaredAction {
    private static final String TYPE = "manualClip";

    public ManualClipAction(Label label, String description) {
        // Two whole-second dimensions. IntType bounds each 0..60; the combined
        // 1..60 total is enforced in fromJson via ManualClipWindow.create so a
        // saved automation can never carry an out-of-contract window.
        super(TYPE, label, description, List.of(
                new IntType(new Label("beforeSeconds", "automation.clip_before"),
                        0, ManualClipWindow.MAX_SECONDS),
                new IntType(new Label("afterSeconds", "automation.clip_after"),
                        0, ManualClipWindow.MAX_SECONDS)));
    }

    /**
     * Reject a saved binding whose two values don't form a valid 1..60s window.
     * {@link BaseAction#fromJson} already range-checks each IntType; this adds
     * the combined-total rule that a single IntType cannot express.
     */
    @Override
    public AutomationAction fromJson(JSONObject input) {
        AutomationAction action = super.fromJson(input);
        if (action == null) return null;
        try {
            ManualClipWindow.create(
                    intVar(action, "beforeSeconds"), intVar(action, "afterSeconds"));
        } catch (RuntimeException invalid) {
            return null;
        }
        return action;
    }

    private static int intVar(AutomationAction action, String key) {
        Object value = action.getVariables().get(key);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /**
     * Request a manual instant replay. Non-blocking: the service queues the
     * export and returns immediately. A refusal (pipeline not running, buffer
     * too small, protected recording mode) is logged, not surfaced — an
     * automation has no user waiting on a synchronous result.
     */
    public void trigger(AutomationAction automationAction) {
        int before = intVar(automationAction, "beforeSeconds");
        int after = intVar(automationAction, "afterSeconds");
        ManualClipService.RequestResult result =
                ManualClipService.getInstance().requestClip(before, after);
        if (result.isAccepted()) {
            logger.info("Automation manualClip before=" + before + "s after=" + after
                    + "s -> " + result.status);
        } else {
            logger.warn("Automation manualClip before=" + before + "s after=" + after
                    + "s refused: " + result.status + " (" + result.message + ")");
        }
    }
}
