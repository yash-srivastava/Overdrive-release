package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SOTA Event Timeline Collector — With Semantic Pre-Record Ring Buffer
 *
 * Architecture: Dual-buffer inline coalescing.
 *
 * The collector maintains TWO buffers:
 *
 * 1. PRE-TRIGGER RING BUFFER (always active):
 *    A small circular buffer that continuously ingests motion and AI events,
 *    keeping the last ~15 seconds of semantic data. This mirrors the H.264
 *    circular buffer that keeps the last 15 seconds of video.
 *
 * 2. ACTIVE SPAN BUFFER (only during recording):
 *    The main span array that stores committed spans for the current event.
 *    When startCollecting(preRecordMs) is called, the pre-trigger ring buffer
 *    is flushed into this array with timestamps shifted to align with the
 *    video's pre-record window.
 *
 * This ensures the JSON sidecar captures YOLO detections and tracking data
 * that occurred during the approach phase BEFORE the recording trigger.
 *
 * Thread safety:
 * - All mutable state access is synchronized
 * - File I/O runs async on a dedicated MIN_PRIORITY thread
 *
 * Memory: ~360 KB (active) + ~22 KB (pre-trigger ring) = ~382 KB total
 */
public class EventTimelineCollector {

    private static final DaemonLogger logger = DaemonLogger.getInstance("EventTimeline");

    private static final long COALESCE_MS = 2000;

    // Type constants (byte values for compact storage). Ordered by span
    // priority: when several classes coalesce into one span the HIGHEST value
    // wins the marker/type (see onAiDetection's `type > bestType`). ANIMAL sits
    // just above MOTION — the lowest-priority *recognized* class — so a
    // person/car/bike sharing the window still names the span (a dog next to a
    // person reads "person", not "animal"), while an animal-only window reads
    // "animal" instead of a bare "motion". Values are private and never
    // persisted as raw bytes, so this renumbering is internal-only.
    private static final byte TYPE_MOTION = 0;
    private static final byte TYPE_ANIMAL = 1;
    private static final byte TYPE_BIKE   = 2;
    private static final byte TYPE_CAR    = 3;
    private static final byte TYPE_PERSON = 4;
    private static final String[] TYPE_NAMES = {"motion", "animal", "bike", "car", "person"};

    // ========================================================================
    // PRE-TRIGGER SEMANTIC RING BUFFER (always active)
    // ========================================================================
    // Keeps the last ~15 seconds of semantic events in a circular buffer.
    // At ~0.5 spans/sec coalescing rate, 64 slots = ~128 seconds capacity.
    // We only need ~15 seconds, so 64 is generous.
    private static final int PRE_RING_CAPACITY = 64;

    private final long[]  preRingStarts = new long[PRE_RING_CAPACITY];
    private final long[]  preRingEnds   = new long[PRE_RING_CAPACITY];
    private final byte[]  preRingTypes  = new byte[PRE_RING_CAPACITY];
    private final float[] preRingConfs  = new float[PRE_RING_CAPACITY];
    private final byte[]  preRingCounts = new byte[PRE_RING_CAPACITY];
    private final byte[]  preRingCams   = new byte[PRE_RING_CAPACITY];
    private int preRingHead = 0;   // Next write position
    private int preRingCount = 0;  // Number of valid entries (max PRE_RING_CAPACITY)

    // In-flight span for the pre-trigger path (separate from the active path)
    private long   preInflightStart = -1;
    private long   preInflightEnd   = 0;
    private byte   preInflightType  = TYPE_MOTION;
    private float  preInflightConf  = 0;
    private byte   preInflightCount = 0;
    private byte   preInflightCams  = 0;

    // Wall-clock reference for pre-trigger timestamps (absolute ms)
    // Pre-trigger events use absolute timestamps; they get shifted when flushed.

    // ========================================================================
    // ACTIVE SPAN BUFFER (only during recording)
    // ========================================================================
    private static final int SPAN_CAPACITY = 16384;

    private final long[]  spanStarts      = new long[SPAN_CAPACITY];
    private final long[]  spanEnds        = new long[SPAN_CAPACITY];
    private final byte[]  spanTypes       = new byte[SPAN_CAPACITY];
    private final float[] spanConfidences = new float[SPAN_CAPACITY];
    private final byte[]  spanCounts      = new byte[SPAN_CAPACITY];
    private final byte[]  spanCameras     = new byte[SPAN_CAPACITY];
    private int spanCount = 0;

    // Accepted dispatches for this recording segment: Cloud=1, Screen=2.
    private int deterrentFlags = 0;
    // In-flight span for the active path
    private long   inflightStart = -1;
    private long   inflightEnd   = 0;
    private byte   inflightType  = TYPE_MOTION;
    private float  inflightConf  = 0;
    private byte   inflightCount = 0;
    private byte   inflightCameras = 0;

    // State
    private long recordingStartTimeMs = 0;
    private volatile boolean collecting = false;
    // COLLECTION GENERATION (audit R11-4 / ExtD-5). Bumped by every
    // startCollecting* call. A stopAndWrite carrying an expected generation
    // no-ops when a NEWER collection has started — without this, an old
    // event's stop tail racing a successor's startCollecting could find
    // collecting==true (the successor's fresh session), flip it false
    // (killing the successor's timeline collection for its whole event) and
    // write the OLD event's sidecar from the successor's just-reset state
    // (zero spans, wrong origin). With the token the stale stop no-ops: the
    // old sidecar is skipped (its span data was already destroyed by the
    // successor's reset — there is nothing correct left to write) and the
    // successor's collection survives intact.
    private int collectionGen = 0;

    /** Latch accepted deterrent dispatches only while a clip is collecting. */
    public synchronized void markDeterrentTriggered(boolean cloud, boolean screen) {
        if (!collecting) return;
        if (cloud) deterrentFlags |= 1;
        if (screen) deterrentFlags |= 2;
    }
    /**
     * Wall-clock timestamp (ms) of the start of the current recording (with
     * pre-record offset already applied). Returns 0 when not collecting.
     * Used by ActorTracker to compute Actor-relative timestamps.
     */
    public long getRecordingStartTimeMs() {
        return recordingStartTimeMs;
    }

    public boolean isCollecting() {
        return collecting;
    }

