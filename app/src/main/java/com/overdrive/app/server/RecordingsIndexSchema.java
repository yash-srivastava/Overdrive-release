package com.overdrive.app.server;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RecordingsIndexSchema {
    static final int VERSION = 4;

    private static final String[] LEGACY_COLUMNS = {
        "filename", "abs_path", "type", "camera_id", "ts_ms", "size_bytes",
        "mp4_mtime", "sidecar_mtime", "schema_version", "peak_severity",
        "peak_proximity", "person_count", "vehicle_count", "bike_count",
        "animal_count", "hero_thumb", "actor_classes", "place_short",
        "place_medium", "place_display", "place_country", "place_source",
        "start_lat", "start_lng", "ymd", "storage"
    };

    private RecordingsIndexSchema() {}

    static void ensure(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            createMetaTable(statement);
        }
        if (!tableExists(connection, "RECORDINGS")) {
            try (Statement statement = connection.createStatement()) {
                createV4Table(statement);
            }
        } else if (!columnExists(connection, "RECORDINGS", "RECORDING_ID")) {
            migrateV3(connection);
        }
        try (Statement statement = connection.createStatement()) {
            createIndexes(statement);
            statement.execute("UPDATE recordings SET type = 'replay'"
                    + " WHERE type <> 'replay'"
                    + " AND filename LIKE 'replay\\_%' ESCAPE '\\'");
            statement.execute("MERGE INTO recordings_meta KEY(meta_key) VALUES"
                    + "('schema_version', '" + VERSION + "')");
        }
    }

    private static void migrateV3(Connection connection) throws Exception {
        List<Map<String, Object>> legacyRows = readLegacyRows(connection);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS recordings_v3_backup");
            statement.execute("ALTER TABLE recordings RENAME TO recordings_v3_backup");
            createV4Table(statement);
            copyLegacyRows(connection, legacyRows);
            statement.execute("DROP TABLE recordings_v3_backup");
            connection.commit();
        } catch (Exception migrationFailure) {
            try { connection.rollback(); } catch (Exception ignored) {}
            // The index is derived. Prefer a clean, warmup-rebuildable v4 table
            // over a daemon lifetime with an unusable half-migrated store.
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS recordings");
                statement.execute("DROP TABLE IF EXISTS recordings_v3_backup");
                createV4Table(statement);
                connection.commit();
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static List<Map<String, Object>> readLegacyRows(Connection connection)
            throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM recordings")) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int index = 1; index <= meta.getColumnCount(); index++) {
                    row.put(meta.getColumnLabel(index).toLowerCase(Locale.US),
                            result.getObject(index));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void copyLegacyRows(Connection connection,
                                       List<Map<String, Object>> rows) throws Exception {
        String sql = "INSERT INTO recordings (recording_id, filename, abs_path, root_id,"
                + " volume_id, relative_path, root_rank, is_available,"
                + String.join(", ", LEGACY_COLUMNS).substring(
                        "filename, abs_path, ".length())
                + ") VALUES (" + placeholders(32) + ")";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (Map<String, Object> legacy : rows) {
                String absolutePath = String.valueOf(legacy.get("abs_path"));
                RecordingIdentity identity = RecordingIdentity.fromPath(absolutePath);
                int parameter = 1;
                insert.setString(parameter++, identity.recordingId);
                insert.setObject(parameter++, legacy.get("filename"));
                insert.setString(parameter++, absolutePath);
                insert.setString(parameter++, identity.rootId);
                insert.setString(parameter++, identity.volumeId);
                insert.setString(parameter++, identity.relativePath);
                insert.setInt(parameter++, 100);
                insert.setBoolean(parameter++, new File(absolutePath).isFile());
                for (int index = 2; index < LEGACY_COLUMNS.length; index++) {
                    insert.setObject(parameter++, legacy.get(LEGACY_COLUMNS[index]));
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void createV4Table(Statement statement) throws Exception {
        statement.execute(
            "CREATE TABLE IF NOT EXISTS recordings (" +
            "  recording_id    VARCHAR(64) PRIMARY KEY," +
            "  filename        VARCHAR(256) NOT NULL," +
            "  abs_path        VARCHAR(768) NOT NULL," +
            "  root_id         VARCHAR(64) NOT NULL," +
            "  volume_id       VARCHAR(128) NOT NULL," +
            "  relative_path   VARCHAR(768) NOT NULL," +
            "  root_rank       INT NOT NULL DEFAULT 100," +
            "  is_available    BOOLEAN NOT NULL DEFAULT TRUE," +
            "  type            VARCHAR(16) NOT NULL," +
            "  camera_id       INT DEFAULT 0," +
            "  ts_ms           BIGINT NOT NULL," +
            "  size_bytes      BIGINT NOT NULL DEFAULT 0," +
            "  mp4_mtime       BIGINT NOT NULL DEFAULT 0," +
            "  sidecar_mtime   BIGINT NOT NULL DEFAULT 0," +
            "  schema_version  INT DEFAULT 0," +
            "  peak_severity   VARCHAR(16)," +
            "  peak_proximity  VARCHAR(16)," +
            "  person_count    INT DEFAULT 0," +
            "  vehicle_count   INT DEFAULT 0," +
            "  bike_count      INT DEFAULT 0," +
            "  animal_count    INT DEFAULT 0," +
            "  hero_thumb      VARCHAR(256)," +
            "  actor_classes   VARCHAR(256)," +
            "  place_short     VARCHAR(128)," +
            "  place_medium    VARCHAR(192)," +
            "  place_display   VARCHAR(256)," +
            "  place_country   VARCHAR(8)," +
            "  place_source    VARCHAR(32)," +
            "  start_lat       DOUBLE," +
            "  start_lng       DOUBLE," +
            "  ymd             VARCHAR(10)," +
            "  storage         VARCHAR(16)," +
            "  UNIQUE(volume_id, relative_path)" +
            ")"
        );
    }

    private static void createIndexes(Statement statement) throws Exception {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_available_ts"
                + " ON recordings(is_available, ts_ms DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_filename_rank"
                + " ON recordings(filename, is_available, root_rank, ts_ms DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_root_id"
                + " ON recordings(root_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_type_ts"
                + " ON recordings(type, ts_ms DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_ymd ON recordings(ymd)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_place ON recordings(place_short)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_country ON recordings(place_country)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_severity ON recordings(peak_severity)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_rec_storage ON recordings(storage)");
    }

    private static void createMetaTable(Statement statement) throws Exception {
        statement.execute("CREATE TABLE IF NOT EXISTS recordings_meta ("
                + "meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(256))");
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet result = meta.getTables(null, null, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet result = meta.getColumns(null, null, table, column)) {
            return result.next();
        }
    }

    private static String placeholders(int count) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) sql.append(',');
            sql.append('?');
        }
        return sql.toString();
    }
}