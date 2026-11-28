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

    private static final String FRAGMENT =
            "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt";
    private static final String TILES =
            "app/src/main/java/com/overdrive/app/ui/view/ProjectionAppTiles.kt";
    private static final String ACTIONS = "app/src/main/res/layout/dialog_projection_app_actions.xml";

    private static final String[] WIRED_IDS = {
            "projectionPickerHero", "projectionAppPicker", "projectionFavoriteGrid",
            "projectionAppGrid", "projectionAppStrip", "projectionNowPlaying",
            "projectionClusterKeys", "projectionKeyBack", "projectionKeyHome",
            "projectionKeyVolumeDown", "projectionKeyVolumeUp", "projectionSettingsButton",
            "projectionDockSettingsButton", "projectionSettingsPanel", "projectionSettingsScrim",
            "projectionAdvancedContent", "projectionTransportSettings", "projectionStopButton",
            "projectionStatus", "projectionStatusDot", "projectionStage", "projectionTexture",
            "projectionBounds", "projectionProgress", "projectionScaleGroup", "projectionScaleFit",
            "projectionScaleFill", "projectionScaleZoom",
            "projectionAdjustButton", "projectionAspectLockSwitch",
            "projectionAutoStartSwitch", "projectionTransportGroup", "projectionTransportAuto",
            "projectionTransportLegacy", "projectionTransportDilink5",
            "projectionDiLink5Controls", "projectionPathGroup", "projectionPathDescription",
            "projectionPathFactoryMini", "projectionAaosMapCheck",
            "projectionStandardSettings", "projectionEditableWindowCard",
            "projectionTransportStatus", "projectionFullscreenButton", "projectionScroll",
            "projectionContent", "projectionMirrorCard", "projectionDock", "projectionSettings",
            "projectionHud", "projectionEmptyState",
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
    public void bothOrientationsHideTheSealion7OptInUntilSupportIsKnown() throws IOException {
        String resource = "projection_sealion7_hdmi_setting";
        String row = readRepositoryFile("app/src/main/res/layout/" + resource + ".xml");
        assertTrue(row.contains("@+id/projectionSealion7HdmiSwitch"));
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int setting = xml.indexOf("@+id/projectionSealion7HdmiRow");
            assertTrue(path, setting >= 0);
            assertTrue(path, xml.substring(setting, xml.indexOf('>', setting))
                    .contains("android:visibility=\"gone\""));
            assertTrue(path, xml.contains("@layout/" + resource));
        }
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt");
        assertTrue(fragment.contains("sealion7HdmiSupported = st.sealion7HdmiSupported"));
        assertTrue(fragment.contains("if (sealion7HdmiSupported && !theater) View.VISIBLE else View.GONE"));
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

    /** The live mirror sits over its status pill; the dock with Stop follows it. */
    @Test
    public void stageLeadsTheLiveDock() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int stage = xml.indexOf("@+id/projectionStage\"");
            int dot = xml.indexOf("@+id/projectionStatusDot");
            int stop = xml.indexOf("@+id/projectionStopButton");
            assertTrue(path, stage >= 0 && dot > stage && stop > dot);
        }
    }

    /**
     * Idle, the app tiles lead the page; the mirror and its dock start hidden and only show once
     * an app is projected (renderLiveState swaps them).
     */
    @Test
    public void pickerLeadsWhileIdleAndTheMirrorWaitsForACast() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int hero = xml.indexOf("@+id/projectionPickerHero");
            int mirror = xml.indexOf("@+id/projectionMirrorCard");
            assertTrue(path, hero >= 0 && mirror > hero);
            assertTrue(path, openingTag(xml, "projectionMirrorCard").contains("android:visibility=\"gone\""));
            assertTrue(path, openingTag(xml, "projectionDock").contains("android:visibility=\"gone\""));
            assertFalse(path, xml.contains("<Spinner"));
        }
        String fragment = readRepositoryFile(FRAGMENT);
        int render = fragment.indexOf("private fun renderLiveState(");
        assertTrue(render >= 0);
        assertTrue(fragment.indexOf("setVisible(stageCard, live)", render) > render);
        assertTrue(fragment.indexOf("setVisible(appPicker, !live)", render) > render);
    }

    /** Tap casts, long-press opens the app's own menu; both gestures are named for TalkBack. */
    @Test
    public void tilesCastOnTapAndOpenTheAppMenuOnLongPress() throws IOException {
        String tiles = readRepositoryFile(TILES);
        assertTrue(tiles.contains("view.setOnClickListener { onTap(item.pkg) }"));
        assertTrue(tiles.contains("view.setOnLongClickListener { onLongPress(item.pkg); true }"));
        assertTrue(tiles.contains("ACTION_LONG_CLICK"));

        String fragment = readRepositoryFile(FRAGMENT);
        assertTrue(fragment.contains("ProjectionAppTileAdapter(false, ::onAppTapped, ::showAppActions)"));
        assertTrue(fragment.contains("ProjectionAppTileAdapter(true, ::onAppTapped, ::showAppActions)"));
        assertTrue(fragment.contains("ProjectionAppTools.open(this) { pkg }"));

        String actions = readRepositoryFile(ACTIONS);
        for (String id : new String[] {"projectionActionsPresets", "projectionActionsPresetFull",
                "projectionActionsPresetLeft", "projectionActionsPresetRight",
                "projectionActionsPresetCenter", "projectionActionsResize", "projectionActionsTools",
                "projectionActionsFavorite", "projectionActionsPrimary"}) {
            assertTrue(id, actions.contains("@+id/" + id));
        }
    }

    /** Back, Home and Volume are dock buttons now, not entries of a ⋮ dialog. */
    @Test
    public void clusterKeysAreVisibleDockButtons() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int dock = xml.indexOf("@+id/projectionDock");
            int keys = xml.indexOf("@+id/projectionClusterKeys");
            int panel = xml.indexOf("@+id/projectionSettingsPanel");
            assertTrue(path, dock >= 0 && keys > dock && panel > keys);
        }
        String fragment = readRepositoryFile(FRAGMENT);
        assertTrue(fragment.contains("R.id.projectionKeyBack to KeyEvent.KEYCODE_BACK"));
        assertFalse(fragment.contains(".setItems(labels)"));
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

    /**
     * The cluster-transport selector is a settings row like auto-start: it
     * belongs after the auto-start switch, behind its own divider, with the
     * resolved-transport status line closing the card.
     */
    @Test
    public void transportSelectorSitsBelowAutoStart() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int autoStart = xml.indexOf("@+id/projectionAutoStartSwitch");
            int group = xml.indexOf("@+id/projectionTransportGroup");
            int status = xml.indexOf("@+id/projectionTransportStatus");
            assertTrue(path, autoStart >= 0 && group > autoStart && status > group);
            assertTrue(path, xml.contains("@string/projection_transport_desc"));
        }
    }

    /** Resize lives in the dock under the stage it edits, in both orientations. */
    @Test
    public void resizeSitsWhereTheStageStaysInView() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int stage = xml.indexOf("@+id/projectionStage\"");
            int dock = xml.indexOf("@+id/projectionDock");
            int adjust = xml.indexOf("@+id/projectionAdjustButton");
            int settings = xml.indexOf("@+id/projectionSettingsPanel");
            assertTrue(path, stage >= 0 && dock > stage && adjust > dock && settings > adjust);
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

    /** Tiles use the themed surfaces and stay at least a touch target in size. */
    @Test
    public void tilesUseTheThemedItems() throws IOException {
        String tiles = readRepositoryFile(TILES);
        assertTrue(tiles.contains("R.layout.item_projection_app_tile"));
        assertTrue(tiles.contains("R.layout.item_projection_app_chip"));
        assertTrue(tiles.contains("colorSecondaryContainer"));

        String tile = readRepositoryFile("app/src/main/res/layout/item_projection_app_tile.xml");
        assertTrue(tile.contains("style=\"@style/Widget.Overdrive.Revamp.Tile\""));
        assertTrue(tile.contains("@style/TextAppearance.Overdrive.Settings.RowTitle"));
        assertTrue(tile.contains("android:layout_height=\"112dp\""));
        String chip = readRepositoryFile("app/src/main/res/layout/item_projection_app_chip.xml");
        assertTrue(chip.contains("style=\"@style/Widget.Overdrive.Revamp.Tile\""));
        assertTrue(chip.contains("android:layout_width=\"@dimen/od_touch_primary\""));
        assertTrue(chip.contains("android:layout_height=\"@dimen/od_touch_primary\""));
    }

    @Test
    public void statusDotFollowsTheMirrorState() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);
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
            int stage = xml.indexOf("@+id/projectionStage");
            // The card wrapping the stage (the idle picker card comes first on the page).
            int card = xml.lastIndexOf("com.google.android.material.card.MaterialCardView", stage);
            assertTrue(path, card >= 0 && stage > card);
            String stageCard = xml.substring(card, stage);
            assertFalse(path, stageCard.contains("android:padding="));
            assertTrue(path, stageCard.contains("app:contentPadding=\"0dp\""));
            assertTrue(path, stageCard.contains("app:cardBackgroundColor=\"@android:color/black\""));
        }
        String fragment = readRepositoryFile(FRAGMENT);
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
        String fragment = readRepositoryFile(FRAGMENT);
        assertTrue(fragment.contains("appToast = AppToast(view)"));
        assertTrue(fragment.contains("result.error.ifEmpty"));
        assertTrue(fragment.contains("getString(R.string.projection_cast_failed)"));
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

    /**
     * Landscape stacks the full-width stage over the dock and its app strip; settings shared by
     * every app slide in from the end edge instead of filling the page under the mirror.
     */
    @Test
    public void landscapeStacksTheStageOverTheDock() throws IOException {
        String land = readRepositoryFile(LAND);
        int stage = land.indexOf("@+id/projectionStage\"");
        int strip = land.indexOf("@+id/projectionAppStrip");
        int panel = land.indexOf("@+id/projectionSettingsPanel");
        assertTrue(stage >= 0 && strip > stage && panel > strip);
        String panelTag = openingTag(land, "projectionSettingsPanel");
        assertTrue(panelTag.contains("android:layout_gravity=\"end\""));
        assertTrue(panelTag.contains("android:visibility=\"gone\""));
    }

    /** Stop pressed (or the cast ending) in full screen must not strand the user there. */
    @Test
    public void fullScreenEndsWhenNothingIsLive() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);
        int render = fragment.indexOf("private fun renderLiveState(");
        assertTrue(render >= 0);
        assertTrue(fragment.indexOf("if (!live && theater) setTheater(false)", render) > render);
    }

    /** On DiLink 5 a switch restarts the cast: the strip sits behind a divider and its own label. */
    @Test
    public void appStripStaysApartFromTheKeyRow() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int keys = xml.indexOf("@+id/projectionClusterKeys");
            int label = xml.indexOf("@+id/projectionAppStripLabel");
            int strip = xml.indexOf("@+id/projectionAppStrip\"");
            assertTrue(path, keys >= 0 && label > keys && strip > label);
            String between = xml.substring(keys, label);
            assertTrue(path, between.contains("android:background=\"?attr/colorOutlineVariant\""));
            assertTrue(path, xml.contains("@string/projection_switch_app"));
        }
    }

    /** A preset for the app the resize frame belongs to goes through the frame's commit path. */
    @Test
    public void presetForTheFramesAppGoesThroughTheFrame() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);
        int apply = fragment.indexOf("private fun applyPresetFor(");
        assertTrue(apply >= 0);
        int frame = fragment.indexOf("(!casting && boxPackage == pkg)", apply);
        int prefs = fragment.indexOf("persistWindowPrefsOnly(pkg", apply);
        assertTrue(frame > apply && prefs > frame);
    }

    /** Dock buttons, Stop included, use the 56dp standard touch target and the shared icons. */
    @Test
    public void dockUsesTheSharedTouchTargetAndIcons() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int dock = xml.indexOf("@+id/projectionDock");
            String dockXml = xml.substring(dock,
                    xml.indexOf("</com.google.android.material.card.MaterialCardView>", dock));
            java.util.regex.Matcher button = java.util.regex.Pattern
                    .compile("<com\\.google\\.android\\.material\\.button\\.MaterialButton[^>]*>")
                    .matcher(dockXml);
            int buttons = 0;
            while (button.find()) {
                buttons++;
                assertTrue(path + " " + button.group(),
                        button.group().contains("android:layout_height=\"@dimen/od_touch_standard\""));
            }
            assertTrue(path, buttons >= 7);
            assertTrue(path, xml.contains("app:icon=\"@drawable/ic_saved_home\""));
            assertTrue(path, xml.contains("app:icon=\"@drawable/ic_volume_on\""));
            assertTrue(path, xml.contains("app:icon=\"@drawable/ic_clear\""));
            assertTrue(path, xml.contains("android:background=\"@color/projection_settings_scrim\""));
        }
        assertTrue(readRepositoryFile(FRAGMENT)
                .contains("if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star"));
    }

    /** Tiles and mirror swap places from the top; tile taps wait a beat after a cast ends. */
    @Test
    public void pageSwitchResetsScrollAndGuardsTileTaps() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);
        int render = fragment.indexOf("private fun renderLiveState(");
        assertTrue(fragment.indexOf("scroll?.scrollTo(0, 0)", render) > render);
        int tap = fragment.indexOf("private fun onAppTapped(");
        assertTrue(fragment.indexOf("TILE_TAP_GUARD_MS", tap) > tap);
        assertTrue(fragment.contains(
                "val canEdit = !aaosHostedMap && !layoutActive && !layoutBusy && !castInFlight"));
    }

    /** Projection tools keep an always-visible entry next to Layouts. */
    @Test
    public void projectionToolsKeepAnEntryNextToLayouts() throws IOException {
        for (String path : new String[] {PORTRAIT, LAND}) {
            String xml = readRepositoryFile(path);
            int tools = xml.indexOf("@+id/projectionToolsRow");
            int layouts = xml.indexOf("@+id/projectionLayoutsRow");
            assertTrue(path, tools >= 0 && layouts > tools);
        }
        assertTrue(readRepositoryFile(FRAGMENT).contains("R.id.projectionToolsRow"));
    }

    /** The opening tag (attributes) of the view carrying {@code id}. */
    private static String openingTag(String xml, String id) {
        int at = xml.indexOf("@+id/" + id + "\"");
        int start = xml.lastIndexOf("<", at);
        int end = xml.indexOf(">", at);
        return xml.substring(start, end);
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
