package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;

/**
 * A trigger/condition on an INBOUND MQTT channel (see
 * {@link com.overdrive.app.automation.Automations#publishMqttTrigger}).
 *
 * <p>Mirrors {@link VariableCondition}: the differentiating sub-field is a FREE-TEXT
 * {@code channel} the user types (e.g. "front_door", "ha_scene"), not a fixed enum — so
 * this subclass uses the shared free-text event parser and schema to carry a
 * {@code channel} string field. The state key it produces
 * ({@code EventData("mqttTrigger",{channel})}) is exactly what the MQTT subscriber writes
 * via {@code publishMqttTrigger}, so an inbound message on {@code <base>/automation/<channel>}
 * lines up with a rule watching that channel. Value comparison is string eq/neq, so
 * "front_door == open" works as written.
 */
public class MqttTriggerCondition extends TextKeyCondition {
    // Length-bound only so older configs still load; the charset is enforced on save
    // (Automations.hasInvalidMqttChannel).
    private static final StringType CHANNEL_TYPE =
            new StringType(new Label("channel", "automation.mqtt_channel"), 64);

    public MqttTriggerCondition(Label label, String description) {
        super(label, description, new StringType(new Label("value", "automation.mqtt_value"),
                256), CHANNEL_TYPE);
    }

    @Override
    protected EventData eventForKey(String key) {
        return BydEvent.mqttTrigger(key);
    }
}
