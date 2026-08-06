package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the parked-vehicle Recordings hierarchy and action safety. */
public class RecordingsLandscapeAssetTest {

    @Test
    public void landscapeUsesEventFirstRowsAndCollapsedFilters() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_recordings.xml");
        String row = readRepositoryFile(
                "app/src/main/res/layout/item_recording_landscape.xml");

        assertTrue(page.contains("@string/recordings_title_in_car"));
        assertTrue(page.contains("android:id=\"@+id/btnFilterToggle\""));
        assertTrue(page.contains("android:id=\"@+id/rowPrimaryFilters\""));
        assertTrue(row.contains("android:id=\"@+id/tvEventTitle\""));
        assertTrue(row.contains("android:id=\"@+id/btnMore\""));
        assertTrue(row.contains("android:layout_height=\"112dp\""));
    }

    @Test
    public void landscapeHasNoDownloadOrProminentDestructiveAction() throws IOException {
        String row = readRepositoryFile(
                "app/src/main/res/layout/item_recording_landscape.xml");
        String adapter = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");

        assertFalse(row.contains("@+id/btnDownload"));
        assertFalse(row.contains("@string/action_download"));
        assertFalse(row.contains("@+id/btnDelete"));
        assertFalse(row.contains("@+id/btnShare"));
        assertTrue(adapter.contains("PopupMenu(anchor.context, anchor)"));
    }

    @Test
    public void inCarControlsMeetTheMinimumTouchTarget() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_recordings.xml");
        String row = readRepositoryFile(
                "app/src/main/res/layout/item_recording_landscape.xml");

        assertTrue(page.contains("android:id=\"@+id/btnFilterToggle\""));
        assertTrue(page.contains("android:layout_height=\"48dp\""));
        assertTrue(row.contains("android:layout_width=\"56dp\""));
    }

    @Test
    public void selectedRecordingPaneKeepsPlayerAndContextTogether() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_recordings.xml");
        String metadata = readRepositoryFile(
                "app/src/main/res/layout-land/recording_preview_metadata.xml");
        String host = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");

        assertTrue(page.contains("android:id=\"@+id/previewContent\""));
        assertTrue(page.contains("android:id=\"@+id/previewContainer\""));
        assertTrue(page.contains("layout=\"@layout/recording_preview_metadata\""));
        assertTrue(metadata.contains("android:id=\"@+id/tvPreviewDetectedValue\""));
        assertTrue(metadata.contains("android:id=\"@+id/tvPreviewDistanceValue\""));
        assertTrue(metadata.contains("android:id=\"@+id/previewDetails\""));
        assertTrue(metadata.contains("@drawable/ic_recording_person"));
        assertTrue(metadata.contains("@drawable/ic_recording_storage"));
        assertTrue(metadata.contains("@drawable/ic_recording_file"));
        assertTrue(host.contains("putBoolean(VideoPlayerFragment.ARG_COMPACT_INLINE, true)"));
        assertTrue(host.contains("showInlinePreview(first, startPaused = true)"));
    }

    @Test
    public void dateAndFilterControlsKeepPreviewLikeProportions() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_recordings.xml");

        assertTrue(page.contains("android:id=\"@+id/recordingsToolbarSpacer\""));
        int dateCard = page.indexOf("android:id=\"@+id/cardDateJump\"");
        assertTrue(dateCard >= 0);
        String dateCardBlock = page.substring(
                dateCard, Math.min(dateCard + 320, page.length()));
        assertTrue(dateCardBlock.contains("android:layout_width=\"150dp\""));
        assertFalse(dateCardBlock.contains("android:layout_weight"));
        assertTrue(page.contains("android:id=\"@+id/segmentAll\""));
        assertTrue(page.contains("android:layout_weight=\"12\""));
        assertTrue(page.contains("android:layout_weight=\"8\""));
        assertTrue(page.contains("android:visibility=\"gone\""));
    }

    @Test
    public void searchAndSettingsRemainInTheVisibleLandscapeToolbar() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_recordings.xml");

        int toolbarSpacer = page.indexOf("android:id=\"@+id/recordingsToolbarSpacer\"");
        int search = page.indexOf("android:id=\"@+id/cardPlaceSearch\"");
        int date = page.indexOf("android:id=\"@+id/cardDateJump\"");
        int filters = page.indexOf("android:id=\"@+id/btnFilterToggle\"");
        int settings = page.indexOf("android:id=\"@+id/btnRecordingsSettings\"");

        assertTrue(toolbarSpacer >= 0);
        assertTrue(toolbarSpacer < search);
        assertTrue(search < date);
        assertTrue(date < filters);
        assertTrue(filters < settings);
    }

    @Test
    public void stickySectionHeaderUsesThePageBackground() throws IOException {
        String decoration = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingSectionHeaderDecoration.kt");

        assertTrue(decoration.contains("android.R.attr.colorBackground"));
        assertTrue(decoration.contains("stickyBackgroundPaint"));
        assertFalse(decoration.contains("c.drawRect(left, top, right, bottom, backgroundPaint)"));
    }

    @Test
    public void preparedDurationFlowsBackToPreviewAndList() throws IOException {
        String host = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");
        String player = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/VideoPlayerFragment.kt");
        String library = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingLibraryFragment.kt");

        assertTrue(player.contains("onDurationResolved?.invoke(path, duration.toLong())"));
        assertTrue(host.contains("onDurationResolved = ::onInlinePlayerDurationResolved"));
        assertTrue(host.contains("resolved.formattedDuration"));
        assertTrue(library.contains("recording.copy(durationMs = durationMs)"));
    }

    @Test
    public void inlinePreviewBuildsTitlesWithTheAttachedHostContext() throws IOException {
        String host = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");

        assertTrue(host.contains("val hostContext = context ?: return"));
        assertTrue(host.contains("RecordingUiText.headline(hostContext, recording)"));
        assertFalse(host.contains("RecordingUiText.headline(requireContext(), recording)"));
        assertTrue(host.contains("initialPreviewRunnable?.let { mainHandler.removeCallbacks(it) }"));
        assertTrue(host.contains("onPlayRecording = null"));
    }

    @Test
    public void selectingARowDoesNotRequireLegacyThemeColorAccent() throws IOException {
        String row = readRepositoryFile(
                "app/src/main/res/layout/item_recording_landscape.xml");
        String stroke = readRepositoryFile(
                "app/src/main/res/color/recording_card_stroke.xml");
        String adapter = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");

        assertTrue(row.contains("app:strokeColor=\"@color/recording_card_stroke\""));
        assertTrue(stroke.contains("android:state_selected=\"true\""));
        assertTrue(stroke.contains("?attr/colorPrimary"));
        assertFalse(adapter.contains("android.R.attr.colorAccent"));
        assertFalse(adapter.contains("MaterialColors.getColor(recordingCard"));
        assertTrue(adapter.contains("recordingCard.isSelected = active"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
