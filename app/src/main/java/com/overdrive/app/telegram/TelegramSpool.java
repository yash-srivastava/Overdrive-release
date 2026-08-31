package com.overdrive.app.telegram;

import android.util.Log;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Tiny on-disk spool for Telegram notifications that could not be delivered to
 * {@link com.overdrive.app.daemon.TelegramBotDaemon} because the daemon was not
 * listening (it only runs during ACC OFF, and is SIGKILL'd on ACC ON).
 *
 * <p>Without this, any notify* emitted while the daemon is down — a proximity
 * alert while driving, or the closing photo of the last surveillance event in
 * the ACC-on teardown race — was lost on the floor with only a {@code Log.d}.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>One JSON file per spooled command under {@link #DIR} (owner-only perms;
 *       every emitter and the drainer run as UID 2000, so it must NOT be
 *       world-writable — that would be a local command-injection surface).</li>
 *   <li>Only commands the emit site explicitly marks spoolable are written, and
 *       only on a definitive "daemon not listening" failure (ConnectException) —
 *       NOT on a read timeout, where the daemon may already have processed the
 *       send (avoids a duplicate).</li>
 *   <li>Bounded: at most {@link #MAX_ENTRIES} files; on drain, entries older
 *       than {@link #MAX_AGE_MS} are dropped unsent so a long drive can't
 *       deliver a stale wall of alerts when the car finally parks.</li>
 *   <li>Each entry carries {@link #K_SPOOLED_AT} so the daemon can apply the age
 *       cap. The daemon replays each entry through its normal command handler,
 *       which re-applies the category/owner gate and re-checks file existence,
 *       so a replay is gated exactly like a live send.</li>
 * </ul>
 *
 * <p>The spool is best-effort: a write failure (e.g. the rare app-UID caller
 * that can't write the sticky /data/local/tmp) degrades to the previous
 * drop-with-log behaviour — never throws into the emit path.
 */
public final class TelegramSpool {

    private static final String TAG = "TelegramSpool";

    /**
     * Owner-only subdir. Every emitter (surveillance engine + proximity handler
     * in CameraDaemon) and the drainer (TelegramBotDaemon) run as UID 2000, so
     * no world bits are needed — and world-writable would be a local
     * command-injection surface (a crafted entry would be replayed verbatim).
     */
    static final String DIR = ScratchPaths.path(".overdrive_tg_spool");

    /** Cap on spooled files. Oldest is evicted past this so the dir can't grow. */
    static final int MAX_ENTRIES = 50;

    /** Entries older than this are dropped unsent on drain (stale ⇒ noise). */
    static final long MAX_AGE_MS = 30L * 60L * 1000L;  // 30 minutes

    /** Injected timestamp (ms epoch) so the daemon can age-cap on drain. */
    static final String K_SPOOLED_AT = "_spooledAt";

    /**
     * Suffix marking an entry that has been handed to send() but whose outcome
     * is unknown (the daemon may be SIGKILL'd mid-send). On the next drain these
     * are reaped WITHOUT replay — at-most-once delivery, preferred over the
     * double-send a delete-after-send would risk.
     */
    static final String INFLIGHT_SUFFIX = ".inflight";

    /**
     * Suffix for an entry still being written by offer(). Does NOT end in
     * ".json", so drain()'s filter never picks up a half-written file; offer()
     * atomically renames it to the final ".json" name once fully written.
     */
    static final String WRITING_SUFFIX = ".writing";

    /**
     * A {@link #WRITING_SUFFIX} temp younger than this is assumed to be an
     * actively-writing emitter (a real write completes in ms), so drain() does
     * NOT reap it — guards the sub-second cross-process seam at daemon spawn.
     */
    static final long WRITING_ORPHAN_MIN_AGE_MS = 60_000L;

    private TelegramSpool() {}

    /**
     * Persist a command for later replay. Returns true if it was written.
     * The {@code command} is stored verbatim plus a {@link #K_SPOOLED_AT}
     * stamp; the daemon strips internal keys before sending.
     */
    public static synchronized boolean offer(JSONObject command) {
        if (command == null) return false;
        try {
            File dir = ensureDir();
            if (dir == null) return false;

            evictOldestIfFull(dir);

            JSONObject copy = new JSONObject(command.toString());
            copy.put(K_SPOOLED_AT, System.currentTimeMillis());

            // currentTimeMillis prefix keeps lexical order ≈ chronological so
            // the daemon can drain oldest-first by sorting filenames. The
            // nanoTime suffix disambiguates same-ms writes from parallel
            // emitters (engine + proximity) without a shared counter.
            String name = System.currentTimeMillis() + "_"
                    + Long.toHexString(System.nanoTime()) + ".json";
            File f = new File(dir, name);

            // ATOMIC publish: the emitter (UID 2000) and the drainer (UID 2000,
            // separate JVM) race with no cross-process lock — synchronized here
            // is per-JVM only. Writing directly to the final ".json" name lets
            // drain() (which filters *.json then read()s) pick up a 0-byte or
            // truncated file mid-write and delete it unsent — losing the exact
            // event the spool exists to save. Write to a ".writing" temp (NOT
            // matched by drain's *.json filter) then atomically rename, so the
            // drainer only ever sees a complete file.
            File tmp = new File(dir, name + WRITING_SUFFIX);
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(copy.toString());
            }
            // Owner-only — the drainer is the same UID 2000. No world bits.
            try { tmp.setReadable(true, true); } catch (Exception ignored) {}
            try { tmp.setWritable(true, true); } catch (Exception ignored) {}
            if (!tmp.renameTo(f)) {
                // Rename failed — don't leave a stray temp; drop (the event is
                // still on disk, only the Telegram push is lost, same as before).
                try { //noinspection ResultOfMethodCallIgnored
                    tmp.delete(); } catch (Exception ignored) {}
                Log.w(TAG, "Spool publish rename failed; dropping "
                        + command.optString("cmd", "?"));
                return false;
            }
            Log.w(TAG, "Spooled Telegram cmd '" + command.optString("cmd", "?")
                    + "' (daemon down) → " + f.getName());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Spool offer failed (dropping): " + t.getMessage());
            return false;
        }
    }

    /**
     * Drain the spool, oldest-first. For each entry, {@code sender} is invoked
     * with the parsed command (internal keys stripped); the file is deleted
     * regardless of the sender's outcome so a single replay attempt is made and
     * the spool always drains (no retry storm). Entries older than
     * {@link #MAX_AGE_MS} are deleted without sending.
     *
     * <p>Intended to run once on daemon startup, after the IPC/HTTP stack is up.
     * Safe to call when the dir is empty/absent.
     *
     * @return number of entries actually replayed (sent to {@code sender})
     */
    public static synchronized int drain(SpooledSender sender) {
        if (sender == null) return 0;
        File dir = new File(DIR);
        if (!dir.isDirectory()) return 0;

        // First, reap any orphaned .inflight markers from a PRIOR drain that was
        // SIGKILL'd mid-send (AccSentryDaemon kill -9's the daemon the instant
        // ACC turns ON — the exact event the spool exists for). An .inflight
        // file was already handed to send() once, so we MUST NOT replay it —
        // that would double-deliver. At-most-once: drop it unsent.
        long reapNow = System.currentTimeMillis();
        File[] orphans = dir.listFiles((d, n) ->
                n.endsWith(INFLIGHT_SUFFIX) || n.endsWith(WRITING_SUFFIX));
        if (orphans != null) {
            for (File o : orphans) {
                // .inflight = already handed to send() once (don't replay →
                // at-most-once). .writing = an offer() that crashed mid-write
                // (incomplete, can't trust it).
                //
                // For .writing ONLY, age-guard the reap: the no-overlap invariant
                // (emitters live ACC-ON, drainer live ACC-OFF) has a sub-second
                // seam at daemon spawn where a still-living emitter could be
                // mid-write. A real write completes in ms, so only reap a
                // .writing temp that's clearly an orphan (older than the guard);
                // never delete one a living emitter is actively writing.
                if (o.getName().endsWith(WRITING_SUFFIX)) {
                    long age = reapNow - o.lastModified();
                    // age < guard → too fresh to reap. A FUTURE-dated temp
                    // (age < 0, cross-process clock skew / NTP-GPS correction)
                    // is also "too fresh" — a living emitter may still be
                    // renaming it — so don't reap it either (consistent with the
                    // backward-clock rescue in the age cap below).
                    if (age < WRITING_ORPHAN_MIN_AGE_MS) continue;
                }
                Log.w(TAG, "Reaping orphaned spool temp (not replaying): " + o.getName());
                try { //noinspection ResultOfMethodCallIgnored
                    o.delete(); } catch (Exception ignored) {}
            }
        }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return 0;

        Arrays.sort(files, Comparator.comparing(File::getName));  // oldest-first
        long now = System.currentTimeMillis();
        int replayed = 0;
        for (File f : files) {
            JSONObject cmd;
            long stamped;
            try {
                cmd = read(f);
                if (cmd == null) { safeDelete(f); continue; }
                stamped = cmd.optLong(K_SPOOLED_AT, 0L);
            } catch (Throwable t) {
                Log.w(TAG, "Spool replay parse failed for " + f.getName()
                        + ": " + t.getMessage());
                safeDelete(f);
                continue;
            }

            // Age cap, with a one-sided wall-clock sanity guard. K_SPOOLED_AT and
            // `now` are both System.currentTimeMillis() (non-monotonic). Only a
            // BACKWARD clock jump is rescued (age < 0 → deliver): a forward jump
            // is indistinguishable from genuine elapsed time, so an upper-bound
            // "too old = must be a clock correction" heuristic would just defeat
            // the cap for the longest drives (which carry the largest, stalest
            // backlogs). A rare forward-correction false-drop is far less harmful
            // than delivering hours-old alerts on park as if fresh.
            long age = now - stamped;
            if (stamped > 0 && age > MAX_AGE_MS) {
                Log.w(TAG, "Dropping stale spooled cmd " + f.getName()
                        + " (age " + (age / 1000) + "s)");
                safeDelete(f);
                continue;
            }
            // (age < 0, a backward clock jump, falls through here → delivered.)

            // AT-MOST-ONCE: claim the entry by renaming to .inflight BEFORE the
            // (blocking, possibly-30-60s-on-429) send, then delete it after the
            // single attempt regardless of outcome. We deliberately do NOT
            // re-arm on a false/failed return: a Telegram send that returns
            // false is frequently an AMBIGUOUS outcome (the POST reached
            // Telegram and was delivered, but the response read timed out / the
            // connection reset on flaky park-boundary mobile data), and there is
            // no outbound idempotency key — re-arming would duplicate an
            // already-delivered critical/proximity alert on the next drain. One
            // attempt, at-most-once, matches the class contract; the underlying
            // event is always still recorded to disk.
            File inflight = new File(dir, f.getName() + INFLIGHT_SUFFIX);
            if (!f.renameTo(inflight)) {
                // Couldn't claim it — skip rather than risk a double-send race.
                Log.w(TAG, "Could not claim spool entry " + f.getName() + "; skipping");
                continue;
            }
            cmd.remove(K_SPOOLED_AT);
            try {
                if (sender.send(cmd)) replayed++;
            } catch (Throwable t) {
                Log.w(TAG, "Spool replay send error for " + f.getName() + ": " + t.getMessage());
            } finally {
                safeDelete(inflight);
            }
        }
        if (replayed > 0) Log.w(TAG, "Drained " + replayed + " spooled Telegram cmd(s)");
        return replayed;
    }

    private static void safeDelete(File f) {
        try { //noinspection ResultOfMethodCallIgnored
            f.delete(); } catch (Exception ignored) {}
    }

    /**
     * Replay target — the daemon supplies its own in-process command handler.
     * The return value is advisory (used only for the drained-count log); the
     * entry is consumed after one attempt either way (at-most-once). Return true
     * if the command was acted on, false/throw otherwise.
     */
    public interface SpooledSender {
        boolean send(JSONObject command) throws Exception;
    }

    private static File ensureDir() {
        File dir = new File(DIR);
        if (dir.isDirectory()) return dir;
        try {
            if (dir.mkdirs() || dir.isDirectory()) {
                // OWNER-ONLY perms (ownerOnly=true). Every spool emitter (the
                // surveillance engine + proximity handler inside CameraDaemon)
                // and the drainer (TelegramBotDaemon) run as UID 2000, so the
                // dir does NOT need to be world-writable. A world-writable
                // spool would let any local UID drop a crafted command that the
                // daemon replays verbatim — chat-spoof / file-exfil surface.
                try { dir.setReadable(true, true); } catch (Exception ignored) {}
                try { dir.setWritable(true, true); } catch (Exception ignored) {}
                try { dir.setExecutable(true, true); } catch (Exception ignored) {}
                return dir;
            }
        } catch (Exception e) {
            Log.w(TAG, "Spool dir create failed: " + e.getMessage());
        }
        return dir.isDirectory() ? dir : null;
    }

    private static void evictOldestIfFull(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length < MAX_ENTRIES) return;
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName));  // oldest-first
        // Evict down to MAX_ENTRIES-1 so the incoming write fits under the cap.
        // Chronological (oldest-first) eviction is intentional/simple — but log
        // each drop at WARN so a full-spool loss is observable in logcat (it was
        // previously the only silent drop path in this class).
        int toRemove = list.size() - (MAX_ENTRIES - 1);
        for (int i = 0; i < toRemove && i < list.size(); i++) {
            File victim = list.get(i);
            Log.w(TAG, "Spool full (" + list.size() + "/" + MAX_ENTRIES
                    + "); evicting oldest unsent entry " + victim.getName());
            try { //noinspection ResultOfMethodCallIgnored
                victim.delete(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject read(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        if (sb.length() == 0) return null;
        return new JSONObject(sb.toString());
    }
}
