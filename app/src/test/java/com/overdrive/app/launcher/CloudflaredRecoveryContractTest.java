package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.config.CloudflaredPaidConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Guards the three pieces that let a cloudflared tunnel come back on its own.
 *
 * Field incident (Sealion 6, OD 55.3): with `--retries 20` cloudflared's silent supervisor
 * backoff (10 s × 2^retries) slept for hours with the network already back. A manual kill
 * recovered it in ~5 s — but `stopTunnel` could not have done that kill, and the boot-scoped
 * health check had no way to relaunch the tunnel afterwards.
 */
public class CloudflaredRecoveryContractTest {

    @Test
    public void retriesCapsTheReconnectBackoff() throws Exception {
        // --retries caps the backoff at 2^N × base (cloudflared retries forever). 5 → ~32 s per
        // connection and ~5 min for the supervisor; 20 meant ~12 / ~121 days.
        assertTrue(CloudflaredPaidConfig.RECONNECT_BACKOFF_CAP_EXPONENT <= 6);

        String source = read("app/src/main/java/com/overdrive/app/config/CloudflaredPaidConfig.kt");
        assertFalse(source.contains("--retries 20"));
        // Both the paid and the free argument strings must use the shared cap.
        assertEquals(2, count(source, "--retries $RECONNECT_BACKOFF_CAP_EXPONENT"));
    }

    @Test
    public void killPayloadTargetsTheBinaryAndVerifies() {
        String payload = DaemonLauncher.killCloudflaredAndVerify();

        // Kill by PID on the binary's own path. A bare "cloudflared" pattern would also kill
        // concurrent shells mentioning cloudflared.disabled / cloudflared.log.
        assertTrue(payload.contains("grep -F 'libcloudflared.so'"));
        assertTrue(payload.contains("grep -F '/cloudflared '"));
        assertFalse(payload.contains("grep -F 'cloudflared'"));
        // Verify by NAME (never matches the calling `sh`), skipping zombie rows.
        assertTrue(payload.contains("ps -A -o S,NAME | grep -v '^ *Z ' | grep -qF cloudflared"));
        assertTrue(payload.contains("echo " + DaemonLauncher.CLOUDFLARED_STOPPED));
        assertTrue(payload.contains("echo " + DaemonLauncher.CLOUDFLARED_STILL_RUNNING));
    }

    @Test
    public void stopTunnelOnlyReportsStoppedOnTheStoppedMarker() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt");
        String stop = between(source, "fun stopTunnel(", "fun isTunnelRunning(");

        assertTrue(stop.contains("DaemonLauncher.killCloudflaredAndVerify()"));
        assertFalse(stop.contains("killall -9 cloudflared 2>/dev/null; echo done"));
        // A survivor, or output with no marker at all, must be an error — never "stopped".
        assertTrue(stop.contains("output.contains(DaemonLauncher.CLOUDFLARED_STILL_RUNNING) ->"));
        assertTrue(stop.contains("output.contains(DaemonLauncher.CLOUDFLARED_STOPPED) ->"));
        assertTrue(stop.contains("callback.onError(\"Could not verify that the tunnel stopped\")"));
    }

    @Test
    public void failedStopsAreNotReportedAsStopped() throws Exception {
        String vm = read("app/src/main/java/com/overdrive/app/ui/viewmodel/DaemonsViewModel.kt");
        String stopDaemon = between(vm, "fun stopDaemon(", "fun refreshDaemonStatus(");
        String vmError = stopDaemon.substring(stopDaemon.indexOf("override fun onError("));
        assertTrue(vmError.contains("updateState(type, DaemonStatus.ERROR"));
        assertFalse(vmError.contains("DaemonStatus.STOPPED"));
        assertTrue(vmError.contains("refreshDaemonStatus(type)"));

        String controller = read("app/src/main/java/com/overdrive/app/ui/daemon/CloudflaredController.kt");
        String stop = between(controller, "override fun stop(", "override fun isRunning(");
        String onError = stop.substring(stop.indexOf("override fun onError("));
        // The tunnel may still be up after a failed stop: keep its URL.
        assertFalse(onError.contains("_tunnelUrl.postValue(null)"));
        assertFalse(onError.contains("DaemonStatus.STOPPED"));
        assertTrue(onError.contains("callback.onError(error)"));
        String success = between(stop, "override fun onLaunched()", "override fun onError(");
        assertTrue(success.contains("_tunnelUrl.postValue(null)"));
        assertTrue(success.contains("callback.onStatusChanged(DaemonStatus.STOPPED"));
    }

    @Test
    public void bootPathHealthCheckCanRelaunchCloudflared() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");
        String fallback = between(source, "private fun doRelaunchDaemon(", "fun clearStaleSentinels(");

        assertTrue(fallback.contains("DaemonType.CLOUDFLARED_TUNNEL -> {"));
        assertTrue(fallback.contains("adbLauncher.launchTunnel("));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue("missing: " + start, from >= 0);
        int to = source.indexOf(end, from);
        assertTrue("missing: " + end, to > from);
        return source.substring(from, to);
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
