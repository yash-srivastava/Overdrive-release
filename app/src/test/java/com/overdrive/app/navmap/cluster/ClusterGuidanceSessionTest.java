package com.overdrive.app.navmap.cluster;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterGuidanceSessionTest {
    @Test public void notificationClearCannotStopRoadSense() {
        ClusterGuidanceSession session = new ClusterGuidanceSession();
        assertTrue(session.submit("maps", 10, 100));
        assertTrue(session.submit("roadsense", 11, 110));
        assertFalse(session.clear("maps", 12));
        assertEquals("roadsense", session.source());
        assertFalse(session.submit("waze", 13, 120));
    }
    @Test public void lateFrameCannotResurrectClearedNavigation() {
        ClusterGuidanceSession session = new ClusterGuidanceSession();
        assertTrue(session.submit("roadsense", 10, 100));
        assertTrue(session.clear("roadsense", 12));
        assertFalse(session.submit("roadsense", 11, 150));
        assertEquals("", session.source());
        assertTrue(session.submit("roadsense", 13, 180));
    }
    @Test public void unchangedGuidanceRefreshExtendsLivenessAndStaleStateExpires() {
        ClusterGuidanceSession session = new ClusterGuidanceSession();
        assertTrue(session.submit("roadsense", 10, 100));
        assertFalse(session.expired(30_100));
        assertTrue(session.submit("roadsense", 11, 30_100));
        assertFalse(session.expired(31_000));
        assertTrue(session.expired(60_101));
        session.reset();
        assertTrue(session.submit("maps", 100, 70_000));
        assertFalse(session.expired(250_000));
        assertTrue(session.expired(250_001));
    }
}
