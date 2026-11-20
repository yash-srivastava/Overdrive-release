package com.overdrive.app.geo;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.SrtWriter;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight geo-only sidecar writer for non-sentry recordings
 * ({@code cam_*.mp4} dashcam, {@code proximity_*.mp4} proximity guard).
 *
 * <p>The sentry path uses {@link com.overdrive.app.surveillance.EventTimelineCollector}
 * which writes a rich v3 sidecar (events, actors, hero thumbnail, geo).
 * Dashcam and proximity recordings have no actor tracking and no event
 * timeline, but they DO have GPS at trigger/finalize and benefit from
 * the same place-tagging UX (recording row chip, push body suffix,
 * SRT location prefix).
 *
 * <p>This class emits a minimal v3-compatible sidecar containing ONLY
 * the {@code geo} block, plus a SRT companion with a t=0 location
 * prefix when the resolver has a synchronous hit. The sidecar shape is
 * a strict subset of the sentry sidecar so {@code RecordingScanner.enrichWithSidecar},
 * {@code RecordingsApiHandler.parseRecordingUncached}, and the web/Android
 * UIs all consume it via the exact same code paths.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   onSegmentClosed(mp4File, flow, startGeo)      // rotation listener path
 *   onRecordingClosed(mp4File, flow, startGeo)    // final-segment / stop path
 * </pre>
 * Both methods are non-blocking — file I/O happens on a single-thread
 * background executor (daemon, MIN_PRIORITY). The async place-resolve
 * pipeline is the same one sentry uses ({@link SidecarGeoUpdater}).
 *
 * <h3>Gating</h3>
 * Gated on {@code geocoding.<flow>.enabled} via {@link GeocodingResolver}'s
 * own per-call gate (no-op when disabled — does not write the sidecar at
 * all). The .mp4 still finalizes with whatever moov-atom geotag was
 * embedded by {@code MediaMuxer.setLocation} (which itself is gated on
 * the same flag at recorder-trigger time).
 */
public final class LocationSidecarWriter {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("LocationSidecar");

    private static volatile LocationSidecarWriter instance;

    /** Single-thread, daemon, MIN_PRIORITY — same discipline as the
     *  sentry timeline writer. Disk I/O off the recorder hot path. */
    private final ExecutorService executor;

