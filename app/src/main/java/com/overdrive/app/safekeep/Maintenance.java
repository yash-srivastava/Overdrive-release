package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.dest.DestError;
import com.overdrive.app.safekeep.dest.Destination;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safety nets that are not on the hot path: the reconcile sweep (clips produced while hooks
 * were not listening), trip-change detection, pin expiry, and remote retention / quota pruning.
 */
final class Maintenance {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepMaint");
    private static final long DAY = 24 * 3600_000L;
    private static final int RECONCILE_PAGE = 200;
    /** Per pass; a cursor carries the sweep on from where it stopped. */
    private static final int RECONCILE_MAX_SCAN = 4000;
    private static final int RECONCILE_MAX_ENQUEUE = 500;
    /** Sidecars are re-checked file by file for this long after capture (late writers). */
    private static final long SIDECAR_REPAIR_MS = 3 * DAY;
    private static final String[] SIDECARS = {".jpg", ".json", ".srt"};
    private static final int PRUNE_MAX_PER_RUN = 500;

    private final Engine e;

    Maintenance(Engine e) {
        this.e = e;
    }

    /** Control thread. Newest-first, bounded per pass. Nothing is anchored on an unset clock. */
    void reconcile() {
        if (e.stopping || !e.ledger.isOpen()) return;
        SafeKeepConfig c = e.cfg;
        try {
            if (e.signals.capture(5000).clockValid) {
                for (SafeKeepConfig.Lane lane : new SafeKeepConfig.Lane[]{SafeKeepConfig.Lane.EVIDENCE,
                        SafeKeepConfig.Lane.ARCHIVE}) {
                    List<SafeKeepConfig.Route> routes = c.routesFor(lane);
                    if (routes.isEmpty()) continue;
                    Set<String> types = new HashSet<>();
                    for (SafeKeepConfig.Route r : routes) {
                        if (lane == SafeKeepConfig.Lane.EVIDENCE) {
                            if (r.includeSentry) types.add("sentry");
                            if (r.includeProximity) types.add("proximity");
                            if (r.includeReplay) types.add("replay");
                        } else {
                            types.add("normal");
                            if (r.includeOemDashcam) types.add("oemDashcam");
                        }
                    }
                    if (types.isEmpty()) continue;
                    Map<String, Long> windows = windows(c, lane, routes);
                    if (!windows.isEmpty()) sweepLane(lane, types, windows);
                }
                if (!c.routesFor(SafeKeepConfig.Lane.TRIPS).isEmpty()
                        || !c.routesFor(SafeKeepConfig.Lane.TRIP_EXPORTS).isEmpty()) {
                    sweepTrips(c);
                }
            }
            for (String p : e.retention.expiredPins()) e.retention.unpin(p);
            e.retention.pruneLocks();
        } catch (Throwable t) {
            logger.warn("Reconcile failed: " + t.getMessage());
        }
        e.wake();
    }

    /** First time this key is seen (clock already validated), so "0 days" never backfills older footage. */
    private long anchor(String key) {
        String since = e.ledger.kv(key);
        long anchor;
        try {
            anchor = since == null ? 0 : Long.parseLong(since);
        } catch (NumberFormatException ex) {
            anchor = 0;
        }
        if (anchor <= 0) {
            anchor = System.currentTimeMillis();
            e.ledger.putKv(key, Long.toString(anchor));
        }
        return anchor;
    }

    /** Backfill window start per destination: its own first-seen time minus its route's backfill days. */
    private Map<String, Long> windows(SafeKeepConfig c, SafeKeepConfig.Lane lane, List<SafeKeepConfig.Route> routes) {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (SafeKeepConfig.Route r : routes) {
            for (String d : r.destIds) {
                SafeKeepConfig.Destination dc = c.destination(d);
                if (dc == null || !dc.enabled) continue;
                long from = anchor("since." + lane.id + "." + d) - r.backfillDays * DAY;
                long floor = now - Math.max(30, r.backfillDays) * DAY;
                long w = Math.max(from, floor);
                Long prev = out.get(d);
                out.put(d, prev == null ? w : Math.min(prev, w));
            }
        }
        return out;
    }

