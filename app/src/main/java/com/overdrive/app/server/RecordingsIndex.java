package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H2-backed index of every .mp4 recording on disk + its sidecar metadata.
 *
 * <p>Replaces the prior model where every list query walked all dirs and
 * parsed every JSON sidecar — that was O(N) disk + O(N) JSON parse per
 * request, plus a 5-second in-memory cache that didn't survive cross-UID
 * reads. With ~1000 clips the request took ~2 minutes; with 5000+ it
 * was unusable.
 *
 * <p>Architecture: pure-Java H2 file at {@code /data/local/tmp/overdrive_recordings_h2}.
 * Same pattern as {@link com.overdrive.app.trips.TripDatabase}: daemon UID
 * 2000 owns the file, app UID reads via the {@code /api/recordings} HTTP
 * surface (not direct JDBC). Cross-UID reads go through HTTP only —
 * {@code FILE_LOCK=SOCKET} is for cross-process coordination within the
 * daemon JVM, not cross-UID JDBC.
 *
 * <p><b>Backward compat:</b>
 * <ul>
 *   <li>Legacy clips with no sidecar are indexed with all sidecar columns
 *       NULL/0. They are visible in unfiltered list queries, but any
 *       explicit severity / proximity / class filter intentionally
 *       excludes them — SQL evaluates {@code NULL IN (...)} as UNKNOWN, so
 *       the WHERE clauses built by {@code buildWhere()} drop NULL rows.
 *       Clients must clear those filters to see legacy clips; this is by
 *       design so filtered views show only metadata-bearing rows.
 *   <li>First boot after upgrade has empty index; warmup walks every dir
 *       once (background, with progress) and populates it. Costs the same
 *       ~2 min as today, paid exactly once. Subsequent boots see all rows.
 *   <li>A clip on disk with no row is repaired on-demand by the API
 *       handler when it spots the gap during a list query.
 *   <li>A row whose mp4 is gone is lazy-deleted on read.
 * </ul>
 *
 * <p><b>Concurrency:</b>
 * <ul>
 *   <li>One H2 connection, accessed serially via internal monitor.
 *       H2's default isolation already gives us per-statement consistency,
 *       and PreparedStatement / ResultSet are NOT thread-safe.
 *   <li>{@link #upsert} / {@link #remove} / {@link #queryRecordings}
 *       all acquire the same internal monitor on this object.
 *   <li>Warmup runs on a single background thread — parallel walking
 *       isn't worth the bug surface for a one-shot operation.
 * </ul>
 *
 * <p><b>Thread model:</b> Construction + init() on the daemon main
 * startup thread. After that all access is via the daemon HTTP worker
 * pool plus one warmup thread plus FileObserver callback threads.
 */
public final class RecordingsIndex {

    private static final String TAG = "RecordingsIndex";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DB_PATH = "/data/local/tmp/overdrive_recordings_h2";
    // DB_CLOSE_ON_EXIT=FALSE to avoid H2's JVM shutdown hook racing the
    // daemon close path. Same justification as TripDatabase — the orphaned
    // lock file would otherwise block the next CameraDaemon boot with
    // "Locked by another process".
    //
    // FILE_LOCK=SOCKET: process-level lock via a localhost socket, NOT
    // suitable for cross-UID coordination. App UID never opens this DB
    // directly; it reads via /api/recordings.
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";

    // Filename patterns mirror RecordingsApiHandler exactly. Kept in sync
    // there too because the parser is the single point of truth — any
    // change to filename grammar updates both.
    private static final Pattern CAM_PATTERN =
            Pattern.compile("cam(\\d+)?_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern REPLAY_PATTERN =
            Pattern.compile("replay_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern EVENT_PATTERN =
            Pattern.compile("event_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern PROXIMITY_PATTERN =
            Pattern.compile("proximity_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern DVR_PATTERN =
            Pattern.compile("dvr_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");

    // Filename-stamp format. Locale.US for the same reason as everywhere
    // else: the writer formats with Locale.US, and parsing under e.g. Thai
    // locale interprets the year as Buddhist Era and dumps every clip
    // ~543 years off.
    private static final ThreadLocal<SimpleDateFormat> FMT_FILENAME =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US));

    // Schema version. Bump when the column layout changes; createSchema()
    // runs ALTER TABLE ADD COLUMN IF NOT EXISTS for every additive change so
    // existing on-disk DBs migrate forward without a wipe.
    //   v1 → v2: added the `storage` column (INTERNAL / SD_CARD / USB), so the
    //            recordings library can filter by physical volume.
    //   v2 → v3: replay_* rows get their own type='replay' (previously folded
    //            into 'normal') so the UIs can list instant replays in a
    //            dedicated Replays tab instead of mixed with dashcam loops.
    //            Migration is a data UPDATE, not a column change — see
    //            createSchema().
    private static final int SCHEMA_VERSION = RecordingsIndexSchema.VERSION;

    // Singleton — one index per daemon process.
    private static volatile RecordingsIndex INSTANCE;

    public static synchronized RecordingsIndex getInstance() {
        if (INSTANCE == null) INSTANCE = new RecordingsIndex();
        return INSTANCE;
    }

    private Connection connection;
    private volatile boolean initialized = false;

    // Set by close() so a concurrent ensureOpen() on an HTTP worker can't
    // resurrect the DB during daemon teardown and orphan the lock file.
    private volatile boolean shuttingDown = false;

    // How many times H2 closed the store under us and ensureOpen() re-opened
    // it. Surfaced on /api/recordings/stats for field diagnosis — a nonzero
    // value means something is interrupting the DB write threads.
    private int reconnectCount = 0;

        // All asynchronous repair sources converge here. A burst before execution
        // becomes one pass; requests arriving during that pass become one follow-up.
        private final java.util.concurrent.ConcurrentLinkedQueue<String> reconcileReasons =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final CoalescingTaskRunner reconcileRequests;
        // Synchronous warmup reconciliation and asynchronous repair must not walk
        // the same FUSE roots concurrently.
        private final Object reconcileExecutionLock = new Object();

    // ---------------- queryStats() memo ----------------
    //
    // WHY: queryStats() is a full-table `GROUP BY type` with SUM(size_bytes).
    // No index covers the SUM (idx_rec_type_ts is (type, ts_ms DESC)), so H2
    // reads the base row of EVERY recording per call — O(N) in library size.
    // Three clients poll it concurrently (recording.js + surveillance.js at
    // 10s, events.js/RecordingsFragment warming poll at 2-10s), so a large
    // library pays several full scans every 10s forever while a page is open.
    //
    // The memo is exact rather than time-based-stale: it is invalidated by
    // every mutation path (upsertRow / remove / reconcile's bulk delete), so a
    // hit can only be served when nothing has changed the table since the
    // scan. That keeps the "a just-finalized clip shows up on the very next
    // poll" property the storage card depends on, and it means we do NOT need
    // to reason about a TTL vs poll-cadence interaction (the mistake that
    // makes StorageManager's 5s CATEGORY_STAT_CACHE_MS miss on every 10s poll).
    //
    // Guarded by the singleton monitor — every reader/writer below is already
    // synchronized, so no extra lock is needed and none is held across I/O.
    private Stats cachedStats = null;
    private long cachedStatsTodayStart = 0L;

    /** Drop the {@link #queryStats()} memo. Call from every path that changes
     *  the recordings table. Cheap — one field write. */
    private void invalidateStatsCache() {
        cachedStats = null;
    }

    // ---------------- removal tombstones ----------------
    //
    // WHY: upsert(File) parses OUTSIDE the monitor (see its javadoc), so the
    // check-then-write is no longer atomic against a concurrent remove(). Every
    // delete path deletes the FILE FIRST and calls remove() second
    // (StorageManager's reaper, RecordingsApiHandler.deleteRecording), so a
    // parse parked ~30ms on a FUSE sidecar read can wake up AFTER the delete and
    // MERGE the row back in — a ghost row for a file that no longer exists.
    // That resurrects exactly the failure remove()'s own comment warns about:
    // playback 404s, and queryStats() counts the dead clip's bytes (inflating
    // the storage card) until the next reconcile, up to an HOUR later.
    //
    // Fix: remove() records the filename against a monotonically increasing
    // sequence. upsert() samples the sequence BEFORE parsing; upsertRow() then
    // refuses the MERGE if that filename was removed after the sample. Costs one
    // map lookup under the monitor — no extra stat()/FUSE I/O, which is the
    // whole point of not re-checking File.isFile() here.
    //
    // Recorded UNCONDITIONALLY, even when the DELETE affected 0 rows: the
    // dangerous interleaving is precisely the one where the in-flight upsert
    // hasn't MERGEd yet, so there is no row to delete at that moment.
    //
    // A tombstone is NOT a permanent block on the filename. The gate is a
    // strict `removedAt > seqSampled`, so it only rejects parses that STARTED
    // BEFORE the removal. A genuine later re-add of the same name (manual replay
    // re-record, a restored file, reconcile finding it back on disk) samples a
    // sequence at or after the tombstone and proceeds normally. That is why this
    // is a sequence comparison and not a "was it ever deleted" set.
    //
    // All four fields/methods here are touched only under the singleton monitor
    // (remove(), upsertRow(), currentRemovalSeq() are all synchronized), so the
    // plain long and HashMap need no extra synchronization.
    private long removalSeq = 0L;
    private final java.util.Map<String, Long> removalTombstones = new java.util.HashMap<>();
    /** Sentinel for callers with no parse window to protect (warmup, which
     *  produces Rows on its parser pool and keeps its pre-existing semantics). */
    private static final long NO_REMOVAL_GATE = -1L;
    // Keep the map bounded. Parse windows are milliseconds; entries this far
    // back cannot have an in-flight parse still referencing them.
    private static final int TOMBSTONE_SOFT_CAP = 2048;
    private static final int TOMBSTONE_KEEP = 1024;

    /** Sample the removal sequence. Taken before an unsynchronized parse. */
    private synchronized long currentRemovalSeq() {
        return removalSeq;
    }

    /** Record that {@code recordingId} was removed, and prune old entries. */
    private void noteRemoval(String recordingId) {
        removalTombstones.put(recordingId, ++removalSeq);
        if (removalTombstones.size() > TOMBSTONE_SOFT_CAP) {
            long cutoff = removalSeq - TOMBSTONE_KEEP;
            removalTombstones.entrySet().removeIf(e -> e.getValue() <= cutoff);
        }
    }

    /** True when {@code filename} was removed after {@code seqSampled}, i.e. a
     *  delete landed while the caller was parsing and its Row is now stale. */
    private boolean removedSince(String recordingId, long seqSampled) {
        if (seqSampled == NO_REMOVAL_GATE) return false;
        Long removedAt = removalTombstones.get(recordingId);
        return removedAt != null && removedAt > seqSampled;
    }

    // Timestamp of the last reopen ATTEMPT (successful or not); throttles
    // retries so a sick store can't trigger a fresh getConnection() +
    // createSchema() on every polled HTTP request. 0 = never attempted.
    private long lastReopenAttemptAtMs = 0L;
    private static final long REOPEN_COOLDOWN_MS = 10_000L;

    // Latched true the first time init() opens the DB successfully. This is
    // what licenses ensureOpen() to heal a dead connection: it distinguishes
    // "this index was never initialized, don't touch the file" from "the
    // index worked before and H2 closed it under us, so re-open it". Without
    // it, a failed reopen (which clears `initialized`) would permanently
    // disable self-healing.
    private volatile boolean everInitialized = false;

    // Latched when a statement failed with a store-level error AND the
    // subsequent reopen+retry also failed. Cleared by the next statement that
    // completes. This is what keeps the honesty invariant when the store is
    // broken in a way Connection.isClosed() cannot see (corrupt page / IO
    // error / MVStore panic): without it the connection still looks open, so
    // the API would serve the empty failureValue as an authoritative 200.
    private volatile boolean storeUnhealthy = false;

    // Latched when init() exhausted its retries. Distinguishes "never tried
    // yet" (cold boot — answer normally, the client retry converges) from
    // "tried and permanently failed" (must be reported as unavailable, not as
    // an empty library).
    private volatile boolean initFailedPermanently = false;

    // Warmup state — single source of truth for whether the API should
    // return {warming: true} on /api/recordings until the first scan
    // completes. Volatile reads are fine; only one writer (the warmup
    // thread).
    private final AtomicBoolean warmupRunning = new AtomicBoolean(false);
    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final AtomicInteger warmupTotal = new AtomicInteger(0);
    private final AtomicInteger warmupDone = new AtomicInteger(0);

    private RecordingsIndex() {
        reconcileRequests = new CoalescingTaskRunner(command -> {
            Thread thread = new Thread(command, "RecordingsIndexReconcile");
            thread.setDaemon(true);
            thread.start();
        }, this::runRequestedReconcile);
    }

    // =================================================================
    // Lifecycle
    // =================================================================

    /**
     * Open + migrate the H2 file. Idempotent. Called from CameraDaemon
     * init. Returns true if the DB is ready for queries; false on
     * unrecoverable error (UI falls back to direct-FS mode).
     */
    public synchronized boolean init() {
        if (initialized) return true;
        // A fresh init() is an explicit "come back up", so clear the shutdown
        // latch — but only on the path that actually opens the DB, and only
        // after the already-initialized short-circuit above. Clearing it
        // unconditionally would let any init() caller re-arm self-healing
        // during teardown (close() runs at CameraDaemon:2100 while the HTTP
        // server is still accepting requests), re-acquiring the lock file and
        // orphaning a .lock.db that blocks the next daemon boot.
        shuttingDown = false;
        logger.info("Initializing RecordingsIndex at " + DB_PATH);

        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("H2 driver not found — check gradle deps", e);
            return false;
        }

        // Same retry-on-stale-lock pattern as TripDatabase. SIGKILL of the
        // previous daemon can leave a stale .lock.db that blocks reopen.
        int maxRetries = 3;
        int retryDelayMs = 1000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                logger.info("H2 recordings connection established");
                try (Statement stmt = connection.createStatement()) {
                    // 8 MiB cache — same as TripDatabase. Tuned for the
                    // ~1000-row typical workload; queries are mostly
                    // index seeks + small fact-table reads, the cache
                    // mainly absorbs index pages.
                    stmt.execute("SET CACHE_SIZE 8192");
                }
                createSchema();
                initialized = true;
                everInitialized = true;
                logger.info("RecordingsIndex initialized (schema v" + SCHEMA_VERSION + ")");
                return true;
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean lockErr = msg != null
                        && (msg.contains("Locked by another process")
                                || msg.contains("lock.db")
                                || msg.contains("already in use"));
                // Corruption: a hard power-cut / SIGKILL mid-write can leave the
                // MVStore file half-flushed. H2 surfaces this as "File corrupted"
                // / IO_EXCEPTION (SQLState-adjacent code 90030). This is NOT
                // recoverable by retrying the same file — the previous behaviour
                // (return false) left `initialized=false` for the whole daemon
                // lifetime, and because there is no direct-FS listing fallback
                // anymore, EVERY /api/recordings query returned an empty list
                // even with .mp4 files present on disk → the app/web UI showed
                // no recordings at all. The index is a pure derived cache of the
                // filesystem (warmup rebuilds it by walking every dir), so the
                // safe recovery is to wipe the corrupt store and reopen fresh;
                // warmupAsync() then repopulates every row from disk.
                boolean corruptErr = msg != null
                        && (msg.contains("File corrupted")
                                || msg.contains("90030")
                                || msg.contains("Corrupt")
                                || msg.contains("Unable to read")
                                || msg.contains("MVStoreException"));
                if (lockErr && attempt < maxRetries) {
                    logger.warn("Index DB locked (attempt " + attempt + "/" + maxRetries + "), cleaning stale locks");
                    cleanupStaleLocks();
                    try { Thread.sleep((long) retryDelayMs * attempt); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else if (corruptErr && attempt < maxRetries) {
                    logger.error("Index DB corrupted (attempt " + attempt + "/" + maxRetries
                            + "), wiping store to rebuild from filesystem: " + msg, e);
                    // A partially-opened connection can hold an OS handle on the
                    // file — close it before deleting so the unlink actually frees
                    // the inode and the reopen sees a clean directory.
                    closeQuietly();
                    wipeCorruptStore();
                    // No sleep needed — this isn't a transient contention error.
                } else {
                    logger.error("Failed to init RecordingsIndex: " + msg, e);
                    // Permanent: the API must report the index as unavailable
                    // rather than serving an empty list as authoritative.
                    // There is no direct-FS listing fallback in the handler
                    // anymore, so a silent empty response here would look
                    // exactly like "you have no recordings".
                    initFailedPermanently = true;
                    return false;
                }
            }
        }
        // Retries exhausted without success — same reasoning as above.
        initFailedPermanently = true;
        return false;
    }

    public synchronized void close() {
        // Mark the shutdown intent BEFORE dropping the connection so a
        // concurrent ensureOpen() on an HTTP worker can't race in and
        // re-open the DB while we're tearing down — that would re-acquire
        // the lock file just before JVM exit and orphan a .lock.db that
        // blocks the next daemon boot. Same defense as
        // SocHistoryDatabase.reconnect()'s !isRunning guard.
        shuttingDown = true;
        if (connection != null) {
            try { connection.close(); }
            catch (Exception e) { logger.warn("Index close failed: " + e.getMessage()); }
            connection = null;
        }
        initialized = false;
    }

    /**
     * Ensure the shared JDBC connection is alive, re-opening it if H2 has
     * closed it underneath us. Returns true when the DB is usable.
     *
     * <p><b>Why this exists:</b> {@code initialized} only tracks whether
     * {@link #init()} succeeded — it is NOT a liveness signal. H2 can close
     * the store while the daemon keeps running: any thread interrupt during
     * an MVStore write surfaces as {@code ClosedByInterruptException} on the
     * backing {@link java.nio.channels.FileChannel}, and H2 responds by
     * closing the whole database. The flag stays true, so every subsequent
     * query sailed past the {@code if (!initialized)} guards straight into
     * "The database has been closed [90098-224]".
     *
     * <p>Observed in the field (log_4BWJLHG3, 2026-07-25): the connection
     * died at 19:50:53 and every recordings query failed for the following
     * two hours of daemon uptime — 550 errors across queryRecordings /
     * queryCount / queryStats / upsertRow / remove / reconcile. Because the
     * query methods swallow the failure and return an empty list, the API
     * answered HTTP 200 {@code {success:true, recordings:[], totalCount:0}}
     * and events.html rendered "no recordings" while the clips sat on disk.
     * 13 clips (3 of them sentry events) were saved but never indexed.
     *
     * <p>Reconnecting is safe and cheap: the H2 file is the same, the store
     * re-opens with the existing rows, and this is the only opener of that
     * DB inside the daemon. On a wiped/corrupt store {@link #init()}'s
     * recovery path still applies on the next daemon boot.
     *
     * <p>Callers must hold this object's monitor (every public entry point
     * is {@code synchronized}); the monitor serializes the connection swap
     * against in-flight PreparedStatements, matching
     * {@link com.overdrive.app.trips.TripDatabase#ensureConnection()}.
     */
    private synchronized boolean ensureOpen() {
        if (shuttingDown) return false;
        // Never opened at all (init() not run yet, or it failed outright):
        // do NOT create the DB here. Opening it from an arbitrary HTTP worker
        // would race the daemon's own init()/warmup sequencing.
        if (!everInitialized) return false;
        // A latched store-level failure means the connection is open but the
        // store behind it is broken. Try a reopen (throttled) rather than
        // trusting isClosed(), which cannot see this class of failure.
        if (storeUnhealthy) return reopen("unhealthy store");
        try {
            if (connection != null && !connection.isClosed()) return true;
        } catch (Exception e) {
            // isClosed() itself failed — treat as dead and re-open below.
            logger.debug("ensureOpen: isClosed() probe failed: " + e.getMessage());
        }
        return reopen("connection closed");
    }

    /**
     * Unconditionally drop and re-open the connection. Used both by
     * {@link #ensureOpen()} and — critically — by the closed-store retry
     * path, because H2 does NOT reliably report a dead store through
     * {@link Connection#isClosed()}: when the MVStore panics, the JDBC
     * session can still consider itself open while every statement throws
     * 90098. An {@code isClosed()}-only check would therefore never heal the
     * exact failure this class hit in the field, so the recovery trigger is
     * the error from the statement, not the connection's self-report.
     *
     * <p>Throttled by {@link #REOPEN_COOLDOWN_MS}: if the store is genuinely
     * unopenable (file deleted, disk full) we must not attempt a fresh
     * {@code getConnection()} on every HTTP request — the web UI polls every
     * ~1.5 s during a failure and each failed open does real file I/O.
     * Between attempts callers fail fast and the API reports
     * {@code indexUnavailable}.
     *
     * @return true when the connection is usable afterwards.
     */
    private synchronized boolean reopen(String reason) {
        if (shuttingDown) return false;
        long now = System.currentTimeMillis();
        // Cooldown applies to ANY recent reopen attempt, successful or not.
        // Gating on failures alone was useless against the very failure mode
        // this class hit: a store that re-opens fine but then fails every
        // statement would do one full close+open+createSchema PER statement,
        // unbounded. One attempt per cooldown window bounds that to a trickle
        // while still healing a genuinely transient close within seconds.
        if (lastReopenAttemptAtMs > 0 && (now - lastReopenAttemptAtMs) < REOPEN_COOLDOWN_MS) {
            return false;
        }
        lastReopenAttemptAtMs = now;
        try {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { /* already dead */ }
                connection = null;
            }
            connection = DriverManager.getConnection(JDBC_URL, "sa", "");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET CACHE_SIZE 8192");
            }
            // Re-assert the schema: if the store was wiped (corrupt-recovery
            // on a previous open, or an external delete of the .mv.db) the
            // reopened DB is empty and every statement would fail on a
            // missing table. createSchema() is idempotent — it is already run
            // on every normal open.
            createSchema();
            initialized = true;
            // Fresh connection + asserted schema: clear the unhealthy latch so
            // the next statement is trusted again. If the store is still
            // broken, withRetry re-latches it on the next failure.
            storeUnhealthy = false;
            reconnectCount++;
            logger.warn("Recordings index re-opened after " + reason
                    + " (reconnect #" + reconnectCount + "). Kicking a reconcile to"
                    + " re-index anything missed while it was down.");
            // The dead window silently dropped upserts/removes, so the index
            // now drifts from disk. Heal it in the background.
            requestReconcile("store-reconnect");
            return true;
        } catch (Exception e) {
            // CRITICAL: drop the half-open connection. getConnection() may
            // have succeeded and createSchema() then failed (e.g. a wiped
            // store, or the store dying again mid-DDL). Leaving that live
            // handle in the field would make ensureOpen() see a non-null,
            // non-closed connection and report the index HEALTHY forever,
            // while every statement failed with "Table RECORDINGS not found"
            // — which is NOT a closed-store error, so withRetry would return
            // empty results and /api/recordings would go back to answering
            // 200 {recordings:[]}. That is exactly the bug being fixed, so
            // the invariant is: a failed reopen leaves connection == null.
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { /* best-effort */ }
                connection = null;
            }
            initialized = false;
            logger.error("Failed to re-open recordings index (" + reason + "): "
                    + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Run one SQL body against the index, healing a closed store once.
     *
     * <p>Every read/write path funnels through here so the recovery
     * behaviour is identical everywhere: fail fast when the index is
     * unavailable, and when a statement dies specifically because H2 closed
     * the database, re-open and run the body exactly once more.
     *
     * <p>Bodies must be self-contained (build and return their own result
     * rather than mutating a captured collection) so the retry cannot
     * double-append into a partially-filled result.
     */
    private synchronized <T> T withRetry(String op, T failureValue, SqlBody<T> body) {
        if (!ensureOpen()) return failureValue;
        try {
            T result = body.run();
            storeUnhealthy = false;   // a statement completed — store is alive
            return result;
        } catch (Exception e) {
            if (isClosedStoreError(e)) {
                logger.warn(op + ": store was closed mid-statement — reconnecting");
                if (reopen("closed store during " + op)) {
                    try {
                        T result = body.run();
                        storeUnhealthy = false;
                        return result;
                    } catch (Exception e2) {
                        // Reconnected and STILL failing: the store is broken
                        // in a way a reopen can't fix. Latch it so
                        // isAvailable() reports the index down instead of
                        // letting the caller's empty failureValue be served as
                        // an authoritative 200.
                        storeUnhealthy = true;
                        logger.error(op + " failed after reconnect: " + e2.getMessage(), e2);
                        return failureValue;
                    }
                }
                storeUnhealthy = true;
                return failureValue;
            }
            // Log the throwable, not just getMessage(): the stack trace is
            // what identifies WHY H2 closed the store (e.g. a thread
            // interrupt surfacing as ClosedByInterruptException under the
            // MVStore write). The message-only logging in the original code
            // made the field failure undiagnosable.
            logger.error(op + " failed: " + e.getMessage(), e);
            return failureValue;
        }
    }

    /** SQL body for {@link #withRetry}. */
    private interface SqlBody<T> {
        T run() throws Exception;
    }

    /**
     * Truncate to at most {@code max} chars so a value can never exceed its
     * VARCHAR column width. An over-long value fails the whole MERGE with
     * VALUE_TOO_LONG, which would drop the clip from the index entirely — a
     * truncated place label is strictly better than an invisible recording.
     */
    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Queue a background reconcile without stacking FUSE walks.
     *
     * @return true when the request was queued or merged into a running worker;
     *         false during shutdown or when the worker could not be started.
     */
    public boolean requestReconcile(String reason) {
        if (shuttingDown) return false;
        if (reason != null && !reason.isEmpty()) reconcileReasons.offer(reason);
        try {
            reconcileRequests.request();
            return true;
        } catch (Throwable failure) {
            logger.warn("Could not schedule recordings reconcile (" + reason + "): "
                    + failure.getMessage());
            // The runner keeps its pending bit and the reason remains queued.
            // A later request retries scheduling without losing diagnostics.
            return false;
        }
    }

    private void runRequestedReconcile() {
        StringBuilder reasons = new StringBuilder();
        String reason;
        while ((reason = reconcileReasons.poll()) != null) {
            if (reasons.length() > 0) reasons.append(',');
            reasons.append(reason);
        }
        if (reasons.length() > 0) {
            logger.debug("Running requested reconcile: " + reasons);
        }
        try {
            reconcile();
        } catch (Throwable failure) {
            logger.warn("Requested reconcile failed: " + failure.getMessage());
        }
    }

    /**
     * True when the index is currently usable for queries. Distinct from
     * {@link #warmupState()}: warmup means "populating, ask again soon",
     * whereas this returning false means "the DB is down, the empty result
     * you are about to get is NOT authoritative." The API surfaces it as
     * {@code indexUnavailable} so clients can show an error instead of a
     * plausible-looking empty library.
     */
    public synchronized boolean isAvailable() {
        return ensureOpen();
    }

    /**
     * True when the index should be reported to clients as unavailable, i.e.
     * an empty result from it would be a lie.
     *
     * <p>Tri-state, so a cold boot isn't mistaken for a failure:
     * <ul>
     *   <li>opened before but not usable now → unavailable (H2 closed or broke
     *       the store under us).
     *   <li>{@link #init()} tried and permanently failed → unavailable. There
     *       is no direct-FS listing fallback in the API anymore, so answering
     *       "empty" here would read as "you have no recordings" forever.
     *   <li>never tried yet (daemon still starting — the HTTP server accepts
     *       requests before the index init thread runs) → NOT unavailable;
     *       answer normally so the existing warming / repair-on-read retry
     *       path converges without a manual reload.
     * </ul>
     */
    public synchronized boolean isUnavailableForClients() {
        if (isAvailable()) return false;
        return everInitialized || initFailedPermanently;
    }

    /** Number of times H2 closed the store and we re-opened it. Diagnostics. */
    public synchronized int reconnectCount() {
        return reconnectCount;
    }

    /**
     * Close the JDBC connection without touching {@link #initialized} or
     * logging at error level — used during the init retry loop when a
     * partially-opened connection must be dropped before wiping a corrupt
     * store. {@link #close()} would flip {@code initialized=false} (already
     * false here) and is a slightly heavier "public shutdown" contract.
     */
    private void closeQuietly() {
        if (connection != null) {
            try { connection.close(); }
            catch (Exception e) { logger.debug("closeQuietly failed: " + e.getMessage()); }
            connection = null;
        }
    }

    /**
     * Delete every H2 store artifact for a corrupt recordings index so the
     * next {@link DriverManager#getConnection} call creates a fresh empty DB.
     * The index is a derived cache of the filesystem — warmupAsync() rebuilds
     * every row by walking the recording dirs — so wiping it loses no
     * authoritative data, only the pre-parsed metadata cache.
     *
     * <p>MVStore uses {@code <path>.mv.db}; older PageStore builds and the
     * trace/lock siblings are removed too so no half-written companion file
     * confuses the reopen.
     */
    private void wipeCorruptStore() {
        String[] suffixes = {
            ".mv.db",        // MVStore (current H2 default)
            ".mv.db.tmp",    // in-progress compaction temp
            ".h2.db",        // legacy PageStore
            ".trace.db",
            ".lock.db",
            ".newFile",      // MVStore atomic-write scratch
            ".tempFile"
        };
        for (String sfx : suffixes) {
            try {
                File f = new File(DB_PATH + sfx);
                if (f.exists() && f.delete()) {
                    logger.warn("Wiped corrupt index artifact: " + f.getName());
                }
            } catch (Exception e) {
                logger.debug("wipeCorruptStore(" + sfx + ") failed: " + e.getMessage());
            }
        }
    }

    private void cleanupStaleLocks() {
        try {
            File lock = new File(DB_PATH + ".lock.db");
            if (lock.exists()) {
                long age = System.currentTimeMillis() - lock.lastModified();
                if (age > 5 * 60 * 1000L && lock.delete()) {
                    logger.info("Deleted stale lock file (age " + (age / 1000) + "s)");
                }
            }
            File trace = new File(DB_PATH + ".trace.db");
            if (trace.exists()) trace.delete();
        } catch (Exception e) {
            logger.debug("Lock cleanup failed: " + e.getMessage());
        }
    }

    private void createSchema() throws Exception {
        RecordingsIndexSchema.ensure(connection);
        invalidateStatsCache();
    }

    // =================================================================
    // Mutators
    // =================================================================

    /**
     * Upsert one recording row. Called by:
     * <ul>
     *   <li>Warmup, on first scan after a fresh boot.
     *   <li>FileObserver, after CREATE/MOVED_TO of a finalised .mp4.
     *   <li>SidecarGeoUpdater, after geo merge into the JSON.
     *   <li>The API handler's repair-on-read path.
     * </ul>
     *
     * <p>NOT {@code synchronized}: {@link #parse} costs ~30ms/file (sidecar
     * JSON read + metadata parse on a FUSE mount) and MUST run outside the
     * singleton monitor. Holding it across the parse is what made
     * /api/recordings/stats slow on large libraries — reconcile() calls this
     * per unindexed file, so a K-file drift chopped the monitor into K windows
     * of ~30ms and every concurrent queryStats/queryRecordings/queryDates
     * request queued behind them. That defeated the explicit intent documented
    * in reconcile(): filesystem fingerprinting stays outside the monitor so
    * FUSE metadata stalls cannot serialize every query behind a parse.
     *
     * <p>Only the short MERGE ({@link #upsertRow}) is synchronized — the same
     * split the warmup pipeline already uses (4 unsynchronized parser threads
     * feeding one writer that calls upsertRow). ensureOpen() is left to
     * upsertRow's own withRetry: probing liveness here would take and release
     * the monitor for no benefit, since the connection can close between that
     * probe and the MERGE anyway.
     *
     * <p>Dropping {@code synchronized} also dropped the ATOMICITY of
     * check-then-write against a concurrent {@link #remove}, which would let a
     * parse that started before a delete resurrect the row afterwards. The
     * removal-sequence sample below restores it without adding any I/O under the
     * monitor — see the tombstone block near the top of this class.
     *
     * @return true if the row was inserted/updated, false on parse failure or
     *         when the file was removed while we were parsing.
     */
    public boolean upsert(File mp4) {
        if (mp4 == null || !mp4.isFile() || !mp4.canRead()) return false;
        if (!mp4.getName().endsWith(".mp4")) return false;

        // Sample BEFORE parsing so any remove() landing during the parse is
        // detected by upsertRow and the stale MERGE is refused.
        long seq = currentRemovalSeq();
        Row row = parse(mp4);   // OUTSIDE the monitor — see above.
        if (row == null) return false;
        return upsertRow(row, seq);
    }

    /**
     * Single-row MERGE using a pre-parsed {@link Row}. Used by the warmup
     * pipeline (parser pool produces Rows, single writer drains them) so
     * the parse cost runs concurrently while writes stay serialised on
     * one JDBC connection.
     *
     * <p>Warmup keeps the ungated behaviour it always had: it runs before the
     * index is serving and its Rows aren't racing user-driven deletes.
     */
    synchronized boolean upsertRow(Row row) {
        return upsertRow(row, NO_REMOVAL_GATE);
    }

    /**
     * @param seqSampled removal sequence sampled before the caller's parse, or
     *                   {@link #NO_REMOVAL_GATE} to skip the staleness check.
     */
    synchronized boolean upsertRow(Row row, long seqSampled) {
        if (row == null) return false;
        // A delete landed while the caller was parsing: the file is already gone
        // from disk, so MERGEing this Row would create a ghost row that 404s on
        // playback and inflates the storage card until the next reconcile.
        if (removedSince(row.recordingId, seqSampled)) {
            logger.debug("upsertRow: dropping stale row for removed " + row.filename);
            return false;
        }
        // Writes are the path where a silent failure is permanent: a dropped
        // upsert means the clip exists on disk but never appears in
        // events.html until some later reconcile happens to catch it. So a
        // closed store here is reconnected and the MERGE retried once.
        final Row r = row;
        return withRetry("upsertRow(" + row.filename + ")", Boolean.FALSE, () -> {
            String sql =
                "MERGE INTO recordings (recording_id, filename, abs_path, root_id, volume_id,"
                + " relative_path, root_rank, is_available, type, camera_id, ts_ms, size_bytes,"
                + " mp4_mtime, sidecar_mtime, schema_version, peak_severity, peak_proximity,"
                + " person_count, vehicle_count, bike_count, animal_count, hero_thumb,"
                + " actor_classes, place_short, place_medium, place_display, place_country,"
                + " place_source, start_lat, start_lng, ymd, storage) KEY(recording_id) VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, r.recordingId);
                ps.setString(2, r.filename);
                ps.setString(3, r.absPath);
                ps.setString(4, r.rootId);
                ps.setString(5, r.volumeId);
                ps.setString(6, r.relativePath);
                ps.setInt(7, r.rootRank);
                ps.setBoolean(8, true);
                ps.setString(9, r.type);
                ps.setInt(10, r.cameraId);
                ps.setLong(11, r.tsMs);
                ps.setLong(12, r.sizeBytes);
                ps.setLong(13, r.mp4Mtime);
                ps.setLong(14, r.sidecarMtime);
                ps.setInt(15, r.schemaVersion);
                setNullableString(ps, 16, r.peakSeverity);
                setNullableString(ps, 17, r.peakProximity);
                ps.setInt(18, r.personCount);
                ps.setInt(19, r.vehicleCount);
                ps.setInt(20, r.bikeCount);
                ps.setInt(21, r.animalCount);
                setNullableString(ps, 22, r.heroThumb);
                setNullableString(ps, 23, r.actorClasses);
                setNullableString(ps, 24, r.placeShort);
                setNullableString(ps, 25, r.placeMedium);
                setNullableString(ps, 26, r.placeDisplay);
                setNullableString(ps, 27, r.placeCountry);
                setNullableString(ps, 28, r.placeSource);
                setNullableDouble(ps, 29, r.startLat);
                setNullableDouble(ps, 30, r.startLng);
                ps.setString(31, r.ymd);
                setNullableString(ps, 32, r.storage);
                ps.executeUpdate();
                invalidateStatsCache();   // row counts/bytes changed
                return Boolean.TRUE;
            }
        });
    }

    /**
     * True when the exception means the store itself is unusable and the right
     * response is "drop this connection and re-open", rather than a normal SQL
     * error (constraint violation, syntax, bad value) that a reconnect cannot
     * help.
     *
     * <p>The code set matters and must NOT be narrowed to 90098 alone. H2
     * 2.2.224's {@code Store.convertMVStoreException} maps MVStore failures
     * onto several distinct SQL codes — only {@code ERROR_CLOSED} becomes
     * 90098 (DATABASE_IS_CLOSED); a corrupt page becomes 90030
     * (FILE_CORRUPTED), an IO failure becomes 90028 (IO_EXCEPTION), and
     * anything unclassified becomes 50000 (GENERAL_ERROR). Those other codes
     * leave {@code Connection.isClosed()} returning false (it is a pure
     * session-state field check, not a store probe), so without matching them
     * here {@code ensureOpen()} would keep reporting the index healthy while
     * every statement failed — the original silent-empty-library bug wearing a
     * different error code.
     *
     * <p>90007 (OBJECT_CLOSED) is included for a statement/result closed under
     * us, and "Table ... not found" (42S02/42102) because a wiped store
     * re-opens as an empty DB whose missing tables are fixed by the
     * {@code createSchema()} inside {@link #reopen}.
     */
    private static boolean isClosedStoreError(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // Prefer the numeric error code. Substring-matching the message is
            // NOT safe here: H2 echoes both the offending bound value and the
            // full SQL text into the message, so a clause like "50000" also
            // matches a VALUE_TOO_LONG (22001) raised by a legitimate place
            // name such as "50000 Kuala Lumpur, Jalan …" overflowing
            // place_display VARCHAR(256). That would tear down a perfectly
            // healthy connection and spawn a reconcile once per cooldown
            // window, forever, over one long string.
            if (t instanceof java.sql.SQLException) {
                switch (((java.sql.SQLException) t).getErrorCode()) {
                    case 90098:   // DATABASE_IS_CLOSED — the field failure
                    case 90007:   // OBJECT_CLOSED
                    case 90030:   // FILE_CORRUPTED (MVStore corrupt page)
                    case 90028:   // IO_EXCEPTION (MVStore read/write failed)
                    case 50000:   // GENERAL_ERROR — MVStore panic lands here
                    case 90097:   // DATABASE_IS_READ_ONLY — writes silently fail
                    // Store was wiped/recreated and re-opened empty: the tables
                    // are gone. 42102/42103/42104 are the TABLE/VIEW-not-found
                    // family; an emptied .mv.db reports 42104 specifically, so
                    // matching only 42102 would leave that case unhealed and
                    // reproduce the original permanent-empty-library bug.
                    case 42102:
                    case 42103:
                    case 42104:
                        return true;
                    default:
                        break;    // fall through to the textual checks below
                }
            }
            // Textual fallbacks for non-SQLException wrappers (e.g. a raw
            // MVStoreException escaping outside the JDBC translation layer).
            // Kept deliberately specific — no bare numeric substrings, which
            // can collide with user data echoed into the message.
            String m = t.getMessage();
            if (m != null && (m.contains("database has been closed")
                    || m.contains("Database is already closed")
                    || m.contains("object is already closed")
                    || m.contains("MVStoreException"))) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Drop a row by filename. Called on DELETE_FROM_FS / explicit delete.
     * No-op when the filename isn't present.
     */
    public synchronized boolean remove(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        final String name = filename;
        String recordingId = withRetry("resolve legacy remove(" + filename + ")", null, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT recording_id FROM recordings WHERE filename = ?"
                            + " ORDER BY is_available DESC, root_rank ASC, ts_ms DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        return recordingId != null && removeById(recordingId);
    }

    public boolean removeByPath(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) return false;
        return removeById(RecordingIdentity.fromPath(absolutePath).recordingId);
    }

    public synchronized boolean removeById(String recordingId) {
        if (recordingId == null || recordingId.isEmpty()) return false;
        noteRemoval(recordingId);
        final String id = recordingId;
        return withRetry("removeById(" + recordingId + ")", Boolean.FALSE, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM recordings WHERE recording_id = ?")) {
                ps.setString(1, id);
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) invalidateStatsCache();
                return deleted;
            }
        });
    }

    /**
     * True when {@code filename} is present in the index. Used by the
     * API handler's repair-on-read path to avoid double-upserting files
     * that are already known.
     */
    public synchronized boolean contains(String filename) {
        if (filename == null) return false;
        final String name = filename;
        return withRetry("contains(" + filename + ")", Boolean.FALSE, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM recordings WHERE filename = ? LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public synchronized boolean containsPath(String absolutePath) {
        if (absolutePath == null) return false;
        final String id = RecordingIdentity.fromPath(absolutePath).recordingId;
        return withRetry("containsPath", Boolean.FALSE, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM recordings WHERE recording_id = ? LIMIT 1")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public static final class RecordingRef {
        public final String id;
        public final String filename;
        public final String absolutePath;
        public final String heroThumbnail;

        RecordingRef(String id, String filename, String absolutePath, String heroThumbnail) {
            this.id = id;
            this.filename = filename;
            this.absolutePath = absolutePath;
            this.heroThumbnail = heroThumbnail;
        }

        public File file() {
            return new File(absolutePath);
        }
    }

    public synchronized RecordingRef resolveById(String recordingId) {
        if (recordingId == null || recordingId.isEmpty()) return null;
        final String id = recordingId;
        return withRetry("resolveById", null, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT recording_id, filename, abs_path, hero_thumb FROM recordings"
                            + " WHERE recording_id = ? AND is_available = TRUE LIMIT 1")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? recordingRef(rs) : null;
                }
            }
        });
    }

    public synchronized RecordingRef resolveByFilename(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        final String name = filename;
        return withRetry("resolveByFilename", null, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT recording_id, filename, abs_path, hero_thumb FROM recordings"
                            + " WHERE filename = ? AND is_available = TRUE"
                            + " ORDER BY root_rank ASC, ts_ms DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? recordingRef(rs) : null;
                }
            }
        });
    }

    private static RecordingRef recordingRef(ResultSet rs) throws Exception {
        return new RecordingRef(
                rs.getString("recording_id"),
                rs.getString("filename"),
                rs.getString("abs_path"),
                rs.getString("hero_thumb"));
    }

    // =================================================================
    // Warmup
    // =================================================================

    /**
     * Background warmup. Walks every dir, parses every clip, populates
     * the index. Called from CameraDaemon post-init on a dedicated
     * thread. Idempotent — second call is a no-op while first is still
     * running, and short-circuits when {@code warmupComplete} is set.
     *
     * <p>Even on a fresh database with 1000 clips this takes ~1-2 min.
     * The API handler short-circuits to {@code {warming: true, progress}}
     * during this window so the UI shows a progress skeleton instead
     * of a partial list.
     */
    public void warmupAsync() {
        if (warmupComplete.get()) return;
        if (!warmupRunning.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                // Fast path: a previous daemon already walked every file and
                // wrote `warmup_state=complete` to recordings_meta. If the row
                // count in the index still matches what's on disk (within a
                // small tolerance for in-flight writes), we skip the full
                // re-walk and run reconcile() instead — that's an O(N) stat()
                // pass with no JSON parse, typically <500 ms even on a 2k-clip
                // library. The user-visible /api/recordings call therefore
                // returns indexed rows immediately on second-and-later boots
                // instead of seeing `warming=true` for 60 s.
                if (canFastPathWarmup()) {
                    warmupComplete.set(true);
                    logger.info("Warmup fast-path: persisted complete + count match, running reconcile");
                    try {
                        reconcile();
                    } catch (Throwable thr) {
                        logger.warn("Fast-path reconcile failed: " + thr.getMessage());
                    }
                    return;
                }

                runWarmup();
                // Only mark complete on a clean run. A crash mid-warmup
                // would otherwise leave the index partial while the API
                // reports complete=true, suppressing the progress skeleton
                // and the repair-on-read fallback (which only fires when
                // totalCount==0). Leaving complete=false lets a later
                // warmupAsync() retry.
                warmupComplete.set(true);
                persistWarmupComplete();
            } catch (Throwable thr) {
                logger.error("Warmup crashed: " + thr.getMessage(), thr);
            } finally {
                warmupRunning.set(false);
            }
        }, "RecordingsIndexWarmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Persisted-warmup fast path. Returns true when the previous daemon
     * already walked every file and the index row count is still close to
     * the on-disk file count. "Close" here means ±2: a couple of in-flight
     * writes between the previous shutdown and this boot are tolerated
     * because reconcile() will pick them up. A larger drift (user manually
     * deleted clips through a file manager, SD card swapped) drops back to
     * the full walk so we don't gloss over a real mismatch.
     */
    private boolean canFastPathWarmup() {
        if (!isAvailable()) return false;
        String state = readMeta("warmup_state");
        if (!"complete".equals(state)) return false;
        int indexedCount = countIndexedRows();
        if (indexedCount <= 0) return false;
        int diskCount = countDiskFiles();
        // Allow ±2 drift for in-flight writes during the previous shutdown.
        // Anything bigger forces a full re-walk.
        if (Math.abs(diskCount - indexedCount) > 2) {
            logger.info("Warmup fast-path skipped: disk=" + diskCount
                    + " indexed=" + indexedCount + " (diff > 2)");
            return false;
        }
        return true;
    }

    private synchronized int countIndexedRows() {
        return withRetry("countIndexedRows", -1, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM recordings WHERE is_available = TRUE");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /**
     * Cheap on-disk file count across all recordings/surveillance/proximity
     * dirs. Uses {@link StorageManager#listMp4Files} (FUSE shell-fallback
     * compatible). Names are deduped so mirror dirs don't double-count.
     */
    private int countDiskFiles() {
        StorageManager sm = StorageManager.getInstance();
        Set<String> names = new HashSet<>();
        scanDirNames(names, sm.getAllRecordingsDirs());
        scanDirNames(names, sm.getAllSurveillanceDirs());
        scanDirNames(names, sm.getAllProximityDirs());
        return names.size();
    }

    private synchronized void persistWarmupComplete() {
        writeMeta("warmup_state", "complete");
        writeMeta("warmup_indexed_count", String.valueOf(countIndexedRows()));
        writeMeta("warmup_completed_ts", String.valueOf(System.currentTimeMillis()));
    }

    private synchronized String readMeta(String key) {
        final String k = key;
        return withRetry("readMeta(" + key + ")", null, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT meta_value FROM recordings_meta WHERE meta_key = ?")) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private synchronized void writeMeta(String key, String value) {
        final String k = key;
        final String v = value;
        withRetry("writeMeta(" + key + ")", Boolean.FALSE, () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO recordings_meta KEY(meta_key) VALUES (?, ?)")) {
                ps.setString(1, k);
                ps.setString(2, v);
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        });
    }

    private void runWarmup() {
        long t0 = System.currentTimeMillis();
        StorageManager sm = StorageManager.getInstance();

        // Invalidate the persisted "complete" flag at the start of a full
        // re-walk. If we crash partway through and a future daemon reads the
        // meta row, the fast-path must NOT short-circuit on a stale flag —
        // the index can be in any state right now.
        writeMeta("warmup_state", "in_progress");

        // Phase 1: enumerate every candidate file across every dir.
        // Done in one pass so we have an honest total for progress.
        List<DirEntry> entries = new ArrayList<>();
        addDirFiles(entries, sm.getAllRecordingsDirs(), null);
        addDirFiles(entries, sm.getAllSurveillanceDirs(), null);
        addDirFiles(entries, sm.getAllProximityDirs(), null);

        // Dedup by row identity. Equal filenames on different volumes remain
        // distinct; repeated references to the same root/path collapse.
        Set<String> seen = new HashSet<>(entries.size() * 2);
        List<File> unique = new ArrayList<>(entries.size());
        for (DirEntry e : entries) {
            if (seen.add(RecordingIdentity.fromFile(e.file).recordingId)) unique.add(e.file);
        }

        warmupTotal.set(unique.size());
        warmupDone.set(0);

        logger.info("Warmup starting: " + unique.size() + " candidate files");

        // Phase 2: parallel parse + batched single-thread upsert.
        //
        // The dominant warmup cost is per-file `parse()` — sidecar JSON read +
        // metadata parse averages ~30 ms/file on the SD card. Single-thread
        // serial walk takes ~60 s on a 1844-clip library.
        //
        // Strategy: a small fixed pool parses files concurrently and pushes
        // pre-parsed Row objects into a bounded queue; one writer thread
        // drains the queue and runs the upserts inside a JDBC transaction
        // (ALL-OR-NOTHING is fine here — we re-write the meta state on
        // success). H2 single-row writes through one connection do NOT
        // benefit from parallel writers, so we keep the writer single-
        // threaded; the parser pool is what cuts wall-time.
        //
        // 4 parser threads matches the typical low-power head-unit (Adreno
        // 610 = quad-A55). More threads contend on the SD card's FUSE
        // mount; fewer leaves CPU idle.
        final int parsers = 4;
        final java.util.concurrent.BlockingQueue<Object> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(parsers * 8);
        final Object POISON = new Object();

        java.util.concurrent.atomic.AtomicInteger parsed =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // Writer: drains the queue and runs MERGE per row. We deliberately
        // keep the connection in its default autoCommit=true state instead
        // of batching N rows in one transaction. Three reasons:
        //  1) The connection is shared with query threads
        //     (queryRecordings/queryStats/queryDates/queryPlaces/queryCount)
        //     and FileObserver-driven `upsert(File)` callers. Holding an
        //     open transaction here would force every concurrent caller
        //     to either join our transaction (silent durability bug — a
        //     "saved" recording disappears on crash) or block on the
        //     `synchronized (this)` monitor for the entire warmup window.
        //  2) H2's per-row commit overhead is small (the redo log is
        //     mmap'd) — the warmup walltime is dominated by parse(), not
        //     the JDBC writes.
        //  3) Crash safety: SIGKILL during warmup leaves whatever rows
        //     made it through fully durable; the persisted
        //     warmup_state=in_progress flag keeps the next boot from
        //     fast-pathing on the partial state.
        // upsertRow is `synchronized (this)` so its executeUpdate doesn't
        // race a concurrent SELECT on the shared connection.
        Thread writer = new Thread(() -> {
            try {
                while (true) {
                    try {
                        Object item = queue.take();
                        if (item == POISON) break;
                        Row r = (Row) item;
                        if (upsertRow(r)) parsed.incrementAndGet();
                        int done = warmupDone.incrementAndGet();
                        if (done % 100 == 0) {
                            logger.info("Warmup progress: " + done + "/" + unique.size());
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t) {
                        // Catch unchecked throwables (Error, RuntimeException,
                        // ClassCastException-from-bad-queue-item) so the
                        // writer keeps draining. If the writer dies silently
                        // here, parsers block forever on the bounded queue
                        // and the daemon shutdown join (futures.get without
                        // timeout) deadlocks.
                        logger.error("Writer iteration failed: " + t.getMessage(), t);
                    }
                }
            } finally {
                // Belt-and-braces: if we exit the loop for any reason other
                // than POISON (interrupt, an Error escaping the inner
                // catch), drain any pending Row items so the parser
                // threads' queue.put() calls unblock and the parser pool
                // can shut down cleanly.
                queue.clear();
            }
        }, "RecordingsIndexWriter");
        writer.setDaemon(true);
        writer.start();

        // Parser pool: each worker pulls files from a shared iterator and
        // pushes parsed Rows into the queue. Failed parses are silently
        // dropped (parse() returns null) — same behaviour as the legacy
        // serial path.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(parsers, r -> {
                    Thread t = new Thread(r, "RecordingsIndexParser");
                    t.setDaemon(true);
                    return t;
                });
        try {
            java.util.concurrent.atomic.AtomicInteger cursor =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>(parsers);
            for (int i = 0; i < parsers; i++) {
                futures.add(pool.submit(() -> {
                    while (true) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= unique.size()) return;
                        File f = unique.get(idx);
                        try {
                            Row r = parse(f);
                            if (r != null) queue.put(r);
                            else warmupDone.incrementAndGet();  // count skipped
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable ignored) {
                            warmupDone.incrementAndGet();
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> fu : futures) {
                try { fu.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdown();
            try { queue.put(POISON); } catch (InterruptedException ignored) {}
            try { writer.join(); } catch (InterruptedException ignored) {}
        }

        long ms = System.currentTimeMillis() - t0;
        logger.info("Warmup complete: " + parsed.get() + "/" + unique.size()
                + " indexed in " + ms + "ms");
    }

    private void addDirFiles(List<DirEntry> out, List<File> dirs, String forceType) {
        if (dirs == null) return;
        StorageManager sm = StorageManager.getInstance();
        for (File dir : dirs) {
            if (dir == null) continue;
            // Use StorageManager.listMp4Files which falls back to shell ls
            // when File.listFiles() returns null (FUSE-mounted SD card under
            // daemon UID 2000 — without this the index silently misses every
            // SD-card .mp4).
            File[] files = sm.listMp4Files(dir);
            for (File f : files) {
                if (!f.isFile() || f.length() <= 0 || !f.canRead()) continue;
                out.add(new DirEntry(f, forceType));
            }
        }
    }

    /** Snapshot of warmup state for the API. */
    public WarmupSnapshot warmupState() {
        return new WarmupSnapshot(
                warmupRunning.get(),
                warmupComplete.get(),
                warmupDone.get(),
                warmupTotal.get());
    }

    /**
      * Reconcile the index against the filesystem. Walks every dir,
      * upserts new or changed files, removes index rows whose mp4 is gone.
     * Backstop for FileObserver event drops on FUSE-mounted SD cards.
      * Cheap when the index is in sync — existing rows compare filesystem
      * fingerprints without parsing sidecar JSON.
     */
    public void reconcile() {
        synchronized (reconcileExecutionLock) {
            reconcileInternal();
        }
    }

    private void reconcileInternal() {
        if (!isAvailable()) return;
        long t0 = System.currentTimeMillis();
        StorageManager sm = StorageManager.getInstance();

        List<RootScan> rootScans = scanRoots(sm);
        Map<String, RecordingFileFingerprint> diskFiles = new LinkedHashMap<>();
        for (RootScan root : rootScans) {
            for (RecordingFileFingerprint fingerprint : root.files.values()) {
                diskFiles.putIfAbsent(fingerprint.recordingId, fingerprint);
            }
        }

          // Three-phase walk: index snapshot → drop missing → upsert new/changed.
        // The SELECT enumerate is synchronized so the snapshot is
        // consistent. The per-row remove()/upsert() calls re-acquire the
          // monitor themselves; filesystem fingerprinting stays outside that
          // monitor because FUSE metadata calls can block, serializing every concurrent
        // queryRecordings/queryCount/queryStats request behind reconcile.
        // Accept temporary inconsistency — a file deleted between the
        // collect and verify phases will be cleaned up on the next
        // periodic reconcile (the operation is idempotent).
        int removed = 0;
        int added = 0;
        int refreshed = 0;

        // Phase 1: snapshot the index under the monitor.
        Map<String, IndexedFileState> indexFiles;
        synchronized (this) {
            // Built inside the body so a reconnect-retry re-enumerates into a
            // fresh map rather than merging two partial snapshots.
            indexFiles = withRetry("reconcile: index enumerate", null, () -> {
                Map<String, IndexedFileState> rows = new HashMap<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT recording_id, filename, root_id, abs_path, size_bytes,"
                            + " mp4_mtime, sidecar_mtime, is_available"
                                + " FROM recordings");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.put(rs.getString(1), new IndexedFileState(
                            rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7),
                            rs.getBoolean(8)));
                    }
                }
                return rows;
            });
        }
        // null (not empty) distinguishes "enumerate failed" from "index is
        // legitimately empty". Bailing on failure is important: an empty
        // snapshot would make Phase 2 believe every indexed row is missing.
        if (indexFiles == null) {
            logger.warn("reconcile: index enumerate unavailable — skipping this pass");
            return;
        }

        applyRootAvailability(rootScans);

        Map<String, RootScan> scansByRoot = new HashMap<>();
        for (RootScan scan : rootScans) scansByRoot.put(scan.rootId, scan);

        // Phase 2: delete only after a complete scan of an available root.
        // Missing/offline and partial roots preserve their rows.
        for (Map.Entry<String, IndexedFileState> indexedEntry : indexFiles.entrySet()) {
            RootScan root = scansByRoot.get(indexedEntry.getValue().rootId);
                if (root != null && RecordingReconcilePolicy.shouldDeleteMissingRow(
                    root.available, root.complete,
                    root.files.containsKey(indexedEntry.getKey()))) {
                if (removeById(indexedEntry.getKey())) removed++;
            }
        }

        // Phase 3: parse only new or fingerprint-changed files. Active-first
        // map insertion also repairs abs_path when the same filename moved
        // between roots or volumes.
        for (Map.Entry<String, RecordingFileFingerprint> entry : diskFiles.entrySet()) {
            RecordingFileFingerprint fingerprint = entry.getValue();
            IndexedFileState indexed = indexFiles.get(entry.getKey());
            RecordingFileFingerprint.Decision decision = fingerprint.decisionAgainst(
                    indexed != null ? indexed.absolutePath : null,
                    indexed != null ? indexed.sizeBytes : 0L,
                    indexed != null ? indexed.mp4Mtime : 0L,
                    indexed != null ? indexed.sidecarMtime : 0L);
            switch (decision) {
                case ADD:
                    if (upsert(fingerprint.file)) added++;
                    break;
                case REFRESH:
                    if (upsert(fingerprint.file)) refreshed++;
                    break;
                case UNCHANGED:
                    break;
            }
        }
        long ms = System.currentTimeMillis() - t0;
        if (added > 0 || refreshed > 0 || removed > 0) {
            logger.info("Reconcile: +" + added + " / ~" + refreshed + " / -" + removed
                    + " in " + ms + "ms");
        }
    }

    private static final class IndexedFileState {
        final String filename;
        final String rootId;
        final String absolutePath;
        final long sizeBytes;
        final long mp4Mtime;
        final long sidecarMtime;

        final boolean available;

        IndexedFileState(String filename, String rootId, String absolutePath,
                         long sizeBytes, long mp4Mtime, long sidecarMtime,
                         boolean available) {
            this.filename = filename;
            this.rootId = rootId;
            this.absolutePath = absolutePath;
            this.sizeBytes = sizeBytes;
            this.mp4Mtime = mp4Mtime;
            this.sidecarMtime = sidecarMtime;
            this.available = available;
        }
    }

    private static final class RootScan {
        final String rootId;
        final int rank;
        final boolean available;
        final boolean complete;
        final Map<String, RecordingFileFingerprint> files = new LinkedHashMap<>();

        RootScan(String rootId, int rank, boolean available, boolean complete) {
            this.rootId = rootId;
            this.rank = rank;
            this.available = available;
            this.complete = complete;
        }
    }

    private List<RootScan> scanRoots(StorageManager sm) {
        List<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<File> category : java.util.Arrays.asList(
                sm.getAllRecordingsDirs(), sm.getAllSurveillanceDirs(), sm.getAllProximityDirs())) {
            for (File root : category) {
                if (root != null && seen.add(root.getAbsolutePath())) roots.add(root);
            }
        }

        List<RootScan> scans = new ArrayList<>(roots.size());
        for (int rank = 0; rank < roots.size(); rank++) {
            File root = roots.get(rank);
            StorageManager.Mp4Listing listing = sm.listMp4FilesWithStatus(root);
            RootScan scan = new RootScan(
                    RecordingIdentity.rootIdFor(root), rank, listing.available, listing.complete);
            for (File file : listing.files) {
                if (!file.isFile()) continue;
                RecordingFileFingerprint fingerprint = RecordingFileFingerprint.from(file);
                if (fingerprint.sizeBytes > 0) {
                    scan.files.put(fingerprint.recordingId, fingerprint);
                }
            }
            scans.add(scan);
        }
        return scans;
    }

    private synchronized void applyRootAvailability(List<RootScan> scans) {
        withRetry("reconcile: root availability", Boolean.FALSE, () -> {
            try (PreparedStatement offline = connection.prepareStatement(
                    "UPDATE recordings SET is_available = FALSE")) {
                offline.executeUpdate();
            }
            try (PreparedStatement online = connection.prepareStatement(
                    "UPDATE recordings SET is_available = TRUE, root_rank = ? WHERE root_id = ?")) {
                for (RootScan scan : scans) {
                    if (!scan.available) continue;
                    online.setInt(1, scan.rank);
                    online.setString(2, scan.rootId);
                    online.addBatch();
                }
                online.executeBatch();
            }
            invalidateStatsCache();
            return Boolean.TRUE;
        });
    }

    private void scanDirNames(Set<String> out, List<File> dirs) {
        StorageManager sm = StorageManager.getInstance();
        for (File dir : dirs) {
            if (dir == null) continue;
            // Same FUSE-fallback as addDirFiles — listMp4Files routes through
            // shell ls when listFiles() returns null on SD-card mounts.
            File[] files = sm.listMp4Files(dir);
            for (File f : files) {
                if (f.isFile() && f.length() > 0) {
                    out.add(RecordingIdentity.fromFile(f).recordingId);
                }
            }
        }
    }

    // =================================================================
    // Queries
    // =================================================================

    /**
     * Filter spec for {@link #queryRecordings} and {@link #queryCount}.
     * Mirrors the existing /api/recordings query params 1:1 so the API
     * handler can pass through.
     */
    public static final class Filter {
        /**
         * "normal" / "sentry" / "proximity" / "oemDashcam" / null=all.
         * For multi-type queries (e.g. native fragment's Dashcam segment
         * which wants NORMAL + PROXIMITY together) use {@link #types}
         * instead — when {@code types} is non-empty it takes precedence
         * and {@code type} is ignored.
         */
        public String type;
        /**
         * Multi-type filter. When non-empty, supersedes {@link #type}.
         * Each entry is a literal type tag matched as-is. Note: "normal"
         * does NOT auto-include "oemDashcam" in this mode — callers must
         * pass both explicitly. (The single-type {@link #type} path keeps
         * the auto-include for backward compat with web clients.)
         */
        public java.util.Set<String> types;
        /** "yyyy-MM-dd" local; null = no date narrowing. */
        public String date;
        /** lowercase class names ("person", "vehicle", ...). Empty = no narrowing. */
        public Set<String> classes;
        /** "ALERT" / "CRITICAL". Empty = no narrowing. */
        public Set<String> severities;
        /** "VERY_CLOSE" / "CLOSE" / "MID" / "FAR". Empty = no narrowing. */
        public Set<String> proximities;
        /** Lowercase short label. null = no narrowing. */
        public String place;
        /**
         * Free-text place substring search. Lowercase. When non-null,
         * matches any clip whose place_short OR place_medium contains
         * this substring (case-insensitive). Stacks with {@link #place}:
         * if both are set, both must match — the exact-match wins
         * effectively because it's a stricter filter. Use cases:
         *  - "show me anything in Bay" → "Marina Bay" + "Bay City" both match.
         *  - autocomplete-style typing → "Che" matches "Cheras" before the
         *    user finishes typing.
         */
        public String placeContains;
        /**
         * ISO 3166-1 alpha-2 country code, lowercased. null = no narrowing.
         * Useful for cross-border travel logs ("everything in Malaysia").
         * Indexed via place_country column.
         */
        public String country;
        /**
         * Physical-volume filter: "INTERNAL" / "SD_CARD" / "USB". Empty/null =
         * no narrowing (show clips from every storage location, which is the
         * default — the index already spans internal + SD + USB). Matched
         * against the indexed {@code storage} column. Legacy rows whose column
         * is still NULL (written before schema v2) are matched by deriving the
         * volume from {@code abs_path} in SQL so the filter stays correct
         * before the lazy backfill completes.
         */
        public Set<String> storages;
    }

    /**
     * Page through the index with the given filter, sorted by ts_ms DESC.
     * Returns JSON rows in the same shape as the legacy
     * RecordingsApiHandler.parseRecordingUncached output so existing
     * clients don't break.
     */
    public synchronized List<JSONObject> queryRecordings(Filter f, int limit, int offset) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(f, where, args);

        String sql = "SELECT * FROM recordings"
                + (where.length() > 0 ? " WHERE " + where : "")
            + " ORDER BY ts_ms DESC, root_rank ASC"
                + " LIMIT ? OFFSET ?";

        // The result list is built INSIDE the body so a reconnect-retry
        // starts from an empty list instead of appending to a partially
        // filled one.
        return withRetry("queryRecordings", new ArrayList<JSONObject>(), () -> {
            List<JSONObject> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int p = 1;
                for (Object a : args) bind(ps, p++, a);
                ps.setInt(p++, Math.max(1, limit));
                ps.setInt(p, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rowToJson(rs));
                }
            }
            return out;
        });
    }

    public synchronized int queryCount(Filter f) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(f, where, args);
        String sql = "SELECT COUNT(*) FROM recordings"
                + (where.length() > 0 ? " WHERE " + where : "");
        return withRetry("queryCount", 0, () -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int p = 1;
                for (Object a : args) bind(ps, p++, a);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            return 0;
        });
    }

    /**
     * Distinct place_short values, top-N by count. Used by the chip row.
     * Filter applies to everything EXCEPT place_short itself — chips
     * are scoped by the surrounding type/date/class context but not
     * narrowed by the user's already-active place selection (otherwise
     * the row would always show only the active chip).
     */
    public synchronized List<PlaceBucket> queryPlaces(Filter f, int limit) {
        Filter copy = copyFilter(f);
        copy.place = null;
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhere(copy, where, args);
        if (where.length() > 0) where.append(" AND ");
        where.append("place_short IS NOT NULL");

        String sql = "SELECT place_short, COUNT(*) AS c, MAX(ts_ms) AS newest FROM recordings"
                + " WHERE " + where
                + " GROUP BY place_short"
                + " ORDER BY c DESC, place_short ASC"
                + " LIMIT ?";

        return withRetry("queryPlaces", new ArrayList<PlaceBucket>(), () -> {
            List<PlaceBucket> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int p = 1;
                for (Object a : args) bind(ps, p++, a);
                ps.setInt(p, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlaceBucket b = new PlaceBucket();
                        b.label = rs.getString(1);
                        b.count = rs.getInt(2);
                        b.newestTs = rs.getLong(3);
                        out.add(b);
                    }
                }
            }
            return out;
        });
    }

    /**
     * Distinct dates (yyyy-MM-dd) plus per-date count and a hasSentry
     * flag for the calendar dot decoration.
     */
    public synchronized List<DateBucket> queryDates() {
        String sql =
            "SELECT ymd, COUNT(*) AS c, "
            + " MAX(CASE WHEN type = 'sentry' THEN 1 ELSE 0 END) AS hasSentry"
            + " FROM recordings WHERE is_available = TRUE AND ymd IS NOT NULL GROUP BY ymd";
        return withRetry("queryDates", new ArrayList<DateBucket>(), () -> {
            List<DateBucket> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DateBucket b = new DateBucket();
                    b.date = rs.getString(1);
                    b.count = rs.getInt(2);
                    b.hasSentry = rs.getInt(3) > 0;
                    out.add(b);
                }
            }
            return out;
        });
    }

    /**
     * Per-type aggregate stats: count, total bytes, count-from-today.
     * Used by /api/recordings/stats and the native fragment header.
     */
    public synchronized Stats queryStats() {
        long todayStart = startOfTodayMillis();
        // Memo hit only when the table is unchanged AND we're still in the same
        // local day — the todayC bucket is relative to midnight, so a day
        // rollover (or a TZ/DST shift) must re-run even with no writes.
        if (cachedStats != null && cachedStatsTodayStart == todayStart) {
            return cachedStats.copy();
        }
        String sql =
            "SELECT type,"
            + "  COUNT(*) AS c,"
            + "  COALESCE(SUM(size_bytes), 0) AS bytes,"
            + "  SUM(CASE WHEN ts_ms >= ? THEN 1 ELSE 0 END) AS todayC"
            + " FROM recordings WHERE is_available = TRUE GROUP BY type";
        // Stats is accumulated with += for the normal/oemDashcam fold, so a
        // fresh instance MUST be allocated inside the body — reusing one
        // across a reconnect-retry would double-count the dashcam bucket.
        //
        // NOTE the null sentinel: withRetry returns this failureValue when the
        // store is down, and an all-zero Stats must NEVER be memoized — that
        // would latch "0 clips / 0 B" until the next write, which is exactly
        // the false-empty the caller's sendIndexUnavailable() guard exists to
        // prevent. Returning null here lets us distinguish failure from a
        // legitimately empty table, and we publish the memo only on success.
        Stats fresh = withRetry("queryStats", null, () -> {
            Stats s = new Stats();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, todayStart);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String t = rs.getString(1);
                        long c = rs.getLong(2);
                        long b = rs.getLong(3);
                        long tc = rs.getLong(4);
                        if ("normal".equals(t) || "oemDashcam".equals(t)) {
                            s.normalCount += c; s.normalBytes += b; s.normalToday += tc;
                        } else if ("sentry".equals(t)) {
                            s.sentryCount = c; s.sentryBytes = b; s.sentryToday = tc;
                        } else if ("proximity".equals(t)) {
                            s.proximityCount = c; s.proximityBytes = b; s.proximityToday = tc;
                        } else if ("replay".equals(t)) {
                            s.replayCount = c; s.replayBytes = b; s.replayToday = tc;
                        }
                    }
                }
            }
            return s;
        });
        // Store down → hand back a zeroed Stats exactly as before (callers
        // gate on isUnavailableForClients(), not on this value), but leave the
        // memo empty so the next poll retries the scan.
        if (fresh == null) return new Stats();
        cachedStats = fresh;
        cachedStatsTodayStart = todayStart;
        return fresh.copy();
    }

    // =================================================================
    // Internal — filter builder
    // =================================================================

    private static void buildWhere(Filter f, StringBuilder where, List<Object> args) {
        appendAnd(where, "is_available = TRUE");
        if (f == null) return;
        if (f.types != null && !f.types.isEmpty()) {
            // Multi-type path: literal IN(...) — caller is explicit about
            // which types to include. No auto-folding (single-type path
            // still folds "normal" → ["normal","oemDashcam"] for compat).
            appendAnd(where, "type IN " + inList(f.types.size()));
            for (String t : f.types) args.add(t);
        } else if (f.type != null && !f.type.isEmpty()) {
            // Single-type path: "normal" includes oemDashcam by convention
            // (AVM cam_* + OEM dvr_* both belong to the dashcam segment in
            // the web UI).
            if ("normal".equals(f.type)) {
                appendAnd(where, "type IN ('normal','oemDashcam')");
            } else {
                appendAnd(where, "type = ?");
                args.add(f.type);
            }
        }
        if (f.date != null && !f.date.isEmpty()) {
            appendAnd(where, "ymd = ?");
            args.add(f.date);
        }
        if (f.severities != null && !f.severities.isEmpty()) {
            appendAnd(where, "peak_severity IN " + inList(f.severities.size()));
            for (String s : f.severities) args.add(s);
        }
        if (f.proximities != null && !f.proximities.isEmpty()) {
            appendAnd(where, "peak_proximity IN " + inList(f.proximities.size()));
            for (String p : f.proximities) args.add(p);
        }
        if (f.classes != null && !f.classes.isEmpty()) {
            // actor_classes is a CSV; "person,vehicle". Match any.
            // The H2 LIKE pattern '%,X,%' against ','+col+',' is
            // standard for this case and uses the index range scan
            // when combined with another filter. For pure-class
            // queries it's a table scan, which is fine at the
            // ~1000-row scale we're targeting.
            StringBuilder clause = new StringBuilder("(");
            int i = 0;
            for (String c : f.classes) {
                if (i++ > 0) clause.append(" OR ");
                clause.append("',' || actor_classes || ',' LIKE ?");
                args.add("%," + c + ",%");
            }
            clause.append(")");
            appendAnd(where, clause.toString());
        }
        if (f.place != null && !f.place.isEmpty()) {
            appendAnd(where, "LOWER(place_short) = ?");
            args.add(f.place.toLowerCase(Locale.US));
        }
        if (f.placeContains != null && !f.placeContains.isEmpty()) {
            // Substring match across short + medium so "Bay" hits both
            // "Marina Bay" (short) and "Marina Bay, Singapore" (medium).
            // No anchored prefix optimization — at typical library sizes
            // the index's row count is the bound, not the column scan.
            appendAnd(where, "(LOWER(COALESCE(place_short, '')) LIKE ?"
                           + " OR LOWER(COALESCE(place_medium, '')) LIKE ?"
                           + " OR LOWER(COALESCE(place_display, '')) LIKE ?)");
            String pat = "%" + f.placeContains.toLowerCase(Locale.US) + "%";
            args.add(pat);
            args.add(pat);
            args.add(pat);
        }
        if (f.country != null && !f.country.isEmpty()) {
            appendAnd(where, "LOWER(place_country) = ?");
            args.add(f.country.toLowerCase(Locale.US));
        }
        if (f.storages != null && !f.storages.isEmpty()) {
            // Primary match is the indexed `storage` column. For legacy rows
            // whose column is still NULL (pre-v2, not yet backfilled), derive
            // the volume from abs_path so the filter is correct immediately:
            //   - under the internal base or /storage/emulated → INTERNAL
            //   - any other /storage/ or /mnt/ subtree            → SD_CARD
            // (USB can't be told apart from SD by path alone without the live
            // mount root, so NULL-row USB clips surface under SD_CARD until the
            // backfill stamps the real column — an acceptable transient for the
            // rare legacy-USB case; fresh rows are always exact.)
            StringBuilder clause = new StringBuilder("(");
            clause.append("storage IN ").append(inList(f.storages.size()));
            for (String s : f.storages) args.add(s);
            boolean wantInternal = f.storages.contains("INTERNAL");
            boolean wantSd = f.storages.contains("SD_CARD");
            if (wantInternal) {
                clause.append(" OR (storage IS NULL AND (abs_path LIKE '/storage/emulated/%'"
                        + " OR abs_path LIKE '/storage/emulated/0/Overdrive/%'))");
            }
            if (wantSd) {
                clause.append(" OR (storage IS NULL AND abs_path NOT LIKE '/storage/emulated/%'"
                        + " AND (abs_path LIKE '/storage/%' OR abs_path LIKE '/mnt/%'))");
            }
            clause.append(")");
            appendAnd(where, clause.toString());
        }
    }

    private static void appendAnd(StringBuilder sb, String clause) {
        if (sb.length() > 0) sb.append(" AND ");
        sb.append(clause);
    }

    // Pre-built '(?,?,...)' patterns for the common cardinalities we hit
    // (severities ≤ 2, proximities ≤ 4, types ≤ 4, classes ≤ 5). Avoids a
    // StringBuilder per filtered query — small, but every queryRecordings()
    // with a non-empty IN-set used to allocate one.
    private static final String[] IN_LIST_CACHE = buildInListCache(10);

    private static String[] buildInListCache(int max) {
        String[] cache = new String[max + 1];
        cache[0] = "()";
        for (int n = 1; n <= max; n++) {
            StringBuilder sb = new StringBuilder(2 + n * 2);
            sb.append('(');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append('?');
            }
            sb.append(')');
            cache[n] = sb.toString();
        }
        return cache;
    }

    private static String inList(int n) {
        if (n >= 0 && n < IN_LIST_CACHE.length) return IN_LIST_CACHE[n];
        StringBuilder sb = new StringBuilder(2 + n * 2);
        sb.append('(');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    private static void bind(PreparedStatement ps, int idx, Object v) throws Exception {
        if (v instanceof String) ps.setString(idx, (String) v);
        else if (v instanceof Long) ps.setLong(idx, (Long) v);
        else if (v instanceof Integer) ps.setInt(idx, (Integer) v);
        else if (v instanceof Double) ps.setDouble(idx, (Double) v);
        else ps.setObject(idx, v);
    }

    private static Filter copyFilter(Filter f) {
        Filter c = new Filter();
        if (f == null) return c;
        c.type = f.type; c.date = f.date; c.place = f.place;
        c.placeContains = f.placeContains;
        c.country = f.country;
        c.types = f.types == null ? null : new HashSet<>(f.types);
        c.classes = f.classes == null ? null : new HashSet<>(f.classes);
        c.severities = f.severities == null ? null : new HashSet<>(f.severities);
        c.proximities = f.proximities == null ? null : new HashSet<>(f.proximities);
        c.storages = f.storages == null ? null : new HashSet<>(f.storages);
        return c;
    }

    // =================================================================
    // Parsing
    // =================================================================

    /**
     * Parse one mp4 (+ its sidecar if present) into an indexable Row.
     * Returns null if the filename isn't recognised (e.g. a stray .mp4
     * we don't own).
     */
    private static Row parse(File mp4) {
        String name = mp4.getName();
        Row r = new Row();
        RecordingIdentity identity = RecordingIdentity.fromFile(mp4);
        r.recordingId = identity.recordingId;
        r.rootId = identity.rootId;
        r.volumeId = identity.volumeId;
        r.relativePath = identity.relativePath;
        r.rootRank = 100;
        r.filename = name;
        r.absPath = mp4.getAbsolutePath();
        r.sizeBytes = mp4.length();
        r.mp4Mtime = mp4.lastModified();
        // Physical volume the clip lives on, classified from its path. Stored
        // as an indexed column so the recordings library can filter by volume
        // (INTERNAL / SD_CARD / USB). Best-effort — a classifier/singleton
        // failure leaves storage NULL and rowToJson falls back to deriving the
        // tag from the path at read time, so the row is never dropped.
        try {
            com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
            r.storage = storageManager.classifyStorageForPath(r.absPath);
            r.rootRank = storageManager.getRecordingRootRank(mp4);
        } catch (Throwable ignored) {
            r.storage = null;
        }

        // Type + timestamp from filename pattern.
        Matcher cam = CAM_PATTERN.matcher(name);
        Matcher replay = REPLAY_PATTERN.matcher(name);
        Matcher event = EVENT_PATTERN.matcher(name);
        Matcher prox = PROXIMITY_PATTERN.matcher(name);
        Matcher dvr = DVR_PATTERN.matcher(name);
        try {
            if (event.matches()) {
                r.type = "sentry";
                r.tsMs = FMT_FILENAME.get().parse(event.group(1) + "_" + event.group(2)).getTime();
            } else if (prox.matches()) {
                r.type = "proximity";
                r.tsMs = FMT_FILENAME.get().parse(prox.group(1) + "_" + prox.group(2)).getTime();
            } else if (cam.matches()) {
                r.type = "normal";
                String camStr = cam.group(1);
                r.cameraId = camStr != null ? Integer.parseInt(camStr) : 0;
                r.tsMs = FMT_FILENAME.get().parse(cam.group(2) + "_" + cam.group(3)).getTime();
            } else if (replay.matches()) {
                r.type = "replay";
                r.cameraId = 0;
                r.tsMs = FMT_FILENAME.get().parse(
                        replay.group(1) + "_" + replay.group(2)).getTime();
            } else if (dvr.matches()) {
                r.type = "oemDashcam";
                r.tsMs = FMT_FILENAME.get().parse(dvr.group(1) + "_" + dvr.group(2)).getTime();
            } else {
                // Unknown filename grammar — fall back to mtime, tag as
                // "normal" so it surfaces somewhere instead of vanishing.
                r.type = "normal";
                r.tsMs = mp4.lastModified();
            }
        } catch (Exception e) {
            r.tsMs = mp4.lastModified();
            if (r.type == null) r.type = "normal";
        }

        // ymd helper for the calendar dot endpoint.
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(r.tsMs);
        r.ymd = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));

        // Sidecar enrichment — same logic as
        // RecordingsApiHandler.parseRecordingUncached, kept close to
        // identical so existing callers don't notice the swap.
        File sidecar = RecordingFileFingerprint.sidecarFor(mp4);
        if (sidecar.exists() && sidecar.canRead()) {
            r.sidecarMtime = sidecar.lastModified();
            try {
                int cap = (int) Math.min(sidecar.length(), 65536L);
                StringBuilder sb = new StringBuilder(cap);
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader(sidecar))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = br.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() >= cap) break;
                    }
                }
                JSONObject side = new JSONObject(sb.toString());
                r.schemaVersion = side.optInt("version", 2);
                JSONObject stats = side.optJSONObject("stats");
                if (stats != null) {
                    r.peakSeverity = stats.optString("peakSeverity", null);
                    r.peakProximity = stats.optString("peakProximity", null);
                    r.personCount = stats.optInt("personCount", 0);
                    r.vehicleCount = stats.optInt("vehicleCount", 0);
                    r.bikeCount = stats.optInt("bikeCount", 0);
                    r.animalCount = stats.optInt("animalCount", 0);
                    if (r.peakSeverity != null && r.peakSeverity.isEmpty()) r.peakSeverity = null;
                    if (r.peakProximity != null && r.peakProximity.isEmpty()) r.peakProximity = null;
                }
                String hero = side.optString("heroThumbnail", null);
                if (hero != null && !hero.isEmpty()) r.heroThumb = hero;

                JSONArray actors = side.optJSONArray("actors");
                if (actors != null && actors.length() > 0) {
                    StringBuilder cls = new StringBuilder();
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < actors.length(); i++) {
                        JSONObject a = actors.optJSONObject(i);
                        if (a == null) continue;
                        String c = a.optString("class", "").toLowerCase(Locale.US);
                        // Skip static NON-person actors (parked cars, hydrants):
                        // they are background, not threats, and shouldn't surface
                        // a "Vehicle" chip / class filter on the events page.
                        // Mirrors the engine's rule (keep a static loitering PERSON,
                        // drop static non-persons) and the JSON count gate in
                        // EventTimelineCollector. Use the timeline-static SUPERSET
                        // (isStaticForTimeline) — falling back to isStatic for older
                        // sidecars — so a parked car detected via the never-moved
                        // signal (which may not have latched the severity-path
                        // isStatic under sparse cadence) also drops its chip.
                        boolean timelineStatic = a.optBoolean("isStaticForTimeline",
                                a.optBoolean("isStatic", false));
                        if (timelineStatic && !"person".equals(c)) {
                            continue;
                        }
                        // Also drop the chip for the low-conf-FAR-NOTICE
                        // misclassification profile (a far low-conf parked car/bike
                        // at NOTICE) that the engine already excluded from the live
                        // count/pill/caption. The verdict is persisted by
                        // EventTimelineCollector (it depends on everMoved/everMovedTested
                        // which aren't otherwise in the sidecar, so it can't be
                        // recomputed here). The persisted flag is written ONLY for
                        // NON-person actors — EventTimelineCollector uses
                        // suppressFromSummary, which exempts PERSON — so a PERSON-FP
                        // chip is intentionally KEPT here, matching the headline
                        // count + SRT + caption (a real far still person is
                        // byte-identical to a bike-as-person FP, and the hard
                        // invariant forbids dropping a person from the summary).
                        // Absent on older sidecars / real non-person actors → fail
                        // open (chip kept) = prior behavior, so no real actor's chip
                        // is ever dropped.
                        if (a.optBoolean("lowConfFarNotice", false)) {
                            continue;
                        }
                        if (!c.isEmpty() && seen.add(c)) {
                            if (cls.length() > 0) cls.append(',');
                            cls.append(c);
                        }
                    }
                    if (cls.length() > 0) r.actorClasses = cls.toString();
                }

                JSONObject geo = side.optJSONObject("geo");
                if (geo != null) {
                    JSONObject startObj = geo.optJSONObject("start");
                    if (startObj != null) {
                        if (startObj.has("lat")) r.startLat = startObj.optDouble("lat");
                        if (startObj.has("lng")) r.startLng = startObj.optDouble("lng");
                    }
                    JSONObject placeObj = geo.optJSONObject("place");
                    if (placeObj != null) {
                        String dist = placeObj.optString("district", "");
                        String city = placeObj.optString("city", "");
                        String dn = placeObj.optString("displayName", "");
                        String cc = placeObj.optString("countryCode", "");
                        String src = placeObj.optString("source", "");
                        String shortLabel = !dist.isEmpty() ? dist
                                : !city.isEmpty() ? city
                                : (!dn.isEmpty() ? dn : null);
                        String mediumLabel = (!dist.isEmpty() && !city.isEmpty() && !dist.equals(city))
                                ? (dist + ", " + city) : shortLabel;
                        // Clamp to the column widths declared in
                        // createSchema(). Reverse-geocoders (Nominatim) return
                        // display_name strings well over 256 chars, which H2
                        // rejects with VALUE_TOO_LONG (22001) — and because
                        // that aborts the whole MERGE, the clip would silently
                        // never be indexed and never appear in events.html.
                        if (shortLabel != null) r.placeShort = clamp(shortLabel, 128);
                        if (mediumLabel != null) r.placeMedium = clamp(mediumLabel, 192);
                        if (!dn.isEmpty()) r.placeDisplay = clamp(dn, 256);
                        if (!cc.isEmpty()) r.placeCountry = clamp(cc.toLowerCase(Locale.US), 8);
                        if (!src.isEmpty()) r.placeSource = clamp(src, 32);
                    }
                }
            } catch (Exception se) {
                // Sidecar parse failure is non-fatal; row still indexed
                // with bare mp4 metadata.
            }
        }

        return r;
    }

    /**
     * Render a row from the index back to the JSON shape the API and
     * legacy clients expect. Mirrors parseRecordingUncached's output
     * verbatim, plus a {@code bucketLabel} field for paging-aware
     * sticky headers.
     */
    private static JSONObject rowToJson(ResultSet rs) throws Exception {
        JSONObject rec = new JSONObject();
        rec.put("id", rs.getString("recording_id"));
        String name = rs.getString("filename");
        long ts = rs.getLong("ts_ms");
        rec.put("filename", name);
        String absPath = rs.getString("abs_path");
        rec.put("path", absPath);
        rec.put("available", rs.getBoolean("is_available"));
        rec.put("volumeId", rs.getString("volume_id"));
        rec.put("rootId", rs.getString("root_id"));
        // Per-clip storage tag (INTERNAL / SD_CARD / USB). Makes the silent
        // SD→internal fallback (SD bridged behind USB power) visible at the
        // file level, and backs the storage filter. Prefer the indexed column
        // (populated at parse time); fall back to deriving from the path for
        // legacy rows written before the v2 column existed (column is NULL).
        // Best-effort throughout: null/unknown is simply omitted so the badge
        // degrades gracefully rather than mislabeling.
        try {
            String storage = rs.getString("storage");
            if (storage == null || storage.isEmpty()) {
                storage = com.overdrive.app.storage.StorageManager
                        .getInstance().classifyStorageForPath(absPath);
            }
            if (storage != null) rec.put("storage", storage);
        } catch (Throwable ignored) {
            // Index queries must never fail because the storage classifier
            // (or the StorageManager singleton) is unavailable in this process.
        }
        rec.put("type", rs.getString("type"));
        rec.put("cameraId", rs.getInt("camera_id"));
        rec.put("timestamp", ts);
        long size = rs.getLong("size_bytes");
        rec.put("size", size);
        rec.put("sizeFormatted", formatSize(size));

        Date d = new Date(ts);
        rec.put("date", FMT_DATE_ISO.get().format(d));
        rec.put("time", FMT_TIME_ISO.get().format(d));
        rec.put("dateFormatted", FMT_DATE_DISPLAY.get().format(d));
        rec.put("timeFormatted", FMT_TIME_DISPLAY.get().format(d));

        String recordingId = rs.getString("recording_id");
        rec.put("videoUrl", "/video/id/" + recordingId);
        rec.put("thumbnailUrl", "/thumb/id/" + recordingId);
        rec.put("deleteUrl", "/api/recordings/id/" + recordingId);
        rec.put("eventUrl", "/api/events/id/" + recordingId);
        rec.put("legacyVideoUrl", "/video/" + name);
        rec.put("legacyThumbnailUrl", "/thumb/" + name);

        int sv = rs.getInt("schema_version");
        if (sv > 0) rec.put("schemaVersion", sv);

        String sev = rs.getString("peak_severity");
        if (sev != null) rec.put("peakSeverity", sev);
        String prox = rs.getString("peak_proximity");
        if (prox != null) rec.put("peakProximity", prox);

        int person = rs.getInt("person_count");
        int vehicle = rs.getInt("vehicle_count");
        int bike = rs.getInt("bike_count");
        int animal = rs.getInt("animal_count");
        if (person > 0) rec.put("personCount", person);
        if (vehicle > 0) rec.put("vehicleCount", vehicle);
        if (bike > 0) rec.put("bikeCount", bike);
        if (animal > 0) rec.put("animalCount", animal);

        String hero = rs.getString("hero_thumb");
        if (hero != null) {
            rec.put("heroThumbnailName", hero);
            rec.put("heroThumbnailUrl", "/thumb/id/" + recordingId);
        }

        String classes = rs.getString("actor_classes");
        if (classes != null && !classes.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (String c : classes.split(",")) {
                if (c.isEmpty()) continue;
                JSONObject a = new JSONObject();
                a.put("class", c);
                arr.put(a);
            }
            rec.put("actors", arr);
        }

        String pShort = rs.getString("place_short");
        if (pShort != null) {
            JSONObject place = new JSONObject();
            place.put("short", pShort);
            String pMed = rs.getString("place_medium");
            if (pMed != null) place.put("medium", pMed);
            String pDisp = rs.getString("place_display");
            if (pDisp != null) place.put("displayName", pDisp);
            String pCC = rs.getString("place_country");
            if (pCC != null) place.put("countryCode", pCC);
            String pSrc = rs.getString("place_source");
            if (pSrc != null) place.put("source", pSrc);
            rec.put("place", place);
        }

        double sLat = rs.getDouble("start_lat");
        if (!rs.wasNull()) rec.put("startLat", sLat);
        double sLng = rs.getDouble("start_lng");
        if (!rs.wasNull()) rec.put("startLng", sLng);

        // bucketLabel — used by the native fragment for sticky time-of-day
        // headers without needing the full list. Format: "Today", "Yesterday",
        // or "MMM d, yyyy" — matches RecordingSectionHeaderDecoration.
        rec.put("bucketLabel", bucketLabelFor(ts));
        rec.put("ymd", rs.getString("ymd"));

        return rec;
    }

    private static String bucketLabelFor(long ts) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        long todayStart = today.getTimeInMillis();
        long yStart = todayStart - 86400000L;
        if (ts >= todayStart) return "Today";
        if (ts >= yStart) return "Yesterday";
        return FMT_DATE_DISPLAY.get().format(new Date(ts));
    }

    private static long startOfTodayMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(Locale.US, "%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }

    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_ISO =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_ISO =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE_DISPLAY =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM d, yyyy", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME_DISPLAY =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("h:mm a", Locale.US));

    private static void setNullableString(PreparedStatement ps, int idx, String v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    // =================================================================
    // POJOs
    // =================================================================

    /** Internal row representation — pre-DB and post-DB share the shape. */
    private static final class Row {
        String recordingId;
        String rootId;
        String volumeId;
        String relativePath;
        int rootRank;
        String filename;
        String absPath;
        String type;
        int cameraId;
        long tsMs;
        long sizeBytes;
        long mp4Mtime;
        long sidecarMtime;
        int schemaVersion;
        String peakSeverity;
        String peakProximity;
        int personCount;
        int vehicleCount;
        int bikeCount;
        int animalCount;
        String heroThumb;
        String actorClasses;
        String placeShort;
        String placeMedium;
        String placeDisplay;
        String placeCountry;
        String placeSource;
        Double startLat;
        Double startLng;
        String ymd;
        String storage;   // "INTERNAL" / "SD_CARD" / "USB" / null
    }

    private static final class DirEntry {
        final File file;
        final String forceType;
        DirEntry(File f, String t) { this.file = f; this.forceType = t; }
    }

    public static final class WarmupSnapshot {
        public final boolean running;
        public final boolean complete;
        public final int done;
        public final int total;
        public WarmupSnapshot(boolean r, boolean c, int d, int t) {
            this.running = r; this.complete = c; this.done = d; this.total = t;
        }
    }

    public static final class PlaceBucket {
        public String label;
        public int count;
        public long newestTs;
    }

    public static final class DateBucket {
        public String date;
        public int count;
        public boolean hasSentry;
    }

    public static final class Stats {
        public long normalCount, normalBytes, normalToday;
        public long sentryCount, sentryBytes, sentryToday;
        public long proximityCount, proximityBytes, proximityToday;
        public long replayCount, replayBytes, replayToday;
        public long totalCount() { return normalCount + sentryCount + proximityCount + replayCount; }
        public long totalBytes() { return normalBytes + sentryBytes + proximityBytes + replayBytes; }
        public long totalToday() { return normalToday + sentryToday + proximityToday + replayToday; }

        /** Field-wise copy. The fields are public and mutable, so
         *  {@link RecordingsIndex#queryStats()} hands every caller its own copy
         *  rather than the memoized instance — otherwise a caller that adjusted
         *  a bucket (the existing handler folds oemDashcam into normal, so this
         *  is a plausible future edit) would silently corrupt the cached value
         *  for every subsequent reader. */
        Stats copy() {
            Stats c = new Stats();
            c.normalCount = normalCount; c.normalBytes = normalBytes; c.normalToday = normalToday;
            c.sentryCount = sentryCount; c.sentryBytes = sentryBytes; c.sentryToday = sentryToday;
            c.proximityCount = proximityCount; c.proximityBytes = proximityBytes;
            c.proximityToday = proximityToday;
            c.replayCount = replayCount; c.replayBytes = replayBytes; c.replayToday = replayToday;
            return c;
        }
    }
}
