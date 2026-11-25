package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.Action.NONE;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.Action.START;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.Action.STOP;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.FIRST_RETRY_MS;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.MAX_RETRY_MS;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.STABLE_MS;
import static com.overdrive.app.remoteaccess.ProximityCollectorKeepAlivePolicy.STALE_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Resource-free checks; no shell, service or vehicle is touched. */
public class ProximityCollectorKeepAlivePolicyTest {

    private static ProximityCollectorKeepAlivePolicy parked(long nowMs) {
        ProximityCollectorKeepAlivePolicy p = new ProximityCollectorKeepAlivePolicy();
        assertEquals(START, p.decide(true, true, nowMs, -1));
        return p;
    }

    @Test public void firstNotWantedTickAfterADaemonStartStopsOnceForAnOrphanedService() {
        // A previous daemon may have left the service running before the car was switched on.
        ProximityCollectorKeepAlivePolicy p = new ProximityCollectorKeepAlivePolicy();
        assertEquals(STOP, p.decide(false, true, 1_000, -1));
        assertEquals(NONE, p.decide(false, true, 6_000, -1));
        assertEquals(NONE, p.decide(false, false, 600_000, 50_000));
    }

    @Test public void switchOffStartsOnceEvenWhileReadingsAreFresh() {
        ProximityCollectorKeepAlivePolicy p = new ProximityCollectorKeepAlivePolicy();
        assertEquals(START, p.decide(true, true, 10_000, 9_000));
        assertEquals("parked", p.reason());
        assertEquals(NONE, p.decide(true, true, 15_000, 14_000));
    }

    @Test public void freshReadingsNeverRestart() {
        ProximityCollectorKeepAlivePolicy p = parked(0);
        for (long t = 5_000; t <= 3_600_000; t += 5_000)
            assertEquals(NONE, p.decide(true, true, t, t - 3_000));
    }

    @Test public void killedProcessIsRestartedAfterTheFirstRetryWindow() {
        // log_3NH3HQ8U: last reading at switch-off, then silence until the car locked itself.
        ProximityCollectorKeepAlivePolicy p = new ProximityCollectorKeepAlivePolicy();
        long off = 100_000;
        assertEquals(START, p.decide(true, true, off, off));
        assertEquals(NONE, p.decide(true, true, off + 15_000, off));
        assertEquals(START, p.decide(true, true, off + FIRST_RETRY_MS, off));
        assertEquals("collector_silent", p.reason());
    }

    @Test public void readingJustInsideStaleWindowCountsAsAlive() {
        ProximityCollectorKeepAlivePolicy p = parked(0);
        long now = 60_000;
        assertEquals(NONE, p.decide(true, true, now, now - STALE_MS));
        assertEquals(START, p.decide(true, true, now + 1, now - STALE_MS));
    }

    @Test public void repeatedFailureBacksOffToTheCap() {
        ProximityCollectorKeepAlivePolicy p = parked(0);
        long t = 0;
        long wait = FIRST_RETRY_MS;
        for (int i = 0; i < 12; i++) {
            assertEquals(NONE, p.decide(true, true, t + wait - 1, -1));
            t += wait;
            assertEquals(START, p.decide(true, true, t, -1));
            wait = Math.min(wait * 2, MAX_RETRY_MS);
        }
        assertEquals(MAX_RETRY_MS, p.retryMs());
    }

    @Test public void aRevivalThatIsKilledAgainQuicklyKeepsBackingOff() {
        // The head unit kills each revival shortly after its first relay.
        ProximityCollectorKeepAlivePolicy p = parked(0);
        long t = 0;
        long wait = FIRST_RETRY_MS;
        for (int i = 0; i < 6; i++) {
            long started = t + wait;
            assertEquals(START, p.decide(true, true, started, -1));
            // One fresh relay a few seconds after the restart, then silence again.
            assertEquals(NONE, p.decide(true, true, started + 5_000, started + 4_000));
            wait = Math.min(wait * 2, MAX_RETRY_MS);
            assertEquals(wait, p.retryMs());
            t = started;
        }
    }

