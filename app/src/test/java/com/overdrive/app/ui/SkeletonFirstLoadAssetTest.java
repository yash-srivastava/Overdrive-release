package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The restyled pages hold their card shape on first paint with one shared placeholder, native and
 * WebView, and retire it once — these pages poll, so a per-refresh placeholder would flicker.
 */
public class SkeletonFirstLoadAssetTest {

    private static final String[] DASHBOARD_LAYOUTS = {
            "app/src/main/res/layout/fragment_dashboard.xml",
            "app/src/main/res/layout-land/fragment_dashboard.xml",
    };

    private static final String[] DASHBOARD_SLOTS = {
            "@+id/vehicleSocSkeleton",
            "@+id/vehicleRangeSkeleton",
            "@+id/metricRecordingsSkeleton",
            "@+id/metricStorageSkeleton",
            "@+id/activityRow1Skeleton",
    };

    private static final String[] RECORDINGS_LAYOUTS = {
            "app/src/main/res/layout/fragment_recordings.xml",
            "app/src/main/res/layout-land/fragment_recordings.xml",
    };

    private static final String[] LIBRARY_LAYOUTS = {
            "app/src/main/res/layout/fragment_recording_library.xml",
            "app/src/main/res/layout-land/fragment_recording_library.xml",
    };

    private static final String[] PROJECTION_LAYOUTS = {
            "app/src/main/res/layout/fragment_projection.xml",
            "app/src/main/res/layout-land/fragment_projection.xml",
    };

    @Test
    public void sharedNativePrimitiveExists() throws IOException {
        assertTrue(readRepositoryFile("app/src/main/res/drawable/skeleton_block.xml")
                .contains("@dimen/dashboard_modern_radius"));
        for (String path : new String[]{
                "app/src/main/res/layout/skeleton_line.xml",
                "app/src/main/res/layout/skeleton_tile.xml"}) {
            assertTrue(path, readRepositoryFile(path).contains("@drawable/skeleton_block"));
        }

        String skeleton = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/widget/Skeleton.kt");
        assertTrue(skeleton.contains("fun bind(blockId: Int"));
        assertTrue(skeleton.contains("fun markLoaded(blockId: Int)"));
        assertTrue(skeleton.contains("fun follow(blockId: Int)"));
        assertTrue(skeleton.contains("fun cancel()"));
        // markLoaded is one-way: a resolved slot must not be re-covered by a later poll.
        assertTrue(skeleton.contains("if (loaded.contains(blockId)) return"));
        // The value keeps its box while covered, so no card changes height on resolve.
        assertTrue(skeleton.contains("view.visibility = View.INVISIBLE"));
        assertFalse(skeleton.contains("view.visibility = View.GONE"));
    }

    @Test
    public void sharedWebPrimitiveExists() throws IOException {
        String styles = readRepositoryFile("app/src/main/assets/web/shared/styles.css")
                .replace("\r", "");
        assertTrue(styles.contains("@keyframes skPulse"));
        // The pulse walks two opaque shades. An opacity pulse would show the covered
        // value and the host's border through the placeholder on every low frame.
        String pulse = styles.substring(styles.indexOf("@keyframes skPulse"));
        pulse = pulse.substring(0, pulse.indexOf("\n}"));
        assertTrue(pulse.contains("background-color: var(--surface-container-high)"));
        assertTrue(pulse.contains("background-color: var(--surface-container-highest)"));
        assertFalse(pulse.contains("opacity"));
        // The host pulses too: the cover cannot reach the border ring, and page CSS
        // sets that border after this file, so the override has to win on weight.
        assertTrue(styles.contains(".is-sk {\n"
                + "    border-color: transparent !important;\n"
                + "    animation: skPulse 1.24s ease-in-out infinite;"));
        for (String cls : new String[]{
                ".sk {", ".sk-line {", ".sk-pill {", ".sk-tile {", ".sk-cover {"}) {
            assertTrue(cls, styles.contains(cls));
        }

        String helper = readRepositoryFile("app/src/main/assets/web/shared/skeleton.js");
        assertTrue(helper.contains("resolve: function (group)"));
        assertTrue(helper.contains("show: function (group)"));
        assertTrue(helper.contains("resolveAll: function ()"));
        // The once flag: a page cannot put a placeholder back after the first success.
        assertTrue(helper.contains("if (this._resolved[group]) return;"));
        assertTrue(helper.contains("setCoverHost"));
        assertTrue(styles.contains(".is-sk > :not(.sk)"));
        // The host carries is-sk in the markup: this file loads from <head>, before
        // the elements exist, so it cannot be the thing that first hides a value.
        assertFalse(helper.contains("each('[data-sk-group]', function (el) {\n        if (el.style"));
    }

