package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Pins the Toybox top format emitted by BYD head units. */
public class TopSnapshotCollectorTest {

    private static final String BYD_TOP_OUTPUT =
            "Tasks: 234 total,   3 running, 231 sleeping,   0 stopped,   0 zombie\n"
            + "  Mem:      7.4G total,      7.3G used,       55M free,       27M buffers\n"
            + " Swap:      4.0G total,      106M used,      3.8G free,      3.4G cached\n"
            + "800%cpu 136%user  25%nice 239%sys 375%idle   0%iow  18%irq   7%sirq   0%host\n"
            + "  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS\n"
            + "  606 system       18  -2 8.8G 174M 174M S 71.4   2.2  30:16.20 system_server\n"
            + "  408 camera       20   0 293M  47M 7.0M S 57.1   0.6 125:03.19 mm-qcamera-daemon\n"
            + " 3714 system       20   0 5.4G 140M  96M S 35.7   1.8  48:33.62 com.byd.cdr\n"
            + "  381 mediacodec   20   0 266M  38M  33M S 21.4   0.4  26:44.93 media.codec hw/android.hardware.media.omx@1.0-service\n"
            + "27695 shell        20   0 6.5G 169M 169M S 14.2   2.2   4:42.58 byd_cam_daemon\n"
            + "11028 shell        20   0  35M 3.7M 2.8M R  7.1   0.0   0:00.06 top -b -n 1 -m 12\n";

    @Test
    public void parsesBydToyboxSnapshotIntoStructuredMetrics() throws Exception {
        JSONObject snapshot = TopSnapshotCollector.parseSnapshot(
                BYD_TOP_OUTPUT,
                20,
                27695,
                1_765_000_000_000L,
                87L,
                "top -b -n 1 -m 28");

        assertTrue(snapshot.getBoolean("available"));
        assertEquals(234, snapshot.getJSONObject("tasks").getInt("total"));
        assertEquals(3, snapshot.getJSONObject("tasks").getInt("running"));
        assertEquals(231, snapshot.getJSONObject("tasks").getInt("sleeping"));

        JSONObject memory = snapshot.getJSONObject("memory");
        assertEquals(gibibytes(7.4), memory.getLong("totalBytes"));
        assertEquals(gibibytes(7.3), memory.getLong("usedBytes"));
        assertEquals(mebibytes(55), memory.getLong("freeBytes"));

        JSONObject cpu = snapshot.getJSONObject("cpu");
        assertEquals(800.0, cpu.getDouble("capacityPercent"), 0.01);
        assertEquals(425.0, cpu.getDouble("busyPercent"), 0.01);
        assertEquals(53.1, cpu.getDouble("deviceBusyPercent"), 0.01);

        JSONArray processes = snapshot.getJSONArray("processes");
        assertEquals(6, processes.length());
        JSONObject mediaCodec = processes.getJSONObject(3);
        assertEquals(
                "media.codec hw/android.hardware.media.omx@1.0-service",
                mediaCodec.getString("command"));
        assertEquals(mebibytes(38), mediaCodec.getLong("residentBytes"));

        JSONObject daemon = processes.getJSONObject(4);
        assertEquals(27695, daemon.getInt("pid"));
        assertTrue(daemon.getBoolean("isOverdrive"));

        JSONObject sampler = processes.getJSONObject(5);
        assertTrue(sampler.getBoolean("isSampler"));
        assertEquals("top -b -n 1 -m 12", sampler.getString("command"));
    }

    @Test
    public void obeysRequestedProcessRowLimit() throws Exception {
        JSONObject snapshot = TopSnapshotCollector.parseSnapshot(
                BYD_TOP_OUTPUT, 2, -1, 1L, 1L, "fixture");

        assertEquals(2, snapshot.getJSONArray("processes").length());
        assertEquals(2, snapshot.getInt("processCount"));
    }

    @Test
    public void cachedProcessSnapshotExpiresAfter750ElapsedMilliseconds() {
        assertTrue(TopSnapshotCollector.isCacheFresh(10_000L, 10_000L));
        assertTrue(TopSnapshotCollector.isCacheFresh(10_000L, 10_750L));
        assertFalse(TopSnapshotCollector.isCacheFresh(10_000L, 10_751L));
    }

    @Test
    public void elapsedClockRegressionDoesNotMakeSnapshotLookFresh() {
        // Defensive even if the clock source is reset unexpectedly: a negative
        // age must never pin one old process snapshot indefinitely.
        assertFalse(TopSnapshotCollector.isCacheFresh(10_000L, 9_500L));
    }

    private static long mebibytes(double value) {
        return Math.round(value * 1024.0 * 1024.0);
    }

    private static long gibibytes(double value) {
        return Math.round(value * 1024.0 * 1024.0 * 1024.0);
    }
}