    private void sweepLane(SafeKeepConfig.Lane lane, Set<String> types, Map<String, Long> windows) {
        RecordingsIndex idx;
        try {
            idx = RecordingsIndex.getInstance();
            if (!idx.isAvailable()) return;
        } catch (Throwable t) {
            return;
        }
        long oldest = Long.MAX_VALUE;
        for (long w : windows.values()) oldest = Math.min(oldest, w);
        String cursorKey = "reconcile.cursor." + lane.id;
        long cursor = 0;
        try {
            String v = e.ledger.kv(cursorKey);
            cursor = v == null || v.isEmpty() ? 0 : Long.parseLong(v);
        } catch (NumberFormatException ignored) {
        }
        RecordingsIndex.Filter f = new RecordingsIndex.Filter();
        f.types = types;
        f.fromMs = oldest;
        if (cursor > oldest) f.toMs = cursor;
        long now = System.currentTimeMillis();
        int offset = 0, scanned = 0, enqueued = 0;
        long lastTs = 0;
        boolean finished = false;
        while (!e.stopping) {
            List<JSONObject> rows = idx.queryRecordings(f, RECONCILE_PAGE, offset);
            if (rows.isEmpty()) {
                finished = true;
                break;
            }
            for (JSONObject row : rows) {
                scanned++;
                String path = row.optString("path", "");
                long ts = row.optLong("timestamp", 0);
                if (ts > 0) lastTs = ts;
                if (path.isEmpty()) continue;
                File file = new File(path);
                Naming.Kind kind = Naming.classify(file.getName());
                if (kind == null || kind == Naming.Kind.EVENT_SIDECAR || kind == Naming.Kind.CLIP_SIDECAR) continue;
                boolean recent = ts > 0 && now - ts < SIDECAR_REPAIR_MS;
                Set<String> need = new HashSet<>();
                for (Map.Entry<String, Long> w : windows.entrySet()) {
                    if (ts > 0 && ts < w.getValue()) continue;
                    if (missingFor(w.getKey(), file, recent)) need.add(w.getKey());
                }
                if (need.isEmpty() || !RemovableStorageFs.isFile(file)) continue;
                enqueued += e.enqueuer.enqueueMedia(file, kind, false, false, false, need);
            }
            if (rows.size() < RECONCILE_PAGE) {
                finished = true;
                break;
            }
            offset += rows.size();
            if (scanned >= RECONCILE_MAX_SCAN || enqueued >= RECONCILE_MAX_ENQUEUE) break;
        }
        // Next pass resumes below the last row seen; a full sweep starts again from the newest.
        e.ledger.putKv(cursorKey, finished || lastTs <= 0 ? "" : Long.toString(lastTs));
    }

    /** The clip, or (while recent) one of its sidecars, has no row on this destination. */
    private boolean missingFor(String destId, File anchor, boolean recent) {
        if (!e.ledger.hasRowFor(destId, Naming.fileKey(anchor.getName()))) return true;
        if (!recent) return false;
        File dir = anchor.getParentFile();
        String stem = Naming.stem(anchor.getName());
        for (String ext : SIDECARS) {
            File sc = new File(dir, stem + ext);
            if (RemovableStorageFs.isFile(sc) && RemovableStorageFs.length(sc) > 0 && !e.ledger.hasRowFor(destId, Naming.fileKey(sc.getName()))) return true;
        }
        return false;
    }

