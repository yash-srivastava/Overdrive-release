package com.overdrive.app.launcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, read-only display diagnostics. Call only from daemon workers. */
public final class ClusterDisplayReader {
    private ClusterDisplayReader() {}

    public static ClusterDisplaySnapshot read(int displayId) {
        if (displayId <= 0) return null;
        return ClusterDisplaySnapshot.find(readDisplayDump(), displayId);
    }

    public static String readDisplayDump() {
        return capture("/system/bin/dumpsys", "display");
    }

    public static String readWindowDisplays() {
        return capture("/system/bin/dumpsys", "window", "displays");
    }

    private static String capture(String... argv) {
        Process process = null;
        InputStream input = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            input = process.getInputStream();
            InputStream stream = input;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AtomicBoolean invalid = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count <= 2 * 1024 * 1024)
                            bytes.write(buffer, 0, count);
                        else invalid.set(true);
                    }
                } catch (Exception unreadable) { invalid.set(true); }
            }, "ClusterDisplayEvidence");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) return null;
            reader.join(300);
            if (reader.isAlive() || invalid.get() || process.exitValue() != 0) return null;
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (process != null) try { process.destroyForcibly(); } catch (Exception ignored) {}
            if (input != null) try { input.close(); } catch (Exception ignored) {}
        }
    }
}