    @Test public void aRevivalThatStaysAliveResetsTheBackoff() {
        ProximityCollectorKeepAlivePolicy p = parked(0);
        assertEquals(START, p.decide(true, true, FIRST_RETRY_MS, -1));
        long restarted = FIRST_RETRY_MS * 3;
        assertEquals(START, p.decide(true, true, restarted, -1));
        assertTrue(p.retryMs() > FIRST_RETRY_MS);
        long stable = restarted + STABLE_MS;
        assertEquals(NONE, p.decide(true, true, stable, stable - 1_000));
        assertEquals(FIRST_RETRY_MS, p.retryMs());
        // It dies long after its last start: restart as soon as the silence is confirmed.
        assertEquals(NONE, p.decide(true, true, stable + STALE_MS - 1_000, stable - 1_000));
        assertEquals(START, p.decide(true, true, stable + STALE_MS, stable - 1_000));
        assertEquals(FIRST_RETRY_MS * 2, p.retryMs());
    }

    @Test public void keyFobOnlyReassertsPeriodicallyWithoutAHeartbeat() {
        ProximityCollectorKeepAlivePolicy p = new ProximityCollectorKeepAlivePolicy();
        assertEquals(START, p.decide(true, false, 0, -1));
        assertEquals(NONE, p.decide(true, false, MAX_RETRY_MS - 1, -1));
        assertEquals(START, p.decide(true, false, MAX_RETRY_MS, -1));
        assertEquals("reassert", p.reason());
        assertEquals(NONE, p.decide(true, false, MAX_RETRY_MS + 5_000, -1));
    }

    @Test public void powerOnStopsOnceAndTheNextParkStartsAgain() {
        ProximityCollectorKeepAlivePolicy p = parked(0);
        assertEquals(STOP, p.decide(false, true, 30_000, 29_000));
        assertEquals(NONE, p.decide(false, true, 35_000, 34_000));
        assertEquals(START, p.decide(true, true, 900_000, 899_000));
        assertEquals("parked", p.reason());
    }

    @Test public void clockGoingBackwardsDoesNotWedgeRestarts() {
        ProximityCollectorKeepAlivePolicy p = parked(500_000);
        assertEquals(START, p.decide(true, true, 100_000, -1));
        ProximityCollectorKeepAlivePolicy fob = new ProximityCollectorKeepAlivePolicy();
        fob.decide(true, false, 500_000, -1);
        assertEquals(START, fob.decide(true, false, 100_000, -1));
    }

    @Test public void phoneCollectorConfiguredMirrorsTheAppStartConditions() throws Exception {
        JSONObject phone = new JSONObject().put("address", "AA:BB:CC:DD:EE:FF").put("enabled", true);
        JSONObject on = new JSONObject().put("enabled", true).put("keepBluetoothOn", true)
                .put("phones", new JSONArray().put(phone));
        assertTrue(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(on));
        assertFalse(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(
                new JSONObject(on.toString()).put("enabled", false)));
        assertFalse(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(
                new JSONObject(on.toString()).put("keepBluetoothOn", false)));
        assertFalse(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(
                new JSONObject(on.toString()).put("phones", new JSONArray().put(
                        new JSONObject(phone.toString()).put("enabled", false)))));
        assertFalse(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(
                new JSONObject(on.toString()).put("phones", new JSONArray())));
        assertFalse(ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(null));
    }

    @Test public void keyFobConfiguredMirrorsTheFobConsent() throws Exception {
        assertTrue(ProximityCollectorKeepAlivePolicy.keyFobConfigured(
                new JSONObject().put("enabled", true).put("keyFobEnabled", true)));
        assertFalse(ProximityCollectorKeepAlivePolicy.keyFobConfigured(
                new JSONObject().put("enabled", false).put("keyFobEnabled", true)));
        assertFalse(ProximityCollectorKeepAlivePolicy.keyFobConfigured(
                new JSONObject().put("enabled", true)));
        assertFalse(ProximityCollectorKeepAlivePolicy.keyFobConfigured(null));
    }
}
