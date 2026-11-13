package com.overdrive.app.monitor;

import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures and parses one bounded Android Toybox top snapshot.
 *
 * <p>This is deliberately request-scoped: no resident top process, scheduler or
 * background thread survives a completed HTTP request. The web client decides
 * when snapshots are requested based on page and subview visibility.
 */
public final class TopSnapshotCollector {

    private static final int MIN_ROWS = 5;
    private static final int MAX_ROWS = 24;
    private static final int COMMAND_ROWS = MAX_ROWS + 4;
    private static final long COMMAND_TIMEOUT_MS = 3_000L;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final long CACHE_MS = 750L;

    private static final Object CAPTURE_LOCK = new Object();
    private static JSONObject cachedSnapshot;
    // Measured since boot, not wall clock: GPS/NTP corrections must not extend
    // the 750 ms cache lifetime or cause repeated top processes.
    private static long cachedAtElapsedMs;
    // Guarded by CAPTURE_LOCK; remember unsupported Toybox -m syntax.
    private static boolean limitedTopUnsupported;

    private static final Pattern TASKS_PATTERN = Pattern.compile(
            "^Tasks:\\s*(\\d+)\\s+total,\\s*(\\d+)\\s+running,\\s*"
                    + "(\\d+)\\s+sleeping,\\s*(\\d+)\\s+stopped,\\s*"
                    + "(\\d+)\\s+zombie",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CPU_TOKEN_PATTERN =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)%([A-Za-z]+)");
    private static final Pattern MEMORY_TOKEN_PATTERN =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?\\s*[KMGTPE]?)\\s+([A-Za-z]+)");
    private static final Pattern PROCESS_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+"
                    + "(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([A-Za-z])\\s+"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s+"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s+(\\S+)\\s+(.+)$");

    private TopSnapshotCollector() {}

    public static JSONObject capture(int requestedRows) {
        int rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, requestedRows));
        synchronized (CAPTURE_LOCK) {
            long elapsedNow = android.os.SystemClock.elapsedRealtime();
            if (cachedSnapshot != null && isCacheFresh(cachedAtElapsedMs, elapsedNow)) {
                return copyWithLimit(cachedSnapshot, rows);
            }

            // Keep the public snapshot timestamp as epoch milliseconds. Only
            // internal cache expiry uses the monotonic clock.
            long now = System.currentTimeMillis();
            long startedAt = elapsedNow;
            try {
                boolean useLimit = !limitedTopUnsupported;
                CommandResult result = runTop(useLimit);
                JSONObject parsed = parseSnapshot(
                        result.output,
                        MAX_ROWS,
                        Process.myPid(),
                        now,
                        android.os.SystemClock.elapsedRealtime() - startedAt,
                        useLimit ? "top -b -n 1 -m " + COMMAND_ROWS : "top -b -n 1");

                // Older Toybox builds reject -m. Remember this only when the
                // portable command succeeds, avoiding a failed subprocess on
                // every subsequent request. Other failures remain retryable.
                if (useLimit && !parsed.optBoolean("available", false)
                        && result.exitCode != 0) {
                    startedAt = android.os.SystemClock.elapsedRealtime();
                    CommandResult fallback = runTop(false);
                    JSONObject fallbackParsed = parseSnapshot(
                            fallback.output,
                            MAX_ROWS,
                            Process.myPid(),
                            now,
                            android.os.SystemClock.elapsedRealtime() - startedAt,
                            "top -b -n 1");
                    if (fallbackParsed.optBoolean("available", false)) {
                        limitedTopUnsupported = true;
                    }
                    result = fallback;
                    parsed = fallbackParsed;
                }

                if (!parsed.optBoolean("available", false) && result.timedOut) {
                    parsed.put("error", "top timed out");
                } else if (!parsed.optBoolean("available", false) && result.exitCode != 0) {
                    parsed.put("error", "top exited with code " + result.exitCode);
                }
                cachedSnapshot = parsed;
                // Start the short reuse window when the capture completes. A
                // slow Toybox invocation should not expire the cache before
                // callers have had a chance to reuse the finished result.
                cachedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
                return copyWithLimit(parsed, rows);
            } catch (Throwable t) {
                JSONObject error = new JSONObject();
                try {
                    error.put("available", false);
                    error.put("timestamp", now);
                    error.put("error", t.getMessage() == null
                            ? t.getClass().getSimpleName()
                            : t.getMessage());
                } catch (Exception ignored) {}
                return error;
            }
        }
    }

    /** Pure clock math, isolated for regression coverage without spawning top. */
    static boolean isCacheFresh(long cachedAtElapsedMs, long elapsedNowMs) {
        return elapsedNowMs >= cachedAtElapsedMs
                && elapsedNowMs - cachedAtElapsedMs <= CACHE_MS;
    }

    static JSONObject parseSnapshot(
            String output,
            int rowLimit,
            int selfPid,
            long timestamp,
            long durationMs,
            String source) {
        JSONObject root = new JSONObject();
        JSONArray processes = new JSONArray();
        JSONObject tasks = new JSONObject();
        JSONObject memory = new JSONObject();
        JSONObject swap = new JSONObject();
        JSONObject cpu = new JSONObject();
        boolean processRows = false;

        try {
            String[] lines = output == null ? new String[0] : output.split("\\r?\\n");
            for (String originalLine : lines) {
                String line = originalLine == null ? "" : originalLine.trim();
                if (line.isEmpty()) continue;

                Matcher tasksMatcher = TASKS_PATTERN.matcher(line);
                if (tasksMatcher.find()) {
                    tasks.put("total", parseInt(tasksMatcher.group(1)));
                    tasks.put("running", parseInt(tasksMatcher.group(2)));
                    tasks.put("sleeping", parseInt(tasksMatcher.group(3)));
                    tasks.put("stopped", parseInt(tasksMatcher.group(4)));
                    tasks.put("zombie", parseInt(tasksMatcher.group(5)));
                    continue;
                }
                if (line.startsWith("Mem:")) {
                    parseMemoryLine(line, memory);
                    continue;
                }
                if (line.startsWith("Swap:")) {
                    parseMemoryLine(line, swap);
                    continue;
                }
                if (line.contains("%cpu")) {
                    parseCpuLine(line, cpu);
                    continue;
                }
                if (line.startsWith("PID ") && line.contains("%CPU")) {
                    processRows = true;
                    continue;
                }
                if (!processRows || processes.length() >= rowLimit) continue;

                Matcher processMatcher = PROCESS_PATTERN.matcher(originalLine);
                if (!processMatcher.matches()) continue;
                JSONObject process = new JSONObject();
                int pid = parseInt(processMatcher.group(1));
                String command = processMatcher.group(12).trim();
                process.put("pid", pid);
                process.put("user", processMatcher.group(2));
                process.put("priority", processMatcher.group(3));
                process.put("nice", processMatcher.group(4));
                process.put("virtual", processMatcher.group(5));
                process.put("resident", processMatcher.group(6));
                process.put("shared", processMatcher.group(7));
                process.put("virtualBytes", parseHumanBytes(processMatcher.group(5)));
                process.put("residentBytes", parseHumanBytes(processMatcher.group(6)));
                process.put("sharedBytes", parseHumanBytes(processMatcher.group(7)));
                process.put("state", processMatcher.group(8));
                process.put("cpuPercent", parseDouble(processMatcher.group(9)));
                process.put("memoryPercent", parseDouble(processMatcher.group(10)));
                process.put("cpuTime", processMatcher.group(11));
                process.put("command", command);
                process.put("isOverdrive", pid == selfPid
                        || command.contains("byd_cam_daemon")
                        || command.contains("com.overdrive.app"));
                process.put("isSampler", command.equals("top") || command.startsWith("top "));
                processes.put(process);
            }

            double capacity = cpu.optDouble("capacityPercent", 0.0);
            double idle = cpu.optDouble("idlePercent", 0.0);
            if (capacity > 0.0) {
                double busy = Math.max(0.0, capacity - idle);
                cpu.put("busyPercent", round1(busy));
                cpu.put("deviceBusyPercent", round1((busy / capacity) * 100.0));
            }

            root.put("available", processes.length() > 0);
            root.put("timestamp", timestamp);
            root.put("durationMs", durationMs);
            root.put("source", source);
            root.put("tasks", tasks);
            root.put("memory", memory);
            root.put("swap", swap);
            root.put("cpu", cpu);
            root.put("processes", processes);
            root.put("processCount", processes.length());
        } catch (Exception ignored) {
            try {
                root.put("available", false);
                root.put("timestamp", timestamp);
                root.put("processes", processes);
            } catch (Exception ignoredAgain) {}
        }
        return root;
    }

    private static CommandResult runTop(boolean withLimit) throws Exception {
        ProcessBuilder builder = withLimit
                ? new ProcessBuilder("top", "-b", "-n", "1", "-m",
                        Integer.toString(COMMAND_ROWS))
                : new ProcessBuilder("top", "-b", "-n", "1");
        builder.redirectErrorStream(true);
        java.lang.Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = process.getInputStream();

        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int kept = 0;
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (kept >= MAX_OUTPUT_BYTES) continue;
                    int copy = Math.min(read, MAX_OUTPUT_BYTES - kept);
                    output.write(buffer, 0, copy);
                    kept += copy;
                }
            } catch (Throwable ignored) {
                // Process teardown closes the stream on timeout.
            }
        }, "TopSnapshotDrain");
        drain.setDaemon(true);
        drain.start();

        boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(500L, TimeUnit.MILLISECONDS);
        }
        try { input.close(); } catch (Throwable ignored) {}
        drain.join(750L);

        int exitCode = finished ? process.exitValue() : -1;
        return new CommandResult(
                new String(output.toByteArray(), StandardCharsets.UTF_8),
                exitCode,
                !finished);
    }

    private static void parseMemoryLine(String line, JSONObject target) throws Exception {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 >= line.length()) return;
        String[] fields = line.substring(colon + 1).split(",");
        for (String field : fields) {
            Matcher matcher = MEMORY_TOKEN_PATTERN.matcher(field.trim());
            if (!matcher.find()) continue;
            String label = matcher.group(2).toLowerCase(Locale.US);
            target.put(label + "Bytes", parseHumanBytes(matcher.group(1)));
        }
    }

    private static void parseCpuLine(String line, JSONObject cpu) throws Exception {
        Matcher matcher = CPU_TOKEN_PATTERN.matcher(line);
        while (matcher.find()) {
            double value = parseDouble(matcher.group(1));
            String label = matcher.group(2).toLowerCase(Locale.US);
            switch (label) {
                case "cpu": cpu.put("capacityPercent", round1(value)); break;
                case "user": cpu.put("userPercent", round1(value)); break;
                case "nice": cpu.put("nicePercent", round1(value)); break;
                case "sys": cpu.put("systemPercent", round1(value)); break;
                case "idle": cpu.put("idlePercent", round1(value)); break;
                case "iow": cpu.put("ioWaitPercent", round1(value)); break;
                case "irq": cpu.put("irqPercent", round1(value)); break;
                case "sirq": cpu.put("softIrqPercent", round1(value)); break;
                case "host": cpu.put("hostPercent", round1(value)); break;
                default: break;
            }
        }
    }

    private static long parseHumanBytes(String raw) {
        if (raw == null) return 0L;
        String value = raw.trim().toUpperCase(Locale.US).replace(" ", "");
        if (value.endsWith("B")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return 0L;

        double multiplier = 1.0;
        char suffix = value.charAt(value.length() - 1);
        switch (suffix) {
            case 'K': multiplier = 1024.0; break;
            case 'M': multiplier = 1024.0 * 1024.0; break;
            case 'G': multiplier = 1024.0 * 1024.0 * 1024.0; break;
            case 'T': multiplier = 1024.0 * 1024.0 * 1024.0 * 1024.0; break;
            case 'P': multiplier = 1024.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0; break;
            case 'E': multiplier = Math.pow(1024.0, 6.0); break;
            default: suffix = 0; break;
        }
        if (suffix != 0) value = value.substring(0, value.length() - 1);
        try {
            return Math.max(0L, Math.round(Double.parseDouble(value) * multiplier));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static JSONObject copyWithLimit(JSONObject source, int rows) {
        try {
            JSONObject copy = new JSONObject(source.toString());
            JSONArray all = copy.optJSONArray("processes");
            if (all == null || all.length() <= rows) return copy;
            JSONArray limited = new JSONArray();
            for (int i = 0; i < rows; i++) limited.put(all.get(i));
            copy.put("processes", limited);
            copy.put("processCount", limited.length());
            return copy;
        } catch (Exception ignored) {
            return source;
        }
    }

    private static final class CommandResult {
        final String output;
        final int exitCode;
        final boolean timedOut;

        CommandResult(String output, int exitCode, boolean timedOut) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }
    }
}
