package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Timeline facts from a clip's JSON sidecar; times in events[] are ms since the clip's t=0. */
public final class SidecarTimeline {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_MARKS_PER_CLIP = 2000;

    private SidecarTimeline() {}

    public static final class Facts {
        public boolean present;
        public boolean parsed;
        /** Wall time of t=0 from the actors' first sightings; -1 when unknown. */
        public long anchorMs = -1L;
        public long preRecordMs = -1L;
        public long durationMs = -1L;
        public String layout;
        public boolean lens;
        /** Sidecar carries the 2x2 grid geometry block (LensTag.GRID). */
        public boolean grid;
        public String peakSeverity;
        public long peakSeverityMs = -1L;
        final List<Span> spans = new ArrayList<>();
        final List<Long> deterrents = new ArrayList<>();
    }

    static final class Span {
        final long start;
        final long end;
        final String type;
        final Double conf;
        final String cam;
        final boolean isStatic;

        Span(long start, long end, String type, Double conf, String cam, boolean isStatic) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.conf = conf;
            this.cam = cam;
            this.isStatic = isStatic;
        }
    }

    public static final class Mark {
        public final long t0;
        public final long t1;
        public final String kind;
        public final Double conf;
        public final String cam;
        public final boolean alert;

        Mark(long t0, long t1, String kind, Double conf, String cam, boolean alert) {
            this.t0 = t0;
            this.t1 = t1;
            this.kind = kind;
            this.conf = conf;
            this.cam = cam;
            this.alert = alert;
        }
    }

    static File sidecarFor(File mp4) {
        String name = mp4.getName();
        String stem = name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
        return new File(mp4.getParentFile(), stem + ".json");
    }

    /** Never throws; a missing, oversized or malformed sidecar yields facts with parsed=false. */
    public static Facts read(File mp4) {
        Facts facts = new Facts();
        if (mp4 == null || mp4.getParentFile() == null) return facts;
        try {
            File json = sidecarFor(mp4);
            if (!RemovableStorageFs.isFile(json)) return facts;
            facts.present = true;
            long length = RemovableStorageFs.length(json);
            if (length <= 0 || length > MAX_BYTES) return facts;
            byte[] bytes = new byte[(int) length];
            int filled = 0;
            try (InputStream in = RemovableStorageFs.openInput(json)) {
                int n;
                while (filled < bytes.length && (n = in.read(bytes, filled, bytes.length - filled)) > 0) {
                    filled += n;
                }
            }
            return parseInto(facts, new String(bytes, 0, filled, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return facts;
        }
    }

    static Facts parse(String json) {
        Facts facts = new Facts();
        facts.present = json != null;
        return parseInto(facts, json);
    }

    private static Facts parseInto(Facts facts, String text) {
        if (text == null) return facts;
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Throwable t) {
            return facts;
        }
        facts.parsed = true;
        if (root.has("preRecordMs")) facts.preRecordMs = root.optLong("preRecordMs", -1L);
        if (root.has("durationMs")) facts.durationMs = root.optLong("durationMs", -1L);
        String layout = root.optString("layout", "");
        facts.layout = layout.isEmpty() ? null : layout;
        facts.lens = root.optJSONObject("lens") != null;
        facts.grid = root.optJSONObject("grid") != null;

        JSONObject stats = root.optJSONObject("stats");
        if (stats != null) {
            String peak = stats.optString("peakSeverity", "");
            facts.peakSeverity = peak.isEmpty() ? null : peak.toUpperCase(Locale.US);
            if (stats.has("peakSeverityMs")) {
                facts.peakSeverityMs = stats.optLong("peakSeverityMs", -1L);
            }
        }

        JSONArray actors = root.optJSONArray("actors");
        if (actors != null) {
            List<Long> origins = new ArrayList<>();
            for (int i = 0; i < actors.length(); i++) {
                JSONObject a = actors.optJSONObject(i);
                if (a == null || !a.has("firstSeenMs") || !a.has("firstSeenWallMs")) continue;
                long rel = a.optLong("firstSeenMs", -1L);
                long wall = a.optLong("firstSeenWallMs", -1L);
                if (rel >= 0 && wall > 0 && wall > rel) origins.add(wall - rel);
            }
            facts.anchorMs = median(origins);
        }

        JSONArray events = root.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null || !e.has("start")) continue;
                long start = e.optLong("start", -1L);
                long end = e.optLong("end", start);
                if (start < 0) continue;
                Double conf = e.has("maxConf") ? Double.valueOf(e.optDouble("maxConf")) : null;
                if (conf != null && (conf.isNaN() || conf.isInfinite())) conf = null;
                String cam = null;
                JSONArray cams = e.optJSONArray("cameras");
                if (cams != null && cams.length() > 0) {
                    String c = cams.optString(0, "");
                    cam = c.isEmpty() ? null : c.toLowerCase(Locale.US);
                }
                boolean isStatic = e.optBoolean("isStatic", false)
                        || e.optBoolean("isStaticForTimeline", false);
                facts.spans.add(new Span(start, Math.max(start, end),
                        e.optString("type", ""), conf, cam, isStatic));
            }
        }

        JSONArray deterrents = root.optJSONArray("deterrents");
        if (deterrents != null) {
            for (int i = 0; i < deterrents.length(); i++) {
                JSONObject d = deterrents.optJSONObject(i);
                if (d == null) continue;
                facts.deterrents.add(d.has("tMs") ? d.optLong("tMs", -1L) : -1L);
            }
        }
        return facts;
    }

    static long median(List<Long> values) {
        if (values == null || values.isEmpty()) return -1L;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(mid);
        long a = sorted.get(mid - 1);
        long b = sorted.get(mid);
        return a + (b - a) / 2;
    }

    /** Timeline kind for a sidecar span type; null drops the span. */
    static String kindFor(String spanType) {
        if (spanType == null) return null;
        switch (spanType.toLowerCase(Locale.US)) {
            case "car":
            case "vehicle":
                return "vehicle";
            case "person":
                return "person";
            case "bike":
                return "bike";
            case "animal":
                return "animal";
            case "motion":
                return "motion";
            default:
                return null;
        }
    }

    /** Absolute marks for a clip starting at {@code clipStart}; static spans are dropped. */
    public static List<Mark> marks(Facts facts, long clipStart) {
        List<Mark> out = new ArrayList<>();
        if (facts == null || !facts.parsed) return out;
        boolean alertClip = "ALERT".equals(facts.peakSeverity)
                || "CRITICAL".equals(facts.peakSeverity);
        long peak = facts.peakSeverityMs;
        for (Span s : facts.spans) {
            if (out.size() >= MAX_MARKS_PER_CLIP) break;
            if (s.isStatic) continue;
            String kind = kindFor(s.type);
            if (kind == null) continue;
            boolean alert = alertClip && peak >= 0 && s.start <= peak && peak <= s.end;
            out.add(new Mark(clipStart + s.start, clipStart + s.end, kind, s.conf, s.cam, alert));
        }
        for (Long t : facts.deterrents) {
            if (out.size() >= MAX_MARKS_PER_CLIP) break;
            long at = clipStart + (t != null && t >= 0 ? t : 0L);
            out.add(new Mark(at, at, "deterrent", null, null, false));
        }
        return out;
    }

    /** standard | dashcam | single; a lens- or grid-tagged clip is always standard. */
    public static String layoutFor(String type, String fileName, Facts facts) {
        if ("oemDashcam".equals(type) || (fileName != null && fileName.startsWith("dvr_"))) {
            return "single";
        }
        if (facts != null && (facts.lens || facts.grid)) return "standard";
        if (facts != null && "dashcam".equals(facts.layout)) return "dashcam";
        return "standard";
    }
}
