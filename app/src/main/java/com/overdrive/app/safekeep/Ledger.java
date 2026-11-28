package com.overdrive.app.safekeep;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only authority on what has been synced (one row per destination × object × generation).
 * Opened only while SafeKeep is on; WRITE_DELAY=0 so a checkpoint survives a SIGKILL.
 */
public final class Ledger {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepLedger");
    private static final String DB_PATH = com.overdrive.app.util.ScratchRoot.path("overdrive_safekeep_h2");
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH
            + ";FILE_LOCK=FS;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE"
            + ";WRITE_DELAY=0;AUTO_COMPACT_FILL_RATE=50";

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String GONE = "GONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String PRUNED = "PRUNED";

    /** error_class of SKIPPED rows: only the system reasons are revivable. */
    public static final String SKIP_USER = "user";
    public static final String SKIP_UNROUTED = "unrouted";
    public static final String SKIP_EMPTY = "empty";

    private final Object lock = new Object();
    private Connection conn;

    public static final class Item {
        public long id;
        public String destId;
        public String lane;
        public String kind;
        public String logicalKey;
        public int generation;
        public String groupKey;
        public String localPath;
        public long sizeBytes;
        public long mtime;
        public String remotePath;
        public String state;
        public int pclass;
        public long pscore;
        public int attempts;
        public String errorClass;
        public String lastError;
        public long nextAttemptAt;
        public String sessionJson;
        public long bytesConfirmed;
        public boolean encrypted;
        public String cryptJson;
        public String md5;
        public String sha256;
        public String remoteRef;
        public String metaJson;
        public long createdAt;
        public long completedAt;
        public long missingSince;
        public boolean manual;
        /** Error kind the {@link #attempts} counter belongs to (poison limits are per kind). */
        public String attemptClass;
        /** Counted attempts across all kinds (an overall ceiling alternating kinds can't dodge). */
        public int totalAttempts;

