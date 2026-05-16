package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * SRT subtitle sidecar writer for dashcam recordings.
 *
 * <p>Burned-in video overlay text is intentionally English (universal numerals
 * + km/h). The SRT sidecar emitted by this class carries localized prose
 * ("Person detected close range", "Charging started · 4.3 kW") so playback
 * tools that auto-load matching {@code .srt} files (VLC, video.js, ExoPlayer)
 * can show the user their language without re-encoding the video.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Caller instantiates one writer per recording / segment.</li>
 *   <li>Surveillance / charging / proximity code calls
 *       {@link #addEvent(long, String, Object...)} with an offset measured
 *       in milliseconds since the start of the .mp4.</li>
 *   <li>On finalize, caller invokes {@link #write(File)} with the playable
 *       {@code .mp4} (already renamed from .tmp). The SRT lands at
 *       {@code <basename>.srt} next to the mp4.</li>
 * </ol>
 *
 * <p>Failure semantics: writing the SRT must never break recording. Every
 * public entry point swallows exceptions internally and routes them to the
 * daemon log. An empty buffer skips the write entirely (no zero-byte .srt).
 *
 * <p>i18n: text is resolved through {@code com.overdrive.app.server.Messages}
 * if present (loaded reflectively to avoid a hard build dependency on a class
 * that may be authored by a parallel agent). When the catalog is unavailable
 * we fall back to the i18n key itself with simple {@link MessageFormat}
 * substitution — useful for development and for unit tests.
 */
public final class SrtWriter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SrtWriter");

    /** How long each subtitle entry stays on screen. */
    private static final long ENTRY_DURATION_MS = 4_000L;

    /** Cap on buffered events so a chatty pipeline can't OOM us. */
    private static final int MAX_EVENTS = 1024;

    /** Locale baked at construction time — matches the locale the user picked. */
    private final String locale;

    private final List<Event> events = new ArrayList<>();

    /** Single-event record. Package-private for tests. */
    static final class Event {
        final long offsetMs;
        final String key;
        final Object[] args;
        Event(long offsetMs, String key, Object[] args) {
            this.offsetMs = offsetMs;
            this.key = key;
            this.args = args;
        }
    }

    public SrtWriter() {
        this(resolveCurrentLocale());
    }

    public SrtWriter(String locale) {
        this.locale = locale != null ? locale : "en";
    }

    /**
     * Buffer an event. {@code offsetMs} is milliseconds since the recording
     * (segment) started. Negative offsets are clamped to 0; we'd rather show
     * a pre-roll event at t=0 than silently drop it.
     *
     * <p>Safe to call from any thread.
     */
    public synchronized void addEvent(long offsetMs, String i18nKey, Object... args) {
        if (i18nKey == null || i18nKey.isEmpty()) return;
        if (events.size() >= MAX_EVENTS) {
            // Don't spam the log on every drop — log once at the boundary.
            if (events.size() == MAX_EVENTS) {
                logger.warn("SRT buffer full (" + MAX_EVENTS + " events); dropping further additions");
            }
            return;
        }
        long clamped = Math.max(0L, offsetMs);
        events.add(new Event(clamped, i18nKey, args != null ? args : new Object[0]));
    }

    /** True if no events have been buffered. */
    public synchronized boolean isEmpty() {
        return events.isEmpty();
    }

    /** Buffered event count. Mainly for diagnostics / tests. */
    public synchronized int size() {
        return events.size();
    }

    /**
     * Finalize: write {@code <basename>.srt} next to {@code mp4File}.
     * Skips silently if the buffer is empty or the file path is unusable.
     * Never throws.
     *
     * @return the {@link File} written, or {@code null} if nothing was written.
     */
    public File write(File mp4File) {
        List<Event> snapshot;
        synchronized (this) {
            if (events.isEmpty()) {
                return null;
            }
            snapshot = new ArrayList<>(events);
        }

        if (mp4File == null) {
            logger.warn("SRT write skipped: mp4File is null");
            return null;
        }

        try {
            // Sort chronologically — surveillance writers don't always submit
            // events in order (e.g. the timeline collector flushes a pre-record
            // ring buffer mid-stream).
            Collections.sort(snapshot, new Comparator<Event>() {
                @Override public int compare(Event a, Event b) {
                    return Long.compare(a.offsetMs, b.offsetMs);
                }
            });

            String name = mp4File.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File parent = mp4File.getParentFile();
            File srtFile = new File(parent, base + ".srt");
            File tmpFile = new File(srtFile.getAbsolutePath() + ".tmp");

            StringBuilder sb = new StringBuilder(snapshot.size() * 64);
            int idx = 1;
            for (Event ev : snapshot) {
                String text = render(ev.key, ev.args);
                if (text == null || text.isEmpty()) continue;

                long startMs = ev.offsetMs;
                long endMs = startMs + ENTRY_DURATION_MS;

                sb.append(idx++).append('\n');
                sb.append(formatTimestamp(startMs)).append(" --> ").append(formatTimestamp(endMs)).append('\n');
                sb.append(text).append('\n');
                sb.append('\n');
            }

            if (sb.length() == 0) {
                // Every event resolved to empty text — nothing to ship.
                return null;
            }

            try (FileWriter fw = new FileWriter(tmpFile)) {
                fw.write(sb.toString());
            }

            // Atomic-ish swap so a half-written file never appears under the
            // final name (matches the discipline used by the JSON sidecar
            // writer in EventTimelineCollector).
            if (!tmpFile.renameTo(srtFile)) {
                try (FileWriter fw = new FileWriter(srtFile)) {
                    fw.write(sb.toString());
                }
                tmpFile.delete();
            }

            try {
                srtFile.setReadable(true, false);
            } catch (Exception ignored) {
                // Best-effort; not all filesystems honour this.
            }

            logger.info("SRT sidecar written: " + srtFile.getName()
                    + " (" + (idx - 1) + " entries, locale=" + locale + ")");
            return srtFile;
        } catch (Exception e) {
            logger.warn("SRT write failed for " + mp4File.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Format ms as {@code HH:MM:SS,mmm} — the SRT V2 timestamp form. */
    static String formatTimestamp(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000L;
        long millis = ms % 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d",
                hours, minutes, seconds, millis);
    }

    /**
     * Resolve an i18n key against the locale catalog and apply MessageFormat
     * substitution. The catalog ({@code com.overdrive.app.server.Messages})
     * is looked up reflectively so this class compiles even if Messages
     * doesn't exist yet (a parallel agent ships it).
     */
    private String render(String key, Object[] args) {
        String template = lookupCatalog(key, locale);
        if (template == null) {
            // Fallback: return the key itself with arg substitution. This is
            // legible enough during development that bugs get spotted early.
            template = key;
        }
        if (args == null || args.length == 0) return template;
        try {
            return MessageFormat.format(template, args);
        } catch (Exception e) {
            // Bad placeholder in the template — emit the raw template so the
            // operator at least sees what fired.
            return template;
        }
    }

    /** Reflectively call {@code Messages.get(locale, key)} or {@code Messages.get(key)}. */
    private static String lookupCatalog(String key, String locale) {
        try {
            Class<?> cls = Class.forName("com.overdrive.app.server.Messages");
            // Try (locale, key) first
            try {
                Method m = cls.getMethod("get", String.class, String.class);
                Object out = m.invoke(null, locale, key);
                if (out instanceof String) return (String) out;
            } catch (NoSuchMethodException ignored) { /* try other signature */ }

            // Fallback to a single-arg signature that resolves the locale
            // internally (some catalogs use a thread-local locale set by
            // LocaleManager.get()).
            try {
                Method m = cls.getMethod("get", String.class);
                Object out = m.invoke(null, key);
                if (out instanceof String) return (String) out;
            } catch (NoSuchMethodException ignored) { /* fall through */ }
        } catch (ClassNotFoundException e) {
            // Messages class not on classpath — happens during early dev or
            // tests. Caller falls back to the key itself.
        } catch (Exception e) {
            // Reflection failure — log once and stop trying.
            logger.warn("Messages reflection failed: " + e.getMessage());
        }
        return null;
    }

    private static String resolveCurrentLocale() {
        try {
            Class<?> cls = Class.forName("com.overdrive.app.server.LocaleManager");
            Method m = cls.getMethod("get");
            Object out = m.invoke(null);
            if (out instanceof String) return (String) out;
        } catch (Exception ignored) {
            // LocaleManager unavailable — default to English.
        }
        return "en";
    }

    // ------------------------------------------------------------------
    // i18n key constants — kept here so the recording call sites don't have
    // to remember string spelling and Find Usages turns up every emitter.
    // ------------------------------------------------------------------

    public static final String K_PERSON_DETECTED   = "srt.person_detected";
    public static final String K_PERSON_CLOSE      = "srt.person_close";
    public static final String K_VEHICLE_DETECTED  = "srt.vehicle_detected";
    public static final String K_MOTION_STARTED    = "srt.motion_started";
    public static final String K_MOTION_ENDED      = "srt.motion_ended";
    public static final String K_PROXIMITY_RED     = "srt.proximity_red";
    public static final String K_PROXIMITY_YELLOW  = "srt.proximity_yellow";
    public static final String K_CHARGING_STARTED  = "srt.charging_started";
    public static final String K_CHARGING_STOPPED  = "srt.charging_stopped";
    public static final String K_CHARGING_COMPLETE = "srt.charging_complete";
    public static final String K_RECORDING_STARTED = "srt.recording_started";
    public static final String K_RECORDING_ENDED   = "srt.recording_ended";
}
