package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.Actor.ClassGroup;
import com.overdrive.app.surveillance.Actor.Proximity;
import com.overdrive.app.surveillance.Actor.Severity;
import com.overdrive.app.surveillance.Actor.Trend;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ActorTracker — Persistent tracker that turns per-frame YOLO detections into
 * lifetime-aware {@link Actor} records.
 *
 * Design notes:
 *  - Sits on top of the existing motion+YOLO pipeline; does NOT replace it.
 *  - One tracker instance per surveillance engine; tracks across cameras.
 *  - Association: greedy IoU within the same quadrant; class-group must match.
 *    Cross-quadrant handoff is deferred to {@link CrossQuadrantTracker} which
 *    keeps doing what it does today — this tracker assigns its own actorIds and
 *    is independent.
 *  - Proximity is pixel-relative (no extrinsics). Calibrated thresholds on
 *    bbox-dim/quadrant-dim ratio.
 *  - Trend = sign of bbox-area change over the last {@code TREND_WINDOW} updates.
 *  - Static = bbox area + position stable for {@code STATIC_FRAMES_NEEDED}+ updates.
 *  - All inputs are in QUADRANT pixel coordinates (the same coordinate space
 *    SurveillanceEngineGpu uses today after foveated→quadrant scaling at lines
 *    1597–1598). The caller is responsible for any coordinate normalisation.
 */
public final class ActorTracker {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ActorTracker");

    /** Active tracks live this long without an update before being pruned.
     *  MUST exceed the YOLO re-dispatch floor (HEARTBEAT_COOLDOWN_MS=5000 in the
     *  engine) so a held track survives the gap between sparse inferences and can
     *  accrue the consecutive observations needed to latch static / accumulate an
     *  everMoved verdict. At exactly 5000 the TTL and the re-dispatch floor raced,
     *  so a parked car pruned and restarted at stableFrames=0 every cycle and
     *  never settled. Raised to 8000 (departed actors linger ~3s longer, which the
     *  eventPeakActors / finalNotificationActors union already tolerates). */
    private static final long TRACK_TTL_MS = 8000;

    /** Hard upper bound on simultaneous tracks. */
    private static final int MAX_TRACKS = 32;

    /** IoU below this is not a match. */
    private static final float MATCH_IOU_MIN = 0.20f;

    // Minimum quiet gap before the xqTrackId hint path demands same-quadrant bbox
    // overlap (see Path A in update()). Chosen above the slowest legitimate YOLO
    // period (AI_COOLDOWN_MS = 500 ms) with headroom for a skipped tick, so a
    // continuously-tracked mover is never subjected to the check, while a track
    // that went quiet long enough for a DIFFERENT object to take its place is.
    private static final long HINT_IOU_CHECK_MIN_GAP_MS = 1500;

    /** History window for trend + static decision. */
    private static final int TREND_WINDOW = 6;

    /** How many consecutive stable observations classify "static" (persons + bikes). */
    private static final int STATIC_FRAMES_NEEDED = 8;

    /**
     * Minimum observations before a track may latch a peak severity above NOTICE.
     * A track seen only 1-2 frames is almost always a YOLO flicker or a one-frame
     * misclassification (e.g. a parked car momentarily boxed as a person). Because
     * peakSeverity is a monotone lifetime latch AND classGroup is final, a single
     * such frame would otherwise pin ALERT/CRITICAL + a (possibly wrong) class for
     * the whole event, dominating both the caption and the hero thumbnail. A
     * genuine actor accrues frames at ~10 FPS, so a real threat is delayed by at
     * most ~(N-1)*100ms (~300ms here) before it can escalate — imperceptible.
     */
    private static final int MIN_ESCALATION_FRAMES = 3;

    /**
     * Vehicles get a much shorter static window. The classic failure to prevent:
     * a parked car that DetectionBaseline missed (e.g. arrived between event-end
     * baseline updates) reaches the Actor layer with a fresh track. With
     * STATIC_FRAMES_NEEDED=8 the Actor would be non-static for ~800ms and the
     * SeverityClassifier could escalate it to ALERT. 2 frames (~200ms at 10 fps)
     * means the second consecutive frame already classifies it as static and
     * caps severity at NOTICE — mirroring the intuition that a vehicle is only
     * a threat when it's *moving toward us*.
     */
    private static final int STATIC_FRAMES_NEEDED_VEHICLE = 2;

    /**
     * Dwell bbox-refresh confidence floor, as a FRACTION of the latched peak
     * confidence. While an actor dwells at its peak proximity tier, the hero
     * bbox/time re-points to the freshest frame whose confidence is at least
     * this fraction of the peak — so a moving actor's box tracks them instead of
     * freezing at first-touch. 0.6 admits the natural YOLO confidence decay of a
     * real, still-clearly-visible actor (a 0.90 first sight stays eligible down
     * to 0.54) while rejecting a collapse toward the detection threshold (a bbox
     * clipped at the frame edge as the actor exits). Paired with an absolute
     * floor so a low-peak actor can't ratchet the bar below a meaningful value.
     */
    private static final float DWELL_REFRESH_CONF_FRAC = 0.60f;

    /**
     * Absolute lower bound for the dwell bbox-refresh floor. Guards the
     * fractional floor for a low-confidence peak: at peak 0.40 the fractional
     * floor is 0.24 (~the YOLO 0.25 threshold), which would re-point onto a
     * near-noise box. 0.40 keeps the refreshed frame a genuine detection
     * regardless of how low the peak was.
     */
    private static final float DWELL_REFRESH_CONF_ABS_MIN = 0.40f;

    /** Bbox-area drift below this counts as "stable" for static detection. */
    private static final float STATIC_AREA_DRIFT_FRAC = 0.10f;

    /** Bbox-centroid drift (pixels) below this counts as "stable" for static detection. */
    private static final int STATIC_CENTROID_DRIFT_PX = 10;

    /**
     * Normalized-space equivalents of {@link #STATIC_CENTROID_DRIFT_PX} (10 px in
     * the 320×240 mosaic quadrant → per-axis fractions of the frame).
     * (audit R5-6) Cross-observation comparisons now run uniformly in QUADRANT
     * space (constant 320×240 reference frame — the engine affine-maps foveated
     * boxes through the window transform before update()), so these fractions are
     * exact pixel-threshold equivalents on every frame, not just pure-mosaic
     * sequences. The per-own-dims normalization is kept as the minimal diff: it
     * is an exact identity for constant-dims inputs and still guards legacy
     * callers that pass no quadrant-space list (audit R3b Ext-8 heritage).
     */
    private static final float STATIC_CENTROID_DRIFT_NX = STATIC_CENTROID_DRIFT_PX / 320f;
    private static final float STATIC_CENTROID_DRIFT_NY = STATIC_CENTROID_DRIFT_PX / 240f;

    /**
     * Coherent vertical centroid motion required by computeTrend, NORMALIZED
     * (was a raw 5 px in the 240-high mosaic quadrant → 5/240 of frame height).
     * (audit R5-6) Trend history now stores quadrant-space centroids normalized
     * by the quadrant dims (uniformly 320×240 from the engine), so the threshold
     * is exactly the old 5 px in quadrant space on every frame.
     */
    private static final float TREND_DCY_NORM = 5f / 240f;