    @Test
    public void dashboardCoversItsFirstPaintValues() throws IOException {
        for (String path : DASHBOARD_LAYOUTS) {
            String layout = readRepositoryFile(path);
            for (String slot : DASHBOARD_SLOTS) {
                assertTrue(path + " " + slot, layout.contains(slot));
            }
        }

        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        assertTrue(fragment.contains("private fun bindSkeletons(view: View)"));
        assertTrue(fragment.contains("skeleton?.cancel()"));
        for (String slot : DASHBOARD_SLOTS) {
            assertTrue(slot, fragment.contains("R.id." + slot.substring("@+id/".length())));
        }
        assertTrue(fragment.contains(
                "if (dashboardState.vehicle != DashboardUiState.VehicleState.Loading)"));
        // Clips and storage answer on separate hops, so neither retires the other's placeholder.
        assertTrue(fragment.contains("if (clipCountResolved || unavailable)"));
        assertTrue(fragment.contains("if (storageResolved || unavailable)"));
        assertTrue(fragment.contains("skeleton?.isLoaded(R.id.activityRow1Skeleton) != true"));
        // The activity placeholder spans the row, so its icon is covered with its text.
        assertTrue(fragment.contains("skeleton.bind(R.id.activityRow1Skeleton, it.icon, it.text)"));
        // A rotation must not repaint resolved sections as Loading.
        assertTrue(fragment.contains("DashboardStateReducer.remoteExpanded("));
        assertFalse(fragment.contains("DashboardUiState(),"));
    }

    @Test
    public void recordingsCoversSummaryAndFirstLoadTiles() throws IOException {
        for (String path : RECORDINGS_LAYOUTS) {
            assertTrue(path, readRepositoryFile(path).contains("@+id/tvRecordingsSummarySkeleton"));
        }
        for (String path : LIBRARY_LAYOUTS) {
            String layout = readRepositoryFile(path);
            assertTrue(path, layout.contains("layout=\"@layout/skeleton_tile\""));
            // The loading copy became the container's description; the spinner is gone.
            assertTrue(path + " label", layout.contains(
                    "android:contentDescription=\"@string/recording_library_loading\""));
        }

        String recordings = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");
        assertTrue(recordings.contains("bind(R.id.tvRecordingsSummarySkeleton"));
        assertTrue(recordings.contains("markLoaded(R.id.tvRecordingsSummarySkeleton)"));
        assertTrue(recordings.contains("skeleton?.cancel()"));

        String library = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingLibraryFragment.kt");
        assertTrue(library.contains("follow(R.id.initialLoadingContainer)"));
        assertTrue(library.contains("skeleton?.cancel()"));
        // The real empty states and the refresh bar stay: they are answers, not loading.
        assertTrue(library.contains("emptyStateContainer?.visibility = View.VISIBLE"));
        assertTrue(library.contains("R.string.recording_library_refreshing"));
    }