    /**
     * New trips inside each destination's own backfill window, and changed rows of trips it already
     * has (scores, route, names).
     */
    private void sweepTrips(SafeKeepConfig c) {
        com.overdrive.app.trips.TripDatabase db = Staging.tripDb();
        if (db == null) return;
        long now = System.currentTimeMillis();
        Map<String, Long> windows = new HashMap<>();
        int backfill = 0;
        for (SafeKeepConfig.Lane lane : new SafeKeepConfig.Lane[]{SafeKeepConfig.Lane.TRIPS,
                SafeKeepConfig.Lane.TRIP_EXPORTS}) {
            for (SafeKeepConfig.Route r : c.routesFor(lane)) {
                backfill = Math.max(backfill, r.backfillDays);
                for (String d : r.destIds) {
                    SafeKeepConfig.Destination dc = c.destination(d);
                    if (dc == null || !dc.enabled) continue;
                    long w = Math.max(anchor("since." + lane.id + "." + d) - r.backfillDays * DAY,
                            now - Math.max(30, r.backfillDays) * DAY);
                    Long prev = windows.get(d);
                    windows.put(d, prev == null ? w : Math.min(prev, w));
                }
            }
        }
        if (windows.isEmpty()) return;
        List<TripRecord> trips = db.getTrips(Math.max(14, backfill), 500, 0);
        for (TripRecord t : trips) {
            if (t.endTime <= 0) continue;
            Set<String> need = new HashSet<>();
            for (Map.Entry<String, Long> w : windows.entrySet()) {
                boolean known = e.ledger.maxGeneration(w.getKey(), Naming.tripKey(t.startTime)) >= 0
                        || e.ledger.maxGeneration(w.getKey(), Naming.gpxKey(t.startTime)) >= 0;
                if (known || t.startTime >= w.getValue()) need.add(w.getKey());
            }
            if (!need.isEmpty()) e.enqueuer.enqueueTrip(t, true, need);
        }
    }

    /**
     * Forgets the backfill anchor of every (lane, destination) pair that is no longer routed, so
     * routing it again starts from "now" plus its own backfill days, not from the old anchor.
     */
    void resetAnchors(SafeKeepConfig c) {
        for (SafeKeepConfig.Lane lane : new SafeKeepConfig.Lane[]{SafeKeepConfig.Lane.EVIDENCE,
                SafeKeepConfig.Lane.ARCHIVE, SafeKeepConfig.Lane.TRIPS, SafeKeepConfig.Lane.TRIP_EXPORTS}) {
            for (SafeKeepConfig.Destination d : c.destinations) {
                boolean routed = d.enabled && c.route(lane, d.id) != null;
                String key = "since." + lane.id + "." + d.id;
                if (!routed && e.ledger.kv(key) != null && !e.ledger.kv(key).isEmpty()) e.ledger.putKv(key, "");
            }
        }
    }

    // ── remote retention (aux thread: network) ──────────────────────────────

    void janitor() {
        if (e.stopping || !e.ledger.isOpen()) return;
        SafeKeepConfig c = e.cfg;
        long now = System.currentTimeMillis();
        for (SafeKeepConfig.Destination d : c.destinations) {
            Destination drv = e.dests.get(d.id);
            if (drv == null || !d.enabled || !drv.caps().canDelete || d.params.optBoolean("writeOnly", false)) {
                continue;
            }
            String st = e.destState(d.id).state;
            if (!"ok".equals(st) && !"quota_full".equals(st)) continue;
            int budget = PRUNE_MAX_PER_RUN;
            try {
                if (d.eventsDays > 0) budget -= prune(drv, d, "evidence", now - d.eventsDays * DAY, budget);
                if (d.archiveDays > 0) budget -= prune(drv, d, "archive", now - d.archiveDays * DAY, budget);
                int done = e.ledger.countDoneSettings(d.id);
                if (done > d.settingsKeep) {
                    for (Ledger.Item it : e.ledger.doneSettingsOldestFirst(d.id, done - d.settingsKeep)) {
                        if (budget-- <= 0) break;
                        deleteRemote(drv, it);
                    }
                }
                checkQuota(drv, d, budget);
            } catch (DestError err) {
                if (err.kind == DestError.Kind.AUTH_EXPIRED) e.setDestState(d.id, "needs_auth", err.getMessage(), 0);
                logger.warn("Retention on " + d.id + " stopped: " + err.getMessage());
            } catch (Throwable t) {
                logger.warn("Retention on " + d.id + " failed: " + t.getMessage());
            }
        }
    }