    /**
     * (audit R5-6) Reference frame dims of the engine's quadrant space — the
     * constant 320×240 coordinate system cqtDetections are affine-mapped into
     * (matches CrossQuadrantTracker's hardcoded Q_WIDTH/Q_HEIGHT). Used to
     * normalize the parallel quadrant-space list; legacy callers that pass no
     * such list fall back to the native frame dims.
     */
    private static final int QUAD_SPACE_W = 320;
    private static final int QUAD_SPACE_H = 240;

    /**
     * Centroid drift (pixels) above which a non-person track is deemed to have
     * "ever moved" — the latch that makes a genuinely parked car read static for
     * TIMELINE purposes even when it never accrued the consecutive stable frames
     * needed for the severity-path isStatic (sparse YOLO cadence rarely delivers
     * 3 consecutive same-track hits within the TTL). A car that ever showed this
     * much centroid travel, or any APPROACHING/RECEDING trend, is NOT parked and
     * keeps its timeline marker. Larger than STATIC_CENTROID_DRIFT_PX so brief
     * bbox jitter on a truly-parked car doesn't trip it.
     */
    private static final int EVER_MOVED_CENTROID_PX = 18;

    // Proximity thresholds — pixel-relative ratios of bbox dim to quadrant dim
    // (quadrant = 320×240 in mosaic mode; foveated path is rescaled to quadrant first).
    private static final float PROX_VERY_CLOSE = 0.60f;
    private static final float PROX_CLOSE      = 0.35f;
    private static final float PROX_MID        = 0.15f;
    // People only: feet in the bottom third of the camera image means right at the car,
    // whatever the box height (legs-only / crouching people read small). Set from field clips.
    static final float PROX_FEET_VERY_CLOSE = 0.65f;
    // Consecutive feet-low looks a track needs before the feet rule counts, so one bad box
    // can't latch a lifetime VERY_CLOSE.
    private static final int FEET_LOW_FRAMES_NEEDED = 2;

    private long nextActorId = 1;
    private final List<Track> tracks = new ArrayList<>();
    private final long bornWallMs;

    public ActorTracker() {
        this.bornWallMs = System.currentTimeMillis();
    }

    /** Person proximity: box-height tier, raised to VERY_CLOSE when the feet sit low in the frame. */
    static Proximity personProximity(float heightRatio, float bottomRatio) {
        Proximity p = Track.ratioToProximity(heightRatio);
        return (p != Proximity.UNKNOWN && bottomRatio >= PROX_FEET_VERY_CLOSE) ? Proximity.VERY_CLOSE : p;
    }

    /**
     * Process a batch of detections from one quadrant for one frame and return
     * the updated actor view (snapshot). Caller may pass an empty list to age
     * tracks without adding new observations.
     *
     * @param detections    YOLO detections, in QUADRANT pixel coords (top-left origin)
     * @param quadrant      Quadrant index 0..3 (front/right/rear/left)
     * @param quadrantW     Width of the coord space the bboxes live in (e.g. 320)
     * @param quadrantH     Height of the coord space the bboxes live in (e.g. 240)
     * @param recordingStartWallMs  Recording start wall-clock; pass 0 if not recording
     * @param wallNowMs     Wall-clock for this frame
     * @return Immutable list of all currently-active Actors (across all quadrants)
     */
    public synchronized List<Actor> update(List<Detection> detections,
                                           int quadrant,
                                           int quadrantW,
                                           int quadrantH,
                                           long recordingStartWallMs,
                                           long wallNowMs) {
        return update(detections, null, null, quadrant, quadrantW, quadrantH,
                      recordingStartWallMs, wallNowMs);
    }

    /**
     * Variant of {@link #update(List, int, int, int, long, long)} that accepts
     * a parallel array of cross-quadrant track ID hints (one per detection,
     * or {@code 0} for no hint).
     *
     * When a hint is present, the matching pass first tries to find an
     * existing Track with the same {@code xqTrackId} regardless of quadrant.
     * This fixes the "same physical person crosses front→right and gets two
     * actorIds" bug: the cross-quadrant tracker has already assigned a
     * persistent ID; we just bind the Actor to it.
     *
     * If no hinted match is found, falls back to the original per-quadrant +
     * IoU + class-group match. Detections without a hint use the legacy path.
     */
    public synchronized List<Actor> update(List<Detection> detections,
                                           int[] xqTrackIdHints,
                                           int quadrant,
                                           int quadrantW,
                                           int quadrantH,
                                           long recordingStartWallMs,
                                           long wallNowMs) {
        return update(detections, null, xqTrackIdHints, quadrant, quadrantW,
                      quadrantH, recordingStartWallMs, wallNowMs);
    }

