package com.overdrive.app.daemon;

import android.os.FileObserver;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.storage.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the {@link FileObserver} set that drives live updates into
 * {@link RecordingsIndex}. Decoupled from {@link CameraDaemon} init so the
 * daemon's main bootstrap stays small.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>{@link #start()} — register observers for every recordings,
 *       surveillance, and proximity dir reported by {@link StorageManager}.
 *       Idempotent; calling start() twice rebuilds the watcher list (used
 *       when storage type swaps INTERNAL ↔ SD_CARD ↔ USB and the active
 *       dir set changes underneath us).
 *   <li>{@link #refresh()} — same as start(), but a no-op when no observer
 *       set has been built yet. The HTTP storage-type setters call this to
 *       reattach observers to the new dirs.
 *   <li>{@link #stop()} — call from the daemon shutdown path. Calls
 *       stopWatching() on every registered observer.
 * </ul>
 *
 * <p><b>Why each event matters:</b>
 * <ul>
 *   <li>{@code CREATE} / {@code MOVED_TO} on {@code .mp4} — the recorder's
 *       finalised mp4 lands here. Upserts an index row.
 *   <li>{@code DELETE} / {@code MOVED_FROM} on {@code .mp4} — storage
 *       cleanup or external host PC delete. Drops the row.
 *   <li>{@code MODIFY} on {@code .json} — sidecar updates. SidecarGeoUpdater
 *       writes geo into the JSON after the mp4 is already on disk; the index
 *       row created at mp4-close time has no place_short yet, so we re-upsert
 *       the matching mp4 to pull the freshly-written sidecar fields.
 * </ul>
 *
 * <p><b>Failure modes (best-effort, periodic reconcile is the backstop):</b>
 * <ul>
 *   <li>FUSE-mounted SD cards drop inotify events silently. The 1-hour
 *       reconcile in {@link CameraDaemon#startPeriodicMemoryLogging()}
 *       walks every dir and patches the index whether or not these events
 *       fire.
 *   <li>FileObserver itself can throw on registration when a dir is on a
 *       FUSE mount the kernel refuses to inotify-arm. We catch + log per
 *       dir; one bad dir doesn't tank the rest.
 *   <li>The kernel inotify watch limit is per-uid; daemon UID 2000 has
 *       headroom but if it's exhausted some observers will silently no-op.
 *       Same backstop covers it.
 * </ul>
 *
 * <p><b>Concurrency:</b> all mutators (start, refresh, stop) acquire the
 * monitor on this object. Observer event callbacks run on the inotify
 * thread inside {@code FileObserver}; they do their work via
 * {@code RecordingsIndex} (which has its own internal monitor) and never
 * touch this watcher's state.
 */
public final class RecordingsIndexFileWatcher {

    private static final String TAG = "RecordingsIndexFileWatcher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int EVENT_MASK =
            FileObserver.CREATE
            | FileObserver.MOVED_TO
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MODIFY
            | FileObserver.CLOSE_WRITE;

    private static volatile RecordingsIndexFileWatcher INSTANCE;

    public static synchronized RecordingsIndexFileWatcher getInstance() {
        if (INSTANCE == null) INSTANCE = new RecordingsIndexFileWatcher();
        return INSTANCE;
    }

    /** Live set of registered observers. Replaced atomically on refresh. */
    private final List<FileObserver> observers = new ArrayList<>();

    /** Whether start() has ever been called — gates refresh()'s no-op. */
    private volatile boolean started = false;

    private RecordingsIndexFileWatcher() {}

    /**
     * Register observers for every recordings/surveillance/proximity dir.
     * Tears down any previously-registered observers first, so it's safe
     * to call repeatedly when the storage-type set changes.
     */
    public synchronized void start() {
        stopInternal();
        try {
            StorageManager sm = StorageManager.getInstance();
            Set<String> seen = new HashSet<>();
            registerAll(sm.getAllRecordingsDirs(), seen);
            registerAll(sm.getAllSurveillanceDirs(), seen);
            registerAll(sm.getAllProximityDirs(), seen);
            started = true;
            CameraDaemon.log("RecordingsIndexFileWatcher: " + observers.size()
                    + " observer(s) registered across " + seen.size() + " unique dir(s)");
        } catch (Throwable t) {
            CameraDaemon.log("RecordingsIndexFileWatcher start failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            logger.error("start failed", t);
        }
    }

    /**
     * Re-register observers against the current StorageManager dir set.
     * Called by storage-type-change paths so observers follow the active
     * dirs when the user switches INTERNAL ↔ SD_CARD ↔ USB. No-op if
     * start() has not yet been called (avoids racing init).
     */
    public synchronized void refresh() {
        if (!started) return;
        start();
    }

    public synchronized void stop() {
        stopInternal();
        started = false;
    }

    private void stopInternal() {
        for (FileObserver o : observers) {
            try { o.stopWatching(); }
            catch (Throwable t) {
                logger.warn("stopWatching threw: " + t.getMessage());
            }
        }
        observers.clear();
    }

    private void registerAll(List<File> dirs, Set<String> seen) {
        if (dirs == null) return;
        for (File dir : dirs) {
            if (dir == null) continue;
            String key;
            try { key = dir.getCanonicalPath(); }
            catch (Throwable t) { key = dir.getAbsolutePath(); }
            if (!seen.add(key)) continue;
            registerOne(dir);
        }
    }

    private void registerOne(final File dir) {
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            logger.debug("Skipping observer for non-readable dir: " + dir);
            return;
        }
        try {
            // FileObserver(File, int) is API 29+ but minSdk=28 here, and BYD
            // head units include Android 7.1 (API 25) targets per the project
            // memory. Use the deprecated String-arg ctor on <29 to avoid
            // NoSuchMethodError, which would otherwise silently disable live
            // indexing on those devices. The String form behaves identically
            // for our event mask; the API 29 ctor was added only because the
            // String form does a redundant File→String→File round-trip.
            FileObserver fo;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                fo = new FileObserver(dir, EVENT_MASK) {
                    @Override
                    public void onEvent(int event, String path) {
                        handleEvent(dir, event, path);
                    }
                };
            } else {
                @SuppressWarnings("deprecation")
                FileObserver legacy = new FileObserver(dir.getAbsolutePath(), EVENT_MASK) {
                    @Override
                    public void onEvent(int event, String path) {
                        handleEvent(dir, event, path);
                    }
                };
                fo = legacy;
            }
            fo.startWatching();
            observers.add(fo);
            logger.info("Watching " + dir.getAbsolutePath());
        } catch (Throwable t) {
            // Don't fail the whole init for one bad dir — FUSE mounts and
            // exhausted inotify watches both surface here. Periodic reconcile
            // is the backstop.
            CameraDaemon.log("FileObserver register failed for " + dir + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Dispatched on the inotify thread. Keep the work small. */
    private static void handleEvent(File dir, int event, String path) {
        if (path == null || path.isEmpty()) return;
        // FileObserver event flags can include action-class bits; mask down.
        int action = event & FileObserver.ALL_EVENTS;
        try {
            if (path.endsWith(".mp4")) {
                if ((action & (FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE)) != 0) {
                    File f = new File(dir, path);
                    // Only react to finalisation signals: CLOSE_WRITE fires
                    // exactly once when the encoder finalises and releases
                    // the fd; MOVED_TO covers atomic-rename publishes. CREATE
                    // is intentionally excluded — it fires on open() with the
                    // file usually zero-byte (or briefly non-zero from a
                    // header write that can race back to zero), which would
                    // upsert a sizeBytes=0 row. A crashed encoder that never
                    // emits CLOSE_WRITE is recovered by periodic reconcile,
                    // which applies the same f.length()>0 guard.
                    if (f.length() > 0) {
                        RecordingsIndex.getInstance().upsert(f);
                    }
                } else if ((action & (FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
                    RecordingsIndex.getInstance().removeByPath(new File(dir, path).getAbsolutePath());
                }
            } else if (path.endsWith(".json")
                    && (action & FileObserver.MODIFY) != 0) {
                // Sidecar updated (geo merge etc.) — re-upsert the matching mp4
                // so the index row picks up the new place_short / lat / lng.
                String mp4Name = path.substring(0, path.length() - ".json".length()) + ".mp4";
                File mp4 = new File(dir, mp4Name);
                if (mp4.exists() && mp4.isFile() && mp4.length() > 0) {
                    RecordingsIndex.getInstance().upsert(mp4);
                }
            }
        } catch (Throwable t) {
            // Never let an event handler throw out of FileObserver — that
            // can wedge the inotify reader thread on some kernels.
            logger.warn("event handler threw: " + t.getMessage());
        }
    }

    /** Snapshot of how many observers are live — for /api/diagnostics surfaces. */
    public synchronized int observerCount() {
        return Collections.unmodifiableList(observers).size();
    }
}
