package com.overdrive.app.ui.recording;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the portrait/landscape recording UI contract and player states. */
public class RecordingModernizationContractTest {

    @Test
    public void bothRecordingScreensKeepCoreAndModernActions() throws IOException {
        String portrait = read("app/src/main/res/layout/fragment_recordings.xml");
        String landscape = read("app/src/main/res/layout-land/fragment_recordings.xml");

        for (String id : new String[] {
                "segmentedSource", "segmentDashcam", "segmentReplays",
                "segmentSurveillance", "libraryContainer", "cardDateJump",
                "etPlaceSearch", "btnOpenRecordingFilters", "btnSelectRecordings"
        }) {
            assertTrue("portrait missing " + id, portrait.contains("@+id/" + id));
            assertTrue("landscape missing " + id, landscape.contains("@+id/" + id));
        }
        assertTrue("portrait must host restored inline player safely",
                portrait.contains("@+id/previewContainer"));
        assertTrue("portrait restoration host must remain hidden",
                portrait.contains("android:importantForAccessibility=\"noHideDescendants\""));
        assertTrue(landscape.contains("@+id/previewContainer"));
        assertTrue(landscape.contains("@+id/previewPlaceholder"));
    }

    @Test
    public void inlinePlayerHasPortraitRestoreGuard() throws IOException {
        String recordings = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");
        String player = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/VideoPlayerFragment.kt");

        assertTrue(recordings.contains("closeRestoredInlinePlayerInPortrait"));
        assertTrue(recordings.contains("commitNowAllowingStateLoss"));
        assertTrue(recordings.contains("override fun onConfigurationChanged"));
        assertTrue(player.contains("findViewById<View>(R.id.twoPaneBody) == null"));
        assertTrue(player.contains("if (!playbackInitialized) return"));
    }

    @Test
    public void mediaAndPlayerExposeStableStatesAndLargeActions() throws IOException {
        String portraitItem = read("app/src/main/res/layout/item_recording.xml");
        String landscapeItem = read("app/src/main/res/layout-land/item_recording.xml");
        String player = read("app/src/main/res/layout/fragment_video_player.xml");

        assertTrue(portraitItem.contains("android:layout_width=\"@dimen/rec_thumb_width\""));
        assertTrue(landscapeItem.contains("android:layout_width=\"@dimen/rec_thumb_width\""));
        assertTrue(player.contains("@+id/playerLoadingContainer"));
        assertTrue(player.contains("@+id/playerErrorContainer"));
        assertTrue(player.contains("@+id/btnPlayerRetry"));
        assertTrue(player.contains("@+id/btnPlayerErrorNext"));
        assertTrue(player.contains("android:layout_width=\"56dp\""));
    }

