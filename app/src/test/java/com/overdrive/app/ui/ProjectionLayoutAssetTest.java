package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Pins the native Projection screen to the shared dashboard card chrome. */
public class ProjectionLayoutAssetTest {

    private static final String PORTRAIT = "app/src/main/res/layout/fragment_projection.xml";
    private static final String LAND = "app/src/main/res/layout-land/fragment_projection.xml";

    private static final String[] WIRED_IDS = {
            "projectionAppSpinner", "projectionCastButton", "projectionStopButton",
            "projectionStatus", "projectionStatusDot", "projectionStage", "projectionTexture",
            "projectionBounds", "projectionProgress", "projectionScaleGroup", "projectionScaleFit",
            "projectionScaleFill", "projectionScaleZoom", "projectionPresetGroup",
            "projectionPresetFull", "projectionPresetLeft", "projectionPresetRight",
            "projectionPresetCenter", "projectionAdjustButton", "projectionAspectLockSwitch",
            "projectionAutoStartSwitch",
    };

    /** ProjectionFragment does not branch on orientation, so both files must carry every ID. */
    @Test
    public void bothOrientationsKeepEveryWiredId() throws IOException {
        String portrait = readRepositoryFile(PORTRAIT);
        String land = readRepositoryFile(LAND);
        for (String id : WIRED_IDS) {
            assertTrue(id, portrait.contains("@+id/" + id));
            assertTrue(id, land.contains("@+id/" + id));
        }
    }

