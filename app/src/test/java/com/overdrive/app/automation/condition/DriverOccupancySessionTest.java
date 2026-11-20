package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DriverOccupancySessionTest {
    private static final DriverOccupancySession.Sample IGNORE =
            DriverOccupancySession.Sample.IGNORE;
    private static final DriverOccupancySession.Sample INVALIDATE =
            DriverOccupancySession.Sample.INVALIDATE;
    private static final DriverOccupancySession.Sample OCCUPIED =
            DriverOccupancySession.Sample.OCCUPIED;

    @Test
    public void positiveEvidencePublishesWithoutDoorHistory() {
        DriverOccupancySession session = new DriverOccupancySession();
        assertEquals(OCCUPIED, session.observePresence(1));
        assertEquals(IGNORE, session.observePresence(0)); // unbuckled is NOT empty
    }

    @Test
    public void aFirstDoorSampleDoesNotInventAnExit() {
        DriverOccupancySession session = new DriverOccupancySession();
        session.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(IGNORE, session.observePresence(0));
        session.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(OCCUPIED, session.observePresence(1));
    }

    @Test
    public void openingADoorWithBeltStillBuckledCannotRearmPresence() {
        DriverOccupancySession session = new DriverOccupancySession();
        session.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(OCCUPIED, session.observePresence(1));
        session.observeDoor(DriverOccupancySession.OPEN);
        assertFalse(session.canPublishPositive());
        assertEquals(IGNORE, session.observePresence(1));
        session.observeDoor(DriverOccupancySession.CLOSED);
        assertTrue(session.canPublishPositive());
        assertEquals(OCCUPIED, session.observePresence(1)); // same value still dedups
    }

    @Test
    public void lossOfPositiveEvidenceWhileDoorOpenInvalidatesExactlyOnce() {
        DriverOccupancySession session = new DriverOccupancySession();
        session.observeDoor(DriverOccupancySession.CLOSED);
        session.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(INVALIDATE, session.observePresence(Integer.MIN_VALUE));
        assertEquals(IGNORE, session.observePresence(0));
        session.observeDoor(DriverOccupancySession.CLOSED);
        assertEquals(IGNORE, session.observePresence(Integer.MIN_VALUE));
        assertEquals(OCCUPIED, session.observePresence(1));
    }

    @Test
    public void invalidDoorReadingsAndRepeatedOpensDoNotCauseResets() {
        DriverOccupancySession session = new DriverOccupancySession();
        session.observeDoor(DriverOccupancySession.CLOSED);
        session.observeDoor(255);
        assertEquals(OCCUPIED, session.observePresence(1));
        session.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(INVALIDATE, session.observePresence(0));
        session.observeDoor(DriverOccupancySession.OPEN);
        assertEquals(IGNORE, session.observePresence(0));
    }
}
