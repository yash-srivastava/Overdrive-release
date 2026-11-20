package com.overdrive.app.geo;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Retroactive place-name backfill for recordings whose sidecar has a GPS fix
 * (a {@code geo} block with lat/lng) but NO resolved {@code geo.place}.
 *
 * <p>WHY THIS EXISTS: at record time the place name is resolved cache-first
 * (synchronous {@code resolveCachedOnly}); on a FIRST visit to a location the
 * cache misses and the single async {@link GeocodingResolver#resolveAsync}
 * attempt can also miss (Nominatim rate-limit/cooldown, transient network, the
 * bounded geocode queue dropping the task under a burst). When every one of
 * those misses, the event is left PERMANENTLY untagged — the sidecar has a
 * {@code geo} fix but no {@code geo.place}, so the events page shows no place
 * chip. {@link GeocodingResolver#resolveBlocking} was written for exactly this
 * sweep but was never wired up. This class wires it.
 *
 * <p>DESIGN — conservative and idempotent:
 * <ul>
 *   <li>Only touches sidecars that HAVE a {@code geo} fix and LACK
 *       {@code geo.place} — already-tagged and fix-less clips are skipped, so
 *       a steady-state library is a cheap stat-only pass.</li>
 *   <li>Only recent files (within {@link #MAX_AGE_MS}) — we don't re-walk an
 *       entire historical library every tick; a months-old untagged clip is not
 *       worth the geocode budget.</li>
 *   <li>Per-sweep work cap ({@link #MAX_RESOLVES_PER_SWEEP}) so a backlog drains
 *       gradually instead of hammering Nominatim; the resolver's own
 *       {@code NominatimRateLimiter} (1 req/s + persisted cooldown) is the hard
 *       throttle underneath.</li>
 *   <li>Online gate respected: {@code resolveBlocking} applies the per-flow
 *       {@code geocoding} enable + {@code allowOnline} toggles and returns null
 *       when disabled, so a user who turned geocoding off gets no network I/O.</li>
 *   <li>Every failure is swallowed — a backfill miss leaves the sidecar exactly
 *       as it was (untagged), never breaks recording/playback.</li>
 * </ul>
 *
 * <p>Runs on a caller-supplied background thread (the daemon's periodic
 * scheduler). Never call from a hot/UI/GL thread — {@code resolveBlocking}
 * performs synchronous network I/O.
 */
public final class GeoBackfillSweep {

    private static final DaemonLogger logger = DaemonLogger.getInstance("GeoBackfill");

    /** Only backfill clips finalized within this window (sidecar mtime). */
    private static final long MAX_AGE_MS = 6L * 60L * 60L * 1000L;  // 6 hours

    /** Max online resolves per sweep — drains a backlog gradually. */
    private static final int MAX_RESOLVES_PER_SWEEP = 8;

    /** After a resolve MISS (coords with no Nominatim address — offshore/
     *  unsurveyed), skip the clip for this long so a permanently-unresolvable fix
     *  stops re-consuming the per-sweep budget every 10-min tick. Self-clearing:
     *  retried after the cooldown in case map coverage improved; still bounded by
     *  the 6h MAX_AGE_MS hard ceiling. */
    private static final long UNRESOLVED_COOLDOWN_MS = 60L * 60L * 1000L;  // 1 hour

    /** Cap on sidecar bytes we'll parse (mirror SidecarGeoUpdater/RecordingScanner). */
    private static final int MAX_BYTES = 256 * 1024;

    private GeoBackfillSweep() {}

    /**
     * One sweep pass. Walks recording + surveillance dirs, finds recent
     * untagged-but-fix-bearing sidecars, and resolves+merges up to
     * {@link #MAX_RESOLVES_PER_SWEEP} of them. Returns the number merged.
     */
    public static int run() {
        int merged = 0;
        // Count online resolve ATTEMPTS, not just successful merges, against the
        // per-sweep budget. A fix-bearing clip whose coords are genuinely
        // unresolvable (offshore/unsurveyed → Nominatim 200 with no address) returns
        // null AND clears the rate-limiter cooldown (recordSuccess), so without an
        // attempt-cap it would re-hit Nominatim on every clip every sweep. Capping
        // attempts bounds network I/O regardless of outcome.
        int attempts = 0;
        try {
            StorageManager sm = StorageManager.getInstance();
            long now = System.currentTimeMillis();

            List<File> dirs = new ArrayList<>();
            try { dirs.addAll(sm.getAllSurveillanceDirs()); } catch (Throwable ignored) {}
            try { dirs.addAll(sm.getAllRecordingsDirs()); } catch (Throwable ignored) {}
            try { dirs.addAll(sm.getAllProximityDirs()); } catch (Throwable ignored) {}

            GeocodingResolver resolver = GeocodingResolver.getInstance();

            // PHASE 1 — gather candidates (NO network). A candidate is a recent
            // sidecar with a usable geo fix and no resolved place. We then sort
            // NEWEST-mtime-first so the per-sweep online budget always goes to the
            // freshest clips: dir.listFiles() returns raw OS order, so without this
            // a backlog of older UNRESOLVABLE clips (open-water GPS → empty-address
            // 200, which returns null and re-qualifies every sweep) could burn the
            // 8-attempt budget ahead of a just-recorded resolvable clip, delaying
            // its tag by up to MAX_AGE_MS. Phase 1 is the cheap stat/parse pass the
            // class is designed around.
            List<Candidate> candidates = new ArrayList<>();
            for (File dir : dirs) {
                File[] sidecars = sm.listFilesWithFallback(dir, ".json");
                if (sidecars == null) continue;
                for (File sidecar : sidecars) {
                    try {
                        long mtime = RemovableStorageFs.lastModified(sidecar);
                        if (mtime <= 0L || now - mtime < 0L) continue;
                        // STAT-ONLY fast skip (the "cheap pass" this class promises):
                        // a clip whose mtime is already older than MAX_AGE_MS can
                        // never be eligible — drop it WITHOUT a read+parse. Safe for
                        // marked clips too: markPlaceUnresolved rewrites the sidecar
                        // (resetting mtime to ~now) and the marker is retried only
                        // within the 6h window, so a still-eligible marked clip
                        // always has mtime <= MAX_AGE_MS here; the precise
                        // marker-anchored age gate below still governs it. This
                        // turns the steady-state historical library into a true
                        // stat-only pass instead of re-parsing every sidecar/tick.
                        if (now - mtime > MAX_AGE_MS) continue;

                        JSONObject root = readSidecar(sidecar);
                        if (root == null) continue;
                        JSONObject geo = root.optJSONObject("geo");
                        if (geo == null) continue;                    // no fix → nothing to tag
                        if (geo.has("place")) continue;               // already tagged → skip

                        long unresolvedAt = geo.optLong("placeUnresolvedAtMs", 0L);
                        // AGE GATE. For a marked clip, age against the STABLE
                        // first-seen marker (the rewrite bumped mtime, so mtime
                        // would never expire); for an unmarked clip, age against
                        // mtime (its finalize time). Either way, stop at MAX_AGE_MS.
                        long ageBasis = unresolvedAt > 0L ? unresolvedAt : mtime;
                        if (now - ageBasis > MAX_AGE_MS) continue;
                        // Cooldown: skip a recently-failed clip until it elapses,
                        // so an unresolvable fix doesn't re-consume the budget.
                        if (unresolvedAt > 0L && (now - unresolvedAt) < UNRESOLVED_COOLDOWN_MS) continue;
                        double[] ll = extractLatLng(geo);
                        if (ll == null) continue;                     // no usable coords

                        candidates.add(new Candidate(sidecar, ll[0], ll[1], mtime));
                    } catch (Throwable t) {
                        logger.warn("Backfill scan failed for " + sidecar.getName() + ": " + t.getMessage());
                    }
                }
            }
            // Newest first.
            candidates.sort((a, b) -> Long.compare(b.mtime, a.mtime));

            // PHASE 2 — resolve newest-first, capped on ATTEMPTS (network calls),
            // not just successes.
            for (Candidate c : candidates) {
                if (attempts >= MAX_RESOLVES_PER_SWEEP) break;
                try {
                    String flow = inferFlow(c.sidecar.getName());
                    // Let the 1-token/s bucket REFILL before a throttled candidate so
                    // this sweep can do up to MAX_RESOLVES_PER_SWEEP real resolves,
                    // not just one. Without this, candidate #1 takes the only token
                    // and #2..#8 throttle-skip but still burn the attempt budget —
                    // so with newest-first ordering, older first-visit clips on a
                    // road trip starve and age out untagged at 6h. Safe to block:
                    // this runs on the dedicated GeoBackfill daemon thread (off the
                    // memory-watchdog scheduler); shutdownNow() interrupts the sleep.
                    // Still <=8 calls/sweep and <=1 req/s → OSM-compliant.
                    if (NominatimRateLimiter.isThrottled()) {
                        try { Thread.sleep(1100L); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    attempts++;   // count the online attempt regardless of outcome
                    // reached[0] is set true ONLY when our own call won the
                    // rate-limiter token and issued the HTTP request — so a null
                    // with reached[0]==true is a GENUINE reach-but-empty-address
                    // miss (offshore/unsurveyed), the only case worth pinning.
                    boolean[] reached = new boolean[1];
                    PlaceResult place = resolver.resolveBlocking(c.lat, c.lng, flow, reached);
                    if (place == null) {
                        // Mark unresolved ONLY for a genuine reach-but-empty miss.
                        // Every "didn't actually reach Nominatim" null — token raced
                        // away by a concurrent live resolve, active cooldown, offline
                        // mode, or geocoding disabled — is transient and must be
                        // retried next tick, NOT pinned for an hour. The explicit
                        // reached flag replaces the earlier racy rate-limiter probes
                        // (isThrottled was polluted by our own just-consumed token).
                        boolean genuineEmptyMiss = reached[0]
                                && NominatimRateLimiter.getCooldownUntilMs() <= System.currentTimeMillis();
                        if (genuineEmptyMiss) {
                            try { SidecarGeoUpdater.markPlaceUnresolved(c.sidecar, now); } catch (Throwable ignored) {}
                        }
                        continue;
                    }

                    File mp4 = mp4SiblingOf(c.sidecar);
                    if (mp4 != null) {
                        SidecarGeoUpdater.mergePlaceForMp4(mp4, place); // routes through H2 index upsert
                    } else {
                        SidecarGeoUpdater.mergePlace(c.sidecar, place);
                    }
                    merged++;
                    logger.info("Backfilled place for " + c.sidecar.getName());
                } catch (Throwable t) {
                    logger.warn("Backfill failed for " + c.sidecar.getName() + ": " + t.getMessage());
                }
            }
            if (merged > 0) logger.info("Geo backfill sweep merged " + merged + " place tag(s)");
        } catch (Throwable t) {
            logger.warn("Geo backfill sweep error: " + t.getMessage());
        }
        return merged;
    }

    /** A scanned untagged-but-fix-bearing sidecar awaiting online resolve. */
    private static final class Candidate {
        final File sidecar;
        final double lat;
        final double lng;
        final long mtime;
        Candidate(File sidecar, double lat, double lng, long mtime) {
            this.sidecar = sidecar; this.lat = lat; this.lng = lng; this.mtime = mtime;
        }
    }

    /** Pull lat/lng from a v3 geo block — directly, or from geo.start/peak/end. */
    private static double[] extractLatLng(JSONObject geo) {
        double[] direct = latLngFrom(geo);
        if (direct != null) return direct;
        for (String k : new String[] {"start", "peak", "end"}) {
            JSONObject sub = geo.optJSONObject(k);
            if (sub != null) {
                double[] ll = latLngFrom(sub);
                if (ll != null) return ll;
            }
        }
        return null;
    }

    private static double[] latLngFrom(JSONObject o) {
        if (o == null || !o.has("lat") || !o.has("lng")) return null;
        double lat = o.optDouble("lat", Double.NaN);
        double lng = o.optDouble("lng", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        if (lat == 0.0 && lng == 0.0) return null;   // ocean sentinel
        return new double[] {lat, lng};
    }

    private static File mp4SiblingOf(File sidecar) {
        String n = sidecar.getName();
        if (!n.endsWith(".json")) return null;
        File mp4 = new File(sidecar.getParentFile(), n.substring(0, n.length() - 5) + ".mp4");
        return RemovableStorageFs.exists(mp4) ? mp4 : null;
    }

    private static String inferFlow(String sidecarName) {
        return sidecarName != null && sidecarName.startsWith("event_") ? "surveillance" : "recording";
    }

    private static JSONObject readSidecar(File f) {
        if (f == null || !RemovableStorageFs.exists(f)) return null;
        try (java.io.Reader r = new java.io.InputStreamReader(RemovableStorageFs.openInput(f), java.nio.charset.StandardCharsets.UTF_8)) {
            long len = Math.min(RemovableStorageFs.length(f), MAX_BYTES);
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder((int) len);
            int total = 0, n;
            while (total < len && (n = r.read(buf, 0, (int) Math.min(buf.length, len - total))) > 0) {
                sb.append(buf, 0, n);
                total += n;
            }
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }
}
