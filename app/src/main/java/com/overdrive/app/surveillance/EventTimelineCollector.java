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

    // Type constants (byte values for compact storage)
    private static final byte TYPE_MOTION = 0;
    private static final byte TYPE_BIKE   = 1;
    private static final byte TYPE_CAR    = 2;
    private static final byte TYPE_PERSON = 3;
    private static final String[] TYPE_NAMES = {"motion", "bike", "car", "person"};

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

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    public synchronized void startCollecting() {
        startCollecting(0);
    }

    /**
     * Start collecting with a pre-record offset.
     * Flushes the pre-trigger ring buffer into the active span array,
     * shifting timestamps to align with the video's pre-record window.
     */
    public synchronized void startCollecting(long preRecordMs) {
        startCollecting(preRecordMs, /*flushPreRing=*/true);
    }

    /**
     * Segment-rotation entry point: start collecting WITHOUT replaying the
     * pre-trigger ring buffer. The pre-ring captured spans from before the
     * original event began; replaying them at every rotation would seed
     * each subsequent segment with stale "marker at relMs=0" entries that
     * already appeared in segment 1's sidecar. Used by SurveillanceEngineGpu
     * when rotateSegment fires.
     */
    public synchronized void startCollectingNoPreRing() {
        startCollecting(0L, /*flushPreRing=*/false);
    }

    private synchronized void startCollecting(long preRecordMs, boolean flushPreRing) {
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
                + (flushPreRing ? "" : ", preRing skipped") + ")");
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
        if (!collecting) return;
        collecting = false;

        commitInflight();

        final int count = spanCount;
        if (count == 0 && (actors == null || actors.isEmpty())) {
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

        writeExecutor.execute(() -> {
            writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cams, count,
                    durationMs, actorsCopy, heroThumb);
            // SRT subtitle sidecar — localized prose so VLC / video.js / ExoPlayer
            // can show "Person detected close range" / "Charging started · 4.3 kW"
            // without re-encoding the burned-in English overlay. Wrapped so an
            // SRT failure can never poison the JSON write above (which the
            // recordings UI depends on).
            try {
                writeSrtSidecar(mp4File, starts, ends, types, count, actorsCopy);
            } catch (Throwable t) {
                logger.warn("SRT sidecar write failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            }
        });
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
     *       offset, keyed by class group (PERSON / VEHICLE) and tightened
     *       to {@code srt.person_close} when peakProximity is VERY_CLOSE
     *       or CLOSE.</li>
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
                                 java.util.List<Actor> actors) {
        SrtWriter srt = new SrtWriter();
        srt.addEvent(0L, SrtWriter.K_RECORDING_STARTED);

        // Actor-driven entries (preferred — they carry class + proximity)
        if (actors != null) {
            for (Actor a : actors) {
                if (a.isStatic) continue;
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
                    default:
                        // BIKE / ANIMAL / UNKNOWN: no key in the catalog yet;
                        // fall through to motion if applicable.
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

    private static boolean overlapsAnyActor(long spanStart, long spanEnd,
                                            java.util.List<Actor> actors) {
        if (actors == null || actors.isEmpty()) return false;
        for (Actor a : actors) {
            if (a.firstSeenRelMs < 0 || a.lastSeenRelMs < 0) continue;
            if (spanStart <= a.lastSeenRelMs && spanEnd >= a.firstSeenRelMs) {
                return true;
            }
        }
        return false;
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
        // Backwards-compat overload: delegate to the v3 writer with no actors/hero.
        writeJsonSidecar(mp4File, starts, ends, types, confs, counts, cameras,
                count, durationMs, java.util.Collections.<Actor>emptyList(), null);
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
     */
    private void writeJsonSidecar(File mp4File, long[] starts, long[] ends,
                                   byte[] types, float[] confs, byte[] counts,
                                   byte[] cameras, int count, long durationMs,
                                   java.util.List<Actor> actors, String heroThumbnail) {
        final String[] CAMERA_NAMES = {"front", "right", "rear", "left"};

        try {
            // SOTA: Sort spans chronologically by start time.
            Integer[] sortIdx = new Integer[count];
            for (int i = 0; i < count; i++) sortIdx[i] = i;
            java.util.Arrays.sort(sortIdx, (a, b) -> Long.compare(starts[a], starts[b]));

            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("durationMs", durationMs);

            JSONArray eventsArray = new JSONArray();
            int motionN = 0, personN = 0, carN = 0, bikeN = 0;

            for (int si = 0; si < count; si++) {
                int i = sortIdx[si];
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

                    actorsArr.put(ao);

                    // Counts + peak fields exclude static actors. The classic
                    // failure to prevent: a parked car next to ours dominates
                    // the stats and notification because YOLO sees it at high
                    // confidence. Static = not a threat = not a count.
                    if (!a.isStatic) {
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

        } catch (Exception e) {
            logger.error("Failed to write timeline JSON: " + e.getMessage(), e);
        }
    }
}
