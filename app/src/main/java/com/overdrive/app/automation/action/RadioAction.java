package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.RadioControl;

import java.util.List;
import java.util.Map;

/**
 * Toggle a device radio (WiFi / Bluetooth / mobile-data) on or off from an automation.
 *
 * <p>Deliberately NOT an {@link ApiAction}: radios are toggled with the daemon-side
 * {@code svc} shell command (no BYD SDK entity), and — for WiFi — this must keep the
 * keep-alive suppression flag in sync so an explicit "WiFi off" isn't auto-re-enabled by
 * the watchdog. It therefore calls {@link RadioControl} IN-PROCESS, mirroring
 * {@link ManualClipAction} / {@link AutomationControlAction}, rather than routing through
 * the automation HTTP allowlist.
 */
public class RadioAction extends DeclaredAction {
    private static final String TYPE = "radio";

    public RadioAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new EnumType(new Label("radio", "automation.radio_kind"),
                        new Label("wifi", "automation.radio_wifi"),
                        new Label("bluetooth", "automation.radio_bluetooth"),
                        new Label("data", "automation.radio_data")),
                new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"),
                        new Label("off", "automation.off"))));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        RadioControl.Radio radio = RadioControl.parse(str(vars.get("radio")));
        String state = str(vars.get("state"));
        if (radio == null) {
            logger.warn("RadioAction: unknown radio '" + str(vars.get("radio")) + "', skipping");
            return;
        }
        boolean enable = "on".equals(state);
        boolean ok = RadioControl.set(radio, enable);
        logger.info("RadioAction: " + radio + " " + state + " ok=" + ok);
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }
}
