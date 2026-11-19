package com.overdrive.app.storage;

import android.os.StatFs;
import com.overdrive.app.daemon.CameraDaemon;

import com.overdrive.app.util.ScratchRoot;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Destructive SD format. Only a worker may run sm commands; the HTTP thread queues it. */
public final class SdCardFormatter {
    private static final long FORMAT_TIMEOUT_MS = 900_000L;
    private static final long COMMAND_TIMEOUT_MS = 15_000L;
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
    public synchronized Job submit(String expectedUuid, boolean stopFactoryDvr) {
        if ("running".equals(job.state) || "recovering".equals(job.state)) return job;
        if (marker().exists()) return new Job("", "failed",
                "Previous format needs mount/DVR recovery. Restart the daemon or inspect the card.");
        if (!isValidUuid(expectedUuid)) return new Job("", "failed", "Invalid SD card identity.");
        String id = UUID.randomUUID().toString();
        job = new Job(id, "running", "Formatting SD card…");
        log("queued job=" + id + " expectedUuid=" + expectedUuid);
        try {
            worker.execute(() -> {
                Result result;
                try {
                    result = format(expectedUuid, stopFactoryDvr);
                } catch (Throwable e) {
                    result = new Result(false, "Formatting failed unexpectedly; inspect the card before retrying.");
                    log("unexpected job failure: " + e);
                }
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

    /** Read-only preview; POST reidentifies the same card under the mount lock. */
    public Target target() {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock()) return null;
        try { return findTarget(storage); }
        finally { storage.releaseSdCardFormatLock(); }
    }

    private Result format(String expectedUuid, boolean stopFactoryDvr) {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock())
            return new Result(false, "Storage is busy. Try again when SD activity has stopped.");
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        boolean paused = false;
        boolean markerSaved = false;
        boolean restoreDvr = false;
        boolean mountVerified = false;
        String volumeId = null;
        try {
            Target target = findTarget(storage);
            if (target == null || !target.uuid.equals(expectedUuid))
                return new Result(false, "SD card identity changed or is unavailable. Review and confirm again.");
            volumeId = target.volumeId;
            if (target.fingerprint == null)
                return new Result(false, "Cannot verify SD media identity on this firmware; format blocked.");
            log("target uuid=" + target.uuid + " volumeId=" + target.volumeId
                    + " capacity=" + target.capacityBytes + " used=" + target.usedBytes);
            if (storage.getRecordingsStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getSurveillanceStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getTripsStorageType() == StorageManager.StorageType.SD_CARD)
                return new Result(false, "Move Overdrive recording, surveillance and trip storage off SD first.");

            // /proc/<other UID>/fd is inaccessible as shell on BYD. A shell-readable
            // process listing reliably detects com.byd.cdr, including :service peers.
            // Fail closed while ANY factory recorder process is running, not just
            // while its current file descriptor is visible (which it isn't).
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
                Result disabled = command(COMMAND_TIMEOUT_MS, "pm", "disable-user", "--user", "0", "com.byd.cdr");
                if (!disabled.success) return new Result(false, "Could not temporarily disable factory DVR: " + disabled.message);
                // Check the package state as well as the process table. A successful pm
                // exit alone is not proof that the recorder has been stopped.
                Result packages = command(COMMAND_TIMEOUT_MS, "pm", "list", "packages", "-d");
                if (!packages.success || !packageListed(packages.message, "com.byd.cdr"))
                    return new Result(false, "Factory DVR disable could not be verified.");
            }
            cdr = checkCdrStopped();
            if (!cdr.success) return cdr;
            // Best-effort additional coverage for processes readable as shell:
            // descriptors, working dirs, mmaps, and Android's alternate mount roots.
            Result busy = scanAccessibleCardUse(target.uuid);
            if (!busy.success) return busy;

            // Re-read media identity right before the irreversible command.
            Target currentTarget = findTarget(storage);
            if (currentTarget == null || !target.uuid.equals(currentTarget.uuid)
                    || !target.volumeId.equals(currentTarget.volumeId)
                    || !target.fingerprint.equals(currentTarget.fingerprint))
                return new Result(false, "SD card changed before format; operation blocked.");

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
            if (markerSaved) {
                // Remount even on refusal/error (e.g. after a partially completed vold call).
                if (!mountVerified && volumeId != null) command(COMMAND_TIMEOUT_MS, "sm", "mount", volumeId);
                boolean restored = !restoreDvr || restoreFactoryDvr();
                // Keep the marker for daemon-start recovery if mount or DVR restoration
                // was not verified; never silently clear unfinished cleanup.
                if (restored && volumeId != null && isMounted(volumeId)) clearMarker();
                else log("pending mount/DVR recovery retained for " + volumeId);
            }
            storage.releaseSdCardFormatLock();
            if (paused) {
                // Recover CDR cleaner's stale UUID/path after vold remount.
                try { cleaner.resumeAfterSdFormat(); }
                catch (Exception e) { log("cleaner resume/refresh failed: " + e); }
            }
            storage.refreshSdCardAsync();
        }
    }

    private Target findTarget(StorageManager storage) {
        String path = storage.getSdCardPath();
        if (!storage.isSdCardAvailable() || path == null || !path.startsWith("/storage/")) return null;
        String uuid = path.substring("/storage/".length());
        if (!isValidUuid(uuid)) return null;
        Result listed = command(COMMAND_TIMEOUT_MS, "sm", "list-volumes", "all");
        if (!listed.success) return null;
        Target found = null;
        for (String line : listed.message.split("\n")) {
            Volume volume = parseVolume(line);
            if (volume != null && uuid.equals(volume.uuid) && "mounted".equals(volume.state)) {
                if (found != null) return null;
                found = new Target(uuid, volume.id, 0L, 0L, null);
            }
        }
        if (found == null) return null;
        try {
            StatFs stat = new StatFs(path);
            long total = stat.getTotalBytes();
            long free = stat.getFreeBytes();
            if (total <= 0 || free < 0 || free > total) return null;
            return new Target(uuid, found.volumeId, total, total - free, mediaFingerprint(found.volumeId));
        } catch (Exception e) {
            log("target space unavailable: " + e);
            return null;
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

    /** Device identity must survive a vold filesystem change, but NOT a physical card swap.
     * MMC CID and Linux diskseq identify media, rather than merely the SD slot.
     * If neither is exposed, refuse an unsafe false-positive success.
     */
    private static String mediaFingerprint(String volumeId) {
        int[] dev = StorageManager.parsePublicVolumeDevice(volumeId);
        if (dev == null) return null;
        try {
            File node = new File("/sys/dev/block/" + dev[0] + ":" + dev[1]).getCanonicalFile();
            for (File cursor = node; cursor != null && cursor.getPath().startsWith("/sys/"); cursor = cursor.getParentFile()) {
                for (String key : new String[]{"device/cid", "cid", "diskseq"}) {
                    File candidate = new File(cursor, key);
                    if (!candidate.isFile()) continue;
                    try (BufferedReader in = new BufferedReader(new FileReader(candidate))) {
                        String value = in.readLine();
                        if (value != null && value.matches("[0-9A-Fa-f]{16,64}|[0-9]{1,20}"))
                            return key + ":" + value + ":" + cursor.getCanonicalPath();
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return null;
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

    private boolean restoreFactoryDvr() {
        Result enabled = command(COMMAND_TIMEOUT_MS, "pm", "enable", "com.byd.cdr");
        Result listed = command(COMMAND_TIMEOUT_MS, "pm", "list", "packages", "-d");
        Result installed = command(COMMAND_TIMEOUT_MS, "pm", "list", "packages", "-a");
        return enabled.success && listed.success && installed.success
                && !packageListed(listed.message, "com.byd.cdr")
                && packageListed(installed.message, "com.byd.cdr");
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
                StorageManager storage = StorageManager.getInstance();
                if (!storage.acquireSdCardFormatLock()) return;
                try {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    do {
                        if (isMounted(id)) { complete = true; break; }
                        command(COMMAND_TIMEOUT_MS, "sm", "mount", id);
                        if (isMounted(id)) { complete = true; break; }
                        Thread.sleep(5_000L);
                    } while (System.nanoTime() < deadline);
                    // A previous explicit temporary disable must be undone on restart,
                    // even if the card remains missing. Keep the marker when PM failed.
                    if ("1".equals(restore))
                        complete &= restoreFactoryDvr();
                    if (complete) clearMarker();
                    storage.refreshSdCardAsync();
                } finally { storage.releaseSdCardFormatLock(); }
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
    public static final class Target {
        public final String uuid;
        public final String volumeId;
        public final long capacityBytes;
        public final long usedBytes;
        public final boolean identityVerifiable;
        final String fingerprint;
        Target(String uuid, String volumeId, long capacityBytes, long usedBytes, String fingerprint) {
            this.uuid = uuid; this.volumeId = volumeId;
            this.capacityBytes = capacityBytes; this.usedBytes = usedBytes;
            this.fingerprint = fingerprint;
            this.identityVerifiable = fingerprint != null;
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
