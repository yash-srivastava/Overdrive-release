package com.overdrive.app.services;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClusterTextTargetTokenTest {
    @Test public void oldDialogCannotWriteIntoARecreatedServiceTarget() {
        Object firstService = new Object();
        ClusterTextTargetToken firstField = new ClusterTextTargetToken(firstService);
        Object reboundService = new Object();
        ClusterTextTargetToken newField = new ClusterTextTargetToken(reboundService);

        assertTrue(firstField.matches(firstService, firstField.id));
        assertFalse(newField.matches(reboundService, firstField.id));
        assertFalse(firstField.matches(reboundService, firstField.id));
        assertTrue(newField.matches(reboundService, newField.id));
    }

    @Test public void changingFieldsInvalidatesTheEarlierDialogToken() {
        Object service = new Object();
        ClusterTextTargetToken firstField = new ClusterTextTargetToken(service);
        ClusterTextTargetToken nextField = new ClusterTextTargetToken(service);

        assertFalse(nextField.matches(service, firstField.id));
        assertTrue(nextField.matches(service, nextField.id));
    }
}
