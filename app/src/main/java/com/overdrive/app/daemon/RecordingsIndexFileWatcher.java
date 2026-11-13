package com.overdrive.app.daemon;

import android.os.FileObserver;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.storage.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *   <li>{@link #stop()} — close the pending queue and unregister observers;
 *       the worker exits without interrupting an in-flight H2 operation.
 * </ul>
 *
 * <p><b>Why each event matters:</b>
 * <ul>
 *   <li>{@code CLOSE_WRITE} / {@code MOVED_TO} on {@code .mp4} — the recorder's
 *       finalised mp4 lands here. Upserts an index row.
 *   <li>{@code DELETE} / {@code MOVED_FROM} on {@code .mp4} — storage
 *       cleanup or external host PC delete. Drops the row.
 *   <li>{@code MODIFY}, {@code CLOSE_WRITE}, or {@code MOVED_TO} on
 *       {@code .json} — sidecar updates after the MP4 has been indexed.
 *       SidecarGeoUpdater writes geo into the JSON; the index
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
 * monitor on this object. Observer callbacks only enqueue path changes;
 * a bounded, coalescing single worker performs filesystem reads and index
 * writes. This avoids blocking Android's shared FileObserver thread on
 * FUSE metadata or H2 IO. Overflow schedules a background reconciliation.
 */
public final class RecordingsIndexFileWatcher {

    private static final String TAG = "RecordingsIndexFileWatcher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int MAX_PENDING_PATHS = 512;

    private static final int EVENT_MASK =
            FileObserver.MOVED_TO
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

    /** Scoped to a start/stop lifecycle; old observers cannot enqueue after stop. */
    private RecordingIndexEventQueue eventQueue;
    private ExecutorService indexWorker;

    private RecordingsIndexFileWatcher() {}

    /**
     * Register observers for every recordings/surveillance/proximity dir.
     * Tears down any previously-registered observers first, so it's safe
     * to call repeatedly when the storage-type set changes.
     */
    public synchronized void start() {
        stopWatchingOnly();
        try {
            if (eventQueue == null) initWorker();
            StorageManager sm = StorageManager.getInstance();
            Set<String> seen = new HashSet<>();
            registerAll(sm.getAllRecordingsDirs(), seen);
            registerAll(sm.getAllSurveillanceDirs(), seen);
            registerAll(sm.getAllProximityDirs(), seen);
            started = true;
            CameraDaemon.log("RecordingsIndexFileWatcher: " + observers.size()
                    + " observer(s) registered across " + seen.size() + " unique dir(s)");
        } catch (Throwable t) {
            stopWatchingOnly();
            if (eventQueue == null && indexWorker != null) {
                indexWorker.shutdown();
                indexWorker = null;
            }
            started = false;
            CameraDaemon.log("RecordingsIndexFileWatcher start failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            logger.error("start failed", t);
        }
    }

    /** Allocate the bounded worker once per daemon lifecycle. */
    private void initWorker() {
        indexWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RecordingsIndexEvents");
            t.setDaemon(true);
            return t;
        });
        eventQueue = new RecordingIndexEventQueue(indexWorker,
                new RecordingIndexEventQueue.Listener() {
                    @Override
                    public void apply(String mp4Path, RecordingIndexEventQueue.Change change) {
                        File file = new File(mp4Path);
                        try {
                            if (change == RecordingIndexEventQueue.Change.REMOVE
                                    || !file.isFile()) {
                                RecordingsIndex.getInstance().removeByPath(mp4Path);
                            } else if (file.length() > 0) {
                                RecordingsIndex.getInstance().upsert(file);
                            }
                        } catch (Throwable t) {
                            logger.warn("Index update failed for " + file.getName()
                                    + ": " + t.getMessage());
                            throw new IllegalStateException("Recording index update failed", t);
                        }
                    }

                    @Override
                    public void reconcile() {
                        // No synchronous root walk on this worker. Reconcile
                        // coalesces on the index's separate background runner.
                        if (!RecordingsIndex.getInstance().requestReconcile(
                                "watcher-event-overflow")) {
                            logger.warn("Could not enqueue watcher recovery reconcile");
                        }
                    }
                }, MAX_PENDING_PATHS);
    }

    /** Rebind watchers, retain queued changes, and discover existing clips on new mounts. */
    public synchronized void refresh() {
        if (!started) return;
        start();
        if (started) {
            RecordingsIndex.getInstance().requestReconcile("watcher-storage-rebind");
        }
    }

    public synchronized void stop() {
        // Close the old queue first. A callback from a stopped observer must
        // never write into a later daemon run's index worker.
        if (eventQueue != null) eventQueue.close();
        stopWatchingOnly();
        if (indexWorker != null) indexWorker.shutdown(); // do not interrupt JDBC
        eventQueue = null;
        indexWorker = null;
        started = false;
    }

    private void stopWatchingOnly() {
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
        final RecordingIndexEventQueue queue = eventQueue;
        if (queue == null) return;
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
                        handleEvent(dir, event, path, queue);
                    }
                };
            } else {
                @SuppressWarnings("deprecation")
                FileObserver legacy = new FileObserver(dir.getAbsolutePath(), EVENT_MASK) {
                    @Override
                    public void onEvent(int event, String path) {
                        handleEvent(dir, event, path, queue);
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

    /** FileObserver callback: no file stats, JSON parse, JDBC or blocking queue put. */
    private static void handleEvent(File dir, int event, String path,
                                    RecordingIndexEventQueue queue) {
        if (path == null || path.isEmpty()
                || path.indexOf('/') >= 0 || path.indexOf('\\') >= 0) return;
        int action = event & FileObserver.ALL_EVENTS;
        if (path.endsWith(".mp4")) {
            if ((action & (FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
                queue.submit(new File(dir, path).getAbsolutePath(),
                        RecordingIndexEventQueue.Change.REMOVE);
            } else if ((action & (FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO)) != 0) {
                // CREATE alone is too early: the recorder may still be writing
                // a zero-byte or partially-finalized mp4.
                queue.submit(new File(dir, path).getAbsolutePath(),
                        RecordingIndexEventQueue.Change.REFRESH);
            }
        } else if (path.endsWith(".json")
                && (action & (FileObserver.MODIFY | FileObserver.CLOSE_WRITE
                    | FileObserver.MOVED_TO)) != 0) {
            // MOVED_TO is required for sidecars published via atomic rename.
            // Refresh the matching mp4 after the metadata writer closes it.
            String mp4Name = path.substring(0, path.length() - ".json".length()) + ".mp4";
            queue.submit(new File(dir, mp4Name).getAbsolutePath(),
                    RecordingIndexEventQueue.Change.REFRESH);
        }
    }

    /** Snapshot of how many observers are live — for /api/diagnostics surfaces. */
    public synchronized int observerCount() {
        return observers.size();
    }
}
