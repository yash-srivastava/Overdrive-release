package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Battery-aware sampling never slows the approach/transition band. */
public class BluetoothProximitySamplingPolicyTest {
    private static final int AWAY = -24;

    @Test
    public void keepsFastCadenceUntilTwoClearlyAwaySamplesArrive() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();

        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-33, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));
    }

    @Test
    public void nearAndTransitionReadingsImmediatelyRestoreFastCadence() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();
        policy.nextIntervalMs(-33, AWAY);
        assertEquals(BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));

        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-29, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-12, AWAY));
    }

    @Test
    public void oneWeakOutlierDoesNotPutTheWorkerIntoLowActivityMode() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-33, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-20, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));
    }

    @Test
    public void missingRssiUsesBoundedRetryAndResetsFarConfirmation() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();
        policy.nextIntervalMs(-33, AWAY);
        assertEquals(BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS,
                policy.nextIntervalMs(null, AWAY));
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));
    }

    @Test
    public void farCadenceFitsDefaultDepartureWindowAndStalenessLease() throws Exception {
        long interval = BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS;
        org.json.JSONObject defaults = RemoteAccessSettings.defaults();
        // Four seconds is the minimum allowed window and stale lease in Advanced mode.
        assertTrue(interval < 4000L);
        assertTrue(interval < defaults.getLong("windowMs"));
        assertTrue(interval < defaults.getLong("staleMs"));
    }

    @Test
    public void explicitResetRestoresTheFastFirstSample() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();
        policy.nextIntervalMs(-33, AWAY);
        policy.nextIntervalMs(-34, AWAY);
        policy.reset();

        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-34, AWAY));
    }

    @Test
    public void customAwayThresholdIsRespected() {
        BluetoothProximitySamplingPolicy policy = new BluetoothProximitySamplingPolicy();
        assertEquals(BluetoothProximitySamplingPolicy.FAST_INTERVAL_MS,
                policy.nextIntervalMs(-60, -50));
        assertEquals(BluetoothProximitySamplingPolicy.LOW_ACTIVITY_INTERVAL_MS,
                policy.nextIntervalMs(-60, -50));
    }
}
