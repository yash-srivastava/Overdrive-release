package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.TextInterpolator;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.server.Messages;

import java.util.List;

public class NotificationAction extends DeclaredAction {
    private static final String TYPE = "notification";

    /**
     * An action to send a notification to the user
     * A variable for the message is automatically added in
     *
     * @param label       The label for this notification with an id and display name
     * @param description The description for this action
     */
    public NotificationAction(Label label, String description) {
        // Create a message variable with the max length required for push notifications
        super(TYPE, label, description, List.of(new StringType(new Label("message", "Message"), 150)));
    }

    /**
     * Send a push notification to the user.
     *
     * <p>The message supports {@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}} /
     * bare {@code ${NAME}} references, resolved against live automation state, so a
     * notification can report a value ("Charged to ${signal:batteryLevel}%") instead of
     * only fixed text — the same interpolation the toast / dialog / speak actions get.
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    public void trigger(AutomationAction automationAction) {
        Object raw = automationAction.getVariables().get("message");
        String message = TextInterpolator.interpolate(raw == null ? "" : raw.toString());
        NotificationEvent.Severity severity = NotificationEvent.Severity.INFO;
        NotificationEvent event = new NotificationEvent(
                "automation.action",
                severity,
                Messages.get("notifications.automation_triggered_title"),
                message,
                "automation-" + System.currentTimeMillis(),
                null,
                null);
        NotificationBus.get().publish(event);
    }
}
