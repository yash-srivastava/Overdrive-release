package com.overdrive.app.launcher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.overdrive.app.util.ScratchPaths;

/** Lightweight app_process helper used by the zrok watchdog. */
public final class ZrokRuntimeProbe {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int BACKEND_PORT = 8080;
    private static final int PROXY_PORT = 8119;
    private static final int PORT_TIMEOUT_MS = 500;
    private static final int HTTP_TIMEOUT_MS = 5_000;
    private static final Pattern VERSION = Pattern.compile("(?i)(?:^|[^0-9])v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern SHARE_URL =
            Pattern.compile("https://([a-z0-9-]+)\\.share\\.zrok\\.io", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_LEVEL = Pattern.compile(
            "\"level\"\\s*:\\s*\"(?:error|fatal|panic)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_MESSAGE = Pattern.compile(
            "\"msg\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern ZROK_SECRET_COMMAND = Pattern.compile(
            "(?is)(\\bzrok\\s+(?:enable|share\\s+reserved)\\s+)(.*?)"
                    + "(\\s+(?:\\$ZROK_OVERRIDE\\s+)?--headless\\b)");
    private static final Pattern RESERVED_TOKEN_OUTPUT = Pattern.compile(
            "(?i)(token\\s+is\\s+')[^']+(')");

    private ZrokRuntimeProbe() {}

    private static void write(FileDescriptor descriptor, String value) {
        try {
            FileOutputStream output = new FileOutputStream(descriptor);
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) return;
        try {
            switch (args[0]) {
                case "proxy":
                    long waitMs = args.length > 1 ? parseLong(args[1], 5_000L) : 5_000L;
                    write(FileDescriptor.out, (waitForProxy(waitMs) ? "PROXY" : "DIRECT") + "\n");
                    break;
                case "supports-override":
                    write(FileDescriptor.out, (args.length > 1 && supportsEndpointOverride(args[1]) ? "1" : "0") + "\n");
                    break;
                case "watch":
                    watch(args);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            write(FileDescriptor.err, "ZrokRuntimeProbe: " + t.getMessage() + "\n");
        }
    }

    static boolean supportsOverrideVersion(String output) {
        if (output == null) return false;
        Matcher matcher = VERSION.matcher(output);
        if (!matcher.find()) return false;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major > 0 || minor >= 4;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean isStaleStatus(Integer status) {
        return status != null && (status == 502 || status == 503 || status == 504);
    }

    static int nextEdgeFailureCount(boolean localAvailable, Integer status, int current) {
        if (!localAvailable) return 0;
        if (status == null || status <= 0) return current;
        return isStaleStatus(status) ? current + 1 : 0;
    }

    public static String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\\''") + "'";
    }

    public static String redactCommand(String command) {
        if (command == null || command.isEmpty()) return command;
        return ZROK_SECRET_COMMAND.matcher(command)
                .replaceAll("$1[REDACTED]$3");
    }

    public static String redactOutput(String output) {
        if (output == null || output.isEmpty()) return output;
        return RESERVED_TOKEN_OUTPUT.matcher(output)
                .replaceAll("$1[REDACTED]$2");
    }

    /**
     * Extract the useful error from zrok's one-JSON-object-per-line output.
     * Returns an empty string when the output contains no recognizable failure.
     */
    public static String extractErrorMessage(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        String[] lines = output.split("\\r?\\n");

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (!ERROR_LEVEL.matcher(line).find()) continue;
            Matcher message = JSON_MESSAGE.matcher(line);
            if (message.find()) {
                return compact(unescapeJsonString(message.group(1)));
            }
        }

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("contacting the zrok service")) continue;
            if (lower.contains("error")
                    || lower.contains("failed")
                    || lower.contains("panic")
                    || lower.contains("unauthorized")
                    || lower.contains("forbidden")
                    || lower.contains("permission denied")
                    || lower.contains("connection refused")
                    || lower.contains("timed out")
                    || lower.contains("timeout")) {
                Matcher message = JSON_MESSAGE.matcher(line);
                return compact(message.find()
                        ? unescapeJsonString(message.group(1))
                        : line);
            }
        }
        return "";
    }