        public JSONObject meta() {
            try {
                return metaJson == null || metaJson.isEmpty() ? new JSONObject() : new JSONObject(metaJson);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public boolean open() {
        synchronized (lock) {
            if (conn != null) return true;
            try {
                Class.forName("org.h2.Driver");
                conn = DriverManager.getConnection(JDBC_URL, "sa", "");
                try (Statement st = conn.createStatement()) {
                    st.execute("SET CACHE_SIZE 1024");
                    st.execute("CREATE TABLE IF NOT EXISTS sync_item ("
                            + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                            + "dest_id VARCHAR(64) NOT NULL,"
                            + "lane VARCHAR(16) NOT NULL,"
                            + "kind VARCHAR(24) NOT NULL,"
                            + "logical_key VARCHAR(512) NOT NULL,"
                            + "generation INT NOT NULL DEFAULT 0,"
                            + "group_key VARCHAR(256),"
                            + "local_path VARCHAR(1024),"
                            + "size_bytes BIGINT NOT NULL DEFAULT 0,"
                            + "mtime BIGINT NOT NULL DEFAULT 0,"
                            + "remote_path VARCHAR(1024),"
                            + "state VARCHAR(16) NOT NULL,"
                            + "pclass INT NOT NULL DEFAULT 1,"
                            + "pscore BIGINT NOT NULL DEFAULT 0,"
                            + "attempts INT NOT NULL DEFAULT 0,"
                            + "error_class VARCHAR(24),"
                            + "last_error VARCHAR(512),"
                            + "next_attempt_at BIGINT NOT NULL DEFAULT 0,"
                            + "session_json CLOB,"
                            + "bytes_confirmed BIGINT NOT NULL DEFAULT 0,"
                            + "encrypted BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "crypt_json VARCHAR(4096),"
                            + "md5 VARCHAR(32),"
                            + "sha256 VARCHAR(64),"
                            + "remote_ref VARCHAR(1024),"
                            + "meta_json VARCHAR(4096),"
                            + "created_at BIGINT NOT NULL,"
                            + "completed_at BIGINT NOT NULL DEFAULT 0,"
                            + "missing_since BIGINT NOT NULL DEFAULT 0,"
                            + "manual BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "attempt_class VARCHAR(24),"
                            + "total_attempts INT NOT NULL DEFAULT 0,"
                            + "CONSTRAINT uq_item UNIQUE (dest_id, logical_key, generation))");
                    st.execute("ALTER TABLE sync_item ADD COLUMN IF NOT EXISTS attempt_class VARCHAR(24)");
                    st.execute("ALTER TABLE sync_item ADD COLUMN IF NOT EXISTS total_attempts INT NOT NULL DEFAULT 0");
                    st.execute("CREATE INDEX IF NOT EXISTS ix_item_sched ON sync_item(state, dest_id, pclass, pscore)");
                    st.execute("CREATE INDEX IF NOT EXISTS ix_item_key ON sync_item(logical_key)");
                    st.execute("CREATE INDEX IF NOT EXISTS ix_item_path ON sync_item(local_path)");
                    st.execute("CREATE TABLE IF NOT EXISTS dest_state ("
                            + "dest_id VARCHAR(64) PRIMARY KEY, state VARCHAR(16), backoff_until BIGINT,"
                            + "quota_used BIGINT, quota_total BIGINT, last_ok_at BIGINT,"
                            + "last_error VARCHAR(512), updated_at BIGINT)");
                    st.execute("CREATE TABLE IF NOT EXISTS budget ("
                            + "period_key VARCHAR(16), transport VARCHAR(12), bytes BIGINT,"
                            + "PRIMARY KEY(period_key, transport))");
                    st.execute("CREATE TABLE IF NOT EXISTS remote_folder ("
                            + "dest_id VARCHAR(64), path VARCHAR(1024), remote_id VARCHAR(512),"
                            + "PRIMARY KEY(dest_id, path))");
                    st.execute("CREATE TABLE IF NOT EXISTS kv (k VARCHAR(64) PRIMARY KEY, v VARCHAR(4096))");
                }
                return true;
            } catch (Throwable t) {
                logger.error("Ledger open failed", t);
                closeQuietly();
                return false;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            if (conn != null) conn.close();
        } catch (Throwable ignored) {
        }
        conn = null;
    }

    public boolean isOpen() {
        synchronized (lock) {
            return conn != null;
        }
    }

    // ── items ────────────────────────────────────────────────────────────

    /** Inserts unless (dest, key, generation) exists. Returns the new id, or -1 when it existed. */
    public long insertIfAbsent(Item it) {
        synchronized (lock) {
            if (conn == null) return -1;
            String sql = "INSERT INTO sync_item (dest_id, lane, kind, logical_key, generation, group_key,"
                    + " local_path, size_bytes, mtime, remote_path, state, pclass, pscore, encrypted,"
                    + " meta_json, created_at, manual) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS"
                    + " (SELECT 1 FROM sync_item WHERE dest_id=? AND logical_key=? AND generation=?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                ps.setString(i++, it.destId);
                ps.setString(i++, it.lane);
                ps.setString(i++, it.kind);
                ps.setString(i++, it.logicalKey);
                ps.setInt(i++, it.generation);
                ps.setString(i++, it.groupKey);
                ps.setString(i++, it.localPath);
                ps.setLong(i++, it.sizeBytes);
                ps.setLong(i++, it.mtime);
                ps.setString(i++, it.remotePath);
                ps.setString(i++, PENDING);
                ps.setInt(i++, it.pclass);
                ps.setLong(i++, it.pscore);
                ps.setBoolean(i++, it.encrypted);
                ps.setString(i++, it.metaJson);
                ps.setLong(i++, it.createdAt);
                ps.setBoolean(i++, it.manual);
                ps.setString(i++, it.destId);
                ps.setString(i++, it.logicalKey);
                ps.setInt(i, it.generation);
                if (ps.executeUpdate() == 0) return -1;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            } catch (Throwable t) {
                logger.warn("insertIfAbsent failed: " + t.getMessage());
                return -1;
            }
        }
    }

    /** Older PENDING generations of the same object will never upload. */
    public void supersedeOlder(String destId, String logicalKey, int generation) {
        exec("UPDATE sync_item SET state='" + SUPERSEDED + "' WHERE dest_id=? AND logical_key=?"
                + " AND generation<? AND state='" + PENDING + "'", destId, logicalKey, generation);
    }

    public int maxGeneration(String destId, String logicalKey) {
        synchronized (lock) {
            if (conn == null) return -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MAX(generation) FROM sync_item WHERE dest_id=? AND logical_key=?")) {
                ps.setString(1, destId);
                ps.setString(2, logicalKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int g = rs.getInt(1);
                        return rs.wasNull() ? -1 : g;
                    }
                }
            } catch (Throwable t) {
                logger.warn("maxGeneration failed: " + t.getMessage());
            }
            return -1;
        }
    }

