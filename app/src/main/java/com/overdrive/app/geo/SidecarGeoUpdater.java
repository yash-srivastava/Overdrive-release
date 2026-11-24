package com.overdrive.app.geo;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Atomically merge a resolved {@link PlaceResult} into an existing v3
 * JSON sidecar. Used by the async resolver-completion path so a place
 * name resolved 800 ms after the .mp4 finalized can be added to the
 * sidecar without the writer having to know it was coming.
 *
 * <p>Atomic write: read → mutate → write to {@code .tmp} sibling → rename.
 * Mirrors the discipline used by {@code EventTimelineCollector.writeJsonSidecar}
 * and {@code UnifiedConfigManager.saveConfigInternal}.
 *
 * <p>Failure semantics: every failure mode is logged at warn level and
 * silently swallowed. A failed sidecar update never breaks recording or
 * playback — the sidecar simply lacks the {@code geo.place} field, exactly
 * the state it was in before this updater ran.
 */
public final class SidecarGeoUpdater {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SidecarGeo");

    /** Cap on sidecar size we'll ingest. Mirrors RecordingScanner's 64 KB cap. */
    private static final int MAX_BYTES = 256 * 1024;

    /** Serializes the two read-modify-write paths (mergePlace + markPlaceUnresolved)
     *  so a backfill mark can't lost-update a live async resolve's freshly-merged
     *  geo.place (both are new same-session writers that can hit one sidecar).
     *  Contention is negligible (a tiny set of recent sidecars). */
    private static final Object WRITE_LOCK = new Object();

    private SidecarGeoUpdater() {}