    @Test
    public void selectionAndThumbnailWorkAreLifecycleSafe() throws IOException {
        String library = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingLibraryFragment.kt");
        String adapter = read(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");

        assertTrue(library.contains("override fun onSaveInstanceState"));
        assertTrue(library.contains("STATE_SELECT_MODE"));
        assertTrue(library.contains("STATE_SELECTED_PATHS"));
        assertTrue(library.contains("STATE_SELECT_ALL_PENDING"));
        assertTrue(library.contains("recordingAdapter.restoreSelection("));
        assertTrue(library.contains("selectAllRecordings()"));
        assertTrue(library.contains("RecordingsApiClient.fetchAllRecordings(filter)"));
        assertTrue(library.contains("setSelectionActionsEnabled(false)"));
        assertTrue(library.contains("pendingRestoredSelectionPaths"));
        assertTrue(library.contains("allMatchingSelected"));
        assertTrue(library.contains("recyclerRecordings.adapter = null"));
        assertTrue(library.contains("recordingAdapter.dispose()"));
        assertTrue(adapter.contains("fun restoreSelection("));
        assertTrue(adapter.contains("fun getSelectedPaths()"));
        assertTrue(adapter.contains("fun selectAllLoaded()"));
        assertTrue(adapter.contains("fun retainSelection("));
        assertTrue(adapter.contains("fun dispose()"));
        assertTrue(adapter.contains("thumbnailScope.cancel()"));
        assertTrue(adapter.contains("thumbnailCache.evictAll()"));
    }

    @Test
    public void nativeRecordingCardsCreateTheSameAuthenticatedEvidencePack()
            throws IOException {
        String row = read("app/src/main/res/layout/item_recording.xml");
        String adapter = read(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");
        String library = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingLibraryFragment.kt");
        String client = read(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingsApiClient.kt");
        String providerPaths = read("app/src/main/res/xml/file_paths.xml");

        assertTrue(row.contains("@+id/btnMore"));
        assertTrue(adapter.contains("R.string.action_create_evidence_pack"));
        assertTrue(adapter.contains("onEvidencePack?.invoke(recording)"));
        assertTrue(library.contains("Theme_Overdrive_M3_Dialog"));
        assertTrue(library.contains("RecordingsApiClient.createIncidentPack("));
        assertTrue(library.contains("type = \"application/zip\""));
        assertTrue(client.contains("DaemonHttpClient.open("));
        assertTrue(client.contains("\"/api/genai/incidents\""));
        assertTrue(client.contains("\"/api/genai/incidents/$packId/download\""));
        assertTrue(client.contains("Thread.currentThread().isInterrupted"));
        assertTrue(providerPaths.contains("path=\"incident-packs/\""));
    }

    @Test
    public void optionalMetadataCannotChangeRepeatedTileHeight() throws IOException {
        String portrait = read("app/src/main/res/layout/item_recording.xml");
        String landscape = read("app/src/main/res/layout-land/item_recording.xml");
        String adapter = read(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");

        String styles = read("app/src/main/res/values/revamp_recordings.xml");
        for (String layout : new String[] {portrait, landscape}) {
            assertTrue("rows are a fixed height",
                    layout.contains("android:layout_height=\"@dimen/rec_row_height\""));
            String location = openingTagForId(layout, "tvLocation");
            assertTrue(location.contains("android:visibility=\"invisible\""));
        }
        for (String style : new String[] {"Widget.Overdrive.Rec.RowMeta", "Widget.Overdrive.Rec.Badge"}) {
            int at = styles.indexOf("<style name=\"" + style + "\"");
            assertTrue(style, at >= 0);
            String body = styles.substring(at, styles.indexOf("</style>", at));
            assertTrue(style + " must not increase repeated tile height",
                    body.contains("<item name=\"android:maxLines\">1</item>"));
            assertTrue(style + " must truncate safely",
                    body.contains("<item name=\"android:ellipsize\">end</item>"));
        }
        assertTrue(adapter.contains("tvLocation?.visibility = View.INVISIBLE"));
    }

    @Test
    public void landscapeLibraryRowReservesTheFilenameLine()
            throws IOException {
        String row = read(
                "app/src/main/res/layout/item_recording_landscape.xml");

        assertTrue(row.contains("android:layout_height=\"@dimen/rec_row_height\""));
        assertTrue(openingTagForId(row, "tvFilename")
                .contains("android:visibility=\"gone\""));
    }

    @Test
    public void daemonMediaUrlsStayQualifiedToTheIndexedVolume() throws IOException {
        String index = read(
                "app/src/main/java/com/overdrive/app/server/RecordingsIndex.java");
        String handler = read(
                "app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java");
        String client = read(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingsApiClient.kt");

        assertTrue(index.contains(
                "rec.put(\"videoUrl\", \"/video/id/\" + recordingId);"));
        assertTrue(index.contains(
                "rec.put(\"thumbnailUrl\", \"/thumb/id/\" + recordingId);"));
        assertTrue(index.contains(
                "rec.put(\"eventUrl\", \"/api/events/id/\" + recordingId);"));
        assertTrue(index.contains("String mediaQuery = mediaPathQuery(absPath);"));
        assertTrue(index.contains(
                "rec.put(\"legacyVideoUrl\", \"/video/\" + name + mediaQuery);"));
        assertTrue(index.contains(
                "rec.put(\"legacyThumbnailUrl\", \"/thumb/\" + name + mediaQuery);"));
        assertTrue(handler.contains(
                "serveThumbnail(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains(
                "deleteRecording(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains(
                "serveEventTimeline(out, filename, mediaRequestedPath(path));"));
        assertTrue(handler.contains("isAllowedRecordingPath(requested)"));
        assertTrue(client.contains(
                "absoluteVideoUrl.replaceFirst(\"/video/\", \"/api/events/\")"));
    }

    @Test
    public void asyncTimelineAndThumbnailResultsCannotRebindStaleViews()
            throws IOException {
        String player = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/VideoPlayerFragment.kt");
        String adapter = read(
                "app/src/main/java/com/overdrive/app/ui/adapter/RecordingAdapter.kt");

        assertTrue(player.contains("val generation = ++timelineGeneration"));
        assertTrue(player.contains("generation != timelineGeneration"));
        assertTrue(player.contains("currentPath != videoPath"));
        assertTrue(player.contains("timelineExecutor?.shutdownNow()"));
        assertTrue(adapter.contains(
                "override fun onViewRecycled(holder: RecordingViewHolder)"));
        assertTrue(adapter.contains("holder.recycle()"));
        assertTrue(adapter.contains("thumbnailJob?.cancel()"));
        assertTrue(adapter.contains(
                "bindingAdapterPosition != RecyclerView.NO_POSITION"));
        assertTrue(adapter.contains(
                "getItem(bindingAdapterPosition).path == recording.path"));
        assertTrue(adapter.contains("inSampleSize = sampleSize("));
    }

    @Test
    public void recordingControlsAndFullscreenBackKeepTheirContracts()
            throws IOException {
        String portrait = read("app/src/main/res/layout/fragment_recordings.xml");
        String landscape = read("app/src/main/res/layout-land/fragment_recordings.xml");
        String host = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");

        for (String layout : new String[] {portrait, landscape}) {
            for (String id : new String[] {
                    "btnSelectRecordings", "segmentDashcam", "segmentReplays",
                    "segmentSurveillance", "btnPrevDay", "btnNextDay",
                    "btnOpenRecordingFilters"
            }) {
                assertTrue(id + " must retain a 48dp target",
                        openingTagForId(layout, id)
                                .contains("android:layout_height=\"48dp\""));
            }
        }
        assertTrue(host.contains("OnBackPressedCallback(false)"));
        assertTrue(host.contains(
                "onBackPressedDispatcher.addCallback("));
        assertTrue(host.contains(
                "fullscreenBackCallback.isEnabled = playerFullscreen"));
        assertTrue(host.contains(
                "player.onFullscreenToggle = { wantFullscreen ->"));
    }

    @Test
    public void inlinePlayerKeepsUsableSpaceAndAutoHidingControls()
            throws IOException {
        String landscape = read("app/src/main/res/layout-land/fragment_recordings.xml");
        String player = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/VideoPlayerFragment.kt");
        String host = read(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");

        assertTrue(openingTagForId(landscape, "libraryContainer")
                .contains("android:layout_weight=\"10\""));
        assertTrue(openingTagForId(landscape, "previewCard")
                .contains("android:layout_weight=\"11\""));
        assertTrue(openingTagForId(landscape, "previewInsightsToggle")
                .contains("android:layout_height=\"48dp\""));
        assertTrue(openingTagForId(landscape, "previewInsights")
                .contains("android:visibility=\"gone\""));
        assertTrue(host.contains("setPreviewDetailsExpanded(root, false)"));
        assertTrue(host.contains("setPreviewDetailsExpanded(root, !expanded)"));
        assertTrue(host.contains(
                "previewInsightsToggle?.visibility = if (fullscreen) View.GONE else View.VISIBLE"));
        assertTrue(host.contains("params.weight = if (fullscreen) 0f else LIBRARY_PANE_WEIGHT"));
        assertTrue(host.contains("LIBRARY_PANE_WEIGHT = 10f"));
        int chrome = player.indexOf("val chrome = listOfNotNull(");
        assertTrue(chrome >= 0);
        String chromeList = player.substring(chrome, player.indexOf(")", chrome));
        assertTrue(chromeList.contains("topBar"));
        assertTrue(chromeList.contains("quadrantBar"));
        assertTrue(chromeList.contains("bottomControls"));
        assertTrue(player.contains(
                "handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)"));
    }

    private static String openingTagForId(String xml, String id) {
        String marker = "android:id=\"@+id/" + id + "\"";
        int idIndex = xml.indexOf(marker);
        if (idIndex < 0) {
            throw new AssertionError("Missing view id: " + id);
        }
        int start = xml.lastIndexOf('<', idIndex);
        int end = xml.indexOf('>', idIndex);
        if (start < 0 || end < 0) {
            throw new AssertionError("Malformed opening tag for: " + id);
        }
        return xml.substring(start, end + 1);
    }

    private static String read(String relativePath) throws IOException {
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