    @Test
    public void cardsWearTheDashboardChrome() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            assertTrue(path, xml.contains("com.google.android.material.card.MaterialCardView"));
            assertTrue(path, xml.contains("app:cardCornerRadius=\"@dimen/dashboard_modern_radius\""));
            assertTrue(path, xml.contains("app:cardBackgroundColor=\"?attr/colorSurfaceContainer\""));
            assertTrue(path, xml.contains("app:cardElevation=\"0dp\""));
            assertFalse(path, xml.contains("@drawable/dialog_m3_background"));
        }
    }

    /** The live mirror leads the page; the cast controls follow it. */
    @Test
    public void portraitPutsTheStageAheadOfTheCastControls() throws IOException {
        String xml = readRepositoryFile(PORTRAIT);
        int dot = xml.indexOf("@+id/projectionStatusDot");
        int stage = xml.indexOf("@+id/projectionStage");
        int cast = xml.indexOf("@+id/projectionCastButton");
        assertTrue(dot >= 0 && stage > dot && cast > stage);
    }

    /**
     * Auto-start is a settings row, not a scaling control: it belongs after the
     * scale group, on the far side of the divider.
     */
    @Test
    public void autoStartSitsBelowScalingRatherThanInsideIt() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int group = xml.indexOf("@+id/projectionScaleGroup");
            int groupEnd = xml.indexOf("</com.google.android.material.button.MaterialButtonToggleGroup>",
                    group);
            int autoStart = xml.indexOf("@+id/projectionAutoStartSwitch");
            assertTrue(path, group >= 0 && groupEnd > group && autoStart > groupEnd);
            assertTrue(path, xml.contains("@string/projection_autostart_desc"));
        }
    }

    /** Resize belongs to the cluster-window card, under the presets it shares. */
    @Test
    public void resizeFollowsTheWindowPresets() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int presets = xml.indexOf("@+id/projectionPresetGroup");
            int presetsEnd = xml.indexOf(
                    "</com.google.android.material.button.MaterialButtonToggleGroup>", presets);
            int adjust = xml.indexOf("@+id/projectionAdjustButton");
            assertTrue(path, presets >= 0 && adjust > presetsEnd);
        }
    }

    @Test
    public void actionsUseTheProjectVectorsAndTouchTarget() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            assertFalse(path, xml.contains("@android:drawable/ic_menu_crop"));
            assertTrue(path, xml.contains("app:icon=\"@drawable/ic_crop_free\""));
            assertTrue(path,
                    xml.contains("android:layout_height=\"@dimen/dashboard_modern_touch_target\""));
        }
        assertTrue(readRepositoryFile("app/src/main/res/drawable/ic_crop_free.xml")
                .contains("android:viewportWidth=\"24\""));
    }

    /** The app bar owns the page name — no second title, and no orphan subtitle. */
    @Test
    public void pageDoesNotRepeatItsTitle() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            assertFalse(path, xml.contains("@string/projection_hero_title"));
            assertFalse(path, xml.contains("@string/projection_hero_subtitle"));
        }
    }

    @Test
    public void spinnerUsesTheThemedItemInsteadOfThePlatformOne() throws IOException {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt");
        assertTrue(fragment.contains("R.layout.item_projection_spinner"));
        assertFalse(fragment.contains("android.R.layout.simple_spinner_item"));

        String item = readRepositoryFile("app/src/main/res/layout/item_projection_spinner.xml");
        assertTrue(item.contains("android:textColor=\"?attr/colorOnSurface\""));
        assertTrue(item.contains("android:minHeight=\"@dimen/dashboard_modern_touch_target\""));
    }

    @Test
    public void statusDotFollowsTheMirrorState() throws IOException {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt");
        assertTrue(fragment.contains("statusDot = view.findViewById(R.id.projectionStatusDot)"));
        assertTrue(fragment.contains("statusDot = null"));
        assertTrue(fragment.contains("setStatusDot(R.drawable.status_dot_online)"));
        assertTrue(fragment.contains("setStatusDot(R.drawable.status_dot_offline)"));
        assertTrue(fragment.contains("R.drawable.status_dot_neutral"));

        int render = fragment.indexOf("private fun renderMirrorState(");
        assertTrue(render >= 0);
        assertTrue(fragment.indexOf("setStatusDot(", render) > render);
    }

    /**
     * MaterialCardView ignores android:padding when measuring its child, so a
     * 4dp bezel becomes a left+top frame after updateStageAspect resizes the
     * preview. The stage must fill the card on every side.
     */
    @Test
    public void stageFillsTheCardOnEverySide() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int card = xml.indexOf("com.google.android.material.card.MaterialCardView");
            int stage = xml.indexOf("@+id/projectionStage");
            assertTrue(path, card >= 0 && stage > card);
            String stageCard = xml.substring(card, stage);
            assertFalse(path, stageCard.contains("android:padding="));
            assertTrue(path, stageCard.contains("app:contentPadding=\"0dp\""));
            assertTrue(path, stageCard.contains("app:cardBackgroundColor=\"@android:color/black\""));
        }
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt");
        assertTrue(fragment.contains("clipMirrorToCard()"));
        assertTrue(fragment.contains("clipToOutline = true"));
    }

    /** Cast / mirror failures use the shared toast chip, not the status row. */
    @Test
    public void errorsUseTheSharedToastInsteadOfTheStatusLabel() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            assertTrue(path, xml.contains("layout=\"@layout/app_toast\""));
        }
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt");
        assertTrue(fragment.contains("appToast = AppToast(view)"));
        assertTrue(fragment.contains("notifyToast(getString(R.string.projection_cast_failed)"));
        assertTrue(fragment.contains("notifyStickyError(getString(R.string.projection_status_unsupported))"));
        assertTrue(fragment.contains("notifyStickyError(getString(R.string.projection_status_service_down))"));
        assertFalse(fragment.contains("setStatus(getString(R.string.projection_cast_failed))"));
        assertFalse(fragment.contains("setStatus(getString(R.string.projection_status_unsupported))"));
        assertFalse(fragment.contains("setStatus(getString(R.string.projection_status_service_down))"));

        String stack = readRepositoryFile("app/src/main/res/layout/app_toast.xml");
        assertTrue(stack.contains("@+id/appToastStack"));
        assertTrue(stack.contains("android:orientation=\"vertical\""));

        String chip = readRepositoryFile("app/src/main/res/layout/item_app_toast.xml");
        assertTrue(chip.contains("@drawable/app_toast_background"));
        assertTrue(chip.contains("@+id/appToastDot"));

        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/widget/AppToast.kt");
        assertTrue(helper.contains("stack.addView(chip)"));
        assertFalse(helper.contains("text.text = message"));
    }

    /** Landscape splits the page: the stage column, then the controls column. */
    @Test
    public void landscapeSplitsStageFromControls() throws IOException {
        String land = readRepositoryFile(LAND);
        assertTrue(land.contains("android:orientation=\"horizontal\""));
        assertTrue(land.contains("android:layout_weight=\"1.4\""));
        int stage = land.indexOf("@+id/projectionStage");
        int cast = land.indexOf("@+id/projectionAppSpinner");
        assertTrue(stage >= 0 && cast > stage);
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
