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
    public void timedOutCommandDoesNotPublishPartialRowsOrRetry() throws Exception {
        TopSnapshotCollector.CommandResult result =
                new TopSnapshotCollector.CommandResult(BYD_TOP_OUTPUT, -1, true);
        JSONObject snapshot = TopSnapshotCollector.parseCommandResult(
                result, -1, 123L, 3000L, "fixture");

        assertFalse(snapshot.getBoolean("available"));
        assertEquals(0, snapshot.getJSONArray("processes").length());
        assertEquals(0, snapshot.getInt("processCount"));
        assertEquals("top timed out", snapshot.getString("error"));
        assertEquals(123L, snapshot.getLong("timestamp"));
        assertEquals(3000L, snapshot.getLong("durationMs"));
        assertFalse(result.shouldRetryWithoutLimit(true));
        assertFalse(new TopSnapshotCollector.CommandResult("", -1, true)
                .shouldRetryWithoutLimit(true));
    }

    @Test
    public void nonzeroExitCannotPublishPartialRowsButAllowsPortableFallback() throws Exception {
        TopSnapshotCollector.CommandResult result =
                new TopSnapshotCollector.CommandResult(BYD_TOP_OUTPUT, 1, false);
        JSONObject snapshot = TopSnapshotCollector.parseCommandResult(
                result, -1, 1L, 1L, "fixture");

        assertFalse(snapshot.getBoolean("available"));
        assertEquals(0, snapshot.getInt("processCount"));
        assertEquals("top exited with code 1", snapshot.getString("error"));
        assertTrue(result.shouldRetryWithoutLimit(true));
        assertFalse(result.shouldRetryWithoutLimit(false));
    }

    @Test
    public void successfulCommandStillPublishesRowsWithoutFallback() throws Exception {
        TopSnapshotCollector.CommandResult result =
                new TopSnapshotCollector.CommandResult(BYD_TOP_OUTPUT, 0, false);
        JSONObject snapshot = TopSnapshotCollector.parseCommandResult(
                result, -1, 1L, 1L, "fixture");

        assertTrue(snapshot.getBoolean("available"));
        assertEquals(6, snapshot.getInt("processCount"));
        assertFalse(snapshot.has("error"));
        assertFalse(result.shouldRetryWithoutLimit(true));
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

    @Test
    public void preservesLineBoundariesAndLateSummariesAfterRowLimit() throws Exception {
        String output = "\r\n  tAsKs: 1 total, 0 running, 1 sleeping, 0 stopped, 0 zombie\r\n"
                + " PID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\r\n"
                + "broken process row\r\n"
                + " 42 shell 20 0 1G 2M 3K S 1.5 0.2 0:01.00 command with spaces  \r\n"
                + " 43 shell 20 0 1G 2M 3K S 1.5 0.2 0:01.00 ignored\n"
                + "  Mem: 2G total, 1G free\n"
                + "  Swap: 1G total, 2M used\r\n"
                + "400%cpu 300%idle\n"
                + "Tasks: 2 total, 1 running, 1 sleeping, 0 stopped, 0 zombie";
        JSONObject snapshot = TopSnapshotCollector.parseSnapshot(output, 1, 42, 0, 0, "fixture");
        assertEquals(1, snapshot.getInt("processCount"));
        JSONObject process = snapshot.getJSONArray("processes").getJSONObject(0);
        assertEquals(42, process.getInt("pid"));
        assertEquals("command with spaces", process.getString("command"));
        assertTrue(process.getBoolean("isOverdrive"));
        assertEquals(mebibytes(2), process.getLong("residentBytes"));
        assertEquals(2, snapshot.getJSONObject("tasks").getInt("total"));
        assertEquals(gibibytes(2), snapshot.getJSONObject("memory").getLong("totalBytes"));
        assertEquals(mebibytes(2), snapshot.getJSONObject("swap").getLong("usedBytes"));
        assertEquals(25.0, snapshot.getJSONObject("cpu").getDouble("deviceBusyPercent"), 0.0);
    }

    @Test
    public void preservesCpuTokenPrecedenceAndUnterminatedProcessRows() throws Exception {
        String header = "PID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\n";
        String row = " 7 shell 20 0 1G 2M 3K S 1.0 0.1 0:00.01 ";
        String output = header + row + "command 100%cpu 25%idle\n"
                + row + "ordinary %CPU command";
        JSONObject snapshot = TopSnapshotCollector.parseSnapshot(output, 24, -1, 0, 0, "fixture");
        assertEquals(1, snapshot.getInt("processCount"));
        assertEquals("ordinary %CPU command", snapshot.getJSONArray("processes")
                .getJSONObject(0).getString("command"));
        assertEquals(75.0, snapshot.getJSONObject("cpu").getDouble("deviceBusyPercent"), 0.0);
        for (String empty : new String[] {null, "", "\n\r\n \t\r\n"}) {
            assertFalse(TopSnapshotCollector.parseSnapshot(empty, 24, -1, 0, 0, "fixture")
                    .getBoolean("available"));
        }
    }

    private static long mebibytes(double value) {
        return Math.round(value * 1024.0 * 1024.0);
    }

    private static long gibibytes(double value) {
        return Math.round(value * 1024.0 * 1024.0 * 1024.0);
    }
}
