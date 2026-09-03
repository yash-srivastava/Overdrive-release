package com.overdrive.app.notifications;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent notification log — the "history" backing the Notifications ▸ Log
 * tab. Every {@link NotificationEvent} that flows through {@link NotificationBus}
 * is written here by {@link com.overdrive.app.notifications.sinks.HistorySink},
 * so the web UI can browse, filter, and delete past alerts.
 *
 * <p>Uses a DEDICATED H2 embedded database (pure Java, no native code), separate
 * from {@code SocHistoryDatabase} — its own file, its own lock. Mirrors the
 * SocHistoryDatabase resilience idioms: one long-lived connection, an init retry
 * loop with stale-lock cleanup, {@link #reconnect()} on a stale connection so a
 * single transient error can't dark the log for the whole session, and a
 * dedicated daily prune scheduler that keeps retention off the request/write
 * path. All DB access is guarded by {@link #lock} because the write path runs on
 * the NotificationBus thread while reads run on HTTP server threads.
 *
 * <p>Runs in the same JVM as the HTTP server, so {@code NotificationApiHandler}
 * reads it directly with no IPC.
 */
public final class NotificationStore {

    private static final String TAG = "NotificationStore";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Dedicated H2 file — NOT shared with overdrive_soc_h2. DB_CLOSE_ON_EXIT=FALSE:
    // shutdown is driven explicitly from CameraDaemon.shutdown() (mirrors
    // SocHistoryDatabase). FILE_LOCK=SOCKET is the cross-process safety net.
    private static final String DB_PATH = ScratchPaths.path("overdrive_notif_h2");
    // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2 stores
    // (see SocHistoryDatabase.JDBC_URL for the full rationale).
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
            ";AUTO_COMPACT_FILL_RATE=50";

    private static final String TABLE = "notifications";

    // Retention. Notifications are tiny rows but fire often (every surveillance
    // motion, every charge edge), so bound BOTH by age and by absolute count so
    // a parked car in a busy lot can't grow the table without limit.
    private static final long RETENTION_DAYS = 120;
    private static final int MAX_ROWS = 5000;
    // Each cap-delete statement is bounded to PRUNE_BATCH rows so no single
    // DELETE plans/executes over an unbounded set. Within a single
    // cleanupOldData() run (on the prune scheduler thread, every 6h) we loop up
    // to PRUNE_MAX_PER_RUN rows so a large pre-existing table gets back under
    // cap in one tick rather than over many days. This runs only on the rare
    // catch-up path (table far over cap); the normal steady-state prune deletes
    // a handful of rows.
    private static final int PRUNE_BATCH = 500;
    private static final int PRUNE_MAX_PER_RUN = 20000;

    // Defensive query clamps — the handler already clamps, but the store stays
    // safe independent of its callers.
    private static final int MAX_PAGE_SIZE = 200;
    // Hard cap on a single bulk-delete so a hostile/oversized body can't build a
    // giant IN(...) statement; larger requests are chunked into batches of this.
    private static final int BULK_DELETE_CHUNK = 500;

    // Column list reused by every SELECT so the row mapper stays in lockstep.
    private static final String COLS =
            "id, ts, category, severity, title, body, tag, click_url, data";

    private static volatile NotificationStore instance;
    private static final Object SINGLETON_LOCK = new Object();

    // Guards ALL access to `connection` (writes on the bus thread, reads on HTTP
    // threads, prune on the scheduler thread).
    private final Object lock = new Object();
    private Connection connection;
    private volatile boolean isInitialized = false;
    // Distinct from isInitialized: gates reconnect() so a stale-connection
    // recovery attempt can't reopen the DB after stop() has begun teardown —
    // that would re-acquire the lock file just before JVM exit and orphan it
    // across restarts (same defense SocHistoryDatabase documents).
    private volatile boolean isRunning = false;
    private ScheduledExecutorService scheduler;

    private NotificationStore() {
        try {
            Class.forName("org.h2.Driver");
        } catch (Throwable t) {
            logger.error("H2 Driver not found for NotificationStore: " + t.getMessage(), t);
        }
    }

    public static NotificationStore getInstance() {
        if (instance == null) {
            synchronized (SINGLETON_LOCK) {
                if (instance == null) instance = new NotificationStore();
            }
        }
        return instance;
    }

    // ==================== LIFECYCLE ====================

    public void init() {
        if (isInitialized) return;
        synchronized (lock) {
            if (isInitialized) return;

            // Retry loop with stale-lock cleanup + backoff. The head unit is
            // frequently hard-killed (ACC-off yank, OOM, restart storms), which
            // can orphan overdrive_notif_h2.lock.db; a single getConnection
            // would then leave the log dark for the whole session. Mirrors
            // SocHistoryDatabase.init().
            int maxRetries = 3;
            int retryDelayMs = 500;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("SET CACHE_SIZE 4096");
                    }
                    createTable();
                    isInitialized = true;
                    isRunning = true;
                    logger.info("NotificationStore initialized via H2: " + DB_PATH);
                    break;
                } catch (Exception e) {
                    String msg = e.getMessage();
                    boolean isLockError = msg != null && (msg.contains("Locked by another process")
                            || msg.contains("lock.db") || msg.contains("already in use"));
                    if (isLockError && attempt < maxRetries) {
                        logger.warn("NotificationStore locked (attempt " + attempt + "/" + maxRetries
                                + "), cleaning stale locks...");
                        cleanupStaleLocks();
                        try {
                            Thread.sleep((long) retryDelayMs * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        logger.error("Failed to initialize NotificationStore: " + msg, e);
                        break;
                    }
                }
            }
        }
        if (!isInitialized) return;
        // Prune once at startup (outside the lock-held init so a slow delete
        // can't stall boot), then daily on a dedicated scheduler so retention
        // never rides the write path / blocks HTTP request threads.
        startPruneScheduler();
    }

    private void startPruneScheduler() {
        // Capture the executor in a local UNDER the lock and schedule on the
        // local — never on the `scheduler` field, which stop() can null between
        // the synchronized block and scheduleAtFixedRate (shutdown-during-boot).
        ScheduledExecutorService sched;
        synchronized (lock) {
            if (scheduler != null || !isRunning) return;
            sched = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NotifStorePrune");
                t.setDaemon(true);
                return t;
            });
            scheduler = sched;
        }
        try {
            sched.scheduleAtFixedRate(() -> {
                try {
                    cleanupOldData();
                } catch (Throwable t) {
                    logger.error("NotificationStore prune task error: " + t.getMessage(),
                            t instanceof Exception ? (Exception) t : new Exception(t));
                }
            }, 0, 6, TimeUnit.HOURS);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // stop() shut the executor down between creation and scheduling —
            // benign shutdown race; the store is being torn down anyway.
            logger.debug("NotificationStore prune scheduler rejected (shutting down)");
        }
    }

    /** Clean up a stale lock/trace file left by a crashed prior daemon. */
    private void cleanupStaleLocks() {
        try {
            java.io.File lockFile = new java.io.File(DB_PATH + ".lock.db");
            if (lockFile.exists()) {
                long ageMs = System.currentTimeMillis() - lockFile.lastModified();
                if (ageMs > 5 * 60 * 1000) {
                    if (lockFile.delete()) {
                        logger.info("Deleted stale NotificationStore lock (age " + (ageMs / 1000) + "s)");
                    }
                }
            }
            java.io.File traceFile = new java.io.File(DB_PATH + ".trace.db");
            if (traceFile.exists()) traceFile.delete();
        } catch (Exception e) {
            logger.debug("NotificationStore lock cleanup failed: " + e.getMessage());
        }
    }

    private void createTable() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id IDENTITY PRIMARY KEY," +
                "ts BIGINT NOT NULL," +
                "category VARCHAR(160) NOT NULL," +
                "severity VARCHAR(16) NOT NULL," +
                "title VARCHAR(512) NOT NULL," +
                "body CLOB," +
                "tag VARCHAR(256)," +
                "click_url VARCHAR(1024)," +
                "data CLOB" +
                ");"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notif_ts ON " + TABLE + "(ts);");
        }
    }

    /**
     * Reopen a stale/closed connection so the next request self-heals instead of
     * the log going permanently dark after one transient error. Caller MUST hold
     * {@link #lock}. Guarded by {@link #isRunning} so it can't reopen after
     * {@link #stop()} began (which would orphan the lock file at JVM exit).
     */
    private void reconnect() {
        if (!isRunning) return;
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                isInitialized = true;
                logger.debug("NotificationStore connection re-established");
            }
        } catch (Exception e) {
            logger.error("NotificationStore reconnect failed: " + e.getMessage(), e);
        }
    }

    public void stop() {
        // Flip isRunning FIRST so an in-flight reconnect() from a racing request
        // bails instead of reopening the DB we're about to close.
        isRunning = false;
        ScheduledExecutorService sched;
        synchronized (lock) {
            sched = scheduler;
            scheduler = null;
        }
        if (sched != null) {
            sched.shutdownNow();
        }
        synchronized (lock) {
            isInitialized = false;
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
                connection = null;
            }
        }
    }

    // ==================== WRITE ====================

    /** Persist one event. Called from the NotificationBus thread via HistorySink. */
    public void insert(NotificationEvent event) {
        insert(event, null);
    }

    /**
     * Persist one event with a caller-resolved click URL.
     *
     * <p>{@code resolvedUrl} lets the sink apply the registry's
     * {@code defaultClickUrl} for categories that publish a null clickUrl
     * (charging/door/tyre/SOH → their settings page) — the same fallback
     * PushSink applies to its push copy, so the Log tab's rows are clickable
     * for every category, not just surveillance/proximity. When null, we fall
     * back to the event's own clickUrl, then {@code data.url}.
     */
    public void insert(NotificationEvent event, String resolvedUrl) {
        if (event == null) return;
        synchronized (lock) {
            if (!isInitialized || connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + TABLE +
                    " (ts, category, severity, title, body, tag, click_url, data)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?);")) {
                ps.setLong(1, event.timestamp);
                ps.setString(2, event.category);
                ps.setString(3, event.severity.name().toLowerCase(java.util.Locale.US));
                ps.setString(4, clip(event.title, 512));
                ps.setString(5, event.body);
                ps.setString(6, event.tag);
                // URL precedence mirrors PushSink: explicit clickUrl → data.url
                // (surveillance/proximity set it there) → registry defaultClickUrl
                // (passed in as resolvedUrl by the sink) so vehicle-category rows
                // link to their settings page too.
                String url = event.clickUrl;
                if (url == null && event.data != null) url = event.data.optString("url", null);
                if (url == null) url = resolvedUrl;
                ps.setString(7, url);
                ps.setString(8, event.data != null ? event.data.toString() : null);
                ps.executeUpdate();
            } catch (Exception e) {
                logger.error("NotificationStore.insert failed: " + e.getMessage(), e);
                reconnect();   // self-heal a stale connection for the next write
            }
        }
    }

    // ==================== READ ====================

    /** Immutable page holder so the caller gets items + total atomically. */
    public static final class Page {
        public final JSONArray items;
        public final long total;
        Page(JSONArray items, long total) { this.items = items; this.total = total; }
    }

    /**
     * Paginated, newest-first list PLUS its total, both read inside ONE lock
     * acquisition so the page and its count can't be torn apart by a concurrent
     * insert/prune (which would otherwise make totalPages disagree with the
     * rows returned). Filters:
     * <ul>
     *   <li>{@code fromMs}/{@code toMs} — inclusive epoch-ms window (toMs=Long.MAX_VALUE = no upper bound)</li>
     *   <li>{@code categoryPrefix} — LIKE '{prefix}%' match on category (group filter), null = any</li>
     *   <li>{@code severity} — exact severity match (info/warn/critical), null = any</li>
     * </ul>
     */
    public Page listWithCount(long fromMs, long toMs, String categoryPrefix, String severity,
                              int limit, int offset) {
        JSONArray out = new JSONArray();
        long total = 0;
        // Defensive clamps so the store is safe regardless of caller.
        if (limit < 1) limit = 1;
        if (limit > MAX_PAGE_SIZE) limit = MAX_PAGE_SIZE;
        if (offset < 0) offset = 0;
        boolean hasPrefix = categoryPrefix != null && !categoryPrefix.isEmpty();
        boolean hasSev = severity != null && !severity.isEmpty();
        String likeArg = hasPrefix ? escapeLike(categoryPrefix) + "%" : null;
        String sevArg = hasSev ? severity.toLowerCase(java.util.Locale.US) : null;
        String where = " WHERE ts >= ? AND ts <= ?"
                + (hasPrefix ? " AND category LIKE ? ESCAPE '\\'" : "")
                + (hasSev ? " AND severity = ?" : "");
        synchronized (lock) {
            if (!isInitialized || connection == null) return new Page(out, 0);
            try {
                // Count first, then the page — same connection, same lock, so
                // they observe the same table snapshot.
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + TABLE + where)) {
                    int i = 1;
                    ps.setLong(i++, fromMs);
                    ps.setLong(i++, toMs);
                    if (hasPrefix) ps.setString(i++, likeArg);
                    if (hasSev) ps.setString(i++, sevArg);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) total = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT " + COLS + " FROM " + TABLE + where
                                + " ORDER BY ts DESC LIMIT ? OFFSET ?;")) {
                    int i = 1;
                    ps.setLong(i++, fromMs);
                    ps.setLong(i++, toMs);
                    if (hasPrefix) ps.setString(i++, likeArg);
                    if (hasSev) ps.setString(i++, sevArg);
                    ps.setInt(i++, limit);
                    ps.setInt(i++, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.put(rowToJson(rs));
                    }
                }
            } catch (Exception e) {
                logger.error("NotificationStore.listWithCount failed: " + e.getMessage(), e);
                reconnect();
                return new Page(new JSONArray(), 0);
            }
        }
        return new Page(out, total);
    }

    private JSONObject rowToJson(ResultSet rs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", rs.getLong("id"));
        o.put("ts", rs.getLong("ts"));
        o.put("category", rs.getString("category"));
        o.put("severity", rs.getString("severity"));
        o.put("title", rs.getString("title"));
        String body = rs.getString("body");
        o.put("body", body == null ? "" : body);
        String tag = rs.getString("tag");
        if (tag != null) o.put("tag", tag);
        String url = rs.getString("click_url");
        if (url != null) o.put("url", url);
        // Inline the stored data blob as a real object so the client gets
        // filename/counts/proximity/place without a second parse layer.
        String data = rs.getString("data");
        if (data != null && !data.isEmpty()) {
            try { o.put("data", new JSONObject(data)); }
            catch (Exception ignored) { /* legacy/corrupt row — omit data */ }
        }
        return o;
    }

    // ==================== DELETE ====================

    /** Delete one row by id. Returns true on success (also true if already gone). */
    public boolean deleteById(long id) {
        synchronized (lock) {
            if (!isInitialized || connection == null) return false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE id = ?;")) {
                ps.setLong(1, id);
                ps.executeUpdate();
                return true;
            } catch (Exception e) {
                logger.error("NotificationStore.deleteById failed: " + e.getMessage(), e);
                reconnect();
                return false;
            }
        }
    }

    /**
     * Bulk delete by ids. Returns rows removed. Chunked into {@link #BULK_DELETE_CHUNK}
     * so an oversized id array can't build a giant IN(...) statement or hold the
     * lock through one unbounded DELETE.
     */
    public int deleteBulk(long[] ids) {
        if (ids == null || ids.length == 0) return 0;
        synchronized (lock) {
            if (!isInitialized || connection == null) return 0;
            int removed = 0;
            try {
                for (int start = 0; start < ids.length; start += BULK_DELETE_CHUNK) {
                    int end = Math.min(start + BULK_DELETE_CHUNK, ids.length);
                    int n = end - start;
                    StringBuilder in = new StringBuilder();
                    for (int i = 0; i < n; i++) in.append(i == 0 ? "?" : ",?");
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE id IN (" + in + ");")) {
                        for (int i = 0; i < n; i++) ps.setLong(i + 1, ids[start + i]);
                        removed += ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                logger.error("NotificationStore.deleteBulk failed: " + e.getMessage(), e);
                reconnect();
            }
            return removed;
        }
    }

    /** Clear the whole log (optionally only within a from/to window). Returns rows removed. */
    public int clear(long fromMs, long toMs) {
        synchronized (lock) {
            if (!isInitialized || connection == null) return 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE ts >= ? AND ts <= ?;")) {
                ps.setLong(1, fromMs);
                ps.setLong(2, toMs);
                int n = ps.executeUpdate();
                logger.info("NotificationStore.clear removed " + n + " rows");
                return n;
            } catch (Exception e) {
                logger.error("NotificationStore.clear failed: " + e.getMessage(), e);
                reconnect();
                return 0;
            }
        }
    }

    // ==================== RETENTION ====================

    /**
     * Age + count based prune. Runs on the daily scheduler thread (never the
     * write/request path). The count-cap delete is bounded per pass so no single
     * lock-hold is unbounded; the scheduler re-runs until the table is under cap.
     */
    public void cleanupOldData() {
        synchronized (lock) {
            if (!isInitialized || connection == null) return;
            try {
                long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24L * 60 * 60 * 1000L);
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE ts < ?;")) {
                    ps.setLong(1, cutoff);
                    int aged = ps.executeUpdate();
                    if (aged > 0) logger.info("NotificationStore prune: " + aged + " rows older than "
                            + RETENTION_DAYS + " days");
                }
                // Count cap: drop the oldest rows beyond MAX_ROWS. Each DELETE is
                // bounded to PRUNE_BATCH (so no single statement holds the lock
                // through a huge delete), but we loop within this one invocation
                // up to PRUNE_MAX_PER_RUN so a large table gets back under cap in
                // one scheduler tick instead of over many days.
                long total;
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE + ";")) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
                if (total > MAX_ROWS) {
                    long toTrim = Math.min(total - MAX_ROWS, PRUNE_MAX_PER_RUN);
                    int trimmedTotal = 0;
                    while (toTrim > 0) {
                        long batch = Math.min(toTrim, PRUNE_BATCH);
                        int trimmed;
                        try (PreparedStatement ps = connection.prepareStatement(
                                "DELETE FROM " + TABLE + " WHERE id IN " +
                                "(SELECT id FROM " + TABLE + " ORDER BY ts ASC LIMIT ?);")) {
                            ps.setLong(1, batch);
                            trimmed = ps.executeUpdate();
                        }
                        trimmedTotal += trimmed;
                        if (trimmed < batch) break;   // table drained early
                        toTrim -= trimmed;
                    }
                    if (trimmedTotal > 0) {
                        logger.info("NotificationStore cap: trimmed " + trimmedTotal + " oldest rows (was "
                                + total + ", cap " + MAX_ROWS + ")");
                    }
                }
            } catch (Exception e) {
                logger.error("NotificationStore.cleanupOldData failed: " + e.getMessage(), e);
                reconnect();
            }
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        // Don't split a UTF-16 surrogate pair at the boundary.
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Escape LIKE metacharacters so a category prefix can't act as a wildcard. */
    private static String escapeLike(String s) {
        if (s == null) return null;
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
