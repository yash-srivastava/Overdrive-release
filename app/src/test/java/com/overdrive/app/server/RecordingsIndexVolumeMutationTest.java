package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

public class RecordingsIndexVolumeMutationTest {
    private Connection connection;

    @After
    public void closeConnection() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    public void duplicateFilenamesCanBeQueriedAndDeletedByPath() throws Exception {
        RecordingsIndex index = newIndex("mutations");
        Path firstDir = Files.createTempDirectory("recordings-volume-a");
        Path secondDir = Files.createTempDirectory("recordings-volume-b");
        File first = write(firstDir.resolve("cam_20260810_120000.mp4"));
        File second = write(secondDir.resolve("cam_20260810_120000.mp4"));

        assertTrue(index.upsert(first));
        assertTrue(index.upsert(second));
        assertEquals(2, index.queryCount(new RecordingsIndex.Filter()));

        List<JSONObject> rows = index.queryRecordings(new RecordingsIndex.Filter(), 10, 0);
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).getString("filename"), rows.get(1).getString("filename"));
        assertNotEquals(rows.get(0).getString("id"), rows.get(1).getString("id"));
        assertTrue(rows.get(0).getString("videoUrl").startsWith("/video/id/"));
        assertTrue(rows.get(0).getString("thumbnailUrl").startsWith("/thumb/id/"));
        assertTrue(rows.get(0).getString("deleteUrl").startsWith("/api/recordings/id/"));

        String firstId = RecordingIdentity.fromFile(first).recordingId;
        RecordingsIndex.RecordingRef exact = index.resolveById(firstId);
        assertEquals(first.getAbsolutePath(), exact.absolutePath);

        assertTrue(index.removeByPath(first.getAbsolutePath()));
        assertEquals(1, index.queryCount(new RecordingsIndex.Filter()));
        assertFalse(index.containsPath(first.getAbsolutePath()));
        assertTrue(index.containsPath(second.getAbsolutePath()));
    }

    @Test
    public void offlineRowsArePreservedButHiddenFromQueriesAndStats() throws Exception {
        RecordingsIndex index = newIndex("offline");
        Path firstDir = Files.createTempDirectory("recordings-online");
        Path secondDir = Files.createTempDirectory("recordings-offline");
        File online = write(firstDir.resolve("event_20260810_120000.mp4"));
        File offline = write(secondDir.resolve("event_20260810_120001.mp4"));
        assertTrue(index.upsert(online));
        assertTrue(index.upsert(offline));

        String offlineId = RecordingIdentity.fromFile(offline).recordingId;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE recordings SET is_available=FALSE WHERE recording_id=?")) {
            update.setString(1, offlineId);
            update.executeUpdate();
        }

        assertEquals(1, index.queryCount(new RecordingsIndex.Filter()));
        assertEquals(1, index.queryRecordings(new RecordingsIndex.Filter(), 10, 0).size());
        RecordingsIndex.Stats stats = index.queryStats();
        assertEquals(1L, stats.sentryCount);
        assertEquals(1, index.countRowsUnderRoots(Arrays.asList(
                firstDir.toString(), secondDir.toString())));
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM recordings")) {
            try (java.sql.ResultSet result = count.executeQuery()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }

    @Test
    public void watchedStateIsSharedButDoesNotFollowAReplacement() throws Exception {
        RecordingsIndex index = newIndex("watched");
        Path dir = Files.createTempDirectory("recordings-watched");
        File file = write(dir.resolve("event_20260810_120000.mp4"));
        assertTrue(index.upsert(file));
        RecordingsIndex.Filter filter = new RecordingsIndex.Filter();
        JSONObject before = index.queryRecordings(filter, 10, 0).get(0);
        String id = before.getString("id");
        long size = before.getLong("size");
        long mtime = before.getLong("mp4Mtime");
        assertFalse(before.getBoolean("watched"));
        assertTrue(index.markWatched(id, size, mtime));
        assertTrue(index.queryRecordings(filter, 10, 0).get(0).getBoolean("watched"));

        // Same path/id but a different physical clip must start unwatched.
        Files.write(file.toPath(), "replacement video".getBytes(StandardCharsets.UTF_8));
        assertTrue(index.upsert(file));
        JSONObject replacement = index.queryRecordings(filter, 10, 0).get(0);
        assertEquals(id, replacement.getString("id"));
        assertFalse(replacement.getBoolean("watched"));
        assertFalse(index.markWatched(id, size, mtime));
        assertTrue(index.markWatched(id, replacement.getLong("size"),
                replacement.getLong("mp4Mtime")));
        assertTrue(index.queryRecordings(filter, 10, 0).get(0).getBoolean("watched"));
    }

    @Test
    public void playlistSeeksAcrossSkippedRowsAndResumesFromUnwatchedEdge() throws Exception {
        RecordingsIndex index = newIndex("playlist");
        Path dir = Files.createTempDirectory("recordings-playlist");
        for (String name : Arrays.asList("event_20260810_120000.mp4", "dvr_p_20260810_120001.mp4",
                "cam_20260810_120002.mp4", "event_20260810_120003.mp4",
                "proximity_20260810_120004.mp4", "event_20260810_120005.mp4")) {
            assertTrue(index.upsert(write(dir.resolve(name))));
        }
        RecordingsIndex.Filter filter = new RecordingsIndex.Filter();
        List<JSONObject> rows = index.queryRecordings(filter, 10, 0);
        JSONObject oldest = rows.get(5);
        JSONObject newest = rows.get(0);
        assertTrue(index.markWatched(oldest.getString("id"), oldest.getLong("size"),
                oldest.getLong("mp4Mtime")));
        Set<String> skipped = new HashSet<>(Arrays.asList("dvr_p", "cam_"));
        RecordingsIndex.AdjacentResult resume = index.queryPlaylistRecording(filter, null,
                -1, true, false, skipped);
        assertEquals(RecordingsIndex.AdjacentResult.Status.FOUND, resume.status);
        assertEquals(rows.get(2).getString("id"), resume.recording.getString("id"));
        assertEquals(2, index.positionOfRecording(filter, resume.recording));
        assertEquals(newest.getString("id"), index.queryPlaylistRecording(filter, null,
                1, true, false, skipped).recording.getString("id"));
        assertEquals(rows.get(1).getString("id"), index.queryPlaylistRecording(filter,
                resume.recording.getString("id"), -1, false, true, skipped).recording.getString("id"));
        assertEquals(RecordingsIndex.AdjacentResult.Status.END, index.queryPlaylistRecording(filter,
                resume.recording.getString("id"), 1, false, true, skipped).status);
        // The anchor need not itself match the playlist. Manual navigation is unfiltered.
        assertEquals(rows.get(4).getString("id"), index.queryAdjacentRecording(filter,
                oldest.getString("id"), -1).recording.getString("id"));
        skipped.add("event_");
        assertEquals(rows.get(1).getString("id"), index.queryPlaylistRecording(filter, null,
                -1, true, false, skipped).recording.getString("id"));
        filter.type = "sentry";
        assertEquals(RecordingsIndex.AdjacentResult.Status.END, index.queryPlaylistRecording(filter,
                null, -1, true, false, skipped).status);
        // Same ID with a replaced file is once again eligible for resume.
        File replacement = dir.resolve(oldest.getString("filename")).toFile();
        Files.write(replacement.toPath(), "replacement video".getBytes(StandardCharsets.UTF_8));
        assertTrue(index.upsert(replacement));
        assertEquals(oldest.getString("id"), index.queryPlaylistRecording(filter, null,
                -1, true, false, Collections.emptySet()).recording.getString("id"));
    }

    @Test
    public void playlistSkipsPoweredOnAndParkedDvrIndependently() throws Exception {
        RecordingsIndex index = newIndex("playlist-dvr");
        Path dir = Files.createTempDirectory("recordings-playlist-dvr");
        for (String name : Arrays.asList("dvr_20260810_120000.mp4",
                "dvr_p_20260810_120001.mp4", "event_20260810_120002.mp4")) {
            assertTrue(index.upsert(write(dir.resolve(name))));
        }
        RecordingsIndex.Filter filter = new RecordingsIndex.Filter();
        List<JSONObject> rows = index.queryRecordings(filter, 10, 0);
        String poweredOn = rows.get(2).getString("id");
        String parked = rows.get(1).getString("id");
        JSONObject event = rows.get(0);
        String newest = event.getString("id");
        assertTrue(index.markWatched(newest, event.getLong("size"), event.getLong("mp4Mtime")));

        Set<String> skipOn = Collections.singleton("dvr_");
        Set<String> skipParked = Collections.singleton("dvr_p");
        Set<String> skipBoth = new HashSet<>(Arrays.asList("dvr_", "dvr_p"));
        assertEquals(poweredOn, index.queryPlaylistRecording(filter, null,
                -1, true, false, Collections.emptySet()).recording.getString("id"));
        for (int step : new int[] {-1, 1}) {
            assertEquals(parked, index.queryPlaylistRecording(filter, null,
                    step, true, false, skipOn).recording.getString("id"));
            assertEquals(poweredOn, index.queryPlaylistRecording(filter, null,
                    step, true, false, skipParked).recording.getString("id"));
            assertEquals(RecordingsIndex.AdjacentResult.Status.END, index.queryPlaylistRecording(
                    filter, null, step, true, false, skipBoth).status);
        }
        assertEquals(parked, index.queryPlaylistRecording(filter, newest,
                1, false, false, skipOn).recording.getString("id"));
        assertEquals(poweredOn, index.queryPlaylistRecording(filter, newest,
                1, false, false, skipParked).recording.getString("id"));
        assertEquals(parked, index.queryPlaylistRecording(filter, poweredOn,
                -1, false, false, skipOn).recording.getString("id"));
        assertEquals(RecordingsIndex.AdjacentResult.Status.END, index.queryPlaylistRecording(
                filter, newest, 1, false, false, skipBoth).status);
        assertEquals(poweredOn, index.queryAdjacentRecording(filter, parked, 1)
                .recording.getString("id"));
        assertEquals(3, index.queryCount(filter));
    }

    private RecordingsIndex newIndex(String name) throws Exception {
        Class.forName("org.h2.Driver");
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:volume_" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        RecordingsIndexSchema.ensure(connection);

        Constructor<RecordingsIndex> constructor = RecordingsIndex.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RecordingsIndex index = constructor.newInstance();
        setField(index, "connection", connection);
        setField(index, "initialized", true);
        setField(index, "everInitialized", true);
        return index;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = RecordingsIndex.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static File write(Path path) throws Exception {
        Files.write(path, "video".getBytes(StandardCharsets.UTF_8));
        return path.toFile();
    }
}
