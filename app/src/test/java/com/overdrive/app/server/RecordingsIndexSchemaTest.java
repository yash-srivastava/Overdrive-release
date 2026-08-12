package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class RecordingsIndexSchemaTest {

    @Test
    public void freshSchemaUsesVolumeAwarePrimaryKey() throws Exception {
        try (Connection connection = open("fresh")) {
            RecordingsIndexSchema.ensure(connection);

            assertTrue(hasColumn(connection, "RECORDING_ID"));
            assertTrue(hasColumn(connection, "VOLUME_ID"));
            assertTrue(hasColumn(connection, "RELATIVE_PATH"));
            assertTrue(hasColumn(connection, "IS_AVAILABLE"));
            assertEquals("4", metaValue(connection, "schema_version"));
        }
    }

    @Test
    public void v3MigrationPreservesMetadataAndDerivesIdentity() throws Exception {
        try (Connection connection = open("migration")) {
            createV3(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO recordings (filename, abs_path, type, ts_ms,"
                        + " size_bytes, mp4_mtime, sidecar_mtime, storage) VALUES ("
                        + "'cam_20260810_120000.mp4',"
                        + "'/storage/ABCD-1234/Overdrive/recordings/cam_20260810_120000.mp4',"
                        + "'normal', 1234, 5678, 100, 200, 'SD_CARD')");
            }

            RecordingsIndexSchema.ensure(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT * FROM recordings")) {
                assertTrue(row.next());
                assertEquals("cam_20260810_120000.mp4", row.getString("filename"));
                assertEquals("portable:abcd-1234", row.getString("volume_id"));
                assertEquals("Overdrive/recordings/cam_20260810_120000.mp4",
                        row.getString("relative_path"));
                assertEquals(5678L, row.getLong("size_bytes"));
                assertFalse(row.getBoolean("is_available"));
                assertFalse(row.next());
            }
        }
    }

    @Test
    public void duplicateFilenamesOnDifferentVolumesCanCoexist() throws Exception {
        try (Connection connection = open("duplicates")) {
            RecordingsIndexSchema.ensure(connection);
            RecordingIdentity internal = RecordingIdentity.fromPath(
                    "/storage/emulated/0/Overdrive/recordings/cam.mp4");
            RecordingIdentity sd = RecordingIdentity.fromPath(
                    "/storage/ABCD-1234/Overdrive/recordings/cam.mp4");
            try (java.sql.PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO recordings (recording_id, filename, abs_path, root_id,"
                    + " volume_id, relative_path, root_rank, is_available, type, ts_ms)"
                    + " VALUES (?, 'cam.mp4', ?, ?, ?, ?, ?, TRUE, 'normal', 1)")) {
                insertIdentity(insert, internal,
                        "/storage/emulated/0/Overdrive/recordings/cam.mp4", 0);
                insert.executeUpdate();
                insertIdentity(insert, sd,
                        "/storage/ABCD-1234/Overdrive/recordings/cam.mp4", 1);
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet count = statement.executeQuery(
                         "SELECT COUNT(*) FROM recordings WHERE filename='cam.mp4'")) {
                assertTrue(count.next());
                assertEquals(2, count.getInt(1));
                assertNotEquals(internal.recordingId, sd.recordingId);
            }
        }
    }

    @Test
    public void ensureIsIdempotent() throws Exception {
        try (Connection connection = open("idempotent")) {
            RecordingsIndexSchema.ensure(connection);
            RecordingsIndexSchema.ensure(connection);
            assertEquals("4", metaValue(connection, "schema_version"));
        }
    }

    private static Connection open(String name) throws Exception {
        Class.forName("org.h2.Driver");
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void createV3(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE recordings ("
                    + "filename VARCHAR(256) PRIMARY KEY, abs_path VARCHAR(512) NOT NULL,"
                    + "type VARCHAR(16) NOT NULL, camera_id INT DEFAULT 0, ts_ms BIGINT NOT NULL,"
                    + "size_bytes BIGINT DEFAULT 0, mp4_mtime BIGINT DEFAULT 0,"
                    + "sidecar_mtime BIGINT DEFAULT 0, schema_version INT DEFAULT 0,"
                    + "peak_severity VARCHAR(16), peak_proximity VARCHAR(16),"
                    + "person_count INT DEFAULT 0, vehicle_count INT DEFAULT 0,"
                    + "bike_count INT DEFAULT 0, animal_count INT DEFAULT 0,"
                    + "hero_thumb VARCHAR(256), actor_classes VARCHAR(256),"
                    + "place_short VARCHAR(128), place_medium VARCHAR(192),"
                    + "place_display VARCHAR(256), place_country VARCHAR(8),"
                    + "place_source VARCHAR(32), start_lat DOUBLE, start_lng DOUBLE,"
                    + "ymd VARCHAR(10), storage VARCHAR(16))");
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData()
                .getColumns(null, null, "RECORDINGS", column)) {
            return columns.next();
        }
    }

    private static String metaValue(Connection connection, String key) throws Exception {
        try (java.sql.PreparedStatement query = connection.prepareStatement(
                "SELECT meta_value FROM recordings_meta WHERE meta_key=?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void insertIdentity(java.sql.PreparedStatement insert,
                                       RecordingIdentity identity,
                                       String absolutePath, int rank) throws Exception {
        insert.setString(1, identity.recordingId);
        insert.setString(2, absolutePath);
        insert.setString(3, identity.rootId);
        insert.setString(4, identity.volumeId);
        insert.setString(5, identity.relativePath);
        insert.setInt(6, rank);
    }
}