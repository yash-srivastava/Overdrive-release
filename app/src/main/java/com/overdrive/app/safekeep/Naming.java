package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic logical keys and remote paths (CS-3/CS-4). Keys derive from the capture
 * timestamp baked into file names, never from a path or volume.
 */
public final class Naming {

    public enum Kind {
        EVENT_CLIP, EVENT_SIDECAR, PROXIMITY_CLIP, REPLAY_CLIP, CLIP_SIDECAR,
        LOOP_CLIP, DVR_CLIP, TRIP_ROW, TRIP_TELEMETRY, TRIP_GPX, SETTINGS;

        public static Kind parse(String s) {
            try {
                return valueOf(s);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static final Pattern TS = Pattern.compile("(\\d{8})_(\\d{6})");

    private Naming() {}

    /** Media classification by file-name prefix; null for files SafeKeep ignores. */
    public static Kind classify(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        boolean mp4 = n.endsWith(".mp4");
        if (n.startsWith("event_")) return mp4 ? Kind.EVENT_CLIP : Kind.EVENT_SIDECAR;
        if (n.startsWith("proximity_")) return mp4 ? Kind.PROXIMITY_CLIP : Kind.CLIP_SIDECAR;
        if (n.startsWith("replay_")) return mp4 ? Kind.REPLAY_CLIP : Kind.CLIP_SIDECAR;
        if (n.startsWith("dvr_")) return mp4 ? Kind.DVR_CLIP : Kind.CLIP_SIDECAR;
        if (n.startsWith("cam") && n.length() > 4 && n.indexOf('_') > 0) {
            return mp4 ? Kind.LOOP_CLIP : Kind.CLIP_SIDECAR;
        }
        return null;
    }

    public static String fileKey(String fileName) {
        return "f/" + fileName;
    }

    public static String tripKey(long startTime) {
        return "trip/" + startTime;
    }

    public static String telemetryKey(long startTime) {
        return "tel/" + startTime;
    }

    public static String gpxKey(long startTime) {
        return "gpx/" + startTime;
    }

    /** One object per snapshot: pruning an old one can never delete a newer one. */
    public static String settingsKey(long now) {
        return "settings/" + fmt("yyyyMMdd-HHmmss", now);
    }

    /** Capture time from "…_yyyyMMdd_HHmmss…", falling back to mtime. */
    public static long captureTime(File f) {
        Matcher m = TS.matcher(f.getName());
        if (m.find()) {
            try {
                String timestamp = m.group(1) + m.group(2);
                // Validate in UTC so impossible fields are rejected without rejecting local DST gaps.
                SimpleDateFormat validator = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                validator.setTimeZone(TimeZone.getTimeZone("UTC"));
                validator.setLenient(false);
                validator.parse(timestamp);
                SimpleDateFormat p = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                Date d = p.parse(timestamp);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return RemovableStorageFs.lastModified(f);
    }

    /** Base name without extension; strips the sidecar suffixes too. */
    public static String stem(String name) {
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String root(SafeKeepConfig cfg, SafeKeepConfig.Destination d) {
        String prefix = d.params.optString("prefix", "").replaceAll("^/+|/+$", "");
        String base = d.humanLayout
                ? "OverDrive/" + cfg.vehicleName
                : "overdrive/v1/" + (cfg.vehicleId.isEmpty() ? "car" : cfg.vehicleId);
        return prefix.isEmpty() ? base : prefix + "/" + base;
    }

    /**
     * @param eventFolder human-layout folder label for an event group ("2026-09-24 14-03-12 Sentry - person - front")
     */
    public static String mediaPath(SafeKeepConfig cfg, SafeKeepConfig.Destination d, Kind kind,
                                   String fileName, long ts, String eventFolder, boolean encrypted) {
        String root = root(cfg, d);
        String p;
        if (d.humanLayout) {
            switch (kind) {
                case EVENT_CLIP:
                case EVENT_SIDECAR:
                case PROXIMITY_CLIP:
                case REPLAY_CLIP:
                    p = root + "/Events/" + fmt("yyyy-MM", ts) + "/"
                            + SafeKeepConfig.sanitizeName(eventFolder) + "/" + fileName;
                    break;
                case DVR_CLIP:
                    p = root + "/Dashcam/" + fmt("yyyy-MM-dd", ts) + "/" + fileName;
                    break;
                case CLIP_SIDECAR:
                    // Sidecars sit next to their clip: event folder, Dashcam, or Recordings.
                    p = root + "/" + (eventFolder != null ? "Events/" + fmt("yyyy-MM", ts) + "/"
                            + SafeKeepConfig.sanitizeName(eventFolder)
                            : (isDvr(fileName) ? "Dashcam/" : "Recordings/") + fmt("yyyy-MM-dd", ts))
                            + "/" + fileName;
                    break;
                default:
                    p = root + "/Recordings/" + fmt("yyyy-MM-dd", ts) + "/" + fileName;
                    break;
            }
        } else {
            String lane;
            switch (kind) {
                case LOOP_CLIP:
                    lane = "loop";
                    break;
                case DVR_CLIP:
                    lane = "dvr";
                    break;
                case CLIP_SIDECAR:
                    lane = eventFolder != null ? "events" : (isDvr(fileName) ? "dvr" : "loop");
                    break;
                default:
                    lane = "events";
                    break;
            }
            String group = "events".equals(lane) ? "/" + stem(fileName) : "";
            p = root + "/" + lane + "/" + fmt("yyyy/MM/dd", ts) + group + "/" + fileName;
        }
        return encrypted ? p + ".age" : p;
    }

    public static String tripRowPath(SafeKeepConfig cfg, SafeKeepConfig.Destination d, long start, boolean enc) {
        String p = root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/trips/" + start + ".json";
        return enc ? p + ".age" : p;
    }

    public static String telemetryPath(SafeKeepConfig cfg, SafeKeepConfig.Destination d, long start, boolean enc) {
        String p = root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/telemetry/" + start + ".jsonl.gz";
        return enc ? p + ".age" : p;
    }

    public static String gpxPath(SafeKeepConfig cfg, SafeKeepConfig.Destination d, long start,
                                 double distanceKm, boolean enc) {
        String p;
        if (d.humanLayout) {
            p = root(cfg, d) + "/Trips/" + fmt("yyyy-MM", start) + "/" + fmt("yyyy-MM-dd HH-mm", start)
                    + String.format(Locale.US, " %.1f km.gpx", Math.max(0, distanceKm));
        } else {
            p = root(cfg, d) + "/trips/gpx/" + fmt("yyyy/MM", start) + "/" + start + ".gpx";
        }
        return enc ? p + ".age" : p;
    }

    public static String settingsPath(SafeKeepConfig cfg, SafeKeepConfig.Destination d, long now, boolean enc) {
        String p = root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/settings/settings-"
                + fmt("yyyyMMdd-HHmmss", now) + ".json.gz";
        return enc ? p + ".age" : p;
    }

    private static boolean isDvr(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).startsWith("dvr_");
    }

    public static String tripsDir(SafeKeepConfig cfg, SafeKeepConfig.Destination d) {
        return root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/trips";
    }

    public static String telemetryDir(SafeKeepConfig cfg, SafeKeepConfig.Destination d) {
        return root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/telemetry";
    }

    public static String settingsDir(SafeKeepConfig cfg, SafeKeepConfig.Destination d) {
        return root(cfg, d) + (d.humanLayout ? "/.overdrive" : "") + "/settings";
    }

    public static String fmt(String pattern, long ts) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        return f.format(new Date(ts));
    }

    public static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".age")) return "application/octet-stream";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".srt")) return "application/x-subrip";
        if (n.endsWith(".gpx")) return "application/gpx+xml";
        if (n.endsWith(".gz")) return "application/gzip";
        return "application/octet-stream";
    }
}
