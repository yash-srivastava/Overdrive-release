package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.TextInterpolator;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.daemon.CameraDaemon;

import java.util.List;
import java.util.Map;

/**
 * Publish an MQTT message from an automation — the outbound sink that lets a rule notify
 * Home Assistant (or any broker consumer), e.g. "when a person is detected while parked →
 * publish overdrive/alert = person". Fans the message out to every active MQTT connection
 * via {@link com.overdrive.app.mqtt.MqttConnectionManager#publishToAll}.
 *
 * <p>Deliberately NOT an {@link ApiAction}: {@code /api/mqtt} is intentionally OFF the
 * automation HTTP allowlist ({@code HttpServer.AUTOMATION_ALLOWED_PREFIXES}) — a hard
 * security boundary. This calls the connection manager IN-PROCESS instead, mirroring
 * {@link ManualClipAction} / {@link AutomationControlAction} / {@link RadioAction}.
 *
 * <p>Topic and payload both support {@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}} /
 * bare {@code ${NAME}} interpolation against the shared automation state (see
 * {@link com.overdrive.app.automation.TextInterpolator}, the same convention as
 * {@link ApiAction} bodies), so a rule can publish a live signal or a counter another
 * action set. A relative topic is scoped under each connection's
 * base topic; an absolute topic ("/…") is used as-is. No live MQTT connection → clean
 * no-op (logged), never throws.
 */
public class MqttPublishAction extends DeclaredAction {
    private static final String TYPE = "mqttPublish";

    public MqttPublishAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new StringType(new Label("topic", "automation.mqtt_topic"), 128),
                new StringType(new Label("payload", "automation.mqtt_payload"), 256),
                new EnumType(new Label("retain", "automation.mqtt_retain"),
                        new Label("false", "automation.off"),
                        new Label("true", "automation.on"))));
    }

    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String topic = TextInterpolator.interpolate(str(vars.get("topic")));
        String payload = TextInterpolator.interpolate(str(vars.get("payload")));
        boolean retain = "true".equals(str(vars.get("retain")));
        if (topic == null || topic.isEmpty()) {
            logger.warn("MqttPublishAction: empty topic, skipping");
            return;
        }
        try {
            com.overdrive.app.mqtt.MqttConnectionManager mgr = CameraDaemon.getMqttConnectionManager();
            if (mgr == null) {
                logger.warn("MqttPublishAction: MQTT manager unavailable — skipping publish to " + topic);
                return;
            }
            int published = mgr.publishToAll(topic, payload == null ? "" : payload, retain);
            logger.info("MqttPublishAction: '" + topic + "' -> " + published + " connection(s)");
        } catch (Throwable t) {
            logger.warn("MqttPublishAction failed: " + t.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString().trim(); }
}