    private LocationSidecarWriter() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LocationSidecar");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    public static LocationSidecarWriter getInstance() {
        if (instance == null) {
            synchronized (LocationSidecarWriter.class) {
                if (instance == null) instance = new LocationSidecarWriter();
            }
        }
        return instance;
    }

    /**
     * Submit a geo sidecar write for a closed recording (or rotated
     * segment). The {@code mp4File} must already be the renamed final
     * file (.mp4, not .tmp). {@code flow} is "recording" or
     * "surveillance" — used for the per-flow gate inside the resolver.
     * {@code startGeo} should be the snapshot captured at trigger time;
     * cold-start fallback is applied if it has no fix.
     */
    public void submit(final File mp4File, final String flow,
                       final GeoSnapshot startGeo) {
        if (mp4File == null) return;
        try {
            executor.execute(() -> writeSync(mp4File, flow, startGeo));
        } catch (Throwable t) {
            // Executor may be saturated/shutting down — log and drop.
            // The recording itself is unaffected; only the sidecar is
            // missing, which the UI handles gracefully.
            logger.warn("submit rejected for " + mp4File.getName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * Synchronous write. Public for tests + the path where the caller
     * is already on a background thread and wants to coalesce. Most
     * callers should use {@link #submit}.
     */
    void writeSync(File mp4File, String flow, GeoSnapshot startGeo) {
        try {
            // Cold-start fallback: if the recorder didn't capture a
            // start GPS (toggle was off at trigger, no fix yet, etc.),
            // poll once now. Same staleness gate as sentry — reject
            // fixes older than 5 min or loaded-from-cache.
            GeoSnapshot effectiveStart = startGeo;
            if (effectiveStart == null || !effectiveStart.hasFix()) {
                effectiveStart = freshFallbackOrNull();
            }

            // Composition layout (standard | dashcam), read at finalize —
            // a good proxy for the layout the clip was recorded under (the
            // setting is sticky). The video player reads this to pick the
            // per-camera zoom regions. Only stamped when non-default so
            // standard clips keep their exact pre-feature on-disk shape.
            String layout = readRecordingLayout();
            org.json.JSONObject lens = com.overdrive.app.surveillance.LensTag.forFile(mp4File);
            // A lens-tagged clip is a 2x2 grid whatever the layout setting says now.
            boolean dashcam = "dashcam".equals(layout) && lens == null;

            // Geo is writable only when the per-flow toggle is on AND we have
            // a usable fix. When it isn't, we used to skip the sidecar
            // entirely — but a dashcam clip still needs its layout stamped so
            // per-camera zoom works without geocoding. Standard clips with no
            // geo write nothing (identical to the pre-feature shape).
            boolean geoOk = isGeocodingEnabledForFlow(flow)
                    && effectiveStart != null && effectiveStart.hasFix();
            if (!geoOk) {
                if (dashcam || lens != null) {
                    JSONObject layoutOnly = new JSONObject();
                    layoutOnly.put("version", 3);
                    if (dashcam) layoutOnly.put("layout", layout);
                    if (lens != null) layoutOnly.put("lens", lens);
                    writeJsonAtomic(mp4File, layoutOnly);
                    try {
                        com.overdrive.app.server.RecordingsIndex.getInstance().upsert(mp4File);
                    } catch (Throwable t) {
                        logger.warn("Index upsert after layout sidecar write failed for "
                                + mp4File.getName() + ": " + t.getMessage());
                    }
                }
                return;
            }

            // ---- JSON sidecar (v3-compatible subset) ----
            JSONObject root = new JSONObject();
            root.put("version", 3);
            if (dashcam) root.put("layout", layout);
            if (lens != null) root.put("lens", lens);
            // durationMs unknown to this writer — the recorder owns it.
            // Readers tolerate absence; if a sentry reader tries
            // durationMs/0 they'll just get the default.
            JSONObject geo = new JSONObject();
            geo.put("start", effectiveStart.toJson());
            // For cam_*/proximity_* clips we don't have a "peak" event
            // (no actor tracking) — capture the recording-end fix as
            // {@code geo.end} so e.g. a long dashcam clip carries the
            // trip-end coordinate.
            GeoSnapshot endGeo = GeoSnapshot.capture(0L);
            if (endGeo.hasFix()) {
                geo.put("end", endGeo.toJson());
            }
            root.put("geo", geo);

            writeJsonAtomic(mp4File, root);

            // Re-upsert the index so the just-written geo.start (and any
            // geo.end) lands in /api/recordings without waiting on the
            // FileObserver to spot the sidecar mtime change. The
            // SidecarGeoUpdater's later mergePlace fires its own upsert.
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance().upsert(mp4File);
            } catch (Throwable t) {
                logger.warn("Index upsert after geo sidecar write failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            }

            // ---- SRT prefix (location label at t=0 only) ----
            // Mirrors sentry: cache-only resolve, frozen-at-write. A
            // miss leaves the SRT entirely absent for this flow (we
            // don't emit a "Recording started" entry alone — sentry
            // does because it has actors; dashcam doesn't, and a
            // single-line SRT with just that key reads as noise).
            PlaceResult cachedPlace = null;
            try {
                cachedPlace = GeocodingResolver.getInstance().resolveCachedOnly(
                        effectiveStart.lat, effectiveStart.lng, flow);
                writeSrtIfCached(mp4File, cachedPlace);
            } catch (Throwable t) {
                logger.warn("SRT prefix write failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            }

            // ---- Async place resolve + sidecar merge ----
            // Same pipeline sentry uses. Cache hits land synchronously
            // and we re-merge inline; misses kick off the resolver
            // worker which calls SidecarGeoUpdater.mergePlaceForMp4
            // when Tier-B/C completes.
            scheduleResolve(mp4File, flow, effectiveStart, cachedPlace);
        } catch (Throwable t) {
            logger.warn("writeSync failed for " + mp4File.getName()
                    + ": " + t.getMessage());
        }
    }

    private static boolean isGeocodingEnabledForFlow(String flow) {
        try {
            return com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow(flow == null ? "recording" : flow);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Current recording composition layout from unified config
     * ({@code recording.recordingLayout}). "dashcam" or "standard"
     * (default). Read at sidecar-write time as a proxy for the layout the
     * clip was recorded under — the setting is sticky, so finalize-time is a
     * close-enough approximation without threading the value through the
     * recorder.
     */
    private static String readRecordingLayout() {
        try {
            String v = com.overdrive.app.config.UnifiedConfigManager
                    .getRecording().optString("recordingLayout", "standard");
            return "dashcam".equals(v) ? "dashcam" : "standard";
        } catch (Throwable t) {
            return "standard";
        }
    }

    /**
     * Cold-start fallback that mirrors {@code EventTimelineCollector}'s
     * gate exactly: require a fresh fix (≤ 5 min old) AND a non-cached
     * origin. Otherwise we'd risk stamping a clip with yesterday's
     * coordinates loaded from the GPS persisted-cache.
     */
    private static GeoSnapshot freshFallbackOrNull() {
        try {
            GeoSnapshot late = GeoSnapshot.capture(0L);
            if (!late.hasFix()) return null;
            boolean fromCache;
            try {
                fromCache = com.overdrive.app.monitor.GpsMonitor.getInstance()
                        .getLocationJson().optBoolean("loadedFromCache", false);
            } catch (Throwable ignored) {
                fromCache = true;  // fail closed
            }
            final long MAX_FALLBACK_AGE_MS = 5L * 60L * 1000L;
            if (!fromCache && late.ageMs >= 0L && late.ageMs <= MAX_FALLBACK_AGE_MS) {
                return late;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Atomic write: temp file + rename. Mirrors the discipline used
     * by EventTimelineCollector.writeJsonSidecar so a crash mid-write
     * never leaves a half-written .json next to a .mp4.
     */
    private static void writeJsonAtomic(File mp4File, JSONObject root) {
        try {
            String name = mp4File.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File parent = mp4File.getParentFile();
            if (parent == null) return;
            File jsonFile = new File(parent, base + ".json");
            File tmpFile = new File(jsonFile.getAbsolutePath() + ".tmp");

            try (java.io.Writer fw = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(tmpFile), java.nio.charset.StandardCharsets.UTF_8)) {
                fw.write(root.toString());
            }

            if (!RemovableStorageFs.renameTo(tmpFile, jsonFile)) {
                // Same fallback pattern as sentry: direct rewrite when
                // rename fails (cross-FS / SD-card quirks). Loses
                // atomicity but keeps the data on disk.
                try (java.io.Writer fw = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(jsonFile), java.nio.charset.StandardCharsets.UTF_8)) {
                    fw.write(root.toString());
                }
                try { RemovableStorageFs.delete(tmpFile); } catch (Throwable ignored) {}
            }
            try { RemovableStorageFs.setReadable(jsonFile, true, false); } catch (Throwable ignored) {}
            // Idle cleanup gate: sidecars land AFTER the mp4's save hook fired,
            // so without this bump their bytes stay invisible to the parked-tick
            // skip until the hourly backstop. Best-effort.
            try {
                com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty(jsonFile);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.warn("writeJsonAtomic failed for " + mp4File.getName()
                    + ": " + t.getMessage());
        }
    }

    private static void writeSrtIfCached(File mp4File, PlaceResult cachedPlace) {
        if (cachedPlace == null) return;
        String label = cachedPlace.shortLabel();
        if (label == null || label.isEmpty()) return;
        SrtWriter srt = new SrtWriter();
        srt.addEvent(0L, SrtWriter.K_LOCATION_PREFIX, label);
        srt.write(mp4File);
        // Same rationale as writeJsonAtomic's bump: post-finalization bytes.
        try {
            com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty(mp4File);
        } catch (Throwable ignored) {}
    }

    /**
     * Try synchronous cache hit; otherwise schedule async resolve. The
     * resolver's own work is gated per-flow by `geocoding.<flow>.allowOnline`
     * for Tier-C — we just hand it the flow.
     */
    private static void scheduleResolve(File mp4File, String flow,
                                        GeoSnapshot startGeo, PlaceResult cachedPlace) {
        if (cachedPlace != null) {
            SidecarGeoUpdater.mergePlaceForMp4(mp4File, cachedPlace);
            return;
        }
        GeocodingResolver.getInstance().resolveAsync(startGeo.lat, startGeo.lng, flow, place -> {
            if (place == null) return;
            SidecarGeoUpdater.mergePlaceForMp4(mp4File, place);
        });
    }
}
