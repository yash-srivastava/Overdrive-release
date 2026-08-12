package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StorageManager - SOTA Storage Management for Overdrive
 * 
 * Manages recording and surveillance storage with:
 * - Dedicated directories under /storage/emulated/0/Overdrive/ (internal) or SD card
 * - Storage type selection: INTERNAL or SD_CARD for both recordings and surveillance
 * - Configurable size limits (100MB - 10000MB for SD card)
 * - Automatic cleanup of oldest files when limit is reached
 * - Event-driven cleanup (after each file save)
 * - Periodic background cleanup during long recordings
 * - Thread-safe operations
 * - SD card detection and availability monitoring
 * 
 * SOTA Cleanup Strategy:
 * 1. Pre-recording check - Reserve space before starting
 * 2. Post-file cleanup - Run after each file is closed/saved
 * 3. Periodic cleanup - Background task every 30 seconds during active recording
 * 
 * Storage Selection:
 * - Each storage type (recordings, surveillance) can independently use internal or SD card
 * - SD card paths are auto-discovered via BYD system properties or known mount points
 * - Graceful fallback to internal storage if SD card becomes unavailable
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    
    // Storage type enum
    public enum StorageType {
        INTERNAL,
        SD_CARD,
        USB
    }
    
    // Hybrid logger - uses DaemonLogger when running as daemon, android.util.Log otherwise
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    /**
     * Enable daemon logging mode (call from daemon process).
     */
    public static void enableDaemonLogging() {
        useDaemonLogger = true;
        daemonLogger = com.overdrive.app.logging.DaemonLogger.getInstance(TAG);
    }
    
    private static void logInfo(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.info(msg);
        } else {
            Log.i(TAG, msg);
        }
    }
    
    private static void logWarn(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.warn(msg);
        } else {
            Log.w(TAG, msg);
        }
    }
    
    private static void logError(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.error(msg);
        } else {
            Log.e(TAG, msg);
        }
    }

    /**
     * Bounded {@link Process#waitFor()} — kills the child if it doesn't exit
     * within {@code timeoutMs}. Returns the exit code on clean exit, or
     * {@code -1} on timeout / interrupt. The vendored {@code sm} binary on
     * BYD ROMs has been observed to hang indefinitely when an SD/USB volume
     * is in a bad state (post-update with the slot empty, or with stale
     * mount table state after a SIGKILL'd vold helper). Without a timeout
     * here, the daemon's startup path blocked forever — see the
     * recovery-first comment in CameraDaemon.main().
     */
    private static int waitForBounded(Process p, long timeoutMs, String label) {
        try {
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            logWarn(label + ": timed out after " + timeoutMs + "ms — killing child");
            p.destroyForcibly();
            // Give the kernel a moment to reap, but bound this too.
            try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            return -1;
        }
    }
    
    private static void logDebug(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.debug(msg);
        } else {
            Log.d(TAG, msg);
        }
    }
    
    // Base directories for Overdrive files
    private static final String INTERNAL_BASE_DIR = "/storage/emulated/0/Overdrive";

    // Legacy paths from older app versions. Files here aren't written anymore
    // but they still count toward the user's configured limit and must be
    // reaped — otherwise a 500 MB limit can show 800 MB used in the UI.
    private static final String LEGACY_APP_FILES_DIR = RecordingDirectoryRegistry.LEGACY_BASE;
    private static final String LEGACY_SURVEILLANCE_DIR = RecordingDirectoryRegistry.LEGACY_SENTRY;

    // Subdirectories
    public static final String RECORDINGS_SUBDIR = "recordings";
    public static final String SURVEILLANCE_SUBDIR = "surveillance";
    public static final String PROXIMITY_SUBDIR = "proximity";
    public static final String TRIPS_SUBDIR = "trips";
    
    // Config file location
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";

    // Persisted UUID of whichever public volume we've previously confirmed as
    // the SD card. Used as the first-class signal in classifyPublicVolume()
    // when the BYD vendor prop (sys.byd.mSdcardUuid) is empty. The vendor
    // prop is only populated WHILE the card is mounted on this firmware, so
    // during the unmount window between ACC OFF and our remount attempt the
    // prop returns "" and the major-number fallback was misclassifying the
    // bridged-SD (major 8, DEVNAME=sd*) as USB. Learning the FAT volume
    // serial from a previous successful cycle bridges the gap. File is
    // tiny (~10 bytes), atomic-write semantics not required because a stale
    // value still resolves to the same physical card.
    private static final String LEARNED_SD_UUID_FILE = "/data/local/tmp/overdrive_sd_uuid";
    
    // Default limits (in bytes)
    private static final long DEFAULT_RECORDINGS_LIMIT_MB = 500;
    private static final long DEFAULT_SURVEILLANCE_LIMIT_MB = 500;
    private static final long DEFAULT_PROXIMITY_LIMIT_MB = 500;
    private static final long DEFAULT_TRIPS_LIMIT_MB = 500;
    private static final long MIN_LIMIT_MB = 100;

    // Hard ceiling fallback used only when StatFs reports 0 (volume unmounted
    // at the moment of the read). Keeps the slider usable while we wait for a
    // refresh. Real cap comes from getEffectiveMaxLimitMb(type) below, which
    // pulls the live filesystem total minus a safety reserve.
    private static final long MAX_LIMIT_MB_FALLBACK = 100000;  // 100GB

    // NOTE (2026-06, per-category share removed): there used to be a
    // PER_CATEGORY_SHARE = 0.40 here that capped EACH category's slider max at
    // 40% of the volume so the four categories sharing one FS couldn't overcommit
    // it 4x. Product decision reversed that: a category's max should reflect the
    // REAL volume capacity, not volume/N — the artificial division confused users
    // (a 256 GB card showing a ~100 GB ceiling). The max is now the full usable
    // volume (getEffectiveMaxLimitMb → volumeCeilingMb). Overcommit is accepted but
    // made SAFE rather than impossible: each category is independently reaped to its
    // own limit, VOLUME_HEADROOM_MB stays reserved on every ceiling, and the
    // recorder pre-flight + ENOSPC fallback guarantee a writable floor. The sum of
    // configured limits CAN now exceed the disk; that's the user's choice and can't
    // cause data loss because retention + physical-space reserve still hold.

    // Reserve a small fraction of the volume so the encoder can never hit
    // ENOSPC mid-file from the user setting "max" on a near-empty disk.
    private static final long VOLUME_HEADROOM_MB = 256;
    
    // Periodic cleanup interval (30 seconds)
    private static final long CLEANUP_INTERVAL_SECONDS = 30;
    // Out-of-band edits and missed events still need an integrity backstop, but
    // an idle daemon does not need to walk every recording directory every 30s.
    private static final long PERIODIC_INTEGRITY_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private volatile long lastPeriodicIntegrityAtMs = 0L;

    // Max anchor files to delete in a single BOUNDED-TRIM pass that runs WHILE the
    // encoder is writing. This caps SD-card I/O contention against the muxer's disk
    // writer — the historical full delete-burst (19 files / 118 MB at once) produced
    // a ~2.8 s mosaic+swap stall because the disk writer was starved by cleanup I/O.
    //
    // It also FIXES the convergence deadlock the old policy created: a continuous
    // recorder parks ~1.9 %% over its cap forever (one ~2-min/220 MB segment in every
    // ~120 s vs the cap), but the old defer gate only allowed a reap when usage was
    // >5 %% over, AND the deferred-drain only runs when the encoder is idle — which
    // never happens mid-drive. So in the 0–5 %% band nothing EVER reaped while driving
    // and the volume overflowed to ENOSPC. A small per-tick trim (every 30 s) keeps
    // the pool converging: 4 deletes/tick ≫ the ~1 segment/2-min inflow, and clears a
    // worst-case sub-5 %% backlog (≤5 %% of the cap ≈ a couple of segments) within a
    // tick or two, while staying ~5× under the known-bad burst size. Larger over-runs
    // (>5 %%, e.g. a big limit drop) bypass this and take the unbounded HARD reap.
    private static final int RECORDING_TRIM_MAX_FILES = 4;
    // Sentinel for ensureSpace's delete budget: no per-pass cap. Used by idle reaps,
    // the boot reap, and the HARD over-limit emergency path (full reap down to limit).
    private static final int UNLIMITED_REAP = 0;

    // Free-space threshold (bytes) at which the periodic tick clears a stale
    // recordingsEnospcFallbackActive — the card must have at least one segment's
    // worth of room before we re-scope recordings retention back to the SD pool.
    // Set ABOVE the recorder's per-segment reserve (~100MB, see
    // resolveTargetWithEnospcFallback) so there is a hysteresis gap between the
    // set threshold (<100MB → latch) and this clear threshold (>=200MB → unlatch):
    // a volume sitting exactly at the boundary can't toggle the latch every tick.
    private static final long ENOSPC_FALLBACK_RECOVER_BYTES = 200L * 1024 * 1024;

    // Physical-volume free-space emergency reaper thresholds. These are
    // DISTINCT from the per-category caps. When a SHARED volume is physically
    // full but every category living on it is still UNDER its OWN cap — the cap
    // oversubscription case (Σ per-category caps > the card's real capacity, e.g.
    // recordings 100 GB + surveillance 20 GB + trips 0.8 GB ≈ 121 GB of caps on a
    // ~119 GB card) — the per-category reaper has nothing eligible (each category
    // compares only against its own cap, never the physical volume), the 90% wake
    // gate and REAP_DEFER both short-circuit, and the diskCritical/forceFull
    // override is unreachable. The volume then stays full forever and new segments
    // spill to internal via the ENOSPC fallback ("recordings on internal storage
    // even though SD is configured" + "periodic cleanup never frees space" — the
    // same single defect). This layer reaps the globally-oldest finalized clips on
    // the volume — ACROSS categories — until free space recovers, independent of
    // any per-category cap. FLOOR triggers the reap; TARGET is reaped down-to.
    // TARGET is well above ENOSPC_FALLBACK_RECOVER_BYTES (200 MB) so reaching it
    // also clears the stale internal-fallback latch in one shot instead of flapping
    // it around the 200 MB boundary every segment rotation. Both are additionally
    // clamped to a fraction of the volume total (see runPhysicalFreeSpaceEmergencyReap)
    // so a genuinely tiny card can never be reaped empty chasing an unreachable target.
    private static final long PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES = 512L * 1024 * 1024;   // act below 512 MB free
    private static final long PHYSICAL_FREE_EMERGENCY_TARGET_BYTES = 1024L * 1024 * 1024; // reap down to ~1 GB free
    // Media categories the physical-free reaper evicts from. Trips (.jsonl.gz,
    // DB-backed, KB-to-low-MB scale) is intentionally excluded — it cannot
    // meaningfully relieve a full volume and would drag TripDatabase row
    // bookkeeping into the emergency path. Its own per-category reaper still runs.
    private static final String[] PHYSICAL_REAP_CATEGORIES = { "recordings", "surveillance", "proximity" };
    // Internal volume root used for on-volume file matching (mirrors internalScopedDirs).
    private static final String INTERNAL_VOLUME_ROOT = "/storage/emulated/0";
    // Max ANCHOR deletions per tick for the physical-free emergency reaper WHILE the
    // encoder is writing, so the burst of unlink syscalls can't starve the muxer's
    // disk writer (the historical 19-file burst caused a ~2.8 s eglSwap stall). 8
    // anchors (~1.7 GB of cam_/dvr_ clips) clears a from-near-zero → 1 GB target in
    // a single tick yet stays ~2.4× under the known-bad burst.
    private static final int PHYSICAL_REAP_MAX_FILES = 8;
    // Absolute idle ceiling on delete ATTEMPTS per tick (no encoder writer to starve,
    // so the muxer-protection cap above doesn't bind). Caps wasted work when the volume
    // is degenerate-unhealthy — read-only/EROFS remount, card pulled (free probe == 0),
    // transient StatFs 0 — where the no-progress loop-exit (free >= target) can never fire
    // because every delete frees nothing; without this the loop would fork a doomed `rm`
    // for the WHOLE pool while holding all three cleanup locks. A real full-volume reap
    // (deletes succeed) frees ~1.7 GB well before 64 anchors and exits via the free target.
    private static final int PHYSICAL_REAP_MAX_FILES_IDLE = 64;
    // Consecutive zero-free delete returns that flag a vanished/read-only volume (EROFS /
    // ENOENT make every unlink a no-op). After this many in a row, stop forking doomed
    // rm's and retry next tick rather than walking the rest of the pool.
    private static final int PHYSICAL_REAP_MAX_CONSEC_FAILS = 8;
    // Wall-clock ceiling on a SINGLE reapForPhysicalFreeSpace() invocation. The reaper
    // runs under the nested recordings→surveillance→proximity locks, and a degenerate
    // FUSE volume can make each deleteAnchorAndSidecars() fork an `rm` that blocks up to
    // 4 s (deleteFileViaShell waitForBounded). With the 8-attempt budget that is a ~32 s
    // worst-case lock hold — which would stall the surveillance trigger path now that it
    // resolves its event dir through getLiveSurveillanceDir() (surveillanceCleanupLock).
    // Bound the per-tick delete work to ~2 s so the trigger thread is never blocked long
    // enough to overrun the finite pre-record ring buffer; the reap simply resumes on the
    // next 30 s tick. This complements (does not replace) the file-count/consec-fail caps.
    private static final long PHYSICAL_REAP_MAX_LOCK_HOLD_MS = 2_000L;

    // Current limits
    // volatile: the cleanup thread reads these under the per-category cleanup
    // locks while settings/HTTP threads write them under configChangeLock — two
    // distinct monitors, so without volatile a write under one establishes no
    // happens-before for a read under the other (the enforced retention size).
    // Matches the cross-thread-visibility rationale on the storage-type fields below.
    private static volatile long recordingsLimitMb = DEFAULT_RECORDINGS_LIMIT_MB;
    private static volatile long surveillanceLimitMb = DEFAULT_SURVEILLANCE_LIMIT_MB;
    private static volatile long proximityLimitMb = DEFAULT_PROXIMITY_LIMIT_MB;
    private volatile long tripsLimitMb = DEFAULT_TRIPS_LIMIT_MB;
    
    // Storage type selection (SOTA: independent selection for recordings and surveillance)
    // volatile: read cross-thread (recorder pre-flight reconcile, size queries)
    // and written on settings/HTTP threads.
    private volatile StorageType recordingsStorageType = StorageType.INTERNAL;
    private volatile StorageType surveillanceStorageType = StorageType.INTERNAL;
    private volatile StorageType tripsStorageType = StorageType.INTERNAL;
    // True while the most recent recordings segment was redirected to internal
    // by the ENOSPC fallback because the configured EXTERNAL volume is
    // mounted-but-FULL. Distinct from the unmounted case (sdCardAvailable=false)
    // — a full-but-writable volume keeps its availability flag true, so without
    // this signal getActiveRecordingsStorageType() would still report the
    // external type and the "saving to internal" banner would never fire for
    // the very disk-full case the fallback exists to handle. Set/cleared by
    // resolveTargetWithEnospcFallback at each segment-target resolution; read by
    // getActiveRecordingsStorageType() so the existing UI banner surfaces it.
    private volatile boolean recordingsEnospcFallbackActive = false;

    // SD card state.
    // volatile: (re)assigned by discoverVolumes/ensureVolumeMounted on the
    // VolumeWatchdog + mount threads and read by unrelated threads (resolveActive
    // via reconcileRecordingOverride, isSdCardLikelyMounted). The volatile fence
    // gives those readers a happens-before edge to the mount-path writes; without
    // it a remount-completed write could stay invisible to the recorder thread.
    private volatile String sdCardPath = null;
    private volatile boolean sdCardAvailable = false;

    // USB state — flash drives mounted via OTG. Treated as a separate volume
    // class from SD because of how head-units enumerate them: SD sits behind
    // an mmc driver (Linux major 179), USB behind sd/SCSI (major 8/65/66/...).
    // Without this distinction discoverSdCard() will happily latch onto a USB
    // stick when both are present. volatile for the same cross-thread reason
    // as the SD state fields above.
    private volatile String usbPath = null;
    private volatile boolean usbAvailable = false;
    
    // Singleton instance
    private static StorageManager instance;
    
    // Internal storage directories (always available)
    private File internalRecordingsDir;
    private File internalSurveillanceDir;
    private File internalProximityDir;
    private File internalTripsDir;
    
    // SD card directories (may be null if SD card not available).
    // volatile: initSdCardDirectories() (re)assigns / null-clears these on the
    // remount paths (watchdog + mount threads); resolveActive reads them from
    // other threads. NOT immutable-after-init — the volatile fence is what makes
    // a freshly-assigned dir visible to the recorder-side reader.
    private volatile File sdCardRecordingsDir;
    private volatile File sdCardSurveillanceDir;
    private volatile File sdCardProximityDir;
    private volatile File sdCardTripsDir;

    // USB directories (may be null if USB drive not available). volatile for the
    // same cross-thread remount-visibility reason as the SD directories above.
    private volatile File usbRecordingsDir;
    private volatile File usbSurveillanceDir;
    private volatile File usbProximityDir;
    private volatile File usbTripsDir;
    
    // Active directories (based on storage type selection).
    // Volatile because they're written by setters/watchdog threads (which
    // hold per-category cleanup locks during the assignment) and read by
    // unrelated readers (size queries, file-saved handlers, recorder
    // pre-flight) that may not hold the same lock. The previous design
    // shared a single cleanupLock that ordered all reads/writes; with
    // per-category locks the reads can land on a stale value without the
    // volatile fence.
    private volatile File recordingsDir;
    private volatile File surveillanceDir;
    private volatile File proximityDir;
    private volatile File tripsDir;
    
    // Background cleanup scheduler
    private ScheduledExecutorService cleanupScheduler;
    private final AtomicBoolean recordingActive = new AtomicBoolean(false);
    private final AtomicBoolean surveillanceActive = new AtomicBoolean(false);

    // Absolute path of the currently-recording trip telemetry file (.jsonl.gz)
    // or null when no trip is active. Path-based instead of a boolean so a
    // limit-change cleanup mid-trip can still reap older trip files; only the
    // in-flight file is protected. Read by ensureSpace before each delete.
    private volatile String activeTripFilePath = null;

    // SOTA: Authoritative "encoder is mid-write" probe.
    //
    // The setRecordingActive / setSurveillanceActive booleans above track the
    // *user-facing* recording state, set by GpuMosaicRecorder.startRecording /
    // stopRecording. They are NOT a reliable signal for "is the disk writer
    // currently flushing packets to the SD card", because there's a real lag:
    //   - User starts recording → recordingActive=true. Encoder hasn't yet
    //     produced its first packet. Cleanup CAN safely run for ~100 ms.
    //   - User stops recording → recordingActive=false. Disk writer is still
    //     draining the muxer queue + finalising the moov atom (~50-200ms).
    //     A cleanup burst here corrupts the still-open file's footer write.
    //
    // The probe below points at HardwareEventRecorderGpu.isWritingToFile() —
    // the volatile flag set under startStopLock that goes true the moment the
    // muxer is constructed and false ONLY after closeEventRecording has
    // released it. Cleanup uses this to gate destructive deletes and avoid
    // contending with the realtime SD-card writes during an active recording.
    //
    // Default probe returns false so a stale binding never blocks cleanup
    // forever. PipelineDaemon installs the real probe after the encoder
    // exists; if the encoder is later released, the probe returns false
    // gracefully (HardwareEventRecorderGpu.isWritingToFile reads a volatile
    // field that's false when the recorder isn't holding a muxer).
    private volatile java.util.function.BooleanSupplier encoderWritingProbe = () -> false;
    /**
     * Set true the first time setEncoderWritingProbe wires a real probe. The
     * periodic cleanup loop early-returns until this flips, so the first
     * 30-second tick after daemon boot can't run un-gated against a default
     * fail-open probe (audit P1).
     */
    private final java.util.concurrent.atomic.AtomicBoolean probeWired =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // Async cleanup executor (single thread to avoid concurrent cleanup)
    private final java.util.concurrent.ExecutorService asyncCleanupExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                // SOTA: Linux nice +10 (THREAD_PRIORITY_BACKGROUND). The Java
                // MIN_PRIORITY below is advisory; this is what actually keeps
                // file deletes from preempting the disk writer's muxer writes
                // under SD card I/O contention.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanupAsync");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    // Deferred-cleanup queue: when a save event fires while encoder is mid-
    // write, instead of running the delete burst we mark the directory as
    // "needs cleanup later". A polling pass on the same asyncCleanupExecutor
    // drains this set the next time encoderWritingProbe returns false. Without
    // this, a back-to-back recording/cleanup pattern would skip cleanup
    // forever and storage would grow past the limit.
    private final java.util.Set<String> deferredCleanupDirs =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String DEFERRED_RECORDINGS = "recordings";
    private static final String DEFERRED_SURVEILLANCE = "surveillance";
    private static final String DEFERRED_PROXIMITY = "proximity";
    private static final String DEFERRED_TRIPS = "trips";

    // Per-category cleanup locks. The previous design used a single shared
    // monitor for every ensureXxxSpace / sweep / wipe call across all four
    // categories. That serialised unrelated work — most catastrophically,
    // the boot startup reap (which sweeps all four categories under the same
    // lock) blocked recorder.startRecording's pre-flight ensureRecordingsSpace
    // for the entire duration of the reap. On a USB drive with 1k+ recordings,
    // that's minutes of starvation, during which the user's drive is silently
    // not recorded (RecordingModeManager.activateMode calls pipeline.startRecording
    // synchronously and pins the manager monitor across the whole stall).
    //
    // Each category now owns its own monitor. Cross-category orchestrators
    // (runCleanup, the periodic ticker, the boot reap) take the locks one
    // category at a time so the windows of contention with per-recording calls
    // are bounded to a single category's walk.
    private final Object recordingsCleanupLock = new Object();
    private final Object surveillanceCleanupLock = new Object();
    private final Object proximityCleanupLock = new Object();
    private final Object tripsCleanupLock = new Object();
    // FIX (audit R8, LOW): serialize peer setStorageType calls so concurrent
    // HTTP threads don't interleave field writes / setOutputDir push /
    // stopRecording / saveConfig. setRecordingsStorageType, setSurveillanceStorageType,
    // and setTripsStorageType all take this lock.
    private final Object configChangeLock = new Object();

    /** Resolve the per-category lock by category key — used by helpers that
     *  receive the category as a string (drainDeferredCleanup, sweep helpers).
     *  Unknown keys map to {@code recordingsCleanupLock} as a safe default;
     *  callers should still validate the category. */
    private Object lockForCategory(String category) {
        switch (category) {
            case "recordings":  return recordingsCleanupLock;
            case "surveillance": return surveillanceCleanupLock;
            case "proximity":   return proximityCleanupLock;
            case "trips":       return tripsCleanupLock;
            default:            return recordingsCleanupLock;
        }
    }
    
    // SD card / USB mount watchdog (keeps the configured external volume
    // mounted during sentry mode). Single scheduler covers both classes —
    // each volume gets its own consecutive-failure counter so quiet-log
    // throttling is independent.
    private ScheduledExecutorService sdCardWatchdog;
    private static final long SD_WATCHDOG_INTERVAL_SECONDS = 15;
    // volatile: written by the VolumeWatchdog thread AND (on a successful ACC-ON
    // remount) by the AccOnRemount thread — see remountExternalOnAccOn. Without
    // volatile the watchdog may never observe the ACC-ON reset (no happens-before
    // between the two threads), leaving a stale failure streak. These gate only
    // log verbosity + remount eagerness, so the `++`/reset are not made atomic;
    // volatile is sufficient for the visibility that matters here.
    private volatile int sdWatchdogConsecutiveFailures = 0;
    private volatile int usbWatchdogConsecutiveFailures = 0;

    // Serializes the volume mount/discovery path. ensureVolumeMounted() and
    // discoverVolumes() fork `sm mount`/`sm list-volumes` and then commit the
    // (sdCardPath, sdCardAvailable) / (usbPath, usbAvailable) pairs as separate
    // volatile writes — group-atomic only against a single pass. Multiple
    // unsynchronized entrants (the 15s VolumeWatchdog, the ACC-OFF force-remount,
    // the constructor's StorageMountInit thread, ensureStorageReady, and now the
    // ACC-ON remount) could interleave into a torn commit — e.g. sdCardAvailable
    // =true with sdCardPath=null, which resolveActive() then treats as
    // "available but no dir" and silently falls back to internal for the whole
    // session (the phantom-unmount failure the discoverVolumes staging comment
    // guards a single pass against, but which staging alone can't stop across two
    // passes). This lock makes the whole mount/discovery critical section
    // single-entry. Lock ORDER: mountLock is always acquired OUTSIDE the
    // per-category cleanup locks — ensureVolumeMounted/discoverVolumes call
    // updateActiveDirectories() (which takes the cleanup locks) while holding
    // mountLock, and no cleanup-lock holder ever calls back into the mount path,
    // so the ordering is one-way and deadlock-free.
    private final Object mountLock = new Object();
    private static final int SD_WATCHDOG_MAX_VERBOSE_FAILURES = 5;  // Log verbosely for first 5 failures
    private static final int SD_WATCHDOG_QUIET_LOG_INTERVAL = 20;   // Then log every 20th attempt (~5 min)

    // Idempotency latch for the remount-FAILURE recovery push (forced
    // stopRecording + setOutputDir(internal) + RMM resync). The recovery is a
    // ONE-TIME transition action ("the configured external volume just went
    // away → move the live recorder to internal"). The watchdog re-probes every
    // SD_WATCHDOG_INTERVAL_SECONDS (15s), so without this latch a card that stays
    // unmounted (e.g. SD slot unpowered while parked with USB power off — the SD
    // shares the USB rail) re-runs the recovery on EVERY tick: it force-stops the
    // in-flight recording, re-pushes the same internal dir, and re-kicks RMM. A
    // continuous segment needs SEGMENT_DURATION_MS (120s) to finalize, so an
    // every-15s force-stop means NO segment ever completes — recording silently
    // produces zero files for the whole park. Latch true after the first recovery
    // and skip the disruptive push on subsequent failed ticks (mount RETRIES still
    // run; only the recorder-disturbing recovery is gated). Reset to false on a
    // successful remount so a card that returns and drops AGAIN recovers each
    // cycle. Per-rail so SD and USB latch independently.
    // volatile: written by the VolumeWatchdog thread AND (re-armed to false on a
    // successful ACC-ON remount) by the AccOnRemount thread. Non-volatile, the
    // watchdog could keep a cached `true` and SKIP the one-time recorder→internal
    // fallback recovery when the card drops AGAIN later in the drive — the
    // recorder would keep writing to the vanished SD path and no segment would
    // ever finalize (the zero-files-for-the-park failure this latch's re-arm
    // exists to prevent).
    private volatile boolean sdFallbackRecoveryDone = false;
    private volatile boolean usbFallbackRecoveryDone = false;

    // Rate-limit for the raw `sm list-volumes` diagnostic dump. The fingerprint
    // line (publicRows=N matchedRows=M) is cheap and stays at logInfo on every
    // failure; the multi-line raw output only re-prints when 5 minutes have
    // elapsed since the last dump. Without this, a multi-hour park with the
    // SD genuinely missing produces ~240 raw dumps/hour which floods cam_daemon.log.
    private long lastSmRawDumpAtMs = 0;
    private static final long SM_RAW_DUMP_INTERVAL_MS = 5 * 60_000;
    
    /**
     * Parse a storage-type string from persisted config. Anything that
     * doesn't match SD_CARD/USB falls back to INTERNAL — that includes
     * legacy configs and accidentally-truncated writes.
     */
    private static StorageType parseStorageType(String s) {
        if ("SD_CARD".equals(s)) return StorageType.SD_CARD;
        if ("USB".equals(s))     return StorageType.USB;
        return StorageType.INTERNAL;
    }

    /**
     * Coerce a configured storage type to {@code INTERNAL} when the
     * underlying volume isn't currently present. Per project spec, when the
     * user-selected external volume isn't available the runtime default is
     * always INTERNAL — never a "phantom" SD_CARD/USB whose path silently
     * resolves to internal at every read.
     *
     * <p>Does NOT overwrite the persisted preference (the persisted value
     * stays untouched in {@code overdrive_config.json}). On the next boot
     * with the volume re-attached, parseStorageType + normalizeStorageType
     * will return the user's original choice.
     */
    private StorageType normalizeStorageType(StorageType configured) {
        if (configured == StorageType.SD_CARD && !sdCardAvailable) return StorageType.INTERNAL;
        if (configured == StorageType.USB     && !usbAvailable)    return StorageType.INTERNAL;
        return configured;
    }

    private StorageManager() {
        discoverVolumes();
        initDirectories();
        loadConfig();

        // If config says SD/USB but it's not available, try to mount it on a
        // background thread. Even with the per-call timeouts in
        // ensureVolumeMounted, the worst-case is `sm list-volumes` (2s) +
        // `sm mount` (8s) + 10×500ms accessibility-poll = up to ~15s. Doing
        // that synchronously here used to wedge daemon startup whenever a
        // configured external volume was missing or in a bad state — which
        // is exactly the post-update scenario users hit (the updater's
        // pkill-9 of vold helpers can leave the volume marked-unmounted in
        // the kernel until the next ACC cycle).
        //
        // The startSdCardWatchdog() loop already retries failed mounts on a
        // schedule, so there's no value in blocking startup on a one-shot
        // attempt. Same logic for USB. updateActiveDirectories() is called
        // here AND inside ensureVolumeMounted on success, so consumers see
        // INTERNAL until/if the mount lands, then transparently switch.
        Runnable mountAttempt = () -> {
            try {
                if (!sdCardAvailable &&
                    (surveillanceStorageType == StorageType.SD_CARD ||
                     recordingsStorageType == StorageType.SD_CARD ||
                     tripsStorageType == StorageType.SD_CARD)) {
                    logInfo("SD card configured but not available - attempting mount (async)...");
                    ensureSdCardMounted(true);
                }
                if (!usbAvailable &&
                    (surveillanceStorageType == StorageType.USB ||
                     recordingsStorageType == StorageType.USB ||
                     tripsStorageType == StorageType.USB)) {
                    logInfo("USB configured but not available - attempting mount (async)...");
                    ensureUsbMounted(true);
                }
            } catch (Exception e) {
                logWarn("Async mount attempt failed: " + e.getMessage());
            }
        };
        new Thread(mountAttempt, "StorageMountInit").start();

        updateActiveDirectories();

        // One-shot startup reap. If the user lowered the limit, switched
        // storage type, or upgraded from a legacy build, the inactive +
        // legacy locations may be holding orphan files that count toward
        // the limit. Reap them once at boot so the UI total agrees with
        // the configured limit before any new event fires the per-save
        // cleanup. Async — don't block daemon startup.
        //
        // Each ensureXxxSpace call self-acquires its own per-category lock,
        // so this loop holds no lock between categories. That matters: on a
        // first-recording-after-boot path the recorder calls
        // ensureRecordingsSpace(100MB) under recordingsCleanupLock; if we
        // held a single shared lock across the entire reap, that pre-flight
        // would block until the boot reap finished walking every category.
        // On a USB volume with thousands of clips, that's the difference
        // between recording the user's drive and silently losing it.
        asyncCleanupExecutor.execute(() -> {
            try {
                sweepOrphanTempFiles();
                ensureRecordingsSpace(0);
                ensureSurveillanceSpace(0);
                ensureProximitySpace(0);
                ensureTripsSpace(0);
            } catch (Exception e) {
                logWarn("Startup reap failed: " + e.getMessage());
            }
        });
    }

    /**
     * Delete orphan {@code .mp4.tmp} and {@code .broken} files left behind by
     * abnormal daemon exits (SIGKILL, OOM, ACC-cycle kill mid-recording).
     *
     * <p>Only the close path renames {@code .mp4.tmp → .mp4}; if the daemon
     * dies before the rename, the {@code .tmp} sits forever — counted by the
     * filesystem but invisible to the events UI. On BYD head-units where
     * the daemon is regularly killed by ACC cycles, these accumulate until
     * the SD card fills.
     *
     * <p>Conservative policy: only delete files older than {@code TEMP_FILE_GRACE_MS}
     * (10 minutes) so we never race a recording in flight. Anything younger
     * is assumed to belong to a live recording on a sibling daemon process.
     *
     * <p>Sweeps surveillance, proximity, and recordings dirs (current + legacy
     * locations).
     */
    private void sweepOrphanTempFiles() {
        final long graceMs = 10 * 60 * 1000L;
        final long cutoff = System.currentTimeMillis() - graceMs;
        int deleted = 0;
        long bytesFreed = 0;

        java.util.HashSet<String> seenPaths = new java.util.HashSet<>();
        for (String category : new String[]{"recordings", "surveillance", "proximity", "trips"}) {
            String[] partials = partialExtensionsForCategory(category);
            if (partials.length == 0) continue;
            // Take this category's lock for its iteration only — releases
            // between categories so a slow USB walk in "recordings" doesn't
            // block ensureSurveillanceSpace fired by a concurrent event save.
            synchronized (lockForCategory(category)) {
                for (File dir : getReapableDirs(category)) {
                    if (dir == null) continue;
                    String path = dir.getAbsolutePath();
                    if (!seenPaths.add(path)) continue;
                    if (!dir.isDirectory()) continue;
                    File[] files = dir.listFiles((d, name) -> {
                        for (String ext : partials) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    });
                    if (files == null) {
                        // FUSE-bridged SD/USB returns null under daemon UID 2000.
                        // Without this fallback the entire external dir is skipped,
                        // so .mp4.tmp/.broken partials accumulate on the card forever:
                        // the size gate (getDirectoriesTotalSize) counts their bytes
                        // and the ensureSpace reaper only walks primary-extension
                        // anchors, so they're counted-but-unreapable and the SD folder
                        // parks permanently over its limit. Shell-ls then filter to
                        // the partial extensions in-process (listFilesViaShell takes
                        // no filter).
                        File[] all = listFilesViaShell(dir);
                        if (all == null || all.length == 0) continue;
                        java.util.List<File> matched = new java.util.ArrayList<>();
                        for (File f : all) {
                            String n = f.getName();
                            for (String ext : partials) {
                                if (n.endsWith(ext)) { matched.add(f); break; }
                            }
                        }
                        files = matched.toArray(new File[0]);
                    }
                    if (files == null) continue;
                    for (File f : files) {
                        // Don't unlink a still-being-written trip file in case
                        // the recorder uses an atomic ".jsonl.gz.tmp → .jsonl.gz"
                        // rename and the in-flight file is the .tmp.
                        if (activeTripFilePath != null
                                && (activeTripFilePath.equals(f.getAbsolutePath())
                                    || activeTripFilePath.equals(f.getAbsolutePath() + ".tmp"))) {
                            continue;
                        }
                        if (f.lastModified() > cutoff) continue;  // grace window
                        long size = f.length();
                        boolean ok = f.delete();
                        if (!ok) ok = deleteFileViaShell(f);
                        if (ok) {
                            deleted++;
                            bytesFreed += size;
                        } else {
                            logWarn("Orphan tmp delete failed: " + f.getAbsolutePath());
                        }
                    }
                }
            }
        }
        if (deleted > 0) {
            // These partials (.mp4.tmp/.broken/.json.tmp) ARE counted by the
            // reported size, so freeing them must invalidate the reporting cache
            // or the storage card keeps showing the bytes we just reclaimed.
            invalidateCategorySizeCache(null);
            logInfo("Orphan tmp sweep: deleted " + deleted + " files, "
                    + (bytesFreed / 1024) + " KB freed");
        }
    }
    
    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            instance = new StorageManager();
        }
        return instance;
    }
    
    // ==================== SD Card Discovery ====================
    
    /**
     * SOTA: Mount SD card if unmounted.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     * 
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted() {
        return ensureSdCardMounted(false);
    }
    
    /**
     * SOTA: Mount SD card, optionally forcing a remount.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     *
     * @param force If true, always attempt to mount even if already mounted
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted(boolean force) {
        return ensureVolumeMounted("SD", force);
    }

    /**
     * Mount USB drive (or remount if stale). Mirror of ensureSdCardMounted
     * for the USB volume class.
     */
    public boolean ensureUsbMounted() {
        return ensureUsbMounted(false);
    }

    public boolean ensureUsbMounted(boolean force) {
        return ensureVolumeMounted("USB", force);
    }

    /**
     * Generic mount-or-remount for a specific volume class (SD or USB).
     * Walks {@code sm list-volumes all}, classifies each public volume by
     * underlying block-device major number (see classifyPublicVolume), and
     * mounts the first one matching the requested class. Updates the
     * corresponding {@code <class>Path} / {@code <class>Available} fields
     * + initializes per-class directories on success.
     *
     * @param targetClass "SD" or "USB"
     * @param force       attempt even if already mounted (for remount cases)
     */
    private boolean ensureVolumeMounted(String targetClass, boolean force) {
        // Serialize the whole mount/discovery critical section against the
        // watchdog / ACC-OFF / ACC-ON / constructor entrants (see mountLock).
        // Reentrant: the body calls discoverVolumes(), whose wrapper re-acquires
        // the same intrinsic lock — Java monitors are reentrant, so that's safe.
        synchronized (mountLock) {
            return ensureVolumeMountedLocked(targetClass, force);
        }
    }

    private boolean ensureVolumeMountedLocked(String targetClass, boolean force) {
        boolean isSd = "SD".equals(targetClass);
        String currentPath = isSd ? sdCardPath : usbPath;
        boolean currentAvailable = isSd ? sdCardAvailable : usbAvailable;

        // Quick check: if path is already accessible, no work needed.
        // Use the cheap StatFs+canWrite probe — the touch+rm shell exec
        // (isMountWritable) blocks up to 2s under FUSE binder contention
        // from concurrent dir-walks, falsely reporting unmounted and
        // forcing the slow `sm mount` path even though the volume is fine.
        // This was the root of the "trips storage selection silently fails"
        // bug: setTripsStorageType → ensureExternalAvailable → here, and
        // the 2s timeout returned false → setTripsStorageType returned false.
        if (!force && currentAvailable && currentPath != null) {
            if (isPathLikelyMounted(currentPath)) {
                logDebug(targetClass + " already mounted at: " + currentPath);
                return true;
            }
        }

        logDebug("Mounting " + targetClass + "...");

        // Pre-mount discovery via /proc/mounts. On some BYD ROMs the system
        // remounts the SD slot itself a few seconds after ACC OFF, but `sm
        // list-volumes` lags behind the kernel's actual mount table — so the
        // SD is live at /storage/<uuid> but `sm` either omits the row or
        // reports it as `unmounted`. Without this probe, ensureVolumeMounted
        // would proceed to `sm mount <id>` (which fails because the kernel
        // already owns the mount) and fall back to internal storage despite
        // the card being writable the whole time.
        //
        // discoverVolumes()'s /proc/mounts pass already does the right thing,
        // but the mount path didn't share that knowledge. Run a discovery
        // pass FIRST; if it commits the field for our class, we're done.
        // Call the ...Locked form directly — we already hold mountLock.
        try {
            discoverVolumesLocked();
            if (isSd && sdCardAvailable && sdCardPath != null
                    && isPathLikelyMounted(sdCardPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + sdCardPath);
                if (isSd) initSdCardDirectories(); else initUsbDirectories();
                updateActiveDirectories();
                // Re-clamp any persisted limit that was inflated to the unmounted-
                // external sentinel down to this now-mounted card's real ceiling, so
                // the slider max and the enforced limit reflect the actual capacity.
                reclampLimitsToMountedCeilings();
                return true;
            }
            if (!isSd && usbAvailable && usbPath != null
                    && isPathLikelyMounted(usbPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + usbPath);
                initUsbDirectories();
                updateActiveDirectories();
                reclampLimitsToMountedCeilings();
                return true;
            }
        } catch (Throwable t) {
            // Discovery is best-effort here — never let it block the
            // sm-driven mount path below.
            logDebug("Pre-mount discovery threw: " + t.getMessage());
        }

        // Raw sm output captured here so we can dump it on failure. Some BYD
        // ROMs at ACC OFF emit no `public:` row for the SD slot at all —
        // distinguishing that from the "row exists but in unmounted state"
        // case is critical for diagnosis. Without the dump, the daemon log
        // shows "SD card mount failed" with no clue WHICH failure mode.
        StringBuilder rawSmOutput = new StringBuilder();
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            String volumeId = null;
            String volumeUuid = null;
            int volMajor = -1, volMinor = -1;
            int publicRowCount = 0;
            int matchedRowCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                rawSmOutput.append(line).append('\n');
                logDebug("sm list-volumes: " + line);
                if (!line.startsWith("public:")) continue;
                publicRowCount++;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String state = parts[1];
                String thisUuid = parts[2];
                String klass = classifyPublicVolume(major, minor, thisUuid);
                if (!targetClass.equals(klass)) continue;  // wrong volume class
                matchedRowCount++;

                if ("mounted".equals(state)) {
                    String mountPath = "/storage/" + thisUuid;
                    // Cheap check (no shell fork). See note at the
                    // already-accessible branch above for why touch+rm is
                    // unsafe under contention.
                    if (isPathLikelyMounted(mountPath)) {
                        if (isSd) {
                            sdCardPath = mountPath;
                            sdCardAvailable = true;
                            learnSdUuid(thisUuid);  // remember for next unmount window
                        } else {
                            usbPath = mountPath;
                            usbAvailable = true;
                        }
                        logInfo(targetClass + " already mounted at: " + mountPath);
                        reader.close();
                        waitForBounded(listProcess, 2_000, "sm list-volumes (already-mounted)");
                        if (isSd) initSdCardDirectories(); else initUsbDirectories();
                        updateActiveDirectories();
                        reclampLimitsToMountedCeilings();
                        return true;
                    }
                    logWarn(targetClass + " volume " + parts[0] + " reports mounted but path " +
                        mountPath + " not accessible — will force remount");
                }

                volumeId = parts[0];
                volumeUuid = thisUuid;
                volMajor = major;
                volMinor = minor;
                break;
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (ensureVolumeMounted)");

            // Diagnostic: capture the WHY of an `sm list-volumes` miss. On
            // affected BYD models at ACC OFF this often shows publicRows>0
            // but matchedRows==0 (slot present but classifier rejected it
            // because sys.byd.mSdcardUuid is empty during the transition).
            // The mismatch fingerprint tells us whether mitigation B (kernel
            // fallback) or mitigation C (path-based discovery) needs to fire.
            if (volumeId == null) {
                logInfo("sm list-volumes: no " + targetClass + " match (publicRows="
                    + publicRowCount + ", matchedRows=" + matchedRowCount
                    + ") — falling back to kernel-level retry");
                long nowMs = System.currentTimeMillis();
                if (rawSmOutput.length() > 0
                        && (nowMs - lastSmRawDumpAtMs) >= SM_RAW_DUMP_INTERVAL_MS) {
                    logInfo("sm list-volumes raw output:\n" + rawSmOutput.toString().trim());
                    lastSmRawDumpAtMs = nowMs;
                }
            }

            if (volumeId != null) {
                Process mountProcess = Runtime.getRuntime().exec(new String[]{"sm", "mount", volumeId});
                BufferedReader outReader = new BufferedReader(new InputStreamReader(mountProcess.getInputStream()));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(mountProcess.getErrorStream()));
                StringBuilder output = new StringBuilder();
                String outLine;
                while ((outLine = outReader.readLine()) != null) output.append(outLine).append("\n");
                while ((outLine = errReader.readLine()) != null) output.append("ERR: ").append(outLine).append("\n");
                outReader.close();
                errReader.close();

                // 8s ceiling for the actual mount. Healthy SD/USB mounts on
                // BYD finish in <1s; anything past 8s is a stuck vold and
                // we'd rather fall back to internal than wedge the daemon.
                int exitCode = waitForBounded(mountProcess, 8_000, "sm mount " + volumeId);
                logInfo("sm mount " + volumeId + " exit code: " + exitCode +
                    (output.length() > 0 ? ", output: " + output.toString().trim() : ""));

                if (exitCode == 0 && volumeUuid != null) {
                    String mountPath = "/storage/" + volumeUuid;
                    // Lengthened from 10 to 20 iterations (5s → 10s budget).
                    // On affected BYD models the FUSE bridge is published
                    // ~3-6s after `sm mount` returns 0 — the prior 5s budget
                    // raced the publication and falsely concluded the mount
                    // failed. Per-iteration cost is microseconds (StatFs +
                    // canWrite, no shell fork), so the longer poll is free
                    // when the mount is healthy.
                    boolean interrupted = false;
                    for (int i = 0; i < 20 && !interrupted; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            // Restore the flag and break — the watchdog (or
                            // shutdown path) is asking us to stop. Without
                            // this, the prior code swallowed the interrupt
                            // via the outer Exception catch and silently
                            // turned a shutdown request into 10s of polling.
                            Thread.currentThread().interrupt();
                            interrupted = true;
                            break;
                        }
                        if (isPathLikelyMounted(mountPath)) {
                            if (isSd) {
                                sdCardPath = mountPath;
                                sdCardAvailable = true;
                                learnSdUuid(volumeUuid);
                            } else {
                                usbPath = mountPath;
                                usbAvailable = true;
                            }
                            logInfo(targetClass + " mounted successfully at: " + mountPath
                                + " (poll attempt " + (i + 1) + "/20)");
                            if (isSd) initSdCardDirectories(); else initUsbDirectories();
                            updateActiveDirectories();
                            // Re-clamp a persisted oversized limit down to the now-
                            // mounted card's real ceiling (see the /proc/mounts branch).
                            reclampLimitsToMountedCeilings();
                            return true;
                        }
                        logDebug("Waiting for " + targetClass + " mount... attempt " + (i+1) + "/20");
                    }
                    logWarn(targetClass + " mount path not accessible after mount: " + mountPath);
                } else {
                    logWarn("sm mount " + volumeId + " failed with exit code: " + exitCode);
                }
            } else {
                logDebug("No public " + targetClass + " volume found in sm output");
            }

        } catch (Exception e) {
            logError("Error mounting " + targetClass + ": " + e.getMessage());
        }

        // TODO: USB has no analogous kernel-level fallback. /sys/class/mmc_host
        // is mmc-only, so an equivalent USB probe would walk /sys/bus/usb/devices.
        // The reported symptom (ACC OFF → external storage invisible) is SD-only
        // so far; revisit if USB-only configs report the same pattern.
        // Kernel-level fallback for the "sm doesn't see the slot at all" case.
        // On affected BYD ROMs, ACC OFF transiently deregisters the volume from
        // vold's VolumeRecord — but the underlying mmcblk* device is still
        // alive and the kernel mount table either still holds it or is about
        // to. Two recovery routes:
        //   (a) The card may already be mounted by the system at /mnt/media_rw/
        //       or /storage/<uuid> with no `sm` row — discoverVolumes() picks
        //       this up via /proc/mounts. Run a fresh discovery and re-check.
        //   (b) The card is physically present (visible under /sys/class/mmc_host)
        //       but vold hasn't surfaced it yet — wait + re-discover up to 5
        //       times (5s total) for vold to catch up. We don't try `sm forget`
        //       or partition rescans here; those are too invasive and can leave
        //       a wedged volume worse off.
        // If neither route catches the card, fall through to internal storage —
        // the caller's existing fallback path handles that gracefully.
        if (isSd && !sdCardAvailable && isSdCardPhysicallyPresent()) {
            logInfo("Kernel fallback: SD slot reports a card present but sm/proc-mounts didn't find it — polling for vold catch-up");
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                discoverVolumesLocked();  // already hold mountLock
                if (sdCardAvailable && sdCardPath != null
                        && isPathLikelyMounted(sdCardPath)) {
                    logInfo("Kernel fallback: SD picked up after " + (i + 1) + "s at " + sdCardPath);
                    initSdCardDirectories();
                    updateActiveDirectories();
                    // Re-clamp a persisted oversized limit down to the now-mounted
                    // card's real ceiling (see the /proc/mounts branch).
                    reclampLimitsToMountedCeilings();
                    return true;
                }
            }
            logWarn("Kernel fallback: SD slot card present but vold never surfaced it within 5s");
        }

        // Re-run discovery in case mount succeeded but we missed it
        discoverVolumesLocked();  // already hold mountLock
        return isSd ? sdCardAvailable : usbAvailable;
    }

    /**
     * Probe whether the SD slot has a physical card inserted, independent of
     * vold/sm state. Used by the kernel-level mount fallback to distinguish
     * "card pulled" (give up cleanly) from "vold hasn't caught up yet" (worth
     * waiting on).
     *
     * <p><b>Bus-agnostic by design.</b> The SD reader on some BYD trims is
     * wired through a SCSI/USB-storage bridge, so the card enumerates under
     * block major 8 / {@code DEVNAME=sd*} and NEVER appears in
     * {@code /sys/class/mmc_host}. The historical mmc_host-only probe returned
     * false on those vehicles, so this fallback never armed: an ACC-OFF
     * vold-drop then fell back to internal storage for the whole (~tens of
     * seconds) republish window even though the card was physically seated the
     * entire time (see the SCSI-bridge ACC-OFF unmount report, 2026-06).
     *
     * <p>Two SD-specific signals, in order. Neither false-positives on the
     * always-present internal eMMC or on a real USB stick — both of which a
     * raw block-device / major-8 scan would wrongly match, which is exactly
     * why we do NOT scan {@code /sys/block} or {@code /sys/class/scsi_disk}
     * here:
     * <ol>
     *   <li><b>{@code sys.byd.isSDExist}</b> — BYD vendor prop. EXPECTED to
     *       report SD-slot occupancy bus-agnostically (independent of how the
     *       kernel surfaces the device), which is what would cover the
     *       SCSI-bridged case. {@code ExternalStorageCleaner} already reads
     *       this prop. <b>Unverified on-car for major-8/sd* bridged readers as
     *       of 2026-06</b> — the raw value is logged on every probe (see below)
     *       so a single ACC-OFF device session (card seated, then pulled)
     *       resolves both (a) whether it is populated for the bridged reader
     *       and (b) whether it tracks live occupancy or can latch stale. NOTE
     *       we intentionally do NOT corroborate with {@code sys.byd.mSdcardUuid}
     *       (as ExternalStorageCleaner does) here: that prop is empty during
     *       the very unmount window this probe recovers from, so requiring it
     *       would defeat the fallback.</li>
     *   <li><b>{@code /sys/class/mmc_host/mmcN/mmcN:*}</b> — the original
     *       MMC-bus probe, retained for non-BYD hosts and for the case where
     *       the prop is unavailable. The kernel publishes this entry as soon
     *       as a card is electrically detected on the native MMC subsystem.</li>
     * </ol>
     *
     * <p>This probe is consulted only inside the SD kernel-fallback branch,
     * which is reached only after {@code sm}/{@code /proc/mounts} already failed
     * to find the SD — i.e. not on any hot path during normal operation — so
     * logging the raw prop value on each call is a ~per-watchdog-tick cadence
     * during an outage, not a flood.
     *
     * @return true if a card is physically present in the SD slot
     */
    private boolean isSdCardPhysicallyPresent() {
        // Signal 1 (vendor prop, EXPECTED bus-agnostic + SD-specific): the BYD
        // firmware's own SD-slot occupancy flag. Intended to cover SCSI/USB-
        // bridged SD readers (major 8 / sd*) that never surface under
        // /sys/class/mmc_host. Log the RAW value so on-car behavior (populated?
        // live vs sticky?) is resolvable from the daemon log in one session.
        try {
            String sdExist = getSystemProperty("sys.byd.isSDExist");
            logInfo("isSdCardPhysicallyPresent: sys.byd.isSDExist='" + sdExist + "'");
            if ("true".equalsIgnoreCase(sdExist)) {
                return true;
            }
        } catch (Throwable t) {
            logDebug("isSdCardPhysicallyPresent: sys.byd.isSDExist read failed: " + t.getMessage());
        }

        // Signal 2 (MMC bus): /sys/class/mmc_host/mmcN/mmcN:* card-present entry.
        // MUST be SD-specific: internal eMMC ALSO creates a child here (typically
        // mmc0:0001), so a blanket "any mmcN:HEX child" match false-positives on
        // the always-present eMMC. That false positive is dangerous now that the
        // single-volume tiebreaker (discoverVolumes Method 4) consults this probe
        // to PROMOTE a lone volume to SD — on a real-USB-only box it would wrongly
        // promote the USB stick to SD and persist it via learnSdUuid(). So we
        // read each card's `type` attribute and accept ONLY type "SD"/"SDIO"
        // (eMMC reports "MMC"). If `type` can't be read we conservatively do NOT
        // match — better to miss the kernel-fallback recovery (Signal 1
        // sys.byd.isSDExist covers the BYD fleet anyway) than to misclassify.
        try {
            File mmcHostDir = new File("/sys/class/mmc_host");
            if (mmcHostDir.exists() && mmcHostDir.isDirectory()) {
                File[] hosts = mmcHostDir.listFiles();
                if (hosts != null) {
                    for (File host : hosts) {
                        File[] children = host.listFiles();
                        if (children == null) continue;
                        for (File child : children) {
                            if (!child.getName().matches("mmc\\d+:[0-9a-fA-F]+")) continue;
                            // Discriminate external SD from internal eMMC via the
                            // card `type` sysfs node: "SD"/"SDIO" = removable card,
                            // "MMC" = soldered eMMC.
                            File typeFile = new File(child, "type");
                            if (!typeFile.isFile() || !typeFile.canRead()) continue;
                            try (BufferedReader tr = new BufferedReader(new FileReader(typeFile))) {
                                String type = tr.readLine();
                                if (type != null) {
                                    type = type.trim().toUpperCase(java.util.Locale.US);
                                    if (type.startsWith("SD")) {
                                        logInfo("isSdCardPhysicallyPresent: mmc_host card type="
                                                + type + " at " + child.getName());
                                        return true;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logDebug("isSdCardPhysicallyPresent probe failed: " + t.getMessage());
        }
        // Neither signal found a card. Log so the silent-give-up path is
        // visible in the daemon log (the kernel fallback will not arm).
        logInfo("isSdCardPhysicallyPresent: no card detected (isSDExist not true, no mmc_host card entry)");
        return false;
    }

    /**
     * Cheap probe: is any USB mass-storage device attached?
     *
     * <p>Used by the single-volume tiebreaker in {@link #discoverVolumes}
     * to distinguish "lone SCSI-bridged SD card" (no USB device → treat as
     * SD) from "real USB stick is the only thing inserted" (USB device
     * present → keep classifying as USB). We walk {@code /sys/bus/usb/devices}
     * for entries with {@code bInterfaceClass=08} (Mass Storage); pure-host
     * USB ports have no children matching that. Returns false on probe error
     * so the tiebreaker stays conservative — better to miss the SD promotion
     * than to misclassify a real USB stick as SD.
     */
    private boolean isUsbDeviceAttached() {
        try {
            File usbDir = new File("/sys/bus/usb/devices");
            if (!usbDir.exists() || !usbDir.isDirectory()) return false;
            File[] devices = usbDir.listFiles();
            if (devices == null) return false;
            for (File dev : devices) {
                // Skip root hubs (usb1, usb2, …) — those are always present
                // even when nothing is plugged in. Real attached devices show
                // up as e.g. 1-1, 1-1.2 (port path notation).
                String name = dev.getName();
                if (name.startsWith("usb") || !name.contains("-")) continue;
                File[] children = dev.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    File classFile = new File(child, "bInterfaceClass");
                    if (!classFile.isFile() || !classFile.canRead()) continue;
                    try (BufferedReader r = new BufferedReader(new FileReader(classFile))) {
                        String cls = r.readLine();
                        // 08 = USB Mass Storage. 06 = Image (cameras), other
                        // classes (HID etc.) won't surface as a public:
                        // volume in sm list-volumes anyway.
                        if (cls != null && "08".equalsIgnoreCase(cls.trim())) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            logDebug("isUsbDeviceAttached probe failed: " + t.getMessage());
        }
        return false;
    }
    
    /**
     * Check if SD card is currently mounted (without attempting to mount).
     * Simply checks if the path exists and is writable.
     *
     * @return true if SD card is mounted
     */
    public boolean isSdCardMounted() {
        if (sdCardPath == null) {
            return false;
        }
        return isMountWritable(sdCardPath);
    }

    /**
     * Cheap liveness check for the SD card mount, suitable for the watchdog
     * tick (called every 15s). Avoids forking a `touch+rm` shell — that
     * probe blocks for up to 2s under FUSE binder contention from concurrent
     * dir-walks (recordings/stats, storage/external, etc.) and falsely
     * reports "unmounted", triggering a remount cascade that itself runs
     * more shell forks and amplifies the contention.
     *
     * <p>Layered check, fail-fast:
     * <ol>
     *   <li>Path resolved? Directory exists? — Java {@code File} API, no fork.</li>
     *   <li>{@code StatFs.getTotalBytes()} — single binder call, ~200µs.</li>
     *   <li>{@code File.canWrite()} — Java permission check, no fork.</li>
     * </ol>
     * Three signals all green = mount is live. The expensive write probe is
     * reserved for {@link #isMountWritable} which callers invoke when they
     * are about to actually write.
     */
    public boolean isSdCardLikelyMounted() {
        if (sdCardPath == null) return false;
        File d = new File(sdCardPath);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(sdCardPath);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }

    /**
     * Check if USB drive is currently mounted (without attempting to mount).
     */
    public boolean isUsbMounted() {
        if (usbPath == null) {
            return false;
        }
        return isMountWritable(usbPath);
    }

    /**
     * Cheap, fork-free USB-mount probe — mirrors {@link #isSdCardLikelyMounted}
     * for the USB volume. Used by the per-minute watchdog tick so we don't
     * spawn a shell ({@code touch} via {@link #isMountWritable}) every cycle.
     *
     * <p>FIX (audit R5): a {@code touch+rm} fork on every tick (1/min) under
     * FUSE binder contention can itself amplify the contention that the
     * watchdog is supposed to recover from, plus the false-positive "USB
     * unmounted" reading after every UI settings save (when the page reflexively
     * walks USB via /api/storage/external + /api/recordings/stats). The cheap
     * StatFs+canWrite path has none of those side effects.
     *
     * <p>The expensive write-probe is reserved for {@link #isMountWritable} —
     * callers that are about to actually write call it before mid-segment
     * fsync points, where a 2s probe stall is preferable to a silently-vanished
     * mount.
     */
    public boolean isUsbLikelyMounted() {
        if (usbPath == null) return false;
        File d = new File(usbPath);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(usbPath);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }

    /**
     * Classify which physical volume a recording's absolute path lives on,
     * purely from the path string. This is the single source of truth for the
     * per-clip storage badge shown in the recordings library (events.html) and
     * the native recording fragments: because the SD card on this BYD fleet is
     * bridged behind the USB power rail, cutting USB power unmounts the SD and
     * recordings transparently fall back to internal storage (see
     * {@link #resolveActive}). That fallback is otherwise invisible — a clip the
     * user expected on the SD card silently lands on internal. Tagging each clip
     * with where it ACTUALLY landed makes the fallback visible at the level that
     * matters (the individual file), without any new persisted state.
     *
     * <p>Path-based, not mount-based, on purpose: a historical clip must still
     * classify correctly long after the volume it was written to was removed.
     * We match the path prefix against the internal base dir and the currently
     * resolved SD/USB mount roots. Anything under the internal base — including
     * the legacy app-files dir — is INTERNAL. A path under the live SD/USB root
     * is SD_CARD/USB respectively. When the path is under neither (e.g. a clip
     * written to an SD card that has since been swapped for one mounted at a
     * different uuid path), we fall back to: not-internal + a /storage/ prefix
     * that is neither the live USB root ⇒ SD_CARD (the common case), else null
     * ("unknown") so the UI can omit the badge rather than mislabel.
     *
     * @param absPath absolute path of the recording file (or its directory)
     * @return "INTERNAL", "SD_CARD", "USB", or {@code null} if unclassifiable
     */
    public String classifyStorageForPath(String absPath) {
        if (absPath == null || absPath.isEmpty()) return null;
        // Internal base (and the legacy app-files dir) → INTERNAL.
        if (absPath.startsWith(INTERNAL_BASE_DIR)
                || absPath.startsWith(LEGACY_APP_FILES_DIR)) {
            return StorageType.INTERNAL.name();
        }
        // Live mount roots take precedence — exact-volume match.
        final String sd = sdCardPath;
        final String usb = usbPath;
        if (sd != null && !sd.isEmpty() && absPath.startsWith(sd)) {
            return StorageType.SD_CARD.name();
        }
        if (usb != null && !usb.isEmpty() && absPath.startsWith(usb)) {
            return StorageType.USB.name();
        }
        // Path is external (under /storage/<uuid>/...) but doesn't match a
        // currently-resolved root — most likely an SD card written in a prior
        // session/swap. /storage/emulated is always the internal emulated
        // volume on this platform; treat that as INTERNAL, every other
        // /storage/ subtree as SD_CARD (the dominant external on BYD head
        // units; a USB stick that's since been unplugged is rare and the
        // badge degrades gracefully to "SD_CARD" rather than mislabeling).
        if (absPath.startsWith("/storage/emulated")) {
            return StorageType.INTERNAL.name();
        }
        if (absPath.startsWith("/storage/") || absPath.startsWith("/mnt/")) {
            return StorageType.SD_CARD.name();
        }
        return null;
    }

    /**
     * Ensure storage is ready for use.
     * If SD/USB storage is selected but not mounted, attempts to mount it.
     * If mount fails, falls back to internal storage.
     *
     * @param forSurveillance true if checking for surveillance, false for recordings
     * @return true if storage is ready (either SD/USB mounted or fallback to internal)
     */
    public boolean ensureStorageReady(boolean forSurveillance) {
        StorageType selectedType = forSurveillance ? surveillanceStorageType : recordingsStorageType;

        if (selectedType == StorageType.INTERNAL) {
            // Internal storage is always ready
            return true;
        }

        // CRITICAL: Don't switch storage location while recording is active
        // This prevents files from being split across volumes
        if (!forSurveillance && recordingActive.get()) {
            logDebug("Recording active - not switching storage location");
            return true;
        }
        if (forSurveillance && surveillanceActive.get()) {
            logDebug("Surveillance active - not switching storage location");
            return true;
        }

        if (selectedType == StorageType.SD_CARD) {
            if (!isSdCardMounted()) {
                logInfo("SD card not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureSdCardMounted()) {
                    logWarn("Failed to mount SD card, falling back to internal storage");
                    // Let updateActiveDirectories handle the fallback via
                    // resolveActive — single source of truth for "configured
                    // external missing → use internal." Avoids the partial
                    // assignment trap (writing only surveillanceDir+proximityDir
                    // while leaving recordingsDir/tripsDir at stale values).
                    updateActiveDirectories();
                    return true;
                }
            }
            initSdCardDirectories();
            updateActiveDirectories();

            // Pre-reserve space on SD card by cleaning BYD dashcam files if needed
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("Pre-recording CDR cleanup failed: " + e.getMessage());
            }
            return true;
        }

        if (selectedType == StorageType.USB) {
            if (!isUsbMounted()) {
                logInfo("USB not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureUsbMounted()) {
                    logWarn("Failed to mount USB, falling back to internal storage");
                    updateActiveDirectories();
                    return true;
                }
            }
            initUsbDirectories();
            updateActiveDirectories();
            return true;
        }

        return true;
    }

    /**
     * Boot/ACC-on mount-race guard: give the configured external volume a
     * short, bounded window to finish mounting before a caller snapshots its
     * output directory.
     *
     * <p>The constructor mounts SD/USB on the background "StorageMountInit"
     * thread so daemon boot isn't wedged by the post-update vold-unmount
     * stall. Consumers see INTERNAL until that mount lands, then transparently
     * switch — but a surveillance event firing inside that 2-15s window pins
     * its event dir to the internal fallback for the first 1-2 events. This
     * helper lets the enable path PAUSE briefly so the first event's dir is
     * the real external path.
     *
     * <p>It is a strict OBSERVER: it does NOT attempt a mount of its own (the
     * background thread and the concurrent {@link #ensureStorageReady} call
     * are already doing that). Forking another unsynchronized mount here would
     * double up on the FUSE contention the {@code isSdCardLikelyMounted}
     * rationale takes pains to avoid. It only watches the volatile
     * availability flag flip.
     *
     * <p>No-op fast-outs (return immediately, never wait):
     * <ul>
     *   <li>configured INTERNAL → true</li>
     *   <li>configured external already available → true</li>
     *   <li>configured SD but no card physically present → false</li>
     * </ul>
     * Reads the RAW configured type ({@link #surveillanceStorageType}) on
     * purpose: {@link #normalizeStorageType} coerces SD/USB to INTERNAL
     * exactly when the volume isn't available, which is the state we want to
     * WAIT on, not skip.
     *
     * @param timeoutMs maximum time to wait for the flag to flip
     * @return true if the configured volume is (or became) available, false if
     *         it's still unavailable after the wait — caller falls back to
     *         internal for now; the watchdog switches subsequent events later
     */
    public boolean waitForConfiguredExternalMount(long timeoutMs) {
        StorageType t = surveillanceStorageType;            // RAW configured type
        boolean isSd = (t == StorageType.SD_CARD);
        boolean isUsb = (t == StorageType.USB);
        if (!isSd && !isUsb) return true;                   // INTERNAL → no-op
        // Readiness = availability flag AND the surveillance dir field is published.
        // The mount writers (ensureVolumeMounted / discoverVolumes) flip
        // sdCardAvailable/usbAvailable BEFORE the lock-free initSdCardDirectories()
        // populates sdCardSurveillanceDir, so a reader can legitimately observe
        // available==true while the dir is still null. resolveActive() then falls
        // back to internal precisely in that window (`available && dir==null` →
        // internal), which is the first-event-on-internal residual this wait targets.
        // Requiring the dir field keeps the enable-time wait honest so the first
        // trigger only proceeds once the path it will resolve is actually published.
        if (isSd && sdCardAvailable && sdCardSurveillanceDir != null) return true;   // ready → no-op
        if (isUsb && usbAvailable && usbSurveillanceDir != null) return true;
        // Genuinely-absent fast-out: only bother polling when the volume could
        // plausibly land. SD has a cheap physical-present probe; USB has no
        // equivalent, so a USB-configured-but-absent stick polls the full
        // window before falling back (tolerable, once per arm).
        if (isSd && !isSdCardPhysicallyPresent()) {
            logInfo("waitForConfiguredExternalMount: SD configured but no card physically present — not waiting");
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (isSd && sdCardAvailable && sdCardSurveillanceDir != null) return true;
            if (isUsb && usbAvailable && usbSurveillanceDir != null) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return isSd ? (sdCardAvailable && sdCardSurveillanceDir != null)
                            : (usbAvailable && usbSurveillanceDir != null);
            }
        }
        boolean landed = isSd ? (sdCardAvailable && sdCardSurveillanceDir != null)
                              : (usbAvailable && usbSurveillanceDir != null);
        if (!landed) {
            logWarn("waitForConfiguredExternalMount: " + t + " not ready after " + timeoutMs +
                "ms (available+dir) — falling back to internal for now (watchdog will switch later)");
        }
        return landed;
    }

    /**
     * Backwards-compatible alias for {@link #discoverVolumes()} — public
     * callers (refreshSdCard, watchdog) keep working unchanged.
     */
    public void discoverSdCard() {
        discoverVolumes();
    }

    /**
     * Classify a public volume as SD or USB.
     *
     * Three signals, in order of authority:
     *   1. {@code sys.byd.mSdcardUuid} — vendor-set prop carrying the UUID of
     *      the SD card slot's volume. Present on BYD head-units; the most
     *      reliable signal because the firmware itself decides what the
     *      slot is. We compare against the volume's UUID (parts[2] from sm
     *      list-volumes), so this works even when the kernel exposes the
     *      SD reader through a USB/SCSI bridge (which surfaces the device
     *      under major 8 / DEVNAME=sd*, otherwise indistinguishable from
     *      a real USB stick — see Seal 2026-05 firmware).
     *   2. {@code /sys/dev/block/M:N/uevent} DEVNAME — kernel-level. Reliable
     *      when the SD goes through the standard mmc subsystem (major 179),
     *      misleading when SD is bridged through SCSI (sda*). Used as the
     *      first fallback when the BYD prop didn't match.
     *   3. Linux major-number table — last resort.
     *      - 179         → mmcblk* (SD slot)                → SD
     *      - 8, 65..71,  → sd* (SCSI; USB-OTG flash drives) → USB
     *        128..135
     *
     * @return "SD", "USB", or null if classification failed (treat as
     *         "don't claim it for either" — better than misclassifying).
     */
    private String classifyPublicVolume(int major, int minor, String volumeUuid) {
        // Signal 1 (vendor-authoritative, live-only): does this volume's UUID
        // match the BYD SD-slot UUID prop? Most reliable WHEN populated, but
        // BYD only writes the prop while the card is mounted, so this misses
        // during the unmount window between ACC OFF and our remount attempt.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty() && sdUuid.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 1b (vendor-authoritative, persistent): UUID we previously
        // confirmed as SD via a successful mount. Survives the unmount
        // window where the BYD vendor prop returns empty. The FAT volume
        // serial in `volumeUuid` is stable across remount cycles for the
        // same physical card, so a match here is conclusive.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String learned = readLearnedSdUuid();
            if (!learned.isEmpty() && learned.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 2: DEVNAME from the kernel uevent.
        try {
            File ueventFile = new File("/sys/dev/block/" + major + ":" + minor + "/uevent");
            if (ueventFile.exists() && ueventFile.canRead()) {
                BufferedReader r = new BufferedReader(new FileReader(ueventFile));
                String l;
                String devname = null;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("DEVNAME=")) {
                        devname = l.substring("DEVNAME=".length()).trim();
                        break;
                    }
                }
                r.close();
                if (devname != null) {
                    if (devname.startsWith("mmcblk")) return "SD";
                    if (devname.startsWith("sd"))     return "USB";
                }
            }
        } catch (Exception e) {
            logDebug("classifyPublicVolume read failed for " + major + ":" + minor + ": " + e.getMessage());
        }

        // Signal 3: major-number fallback.
        if (major == 179) return "SD";
        if (major == 8 || (major >= 65 && major <= 71) || (major >= 128 && major <= 135)) return "USB";
        return null;
    }

    /**
     * Probe whether the given mount point is writable from app/daemon UID.
     * Java's File.canWrite() returns false on FUSE-bridged mounts that are
     * actually writable via shell, so we fall back to a touch+rm probe.
     */
    private boolean isMountWritable(String mountPath) {
        File dir = new File(mountPath);
        if (!dir.exists() || !dir.isDirectory()) return false;
        if (dir.canWrite()) return true;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "touch " + mountPath + "/.overdrive_probe && rm " + mountPath + "/.overdrive_probe"
            });
            // 2s ceiling — touch/rm against a healthy FUSE mount returns in
            // single-digit ms; anything slower is a stuck filesystem and
            // should be treated as not-writable so we don't latch onto it.
            return waitForBounded(p, 2_000, "isMountWritable(" + mountPath + ")") == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Discover both SD card and USB drive paths in a single pass so they
     * can never alias each other. Replaces the old SD-only discoverSdCard
     * which would happily latch onto a USB stick when both were inserted
     * (the type-blind methods accepted any writable {@code public:} volume).
     *
     * Strategy:
     *   1. {@code sm list-volumes all} — walk every mounted public volume
     *      and classify by underlying block-device major number.
     *   2. BYD UUID prop ({@code sys.byd.mSdcardUuid}) as a tie-breaker
     *      for SD when sm didn't help.
     *   3. /proc/mounts vfat/exfat as final fallback, with the same
     *      major-number classifier applied to the source device.
     *
     * The legacy /storage/ blind scan and SD_CARD_PATHS catch-all are
     * removed — they were the source of the SD/USB confusion.
     */
    public void discoverVolumes() {
        // Serialize against concurrent mount/discovery entrants so the staged→
        // committed field writes below (foundSd*/foundUsb* → sdCard*/usb*) can't
        // interleave with another pass into a torn commit. Reentrant: reached both
        // directly and from inside ensureVolumeMountedLocked (already holding the
        // lock) — Java intrinsic locks are reentrant, so both paths are safe.
        synchronized (mountLock) {
            discoverVolumesLocked();
        }
    }

    private void discoverVolumesLocked() {
        // Stage detection in local vars — only commit to fields on success.
        // Previously we nulled sdCardPath / sdCardAvailable at the top, which
        // meant any transient failure mid-detect (sm timeout, isMountWritable
        // false-positive under FUSE contention, /proc/mounts read error)
        // permanently wiped known-good state until the next watchdog tick.
        // Combined with B5 in the audit, that's the "finds it but can't
        // mount" failure mode the user reported: sm list-volumes correctly
        // returned the volume id, but isMountWritable's `touch+rm` probe
        // timed out under contention so the field was never assigned, and
        // the daemon ran the rest of the session thinking the SD was gone.
        String foundSdPath = null;
        boolean foundSdAvail = false;
        String foundUsbPath = null;
        boolean foundUsbAvail = false;

        // Track every mounted public volume we observed, even ones the
        // classifier couldn't bucket. Used by the single-volume tiebreaker
        // below to promote an ambiguously-typed lone volume to SD when no
        // physical USB stick is present (the v17-and-earlier "any writable
        // public volume → SD" behaviour, scoped to safe conditions).
        java.util.List<String[]> ambiguousMounts = new java.util.ArrayList<>();

        // Method 1: sm list-volumes all
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                // Parse lines like: "public:8,97 mounted 3661-3064"
                line = line.trim();
                if (!line.startsWith("public:") || !line.contains("mounted")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                // parts[0] = "public:8,97" → major=8, minor=97
                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String volumeUuid = parts[2];
                String mountPath = "/storage/" + volumeUuid;
                // Use the cheap layered check — the expensive touch+rm probe
                // here was the source of the false-negative cascade. If a
                // public:* volume is in `mounted` state per `sm` AND the
                // path exists with positive StatFs, trust it.
                if (!isPathLikelyMounted(mountPath)) continue;

                String klass = classifyPublicVolume(major, minor, volumeUuid);
                ambiguousMounts.add(new String[]{mountPath, volumeUuid,
                        klass == null ? "" : klass,
                        String.valueOf(major), String.valueOf(minor)});
                if ("SD".equals(klass) && !foundSdAvail) {
                    foundSdPath = mountPath;
                    foundSdAvail = true;
                    learnSdUuid(volumeUuid);
                    logInfo("Found SD card via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                } else if ("USB".equals(klass) && !foundUsbAvail) {
                    foundUsbPath = mountPath;
                    foundUsbAvail = true;
                    logInfo("Found USB drive via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                }
                // Keep iterating — both kinds may be present.
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (discoverVolumes)");
        } catch (Exception e) {
            logDebug("Could not check sm list-volumes: " + e.getMessage());
        }

        // Method 2: BYD UUID prop is SD-specific. Only use if Method 1 missed SD.
        if (!foundSdAvail) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty()) {
                String uuidPath = "/storage/" + sdUuid;
                if (isPathLikelyMounted(uuidPath) && !uuidPath.equals(foundUsbPath)) {
                    foundSdPath = uuidPath;
                    foundSdAvail = true;
                    learnSdUuid(sdUuid);
                    logInfo("Found SD card via BYD UUID: " + uuidPath);
                }
            }
        }

        // Method 3: /proc/mounts for vfat/exfat — classify the source device
        // by its base name (mmcblk* → SD, sd* → USB) before claiming it.
        if (!foundSdAvail || !foundUsbAvail) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("vfat") && !line.contains("exfat")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) continue;
                    String source = parts[0];      // e.g., /dev/block/mmcblk1p1 or /dev/block/sda1
                    String mountPoint = parts[1];
                    if (mountPoint.startsWith("/mnt/vendor") || mountPoint.startsWith("/firmware") ||
                        mountPoint.equals("/boot") || mountPoint.startsWith("/cache")) {
                        continue;
                    }
                    if (!isPathLikelyMounted(mountPoint)) continue;

                    // Strip /dev/block/ prefix and trailing partition number.
                    String base = source;
                    int slash = base.lastIndexOf('/');
                    if (slash >= 0) base = base.substring(slash + 1);
                    // base now like "mmcblk1p1" or "sda1"
                    String klass = null;
                    if (base.startsWith("mmcblk")) klass = "SD";
                    else if (base.startsWith("sd")) klass = "USB";

                    if ("SD".equals(klass) && !foundSdAvail && !mountPoint.equals(foundUsbPath)) {
                        foundSdPath = mountPoint;
                        foundSdAvail = true;
                        logInfo("Found SD card via /proc/mounts (" + source + "): " + mountPoint);
                    } else if ("USB".equals(klass) && !foundUsbAvail && !mountPoint.equals(foundSdPath)) {
                        foundUsbPath = mountPoint;
                        foundUsbAvail = true;
                        logInfo("Found USB drive via /proc/mounts (" + source + "): " + mountPoint);
                    }
                }
                reader.close();
            } catch (Exception e) {
                logDebug("Could not parse /proc/mounts: " + e.getMessage());
            }
        }

        // Method 4: single-volume tiebreaker (v17 behaviour, scoped).
        // Affected firmwares route the SD slot through a SCSI/USB bridge so
        // the kernel surfaces it as DEVNAME=sd*, major 8 — indistinguishable
        // from a real USB stick to classifyPublicVolume(). On those vehicles
        // the BYD prop is also empty, so Method 2 can't help, and the v17
        // permissive "any writable public volume → SD" code path was lost in
        // the type-discriminating rewrite. Recover for the unambiguous case:
        //   - SD still not found
        //   - exactly one mounted public volume observed by Method 1
        //   - EITHER no physical USB device attached (per /sys/bus/usb/devices)
        //     OR the firmware positively reports a card in the SD slot
        //     (isSdCardPhysicallyPresent → sys.byd.isSDExist / mmc_host).
        // The isSdCardPhysicallyPresent() leg is the regression fix for
        // SCSI-bridged readers: on those the SD reader ITSELF enumerates as a
        // USB mass-storage device, so isUsbDeviceAttached() is permanently
        // true and this tiebreaker never armed even with the SD as the lone
        // volume. Gating the new leg on the firmware SD-present signal keeps a
        // lone REAL USB stick (isSDExist=false, no mmc_host entry) from being
        // mispromoted to SD — that case still falls through, exactly as today.
        // Then the lone volume is, by elimination, the SD slot.
        boolean m4NoUsb = !isUsbDeviceAttached();
        if (!foundSdAvail && ambiguousMounts.size() == 1
                && (m4NoUsb || isSdCardPhysicallyPresent())) {
            String[] only = ambiguousMounts.get(0);
            String mountPath = only[0];
            String volumeUuid = only[1];
            // If we already classed this as USB above, demote it — we now
            // know it's the only volume and (no real USB present, or the
            // firmware says the slot is the SD card).
            if (mountPath.equals(foundUsbPath)) {
                foundUsbPath = null;
                foundUsbAvail = false;
            }
            foundSdPath = mountPath;
            foundSdAvail = true;
            learnSdUuid(volumeUuid);
            logInfo("Found SD card via single-volume tiebreaker ("
                    + (m4NoUsb ? "no USB attached" : "firmware SD-present, bridged reader") + "): "
                    + mountPath + " [" + only[3] + ":" + only[4]
                    + ", classifier=" + (only[2].isEmpty() ? "ambiguous" : only[2]) + "]");
        }

        // Method 4b: USB-OTG-prop elimination (regression fix for the
        // two-major-8 case: a SCSI-bridged SD card (8:97) AND a real USB
        // stick (8:113) mounted at the same time). classifyPublicVolume()
        // buckets BOTH as "USB" when sys.byd.mSdcardUuid is momentarily empty
        // (the ACC-cycle unmount window) AND no learned UUID exists yet, so
        // Method 1 finds no SD; Method 4 can't help because there are two
        // volumes, not one. Use the firmware's OWN USB-OTG identifier
        // (sys.byd.mUsbotgUuid, symmetric to mSdcardUuid) to name the real
        // USB, then the single remaining mounted volume is the SD by
        // elimination. Strictly additive and positive-evidence-only:
        //   - SD still not found
        //   - firmware names a USB-OTG uuid AND that volume is actually
        //     mounted right now (otgSeen → the firmware view is LIVE, not
        //     stale), so we never act on a phantom USB identity
        //   - exactly one OTHER mounted volume remains
        //   - firmware confirms a card is in the SD slot
        //     (isSdCardPhysicallyPresent) — this guard stops the exotic
        //     "two USB sticks via a hub, no SD" layout from promoting the
        //     second stick to SD.
        if (!foundSdAvail && !ambiguousMounts.isEmpty()) {
            String otgUuid = getSystemProperty("sys.byd.mUsbotgUuid");
            // Contradictory-firmware guard: if the OTG prop names the SAME uuid
            // the firmware (or our learned record) calls the SD card, the props
            // are internally inconsistent. Acting would EXCLUDE the SD as "OTG"
            // and promote the real USB stick to SD (a persisted SD/USB swap via
            // learnSdUuid). Refuse Method 4b in that case and let the safer
            // signals / fallback handle it.
            String sdUuidProp = getSystemProperty("sys.byd.mSdcardUuid");
            String learnedSd = readLearnedSdUuid();
            boolean otgContradictsSd = otgUuid != null && !otgUuid.isEmpty()
                    && ((sdUuidProp != null && otgUuid.equalsIgnoreCase(sdUuidProp))
                        || (learnedSd != null && !learnedSd.isEmpty()
                            && otgUuid.equalsIgnoreCase(learnedSd)));
            if (otgContradictsSd) {
                logWarn("Method 4b skipped: sys.byd.mUsbotgUuid (" + otgUuid
                        + ") collides with the SD uuid (prop=" + sdUuidProp
                        + ", learned=" + learnedSd + ") — contradictory firmware, not eliminating");
            }
            if (!otgContradictsSd && otgUuid != null && !otgUuid.isEmpty()) {
                boolean otgSeen = false;
                java.util.List<String[]> nonOtg = new java.util.ArrayList<>();
                for (String[] m : ambiguousMounts) {
                    if (otgUuid.equalsIgnoreCase(m[1])) otgSeen = true;
                    else nonOtg.add(m);
                }
                if (otgSeen && nonOtg.size() == 1 && isSdCardPhysicallyPresent()) {
                    String[] sd = nonOtg.get(0);
                    String mountPath = sd[0];
                    String volumeUuid = sd[1];
                    // Pin the firmware-named volume as the USB (it may have
                    // been the one Method 1 latched as foundUsbPath, or none).
                    String otgPath = "/storage/" + otgUuid;
                    if (isPathLikelyMounted(otgPath)) {
                        foundUsbPath = otgPath;
                        foundUsbAvail = true;
                    }
                    // Don't let the SD volume also masquerade as USB.
                    if (mountPath.equals(foundUsbPath)) {
                        foundUsbPath = null;
                        foundUsbAvail = false;
                    }
                    foundSdPath = mountPath;
                    foundSdAvail = true;
                    learnSdUuid(volumeUuid);
                    logInfo("Found SD card via USB-OTG elimination (firmware names "
                            + otgUuid + " as USB-OTG, SD-present confirmed): " + mountPath
                            + " [" + sd[3] + ":" + sd[4]
                            + ", classifier=" + (sd[2].isEmpty() ? "ambiguous" : sd[2]) + "]");
                }
            }
        }

        // Commit results atomically. Volumes that disappeared since the last
        // detection do go from non-null → null here; that's correct behavior
        // (the card was actually pulled). What we avoid is the transient-
        // failure case where Method 1 found the card via sm list-volumes
        // but Method 1's writability probe timed out — without staging, that
        // would have nulled state mid-walk and Method 2/3 wouldn't recover
        // because they branch on `!sdCardAvailable` (now `!foundSdAvail`,
        // which preserved the success).
        sdCardPath = foundSdPath;
        sdCardAvailable = foundSdAvail;
        usbPath = foundUsbPath;
        usbAvailable = foundUsbAvail;

        if (!sdCardAvailable) logDebug("No writable SD card found");
        if (!usbAvailable) logDebug("No writable USB drive found");
    }

    /** Cheap mount-liveness check for any path. Same layered logic as
     * {@link #isSdCardLikelyMounted} but for arbitrary mount points.
     * StatFs + canWrite, no shell fork. */
    private boolean isPathLikelyMounted(String path) {
        if (path == null) return false;
        File d = new File(path);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(path);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }
    
    /**
     * Get Android system property via reflection or shell.
     */
    private String getSystemProperty(String key) {
        try {
            // Try reflection first
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            // Fall back to shell
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                waitForBounded(p, 1_000, "getprop " + key);
                return line != null ? line.trim() : "";
            } catch (Exception e2) {
                return "";
            }
        }
    }

    /**
     * Read the persisted UUID of the volume previously confirmed as SD. See
     * {@link #LEARNED_SD_UUID_FILE} for why this exists. Returns empty string
     * if no learned value (first boot, or file missing).
     */
    private String readLearnedSdUuid() {
        File f = new File(LEARNED_SD_UUID_FILE);
        if (!f.exists() || !f.canRead()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Persist the UUID of a volume just confirmed as SD. Idempotent — re-writes
     * are cheap and harmless. We only record on a successful mount, so this
     * value only ever describes a real, working SD card.
     */
    private void learnSdUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        if (uuid.equalsIgnoreCase(readLearnedSdUuid())) return;  // unchanged, skip write
        try (FileWriter w = new FileWriter(LEARNED_SD_UUID_FILE, false)) {
            w.write(uuid);
            // 0644 — daemon (UID 2000) writes, app process needs to read on
            // the rare path where it walks classifyPublicVolume itself.
            try { new File(LEARNED_SD_UUID_FILE).setReadable(true, false); } catch (Exception ignored) {}
            logInfo("Learned SD UUID for future classification: " + uuid);
        } catch (Exception e) {
            logDebug("learnSdUuid write failed: " + e.getMessage());
        }
    }


    /**
     * Initialize storage directories.
     * IMPORTANT: Sets world-readable permissions so the UI app can access recordings.
     */
    private void initDirectories() {
        // Initialize internal storage directories (always available)
        File internalBaseDir = new File(INTERNAL_BASE_DIR);
        if (!internalBaseDir.exists()) {
            boolean created = internalBaseDir.mkdirs();
            logInfo("Created internal base directory: " + INTERNAL_BASE_DIR + " (success=" + created + ")");
        }
        internalBaseDir.setReadable(true, false);
        internalBaseDir.setExecutable(true, false);
        
        internalRecordingsDir = new File(internalBaseDir, RECORDINGS_SUBDIR);
        if (!internalRecordingsDir.exists()) {
            boolean created = internalRecordingsDir.mkdirs();
            logInfo("Created internal recordings directory: " + internalRecordingsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalRecordingsDir.setReadable(true, false);
        internalRecordingsDir.setExecutable(true, false);
        
        internalSurveillanceDir = new File(internalBaseDir, SURVEILLANCE_SUBDIR);
        if (!internalSurveillanceDir.exists()) {
            boolean created = internalSurveillanceDir.mkdirs();
            logInfo("Created internal surveillance directory: " + internalSurveillanceDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalSurveillanceDir.setReadable(true, false);
        internalSurveillanceDir.setExecutable(true, false);
        
        internalProximityDir = new File(internalBaseDir, PROXIMITY_SUBDIR);
        if (!internalProximityDir.exists()) {
            boolean created = internalProximityDir.mkdirs();
            logInfo("Created internal proximity directory: " + internalProximityDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalProximityDir.setReadable(true, false);
        internalProximityDir.setExecutable(true, false);
        
        internalTripsDir = new File(internalBaseDir, TRIPS_SUBDIR);
        if (!internalTripsDir.exists()) {
            boolean created = internalTripsDir.mkdirs();
            logInfo("Created internal trips directory: " + internalTripsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalTripsDir.setReadable(true, false);
        internalTripsDir.setExecutable(true, false);
        
        // Initialize SD card and USB directories if available
        initSdCardDirectories();
        initUsbDirectories();
    }

    /**
     * Initialize SD card directories if SD card is available.
     */
    private void initSdCardDirectories() {
        // Under mountLock: this reads the (sdCardAvailable, sdCardPath) pair and
        // derives the sdCard*Dir fields from it. It is called both from inside the
        // locked mount/discovery path (reentrant — already holds the lock) AND from
        // unlocked callers (ensureStorageReady, refreshUsb, the constructor, the
        // watchdog success branch). Without the lock, an unlocked caller could
        // observe a half-committed (available=true, path=null) pair mid-remount and
        // null the derived dirs — leaving the recorder pointed at internal even
        // after the card is back. Serializing here keeps the derived dirs coherent
        // with the same critical section that commits their source-of-truth pair.
        synchronized (mountLock) {
            if (!sdCardAvailable || sdCardPath == null) {
                sdCardRecordingsDir = null;
                sdCardSurveillanceDir = null;
                sdCardProximityDir = null;
                sdCardTripsDir = null;
                return;
            }
            File[] dirs = initVolumeDirectories(sdCardPath, "SD card");
            if (dirs != null) {
                sdCardRecordingsDir   = dirs[0];
                sdCardSurveillanceDir = dirs[1];
                sdCardProximityDir    = dirs[2];
                sdCardTripsDir        = dirs[3];
            }
        }
    }

    /**
     * Initialize USB directories if USB drive is available.
     */
    private void initUsbDirectories() {
        // Under mountLock — same rationale as initSdCardDirectories (keep the
        // derived usb*Dir fields coherent with the locked (usbAvailable, usbPath)
        // commit; reentrant for the in-lock callers).
        synchronized (mountLock) {
            if (!usbAvailable || usbPath == null) {
                usbRecordingsDir = null;
                usbSurveillanceDir = null;
                usbProximityDir = null;
                usbTripsDir = null;
                return;
            }
            File[] dirs = initVolumeDirectories(usbPath, "USB");
            if (dirs != null) {
                usbRecordingsDir   = dirs[0];
                usbSurveillanceDir = dirs[1];
                usbProximityDir    = dirs[2];
                usbTripsDir        = dirs[3];
            }
        }
    }

    /**
     * Build {@code <volumePath>/Overdrive/{recordings,surveillance,proximity,trips}}
     * with world rwx so the app UID can read them. Returns the four dirs in
     * order, or null if the base couldn't be created.
     */
    private File[] initVolumeDirectories(String volumePath, String label) {
        File base = new File(volumePath, "Overdrive");
        boolean baseCreated = base.mkdirs();
        if (!base.exists()) {
            logError("Failed to create " + label + " base directory: " + base.getAbsolutePath());
            return null;
        }
        if (baseCreated) {
            logInfo("Created " + label + " base directory: " + base.getAbsolutePath());
        }
        base.setReadable(true, false);
        base.setWritable(true, false);
        base.setExecutable(true, false);

        File rec = makeChildDir(base, RECORDINGS_SUBDIR, label + " recordings");
        File surv = makeChildDir(base, SURVEILLANCE_SUBDIR, label + " surveillance");
        File prox = makeChildDir(base, PROXIMITY_SUBDIR, label + " proximity");
        File trips = makeChildDir(base, TRIPS_SUBDIR, label + " trips");

        if (surv != null && surv.exists() && !surv.canWrite()) {
            logError(label + " surveillance directory exists but is not writable: " + surv.getAbsolutePath());
        }
        return new File[]{rec, surv, prox, trips};
    }

    private File makeChildDir(File parent, String name, String label) {
        File dir = new File(parent, name);
        boolean created = dir.mkdirs();
        if (!dir.exists()) {
            logError("Failed to create " + label + " directory: " + dir.getAbsolutePath());
            return dir;
        }
        if (created) {
            logInfo("Created " + label + " directory: " + dir.getAbsolutePath());
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
        return dir;
    }
    
    /**
     * Resolve the active directory for one (category, type) pair, falling
     * back to internal when the requested external volume isn't ready.
     * Logs the fallback only when we actually downgraded (else the boot path
     * spams "fell back" lines for users who never selected SD/USB).
     *
     * <p>Returns a small holder so the caller can log both the configured
     * type AND the resolved type. The previous API returned only the File,
     * forcing the caller to log the configured enum even when we'd
     * downgraded — the resulting log line ("Trips using SD_CARD:
     * /storage/emulated/0/...") was actively misleading on Seal trims with
     * no SD slot.
     */
    private static final class ResolvedDir {
        final File dir;
        final StorageType resolved;
        ResolvedDir(File dir, StorageType resolved) {
            this.dir = dir;
            this.resolved = resolved;
        }
    }

    private ResolvedDir resolveActive(StorageType type,
                                      File internalDir, File sdDir, File usbDir,
                                      String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable && sdDir != null) {
                return new ResolvedDir(sdDir, StorageType.SD_CARD);
            }
            logWarn("SD card not available for " + label + ", falling back to internal storage");
            return new ResolvedDir(internalDir, StorageType.INTERNAL);
        }
        if (type == StorageType.USB) {
            if (usbAvailable && usbDir != null) {
                return new ResolvedDir(usbDir, StorageType.USB);
            }
            logWarn("USB not available for " + label + ", falling back to internal storage");
            return new ResolvedDir(internalDir, StorageType.INTERNAL);
        }
        return new ResolvedDir(internalDir, StorageType.INTERNAL);
    }

    /**
     * Reconcile a recorder's latched recordings output-dir override against
     * the CURRENT resolved storage state, at the instant the recorder is about
     * to consume it for a fresh recording.
     *
     * <p><b>Why this exists.</b> The volume watchdog pushes a one-shot
     * {@code setOutputDir} override onto the pano recorder: the SUCCESS branch
     * pushes the live SD/USB path, the FAILURE branch pushes the INTERNAL
     * fallback so segments keep landing somewhere while the external volume is
     * gone. That override outranks {@link #getRecordingsDir()} at consumption
     * time. But when an SD card comes back via the recording-start path's own
     * mount attempt (not a watchdog SUCCESS tick), nothing refreshes the
     * latched INTERNAL override — so the very next recording lands on internal
     * even though the configured SD remounted moments earlier (observed
     * 2026-06: SD back at 09:38:20.435, recording opened on internal 0.3s
     * later). This reconciles that stale latch.
     *
     * <p><b>Conservative by construction — no regression to the override
     * mechanism.</b> The override is redirected ONLY in the exact stale case:
     * the configured recordings volume is external AND it now resolves as
     * available again (the watchdog-FAILURE internal latch is obsolete). In
     * every other case the override is returned unchanged:
     * <ul>
     *   <li>override already equals the live target → unchanged;</li>
     *   <li>configured volume still resolves to internal (genuinely gone) →
     *       unchanged, so we never redirect a recording onto a volume we just
     *       resolved as unavailable;</li>
     *   <li>configured storage is INTERNAL → unchanged (no external latch is
     *       ever pushed in that mode).</li>
     * </ul>
     * Any failure falls back to returning the override as-is.
     *
     * <p>Safe to call at start-of-recording only (no in-flight segment to
     * split): the recorder guards on {@code recording==false} before this.
     *
     * <p><b>Thread-safety.</b> This runs on the recording-start thread while the
     * volume watchdog (a separate thread) and HTTP storage-setter threads mutate
     * the volume state. We (1) snapshot the configured type, per-class dirs and
     * availability flags under {@code recordingsCleanupLock} — the same lock the
     * recordings-category writers ({@code updateActiveDirectories}) take, which
     * gives this reader a happens-before edge to those writes and reads the
     * {availability, dir} pair as a consistent unit rather than a torn mix; and
     * (2) before actually redirecting, re-confirm the external volume is live
     * RIGHT NOW with the cheap fork-free liveness probe. Step (2) is the real
     * safety net: even if the snapshot's availability flag is momentarily stale
     * (card re-pulled since the watchdog last ran), the StatFs+canWrite probe
     * fails on the vanished mount and we keep the protective internal override
     * — so a stale read can never redirect a recording onto a dead SD/USB path.
     *
     * @param override the recorder's currently latched override (may be null)
     * @return the directory the recorder should actually use; {@code override}
     *         unchanged unless it is a confirmed-stale internal fallback AND the
     *         configured external volume is verified live at this instant
     */
    public File reconcileRecordingOverride(File override) {
        if (override == null) return null;
        try {
            // (1) Consistent snapshot under the recordings-category lock.
            final StorageType type;
            final File sdDir, usbDir;
            final boolean sdAvail, usbAvail;
            synchronized (recordingsCleanupLock) {
                type = recordingsStorageType;
                sdDir = sdCardRecordingsDir;
                usbDir = usbRecordingsDir;
                sdAvail = sdCardAvailable;
                usbAvail = usbAvailable;
            }

            // Internal mode never carries an external latch worth reconciling.
            if (type == StorageType.INTERNAL) return override;

            // Resolve the live external dir for the configured type, but only
            // if it currently resolves as available. If it doesn't, the volume
            // is genuinely gone — leave the protective override untouched.
            final File live;
            final boolean liveMounted;
            if (type == StorageType.SD_CARD) {
                if (!sdAvail || sdDir == null) return override;
                live = sdDir;
                liveMounted = isSdCardLikelyMounted();
            } else { // USB
                if (!usbAvail || usbDir == null) return override;
                live = usbDir;
                liveMounted = isUsbLikelyMounted();
            }

            // Already pointed at what we'd pick right now — nothing to do.
            if (live.getAbsolutePath().equals(override.getAbsolutePath())) {
                return override;
            }

            // (2) Ground-truth re-confirm: only redirect onto a volume that is
            // verifiably live at this instant. Neutralizes a stale availability
            // read — a re-pulled card fails this probe and we keep the override.
            if (!liveMounted) return override;

            logInfo("Recorder override reconciled: configured " + type
                + " is available again — redirecting stale override "
                + override.getAbsolutePath() + " → " + live.getAbsolutePath());
            return live;
        } catch (Throwable t) {
            logWarn("reconcileRecordingOverride failed, using override as-is: " + t.getMessage());
        }
        return override;
    }

    /**
     * ENOSPC fallback: given the directory a recorder is about to write into,
     * return an INTERNAL recordings directory instead if {@code targetDir}
     * lives on a configured EXTERNAL volume that is mounted-but-full (free
     * space below {@code reserveBytes}) AND internal storage has room.
     *
     * <p><b>Why this exists.</b> The existing fallback machinery
     * ({@link #resolveActive}, the volume watchdog, {@link
     * #reconcileRecordingOverride}) only redirects to internal when an external
     * volume is <em>unmounted</em> (availability flag false). A mounted-but-FULL
     * SD/USB volume is treated as "ready" everywhere, so the recorder retargets
     * it forever: every segment hits ENOSPC, the disk writer aborts after 5
     * failures and quarantines each clip as {@code .broken}, while
     * {@code modeActive} stays true — the "REC green, no file" permanent-death
     * case. This method closes that gap at the one place it matters: the
     * instant a recorder resolves its segment target.
     *
     * <p><b>One-directional and start-of-segment only.</b> It only ever
     * redirects external→internal (never internal→external), so it cannot
     * ping-pong a live session across volumes. Callers invoke it at
     * start-of-recording / start-of-segment, never mid-clip. When the external
     * volume frees space (retention reap) or is swapped, the next segment's
     * resolution naturally returns to it because {@link #getRecordingsDir()}
     * still points at the external dir — this method redirects a COPY of the
     * target for this segment, it does NOT mutate {@code recordingsDir}.
     *
     * <p><b>Conservative.</b> Returns {@code targetDir} unchanged when: target
     * is null; target is already internal (path under {@link
     * #INTERNAL_BASE_DIR}); StatFs on the target succeeds and shows enough free;
     * or internal itself lacks {@code reserveBytes}. If the target StatFs THROWS
     * (genuinely unmounted), this returns {@code targetDir} unchanged and leaves
     * recovery to the unmount path (watchdog / resolveActive / reconcile) —
     * this method's job is the FULL case, not the GONE case.
     *
     * @param targetDir   the directory the recorder intends to use
     * @param reserveBytes minimum free bytes a new segment needs
     * @return the directory to actually write into — {@code targetDir} or an
     *         internal recordings dir
     */
    public File resolveTargetWithEnospcFallback(File targetDir, long reserveBytes) {
        return resolveTargetWithEnospcFallback(targetDir, reserveBytes, false);
    }

    /**
     * @param trackState when true, this resolution updates
     *        {@link #recordingsEnospcFallbackActive} so the UI banner /
     *        getActiveRecordingsStorageType() reflect a full-volume redirect.
     *        Only the PRIMARY recordings (cam_*) path passes true; secondary
     *        callers (OEM dvr_, smaller reserve) pass false so the two reserve
     *        thresholds can't flap the shared flag.
     */
    public File resolveTargetWithEnospcFallback(File targetDir, long reserveBytes, boolean trackState) {
        if (targetDir == null) return targetDir;
        try {
            String targetPath = targetDir.getAbsolutePath();
            // Already internal — nothing to fall back to. Don't touch the flag:
            // an internal-configured user is never "fell back from external."
            if (targetPath.startsWith(INTERNAL_BASE_DIR)) return targetDir;

            // Measure free space on the target volume. A throw here means the
            // volume is unmounted/half-mounted (the GONE case) — not our job;
            // leave it to the unmount fallback path.
            long targetFree;
            try {
                File probe = targetDir.exists() ? targetDir : targetDir.getParentFile();
                if (probe == null || !probe.exists()) return targetDir;
                targetFree = new StatFs(probe.getAbsolutePath()).getAvailableBytes();
            } catch (Throwable statThrow) {
                logWarn("ENOSPC-fallback: StatFs on external target " + targetPath
                    + " threw (" + statThrow.getMessage() + ") — treating as unmounted, "
                    + "leaving to the unmount fallback path");
                return targetDir;
            }

            if (targetFree >= reserveBytes) {
                // External volume has room for THIS segment — keep it. But only
                // CLEAR the full-fallback flag once free is durably above the
                // recovery hysteresis (200MB), not merely back over the per-segment
                // reserve (100MB). Setting the flag at <reserveBytes and clearing it
                // at >=reserveBytes shares one boundary, so a volume hovering at the
                // reserve flaps the banner / scope every segment rotation. The wider
                // clear threshold matches the periodic ticker (ENOSPC_FALLBACK_RECOVER_BYTES)
                // so both writers of the flag use the same hysteresis band.
                if (trackState && targetFree >= ENOSPC_FALLBACK_RECOVER_BYTES) {
                    recordingsEnospcFallbackActive = false;
                }
                return targetDir;
            }

            // External volume is mounted-but-full. Redirect to internal IFF
            // internal has the reserve.
            long internalFree = getInternalFreeSpace();
            if (internalFree < reserveBytes) {
                logWarn("ENOSPC-fallback: external target " + targetPath + " is full ("
                    + formatSize(targetFree) + " free < " + formatSize(reserveBytes)
                    + " reserve) AND internal is also short (" + formatSize(internalFree)
                    + " free) — cannot fall back; recording will fail to save");
                // Both volumes short — we did NOT redirect to internal, so don't
                // claim a fall-back. (The recording will likely fail; the wedge
                // detector + diagnostic log surface that separately.)
                if (trackState) recordingsEnospcFallbackActive = false;
                return targetDir;
            }

            File internalDir = internalRecordingsDir;
            if (internalDir == null) return targetDir;
            if (!internalDir.exists()) internalDir.mkdirs();
            logWarn("ENOSPC-fallback: external target " + targetPath + " is full ("
                + formatSize(targetFree) + " free < " + formatSize(reserveBytes)
                + " reserve) — redirecting THIS segment to internal "
                + internalDir.getAbsolutePath() + " (" + formatSize(internalFree)
                + " free) so recording is not lost");
            // Rising edge of the fallback: trim internal to its effective cap NOW
            // (off this per-segment path, on the async executor) so internal stays
            // bounded from the first fallback segment rather than waiting up to 30s
            // for the periodic ticker. ensureRecordingsSpace's own active-volume
            // scoping does the internal-only bounding; it self-defers if the encoder
            // is mid-write. Only on the false→true transition so we don't re-enqueue
            // a reap every segment for the whole full-disk stint.
            //
            // ORDERING (data-loss guard): set the flag true BEFORE enqueueing so the
            // executor task observes it via the j.u.c. submit happens-before edge.
            // getActiveRecordingsStorageType() reads this flag to decide INTERNAL; if
            // the task ran while it still read false, ensureSpace would NOT rescope and
            // would reap the combined pool against the full external limit — deleting
            // the configured SD/USB archive. Setting it first makes the scoping engage.
            boolean risingEdge = trackState && !recordingsEnospcFallbackActive;
            if (trackState) recordingsEnospcFallbackActive = true;
            if (risingEdge) {
                try {
                    asyncCleanupExecutor.execute(() -> {
                        try { ensureRecordingsSpace(0, internalDir); }
                        catch (Throwable t) { logWarn("Fallback-engage internal trim failed: " + t.getMessage()); }
                    });
                } catch (Throwable ignored) {
                    // Executor shutting down — the 30s ticker + post-save cleanup still bound internal.
                }
            }
            return internalDir;
        } catch (Throwable t) {
            logWarn("resolveTargetWithEnospcFallback failed, using target as-is: " + t.getMessage());
            return targetDir;
        }
    }

    /**
     * SURVEILLANCE-bucket analogue of {@link #resolveTargetWithEnospcFallback}:
     * when the configured external surveillance volume is mounted-but-FULL,
     * redirect THIS event clip to the INTERNAL SURVEILLANCE dir (not the
     * recordings internal dir) so it isn't quarantined as {@code .broken} on a
     * packed card.
     *
     * <p><b>Why a separate method.</b> {@link #resolveTargetWithEnospcFallback}
     * hard-codes {@code internalRecordingsDir} as its spill target and flips the
     * recordings UI banner. Spilling an {@code event_*.mp4} into the recordings
     * folder would orphan it: the surveillance cleanup scans
     * {@code getReapableDirs("surveillance")} with the {@code event_} prefix and
     * the recordings cleanup scans with the {@code cam} prefix — a surveillance
     * clip in the recordings dir matches neither and would never be reaped.
     * Spilling to {@code internalSurveillanceDir} (which IS in
     * {@code getAllSurveillanceDirs()}) keeps it in the surveillance reap pool.
     *
     * <p>Mirrors the conservative contract of the recordings variant: returns
     * {@code targetDir} unchanged when target is null, already internal, StatFs
     * throws (the unmounted/GONE case — left to the watchdog), the external has
     * room, or internal itself lacks the reserve. Does NOT touch the recordings
     * ENOSPC banner flag. Any throw returns {@code targetDir} as-is.
     *
     * <p>Resolved ONCE per trigger (callers guard {@code recording==false}), so
     * no clip is split across volumes.
     */
    public File resolveSurveillanceTargetWithEnospcFallback(File targetDir, long reserveBytes) {
        if (targetDir == null) return targetDir;
        try {
            String targetPath = targetDir.getAbsolutePath();
            if (targetPath.startsWith(INTERNAL_BASE_DIR)) return targetDir;

            long targetFree;
            try {
                File probe = targetDir.exists() ? targetDir : targetDir.getParentFile();
                if (probe == null || !probe.exists()) return targetDir;
                targetFree = new StatFs(probe.getAbsolutePath()).getAvailableBytes();
            } catch (Throwable statThrow) {
                logWarn("ENOSPC-fallback(surveillance): StatFs on external target " + targetPath
                    + " threw (" + statThrow.getMessage() + ") — treating as unmounted, "
                    + "leaving to the unmount fallback path");
                return targetDir;
            }

            if (targetFree >= reserveBytes) {
                return targetDir;  // external has room for THIS clip
            }

            File internalDir = internalSurveillanceDir;
            if (internalDir == null) return targetDir;
            long internalFree = getInternalFreeSpace();
            if (internalFree < reserveBytes) {
                logWarn("ENOSPC-fallback(surveillance): external target " + targetPath + " is full ("
                    + formatSize(targetFree) + " free < " + formatSize(reserveBytes)
                    + " reserve) AND internal is also short (" + formatSize(internalFree)
                    + " free) — cannot fall back; event may fail to save");
                return targetDir;
            }

            if (!internalDir.exists()) internalDir.mkdirs();
            logWarn("ENOSPC-fallback(surveillance): external target " + targetPath + " is full ("
                + formatSize(targetFree) + " free < " + formatSize(reserveBytes)
                + " reserve) — redirecting THIS event to internal surveillance "
                + internalDir.getAbsolutePath() + " (" + formatSize(internalFree)
                + " free) so the event is not lost");
            return internalDir;
        } catch (Throwable t) {
            logWarn("resolveSurveillanceTargetWithEnospcFallback failed, using target as-is: " + t.getMessage());
            return targetDir;
        }
    }

    /**
     * Emit the canonical "<Category> using <type>: <path>" line. When the
     * resolved type doesn't match what the user configured (external volume
     * missing → fell back to internal), the line includes both so log
     * readers don't have to cross-reference the path against the directory
     * constants to detect a fallback.
     */
    private void logResolvedDir(String label, StorageType configured, ResolvedDir r) {
        if (r.resolved != configured) {
            logInfo(label + " configured=" + configured + " active=" + r.resolved
                + " (fallback): " + r.dir.getAbsolutePath());
        } else {
            logInfo(label + " using " + r.resolved + ": " + r.dir.getAbsolutePath());
        }
    }

    /**
     * Update active directories based on storage type selection.
     * Falls back to internal storage if the selected external volume is not
     * available. Per-category recording-active guard prevents files from
     * being split across volumes when the user changes storage mid-recording.
     */
    private void updateActiveDirectories() {
        // Each per-category lock is taken briefly so a concurrent
        // ensureXxxSpace / sweep / wipe sees an atomic dir swap (not a
        // torn read where one volatile read returns the old dir and a
        // later read in the same call path returns the new one).
        // resolveActive reads internalXxxDir (immutable after init) plus
        // sdCardXxxDir / usbXxxDir / sdCardAvailable / usbAvailable. The
        // external dir + availability fields are NOT immutable — they are
        // (re)assigned/null-cleared by initSdCardDirectories/initUsbDirectories
        // and discoverVolumes on the remount paths. They are declared volatile
        // for cross-thread visibility; we hold the per-category lock here for
        // the assignment fence and so a reader taking the same lock (e.g.
        // reconcileRecordingOverride) sees the {availability, dir} pair
        // consistently rather than a torn mix.

        // Recordings directory
        synchronized (recordingsCleanupLock) {
            if (recordingActive.get()) {
                logDebug("Recording active - skipping recordings directory update");
            } else {
                ResolvedDir r = resolveActive(recordingsStorageType,
                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir, "recordings");
                recordingsDir = r.dir;
                logResolvedDir("Recordings", recordingsStorageType, r);
            }
        }

        // Surveillance directory
        synchronized (surveillanceCleanupLock) {
            if (surveillanceActive.get()) {
                logDebug("Surveillance active - skipping surveillance directory update");
            } else {
                ResolvedDir r = resolveActive(surveillanceStorageType,
                    internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir, "surveillance");
                surveillanceDir = r.dir;
                logResolvedDir("Surveillance", surveillanceStorageType, r);
            }
        }

        // Proximity follows the RECORDINGS (ACC-ON) storage type. Proximity
        // recording is an ACC-ON feature (PROXIMITY_GUARD runs while the car is
        // on/armed), so it must honor the user's ACC-ON storage selection
        // (recordingsStorageType), NOT the ACC-OFF/surveillance one. Routing it
        // to surveillanceStorageType meant "ACC-ON storage = SD, ACC-OFF = INTERNAL"
        // silently put ACC-ON proximity clips on internal. NOTE: proximity has no
        // ENOSPC per-segment redirect of its own, so it resolves with the plain
        // mount-based resolveActive(recordingsStorageType) here (and a mount-based
        // active type below) rather than the recordings ENOSPC-aware path.
        synchronized (proximityCleanupLock) {
            if (!surveillanceActive.get()) {
                ResolvedDir r = resolveActive(recordingsStorageType,
                    internalProximityDir, sdCardProximityDir, usbProximityDir, "proximity");
                proximityDir = r.dir;
                // Proximity follows recordings silently — no log here, the
                // recordings line already conveyed the resolution.
            }
        }

        // Trips directory — trip telemetry files are small, no active guard
        synchronized (tripsCleanupLock) {
            ResolvedDir rTrips = resolveActive(tripsStorageType,
                internalTripsDir, sdCardTripsDir, usbTripsDir, "trips");
            tripsDir = rTrips.dir;
            logResolvedDir("Trips", tripsStorageType, rTrips);
        }
    }
    
    /**
     * Load storage limits and storage type from config file.
     */
    private void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                JSONObject storage = config.optJSONObject("storage");
                if (storage != null) {
                    recordingsLimitMb = storage.optLong("recordingsLimitMb", DEFAULT_RECORDINGS_LIMIT_MB);
                    surveillanceLimitMb = storage.optLong("surveillanceLimitMb", DEFAULT_SURVEILLANCE_LIMIT_MB);
                    proximityLimitMb = storage.optLong("proximityLimitMb", DEFAULT_PROXIMITY_LIMIT_MB);
                    tripsLimitMb = storage.optLong("tripsLimitMb", DEFAULT_TRIPS_LIMIT_MB);
                    
                    // Load storage type selection. The configured values are
                    // kept as-is so the watchdog still tries to mount what
                    // the user originally asked for; the runtime "active"
                    // type is reported via getActive*StorageType() and
                    // resolves to INTERNAL when the configured external
                    // volume isn't currently available.
                    recordingsStorageType   = parseStorageType(storage.optString("recordingsStorageType", "INTERNAL"));
                    surveillanceStorageType = parseStorageType(storage.optString("surveillanceStorageType", "INTERNAL"));
                    tripsStorageType        = parseStorageType(storage.optString("tripsStorageType", "INTERNAL"));

                    // Clamp to dynamic max — limit may have been persisted against
                    // a different volume (e.g., user swapped a 128GB SD for a 32GB
                    // one), so re-check against the current effective ceiling.
                    // BUT: if the configured external volume is simply UNMOUNTED at
                    // boot (not swapped), don't shrink the user's persisted limit down
                    // to internal's ceiling — that would silently lose their large
                    // external limit with no re-expansion on remount. Preserve the
                    // persisted intent (clamp only to the absolute sentinel); runtime
                    // enforcement is already bounded to internal capacity by
                    // effectiveReapLimitBytes during the fallback. See loadTimeCeilingMb.
                    recordingsLimitMb   = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(recordingsStorageType),   recordingsLimitMb));
                    surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(surveillanceStorageType), surveillanceLimitMb));
                    proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(recordingsStorageType), proximityLimitMb));  // proximity follows recordings (ACC-ON) volume
                    tripsLimitMb        = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(tripsStorageType),        tripsLimitMb));
                    
                    logInfo("Loaded storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType + 
                        "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType + 
                        "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
                }
            }
        } catch (Exception e) {
            logWarn("Could not load storage config: " + e.getMessage());
        }
    }

    /**
     * Save storage limits and storage type to config file.
     *
     * <p>Synchronized: the HTTP layer uses a 32-thread pool, so
     * concurrent setters (setRecordingsLimitMb, setSurveillanceStorageType,
     * etc.) can race the read-modify-write cycle below. Without this lock
     * two writers could each read the file, mutate disjoint fields in their
     * own copy, and the second writer's full-file write would clobber the
     * first writer's changes.
     */
    public synchronized void saveConfig() {
        try {
            // Route the storage-section persist through UnifiedConfigManager so
            // there is ONE lock domain + ONE atomic writer over
            // /data/local/tmp/overdrive_config.json. The previous body did its
            // own read-modify-write guarded only by synchronized(this) (the
            // StorageManager monitor) and wrote with a plain truncating
            // FileWriter — a disjoint mutex from UCM's cross-process FileLock on
            // overdrive_config.json.lock + tmp+rename. The two schemes did not
            // exclude each other, so a concurrent UCM updateSection (same file)
            // could lost-update the storage limit, torn-read a half-truncated
            // file, or — worst — a SIGKILL/ACC-cut between the FileWriter
            // truncate and flush could wipe the ENTIRE config (the v23→v24 wipe
            // vector UCM was hardened against). updateSection does
            // loadConfigFresh + FileLock + atomic tmp+rename + .bak self-heal,
            // and (in the daemon, UID 2000) writes locally. It also refreshes
            // the UCM cache, so the old explicit forceReload() is now redundant.
            // Stay synchronized so the in-memory fields are read consistently
            // while assembling the JSON.
            JSONObject storage = new JSONObject();
            storage.put("recordingsLimitMb", recordingsLimitMb);
            storage.put("surveillanceLimitMb", surveillanceLimitMb);
            storage.put("proximityLimitMb", proximityLimitMb);
            storage.put("tripsLimitMb", tripsLimitMb);
            storage.put("recordingsStorageType", recordingsStorageType.name());
            storage.put("surveillanceStorageType", surveillanceStorageType.name());
            storage.put("tripsStorageType", tripsStorageType.name());

            boolean ok = com.overdrive.app.config.UnifiedConfigManager.updateSection("storage", storage);
            if (!ok) {
                logError("Could not save storage config: UnifiedConfigManager.updateSection(\"storage\") failed");
                return;
            }

            logInfo("Saved storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType +
                "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType +
                "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
        } catch (Exception e) {
            logError("Could not save storage config: " + e.getMessage());
        }
    }
    
    // ==================== Directory Getters ====================
    
    public File getRecordingsDir() {
        return recordingsDir;
    }
    
    public File getSurveillanceDir() {
        return surveillanceDir;
    }

    /**
     * Resolve the surveillance directory LIVE, on demand, bypassing the
     * {@code surveillanceActive} freeze that gates the volatile
     * {@link #surveillanceDir} field.
     *
     * <p><b>Why this exists.</b> {@code surveillanceDir} has exactly one writer
     * — {@link #updateActiveDirectories()} — and that writer SKIPS the
     * surveillance branch whenever {@code surveillanceActive.get()==true}. So
     * once an arm session latches active, the field is frozen at whatever it
     * resolved to before {@code enable()}. If the configured external volume
     * mount lands AFTER the bounded {@link #waitForConfiguredExternalMount}
     * window (the 4-15s slow-mount tail), the field stays pinned to the
     * internal fallback for the whole session and the per-trigger refresh in
     * {@link com.overdrive.app.surveillance.SurveillanceEngineGpu} reads the
     * same stale internal path — defeating the first-event-on-SD intent.
     *
     * <p>This mirrors the watchdog's recordings push (audit R5), which already
     * resolves the canonical SD path DIRECTLY rather than reading the frozen
     * field, precisely because the field doesn't move while active.
     *
     * <p><b>Safe to call per trigger.</b> The engine resolves this ONCE while
     * {@code recording==false} (Site A / Site B guards), so an in-flight clip
     * can never be split across volumes. {@link #resolveActive} returns the
     * internal fallback whenever the configured external is genuinely
     * unavailable, so the live resolve is no worse than the frozen field when
     * the card is absent. Held under {@code surveillanceCleanupLock} for a
     * consistent {availability, dir} read against {@code updateActiveDirectories}.
     *
     * @return the live surveillance directory for the configured volume, or the
     *         internal fallback when the external volume isn't available
     */
    public File getLiveSurveillanceDir() {
        synchronized (surveillanceCleanupLock) {
            ResolvedDir r = resolveActive(surveillanceStorageType,
                internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir, "surveillance");
            return r.dir;
        }
    }

    public File getProximityDir() {
        return proximityDir;
    }

    /**
     * Live proximity dir — the proximity analogue of {@link #getLiveSurveillanceDir()}.
     * Resolves the configured RECORDINGS (ACC-ON) volume DIRECTLY rather than reading
     * the frozen {@code proximityDir} field, which {@link #updateActiveDirectories()}
     * stops updating once a surveillance arm session latches active (the proximity
     * branch there is gated on {@code !surveillanceActive.get()}). Without this, a
     * proximity recording that starts during an armed session — or right after a
     * boot/intermittent SD mount lands — would read the stale internal fallback even
     * though the configured SD is now mounted. Mirrors the recordings-volume resolve
     * (no ENOSPC redirect: proximity has no per-segment fallback of its own) and falls
     * back to internal when the external is genuinely unavailable. Held under
     * {@code proximityCleanupLock} for a consistent {availability, dir} read.
     */
    public File getLiveProximityDir() {
        synchronized (proximityCleanupLock) {
            ResolvedDir r = resolveActive(recordingsStorageType,
                internalProximityDir, sdCardProximityDir, usbProximityDir, "proximity");
            return r.dir;
        }
    }

    public File getTripsDir() {
        return tripsDir;
    }
    
    public String getRecordingsPath() {
        return recordingsDir.getAbsolutePath();
    }
    
    public String getSurveillancePath() {
        return surveillanceDir.getAbsolutePath();
    }
    
    public String getProximityPath() {
        return proximityDir.getAbsolutePath();
    }
    
    public String getTripsPath() {
        return tripsDir.getAbsolutePath();
    }
    
    /**
     * Fix permissions on all storage directories and files.
     * Call this from daemon startup to ensure UI app can read recordings.
     * Note: chmod doesn't work on FUSE - rely on MediaScanner broadcast for cross-UID visibility.
     */
    public void fixAllPermissions() {
        // Fix directory permissions synchronously (fast, no I/O contention)
        File baseDir = new File(INTERNAL_BASE_DIR);
        if (baseDir.exists()) {
            baseDir.setReadable(true, false);
            baseDir.setExecutable(true, false);
        }
        fixDirectoryPermissions(recordingsDir);
        fixDirectoryPermissions(surveillanceDir);
        fixDirectoryPermissions(proximityDir);
        fixDirectoryPermissions(tripsDir);
        
        // Make all existing files world-readable (chmod 666).
        // Required for: (1) UI app (different UID) to read files directly,
        // (2) FUSE layer on BYD Android to allow File.listFiles() to see them.
        // This is fast (no shell processes) — just Java File.setReadable() calls.
        makeFilesReadable(recordingsDir);
        makeFilesReadable(surveillanceDir);
        makeFilesReadable(proximityDir);
        makeFilesReadable(tripsDir);
        
        // SOTA: Incremental MediaScanner broadcast — only broadcast files created
        // since the last successful broadcast. Uses a marker file to track the
        // timestamp of the last full scan. On first run (no marker), broadcasts
        // everything once, then subsequent startups only broadcast new files.
        //
        // Additionally, broadcasts are throttled (50ms between each shell exec)
        // to avoid saturating the I/O bus during camera pipeline startup.
        // The old approach spawned 2 shell processes per file × hundreds of files
        // = hundreds of concurrent process forks competing with the GPU pipeline.
        new Thread(() -> {
            long lastScanTimestamp = loadLastBroadcastTimestamp();
            long scanStartTime = System.currentTimeMillis();
            
            int count = 0;
            count += broadcastFilesSince(recordingsDir, lastScanTimestamp);
            count += broadcastFilesSince(surveillanceDir, lastScanTimestamp);
            count += broadcastFilesSince(proximityDir, lastScanTimestamp);
            
            saveLastBroadcastTimestamp(scanStartTime);
            
            if (count > 0) {
                logInfo("MediaScanner broadcast complete: " + count + " new files indexed");
            } else {
                logDebug("MediaScanner: no new files to broadcast since last scan");
            }
        }, "MediaScannerBroadcast").start();
    }
    
    /** Marker file that stores the epoch millis of the last successful broadcast scan. */
    private static final String BROADCAST_MARKER_FILE = "/data/local/tmp/overdrive_last_mediascan";
    
    /** Throttle delay between individual file broadcasts (ms). */
    private static final long BROADCAST_THROTTLE_MS = 50;
    
    /**
     * Load the timestamp of the last successful MediaScanner broadcast.
     * Returns 0 if no marker exists (first run — will broadcast everything).
     */
    private long loadLastBroadcastTimestamp() {
        try {
            File marker = new File(BROADCAST_MARKER_FILE);
            if (marker.exists()) {
                String content = new java.util.Scanner(marker).useDelimiter("\\A").next().trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            logDebug("No broadcast marker found, will do full scan");
        }
        return 0;
    }
    
    /**
     * Save the timestamp of the current broadcast scan.
     */
    private void saveLastBroadcastTimestamp(long timestamp) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(BROADCAST_MARKER_FILE);
            fw.write(String.valueOf(timestamp));
            fw.close();
        } catch (Exception e) {
            logWarn("Failed to save broadcast marker: " + e.getMessage());
        }
    }
    
    /**
     * Broadcast only files modified after the given timestamp.
     * Throttled to avoid I/O contention with the GPU pipeline.
     * @return number of files broadcast
     */
    private int broadcastFilesSince(File dir, long sinceTimestamp) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) return 0;
        
        int count = 0;
        for (File f : files) {
            if (f.lastModified() > sinceTimestamp) {
                broadcastFile(f);
                count++;
                
                // Throttle: yield between broadcasts to avoid saturating I/O
                if (count % 5 == 0) {
                    try { Thread.sleep(BROADCAST_THROTTLE_MS); } catch (InterruptedException e) { break; }
                }
            }
        }
        return count;
    }
    
    // ==================== Limit Getters/Setters ====================
    
    public long getRecordingsLimitMb() {
        return recordingsLimitMb;
    }

    public long getSurveillanceLimitMb() {
        return surveillanceLimitMb;
    }

    public long getProximityLimitMb() {
        return proximityLimitMb;
    }

    public long getTripsLimitMb() {
        return tripsLimitMb;
    }

    /**
     * The retention limit (MB) ACTUALLY being enforced for a category right now —
     * the configured limit clamped to what the currently-active volume can hold.
     * Equals the configured limit in normal operation; during a fallback to internal
     * it's {@code min(configured, internal capacity)}. The UI shows this (alongside
     * the configured value) so a user whose external volume is full/absent sees the
     * honest "saving to internal: enforcing N MB" number rather than a limit the
     * fallback volume can never reach. Categories: recordings/surveillance/proximity/trips.
     */
    public long getEffectiveLimitMb(String category) {
        return effectiveReapLimitMb(activeTypeForCategory(category), configuredLimitMbForCategory(category));
    }
    
    // Runtime limit setters clamp with loadTimeCeilingMb (NOT the strict
    // getEffectiveMaxLimitMb) so a save while the configured external volume is
    // merely UNMOUNTED does not silently shrink the persisted limit to internal's
    // ceiling with no upward re-clamp on remount. When the volume IS mounted,
    // loadTimeCeilingMb returns the real ceiling so a genuine smaller-volume swap
    // is still clamped. Runtime over-fill during a fallback stays bounded by
    // effectiveReapLimitBytes. Mirrors loadConfig's load-time clamp (see ~line 2116).
    // Take configChangeLock around the read-modify-write so a concurrent
    // reclampLimitsToMountedCeilings() (now fired on several remount-success paths)
    // or a peer setter can't interleave and lose this update on the static long
    // *LimitMb fields. Same monitor the storage-type setters and reclamp use.
    public void setRecordingsLimitMb(long limitMb) {
        synchronized (configChangeLock) {
            recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(recordingsStorageType), limitMb));
            saveConfig();
        }
    }

    public void setSurveillanceLimitMb(long limitMb) {
        synchronized (configChangeLock) {
            surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(surveillanceStorageType), limitMb));
            saveConfig();
        }
    }

    public void setProximityLimitMb(long limitMb) {
        synchronized (configChangeLock) {
            proximityLimitMb = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(recordingsStorageType), limitMb));  // proximity follows recordings (ACC-ON) volume
            saveConfig();
        }
    }

    public void setTripsLimitMb(long limitMb) {
        synchronized (configChangeLock) {
            tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(loadTimeCeilingMb(tripsStorageType), limitMb));
            saveConfig();
        }
    }
    
    // ==================== Storage Type Getters/Setters ====================

    /** The user's persisted choice. May not match where files are actually
     *  written if the external volume isn't currently available — see
     *  {@link #getActiveRecordingsStorageType}. */
    public StorageType getRecordingsStorageType() {
        return recordingsStorageType;
    }

    public StorageType getSurveillanceStorageType() {
        return surveillanceStorageType;
    }

    public StorageType getTripsStorageType() {
        return tripsStorageType;
    }

    /**
     * The storage type that recordings are actually being written to right
     * now. Returns INTERNAL if the configured external volume isn't
     * currently mounted. UI should show this (with the configured value as
     * a secondary "you wanted X, currently using Y" hint when they differ).
     */
    public StorageType getActiveRecordingsStorageType() {
        // A mounted-but-FULL external volume keeps its availability flag true
        // (ENOSPC surfaces on write, not on canWrite of a 0-free dir), so the
        // mount-based normalizeStorageType alone would still report the
        // external type while the ENOSPC fallback quietly writes to internal.
        // Reflect that redirect so the UI's "saving to internal" banner fires
        // for the disk-full case too, not only the unmounted case.
        if (recordingsEnospcFallbackActive
                && recordingsStorageType != StorageType.INTERNAL) {
            return StorageType.INTERNAL;
        }
        return normalizeStorageType(recordingsStorageType);
    }

    public StorageType getActiveSurveillanceStorageType() {
        return normalizeStorageType(surveillanceStorageType);
    }

    public StorageType getActiveTripsStorageType() {
        return normalizeStorageType(tripsStorageType);
    }
    
    /**
     * Set recordings storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setRecordingsStorageType(StorageType type) {
        // FIX (audit R8, LOW): serialize concurrent setters so a fast
        // double-fire from the web UI / HTTP pool can't interleave
        // recordingsStorageType writes, pendingOutputDirOverride pushes,
        // and saveConfig persistence. configChangeLock is shared with the
        // peer setSurveillance/setTrips methods so cross-category races
        // are also serialized (saveConfig is a single shared file write).
        //
        // LOCK ORDER (mountLock → configChangeLock): this method holds
        // configChangeLock AND calls the mount path (ensureExternalAvailable →
        // ensureVolumeMounted, which takes mountLock). Meanwhile the mount path's
        // reclampLimitsToMountedCeilings() takes configChangeLock while holding
        // mountLock. Acquiring mountLock FIRST here makes the global order one-way
        // (mountLock → configChangeLock) so those two can't deadlock. Both locks
        // are reentrant, so the nested ensureVolumeMounted re-acquires mountLock
        // harmlessly.
        synchronized (mountLock) {
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "recordings")) return false;

        recordingsStorageType = type;
        // Clear any stale ENOSPC full-fallback signal: the user just (re)chose
        // a target, so the next segment re-evaluates free space fresh. Without
        // this, switching away from a full volume could leave the banner stuck.
        recordingsEnospcFallbackActive = false;
        // Re-clamp the persisted limit against the new volume's effective max
        // (e.g., user switches from SD to USB, USB is smaller). Limit may
        // need to shrink before updateActiveDirectories runs cleanup.
        //
        // FIX (audit, MEDIUM): only DOWN-clamp when the live StatFs read is
        // genuinely positive. The web POST echoes the storage TYPE on every
        // limit save, so this runs constantly — and getEffectiveMaxLimitMb()
        // collapses to internal's ~8GB ceiling whenever the live volume read
        // returns 0/throws (FUSE/vold hiccup) even though sdCardAvailable still
        // reports the card mounted. Clamping on that transient zero would
        // permanently shrink a valid persisted external limit (reclamp only
        // GROWS back, never restores the original intent). A genuine smaller
        // SWAP still reads live>0 and clamps correctly; runtime over-fill on a
        // transient zero is already bounded by effectiveReapLimitBytes.
        long liveTotal = liveVolumeTotalBytes(type);
        if (liveTotal > 0) {
            // Reuse the single read that just cleared the transient-zero guard —
            // do NOT call getEffectiveMaxLimitMb(type), which performs its OWN
            // fresh StatFs read that can race to 0 (read#1 positive, read#2 zero
            // on a flaky FUSE mount) and collapse to internal's ~8GB ceiling,
            // irreversibly shrinking the persisted external limit + mass-reaping
            // the card. volumeCeilingMb(liveTotal) is exactly the transform
            // getEffectiveMaxLimitMb applies for a positive total (mirrors the
            // converged reclampCeilingMb path).
            recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(volumeCeilingMb(liveTotal), recordingsLimitMb));
        }
        updateActiveDirectories();
        saveConfig();
        logInfo("Recordings storage type set to: " + type);

        // Re-arm the volume watchdog so a transition INTERNAL → SD/USB during
        // ACC=ON brings up the per-minute remount loop, and SD/USB → INTERNAL
        // tears it down when no longer needed. startSdCardWatchdog is
        // idempotent and self-gating (returns early when no category is on
        // an external volume), so we can call it unconditionally here.
        try {
            startSdCardWatchdog();
            logInfo("setRecordingsStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        // Push the new recordings dir into the live pano recorder so an
        // in-progress CONTINUOUS / DRIVE_MODE session lands future segments
        // on the freshly selected volume. Without this, the recorder keeps
        // writing to the dir captured at startRecording time until the next
        // mode toggle or hot remount cycle (watchdog at line ~4229 only
        // covers unmount/remount, not user-initiated type swaps).
        //
        // Bypass getRecordingsDir() / the volatile field: when a recording is
        // active, updateActiveDirectories SKIPS the recordingsDir swap (per
        // the active-recording guard at line ~1658), so the field still
        // points at the OLD volume. Resolve the new dir directly from the
        // freshly-set type so the user's choice actually reaches the
        // recorder override even mid-session.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.getRecorder() != null) {
                ResolvedDir r = resolveActive(type,
                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir, "recordings");
                java.io.File newRecordingsDir = r.dir;
                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                logInfo("setRecordingsStorageType: pano recorder output dir updated to "
                    + (newRecordingsDir != null ? newRecordingsDir.getAbsolutePath() : "null")
                    + " (resolved=" + r.resolved
                    + "; in-flight segment keeps prior path; future segments use new dir)");
                // FIX (audit R4): if a CONTINUOUS / DRIVE_MODE session is
                // mid-flight, the encoder's segmentBasePath was latched at
                // startRecording time and segment rotations stay on the OLD
                // volume until the next mode toggle. Force a stopRecording on
                // the wrapper so the listener bridge clears recordingActive,
                // then RMM's wedge ticker (or the next activateMode call)
                // performs a fresh startRecording that picks up the new
                // pendingOutputDirOverride. We lose ≈ one segment-second but
                // the user's storage-type choice takes effect within seconds
                // instead of waiting for the next ACC cycle.
                try {
                    if (pipeline.getRecorder().isRecording()) {
                        logWarn("setRecordingsStorageType: recording active, "
                            + "forcing pipeline.stopRecording so override applies on next start");
                        // FIX (audit R7, HIGH): use pipeline.stopRecording() not
                        // recorder.stopRecording(). The wrapper-only stop leaves
                        // pipeline.currentMode=NORMAL_RECORDING + recordingMode=true,
                        // which makes RMM.runActivateGuarded short-circuit on
                        // pipeline.isNormalRecordingMode() and never re-issue
                        // pipeline.startRecording() — pendingOutputDirOverride
                        // is never consumed, recording is silently lost until
                        // next ACC cycle. pipeline.stopRecording() additionally
                        // clears recordingMode, currentMode=IDLE, and
                        // pendingRecordingDir/Prefix so the next activateMode
                        // is allowed and consumes the new override.
                        pipeline.stopRecording();
                        // FIX (audit R5, MEDIUM): kick RMM to re-evaluate mode
                        // immediately so the next startRecording fires within
                        // a tick instead of waiting for the wedge ticker / next
                        // ACC cycle. resyncFromHardware reads currentMode +
                        // accIsOn fresh and re-issues activateMode → which
                        // consumes the just-pushed setOutputDir override.
                        // Caller is HTTP / settings-save thread; resyncFromHardware
                        // dispatches to its own executor and returns quickly.
                        try {
                            com.overdrive.app.recording.RecordingModeManager rmm =
                                com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                            if (rmm != null) {
                                rmm.resyncFromHardware("storage-type-switch-recordings");
                                logInfo("setRecordingsStorageType: kicked RMM "
                                    + "resyncFromHardware to re-arm recording on new volume");
                            }
                        } catch (Throwable rt) {
                            logWarn("setRecordingsStorageType: RMM resync kick threw: "
                                + rt.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    logWarn("setRecordingsStorageType: stopRecording for re-segment threw: "
                        + t.getMessage());
                }
            } else {
                logInfo("setRecordingsStorageType: no live pano recorder, recorder dir push skipped");
            }
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: could not push recorder dir: " + t.getMessage());
        }

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }

        // FIX (audit R8, MEDIUM): re-arm RecordingsIndex against the new
        // volume's recordings dir + reconcile so events.html / native
        // fragment lists stop showing the OLD volume's clips. Without
        // this, FileObservers continue watching the old dir set and new
        // cam_*.mp4 segments on the new volume don't fire events — UI
        // shows stale data until the 1-hour periodic reconcile. Mirrors
        // the existing notifyRecordingsIndexOfStorageChange calls in the
        // SD/USB watchdog success branches (lines 4454, 4677).
        try {
            notifyRecordingsIndexOfStorageChange("set-recordings-storage-type");
            logInfo("setRecordingsStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
        } // end synchronized(mountLock) — lock order mountLock → configChangeLock
    }

    /**
     * Set surveillance storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setSurveillanceStorageType(StorageType type) {
        // FIX (audit R8, LOW): peer setter — share configChangeLock with
        // setRecordingsStorageType / setTripsStorageType.
        // LOCK ORDER (mountLock → configChangeLock): see setRecordingsStorageType.
        synchronized (mountLock) {
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "surveillance")) return false;

        surveillanceStorageType = type;
        // FIX (audit, MEDIUM): only DOWN-clamp on a genuinely positive live
        // StatFs read — see setRecordingsStorageType for the full rationale (a
        // transient 0/exception read must not permanently shrink the persisted
        // external limit).
        long liveTotal = liveVolumeTotalBytes(type);
        if (liveTotal > 0) {
            // Reuse the single guard-clearing read; getEffectiveMaxLimitMb(type)
            // would do a second StatFs read that can race to 0 and collapse to
            // internal's ~8GB ceiling (see setRecordingsStorageType).
            long ceilingMb = volumeCeilingMb(liveTotal);
            surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(ceilingMb, surveillanceLimitMb));
            proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(ceilingMb, proximityLimitMb));
        }
        updateActiveDirectories();
        saveConfig();
        logInfo("Surveillance storage type set to: " + type);

        // Re-arm the volume watchdog: a transition INTERNAL → SD/USB during
        // ACC=ON must bring up the remount loop so a transient unmount during
        // the drive (kernel hiccup, FUSE bridge reset) is recovered before
        // the next ACC cycle. Idempotent + self-gating.
        try {
            startSdCardWatchdog();
            logInfo("setSurveillanceStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setSurveillanceStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }

        // FIX (audit R8, MEDIUM): symmetric with setRecordingsStorageType.
        // Recordings live alongside surveillance events on the same volume
        // family; a surveillance-side type swap may shift the recordings
        // dir indirectly (when both share a volume) or alter availability.
        // Re-arm the index defensively. Best-effort.
        try {
            notifyRecordingsIndexOfStorageChange("set-surveillance-storage-type");
            logInfo("setSurveillanceStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setSurveillanceStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
        } // end synchronized(mountLock) — lock order mountLock → configChangeLock
    }

    /**
     * Set trips storage type (INTERNAL or SD_CARD).
     * Does NOT call autoEnableCdrCleanup() — trip files are small and don't compete with BYD dashcam space.
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setTripsStorageType(StorageType type) {
        // FIX (audit R8, LOW): peer setter — share configChangeLock with
        // setRecordingsStorageType / setSurveillanceStorageType.
        // LOCK ORDER (mountLock → configChangeLock): see setRecordingsStorageType.
        synchronized (mountLock) {
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "trips")) return false;

        tripsStorageType = type;
        // FIX (audit, MEDIUM): only DOWN-clamp on a genuinely positive live
        // StatFs read — see setRecordingsStorageType for the full rationale.
        long liveTotal = liveVolumeTotalBytes(type);
        if (liveTotal > 0) {
            // Reuse the single guard-clearing read; getEffectiveMaxLimitMb(type)
            // would do a second StatFs read that can race to 0 and collapse to
            // internal's ~8GB ceiling (see setRecordingsStorageType).
            tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(volumeCeilingMb(liveTotal), tripsLimitMb));
        }
        updateActiveDirectories();
        saveConfig();
        logInfo("Trips storage type set to: " + type);

        // Re-arm the volume watchdog so a trips-only external choice still
        // gets remount coverage during ACC=ON. Idempotent + self-gating.
        try {
            startSdCardWatchdog();
            logInfo("setTripsStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setTripsStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        // FIX (audit R8, MEDIUM): symmetric with setRecordingsStorageType.
        // Trips don't live in the recordings tree directly, but a type
        // swap that changes mount availability can affect peer dirs;
        // refresh the index so derived availability views stay coherent.
        try {
            notifyRecordingsIndexOfStorageChange("set-trips-storage-type");
            logInfo("setTripsStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setTripsStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
        } // end synchronized(mountLock) — lock order mountLock → configChangeLock
    }

    /**
     * Helper: ensure the requested external volume is available before we
     * accept a storage-type change. INTERNAL is always OK. SD/USB get a
     * mount attempt; refusing the change is preferable to silently writing
     * to internal under a label that says "SD card".
     */
    private boolean ensureExternalAvailable(StorageType type, String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable) return true;
            logInfo("SD card not available, attempting to mount for " + label + "...");
            if (!ensureSdCardMounted(true)) {
                logWarn("Cannot set " + label + " to SD card - mount failed");
                return false;
            }
            return true;
        }
        if (type == StorageType.USB) {
            if (usbAvailable) return true;
            logInfo("USB not available, attempting to mount for " + label + "...");
            if (!ensureUsbMounted(true)) {
                logWarn("Cannot set " + label + " to USB - mount failed");
                return false;
            }
            return true;
        }
        return true;  // INTERNAL
    }
    
    /**
     * SOTA: Auto-enable CDR (BYD dashcam) cleanup when Overdrive uses SD card.
     * This ensures Overdrive always has space by cleaning up old dashcam files.
     */
    private void autoEnableCdrCleanup() {
        try {
            ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
            if (!cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                // Calculate recommended reserved space based on our limits
                long totalNeeded = 0;
                if (recordingsStorageType == StorageType.SD_CARD) {
                    totalNeeded += recordingsLimitMb;
                }
                if (surveillanceStorageType == StorageType.SD_CARD) {
                    totalNeeded += surveillanceLimitMb;
                }
                // Add 20% buffer
                long reservedMb = Math.max(2048, (long)(totalNeeded * 1.2));
                
                cleaner.setReservedSpaceMb(reservedMb);
                cleaner.setEnabled(true);
                logInfo("Auto-enabled CDR cleanup with " + reservedMb + "MB reserved for Overdrive");
            }
        } catch (Exception e) {
            logWarn("Could not auto-enable CDR cleanup: " + e.getMessage());
        }
    }
    
    // ==================== Volume Info ====================

    public boolean isSdCardAvailable() {
        return sdCardAvailable;
    }

    public String getSdCardPath() {
        return sdCardPath;
    }

    public boolean isUsbAvailable() {
        return usbAvailable;
    }

    public String getUsbPath() {
        return usbPath;
    }

    /**
     * Re-detect both SD and USB. Public alias mostly used by polling
     * watchdogs / API handlers that want to refresh state on demand.
     */
    public void refreshUsb() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }

    /**
     * ACC-ON active remount of the configured external volume(s).
     *
     * <p>BYD unmounts the USB-bridged SD (SCSI major 8) across the ACC-off→on
     * wake — the reader's power rail follows AP/display wake, so the USB bus
     * re-enumerates and the card drops for the first several seconds of the
     * drive (see project_sd_drop_safezone_no_pipeline). The ACC-OFF handler
     * force-remounts (CameraDaemon ~3708), but historically the ACC-ON handler
     * did NOT — it left recovery entirely to the free-running 15s VolumeWatchdog,
     * which needs TWO consecutive failed ticks (the two-strikes anti-flap rule)
     * before it even attempts a remount, and that remount then races the
     * documented 4-15s slow-mount tail of a re-powering bridged reader. Net: a
     * 30s+ window where the card reads "not detected" with the car ON — exactly
     * the user-reported symptom. This mirrors the ACC-OFF force-remount so the
     * card is actively driven back at wake instead of waited on.
     *
     * <p>Only touches a volume class that a storage type is actually configured
     * for (raw configured type — the getters return the un-normalized field, so
     * a card that is currently unmounted still reports SD_CARD here). No-ops
     * fully when everything is on INTERNAL. force=true so a stale
     * mounted-but-dead handle is torn down and re-established.
     *
     * <p>Resets the two-strikes watchdog counters + one-shot internal-fallback
     * latches so a successful remount here is treated as a clean session (a
     * later drop re-runs the watchdog's recovery once more) and the watchdog
     * doesn't immediately re-log a stale failure streak. Blocking (up to the
     * per-class mount ceiling) — callers on a latency-sensitive path (the ACC-ON
     * handler) MUST invoke this off-thread; the ACC-OFF handler already blocks on
     * the equivalent call so the cost is well-characterised.
     *
     * @return true if every configured external class is mounted afterwards.
     */
    public boolean remountExternalOnAccOn() {
        boolean anyOnSd  = surveillanceStorageType == StorageType.SD_CARD ||
                           recordingsStorageType   == StorageType.SD_CARD ||
                           tripsStorageType        == StorageType.SD_CARD;
        boolean anyOnUsb = surveillanceStorageType == StorageType.USB ||
                           recordingsStorageType   == StorageType.USB ||
                           tripsStorageType        == StorageType.USB;
        if (!anyOnSd && !anyOnUsb) {
            return true;  // all-internal: nothing bridged to re-power
        }

        boolean ok = true;
        if (anyOnSd) {
            // NB: intentionally NO isSdCardPhysicallyPresent() fast-out here. On
            // the SCSI/USB-bridged readers this fix targets, that probe is
            // documented UNRELIABLE (it never surfaces under /sys/class/mmc_host,
            // and sys.byd.isSDExist can read 'false' for a seated bridged card —
            // see isSdCardPhysicallyPresent's own caveats and
            // project_sd_drop_safezone_no_pipeline). Gating on it would skip the
            // remount for exactly the hardware that needs it — self-defeating. The
            // cost of an unconditional attempt when the slot is genuinely empty is
            // ~4s of bounded shell forks, once per real ACC-ON edge (CAS-guarded,
            // not per heartbeat), matching the equally-ungated ACC-OFF branch.
            logInfo("ACC ON: force-remounting SD card (bridged reader may have dropped across wake)...");
            boolean sdOk = ensureSdCardMounted(true);
            if (sdOk) {
                // Clean session: clear the anti-flap streak + one-shot recovery
                // latch so the watchdog doesn't re-log a stale failure count and
                // a later drop re-runs its internal-fallback recovery once more.
                sdWatchdogConsecutiveFailures = 0;
                sdFallbackRecoveryDone = false;
                logInfo("ACC ON: SD card remounted (" + sdCardPath + ")");
            } else {
                logWarn("ACC ON: SD remount failed — VolumeWatchdog will keep retrying, using internal until it lands");
            }
            ok &= sdOk;
        }
        if (anyOnUsb) {
            logInfo("ACC ON: force-remounting USB drive (bridged reader may have dropped across wake)...");
            boolean usbOk = ensureUsbMounted(true);
            if (usbOk) {
                usbWatchdogConsecutiveFailures = 0;
                usbFallbackRecoveryDone = false;
                logInfo("ACC ON: USB drive remounted (" + usbPath + ")");
            } else {
                logWarn("ACC ON: USB remount failed — VolumeWatchdog will keep retrying, using internal until it lands");
            }
            ok &= usbOk;
        }

        // Re-arm dirs + recordings index so a freshly-remounted card is visible
        // to the HTTP server / events UI immediately, not on the next periodic
        // reconcile. ensureVolumeMounted already calls initXxxDirectories +
        // updateActiveDirectories on success, but call updateActiveDirectories
        // once more defensively (idempotent) to cover the failure branch's
        // fall-back-to-internal path, then poke the index.
        updateActiveDirectories();
        notifyRecordingsIndexOfStorageChange("ACC-ON remount");
        return ok;
    }
    
    // ==================== All Storage Locations (for scanning) ====================
    
    /**
     * Get ALL directories that may contain recordings of a given type.
     * Returns the active (configured) directory first, then any alternate locations
     * where files may exist (e.g., internal when SD card is active, or vice versa).
     * 
     * This is the single source of truth for multi-location scanning.
     * Callers should iterate all returned directories to find all files.
     */
    public List<File> getAllRecordingsDirs() {
        return RecordingDirectoryRegistry.recordings(
            recordingsDir, internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir);
    }

    public List<File> getAllSurveillanceDirs() {
        return RecordingDirectoryRegistry.surveillance(
            surveillanceDir, internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir);
    }

    public List<File> getAllProximityDirs() {
        return RecordingDirectoryRegistry.proximity(
            proximityDir, internalProximityDir, sdCardProximityDir, usbProximityDir);
    }

    public List<File> getAllTripsDirs() {
        return getAllDirsForType(tripsDir, internalTripsDir, sdCardTripsDir, usbTripsDir);
    }

    /** Active-first rank used only to resolve legacy filename-only requests. */
    public int getRecordingRootRank(File file) {
        if (file == null) return 100;
        String path = file.getAbsolutePath();
        List<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<File> category : java.util.Arrays.asList(
                getAllRecordingsDirs(), getAllSurveillanceDirs(), getAllProximityDirs())) {
            for (File root : category) {
                if (root != null && seen.add(root.getAbsolutePath())) roots.add(root);
            }
        }
        for (int rank = 0; rank < roots.size(); rank++) {
            String root = roots.get(rank).getAbsolutePath();
            if (path.equals(root) || path.startsWith(root + File.separator)) return rank;
        }
        return 100;
    }

    /**
     * Bounded directory listing for callers (e.g. trip recovery) that walk a
     * possibly-FUSE-bridged SD/USB trips dir. Runs the in-process
     * {@code File.listFiles()} on a SEPARATE worker thread with a hard deadline:
     * a flaky FUSE mount mid-write does NOT return null from listFiles() — it
     * BLOCKS inside native readdir/stat indefinitely — so we must bound the call
     * itself, not just handle a null return. On timeout (or null), we abandon
     * the (possibly-stuck, daemon) worker and fall back to {@link
     * #listFilesViaShell}, which has its own 4s `ls` deadline + destroyForcibly.
     * Returns null only if both paths fail. This is what keeps a hung card from
     * stranding the recovery worker + latching its in-progress flags forever.
     */
    public File[] listTripFilesBounded(File dir) {
        if (dir == null) return null;
        final File d = dir;
        final java.util.concurrent.atomic.AtomicReference<File[]> result =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        Thread worker = new Thread(() -> {
            try { result.set(d.listFiles()); } catch (Throwable ignored) {}
        }, "listTripFiles-" + dir.getName());
        worker.setDaemon(true);   // never block daemon shutdown if it's wedged
        worker.start();
        try {
            worker.join(4_000);   // hard deadline, mirrors listFilesViaShell
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        File[] direct = result.get();
        if (direct != null) return direct;
        // Either listFiles is still blocked in the (abandoned) worker, or it
        // returned null — fall back to the deadline-bounded shell listing.
        return listFilesViaShell(dir);
    }

    /**
    * Same canonical roots as {@link #getAllSurveillanceDirs()} et al.
     *
     * Used by both the size accounting and the cleanup reaper so the two
     * agree about what "the surveillance pool" actually is — otherwise
     * the UI can show 800 MB used against a 500 MB limit while cleanup
     * (which only saw the active dir) thinks everything is fine.
     *
     * The flat legacy base is shared across categories, so callers use
     * {@link #namePrefixForCategory} to touch only matching files.
     */
    private List<File> getReapableDirs(String category) {
        switch (category) {
            case "recordings":
                return new ArrayList<>(getAllRecordingsDirs());
            case "surveillance":
                return new ArrayList<>(getAllSurveillanceDirs());
            case "proximity":
                return new ArrayList<>(getAllProximityDirs());
            case "trips":
                return new ArrayList<>(getAllTripsDirs());
            default:
                return new ArrayList<>();
        }
    }

    /**
     * Filename prefix that identifies media belonging to {@code category}.
     * When non-null, callers that scan multi-category directories (the
     * flat legacy base) should restrict to filenames starting with this
     * prefix so they don't reap a sibling category's files. Returns null
     * for categories whose dirs are all category-dedicated.
     */
    private static String namePrefixForCategory(String category) {
        switch (category) {
            case "recordings":  return "cam";        // cam_*, cam2_*, …
            case "surveillance": return "event_";
            case "proximity":   return "proximity_";
            default: return null;
        }
    }

    /**
     * Auxiliary filename prefixes that don't share a stem with the anchor but
     * still belong to {@code category} and must be reaped to keep the limit
     * honest. Used by the orphan-sidecar pass — these files are matched as
     * sidecars whose anchor stem is parsed by stripping the auxiliary prefix.
     *
     * <p>Surveillance: per-actor thumbnails are written as
     * {@code thumb_event_<base>_a<id>_<rel>.jpg} (SurveillanceEngineGpu:4823).
     * They don't start with {@code event_}, so the standard prefix gate
     * filters them out — without this auxiliary entry they accumulate
     * untracked.
     */
    private static String[] auxiliaryPrefixesForCategory(String category) {
        switch (category) {
            // OEM Dashcam and manual replay clips share the recordings
            // directory with cam_*. The primary prefix gate is "cam", so both
            // dedicated prefixes need to be first-class anchors for size
            // accounting and loop-recording cleanup.
            case "recordings":   return new String[]{"dvr_", "replay_"};
            // Per-actor JPGs are named `thumb_<anchorStem>_a<id>_<rel>.jpg`,
            // where anchorStem already includes the `event_` prefix
            // (SurveillanceEngineGpu:4815-4824 derives tmpBase from the
            // segment basename minus ".mp4"). The aux prefix must be just
            // `thumb_` — anything longer would double-count `event_` and
            // miss every actual file.
            case "surveillance": return new String[]{"thumb_"};
            default:             return new String[]{};
        }
    }

    /**
     * Returns true if {@code name} matches the category's primary prefix or
     * any auxiliary prefix. Centralizes the prefix gate so size accounting,
     * the anchor reaper, and the orphan-sidecar pass agree on which files
     * belong to a category.
     */
    private static boolean nameMatchesCategoryPrefix(String name, String primaryPrefix,
                                                     String[] auxPrefixes) {
        if (primaryPrefix != null && name.startsWith(primaryPrefix)) return true;
        for (String aux : auxPrefixes) {
            if (name.startsWith(aux)) return true;
        }
        return false;
    }

    /**
     * Sum primary + sidecar files across the given dirs, deduplicating by
     * filename (so a clip mirrored on internal + SD-card isn't counted twice).
     * Must match what {@link #ensureSpace} actually frees, otherwise the UI
     * reports usage past the limit while cleanup believes it's fine.
     *
     * @param category   Category key — recordings/surveillance/proximity/trips.
     * @param namePrefix If non-null, only files whose name starts with
     *                   this prefix are summed. Used when the dir set
     *                   includes the flat legacy base shared across
     *                   categories.
     */
    private long getDirectoriesTotalSize(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        String[] sidecarExts = sidecarExtensionsForCategory(category);
        String[] partialExts = partialExtensionsForCategory(category);
        String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        long size = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) {
                files = listFilesViaShell(dir);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null
                        && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                boolean isPrimary = name.endsWith(primaryExt);
                boolean isSidecar = false;
                boolean isPartial = false;
                if (!isPrimary) {
                    for (String ext : sidecarExts) {
                        if (name.endsWith(ext)) { isSidecar = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar) {
                    for (String ext : partialExts) {
                        if (name.endsWith(ext)) { isPartial = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar && !isPartial) continue;
                if (!seen.add(name)) continue;
                size += f.length();
            }
        }
        return size;
    }
    
    /**
     * Build a deduplicated list of directories: active first, then alternates.
     * Skips null entries and directories that match the active one.
     */
    private List<File> getAllDirsForType(File activeDir, File internalDir, File sdCardDir, File usbDir) {
        List<File> dirs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (activeDir != null) {
            dirs.add(activeDir);
            seen.add(activeDir.getAbsolutePath());
        }
        if (internalDir != null && !seen.contains(internalDir.getAbsolutePath())) {
            dirs.add(internalDir);
            seen.add(internalDir.getAbsolutePath());
        }
        if (sdCardDir != null && !seen.contains(sdCardDir.getAbsolutePath())) {
            dirs.add(sdCardDir);
            seen.add(sdCardDir.getAbsolutePath());
        }
        if (usbDir != null && !seen.contains(usbDir.getAbsolutePath())) {
            dirs.add(usbDir);
            seen.add(usbDir.getAbsolutePath());
        }
        return dirs;
    }
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                logDebug("SD card path not accessible: " + sdCardPath);
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get SD card free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on SD card in bytes.
     */
    public long getSdCardTotalSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get available space on internal storage in bytes.
     */
    public long getInternalFreeSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get internal free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on internal storage in bytes.
     */
    public long getInternalTotalSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Refresh SD card AND USB detection and update directories.
     * Call this when either volume may have been inserted/removed.
     * (Kept under the historical name for callers that still reference it.)
     */
    public void refreshSdCard() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        reclampLimitsToMountedCeilings();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }

    /**
     * Re-clamp persisted limits DOWN to the now-mounted external volume's real ceiling.
     * Shrink-or-hold only (see {@link #reclampTargetMb}): when the persisted limit exceeds
     * the mounted card's ceiling (a larger card was swapped for a smaller one, or an
     * oversized value was persisted while the card was absent — loadConfig/the setters
     * clamp through loadTimeCeilingMb, which returns the 100GB sentinel for an unmounted
     * external), this shrinks it to the real ceiling so the slider max and the enforced
     * limit match the actual capacity and the card can't fill to physical-full. A limit at
     * or below the card ceiling (the user's deliberate choice) is left untouched — reclamp
     * never grows a configured limit (that would discard the user's retention cap on every
     * routine USB-rail/FUSE remount). Persists only if something changed. Cheap; safe to
     * call on every remount.
     */
    private void reclampLimitsToMountedCeilings() {
        try {
            // Serialize against the limit setters and storage-type setters: all four
            // *LimitMb fields are static long and were read-modify-written here with no
            // lock, while a concurrent set*LimitMb / set*StorageType on the HTTP pool
            // wrote the same fields. A card-insert remount (this is now called on the
            // extra remount-success paths, exactly the window a user adjusts limits)
            // could interleave with a limit save → lost update durably corrupts the
            // PERSISTED + UI-displayed limit. configChangeLock is the shared monitor the
            // setters already hold, so taking it here makes the whole RMW atomic w.r.t.
            // them. (saveConfig's synchronized(this) only serializes JSON assembly, not
            // these field mutations.)
            synchronized (configChangeLock) {
                long r = reclampTargetMb(recordingsStorageType,   recordingsLimitMb);
                long s = reclampTargetMb(surveillanceStorageType, surveillanceLimitMb);
                long p = reclampTargetMb(recordingsStorageType, proximityLimitMb);  // proximity follows recordings (ACC-ON) volume
                long t = reclampTargetMb(tripsStorageType,        tripsLimitMb);
                // reclampTargetMb preserves a large external limit while UNMOUNTED (no
                // shrink against an absent card) AND while a still-mounted card returns a
                // transient StatFs 0 (FUSE/vold mid-remount hiccup). Only a genuine
                // mounted card with a positive live total down-clamps an oversized value
                // (smaller-swap) to the card's real ceiling; a configured limit at/below
                // the ceiling is held, never grown.
                boolean changed = (r != recordingsLimitMb) || (s != surveillanceLimitMb)
                               || (p != proximityLimitMb) || (t != tripsLimitMb);
                recordingsLimitMb = r;
                surveillanceLimitMb = s;
                proximityLimitMb = p;
                tripsLimitMb = t;
                if (changed) saveConfig();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Get available space on USB drive in bytes.
     */
    public long getUsbFreeSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get USB free space: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Get total space on USB drive in bytes.
     */
    public long getUsbTotalSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Effective max-limit ceiling in MB for the requested storage type.
     *
     * Pulled live from StatFs each call so card swaps and capacity changes
     * reflect immediately in the slider — but capped per-category at
     * PER_CATEGORY_SHARE of the volume so the four categories sharing a
     * single FS can't overcommit it 4x.
     *
     * When the requested SD/USB volume is unmounted, falls back to the
     * INTERNAL ceiling rather than the absurd MAX_LIMIT_MB_FALLBACK
     * sentinel — the runtime fall-back path lands writes on internal,
     * so capping at internal's true total stops the user from persisting
     * a 100GB limit against a missing 32GB stick. INTERNAL itself
     * returning <=0 (StatFs literally unreadable) keeps the sentinel.
     *
     * <p>The ceiling is the FULL usable volume (total − {@link #VOLUME_HEADROOM_MB}),
     * NOT a per-category fraction — the per-category /N share was removed (see the
     * note where PER_CATEGORY_SHARE used to be declared).
     */
    /**
     * Live total-bytes of the volume for {@code type}, straight from StatFs.
     * Returns 0 if the read throws or the volume is currently unreadable.
     *
     * <p>Used by the storage-type setters to distinguish a real smaller-volume
     * SWAP (live &gt; 0 → down-clamp the persisted limit) from a transient
     * FUSE/vold hiccup (live == 0 → leave the limit intact). Unlike
     * {@link #getEffectiveMaxLimitMb}, this never substitutes internal's ceiling
     * for an unreadable external — a 0 here means "don't shrink", not "8GB".
     */
    private long liveVolumeTotalBytes(StorageType type) {
        switch (type) {
            case SD_CARD: return getSdCardTotalSpace();
            case USB:     return getUsbTotalSpace();
            case INTERNAL:
            default:      return getInternalTotalSpace();
        }
    }

    public long getEffectiveMaxLimitMb(StorageType type) {
        long totalBytes;
        switch (type) {
            case SD_CARD: totalBytes = sdCardAvailable ? getSdCardTotalSpace() : 0; break;
            case USB:     totalBytes = usbAvailable    ? getUsbTotalSpace()    : 0; break;
            case INTERNAL:
            default:      totalBytes = getInternalTotalSpace(); break;
        }
        if (totalBytes <= 0) {
            if (type == StorageType.INTERNAL) return MAX_LIMIT_MB_FALLBACK;
            // Unmounted SD/USB: clamp to internal volume's FULL ceiling so a save
            // while the volume is missing can't persist a value larger than
            // the fallback target can ever hold.
            long internalBytes = getInternalTotalSpace();
            if (internalBytes <= 0) return MAX_LIMIT_MB_FALLBACK;
            return volumeCeilingMb(internalBytes);
        }
        return volumeCeilingMb(totalBytes);
    }

    /**
     * Full usable ceiling (MB) for a volume of {@code totalBytes}: the whole
     * volume minus {@link #VOLUME_HEADROOM_MB} of ENOSPC headroom, floored at
     * {@link #MIN_LIMIT_MB}. No per-category division — one category may be
     * configured up to (nearly) the whole disk.
     */
    private long volumeCeilingMb(long totalBytes) {
        long usableMb = (totalBytes / 1024L / 1024L) - VOLUME_HEADROOM_MB;
        return usableMb <= MIN_LIMIT_MB ? MIN_LIMIT_MB : usableMb;
    }

    /**
     * Load-time clamp ceiling (MB) for a configured storage type. Same as
     * {@link #getEffectiveMaxLimitMb} EXCEPT that an UNMOUNTED external volume yields
     * the absolute {@link #MAX_LIMIT_MB_FALLBACK} sentinel rather than internal's
     * ceiling — so a persisted external limit isn't silently shrunk to internal size
     * just because the card happens to be absent at boot (there is no upward
     * re-clamp on remount). When the external IS mounted we use the real ceiling, so
     * a genuine card SWAP to a smaller volume is still clamped correctly. The save
     * setters ({@link #setRecordingsLimitMb} etc.) keep using the strict
     * {@link #getEffectiveMaxLimitMb} so a user can't PERSIST an oversized value, and
     * runtime enforcement during a fallback stays bounded by
     * {@link #effectiveReapLimitBytes} regardless of this persisted number.
     */
    private long loadTimeCeilingMb(StorageType type) {
        if (type == StorageType.INTERNAL) return getEffectiveMaxLimitMb(StorageType.INTERNAL);
        boolean mounted = (type == StorageType.SD_CARD) ? sdCardAvailable
                        : (type == StorageType.USB) ? usbAvailable : false;
        return mounted ? getEffectiveMaxLimitMb(type) : MAX_LIMIT_MB_FALLBACK;
    }

    /**
     * Re-clamp ceiling (MB) for {@link #reclampLimitsToMountedCeilings}, honoring the
     * SAME transient-zero guard the storage-type setters use ({@code liveTotal > 0}).
     *
     * <p>{@link #loadTimeCeilingMb} is unsafe to down-clamp against here: for an
     * external type it gates on {@code sdCardAvailable}/{@code usbAvailable} and then
     * calls {@link #getEffectiveMaxLimitMb}, which collapses to internal's ~8GB
     * ceiling whenever the live StatFs read returns 0 (a FUSE/vold hiccup mid-remount)
     * even though the card is still mounted. reclamp persists its result via
     * saveConfig() and only ever GROWS back via min(realCeiling, current) — so a
     * single transient zero would permanently shrink a valid external limit to ~8GB
     * and then drive the reaper to delete the SD/USB archive down to that size.
     * Treating a 0 live total as "don't shrink" (return the current limit, an effective
     * no-op through the caller's min) leaves the persisted limit intact until a real
     * positive read confirms the actual capacity. A genuine smaller card SWAP still
     * reads live &gt; 0 and clamps correctly; runtime over-fill on a transient zero
     * stays bounded by {@link #effectiveReapLimitBytes}/{@link #activeExternalReapLimitBytes}.
     */
    private long reclampCeilingMb(StorageType type, long currentLimitMb) {
        if (type == StorageType.INTERNAL) return getEffectiveMaxLimitMb(StorageType.INTERNAL);
        boolean mounted = (type == StorageType.SD_CARD) ? sdCardAvailable
                        : (type == StorageType.USB) ? usbAvailable : false;
        if (!mounted) return MAX_LIMIT_MB_FALLBACK; // unmounted: preserve persisted limit (no shrink)
        long liveTotal = liveVolumeTotalBytes(type);
        if (liveTotal <= 0) return currentLimitMb; // mounted but transient StatFs 0: don't shrink
        // Reuse the read that just passed the guard — do NOT call getEffectiveMaxLimitMb(type),
        // which performs its OWN fresh StatFs read that can race to 0 on a flaky FUSE mount
        // (read#1 positive, read#2 zero) and collapse to internal's ~8GB ceiling, persisting an
        // irreversible shrink + mass-reap of the external archive (the round-3 data-loss bug).
        // volumeCeilingMb(liveTotal) is exactly the transform getEffectiveMaxLimitMb applies for
        // a positive total, computed from the single value that cleared the transient-zero guard.
        return volumeCeilingMb(liveTotal);
    }

    /**
     * Resolve the new persisted limit (MB) for {@link #reclampLimitsToMountedCeilings}.
     *
     * <p>SHRINK-OR-HOLD ONLY: {@code min(reclampCeilingMb, current)}, floored at MIN. This
     * preserves a deliberately-small user limit (it's at/below the card ceiling, so {@code
     * min} keeps it) and still down-clamps an OVERSIZED persisted limit to the card's real
     * ceiling when a smaller card is mounted, while never widening against the unmounted
     * sentinel or a transient StatFs 0. The same {@code min(ceiling, current)} contract is
     * used by loadConfig, the limit setters, and the storage-type setters — reclamp must
     * not be the lone path that diverges.
     *
     * <p>The "Up" recovery once enumerated in the class doc (grow a limit an older build
     * stale-shrank to internal's ~8GB ceiling back toward the card's capacity) was REMOVED:
     * a bare persisted {@code *LimitMb} carries no provenance, so the adopt-the-ceiling
     * implementation could not tell a sentinel-corrupted value from an intentional small
     * user choice and grew EVERY external limit below the card ceiling up to physical-full
     * on each routine USB-rail/FUSE remount, destroying + persisting over the user's choice
     * within one ACC cycle. Honoring the configured retention cap outweighs auto-recovering
     * the rare stale-shrunk case; a user who wants more capacity can simply raise the limit.
     */
    private long reclampTargetMb(StorageType type, long currentLimitMb) {
        long ceiling = reclampCeilingMb(type, currentLimitMb);
        return Math.max(MIN_LIMIT_MB, Math.min(ceiling, currentLimitMb));
    }

    /**
     * Runtime EFFECTIVE retention limit (MB) for a category whose configured
     * limit is {@code configuredMb}, given the volume writes are ACTUALLY landing
     * on right now ({@code activeType}). Returns {@code min(configuredMb,
     * fullCeiling(activeType))}, floored at {@link #MIN_LIMIT_MB}.
     *
     * <p>This is the heart of the fallback fix. The persisted/displayed
     * {@code *LimitMb} is the user's configured number on their chosen (usually
     * external) volume and is NEVER rewritten. But when an external volume is
     * full/absent and recording falls back to internal, enforcing that
     * external-sized number (e.g. 100 GB) as the reaper target on an 8 GB internal
     * means the reaper never fires and internal fills to physical-full — the exact
     * "recording lost on fallback" bug. Clamping the ENFORCED target to what the
     * active volume can physically hold bounds internal correctly while changing
     * nothing the user sees. When the external returns, {@code activeType} flips
     * back and the clamp evaporates — the configured number is honored again on the
     * volume that can hold it.
     */
    private long effectiveReapLimitMb(StorageType activeType, long configuredMb) {
        long ceiling = getEffectiveMaxLimitMb(activeType);
        return Math.max(MIN_LIMIT_MB, Math.min(configuredMb, ceiling));
    }

    /** Bytes form of {@link #effectiveReapLimitMb}. */
    private long effectiveReapLimitBytes(StorageType activeType, long configuredMb) {
        return effectiveReapLimitMb(activeType, configuredMb) * 1024L * 1024L;
    }

    /**
     * Runtime EFFECTIVE retention limit (bytes) for a category whose writes are
     * ACTUALLY landing on a mounted EXTERNAL volume ({@code activeType} is SD/USB,
     * active == configured, no internal fallback). Returns {@code min(configuredMb,
     * liveCeiling(activeType))} in bytes, where the ceiling is the volume's LIVE
     * total minus {@link #VOLUME_HEADROOM_MB}.
     *
     * <p>WHY NOT {@link #effectiveReapLimitBytes}: that path routes through
     * {@link #getEffectiveMaxLimitMb}, which substitutes INTERNAL's (smaller)
     * ceiling whenever the external StatFs transiently reads 0 — on a FUSE hiccup
     * that would shrink the SD target to ~8 GB and over-reap a large card. Here we
     * read {@link #liveVolumeTotalBytes} and treat a 0/unreadable result as
     * "don't shrink" (return the configured limit untouched this pass), so a
     * momentary vold/FUSE stall never triggers a spurious mass-delete on the card.
     * The clamp only bites once StatFs reports the card's real (smaller) capacity —
     * exactly when an oversized persisted limit would otherwise let the card
     * fill to physical-full.
     */
    private long activeExternalReapLimitBytes(StorageType activeType, long configuredMb) {
        long configuredBytes = configuredMb * 1024L * 1024L;
        long totalBytes = liveVolumeTotalBytes(activeType);
        if (totalBytes <= 0) {
            // Transient StatFs zero — don't shrink the target on a hiccup.
            return configuredBytes;
        }
        long ceilingBytes = volumeCeilingMb(totalBytes) * 1024L * 1024L;
        long minBytes = MIN_LIMIT_MB * 1024L * 1024L;
        return Math.max(minBytes, Math.min(configuredBytes, ceilingBytes));
    }

    // ── Per-category resolvers for the active-volume retention scoping ──────────
    // These let the single chokepoint in ensureSpace() resolve, for any category,
    // (a) the volume writes are CURRENTLY landing on, (b) the user's CONFIGURED
    // volume, and (c) the configured MB limit — without each of the ~16 reap call
    // sites needing to know about fallback. Proximity rides SURVEILLANCE's storage
    // type (see the proximity clamp sites) but keeps its own proximityLimitMb.

    private StorageType activeTypeForCategory(String category) {
        switch (category) {
            case "recordings":   return getActiveRecordingsStorageType();   // ENOSPC-aware (mounted-but-full → INTERNAL)
            case "surveillance": return getActiveSurveillanceStorageType();
            case "proximity":    return normalizeStorageType(recordingsStorageType);  // proximity follows recordings (ACC-ON) volume; plain mount-based (no ENOSPC redirect of its own)
            case "trips":        return getActiveTripsStorageType();
            default:             return StorageType.INTERNAL;
        }
    }

    private StorageType configuredTypeForCategory(String category) {
        switch (category) {
            case "recordings":   return recordingsStorageType;
            case "surveillance": return surveillanceStorageType;
            case "proximity":    return recordingsStorageType;  // proximity follows recordings (ACC-ON) volume
            case "trips":        return tripsStorageType;
            default:             return StorageType.INTERNAL;
        }
    }

    private long configuredLimitMbForCategory(String category) {
        switch (category) {
            case "recordings":   return recordingsLimitMb;
            case "surveillance": return surveillanceLimitMb;
            case "proximity":    return proximityLimitMb;
            case "trips":        return tripsLimitMb;
            default:             return DEFAULT_RECORDINGS_LIMIT_MB;
        }
    }

    /** True when a category's writes have fallen back from a configured EXTERNAL
     *  volume onto internal (external full/absent) — the condition under which the
     *  reaper, the size accounting, and the encoder-busy defer gate must all switch
     *  to the internal-scoped pool + effective (clamped) limit instead of the
     *  combined cross-volume pool + raw external limit. Single source of truth so
     *  the gate decisions can't disagree with {@link #ensureSpace}'s own rescoping. */
    private boolean isFallbackActiveForCategory(String category) {
        return activeTypeForCategory(category) == StorageType.INTERNAL
            && configuredTypeForCategory(category) != StorageType.INTERNAL;
    }

    /** The byte size to compare against the limit when deciding whether a reap is
     *  needed — the INTERNAL-scoped pool during a fallback (so the full external
     *  archive doesn't inflate the number and make a near-full internal look fine),
     *  else the normal combined-pool size. Matches the pool {@link #ensureSpace}
     *  actually reaps.
     *
     *  <p>Normal mode delegates to each category's regular size source so we don't
     *  regress any caching — notably {@link #getTripsSize()} prefers a sub-ms H2
     *  {@code SUM(size_bytes)} query over a multi-minute FUSE walk. The internal-scoped
     *  fallback branch must use the raw {@link #getDirectoriesTotalSize} walk because
     *  the cached whole-pool sums span all volumes and wouldn't match internal-only. */
    private long scopedSizeForCategory(String category) {
        if (isFallbackActiveForCategory(category)) {
            return getDirectoriesTotalSize(category,
                internalScopedDirs(getReapableDirs(category)), namePrefixForCategory(category));
        }
        // UNCACHED on purpose: this feeds the reaper / limit enforcement, which
        // must see the filesystem as it is right now. The public
        // getRecordingsSize()/getSurveillanceSize()/getProximitySize() getters
        // carry a short TTL cache for the UI reporting path; routing the reaper
        // through those would let it delete against a stale measurement (over-
        // reaping just-freed space, or under-reaping and overflowing to ENOSPC).
        // trips keeps getTripsSize() because that path is DB-backed (an exact
        // SUM over rows the reaper itself maintains), not a stale FS snapshot.
        switch (category) {
            case "recordings":   return getRecordingsSizeUncached();
            case "surveillance": return getSurveillanceSizeUncached();
            case "proximity":    return getProximitySizeUncached();
            case "trips":        return getTripsSize();   // DB-cached — avoids the FUSE walk
            default:             return getDirectoriesTotalSize(category,
                getReapableDirs(category), namePrefixForCategory(category));
        }
    }

    /** The byte limit to enforce for a category right now — the effective (configured
     *  clamped to internal capacity) limit during a fallback, else the raw configured
     *  limit. Matches the limit {@link #ensureSpace} actually applies. */
    private long scopedLimitBytesForCategory(String category) {
        long configuredMb = configuredLimitMbForCategory(category);
        if (isFallbackActiveForCategory(category)) {
            return effectiveReapLimitBytes(StorageType.INTERNAL, configuredMb);
        }
        // Active EXTERNAL (SD/USB), no fallback: mirror ensureSpace's active-external
        // clamp so the periodic 90% gate / HARD escape / deferred-drain measure against
        // the SAME card-capacity-bounded limit the reaper enforces. Without this the
        // gate would compare the real card pool against an oversized persisted limit
        // (up to the 100 GB sentinel), never trip, and the reaper would never be
        // invoked even though it would now reap correctly once called.
        StorageType activeType = activeTypeForCategory(category);
        if (activeType != StorageType.INTERNAL) {
            return activeExternalReapLimitBytes(activeType, configuredMb);
        }
        // Natively-INTERNAL (active == configured == INTERNAL): mirror ensureSpace's
        // internal-capacity clamp (see the `else if (activeType == INTERNAL)` branch)
        // so the gate trips at the SAME bound the reaper enforces. A limit persisted
        // while internal's StatFs transiently read 0 can be up to the 100 GB sentinel;
        // returning that raw value here means the 90% gate / HARD escape / onSaved
        // checks compare the real internal pool against ~100 GB, never trip, and the
        // reaper is never invoked even though it would now clamp+converge once called —
        // internal fills to physical-full with the configured limit silently un-enforced.
        return Math.min(configuredMb * 1024L * 1024L,
            effectiveReapLimitBytes(StorageType.INTERNAL, configuredMb));
    }

    /** The INTERNAL active dir for a category — the dir writes land on during a
     *  fallback. Used as the {@code activeDir} when the reaper is scoped to internal
     *  so its mkdir / log references the volume actually being written. */
    private File internalDirForCategory(String category) {
        switch (category) {
            case "recordings":   return internalRecordingsDir;
            case "surveillance": return internalSurveillanceDir;
            case "proximity":    return internalProximityDir;
            case "trips":        return internalTripsDir;
            default:             return internalRecordingsDir;
        }
    }

    /**
     * Filter a reapable-dir list down to the INTERNAL volume. Used by the
     * active-volume scoping in {@link #ensureSpace} during a fallback so the reaper
     * bounds ONLY internal and NEVER auto-deletes the configured external volume's
     * archive while it's merely full/absent.
     *
     * <p>ALLOWLIST by {@link #INTERNAL_BASE_DIR}'s volume root (/storage/emulated/0)
     * rather than denylisting {@code sdCardPath}/{@code usbPath}: those external-path
     * volatiles can momentarily read null/stale during a card pull/swap (they're
     * assigned in a separate call from the {@code sdCardRecordingsDir} fields this
     * filters), and a null external path would let an external dir slip through a
     * denylist and into the reap set — risking deletion of the external archive.
     * Keeping ONLY internal-volume dirs is immune to that race. Legacy/flat-base
     * paths all live under /storage/emulated/0, so they correctly stay in the set.
     */
    private List<File> internalScopedDirs(List<File> all) {
        final String internalVolumeRoot = "/storage/emulated/0";
        List<File> out = new ArrayList<>(all.size());
        for (File d : all) {
            if (d == null) continue;
            if (d.getAbsolutePath().startsWith(internalVolumeRoot)) out.add(d);
        }
        return out;
    }

    /**
     * Backwards-compatible: returns the dynamic max for the given type.
     * Old callers that passed a {@link StorageType} keep working.
     */
    public long getMaxLimitMb(StorageType type) {
        return getEffectiveMaxLimitMb(type);
    }
    
    // ==================== Storage Stats ====================
    
    // ==================== CATEGORY SIZE/COUNT TTL CACHE ====================
    //
    // WHY (perf): getDirectoriesTotalSize / getFileCountAcross are UNCACHED
    // full directory walks — listFiles() plus a per-entry isFile() and
    // length() for every file in every reapable dir of the category. Every
    // recording carries sidecars (.mp4 + .json + .srt), so a 500-clip library
    // is ~1500 stat()s per walk. On the emulated/FUSE volumes these paths live
    // on (INTERNAL_BASE_DIR is /storage/emulated/0/Overdrive, and the daemon
    // runs as UID 2000), every one of those stats is an IPC round-trip through
    // the FUSE bridge.
    //
    // Two callers hit these on a timer:
    //   1. QualitySettingsApiHandler's storage card — the web UI polls it on a
    //      10s interval (recording.js + surveillance.js reloadConfig).
    //   2. The 30s periodic cleanup tick's over-limit snapshot.
    // That is a sustained metadata load on the same dentry/inode cache the
    // package manager uses to read APK/dex/oat pages during app launch, which
    // is why it degrades WHOLE-SYSTEM responsiveness rather than just the app.
    //
    // The cache is deliberately conservative, in three ways:
    //
    //   a) READ-ONLY CALLERS ONLY. The reaper must never act on a stale size —
    //      it would under- or over-delete. scopedSizeForCategory() therefore
    //      calls the *Uncached() variants below, preserving byte-exact reap
    //      behaviour. Only the reporting getters are cached.
    //   b) TTL ABOVE THE POLL CADENCE. This was 5s, chosen to sit BELOW the 10s
    //      UI poll so "each tick still gets a fresh walk" — which meant the
    //      steady-state poll missed 100% of the time and the cache only ever
    //      absorbed bursts. On a large library that is a full re-walk of every
    //      reapable dir 6×/min for as long as a settings page is open, which is
    //      the "settings page is slow with lots of recordings" report. It is now
    //      15s, so the poll hits; freshness comes from (c), not from the TTL.
    //   c) EXPLICIT INVALIDATION — this is what actually guarantees correctness.
    //      invalidateCategorySizeCache() is called on clip finalize (onFileSaved)
    //      and on reap, so a just-written or just-deleted clip is reflected on
    //      the very next poll regardless of TTL.
    //   d) ONE WALK PER REFRESH. A miss on either the size or the count fills
    //      BOTH caches from a single traversal (refreshCategoryStats), so the
    //      4-value storage response costs 2 walks instead of 4.
    //
    // LOCKING: a dedicated monitor, and it is NEVER held across the directory
    // walk. Deliberately NOT `synchronized(this)`:
    //
    //   - The StorageManager monitor is also taken by saveConfig() and by the
    //     trips size/count caches, and those DO hold it across a full FUSE walk
    //     (pre-existing). Joining that domain would mean invalidation from the
    //     clip-finalize path could block behind an unrelated multi-hundred-ms
    //     walk. That path is GpuSegmentFinalizer, which runs at
    //     THREAD_PRIORITY_BACKGROUND (nice +10), while the walk holder can be a
    //     request thread — i.e. a priority inversion stalling clip finalize.
    //   - Holding any lock across the walk also serialises concurrent readers of
    //     DIFFERENT categories behind each other for no benefit.
    //
    // So: take the lock only to read the cache, release it, walk unlocked, then
    // take it again to publish. Two threads racing the same cold category may
    // both walk once — harmless (idempotent, read-only) and strictly better than
    // holding a lock across FUSE I/O. Last writer wins; both values are equally
    // fresh.
    // TTL sits ABOVE the 10s UI poll cadence deliberately. It used to be 5s,
    // which guaranteed a MISS on every steady-state poll (10s > 5s) — the
    // cache only ever absorbed bursts, so a large library re-walked the whole
    // dir set 6×/min for as long as a settings page stayed open. That is the
    // reported "settings page is slow when there are lots of recordings".
    // 15s means the poll normally hits; correctness is preserved by the
    // explicit invalidation below (clip finalize + reap), which is what
    // actually guarantees freshness — not the TTL.
    private static final long CATEGORY_STAT_CACHE_MS = 15_000;
    private final Object categoryStatCacheLock = new Object();
    private final java.util.Map<String, long[]> cachedCategorySize =
            new java.util.HashMap<>();   // category -> {sizeBytes, atMs}
    private final java.util.Map<String, long[]> cachedCategoryCount =
            new java.util.HashMap<>();   // category -> {count, atMs}

    private long categorySizeCached(String category) {
        return categoryStatCached(category, cachedCategorySize, 0);
    }

    private int categoryCountCached(String category) {
        return (int) categoryStatCached(category, cachedCategoryCount, 1);
    }

    // ---------------- STALE-WHILE-REVALIDATE ----------------
    //
    // In-flight guard for the async refresh, so N concurrent readers (both
    // categories × size+count, plus several HTTP clients) collapse onto ONE
    // background walk per category instead of stacking threads on a FUSE mount.
    private final java.util.Set<String> refreshInFlight = new java.util.HashSet<>();

    /**
     * Read a category stat, NEVER blocking on a directory walk once we have any
     * previous value for it.
     *
     * <p>WHY: this feeds the /api/settings/storage reporting path only, which
     * both settings pages fetch on load and re-poll every 10s. A fresh walk is
     * O(files) FUSE stats — on a large library that is what made the settings
     * page take seconds to become usable, because the HTTP response (and with it
     * the storage slider, the SD/USB buttons and everything the page awaited
     * behind it) sat on the walk.
     *
     * <p>Semantics:
     * <ul>
     *   <li>Fresh value (within TTL) → return it.
     *   <li>Stale value → return the stale number IMMEDIATELY and kick a
     *       background refresh. The next poll (10s later, or the client's
     *       post-save refresh) shows the updated figure. The number is a usage
     *       readout that is already only as fresh as the last walk, so showing it
     *       one poll late is a far better trade than blocking the page.
     *   <li>NO value at all (first call after boot) → walk synchronously, since
     *       returning a fabricated 0 would render "0 B used" as if authoritative.
     * </ul>
     *
     * <p>Enforcement is unaffected: the reaper reads the *Uncached() variants
     * (see scopedSizeForCategory), so it never acts on a stale or absent figure.
     *
     * @param idx 0 for the size cache, 1 for the count cache — the index into
     *            refreshCategoryStats()'s {size, count} result.
     */
    private long categoryStatCached(String category,
                                    java.util.Map<String, long[]> cache, int idx) {
        // MONOTONIC clock for TTL age. These head units correct the RTC from
        // NTP/GPS after boot; with wall-clock timestamps a BACKWARD jump makes
        // (now - stamp) negative, which reads as "fresh" and can latch a stale
        // figure until wall-clock catches back up. elapsedRealtime never goes
        // backwards. markStale()'s 0 sentinel still works: elapsedRealtime is
        // always > 0 after boot, so 0 always reads as older than the TTL.
        long now = statClockMs();
        synchronized (categoryStatCacheLock) {
            long[] e = cache.get(category);
            if (e != null && e[0] >= 0) {
                if ((now - e[1]) < CATEGORY_STAT_CACHE_MS) {
                    return e[0];                    // fresh
                }
                // Stale: serve it now, refresh behind the response. add() returns
                // false when a refresh for this category is already running, which
                // is what collapses a burst of readers onto a single walk.
                if (refreshInFlight.add(category)) {
                    // Roll the in-flight marker back if the thread never starts.
                    // t.start() can throw OutOfMemoryError (pthread_create under
                    // thread exhaustion — this daemon spawns ad-hoc threads
                    // freely), and the compensating remove() lives in the thread
                    // body, so without this the category would stay "in flight"
                    // forever and NEVER refresh again: markStale only backdates
                    // the entry, so the cold path can't rescue it either and the
                    // figure freezes for the daemon's lifetime.
                    boolean started = false;
                    try {
                        kickCategoryStatRefresh(category);
                        started = true;
                    } catch (Throwable t) {
                        logWarn("Category stat refresh spawn failed for " + category
                                + ": " + t);
                    } finally {
                        if (!started) refreshInFlight.remove(category);
                    }
                }
                return e[0];
            }
        }
        // Cold: no prior value for this category — must walk inline rather than
        // fabricate a 0. Serialised on a per-category monitor so the ~4 readers
        // of a single /api/settings/storage response (and any concurrent client)
        // perform ONE walk and the rest wait for it, instead of each launching
        // its own walk over the same FUSE dirs. Deliberately NOT
        // categoryStatCacheLock — that must never be held across a walk.
        Object gate;
        synchronized (coldWalkGates) {
            gate = coldWalkGates.computeIfAbsent(category, k -> new Object());
        }
        synchronized (gate) {
            // Re-check: a walk that completed while we waited on the gate has
            // already published, so use it instead of walking again.
            synchronized (categoryStatCacheLock) {
                long[] e = cache.get(category);
                if (e != null && e[0] >= 0) return e[0];
            }
            return refreshCategoryStats(category)[idx];
        }
    }

    /** Per-category monitors serialising the cold (no-value-yet) inline walk. */
    private final java.util.Map<String, Object> coldWalkGates = new java.util.HashMap<>();

    /** Monotonic millisecond clock for the stat-cache TTL. Falls back to
     *  System.nanoTime (also monotonic) if the Android class isn't present, so
     *  this never depends on wall-clock corrections. */
    private static long statClockMs() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            return System.nanoTime() / 1_000_000L;
        }
    }

    /**
     * Walk {@code category} on a background thread and publish both caches.
     * Caller must hold {@link #categoryStatCacheLock} and have just inserted
     * {@code category} into {@link #refreshInFlight}.
     *
     * <p>Detached thread rather than a pooled executor to match the idiom used
     * by the storage POST path's cleanup kick; these are short-lived and bounded
     * to ONE per category at a time by {@link #refreshInFlight}.
     *
     * <p>Rate: a refresh is only ever started by a READ, and the only periodic
     * reader is the 10s UI poll, so the ceiling is one walk per category per
     * poll — the same cadence as before this change, except now it happens
     * BEHIND the response instead of inside it. There is no self-sustaining loop:
     * nothing here schedules the next refresh.
     */
    private void kickCategoryStatRefresh(String category) {
        Thread t = new Thread(() -> {
            try {
                refreshCategoryStats(category);
            } catch (Throwable thr) {
                logWarn("Async category stat refresh failed for " + category
                        + ": " + thr.getMessage());
            } finally {
                synchronized (categoryStatCacheLock) {
                    refreshInFlight.remove(category);
                }
            }
        }, "CategoryStatRefresh-" + category);
        t.setDaemon(true);
        // Below the request/encoder threads: this is a reporting refresh and must
        // never compete with clip writing for I/O.
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * ONE directory walk that produces both the byte total and the file count
     * for {@code category}, publishing both caches.
     *
     * <p>WHY: /api/settings/storage reads size AND count for two categories
     * (QualitySettingsApiHandler.sendStorageSettings), and the two accessors
     * used to walk independently — 4 full traversals of the same dirs per
     * request, each re-listing every directory and re-stat'ing every file.
     * Sharing one traversal halves the syscall load for free. Same collapse
     * ExternalStorageCleaner.refreshWalkIfStale already does for its four CDR
     * accessors.
     *
     * <p>The two figures use DIFFERENT prefix gates, exactly as the two
     * original methods did — do not "unify" them:
     * <ul>
     *   <li>size sums primary + sidecar + partial files under the
     *       aux-inclusive gate ({@code cam*} plus {@code dvr_}/{@code replay_}
     *       /{@code thumb_}), matching {@link #getDirectoriesTotalSize} and,
     *       critically, what the reaper actually frees.
     *   <li>count counts only primary-extension files under the STRICT
     *       {@code startsWith(namePrefix)} gate, matching
     *       {@link #getFileCountAcross}. So OEM {@code dvr_} and
     *       {@code replay_} clips contribute bytes but not to
     *       {@code recordingsCount} — preserving the number the UI has always
     *       shown.
     * </ul>
     * Dedupe is by filename across dirs so a clip mirrored on internal + SD
     * isn't double-counted; a shared {@code seen} set is safe because a
     * sidecar's name never collides with its {@code .mp4}.
     *
     * @return {sizeBytes, count}
     */
    private long[] refreshCategoryStats(String category) {
        // Sampled BEFORE the walk so a clip-finalize/reap landing mid-walk is
        // detected at publish time — see the publish block at the end.
        final long seqAtStart;
        synchronized (categoryStatCacheLock) {
            seqAtStart = statInvalidationSeq;
        }
        String primaryExt = primaryExtensionForCategory(category);
        String[] sidecarExts = sidecarExtensionsForCategory(category);
        String[] partialExts = partialExtensionsForCategory(category);
        String[] auxPrefixes = auxiliaryPrefixesForCategory(category);
        String namePrefix = namePrefixForCategory(category);

        long size = 0;
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : getReapableDirs(category)) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) {
                files = listFilesViaShell(dir);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null
                        && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                boolean isPrimary = name.endsWith(primaryExt);
                boolean isSidecar = false;
                boolean isPartial = false;
                if (!isPrimary) {
                    for (String ext : sidecarExts) {
                        if (name.endsWith(ext)) { isSidecar = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar) {
                    for (String ext : partialExts) {
                        if (name.endsWith(ext)) { isPartial = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar && !isPartial) continue;
                if (!seen.add(name)) continue;
                size += f.length();
                // STRICT prefix gate for the count — see the javadoc. The gate
                // above is aux-inclusive (needed for byte parity with the
                // reaper); getFileCountAcross used a bare startsWith, so
                // re-test here rather than reusing that result.
                if (isPrimary
                        && (namePrefix == null || name.startsWith(namePrefix))) {
                    count++;
                }
            }
        }

        long at = statClockMs();   // monotonic — see categoryStatCached
        synchronized (categoryStatCacheLock) {
            // Publish only if no invalidation landed while we were walking.
            // invalidateCategorySizeCache() bumps this counter; if it changed,
            // our figures already predate a clip-finalize or a reap, so we
            // publish the VALUE but leave the timestamp stale (0) so the next
            // read serves this best-effort number immediately AND schedules a
            // fresh walk. Previously the in-flight walk clobbered the
            // invalidation outright, latching a pre-mutation figure for a full
            // TTL — tolerable when the TTL was the freshness mechanism, but not
            // now that invalidation is.
            boolean superseded = (statInvalidationSeq != seqAtStart);
            long stamp = superseded ? 0L : at;
            cachedCategorySize.put(category, new long[]{size, stamp});
            cachedCategoryCount.put(category, new long[]{count, stamp});
        }
        return new long[]{size, count};
    }

    /**
     * Drop the cached size/count for a category (or all categories when
     * {@code category} is null) so the next reporting read re-walks. Called
     * whenever this process adds or removes a file in a reapable dir, so the
     * storage card never shows a figure that predates a just-finished clip or
     * a just-completed reap.
     *
     * <p>Cheap and safe to call redundantly — it only rewrites entry timestamps,
     * and it never blocks on I/O (the walk is done outside this lock), so it is
     * safe to call from the encoder's finalize path.
     *
     * <p>Marks entries STALE (backdates their timestamp) rather than REMOVING
     * them. That distinction matters now that the reporting getters are
     * stale-while-revalidate: a removed entry is the "cold" case, which is the
     * one path that still walks INLINE on the HTTP request thread. Since this is
     * called on every finalized clip (~2 min) and every reap, deleting entries
     * would drop the settings page back onto a blocking walk over and over. A
     * backdated entry instead serves the last known figure immediately and
     * triggers the background refresh — the user sees the updated number on the
     * next 10s poll (or the client's post-save refresh ~1s after Apply).
     *
     * <p>Note this cannot fully prevent a stale publish: a walk already in flight
     * when the invalidation lands will still publish its (now slightly stale)
     * result afterwards. Bounded by CATEGORY_STAT_CACHE_MS and only ever affects
     * the reported figure — never reap enforcement, which reads uncached.
     */
    public void invalidateCategorySizeCache(String category) {
        synchronized (categoryStatCacheLock) {
            statInvalidationSeq++;   // makes any in-flight walk publish as stale
            if (category == null) {
                markAllStale(cachedCategorySize);
                markAllStale(cachedCategoryCount);
            } else {
                markStale(cachedCategorySize.get(category));
                markStale(cachedCategoryCount.get(category));
            }
        }
    }

    /** Bumped by every invalidation; sampled by {@link #refreshCategoryStats}
     *  before it walks so a mutation landing mid-walk isn't clobbered by the
     *  walk's own publish. Guarded by {@link #categoryStatCacheLock}. */
    private long statInvalidationSeq = 0L;

    /** Backdate one cache entry past the TTL so the next read revalidates.
     *  Caller holds {@link #categoryStatCacheLock}. Null-safe (nothing cached
     *  yet → the next read takes the cold path and walks, as it should). */
    private static void markStale(long[] entry) {
        if (entry != null) entry[1] = 0L;
    }

    private static void markAllStale(java.util.Map<String, long[]> cache) {
        for (long[] e : cache.values()) markStale(e);
    }

    /**
     * Get current size of recordings across all locations (active dir, the
     * inactive internal/SD-card mirror, and legacy app-files paths).
     *
     * Must match the dirs the cleanup actually reaps — otherwise the UI can
     * report 800 MB used while the limit is 500 MB and cleanup never fires.
     *
     * <p>Reporting path: {@value #CATEGORY_STAT_CACHE_MS}ms TTL-cached. The
     * reaper uses {@link #getRecordingsSizeUncached()}.
     */
    public long getRecordingsSize() {
        return categorySizeCached("recordings");
    }

    /**
     * Get current size of surveillance across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy sentry_events path).
     */
    public long getSurveillanceSize() {
        return categorySizeCached("surveillance");
    }

    /**
     * Get current size of proximity across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy proximity_events path).
     */
    public long getProximitySize() {
        return categorySizeCached("proximity");
    }

    // Uncached variants — for the reap/enforcement path, which must measure the
    // filesystem as it is RIGHT NOW. Never route the periodic cleanup or
    // ensureSpace through the cached getters above.
    private long getRecordingsSizeUncached() {
        return getDirectoriesTotalSize("recordings", getReapableDirs("recordings"), namePrefixForCategory("recordings"));
    }

    private long getSurveillanceSizeUncached() {
        return getDirectoriesTotalSize("surveillance", getReapableDirs("surveillance"), namePrefixForCategory("surveillance"));
    }

    private long getProximitySizeUncached() {
        return getDirectoriesTotalSize("proximity", getReapableDirs("proximity"), namePrefixForCategory("proximity"));
    }

    /**
     * Get recordings file count across all locations (active + inactive
     * mirror + legacy). Matches the size accounting so per-file averages
     * line up with reported totals.
     */
    public int getRecordingsCount() {
        return categoryCountCached("recordings");
    }

    /**
     * Get surveillance events file count across all locations.
     */
    public int getSurveillanceCount() {
        return categoryCountCached("surveillance");
    }

    /**
     * Get proximity events file count across all locations.
     */
    public int getProximityCount() {
        return categoryCountCached("proximity");
    }

    private int getFileCountAcross(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null && !name.startsWith(namePrefix)) continue;
                if (seen.add(name)) {
                    total++;
                }
            }
        }
        return total;
    }
    
    /**
     * Get current size of trips directory in bytes.
     *
     * <p>Prefers the DB-backed aggregate ({@code size_bytes + sidecar_size_bytes}
     * column sum) once the one-shot backfill has populated every legacy row.
     * On full-storage / FUSE-mounted SD cards the legacy filesystem walk took
     * 10-20 minutes — the storage card on trips.html would block until that
     * completed. The DB query is sub-millisecond.
     *
     * <p>While backfill is still running (or if the trip analytics manager
     * isn't initialised yet), falls back to the legacy walk wrapped in a 30s
     * in-memory cache so concurrent storage-card requests during the
     * backfill window don't pile up. Once {@code isBackfillComplete()} flips
     * to true, the DB path takes over and the cache path is unreachable.
     */
    public long getTripsSize() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripDatabase db = tam.getDatabase();
                if (db != null && db.isBackfillComplete()) {
                    return getTripsSizeFromDbCached(db);
                }
            }
        } catch (Throwable t) {
            // Fall through to direct walk
        }
        // Fallback: legacy walk + 30s in-memory cache so concurrent requests
        // during the backfill window don't pile up. Once backfill completes,
        // the DB path takes over and this is unreachable.
        return getTripsSizeWithCache();
    }

    private long cachedTripsSize = -1;
    private long cachedTripsSizeAt = 0;
    private static final long TRIPS_SIZE_CACHE_MS = 30_000;

    // Short TTL cache for the DB-backed SUM(size_bytes+sidecar_size_bytes)
    // aggregate. The query itself is sub-ms but it serializes on
    // TripDatabase's monitor; without this cache, repeated ensureTripsSpace
    // calls under tripsCleanupLock would each pay the round-trip while
    // holding the lock, deferring peer cleanup. 5s is short enough that
    // storage bookkeeping stays accurate but long enough to coalesce bursts.
    private long cachedTripsDbSize = -1;
    private long cachedTripsDbSizeAt = 0;
    private static final long TRIPS_DB_SIZE_CACHE_MS = 5_000;

    private synchronized long getTripsSizeFromDbCached(
            com.overdrive.app.trips.TripDatabase db) {
        long now = System.currentTimeMillis();
        if (cachedTripsDbSize >= 0 && (now - cachedTripsDbSizeAt) < TRIPS_DB_SIZE_CACHE_MS) {
            return cachedTripsDbSize;
        }
        long size = db.getTotalSizeBytes();
        cachedTripsDbSize = size;
        cachedTripsDbSizeAt = now;
        return size;
    }

    private synchronized long getTripsSizeWithCache() {
        long now = System.currentTimeMillis();
        if (cachedTripsSize >= 0 && (now - cachedTripsSizeAt) < TRIPS_SIZE_CACHE_MS) {
            return cachedTripsSize;
        }
        long size = getDirectoriesTotalSize("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
        cachedTripsSize = size;
        cachedTripsSizeAt = now;
        return size;
    }

    /**
     * Get trips file count.
     *
     * <p>Same DB-vs-walk pattern as {@link #getTripsSize()}: prefer
     * {@code TripDatabase.getTripCount()} once backfill is complete (a
     * one-row aggregate against the indexed trips table), fall back to the
     * filesystem walk wrapped in a 30s in-memory cache while backfill is
     * still running.
     */
    public int getTripsCount() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripDatabase db = tam.getDatabase();
                if (db != null && db.isBackfillComplete()) {
                    return db.getTripCount();
                }
            }
        } catch (Throwable t) {
            // Fall through to direct walk
        }
        return getTripsCountWithCache();
    }

    private int cachedTripsCount = -1;
    private long cachedTripsCountAt = 0;

    private synchronized int getTripsCountWithCache() {
        long now = System.currentTimeMillis();
        if (cachedTripsCount >= 0 && (now - cachedTripsCountAt) < TRIPS_SIZE_CACHE_MS) {
            return cachedTripsCount;
        }
        int count = getFileCountAcross("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
        cachedTripsCount = count;
        cachedTripsCountAt = now;
        return count;
    }

    /**
     * SOTA: List files via shell command when direct access fails.
     * This handles the case where UI app owns the directory but daemon needs to list files.
     * Returns every file in the directory regardless of extension.
     */
    static final class FileListResult {
        final File[] files;
        final boolean complete;

        FileListResult(File[] files, boolean complete) {
            this.files = files;
            this.complete = complete;
        }
    }

    private File[] listFilesViaShell(File dir) {
        return listFilesViaShellWithStatus(dir).files;
    }

    private FileListResult listFilesViaShellWithStatus(File dir) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"ls", dir.getAbsolutePath()});
            final Process proc = p;
            final java.util.List<File> files =
                java.util.Collections.synchronizedList(new java.util.ArrayList<File>());

            // Run the stdout drain on a daemon thread so we can bound the WHOLE
            // read, not just the post-EOF wait. A bare readLine() loop blocks
            // indefinitely if `ls` hangs mid-write on a bad FUSE-bridged volume
            // (a real fault on these head-units) — the previous waitForBounded
            // here only fired AFTER stdout EOF, so a mid-write stat hang was
            // still unbounded. We give the drain a hard deadline and
            // destroyForcibly() the child on timeout (which unblocks the
            // reader's readLine with EOF). Conservative on timeout: we return
            // whatever lines were drained before the deadline — under-counting
            // self-corrects on the next reap/ticker pass; we never delete a file
            // we didn't fully see.
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        files.add(new File(dir, line));
                    }
                } catch (Exception ignored) {
                    // Stream closed by destroyForcibly on timeout, or read error
                    // — return whatever we have.
                }
            }, "listFilesViaShell-drain");
            drain.setDaemon(true);
            drain.start();
            drain.join(4_000);
            boolean complete = false;
            if (drain.isAlive()) {
                logWarn("listFilesViaShell(" + dir.getName() + "): `ls` drain exceeded 4s"
                    + " — killing child and returning partial list (" + files.size() + " so far)");
                p.destroyForcibly();
                // Give the kernel a moment to close the stream so the drain
                // thread unblocks and stops touching `files` before we snapshot.
                drain.join(500);
            } else {
                // Drain finished; reap the (now-exited or about-to-exit) child.
                complete = waitForBounded(
                        p, 1_000, "listFilesViaShell(" + dir.getName() + ")") == 0;
            }

            File[] snapshot;
            synchronized (files) {
                snapshot = files.toArray(new File[0]);
            }
            logDebug("listFilesViaShell: found " + snapshot.length + " files in " + dir.getName());
            return new FileListResult(snapshot, complete);
        } catch (Exception e) {
            logWarn("listFilesViaShell failed: " + e.getMessage());
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return new FileListResult(new File[0], false);
        }
    }

    /**
     * Same as {@link #listFilesViaShell(File)} but filtered to a specific
     * extension. Used by the cleanup path so the anchor-collection step
     * picks up the right primary file type for each category.
     */
    private File[] listFilesByExt(File dir, String ext) {
        File[] all = listFilesViaShell(dir);
        if (all == null || all.length == 0) return all;
        java.util.List<File> matched = new java.util.ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(ext)) matched.add(f);
        }
        return matched.toArray(new File[0]);
    }

    /**
     * Public mp4-aware listing with the FUSE shell fallback. Mirrors what
     * the legacy {@code RecordingsApiHandler.scanDirectory} did before the
     * SOTA index rewrite — direct {@code listFiles()} returns null on SD
     * card / USB FUSE mounts under daemon UID 2000, and silently dropping
     * that directory leaves the recordings index empty.
     *
     * <p>Used by {@link com.overdrive.app.server.RecordingsIndex} during
     * warmup + reconcile, and by the API handler's {@code hasAnyMp4OnDisk}
     * probe. Returns an empty array (never null) so callers don't need to
     * null-check.
     */
    public File[] listMp4Files(File dir) {
        return listFilesWithFallback(dir, ".mp4");
    }

    public static final class Mp4Listing {
        public final File[] files;
        public final boolean available;
        public final boolean complete;

        Mp4Listing(File[] files, boolean available, boolean complete) {
            this.files = files;
            this.available = available;
            this.complete = complete;
        }
    }

    /**
     * MP4 listing with enough status for destructive reconciliation. A partial
     * shell result may add/update rows, but callers must delete missing rows
     * only when {@link Mp4Listing#complete} is true.
     */
    public Mp4Listing listMp4FilesWithStatus(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new Mp4Listing(new File[0], false, false);
        }
        java.io.FileFilter mp4Filter = file -> file.getName().endsWith(".mp4");
        FileListResult direct = listFilesDirectWithStatus(dir, mp4Filter, 4_000L);
        if (direct.complete) {
            return new Mp4Listing(direct.files, true, true);
        }
        FileListResult shell = listFilesViaShellWithStatus(dir);
        java.util.List<File> mp4 = new java.util.ArrayList<>();
        for (File file : shell.files) {
            if (file.getName().endsWith(".mp4")) mp4.add(file);
        }
        return new Mp4Listing(mp4.toArray(new File[0]), true, shell.complete);
    }

    static FileListResult listFilesDirectWithStatus(
            File dir, java.io.FileFilter filter, long timeoutMs) {
        java.util.concurrent.atomic.AtomicReference<File[]> files =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                files.set(dir.listFiles(filter));
            } catch (Throwable ignored) {
                files.set(null);
            } finally {
                finished.set(true);
            }
        }, "StorageDirectList-" + dir.getName());
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new FileListResult(new File[0], false);
        }
        File[] result = files.get();
        return new FileListResult(result != null ? result : new File[0],
                finished.get() && result != null);
    }

    /**
     * Generic FUSE-fallback listing — same shell-ls fallback as
     * {@link #listMp4Files(File)} but for any single suffix or
     * prefix+suffix combination. Used by sidecar-cleanup paths that
     * sweep per-actor thumbs ({@code thumb_<base>_a*.jpg}) and by
     * {@code dir.listFiles(filter)} sites that would otherwise swallow
     * the SD-card / USB null-listing case.
     *
     * <p>Pass null for {@code prefix} to filter on suffix only.
     * Returns an empty array (never null).
     */
    public File[] listFilesWithFallback(File dir, String suffix) {
        return listFilesWithFallback(dir, null, suffix);
    }

    public File[] listFilesWithFallback(File dir, String prefix, String suffix) {
        // NOTE: deliberately do NOT gate on canRead(). On the SD/USB FUSE mount
        // under daemon UID 2000 the dir reads non-readable to Java yet the shell
        // ls fallback below still enumerates it — the same reason the size gate,
        // wipeMediaCategory, sweepOrphanTempFiles and the ensureSpace sidecar
        // sweep guard only on exists()+isDirectory(). A canRead() short-circuit
        // here would skip the listFilesViaShell path and silently no-op the
        // FUSE consumers (e.g. cleanupOrphanedTmpFiles).
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new File[0];
        }
        java.io.FileFilter filter = f -> {
            String n = f.getName();
            if (suffix != null && !n.endsWith(suffix)) return false;
            if (prefix != null && !n.startsWith(prefix)) return false;
            return true;
        };
        File[] files = dir.listFiles(filter);
        if (files == null) {
            // FUSE returned null — shell ls then filter in-process. The
            // listFilesViaShell path doesn't take a filter so we apply it
            // ourselves on the returned array.
            File[] all = listFilesViaShell(dir);
            if (all == null || all.length == 0) return new File[0];
            java.util.List<File> matched = new java.util.ArrayList<>();
            for (File f : all) {
                if (filter.accept(f)) matched.add(f);
            }
            files = matched.toArray(new File[0]);
        }
        return files != null ? files : new File[0];
    }

    /**
     * Notify {@link com.overdrive.app.server.RecordingsIndex} that the
     * active recordings/surveillance/proximity dir set has changed —
     * either via user-driven storage-type switch (settings page) or via
     * volume hot-plug detected by the SD/USB watchdogs.
     *
     * <p>Two-step recovery:
     *  1. Re-arm FileObservers against the new dir set so future writes
     *     reach the index.
     *  2. Reconcile so existing files on the new volume populate the
     *     index immediately. Without this, hot-mounted SD/USB sticks
     *     stay invisible to events.html and the native fragment until
     *     the 1-hour periodic reconcile.
     *
     * <p>Step 2 runs on a background thread so we don't block the
     * caller (the SD/USB watchdog tick is on a single-thread executor;
     * blocking it would delay the next health probe).
     *
     * <p>Best-effort: any failure here is logged and swallowed. The
     * periodic reconcile is the absolute backstop.
     */
    private void notifyRecordingsIndexOfStorageChange(String reason) {
        try {
            com.overdrive.app.daemon.RecordingsIndexFileWatcher.getInstance().refresh();
        } catch (Throwable t) {
            logWarn(reason + ": RecordingsIndexFileWatcher refresh failed: " + t.getMessage());
        }
        try {
            com.overdrive.app.server.RecordingsIndex.getInstance()
                    .requestReconcile(reason);
        } catch (Throwable t) {
            logWarn(reason + ": RecordingsIndex reconcile request failed: " + t.getMessage());
        }
    }

    // ==================== Cleanup Logic ====================
    
    // Return values of {@link #cleanupGateDuringRecording}: how a caller should
    // proceed when it wants to reap a category.
    /** Encoder idle (or no over-limit) — run the normal UNLIMITED reap. */
    private static final int REAP_FULL = 0;
    /** Encoder writing, usage in the 0–5% over-cap band — run a BOUNDED trim
     *  ({@link #RECORDING_TRIM_MAX_FILES}) so the limit still converges mid-drive
     *  without a disk-writer-starving burst. */
    private static final int REAP_BOUNDED = 1;
    /** Encoder writing, usage under cap (or category empty) — defer to idle drain. */
    private static final int REAP_DEFER = 2;

    /**
     * If a recording is in flight, defer the cleanup so it runs on the next
     * encoder-idle periodic tick. Returns true when deferral happened.
     *
     * <p>Three of the public ensure*Space callers can race the encoder:
     * user-initiated limit changes from the HTTP/IPC settings handlers
     * (QualitySettingsApiHandler, SurveillanceIpcServer, TcpCommandServer).
     * Without this gate, lowering the recordings limit while actively
     * recording triggers a delete burst that contends with the disk
     * writer and produces multi-second eglSwap stalls observed in field
     * logs. The post-save cleanup path has its own gate at
     * onRecordingFileSaved; this generalises the same guard to limit-
     * change paths so the cleanup contract is uniform.
     *
     * <p>Hard-overlimit escape: if usage already exceeds the limit by
     * &gt;5%, deferral is skipped — at that point the encoder will
     * backpressure on disk full anyway, so unblocking storage is the
     * lesser evil. Mirrors the periodic-loop policy.
     *
     * <p>This is the DEFER-OR-FULL gate used by the limit-change / boot / wipe
     * callers. The periodic ticker uses {@link #cleanupGateDuringRecording}
     * instead, which adds the BOUNDED-trim middle state.
     */
    private boolean deferIfEncoderBusy(String deferredKey, long currentSize, long limitBytes) {
        if (!isEncoderWriting()) return false;
        boolean hardOverLimit = limitBytes > 0 && currentSize > limitBytes * 21 / 20;
        if (hardOverLimit) {
            logWarn("Cleanup forced during recording: " + deferredKey + " at "
                + formatSize(currentSize) + "/" + formatSize(limitBytes) + " (HARD)");
            return false;
        }
        deferredCleanupDirs.add(deferredKey);
        logDebug("Cleanup deferred (encoder busy): " + deferredKey + " — will drain on next idle tick");
        return true;
    }

    /**
     * Cleanup-gate for the PERIODIC TICKER while it may be racing the encoder.
     *
     * <p>WHY THIS EXISTS — the "periodic cleanup never frees space" deadlock.
     * {@link #deferIfEncoderBusy} is all-or-nothing: below +5% over cap it DEFERS,
     * and the deferred queue only drains when {@code isEncoderWriting()} is false.
     * During a continuous drive that idle window never opens, and a continuous
     * recorder parks ~1.9% over its cap forever (one ~220 MB segment per ~2 min vs
     * the cap). Result: in the 0–5% band nothing was EVER reaped while driving —
     * the gate fired every 30 s, logged "...recordings at 10.7 GB/10.5 GB", and
     * deleted zero bytes until the volume hit ENOSPC. (The "forced...CRITICAL"
     * tick admitted itself on a disk-critical signal, but then called
     * ensureRecordingsSpace which re-deferred on this same +5% rule with no
     * knowledge of the disk-critical state — so the signal never reached the
     * reaper. Gate disagreement, now closed: the ticker reaps directly via the
     * decision returned here.)
     *
     * <p>Three outcomes:
     * <ul>
     *   <li>{@link #REAP_FULL} — encoder idle, or not over cap: unbounded reap.
     *   <li>{@link #REAP_BOUNDED} — encoder writing AND over cap (any amount up to
     *       +5%): trim a few oldest finalized segments this tick so the limit
     *       converges without a burst. Above +5% also returns FULL (HARD escape).
     *   <li>{@link #REAP_DEFER} — encoder writing and under cap: nothing to do,
     *       mark deferred so an idle drain re-checks.
     * </ul>
     */
    private int cleanupGateDuringRecording(String deferredKey, long currentSize, long limitBytes) {
        if (!isEncoderWriting()) return REAP_FULL;
        if (limitBytes <= 0 || currentSize <= limitBytes) {
            // Under cap (the 90% ticker gate can fire between 90%–100%): nothing to
            // free yet. Mark deferred so the next idle drain revisits if it climbs.
            deferredCleanupDirs.add(deferredKey);
            return REAP_DEFER;
        }
        boolean hardOverLimit = currentSize > limitBytes * 21 / 20;  // >5% over
        // Over cap but ≤5%: bounded trim. Over cap by >5%: full emergency reap.
        return hardOverLimit ? REAP_FULL : REAP_BOUNDED;
    }

    /**
     * Ensure recordings storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * <p>Defers when the encoder is mid-write (see {@link #deferIfEncoderBusy})
     * unless usage is hard-over-limit, in which case cleanup runs anyway to
     * keep the disk writer from hitting ENOSPC.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available, or true
     *         when deferred (the deferred-cleanup drain will retry).
     */
    public boolean ensureRecordingsSpace(long reserveBytes) {
        return ensureRecordingsSpace(reserveBytes, null);
    }

    /**
     * Variant that takes an explicit {@code activeDir} so the caller can
     * snapshot it once and pass through. Without this, two volatile reads
     * of {@code recordingsDir} (one in the caller for filename construction,
     * one inside this method for the cleanup target) can disagree if a
     * concurrent storage-type switch swaps volumes between them — the
     * recorder writes the file into the OLD volume while the pre-flight
     * reserve targets the NEW volume.
     *
     * @param activeDir the directory the caller intends to write to. Pass
     *                  {@code null} to use the live {@code recordingsDir}.
     */
    public boolean ensureRecordingsSpace(long reserveBytes, File activeDir) {
        // recordingsCleanupLock serializes recordings-only work — post-save
        // async cleanups, periodic ticks, the reset/wipe path, and the
        // recorder's pre-flight reserve. It does NOT block on the surveillance
        // / proximity / trips locks, so a long boot reap of one category can't
        // starve the recorder's pre-flight in another.
        synchronized (recordingsCleanupLock) {
            File targetDir = (activeDir != null) ? activeDir : recordingsDir;
            // Defer-gate on the EFFECTIVE scope (internal pool + clamped limit during
            // a fallback), matching what ensureSpace will actually enforce — otherwise
            // the combined-pool size vs raw external limit never trips and a near-full
            // internal fallback volume is never reaped while the encoder is writing.
            if (deferIfEncoderBusy(DEFERRED_RECORDINGS, scopedSizeForCategory("recordings"),
                    scopedLimitBytesForCategory("recordings"))) {
                return true;
            }
            return ensureSpace("recordings", getReapableDirs("recordings"), targetDir,
                namePrefixForCategory("recordings"),
                recordingsLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Recorder-critical-path variant of {@link #ensureRecordingsSpace(long, File)}.
     *
     * <p>WHY THIS EXISTS — the ~9-10 min "recording doesn't start after ACC ON"
     * bug. The full {@code ensureRecordingsSpace} acquires
     * {@code recordingsCleanupLock} and walks/deletes the entire recordings pool
     * down to the configured size limit. On a cold boot that lock is held by the
     * startup reap (constructor → {@code ensureRecordingsSpace(0)}), whose
     * recordings leg can take many minutes on a FUSE-bridged SD/USB volume with
     * thousands of legacy clips (one {@code ls}/{@code rm} shell fork per file).
     * The recorder's pre-flight used to block on that same per-category lock for
     * the WHOLE walk, so {@code RecordingModeManager.activateMode} →
     * {@code pipeline.startRecording()} stalled and the user's drive was silently
     * not recorded until the reap finished. The historical per-category-lock
     * split fixed cross-category starvation but NOT recordings-vs-recordings:
     * boot reap and recorder pre-flight are the same category.
     *
     * <p>THE INSIGHT — the recorder pre-flight only needs ONE guarantee: enough
     * PHYSICAL free space that the next segment won't hit ENOSPC mid-file. That's
     * a {@link android.os.StatFs} question (single ~200µs binder call, no lock, no
     * shell fork), not a category-retention question. Keeping the folder under its
     * configured MB limit is housekeeping that the startup reap, the 30s periodic
     * ticker, AND the post-save cleanup all already enforce OFF the critical path.
     *
     * <p>So: if the target volume already has {@code reserveBytes} free, start
     * recording immediately and hand category-limit retention to the async
     * executor (it serialises behind any in-flight boot reap on the single-thread
     * {@link #asyncCleanupExecutor} — correct ordering, no double-reap contention;
     * and if the encoder is by then writing, {@code deferIfEncoderBusy} defers it
     * to the next idle drain, so it never competes with live muxer writes). Only
     * when the disk is GENUINELY short of physical space do we fall back to the
     * synchronous lock-held reap — the one case where blocking is correct, because
     * we truly cannot write the next segment until space frees.
     *
     * <p>This keeps every existing caller of {@link #ensureRecordingsSpace} (boot
     * reap, periodic ticker, post-save, TCP limit-change, reset/wipe) byte-for-byte
     * unchanged — only the recorder's hot path routes through here.
     *
     * @param reserveBytes physical bytes the next segment needs (recorder passes ~100MB)
     * @param targetDir    the directory the recorder is about to write into
     * @return true once it's safe to start recording (fast-path) or after a
     *         successful synchronous reap; false only if the synchronous reap
     *         could not free enough space.
     */
    public boolean ensureRecordingsSpaceForRecorder(long reserveBytes, File targetDir) {
        File dir = (targetDir != null) ? targetDir : recordingsDir;

        long availableBytes = -1L;
        try {
            // Probe the target dir itself when it exists; otherwise its parent
            // (the dir may not be mkdir'd yet on first boot). StatFs reads the
            // volume the path lives on either way.
            File probeDir = null;
            if (dir != null) {
                probeDir = dir.exists() ? dir : dir.getParentFile();
            }
            if (probeDir != null && probeDir.exists()) {
                android.os.StatFs stat = new android.os.StatFs(probeDir.getAbsolutePath());
                availableBytes = stat.getAvailableBytes();
            }
        } catch (Throwable t) {
            // StatFs throws IllegalArgumentException on an unmounted / half-
            // mounted volume. Treat as "unknown" → fall through to the
            // synchronous reap (which has its own dir-existence guards and is
            // the correct conservative choice when we can't measure free space).
            logWarn("Recorder pre-flight StatFs failed for "
                + (dir != null ? dir.getAbsolutePath() : "null") + ": " + t.getMessage()
                + " — falling back to synchronous reap");
            availableBytes = -1L;
        }

        if (availableBytes >= reserveBytes) {
            // Enough physical space — do NOT block on recordingsCleanupLock
            // (which the boot reap may hold for minutes). Start recording now;
            // hand category-limit retention to the async executor.
            logDebug("Recorder pre-flight fast-path: " + formatSize(availableBytes)
                + " free >= " + formatSize(reserveBytes) + " reserve — starting now, "
                + "deferring retention to async cleanup");
            final File asyncTarget = dir;
            try {
                asyncCleanupExecutor.execute(() -> {
                    try {
                        ensureRecordingsSpace(0, asyncTarget);
                    } catch (Throwable t) {
                        logWarn("Deferred recorder retention reap failed: " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                // Executor rejected (shutting down) — non-fatal; the 30s
                // periodic ticker and post-save cleanup still enforce the limit.
                logDebug("Could not enqueue deferred retention: " + t.getMessage());
            }
            return true;
        }

        // Genuine physical-space pressure (or StatFs unavailable): we MUST free
        // space before writing, so the synchronous lock-held reap is correct
        // here even if it means waiting on an in-flight reap. The bounded shell
        // forks (listFilesViaShell / deleteFileViaShell) keep each delete from
        // hanging indefinitely while we hold the recorder thread.
        // WARN (not INFO): this is the ONE path that can still block the
        // recorder behind the boot reap on recordingsCleanupLock — the exact
        // mechanism behind the historical "~9-10 min late recording" stall.
        // It is correct to block here (we genuinely cannot write a segment with
        // less than the reserve free), but if this fires repeatedly in the
        // field it means a volume is chronically near-full and recording start
        // is being delayed — so make it loud and timed for diagnosability.
        logWarn("Recorder pre-flight: only "
            + (availableBytes < 0 ? "unknown (StatFs unavailable)" : formatSize(availableBytes))
            + " free (< " + formatSize(reserveBytes) + " reserve) — running "
            + "SYNCHRONOUS lock-held reap before recording (may block behind an "
            + "in-flight boot/periodic reap; this is the near-full-volume fallback)");
        long syncReapStartNs = System.nanoTime();
        boolean ok = ensureRecordingsSpace(reserveBytes, targetDir);
        long syncReapMs = (System.nanoTime() - syncReapStartNs) / 1_000_000L;
        if (syncReapMs > 1_000) {
            logWarn("Recorder pre-flight synchronous reap took " + syncReapMs
                + "ms (freedEnough=" + ok + ") — recording start was delayed by storage cleanup");
        }
        return ok;
    }

    /**
     * Ensure surveillance storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureSurveillanceSpace(long reserveBytes) {
        synchronized (surveillanceCleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_SURVEILLANCE, scopedSizeForCategory("surveillance"),
                    scopedLimitBytesForCategory("surveillance"))) {
                return true;
            }
            return ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                namePrefixForCategory("surveillance"),
                surveillanceLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure proximity storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureProximitySpace(long reserveBytes) {
        synchronized (proximityCleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_PROXIMITY, scopedSizeForCategory("proximity"),
                    scopedLimitBytesForCategory("proximity"))) {
                return true;
            }
            return ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                namePrefixForCategory("proximity"),
                proximityLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure trips storage is within size limit.
     * Deletes oldest files until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureTripsSpace(long reserveBytes) {
        synchronized (tripsCleanupLock) {
            reconcileOrphanTripRows();

            if (deferIfEncoderBusy(DEFERRED_TRIPS, scopedSizeForCategory("trips"),
                    scopedLimitBytesForCategory("trips"))) {
                return true;
            }
            return ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                namePrefixForCategory("trips"),
                tripsLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Drop trip DB rows whose backing .jsonl.gz vanished out-of-band, BEFORE any
     * trips-size gate read. The trips limit gate reads the DB
     * SUM(size_bytes+sidecar_size_bytes), but the reaper frees bytes by walking the
     * filesystem. A row whose file vanished (file-manager delete, SD/volume swap, or
     * a crash between TripApiHandler's file-delete and row-delete) keeps the SUM
     * inflated forever: the gate fires every 30 s while the disk walk has nothing to
     * delete, so it never converges. Caller must hold {@code tripsCleanupLock}.
     */
    private void reconcileOrphanTripRows() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            com.overdrive.app.trips.TripDatabase db = (tam != null) ? tam.getDatabase() : null;
            // Pass a volume-availability guard so a dropped SD/USB card (where
            // every file reads as missing) can NEVER mass-delete the user's
            // trip history. A row is only reapable if its file is gone AND the
            // volume that holds it is mounted. See deleteRowsWithMissingFiles.
            if (db != null && db.deleteRowsWithMissingFiles(this::isTelemetryVolumeAvailable) > 0) {
                invalidateTripsDbSizeCache();
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * True iff the storage volume that {@code telemetryPath} lives on is
     * currently mounted and readable — the guard that stops the trips
     * orphan-reconcile from mass-deleting history when an SD/USB card is
     * dropped (every file on an unmounted volume reads as missing).
     *
     * <p>Resolution is DENYLIST-BY-INTERNAL (fail safe toward preservation):
     * return true ONLY for paths we can prove live on internal storage (which is
     * always mounted). Removable roots gate on their live mount probe. ANYTHING
     * else — including a path on a volume whose root we can't currently resolve
     * (e.g. {@code sdCardPath==null} because the card is unmounted, or a
     * {@code /mnt/*} mount point we didn't recognise) — returns false so the row
     * is PRESERVED. The earlier allowlist-by-removable-shape version wrongly fell
     * through to "internal → true" for unrecognised removable mount points (e.g.
     * {@code /mnt/external_sd} from the /proc/mounts fallback), which would
     * mass-delete history on exactly the dropped-card case this guard exists for.
     */
    private boolean isTelemetryVolumeAvailable(String telemetryPath) {
        if (telemetryPath == null || telemetryPath.isEmpty()) return false;

        // Known removable roots, while mounted: gate on the live probe.
        String sd = sdCardPath;
        if (sd != null && !sd.isEmpty() && telemetryPath.startsWith(sd)) {
            return isSdCardLikelyMounted();
        }
        String usb = usbPath;
        if (usb != null && !usb.isEmpty() && telemetryPath.startsWith(usb)) {
            return isUsbLikelyMounted();
        }

        // Provably-internal roots are always available. Only these return true.
        if (telemetryPath.startsWith(INTERNAL_BASE_DIR)
                || telemetryPath.startsWith(LEGACY_APP_FILES_DIR)
                || telemetryPath.startsWith("/storage/emulated")
                || telemetryPath.startsWith("/storage/self")
                || telemetryPath.startsWith("/data/")) {
            return true;
        }

        // Anything else — an unmounted removable root (path under /storage/<uuid>,
        // /mnt/*, etc. whose live root we couldn't match above) — is treated as
        // unavailable so the reconcile PRESERVES the row rather than deleting it.
        return false;
    }

    /**
     * Primary file extension for a category. Cleanup walks files matching
     * this extension as the "anchor" rows; sidecars are pulled in via
     * {@link #sidecarExtensionsForCategory(String)}.
     *
     * @return non-null lowercase extension including the leading dot.
     */
    private static String primaryExtensionForCategory(String category) {
        switch (category) {
            case "recordings":   return ".mp4";
            case "surveillance": return ".mp4";
            case "proximity":    return ".mp4";
            case "trips":        return ".jsonl.gz";
            default:             return ".mp4";
        }
    }

    /**
     * Partial / orphan extensions that aren't anchors but still consume disk
     * space the user expects to be subject to the limit. These are produced
     * by abnormal exits (SIGKILL between {@code .mp4.tmp} and {@code .mp4}
     * rename, encoder write that left a {@code .broken}, hero-image
     * extraction that left a {@code .jpg.tmp}). Counted by size accounting
     * and {@link #sweepOrphanTempFiles} reaps them with a 10-minute grace
     * window so live writers aren't disturbed.
     *
     * @return possibly empty array of lowercase suffixes (with leading dot
     *         or compound like {@code .mp4.tmp}).
     */
    private static String[] partialExtensionsForCategory(String category) {
        switch (category) {
            // .json.tmp / .srt.tmp: LocationSidecarWriter.writeJsonAtomic and
            // SrtWriter.write both write a <base>.<ext>.tmp then rename; an abnormal
            // exit between write and rename orphans the .tmp next to a cam_/dvr_ clip.
            case "recordings":   return new String[]{".mp4.tmp", ".broken", ".json.tmp", ".srt.tmp"};
            case "surveillance": return new String[]{".mp4.tmp", ".broken", ".jpg.tmp", ".json.tmp", ".srt.tmp"};
            // .json.tmp added so the geo-backfill sidecar rewrite (SidecarGeoUpdater,
            // which sweeps proximity dirs too) leaves no unreaped orphan on a
            // SIGKILL in its write→rename window; .jpg.tmp mirrors the surveillance
            // hero-thumb partial since proximity events write the same sidecar/thumb set.
            case "proximity":    return new String[]{".mp4.tmp", ".broken", ".json.tmp", ".jpg.tmp", ".srt.tmp"};
            case "trips":        return new String[]{".jsonl.gz.tmp"};
            default:             return new String[]{};
        }
    }

    /**
     * Sidecar extensions that share a stem with the primary file and should
     * be reaped together. Used both for size accounting (so the on-disk
     * footprint matches what the user sees in the UI) and for delete-time
     * orphan cleanup.
     *
     * @return possibly empty array of lowercase extensions (each starting
     *         with a dot), in addition to the primary extension.
     */
    private static String[] sidecarExtensionsForCategory(String category) {
        switch (category) {
            case "recordings":
                // Both cam_*.mp4 AND dvr_*.mp4 (OEM Dashcam) carry geo + subtitle
                // sidecars: LocationSidecarWriter.writeJsonAtomic writes <base>.json
                // and SrtWriter writes <base>.srt for any clip whose
                // inferGeocodingFlow != "surveillance" — i.e. everything that isn't
                // event_* (HardwareEventRecorderGpu:inferGeocodingFlow only special-
                // cases the "event_" prefix). dvr_ clips run through the SAME encoder,
                // so they get dvr_*.json and dvr_*.srt companions too (the old comment
                // claiming "dvr_ aux clips get no .json" was wrong). Both extensions
                // must be in this list or the .srt (and, for dvr_, the .json) leak
                // forever — one per recorded segment — counted by nothing and reaped
                // by nothing. .srt was entirely absent here.
                return new String[]{".json", ".srt"};
            case "surveillance":
                // event_*: timeline JSON, hero JPG, overlay SRT.
                return new String[]{".json", ".jpg", ".srt"};
            case "proximity":
                return new String[]{".json"};
            case "trips":
                return new String[]{};
            default:
                return new String[]{};
        }
    }

    /**
     * Strip the primary extension from a file name, leaving the stem used
     * to match sidecars. Handles compound extensions like ".jsonl.gz".
     */
    private static String stemForName(String fileName, String primaryExt) {
        if (fileName.endsWith(primaryExt)) {
            return fileName.substring(0, fileName.length() - primaryExt.length());
        }
        return fileName;
    }

    /**
     * Generic cleanup method that operates across a set of directories.
     *
     * Pools all primary-extension files from every dir (active, inactive
     * mirror, legacy), sorts globally by mtime, and deletes oldest-first
     * (along with each anchor's sidecars) until the combined total is
     * under the limit. This guarantees the user-configured limit is honored
     * across orphan locations after a storage-type switch or after a legacy
     * install left behind clips.
     *
     * Size accounting includes sidecars so the limit reflects the on-disk
     * footprint the user sees in the UI, not just the anchor file's bytes.
     *
     * SOTA: Uses shell fallback for listing/deleting when directory is owned
     * by a different UID than the daemon.
     *
     * <p><b>Fallback scoping (2026-06).</b> When the category's CONFIGURED volume
     * is external but writes are CURRENTLY landing on internal (the external is
     * full or unmounted — {@link #activeTypeForCategory} resolves to INTERNAL while
     * {@link #configuredTypeForCategory} is SD/USB), this method rescopes itself to
     * the INTERNAL volume only and enforces {@link #effectiveReapLimitBytes} (the
     * configured MB clamped to what internal can physically hold). This bounds the
     * fallback internal volume to the SAME configured limit — capped at internal's
     * real capacity so it can't fill to physical-full — WITHOUT auto-deleting the
     * configured external volume's archive while it's merely full/absent (the
     * external dirs are dropped from the reap set, never reaped here). In normal
     * mode (active == configured) the scoping is a no-op and behavior is unchanged.
     *
     * @param category    Category key — recordings/surveillance/proximity/trips.
     * @param dirs        Every directory whose files count toward this limit.
     *                    May contain a mix of active, inactive, and legacy
     *                    paths. Nulls/missing dirs are skipped.
     * @param activeDir   The dir new files will land in. Created if missing
     *                    so the next write doesn't fail.
     * @param limitBytes  Total bytes allowed across all dirs.
     * @param reserveBytes Additional bytes to keep free (subtracted from limit).
     * @return true if cleanup was successful and space is available
     */
    private boolean ensureSpace(String category, List<File> dirs, File activeDir,
                                String namePrefix,
                                long limitBytes, long reserveBytes) {
        // Backward-compatible entry point: no delete budget (full reap to limit).
        // Boot reap, idle drain, post-save cleanup, limit-change, and the HARD
        // over-limit emergency path all want the unbounded behaviour.
        return ensureSpace(category, dirs, activeDir, namePrefix,
            limitBytes, reserveBytes, UNLIMITED_REAP);
    }

    /**
     * As {@link #ensureSpace(String, List, File, String, long, long)} but with a
     * delete budget. {@code maxDeletes <= 0} ({@link #UNLIMITED_REAP}) reaps as many
     * oldest anchors as needed to reach the limit. A positive budget stops after that
     * many anchor deletions — the BOUNDED-TRIM mode used by the periodic tick WHILE
     * the encoder is writing, so a single pass can't fire a large delete-burst that
     * starves the muxer's disk writer (see {@link #RECORDING_TRIM_MAX_FILES}). The
     * orphan-sidecar sweep and the CDR fallback are skipped under a budget — they walk
     * the whole tree and are housekeeping that the next idle/full pass still performs.
     */
    private boolean ensureSpace(String category, List<File> dirs, File activeDir,
                                String namePrefix,
                                long limitBytes, long reserveBytes, int maxDeletes) {
        final boolean budgeted = maxDeletes > 0;
        // Active-volume scoping: if writes have fallen back from a configured
        // external volume onto internal, bound internal to the configured limit
        // (clamped to internal's capacity) and DON'T touch the external archive.
        StorageType activeType = activeTypeForCategory(category);
        StorageType configuredType = configuredTypeForCategory(category);
        if (activeType == StorageType.INTERNAL && configuredType != StorageType.INTERNAL) {
            long configuredMb = configuredLimitMbForCategory(category);
            long effLimitBytes = effectiveReapLimitBytes(StorageType.INTERNAL, configuredMb);
            if (effLimitBytes != limitBytes || dirs.size() != internalScopedDirs(dirs).size()) {
                logInfo(category + " retention scoped to internal fallback volume: limit "
                    + formatSize(limitBytes) + " → effective " + formatSize(effLimitBytes)
                    + " (configured volume " + configuredType + " unavailable/full; not reaping it)");
            }
            limitBytes = effLimitBytes;
            dirs = internalScopedDirs(dirs);
            File internalDir = internalDirForCategory(category);
            if (internalDir != null) activeDir = internalDir;
        } else if (activeType == StorageType.INTERNAL) {
            // Natively-INTERNAL category (configuredType == INTERNAL, so the
            // fallback rescope above did NOT fire). A limit persisted while
            // internal's StatFs transiently read 0 can be up to the 100 GB
            // sentinel; with no clamp here the reaper target would stay at that
            // sentinel on an ~8 GB volume and internal fills to physical-full —
            // the persisted slider value is silently un-enforced. Bound the
            // enforced target to internal's real capacity. effectiveReapLimitBytes
            // also returns the sentinel during the SAME transient zero, so this
            // only bites once StatFs recovers — which is exactly when over-fill
            // matters — giving correct convergence without shrinking on a hiccup.
            limitBytes = Math.min(limitBytes,
                effectiveReapLimitBytes(StorageType.INTERNAL, configuredLimitMbForCategory(category)));
        } else if (activeType != StorageType.INTERNAL) {
            // Active EXTERNAL volume (SD/USB), active == configured (no fallback).
            // A limit persisted while the card was absent — or carried over from a
            // larger card before a swap — can be up to the 100 GB MAX_LIMIT_MB_FALLBACK
            // sentinel (loadConfig + the set*LimitMb setters both clamp through
            // loadTimeCeilingMb, which returns that sentinel for an unmounted external).
            // reclampLimitsToMountedCeilings() only re-clamps on SOME remount paths,
            // so an oversized value can survive on a now-mounted small card. Without a
            // clamp here the reaper target stays at that sentinel on e.g. a 32 GB card,
            // currentSize never exceeds it, the reaper deletes nothing, and the card
            // fills to physical-full — the configured limit silently un-enforced.
            // Bound the enforced target to the active card's real capacity using the
            // LIVE total (activeExternalReapLimitBytes), which treats a transient StatFs
            // zero as "don't shrink" rather than substituting internal's smaller ceiling
            // (which would over-reap the SD card on a FUSE hiccup).
            limitBytes = Math.min(limitBytes,
                activeExternalReapLimitBytes(activeType, configuredLimitMbForCategory(category)));
        }

        if (activeDir != null && (!activeDir.exists() || !activeDir.isDirectory())) {
            activeDir.mkdirs();
        }

        long targetSize = limitBytes - reserveBytes;
        if (targetSize < 0) targetSize = 0;

        final String primaryExt = primaryExtensionForCategory(category);
        final String[] sidecarExts = sidecarExtensionsForCategory(category);
        final String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        // Set true when a trips DB row is dropped below so the DB-size cache can
        // be expired after the loop (otherwise the next getTripsSize() returns a
        // stale, inflated SUM and the limit gate never converges).
        boolean tripsRowReaped = false;

        // Collect every reapable anchor file, deduplicated by filename so a
        // clip that exists on both internal and SD card isn't accounted twice.
        // When namePrefix is non-null, restrict to files matching the category
        // (some dirs in the list — typically the flat legacy base — are shared
        // across categories).
        List<File> allFiles = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        long currentSize = 0;
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            // PERF (was O(N^2)): the aux-sidecar accounting below used to call
            // findAuxiliarySiblings(dir, ...) once PER ANCHOR, and that helper
            // does its own full dir.listFiles() every call. Measuring a
            // 1000-clip recordings dir therefore issued 1000 full directory
            // listings — on the FUSE-bridged volumes these dirs live on, each
            // one is an IPC round-trip per entry. Build the aux index ONCE per
            // directory instead and look stems up in it.
            //
            // Only built when the category actually has aux prefixes
            // ("recordings" -> dvr_/replay_, "surveillance" -> thumb_); null
            // otherwise so no listing cost is added for categories without them.
            // Deliberately reuses the same matching rule as
            // findAuxiliarySiblings ("<auxPrefix><stem>_" prefix) so the byte
            // accounting is identical to before — see the assertion in that
            // method's contract.
            java.util.Map<String, List<File>> auxIndex =
                    (auxPrefixes.length > 0) ? buildAuxiliaryIndex(dir, auxPrefixes) : null;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                // Anchor gate. Use nameMatchesCategoryPrefix (primary OR auxiliary
                // prefix), NOT a bare startsWith(namePrefix), so STANDALONE
                // auxiliary clips are reaped as first-class anchors. Concretely:
                // OEM Dashcam DRIVE clips (dvr_<ts>.mp4) live in the recordings
                // dir and are COUNTED toward the limit by getDirectoriesTotalSize
                // (which already uses nameMatchesCategoryPrefix), but they have
                // their own timestamps — never a matching cam_ stem — so the old
                // startsWith("cam") gate excluded them from allFiles entirely.
                // They were counted-but-unreapable: once the cam_ files were under
                // the limit the reaper deleted nothing while dvr_ kept the dir over
                // limit forever (SD filled silently). The aux-sidecar branch below
                // only catches `<aux><anchorStem>_…` siblings (e.g. surveillance
                // thumb_<event>_…), which dvr_ standalone clips never match — hence
                // they were invisible here. Matching them as anchors makes the
                // recordings limit enforceable regardless of cam_/dvr_ mix, and
                // keeps the reaper consistent with the size measurement.
                if (namePrefix != null
                        && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;
                if (!seenNames.add(name)) continue;
                allFiles.add(f);
                currentSize += f.length();
                // Add sidecar bytes to the running total so we measure the
                // same on-disk footprint we'd actually free by deleting the
                // anchor + its sidecars below. Two sidecar shapes:
                //   - same-stem siblings: <stem><sidecarExt>  (json/srt/jpg)
                //   - aux-prefix siblings: <auxPrefix><stem>_*  (per-actor thumbs)
                if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
                    String stem = stemForName(name, primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(f.getParentFile(), stem + ext);
                        if (sidecar.isFile()) currentSize += sidecar.length();
                    }
                    if (auxPrefixes.length > 0) {
                        // Index lookup instead of a per-anchor directory listing.
                        // Falls back to the direct walk if the index couldn't be
                        // built (listFiles returned null mid-remount), so a flaky
                        // volume degrades to the old behaviour rather than
                        // silently under-counting aux bytes.
                        List<File> auxHits = (auxIndex != null)
                                ? auxIndex.get(stem)
                                : findAuxiliarySiblings(f.getParentFile(), auxPrefixes, stem);
                        if (auxHits != null) {
                            for (File aux : auxHits) {
                                currentSize += aux.length();
                            }
                        }
                    }
                }
            }
        }

        if (currentSize <= targetSize) {
            return true;  // Already within limit
        }

        if (allFiles.isEmpty()) {
            // We were called because the GATE measured this category OVER its
            // limit (scopedSizeForCategory > scopedLimitBytesForCategory), yet
            // the reaper's own walk found ZERO reapable anchors. On a flaky
            // FUSE-bridged SD/USB volume mid-remount, listFiles() (and the
            // shell-ls fallback) can both return null, so the reaper sees an
            // empty pool, "converges" to nothing, and the gate keeps firing
            // every 30 s with no delete — the silent no-op behind the field
            // report "periodic cleanup never cleans up". Make it loud and
            // measured so this is diagnosable from a single log capture instead
            // of inferred. The next tick re-tries once the volume re-resolves.
            logWarn(category + " over limit (" + formatSize(currentSize) + "/"
                + formatSize(limitBytes) + ") but found NO reapable files in "
                + dirs.size() + " dir(s) — volume listing likely failed (transient "
                + "unmount?); will retry next pass");
            return true;
        }

        // Oldest first (global ordering across all dirs).
        Collections.sort(allFiles, Comparator.comparingLong(File::lastModified));

        int deletedCount = 0;
        long deletedSize = 0;
        boolean reapedFromInactive = false;
        boolean budgetExhausted = false;

        // Path of the in-flight trip telemetry file, if any. Only honored
        // when reaping the trips category; recordings/surveillance use the
        // encoder-writing probe further down.
        final String protectedTripPath = "trips".equals(category) ? activeTripFilePath : null;

        for (File file : allFiles) {
            if (currentSize <= targetSize) break;

            // Bounded-trim cap: when running WHILE the encoder writes, stop after
            // maxDeletes ANCHOR deletions so this pass can't fire a large delete-
            // burst that contends with the muxer's disk writer. The budget counts
            // ANCHORS (the heavy ~220 MB .mp4 files); each anchor's same-stem
            // sidecars and aux thumbs (KB-scale) ride along uncounted below — their
            // unlink cost is negligible next to the big files the budget exists to
            // pace. The 30 s ticker resumes the trim next pass, so the limit still
            // converges.
            if (budgeted && deletedCount >= maxDeletes) {
                budgetExhausted = true;
                break;
            }

            // Don't unlink a still-being-written trip file. The recorder
            // keeps the GZIPOutputStream open across SAMPLE_INTERVAL_MS ticks;
            // a delete here would orphan every byte buffered after this point.
            if (protectedTripPath != null
                    && protectedTripPath.equals(file.getAbsolutePath())) {
                logDebug("Skipping in-flight trip file: " + file.getName());
                continue;
            }

            long fileSize = file.length();
            boolean deleted = file.delete();
            if (!deleted) {
                deleted = deleteFileViaShell(file);
            }

            if (deleted) {
                currentSize -= fileSize;
                deletedCount++;
                deletedSize += fileSize;
                if (activeDir == null
                    || !file.getParentFile().getAbsolutePath().equals(activeDir.getAbsolutePath())) {
                    reapedFromInactive = true;
                }
                logInfo("Deleted old file: " + file.getAbsolutePath() + " (" + formatSize(fileSize) + ")");

                // Drop the H2 row immediately. Without this, a limit-driven
                // retention sweep would leave phantom entries until the
                // next 1-hour reconcile — chips and counts would lie for
                // up to an hour. Cheap (single indexed DELETE), no-op for
                // non-recording categories like trips because the index
                // only knows about .mp4 filenames.
                if (file.getName().endsWith(".mp4")) {
                    try {
                            com.overdrive.app.server.RecordingsIndex
                                .getInstance().removeByPath(file.getAbsolutePath());
                    } catch (Throwable ignored) {}
                }

                // Trips anchors live in TripDatabase, not RecordingsIndex. The
                // limit gate reads the DB SUM(size_bytes+sidecar_size_bytes), so
                // a reaped .jsonl.gz whose row survives keeps that SUM (and the
                // gate) permanently inflated — the gate fires every 30s but finds
                // nothing to delete. Drop the row here, mirroring the .mp4 branch
                // above, and flag the DB-size cache for invalidation after the
                // loop so the next getTripsSize() reflects post-reap reality.
                if (file.getName().endsWith(".jsonl.gz")) {
                    try {
                        com.overdrive.app.trips.TripAnalyticsManager tam =
                                com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
                        com.overdrive.app.trips.TripDatabase db = (tam != null) ? tam.getDatabase() : null;
                        if (db != null && db.deleteByTelemetryPath(file.getAbsolutePath()) > 0) {
                            tripsRowReaped = true;
                        }
                    } catch (Throwable ignored) {}
                }

                // Sidecars share the anchor's stem and are dead weight once
                // the anchor is gone. Walk the registered set instead of the
                // recordings-only ".mp4 → .json" replace().
                if (sidecarExts.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(file.getParentFile(), stem + ext);
                        if (sidecar.exists()) {
                            long sidecarSize = sidecar.length();
                            boolean sidecarDeleted = sidecar.delete();
                            if (!sidecarDeleted) sidecarDeleted = deleteFileViaShell(sidecar);
                            if (sidecarDeleted) {
                                currentSize -= sidecarSize;
                                deletedSize += sidecarSize;
                            }
                        }
                    }
                }

                // Aux-prefix siblings (per-actor thumbs `thumb_event_<base>_a*`).
                // These don't share a stem suffix with the anchor — they share
                // a prefix construction (auxPrefix + anchorStem + "_…").
                if (auxPrefixes.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (File aux : findAuxiliarySiblings(file.getParentFile(), auxPrefixes, stem)) {
                        long auxSize = aux.length();
                        boolean auxDeleted = aux.delete();
                        if (!auxDeleted) auxDeleted = deleteFileViaShell(aux);
                        if (auxDeleted) {
                            currentSize -= auxSize;
                            deletedSize += auxSize;
                        }
                    }
                }

                // Drop any cached entry the recordings API might still hold.
                try {
                    com.overdrive.app.server.RecordingsApiHandler
                        .invalidateRecordingCache(file.getAbsolutePath());
                } catch (Throwable ignored) {
                    // RecordingsApiHandler may not be loaded in every process.
                }
            } else {
                logWarn("Failed to delete: " + file.getAbsolutePath());
            }
        }

        if (deletedCount > 0) {
            // Files were removed: drop the reporting size/count TTL cache so the
            // storage card shows the freed space immediately. The reaper itself
            // reads the uncached variants (see scopedSizeForCategory), so this
            // only affects what the UI reports — never enforcement.
            invalidateCategorySizeCache(null);
            logInfo("Cleanup complete: deleted " + deletedCount + " files (" + formatSize(deletedSize) + ")"
                + (reapedFromInactive ? " — including orphan/legacy locations" : "")
                + (budgetExhausted ? " — bounded trim (budget=" + maxDeletes
                    + "/tick, still " + formatSize(currentSize - targetSize)
                    + " over; continues next pass)" : ""));
        }

        // Bounded-trim pass stops here: the orphan-sidecar sweep and CDR fallback
        // below both walk the ENTIRE tree (and CDR forks a deep external clean),
        // which is exactly the whole-tree I/O the budget exists to avoid while the
        // encoder is writing. They're pure housekeeping that the next idle/full
        // reap (or the HARD emergency path) still performs, so deferring them keeps
        // the mid-recording pass cheap without losing any cleanup.
        if (budgeted) {
            // Must still expire the trips DB-size cache if we dropped any rows,
            // exactly as the full path does at its tail — otherwise the next
            // getTripsSize() returns the stale (inflated) SUM and the gate keeps
            // firing with nothing to free. No-op for non-trips categories.
            if (tripsRowReaped) invalidateTripsDbSizeCache();
            return currentSize <= targetSize;
        }

        // Orphan-sidecar pass. The collection loop above accumulated every
        // anchor stem in `seenNames`; any sidecar (same-stem .json/.jpg/.srt
        // or aux-prefix `thumb_event_<stem>_a*`) whose stem isn't in that set
        // belongs to an anchor that was deleted in a prior cycle (or by an
        // app-side reset that didn't sweep sidecars). Reap them so per-actor
        // thumbnails / SRT / JSON fragments don't accumulate forever.
        // Skipped when the category has no sidecars or aux prefixes.
        if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
            // Convert anchor-name set to stems for sidecar comparison.
            Set<String> liveStems = new HashSet<>();
            for (String anchorName : seenNames) {
                liveStems.add(stemForName(anchorName, primaryExt));
            }
            // Anchors we just deleted are still in seenNames but their
            // sidecars have already been wiped above; safe to leave them in.
            int orphansDeleted = 0;
            long orphansFreed = 0;
            Set<String> seenSidecarPaths = new HashSet<>();
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) files = listFilesViaShell(dir);
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    // Sidecar candidates may match the primary prefix
                    // (event_xxx.json) OR an aux prefix (thumb_event_xxx_a17.jpg);
                    // both must be considered, hence nameMatchesCategoryPrefix.
                    if (namePrefix != null
                            && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                    String stem = null;
                    // Aux-prefix sibling first, since a thumb_event_xxx_a*.jpg
                    // also matches the .jpg sidecar branch — without this
                    // ordering, we'd parse stem as the full minus-".jpg" name
                    // and incorrectly conclude it's an orphan on every tick.
                    String matchedAux = null;
                    for (String aux : auxPrefixes) {
                        if (name.startsWith(aux)) { matchedAux = aux; break; }
                    }
                    if (matchedAux != null) {
                        // Per-actor thumb shape: "<aux><anchorStem>_a<id>[_<rel>].<ext>".
                        // The anchor stem itself can contain underscores
                        // (event_<date>_<time>), so we can't parse with
                        // indexOf('_', auxLen). Find the LAST "_a<digit>" run
                        // and treat everything between aux and that as stem.
                        int auxLen = matchedAux.length();
                        int actorMarker = lastIndexOfActorMarker(name, auxLen);
                        if (actorMarker > auxLen) {
                            stem = name.substring(auxLen, actorMarker);
                        }
                    }
                    // Same-stem sidecar (event_xxx.json/.srt/.jpg) — ALSO the fall-through
                    // for an aux-prefixed name that is NOT a per-actor thumb. Not every aux
                    // prefix is thumb-shaped: "dvr_" (OEM dashcam) is an aux prefix only so
                    // dvr_*.mp4 counts as a recordings ANCHOR, but its sidecars are SAME-STEM
                    // (dvr_<ts>.json from LocationSidecarWriter, dvr_<ts>.srt from SrtWriter —
                    // inferGeocodingFlow treats every non-event_ clip as "recording"). Those
                    // names carry no "_a<digit>" actor marker, so the aux branch above leaves
                    // stem==null and they were skipped forever: counted toward the limit by
                    // getDirectoriesTotalSize yet reaped by nothing once their .mp4 anchor was
                    // gone — a slow recordings leak that keeps the folder above its limit.
                    // Falling through to the same-stem parse recognizes dvr_<ts>.json/.srt as
                    // sidecars of stem dvr_<ts>. Safe for thumb_: real per-actor thumbs always
                    // have the actor marker (parsed above → stem set → this is skipped), and a
                    // live anchor's sidecars are still protected by the liveStems guard below.
                    if (stem == null) {
                        for (String ext : sidecarExts) {
                            if (name.endsWith(ext)) {
                                stem = name.substring(0, name.length() - ext.length());
                                break;
                            }
                        }
                    }
                    if (stem == null) continue;
                    if (!seenSidecarPaths.add(f.getAbsolutePath())) continue;
                    if (liveStems.contains(stem)) continue;  // anchor still around
                    long sz = f.length();
                    boolean ok = f.delete();
                    if (!ok) ok = deleteFileViaShell(f);
                    if (ok) {
                        orphansDeleted++;
                        orphansFreed += sz;
                        currentSize -= sz;
                    }
                }
            }
            if (orphansDeleted > 0) {
                // Independent of the anchor loop's deletedCount gate above: when
                // the anchor pass freed nothing but this pass did, that gate never
                // fires and the reported size would keep the reclaimed sidecar
                // bytes. These files are size-counted, so invalidate here too.
                invalidateCategorySizeCache(null);
                logInfo("Sidecar orphans reaped: " + orphansDeleted + " files (" + formatSize(orphansFreed) + ")");
            }
        }

        // If still over limit and the active dir lives on the SD card, fall
        // back to CDR cleanup to free up underlying SD-card space.
        if (currentSize > targetSize
            && sdCardAvailable
            && activeDir != null
            && sdCardPath != null
            && activeDir.getAbsolutePath().startsWith(sdCardPath)) {
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled()) {
                    logInfo("Overdrive cleanup insufficient on SD card — triggering CDR cleanup");
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("CDR fallback cleanup failed: " + e.getMessage());
            }
        }

        // If we dropped any trips DB rows above, expire the DB-size cache so the
        // next getTripsSize()/getTripsCount() re-queries the post-reap SUM rather
        // than returning the stale (inflated) value that keeps the limit gate
        // from ever converging.
        if (tripsRowReaped) {
            invalidateTripsDbSizeCache();
        }

        return currentSize <= targetSize;
    }

    /** Force the next {@link #getTripsSizeFromDbCached} read to re-query the DB. */
    private synchronized void invalidateTripsDbSizeCache() {
        cachedTripsDbSize = -1;
        cachedTripsDbSizeAt = 0;
    }
    
    /**
     * Find the last "_a<digit>" actor-id marker in a thumb filename. Returns
     * the offset of the underscore in "_a", or -1 if none found within
     * {@code [from, name.length())}. The anchor stem (e.g.
     * {@code event_<date>_<time>}) can contain underscores, so we cannot
     * use the first underscore after the aux prefix.
     */
    private static int lastIndexOfActorMarker(String name, int from) {
        for (int i = name.length() - 2; i >= from; i--) {
            if (name.charAt(i) != '_') continue;
            if (i + 1 >= name.length() || name.charAt(i + 1) != 'a') continue;
            // Require a digit after "_a" so we don't match arbitrary text.
            if (i + 2 < name.length() && Character.isDigit(name.charAt(i + 2))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find every aux-prefix sibling of an anchor stem within a directory.
     * For surveillance, an anchor {@code event_<base>.mp4} in {@code dir}
     * has aux siblings matching {@code thumb_event_<base>_*} — per-actor
     * thumbnails written by {@code SurveillanceEngineGpu.dispatchSegmentMetadata}.
     *
     * <p>Returns an empty list when {@code auxPrefixes} is empty, the dir
     * doesn't exist, or no matches are found.
     */
    /**
     * Build a stem -> aux-sibling index for one directory in a SINGLE listing,
     * so callers that need aux bytes for many anchors don't re-list the dir per
     * anchor (the O(N^2) the size/reap walk used to pay).
     *
     * <p>Produces exactly the same grouping {@link #findAuxiliarySiblings} would
     * return for each stem. That method's rule is
     * {@code name.startsWith(auxPrefix + stem + "_")}; inverted, a file named
     * {@code <auxPrefix><stem>_<rest>} contributes to key {@code <stem>}. We
     * recover the stem by stripping the matched aux prefix and then cutting at
     * the LAST {@code '_'}-delimited boundary that still leaves a non-empty
     * stem — but because a stem may itself contain underscores
     * (e.g. {@code event_20260106_184835}), we cannot simply cut at the first
     * one. Instead we register the file under EVERY candidate stem prefix, so a
     * lookup for the real anchor stem always hits. Extra keys are harmless:
     * they can only be matched by an anchor whose stem is literally that
     * prefix, which is the same file findAuxiliarySiblings would have matched.
     *
     * <p>Uses the SAME listing sequence as {@link #findAuxiliarySiblings}
     * ({@code dir.listFiles()}, then the shell-ls fallback), so on a flaky
     * FUSE-bridged volume this degrades exactly the way the per-anchor walk did:
     * {@code listFilesViaShell} returns an empty array (never null) when the
     * child fails or times out, which yields an empty index — the same
     * "no aux siblings found" answer the old code produced for every anchor in
     * that directory. Never returns null, so callers do not need a null branch;
     * the caller keeps one only as belt-and-braces.
     */
    private java.util.Map<String, List<File>> buildAuxiliaryIndex(File dir, String[] auxPrefixes) {
        if (dir == null || auxPrefixes == null || auxPrefixes.length == 0
                || !dir.isDirectory()) {
            return java.util.Collections.emptyMap();
        }
        File[] files = dir.listFiles();
        if (files == null) files = listFilesViaShell(dir);
        if (files == null) return java.util.Collections.emptyMap();   // defensive; not reachable today

        java.util.Map<String, List<File>> index = new java.util.HashMap<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            for (String aux : auxPrefixes) {
                if (!name.startsWith(aux)) continue;
                String rest = name.substring(aux.length());
                // rest == "<stem>_<suffix…>". Register under every prefix of
                // `rest` that ends at an '_' boundary, mirroring the
                // startsWith(aux + stem + "_") test for all possible stems.
                for (int i = rest.indexOf('_'); i >= 0; i = rest.indexOf('_', i + 1)) {
                    if (i == 0) continue;               // empty stem — cannot match
                    String stem = rest.substring(0, i);
                    List<File> hits = index.get(stem);
                    if (hits == null) {
                        hits = new ArrayList<>();
                        index.put(stem, hits);
                    }
                    hits.add(f);
                }
                break;   // first matching aux prefix wins, as in findAuxiliarySiblings
            }
        }
        return index;
    }

    private List<File> findAuxiliarySiblings(File dir, String[] auxPrefixes, String stem) {
        if (dir == null || auxPrefixes == null || auxPrefixes.length == 0
                || !dir.isDirectory()) {
            return java.util.Collections.emptyList();
        }
        List<File> hits = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) files = listFilesViaShell(dir);
        if (files == null) return hits;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            for (String aux : auxPrefixes) {
                // Match `<aux><stem>_…` so we don't accidentally swallow
                // an unrelated stem that happens to share a prefix.
                String wanted = aux + stem + "_";
                if (name.startsWith(wanted)) {
                    hits.add(f);
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * SOTA: Delete file via shell command when Java delete fails.
     */
    private boolean deleteFileViaShell(File file) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", file.getAbsolutePath()});
            // Bounded wait — a stuck `rm` on a bad FUSE-bridged external volume
            // would otherwise pin the reap (and any caller holding a per-category
            // cleanup lock) indefinitely. This is the per-file fork the boot reap
            // runs hundreds of times; without a bound, a single hung delete stalls
            // the entire walk. Returns -1 on timeout (treated as "not deleted",
            // matching a non-zero exit) and force-kills the child.
            int exitCode = waitForBounded(p, 4_000, "deleteFileViaShell(" + file.getName() + ")");
            return exitCode == 0;
        } catch (Exception e) {
            logWarn("deleteFileViaShell failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Run cleanup across every category. Each ensureXxxSpace self-acquires
     * its own per-category lock; we no longer hold a single big lock across
     * the four calls. That used to be done so the four passes "appeared
     * atomic" to peers, but no caller relies on that atomicity — the four
     * categories are independent volumes/limits. Removing the big lock lets
     * a per-recording ensureRecordingsSpace overlap with a periodic
     * ensureSurveillanceSpace, which is the common case.
     */
    public void runCleanup() {
        ensureRecordingsSpace(0);
        ensureSurveillanceSpace(0);
        ensureProximitySpace(0);
        ensureTripsSpace(0);
    }
    
    // ==================== Utility ====================
    
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
    
    public static long getMinLimitMb() {
        return MIN_LIMIT_MB;
    }
    
    /**
     * Static fallback ceiling. Use the instance methods
     * {@link #getEffectiveMaxLimitMb(StorageType)} / {@link #getMaxLimitMb(StorageType)}
     * for the live, volume-aware ceiling. These statics are kept only
     * for legacy callers that don't have an instance handy.
     */
    public static long getMaxLimitMb() {
        return MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbInternal() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.INTERNAL) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbSdCard() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.SD_CARD) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbUsb() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.USB) : MAX_LIMIT_MB_FALLBACK;
    }
    
    // ==================== Event-Driven Cleanup (SOTA) ====================
    
    /**
     * Called after a recording file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * This is the SOTA approach - cleanup after each file save rather than
     * only at recording start, preventing storage overflow during long sessions.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onRecordingFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(recordingsDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        // Gate destructive cleanup on encoder write state. If a recording is
        // mid-flight, deferring this delete burst is what keeps the SD card
        // available for the encoder's disk writer. The deferred queue is
        // drained the next time we observe encoder=idle, so nothing is lost.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            logDebug("Recording file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (recordingsCleanupLock) {
                try {
                    // Scoped to the active volume so a fallback to internal triggers
                    // (and bounds) on internal's pool + effective limit, not the
                    // combined cross-volume pool vs the raw external limit.
                    long currentSize = scopedSizeForCategory("recordings");
                    long limitBytes = scopedLimitBytesForCategory("recordings");

                    if (currentSize > limitBytes) {
                        logInfo("Recording file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        // Call ensureSpace directly: we already hold
                        // recordingsCleanupLock and already gated on
                        // isEncoderWriting at the top. Going through
                        // ensureRecordingsSpace would just re-take the
                        // (reentrant) lock and re-check the encoder gate.
                        ensureSpace("recordings", getReapableDirs("recordings"), recordingsDir,
                            namePrefixForCategory("recordings"),
                            limitBytes, 0);
                    } else {
                        logDebug("Recording file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async recording cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a surveillance event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onSurveillanceFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(surveillanceDir);

        // FIX: Removed broadcastRecentFiles() call that re-scanned ALL files modified
        // in the last 60 seconds. This caused duplicate MediaScanner broadcasts —
        // if two events saved 20 seconds apart, the second save re-broadcast the first.
        // Over days of parking, this list grows to hundreds of files, causing massive
        // CPU spikes on every new event. The specific file is already broadcast by
        // onFileSaved() → broadcastFile(file) before this method is called.

        // Defer destructive cleanup if encoder is mid-write. See onRecordingFileSaved
        // for rationale — SD card I/O contention against the encoder's disk writer
        // is what produces the freeze+skip artifact in the recorded MP4.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            logDebug("Surveillance file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (surveillanceCleanupLock) {
                try {
                    long currentSize = scopedSizeForCategory("surveillance");
                    long limitBytes = scopedLimitBytesForCategory("surveillance");

                    if (currentSize > limitBytes) {
                        logInfo("Surveillance file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                            namePrefixForCategory("surveillance"),
                            limitBytes, 0);
                    } else {
                        logDebug("Surveillance file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async surveillance cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a proximity event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onProximityFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(proximityDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            logDebug("Proximity file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (proximityCleanupLock) {
                try {
                    long currentSize = scopedSizeForCategory("proximity");
                    long limitBytes = scopedLimitBytesForCategory("proximity");

                    if (currentSize > limitBytes) {
                        logInfo("Proximity file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                            namePrefixForCategory("proximity"),
                            limitBytes, 0);
                    } else {
                        logDebug("Proximity file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async proximity cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a trip telemetry file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the telemetry recording thread.
     */
    public void onTripFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(tripsDir);

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_TRIPS);
            logDebug("Trip file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (tripsCleanupLock) {
                try {
                    // Scoped (parity with the other onXxxFileSaved handlers) so a
                    // trips fallback to internal triggers on internal's pool + cap.
                    // scopedSizeForCategory("trips") still uses the DB-cached size in
                    // normal mode (no FUSE walk).
                    long currentSize = scopedSizeForCategory("trips");
                    long limitBytes = scopedLimitBytesForCategory("trips");

                    if (currentSize > limitBytes) {
                        logInfo("Trip file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                            namePrefixForCategory("trips"),
                            tripsLimitMb * 1024 * 1024, 0);
                    } else {
                        logDebug("Trip file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async trips cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Fix directory permissions so UI app can read files.
     * Note: chmod doesn't work on Android FUSE filesystem, but we keep Java API calls.
     */
    private void fixDirectoryPermissions(File dir) {
        if (dir != null && dir.exists()) {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
    }
    
    /**
     * Make all .mp4 files in directory readable by all.
     * Note: chmod doesn't work on Android FUSE filesystem - rely on MediaStore instead.
     */
    private void makeFilesReadable(File dir) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) {
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File f : files) {
                f.setReadable(true, false);
            }
        }
    }
    
    /**
     * Make a single file readable by all users.
     * Note: chmod doesn't work on Android FUSE - rely on MediaStore for cross-UID access.
     */
    public void makeFileReadable(File file) {
        if (file == null || !file.exists()) return;
        file.setReadable(true, false);
    }
    
    /**
     * Force Android MediaScanner to index a file so it appears in MediaStore
     * and becomes visible to standard apps with READ_EXTERNAL_STORAGE.
     * 
     * CRITICAL: Both methods are required on BYD's Android 10:
     * - `am broadcast MEDIA_SCANNER_SCAN_FILE` refreshes the FUSE permission cache
     *   so that File.listFiles() on SD card paths can see the file. Without this,
     *   the RecordingsApiHandler's scanDirectory() gets incomplete file listings.
     * - `content insert` directly inserts into MediaStore for cross-UID visibility
     *   (needed for the UI app running as a different UID).
     */
    private void broadcastFile(File file) {
        if (file == null || !file.exists()) return;
        
        String path = file.getAbsolutePath();
        
        try {
            // Method 1: FUSE cache refresh via MediaScanner intent
            // Required for File.listFiles() to work on SD card FUSE paths
            Runtime.getRuntime().exec(new String[]{
                "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://" + path
            });
            
            // Method 2: Direct MediaStore insert for cross-UID visibility
            Runtime.getRuntime().exec(new String[]{
                "content", "insert",
                "--uri", "content://media/external/video/media",
                "--bind", "_data:s:" + path
            });
            
            logDebug("Broadcast file to MediaScanner: " + file.getName());
        } catch (Exception e) {
            logWarn("Failed to broadcast file: " + e.getMessage());
        }
    }
    
    /**
     * SOTA: Fix permissions and broadcast a single file after it's saved.
     * Call this immediately after closing a video file.
     * @param file The video file that was just saved
     */
    public void onFileSaved(File file) {
        if (file == null || !file.exists()) {
            logWarn("onFileSaved: file is null or doesn't exist");
            return;
        }
        
        logInfo("Processing saved file: " + file.getName() + " (" + formatSize(file.length()) + ")");

        // Invalidate the reporting size/count TTL cache: a clip just landed, so
        // the storage card must reflect it on the very next poll rather than up
        // to CATEGORY_STAT_CACHE_MS later. Clearing all categories (null) keeps
        // this correct without having to classify the file by prefix here —
        // the cost is one extra walk per finalized clip (every ~2 min at the
        // default segment duration), which is negligible next to the 10s UI poll
        // this cache exists to coalesce.
        invalidateCategorySizeCache(null);

        // 1. Make file readable by all (chmod 666)
        makeFileReadable(file);

        // 2. Broadcast to MediaScanner. This spawns shell processes
        // (am broadcast + content insert) which compete for I/O bandwidth.
        // While the encoder is mid-write, defer to the background cleanup
        // executor so the disk writer keeps priority. (Audit P2.)
        if (isEncoderWriting()) {
            final File f = file;
            asyncCleanupExecutor.execute(() -> {
                try { broadcastFile(f); } catch (Exception e) {
                    logWarn("Deferred broadcastFile error: " + e.getMessage());
                }
            });
        } else {
            broadcastFile(file);
        }

        // 3. Trigger appropriate cleanup based on directory
        String path = file.getAbsolutePath();
        if (path.contains(RECORDINGS_SUBDIR)) {
            onRecordingFileSaved();
        } else if (path.contains(SURVEILLANCE_SUBDIR)) {
            onSurveillanceFileSaved();
        } else if (path.contains(PROXIMITY_SUBDIR)) {
            onProximityFileSaved();
        } else if (path.contains(TRIPS_SUBDIR)) {
            onTripFileSaved();
        }
    }
    
    /**
     * Broadcast all recent files in a directory to MediaScanner.
     * @param dir Directory to scan
     * @param maxAgeMs Only broadcast files modified within this time (ms)
     */
    private void broadcastRecentFiles(File dir, long maxAgeMs) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files != null) {
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (now - f.lastModified() < maxAgeMs) {
                    broadcastFile(f);
                }
            }
        }
    }
    
    // ==================== Periodic Background Cleanup ====================
    
    /**
     * Start periodic cleanup for long recording sessions.
      * Runs every 30 seconds while storage work is active and performs an
      * hourly full integrity pass while idle.
     */
    public void startPeriodicCleanup() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            return;  // Already running
        }
        
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                // Same low-priority strategy as asyncCleanupExecutor — the
                // periodic tick must never preempt the disk writer.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanup");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // The constructor already queued a startup reap. Start the idle
        // integrity clock here so the first 30-second tick does not duplicate
        // that whole-library work while daemon initialization is still busy.
        lastPeriodicIntegrityAtMs = statClockMs();

        cleanupScheduler.scheduleWithFixedDelay(() -> {
            try {
                // Don't run un-gated cleanup before the encoder probe is wired.
                // Daemon-init ordering: startPeriodicCleanup() fires early
                // (before pipeline.init), so the first scheduled tick at
                // T+30s could land before the probe is bound and would treat
                // an active recording as "encoder idle" → run the destructive
                // cleanup right through the recording. (Audit P1.)
                if (!probeWired.get()) {
                    logDebug("Periodic cleanup tick skipped — encoder probe not wired yet");
                    return;
                }
                final long tickNowMs = statClockMs();
                final boolean encoderWriting = isEncoderWriting();
                final boolean activeSession = recordingActive.get()
                    || surveillanceActive.get()
                    || activeTripFilePath != null
                    || encoderWriting;
                final boolean deferredWork = !deferredCleanupDirs.isEmpty();
                final boolean fallbackWasActive = recordingsEnospcFallbackActive;
                final boolean integrityDue = isPeriodicIntegrityDue(
                    tickNowMs, lastPeriodicIntegrityAtMs);
                if (!shouldRunPeriodicMaintenance(activeSession, deferredWork,
                        fallbackWasActive, integrityDue)) {
                    return;
                }
                if (integrityDue) {
                    // Advance before I/O so a failed or degraded-volume pass
                    // cannot retry continuously on every 30-second tick.
                    lastPeriodicIntegrityAtMs = tickNowMs;
                }
                // Self-clear a stale ENOSPC fallback. recordingsEnospcFallbackActive
                // latches true when a mounted-but-full external volume redirects a
                // segment to internal, but it is only re-evaluated by the per-segment
                // recorder probe — so after a full-volume drive ends (recording stops,
                // no more probes), the flag stays true while parked even though
                // surveillance reaps / the user deletes files and the volume frees up.
                // While stale-true getActiveRecordingsStorageType() reports INTERNAL, so
                // the reaper scopes to internal-only and the real external recordings
                // pool is neither measured nor reaped against the configured external
                // limit until the next external recording session. Re-probe the
                // volume recordings is ACTUALLY configured to (cheap StatFs, ~200µs)
                // and clear the flag once it has room again, so this same tick's
                // recordings pass below re-scopes to that pool and enforces its limit.
                //
                // The flag is volume-agnostic (resolveTargetWithEnospcFallback sets it
                // for ANY mounted-but-full external recordings target), so we MUST probe
                // the configured volume — getUsbFreeSpace() for USB, getSdCardFreeSpace()
                // for SD. Probing SD only would leave a USB-configured user stuck-true
                // forever while parked, and would read the wrong volume's free space when
                // both cards are present.
                if (recordingsEnospcFallbackActive) {
                    long extFreeNow = (recordingsStorageType == StorageType.USB)
                        ? getUsbFreeSpace() : getSdCardFreeSpace();
                    if (extFreeNow >= ENOSPC_FALLBACK_RECOVER_BYTES) {
                        recordingsEnospcFallbackActive = false;
                        logInfo("ENOSPC-fallback cleared: " + recordingsStorageType
                            + " now has " + formatSize(extFreeNow)
                            + " free — recordings retention re-scoped to the external pool");
                    }
                }
                // Decide how aggressive this tick is allowed to be, based on
                // whether the encoder is mid-write.
                //
                // OLD POLICY (the bug): while recording, skip the ENTIRE pass
                // unless usage was >5% over a cap; otherwise mark-deferred and
                // return. But a continuous recorder parks ~1.9% over forever and
                // the deferred queue only drains at encoder-idle — which never
                // happens mid-drive — so in the 0–5% band NOTHING ever reaped and
                // the volume overflowed to ENOSPC ("periodic cleanup never frees
                // space"). The full delete-burst the skip was protecting against
                // (19 files / 118 MB → 2.8 s eglSwap stall) is now avoided a
                // different way: the per-category passes below run a BOUNDED trim
                // (≤RECORDING_TRIM_MAX_FILES/tick) while recording, which converges
                // the limit without starving the muxer's disk writer.
                //
                // This block now only: (1) computes the free-disk emergency
                // signal, (2) logs the over-limit state, and (3) decides whether
                // to run the whole-tree idle-only work (deferred drain + orphan
                // tmp sweep) — kept OUT of the steady-state recording path to
                // hold tick I/O low, but still run during a genuine emergency.
                boolean diskCritical = false;
                boolean emergencyMaintenance = false;

                // Scoped size/limit snapshot for recordings/surveillance/proximity,
                // measured ONCE per tick in the recording branch and reused by the
                // per-category passes below (-1 = "measure in the pass"). This avoids
                // a second uncached FUSE directory walk per category per tick while
                // recording. Trips is excluded: it's reconciled (orphan-row drop)
                // right before its pass and is DB-SUM-cached, so it re-reads there.
                long recBytesSnap = -1, survBytesSnap = -1, proxBytesSnap = -1;
                long recLimSnap = -1, survLimSnap = -1, proxLimSnap = -1;

                if (encoderWriting) {
                    // Per-dir over-limit ratio (per-category, not MAX(limits)/20),
                    // scoped to each category's ACTIVE volume + effective limit so
                    // an external→internal fallback is measured on internal's pool.
                    long recBytes = scopedSizeForCategory("recordings");
                    long survBytes = scopedSizeForCategory("surveillance");
                    long tripsBytes = scopedSizeForCategory("trips");
                    long proxBytes = scopedSizeForCategory("proximity");
                    long recLim = scopedLimitBytesForCategory("recordings");
                    long survLim = scopedLimitBytesForCategory("surveillance");
                    long tripsLim = scopedLimitBytesForCategory("trips");
                    long proxLim = scopedLimitBytesForCategory("proximity");
                    // Hand these same measurements to the per-category passes.
                    recBytesSnap = recBytes;   recLimSnap = recLim;
                    survBytesSnap = survBytes; survLimSnap = survLim;
                    proxBytesSnap = proxBytes; proxLimSnap = proxLim;
                    boolean recHard  = recLim   > 0 && recBytes   > recLim   * 21 / 20;  // >5% over OWN limit
                    boolean survHard = survLim  > 0 && survBytes  > survLim  * 21 / 20;
                    boolean tripsHard= tripsLim > 0 && tripsBytes > tripsLim * 21 / 20;
                    boolean proxHard = proxLim  > 0 && proxBytes  > proxLim  * 21 / 20;

                    // Free-disk emergency: if ANY active volume is critically
                    // low, continuing to write is going to fail anyway. The min
                    // across all categories' active volumes covers the case where
                    // surveillance is on USB while recordings are on internal.
                    // Probe each category's ACTIVE volume (activeTypeForCategory),
                    // not the raw configured volatiles — mirroring scopedSizeForCategory.
                    long minFree = Long.MAX_VALUE;
                    boolean activeExternalDown = false;
                    Set<StorageType> activeTypes = new HashSet<>();
                    activeTypes.add(activeTypeForCategory("recordings"));
                    activeTypes.add(activeTypeForCategory("surveillance"));
                    activeTypes.add(activeTypeForCategory("trips"));
                    activeTypes.add(activeTypeForCategory("proximity"));
                    for (StorageType t : activeTypes) {
                        long f;
                        switch (t) {
                            case SD_CARD: f = getSdCardFreeSpace(); break;
                            case USB:     f = getUsbFreeSpace();    break;
                            case INTERNAL:
                            default:      f = getInternalFreeSpace(); break;
                        }
                        if (f <= 0) {
                            // A 0 reading on an ACTIVE external volume means it is
                            // unmounted/inaccessible (or genuinely 0-free) — treat
                            // that as critical. Internal 0 is a transient StatFs
                            // hiccup, not a definitive signal, so don't latch on it.
                            if (t == StorageType.SD_CARD || t == StorageType.USB) {
                                activeExternalDown = true;
                            }
                            continue;
                        }
                        if (f < minFree) minFree = f;
                    }
                    long sdFree = (minFree == Long.MAX_VALUE) ? 0 : minFree;
                    diskCritical = activeExternalDown
                        || (sdFree > 0 && sdFree < 200L * 1024 * 1024);  // <200MB free

                    boolean hardOverlimit = recHard || survHard || tripsHard || proxHard || diskCritical;
                    emergencyMaintenance = hardOverlimit;
                    if (hardOverlimit) {
                        // Emergency: log it AND run the idle-only whole-tree work
                        // (orphan tmp sweep) right now — the disk is about to
                        // ENOSPC, so the extra I/O is the lesser evil. The
                        // per-category passes below run a FULL reap (diskCritical
                        // upgrades the bounded trim to full, see the force flag).
                        logWarn("Periodic cleanup forced during recording: "
                            + "rec=" + formatSize(recBytes) + "/" + formatSize(recLim) + (recHard ? " HARD" : "")
                            + " surv=" + formatSize(survBytes) + "/" + formatSize(survLim) + (survHard ? " HARD" : "")
                            + " trips=" + formatSize(tripsBytes) + "/" + formatSize(tripsLim) + (tripsHard ? " HARD" : "")
                            + " prox=" + formatSize(proxBytes) + "/" + formatSize(proxLim) + (proxHard ? " HARD" : "")
                            + " sdFree=" + formatSize(sdFree) + (diskCritical ? " CRITICAL" : ""));
                    }
                    // Soft state (over cap ≤5%, disk healthy): fall through to the
                    // per-category passes, which run a BOUNDED trim. We intentionally
                    // SKIP drainDeferredCleanupIfDue + sweepOrphanTempFiles here to
                    // keep steady-state recording-tick I/O low; both run at idle.
                } else {
                    // Encoder idle: drain any deferred work first so storage limits
                    // re-converge after a long recording. Sweep orphan partials only
                    // on the hourly integrity pass; startup and hard-emergency paths
                    // retain their immediate sweeps.
                    drainDeferredCleanupIfDue();
                    boolean fallbackRecovered = fallbackWasActive
                        && !recordingsEnospcFallbackActive;
                    if (!shouldRunFullPeriodicMaintenance(
                            activeSession, integrityDue, fallbackRecovered)) {
                        // A deferred-only tick already drained its categories. An
                        // unresolved fallback-only tick already performed the cheap
                        // free-space probe. Neither needs the four full scans below.
                        return;
                    }
                }

                if (shouldSweepOrphanTempFiles(integrityDue, emergencyMaintenance)) {
                    sweepOrphanTempFiles();
                }

                // Standard periodic pass (catches dirs that grew past the limit
                // while the daemon was offline, after a manual limit change, OR —
                // the field "cleanup never frees space" case — that sit over cap
                // for the whole drive because the encoder never goes idle).
                //
                // Each category reaps via runPeriodicCategoryCleanup, which uses
                // cleanupGateDuringRecording to choose FULL / BOUNDED / DEFER and
                // then calls ensureSpace DIRECTLY. This is the fix for the gate
                // disagreement: the old code called ensureXxxSpace here, which
                // re-ran deferIfEncoderBusy and silently re-deferred anything ≤5%
                // over cap — so a continuously-recording car (parked ~1.9% over)
                // never reaped while driving. Reaping directly with a small
                // per-tick budget converges the limit without a disk-writer-
                // starving burst.
                //
                // Scope size+limit to each category's ACTIVE volume + effective
                // (clamped) cap, exactly like the HARD branch above and ensureSpace
                // itself. The scoped helpers are no-ops in normal
                // (active==configured) mode, so non-fallback behavior is unchanged.
                // diskCritical (only ever set while recording) upgrades a bounded
                // trim to a FULL reap: when the volume is about to ENOSPC we want
                // every over-cap category taken all the way down to its limit (and
                // the CDR/external fallback inside ensureSpace re-enabled), not just
                // a few-file trim. Harmless at idle (forceFull is false there).
                final boolean forceFull = diskCritical;

                // PHYSICAL-VOLUME emergency reap — runs BEFORE the per-category
                // passes. This is the fix for the cap-oversubscription deadlock: when
                // the SUM of per-category caps exceeds a shared volume's real capacity,
                // the volume fills to 0-free while every category is under its OWN cap,
                // so the per-category passes below (and the diskCritical/forceFull
                // upgrade) free NOTHING — recordings spill to internal via the ENOSPC
                // fallback and "periodic cleanup never frees space". This pass ignores
                // per-category caps and evicts the globally-oldest clips on any
                // physically-full volume until free space recovers, then the
                // per-category passes run on the freed volume (mostly no-ops). Cheap
                // when healthy: a StatFs free probe per active volume, no FUSE walk
                // unless a volume is actually below the floor. Not gated on
                // encoderWriting — it self-bounds its delete burst while recording.
                runPhysicalFreeSpaceEmergencyReap();

                synchronized (recordingsCleanupLock) {
                    runPeriodicCategoryCleanup("recordings", DEFERRED_RECORDINGS, recordingsDir,
                        forceFull, recBytesSnap, recLimSnap);
                }

                synchronized (surveillanceCleanupLock) {
                    runPeriodicCategoryCleanup("surveillance", DEFERRED_SURVEILLANCE, surveillanceDir,
                        forceFull, survBytesSnap, survLimSnap);
                }

                synchronized (tripsCleanupLock) {
                    // Reconcile orphan rows first (same as ensureTripsSpace) so the
                    // gate's DB-SUM size reflects only bytes that actually exist —
                    // else a vanished-file row keeps the gate firing with nothing
                    // to delete. Pass -1 so the size is re-read AFTER the reconcile
                    // (a pre-reconcile snapshot would be inflated by dropped rows).
                    reconcileOrphanTripRows();
                    runPeriodicCategoryCleanup("trips", DEFERRED_TRIPS, tripsDir, forceFull, -1, -1);
                }

                // Proximity must be swept here too. It was historically reaped
                // ONLY reactively — on the next proximity recording start
                // (ProximityRecordingHandler) or an explicit limit change
                // (runCleanup). Once proximity events stop, the dir parks above
                // its limit forever (field: 476/500 MB = 95%, well over the 90%
                // threshold) because nothing periodic ever revisits it. Mirror
                // the other three categories so the limit converges regardless
                // of whether new proximity clips are still being written.
                synchronized (proximityCleanupLock) {
                    runPeriodicCategoryCleanup("proximity", DEFERRED_PROXIMITY, proximityDir,
                        forceFull, proxBytesSnap, proxLimSnap);
                }
            } catch (Exception e) {
                logWarn("Periodic cleanup error: " + e.getMessage());
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started periodic storage cleanup (interval=" + CLEANUP_INTERVAL_SECONDS + "s)");
    }

    static boolean shouldRunPeriodicMaintenance(boolean activeSession,
                                                boolean deferredWork,
                                                boolean fallbackActive,
                                                boolean integrityDue) {
        return activeSession || deferredWork || fallbackActive || integrityDue;
    }

    static boolean isPeriodicIntegrityDue(long nowMs, long lastRunMs) {
        return lastRunMs <= 0L || (nowMs - lastRunMs) >= PERIODIC_INTEGRITY_INTERVAL_MS;
    }

    static boolean shouldRunFullPeriodicMaintenance(boolean activeSession,
                                                    boolean integrityDue,
                                                    boolean fallbackRecovered) {
        return activeSession || integrityDue || fallbackRecovered;
    }

    static boolean shouldSweepOrphanTempFiles(boolean integrityDue,
                                              boolean emergencyMaintenance) {
        return integrityDue || emergencyMaintenance;
    }
    
    /**
     * Drain any cleanup that was deferred because the encoder was mid-write.
     * Called from the periodic tick AND from each onXxxFileSaved path, so a
     * deferred backlog never sits indefinitely. Safe to call when the queue
     * is empty (early-exits on empty set).
     */
    private void drainDeferredCleanupIfDue() {
        if (deferredCleanupDirs.isEmpty()) return;
        if (isEncoderWriting()) return;  // still busy, try later
        // Snapshot+clear so a concurrent add (e.g. a periodic tick that fires
        // while we're draining) doesn't lose the new mark.
        java.util.Set<String> toRun = new java.util.HashSet<>(deferredCleanupDirs);
        deferredCleanupDirs.removeAll(toRun);
        logInfo("Draining deferred cleanup: " + toRun);

        // Per-dir try/catch: a failure on one dir must NOT cause the others
        // to be re-marked. The previous catch-all re-added the entire toRun
        // snapshot on any exception, including dirs that had already been
        // cleaned successfully — wasting the next tick on idempotent re-runs
        // (audit P1).
        // Same direct-ensureSpace pattern as onXxxFileSaved: we already
        // hold the per-category lock and already gated on isEncoderWriting
        // at the top. Going through ensureXxxSpace would re-take the
        // (reentrant) lock and re-check the encoder gate.
        if (toRun.contains(DEFERRED_RECORDINGS)) {
            try {
                synchronized (recordingsCleanupLock) {
                    // Scoped to the active volume (internal during a fallback) so the
                    // drain actually fires when internal is over its effective cap.
                    if (scopedSizeForCategory("recordings") > scopedLimitBytesForCategory("recordings")) {
                        ensureSpace("recordings", getReapableDirs("recordings"), recordingsDir,
                            namePrefixForCategory("recordings"), recordingsLimitMb * 1024 * 1024, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred recordings cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            }
        }
        if (toRun.contains(DEFERRED_SURVEILLANCE)) {
            try {
                synchronized (surveillanceCleanupLock) {
                    if (scopedSizeForCategory("surveillance") > scopedLimitBytesForCategory("surveillance")) {
                        ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                            namePrefixForCategory("surveillance"), surveillanceLimitMb * 1024 * 1024, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred surveillance cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            }
        }
        if (toRun.contains(DEFERRED_PROXIMITY)) {
            try {
                synchronized (proximityCleanupLock) {
                    if (scopedSizeForCategory("proximity") > scopedLimitBytesForCategory("proximity")) {
                        ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                            namePrefixForCategory("proximity"), proximityLimitMb * 1024 * 1024, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred proximity cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            }
        }
        if (toRun.contains(DEFERRED_TRIPS)) {
            try {
                synchronized (tripsCleanupLock) {
                    if (scopedSizeForCategory("trips") > scopedLimitBytesForCategory("trips")) {
                        ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                            namePrefixForCategory("trips"), tripsLimitMb * 1024 * 1024, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred trips cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_TRIPS);
            }
        }
    }

    /**
     * Raw (unscoped) configured limit in BYTES for a category — the value the
     * direct {@link #ensureSpace} callers pass. {@code ensureSpace} re-scopes/clamps
     * it to the active volume internally, so we hand it the raw configured cap here.
     */
    private long rawLimitBytesForCategory(String category) {
        switch (category) {
            case "recordings":   return recordingsLimitMb   * 1024 * 1024;
            case "surveillance": return surveillanceLimitMb * 1024 * 1024;
            case "proximity":    return proximityLimitMb    * 1024 * 1024;
            case "trips":        return tripsLimitMb         * 1024 * 1024;
            default:             return 0;
        }
    }

    /**
     * One category's periodic-cleanup pass. Caller MUST hold the category's
     * cleanup lock. Mirrors the old per-category block but routes through
     * {@link #cleanupGateDuringRecording} so it reaps DIRECTLY (FULL or BOUNDED)
     * instead of bouncing through {@code ensureXxxSpace}, which would re-defer
     * anything ≤5% over cap while recording — the deadlock this fix removes.
     *
     * @param category    "recordings" | "surveillance" | "proximity" | "trips"
     * @param deferredKey the matching DEFERRED_* key for the idle-drain queue
     * @param activeDir   the live write dir for the category (snapshotted by caller)
     * @param forceFull   when true (disk-critical emergency), reap FULLY even while
     *                    recording instead of a bounded trim — the disk is about to
     *                    ENOSPC, so the extra I/O is the lesser evil.
     * @param preSize     a scoped size already measured this tick, or {@code < 0} to
     *                    measure it here. Lets the recording branch share its single
     *                    {@code scopedSizeForCategory} walk (an uncached FUSE
     *                    directory scan) instead of repeating it — the reaper still
     *                    does its own authoritative re-measure if a gate fires.
     * @param preLimit    a scoped limit already measured this tick, or {@code < 0}.
     */
    private void runPeriodicCategoryCleanup(String category, String deferredKey,
                                            File activeDir, boolean forceFull,
                                            long preSize, long preLimit) {
        // Scope size+limit to the ACTIVE volume + effective (clamped) cap. No-op in
        // normal mode; during an external→internal fallback it measures internal's
        // pool vs internal's cap (see ensureSpace's own scoping). Reuse the caller's
        // snapshot when given (avoids a second FUSE walk per category per tick).
        long currentSize = (preSize >= 0) ? preSize : scopedSizeForCategory(category);
        long limitBytes = (preLimit >= 0) ? preLimit : scopedLimitBytesForCategory(category);
        if (limitBytes <= 0 || currentSize <= limitBytes * 0.9) {  // 90% wake threshold
            return;
        }
        int decision = cleanupGateDuringRecording(deferredKey, currentSize, limitBytes);
        if (decision == REAP_DEFER) {
            // Under cap while recording (90–100% band): nothing over the limit to
            // free yet; the gate already marked it deferred for the next idle drain.
            // (This matches the old recording-time behavior, which also deferred
            // everything ≤5% over while the encoder was writing.)
            return;
        }
        // diskCritical emergency overrides the bounded trim — take it all the way down.
        boolean bounded = (decision == REAP_BOUNDED) && !forceFull;
        logInfo("Periodic cleanup: " + category + " at "
            + formatSize(currentSize) + "/" + formatSize(limitBytes)
            + (bounded ? " — bounded trim while recording (≤" + RECORDING_TRIM_MAX_FILES
                + " files/tick)" : (forceFull && decision == REAP_BOUNDED
                    ? " — full reap (disk critical)" : "")));
        // Reserve 50MB headroom (parity with the old ensureXxxSpace(50MB) calls).
        // Pass the RAW configured cap; ensureSpace re-scopes/clamps it to the
        // active volume just like every other caller.
        ensureSpace(category, getReapableDirs(category), activeDir,
            namePrefixForCategory(category),
            rawLimitBytesForCategory(category), 50 * 1024 * 1024,
            bounded ? RECORDING_TRIM_MAX_FILES : UNLIMITED_REAP);
    }

    /** Live free bytes on the volume backing {@code type} (StatFs availableBytes). */
    private long freeBytesForType(StorageType type) {
        switch (type) {
            case SD_CARD: return getSdCardFreeSpace();
            case USB:     return getUsbFreeSpace();
            case INTERNAL:
            default:      return getInternalFreeSpace();
        }
    }

    /** Filesystem path prefix that identifies files physically residing on {@code type}.
     *  Null when an external volume isn't currently mounted (no path resolved). */
    private String volumeRootForType(StorageType type) {
        switch (type) {
            case SD_CARD: return sdCardPath;
            case USB:     return usbPath;
            case INTERNAL:
            default:      return INTERNAL_VOLUME_ROOT;
        }
    }

    /**
     * PHYSICAL-VOLUME emergency reaper — the fix for the cap-oversubscription
     * deadlock (see {@link #PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES}).
     *
     * <p>The per-category reaper ({@link #runPeriodicCategoryCleanup} /
     * {@link #cleanupGateDuringRecording}) compares each category ONLY against its
     * own cap, so when the SUM of caps oversubscribes a shared volume, the volume
     * fills to 0-free while every category sits under its own cap and NOTHING is
     * eligible to reap. This method ignores per-category caps entirely: it pools the
     * finalized media clips PHYSICALLY ON {@code volume} across
     * {@link #PHYSICAL_REAP_CATEGORIES}, sorts them STRICTLY oldest-first (global,
     * cross-category — the chosen policy: the single oldest clip on the card goes
     * first regardless of which category it belongs to), and deletes until live free
     * space reaches {@code targetFreeBytes} (or a per-tick budget is hit while the
     * encoder is writing). It does NOT touch per-category caps or the trips DB.
     *
     * <p>Safety: never deletes the encoder's currently-open output (or its {@code
     * .tmp}), never deletes a {@code .tmp} newer than the 10-min grace window, never
     * deletes the in-flight trip file, and only ever deletes finalized anchors +
     * their sidecars. Caller MUST hold all the category cleanup locks (acquired in a
     * fixed order by {@link #runPhysicalFreeSpaceEmergencyReap}) to serialize against
     * the per-category passes and the recorder pre-flight.
     *
     * @return bytes freed on the volume (sum of deleted file lengths).
     */
    private long reapForPhysicalFreeSpace(StorageType volume, long targetFreeBytes) {
        final String volumeRoot = volumeRootForType(volume);
        if (volumeRoot == null) {
            // External volume not currently path-resolved (unmounted mid-swap) —
            // nothing safe to scope; the next tick retries once it re-resolves.
            return 0;
        }

        // Probe the live encoder output so we never unlink the file (or its .tmp)
        // the muxer is currently writing into. Mirrors wipeMediaCategory's probe,
        // but WITHOUT force-stopping the recorder — this is steady-state retention,
        // not a user wipe, so we must not interrupt an active recording.
        String activeEncoderPath = null;
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.getRecorder() != null) {
                com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                    pipeline.getRecorder().getEncoder();
                if (enc != null) activeEncoderPath = enc.getCurrentOutputPath();
            }
        } catch (Throwable t) {
            logWarn("Physical-free reap: encoder-path probe threw: " + t.getMessage());
        }
        final String protEncoderPath = activeEncoderPath;
        final String protEncoderTmpPath = (activeEncoderPath != null) ? activeEncoderPath + ".tmp" : null;
        final String protectedTripPath = activeTripFilePath;  // belt-and-suspenders; trips excluded anyway
        final long tmpGraceCutoff = System.currentTimeMillis() - (10L * 60L * 1000L);

        // Pool finalized anchors physically on this volume, tagged with their
        // category (so the right sidecar/aux extensions are reaped alongside).
        List<ReapCandidate> pool = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (String category : PHYSICAL_REAP_CATEGORIES) {
            String primaryExt = primaryExtensionForCategory(category);
            String namePrefix = namePrefixForCategory(category);
            String[] auxPrefixes = auxiliaryPrefixesForCategory(category);
            for (File dir : getReapableDirs(category)) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                // Only dirs physically on the target volume.
                if (!dir.getAbsolutePath().startsWith(volumeRoot)) continue;
                File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
                if (files == null) files = listFilesByExt(dir, primaryExt);
                if (files == null) continue;
                for (File f : files) {
                    if (f == null || !f.isFile()) continue;
                    String name = f.getName();
                    if (namePrefix != null
                            && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;
                    // De-dup by absolute path so a clip listed under two overlapping
                    // dir entries (active == one of the all-dirs) isn't queued twice.
                    if (!seenPaths.add(f.getAbsolutePath())) continue;
                    pool.add(new ReapCandidate(f, category));
                }
            }
        }

        if (pool.isEmpty()) {
            logWarn("Physical-free reap: " + volume + " critically low ("
                + formatSize(freeBytesForType(volume)) + " free) but found NO reapable "
                + "clips on the volume — listing likely failed (transient unmount?); retry next tick");
            return 0;
        }

        // STRICT oldest-first, globally across categories.
        Collections.sort(pool, (a, b) -> Long.compare(a.file.lastModified(), b.file.lastModified()));

        boolean budgeted = isEncoderWriting();
        long freedTotal = 0;
        int deletedAnchors = 0;
        // Budget bounds costly delete WORK (each anchor may fork an `rm` that blocks
        // up to 4s), not just successful frees. On a FUSE-bridged SD under I/O
        // contention — which coincides with isEncoderWriting()==true — File.delete()
        // and the shell fallback can repeatedly return 0; counting only successes
        // would let the loop walk the entire pool, serially forking `rm` and starving
        // the muxer on the same volume. Count every candidate that reaches the delete
        // call (i.e. passed the cheap protection-skips) against the per-tick budget.
        int attempted = 0;
        int consecutiveFails = 0;
        // Wall-clock cap on the time this invocation holds the nested cleanup locks.
        // A degenerate FUSE volume can make each delete fork an `rm` that blocks up to
        // 4 s, so the file-count budget alone permits a ~32 s lock hold that would stall
        // the surveillance trigger path (it now resolves its dir under surveillanceCleanupLock).
        final long reapDeadlineMs = System.currentTimeMillis() + PHYSICAL_REAP_MAX_LOCK_HOLD_MS;
        for (ReapCandidate c : pool) {
            if (freeBytesForType(volume) >= targetFreeBytes) break;
            // Stop once this invocation has spent its time budget — resume next tick.
            // Checked AFTER at least the first candidate so a slow single delete still
            // makes progress; cheap skips below don't consume the budget meaningfully.
            if (attempted > 0 && System.currentTimeMillis() >= reapDeadlineMs) {
                logInfo("Physical-free reap: hit " + PHYSICAL_REAP_MAX_LOCK_HOLD_MS
                    + "ms lock-hold budget on " + volume + " after " + attempted
                    + " delete attempt(s) — continues next tick");
                break;
            }
            // Re-sample the (cheap, lock-free) encoder state once the muxer-protection
            // budget would bind: an encoder that flips idle->writing mid-reap (e.g. a
            // continuous-segment rollover that bypasses the lock-blocking recorder pre-
            // flight) must still be honored, or the per-tick cap goes permanently dead
            // and we walk the whole pool while the muxer contends for the same FUSE
            // volume. Whichever cap is active (writing vs idle), it also bounds the
            // wasted work — forking a doomed `rm` per anchor — on a degenerate volume.
            int cap = (budgeted || (budgeted = isEncoderWriting()))
                ? PHYSICAL_REAP_MAX_FILES : PHYSICAL_REAP_MAX_FILES_IDLE;
            if (attempted >= cap) {
                logInfo("Physical-free reap: bounded to " + cap
                    + " delete attempts/tick (" + (budgeted ? "recording" : "idle")
                    + ") — continues next tick");
                break;
            }
            // A run of zero-free deletes signals a read-only/EROFS or vanished volume
            // where every unlink is a no-op; stop forking doomed rm's over the rest of
            // the pool (the free target can never be reached when nothing frees).
            if (consecutiveFails >= PHYSICAL_REAP_MAX_CONSEC_FAILS) {
                logWarn("Physical-free reap: " + PHYSICAL_REAP_MAX_CONSEC_FAILS
                    + " consecutive deletes freed nothing on " + volume
                    + " (read-only/vanished volume?) — aborting reap, retry next tick");
                break;
            }
            String absPath = c.file.getAbsolutePath();
            // Never the live encoder output / its .tmp companion.
            if (protEncoderPath != null
                    && (absPath.equals(protEncoderPath) || absPath.equals(protEncoderTmpPath))) {
                continue;
            }
            // Never the in-flight trip file (defensive; trips not in the pool).
            if (protectedTripPath != null
                    && (protectedTripPath.equals(absPath) || protectedTripPath.equals(absPath + ".tmp"))) {
                continue;
            }
            // Never a fresh .tmp partial a writer may still hold open.
            if (c.file.getName().endsWith(".tmp") && c.file.lastModified() > tmpGraceCutoff) {
                continue;
            }
            attempted++;
            long freed = deleteAnchorAndSidecars(c.file, c.category, reapDeadlineMs);
            if (freed > 0) {
                freedTotal += freed;
                deletedAnchors++;
                consecutiveFails = 0;
            } else {
                consecutiveFails++;
            }
        }

        if (deletedAnchors > 0) {
            // Whole finalized clips (+ their size-counted sidecars) are gone, so the
            // reported size/count cache must drop. Once, after the loop — see the
            // note in deleteAnchorAndSidecars. This is REQUIRED here: the emergency
            // reap runs before runPeriodicCategoryCleanup on the same tick, and that
            // pass's own invalidation is gated on ITS deletedCount, which is zero
            // precisely because this pass already freed the space. Categories are
            // mixed across the candidate list, so clear all (null).
            //
            // LOCKING: runPhysicalFreeSpaceEmergencyReap holds ALL THREE cleanup
            // locks (recordings→surveillance→proximity) across this call. Safe
            // because categoryStatCacheLock is a LEAF — invalidateCategorySizeCache
            // only bumps a counter and backdates map entries, acquiring nothing
            // else, and the walk in refreshCategoryStats deliberately runs off-lock.
            // So there is no cleanupLock↔categoryStatCacheLock cycle.
            invalidateCategorySizeCache(null);
            logInfo("Physical-free reap: freed " + formatSize(freedTotal) + " across "
                + deletedAnchors + " clip(s) on " + volume + " — "
                + formatSize(freeBytesForType(volume)) + " free now"
                + (budgeted ? " (bounded trim while recording)" : ""));
        }
        return freedTotal;
    }

    /** A finalized media clip queued for the physical-free reaper, with its category
     *  so the correct sidecar/aux companions are removed alongside the anchor. */
    private static final class ReapCandidate {
        final File file;
        final String category;
        ReapCandidate(File file, String category) { this.file = file; this.category = category; }
    }

    /**
     * Delete one finalized anchor plus its same-stem sidecars and aux-prefix
     * siblings, and drop the matching index/cache rows. Mirrors the per-file work
     * inside {@link #ensureSpace}'s delete loop, factored out for the physical-free
     * reaper. Caller is responsible for in-flight / grace-window protection and for
     * holding the category lock. Returns total bytes freed (anchor + companions).
     */
    private long deleteAnchorAndSidecars(File file, String category) {
        return deleteAnchorAndSidecars(file, category, Long.MAX_VALUE);
    }

    /**
     * As {@link #deleteAnchorAndSidecars(File, String)} but bounds the per-anchor
     * lock-hold so a single anchor on a degenerate/hung FUSE volume can't fork many
     * serial 4 s {@code rm}s while all three cleanup locks are held — the exact
     * overrun that would breach {@link #PHYSICAL_REAP_MAX_LOCK_HOLD_MS} and stall the
     * surveillance trigger thread (it resolves its event dir under
     * surveillanceCleanupLock). Two bounds:
     *  - {@code deadlineMs}: once exceeded, the slow shell fallback is skipped for the
     *    remaining sidecars/aux this tick (in-process {@code File.delete()} only — a
     *    hung volume won't free them either, so forking more doomed 4 s rm's only pads
     *    the hold); the reap simply resumes on the next 30 s tick.
     *  - hung-anchor signal: if the anchor itself only deleted via the shell fallback
     *    (its in-process {@code File.delete()} failed), the volume is in trouble, so
     *    its companions also skip the shell fallback this tick.
     */
    private long deleteAnchorAndSidecars(File file, String category, long deadlineMs) {
        final String primaryExt = primaryExtensionForCategory(category);
        final String[] sidecarExts = sidecarExtensionsForCategory(category);
        final String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        long fileSize = file.length();
        boolean deleted = file.delete();
        // Whether the anchor's fast in-process unlink failed and we had to fall through
        // to (or are about to skip) the bounded shell `rm`. A failed fast unlink is the
        // hung/degenerate-volume signal: don't fork serial 4 s rm's for the companions.
        boolean fastUnlinkFailed = !deleted;
        if (!deleted) deleted = deleteFileViaShell(file);
        if (!deleted) {
            logWarn("Physical-free reap: failed to delete " + file.getAbsolutePath());
            return 0;
        }
        long freed = fileSize;
        logInfo("Deleted old file: " + file.getAbsolutePath() + " (" + formatSize(fileSize)
            + ") — physical-free emergency reap [" + category + "]");

        if (file.getName().endsWith(".mp4")) {
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance()
                    .removeByPath(file.getAbsolutePath());
            } catch (Throwable ignored) {}
        }
        if (sidecarExts.length > 0) {
            String stem = stemForName(file.getName(), primaryExt);
            for (String ext : sidecarExts) {
                File sidecar = new File(file.getParentFile(), stem + ext);
                if (sidecar.exists()) {
                    long sz = sidecar.length();
                    boolean sd = sidecar.delete();
                    // Skip the bounded shell fallback once the lock-hold budget is spent
                    // or the anchor signalled a hung volume — either way forking another
                    // 4 s `rm` only pads the hold without freeing space on a wedged FUSE.
                    if (!sd && !fastUnlinkFailed && System.currentTimeMillis() < deadlineMs) {
                        sd = deleteFileViaShell(sidecar);
                    }
                    if (sd) freed += sz;
                }
            }
        }
        if (auxPrefixes.length > 0) {
            String stem = stemForName(file.getName(), primaryExt);
            for (File aux : findAuxiliarySiblings(file.getParentFile(), auxPrefixes, stem)) {
                long sz = aux.length();
                boolean ad = aux.delete();
                if (!ad && !fastUnlinkFailed && System.currentTimeMillis() < deadlineMs) {
                    ad = deleteFileViaShell(aux);
                }
                if (ad) freed += sz;
            }
        }
        try {
            com.overdrive.app.server.RecordingsApiHandler.invalidateRecordingCache(file.getAbsolutePath());
        } catch (Throwable ignored) {}
        // NOTE: the reported size/count cache is invalidated ONCE by the caller
        // after its delete loop (see reapForPhysicalFreeSpace), not here per file.
        // Per-file would also be correct but each call bumps statInvalidationSeq,
        // and a loop deleting hundreds of clips would repeatedly supersede any
        // concurrent background walk, forcing it to republish as stale and making
        // the next read re-walk for no benefit.
        return freed;
    }

    /**
     * Sum the bytes of finalized media physically residing on {@code volumeRoot}
     * across {@link #PHYSICAL_REAP_CATEGORIES} — i.e. exactly the pool the
     * physical-free reaper is allowed to delete. Used by
     * {@link #runPhysicalFreeSpaceEmergencyReap} to decide whether reaping media can
     * plausibly recover the free-space deficit, so a volume that is full from
     * NON-media usage (OS / maps / OTA staging / logs on INTERNAL) is left alone
     * instead of progressively bleeding away in-cap user clips it can never satisfy.
     *
     * <p>Counts only the primary anchors (sidecars/aux are small and tracked with the
     * anchor) and matches the same dir / prefix / extension filters the reaper applies,
     * so this is a conservative lower bound on what a reap would actually free. Cheap
     * because it only runs once a volume is already below the emergency floor.
     */
    private long pooledMediaBytesOnVolume(String volumeRoot) {
        if (volumeRoot == null) return 0L;
        long total = 0L;
        Set<String> seenPaths = new HashSet<>();
        for (String category : PHYSICAL_REAP_CATEGORIES) {
            final String primaryExt = primaryExtensionForCategory(category);
            final String namePrefix = namePrefixForCategory(category);
            final String[] auxPrefixes = auxiliaryPrefixesForCategory(category);
            for (File dir : getReapableDirs(category)) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                if (!dir.getAbsolutePath().startsWith(volumeRoot)) continue;
                File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
                if (files == null) files = listFilesByExt(dir, primaryExt);
                if (files == null) continue;
                for (File f : files) {
                    if (f == null || !f.isFile()) continue;
                    String name = f.getName();
                    if (namePrefix != null
                            && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;
                    if (!seenPaths.add(f.getAbsolutePath())) continue;
                    total += f.length();
                }
            }
        }
        return total;
    }

    /**
     * Entry point for the periodic tick: if any ACTIVE volume backing a media
     * category is physically below {@link #PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES},
     * run {@link #reapForPhysicalFreeSpace} on it. Acquires the category cleanup
     * locks in a FIXED order (recordings → surveillance → proximity) — matching the
     * per-category passes' lock usage — so the cross-category reap can't deadlock
     * against them or the recorder pre-flight. Targets a free-space level above the
     * 200 MB internal-fallback clear threshold so a successful reap also un-latches a
     * stale {@code recordingsEnospcFallbackActive} on the next probe.
     *
     * @return true if any reap freed space (caller may re-snapshot sizes).
     */
    private boolean runPhysicalFreeSpaceEmergencyReap() {
        // The media categories share at most ONE external volume + internal. Build
        // the distinct set of volumes PHYSICALLY backing media right now, driven by
        // each category's CONFIGURED type (normalized for a genuinely-unmounted
        // external → INTERNAL), NOT the ENOSPC-aware active type.
        //
        // Why configured, not active: activeTypeForCategory("recordings") delegates
        // to getActiveRecordingsStorageType(), which is ENOSPC-AWARE — once a
        // mounted-but-FULL external card latches recordingsEnospcFallbackActive, it
        // reports INTERNAL. In a recordings-only-on-external config (surveillance +
        // proximity on INTERNAL), that would collapse the set to {INTERNAL} the
        // instant the latch flips and HIDE the full external card from this reaper —
        // the exact deadlock this pass exists to break (SD stays full, recordings
        // spill to internal forever, the 200 MB self-clear never fires because
        // nothing reaps SD). The physical-volume pass must see the configured
        // external regardless of the latch. normalizeStorageType still folds a
        // genuinely-unmounted external back to INTERNAL (so a transiently-absent
        // archive is never auto-reaped — req 3), and the all-internal config stays a
        // no-op (req 10). The HashSet de-dups the common active==configured case.
        Set<StorageType> volumes = new HashSet<>();
        volumes.add(normalizeStorageType(configuredTypeForCategory("recordings")));
        volumes.add(normalizeStorageType(configuredTypeForCategory("surveillance")));
        volumes.add(normalizeStorageType(configuredTypeForCategory("proximity")));

        boolean freedAny = false;
        for (StorageType volume : volumes) {
            long free = freeBytesForType(volume);
            // free <= 0 on INTERNAL is a transient StatFs hiccup (don't act); on an
            // external volume it means unmounted/inaccessible (no safe scope) — skip
            // either way, the per-category passes + watchdog handle those.
            if (free <= 0 || free >= PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES) continue;

            // Clamp the target to a fraction of the volume so a tiny card is never
            // reaped toward an unreachable 1 GB. Never below the floor (else a reap
            // that can't reach target would loop every tick deleting one clip).
            long total = liveVolumeTotalBytes(volume);
            long target = PHYSICAL_FREE_EMERGENCY_TARGET_BYTES;
            if (total > 0) {
                long quarter = total / 4;
                if (target > quarter) target = Math.max(quarter, PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES);
            }

            // MEDIA-FOOTPRINT gate (data-loss guard): only reap when deleting media
            // can PLAUSIBLY recover the deficit. On INTERNAL, freeBytesForType reads
            // whole-filesystem free, which can dip below the floor purely from
            // NON-media pressure (OS / maps / OTA staging / logs). Reaping in-cap
            // clips can never recover that, so without this gate the reaper would
            // bleed away the oldest user clips every 30 s — deleting media the user
            // explicitly kept within cap, and never reaching the target. Pool the
            // media physically on the volume (exactly what the reaper may delete) and
            // skip when it's smaller than the deficit: the per-category caps already
            // bound that media, and non-media pressure is not ours to fix. When media
            // IS the consumer (the oversubscription/ENOSPC-spill case this pass exists
            // for — SD or an oversubscribed internal), the pool dwarfs the deficit, the
            // gate passes, and the deadlock-break is unchanged. Only walked below the
            // floor, so the healthy path stays a cheap StatFs no-op.
            long deficit = target - free;            // > 0 here (free < floor < target)
            long pooledMedia = pooledMediaBytesOnVolume(volumeRootForType(volume));
            if (pooledMedia < deficit) {
                logWarn("Physical-free emergency: " + volume + " at " + formatSize(free)
                    + " free but only " + formatSize(pooledMedia) + " of reapable media on it"
                    + " (deficit " + formatSize(deficit) + ") — pressure is non-media; "
                    + "skipping reap (per-category caps still bound media)");
                continue;
            }

            logWarn("Physical-free emergency: " + volume + " at " + formatSize(free)
                + " free (< " + formatSize(PHYSICAL_FREE_EMERGENCY_FLOOR_BYTES)
                + ") — cross-category oldest-first reap to " + formatSize(target));

            // Fixed lock order: recordings → surveillance → proximity. No other path
            // nests these locks, so a consistent acquisition order is deadlock-free.
            synchronized (recordingsCleanupLock) {
                synchronized (surveillanceCleanupLock) {
                    synchronized (proximityCleanupLock) {
                        if (reapForPhysicalFreeSpace(volume, target) > 0) freedAny = true;
                    }
                }
            }
        }
        return freedAny;
    }

    /**
     * Stop periodic cleanup.
     */
    public void stopPeriodicCleanup() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
            }
            cleanupScheduler = null;
            logInfo("Stopped periodic storage cleanup");
        }
    }

    /**
     * Start SD card / USB mount watchdog for sentry mode.
     * Periodically checks if the configured external volume(s) are still
     * mounted and re-mounts them if the system unmounted them (BYD/Android
     * tends to unmount SD when ACC is off; USB drops on bus glitches).
     *
     * Call this when entering sentry mode with an external volume selected.
     * The single watchdog now covers BOTH SD and USB so a USB-only config
     * doesn't go un-watched and silently fall back to internal forever.
     */
    public void startSdCardWatchdog() {
        // Start watchdog if ANY storage type uses SD or USB (not just surveillance).
        // The watchdog keeps the external volume mounted so recordings, events,
        // and trips remain accessible via the HTTP server even when surveillance
        // is suppressed.
        boolean anyOnSd  = surveillanceStorageType == StorageType.SD_CARD ||
                          recordingsStorageType   == StorageType.SD_CARD ||
                          tripsStorageType        == StorageType.SD_CARD;
        boolean anyOnUsb = surveillanceStorageType == StorageType.USB ||
                          recordingsStorageType   == StorageType.USB ||
                          tripsStorageType        == StorageType.USB;
        if (!anyOnSd && !anyOnUsb) {
            logDebug("Volume watchdog not needed - no storage type uses SD or USB");
            return;
        }

        stopSdCardWatchdog();  // Stop any existing watchdog first

        // Fresh watchdog session (boot, ACC-OFF arm, or storage-type change):
        // clear the one-time internal-fallback recovery latches so the first
        // remount failure of this session runs its recovery, and reset the
        // failure counters so quiet-log throttling starts clean.
        sdFallbackRecoveryDone = false;
        usbFallbackRecoveryDone = false;
        sdWatchdogConsecutiveFailures = 0;
        usbWatchdogConsecutiveFailures = 0;

        final boolean watchSd = anyOnSd;
        final boolean watchUsb = anyOnUsb;

        sdCardWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VolumeWatchdog");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);  // Normal priority - mount is critical
            return t;
        });

        sdCardWatchdog.scheduleAtFixedRate(() -> {
            try {
                // Use the cheap layered check (StatFs + canWrite, no shell
                // fork) for the watchdog tick. The expensive `touch+rm`
                // probe in isSdCardMounted() falsely reports unmounted under
                // FUSE binder contention from concurrent dir-walks, kicking
                // off a remount cascade that itself spawns more shell forks
                // and amplifies the contention. The cheap check has zero
                // such side effects.
                //
                // Two-strikes rule: a single negative reading is treated as
                // a transient probe failure. Only after TWO consecutive
                // failures do we fire the remount path. This eliminates
                // the false-positive "card unmounted" log that fires after
                // every UI settings save (when the page reflexively walks
                // the SD via /api/storage/external + /api/recordings/stats).
                // FIX (audit R8, LOW): track whether the SD branch fully
                // executed this tick. We must NOT short-circuit the whole
                // lambda on a first-strike SD failure — the USB branch
                // below has its own independent state machine and a
                // first-strike SD probe failure must not delay USB
                // unmount detection by a full 15s tick. We bypass the
                // SD-handling block via a scoped flag instead of the
                // historical `return;`.
                boolean sdHandled = false;
                if (watchSd && !isSdCardLikelyMounted()) {
                    sdWatchdogConsecutiveFailures++;

                    // First failure: silent, just record and let the USB
                    // branch still run. (FIX audit R8, LOW: was `return;`)
                    if (sdWatchdogConsecutiveFailures < 2) {
                        logDebug("SD watchdog: first-strike probe failure, deferring to next tick "
                            + "(USB branch still runs)");
                        sdHandled = true;
                    }
                    if (!sdHandled) {

                    // Only log verbosely for the first few failures, then quiet down
                    boolean shouldLog = sdWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                        sdWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;

                    if (shouldLog) {
                        logWarn("SD card watchdog: card unmounted, attempting remount... (attempt #" +
                            sdWatchdogConsecutiveFailures + ")");
                    }

                    if (ensureSdCardMounted(true)) {
                        logInfo("SD card watchdog: remounted successfully after " +
                            sdWatchdogConsecutiveFailures + " attempts");
                        sdWatchdogConsecutiveFailures = 0;
                        // Re-arm the one-time internal-fallback recovery: the card
                        // is back, so if it drops AGAIN later the failure branch
                        // must run its recovery once more for that new outage.
                        sdFallbackRecoveryDone = false;

                        // Restore SD card directories now that card is back
                        initSdCardDirectories();
                        updateActiveDirectories();

                        // Update running sentry engine's output directory
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null) {
                                // FIX (audit R1, surveillance freeze): use the
                                // LIVE canonical resolution rather than the
                                // volatile surveillanceDir field. While an arm
                                // session is active, updateActiveDirectories()
                                // skips the surveillance branch, so getSurveillanceDir()
                                // returns the frozen internal-fallback path
                                // captured before enable() — re-pushing it here
                                // would be a no-op and subsequent events would
                                // stay on internal for the whole session.
                                // getLiveSurveillanceDir() resolves the freshly
                                // remounted SD path directly, exactly like the
                                // recordings branch below (R5).
                                File liveSurvDir = getLiveSurveillanceDir();
                                pipeline.getSentry().setEventOutputDir(liveSurvDir);
                                logInfo("SD card watchdog: updated sentry output dir to " +
                                    liveSurvDir.getAbsolutePath());
                            }

                            // Also re-poke the pano recorder if recordings
                            // are configured to land on SD. Without this,
                            // an in-progress pano recording silently
                            // continues writing to the vanished SD mount
                            // path captured at startRecording time —
                            // segments are lost until the next mode toggle.
                            // Only future segments / start-recording calls
                            // pick up the new dir; the in-flight segment is
                            // unrecoverable (encoder's segmentBasePath was
                            // fixed at the segment open).
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.SD_CARD) {
                                // FIX (audit R5, LOW): use canonical SD path
                                // directly rather than the volatile recordingsDir
                                // field (which is only swapped by
                                // updateActiveDirectories when no recording is
                                // active — so a hot remount during an in-flight
                                // segment would still read the vanished dir).
                                ResolvedDir rSd = resolveActive(StorageType.SD_CARD,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File newRecordingsDir = (sdCardRecordingsDir != null)
                                    ? sdCardRecordingsDir : rSd.dir;
                                // FIX (audit R2): if the encoder has already
                                // latched writerAbortedCorrupt (SD vanished
                                // mid-segment), the listener bridge in
                                // GpuMosaicRecorder MAY have already cleared
                                // wrapper.recording — but if it didn't fire
                                // (raced, exception path), wrapper.recording
                                // still lies. Belt-and-suspenders: force a
                                // stopRecording() on the wrapper before we
                                // poke setOutputDir. That guarantees
                                // recording=false + recordingActive=false so
                                // RMM's wedge detector + activateMode path
                                // can consume pendingOutputDirOverride on
                                // the very next tick instead of waiting for
                                // ACC OFF/ON or daemon restart.
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: encoder writer aborted "
                                            + "but wrapper.recording still true — forcing pipeline.stopRecording()"
                                            + " before setOutputDir to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for full rationale.
                                        // recorder-only stop leaves pipeline.currentMode
                                        // pinned at NORMAL_RECORDING and RMM rejects
                                        // every wedge-retry activateMode forever.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("SD card watchdog: writer-abort stop probe failed: "
                                        + t.getMessage());
                                }
                                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                                logInfo("SD card watchdog: pano recorder output dir updated to " +
                                    newRecordingsDir.getAbsolutePath()
                                    + " (in-flight segment may be lost; future segments use new path)");

                                // FIX (audit R7, HIGH): kick RMM resync immediately
                                // so the freshly-cleared currentMode→IDLE state is
                                // re-armed without waiting up to 30s for the next
                                // ticker. Mirrors the setRecordingsStorageType kick.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("sd-watchdog-remount-success");
                                        logInfo("SD card watchdog: kicked RMM resyncFromHardware "
                                            + "to re-arm recording on remounted volume");
                                    }
                                } catch (Throwable rt) {
                                    logWarn("SD card watchdog: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: could not update recorder/sentry dir: " + t.getMessage());
                        }

                        // Re-arm RecordingsIndex against the freshly-mounted
                        // SD dirs + reconcile so existing files on the card
                        // populate the index immediately. Without this, a
                        // hot-mounted SD stays invisible to events.html and
                        // the native fragment until the 1-hour periodic
                        // reconcile.
                        notifyRecordingsIndexOfStorageChange("SD watchdog");
                    } else {
                        // FIX (audit R4): on remount failure, eagerly re-resolve
                        // active directories so recordingsDir / surveillanceDir
                        // fields fall back to internal NOW. Previously this
                        // branch only logged: cleanup + recordings-stats +
                        // pre-flight reserve callers that don't go through
                        // ensureStorageReady continued to hit the vanished SD
                        // path for 30-60s until RMM wedge detection kicked the
                        // pipeline into a fresh start(). Mirrors the success
                        // branch's updateActiveDirectories() call.
                        try {
                            discoverVolumes();
                            updateActiveDirectories();
                            logWarn("SD card watchdog: remount FAILED — fell back active dirs to internal "
                                + "(recordings=" + getRecordingsDir().getAbsolutePath() + ")");
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: remount-failure fallback re-resolve threw: "
                                + t.getMessage());
                        }

                        // FIX (audit R5, HIGH): mirror SUCCESS branch on
                        // FAILURE too. Without this, encoder writer-abort may
                        // not have fired yet (3 disk-write fails needed) OR
                        // FUSE may block the writer indefinitely. Wrapper
                        // recording stays true, recordingsDir stays pinned at
                        // the vanished SD path, setOutputDir is never pushed,
                        // and segments are silently lost until ACC OFF/ON.
                        //
                        // (1) probe writer-aborted+isRecording — if true,
                        //     force stopRecording so wrapper.recording and
                        //     recordingActive synchronously flip false BEFORE
                        //     dir swap.
                        // (2) compute internal-fallback dir directly via
                        //     resolveActive(INTERNAL,...) — bypasses the
                        //     recordingActive-gated recordingsDir field which
                        //     was still pointing at the vanished mount until
                        //     step (1) cleared it.
                        // (3) push setOutputDir(internalFallbackDir) so RMM's
                        //     next start consumes the override and lands on
                        //     internal instead of the dead SD path.
                        //
                        // IDEMPOTENCY: this recovery is a one-time TRANSITION
                        // action. Gate it on sdFallbackRecoveryDone so a card
                        // that stays unmounted (e.g. SD slot unpowered while
                        // parked with USB power off) doesn't re-force-stop the
                        // recorder every 15s — which would prevent any 120s
                        // continuous segment from ever finalizing (zero files
                        // for the whole park). Run it once on the drop, then
                        // skip until a successful remount resets the latch.
                        // The cheap discoverVolumes()/updateActiveDirectories()
                        // re-resolve above STILL runs every tick (truthful paths
                        // for cleanup/stats); only the recorder-disturbing push
                        // is latched.
                        if (sdFallbackRecoveryDone) {
                            // Already moved the recorder to internal for this
                            // outage. Belt-and-suspenders: if a NEW recording
                            // somehow re-armed onto the dead SD path since then
                            // (e.g. a mode toggle), the writer-abort branch below
                            // would have caught it — but in steady state there's
                            // nothing to do. Stay quiet.
                            logDebug("SD card watchdog: internal-fallback recovery already applied "
                                + "this outage — skipping recorder re-push (idempotent)");
                        } else {
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.SD_CARD) {
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: remount FAILED + encoder writer aborted "
                                            + "— forcing pipeline.stopRecording before internal-fallback setOutputDir "
                                            + "to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    } else if (pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: remount FAILED while recording — "
                                            + "forcing pipeline.stopRecording so internal-fallback setOutputDir "
                                            + "applies on next start (in-flight segment lost)");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("SD card watchdog: remount-failure stop probe threw: "
                                        + t.getMessage());
                                }
                                // Resolve INTERNAL directly — bypass the
                                // recordingsDir field which may still be stale
                                // until updateActiveDirectories above swapped it.
                                ResolvedDir rFallback = resolveActive(StorageType.INTERNAL,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File internalFallbackDir = rFallback.dir;
                                if (internalFallbackDir != null) {
                                    pipeline.getRecorder().setOutputDir(internalFallbackDir);
                                    // Latch: recovery applied for this outage.
                                    // Reset on a successful remount (below).
                                    sdFallbackRecoveryDone = true;
                                    logWarn("SD card watchdog: remount FAILED — pushed pano recorder dir "
                                        + "to INTERNAL fallback " + internalFallbackDir.getAbsolutePath()
                                        + " (future segments land on internal until SD recovers; "
                                        + "recovery latched — won't re-disrupt recording on later failed ticks)");

                                    // FIX (audit R7, HIGH): kick RMM resync to
                                    // re-arm recording on the internal fallback
                                    // immediately, instead of waiting up to 30s.
                                    try {
                                        com.overdrive.app.recording.RecordingModeManager rmm =
                                            com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                        if (rmm != null) {
                                            rmm.resyncFromHardware("sd-watchdog-remount-failed");
                                            logInfo("SD card watchdog: kicked RMM resyncFromHardware "
                                                + "to re-arm recording on internal fallback");
                                        }
                                    } catch (Throwable rt) {
                                        logWarn("SD card watchdog: RMM resync kick threw: "
                                            + rt.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: remount-failure setOutputDir push threw: "
                                + t.getMessage());
                        }
                        }  // end if (!sdFallbackRecoveryDone)

                        if (shouldLog) {
                            logError("SD card watchdog: remount FAILED - surveillance may use internal fallback");
                        }
                    }
                    } // end if (!sdHandled) — FIX audit R8 LOW
                } else if (watchSd) {
                    // Card is healthy — reset failure counter (was a single
                    // transient probe failure, not a real unmount).
                    if (sdWatchdogConsecutiveFailures > 0) {
                        if (sdWatchdogConsecutiveFailures >= 2) {
                            logInfo("SD card watchdog: card is mounted again");
                        }
                        sdWatchdogConsecutiveFailures = 0;
                    }
                }

                // USB watchdog branch — independent state machine but shares
                // the schedule. Per user spec: USB-only configs must also fall
                // back to internal transparently when the stick disappears
                // mid-recording, but ALSO get a remount attempt when the
                // bus settles. Without this branch a USB-only surveillance
                // config that loses its drive stays on internal forever.
                // FIX (audit R5): mirror SD branch — use cheap fork-free
                // probe (StatFs+canWrite) so the per-minute tick never forks
                // a shell, AND apply two-strikes (single negative reading is
                // a transient probe failure, not real unmount). Eliminates the
                // false-positive remount cascade after every UI settings save.
                // FIX (audit R8, LOW): mirror SD-side fix — never `return;`
                // out of the whole tick on a first-strike USB probe
                // failure. The SD branch above is also independent and
                // its state must not be skipped if/when USB transient
                // failures stack at the start of a tick.
                boolean usbHandled = false;
                if (watchUsb && !isUsbLikelyMounted()) {
                    usbWatchdogConsecutiveFailures++;

                    // First failure: silent, just record and let the rest
                    // of the tick run. (FIX audit R8 LOW: was `return;`)
                    if (usbWatchdogConsecutiveFailures < 2) {
                        logDebug("USB watchdog: first-strike probe failure, deferring to next tick");
                        usbHandled = true;
                    }
                    if (!usbHandled) {

                    boolean shouldLogUsb = usbWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                           usbWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;
                    if (shouldLogUsb) {
                        logWarn("USB watchdog: drive unmounted, attempting remount... (attempt #" +
                            usbWatchdogConsecutiveFailures + ")");
                    }
                    if (ensureUsbMounted(true)) {
                        logInfo("USB watchdog: remounted successfully after " +
                            usbWatchdogConsecutiveFailures + " attempts");
                        usbWatchdogConsecutiveFailures = 0;
                        // Re-arm the one-time internal-fallback recovery (see SD
                        // branch): drive is back, so a later drop recovers again.
                        usbFallbackRecoveryDone = false;
                        initUsbDirectories();
                        updateActiveDirectories();
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null
                                    && surveillanceStorageType == StorageType.USB) {
                                // FIX: use getLiveSurveillanceDir() — NOT getSurveillanceDir().
                                // getSurveillanceDir() returns the frozen field that
                                // updateActiveDirectories() SKIPS while surveillanceActive
                                // is true (an armed session). During a parked surveillance
                                // session the field stays at internal-fallback from when the
                                // USB dropped, so pushing it after remount success re-pins
                                // sentry to INTERNAL despite the USB being back. This was
                                // the root cause of "surveillance videos saved to internal
                                // for 10-15 min" — the SD watchdog branch (line ~7097)
                                // already has this fix; the USB branch was missed.
                                File liveSurvDir = getLiveSurveillanceDir();
                                pipeline.getSentry().setEventOutputDir(liveSurvDir);
                                logInfo("USB watchdog: updated sentry output dir to " +
                                    liveSurvDir.getAbsolutePath());
                            }

                            // Pano recorder dir re-poke: same rationale as
                            // the SD branch — gated to USB-backed recordings
                            // since otherwise the recordings dir didn't move.
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.USB) {
                                // FIX (audit R5, LOW): use canonical USB path
                                // directly — see SD branch comment for rationale.
                                ResolvedDir rUsb = resolveActive(StorageType.USB,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File newRecordingsDir = (usbRecordingsDir != null)
                                    ? usbRecordingsDir : rUsb.dir;
                                // FIX (audit R2): same writerAborted belt-and-
                                // suspenders as the SD branch — see comment
                                // there for full rationale.
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: encoder writer aborted "
                                            + "but wrapper.recording still true — forcing pipeline.stopRecording()"
                                            + " before setOutputDir to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("USB watchdog: writer-abort stop probe failed: "
                                        + t.getMessage());
                                }
                                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                                logInfo("USB watchdog: pano recorder output dir updated to " +
                                    newRecordingsDir.getAbsolutePath()
                                    + " (in-flight segment may be lost; future segments use new path)");

                                // FIX (audit R7, HIGH): kick RMM resync to re-arm
                                // recording immediately on the freshly-remounted
                                // USB volume. Mirrors SD watchdog success branch.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("usb-watchdog-remount-success");
                                        logInfo("USB watchdog: kicked RMM resyncFromHardware "
                                            + "to re-arm recording on remounted volume");
                                    }
                                } catch (Throwable rt) {
                                    logWarn("USB watchdog: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("USB watchdog: could not update recorder/sentry dir: " + t.getMessage());
                        }

                        // Same RecordingsIndex re-arm + reconcile pattern as
                        // the SD watchdog branch — see comment there. Hot-
                        // mounted USB sticks otherwise stay invisible to the
                        // index until the 1-hour periodic reconcile.
                        notifyRecordingsIndexOfStorageChange("USB watchdog");
                    } else {
                        // FIX (audit R4): symmetric to SD branch — eagerly fall
                        // back active dirs to internal on USB remount failure
                        // so cleanup / recordings-stats / pre-flight reserve
                        // callers see truthful paths immediately.
                        try {
                            discoverVolumes();
                            updateActiveDirectories();
                            logWarn("USB watchdog: remount FAILED — fell back active dirs to internal "
                                + "(recordings=" + getRecordingsDir().getAbsolutePath() + ")");
                        } catch (Throwable t) {
                            logWarn("USB watchdog: remount-failure fallback re-resolve threw: "
                                + t.getMessage());
                        }

                        // FIX (audit R5, HIGH): identical recovery to SD
                        // FAILURE branch — see comment there. Without this,
                        // a USB-only configuration that loses the stick mid-
                        // segment continues to write to the vanished mount
                        // until ACC OFF/ON or daemon restart.
                        //
                        // IDEMPOTENCY: one-time transition action, latched on
                        // usbFallbackRecoveryDone — see the SD branch + the field
                        // doc for why re-running this every 15s zeroes out
                        // continuous recording. Reset on a successful remount.
                        if (usbFallbackRecoveryDone) {
                            logDebug("USB watchdog: internal-fallback recovery already applied "
                                + "this outage — skipping recorder re-push (idempotent)");
                        } else {
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.USB) {
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: remount FAILED + encoder writer aborted "
                                            + "— forcing pipeline.stopRecording before internal-fallback setOutputDir "
                                            + "to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    } else if (pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: remount FAILED while recording — "
                                            + "forcing pipeline.stopRecording so internal-fallback setOutputDir "
                                            + "applies on next start (in-flight segment lost)");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("USB watchdog: remount-failure stop probe threw: "
                                        + t.getMessage());
                                }
                                ResolvedDir rFallback = resolveActive(StorageType.INTERNAL,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File internalFallbackDir = rFallback.dir;
                                if (internalFallbackDir != null) {
                                    pipeline.getRecorder().setOutputDir(internalFallbackDir);
                                    usbFallbackRecoveryDone = true;
                                    logWarn("USB watchdog: remount FAILED — pushed pano recorder dir "
                                        + "to INTERNAL fallback " + internalFallbackDir.getAbsolutePath()
                                        + " (future segments land on internal until USB recovers; "
                                        + "recovery latched — won't re-disrupt recording on later failed ticks)");

                                    // FIX (audit R7, HIGH): kick RMM resync to
                                    // re-arm recording on the internal fallback
                                    // immediately, mirroring SD failure branch.
                                    try {
                                        com.overdrive.app.recording.RecordingModeManager rmm =
                                            com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                        if (rmm != null) {
                                            rmm.resyncFromHardware("usb-watchdog-remount-failed");
                                            logInfo("USB watchdog: kicked RMM resyncFromHardware "
                                                + "to re-arm recording on internal fallback");
                                        }
                                    } catch (Throwable rt) {
                                        logWarn("USB watchdog: RMM resync kick threw: "
                                            + rt.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("USB watchdog: remount-failure setOutputDir push threw: "
                                + t.getMessage());
                        }
                        }  // end if (!usbFallbackRecoveryDone)

                        if (shouldLogUsb) {
                            logError("USB watchdog: remount FAILED - surveillance may use internal fallback");
                        }
                    }
                    } // end if (!usbHandled) — FIX audit R8 LOW
                } else if (watchUsb) {
                    // FIX (audit R5): mirror SD reset path — only log "mounted
                    // again" when we actually crossed the two-strikes threshold,
                    // otherwise the tick is a single transient probe failure
                    // recovering on the very next read (not user-visible).
                    if (usbWatchdogConsecutiveFailures > 0) {
                        if (usbWatchdogConsecutiveFailures >= 2) {
                            logInfo("USB watchdog: drive is mounted again");
                        }
                        usbWatchdogConsecutiveFailures = 0;
                    }
                }
            } catch (Exception e) {
                logWarn("Volume watchdog error: " + e.getMessage());
            }
        }, SD_WATCHDOG_INTERVAL_SECONDS, SD_WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logInfo("Started volume mount watchdog (interval=" + SD_WATCHDOG_INTERVAL_SECONDS +
            "s, sd=" + watchSd + ", usb=" + watchUsb + ")");
    }

    /**
     * Stop SD card mount watchdog (call when exiting sentry mode or ACC comes back on).
     */
    public void stopSdCardWatchdog() {
        if (sdCardWatchdog != null) {
            sdCardWatchdog.shutdown();
            try {
                if (!sdCardWatchdog.awaitTermination(3, TimeUnit.SECONDS)) {
                    sdCardWatchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                sdCardWatchdog.shutdownNow();
            }
            sdCardWatchdog = null;
            logInfo("Stopped SD card mount watchdog");
        }
    }
    
    /**
     * Set recording active state. Periodic cleanup runs continuously regardless
     * (started at daemon boot via {@link #startPeriodicCleanup()}); this flag
     * is kept for callers that may consult {@link #isRecordingActive()}.
     */
    public void setRecordingActive(boolean active) {
        recordingActive.set(active);
    }

    /**
     * Wires the authoritative "encoder is currently writing" probe used by
     * the cleanup gate. Should point at HardwareEventRecorderGpu.isWritingToFile.
     * Pipeline init wires this once; release-and-reinit cycles can re-wire.
     * Passing null reverts to the default (always false → cleanup never blocked).
     */
    public void setEncoderWritingProbe(java.util.function.BooleanSupplier probe) {
        this.encoderWritingProbe = probe != null ? probe : () -> false;
        if (probe != null) {
            probeWired.set(true);
        }
    }

    /**
     * True when the encoder is actively writing packets to disk. The cleanup
     * paths (post-save, periodic, sidecar) consult this before running
     * destructive deletes; if true, the cleanup is deferred to the deferred
     * queue and drained on the next non-recording pass.
     *
     * Cheap (volatile read) — safe to call from any thread, every iteration.
     */
    private boolean isEncoderWriting() {
        try {
            return encoderWritingProbe.getAsBoolean();
        } catch (Exception e) {
            // A buggy probe must never block cleanup forever — fail open on
            // recoverable exceptions. Errors (OOM, StackOverflow, LinkageError)
            // propagate; "treat the JVM as healthy and run a delete burst" is
            // the wrong default response to a process that's already broken.
            return false;
        }
    }

    /**
     * Set surveillance active state. See {@link #setRecordingActive(boolean)}
     * for periodic-cleanup lifetime semantics.
     */
    public void setSurveillanceActive(boolean active) {
        surveillanceActive.set(active);
    }

    /**
     * Mark a trip telemetry file as in-flight so {@link #ensureSpace} skips
     * it during cleanup. The recorder still writes through a buffered
     * GZIPOutputStream; if cleanup were to delete and unlink the file mid-write
     * on Linux, subsequent writes go to a still-open fd whose bytes are lost
     * once close() runs (the inode is reaped at fd-close, not at unlink).
     *
     * Pass {@code null} on stop. Path-based rather than a boolean so older
     * trip files can still be reaped during an active trip.
     */
    public void setActiveTripFile(File file) {
        activeTripFilePath = (file != null) ? file.getAbsolutePath() : null;
    }

    /**
     * Absolute path of the telemetry file for the trip CURRENTLY being recorded,
     * or null if no trip is active. Used by trip recovery to skip the in-flight
     * file — it has no DB row yet (the row is inserted only at trip end), so
     * recovery would otherwise rebuild a phantom duplicate that the real
     * trip-end insert then duplicates. Mirrors the reaper's protectedTripPath.
     */
    public String getActiveTripFilePath() {
        return activeTripFilePath;
    }
    
    /**
     * Check if recording is active.
     */
    public boolean isRecordingActive() {
        return recordingActive.get();
    }
    
    /**
     * Check if surveillance is active.
     */
    public boolean isSurveillanceActive() {
        return surveillanceActive.get();
    }
    
    /**
     * Wipes every media file (and JSON sidecars) for the given category from
     * all known storage locations — active dir, internal fallback, and SD-card
     * mirror — plus thumbnails for that category.
     *
     * Used by the user-initiated "Reset Data" feature. Holds the per-category
     * cleanup lock for {@code category} so it cannot race with periodic cleanup
     * or any in-flight delete in that category.
     *
     * @param category one of "recordings", "surveillance", "proximity", "trips"
     * @return number of files deleted, or -1 on unknown category
     */
    public long wipeMediaCategory(String category) {
        if (category == null) return -1;
        List<File> dirs;
        switch (category) {
            case "recordings":  dirs = getAllRecordingsDirs(); break;
            case "surveillance": dirs = getAllSurveillanceDirs(); break;
            case "proximity":   dirs = getAllProximityDirs(); break;
            case "trips":       dirs = getAllTripsDirs(); break;
            default: return -1;
        }

        // FIX (audit R4): protect the in-flight encoder output and any *.tmp
        // newer than the 10-min grace window used by sweepOrphanTempFiles.
        // Without these gates, a user-initiated Reset Data → Recordings during
        // an active CONTINUOUS / DRIVE_MODE pano session unlinks the open
        // *.mp4.tmp the encoder is currently writing into; recovery only
        // happens after RMM wedge detection (~30-60 s).
        //
        // Probe the encoder's active path through the live pipeline. We look
        // up GpuSurveillancePipeline lazily so the wipe still works when no
        // recorder exists (e.g., daemon shutdown). For the trips category we
        // also honour activeTripFilePath like sweepOrphanTempFiles does.
        String activeEncoderPath = null;
        String activeEncoderTmpPath = null;
        if ("recordings".equals(category) || "surveillance".equals(category)
                || "proximity".equals(category)) {
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                if (pipeline != null && pipeline.getRecorder() != null) {
                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                        pipeline.getRecorder().getEncoder();
                    if (enc != null) {
                        // Force a clean segment finalise so the encoder is no
                        // longer holding an open fd against any path we are
                        // about to nuke. Best-effort — if it throws or the
                        // recorder isn't actually recording the call is a
                        // no-op.
                        try {
                            if (pipeline.getRecorder().isRecording()) {
                                logWarn("wipeMediaCategory(" + category + "): recording active, "
                                    + "forcing pipeline.stopRecording before wipe to finalise current segment");
                                // FIX (audit R7, HIGH): pipeline.stopRecording()
                                // not recorder.stopRecording(); recorder-only
                                // stop leaves pipeline.currentMode pinned at
                                // NORMAL_RECORDING and RMM rejects re-activation,
                                // wedging recording until ACC OFF/ON. See
                                // setRecordingsStorageType for full rationale.
                                pipeline.stopRecording();
                                // Kick RMM so re-activation runs immediately on
                                // the next ticker rather than after up-to-30s.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("wipe-media-" + category);
                                    }
                                } catch (Throwable rt) {
                                    logWarn("wipeMediaCategory: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("wipeMediaCategory: stopRecording before wipe threw: "
                                + t.getMessage());
                        }
                        activeEncoderPath = enc.getCurrentOutputPath();
                        if (activeEncoderPath != null) {
                            activeEncoderTmpPath = activeEncoderPath + ".tmp";
                        }
                    }
                }
            } catch (Throwable t) {
                logWarn("wipeMediaCategory: encoder-path probe threw: " + t.getMessage());
            }
        }
        final String protectedTripPath = "trips".equals(category) ? activeTripFilePath : null;
        final String protEncoderPath = activeEncoderPath;
        final String protEncoderTmpPath = activeEncoderTmpPath;
        final long tmpGraceCutoff = System.currentTimeMillis() - (10L * 60L * 1000L);

        long deleted = 0;
        long skippedActive = 0;
        synchronized (lockForCategory(category)) {
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) {
                    // FUSE-bridged SD/USB returns null under daemon UID 2000. Without
                    // this fallback a user "delete all" on an SD-configured category
                    // silently deletes ZERO files while reporting success — the SD
                    // bytes remain. Mirror getDirectoriesTotalSize's shell-ls fallback.
                    files = listFilesViaShell(dir);
                }
                if (files == null) continue;
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName();
                        String absPath = f.getAbsolutePath();
                        // Skip the encoder's currently-open output path and
                        // its .tmp companion.
                        if (protEncoderPath != null
                                && (absPath.equals(protEncoderPath)
                                    || absPath.equals(protEncoderTmpPath))) {
                            skippedActive++;
                            continue;
                        }
                        // Skip in-flight trip file (mirrors sweepOrphanTempFiles).
                        if (protectedTripPath != null
                                && (protectedTripPath.equals(absPath)
                                    || protectedTripPath.equals(absPath + ".tmp"))) {
                            skippedActive++;
                            continue;
                        }
                        // Honour the same 10-min grace window for any *.tmp
                        // partial — newer than that and a writer may still
                        // hold it open.
                        if (name.endsWith(".tmp") && f.lastModified() > tmpGraceCutoff) {
                            skippedActive++;
                            continue;
                        }
                        boolean wiped = f.delete();
                        if (!wiped) wiped = deleteFileViaShell(f);
                        if (wiped) {
                            deleted++;
                            // Drop the H2 row eagerly so the next
                            // /api/recordings call doesn't return a phantom
                            // entry for a just-wiped file. Mirrors the
                            // single-file deleteRecording path.
                            if (name.endsWith(".mp4")) {
                                try {
                                    com.overdrive.app.server.RecordingsIndex
                                            .getInstance().remove(name);
                                } catch (Throwable ignored) {
                                    // Index not initialised in this
                                    // process; reconcile() will catch up.
                                }
                            }
                        }
                    }
                }
            }

            // Best-effort thumbnail cleanup. Thumbnails live alongside the
            // active dir's parent in a "thumbs" subfolder; nuking the whole
            // dir would also kill any other category's thumbs, so we limit
            // to those derived from the just-wiped filenames. Cheaper to
            // just blow away the whole thumbs dir on a media wipe.
            try {
                File baseDir = (dirs.isEmpty() || dirs.get(0).getParentFile() == null)
                    ? null : dirs.get(0).getParentFile();
                if (baseDir != null) {
                    File thumbs = new File(baseDir, "thumbs");
                    if (thumbs.exists() && thumbs.isDirectory()) {
                        File[] thumbFiles = thumbs.listFiles();
                        if (thumbFiles == null) {
                            // FUSE-bridged SD/USB returns null here too. The thumbs
                            // cache is reaped by nothing else (it isn't scoped by any
                            // category limit), so without the shell-ls fallback an
                            // external "delete all" leaves every cached JPEG on the
                            // card. Mirror the main wipe loop above.
                            thumbFiles = listFilesViaShell(thumbs);
                        }
                        if (thumbFiles != null) {
                            for (File t : thumbFiles) {
                                if (t.isFile() && !t.delete()) deleteFileViaShell(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // The reporting size/count cache is now served stale-while-revalidate,
        // so without this a user-initiated "Reset Data" (which deletes the ENTIRE
        // category) leaves the storage card reporting the full pre-wipe figure
        // for the rest of the TTL and one further poll. Every other mutation path
        // (clip finalize, reap) already invalidates; this one never did, and it
        // is the most visibly wrong case since the user explicitly asked for the
        // data to be gone.
        if (deleted > 0) invalidateCategorySizeCache(category);

        logInfo("wipeMediaCategory(" + category + ") deleted " + deleted + " files"
            + (skippedActive > 0 ? " (skipped " + skippedActive
                + " in-flight/grace-window files)" : ""));
        return deleted;
    }

    /**
     * Shutdown all background threads.
     * Call this when the app is terminating.
     */
    public void shutdown() {
        stopPeriodicCleanup();
        stopSdCardWatchdog();
        
        asyncCleanupExecutor.shutdown();
        try {
            if (!asyncCleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                asyncCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncCleanupExecutor.shutdownNow();
        }
        
        logInfo("StorageManager shutdown complete");
    }
}
