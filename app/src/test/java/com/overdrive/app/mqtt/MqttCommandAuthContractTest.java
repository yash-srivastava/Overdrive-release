package com.overdrive.app.mqtt;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** V60-08 contract: unsigned MQTT control commands must be rejected. */
public class MqttCommandAuthContractTest {
    @Test
    public void emptySigNeverVerifies() {
        assertFalse(MqttCommandAuth.verify("base/tailgate/set", "OPEN", ""));
        assertFalse(MqttCommandAuth.verify("base/tailgate/set", "OPEN", null));
    }
}
