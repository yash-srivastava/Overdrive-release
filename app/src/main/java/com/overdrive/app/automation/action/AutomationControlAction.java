package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.AutomationType;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.value.Label;

import java.util.List;
import java.util.Map;

/**
 * Enable, disable, or toggle ANOTHER automation — so one automation can arm/disarm
 * others (e.g. "when I leave home, enable the Sentry routines").
 *
 * <p>Deliberately NOT an {@link ApiAction}: it must not be reachable through the
 * automation HTTP allowlist (which does not include the /api/automations management
 * surface, on purpose). Instead it calls {@link Automations#disableAutomation} /
 * {@link Automations#toggleAutomation} IN-PROCESS, mirroring {@link ManualClipAction}
 * and {@link NotificationAction}.
 *
 * <p>The target is picked from a live dropdown ({@link AutomationType} →
 * {@code GET /api/automations/picker}); the value is the target's id. An unknown/
 * deleted target is a logged no-op. Self-targeting is allowed at the engine level but
 * the picker excludes self.
 */
public class AutomationControlAction extends DeclaredAction {
    private static final String TYPE = "automationControl";

    public AutomationControlAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new AutomationType(new Label("target", "automation.control_target")),
                new EnumType(new Label("action", "automation.action"),
                        new Label("enable", "automation.control_enable"),
                        new Label("disable", "automation.control_disable"),
                        new Label("toggle", "automation.toggle"))));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String target = str(vars.get("target"));
        String action = str(vars.get("action"));
        if (target == null || target.isEmpty()) {
            logger.warn("AutomationControlAction: no target, skipping");
            return;
        }
        if (!Automations.automationExists(target)) {
            logger.warn("AutomationControlAction: unknown target " + target + ", skipping");
            return;
        }
        boolean ok;
        if ("toggle".equals(action)) {
            ok = Automations.toggleAutomation(target);
        } else if ("disable".equals(action)) {
            ok = Automations.disableAutomation(target, true);
        } else { // default / "enable"
            ok = Automations.disableAutomation(target, false);
        }
        logger.info("AutomationControlAction: " + action + " " + target + " ok=" + ok);
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }
}