    /**
     * Highest-priority eligible row for a destination among the allowed lanes and classes.
     * {@code manualOnly} selects user-requested rows; otherwise only the rest (manual rows are
     * judged by the manual rule, never by a route's).
     */
    public Item nextEligible(String destId, List<String> lanes, int maxClass, long now,
                             boolean manualOnly) {
        List<Item> rows = eligible(destId, lanes, maxClass, now, manualOnly, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Up to {@code limit} eligible rows in priority order (see {@link #nextEligible}). */
    public List<Item> eligible(String destId, List<String> lanes, int maxClass, long now,
                               boolean manualOnly, int limit) {
        List<Item> out = new ArrayList<>();
        if (lanes.isEmpty()) return out;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < lanes.size(); i++) in.append(i == 0 ? "?" : ",?");
        String sql = "SELECT * FROM sync_item WHERE state='" + PENDING + "' AND dest_id=?"
                + " AND lane IN (" + in + ") AND pclass<=? AND next_attempt_at<=?"
                + (manualOnly ? " AND manual=TRUE" : " AND manual=FALSE")
                + " ORDER BY pclass ASC, pscore DESC, id ASC LIMIT " + Math.max(1, limit);
        synchronized (lock) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, destId);
                for (String l : lanes) ps.setString(i++, l);
                ps.setInt(i++, maxClass);
                ps.setLong(i, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            } catch (Throwable t) {
                logger.warn("eligible failed: " + t.getMessage());
            }
        }
        return out;
    }

    /** Soonest next_attempt_at among PENDING rows, or 0 when none are waiting on a backoff. */
    public long earliestDeferred(long now) {
        synchronized (lock) {
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT MIN(next_attempt_at) FROM sync_item"
                    + " WHERE state='" + PENDING + "' AND next_attempt_at>?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    public Item get(long id) {
        synchronized (lock) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM sync_item WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? read(rs) : null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public void markActive(long id) {
        exec("UPDATE sync_item SET state='" + ACTIVE + "' WHERE id=? AND state='" + PENDING + "'", id);
    }

    public void saveSession(long id, String sessionJson, long bytesConfirmed) {
        exec("UPDATE sync_item SET session_json=?, bytes_confirmed=? WHERE id=?",
                sessionJson, bytesConfirmed, id);
    }

    public void saveCrypt(long id, String cryptJson) {
        exec("UPDATE sync_item SET crypt_json=? WHERE id=?", cryptJson, id);
    }

    /**
     * User asked for this object now (or locked it): lift priority, revive a FAILED or SKIPPED row,
     * and move it to the lane it is queued on now (its old lane may no longer be routed).
     */
    public int promote(String destId, String logicalKey, String lane, int pclass, boolean manual, boolean mobileOk,
                       long pscore) {
        List<Item> rows = query("SELECT * FROM sync_item WHERE dest_id=? AND logical_key=? AND state IN ('"
                + PENDING + "','" + FAILED + "','" + SKIPPED + "') ORDER BY generation DESC", 1, destId, logicalKey);
        if (rows.isEmpty()) return 0;
        Item it = rows.get(0);
        JSONObject meta = it.meta();
        try {
            if (mobileOk) meta.put("mobileOk", true);
        } catch (Exception ignored) {
        }
        boolean revive = !PENDING.equals(it.state);
        return exec("UPDATE sync_item SET manual=?, pscore=?, lane=?, pclass=LEAST(pclass, ?), next_attempt_at=0,"
                        + " state='" + PENDING + "', attempts=" + (revive ? "0" : "attempts") + ", attempt_class="
                        + (revive ? "NULL" : "attempt_class") + ", total_attempts=" + (revive ? "0" : "total_attempts")
                        + ", error_class=" + (revive ? "NULL" : "error_class") + ", meta_json=? WHERE id=?",
                manual || it.manual, Math.max(pscore, it.pscore), lane, pclass, meta.toString(), it.id);
    }

    public void saveMeta(long id, String metaJson) {
        exec("UPDATE sync_item SET meta_json=? WHERE id=?", metaJson, id);
    }

    /** Drops the remote session but keeps the encryption material (resume stays byte-identical). */
    public void clearSessionKeepCrypt(long id) {
        exec("UPDATE sync_item SET session_json=NULL, bytes_confirmed=0 WHERE id=?", id);
    }

    public boolean hasOpenRowForPath(String path) {
        return path != null && countWhere("local_path=? AND state IN ('" + PENDING + "','" + ACTIVE + "')", path) > 0;
    }

    public void markDone(long id, String remoteRef, String md5, String sha256, long now) {
        exec("UPDATE sync_item SET state='" + DONE + "', remote_ref=?, md5=?, sha256=?, completed_at=?,"
                        + " session_json=NULL, crypt_json=NULL, error_class=NULL, last_error=NULL WHERE id=?",
                remoteRef, md5, sha256, now, id);
    }

    /**
     * Back to PENDING with a delay. {@code countAttempt} is false for pauses and volume waits; a
     * counted retry restarts the counter when the error kind changes.
     */
    public void markRetry(long id, String errorClass, String lastError, long nextAttemptAt,
                          boolean countAttempt) {
        if (countAttempt) {
            exec("UPDATE sync_item SET attempts=CASE WHEN attempt_class=? THEN attempts+1 ELSE 1 END,"
                            + " total_attempts=total_attempts+1,"
                            + " attempt_class=?, state='" + PENDING + "', error_class=?, last_error=?,"
                            + " next_attempt_at=? WHERE id=?",
                    errorClass, errorClass, errorClass, trim(lastError), nextAttemptAt, id);
        } else {
            exec("UPDATE sync_item SET state='" + PENDING + "', error_class=?, last_error=?,"
                    + " next_attempt_at=? WHERE id=?", errorClass, trim(lastError), nextAttemptAt, id);
        }
    }

    /** The source file changed after queueing: new size, and any partial remote session is void. */
    public void updateSource(long id, long size, long mtime) {
        exec("UPDATE sync_item SET size_bytes=?, mtime=?, session_json=NULL, bytes_confirmed=0,"
                + " crypt_json=NULL WHERE id=?", size, mtime, id);
    }

    public void markState(long id, String state, String errorClass, String lastError) {
        exec("UPDATE sync_item SET state=?, error_class=?, last_error=?, session_json=NULL,"
                + " crypt_json=NULL WHERE id=?", state, errorClass, trim(lastError), id);
    }

    public void setMissingSince(long id, long ts) {
        exec("UPDATE sync_item SET missing_since=? WHERE id=?", ts, id);
    }

    /** Boot: anything ACTIVE was killed mid-flight; its session is kept for resume. */
    public int resetActive() {
        return exec("UPDATE sync_item SET state='" + PENDING + "' WHERE state='" + ACTIVE + "'");
    }

    /** Re-queue FAILED rows (user "Retry all"), optionally only one destination. */
    public int retryFailed(String destId) {
        String reset = "UPDATE sync_item SET state='" + PENDING + "', attempts=0, attempt_class=NULL,"
                + " total_attempts=0, next_attempt_at=0 WHERE state='" + FAILED + "'";
        return destId == null ? exec(reset) : exec(reset + " AND dest_id=?", destId);
    }

    public int retryItem(long id) {
        return exec("UPDATE sync_item SET state='" + PENDING + "', attempts=0, attempt_class=NULL, total_attempts=0,"
                + " next_attempt_at=0 WHERE id=? AND state IN ('" + FAILED + "','" + PENDING + "')", id);
    }

    /** User skip: never revived by reconcile (a system skip is, see {@link #reviveSkipped}). */
    public int skipItem(long id) {
        return exec("UPDATE sync_item SET state='" + SKIPPED + "', error_class='" + SKIP_USER + "',"
                + " session_json=NULL, crypt_json=NULL WHERE id=? AND state IN ('" + FAILED + "','" + PENDING + "')", id);
    }

    /** Rows the system skipped (lane unrouted, file empty) come back, on their current lane, when queued again. */
    public int reviveSkipped(String destId, String logicalKey, String lane, int pclass, long size, long mtime) {
        return exec("UPDATE sync_item SET state='" + PENDING + "', error_class=NULL, last_error=NULL,"
                        + " next_attempt_at=0, attempts=0, attempt_class=NULL, total_attempts=0, lane=?, pclass=?,"
                        + " size_bytes=?, mtime=? WHERE dest_id=? AND logical_key=? AND state='" + SKIPPED + "'"
                        + " AND error_class IN ('" + SKIP_UNROUTED + "','" + SKIP_EMPTY + "')",
                lane, pclass, size, mtime, destId, logicalKey);
    }

    /** Wakes every deferred row for a destination (after re-link or quota freed). */
    public int undeferDestination(String destId) {
        return exec("UPDATE sync_item SET next_attempt_at=0 WHERE dest_id=? AND state='" + PENDING + "'",
                destId);
    }

    public List<String> destIds() {
        List<String> out = new ArrayList<>();
        synchronized (lock) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT dest_id FROM sync_item");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Drops rows of a removed destination (never touches remote data). */
    public int forgetDestination(String destId) {
        exec("DELETE FROM remote_folder WHERE dest_id=?", destId);
        exec("DELETE FROM dest_state WHERE dest_id=?", destId);
        return exec("DELETE FROM sync_item WHERE dest_id=?", destId);
    }

    /** Rows {@link #skipUnrouted} is about to drop that still hold a remote session (to abandon first). */
    public List<Item> unroutedWithSession(String destId, List<String> routedLanes) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < routedLanes.size(); i++) in.append(i == 0 ? "?" : ",?");
        Object[] args = new Object[routedLanes.size() + 1];
        args[0] = destId;
        for (int i = 0; i < routedLanes.size(); i++) args[i + 1] = routedLanes.get(i);
        return query("SELECT * FROM sync_item WHERE dest_id=? AND state='" + PENDING + "' AND manual=FALSE"
                + " AND session_json IS NOT NULL" + (routedLanes.isEmpty() ? "" : " AND lane NOT IN (" + in + ")"),
                500, args);
    }

    /** Pending rows of a lane that is no longer routed to this destination are skipped (manual rows stay). */
    public int skipUnrouted(String destId, List<String> routedLanes) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < routedLanes.size(); i++) in.append(i == 0 ? "?" : ",?");
        Object[] args = new Object[routedLanes.size() + 1];
        args[0] = destId;
        for (int i = 0; i < routedLanes.size(); i++) args[i + 1] = routedLanes.get(i);
        String sql = "UPDATE sync_item SET state='" + SKIPPED + "', error_class='" + SKIP_UNROUTED + "',"
                + " session_json=NULL, crypt_json=NULL WHERE dest_id=? AND state='" + PENDING + "' AND manual=FALSE"
                + (routedLanes.isEmpty() ? "" : " AND lane NOT IN (" + in + ")");
        return exec(sql, args);
    }

