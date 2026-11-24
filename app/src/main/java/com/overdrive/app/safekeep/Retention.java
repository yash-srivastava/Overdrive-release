package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What local rotation must keep while SafeKeep is on. Pins (awaiting upload) are bounded by
 * size and age (CS-9); locks are user-chosen, bounded by a quota and persisted in a small file
 * so they hold before the engine is up. Lookups are O(1) map reads.
 */
public final class Retention {

    private static final long PIN_MAX_AGE_MS = 72L * 3600_000L;
    static final File LOCKS_FILE = new File(com.overdrive.app.util.ScratchRoot.path(".safekeep/locks.json"));
    /** Share of a category's limit that pins may hold (design CS-9), and that locks may hold. */
    private static final double PIN_SHARE = 0.20;
    private static final double LOCK_SHARE = 0.50;
    /** Rotation honours locks up to this share, so a lowered limit still converges. */
    private static final double LOCK_HONOR_SHARE = 0.70;

    private static final class Entry {
        final long size;
        final long since;

        Entry(long size, long since) {
            this.size = size;
            this.since = since;
        }
    }

    private volatile Map<String, Entry> pins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();
    private volatile long pinCapBytes = 4L << 30;
    private volatile long lockCapBytes = 8L << 30;

    void configure(SafeKeepConfig cfg) {
        pinCapBytes = (long) (cfg.limits.pinMaxGb * (1L << 30));
        lockCapBytes = (long) (cfg.limits.lockMaxGb * (1L << 30));
    }

    /** Swaps in a freshly built pin set: rotation never sees an empty set mid-rebuild. */
    void replacePins(List<Ledger.Item> items) {
        ConcurrentHashMap<String, Entry> fresh = new ConcurrentHashMap<>();
        for (Ledger.Item it : items) {
            if (it.localPath != null) fresh.putIfAbsent(it.localPath, new Entry(it.sizeBytes, it.createdAt));
        }
        pins = fresh;
    }

    void pin(String path, long size, long since) {
        if (path != null) pins.putIfAbsent(path, new Entry(size, since));
    }

    void unpin(String path) {
        if (path != null) pins.remove(path);
    }

    /** Unpins once no destination still has the file queued or in flight. */
    void unpinIfIdle(String path, Ledger ledger) {
        if (path != null && !ledger.hasOpenRowForPath(path)) unpin(path);
    }

    void clearPins() {
        pins = new ConcurrentHashMap<>();
    }

