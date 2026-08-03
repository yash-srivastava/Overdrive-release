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
        assertTrue(row.contains("android:layout_height=\"132dp\""));
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