    /** Queued media (any lane: manual and locked clips can ride another lane's route). */
    public List<Item> pendingWithLocalFiles() {
        return query("SELECT * FROM sync_item WHERE state IN ('" + PENDING + "','" + ACTIVE + "')"
                + " AND local_path IS NOT NULL AND kind IN ('EVENT_CLIP','EVENT_SIDECAR','PROXIMITY_CLIP',"
                + "'REPLAY_CLIP','CLIP_SIDECAR','LOOP_CLIP','DVR_CLIP')", 20000);
    }

    /** Newest generation of an object on a destination, or null. */
    public Item latest(String destId, String logicalKey) {
        List<Item> rows = query("SELECT * FROM sync_item WHERE dest_id=? AND logical_key=?"
                + " ORDER BY generation DESC", 1, destId, logicalKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A newer settings snapshot replaces every not-yet-sent one. */
    public void supersedePendingSettings(String destId) {
        exec("UPDATE sync_item SET state='" + SUPERSEDED + "' WHERE dest_id=? AND kind='SETTINGS' AND state='"
                + PENDING + "'", destId);
    }

    /** Uploaded settings snapshots, oldest first (keep-N pruning). */
    public List<Item> doneSettingsOldestFirst(String destId, int limit) {
        return query("SELECT * FROM sync_item WHERE dest_id=? AND kind='SETTINGS' AND state='" + DONE + "'"
                + " AND remote_ref IS NOT NULL ORDER BY completed_at ASC LIMIT " + Math.max(1, limit), limit, destId);
    }

    public int countDoneSettings(String destId) {
        return countWhere("dest_id=? AND kind='SETTINGS' AND state='" + DONE + "' AND remote_ref IS NOT NULL", destId);
    }

    /** Sizes of this destination's DONE rows for one clip group (a locked clip and its sidecars). */
    public long doneBytesForGroup(String destId, String groupKey, List<String> lanes) {
        if (groupKey == null || lanes.isEmpty()) return 0;
        long total = 0;
        for (Item it : query("SELECT * FROM sync_item WHERE dest_id=? AND group_key=? AND state='" + DONE + "'",
                50, destId, groupKey)) {
            if (lanes.contains(it.lane)) total += Math.max(0, it.sizeBytes);
        }
        return total;
    }

    /** Another DONE row still points at this remote object (same path, or a Drive file updated in place). */
    public boolean sharedRemoteRef(String destId, String remoteRef, long exceptId) {
        return remoteRef != null && countWhere("dest_id=? AND remote_ref=? AND id<>? AND state='" + DONE + "'",
                destId, remoteRef, exceptId) > 0;
    }

    /** Bytes SafeKeep itself holds on a destination in the given lanes. */
    public long doneBytes(String destId, List<String> lanes) {
        if (lanes.isEmpty()) return 0;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < lanes.size(); i++) in.append(i == 0 ? "?" : ",?");
        Object[] args = new Object[lanes.size() + 1];
        args[0] = destId;
        for (int i = 0; i < lanes.size(); i++) args[i + 1] = lanes.get(i);
        synchronized (lock) {
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(size_bytes),0) FROM sync_item"
                    + " WHERE dest_id=? AND lane IN (" + in + ") AND state='" + DONE + "' AND remote_ref IS NOT NULL")) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    public List<Item> byLogicalKey(String logicalKey) {
        return query("SELECT * FROM sync_item WHERE logical_key=? ORDER BY generation DESC", 50,
                logicalKey);
    }

