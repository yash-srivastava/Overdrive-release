package com.overdrive.app.automation;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Shared file operations; callers own locking, backup eligibility and recovery policy. */
final class AutomationStoreFiles {
    private AutomationStoreFiles() {}

    static JSONObject readObject(File file) throws IOException, JSONException {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /** Whether the live file holds a parseable JSON object and may replace the backup. */
    static boolean isParseable(File file, String label, Consumer<String> logWarning) {
        try {
            readObject(file);
            return true;
        } catch (Exception e) {
            logWarning.accept("Live " + label + " config is unparseable — keeping the existing backup");
            return false;
        }
    }

    /** Sync scratch bytes before preparing the backup and replacing the live file. */
    static boolean save(File scratch, File live, File backup, byte[] bytes,
                        Predicate<File> backupAllowed, String label, Consumer<String> logError) {
        try (FileOutputStream out = new FileOutputStream(scratch)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException e) {
            logError.accept("Failed to write " + label + " scratch file");
            return false;
        }
        if (live.exists() && backupAllowed.test(live)) {
            copy(live, backup, "Failed to back up " + label, logError);
        }
        if (!scratch.renameTo(live)) {
            logError.accept("Failed to promote " + label + " scratch file");
            return false;
        }
        return true;
    }

    /** Best-effort copy, retaining the caller's diagnostic on failure. */
    static void copy(File from, File to, String failureMessage, Consumer<String> logError) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logError.accept(failureMessage);
        }
    }
}