    @Test
    public void vehicleControlCoversPillsAndTyresUntilFirstReading() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        assertTrue(html.contains("skeleton.js"));
        // Each placeholder covers its own pill or cell, so it cannot be a different size.
        for (String group : new String[]{"vcLock", "vcCloud", "vcTyres"}) {
            assertTrue(group, html.contains("sk-cover") && html.contains(
                    "data-sk-group=\"" + group + "\""));
            assertFalse(group + " sibling", html.contains("data-sk-real=\"" + group + "\""));
        }
        // Every covered host is marked in the markup, so nothing shows through the pulse.
        assertTrue(html.contains("vc-pill compact-status-pill is-sk"));
        assertTrue(html.contains("vc-tyre-cell is-sk"));
        // The host has to establish the containing block the cover positions against.
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css")
                .replace("\r", "");
        assertTrue(css.contains(".vc-pill {\n    position: relative;"));
        assertTrue(css.contains(".vc-tyre-cell {\n    position: relative;"));

        String js = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        assertTrue(js.contains("BYD.skeleton.resolve('vcCloud')"));
        assertTrue(js.contains("BYD.skeleton.resolve('vcTyres')"));
        // First /api/vehicle/state hop retires the lock cover, including "NO DATA".
        assertTrue(js.contains("BYD.skeleton.resolve('vcLock')"));
        assertTrue(js.contains("if (!data.tyres) BYD.skeleton.resolve('vcTyres')"));
        assertTrue(js.contains("translatedText('vehicle.tyre_no_data'"));
    }

    @Test
    public void seatPositionsCoversTheReportedEmptyHero() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/seat-positions.html");
        assertTrue(html.contains("skeleton.js"));
        assertTrue(html.contains("data-sk-group=\"seatCurrent\""));
        assertTrue(html.contains("data-sk-group=\"seatList\""));
        assertTrue(html.contains("id=\"spCurrentMatch\" data-sk-real=\"seatCurrent\""));
        assertTrue(html.contains("id=\"spList\" data-sk-real=\"seatList\""));

        String js = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");
        assertTrue(js.contains("BYD.skeleton.resolve('seatCurrent')"));
        assertTrue(js.contains("BYD.skeleton.resolve('seatList')"));
        // The 5s poll only updates axes; it must not touch the placeholders.
        assertFalse(js.contains("BYD.skeleton.show("));
    }

    @Test
    public void liveViewCoversTheLocationReadouts() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/live-view.html");
        assertTrue(html.contains("skeleton.js"));
        assertTrue(html.contains("data-sk-group=\"gpsFreshness\""));
        assertTrue(html.contains("data-sk-group=\"gpsDistance\""));
        // Both slots reserve a width and are covered in place, so the chip never resizes.
        assertTrue(html.contains(".location-freshness-label {"));
        assertTrue(html.contains(".location-distance-label {"));
        // The cover spans the whole row and the whole chip, dot and pin included, so no
        // chrome is left framing the placeholder.
        assertTrue(html.contains("class=\"location-freshness is-sk\""));
        assertTrue(html.contains("class=\"location-distance-chip is-sk\""));
        assertTrue(html.replace("\r", "").contains(
                ".location-freshness {\n            position: relative;"));

        String map = readRepositoryFile("app/src/main/assets/web/shared/map.js");
        assertTrue(map.contains("BYD.skeleton.resolve('gpsFreshness')"));
        assertTrue(map.contains("BYD.skeleton.resolve('gpsDistance')"));
        // Freshness reads as a state dot plus a plain label, never coloured text.
        assertTrue(map.contains("_setFreshness(labelEl, text, dotState)"));
        assertTrue(html.contains(".location-freshness-dot.is-down"));
    }

    @Test
    public void projectionStaysSkeletonFree() throws IOException {
        for (String path : PROJECTION_LAYOUTS) {
            String layout = readRepositoryFile(path);
            assertFalse(path, layout.contains("skeleton_block"));
            assertFalse(path, layout.contains("@layout/skeleton_tile"));
        }
        assertFalse(readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt")
                .contains("Skeleton("));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
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
