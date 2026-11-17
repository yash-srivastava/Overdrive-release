package com.overdrive.app.storage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Explicit, destructive SD-card formatting via Android's public-volume manager.
 * No command takes a volume identifier from the network: the target is resolved
 * against the currently detected SD card while holding the mount/discovery lock.
 */
public final class SdCardFormatter {
    private static final long COMMAND_TIMEOUT_MS = 20_000L;
    private static final long MOUNT_POLL_MS = 30_000L;
    private static final long MOUNT_POLL_INTERVAL_MS = 1_000L;
    private static final long FD_SCAN_TIMEOUT_MS = 5_000L;
    private static final SdCardFormatter INSTANCE = new SdCardFormatter();

    public static SdCardFormatter getInstance() { return INSTANCE; }
    private SdCardFormatter() {}

    /** Read-only preview for the confirmation dialog; rechecked on POST. */
    public synchronized Target target() {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock()) return null;
        try {
            return findTarget(storage);
        } finally {
            storage.releaseSdCardFormatLock();
        }
    }

    public synchronized Result format(String expectedUuid) {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock()) {
            return new Result(false, "Storage is busy. Try again when SD activity has stopped.");
        }
        try {
            Target target = findTarget(storage);
            if (target == null || expectedUuid == null || !target.uuid.equals(expectedUuid)) {
                return new Result(false, "SD card identity changed or is unavailable. Review the card and confirm again.");
            }
            if (storage.getRecordingsStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getSurveillanceStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getTripsStorageType() == StorageManager.StorageType.SD_CARD) {
                return new Result(false, "Move all Overdrive recording, surveillance and trip storage off the SD card first.");
            }

            // `sm format` can kill every process with open files on the volume,
            // including BYD's CDR during a recording. Never stop/kill CDR on the
            // user's behalf. Require it to be stopped and refuse if /proc cannot
            // establish that the volume has no open descriptors. This is a
            // best-effort snapshot, not a promise against future OEM writes.
            Result inUse = checkOpenCardFiles(target.uuid);
            if (!inUse.success) return inUse;

            // A shell exit code of 0 is NOT proof of formatting. On some BYD
            // ROMs StorageManagerService logs a vold failure without rethrowing.
            Result command = command("sm", "format", target.volumeId);
            if (!command.success) {
                storage.refreshSdCardAsync();
                return new Result(false, "Android could not confirm formatting: " + command.message
                        + ". Check the card before trying again.");
            }

            // Try mounting by the same *physical* public-volume ID. The UUID
            // must change and the new UUID must be reported as mounted. A
            // successful `sm mount` exit alone is not evidence of either.
            Result mount = command("sm", "mount", target.volumeId);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MOUNT_POLL_MS);
            try {
                do {
                    Volume current = findVolumeById(target.volumeId);
                    if (current != null && !target.uuid.equals(current.uuid)
                            && isValidUuid(current.uuid) && "mounted".equals(current.state)) {
                        storage.refreshSdCardAsync();
                        return new Result(true, "SD card formatted and new volume " + current.uuid + " mounted.");
                    }
                    if (System.nanoTime() >= deadline) break;
                    Thread.sleep(MOUNT_POLL_INTERVAL_MS);
                } while (true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                storage.refreshSdCardAsync();
                return new Result(false, "Card state verification was interrupted; formatting may have occurred. Inspect the card.");
            }
            storage.refreshSdCardAsync();
            return new Result(false, "Card state unverified after format command (mount: " + mount.message
                    + "). No new mounted UUID was detected. Data may have been erased; inspect the card before retrying.");
        } finally {
            storage.releaseSdCardFormatLock();
        }
    }

    private Target findTarget(StorageManager storage) {
        String path = storage.getSdCardPath();
        if (!storage.isSdCardAvailable() || path == null || !path.startsWith("/storage/")) return null;
        String uuid = path.substring("/storage/".length());
        if (!isValidUuid(uuid)) return null;
        Result listed = command("sm", "list-volumes", "all");
        if (!listed.success) return null;
        Target found = null;
        for (String line : listed.message.split("\\n")) {
            Volume volume = parseVolume(line);
            if (volume != null && uuid.equals(volume.uuid) && "mounted".equals(volume.state)) {
                if (found != null) return null; // ambiguous: never guess which card
                found = new Target(uuid, volume.id);
            }
        }
        return found;
    }

    private Volume findVolumeById(String id) {
        Result listed = command("sm", "list-volumes", "all");
        if (!listed.success) return null;
        Volume found = null;
        for (String line : listed.message.split("\\n")) {
            Volume volume = parseVolume(line);
            if (volume != null && id.equals(volume.id)) {
                if (found != null) return null;
                found = volume;
            }
        }
        return found;
    }

    // Only removable public volumes; private/emulated and disk IDs are excluded.
    static Volume parseVolume(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 3 || !fields[0].matches("public:[0-9]+,[0-9]+")
                || !("mounted".equals(fields[1]) || "unmounted".equals(fields[1]))) return null;
        return new Volume(fields[0], fields[1], fields[2]);
    }

    static boolean isValidUuid(String uuid) {
        return uuid != null && uuid.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                && !"null".equalsIgnoreCase(uuid);
    }

    private static boolean isCardPath(String link, String uuid) {
        if (link == null) return false;
        // /proc/pid/fd symlinks can include the suffix " (deleted)".
        if (under(link, "/storage/" + uuid) || under(link, "/mnt/media_rw/" + uuid)) return true;
        return under(link, "/mnt/runtime/default/" + uuid)
                || under(link, "/mnt/runtime/read/" + uuid)
                || under(link, "/mnt/runtime/write/" + uuid);
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/") || path.startsWith(root + " (deleted)");
    }

    /** Fail closed if the process table cannot be fully inspected. */
    private Result checkOpenCardFiles(String uuid) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FD_SCAN_TIMEOUT_MS);
        File[] processes = new File("/proc").listFiles();
        if (processes == null) return new Result(false, "Cannot inspect card users; leave BYD CDR stopped and try on a supported device.");
        for (File process : processes) {
            if (System.nanoTime() > deadline) return new Result(false, "Process inspection timed out; formatting blocked.");
            if (!process.getName().matches("[0-9]+")) continue;
            File[] fds = new File(process, "fd").listFiles();
            if (fds == null) {
                if (process.exists()) return new Result(false, "Cannot verify that all processes have closed the SD card; formatting blocked.");
                continue; // process terminated between the two reads
            }
            for (File fd : fds) {
                if (System.nanoTime() > deadline) return new Result(false, "Process inspection timed out; formatting blocked.");
                try {
                    if (isCardPath(Files.readSymbolicLink(fd.toPath()).toString(), uuid)) {
                        return new Result(false, "An app (possibly BYD CDR) still has files open on this card. Stop recording and retry.");
                    }
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // An FD was closed while /proc was being inspected.
                } catch (Exception e) {
                    return new Result(false, "Cannot verify that the SD card is idle; formatting blocked.");
                }
            }
        }
        return new Result(true, "No open card files observed.");
    }

    private Result command(String... args) {
        Process process = null;
        final StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            final Process child = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < 16_384) output.append(line, 0, Math.min(line.length(), 16_384 - output.length())).append('\n');
                        }
                    }
                } catch (Exception ignored) { }
            }, "SdFormat-command-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return new Result(false, "sm command timed out");
            }
            reader.join(1_000L);
            String text;
            synchronized (output) { text = output.toString().trim(); }
            return new Result(process.exitValue() == 0, text.isEmpty() ? "exit code " + process.exitValue() : text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new Result(false, "sm command interrupted");
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public static final class Target {
        public final String uuid;
        public final String volumeId;
        Target(String uuid, String volumeId) { this.uuid = uuid; this.volumeId = volumeId; }
    }

    static final class Volume {
        final String id;
        final String state;
        final String uuid;
        Volume(String id, String state, String uuid) {
            this.id = id; this.state = state; this.uuid = uuid;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        Result(boolean success, String message) { this.success = success; this.message = message; }
    }
}
