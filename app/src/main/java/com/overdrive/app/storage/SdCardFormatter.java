package com.overdrive.app.storage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Destructive, explicitly requested formatting of the detected SD public volume. */
public final class SdCardFormatter {
    private static final long COMMAND_TIMEOUT_MS = 20_000L;
    private static final SdCardFormatter INSTANCE = new SdCardFormatter();

    public static SdCardFormatter getInstance() { return INSTANCE; }
    private SdCardFormatter() {}

    public synchronized Result format() {
        StorageManager storage = StorageManager.getInstance();
        if (!storage.acquireSdCardFormatLock()) {
            return new Result(false, "Storage is busy. Wait for SD card activity to finish and try again.");
        }
        try {
            String path = storage.getSdCardPath();
            if (path == null || !path.startsWith("/storage/")) {
                return new Result(false, "Could not identify the SD card volume.");
            }
            if (storage.getRecordingsStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getSurveillanceStorageType() == StorageManager.StorageType.SD_CARD
                    || storage.getTripsStorageType() == StorageManager.StorageType.SD_CARD) {
                return new Result(false, "Move recordings, surveillance and trip storage off the SD card before formatting.");
            }

            // Capture the UUID before BYD's dashcam can unmount the public volume.
            // `sm format` delegates to StorageManagerService rather than issuing a
            // direct vold request from the app daemon's shell process.
            String uuid = path.substring("/storage/".length());
            if (uuid.isEmpty() || uuid.contains("/") || ".".equals(uuid) || "..".equals(uuid)) {
                return new Result(false, "Could not identify the SD card volume safely.");
            }
            String volumeId = findVolumeId(uuid);
            if (volumeId == null) {
                return new Result(false, "Could not uniquely identify the SD card volume. Reinsert the card and retry.");
            }

            Result formatted = command("sm", "format", volumeId);
            if (!formatted.success) {
                Result remountAfterFailure = command("sm", "mount", volumeId);
                storage.refreshSdCardAsync();
                return new Result(false, "Formatting failed: " + formatted.message
                        + (remountAfterFailure.success ? " The SD card was remounted."
                        : " The SD card could not be remounted."));
            }

            Result remount = command("sm", "mount", volumeId);
            storage.refreshSdCardAsync();
            if (!remount.success) {
                return new Result(false, "The card was formatted, but remount failed: " + remount.message);
            }
            return new Result(true, "SD card formatted by Android Storage Manager and remounted.");
        } finally {
            storage.releaseSdCardFormatLock();
        }
    }

    private String findVolumeId(String uuid) {
        Result result = command("sm", "list-volumes", "all");
        if (!result.success) return null;
        String found = null;
        for (String line : result.message.split("\\n")) {
            String[] fields = line.trim().split("\\s+");
            // BYD's dashcam may unmount the card before this request reaches us.
            // Accept only public volumes in mounted/unmounted states, and require
            // an exact UUID match; ambiguous matches fail closed.
            if (fields.length >= 3 && fields[0].matches("(?:public:)?[0-9,:]+")
                    && ("mounted".equals(fields[1]) || "unmounted".equals(fields[1]))
                    && uuid.equals(fields[2])) {
                if (found != null) return null;
                found = fields[0];
            }
        }
        return found;
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
                    while ((line = in.readLine()) != null) synchronized (output) { output.append(line).append('\n'); }
                } catch (Exception ignored) { }
            }, "SdFormat-command-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return new Result(false, "Command timed out: " + args[0]);
            }
            reader.join(1_000L);
            String text;
            synchronized (output) { text = output.toString().trim(); }
            return new Result(process.exitValue() == 0, text.isEmpty() ? "exit code " + process.exitValue() : text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new Result(false, "Command interrupted.");
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        Result(boolean success, String message) { this.success = success; this.message = message; }
    }
}
