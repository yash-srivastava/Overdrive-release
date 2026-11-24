package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turns producer events into ledger rows (one per routed destination). Runs on the control
 * thread only; producers never wait on it.
 */
final class Enqueuer {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepQueue");
    private static final long SEAL_TIMEOUT_MS = 60_000;
    private static final long SETTINGS_DEBOUNCE_MS = 15 * 60_000L;
    private static final long SETTINGS_MIN_GAP_MS = 6 * 3600_000L;
    private static final String[] CLIP_SIDECARS = {".jpg", ".json", ".srt"};
    /** Sidecars written after the clip (location JSON, hero image) get one late look. */
    private static final long LATE_SIDECAR_MS = 90_000;

    private final Engine e;
    private final Map<String, ScheduledFuture<?>> sealTimers = new HashMap<>();
    private final java.util.Set<String> lateChecks = new java.util.HashSet<>();
    private ScheduledFuture<?> settingsTimer;
    private long lastSettingsEnqueueAt;

    Enqueuer(Engine e) {
        this.e = e;
    }

    // ── media ──────────────────────────────────────────────────────────

    void fileSaved(File f) {
        Naming.Kind kind = Naming.classify(f.getName());
        if (kind == null) return;
        if (kind == Naming.Kind.EVENT_CLIP) {
            String stem = Naming.stem(f.getName());
            if (sealTimers.containsKey(stem) || !e.ledger.byLogicalKey(Naming.fileKey(f.getName())).isEmpty()) return;
            // Protect it from rotation right away; the rows follow once its sidecars are sealed.
            for (SafeKeepConfig.Route r : e.cfg.routesFor(SafeKeepConfig.Lane.EVIDENCE)) {
                if (r.includeSentry) {
                    e.retention.pin(f.getAbsolutePath(), RemovableStorageFs.length(f), System.currentTimeMillis());
                    break;
                }
            }
            try {
                sealTimers.put(stem, e.ctl.schedule(() -> e.runCtl(() -> eventSealed(f)),
                        SEAL_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception ignored) {
            }
            return;
        }
        enqueueMedia(f, kind, false, false, false);
    }

    void eventSealed(File mp4) {
        ScheduledFuture<?> t = sealTimers.remove(Naming.stem(mp4.getName()));
        if (t != null) t.cancel(false);
        enqueueMedia(mp4, Naming.Kind.EVENT_CLIP, false, false, false);
    }

    int enqueueMedia(File anchor, Naming.Kind kind, boolean manual, boolean mobileOk, boolean locked) {
        return enqueueMedia(anchor, kind, manual, mobileOk, locked, null);
    }

    /**
     * @param manual   user "Upload now": skips soft rules, uses evidence destinations if the lane is unrouted
     * @param mobileOk the user confirmed mobile data for this one clip
     * @param locked   user Lock: normal rules, top priority, evidence destinations if unrouted
     * @param onlyDests reconcile: only these destinations (null = every routed one)
     * @return rows created or re-prioritised
     */
    int enqueueMedia(File anchor, Naming.Kind kind, boolean manual, boolean mobileOk, boolean locked,
                     java.util.Set<String> onlyDests) {
        if (anchor == null || !RemovableStorageFs.isFile(anchor) || RemovableStorageFs.length(anchor) == 0) return 0;
        SafeKeepConfig c = e.cfg;
        SafeKeepConfig.Lane lane;
        String label;
        boolean evidence;
        switch (kind) {
            case EVENT_CLIP:
                lane = SafeKeepConfig.Lane.EVIDENCE;
                label = "Sentry";
                evidence = true;
                break;
            case PROXIMITY_CLIP:
                lane = SafeKeepConfig.Lane.EVIDENCE;
                label = "Proximity";
                evidence = true;
                break;
            case REPLAY_CLIP:
                lane = SafeKeepConfig.Lane.EVIDENCE;
                label = "Saved clip";
                evidence = true;
                break;
            case LOOP_CLIP:
            case DVR_CLIP:
                lane = SafeKeepConfig.Lane.ARCHIVE;
                label = null;
                evidence = false;
                break;
            default:
                return 0;
        }
        List<SafeKeepConfig.Route> routes = new ArrayList<>();
        for (SafeKeepConfig.Route r : c.routesFor(lane)) {
            boolean include = kind == Naming.Kind.EVENT_CLIP ? r.includeSentry
                    : kind == Naming.Kind.PROXIMITY_CLIP ? r.includeProximity
                    : kind == Naming.Kind.REPLAY_CLIP ? r.includeReplay
                    : kind != Naming.Kind.DVR_CLIP || r.includeOemDashcam;
            if (include || manual || locked) routes.add(r);
        }
        // No media route at all: any routed destination takes it as a user request on the evidence
        // lane (never a trips/settings lane, whose snapshot and retention logic would drop it).
        boolean anyRoute = false;
        if (routes.isEmpty() && (manual || locked)) {
            routes.addAll(c.routesFor(SafeKeepConfig.Lane.EVIDENCE));
            if (routes.isEmpty()) {
                routes.addAll(c.routes);
                anyRoute = true;
            }
        }
        if (routes.isEmpty()) return 0;

        long ts = Naming.captureTime(anchor);
        String stem = Naming.stem(anchor.getName());
        File dir = anchor.getParentFile();
        List<File> sidecars = new ArrayList<>();
        boolean sidecarMissing = false;
        for (String ext : CLIP_SIDECARS) {
            File s = new File(dir, stem + ext);
            if (RemovableStorageFs.isFile(s) && RemovableStorageFs.length(s) > 0) sidecars.add(s);
            else sidecarMissing = true;
        }
        JSONObject info = evidence ? eventInfo(new File(dir, stem + ".json")) : new JSONObject();
        // An event keeps the folder chosen when it was first queued (late sidecars land beside it).
        String folder = frozenFolder(anchor.getName());
        if (folder == null && label != null) {
            StringBuilder sb = new StringBuilder(Naming.fmt("yyyy-MM-dd HH-mm-ss", ts)).append(' ').append(label);
            String cls = info.optString("class", "");
            String cam = info.optString("camera", "");
            if (!cls.isEmpty()) sb.append(" - ").append(cls);
            if (!cam.isEmpty()) sb.append(" - ").append(cam);
            folder = sb.toString();
        }
        int severity = info.optInt("severity", 0);
        boolean isLocked = locked || e.retention.isLocked(anchor);
        // Manual and locked clips get evidence priority whatever their lane.
        boolean priority = evidence || isLocked || manual;
        int created = 0;
        long now = System.currentTimeMillis();
        java.util.Set<String> destsDone = new java.util.HashSet<>();
        for (SafeKeepConfig.Route r : routes) {
            for (String destId : r.destIds) {
                SafeKeepConfig.Destination d = c.destination(destId);
                if (d == null || !d.enabled || !destsDone.add(destId)) continue;
                if (onlyDests != null && !onlyDests.contains(destId)) continue;
                boolean enc = d.encrypt && r.encrypt != 0;
                List<File> files = new ArrayList<>(sidecars);
                files.add(anchor);
                for (File f : files) {
                    boolean isAnchor = f == anchor;
                    Naming.Kind k = isAnchor ? kind : (kind == Naming.Kind.EVENT_CLIP
                            ? Naming.Kind.EVENT_SIDECAR : Naming.Kind.CLIP_SIDECAR);
                    Ledger.Item it = new Ledger.Item();
                    it.destId = destId;
                    // The route's own lane: a manual or locked clip may ride the evidence route.
                    it.lane = anyRoute ? SafeKeepConfig.Lane.EVIDENCE.id : r.lane.id;
                    it.kind = k.name();
                    it.logicalKey = Naming.fileKey(f.getName());
                    it.generation = 0;
                    it.groupKey = stem;
                    it.localPath = f.getAbsolutePath();
                    it.sizeBytes = RemovableStorageFs.length(f);
                    it.mtime = RemovableStorageFs.lastModified(f);
                    it.remotePath = Naming.mediaPath(c, d, k, f.getName(), ts, folder, enc);
                    it.pclass = priority ? (isAnchor ? 1 : 0) : 2;
                    it.pscore = score(priority, ts, severity, isLocked);
                    it.encrypted = enc;
                    it.manual = manual || anyRoute;
                    it.createdAt = now;
                    try {
                        JSONObject meta = new JSONObject().put("ts", ts);
                        if (folder != null) meta.put("folder", folder);
                        if (mobileOk) meta.put("mobileOk", true);
                        it.metaJson = meta.toString();
                    } catch (Exception ignored) {
                    }
                    long id = e.ledger.insertIfAbsent(it);
                    int changed;
                    if (id > 0) {
                        changed = 1;
                    } else if (manual || locked) {
                        changed = e.ledger.promote(destId, it.logicalKey, it.lane, it.pclass, it.manual, mobileOk, it.pscore);
                    } else {
                        changed = e.ledger.reviveSkipped(destId, it.logicalKey, it.lane, it.pclass, it.sizeBytes, it.mtime);
                    }
                    created += changed;
                    // Queued again: keep it on the card until it uploads (a promoted row may have been unpinned).
                    if (changed > 0) e.retention.pin(it.localPath, it.sizeBytes, now);
                }
            }
        }
        if (sidecarMissing && created > 0 && onlyDests == null) {
            scheduleLateSidecars(anchor, kind, manual, mobileOk, locked);
        }
        if (created > 0) e.wake();
        return created;
    }

    /** The event folder already used for this clip on any destination, or null. */
    private String frozenFolder(String anchorName) {
        for (Ledger.Item row : e.ledger.byLogicalKey(Naming.fileKey(anchorName))) {
            String f = row.meta().optString("folder", "");
            if (!f.isEmpty()) return f;
        }
        return null;
    }

    /** One late look per clip, with the same request flags (a manual clip's lane may be unrouted). */
    private void scheduleLateSidecars(File anchor, Naming.Kind kind, boolean manual, boolean mobileOk, boolean locked) {
        String stem = Naming.stem(anchor.getName());
        if (!lateChecks.add(stem)) return;
        try {
            e.ctl.schedule(() -> e.runCtl(() -> {
                lateChecks.remove(stem);
                enqueueMedia(anchor, kind, manual, mobileOk, locked, null);
            }), LATE_SIDECAR_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            lateChecks.remove(stem);
        }
    }

    private static long score(boolean evidence, long ts, int severity, boolean locked) {
        long sec = ts / 1000L;
        if (!evidence) return -sec;
        return (locked ? 3_000_000_000_000L : 0) + severity * 100_000_000_000L + sec;
    }

    /** Top class / camera / severity from the event timeline sidecar (tolerates absence). */
    static JSONObject eventInfo(File json) {
        JSONObject out = new JSONObject();
        if (json == null || !RemovableStorageFs.isFile(json) || RemovableStorageFs.length(json) > 4 * 1024 * 1024) return out;
        try {
            JSONObject root = new JSONObject(new String(RemovableStorageFs.readAllBytes(json),
                    StandardCharsets.UTF_8));
            JSONObject stats = root.optJSONObject("stats");
            String sev = stats == null ? "" : stats.optString("peakSeverity", "");
            out.put("severity", severityRank(sev));
            JSONArray actors = root.optJSONArray("actors");
            String bestClass = "";
            String bestCam = "";
            int bestRank = -1;
            if (actors != null) {
                for (int i = 0; i < actors.length(); i++) {
                    JSONObject a = actors.optJSONObject(i);
                    if (a == null) continue;
                    int rank = severityRank(a.optString("peakSeverity", ""));
                    if (rank > bestRank) {
                        bestRank = rank;
                        bestClass = a.optString("class", "");
                        JSONArray cams = a.optJSONArray("cameras");
                        bestCam = cams != null && cams.length() > 0 ? cams.optString(0, "") : "";
                    }
                }
            }
            if (bestCam.isEmpty()) {
                JSONArray events = root.optJSONArray("events");
                if (events != null && events.length() > 0) {
                    JSONObject ev = events.optJSONObject(0);
                    JSONArray cams = ev == null ? null : ev.optJSONArray("cameras");
                    if (cams != null && cams.length() > 0) bestCam = cams.optString(0, "");
                }
            }
            out.put("class", bestClass.toLowerCase(java.util.Locale.ROOT));
            out.put("camera", bestCam.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
        }
        return out;
    }

    static int severityRank(String s) {
        if (s == null) return 0;
        switch (s.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL":
                return 3;
            case "ALERT":
            case "HIGH":
                return 2;
            case "NOTICE":
            case "MEDIUM":
                return 1;
            default:
                return 0;
        }
    }

    // ── manual actions ────────────────────────────────────────────────────

    /** Null on success, else an error code for the API. */
    String uploadNow(String fileName, boolean mobileOk) {
        SafeKeepConfig c = e.cfg;
        if (c.destinations.isEmpty()) return "no_destination";
        File f = resolveRecording(fileName);
        if (f == null) return "not_found";
        Naming.Kind k = Naming.classify(f.getName());
        if (k == null || k == Naming.Kind.EVENT_SIDECAR || k == Naming.Kind.CLIP_SIDECAR) return "unsupported";
        final File file = f;
        e.runCtl(() -> enqueueMedia(file, k, true, mobileOk, false));
        return null;
    }

    String lock(String fileName, boolean lock) {
        File f = resolveRecording(fileName);
        if (f == null) return "not_found";
        String path = f.getAbsolutePath();
        if (!lock) return e.retention.removeLock(path) ? null : "save_failed";
        long size = RemovableStorageFs.length(f);
        if (!e.retention.addLock(path, size, categoryLimitBytes(f.getName()))) return "lock_quota";
        Naming.Kind k = Naming.classify(f.getName());
        if (k != null && k != Naming.Kind.EVENT_SIDECAR && k != Naming.Kind.CLIP_SIDECAR) {
            e.runCtl(() -> enqueueMedia(f, k, false, false, true));
        }
        return null;
    }

    /** The rotation limit of the clip's category (0 when unknown). */
    static long categoryLimitBytes(String name) {
        try {
            StorageManager sm = StorageManager.getInstance();
            long mb;
            switch (Retention.categoryOf(name)) {
                case "surveillance":
                    mb = sm.getSurveillanceLimitMb();
                    break;
                default:
                    mb = sm.getRecordingsLimitMb();
                    break;
            }
            return Math.max(0, mb) * 1024L * 1024L;
        } catch (Throwable t) {
            return 0;
        }
    }

    void uploadLatestEvent() {
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            RecordingsIndex.Filter flt = new RecordingsIndex.Filter();
            flt.type = "sentry";
            List<JSONObject> rows = idx.queryRecordings(flt, 1, 0);
            if (rows.isEmpty()) return;
            File f = new File(rows.get(0).optString("path", ""));
            if (RemovableStorageFs.isFile(f)) enqueueMedia(f, Naming.Kind.EVENT_CLIP, true, false, false);
        } catch (Throwable t) {
            logger.warn("uploadLatestEvent failed: " + t.getMessage());
        }
    }

    static File resolveRecording(String fileName) {
        if (fileName == null || fileName.isEmpty() || fileName.contains("/") || fileName.contains("..")) return null;
        try {
            RecordingsIndex.RecordingRef ref = RecordingsIndex.getInstance().resolveByFilename(fileName);
            if (ref != null && ref.absolutePath != null) {
                File f = new File(ref.absolutePath);
                if (RemovableStorageFs.isFile(f)) return f;
            }
        } catch (Throwable ignored) {
        }
        try {
            StorageManager sm = StorageManager.getInstance();
            for (File dir : new File[]{sm.getSurveillanceDir(), sm.getRecordingsDir(), sm.getProximityDir()}) {
                if (dir == null) continue;
                File f = new File(dir, fileName);
                if (RemovableStorageFs.isFile(f)) return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── trips ─────────────────────────────────────────────────────────────

    void tripSaved(long tripId) {
        TripRecord t = Staging.trip(tripId);
        if (t == null || t.endTime <= 0) return;
        enqueueTrip(t, false);
        try {
            JSONObject data = new JSONObject().put("startTime", t.startTime).put("endTime", t.endTime)
                    .put("distanceKm", t.distanceKm).put("durationSeconds", t.durationSeconds)
                    .put("socStart", t.socStart).put("socEnd", t.socEnd).put("cost", t.tripCost)
                    .put("currency", t.currency == null ? "" : t.currency);
            e.notifier.webhook("trip.finished", data);
        } catch (Exception ignored) {
        }
    }

    int enqueueTrip(TripRecord t, boolean onlyIfChanged) {
        return enqueueTrip(t, onlyIfChanged, null);
    }

    /**
     * @param onlyIfChanged re-upload the row only when its content differs from the last upload
     * @param onlyDests     reconcile: only these destinations (null = every routed one)
     */
    int enqueueTrip(TripRecord t, boolean onlyIfChanged, java.util.Set<String> onlyDests) {
        SafeKeepConfig c = e.cfg;
        int created = 0;
        long now = System.currentTimeMillis();
        String rowHash = null;
        for (SafeKeepConfig.Route r : c.routesFor(SafeKeepConfig.Lane.TRIPS)) {
            for (String destId : r.destIds) {
                SafeKeepConfig.Destination d = c.destination(destId);
                if (d == null || !d.enabled) continue;
                if (onlyDests != null && !onlyDests.contains(destId)) continue;
                boolean enc = d.encrypt && r.encrypt != 0;
                String key = Naming.tripKey(t.startTime);
                Ledger.Item newest = e.ledger.latest(destId, key);
                int maxGen = newest == null ? -1 : newest.generation;
                if (onlyIfChanged && newest != null) {
                    // Poisoned or user-skipped rows are not retried behind the user's back.
                    if (Ledger.FAILED.equals(newest.state) || (Ledger.SKIPPED.equals(newest.state)
                            && Ledger.SKIP_USER.equals(newest.errorClass))) continue;
                    if (rowHash == null) rowHash = tripRowHash(t.id, t.startTime);
                    if (rowHash == null) continue;
                    boolean open = Ledger.PENDING.equals(newest.state) || Ledger.ACTIVE.equals(newest.state);
                    // An open row not staged yet will stage the current row; a staged or sent one carries its hash.
                    String known = open ? newest.meta().optString("rowHash", "")
                            : e.ledger.kv("trip:" + destId + ":" + t.startTime);
                    if (open && known.isEmpty()) continue;
                    if (rowHash.equals(known)) continue;
                }
                int gen = maxGen < 0 ? 0 : maxGen + 1;
                if (!onlyIfChanged && maxGen >= 0) gen = maxGen;
                Ledger.Item it = base(destId, SafeKeepConfig.Lane.TRIPS, Naming.Kind.TRIP_ROW, key, gen, now);
                it.remotePath = Naming.tripRowPath(c, d, t.startTime, enc);
                it.pclass = 0;
                it.pscore = t.startTime / 1000L;
                it.encrypted = enc;
                it.metaJson = meta(t);
                if (e.ledger.insertIfAbsent(it) > 0) {
                    created++;
                    e.ledger.supersedeOlder(destId, key, gen);
                }
                File tel = t.telemetryFilePath == null ? null : new File(t.telemetryFilePath);
                if (tel != null && RemovableStorageFs.isFile(tel)) {
                    Ledger.Item ti = base(destId, SafeKeepConfig.Lane.TRIPS, Naming.Kind.TRIP_TELEMETRY,
                            Naming.telemetryKey(t.startTime), 0, now);
                    ti.localPath = tel.getAbsolutePath();
                    ti.sizeBytes = RemovableStorageFs.length(tel);
                    ti.mtime = RemovableStorageFs.lastModified(tel);
                    ti.remotePath = Naming.telemetryPath(c, d, t.startTime, enc);
                    ti.pclass = 0;
                    ti.pscore = t.startTime / 1000L - 1;
                    ti.encrypted = enc;
                    ti.metaJson = meta(t);
                    if (e.ledger.insertIfAbsent(ti) > 0) created++;
                }
            }
        }
        for (SafeKeepConfig.Route r : c.routesFor(SafeKeepConfig.Lane.TRIP_EXPORTS)) {
            for (String destId : r.destIds) {
                SafeKeepConfig.Destination d = c.destination(destId);
                if (d == null || !d.enabled) continue;
                if (onlyDests != null && !onlyDests.contains(destId)) continue;
                boolean enc = d.encrypt && r.encrypt != 0;
                Ledger.Item gi = base(destId, SafeKeepConfig.Lane.TRIP_EXPORTS, Naming.Kind.TRIP_GPX,
                        Naming.gpxKey(t.startTime), 0, now);
                gi.remotePath = Naming.gpxPath(c, d, t.startTime, t.distanceKm, enc);
                gi.pclass = 2;
                gi.pscore = t.startTime / 1000L;
                gi.encrypted = enc;
                gi.metaJson = meta(t);
                if (e.ledger.insertIfAbsent(gi) > 0) created++;
            }
        }
        if (created > 0) e.wake();
        return created;
    }

    /** Backfill of finished trips from the last {@code days} (0 = all history). */
    int backfillTrips(int days) {
        com.overdrive.app.trips.TripDatabase db = Staging.tripDb();
        if (db == null) return 0;
        int created = 0, offset = 0;
        while (true) {
            List<TripRecord> page = db.getTrips(days <= 0 ? 36500 : days, 200, offset);
            if (page.isEmpty()) break;
            for (TripRecord t : page) if (t.endTime > 0) created += enqueueTrip(t, true);
            offset += page.size();
            if (page.size() < 200 || offset > 50_000) break;
        }
        return created;
    }

    private static String meta(TripRecord t) {
        try {
            return new JSONObject().put("tripId", t.id).put("startTime", t.startTime)
                    .put("distanceKm", t.distanceKm).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Ledger.Item base(String destId, SafeKeepConfig.Lane lane, Naming.Kind kind, String key,
                                    int gen, long now) {
        Ledger.Item it = new Ledger.Item();
        it.destId = destId;
        it.lane = lane.id;
        it.kind = kind.name();
        it.logicalKey = key;
        it.generation = gen;
        it.groupKey = key;
        it.createdAt = now;
        return it;
    }

    static String tripRowHash(long tripId, long startTime) {
        try {
            JSONObject row = Staging.tripRow(tripId);
            if (row == null) return null;
            return sha256(row.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    // ── settings ──────────────────────────────────────────────────────────

    void settingsChanged() {
        if (e.cfg.routesFor(SafeKeepConfig.Lane.SETTINGS).isEmpty()) return;
        if (settingsTimer != null && !settingsTimer.isDone()) return;
        try {
            settingsTimer = e.ctl.schedule(() -> e.runCtl(this::settingsSnapshot), SETTINGS_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    void settingsSnapshot() {
        SafeKeepConfig c = e.cfg;
        if (c.routesFor(SafeKeepConfig.Lane.SETTINGS).isEmpty()) return;
        long now = System.currentTimeMillis();
        if (lastSettingsEnqueueAt > 0 && now - lastSettingsEnqueueAt < SETTINGS_MIN_GAP_MS) {
            try {
                settingsTimer = e.ctl.schedule(() -> e.runCtl(this::settingsSnapshot),
                        lastSettingsEnqueueAt + SETTINGS_MIN_GAP_MS - now, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
            return;
        }
        String hash;
        try {
            hash = Staging.settingsHash(Staging.settingsForCloud());
        } catch (Exception ex) {
            logger.warn("Settings snapshot failed: " + ex.getMessage());
            return;
        }
        boolean any = false;
        for (SafeKeepConfig.Route r : c.routesFor(SafeKeepConfig.Lane.SETTINGS)) {
            for (String destId : r.destIds) {
                SafeKeepConfig.Destination d = c.destination(destId);
                if (d == null || !d.enabled) continue;
                if (hash.equals(e.ledger.kv("settings:" + destId))) continue;
                boolean enc = d.encrypt && r.encrypt != 0;
                String key = Naming.settingsKey(now);
                int gen = Math.max(0, e.ledger.maxGeneration(destId, key) + 1);
                // Only the newest snapshot is worth sending.
                e.ledger.supersedePendingSettings(destId);
                Ledger.Item it = base(destId, SafeKeepConfig.Lane.SETTINGS, Naming.Kind.SETTINGS, key, gen, now);
                it.remotePath = Naming.settingsPath(c, d, now, enc);
                it.pclass = 0;
                it.pscore = now / 1000L;
                it.encrypted = enc;
                try {
                    it.metaJson = new JSONObject().put("hash", hash).toString();
                } catch (Exception ignored) {
                }
                if (e.ledger.insertIfAbsent(it) > 0) {
                    e.ledger.supersedeOlder(destId, key, gen);
                    any = true;
                }
            }
        }
        if (any) {
            lastSettingsEnqueueAt = now;
            e.wake();
        }
    }

    static String sha256(byte[] b) throws Exception {
        return com.overdrive.app.safekeep.util.StreamDigests.hex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
