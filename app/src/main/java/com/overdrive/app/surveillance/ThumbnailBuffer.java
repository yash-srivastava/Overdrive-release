package com.overdrive.app.surveillance;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.DaemonFonts;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThumbnailBuffer — Captures the highest-severity frame per Actor over the life
 * of a recording, then writes JPEG thumbnails next to the MP4 when the recording
 * closes.
 *
 * Score tuple per slot, most significant first:
 * (severity ordinal, live-vs-static, class rank, proximity rank, confidence).
 * Higher tuple wins; new observations only overwrite the slot's PIXELS when their
 * tuple beats the existing one, so the saved JPEG is the peak-threat moment, not
 * the first or last detection.
 *
 * <p>The live-vs-static term sits above class rank so a moving actor of ANY class
 * outranks stationary scenery at the same severity — a live animal beats a parked
 * car, and a live car beats a motionless animal. Staticness itself is a property
 * of the ACTOR, not of the captured frame, so it is refreshed on every observation
 * independently of whether the pixels are re-captured. See {@link #observe}.
 *
 * Memory bound: one slot per active actorId, one 640×640 RGB byte[] each
 * (~1.2 MB). Worst case at MAX_TRACKS=32 ≈ 38 MB; in practice 1–4 actors so
 * ~5 MB during a recording. All slots are dropped when the recording closes.
 */
public final class ThumbnailBuffer {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ThumbBuf");

    /** Output JPEG side-length. The crop is resized to this from whatever the
     *  source dimensions were (typically 640×640 foveated or 320×240 mosaic). */
    private static final int OUT_SIDE = 640;
    private static final int JPEG_QUALITY = 85;

    /** Package-visible so the caller-driven per-actor writer (see
     *  {@link #writePerActorJpeg}) can iterate a snapshot of slots after
     *  the buffer has been cleared synchronously. */
    static final class Slot {
        byte[] rgb;
        int srcW;
        int srcH;
        int bboxX, bboxY, bboxW, bboxH;
        Actor.Severity severity;
        float confidence;
        Actor.Proximity proximity;
        long wallMs;
        // Wall-clock of the actor's PEAK-severity moment (the frame this crop
        // depicts), as opposed to wallMs which is when the slot was last touched.
        // Used to reject a hero whose peak predates the recorded MP4 window
        // (the pre-record ring is bounded, so a peak captured before the clip
        // starts was evicted and is not in the video).
        long peakWallMs;
        Actor.ClassGroup classGroup;
        long actorId;
        int camera;
        // Whether this actor was judged stationary scenery (parked car, etc.) at
        // capture time. Persisted in the Slot because pickHero() re-scores from
        // stored fields long after the Actor object is gone, and the static
        // penalty in score() must survive that round-trip.
        boolean staticNonThreat;

        /** Package-visible accessor for consumers outside this class (the
         *  per-actor JPEG writer skips static scenery — see
         *  SurveillanceEngineGpu's per-segment metadata flush). */
        boolean isStaticNonThreat() { return staticNonThreat; }
    }

    private final Map<Long, Slot> slots = new HashMap<>();

    /**
     * Consecutive observations on which an actor's bbox showed ZERO confirmed
     * motion coverage. Keyed by actorId. Reset to 0 the moment coverage returns,
     * so this is a run-length, not a total. Feeds the staticness hysteresis in
     * {@link #observe}.
     */
    private final Map<Long, Integer> zeroMotionObs = new HashMap<>();

    /**
     * actorIds judged stationary scenery, carried across {@link
     * #drainSnapshotForAsync()} so the post-drain notification/caption threat pick
     * can still consult the verdict. Repopulated at each drain and cleared with
     * everything else in {@link #clear()}.
     */
    private final java.util.Set<Long> lastStaticActorIds = new java.util.HashSet<>();

    /**
     * How many CONSECUTIVE zero-coverage observations before the motion evidence
     * is allowed to declare an actor static. 2 (not 1) so a single mask miss —
     * a frame where a walker paused mid-stride, a brief occlusion, or a decimated
     * motion tick — cannot flip a live actor to static.
     *
     * <p>These are OBSERVATIONS, not a wall-clock window, and the spacing is
     * uneven: back-to-back samples of one quadrant can be ~250-500 ms apart
     * (AI_COOLDOWN_CLOSE_MS / AI_COOLDOWN_MS), but with all four quadrants
     * competing the round-robin can leave a single quadrant unexamined for ~2 s.
     * So 2 observations spans roughly 0.5 s at best and up to ~4 s at worst — the
     * hysteresis is much weaker per unit time than the observation count suggests.
     * That is why zero coverage alone is not trusted: the verdict additionally
     * requires the Actor layer's displacement signals not to contradict stillness
     * (see the corroboration gate in {@link #observe}).
     */
    private static final int STATIC_CONFIRM_OBS = 2;

    /**
     * Hard cap on {@link #zeroMotionObs}. Generously above the tracker's
     * MAX_TRACKS=32 live slots, since fragmentation mints new actorIds within a
     * single event and entries are only cleared at drain/clear.
     */
    private static final int ZERO_MOTION_MAP_CAP = 256;

    /**
     * Hard cap on {@link #slots}. Unlike {@link #ZERO_MOTION_MAP_CAP} this one is
     * about BYTES, not entries: each Slot owns a {@code byte[w*h*3]} crop, which is
     * 640×640×3 = 1.2 MB on the foveated path. 32 slots ≈ 39 MB worst case.
     *
     * <p>Why a cap is needed at all. Slots are only released at
     * {@link #drainSnapshotForAsync()} (segment rotation / recording stop) or
     * {@link #clear()}, but {@link #observe} is NOT gated on `recording` — it runs
     * on every YOLO tick while the engine is merely armed. So in armed-but-quiet
     * sentry mode (motion firing, no trigger) nothing ever drains, and actorIds are
     * monotonic with fragmentation minting far more of them than the tracker's
     * MAX_TRACKS=32 live slots. A multi-hour unattended session could otherwise
     * retain hundreds of 1.2 MB crops — heap pressure, then GC thrash, then OOM,
     * hours in, on a device with no largeHeap and a constrained SoC.
     *
     * <p>Set to the tracker's MAX_TRACKS so the pool can always hold every
     * simultaneously-live actor; anything beyond that is fragmentation debris.
     */
    private static final int MAX_SLOTS = 32;

    /**
     * How recently the tracker must have observed an actor for its {@code lastBbox}
     * to be treated as belonging to the frame whose motion mask we hold. Slightly
     * above one YOLO period (AI_COOLDOWN_MS = 500 ms) so the current tick's
     * observations qualify while a TTL-retained actor (TRACK_TTL_MS = 8 s) does not.
     */
    private static final long SAMPLE_FRESH_MS = 700L;


    /**
     * Does this actor's MOST RECENT bbox overlap any confirmed-motion block?
     *
     * <p>Uses {@code lastBbox*}, NOT {@code peakBbox*}. The mask describes THIS
     * frame, so it must be compared against the observation from this frame.
     * {@code peakBbox} is a lifetime latch that stops tracking the actor once its
     * proximity tier degrades or its confidence falls below the dwell floor
     * (ActorTracker's dwell-refresh conditions), so a laterally-moving car whose
     * confidence decayed would have its FROZEN box compared against blocks lit at
     * its NEW position — zero overlap, and the actor gets declared static while
     * visibly moving. That is the same regression class this file exists to avoid,
     * reached from the opposite direction.
     *
     * <p>Callers MUST satisfy the {@code sampleIsThisFrame} precondition in
     * {@link #observe}, which requires the tracker to have re-latched the peak on
     * this observation. Under that condition {@code lastBbox} and {@code peakBbox}
     * are assigned from the SAME detection in the SAME {@code update()} call, so
     * {@code peakBboxQuadW/H} correctly describes the space {@code lastBbox} is in
     * — which is what makes this mapping verifiable at all, since Actor exports no
     * {@code lastBboxQuadW/H}.
     *
     * <p>Geometry is the same mapping the engine's motion-overlap detection
     * filter applies: the bbox lives in the crop space the actor was detected in
     * (mosaic 320×240 identity, or foveated 640×640 whose affine folds in scale,
     * crop-window origin, per-role flip and APA inset), and the mask lives on the
     * 320×240 / 10×7 block grid. A mirrored role yields a negative scale, so
     * corners are normalised with min/max after mapping.
     *
     * <p>Returns TRUE (live) on any degenerate input — a zero-area bbox, a short
     * mask — so a mapping or plumbing fault can never demote a real mover.
     */
    private static boolean bboxOverlapsMotion(Actor a, float[] blockConf,
                                              float ax, float bx, float ay, float by) {
        if (blockConf == null || blockConf.length < MotionPipelineV2.TOTAL_BLOCKS) return true;
        if (a.lastBboxW <= 0 || a.lastBboxH <= 0) return true;

        float gx0 = ax * a.lastBboxX + bx;
        float gx1 = ax * (a.lastBboxX + a.lastBboxW) + bx;
        float gy0 = ay * a.lastBboxY + by;
        float gy1 = ay * (a.lastBboxY + a.lastBboxH) + by;
        int left   = (int) Math.min(gx0, gx1);
        int right  = (int) Math.max(gx0, gx1);
        int top    = (int) Math.min(gy0, gy1);
        int bottom = (int) Math.max(gy0, gy1);

        final int blockPx = MotionPipelineV2.BLOCK_SIZE;
        for (int bi = 0; bi < MotionPipelineV2.TOTAL_BLOCKS; bi++) {
            if (blockConf[bi] < MotionPipelineV2.BLOCK_MOTION_MIN_CONF) continue;
            int bxPx = (bi % MotionPipelineV2.GRID_COLS) * blockPx;
            int byPx = (bi / MotionPipelineV2.GRID_COLS) * blockPx;
            if (left < bxPx + blockPx && right > bxPx
                    && top < byPx + blockPx && bottom > byPx) {
                return true;
            }
        }

        // UNMAPPED BOTTOM BAND. The grid is GRID_ROWS(7) × BLOCK_SIZE(32) = 224 px
        // tall but a quadrant is 240 px, so the bottom 16 px — the strip CLOSEST to
        // the car — has no blocks at all and can never report motion. A bbox
        // reaching into that band therefore cannot be proven still, so return
        // "live" rather than letting an unmeasurable region masquerade as zero
        // coverage. Rare (the object would have to sit almost entirely in 16 px)
        // but it is exactly the closest-range case, where a false-static verdict is
        // least acceptable.
        if (bottom > MotionPipelineV2.GRID_ROWS * blockPx) return true;

        return false;
    }

    // Pooled scratch buffer for ARGB conversion in writeJpeg. Hero JPEGs are
    // written sequentially during stopRecording, all from foveated crops of
    // identical dimension. Without pooling, each writeJpeg allocates a fresh
    // int[srcW*srcH] (~1.6 MB per 640×640 thumb) and discards it, churning
    // 6-16 MB per recording-stop and triggering GC pauses on the main thread.
    // Held by class because flushToDisk is single-threaded (synchronized).
    private int[] argbScratch = null;

    /**
     * Score tuple for ranking observations. Higher wins.
     *
     * Order of importance:
     *  1. Severity ordinal (NOTICE < ALERT < CRITICAL).
     *  2. LIVE beats STATIC. A stationary non-threat (parked car, a dog that
     *     never moved) is still a legitimate hero when it is the only thing in
     *     the clip, but it must never outrank an actor that actually moved.
     *     Ranked above class so it is CLASS-AGNOSTIC: a live animal beats a
     *     parked car, and a live car beats a motionless animal. Fixes the
     *     observed on-car bug where an event whose timeline listed an ANIMAL
     *     produced a hero showing a parked, non-moving vehicle — both were
     *     NOTICE, so the tie fell through to class rank and VEHICLE won.
     *  3. Class group rank — person > bike > vehicle > animal > unknown.
     *     Reason: when two actors hit the same severity tier (e.g. an approaching
     *     car and a walking person both reach ALERT), the *person* is what the
     *     user actually wants the thumbnail to depict. Without this, a high-
     *     confidence vehicle bbox can mask the lower-confidence but more
     *     relevant person.
     *  4. Proximity (closer wins).
     *  5. Confidence — high-resolution tie-breaker only.
     */
    private static long score(Actor.Severity sev, float conf, Actor.Proximity p,
                              Actor.ClassGroup g, boolean staticNonThreat) {
        int sevOrd = sev != null ? sev.ordinal() : 0;
        int liveRank = staticNonThreat ? 0 : 1;          // 0..1 — live outranks static
        int classRank = classRank(g);                    // 0..4
        int proxRank = (p == null) ? 0 : (Actor.Proximity.values().length - 1 - p.ordinal());
        int confMilli = Math.max(0, Math.min(1000, Math.round(conf * 1000f)));
        // Pack: [sev:4][live:1][class:4][prox:4][confMilli:14]
        // Shifts moved up by one bit vs the pre-live-rank layout; the packing is
        // internal to this class and never persisted, so the widths are free.
        return ((long) sevOrd    << 33)
             | ((long) liveRank  << 32)
             | ((long) classRank << 28)
             | ((long) proxRank  << 24)
             | ((long) confMilli);
    }

    private static int classRank(Actor.ClassGroup g) {
        if (g == null) return 0;
        switch (g) {
            case PERSON:  return 4;
            case BIKE:    return 3;
            case VEHICLE: return 2;
            case ANIMAL:  return 1;
            default:      return 0;
        }
    }

    /**
     * Observe a frame: examine each Actor in the snapshot and update its slot
     * iff the new tuple beats the existing one.
     *
     * The {@code rgb} buffer is COPIED into the slot — the caller is free to
     * recycle their own buffer immediately.
     *
     * @param actors  Snapshot from {@link ActorTracker#update(java.util.List, int, int, int, long, long)}
     * @param rgb     RGB byte[] (length = w*h*3) of the YOLO crop the actors were detected in
     * @param w       Width of the rgb buffer (e.g. 320 for mosaic, 640 for foveated)
     * @param h       Height of the rgb buffer
     * @param camera  Quadrant index
     *
     * @deprecated NO CURRENT CALLERS — the engine uses the mask-aware overload
     *     {@link #observe(List, byte[], int, int, int, float[], float, float, float, float)}.
     *     Retained only as the documented fail-open entry point for a caller that
     *     genuinely cannot supply the motion mask. Prefer the mask-aware form:
     *     without the mask, motion-grounded staticness is disabled and every actor
     *     is ranked LIVE, so stationary scenery can win the hero again — the exact
     *     bug that motivated Tier 1. Kept (rather than deleted) so that failure
     *     mode is spelled out at the call site instead of being rediscovered.
     */
    @Deprecated
    public synchronized void observe(List<Actor> actors, byte[] rgb, int w, int h, int camera) {
        observe(actors, rgb, w, h, camera, null, 1f, 0f, 1f, 0f);
    }

    /**
     * As {@link #observe(List, byte[], int, int, int)} but with the motion
     * pipeline's per-block confidence mask for THIS frame, enabling
     * motion-grounded staticness for hero ranking.
     *
     * @param blockConf per-block confidence over the 320×240 / 10×7 grid, or
     *                  {@code null} when unavailable — null FAILS OPEN (all
     *                  actors treated as live, never demoted).
     * @param mapAx     affine mapping crop-space X → block-grid X (scale)
     * @param mapBx     affine mapping crop-space X → block-grid X (offset)
     * @param mapAy     affine mapping crop-space Y → block-grid Y (scale)
     * @param mapBy     affine mapping crop-space Y → block-grid Y (offset)
     */
    public synchronized void observe(List<Actor> actors, byte[] rgb, int w, int h, int camera,
                                     float[] blockConf,
                                     float mapAx, float mapBx, float mapAy, float mapBy) {
        if (actors == null || actors.isEmpty() || rgb == null || w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        for (Actor a : actors) {
            // Only consider actors that hit at least NOTICE in this frame's quadrant
            if (a.peakCamera != camera) continue;
            // BACKGROUND SCENERY (parked car, a tree briefly uncovered by motion)
            // must never win the thumbnail: a bbox drawn over a static car in the
            // distance reads to the user as "the system flagged this as a threat".
            //
            // This used to be an outright `continue` here, keyed on
            // isStaticForTimeline. That was INEFFECTIVE against the reported bug —
            // an event whose timeline listed an animal produced a hero showing a
            // parked car — because the car never latched isStaticForTimeline at all:
            // the latch needs historyCount>=3 plus a ran net-displacement test, and
            // the ~2 Hz YOLO cadence with fisheye bbox jitter keeps resetting both.
            // The car sailed past the gate and then beat the NOTICE animal on class
            // rank.
            //
            // Now the scenery verdict is computed below (staticNonThreat) from the
            // MOTION mask — which runs at the motion rate and needs no track
            // history — and applied as a SCORE DEMOTION (the liveRank bit) rather
            // than a drop. Two reasons to demote rather than exclude here:
            //   - staticness is only knowable after a couple of observations, so an
            //     exclusion at this point would have to act on an unknown verdict;
            //   - keeping the slot lets the verdict be REVISED later (an actor that
            //     starts moving must be able to win the hero back).
            // The exclusion still happens, just later and at the right layer:
            // pickHero() skips static slots, so an all-scenery pool yields no hero
            // and the caller falls back to a plain MP4 keyframe — same pixels, no
            // threat decoration. The per-actor JPEG writer skips them too.
            // PERSON is never treated as scenery.
            // Skip the low-confidence FAR NOTICE misclassification profile from
            // the hero pool (the on-car case: a parked motorcycle read as
            // "person · far" @0.44 that won the hero over the real moving car and
            // drew a grey box on a bike with no person present). HERO scope drops
            // it for ALL classes incl. PERSON — a phantom box is the visible bug
            // and the hero falls back to a real MP4 keyframe. (Summary surfaces
            // use Actor.suppressFromSummary, which exempts PERSON.)
            if (Actor.suppressFromHero(a)) {
                continue;
            }
            // STATIC NON-THREAT flag, feeding the live-beats-static term in score().
            //
            // Deliberately NOT an exclusion HERE: the verdict is only knowable after
            // a couple of observations and must stay revisable (an actor that starts
            // moving has to be able to win the hero back), so this layer records the
            // flag and score() demotes on it.
            //
            // pickHero() then DOES exclude static slots, so an all-scenery pool
            // yields no slot hero and the caller falls back to a plain MP4 keyframe
            // — deliberate (see pickHero's own note): a severity-coloured box over a
            // parked car is the user-visible bug. Two consequences to keep in mind
            // when touching this: the notification headline picks must apply the
            // matching all-scenery demotion or they will name a car the image does
            // not show (SurveillanceEngineGpu does this via snapshotSceneryIds), and
            // the push body must not advertise a "close-up view" for a keyframe.
            //
            // Signal choice matters. We use isStaticForTimeline ONLY — the same
            // predicate the pre-existing exclusion above uses — deliberately NOT
            // OR'd with the bare `isStatic`:
            //   - isStaticForTimeline additionally requires !everMoved and
            //     trend not APPROACHING/RECEDING (ActorTracker:785-791), i.e. it
            //     is a CUMULATIVE net-displacement verdict.
            //   - `isStatic` alone is a PER-STEP bbox-stability run
            //     (ActorTracker:409-419: area drift < 10% and centroid drift
            //     < 10 px vs the PREVIOUS observation), and for a VEHICLE it needs
            //     only 2 consecutive stable steps. A slowly-approaching car
            //     sampled ~500 ms apart easily stays under those per-step deltas
            //     while genuinely closing, so `isStatic` would mark a real
            //     approaching vehicle static — and since MIN_ESCALATION_FRAMES=3
            //     pins it at NOTICE for its first two observations, the NOTICE
            //     guard would not save it. That would demote a real threat below
            //     trivial scenery: a regression, and precisely the class of bug
            //     this change exists to fix.
            // PERSON is exempt: a person standing still is the threat we must
            // depict, never scenery. Only a NOTICE actor can be demoted, so an
            // ALERT/CRITICAL vehicle keeps full priority regardless.
            //
            // TIER 1 — MOTION-GROUNDED OVERRIDE. The Actor-layer verdict above is
            // an INFERENCE from sparse bbox history; the block mask is direct
            // pixel-change evidence at the motion rate. When the mask is
            // available it wins, in BOTH directions:
            //   - coverage > 0  ⇒ LIVE, even if isStaticForTimeline latched. A real
            //     mover can never be demoted while its pixels are changing.
            //   - coverage == 0 for >= STATIC_CONFIRM_OBS consecutive observations
            //     ⇒ STATIC, even if the Actor layer never latched (the cadence hole
            //     that let a parked car beat a NOTICE animal on class rank).
            // The consecutive requirement is the hysteresis: a single mask miss
            // (occlusion, a frame where the subject paused mid-stride, a
            // decimated motion tick) must not flip a live actor to static.
            //
            // FRAME + CROP-SPACE PRECONDITION. The mask and the affine describe THIS
            // frame's crop (w × h), so the bbox compared against them must be in
            // that same crop space. Three conditions, all required:
            //
            //  1. lastCamera == camera — the box belongs to the quadrant whose mask
            //     we hold. A TTL-retained actor last seen in another quadrant would
            //     otherwise have its box tested against this quadrant's blocks:
            //     guaranteed zero overlap, and after two ticks it would be declared
            //     static while possibly still moving elsewhere.
            //
            //  2. peakSeverityWallMs == lastSeenWallMs — the tracker re-latched the
            //     peak on THIS observation. That is what makes the crop space
            //     KNOWABLE: Actor exports peakBboxQuadW/H but has no lastBboxQuadW/H
            //     sibling, so lastBbox's space is otherwise unverifiable. When the
            //     peak was re-latched on this frame, ActorTracker set
            //     peakBboxQuad{W,H} = (quadW, quadH) of this very call — and
            //     lastBbox was assigned from the same detection in the same call, so
            //     they share one space (checked in 3).
            //
            //     A pure wall-clock freshness test is NOT sufficient and was wrong:
            //     the crop mode alternates within a few hundred ms (heartbeat runs,
            //     preferWideForClose, stale-slot mosaic fallback), so a foveated
            //     640-space lastBbox could be mapped through a mosaic identity
            //     affine, land outside the grid, read zero coverage, and latch a
            //     laterally-moving car as static — the very regression this test
            //     exists to prevent. (A lateral mover also defeats the corroboration
            //     gate: everMoved is mosaic-only and computeTrend needs |dCy|>=5.)
            //
            //  3. peakBboxQuad{W,H} == (w, h) — belt and braces on 2, and the same
            //     guard the capture path applies further down.
            //
            //  4. lastSeenWallMs within SAMPLE_FRESH_MS of now — an ABSOLUTE
            //     freshness term, and an AND not an OR. update() returns a snapshot
            //     of EVERY track including TTL-retained ones (TRACK_TTL_MS = 8 s)
            //     that YOLO did not re-detect this tick, and condition 2 is NOT a
            //     freshness test: a stale actor has BOTH timestamps frozen at the
            //     same old value, so it satisfies the equality trivially. Without
            //     this term a dog seen on alternate ticks had its 250-500 ms-old box
            //     tested against the current mask, read zero coverage twice, and got
            //     demoted while visibly moving (the corroboration gate cannot save a
            //     lateral mover: everMoved is mosaic-only, and computeTrend needs
            //     |dCy| >= 5).
            //
            // Failing any of these ⇒ treat the mask as unavailable and fall back to
            // the Actor-layer verdict. Never demote on geometry we cannot trust, and
            // never let such a sample advance the zero-coverage run.
            boolean sampleIsThisFrame = (a.lastCamera == camera)
                    && (a.peakSeverityWallMs == a.lastSeenWallMs)
                    && (a.lastSeenWallMs >= now - SAMPLE_FRESH_MS)
                    && (a.peakBboxQuadW <= 0 || a.peakBboxQuadW == w)
                    && (a.peakBboxQuadH <= 0 || a.peakBboxQuadH == h);

            // PROMOTION (static → live) uses a DELIBERATELY LOOSER gate.
            //
            // The gates above exist to stop a bad sample DEMOTING a real mover.
            // Recovering a demoted actor needs no such protection: promotion has no
            // run-length and no corroboration requirement, and its failure mode is
            // merely "scenery briefly outranks scenery". Requiring the strict gate
            // for recovery too created a permanent trap — ActorTracker's third
            // observe branch (proximity WORSE than the lifetime peak) stamps no
            // timestamps, so a demoted car that then DRIVES AWAY (MID → FAR) stops
            // satisfying condition 2 forever, `demotable` stays true because a
            // receding vehicle never escalates, and the latch held it static for the
            // rest of the event: a real moving vehicle invisible to the hero.
            //
            // So: any fresh, same-quadrant observation whose crop space we can trust
            // is enough to clear the latch, even without a peak re-latch. Geometry is
            // still required (conditions 1/3/4) — we just drop the re-latch demand.
            boolean sampleUsableForPromotion = (a.lastCamera == camera)
                    && (a.lastSeenWallMs >= now - SAMPLE_FRESH_MS)
                    && (a.peakBboxQuadW <= 0 || a.peakBboxQuadW == w)
                    && (a.peakBboxQuadH <= 0 || a.peakBboxQuadH == h);

            int zeroObs = 0;
            Integer prevZero = zeroMotionObs.get(a.actorId);
            if (prevZero != null) zeroObs = prevZero;
            Boolean motionSaysLive = null;
            // Evaluate coverage under the looser promotion gate, then let only the
            // strict gate authorise a DEMOTION. Positive coverage always promotes.
            if (blockConf != null && sampleUsableForPromotion) {
                boolean covered = bboxOverlapsMotion(a, blockConf, mapAx, mapBx, mapAy, mapBy);
                if (covered) {
                    zeroObs = 0;
                    motionSaysLive = Boolean.TRUE;
                } else if (sampleIsThisFrame) {
                    if (zeroObs < Integer.MAX_VALUE - 1) zeroObs++;
                    // ZERO COVERAGE IS NOT SUFFICIENT ON ITS OWN.
                    //
                    // A real mover can read zero blocks: the native block gate needs
                    // lumaChangedCount >= densityThreshold(12) of 64 stride-4 samples
                    // AND edgeChangedCount >= 2 against frame N-3, so a low-contrast
                    // slow approacher displacing ~3 px stays inside the sampling step
                    // and registers nothing. The oscillation and shadow filters, and a
                    // user ROI, can also zero an individual actor's blocks while other
                    // blocks keep confirmedBlocks>0 (so the mask stays non-null and we
                    // do NOT fail open). Trusting coverage alone would demote exactly
                    // the slowly-approaching vehicle this file's own comment above
                    // says must never be demoted — and, since static slots are skipped
                    // by the per-actor JPEG writer, would also drop its thumbnail.
                    //
                    // So the mask acts as CONFIRMATION, not sole authority. It may
                    // force static only when the motion-independent signals also
                    // fail to contradict stillness:
                    //   - !everMoved: the cumulative net-displacement latch never
                    //     fired. Critically this includes the AREA-GROWTH arm
                    //     (areaOverBandFrames>=2), which is what catches a HEAD-ON
                    //     approacher whose centroid barely shifts — the very case
                    //     computeTrend reports as STABLE (ActorTracker:436).
                    //   - trend not APPROACHING/RECEDING: a resolved closing or
                    //     departing track is never scenery.
                    //
                    // Deliberately NOT gated on Actor.everMovedTested. That field is
                    // everMovedTestFrames>=2, which requires >=3 same-quadrant mosaic
                    // observations and therefore implies historyCount>=3 — the exact
                    // floor that makes isStaticForTimeline unreachable at this
                    // cadence. Requiring it would collapse Tier 1 back into a subset
                    // of the gate it was written to bypass, differing only where
                    // trend is APPROACHING/RECEDING — i.e. only where demoting would
                    // be WRONG. Using the raw latch plus trend keeps the cadence hole
                    // closed while still protecting a real approacher.
                    boolean displacementContradictsStill = a.everMoved
                            || a.trend == Actor.Trend.APPROACHING
                            || a.trend == Actor.Trend.RECEDING;
                    if (zeroObs >= STATIC_CONFIRM_OBS && !displacementContradictsStill) {
                        motionSaysLive = Boolean.FALSE;
                    }
                    // else: insufficient or uncorroborated evidence — leave the
                    // verdict to the Actor layer rather than guessing.
                }
                // Bound the map. actorIds are monotonic and tracks fragment
                // (MATCH_IOU_MIN=0.20 against a stale box breaks identity for a
                // mover), so a long segment can mint far more ids than the
                // tracker's MAX_TRACKS=32 live slots. Entries are only cleared at
                // drain/clear, so without a cap this grows for the whole event.
                // Purging the whole map on overflow is safe: the run-lengths are
                // pure hysteresis, so the worst case is that a static actor needs
                // STATIC_CONFIRM_OBS more observations to be re-confirmed static —
                // it fails toward LIVE, never toward demoting a mover.
                if (zeroMotionObs.size() > ZERO_MOTION_MAP_CAP) {
                    zeroMotionObs.clear();
                    zeroObs = 0;
                }
                zeroMotionObs.put(a.actorId, zeroObs);
            }

            Slot existing = slots.get(a.actorId);

            // Only a NOTICE non-person can be scenery at all. Escalation past
            // NOTICE, or being a PERSON, ends the question permanently.
            boolean demotable = a.classGroup != Actor.ClassGroup.PERSON
                    && a.peakSeverity == Actor.Severity.NOTICE;

            boolean staticNonThreat;
            if (!demotable) {
                staticNonThreat = false;
            } else if (motionSaysLive != null) {
                // Positive motion evidence decides, in both directions.
                staticNonThreat = !motionSaysLive;
            } else {
                // NO MOTION EVIDENCE THIS FRAME (mask null, or the sample wasn't
                // from this frame/quadrant). Do NOT let that erase a demotion the
                // mask already established: STICK to the previous verdict, and only
                // let the Actor layer ADD a demotion.
                //
                // Without this the verdict became "whatever the last observation
                // happened to be able to measure". A parked car demoted on mosaic
                // ticks was re-promoted to LIVE on the next tick whose mask was
                // unavailable (snapshotConfirmedBlocks==0 on a quiet scene, foveated
                // without a valid affine, or the actor not re-observed in this
                // quadrant) — because the fallback reads isStaticForTimeline, which
                // for the cadence-hole car is exactly the signal that never latches.
                // It would then re-enter the pool as LIVE and beat the animal on
                // class rank: the original bug, restored.
                //
                // Demotion is therefore a LATCH, cleared only by positive evidence
                // of life (the motionSaysLive==TRUE branch above, or escalation /
                // PERSON via `demotable`). Absence of evidence is not evidence.
                boolean previously = existing != null && existing.staticNonThreat;
                staticNonThreat = previously || a.isStaticForTimeline;
            }

            long incoming = score(a.peakSeverity, a.peakConfidence, a.peakProximity,
                    a.classGroup, staticNonThreat);

            // Publish the staticness verdict to the existing slot IMMEDIATELY,
            // before the score gate below.
            //
            // Staticness is a property of the ACTOR, not of the frame the hero
            // pixels were captured on, so it must not be hostage to the capture
            // gate. Without this the demotion could never land: a live→static
            // transition LOWERS the score by (1<<32), so `scoreImproved` is false
            // and `dwellRefresh` requires equality — the method `continue`s and
            // never reaches the s.staticNonThreat write at the bottom. The
            // practical effect was that the first observation always won (its
            // existingScore is -1, so capture is unconditional) and at that point
            // Tier 1 has only ONE zero-coverage sample, which is below
            // STATIC_CONFIRM_OBS — so every slot was born LIVE and could never be
            // revised. That is precisely the parked-car-beats-animal bug this
            // change exists to fix, so the fix was inert without this write.
            //
            // Refreshing before computing existingScore also keeps dwellRefresh's
            // `incoming == existingScore` comparison meaningful: both sides then
            // reflect the same verdict, so the equality test is about the bbox
            // freshness it was written for, not about a staticness mismatch.
            // Pixels/bbox are untouched here.
            if (existing != null) existing.staticNonThreat = staticNonThreat;

            long existingScore = existing != null
                    ? score(existing.severity, existing.confidence, existing.proximity,
                            existing.classGroup, existing.staticNonThreat) : -1L;
            // Recapture on a strict score improvement OR — at equal score — when
            // the actor's latched bbox has moved to a FRESHER frame (the dwell
            // refresh in ActorTracker re-points peakBbox while the actor stays at
            // its peak proximity tier). Without the equal-score branch, a moving
            // actor that holds its peak tier never re-captures, so the hero keeps
            // a stale (rgb, bbox) pair from first-touch — the "delayed + wrong
            // position" bug. The branch re-pairs THIS frame's rgb with the
            // freshened bbox, so coherence is preserved. peakSeverityWallMs is the
            // latch's frame-time; a newer value means the bbox was re-pointed.
            boolean scoreImproved = incoming > existingScore;
            boolean dwellRefresh = existing != null
                    && incoming == existingScore
                    && a.peakSeverityWallMs > existing.peakWallMs;
            if (!scoreImproved && !dwellRefresh) continue;

            // CRITICAL: bbox alignment guard. The actor's peakBbox lives in
            // peakBboxQuadW × peakBboxQuadH coords (the crop space at the
            // frame peak severity was hit). The rgb we'd store is in THIS
            // frame's w × h. The pipeline alternates between mosaic (320×240,
            // full quadrant downscaled) and foveated (640×640, a high-res
            // window centered on motion centroid) — these are NOT
            // proportionally related geometries. Naive rescaling would draw
            // the bbox on the wrong physical region.
            //
            // Skip the update unless this frame's crop matches the peak's
            // crop. The score gate above already returned for non-improving
            // observations, so the only path that lands here is a real
            // improvement — but if it lands during an incompatible crop
            // mode, we'd rather keep the prior matching (rgb, bbox) pair
            // than overwrite with mismatched ones. The peak frame itself
            // (when peakSeverityWallMs == this frame's wallMs) is always
            // compatible because peakBboxQuad{W,H} were just set to (w, h).
            //
            // Defensive fallback: if peakBboxQuadW/H are zero (Actor
            // produced before this field existed in storage / very early
            // frames), trust the current crop dims.
            int bboxQuadW = a.peakBboxQuadW > 0 ? a.peakBboxQuadW : w;
            int bboxQuadH = a.peakBboxQuadH > 0 ? a.peakBboxQuadH : h;
            if (bboxQuadW != w || bboxQuadH != h) {
                // Wait for a frame whose crop matches the peak's crop. The
                // existing slot (if any) already has a coherent (rgb, bbox)
                // pair captured when the dims did match — better than
                // overwriting with a mismatched pair.
                continue;
            }

            // COHERENCE GATE — rgb and bbox MUST come from the same frame.
            // We store THIS frame's rgb (captured at lastSeenWallMs) paired with
            // a.peakBbox*, which was latched at a.peakSeverityWallMs. They depict
            // the SAME moment only when peakBbox was (re)latched on this very
            // frame — i.e. peakSeverityWallMs == lastSeenWallMs (both are the
            // tracker's wallNowMs for the current observe()).
            //
            // The bug this closes: a person seen CLOSE on first sight is gated to
            // NOTICE by the escalation window, so peakBbox latches at the close
            // frame but the ThumbnailBuffer score stays low. As they recede to
            // MID and the track confirms, toActor() re-derives ALERT from the
            // lifetime peakProximity — the score jumps on a LATER frame whose rgb
            // shows the actor at the frame edge, while peakBbox still points at
            // the earlier close-approach position. Pairing them drew the orange
            // box over the empty spot the actor had left — "the box misses the
            // actor". (The dwell-refresh path is coherent by construction: it
            // advances peakSeverityWallMs to the frame it re-points peakBbox on.)
            boolean bboxIsThisFrame = (a.peakSeverityWallMs == a.lastSeenWallMs);
            if (!bboxIsThisFrame) {
                // Score improved but peakBbox is from an earlier frame. Do NOT
                // overwrite the coherent pair with a mismatched rgb. If the
                // existing slot already holds the SAME peak frame's (rgb, bbox),
                // just refresh the score/label metadata so the hero's severity
                // colour tracks the re-derived tier while its pixels + box stay
                // coherent. Otherwise skip — the MP4-keyframe fallback produces
                // the hero rather than a box drawn on the wrong pixels.
                if (existing != null && existing.peakWallMs == a.peakSeverityWallMs) {
                    existing.severity = a.peakSeverity;
                    existing.confidence = a.peakConfidence;
                    existing.proximity = a.peakProximity;
                    // staticNonThreat is NOT re-written here: the unconditional
                    // pre-gate write above already applied this frame's verdict to
                    // this same Slot. Duplicating it would be dead today and would
                    // silently drift if the latch rule above ever changes.
                    existing.wallMs = now;
                }
                continue;
            }

            // POOL CAP. Only a NEW actorId can grow the map, so an existing slot
            // never has to fight for room. When full, evict the stalest scenery
            // slot: statics are already excluded from the hero (pickHero skips
            // them) and from the per-actor JPEGs, so dropping one costs nothing
            // the user can see, while dropping a live actor could cost the hero.
            // If the pool is entirely live actors, skip THIS capture instead —
            // refusing a new slot degrades to "no thumbnail for the 33rd actor",
            // whereas evicting a live one could discard the actual threat.
            if (existing == null && slots.size() >= MAX_SLOTS) {
                long stalest = Long.MAX_VALUE;
                Long victim = null;
                for (Slot cand : slots.values()) {
                    if (cand.staticNonThreat && cand.wallMs < stalest) {
                        stalest = cand.wallMs;
                        victim = cand.actorId;
                    }
                }
                if (victim == null) continue;
                slots.remove(victim);
            }

            Slot s = existing != null ? existing : new Slot();
            // Re-allocate only if size changed (or first capture) — avoids per-frame churn
            int needBytes = w * h * 3;
            if (s.rgb == null || s.rgb.length != needBytes) {
                s.rgb = new byte[needBytes];
            }
            System.arraycopy(rgb, 0, s.rgb, 0, needBytes);
            s.srcW = w;
            s.srcH = h;
            s.bboxX = a.peakBboxX;
            s.bboxY = a.peakBboxY;
            s.bboxW = a.peakBboxW;
            s.bboxH = a.peakBboxH;
            s.severity = a.peakSeverity;
            s.confidence = a.peakConfidence;
            s.proximity = a.peakProximity;
            s.wallMs = now;
            s.peakWallMs = a.peakSeverityWallMs;
            s.classGroup = a.classGroup;
            s.actorId = a.actorId;
            s.camera = a.peakCamera;
            s.staticNonThreat = staticNonThreat;
            slots.put(a.actorId, s);
        }
    }

    /**
     * Pick the highest-score slot whose peak frame lies within the recorded
     * window [windowStartMs, windowEndMs]. A slot whose peakWallMs predates the
     * window depicts a moment evicted from the bounded pre-record ring — i.e. a
     * frame the user will NOT find when scrubbing the MP4 — so it is excluded.
     *
     * windowStartMs<=0 disables the gate (legacy behavior). windowEndMs<=0 means
     * "no upper bound" (open-ended, e.g. the still-growing current segment).
     *
     * HARD GATE: when the window IS active (windowStartMs>0) and NO slot's peak
     * lies inside it, return null — do NOT fall back to an out-of-window slot.
     * The buffer is cleared only at recording STOP (not start), but observe()
     * runs continuously during monitoring, so a slot captured minutes earlier
     * (e.g. an animal that crossed the lot during a quiet gap) survives into the
     * next, unrelated event. The old "stale hero beats no hero" fallback then
     * stamped that phantom (e.g. "animal · far") onto an event whose MP4 never
     * contains it — observed on-car as a dog hero on a car-only motion clip.
     * Returning null instead routes the caller to its MP4-keyframe fallback
     * (writeFallbackHeroFromMp4 / the /thumb endpoint), which extracts a REAL
     * frame from the recorded clip. The pre-record ring is already inside the
     * window (windowStartMs = recordingStart = trigger − preRecordMs), so a
     * legitimate pre-roll peak is still in-window and kept; only genuinely
     * evicted/stale peaks are dropped. windowStartMs<=0 keeps the legacy
     * unconditional best-slot pick.
     */
    static Slot pickHero(List<Slot> snap, long windowStartMs, long windowEndMs) {
        Slot hero = null, heroAny = null;
        Slot staticHero = null, staticHeroAny = null;
        long heroScore = -1L, heroAnyScore = -1L;
        long staticScore = -1L, staticAnyScore = -1L;
        for (Slot s : snap) {
            long sc = score(s.severity, s.confidence, s.proximity, s.classGroup,
                    s.staticNonThreat);
            boolean inWindow = windowStartMs <= 0
                    || (s.peakWallMs >= windowStartMs
                        && (windowEndMs <= 0 || s.peakWallMs <= windowEndMs));
            if (s.staticNonThreat) {
                if (sc > staticAnyScore) { staticAnyScore = sc; staticHeroAny = s; }
                if (inWindow && sc > staticScore) { staticScore = sc; staticHero = s; }
                continue;
            }
            if (sc > heroAnyScore) { heroAnyScore = sc; heroAny = s; }
            if (inWindow && sc > heroScore) { heroScore = sc; hero = s; }
        }
        if (hero != null) return hero;
        if (staticHero != null) return staticHero;
        if (windowStartMs <= 0) {
            return heroAny != null ? heroAny : staticHeroAny;
        }
        // If window gate is active and nothing matched in window, fall back to any static hero before returning null
        return staticHeroAny != null ? staticHeroAny : heroAny;
    }

    /**
     * Write the hero JPEG from a pre-drained snapshot. Caller decides whether
     * this runs on the engine thread (sync hero, publish path needs it
     * deterministic) or on the executor (rotation path, no publish dep).
     *
     * <p>Taking the snapshot AT DRAIN TIME (rather than later inside the executor)
     * avoids a cross-segment race: the rotation listener and stopRecording can both
     * call in for different segments — each captures its own snapshot
     * synchronously, so the second segment isn't left with an empty buffer because
     * the first already drained.
     *
     * <p>Window-gated: only a slot whose peak frame lies within
     * [windowStartMs, windowEndMs] is eligible as the hero (with a best-effort
     * fallback if none qualify — see {@link #pickHero(List, long, long)}). This
     * stops the hero JPEG from depicting a peak moment that was evicted from the
     * bounded pre-record ring and therefore is not present anywhere in the MP4.
     */
    public synchronized File writeHeroFromSnapshot(List<Slot> snap, File mp4File,
                                                   long windowStartMs, long windowEndMs) {
        if (snap == null || snap.isEmpty() || mp4File == null) return null;
        File parent = mp4File.getParentFile();
        if (parent == null) return null;
        Slot hero = pickHero(snap, windowStartMs, windowEndMs);
        if (hero == null) return null;
        String base = mp4File.getName();
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        File heroFile = new File(parent, base + ".jpg");
        try {
            // Synchronized: writeJpeg uses the instance argbScratch field;
            // engine-thread (stopRecording publish) and executor-thread
            // (segment-rotation listener) can BOTH call this for different
            // segments at the same time. Lock the buffer instance so the
            // scratch buffer isn't torn between callers. JPEG compress is
            // ~50ms — short enough that serializing rotation+stop heroes
            // doesn't matter; correctness wins over minor parallelism.
            writeJpeg(hero, heroFile);
            return heroFile;
        } catch (Exception e) {
            logger.warn("Hero thumb write failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detach the current per-actor slots into a snapshot list and clear the
     * internal map. Caller drives the actual JPEG writes on a background
     * executor via {@link #writePerActorJpeg}, one slot at a time.
     *
     * <p>Returning a snapshot rather than draining inside the writer lets
     * the caller decide which executor to use AND lets the buffer be
     * cleared synchronously here — so the next recording's
     * {@link #observe(java.util.List, byte[], int, int, int)} starts with a
     * clean slate even if per-actor writes are still pending.
     *
     * <p>The returned slots reference the SAME {@code byte[] rgb} arrays the
     * buffer was holding — caller must NOT mutate them. After this call the
     * buffer no longer holds them, so they're safe to use until the
     * background executor finishes JPEG compression.
     *
     * @return list of slots (empty if nothing captured). Never null.
     */
    public synchronized List<Slot> drainSnapshotForAsync() {
        // Preserve the staticness VERDICTS across the drain.
        //
        // The notification/caption headline pick (isActorStaticNonThreat) runs
        // AFTER this drain — stopRecording calls flushSegmentMetadata (which drains
        // here) and only then publishMotionFinal / sendFinalTelegramNotification.
        // Without this carry-over, every lookup after the drain would hit an empty
        // `slots` map and return false for every actor, silently collapsing the
        // live-beats-static tier back to plain class rank on exactly the two
        // surfaces the user sees — i.e. the parked-car-beats-animal caption would
        // be "fixed" in code and unfixed in behaviour.
        //
        // Verdicts are cheap (a Long per static actor) and are cleared at the next
        // clear()/enable(), so they live no longer than the run-lengths did.
        lastStaticActorIds.clear();
        for (Slot s : slots.values()) {
            if (s.staticNonThreat) lastStaticActorIds.add(s.actorId);
        }
        // Drop the staticness run-lengths with the slots: they are per-actor
        // per-event state, and keeping them across a segment boundary would let a
        // previous segment's zero-coverage run pre-condemn a returning actor.
        // Cleared even on the early-return so an actor observed but never
        // slotted (all captures blocked by the crop-coherence gates) can't leak
        // an entry and grow this map without bound.
        zeroMotionObs.clear();
        if (slots.isEmpty()) return java.util.Collections.emptyList();
        List<Slot> snap = new ArrayList<>(slots.values());
        slots.clear();
        return snap;
    }

    /**
     * Write a single per-actor JPEG. Caller-driven: invoked from the
     * background executor inside SurveillanceEngineGpu's per-segment
     * publish path. Allocates a fresh scratch int[] every call — cold path
     * (≤ a few JPEGs per recording) so the allocation cost is negligible
     * compared to the JPEG compress itself.
     *
     * <p>Does NOT touch the buffer-instance {@link #argbScratch} field, so
     * the engine-thread sync hero writer (which uses that field) and this
     * async per-actor writer are safe to run concurrently for different
     * segments.
     */
    public void writePerActorJpeg(Slot s, File outFile) throws Exception {
        writeJpegWithScratch(s, outFile, null);
    }

    /**
     * Has this actor been judged stationary scenery (parked car etc.) by the
     * motion-grounded staticness test?
     *
     * <p>Exposed so the CAPTION / notification-headline threat pick can agree with
     * the hero. Those loops skip statics using {@code isStaticForTimeline} only —
     * precisely the signal the motion test exists to bypass — so without this a
     * mask-demoted parked car is excluded from the hero yet still wins the headline
     * on class rank, producing "vehicle at rear" over a thumbnail depicting an
     * animal: the reported bug inverted.
     *
     * <p>Returns false for an unknown actorId, so a caller that asks about an actor
     * with no slot behaves exactly as before (treated as a normal candidate).
     */
    public synchronized boolean isActorStaticNonThreat(long actorId) {
        Slot s = slots.get(actorId);
        if (s != null) return s.staticNonThreat;
        // Post-drain fallback: stopRecording drains the slots (inside
        // flushSegmentMetadata) BEFORE it publishes the final push / Telegram
        // caption, so by the time those threat picks ask, `slots` is empty. The
        // drain stashes the static actorIds precisely so this lookup still answers
        // for the event being published.
        return lastStaticActorIds.contains(actorId);
    }

    /** Drop everything (e.g. when recording aborted). */
    public synchronized void clear() {
        slots.clear();
        zeroMotionObs.clear();
        lastStaticActorIds.clear();
    }

    // ---------- writer ------------------------------------------------------

    /**
     * Sync-path JPEG writer; uses the buffer's pooled {@link #argbScratch}
     * which is owned by the surveillance (caller) thread. Must NOT be
     * called from the async per-actor executor — use
     * {@link #writeJpegWithScratch} there.
     */
    private void writeJpeg(Slot s, File outFile) throws Exception {
        argbScratch = writeJpegImpl(s, outFile, argbScratch);
    }

    /**
     * Async-path JPEG writer; takes a caller-owned scratch buffer (or null
     * for first call) and returns the (possibly grown) buffer for reuse on
     * the next iteration. Lets the per-actor lambda pool ARGB allocation
     * across slots without sharing state with the sync hero path.
     */
    private static int[] writeJpegWithScratch(Slot s, File outFile, int[] scratch)
            throws Exception {
        return writeJpegImpl(s, outFile, scratch);
    }

    /** Shared implementation. Returns the (possibly grown) scratch buffer. */
    private static int[] writeJpegImpl(Slot s, File outFile, int[] scratchIn) throws Exception {
        Bitmap bmp = null;
        Bitmap out = null;
        int[] argbScratchLocal = scratchIn;
        try {
            bmp = Bitmap.createBitmap(s.srcW, s.srcH, Bitmap.Config.ARGB_8888);
            // Convert RGB byte[] → ARGB pixel array, reusing a pooled scratch
            // buffer when possible. Realloc only when the size grows.
            int needPixels = s.srcW * s.srcH;
            if (argbScratchLocal == null || argbScratchLocal.length < needPixels) {
                argbScratchLocal = new int[needPixels];
            }
            int[] pixels = argbScratchLocal;
            for (int i = 0, p = 0; i < s.rgb.length; i += 3, p++) {
                int r = s.rgb[i] & 0xFF;
                int g = s.rgb[i + 1] & 0xFF;
                int b = s.rgb[i + 2] & 0xFF;
                pixels[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(pixels, 0, s.srcW, 0, 0, s.srcW, s.srcH);

            // Resize to OUT_SIDE if needed
            if (s.srcW != OUT_SIDE || s.srcH != OUT_SIDE) {
                out = Bitmap.createScaledBitmap(bmp, OUT_SIDE, OUT_SIDE, true);
                // bmp is now redundant — recycle eagerly (and null it so the
                // finally block doesn't double-recycle). createScaledBitmap
                // can also return the same bitmap if dims happened to match;
                // guard by identity.
                if (out != bmp) {
                    bmp.recycle();
                    bmp = null;
                }
            } else {
                out = bmp;
                bmp = null;  // ownership transferred to `out`
            }

            // Draw bbox + label ONLY for live threats (skip for staticNonThreat scenery)
            if (!s.staticNonThreat) {
                Canvas canvas = new Canvas(out);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(4f);
                stroke.setColor(severityColor(s.severity));

                float scaleX = (float) OUT_SIDE / s.srcW;
                float scaleY = (float) OUT_SIDE / s.srcH;
                Rect r = new Rect(
                        Math.round(s.bboxX * scaleX),
                        Math.round(s.bboxY * scaleY),
                        Math.round((s.bboxX + s.bboxW) * scaleX),
                        Math.round((s.bboxY + s.bboxH) * scaleY));
                canvas.drawRect(r, stroke);

                // Label uses no explicit typeface, so on a BSP with a null default
                // typeface (DiLink 5) drawText would abort the daemon natively. Set
                // a disk-loaded face and skip the label if the font system is
                // unusable — the bbox rectangle above is the essential annotation.
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setColor(Color.WHITE);
                label.setTextSize(28f);
                label.setShadowLayer(3f, 0f, 0f, Color.BLACK);
                DaemonFonts.apply(label, Typeface.NORMAL);
                if (DaemonFonts.canDrawText()) {
                    String text = Actor.severityLabel(s.severity) + " · "
                            + Actor.groupLabel(s.classGroup) + " · "
                            + Actor.proximityLabel(s.proximity);
                    canvas.drawText(text, Math.max(8, r.left), Math.max(32, r.top - 8), label);
                }
            }

            // Atomic write: compress to <name>.tmp, fsync, rename to <name>.
            // A process kill mid-compress would otherwise leave a truncated
            // .jpg at the final filename — and the hero JPEG is now
            // load-bearing for both PWA push and Telegram sendPhoto, with
            // no regeneration path once the sidecar names it as heroThumbnail.
            // Same discipline EventTimelineCollector uses for the JSON sidecar.
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }
            // World-readable so the Telegram daemon (separate UID, typically
            // shell/2000) can read the JPEG with sendPhoto. Set on tmp BEFORE
            // rename so the readable bit lands atomically with the file move.
            try { tmpFile.setReadable(true, /*ownerOnly=*/false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                // Rename failed (e.g. cross-volume on weird mounts). Best-effort
                // direct copy as a fallback so we don't lose the hero entirely.
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    throw new java.io.IOException("Failed to atomically rename " + tmpFile + " → " + outFile);
                }
            }
        } finally {
            // Recycle whichever Bitmaps are still live. setPixels / createScaledBitmap /
            // FileOutputStream can all throw, and previously these paths leaked
            // 1.6 MB of native pixels per failure. Identity-guard against
            // double-recycle when out==bmp.
            if (out != null) out.recycle();
            if (bmp != null && bmp != out) bmp.recycle();
        }
        return argbScratchLocal;
    }

    public static void writeRawRgbJpeg(byte[] rgb, int srcW, int srcH, File outFile) throws Exception {
        if (rgb == null || rgb.length < srcW * srcH * 3 || outFile == null) return;
        Bitmap bmp = null;
        Bitmap out = null;
        try {
            bmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[srcW * srcH];
            for (int i = 0, p = 0; i < pixels.length && (i * 3 + 2) < rgb.length; i++, p++) {
                int r = rgb[i * 3] & 0xFF;
                int g = rgb[i * 3 + 1] & 0xFF;
                int b = rgb[i * 3 + 2] & 0xFF;
                pixels[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(pixels, 0, srcW, 0, 0, srcW, srcH);
            if (srcW != OUT_SIDE || srcH != OUT_SIDE) {
                out = Bitmap.createScaledBitmap(bmp, OUT_SIDE, OUT_SIDE, true);
                if (out != bmp) bmp.recycle();
                bmp = null;
            } else {
                out = bmp;
                bmp = null;
            }
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }
            try { tmpFile.setReadable(true, false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    throw new java.io.IOException("Failed to atomically rename " + tmpFile + " → " + outFile);
                }
            }
        } finally {
            if (bmp != null && !bmp.isRecycled() && bmp != out) bmp.recycle();
            if (out != null && !out.isRecycled()) out.recycle();
        }
    }

    private static int severityColor(Actor.Severity sev) {
        if (sev == Actor.Severity.CRITICAL) return Color.RED;
        if (sev == Actor.Severity.ALERT)    return 0xFFFF8800; // orange
        return 0xFFAAAAAA; // grey for NOTICE
    }
}
