package com.overdrive.app.charging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards every surface that consumes the canonical charging ETA block. */
public class ChargingCompletionUiAssetTest {

    @Test
    public void bothLiveApisUseCanonicalResolver() throws Exception {
        String status = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String chargingApi = read("app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        String launcherApi = read("app/src/main/java/com/overdrive/app/server/LauncherApiHandler.java");

        assertTrue(status.contains("ChargingCompletionEstimate"));
        assertTrue(status.contains(".resolveLive(isChargingFused, isFull)"));
        assertTrue(chargingApi.contains("ChargingCompletionEstimate.resolveLive(charging, full)"));
        assertTrue(launcherApi.contains("ChargingCompletionEstimate.resolveLive(active, full)"));
    }

    @Test
    public void webDashboardSidebarAndChargingStatsShowCompletion() throws Exception {
        String dashboard = read("app/src/main/assets/web/local/index.html");
        String shell = read("app/src/main/assets/web/shared/app-shell.js");
        String core = read("app/src/main/assets/web/shared/core.js");
        String chargingPage = read("app/src/main/assets/web/local/charging.html");
        String chargingJs = read("app/src/main/assets/web/shared/charging.js");

        assertTrue(dashboard.contains("dashChargeCompletionMetric"));
        assertTrue(dashboard.contains("dashChargeTtfMeta"));
        assertTrue(shell.contains("evCompletionRow"));
        assertTrue(core.contains("formatChargingCompletion"));
        assertTrue(core.contains("timeToFullSource === 'calculated'"));
        assertTrue(chargingPage.contains("completionHeroCard"));
        assertTrue(chargingJs.contains("completionHeroValue"));
    }

    @Test
    public void nativeCockpitShowsCompletionWithoutDuplicatingEstimator() throws Exception {
        String layout = read("app/src/main/res/layout-land/fragment_dashboard.xml");
        String fragment = read("app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String strings = read("app/src/main/res/values/strings.xml");

        assertTrue(layout.contains("android:id=\"@+id/heroChargeCompletion\""));
        assertTrue(fragment.contains("optString(\"timeToFullSource\""));
        assertTrue(fragment.contains("dashboard_charge_completion_calculated"));
        assertTrue(fragment.contains("dashboard_charge_waiting"));
        assertTrue(fragment.contains("dashboard_charge_complete"));
        assertTrue(strings.contains("%1$s &#183; Full at %2$s"));
        assertTrue(strings.contains("%1$s &#183; %2$d%% at %3$s"));
        assertFalse(strings.contains("Â·"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
