package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LateResourceHandoffTest {

    @Test
    public void publishedResourceTransfersOnceToTheCaller() {
        LateResourceHandoff<Object> handoff = new LateResourceHandoff<>();
        Object resource = new Object();

        assertTrue(handoff.publish(resource));
        assertSame(resource, handoff.take());
        assertNull(handoff.take());
        assertFalse(handoff.publish(new Object()));
    }

    @Test
    public void timeoutReclaimsAnAlreadyPublishedResource() {
        LateResourceHandoff<Object> handoff = new LateResourceHandoff<>();
        Object resource = new Object();

        assertTrue(handoff.publish(resource));
        assertSame(resource, handoff.abandon());
        assertNull(handoff.take());
        assertFalse(handoff.publish(new Object()));
    }

    @Test
    public void workerCannotPublishAResourceAfterTimeout() {
        LateResourceHandoff<Object> handoff = new LateResourceHandoff<>();
        Object lateResource = new Object();

        assertNull(handoff.abandon());
        assertFalse(handoff.publish(lateResource));
        assertNull(handoff.take());
    }
}