    /** Result of a quota check: only {@link #FREED} is real relief. */
    enum Relief { UNKNOWN, UNDER, FREED, STILL_FULL }

    void quotaPressure(String destId) {
        e.runMaint(() -> {
            SafeKeepConfig.Destination d = e.cfg.destination(destId);
            Destination drv = e.dests.get(destId);
            if (d == null || drv == null || !drv.caps().canDelete || d.params.optBoolean("writeOnly", false)) return;
            try {
                // Unknown or already-low usage means the refusal came from elsewhere: the 30-min hold stands.
                if (checkQuota(drv, d, PRUNE_MAX_PER_RUN) == Relief.FREED) e.setDestState(destId, "ok", null, 0);
            } catch (Throwable t) {
                logger.warn("Quota relief on " + destId + " failed: " + t.getMessage());
            }
        });
    }

    /** Rows of locked clips stay at the head of oldest-first lists; fetch past them. */
    private int lockedRowAllowance() {
        return e.retention.lockedStems().size() * (SIDECARS.length + 1);
    }

    private int prune(Destination drv, SafeKeepConfig.Destination d, String lane, long cutoff, int budget)
            throws DestError {
        int n = 0;
        for (Ledger.Item it : e.ledger.prunable(d.id, lane, cutoff, Math.max(1, budget) + lockedRowAllowance())) {
            if (n >= budget || e.stopping) break;
            if (e.retention.isLockedItem(it)) continue;
            deleteRemote(drv, it);
            n++;
        }
        return n;
    }

    private void deleteRemote(Destination drv, Ledger.Item it) throws DestError {
        // A newer DONE row may point at the same object (same path, or a Drive file updated in place).
        if (!e.ledger.sharedRemoteRef(it.destId, it.remoteRef, it.id)) {
            try {
                drv.delete(it.remoteRef);
            } catch (DestError err) {
                if (err.httpCode != 404 && err.kind != DestError.Kind.SESSION_GONE) throw err;
            }
        }
        e.ledger.markState(it.id, Ledger.PRUNED, null, null);
    }

    private Relief checkQuota(Destination drv, SafeKeepConfig.Destination d, int budget) throws DestError {
        Destination.Quota q = drv.quota();
        if (q == null || q.total <= 0) return Relief.UNKNOWN;
        e.noteQuota(d.id, q);
        int pct = (int) (q.used * 100 / q.total);
        if (pct < d.maxQuotaPct) return Relief.UNDER;
        e.notifier.quota(d.id, pct);
        long target = q.total * Math.max(40, d.maxQuotaPct - 5) / 100;
        long toFree = q.used - target;
        // Only SafeKeep's own copies are candidates: loop footage always, events only with a finite retention.
        List<String> lanes = new ArrayList<>();
        lanes.add("archive");
        if (d.eventsDays > 0) lanes.add("evidence");
        long prunable = e.ledger.doneBytes(d.id, lanes);
        for (String stem : e.retention.lockedStems()) prunable -= e.ledger.doneBytesForGroup(d.id, stem, lanes);
        if (prunable < toFree) {
            // Other data (or locked clips) fill the account; deleting our copies can't get it under the mark.
            logger.warn("Quota on " + d.id + " is " + pct + "% but SafeKeep's own prunable copies can't free "
                    + toFree + " bytes; nothing pruned");
            return Relief.STILL_FULL;
        }
        long freed = 0;
        int n = 0;
        int allowance = lockedRowAllowance();
        for (String lane : lanes) {
            if (freed >= toFree) break;
            for (Ledger.Item it : e.ledger.doneOldestFirst(d.id, lane, Math.max(1, budget - n) + allowance)) {
                if (freed >= toFree || n >= budget || e.stopping) break;
                if (e.retention.isLockedItem(it)) continue;
                deleteRemote(drv, it);
                freed += Math.max(0, it.sizeBytes);
                n++;
            }
        }
        return freed >= toFree ? Relief.FREED : Relief.STILL_FULL;
    }
}
