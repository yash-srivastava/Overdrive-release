package com.overdrive.app.storage;

import com.overdrive.app.logging.DaemonLogger;

import org.h2.api.ErrorCode;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVStoreException;
import org.h2.tools.Recover;
import org.h2.util.ScriptReader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replaces an H2 store whose file no longer opens. The corrupt file is kept in
 * {@code <db>.corrupt-<ms>/}; the replacement holds the newest version that still reads cleanly,
 * else whatever H2 Recover can read around the damage, else nothing.
 */
public final class H2StoreSalvage {

    public enum Outcome { SALVAGED, FRESH, FAILED }

    private static final String MV_SUFFIX = ".mv.db";
    private static final String INCIDENT_INFIX = ".corrupt-";
    private static final String IN_PROGRESS_MARKER = "salvage.inprogress";
    private static final String SCRIPT = "salvage.sql";
    private static final String CANDIDATE = "candidate";
    private static final String OPENABLE = "openable";
    private static final String STAGING = "salvaged";
    private static final String REJECTED = "salvaged-rejected";
    private static final String WORK_DB_OPTIONS =
            ";FILE_LOCK=NO;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
    private static final int BLOCK_SIZE = 4096;
    // A power cut can leave several of the newest chunks half-written.
    private static final int MAX_DROPPED_CHUNKS = 32;
    private static final long MAX_SALVAGE_BYTES = 128L * 1024 * 1024;
    // Copy, candidate, openable copy, script and staging store, against the corrupt file's size.
    private static final int FREE_SPACE_FACTOR = 7;
    private static final long FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024;
    private static final int KEPT_INCIDENTS = 2;
    private static final int LOGGED_REPLAY_FAILURES = 3;

    private final String dbPath;
    private final DaemonLogger logger;
    private Outcome outcome;
    private File incidentDir;

    public H2StoreSalvage(String dbPath, DaemonLogger logger) {
        this.dbPath = dbPath;
        this.logger = logger;
    }

    /** True when a failed open should be answered with {@link #replace()}. */
    public synchronized boolean shouldReplace(Throwable failure) {
        return outcome == null ? isCorruption(failure) : outcome == Outcome.SALVAGED;
    }

    /** Swaps the store file once per process; the caller must have closed its handle. */
    public synchronized boolean replace() {
        if (outcome == null) {
            outcome = replaceCorruptStore();
        } else if (outcome == Outcome.SALVAGED) {
            outcome = discardSalvagedStore() ? Outcome.FRESH : Outcome.FAILED;
        } else {
            return false;
        }
        return outcome != Outcome.FAILED;
    }