    /**
     * (audit R5-6) Variant that additionally accepts a parallel QUADRANT-SPACE
     * copy of the detections ({@code quadDetections}, same size and order as
     * {@code detections}, boxes affine-mapped into the constant 320×240 quadrant
     * frame — the engine already builds this list for CrossQuadrantTracker).
     *
     * Rationale: {@code detections} arrive in the crop's NATIVE space (640×640
     * foveated window or 320×240 mosaic). The foveated window is a moving
     * ~160×160 SUB-REGION of the quadrant, so even per-own-dims normalization
     * (audit R3b Ext-8) leaves a ~3× normalized-area step across every
     * mosaic↔foveated flip: stableFrames reset on every flip, computeTrend could
     * manufacture APPROACHING, and Path-B IoU failed off-center. All
     * CROSS-OBSERVATION comparisons (hint-rejection IoU, Path-B matching IoU,
     * stability, trend history, everMoved) therefore use the quadrant-space box,
     * which lives in one constant physical reference frame. Native boxes are
     * still stored/reported unchanged (lastX/Y/W/H, peakBbox*) so ThumbnailBuffer
     * bbox↔pixels coherence is untouched, and per-frame proximity stays
     * dimension-aware on the native box.
     *
     * {@code quadDetections == null} → each detection falls back to itself
     * (legacy behaviour for callers without a quadrant-space copy).
     */
    public synchronized List<Actor> update(List<Detection> detections,
                                           List<Detection> quadDetections,
                                           int[] xqTrackIdHints,
                                           int quadrant,
                                           int quadrantW,
                                           int quadrantH,
                                           long recordingStartWallMs,
                                           long wallNowMs) {
        pruneStale(wallNowMs);

        // (audit R5-6) Reference dims of the quadrant-space boxes: constant
        // 320×240 when the caller supplies the parallel quadrant-space list;
        // the native frame dims otherwise (legacy — qd falls back to d below).
        final int qSpaceW = (quadDetections != null) ? QUAD_SPACE_W : quadrantW;
        final int qSpaceH = (quadDetections != null) ? QUAD_SPACE_H : quadrantH;

        if (detections != null && !detections.isEmpty()) {
            for (int i = 0; i < detections.size(); i++) {
                Detection d = detections.get(i);
                ClassGroup group = Actor.groupOf(d.getClassId());
                if (group == ClassGroup.UNKNOWN) continue;

                // (audit R5-6) Quadrant-space twin of d for all cross-observation
                // comparisons; the lists are parallel by construction (engine
                // builds cqtDetections from the same list, same order).
                Detection qd = (quadDetections != null && i < quadDetections.size())
                        ? quadDetections.get(i) : d;

                int hint = (xqTrackIdHints != null && i < xqTrackIdHints.length)
                        ? xqTrackIdHints[i] : 0;

                Track best = null;

                // Path A: cross-quadrant trackId match (any quadrant). This is
                // the primary identity signal — same xqTrackId means the
                // CrossQuadrantTracker says it's the same physical thing.
                //
                // SAME-QUADRANT SANITY CHECK. Accepting the hint unconditionally
                // makes this Track inherit everMoved=false and the accumulated
                // historyCount of whatever the hint used to describe, which stamps
                // isStaticForTimeline on a genuinely-moving object's first frame
                // (skipped by every count/threat loop, eventEverSawMovingObject
                // never latches, promoted into DetectionBaseline). CQT bounds that
                // for stale tracks with a jitter radius, but a wrongly-absorbed
                // hint can still arrive. So when the hinted Track was last seen in
                // THIS quadrant, require the boxes to actually overlap — a real
                // same-quadrant continuation always does at the ~2-4 Hz cadence.
                //
                // Deliberately NOT applied across quadrants: a genuine handoff has
                // no reason to overlap (different camera, different pixels), and
                // rejecting it would mint a fresh actorId whose
                // MIN_ESCALATION_FRAMES pins it at NOTICE — a suppressed
                // notification, the failure this hint path exists to prevent.
                //
                // Also gated on the track being STALE (> HINT_IOU_CHECK_MIN_GAP_MS).
                // On consecutive YOLO ticks a fast close-range mover legitimately
                // clears its own previous box, so demanding overlap there would
                // fragment exactly the subject we most want tracked. Absorption
                // only becomes possible after a multi-second gap, which is where
                // the check is both safe and needed.
                boolean hintRejected = false;
                if (hint != 0) {
                    for (Track t : tracks) {
                        if (t.classGroup == group && t.xqTrackId == hint) {
                            boolean staleSameQuadrant =
                                    t.quadrant == quadrant
                                    && (wallNowMs - t.lastSeenWallMs) > HINT_IOU_CHECK_MIN_GAP_MS;
                            if (staleSameQuadrant
                                    // (audit R5-6) compare in QUADRANT space (constant
                                    // 320×240 frame) — per-own-dims normalization alone
                                    // (audit R3b Ext-8) couldn't make the moving foveated
                                    // window commensurable with mosaic frames.
                                    && iouNorm(t, qd, qSpaceW, qSpaceH) <= 0f) {
                                // This hint describes a DIFFERENT physical object that
                                // happened to reuse the id. Remember the rejection so
                                // the id is not re-stamped below onto whatever Track we
                                // end up using — otherwise two Tracks would share one
                                // xqTrackId and the next frame's Path A `break` would
                                // pick whichever comes first in `tracks`, oscillating
                                // the actorId between them (and with it the severity
                                // history each surface reads).
                                hintRejected = true;
                                continue;
                            }
                            best = t;
                            hintRejected = false;
                            break;
                        }
                    }
                }

                // Path B: per-quadrant IoU fallback (legacy behaviour). Only
                // runs when there's no hinted Track. We also gracefully bind
                // the cross-quadrant trackId to a same-quadrant IoU match if
                // both end up describing the same Track — keeps subsequent
                // frames stable.
                if (best == null) {
                    float bestIou = MATCH_IOU_MIN;
                    for (Track t : tracks) {
                        if (t.quadrant != quadrant) continue;
                        if (t.classGroup != group) continue;
                        // (audit R5-6) match in QUADRANT space — a crop-mode flip is a
                        // no-op there, so an off-center subject no longer fragments
                        // (the R3b Ext-8 per-own-dims form failed off-center because
                        // the foveated window is a moving sub-region of the quadrant).
                        float iou = iouNorm(t, qd, qSpaceW, qSpaceH);
                        if (iou > bestIou) {
                            bestIou = iou;
                            best = t;
                        }
                    }
                }

                if (best == null) {
                    if (tracks.size() >= MAX_TRACKS) {
                        evictOldest(wallNowMs);
                    }
                    best = new Track(nextActorId++, group, quadrant);
                    tracks.add(best);
                }
                // Bind the cross-quadrant id to this Track, EXCEPT when the sanity
                // check above rejected it: re-stamping would recreate the duplicate
                // the rejection exists to avoid. The Track then carries xqTrackId=0
                // and is matched by per-quadrant IoU (Path B) until CQT issues it a
                // fresh id — degrading to the legacy behaviour, which is correct
                // rather than wrong.
                if (hint != 0 && !hintRejected && best.xqTrackId == 0) {
                    best.xqTrackId = hint;
                }
                best.observe(d, qd, quadrant, quadrantW, quadrantH,
                             qSpaceW, qSpaceH, recordingStartWallMs, wallNowMs);
            }
        }

        // Build snapshot for callers
        List<Actor> snapshot = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            snapshot.add(t.toActor());
        }
        return snapshot;
    }

    /**
     * Reset tracker state (e.g. when a recording finishes or the user toggles
     * surveillance off).
     */
    public synchronized void reset() {
        tracks.clear();
        nextActorId = 1;
    }

    // ---------- internal -----------------------------------------------------

    private void pruneStale(long now) {
        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (now - t.lastSeenWallMs > TRACK_TTL_MS) {
                it.remove();
            }
        }
    }

    private void evictOldest(long now) {
        Track oldest = null;
        for (Track t : tracks) {
            if (oldest == null || t.lastSeenWallMs < oldest.lastSeenWallMs) {
                oldest = t;
            }
        }
        if (oldest != null) tracks.remove(oldest);
    }

    private static float iou(int ax, int ay, int aw, int ah,
                             int bx, int by, int bw, int bh) {
        int x1 = Math.max(ax, bx);
        int y1 = Math.max(ay, by);
        int x2 = Math.min(ax + aw, bx + bw);
        int y2 = Math.min(ay + ah, by + bh);
        int interW = Math.max(0, x2 - x1);
        int interH = Math.max(0, y2 - y1);
        int inter = interW * interH;
        int union = aw * ah + bw * bh - inter;
        return union > 0 ? (float) inter / union : 0f;
    }

    /**
     * Space-aware IoU between a Track's last stored QUADRANT-SPACE bbox and an
     * incoming detection's quadrant-space box (audit R5-6, completing audit R3b
     * Ext-8): with the engine passing affine-mapped quadrant-space boxes, both
     * sides live in the same constant 320×240 physical reference frame, so a
     * mosaic↔foveated flip is a genuine no-op here (the R3b per-own-dims form
     * still broke across flips because the foveated window is a moving
     * sub-region of the quadrant). The per-own-dims normalization is kept as
     * the minimal diff — an exact identity for constant-dims inputs, and the
     * correct legacy behaviour for callers without a quadrant-space list
     * (qd==d, dims=native).
     */
    private static float iouNorm(Track t, Detection qd, int qQuadW, int qQuadH) {
        if (t.qLastQuadW <= 0 || t.qLastQuadH <= 0 || qQuadW <= 0 || qQuadH <= 0) {
            // Missing dims — fall back to raw-space IoU (legacy behaviour).
            return iou(t.qLastX, t.qLastY, t.qLastW, t.qLastH,
                       qd.getX(), qd.getY(), qd.getW(), qd.getH());
        }
        return iouF((float) t.qLastX / t.qLastQuadW, (float) t.qLastY / t.qLastQuadH,
                    (float) t.qLastW / t.qLastQuadW, (float) t.qLastH / t.qLastQuadH,
                    (float) qd.getX() / qQuadW, (float) qd.getY() / qQuadH,
                    (float) qd.getW() / qQuadW, (float) qd.getH() / qQuadH);
    }

    /** Float-precision IoU for normalized [0,1] boxes (audit R3b Ext-8). */
    private static float iouF(float ax, float ay, float aw, float ah,
                              float bx, float by, float bw, float bh) {
        float x1 = Math.max(ax, bx);
        float y1 = Math.max(ay, by);
        float x2 = Math.min(ax + aw, bx + bw);
        float y2 = Math.min(ay + ah, by + bh);
        float interW = Math.max(0f, x2 - x1);
        float interH = Math.max(0f, y2 - y1);
        float inter = interW * interH;
        float union = aw * ah + bw * bh - inter;
        return union > 0f ? inter / union : 0f;
    }

    /** Per-Actor mutable state. */
    private static final class Track {
        final long actorId;
        final ClassGroup classGroup;
        int quadrant;
        // Cross-quadrant track ID (from CrossQuadrantTracker). When non-zero,
        // this Actor is bound to a cross-camera identity that survives quadrant
        // boundaries. The merge hint in update() lets us look up an existing
        // Actor by xqTrackId regardless of which quadrant it currently lives
        // in — fixes the "person walks front→right gets two actorIds" bug.
        int xqTrackId = 0;

        long firstSeenWallMs = 0;
        long lastSeenWallMs = 0;
        long firstSeenRelMs = -1;
        long lastSeenRelMs = -1;

        int lastX, lastY, lastW, lastH;
        int lastQuadW = 0, lastQuadH = 0;
        // (audit R5-6) QUADRANT-SPACE twin of lastX/Y/W/H + its reference frame
        // dims (constant 320×240 from the engine; native dims for legacy
        // callers). All cross-observation comparisons (matching IoU, stability,
        // trend, everMoved) read these; the native lastX/Y/W/H stay paired with
        // lastQuadW/H for ThumbnailBuffer/toActor coherence.
        int qLastX, qLastY, qLastW, qLastH;
        int qLastQuadW = 0, qLastQuadH = 0;
        int cameraMask = 0;

        // History for trend / static — stored NORMALIZED in QUADRANT space
        // (narea = qArea/(qQuadW*qQuadH), ncx = qcx/qQuadW, ncy = qcy/qQuadH)
        // so a mosaic↔foveated crop flip inside the window can't manufacture a
        // trend (audit R5-6, was per-own-native-dims under audit R3b Ext-8).
        // Same representation the everMoved anchor uses.
        final float[] areaHistory = new float[TREND_WINDOW];
        final float[] cxHistory = new float[TREND_WINDOW];
        final float[] cyHistory = new float[TREND_WINDOW];
        int historyCount = 0;
        int stableFrames = 0;
        // True once this track ever showed real translation (coherent trend or a
        // centroid jump beyond EVER_MOVED_CENTROID_PX). Used ONLY for the
        // timeline-static inference of a NON-PERSON actor: a car that never moved
        // is treated static for the SRT/markers even if it never latched the
        // severity-path isStatic (sparse cadence). Latch-once, never cleared.
        boolean everMoved = false;
        // First-observation anchor for the everMoved net-displacement test, stored
        // NORMALIZED to [0,1] of the quadrant so the test is scale-invariant: the
        // engine feeds update() bboxes in TWO pixel spaces (mosaic 320×240 and
        // foveated 640×640) for the SAME persistent track, and a raw-pixel net test
        // would cross scales and mis-fire. haveAnchor gates the sentinel.
        boolean haveAnchor = false;
        int anchorQuadrant = -1;   // quadrant the everMoved anchor was captured in
        // Number of frames on which the everMoved net-displacement test ACTUALLY
        // ran (anchor present, same quadrant). The timeline-static gate requires
        // this >=1 — mosaicFrameCount alone over-counts anchor-(re)seed frames
        // where no displacement was measured (cross-quadrant mover re-seeds every
        // quadrant change), which would wrongly infer a real mover static.
        int everMovedTestFrames = 0;
        float firstNcx = 0f, firstNcy = 0f, firstNarea = 0f;
        // Count of MOSAIC-frame observations. The everMoved latch + timeline-static
        // inference are mosaic-only (the foveated window pans, so its coords can't
        // measure real displacement). The net-displacement test needs >=2 mosaic
        // frames to run (the 1st only sets the anchor), so a track with <2 mosaic
        // frames has NO valid stillness evidence and the timeline-static inference
        // must fail OPEN (treat as moving) — otherwise a lateral mover seen mostly
        // on foveated frames + one mosaic anchor is wrongly inferred static.
        int mosaicFrameCount = 0;
        // Consecutive over-band area-change frames, for the everMoved area latch
        // (requires 2 in a row so a single YOLO bbox wobble can't latch it).
        int areaOverBandFrames = 0;

        // Peak severity bookkeeping
        Severity peakSeverity = Severity.NOTICE;
        long peakSeverityWallMs = 0;
        long peakSeverityRelMs = -1;
        Proximity peakProximity = Proximity.UNKNOWN;
        // Frames observed AT the current peakProximity (1 on upgrade, ++ on dwell).
        // The proximity-consistent severity re-derivation requires >=2 so a
        // single-frame VERY_CLOSE/CLOSE spike (the exact flicker MIN_ESCALATION_FRAMES
        // guards against) can't manufacture CRITICAL/ALERT via the lifetime latch.
        int peakProxFrames = 0;
        int feetLowStreak = 0;
        float peakConfidence = 0f;
        int peakBboxX, peakBboxY, peakBboxW, peakBboxH;
        // Crop dimensions peakBbox was measured against — see Actor.peakBboxQuadW/H.
        int peakBboxQuadW, peakBboxQuadH;
        int peakCamera;

        // Dwell at current peak proximity
        long peakProxStartWallMs = 0;

        Track(long id, ClassGroup g, int quadrant) {
            this.actorId = id;
            this.classGroup = g;
            this.quadrant = quadrant;
            this.peakCamera = quadrant;
        }

        void observe(Detection d, Detection qd, int newQuadrant,
                     int quadW, int quadH, int qQuadW, int qQuadH,
                     long recordingStartWallMs, long wallNowMs) {
            // (audit R5-6) CROSS-CAMERA HANDOFF RESET. A Path-A cross-quadrant
            // hint match re-points this Track to a DIFFERENT physical camera;
            // the trend/stability histories then span two incomparable lens
            // geometries, and the first cross-camera dCy/area step manufactures
            // APPROACHING (or resets/holds stableFrames on noise). Clear them
            // before observing — mirrors the everMoved anchor's quadrant-change
            // re-seed idiom below. historyCount=0 is sufficient to retire the
            // ring buffers (computeTrend never reads slots >= historyCount, and
            // post-reset writes start at slot 0 before any wrap).
            final boolean handoff = lastSeenWallMs != 0 && newQuadrant != quadrant;
            if (handoff) {
                historyCount = 0;
                stableFrames = 0;
                feetLowStreak = 0;
            }

            if (firstSeenWallMs == 0) {
                firstSeenWallMs = wallNowMs;
                if (recordingStartWallMs > 0) {
                    firstSeenRelMs = wallNowMs - recordingStartWallMs;
                }
            }
            lastSeenWallMs = wallNowMs;
            lastSeenRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;

            quadrant = newQuadrant;
            cameraMask |= (1 << (newQuadrant & 0x03));
            lastQuadW = quadW;
            lastQuadH = quadH;
            // (audit R5-6) Snapshot the PREVIOUS observation's quadrant-space
            // frame dims before overwriting — the stability check below must
            // normalize the previous box by ITS OWN reference dims (constant
            // 320×240 from the engine; native for legacy callers).
            int prevQQuadW = qLastQuadW, prevQQuadH = qLastQuadH;
            qLastQuadW = qQuadW;
            qLastQuadH = qQuadH;

            int x = d.getX();
            int y = d.getY();
            int w = d.getW();
            int h = d.getH();

            // (audit R5-6) Quadrant-space geometry — the single constant
            // reference frame ALL cross-observation math below uses (stability,
            // trend history, everMoved). The native x/y/w/h above are only
            // stored/latched for consumers that pair them with their own dims
            // (lastX/Y/W/H, peakBbox*) and for per-frame proximity.
            int qx = qd.getX();
            int qy = qd.getY();
            int qw = qd.getW();
            int qh = qd.getH();

            float prevQArea = qLastW > 0 ? (float)(qLastW * qLastH) : 0f;
            float curQArea = (float)(qw * qh);

            int qcx = qx + qw / 2;
            int qcy = qy + qh / 2;

            // Stability check (against previous observation, not full history).
            // (audit R5-6) Compared in QUADRANT space: the engine affine-maps
            // foveated boxes into the constant 320×240 quadrant frame before
            // update(), so a mosaic↔foveated crop flip is a genuine no-op here.
            // (The R3b Ext-8 per-own-dims normalization was NOT flip-immune: the
            // foveated window is a moving ~160×160 sub-region of the quadrant,
            // so normalized area still stepped ~3× on every flip and reset
            // stableFrames on a genuinely still object.) The normalized-space
            // formulation is kept as the minimal diff — with constant 320×240
            // inputs the thresholds are exactly the original 10 px, and legacy
            // callers (qd==d, dims=native) keep the R3b behaviour. Skipped on a
            // cross-camera handoff: the previous box is another lens's geometry.
            if (!handoff && qLastW > 0
                    && prevQQuadW > 0 && prevQQuadH > 0 && qQuadW > 0 && qQuadH > 0) {
                float prevNarea = prevQArea / ((float) prevQQuadW * prevQQuadH);
                float curNarea = curQArea / ((float) qQuadW * qQuadH);
                float drift = prevNarea > 0 ? Math.abs(curNarea - prevNarea) / prevNarea : 1f;
                float dNcx = Math.abs((float) qcx / qQuadW
                        - (float) (qLastX + qLastW / 2) / prevQQuadW);
                float dNcy = Math.abs((float) qcy / qQuadH
                        - (float) (qLastY + qLastH / 2) / prevQQuadH);
                if (drift < STATIC_AREA_DRIFT_FRAC
                        && dNcx < STATIC_CENTROID_DRIFT_NX
                        && dNcy < STATIC_CENTROID_DRIFT_NY) {
                    if (stableFrames < Integer.MAX_VALUE - 1) stableFrames++;
                } else {
                    stableFrames = 0;
                }
            }
            // EVER-MOVED latch (timeline-static inference) — uses CUMULATIVE NET
            // displacement from the FIRST observation, not per-step deltas. This
            // is the right discriminator between a real mover and a jittering
            // parked car: a parked car's centroid oscillates around a fixed point
            // and its bbox area wobbles around a fixed size (net ≈ 0 over its
            // lifetime, even if a single noisy step exceeds the per-step jitter
            // band), whereas a creeping/approaching vehicle travels monotonically
            // (net centroid travel OR net area growth accumulates without bound).
            //
            // Per-step latching was wrong both ways: a >=18px-only step test MISSED
            // a slow lateral creeper in the [10,18px)/step band (the FN the audit
            // found), while latching on ANY non-stable step would FALSELY trip on a
            // single YOLO-box jitter of a truly-parked car and re-leak it into the
            // timeline (the user's original bug). Net-from-origin closes both:
            //  - lateral creep: |cx-firstCx| grows past EVER_MOVED_CENTROID_PX.
            //  - head-on approach: area grows past (1+frac) of first area (centroid
            //    barely moves, so the centroid test alone would miss it).
            //  - parked car (any jitter): net centroid stays within the radius and
            //    net area stays within the band → never latches.
            // Latch-once, never cleared. Severity path (computeTrend) untouched.
            //
            // QUADRANT-FRAME-ONLY: this latch (and the timeline-static inference
            // it feeds) needs coords that reference the STABLE full quadrant —
            // net-displacement in the moving foveated window's own space is
            // meaningless (the window re-centers on the motion centroid, so a
            // real mover's window-local centroid stays ~fixed).
            // (audit R5-6) The gate tests the quadrant-space reference dims: with
            // the engine's affine-mapped list every frame is quadrant-referenced
            // (320×240), so foveated frames now legitimately feed the test too —
            // an improvement over the old mosaic-only sampling (more evidence per
            // track, same physical meaning). Legacy callers without the parallel
            // list keep the old behaviour exactly (qQuadW=native, 640 foveated
            // frames still excluded). The anchor re-seed on quadrant change and
            // the >=2-test-frames gating semantics (everMovedTestFrames /
            // toActor) are unchanged. mosaicFrameCount keeps its name but now
            // counts quadrant-referenced frames.
            boolean mosaicFrame = qQuadW > 0 && qQuadW <= 320 && qQuadH > 0 && qQuadH <= 320;
            if (mosaicFrame) {
                mosaicFrameCount++;
                float ncx = (float) qcx / qQuadW;
                float ncy = (float) qcy / qQuadH;
                float narea = curQArea / ((float) qQuadW * qQuadH);
                if (!haveAnchor || newQuadrant != anchorQuadrant) {
                    // (Re)seed the anchor in THIS quadrant's local [0,1] space. A
                    // cross-quadrant-bound Track (same xqTrackId across cameras)
                    // would otherwise compare a Q0-local anchor against a Q1-local
                    // centroid and spuriously latch everMoved for a seam-straddling
                    // parked object. Net-displacement is only meaningful within one
                    // quadrant's frame.
                    haveAnchor = true;
                    anchorQuadrant = newQuadrant;
                    firstNcx = ncx; firstNcy = ncy; firstNarea = narea;
                    areaOverBandFrames = 0;
                } else {
                    // The net-displacement test ACTUALLY RAN this frame (anchor
                    // present, same quadrant). Count it so the timeline-static gate
                    // can require real evidence: mosaicFrameCount alone is wrong
                    // because it increments even on anchor-(re)seed frames where no
                    // displacement was measured (the cross-quadrant case where a
                    // mover re-seeds on every quadrant change → mosaicFrameCount
                    // climbs but the test never ran → wrongly inferred static).
                    everMovedTestFrames++;
                  if (!everMoved) {
                    // Net displacement in NORMALIZED units; thresholds as fractions
                    // of the mosaic quadrant (320×240).
                    float netNcx = Math.abs(ncx - firstNcx);
                    float netNcy = Math.abs(ncy - firstNcy);
                    boolean centroidTravelled =
                            netNcx >= EVER_MOVED_CENTROID_PX / 320f
                            || netNcy >= EVER_MOVED_CENTROID_PX / 240f;
                    // Two-sided: net area GROWTH (approaching) OR SHRINK (receding)
                    // past the jitter band is movement. Growth-only missed a
                    // modestly-receding vehicle whose bbox shrinks but whose
                    // centroid drifts DOWN — computeTrend reads STABLE (RECEDING
                    // needs dCy<=-5), so without the shrink side it was wrongly
                    // inferred static and dropped from the timeline.
                    boolean areaChanged = firstNarea > 0
                            && (narea > firstNarea * (1f + STATIC_AREA_DRIFT_FRAC)
                                || narea < firstNarea * (1f - STATIC_AREA_DRIFT_FRAC));
                    // Centroid travel is a clean signal → latch immediately. Area
                    // change is jittery (YOLO bbox wobble on a parked car can spike
                    // >10% for one frame), so require 2 CONSECUTIVE over-band area
                    // frames before latching — mirrors the stableFrames
                    // consecutive-evidence idiom. A genuinely approaching/receding
                    // vehicle accrues the 2 frames immediately; a one-off box wobble
                    // on a parked car no longer re-leaks it into the timeline.
                    if (centroidTravelled) {
                        everMoved = true;
                    } else if (areaChanged) {
                        if (++areaOverBandFrames >= 2) everMoved = true;
                    } else {
                        areaOverBandFrames = 0;
                    }
                  }
                }
            }

            lastX = x; lastY = y; lastW = w; lastH = h;
            // (audit R5-6) quadrant-space twin — read by iouNorm + next frame's
            // stability check.
            qLastX = qx; qLastY = qy; qLastW = qw; qLastH = qh;

            // Roll history — QUADRANT-SPACE, normalized by the quadrant reference
            // dims (audit R5-6; was per-own-native-dims under R3b Ext-8, which
            // still let a crop flip inside the 6-slot window read as a ~3× area
            // step + a window-origin dCy in computeTrend, manufacturing
            // APPROACHING/RECEDING on a stationary object — pinning ALERT via the
            // lifetime severity latch and blocking isStaticForTimeline). With the
            // engine's affine-mapped inputs all six slots share one physical
            // reference frame, so a flip contributes zero artificial step.
            int slot = historyCount % TREND_WINDOW;
            areaHistory[slot] = (qQuadW > 0 && qQuadH > 0)
                    ? curQArea / ((float) qQuadW * qQuadH) : 0f;
            cxHistory[slot] = qQuadW > 0 ? (float) qcx / qQuadW : 0f;
            cyHistory[slot] = qQuadH > 0 ? (float) qcy / qQuadH : 0f;
            historyCount++;

            // Compute proximity from bbox dimension relative to quadrant dim.
            // For people use height (taller-than-wide); for vehicles use width.
            // (audit R7 ExtC-7) QUADRANT-SPACE ratio, matching the threshold
            // constants' own doc ("foveated path is rescaled to quadrant
            // first"): the native-dims ratio was self-consistent within a
            // frame but MAGNIFICATION-biased — the foveated window covers a
            // ~160×160 sub-region of the 320×240 quadrant, inflating the
            // person ratio 1.5× and the vehicle ratio 2×, crossing proximity
            // tiers (MID→CLOSE, CLOSE→VERY_CLOSE) and lifetime-latching
            // escalated severity via peakProximity. Mosaic frames are
            // byte-identical (qd==d, dims equal); legacy callers without the
            // quadrant-space list likewise unchanged.
            float ratio;
            if (classGroup == ClassGroup.VEHICLE) {
                ratio = qQuadW > 0 ? (float) qw / qQuadW : 0f;
            } else {
                ratio = qQuadH > 0 ? (float) qh / qQuadH : 0f;
            }
            Proximity prox = ratioToProximity(ratio);
            if (classGroup == ClassGroup.PERSON) {
                boolean feetLow = prox != Proximity.UNKNOWN && qQuadH > 0
                        && (float) (qy + qh) / qQuadH >= PROX_FEET_VERY_CLOSE;
                feetLowStreak = feetLow ? feetLowStreak + 1 : 0;
                if (feetLowStreak >= FEET_LOW_FRAMES_NEEDED) prox = Proximity.VERY_CLOSE;
            }

            // Update peak proximity (smaller ordinal = closer)
            if (peakProximity == Proximity.UNKNOWN
                    || prox.ordinal() < peakProximity.ordinal()) {
                peakProximity = prox;
                peakProxStartWallMs = wallNowMs;
                // Refresh peakBbox + its crop space whenever proximity
                // upgrades (got closer). The thumbnail capture rule is
                // "the moment threat was highest", and a closer actor
                // is more threatening even if the severity tier hasn't
                // changed. Without this, ThumbnailBuffer would see a
                // score increase (proximity bumped) but the actor's
                // peakBbox would still be in the OLD frame's crop space
                // — and the bbox-vs-rgb alignment guard would refuse
                // to update the slot, leaving a stale crop on disk.
                peakBboxX = x; peakBboxY = y; peakBboxW = w; peakBboxH = h;
                peakBboxQuadW = quadW;
                peakBboxQuadH = quadH;
                // Stamp the bbox-latch time to THIS frame. peakSeverityWallMs is
                // the "peak moment" timestamp ThumbnailBuffer uses to verify the
                // hero's rgb and bbox come from the SAME frame (its coherence
                // gate). This branch re-points peakBbox on a proximity upgrade
                // WITHOUT a severity change, so without this stamp the timestamp
                // would lag the bbox: the hero score later improves (toActor
                // re-derives severity from the lifetime peakProximity) on a frame
                // whose rgb no longer matches this now-stale bbox, and the box is
                // drawn where the actor USED to be — the "box misses the actor"
                // bug. The dwell-refresh + severity-upgrade branches already stamp
                // it for the same reason; keep all three latch sites consistent.
                peakSeverityWallMs = wallNowMs;
                peakSeverityRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;
                // Without this, a person crossing front → right quadrant
                // whose proximity bumped but severity stayed at ALERT
                // would have peakBbox set to right-camera coords but
                // peakCamera stuck on front. ThumbnailBuffer.observe
                // gates on `a.peakCamera != camera` and would reject the
                // right-frame, leaving the hero stuck on the older,
                // less-close moment from the front camera.
                peakCamera = newQuadrant;
                peakProxFrames = 1;
            } else if (prox == peakProximity) {
                // continue dwell
                peakProxFrames++;
                // DWELL BBOX REFRESH: re-point peakBbox/crop/camera/time to THIS
                // (later) frame while the actor stays at its peak proximity tier.
                // Previously peakBbox froze on the FIRST frame that reached this
                // tier, so a moving actor (walking past at constant distance, or
                // crossing the frame while still CLOSE) got a hero box pinned to
                // where they WERE on first touch — the "delayed + wrong position"
                // bug. This only re-points the latch to a fresher frame at the
                // SAME threat tier; it never raises severity and never changes
                // hero SELECTION (the ThumbnailBuffer score is unchanged). Pairs
                // with the dwell-refresh recapture in ThumbnailBuffer.observe so
                // the hero's rgb and bbox stay from the SAME frame (coherent).
                //
                // QUALITY GATE — adaptive floor, NOT ">= peakConfidence". The old
                // ">= peak" gate NEVER re-pointed when the peak latched on the
                // actor's highest-confidence frame (the common case: a person is
                // most confidently detected on first clear sight, e.g. 0.90, then
                // YOLO confidence naturally decays as they turn/recede — 0.84,
                // 0.77, 0.76). Every later frame failed `conf >= 0.90`, so the box
                // froze at first-touch while the person walked on — the EXACT
                // on-car bug (hero box on empty ground, person already metres
                // away). Instead, advance the bbox on any frame that is still a
                // SOLID detection: at least DWELL_REFRESH_CONF_FRAC of the peak
                // AND an absolute floor. That tracks the natural-decay case while
                // still rejecting a degenerate exit frame (bbox clipped at the
                // frame edge collapses confidence toward the YOLO threshold).
                //
                // peakConfidence stays the running MAX (Math.max), NOT this
                // frame's value: it is the cross-actor hero SCORE tiebreaker
                // (ThumbnailBuffer.score) and the anchor this very gate measures
                // against. Lowering it would (a) let the actor lose hero selection
                // to another mid-dwell and (b) move the goalposts so a slow
                // confidence slide ratchets the floor down frame by frame. Holding
                // the max keeps the score stable so ThumbnailBuffer's equal-score
                // dwell-refresh branch fires and re-pairs THIS frame's rgb with the
                // freshened bbox (coherent hero), while peakSeverityWallMs advances
                // to mark the fresher frame.
                float dwellFloor = Math.max(
                        peakConfidence * DWELL_REFRESH_CONF_FRAC,
                        DWELL_REFRESH_CONF_ABS_MIN);
                if (d.getConfidence() >= dwellFloor) {
                    peakBboxX = x; peakBboxY = y; peakBboxW = w; peakBboxH = h;
                    peakBboxQuadW = quadW;
                    peakBboxQuadH = quadH;
                    peakCamera = newQuadrant;
                    peakConfidence = Math.max(peakConfidence, d.getConfidence());
                    peakSeverityWallMs = wallNowMs;
                    peakSeverityRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;
                }
            } else {
                // moved further; reset dwell
                peakProxStartWallMs = wallNowMs;
            }

            long dwellMs = wallNowMs - peakProxStartWallMs;
            int staticThreshold = (classGroup == ClassGroup.VEHICLE)
                    ? STATIC_FRAMES_NEEDED_VEHICLE : STATIC_FRAMES_NEEDED;
            Severity sev = SeverityClassifier.classify(classGroup, prox, peakProximity,
                    computeTrend(), stableFrames >= staticThreshold, dwellMs);

            // FLICKER / MISCLASSIFICATION GUARD: don't let a track escalate above
            // NOTICE until it has been confirmed across MIN_ESCALATION_FRAMES
            // observations. A 1-2 frame track is almost always a YOLO flicker or a
            // one-frame false class (parked car boxed as a person); since peak
            // severity is a monotone lifetime latch and classGroup is final, a
            // single such frame would otherwise pin ALERT/CRITICAL + a wrong class
            // for the whole event. historyCount was incremented above, so it is the
            // observation count INCLUDING this frame.
            if (historyCount < MIN_ESCALATION_FRAMES && sev.ordinal() > Severity.NOTICE.ordinal()) {
                sev = Severity.NOTICE;
            }

            // Track peak severity moment for thumbnail capture
            boolean upgradeSev = sev.ordinal() > peakSeverity.ordinal();
            boolean tieBetterConf = sev == peakSeverity && d.getConfidence() > peakConfidence;
            if (upgradeSev || tieBetterConf) {
                peakSeverity = sev;
                peakSeverityWallMs = wallNowMs;
                peakSeverityRelMs = recordingStartWallMs > 0 ? wallNowMs - recordingStartWallMs : -1;
                peakConfidence = d.getConfidence();
                peakBboxX = x; peakBboxY = y; peakBboxW = w; peakBboxH = h;
                // Snapshot the crop dims THIS frame's bbox is in. Without
                // these, downstream consumers (ThumbnailBuffer, baseline
                // promotion) can't tell whether to interpret the bbox in
                // 320×240 mosaic or 640×640 foveated coords.
                peakBboxQuadW = quadW;
                peakBboxQuadH = quadH;
                peakCamera = newQuadrant;
            }
        }

        private Trend computeTrend() {
            if (historyCount < 2) return Trend.UNKNOWN;
            // newest is at slot (historyCount-1) % TREND_WINDOW, oldest at historyCount % TREND_WINDOW
            int newest = (historyCount - 1) % TREND_WINDOW;
            int oldest = historyCount >= TREND_WINDOW ? historyCount % TREND_WINDOW : 0;
            float a0 = areaHistory[oldest];
            float a1 = areaHistory[newest];
            if (a0 <= 0) return Trend.UNKNOWN;
            float change = (a1 - a0) / a0;
            // Direction sanity check — avoids false APPROACHING when a stationary
            // object's bbox is repeatedly reshaped by an occluder (e.g. a person
            // walking past a parked car). Real approach: bbox grows AND its
            // bottom edge drifts down (or its centroid drifts down for ground
            // objects). Occlusion noise: bbox grows but centroid jitters with
            // no net direction. We require coherent vertical motion >= 5 px
            // (of the 240-high mosaic quadrant).
            // (audit R5-6) History is stored in QUADRANT space normalized by the
            // constant 320×240 reference dims, so the ±10% area RATIO and the dCy
            // threshold (old 5 px → 5/240 of quadrant height) compare like with
            // like across every mosaic↔foveated flip — no window-origin artifact.
            float dCy = cyHistory[newest] - cyHistory[oldest];
            if (change > 0.10f && dCy >= TREND_DCY_NORM) return Trend.APPROACHING;
            if (change < -0.10f && dCy <= -TREND_DCY_NORM) return Trend.RECEDING;
            return Trend.STABLE;
        }

        private static Proximity ratioToProximity(float ratio) {
            if (ratio <= 0f) return Proximity.UNKNOWN;
            if (ratio >= PROX_VERY_CLOSE) return Proximity.VERY_CLOSE;
            if (ratio >= PROX_CLOSE)      return Proximity.CLOSE;
            if (ratio >= PROX_MID)        return Proximity.MID;
            return Proximity.FAR;
        }

        Actor toActor() {
            // current proximity = recompute from last frame so toActor is internally consistent
            // (audit R7 ExtC-7) quadrant-space, mirroring observe()'s ratio —
            // the native-dims form inflated foveated frames' proximity.
            float ratio;
            if (classGroup == ClassGroup.VEHICLE) {
                ratio = qLastQuadW > 0 ? (float) qLastW / qLastQuadW : 0f;
            } else {
                ratio = qLastQuadH > 0 ? (float) qLastH / qLastQuadH : 0f;
            }
            Proximity lastProx = classGroup == ClassGroup.PERSON && feetLowStreak >= FEET_LOW_FRAMES_NEEDED
                    ? Proximity.VERY_CLOSE
                    : ratioToProximity(ratio);
            int staticThreshold = (classGroup == ClassGroup.VEHICLE)
                    ? STATIC_FRAMES_NEEDED_VEHICLE : STATIC_FRAMES_NEEDED;
            boolean isStatic = stableFrames >= staticThreshold;

            // PROXIMITY-CONSISTENT SEVERITY. The per-frame peakSeverity latch
            // (in observe) classifies against that frame's instantaneous prox,
            // so an actor whose CLOSEST frame happened to fall inside the first
            // MIN_ESCALATION_FRAMES flicker window — or whose proximity peaked on
            // a frame whose live prox had already receded — keeps peakSeverity at
            // NOTICE while peakProximity (latched unconditionally) reads CLOSE.
            // That produced the on-car contradiction: a "close" person tagged
            // "Notice" with a WHITE hero box (box colour is derived from
            // severity). Re-derive severity from the lifetime peakProximity so
            // the severity, the proximity tag, and the box colour all agree.
            //
            // Same SeverityClassifier rules (single source of truth), so all the
            // FP guards still hold: a static non-person stays NOTICE (parked car),
            // vehicles need APPROACHING, and the escalation only fires once the
            // actor is CONFIRMED (>= MIN_ESCALATION_FRAMES lifetime observations)
            // — a 1-2 frame YOLO flicker can't manufacture an ALERT. trend uses
            // the live computeTrend(); for a person CLOSE/VERY_CLOSE the rules
            // don't depend on trend, so a receded-but-was-close person still
            // escalates, which is the intent.
            // Re-derive severity from the lifetime peakProximity. We do NOT
            // repoint the hero bbox/timestamp here: the per-frame proximity-
            // upgrade latch (observe, ~:378) already set peakBbox* to the
            // closest-approach frame, and ThumbnailBuffer captures the coherent
            // (rgb, bbox) pair live at that frame. Anchoring is handled there;
            // toActor only carries the corrected scalar severity (which drives
            // the JSON stats, the tags, the caption, and — via a same-actor
            // severity bump in ThumbnailBuffer — the hero box colour).
            Severity effSeverity = peakSeverity;
            Trend trend = computeTrend();
            // PERSON-ONLY: the motivating bug was a CLOSE/VERY_CLOSE PERSON
            // mis-tagged NOTICE (white box). PERSON severity is trend-independent
            // (SeverityClassifier: CLOSE->ALERT, VERY_CLOSE->CRITICAL), so
            // re-deriving from the lifetime peakProximity is safe and correct.
            // For VEHICLE/BIKE the classifier requires APPROACHING, and pairing a
            // STALE lifetime peakProximity with a LIVE trend would manufacture an
            // ALERT for an occlusion-jittered parked car (defeating the
            // eventPeakActors retain guard) — so vehicles/bikes keep their
            // co-occurrence-gated per-frame peakSeverity (HEAD behavior).
            // peakProxFrames>=2 also blocks a single-frame proximity spike from
            // resurrecting CRITICAL past the MIN_ESCALATION_FRAMES flicker guard.
            //
            // DECISION (user): when a CONFIRMED person's displayed proximity is
            // closer than their gated severity, RAISE the severity to match — so
            // the badge + box colour agree with the "very close"/"close" tag. The
            // earlier peakProxFrames>=2 dwell gate is REMOVED: it was the cause of
            // the on-car "👤 very close + Notice + white box" card — a person who
            // reached VERY_CLOSE on a single sparse-YOLO frame latched the
            // proximity tag unconditionally (:512) but, with peakProxFrames stuck
            // at 1, never got the severity re-derived. historyCount>=MIN_ESCALATION
            // _FRAMES still guards against a 1-2 frame YOLO flicker manufacturing a
            // CRITICAL, so a phantom one-frame "person" can't escalate; but a
            // genuinely-confirmed person who was momentarily close now escalates to
            // match what the UI shows. PERSON severity is trend-independent in
            // SeverityClassifier (CLOSE->ALERT, VERY_CLOSE->CRITICAL) so this is
            // safe; only ever RAISES (max), never lowers.
            if (classGroup == ClassGroup.PERSON
                    && historyCount >= MIN_ESCALATION_FRAMES
                    && peakProximity != Proximity.UNKNOWN) {
                Severity proxSev = SeverityClassifier.classify(
                        classGroup, peakProximity, peakProximity,
                        trend, isStatic, 0L);
                if (proxSev.ordinal() > effSeverity.ordinal()) {
                    effSeverity = proxSev;
                }
            }

            // TIMELINE-STATIC verdict (superset of isStatic for NON-PERSON only).
            // The severity-path isStatic needs consecutive stable frames that
            // sparse YOLO cadence rarely delivers, so a genuinely parked car often
            // reads non-static there. For the cosmetic timeline/markers/chip, also
            // treat a confirmed non-person that NEVER moved (no centroid jump, no
            // coherent approach/recede) as static. PERSON is NEVER inferred-static
            // from stillness — a standing loiterer must keep its timeline entry
            // (EventTimelineCollector's isStatic skip has no person exemption), so
            // for PERSON this is exactly isStatic. Requires historyCount>=2 so a
            // single first-appearance frame (trend not yet resolved) can't
            // prematurely mark an approaching car static.
            boolean isStaticForTimeline = isStatic;
            if (classGroup != ClassGroup.PERSON
                    && effSeverity == Severity.NOTICE   // never timeline-suppress a non-person that ESCALATED (ALERT motorcycle etc.)
                    && historyCount >= MIN_ESCALATION_FRAMES   // require confirmed evidence, not a 2-obs flicker
                    && everMovedTestFrames >= 1   // the everMoved net-displacement test actually RAN (not just anchor/re-seed frames)
                    && !everMoved
                    && trend != Trend.APPROACHING
                    && trend != Trend.RECEDING) {
                isStaticForTimeline = true;
            }

            return new Actor(actorId, classGroup,
                    firstSeenWallMs, lastSeenWallMs,
                    firstSeenRelMs, lastSeenRelMs,
                    cameraMask,
                    peakProximity, lastProx,
                    trend, isStatic, isStaticForTimeline,
                    // everMovedTested requires >=2 test frames, NOT >=1: the
                    // everMoved area-growth latch needs areaOverBandFrames>=2
                    // (two consecutive over-band mosaic frames), so after only
                    // ONE test frame everMoved provably cannot have latched yet —
                    // asserting "stillness measured" then would let the
                    // isLowConfFarNotice gate suppress a head-on approacher seen on
                    // exactly 2 mosaic frames (anchor + 1 test) whose area latch
                    // hadn't fired. Requiring 2 test frames means both the centroid
                    // and the 2-frame area paths have had their chance before we
                    // trust !everMoved. Still fails OPEN for foveated-only / single-
                    // test-frame tracks. (The sibling isStaticForTimeline uses
                    // everMovedTestFrames>=1 directly but is additionally gated by
                    // historyCount>=MIN_ESCALATION_FRAMES, so it never trusted a
                    // 2-frame track; the new gates have no such floor.)
                    everMoved, everMovedTestFrames >= 2,
                    historyCount >= MIN_ESCALATION_FRAMES,
                    effSeverity, peakSeverityWallMs, peakSeverityRelMs,
                    peakConfidence,
                    peakBboxX, peakBboxY, peakBboxW, peakBboxH,
                    peakBboxQuadW, peakBboxQuadH, peakCamera,
                    lastX, lastY, lastW, lastH,
                    quadrant);
        }
    }
}