    // Async writer
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TimelineWriter");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * Block until every queued JSON/SRT write has completed (or the timeout
     * elapses). writeExecutor is a single-thread FIFO, so a submitted no-op that
     * completes implies all previously-queued writes finished. Used by the
     * discard path so a multi-segment empty-bright-motion discard deletes the
     * sidecars AFTER their async write lands (otherwise a queued earlier-segment
     * .json/.srt would be written just after deleteEventSidecars ran, orphaning
     * it). Bounded; returns false on timeout/failure (caller proceeds anyway —
     * deleteEventSidecars is idempotent).
     */
    public boolean awaitWrites(long timeoutMs) {
        try {
            java.util.concurrent.Future<?> f = writeExecutor.submit(() -> {});
            f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable t) {
            logger.warn("awaitWrites drain timed out/failed: " + t.getMessage());
            return false;
        }
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    public synchronized int startCollecting() {
        return startCollecting(0);
    }

    /**
     * Start collecting with a pre-record offset.
     * Flushes the pre-trigger ring buffer into the active span array,
     * shifting timestamps to align with the video's pre-record window.
     *
     * @return the new collection generation (audit R11-4) — pass it to the
     *         generation-checked {@code stopAndWrite} overload so a stale
     *         stop cannot corrupt a successor's collection.
     */
    public synchronized int startCollecting(long preRecordMs) {
        return startCollecting(preRecordMs, /*flushPreRing=*/true);
        deterrentFlags = 0;
    }

    /**
     * Segment-rotation entry point: start collecting WITHOUT replaying the
     * pre-trigger ring buffer. The pre-ring captured spans from before the
     * original event began; replaying them at every rotation would seed
     * each subsequent segment with stale "marker at relMs=0" entries that
     * already appeared in segment 1's sidecar. Used by SurveillanceEngineGpu
     * when rotateSegment fires.
     */
    public synchronized int startCollectingNoPreRing() {
        return startCollecting(0L, /*flushPreRing=*/false);
    }

    private synchronized int startCollecting(long preRecordMs, boolean flushPreRing) {
        collectionGen++; // audit R11-4 / ExtD-5 — invalidates stale stops
        spanCount = 0;
        inflightStart = -1;

        // Timeline origin: the start of the video (preRecordMs before now)
        recordingStartTimeMs = System.currentTimeMillis() - preRecordMs;

        int flushed = 0;
        if (flushPreRing) {
            // Flush pre-trigger ring buffer: copy spans that fall within the
            // pre-record window into the active span array.
            // Pre-trigger spans use absolute wall-clock timestamps.
            // We need to convert them to relative timestamps (ms since recordingStartTimeMs).
            commitPreInflight();  // Commit any in-flight pre-trigger span first

            long windowStart = recordingStartTimeMs;  // Earliest timestamp to include

            // Read ring buffer in chronological order
            int readStart = (preRingCount < PRE_RING_CAPACITY) ? 0 : preRingHead;
            for (int i = 0; i < preRingCount; i++) {
                int idx = (readStart + i) % PRE_RING_CAPACITY;

                // Only include spans that overlap with the pre-record window
                if (preRingEnds[idx] < windowStart) continue;

                long relStart = Math.max(0, preRingStarts[idx] - recordingStartTimeMs);
                long relEnd = preRingEnds[idx] - recordingStartTimeMs;

                if (spanCount < SPAN_CAPACITY) {
                    spanStarts[spanCount]      = relStart;
                    spanEnds[spanCount]        = relEnd;
                    spanTypes[spanCount]       = preRingTypes[idx];
                    spanConfidences[spanCount] = preRingConfs[idx];
                    spanCounts[spanCount]      = preRingCounts[idx];
                    spanCameras[spanCount]     = preRingCams[idx];
                    spanCount++;
                    flushed++;
                }
            }
        } else {
            // Rotation path: drop any in-flight pre-ring span so it won't
            // spuriously appear in a future event's pre-roll.
            preInflightStart = -1;
        }

        collecting = true;
        logger.info("Timeline collection started (preRecord=" + preRecordMs +
                "ms, origin shifted, flushed " + flushed + " pre-trigger spans"
                + (flushPreRing ? "" : ", preRing skipped") + ", gen=" + collectionGen + ")");
        return collectionGen;
    }

    /**
     * Stop collecting and write the JSON sidecar ASYNCHRONOUSLY.
     */
    public synchronized void stopAndWrite(File mp4File) {
        stopAndWrite(mp4File, null, null);
    }

    /**
     * Stop collecting and write a v3 JSON sidecar with Actor records + hero
     * thumbnail reference. Backwards-compat: the v3 sidecar is a strict superset
     * of v2 (all v2 fields preserved), so old readers continue to work.
     *
     * @param mp4File          The recording file
     * @param actors           Final actor snapshot from ActorTracker (may be null/empty)
     * @param heroThumbnail    Filename of the hero JPEG, written by ThumbnailBuffer.
     *                         Just the basename (e.g. "event_xxx.jpg"), or null.
     */
    public synchronized void stopAndWrite(File mp4File,
                                          java.util.List<Actor> actors,
                                          String heroThumbnail) {
        stopAndWrite(mp4File, actors, heroThumbnail, null, null, null);
    }

    /**
     * Geo-aware overload — adds {@code geo.start} / {@code geo.peak} /
     * {@code geo.end} fields to the v3 sidecar. Each snapshot may be null;
     * the JSON writer omits absent ones rather than emitting empty objects.
     *
     * <p>This overload is preferred when the recorder side has captured
     * locations (HardwareEventRecorderGpu's startGeo* fields). Called from
     * SurveillanceEngineGpu.scheduleSegmentMetadataFlush via the same
     * writeExecutor as the legacy 3-arg form, so ordering with hero +
     * per-actor JPEG writes is unchanged.
     */
    public synchronized void stopAndWrite(File mp4File,
                                          java.util.List<Actor> actors,
                                          String heroThumbnail,
                                          com.overdrive.app.geo.GeoSnapshot startGeo,
                                          com.overdrive.app.geo.GeoSnapshot peakGeo,
                                          com.overdrive.app.geo.GeoSnapshot endGeo) {
        // Legacy (un-tokened) form: no generation check. Kept for callers
        // that cannot know their generation; new code should use the
        // generation-checked overload (audit R11-4).
        stopAndWrite(mp4File, actors, heroThumbnail, startGeo, peakGeo, endGeo,
                /* expectedGen = */ -1);
    }

    /**
        final int acceptedDeterrents = deterrentFlags;
     * Generation-checked stop (audit R11-4 / ExtD-5). No-ops — leaving the
     * live collection UNTOUCHED — when {@code expectedGen} is non-negative
     * and a newer {@code startCollecting*} has run since the caller's
     * collection began. This is what makes an old event's stop tail safe
     * against a successor event that has already restarted collection: the
     * stale stop can neither flip the successor's {@code collecting} gate
     * nor consume its just-reset span state. {@code expectedGen < 0} skips
     * the check (legacy behavior).
     */
    public synchronized void stopAndWrite(File mp4File,
                                          java.util.List<Actor> actors,
                                          String heroThumbnail,
                                          com.overdrive.app.geo.GeoSnapshot startGeo,
                                          com.overdrive.app.geo.GeoSnapshot peakGeo,
                                          com.overdrive.app.geo.GeoSnapshot endGeo,
                                          int expectedGen) {
        if (expectedGen >= 0 && expectedGen != collectionGen) {
            logger.info("stopAndWrite skipped — stale collection gen "
                    + expectedGen + " (current " + collectionGen + ") for "
                    + (mp4File != null ? mp4File.getName() : "null"));
            return;
        }
        if (!collecting) return;
        collecting = false;

        commitInflight();

        final int count = spanCount;
        if (count == 0 && (actors == null || actors.isEmpty()) && acceptedDeterrents == 0) {
            logger.info("No events collected, skipping sidecar write");
            return;
        }

        final long[]  starts = new long[count];
        final long[]  ends   = new long[count];
        final byte[]  types  = new byte[count];
        final float[] confs  = new float[count];
        final byte[]  counts = new byte[count];
        final byte[]  cams   = new byte[count];

        System.arraycopy(spanStarts,      0, starts, 0, count);
        System.arraycopy(spanEnds,        0, ends,   0, count);
        System.arraycopy(spanTypes,       0, types,  0, count);
        System.arraycopy(spanConfidences, 0, confs,  0, count);
        System.arraycopy(spanCounts,      0, counts, 0, count);
        System.arraycopy(spanCameras,     0, cams,   0, count);

        final long durationMs = System.currentTimeMillis() - recordingStartTimeMs;
        // Snapshot actors so the writer thread sees an immutable copy
        final java.util.List<Actor> actorsCopy =
                (actors == null || actors.isEmpty())
                        ? java.util.Collections.<Actor>emptyList()
                        : new java.util.ArrayList<>(actors);
        final String heroThumb = heroThumbnail;
        // End snapshot wasn't passed by older callers; capture it now if the
        // overload was invoked with a null endGeo. Done on this thread (the
        // dispatch thread) instead of inside the executor so the GPS reading
        // matches the time the .mp4 actually finalized.
        //
        // Cold-start fallback: if the recorder didn't capture a startGeo
        // (no GPS fix at trigger time, e.g. parking-garage exit, fresh
        // ACC-on, or a sub-2-min event that ended before the rotation
        // re-capture path could fire), poll the live GpsMonitor here.
        //
        // Staleness gate: GpsMonitor.hasLocation() returns true even for
        // fixes loaded from a stale cache (last drive's coordinates),
        // which would mis-tag an indoor-parking surveillance clip with
        // home address from yesterday. Reject any fix whose age exceeds
        // 5 minutes. The recorder-captured snapshot already passed an
        // implicit freshness gate (it was current at trigger time) so
        // this only applies to the fallback path.
        //
        // relMs semantics: pass 0 (start-of-clip) so the JSON's geo.start
        // block carries `tMs: 0`, matching the documented contract on
        // GeoSnapshot. Using durationMs would emit an end-of-clip stamp
        // that no reader currently consumes but a future one might.
        com.overdrive.app.geo.GeoSnapshot resolvedStart = startGeo;
        if (resolvedStart == null || !resolvedStart.hasFix()) {
            com.overdrive.app.geo.GeoSnapshot late =
                    com.overdrive.app.geo.GeoSnapshot.capture(0L);
            // Adopt only if a fix exists AND it's recent enough to be
            // attributable to this recording's geographic neighbourhood.
            // Require BOTH an explicit fresh age (no `ageMs < 0` permissive
            // branch — that would let a cache-loaded fix with a corrupt
            // lastUpdate slip through) AND a non-cached origin so that a
            // freshly-booted daemon with yesterday's persisted fix doesn't
            // mis-tag indoor-parking surveillance with home address.
            final long MAX_FALLBACK_AGE_MS = 5L * 60L * 1000L;
            boolean fromCache = false;
            try {
                fromCache = com.overdrive.app.monitor.GpsMonitor.getInstance()
                        .getLocationJson().optBoolean("loadedFromCache", false);
            } catch (Throwable ignored) {
                fromCache = true;  // Fail closed — assume cached if probe failed.
            }
            if (late.hasFix()
                    && !fromCache
                    && late.ageMs >= 0L
                    && late.ageMs <= MAX_FALLBACK_AGE_MS) {
                resolvedStart = late;
            }
        }
        final com.overdrive.app.geo.GeoSnapshot startG = resolvedStart;
        final com.overdrive.app.geo.GeoSnapshot peakG  = peakGeo;
        final com.overdrive.app.geo.GeoSnapshot endG   = (endGeo != null)
                ? endGeo
                : com.overdrive.app.geo.GeoSnapshot.capture(durationMs);
        // Parking Intelligence: capture the OPEN session id HERE on the
        // dispatch thread (null when the feature is off / nothing open). The
        // writer runs asynchronously and could otherwise observe a session
        // that opened after this clip finalized.
        final String parkingSessionId = com.overdrive.app.parking.ParkingHooks.currentSessionId();

        writeExecutor.execute(() -> {
            writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cams, count,
                    durationMs, actorsCopy, heroThumb, startG, peakG, endG,
                    parkingSessionId, acceptedDeterrents);
            // SRT subtitle sidecar — localized prose so VLC / video.js / ExoPlayer
            // can show "Person detected close range" / "Charging started · 4.3 kW"
            // without re-encoding the burned-in English overlay. Wrapped so an
            // SRT failure can never poison the JSON write above (which the
            // recordings UI depends on).
            try {
                writeSrtSidecar(mp4File, starts, ends, types, count, actorsCopy, startG);
            } catch (Throwable t) {
                logger.warn("SRT sidecar write failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            }

            // Async place-name resolve. Cache hits land synchronously and we
            // can write them with the JSON; misses kick off a background
            // resolve that re-merges the sidecar when it completes. The whole
            // step is gated by the geocoding feature flag.
            try {
                maybeResolveAndMergePlace(mp4File, startG);
            } catch (Throwable t) {
                logger.warn("Place resolve dispatch failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            }

            // Idle cleanup gate: this batch (JSON + SRT) runs async and can
            // land AFTER onSurveillanceFileSaved's bump and after an idle
            // pass completed — without a bump of its own the bytes stay
            // invisible to the parked-tick skip until the hourly backstop.
            // One bump per batch. Best-effort. (Async place merges bump via
            // SidecarGeoUpdater.writeSidecar.)
            try {
                com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty(mp4File);
            } catch (Throwable ignored) {}

            // SafeKeep: the event's sidecars now exist, so the clip group can be queued as a unit.
            try {
                com.overdrive.app.safekeep.SafeKeep.onEventSidecarsWritten(mp4File);
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Kick off the place-name resolve for {@code startGeo}. The resolver
     * fast-paths SafeLocation + cache, so the common case is a synchronous
     * write; otherwise the JSON sidecar gets a {@code geo.place} field
     * appended a second or two later when Tier B / C completes.
     *
     * <p>{@code flow} is derived from the mp4 filename: {@code event_*}
     * → {@code "surveillance"}, everything else → {@code "recording"}.
     * The resolver applies the matching per-flow {@code allowOnline} gate
     * for Tier C.
     */
    private static void maybeResolveAndMergePlace(File mp4File,
                                                  com.overdrive.app.geo.GeoSnapshot startGeo) {
        if (mp4File == null || startGeo == null || !startGeo.hasFix()) return;
        String flow = inferFlow(mp4File.getName());
        com.overdrive.app.geo.GeocodingResolver resolver =
                com.overdrive.app.geo.GeocodingResolver.getInstance();
        // Try cached-only first so the JSON we just wrote is updated in
        // place even before the resolver thread schedules the async part.
        com.overdrive.app.geo.PlaceResult fast =
                resolver.resolveCachedOnly(startGeo.lat, startGeo.lng, flow);
        if (fast != null) {
            com.overdrive.app.geo.SidecarGeoUpdater.mergePlaceForMp4(mp4File, fast);
            return;
        }
        resolver.resolveAsync(startGeo.lat, startGeo.lng, flow, place -> {
            if (place == null) return;
            com.overdrive.app.geo.SidecarGeoUpdater.mergePlaceForMp4(mp4File, place);
        });
    }

    private static String inferFlow(String filename) {
        if (filename == null) return "recording";
        return filename.startsWith("event_") ? "surveillance" : "recording";
    }

    /**
     * Convert the snapshotted spans + actor list into a localized SRT
     * sidecar next to {@code mp4File}. Runs on the same low-priority
     * writer thread that emits the JSON sidecar, so it never blocks the
     * encoder drainer.
     *
     * <p>Mapping rules (kept conservative — better to under-emit than to
     * spam every motion blip into a subtitle line):
     * <ul>
     *   <li>Each non-static actor produces ONE entry at its first-seen
     *       offset, keyed by class group (PERSON / VEHICLE / BIKE / ANIMAL)
     *       and tightened to {@code srt.person_close} when peakProximity is
     *       VERY_CLOSE or CLOSE.</li>
     *   <li>Spans of type {@code motion} that don't overlap any actor
     *       produce a single {@code srt.motion_started} entry at the
     *       span's start. Avoids duplicating actor-driven entries.</li>
     *   <li>Proximity-tier alerts are emitted from the actor's
     *       peakSeverityRelMs when severity is CRITICAL/ALERT.</li>
     *   <li>"Recording started" is always emitted at offset 0.</li>
     * </ul>
     */
    private void writeSrtSidecar(File mp4File,
                                 long[] starts, long[] ends, byte[] types,
                                 int spanCount,
                                 java.util.List<Actor> actors,
                                 com.overdrive.app.geo.GeoSnapshot startGeo) {
        SrtWriter srt = new SrtWriter();
        srt.addEvent(0L, SrtWriter.K_RECORDING_STARTED);

        // Place-name SRT prefix — frozen at write-time. If the cache or
        // SafeLocation overlay has a hit for the start coords we add a
        // second t=0 entry carrying the short label. Online resolution is
        // deliberately NOT awaited here: the SRT must be deterministic and
        // synchronous next to the .mp4. A miss leaves the SRT exactly as it
        // was before — no degraded user experience.
        try {
            if (startGeo != null && startGeo.hasFix()) {
                String flow = inferFlow(mp4File != null ? mp4File.getName() : null);
                com.overdrive.app.geo.PlaceResult cached =
                        com.overdrive.app.geo.GeocodingResolver.getInstance()
                                .resolveCachedOnly(startGeo.lat, startGeo.lng, flow);
                if (cached != null) {
                    String label = cached.shortLabel();
                    if (label != null && !label.isEmpty()) {
                        srt.addEvent(0L, SrtWriter.K_LOCATION_PREFIX, label);
                    }
                }
            }
        } catch (Throwable ignored) {
            // SRT prefix is decorative; failures must never block sidecar write.
        }

        // Actor-driven entries (preferred — they carry class + proximity)
        if (actors != null) {
            for (Actor a : actors) {
                // Suppressed actors emit no SRT line: timeline-static (parked car;
                // isStaticForTimeline == isStatic for PERSON so a loiterer keeps its
                // entry) OR the low-confidence FAR NOTICE misclassification profile
                // (a parked motorcycle read as "person · far" @0.44). The latter
                // mirrors the headline + hero gates so all three views agree — a
                // genuine far actor escalates above NOTICE or is seen with solid
                // confidence, so real events still get their SRT line. Single
                // source of truth so overlapsAnyActor stays in lockstep (else a
                // dropped actor would also suppress the generic-motion fallback,
                // leaving the window with no subtitle at all).
                if (isSuppressedFromSrt(a)) continue;
                long offset = a.firstSeenRelMs >= 0 ? a.firstSeenRelMs : 0L;
                String key = null;
                switch (a.classGroup) {
                    case PERSON:
                        key = (a.peakProximity == Actor.Proximity.VERY_CLOSE
                                || a.peakProximity == Actor.Proximity.CLOSE)
                                ? SrtWriter.K_PERSON_CLOSE
                                : SrtWriter.K_PERSON_DETECTED;
                        break;
                    case VEHICLE:
                        key = SrtWriter.K_VEHICLE_DETECTED;
                        break;
                    case BIKE:
                        key = SrtWriter.K_BIKE_DETECTED;
                        break;
                    case ANIMAL:
                        key = SrtWriter.K_ANIMAL_DETECTED;
                        break;
                    default:
                        // UNKNOWN: no class-specific key; fall through to the
                        // generic-motion span fallback below if applicable.
                        break;
                }
                if (key != null) {
                    srt.addEvent(offset, key);
                }

                // Severity → proximity-band alert. peakSeverityRelMs may be -1
                // when the peak fell outside this segment's window (renormalized
                // upstream by SurveillanceEngineGpu#flushSegmentMetadata).
                if (a.peakSeverityRelMs >= 0) {
                    if (a.peakSeverity == Actor.Severity.CRITICAL) {
                        srt.addEvent(a.peakSeverityRelMs, SrtWriter.K_PROXIMITY_RED);
                    } else if (a.peakSeverity == Actor.Severity.ALERT) {
                        srt.addEvent(a.peakSeverityRelMs, SrtWriter.K_PROXIMITY_YELLOW);
                    }
                }
            }
        }

        // Plain-motion spans (TYPE_MOTION) that have no actor to anchor on.
        // Without a class signal we fall back to the generic motion key.
        for (int i = 0; i < spanCount; i++) {
            if (types[i] != TYPE_MOTION) continue;
            // Skip motion spans that overlap a known actor — the actor entry
            // already covers them and we don't want double-up subtitles.
            if (overlapsAnyActor(starts[i], ends[i], actors)) continue;
            srt.addEvent(starts[i], SrtWriter.K_MOTION_STARTED);
        }

        srt.write(mp4File);
    }

    /**
     * True if this actor is suppressed from the SRT actor loop (no own subtitle
     * entry). Such an actor must ALSO be skipped by {@link #overlapsAnyActor} so
     * it doesn't suppress the generic-motion fallback and leave a window with no
     * subtitle at all. Single source of truth for "did this actor emit an SRT
     * line": timeline-static (parked car / loiterer-exempt) OR the low-confidence
     * FAR NOTICE misclassification profile (mirrors the headline + hero gates).
     */
    private static boolean isSuppressedFromSrt(Actor a) {
        if (a.isStaticForTimeline) return true;
        // The low-conf FAR NOTICE misclassification profile. SUMMARY scope, so
        // PERSON is exempt (a real far still person keeps its SRT line per the
        // hard invariant); only non-person FPs (car/bike/animal) drop. Canonical
        // predicate on Actor so SRT, headline counts, and the events-page chip
        // never diverge.
        return Actor.suppressFromSummary(a);
    }

    private static boolean overlapsAnyActor(long spanStart, long spanEnd,
                                            java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return false;
        for (Actor a : actors) {
            // Only actors that ACTUALLY EMITTED an SRT entry count as "covering"
            // this motion span. A suppressed actor (timeline-static, or the
            // low-conf FAR NOTICE FP) is skipped at the actor loop (its own entry
            // is dropped), so it must NOT also suppress the generic-motion
            // fallback — otherwise a window with a dropped actor gets NO subtitle
            // at all. Skipping it here lets the K_MOTION_STARTED fallback fire.
            if (isSuppressedFromSrt(a)) continue;
            if (a.firstSeenRelMs < 0 || a.lastSeenRelMs < 0) continue;
            if (spanStart <= a.lastSeenRelMs && spanEnd >= a.firstSeenRelMs) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the [spanStart, spanEnd] window overlaps a timeline-static
     * NON-PERSON actor (a parked car / static object). Used to drop CAR/BIKE
     * span markers that are really just a parked vehicle. PERSON actors are
     * intentionally excluded — a person span must always survive (a loiterer is
     * the threat, and the span path carries no way to re-confirm a person later).
     * Falls through to false (keep the span) when no actor context is available,
     * so the gate is fail-open: a real event is never silently dropped for lack
     * of actor data.
     */
    private static boolean overlapsStaticActor(long spanStart, long spanEnd,
                                               int spanCamMask,
                                               java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return false;
        // The span path carries no actor identity — a CAR/BIKE span is one
        // coalesced inflight chain that may overlap several actors in time. Drop
        // it ONLY when EVERY overlapping non-person actor is timeline-static: if a
        // genuinely-moving non-person (a passing car/bike) coexists in the window,
        // the merged span may belong to IT, so keep the marker (fail-open). This
        // prevents a parked car — whose lifespan spans ~the whole event — from
        // silently deleting a real mover's events[] marker / undercounting
        // stats.car when both are present (the audit edge case). PERSON actors are
        // ignored (never drive a vehicle-span drop).
        boolean hasOverlappingStatic = false;
        for (Actor a : actors) {
            if (a.classGroup == Actor.ClassGroup.PERSON) continue;
            // A non-person classified during PRE-ROLL keeps firstSeenRelMs==-1
            // (it was present before recordingStart). Treat that as 0 so it still
            // overlaps the segment window — otherwise the old `firstSeenRelMs < 0
            // continue` skipped a real moving car and let an overlapping parked
            // car drop its marker. lastSeenRelMs<0 means no recording-relative
            // window at all → genuinely skip.
            if (a.lastSeenRelMs < 0) continue;
            long fRel = a.firstSeenRelMs >= 0 ? a.firstSeenRelMs : 0L;
            if (!(spanStart <= a.lastSeenRelMs && spanEnd >= fRel)) continue;
            if (!a.isStaticForTimeline) {
                // A real moving/approaching non-person coexists — never drop
                // (fail-open on the mover), regardless of its latched rel times.
                return false;
            }
            // Static (parked) actor. Drop the span ONLY when it is the parked
            // car's OWN marker: STRICTLY CONTAINED within the actor's lifespan AND
            // covering most of it (>=90%). A transient passing-car span (the
            // mover's own actor TTL-pruned, only the parked car left in-window) is
            // SHORT relative to the long parked window and/or overhangs an edge, so
            // it fails containment/coverage and is KEPT (fail-open). A drive-in
            // span (starts before the actor) or a late-departing span (ends after)
            // also overhangs and is kept.
            long actorLife = a.lastSeenRelMs - fRel;
            long spanLen = spanEnd - spanStart;
            boolean coextensive = actorLife <= 0 || spanLen >= (long) (0.9 * actorLife);
            // SPATIAL containment too: the span must not light a quadrant the
            // parked actor never occupied. onAiDetection ingests per-quadrant, and
            // CAR/BIKE spans coalesce ACROSS quadrants, so a passing car in
            // quadrant A merged with a parked car in quadrant B yields one span
            // whose camera mask has A's bit — which the parked car's cameraMask
            // lacks. That extra quadrant is positive evidence a different (moving)
            // object is in the span → keep it (fail-open). camSubset==true only
            // when the span is confined to the parked car's own quadrant(s).
            boolean camSubset = (spanCamMask & ~a.cameraMask) == 0;
            if (camSubset && spanStart >= fRel && spanEnd <= a.lastSeenRelMs && coextensive) {
                hasOverlappingStatic = true;
            }
        }
        return hasOverlappingStatic;
    }

    // ========================================================================
    // HOT PATH — Motion events
    // ========================================================================

    public void onMotionDetected(int activeBlocks) {
        onMotionDetected(activeBlocks, 0);
    }

    public void onMotionDetected(int activeBlocks, int cameraMask) {
        long now = System.currentTimeMillis();
        if (collecting) {
            ingestEvent(now - recordingStartTimeMs,
                    TYPE_MOTION, 0f, (byte) 0, (byte)(cameraMask & 0x0F));
        } else {
            // Pre-trigger: always ingest into the ring buffer
            ingestPreTrigger(now, TYPE_MOTION, 0f, (byte) 0, (byte)(cameraMask & 0x0F));
        }
    }

    // ========================================================================
    // HOT PATH — AI detection events
    // ========================================================================

    public void onAiDetection(List<Detection> detections, boolean hasActiveMotion) {
        onAiDetection(detections, hasActiveMotion, 0);
    }

    public void onAiDetection(List<Detection> detections, boolean hasActiveMotion, int cameraMask) {
        if (!hasActiveMotion || detections == null || detections.isEmpty()) return;

        byte bestType = TYPE_MOTION;
        float bestConf = 0;
        int totalCount = 0;

        for (int i = 0, size = detections.size(); i < size; i++) {
            Detection det = detections.get(i);
            int classId = det.getClassId();
            byte type;

            if (classId == 0)                                      type = TYPE_PERSON;
            else if (classId == 2 || classId == 5 || classId == 7) type = TYPE_CAR;
            else if (classId == 1 || classId == 3)                 type = TYPE_BIKE;
            else if (classId >= 14 && classId <= 23)               type = TYPE_ANIMAL;  // COCO animals
            else continue;

            totalCount++;
            if (type > bestType || (type == bestType && det.getConfidence() > bestConf)) {
                bestType = type;
                bestConf = det.getConfidence();
            }
        }

        if (totalCount > 0) {
            long now = System.currentTimeMillis();
            byte countByte = (byte) Math.min(totalCount, 127);
            byte camByte = (byte)(cameraMask & 0x0F);

            if (collecting) {
                ingestEvent(now - recordingStartTimeMs, bestType, bestConf, countByte, camByte);
            } else {
                // Pre-trigger: always ingest into the ring buffer
                ingestPreTrigger(now, bestType, bestConf, countByte, camByte);
            }
        }
    }

    // ========================================================================
    // PRE-TRIGGER RING BUFFER INGESTION (uses absolute wall-clock timestamps)
    // ========================================================================

    private synchronized void ingestPreTrigger(long absoluteMs, byte type, float conf,
                                                byte count, byte cameras) {
        if (preInflightStart < 0) {
            preInflightStart = absoluteMs;
            preInflightEnd   = absoluteMs;
            preInflightType  = type;
            preInflightConf  = conf;
            preInflightCount = count;
            preInflightCams  = cameras;
            return;
        }

        if (absoluteMs - preInflightEnd <= COALESCE_MS) {
            preInflightEnd = absoluteMs;
            preInflightCams |= cameras;
            if (type > preInflightType) {
                preInflightType = type;
                preInflightConf = conf;
            } else if (type == preInflightType && conf > preInflightConf) {
                preInflightConf = conf;
            }
            if (count > preInflightCount) {
                preInflightCount = count;
            }
        } else {
            commitPreInflight();
            preInflightStart = absoluteMs;
            preInflightEnd   = absoluteMs;
            preInflightType  = type;
            preInflightConf  = conf;
            preInflightCount = count;
            preInflightCams  = cameras;
        }
    }

    private void commitPreInflight() {
        if (preInflightStart < 0) return;

        int idx = preRingHead;
        preRingStarts[idx] = preInflightStart;
        preRingEnds[idx]   = preInflightEnd;
        preRingTypes[idx]  = preInflightType;
        preRingConfs[idx]  = preInflightConf;
        preRingCounts[idx] = preInflightCount;
        preRingCams[idx]   = preInflightCams;

        preRingHead = (preRingHead + 1) % PRE_RING_CAPACITY;
        if (preRingCount < PRE_RING_CAPACITY) preRingCount++;

        preInflightStart = -1;
    }

    // ========================================================================
    // ACTIVE SPAN INGESTION (uses relative timestamps from recordingStartTimeMs)
    // ========================================================================

    private synchronized void ingestEvent(long relativeMs, byte type, float conf,
                                           byte count, byte cameras) {
        if (inflightStart < 0) {
            inflightStart = relativeMs;
            inflightEnd   = relativeMs;
            inflightType  = type;
            inflightConf  = conf;
            inflightCount = count;
            inflightCameras = cameras;
            return;
        }

        if (relativeMs - inflightEnd <= COALESCE_MS) {
            inflightEnd = relativeMs;
            inflightCameras |= cameras;
            if (type > inflightType) {
                inflightType = type;
                inflightConf = conf;
            } else if (type == inflightType && conf > inflightConf) {
                inflightConf = conf;
            }
            if (count > inflightCount) {
                inflightCount = count;
            }
        } else {
            commitInflight();
            inflightStart = relativeMs;
            inflightEnd   = relativeMs;
            inflightType  = type;
            inflightConf  = conf;
            inflightCount = count;
            inflightCameras = cameras;
        }
    }

    private void commitInflight() {
        if (inflightStart < 0) return;
        if (spanCount >= SPAN_CAPACITY) {
            if (spanCount == SPAN_CAPACITY) {
                logger.warn("Timeline buffer full (" + SPAN_CAPACITY + " spans). Dropping.");
            }
            inflightStart = -1;
            return;
        }

        int idx = spanCount;
        spanStarts[idx]      = inflightStart;
        spanEnds[idx]        = inflightEnd;
        spanTypes[idx]       = inflightType;
        spanConfidences[idx] = inflightConf;
        spanCounts[idx]      = inflightCount;
        spanCameras[idx]     = inflightCameras;
        spanCount = idx + 1;
        inflightStart = -1;
    }

    // ========================================================================
    // COLD PATH — Async JSON file I/O
    // ========================================================================

    private void writeJsonSidecar(File mp4File, long[] starts, long[] ends,
                                   byte[] types, float[] confs, byte[] counts,
                                   byte[] cameras, int count, long durationMs) {
        // Backwards-compat overload: delegate to the v3 writer with no actors/hero/geo.
        writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cameras,
                count, durationMs, java.util.Collections.<Actor>emptyList(),
                null, null, null, null);
    }

    /**
     * Backwards-compat 11-arg overload (no geo). Retained for any caller
     * that still threads only actors + hero through.
     */
    private void writeJsonSidecar(File mp4File, long[] starts, long[] ends,
                                   byte[] types, float[] confs, byte[] counts,
                                   byte[] cameras, int count, long durationMs,
                                   java.util.List<Actor> actors, String heroThumbnail) {
        writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cameras,
                count, durationMs, actors, heroThumbnail, null, null, null);
    }

    /**
     * v3 sidecar writer — superset of v2. Old readers (which look for
     * {@code version=2}, {@code events[]}, {@code stats.{motion,person,car,bike}})
     * keep working because every v2 field is still present. New readers may
     * additionally read:
     *   - {@code actors[]}        list of {@link Actor}-shaped records
     *   - {@code stats.peakSeverity} / {@code peakSeverityTMs}
     *   - {@code stats.{personCount,vehicleCount,bikeCount,animalCount}}
     *   - {@code stats.peakProximity}
     *   - {@code heroThumbnail}    basename of the JPEG sibling file
     *   - {@code geo.start} / {@code geo.peak} / {@code geo.end} — coords
     *     captured at recording start / peak severity / finalize.
     *   - {@code geo.place} — reverse-geocoded place name (added later by
     *     {@link com.overdrive.app.geo.SidecarGeoUpdater} when the resolver
     *     completes; absent when the geocoding feature is off).
     */
    private void writeJsonSidecar(File mp4File, long[] starts, long[] ends,
                                   byte[] types, float[] confs, byte[] counts,
                                   byte[] cameras, int count, long durationMs,
                                   java.util.List<Actor> actors, String heroThumbnail,
                                   com.overdrive.app.geo.GeoSnapshot startGeo,
                                   com.overdrive.app.geo.GeoSnapshot peakGeo,
                                   com.overdrive.app.geo.GeoSnapshot endGeo) {
        writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cameras, count,
                durationMs, actors, heroThumbnail, startGeo, peakGeo, endGeo, null, 0);
    }

    private void writeJsonSidecar(File mp4File, long[] starts, long[] ends,
                                   byte[] types, float[] confs, byte[] counts,
                                   byte[] cameras, int count, long durationMs,
                                   java.util.List<Actor> actors, String heroThumbnail,
                                   com.overdrive.app.geo.GeoSnapshot startGeo,
                                   com.overdrive.app.geo.GeoSnapshot peakGeo,
                                   com.overdrive.app.geo.GeoSnapshot endGeo,
                                   String parkingSessionId, int acceptedDeterrents) {
        final String[] CAMERA_NAMES = {"front", "right", "rear", "left"};

        try {
            // SOTA: Sort spans chronologically by start time.
            Integer[] sortIdx = new Integer[count];
            for (int i = 0; i < count; i++) sortIdx[i] = i;
            java.util.Arrays.sort(sortIdx, (a, b) -> Long.compare(starts[a], starts[b]));

            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("durationMs", durationMs);
            // Composition layout so the player picks the right per-camera zoom
            // regions. Sentry events are composited under the SENTRY layout
            // profile (surveillance.recordingLayout), independent of the
            // dashcam layout; fall back to the dashcam value when the sentry
            // key is unset. Read at write time; omitted for the default
            // standard layout to keep legacy sidecars byte-identical.
            try {
                String survLayout = com.overdrive.app.config.UnifiedConfigManager
                        .getSurveillance().optString("recordingLayout",
                            com.overdrive.app.config.UnifiedConfigManager
                                .getRecording().optString("recordingLayout", "standard"));
                if ("dashcam".equals(survLayout)) {
                    root.put("layout", "dashcam");
                }
            } catch (Throwable ignored) { /* default standard */ }

            JSONArray eventsArray = new JSONArray();
            int motionN = 0, personN = 0, carN = 0, bikeN = 0, animalN = 0;

            for (int si = 0; si < count; si++) {
                int i = sortIdx[si];

                // Drop a CAR/BIKE span marker that lies entirely within the
                // lifespan of a timeline-static non-person actor (a parked car).
                // The span path carries no actor identity, so we match by time
                // window. PERSON spans are never dropped (overlapsStaticActor only
                // considers non-person static actors), and a real moving car —
                // which produces a non-static actor — is unaffected. Closes the
                // "parked car as a car marker in the JSON/events timeline" leak.
                // maxCount<=1 guard: the span path coalesces all CAR/BIKE
                // detections into one chain, so a span whose peak simultaneous
                // count reached >=2 may be hiding a real coexisting mover merged
                // with the parked car — keep it (fail-open). Only a lone-object
                // span (count<=1) that is a parked car's own marker is dropped.
                // ANIMAL spans are included: a static/background animal
                // misread (or a coalesced parked-object chain) is dropped on
                // the same containment test, keeping the marker consistent with
                // the pill/chip (both of which already exclude static
                // non-person actors). overlapsStaticActor considers non-person
                // static actors only, so a real moving animal — a non-static
                // ANIMAL actor — is never dropped (fail-open).
                if ((types[i] == TYPE_CAR || types[i] == TYPE_BIKE || types[i] == TYPE_ANIMAL)
                        && (counts[i] & 0xFF) <= 1
                        && overlapsStaticActor(starts[i], ends[i], (cameras[i] & 0x0F), actors)) {
                    continue;
                }

                JSONObject ev = new JSONObject();
                ev.put("start", starts[i]);
                ev.put("end", ends[i]);

                String typeName = TYPE_NAMES[types[i]];
                ev.put("type", typeName);

                if (confs[i] > 0) {
                    ev.put("maxConf", Math.round(confs[i] * 100) / 100.0);
                }
                int cnt = counts[i] & 0xFF;
                if (cnt > 1) {
                    ev.put("maxCount", cnt);
                }

                int camMask = cameras[i] & 0x0F;
                if (camMask > 0) {
                    JSONArray camArray = new JSONArray();
                    for (int bit = 0; bit < 4; bit++) {
                        if ((camMask & (1 << bit)) != 0) {
                            camArray.put(CAMERA_NAMES[bit]);
                        }
                    }
                    ev.put("cameras", camArray);
                }

                eventsArray.put(ev);

                switch (typeName) {
                    case "person": personN++; break;
                    case "car":    carN++;    break;
                    case "bike":   bikeN++;   break;
                    case "animal": animalN++; break;
                    default:       motionN++; break;
                }
            }

            root.put("events", eventsArray);

            // ---- Actors (v3) ----
            int personCount = 0, vehicleCount = 0, bikeCount = 0, animalCount = 0;
            Actor.Severity peakSev = null;
            Actor.Proximity peakProx = null;
            long peakSevRel = -1;
            JSONArray actorsArr = new JSONArray();
            if (actors != null) {
                for (Actor a : actors) {
                    JSONObject ao = new JSONObject();
                    ao.put("actorId", a.actorId);
                    ao.put("classGroup", a.classGroup.name());
                    ao.put("class", Actor.groupLabel(a.classGroup));
                    ao.put("firstSeenWallMs", a.firstSeenWallMs);
                    ao.put("lastSeenWallMs", a.lastSeenWallMs);
                    if (a.firstSeenRelMs >= 0) ao.put("firstSeenMs", a.firstSeenRelMs);
                    if (a.lastSeenRelMs >= 0)  ao.put("lastSeenMs", a.lastSeenRelMs);
                    ao.put("peakProximity", a.peakProximity.name());
                    ao.put("lastProximity", a.lastProximity.name());
                    ao.put("trend", a.trend.name());
                    ao.put("isStatic", a.isStatic);
                    ao.put("isStaticForTimeline", a.isStaticForTimeline);
                    ao.put("peakSeverity", a.peakSeverity.name());
                    ao.put("peakSeverityWallMs", a.peakSeverityWallMs);
                    if (a.peakSeverityRelMs >= 0) ao.put("peakSeverityMs", a.peakSeverityRelMs);
                    ao.put("peakConfidence", Math.round(a.peakConfidence * 100) / 100.0);
                    ao.put("peakCamera", a.peakCamera >= 0 && a.peakCamera < 4
                            ? CAMERA_NAMES[a.peakCamera] : "");
                    JSONArray camArr = new JSONArray();
                    for (int bit = 0; bit < 4; bit++) {
                        if ((a.cameraMask & (1 << bit)) != 0) camArr.put(CAMERA_NAMES[bit]);
                    }
                    ao.put("cameras", camArr);
                    // Persist the SUMMARY suppression verdict so the downstream
                    // events-page class chip/filter (RecordingsIndex actor_classes)
                    // honours the SAME decision the live count/SRT/caption made —
                    // the verdict depends on everMoved/everMovedTested which aren't
                    // otherwise serialized, so it can't be recomputed from the
                    // sidecar. Summary scope (PERSON exempt), matching the chip's
                    // role as a summary surface. Only emit when true (older sidecars
                    // + real actors default-absent → readers fail open = prior behavior).
                    if (Actor.suppressFromSummary(a)) {
                        ao.put("lowConfFarNotice", true);
                    }

                    actorsArr.put(ao);
            if (acceptedDeterrents != 0) stats.put("deterrentFlags", acceptedDeterrents);

                    // Counts + peak fields exclude static actors. The classic
                    // failure to prevent: a parked car next to ours dominates
                    // the stats and notification because YOLO sees it at high
                    // confidence. Static = not a threat = not a count. Use the
                    // timeline-static superset so a parked car that never latched
                    // the severity-path isStatic (sparse cadence) is still excluded
                    // from vehicleCount/peakProximity. PERSON is unaffected
                    // (isStaticForTimeline == isStatic for persons).
                    // Headline contribution gate. Always exclude timeline-static
                    // actors (parked cars). ADDITIONALLY exclude an UNCONFIRMED
                    // person (historyCount < MIN_ESCALATION_FRAMES, a 1-2 frame
                    // YOLO flicker): such a person latches peakProximity
                    // unconditionally (ActorTracker:512) but is never severity-
                    // escalated, which produced the "👤 very close + Notice" card.
                    // The caption/retention path (eventPeakActors) already requires
                    // confirmed for persons, so this aligns the headline stats with
                    // it: a flicker-person no longer counts toward personCount nor
                    // sets the headline proximity/severity. A CONFIRMED person is
                    // unaffected (and now carries a proximity-consistent severity
                    // via ActorTracker.toActor()). Non-person classes keep their
                    // existing isStaticForTimeline-only gate.
                    // ALSO exclude a low-confidence FAR NOTICE actor via
                    // suppressFromSummary — but note that predicate EXEMPTS PERSON
                    // (it only drops NON-person FPs: a far low-conf parked car/bike
                    // at NOTICE). For a PERSON-classified FP (e.g. the parked
                    // motorcycle YOLO labelled "person · far" @0.44) this clause is
                    // a NO-OP: the person is still counted here, keeps its SRT line
                    // and events-page chip, and is named in the caption — because a
                    // genuinely-motionless distant person is byte-identical to that
                    // FP and the hard invariant forbids dropping a real person from
                    // the summary. The hero pool separately drops the PERSON-FP box
                    // (Actor.suppressFromHero, all classes) so the thumbnail shows a
                    // clean keyframe instead of a phantom box; that is an
                    // intentional card-keeps-person / hero-shows-no-box asymmetry,
                    // NOT a bug. Real moving/approaching/closer far actors are never
                    // dropped on any surface (trend + everMoved exemptions in core).
                    boolean contributesToHeadline = !a.isStaticForTimeline
                            && !(a.classGroup == Actor.ClassGroup.PERSON && !a.confirmed)
                            && !Actor.suppressFromSummary(a);
                    if (contributesToHeadline) {
                        switch (a.classGroup) {
                            case PERSON:  personCount++;  break;
                            case VEHICLE: vehicleCount++; break;
                            case BIKE:    bikeCount++;    break;
                            case ANIMAL:  animalCount++;  break;
                            default: break;
                        }
                        if (peakSev == null || a.peakSeverity.ordinal() > peakSev.ordinal()) {
                            peakSev = a.peakSeverity;
                            peakSevRel = a.peakSeverityRelMs;
                        }
                        if (peakProx == null || a.peakProximity.ordinal() < peakProx.ordinal()) {
                            peakProx = a.peakProximity;
                        }
                    }
                }
            }
            root.put("actors", actorsArr);

            // ---- Stats (v2 fields preserved + v3 additions) ----
            JSONObject stats = new JSONObject();
            // v2 fields: keep names exactly so existing readers keep working
            stats.put("motion", motionN);
            stats.put("person", personN);
            stats.put("car", carN);
            stats.put("bike", bikeN);
            // Additive span-count field (mirrors the v2 set). Older readers
            // ignore it; the actor-based animalCount below remains the primary
            // headline source. Kept in lockstep so animal spans don't silently
            // fold into the "motion" count.
            stats.put("animal", animalN);
            // v3 additions
            stats.put("personCount", personCount);
            stats.put("vehicleCount", vehicleCount);
            stats.put("bikeCount", bikeCount);
            stats.put("animalCount", animalCount);
            if (peakSev != null) {
                stats.put("peakSeverity", peakSev.name());
                if (peakSevRel >= 0) stats.put("peakSeverityMs", peakSevRel);
            }
            if (peakProx != null) {
                stats.put("peakProximity", peakProx.name());
            }
            root.put("stats", stats);

            if (heroThumbnail != null && !heroThumbnail.isEmpty()) {
                root.put("heroThumbnail", heroThumbnail);
            }
            // Parking Intelligence (additive, omitted when the feature is off):
            // the parked session this event happened in. The recordings index
            // ingests it into parking_session_id so a session page can list
            // its clips without scanning sidecars.
            if (parkingSessionId != null && !parkingSessionId.isEmpty()) {
                root.put("parkingSessionId", parkingSessionId);
            }

            // ---- Geo block (v3 addition) ----
            // start / peak / end are independent: a clip with a fix at start
            // but no fix at finalize emits only `start`. The block itself is
            // omitted entirely when no fix at any moment; readers see the
            // sidecar in pre-geo shape and behave as before.
            if ((startGeo != null && startGeo.hasFix())
                    || (peakGeo != null && peakGeo.hasFix())
                    || (endGeo != null && endGeo.hasFix())) {
                JSONObject geo = new JSONObject();
                if (startGeo != null && startGeo.hasFix()) geo.put("start", startGeo.toJson());
                if (peakGeo  != null && peakGeo.hasFix())  geo.put("peak",  peakGeo.toJson());
                if (endGeo   != null && endGeo.hasFix())   geo.put("end",   endGeo.toJson());
                root.put("geo", geo);
            }

            String jsonName = mp4File.getName().replace(".mp4", ".json");
            File jsonFile = new File(mp4File.getParentFile(), jsonName);
            File tmpFile = new File(jsonFile.getAbsolutePath() + ".tmp");

            try (FileWriter fw = new FileWriter(tmpFile)) {
                fw.write(root.toString());
            }

            if (!tmpFile.renameTo(jsonFile)) {
                try (FileWriter fw = new FileWriter(jsonFile)) {
                    fw.write(root.toString());
                }
                tmpFile.delete();
            }

            jsonFile.setReadable(true, false);

            logger.info(String.format("Timeline saved (v3): %s (%d spans, %d actors, dur=%ds)",
                    jsonFile.getName(), count, actorsArr.length(), durationMs / 1000));

            // Re-upsert the index now that the rich sidecar (stats, heroThumbnail,
            // actors, geo.start/peak/end) has landed. The first upsert at rename
            // time saw bare-mp4 metadata; this one populates peak_severity,
            // hero_thumb, actor_classes, etc. so /api/recordings filter+chip
            // queries reflect reality without waiting for FileObserver.
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance().upsert(mp4File);
            } catch (Throwable t) {
                logger.warn("Index upsert failed for " + mp4File.getName() + ": " + t.getMessage());
            }

        } catch (Exception e) {
            logger.error("Failed to write timeline JSON: " + e.getMessage(), e);
        }
    }
}