    public static String summarizeFailure(String output) {
        String extracted = extractErrorMessage(output);
        if (!extracted.isEmpty()) return extracted;
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                String compact = compact(line);
                if (!compact.isEmpty()
                        && !compact.toLowerCase(Locale.ROOT)
                                .contains("contacting the zrok service")) {
                    return compact;
                }
            }
        }
        return "Unknown zrok error";
    }

    private static String compact(String value) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 320
                ? compact.substring(0, 319) + "…"
                : compact;
    }

    private static String unescapeJsonString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                out.append(current);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(
                                    value.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ignored) {}
                    }
                    out.append("\\u");
                    break;
                default:
                    out.append(escaped);
                    break;
            }
        }
        return out.toString();
    }

    private static boolean supportsEndpointOverride(String zrokPath) {
        String version = runCommand(zrokPath, "version");
        if (!supportsOverrideVersion(version)) return false;
        String help = runCommand(zrokPath, "share", "reserved", "--help");
        return help != null && help.contains("override-endpoint");
    }

    private static void watch(String[] args) {
        if (args.length < 9) return;
        int zrokPid = (int) parseLong(args[1], -1L);
        File uniqueNameFile = new File(args[2]);
        File sentinel = new File(args[3]);
        File parked = new File(args[4]);
        File logFile = new File(args[5]);
        long initialDelayMs = parseLong(args[6], 60L) * 1_000L;
        long intervalMs = parseLong(args[7], 60L) * 1_000L;
        int strikeLimit = (int) parseLong(args[8], 2L);
        if (zrokPid <= 0 || strikeLimit <= 0) return;

        if (!sleep(initialDelayMs)) return;
        int failures = 0;
        boolean localWasDown = false;
        String lastName = "";

        while (isZrokProcess(zrokPid)) {
            if (sentinel.exists() || parked.exists()) return;

            String loggedName = readLastShareName(logFile);
            if (!loggedName.isEmpty()) lastName = loggedName;
            String name = lastName.isEmpty() ? readFirstLine(uniqueNameFile) : lastName;
            if (name.isEmpty()) {
                if (!sleep(intervalMs)) return;
                continue;
            }

            boolean localAvailable = portOpen(BACKEND_PORT, PORT_TIMEOUT_MS);
            if (!localAvailable) {
                failures = nextEdgeFailureCount(false, null, failures);
                if (!localWasDown) {
                    appendLog(logFile, "Local origin 127.0.0.1:8080 is unavailable; preserving zrok session");
                }
                localWasDown = true;
                if (!sleep(intervalMs)) return;
                continue;
            }
            if (localWasDown) {
                appendLog(logFile, "Local origin recovered");
                localWasDown = false;
            }

            String url = "https://" + name + ".share.zrok.io";
            Integer status = probeStatus(url);
            int nextFailures = nextEdgeFailureCount(true, status, failures);

            if (isStaleStatus(status)) {
                failures = nextFailures;
                appendLog(logFile, "Edge probe got HTTP " + status + " for " + name
                        + " (consecutive=" + failures + ")");
                if (failures >= strikeLimit) {
                    appendLog(logFile, "Edge stale confirmed; terminating zrok pid " + zrokPid);
                    terminateZrok(zrokPid);
                    return;
                }
            } else if (status == null || status <= 0) {
                failures = nextFailures;
            } else {
                if (failures > 0) {
                    appendLog(logFile, "Edge probe recovered (HTTP " + status + ")");
                }
                failures = nextFailures;
            }

            if (!sleep(intervalMs)) return;
        }
    }

    static Integer probeStatus(String url) {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = portOpen(PROXY_PORT, PORT_TIMEOUT_MS)
                    ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress(LOOPBACK, PROXY_PORT))
                    : Proxy.NO_PROXY;
            connection = (HttpURLConnection) new URL(url).openConnection(proxy);
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            return connection.getResponseCode();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static boolean waitForProxy(long waitMs) {
        long started = System.currentTimeMillis();
        do {
            if (portOpen(PROXY_PORT, PORT_TIMEOUT_MS)) return true;
            if (!sleep(250L)) return false;
        } while (System.currentTimeMillis() - started < waitMs);
        return portOpen(PROXY_PORT, PORT_TIMEOUT_MS);
    }

    private static boolean portOpen(int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isZrokProcess(int pid) {
        String cmdline = readCmdline(new File("/proc/" + pid + "/cmdline"));
        return cmdline.contains(ScratchPaths.path("zrok")) && cmdline.contains("share");
    }


    private static String readCmdline(File file) {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < 8_192) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8").replace('\0', ' ');
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readLastShareName(File file) {
        String last = "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Starting zrok share...")) last = "";
                String found = extractLastShareName(line);
                if (!found.isEmpty()) last = found;
            }
        } catch (Exception ignored) {}
        return last;
    }

    static String extractLastShareName(String text) {
        if (text == null) return "";
        String last = "";
        for (String line : text.split("\r?\n")) {
            if (line.contains("Starting zrok share...")) last = "";
            Matcher matcher = SHARE_URL.matcher(line);
            while (matcher.find()) last = matcher.group(1);
        }
        return last;
    }

    private static void terminateZrok(int pid) {
        if (!isZrokProcess(pid)) return;
        signal(pid, false);
        sleep(2_000L);
        if (isZrokProcess(pid)) signal(pid, true);
    }

    private static void signal(int pid, boolean force) {
        try {
            ProcessBuilder builder = force
                    ? new ProcessBuilder("/system/bin/kill", "-9", String.valueOf(pid))
                    : new ProcessBuilder("/system/bin/kill", String.valueOf(pid));
            builder.redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
    }

    private static String runCommand(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().put("HOME", ScratchPaths.getDir());
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copyOutput(process.getInputStream(), output), "zrok-version-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            reader.join(1_000L);
            return reader.isAlive() ? "" : output.toString("UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void copyOutput(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[1_024];
        try (InputStream stream = input) {
            int read;
            while (output.size() < 65_536 && (read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, Math.min(read, 65_536 - output.size()));
            }
        } catch (Exception ignored) {}
    }

    private static void appendLog(File file, String message) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write("[" + new Date() + "] " + message + "\n");
        } catch (Exception ignored) {}
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
