package com.overdrive.app.geo;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.storage.removable.RemovableStorageFs;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

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
 *       {@code geo.place} — already-tagged and fix-less clips are skipped.</li>
 *   <li>Only recent files (within {@link #MAX_AGE_MS}) — we don't re-walk an
 *       historical library's JSON every tick; older files are skipped using
 *       listing metadata.</li>
 *   <li>Per-sweep work cap ({@link #MAX_RESOLVES_PER_SWEEP}) so a backlog drains
 *       gradually instead of hammering Nominatim; the resolver's own
 *       {@code NominatimRateLimiter} (1 req/s + persisted cooldown) is the hard
 *       throttle underneath.</li>
 *   <li>Disabled flows are skipped before opening sidecars. The resolver still
 *       applies the live enable and {@code allowOnline} gates; offline tiers
 *       remain available when only online geocoding is disabled.</li>
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

    /** Max resolver attempts per sweep — drains a backlog gradually. */
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
        try {
            // Snapshot the flow gates once per sweep. The resolver still checks the
            // live setting before each lookup; newly enabled flows join next tick.
            boolean recordingEnabled = UnifiedConfigManager.isGeocodingEnabledForFlow("recording");
            boolean surveillanceEnabled = UnifiedConfigManager.isGeocodingEnabledForFlow("surveillance");
            if (!recordingEnabled && !surveillanceEnabled) return 0;
            StorageManager sm = StorageManager.getInstance();
            long now = System.currentTimeMillis();

            List<File> dirs = new ArrayList<>();
            try { dirs.addAll(sm.getAllSurveillanceDirs()); } catch (Throwable ignored) {}
            try { dirs.addAll(sm.getAllRecordingsDirs()); } catch (Throwable ignored) {}
            try { dirs.addAll(sm.getAllProximityDirs()); } catch (Throwable ignored) {}

            GeocodingResolver resolver = GeocodingResolver.getInstance();

            // Collect only listing metadata first. Sort before opening JSON so older
            // sidecars need not be read after the resolve budget is exhausted.
            List<RecentSidecar> recent = new ArrayList<>();
            for (File dir : dirs) {
                File[] sidecars = sm.listFilesWithFallback(dir, ".json");
                if (sidecars == null) continue;
                for (File sidecar : sidecars) {
                    try {
                        String flow = inferFlow(sidecar.getName());
                        if ("surveillance".equals(flow) ? !surveillanceEnabled : !recordingEnabled) continue;
                        long mtime = RemovableStorageFs.listedLastModified(sidecar);
                        if (mtime <= 0L || now - mtime < 0L || now - mtime > MAX_AGE_MS) continue;
                        recent.add(new RecentSidecar(sidecar, mtime, flow));
                    } catch (Throwable t) {
                        logger.warn("Backfill scan failed for " + sidecar.getName() + ": " + t.getMessage());
                    }
                }
            }
            merged = scanRecent(recent, now, GeoBackfillSweep::readSidecar,
                    (entry, ll) -> resolveCandidate(resolver, entry, ll, now));
            if (merged > 0) logger.info("Geo backfill sweep merged " + merged + " place tag(s)");
        } catch (Throwable t) {
            logger.warn("Geo backfill sweep error: " + t.getMessage());
        }
        return merged;
    }

    // Inject reads and resolutions so the work budget can be tested without disk/network I/O.
    static int scanRecent(List<RecentSidecar> recent, long now,
                          Function<File, JSONObject> reader,
                          BiPredicate<RecentSidecar, double[]> resolve) {
        // List.sort is stable, preserving listing order for equal mtimes.
        recent.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        int attempts = 0;
        int merged = 0;
        for (RecentSidecar entry : recent) {
            if (attempts >= MAX_RESOLVES_PER_SWEEP || Thread.currentThread().isInterrupted()) break;
            try {
                JSONObject root = reader.apply(entry.sidecar);
                if (root == null) continue;
                JSONObject geo = root.optJSONObject("geo");
                if (geo == null || geo.has("place")) continue;
                // Rewrites refresh mtime, but the first unresolved marker anchors
                // the retry window and cooldown, just as before.
                long unresolvedAt = geo.optLong("placeUnresolvedAtMs", 0L);
                long ageBasis = unresolvedAt > 0L ? unresolvedAt : entry.mtime;
                if (now - ageBasis > MAX_AGE_MS) continue;
                if (unresolvedAt > 0L && now - unresolvedAt < UNRESOLVED_COOLDOWN_MS) continue;
                double[] ll = extractLatLng(geo);
                if (ll == null) continue;

                // Count attempts, including misses/errors, not just successful merges.
                attempts++;
                if (resolve.test(entry, ll)) merged++;
            } catch (Throwable t) {
                logger.warn("Backfill failed for " + entry.sidecar.getName() + ": " + t.getMessage());
            }
        }
        return merged;
    }

    private static boolean resolveCandidate(GeocodingResolver resolver, RecentSidecar entry,
                                            double[] ll, long now) {
        // Allow the token bucket to refill, on the dedicated backfill worker only.
        if (NominatimRateLimiter.isThrottled()) {
            try { Thread.sleep(1100L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        boolean[] reached = new boolean[1];
        PlaceResult place = resolver.resolveBlocking(ll[0], ll[1], entry.flow, reached);
        if (place == null) {
            // Only a genuine online empty-address miss warrants an unresolved marker.
            boolean genuineEmptyMiss = reached[0]
                    && NominatimRateLimiter.getCooldownUntilMs() <= System.currentTimeMillis();
            if (genuineEmptyMiss) {
                try { SidecarGeoUpdater.markPlaceUnresolved(entry.sidecar, now); } catch (Throwable ignored) {}
            }
            return false;
        }
        File mp4 = mp4SiblingOf(entry.sidecar);
        if (mp4 != null) {
            SidecarGeoUpdater.mergePlaceForMp4(mp4, place); // routes through H2 index upsert
        } else {
            SidecarGeoUpdater.mergePlace(entry.sidecar, place);
        }
        logger.info("Backfilled place for " + entry.sidecar.getName());
        return true;
    }

    /** Recent listing metadata; no JSON or coordinates retained during sorting. */
    static final class RecentSidecar {
        final File sidecar;
        final long mtime;
        final String flow;
        RecentSidecar(File sidecar, long mtime, String flow) {
            this.sidecar = sidecar;
            this.mtime = mtime;
            this.flow = flow;
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
        if (!GeoCoordinates.isUsable(lat, lng)) return null;
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
