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
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM recordings")) {
            try (java.sql.ResultSet result = count.executeQuery()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
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