    /**
     * Merge {@code place} into {@code sidecarFile}'s {@code geo.place} field.
     * No-op when the file doesn't exist or doesn't parse as JSON.
     */
    public static void mergePlace(File sidecarFile, PlaceResult place) {
        if (sidecarFile == null || place == null) return;
        if (!RemovableStorageFs.exists(sidecarFile) || !RemovableStorageFs.canRead(sidecarFile)) return;
        synchronized (WRITE_LOCK) {   // mutually exclusive with markPlaceUnresolved
            try {
                JSONObject root = readSidecar(sidecarFile);
                if (root == null) return;

                JSONObject geo = root.optJSONObject("geo");
                if (geo == null) {
                    geo = new JSONObject();
                    root.put("geo", geo);
                }
                geo.put("place", place.toJson());
                // Clear any prior "unresolved" marker — we just resolved it.
                geo.remove("placeUnresolvedAtMs");

                writeSidecar(sidecarFile, root);
            } catch (Throwable t) {
                logger.warn("mergePlace failed for "
                        + sidecarFile.getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Stamp {@code geo.placeUnresolvedAtMs} on a sidecar that has a GPS fix but
     * could not be resolved to a place (Nominatim returned no address for those
     * coords). The backfill sweep skips a marked sidecar for a cooldown window so
     * a permanently-unresolvable clip (offshore/unsurveyed) stops consuming the
     * per-sweep resolve budget every tick. Never touches {@code geo.place}, so a
     * later successful resolve still tags normally (and clears the marker).
     * No-op when the sidecar has no {@code geo} block (nothing to mark).
     */
    public static void markPlaceUnresolved(File sidecarFile, long whenMs) {
        if (sidecarFile == null || !RemovableStorageFs.exists(sidecarFile) || !RemovableStorageFs.canRead(sidecarFile)) return;
        synchronized (WRITE_LOCK) {   // mutually exclusive with mergePlace
            try {
                JSONObject root = readSidecar(sidecarFile);
                if (root == null) return;
                JSONObject geo = root.optJSONObject("geo");
                if (geo == null) return;            // no fix → not a backfill candidate anyway
                if (geo.has("place")) return;       // already tagged → don't mark (lost-update guard:
                                                    // a live mergePlace under the same lock may have
                                                    // just resolved it — never clobber that)
                // FIRST-seen anchor only: do NOT advance the timestamp on a re-mark.
                // The sweep ages an unresolved clip against THIS value, and the rewrite
                // resets the file mtime — if we re-stamped now() each retry, the clip
                // would never reach the 6h MAX_AGE ceiling and would recycle ~hourly
                // forever. Stamping once pins the age basis so retries stop after 6h.
                if (geo.has("placeUnresolvedAtMs")) return;   // already marked → nothing to write
                geo.put("placeUnresolvedAtMs", whenMs);
                writeSidecar(sidecarFile, root);
            } catch (Throwable t) {
                logger.warn("markPlaceUnresolved failed for "
                        + sidecarFile.getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Convenience: locate the sidecar sibling of {@code mp4File} and merge.
     */
    public static void mergePlaceForMp4(File mp4File, PlaceResult place) {
        if (mp4File == null) return;
        File parent = mp4File.getParentFile();
        if (parent == null) return;
        String name = mp4File.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File sidecar = new File(parent, base + ".json");
        mergePlace(sidecar, place);

        // Reflect the freshly-merged place_short / place_country into the
        // H2 index so the /api/recordings place chip row updates without
        // having to wait for FileObserver to notice the sidecar mtime
        // change. Index upsert re-parses the sidecar internally.
        try {
            com.overdrive.app.server.RecordingsIndex.getInstance().upsert(mp4File);
        } catch (Throwable t) {
            logger.warn("Index upsert after geo merge failed for "
                    + mp4File.getName() + ": " + t.getMessage());
        }
    }

    private static JSONObject readSidecar(File f) {
        try (java.io.Reader r = new java.io.InputStreamReader(RemovableStorageFs.openInput(f), java.nio.charset.StandardCharsets.UTF_8)) {
            long len = Math.min(RemovableStorageFs.length(f), MAX_BYTES);
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder((int) len);
            int total = 0;
            int n;
            while (total < len && (n = r.read(buf, 0, (int) Math.min(buf.length, len - total))) > 0) {
                sb.append(buf, 0, n);
                total += n;
            }
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            logger.warn("readSidecar failed for " + f.getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static void writeSidecar(File f, JSONObject root) throws Exception {
        // PER-WRITE-UNIQUE temp path. There are now TWO independent callers of
        // mergePlace on the same sidecar from different threads — the live
        // record-time resolveAsync callback (Geocoder pool) and the new
        // GeoBackfillSweep (daemon scheduler). A FIXED ".geo.tmp" path let writer
        // B's FileWriter truncate the shared tmp while writer A was mid-write,
        // so A's rename could publish a half-written (corrupt) sidecar. A unique
        // tmp per write means each renames its OWN fully-written file; rename(2)
        // atomicity then guarantees the live sidecar always holds complete JSON.
        // End the unique tmp name in ".json.tmp" so the existing orphan reaper
        // (StorageManager.sweepOrphanTempFiles matches the ".json.tmp" partial
        // suffix) and size-accounting BOTH see it — otherwise a SIGKILL/LMK in the
        // write→rename window would leave a never-reaped, never-counted orphan
        // (the unique ".geo.<id>.<nano>.tmp" suffix matched no category). Uniqueness
        // stays in the middle to keep the cross-thread no-truncation guarantee.
        // (f.getAbsolutePath() already ends in ".json", so this is
        // "<base>.json.geo.<id>.<nano>.json.tmp" → endsWith(".json.tmp").)
        File tmp = new File(f.getAbsolutePath() + ".geo."
                + Thread.currentThread().getId() + "." + System.nanoTime() + ".json.tmp");
        try {
            try (java.io.Writer w = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(tmp), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            try { RemovableStorageFs.setReadable(tmp, true, false); } catch (Throwable ignored) {}
            if (!RemovableStorageFs.renameTo(tmp, f)) {
                // Fall back to a direct rewrite if rename is denied (shouldn't
                // happen for a same-directory rename, but guard anyway).
                try (java.io.Writer w = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(f), java.nio.charset.StandardCharsets.UTF_8)) {
                    w.write(root.toString());
                }
            }
        } finally {
            // ALWAYS clean up the unique tmp unless it was consumed by a
            // successful rename. Because the tmp name is now per-write-unique
            // (threadId+nanoTime), a write-throw (disk-full / FUSE-EIO) would
            // otherwise leave a DISTINCT orphan every attempt — and the backfill
            // sweep re-tries the same untagged sidecar every 10 min, so under a
            // sustained write-error the orphans would accumulate unbounded (the
            // orphan reaper doesn't match the `.geo.<n>.tmp` suffix). Deleting
            // here bounds it to zero. (A successful renameTo already consumed tmp,
            // so this exists() check is a safe no-op in the happy path.)
            if (RemovableStorageFs.exists(tmp)) {
                try { RemovableStorageFs.delete(tmp); } catch (Throwable ignored) {}
            }
        }
        // Idle cleanup gate: this sidecar landed AFTER the mp4's save hook
        // fired (async place resolve / backfill can be minutes later), so the
        // bytes it added would otherwise stay invisible to the parked-tick
        // skip until the hourly backstop. Best-effort, never fails the write.
        try {
            com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty(f);
        } catch (Throwable ignored) {}
    }
}
