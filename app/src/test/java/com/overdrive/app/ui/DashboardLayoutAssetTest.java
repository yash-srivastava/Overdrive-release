package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the dashboard's generated visual direction and native state wiring. */
public class DashboardLayoutAssetTest {

    @Test
    public void landscapeCockpitUsesLiveVisualSignals() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");

        assertFalse(layout.contains("android:id=\"@+id/heroStateIcon\""));
        assertTrue(layout.contains("android:id=\"@+id/heroStateDot\""));
        assertTrue(layout.contains("android:id=\"@+id/heroVehicleStage\""));
        assertTrue(layout.contains("android:id=\"@+id/heroVehicleGuideLines\""));
        assertTrue(layout.contains("android:id=\"@+id/heroVehicleWebView\""));
        assertTrue(layout.contains("android:id=\"@+id/heroSocProgress\""));
        assertTrue(layout.contains("android:id=\"@+id/heroRangeValue\""));
        assertTrue(layout.contains("android:id=\"@+id/heroSohValue\""));
        assertTrue(layout.contains("android:id=\"@+id/heroChargeCompletion\""));
        assertTrue(layout.contains("android:id=\"@+id/servicesSegmentGroup\""));
        assertTrue(layout.contains("android:id=\"@+id/metricVehicleDetail\""));
        assertFalse(layout.contains("@drawable/dashboard_vehicle_hero"));
        assertTrue(layout.contains("@drawable/dashboard_radar"));
        assertTrue(layout.contains("@drawable/dashboard_vehicle_stage_lines"));
        assertTrue(layout.contains("@drawable/dashboard_soc_outer_ring"));
        assertTrue(layout.contains("@drawable/ic_battery_health"));
        assertFalse(layout.contains("@drawable/ic_security_shield"));
        assertTrue(layout.contains("@drawable/dashboard_icon_badge"));
        assertTrue(layout.contains("@drawable/dashboard_brand_mark"));
    }

    @Test
    public void landscapeHeroReusesSelectedGlbVehiclePipeline() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String heroPage = readRepositoryFile(
                "app/src/main/assets/web/local/dashboard-vehicle-hero.html");

        assertFalse(layout.contains("android:id=\"@+id/heroVehicleImage\""));
        assertTrue(layout.contains("android:id=\"@+id/heroVehicleWebView\""));
        assertTrue(fragment.contains("/local/dashboard-vehicle-hero.html"));
        assertTrue(fragment.contains("DashboardHeroBridge"));
        assertTrue(heroPage.contains("../shared/app-shell.js"));
        assertTrue(heroPage.contains("mountVehicleCanvas(canvas"));
        assertTrue(heroPage.contains("view: 'three-quarter'"));
        assertTrue(heroPage.contains("onVehicleHeroReady"));
        assertTrue(heroPage.contains("width: 400px"));
        assertTrue(heroPage.contains("height: 168px"));
        assertTrue(heroPage.contains("max-width: 96%"));
        assertTrue(heroPage.contains("max-height: 88%"));
        assertFalse(heroPage.contains("atto3"));
        assertFalse(repositoryFileExists(
                "app/src/main/res/drawable-nodpi/dashboard_vehicle_hero.png"));
    }

    @Test
    public void healthIsAnAccentRatherThanAFilledHero() throws Exception {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");

        assertTrue(fragment.contains("colorSurfaceContainerLow"));
        assertTrue(fragment.contains("heroCard.strokeColor = accent"));
        assertTrue(fragment.contains("renderServiceSegments(running)"));
        assertFalse(fragment.contains("colorPrimaryContainer"));
        assertFalse(fragment.contains("colorErrorContainer"));
    }

    @Test
    public void landscapeQrUrlCannotWrapBelowItsCard() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout/dashboard_remote_access_details.xml");
        int start = layout.indexOf("android:id=\"@+id/tvUrl\"");
        int end = layout.indexOf("/>", start);
        String urlLabel = layout.substring(start, end);

        assertTrue(urlLabel.contains("android:ellipsize=\"middle\""));
        assertTrue(urlLabel.contains("android:maxLines=\"1\""));
        assertTrue(urlLabel.contains("android:singleLine=\"true\""));
        assertTrue(layout.contains("android:layout_width=\"180dp\""));
    }

    @Test
    public void landscapeCommandDeckOmitsRedundantQuickActions() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String brandMark = readRepositoryFile(
                "app/src/main/res/drawable/dashboard_brand_mark.xml");

        assertTrue(layout.contains("android:id=\"@+id/dashboardCommandDeck\""));
        assertTrue(layout.contains("android:layout_height=\"192dp\""));
        assertTrue(layout.contains("android:id=\"@+id/metricVehicle\""));
        assertTrue(layout.contains("android:id=\"@+id/dashboardActionsColumn\""));
        assertTrue(layout.contains("android:id=\"@+id/cardDaemons\""));
        assertTrue(layout.contains("android:id=\"@+id/servicesSegmentGroup\""));
        assertFalse(layout.contains("android:id=\"@+id/dashboardQuickActions\""));
        assertFalse(layout.contains("android:id=\"@+id/quickLivePrimary\""));
        assertFalse(layout.contains("android:id=\"@+id/quickRecordings\""));
        assertFalse(layout.contains("android:id=\"@+id/quickCharging\""));
        assertFalse(fragment.contains("quickLivePrimary"));
        assertFalse(fragment.contains("quickRecordings"));
        assertFalse(fragment.contains("quickCharging"));
        assertTrue(brandMark.contains("official OverDrive OD glyph"));
        assertTrue(brandMark.contains("?attr/colorPrimary"));
    }

    @Test
    public void recordingsMetricDeepLinksToTodayWithoutOverridingRestoredState() throws Exception {
        String dashboard = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String recordings = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");
        String navGraph = readRepositoryFile("app/src/main/res/navigation/nav_graph.xml");

        assertTrue(dashboard.contains(
                "putBoolean(RecordingsFragment.ARG_TODAY_ONLY, true)"));
        assertTrue(recordings.contains(
                "arguments?.getBoolean(ARG_TODAY_ONLY, false) == true"));
        assertTrue(recordings.contains("if (savedInstanceState == null)"));
        assertTrue(recordings.contains("narrowToToday()"));
        assertTrue(recordings.contains("dateNarrowed = true"));
        assertTrue(recordings.contains("selectTodaySourceIfNeeded("));
        assertTrue(recordings.contains("stats.normalToday + stats.proximityToday"));
        assertTrue(recordings.contains("Source.SURVEILLANCE to surveillanceToday"));
        assertTrue(navGraph.contains("android:name=\"today_only\""));
        assertTrue(navGraph.contains("android:defaultValue=\"false\""));
    }

    @Test
    public void landscapeVehicleStageIsStaticAndServicesUseEightSegments() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String guideLines = readRepositoryFile(
                "app/src/main/res/drawable/dashboard_vehicle_stage_lines.xml");

        assertTrue(layout.contains("android:id=\"@+id/heroVehicleGuideLines\""));
        assertFalse(layout.contains("heroRadarPulse"));
        for (int index = 1; index <= 8; index++) {
            assertTrue(layout.contains("android:id=\"@+id/serviceSegment" + index + "\""));
        }
        assertFalse(fragment.contains("ValueAnimator.INFINITE"));
        assertFalse(fragment.contains("RADAR_PULSE_STAGGER_MS"));
        assertTrue(fragment.contains("SERVICE_SEGMENT_COUNT = 8"));
        assertTrue(fragment.contains("renderServiceSegments(running)"));
        assertFalse(fragment.contains("stopRadarPulse()"));
        assertTrue(guideLines.contains("xmlns:aapt"));
        assertTrue(guideLines.contains("<gradient"));
        assertTrue(guideLines.contains("@android:color/transparent"));
        assertTrue(guideLines.contains("dashboard_vehicle_stage_line_focus"));
        assertTrue(guideLines.contains("M72,151 L348,151"));
        assertFalse(guideLines.contains("M18,124 C88,82"));
    }

    @Test
    public void landscapeVehicleCardReservesRoomForItsDetailLine() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        int start = layout.indexOf("android:id=\"@+id/metricVehicle\"");
        int end = layout.indexOf("</com.google.android.material.card.MaterialCardView>", start);
        String vehicleCard = layout.substring(start, end);

        assertTrue(vehicleCard.contains("android:padding=\"16dp\""));
        assertTrue(vehicleCard.contains("android:id=\"@+id/metricVehicleDetail\""));
        assertTrue(vehicleCard.contains("android:id=\"@+id/metricVehicleStatus\""));
        assertTrue(vehicleCard.contains("android:includeFontPadding=\"false\""));
        int detailStart = vehicleCard.indexOf("android:id=\"@+id/metricVehicleDetail\"");
        int detailEnd = vehicleCard.indexOf("/>", detailStart);
        String detail = vehicleCard.substring(detailStart, detailEnd);
        assertTrue(detail.contains("android:maxLines=\"2\""));
        assertFalse(detail.contains("android:ellipsize=\"end\""));
    }

    @Test
    public void landscapeCockpitUsesUniformGuttersAndAnInsetSocRing() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");

        assertTrue(layout.contains("android:layout_height=\"220dp\""));
        assertTrue(layout.contains("android:paddingBottom=\"16dp\""));
        assertTrue(layout.contains("android:layout_width=\"138dp\""));
        assertTrue(layout.contains("android:layout_height=\"138dp\""));
        assertTrue(layout.contains("app:indicatorSize=\"118dp\""));
        assertTrue(layout.contains("app:trackThickness=\"8dp\""));
        assertTrue(layout.contains("app:trackCornerRadius=\"4dp\""));
        assertTrue(layout.contains("android:paddingHorizontal=\"8dp\""));
        assertTrue(layout.contains("android:layout_marginBottom=\"6dp\""));
        assertTrue(layout.contains("android:layout_marginVertical=\"6dp\""));
        assertTrue(layout.contains("android:layout_height=\"96dp\""));
        assertTrue(layout.contains("android:paddingHorizontal=\"16dp\""));
        assertTrue(layout.contains("android:paddingVertical=\"8dp\""));
        int completionStart = layout.indexOf("android:id=\"@+id/heroChargeCompletion\"");
        int completionEnd = layout.indexOf("/>", completionStart);
        String completion = layout.substring(completionStart, completionEnd);
        assertTrue(completion.contains("android:layout_marginTop=\"3dp\""));
        assertTrue(completion.contains("android:textSize=\"11sp\""));
    }

    @Test
    public void vehicleCardLabelsDirectAndEstimatedSohCorrectly() throws Exception {
        String landscape = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String portrait = readRepositoryFile(
                "app/src/main/res/layout/fragment_dashboard.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String strings = readRepositoryFile(
                "app/src/main/res/values/strings.xml");

        assertTrue(landscape.contains("android:id=\"@+id/metricVehicleDetail\""));
        assertTrue(portrait.contains("android:id=\"@+id/metricVehicleDetail\""));
        assertTrue(fragment.contains("\"/status\""));
        assertTrue(fragment.contains("\"/api/performance/soh\""));
        assertTrue(fragment.contains("optDouble(\"elecRangeKm\""));
        assertTrue(fragment.contains("heroSocProgress?.setProgressCompat"));
        assertTrue(fragment.contains("dashboard_vehicle_range_miles"));
        assertTrue(fragment.contains("socPercent, sohPercent, sohSource, nominalKwh"));
        assertTrue(fragment.contains("if (sohSource == \"oem\") R.string.dashboard_vehicle_soh_metric"));
        assertTrue(fragment.contains("VEHICLE_REFRESH_MAX_RETRIES = 2"));
        assertTrue(fragment.contains("generation != vehicleRefreshGeneration"));
        assertTrue(strings.contains("name=\"dashboard_vehicle_soc_metric\""));
        assertTrue(strings.contains("name=\"dashboard_vehicle_soh_metric\""));
        assertTrue(strings.contains("name=\"dashboard_vehicle_soh_metric_estimated\""));
        assertTrue(strings.contains("name=\"dashboard_vehicle_capacity_metric\""));
        assertTrue(strings.contains("name=\"dashboard_vehicle_soc_value\""));
        assertTrue(strings.contains("name=\"dashboard_vehicle_telemetry_linked\""));
    }

    @Test
    public void landscapeRemotePairingIsCollapsedUntilRequested() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String remoteDetails = readRepositoryFile(
                "app/src/main/res/layout/dashboard_remote_access_details.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");

        assertTrue(layout.contains("<include layout=\"@layout/dashboard_remote_access_details\""));
        assertTrue(layout.contains("android:id=\"@+id/heroCard\""));
        assertTrue(remoteDetails.contains("android:visibility=\"gone\""));
        assertTrue(remoteDetails.contains("android:id=\"@+id/ivQrCode\""));
        assertTrue(remoteDetails.contains("android:id=\"@+id/btnCloseRemoteAccess\""));
        assertTrue(fragment.contains("toggleRemoteAccessDetails()"));
        assertTrue(fragment.contains("setRemoteAccessDetailsVisible(false)"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
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

    private static boolean repositoryFileExists(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(relativePath))) {
                return true;
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