    /**
     * The files one rotation pass must keep, newest first: locks up to 70 % of the category limit,
     * then fresh pins up to min(20 %, pin cap). Everything else rotates as if SafeKeep were off.
     */
    Set<String> retainedAmong(List<File> oldestFirst, long limitBytes) {
        if (limitBytes <= 0 || (locks.isEmpty() && pins.isEmpty())) return Collections.emptySet();
        Map<String, Entry> p = pins;
        long lockBudget = (long) (limitBytes * LOCK_HONOR_SHARE);
        long pinBudget = Math.min(pinCapBytes, (long) (limitBytes * PIN_SHARE));
        long now = System.currentTimeMillis();
        long lockedKept = 0, pinnedKept = 0;
        Set<String> keep = new HashSet<>();
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            File f = oldestFirst.get(i);
            String path = f.getAbsolutePath();
            Entry l = locks.get(path);
            if (l != null) {
                long size = Math.max(0, RemovableStorageFs.length(f));
                if (lockedKept + size <= lockBudget) {
                    lockedKept += size;
                    keep.add(path);
                }
                continue;
            }
            Entry e = p.get(path);
            if (e == null || now - e.since > PIN_MAX_AGE_MS) continue;
            long size = Math.max(0, RemovableStorageFs.length(f));
            if (pinnedKept + size <= pinBudget) {
                pinnedKept += size;
                keep.add(path);
            }
        }
        return keep;
    }

    public boolean isLocked(File f) {
        return f != null && locks.containsKey(f.getAbsolutePath());
    }

    /** A row belongs to a locked clip: its own path, or the clip's .mp4 for a sidecar. */
    boolean isLockedItem(Ledger.Item it) {
        if (it == null || it.localPath == null) return false;
        if (locks.containsKey(it.localPath)) return true;
        File dir = new File(it.localPath).getParentFile();
        return dir != null && it.groupKey != null && locks.containsKey(new File(dir, it.groupKey + ".mp4").getAbsolutePath());
    }

    /** Badge lookups by file name only (no index round trip per clip). */
    public boolean isLockedName(String fileName) {
        if (fileName == null) return false;
        for (String p : locks.keySet()) {
            if (p.endsWith("/" + fileName)) return true;
        }
        return false;
    }

    public long lockedBytes() {
        long t = 0;
        for (Entry e : locks.values()) t += Math.max(0, e.size);
        return t;
    }

    public long lockCapBytes() {
        return lockCapBytes;
    }

    /**
     * Refuses when the lock quota, or half of the clip's own category limit, would be exceeded
     * (beyond that rotation could no longer converge).
     */
    synchronized boolean addLock(String path, long size, long categoryLimitBytes) {
        if (path == null) return false;
        if (locks.containsKey(path)) return true;
        long s = Math.max(0, size);
        if (lockedBytes() + s > lockCapBytes) return false;
        if (categoryLimitBytes > 0) {
            String cat = categoryOf(new File(path).getName());
            long inCategory = 0;
            for (Map.Entry<String, Entry> e : locks.entrySet()) {
                if (cat.equals(categoryOf(new File(e.getKey()).getName()))) inCategory += Math.max(0, e.getValue().size);
            }
            if (inCategory + s > categoryLimitBytes * LOCK_SHARE) return false;
        }
        locks.put(path, new Entry(s, System.currentTimeMillis()));
        if (saveLocks()) return true;
        // Not durable, so not locked: memory and file never disagree.
        locks.remove(path);
        return false;
    }

    /** StorageManager's rotation pool for a clip name (proximity clips rotate with recordings). */
    static String categoryOf(String name) {
        return name.startsWith("event_") ? "surveillance" : "recordings";
    }

    /** False when the unlock could not be saved (the lock then stays, in memory and on disk). */
    synchronized boolean removeLock(String path) {
        if (path == null) return true;
        Entry prev = locks.remove(path);
        if (prev == null || saveLocks()) return true;
        locks.put(path, prev);
        return false;
    }

    /** Stems of locked clips (their sidecars share the stem as ledger group key). */
    Set<String> lockedStems() {
        Set<String> out = new HashSet<>();
        for (String p : locks.keySet()) out.add(Naming.stem(new File(p).getName()));
        return out;
    }

    /** Drops locks whose clip no longer exists (only when its folder is readable, CS-8). */
    synchronized void pruneLocks() {
        boolean changed = false;
        Map<File, Boolean> readable = new HashMap<>();
        for (String p : new java.util.ArrayList<>(locks.keySet())) {
            File f = new File(p);
            if (!RemovableStorageFs.isConfirmedAbsent(f)) continue;
            File dir = f.getParentFile();
            Boolean ok = readable.get(dir);
            if (ok == null) {
                String[] listing = dir == null ? null : RemovableStorageFs.list(dir);
                ok = listing != null && listing.length > 0;
                readable.put(dir, ok);
            }
            if (ok) {
                locks.remove(p);
                changed = true;
            }
        }
        if (changed) saveLocks();
    }

    /** Loads the durable lock list (a missing or unreadable file means no locks). */
    synchronized void loadLocks() {
        Map<String, Entry> loaded = new HashMap<>();
        try {
            if (RemovableStorageFs.isFile(LOCKS_FILE) && RemovableStorageFs.length(LOCKS_FILE) < 1024 * 1024) {
                JSONArray a = new JSONObject(new String(RemovableStorageFs.readAllBytes(LOCKS_FILE),
                        StandardCharsets.UTF_8)).optJSONArray("locks");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && !o.optString("path").isEmpty()) {
                        loaded.put(o.optString("path"), new Entry(o.optLong("size"), o.optLong("at")));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        locks.clear();
        locks.putAll(loaded);
    }

    private boolean saveLocks() {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<String, Entry> e : locks.entrySet()) {
                a.put(new JSONObject().put("path", e.getKey()).put("size", e.getValue().size)
                        .put("at", e.getValue().since));
            }
            Staging.writeAtomic(LOCKS_FILE, new JSONObject().put("locks", a).toString()
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public int pinCount() {
        return pins.size();
    }

    public long pinnedBytes() {
        long t = 0;
        for (Entry e : pins.values()) t += Math.max(0, e.size);
        return t;
    }

    public List<String> expiredPins() {
        List<String> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : pins.entrySet()) {
            if (now - e.getValue().since > PIN_MAX_AGE_MS) out.add(e.getKey());
        }
        return out;
    }
}
