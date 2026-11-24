package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Source-level contract for the Wi-Fi monitoring path: no process in the app may run the
 * Wi-Fi service's dump (each call forks log-capture children inside system_server that can
 * outlive it), every remaining shell fallback is bounded, periodic consumers read through
 * the cache, and the hidden-name placeholder is one shared constant.
 */
public class NetworkMonitorPlaceholderContractTest {

    private static final String MONITOR = "app/src/main/java/com/overdrive/app/monitor/NetworkMonitor.java";
    private static final String EVENT = "app/src/main/java/com/overdrive/app/automation/condition/NetworkEvent.java";
    private static final String POLICY = "app/src/main/java/com/overdrive/app/safekeep/Policy.java";
    private static final String SIGNALS = "app/src/main/java/com/overdrive/app/safekeep/UploadSignals.java";
    private static final String DAEMON = "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java";

    @Test
    public void networkMonitorNeverDumpsAndBoundsEveryShellCall() throws IOException {
        String nm = read(MONITOR);
        assertFalse(nm.contains("dumpsys"));
        assertFalse(nm.contains("Runtime.getRuntime().exec("));
        assertTrue(nm.contains("BoundedShell.run("));
        assertTrue(nm.contains("BoundedShell.run(\"cmd wifi status\", SHELL_DEADLINE_MS)"));
        assertTrue(nm.contains("WifiStatusParser.parse("));
        // Every shell fallback is bounded (ip addr, cmd, wpa_cli x2, route get).
        assertEquals(5, count(nm, "BoundedShell.run("));
        assertTrue(nm.contains("SHELL_SSID_MIN_INTERVAL_MS = 10_000L"));
        assertTrue(nm.contains("now - lastShellSsidAttemptAtElapsed >= SHELL_SSID_MIN_INTERVAL_MS"));
    }

    @Test
    public void networkCacheRefreshesWithoutAnInitialSample() {
        assertTrue(NetworkMonitor.needsNetworkRefresh(-1L, 0L));
        assertTrue(NetworkMonitor.needsNetworkRefresh(-1L, 1_000L));
        assertFalse(NetworkMonitor.needsNetworkRefresh(0L, 0L));
    }

    @Test
    public void networkCachePreservesTenSecondAgeBoundary() {
        assertFalse(NetworkMonitor.needsNetworkRefresh(1_000L, 1_000L));
        assertFalse(NetworkMonitor.needsNetworkRefresh(1_000L, 10_999L));
        assertFalse(NetworkMonitor.needsNetworkRefresh(1_000L, 11_000L));
        assertTrue(NetworkMonitor.needsNetworkRefresh(1_000L, 11_001L));
        assertTrue(NetworkMonitor.needsNetworkRefresh(1_000L, 999L));
    }

    @Test
    public void allNetworkCachePathsUseElapsedRealtime() throws IOException {
        String nm = read(MONITOR);
        assertTrue(nm.contains("lastUpdateElapsed = -1L;"));
        assertEquals(7, count(nm, "lastUpdateElapsed = SystemClock.elapsedRealtime();"));
        assertTrue(nm.contains("needsNetworkRefresh(lastUpdateElapsed, SystemClock.elapsedRealtime())"));
        assertFalse(nm.contains("System.currentTimeMillis()"));
    }

    @Test
    public void placeholderIsOneSharedConstant() throws IOException {
        String nm = read(MONITOR);
        assertTrue(nm.contains("public static final String SSID_UNKNOWN_PLACEHOLDER = \"WiFi\";"));
        // The only "WiFi" literal left in the monitor is the constant's own definition.
        assertEquals(1, count(nm, "\"WiFi\""));
        assertTrue(count(nm, "SSID_UNKNOWN_PLACEHOLDER") >= 6);

        String policy = read(POLICY);
        assertTrue(policy.contains("NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER.equals(ssid)"));
        assertFalse(policy.contains("\"WiFi\""));
        // Policy stays JVM-pure: the constant is inlined, no Android import is needed.
        assertFalse(policy.contains("import android."));

        String event = read(EVENT);
        assertTrue(event.contains("NetworkMonitor.SSID_UNKNOWN_PLACEHOLDER"));
    }

    @Test
    public void networkEventReadsThroughTheCacheEveryTick() throws IOException {
        String event = read(EVENT);
        int start = event.indexOf("private static void publishWifi()");
        assertTrue(start > 0);
        String body = event.substring(start);
        assertTrue(body.contains("NetworkMonitor.getNetworkInfo();"));
        assertFalse(body.contains("NetworkMonitor.refresh()"));
        assertFalse(event.contains("is internally cache-bounded"));
        assertTrue(event.contains("private static final long POLL_MS = 2000L;"));
        // Publish order on connect is unchanged: SSID lands before the STATE edge.
        assertTrue(body.indexOf("Automations.update(BydEvent.WIFI_SSID")
                < body.indexOf("Automations.update(BydEvent.WIFI_STATE"));
    }

    @Test
    public void statusJsonIsAdditiveAndMeteredHintIsTriState() throws IOException {
        String nm = read(MONITOR);
        int start = nm.indexOf("public static JSONObject getNetworkInfo()");
        assertTrue(start > 0);
        String body = nm.substring(start, nm.indexOf("public static String getNetworkType()"));
        for (String key : new String[]{"type", "ssid", "ip", "signal", "meteredHint", "meteredHintKnown"}) {
            assertTrue(key, body.contains("net.put(\"" + key + "\""));
        }
        assertTrue(nm.contains("public static boolean isWifiMeteredHintKnown()"));

        String signals = read(SIGNALS);
        assertTrue(signals.contains(
                "NetworkMonitor.isWifiMeteredHintKnown() && NetworkMonitor.isWifiMeteredHint()"));
    }

    @Test
    public void daemonHotspotProbeUsesNoWifiDump() throws IOException {
        String daemon = read(DAEMON);
        assertFalse(daemon.contains("dumpsys wifi"));
        assertFalse(daemon.contains("curState=SoftApState"));
        int start = daemon.indexOf("private static boolean reconcileStrandedHotspotSuppression()");
        assertTrue(start > 0);
        String body = daemon.substring(start, daemon.indexOf("// ==================== MONITORING & DIAGNOSTICS"));
        assertTrue(body.contains("HotspotApLiveness.probe(declared, apIface)"));
        assertFalse(body.contains("execShell("));
        // ENABLING hold-off and the DI5 seen-up trust window are untouched.
        assertTrue(body.contains("if (enablingHoldOffTicks < MAX_ENABLING_HOLD_OFF_TICKS) {"));
        assertTrue(body.contains("< DILINK5_SOFTAP_UP_RECHECK_MS) {"));
        assertTrue(daemon.contains("DILINK5_SOFTAP_UP_RECHECK_MS = 60_000L;"));
    }

    @Test
    public void noMainSourceRunsTheWifiDump() throws IOException {
        Path main = repositoryRoot().resolve("app/src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".java") || n.endsWith(".kt");
                    })
                    .forEach(f -> {
                        try {
                            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                            if (text.contains("dumpsys wifi")) {
                                offenders.add(main.relativize(f).toString());
                            }
                        } catch (IOException e) {
                            offenders.add(f + ": unreadable");
                        }
                    });
        }
        assertTrue("sources still running the Wi-Fi dump: " + offenders, offenders.isEmpty());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("app/src/main"))) return current;
            current = current.getParent();
        }
        throw new AssertionError("repository root not found from " + System.getProperty("user.dir"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
