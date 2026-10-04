package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Guards the safety/lifecycle contract of the opt-in DiLink 5 parked
 * BYD-cloud heartbeat.
 */
public class Di5ParkedCloudKeepAliveContractTest {

    @Test
    public void nextRequestIsAnchoredToExactlyFifteenSeconds() {
        long started = TimeUnit.SECONDS.toNanos(10L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveCadenceDelayMs(
                        started,
                        started + TimeUnit.SECONDS.toNanos(2L))
                == 13_000L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveCadenceDelayMs(
                        started,
                        started + TimeUnit.MILLISECONDS.toNanos(14_999L))
                == 1L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveCadenceDelayMs(
                        started,
                        started + TimeUnit.SECONDS.toNanos(15L))
                == 0L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveCadenceDelayMs(
                        started,
                        started + TimeUnit.SECONDS.toNanos(20L))
                == 0L);
    }

    @Test
    public void failedWakeRequestsUseBoundedFastRetryBackoff() {
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveRetryDelayMs(1) == 1_000L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveRetryDelayMs(2) == 3_000L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveRetryDelayMs(3) == 5_000L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveRetryDelayMs(100) == 5_000L);

        long started = TimeUnit.SECONDS.toNanos(10L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveFailureDelayMs(
                        3,
                        started,
                        started + TimeUnit.SECONDS.toNanos(14L))
                == 1_000L);
        assertTrue(BydCloudDataProvider
                .di5ParkedKeepAliveFailureDelayMs(
                        3,
                        started,
                        started + TimeUnit.SECONDS.toNanos(15L))
                == 0L);
    }

    @Test
    public void configAndApiAreOptInAndPrerequisiteGated() throws IOException {
        String config = read(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        String api = read(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");

        assertTrue(config.contains(
                "surveillance.put(\"di5CloudKeepAlive\", false)"));
        assertTrue(config.contains(
                "fun isDi5CloudKeepAliveEnabled(): Boolean"));
        assertTrue(config.contains(
                "optBoolean(\"di5CloudKeepAlive\", false)"));

        assertTrue(api.contains(
                "config.put(\"di5CloudKeepAlive\","));
        assertTrue(api.contains(
                "config.put(\"di5CloudKeepAliveSupported\","));
        assertTrue(api.contains(
                "config.put(\"di5CloudKeepAliveCloudReady\","));
        assertTrue(api.contains(
                "if (configJson.has(\"di5CloudKeepAlive\"))"));
        assertTrue(api.contains(
                "Connect and verify BYD Cloud before enabling this setting"));
        assertTrue(api.contains(
                "CameraDaemon.reconcileDi5CloudKeepAliveFromConfig()"));
    }

    @Test
    public void providerUsesOneCancelableGenerationOrderedWorker()
            throws IOException {
        String provider = read(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudDataProvider.java");

        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_INTERVAL_MS = 15_000L"));
        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_LOCK_RETRY_MS = 500L"));
        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_DEADLINE_MS = 16_000L"));
        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_RETRY_FIRST_MS = 1_000L"));
        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_RETRY_SECOND_MS = 3_000L"));
        assertTrue(provider.contains(
                "DI5_PARKED_KEEPALIVE_RETRY_MAX_MS = 5_000L"));
        assertTrue(provider.contains("executor.schedule("));
        assertFalse(provider.contains("executor.scheduleAtFixedRate("));
        assertTrue(provider.contains(
                "di5ParkedKeepAliveCadenceDelayMs("));
        assertTrue(provider.contains(
                "warnIfDi5ParkedKeepAliveLate("));
        assertTrue(provider.contains(
                "fetchVehicleRealtimeForParkedKeepAlive("));
        assertTrue(provider.contains(
                "invalidateDi5ParkedKeepAliveProxyRoute(t)"));
        assertTrue(provider.contains("t.setDaemon(true);"));
        assertTrue(provider.contains(
                "if (generation < di5ParkedKeepAliveGeneration)"));
        assertTrue(provider.contains(
                "if (!realtimeRequestLock.tryLock())"));
        assertTrue(provider.contains(
                "client.fetchVehicleRealtime(config.vin)"));
        assertTrue(provider.contains(
                "handle.future.cancel(true)"));
        assertTrue(provider.contains(
                "handle.client.cancelRequestForThread(handle.worker)"));
        assertTrue(provider.contains(
                "stopDi5ParkedKeepAlive(\"cloud runtime reset\")"));
        assertTrue(provider.contains(
                "di5ParkedKeepAliveShutdown = true"));
        assertTrue(provider.contains(
                "DI5 cloud keep-alive request starting"));
        assertTrue(provider.contains(
                "DI5 cloud keep-alive request completed in "));
        assertTrue(provider.contains(
                "\"di5ParkedKeepAliveDeadlineMisses\""));
    }

    @Test
    public void everyHeartbeatRequestReevaluatesTheLiveProxy()
            throws IOException {
        String transport = read(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudTransport.java");
        String client = read(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java");

        assertTrue(transport.contains(
                "DYNAMIC_PROXY_SELECTOR"));
        assertTrue(transport.contains(
                ".proxySelector(DYNAMIC_PROXY_SELECTOR)"));
        assertTrue(transport.contains(
                "ProxyHelper.getHttpProxy()"));
        assertTrue(transport.contains(
                "ProxyHelper.invalidateCache()"));
        assertTrue(transport.contains(
                "call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)"));
        assertTrue(client.contains(
                "fetchVehicleRealtimeForParkedKeepAlive(String vin)"));
        assertTrue(client.contains(
                "return fetchVehicleRealtime(vin, 1, 6_000L)"));
    }

    @Test
    public void accAndDaemonShutdownOwnTheRuntimeLifecycle()
            throws IOException {
        String daemon = read(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String cloudApi = read(
                "app/src/main/java/com/overdrive/app/server/BydCloudApiHandler.java");

        int accPublish = daemon.indexOf(
                "com.overdrive.app.monitor.AccMonitor.setAccState(!accIsOff)");
        int reconcile = daemon.indexOf(
                "reconcileDi5CloudKeepAliveForAccState(",
                accPublish);
        assertTrue(accPublish >= 0);
        assertTrue(reconcile > accPublish);
        assertTrue(daemon.contains(
                ".shutdownDi5ParkedKeepAlive(\"CameraDaemon shutdown\")"));
        assertTrue(daemon.contains(
                ".shutdownDi5ParkedKeepAlive("));
        assertTrue(daemon.contains(
                "shouldBridgeDi5KeepAliveAcrossProcessRestart()"));
        assertTrue(daemon.contains(
                "planned restart cleanup complete"));
        assertTrue(daemon.contains(
                "public static void reconcileDi5CloudKeepAliveFromConfig()"));
        assertTrue(daemon.contains(
                "reconcileDi5CloudKeepAliveForCurrentState(\n"
                        + "                        \"confirmed startup recovery\")"));
        assertTrue(cloudApi.contains(
                "CameraDaemon.reconcileDi5CloudKeepAliveFromConfig()"));
    }

    @Test
    public void surveillanceUiMatchesExistingImmediateToggleContract()
            throws IOException {
        String html = read(
                "app/src/main/assets/web/local/surveillance.html");
        String script = read(
                "app/src/main/assets/web/shared/surveillance.js");
        String english = read(
                "app/src/main/assets/web/i18n/en.json");

        assertTrue(html.contains(
                "id=\"survDi5CloudKeepAliveRow\" style=\"display:none;\""));
        assertTrue(html.contains(
                "id=\"survDi5CloudKeepAlive\" disabled "
                        + "onchange=\"SurvSettings.toggleDi5CloudKeepAlive()\""));
        assertTrue(html.contains(
                "surveillance.js?v=survtoggle10"));
        assertTrue(script.contains(
                "toggleDi5CloudKeepAlive()"));
        assertTrue(script.contains(
                "_nextImmediateWrite('di5CloudKeepAlive')"));
        assertTrue(script.contains(
                "{ di5CloudKeepAlive: on }"));
        assertTrue(script.contains(
                "applyDi5CloudKeepAliveUI()"));
        assertTrue(script.contains(
                "(!cloudReady && !enabled)"));
        assertTrue(english.contains(
                "\"di5_cloud_keepalive\""));
        assertTrue(english.contains(
                "\"di5_cloud_keepalive_desc\""));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(
                        Files.readAllBytes(direct),
                        StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
