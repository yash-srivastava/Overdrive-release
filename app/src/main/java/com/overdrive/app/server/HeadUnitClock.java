package com.overdrive.app.server;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The head unit's 12/24-hour clock: its switch ({@code time_12_24}), or when that was never set,
 * its language's default, as Android's own clock does. Read off-thread at most once a minute.
 */
public final class HeadUnitClock {

    private static final long REFRESH_MS = 60_000L;
    private static final long READ_TIMEOUT_MS = 3_000L;
    private static final AtomicBoolean READING = new AtomicBoolean(false);

    /** "24", "12", "" for no preference (12-hour language default), null until a read succeeds. */
    private static volatile String setting;
    private static volatile long readAtMs;
    private static volatile boolean everRead;

    private HeadUnitClock() {}

    /** "24", "12", "" (no preference) or null (not known yet). */
    public static String setting() {
        long now = nowMs();
        if ((!everRead || now - readAtMs >= REFRESH_MS) && READING.compareAndSet(false, true)) {
            try {
                Thread reader = new Thread(HeadUnitClock::refresh, "HeadUnitClock");
                reader.setDaemon(true);
                reader.start();
            } catch (Throwable t) {
                READING.set(false);
            }
        }
        return setting;
    }

    /** True only when the head unit is set to 24-hour. */
    public static boolean is24h() {
        try {
            return "24".equals(setting());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void refresh() {
        try {
            String parsed = parse(read());
            if (parsed != null) setting = parsed;
        } catch (Throwable ignored) {
            // Keep the last good value.
        } finally {
            readAtMs = nowMs();
            everRead = true;
            READING.set(false);
        }
    }

    /** Monotonic, and plain Java so JVM tests of callers don't hit Android stubs. */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static String read() throws Exception {
        Process p = new ProcessBuilder("sh", "-c",
                "settings get system time_12_24; getprop persist.sys.locale")
                .redirectErrorStream(true).start();
        try {
            if (!p.waitFor(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
            InputStream in = p.getInputStream();
            byte[] buf = new byte[128];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } finally {
            p.destroy();
        }
    }

    /** Switch line then locale line to "24" / "12" / ""; null for an error, so the old value stays. */
    static String parse(String out) {
        if (out == null) return null;
        String[] lines = out.split("\n", -1);
        String value = lines[0].trim();
        if (value.equals("24") || value.equals("12")) return value;
        if (!value.isEmpty() && !value.equals("null")) return null;
        return lines.length > 1 && localeUses24h(lines[1]) ? "24" : "";
    }

    /** Same test as Android's DateFormat.is24HourLocale: an unquoted 'H' in the long time pattern. */
    static boolean localeUses24h(String tag) {
        if (tag == null || tag.trim().isEmpty()) return false;
        Locale locale = Locale.forLanguageTag(tag.trim());
        if (locale.getLanguage().isEmpty() || !hasTimeData(locale)) return false;
        DateFormat format = DateFormat.getTimeInstance(DateFormat.LONG, locale);
        if (!(format instanceof SimpleDateFormat)) return false;
        String pattern = ((SimpleDateFormat) format).toPattern();
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && c == 'H') return true;
        }
        return false;
    }

    private static boolean hasTimeData(Locale locale) {
        for (Locale known : DateFormat.getAvailableLocales()) {
            if (known.getLanguage().equals(locale.getLanguage())) return true;
        }
        return false;
    }
}
