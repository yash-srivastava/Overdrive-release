package com.overdrive.app.storage;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.camera.DiLink100Platform;
import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.util.ScratchRoot;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Destructive SD format. Only a worker may run sm commands; the HTTP thread queues it. */
public final class SdCardFormatter {
    private static final long FORMAT_TIMEOUT_MS = 900_000L;
    private static final long COMMAND_TIMEOUT_MS = 15_000L;
    // Read-only UI preview must never stall a mount operation behind mountLock.
    private static final long PREVIEW_COMMAND_TIMEOUT_MS = 3_000L;
    private static final long PREVIEW_CACHE_NS = TimeUnit.SECONDS.toNanos(30);
    private static final long MOUNT_POLL_MS = 300_000L;
    private static final long MOUNT_POLL_INTERVAL_MS = 1_000L;
    private static final long PROC_SCAN_TIMEOUT_MS = 5_000L;
    private static final SdCardFormatter INSTANCE = new SdCardFormatter();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SdCardFormatWorker");
        t.setDaemon(true);
        return t;
    });
    private volatile Job job = new Job("", "idle", "No SD format has been requested.");
    // A wedged filesystem StatFs can ignore interruption. Do not perform any
    // preview filesystem access on the HTTP thread or create unbounded probe
    // threads/queued work after a timeout. Saturation fails closed as busy.
    private final ExecutorService inspectionWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "SdCardInspection");
                t.setDaemon(true);
                return t;
            });
    private final ReentrantLock previewMonitor = new ReentrantLock();
    private volatile CachedPreview cachedPreview;
    // Survives a daemon death after vold unmounted the card or the OEM package was disabled.
    private static final String RECOVERY_MARKER = "overdrive-sd-format-pending";

    public static SdCardFormatter getInstance() { return INSTANCE; }
    private SdCardFormatter() {}

    public synchronized Job status() {
        if (marker().exists() && "idle".equals(job.state))
            return new Job("", "recovering", "Interrupted SD format: mount recovery is pending.");
        return job;
    }

    /** Atomic reservation: two overlapping POSTs cannot queue concurrent erases. */
    public synchronized Job submit(String expectedUuid, String expectedIdentityToken, boolean stopFactoryDvr) {
        if ("running".equals(job.state) || "recovering".equals(job.state)) return job;
        if (marker().exists()) return new Job("", "failed",
                "Previous format needs mount/DVR recovery. Restart the daemon or inspect the card.");
        if (!isValidUuid(expectedUuid) || expectedIdentityToken == null
                || !expectedIdentityToken.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            return new Job("", "failed", "SD card identity changed or is unavailable. Review and confirm again.");
        String id = UUID.randomUUID().toString();
        cachedPreview = null;
        job = new Job(id, "running", "Formatting SD card…");
        log("queued job=" + id + " expectedUuid=" + expectedUuid);
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = format(expectedUuid, expectedIdentityToken, stopFactoryDvr);
                } catch (Throwable e) {
                    result = new Result(false, "Formatting failed unexpectedly; inspect the card before retrying.");
                    log("unexpected job failure: " + e);
                }
                // Even a newly mounted UUID is not a completed operation if
                // the recovery journal survived (e.g. DVR re-enable failed).
                // Never show success while factory recording may be disabled.
                if (result.success && marker().exists()) {
                    result = new Result(false, "Format outcome unverified: mount or factory DVR recovery is still pending.");
                }
                cachedPreview = null;
                synchronized (SdCardFormatter.this) {
                    job = new Job(id, result.success ? "succeeded"
                            : result.message.startsWith("Format outcome unverified:") ? "unverified"
                            : "failed", result.message);
                }
                log("job=" + id + " result=" + (result.success ? "success" : "failure")
                        + " detail=" + result.message);
            });
        } catch (RuntimeException e) {
            job = new Job(id, "failed", "Could not start formatting worker.");
            log("worker rejected job=" + id + ": " + e);
        }
        return job;
    }

    /** Read-only preview: never takes mountLock (a slow sm would stall mounts). Cached
     *  30 s per card path; the format worker re-identifies under the mount lock. */
    public Preview targetPreview() {
        if (marker().exists()) return new Preview(null, "recovery");
        if ("running".equals(job.state) || "recovering".equals(job.state))
            return new Preview(null, "storage_busy");
        StorageManager storage = StorageManager.getInstance();
        String key = previewKey(storage);
        long now = System.nanoTime();
        CachedPreview cached = cachedPreview;
        if (isFreshPreview(cached, key, now)) return cached.preview;
        // Concurrent HTTP requests must not queue behind a wedged preview.
        if (!previewMonitor.tryLock()) return new Preview(null, "storage_busy");
        try {
            key = previewKey(storage);
            now = System.nanoTime();
            cached = cachedPreview;
            if (isFreshPreview(cached, key, now)) return cached.preview;
            // Bounds the ENTIRE preview (sm + StatFs + sysfs), not just sm.
            Preview preview = inspectTargetBounded(storage, PREVIEW_COMMAND_TIMEOUT_MS);
            // A concurrent mount refresh can invalidate the snapshot mid-probe.
            // Never cache (or offer) an identity for a different current path.
            if (!key.equals(previewKey(storage))) return new Preview(null, "changed");
            if ("running".equals(job.state) || "recovering".equals(job.state))
                return new Preview(null, "storage_busy");
            // A temporary vold/StatFs timeout is retryable immediately.
            if (isCacheablePreview(preview))
                cachedPreview = new CachedPreview(key, System.nanoTime(), preview);
            return preview;
        } finally {
            previewMonitor.unlock();
        }
    }

    static boolean isCacheablePreview(Preview preview) {
        return preview != null && !"storage_busy".equals(preview.reasonCode);
    }

    private static String previewKey(StorageManager storage) {
        return storage.isSdCardAvailable() + "|" + String.valueOf(storage.getSdCardPath());
    }

    private static boolean isFreshPreview(CachedPreview cached, String key, long now) {
        return cached != null && cached.key.equals(key)
                && now - cached.createdNs >= 0 && now - cached.createdNs < PREVIEW_CACHE_NS;
    }

    private Result format(String expectedUuid, String expectedIdentityToken, boolean stopFactoryDvr) {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock())
            return new Result(false, "Storage is busy. Try again when SD activity has stopped.");
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        boolean paused = false;
        boolean markerSaved = false;
        boolean mountLockHeld = true;
        boolean formatReserved = false;
        boolean restoreDvr = false;
        boolean mountVerified = false;
        String volumeId = null;
        try {
            Preview checked = findTarget(storage);
            Target target = checked.target;
            if (target == null || !target.uuid.equals(expectedUuid)
                    || !expectedIdentityToken.equals(target.identityToken))
                return new Result(false, preflightFailureMessage(checked,
                        "SD card identity changed or is unavailable. Review and confirm again."));
            volumeId = target.volumeId;
            if (target.fingerprint == null)
                return new Result(false, "Cannot verify SD media identity on this firmware; format blocked.");
            log("target uuid=" + target.uuid + " volumeId=" + target.volumeId
                    + " capacity=" + target.capacityBytes + " used=" + target.usedBytes);
            if (storage.getRecordingsStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getSurveillanceStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getTripsStorageType() == StorageManager.StorageType.SD_CARD)
                return new Result(false, "Move Overdrive recording, surveillance and trip storage off SD first.");
            // Reserve only the SD operation, not the shared mount lock: USB and
            // storage-type changes must remain responsive while vold runs.
            storage.setSdCardFormatActive(true);
            formatReserved = true;
            storage.releaseSdCardFormatLock();
            mountLockHeld = false;

            // Other UIDs' /proc/<pid>/fd is unreadable as shell: fail closed while any
            // com.byd.cdr process (including :service peers) is listed by ps.
            Result cdr = checkCdrStopped();
            if (!cdr.success && (!stopFactoryDvr
                    || !cdr.message.startsWith("Factory BYD CDR is running."))) return cdr;
            boolean stopped = cleaner.pauseForSdFormat();
            paused = true;
            if (!stopped) return new Result(false,
                    "Could not stop SD cleanup safely; formatting blocked.");
            // Write a durable recovery intent BEFORE disabling the DVR or invoking vold.
            // A daemon restart must not strand a disabled package or an unmounted card.
            restoreDvr = !cdr.success && stopFactoryDvr;
            if (!saveMarker(target.volumeId, target.uuid, restoreDvr))
                return new Result(false, "Cannot save format recovery intent; format blocked.");
            markerSaved = true;
            if (restoreDvr) {
                Result disabled = command(COMMAND_TIMEOUT_MS, "sh", "-c", "pm disable-user " + com.overdrive.app.util.ShellUser.flag() + " com.byd.cdr");
                if (!disabled.success) return new Result(false, "Could not temporarily disable factory DVR: " + disabled.message);
                // Check the package state as well as the process table. A successful pm
                // exit alone is not proof that the recorder has been stopped.
                Result packages = command(COMMAND_TIMEOUT_MS, "sh", "-c", "pm list packages " + com.overdrive.app.util.ShellUser.flag() + " -d");
                if (!packages.success || !packageListed(packages.message, "com.byd.cdr"))
                    return new Result(false, "Factory DVR disable could not be verified.");
            }
            cdr = checkCdrStopped();
            if (!cdr.success && !(restoreDvr && persistentDvrSurvivesDisable())) return cdr;
            // Best-effort additional coverage for processes readable as shell:
            // descriptors, working dirs, mmaps, and Android's alternate mount roots.
            Result busy = scanAccessibleCardUse(target.uuid);
            if (!busy.success) return busy;

            // Re-read media identity under the short mount lock immediately
            // before the irreversible call; never hold it during sm format.
            if (!storage.acquireSdCardFormatLock())
                return new Result(false, "Storage is busy. Try again when SD activity has stopped.");
            try {
                Preview rechecked = findTarget(storage);
                Target currentTarget = rechecked.target;
                if (currentTarget == null || !target.uuid.equals(currentTarget.uuid)
                        || !target.volumeId.equals(currentTarget.volumeId)
                        || !target.fingerprint.equals(currentTarget.fingerprint)
                        || !expectedIdentityToken.equals(currentTarget.identityToken))
                    return new Result(false, preflightFailureMessage(rechecked,
                            "SD card changed before format; operation blocked."));
            } finally {
                storage.releaseSdCardFormatLock();
            }

            Result formatResult = command(FORMAT_TIMEOUT_MS, "sm", "format", target.volumeId);
            log("sm format " + target.volumeId + " exit=" + formatResult.success
                    + " output=" + formatResult.message);
            // ALWAYS remount and poll: a timed-out or rejected sm format can
            // still unmount the card. The changed-UUID check is the only success.
            Result mountResult = command(COMMAND_TIMEOUT_MS, "sm", "mount", target.volumeId);
            log("sm mount " + target.volumeId + " exit=" + mountResult.success
                    + " output=" + mountResult.message);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MOUNT_POLL_MS);
            try {
                do {
                    Volume current = findVolumeById(target.volumeId);
                    if (current != null && !target.uuid.equals(current.uuid)
                            && isValidUuid(current.uuid) && "mounted".equals(current.state)
                            && target.fingerprint.equals(mediaFingerprint(current.id))) {
                        mountVerified = true;
                        storage.rememberFormattedSdUuid(current.uuid);
                        storage.refreshSdCardAsync();
                        log("verified mounted newUuid=" + current.uuid);
                        // Even if sm exited nonzero (some BYD ROMs swallow vold errors),
                        // observation of the new mounted UUID is decisive.
                        return new Result(true, "SD card formatted and mounted: " + current.uuid);
                    }
                    if (System.nanoTime() >= deadline) break;
                    Thread.sleep(MOUNT_POLL_INTERVAL_MS);
                } while (true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "Format outcome unverified: verification interrupted; card may have been erased. Inspect it.");
            }
            return new Result(false, "Format outcome unverified: no changed, mounted UUID on the original SD media (format: "
                    + formatResult.message + "; mount: " + mountResult.message
                    + "). Card may have been erased; inspect before retrying.");
        } finally {
            if (mountLockHeld) storage.releaseSdCardFormatLock();
            if (markerSaved) {
                // Remount even on refusal/error (e.g. after a partially completed vold call).
                if (!mountVerified && volumeId != null) command(COMMAND_TIMEOUT_MS, "sm", "mount", volumeId);
                boolean restored = !restoreDvr || restoreFactoryDvr();
                // Keep the marker for daemon-start recovery if mount or DVR restoration
                // was not verified; never silently clear unfinished cleanup.
                if (restored && volumeId != null && isMounted(volumeId)) clearMarker();
                else log("pending mount/DVR recovery retained for " + volumeId);
            }
            if (paused) {
                // Recover CDR cleaner's stale UUID/path after vold remount.
                try { cleaner.resumeAfterSdFormat(); }
                catch (Exception e) { log("cleaner resume/refresh failed: " + e); }
            }
            if (formatReserved) storage.setSdCardFormatActive(false);
            storage.refreshSdCardAsync();
        }
    }

    /** Format-only lookup: caller owns mountLock; never accepts a cached preview. */
    private Preview findTarget(StorageManager storage) {
        return inspectTargetBounded(storage, COMMAND_TIMEOUT_MS);
    }

    static String preflightFailureMessage(Preview preview, String identityFailure) {
        return preview != null && "storage_busy".equals(preview.reasonCode)
                ? "Storage is busy. Try again when SD activity has stopped."
                : identityFailure;
    }

    /** A stuck kernel StatFs cannot block the HTTP thread or mount lock indefinitely. */
    private Preview inspectTargetBounded(StorageManager storage, long timeoutMs) {
        Future<Preview> task;
        try {
            task = inspectionWorker.submit(() -> inspectTarget(storage, timeoutMs));
        } catch (RejectedExecutionException e) {
            // The sole probe worker may still be blocked in an uninterruptible StatFs.
            return new Preview(null, "storage_busy");
        }
        boolean completed = false;
        try {
            Preview preview = task.get(timeoutMs, TimeUnit.MILLISECONDS);
            completed = true;
            return preview;
        } catch (TimeoutException e) {
            log("SD inspection exceeded " + timeoutMs + " ms");
            return new Preview(null, "storage_busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Preview(null, "storage_busy");
        } catch (ExecutionException e) {
            log("SD inspection failed: " + e.getCause());
            return new Preview(null, "storage_busy");
        } finally {
            if (!completed) task.cancel(true);
        }
    }

    private Preview inspectTarget(StorageManager storage, long timeoutMs) {
        String path = storage.getSdCardPath();
        if (!storage.isSdCardAvailable() || path == null || !path.startsWith("/storage/"))
            return new Preview(null, "no_card");
        String uuid = path.substring("/storage/".length());
        if (!isValidUuid(uuid)) return new Preview(null, "no_card");
        Result listed = command(timeoutMs, "sm", "list-volumes", "all");
        // A busy or timed-out volume service is not evidence of card removal.
        if (!listed.success) return new Preview(null, "storage_busy");
        Target found = null;
        for (String line : listed.message.split("\\n")) {
            Volume volume = parseVolume(line);
            if (volume != null && uuid.equals(volume.uuid) && "mounted".equals(volume.state)) {
                if (found != null) return new Preview(null, "media_identity");
                found = new Target(uuid, volume.id, 0L, 0L, null);
            }
        }
        if (found == null) return new Preview(null, "no_card");
        try {
            // A card mounted for another user (BYD100F) is reached through MediaStore.
            long total = RemovableStorageFs.statFsTotalBytes(path);
            long free = RemovableStorageFs.statFsFreeBytes(path);
            if (total <= 0 || free < 0 || free > total)
                return new Preview(null, "storage_busy");
            Target target = new Target(uuid, found.volumeId, total, total - free,
                    mediaFingerprint(found.volumeId));
            return new Preview(target, target.identityVerifiable ? null : "media_identity");
        } catch (Exception e) {
            log("target space unavailable: " + e);
            return new Preview(null, "storage_busy");
        }
    }

    private Volume findVolumeById(String id) {
        Result listed = command(COMMAND_TIMEOUT_MS, "sm", "list-volumes", "all");
        if (!listed.success) return null;
        Volume found = null;
        for (String line : listed.message.split("\n")) {
            Volume volume = parseVolume(line);
            if (volume != null && id.equals(volume.id)) {
                if (found != null) return null;
                found = volume;
            }
        }
        return found;
    }

    static Volume parseVolume(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 3 || !StorageManager.isPublicVolumeId(fields[0])
                || StorageManager.parsePublicVolumeDevice(fields[0]) == null
                || !("mounted".equals(fields[1]) || "unmounted".equals(fields[1]))) return null;
        return new Volume(fields[0], fields[1], fields[2]);
    }
    static boolean isValidUuid(String uuid) {
        return uuid != null && uuid.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                && !"null".equalsIgnoreCase(uuid);
    }

    /** Shell can list processes but cannot read other UIDs' /proc fd trees. */
    private Result checkCdrStopped() {
        Result ps = command(COMMAND_TIMEOUT_MS, "ps", "-A");
        if (!ps.success || !ps.message.contains("PID") || !ps.message.contains("USER"))
            return new Result(false, "Cannot verify BYD CDR process state as shell; formatting blocked.");
        boolean otherUserVisible = false;
        for (String line : ps.message.split("\n")) {
            String[] cells = line.trim().split("\\s+");
            if (cells.length < 3 || "USER".equals(cells[0])) continue;
            if (!"shell".equals(cells[0])) otherUserVisible = true;
            String name = cells[cells.length - 1];
            if (name.equals("com.byd.cdr") || name.startsWith("com.byd.cdr:"))
                return new Result(false, "Factory BYD CDR is running. Stop it before formatting.");
        }
        if (!otherUserVisible)
            return new Result(false, "Process listing shows no non-shell users; CDR check is inconclusive.");
        return new Result(true, "BYD CDR is not running.");
    }

    private static boolean isCardPath(String path, String uuid) {
        if (path == null) return false;
        String[] roots = {"/storage/", "/mnt/media_rw/", "/mnt/user/", "/mnt/pass_through/",
                "/mnt/runtime/default/", "/mnt/runtime/read/", "/mnt/runtime/write/",
                "/mnt/runtime/full/"};
        for (String root : roots) {
            // /mnt/user/0/<UUID>, /mnt/pass_through/0/<UUID>, etc.
            if (root.equals("/mnt/user/") || root.equals("/mnt/pass_through/")) {
                if (path.matches(root + "[0-9]+/" + uuid + "(/.*| \\(deleted\\))?")) return true;
            } else if (path.equals(root + uuid) || path.startsWith(root + uuid + "/")
                    || path.startsWith(root + uuid + " (deleted)")) return true;
        }
        return false;
    }

    /** Only accessible processes; inaccessible processes are covered by the CDR process gate, not a fictitious full scan. */
    private Result scanAccessibleCardUse(String uuid) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROC_SCAN_TIMEOUT_MS);
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return new Result(false, "Cannot inspect local card users.");
        for (File proc : procs) {
            if (!proc.getName().matches("[0-9]+")) continue;
            if (System.nanoTime() > deadline) return new Result(false, "Card-use scan timed out.");
            File fdDir = new File(proc, "fd");
            File[] fds = fdDir.listFiles();
            if (fds == null) continue; // restricted other UID: best-effort only
            for (File fd : fds) {
                if (System.nanoTime() > deadline) return new Result(false, "Card-use scan timed out.");
                try {
                    if (isCardPath(Files.readSymbolicLink(fd.toPath()).toString(), uuid))
                        return new Result(false, "A process has an SD file open; formatting blocked.");
                } catch (Exception ignored) { /* raced close / inaccessible descriptor */ }
            }
            try {
                if (isCardPath(Files.readSymbolicLink(new File(proc, "cwd").toPath()).toString(), uuid))
                    return new Result(false, "A process is working on the SD card; formatting blocked.");
            } catch (Exception ignored) { }
            try (BufferedReader maps = new BufferedReader(new FileReader(new File(proc, "maps")))) {
                String line;
                while ((line = maps.readLine()) != null) {
                    if (System.nanoTime() > deadline) return new Result(false, "Card-use scan timed out.");
                    int slash = line.indexOf('/');
                    if (slash >= 0 && isCardPath(line.substring(slash), uuid))
                        return new Result(false, "A process has an SD memory map; formatting blocked.");
                }
            } catch (Exception ignored) { /* /proc/maps not exposed on other UIDs */ }
        }
        return new Result(true, "No accessible SD users found.");
    }

    private Result command(long timeout, String... args) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        final boolean[] truncated = {false};
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            final Process child = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 262_144) {
                                int available = 262_144 - output.length();
                                if (line.length() > available) truncated[0] = true;
                                output.append(line, 0, Math.min(line.length(), available));
                                output.append('\n');
                            } else truncated[0] = true;
                        }
                    }
                } catch (Exception e) { log("command stream read failed: " + e); }
            }, "SdFormat-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return new Result(false, "sm/ps command timed out");
            }
            reader.join(1_000L);
            String text;
            synchronized (output) { text = output.toString().trim(); }
            if (truncated[0]) return new Result(false, "Command output exceeded 256 KiB; inspect logs.");
            return new Result(process.exitValue() == 0, text.isEmpty()
                    ? "exit code " + process.exitValue() : text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new Result(false, "Command interrupted");
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result(false, "Command failed: " + e.getClass().getSimpleName());
        }
    }

    /** A kernel MMC CID (32 hex) on an SD-type device authorizes formatting. Shark 6 labels a
     *  lone USB stick SD via sys.byd.isSDExist, and diskseq also identifies USB disks, so neither
     *  may be a fallback; only BYD100F falls back to vold's SD disk record (no BYD property). */
    private static String mediaFingerprint(String volumeId) {
        String cid = mmcFingerprint(volumeId);
        if (cid != null || !DiLink100Platform.isByd100f()) return cid;
        return voldSlotFingerprint(volumeId);
    }

    private static String mmcFingerprint(String volumeId) {
        int[] dev = StorageManager.parsePublicVolumeDevice(volumeId);
        if (dev == null) return null;
        try {
            File node = new File("/sys/dev/block/" + dev[0] + ":" + dev[1]).getCanonicalFile();
            for (File cursor = node; cursor != null && cursor.getPath().startsWith("/sys/");
                    cursor = cursor.getParentFile()) {
                // For an mmcblk partition, device/cid and device/type are
                // exposed on its parent mmcblk device in sysfs.
                File cidFile = new File(cursor, "device/cid");
                File typeFile = new File(cursor, "device/type");
                if (!cidFile.isFile() || !typeFile.isFile()) continue;
                try (BufferedReader cidReader = new BufferedReader(new FileReader(cidFile));
                        BufferedReader typeReader = new BufferedReader(new FileReader(typeFile))) {
                    String cid = cidReader.readLine();
                    String type = typeReader.readLine();
                    // SD and MMC/eMMC both expose CID; MMC is not a removable SD.
                    if ("SD".equals(type == null ? null : type.trim()) && isValidMmcCid(cid))
                        return "mmc-cid:" + cid.toLowerCase(java.util.Locale.ROOT);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * BYD100F: the SD slot is an internal USB card reader, so the kernel has no SD CID. vold
     * still flags that disk SD (a USB stick is flagged USB). Bind to the only such disk by its
     * reader port and exact size; a swap mid-format to an identical card is out of scope.
     */
    private static String voldSlotFingerprint(String volumeId) {
        try {
            android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "mount");
            Object sm = Class.forName("android.os.storage.IStorageManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            String diskId = null;
            for (Object v : (Object[]) sm.getClass().getMethod("getVolumes", int.class).invoke(sm, 0)) {
                if (!volumeId.equals(v.getClass().getField("id").get(v))) continue;
                // VolumeInfo keeps the whole DiskInfo, not its id.
                Object disk = v.getClass().getMethod("getDisk").invoke(v);
                if (disk != null) diskId = (String) disk.getClass().getField("id").get(disk);
            }
            if (diskId == null) return null;
            String found = null;
            int sdDisks = 0;
            for (Object d : (Object[]) sm.getClass().getMethod("getDisks").invoke(sm)) {
                Class<?> c = d.getClass();
                int flags = c.getField("flags").getInt(d);
                String fingerprint = voldSlotFingerprint(flags, (String) c.getField("sysPath").get(d),
                        c.getField("size").getLong(d), (String) c.getField("label").get(d));
                if (fingerprint == null) continue;
                sdDisks++;
                if (diskId.equals(c.getField("id").get(d))) found = fingerprint;
            }
            if (sdDisks != 1) log("vold reports " + sdDisks + " SD disks; media identity refused");
            return sdDisks == 1 ? found : null;
        } catch (Throwable e) {
            log("vold disk identity unavailable: " + e);
            return null;
        }
    }

    /** DiskInfo FLAG_SD (4) without FLAG_USB (8), on a real device path, with a known size. */
    static String voldSlotFingerprint(int flags, String sysPath, long size, String label) {
        if ((flags & 4) == 0 || (flags & 8) != 0 || size <= 0 || sysPath == null) return null;
        String path = sysPath.replaceAll("/+", "/");
        if (!path.startsWith("/sys/devices/")) return null;
        // The block name (sda/sdb) follows detection order; the reader's port does not.
        int host = path.indexOf("/host");
        String port = host > 0 ? path.substring(0, host) : path;
        return "vold-sd:" + port + "|" + size + "|" + (label == null ? "" : label.trim());
    }

    static boolean isValidMmcCid(String cid) {
        return cid != null && cid.matches("[0-9a-fA-F]{32}");
    }

    private static File marker() { return ScratchRoot.file(RECOVERY_MARKER); }

    private static boolean saveMarker(String id, String uuid, boolean restoreDvr) {
        if (!StorageManager.isPublicVolumeId(id) || !isValidUuid(uuid) || marker().exists()) return false;
        File temp = ScratchRoot.file(RECOVERY_MARKER + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((id + "\n" + uuid + "\n" + (restoreDvr ? "1" : "0") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            log("format recovery journal write failed: " + e);
            return false;
        }
        return temp.renameTo(marker());
    }

    private static void clearMarker() {
        if (!marker().delete() && marker().exists()) log("could not clear format recovery journal");
    }

    /**
     * BYD100F runs com.byd.cdr as a persistent system app, which Android keeps alive after a
     * disable. Disabled, it starts no new recording, and vold's unmount closes its card files.
     */
    private boolean persistentDvrSurvivesDisable() {
        if (!DiLink100Platform.isByd100f()) return false;
        Result info = command(COMMAND_TIMEOUT_MS, "dumpsys", "package", "com.byd.cdr");
        boolean persistent = info.success && isPersistentPackage(info.message);
        log("factory DVR still running after disable; persistent=" + persistent);
        return persistent;
    }

    static boolean isPersistentPackage(String dumpsys) {
        for (String line : dumpsys.split("\n")) {
            String t = line.trim();
            if (t.startsWith("pkgFlags=[") && t.contains(" PERSISTENT ")) return true;
        }
        return false;
    }

    private boolean restoreFactoryDvr() {
        Result enabled = command(COMMAND_TIMEOUT_MS, "sh", "-c", "pm enable " + com.overdrive.app.util.ShellUser.flag() + " com.byd.cdr");
        Result listed = command(COMMAND_TIMEOUT_MS, "sh", "-c", "pm list packages " + com.overdrive.app.util.ShellUser.flag() + " -d");
        // Older DiLink builds reject `pm list packages -a`. A successful
        // `pm path` proves the enabled APK remains installed; -d proves the
        // package is no longer disabled for the current user.
        Result path = command(COMMAND_TIMEOUT_MS, "sh", "-c", "pm path " + com.overdrive.app.util.ShellUser.flag() + " com.byd.cdr");
        return enabled.success && listed.success && path.success
                && isFactoryDvrRestored(listed.message, path.message);
    }

    static boolean isFactoryDvrRestored(String disabledPackages, String packagePath) {
        if (packageListed(disabledPackages, "com.byd.cdr")) return false;
        for (String line : packagePath.split("\n")) {
            String path = line.trim();
            if (path.startsWith("package:/") && path.endsWith(".apk")) return true;
        }
        return false;
    }

    private static boolean packageListed(String result, String pkg) {
        for (String line : result.split("\n")) {
            if (("package:" + pkg).equals(line.trim())) return true;
        }
        return false;
    }

    private boolean isMounted(String id) {
        Volume current = findVolumeById(id);
        return current != null && "mounted".equals(current.state) && isValidUuid(current.uuid);
    }

    /** Called at daemon boot irrespective of which categories use SD. Never repeats format. */
    public synchronized void recoverAtDaemonStart() {
        if ("running".equals(job.state) || !marker().exists()) return;
        job = new Job("", "recovering", "Recovering mount after interrupted SD format.");
        worker.execute(() -> {
            boolean complete = false;
            try (BufferedReader in = new BufferedReader(new FileReader(marker()))) {
                String id = in.readLine(), uuid = in.readLine(), restore = in.readLine();
                if (!StorageManager.isPublicVolumeId(id) || !isValidUuid(uuid)
                        || !("1".equals(restore) || "0".equals(restore))) {
                    log("invalid recovery journal; manual inspection required");
                    return;
                }
                // Re-enable the recorder BEFORE the potentially ten-minute mount
                // retry; even a missing card must not strand the disabled DVR.
                boolean dvrRestored = !"1".equals(restore) || restoreFactoryDvr();
                StorageManager storage = StorageManager.getInstance();
                // A boot mount pass can hold mountLock longer than its five-second
                // tryLock timeout. Keep trying within the SAME ten-minute recovery
                // budget rather than abandoning the card and its recovery journal.
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                while (true) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                        log("boot recovery mount lock unavailable before deadline");
                        return;
                    }
                    if (storage.acquireSdCardFormatLock()) break;
                }
                try {
                    storage.setSdCardFormatActive(true);
                } finally {
                    storage.releaseSdCardFormatLock();
                }
                try {
                    boolean mounted = false;
                    do {
                        if (isMounted(id)) { mounted = true; break; }
                        command(COMMAND_TIMEOUT_MS, "sm", "mount", id);
                        if (isMounted(id)) { mounted = true; break; }
                        Thread.sleep(5_000L);
                    } while (System.nanoTime() < deadline);
                    complete = mounted && dvrRestored;
                    if (complete) clearMarker();
                } finally {
                    storage.setSdCardFormatActive(false);
                    storage.refreshSdCardAsync();
                }
            } catch (Exception e) {
                log("boot recovery failed: " + e);
            } finally {
                synchronized (SdCardFormatter.this) {
                    job = new Job("", "unverified", complete
                            ? "Interrupted format remounted; formatting result not verified. Inspect the card."
                            : "Remount/DVR recovery incomplete. Inspect the SD card before retrying.");
                }
            }
        });
    }

    private static void log(String message) { CameraDaemon.log("SD format: " + message); }

    /** Stable error codes for localized UI; raw message stays available for device logs. */
    public static String reasonCode(String message) {
        if (message == null) return "generic";
        if (message.startsWith("DiLink 100 multi-user removable storage")) return "dilink100_unsupported";
        if (message.startsWith("SD card identity changed") || message.startsWith("SD card changed")) return "changed";
        if (message.startsWith("Cannot verify SD media identity")) return "media_identity";
        if (message.startsWith("Move Overdrive")) return "overdrive_using_sd";
        if (message.startsWith("Factory BYD CDR is running")) return "cdr_running";
        if (message.startsWith("Cannot verify BYD CDR") || message.startsWith("Process listing")) return "cdr_check";
        if (message.startsWith("A process") || message.startsWith("Card-use scan")
                || message.startsWith("Cannot inspect")) return "card_busy";
        if (message.startsWith("Storage is busy")) return "storage_busy";
        if (message.startsWith("Could not stop SD cleanup")) return "cleaner";
        if (message.startsWith("Could not temporarily disable factory DVR")
                || message.startsWith("Factory DVR disable")) return "dvr_disable";
        if (message.startsWith("Cannot save format recovery intent")
                || message.startsWith("Previous format") || message.startsWith("Remount/DVR recovery")) return "recovery";
        if (message.startsWith("Format outcome unverified") || message.startsWith("Interrupted format")) return "unverified";
        return "generic";
    }

    public static final class Job {
        public final String id;
        public final String state;
        public final String message;
        Job(String id, String state, String message) {
            this.id = id; this.state = state; this.message = message;
        }
    }
    /** The preview reason is intentionally separate from the destructive job status. */
    public static final class Preview {
        public final Target target;
        public final String reasonCode;
        Preview(Target target, String reasonCode) {
            this.target = target; this.reasonCode = reasonCode;
        }
    }
    private static final class CachedPreview {
        final String key;
        final long createdNs;
        final Preview preview;
        CachedPreview(String key, long createdNs, Preview preview) {
            this.key = key; this.createdNs = createdNs; this.preview = preview;
        }
    }
    public static final class Target {
        public final String uuid;
        public final String volumeId;
        public final long capacityBytes;
        public final long usedBytes;
        public final boolean identityVerifiable;
        // Opaque binding of the confirmation to the physical media, not the UUID alone.
        // The raw CID must never be sent to the browser.
        public final String identityToken;
        final String fingerprint;
        Target(String uuid, String volumeId, long capacityBytes, long usedBytes, String fingerprint) {
            this.uuid = uuid; this.volumeId = volumeId;
            this.capacityBytes = capacityBytes; this.usedBytes = usedBytes;
            this.fingerprint = fingerprint;
            this.identityVerifiable = fingerprint != null;
            this.identityToken = fingerprint == null ? null : UUID.nameUUIDFromBytes(
                    (uuid + "\n" + volumeId + "\n" + fingerprint)
                            .getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
    static final class Volume {
        final String id, state, uuid;
        Volume(String id, String state, String uuid) {
            this.id = id; this.state = state; this.uuid = uuid;
        }
    }
    private static final class Result {
        final boolean success;
        final String message;
        Result(boolean success, String message) {
            this.success = success; this.message = message;
        }
    }
}
