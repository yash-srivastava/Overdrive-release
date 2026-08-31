package com.overdrive.app.monitor;

import android.content.Context;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * DataUsageMonitor — tracks the network data consumed by Overdrive (the app UID
 * plus the UID-2000 daemons AND the sing-box / tailscale / cloudflared / zrok
 * native binaries, which all run under UID 2000), split by WiFi vs mobile, and
 * persisted per-day for the performance page's Data graph.
 *
 * <h3>Accuracy through proxies (the double-count crux)</h3>
 * When sing-box/tailscale are enabled, one logical request egresses as
 * {@code app(10xxx) -> 127.0.0.1:8119 -> sing-box(2000) -> WAN}. Naive per-UID
 * totals would count each proxied byte 2-3x (the app->proxy loopback hop AND the
 * proxy->WAN real hop, plus tailscale->sing-box when chained). Every proxy hop
 * lives entirely on the loopback interface {@code lo}, so we read per-UID-per-
 * INTERFACE counters and simply EXCLUDE {@code lo}. The single real egress
 * (sing-box -> WAN on {@code wlan0}/{@code rmnet}) is then counted exactly once,
 * whether the tunnel is on or off — no special-casing. The interface name is
 * also the WiFi/mobile axis for free ({@code wlan*}=wifi, {@code rmnet*}/
 * {@code ccmni*}=mobile), mirroring {@link NetworkMonitor}'s shell fallback.
 *
 * <h3>Source</h3>
 * {@code /proc/net/xt_qtaguid/stats} — per (iface, uid) rx/tx byte counters.
 * Deprecated in AOSP API 28+, but present on the old BYD vendor kernel (API 29
 * head unit). Reads fail-safe: if the file is absent/unreadable the sampler logs
 * once and records nothing rather than crashing.
 *
 * <h3>Zero-overhead-when-off</h3>
 * The sampler scheduler is armed ONLY on the enable edge (see
 * {@link #startIfEnabled()}) and torn down on the disable edge
 * ({@link #stop()}). A disabled feature reads no /proc stats, writes no H2 rows,
 * and schedules no wakeups — the same lazy-arm contract as the keymap
 * FileObserver and the a11y watchdog.
 *
 * <h3>Re-enable / restart correctness</h3>
 * qtaguid counters are cumulative (reset only on reboot / iface-down). We store
 * the last reading + its wall-clock in a state row and take deltas. Two guards:
 * <ul>
 *   <li><b>Reset-safe:</b> {@code current < last} (reboot / counter wrap) → the
 *       delta is {@code current} (all bytes since the reset), never negative.</li>
 *   <li><b>Stale-baseline:</b> if the gap since the last sample exceeds
 *       {@link #STALE_BASELINE_MS} (feature was disabled, or a long daemon-down
 *       window), the first delta is DISCARDED (re-seed) so a week of untracked
 *       traffic is never dumped into the day the user re-enables. A brief restart
 *       (within the window) instead RECOVERS the downtime bytes.</li>
 * </ul>
 * H2 (pure Java, no native deps) — same embedded-DB choice as
 * {@link SocHistoryDatabase}, correct for the UID-2000 daemon process.
 */
public class DataUsageMonitor {

    private static final String TAG = "DataUsageMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DB_PATH = ScratchPaths.path("overdrive_datausage_h2");
    // Same H2 flags rationale as SocHistoryDatabase: socket lock, no trace file,
    // we own shutdown (DB_CLOSE_ON_EXIT=FALSE). Single writer (the sampler thread)
    // + same-JVM HTTP reads, so AUTO_SERVER is intentionally omitted.
    // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2 stores
    // (see SocHistoryDatabase.JDBC_URL for the full rationale).
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
            ";AUTO_COMPACT_FILL_RATE=50";

    private static final String TABLE_DAILY = "data_usage_daily";
    private static final String TABLE_STATE = "data_usage_state";

    // Legacy per-UID/per-iface counters — REMOVED on this device's kernel
    // (Android 10 / API 29 moved netstats to eBPF; the file is absent). Kept only
    // as an opportunistic fast path: if present (older ROMs) it's read directly,
    // else we fall back to `dumpsys netstats` which works on BOTH backends.
    private static final String QTAGUID_PATH = "/proc/net/xt_qtaguid/stats";

    // Sample every 2 min — matches SocHistoryDatabase's cadence; the read+parse
    // is a few-KB proc file + one small H2 write, negligible.
    private static final long SAMPLE_INTERVAL_MS = 120_000L;
    // Gap beyond which the stored baseline is considered stale (feature was off /
    // long daemon-down) and the next delta is discarded rather than attributed.
    // 15 min recovers normal ticks + brief restarts, discards disabled periods.
    private static final long STALE_BASELINE_MS = 15 * 60 * 1000L;
    // The shell daemons + native tunnels all run under this UID.
    private static final int SHELL_UID = 2000;

    // SimpleDateFormat is NOT thread-safe and this key is formatted from BOTH the
    // sampler thread (addToDay, every 2 min) and the HTTP thread (getUsage, per
    // page poll). A shared instance would race → garbled date strings or an
    // internal-calendar throw. ThreadLocal gives each thread its own formatter.
    // Local day boundaries (the user's "per day"). TZ may be briefly wrong on a
    // cold-boot head unit until GPS/NTP corrects the RTC — acceptable for a data
    // graph; the bucket just lands on the then-current local day. Write (addToDay)
    // and read (getUsage) both use this same format, so their day-keys always agree.
    private static final ThreadLocal<SimpleDateFormat> DAY_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));

    /** Format a timestamp as the local yyyy-MM-dd day key (thread-safe). */
    private static String dayKey(long ms) {
        return DAY_FMT.get().format(new java.util.Date(ms));
    }

    private static DataUsageMonitor instance;
    private static final Object lock = new Object();

    private Connection connection;
    private volatile boolean isInitialized = false;
    private volatile boolean isRunning = false;
    private ScheduledExecutorService scheduler;

    // Resolved once from the app context: the app process UID (10xxx). -1 until
    // resolved → we then count UID 2000 only (still useful; logged).
    private volatile int appUid = -1;

    private DataUsageMonitor() {
        try {
            Class.forName("org.h2.Driver");
        } catch (Throwable t) {
            logger.error("H2 Driver not found for DataUsageMonitor: " + t.getMessage(), t);
        }
    }

    public static DataUsageMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new DataUsageMonitor();
            }
        }
        return instance;
    }

    /**
     * Resolve the app UID from a package context the daemon holds. Best-effort;
     * on failure we degrade to counting UID 2000 only.
     */
    public void resolveAppUid(Context appContext) {
        if (appUid > 0 || appContext == null) return;
        try {
            int uid = appContext.getApplicationInfo().uid;
            if (uid > 0) {
                appUid = uid;
                logger.info("DataUsageMonitor: resolved app UID = " + uid);
            }
        } catch (Throwable t) {
            logger.warn("DataUsageMonitor: could not resolve app UID (" + t.getMessage()
                    + ") — will count UID " + SHELL_UID + " only");
        }
    }

    // ==================== LIFECYCLE ====================

    /**
     * Arm the sampler IFF the feature is enabled in config. Called on daemon boot
     * and on the config-change enable edge. Idempotent: a second call while
     * running is a no-op. When disabled, this does nothing (zero overhead) and any
     * running sampler is stopped so the disable edge frees the thread.
     */
    public void startIfEnabled() {
        boolean enabled;
        try {
            com.overdrive.app.config.UnifiedConfigManager.INSTANCE.forceReload();
            enabled = com.overdrive.app.config.UnifiedConfigManager.isDataUsageEnabled();
        } catch (Throwable t) {
            enabled = false;
        }
        if (!enabled) {
            if (isRunning) {
                logger.info("DataUsageMonitor: feature disabled — stopping sampler");
                stop();
            }
            return;
        }
        start();
    }

    private synchronized void start() {
        if (isRunning) return;
        if (!isInitialized) init();
        if (!isInitialized) {
            logger.warn("DataUsageMonitor: DB not initialized — sampler not started");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "DataUsageSampler");
                t.setDaemon(true);
                return t;
            }
        });
        isRunning = true;
        // First tick after one interval — start() only seeds the baseline (the
        // first sampleTick with a fresh/stale baseline records a zero delta).
        scheduler.scheduleWithFixedDelay(this::safeSampleTick,
                SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Seed immediately so the very first real delta is anchored to now, not to
        // a possibly-stale persisted baseline from a previous enable session.
        safeSampleTick();
        logger.info("DataUsageMonitor: sampler started (interval=" + (SAMPLE_INTERVAL_MS / 1000)
                + "s, uids=[" + (appUid > 0 ? appUid + "," : "") + SHELL_UID + "])");
    }

    public synchronized void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void init() {
        if (isInitialized) return;
        synchronized (lock) {
            if (isInitialized) return;
            try {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                try (Statement st = connection.createStatement()) {
                    st.execute("SET CACHE_SIZE 2048");
                }
                createTables();
                isInitialized = true;
                logger.info("DataUsageMonitor: H2 initialized at " + DB_PATH);
            } catch (Throwable t) {
                logger.error("DataUsageMonitor: init failed: " + t.getMessage(), t);
            }
        }
    }

    private void createTables() throws Exception {
        try (Statement st = connection.createStatement()) {
            // Two independent breakdowns of the SAME total: by transport
            // (wifi/mobile/other) and by origin (app vs system=UID-2000
            // daemons+tunnels). app+system == wifi+mobile+other by construction
            // (same qtaguid rows summed two ways).
            st.execute(
                // NB: the day-key column is named `day_key`, NOT `day` — `DAY` is a
                // RESERVED word in H2 (the DAY() datetime function / field), so
                // `CREATE TABLE (day VARCHAR...)` fails with "Syntax error ...
                // expected identifier" and the whole DB never initializes → sampler
                // never starts → the Data tab shows an empty graph forever. Renaming
                // (rather than quoting "day" everywhere) keeps every query simple.
                "CREATE TABLE IF NOT EXISTS " + TABLE_DAILY + " (" +
                "day_key VARCHAR(10) PRIMARY KEY," + // local yyyy-MM-dd
                "wifi_bytes BIGINT DEFAULT 0," +
                "mobile_bytes BIGINT DEFAULT 0," +
                "other_bytes BIGINT DEFAULT 0," +
                "app_bytes BIGINT DEFAULT 0," +
                "system_bytes BIGINT DEFAULT 0," +
                "updated_at BIGINT DEFAULT 0" +
                ");"
            );
            // Migration for DBs created before the origin split.
            try { st.execute("ALTER TABLE " + TABLE_DAILY + " ADD COLUMN IF NOT EXISTS app_bytes BIGINT DEFAULT 0;"); } catch (Exception ignored) {}
            try { st.execute("ALTER TABLE " + TABLE_DAILY + " ADD COLUMN IF NOT EXISTS system_bytes BIGINT DEFAULT 0;"); } catch (Exception ignored) {}
            st.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_STATE + " (" +
                "id INT PRIMARY KEY," +
                "wifi_last BIGINT DEFAULT 0," +
                "mobile_last BIGINT DEFAULT 0," +
                "other_last BIGINT DEFAULT 0," +
                "app_last BIGINT DEFAULT 0," +
                "system_last BIGINT DEFAULT 0," +
                "last_sample_ms BIGINT DEFAULT 0" +
                ");"
            );
            try { st.execute("ALTER TABLE " + TABLE_STATE + " ADD COLUMN IF NOT EXISTS app_last BIGINT DEFAULT 0;"); } catch (Exception ignored) {}
            try { st.execute("ALTER TABLE " + TABLE_STATE + " ADD COLUMN IF NOT EXISTS system_last BIGINT DEFAULT 0;"); } catch (Exception ignored) {}
        }
    }

    // ==================== SAMPLING ====================

    private void safeSampleTick() {
        try {
            sampleTick();
        } catch (Throwable t) {
            // Never let an exception kill the recurring task (scheduleWithFixedDelay
            // cancels the schedule on an uncaught throw).
            logger.warn("DataUsageMonitor: sample tick failed: " + t.getMessage());
        }
    }

    private void sampleTick() throws Exception {
        long[] cur = readCounters();   // {wifi, mobile, other, app, system} cumulative rx+tx
        if (cur == null) return;       // proc unreadable — recorded nothing

        long now = System.currentTimeMillis();
        long[] last = new long[]{0, 0, 0, 0, 0};
        long lastMs = 0;
        boolean haveState = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT wifi_last, mobile_last, other_last, "
                     + "app_last, system_last, last_sample_ms FROM " + TABLE_STATE + " WHERE id=1")) {
            if (rs.next()) {
                last[0] = rs.getLong(1);
                last[1] = rs.getLong(2);
                last[2] = rs.getLong(3);
                last[3] = rs.getLong(4);
                last[4] = rs.getLong(5);
                lastMs = rs.getLong(6);
                haveState = true;
            }
        }

        // Decide whether to attribute this delta or just re-seed the baseline.
        boolean seedOnly = !haveState || lastMs == 0
                || (now - lastMs) > STALE_BASELINE_MS;

        if (!seedOnly) {
            long dWifi = delta(cur[0], last[0]);
            long dMobile = delta(cur[1], last[1]);
            long dOther = delta(cur[2], last[2]);
            long dApp = delta(cur[3], last[3]);
            long dSystem = delta(cur[4], last[4]);
            if (dWifi > 0 || dMobile > 0 || dOther > 0 || dApp > 0 || dSystem > 0) {
                addToDay(dayKey(now),
                        dWifi, dMobile, dOther, dApp, dSystem, now);
            }
        }

        // Persist the new baseline (MERGE = upsert the single state row).
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO " + TABLE_STATE
                        + " (id, wifi_last, mobile_last, other_last, app_last, system_last, last_sample_ms) "
                        + "KEY(id) VALUES (1, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, cur[0]);
            ps.setLong(2, cur[1]);
            ps.setLong(3, cur[2]);
            ps.setLong(4, cur[3]);
            ps.setLong(5, cur[4]);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    /** Reset-safe delta: a smaller current means the counter reset (reboot /
     *  iface-down), so the delta is the whole current value, never negative. */
    private static long delta(long cur, long last) {
        return cur >= last ? (cur - last) : cur;
    }

    private void addToDay(String day, long wifi, long mobile, long other,
                          long app, long system, long now) throws Exception {
        // Read-modify-write under the single sampler thread (only writer). MERGE
        // with an accumulating subselect is possible but verbose in H2; a plain
        // select-then-upsert is clear and correct given the single writer.
        long curWifi = 0, curMobile = 0, curOther = 0, curApp = 0, curSystem = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT wifi_bytes, mobile_bytes, other_bytes, app_bytes, system_bytes FROM "
                        + TABLE_DAILY + " WHERE day_key=?")) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    curWifi = rs.getLong(1);
                    curMobile = rs.getLong(2);
                    curOther = rs.getLong(3);
                    curApp = rs.getLong(4);
                    curSystem = rs.getLong(5);
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO " + TABLE_DAILY
                        + " (day_key, wifi_bytes, mobile_bytes, other_bytes, app_bytes, system_bytes, updated_at) "
                        + "KEY(day_key) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, day);
            ps.setLong(2, curWifi + wifi);
            ps.setLong(3, curMobile + mobile);
            ps.setLong(4, curOther + other);
            ps.setLong(5, curApp + app);
            ps.setLong(6, curSystem + system);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    /**
     * Cumulative rx+tx bytes for our UIDs (app + UID-2000), split {wifi, mobile,
     * other, app, system}. Two backends, tried in order; returns {@code null}
     * only if BOTH fail (logged once → the UI shows "unavailable"):
     * <ol>
     *   <li>{@code /proc/net/xt_qtaguid/stats} — legacy classic-kernel path. Fast
     *       when present, but ABSENT on this device (Android 10 moved to eBPF).</li>
     *   <li>{@code dumpsys netstats detail} — the universal path. Works on BOTH
     *       the legacy and eBPF backends and is readable by the UID-2000 shell
     *       daemon (~20ms, 125 lines here).</li>
     * </ol>
     * Loopback is excluded in both (qtaguid skips {@code lo}; dumpsys UID stats
     * are keyed by network ident — WIFI/MOBILE — and never include loopback), so
     * the app→sing-box/tailscale proxy hop is not counted and the single real WAN
     * egress under UID 2000 is counted once. Only untagged ({@code tag=0x0})
     * per-UID rows are summed — tagged rows are a subset and would double-count.
     */
    private long[] readCounters() {
        long[] viaProc = readCountersQtaguid();
        if (viaProc != null) { available = true; return viaProc; }
        long[] viaDumpsys = readCountersDumpsys();
        if (viaDumpsys != null) { available = true; return viaDumpsys; }
        if (!sourceWarned) {
            logger.warn("DataUsageMonitor: no usable netstats source (qtaguid absent AND "
                    + "dumpsys netstats parse failed) — data usage cannot be recorded");
            sourceWarned = true;
        }
        available = false;
        return null;
    }

    /** Legacy /proc/net/xt_qtaguid/stats reader. Returns null if the file is
     *  absent/unreadable (the common case on API 28+ eBPF kernels). */
    private long[] readCountersQtaguid() {
        java.io.File f = new java.io.File(QTAGUID_PATH);
        if (!f.exists()) return null;
        long wifi = 0, mobile = 0, other = 0, app = 0, system = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 8) continue;
                String iface = p[1];
                if ("iface".equals(iface)) continue;   // header row
                if ("lo".equals(iface)) continue;       // loopback — proxy hops
                if (!isUntagged(p[2])) continue;         // tagged rows are a subset
                int uid;
                try { uid = Integer.parseInt(p[3]); } catch (NumberFormatException e) { continue; }
                boolean isApp = appUid > 0 && uid == appUid;
                if (uid != SHELL_UID && !isApp) continue;
                long rx, tx;
                try {
                    rx = Long.parseLong(p[5]);
                    tx = Long.parseLong(p[7]);
                } catch (NumberFormatException e) { continue; }
                long bytes = rx + tx;
                if (iface.startsWith("wlan")) wifi += bytes;
                else if (iface.startsWith("rmnet") || iface.startsWith("ccmni")
                        || iface.startsWith("rndis") || iface.startsWith("ppp")) mobile += bytes;
                else other += bytes;
                if (isApp) app += bytes; else system += bytes;
            }
        } catch (Throwable t) {
            return null;
        }
        return new long[]{wifi, mobile, other, app, system};
    }

    /**
     * Parse {@code dumpsys netstats detail} — the eBPF-era universal source.
     * Only the "UID stats:" section is parsed (the Dev/Xt sections are device-wide
     * uid=-1 aggregates and the "UID tag stats:" section is a tagged SUBSET — both
     * would double-count). Within it, each block is:
     * <pre>
     *   ident=[{type=WIFI, ...}] uid=N set=DEFAULT|FOREGROUND tag=0x0
     *     NetworkStatsHistory: bucketDuration=..
     *       st=.. rb=&lt;rx&gt; rp=.. tb=&lt;tx&gt; tp=.. op=..
     *       st=.. rb=.. tb=..            (more buckets)
     * </pre>
     * We keep only {@code tag=0x0} blocks for our two UIDs, read {@code type=} for
     * the transport, and sum {@code rb+tb} across ALL buckets (cumulative since
     * boot). {@code set=DEFAULT} and {@code set=FOREGROUND} are disjoint, so
     * summing both is correct — the delta layer handles the since-boot cumulative.
     */
    private long[] readCountersDumpsys() {
        String out = execShell("dumpsys netstats detail");
        if (out == null || out.isEmpty()) return null;
        long wifi = 0, mobile = 0, other = 0, app = 0, system = 0;
        boolean sawUidSection = false, inUidSection = false;
        // State for the current ident block.
        boolean blockMatches = false;
        boolean blockIsApp = false;
        int blockTransport = 0; // 0=other,1=wifi,2=mobile
        try {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                // Section boundaries: a header at column 0 (no leading spaces).
                if (!raw.startsWith(" ") && raw.endsWith(":")) {
                    inUidSection = "UID stats:".equals(line);
                    if (inUidSection) sawUidSection = true;
                    blockMatches = false;
                    continue;
                }
                if (!inUidSection) continue;

                if (line.startsWith("ident=")) {
                    // New per-UID block header. Decide if it's one of ours + untagged.
                    blockMatches = false;
                    int uid = parseIntAfter(line, "uid=");
                    String tag = parseTokenAfter(line, "tag=");
                    boolean isApp = appUid > 0 && uid == appUid;
                    boolean ours = (uid == SHELL_UID) || isApp;
                    if (ours && isUntagged(tag)) {
                        blockMatches = true;
                        blockIsApp = isApp;
                        String type = parseTypeIdent(line);   // WIFI / MOBILE / ...
                        blockTransport = "WIFI".equals(type) ? 1
                                : ("MOBILE".equals(type) ? 2 : 0);
                    }
                    continue;
                }
                if (blockMatches && line.startsWith("st=")) {
                    long rb = parseLongAfter(line, "rb=");
                    long tb = parseLongAfter(line, "tb=");
                    long bytes = rb + tb;
                    if (blockTransport == 1) wifi += bytes;
                    else if (blockTransport == 2) mobile += bytes;
                    else other += bytes;
                    if (blockIsApp) app += bytes; else system += bytes;
                }
                // "NetworkStatsHistory:" and other lines inside a block are ignored.
            }
        } catch (Throwable t) {
            return null;
        }
        if (!sawUidSection) return null;   // unexpected format → let caller warn
        return new long[]{wifi, mobile, other, app, system};
    }

    // Whether at least one backend produced a reading since start (drives the
    // UI "available" flag). Volatile: written on the sampler thread, read on HTTP.
    private volatile boolean available = false;
    private volatile boolean sourceWarned = false;

    /** Run a shell command and return stdout (null on failure). Bounded so a
     *  wedged dumpsys can't hang the sampler thread. */
    private static String execShell(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(false).start();
            StringBuilder sb = new StringBuilder(8192);
            try (BufferedReader r = new BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(8, TimeUnit.SECONDS);
            try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code type=WIFI} inside the {@code ident=[{...}]} of a netstats line. */
    private static String parseTypeIdent(String line) {
        return parseTokenAfter(line, "type=");
    }

    /** Integer immediately after {@code key} (e.g. "uid=") up to the next
     *  whitespace/comma/brace. Returns -1 if absent/unparseable. */
    private static int parseIntAfter(String line, String key) {
        String tok = parseTokenAfter(line, key);
        try { return Integer.parseInt(tok); } catch (Exception e) { return -1; }
    }

    private static long parseLongAfter(String line, String key) {
        String tok = parseTokenAfter(line, key);
        try { return Long.parseLong(tok); } catch (Exception e) { return 0L; }
    }

    /** The token right after {@code key}, delimited by whitespace, ',', '}', ']'.
     *  "" if key not present. */
    private static String parseTokenAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        int start = i + key.length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == ' ' || c == ',' || c == '}' || c == ']' || c == '\t') break;
            end++;
        }
        return line.substring(start, end);
    }

    /** True when the acct_tag_hex column is the untagged/grand-total tag (0x0),
     *  in either the short "0x0" or zero-padded "0x00...0" form. Parse the hex and
     *  compare to 0 so any width is handled; a malformed tag is treated as tagged
     *  (skip) so we never accidentally sum a subset row into the total. */
    private static boolean isUntagged(String acctTagHex) {
        if (acctTagHex == null) return false;
        String s = acctTagHex.startsWith("0x") || acctTagHex.startsWith("0X")
                ? acctTagHex.substring(2) : acctTagHex;
        if (s.isEmpty()) return false;
        try {
            return Long.parseLong(s, 16) == 0L;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ==================== QUERY (HTTP) ====================

    /**
     * Per-day usage for the last {@code days} days (inclusive of today), oldest
     * first, plus rolled-up totals. Shape:
     * <pre>{@code
     * {
     *   "enabled": true,
     *   "available": true,          // false when qtaguid was unreadable
     *   "days": [ {"date":"2026-07-16","wifi":123,"mobile":45,"other":0,"total":168}, ... ],
     *   "totalWifi": ..., "totalMobile": ..., "totalOther": ..., "total": ...
     * }
     * }</pre>
     * All byte values are longs. Missing days are omitted (client renders gaps as
     * zero). Safe to call from the HTTP thread (same-JVM H2 read).
     */
    public JSONObject getUsage(int days) {
        JSONObject out = new JSONObject();
        try {
            boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isDataUsageEnabled();
            out.put("enabled", enabled);
            // available flips true once any backend yields a reading. When the
            // feature was just enabled and no tick has run yet, report true
            // (optimistic) so the UI shows "collecting" rather than "unavailable";
            // it only goes false after a sample actually failed both backends.
            out.put("available", available || !sourceWarned);
            if (!isInitialized) {
                out.put("days", new JSONArray());
                out.put("totalWifi", 0);
                out.put("totalMobile", 0);
                out.put("totalOther", 0);
                out.put("totalApp", 0);
                out.put("totalSystem", 0);
                out.put("total", 0);
                return out;
            }
            if (days <= 0) days = 30;
            long now = System.currentTimeMillis();
            String from = dayKey(now - (days - 1L) * 86400000L);

            JSONArray arr = new JSONArray();
            long tW = 0, tM = 0, tO = 0, tA = 0, tS = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT day_key, wifi_bytes, mobile_bytes, other_bytes, app_bytes, system_bytes FROM "
                            + TABLE_DAILY + " WHERE day_key >= ? ORDER BY day_key ASC")) {
                ps.setString(1, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long w = rs.getLong(2), m = rs.getLong(3), o = rs.getLong(4);
                        long a = rs.getLong(5), s = rs.getLong(6);
                        JSONObject d = new JSONObject();
                        d.put("date", rs.getString(1));
                        d.put("wifi", w);
                        d.put("mobile", m);
                        d.put("other", o);
                        d.put("app", a);
                        d.put("system", s);
                        d.put("total", w + m + o);
                        arr.put(d);
                        tW += w; tM += m; tO += o; tA += a; tS += s;
                    }
                }
            }
            out.put("days", arr);
            out.put("totalWifi", tW);
            out.put("totalMobile", tM);
            out.put("totalOther", tO);
            out.put("totalApp", tA);
            out.put("totalSystem", tS);
            out.put("total", tW + tM + tO);
        } catch (Throwable t) {
            logger.warn("DataUsageMonitor.getUsage failed: " + t.getMessage());
            try { out.put("error", String.valueOf(t.getMessage())); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Clear all recorded history (both the daily rows and the delta baseline).
     *  Used by the performance page's Reset action. */
    public boolean resetHistory() {
        if (!isInitialized) return false;
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM " + TABLE_DAILY);
            st.execute("DELETE FROM " + TABLE_STATE);
            logger.info("DataUsageMonitor: history reset");
            return true;
        } catch (Throwable t) {
            logger.warn("DataUsageMonitor.resetHistory failed: " + t.getMessage());
            return false;
        }
    }

    /** Close the H2 connection on daemon shutdown. */
    public void shutdown() {
        stop();
        synchronized (lock) {
            if (connection != null) {
                try { connection.close(); } catch (Throwable ignored) {}
                connection = null;
            }
            isInitialized = false;
        }
    }
}