    /** True when the object is known to the destination (system-skipped rows don't count). */
    public boolean hasRowFor(String destId, String logicalKey) {
        synchronized (lock) {
            if (conn == null) return true;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM sync_item WHERE dest_id=? AND logical_key=? AND NOT (state='" + SKIPPED
                            + "' AND error_class IN ('" + SKIP_UNROUTED + "','" + SKIP_EMPTY + "')) LIMIT 1")) {
                ps.setString(1, destId);
                ps.setString(2, logicalKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (Throwable t) {
                return true;
            }
        }
    }

    public List<Item> listQueue(String state, String lane, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sync_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (state != null && !state.isEmpty()) {
            sql.append(" AND state=?");
            args.add(state);
        } else {
            sql.append(" AND state IN ('" + PENDING + "','" + ACTIVE + "','" + FAILED + "')");
        }
        if (lane != null && !lane.isEmpty()) {
            sql.append(" AND lane=?");
            args.add(lane);
        }
        sql.append(" ORDER BY CASE state WHEN 'ACTIVE' THEN 0 WHEN 'FAILED' THEN 1 ELSE 2 END,"
                + " manual DESC, pclass ASC, pscore DESC, id ASC LIMIT ").append(Math.max(1, limit));
        return query(sql.toString(), limit, args.toArray());
    }

    /** DONE rows older than {@code cutoff} for janitor pruning, oldest first. */
    public List<Item> prunable(String destId, String lane, long cutoff, int limit) {
        return query("SELECT * FROM sync_item WHERE dest_id=? AND lane=? AND state='" + DONE + "'"
                + " AND remote_ref IS NOT NULL AND completed_at>0 AND completed_at<?"
                + " ORDER BY completed_at ASC LIMIT " + Math.max(1, limit), limit, destId, lane, cutoff);
    }

    /** DONE generations beyond the newest {@code keep} for a lane (settings history). */
    public List<Item> doneOldestFirst(String destId, String lane, int limit) {
        return query("SELECT * FROM sync_item WHERE dest_id=? AND lane=? AND state='" + DONE + "'"
                + " AND remote_ref IS NOT NULL ORDER BY completed_at ASC LIMIT " + Math.max(1, limit),
                limit, destId, lane);
    }

    /** {lane -> {state -> [count, bytes]}} across all destinations. */
    public Map<String, Map<String, long[]>> backlog() {
        Map<String, Map<String, long[]>> out = new HashMap<>();
        synchronized (lock) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement("SELECT lane, state, COUNT(*),"
                    + " SUM(size_bytes - bytes_confirmed) FROM sync_item WHERE state IN ('" + PENDING
                    + "','" + ACTIVE + "','" + FAILED + "') GROUP BY lane, state");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, long[]> m = out.get(rs.getString(1));
                    if (m == null) {
                        m = new HashMap<>();
                        out.put(rs.getString(1), m);
                    }
                    m.put(rs.getString(2), new long[]{rs.getLong(3), Math.max(0, rs.getLong(4))});
                }
            } catch (Throwable t) {
                logger.warn("backlog failed: " + t.getMessage());
            }
        }
        return out;
    }

    public int countWhere(String where, Object... args) {
        synchronized (lock) {
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sync_item WHERE " + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    // ── destination state ─────────────────────────────────────────────

    public static final class DestState {
        public String state = "ok";
        public long backoffUntil;
        public long quotaUsed = -1;
        public long quotaTotal = -1;
        public long lastOkAt;
        public String lastError;
    }

    public DestState destState(String destId) {
        DestState d = new DestState();
        synchronized (lock) {
            if (conn == null) return d;
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dest_state WHERE dest_id=?")) {
                ps.setString(1, destId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.state = rs.getString("state");
                        d.backoffUntil = rs.getLong("backoff_until");
                        d.quotaUsed = rs.getLong("quota_used");
                        d.quotaTotal = rs.getLong("quota_total");
                        d.lastOkAt = rs.getLong("last_ok_at");
                        d.lastError = rs.getString("last_error");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (d.state == null) d.state = "ok";
        return d;
    }

    public void saveDestState(String destId, DestState d) {
        exec("MERGE INTO dest_state (dest_id, state, backoff_until, quota_used, quota_total, last_ok_at,"
                        + " last_error, updated_at) KEY(dest_id) VALUES (?,?,?,?,?,?,?,?)",
                destId, d.state, d.backoffUntil, d.quotaUsed, d.quotaTotal, d.lastOkAt,
                trim(d.lastError), System.currentTimeMillis());
    }

    // ── budgets, folders, locks, kv ────────────────────────────────────

    public long budget(String periodKey, String transport) {
        synchronized (lock) {
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bytes FROM budget WHERE period_key=? AND transport=?")) {
                ps.setString(1, periodKey);
                ps.setString(2, transport);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    public void addBudget(String periodKey, String transport, long bytes) {
        if (bytes <= 0) return;
        exec("MERGE INTO budget (period_key, transport, bytes) KEY(period_key, transport)"
                        + " VALUES (?, ?, COALESCE((SELECT bytes FROM budget WHERE period_key=? AND transport=?), 0) + ?)",
                periodKey, transport, periodKey, transport, bytes);
    }

    public String folderId(String destId, String path) {
        synchronized (lock) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT remote_id FROM remote_folder WHERE dest_id=? AND path=?")) {
                ps.setString(1, destId);
                ps.setString(2, path);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public void putFolderId(String destId, String path, String remoteId) {
        exec("MERGE INTO remote_folder (dest_id, path, remote_id) KEY(dest_id, path) VALUES (?,?,?)",
                destId, path, remoteId);
    }

    public void dropFolderIds(String destId) {
        exec("DELETE FROM remote_folder WHERE dest_id=?", destId);
    }

    public String kv(String k) {
        synchronized (lock) {
            if (conn == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM kv WHERE k=?")) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public void putKv(String k, String v) {
        exec("MERGE INTO kv (k, v) KEY(k) VALUES (?, ?)", k, v);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private int exec(String sql, Object... args) {
        synchronized (lock) {
            if (conn == null) return 0;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                return ps.executeUpdate();
            } catch (Throwable t) {
                logger.warn("Ledger exec failed (" + sql.substring(0, Math.min(40, sql.length()))
                        + "…): " + t.getMessage());
                return 0;
            }
        }
    }

    private List<Item> query(String sql, int limit, Object... args) {
        List<Item> out = new ArrayList<>();
        synchronized (lock) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, args);
                ps.setMaxRows(Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            } catch (Throwable t) {
                logger.warn("Ledger query failed: " + t.getMessage());
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof Long) ps.setLong(i + 1, (Long) a);
            else if (a instanceof Integer) ps.setInt(i + 1, (Integer) a);
            else if (a instanceof Boolean) ps.setBoolean(i + 1, (Boolean) a);
            else ps.setString(i + 1, a.toString());
        }
    }

    private static Item read(ResultSet rs) throws Exception {
        Item it = new Item();
        it.id = rs.getLong("id");
        it.destId = rs.getString("dest_id");
        it.lane = rs.getString("lane");
        it.kind = rs.getString("kind");
        it.logicalKey = rs.getString("logical_key");
        it.generation = rs.getInt("generation");
        it.groupKey = rs.getString("group_key");
        it.localPath = rs.getString("local_path");
        it.sizeBytes = rs.getLong("size_bytes");
        it.mtime = rs.getLong("mtime");
        it.remotePath = rs.getString("remote_path");
        it.state = rs.getString("state");
        it.pclass = rs.getInt("pclass");
        it.pscore = rs.getLong("pscore");
        it.attempts = rs.getInt("attempts");
        it.errorClass = rs.getString("error_class");
        it.lastError = rs.getString("last_error");
        it.nextAttemptAt = rs.getLong("next_attempt_at");
        it.sessionJson = rs.getString("session_json");
        it.bytesConfirmed = rs.getLong("bytes_confirmed");
        it.encrypted = rs.getBoolean("encrypted");
        it.cryptJson = rs.getString("crypt_json");
        it.md5 = rs.getString("md5");
        it.sha256 = rs.getString("sha256");
        it.remoteRef = rs.getString("remote_ref");
        it.metaJson = rs.getString("meta_json");
        it.createdAt = rs.getLong("created_at");
        it.completedAt = rs.getLong("completed_at");
        it.missingSince = rs.getLong("missing_since");
        it.manual = rs.getBoolean("manual");
        it.attemptClass = rs.getString("attempt_class");
        it.totalAttempts = rs.getInt("total_attempts");
        return it;
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