    /** An unreadable store file, as opposed to a lock, I/O or schema failure. */
    public static boolean isCorruption(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof SQLException
                    && ((SQLException) t).getErrorCode() == ErrorCode.FILE_CORRUPTED_1) {
                return true;
            }
            if (t instanceof MVStoreException) {
                int code = ((MVStoreException) t).getErrorCode();
                if (code == DataUtils.ERROR_FILE_CORRUPT
                        || code == DataUtils.ERROR_CHUNK_NOT_FOUND) {
                    return true;
                }
            }
        }
        return false;
    }

    private Outcome replaceCorruptStore() {
        File store = new File(dbPath + MV_SUFFIX).getAbsoluteFile();
        File parent = store.getParentFile();
        if (parent == null || !store.isFile()) {
            logger.error("Corrupt store " + store + " is not a file; leaving it in place");
            return Outcome.FAILED;
        }
        String base = store.getName().substring(0, store.getName().length() - MV_SUFFIX.length());
        File incident = new File(parent, base + INCIDENT_INFIX + System.currentTimeMillis());
        if (!incident.mkdir()) {
            logger.error("Could not create " + incident + "; leaving the corrupt store in place");
            return Outcome.FAILED;
        }
        incidentDir = incident;
        File kept = new File(incident, store.getName());
        boolean earlierSalvageDied = clearSalvageMarkers(parent, base, incident);
        String skipReason = earlierSalvageDied
                ? "an earlier salvage did not finish"
                : salvageSkipReason(parent, store.length());
        Outcome result = skipReason == null
                ? salvage(store, kept, incident)
                : setAside(store, kept, skipReason);
        if (result == Outcome.FAILED) deleteTree(incident);
        pruneIncidents(parent, base, incident);
        return result;
    }

    private Outcome setAside(File store, File kept, String reason) {
        if (!move(store, kept)) {
            logger.error("Could not move the corrupt store " + store + " aside");
            return Outcome.FAILED;
        }
        logger.error("Moved the corrupt store to " + kept + " without salvage (" + reason
                + "); starting an empty store");
        return Outcome.FRESH;
    }

    private Outcome salvage(File store, File kept, File incident) {
        long startedNs = System.nanoTime();
        File marker = new File(incident, IN_PROGRESS_MARKER);
        try {
            Files.copy(store.toPath(), kept.toPath());
            // The copy must be on disk before anything replaces or deletes the original.
            syncToDisk(kept);
            syncToDisk(incident);
            if (!marker.createNewFile()) throw new IOException("marker exists");
        } catch (IOException e) {
            marker.delete();
            kept.delete();
            return setAside(store, kept, "copy failed: " + e.getMessage());
        }
        File script = new File(incident, SCRIPT);
        File staging = new File(incident, STAGING);
        File openable = new File(incident, OPENABLE + MV_SUFFIX);
        try {
            String source = exportOlderVersion(kept, incident, script, openable);
            Replay replay = source != null ? replay(script, staging) : null;
            if (replay == null || replay.rows == 0) {
                deleteDatabaseFiles(incident, STAGING);
                source = dumpWithRecover(openable.isFile() ? openable : kept, script)
                        ? "H2 Recover" : null;
                replay = source != null ? replay(script, staging) : null;
            }
            if (replay != null && replay.rows > 0) {
                install(new File(incident, STAGING + MV_SUFFIX), store);
                logger.warn("Salvaged the corrupt store from " + source + " in "
                        + (System.nanoTime() - startedNs) / 1_000_000 + " ms: "
                        + replay.summary
                        + (replay.failures > 0
                                ? " (" + replay.failures + " statements failed)" : "")
                        + "; original kept at " + kept);
                return Outcome.SALVAGED;
            }
            logger.error("Nothing could be salvaged from the corrupt store; starting an empty"
                    + " store. Original kept at " + kept);
        } catch (Throwable t) {
            logger.error("Salvage failed; starting an empty store. Original kept at " + kept, t);
        } finally {
            script.delete();
            deleteDatabaseFiles(incident, CANDIDATE);
            deleteDatabaseFiles(incident, OPENABLE);
            deleteDatabaseFiles(incident, STAGING);
            marker.delete();
        }
        if (store.delete() || !store.exists()) return Outcome.FRESH;
        logger.error("Could not remove the corrupt store " + store);
        return Outcome.FAILED;
    }

    /**
     * Hides the newest chunks one at a time and exports the first version that reads in full. The
     * first version that opens but does not read in full is copied to {@code openable} for Recover.
     */
    private String exportOlderVersion(File kept, File incident, File script, File openable)
            throws IOException {
        List<long[]> headers = chunkHeaders(kept);
        List<Long> newest = newestChunkIds(headers);
        int maxDropped = Math.min(MAX_DROPPED_CHUNKS, newest.size() - 1);
        if (maxDropped < 1) return null;
        File candidate = new File(incident, CANDIDATE + MV_SUFFIX);
        Files.copy(kept.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING);
        String url = "jdbc:h2:file:" + new File(incident, CANDIDATE).getAbsolutePath()
                + WORK_DB_OPTIONS + ";ACCESS_MODE_DATA=r";
        String lastError = null;
        try {
            for (int dropped = 1; dropped <= maxDropped; dropped++) {
                hideChunk(candidate, headers, newest.get(dropped - 1));
                boolean opened = false;
                try (Connection connection = DriverManager.getConnection(url, "sa", "");
                     Statement statement = connection.createStatement()) {
                    if (publicTables(statement).isEmpty()) continue;
                    opened = true;
                    statement.execute("SCRIPT TO '"
                            + script.getAbsolutePath().replace("'", "''") + "'");
                    return "the version before its last " + dropped + " write"
                            + (dropped == 1 ? "" : "s");
                } catch (Exception e) {
                    lastError = errorCode(e);
                    if (opened && !openable.exists()) {
                        try {
                            Files.copy(candidate.toPath(), openable.toPath());
                        } catch (IOException copyFailure) {
                            openable.delete();
                        }
                    }
                }
            }
        } finally {
            deleteDatabaseFiles(incident, CANDIDATE);
        }
        logger.info("No version among the last " + maxDropped + " writes reads in full"
                + (lastError != null ? " (last error " + lastError + ")" : ""));
        return null;
    }

    /** {offset, chunk id, version} for every block that starts with an MVStore chunk header. */
    private static List<long[]> chunkHeaders(File file) throws IOException {
        List<long[]> headers = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            ByteBuffer block = ByteBuffer.allocate(1024);
            for (long offset = 2L * BLOCK_SIZE; offset + BLOCK_SIZE <= size; offset += BLOCK_SIZE) {
                block.clear();
                if (channel.read(block, offset) <= 0) continue;
                String text = new String(block.array(), 0, block.position(),
                        StandardCharsets.ISO_8859_1);
                int end = text.indexOf('\n');
                if (end < 0 || !text.startsWith("chunk:")) continue;
                Map<String, Long> fields = new HashMap<>();
                for (String field : text.substring(0, end).trim().split(",")) {
                    int colon = field.indexOf(':');
                    if (colon <= 0) continue;
                    try {
                        fields.put(field.substring(0, colon),
                                Long.parseLong(field.substring(colon + 1), 16));
                    } catch (NumberFormatException ignored) {
                        // Not a hex attribute.
                    }
                }
                Long id = fields.get("chunk");
                Long length = fields.get("len");
                Long version = fields.get("version");
                if (id == null || length == null || version == null || length <= 0
                        || !fields.containsKey("pages") || !fields.containsKey("root")
                        || offset + length * BLOCK_SIZE > size) {
                    continue;
                }
                headers.add(new long[] {offset, id, version});
            }
        }
        return headers;
    }

    /** Chunk ids ordered newest first. */
    private static List<Long> newestChunkIds(List<long[]> headers) {
        Map<Long, Long> versionById = new HashMap<>();
        for (long[] header : headers) versionById.merge(header[1], header[2], Math::max);
        List<Long> ids = new ArrayList<>(versionById.keySet());
        ids.sort((a, b) -> Long.compare(versionById.get(b), versionById.get(a)));
        return ids;
    }

    private static void hideChunk(File file, List<long[]> headers, long id) throws IOException {
        // Reuse a single zero-filled block when hiding matching chunk headers.
        ByteBuffer zeros = ByteBuffer.allocate(BLOCK_SIZE);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            for (long[] header : headers) {
                if (header[1] != id) continue;
                zeros.clear();
                long position = header[0];
                while (zeros.hasRemaining()) {
                    int written = channel.write(zeros, position);
                    if (written <= 0) throw new IOException("Could not clear chunk header at " + position);
                    position += written;
                }
            }
        }
    }

    private boolean dumpWithRecover(File kept, File script) {
        try {
            // Recover's public entry point also runs MVStoreTool.dump, which throws on damaged chunks.
            Method dump = Recover.class.getDeclaredMethod(
                    "dumpMVStoreFile", PrintWriter.class, String.class);
            dump.setAccessible(true);
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(script), StandardCharsets.UTF_8)))) {
                dump.invoke(new Recover(), writer, kept.getAbsolutePath());
                if (writer.checkError()) throw new IOException("could not write " + script);
            }
            return true;
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            logger.warn("H2 Recover could not read the corrupt store: " + e);
            return false;
        }
    }

    private Replay replay(File script, File stagingBase) throws SQLException, IOException {
        String url = "jdbc:h2:file:" + stagingBase.getAbsolutePath() + WORK_DB_OPTIONS;
        int failures = 0;
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             Reader in = new BufferedReader(new InputStreamReader(
                     new FileInputStream(script), StandardCharsets.UTF_8))) {
            ScriptReader reader = new ScriptReader(in);
            reader.setSkipRemarks(true);
            for (String sql = reader.readStatement(); sql != null; sql = reader.readStatement()) {
                if (sql.trim().isEmpty()) continue;
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    if (++failures <= LOGGED_REPLAY_FAILURES) {
                        logger.warn("Salvage statement failed [" + e.getErrorCode() + "]: "
                                + statementHead(sql));
                    }
                }
            }
            long rows = 0;
            StringBuilder summary = new StringBuilder();
            for (String table : publicTables(statement)) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM \""
                        + table.replace("\"", "\"\"") + "\"")) {
                    long count = rs.next() ? rs.getLong(1) : 0;
                    rows += count;
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(table.toLowerCase(Locale.ROOT)).append('=').append(count);
                }
            }
            statement.execute("SHUTDOWN COMPACT");
            return new Replay(rows, summary.toString(), failures);
        }
    }

    private static List<String> publicTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("SELECT TABLE_NAME"
                + " FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
                + " AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    private static void install(File product, File store) throws IOException {
        try (FileChannel channel = FileChannel.open(product.toPath(), StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(product.toPath(), store.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(product.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean discardSalvagedStore() {
        File store = new File(dbPath + MV_SUFFIX).getAbsoluteFile();
        File rejected = incidentDir != null ? new File(incidentDir, REJECTED + MV_SUFFIX) : null;
        if ((rejected != null && move(store, rejected)) || store.delete() || !store.exists()) {
            logger.error("The salvaged store did not open; starting an empty store");
            return true;
        }
        logger.error("Could not remove the salvaged store " + store);
        return false;
    }

    private static String salvageSkipReason(File parent, long size) {
        if (size > MAX_SALVAGE_BYTES) return size + " bytes is over the salvage limit";
        if (parent.getUsableSpace() < size * FREE_SPACE_FACTOR + FREE_SPACE_MARGIN_BYTES) {
            return "not enough free space";
        }
        return null;
    }

    /** True when an earlier salvage died mid-way; its marker then blocks only this retry. */
    private static boolean clearSalvageMarkers(File parent, String base, File current) {
        boolean found = false;
        for (File incident : incidents(parent, base)) {
            File marker = new File(incident, IN_PROGRESS_MARKER);
            if (!incident.equals(current) && marker.exists()) {
                found = true;
                marker.delete();
            }
        }
        return found;
    }

    /** Keeps the current incident and the newest of the others. */
    private static void pruneIncidents(File parent, String base, File current) {
        List<File> others = incidents(parent, base);
        others.remove(current);
        others.sort((a, b) -> Long.compare(incidentTime(b), incidentTime(a)));
        for (int i = KEPT_INCIDENTS - 1; i < others.size(); i++) deleteTree(others.get(i));
    }

    private static List<File> incidents(File parent, String base) {
        String prefix = base + INCIDENT_INFIX;
        File[] found = parent.listFiles(f -> f.getName().startsWith(prefix) && f.isDirectory()
                && !Files.isSymbolicLink(f.toPath()));
        List<File> incidents = new ArrayList<>();
        if (found != null) {
            for (File f : found) incidents.add(f.getAbsoluteFile());
        }
        return incidents;
    }

    private static long incidentTime(File incident) {
        String name = incident.getName();
        try {
            return Long.parseLong(name.substring(name.lastIndexOf(INCIDENT_INFIX)
                    + INCIDENT_INFIX.length()));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static boolean move(File from, File to) {
        try {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(from.toPath(), to.toPath());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void syncToDisk(File file) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void deleteDatabaseFiles(File dir, String name) {
        File[] files = dir.listFiles(f -> f.getName().startsWith(name + "."));
        if (files == null) return;
        for (File f : files) f.delete();
    }

    /** Deletes a directory this class created; a symlink is removed, never followed. */
    private static void deleteTree(File dir) {
        if (!Files.isSymbolicLink(dir.toPath())) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        dir.delete();
    }

    /** The statement's leading keywords, without the row values it carries. */
    private static String statementHead(String sql) {
        String trimmed = sql.trim().replace('\n', ' ');
        int values = trimmed.indexOf('(');
        int end = Math.min(values > 0 ? values : trimmed.length(), 64);
        return trimmed.substring(0, end);
    }

    private static String errorCode(Exception e) {
        return e instanceof SQLException
                ? "[" + ((SQLException) e).getErrorCode() + "]"
                : e.getClass().getSimpleName();
    }

    private static final class Replay {
        final long rows;
        final String summary;
        final int failures;

        Replay(long rows, String summary, int failures) {
            this.rows = rows;
            this.summary = summary;
            this.failures = failures;
        }
    }
}
