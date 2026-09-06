package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.ai.YoloDetector;
import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SurveillanceEngineGpu - V2 Per-Quadrant Motion Detection Pipeline
 * 
 * Uses the V2 native pipeline for per-quadrant 6-stage motion detection
 * with staggered YOLO AI inference on active quadrants.
 */
public class SurveillanceEngineGpu {
    private static final String TAG = "SurveillanceEngineGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Motion detection buffers
    private ByteBuffer currentFrame;
    private long lastMotionTime = 0;
    // volatile: read cross-thread by hasActiveMotion() (RMM control thread) to
    // drive the parked-idle fps ramp. Written only on the motion thread.
    private volatile long firstMotionTime = 0;  // When sustained motion started (for duration check)

    // Parked-idle throttle keyframe pulse: last time we forced an IDR while idle.
    // At the low idle fps the encoder's frame-count GOP stretches past the
    // pre-record window, so a periodic forced sync-frame keeps a valid keyframe
    // in the ring for pre-roll. Motion-thread confined (read/written only in
    // processFrameV2), so a plain long is sufficient — no separate timer thread
    // and therefore no start/stop lifecycle races.
    private long lastIdleKeyframePulseMs = 0;

    // Parked-idle throttle fps-ramp edge detector. hasActiveMotion() is checked at
    // the TOP of every processFrameV2 tick; when it changes vs this last-observed
    // value we push a single RMM reconcile (ramp HAL up on the rising edge, down on
    // the falling edge). Checking at the top of the tick (not per-write) makes it
    // robust to ALL firstMotionTime reset paths — including the hard-ceiling stop
    // that early-returns before the end of the method — at ~1 tick (200ms) latency,
    // negligible for a power ramp. Motion-thread confined; -1 = "not yet observed".
    private int lastReconciledActiveState = -1;

    // Quiet-tier tracking (issue #174): wall-clock ms of the most recent tick at
    // which hasActiveMotion() was true. getQuietDurationMs() reports how long the
    // engine has been quiet since; RMM uses it to step the AI cadence down after a
    // sustained no-motion period. volatile: written on the motion thread (top of
    // processFrameV2), read cross-thread by RMM. 0 = "motion active now or never
    // observed" → getQuietDurationMs() returns 0 (never triggers the quiet tier
    // until at least one active tick has been seen and then gone quiet).
    private volatile long lastMotionActiveWallMs = 0;

    // SUSTAINED MOTION: Base minimum before any trigger (prevents single-frame noise).
    // For THREAT_HIGH (loitering confirmed), this is the only delay needed.
    // For THREAT_MEDIUM (approaching), the loitering time setting adds additional delay.
    private static final long SUSTAINED_MOTION_BASE_MS = 500;

    // Track-anchored confirmation recency window. A held in-zone person track may
    // substitute for a within-sequence YOLO confirmation (so a person pacing
    // across the zone boundary keeps the fast trigger path) ONLY if a real YOLO
    // confirmation occurred within this window. The native track's "active" flag
    // has NO pre-recording liveness — trackerUpdate (NCC age-out + heartbeat
    // teardown) runs only while recording — so a track seeded for a person who
    // then LEFT stays frozen-active in-zone indefinitely. Without a recency gate,
    // that stale zombie would certify a LATER unrelated shadow/leaf burst as
    // "confirmed" and bypass the AI-suppression gate. A few seconds comfortably
    // covers a real zone-boundary re-entry while rejecting a minutes-old zombie.
    private static final long TRACK_ANCHOR_RECENCY_MS = 5000;

    // FLAG / SHADOW FALSE-POSITIVE GUARD.
    // A waving flag (or a sweeping cast shadow) is genuine, spatially-anchored
    // pixel motion: its connected-component centroid barely drifts, so the
    // native Stage-5 classifies it as THREAT_HIGH "loiter" — which historically
    // bypassed the YOLO confirmation gate and fired a recording after only
    // SUSTAINED_MOTION_BASE_MS. The discriminator is motion DIRECTIONALITY: a
    // real intruder TRANSLATES (per-block flow accumulates a coherent net
    // displacement, coherence ratio → 1); a flag/foliage/shadow OSCILLATES in
    // place (vectors cancel, ratio → 0, cumulative net drift ≈ 0).
    //
    // A THREAT_HIGH is "trusted" (keeps the fast 500ms, YOLO-exempt path) only
    // when an in-zone PERSON tracker holds it OR the native flow-coherence
    // signal says the motion is coherently translating. An untrusted HIGH
    // (flag/shadow) is DOWNGRADED to a YOLO-gated MEDIUM — it still records via
    // the existing YOLO-confirm / 2s-timeout / no-YOLO fallbacks, just on the
    // same evidence bar as MEDIUM, so a flag (never a YOLO person/car) stops
    // self-triggering. Tuning lives in MotionPipelineV2.Config.coherence*.
    private static final float COHERENCE_RATIO_MIN = 0.35f;
    private static final float COHERENCE_NET_MIN = 1.5f;

    // Loitering time in ms — derived from user setting (1-10 seconds).
    // THREAT_MEDIUM must persist for this duration before triggering recording.
    // THREAT_HIGH triggers after SUSTAINED_MOTION_BASE_MS (loitering already confirmed by native pipeline).
    private long loiteringTimeMs = 3000;  // Default 3 seconds

    // APPROACH FAST-PATH: sustained-motion ms required to record a MEDIUM(approach)
    // event ONCE YOLO has confirmed a real in-zone object during the sequence.
    // Short and responsive (default 2s) so someone walking up to / past the car is
    // recorded without waiting out the full loiter dwell. 0 disables it (every
    // MEDIUM then needs loiteringTimeMs, the legacy behavior). Motion with NO AI
    // confirmation always uses loiteringTimeMs regardless, so this can't lower the
    // bar for lighting artifacts / flags / shadows (they never yield a YOLO object).
    private long approachTriggerMs = 2000;

    // CLOSE-RANGE CONFIRMED FAST-PATH: the shortest sustained-motion bar, used
    // ONLY when BOTH hold during a sequence: (a) YOLO confirmed a real in-zone
    // object (sequenceConfirmed) AND (b) proximity reached NEAR (tier==NEAR).
    // Rationale: a person walking briskly PAST the car at close range is only
    // in-frame per-quadrant for ~1.5-2s — shorter than approachTriggerMs (2s),
    // so the normal approach fast-path times out and nothing records (observed
    // on-device: 1.9s walk-past missed the 2.0s bar by 100ms). This bar catches
    // that case. FP-safe by construction: flags/shadows/lighting NEVER yield a
    // YOLO object (fails gate a), and a distant passer-by never reaches NEAR
    // (fails gate b) — so this can only fire for a real object physically close
    // to the car. Set <= approachTriggerMs; the code takes min() so a config
    // that lowers approachTriggerMs below this still wins. 0 disables it.
    private static final long CLOSE_CONFIRMED_TRIGGER_MS = 1000;

    // Sequence latch: did proximity reach NEAR at any point during the current
    // motion sequence? Reset at sequence start (with peakThreatDuringSequence /
    // cachedHighIsTrusted), set each frame the best quadrant reads NEAR. Gates
    // the CLOSE_CONFIRMED_TRIGGER_MS fast-path above.
    private boolean peakNearDuringSequence = false;

    // Sequence latch: did proximity reach the CLOSE ZONE (NEAR or MID tier, i.e.
    // not FAR) at any point during the current motion sequence? Wider than
    // peakNearDuringSequence (which is NEAR-only). Gates the UNCONFIRMED
    // close-zone fast-path + AI-gate override — the safety-critical FN fix for a
    // real subject that YOLO couldn't classify in a short close-range window
    // (person parked a bike + walked up at ~2.5m = MID). Reset with the other
    // sequence latches at sequence start and enable().
    private boolean peakCloseZoneDuringSequence = false;

    // ════════════════════════════════════════════════════════════════════
    // STATIONARY-SUBJECT REVIVAL (persistent-foreground / MOG2 channel).
    //
    // The V2 pipeline is frame differencing (N vs N-3): a subject who walks
    // up and then STANDS STILL sheds all motion blocks within ~300ms. The
    // standing-person tracker immunity covers this ONLY after a YOLO person
    // hit seeded the NCC tracker; a subject YOLO never resolved (dark,
    // fisheye-warped, partially occluded) has no track, the sequence
    // gap-resets at >2s of stillness, and the loiter bar is never reached.
    //
    // This channel fills exactly that hole with the OpenCV MOG2 background
    // model already compiled into the .so (native_motion.cpp): a true
    // background subtractor keeps reporting a stationary subject as
    // foreground until the model absorbs them (history=100 @ 2 FPS ≈ 50s).
    //
    // SHAPE: REVIVAL-ONLY, deliberately NOT a trigger channel. It can only
    // keep an ALREADY-RUNNING motion sequence alive through stillness
    // (anyMotion revive, mirroring the standing-person immunity branch); it
    // can never start a sequence, never lowers a duration bar, never touches
    // the AI-confirm gate, and is not a shouldDiscardEvent KEEP. The normal
    // bars + YOLO gate + far-unconfirmed gate still fully decide the
    // recording — so its FP surface is bounded by gates that all exist
    // today.
    //
    // Brakes (I2/I9/I10/I11):
    //   - revive-only guard (firstMotionTime != 0) — same as the tracker
    //     immunity branch, prevents any fresh-sequence storm;
    //   - quadrant-scoped to the sequence's own last-qualifying-motion
    //     quadrant (I11 — a bush's foreground in Q2 can't vouch for Q0);
    //   - brightness discriminator on that quadrant (I3-scoped);
    //   - fail-closed on absent evidence: OpenCV missing, warmup, stale
    //     sample, native error → no revival (I9);
    //   - per-sequence revival budget (MOG2_REVIVAL_MAX_MS) on top of the
    //     model's natural ~50s absorption (I10);
    //   - all state reset in enable(), background model reset natively (I2).
    //
    // DEVICE-UNVERIFIED: MOG2_REVIVE_MIN_FRAC is reasoned (a person at 2-5m
    // covers well over 3% of a 320×240 tile's pixels; post-morphology noise
    // sits far below), not measured. Kill switch: surveillance config JSON
    // key "stationaryRevivalEnabled" (default true), read once in enable().
    // ════════════════════════════════════════════════════════════════════
    private static final long MOG2_SAMPLE_PERIOD_MS = 500;   // 2 FPS; ~50s absorption comes from the FIXED post-warmup lr=0.001 (R3b Ext-5), NOT history=100
    private static final int  MOG2_WARMUP_CALLS = 20;        // ~10s of learning before the channel arms
    private static final float MOG2_REVIVE_MIN_FRAC = 0.03f; // min foreground fraction in the sequence quadrant
    private static final long MOG2_FRESHNESS_MS = 1600;      // sample must be ≤ ~3 periods old
    private static final long MOG2_REVIVAL_MAX_MS = 60_000;  // per-revival-stretch budget

    private boolean mog2ChannelEnabled = false;  // config kill switch, read in enable()
    private boolean mog2Available = false;       // native OpenCV probe, set in enable()
    private java.nio.ByteBuffer mog2Frame = null;
    private int mog2CallCount = 0;
    private long lastMog2SampleMs = 0;
    private final float[] mog2QuadFrac = new float[MotionPipelineV2.NUM_QUADRANTS];
    private long mog2FracAtMs = 0;
    // Quadrant of this sequence's most recent QUALIFYING motion (-1 = none).
    // Set alongside the lastMotionTime bump; reset at sequence start/enable().
    private int sequenceMotionQuadrant = -1;
    // Start of the current continuous revival stretch (0 = not reviving).
    private long mog2RevivalStartMs = 0;
    // MOG2 foreground fraction in the sequence quadrant at the last
    // qualifying-motion bump (-1 = no sample was available). DEPARTURE BRAKE
    // (audit finding): pre-existing static foreground — a fresh parker, a
    // persistent light patch — must not sustain a DEPARTED subject's
    // sequence to the AI-timeout fallback. A subject who is still present
    // (standing) keeps the fraction at or near this baseline; a subject who
    // LEFT removes their own foreground contribution, dropping it below
    // MOG2_NO_DROP_FRAC × baseline, which disarms revival.
    private float sequenceMog2BaselineFrac = -1f;
    private static final float MOG2_NO_DROP_FRAC = 0.8f;
    // AMBIENT-FOREGROUND snapshot at sequence start (audit R3b Ext-9): the
    // departure brake used to compare WHOLE-QUADRANT fractions, so unrelated
    // static foreground S (fresh parker, light patch) satisfied
    // S >= 0.8(P+S) after the subject left whenever S >= 4P, and losing an
    // unrelated patch could reject a still-present person. Snapshotting the
    // per-quadrant fractions at sequence start (subject's own pixels mostly
    // not yet foreground) lets both brake sides subtract the ambient term,
    // referencing the comparison to the SUBJECT's contribution. Valid only
    // when the sample was fresh at snapshot time (else -1 = no subtraction,
    // legacy behavior). Reset at sequence start + enable().
    private final float[] sequenceMog2AmbientQuad =
            new float[MotionPipelineV2.NUM_QUADRANTS];
    private boolean sequenceMog2AmbientValid = false;
    // Snapshot stamp (elapsedRealtime) + absorption-horizon expiry (audit R5
    // / R4-mog2 #1): the snapshot is frozen while the real ambient absorbs
    // into the background at the post-warmup lr=0.001 (~53s), so subtracting
    // a stale S₀ late in a long sequence UNDERCOUNTS the subject (FN). Past
    // the horizon the true residual ambient is ≈0 — treat it as such.
    private long sequenceMog2AmbientAtMs = 0;
    private static final long MOG2_AMBIENT_HORIZON_MS = 60_000;
    // ONE-SAMPLE-DELAYED shadow of mog2QuadFrac (audit R4-mog2 R#2 + R4
    // ExtB-6): the ambient snapshot must come from a sample that (a) is not
    // the SAME sample the tick-1 baseline reads (identity made the
    // positivity gate 0>0 fail) and (b) predates the subject's entry as far
    // as possible (a 2Hz sample landing between entry and the first
    // qualifying tick bakes the subject's own silhouette into the
    // "ambient"). The previous sample is copied before each sampler write.
    private final float[] mog2QuadFracPrev = new float[MotionPipelineV2.NUM_QUADRANTS];
    private long mog2FracPrevAtMs = 0;
    // Per-sequence LATCH of the correlated-lighting verdict (audit R3
    // fresh-eyes #2): correlation is a property of the sequence's CREATION,
    // but the per-quadrant onset stamps are overwritten on every later
    // suppression edge — a second sweep >1s into a live sequence used to
    // erase the correlated status while the first event's light pool kept
    // the sequence alive, reopening the close-zone fast paths. Once the ±1s
    // test passes for this sequence, it stays latched until sequence
    // start/enable() resets it.
    private boolean sequenceLightingCorrelated = false;
    // Per-quadrant brightness-suppression ONSET stamps
    // (SystemClock.elapsedRealtime() ms — MONOTONIC by contract, audit R2
    // #4: the correlated-lighting guard compares onsets against the sequence
    // start ACROSS ticks, and a wall-clock step (GPS/NTP re-sync on park)
    // would fake or hide a ±1s correlation) + previous-tick state for edge
    // detection. Unlike suppressionWasActive[]
    // (which is gated on the AI-available bookkeeping block), these are
    // stamped unconditionally every tick, so the correlated-lighting guard
    // below works in no-AI mode too. Reset in enable().
    private final long[] suppressionOnsetMs = new long[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] brightnessPrevTick = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    // Monotonic (elapsedRealtime) twin of firstMotionTime, stamped at the
    // same single sequence-start point. The correlated-lighting guard's
    // onset-vs-sequence-start comparison runs entirely in the monotonic
    // domain (audit R2 #4). Only meaningful while firstMotionTime > 0.
    // volatile: written on the engine thread only; the multi-batch
    // confirmation SHADOW block (aiExecutor thread) reads it for its
    // sequence-scoped batch count. A plain long read across threads could
    // tear/staleness — volatile keeps the diagnostic honest without any
    // lock-protocol coupling. Trigger logic is unaffected (its readers stay
    // on the engine thread).
    private volatile long firstMotionElapsedMs = 0;
    // CORRELATED-LIGHTING GUARD (audit finding on the I3 scoping change): one
    // physical lighting event can suppress camera A while its light POOL
    // shows up as ordinary coherent MEDIUM+ motion in camera B — per-quadrant
    // scoping alone would let B's pool take the close-zone fast paths the old
    // scene-wide OR blocked. Correlation in TIME restores that protection
    // exactly where it applies: if any quadrant's suppression ONSET lies
    // within this window of the sequence's firstMotionTime, the sequence
    // itself was plausibly CREATED by the lighting event, and all three
    // brightness scopes treat it as a lighting-artifact sequence (old
    // behavior). A sweep that starts well after a real subject's sequence
    // began stays scoped to its own quadrant (the FN fix is preserved).
    private static final long LIGHTING_CORRELATION_MS = 1000;

    // Quadrant that latched peakCloseZoneDuringSequence (-1 = none). Used to
    // EVIDENCE-SCOPE (invariant I3) the brightness-event discriminator: a
    // headlight sweep may only close the close-zone paths when it fired in a
    // quadrant that can describe the close-zone subject (this latch's quadrant
    // or the live best-threat quadrant) — never from an unrelated camera.
    // Reset with the other sequence latches at sequence start and enable().
    private int peakCloseZoneQuadrant = -1;

    // ========================================================================
    // MOTION SALIENCE — the third evidence channel (opt-in, default OFF)
    // ========================================================================
    // The system has two ways to justify a recording: a YOLO class confirmation,
    // or a TRUSTED HIGH loiter. Both can fail on a real, close, obvious subject:
    // YOLO gets ~2-4 inference windows on a 320×240 fisheye crop (mean luma ~47)
    // and returns nothing; the loiter path needs 3s of near-stationary centroid.
    // The documented residual is a large close object that produces overwhelming
    // motion evidence and is recorded by NEITHER channel.
    //
    // Salience is that third channel: motion so large, compact, sustained,
    // photometrically stable and rigidly translating that the geometry alone is
    // object-grade evidence. It does NOT lower any existing bar — it adds a
    // parallel one, so with the flag off every path is byte-identical.
    //
    // The FP classes it must reject, and the term that rejects each:
    //   waving foliage / flag      → coherence (in-place oscillation, netDrift≈0)
    //   sweeping shadow            → coherence + luma stability
    //   headlight sweep / IR glare → brightnessSuppressed + luma-delta gate
    //   rain / snow / wipers       → componentCount (diffuse, many components)
    //   camera/ISP exposure step   → luma-delta gate + native flash filter
    //   distant street traffic     → close-zone tier + in-zone row gate
    // Every one is a POSITIVE test against a signal the FP produces, so absence
    // of evidence never triggers (the inverse of a fail-open — see the native
    // probe's identical stance).

    /** Master gate. Mirrors {@code surveillance.motionSalienceEnabled}. */
    private volatile boolean salienceEnabled = false;
    /** Largest-component blocks required. 12/70 ≈ a person inside ~2m on a
     *  320×240 quadrant; below this a shadow edge or a distant car qualifies. */
    private volatile int salienceMinBlocks = 12;
    /** Largest component as a fraction of confirmed mass. One object ≈ 1.0;
     *  rain/wipers/global relight scatter well below this. */
    private volatile float salienceDominanceFrac = 0.60f;
    /** Consecutive qualifying ticks required (~8.7 Hz → 6 ≈ 0.7s). A real
     *  approach holds; a one-frame ISP step or a gust cannot. */
    private volatile int salienceSustainTicks = 6;
    /** Max |meanLuma - sequence-start meanLuma| allowed. A person changes the
     *  scene's GEOMETRY, not its overall brightness; a headlight sweep or
     *  exposure step changes brightness. 12 of 255 ≈ 4.7%. */
    private volatile float salienceMaxLumaDelta = 12f;
    /** Sustained-motion bar once salience holds. Matches the close-confirmed
     *  bar — this is the same "real object, act now" tier of evidence. */
    private static final long SALIENCE_TRIGGER_MS = 1000;

    // Per-quadrant consecutive-qualifying-tick counters + the luma anchor each
    // quadrant's stability is measured against. Both reset on the quadrant's
    // first non-qualifying tick and in enable(). Per-tick maintained (never in a
    // frameCount % N block) — invariant I2.
    private final int[] salienceRunTicks = new int[MotionPipelineV2.NUM_QUADRANTS];
    private final float[] salienceLumaAnchor = new float[MotionPipelineV2.NUM_QUADRANTS];
    // Sequence latch: did any quadrant hold a full salience run this sequence?
    // Reset with the other sequence latches at sequence start and in enable().
    private boolean salienceConfirmedDuringSequence = false;

    /**
     * The salience latch, ANDed with the live master flag. Every consumer must read
     * the channel through this, never the raw field: the per-tick evaluator is gated
     * on {@code salienceEnabled}, so once the user turns the toggle OFF mid-sequence
     * the evaluator can no longer clear a latch it already set — and the three
     * consumers (the requiredDuration bar, the far-unconfirmed gate exemption, the
     * AI-gate override) would keep honouring a disabled channel until the next
     * sequence start. Folding the flag in here makes "off" take effect immediately
     * and keeps the latch's meaning identical everywhere.
     */
    private boolean salienceActive() {
        if (!salienceConfirmedDuringSequence || !salienceEnabled) return false;
        // Freshness: see salienceConfirmedAtMs. A latch older than the TTL has
        // outlived its evidence and must be re-earned. elapsedRealtime for the same
        // reason as the min-gap — a duration test must not read a wall-clock step as
        // an expiry (or, backwards, as eternal freshness).
        return salienceConfirmedAtMs > 0
                && (android.os.SystemClock.elapsedRealtime() - salienceConfirmedAtMs)
                        <= SALIENCE_LATCH_TTL_MS;
    }

    /**
     * Minimum gap between two SALIENCE-ONLY recordings. Direct analogue of
     * {@link #NO_AI_MIN_GAP_MS}, and it exists for the same failure mode.
     *
     * <p>Without it the channel can storm: the per-quadrant run counters survive a
     * sequence boundary, so a scene that keeps satisfying all five terms re-latches
     * on the FIRST tick of the next sequence and re-fires after only
     * SALIENCE_TRIGGER_MS. Each retrigger forces a fresh muxer init + pre-record
     * flush; sustained over a multi-hour park that leaks MediaCodec instance slots
     * on the Adreno 610 until the daemon takes SIGABRT — the exact end state the
     * AI-confirm gate and the no-AI rate limit were added to prevent. YOLO
     * confirmation is what naturally rate-limits the other channels; salience has
     * no such external brake, so it needs an explicit one.
     *
     * <p>15 s rather than NO_AI_MIN_GAP_MS's 30 s: salience IS positive object
     * evidence, so the cost of suppressing a genuine return visit is higher here.
     * With the 3×postRecord hard ceiling this caps salience-only codec churn at
     * roughly one clip per 45 s instead of one per second.
     */
    private static final long SALIENCE_MIN_GAP_MS = 15_000;

    /**
     * True when salience may drive a trigger right now: the channel is active AND
     * we are outside the post-stop cooldown. Consulted by the three trigger-side
     * consumers (bar, far-gate exemption, AI-gate override) but NOT by the
     * {@code eventTriggerWasSalience} latch, which records what evidence existed
     * rather than what was allowed to act on it.
     *
     * <p>Scoped to SALIENCE-ONLY events on purpose: a YOLO-confirmed object or a
     * trusted HIGH loiter reaches the trigger through its own path and is never
     * delayed by this cooldown.
     */
    private boolean salienceMayTrigger() {
        if (!salienceActive()) return false;
        // elapsedRealtime, not currentTimeMillis: this is a pure duration test, and a
        // wall-clock source lets a GPS/NTP correction on ACC-on either mute the channel
        // for the length of a backward jump or skip the gap entirely on a forward one.
        // Same reasoning as aiRunStartedMs (~:4219). lastRecordingStopElapsedMs is
        // stamped beside the wall-clock lastRecordingStopTime, which is left alone
        // because the pre-existing no-AI limiter and other consumers read it.
        if (lastRecordingStopElapsedMs <= 0) return true;
        return (android.os.SystemClock.elapsedRealtime() - lastRecordingStopElapsedMs)
                >= SALIENCE_MIN_GAP_MS;
    }

    /**
     * Has the quadrant that latched salience NOT since been contradicted by its own
     * coherence signal? The per-quadrant analogue of the {@code cachedIncoherentLoiter}
     * guard the sibling close-zone paths use.
     *
     * <p>Scoped to the salient quadrant on purpose: the shared latch is armed by the
     * highest-threat quadrant, so consulting it here would let an incoherent bush in
     * one quadrant veto a coherent approach in another. Returns FALSE (blocking the
     * override) only on POSITIVE contradiction — the quadrant published a usable
     * coherence reading and it now says in-place oscillation on a formed component.
     * An unavailable reading (-1, e.g. a texture-poor dark crop) leaves the earlier
     * 6-tick positive evidence standing rather than discarding it.
     */
    private boolean salientQuadrantStillCoherent(MotionPipelineV2.QuadrantResult[] results) {
        int q = salienceQuadrant;
        if (q < 0 || results == null || q >= results.length) return true;
        MotionPipelineV2.QuadrantResult r = results[q];
        if (r == null || r.flowCoherence < 0f || r.componentSize <= 0) return true;
        return r.flowCoherence >= COHERENCE_RATIO_MIN || r.netDriftBlocks >= COHERENCE_NET_MIN;
    }
    // Quadrant that latched it, for the trigger log line (-1 = none).
    private int salienceQuadrant = -1;
    // When the latch was set. The latch is otherwise cleared only at sequence start,
    // and a sequence does not end while ANY quadrant keeps refreshing lastMotionTime
    // — so on a busy scene a 600 ms burst in one quadrant could keep vouching for an
    // unrelated subject minutes later. Consumers require freshness within
    // SALIENCE_LATCH_TTL_MS. Re-latching is cheap (the run counters persist), so a
    // subject that is genuinely still there simply re-confirms.
    private long salienceConfirmedAtMs = 0;
    /** How long a salience confirmation stays valid without being re-earned.
     *  Generous relative to the 6-tick (~0.7 s) evidence window so a texture-poor
     *  tick or a brief occlusion can't drop a live approach, but far short of a
     *  multi-minute sequence. */
    private static final long SALIENCE_LATCH_TTL_MS = 5_000;
    /** Set by setConfig (HTTP/IPC thread) when the master flag flips; consumed by the
     *  engine thread at the top of the per-tick evaluation. Volatile hand-off so the
     *  non-volatile run counters/latch stay single-writer — see setConfig. */
    private volatile boolean salienceResetRequested = false;
    // Event latch: the recording fired on salience evidence. Deliberately NOT a
    // KEEP clause in shouldDiscardEvent — the no-actor discard is the intended
    // precision partner of this channel's recall, so a salience clip with no actor
    // is exactly what the user asked to be filtered. Used only for attribution in
    // the discard/keep log lines. Reset in startRecording.
    private volatile boolean eventTriggerWasSalience = false;

    // ========================================================================
    // POST-PARK VIGILANCE — the fourth evidence channel (see DETECTION-INVARIANTS.md).
    //
    // Target FN: a vehicle is WATCHED parking (event records, vehicle promoted
    // to the DetectionBaseline), the clip closes, and the occupant exits 20-120s
    // later. The exit sequence is structurally handicapped at every stage:
    //   - the parked car is now baseline-suppressed, so the only class evidence
    //     left is a YOLO *person* box — which the person's own door/car occludes
    //     and fisheye warp frequently denies (documented whole-session YOLO
    //     zero-detections on close subjects);
    //   - unconfirmed, the bar stays at the full loiter time, while door-swing +
    //     step-out + walk-away is short and fragmented (>2s pauses reset
    //     firstMotionTime);
    //   - a person walking AWAY reads passing, not approaching;
    //   - and the far-unconfirmed gate kills the 2s timeout fallback whenever the
    //     parked car sits beyond the NEAR/MID tiers.
    //
    // The channel: motion whose centroid is ADJACENT to a fresh live-event
    // vehicle baseline entry (a "fresh parker" — watched arriving, confirmed,
    // younger than VIGILANCE_ANCHOR_MAX_AGE_MS) earns (a) a lowered sustained-
    // motion bar, (b) an exemption from the far-unconfirmed gate, and (c) the
    // fast AI cadence so YOLO gets more person-classification chances. It does
    // NOT clear the AI-confirm gate — the 2s timeout still runs, so YOLO always
    // gets its window and a confirmed person records via the normal paths.
    //
    // FP posture ("no false recordings"):
    //   - Evidence-scoped (I3): only the anchor's quadrant, only motion within
    //     VIGILANCE_ADJACENCY_NORM of the anchor's foot-point, only while the
    //     entry is younger than the window. A shadow two quadrants over is
    //     untouched.
    //   - Anchors are CONFIRMED live-event vehicle entries only (I9 fail-closed:
    //     a one-frame YOLO hallucination can never open a zone; any error in the
    //     probe reads as "no anchor").
    //   - Per-tick discriminators re-checked at every consumer: brightness event
    //     (headlight sweep) and confirmed incoherent loiter (flag/foliage shadow)
    //     both close the channel.
    //   - Explicit brakes (I10 — a channel that only ADDS triggers needs one):
    //     VIGILANCE_MIN_GAP_MS since the last recording stop, plus a hard budget
    //     of VIGILANCE_MAX_ASSISTS assists per rolling VIGILANCE_ASSIST_WINDOW_MS
    //     — a flapping shadow at the parked car's spot can cost at most 3 extra
    //     clips per 10 minutes, then the channel silences itself while the normal
    //     (confirmed) paths continue unaffected.
    //   - The latch carries a TTL (I11): stamped per qualifying tick, expires
    //     VIGILANCE_LATCH_TTL_MS after the last adjacency evidence, reset in
    //     enable(). No frameCount%N decay (I2).
    //   - Like salience, eventTriggerWasVigilance is deliberately NOT a KEEP in
    //     shouldDiscardEvent: the no-actor discard is this channel's precision
    //     partner, so a vigilance clip with no actor stays prunable.
    // ========================================================================

    /** Master gate. Mirrors {@code surveillance.postParkVigilanceEnabled}. */
    private volatile boolean postParkVigilanceEnabled = true;
    /** How long after a watched arrival the parked spot stays vigilance-armed.
     *  Occupants overwhelmingly exit within a few minutes of parking; beyond
     *  this the spot is ordinary background again. */
    private static final long VIGILANCE_ANCHOR_MAX_AGE_MS = 10 * 60_000L;
    /** Foot-point adjacency radius, normalized to quadrant dims (~96px of 320).
     *  Wide enough for a door swing + first steps beside the anchor bbox,
     *  narrow enough that motion across the quadrant never qualifies. */
    private static final float VIGILANCE_ADJACENCY_NORM = 0.30f;
    /** Latch freshness: how long the last adjacency evidence keeps vouching.
     *  Covers the door-pause-walk cadence of an exit without letting a stale
     *  stamp certify an unrelated later burst (I11). */
    private static final long VIGILANCE_LATCH_TTL_MS = 10_000;
    /** Sustained-motion bar once vigilance holds — same "high-prior, act now"
     *  tier as the close-confirmed and salience bars. */
    private static final long VIGILANCE_TRIGGER_MS = 1000;
    /** Min gap since the LAST recording stop before vigilance may assist again.
     *  Short relative to salience's 15s: the target event fires shortly after
     *  the parking clip closes, and the pre-record ring means a delayed trigger
     *  still captures backwards. */
    private static final long VIGILANCE_MIN_GAP_MS = 10_000;
    /** Hard budget: vigilance-ASSISTED (unconfirmed) triggers per rolling window. */
    private static final int VIGILANCE_MAX_ASSISTS = 3;
    private static final long VIGILANCE_ASSIST_WINDOW_MS = 10 * 60_000L;

    // Latch: last tick whose best-threat quadrant's motion centroid sat next to
    // a fresh-parker anchor. Engine-thread only. elapsedRealtime (duration test —
    // immune to GPS/NTP wall-clock steps, same reasoning as the salience stamps).
    private int vigilanceQuadrant = -1;
    private long vigilanceSeenAtMs = 0;
    /** elapsedRealtime stamps of vigilance-assisted triggers (budget brake). */
    private final java.util.ArrayDeque<Long> vigilanceAssistStamps = new java.util.ArrayDeque<>();
    // Event latch for log attribution only — deliberately NOT a discard KEEP
    // (mirrors eventTriggerWasSalience; see channel comment). Reset in startRecording.
    private volatile boolean eventTriggerWasVigilance = false;

    /**
     * The vigilance latch, ANDed with the live master flag (same contract as
     * {@link #salienceActive()}: consumers must read through this so an OFF flip
     * takes effect immediately) and its TTL.
     */
    private boolean vigilanceActive() {
        if (!postParkVigilanceEnabled) return false;
        return vigilanceSeenAtMs > 0
                && (android.os.SystemClock.elapsedRealtime() - vigilanceSeenAtMs)
                        <= VIGILANCE_LATCH_TTL_MS;
    }

    /**
     * True when vigilance may assist a trigger right now: latch fresh AND outside
     * the post-stop min gap AND under the rolling assist budget. Only unconfirmed
     * (vigilance-dependent) triggers consume budget — a YOLO-confirmed object or
     * trusted loiter records through its own path and is never delayed by this.
     */
    private boolean vigilanceMayTrigger() {
        if (!vigilanceActive()) return false;
        long nowE = android.os.SystemClock.elapsedRealtime();
        if (lastRecordingStopElapsedMs > 0
                && (nowE - lastRecordingStopElapsedMs) < VIGILANCE_MIN_GAP_MS) {
            return false;
        }
        // Rolling-window budget. Prune then count — engine-thread only.
        while (!vigilanceAssistStamps.isEmpty()
                && (nowE - vigilanceAssistStamps.peekFirst()) > VIGILANCE_ASSIST_WINDOW_MS) {
            vigilanceAssistStamps.pollFirst();
        }
        return vigilanceAssistStamps.size() < VIGILANCE_MAX_ASSISTS;
    }

    // MOTION THROTTLING: Process motion at 10 FPS max (saves 66% CPU vs 30 FPS)
    private static final long MOTION_PROCESS_INTERVAL_MS = 100;  // 10 FPS
    private long lastMotionProcessTime = 0;
    
    // ROI mask (null = full frame, otherwise byte array with 0/1 values)
    private byte[] roiMask = null;
    private int roiPixelCount = 0;  // Number of pixels in ROI (for normalization)
    
    // Reference to downscaler for buffer recycling
    private GpuDownscaler downscaler;
    
    // Reference to mosaic recorder for triggering recording
    private GpuMosaicRecorder recorder;
    
    // Block size for the motion grid. ALIAS of MotionPipelineV2.BLOCK_SIZE (which
    // itself mirrors the native V2_BLOCK_SIZE) — never an independent value.
    //
    // The per-quadrant COLS/ROWS/TOTAL that used to live here (20×15=300, derived
    // from a 640×480 frame) were dead and wrong: the live pipeline grids a 320×240
    // quadrant as 10×7=70 (MotionPipelineV2.GRID_COLS/GRID_ROWS/TOTAL_BLOCKS,
    // matching native). Nothing indexed blockConfidence with them — every real
    // consumer already qualifies with MotionPipelineV2 — but getTotalBlocks()
    // returned the 300, publishing a wrong block count over /api/surveillance
    // config. Removed rather than corrected so a future reader cannot pick the
    // unqualified name by accident and walk 300 entries of a 70-element array.
    private static final int GRID_BLOCK_SIZE = MotionPipelineV2.BLOCK_SIZE;
    
    // SIMPLIFIED: Frame-to-frame motion detection
    private int requiredActiveBlocks = 3;    // Need 3+ blocks changed to trigger
    
    // SOTA: Flash Immunity Level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
    // Uses edge-based detection to ignore light flashes while detecting real motion
    private int flashImmunity = 2;  // Default: MEDIUM
    
    // SOTA: Unified configuration for motion detection, flash filtering, and distance estimation
    private SurveillanceConfig config = createDefaultConfig();
    
    /**
     * Creates default config with proper resolution for mosaic mode.
     * SOTA: Enables chroma filtering by default to ignore lighting changes.
     */
    private static SurveillanceConfig createDefaultConfig() {
        SurveillanceConfig cfg = new SurveillanceConfig(
            SurveillanceConfig.DistancePreset.MEDIUM,
            SurveillanceConfig.FlashMode.ADAPTIVE
        );
        // CRITICAL: Set resolution to match THUMBNAIL dimensions
        cfg.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        cfg.setIsMosaic(true);  // We use 2x2 mosaic layout
        cfg.setUseChroma(true); // SOTA: Enable chroma filtering to ignore lighting changes
        return cfg;
    }
    
    // Track active blocks for UI display
    private int lastActiveBlocksCount = 0;
    private int lastTemporalBlocksCount = 0;  // SOTA: Temporally consistent blocks
    private int lastMotionMinY = 0;           // SOTA: Top of motion bounding box
    private int lastMotionMaxY = 0;           // SOTA: Bottom of motion bounding box
    private float lastEstimatedDistance = 0;  // SOTA: Estimated distance in meters
    
    // Pre-record and post-record configuration (configurable via API)
    private long preRecordMs = 5000;    // 5 seconds before motion (default)
    private long postRecordMs = 10000;  // 10 seconds after motion (default)
    private long recordingStopTime = 0;  // When to stop recording (motion time + post-record)
    private long lastRecordingStopTime = 0;  // When last recording stopped (for cooldown)
    // Monotonic twin of the above, for consumers that only measure an ELAPSED gap and
    // must not be fooled by a GPS/NTP wall-clock step (currently the salience min-gap).
    private volatile long lastRecordingStopElapsedMs = 0;
    private static final long NO_AI_MIN_GAP_MS = 30_000;
    // Monotonic trigger time for the current recording. Unconfirmed events keep
    // the short 3x-post-record ceiling; recently confirmed person events may
    // continue to the bounded emergency ceiling in RecordingContinuationPolicy.
    private long recordingTriggerStartElapsedMs = 0;
    private boolean confirmedPersonCeilingExtensionLogged = false;
    
    // DETERRENT FLASH SUPPRESSION: After the deterrent fires, suppress new motion triggers
    // for a window that covers the cloud API round-trip + flash sequence + ring buffer flush.
    // The BYD cloud flash_lights command has ~15s network latency (dispatch → poll → execute).
    // The lights then flash for 2-3 seconds. Total: 20 seconds from dispatch to scene stable.
    // This window prevents the deterrent's own light from triggering a second recording.
    //
    // WHY AI DOESN'T CATCH THIS (without the fix below):
    // The DetectionBaseline only filtered YOLO output for THREAT_LOW events. For MEDIUM/HIGH,
    // recording triggered purely from the motion pipeline. The deterrent light (and any
    // external light source) creates persistent edge differences that Stage 5 classifies as
    // THREAT_HIGH (loitering) or THREAT_MEDIUM (approaching). The fix extends the YOLO gate
    // to MEDIUM always, and to HIGH during the deterrent window.
    private static final long DETERRENT_SUPPRESSION_MS = 20000;
    private long deterrentFiredTime = 0;  // Timestamp when deterrent was last dispatched

    // NO-AI rate limit: minimum gap between consecutive motion-triggered recordings
    // when YOLO is unavailable (daemon-classpath OR user-disabled all classes). Without
    // YOLO, motion-only triggering can fire continuously on wind/shadow/streetlight
    // artifacts; each retrigger forces a fresh muxer init + pre-record flush, leaking
    // MediaCodec slots and direct-buffer RSS until the daemon SIGABRTs or LMK takes
    // it. 30s is empirically enough headroom that the muxer pool drains and the
    // codec's hardware refcount returns to baseline before the next event fires,
    // while still catching real intruders quickly (post-record covers the gap on
    // sustained loitering, and the trigger fires immediately past the gap if motion
    // continues). Only active when aiAvailable=false; YOLO installs are unaffected.
    
    // YOLO CONFIRMATION GATE: while AI is available, recording requires a
    // current-sequence person or a new/moved non-baseline object. Motion-only
    // timeout and proximity paths cannot authorize a static/unclassified scene.
    private volatile long lastAiConfirmationElapsedMs = 0;
    // When YOLO last confirmed a PERSON specifically. The track-anchored
    // confirmation + standing-person-immunity recency gates key on THIS (not the
    // class-agnostic timestamp): a held in-zone track is classId==0 (person), so
    // its "freshness" must be backed by a recent PERSON hit. Keying on the
    // class-agnostic timestamp let a passing CAR/BIKE certify a stale zombie
    // person track as fresh, firing a false recording on an unrelated burst.
    private volatile long lastPersonConfirmationTimeMs = 0;
    // Monotonic twin used by recording continuation and event evidence carry.
    private volatile long lastPersonConfirmationElapsedMs = 0;
    
    // Detection mode
    private boolean useObjectDetection = false;
    // FIX (A8/B3): volatile so the AI executor sees writes from the UI thread
    // without a torn read. The lambda still snapshots into a local before use.
    private volatile YoloDetector yoloDetector = null;
    // FIX (Bug B): retain context references so we can lazily re-init the YOLO detector
    // when the user re-enables object detection after disabling all classes.
    private android.content.Context yoloContext = null;
    private android.content.res.AssetManager yoloAssetManager = null;
    
    // Object detection filters (SOTA: Quadrant-relative height filter in YoloDetector)
    private float minObjectSize = 0.12f;  // 12% of QUADRANT height (~8m for person in 2x2 grid)
    private float aiConfidence = 0.25f;  // 25% confidence (lowered for debugging)
    // FIX (Bug B): tri-state semantics for classFilter:
    //   null            -> uninitialised, fall back to "all classes" defaults
    //   length == 0     -> user explicitly disabled all classes; YOLO must be skipped
    //   length >  0     -> only those COCO class IDs are kept
    private int[] classFilter = null;
    // CLASS/QUADRANT CONFIG EPOCH (audit R11-8 / ExtD-9). Bumped by
    // setObjectFilters and setV2QuadrantEnabled. The aiTask lambda captures
    // it at dispatch and re-checks it post-inference: a config change landing
    // inside the ~250-300ms detect() window used to let the in-flight result
    // publish under the OLD config — re-seeding the native track that the
    // toggle's drop loop had JUST dropped and re-stamping
    // lastPersonConfirmationTimeMs for a now-disabled class. With all classes
    // off that resurrection was un-teardown-able (aiEnabled=false blocks the
    // heartbeat runs whose teardown gate would have killed it) and could
    // extend a recording to the 3-minute tracker failsafe. Same pattern as
    // armEpoch: stale config ⇒ drop the whole publication (next run uses the
    // new config).
    private final java.util.concurrent.atomic.AtomicInteger classConfigEpoch =
            new java.util.concurrent.atomic.AtomicInteger();
    // ATOMIC (FILTER, EPOCH) PAIR (audit R13-5 / ExtE-5). The R11-8 dispatch
    // captured the filter and the epoch with TWO separate reads, and the
    // setter wrote the filter before bumping the epoch — a dispatch landing
    // between the two writes could pair the NEW filter with the OLD epoch
    // (or vice versa), and the post-detect epoch re-check would then wave
    // through a run whose config view was torn. This immutable holder is
    // published with a single volatile write (epoch allocated from the
    // AtomicInteger above so racing setters can't mint duplicates) and read
    // with a single volatile read at dispatch — the pair is coherent by
    // construction. The bare classFilter field survives only for
    // single-reference availability checks (aiAvailable, discard predicate,
    // baseline-person gate) that never pair it with the epoch.
    private static final class ClassConfig {
        final int[] filter;   // null = defaults; empty = all classes off
        final int epoch;
        ClassConfig(int[] filter, int epoch) { this.filter = filter; this.epoch = epoch; }
    }
    private volatile ClassConfig classConfig = new ClassConfig(null, 0);
    // Mirror of "should AI run at all" derived from classFilter; cheaper to read on hot path.
    private volatile boolean aiEnabled = true;
    
    // AI throttling - only run YOLO every 500ms to save CPU
    private long lastAiTimeMs = 0;
    private static final long AI_COOLDOWN_MS = 500;
    // FAST cadence for the close zone: when a HIGH/MEDIUM threat is in the
    // configured close zone, a real subject (person walking up) can be present
    // for barely 1s — at the 500ms base cadence + ~500ms CPU inference that is
    // only ~1-2 YOLO runs, so a person arriving after a first frame that caught
    // only (say) their parked bike is never classified before the sequence
    // decays → no person confirmation → no trigger (observed on-car: a person
    // parked a bike and walked up at 2.5m; YOLO ran twice, saw only the bike,
    // event ended in 1.1s WITHOUT trigger). Halving the cooldown while an object
    // is genuinely close doubles the classification chances in that short
    // window. Scoped to close-zone motion so steady-state CPU is unchanged.
    private static final long AI_COOLDOWN_CLOSE_MS = 250;

    // --- SOTA FIX: Persistent Resources (Eliminates GC Stutter) ---
    // 1. Reusable Buffer: Prevents ~900KB allocation per frame
    // Per-thread scratch buffer for cropFromMosaic. Previously a single
    // shared byte[] was racy: the main render thread (processFrame, tracker
    // update) and the aiExecutor thread (baseline seed / lighting refresh /
    // post-suppression refresh) both call cropFromMosaic, and concurrent
    // System.arraycopy into the same buffer can produce torn rows. All
    // call sites that retain the result already defensive-copy, but the
    // arraycopy ITSELF is racy — torn rows feed YOLO garbage. ThreadLocal
    // gives each thread its own scratch with no synchronization overhead.
    private final ThreadLocal<byte[]> aiBufferTL = new ThreadLocal<>();
    // 2. Single Thread Executor: Prevents OS thread creation overhead.
    //    Runs at THREAD_PRIORITY_BACKGROUND so a 200-300ms CPU YOLO inference
    //    can't preempt the camera-frame producer or encoder-feed thread.
    //
    //    GPU delegate intentionally removed (see YoloDetector class doc):
    //    on Adreno 610 / SD662 the GPU and the H.265 encoder share one DDR
    //    bus, and concurrent OpenCL inference produced 200–300 ms eglSwap
    //    stalls during recording. CPU XNNPACK at nice +10 wins/loses CFS
    //    contention as scheduled and never competes with the encoder for
    //    memory bandwidth on the GPU side.
    //
    //    The thread factory below sets BACKGROUND priority once at thread
    //    creation. {@link #priorityAffirmingExecutor} re-applies it at the
    //    start of every submitted task — this is the Android 11/12-portable
    //    defense against EAS scheduler migration that can otherwise reset a
    //    long-lived executor thread's priority class out from under us. On
    //    Android 10 the re-apply is a no-op steady-state; cost is one
    //    setThreadPriority syscall (~µs) per inference.
    private final ExecutorService aiExecutorRaw = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "SentryAiExecutor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * Wraps {@link #aiExecutorRaw} so every submitted Runnable is preceded
     * by a {@code Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)}
     * call. See the field comment above for why this is needed despite the
     * thread factory already setting priority once.
     */
    private final ExecutorService aiExecutor = new java.util.concurrent.AbstractExecutorService() {
        @Override public void execute(Runnable command) {
            aiExecutorRaw.execute(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                command.run();
            });
        }
        @Override public void shutdown()                         { aiExecutorRaw.shutdown(); }
        @Override public java.util.List<Runnable> shutdownNow()  { return aiExecutorRaw.shutdownNow(); }
        @Override public boolean isShutdown()                    { return aiExecutorRaw.isShutdown(); }
        @Override public boolean isTerminated()                  { return aiExecutorRaw.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException                      { return aiExecutorRaw.awaitTermination(timeout, unit); }
    };
    // 3. Atomic Flag for thread safety
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    // MOTION-LANE in-flight counter (audit R11-6 / ExtD-6). Incremented for
    // the duration of processFrame's working body (post-gate → finally).
    // disable() drains on it in addition to isAiRunning: the AiLaneWorker
    // checks isActive() only BEFORE entering processFrame, so a frame
    // admitted just before active=false used to run processFrameV2 (native
    // motion + MOG2 sampler + tracker updates, ~20-50ms) concurrently with
    // disable()'s whole teardown tail. Every overlap was individually
    // bounded (g_mog2_mutex, accepted torn-POD tracker class, catch-all in
    // the sampler) EXCEPT one: a straggler's MOG2 apply() landing after
    // releaseMOG2() lazily re-created the ~18.7MB native model, silently
    // defeating the disarm-period memory release for the whole drive. The
    // native side now also refuses post-release re-init (g_mog2_released
    // latch), so this drain is belt-and-braces that additionally closes the
    // accepted-class tracker overlap deterministically.
    private final java.util.concurrent.atomic.AtomicInteger motionFramesInFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    // CONSECUTIVE EMPTY-HEARTBEAT COUNTER (audit R14-1 / ExtF-1). One empty
    // YOLO heartbeat used to kill the NCC track immediately — but isolated
    // heartbeat misses on a REAL stationary person are seen in device logs
    // (occlusion wobble, exposure flicker, a borderline confidence tick),
    // and a dropped stationary person generates no motion to re-trigger
    // inference: the clip splits or the subject is lost until they move.
    // Drop now requires TWO consecutive empty heartbeats; any successful
    // match/refresh, semantic drop, or new track start resets the count. A
    // spared first miss leaves the heartbeat UNCONFIRMED, so the native
    // tracker re-requests promptly and the confirming retry lands within
    // seconds — a real departure therefore tears down one heartbeat interval
    // later than before (bounded; the post-record clock and hard ceiling
    // still own the stop). Touched only on the single-lane aiExecutor
    // (+ idempotent reset at enable()).
    private final int[] heartbeatMissCount = new int[MotionPipelineV2.NUM_QUADRANTS];
    private static final int HEARTBEAT_MISSES_TO_DROP = 2;

    // SystemClock.elapsedRealtime() at which the current inference claimed the AI
    // lane (0 = idle). MONOTONIC by contract — it is only ever used as one end of
    // an interval against AI_LANE_STUCK_MS, so a wall-clock source would let a
    // GPS/NTP correction either trip the watchdog on a healthy lane or silently
    // disable it. Written on the engine thread just before dispatch, read by the
    // watchdog on the same thread; volatile only so a debug/status reader sees a
    // sane value. Cleared by releaseAiLane AND by the watchdog.
    private volatile long aiRunStartedMs = 0;

    // Monotonic stamp for "which inference currently owns the AI lane". Bumped on
    // the engine thread each time the lane is claimed, AND by the watchdog when it
    // force-releases. An inference captures the stamp at dispatch and only clears
    // isAiRunning if it still matches — so a task the watchdog already gave up on
    // cannot unlatch a newer occupant, nor a lane that has since gone idle.
    private volatile long aiLaneStamp = 0;

    /**
     * How long {@link #isAiRunning} may stay latched before the watchdog treats
     * it as leaked.
     *
     * <p>A CPU-XNNPACK detect() is ~250-300 ms on this hardware, and the baseline
     * seeder can queue four back-to-back. The bar is nonetheless set to 60 s, an
     * order of magnitude above that, because the aiExecutor is shared with the
     * baseline seed/refresh paths ({@code detect()} calls that do NOT participate
     * in the {@code isAiRunning} latch) and because a genuinely slow filesystem
     * can stretch a queued task well past any inference budget. An overlong-but-
     * live inference must NOT be mistaken for a leak: the release is stamp-guarded
     * (see {@link #releaseAiLane}) so a false trip cannot unlatch a newer
     * occupant, but the cheapest way to keep two {@code detect()} bodies from
     * overlapping at all is to make a false trip effectively impossible.
     *
     * <p>60 s is still far below the ~12 min blind window the leak itself causes,
     * so the backstop retains its value.
     */
    private static final long AI_LANE_STUCK_MS = 5_000;

    // --- Per-stage detection counters (diagnostics only; never gate anything) ---
    // A user report of "it missed an event" is currently unfalsifiable: the only
    // counters are frameCount and motionDetections, and every one of the six
    // runAiOnQuadrant early-returns plus both in-lambda bail-outs is silent. These
    // let a single log line say which stage dropped the event.
    //
    // Atomic because the write sites straddle two threads: the engine thread
    // (dispatch + most skips) and the aiExecutor thread (detect-completed, and the
    // two in-lambda skip reasons). Nothing reads these to make a decision, so the
    // only requirement is that they don't tear or go backwards.
    private final java.util.concurrent.atomic.AtomicLong aiDispatchCount =
            new java.util.concurrent.atomic.AtomicLong();        // reached aiExecutor.execute()
    private final java.util.concurrent.atomic.AtomicLong aiDetectCompletedCount =
            new java.util.concurrent.atomic.AtomicLong();        // detect() returned (incl. empty)
    private final java.util.concurrent.atomic.AtomicLong aiSkipCount =
            new java.util.concurrent.atomic.AtomicLong();        // early-returned
    private final java.util.concurrent.atomic.AtomicLong aiLaneRepairCount =
            new java.util.concurrent.atomic.AtomicLong();        // watchdog releases
    private volatile String lastAiSkipReason = "none";

    // 4. Scheduler for staggered inference dispatch. Used by the baseline
    //    seeder to space the four per-quadrant YOLO calls ~500 ms apart.
    //    Firing them back-to-back back-pressures the single-thread
    //    aiExecutor with 4 × ~250 ms work items in a row (~1 s of pending
    //    inference), which delays the next legitimate motion-triggered
    //    YOLO call by up to a full second. Spacing the seed calls 500 ms
    //    apart keeps the executor available for live work between ticks.
    //    The actual detect() still runs on aiExecutor so all CPU TFLite
    //    state stays on a single thread (TFLite Interpreter is not
    //    thread-safe across runs).
    private final java.util.concurrent.ScheduledExecutorService aiScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SentryAiScheduler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    // 5. Storage-housekeeping executor. MUST NOT be aiExecutor.
    //    ensureSurveillanceSpace() takes surveillanceCleanupLock and walks the
    //    event directories; on a dropped/bridged SD mount it falls back to
    //    shell-based listing whose per-directory budget is measured in
    //    SECONDS. Running that on the single-thread aiExecutor blocks every
    //    detect() behind it — and because isAiRunning is latched true BEFORE
    //    the task is enqueued (see runAiOnQuadrant), all six dispatch sites
    //    then early-return silently. Field evidence (log_2C26G4RL,
    //    2026-07-19): SD remount failures at 08:17-08:20 coincided exactly
    //    with a ~43 s window where YOLO emitted zero "Detected N objects"
    //    lines while HIGH(loiter) motion fired continuously, and five real
    //    motion sequences expired WITHOUT trigger. Cleanup is pure
    //    housekeeping with no ordering relationship to inference, so it gets
    //    its own thread and can block as long as the filesystem needs.
    private final java.util.concurrent.ExecutorService storageMaintenanceExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SentryStorageMaint");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    // --- END SOTA FIX ---

    // State
    private volatile boolean active = false;
    // ARM-SESSION EPOCH (audit R3b Ext-2). Bumped once per enable() (both
    // modes), immediately before the volatile active=true publication.
    // Async work scheduled during a session (staggered baseline seeds,
    // post-suppression/lighting-transition refresh lambdas) captures the
    // epoch at schedule time and aborts when it no longer matches — a plain
    // `!active || isAccOn()` re-check cannot distinguish "still THIS arm
    // session" from "a NEW session after a disarm→rearm bounce", which let
    // a stale pre-bounce frame seed the new session's just-reset baseline.
    // Same pattern recordingGeneration already implements for recordings.
    // volatile: written on the control thread, read from aiScheduler /
    // aiExecutor lambdas.
    private volatile int armEpoch = 0;
    private boolean inActiveMode = false;
    // volatile: read cross-thread by hasActiveMotion()/isRecording() (RMM
    // control thread) to drive the parked-idle fps ramp. Written on the
    // motion/recording thread.
    private volatile boolean recording = false;
    // RECORDING LIFECYCLE SERIALIZATION (audit R3b Ext-3). startRecording()
    // is reachable from TWO threads (AiLaneWorker motion triggers and the
    // aiExecutor baseline-person trigger) and disable() runs on a control
    // thread; the bare volatile `recording` left a check-then-act gap wide
    // enough for the storage resolves (seconds under lock contention):
    //  - two starts could both pass `if (recording)` → the loser overwrote
    //    currentEventFile and cleared the live event's latches while the
    //    recorder kept the winner's path (metadata/notification lost);
    //  - a disarm mid-start saw recording==false, skipped the stop, and the
    //    start then committed recording=true with no live tick to ever stop
    //    it (orphan clip until the next disable()).
    // recordingLifecycleLock serializes the start claim/commit plus all
    // post-commit event initialization, and the complete stop/finalization
    // transaction. A successor event therefore cannot inherit or be damaged
    // by the previous event's clocks, listener, timeline, actor, or native
    // tracker cleanup. recordingStartInFlight is the claim token (guarded by
    // the lock).
    private final Object recordingLifecycleLock = new Object();
    private boolean recordingStartInFlight = false;  // guarded by recordingLifecycleLock
    // STATE-TRANSITION MONITOR (audit R13-2 / ExtE-3). Serializes the three
    // arm/disarm entry points — enable(), disable(), and
    // resumeAfterCameraReacquire() — end-to-end, making each a single
    // atomic state transition. Twelve audit rounds of point guards (epoch
    // checks, ordered publications, drains) each closed one interleaving of
    // the enable-vs-disable-vs-reacquire triangle and each external audit
    // found the next; the remaining irreducible window (a mid-arm enable
    // that has not yet bumped armEpoch being stomped by a disable tail) is
    // only closable by mutual exclusion. The epoch guards stay as
    // belt-and-braces for callers that bypass the monitor (none today).
    //
    // LOCK ORDER: stateTransitionLock → recordingLifecycleLock →
    // GMR.recordingLock → HERG.startStopLock — strictly outermost; nothing
    // that holds an inner lock ever calls enable/disable/resume (GMR/HERG
    // never call back into SEG; the aiScheduler retry ticks call
    // startContinuousRecording directly, below this monitor). Hold time is
    // bounded (worst: disable with a live recording ≈ drains 150ms + encoder
    // close ≤2s). Re-entrant by construction (synchronized), so the
    // processFrame ACC self-disable leg — which releases its frame-admission
    // count BEFORE calling disable() (audit R13-3) — cannot deadlock: it
    // merely blocks behind a concurrent transition, both of which are
    // time-bounded.
    private final Object stateTransitionLock = new Object();
    // True only when THIS engine's surveillance event opened the OEM
    // dashcam recording. Pre-fix the matching stop ran unconditionally,
    // finalizing a user-initiated continuous OEM recording mid-segment.
    // Now we track ownership: started-by-surveillance ⇒ stopped-by-surveillance;
    // already-recording-when-surveillance-fired ⇒ left alone on event end.
    private volatile boolean oemEventOwned = false;
    // Captured pipeline generation at the moment we acquired ownership.
    // If the pipeline is rebuilt (e.g. quality-mirror restart) BETWEEN
    // start and stop, the new instance's generation differs and we MUST
    // NOT stop it — that would kill the user's NEW continuous clip.
    private volatile int oemEventOwnedGeneration = -1;
    // ACC-OFF mode: false (default) runs the V2 motion + YOLO event pipeline;
    // true bypasses everything and writes a continuous rolling 4-cam mosaic.
    // Latched at enable() from UnifiedConfigManager.surveillance.accOffMode so
    // a mid-session config flip can't tear the recorder down half-way.
    private volatile boolean continuousMode = false;
    
    // V2 Pipeline: Per-quadrant 6-stage motion detection
    private MotionPipelineV2 pipelineV2 = null;
    private MotionPipelineV2.Config pipelineV2Config = null;
    // Staggered YOLO: queue of quadrants to run AI on.
    // Bounded + de-duplicating: there are only 4 quadrants, so a bitset-backed
    // ArrayDeque guarantees the queue can never grow past 4 entries no matter
    // how many motion events fire. Without this bound, sustained 4-quadrant
    // motion would pile up dozens of pending inferences (the executor processes
    // at AI_COOLDOWN_MS = 500ms; bursts at 10 FPS add 4 per frame), every one
    // of which holds GPU/DSP cycles when it eventually runs and contributes to
    // the recording stutter. add() is idempotent for already-queued quadrants.
    private final java.util.ArrayDeque<Integer> aiQuadrantQueue = new java.util.ArrayDeque<>(4);
    private int aiQuadrantQueueMask = 0;  // bit q = quadrant q is in queue
    // P1 #12: lock guards the deque + mask as one unit. Callers come from
    // AiLaneWorker (processFrameV2) and the aiExecutor lambda (heartbeat /
    // post-suppression refresh paths); without this the deque could throw
    // ConcurrentModificationException and mask/deque could decohere.
    private final Object aiQuadrantQueueLock = new Object();

    private void aiQuadrantQueueAdd(int q) {
        if (q < 0 || q >= MotionPipelineV2.NUM_QUADRANTS) return;
        int bit = 1 << q;
        synchronized (aiQuadrantQueueLock) {
            if ((aiQuadrantQueueMask & bit) != 0) return;  // already queued
            aiQuadrantQueueMask |= bit;
            aiQuadrantQueue.addLast(q);
        }
    }

    private Integer aiQuadrantQueuePoll() {
        synchronized (aiQuadrantQueueLock) {
            Integer q = aiQuadrantQueue.pollFirst();
            if (q != null) aiQuadrantQueueMask &= ~(1 << q);
            return q;
        }
    }

    private void aiQuadrantQueueClear() {
        synchronized (aiQuadrantQueueLock) {
            aiQuadrantQueue.clear();
            aiQuadrantQueueMask = 0;
        }
    }

    private boolean aiQuadrantQueueIsEmpty() {
        synchronized (aiQuadrantQueueLock) {
            return aiQuadrantQueue.isEmpty();
        }
    }
    
    // Foveated AI cropping: high-res 640×640 crop from raw camera strip
    private FoveatedCropper foveatedCropper = null;
    private int cameraTextureId = -1;  // OES texture for foveated crop
    // Vestigial. Foveated crops now run on AiLaneGl's dedicated GL thread
    // (which calls serviceFoveatedRequestsOnGlThread once per AI tick),
    // not posted via this handler. The setter is left in place because
    // PanoramicCameraGpu still calls it during initialization for backwards
    // compat with the API surface; the field is never read.
    private android.os.Handler glHandler = null;
    // Camera FPS — surfaced from PanoramicCameraGpu.setCameraTargetFps()
    // for diagnostic logging in the periodic stats line. No longer
    // load-bearing for any timeout (the cropOnGlThread path it used to
    // size was removed when the foveated mailbox replaced post-Runnable
    // dispatch).
    private volatile int cameraTargetFps = 0;

    // ==================== FOVEATED MAILBOX (Option B, full async) ====================
    //
    // Old design: AI worker called cropOnGlThread() which posted a Runnable to
    // glHandler and blocked waiting. That post competed with the render loop's
    // self-post for handler slots, AND FoveatedCropper.crop() internally called
    // glFinish() which blocks on any GPU work in flight (including unrelated
    // YOLO OpenCL jobs). Result: 100-300ms gaps in eglSwapBuffers cadence,
    // baked into the encoded MP4 as PTS jumps that play back as freeze+skip
    // at consistent timestamps regardless of player.
    //
    // New design: completely decoupled producer/consumer.
    //   - AI worker (any thread): calls requestFoveatedCrop(q, cx, cy) — sets
    //     a per-quadrant request flag with the latest centroid; non-blocking.
    //     Then calls pollFoveatedSlot(q) to read the most recent result; null
    //     if no result yet (caller falls back to mosaic for THIS tick).
    //   - GL thread: at the END of each renderLoop iteration, calls
    //     serviceFoveatedRequestsOnGlThread(). This walks the request flags,
    //     performs the crop synchronously on the GL thread (no hop, no post),
    //     deep-copies the result into the slot, clears the request flag.
    //
    // Cost: foveated results lag the requesting AI tick by ~1 motion cycle
    // (~100ms at V2's 10Hz). At 30 fps that's 3 frames behind — well below
    // human perception during playback overlay. Net benefit: zero GL handler
    // queue contention, zero glFinish cross-contention, encoder cadence
    // remains tight.
    private static final int FOVEATED_NUM_QUADRANTS = MotionPipelineV2.NUM_QUADRANTS;
    private final boolean[] foveatedRequested = new boolean[FOVEATED_NUM_QUADRANTS];
    private final float[] foveatedReqCentroidX = new float[FOVEATED_NUM_QUADRANTS];
    private final float[] foveatedReqCentroidY = new float[FOVEATED_NUM_QUADRANTS];
    private final Object foveatedRequestLock = new Object();
    /** Most-recent foveated crop result per quadrant. Slot value is immutable;
     *  publication replaces the AtomicReference. AI worker reads without lock. */
    @SuppressWarnings("unchecked")
    private final java.util.concurrent.atomic.AtomicReference<FoveatedSlot>[] foveatedSlots =
        (java.util.concurrent.atomic.AtomicReference<FoveatedSlot>[])
            new java.util.concurrent.atomic.AtomicReference[FOVEATED_NUM_QUADRANTS];
    {
        for (int i = 0; i < FOVEATED_NUM_QUADRANTS; i++) {
            foveatedSlots[i] = new java.util.concurrent.atomic.AtomicReference<>(null);
        }
    }
    /** Slot result is the deep-copied crop bytes plus the timestamp it was
     *  produced at (System.nanoTime() at publish). Consumers can compare the
     *  timestamp against frame time to decide whether the result is fresh
     *  enough to use; older than ~500ms = treat as stale. */
    private static final class FoveatedSlot {
        final byte[] rgb;
        final long publishedNanos;
        final int width;
        final int height;
        // Foveated-pixel → 320×240 block-grid affine that came WITH these
        // exact pixels through the async ring (see FoveatedCropper.Result).
        // blockGridX = mapAx*fx + mapBx ; blockGridY = mapAy*fy + mapBy.
        final float mapAx;
        final float mapBx;
        final float mapAy;
        final float mapBy;
        // False when the producing Result carried no real affine (legacy/first
        // publish/race) — the AI worker then FAILS SAFE (keeps the detection)
        // rather than dropping it, because a missed person is worse than an
        // over-inclusive recording.
        final boolean hasAffine;
        // CAPTURE time of the PIXELS (System.nanoTime at the cropper's queue
        // time — audit R4 ExtB-8). The PBO ring returns bytes 1-2 service
        // intervals (150-300ms+) older than the publish, so aging against
        // publishedNanos alone let total pixel age reach ~0.8s under a
        // "500ms" staleness budget. 0 = unknown (legacy Result) — consumers
        // fall back to publish-time aging.
        final long captureNanos;
        FoveatedSlot(byte[] rgb, long publishedNanos, int w, int h,
                     float mapAx, float mapBx, float mapAy, float mapBy,
                     boolean hasAffine, long captureNanos) {
            this.rgb = rgb;
            this.publishedNanos = publishedNanos;
            this.width = w;
            this.height = h;
            this.mapAx = mapAx;
            this.mapBx = mapBx;
            this.mapAy = mapAy;
            this.mapBy = mapBy;
            this.hasAffine = hasAffine;
            this.captureNanos = captureNanos;
        }
    }
    /** Stale threshold for slot results. ~500ms = ~15 frames at 30fps.
     *  Since audit R4 ExtB-8 this ages against the pixels' CAPTURE time
     *  (PBO-ring queue time), not the publish time, so the ring's
     *  150-300ms lag counts against the budget. */
    private static final long FOVEATED_SLOT_STALE_NANOS = 500_000_000L;

    /** Max DISPATCH-TIME capture age of foveated pixels whose detections may
     *  seed the NCC tracker (audit R4 ExtB-8; re-anchored per R7 ExtC-5).
     *  The seed pairs an affine-mapped box from the foveated pixels with the
     *  mosaic frame SNAPSHOTTED AT DISPATCH — both freeze there, so the
     *  pairing skew is dispatch − capture (ring lag + slot dwell), NOT the
     *  post-inference age the original gate measured (which stacked the
     *  ~250-300ms inference and made this budget unsatisfiable, silently
     *  killing all foveated seeding). A walking subject covers a full
     *  box-width in ~0.5-0.8s at 3-5m; 250ms of dispatch-time skew bounds
     *  the displacement to well under a box-width. A skipped seed is
     *  recoverable on the next tick (mosaic runs seed with age 0). */
    private static final long FOVEATED_SEED_MAX_AGE_NANOS = 250_000_000L;

    // GL-thread service throttle. The cropper is double-buffered (async readback
    // is non-blocking) but we still pay the 640×640 RGBA→RGB Y-flip on every
    // call. Capping to one service call per ~150 ms keeps the encoder GL thread
    // well under its 33 ms (30 fps) / 66 ms (15 fps) per-frame budget even on
    // worst-case event frames. V2 motion runs at 10 Hz internally, so 150 ms
    // is exactly one motion tick — zero AI cadence loss.
    private long lastFoveatedServiceNs = 0L;
    private static final long FOVEATED_SERVICE_INTERVAL_NS = 150_000_000L;


    // Cross-quadrant object tracker
    private final CrossQuadrantTracker crossQuadrantTracker = new CrossQuadrantTracker();

    // Actor-layer tracker — sits ON TOP of the existing YOLO + cross-quadrant
    // pipeline and emits Actor records carrying proximity / trend / severity for
    // the timeline + thumbnail + notification + UI layers. Does not affect motion
    // detection or recording trigger logic.
    private final ActorTracker actorTracker = new ActorTracker();

    // SHADOW-MODE multi-batch confirmation counter (parked-car single-frame
    // FP audit). LOG-ONLY — nothing in the trigger chain reads it. Counts
    // DISTINCT AI batches per CrossQuadrantTracker trackId and, at each
    // single-frame confirmation (the lastAiConfirmationElapsedMs stamp site),
    // logs what an N(class)-batches-within-this-sequence gate WOULD have
    // decided. Field-cycle goal: measure the FN cost (real short-window
    // subjects that would have been deferred) and the FP win (one-frame blips
    // that never reach N) before any enforcement. Candidate design +
    // thresholds documented on MultiBatchConfirmationShadow.
    private final MultiBatchConfirmationShadow aiConfirmShadow =
            new MultiBatchConfirmationShadow();
    // Snapshot of the most recent Actor list, for callers that read state.
    // CopyOnWrite to keep reads lock-free for UI / API threads.
    private volatile java.util.List<Actor> lastActors = java.util.Collections.emptyList();

    // Event-level peak severity, latched across the WHOLE recording. The two
    // Telegram stages (start ping + final photo/video) must gate on the same
    // value, otherwise a threat that escalated after the start snapshot drops
    // the opening ping, and one that receded (its actor TTL-pruned from
    // lastActors before stop) drops the closing photo+video — both gating on
    // an instantaneous lastActors snapshot that no longer reflects the event's
    // peak. null = "no severity observed yet this event" (fail-open, like the
    // rest of the notification system treats null). Reset at trigger, advanced
    // wherever lastActors is written.
    private volatile Actor.Severity eventPeakSeverity = null;

    // Event-peak actor retention. lastActors is the INSTANTANEOUS live snapshot,
    // continuously overwritten and TTL-pruned (TRACK_TTL_MS=5s). The JSON/SRT/stats
    // headline is built from that snapshot at event END — so an actor that was
    // significant DURING the event but departed before it ended (a person who came
    // very-close then walked away) is pruned and ERASED from the summary, leaving a
    // lingering far car as the misleading headline (observed on-car: SRT/tags say
    // "vehicle/far" while the timeline spans correctly recorded "person"). This map
    // accumulates each actor at its most-significant moment (highest severity; ties
    // broken by closest proximity) across the WHOLE event, keyed by actorId, NEVER
    // pruned, reset per event. It is UNIONed into the actor list handed to
    // stopAndWrite so a departed close person survives in the JSON actors[], stats
    // (peakSeverity/peakProximity/personCount), and SRT. Same per-AI-frame update
    // site as updateEventPeakSeverity; guarded by recordingGeneration like lastActors.
    private final java.util.Map<Long, Actor> eventPeakActors =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Event-level latches for the "empty bright motion event" discard (the
    // shadow-over-parked-car false positive: a sunlit surface, a sweeping shadow
    // classed MEDIUM(approach) or coherent HIGH(loiter), parked-car YOLO boxes
    // overlapping the shadow's motion blocks open the AI gate, yet the ActorTracker ends with 0 real
    // actors). All event-scoped: reset in startRecording alongside eventPeakActors,
    // read once in stopRecording's shouldDiscardEvent(). Volatile — written on the
    // engine + aiExecutor threads, read on the engine thread at stop.
    private volatile boolean eventTriggerWasMotionOnly = false;  // motion-source trigger, not tracker/deferred-person
    private volatile boolean eventEverApproaching = false;       // any non-static actor read APPROACHING this event
    private volatile boolean eventEverSawPerson = false;         // any YOLO-classified PERSON this event (any conf/sev/static) → hard KEEP
    private volatile boolean eventEverSawMovingObject = false;   // any YOLO-classified MOVING (!isStaticForTimeline) vehicle/bike/animal → hard KEEP (parked cars excluded — they are the FP target)
    private volatile boolean eventTriggerWasLateralMass = false; // trigger was a side-cam proximity-mass override → possible real lateral actor a fisheye-distorted YOLO can't classify → hard KEEP
    private volatile boolean eventTriggerWasAiTimeout = false;    // trigger fired on the AI-timeout fallback with NO in-sequence YOLO confirmation → could be a YOLO-missed real actor → hard KEEP
    // YOLO ran during this event AND produced raw class detections that the
    // downstream filters (baseline / motion-overlap / threat gate) then discarded.
    //
    // This is the evidence eventTriggerWasAiTimeout was TRYING to infer and cannot.
    // That latch is `!sequenceConfirmed`, and its comment assumes a shadow FP
    // "opens its AI gate via the PARKED CAR's own YOLO boxes, so sequenceConfirmed
    // == true and it stays discardable". Field log (2026-07-26 10:26:36 event)
    // falsifies that: lastAiConfirmationElapsedMs — the only setter of
    // sequenceConfirmed — is written only when relevantCount > 0, i.e. AFTER the
    // baseline filter, and the baseline filter's whole job is to suppress those
    // parked-car boxes. So on a scene with an established baseline the latch can
    // never arm, eventTriggerWasAiTimeout is true for essentially every shadow
    // event, and the discard feature is inert.
    //
    // Raw-detection evidence is the right discriminator: if YOLO looked at this
    // scene and DID resolve classes (which the filters then judged to be standing
    // scenery), then the "object too small/dark/distorted for YOLO but real"
    // rationale behind the AI-timeout KEEP does not apply, and the clip stays
    // eligible for discard. If YOLO produced nothing at all, that rationale DOES
    // apply and the KEEP still fires — which is the conservative direction.
    private volatile boolean eventYoloSawRawDetections = false;
    // Most recent raw enabled-class detection in each quadrant, stamped at the
    // source-frame observation time. startRecording() resets the event latch
    // above, so a raw result that opened the trigger gate immediately before
    // recording would otherwise be forgotten. The trigger path carries this
    // evidence only when it belongs to the current motion sequence.
    private final java.util.concurrent.atomic.AtomicLongArray lastRawDetectionElapsedMs =
            new java.util.concurrent.atomic.AtomicLongArray(MotionPipelineV2.NUM_QUADRANTS);
    // MOTION-EVIDENCE SEVERITY FLOOR (notification fix). When YOLO classifies 0
    // actors for the WHOLE event (model unloaded, subject too dark/distant/close
    // for the CPU model, foveated black-crop), eventPeakSeverity stays null and
    // both notification stages collapse to NOTICE — even though the RECORDING was
    // committed on a trusted HIGH loiter / NEAR-or-approaching proximity signal
    // the engine already deemed threat-worthy (the same evidence the AI-timeout
    // and close-zone-override record paths trust when YOLO is blind). This latch
    // records that "the trigger itself was a strong threat signal" so the final
    // notification can floor its severity to ALERT instead of silently NOTICE-ing
    // a real approach the user never gets pushed. Set once at trigger time (engine
    // thread), read at stop in publishMotionFinal / sendFinalTelegramNotification,
    // reset in startRecording. Deliberately NARROW: only trusted-HIGH or
    // NEAR/approaching triggers set it — plain LOW/MEDIUM passing motion stays
    // NOTICE so we don't flood the user with Alerts for distant passers-by.
    private volatile boolean eventTriggerWasStrongThreat = false;
    private volatile float   eventMaxLuma = 0f;                  // brightest quadrant meanLuma seen while recording
    private volatile float   eventMinLuma = Float.MAX_VALUE;     // darkest non-black quadrant meanLuma seen while recording
    // NIGHT-path discriminators (luma-free, YOLO-free) for the dark-scene discard.
    // The bright-everywhere clauses are meaningless after dark, so the night sub-
    // toggle instead rests on signals a real person walking through the dark never
    // produces: rigid flow translation (→ keep) vs confirmed in-place foliage/shadow
    // oscillation or a whole-quadrant illumination change (→ FP evidence). Latched
    // per-tick from results[q] on the engine thread, read once at stop.
    private volatile boolean eventEverSawCoherentMotion = false; // any tick with a rigidly-translating component (flowCoherence≥ratio or netDrift≥min) → a real moving subject → hard KEEP on the night path
    private volatile boolean eventSawNightFpEvidence = false;     // positive dark-scene FP evidence: confirmed in-place incoherent flow (tree/shadow) OR a whole-quadrant brightness-suppression event (IR/headlight) — REQUIRED for the night discard path
    private volatile boolean eventSawUncharacterizedMotion = false; // any tick where a quadrant had a formed motion component (componentSize>0) but flow could NOT resolve it (flowCoherence<0, e.g. a texture-poor dark crop) → the blob is unexplained (could be a dim real person YOLO/flow both missed) → hard KEEP on the night path (true scene-wide fail-open)
    // Every finalized segment of the CURRENT event (the final segment is
    // currentEventFile; earlier ones are added by the rotation listener). The
    // discard decision is whole-event, so a discard must delete ALL of them, not
    // just the final segment. CopyOnWriteArrayList — added on the finalizer
    // thread, read on the engine thread at stop.
    private final java.util.List<File> eventSegmentFiles =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    // Timeline collection generation for the CURRENT event/segment (audit
    // R11-4 / ExtD-5). Captured from every startCollecting* call (trigger
    // thread at event start; finalizer thread at rotation) and passed to the
    // flush → stopAndWrite chain so a stale stop (an old event's tail racing
    // a successor's already-restarted collection) no-ops instead of killing
    // the successor's collection and writing a zero-span sidecar. volatile:
    // written on the trigger/finalizer threads, read on the stop path.
    private volatile int timelineGen = -1;
    // Config-flag cache for the discard feature (default OFF → byte-identical).
    // 110, NOT 150: the BYD ISP clamps whole-quadrant mean luma to ~122 even in
    // full sun (motion_pipeline_v2.cpp:874; daytime sits ~115-130, night ~75-85),
    // so a 150 floor is almost never satisfiable and the feature would be inert
    // on-device. 110 sits in the safe gap — ABOVE the night real-person miss
    // (brightest quadrant ~96 → still KEEP) and the close-zone miss (46-61 →
    // still KEEP via this + the dark floor), BELOW the daytime clamp so a genuine
    // bright FP can actually clear it.
    private static final float DISCARD_BRIGHT_LUMA_THRESHOLD = 110f;  // event must be bright everywhere
    private static final float DISCARD_DARK_FLOOR = 70f;             // no quadrant may be dark (protects low-light/close-zone real person)

    // Filename of the last event whose FINAL notification was emitted. Guards
    // against a double final-send when stopRecording() is entered twice for one
    // event (disable() on the control thread racing the engine's post-record
    // stop). AtomicReference so the claim is a single atomic getAndSet — a plain
    // volatile check-then-set still lets both threads pass before either writes.
    // Reset is unnecessary — event filenames are unique (timestamped to the
    // second + the recorder refuses a same-name re-trigger), so a stale value
    // never false-blocks a new event.
    private final java.util.concurrent.atomic.AtomicReference<String> lastFinalNotifiedEvent =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Latch the event peak from a fresh actor snapshot (non-static actors only). */
    private void updateEventPeakSeverity(java.util.List<Actor> snapshot, long gen) {
        if (snapshot == null || snapshot.isEmpty()) return;
        // TOCTOU close: this runs on the aiExecutor thread; between the caller's
        // generation check (hundreds of ms upstream, before detect()) and here, a
        // new event's startRecording() can bump the generation + clear
        // eventPeakActors. Re-check the generation ADJACENT to the mutations so a
        // preempted gap-epoch lambda can't inject a stale actor (or resurrect the
        // scalar peak) into the next event's freshly-cleared summary.
        if (recordingGeneration.get() != gen) return;
        Actor.Severity max = eventPeakSeverity;
        for (Actor a : snapshot) {
            // Latch that a PERSON was YOLO-classified at all this event — a hard
            // KEEP for the discard (clause: eventEverSawPerson). Done BEFORE the
            // static-skip below and independent of confirmed/severity so a real
            // person the retain gate at :578 drops (unconfirmed 1-2 frame
            // far/mid lateral crosser at NOTICE) still protects its clip. Person
            // evidence can only ever PROTECT a clip, never delete one.
            if (a.classGroup == Actor.ClassGroup.PERSON) eventEverSawPerson = true;
            // Mirror the person KEEP for a MOVING vehicle/bike. A YOLO-classified
            // car/bike that is NOT timeline-static is a real moving object — the
            // discard must never delete it even if it stayed at NOTICE (a close
            // vehicle paralleling the car never escalates above NOTICE, so the
            // eventPeakActors retain gate at :578 drops it). CRUCIALLY gated on
            // !isStaticForTimeline so a PARKED car (the shadow-FP's whole reason
            // to exist) is NOT protected and the event stays discardable.
            if (!a.isStaticForTimeline
                    && (a.classGroup == Actor.ClassGroup.VEHICLE
                        || a.classGroup == Actor.ClassGroup.BIKE
                        || a.classGroup == Actor.ClassGroup.ANIMAL)) {
                // ANIMAL is forced to NOTICE by SeverityClassifier and never
                // enters eventPeakActors (retain gate needs >NOTICE), so without
                // this a real moving dog/deer in a bright lot would be discarded.
                eventEverSawMovingObject = true;
            }
            // Skip non-person statics (parked cars — SeverityClassifier forces
            // them to NOTICE anyway) but KEEP a static PERSON: a loiterer who
            // stood still is the threat, and the gate already treats it CRITICAL.
            // Use the timeline-static superset so a parked car that never latched
            // the severity-path isStatic under sparse cadence is also skipped
            // (isStaticForTimeline == isStatic for PERSON, so a loiterer is kept).
            //
            // NOTE: deliberately NOT extended with the hero's motion-grounded
            // staticness verdict (thumbnailBuffer.isActorStaticNonThreat). This is
            // eventPeakActors / discard-safety state, not a presentation surface:
            // skipping MORE actors here removes KEEP evidence and could make a real
            // event discardable. Widening a skip is safe for a caption, unsafe here.
            if (a.isStaticForTimeline && a.classGroup != Actor.ClassGroup.PERSON) continue;
            // Latch whether any NON-STATIC actor ever approached this event — a
            // discard clause (clause 4). Non-static only, so a parked car's
            // occlusion-jitter that briefly reads APPROACHING on a still-NOTICE
            // actor is excluded (matches the eventPeakActors retain guard below).
            if (!a.isStaticForTimeline && a.trend == Actor.Trend.APPROACHING) {
                eventEverApproaching = true;
            }
            if (a.peakSeverity != null
                    && (max == null || a.peakSeverity.ordinal() > max.ordinal())) {
                max = a.peakSeverity;
            }
            // Event-peak actor retention: remember this actor at its most
            // significant moment so it survives TTL-prune for the end-of-event
            // summary. Keep the version with higher peakSeverity; tie-break on
            // closer peakProximity (lower ordinal). Same static-skip as above so a
            // parked car never enters (it can't out-rank a real actor anyway).
            //
            // THREAT GATE (non-person): the isStatic skip above evaluates BEFORE a
            // vehicle latches isStatic (vehicles need 3 observations to reach
            // stableFrames>=2), so a parked car's FRAME-1 copy has isStatic=false
            // and would be stored, frozen (never re-pruned), then unioned back into
            // actors[]/SRT/stats at event-end — defeating every downstream
            // !a.isStatic gate and resurfacing the parked car as a "vehicle".
            // Retain a NON-PERSON actor ONLY once it actually became a threat:
            // peakSeverity above NOTICE. A genuinely approaching CLOSE/VERY_CLOSE
            // vehicle/bike already latches peakSeverity>NOTICE via SeverityClassifier,
            // so recall is preserved; a momentary occlusion-jitter that reads
            // trend==APPROACHING on a still-NOTICE parked car is NOT retained (the
            // old unguarded `trend==APPROACHING` clause froze exactly that frame —
            // isStaticForTimeline=false — and resurfaced the parked car). A
            // CONFIRMED PERSON is always retained (the departed-close-person
            // caption this map exists for), including a static loiterer — but a
            // 1-2 frame flicker-person (confirmed==false) is NOT, so a one-frame
            // YOLO false-positive can't add a spurious +1 person to the summary.
            boolean retain = (a.classGroup == Actor.ClassGroup.PERSON && a.confirmed)
                    || (a.peakSeverity != null && a.peakSeverity.ordinal() > Actor.Severity.NOTICE.ordinal());
            if (retain) {
                Actor prev = eventPeakActors.get(a.actorId);
                if (prev == null || isMoreSignificant(a, prev)) {
                    eventPeakActors.put(a.actorId, a);
                }
            }
        }
        eventPeakSeverity = max;
    }

    /** True if {@code a} is a "more significant" peak than {@code b}: higher
     *  severity, or equal severity but closer proximity. Used to retain the best
     *  moment of each actor across an event for the forensic summary. */
    private static boolean isMoreSignificant(Actor a, Actor b) {
        int sa = a.peakSeverity != null ? a.peakSeverity.ordinal() : 0;
        int sb = b.peakSeverity != null ? b.peakSeverity.ordinal() : 0;
        if (sa != sb) return sa > sb;
        int pa = a.peakProximity != null ? a.peakProximity.ordinal() : Integer.MAX_VALUE;
        int pb = b.peakProximity != null ? b.peakProximity.ordinal() : Integer.MAX_VALUE;
        return pa < pb;  // smaller ordinal = closer
    }

    /** Higher of two severities; null is treated as "no opinion" (lowest). */
    private static Actor.Severity maxOf(Actor.Severity a, Actor.Severity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    // Thumbnail capture buffer (Block C). Field declared here so the wiring point
    // in runAiOnQuadrant compiles even before Block C lands. Constructed/reset by
    // recording lifecycle handlers.
    private ThumbnailBuffer thumbnailBuffer = null;

    // FIX (B1/H-a): recording-generation counter. Bumped whenever a recording
    // ends and Actor/Thumbnail state is reset. The aiExecutor lambda captures
    // the value at scheduling time; on completion, it compares against the
    // current value and drops its writes (lastActors update, ThumbnailBuffer
    // observe, baseline promotion) if the generation has advanced. Without
    // this, a slow YOLO inference scheduled before stopRecording can repopulate
    // state for a recording that has already finished, polluting the next
    // recording's first frames.
    private final java.util.concurrent.atomic.AtomicLong recordingGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    
    // Heartbeat cooldown: prevent NCC tracker from spamming YOLO on every frame
    // when the template match is failing. Without this, a bad template causes
    // needsYoloHeartbeat=true on every frame, turning YOLO into a continuous
    // 10 FPS detector and destroying the battery savings of decoupled tracking.
    private static final long HEARTBEAT_COOLDOWN_MS = 5000;  // Min 5s between heartbeats per quadrant
    private final long[] lastHeartbeatTimeMs = new long[MotionPipelineV2.NUM_QUADRANTS];
    
    // Auto-exposure state (C++ handles per-quadrant threshold scaling,
    // Java only handles global params like brightness suppression and shadow filter mode)
    
    // Filter debug log: ring buffer of recent filter decisions (max 100 entries)
    private static final int FILTER_LOG_CAPACITY = 100;
    private final String[] filterLog = new String[FILTER_LOG_CAPACITY];
    private int filterLogIndex = 0;
    private int filterLogCount = 0;
    private boolean filterDebugEnabled = false;
    
    // RAM frame cache for zero-overhead fallback hero generation (bypasses MediaMetadataRetriever)
    private volatile byte[] latestFrameRgb = null;
    private volatile byte[] lastTriggerFrameRgb = null;
    
    // SOTA: Event timeline collector for JSON sidecar files
    private final EventTimelineCollector timelineCollector = new EventTimelineCollector();
    
    // SOTA: Detection baseline for filtering static objects from YOLO output.
    // Maintains a per-quadrant "living memory" of known scene objects so that
    // motion-triggered YOLO detections of parked cars, trash cans, etc. are
    // suppressed — only NEW or MOVED objects trigger recording.
    private final DetectionBaseline detectionBaseline = new DetectionBaseline();
    // Track whether baseline has been seeded (one-time on sentry enable)
    private volatile boolean baselineSeeded = false;
    // Track last YOLO detections per quadrant for event-end baseline update.
    // P1 #13: AtomicReferenceArray — written from aiExecutor lambda, read by
    // stopRecording() (recorder drainer thread) and reset by enable() / disable().
    // Plain array slot publication wasn't safe-published across threads. Readers
    // must tolerate null (they already do — null check before deref).
    //
    // <b>Single-atomic publication:</b> the detection list and the coord-space
    // frame height are packed into one immutable {@link YoloPublication} record
    // so a single AtomicReference write/read covers both. The earlier version
    // used two parallel atomics ({@code lastYoloDetections} and a sibling
    // {@code lastYoloFrameHeight}); the writer published list-then-height, the
    // reader read list-then-height in the SAME order, but a re-publish from
    // the AI thread between the reader's two reads could compose new
    // detections with the OLD height (or vice-versa). Re-audit caught a torn-
    // read window where mosaic detections (240-space) got read with a
    // foveated frame-height (640) → reader assumed foveated FOV scaling →
    // 3-5× distance overestimate intermittently. One atomic eliminates the
    // window — the reader sees either the old YoloPublication or the new
    // one, never a mix.
    private final java.util.concurrent.atomic.AtomicReferenceArray<YoloPublication> lastYoloPublication =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(MotionPipelineV2.NUM_QUADRANTS);

    /** Immutable snapshot of one quadrant's most recent YOLO output, plus
     *  the coord-space frame height the detections were computed against.
     *  Published as a unit so cross-thread readers can't see a torn state. */
    private static final class YoloPublication {
        final java.util.List<com.overdrive.app.ai.Detection> detections;
        final java.util.List<com.overdrive.app.ai.Detection> triggerDetections;
        final int frameHeightPx;
        // True when the SOURCE pixels were a foveated crop (audit R5 / R4
        // coords #1): the M2 fix publishes affine-mapped quadrant-space
        // boxes (frameHeightPx=240) for foveated runs, which made the
        // frameH>=640 isFoveated inference false and silently disabled the
        // tile-edge distance guard for exactly the detections it was added
        // for. Consumers needing "were these pixels foveated" read this
        // bit; consumers needing the coordinate SPACE keep frameHeightPx.
        final boolean wasFoveated;
        YoloPublication(java.util.List<com.overdrive.app.ai.Detection> detections,
                java.util.List<com.overdrive.app.ai.Detection> triggerDetections,
                int frameHeightPx, boolean wasFoveated) {
            this.detections = detections;
            this.triggerDetections = triggerDetections;
            this.frameHeightPx = frameHeightPx;
            this.wasFoveated = wasFoveated;
        }
    }
    // Track which quadrant had the last event (for event-end baseline update)
    private int lastEventQuadrant = -1;
    
    // POST-SUPPRESSION BASELINE REFRESH: When brightness suppression fires (lighting change),
    // queue a baseline refresh for after the scene stabilizes. This keeps the baseline
    // synchronized with what YOLO can actually see under the current lighting conditions.
    // Without this, a streetlight turning on makes a previously-invisible car "appear" to
    // YOLO as a new object → false trigger. Cost: 1 inference per quadrant per lighting
    // event (5-10 per night = negligible).
    //
    // We track per-quadrant: when suppression was last active, and whether we've already
    // refreshed after it. The refresh runs STABILIZATION_FRAMES after suppression ends
    // (to let the ISP settle and avoid refreshing on a transitional frame).
    private static final int BASELINE_STABILIZATION_FRAMES = 15;  // 1.5s at 10 FPS
    private final int[] framesSinceSuppressionEnded = new int[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] suppressionWasActive = new boolean[MotionPipelineV2.NUM_QUADRANTS];

    /**
     * Was there a brightness event in THIS quadrant — live this tick
     * ({@code results[q].brightnessSuppressed}) or within the decaying
     * {@code suppressionWasActive[q]} latch window (BASELINE_STABILIZATION_FRAMES,
     * ~1.5s past the last suppressed tick)?
     *
     * This is the EVIDENCE-SCOPED (invariant I3) replacement for the old
     * scene-wide any-quadrant OR: a headlight sweep on one camera must only
     * close the lighting-artifact paths for subjects that sweep could actually
     * be — i.e. in its own quadrant — never a concurrent real approach on a
     * different camera. Each consumer tests the quadrant(s) that can describe
     * its subject: the live best-threat quadrant PLUS its own evidence latch's
     * quadrant (close-zone / salience / vigilance). Including the latch quadrant
     * is what keeps the FP guard intact while the subject's own quadrant is
     * mid-suppression (a suppressed quadrant emits no motion, so best-threat
     * momentarily points elsewhere — the latch quadrant still tests true via
     * suppressionWasActive[q]).
     *
     * Fail-safe direction: q < 0 (no quadrant resolved) returns false, which
     * only matters for consumers whose own gates (peakCloseZone / salience /
     * vigilance latches) already require a resolved quadrant to be armed.
     */
    private boolean brightnessEventInQuadrant(int q, MotionPipelineV2.QuadrantResult[] results) {
        if (q < 0 || q >= MotionPipelineV2.NUM_QUADRANTS) return false;
        if (suppressionWasActive[q]) return true;
        return results != null && results[q] != null && results[q].brightnessSuppressed;
    }

    /**
     * Raw ±1s onset-vs-sequence-start correlation test (monotonic domain).
     * True when any quadrant's brightness-suppression ONSET lies within
     * LIGHTING_CORRELATION_MS of THIS sequence's start — the physical
     * signature of one lighting event both suppressing camera A and
     * creating the sequence via its light pool in camera B.
     */
    private boolean lightingOnsetCorrelatedWithSequenceStart() {
        if (firstMotionTime <= 0 || firstMotionElapsedMs <= 0) return false;
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            long onset = suppressionOnsetMs[q];
            if (onset > 0
                    && onset >= firstMotionElapsedMs - LIGHTING_CORRELATION_MS
                    && onset <= firstMotionElapsedMs + LIGHTING_CORRELATION_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Latching correlated-lighting verdict for the CURRENT sequence (audit
     * R3 fresh-eyes #2 + R3b Ext-10). Correlation is a property of the
     * sequence's creation; the raw test reads overwritable onset stamps, so
     * a later suppression edge in the same quadrant would erase it. Latch
     * on first pass; sequence start / enable() reset the latch. Consumers:
     * the three trigger-path brightness scopes, the MOG2 revival branch,
     * and the departure-brake baseline trust gate — one shared predicate so
     * a lighting-created sequence can neither take the fast paths nor
     * sustain/arm revival off its own light pool. Engine thread only.
     */
    private boolean lightingCorrelatedWithSequence() {
        if (!sequenceLightingCorrelated && lightingOnsetCorrelatedWithSequenceStart()) {
            sequenceLightingCorrelated = true;
        }
        return sequenceLightingCorrelated;
    }
    private final boolean[] baselineRefreshQueued = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    // Set when a quadrant's brightness-suppression latch decays (per-tick, in
    // processFrameV2) and consumed by the 500-frame periodic block, which owns the
    // actual YOLO baseline-refresh dispatch. Splits the cheap latch decay (must be
    // per-tick — the trigger path reads the latch) from the expensive refresh
    // inference (fine at the coarse cadence).
    private final boolean[] baselineRefreshDue = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    
    // Output directory
    private File eventOutputDir;
    // volatile: read by main render thread (publishMotionFinal,
    // sendFinalTelegramNotification) and written by the encoder drainer
    // thread (segment listener at rotation time). Without this, the main
    // thread could observe a stale File reference.
    private volatile File currentEventFile;
    
    // Frame dimensions - SOTA: Increased to 640x480 for better AI detection
    // At 320x240 with quad view, each camera is 160x120 - too small for YOLO
    // At 640x480 with quad view, each camera is 320x240 - YOLO can detect people at 5m
    private static final int THUMBNAIL_WIDTH = 640;
    private static final int THUMBNAIL_HEIGHT = 480;
    private static final int BYTES_PER_PIXEL = 3;  // RGB
    private static final int FRAME_SIZE = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * BYTES_PER_PIXEL;
    
    // Stats
    private int frameCount = 0;
    private int motionDetections = 0;
    
    // Cached latest mosaic frame for snapshot API (640×480 RGB)
    private volatile byte[] latestMosaicFrame = null;
    // Last wallclock time a HTTP/TCP client polled the snapshot getters.
    // Updated by getLatestMosaicFrame / getLatestMosaicJpeg. Used by
    // processFrame to skip the per-N-frame mosaic clone + JPEG encode
    // when no client has asked for a snapshot in a while — that work
    // costs ~3-5% sustained CPU on a parked car with no UI connected.
    private volatile long lastSnapshotPollMs = 0L;
    // 30 s grace window: any poll within the last 30 s keeps the snapshot
    // pipeline warm. Picked to match the WebSocket idle-shutdown cadence
    // and the typical web UI auto-refresh poll cycle (~5-10 s).
    private static final long SNAPSHOT_POLL_GRACE_MS = 30_000L;
    // JPEG-encoded snapshot of the cached mosaic. Refresh is OFF the
    // AiLaneWorker thread — every MOSAIC_JPEG_FRAME_MODULO frames we hand a
    // snapshot of the RGB mosaic to {@link #mosaicJpegExecutor} and let it
    // do the int[] alloc + Bitmap.compress. This keeps Bitmap.compress
    // (30–80 ms on the BYD SoC) off the motion-pipeline thread. Readers see
    // a plain volatile read.
    private volatile byte[] latestMosaicJpeg = null;
    // ~1 Hz at 15 fps. Fast enough that a dialog tile feels live, slow enough
    // that the JPEG encode doesn't impact other lanes.
    private static final int MOSAIC_JPEG_FRAME_MODULO = 15;
    // Single-thread bounded executor — drops new requests if the previous
    // encode hasn't completed (rather than queuing forever and producing
    // backlog). A bounded queue size of 1 with discardOldestPolicy keeps
    // the freshest pending request and skips intermediate ones. Daemon
    // thread so it doesn't block JVM shutdown.
    private final java.util.concurrent.ThreadPoolExecutor mosaicJpegExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "MosaicJpegEncoder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    // Reusable scratch buffers — avoid the per-encode int[] + Bitmap alloc
    // churn (~5 MB/sec at 30 fps cadence). Guarded by mosaicJpegExecutor's
    // single-thread invariant (no need for explicit lock).
    private int[] mosaicJpegPixelsScratch = null;
    private android.graphics.Bitmap mosaicJpegBitmapScratch = null;

    // Segment-metadata executor: hero JPEG compress + per-actor JPEGs +
    // JSON sidecar + SRT sidecar.
    //
    // PROBLEM IT FIXES: when called from the segment-rotation listener,
    // flushSegmentMetadata runs on a {@code GpuSegmentFinalizer-N} thread
    // which is what {@code waitForFinalizers} blocks on at recording-close
    // and pipeline-shutdown. Bitmap.compress(JPEG, 85) at 640×640 takes
    // ~50 ms on the BYD SoC; with 4 actors per segment that's 200+ ms of
    // the close-path's 2 s budget consumed by JPEG work. Multi-segment
    // events stack the cost across segments and can blow the budget.
    //
    // FIX: dispatch flushSegmentMetadata to this dedicated single-thread
    // executor so the finalizer thread returns immediately. The JPEG
    // writes proceed in the background and don't gate close-path or
    // rotation cadence. The executor has an unbounded queue (we want
    // every segment's metadata to be written eventually, never dropped),
    // but the close-path explicitly drains it before declaring shutdown
    // complete via {@link #drainSegmentMetadata}.
    //
    // Thread is BACKGROUND priority (nice +10) so its JPEG work yields to
    // the encoder/drainer/disk-writer if they need cycles.
    private final java.util.concurrent.ExecutorService segmentMetadataExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "SegmentMetadataWriter");
            t.setDaemon(true);
            return t;
        });
    // Name of the mp4 whose SYNC (publish-path) hero JPEG was actually written by
    // ThumbnailBuffer — i.e. a real foveated close-up crop with a threat box, not
    // the MP4-keyframe fallback. Read once by stopRecording to decide whether the
    // push body may say "close-up view". A name (not a boolean) so a stale value
    // from a previous event can never be mistaken for this event's verdict, and
    // not file existence, because an orphan .jpg at the same path is
    // indistinguishable from a fresh hero. Written on the segment-finalizer
    // thread, read on the engine thread → atomic.
    private final java.util.concurrent.atomic.AtomicReference<String> lastSyncHeroMp4Name =
        new java.util.concurrent.atomic.AtomicReference<>(null);
    // Track in-flight + queued metadata tasks so close-path can wait for
    // them to finish before tearing down state they reference.
    private final java.util.concurrent.atomic.AtomicInteger inFlightSegmentMetadata =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final Object segmentMetadataDrainLock = new Object();
    
    /**
     * Initializes the surveillance engine.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     */
    public void init(File eventDir, GpuDownscaler downscaler) {
        init(eventDir, downscaler, null, null);
    }
    
    /**
     * Initializes the surveillance engine with optional AssetManager for YOLO loading.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager) {
        init(eventDir, downscaler, assetManager, null);
    }
    
    /**
     * Initializes the surveillance engine with Context for Java TFLite.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager, android.content.Context context) {
        this.eventOutputDir = eventDir;
        this.downscaler = downscaler;
        // Retain for lazy YOLO re-init (Bug B fix path)
        this.yoloContext = context;
        this.yoloAssetManager = assetManager;
        // Construct thumbnail buffer once; it is reused across recordings (it
        // clears its slots itself at recording-stop).
        if (this.thumbnailBuffer == null) {
            this.thumbnailBuffer = new ThumbnailBuffer();
        }
        
        if (!eventDir.exists()) {
            eventDir.mkdirs();
        }
        
        // Allocate direct buffer for V2 pipeline JNI
        currentFrame = ByteBuffer.allocateDirect(FRAME_SIZE);
        currentFrame.order(ByteOrder.nativeOrder());
        
        // Detect available features
        try {
            // Initialize Java TFLite YOLO detector
            // Note: We don't have a full Context in daemon mode, but we can create one from AssetManager
            if (context != null) {
                try {
                    logger.info("Initializing Java TFLite YOLO detector...");
                    yoloDetector = new YoloDetector(context);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("YOLO backend: CPU XNNPACK (4-thread, encoder-isolated via nice gradient)");
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error initializing YOLO detector: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else if (assetManager != null) {
                // Daemon mode: Create minimal context from AssetManager
                try {
                    logger.info("Creating AssetContext for TFLite (daemon mode)...");
                    android.content.Context assetContext = new com.overdrive.app.ai.AssetContext(assetManager);
                    
                    yoloDetector = new YoloDetector(assetContext);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("YOLO backend: CPU XNNPACK (4-thread, encoder-isolated via nice gradient)");
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error creating AssetContext: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else {
                logger.info("No Context or AssetManager provided - object detection disabled");
                useObjectDetection = false;
            }
        } catch (UnsatisfiedLinkError e) {
            logger.warn("Native features not available: " + e.getMessage());
            useObjectDetection = false;
        }
        
        logger.info("Initialized surveillance engine (buffer=" + FRAME_SIZE + " bytes)");

        // Sweep orphan recordings: any .mp4 without a sibling hero .jpg is
        // either pre-fix legacy or a daemon-killed-mid-finalizer victim.
        // Generate fallback heroes from the mp4's first keyframe so the
        // events UI never shows a thumbnail-less card.
        // Async — must not block init().
        new Thread(() -> {
            try {
                if (!com.overdrive.app.byd.DiLink5Platform.isActive() && eventDir != null && eventDir.isDirectory()) {
                    sweepOrphanHeroThumbnails(eventDir);
                }
            } catch (Throwable t) {
                logger.debug("Orphan hero sweep failed: " + t.getMessage());
            }
        }, "OverdriveOrphanHeroSweep").start();

        // Initialize V2 per-quadrant pipeline
        try {
            pipelineV2 = new MotionPipelineV2();
            if (pipelineV2.init()) {
                pipelineV2Config = new MotionPipelineV2.Config();
                pipelineV2Config.applyEnvironmentPreset("outdoor");  // Default preset
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 per-quadrant pipeline initialized");
            } else {
                logger.error("V2 pipeline init failed");
                pipelineV2 = null;
            }
        } catch (Exception e) {
            logger.error("V2 pipeline not available: " + e.getMessage());
            pipelineV2 = null;
        }
    }
    
    /**
     * Sets the mosaic recorder for event recording.
     * 
     * @param recorder Mosaic recorder instance
     */
    public void setRecorder(GpuMosaicRecorder recorder) {
        this.recorder = recorder;
    }

    // Event-lifecycle callback into the owning pipeline so it can enable/disable
    // the (opt-in, default-off) surveillance telemetry overlay for the exact
    // duration of a sentry event clip — compositing/polling never run while the
    // engine is merely armed. Boolean arg: true at event start, false at stop.
    // Null-safe: unset in tests / non-pipeline embeddings.
    private volatile java.util.function.Consumer<Boolean> eventOverlayHook;

    /** Register the surveillance-overlay event hook (see {@link #eventOverlayHook}). */
    public void setEventOverlayHook(java.util.function.Consumer<Boolean> hook) {
        this.eventOverlayHook = hook;
    }

    /** Fire the event-overlay hook defensively (never let it break recording). */
    private void fireEventOverlayHook(boolean recordingActive) {
        java.util.function.Consumer<Boolean> h = eventOverlayHook;
        if (h == null) return;
        try {
            h.accept(recordingActive);
        } catch (Throwable t) {
            logger.warn("Event overlay hook failed: " + t.getMessage());
        }
    }

    /**
     * Set the foveated cropper for high-res AI inference.
     * When set, YOLO runs on a 640×640 crop from the raw 5120×960 strip
     * instead of the 320×240 mosaic quadrant. Must be called from GL thread.
     *
     * @param cropper FoveatedCropper instance (initialized on GL thread)
     * @param textureId Camera OES texture ID for direct strip access
     */
    public void setFoveatedCropper(FoveatedCropper cropper, int textureId) {
        this.foveatedCropper = cropper;
        this.cameraTextureId = textureId;
        if (cropper != null && cropper.isInitialized()) {
            logger.info("Foveated AI cropping enabled (640×640 from raw strip)");
        }
    }

    /**
     * Mark a quadrant as wanting a foveated crop on the next render loop pass.
     * Non-blocking. Latest centroid wins (overwrite semantics). Safe from any
     * thread.
     */
    public void requestFoveatedCrop(int quadrant, float centroidX, float centroidY) {
        if (quadrant < 0 || quadrant >= FOVEATED_NUM_QUADRANTS) return;
        synchronized (foveatedRequestLock) {
            foveatedRequested[quadrant] = true;
            foveatedReqCentroidX[quadrant] = centroidX;
            foveatedReqCentroidY[quadrant] = centroidY;
        }
    }

    /**
     * Read the latest foveated crop for a quadrant, if fresh.
     * Returns null if no result is available or the result is older than
     * FOVEATED_SLOT_STALE_NANOS (the AI worker will fall back to mosaic).
     * Non-blocking. Safe from any thread.
     */
    public byte[] pollFoveatedSlot(int quadrant) {
        FoveatedSlot slot = pollFoveatedSlotFresh(quadrant);
        return slot == null ? null : slot.rgb;
    }

    /**
     * Single-atomic-read variant returning the whole fresh slot (or null when
     * absent/stale), so the caller gets the rgb AND the foveated→block-grid
     * affine from the SAME publication. Reading rgb and the affine via two
     * separate polls could pair rgb from publication A with the affine from a
     * publication B that landed in between — this method reads the
     * AtomicReference exactly once to keep them coherent.
     */
    private FoveatedSlot pollFoveatedSlotFresh(int quadrant) {
        if (quadrant < 0 || quadrant >= FOVEATED_NUM_QUADRANTS) return null;
        FoveatedSlot slot = foveatedSlots[quadrant].get();
        if (slot == null) return null;
        // Age against CAPTURE time when known (audit R4 ExtB-8): the PBO
        // ring's 150-300ms+ lag was invisible to a publish-time age, letting
        // ~0.8s-old pixels pass the "500ms" budget — fast subjects then
        // seeded background templates and missed the motion-overlap window.
        long ageAnchor = slot.captureNanos > 0 ? slot.captureNanos : slot.publishedNanos;
        long age = System.nanoTime() - ageAnchor;
        if (age > FOVEATED_SLOT_STALE_NANOS) return null;
        return slot;
    }

    /**
     * GL-thread hook. Called from PanoramicCameraGpu.renderLoop once per
     * frame. Walks the per-quadrant request flags, performs each requested
     * crop synchronously on the GL thread (since this method is itself called
     * on the GL thread), publishes the result to the matching slot, and
     * clears the request flag. Deep-copies the cropper's internal buffer
     * because FoveatedCropper.crop() returns a pointer to a shared array.
     *
     * MUST be called on the GL thread that owns cameraTextureId.
     *
     * Performance: each crop is ~3-8ms (FBO blit + glReadPixels of 640×640
     * RGBA + Y-flip RGBA→RGB). Capped to one crop per render frame to keep
     * the per-frame budget predictable. If multiple quadrants request
     * simultaneously, they're serviced round-robin across consecutive
     * render frames.
     */
    public void serviceFoveatedRequestsOnGlThread() {
        FoveatedCropper cropper = this.foveatedCropper;
        int texId = this.cameraTextureId;
        if (cropper == null || texId < 0 || !cropper.isInitialized()) return;

        // SOTA throttle: even with double-buffered async readback, the row pack
        // + Y-flip costs ~3 ms per call on Adreno 610. Capping to one service
        // per 150 ms keeps total per-frame GL cost predictable across the
        // entire event window.
        long nowNs = System.nanoTime();
        if (nowNs - lastFoveatedServiceNs < FOVEATED_SERVICE_INTERVAL_NS) return;

        // Snapshot the requests under the lock, then service at most one per
        // call. Round-robin via a per-instance cursor so no quadrant starves.
        int qToServe = -1;
        float cx = 0f, cy = 0f;
        synchronized (foveatedRequestLock) {
            for (int i = 0; i < FOVEATED_NUM_QUADRANTS; i++) {
                int q = (foveatedRoundRobin + i) % FOVEATED_NUM_QUADRANTS;
                if (foveatedRequested[q]) {
                    qToServe = q;
                    cx = foveatedReqCentroidX[q];
                    cy = foveatedReqCentroidY[q];
                    foveatedRequested[q] = false;
                    foveatedRoundRobin = (q + 1) % FOVEATED_NUM_QUADRANTS;
                    break;
                }
            }
        }
        if (qToServe < 0) return;

        lastFoveatedServiceNs = nowNs;

        FoveatedCropper.Result result;
        try {
            // Async readback: this submits the CURRENT quadrant's render and
            // returns the PREVIOUS quadrant's bytes (which the GPU has already
            // finished). The result's quadrant field tells us which slot to
            // publish to. The very first call returns null — by design.
            result = cropper.crop(texId, qToServe, cx, cy);
        } catch (Throwable t) {
            logger.warn("Foveated crop (GL inline) failed: " + t.getMessage());
            return;
        }
        if (result == null || result.rgb == null || result.quadrant < 0
                || result.quadrant >= FOVEATED_NUM_QUADRANTS) {
            return;
        }
        // Deep-copy: cropper.rgb is a pointer to its shared internal buffer;
        // the next service call will overwrite it. The copy is 1.2 MB —
        // negligible vs. the 200 ms YOLO inference downstream.
        byte[] copy = new byte[result.rgb.length];
        System.arraycopy(result.rgb, 0, copy, 0, result.rgb.length);
        // Publish the crop rect's affine ATOMICALLY with the rgb — the same
        // AtomicReference swap. The affine describes the window THESE bytes
        // came from (which, on the async ring, is an earlier crop() call than
        // the centroid we just requested), so the bbox is mapped with the
        // matching window downstream.
        foveatedSlots[result.quadrant].set(new FoveatedSlot(
                copy, System.nanoTime(),
                result.width, result.height,
                result.mapAx, result.mapBx, result.mapAy, result.mapBy,
                result.hasAffine(), result.captureNanos));
    }

    private int foveatedRoundRobin = 0;

    /** GL handler for posting foveated crops back to the GL thread.
     *  Required when processFrame runs on AiLaneWorker. */
    public void setGlHandler(android.os.Handler glHandler) {
        this.glHandler = glHandler;
    }

    /** Camera target FPS — sizes the foveated GL-hop wait budget so the
     *  AI lane never times out on a normal-load render frame. */
    public void setCameraTargetFps(int fps) {
        if (fps > 0) this.cameraTargetFps = fps;
    }

    // cropOnGlThread() removed — foveated crops now run synchronously on the
    // GL thread inside serviceFoveatedRequestsOnGlThread() (called from
    // PanoramicCameraGpu.renderLoop). The AI worker uses the mailbox
    // (requestFoveatedCrop / pollFoveatedSlot) and never blocks on GL.

    /**
     * Get the current foveated cropper (for lazy-init check).
     */
    public FoveatedCropper getFoveatedCropper() {
        return foveatedCropper;
    }
    
    /**
     * SOTA: Updates the event output directory.
     * Called when storage type changes (internal <-> SD card) to ensure
     * events are saved to the correct location.
     * 
     * @param eventDir New directory for saving event recordings
     */
    public void setEventOutputDir(File eventDir) {
        this.eventOutputDir = eventDir;
        if (eventDir != null && !eventDir.exists()) {
            boolean created = eventDir.mkdirs();
            logger.info("Updated event output directory: " + eventDir.getAbsolutePath() + " (created=" + created + ")");
            if (created) {
                eventDir.setReadable(true, false);
                eventDir.setExecutable(true, false);
            }
        } else {
            logger.info("Updated event output directory: " + (eventDir != null ? eventDir.getAbsolutePath() : "null"));
        }
    }
    
    /**
     * Processes a frame from the GPU downscaler.
     * 
     * This is called at 2 FPS during idle mode. When motion is detected,
     * it can be called at 5 FPS for more responsive AI.
     * 
     * CRITICAL: This method receives a BORROWED buffer from the pool.
     * The buffer MUST be recycled in a finally block to prevent pool exhaustion.
     * If async AI is needed, the data must be copied before recycling.
     * 
     * @param smallRgbFrame 320x240 RGB frame from GPU (borrowed from pool)
     */
    public void processFrame(byte[] smallRgbFrame) {
        // ADMISSION PROTOCOL (audit R13-3 / ExtE-4): increment the in-flight
        // counter BEFORE reading `active`, then re-check under the counter.
        // The R11-6 order (check active, then increment) left an admission
        // gap: disable() could publish active=false, observe the counter at
        // zero, and begin native teardown while a frame that had already
        // passed the active check was still on its way to the increment.
        // With inc-then-check, any frame that passed the active read is
        // visible to the drain; a frame that reads active==false releases
        // the counter and leaves. The ACC self-disable leg below releases
        // the counter BEFORE calling disable() — otherwise it would wait on
        // itself in disable()'s drain (bounded, but pointless).
        motionFramesInFlight.incrementAndGet();
        if (!active) {
            motionFramesInFlight.decrementAndGet();
            // Still need to recycle even if not active
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }

        // Continuous mode: encoder is fed by the GL→encoder surface chain
        // independently of this method, so skip every CPU-side stage
        // (mosaic snapshot, motion throttle, V2 pipeline, YOLO). Still
        // recycle the borrowed buffer or the downscaler pool starves.
        if (continuousMode) {
            motionFramesInFlight.decrementAndGet();
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }

        // RACE CONDITION FIX (belt-and-suspenders): If somehow active=true but ACC is ON,
        // auto-disable. This catches the case where enable() raced with ACC ON and the
        // disable path hasn't run yet.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("processFrame: ACC is ON but surveillance is active — auto-disabling");
            // Release the admission BEFORE the (blocking) disable so its
            // drain doesn't count this thread's own admission (audit R13-3).
            motionFramesInFlight.decrementAndGet();
            disable();
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        if (smallRgbFrame == null || smallRgbFrame.length != FRAME_SIZE) {
            logger.warn( "Invalid frame size: " + (smallRgbFrame != null ? smallRgbFrame.length : 0));
            motionFramesInFlight.decrementAndGet();
            if (downscaler != null && smallRgbFrame != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        try {
            frameCount++;
            long now = System.currentTimeMillis();
            
            // Cache latest frame for snapshot API (every 10th frame). Skip
            // the 920 KB System.arraycopy when no client has polled within
            // the grace window — on a parked car with no UI connected,
            // this saves ~1 MB/s of continuous memcpy. Any poll bumps
            // lastSnapshotPollMs and re-warms the cache on the next tick.
            boolean snapshotClientActive =
                (now - lastSnapshotPollMs) < SNAPSHOT_POLL_GRACE_MS;
            if (frameCount % 10 == 0 && snapshotClientActive) {
                if (latestMosaicFrame == null || latestMosaicFrame.length != smallRgbFrame.length) {
                    latestMosaicFrame = new byte[smallRgbFrame.length];
                }
                System.arraycopy(smallRgbFrame, 0, latestMosaicFrame, 0, smallRgbFrame.length);
            }

            // Hand a snapshot of the cached mosaic to a dedicated single-
            // thread encoder executor. Bitmap.compress (30–80 ms) does NOT
            // run on AiLaneWorker — the motion pipeline keeps its frame
            // budget. The executor's queue is depth-1 with DiscardOldest so
            // a stalled encoder doesn't grow a backlog: the next tick just
            // replaces the pending request with the freshest mosaic.
            //
            // We CLONE here rather than capturing the latestMosaicFrame
            // reference — the cache buffer is rewritten in-place every 10
            // frames via System.arraycopy, which would tear the encoder's
            // pixel reads if it shared the buffer. The clone is bounded
            // (640×480×3 = 920 KB once per ~15 frames at 15 fps ≈ 1 Hz).
            // Same client-poll gate as the mosaic clone above: skip the
            // 920 KB clone + 30-80 ms Bitmap.compress when no client has
            // asked for a JPEG within the grace window. Saves ~3.5%
            // sustained CPU on a parked car with no UI connected.
            if (frameCount % MOSAIC_JPEG_FRAME_MODULO == 0
                    && latestMosaicFrame != null
                    && snapshotClientActive) {
                final byte[] snapshot = latestMosaicFrame.clone();
                try {
                    mosaicJpegExecutor.execute(() -> {
                        try {
                            byte[] encoded = encodeMosaicJpeg(snapshot);
                            if (encoded != null) latestMosaicJpeg = encoded;
                        } catch (Throwable t) {
                            logger.warn("mosaic jpeg encode failed: " + t.getMessage());
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // Executor was shut down (engine releasing). Skip.
                }
            }
            
            // Log frame count every 100 frames to confirm frames are arriving
            if (frameCount % 100 == 0) {
                logger.info("Surveillance frame #" + frameCount + " received");
            }
            
            // MOTION THROTTLING: Skip frames to achieve 10 FPS (saves 66% CPU)
            if (now - lastMotionProcessTime < MOTION_PROCESS_INTERVAL_MS) {
                return;
            }
            lastMotionProcessTime = now;
            
            if (pipelineV2 == null) {
                logger.warn("V2 pipeline not initialized — skipping frame");
                return;
            }
            
            processFrameV2(smallRgbFrame, now);
            
        } finally {
            motionFramesInFlight.decrementAndGet(); // audit R11-6 / ExtD-6
            // CRITICAL: Always recycle buffer back to pool
            // This MUST happen in finally block to prevent pool exhaustion
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
        }
    }
    
    /**
     * V2 Pipeline: Per-quadrant 6-stage motion detection.
     */
    // Track peak threat level during a motion sequence (reset when sequence ends)
    private int peakThreatDuringSequence = 0;

    // Whether the current sequence's THREAT_HIGH is "trusted" (coherent
    // translation OR an in-zone person tracker), latched at motion-start and
    // refreshed each qualifying frame. Read by the async YOLO decision matrix
    // (which runs on the aiExecutor thread and can't safely re-read the live
    // pipeline result) so both the synchronous gate and the matrix encode the
    // same "untrusted HIGH = flag/shadow → YOLO-gate it" invariant. volatile
    // for cross-thread visibility. Reset to false wherever firstMotionTime is
    // cleared. See COHERENCE_RATIO_MIN.
    private volatile boolean cachedHighIsTrusted = false;

    // Latched true when the native flow-coherence signal POSITIVELY reported an
    // incoherent in-place loiter (a confirmed flag / foliage / sweeping shadow)
    // during this sequence and no coherent/tracked frame ever appeared. Extends
    // the YOLO-confirmation timeout so a relentlessly-waving flag can't leak a
    // recording through the 2s fallback. NOT set on the fail-open (coherence
    // unavailable) path. Reset with cachedHighIsTrusted at each sequence start.
    private volatile boolean cachedIncoherentLoiter = false;

    // Previous frame sample for Java-side motion diff check (independent of native pipeline)
    private int[] prevFrameSamples = null;
    private int[] prevDenseHash = null;
    
    private void processFrameV2(byte[] smallRgbFrame, long now) {
        // Monotonic twin of `now` for cross-tick interval math (audit R2 #4):
        // wall clock can STEP on this platform (GPS/NTP re-sync on park),
        // which the correlated-lighting guard would read as a false ±1s
        // correlation (FN) or miss a real one (FP). Same pattern as
        // lastRecordingStopElapsedMs.
        final long nowElapsed = android.os.SystemClock.elapsedRealtime();
        // AI-lane liveness backstop. Runs first so a leaked isAiRunning latch is
        // released BEFORE this tick's dispatch sites consult it — otherwise the
        // repair would only take effect on the following tick. Pure liveness: it
        // can only re-enable inference, never suppress a detection.
        maybeRepairStuckAiLane();

        // Parked-idle throttle fps-ramp edge detector. Runs at the top of every
        // tick so it observes the hasActiveMotion() transition left by the PREVIOUS
        // tick — robust to every firstMotionTime reset path (including the
        // hard-ceiling stop that early-returns). On a change, push one RMM reconcile:
        // rising edge ramps the HAL up (motion began), falling edge ramps back to
        // idle fps (event fully ended, firstMotionTime cleared). No-op when the
        // throttle is off (reconcile returns the same intent). Cheap: one volatile
        // read + an int compare per tick, one reconcile only on an actual edge.
        if (com.overdrive.app.config.UnifiedConfigManager.isSurveillanceIdleThrottle()) {
            boolean active = hasActiveMotion();
            // Quiet-tier baseline (issue #174): seed on the first observed tick,
            // and hold it at `now` for as long as motion is active. While quiet it
            // stays fixed at the last-active (or first-seen) instant, so
            // getQuietDurationMs() measures the length of the current quiet run —
            // counting from boot if no motion has occurred, or from the falling
            // edge otherwise. On the rising edge this reset happens in the SAME
            // tick that pushes the reconcile, so the ramp-up sees quietDuration=0.
            if (lastMotionActiveWallMs == 0 || active) {
                lastMotionActiveWallMs = now;
            }
            int activeNow = active ? 1 : 0;
            if (activeNow != lastReconciledActiveState) {
                lastReconciledActiveState = activeNow;
                notifySurveillanceActivityChanged();
            }
        } else {
            lastReconciledActiveState = -1;  // re-arm so a re-enable pushes a fresh edge
            lastMotionActiveWallMs = 0;       // re-seed the quiet baseline on re-enable
        }

        // Copy frame data into a direct ByteBuffer for JNI
        currentFrame.clear();
        currentFrame.put(smallRgbFrame);
        currentFrame.flip();
        
        // Cache latest frame in RAM for zero-latency fallback hero generation
        if (smallRgbFrame != null && smallRgbFrame.length == FRAME_SIZE) {
            byte[] cached = latestFrameRgb;
            if (cached == null || cached.length != smallRgbFrame.length) {
                cached = new byte[smallRgbFrame.length];
                latestFrameRgb = cached;
            }
            System.arraycopy(smallRgbFrame, 0, cached, 0, smallRgbFrame.length);
        }
        
        // DIAGNOSTIC: Every 100 frames, check frame validity and inter-frame diff.
        // Only in debug builds — this is pure development tooling.
        if (com.overdrive.app.BuildConfig.DEBUG && frameCount % 100 == 0) {
            // Sample 16 pixels spread across the frame
            int[] currentSamples = new int[16];
            int[][] sampleCoords = {
                {60, 80}, {60, 240}, {60, 400}, {60, 560},    // Row 1
                {180, 80}, {180, 240}, {180, 400}, {180, 560}, // Row 2
                {300, 80}, {300, 240}, {300, 400}, {300, 560}, // Row 3
                {420, 80}, {420, 240}, {420, 400}, {420, 560}  // Row 4
            };
            boolean allBlack = true;
            for (int i = 0; i < 16; i++) {
                int off = (sampleCoords[i][0] * THUMBNAIL_WIDTH + sampleCoords[i][1]) * 3;
                if (off + 2 < smallRgbFrame.length) {
                    int r = smallRgbFrame[off] & 0xFF;
                    int g = smallRgbFrame[off + 1] & 0xFF;
                    int b = smallRgbFrame[off + 2] & 0xFF;
                    currentSamples[i] = (r << 16) | (g << 8) | b;
                    if (r > 5 || g > 5 || b > 5) allBlack = false;
                }
            }
            
            // Compare with previous frame samples
            int maxDiff = 0;
            int changedSamples = 0;
            if (prevFrameSamples != null) {
                for (int i = 0; i < 16; i++) {
                    int r1 = (currentSamples[i] >> 16) & 0xFF;
                    int g1 = (currentSamples[i] >> 8) & 0xFF;
                    int b1 = currentSamples[i] & 0xFF;
                    int r2 = (prevFrameSamples[i] >> 16) & 0xFF;
                    int g2 = (prevFrameSamples[i] >> 8) & 0xFF;
                    int b2 = prevFrameSamples[i] & 0xFF;
                    int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                    if (diff > maxDiff) maxDiff = diff;
                    if (diff > 10) changedSamples++;
                }
            }
            prevFrameSamples = currentSamples;
            
            // Also compute a dense diff: scan every 20th pixel across the full frame
            int denseMaxDiff = 0;
            int denseChanged = 0;
            int denseSamples = 0;
            if (prevDenseHash != null) {
                for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                    for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                        int off = (y * THUMBNAIL_WIDTH + x) * 3;
                        if (off + 2 < smallRgbFrame.length) {
                            int r = smallRgbFrame[off] & 0xFF;
                            int g = smallRgbFrame[off + 1] & 0xFF;
                            int b = smallRgbFrame[off + 2] & 0xFF;
                            int idx = denseSamples;
                            if (idx < prevDenseHash.length) {
                                int pr = (prevDenseHash[idx] >> 16) & 0xFF;
                                int pg = (prevDenseHash[idx] >> 8) & 0xFF;
                                int pb = prevDenseHash[idx] & 0xFF;
                                int diff = Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb);
                                if (diff > denseMaxDiff) denseMaxDiff = diff;
                                if (diff > 30) denseChanged++;
                            }
                            denseSamples++;
                        }
                    }
                }
            }
            // Store dense samples for next comparison
            int totalDense = (THUMBNAIL_HEIGHT / 20) * (THUMBNAIL_WIDTH / 20);
            if (prevDenseHash == null || prevDenseHash.length != totalDense) {
                prevDenseHash = new int[totalDense];
            }
            int di = 0;
            for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                    int off = (y * THUMBNAIL_WIDTH + x) * 3;
                    if (off + 2 < smallRgbFrame.length && di < prevDenseHash.length) {
                        int r = smallRgbFrame[off] & 0xFF;
                        int g = smallRgbFrame[off + 1] & 0xFF;
                        int b = smallRgbFrame[off + 2] & 0xFF;
                        prevDenseHash[di++] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            // Log sample pixels from each quadrant center
            int q0 = currentSamples[5];
            int q1 = currentSamples[6];
            int q2 = currentSamples[9];
            int q3 = currentSamples[10];
            
            logger.info(String.format("FRAME_DIAG #%d: %s | sparse: max=%d changed=%d/16 | dense: max=%d changed=%d/%d | Q0=(%d,%d,%d) Q1=(%d,%d,%d) Q2=(%d,%d,%d) Q3=(%d,%d,%d)",
                    frameCount,
                    allBlack ? "ALL_BLACK!" : "ok",
                    maxDiff, changedSamples,
                    denseMaxDiff, denseChanged, denseSamples,
                    (q0>>16)&0xFF, (q0>>8)&0xFF, q0&0xFF,
                    (q1>>16)&0xFF, (q1>>8)&0xFF, q1&0xFF,
                    (q2>>16)&0xFF, (q2>>8)&0xFF, q2&0xFF,
                    (q3>>16)&0xFF, (q3>>8)&0xFF, q3&0xFF));
        }
        
        // Run V2 pipeline (includes C++ Global Illumination Sync)
        MotionPipelineV2.QuadrantResult[] results = pipelineV2.processFrame(
                currentFrame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        
        // SOTA: Seed detection baseline once after camera warmup (frame 30 = ~3s at 10 FPS).
        // Runs YOLO on each quadrant to catalog what's already in the scene (parked cars,
        // trash cans, etc.) so future motion-triggered detections can filter them out.
        // Cost: 4 inferences, one-time. Runs on AI executor thread to avoid blocking motion pipeline.
        if (!baselineSeeded && frameCount == 30 && useObjectDetection && yoloDetector != null) {
            baselineSeeded = true;  // Set immediately to prevent re-entry
            final byte[] seedFrame = new byte[smallRgbFrame.length];
            System.arraycopy(smallRgbFrame, 0, seedFrame, 0, smallRgbFrame.length);
            // Stagger the four per-quadrant inferences instead of running them
            // back-to-back. With CPU XNNPACK each detect() runs ~250 ms; four
            // back-to-back inferences would block aiExecutor for ~1 s during
            // the seeding window, delaying the first legitimate
            // motion-triggered YOLO call by up to a full second. Spacing
            // them 500 ms apart keeps aiExecutor available for live work
            // between ticks.
            //
            // Scheduling is on aiScheduler; each tick re-dispatches to
            // aiExecutor because the TFLite Interpreter is not thread-safe
            // across run() calls (interpLock serialises all detect() calls,
            // and the single-thread aiExecutor is the contract that keeps
            // the lock uncontended steady-state).
            final int qW = THUMBNAIL_WIDTH / 2;
            final int qH = THUMBNAIL_HEIGHT / 2;
            final long staggerMs = 500L;
            logger.info("Scheduling staggered detection baseline seed (4 quadrants × " +
                    staggerMs + "ms apart) starting at frame 30");
            // Arm-session epoch captured at SCHEDULE time (audit R3b Ext-2):
            // a disarm→rearm bounce inside the ≤1.5s stagger window used to
            // pass the active/ACC re-checks and seed the NEW session's
            // just-reset baseline with the OLD session's frame.
            final int seedEpoch = armEpoch;
            for (int qi = 0; qi < MotionPipelineV2.NUM_QUADRANTS; qi++) {
                final int q = qi;
                aiScheduler.schedule(() -> {
                    // Outer-scheduler ACC-ON short-circuit: if ACC has turned
                    // ON between frame-30 enqueue and this tick (up to 1.5s
                    // for the 4th quadrant), don't even pay the
                    // aiExecutor.execute() dispatch cost. The inner lambda
                    // re-checks anyway as a defense in depth.
                    if (!active || armEpoch != seedEpoch
                            || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        if (q == 0) {
                            logger.info("Baseline seed dispatch skipped (surveillance inactive / ACC ON)");
                        }
                        return;
                    }
                    aiExecutor.execute(() -> {
                        // FIX (A8/B3): snapshot detector at lambda entry — see
                        // runAiOnQuadrant for rationale. Toggling AI off via
                        // setObjectFilters between schedule and execution
                        // would otherwise NPE or crash native TFLite.
                        final YoloDetector detectorSnap = yoloDetector;
                        if (detectorSnap == null || !aiEnabled) {
                            if (q == 0) {
                                logger.info("Baseline seed skipped (detector closed)");
                            }
                            return;
                        }
                        // ACC-ON guard: the staggered seed has up to ~1.5s of
                        // dispatch lag (4 quadrants × 500ms apart on
                        // aiScheduler, then re-dispatch onto aiExecutor). If
                        // ACC turns ON between schedule time and now, the
                        // surveillance session is logically over and a YOLO
                        // inference here would (a) burn CPU during a window
                        // where the user expects minimal load and (b) write
                        // baseline state for a session that's about to be
                        // torn down. Skip and let the next ACC-OFF session
                        // re-seed naturally. Epoch check (R3b Ext-2): also
                        // abort if this is no longer the SAME arm session.
                        if (!active || armEpoch != seedEpoch
                                || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                            if (q == 0) {
                                logger.info("Baseline seed skipped (surveillance inactive / ACC ON)");
                            }
                            return;
                        }
                        try {
                            byte[] quadCrop = cropFromMosaic(seedFrame, q, qW, qH);
                            if (quadCrop != null) {
                                java.util.List<com.overdrive.app.ai.Detection> dets =
                                        detectorSnap.detect(quadCrop, qW, qH,
                                                aiConfidence, true, true, false,
                                                true, minObjectSize);
                                // POST-INFERENCE epoch re-check (audit R4
                                // ExtB-2): the entry guard ran BEFORE the
                                // ~250ms detect(); a disarm→rearm bounce
                                // completing inside it would seed the NEW
                                // session's just-reset baseline with the OLD
                                // session's frame.
                                if (!active || armEpoch != seedEpoch) {
                                    return;
                                }
                                detectionBaseline.seedFromDetections(q, dets, qW, qH);
                            }
                        } catch (Exception e) {
                            logger.warn("Baseline seed failed for Q" + q + ": " + e.getMessage());
                        }
                        if (q == MotionPipelineV2.NUM_QUADRANTS - 1) {
                            logger.info("Detection baseline seeded for all quadrants");
                        }
                    });
                }, q * staggerMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
        
        // Per-quadrant override post-filter. The native pipeline ran with the
        // aggregate (most-permissive) sensitivity/zone, so each quadrant's
        // result currently reflects the loosest gates. Walk the quadrants and
        // demote any result that wouldn't pass its own effective gates.
        applyQuadrantOverrides(results);

        // BRIGHTNESS-SUPPRESSION LATCH BOOKKEEPING — MUST run every tick.
        //
        // suppressionWasActive[q] is read by the trigger path (see
        // brightnessEventInQuadrant and the brightnessEvent*Scope flags) where it
        // DISABLES three separate miss-reducing fast paths: the close-zone NEAR
        // short trigger, the close-zone proximity override, and the 2s AI-confirm
        // timeout (stretched to DETERRENT_SUPPRESSION_MS=20s). So a latch that
        // fails to clear leaves the subject's quadrant (formerly the whole
        // engine, when the flag was a scene-wide OR — since evidence-scoped per
        // I3) deaf to exactly the close, YOLO-invisible walk-up this system
        // exists to catch.
        //
        // This bookkeeping used to live inside the `frameCount % 500` periodic-stats
        // block, so framesSinceSuppressionEnded could only advance once per 500
        // frames. Clearing the latch needs BASELINE_STABILIZATION_FRAMES (15)
        // consecutive clear samples, which meant ~7500 frames — minutes of wall
        // clock — and any single suppressed sample landing on a 500-boundary reset
        // it to 0. One headlight sweep could therefore silence the fast paths for
        // the rest of the session (it survived only an enable() cycle, which is why
        // the symptom read as intermittent). The comment below always intended
        // per-tick decay; only the nesting was wrong.
        //
        // Strictly FP-neutral: every consumer uses this latch to SUPPRESS, so
        // clearing it sooner can only stop a stale artifact from blocking a real
        // trigger — it cannot manufacture one. The YOLO baseline-refresh DISPATCH
        // deliberately stays on the 500-frame cadence (below); only the counters
        // move here, so inference cost is unchanged.
        //
        // UNCONDITIONAL (audit R3 fresh-eyes #6): this used to be gated on
        // `baselineSeeded && useObjectDetection && yoloDetector != null`, so
        // in no-AI mode the latch was never SET and brightnessEventInQuadrant
        // lost its ~1.5s decay window — a just-ended sweep left the close-zone
        // NEAR fast-path (active in no-AI mode) open one tick after the live
        // flag dropped. The latch is a trigger-path discriminator, not an AI
        // feature; only the refresh DISPATCH needs AI, and its own block is
        // already gated. A baselineRefreshDue flag set while AI is off simply
        // never dispatches (and correctly fires once if AI is enabled
        // mid-session).
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            if (results[q].brightnessSuppressed) {
                suppressionWasActive[q] = true;
                framesSinceSuppressionEnded[q] = 0;
                baselineRefreshQueued[q] = false;
            } else if (suppressionWasActive[q]) {
                framesSinceSuppressionEnded[q]++;
                if (framesSinceSuppressionEnded[q] >= BASELINE_STABILIZATION_FRAMES) {
                    // Scene has stabilized — clear the flag so future sequences
                    // are not judged against a stale lighting artifact, and mark
                    // the quadrant as owing a baseline refresh. The refresh itself
                    // is a YOLO inference, so it is left to the 500-frame block to
                    // dispatch; decoupling the two is what lets the latch decay at
                    // tick rate without changing inference cost.
                    suppressionWasActive[q] = false;
                    baselineRefreshDue[q] = true;
                }
            }
        }

        // Brightness-suppression ONSET stamping — every tick, unconditionally
        // (like the suppressionWasActive bookkeeping above, un-gated per R3
        // fresh-eyes #6). Feeds the correlated-lighting guard in
        // the trigger path: correlation is measured between a suppression's
        // ONSET and the sequence's start, so the stamp must be an edge, not
        // a level.
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            boolean nowSuppressed = results[q].brightnessSuppressed;
            if (nowSuppressed && !brightnessPrevTick[q]) {
                // MONOTONIC stamp (audit R2 #4) — compared cross-tick against
                // the firstMotionElapsedMs twin, never against wall clock.
                suppressionOnsetMs[q] = nowElapsed;
            }
            brightnessPrevTick[q] = nowSuppressed;
        }

        // MOG2 PERSISTENT-FOREGROUND SAMPLER (stationary-subject revival).
        // Feeds the whole 640×480 mosaic to the native background model at
        // 2 FPS (its history=100 tuning cadence). One model, one stream —
        // per-quadrant fractions come back from the single apply(). First
        // call of a session reseeds the background from the live frame
        // (learningRate=1.0) so the previous parking spot can't bleed in;
        // the warmup gate below keeps the channel disarmed while the model
        // learns. Runs on the engine thread only (the native model is
        // single-stream by contract). Any failure marks the sample stale —
        // the consuming revival branch then simply has no evidence (I9).
        // MONOTONIC cadence (audit R3b Ext-13): a backward wall-clock step
        // (NTP/GPS re-sync while parked) froze the sampler for the whole
        // step span while the stale fraction kept passing freshness.
        if (mog2ChannelEnabled && mog2Available && smallRgbFrame != null
                && (lastMog2SampleMs == 0 || (nowElapsed - lastMog2SampleMs) >= MOG2_SAMPLE_PERIOD_MS)) {
            lastMog2SampleMs = nowElapsed;
            try {
                if (mog2Frame == null || mog2Frame.capacity() < smallRgbFrame.length) {
                    mog2Frame = java.nio.ByteBuffer.allocateDirect(smallRgbFrame.length);
                }
                // Shadow the OUTGOING sample before this apply() overwrites
                // it (audit R5 — ambient source, see mog2QuadFracPrev doc).
                if (mog2FracAtMs > 0) {
                    System.arraycopy(mog2QuadFrac, 0, mog2QuadFracPrev, 0,
                            MotionPipelineV2.NUM_QUADRANTS);
                    mog2FracPrevAtMs = mog2FracAtMs;
                }
                mog2Frame.clear();
                mog2Frame.put(smallRgbFrame, 0, smallRgbFrame.length);
                // LEARNING-RATE SCHEDULE (audit R3b Ext-5). Call 0: lr=1.0
                // reseeds the background from the live frame. Warmup: auto
                // (1/min(2n,history)) learns the scene fast. AFTER warmup:
                // FIXED lr=0.001, because auto's steady 1/100 absorbs a
                // stationary subject in ~5s, not the ~50s this channel was
                // built on — OpenCV flips a pixel to background as soon as
                // the OLD background mode's weight decays below
                // backgroundRatio (0.9), i.e. after ~10-11 apply() calls at
                // lr=0.01 ((1-a)^n < 0.9), NOT when the subject's own mode
                // reaches 0.9 (~229 calls). At lr=0.001 absorption takes
                // ~105 samples ≈ 53s at 2Hz — matching the 60s revival
                // budget and the loiter windows this channel serves. Trade:
                // genuine scene changes (fresh parker) stay foreground ~50s
                // too, which the min-frac gate, departure brake, quadrant
                // scoping, revive-only rule and budget already police.
                float lr = (mog2CallCount == 0) ? 1.0f
                        : (mog2CallCount <= MOG2_WARMUP_CALLS ? -1.0f : 0.001f);
                float total = NativeMotion.computeMOG2Quadrants(
                        mog2Frame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, lr, mog2QuadFrac);
                if (total >= 0f) {
                    mog2CallCount++;
                    mog2FracAtMs = nowElapsed;  // monotonic domain (R3b Ext-13)
                }
            } catch (Throwable t) {
                // Adding-only channel: no sample, no revival. Never throws out.
            }
        }

        // MOTION-SALIENCE EVALUATION — MUST run every tick, like the latch
        // bookkeeping above and for the same reason (invariant I2): the run
        // counters below are read by the trigger path, so maintaining them on a
        // periodic cadence would make a 0.7s sustain requirement take minutes.
        //
        // Per quadrant, a tick QUALIFIES when the motion is object-shaped on five
        // independent axes. All five are POSITIVE tests (see the field comments):
        //   1. MASS      — largest component >= salienceMinBlocks.
        //   2. COMPACT   — that component dominates the confirmed mass, and the
        //                  scene isn't fragmented into many blobs (rain/wipers).
        //   3. TRANSLATING — native flow coherence says rigid motion. Coherence
        //                  UNAVAILABLE (-1) does NOT qualify: this channel only
        //                  ADDS triggers, so missing evidence must mean "no",
        //                  never "assume yes".
        //   4. PHOTOMETRIC — no brightness suppression on this quadrant, and mean
        //                  luma has not drifted from the run's anchor. A person
        //                  changes scene geometry; a light changes scene luma.
        //   5. IN ZONE   — the quadrant still reports motionDetected at MEDIUM+
        //                  after applyQuadrantOverrides, i.e. it passed the user's
        //                  sensitivity + maxDistanceRow gates. Salience never
        //                  widens the user's configured zone.
        // A non-qualifying tick resets that quadrant's run to 0 immediately: the
        // requirement is CONSECUTIVE, so a flicker cannot accumulate to a trigger.
        // Consume a pending flag-flip reset FIRST, and outside the salienceEnabled
        // guard — an OFF transition must be honoured even though the evaluator below
        // is then skipped. This is the only writer of these fields (setConfig merely
        // requests); see salienceResetRequested.
        if (salienceResetRequested) {
            salienceResetRequested = false;
            salienceConfirmedDuringSequence = false;
            salienceQuadrant = -1;
            salienceConfirmedAtMs = 0;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                salienceRunTicks[q] = 0;
                salienceLumaAnchor[q] = 0f;
            }
        }
        if (salienceEnabled && pipelineV2 != null && pipelineV2.isNativeSalienceSupported()) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                boolean qualifies = false;
                if (r != null
                        && r.motionDetected
                        && r.threatLevel >= MotionPipelineV2.THREAT_MEDIUM
                        && !r.brightnessSuppressed) {
                    boolean mass = r.componentSize >= salienceMinBlocks;
                    // componentCount==0 means the native side didn't report it;
                    // treat as unknown → fails the compactness test (positive-
                    // evidence stance). >3 blobs is a diffuse scene response.
                    boolean compact = r.confirmedBlocks > 0
                            && ((float) r.componentSize / (float) r.confirmedBlocks) >= salienceDominanceFrac
                            && r.componentCount >= 1 && r.componentCount <= 3;
                    boolean translating = r.flowCoherence >= 0f
                            && (r.flowCoherence >= COHERENCE_RATIO_MIN
                                || r.netDriftBlocks >= COHERENCE_NET_MIN);
                    // Anchor the luma on the run's FIRST qualifying tick, then
                    // require stability against that anchor for the whole run.
                    boolean lumaStable;
                    if (salienceRunTicks[q] == 0) {
                        lumaStable = r.meanLuma > 0f;
                    } else {
                        lumaStable = r.meanLuma > 0f
                                && Math.abs(r.meanLuma - salienceLumaAnchor[q]) <= salienceMaxLumaDelta;
                    }
                    qualifies = mass && compact && translating && lumaStable;
                }
                if (qualifies) {
                    if (salienceRunTicks[q] == 0) salienceLumaAnchor[q] = results[q].meanLuma;
                    // Saturate rather than increment forever: only the >= comparison
                    // matters, and an unbounded counter on a days-long park would
                    // eventually wrap negative and silently disarm the channel.
                    if (salienceRunTicks[q] < salienceSustainTicks) salienceRunTicks[q]++;
                    // Refresh the freshness stamp on every qualifying tick once the run
                    // is complete, so a subject that is genuinely still there keeps the
                    // latch alive without re-earning all 6 ticks. `logFirst` keeps the
                    // INFO line a state-change event, not a per-tick one.
                    if (salienceRunTicks[q] >= salienceSustainTicks) {
                        boolean logFirst = !salienceActive() || salienceQuadrant != q;
                        salienceConfirmedDuringSequence = true;
                        salienceQuadrant = q;
                        salienceConfirmedAtMs = android.os.SystemClock.elapsedRealtime();
                        MotionPipelineV2.QuadrantResult sr = results[q];
                        if (logFirst) logger.info(String.format(
                                "Motion salience CONFIRMED on %s: component=%d/%d (dominance=%.2f, "
                                + "blobs=%d) coherence=%.2f netDrift=%.1f luma=%.0f(anchor %.0f) "
                                + "ticks=%d — object-grade motion evidence without YOLO",
                                MotionPipelineV2.QUADRANT_NAMES[q], sr.componentSize,
                                sr.confirmedBlocks,
                                sr.confirmedBlocks > 0
                                        ? (float) sr.componentSize / sr.confirmedBlocks : 0f,
                                sr.componentCount, sr.flowCoherence, sr.netDriftBlocks,
                                sr.meanLuma, salienceLumaAnchor[q], salienceRunTicks[q]));
                    }
                } else {
                    salienceRunTicks[q] = 0;
                }
            }
        }

        // Accumulate per-tick min/max quadrant luma WHILE RECORDING, for the
        // empty-bright-motion discard's brightness clauses. The existing avgLuma
        // loop runs only inside the every-500-frames stats block, too coarse to
        // characterize a short event — sample every recording tick instead. Guard
        // meanLuma>0 so an inactive/black quadrant can't spuriously trip the dark
        // floor (which would wrongly BLOCK a valid discard). Cheap: 4 compares,
        // only while recording.
        if (recording) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                float l = results[q].meanLuma;
                if (l > 0f) {
                    if (l > eventMaxLuma) eventMaxLuma = l;
                    if (l < eventMinLuma) eventMinLuma = l;
                }
                // Night-path discriminators (luma-free, YOLO-free). The flow-
                // coherence signal (native Stage 4b) is the same one the live
                // flag/shadow guard trusts; here we accumulate it across the WHOLE
                // event so a dark scene can be characterized without brightness.
                MotionPipelineV2.QuadrantResult r = results[q];
                if (r.flowCoherence >= 0f) {
                    // A rigidly-translating component appeared this tick — a real
                    // moving subject (person/vehicle), never a waving flag or an
                    // in-place shadow. Any single coherent tick hard-KEEPS the clip
                    // on the night path. Mirror of highThreatIsTrusted's OR test.
                    if (r.flowCoherence >= COHERENCE_RATIO_MIN
                            || r.netDriftBlocks >= COHERENCE_NET_MIN) {
                        eventEverSawCoherentMotion = true;
                    } else if (r.componentSize > 0) {
                        // Signal FIRED and reported incoherent, in-place motion on a
                        // formed component — a confirmed tree/foliage/sweeping-shadow
                        // oscillation. Positive FP evidence for the night discard.
                        eventSawNightFpEvidence = true;
                    }
                } else if (r.componentSize > 0 && !r.brightnessSuppressed) {
                    // A formed motion component that the flow stage could NOT resolve
                    // (flowCoherence==-1: too few textured blocks to block-match — the
                    // texture-poor dark-crop signature) AND is not itself explained by
                    // a brightness event. This could be a dim real person that BOTH
                    // YOLO and flow missed. Because the two latches above are OR-
                    // accumulated scene-wide, FP evidence from a DIFFERENT quadrant (a
                    // swaying branch / a headlight in Q2) must NOT override this
                    // quadrant's unexplained blob. Latch it → hard KEEP on the night
                    // path, restoring a true scene-wide fail-open. The !brightnessSuppressed
                    // guard is essential: a headlight/IR flash can itself form an
                    // unresolved (flow==-1) component, and without the guard that FP —
                    // the one the user wants discarded — would spuriously self-KEEP.
                    // (netRingCount saturates during the long pre-record park, so post-
                    // park a -1 here is genuine unmatchable texture, not flow warmup.)
                    eventSawUncharacterizedMotion = true;
                }
                // A whole-quadrant illumination change (headlight sweep / IR
                // reflection / glare) — never produced by a person crossing the
                // scene. Also counts as positive night-FP evidence. Reuses the
                // Stage-1 global-brightness flag (motion_pipeline_v2.cpp:141).
                if (r.brightnessSuppressed) {
                    eventSawNightFpEvidence = true;
                }
            }
        }

        // Check if any quadrant detected motion at MEDIUM or higher threat.
        int maxThreat = pipelineV2.getMaxThreatLevel();
        boolean anyMotion = maxThreat >= MotionPipelineV2.THREAT_MEDIUM;
        
        // SOTA: Tracker immunity from brightness suppression (Headlight Sweep Fix).
        // When a car's headlights sweep across the camera, the brightness suppression
        // stage kills ALL motion blocks in that quadrant. If a person is being tracked
        // in that quadrant, the motion sequence timer loses them and the recording
        // stops prematurely. Fix: if any quadrant is brightness-suppressed but the
        // NCC tracker has an active lock on it, keep anyMotion=true so the sequence
        // timer continues. The tracker's pixel-level lock is immune to global
        // brightness changes — it tracks texture, not absolute luminance.
        // FIX: Only person tracks (classId==0) get immunity. Vehicle tracks
        // (motorcycles, cars) should not override brightness suppression.
        if (!anyMotion) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].brightnessSuppressed) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                // ZONE GATE: don't grant immunity to a tracker
                                // bbox that's outside the user's configured
                                // detection zone. Without this check, a person
                                // tracked in row 0-1 (far from the car) would
                                // bypass the row gate during any brightness-
                                // suppression frame — recording fires for an
                                // object the user explicitly excluded.
                                if (!trackerInZone(q)) continue;
                                anyMotion = true;
                                if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                                    maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                                }
                                if (frameCount % 50 == 0) {
                                    logger.info("Headlight sweep immunity: Q" + q +
                                            " [" + MotionPipelineV2.QUADRANT_NAMES[q] +
                                            "] suppressed but tracker holds person lock");
                                }
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // STANDING-PERSON IMMUNITY (motion-block decay, not brightness).
        // A person who walks up and then STANDS STILL sheds almost all motion
        // blocks: frame-differencing sees nothing when nothing moves, so the
        // native classifier drops them from MEDIUM(approach) to LOW(pass) (their
        // sparse edge-blocks jitter the centroid past the loiter radius, they're
        // not translating toward centre, and block-mass falls under the 0.15
        // side-camera threshold). At that point anyMotion goes false, the
        // sequence falls into the no-motion gap branch, and motionDuration
        // (lastMotionTime − firstMotionTime) FREEZES at the ~1s of walk-up —
        // it never reaches the 3s sustained bar, so a person standing dead-still
        // 1.6 m from the car never records (observed on-car: "lasted=0.9s,
        // required=3.0s (tracker was active)"). The brightness-immunity branch
        // above is the intended safety net but only deploys when blocks were
        // killed by a light change; a normally-lit standing person never trips
        // brightnessSuppressed, so it never engages.
        //
        // Fix: keep anyMotion=true when an in-zone YOLO-SEEDED PERSON TRACKER
        // holds a lock, regardless of motion-block threat. This keeps the
        // sequence on the inline path where motionDuration = now − firstMotionTime
        // grows with wall-clock and crosses the trigger threshold. FP-safe: it
        // requires an actual texture-tracker lock on a YOLO-classified person
        // (classId==0) whose bbox bottom is inside the configured zone — a
        // tracker is only ever seeded from a real YOLO person detection, so
        // shadows / leaves / flags / headlight sweeps can never satisfy it. The
        // downstream trigger still applies the normal AI-confirmation gate, so
        // this only revives sequences a real, already-detected person produced.
        //
        // REVIVE-ONLY GUARD (firstMotionTime != 0): this branch may only KEEP AN
        // ALREADY-RUNNING sequence alive — it must NEVER start a fresh one from a
        // static track. Critical: native track teardown (NCC age-out + YOLO
        // heartbeat drop) runs only inside the `if (recording)` block, so after a
        // recording force-stops at the 3× hard ceiling the person's tracker stays
        // "active" indefinitely (a zombie). Without this guard, that immortal
        // in-zone person track would re-arm a brand-new sequence every frame; with
        // zero current motion YOLO never re-dispatches (getHighestThreatQuadrant
        // returns -1) so it can't be torn down, and ~5s later the AI-timeout
        // fallback fires a fresh recording — a self-perpetuating 30s-clip / 5s-gap
        // storm that leaks MediaCodec slots until SIGABRT (the exact failure mode
        // the AI-confirm + min-gap rate-limits exist to prevent). Requiring an
        // already-latched firstMotionTime means revival only happens mid-walk-up
        // (firstMotionTime set from real MEDIUM motion at ~:1671 before the person
        // stopped) — a zombie track post-stop, where firstMotionTime was reset to
        // 0, can never satisfy it. Defense-in-depth: stopRecording() and the hard
        // ceiling also drop all tracks so the zombie can't persist at all.
        //
        // YOLO-RECENCY GATE (now - lastPersonConfirmationTimeMs <= TRACK_ANCHOR_RECENCY_MS):
        // the revive-only guard alone is not enough for the PRE-recording case. If a
        // person stood in-zone long enough to start a sequence (firstMotionTime set)
        // then LEFT before the sequence triggered, the native track is NOT torn down
        // (teardown is recording-gated) and stays a frozen in-zone "zombie". With
        // firstMotionTime still set (no trigger, no reset yet), this branch would
        // keep reviving anyMotion off the zombie and the AI-timeout fallback could
        // fire a recording of an EMPTY scene. Mirror the recency gate the
        // track-anchored-confirmation sibling already uses: only a track backed by a
        // genuine YOLO hit within the last TRACK_ANCHOR_RECENCY_MS keeps immunity. A
        // truly-present standing person is continuously re-confirmed (heartbeat /
        // early-AI), refreshing lastAiConfirmationElapsedMs, so the legitimate fix
        // survives; a departed-person zombie's last confirmation goes stale and
        // immunity lapses, letting the sequence end normally.
        boolean recentYoloForImmunity = lastPersonConfirmationTimeMs > 0
                && (now - lastPersonConfirmationTimeMs) <= TRACK_ANCHOR_RECENCY_MS;
        // !isAiRunning: the immunity branch now WRITES the native tracker
        // (trackerUpdate below). The aiExecutor concurrently seeds/refreshes the
        // SAME unsynchronized global g_trackerState (trackerStartTrack/RefreshTemplate)
        // while isAiRunning is held. Gating on the established isAiRunning interlock
        // serializes all g_trackerState mutation to one thread at a time; when AI is
        // in flight the immunity update is skipped this frame and re-evaluated next
        // (self-healing — a present person is continuously re-confirmed).
        if (!anyMotion && firstMotionTime != 0 && recentYoloForImmunity && !isAiRunning.get()) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        // LIVENESS: drive the NCC tracker on this frame BEFORE
                        // trusting it. trackerUpdate normally runs only while
                        // recording, so pre-recording a person-track is frozen and
                        // stays "active" forever after the person leaves — a zombie
                        // that would keep immunity alive and fire an empty-scene
                        // recording at the ~2s trigger. Updating here makes the NCC
                        // score track reality: when the person is gone the match
                        // score falls and the track deactivates after
                        // TRACKER_LOST_FRAMES_MAX, so the lock below is only granted
                        // while the person is actually still there. A genuinely
                        // present standing person keeps a high NCC score and holds.
                        byte[] qc = (smallRgbFrame != null)
                                ? cropFromMosaic(smallRgbFrame, q, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2)
                                : null;
                        if (qc != null) {
                            NativeMotion.trackerUpdate(qc, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2, q, now);
                        }
                        if (!NativeMotion.trackerHasActiveTrack(q)) continue; // aged out → person gone
                        float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                        if (trackBox != null && (int) trackBox[5] == 0   // person only
                                && trackerInZone(q)) {
                            anyMotion = true;
                            if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                                maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                            }
                            // Re-dispatch YOLO on this quadrant (cooldown-gated) so
                            // lastPersonConfirmationTimeMs keeps refreshing while the
                            // NCC lock genuinely holds. Without this, a dead-still
                            // person gets NO re-confirmation pre-recording (early-AI
                            // is motion-gated and the NCC heartbeat is recording-
                            // gated), so the recency window goes stale at ~5s and a
                            // loiter bar configured >5s (with the approach fast-path
                            // off/>5s) would never trigger. A departed person's NCC
                            // track ages out above (the `continue`), so YOLO then
                            // finds nothing and immunity still lapses — preserving
                            // the empty-scene-storm defense.
                            if (useObjectDetection && !isAiRunning.get()
                                    && aiQuadrantQueueIsEmpty()
                                    && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                                aiQuadrantQueueAdd(q);
                                runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                            }
                            if (frameCount % 50 == 0) {
                                logger.info("Standing-person immunity: Q" + q +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] +
                                        "] motion decayed but tracker holds in-zone person lock");
                            }
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // STATIONARY-SUBJECT REVIVAL (persistent-foreground / MOG2 channel —
        // see the field-block doc). Third and last resort of the immunity
        // chain: brightness immunity needs a suppression event, standing-
        // person immunity needs a YOLO-seeded person track — this branch
        // covers the YOLO-blind stationary subject via the background model.
        // REVIVE-ONLY (firstMotionTime != 0): sustains a sequence that real
        // Stage-2 motion started; can never begin one. Quadrant-scoped to
        // the sequence's own last qualifying-motion quadrant, brightness-
        // discriminated on that quadrant (I3), fail-closed on any missing
        // evidence (I9), and budgeted per revival stretch (I10) on top of
        // the model's natural ~50s absorption of a truly static scene.
        // !recording (audit fix): revival exists to carry a PRE-trigger
        // sequence across stillness; once recording, the post-record window
        // + tracker/person-confirmation extension govern. Feeding raw MOG2
        // foreground into that branch would make unrelated persistent pixels
        // look like live recording activity until the absolute ceiling.
        //
        // Departure brake (audit fix): pre-existing static foreground (a
        // parked car, a persistent light patch) must not sustain a DEPARTED
        // subject to the AI-timeout fallback. The subject's own pixels are
        // part of the fraction, so a departure DROPS it below the baseline
        // captured while they were still moving; require the current
        // fraction to hold ≥80% of that baseline. Baseline < 0 (no fresh
        // sample at capture time) disarms revival — fail-closed (I9).
        // SUBJECT-REFERENCED brake terms (audit R3b Ext-9): subtract the
        // sequence-start ambient foreground from both the live fraction and
        // the baseline, so unrelated static foreground can neither sustain a
        // departed subject (S >= 4P) nor reject a present one (losing an
        // unrelated patch). Ambient invalid → 0 (legacy whole-quadrant
        // behavior). Correlated-lighting gate (R3 fresh-eyes #1 / Ext-10):
        // a lighting-created sequence must not revive off its own pool.
        // All MOG2 interval math is in the MONOTONIC domain (R3b Ext-13 —
        // a backward wall step made stale samples read as fresh and
        // stretched the budget by the step size).
        // Horizon expiry (audit R5 / R4-mog2 #1): past ~1 absorption horizon
        // the true residual ambient is ≈0 — keeping the frozen S₀ would
        // undercount a late-sequence stander (FN).
        final float reviveAmbient =
                (sequenceMog2AmbientValid && sequenceMotionQuadrant >= 0
                        && sequenceMog2AmbientAtMs > 0
                        && (nowElapsed - sequenceMog2AmbientAtMs) <= MOG2_AMBIENT_HORIZON_MS)
                        ? sequenceMog2AmbientQuad[sequenceMotionQuadrant] : 0f;
        if (!anyMotion && firstMotionTime != 0
                && !recording
                && mog2ChannelEnabled && mog2Available
                && mog2CallCount > MOG2_WARMUP_CALLS
                && sequenceMotionQuadrant >= 0
                && mog2FracAtMs > 0 && (nowElapsed - mog2FracAtMs) <= MOG2_FRESHNESS_MS
                && !brightnessEventInQuadrant(sequenceMotionQuadrant, results)
                && !lightingCorrelatedWithSequence()
                && (mog2QuadFrac[sequenceMotionQuadrant] - reviveAmbient)
                        >= MOG2_REVIVE_MIN_FRAC
                && sequenceMog2BaselineFrac >= 0f
                && (sequenceMog2BaselineFrac - reviveAmbient) > 0f
                && (mog2QuadFrac[sequenceMotionQuadrant] - reviveAmbient)
                        >= MOG2_NO_DROP_FRAC * (sequenceMog2BaselineFrac - reviveAmbient)) {
            if (mog2RevivalStartMs == 0) mog2RevivalStartMs = nowElapsed;
            if ((nowElapsed - mog2RevivalStartMs) <= MOG2_REVIVAL_MAX_MS) {
                anyMotion = true;
                if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                    maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                }
                if (frameCount % 50 == 0) {
                    logger.info(String.format(
                            "Stationary-subject revival: Q%d [%s] motion decayed but "
                            + "background model holds %.0f%% persistent foreground",
                            sequenceMotionQuadrant,
                            MotionPipelineV2.QUADRANT_NAMES[sequenceMotionQuadrant],
                            mog2QuadFrac[sequenceMotionQuadrant] * 100f));
                }
            }
            // Budget exhausted → no revival; the sequence ends normally via
            // the gap branch (lastMotionTime went stale the moment real
            // qualifying motion stopped, so the reset is immediate).
        } else if (anyMotion) {
            // Real signal resumed (motion / tracker immunity) → close the
            // revival stretch so the budget measures CONTINUOUS stillness.
            mog2RevivalStartMs = 0;
        }

        // Per-tick proximity state update — runs ONCE per quadrant per
        // processFrameV2 iteration, BEFORE any log site reads
        // proximityForQuadrant. Without this, multiple downstream log sites
        // in the same tick (per-quadrant summary, motion-start,
        // motion-building, recording-trigger) would each clobber each
        // other's prevLowestBlockY in the old unified proximityForQuadrant,
        // collapsing dt to 0 and silently breaking the trend signal
        // (audit H2). Runs unconditionally — quiet quadrants need their
        // stale prev state cleared too (audit M4), otherwise a 30-second-
        // old prevRow with a fresh nowMs produces a bogus APPROACHING the
        // next time blocks fire in that quadrant.
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            updateProximityState(q, results[q]);
        }

        // Parked-idle throttle keyframe pulse. At the low idle fps the encoder's
        // frame-count GOP (2s × KEY_FRAME_RATE) stretches past the pre-record
        // window in wall-clock, so the ring can hold zero keyframes and the
        // pre-roll flush is skipped. While idle (throttle on, armed, NOT recording,
        // no active motion) force an IDR every ~preRecordSeconds/2 so the ring
        // always contains a keyframe. Piggybacks on this motion tick (single
        // thread, ~5 Hz) — no separate timer, so no cancellation races. Only adds
        // IDRs (never removes), so pre-roll validity can only improve; at full fps
        // / throttle-off it never runs (guarded), leaving today's behaviour intact.
        if (com.overdrive.app.config.UnifiedConfigManager.isSurveillanceIdleThrottle()
                && active && !recording && !continuousMode && firstMotionTime == 0) {
            long pulsePeriodMs = Math.max(1000L, (preRecordMs / 2L));
            if (lastIdleKeyframePulseMs == 0 || (now - lastIdleKeyframePulseMs) >= pulsePeriodMs) {
                lastIdleKeyframePulseMs = now;
                try {
                    HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                    if (enc != null) enc.requestSyncFrame();
                } catch (Throwable t) {
                    logger.debug("Idle keyframe pulse failed: " + t.getMessage());
                }
            }
        } else {
            // Reset so the first idle tick after an event pulses immediately.
            lastIdleKeyframePulseMs = 0;
        }

        // --- Diagnostic: Log per-quadrant pipeline results every time motion is detected ---
        // This shows exactly what the pipeline saw and why it did/didn't trigger.
        if (anyMotion || filterDebugEnabled) {
            String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
            int bestQ = pipelineV2.getHighestThreatQuadrant();

            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                if (r.activeBlocks == 0 && !r.brightnessSuppressed) continue;

                String qName = MotionPipelineV2.QUADRANT_NAMES[q];

                // SOTA proximity (bbox-height when YOLO has fired, tier+trend
                // pre-YOLO). Replaces the previous centroid-Y geometric
                // distance which was producing 0.4–0.9 m noise for objects
                // actually 3–8 m away (wrong projection model + Y=horizon
                // assumption + foot-vs-torso confusion). Read-only here —
                // state was updated above for this tick.
                DistanceEstimator.ProximityEstimate proxQ = proximityForQuadrant(q, r);
                String proxStr = proxQ.describe();

                // Zone cutoff is row-based natively (maxDistanceRow), so the
                // metric expression here is purely cosmetic. Keep the legacy
                // geometric estimate for that single label so users have
                // something stable to read against — it's wrong but it's
                // what the existing UI configuration text refers to.
                int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                String zoneStr = config != null ? config.getDetectionZone() : "?";
                String zoneLimitStr = maxRow > 0
                        ? String.format("%s(<%s)", zoneStr,
                                maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended")
                        : zoneStr + "(no limit)";

                if (r.brightnessSuppressed) {
                    logger.debug(String.format(
                        "  [%s] BRIGHTNESS_SUPPRESSED luma=%.0f (light change detected)",
                        qName, r.meanLuma));
                } else if (r.salienceState == 1) {
                    // The native flash filter killed this quadrant AFTER probing it.
                    // Must be reported before the generic reason chain below: that
                    // chain would read confirmedBlocks/componentSize (both zeroed by
                    // the probe-fail restore) and blame "component too small", i.e.
                    // attribute the kill to Stage 4 instead of the flash filter. This
                    // is the sole Java reader of salienceState and is what makes the
                    // probe outcome observable per invariant I7.
                    logger.info(String.format(
                        "  [%s] FLASH_SUPPRESSED active=%d luma=%.0f (>25%% mass; salience "
                        + "probe ran and judged it diffuse/incoherent — a lighting event)",
                        qName, r.activeBlocks, r.meanLuma));
                } else if (r.shadowFiltered && !r.motionDetected) {
                    logger.debug(String.format(
                        "  [%s] SHADOW_FILTERED active=%d (shadow discrimination removed blocks)",
                        qName, r.activeBlocks));
                } else if (r.motionDetected) {
                    logger.info(String.format(
                        "  [%s] %s | prox=%s | blocks: active=%d confirmed=%d component=%d | zone=%s",
                        qName, threatNames[r.threatLevel], proxStr,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, zoneLimitStr));
                } else if (r.activeBlocks > 0) {
                    // Motion was detected at block level but rejected by later stages
                    String reason;
                    if (r.confirmedBlocks == 0) {
                        reason = "not yet confirmed (need more frames)";
                    } else if (r.componentSize < (pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1)) {
                        reason = String.format("component too small (%d blocks, need %d)", r.componentSize,
                                pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1);
                    } else if (maxRow > 0 && r.centroidY < maxRow) {
                        reason = String.format("outside zone (%s)", zoneLimitStr);
                    } else if (r.confirmedBlocks < (pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2)) {
                        reason = String.format("below alarm threshold (%d blocks, need %d)", r.confirmedBlocks,
                                pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2);
                    } else {
                        reason = "passing motion (" + threatNames[r.threatLevel] + ", ignored)";
                    }
                    logger.debug(String.format(
                        "  [%s] REJECTED: %s | prox=%s active=%d confirmed=%d",
                        qName, reason, proxStr, r.activeBlocks, r.confirmedBlocks));
                }
            }
        }
        
        // Update legacy tracking variables for compatibility
        if (anyMotion) {
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            if (bestQ >= 0) {
                lastActiveBlocksCount = results[bestQ].activeBlocks;
                lastTemporalBlocksCount = results[bestQ].confirmedBlocks;
            }
        }
        
        if (anyMotion) {
            // SEQUENCE-START clears — must precede the qualifying-motion
            // capture below (audit R2 #3): the firstMotionTime==0 block
            // further down runs AFTER the capture within this same tick, so
            // clearing these fields there wiped a first-tick capture and
            // disarmed revival for any sequence whose only qualifying tick
            // was its first. firstMotionTime is still 0 here iff THIS tick
            // starts the sequence (it is latched only in that block).
            if (firstMotionTime == 0) {
                sequenceMotionQuadrant = -1;
                mog2RevivalStartMs = 0;
                sequenceMog2BaselineFrac = -1f;
                // New sequence: clear the correlated-lighting latch (R3
                // fresh-eyes #2) — the guard re-evaluates for THIS sequence.
                sequenceLightingCorrelated = false;
                // AMBIENT-FOREGROUND snapshot (R3b Ext-9): capture the
                // pre-motion foreground so the departure brake can reference
                // the subject's own contribution. Taken from the ONE-SAMPLE-
                // DELAYED shadow, not the live array (audit R4-mog2 R#2 +
                // ExtB-6): the live sample can (a) be the exact sample the
                // first qualifying tick's baseline reads — identity made the
                // (baseline-ambient)>0 gate 0>0 fail, disarming revival for
                // single-tick sequences — and (b) already contain the
                // arriving subject's silhouette (MOG2 foreground is
                // instantaneous; block confirmation lags entry by 200-400ms+).
                // The shadow predates both. Requires a reasonably fresh
                // shadow; else no subtraction (legacy whole-quadrant math).
                sequenceMog2AmbientValid =
                        mog2CallCount > MOG2_WARMUP_CALLS
                        && mog2FracPrevAtMs > 0
                        && (nowElapsed - mog2FracPrevAtMs)
                                <= MOG2_FRESHNESS_MS + MOG2_SAMPLE_PERIOD_MS;
                if (sequenceMog2AmbientValid) {
                    System.arraycopy(mog2QuadFracPrev, 0, sequenceMog2AmbientQuad, 0,
                            MotionPipelineV2.NUM_QUADRANTS);
                    sequenceMog2AmbientAtMs = nowElapsed;
                }
            }
            // GATING: only "qualifying" motion bumps lastMotionTime. The
            // post-record stop check uses (now - lastMotionTime) ≥ postRecordMs
            // as the gate that lets recording finally end. Bumping
            // lastMotionTime on EVERY anyMotion frame meant residual
            // shadow flickers, brightness blips, and out-of-zone motion
            // (which DON'T qualify as recording-extension events under
            // the recordingStopTime path) silently kept the post-record
            // clock alive — turning a brief 3-second approach into a
            // 50-second clip when shadows kept tickling the detector.
            //
            // Qualifying = at least one quadrant has MEDIUM+ threat AND
            // (an in-zone motion result OR an in-zone tracker lock). This
            // matches the same conditions that gate the recordingStopTime
            // extension at line ~1842.
            boolean qualifyingMotion = false;
            // The quadrant that PRODUCED the qualifying signal (audit fix:
            // getHighestThreatQuadrant() returns any threat > NONE, so a
            // LOW(pass) quadrant elsewhere could poison the MOG2 revival
            // scope away from the actual subject — capture the qualifying
            // quadrant itself instead).
            int qualifyingQ = -1;
            int qualifyingBestLvl = 0;  // trust-demoted level of qualifyingQ (R3 R1-1)
            if (maxThreat >= MotionPipelineV2.THREAT_MEDIUM) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM
                            && (results[q].activeBlocks > 0 || results[q].confirmedBlocks > 0)) {
                        // Use trackerInZone as the cheap zone proxy when a
                        // tracker is present; otherwise fall back to the
                        // pipeline's own zone gating (already applied via
                        // applyQuadrantOverrides above — if threat survives
                        // post-override, it's in-zone).
                        qualifyingMotion = true;
                        // Highest-threat selection, not break-on-first (audit
                        // R2 #2): a lower-indexed MEDIUM interferer (light
                        // pool, swaying branch) must not claim the revival
                        // scope over a HIGH loiterer in a higher-indexed
                        // quadrant on the sequence's last qualifying tick.
                        // TRUST-AWARE (audit R3 engine R1-1): an UNTRUSTED
                        // HIGH (waving flag / sweeping shadow reaches
                        // THREAT_HIGH) is demoted to MEDIUM for this
                        // comparison, so it can't deterministically outrank
                        // a real MEDIUM subject for the revival scope.
                        // highThreatIsTrusted is only probed for HIGH
                        // qualifying quadrants (rare — requires loiter).
                        int effLvl = results[q].threatLevel;
                        if (effLvl >= MotionPipelineV2.THREAT_HIGH
                                && !highThreatIsTrusted(q, results[q])) {
                            effLvl = MotionPipelineV2.THREAT_MEDIUM;
                        }
                        if (qualifyingQ < 0 || effLvl > qualifyingBestLvl) {
                            qualifyingQ = q;
                            qualifyingBestLvl = effLvl;
                        }
                    }
                }
            }
            // Tracker-immunity branch: the headlight-sweep fix at line ~1240
            // bumps `maxThreat` to MEDIUM but does NOT touch
            // `results[q].threatLevel`, so the loop above misses cases where
            // the only motion signal is an in-zone person tracker holding
            // through a brightness sweep. Those frames are exactly the
            // "real intrusion under flash" scenario the immunity branch
            // exists for — they MUST keep lastMotionTime alive, otherwise
            // the recording falls back to the hard ceiling instead of
            // stopping cleanly when the person actually leaves. Mirror the
            // same in-zone person-tracker probe used by trackerHolding
            // below (line ~1899).
            if (!qualifyingMotion && maxThreat >= MotionPipelineV2.THREAT_MEDIUM) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0
                                    && trackerInZone(q)) {
                                qualifyingMotion = true;
                                // The tracked person's own quadrant IS the
                                // subject's quadrant (audit fix — see
                                // qualifyingQ above).
                                qualifyingQ = q;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (qualifyingMotion) {
                lastMotionTime = now;
                // Track the sequence's motion quadrant for the MOG2 revival
                // scope (I11 — revival evidence must describe THIS subject's
                // quadrant, never another camera's). qualifyingQ is the exact
                // quadrant that produced the qualifying signal (results-based
                // MEDIUM+ or the tracked person's quadrant) — deliberately
                // NOT getHighestThreatQuadrant(), which returns any threat
                // > NONE and could be poisoned by a LOW(pass) elsewhere.
                if (qualifyingQ >= 0) {
                    int prevScopeQ = sequenceMotionQuadrant;
                    sequenceMotionQuadrant = qualifyingQ;
                    // DEPARTURE-BRAKE baseline: the MOG2 foreground fraction
                    // in the subject's quadrant while the subject was still
                    // producing real motion. A fresh (≤ freshness window)
                    // sample is required; otherwise the baseline stays -1 and
                    // revival is disarmed for this sequence (fail-closed, I9).
                    //
                    // TRUSTWORTHINESS gates (audit R2 #1): the revival
                    // consumer only accepts fractions past warmup and outside
                    // a lighting event in the scope quadrant — a baseline
                    // captured without the SAME gates can be inflated (a
                    // headlight pool ≈40% vs a person's ≈8%, or a still-
                    // learning model's sample), making the ≥80%-of-baseline
                    // brake unreachable and permanently disarming revival for
                    // a real stander (FN, the unsafe direction). On a
                    // disqualified tick: RETAIN the previous baseline if the
                    // scope quadrant is unchanged; fail closed (-1) if the
                    // scope moved (a baseline captured for another quadrant
                    // can't vouch here).
                    // + correlated-lighting gate (R3b Ext-10): a correlated
                    // cross-camera pool inflates THIS quadrant's foreground
                    // without tripping its local suppression flag — same
                    // inflated-baseline threat, same shared predicate as the
                    // revival branch.
                    boolean baselineTickTrustworthy =
                            mog2CallCount > MOG2_WARMUP_CALLS
                            && !brightnessEventInQuadrant(qualifyingQ, results)
                            && !lightingCorrelatedWithSequence();
                    if (baselineTickTrustworthy) {
                        boolean sampleFresh = mog2FracAtMs > 0
                                && (nowElapsed - mog2FracAtMs) <= MOG2_FRESHNESS_MS;
                        if (sampleFresh) {
                            sequenceMog2BaselineFrac = mog2QuadFrac[qualifyingQ];
                        } else if (qualifyingQ != prevScopeQ) {
                            // Stale AND the scope moved → fail closed.
                            sequenceMog2BaselineFrac = -1f;
                        }
                        // Trustworthy + stale + SAME quadrant → RETAIN the
                        // previous baseline (audit R3 fresh-eyes #5): wiping
                        // it on a transient sampler outage disarmed revival
                        // for the whole sequence — the FN direction.
                    } else if (qualifyingQ != prevScopeQ) {
                        sequenceMog2BaselineFrac = -1f;
                    }
                }
            }

            // Track peak threat across the entire motion sequence
            if (maxThreat > peakThreatDuringSequence) {
                peakThreatDuringSequence = maxThreat;
            }

            // Latch NEAR proximity across the sequence (gates the close-range
            // confirmed fast-path). Read the best-threat quadrant's proximity;
            // once NEAR is seen it stays latched until the sequence resets.
            // Cheap: proximityForQuadrant is the same read already done for the
            // per-frame diag logs. Only bother while not yet latched.
            if (!peakNearDuringSequence || !peakCloseZoneDuringSequence) {
                int nearQ = pipelineV2.getHighestThreatQuadrant();
                if (nearQ >= 0) {
                    DistanceEstimator.ProximityEstimate proxNear =
                            proximityForQuadrant(nearQ, results[nearQ]);
                    if (proxNear != null) {
                        // NEAR-only latch — feeds the confirmed close-range
                        // walk-past fast-path (unchanged, deliberately strict).
                        if (proxNear.tier == DistanceEstimator.Tier.NEAR) {
                            peakNearDuringSequence = true;
                        }
                        // Close-zone latch (NEAR *or* MID, i.e. not FAR) — feeds
                        // the UNCONFIRMED close-zone fast-path + AI-gate override.
                        // A real walk-up sits at MID (~2-6m: tierFromMeters), so a
                        // NEAR-only gate misses it (the on-car 2.5m FN); FAR is
                        // excluded so a distant passer-by never qualifies. Being
                        // in the user's configured zone is already implied by
                        // reaching a MEDIUM+ threat (native maxDistanceRow gate),
                        // so this proximity tier is the additional "genuinely
                        // close, not far" evidence.
                        if (proxNear.tier == DistanceEstimator.Tier.NEAR
                                || proxNear.tier == DistanceEstimator.Tier.MID) {
                            peakCloseZoneDuringSequence = true;
                            peakCloseZoneQuadrant = nearQ;
                        }
                    }
                }
            }

            // POST-PARK VIGILANCE per-tick evaluator: stamp the latch when the
            // best-threat quadrant's motion centroid sits next to a fresh-parker
            // anchor (a confirmed vehicle baseline entry WATCHED arriving within
            // the last VIGILANCE_ANCHOR_MAX_AGE_MS). Re-stamped every qualifying
            // tick so the TTL measures from the LAST adjacency evidence; a
            // non-qualifying tick simply lets it age out (I2: per-tick, no
            // frameCount%N nesting; I11: TTL'd, quadrant-scoped). Centroid is in
            // block units (10×7 grid of 32px blocks) → normalize against the
            // 320×240 quadrant the baseline foot-points are normalized to.
            // Cheap: one O(entries) synchronized scan at ~8.7Hz, only while
            // MEDIUM+ motion is active.
            if (postParkVigilanceEnabled) {
                int vq = pipelineV2.getHighestThreatQuadrant();
                if (vq >= 0 && results[vq] != null && results[vq].componentSize > 0) {
                    // Block-centre convention (+0.5) matches estimateDistanceFromCentroid:
                    // pixel = blockCoord * 32 + 16.
                    float vnx = ((results[vq].centroidX + 0.5f) * MotionPipelineV2.BLOCK_SIZE)
                            / (THUMBNAIL_WIDTH / 2f);
                    float vny = ((results[vq].centroidY + 0.5f) * MotionPipelineV2.BLOCK_SIZE)
                            / (THUMBNAIL_HEIGHT / 2f);
                    try {
                        if (detectionBaseline.hasFreshParkedVehicleNear(
                                vq, vnx, vny, VIGILANCE_ADJACENCY_NORM,
                                VIGILANCE_ANCHOR_MAX_AGE_MS)) {
                            boolean logFirst = !vigilanceActive() || vigilanceQuadrant != vq;
                            vigilanceQuadrant = vq;
                            vigilanceSeenAtMs = android.os.SystemClock.elapsedRealtime();
                            if (logFirst) {
                                logger.info(String.format(
                                    "Post-park vigilance armed: Q%d [%s] motion adjacent to "
                                    + "fresh-parker anchor (centroid %.2f,%.2f norm)",
                                    vq, MotionPipelineV2.QUADRANT_NAMES[vq], vnx, vny));
                            }
                        }
                    } catch (Throwable ignored) {
                        // I9: an adding-only channel fails CLOSED — no anchor.
                    }
                }
            }

            // Log motion to timeline — ALWAYS, even before recording starts.
            // The timeline collector's pre-trigger ring buffer captures events during
            // the approach phase. When recording triggers, these are flushed into the
            // active span array with timestamps aligned to the video's pre-record window.
            timelineCollector.onMotionDetected(lastActiveBlocksCount, pipelineV2.getActiveQuadrantMask());
            
            if (firstMotionTime == 0) {
                firstMotionTime = now;
                firstMotionElapsedMs = nowElapsed;  // monotonic twin (audit R2 #4)
                // (Parked-idle throttle ramp is driven by a hasActiveMotion() edge
                // detector at the end of processFrameV2 — see maybeReconcileOnActivityEdge.
                // Latching firstMotionTime here is the rising edge it observes.)
                peakThreatDuringSequence = maxThreat;
                // New sequence: clear the latched HIGH-trust flag. It is
                // re-evaluated and re-latched below (and each subsequent frame)
                // from the live coherence/tracker signal for THIS sequence, so a
                // flag's untrusted HIGH can't inherit trust from a prior real
                // loiterer. Reset here (the single sequence-start point) rather
                // than at the 5 scattered sequence-end sites.
                cachedHighIsTrusted = false;
                cachedIncoherentLoiter = false;
                // New sequence: clear the close-range latches (re-set below each
                // frame from the best quadrant's proximity tier). Same single-point
                // reset discipline as cachedHighIsTrusted above.
                peakNearDuringSequence = false;
                peakCloseZoneDuringSequence = false;
                peakCloseZoneQuadrant = -1;
                // (MOG2 revival scope + budget + departure-brake baseline are
                // cleared at the TOP of this anyMotion branch, BEFORE the
                // qualifying-motion capture — clearing them here executed
                // after a same-tick capture and wiped it, disarming revival
                // for sequences whose only qualifying tick was their first.
                // Audit R2 #3. The background model itself is NOT reset at
                // sequence start — it describes the scene, not the sequence,
                // same rationale as the salience run counters below.)
                // New sequence: clear the salience latch. The per-quadrant run
                // counters are NOT cleared here — they are physical evidence about
                // the scene, maintained per tick above, and a sequence boundary
                // (a >2s motion gap) already breaks any run via its own
                // non-qualifying ticks.
                salienceConfirmedDuringSequence = false;
                salienceQuadrant = -1;
                salienceConfirmedAtMs = 0;
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                // SOTA proximity: bbox-height (post-YOLO) or tier+trend (pre-YOLO).
                // At motion-start YOLO has almost certainly not fired yet, so this
                // log line will read e.g. "near approaching" rather than fabricate
                // a metric distance from the motion-block centroid.
                DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                        ? proximityForQuadrant(bestQ, bestR)
                        : DistanceEstimator.ProximityEstimate.tierOnly(
                                DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                String threatStr = maxThreat >= MotionPipelineV2.THREAT_HIGH ? "HIGH(loiter)" : "MEDIUM(approach)";
                // Trust probe at motion-start (re-latched each frame below). Tells
                // us up-front whether a HIGH is a real loiterer or a flag/shadow.
                boolean startTrusted = (maxThreat >= MotionPipelineV2.THREAT_HIGH)
                        && highThreatIsTrusted(bestQ, bestR);
                long needed = (maxThreat >= MotionPipelineV2.THREAT_HIGH && startTrusted)
                        ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                logger.info(String.format("Motion started: %s camera, threat=%s, prox=%s, %s, need %.1fs sustained...",
                        bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?",
                        threatStr, prox.describe(), describeHighTrust(maxThreat, startTrusted, bestR),
                        needed / 1000.0));
            }
            
            long motionDuration = now - firstMotionTime;

            // Use peak threat for duration requirement (not just current frame).
            // This prevents a brief MEDIUM→NONE→MEDIUM flicker from resetting the clock.
            int effectiveThreat = peakThreatDuringSequence;

            // FLAG/SHADOW GUARD: is this THREAT_HIGH a real loiterer (coherent
            // translation or an in-zone person tracker) or an in-place oscillator
            // (waving flag / sweeping shadow)? Evaluated against the current
            // best-threat quadrant. Latched into cachedHighIsTrusted for the
            // async YOLO matrix. Only meaningful at HIGH; harmless at MEDIUM.
            int trustQ = pipelineV2.getHighestThreatQuadrant();
            boolean highIsTrusted = (effectiveThreat >= MotionPipelineV2.THREAT_HIGH)
                    && highThreatIsTrusted(trustQ, trustQ >= 0 ? results[trustQ] : null);
            // Once trusted in a sequence, stay trusted (a real loiterer who goes
            // briefly still mid-sequence shouldn't be downgraded to a flag).
            if (highIsTrusted) cachedHighIsTrusted = true;

            // POSITIVE incoherence evidence for the timeout extension below.
            // Distinct from "untrusted": untrusted includes the fail-open
            // (coherence unavailable) case, whereas this requires the native
            // signal to have actually FIRED and reported incoherent flow — i.e.
            // a confirmed flag/foliage/shadow oscillation, not just "no signal".
            // Latched for the whole sequence and never set when a coherent or
            // tracked frame appeared (cachedHighIsTrusted), so a real approach
            // that briefly stalls can't arm the flag-extension.
            if (trustQ >= 0 && !cachedHighIsTrusted) {
                MotionPipelineV2.QuadrantResult tr = results[trustQ];
                if (tr != null && tr.flowCoherence >= 0f
                        && tr.flowCoherence < COHERENCE_RATIO_MIN
                        && tr.netDriftBlocks < COHERENCE_NET_MIN) {
                    cachedIncoherentLoiter = true;
                }
            }

            // TRACK-ANCHORED CONFIRMATION. A live in-zone person tracker is, by
            // construction, the product of a PRIOR real YOLO person detection
            // (the only tracker seed is trackerStartTrack from a YOLO 'best' —
            // see ~:3150). So an in-zone person track held right now is itself
            // standing confirmation that a real person is at the car, even if
            // the last AI confirmation predates this sequence's firstMotionElapsedMs.
            // This matters across a ZONE-BOUNDARY JITTER: a person pacing in and
            // out of the configured zone trips the gap-branch firstMotionTime
            // reset, so on re-entry the YOLO timestamp looks "stale" and the
            // approach fast-path / AI gate would otherwise demote to the full
            // loiter bar — delaying a re-entering, already-identified person.
            // Anchoring on the live track keeps the fast path. FP-safe: requires
            // a YOLO-seeded person track (classId==0) in-zone, which shadows /
            // leaves / flags can never produce. Storm-safe: only consulted on the
            // inline path, which the revive-only guard (~:1503) keeps tied to a
            // live sequence, and tracks are dropped on every stop (~:5603).
            // RECENCY GATE: only trust a held track as confirmation if a real
            // YOLO confirmation landed within TRACK_ANCHOR_RECENCY_MS. The track
            // "active" flag alone has no pre-recording liveness (trackerUpdate is
            // recording-gated), so a track seeded for a person who LEFT stays
            // frozen-active in-zone forever; reading it bare would certify a later
            // unrelated shadow burst as confirmed. Tying it to a recent YOLO hit
            // means a genuine zone-jitter re-entry (person confirmed seconds ago)
            // keeps the fast path, while a stale zombie (last confirmation long
            // past) is rejected and the normal AI-suppression gate applies.
            // PERSON-specific: the held track is a person (classId==0 check
            // below), so only a recent PERSON YOLO hit may certify it fresh — a
            // passing car/bike must not keep a stale zombie person track alive.
            boolean recentYoloHit = lastPersonConfirmationTimeMs > 0
                    && (now - lastPersonConfirmationTimeMs) <= TRACK_ANCHOR_RECENCY_MS;
            boolean inZonePersonTrackerHeld = false;
            if (recentYoloHit) {
                for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(tq)) {
                            float[] tb = NativeMotion.trackerGetTrackBox(tq);
                            if (tb != null && tb.length >= 7 && (int) tb[5] == 0
                                    && trackerInZone(tq)) {
                                inZonePersonTrackerHeld = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // A live, recently-YOLO-backed in-zone person track counts as
            // confirmation for THIS sequence's gating decisions (fast-path bar +
            // AI-confirm gate). The first disjunct is the normal within-sequence
            // confirmation; the second bridges a zone-boundary firstMotionTime
            // reset for an already-identified, still-tracked person.
            boolean sequenceConfirmed =
                    (lastAiConfirmationElapsedMs >= firstMotionElapsedMs)
                    || inZonePersonTrackerHeld;

            // Brightness-event flags (hoisted): the lighting-artifact signature
            // that closes the close-zone / salience / vigilance paths and
            // stretches the AI-confirm timeout. Formerly ONE scene-wide
            // any-quadrant OR — which violated invariant I3 (suppression is
            // evidence-scoped): a headlight sweep on the LEFT camera closed the
            // close-zone fast paths and stretched the AI timeout to 20s for a
            // concurrent real walk-up on the RIGHT camera. Same scene-wide-OR
            // false negative this file already fixed once for the night-discard
            // latches, and the same reason the salience override deliberately
            // refuses cachedIncoherentLoiter from another quadrant.
            //
            // Now each consumer group tests only the quadrants that can describe
            // ITS subject: the live best-threat quadrant plus its own evidence
            // latch's quadrant (see brightnessEventInQuadrant). This is strictly
            // NARROWER than the old OR only in the cross-quadrant case; a sweep
            // in the subject's own quadrant still tests true — including while
            // best-threat momentarily points elsewhere mid-suppression, because
            // the latch quadrant keeps testing true via suppressionWasActive[q].
            //
            // brightnessEventAnyQuadrant is retained ONLY for the diagnostic
            // "AI gate holding" log line (attribution, not gating).
            boolean brightnessEventAnyQuadrant = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (suppressionWasActive[q] || results[q].brightnessSuppressed) {
                    brightnessEventAnyQuadrant = true;
                    break;
                }
            }
            final int brightnessScopeBestQ = pipelineV2.getHighestThreatQuadrant();
            // CORRELATED-LIGHTING GUARD (see field doc at LIGHTING_CORRELATION_MS):
            // if any quadrant's suppression ONSET falls within ±1s of THIS
            // sequence's start, the sequence itself was plausibly created by
            // that lighting event (one car's beam suppresses camera A while
            // its light pool reads as coherent motion in camera B). Such a
            // sequence is treated as lighting-correlated by ALL three scopes —
            // restoring the old scene-wide OR's protection exactly for the
            // correlated case, while an UNCORRELATED sweep (starting well
            // after a real subject's sequence began) stays scoped to its own
            // quadrant, preserving the cross-camera FN fix.
            // LATCHED per-sequence verdict via the shared helper (audit R3
            // fresh-eyes #2): the raw ±1s test (monotonic domain, R2 #4)
            // reads overwritable onset stamps, so a SECOND suppression edge
            // >1s into a live sequence used to erase the correlated status
            // while the first event's light pool kept the sequence alive —
            // reopening exactly the fast paths this guard closes. The latch
            // holds for the sequence's lifetime; sequence start resets it.
            final boolean lightingCorrelatedWithSequence = lightingCorrelatedWithSequence();
            // Close-zone consumers (close-zone fast-path, close-zone override,
            // AI-confirm timeout): the close-zone latch quadrant + best-threat.
            boolean brightnessEventCloseZoneScope =
                    lightingCorrelatedWithSequence
                    || brightnessEventInQuadrant(brightnessScopeBestQ, results)
                    || brightnessEventInQuadrant(peakCloseZoneQuadrant, results);
            // Salience consumers (fast-path, far-gate exemption, AI-gate
            // override): the salient quadrant + best-threat. The pre-existing
            // comment on the fast-path ("re-checked here because it is
            // scene-wide and can have fired on a DIFFERENT quadrant") is
            // preserved in spirit: a sweep on the salient quadrant itself, or on
            // the quadrant driving the trigger, still closes the path.
            boolean brightnessEventSalienceScope =
                    lightingCorrelatedWithSequence
                    || brightnessEventInQuadrant(brightnessScopeBestQ, results)
                    || brightnessEventInQuadrant(salienceQuadrant, results);
            // Vigilance consumers (fast-path, far-gate exemption): the vigilance
            // quadrant + best-threat. Every vigilance consumer additionally
            // requires bestQ == vigilanceQuadrant, so in practice this is the
            // vigilance quadrant's own flag.
            boolean brightnessEventVigilanceScope =
                    lightingCorrelatedWithSequence
                    || brightnessEventInQuadrant(brightnessScopeBestQ, results)
                    || brightnessEventInQuadrant(vigilanceQuadrant, results);

            // Determine required sustained motion based on threat level:
            // - THREAT_HIGH, TRUSTED (coherent/tracked loiter): base delay 500ms.
            // - THREAT_HIGH, UNTRUSTED (flag/shadow): treated like MEDIUM —
            //   require the full loitering time so it must clear the YOLO gate.
            // - THREAT_MEDIUM (approaching) + YOLO-confirmed in-zone object:
            //   the short approachTriggerMs fast-path (records a walk-up/walk-past
            //   without waiting out the full loiter dwell).
            // - THREAT_MEDIUM, motion-only (no AI confirmation yet): the full
            //   loitering time, so lighting artifacts / flags / shadows (which
            //   never yield a YOLO object) can't take the fast path.
            long requiredDuration;
            if (effectiveThreat >= MotionPipelineV2.THREAT_HIGH && cachedHighIsTrusted) {
                requiredDuration = SUSTAINED_MOTION_BASE_MS;
            } else {
                requiredDuration = loiteringTimeMs;
                // Approach fast-path: enabled (approachTriggerMs>0), AI confirmed a
                // real object during THIS sequence, and the fast bar is shorter than
                // the loiter bar. firstMotionTime>0 guards against a stale prior-
                // sequence confirmation leaking in (lastAiConfirmationElapsedMs is reset
                // on enable()/sequence handling).
                if (approachTriggerMs > 0
                        && firstMotionTime > 0
                        && sequenceConfirmed
                        && approachTriggerMs < requiredDuration) {
                    requiredDuration = approachTriggerMs;
                }
                // CLOSE-RANGE fast-path: a confirmed object that reached NEAR
                // during the sequence records after just CLOSE_CONFIRMED_TRIGGER_MS
                // — catches a brisk close walk-past that clears neither the loiter
                // nor the 2s approach bar. Same sequenceConfirmed gate (never a
                // flag/shadow) plus the NEAR latch (never a distant passer-by).
                // Takes the min so it never RAISES an already-shorter bar.
                if (CLOSE_CONFIRMED_TRIGGER_MS > 0
                        && firstMotionTime > 0
                        && sequenceConfirmed
                        && peakNearDuringSequence
                        && CLOSE_CONFIRMED_TRIGGER_MS < requiredDuration) {
                    requiredDuration = CLOSE_CONFIRMED_TRIGGER_MS;
                }
                // CLOSE-ZONE NEAR fast-path — the near-sibling of the confirmed
                // bar above, for the safety-critical FN where YOLO never returns
                // a *person* class in its short window even though a real subject
                // reached the close zone (on-car: person parked a bike + walked up
                // to 2.5m; YOLO caught only the bike, event ended at 1.1s,
                // requiredDuration stayed at the 3s loiter bar → the whole trigger
                // evaluation was never even reached). This FN is DEFINED by the
                // absence of any confirmation (no YOLO person, no coherent-drift
                // trust yet), so gating on (cachedHighIsTrusted||sequenceConfirmed)
                // would make the fast-path inert for its own scenario. Instead lower
                // the bar to CLOSE_CONFIRMED_TRIGGER_MS on close-zone motion evidence
                // ALONE, discriminated from a flag/shadow by POSITIVE incoherence:
                //   - peakCloseZoneDuringSequence (NEAR|MID, never FAR/UNKNOWN — the
                //     tier is reachable motion-only via tierFromMotion, no YOLO),
                //   - !brightnessEventCloseZoneScope (lighting-artifact signature,
                //     evidence-scoped per I3 to the close-zone/best-threat quadrants),
                //   - !cachedIncoherentLoiter — the fail-CLOSED discriminator. It
                //     latches ONLY once the native coherence signal fires AND reports
                //     incoherent flow (flowCoherence>=0 && <ratioMin && netDrift<netMin;
                //     see ~:2017). It is deliberately INERT for the first ~0.8s
                //     (netRingCount<coherenceMinFrames → flowCoherence pinned <0), so
                //     the window opens on motion alone up front, then a confirmed
                //     flag/shadow closes it once coherence publishes incoherent, while
                //     a coherent close approach (never arms cachedIncoherentLoiter,
                //     guarded by !cachedHighIsTrusted at :2017) keeps the lowered bar.
                // This only makes the trigger *evaluation* reachable; the AI-confirm
                // gate (shouldSuppress) still runs and its own close-zone override
                // decides the final fire.
                if (CLOSE_CONFIRMED_TRIGGER_MS > 0
                        && firstMotionTime > 0
                        && peakCloseZoneDuringSequence
                        && !brightnessEventCloseZoneScope
                        && !cachedIncoherentLoiter
                        && CLOSE_CONFIRMED_TRIGGER_MS < requiredDuration) {
                    requiredDuration = CLOSE_CONFIRMED_TRIGGER_MS;
                }
                // MOTION-SALIENCE fast-path. The third evidence channel: a large,
                // compact, sustained, photometrically-stable, rigidly-translating
                // motion mass is object-grade evidence on its own, so it earns the
                // same short bar as a YOLO-confirmed close object. Deliberately
                // does NOT require sequenceConfirmed or cachedHighIsTrusted — this
                // channel exists precisely for the case where both are absent
                // because YOLO returned nothing and the subject never stood still.
                // Every FP discriminator was applied per-tick when the latch was
                // set (mass/compactness/coherence/luma-stability/in-zone × 6
                // consecutive ticks); the brightness event is re-checked here
                // because it can have fired AFTER the run completed — scoped
                // (I3) to the salient quadrant + the best-threat quadrant, so a
                // sweep on an unrelated camera no longer closes this channel.
                if (salienceMayTrigger()
                        && firstMotionTime > 0
                        && !brightnessEventSalienceScope
                        && SALIENCE_TRIGGER_MS < requiredDuration) {
                    requiredDuration = SALIENCE_TRIGGER_MS;
                }
                // POST-PARK VIGILANCE fast-path. A person exiting a just-parked
                // car produces short, fragmented motion (door swing, pause,
                // walk-away) that rarely survives the full loiter bar — each
                // >2s pause resets firstMotionTime. Motion adjacent to a
                // fresh-parker anchor is high-prior, so it earns the same 1s
                // bar as the other strong-evidence channels. This only makes
                // the trigger EVALUATION reachable: the AI-confirm gate still
                // runs (YOLO gets its full 2s timeout window of chances), and
                // the far-gate exemption below carries its own discriminators.
                // Quadrant-scoped to the latch's own quadrant (I11 — the
                // best-threat quadrant and the vigilance quadrant can describe
                // different subjects on a busy scene). FP discriminators:
                // brightness event (headlight sweep over the parked car) and
                // POSITIVE incoherent-loiter evidence (foliage shadow at the
                // same spot) both close the path; both are the same fail-closed
                // guards the close-zone fast-path uses.
                if (vigilanceMayTrigger()
                        && firstMotionTime > 0
                        && pipelineV2.getHighestThreatQuadrant() == vigilanceQuadrant
                        && !brightnessEventVigilanceScope
                        && !cachedIncoherentLoiter
                        && VIGILANCE_TRIGGER_MS < requiredDuration) {
                    requiredDuration = VIGILANCE_TRIGGER_MS;
                }
            }

            // --- Diagnostic: Log sustained motion progress ---
            if (motionDuration > 0 && motionDuration < requiredDuration) {
                // Log every second while waiting
                if (motionDuration % 1000 < MOTION_PROCESS_INTERVAL_MS) {
                    String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                    DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                            ? proximityForQuadrant(bestQ, bestR)
                            : DistanceEstimator.ProximityEstimate.tierOnly(
                                    DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                    logger.info(String.format("Motion building: %.1fs / %.1fs | threat=%s | prox=%s | loiterSetting=%ds",
                            motionDuration / 1000.0, requiredDuration / 1000.0,
                            threatNames[maxThreat], prox.describe(), (int)(loiteringTimeMs / 1000)));
                }
            }
            
            // FIX: Early AI initialization — queue YOLO on active quadrants as soon as
            // motion is detected, not after the loitering timer expires. This lets the
            // EventTimelineCollector build contextual history (person vs car vs bike)
            // BEFORE the MP4 write is triggered. Without this, if someone leaves the
            // frame right at the trigger threshold, YOLO runs on an empty frame and the
            // JSON sidecar records a generic "motion" event instead of classifying it.
            if (useObjectDetection && !isAiRunning.get() && aiQuadrantQueueIsEmpty()) {
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (q != bestQ && results[q].motionDetected) {
                        aiQuadrantQueueAdd(q);
                    }
                }
                // Pick the cooldown by proximity: a NEAR (close-zone) subject
                // gets the fast 250ms cadence so a short walk-up window yields
                // more classification attempts; everything else keeps the 500ms
                // base to preserve steady-state CPU. Read the best quadrant's
                // proximity tier (the same estimate the diag logs use).
                long cooldown = AI_COOLDOWN_MS;
                if (bestQ >= 0) {
                    DistanceEstimator.ProximityEstimate bestProx =
                            proximityForQuadrant(bestQ, results[bestQ]);
                    if (bestProx != null && bestProx.tier == DistanceEstimator.Tier.NEAR) {
                        cooldown = AI_COOLDOWN_CLOSE_MS;
                    }
                    // POST-PARK VIGILANCE: an occupant exit next to a fresh
                    // parker is a short window in which a YOLO *person* hit is
                    // the difference between the confirmed fast-path and the
                    // heavily-gated motion-only path — same rationale as the
                    // NEAR cadence boost (double the classification chances
                    // while the subject is present). Scoped to the vigilance
                    // quadrant; steady-state CPU unchanged (latch TTL is 10s
                    // past the last adjacency evidence).
                    if (vigilanceActive() && bestQ == vigilanceQuadrant) {
                        cooldown = AI_COOLDOWN_CLOSE_MS;
                    }
                }
                // Kick off AI immediately if cooldown allows
                if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= cooldown) {
                    runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                }
            }
            
            if (motionDuration >= requiredDuration) {
                inActiveMode = true;
                
                // Filter debug log
                if (filterDebugEnabled) {
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                    String[] threatNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                    MotionPipelineV2.QuadrantResult r = bestQ >= 0 ? results[bestQ] : null;
                    DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                            ? proximityForQuadrant(bestQ, r)
                            : DistanceEstimator.ProximityEstimate.tierOnly(
                                    DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                    addFilterLogEntry(String.format("[%s] TRIGGER: %s threat=%s prox=%s active=%d confirmed=%d component=%d sustained=%.1fs",
                            new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                            qName, threatNames[maxThreat], prox.describe(),
                            r != null ? r.activeBlocks : 0, r != null ? r.confirmedBlocks : 0,
                            r != null ? r.componentSize : 0, motionDuration / 1000.0));
                }
                
                if (!recording) {
                    // AI CONFIRMATION GATE: For THREAT_MEDIUM, require YOLO to have confirmed
                    // a real object during this motion sequence before committing a recording.
                    // This prevents lighting artifacts (streetlights, porch lights, deterrent
                    // flashes, slow headlight sweeps) from triggering false recordings.
                    //
                    // For THREAT_HIGH (loitering), only gate during the deterrent window —
                    // loitering is confirmed by 10+ seconds of centroid analysis, which is
                    // strong enough evidence on its own. But the deterrent's own light creates
                    // a static centroid that mimics loitering, so we gate it there.
                    //
                    // TIMEOUT FALLBACK: If YOLO hasn't confirmed within 2 seconds past the
                    // required sustained duration, let it through anyway. This handles:
                    // - YOLO model not loaded (useObjectDetection=false)
                    // - YOLO busy on another quadrant (AI cooldown)
                    // - Object too small/dark for YOLO but real (motion evidence sufficient)
                    //
                    // The 2-second grace is safe because YOLO gets queued at motion start
                    // (early AI init). If it hasn't confirmed in 5+ seconds of motion, the
                    // object is genuinely undetectable by YOLO and motion evidence alone
                    // must be trusted.
                    boolean deterrentActive = deterrentFiredTime > 0
                            && (now - deterrentFiredTime) < DETERRENT_SUPPRESSION_MS;
                    // Confirmed during THIS sequence — either a YOLO hit since
                    // firstMotionTime, OR a live in-zone person track (which is
                    // itself the product of a prior real YOLO person detection;
                    // see sequenceConfirmed / track-anchored confirmation above).
                    boolean aiRecentlyConfirmed = sequenceConfirmed;
                    // FIX: aiAvailable must also reflect the user-side gate
                    // (classFilter empty OR aiEnabled false). Previously this
                    // only tracked the daemon-classpath state, so a user who
                    // turned all object classes off in the UI fell into the
                    // "no AI" path on every loop iteration — and combined
                    // with the missing post-stop cooldown below, every gust
                    // of motion fired a recording. That leaked MediaCodec
                    // slots and thumbnail-buffer allocations until the
                    // daemon was killed by SIGABRT (codec exhaustion) or
                    // OOM, eventually tripping wrapper retry exhaustion.
                    boolean aiAvailable = useObjectDetection && yoloDetector != null
                            && aiEnabled
                            && (classFilter == null || classFilter.length > 0);
                    long timePastRequired = motionDuration - requiredDuration;  // How long past the trigger threshold
                    
                    // TIMEOUT FALLBACK: Let motion through if YOLO hasn't confirmed in time.
                    // BUT: If brightness suppression fired during this motion sequence, the
                    // motion is likely a lighting artifact. In that case, extend the timeout
                    // to the full deterrent window (5s) — persistent lights (streetlights)
                    // create motion that lasts indefinitely, so a short timeout would let
                    // them through. If it's a real person in changing light, YOLO WILL see
                    // them within 5 seconds (multiple inference opportunities).
                    // brightnessEventCloseZoneScope computed once, hoisted above
                    // the requiredDuration block (the close-zone NEAR fast-path
                    // reuses it). Same value here — do not recompute. Scoped per
                    // I3: the timeout stretch is a lighting-artifact guard for
                    // THE SUBJECT BEING EVALUATED (best-threat / close-zone
                    // quadrants) — a sweep on an unrelated camera must not
                    // stretch a real approach's 2s window to 20s.
                    // Extend the YOLO-confirm timeout when EITHER a brightness
                    // event occurred (lighting artifact) OR the native signal
                    // positively confirmed an incoherent in-place loiter (a
                    // relentlessly-waving flag / sweeping shadow). Both are
                    // motion sources that persist indefinitely, so the short 2s
                    // fallback would otherwise leak one recording. A real person
                    // is confirmed by YOLO well within this window; if YOLO
                    // genuinely can't see them, the extended timeout still fires
                    // (downgrade, not drop). cachedIncoherentLoiter is never set
                    // once a coherent/tracked frame appeared, so a real approach
                    // that briefly stalls is unaffected.
                    long timeoutMs = (brightnessEventCloseZoneScope || cachedIncoherentLoiter)
                            ? DETERRENT_SUPPRESSION_MS : 2000;
                    boolean timeoutExpired = timePastRequired > timeoutMs;
                    
                    boolean shouldSuppress = false;
                    if (aiAvailable && !aiRecentlyConfirmed && !timeoutExpired) {
                        if (effectiveThreat <= MotionPipelineV2.THREAT_MEDIUM) {
                            // MEDIUM: always require AI confirmation (with timeout fallback)
                            shouldSuppress = true;
                        } else if (deterrentActive) {
                            // HIGH during deterrent: require AI confirmation (deterrent mimics loitering)
                            shouldSuppress = true;
                        } else if (!cachedHighIsTrusted) {
                            // HIGH but UNTRUSTED (flag/shadow: incoherent in-place
                            // oscillation, no in-zone person tracker) → require AI
                            // confirmation exactly like MEDIUM. A real loiterer is
                            // either coherently translating or tracker-held, so this
                            // only gates the waving-flag / sweeping-shadow case. Still
                            // records via the 2s timeout fallback / no-YOLO paths, so
                            // a genuinely YOLO-invisible loiterer is delayed, not lost.
                            shouldSuppress = true;
                        }
                    }
                    // FAR-UNCONFIRMED TIMEOUT GATE (user-requested tightening).
                    // The 2s AI-timeout fallback above lets an event record with NO
                    // YOLO confirmation once motion is merely sustained — regardless
                    // of distance. On a device where YOLO classifies nothing, that
                    // fallback fires on EVERY event, so distant/unconfirmed motion
                    // (a person walking by across the street, a car passing on the
                    // road) records just like a real close approach. Per user: a FAR,
                    // unconfirmed detection should NOT record.
                    //
                    // So when the recording would fire ONLY because the timeout
                    // expired (not a YOLO confirmation, not a trusted-HIGH loiter)
                    // AND the subject never reached the close zone (NEAR|MID —
                    // peakCloseZoneDuringSequence stays false ⇔ it was FAR/UNKNOWN the
                    // whole sequence), re-suppress. This is DELIBERATELY narrow:
                    //   - aiAvailable            → don't change no-YOLO daemon mode.
                    //   - !aiRecentlyConfirmed   → a YOLO-confirmed object still records.
                    //   - !cachedHighIsTrusted   → a trusted (coherent/tracker) loiter
                    //                              still records at any distance.
                    //   - timeoutExpired         → only the pure-timeout path, not the
                    //                              within-duration confirmed path.
                    //   - !peakCloseZoneDuringSequence → only FAR/UNKNOWN-only events;
                    //                              anything that reached NEAR/MID (incl.
                    //                              the close-zone override below) records.
                    // TRADE-OFF (accepted): a real FAR subject YOLO genuinely cannot
                    // classify is no longer recorded on motion alone. The close-zone
                    // override still catches it the moment it comes within NEAR/MID.
                    // Salience is an exemption here: the gate's premise is "no YOLO
                    // actor, untrusted, and never came close — so there is no
                    // evidence of a real object". A completed salience run IS that
                    // evidence, independently of the proximity tier (which is
                    // derived from the same block mass the flash filter can eat).
                    //
                    // The exemption must carry the SAME live discriminators as the
                    // salience AI-gate override below, because this path LEAVES
                    // shouldSuppress false and therefore that override — which is
                    // guarded on shouldSuppress being true — never runs to apply them.
                    // Without them a latch set by 6 ticks of a swinging banner in one
                    // quadrant stays valid for the whole sequence (the latch clears
                    // only at sequence start, and continuous MEDIUM+ motion elsewhere
                    // keeps refreshing lastMotionTime so the sequence never ends),
                    // exempting an unrelated distant passer-by in the best-threat
                    // quadrant from a gate that used to suppress it indefinitely.
                    boolean salienceExempts = salienceMayTrigger()
                            && !brightnessEventSalienceScope
                            && !deterrentActive
                            && salientQuadrantStillCoherent(results);
                    // POST-PARK VIGILANCE exemption — sibling of salienceExempts,
                    // for the fresh-parker exit FN. The gate's premise is "no
                    // YOLO actor, untrusted, never came close → no evidence of a
                    // real object". A watched arrival minutes ago at exactly this
                    // spot IS that evidence: a car parked beyond the NEAR/MID
                    // tiers keeps peakCloseZoneDuringSequence false for its
                    // occupant's whole exit, which is precisely the clip this
                    // channel exists to save. Carries the same live
                    // discriminators as its consumers elsewhere (brightness /
                    // deterrent / positive incoherence), plus the latch's own
                    // quadrant scoping and the min-gap + budget brakes inside
                    // vigilanceMayTrigger().
                    boolean vigilanceExempts = vigilanceMayTrigger()
                            && !brightnessEventVigilanceScope
                            && !deterrentActive
                            && !cachedIncoherentLoiter
                            && pipelineV2.getHighestThreatQuadrant() == vigilanceQuadrant;
                    if (!shouldSuppress
                            && aiAvailable
                            && !aiRecentlyConfirmed
                            && !cachedHighIsTrusted
                            && timeoutExpired
                            && !peakCloseZoneDuringSequence
                            && !salienceExempts
                            && !vigilanceExempts) {
                        shouldSuppress = true;
                        if (frameCount % 50 == 0) {
                            logger.info("Far-unconfirmed gate: suppressing timeout-fallback "
                                    + "recording (no YOLO actor, untrusted, never reached "
                                    + "NEAR/MID — stayed far)");
                        }
                    } else if (!shouldSuppress
                            && aiAvailable
                            && !aiRecentlyConfirmed
                            && !cachedHighIsTrusted
                            && timeoutExpired
                            && !peakCloseZoneDuringSequence
                            && vigilanceExempts
                            && !salienceExempts) {
                        // Attribution (I7): vigilance was DECISIVE for this pass —
                        // the gate would have suppressed but for the fresh-parker
                        // evidence. One line per pass window, not per tick.
                        if (frameCount % 50 == 0) {
                            logger.info("Far-unconfirmed gate: PASSED on post-park vigilance "
                                    + "(fresh-parker anchor Q" + vigilanceQuadrant
                                    + " — probable occupant exit)");
                        }
                    }
                    // CLOSE-ZONE PROXIMITY OVERRIDE (safety-critical FN fix).
                    // A genuine subject physically CLOSE to the car (NEAR/MID tier,
                    // in the configured close zone) is a real threat regardless of
                    // whether YOLO returned a *person* class in the short window it
                    // was present. On-car FN: a person parked a bike and walked up to
                    // ~2.5m on the right cam; YOLO ran only twice (cooldown +
                    // inference in a 1.1s window), caught only the bike, and the
                    // AI-confirm gate above suppressed the untrusted HIGH — the
                    // person clip was lost. When motion reaches the close zone
                    // (NEAR or MID, not FAR) with a sustained (>=1s) HIGH/MEDIUM
                    // threat, clear the AI-confirm hold.
                    //
                    // FP-safety (must STILL suppress a waving flag / sweeping shadow):
                    //   (1) !brightnessEventCloseZoneScope — lighting-artifact signature,
                    //       evidence-scoped (I3) to the close-zone/best-threat quadrants.
                    //   (2) !deterrentActive — our own light flash.
                    //   (3) NEAR/MID (not FAR/UNKNOWN) via probeProx below.
                    //   (4) !cachedIncoherentLoiter — the fail-CLOSED discriminator.
                    //       This is the SAME motion-only gating as the requiredDuration
                    //       bar above: the target FN is DEFINED by the absence of any
                    //       YOLO-person / coherent-trust confirmation, so requiring
                    //       (cachedHighIsTrusted || sequenceConfirmed) here would make
                    //       the override unreachable for its own scenario (and, since
                    //       the bar above would then never drop below 3s, the whole
                    //       !recording block — this override included — would never even
                    //       run at 1.1s). cachedIncoherentLoiter latches ONLY on POSITIVE
                    //       incoherence once the native coherence signal fires (flowCoherence
                    //       >=0 && <ratioMin && netDrift<netMin; ~:2017); it is inert for
                    //       the first ~0.8s (netRingCount<coherenceMinFrames), so the
                    //       override opens on close-zone motion evidence up front and only
                    //       a confirmed flag/shadow closes it, while a coherent close
                    //       approach (never arms it) clears the AI-confirm hold.
                    // Being in the user's zone is already implied by the MEDIUM+ threat
                    // (native maxDistanceRow centroid gate); the NEAR/MID tier is the
                    // extra "genuinely close, not far" evidence. Uses the same
                    // peakCloseZone latch and same !cachedIncoherentLoiter guard as the
                    // requiredDuration bar above so the two stages AGREE (bar reachable
                    // ⇔ override can clear): identical open/close conditions on both.
                    if (shouldSuppress
                            && effectiveThreat >= MotionPipelineV2.THREAT_MEDIUM
                            && motionDuration >= 1000L
                            && peakCloseZoneDuringSequence
                            && !brightnessEventCloseZoneScope
                            && !cachedIncoherentLoiter
                            && (!deterrentActive || (now - deterrentFiredTime) >= 5000L)) {
                        int probeQ = pipelineV2.getHighestThreatQuadrant();
                        // Zone gate that does NOT require a live tracker lock. The
                        // target FN is DEFINED by the absence of a YOLO person class
                        // (hence no person track) — the only tracker seed is a YOLO
                        // 'best' (see ~:3702) — so demanding trackerInZone() here would
                        // make the override dead for its own scenario (no track →
                        // trackerInZone returns false on the null box). Proceed when
                        // there is NO active track (in-zone is established by the
                        // MEDIUM+ maxDistanceRow gate + peakCloseZone + tier below), and
                        // only apply the bbox-bottom zone filter when a track actually
                        // exists AND is a PERSON (classId==0) — a stale bike/car track
                        // must not act as the zone witness (mirrors every other
                        // trackerInZone call site, which pairs it with a person check).
                        boolean trackActive = false;
                        boolean trackIsInZonePerson = false;
                        if (probeQ >= 0) {
                            try {
                                if (NativeMotion.trackerHasActiveTrack(probeQ)) {
                                    float[] tb = NativeMotion.trackerGetTrackBox(probeQ);
                                    if (tb != null && tb.length >= 7 && tb[6] > 0f) {
                                        trackActive = true;
                                        trackIsInZonePerson = (int) tb[5] == 0
                                                && trackerInZone(probeQ);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        boolean zoneOk = !trackActive || trackIsInZonePerson;
                        if (probeQ >= 0 && zoneOk) {
                            DistanceEstimator.ProximityEstimate probeProx =
                                    proximityForQuadrant(probeQ, results[probeQ]);
                            if (probeProx != null
                                    && probeProx.tier != DistanceEstimator.Tier.FAR
                                    && probeProx.tier != DistanceEstimator.Tier.UNKNOWN) {
                                shouldSuppress = false;
                                logger.info(String.format(
                                    "Close-zone override: %s in-zone %s motion %.1fs "
                                    + "(track=%s) — recording on motion evidence",
                                    probeProx.tier.name(),
                                    (effectiveThreat >= MotionPipelineV2.THREAT_HIGH
                                        ? "HIGH" : "MEDIUM"),
                                    motionDuration / 1000.0,
                                    trackActive ? "person-in-zone" : "none"));
                            }
                        }
                    }
                    // MOTION-SALIENCE AI-GATE OVERRIDE. Sibling of the close-zone
                    // override above, for the case where the subject is real and
                    // obvious but the proximity TIER never resolved — which is the
                    // norm for the large-close-object miss, because tierFromMotion
                    // reads NEAR at >=20 active blocks, exactly the mass the native
                    // flash filter used to discard. So peakCloseZone can be false on
                    // the very events with the most motion evidence.
                    //
                    // Clearing the hold on salience alone is sound because the latch
                    // already required 6 consecutive ticks of mass + compactness +
                    // rigid translation + luma stability + in-zone MEDIUM+ threat.
                    // The two FP sources that outlive the per-tick latch checks are
                    // re-tested here: our own deterrent flash, and a brightness event
                    // — the latter evidence-scoped (I3) to the salient quadrant + the
                    // best-threat quadrant, matching this override's own refusal (below)
                    // to let an unrelated quadrant's incoherence veto this one.
                    // Note on !cachedIncoherentLoiter: the two sibling close-zone paths
                    // carry it, this one deliberately does not. That latch is armed by
                    // the HIGHEST-THREAT quadrant only, whereas salience is per-quadrant
                    // and its own evidence is strictly stronger — it already required
                    // POSITIVE coherence (flowCoherence >= 0 AND above ratio/drift) on
                    // the salient quadrant for 6 consecutive ticks, which is the exact
                    // inverse of what arms cachedIncoherentLoiter. Adding it would let
                    // an incoherent OTHER quadrant (a bush in Q2) veto a coherent
                    // approach in Q3 — the scene-wide-OR false negative this file
                    // already had to fix once for the night-discard latches. The
                    // salient quadrant's own live coherence is re-checked below instead.
                    if (shouldSuppress
                            && salienceMayTrigger()
                            && !brightnessEventSalienceScope
                            && !deterrentActive
                            && salientQuadrantStillCoherent(results)) {
                        shouldSuppress = false;
                        logger.info(String.format(
                            "Motion-salience override: %s sustained %.1fs — recording on "
                            + "object-grade motion evidence (no YOLO class, untrusted loiter)",
                            salienceQuadrant >= 0
                                    ? MotionPipelineV2.QUADRANT_NAMES[salienceQuadrant] : "?",
                            motionDuration / 1000.0));
                    }
                    // NO-YOLO DETERRENT FALLBACK: When object detection is not available
                    // (daemon mode without Context/AssetManager), the AI gate can't function.
                    // But we still know when OUR OWN deterrent fired. Use a pure time-based
                    // suppression: block new recordings for the full deterrent window after
                    // we fired the lights. This prevents the exact scenario from the logs:
                    // deterrent fires → light flash → motion re-triggers → second recording.
                    // Without YOLO there's no way to confirm "is this a real person or just
                    // our own lights?" so we err on the side of suppressing false positives.
                    // Real threats that arrive during the 5s window will still be caught
                    // because the first recording's post-record (10s) covers the gap.
                    if (!aiAvailable && deterrentActive && !recording) {
                        shouldSuppress = true;
                        if (frameCount % 50 == 0) {
                            logger.debug(String.format(
                                "No-YOLO deterrent guard: suppressing (deterrent %.1fs ago, no AI available)",
                                (now - deterrentFiredTime) / 1000.0));
                        }
                        firstMotionTime = 0;
                        peakThreatDuringSequence = 0;
                    }

                    // NO-AI RATE LIMIT: when YOLO is off, motion-only triggers re-fire
                    // on every wind gust / shadow / streetlight artifact. Each retrigger
                    // forces a fresh muxer init + pre-record flush; over a multi-hour
                    // park that storm leaks MediaCodec instance slots on the Adreno 610
                    // and steadily inflates direct-buffer RSS until the daemon takes
                    // SIGABRT (codec exhaustion) or SIGKILL (LMK). YOLO confirmation is
                    // the natural rate-limit on real installs — without it, enforce a
                    // minimum gap between consecutive recordings so a noisy parking lot
                    // can't trigger more than once per NO_AI_MIN_GAP_MS.
                    //
                    // Only applies when AI is genuinely unavailable (daemon-classpath
                    // OR user-disabled) AND we're not already recording AND the last
                    // recording stopped recently. The post-record window (10s default)
                    // already covers the immediate aftermath; this gate runs after that.
                    if (!aiAvailable && !recording && lastRecordingStopTime > 0) {
                        long sinceLastStop = now - lastRecordingStopTime;
                        if (sinceLastStop < NO_AI_MIN_GAP_MS) {
                            shouldSuppress = true;
                            if (frameCount % 50 == 0) {
                                logger.debug(String.format(
                                    "No-AI rate limit: suppressing (last recording stopped %.1fs ago, min gap %.1fs)",
                                    sinceLastStop / 1000.0, NO_AI_MIN_GAP_MS / 1000.0));
                            }
                            firstMotionTime = 0;
                            peakThreatDuringSequence = 0;
                        }
                    }

                    // With AI available, a current-sequence person or new/moved non-baseline
                    // object authorizes recording. Also allow recording if:
                    // 1) A confirmed person was seen recently (stationary-subject recency window of 60s),
                    //    preventing MOG2 background absorption or brief detection stalls from freezing re-arming.
                    // 2) The close-zone proximity override or motion-salience override cleared shouldSuppress above.
                    boolean recentPersonPresence = (nowElapsed - lastPersonConfirmationElapsedMs) < 60000L
                            && lastPersonConfirmationElapsedMs > 0;
                    boolean overrideClearedSuppression = !shouldSuppress;

                    if (aiAvailable && !aiRecentlyConfirmed && !recentPersonPresence && !overrideClearedSuppression) {
                        shouldSuppress = true;
                    }
                    
                    if (shouldSuppress) {
                        if (frameCount % 50 == 0) {
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.debug(String.format(
                                "AI gate holding: threat=%s, motion=%.1fs, grace=%.1fs remaining, deterrent=%s, brightnessEvent=%s/scoped=%s",
                                tNames[effectiveThreat], motionDuration / 1000.0,
                                Math.max(0, timeoutMs - timePastRequired) / 1000.0,
                                deterrentActive ? "active" : "inactive",
                                brightnessEventAnyQuadrant ? "yes" : "no",
                                brightnessEventCloseZoneScope ? "yes" : "no"));
                        }
                        // ANTI-STALL WATCHDOG: When YOLO confirms, the trigger fires immediately.
                        // However, if the sequence stays unconfirmed for >10s past requiredDuration
                        // (e.g. subject absorbed by MOG2 background, or stationary loiter with no
                        // detections), we must NOT allow motionDuration to run indefinitely (30s..75s+).
                        // Resetting the sequence timer forces the motion pipeline, threat latches,
                        // and early-AI queueing to cleanly re-arm on the next motion frame.
                        if (motionDuration > (requiredDuration + 10000L)) {
                            logger.info(String.format(
                                "AI gate unstick: held unconfirmed for %.1fs (>10s past required) — resetting sequence to re-arm",
                                motionDuration / 1000.0));
                            firstMotionTime = 0;
                            firstMotionElapsedMs = 0;
                            peakThreatDuringSequence = 0;
                            peakCloseZoneDuringSequence = false;
                        }
                    }
                    // SOTA: Event stitching — if new motion appears shortly after the last
                    // recording stopped, start a new recording immediately. The previous
                    // recentlyStoppedRecording cooldown blocked new recordings for the entire
                    // postRecordMs window after a stop, causing missed events when someone
                    // lingered near the car. The 3-second sustained motion requirement already
                    // prevents rapid-fire false triggers, so the cooldown is unnecessary.
                    else {
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        // If no quadrant has motion (e.g., tracker held through flash),
                        // fall back to the quadrant with an active tracker lock.
                        // ZONE GATE: only consider trackers whose bbox bottom is
                        // in-zone — otherwise an out-of-zone person locked by
                        // the tracker can leak past the user's "close"/"normal"
                        // setting and trigger recording. trackerInZone() returns
                        // true when the gate is disabled ("extended") so this
                        // doesn't change behaviour for users who chose extended.
                        if (bestQ < 0) {
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq) && trackerInZone(tq)) {
                                        bestQ = tq;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                        String triggerSource = (pipelineV2.getMaxThreatLevel() >= MotionPipelineV2.THREAT_MEDIUM)
                                ? "motion" : "tracker";
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        MotionPipelineV2.QuadrantResult bestResult = bestQ >= 0 ? results[bestQ] : null;

                        // SOTA proximity: prefers post-YOLO bbox-height inference,
                        // falls back to discrete tier+trend pre-YOLO. The trigger
                        // gate itself doesn't depend on this value (zone gating is
                        // row-based natively); this is for the human-readable log
                        // line and the downstream notification copy.
                        DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                                ? proximityForQuadrant(bestQ, bestResult)
                                : DistanceEstimator.ProximityEstimate.tierOnly(
                                        DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                        String proxStr = prox.describe();

                        String detectionZone = config != null ? config.getDetectionZone() : "?";
                        int sensitivityLevel = config != null ? config.getSensitivityLevel() : -1;
                        int loiteringSec = config != null ? config.getLoiteringTimeSeconds() : -1;
                        int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                        String zoneLimitStr = maxRow > 0
                                ? (maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended")
                                : "none";

                        logger.info(String.format(
                            ">>> RECORDING TRIGGERED <<<\n" +
                            "  Camera: %s | Threat: %s | Proximity: %s | Sustained: %.1fs | Source: %s | %s\n" +
                            "  Blocks: active=%d, confirmed=%d, component=%d\n" +
                            "  Settings: sensitivity=%d, zone=%s (limit %s), loiterTime=%ds\n" +
                            "  Why: threat %s >= MEDIUM ✓, duration %.1fs >= %.1fs ✓, proximity %s within zone ✓",
                            qName, threatNames[maxThreat], proxStr, motionDuration / 1000.0, triggerSource,
                            describeHighTrust(maxThreat, cachedHighIsTrusted, bestResult),
                            bestResult != null ? bestResult.activeBlocks : 0,
                            bestResult != null ? bestResult.confirmedBlocks : 0,
                            bestResult != null ? bestResult.componentSize : 0,
                            sensitivityLevel, detectionZone,
                            zoneLimitStr,
                            loiteringSec,
                            threatNames[maxThreat], motionDuration / 1000.0, requiredDuration / 1000.0,
                            proxStr));
                        
                        long startedGeneration = startRecording();
                        boolean startedThisEvent = false;
                        String startedVideoFilename = null;

                        // Only fire the start-stage notifications when a recording
                        // is actually active. startRecording() can refuse (encoder
                        // savedFormat barrier on cold boot) and leave recording=false
                        // — without this guard a "Recording in progress" Telegram
                        // ping + push banner would fire for an event that never
                        // started and whose final-stage replacement never comes,
                        // leaving a dangling never-resolved notification.
                        // Latch whether THIS event fired from native motion rather
                        // than a tracker/deferred-person path. Threat level is
                        // deliberately irrelevant: coherent moving shadows can be
                        // classified HIGH(loiter), which made the user-enabled
                        // non-actor discard inert. Set AFTER startRecording
                        // (which resets the latch for the fresh event), and only
                        // when recording actually started.
                        synchronized (recordingLifecycleLock) {
                            if (startedGeneration >= 0
                                    && recording
                                    && recordingGeneration.get() == startedGeneration) {
                                startedThisEvent = true;
                                startedVideoFilename = currentEventFile != null
                                        ? currentEventFile.getName() : null;
                            eventTriggerWasMotionOnly =
                                    EmptyMotionDiscardPolicy.isMotionSourceTrigger(triggerSource);
                            // A raw YOLO result can open the trigger path immediately
                            // before startRecording() resets event-scoped evidence.
                            // Carry it only when the source frame belongs to this
                            // sequence and quadrant; stale/other-camera detections
                            // cannot weaken the yoloBlind safety keep.
                            if (bestQ >= 0
                                    && EmptyMotionDiscardPolicy.rawDetectionBelongsToSequence(
                                            lastRawDetectionElapsedMs.get(bestQ),
                                            firstMotionElapsedMs)) {
                                eventYoloSawRawDetections = true;
                            }
                            // AUTHORIZING-EVIDENCE CARRY (audit R7 ExtC-8):
                            // startRecording's post-commit block wipes ALL
                            // event latches — including eventEverSawPerson
                            // set by the PRE-trigger YOLO confirmation that
                            // opened this trigger's AI gate. If the person
                            // exits before any post-start inference completes
                            // (the brisk close walk-past the CLOSE_CONFIRMED
                            // fast path exists for), the opt-in discard then
                            // saw "no actor evidence" and deleted a clip
                            // whose pre-roll contains a YOLO-confirmed
                            // person. Re-latch from the sequence-scoped
                            // person-confirmation stamp: latch-only (converts
                            // discards into KEEPs, never the reverse), and a
                            // vehicle-confirmed gate has no person stamp so
                            // the shadow-FP discard is preserved.
                            if (lastPersonConfirmationElapsedMs >= firstMotionElapsedMs
                                    && firstMotionElapsedMs > 0) {
                                eventEverSawPerson = true;
                            }
                            // Latch the side-camera lateral proximity-mass override
                            // (native motion_pipeline_v2.cpp:768: componentSize/70 >
                            // 0.15 on a left/right cam). On the fisheye side cams a
                            // large lateral object that YOLO returns 0 detections for
                            // is barrel distortion (project_fisheye_dewarp), NOT an
                            // empty scene — so a possible real close lateral actor.
                            // This makes shouldDiscardEvent KEEP such a clip even
                            // when no Actor ever latched, closing the bright-fisheye
                            // false-negative. Front/rear cams and sub-15% components
                            // (the shadow/leaf signature) are unaffected.
                            if (bestResult != null && (bestQ == 1 || bestQ == 3)
                                    && (bestResult.componentSize
                                        / (float) MotionPipelineV2.TOTAL_BLOCKS) > 0.15f) {
                                eventTriggerWasLateralMass = true;
                            }
                            // Latch whether the recording fired WITHOUT any
                            // in-sequence YOLO confirmation (the AI-timeout
                            // fallback that exists to trust motion when "the
                            // object is too small/dark/distorted for YOLO but
                            // real"). A genuinely-empty shadow FP instead opens
                            // its AI gate via the PARKED CAR's own YOLO boxes, so
                            // it has sequenceConfirmed==true and stays discardable;
                            // a real person/vehicle YOLO never classified leaves
                            // this true and must never be auto-deleted.
                            eventTriggerWasAiTimeout = !sequenceConfirmed;
                            // Latch salience as the trigger evidence for this event.
                            // Read by shouldDiscardEvent as a hard KEEP: the no-actor
                            // discard deletes clips for lack of actor evidence, and a
                            // salience run is precisely non-actor evidence that a real
                            // object was there. Deleting such a clip would throw away
                            // the recording this channel exists to produce.
                            eventTriggerWasSalience = salienceActive();
                            // POST-PARK VIGILANCE attribution + budget. An
                            // UNCONFIRMED trigger with a live vigilance latch is
                            // a vigilance-assisted recording: latch it for the
                            // discard/keep log lines (I7 — NOT a KEEP clause;
                            // the no-actor discard is this channel's precision
                            // partner) and consume one unit of the rolling
                            // assist budget (I10 brake). A YOLO-confirmed
                            // trigger records through its own path and costs
                            // nothing here — slight overcounting on triggers
                            // that would also have passed via close-zone is
                            // accepted: it only ever TIGHTENS the brake.
                            // QUADRANT LINKAGE (audit R3b Ext-12): the assist
                            // budget is consumed only when the trigger's own
                            // quadrant IS the vigilance quadrant — every
                            // vigilance consumer already requires
                            // bestQ == vigilanceQuadrant, so an unconfirmed
                            // trigger from another quadrant or an independent
                            // fast path was never vigilance-assisted; charging
                            // it exhausted the 3-per-10min budget and
                            // suppressed a GENUINE assist minutes later (FN),
                            // and mislabeled the event in logs/stats.
                            eventTriggerWasVigilance = !sequenceConfirmed
                                    && vigilanceActive()
                                    && bestQ == vigilanceQuadrant;
                            if (eventTriggerWasVigilance) {
                                vigilanceAssistStamps.addLast(
                                        android.os.SystemClock.elapsedRealtime());
                                logger.info(String.format(
                                    "Post-park vigilance assisted this trigger (Q%d anchor); "
                                    + "budget used %d/%d in window",
                                    vigilanceQuadrant, vigilanceAssistStamps.size(),
                                    VIGILANCE_MAX_ASSISTS));
                            }
                            // MOTION-EVIDENCE SEVERITY FLOOR (notification fix).
                            // Latch when the trigger itself was a strong threat
                            // signal, so a YOLO-less event (0 actors → eventPeakSeverity
                            // null → NOTICE) can still push as ALERT instead of being
                            // silently swallowed at the NOTICE tier. NARROW by design,
                            // and MIRRORS SeverityClassifier's proximity rules so this
                            // never labels something ALERT that a classified actor
                            // wouldn't be. Only these qualify (ALL gated to MID/NEAR —
                            // distance is required, see the closeEnough guard below):
                            //   - a TRUSTED HIGH loiter (coherent translation or an
                            //     in-zone person tracker holds it — never a flag/shadow,
                            //     which is gated to MEDIUM and requires YOLO) that is
                            //     also MID/NEAR, or
                            //   - proximity reached NEAR (subject physically AT the car
                            //     — the close zone), or
                            //   - proximity is MID *and* APPROACHING (closing distance
                            //     at mid-range — matches SeverityClassifier's
                            //     "PERSON @ MID + APPROACHING → ALERT" rule).
                            // Distance is REQUIRED: a FAR or UNKNOWN-tier detection is
                            // NEVER a strong threat, even a trusted-HIGH loiter or an
                            // APPROACHING one — "motion detected far away" must stay
                            // NOTICE, matching the Alert definition ("close enough to
                            // warrant attention"). Plain MID-stable / far / unknown
                            // passing motion does NOT set this, so distant passers-by
                            // stay NOTICE (and under the event-evidence push gate,
                            // aren't pushed at all).
                            // Proximity must be at least MID for ANY alert floor —
                            // a FAR or UNKNOWN-tier detection is never "close enough
                            // to warrant attention", so it can never be ALERT even if
                            // it reads HIGH-trusted or APPROACHING. This is the hard
                            // "far away is never an Alert" guard.
                            boolean closeEnough = prox != null
                                    && (prox.tier == DistanceEstimator.Tier.NEAR
                                        || prox.tier == DistanceEstimator.Tier.MID);
                            // A trusted HIGH loiter (coherent translation / in-zone
                            // person tracker) that is also close enough (MID/NEAR).
                            boolean trustedHighClose =
                                    maxThreat >= MotionPipelineV2.THREAT_HIGH
                                    && cachedHighIsTrusted
                                    && closeEnough;
                            // Close proximity as its own signal: NEAR (subject at the
                            // car, any trend) or MID *and* APPROACHING (closing at
                            // mid-range). Mirrors SeverityClassifier's proximity rules.
                            boolean closeProximity = prox != null
                                    && (prox.tier == DistanceEstimator.Tier.NEAR
                                        || (prox.tier == DistanceEstimator.Tier.MID
                                            && prox.trend == DistanceEstimator.Trend.APPROACHING));
                            if (trustedHighClose || closeProximity) {
                                eventTriggerWasStrongThreat = true;
                            }
                            }
                        }

                        // Only fire the start-stage notifications when a recording
                        // is actually active. startRecording() can refuse (encoder
                        // savedFormat barrier on cold boot) and leave recording=false
                        // — without this guard a "Recording in progress" Telegram
                        // ping + push banner would fire for an event that never
                        // started and whose final-stage replacement never comes,
                        // leaving a dangling never-resolved notification.
                        if (startedThisEvent) {
                            try {
                                sendRichMotionNotifications(startedVideoFilename);
                                publishMotionNotification(startedVideoFilename);
                            } catch (Exception e) {
                                logger.warn("Failed to send motion notification: " + e.getMessage());
                            }
                        }

                        // SOTA: Fire deterrents (cloud + screen). Both run on
                        // background threads and never block the surveillance pipeline.
                        // Each one independently honors its own enabled flag and cooldown.
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            ScreenDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;  // Track when deterrent was dispatched
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                } else {
                    // Already recording — extend recording timer on continued motion.
                    // Any quadrant with MEDIUM+ threat extends the recording.
                    long newStopTime = now + postRecordMs;
                    if (newStopTime > recordingStopTime) {
                        recordingStopTime = newStopTime;
                    }

                    // SOTA: Recurring deterrent — re-trigger while motion continues.
                    // Per-deterrent cooldowns prevent spamming.
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                        ScreenDeterrent.getInstance().onMotionDetected();
                        deterrentFiredTime = now;  // Track latest deterrent dispatch
                    } catch (Exception e) {
                        // Fail silently — never block surveillance
                    }
                    
                    // Also run YOLO on new quadrants that have motion (even if different from original)
                    if (useObjectDetection && !isAiRunning.get()) {
                        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                            if (results[q].motionDetected && results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                            }
                        }
                        // FIX: Check cooldown before consuming queue item
                        if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                            runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                        }
                    }
                }

                // Staggered YOLO: queue active quadrants for AI detection
                if (useObjectDetection && !isAiRunning.get()) {
                    aiQuadrantQueueClear();
                    // Add quadrants sorted by threat level (highest first)
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (q != bestQ && results[q].motionDetected) {
                            aiQuadrantQueueAdd(q);
                        }
                    }
                    // FIX: Check cooldown before consuming queue item
                    if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                        runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                    }
                }
            }
        } else {
            // No motion detected on this frame (all quadrants below MEDIUM threat).
            // Don't immediately end the sequence — allow gaps up to 2 seconds.
            // A person walking past creates motion bursts with brief gaps as they
            // move between quadrants or between block boundaries. A 500ms gap was
            // too tight and caused the sequence to reset before reaching the trigger.
            if (!recording) {
                long timeSinceLastMotion = now - lastMotionTime;
                
                // SOTA: Extend gap tolerance during cross-quadrant transit.
                // When a person walks from the left camera to the rear camera, there's
                // a brief gap where neither camera has MEDIUM+ threat (left is decaying,
                // rear hasn't confirmed yet). Without this fix, the 2-second timeout
                // resets firstMotionTime, and the rear camera starts a fresh sequence
                // from zero — the person's total approach time is never accumulated.
                //
                // If the texture tracker has an active track, we know an object is still
                // physically present. Extend the gap tolerance to 4 seconds to bridge
                // the cross-quadrant handoff. Also check for any quadrant with active
                // blocks (even below MEDIUM threat) as a secondary signal.
                //
                // FIX: Also extend tolerance if YOLO is currently running or queued.
                // The tracker is started inside the async YOLO lambda. If motion drops
                // before the lambda executes, trackerHasActiveTrack returns false even
                // though YOLO is about to classify the person and start a track.
                boolean trackerActive = false;
                boolean anyLowActivity = false;
                boolean aiPending = isAiRunning.get() || !aiQuadrantQueueIsEmpty();
                // SOTA: If YOLO confirmed a person during this motion sequence, extend
                // gap tolerance. The person may have briefly moved between block boundaries
                // or between quadrants, causing motion to drop below MEDIUM. But YOLO
                // already verified they're real — don't kill the sequence prematurely.
                boolean aiConfirmedDuringSequence = (firstMotionTime > 0) 
                        && (lastAiConfirmationElapsedMs >= firstMotionElapsedMs);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                // ZONE GATE: only extend gap tolerance for an
                                // in-zone person. An out-of-zone tracker lock
                                // shouldn't keep the motion sequence alive
                                // past the normal 2s gap.
                                if (trackerInZone(q)) {
                                    trackerActive = true;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    if (results[q].activeBlocks > 0) anyLowActivity = true;
                }
                long gapTolerance = (trackerActive || anyLowActivity || aiPending || aiConfirmedDuringSequence) ? 4000 : 2000;
                
                // DEFERRED TRIGGER: If the motion duration already exceeded the threshold
                // during this sequence AND YOLO confirmed a real person, trigger NOW even
                // though the current frame has no MEDIUM+ motion. This catches the case
                // where the person is still present (YOLO confirmed) but motion blocks
                // briefly dipped below MEDIUM on the exact frame where duration crossed
                // the threshold. Without this, the sequence dies at gap tolerance expiry
                // even though all conditions were met.
                if (firstMotionTime != 0 && !recording && aiConfirmedDuringSequence) {
                    long motionDuration = lastMotionTime - firstMotionTime;
                    // Mirror the inline requiredDuration logic EXACTLY: only a
                    // TRUSTED HIGH (coherent/tracked loiter) gets the 500ms base —
                    // an untrusted HIGH (flag/shadow) is treated like MEDIUM. Else
                    // the loiter bar, shortened to approachTriggerMs because YOLO has
                    // confirmed a real object during this sequence (the gate above).
                    long requiredMs;
                    if (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH && cachedHighIsTrusted) {
                        requiredMs = SUSTAINED_MOTION_BASE_MS;
                    } else {
                        requiredMs = loiteringTimeMs;
                        if (approachTriggerMs > 0 && approachTriggerMs < requiredMs) {
                            requiredMs = approachTriggerMs;
                        }
                        // Close-range fast-path (mirror of the inline gate): AI is
                        // already confirmed here (aiConfirmedDuringSequence guards
                        // this block), so only the NEAR latch is additionally needed.
                        if (CLOSE_CONFIRMED_TRIGGER_MS > 0
                                && peakNearDuringSequence
                                && CLOSE_CONFIRMED_TRIGGER_MS < requiredMs) {
                            requiredMs = CLOSE_CONFIRMED_TRIGGER_MS;
                        }
                    }
                    if (motionDuration >= requiredMs && peakThreatDuringSequence >= MotionPipelineV2.THREAT_MEDIUM) {
                        // All conditions met: duration exceeded, threat was MEDIUM+, YOLO confirmed person
                        logger.info(String.format("DEFERRED TRIGGER: motion=%.1fs >= %.1fs, AI confirmed, triggering from gap phase",
                                motionDuration / 1000.0, requiredMs / 1000.0));
                        inActiveMode = true;
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        if (bestQ < 0) {
                            // No quadrant has motion right now — use the last known active quadrant.
                            // ZONE GATE: only fall back to trackers whose bbox bottom is in-zone.
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq) && trackerInZone(tq)) { bestQ = tq; break; }
                                } catch (Exception ignored) {}
                            }
                        }
                        long startedGeneration = startRecording();
                        boolean startedThisEvent = false;
                        String startedVideoFilename = null;
                        // Guard on recording (see the other trigger site): no
                        // start-stage notification when startRecording() refused.
                        synchronized (recordingLifecycleLock) {
                            if (startedGeneration >= 0
                                    && recording
                                    && recordingGeneration.get() == startedGeneration) {
                                startedThisEvent = true;
                                startedVideoFilename = currentEventFile != null
                                        ? currentEventFile.getName() : null;
                                // startRecording resets event latches. Preserve the
                                // pre-trigger person confirmation that authorized this
                                // deferred trigger, matching the inline trigger path.
                                if (lastPersonConfirmationElapsedMs >= firstMotionElapsedMs
                                        && firstMotionElapsedMs > 0) {
                                    eventEverSawPerson = true;
                                }
                            }
                        }
                        if (startedThisEvent) {
                            try {
                                sendRichMotionNotifications(startedVideoFilename);
                                publishMotionNotification(startedVideoFilename);
                            } catch (Exception e) {
                                logger.warn("Failed to send motion notification: " + e.getMessage());
                            }
                        }
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            ScreenDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                }

                // !recording: the deferred-trigger block above (same if(!recording)
                // scope) may have just called startRecording() — which sets
                // recording=true but does NOT touch firstMotionTime/lastMotionTime,
                // so this gap-reset would otherwise fire on the SAME frame and its
                // dropAllTrackerLocks() would strip the brand-new recording's NCC
                // locks, killing the post-record trackerHolding extension and
                // truncating a standing person's clip. Skip the reset while
                // recording; stopRecording() drops the locks at stop instead.
                if (firstMotionTime != 0 && !recording && timeSinceLastMotion > gapTolerance) {
                    // Motion sequence ended without triggering
                    long motionDuration = lastMotionTime - firstMotionTime;
                    if (motionDuration > 200) {
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        // Include the TRUST term the live gate uses: a HIGH earns the
                        // short base bar ONLY when it was trusted. Omitting it printed
                        // "required=0.5s" for sequences whose effective bar was the
                        // full loiter time — which is what made the 2026-07-19 field
                        // log read as an unexplained miss.
                        //
                        // This is an UPPER BOUND, not an exact mirror: the live gate's
                        // else-branch can additionally lower the bar via approachTrigger
                        // or the two CLOSE_CONFIRMED fast paths, whose inputs
                        // (sequenceConfirmed / peakNear) are locals not in scope here.
                        // So a sequence that took a fast path may print a larger bar
                        // than it actually faced. Labelled "requiredMax" to say so.
                        boolean highTrusted = peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH
                                && cachedHighIsTrusted;
                        long requiredMs = highTrusted ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                        logger.info(String.format("Motion ended WITHOUT trigger: lasted=%.1fs, peakThreat=%s, "
                                + "requiredMax=%.1fs (highTrusted=%b), gapTolerance=%.1fs%s",
                                motionDuration / 1000.0, threatNames[peakThreatDuringSequence],
                                requiredMs / 1000.0, highTrusted, gapTolerance / 1000.0,
                                trackerActive ? " (tracker was active)" : ""));
                    }
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                    // Drop tracker locks when a sequence ends WITHOUT triggering.
                    // trackerUpdate (NCC age-out + heartbeat teardown) runs ONLY
                    // while recording, so a YOLO-person track seeded during this
                    // brief, non-triggering sequence would otherwise stay frozen-
                    // active in-zone indefinitely. The track-anchored confirmation
                    // (sequenceConfirmed, ~:1768) would then read that STALE track
                    // and grant "confirmed" status to a LATER, unrelated sequence
                    // started by a shadow/leaf — letting a lighting artifact record
                    // as if YOLO had seen a person. Dropping here bounds a track's
                    // stale lifetime to a single motion sequence. Safe for the
                    // real cases: the standing-person fix TRIGGERS (never reaches
                    // this reset), and a legitimate in-zone person triggers a
                    // recording fast (where trackerUpdate then manages teardown);
                    // only a track that failed to trigger — which shouldn't lend
                    // confirmation to anything later — is cleared.
                    dropAllTrackerLocks();
                }
            }
        }

        // Absolute recording ceiling. The short ceiling still bounds shadows,
        // stale trackers, and other events that never confirmed a person. Once
        // an event has confirmed a person, normal activity/tracker/inactivity
        // rules govern until the larger unconditional emergency ceiling. A
        // recent confirmation also supplies a bounded inactivity grace below,
        // so one missed heartbeat cannot split a continuous encounter.
        boolean freshConfirmedPersonForContinuation = recording
                && RecordingContinuationPolicy.hasFreshConfirmedPerson(
                        eventEverSawPerson,
                        nowElapsed,
                        lastPersonConfirmationElapsedMs,
                        postRecordMs);
        if (recording && recordingTriggerStartElapsedMs > 0) {
            long elapsedSinceTrigger = nowElapsed - recordingTriggerStartElapsedMs;
            RecordingContinuationPolicy.CeilingDecision ceilingDecision =
                    RecordingContinuationPolicy.evaluateCeiling(
                            elapsedSinceTrigger,
                            postRecordMs,
                            eventEverSawPerson);
            if (ceilingDecision == RecordingContinuationPolicy.CeilingDecision.STOP_BASE_CEILING
                    || ceilingDecision == RecordingContinuationPolicy.CeilingDecision.STOP_EMERGENCY_CEILING) {
                long ceilingMs = ceilingDecision
                        == RecordingContinuationPolicy.CeilingDecision.STOP_EMERGENCY_CEILING
                        ? RecordingContinuationPolicy.emergencyCeilingMs(postRecordMs)
                        : RecordingContinuationPolicy.baseCeilingMs(postRecordMs);
                String ceilingName = ceilingDecision
                        == RecordingContinuationPolicy.CeilingDecision.STOP_EMERGENCY_CEILING
                        ? "confirmed-person emergency"
                        : "unconfirmed base";
                logger.warn(String.format(
                        "V2 post-record %s ceiling reached (%.1fs >= %.1fs); force-stopping.",
                        ceilingName, elapsedSinceTrigger / 1000.0, ceilingMs / 1000.0));
                stopRecording();
                return;
            }
            if (ceilingDecision
                    == RecordingContinuationPolicy.CeilingDecision.CONTINUE_CONFIRMED_PERSON
                    && !confirmedPersonCeilingExtensionLogged) {
                long personAgeMs = nowElapsed - lastPersonConfirmationElapsedMs;
                logger.info(String.format(
                        "V2 post-record base ceiling bypassed: event confirmed a person "
                                + "(last confirmation %.1fs ago); "
                                + "emergency ceiling %.1fs.",
                        personAgeMs / 1000.0,
                        RecordingContinuationPolicy.emergencyCeilingMs(postRecordMs) / 1000.0));
                confirmedPersonCeilingExtensionLogged = true;
            }
        }

        // Post-record check: stop recording when no motion for postRecordMs.
        // SOTA: Also check ANY quadrant for activity (not just MEDIUM+ threat).
        // A person standing still near the car produces minimal block changes but
        // is still a valid reason to keep recording. Use a lower threshold:
        // any quadrant with confirmedBlocks > 0 counts as "activity" for post-record.
        if (recording && now >= recordingStopTime && recordingStopTime > 0) {

            // Check if any quadrant has residual activity (even below MEDIUM threat)
            boolean anyActivity = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].confirmedBlocks > 0 || results[q].activeBlocks > 0) {
                    anyActivity = true;
                    break;
                }
            }
            
            // SOTA: Also check texture tracker — a person standing still produces
            // zero motion blocks but the NCC tracker holds a lock on their pixel texture.
            // This is the "Static Foreground Victory" — recording stays alive as long as
            // the tracked object is present, even with zero motion pipeline activity.
            // FIX: Only person tracks (classId==0) can hold recording open.
            // Parked vehicles (motorcycles, cars) would otherwise keep recording
            // indefinitely via the "hitchhiker" pattern.
            boolean trackerHolding = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                        if (trackBox != null && (int) trackBox[5] == 0) { // class 0 = person only
                            // ZONE GATE: only let an in-zone tracker hold the
                            // post-record window open. An out-of-zone lock
                            // shouldn't keep recording alive after motion ends.
                            if (trackerInZone(q)) {
                                trackerHolding = true;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            if (anyActivity || trackerHolding || freshConfirmedPersonForContinuation) {
                // Still some activity, tracker holding, or a bounded recent
                // person confirmation: extend recording. The latter bridges an
                // isolated false-negative heartbeat without becoming unbounded.
                recordingStopTime = now + postRecordMs;
                if (trackerHolding && !anyActivity && frameCount % 100 == 0) {
                    logger.info("Post-record extended by texture tracker (no motion, object still present)");
                } else if (freshConfirmedPersonForContinuation && !anyActivity
                        && !trackerHolding && frameCount % 100 == 0) {
                    logger.info("Post-record extended by recent person confirmation "
                            + "(tracker heartbeat temporarily unavailable)");
                }
            } else {
                long timeSinceLastMotion = now - lastMotionTime;
                if (timeSinceLastMotion >= postRecordMs) {
                    logger.info(String.format("V2 post-record complete — stopping (no motion for %.1fs)",
                            timeSinceLastMotion / 1000.0));
                    stopRecording();
                }
            }
        }
        
        // SOTA: Update texture tracker on every frame (runs NCC template matching).
        // This is the core of the decoupled tracking — YOLO sleeps, NCC tracks.
        // Also handles YOLO heartbeat: when NCC confidence drops below 0.60 or
        // 3 seconds have elapsed, the tracker requests YOLO re-verification.
        if (recording) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        // Feed the quadrant crop to the tracker
                        int qW = THUMBNAIL_WIDTH / 2;
                        int qH = THUMBNAIL_HEIGHT / 2;
                        byte[] quadCrop = cropFromMosaic(smallRgbFrame, q, qW, qH);
                        if (quadCrop != null) {
                            NativeMotion.trackerUpdate(quadCrop, qW, qH, q, now);
                        }
                        
                        // Check if tracker wants YOLO heartbeat (NCC score dropped or timer expired).
                        // FIX: Enforce a hard 2-second cooldown per quadrant to prevent heartbeat spam.
                        // Without this, a failing NCC tracker fires needsYoloHeartbeat=true on every
                        // single frame, turning YOLO into a 10 FPS continuous detector and destroying
                        // the battery savings of the decoupled architecture.
                        if (NativeMotion.trackerNeedsYoloHeartbeat(q)) {
                            long timeSinceLastHeartbeat = now - lastHeartbeatTimeMs[q];
                            if (timeSinceLastHeartbeat >= HEARTBEAT_COOLDOWN_MS
                                    && useObjectDetection && !isAiRunning.get()) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                                lastHeartbeatTimeMs[q] = now;
                                logger.info("Tracker heartbeat: waking YOLO for Q" + q +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] + "]");
                            }
                        }
                    }
                } catch (Exception e) {
                    // Tracker not available — continue without it
                }
            }
        }
        
        // Process staggered YOLO queue (one per frame)
        // FIX: Check cooldown BEFORE polling the queue. Previously, poll() consumed
        // the quadrant, then runAiOnQuadrant's internal cooldown check rejected it —
        // permanently vaporizing that quadrant's AI pass.
        if (useObjectDetection && !isAiRunning.get() && !aiQuadrantQueueIsEmpty()) {
            if ((System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
            }
        }
        
        // Periodic stats
        if (frameCount % 500 == 0) {
            // Per-stage AI funnel appended so a "it missed an event" report can be
            // attributed: dispatches≈detects means the lane is healthy; dispatches
            // far exceeding detects, or skips climbing with skipReason=laneBusy,
            // means the lane is starved/wedged rather than the detector blind.
            //
            // FIX (funnel blind spot): every runAiOnQuadrant() call site pre-gates
            // on useObjectDetection, so when YOLO is OFF the function is never
            // entered and noteAiSkip("useObjectDetection=false") can never fire —
            // an all-zero funnel line was indistinguishable from "no motion".
            // Print the gate states themselves so a 7-hour dispatch=0 session is
            // attributable at a glance without per-frame counter churn (I7:
            // every dropped signal is observable).
            logger.info(String.format(
                    "V2 stats: frames=%d, motions=%d, recording=%b | AI dispatch=%d detect=%d skip=%d "
                    + "(last=%s) laneBusy=%b repairs=%d | gates: useOD=%b aiEnabled=%b detector=%s",
                    frameCount, motionDetections, recording,
                    aiDispatchCount.get(), aiDetectCompletedCount.get(), aiSkipCount.get(),
                    lastAiSkipReason, isAiRunning.get(), aiLaneRepairCount.get(),
                    useObjectDetection, aiEnabled,
                    (yoloDetector != null ? "ok" : "NULL")));
            // Log per-quadrant status for debugging
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                String status = r.brightnessSuppressed ? "SUPPRESSED" : 
                    (r.motionDetected ? "MOTION(t=" + r.threatLevel + ")" : "quiet");
                logger.info(String.format("  Q%d[%s]: %s active=%d confirmed=%d component=%d luma=%.0f",
                        q, MotionPipelineV2.QUADRANT_NAMES[q], status,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, r.meanLuma));
            }
            
            // SOTA: Auto day/night mode switch based on ambient light.
            // The BYD's camera ISP boosts ISO at night, pushing mean luma to ~75-85.
            // Daytime luma sits at ~115-130. The threshold of 95 cleanly splits the two.
            // Night mode relaxes edge/shadow thresholds to handle ISO noise and headlights.
            float avgLuma = 0;
            int lumaCount = 0;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].meanLuma > 0) {
                    avgLuma += results[q].meanLuma;
                    lumaCount++;
                }
            }
            if (lumaCount > 0) {
                avgLuma /= lumaCount;
                
                // Java-level auto-exposure: simple day/night switch for global params.
                // Threshold scaling is handled per-quadrant in C++ with relative multipliers.
                boolean shouldBeNight = avgLuma < 90.0f;
                boolean currentlyNight = isNightMode();
                
                if (shouldBeNight != currentlyNight && pipelineV2Config != null) {
                    // Restore base global params from user's preset
                    String preset = config != null ? config.getEnvironmentPreset() : "outdoor";
                    MotionPipelineV2.Config tempCfg = new MotionPipelineV2.Config();
                    tempCfg.applyEnvironmentPreset(preset);
                    
                    pipelineV2Config.brightnessShiftThreshold = tempCfg.brightnessShiftThreshold;
                    pipelineV2Config.brightnessSuppressionFrames = tempCfg.brightnessSuppressionFrames;
                    pipelineV2Config.shadowFilterMode = tempCfg.shadowFilterMode;
                    pipelineV2Config.chromaRatioTolerance = tempCfg.chromaRatioTolerance;
                    pipelineV2Config.shadowPixelFraction = tempCfg.shadowPixelFraction;
                    pipelineV2Config.oscillationThreshold = tempCfg.oscillationThreshold;
                    
                    if (shouldBeNight) {
                        pipelineV2Config.brightnessShiftThreshold = 0.35f;
                        pipelineV2Config.brightnessSuppressionFrames = 8;
                        pipelineV2Config.shadowFilterMode = 1;  // LIGHT
                        pipelineV2Config.chromaRatioTolerance = 0.25f;
                        pipelineV2Config.shadowPixelFraction = 0.7f;
                        pipelineV2Config.oscillationThreshold = 4;
                        setNightMode(true);
                        logger.info(String.format("Auto NIGHT mode (avgLuma=%.0f < 95)", avgLuma));
                    } else {
                        setNightMode(false);
                        logger.info(String.format("Auto NORMAL mode (avgLuma=%.0f >= 95)", avgLuma));
                    }
                    pipelineV2.applyConfig(pipelineV2Config);
                    
                    // SOTA: Refresh detection baseline on lighting transition.
                    // Dawn/dusk changes affect detector confidence scores and object
                    // appearance. Refresh all quadrants to keep baseline accurate.
                    // Cost: 4 inferences, happens 2-3 times per night.
                    // Runs on AI executor thread to avoid blocking motion pipeline.
                    if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                        logger.info("Queuing detection baseline refresh (lighting transition)...");
                        final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                        System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                        final int refreshEpoch = armEpoch;  // R3b Ext-2
                        aiExecutor.execute(() -> {
                            // FIX (A8/B3): snapshot detector at lambda entry.
                            final YoloDetector detectorSnap = yoloDetector;
                            if (detectorSnap == null || !aiEnabled) {
                                logger.info("Lighting-transition baseline refresh skipped (detector closed)");
                                return;
                            }
                            // ACC-ON guard: lambda is dispatched onto the
                            // single-thread aiExecutor and may sit behind
                            // an in-flight detect() for up to ~300ms. If
                            // ACC has turned ON in that window, drop the
                            // refresh — the next ACC-OFF session's frame
                            // 30 baseline seed will re-cover this case.
                            if (!active || armEpoch != refreshEpoch
                                    || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                logger.info("Lighting-transition baseline refresh skipped (surveillance inactive / ACC ON)");
                                return;
                            }
                            logger.info("Refreshing detection baseline (lighting transition)...");
                            int qW = THUMBNAIL_WIDTH / 2;
                            int qH = THUMBNAIL_HEIGHT / 2;
                            for (int qr = 0; qr < MotionPipelineV2.NUM_QUADRANTS; qr++) {
                                // Re-check PER QUADRANT (R3b Ext-2): this loop
                                // runs up to 4 sequential ~250ms inferences —
                                // an entry-only guard let a disarm (or a
                                // disarm→rearm) mid-loop write the remaining
                                // quadrants into a dead or NEW session's
                                // baseline, and the trigger call below fired
                                // with the engine logically down.
                                if (!active || armEpoch != refreshEpoch
                                        || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                    logger.info("Lighting-transition baseline refresh aborted mid-loop at Q" + qr);
                                    return;
                                }
                                try {
                                    byte[] quadCrop = cropFromMosaic(frameSnapshot, qr, qW, qH);
                                    if (quadCrop != null) {
                                        java.util.List<com.overdrive.app.ai.Detection> dets =
                                                detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                        // POST-INFERENCE epoch re-check (audit
                                        // R4 ExtB-2): the per-quadrant check
                                        // above ran BEFORE this ~250ms detect.
                                        if (!active || armEpoch != refreshEpoch) {
                                            logger.info("Lighting-transition baseline refresh aborted post-inference at Q" + qr);
                                            return;
                                        }
                                        detectionBaseline.refreshQuadrant(qr, dets, qW, qH);
                                        // A person YOLO sees here is one the motion
                                        // pipeline missed (static / zone-rejected) —
                                        // route it to the trigger. PERSON-only +
                                        // conf gate, so a parked car stays baseline.
                                        maybeTriggerFromBaselinePerson(qr, dets);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Baseline refresh failed for Q" + qr + ": " + e.getMessage());
                                }
                            }
                        });
                    }
                }
            }
            
            // Log suppressed quadrants for debug
            if (filterDebugEnabled) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        addFilterLogEntry(String.format("[%s] SUPPRESSED: %s (brightness shift, luma=%.0f)",
                                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                                MotionPipelineV2.QUADRANT_NAMES[q], results[q].meanLuma));
                    }
                }
            }
            
            // POST-SUPPRESSION BASELINE REFRESH (dispatch only).
            // When suppression ends and the scene stabilizes, run YOLO once to update
            // the baseline with what's currently visible. This prevents the "4 objects
            // in day, 3 visible at night" mismatch from causing false triggers.
            //
            // The suppression-state BOOKKEEPING (the suppressionWasActive latch and its
            // stabilization counter) deliberately does NOT live here any more — it runs
            // per-tick in processFrameV2, because the trigger path reads that latch and a
            // once-per-500-frames decay left it stuck for minutes. This block now only
            // consumes the baselineRefreshDue hand-off, so the YOLO inference keeps the
            // coarse cadence it was tuned for.
            if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    // Don't refresh during active recording (the subject is still
                    // there — we must not fold them into the baseline).
                    if (!baselineRefreshDue[q] || baselineRefreshQueued[q] || recording) continue;
                    baselineRefreshDue[q] = false;
                    baselineRefreshQueued[q] = true;

                    final int qToRefresh = q;
                    final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                    System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                    final int refreshEpoch = armEpoch;  // R3b Ext-2

                    aiExecutor.execute(() -> {
                        // FIX (A8/B3): snapshot detector at lambda entry.
                        final YoloDetector detectorSnap = yoloDetector;
                        if (detectorSnap == null || !aiEnabled) {
                            logger.debug("Post-suppression baseline refresh skipped (detector closed)");
                            return;
                        }
                        // ACC-ON guard — see baseline-seed lambda for
                        // rationale. Same dispatch-lag race; skip if
                        // surveillance was torn down before the
                        // executor reached this task.
                        if (!active || armEpoch != refreshEpoch
                                || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                            logger.debug("Post-suppression baseline refresh skipped (surveillance inactive / ACC ON)");
                            return;
                        }
                        try {
                            int qW = THUMBNAIL_WIDTH / 2;
                            int qH = THUMBNAIL_HEIGHT / 2;
                            byte[] quadCrop = cropFromMosaic(frameSnapshot, qToRefresh, qW, qH);
                            if (quadCrop != null) {
                                java.util.List<com.overdrive.app.ai.Detection> dets =
                                        detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                // POST-INFERENCE epoch re-check (audit R4
                                // ExtB-2): entry guard ran before the detect.
                                if (!active || armEpoch != refreshEpoch) {
                                    logger.debug("Post-suppression baseline refresh aborted post-inference Q" + qToRefresh);
                                    return;
                                }
                                detectionBaseline.refreshQuadrant(qToRefresh, dets, qW, qH);
                                // Person YOLO sees here = one the motion
                                // pipeline missed; route to trigger
                                // (PERSON-only + conf gate, parked car
                                // stays baseline-only).
                                maybeTriggerFromBaselinePerson(qToRefresh, dets);
                                logger.debug("Post-suppression baseline refresh Q" + qToRefresh +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[qToRefresh] + "]: " +
                                        (dets != null ? dets.size() : 0) + " detections");
                            }
                        } catch (Exception e) {
                            logger.warn("Post-suppression baseline refresh failed Q" + qToRefresh + ": " + e.getMessage());
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Run YOLO on a single quadrant (cropped from the mosaic).
     */
    private void runAiOnQuadrant(byte[] mosaicRgb, int quadrant) {
        // FIX (Bug B): respect user's class toggles. Empty classFilter = sentinel for
        // "all classes disabled" — skip YOLO entirely. Saves ~50-80ms per quadrant per
        // wake event and frees the TFLite interpreter.
        // Every early-return below records WHY. These were silent, which is what
        // made the 2026-07-19 "YOLO went quiet for 43 s" incident undiagnosable
        // from the log. Counters only — no control-flow change.
        if (!useObjectDetection) { noteAiSkip("useObjectDetection=false"); return; }
        if (!aiEnabled || (classFilter != null && classFilter.length == 0)) { noteAiSkip("aiEnabled=false/noClasses"); return; }
        // DISABLED-QUADRANT GATE (audit R13-5 / ExtE-5): a quadrant already
        // sitting in aiQuadrantQueue when the user disables it would still be
        // dispatched here — its detections could then re-seed the track that
        // setV2QuadrantEnabled(false) just dropped (the config-epoch check
        // catches toggles landing DURING the lambda, but a dispatch strictly
        // AFTER the toggle captures the fresh epoch and would pass). Never
        // dispatch a disabled quadrant.
        if (pipelineV2Config != null && quadrant >= 0 && quadrant < 4
                && !pipelineV2Config.quadrantEnabled[quadrant]) {
            noteAiSkip("quadrantDisabled");
            return;
        }
        if (yoloDetector == null) { noteAiSkip("detector=null"); return; }
        if (isAiRunning.get()) { noteAiSkip("laneBusy"); return; }

        long now = System.currentTimeMillis();
        // Internal backstop = the FASTEST legitimate cadence (AI_COOLDOWN_CLOSE_MS),
        // NOT AI_COOLDOWN_MS. The close-zone NEAR path (see the cooldown=
        // AI_COOLDOWN_CLOSE_MS call site) admits a run at 250-499ms since the last
        // inference; a 500ms floor here would reject it AFTER aiQuadrantQueuePoll()
        // already consumed + cleared the quadrant's request bit, silently
        // vaporizing that quadrant's AI pass (and, in the multi-quadrant case,
        // mis-ordering the first classification onto a secondary quadrant). The
        // other 5 callers self-gate at >= AI_COOLDOWN_MS (500) BEFORE calling, so
        // this lower floor is invisible to them; isAiRunning (checked above) still
        // prevents overlapping inference regardless of cadence.
        if ((now - lastAiTimeMs) < AI_COOLDOWN_CLOSE_MS) { noteAiSkip("cooldown"); return; }
        lastAiTimeMs = now;
        
        // Determine crop dimensions and data source.
        // If foveated cropper is available, extract a 640×640 window from the raw
        // 5120×960 strip centered on the motion centroid. This gives YOLO ~4× more
        // pixels per object compared to the 320×240 mosaic quadrant.
        final int qW;
        final int qH;
        final byte[] cropData;
        // Foveated-pixel → 320×240 block-grid affine, paired with cropData when
        // (and only when) cropData is a foveated 640×640 crop. Carried out of
        // the SAME atomic slot read as the rgb so bbox↔pixels stay coherent.
        // fovMapValid=false ⇒ mosaic path OR foveated-without-rect ⇒ consumer
        // keeps identity/fail-safe behavior (see line ~3413).
        final float fovMapAx, fovMapBx, fovMapAy, fovMapBy;
        final boolean fovMapValid;
        // Capture time of the foveated PIXELS (0 = mosaic/unknown) — audit
        // R4 ExtB-8: gates the tracker seed below so a box computed from old
        // pixels is never pasted onto the CURRENT mosaic frame after the
        // subject has moved a full box-width.
        final long fovCaptureNanos;

        MotionPipelineV2.QuadrantResult motionResult = pipelineV2 != null ? pipelineV2.getResults()[quadrant] : null;
        
        // For heartbeat runs, the person may be stationary (zero motion blocks).
        // Use the tracker's last known position as the centroid for foveated crop
        // instead of requiring active motion blocks from the V2 pipeline.
        boolean heartbeatHasTrackerPos = false;
        float trackerCentroidX = 0, trackerCentroidY = 0;
        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox != null && trackBox[6] > 0) {  // trackBox[6] = active flag
                // Convert tracker bbox (pixel coords) to block coords for foveated crop
                trackerCentroidX = (trackBox[0] + trackBox[2] / 2.0f) / 32.0f;
                trackerCentroidY = (trackBox[1] + trackBox[3] / 2.0f) / 32.0f;
                heartbeatHasTrackerPos = true;
            }
        } catch (Exception ignored) {}
        
        // Detect heartbeat runs early so we can skip the foveated GL hop
        // entirely on this code path. Heartbeats fire when the NCC tracker
        // wants to refresh its template — which only happens once the
        // tracked person has been stationary long enough for the score to
        // drift, so by definition the subject is NOT moving and a 320×240
        // mosaic crop carries enough detail. Doing the foveated 640×640
        // dance for it is pure cost: it forces a GL-thread hop while the
        // Adreno 610 is already saturated by the YOLO inference itself,
        // which produces the 296ms `acq` stall (and the 1500ms "Foveated
        // crop GL hop timed out" warning) we see in field logs during
        // active recording.
        boolean heartbeatRunEarly = false;
        try {
            heartbeatRunEarly = NativeMotion.trackerNeedsYoloHeartbeat(quadrant)
                    && NativeMotion.trackerHasActiveTrack(quadrant);
        } catch (Exception ignored) {}

        // CLOSE-SUBJECT WIDE-CROP GATE (detection FN fix). The foveated crop
        // zooms a 640×640 window onto the motion centroid — great for a distant
        // subject that would otherwise be a handful of pixels, but WRONG for a
        // subject already physically close: the zoom clips the body to a
        // partial, heavily-fisheye-warped blob (torso/leg only) and YOLO, which
        // is trained on whole-body aspect ratios, scores it ~0 (max_conf=0.000).
        // Field evidence: side/rear close walk-ups produced zero YOLO hits for a
        // whole session while the wider mosaic quadrant would have kept the full
        // body in frame. When this quadrant's PRE-YOLO motion tier is NEAR, skip
        // foveated and feed YOLO the wider 320×240 mosaic quadrant instead so the
        // whole subject stays visible. This does NOT weaken static-object
        // rejection: the DetectionBaseline suppression + ActorTracker static
        // gates run identically downstream regardless of crop source, so a parked
        // car / non-threat that never moved is still filtered out. Heartbeat runs
        // already prefer mosaic (handled below), so only the live-motion path
        // needs this. proximityForQuadrant Technique B is a cheap O(70) block
        // scan and needs no YOLO result, so it is valid even when YOLO has been
        // failing on this quadrant.
        boolean preferWideForClose = false;
        if (motionResult != null && motionResult.componentSize > 0) {
            DistanceEstimator.ProximityEstimate preYoloProx =
                    proximityForQuadrant(quadrant, motionResult);
            preferWideForClose = (preYoloProx != null
                    && preYoloProx.tier == DistanceEstimator.Tier.NEAR);
        }

        if (!heartbeatRunEarly
                && !preferWideForClose
                && foveatedCropper != null && foveatedCropper.isInitialized() && cameraTextureId >= 0
                && ((motionResult != null && motionResult.componentSize > 0) || heartbeatHasTrackerPos)) {
            // Foveated path (Option B mailbox).
            //
            // We never block the GL thread or post a Runnable to its handler
            // queue. Instead:
            //   1. Mark this quadrant as wanting a foveated crop on the next
            //      render frame (requestFoveatedCrop, non-blocking).
            //   2. Read the most recent crop from the slot. If present and
            //      fresh (< 500ms old), use it. If absent or stale, fall
            //      back to mosaic for THIS tick — the next tick's slot will
            //      have been filled by the render loop in the interim.
            //
            // First-tick latency: the very first foveated tick after motion
            // starts will see an empty slot and fall back to mosaic. By the
            // second tick (~100ms later via V2's MOTION_PROCESS_INTERVAL_MS)
            // the slot is populated. Steady-state behavior is foveated;
            // start-of-event behavior is mosaic-then-foveated. Acceptable.
            float centroidX = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidX : trackerCentroidX;
            float centroidY = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidY : trackerCentroidY;
            requestFoveatedCrop(quadrant, centroidX, centroidY);

            // Single atomic slot read: rgb + the affine that maps THIS crop's
            // 640-pixel bboxes back into the 320×240 block grid must come from
            // the SAME publication.
            FoveatedSlot fovSlot = pollFoveatedSlotFresh(quadrant);
            byte[] foveatedRgb = (fovSlot != null) ? fovSlot.rgb : null;
            if (foveatedRgb != null) {
                qW = FoveatedCropper.CROP_SIZE;
                qH = FoveatedCropper.CROP_SIZE;
                // Slot deep-copies on publish; the bytes we got here are
                // ours alone (no further copy needed for thread safety).
                cropData = foveatedRgb;
                // Carry the window's affine. If the slot lacks a valid rect
                // (older publish / race), fovMapValid stays false and the
                // motion-overlap filter FAILS SAFE (keeps the detection).
                fovMapAx = fovSlot.mapAx;
                fovMapBx = fovSlot.mapBx;
                fovMapAy = fovSlot.mapAy;
                fovMapBy = fovSlot.mapBy;
                fovMapValid = fovSlot.hasAffine;
                fovCaptureNanos = fovSlot.captureNanos;  // R4 ExtB-8
            } else {
                // Slot empty or stale — fall back to mosaic for this tick.
                qW = THUMBNAIL_WIDTH / 2;
                qH = THUMBNAIL_HEIGHT / 2;
                byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
                cropData = new byte[mosaicShared.length];
                System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
                // Mosaic crop: identity mapping, handled by the scaleX=1.0
                // branch downstream. No foveated affine.
                fovMapAx = 0f; fovMapBx = 0f; fovMapAy = 0f; fovMapBy = 0f;
                fovMapValid = false;
                fovCaptureNanos = 0L;
            }
        } else {
            // Mosaic 320×240 path. Reached when: no foveated cropper (legacy),
            // a heartbeat run (stationary subject — mosaic detail suffices), OR
            // preferWideForClose (a NEAR subject whose full body must stay in
            // frame — see the wide-crop gate above). Must copy: cropFromMosaic
            // returns a thread-local shared buffer.
            qW = THUMBNAIL_WIDTH / 2;
            qH = THUMBNAIL_HEIGHT / 2;
            byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
            cropData = new byte[mosaicShared.length];
            System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
            // Mosaic crop: identity mapping downstream. No foveated affine.
            fovMapAx = 0f; fovMapBx = 0f; fovMapAy = 0f; fovMapBy = 0f;
            fovMapValid = false;
            fovCaptureNanos = 0L;
            // Retract any request an EARLIER tick left pending for this quadrant.
            // A request set while we were still taking the foveated path stays
            // latched in foveatedRequested[], so the GL thread would render a
            // 640×640 crop and deep-copy 1.2 MB into a slot this mosaic path
            // never polls — pure waste on an already-saturated Adreno 610, and
            // this branch is reached exactly when the device is busiest (a NEAR
            // subject, or a heartbeat during active recording). Harmless if the
            // request was already serviced; the flag is idempotent.
            synchronized (foveatedRequestLock) {
                if (quadrant < FOVEATED_NUM_QUADRANTS) {
                    foveatedRequested[quadrant] = false;
                }
            }
        }
        
        if (cropData == null) { noteAiSkip("cropNull"); return; }

        isAiRunning.set(true);
        // Latch the MONOTONIC time at which this inference claimed the AI lane,
        // so the watchdog (see maybeRepairStuckAiLane) can tell a legitimately
        // long-running detect() from a permanently leaked flag. Deliberately
        // elapsedRealtime, not currentTimeMillis: this value is only ever used
        // as one end of an interval, and a wall-clock correction (GPS/NTP after
        // cold boot) would otherwise either trip the watchdog on a healthy lane
        // or disable it for the length of the correction.
        aiRunStartedMs = android.os.SystemClock.elapsedRealtime();
        aiLaneStamp++;
        final int qIdx = quadrant;

        // FIX: Snapshot block confidences on the main thread BEFORE dispatching to aiExecutor.
        // The live pipelineV2.getResults() array is mutated by the JNI backend on every frame.
        // By the time the aiExecutor thread runs (150-300ms later), the main loop has processed
        // 2-3 new frames. If the person briefly stopped, confirmedBlocks will be 0 on the new
        // frame, and a valid YOLO detection gets thrown away because it doesn't overlap with
        // the "current" empty motion mask. Deep-copy the confidence array now.
        // DISPATCH-ANCHORED foveated pixel age (audit R7 ExtC-5). The seed
        // gate below pastes the affine-mapped foveated box onto
        // mosaicQuadCrop — and BOTH freeze at dispatch (the mosaic crop is
        // deep-copied here; the box derives from pixels captured at
        // fovCaptureNanos). Queue latency and the ~250-300ms inference delay
        // them EQUALLY and add zero pairing skew, so the age that matters is
        // dispatch − capture (ring lag + slot dwell, ~150-450ms). The
        // original gate measured the age at SEED time (post-inference),
        // which stacked the inference on top: minimum measurable age ~400ms
        // against a 250ms budget — structurally unsatisfiable, silently
        // disabling ALL foveated NCC seeding instead of freshness-gating it.
        // -1 = mosaic/unknown (gate then requires !usedFoveated).
        final long fovAgeAtDispatchNanos =
                fovCaptureNanos > 0 ? System.nanoTime() - fovCaptureNanos : -1L;

        final float[] blockConfSnapshot = new float[MotionPipelineV2.TOTAL_BLOCKS];
        final int snapshotConfirmedBlocks;
        if (pipelineV2 != null) {
            MotionPipelineV2.QuadrantResult snapResult = pipelineV2.getResults()[qIdx];
            System.arraycopy(snapResult.blockConfidence, 0, blockConfSnapshot, 0, MotionPipelineV2.TOTAL_BLOCKS);
            snapshotConfirmedBlocks = snapResult.confirmedBlocks;
        } else {
            snapshotConfirmedBlocks = 0;
        }
        
        final boolean usedFoveated = (qW == FoveatedCropper.CROP_SIZE);
        final long detectionObservationElapsedMs =
                android.os.SystemClock.elapsedRealtime()
                        - ((usedFoveated && fovAgeAtDispatchNanos > 0)
                                ? fovAgeAtDispatchNanos / 1_000_000L : 0L);
        
        // Capture whether this YOLO run is a heartbeat verification BEFORE the lambda.
        // Reuses the early decision computed above the foveated branch — the C++
        // tracker's needsYoloVerification flag is mutated by trackerUpdate() on
        // every frame, so reading it again here would race with the live state
        // and could disagree with the foveated-skip decision.
        final boolean isHeartbeatRun = heartbeatRunEarly;

        // FIX (B1/H-a): capture the recording generation NOW. The lambda below
        // will check it on completion and skip cross-recording writes if the
        // generation has advanced (i.e. stopRecording fired in the meantime).
        final long generationAtSchedule = recordingGeneration.get();
        
        // Capture mosaic quadrant crop for the texture tracker (always 320×240).
        // The tracker needs the mosaic-scale image regardless of whether YOLO used foveated.
        final byte[] mosaicQuadCrop;
        {
            int mqW = THUMBNAIL_WIDTH / 2;
            int mqH = THUMBNAIL_HEIGHT / 2;
            byte[] tmp = cropFromMosaic(mosaicRgb, quadrant, mqW, mqH);
            if (tmp != null) {
                mosaicQuadCrop = new byte[tmp.length];
                System.arraycopy(tmp, 0, mosaicQuadCrop, 0, tmp.length);
            } else {
                mosaicQuadCrop = null;
            }
        }
        
        // Stamp identifying THIS occupancy of the AI lane. If the watchdog ever
        // force-releases the lane while this task is still running (a genuinely
        // stuck/overlong inference), a NEW task can legitimately claim the lane
        // and bump the stamp. This task must then NOT clear isAiRunning in its
        // finally-block — doing so would unlatch the *new* occupant and allow two
        // detect() calls to be considered concurrent. (They would still serialize
        // on YoloDetector's interpLock, so this is a correctness/ordering guard,
        // not a native-crash guard.)
        final long laneStamp = aiLaneStamp;
        // Arm-session epoch at BUILD time (audit R4 H2a/ExtB-2): this lambda
        // can sit ~250-300ms behind an in-flight detect() on the single-lane
        // executor; a disarm→rearm bounce in that window used to pass the
        // active/ACC guard below (new session is armed too) and write
        // stale-session detections into the fresh session's baseline/tracker
        // state. Same pattern as the seed/refresh lambdas.
        final int taskEpoch = armEpoch;
        // CLASS-CONFIG SNAPSHOT + EPOCH (audit R11-8 / ExtD-9). classFilter
        // is a plain (non-volatile) field the lambda used to read TWICE
        // (detect-param derivation, per-detection allowlist) — a toggle
        // landing between the two reads gave the run an incoherent config,
        // and the all-off sentinel (empty array) read mid-flight fell into
        // the "no filter → default classes" else-branch at the allowlist,
        // publishing person/vehicle detections under a config the user had
        // just fully disabled. One snapshot at dispatch keeps the run
        // internally coherent; the epoch (re-checked post-detect alongside
        // taskEpoch) drops the publication entirely when config changed
        // mid-flight.
        // Single volatile read — the (filter, epoch) pair is coherent by
        // construction (audit R13-5 / ExtE-5).
        final ClassConfig ccAtDispatch = classConfig;
        final int[] classFilterAtDispatch = ccAtDispatch.filter;
        final int cfgEpochAtDispatch = ccAtDispatch.epoch;
        final Runnable aiTask = () -> {
            try {
                // FIX (A8/B3): Snapshot the detector reference at lambda entry.
                // The line-1450 guard runs on the calling thread BEFORE this
                // lambda is scheduled. Between scheduling and execution, the UI
                // thread can call setObjectFilters() which closes the
                // interpreter and nulls yoloDetector. Without this snapshot,
                // the .detect() call below would NPE on a null field — or
                // worse, race against close() and crash the native interpreter.
                final YoloDetector detectorSnap = yoloDetector;
                if (detectorSnap == null || !aiEnabled) {
                    noteAiSkip("lambda:detectorClosed");
                    releaseAiLane(laneStamp);
                    return;
                }
                // ACC-ON guard. processFrameV2's caller (processFrame) ran the
                // active+isAccOn() gate, but the aiExecutor lambda can be
                // scheduled while ACC was still OFF and pulled off the queue
                // up to ~250-300ms later (one in-flight detect() ahead of us).
                // If ACC turned ON in that window — typical for the
                // ACC-OFF→ON transition with a motion event in flight — we
                // must NOT run inference: it (a) burns CPU during a window
                // where surveillance is logically disabled, (b) writes
                // lastYoloPublication / actor state for a session that's
                // about to be torn down, polluting the next session's first
                // frames. Drop the detect() and the downstream writes.
                if (!active || armEpoch != taskEpoch
                        || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                    noteAiSkip("lambda:inactiveOrAccOn");
                    releaseAiLane(laneStamp);
                    return;
                }

                // ALL-OFF SENTINEL (audit R11-8 / ExtD-9): an empty snapshot
                // means the user disabled every class at/before dispatch.
                // Without this bail the empty array fails the `length > 0`
                // test below and the derivation LEAVES the defaults on —
                // exactly the "empty filter = no filtering" fallthrough.
                if (classFilterAtDispatch != null && classFilterAtDispatch.length == 0) {
                    noteAiSkip("lambda:allClassesOff");
                    releaseAiLane(laneStamp);
                    return;
                }

                boolean detectPerson = true, detectCar = true, detectBike = true, detectAnimal = false;
                if (classFilterAtDispatch != null && classFilterAtDispatch.length > 0) {
                    detectPerson = false; detectCar = false; detectBike = false; detectAnimal = false;
                    for (int cls : classFilterAtDispatch) {
                        if (cls == 0) detectPerson = true;
                        if (cls == 2 || cls == 5 || cls == 7) detectCar = true;
                        if (cls == 1 || cls == 3) detectBike = true;
                        if (cls >= 14 && cls <= 23) detectAnimal = true;  // COCO animals
                    }
                }

                // A3: SIDE-CAM FISHEYE DEWARP of the DETECTOR INPUT ONLY.
                // Full-tile mosaic crops only (a foveated window is not
                // lens-centered, so the tile-radial model would be wrong
                // there — see FisheyeDewarp class doc). The bytes every
                // OTHER consumer sees (cropData → ThumbnailBuffer, tracker
                // seeds, hero JPEG) are untouched; detection boxes are mapped
                // back to cropData's warped space immediately below, so all
                // downstream coordinate contracts (I5) are byte-identical.
                // dewarpForDetector returns null when not applicable (front/
                // rear cams, bad input, any error) → original crop, identity
                // boxes — fail-open (I1), this stage can only ADD signal.
                final byte[] dewarpedInput = usedFoveated
                        ? null
                        : FisheyeDewarp.dewarpForDetector(cropData, qW, qH, qIdx);
                final boolean dewarpApplied = (dewarpedInput != null);

                java.util.List<com.overdrive.app.ai.Detection> detections = detectorSnap.detect(
                        dewarpApplied ? dewarpedInput : cropData,
                        qW, qH, aiConfidence, detectPerson, detectCar, detectAnimal, detectBike, minObjectSize);
                // Inference actually ran (an empty result still counts — it proves
                // the lane is alive, which is exactly what the field log could not
                // distinguish from "never dispatched").
                aiDetectCompletedCount.incrementAndGet();

                // POST-INFERENCE epoch re-check (audit R4 ExtB-2): the entry
                // guard ran BEFORE the ~250ms detect(); a disarm→rearm bounce
                // completing inside the inference would otherwise land these
                // detections in the NEW session's just-reset baseline and
                // tracker state. Everything below is a session-state write.
                // Also drops the run when the CLASS/QUADRANT CONFIG changed
                // mid-flight (audit R11-8 / ExtD-9): these detections were
                // computed under the pre-toggle filter, and letting them
                // publish would re-seed just-dropped tracks / re-stamp person
                // recency for a disabled class. One lambda's worth of loss;
                // the next run uses the new config.
                if (!active || armEpoch != taskEpoch
                        || classConfig.epoch != cfgEpochAtDispatch) {
                    noteAiSkip("lambda:staleEpochPostDetect");
                    releaseAiLane(laneStamp);
                    return;
                }

                // Map boxes from dewarped-input space back to cropData's warped
                // space (same forward transform the pixels used; corners + edge
                // midpoints). After this line every consumer sees the exact
                // coordinate space it always has. Fail-open on error inside.
                if (dewarpApplied && detections != null && !detections.isEmpty()) {
                    detections = FisheyeDewarp.mapDetectionsToSource(detections, qW, qH, qIdx);
                }

                // Commit all post-inference state atomically with event
                // start/stop. Inference remains outside this lock; only the
                // comparatively short filtering/actor/tracker publication
                // phase is serialized. Recheck every ownership epoch after
                // acquiring the lock so a stop/start or live config change
                // cannot land between a check and its writes.
                synchronized (recordingLifecycleLock) {
                    if (!active || armEpoch != taskEpoch
                            || classConfig.epoch != cfgEpochAtDispatch
                            || recordingGeneration.get() != generationAtSchedule) {
                        noteAiSkip("lambda:staleEpochAtCommit");
                        return;
                    }

                // RAW-DETECTION EVIDENCE for the discard predicate. Latched BEFORE any
                // filtering, because the point is "did YOLO resolve classes on this
                // scene at all" — not "did anything survive the filters" (that is what
                // the Actor latches already record). Generation-gated so a lambda whose
                // recording already ended cannot mark the NEXT event as YOLO-seen.
                // See eventYoloSawRawDetections.
                if (detections != null && !detections.isEmpty()
                        && recordingGeneration.get() == generationAtSchedule) {
                    lastRawDetectionElapsedMs.set(qIdx, detectionObservationElapsedMs);
                    eventYoloSawRawDetections = true;
                }

                // Track how many motion-filtered detections we found (accessible outside the block
                // for the teardown gate that kills zombie tracks when YOLO returns empty)
                int motionFilteredCount = 0;

                if (detections != null && !detections.isEmpty()) {
                    // Filter detections: only keep objects that overlap with active motion blocks.
                    // A static parked car detected by YOLO should be ignored if no motion blocks
                    // overlap with it. Only the moving person (whose bounding box overlaps with
                    // active motion blocks) should be reported to the timeline.
                    // FIX: Use the snapshot taken on the main thread, NOT the live pipeline results.
                    // The live results have been mutated by 2-3 frames by now.
                    
                    java.util.List<com.overdrive.app.ai.Detection> motionFiltered = new java.util.ArrayList<>();
                    for (com.overdrive.app.ai.Detection det : detections) {
                        int classId = det.getClassId();
                        
                        // Respect user's class filter settings.
                        // Only keep detections for classes the user has enabled.
                        // Reads the DISPATCH-TIME snapshot (audit R11-8 /
                        // ExtD-9), not the live field: keeps this allowlist
                        // coherent with the detect-param derivation above, and
                        // the empty (all-off) sentinel can no longer fall into
                        // the default-classes else-branch (an empty snapshot
                        // bailed at lambda entry; a mid-flight all-off toggle
                        // is dropped by the post-detect config-epoch check).
                        if (classFilterAtDispatch != null && classFilterAtDispatch.length > 0) {
                            boolean classAllowed = false;
                            for (int allowedCls : classFilterAtDispatch) {
                                if (classId == allowedCls) {
                                    classAllowed = true;
                                    break;
                                }
                            }
                            if (!classAllowed) continue;
                        } else {
                            // No filter set — only allow known relevant classes
                            if (classId != 0 && classId != 1 && classId != 2 && 
                                classId != 3 && classId != 5 && classId != 7) continue;
                        }
                        
                        // Check if detection overlaps with any confirmed motion blocks
                        // using the SNAPSHOT taken on the main thread.
                        // When using foveated crop (640×640), detection coords are in a different
                        // coordinate space than the block grid (320×240 with 32px blocks).
                        // Scale detection coords to match the block grid.
                        //
                        // FIX: During a heartbeat run, BYPASS the spatial filter entirely.
                        // The whole point of the heartbeat is to verify a STATIONARY person
                        // who has zero motion blocks. If we require motion-block overlap,
                        // the heartbeat will always fail for stationary objects, the teardown
                        // gate will kill the track, and the recording will stop even though
                        // the person is still standing right there.
                        // Spatial filter: check if detection overlaps with active motion blocks.
                        // Heartbeat bypass: persons (class 0) skip the spatial check because
                        // a stationary person has zero motion blocks but is a real threat.
                        // Vehicles during heartbeat still require motion blocks — a parked car
                        // is never a threat and will hold the recording open forever otherwise.
                        boolean passesFilter = false;
                        
                        if (isHeartbeatRun && classId == 0) {
                            // Heartbeat + person: bypass spatial filter
                            passesFilter = true;
                        } else if (usedFoveated && !fovMapValid) {
                            // FAIL SAFE: foveated crop but no valid window affine
                            // travelled with the pixels (older publish / ring
                            // race). The OLD code applied a scale-only map that
                            // ignored the crop window ORIGIN, throwing the bbox
                            // ~100px sideways and DROPPING the person (actors:[]).
                            // A false-keep is a recording; a false-drop is a
                            // MISSED PERSON. Keep the detection.
                            passesFilter = true;
                        } else if (usedFoveated && (fovAgeAtDispatchNanos < 0
                                || fovAgeAtDispatchNanos > FOVEATED_SEED_MAX_AGE_NANOS)) {
                            // CAPTURE-TIME vs MASK-TIME COHERENCE (audit R11-7 /
                            // ExtD-8). The crop's pixels — and therefore these
                            // bbox positions — were captured at fovCaptureNanos
                            // (GL ring lag + slot dwell, up to the 500ms slot
                            // bound), while blockConfSnapshot was copied at
                            // DISPATCH time. The R8-3 age gate protects only the
                            // NCC seed; the spatial filter still compared a
                            // fast subject's OLD position against blocks lit at
                            // its NEW position, dropping the detection (no
                            // Actor, no eventEverSawPerson latch for that run).
                            // When the crop is older than the same 250ms budget
                            // the seed gate uses, the old-position overlap test
                            // is unreliable evidence of stillness — treat it
                            // like the fovMapValid fail-safe above and KEEP the
                            // detection (a false-keep is policed downstream by
                            // baseline/ActorTracker static gates; a false-drop
                            // is a missed subject). Fresh crops (≤250ms — the
                            // common 150-250ms band) still get the full filter.
                            passesFilter = true;
                        } else if (snapshotConfirmedBlocks > 0) {
                            // Normal path: require overlap with active motion blocks.
                            //
                            // Map the detection bbox from its NATIVE crop space
                            // into the 320×240 block grid.
                            //  - Foveated (usedFoveated && fovMapValid): use the
                            //    affine that came WITH these exact pixels. It
                            //    folds in scale + crop-window ORIGIN + per-role
                            //    flip + APA inset, so a person at the window edge
                            //    lands on the right blocks (the bug this fixes).
                            //    A mirrored role gives a negative X/Y scale, so
                            //    normalise corners with min/max after mapping.
                            //  - Mosaic (identity): byte-identical to the old
                            //    scaleX = scaleY = 1.0 behavior.
                            float ax, bxc, ay, byc;
                            if (usedFoveated) {
                                ax = fovMapAx; bxc = fovMapBx;
                                ay = fovMapAy; byc = fovMapBy;
                            } else {
                                ax = 1.0f; bxc = 0.0f;
                                ay = 1.0f; byc = 0.0f;
                            }
                            float fx0 = det.getX();
                            float fy0 = det.getY();
                            float fx1 = det.getX() + det.getW();
                            float fy1 = det.getY() + det.getH();
                            float gx0 = ax * fx0 + bxc;
                            float gx1 = ax * fx1 + bxc;
                            float gy0 = ay * fy0 + byc;
                            float gy1 = ay * fy1 + byc;
                            int detLeft   = (int) Math.min(gx0, gx1);
                            int detRight  = (int) Math.max(gx0, gx1);
                            int detTop    = (int) Math.min(gy0, gy1);
                            int detBottom = (int) Math.max(gy0, gy1);

                            final int blockPx = MotionPipelineV2.BLOCK_SIZE;
                            if (detRight <= detLeft || detBottom <= detTop) {
                                // Degenerate box after mapping (zero/negative area).
                                // Nothing to test, so nothing is proven — keep it.
                                passesFilter = true;
                            } else if (detBottom > MotionPipelineV2.GRID_ROWS * blockPx) {
                                // UNMAPPED BOTTOM BAND. The grid is GRID_ROWS(7) ×
                                // BLOCK_SIZE(32) = 224 px tall but a quadrant is 240 px,
                                // so the bottom 16 px — the strip CLOSEST to the car —
                                // has no blocks and can NEVER report motion. Any bbox
                                // reaching into that band therefore cannot be proven
                                // still, and rejecting it deletes a detection for lack
                                // of evidence that was never collectable.
                                //
                                // This mirrors the identical guard in
                                // ThumbnailBuffer.bboxOverlapsMotion. It matters MORE
                                // here: there the penalty is a cosmetic scenery flag,
                                // here the detection is dropped outright — no Actor, no
                                // timeline entry, no hero, and eventEverSawPerson never
                                // latches, so the empty-motion discard can delete the
                                // clip. Reached both on the mosaic path (a short box
                                // sitting on the quadrant floor) and on the foveated
                                // path (the crop window clamped to the quadrant bottom).
                                passesFilter = true;
                            } else {
                                // ONE-BLOCK DILATION (FN fix). The arming condition is
                                // quadrant-GLOBAL (snapshotConfirmedBlocks > 0) but the
                                // test is per-bbox, so a subject who has just STOPPED is
                                // dropped whenever anything else in the same quadrant is
                                // still moving — its own blocks have decayed while a
                                // waving bush 200 px away keeps the filter armed. Three
                                // mechanisms make strict overlap too tight:
                                //   - The grid is coarse: one block is 32 px of a 320-px
                                //     quadrant, so a half-block mapping error is 5% of
                                //     the frame.
                                //   - The bbox arrives from a different coordinate space
                                //     (640×640 foveated) via an affine, and the int
                                //     truncation of the four corners is up to 1 px each
                                //     BEFORE the 0.25 scale is undone.
                                //   - A subject that stopped between the motion tick
                                //     (~8.7 Hz) and the YOLO tick (~2-4 Hz) has its
                                //     confirmed blocks at its PREVIOUS position, one
                                //     block back along its path.
                                // Accept a confirmed block within one block of the bbox.
                                //
                                // FP exposure stays bounded and does NOT open a new class
                                // of leak: a static object still needs confirmed motion
                                // within 32 px of its own box, so a parked car across the
                                // quadrant is rejected exactly as before. A parked car
                                // that a person walks CLOSE to already passed the strict
                                // test (their boxes overlap the same blocks), and the
                                // DetectionBaseline suppression plus the ActorTracker
                                // static gates — which are what actually reject scenery —
                                // run identically downstream either way.
                                final int dLeft   = detLeft   - blockPx;
                                final int dRight  = detRight  + blockPx;
                                final int dTop    = detTop    - blockPx;
                                final int dBottom = detBottom + blockPx;
                                for (int bi = 0; bi < MotionPipelineV2.TOTAL_BLOCKS; bi++) {
                                    if (blockConfSnapshot[bi] < MotionPipelineV2.BLOCK_MOTION_MIN_CONF) continue;

                                    int bx = (bi % MotionPipelineV2.GRID_COLS) * blockPx;
                                    int by = (bi / MotionPipelineV2.GRID_COLS) * blockPx;
                                    int bRight = bx + blockPx;
                                    int bBottom = by + blockPx;

                                    if (dLeft < bRight && dRight > bx && dTop < bBottom && dBottom > by) {
                                        passesFilter = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            // No motion data available — keep all detections (fallback)
                            passesFilter = true;
                        }
                        
                        if (passesFilter) {
                            motionFiltered.add(det);
                        }
                    }
                    
                    int relevantCount = motionFiltered.size();
                    motionFilteredCount = relevantCount;
                    boolean triggerEvidenceFound = false;
                    
                    if (relevantCount > 0) {
                        // COORDINATE SPACE for DetectionBaseline (audit R3b
                        // Ext-7): the baseline is seeded/refreshed in QUADRANT
                        // space (320×240 mosaic tiles, see seedFromDetections/
                        // refreshQuadrant call sites), but this block used to
                        // hand it crop-NATIVE boxes normalized by 640×640 when
                        // foveated — a MOVING-WINDOW space numerically
                        // incomparable with the quadrant-space entries (~2×
                        // normalized size + window-origin shift). Effects:
                        // defeated static-object suppression during foveated
                        // runs (FP recordings + baseline churn) and mixed-space
                        // spatial vetoes. Map through the SAME window affine
                        // the motion filter / CQT / tracker seeding use, and
                        // normalize by mosaic dims ALWAYS. Without a valid
                        // affine, skip baseline interactions entirely (fail
                        // open — never suppress on unmappable evidence;
                        // isInBaseline simply isn't consulted).
                        final int qWNorm = THUMBNAIL_WIDTH / 2;
                        final int qHNorm = THUMBNAIL_HEIGHT / 2;
                        java.util.List<com.overdrive.app.ai.Detection> baselineSpaceDets;
                        if (!usedFoveated) {
                            baselineSpaceDets = motionFiltered;
                        } else if (fovMapValid) {
                            baselineSpaceDets = new java.util.ArrayList<>(motionFiltered.size());
                            for (com.overdrive.app.ai.Detection det : motionFiltered) {
                                float gx0 = fovMapAx * det.getX() + fovMapBx;
                                float gx1 = fovMapAx * (det.getX() + det.getW()) + fovMapBx;
                                float gy0 = fovMapAy * det.getY() + fovMapBy;
                                float gy1 = fovMapAy * (det.getY() + det.getH()) + fovMapBy;
                                // Mirrored roles give a negative scale — normalise
                                // corners after mapping (same as the CQT copy).
                                baselineSpaceDets.add(new com.overdrive.app.ai.Detection(
                                        det.getClassId(), det.getConfidence(),
                                        (int) Math.min(gx0, gx1), (int) Math.min(gy0, gy1),
                                        (int) Math.abs(gx1 - gx0), (int) Math.abs(gy1 - gy0)));
                            }
                        } else {
                            baselineSpaceDets = null;  // unmappable — skip baseline I/O
                        }

                        // SOTA: Record person detections for spatial veto baseline tracking.
                        // This must happen BEFORE baseline filtering so the person positions
                        // are available for the spatial veto check during event-end update.
                        if (baselineSpaceDets != null) {
                            for (com.overdrive.app.ai.Detection det : baselineSpaceDets) {
                                if (det.getClassId() == 0) {  // person
                                    detectionBaseline.recordPersonDetection(qIdx, det, qWNorm, qHNorm);
                                }
                            }
                        }
                        
                        // SOTA: Filter detections against baseline — suppress known static objects.
                        // Only NEW or MOVED objects pass through. This eliminates false recordings
                        // from shadows/headlights that trigger motion near parked cars or trash cans.
                        // Skip baseline filtering for person detections (class 0) — a person is
                        // never a legitimate static background object for a sentry system.
                        // The QUERY uses the quadrant-space copy; the list handed
                        // downstream keeps the NATIVE-space det (bbox↔pixels
                        // coherence for ActorTracker/ThumbnailBuffer).
                        java.util.List<com.overdrive.app.ai.Detection> baselineFiltered = new java.util.ArrayList<>();
                        java.util.List<com.overdrive.app.ai.Detection> triggerSpaceDets =
                                new java.util.ArrayList<>();
                        int baselineSuppressed = 0;
                        for (int di = 0; di < motionFiltered.size(); di++) {
                            com.overdrive.app.ai.Detection det = motionFiltered.get(di);
                            if (det.getClassId() == 0) {
                                // Person — always pass through, never check baseline
                                baselineFiltered.add(det);
                                triggerSpaceDets.add(baselineSpaceDets != null
                                        ? baselineSpaceDets.get(di) : det);
                                triggerEvidenceFound = true;
                                // SHADOW (containment validation, log-only): a person
                                // box heavily overlapped by a confirmed baseline entry
                                // is the "parked-car part misread as person" FP
                                // signature the multi-batch audit is chasing — class 0
                                // bypasses the baseline by design, so this is the only
                                // place the geometry can be captured. Suppresses
                                // nothing; fail-silent (instrumentation must never
                                // break the trigger path).
                                if (baselineSpaceDets != null) {
                                    try {
                                        String diag = detectionBaseline.shadowContainmentDiag(
                                                baselineSpaceDets.get(di), qIdx, qWNorm, qHNorm);
                                        if (diag != null) {
                                            logger.info("Baseline shadow Q" + qIdx
                                                    + " (person-bypass): " + diag);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            } else if (baselineSpaceDets != null
                                    && detectionBaseline.isInBaseline(
                                            baselineSpaceDets.get(di), qIdx, qWNorm, qHNorm)) {
                                // Known static object — suppress
                                baselineSuppressed++;
                            } else {
                                // New or moved non-person object — pass through
                                baselineFiltered.add(det);
                                // A non-person may authorize recording only
                                // when baseline comparison was actually possible.
                                if (baselineSpaceDets != null) {
                                    triggerSpaceDets.add(baselineSpaceDets.get(di));
                                    triggerEvidenceFound = true;
                                    // SHADOW (containment validation, log-only): this
                                    // det just PASSED the baseline and can authorize a
                                    // recording. If it is largely CONTAINED in a
                                    // confirmed entry (split box of a parked car: IoU
                                    // < 0.7 and the foot-point/size checks fail, but
                                    // the fragment lies INSIDE the stored full-car
                                    // box), the candidate rule "contain >= 0.80 +
                                    // same canonical class => suppress" would have
                                    // caught it. Log the geometry so field data can
                                    // prove or kill that hypothesis before it gates
                                    // anything. Fail-silent by design.
                                    try {
                                        String diag = detectionBaseline.shadowContainmentDiag(
                                                baselineSpaceDets.get(di), qIdx, qWNorm, qHNorm);
                                        if (diag != null) {
                                            logger.info("Baseline shadow Q" + qIdx
                                                    + " (nonperson-pass): " + diag);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        
                        if (baselineSuppressed > 0) {
                            logger.info("Baseline filter Q" + qIdx + ": " + baselineSuppressed + 
                                    " static objects suppressed, " + baselineFiltered.size() + " new/moved pass");
                        }
                        
                        // Store last detections + coord-space frame height for the
                        // event-end baseline update AND DistanceEstimator's bbox-height
                        // inference. Single atomic publication so the reader can't
                        // see new detections paired with stale frame height (or
                        // vice-versa). QUADRANT space whenever mappable (audit R3b
                        // Ext-7): the event-end updateFromEventEnd folds these into
                        // the quadrant-space baseline, so publishing the foveated
                        // window space here corrupted entries at every event end.
                        // Only the rare foveated-without-affine race publishes
                        // native space (pair stays internally consistent).
                        lastYoloPublication.set(qIdx, new YoloPublication(
                                new java.util.ArrayList<>(
                                        baselineSpaceDets != null ? baselineSpaceDets : motionFiltered),
                                triggerSpaceDets,
                                baselineSpaceDets != null ? qHNorm : qH,
                                usedFoveated));
                        lastEventQuadrant = qIdx;
                        
                        // THREAT-LEVEL DECISION MATRIX (AI background subtraction gate):
                        //
                        // THREAT_LOW:    Require YOLO to find a non-baseline object. If all
                        //                detections are known static objects → suppress entirely.
                        //
                        // THREAT_MEDIUM: Require YOLO to find a non-baseline object. This prevents
                        //                lighting artifacts (streetlights, porch lights, slow
                        //                headlight sweeps below brightness threshold) from triggering
                        //                recordings. These create persistent edge differences that
                        //                Stage 5 classifies as "approaching" because the centroid
                        //                drifts slightly as shadows shift.
                        //
                        // THREAT_HIGH:   Auto-record ONLY when the loiter is TRUSTED — i.e. the
                        //                motion is coherently translating (native flow coherence)
                        //                or an in-zone person tracker holds it. An UNTRUSTED HIGH
                        //                (a waving flag / sweeping shadow whose stationary centroid
                        //                merely looks like loitering) is gated exactly like MEDIUM:
                        //                it must yield a non-baseline YOLO object, otherwise suppress.
                        //                A flag never produces a person/car box, so it stops here.
                        //
                        // Safety: The motion pipeline already queues YOLO at the START of motion
                        // (early AI init, line ~752). By the time MEDIUM's 3-second sustained
                        // timer expires, YOLO has had 2.7+ seconds to run. If baselineFiltered
                        // is empty, it means YOLO ran and found nothing real — the "motion" is
                        // a lighting artifact. cachedHighIsTrusted is latched on the main thread
                        // at motion-start/per-frame; read here on the aiExecutor thread (volatile).
                        int currentThreat = pipelineV2 != null ? pipelineV2.getMaxThreatLevel() : MotionPipelineV2.THREAT_MEDIUM;
                        boolean untrustedHigh = (currentThreat >= MotionPipelineV2.THREAT_HIGH) && !cachedHighIsTrusted;
                        if ((currentThreat <= MotionPipelineV2.THREAT_MEDIUM || untrustedHigh) && baselineFiltered.isEmpty()) {
                            // LOW/MEDIUM, or an UNTRUSTED HIGH (flag/shadow), + all detections are
                            // known static objects (or none) → suppress.
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.info("AI gate: " + tNames[currentThreat]
                                    + (untrustedHigh ? "(untrusted loiter)" : "")
                                    + " + no new objects → suppressing for Q" + qIdx);
                            relevantCount = 0;
                            motionFilteredCount = 0;
                        } else {
                            // Use baseline-filtered detections for downstream processing
                            motionFiltered = baselineFiltered;
                            relevantCount = motionFiltered.size();
                            motionFilteredCount = relevantCount;
                        }
                    }
                    
                    if (relevantCount > 0) {
                        // YOLO confirmed a real object — update AI confirmation timestamp.
                        // This is used by the deterrent flash guard to allow recording
                        // even during the suppression window if YOLO sees a real threat.
                        if (triggerEvidenceFound) {
                            lastAiConfirmationElapsedMs = detectionObservationElapsedMs;
                        }
                        // PERSON-specific timestamp for the track-anchored / immunity
                        // recency gates (a held in-zone track is a person; only a
                        // recent PERSON hit may certify it fresh). GENERATION-GATED:
                        // a lambda whose recording already ended must NOT refresh
                        // this — otherwise a post-stop run revives a stale person
                        // timestamp that, with a re-seeded zombie track, would
                        // certify a later unrelated burst's fast-path (defeating
                        // dropAllTrackerLocks). The track re-seed below is gated the
                        // same way, so the two stay consistent.
                        if (recordingGeneration.get() == generationAtSchedule) {
                            for (com.overdrive.app.ai.Detection d : motionFiltered) {
                                if (d.getClassId() == 0) {
                                    lastPersonConfirmationTimeMs = System.currentTimeMillis();
                                    lastPersonConfirmationElapsedMs =
                                            android.os.SystemClock.elapsedRealtime();
                                    break;
                                }
                            }
                        }

                        long timeSinceMotion = System.currentTimeMillis() - lastMotionTime;
                        if (timeSinceMotion < 2000) {
                            lastMotionTime = System.currentTimeMillis();
                        }
                        
                        boolean hasActiveMotion = timeSinceMotion < 2000;
                        // Always send to timeline — pre-trigger ring buffer captures
                        // events before recording starts for the JSON sidecar.
                        timelineCollector.onAiDetection(motionFiltered, hasActiveMotion, 1 << qIdx);
                        
                        // Cross-quadrant tracking REQUIRES bboxes in 320×240 quadrant
                        // pixel space — its centroid threshold (dist < 120) and edge
                        // margin (48 px) are hardcoded against Q_WIDTH=320 / Q_HEIGHT=240
                        // (CrossQuadrantTracker.java:77-78). When foveated, map a
                        // separate copy into quadrant space for CQT only.
                        //
                        // Use the SAME affine the motion-overlap filter above used, NOT
                        // a bare 320/640 scale. A foveated crop is a 640×640 WINDOW onto
                        // a quadrant that is only 320×240, so the real mapping is
                        // ax = ay = 0.25 plus the window's ORIGIN inside the quadrant
                        // (bx ∈ [0,160], by ∈ [0,80]) plus the per-role flip. The old
                        // 0.5 scale-only form was wrong in both terms at once: it
                        // doubled every coordinate and pinned every window to the
                        // quadrant's top-left corner. Consequences, all of them
                        // MISS-producing:
                        //   - Centroids landed up to 320 px from the truth, so the
                        //     same-quadrant proximity match failed and one subject
                        //     fragmented into a new trackId per YOLO tick.
                        //   - nearLeft/nearRight/nearTop/nearBottom (the edge flags
                        //     isHandoffEdgeMatch reads) were computed from those bogus
                        //     coordinates, so front→side handoff was decided on noise.
                        //   - Every fresh trackId restarts ActorTracker history, and
                        //     MIN_ESCALATION_FRAMES then pins the actor at NOTICE, which
                        //     suppresses the notification for a subject that really did
                        //     walk around the car.
                        // fovMapValid=false keeps the legacy scale-only form: without a
                        // window we cannot do better, and CQT failing to match is a
                        // duplicate track, never a dropped detection.
                        java.util.List<com.overdrive.app.ai.Detection> cqtDetections;
                        if (usedFoveated) {
                            cqtDetections = new java.util.ArrayList<>(motionFiltered.size());
                            final float cAx, cBx, cAy, cBy;
                            if (fovMapValid) {
                                cAx = fovMapAx; cBx = fovMapBx;
                                cAy = fovMapAy; cBy = fovMapBy;
                            } else {
                                cAx = 320.0f / FoveatedCropper.CROP_SIZE;  // 0.5, legacy
                                cAy = 320.0f / FoveatedCropper.CROP_SIZE;
                                cBx = 0f; cBy = 0f;
                            }
                            for (com.overdrive.app.ai.Detection det : motionFiltered) {
                                float gx0 = cAx * det.getX() + cBx;
                                float gx1 = cAx * (det.getX() + det.getW()) + cBx;
                                float gy0 = cAy * det.getY() + cBy;
                                float gy1 = cAy * (det.getY() + det.getH()) + cBy;
                                // A mirrored role gives a negative scale, so normalise
                                // corners after mapping (x,y must stay top-left).
                                cqtDetections.add(new com.overdrive.app.ai.Detection(
                                        det.getClassId(),
                                        det.getConfidence(),
                                        (int) Math.min(gx0, gx1),
                                        (int) Math.min(gy0, gy1),
                                        (int) Math.abs(gx1 - gx0),
                                        (int) Math.abs(gy1 - gy0)
                                ));
                            }
                        } else {
                            cqtDetections = motionFiltered;
                        }

                        // CAPTURE-ANCHORED OBSERVATION TIME (audit R13-7 /
                        // ExtE-8). Foveated pixels are older than "now" by the
                        // ring lag + slot dwell measured at dispatch (up to
                        // ~500ms) — stamping their detections with the LATER
                        // processing time skewed CQT recency, Actor dwell/
                        // trend windows and identity matching for exactly the
                        // fast subjects foveation targets. Back-date the
                        // observation clock by the capture age so identity/
                        // dwell consumers see the moment the subject was
                        // actually AT that position. Mosaic runs keep
                        // processing time: their crop freezes at dispatch like
                        // every run before this fix, so their calibrations
                        // (TTLs, trend windows) are untouched, and foveated
                        // runs now carry the SAME dispatch-relative skew
                        // instead of dispatch+capture-lag. Timeline badges and
                        // thumbnails deliberately keep processing time
                        // (cosmetic ±0.5s, per the ExtC-6 disposition).
                        final long obsWallMs = (usedFoveated && fovAgeAtDispatchNanos > 0)
                                ? System.currentTimeMillis() - (fovAgeAtDispatchNanos / 1_000_000L)
                                : System.currentTimeMillis();

                        java.util.List<CrossQuadrantTracker.TrackResult> tracked =
                                crossQuadrantTracker.processDetections(cqtDetections, qIdx, obsWallMs);

                        // ------ MULTI-BATCH CONFIRMATION SHADOW (log-only) ------
                        // The stamp above (lastAiConfirmationElapsedMs) confirmed
                        // this sequence from a SINGLE qualifying frame. Candidate
                        // replacement under field validation: require the same
                        // CQT-tracked object across N(class) DISTINCT AI batches
                        // within the current motion sequence (person 2,
                        // vehicle/bike 3, other 2). This block only counts and
                        // logs the would-be verdict next to today's behavior — it
                        // gates nothing and writes no trigger state. Sequence
                        // scoping = stamps >= firstMotionElapsedMs, the same
                        // comparison idiom sequenceConfirmed uses, so stale
                        // stamps stop counting on their own when a new sequence
                        // starts (no cross-sequence carry, no handoff reset —
                        // CQT ids survive camera handoffs). The N batches must
                        // additionally cluster inside MAX_CONFIRM_WINDOW_MS of
                        // the newest one (review fix): a sequence can live for
                        // minutes, and without a recency bound a sporadic false
                        // box would accumulate to N across a long shadow storm.
                        try {
                            final long shadowSeqStart = firstMotionElapsedMs;
                            StringBuilder shadowSb = null;
                            boolean shadowWouldConfirm = false;
                            for (int si = 0; si < motionFiltered.size(); si++) {
                                com.overdrive.app.ai.Detection sd = motionFiltered.get(si);
                                // Trigger-evidence mirror of the baseline filter loop
                                // above: persons always qualify; non-persons only when
                                // the baseline comparison was possible (mappable
                                // coords — same usedFoveated/fovMapValid condition
                                // that decided baselineSpaceDets there). Checked
                                // BEFORE counting (review fix): a detection the gate
                                // could never stamp from must not advance the counter
                                // either — otherwise unmappable-foveated batches
                                // contribute hidden counts and the shadow verdict
                                // reads optimistic vs the gate it models.
                                boolean sEvidence = sd.getClassId() == 0
                                        || !(usedFoveated && !fovMapValid);
                                if (!sEvidence) continue;
                                int sTid = si < tracked.size() ? tracked.get(si).trackId : 0;
                                int inSeq = aiConfirmShadow.observe(
                                        sTid, sd.getClassId(),
                                        detectionObservationElapsedMs, shadowSeqStart);
                                int sNeed = MultiBatchConfirmationShadow
                                        .requiredBatches(sd.getClassId());
                                boolean sMet = inSeq >= sNeed;
                                shadowWouldConfirm |= sMet;
                                if (shadowSb == null) shadowSb = new StringBuilder(96);
                                else shadowSb.append("; ");
                                shadowSb.append("trk#").append(sTid)
                                        .append(" cls=").append(sd.getClassId())
                                        .append(String.format(java.util.Locale.US,
                                                " conf=%.2f", sd.getConfidence()))
                                        .append(" seqBatches=").append(inSeq)
                                        .append('/').append(sNeed)
                                        .append(sMet ? " (met)" : "");
                            }
                            if (triggerEvidenceFound && shadowSb != null) {
                                logger.info("AI-confirm shadow Q" + qIdx
                                        + ": today=stamped, wouldBe="
                                        + (shadowWouldConfirm ? "CONFIRM" : "DEFER")
                                        + " | " + shadowSb);
                            }
                        } catch (Throwable shadowT) {
                            // Instrumentation must never break the AI pipeline.
                            logger.debug("Confirm-shadow logging failed: " + shadowT);
                        }

                        // ActorTracker + ThumbnailBuffer want bboxes in cropData's
                        // NATIVE coord space so the bbox-vs-rgb pair stays coherent
                        // when ThumbnailBuffer draws the box on the hero JPEG. In
                        // foveated mode that's 640×640 (motionFiltered's native space);
                        // in mosaic mode, 320×240 (also motionFiltered's native space).
                        // Either way, motionFiltered is what we want as the PRIMARY
                        // list — NOT cqtDetections, which is forced to 320 for CQT's
                        // hardcoded thresholds.
                        //
                        // (audit R5-6) cqtDetections IS additionally passed to
                        // actorTracker.update below as a parallel QUADRANT-SPACE
                        // list: ActorTracker's cross-observation comparisons
                        // (matching IoU, stability, trend, everMoved) need one
                        // constant physical reference frame, and the foveated
                        // window is a moving sub-region of the quadrant, so even
                        // per-frame-dims normalization stepped ~3× in area across
                        // every mosaic↔foveated flip. Index alignment holds:
                        // cqtDetections is built (above) from motionFiltered AFTER
                        // the baseline-filter reassignment (motionFiltered =
                        // baselineFiltered), i.e. from the SAME list instance the
                        // tracker consumes, in the same iteration order.
                        java.util.List<com.overdrive.app.ai.Detection> trackableDetections = motionFiltered;

                        // Build a parallel array of cross-quadrant track IDs to
                        // hand to ActorTracker. This binds the per-quadrant
                        // ActorTracker to the cross-camera identity assigned by
                        // CrossQuadrantTracker, so a person walking front→right
                        // doesn't get two actorIds. The arrays line up by index
                        // because processDetections returns one TrackResult per
                        // input Detection in order — and motionFiltered + cqtDetections
                        // share the same iteration order so indices stay aligned.
                        int[] xqTrackIds = new int[trackableDetections.size()];
                        for (int ti = 0; ti < tracked.size() && ti < xqTrackIds.length; ti++) {
                            xqTrackIds[ti] = tracked.get(ti).trackId;
                        }

                        // Actor layer: convert YOLO detections (in cropData's native
                        // coord space) into persistent Actor records. Snapshot is
                        // published as lastActors for downstream consumers (timeline,
                        // thumbnails, notifications, UI). Recording-relative timestamps
                        // require the recording start time which we look up from the
                        // recorder.
                        try {
                            // FIX (B1/H-a): if stopRecording bumped the generation
                            // while we were running, our writes belong to a
                            // recording that's already finalised. Skip them so
                            // we don't pollute the *next* recording's state.
                            if (recordingGeneration.get() != generationAtSchedule) {
                                logger.debug("AI lambda completed after recording stop (gen "
                                        + generationAtSchedule + " vs " + recordingGeneration.get()
                                        + ") — skipping Actor/Thumbnail writes");
                            } else {
                            long recordingStartWall = (timelineCollector != null && timelineCollector.isCollecting())
                                    ? timelineCollector.getRecordingStartTimeMs() : 0L;
                            // Pass the ACTUAL crop dims (qW × qH) used for this YOLO
                            // run. trackableDetections (motionFiltered) is in qW × qH
                            // pixel space, so the proximity ratio (bboxH / quadH) is
                            // self-consistent. ThumbnailBuffer also receives qW × qH
                            // and the same bbox space — bbox draws on the right pixels.
                            // (audit R5-6) cqtDetections rides along as the parallel
                            // quadrant-space (320×240) list for ActorTracker's
                            // cross-observation comparisons (see comment at the
                            // trackableDetections declaration). In mosaic mode it is
                            // the same list instance, so this is a no-op there.
                            // wallNowMs = capture-anchored observation time
                            // (audit R13-7 / ExtE-8) — see obsWallMs above.
                            java.util.List<Actor> actorSnapshot = actorTracker.update(
                                    trackableDetections,
                                    cqtDetections,
                                    xqTrackIds,
                                    qIdx,
                                    qW,
                                    qH,
                                    recordingStartWall,
                                    obsWallMs);
                            lastActors = actorSnapshot;
                            // Latch the event-level peak severity so the two
                            // Telegram stages gate on the worst the event ever
                            // reached, not whatever happens to be in lastActors
                            // at stop time (which TTL-prunes departed actors).
                            updateEventPeakSeverity(actorSnapshot, generationAtSchedule);
                            // Forward to thumbnail buffer so it can capture the peak-severity frame.
                            // Block C wires this; safe no-op if buffer not yet attached.
                            if (thumbnailBuffer != null && cropData != null) {
                                // MOTION-GROUNDED STATICNESS (hero selection).
                                //
                                // Hand the buffer the per-block motion mask for THIS
                                // frame so it can ask "are the pixels under this
                                // actor's bbox actually changing?" instead of relying
                                // on the Actor layer's staticness inference — which
                                // needs historyCount>=3 plus a settled stableFrames
                                // run and therefore often never latches at the ~2 Hz
                                // YOLO cadence (bbox jitter from fisheye warp and
                                // mosaic/foveated crop switching keeps resetting it).
                                // The mask is computed at the motion rate (~8.7 Hz)
                                // and is direct pixel-change evidence, so a parked
                                // car reads zero coverage however much its box wobbles.
                                //
                                // Same affine the motion-overlap detection filter
                                // uses above, so the two agree by construction.
                                //
                                // The buffer deliberately does NOT copy that filter's
                                // one-block dilation. The two have OPPOSITE penalty
                                // asymmetries: there, too-tight drops the detection
                                // outright (a miss), so slack is free; here, too-tight
                                // only flags scenery — and slack would make "static"
                                // harder to reach, letting a parked car beside a waving
                                // bush read live and take the hero, which is the bug the
                                // mask exists to fix. The buffer also already requires
                                // STATIC_CONFIRM_OBS consecutive zero-coverage samples
                                // plus agreement from the motion-independent signals, so
                                // it has its own slack in the safe direction.
                                //
                                // usedFoveated && !fovMapValid ⇒ pass a null mask so
                                // the buffer FAILS OPEN to "live" (never demote a real
                                // mover on a missing mapping).
                                float[] heroMask =
                                        (snapshotConfirmedBlocks > 0 && !(usedFoveated && !fovMapValid))
                                                ? blockConfSnapshot : null;
                                float mAx, mBx, mAy, mBy;
                                if (usedFoveated) {
                                    mAx = fovMapAx; mBx = fovMapBx;
                                    mAy = fovMapAy; mBy = fovMapBy;
                                } else {
                                    mAx = 1.0f; mBx = 0.0f;
                                    mAy = 1.0f; mBy = 0.0f;
                                }
                                thumbnailBuffer.observe(actorSnapshot, cropData, qW, qH, qIdx,
                                        heroMask, mAx, mBx, mAy, mBy);
                            }
                            // Mid-event baseline promotion: if the Actor layer
                            // has classified a vehicle / non-person actor as
                            // static, promote it into the baseline now so the
                            // *next* motion event suppresses it without waiting
                            // for stopRecording → updateFromEventEnd. Closes
                            // the loop with the Actor tracker's "static" flag.
                            for (Actor a : actorSnapshot) {
                                // Timeline-static superset: promote a parked car
                                // detected via the never-moved signal into the
                                // baseline now (so the next event suppresses it)
                                // without waiting for the consecutive stable frames
                                // the severity-path isStatic needs under sparse
                                // cadence.
                                if (!a.isStaticForTimeline) continue;
                                if (a.classGroup == Actor.ClassGroup.PERSON
                                        || a.classGroup == Actor.ClassGroup.ANIMAL
                                        || a.classGroup == Actor.ClassGroup.UNKNOWN) continue;
                                int cocoCls;
                                switch (a.classGroup) {
                                    case VEHICLE: cocoCls = 2; break;  // car
                                    case BIKE:    cocoCls = 1; break;  // bicycle
                                    default: continue;
                                }
                                // Baseline promotion only on mosaic frames.
                                // The DetectionBaseline normalizes coords to
                                // [0,1] of the QUADRANT for stable cross-event
                                // comparison. Foveated crops are a moving 640×640
                                // window centered on motion centroid, so their
                                // normalized coords don't refer to a stable
                                // physical region — promoting a foveated bbox
                                // would seed an entry that nothing in the next
                                // event can possibly match. Mosaic frames are
                                // the full quadrant downscaled, which is the
                                // stable reference baseline expects.
                                if (usedFoveated) continue;
                                detectionBaseline.promoteStaticActor(qIdx, cocoCls,
                                        a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH,
                                        qW, qH);
                            }
                            }  // close else { generation guard
                        } catch (Exception aEx) {
                            logger.warn("ActorTracker.update failed: " + aEx.getMessage());
                        }
                        
                        // SOTA: Start/refresh texture tracker on the highest-confidence detection.
                        // YOLO's job is done — the NCC tracker takes over frame-by-frame
                        // tracking. YOLO only wakes up again on heartbeat or NCC score drop.
                        // GENERATION-GATED: if this lambda's recording already ended, do
                        // NOT (re)seed or refresh a track. The native track has no
                        // pre-recording age-out (tracker_update is recording-gated), so a
                        // post-stop re-seed would survive as a zombie that the
                        // track-anchored / immunity recency gates read to certify a later
                        // unrelated burst — the exact TOCTOU hole that races
                        // dropAllTrackerLocks (the LAST statement of stopRecording).
                        // Dropping a track is always safe; only the (re)seed is gated.
                        // COORDINATE SPACE (audit R3b Ext-4): `best` is in
                        // cropData's NATIVE space — 640×640 when foveated — but
                        // the tracker contract is QUADRANT pixel coords
                        // (texture_tracker.h "320x240 space") and the image
                        // passed is the 320×240 mosaicQuadCrop. Unmapped
                        // foveated boxes extracted wrong-location or flat-gray
                        // templates and read as permanently in-zone in
                        // trackerInZone's 0..240 row math (zone-config bypass +
                        // bounded bogus extensions). Map through the SAME window
                        // affine the motion filter/CQT/baseline use; without a
                        // valid affine, skip seeding entirely — a missed seed is
                        // recoverable (the next mosaic run re-seeds), a bogus
                        // always-in-zone track is not. Heartbeat runs are forced
                        // mosaic upstream, so the refresh path is native=quadrant
                        // space either way.
                        // CAPTURE-AGE gate (audit R4 ExtB-8, re-anchored per
                        // R7 ExtC-5): the affine fixes SPACE but not TIME —
                        // `best` came from foveated pixels older than
                        // mosaicQuadCrop by the ring lag + slot dwell. The
                        // age is measured at DISPATCH (fovAgeAtDispatchNanos,
                        // captured alongside the mosaic snapshot), because
                        // both the box and the target frame freeze there;
                        // measuring here (post-inference) over-counted by the
                        // full ~250-300ms inference and made the 250ms budget
                        // structurally unsatisfiable.
                        final boolean fovSeedFresh = !usedFoveated
                                || (fovAgeAtDispatchNanos >= 0
                                    && fovAgeAtDispatchNanos <= FOVEATED_SEED_MAX_AGE_NANOS);
                        if (!trackableDetections.isEmpty() && mosaicQuadCrop != null
                                && (!usedFoveated || fovMapValid)
                                && fovSeedFresh
                                && recordingGeneration.get() == generationAtSchedule) {
                            com.overdrive.app.ai.Detection best = trackableDetections.get(0);
                            for (com.overdrive.app.ai.Detection d : trackableDetections) {
                                if (d.getConfidence() > best.getConfidence()) best = d;
                            }
                            int seedX = best.getX(), seedY = best.getY();
                            int seedW = best.getW(), seedH = best.getH();
                            if (usedFoveated) {  // fovMapValid guaranteed by the gate above
                                float gx0 = fovMapAx * best.getX() + fovMapBx;
                                float gx1 = fovMapAx * (best.getX() + best.getW()) + fovMapBx;
                                float gy0 = fovMapAy * best.getY() + fovMapBy;
                                float gy1 = fovMapAy * (best.getY() + best.getH()) + fovMapBy;
                                // Mirrored roles give a negative scale — normalise
                                // corners after mapping (same as the CQT copy).
                                seedX = (int) Math.min(gx0, gx1);
                                seedY = (int) Math.min(gy0, gy1);
                                seedW = (int) Math.abs(gx1 - gx0);
                                seedH = (int) Math.abs(gy1 - gy0);
                            }
                            try {
                                // FIX: Use the pre-captured isHeartbeatRun flag, NOT a live
                                // call to trackerNeedsYoloHeartbeat(). The live flag is mutated
                                // by trackerUpdate() on the main thread between when we queued
                                // this quadrant and when the lambda executes (100-200ms later).
                                if (isHeartbeatRun) {
                                    float[] trackBox = NativeMotion.trackerGetTrackBox(qIdx);

                                    // SPATIAL ASSOCIATION (audit R13-8 / ExtE-9).
                                    // The refresh used to consume `best` — the
                                    // highest-confidence detection ANYWHERE in the
                                    // quadrant — without matching it to the track's
                                    // own bbox. A second person entering the quadrant
                                    // with a higher score silently TOOK OVER the
                                    // track (template re-anchored onto them), and a
                                    // misclassified far object could semantic-kill a
                                    // perfectly healthy track. Associate first:
                                    // nearest detection within an adaptive radius of
                                    // the track's centroid (same 1.5×maxDim shape CQT
                                    // uses). No spatial match ⇒ neither refresh nor
                                    // kill — leave the heartbeat UNCONFIRMED so the
                                    // native tracker re-requests it; the track rides
                                    // its NCC template until a matched heartbeat or
                                    // its own lost-frame/TTL lifecycle resolves it.
                                    // (Heartbeat runs are forced mosaic upstream, so
                                    // detection coords are already quadrant-space.)
                                    com.overdrive.app.ai.Detection hbMatch = null;
                                    if (trackBox != null) {
                                        float tcx = trackBox[0] + trackBox[2] / 2f;
                                        float tcy = trackBox[1] + trackBox[3] / 2f;
                                        float gateRadius = Math.max(120f,
                                                Math.max(trackBox[2], trackBox[3]) * 1.5f);
                                        float bestD = Float.MAX_VALUE;
                                        for (com.overdrive.app.ai.Detection d : trackableDetections) {
                                            float cx = d.getX() + d.getW() / 2f;
                                            float cy = d.getY() + d.getH() / 2f;
                                            float dd = (float) Math.hypot(cx - tcx, cy - tcy);
                                            if (dd < gateRadius && dd < bestD) {
                                                bestD = dd;
                                                hbMatch = d;
                                            }
                                        }
                                    }

                                    int trackClassId = (trackBox != null) ? (int) trackBox[5] : -1;

                                    // CANONICAL compare, not raw classId. YOLO flips
                                    // car(2)↔truck(7)↔bus(5) and bicycle(1)↔motorcycle(3)
                                    // between frames on ONE physical object — the same
                                    // instability CrossQuadrantTracker and
                                    // DetectionBaseline already collapse. On a raw
                                    // compare a single flip at 0.78 conf on the very
                                    // vehicle being tracked reads as "morphed onto
                                    // background", drops the NCC track, and the teardown
                                    // gate then ends the recording while the vehicle is
                                    // still sitting there. The case the lock exists for
                                    // — person→car, i.e. a genuinely different GROUP —
                                    // survives the collapse untouched.
                                    // (audit R13-8) The semantic check now applies to
                                    // the SPATIALLY MATCHED detection — the one that
                                    // is plausibly the tracked object — never to an
                                    // unrelated high-confidence detection elsewhere.
                                    if (trackBox == null) {
                                        // Track vanished between dispatch and now
                                        // (dropped by toggle/stop) — nothing to do.
                                    } else if (hbMatch == null) {
                                        logger.info("Tracker heartbeat: no detection within "
                                                + "association radius of track Q" + qIdx
                                                + " — refresh skipped (no takeover)");
                                    } else if (trackClassId >= 0
                                            && canonicalClassId(hbMatch.getClassId()) != canonicalClassId(trackClassId)
                                            && hbMatch.getConfidence() > 0.70f) {
                                        // Semantic mismatch with high confidence — tracker morphed
                                        // onto a different object (e.g., person → parked car).
                                        // Require >70% confidence to avoid killing tracks on
                                        // low-confidence misclassifications (person torso → bus).
                                        logger.info("Semantic mismatch: track Q" + qIdx + 
                                                " born as class " + trackClassId + 
                                                " but YOLO sees class " + hbMatch.getClassId() + 
                                                " @" + String.format("%.0f%%", hbMatch.getConfidence() * 100) +
                                                " — killing track");
                                        NativeMotion.trackerDropTrack(qIdx);
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                        heartbeatMissCount[qIdx] = 0; // track gone (audit R14-1)
                                    } else {
                                        // Class matches — refresh the template with the
                                        // MATCHED detection's box (quadrant-space —
                                        // R3b Ext-4; heartbeats are mosaic-forced).
                                        NativeMotion.trackerRefreshTemplate(
                                                mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                                qIdx,
                                                hbMatch.getX(), hbMatch.getY(),
                                                hbMatch.getW(), hbMatch.getH(),
                                                System.currentTimeMillis());
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                        heartbeatMissCount[qIdx] = 0; // successful match (audit R14-1)
                                        logger.info("Tracker heartbeat confirmed: refreshed template for Q" + qIdx +
                                                " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                                    }
                                } else {
                                    // First detection: start a new track
                                    // (quadrant-space coords — R3b Ext-4)
                                    NativeMotion.trackerStartTrack(
                                            mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                            qIdx, best.getClassId(),
                                            seedX, seedY, seedW, seedH,
                                            System.currentTimeMillis());
                                    heartbeatMissCount[qIdx] = 0; // fresh track (audit R14-1)
                                }
                            } catch (Exception e) {
                                logger.warn("Tracker start/refresh failed: " + e.getMessage());
                            }
                        } else if (trackableDetections.isEmpty() && isHeartbeatRun) {
                            // (Moved to teardown gate outside the detections block)
                        }
                        
                        String qName = MotionPipelineV2.QUADRANT_NAMES[qIdx];
                        String cropMode = usedFoveated ? "foveated 640×640" : "mosaic 320×240";
                        logger.info(String.format("V2 AI [%s] (%s): %d objects (motion-filtered from %d), %d tracks",
                                qName, cropMode, relevantCount, detections.size(),
                                crossQuadrantTracker.getActiveTrackCount()));
                    }
                }
                
                // TEARDOWN GATE: When YOLO returns 0 objects during a heartbeat,
                // the object has left the scene. Kill the zombie track immediately.
                // Previously this was nested inside the `if (!detections.isEmpty())` block,
                // so it never executed when YOLO returned empty — the track stayed alive
                // forever, spamming heartbeats on every frame.
                if (isHeartbeatRun && (detections == null || detections.isEmpty()
                        || motionFilteredCount == 0)) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(qIdx)) {
                            // CONSECUTIVE-MISS GATE (audit R14-1 / ExtF-1):
                            // spare the first empty heartbeat — isolated
                            // misses on a real stationary person are seen in
                            // device logs, and a dropped stationary person
                            // may never re-trigger inference. The heartbeat
                            // is left UNCONFIRMED on the spared miss so the
                            // native tracker re-requests it promptly; a
                            // genuinely departed subject fails that retry
                            // too and drops one interval later.
                            heartbeatMissCount[qIdx]++;
                            if (heartbeatMissCount[qIdx] >= HEARTBEAT_MISSES_TO_DROP) {
                                heartbeatMissCount[qIdx] = 0;
                                NativeMotion.trackerDropTrack(qIdx);
                                NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                logger.info("Tracker teardown: " + HEARTBEAT_MISSES_TO_DROP
                                        + " consecutive empty heartbeats, killed track Q" + qIdx +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                            } else {
                                logger.info("Tracker heartbeat miss "
                                        + heartbeatMissCount[qIdx] + "/" + HEARTBEAT_MISSES_TO_DROP
                                        + " for Q" + qIdx + " — track spared, retry pending");
                            }
                        }
                    } catch (Exception ignored) {}
                }
                }
            } catch (Exception e) {
                logger.error("V2 AI detection error (Q" + qIdx + ")", e);
            } finally {
                releaseAiLane(laneStamp);
            }
        };

        // isAiRunning was latched true ABOVE, before this dispatch. If the
        // executor refuses the task (shutdownNow during release/ACC-ON teardown)
        // the lambda — and therefore its finally-block — never runs, leaking the
        // flag true forever and silently wedging the AI lane for the remainder of
        // the engine's life. Clear it here on the rejection path.
        try {
            aiExecutor.execute(aiTask);
            aiDispatchCount.incrementAndGet();
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            aiRunStartedMs = 0;
            isAiRunning.set(false);
            logger.warn("AI dispatch rejected (executor shut down) — released AI lane for Q" + quadrant);
        } catch (Throwable t) {
            // Realistically only an Error can land here (RejectedExecutionException
            // is caught above) — e.g. OOM while the executor allocates its queue
            // node or worker thread. Release the lane so the engine isn't left
            // wedged, log with a stack trace, then RETHROW Errors: swallowing an
            // OOM at warn level would let the daemon limp on in a corrupt state
            // with no crash report.
            aiRunStartedMs = 0;
            isAiRunning.set(false);
            logger.error("AI dispatch failed for Q" + quadrant, t);
            if (t instanceof Error) throw (Error) t;
        }
    }

    /**
     * Record why an AI dispatch was skipped. Diagnostics only — callers have
     * already decided to return. Keeps the reason string for the periodic stats
     * line so a field log can attribute a missed event to a specific stage.
     */
    private void noteAiSkip(String reason) {
        aiSkipCount.incrementAndGet();
        lastAiSkipReason = reason;
    }

    /**
     * Release the AI lane on behalf of the inference that claimed it with
     * {@code stamp}. No-op when the stamp no longer matches — i.e. the watchdog
     * already force-released this lane and a NEWER inference has since claimed
     * it. Without that check, a task the watchdog gave up on would unlatch its
     * successor on completion, letting a third dispatch overlap the second.
     *
     * <p>Called from the aiExecutor thread; {@code aiLaneStamp} is written only
     * on the engine thread, so the comparison reads a stable published value.
     */
    private void releaseAiLane(long stamp) {
        if (stamp != aiLaneStamp) {
            logger.debug("AI lane release ignored (stale stamp " + stamp
                    + " != " + aiLaneStamp + ") — watchdog already reassigned the lane");
            return;
        }
        // Clear the age BEFORE the latch: the watchdog's first guard is
        // isAiRunning, so ordering it this way means a watchdog tick that observes
        // the latch still set can never read a start time belonging to a finished
        // task. Restores the documented "0 = idle" contract on aiRunStartedMs.
        aiRunStartedMs = 0;
        isAiRunning.set(false);
    }

    /**
     * Watchdog for a leaked {@link #isAiRunning} latch.
     *
     * <p>The flag is set on the engine thread BEFORE the inference task is
     * enqueued and cleared only in that task's {@code finally}. Every known
     * path is covered, but the failure mode is catastrophic and silent: while
     * the flag is stuck true, all six dispatch sites early-return, YOLO stops
     * running entirely, and — because an untrusted THREAT_HIGH is YOLO-gated —
     * real loiter events stop triggering with nothing in the log to say why.
     * That is precisely the shape of the 2026-07-19 field incident.
     *
     * <p>This is a liveness backstop: it releases a lane held far longer than any
     * legitimate inference (~250-300 ms on this hardware; the bar is
     * {@link #AI_LANE_STUCK_MS} = 60 s). It never suppresses a detection and never
     * lowers a trigger bar, so it cannot introduce a false positive.
     *
     * <p>It cannot PROVE the holder is dead — only that it is overdue. If it trips
     * on a live-but-overlong inference, a new one may claim the lane while the old
     * one finishes; {@link #releaseAiLane} stamp-guards that so the finishing task
     * cannot unlatch its successor. The two {@code detect()} bodies would still
     * serialize on YoloDetector's interpLock for the interpreter run, but note that
     * detector's post-processing scratch buffers assume a single caller — which is
     * why the threshold is deliberately set an order of magnitude above the real
     * inference cost rather than close to it.
     *
     * <p>Called once per motion tick, on the engine thread (the sole writer of
     * {@link #isAiRunning}'s claim side and of all five dispatch sites). Reads
     * the monotonic clock itself rather than taking the frame's wall-clock
     * {@code now} — see the comment on the comparison below.
     */
    private void maybeRepairStuckAiLane() {
        if (!isAiRunning.get()) return;
        long started = aiRunStartedMs;
        if (started <= 0) return;
        // MONOTONIC clock, matching the claim stamp at the dispatch site. This
        // measures an INTERVAL, so it must not use wall-clock: a head unit
        // acquires GPS/NTP time seconds-to-minutes after cold boot and the
        // correction from the drifted RTC can be minutes. With
        // System.currentTimeMillis() a forward jump >60 s landing mid-inference
        // would force-release a perfectly healthy lane (inviting the very
        // overlap the 60 s bar is set high to avoid), and a backward jump would
        // make heldMs negative — silently disabling the watchdog for the whole
        // correction window, exactly when the stuck-lane failure it guards is
        // most likely (SD remount storm during boot).
        long heldMs = android.os.SystemClock.elapsedRealtime() - started;
        if (heldMs < AI_LANE_STUCK_MS) return;
        // Re-check under the same read: if the task completed between the guard
        // above and here, don't stomp a fresh inference's latch.
        if (!isAiRunning.get()) return;
        if (aiRunStartedMs != started) return;
        // Retire this lane occupancy BEFORE unlatching, in the same order
        // releaseAiLane uses and for the same reason.
        //
        // Bumping the stamp is what makes the abandoned task's release a no-op.
        // Without it, the stamp guard in releaseAiLane only protects the case
        // where a NEW dispatch happened to intervene (that bumps the stamp at
        // the claim site); if motion has since stopped, the task we gave up on
        // unwinds with a stamp that still matches and its finally-block clears
        // a lane it no longer owns.
        aiLaneStamp++;
        // Clear the age too — the watchdog is otherwise the only unlatch path
        // that leaves this field non-zero, breaking the documented "0 = idle"
        // contract for every reader until the next dispatch overwrites it.
        aiRunStartedMs = 0;
        isAiRunning.set(false);
        long repairs = aiLaneRepairCount.incrementAndGet();
        logger.warn(String.format(
                "AI lane WATCHDOG: isAiRunning held %.1fs (>%.1fs) — force-releasing so YOLO can resume. "
                + "repairs=%d dispatches=%d detects=%d",
                heldMs / 1000.0, AI_LANE_STUCK_MS / 1000.0,
                repairs, aiDispatchCount.get(), aiDetectCompletedCount.get()));
    }

    /**
     * Crop a quadrant from the 640×480 mosaic into the reusable aiBuffer.
     * Legacy path used when foveated cropper is not available.
     */
    private byte[] cropFromMosaic(byte[] mosaicRgb, int quadrant, int qW, int qH) {
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;

        int cropSize = qW * qH * BYTES_PER_PIXEL;
        byte[] aiBuffer = aiBufferTL.get();
        if (aiBuffer == null || aiBuffer.length != cropSize) {
            aiBuffer = new byte[cropSize];
            aiBufferTL.set(aiBuffer);
        }

        for (int y = 0; y < qH; y++) {
            int srcOffset = ((startY + y) * THUMBNAIL_WIDTH + startX) * BYTES_PER_PIXEL;
            int dstOffset = y * qW * BYTES_PER_PIXEL;
            System.arraycopy(mosaicRgb, srcOffset, aiBuffer, dstOffset, qW * BYTES_PER_PIXEL);
        }
        return aiBuffer;
    }
    
    /**
     * Sets the Region of Interest (ROI) mask for motion detection.
     * 
     * @param mask Byte array (320×240) where 1 = check motion, 0 = ignore
     *             Pass null to use entire frame (default)
     */
    public void setRoiMask(byte[] mask) {
        if (mask != null && mask.length != THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) {
            logger.error("Invalid ROI mask size: " + mask.length + 
                       " (expected " + (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) + ")");
            return;
        }
        
        this.roiMask = mask;
        
        // Count pixels in ROI for normalization
        if (mask != null) {
            roiPixelCount = 0;
            for (byte b : mask) {
                if (b != 0) roiPixelCount++;
            }
            logger.info("ROI mask set: " + roiPixelCount + " pixels (" + 
                      (roiPixelCount * 100 / (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT)) + "%)");
        } else {
            roiPixelCount = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
            logger.info("ROI mask cleared (using full frame)");
        }
    }
    
    /**
     * Sets ROI from polygon points (normalized 0.0-1.0 coordinates).
     * 
     * @param points Array of [x, y] pairs defining polygon vertices
     */
    public void setRoiFromPolygon(float[][] points) {
        if (points == null || points.length < 3) {
            setRoiMask(null);  // Clear ROI
            return;
        }
        
        // Create mask by rasterizing polygon
        byte[] mask = new byte[THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT];
        
        for (int y = 0; y < THUMBNAIL_HEIGHT; y++) {
            for (int x = 0; x < THUMBNAIL_WIDTH; x++) {
                float nx = (float) x / THUMBNAIL_WIDTH;
                float ny = (float) y / THUMBNAIL_HEIGHT;
                
                // Point-in-polygon test (ray casting algorithm)
                if (isPointInPolygon(nx, ny, points)) {
                    mask[y * THUMBNAIL_WIDTH + x] = 1;
                }
            }
        }
        
        setRoiMask(mask);
    }
    
    /**
     * Point-in-polygon test using ray casting.
     */
    private boolean isPointInPolygon(float x, float y, float[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = polygon[i][0], yi = polygon[i][1];
            float xj = polygon[j][0], yj = polygon[j][1];
            
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        
        return inside;
    }
    
    // ========================================================================
    // Per-Quadrant ROI (Region of Interest)
    // ========================================================================
    
    /**
     * Applies a polygon ROI to a specific quadrant.
     * Converts the polygon (normalized 0-1 coords) to a 10×7 block mask
     * and passes it to the C++ pipeline via JNI.
     *
     * @param quadrant Quadrant index (0-3)
     * @param polygon  Array of [x, y] vertex pairs in normalized coords (0.0-1.0)
     */
    public void applyQuadrantRoi(int quadrant, float[][] polygon) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        if (polygon == null || polygon.length < 3) {
            clearQuadrantRoi(quadrant);
            return;
        }
        
        // Convert polygon to 10×7 block mask.
        // For each block, check if its center is inside the polygon.
        byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
        int enabledCount = 0;
        
        for (int by = 0; by < MotionPipelineV2.GRID_ROWS; by++) {
            for (int bx = 0; bx < MotionPipelineV2.GRID_COLS; bx++) {
                int blockIdx = by * MotionPipelineV2.GRID_COLS + bx;
                // Block center in normalized coordinates
                float cx = (bx + 0.5f) / MotionPipelineV2.GRID_COLS;
                float cy = (by + 0.5f) / MotionPipelineV2.GRID_ROWS;
                
                if (isPointInPolygon(cx, cy, polygon)) {
                    blockMask[blockIdx] = 1;
                    enabledCount++;
                }
            }
        }
        
        try {
            NativeMotion.setQuadrantRoi(quadrant, blockMask);
            logger.info("ROI applied to Q" + quadrant + " [" + 
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "]: " + 
                    enabledCount + "/" + MotionPipelineV2.TOTAL_BLOCKS + " blocks enabled");
        } catch (Exception e) {
            logger.warn("Failed to apply ROI to Q" + quadrant + ": " + e.getMessage());
        }
    }
    
    /**
     * Clears the ROI for a specific quadrant (all blocks enabled).
     */
    public void clearQuadrantRoi(int quadrant) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        try {
            NativeMotion.setQuadrantRoi(quadrant, null);
            logger.info("ROI cleared for Q" + quadrant + " [" +
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "] (all blocks enabled)");
        } catch (Exception e) {
            logger.warn("Failed to clear ROI for Q" + quadrant + ": " + e.getMessage());
        }
    }

    /**
     * Resolves and applies the effective ROI for a quadrant to the native
     * pipeline, honoring BOTH ROI storage modes:
     *
     *   1. Polygon ROI (legacy): {@code config.getRoiPolygon(q)} — rasterized
     *      to a block mask by {@link #applyQuadrantRoi}.
     *   2. Block-tap ROI (current UI): the {@code roiBlocks_Q*} mask persisted
     *      in unified config by the block-tap editor. The block editor never
     *      writes a polygon, so any polygon-only re-apply path would wrongly
     *      clear it — see below.
     *
     * This MUST be the single entry point for every "re-apply ROI from config"
     * loop (setConfig, enableSurveillance). Previously those loops only checked
     * the polygon and fell through to clearQuadrantRoi() when it was null, which
     * silently wiped a block-tap ROI on the running pipeline every time any
     * config field changed while surveillance was active.
     *
     * @param quadrant Quadrant index (0-3)
     */
    private void applyEffectiveRoi(int quadrant) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;

        // Mode 1: explicit polygon (only set by the legacy polygon editor).
        if (config != null && config.isRoiEnabled(quadrant) && config.getRoiPolygon(quadrant) != null) {
            applyQuadrantRoi(quadrant, config.getRoiPolygon(quadrant));
            return;
        }

        // Mode 2: block-tap mask persisted in unified config.
        try {
            org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            String qKey = "Q" + quadrant;
            boolean roiEnabled = survCfg.optBoolean("roiEnabled_" + qKey, false);
            org.json.JSONArray blockArr = survCfg.optJSONArray("roiBlocks_" + qKey);
            if (roiEnabled && blockArr != null && blockArr.length() == MotionPipelineV2.TOTAL_BLOCKS) {
                byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
                for (int i = 0; i < MotionPipelineV2.TOTAL_BLOCKS; i++) {
                    blockMask[i] = (byte)(blockArr.optInt(i, 1) != 0 ? 1 : 0);
                }
                NativeMotion.setQuadrantRoi(quadrant, blockMask);
                logger.info("ROI blocks applied to Q" + quadrant + " [" +
                        MotionPipelineV2.QUADRANT_NAMES[quadrant] + "] from unified config");
                return;
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve block-tap ROI for Q" + quadrant + ": " + e.getMessage());
        }

        // Neither mode active — full frame.
        clearQuadrantRoi(quadrant);
    }

    /**
     * Sets the SOTA surveillance configuration.
     * 
     * @param config Configuration object with distance preset, flash mode, and camera calibration
     */
    public void setConfig(SurveillanceConfig config) {
        this.config = config;

        // Sync legacy fields for backward compatibility
        this.flashImmunity = config.getFlashImmunity();
        this.requiredActiveBlocks = config.getRequiredBlocks();
        this.minObjectSize = config.getMinObjectSize();
        this.aiConfidence = config.getAiConfidence();
        this.preRecordMs = config.getPreRecordSeconds() * 1000L;
        this.postRecordMs = config.getPostRecordSeconds() * 1000L;

        // FIX (Bug A): propagate the loaded pre-record duration to the encoder's
        // circular buffer. Without this the encoder retains its hardcoded 5s
        // allocation from init() until the user re-saves the setting, which makes
        // the persisted value look like it was reset.
        if (recorder != null && recorder.getEncoder() != null) {
            try {
                recorder.getEncoder().setPreRecordDuration(config.getPreRecordSeconds());
            } catch (Exception e) {
                logger.warn("Failed to propagate pre-record duration to encoder: " + e.getMessage());
            }
        }
        
        // Sync loitering time for Java-side sustained motion enforcement
        this.loiteringTimeMs = config.getLoiteringTimeSeconds() * 1000L;
        // Sync the approach fast-path bar (0 = disabled).
        this.approachTriggerMs = config.getApproachTriggerSeconds() * 1000L;
        
        // Update frame dimensions in config for distance estimation
        config.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        config.setIsMosaic(true);  // We use 2x2 mosaic layout
        
        // Apply object detection filters from saved config.
        // This rebuilds the classFilter array so YOLO respects detectPerson/detectCar/detectBike/detectAnimal.
        setObjectFilters(config.getMinObjectSize(), config.getAiConfidence(),
                config.isDetectPerson(), config.isDetectCar(), config.isDetectBike(),
                config.isDetectAnimal());
        
        // Apply V2 pipeline settings from loaded config.
        // Order matters: environment preset sets all defaults, then sensitivity and
        // detection zone override their specific parameters, then loitering and cameras.
        if (pipelineV2Config != null && pipelineV2 != null) {
            pipelineV2Config.applyEnvironmentPreset(config.getEnvironmentPreset());

            // The native pipeline runs once with a single config. To honor
            // per-quadrant overrides we feed the *most-permissive* aggregate
            // (highest sensitivity, widest detection zone) to native, then
            // demote per-quadrant in Java via applyQuadrantOverrides().
            int aggSens = config.getSensitivityLevel();
            String aggZone = config.getDetectionZone();
            for (int q = 0; q < 4; q++) {
                aggSens = Math.max(aggSens, config.getEffectiveSensitivityLevel(q));
                if ("extended".equals(config.getEffectiveDetectionZone(q))) {
                    aggZone = "extended";
                } else if ("normal".equals(config.getEffectiveDetectionZone(q))
                        && "close".equals(aggZone)) {
                    aggZone = "normal";
                }
            }
            pipelineV2Config.applySensitivity(aggSens);
            pipelineV2Config.applyDetectionZone(aggZone);
            pipelineV2Config.loiteringFrames = config.getLoiteringTimeSeconds() * 10;
            // Apply saved shadow filter mode (after preset, so user override takes precedence)
            pipelineV2Config.shadowFilterMode = config.getShadowFilterMode();
            // Motion salience: one user flag drives BOTH halves of the channel —
            // the native flash-filter probe (so a large close object survives the
            // >25% mass filter long enough to be measured) and the Java trigger
            // channel below. Keeping them on one flag means the Java side can never
            // wait on a signal the native side isn't producing.
            pipelineV2Config.salienceEnabled = config.isMotionSalienceEnabled();
            boolean[] cameras = config.getCameraEnabled();
            for (int i = 0; i < 4; i++) {
                pipelineV2Config.quadrantEnabled[i] = cameras[i];
            }
            pipelineV2.applyConfig(pipelineV2Config);
            logger.info(String.format("V2 pipeline config applied: env=%s, sens=%d (agg=%d), zone=%s (agg=%s), loiter=%ds, cameras=[%b,%b,%b,%b], salience=%s",
                    config.getEnvironmentPreset(), config.getSensitivityLevel(), aggSens,
                    config.getDetectionZone(), aggZone,
                    config.getLoiteringTimeSeconds(), cameras[0], cameras[1], cameras[2], cameras[3],
                    !config.isMotionSalienceEnabled() ? "off"
                            : (pipelineV2.isNativeSalienceSupported() ? "on" : "on(native UNSUPPORTED → inert)")));
        }

        // Java-side salience gate. Mirrors the native flag; when the loaded .so
        // predates the salience fields the per-tick evaluation additionally checks
        // isNativeSalienceSupported(), so an old library degrades to inert rather
        // than waiting forever on componentCount==0.
        //
        // On ANY change to the flag, discard the channel's accumulated state. While
        // the flag is off the per-tick evaluator doesn't run, so the per-quadrant run
        // counters FREEZE instead of decaying on non-qualifying ticks — a counter
        // left at 5 of the 6 required ticks would let the first qualifying tick after
        // a re-enable confirm on evidence gathered in the previous epoch, defeating
        // the "6 CONSECUTIVE ticks" requirement. Clearing on the transition (rather
        // than only in enable()) keeps a mid-session toggle equivalent to a re-arm.
        // The reset is REQUESTED, not performed here: setConfig runs on the HTTP/IPC
        // thread while the engine thread is doing salienceRunTicks[q]++ in
        // processFrameV2. Writing those non-volatile fields from here is a data race —
        // an interleaving where we store 0 between the engine's read of 5 and its
        // store of 6 leaves the counter at 6, latching the channel on a SINGLE
        // qualifying tick using the previous epoch's evidence, which is exactly what
        // this reset exists to prevent. The engine thread performs it on its next tick
        // (a bounded ~115 ms) and owns every one of those fields, so no other site
        // needs synchronisation. The volatile write below publishes the request.
        // Post-park vigilance master flag: a volatile hand-off is sufficient — every
        // consumer reads through vigilanceActive()/vigilanceMayTrigger(), which fold
        // the flag in, and the latch is TTL-bounded, so an OFF flip mutes the channel
        // immediately with no engine-thread reset ceremony needed.
        this.postParkVigilanceEnabled = config.isPostParkVigilanceEnabled();
        boolean salienceWas = this.salienceEnabled;
        this.salienceEnabled = config.isMotionSalienceEnabled();
        if (salienceWas != this.salienceEnabled) {
            salienceResetRequested = true;
        }

        // Apply filter debug setting
        this.filterDebugEnabled = config.isFilterDebugLogEnabled();
        
        // Apply per-quadrant ROI from config (if surveillance is active, apply immediately).
        // Resolves polygon AND block-tap masks — a polygon-only check here used to
        // clear the block-tap ROI (the mode the UI actually uses) on every config
        // change while running. See applyEffectiveRoi.
        if (active) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                applyEffectiveRoi(q);
            }
        }
        
        logger.info("Config applied: " + config.toString());
    }
    
    /**
     * Gets the current SOTA configuration.
     * 
     * @return Current configuration
     */
    public SurveillanceConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the last estimated distance to motion.
     * 
     * @return Distance in meters, or 0 if no motion detected
     */
    public float getLastEstimatedDistance() {
        return lastEstimatedDistance;
    }

    /**
     * Snapshot of currently-active Actors. Lock-free read suitable for UI / API
     * threads. May be empty if no detections have been observed yet.
     */
    public java.util.List<Actor> getLastActors() {
        return lastActors;
    }
    
    /**
     * Re-evaluate each quadrant's result against its effective (possibly
     * overridden) sensitivity / zone gates. The native pipeline already ran
     * with the most-permissive aggregate config, so we only ever demote — we
     * never falsely promote, so this can't synthesize motion that the native
     * stage didn't see.
     *
     * Demotion clears motionDetected, threatLevel, confirmedBlocks, and
     * componentSize. activeBlocks and per-block confidences are preserved for
     * diagnostics.
     */
    private void applyQuadrantOverrides(MotionPipelineV2.QuadrantResult[] results) {
        if (config == null || results == null) return;

        // Fast path: nothing overridden → skip entirely.
        boolean anyOverride = false;
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            if (config.getQuadrantSensitivityOverride(q) != null
                    || config.getQuadrantDetectionZoneOverride(q) != null) {
                anyOverride = true;
                break;
            }
        }
        if (!anyOverride) return;

        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            MotionPipelineV2.QuadrantResult r = results[q];
            if (r == null || !r.motionDetected) continue;

            int effSens = config.getEffectiveSensitivityLevel(q);
            String effZone = config.getEffectiveDetectionZone(q);
            MotionPipelineV2.Config.GateThresholds gates =
                    MotionPipelineV2.Config.gatesForSensitivity(effSens);
            int maxRow = MotionPipelineV2.Config.maxDistanceRowForZone(effZone);

            // Recount confirmed blocks at this quadrant's stricter confidence threshold.
            int confirmedAtThreshold = 0;
            if (r.blockConfidence != null) {
                for (int i = 0; i < r.blockConfidence.length; i++) {
                    if (r.blockConfidence[i] >= gates.confidenceThreshold) confirmedAtThreshold++;
                }
            } else {
                confirmedAtThreshold = r.confirmedBlocks;
            }

            boolean failsAlarm = confirmedAtThreshold < gates.alarmBlockThreshold;
            boolean failsComponent = r.componentSize < gates.minComponentSize;
            boolean failsZone = (maxRow > 0) && (r.centroidY < maxRow);

            if (failsAlarm || failsComponent || failsZone) {
                r.motionDetected = false;
                r.threatLevel = MotionPipelineV2.THREAT_NONE;
                r.confirmedBlocks = confirmedAtThreshold;
                if (filterDebugEnabled) {
                    String reason = failsAlarm ? "alarm" : failsComponent ? "component" : "zone";
                    logger.debug(String.format(
                            "  [%s] OVERRIDE_DEMOTED reason=%s sens=%d zone=%s confirmed@%.2f=%d/%d component=%d/%d centroidRow=%.1f/cutoff=%d",
                            MotionPipelineV2.QUADRANT_NAMES[q], reason, effSens, effZone,
                            gates.confidenceThreshold, confirmedAtThreshold, gates.alarmBlockThreshold,
                            r.componentSize, gates.minComponentSize, r.centroidY, maxRow));
                }
            } else {
                // PASS: leave r.confirmedBlocks at the native value.
                //
                // It used to be overwritten with the stricter per-quadrant recount
                // "so downstream consumers see consistent numbers", but the quadrant
                // PASSED its own gates here — nothing is being demoted — and the
                // recount is only ever <= native. Deflating it fed two liveness
                // checks that read confirmedBlocks directly: the post-record
                // `anyActivity` extension and `qualifyingMotion`. A subject whose
                // blocks all sit just under the stricter threshold could recount to
                // 0 and, with activeBlocks also 0, make the extension read "nothing
                // there" and STOP the recording while the subject was still present.
                //
                // The demotion decision above (the FP-protecting part) already used
                // confirmedAtThreshold locally, so it is unaffected; only the value
                // handed to the liveness checks stops being artificially low. Cannot
                // create a trigger: motionDetected/threatLevel are untouched here.
            }
        }
    }

    /**
     * Estimate real-world distance from a centroid Y position in block coordinates.
     * 
     * The centroid Y is in block coordinates (0 = top row / far, GRID_ROWS-1 = bottom / close).
     * We convert to pixel coordinates in the full mosaic, then use SurveillanceConfig's
     * camera calibration to estimate distance in meters.
     * 
     * @param quadrant Quadrant index (0=front, 1=right, 2=rear, 3=left)
     * @param centroidBlockY Centroid Y in block coordinates (0-6)
     * @return Estimated distance in meters, or -1 if unavailable
     */
    private float estimateDistanceFromCentroid(int quadrant, float centroidBlockY) {
        if (config == null) return -1;

        // Convert block Y to pixel Y within the quadrant
        // Block size is 32px, centroid is center of the block cluster
        float pixelY = centroidBlockY * GRID_BLOCK_SIZE + (GRID_BLOCK_SIZE / 2.0f);

        // Convert quadrant-local pixel Y to global mosaic Y
        // Mosaic layout: top row = quadrants 0,1; bottom row = quadrants 2,3
        int quadrantOffsetY = (quadrant >= 2) ? (THUMBNAIL_HEIGHT / 2) : 0;
        int globalY = quadrantOffsetY + (int) pixelY;

        return config.estimateDistanceForQuadrant(quadrant, globalY);
    }

    // ===== SOTA proximity estimation =====
    //
    // Per-quadrant tracking state for the trend computation. lowestY is the
    // row of the lowest active motion block on the previous tick; the trend
    // is the sign of (now - then). Mutated EXACTLY ONCE per processFrameV2
    // tick by {@link #updateProximityState}; downstream log sites within the
    // same tick read {@link #cachedTrend} (the result of that one update)
    // rather than re-mutating these fields. See audit H2.
    private final int[] prevLowestBlockY = new int[]{-1, -1, -1, -1};
    private final long[] prevLowestBlockYAtMs = new long[]{0, 0, 0, 0};

    // Effective vertical FOV after the BYD AVM HAL's dewarp. Stored
    // per-quadrant because front/rear cameras (ultra-wide fisheye in the
    // grille and rear plate) carry a wider vertical FOV than the
    // mirror-housing side cameras. A single global constant inflated
    // side-camera distances by ~70% per the validation analysis;
    // per-quadrant values close that gap with no calibration cost.
    //
    // Foveated 640×640 crops are a moving window inside one tile, so
    // their effective vertical FOV is smaller —
    // {@code per_quadrant_FOV × (CROP_SIZE / camStripHeight)}, computed
    // dynamically (Seal stripHeight=960 → 0.667; Tang stripHeight=720
    // → 0.889).
    //
    // Quadrant order: 0=front, 1=right, 2=rear, 3=left.
    private static final int FOVEATED_CROP_SIZE_PX = 640;  // matches FoveatedCropper.CROP_SIZE
    private static final float[] DEFAULT_VERTICAL_FOV_DEG = { 115f, 95f, 115f, 95f };

    /**
     * Per-vehicle camera-tile height in pixels (Seal=960, Tang=720).
     * Set by {@link #setCameraStripHeight} during pipeline init; used
     * to compute the foveated-crop FOV scale dynamically.
     */
    private volatile int cameraStripHeightPx = 960;  // Seal default
    /**
     * Per-quadrant vertical FOV in degrees. Set by
     * {@link #setCameraVerticalFovDeg(float[])} from the active
     * {@link com.overdrive.app.camera.CameraProfile}.
     */
    private volatile float[] cameraVerticalFovDeg = DEFAULT_VERTICAL_FOV_DEG.clone();

    /**
     * Configure the per-vehicle camera-tile height so the foveated-crop
     * FOV scaling is correct. Without this, the foveated path uses a
     * Seal-specific 640/960 ratio and reads ~30% long on Tang.
     */
    public void setCameraStripHeight(int stripHeightPx) {
        if (stripHeightPx > 0) this.cameraStripHeightPx = stripHeightPx;
    }

    /**
     * Configure per-quadrant vertical FOV (degrees) for the
     * bbox-height distance inference. Source: the active
     * {@link com.overdrive.app.camera.CameraProfile}'s
     * {@code getVerticalFovDeg(q)} values, written once during pipeline
     * init. Out-of-shape input is ignored (defaults retained).
     */
    public void setCameraVerticalFovDeg(float[] fovDegPerQuadrant) {
        if (fovDegPerQuadrant != null && fovDegPerQuadrant.length == 4) {
            float[] copy = new float[4];
            for (int i = 0; i < 4; i++) {
                copy[i] = fovDegPerQuadrant[i] > 0 ? fovDegPerQuadrant[i] : DEFAULT_VERTICAL_FOV_DEG[i];
            }
            this.cameraVerticalFovDeg = copy;
        }
    }

    /**
     * True iff the NCC tracker's bbox in {@code quadrant} sits inside the
     * user's configured detection zone. The motion-block path is gated
     * by {@code maxDistanceRow} natively + via {@link #applyQuadrantOverrides};
     * the NCC tracker bbox isn't, so an out-of-zone tracker lock can
     * leak past the zone gate via four paths:
     *
     * <ul>
     *   <li>brightness-suppression immunity ({@code !anyMotion} → forced
     *       {@code anyMotion=true} when a tracker has a person lock)</li>
     *   <li>{@code bestQ} fallback when {@code getHighestThreatQuadrant()}
     *       returns -1 because every quadrant got demoted by the row gate</li>
     *   <li>gap-tolerance extension ({@code trackerActive} extends 2s gap
     *       to 4s)</li>
     *   <li>post-record extension ({@code trackerHolding} keeps recording
     *       alive past motion-end)</li>
     * </ul>
     *
     * <p>Each of those gives recording a way to fire / persist for an
     * object outside the user's chosen zone. Gating those four sites on
     * {@code trackerInZone(q)} preserves the legitimate use cases (a
     * person walking in close gets locked and held through a headlight
     * sweep) while honouring the user's zone choice.
     *
     * <p>The check uses the bbox's <b>bottom edge</b> in row units against
     * the effective {@code maxDistanceRow} for that quadrant. Bbox-bottom
     * mirrors what the row gate uses for motion centroids — bottom-of-
     * silhouette ≈ closest point on the object to the camera ground plane.
     * Returns true when zone gating is disabled ({@code maxRow == 0},
     * "extended"), or when {@code maxRow} is unset.
     *
     * <p>NOTE: the JNI ({@code trackerGetTrackBox}) returns {@code null}
     * when there is no active track, and never returns an inactive box
     * (index 6 is hard-coded to 1.0f on the native side), so the
     * {@code trackBox[6] <= 0} "vacuously in-zone" branch below is
     * effectively dead. With no lock this method returns <b>false</b>
     * (via the null check) UNLESS the zone gate is off. Every legitimate
     * caller therefore pairs this with a {@code trackerHasActiveTrack}
     * guard so the null→false path is not reached for a genuine no-track
     * subject; the close-zone override handles the no-track case itself
     * rather than calling this method.
     *
     * @return true if the tracker bbox bottom is at or below {@code maxRow},
     *         OR the zone gate is off. Returns false when an active lock is
     *         OUTSIDE the configured zone, and also when there is no lock at
     *         all (null box) — callers must guard the no-track case.
     */
    /**
     * Whether a THREAT_HIGH (loiter) in the given quadrant is "trusted" enough
     * to keep the fast, YOLO-exempt 500ms recording path — i.e. it is a real
     * loiterer, not a waving flag / sweeping shadow whose stationary centroid
     * merely looks like loitering.
     *
     * Trusted iff EITHER:
     *   (a) an in-zone PERSON tracker holds the quadrant (classId==0 +
     *       trackerInZone) — a person who walked up and stopped; or
     *   (b) the native flow-coherence signal says the motion is coherently
     *       translating: flowCoherence >= COHERENCE_RATIO_MIN, OR the windowed
     *       cumulative net drift >= COHERENCE_NET_MIN blocks (a slow but steady
     *       approach with low per-frame coherence still accumulates net drift).
     *
     * FAIL-OPEN on the native signal: when the loaded .so doesn't compute
     * coherence (flowCoherence < 0, the pre-Phase-2 state), this method does
     * NOT treat that as "incoherent" — it simply relies on the tracker test
     * (a). The net effect pre-Phase-2 is that an unconfirmed HIGH with no
     * in-zone person tracker is YOLO-gated like MEDIUM, which is exactly the
     * flag-rejection behaviour we want, with the tracker preserving genuine
     * standing-person loiter.
     *
     * @param quadrant best-threat quadrant; if &lt; 0 (e.g. only the
     *                 headlight-immunity branch bumped maxThreat) returns false
     *                 so the caller falls through to its tracker-fallback path.
     */
    private boolean highThreatIsTrusted(int quadrant,
                                        MotionPipelineV2.QuadrantResult result) {
        // (a) In-zone person tracker — genuine standing loiterer keeps fast path.
        if (quadrant >= 0) {
            try {
                if (NativeMotion.trackerHasActiveTrack(quadrant)) {
                    float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
                    if (trackBox != null && trackBox.length >= 7
                            && (int) trackBox[5] == 0  // person
                            && trackerInZone(quadrant)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // (b) Native flow coherence (Phase 2). Negative = unavailable → fail
        // open to the tracker test above (don't demote on a missing signal).
        if (result != null && result.flowCoherence >= 0f) {
            if (result.flowCoherence >= COHERENCE_RATIO_MIN
                    || result.netDriftBlocks >= COHERENCE_NET_MIN) {
                return true;
            }
            return false;  // coherence computed AND incoherent → flag/shadow
        }
        // No coherence signal and no in-zone person tracker → not trusted.
        return false;
    }

    /**
     * Human-readable trust label for the trigger logs. Surfaces WHY a HIGH was
     * (or wasn't) granted the fast path so an on-device debug round can see the
     * flag/shadow demotion without a rebuild. Prints the native coherence values
     * when available, or "n/a" pre-Phase-2.
     */
    private String describeHighTrust(int threat, boolean trusted,
                                     MotionPipelineV2.QuadrantResult r) {
        if (threat < MotionPipelineV2.THREAT_HIGH) return "trust=n/a(<HIGH)";
        String coh = (r != null && r.flowCoherence >= 0f)
                ? String.format("coherence=%.2f netDrift=%.1f", r.flowCoherence, r.netDriftBlocks)
                : "coherence=n/a";
        return String.format("trust=%s(%s)", trusted ? "YES" : "NO→YOLO-gated", coh);
    }

    private boolean trackerInZone(int quadrant) {
        if (config == null) return true;  // no config → no gate
        // Resolve the per-quadrant effective zone (per-quadrant override
        // wins over global). maxDistanceRowForZone returns 0 for "extended"
        // (gate off), 2 for "normal", 4 for "close". Higher = stricter.
        String effZone = config.getEffectiveDetectionZone(quadrant);
        int maxRow = MotionPipelineV2.Config.maxDistanceRowForZone(effZone);
        if (maxRow <= 0) return true;  // gate disabled

        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox == null || trackBox.length < 7) return false;
            // trackBox[6] == active flag; if no active lock, "in zone" is
            // vacuously true so callers don't double-gate themselves.
            if (trackBox[6] <= 0) return true;
            // bbox y is in 0..240 quadrant pixel space; row index = y / 32.
            // Bottom edge of bbox in row units = (y + h) / 32.
            float bottomRow = (trackBox[1] + trackBox[3]) / (float) GRID_BLOCK_SIZE;
            return bottomRow >= maxRow;
        } catch (Throwable t) {
            // Any native failure → fail safe to "in zone" so we don't
            // accidentally suppress a legitimate trigger because the
            // tracker JNI burped.
            return true;
        }
    }

    /**
     * Edge-band sentinel for the tile-edge guard. Validation analysis
     * showed the BYD HAL fisheye dewarp's angular-density-per-pixel is
     * roughly uniform in the central 60% of a tile and degrades steeply
     * in the outer 20% on each side. When the closest detection's bbox
     * center lands in that outer band we don't trust the bbox-height
     * inference and fall back to tier+trend.
     *
     * <p>Returns true if the closest predicted detection's bbox center
     * is in the outermost {@code edgeFrac} of the frame width on either
     * side. Mirrors {@link DistanceEstimator#fromYoloDetections}'s
     * "smallest distance wins" selection so we guard the same
     * detection that would actually be returned.
     */
    private static final float TILE_EDGE_BAND_FRAC = 0.20f;

    private boolean isInTileEdgeBand(java.util.List<com.overdrive.app.ai.Detection> dets,
                                     int frameH) {
        if (dets == null || dets.isEmpty()) return false;
        // Use bbox aspect to back into a sensible frame width assumption.
        // Foveated frames are square (640×640); mosaic/quadrant frames are
        // 4:3 (320×240). SPACE-AWARE since audit R5: the M2 fix publishes
        // foveated detections in QUADRANT space (frameH=240), and this
        // guard is now also invoked for those via the wasFoveated bit — the
        // old square assumption would test x against a 240-wide frame and
        // misread the band.
        final int frameW = frameH >= FOVEATED_CROP_SIZE_PX
                ? frameH : (THUMBNAIL_WIDTH / 2);

        // Pick the closest valid detection (matches the selection rule
        // inside DistanceEstimator.fromYoloDetections).
        com.overdrive.app.ai.Detection closest = null;
        float closestRatio = Float.MAX_VALUE;
        for (com.overdrive.app.ai.Detection d : dets) {
            if (d.getH() <= 0) continue;
            // We only need ranking, not absolute distance — bbox-h ratio
            // is monotonic with closeness given a fixed real height prior.
            float invSize = 1.0f / (float) d.getH();
            if (invSize < closestRatio) {
                closestRatio = invSize;
                closest = d;
            }
        }
        if (closest == null) return false;
        float centerX = closest.getX() + closest.getW() / 2.0f;
        float frac = centerX / (float) frameW;
        return frac < TILE_EDGE_BAND_FRAC || frac > (1.0f - TILE_EDGE_BAND_FRAC);
    }

    /**
     * Compute the row of the lowest active motion block in a quadrant.
     * "Lowest" = highest row index = closest to the bottom of the FOV
     * which (after dewarp) corresponds to closest to the car. Returns -1
     * if no block is confirmed.
     */
    private int lowestActiveBlockRow(MotionPipelineV2.QuadrantResult r) {
        if (r == null || r.blockConfidence == null) return -1;
        // blockConfidence is row-major: index = row * GRID_COLS + col
        for (int row = MotionPipelineV2.GRID_ROWS - 1; row >= 0; row--) {
            for (int col = 0; col < MotionPipelineV2.GRID_COLS; col++) {
                int idx = row * MotionPipelineV2.GRID_COLS + col;
                if (idx < r.blockConfidence.length && r.blockConfidence[idx] > 0f) {
                    return row;
                }
            }
        }
        return -1;
    }

    // Cached per-tick trend per quadrant. The state-update half of
    // proximityForQuadrant runs once per processFrameV2 tick (in the
    // per-quadrant motion summary loop) and writes here; the read-only
    // half consults this cache so subsequent log sites in the same tick
    // (motion-start, motion-building, recording-trigger) report a
    // consistent trend instead of clobbering each other's state.
    //
    // Indexed by quadrant. Cleared to UNKNOWN at engine shutdown.
    private final DistanceEstimator.Trend[] cachedTrend = {
            DistanceEstimator.Trend.UNKNOWN, DistanceEstimator.Trend.UNKNOWN,
            DistanceEstimator.Trend.UNKNOWN, DistanceEstimator.Trend.UNKNOWN };

    /**
     * <b>State-mutating tick.</b> Updates {@code prevLowestBlockY[q]} and
     * caches the per-quadrant trend for downstream read-only callers in
     * the same tick. Call EXACTLY ONCE per quadrant per processFrameV2
     * iteration (currently from the per-quadrant motion summary loop).
     *
     * <p>Audit H2: previously {@link #proximityForQuadrant} mutated
     * prevLowestBlockY on every call. Multiple log sites in one tick
     * (motion-summary → motion-start → motion-building → trigger) hit
     * this path, the second call saw {@code dt=0} between sibling reads,
     * and trendFromBlockY's elapsedMs guard returned UNKNOWN. The trend
     * signal was silently lost on every multi-call frame. Splitting
     * mutation from query fixes that.
     */
    private void updateProximityState(int quadrant, MotionPipelineV2.QuadrantResult result) {
        if (quadrant < 0 || quadrant >= prevLowestBlockY.length) return;
        int lowestNow = lowestActiveBlockRow(result);
        long nowMs = System.currentTimeMillis();

        DistanceEstimator.Trend trend = DistanceEstimator.Trend.UNKNOWN;
        int prevRow = prevLowestBlockY[quadrant];
        long prevMs = prevLowestBlockYAtMs[quadrant];
        if (prevRow >= 0 && prevMs > 0 && lowestNow >= 0) {
            trend = DistanceEstimator.trendFromBlockY(prevRow, lowestNow, nowMs - prevMs);
        }

        // Reset state on quiet quadrants so a 30s-old prevRow doesn't
        // produce a bogus APPROACHING the next time blocks fire (audit M4).
        if (lowestNow < 0) {
            prevLowestBlockY[quadrant] = -1;
            prevLowestBlockYAtMs[quadrant] = 0;
        } else {
            prevLowestBlockY[quadrant] = lowestNow;
            prevLowestBlockYAtMs[quadrant] = nowMs;
        }
        cachedTrend[quadrant] = trend;
    }

    /**
     * SOTA proximity estimate for a given quadrant. <b>Read-only.</b>
     * Composes:
     *   1. <b>Technique A</b> (preferred) — class-conditional bbox-height
     *      inference from the latest YOLO detections in this quadrant.
     *      Uses the coord-space frame height the AI ran against
     *      (mosaic 240 vs foveated 640) so the focal/bbox ratio is
     *      coherent. Picks the closest predicted detection (audit M2).
     *   2. <b>Technique B</b> (fallback) — discrete tier from motion-block
     *      density + lowest-active-row, plus trend from the per-tick
     *      cache. No metric distance.
     *
     * <p>Idempotent within a tick — does not mutate state. Call
     * {@link #updateProximityState} once per tick before any read sites
     * fire to keep the trend signal current.
     */
    private DistanceEstimator.ProximityEstimate proximityForQuadrant(
            int quadrant, MotionPipelineV2.QuadrantResult result) {
        DistanceEstimator.Trend trend = (quadrant >= 0 && quadrant < cachedTrend.length)
                ? cachedTrend[quadrant]
                : DistanceEstimator.Trend.UNKNOWN;

        // Technique A: try the latest YOLO detections for this quadrant.
        // Single atomic read produces the (detections, frameHeight) pair
        // atomically — no torn read between two sibling atomics. Either
        // we see the previous tick's complete YoloPublication or we see
        // the new tick's; never new detections paired with old frame H.
        if (quadrant >= 0 && quadrant < lastYoloPublication.length()) {
            YoloPublication pub = lastYoloPublication.get(quadrant);
            if (pub != null && pub.triggerDetections != null
                    && !pub.triggerDetections.isEmpty()) {
                int frameH = pub.frameHeightPx > 0 ? pub.frameHeightPx : (THUMBNAIL_HEIGHT / 2);
                // Per-quadrant base FOV from the active CameraProfile.
                // Side-camera FOV is materially narrower than front/rear,
                // and a single 110° constant was producing ~70%-high
                // estimates on side cameras per validation analysis.
                float baseFovDeg = (quadrant < cameraVerticalFovDeg.length)
                        ? cameraVerticalFovDeg[quadrant]
                        : DEFAULT_VERTICAL_FOV_DEG[0];

                // Foveated crops sample a sub-window of one tile so
                // their effective vertical FOV is narrower:
                // baseFOV × (CROP_SIZE / stripHeight).
                final float fovDeg;
                final boolean isFoveated = frameH >= FOVEATED_CROP_SIZE_PX;
                if (isFoveated) {
                    int strip = cameraStripHeightPx > 0 ? cameraStripHeightPx : 960;
                    float scale = (float) FOVEATED_CROP_SIZE_PX / (float) strip;
                    if (scale > 1f) scale = 1f;  // foveated FOV can never exceed tile FOV
                    fovDeg = baseFovDeg * scale;
                } else {
                    fovDeg = baseFovDeg;
                }

                // Tile-edge guard (validation report recommendation #2).
                // The HAL fisheye dewarp is non-uniform: angular density
                // per pixel varies across the tile. Bbox-height inference
                // assumes locally affine projection, which holds near tile
                // center but degrades at the edges (errors of 30-50% in
                // the outer 20% of the tile). When the closest valid
                // detection's bbox center is in that outer band, drop to
                // tier-only — honest "near approaching" beats a wrong
                // metric number. Only applies to foveated detections
                // (the moving window can land at any tile-X); mosaic
                // quadrants are bounded to one camera's tile and don't
                // have this edge problem.
                // wasFoveated (audit R5 / R4 coords #1): the M2 quadrant-
                // space publication made frameH-based isFoveated false for
                // mappable foveated runs, silently disabling this guard —
                // the bit restores it regardless of publication space.
                if ((isFoveated || pub.wasFoveated)
                        && isInTileEdgeBand(pub.triggerDetections, frameH)) {
                    // Fall through to Technique B below.
                } else {
                    DistanceEstimator.ProximityEstimate est =
                            DistanceEstimator.fromYoloDetections(
                                    pub.triggerDetections, frameH, fovDeg, trend);
                    if (est != null) return est;
                }
            }
        }

        // Technique B: pre-YOLO tier + trend. Honest absence of meters.
        // We re-scan the lowest active block here (cheap; just an O(70)
        // pass over blockConfidence) rather than caching it, because
        // tierFromMotion needs the *current* result not what
        // updateProximityState saw — multiple log sites can be reading
        // results at slightly different points within one tick.
        int lowestNow = lowestActiveBlockRow(result);
        DistanceEstimator.Tier tier = DistanceEstimator.tierFromMotion(
                result != null ? result.activeBlocks : 0,
                lowestNow,
                MotionPipelineV2.GRID_ROWS);
        return DistanceEstimator.ProximityEstimate.tierOnly(tier, trend);
    }
    
    /**
     * Gets the last temporal blocks count (blocks with temporal consistency).
     * 
     * @return Number of temporally consistent blocks
     */
    public int getLastTemporalBlocksCount() {
        return lastTemporalBlocksCount;
    }
    
    /**
     * Gets the last motion bounding box Y coordinates.
     * 
     * @return int array [minY, maxY] or null if no motion
     */
    public int[] getLastMotionBounds() {
        if (lastMotionMaxY > lastMotionMinY) {
            return new int[] { lastMotionMinY, lastMotionMaxY };
        }
        return null;
    }
    
    /**
     * Gets class name from COCO class ID.
     */
    private String getClassName(int classId) {
        switch (classId) {
            case 0: return "person";
            case 2: return "car";
            case 3: return "motorcycle";
            case 5: return "bus";
            case 7: return "truck";
            default: return "object_" + classId;
        }
    }
    
    /**
     * Sets object detection filters.
     * 
     * Also adjusts motion detection sensitivity based on minSize:
     * - Lower minSize (for distant objects) = lower motion sensitivity
     * - Higher minSize (for close objects) = higher motion sensitivity
     * 
     * @param minSize Minimum object size (0.0-1.0, fraction of frame area)
     * @param confidence Minimum confidence (0.0-1.0)
     * @param detectPerson Enable person detection
     * @param detectCar Enable car detection
     * @param detectBike Enable bike detection
     * @param detectAnimal Enable animal detection (COCO 14-23)
     */
    public void setObjectFilters(float minSize, float confidence,
                                 boolean detectPerson, boolean detectCar, boolean detectBike,
                                 boolean detectAnimal) {
        this.minObjectSize = minSize;
        this.aiConfidence = confidence;

        // Build class filter for YOLO
        java.util.ArrayList<Integer> classes = new java.util.ArrayList<>();
        if (detectPerson) classes.add(0);  // COCO: person
        if (detectCar) {
            classes.add(2);  // COCO: car
            classes.add(5);  // COCO: bus
            classes.add(7);  // COCO: truck
        }
        if (detectBike) {
            classes.add(1);  // COCO: bicycle
            classes.add(3);  // COCO: motorcycle
        }
        if (detectAnimal) {
            // COCO 14-23: bird, cat, dog, horse, sheep, cow, elephant, bear,
            // zebra, giraffe. Same range the YoloDetector animal mask uses
            // and that Actor.classGroupFor() maps to ClassGroup.ANIMAL.
            for (int c = 14; c <= 23; c++) classes.add(c);
        }

        // FIX (Bug B): empty list now means "user disabled all classes" — represented as
        // an empty array (sentinel) rather than null. The hot-path guard short-circuits
        // YOLO entirely. This also lets us unload the TFLite interpreter to reclaim
        // ~50MB of native memory until detection is re-enabled.
        boolean hadAi = this.aiEnabled;
        if (classes.isEmpty()) {
            classFilter = new int[0];
            this.aiEnabled = false;
        } else {
            classFilter = new int[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                classFilter[i] = classes.get(i);
            }
            this.aiEnabled = true;
        }
        // Invalidate in-flight inference (audit R11-8 / ExtD-9): a lambda
        // dispatched under the OLD filter re-checks this epoch post-detect
        // and drops its publication, so it can neither re-seed the tracks the
        // loop below drops nor re-stamp person recency for a disabled class.
        // Published as ONE immutable (filter, epoch) pair (audit R13-5 /
        // ExtE-5) so a concurrent dispatch can never pair the new filter
        // with the old epoch or vice versa.
        classConfig = new ClassConfig(classFilter, classConfigEpoch.incrementAndGet());

        // Unload YOLO when AI is now off; load lazily again on next call when re-enabled
        // (lazy re-init is handled where YoloDetector is constructed in init()).
        if (!aiEnabled && yoloDetector != null) {
            try {
                yoloDetector.close();
            } catch (Exception e) {
                logger.warn("YoloDetector close failed: " + e.getMessage());
            }
            yoloDetector = null;
            logger.info("YOLO detector closed: all object classes disabled by user");
        } else if (aiEnabled && !hadAi) {
            logger.info("Object detection re-enabled; YOLO will be reloaded on next inference");
        }

        logger.info(String.format("Object filters: minSize=%.1f%%, confidence=%.0f%%, aiEnabled=%s, classes=%s",
                minSize * 100, confidence * 100, aiEnabled, classes));

        // DROP-ON-TOGGLE (audit R7 ExtC-9): existing native tracks of a
        // now-disabled class kept certifying immunity and extending a live
        // recording after the toggle — for ~5-8s via the heartbeat teardown
        // when other classes stayed on, and all the way to the 3× postRecord
        // hard ceiling when ALL classes were disabled (the teardown gate
        // lives inside runAiOnQuadrant, which bails before it when
        // aiEnabled=false — the track could then never die). Drop tracks
        // whose canonical class group is no longer enabled, at the moment of
        // the toggle. The recency stamps (lastPersonConfirmationTimeMs etc.)
        // then age out on their existing ≤5s TTLs with nothing refreshing
        // them.
        try {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (!NativeMotion.trackerHasActiveTrack(q)) continue;
                float[] tb = NativeMotion.trackerGetTrackBox(q);
                if (tb == null) continue;
                int trackCls = (int) tb[5];
                boolean stillEnabled = false;
                if (!classes.isEmpty()) {
                    int trackCanon = canonicalClassId(trackCls);
                    for (int c : classes) {
                        if (canonicalClassId(c) == trackCanon) {
                            stillEnabled = true;
                            break;
                        }
                    }
                }
                if (!stillEnabled) {
                    NativeMotion.trackerDropTrack(q);
                    logger.info("Dropped Q" + q + " track (class " + trackCls
                        + ") — class disabled by user toggle");
                }
            }
        } catch (Throwable t) {
            // Adding-only hygiene: a failed drop just leaves the track to the
            // pre-existing TTL/heartbeat lifecycle.
        }

        // Lazily reload YOLO if it was previously closed and we now need it again
        if (aiEnabled && yoloDetector == null) {
            reloadYoloDetectorIfPossible();
        }
    }

    /**
     * (Bug B helper) Lazily re-initialise the YOLO detector after a previous unload.
     * Uses the same Context/AssetManager paths as the original init() so daemon and
     * regular runs both work. Idempotent and safe to call when AI is already loaded.
     */
    private void reloadYoloDetectorIfPossible() {
        if (yoloDetector != null) return;
        try {
            // Audit fix: construct + init() into a LOCAL and publish to the
            // field only after init() succeeds. Publishing first let a queued
            // aiExecutor lambda snapshot a half-initialized instance (worst
            // case a caught NPE inside interpLock — one lost inference per
            // class-filter toggle — but the ordering was still wrong).
            final YoloDetector candidate;
            if (yoloContext != null) {
                candidate = new YoloDetector(yoloContext);
            } else if (yoloAssetManager != null) {
                candidate = new YoloDetector(new com.overdrive.app.ai.AssetContext(yoloAssetManager));
            } else {
                logger.warn("Cannot reload YOLO: no context/assetManager retained");
                return;
            }
            boolean ok = candidate.init();
            if (ok) {
                yoloDetector = candidate;
                useObjectDetection = true;
                logger.info("YOLO detector reloaded (object detection re-enabled)");
            } else {
                logger.warn("YOLO reload failed");
            }
        } catch (Exception e) {
            logger.warn("YOLO reload threw: " + e.getMessage());
        }
    }
    
    /**
     * Publish a motion notification onto the cross-cutting NotificationBus.
     *
     * <p>Filename is the not-yet-finalized {@code currentEventFile} name —
     * recording is still in progress when this fires. Delivering the push
     * immediately (rather than waiting for finalization) prioritizes alert
     * latency over tap-to-play polish; the events page will surface a
     * "still recording" state until the file closes.
     */
    /**
     * Send a Telegram motion notification enriched with the current Actor
     * snapshot. Falls back to a generic "motion" payload when no Actors are
     * known yet (e.g. recording started purely on motion before YOLO ran).
     * Honours the user's notification tier toggles (item 8).
     */
    private void sendRichMotionNotifications(String videoFilename) {
        // User opt-out: by default, Telegram only gets the recording-CLOSE
        // photo (sendFinalTelegramNotification, fired from stopRecording). The
        // start-stage text message is suppressed because the user-visible end
        // result is two messages back-to-back — same content, no replace
        // semantics in Telegram. Telegram-only users who want low-latency
        // pings can flip telegramSendStartPing on in Sentry settings.
        //
        // Treat null config as "default" (off). Without this, an early-startup
        // motion event before config has been wired would leak through with
        // legacy "always send" behaviour, contradicting the documented default.
        if (config == null || !config.isTelegramSendStartPing()) {
            return;
        }
        java.util.List<Actor> snap = lastActors;
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        // Per-tier muting for the web push system happens device-side via
        // muted-categories. Telegram has its own subscription model; we still
        // pass severity so the daemon can format the message accordingly.
        // Static actors (parked cars next to ours) MUST NOT count or be picked
        // as the detection label — see publishMotionNotification for the same
        // reasoning.
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        String detectionLabel = "motion";
        Actor threat = null;
        long nowMs = System.currentTimeMillis();
        // One stable scenery verdict for the whole loop — observe() is still
        // mutating the buffer on aiExecutor while this runs. See snapshotSceneryIds.
        final java.util.Set<Long> sceneryIds = snapshotSceneryIds(snap);
        for (Actor a : snap) {
            // FRESHNESS GATE: the tracker retains actors for TRACK_TTL_MS (5s)
            // after they leave; a caption must describe the LIVE scene, so skip
            // any actor not observed within ACTOR_CAPTION_FRESHNESS_MS. Without
            // this, a person who crossed and left keeps captioning the event as
            // the threat for up to 5s ("Person at front" when none is there).
            if (!isActorFresh(a, nowMs)) continue;
            // Keep a static PERSON (loiterer = the threat, gated CRITICAL); skip
            // only non-person statics (parked cars, forced to NOTICE anyway).
            if (a.isStaticForTimeline && a.classGroup != Actor.ClassGroup.PERSON) continue;
            // Skip an UNCONFIRMED (1-2 frame flicker) person, exactly as both
            // FINAL surfaces do. Without this the two Telegram messages for one
            // clip contradict each other: a 2-frame YOLO flicker on a swaying
            // bush is fresh and outranks a real vehicle on class rank, so the
            // start ping says "1 person" while the final caption — which drops
            // !confirmed persons — says "1 vehicle, 0 persons". Cannot suppress
            // the ping: its gate is shouldTelegram(peakSev) and peakSev comes
            // from the UNFILTERED snap above.
            if (a.classGroup == Actor.ClassGroup.PERSON && !a.confirmed) continue;
            // Drop the low-conf FAR NOTICE FP so the caption agrees with the card
            // + hero (both suppress it). See isLowConfFarNotice.
            if (isLowConfFarNotice(a)) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            // Shared ordering (severity → live-beats-static → class) so the
            // headline agrees with the hero thumbnail. Counts above are untouched.
            if (beatsAsThreat(a, threat, sceneryIds)) {
                threat = a;
            }
        }
        // ALL-SCENERY POOL ⇒ describe the event as motion, not as the parked car.
        // The live-beats-scenery tier only ORDERS candidates; when every candidate
        // is scenery the comparison falls through to class rank and a parked car
        // still wins the headline. ThumbnailBuffer.pickHero instead DROPS every
        // scenery slot and returns null, routing the caller to a bare MP4 keyframe
        // — so the caption would name a vehicle over a photo with no vehicle box,
        // for a car that never moved. Counts are deliberately left intact (they are
        // an inventory of what was classified). The camera stays: WHERE the motion
        // was is still true, it is only the class claim that isn't.
        Actor camAnchor = threat;
        if (threat != null && isScenery(threat, sceneryIds)) {
            threat = null;
        }
        // camHint follows the threat actor so the title's "X at <camera>" phrase
        // names the camera that saw X, not whichever actor happened to be closest.
        String camHint = cameraNameFor(camAnchor);
        float bestConf = threat != null ? threat.peakConfidence : 0f;
        if (threat != null) detectionLabel = Actor.groupLabel(threat.classGroup);
        // Telegram tier mute — mirrors the push tier toggles so a
        // Telegram-only user can keep CRITICAL/ALERT and silence NOTICE.
        if (!com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config)) {
            logger.debug("Telegram start-stage suppressed by per-tier toggle (sev=" + peakSev + ")");
            return;
        }
        try {
            TelegramNotifier.notifyMotion(
                    detectionLabel,
                    bestConf > 0f ? bestConf : 1.0f,
                    videoFilename,
                    peakSev != null ? peakSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram notify failed: " + t.getMessage());
        }
    }

    /**
     * Actor set for the FINAL (recording-end) notifications and caption. The
     * live {@code lastActors} snapshot is TTL-pruned (5s), so an actor that was
     * significant during the event but departed before it ended (a person who
     * came very-close then walked away) has been erased — leaving a lingering
     * far car as the caption subject while the HERO thumbnail (captured at the
     * event-peak frame) correctly shows the close person. That mismatch is the
     * "caption says Vehicle/FAR but the hero shows a close person" bug.
     *
     * <p>This unions the retained {@link #eventPeakActors} (each actor at its
     * most-significant moment across the whole event — same set that feeds the
     * JSON/SRT/stats headline) with the live snapshot, de-duped by actorId with
     * the live copy winning (freshest for an actor still present). The result is
     * the caption built from the SAME actors the hero was chosen from, so the
     * two agree. Returns the live snapshot unchanged when no peak actors were
     * retained (e.g. a motion-only event with no AI classification).
     */
    private java.util.List<Actor> finalNotificationActors() {
        java.util.List<Actor> live = lastActors;
        if (eventPeakActors.isEmpty()) {
            return live != null ? live : java.util.Collections.<Actor>emptyList();
        }
        // Multi-segment guard (mirror of scheduleSegmentMetadataFlushWithSnapshot):
        // the final push / Telegram caption + hero describe the FINAL clip only, so
        // drop a peak-retained actor that was entirely gone before the final
        // segment's window began — otherwise a multi-segment event names/counts an
        // actor absent from the final clip. recordingStartTimeMs is not reset
        // between the final flushSegmentMetadata and the publish calls, so it is
        // the correct final-segment anchor. No-op for single-segment events (the
        // departed-close-person's lastSeenWallMs lies within the only segment).
        final long segmentStartMs = timelineCollector.getRecordingStartTimeMs();
        java.util.Map<Long, Actor> merged = new java.util.LinkedHashMap<>();
        for (Actor a : eventPeakActors.values()) {
            if (segmentStartMs > 0 && a.lastSeenWallMs > 0 && a.lastSeenWallMs < segmentStartMs) {
                continue;
            }
            merged.put(a.actorId, a);
        }
        if (live != null) {
            for (Actor a : live) merged.put(a.actorId, a); // live wins
        }
        return coalesceReenteredPersons(new java.util.ArrayList<>(merged.values()));
    }

    // The depart→re-enter merge is only valid within ONE physical person's
    // track-TTL window: a track TTL-prunes after ~8s out of YOLO range and the
    // same person re-entering gets a fresh actorId. A gap LONGER than this means
    // they were gone long enough to be a genuinely SEPARATE person — must NOT be
    // collapsed. Mirrors ActorTracker.TRACK_TTL_MS (kept local to avoid exposing
    // a private constant); a small margin covers scheduling jitter.
    private static final long REENTER_COALESCE_WINDOW_MS = 8000L;

    /**
     * Collapse a depart-and-re-enter PERSON double-count. The actorId-keyed union
     * keeps BOTH a retained eventPeakActors copy (old id, person left) AND the live
     * re-entered copy (new id, assigned because the track TTL-pruned while they were
     * out of YOLO range) — one physical person counted twice ("2 people" caption,
     * +1 personCount). Drop the OLDER person entry ONLY when a same-class PERSON
     * entry began after it was last seen AND within REENTER_COALESCE_WINDOW_MS (the
     * plausibly-same-person window) AND sharing a camera quadrant. Those three
     * guards together prevent collapsing two GENUINELY DISTINCT sequential people
     * (the dangerous case: dropping a CRITICAL departed person in favour of a later
     * NOTICE passer-by). Keeps the later (live) copy, which wins the union for
     * severity/hero. Non-person and temporally-overlapping persons are untouched.
     */
    private static java.util.List<Actor> coalesceReenteredPersons(java.util.List<Actor> actors) {
        if (actors == null || actors.size() < 2) return actors;
        java.util.List<Actor> out = new java.util.ArrayList<>(actors.size());
        for (Actor a : actors) {
            if (a.classGroup != Actor.ClassGroup.PERSON) { out.add(a); continue; }
            boolean supersededByReentry = false;
            for (Actor b : actors) {
                if (b == a || b.classGroup != Actor.ClassGroup.PERSON) continue;
                if (a.lastSeenWallMs <= 0) continue;
                long gap = b.firstSeenWallMs - a.lastSeenWallMs;
                // b began after a left, within the same-person TTL window, in an
                // overlapping quadrant → b is a's re-entry, not a new person.
                // SEVERITY-MONOTONE GUARD: never drop the MORE-significant copy. A
                // true same-person re-entry carries that person's accumulated peak
                // into the live copy b (so b >= a and still collapses), whereas a
                // distinct CRITICAL/closer departed person `a` superseded by a later
                // NOTICE passer-by `b` is PRESERVED — otherwise the headline
                // severity, personCount, and closest-proximity would silently
                // downgrade to the lesser later person.
                if (gap >= 0 && gap <= REENTER_COALESCE_WINDOW_MS
                        && (a.cameraMask & b.cameraMask) != 0
                        && !isMoreSignificant(a, b)) {
                    supersededByReentry = true;
                    break;
                }
            }
            if (!supersededByReentry) out.add(a);
        }
        return out;
    }

    /**
     * Final Telegram notification at recording-end. Computes the same actor
     * summary as {@link #sendRichMotionNotifications} but routes via
     * {@code notifyMotionFinalized} so the daemon sends a photo (with the hero
     * JPEG as the image and the threat summary as the caption) instead of a
     * text-only message. Falls back gracefully on the daemon side if the photo
     * can't be sent.
     */
    private void sendFinalTelegramNotification(String videoFilename, String heroPhotoPath) {
        // Legacy entry: computes the union/scenery/live snapshots from live
        // fields. stopRecording no longer uses this — it passes its stop-time
        // snapshots (audit R8-2 / ExtC-3: lastActors, thumbnailBuffer and
        // currentEventFile are cleared before the publish tail runs).
        java.util.List<Actor> liveSnap = finalNotificationActors();
        sendFinalTelegramNotification(videoFilename, heroPhotoPath,
                liveSnap, lastActors, snapshotSceneryIds(liveSnap), currentEventFile, null);
    }

    /** Snapshot variant (audit R8-2 / ExtC-3): consumes the caller's actor
     *  union, live-actor list, scenery verdicts and event file so no live
     *  shared field is read after stopRecording's early clears. */
    private void sendFinalTelegramNotification(String videoFilename, String heroPhotoPath,
                                               java.util.List<Actor> snapIn,
                                               java.util.List<Actor> liveActors,
                                               java.util.Set<Long> sceneryIdsIn,
                                               File eventFile,
                                               com.overdrive.app.camera.OemDashcamPipeline.FinalizedClip
                                                       oemFinalizedClip) {
        // Event-peak union (not the TTL-pruned lastActors) so the caption names
        // the same actor the hero shows — see finalNotificationActors().
        java.util.List<Actor> snap = snapIn != null
                ? snapIn : java.util.Collections.<Actor>emptyList();
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        // MOTION-EVIDENCE SEVERITY FLOOR (notification fix) — mirror of
        // publishMotionFinal. A YOLO-less event (0 actors) collapses to the
        // NOTICE floor, so a trusted-HIGH / NEAR / approaching trigger the engine
        // recorded without YOLO would otherwise be gated by the Telegram Notice
        // tier (off by default) and never reach the chat. Floor to ALERT so the
        // real approach routes through the Alert tier + header. Only ever raises
        // NOTICE→ALERT; a real ALERT/CRITICAL actor is untouched. Gated on the
        // same narrow eventTriggerWasStrongThreat latch.
        if (peakSev == Actor.Severity.NOTICE && eventTriggerWasStrongThreat) {
            peakSev = Actor.Severity.ALERT;
        }
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        Actor threat = null;
        // One stable scenery verdict for the whole loop — snapshotted by the
        // caller (audit R8-2 / ExtC-3), see snapshotSceneryIds.
        final java.util.Set<Long> sceneryIds = sceneryIdsIn != null
                ? sceneryIdsIn : java.util.Collections.<Long>emptySet();
        for (Actor a : snap) {
            // Keep a static PERSON so the body matches the CRITICAL the gate
            // already sends for a loiterer; skip only non-person statics.
            if (a.isStaticForTimeline && a.classGroup != Actor.ClassGroup.PERSON) continue;
            // Skip an UNCONFIRMED (1-2 frame YOLO flicker) person so the caption
            // count matches the event-card headline (EventTimelineCollector also
            // excludes !confirmed persons) and the eventPeakActors retention path
            // (which only retains confirmed persons). Without this, a flicker
            // person could caption "1 person" on a clip whose card shows 0.
            if (a.classGroup == Actor.ClassGroup.PERSON && !a.confirmed) continue;
            // Drop the low-conf FAR NOTICE FP so the caption count agrees with the
            // card + hero (both suppress it). See isLowConfFarNotice.
            if (isLowConfFarNotice(a)) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            // Shared ordering (severity → live-beats-static → class) so the
            // headline agrees with the hero thumbnail. Counts above are untouched.
            if (beatsAsThreat(a, threat, sceneryIds)) {
                threat = a;
            }
        }
        // ALL-SCENERY POOL ⇒ don't name the parked car; pickHero returns null for
        // this pool and the photo degrades to a bare MP4 keyframe. See the same
        // guard in sendRichMotionNotifications. Counts stay (inventory).
        Actor camAnchor = threat;
        if (threat != null && isScenery(threat, sceneryIds)) {
            threat = null;
        }
        // camHint follows the threat actor — see sendRichMotionNotifications.
        String camHint = cameraNameFor(camAnchor);
        // Telegram tier mute. By stop time, an actor that was CRITICAL mid-event
        // may have been TTL-pruned from lastActors, collapsing the instantaneous
        // snapshot to NOTICE and silently dropping the closing photo+video of a
        // genuinely severe event. So consider BOTH the instantaneous snapshot
        // and the event-level peak latched across the whole recording.
        //
        // The tier toggles (tierNotices/tierAlerts/tierCritical) are INDEPENDENT
        // booleans, NOT an ordinal threshold — so we can't just gate on the max
        // (that could flip SEND→SUPPRESS when a lower tier is on and a higher
        // tier off). Send if EITHER severity passes its own toggle: this never
        // suppresses anything the old instantaneous gate would have sent, and
        // additionally rescues the receded-CRITICAL case.
        boolean snapOk = com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config);
        // Only let the latch CONTRIBUTE when it was actually observed. A null
        // latch (no actor ever classified this event) must NOT fail-open via
        // shouldTelegram(null)=true — that would force-send actor-less events
        // and break the default NOTICE-suppression. Snapshot remains the
        // baseline; the latch only ever ADDS a reason to send.
        boolean peakOk = eventPeakSeverity != null
                && com.overdrive.app.notifications.NotificationGate.shouldTelegram(eventPeakSeverity, config);
        // LIVE disjunct (mirrors publishMotionFinal). `snap` is the event-peak
        // UNION, so peakSev=maxSeverity(snap) reflects the MAX — which, with the
        // INDEPENDENT (non-ordinal) tier toggles, can MASK a still-present lower
        // tier in an inverted config (e.g. Notices ON, Alerts OFF: a retained
        // departed ALERT person makes peakSev=ALERT → snapOk=false, suppressing a
        // live NOTICE actor HEAD would have sent). Restore the live snapshot's own
        // reason-to-send so nothing the old instantaneous gate sent is suppressed.
        // (audit R8-2 / ExtC-3) liveActors is the caller's stop-time snapshot
        // of lastActors — the field is cleared before this runs.
        boolean liveOk = com.overdrive.app.notifications.NotificationGate.shouldTelegram(
                com.overdrive.app.notifications.NotificationGate.maxSeverity(liveActors), config);
        if (!liveOk && !snapOk && !peakOk) {
            logger.debug("Telegram final-stage suppressed by per-tier toggle (eventPeak="
                    + eventPeakSeverity + ", snapshot=" + peakSev + ")");
            return;
        }
        // Report the higher of the two as the header severity so a receded
        // CRITICAL still reads CRITICAL when that's why we're sending.
        Actor.Severity gateSev = maxOf(eventPeakSeverity, peakSev);
        try {
            TelegramNotifier.notifyMotionFinalized(
                    videoFilename,
                    heroPhotoPath,
                    // Report the event-level peak so the message header matches
                    // the gate decision (a receded CRITICAL still reads CRITICAL).
                    gateSev != null ? gateSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram finalized notify failed: " + t.getMessage());
        }

        // Surveillance video upload. We're past the shouldTelegram() tier gate
        // above, so the video honours the same per-severity toggle as the text
        // + photo — fixing "NOTICE muted but the clip still arrives". The
        // generic recorder (HardwareEventRecorderGpu) deliberately does NOT
        // auto-send event_*.mp4 for exactly this reason; this is the single
        // gated send for surveillance clips. notifyVideoRecorded applies its
        // own videoUploads gate, so the net rule is "tier enabled AND video
        // uploads on" — the user's expected behaviour. heroPhotoPath is the
        // absolute hero path, so its parent is the event directory; derive the
        // clip's absolute path from there (videoFilename is bare).
        try {
            String videoPath = null;
            if (heroPhotoPath != null && !heroPhotoPath.isEmpty()) {
                java.io.File heroFile = new java.io.File(heroPhotoPath);
                java.io.File parent = heroFile.getParentFile();
                if (parent != null) {
                    videoPath = new java.io.File(parent, videoFilename).getAbsolutePath();
                }
            }
            if (videoPath == null && eventFile != null) {
                // Fallback when no hero was written (text-only short clips):
                // eventFile is the caller's snapshot of the just-closed event's
                // absolute path (audit R8-2 / ExtC-3: the currentEventFile
                // field is nulled before the publish tail runs).
                videoPath = eventFile.getAbsolutePath();
            }
            if (videoPath != null && new java.io.File(videoPath).exists()) {
                int durationSec = 0;
                try {
                    HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                    if (enc != null) durationSec = enc.getLastFinalizedDurationSec();
                } catch (Throwable ignored) {}
                String label = threat != null ? Actor.groupLabel(threat.classGroup) : null;
                TelegramNotifier.notifyVideoRecorded(videoPath, label, durationSec);
            } else {
                logger.debug("Surveillance video upload skipped — clip path unresolved/missing");
            }
        } catch (Throwable t) {
            logger.debug("Telegram surveillance video send failed: " + t.getMessage());
        }

        // This OEM mirror was opened SURVEILLANCE_GATED, so deliver it only
        // after the same parent-event tier gate above has passed.
        try {
            if (oemFinalizedClip != null
                    && oemFinalizedClip.getPath() != null
                    && new java.io.File(oemFinalizedClip.getPath()).exists()) {
                String label = threat != null ? Actor.groupLabel(threat.classGroup) : null;
                TelegramNotifier.notifyVideoRecorded(
                        oemFinalizedClip.getPath(), label,
                        oemFinalizedClip.getDurationSeconds());
            }
        } catch (Throwable t) {
            logger.debug("Telegram OEM surveillance video send failed: " + t.getMessage());
        }
    }

    /**
     * Should this actor beat {@code threat} as the scene's headline actor?
     *
     * <p>Single source of truth for the three notification/caption threat picks
     * (rich Telegram start, final Telegram, final push) so they cannot drift.
     *
     * <p>Ordering mirrors {@link ThumbnailBuffer}'s hero score: severity first,
     * then LIVE-beats-STATIC, then class rank. The live-vs-static term is what
     * keeps the headline agreeing with the thumbnail — the hero skips actors the
     * motion mask judged to be stationary scenery, and that verdict fires exactly
     * where the Actor layer's {@code isStaticForTimeline} latch cannot (its
     * historyCount>=3 floor is unreachable at the ~2 Hz YOLO cadence). Without it
     * a mask-demoted parked car is absent from the hero yet still wins the
     * headline on class rank, captioning "vehicle at rear" over a thumbnail
     * depicting the real actor.
     *
     * <p>Applied ONLY to the threat pick, never to the per-class COUNTS: those
     * three loops also accumulate persons/vehicles/bikes/animals and `closest`.
     * Skipping actors outright would make the three surfaces report different
     * counts for one event. publishMotionFinal latches {@code threatClassified}
     * from this loop's result BEFORE the all-scenery demotion and gates pushWorthy
     * on that, so no headline decision can suppress a web push.
     *
     * @param scenery pre-snapshotted scenery actorIds from
     *                {@link #snapshotSceneryIds}. Passed in rather than queried
     *                per comparison because the hero buffer is mutated
     *                concurrently by observe() on aiExecutor — a live query made
     *                this relation unstable within a single loop.
     */
    private boolean beatsAsThreat(Actor a, Actor threat, java.util.Set<Long> scenery) {
        if (a == null) return false;
        if (threat == null) return true;
        int aSev = a.peakSeverity != null ? a.peakSeverity.ordinal() : 0;
        int tSev = threat.peakSeverity != null ? threat.peakSeverity.ordinal() : 0;
        if (aSev != tSev) return aSev > tSev;
        int aLive = isScenery(a, scenery) ? 0 : 1;
        int tLive = isScenery(threat, scenery) ? 0 : 1;
        if (aLive != tLive) return aLive > tLive;
        int aCls = classRank(a.classGroup);
        int tCls = classRank(threat.classGroup);
        if (aCls != tCls) return aCls > tCls;
        // Tie-break on proximity then confidence, mirroring ThumbnailBuffer.score()'s
        // remaining two terms. Without these the headline resolved a (sev, live,
        // class) tie by ITERATION ORDER while the hero resolved it by proximity, so
        // with two same-class actors the title could name the far one ("Vehicle at
        // front") over a thumbnail depicting the closer one. Lower Proximity ordinal
        // = closer (VERY_CLOSE first), hence the inverted comparison.
        int aProx = a.peakProximity != null ? a.peakProximity.ordinal() : Integer.MAX_VALUE;
        int tProx = threat.peakProximity != null ? threat.peakProximity.ordinal() : Integer.MAX_VALUE;
        if (aProx != tProx) return aProx < tProx;
        return a.peakConfidence > threat.peakConfidence;
    }

    /**
     * Snapshot the hero buffer's motion-grounded scenery verdict for a whole
     * actor list, ONCE, before a threat loop runs.
     *
     * <p>Why a snapshot instead of asking per comparison: {@code observe()} runs
     * on aiExecutor and keeps mutating {@code staticNonThreat} while the START
     * ping's threat loop executes on the engine thread (the final surfaces are
     * safe — they run after the drain froze the verdicts). Querying inside
     * {@link #beatsAsThreat} therefore did two bad things at once. It made the
     * relation UNSTABLE — the same actor could read live in one comparison and
     * scenery in the next, so the loop's winner depended on iteration order and
     * on when the AI lane happened to land its Nth zero-coverage sample — and it
     * made ~2N synchronized calls into the buffer per loop instead of N.
     *
     * <p>A PERSON is never scenery (a loiterer IS the threat), matching the
     * per-actor rule the old helper applied.
     */
    private java.util.Set<Long> snapshotSceneryIds(java.util.List<Actor> actors) {
        ThumbnailBuffer tb = thumbnailBuffer;
        if (tb == null || actors == null || actors.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Actor a : actors) {
            if (a == null || a.classGroup == Actor.ClassGroup.PERSON) continue;
            if (tb.isActorStaticNonThreat(a.actorId)) ids.add(a.actorId);
        }
        return ids;
    }

    /** Is this actor stationary scenery, per a {@link #snapshotSceneryIds} set?
     *  Absent set / absent id ⇒ false, so behaviour is unchanged wherever the
     *  hero buffer had no verdict to offer. */
    private static boolean isScenery(Actor a, java.util.Set<Long> scenery) {
        return a != null && scenery != null && scenery.contains(a.actorId);
    }

    /**
     * Rank a class group for "which actor is the threat in this scene". Higher
     * = more important to surface. Mirrors {@link ThumbnailBuffer}'s scoring so
     * the notification title agrees with the thumbnail.
     */
    /**
     * Collapse interchangeable COCO classes so a per-frame YOLO class flip on the
     * same physical object is not read as a different object. Mirrors
     * {@code DetectionBaseline.canonicalClass} and {@code CrossQuadrantTracker}'s
     * copy of the same mapping.
     */
    private static int canonicalClassId(int classId) {
        if (classId == 5 || classId == 7) return 2;   // bus, truck → car
        if (classId == 3) return 1;                   // motorcycle → bicycle
        return classId;
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

    /** How recently an actor must have been observed to be eligible to drive a
     *  caption/notification. The tracker retains tracks for TRACK_TTL_MS (5s)
     *  after an actor leaves so cross-quadrant handoff and post-record framing
     *  work — but a caption must describe the LIVE scene, not a ghost that left
     *  up to 5s ago.
     *
     *  Sized above the WORST-CASE AI refresh latency: the AI lane runs at most
     *  one quadrant per AI_COOLDOWN_MS (500ms), round-robin over up to 4
     *  quadrants, so a non-priority quadrant's actor refreshes only ~every 2s.
     *  A present actor must stay "fresh" across that gap (and across the
     *  deferred/timeout trigger paths, which can fire several seconds after the
     *  last AI hit), so 2500ms > the ~2000ms round-robin worst case — while
     *  still well under TRACK_TTL_MS so a departed actor is excluded. A tighter
     *  value dropped the real triggering actor on the live START ping
     *  (degrading the caption to a bare "Motion detected"). */
    private static final long ACTOR_CAPTION_FRESHNESS_MS = 2500;

    /** True iff the actor was observed recently enough to caption the live scene. */
    private boolean isActorFresh(Actor a, long now) {
        return a != null && a.lastSeenWallMs > 0
                && (now - a.lastSeenWallMs) <= ACTOR_CAPTION_FRESHNESS_MS;
    }

    /** A low-confidence FAR NOTICE actor — the misclassification profile (e.g. a
     *  parked motorcycle read as "person · far" @0.44) dropped from the three
     *  notification caption count loops (rich-start, final-telegram, final-push)
     *  so the caption can't say "1 person · far" while the linked card shows none.
     *  SUMMARY scope (delegates to Actor.suppressFromSummary), so PERSON is exempt
     *  — a real far still person keeps its caption mention per the hard invariant;
     *  only non-person FPs drop. Cosmetic only: never affects startRecording or
     *  the discard-keep (eventEverSawPerson). */
    private static boolean isLowConfFarNotice(Actor a) {
        return Actor.suppressFromSummary(a);
    }

    /** Camera name for a caption — the actor's CURRENT (last-seen) quadrant, NOT
     *  the lifetime peakCamera high-water latch. Naming peakCamera captioned a
     *  quadrant the actor may have already left ("person at front" when it has
     *  moved to / off the side). Forensic/thumbnail paths still use peakCamera. */
    private static String cameraNameFor(Actor a) {
        if (a == null) return null;
        if (a.lastCamera < 0 || a.lastCamera >= MotionPipelineV2.QUADRANT_NAMES.length) return null;
        return MotionPipelineV2.QUADRANT_NAMES[a.lastCamera];
    }

    /**
     * Stable per-event tag used by both the initial quick notification and the
     * finalized rich one. Same tag → OS replaces the first banner with the
     * second instead of stacking. Tag is derived from the filename so it's
     * unique per recording (a 1-minute dedupe window across recordings is no
     * longer needed since the tag is already event-scoped).
     */
    private static String notificationTagFor(String videoFilename) {
        if (videoFilename != null && !videoFilename.isEmpty()) {
            return "motion:" + videoFilename;
        }
        // Fallback when we don't yet have a filename — minute-bucket dedupe
        // (matches legacy behaviour).
        return "motion-" + (System.currentTimeMillis() / 60000L);
    }

    /**
     * Fallback hero JPEG: extract a keyframe from the MP4 itself when
     * ThumbnailBuffer didn't capture one. Saves to the same path
     * `<videoBase>.jpg` so the rest of the pipeline (sidecar reference,
     * Telegram sendPhoto, PWA push image) works unchanged. Atomic write
     * via .tmp + rename, world-readable so the Telegram daemon (different
     * UID) can read it.
     *
     * Idempotent and exception-safe — failure is logged but never thrown
     * to the caller. The notification path treats absence of the file as
     * "text-only", which is the correct degraded behaviour.
     */
    /**
     * On daemon startup, sweep the surveillance directory for {@code .mp4}
     * files whose sibling hero {@code .jpg} is missing — daemon SIGKILL
     * between rename and finalizer would leave that pair dangling. Generate
     * fallback heroes so the events UI never shows a thumbnail-less card.
     *
     * <p>Idempotent: skips files whose hero already exists. Bounded: only
     * looks at files older than 30 seconds (anything younger is probably
     * still being finalized by a peer daemon process). 5-second budget per
     * file (MediaMetadataRetriever can hang on a malformed mp4).
     */
    private void sweepOrphanHeroThumbnails(File dir) {
        if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
            logger.debug("Orphan hero sweep skipped on DiLink 5 (avoid MediaMetadataRetriever/Codec2 instability)");
            return;
        }
        File[] mp4s = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (mp4s == null || mp4s.length == 0) return;
        long cutoff = System.currentTimeMillis() - 30_000L;
        int generated = 0;
        // Per-file timeout: MediaMetadataRetriever can hang indefinitely on
        // a malformed mp4 (truncated moov, corrupt sample tables) and
        // Future.cancel(true) only flips the interrupt flag — it does NOT
        // abort the underlying native JNI call. So a single-thread executor
        // would queue all subsequent files behind the stuck worker and they'd
        // ALL time out without ever running. Spawn a fresh daemon thread per
        // file so a hung worker leaks one thread but the sweep keeps making
        // progress on the remaining files. 5s budget per file is generous
        // (a healthy keyframe extract is <500 ms even on a slow SD card).
        for (File mp4 : mp4s) {
            if (mp4.lastModified() > cutoff) continue;
            String name = mp4.getName();
            String heroName = name.substring(0, name.length() - 4) + ".jpg";
            File heroFile = new File(dir, heroName);
            if (heroFile.exists()) continue;

            final File mp4Final = mp4;
            final File heroFinal = heroFile;
            final Object done = new Object();
            final boolean[] finished = { false };
            Thread worker = new Thread(() -> {
                try {
                    writeFallbackHeroFromMp4(mp4Final, heroFinal);
                } catch (Throwable t) {
                    logger.debug("Orphan hero extract worker failed for "
                            + name + ": " + t.getMessage());
                } finally {
                    synchronized (done) {
                        finished[0] = true;
                        done.notifyAll();
                    }
                }
            }, "OverdriveOrphanHeroExtract-" + name);
            worker.setDaemon(true);
            worker.start();
            synchronized (done) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (!finished[0]) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try { done.wait(remaining); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!finished[0]) {
                worker.interrupt();
                logger.warn("Orphan hero extract timed out for " + name
                        + " — leaking worker thread, continuing sweep");
            }
            if (heroFile.exists()) generated++;
        }
        if (generated > 0) {
            logger.info("Orphan hero sweep: generated " + generated
                    + " fallback thumbnails in " + dir.getName());
        }
    }

    /**
     * Run {@link #writeFallbackHeroFromMp4} on a fresh daemon thread with a
     * hard timeout. Returns when the worker finishes or {@code timeoutMs}
     * elapses, whichever first. On timeout the worker is interrupted and
     * abandoned — {@link android.media.MediaMetadataRetriever} runs in JNI
     * and ignores Thread.interrupt(), so a hung worker will eventually be
     * killed when its ANR budget exhausts or the process exits. Daemon flag
     * means the leak doesn't block JVM shutdown.
     */
    private void writeFallbackHeroWithTimeout(File mp4File, File outFile, long timeoutMs) {
        if (mp4File == null || outFile == null) return;
        final Object done = new Object();
        final boolean[] finished = { false };
        Thread worker = new Thread(() -> {
            try {
                writeFallbackHeroFromMp4(mp4File, outFile);
            } catch (Throwable t) {
                logger.debug("Fallback hero worker failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            } finally {
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            }
        }, "OverdriveFallbackHero-" + mp4File.getName());
        worker.setDaemon(true);
        worker.start();
        synchronized (done) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { done.wait(remaining); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (!finished[0]) {
            worker.interrupt();
            logger.warn("Fallback hero extract timed out for " + mp4File.getName()
                    + " — leaking worker thread, continuing publish");
        }
    }

    private void writeFallbackHeroFromMp4(File mp4File, File outFile) {
        if (mp4File == null || outFile == null) return;
        if (outFile.exists()) return;          // ThumbnailBuffer already wrote one
        if (!mp4File.exists() || mp4File.length() == 0) return;

        // SAFE PATH 1: Write hero directly from captured trigger/motion RGB frame in RAM
        byte[] rawFrame = lastTriggerFrameRgb;
        if (rawFrame == null) rawFrame = latestFrameRgb;
        if (rawFrame != null && rawFrame.length >= THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * 3) {
            try {
                ThumbnailBuffer.writeRawRgbJpeg(rawFrame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, outFile);
                logger.info("Fallback hero written directly from RAM frame cache: " + outFile.getName());
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty();
                } catch (Throwable ignored) {}
                return;
            } catch (Throwable t) {
                logger.warn("RAM fallback hero write failed: " + t.getMessage());
            }
        }

        // On DiLink 5 / Qualcomm SA8155P, MediaMetadataRetriever spins up Codec2 decoder
        // which collides with active MediaCodec encoder and crashes media.hwcodec (SIGSEGV in QC2GrallocBuffer).
        // Skip MediaMetadataRetriever on DiLink 5 to protect hardware codec stability.
        if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
            logger.warn("Skipping MediaMetadataRetriever on DiLink 5 to protect media.hwcodec HAL: " + mp4File.getName());
            return;
        }

        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(mp4File.getAbsolutePath());
            // Seek to the MOTION moment, not the pre-roll. The clip is
            // [pre-record | motion | post-record]; the old hardcoded ~1s landed
            // inside the pre-record quiet window (preRecordMs default 5s, often
            // flushed to 14s+ from the nearest keyframe) — i.e. an empty frame
            // BEFORE anything happened, which is why fallback heroes often showed
            // a still scene. Aim at preRecordMs + ~1s (just past the trigger), and
            // clamp into [1s, max(1s, duration - postRecordMs)] so we never land in
            // the trailing post-record tail or past the end. preRecordMs/postRecordMs
            // are fields available to BOTH the live and orphan-sweep callers; the
            // clip's own duration bounds it when the buffer flushed more pre-roll
            // than configured. Falls back to mid-clip if the metadata is unusable.
            String dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 0;
            try { if (dur != null) durMs = Long.parseLong(dur); } catch (Exception ignored) {}
            long sampleUs;
            if (durMs >= 1500) {
                // Anchor on the ACTUAL flushed pre-roll, not the configured
                // preRecordMs. The encoder's circular buffer may flush more than
                // the configured pre-record (commonly ~14s), so preRecordMs+1s
                // still lands inside the empty pre-roll quiet window and the hero
                // shows a frame before anything happened. getActualPreRecordDurationMs()
                // is the encoder's real flushed pre-roll (same source startRecording
                // uses); fall back to preRecordMs for the orphan-sweep path where no
                // live encoder is available.
                long anchorMs = preRecordMs;
                try {
                    HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                    if (enc != null) {
                        long actual = enc.getActualPreRecordDurationMs();
                        if (actual > 0) anchorMs = actual;
                    }
                } catch (Throwable ignored) {}
                long target = anchorMs + 1000L;                    // just after motion start
                long upper  = Math.max(1000L, durMs - postRecordMs); // before the post-record tail
                if (target > upper) target = Math.min(upper, durMs / 2); // degrade to mid-clip
                if (target < 1000L) target = 1000L;                // never the leading black frame
                if (target > durMs - 1) target = Math.max(0L, durMs - 1);
                sampleUs = target * 1000L;                          // ms → µs
            } else {
                sampleUs = Math.max(0L, (durMs * 500L));            // very short clip: ~mid
            }
            android.graphics.Bitmap frame = mmr.getFrameAtTime(sampleUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                logger.debug("Fallback hero: getFrameAtTime returned null for " + mp4File.getName());
                return;
            }
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            } finally {
                frame.recycle();
            }
            try { tmpFile.setReadable(true, /*ownerOnly=*/false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    logger.warn("Fallback hero rename failed for " + outFile.getName());
                    return;
                }
            }
            logger.info("Fallback hero (from mp4 keyframe): " + outFile.getName());
            // Idle cleanup gate: shared successful-write point for BOTH
            // fallback-hero callers (rotation path is also covered by its
            // task's finally-bump — double bump is harmless; the
            // final-segment path at stop has no other bump).
            try {
                com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty();
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("Fallback hero extraction failed for " + mp4File.getName()
                    + ": " + t.getMessage());
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Capitalised, human-readable proximity phrase used as the lead clause
     * in notification bodies. "VERY_CLOSE" → "Very close", etc.
     */
    private static String proximityPhrase(Actor.Proximity p) {
        if (p == null) return "";
        switch (p) {
            case VERY_CLOSE: return "Very close";
            case CLOSE:      return "Close";
            case MID:        return "Mid range";
            case FAR:        return "Far";
            default:         return "";
        }
    }

    /**
     * Pluralised count list for notification bodies.
     * (1, 0, 0, 0) → "1 person"
     * (2, 1, 0, 0) → "2 people, 1 vehicle"
     * (0, 2, 0, 1) → "2 vehicles, 1 animal"
     * Skips zero counts; uses proper plurals; drops the "× n" formatter.
     */
    private static String formatActorCounts(int persons, int vehicles, int bikes, int animals) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        if (persons > 0)  parts.add(persons  + " " + (persons  == 1 ? "person"  : "people"));
        if (vehicles > 0) parts.add(vehicles + " " + (vehicles == 1 ? "vehicle" : "vehicles"));
        if (bikes > 0)    parts.add(bikes    + " " + (bikes    == 1 ? "bike"    : "bikes"));
        if (animals > 0)  parts.add(animals  + " " + (animals  == 1 ? "animal"  : "animals"));
        return String.join(", ", parts);
    }

    /**
     * Initial low-priority notification at the moment recording starts.
     *
     * Why we still publish at start: the user expects feedback "something just
     * happened" with low latency. The hero thumbnail isn't ready yet (it's
     * written by ThumbnailBuffer at recording-end), so this banner is text-
     * only and minimum-severity. The matching {@link #publishMotionFinal}
     * call after recording-end carries the rich title + thumbnail and uses
     * the SAME tag — so the OS replaces this quick banner instead of stacking.
     *
     * Routing: always to {@code surveillance.motion.notice} so users who only
     * want Alerts/Criticals at start can mute this tier and just receive the
     * final notification.
     */
    private void publishMotionNotification(String videoFilename) {
        try {
            // Honour the user's per-tier toggle. The start banner is always
            // routed to surveillance.motion.notice (final stage carries the
            // real severity), so it's gated by isPushNotices(). With the
            // default config (pushNotices=false) start banners are off and
            // the user only sees the rich final notification.
            if (config != null && !config.isPushNotices()) {
                return;
            }
            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null && !videoFilename.isEmpty()) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Deliberately NOT setting data.snapshot here. At start time the
                // hero JPEG hasn't been written yet and /thumb/<mp4> will return
                // 202 or, worse, a mid-event MMR frame off the in-flight .tmp.
                // iOS Safari Web Push caches the resolved image bytes against
                // the tag on first paint and refuses to swap them when the
                // matching `final` push arrives — so leaving snapshot out keeps
                // the start banner intentionally text-only and lets the final
                // push install the real hero image cleanly.
                data.put("stage", "start");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }

            // Name the quadrant where an actor IS NOW (lastCamera via cameraNameFor),
            // and only from a FRESH actor — a TTL-retained ghost that already left
            // must not label this live "Motion at <camera>" banner.
            long nowMs = System.currentTimeMillis();
            String camHint = null;
            for (Actor a : lastActors) {
                if (!isActorFresh(a, nowMs)) continue;
                String name = cameraNameFor(a);
                if (name != null) { camHint = name; break; }
            }
            String title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            String body = "Recording in progress";

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.motion.notice",
                            com.overdrive.app.notifications.NotificationEvent.Severity.INFO,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishMotionNotification (start) failed: " + t.getMessage());
        }
    }

    /**
     * Publish a just-finalized sentry event's verdict to the automation engine so a
     * rule can trigger on what the guard saw ({@code surveillanceThreat} = severity,
     * {@code surveillanceObject} = headline object class). Called once per event from
     * {@link #publishMotionFinal} — a COLD path (the .mp4 has finalized), never the
     * hot GL frame loop.
     *
     * <p>The automation state model is level-triggered (fires on value TRANSITIONS),
     * so two consecutive events of the same severity would only fire the first. That's
     * resolved by seeding an idle baseline: {@link #resetSurveillanceAutomationState}
     * is called at each recording start (and at arm), so every event is a genuine
     * "idle → X" transition and re-fires. Mirrors {@code SafeLocationManager}'s
     * daemon-side {@code Automations.update} publisher — no local dedup latch, wrapped
     * in try/catch so an automation-layer hiccup never disturbs surveillance.
     */
    private void publishSurveillanceAutomationEvent(Actor.Severity peakSev, Actor threat,
                                                    int persons, int vehicles, int bikes, int animals) {
        try {
            String sev = (peakSev == Actor.Severity.CRITICAL) ? "critical"
                    : (peakSev == Actor.Severity.ALERT) ? "alert" : "notice";
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_THREAT, sev);
            // Headline object. Prefer the THREAT actor — the same pick that drives the
            // notification title and (via the shared live-beats-static ordering) the
            // hero thumbnail — so a rule written against surveillanceObject sees what
            // the user was told.
            //
            // The count ladder below is only the fallback for threat == null. Deriving
            // `obj` from the counts made this event contradict its own notification:
            // the counts intentionally still include a mask-demoted parked car (they
            // are an inventory, and narrowing them would change pushWorthy and make
            // the three surfaces disagree), so a live animal + static parked car
            // produced title "Animal at rear", an animal thumbnail, and
            // surveillanceObject "vehicle" — because vehicles>0 outranks animals>0.
            //
            // Map explicitly rather than via Actor.groupLabel(): the automation enum
            // (Conditions.java, surveillanceObject) offers exactly
            // person/vehicle/bike/animal/none, and groupLabel returns "object" for
            // ClassGroup.UNKNOWN — a value no user rule can ever match. An UNKNOWN
            // threat therefore falls through to the count ladder, as before.
            String obj = null;
            if (threat != null) {
                switch (threat.classGroup) {
                    case PERSON:  obj = "person";  break;
                    case VEHICLE: obj = "vehicle"; break;
                    case BIKE:    obj = "bike";    break;
                    case ANIMAL:  obj = "animal";  break;
                    // UNKNOWN can't reach here (ActorTracker drops UNKNOWN before an
                    // Actor exists), but fall to the ladder rather than publishing a
                    // value no user rule can match.
                    default:      obj = null;      break;
                }
            }
            if (obj == null) {
                // Ladder order MUST match classRank (person > bike > vehicle >
                // animal) — the same precedence beatsAsThreat and the hero score
                // use. It previously led with `bike`, which was harmless only while
                // the ladder was unreachable; the all-scenery headline demotion in
                // publishMotionFinal now nulls `threat` with counts still non-zero,
                // so a person-and-bike event would have published
                // surveillanceObject="bike" while the title said "Person" —
                // mis-firing a "person detected → siren" rule.
                if (persons > 0) obj = "person";
                else if (bikes > 0) obj = "bike";
                else if (vehicles > 0) obj = "vehicle";
                else if (animals > 0) obj = "animal";
                else obj = "none";
            }
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_OBJECT, obj);
        } catch (Throwable t) {
            logger.debug("publishSurveillanceAutomationEvent failed: " + t.getMessage());
        }
    }

    /**
     * Seed the sentry automation events back to an idle baseline so the NEXT event is a
     * real transition (see {@link #publishSurveillanceAutomationEvent}). Called at each
     * recording start and at arm. Publishing "notice"/"none" is the neutral floor; a
     * genuine event then transitions up from it. try/catch — never disturbs recording.
     */
    private void resetSurveillanceAutomationState() {
        try {
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_THREAT, "notice");
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_OBJECT, "none");
        } catch (Throwable t) {
            logger.debug("resetSurveillanceAutomationState failed: " + t.getMessage());
        }
    }

    /**
     * Finalized rich notification fired from stopRecording AFTER the hero JPEG
     * has been written by ThumbnailBuffer. Uses the same tag as the initial
     * notification so the OS replaces the "Recording in progress…" banner
     * with the proper threat summary + image.
     *
     * Routes to the severity-appropriate subcategory ({@code .notice/.alert/.critical})
     * so per-tier muting works.
     *
     * @param heroIsCloseUp true when {@code heroJpegName} is a ThumbnailBuffer
     *        foveated crop (so the body may advertise a "close-up view"), false
     *        when it is an MP4-keyframe fallback — a plain wide frame with no
     *        threat box. The caller must decide this: by the time we run, both
     *        sources are just a file on disk.
     */
    private void publishMotionFinal(String videoFilename, String heroJpegName,
                                    boolean heroIsCloseUp) {
        // Legacy entry: computes the actor union + scenery verdicts from live
        // fields. stopRecording no longer uses this — it passes its stop-time
        // snapshots (audit R8-2 / ExtC-3: lastActors + thumbnailBuffer are
        // cleared before the publish tail runs).
        java.util.List<Actor> liveSnap = finalNotificationActors();
        publishMotionFinal(videoFilename, heroJpegName, heroIsCloseUp,
                liveSnap, snapshotSceneryIds(liveSnap));
    }

    /** Snapshot variant (audit R8-2 / ExtC-3): consumes the caller's actor
     *  union + scenery-verdict snapshot so no live shared field is read. */
    private void publishMotionFinal(String videoFilename, String heroJpegName,
                                    boolean heroIsCloseUp,
                                    java.util.List<Actor> snapIn,
                                    java.util.Set<Long> sceneryIdsIn) {
        try {
            // Event-peak union (NOT the bare TTL-pruned lastActors) so the push
            // title/body name the same actor the hero thumbnail shows. A close
            // person who departed before event-end is pruned from lastActors,
            // leaving a lingering far car to caption the event — while the hero
            // (event-peak frame) shows the person. See finalNotificationActors().
            java.util.List<Actor> snap = snapIn != null
                    ? snapIn : java.util.Collections.<Actor>emptyList();
            Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
            // MOTION-EVIDENCE SEVERITY FLOOR (notification fix). maxSeverity()
            // returns the NOTICE floor when the event classified 0 actors (YOLO
            // unloaded, or the subject was too dark/distant/close/fisheye-warped
            // for the CPU model — the exact 0-actor timelines seen in field logs).
            // In that case eventPeakSeverity is still null and this final push
            // would go out as bare NOTICE, even though the RECORDING was committed
            // on a trusted-HIGH loiter or a NEAR/approaching proximity signal the
            // engine trusts enough to record without YOLO. Floor to ALERT so that
            // real, YOLO-invisible approach routes to surveillance.motion.alert
            // (a tier on by default) instead of being silently swallowed at the
            // NOTICE tier a cautious user may have muted. Gated on the narrow
            // eventTriggerWasStrongThreat latch (trusted-HIGH / NEAR / approaching
            // only), so plain LOW/MEDIUM passing motion still pushes as NOTICE.
            // Only ever RAISES NOTICE→ALERT; a genuine ALERT/CRITICAL from a real
            // actor is left untouched.
            if (peakSev == Actor.Severity.NOTICE && eventTriggerWasStrongThreat) {
                logger.info("publishMotionFinal: flooring severity NOTICE→ALERT "
                        + "(strong-threat trigger, 0 YOLO actors classified)");
                peakSev = Actor.Severity.ALERT;
            }
            // Web-push delivery is governed by TWO gates, in order:
            //   1. The event-evidence gate below (pushWorthy) — a daemon-side,
            //      per-EVENT decision: push only when YOLO saw an actor or the
            //      trigger was a trusted strong threat. This replaced the OLD
            //      daemon-global pushNotices/pushAlerts/pushCritical flags, which
            //      had no serializer and no UI/API writer, so they were stuck at
            //      their defaults forever and the user's toggle could never move
            //      them. The per-event evidence gate is writable-config-free and
            //      keys off the actual event, not a dead flag.
            //   2. Per-device muted-categories (downstream in PushSink via
            //      subscription.isMuted() against the severity-routed subcategory
            //      below) — a per-DEVICE decision the Notifications UI controls.
            // We ALWAYS publish to the bus regardless; suppression is web-push
            // ONLY. HistorySink persists every event to the Notification Log,
            // LogSink logs it, TelegramSink forwards it. The pushWorthy decision
            // is deferred to just before publish() so it can read `threat` (the
            // classified-actor signal), computed below.

            // Build per-class counts + closest proximity from snapshot.
            //
            // ONLY count non-static actors. The user's worry: two cars parked
            // next to ours while a person walks in. YOLO returns 3 detections;
            // the parked cars are flagged isStatic by the tracker; we exclude
            // them from counts so the notification reads "1 person near front
            // camera" — not "1 person, 2 vehicles".
            int persons = 0, vehicles = 0, bikes = 0, animals = 0;
            Actor.Proximity closest = null;
            // Threat actor = highest-severity, then best class rank
            // (person > bike > vehicle > animal).
            Actor threat = null;
            // One stable scenery verdict for the whole loop — snapshotted by the
            // caller (audit R8-2 / ExtC-3), see snapshotSceneryIds.
            final java.util.Set<Long> sceneryIds = sceneryIdsIn != null
                    ? sceneryIdsIn : java.util.Collections.<Long>emptySet();
            for (Actor a : snap) {
                // Keep a static PERSON (loiterer = threat, gated CRITICAL); skip
                // only non-person statics (parked cars → NOTICE anyway).
                //
                // NOTE: deliberately NOT extended with the hero's motion-grounded
                // staticness verdict. This single loop computes the per-class COUNTS
                // and `closest` as well as `threat`, and `threat != null` feeds
                // pushWorthy below — so widening the skip here could zero the counts
                // and SUPPRESS a web push that previously fired (a missed
                // notification, far worse than a mismatched thumbnail). The headline
                // divergence is instead addressed where it is safe: the hero and the
                // per-actor JPEGs skip statics, and the two Telegram/rich-caption
                // threat picks (which do not gate any push) apply the extra term.
                if (a.isStaticForTimeline && a.classGroup != Actor.ClassGroup.PERSON) continue;
                // Skip an UNCONFIRMED (1-2 frame flicker) person so the push count
                // matches the event-card headline and the Telegram caption (both
                // exclude !confirmed persons).
                if (a.classGroup == Actor.ClassGroup.PERSON && !a.confirmed) continue;
                // Drop the low-conf FAR NOTICE FP so the push count agrees with the
                // card + hero (both suppress it). See isLowConfFarNotice.
                if (isLowConfFarNotice(a)) continue;
                switch (a.classGroup) {
                    case PERSON:  persons++;  break;
                    case VEHICLE: vehicles++; break;
                    case BIKE:    bikes++;    break;
                    case ANIMAL:  animals++;  break;
                    default: break;
                }
                if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                    closest = a.peakProximity;
                }
                // Shared ordering (severity → live-beats-static → class) so the
                // headline agrees with the hero thumbnail. Counts above are
                // untouched, so pushWorthy and the per-class totals are unchanged.
                if (beatsAsThreat(a, threat, sceneryIds)) {
                    threat = a;
                }
            }
            // ALL-SCENERY POOL ⇒ don't let the title or the automation event name a
            // parked car. The live-beats-scenery tier only ORDERS candidates, so when
            // EVERY candidate is scenery it still elects one; ThumbnailBuffer.pickHero
            // instead drops them all and returns null, so the push would read
            // "Alert · Vehicle at rear" over a bare keyframe of an empty parking space
            // and fire the user's "vehicle detected" automation for a car that has not
            // moved all night.
            //
            // pushWorthy is latched FIRST and read below instead of `threat != null`:
            // demoting the headline must never suppress a push that HEAD would send
            // (a missed notification is far worse than a mismatched title). Counts are
            // likewise untouched — they remain an inventory of what was classified.
            final boolean threatClassified = (threat != null);
            final Actor camAnchor = threat;
            if (threat != null && isScenery(threat, sceneryIds)) {
                threat = null;
            }
            // Publish this event's verdict to the automation engine (surveillanceThreat
            // + surveillanceObject) so a rule can react to what the sentry saw. Cold
            // per-event path (fires once as the .mp4 finalizes), never the GL loop.
            publishSurveillanceAutomationEvent(peakSev, threat, persons, vehicles, bikes, animals);

            // camHint follows the threat actor so the title's "X at <camera>"
            // phrase names the camera that saw X, not whichever actor was closest.
            // Uses the pre-demotion anchor: WHERE the motion was is still true even
            // when the class claim is withheld.
            String camHint = cameraNameFor(camAnchor);

            // ---- Title (severity tier + threat class + camera) ----
            // Format: "CRITICAL · Person at front" or "Alert · Vehicle at rear"
            // or plain "Motion at front" when AI didn't classify.
            String title;
            if (threat == null) {
                title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            } else {
                StringBuilder sb = new StringBuilder();
                if (peakSev == Actor.Severity.CRITICAL) sb.append("CRITICAL · ");
                else if (peakSev == Actor.Severity.ALERT) sb.append("Alert · ");
                String label = Actor.groupLabel(threat.classGroup);
                if (!label.isEmpty()) {
                    label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                }
                sb.append(label);
                if (camHint != null) sb.append(" at ").append(camHint);
                title = sb.toString();
            }

            // ---- Body (proximity phrase + counts when relevant) ----
            // Single actor: "Very close" / "Close" / "Mid range" / "Far".
            // Multiple actors: "Very close · 1 person, 2 vehicles".
            // When a ThumbnailBuffer hero was written, append "close-up view" so the
            // user knows the attached image is the foveated crop around the threat,
            // not a wide shot of the camera frame. An MP4-keyframe FALLBACK image
            // must not claim that — it IS the wide shot, with no threat box drawn —
            // hence heroIsCloseUp rather than mere file existence.
            String body;
            int totalActors = persons + vehicles + bikes + animals;
            boolean hasHero = heroIsCloseUp && heroJpegName != null && !heroJpegName.isEmpty();
            if (threat == null) {
                // No actor classified. If the trigger itself was a strong threat
                // (the severity-floor case: trusted-HIGH loiter / NEAR / approaching
                // that YOLO couldn't label), say so — a bare "Recording in progress"
                // reads as an unactionable non-event and undersells an Alert-tier
                // push. Otherwise keep the neutral phrasing for plain motion.
                body = eventTriggerWasStrongThreat
                        ? "Movement near vehicle — tap to review"
                        : "Motion recorded";
            } else {
                StringBuilder sb = new StringBuilder();
                if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                    sb.append(proximityPhrase(closest));
                }
                if (totalActors > 1) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(formatActorCounts(persons, vehicles, bikes, animals));
                }
                if (sb.length() == 0) sb.append("Motion detected");
                if (hasHero) sb.append(" · close-up view");
                body = sb.toString();
            }

            // Place suffix — appended only when the resolver has a synchronous
            // hit (cache or SafeLocation). Online resolution is deliberately
            // never awaited from the publish path: the push must fire as
            // soon as the .mp4 finalizes, not after a 6-second Nominatim
            // round-trip. A miss leaves the body unchanged. Coords are
            // never leaked into the body — better silent on location than
            // posting "3.0509, 101.7166" into a Telegram chat.
            String placeMid = null;
            String placeCC = null;
            try {
                HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                if (enc != null && enc.hasStartGeo()) {
                    // Always "surveillance" flow on this path — this is the
                    // sentry/proximity publish exit.
                    com.overdrive.app.geo.PlaceResult place =
                            com.overdrive.app.geo.GeocodingResolver.getInstance()
                                    .resolveCachedOnly(enc.getStartGeoLat(),
                                            enc.getStartGeoLng(), "surveillance");
                    if (place != null) {
                        String mid = place.mediumLabel();
                        if (mid != null && !mid.isEmpty()) {
                            body = body + " · " + mid;
                            placeMid = mid;
                            if (!place.countryCode.isEmpty()) {
                                placeCC = place.countryCode;
                            }
                        }
                    }
                }
            } catch (Throwable placeErr) {
                logger.debug("publishMotionFinal place lookup failed: " + placeErr.getMessage());
            }

            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Prefer the just-written hero JPEG for the OS banner image.
                // Falls back to /thumb/<mp4-name> which the server also resolves
                // to the hero sibling, but the explicit JPEG path skips a layer
                // of resolution and avoids any 202-while-generating window.
                String snapshotName = (heroJpegName != null && !heroJpegName.isEmpty())
                        ? heroJpegName : videoFilename;
                String encSnap = java.net.URLEncoder.encode(snapshotName, "UTF-8");
                // Carry a single-purpose signed token so Web Push service
                // workers / OS notification banners can fetch the thumbnail
                // without an Authorization header. 10 min TTL is plenty for
                // a banner that the user dismisses or taps within seconds.
                String thumbTok = com.overdrive.app.auth.AuthManager
                        .signThumbToken(snapshotName, 600L);
                String snapUrl = "/thumb/" + encSnap;
                if (thumbTok != null) snapUrl += "?t=" + thumbTok;
                data.put("snapshot", snapUrl);
                data.put("stage", "final");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }
            // Surface the new metadata so the notification UI / SW can render it
            data.put("severity", peakSev.name());
            data.put("personCount", persons);
            data.put("vehicleCount", vehicles);
            data.put("bikeCount", bikes);
            data.put("animalCount", animals);
            if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                data.put("closestProximity", closest.name());
            }
            if (camHint != null) data.put("camera", camHint);
            if (placeMid != null) data.put("place", placeMid);
            if (placeCC  != null) data.put("placeCountry", placeCC);

            com.overdrive.app.notifications.NotificationEvent.Severity nsev;
            if (peakSev == Actor.Severity.CRITICAL) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL;
            } else if (peakSev == Actor.Severity.ALERT) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            } else {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.INFO;
            }

            // Route to severity-specific subcategory so per-tier muting works
            // (item 8). Devices that have not yet learned the new IDs still
            // receive the parent "surveillance.motion" event below.
            String subCategory;
            if (peakSev == Actor.Severity.CRITICAL) subCategory = "surveillance.motion.critical";
            else if (peakSev == Actor.Severity.ALERT) subCategory = "surveillance.motion.alert";
            else subCategory = "surveillance.motion.notice";

            com.overdrive.app.notifications.NotificationEvent ev =
                    new com.overdrive.app.notifications.NotificationEvent(
                            subCategory,
                            nsev,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data);
            // EVENT-EVIDENCE WEB-PUSH GATE. We ALWAYS publish to the bus so
            // HistorySink persists every event to the Notification Log, LogSink
            // logs it, and TelegramSink forwards it — suppression here is
            // web-push-ONLY (PushSink honours isPushSuppressed()). A web push
            // fires only when the event carries real threat evidence:
            //   (a) YOLO classified an actor (threatClassified — a person/vehicle/
            //       bike/animal actually seen), OR
            //   (b) the trigger itself was a strong threat the engine trusts
            //       without YOLO (eventTriggerWasStrongThreat: trusted-HIGH loiter
            //       / NEAR / approaching — already floored to ALERT above).
            // A 0-actor event from plain or untrusted motion (distant/stable
            // passing-by, shadow, foliage — the ~80%-of-events bulk that floods
            // a parked car) is NOT pushed: it stays in the Log for review but
            // doesn't buzz the phone. This is the intentional replacement for the
            // removed daemon-global pushNotices gate — gated on per-EVENT evidence
            // instead of a dead never-writable config flag. Per-device
            // muted-categories still applies downstream in PushSink for the events
            // that DO pass this gate.
            // threatClassified, NOT (threat != null): the all-scenery headline
            // demotion above nulls `threat` for cosmetic reasons and must not be
            // able to swallow a push.
            boolean pushWorthy = threatClassified || eventTriggerWasStrongThreat;
            if (!pushWorthy) {
                logger.info("publishMotionFinal: web-push suppressed (no YOLO actor + "
                        + "weak/untrusted trigger) — still persisting to Log");
                ev.suppressPush();
            }
            com.overdrive.app.notifications.NotificationBus.get().publish(ev);
        } catch (Throwable t) {
            logger.debug("publishMotionFinal failed: " + t.getMessage());
        }
    }

    /**
     * Continuous-mode entry. Bypasses motion / YOLO / baseline entirely and
     * starts a plain rolling recording. Segment rotation @
     * {@code SEGMENT_DURATION_MS} (2 min) still runs so a long session is
     * split into manageable .mp4 files. Filename uses the same {@code event_}
     * prefix as smart-mode events so recordings library / Telegram /
     * storage-bucket plumbing handles them without separate code paths.
     *
     * No timeline collector, no per-segment hero JPEG, no Telegram
     * notifications, no screen deterrent — the user opted in knowing the
     * whole park is recorded; per-segment notifications would just spam.
     *
     * Stop happens at {@link #disable()} (ACC ON or owner-unlock disarm).
     */
    private void startContinuousRecording() {
        if (recorder == null) {
            logger.error("Cannot start continuous recording — recorder is null");
            return;
        }
        if (recording) {
            logger.debug("Already recording");
            return;
        }
        // Arm-session epoch at entry (audit R4 ExtB-4): re-checked at the
        // locked commit below. enable() bumps the epoch before calling us;
        // the H5 retry tick passes its own epoch check just before calling.
        final int entryEpoch = armEpoch;

        // Same SD/USB mount sanity-check as startRecording(); otherwise the
        // first segment can land on a stale path right after a card eject.
        com.overdrive.app.storage.StorageManager storageManager;
        try {
            storageManager = com.overdrive.app.storage.StorageManager.getInstance();
            com.overdrive.app.storage.StorageManager.StorageType type =
                    storageManager.getSurveillanceStorageType();
            if (type == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD
                    && !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before continuous recording — attempting remount");
                storageManager.ensureSdCardMounted(true);
            } else if (type == com.overdrive.app.storage.StorageManager.StorageType.USB
                    && !storageManager.isUsbMounted()) {
                logger.warn("USB unmounted before continuous recording — attempting remount");
                storageManager.ensureUsbMounted(true);
            }
        } catch (Exception e) {
            logger.warn("Storage mount check failed: " + e.getMessage());
            storageManager = null;
        }
        final com.overdrive.app.storage.StorageManager smRef = storageManager;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // Reuse the `event_` prefix so everything downstream (recordings
        // library scanner, /api/recordings/<file> server, Telegram /download,
        // StorageManager surveillance bucket, daily-prune watcher) handles
        // continuous-mode segments without separate code paths. The mode
        // distinction lives in the user's config, not in the filename.
        String fileName = "event_" + timestamp + ".mp4";
        // Re-read the live surveillance dir: enableSurveillance() may have
        // snapshotted the internal fallback during the boot mount-race, and the
        // SD/USB mount (incl. the smRef.ensure*Mounted attempt just above) may
        // have landed since. We MUST resolve LIVE (getLiveSurveillanceDir)
        // rather than read getSurveillanceDir(): the volatile surveillanceDir
        // field is frozen while the arm session is active (updateActiveDirectories
        // skips the surveillance branch), so getSurveillanceDir() would return
        // the stale internal fallback for a mount that landed after the bounded
        // enable-wait. getLiveSurveillanceDir() bypasses that freeze and falls
        // back to internal when the external is genuinely unavailable. Resolved
        // ONCE here (recording == false above), so the in-flight clip can't be
        // split across volumes; null-guard back to the snapshot.
        File trigDir = (smRef != null) ? smRef.getLiveSurveillanceDir() : eventOutputDir;
        if (trigDir == null) trigDir = eventOutputDir;
        // ENOSPC internal-spill: if the configured external is mounted-but-FULL,
        // redirect THIS clip to the INTERNAL SURVEILLANCE dir so it isn't
        // quarantined as .broken on a packed card. Uses the surveillance-bucket
        // helper (NOT resolveTargetWithEnospcFallback, which spills to the
        // recordings folder and would orphan an event_* clip from both cleanup
        // pools). Defensive: a throw returns trigDir unchanged.
        if (smRef != null && trigDir != null) {
            try {
                File spill = smRef.resolveSurveillanceTargetWithEnospcFallback(trigDir, 100L * 1024 * 1024);
                if (spill != null) trigDir = spill;
            } catch (Throwable t) {
                logger.warn("ENOSPC-fallback resolve failed (continuous): " + t.getMessage());
            }
        }
        if (trigDir != null && !trigDir.equals(eventOutputDir)) {
            eventOutputDir = trigDir;
        }
        // LOCAL until ownership is proven (audit R13-6 / ExtE-6): the field
        // used to be overwritten HERE, before the exclusive trigger — so an
        // ALREADY_RECORDING outcome left currentEventFile pointing at a file
        // that never existed (the R6-1 "transient mispoint" accepted
        // residual). Now the field is only assigned inside the locked commit
        // after STARTED, retiring that residual: a refused or already-owned
        // start never touches shared state.
        final File contTargetFile = new File(trigDir, fileName);

        // Storage housekeeping — continuous mode generates much more data than
        // smart mode, so trigger an immediate prune so the first segment doesn't
        // land on a near-full card.
        //
        // Position and executor are BOTH load-bearing; see the twin dispatch on
        // the smart-trigger path (startRecording) for the full rationale. In
        // short: ensureSurveillanceSpace() takes StorageManager's
        // surveillanceCleanupLock with no wall-clock bound (UNLIMITED_REAP, shell
        // fallback budgets seconds per directory), and so do
        // getLiveSurveillanceDir() / resolveSurveillanceTargetWithEnospcFallback
        // above. Dispatching BEFORE those resolves — as this used to — let the
        // maintenance thread win the lock and park the CALLER on the resolve for
        // the walk's whole duration. Here the caller is enable() on the daemon/
        // control thread, so on a dropped/bridged SD mount that stalled arming
        // itself: `active` already true, `recording` still false, nothing being
        // recorded, and the control thread deaf to the next arm/disarm.
        //
        // Uses storageMaintenanceExecutor rather than a raw thread so it is
        // daemon + MIN_PRIORITY, bounded, and actually shut down by release().
        if (smRef != null) {
            try {
                storageMaintenanceExecutor.execute(() -> {
                    try { smRef.ensureSurveillanceSpace(50 * 1024 * 1024); }
                    catch (Exception e) { logger.warn("Async storage cleanup failed: " + e.getMessage()); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Engine releasing — skip housekeeping. The daemon's 30 s periodic
                // reclaim performs an equivalent surveillance pass independently.
            }
        }

        logger.info("Starting continuous recording: " + contTargetFile.getAbsolutePath());

        // postRecordMs=0: the engine itself owns the stop schedule (it stops
        // at disable()), so we don't want the recorder to schedule any
        // automatic close. The encoder's pre-record buffer is harmless here
        // — it just front-loads the first segment with a few seconds of
        // pre-arm video, which is fine.
        //
        // Honor triggerEventRecording's return — the recorder refuses to
        // build a muxer when the encoder hasn't published its format
        // (savedFormat barrier). Flipping `recording = true` regardless
        // would leave the engine bookkeeping advanced (rotation listener
        // attached, segment counter incremented) against a no-op recorder
        // → user sees a "recording" indicator but no clip lands on disk.
        // OWNERSHIP-EXPLICIT trigger (audit R6 lifecycle #1): the boolean
        // form reported "already recording" as success, so a STALE start
        // (pre-bounce retry tick resuming after seconds in the storage
        // resolves) sailed past refusal into the epoch-mismatch unwind and
        // STOPPED the successor session's live recording — engine left
        // active+recording with the recorder dead for the whole parked
        // session. ALREADY_RECORDING now means "another session owns the
        // recorder": neither commit nor unwind, and leave currentEventFile
        // alone beyond what was already overwritten (the owner's segment
        // listener re-points it on the next rotation).
        GpuMosaicRecorder.TriggerResult contTrig =
                recorder.triggerEventRecordingExclusive(contTargetFile.getAbsolutePath(), 0L);
        if (contTrig == GpuMosaicRecorder.TriggerResult.ALREADY_RECORDING) {
            // currentEventFile genuinely untouched now (audit R13-6) — the
            // owner's bookkeeping is not even transiently disturbed.
            logger.warn("Continuous-mode start skipped — recorder already owned by "
                + "another session (stale start after re-arm?)");
            return;
        }
        if (contTrig != GpuMosaicRecorder.TriggerResult.STARTED) {
            // NOTE (audit R3b Ext-6): "will retry on next event" was a
            // copy-paste fiction here — continuous mode has no events. The
            // retry is real now: enable()'s continuous branch (and each
            // retry tick) reschedules via scheduleContinuousStartRetry while
            // the engine stays armed-but-not-recording.
            logger.warn("Continuous-mode triggerEventRecording refused "
                + "(encoder format not ready); bounded retry scheduled");
            return;
        }
        // COMMIT under the lifecycle lock with a disarm/epoch re-check
        // (audit R4 ExtB-4): the storage resolves above can block for
        // seconds, and the H5 retry runs this method OFF the control thread
        // — a disable() in that window saw recording==false, skipped its
        // stop, and a blind commit here left the recorder rolling for the
        // whole drive (postRecordMs=0 ⇒ it never self-closes, and
        // continuous mode has no tick loop to notice). Mirrors the smart
        // path's locked commit; disable()'s continuous stop decision takes
        // the same lock, so every interleaving either stops the committed
        // recording or unwinds the in-flight start.
        synchronized (recordingLifecycleLock) {
            if (!active || armEpoch != entryEpoch) {
                logger.warn("Disarmed mid-start — unwinding just-started continuous recording");
                try {
                    recorder.stopEventRecording(true, 0);
                } catch (Exception e) {
                    logger.warn("Continuous unwind stop failed: " + e.getMessage());
                }
                return;
            }
            // RECORDER LIVENESS AT COMMIT (audit R14-3 / ExtF-3, symmetric
            // with the smart-mode commit): a camera yield's onPreYield() can
            // stop the just-started recorder in the trigger→commit window.
            // Don't publish recording=true against a dead recorder — the
            // retry chain / reacquire restart re-establishes the session.
            if (!recorder.isRecording()) {
                logger.warn("Recorder died between trigger and commit (camera yield?) — "
                    + "abandoning continuous start; retry chain / reacquire will restart");
                if (!recording) {
                    scheduleContinuousStartRetry(entryEpoch, 1);
                }
                return;
            }
            recording = true;
            // Assigned INSIDE the locked commit, atomic with ownership
            // (audit R13-6 / ExtE-6).
            currentEventFile = contTargetFile;
        }

        // Segment rotation listener: track the new segment filename for the
        // future stop() AND submit the closed segment to the geo sidecar
        // writer. Continuous-mode reuses the event_*.mp4 prefix to share
        // downstream plumbing, so the recorder's own filename-based
        // skip-sentry guard correctly skips these in HardwareEventRecorderGpu;
        // we have to do the submit here. Flow="recording" because
        // continuous-mode is NOT sentry surveillance — it's a parking
        // dashcam mode that the user opts into separately, and gating
        // on the "recording" flow's geocoding toggle matches that
        // mental model.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            // RACING-STOP GUARD (audit R13-6 / ExtE-6, mirror of the R11-2
            // smart-mode pattern): a disable() landing between the locked
            // commit above and this install runs the full stop (which
            // detaches the listener slot); installing ours after that would
            // latch a continuous listener onto the shared encoder in the
            // disarmed state. Check before, compensate after.
            if (enc != null && recording) {
                enc.setSegmentListener((closedSegment, newSegment) -> {
                    if (closedSegment != null) {
                        try {
                            com.overdrive.app.geo.GeoSnapshot startGeo;
                            if (enc.hasClosedStartGeo()) {
                                startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                        enc.getClosedStartGeoLat(), enc.getClosedStartGeoLng(),
                                        enc.getClosedStartGeoAccuracy(), enc.getClosedStartGeoAgeMs(),
                                        enc.getClosedStartGeoCapturedAtMs(), 0L);
                            } else {
                                startGeo = com.overdrive.app.geo.GeoSnapshot.empty();
                            }
                            com.overdrive.app.geo.LocationSidecarWriter
                                    .getInstance()
                                    .submit(closedSegment, "recording", startGeo);
                        } catch (Throwable t) {
                            logger.warn("Continuous segment sidecar submit failed: "
                                    + t.getMessage());
                        }
                    }
                    if (newSegment != null) {
                        currentEventFile = newSegment;
                    }
                });
                // Compensating check (audit R13-6 / ExtE-6): if a stop ran
                // its detach while we were installing, detach again. A
                // successor start cannot have installed ITS listener yet
                // (its storage resolves take hundreds of ms), so this can
                // only remove the listener WE just installed.
                if (!recording) {
                    enc.setSegmentListener(null);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not register continuous segment listener: " + e.getMessage());
        }

        logger.info("Continuous recording started successfully");
    }

    /** Retry cadence/bound for a refused continuous-mode start (R3b Ext-6):
     *  3s covers encoder warmup (typically <10s) without hammering; 20
     *  attempts ≈ 1 minute, after which the encoder is not coming up and
     *  retrying forever would just spam logs. */
    private static final long CONTINUOUS_START_RETRY_MS = 3000L;
    private static final int CONTINUOUS_START_MAX_RETRIES = 20;

    /**
     * Bounded, epoch-guarded retry for a refused continuous-mode recording
     * start (audit R3b Ext-6). Continuous mode has no event loop, no AI lane
     * and no watchdog, so a cold-boot savedFormat refusal used to leave the
     * engine silently armed-but-not-recording for the entire parked session.
     * The epoch guard aborts the chain on disable() or a new arm session;
     * `recording` flips true on the first successful start and stops the
     * chain.
     */
    private void scheduleContinuousStartRetry(final int epoch, final int attempt) {
        if (attempt > CONTINUOUS_START_MAX_RETRIES) {
            logger.error("Continuous-mode start abandoned after "
                + CONTINUOUS_START_MAX_RETRIES + " retries — engine is armed but NOT "
                + "recording; will not recover until the next arm cycle");
            return;
        }
        try {
            aiScheduler.schedule(() -> {
                if (!active || armEpoch != epoch || !continuousMode || recording) {
                    return;  // disarmed / new session / already recording
                }
                logger.info("Continuous-mode start retry " + attempt + "/"
                    + CONTINUOUS_START_MAX_RETRIES);
                try {
                    startContinuousRecording();
                } catch (Throwable t) {
                    logger.warn("Continuous-mode start retry failed: " + t.getMessage());
                }
                if (!recording) {
                    scheduleContinuousStartRetry(epoch, attempt + 1);
                }
            }, CONTINUOUS_START_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Engine releasing — nothing to retry into.
        }
    }

    /**
     * Stops a continuous-mode recording. Mirrors the close path in
     * {@link #stopRecording()} but skips the YOLO baseline update, timeline
     * flush, hero JPEG, and final-segment metadata sidecar — none of which
     * apply when motion was never analyzed.
     */
    private void stopContinuousRecording() {
        if (recorder == null || !recording) {
            return;
        }
        logger.info("Stopping continuous recording");

        // Snapshot the final-segment file + active geo BEFORE the close.
        // After stopEventRecording the recorder clears its state and we
        // lose the binding from "active recording" → "the file that just
        // finalized." The recorder's own close path will run its
        // filename-based sidecar submit, but it skips event_*.mp4 to
        // avoid double-writing for sentry — which means continuous-mode
        // (which intentionally reuses the event_* prefix) gets skipped
        // too. We submit explicitly here, mirroring the rotation
        // listener's behavior for the final segment.
        File finalSegment = currentEventFile;
        com.overdrive.app.geo.GeoSnapshot finalStartGeo = null;
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null && enc.hasStartGeo()) {
                finalStartGeo = new com.overdrive.app.geo.GeoSnapshot(
                        enc.getStartGeoLat(), enc.getStartGeoLng(),
                        enc.getStartGeoAccuracy(), enc.getStartGeoAgeMs(),
                        enc.getStartGeoCapturedAtMs(), 0L);
            }
        } catch (Throwable t) {
            logger.warn("Continuous final-segment geo snapshot failed: " + t.getMessage());
        }

        try {
            recorder.stopEventRecording(true, 0);
        } catch (Throwable t) {
            logger.warn("Continuous recorder stop error: " + t.getMessage());
        }
        // Mirror stop on the OEM dashcam pipeline if it was running this
        // continuous segment alongside pano (gate is in startContinuousRecording).
        try {
            com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            // Only stop if (a) we owned it AND (b) the pipeline is still
            // the same instance we acquired ownership on. A rebuild between
            // start and stop (quality-mirror restart, ACC cycle) advances
            // the generation counter; the new instance's recording is
            // user-initiated, not ours.
            int curGen = com.overdrive.app.daemon.CameraDaemon
                .getOemDashcamPipelineGeneration();
            boolean canStop = oemEventOwned && curGen == oemEventOwnedGeneration;
            // Continuous segment ends on a hard boundary (no post-event
            // tail) — pass 0 so the OEM recorder finalizes immediately,
            // matching pano's stopEventRecording(true, 0) on this path.
            if (oemPipe != null) oemPipe.stopRecordingIfOwned(canStop, 0L);
            oemEventOwned = false;
            oemEventOwnedGeneration = -1;
        } catch (Throwable t) {
            logger.warn("OEM dashcam stop on continuous end failed: " + t.getMessage());
        }
        // Drop the segment listener that startContinuousRecording set so
        // a leftover closure can't fire during the gap between sessions.
        // Mirrors what the smart-mode close path does inside
        // stopRecording. If the next mode start is smart vs continuous,
        // its own setSegmentListener() will install the right one.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) enc.setSegmentListener(null);
        } catch (Throwable ignored) {}
        recording = false;
        // Surveillance overlay off for the inter-event gap (stops compositing +
        // releases the telemetry polling hold). No-op unless it was enabled.
        fireEventOverlayHook(false);
        currentEventFile = null;

        // Submit AFTER the close so the .mp4 has been renamed from .tmp.
        // LocationSidecarWriter is non-blocking — disk work happens on
        // its own background executor.
        if (finalSegment != null) {
            try {
                com.overdrive.app.geo.GeoSnapshot startGeo = finalStartGeo != null
                        ? finalStartGeo
                        : com.overdrive.app.geo.GeoSnapshot.empty();
                com.overdrive.app.geo.LocationSidecarWriter
                        .getInstance()
                        .submit(finalSegment, "recording", startGeo);
            } catch (Throwable t) {
                logger.warn("Continuous final-segment sidecar submit failed: " + t.getMessage());
            }
        }
    }

    /**
     * Starts recording an event with pre-record support.
     *
     * The encoder is always running and buffering frames. This method
     * triggers the flush of the pre-record buffer and starts writing to file.
     */
    /** Confidence floor for the baseline-refresh person-trigger. Matches
     *  Actor.FAR_NOTICE_MIN_CONF so a low-confidence FAR misclassification (the
     *  "parked bike read as person @0.44" profile) can NOT trigger a recording;
     *  only a solidly-detected person does. */
    private static final float BASELINE_PERSON_TRIGGER_MIN_CONF = 0.50f;

    /**
     * Baseline seed/refresh runs YOLO on the full quadrant regardless of motion.
     * When it positively detects a PERSON, that is a real subject the motion
     * pipeline MISSED — e.g. a person standing still, or one whose motion
     * centroid fell in the zone-rejected upper rows of a wide rear/side camera,
     * so no motion component ever formed and the normal motion→YOLO→trigger
     * chain never ran (field case: a person in the rear cam produced 0 motion
     * events for a whole session while the baseline refresh kept seeing them).
     * Route that person-positive signal to the recording trigger instead of
     * discarding it into the baseline-update only.
     *
     * Regression-safety (honours "never record a static non-threat"):
     *  - PERSON class only (classId 0). Vehicles/bikes/animals never trigger
     *    here — a parked car seen by a baseline refresh stays baseline-only, so
     *    this cannot resurrect the parked-car FP.
     *  - Confidence >= BASELINE_PERSON_TRIGGER_MIN_CONF, so a low-conf FAR
     *    misclassification can't fire it.
     *  - Only from the baseline-refresh lambdas, which already gate on
     *    `active && !AccMonitor.isAccOn()` (armed sentry) upstream.
     *  - startRecording() itself no-ops if already recording, and the
     *    refresh paths are gated `!recording`, so no storm.
     *
     * @return true if a person cleared the bar (a recording was requested).
     */
    private boolean maybeTriggerFromBaselinePerson(int quadrant,
            java.util.List<com.overdrive.app.ai.Detection> dets) {
        // ARMED re-check at the moment of TRIGGER (audit R3b Ext-2): the
        // doc above says the refresh lambdas gate on active/ACC "upstream",
        // but that gate ran at lambda ENTRY — up to ~1s of sequential
        // inference earlier. A disarm in that window let this path start a
        // recording with the engine logically down, and with no live tick
        // the clip had no stop path until the next disable().
        if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) return false;
        if (dets == null || dets.isEmpty() || recording) return false;
        // PERSON-TOGGLE gate (audit R4 ExtB-7): the baseline seed/refresh
        // inferences hardcode detectPerson=true (deliberately — persons must
        // never be folded INTO the baseline, so the refresh has to see
        // them), which means a person detection reaches this trigger even
        // when the user disabled Person detection. The trigger must honor
        // the toggle: with Person OFF, a person sighting is baseline
        // bookkeeping only, never a recording — recording a pedestrian
        // against an explicit vehicle-only config is a hard config
        // violation. classFilter == null means no filter (defaults, Person
        // on); empty means all-off (aiEnabled false upstream anyway).
        int[] cf = classFilter;
        if (cf != null) {
            boolean personEnabled = false;
            for (int c : cf) {
                if (c == 0) { personEnabled = true; break; }
            }
            if (!personEnabled) return false;
        }
        if (pipelineV2Config != null && pipelineV2Config.quadrantEnabled != null
                && quadrant >= 0 && quadrant < pipelineV2Config.quadrantEnabled.length
                && !pipelineV2Config.quadrantEnabled[quadrant]) {
            return false;  // quadrant disabled by user — respect the toggle
        }
        for (com.overdrive.app.ai.Detection d : dets) {
            if (d.getClassId() == 0
                    && d.getConfidence() >= BASELINE_PERSON_TRIGGER_MIN_CONF) {
                long confirmationWallMs = System.currentTimeMillis();
                long confirmationElapsedMs = android.os.SystemClock.elapsedRealtime();
                logger.info(String.format(
                    "Baseline-refresh person trigger: Q%d [%s] person @%.2f — motion "
                    + "pipeline missed it (static / zone-rejected); recording on YOLO evidence",
                    quadrant, MotionPipelineV2.QUADRANT_NAMES[quadrant], d.getConfidence()));
                long startedGeneration = startRecording();
                boolean startedThisEvent = false;
                synchronized (recordingLifecycleLock) {
                    if (startedGeneration >= 0
                            && recording
                            && recordingGeneration.get() == startedGeneration) {
                        // startRecording resets event-scoped evidence. This person
                        // detection is itself the authorizing evidence for the new
                        // event, so restore both the retention and continuation latches.
                        lastPersonConfirmationTimeMs = confirmationWallMs;
                        lastPersonConfirmationElapsedMs = confirmationElapsedMs;
                        eventEverSawPerson = true;
                        startedThisEvent = true;
                    }
                }
                return startedThisEvent;
            }
        }
        return false;
    }

    /** @return recording generation started by this call, or -1 when no start occurred. */
    private long startRecording() {
        if (recorder == null) {
            logger.error("Cannot start recording - recorder is null");
            return -1;
        }

        // ARMED guard (audit R3b Ext-2): no caller may start an event
        // recording for a disarmed engine — without a live tick loop the
        // post-record stop check never runs and the clip is orphaned.
        if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("startRecording refused: surveillance inactive / ACC ON");
            return -1;
        }

        // START CLAIM (audit R3b Ext-3): atomic check-and-claim replaces the
        // bare `if (recording) return`. Exactly one caller can be between
        // claim and commit; a concurrent second trigger (motion tick vs
        // baseline-person lambda for the same walking subject) bows out here
        // instead of overwriting currentEventFile mid-event. The claim is
        // released at the commit block, the refusal return, and on a
        // recorder-call throw below.
        synchronized (recordingLifecycleLock) {
            if (recording || recordingStartInFlight) {
                logger.debug("Already recording (or start in flight)");
                return -1;
            }
            recordingStartInFlight = true;
        }
        // Arm-session epoch at claim time (audit R4 ExtB-3): re-checked at
        // the commit. `active` alone can't distinguish "still THIS session"
        // from a disarm→rearm bounce completing inside the storage-resolve
        // window — the commit would then bind an event resolved under the
        // OLD session's storage state to the NEW session (cross-session
        // ghost event with stale dir attribution).
        final int claimEpoch = armEpoch;
        
        // SOTA: Storage cleanup happens off the trigger thread.
        // The periodic cleanup (StorageManager.startPeriodicCleanup, 30s cadence
        // with a 90%-of-limit threshold) is the steady-state mechanism. Doing
        // a synchronous scan + delete here was costing 100-200ms on the
        // motion-detection thread on near-full SD cards, which was the
        // dominant contributor to lag at motion onset. Two changes:
        //   1. SD-card mount check stays sync (cheap, microseconds, and we
        //      need a valid path before triggerEventRecording).
        //   2. ensureSurveillanceSpace fires on storageMaintenanceExecutor (NOT
        //      aiExecutor — that placement cost 43 s of zero YOLO during an SD
        //      remount storm; see the executor's own comment and the
        //      dispatch-after-resolve rationale at the execute() site below).
        //      A cheap noop if already under the limit, and worst case it runs
        //      in parallel with the recording itself. New recordings still
        //      write; old ones get pruned a beat later.
        com.overdrive.app.storage.StorageManager storageManager;
        try {
            storageManager = com.overdrive.app.storage.StorageManager.getInstance();
            com.overdrive.app.storage.StorageManager.StorageType type =
                    storageManager.getSurveillanceStorageType();
            if (type == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD &&
                    !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before recording - attempting remount");
                if (!storageManager.ensureSdCardMounted(true)) {
                    logger.error("SD card remount failed - event may write to stale path");
                }
            } else if (type == com.overdrive.app.storage.StorageManager.StorageType.USB &&
                    !storageManager.isUsbMounted()) {
                logger.warn("USB unmounted before recording - attempting remount");
                if (!storageManager.ensureUsbMounted(true)) {
                    logger.error("USB remount failed - event may write to stale path");
                }
            }
        } catch (Exception e) {
            logger.warn("Storage mount check failed: " + e.getMessage());
            storageManager = null;
        }
        final com.overdrive.app.storage.StorageManager smRef = storageManager;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "event_" + timestamp + ".mp4";
        // Re-read the live surveillance dir: enableSurveillance() may have
        // snapshotted the internal fallback during the boot mount-race, and the
        // SD/USB mount (incl. the smRef.ensure*Mounted attempt just above) may
        // have landed since. We MUST resolve LIVE (getLiveSurveillanceDir)
        // rather than read getSurveillanceDir(): the volatile surveillanceDir
        // field is frozen while the arm session is active (updateActiveDirectories
        // skips the surveillance branch), so getSurveillanceDir() would return
        // the stale internal fallback for a mount that landed after the bounded
        // enable-wait. getLiveSurveillanceDir() bypasses that freeze and falls
        // back to internal when the external is genuinely unavailable. Resolved
        // ONCE here (recording == false above), so the in-flight clip can't be
        // split across volumes; null-guard back to the snapshot.
        File trigDir = (smRef != null) ? smRef.getLiveSurveillanceDir() : eventOutputDir;
        if (trigDir == null) trigDir = eventOutputDir;
        // ENOSPC internal-spill: if the configured external is mounted-but-FULL,
        // redirect THIS event to the INTERNAL SURVEILLANCE dir so it isn't
        // quarantined as .broken on a packed card. Surveillance-bucket helper so
        // the spilled event_* clip stays in the surveillance reap pool.
        // Defensive: a throw returns trigDir unchanged.
        if (smRef != null && trigDir != null) {
            try {
                File spill = smRef.resolveSurveillanceTargetWithEnospcFallback(trigDir, 100L * 1024 * 1024);
                if (spill != null) trigDir = spill;
            } catch (Throwable t) {
                logger.warn("ENOSPC-fallback resolve failed (event): " + t.getMessage());
            }
        }
        if (trigDir != null && !trigDir.equals(eventOutputDir)) {
            eventOutputDir = trigDir;
        }
        currentEventFile = new File(trigDir, fileName);

        // SAME-SECOND COLLISION GUARD. The timestamp has one-second resolution and
        // "Event stitching" deliberately removed the post-stop cooldown, so a
        // retrigger inside the same second resolves to the path just written. That
        // is worse than an overwrite: the final-notification dedup keys on the
        // filename via lastFinalNotifiedEvent.getAndSet(), so the second event
        // would be treated as a duplicate of the first and the user would NEVER be
        // told about it. Uniquify instead — bounded probe, then fall back to
        // millisecond precision so we can never loop or reuse a name.
        if (currentEventFile.exists()) {
            File unique = null;
            for (int n = 2; n <= 9; n++) {
                File cand = new File(trigDir, "event_" + timestamp + "_" + n + ".mp4");
                if (!cand.exists()) { unique = cand; break; }
            }
            if (unique == null) {
                unique = new File(trigDir, "event_" + timestamp + "_"
                        + (System.currentTimeMillis() % 1000L) + ".mp4");
            }
            logger.info("Event filename collision on " + fileName
                    + " — using " + unique.getName());
            // Every downstream consumer (recorder trigger, hero sibling, dedup tag,
            // notifications) derives its name from currentEventFile, so redirecting
            // this reference is sufficient.
            currentEventFile = unique;
        }

        // Storage housekeeping — dispatched HERE, deliberately AFTER the
        // getLiveSurveillanceDir()/ENOSPC resolves above.
        //
        // Two constraints fix its position:
        //  (a) NOT on aiExecutor. ensureSurveillanceSpace() has no wall-clock
        //      bound (UNLIMITED_REAP) and its shell fallback budgets seconds per
        //      directory listing, so on a dropped/bridged SD mount it runs for
        //      tens of seconds. On the single-thread aiExecutor that stalled every
        //      detect() behind it — and because isAiRunning is latched true before
        //      dispatch, all AI dispatch sites then early-returned silently. Field
        //      evidence (log_2C26G4RL, 2026-07-19): SD remount failures coincided
        //      with ~43 s of zero YOLO while HIGH(loiter) motion fired
        //      continuously, and five real sequences expired WITHOUT trigger.
        //  (b) AFTER the directory resolves. Both the cleanup and
        //      getLiveSurveillanceDir()/resolveSurveillanceTargetWithEnospcFallback
        //      take StorageManager.surveillanceCleanupLock. Submitting the walk
        //      first let the maintenance thread win that lock and park THIS thread
        //      (AiLaneWorker) on the resolve for the walk's whole duration, which
        //      would have relocated the stall from the AI lane to the motion lane
        //      on the trigger path. Dispatching after the resolves means the engine
        //      thread never contends for that lock in this method.
        if (smRef != null) {
            try {
                storageMaintenanceExecutor.execute(() -> {
                    try { smRef.ensureSurveillanceSpace(50 * 1024 * 1024); }
                    catch (Exception e) { logger.warn("Async storage cleanup failed: " + e.getMessage()); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Engine releasing — skip housekeeping. The daemon's 30 s periodic
                // reclaim performs an equivalent surveillance pass independently.
            }
        }

        logger.info("Triggering event recording: " + currentEventFile.getAbsolutePath());
        logger.info(String.format("Pre-record: %d sec, Post-record: %d sec", 
                preRecordMs / 1000, postRecordMs / 1000));
        
        // Trigger event recording (flushes pre-record buffer).
        // Honor the boolean — savedFormat barrier can refuse on cold-boot
        // encoder warmup. See continuous-mode site above for rationale.
        // A throw releases the start claim before propagating (R3b Ext-3);
        // everything between claim and here is internally try/caught.
        GpuMosaicRecorder.TriggerResult eventTrig;
        try {
            eventTrig = recorder.triggerEventRecordingExclusive(
                    currentEventFile.getAbsolutePath(), postRecordMs);
        } catch (RuntimeException | Error e) {
            synchronized (recordingLifecycleLock) { recordingStartInFlight = false; }
            throw e;
        }
        // OWNERSHIP-EXPLICIT (audit R6 lifecycle #1): ALREADY_RECORDING means
        // another session owns the recorder (cross-mode bounce, or a
        // driving-mode recording started right after an ACC-ON disable while
        // this stale start was in flight). Neither commit nor unwind —
        // stopping the recorder here would kill THAT session's live
        // recording. Release the claim and bow out.
        if (eventTrig == GpuMosaicRecorder.TriggerResult.ALREADY_RECORDING) {
            logger.warn("Event start skipped — recorder already owned by another session");
            synchronized (recordingLifecycleLock) { recordingStartInFlight = false; }
            return -1;
        }
        if (eventTrig != GpuMosaicRecorder.TriggerResult.STARTED) {
            logger.warn("Event triggerEventRecording refused (encoder format "
                + "not ready); event skipped, will fire on next motion");
            currentEventFile = null;
            synchronized (recordingLifecycleLock) { recordingStartInFlight = false; }
            return -1;
        }
        // COMMIT under the lifecycle lock, with a disarm re-check (audit R3b
        // Ext-3 leg A): the storage resolves above can take seconds, and a
        // disable() in that window saw recording==false and skipped its stop.
        // Committing blind would leave the recorder live with the engine
        // dead. Interleavings: if disable()'s locked stop-check ran first, we
        // see active==false here and unwind the just-started recorder; if we
        // commit first, disable()'s locked check sees recording==true and
        // stops normally.
        long startNow = System.currentTimeMillis();
        long startElapsed = android.os.SystemClock.elapsedRealtime();
        synchronized (recordingLifecycleLock) {
            try {
            if (!active || armEpoch != claimEpoch) {
                logger.warn("Disarmed or re-armed mid-start — unwinding just-started event recording");
                try {
                    recorder.stopEventRecording(true, 0);
                } catch (Exception e) {
                    logger.warn("Unwind stop failed: " + e.getMessage());
                }
                currentEventFile = null;
                return -1;
            }
            // RECORDER LIVENESS AT COMMIT (audit R14-3 / ExtF-3). Between the
            // exclusive trigger above and this locked commit there is a
            // narrow window where a camera yield's onPreYield() stops the
            // recorder directly (it must finalize the moov before the camera
            // closes, and it does NOT take this lock). Committing
            // recording=true against that dead recorder minted a phantom
            // logical recording with no muxer: every trigger path gates on
            // `if (!recording)`, so real events were swallowed until the
            // post-record clock or reacquire repair (R13-4) cleaned it up.
            // Require the recorder to still be live at the commit point;
            // if the yield won the window, bow out — the motion that caused
            // this trigger is still present and re-fires after reacquire.
            if (!recorder.isRecording()) {
                logger.warn("Recorder died between trigger and commit (camera yield?) — "
                    + "abandoning event start; motion will re-trigger after reacquire");
                currentEventFile = null;
                return -1;
            }
            // Publish all stop-clock state before the volatile recording=true
            // write, so the frame worker cannot observe a live recording with
            // an uninitialized inactivity or ceiling clock.
            recordingStopTime = startNow + postRecordMs;
            recordingTriggerStartElapsedMs = startElapsed;
            confirmedPersonCeilingExtensionLogged = false;
        // STOP-CLOCK SEED. Owned here rather than by the callers so every
        // recording entry point receives both the inactivity and absolute
        // ceiling clocks.
        //
        // That was live: maybeTriggerFromBaselinePerson (the dawn/dusk
        // baseline-refresh trigger) called startRecording() without them, and
        // stopRecording() clears recordingTriggerStartElapsedMs but leaves
        // recordingStopTime at the 0 the previous event's clean stop wrote. The
        // clip then ran until ACC-on, and because the whole trigger path is
        // under `if (!recording)`, EVERY later event for the rest of the session
        // was folded into it instead of being recorded and notified separately.
        //
        // The two normal trigger sites assign the same logical values before
        // this call, so the authoritative seed above is behavior-preserving.
        // GENERATION FENCE: bump BEFORE the clears below so any YOLO lambda
        // scheduled during the inter-event gap (which carries the prior, post-stop
        // generation) sees gen != generationAtSchedule at its guard and SKIPS its
        // updateEventPeakSeverity/lastActors writes. Without this, an in-flight
        // inter-event lambda could complete AFTER eventPeakActors.clear() and
        // inject a stale actor into this fresh event's summary (spurious +1
        // personCount / wrong caption). stopRecording bumps gen too; bumping here
        // closes the start side. recordingGeneration is only read by the lambda
        // guard, so an extra bump is safe.
        long startedGeneration = recordingGeneration.incrementAndGet();
        // Fresh event → snapshot trigger frame for zero-latency fallback hero
        byte[] frameSnap = latestFrameRgb;
        lastTriggerFrameRgb = (frameSnap != null) ? frameSnap.clone() : null;
        // Fresh event → reset the latched peak severity. Each lastActors write
        // during this recording advances it; both Telegram stages read it.
        eventPeakSeverity = null;
        // Fresh event → clear the retained event-peak actors (see field comment).
        eventPeakActors.clear();
        // Fresh event → reset the empty-bright-motion discard latches.
        eventTriggerWasMotionOnly = false;
        eventEverApproaching = false;
        eventEverSawPerson = false;
        eventEverSawMovingObject = false;
        eventTriggerWasLateralMass = false;
        eventYoloSawRawDetections = false;
        eventTriggerWasAiTimeout = false;
        eventTriggerWasStrongThreat = false;
        eventMaxLuma = 0f;
        eventMinLuma = Float.MAX_VALUE;
        eventEverSawCoherentMotion = false;
        eventSawNightFpEvidence = false;
        eventSawUncharacterizedMotion = false;
        eventTriggerWasSalience = false;
        eventTriggerWasVigilance = false;
        eventSegmentFiles.clear();
        // Fresh event → seed the sentry automation events back to idle so this event's
        // verdict (published at finalize) is a genuine transition and re-fires even if
        // it matches the previous event's severity/object. See publishSurveillanceAutomationEvent.
        resetSurveillanceAutomationState();

        // Publish only after the new generation and every event-scoped field
        // are initialized. Post-inference commits use this same lifecycle lock,
        // so no frame can observe a live event in the previous generation.
        recording = true;

        // Surveillance telemetry overlay (opt-in, default off): enable burn-in
        // for the duration of this event clip. Pre-roll frames were encoded
        // before now, so they cannot carry it retroactively.
        fireEventOverlayHook(true);

        // OEM Dashcam parallel event recording. When the user has opted into
        // surveillance-driven OEM clips (oemDashcam.surveillance.enabled),
        // we ALSO trigger a recording on the OEM forward-sensor pipeline.
        //
        // Ownership: tryStartIfIdle returns true only when WE actually
        // opened the OEM clip. If OEM was already recording (user enabled
        // continuous mode), we MUST NOT stop it on event end — that would
        // finalize the user's clip prematurely. Track the result and
        // stopRecordingIfOwned matches the contract.
        oemEventOwned = false;
        oemEventOwnedGeneration = -1;
        try {
            // Read surveillanceMode directly — the legacy nested
            // oemDashcam.surveillance.enabled boolean is a one-way mirror of
            // surveillanceMode != off, kept in sync by the POST handler and the
            // migration. Reading the mode-tier accessor is the authoritative
            // path and survives any future drift between mirror and mode.
            String oemSurvMode = com.overdrive.app.config.UnifiedConfigManager
                .getOemSurveillanceMode();
            boolean oemSurvEnabled = !"off".equals(oemSurvMode);
            if (oemSurvEnabled) {
                com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                if (oemPipe != null && oemPipe.isRunning()
                        // Tail re-check (audit R4 ExtB-3): a concurrent
                        // stopRecording can finalize the pano event between
                        // our commit and this OEM start; its OEM stop no-ops
                        // (oemEventOwned still false), so starting here would
                        // orphan an OEM clip for an already-finalized event
                        // (self-terminating via postRecordMs, but pointless).
                        && recording) {
                    // Capture the generation BEFORE start so a concurrent
                    // pipeline rebuild (quality-mirror restart) doesn't
                    // make us think we own a clip on a new instance.
                    int gen = com.overdrive.app.daemon.CameraDaemon
                        .getOemDashcamPipelineGeneration();
                    // Pass our configured post-window so the OEM clip's tail
                    // matches pano's. Pre-roll is handled by the pre-record
                    // ring inside the OEM encoder (sized off
                    // surveillance.preRecordSeconds at OEM init time).
                    boolean started = oemPipe.tryStartIfIdle(postRecordMs);
                    if (started) {
                        oemEventOwned = true;
                        oemEventOwnedGeneration = gen;
                    }
                    logger.info("OEM dashcam event recording: "
                        + (started ? "started (owned, gen=" + gen + ")"
                                   : "skipped (already in flight)"));
                } else {
                    logger.debug("OEM dashcam event recording skipped — pipeline not running");
                }
            }
        } catch (Throwable t) {
            // Surveillance must NEVER break because OEM dashcam threw; this
            // is a parallel sink, not a primary one.
            logger.warn("OEM dashcam event trigger failed: " + t.getMessage());
        }

        // Per-segment hero / sidecar plumbing.
        // The recorder rotates .mp4 files at SEGMENT_DURATION_MS (2 min) so a
        // long event spans multiple files. Without this listener, the
        // ThumbnailBuffer + EventTimelineCollector would only flush against
        // the FIRST segment's filename at stopRecording — segments 2..N would
        // appear in the recordings library as plain MP4s with no badges, no
        // actor counts, no hero thumbnail. With this listener, each rotated
        // segment gets its own coherent (hero JPEG, per-actor JPEGs, JSON
        // sidecar) tied to its own filename. The metadata buffers are reset
        // on rotation so segment N+1 collects independent state.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            // RACING-STOP GUARD (audit R11-2 / ExtD-3): a stop that ran inside
            // this unlocked tail already detached the listener slot; installing
            // ours after that would latch a surveillance listener onto the
            // SHARED encoder in the disarmed state — normal driving mode
            // installs no listener of its own, so every subsequent DRIVING
            // segment rotation would fire it (spurious surveillance sidecars
            // for driving clips). Check before, compensate after.
            if (enc != null && recording) {
                enc.setSegmentListener((closedSegment, newSegment) -> {
                    // CRITICAL: this listener fires on the
                    // GpuSegmentFinalizer-N thread, which is what
                    // waitForFinalizers blocks the recording-close and
                    // pipeline-shutdown paths on. Heavy work here
                    // (JPEG compress, fsync) directly extends close
                    // latency and risks the 2s waitForFinalizers timeout
                    // on multi-segment events.
                    //
                    // Ordering matters: the engine thread's observe() may
                    // be writing slots for segment N+1 frames concurrently
                    // with this listener firing. Drain the buffer's slot
                    // snapshot AS THE FIRST ACT so we minimize the window
                    // during which N+1 observations land in the
                    // closed-segment's snapshot. observe() and
                    // drainSnapshotForAsync are both synchronized on the
                    // buffer instance; whichever takes the monitor first
                    // wins. By draining first here we shrink the window
                    // proportional to whatever observe() calls were
                    // already in flight when the listener fired.
                    //
                    // After the drain, reset currentEventFile + timeline
                    // so subsequent observe() / timeline-collect calls
                    // attribute to N+1.
                    final java.util.List<ThumbnailBuffer.Slot> closedSnap;
                    final ThumbnailBuffer buf = thumbnailBuffer;
                    if (buf != null) {
                        closedSnap = buf.drainSnapshotForAsync();
                    } else {
                        closedSnap = java.util.Collections.emptyList();
                    }
                    // Capture closed-segment start time BEFORE the timeline
                    // collector restarts for N+1.
                    final long closedSegmentStartMs =
                            timelineCollector.getRecordingStartTimeMs();
                    final java.util.List<Actor> closedSegmentActors = lastActors;

                    // Re-point the event file INLINE so subsequent observe()
                    // calls attribute to N+1. Cheap (no I/O).
                    if (newSegment != null) {
                        currentEventFile = newSegment;
                    }

                    // FLUSH THE CLOSED SEGMENT **BEFORE** RESTARTING THE
                    // COLLECTOR (audit R11-5 / ExtD-5-NEW). The flush's
                    // timeline tail calls stopAndWrite INLINE on this thread;
                    // the previous order (restart first, flush second) meant
                    // stopAndWrite consumed the RESET state — segment N's
                    // sidecar was written with zero spans/wrong origin, and
                    // collecting was flipped false so segment N+1 never
                    // collected at all (its events fell into the pre-ring,
                    // which startCollectingNoPreRing deliberately drops). On
                    // any multi-segment event, EVERY rotated segment's
                    // timeline sidecar was span-empty and the final segment
                    // got none (stop-tail stopAndWrite no-oped on
                    // !collecting). Flush-then-restart lets stopAndWrite
                    // consume the closed segment's real spans and hands the
                    // new segment a fresh collection. Single-segment events
                    // were never affected (no rotation).
                    if (closedSegment != null) {
                        // Remember the finalized earlier segment so a whole-event
                        // discard (shouldDiscardEvent → discardCurrentEvent) deletes
                        // it too. Without this, discard removes only the FINAL
                        // segment and N-1 earlier segments survive as orphan
                        // recordings with full metadata. Reset per event in
                        // startRecording.
                        eventSegmentFiles.add(closedSegment);
                        scheduleSegmentMetadataFlushWithSnapshot(
                                closedSegment, closedSegmentActors,
                                closedSegmentStartMs,
                                closedSnap,
                                /* syncHero = */ false,
                                /* timelineGen = */ timelineGen);
                    }

                    // Restart timeline collection for segment N+1 (after the
                    // flush above so the closed segment's spans were consumed,
                    // not reset). Capture the new generation so a stale stop
                    // cannot corrupt this segment's collection (audit R11-4).
                    if (newSegment != null) {
                        timelineGen = timelineCollector.startCollectingNoPreRing();
                    }
                });
                // Compensating check (audit R11-2 / ExtD-3): if a stop ran
                // its listener detach while we were installing, detach again.
                // A successor start cannot have installed ITS listener yet
                // (storage resolves take hundreds of ms), so this can only
                // remove the listener WE just installed.
                if (!recording) {
                    enc.setSegmentListener(null);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not register segment listener: " + e.getMessage());
        }
        
        // SOTA: Start timeline event collection for this recording.
        // Use the ACTUAL pre-record duration from the H.264 circular buffer, not the
        // configured preRecordMs. The circular buffer starts from the nearest keyframe,
        // which can be significantly longer than the configured pre-record window.
        // Example: configured preRecordMs=5000, but buffer flushed 14.1 sec of video.
        // If we use 5000ms as the origin, timeline events appear 9 seconds too early.
        long actualPreRecordMs = preRecordMs;
        try {
            HardwareEventRecorderGpu encoder = recorder.getEncoder();
            if (encoder != null) {
                long actual = encoder.getActualPreRecordDurationMs();
                if (actual > 0) {
                    actualPreRecordMs = actual;
                    logger.info("Timeline using actual pre-record duration: " + actual + "ms (configured: " + preRecordMs + "ms)");
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get actual pre-record duration: " + e.getMessage());
        }
        // RACING-STOP GUARD (audit R11-2 / ExtD-3): don't (re)start collection
        // for an event a concurrent stop has already finalized. If the stop
        // lands AFTER this start, the collector is left collecting into the
        // disarmed gap — harmless: nothing consumes it while disarmed, and the
        // next event's startCollecting resets it (the R11-4 generation token
        // additionally keeps any stale stop from touching that next
        // collection).
        if (recording) {
            timelineGen = timelineCollector.startCollecting(actualPreRecordMs);
        }
        
        logger.info("Event recording triggered successfully");
        return startedGeneration;
            } finally {
                recordingStartInFlight = false;
            }
        }
    }
    
    /**
     * Empty-bright-motion discard predicate (the shadow-over-parked-car FP).
     * Returns true ONLY when ALL six clauses hold — a strict conjunction with
     * fail-OPEN semantics (any uncertainty → keep the clip). Gated behind the
     * default-OFF config flag {@code surveillance.discardEmptyBrightMotionEvents},
     * so with the flag off this is always false → byte-identical behaviour.
     *
     * <p>The clauses jointly discriminate the FP from the documented real-threat
     * cases that ALSO end with 0 retained actors. KEEP overrides (any one keeps
     * the clip), checked before the discard conjunction:
     * <ul>
     *   <li>AI-racing: never decide while a YOLO inference is in flight/queued
     *       (its actor latches could still be gen-rejected at stop).</li>
     *   <li>Any YOLO-seen PERSON (even unconfirmed/NOTICE/static) → keep.</li>
     *   <li>Any YOLO-seen MOVING (!isStaticForTimeline) vehicle/bike → keep
     *       (a parked car is NOT moving, so it stays discardable).</li>
     *   <li>A side-cam lateral proximity-mass trigger (fisheye YOLO may 0-detect
     *       a real close lateral actor) → keep.</li>
     * </ul>
     * Then the discard conjunction (ALL must hold to delete):
     * <ul>
     *   <li>1 motion-only MEDIUM trigger; 4 never approached.</li>
     *   <li>5 bright everywhere (≥110, below the BYD ISP ~122 day clamp, above the
     *       NIGHT real-person brightest-quadrant ~96) rejects the NIGHT miss.</li>
     *   <li>6 no dark quadrant (≥70) rejects the CLOSE-ZONE miss (luma~46-61).</li>
     *   <li>2/3 no non-static retained actor and no confirmed loitering PERSON.</li>
     * </ul>
     * Person/object/lateral evidence can only PROTECT a clip here, never delete one.
     *
     * <p><b>Night path.</b> Clauses 5/6 (bright-everywhere) are meaningless after
     * dark and were deliberately un-satisfiable at night so a real intruder YOLO
     * missed in the dark was never deleted. When the scene is too dark for the
     * brightness test AND the SECOND opt-in flag {@code discardEmptyMotionAtNight}
     * is on, the predicate swaps the brightness clauses (and the AI-timeout keep,
     * which is structurally always-true at night since YOLO produces no anchor in
     * the dark) for two luma-free, YOLO-free criteria drawn from the native flow-
     * coherence signal (Stage 4b):
     * <ul>
     *   <li>KEEP if any tick showed a rigidly-translating component
     *       ({@code eventEverSawCoherentMotion}) — a real moving subject, never a
     *       waving flag / in-place shadow.</li>
     *   <li>DISCARD requires POSITIVE FP evidence ({@code eventSawNightFpEvidence}):
     *       confirmed in-place incoherent flow (tree/foliage/sweeping shadow) OR a
     *       whole-quadrant brightness-suppression event (headlight sweep / IR
     *       reflection / glare). Absent that evidence the clip is KEPT (fail-open),
     *       so a dark scene the pipeline couldn't characterize is never deleted.</li>
     * </ul>
     * All the shared KEEP-overrides (person/moving-object/lateral-mass/approach/
     * retained-actor) still apply on the night path. This is strictly opt-in behind
     * its own toggle with explicit low-light risk copy — with either flag off it is
     * byte-identical to before.
     */
    /** Why a clip that the discard feature examined was KEPT. Every KEEP path was
     *  previously silent, so a user reporting "this shadow event should have been
     *  discarded" produced no evidence at all and the cause had to be reverse-
     *  engineered from sidecars (invariant I7). One line at INFO, once per event,
     *  only when the feature is actually enabled. */
    private void logKeepReason(String reason) {
        logger.info(String.format(
                "Discard check KEPT event (reason=%s): motionOnly=%b person=%b movingObj=%b "
                + "approach=%b lateralMass=%b aiTimeout=%b yoloSawRaw=%b salience=%b vigilance=%b "
                + "maxLuma=%.0f minLuma=%.0f peakActors=%d",
                reason, eventTriggerWasMotionOnly, eventEverSawPerson,
                eventEverSawMovingObject, eventEverApproaching, eventTriggerWasLateralMass,
                eventTriggerWasAiTimeout, eventYoloSawRawDetections, eventTriggerWasSalience,
                eventTriggerWasVigilance,
                eventMaxLuma, (eventMinLuma == Float.MAX_VALUE ? -1f : eventMinLuma),
                eventPeakActors.size()));
    }

    private boolean shouldDiscardEvent() {
        // Flag default OFF → never discard (byte-identical). The night sub-flag is
        // an additional opt-in that only takes effect when the primary flag is on.
        boolean enabled;
        boolean nightEnabled;
        try {
            org.json.JSONObject sv = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            enabled = sv.optBoolean("discardEmptyBrightMotionEvents", false);
            nightEnabled = sv.optBoolean("discardEmptyMotionAtNight", false);
        } catch (Throwable t) {
            return false;
        }
        if (!enabled) return false;

        // HARD KEEP (no detection available): a discard is only safe when YOLO
        // could actually have produced actor evidence. When detection is
        // unavailable — daemon without Context/AssetManager, model load failure,
        // OR the user turned off every object class (aiEnabled false / empty
        // classFilter) — no Actor is ever created, so all the actor-evidence KEEP
        // latches are structurally impossible and the predicate degenerates to a
        // luma-only test that cannot tell a real person/vehicle from a shadow.
        // Refuse to discard in that mode (keep every clip, exactly as before
        // detection was disabled). Mirrors the aiAvailable definition (~:2123).
        if (!(useObjectDetection && yoloDetector != null && aiEnabled
                && (classFilter == null || classFilter.length > 0))) {
            return false;
        }

        // HARD KEEP (AI race): never decide to discard while a YOLO inference is
        // in flight or queued. Its updateEventPeakSeverity() write — the only
        // setter of eventEverSawPerson / eventEverSawMovingObject /
        // eventEverApproaching / eventPeakActors — is gen-rejected by the
        // stop-time recordingGeneration bump (:6088) if it lands after stop, so a
        // person/approach detection on the final lambda would never latch and a
        // real-actor clip could be deleted. Strictly more conservative (only ever
        // turns a discard into a KEEP); no effect when the flag is off.
        if (isAiRunning.get() || !aiQuadrantQueueIsEmpty()) { logKeepReason("aiInFlight"); return false; }

        // Clause 1: native motion-source trigger (the FP path). HIGH is
        // intentionally eligible: coherent moving shadows can be classified
        // HIGH(loiter), while tracker/deferred-person safety paths remain
        // excluded by the source latch.
        if (!eventTriggerWasMotionOnly) { logKeepReason("notMotionOnly"); return false; }
        // Hard person KEEP: a PERSON was YOLO-classified at any point — even
        // unconfirmed (1-2 frame far/mid lateral crosser), even NOTICE, even
        // static. The eventPeakActors retain gate (:578) only stores a
        // CONFIRMED person, so clause 3 below alone would let a real but
        // unconfirmed daytime pedestrian fall through all six clauses and be
        // deleted. This latch closes that false-negative; the shadow/leaf FP
        // (zero person detections) is unaffected and still discardable.
        if (eventEverSawPerson) { logKeepReason("sawPerson"); return false; }
        // Hard moving-object KEEP: a YOLO-classified MOVING (!isStaticForTimeline)
        // vehicle/bike — a close vehicle paralleling the car stays at NOTICE and
        // the retain gate (:578) drops it, yet it is a real moving object. A
        // PARKED car is excluded (eventEverSawMovingObject gated on
        // !isStaticForTimeline) so the shadow-over-parked-car FP stays discardable.
        if (eventEverSawMovingObject) { logKeepReason("movingObject"); return false; }
        // Hard lateral-mass KEEP: a side-cam proximity-mass trigger (cpp:768) is a
        // possible real close lateral actor that fisheye barrel distortion can
        // make YOLO miss entirely (project_fisheye_dewarp) — keep it rather than
        // risk deleting a real-person/vehicle clip in a bright lot.
        if (eventTriggerWasLateralMass) { logKeepReason("lateralMass"); return false; }
        // eventTriggerWasSalience is intentionally NOT a keep clause — but note that
        // adding one would be redundant, because a salience clip is ALREADY kept by
        // two pre-existing clauses, on both paths:
        //   - day (:8313): salience fires precisely when !sequenceConfirmed, so
        //     eventTriggerWasAiTimeout is true; if YOLO also saw nothing (this
        //     channel's own premise) the aiTimeout-yoloBlind KEEP fires.
        //   - night (:8328): salience requires positive flow coherence on 6
        //     consecutive ticks, which is the same OR test that latches
        //     eventEverSawCoherentMotion → KEEP.
        // So enabling "discard non-actor recordings" does NOT prune salience clips.
        // That is the conservative direction (a YOLO-blind clip with object-grade
        // motion geometry is the last thing that should be auto-deleted), but it
        // means the salience toggle's own copy must not promise otherwise — the
        // recall it adds is not offset by the discard. Left as-is deliberately:
        // making these deletable would require weakening two KEEPs that exist to
        // protect real YOLO-invisible subjects (invariant I6).
        // Clause 4: never approached (shared — an approacher is real day or night).
        if (eventEverApproaching) { logKeepReason("approaching"); return false; }

        // ── Day vs night split ────────────────────────────────────────────────
        // "Dark" = the bright-everywhere clause (5) cannot be satisfied. On a
        // bright event we run the original day predicate unchanged (byte-identical
        // when the night flag is off). On a dark event the brightness clauses are
        // meaningless, so we either KEEP (night flag off — the original behaviour)
        // or evaluate the luma-free night criteria (night flag on).
        boolean darkScene = eventMaxLuma < DISCARD_BRIGHT_LUMA_THRESHOLD
                || eventMinLuma == Float.MAX_VALUE
                || eventMinLuma < DISCARD_DARK_FLOOR;
        if (!darkScene) {
            // DAY PATH (unchanged).
            // Hard AI-timeout KEEP: the recording fired purely on the AI-timeout
            // fallback with NO in-sequence YOLO confirmation — the path that exists
            // precisely to trust motion when an object is too small/dark/distorted
            // for YOLO but real. Such a clip could contain a YOLO-missed real actor
            // (documented bright-daytime / fisheye whole-event 0-detection), so it
            // must never be auto-deleted. The shadow-over-parked-car FP is
            // unaffected: it gets its AI gate opened by the parked car's own YOLO
            // boxes (sequenceConfirmed==true at trigger → this latch false → still
            // discardable). Clauses 5/6 already held (darkScene is false here).
            // Narrowed: the KEEP applies only when YOLO never resolved ANY class on
            // this event. Its rationale is "the object was too small/dark/distorted
            // for YOLO but real" — which is only credible if YOLO came up empty. When
            // YOLO DID produce raw detections and the filters judged them standing
            // scenery (baseline-suppressed parked cars — the shadow-FP signature),
            // that rationale does not hold and the clip stays discardable.
            //
            // Without this, the latch was unconditionally true on any scene with an
            // established baseline: it derives from sequenceConfirmed, whose only
            // setter fires AFTER the baseline filter, so the parked-car boxes it
            // assumed would arm it are exactly the ones the baseline suppresses. That
            // made the whole discard feature inert (verified on-device 10:26 event:
            // two 0.78-confidence YOLO runs, 0 actors, clip kept, no discard log).
            //
            // Still fail-safe in the miss direction: every actor-evidence KEEP above
            // (person / moving object / approaching / lateral mass) runs first, and a
            // YOLO-blind event keeps this KEEP intact.
            if (EmptyMotionDiscardPolicy.shouldKeepAsYoloBlind(
                    eventTriggerWasAiTimeout, eventYoloSawRawDetections)) {
                logKeepReason("aiTimeout-yoloBlind");
                return false;
            }
        } else {
            // NIGHT PATH. Requires the second opt-in flag; otherwise keep (this is
            // exactly the pre-existing clause-5 KEEP for dark scenes).
            if (!nightEnabled) return false;
            // The AI-timeout keep is skipped here ON PURPOSE: in the dark YOLO
            // produces no in-sequence anchor, so eventTriggerWasAiTimeout is
            // structurally almost always true and would make the night path inert.
            // Its safety role is taken over by the two luma-free criteria below.
            //
            // KEEP: any tick showed a rigidly-translating component — a real moving
            // subject (person/vehicle). Foliage/shadow oscillates in place and a
            // headlight/IR event is a global illumination shift, neither of which
            // ever reads coherent. This is the night analogue of the day person/
            // moving-object keeps, using motion geometry instead of YOLO class.
            if (eventEverSawCoherentMotion) return false;
            // KEEP: any quadrant had a formed motion blob the flow stage could not
            // resolve (texture-poor dark crop, flow==-1) and that wasn't a brightness
            // event — a possible dim real person BOTH YOLO and flow missed. Without
            // this, FP evidence latched from a DIFFERENT quadrant (a swaying branch)
            // could delete a clip whose real subject was simply unmatchable — the
            // scene-wide-OR false-negative the audit found. Restores fail-open.
            if (eventSawUncharacterizedMotion) return false;
            // DISCARD requires POSITIVE FP evidence: confirmed in-place incoherent
            // flow (tree/foliage/sweeping shadow) OR a whole-quadrant brightness-
            // suppression event (headlight sweep / IR reflection / glare). Without
            // it, the pipeline never characterized the motion as an FP, so a dark
            // scene it simply couldn't resolve (real intruder in a texture-poor
            // dark crop, flow signal == -1 all event) is KEPT — fail-open.
            if (!eventSawNightFpEvidence) return false;
        }
        // Clauses 2 & 3: no non-static actor of any class, and no confirmed person
        // (a still loiterer is a confirmed PERSON → KEEP-override).
        for (Actor a : eventPeakActors.values()) {
            if (!a.isStaticForTimeline) return false;                          // a real moving actor existed → keep
            if (a.classGroup == Actor.ClassGroup.PERSON && a.confirmed) return false;  // confirmed loiterer → keep
        }
        // All clauses held → this is an empty-motion FP (bright shadow by day, or
        // confirmed incoherent-flow / illumination-artifact motion at night).
        logger.warn(String.format(
                "Discarding empty motion event (%s-FP): motionOnly=%b approaching=%b "
                + "maxLuma=%.0f minLuma=%.0f coherentSeen=%b nightFpEvidence=%b peakActors=%d peakSev=%s "
                + "aiTimeout=%b yoloSawRaw=%b salience=%b vigilance=%b",
                darkScene ? "night" : "bright",
                eventTriggerWasMotionOnly, eventEverApproaching, eventMaxLuma,
                (eventMinLuma == Float.MAX_VALUE ? -1f : eventMinLuma),
                eventEverSawCoherentMotion, eventSawNightFpEvidence,
                eventPeakActors.size(), eventPeakSeverity,
                eventTriggerWasAiTimeout, eventYoloSawRawDetections,
                eventTriggerWasSalience, eventTriggerWasVigilance));
        return true;
    }

    /**
     * Delete a just-finalized event that {@link #shouldDiscardEvent()} flagged as
     * an empty-bright-motion false positive: the mp4 + all sidecars (.json/.srt/
     * .jpg + per-actor thumbs) + the H2 index row. No notification is sent (the
     * caller skips the publish/Telegram block). Called from stopRecording BEFORE
     * flushSegmentMetadata, so the hero/JSON/SRT are usually never even written.
     */
    private void discardCurrentEvent() {
        // Legacy no-arg entry: reads the live field. stopRecording no longer
        // uses this — it passes its stop-time snapshot to the File overload
        // (audit R8-2 / ExtC-3: the field is nulled before the tail runs).
        discardCurrentEvent(currentEventFile);
    }

    /** File-arg variant (audit R8-2 / ExtC-3): operates on the caller's
     *  snapshot so a successor event's currentEventFile is never read here. */
    private void discardCurrentEvent(File f) {
        if (f == null) return;
        String name = f.getName();
        // Drain any in-flight per-segment metadata writes FIRST. Earlier
        // segments were handed to segmentMetadataExecutor by the rotation
        // listener; a writer still in flight would otherwise re-create a
        // hero/JPEG/JSON sidecar AFTER we delete it, leaving an orphan. Bounded
        // (2s) — same budget the close path uses; on timeout we proceed anyway
        // (deleteEventSidecars is idempotent and a stray sidecar is cosmetic).
        try { drainSegmentMetadata(2_000); } catch (Throwable ignored) {}
        // Also drain the timeline writer (JSON + SRT live on a separate
        // single-thread executor, NOT covered by drainSegmentMetadata which only
        // tracks the JPEG/hero writers). Without this, a queued earlier-segment
        // .json/.srt could be written just AFTER the delete loop below runs,
        // leaving an orphan sidecar for a discarded event.
        try {
            if (timelineCollector != null) timelineCollector.awaitWrites(2_000);
        } catch (Throwable ignored) {}
        // Whole-event discard: delete EVERY finalized segment of this event, not
        // just the final one. The decision (shouldDiscardEvent) is whole-event;
        // earlier segments were collected by the rotation listener into
        // eventSegmentFiles. Deletes strictly more files only when discard
        // already fired for the whole event, so it cannot worsen any
        // false-negative. The final segment (currentEventFile) is deleted below.
        for (File seg : eventSegmentFiles) {
            if (seg == null || seg.equals(f)) continue;       // skip the final segment (handled below)
            try {
                if (seg.exists()) seg.delete();
            } catch (Throwable ignored) {}
            try {
                com.overdrive.app.server.RecordingsApiHandler.deleteEventSidecars(seg, seg.getName());
            } catch (Throwable t) {
                logger.debug("discardCurrentEvent earlier-segment cleanup failed: " + t.getMessage());
            }
        }
        eventSegmentFiles.clear();
        try {
            if (f.exists()) f.delete();                       // the mp4 (final segment)
        } catch (Throwable ignored) {}
        try {
            // Sidecars (.json/.jpg/per-actor thumbs) + H2 row + cache invalidate,
            // and .srt (the public wrapper adds .srt parity).
            com.overdrive.app.server.RecordingsApiHandler.deleteEventSidecars(f, name);
        } catch (Throwable t) {
            logger.debug("discardCurrentEvent sidecar cleanup failed: " + t.getMessage());
        }
        // Resolve the dangling start-stage push banner. If pushNotices is on, a
        // "Recording in progress" banner fired at trigger (publishMotionNotification,
        // same tag) and its normal replacement (publishMotionFinal) is now skipped
        // because we discarded — leaving a stale banner that taps through to a
        // deleted clip. Emit a final, auto-closing push on the SAME tag so the
        // service worker REPLACES the banner rather than leaving it. Gated by the
        // identical isPushNotices() check, so this is a no-op when start banners
        // were never sent (default config) — preserving byte-identical behaviour.
        try {
            if (config != null && config.isPushNotices()) {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("filename", name);
                d.put("stage", "discarded");
                d.put("autoClose", true);
                com.overdrive.app.notifications.NotificationBus.get().publish(
                        new com.overdrive.app.notifications.NotificationEvent(
                                "surveillance.motion.notice",
                                com.overdrive.app.notifications.NotificationEvent.Severity.INFO,
                                "Motion cleared",
                                "No event recorded",
                                notificationTagFor(name),     // SAME tag → replaces the start banner
                                "/events.html?filter=sentry",
                                d));
            }
        } catch (Throwable t) {
            logger.debug("discardCurrentEvent banner-clear failed: " + t.getMessage());
        }
        // Symmetric Telegram compensation. If telegramSendStartPing is on, a
        // start-stage "motion detected" text fired at trigger embedding this
        // filename + a now-dead "/download <file>" command. Telegram has no
        // edit/delete API, so the only honest compensation is a follow-up plain
        // text saying the event was cleared. Use sendMessage (NOT notifyMotion,
        // which would re-emit the filename + /download footer for a deleted
        // file). Gated by the identical isTelegramSendStartPing() check → strict
        // no-op when no start ping was ever sent (default config).
        try {
            if (config != null && config.isTelegramSendStartPing()) {
                com.overdrive.app.telegram.TelegramNotifier.sendMessage(
                        com.overdrive.app.telegram.TelegramMessages.get(
                                "motion.cleared_no_event"),
                        "MOTION");
            }
        } catch (Throwable t) {
            logger.debug("discardCurrentEvent telegram-clear failed: " + t.getMessage());
        }
        logger.info("Discarded empty bright motion event: " + name);
    }

    /**
     * Stops recording an event with post-record support.
     */
    private void stopRecording() {
        synchronized (recordingLifecycleLock) {
            stopRecordingLocked();
        }
    }

    /** Complete smart-event stop transaction. Caller holds recordingLifecycleLock. */
    private void stopRecordingLocked() {
        if (recorder == null || !recording) {
            return;
        }
        
        // SOTA: Update detection baseline from the last YOLO detections of this event.
        // This is the event-driven baseline update — zero extra inferences.
        // Only updates the quadrant where the event happened.
        // P1 #13: snapshot the slot once via getAndSet() so a late aiExecutor
        // lambda writing the same slot can't corrupt the value mid-read.
        if (lastEventQuadrant >= 0) {
            YoloPublication pub = lastYoloPublication.getAndSet(lastEventQuadrant, null);
            if (pub != null && pub.detections != null) {
                // updateFromEventEnd needs the bbox-coord-space dims that
                // produced these detections — pull from the publication.
                // Since the M2 fix, mappable foveated runs publish QUADRANT
                // space (qH=240); only the foveated-without-affine fallback
                // still publishes 640-window space.
                int qH = pub.frameHeightPx > 0 ? pub.frameHeightPx : (THUMBNAIL_HEIGHT / 2);
                int qW = qH >= FOVEATED_CROP_SIZE_PX ? FOVEATED_CROP_SIZE_PX : (THUMBNAIL_WIDTH / 2);
                // SPACE GATE (audit R4 ExtB-9): never fold WINDOW-space
                // coordinates into the quadrant-space baseline — the moving
                // window's normalized coords are incomparable with quadrant
                // entries (inert-but-polluting unconfirmed entries, mixed-
                // space spatial vetoes). Losing one event-end refresh on the
                // pathological no-affine tick is free (fail-open, same
                // posture as the in-lambda null path).
                if (qH < FOVEATED_CROP_SIZE_PX) {
                    detectionBaseline.updateFromEventEnd(lastEventQuadrant, pub.detections, qW, qH);
                } else {
                    logger.debug("Event-end baseline update skipped (window-space publication)");
                }
            }
            lastEventQuadrant = -1;
        }
        
        // Stop immediately (post-record already handled by timeout). Synchronously
        // joins the encoder drainer thread, after which no further frames or
        // segment-rotation listener calls can fire.
        recorder.stopEventRecording(true, 0);
        // Capture the finalized event-owned OEM mirror for gated delivery.
        com.overdrive.app.camera.OemDashcamPipeline.FinalizedClip oemFinalizedClip = null;
        try {
            com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            // Only stop if (a) we owned it AND (b) the pipeline is still
            // the same instance we acquired ownership on. A rebuild between
            // start and stop (quality-mirror restart, ACC cycle) advances
            // the generation counter; the new instance's recording is
            // user-initiated, not ours.
            int curGen = com.overdrive.app.daemon.CameraDaemon
                .getOemDashcamPipelineGeneration();
            boolean canStop = oemEventOwned && curGen == oemEventOwnedGeneration;
            // Engine has already absorbed the post-record window via its
            // own loop (lastMotionTime + postRecordMs gate). Pass 0 so the
            // OEM recorder finalizes promptly, matching pano's behaviour.
            if (oemPipe != null) {
                oemFinalizedClip = oemPipe.stopRecordingIfOwnedAndGetFinalizedClip(
                        canStop, 0L);
            }
            oemEventOwned = false;
            oemEventOwnedGeneration = -1;
        } catch (Throwable t) {
            logger.warn("OEM dashcam stop on event end failed: " + t.getMessage());
        }
        // Clear every value that authorizes continuation before publishing
        // recording=false. startRecording() uses the same lifecycle lock, so
        // a successor cannot inherit these values or have them cleared by this
        // event's stop tail.
        recordingStopTime = 0;
        recordingTriggerStartElapsedMs = 0;
        confirmedPersonCeilingExtensionLogged = false;
        firstMotionTime = 0;
        firstMotionElapsedMs = 0;
        peakThreatDuringSequence = 0;
        recording = false;
        // Surveillance overlay off at event end (see startRecording's enable).
        fireEventOverlayHook(false);
        // Parked-idle throttle ramp-DOWN is handled by the hasActiveMotion()
        // edge detector at the end of processFrameV2. firstMotionTime is
        // already reset above, so that reconciliation observes the idle state.
        lastRecordingStopTime = System.currentTimeMillis();  // Track when we stopped
        // Monotonic twin for duration-only consumers (the salience min-gap). Stamped
        // here so every stop path — post-record, hard ceiling, discard, disable —
        // covers both clocks.
        lastRecordingStopElapsedMs = android.os.SystemClock.elapsedRealtime();
        // FIX (B1/H-a): bump the generation counter NOW — before the publish
        // path reads lastActors. Any aiExecutor lambda still in flight will,
        // when it completes, observe gen != generationAtSchedule and skip its
        // writes (Actor/Thumbnail/lastActors mutation). Without this fence,
        // a late lambda landing between the recorder stop and publishMotionFinal
        // could overwrite lastActors, making the notification mis-attribute
        // the event. (audit R8-2 / ExtC-3: the shared-state clears now run
        // HERE, immediately after this bump — the publish path below consumes
        // the local snapshots taken in the block that follows, not the fields.)
        recordingGeneration.incrementAndGet();

        // ── EARLY SHARED-STATE CLEARS (audit R8-2 / ExtC-3) ─────────────────
        // The lifecycle lock stays held through this tail, so no successor can
        // start until all old-event mutations complete. Keep the stop-time
        // snapshots as a second ownership boundary for the slow metadata and
        // notification work.
        //
        // NOTE: stoppingFile is snapshotted HERE rather than at method entry
        // (deviation from the prescribed fix): a segment rotation firing
        // between entry and the recorder join above would make an entry-time
        // snapshot point at the second-to-last segment. Here the drainer is
        // joined (no further rotations) and no successor can have overwritten
        // the field yet.
        final java.io.File stoppingFile = currentEventFile;
        // Actor/scenery snapshots for the notify tail — MUST be taken before
        // the clears below: finalNotificationActors() reads lastActors, and
        // snapshotSceneryIds() reads the thumbnail buffer's staticness
        // verdicts, which thumbnailBuffer.clear() wipes.
        final java.util.List<Actor> stoppingActors = lastActors;
        final java.util.List<Actor> stoppingNotifActors = finalNotificationActors();
        final java.util.Set<Long> stoppingSceneryIds = snapshotSceneryIds(stoppingNotifActors);
        // Final-segment anchor for the metadata flush, frozen before a later
        // timeline restart can re-point it.
        final long stoppingSegmentStartMs = timelineCollector.getRecordingStartTimeMs();
        // Timeline generation snapshot (audit R11-4 / ExtD-5): frozen with the
        // other stop-time snapshots. If a later startCollecting bumps
        // the generation before our KEEP flush runs, the flush's inline
        // stopAndWrite sees the mismatch and no-ops instead of flipping the
        // successor's collection off and writing a zero-span sidecar.
        final int stoppingTimelineGen = timelineGen;
        // Hero/thumbnail slots: drain (same drain flushSegmentMetadata used to
        // do inside the KEEP branch — also preserves the staticness verdicts)
        // and then clear the rest so a successor event starts with an empty
        // buffer instead of having its early captures wiped seconds from now.
        final java.util.List<ThumbnailBuffer.Slot> stoppingSlots;
        {
            ThumbnailBuffer tbAtStop = thumbnailBuffer;
            if (tbAtStop != null) {
                stoppingSlots = tbAtStop.drainSnapshotForAsync();
                tbAtStop.clear();
            } else {
                stoppingSlots = java.util.Collections.emptyList();
            }
        }
        // Detach the segment listener so a stale lambda from a previous event
        // can't reset state for the next event's segments. (Moved up, audit
        // R8-2 / ExtC-3: the recorder drainer is already joined, so this
        // listener can never fire for OUR event again — detaching it now means
        // we can no longer kill a successor event's just-installed listener.)
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) enc.setSegmentListener(null);
        } catch (Exception ignored) {}
        // (audit R8-2 / ExtC-3) Null the field BEFORE the slow tail so a
        // successor's own finalization can never be orphaned by a late clear.
        currentEventFile = null;
        // Reset Actor state for the next event so IDs / dwell windows don't
        // leak across recordings. (Moved up, audit R8-2 / ExtC-3: a late
        // reset() restarted a successor's actorId space mid-event.)
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();

        if (stoppingFile != null && stoppingFile.exists() && shouldDiscardEvent()) {
            // Empty-bright-motion false positive (shadow over a parked car, etc.):
            // delete the clip + sidecars + H2 row and emit NO notification. Runs
            // BEFORE flushSegmentMetadata so the hero/JSON/SRT are usually never
            // even written. Flag-gated (default OFF) — see shouldDiscardEvent().
            //
            // Claim the same per-event dedup slot the KEEP branch uses (below):
            // the double-stop race (disable() on the control thread vs the engine
            // post-record stop, documented at the KEEP-branch dedup) can re-enter
            // this branch for one event during discardCurrentEvent's ~4s drain
            // window, double-firing the un-deduped "Motion cleared" Telegram
            // (TelegramNotifier.sendMessage has no replace). Claim by filename so
            // only the first entry compensates; the deletes are idempotent anyway.
            String discardName = stoppingFile.getName();   // snapshot, not field (audit R8-2 / ExtC-3)
            if (!discardName.equals(lastFinalNotifiedEvent.getAndSet(discardName))) {
                discardCurrentEvent(stoppingFile);
            } else {
                logger.debug("Discard compensation already emitted for " + discardName
                        + "; skipping duplicate");
            }
        } else if (stoppingFile != null && stoppingFile.exists()) {
            logger.info( String.format("Saved: %s (%d KB)",
                    stoppingFile.getName(), stoppingFile.length() / 1024));

            // Flush metadata for the FINAL segment. Earlier segments were
            // scheduled via the rotation listener and run on
            // segmentMetadataExecutor in the background.
            //
            // SOTA split: the flush writes the hero synchronously on this
            // thread (so the publish path below sees a deterministic on-disk
            // hero file) and only schedules the per-actor JPEGs
            // asynchronously. No drainSegmentMetadata is needed before
            // publish — the hero is already on disk by the time the call
            // returns.
            //
            // (audit R8-2 / ExtC-3) Call the WithSnapshot variant directly
            // with the stop-time snapshots (file / actors / slots / segment
            // anchor) instead of flushSegmentMetadata(currentEventFile),
            // which read the now-cleared lastActors + thumbnailBuffer fields
            // and would otherwise consume a successor event's state.
            scheduleSegmentMetadataFlushWithSnapshot(stoppingFile, stoppingActors,
                    stoppingSegmentStartMs, stoppingSlots, /* syncHero = */ true,
                    /* timelineGen (audit R11-4) = */ stoppingTimelineGen);

            // Two-stage notification: replace the "Recording in progress…"
            // banner that fired at recording start with the rich threat
            // summary + hero image. Same notification tag → OS replaces, not
            // stacks. Telegram gets the equivalent rich path with photo.
            // Use the final segment's metadata for the user-facing notif —
            // earlier segments already published their own banners on close.
            String videoName = stoppingFile.getName();     // snapshot, not field (audit R8-2 / ExtC-3)
            String heroSibling = videoName.replace(".mp4", ".jpg");
            File heroSiblingFile = new File(stoppingFile.getParentFile(), heroSibling);

            // If ThumbnailBuffer didn't write a YOLO-derived hero (no actor
            // ever classified during this event — e.g. very short motion-only
            // clips, or every actor was filtered out as static-NOTICE
            // background) fall back to a single keyframe extracted from the
            // recorded MP4 so Telegram and the PWA push always have an image
            // to show. Without this, the user gets a text-only "Motion
            // detected" alert with no preview, which looks broken.
            //
            // Wrapped in a per-call timeout: MediaMetadataRetriever can
            // hang indefinitely on a malformed mp4 (truncated moov, corrupt
            // sample tables — exactly what a SIGKILL boundary or SD-card
            // unmount mid-write produces). Without the timeout, this path
            // would block the engine thread forever, wedging stop +
            // disable + shutdown. Same pattern as sweepOrphanHeroThumbnails
            // (5s budget, daemon worker, accept thread leak on hang).
            // Which SOURCE produced the image? The body text says "close-up view" to
            // tell the user the attachment is the foveated crop around the threat —
            // true for a ThumbnailBuffer hero, false for an MP4 keyframe, which is a
            // plain wide frame with no box. publishMotionFinal can only see that a
            // file exists, so without this flag it captioned every keyframe as a
            // close-up. Increasingly reachable now that pickHero returns null for an
            // all-scenery pool.
            //
            // Taken from the flag flushSegmentMetadata set when
            // writeHeroFromSnapshot actually returned a File, NOT from
            // heroSiblingFile.exists(): existence cannot tell a hero written a
            // moment ago from an orphan .jpg an earlier run left at the same path.
            // Matched by name so a stale latch from a previous event reads false.
            final boolean heroIsCloseUp = videoName.equals(lastSyncHeroMp4Name.get());
            if (!heroSiblingFile.exists()) {
                writeFallbackHeroWithTimeout(stoppingFile, heroSiblingFile, 5_000L);  // snapshot (audit R8-2 / ExtC-3)
            }

            String heroName = heroSiblingFile.exists() ? heroSibling : null;
            String heroPath = heroSiblingFile.exists() ? heroSiblingFile.getAbsolutePath() : null;
            // Per-event dedup remains as defense in depth for retries or future
            // alternate finalization paths. The lifecycle lock now serializes
            // concurrent stop callers, but the final notification transports
            // do not provide their own deduplication.
            // Atomic claim: getAndSet returns the PREVIOUS value; only the
            // caller that finds it != videoName proceeds, so two interleaving
            // stopRecording() entries for the same event can't both emit.
            // (audit R8-2 / ExtC-3) Pass the stop-time snapshots: the helpers'
            // no-snapshot overloads read lastActors / thumbnailBuffer /
            // currentEventFile, all cleared above.
            if (!videoName.equals(lastFinalNotifiedEvent.getAndSet(videoName))) {
                try { publishMotionFinal(videoName, heroName, heroIsCloseUp,
                        stoppingNotifActors, stoppingSceneryIds); }
                catch (Throwable t) { logger.debug("publishMotionFinal threw: " + t.getMessage()); }
                try { sendFinalTelegramNotification(videoName, heroPath,
                        stoppingNotifActors, stoppingActors, stoppingSceneryIds, stoppingFile,
                        oemFinalizedClip); }
                catch (Throwable t) { logger.debug("sendFinalTelegramNotification threw: " + t.getMessage()); }
            } else {
                logger.debug("Final notification already sent for " + videoName + "; skipping duplicate");
            }
        }

        // (audit R8-2 / ExtC-3) The segment-listener detach, currentEventFile
        // null, actorTracker.reset(), lastActors clear and thumbnailBuffer
        // clear that used to live here were MOVED UP to immediately after the
        // generation bump — see the EARLY SHARED-STATE CLEARS block. Only
        // dropAllTrackerLocks() deliberately remains at the very end:
        //
        // Drop all native texture-tracker locks on stop. The NCC age-out and
        // YOLO-heartbeat teardown that would normally retire a track run ONLY
        // inside the `if (recording)` block, so a track that was still locked at
        // stop time (a person who stood still until the 3× hard ceiling fired)
        // would otherwise persist "active" forever — a zombie that the
        // standing-person-immunity branch (~:1485) could read every frame. The
        // revive-only guard there already prevents that zombie from re-arming a
        // sequence, but dropping the locks here removes the zombie at its root:
        // if a real person is still present, the next genuine motion + YOLO
        // re-seeds a fresh track cleanly. Cheap (≤4 JNI calls), best-effort.
        //
        // LEFT LAST on purpose: the aiExecutor NCC-seed block checks its
        // generation before the trackerStartTrack JNI call, so an old lambda
        // can still land a seed just after the generation bump. This final drop
        // closes that TOCTOU, while the lifecycle lock prevents a successor
        // event from installing its own track until teardown is complete.
        dropAllTrackerLocks();
        logger.info("Recording stopped, motion detection continues");
    }

    /**
     * Best-effort drop of every active native texture-tracker lock across all
     * quadrants. Used on recording stop so a track that outlived its recording
     * (NCC age-out / heartbeat teardown only run while recording) can't linger
     * as a zombie that keeps the standing-person-immunity branch alive after the
     * subject has gone. Safe to call when no tracks are active (no-op per
     * quadrant); any native failure is swallowed so it can never block a stop.
     */
    private void dropAllTrackerLocks() {
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            try {
                if (NativeMotion.trackerHasActiveTrack(q)) {
                    NativeMotion.trackerDropTrack(q);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Write hero JPEG + per-actor thumbnails + JSON timeline sidecar
     * alongside the given .mp4 segment. Used by both stopRecording (final
     * segment) and the rotation listener (intermediate segments). Resets
     * {@link #thumbnailBuffer} and {@link #timelineCollector} after writing
     * so the next segment starts with empty metadata buffers.
     *
     * Idempotent and exception-safe — failures are logged but never
     * thrown to the caller, since rotation can't be aborted by a hero
     * write failure.
     */
    /**
     * Schedule the segment metadata flush (thumbnails + JSON + SRT) for
     * the given closed segment. Snapshots all engine state needed for the
     * write at dispatch time so the actual I/O can happen on
     * {@link #segmentMetadataExecutor} without racing the next segment's
     * mutations.
     *
     * <p>The JSON+SRT sidecar write is dispatched synchronously here (it
     * already routes onto its own writeExecutor inside the timeline
     * collector — cheap on this thread). The thumbnail JPEG compress
     * (the actually-expensive part) is what runs async.
     *
     * <p>Called from the segment-rotation listener and from
     * {@link #stopRecording}'s final-segment flush. The close path drains
     * via {@link #drainSegmentMetadata} before returning.
     */
    /**
     * @param syncHero  true when the caller is the publish path
     *                  ({@link #flushSegmentMetadata} from {@link #stopRecording})
     *                  and the hero JPEG MUST be on disk before this method
     *                  returns. false (rotation-listener path) lets the hero
     *                  go to {@link #segmentMetadataExecutor} so the
     *                  GpuSegmentFinalizer thread returns immediately.
     *                  Explicit parameter — previously a volatile flag, but
     *                  that introduced a cross-thread race where the rotation
     *                  listener could consume the flag intended for the
     *                  publish path, sending the publish-segment's hero to
     *                  the executor and re-introducing the mosaic-fallback
     *                  symptom under multi-segment events.
     */
    private void scheduleSegmentMetadataFlush(File segmentMp4,
                                              java.util.List<Actor> actorsAtRotation,
                                              long segmentStartMs,
                                              boolean syncHero) {
        // Drain on caller thread, then delegate. Used ONLY by the publish
        // path (stopRecording → flushSegmentMetadata). By the time the
        // publish path runs, recorder.stopEventRecording has already
        // joined the encoder drainer, so no further frames will trigger
        // observe() — the buffer is quiescent. Stop-during-rotation: if
        // a rotation listener fired right before stopRecording, it has
        // ALREADY drained on its own thread (synchronized) — by the time
        // we get here, slots map holds whatever observe() landed during
        // the brief window between listener-release and recorder-stop.
        // That's the FINAL segment's authentic slot set; not a race loser.
        java.util.List<ThumbnailBuffer.Slot> snap;
        ThumbnailBuffer buf = thumbnailBuffer;
        if (buf != null) {
            snap = buf.drainSnapshotForAsync();
        } else {
            snap = java.util.Collections.emptyList();
        }
        scheduleSegmentMetadataFlushWithSnapshot(segmentMp4, actorsAtRotation,
                segmentStartMs, snap, syncHero);
    }

    /**
     * Variant that takes a pre-drained slot snapshot. Used by the
     * rotation-listener path so the drain happens AS THE FIRST ACT on the
     * finalizer thread (minimizing the window during which observe() for
     * segment N+1 can pollute the closed segment's snapshot).
     */
    private void scheduleSegmentMetadataFlushWithSnapshot(
            File segmentMp4,
            java.util.List<Actor> actorsAtRotation,
            long segmentStartMs,
            java.util.List<ThumbnailBuffer.Slot> snap,
            boolean syncHero) {
        // Legacy shape without a timeline generation — no stale-stop check
        // (audit R11-4).
        scheduleSegmentMetadataFlushWithSnapshot(segmentMp4, actorsAtRotation,
                segmentStartMs, snap, syncHero, /* timelineGen = */ -1);
    }

    /**
     * Generation-aware variant (audit R11-4 / ExtD-5): {@code timelineGenIn}
     * is the collection generation captured when THIS segment's timeline
     * collection started; the inline {@code stopAndWrite} no-ops if a newer
     * collection has since begun (stale stop racing a successor).
     */
    private void scheduleSegmentMetadataFlushWithSnapshot(
            File segmentMp4,
            java.util.List<Actor> actorsAtRotation,
            long segmentStartMs,
            java.util.List<ThumbnailBuffer.Slot> snap,
            boolean syncHero,
            int timelineGenIn) {
        if (segmentMp4 == null) return;

        // EVENT-PEAK ACTOR RETENTION: actorsAtRotation is the live (TTL-pruned)
        // snapshot. Union in any retained event-peak actors (see eventPeakActors
        // field) that are NOT already present, so an actor that was significant
        // during the event but departed before it ended (a person who came
        // very-close then left) still appears in the JSON actors[]/stats/SRT and
        // isn't replaced by a lingering far car. De-duped by actorId; the live
        // snapshot's copy wins (it is the freshest for an actor still present).
        if (!eventPeakActors.isEmpty()) {
            java.util.Map<Long, Actor> merged = new java.util.LinkedHashMap<>();
            for (Actor a : eventPeakActors.values()) merged.put(a.actorId, a);
            if (actorsAtRotation != null) {
                for (Actor a : actorsAtRotation) merged.put(a.actorId, a); // live wins
            }
            // Collapse a depart-and-re-enter PERSON double-count (old retained id +
            // new live id for one physical person) so JSON actors[]/personCount/SRT
            // don't over-report — same coalesce the caption path uses.
            actorsAtRotation = coalesceReenteredPersons(new java.util.ArrayList<>(merged.values()));
        }

        // STALE-SLOT GUARD (mirror of the hero window-gate in pickHero): the
        // ThumbnailBuffer is cleared only at recording STOP, but observe() runs
        // continuously during monitoring, so a slot captured minutes earlier in
        // a quiet gap (e.g. an animal that crossed the lot) survives into this,
        // unrelated event's snapshot. Drop any slot whose peak frame predates
        // this segment's recorded window so we don't emit a per-actor thumb
        // (thumb_<base>_a<id>.jpg) for an actor that is NOT in the clip — the
        // same phantom the hero gate rejects. segmentStartMs<=0 (gate disabled)
        // keeps every slot. The pre-record ring is inside the window, so a
        // legitimate pre-roll capture is retained. Final + new list so the
        // downstream executor lambdas can capture it (effectively-final).
        final java.util.List<ThumbnailBuffer.Slot> windowedSnap;
        if (snap != null && !snap.isEmpty() && segmentStartMs > 0) {
            java.util.List<ThumbnailBuffer.Slot> inWindow =
                    new java.util.ArrayList<>(snap.size());
            for (ThumbnailBuffer.Slot s : snap) {
                if (s.peakWallMs <= 0 || s.peakWallMs >= segmentStartMs) {
                    inWindow.add(s);
                }
            }
            if (inWindow.size() != snap.size()) {
                logger.info("Dropped " + (snap.size() - inWindow.size())
                        + " stale pre-window thumbnail slot(s) for "
                        + segmentMp4.getName() + " (kept " + inWindow.size() + ")");
            }
            windowedSnap = inWindow;
        } else {
            windowedSnap = snap;
        }

        // Renormalize peak times against this segment's window — same logic
        // as the inline path used to do.
        final java.util.List<Actor> segmentActors;
        if (actorsAtRotation == null || actorsAtRotation.isEmpty() || segmentStartMs <= 0) {
            segmentActors = actorsAtRotation;
        } else {
            java.util.List<Actor> renormalized = new java.util.ArrayList<>(actorsAtRotation.size());
            for (Actor a : actorsAtRotation) {
                // Multi-segment guard: drop a peak-retained actor that was entirely
                // gone before THIS segment's window began — it belongs only to an
                // earlier segment and would otherwise be over-counted in every
                // later segment's actors[]/counts/SRT. lastSeenWallMs is wall-clock;
                // an actor still present at rotation (the freshest live copy that
                // wins the union) has lastSeenWallMs >= segmentStartMs and is kept,
                // as is the single-segment departed-close-person the union exists
                // for (its lifespan lies within the only segment). No-op for
                // single-segment events.
                if (a.lastSeenWallMs > 0 && a.lastSeenWallMs < segmentStartMs) {
                    continue;
                }
                long renormalizedRelMs;
                if (a.peakSeverityWallMs > 0 && a.peakSeverityWallMs >= segmentStartMs) {
                    renormalizedRelMs = a.peakSeverityWallMs - segmentStartMs;
                } else {
                    renormalizedRelMs = -1L;
                }
                if (renormalizedRelMs == a.peakSeverityRelMs) {
                    renormalized.add(a);
                } else {
                    renormalized.add(new Actor(
                            a.actorId, a.classGroup,
                            a.firstSeenWallMs, a.lastSeenWallMs,
                            a.firstSeenRelMs, a.lastSeenRelMs,
                            a.cameraMask,
                            a.peakProximity, a.lastProximity,
                            a.trend, a.isStatic, a.isStaticForTimeline,
                            a.everMoved, a.everMovedTested, a.confirmed,
                            a.peakSeverity, a.peakSeverityWallMs, renormalizedRelMs,
                            a.peakConfidence,
                            a.peakBboxX, a.peakBboxY, a.peakBboxW, a.peakBboxH,
                            a.peakBboxQuadW, a.peakBboxQuadH, a.peakCamera,
                            a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH,
                            a.lastCamera));
                }
            }
            segmentActors = renormalized;
        }

        // Compute the deterministic hero filename now (we don't yet know
        // whether ThumbnailBuffer will produce one, but the JSON sidecar
        // can record the filename it WILL have if produced).
        String base = segmentMp4.getName();
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        final String expectedHeroName = base + ".jpg";

        // Build the actorId → relMs map up front (cheap; pure read).
        final java.util.Map<Long, Long> relMap = new java.util.HashMap<>();
        if (segmentActors != null) {
            for (Actor a : segmentActors) {
                if (a.peakSeverityRelMs >= 0) {
                    relMap.put(a.actorId, a.peakSeverityRelMs);
                }
            }
        }

        // SOTA split: hero is load-bearing (publish notification reads it
        // directly), per-actor JPEGs are decorative (only the events page
        // consumes them). Doing both in the same async task forced the
        // notification path to block on drainSegmentMetadata(2s) waiting for
        // ALL JPEGs to finish — under multi-actor events the drain timed
        // out, the fallback (mp4 keyframe = 2×2 mosaic) fired, the
        // notification went out with the wrong image, and only later did
        // the proper YOLO hero overwrite the file. Result: users saw the
        // mosaic in PWA / Telegram pushes despite a valid YOLO hero
        // existing on disk seconds afterwards.
        //
        // The snapshot was already drained by the caller (this is the
        // {@code WithSnapshot} variant). Ordering:
        //   1. If syncHero=true (publish path), write the hero inline.
        //      Otherwise (rotation listener) schedule it on the executor
        //      so the finalizer thread returns immediately.
        //   2. Per-actor JPEGs always go to the executor.
        ThumbnailBuffer bufferAtDispatch = thumbnailBuffer;
        if (bufferAtDispatch != null && windowedSnap != null) {
            // Step 2: hero. Whether sync or async depends on the explicit
            // syncHero parameter. The publish path passes true; the
            // rotation listener passes false. Per-call argument means
            // concurrent rotation+stop callers can't steal each other's
            // signal — the previous volatile flag had exactly that race.
            if (syncHero) {
                File heroFile;
                try {
                    // Window-gate the hero so it can't depict a peak frame evicted
                    // from the bounded pre-record ring (segmentStartMs is this
                    // segment's window start; open upper bound = still finalizing).
                    heroFile = bufferAtDispatch.writeHeroFromSnapshot(windowedSnap, segmentMp4, segmentStartMs, 0L);
                } catch (Throwable t) {
                    logger.warn("Hero thumbnail sync write failed for "
                            + segmentMp4.getName() + ": " + t.getMessage());
                    heroFile = null;
                }
                if (heroFile != null) {
                    logger.info("Hero thumbnail (" + segmentMp4.getName() + "): "
                            + heroFile.getName());
                    // Latch WHICH segment got a real ThumbnailBuffer hero, so the
                    // push body can advertise "close-up view" only for a genuine
                    // foveated crop. Keyed by the mp4 name because file existence
                    // alone cannot distinguish a hero written just now from an
                    // orphan .jpg left at the same path by an earlier run.
                    lastSyncHeroMp4Name.set(segmentMp4.getName());
                    // Idle cleanup gate: hero JPEG bytes land after the mp4's
                    // save hook — bump so a parked idle tick sees them.
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty();
                    } catch (Throwable ignored) {}
                }
            } else {
                // Async hero path (rotation listener — no publish blocking
                // on this segment). Schedule on the executor so we return
                // off the GpuSegmentFinalizer-N thread fast. ALWAYS scheduled
                // (even when windowedSnap is empty) so the MP4-keyframe fallback
                // below can run: the sidecar records expectedHeroName for THIS
                // segment unconditionally, so if no YOLO-derived hero is written
                // the rotated segment's card would point at a non-existent
                // <base>.jpg → /thumb 404 "no preview". The final-segment path
                // gets this fallback via writeFallbackHeroWithTimeout; the
                // rotation path had no equivalent until now, so a non-final
                // segment whose only slots were out-of-window (stale) produced a
                // dangling hero reference. (Self-heals on restart via
                // sweepOrphanHeroThumbnails, but the live card was broken.)
                inFlightSegmentMetadata.incrementAndGet();
                final ThumbnailBuffer bufFinal = bufferAtDispatch;
                final long heroWindowStartMs = segmentStartMs;
                final java.util.List<ThumbnailBuffer.Slot> heroSnap = windowedSnap;
                final String heroBase = base;
                segmentMetadataExecutor.execute(() -> {
                    try {
                        File heroFile = heroSnap.isEmpty() ? null
                                : bufFinal.writeHeroFromSnapshot(heroSnap, segmentMp4, heroWindowStartMs, 0L);
                        if (heroFile == null) {
                            // No YOLO hero (empty/all-stale snapshot or in-window
                            // pick failed) — extract a real keyframe from this
                            // segment's MP4 so expectedHeroName resolves to a file.
                            File fallback = new File(segmentMp4.getParentFile(), heroBase + ".jpg");
                            if (!fallback.exists()) {
                                // Timeout-isolated: writeFallbackHeroFromMp4 drives
                                // MediaMetadataRetriever, which can hang forever on a
                                // truncated-moov / corrupt-sample MP4 (SD unmount or
                                // power transition mid-write). This lambda runs on the
                                // SINGLE-THREADED segmentMetadataExecutor, so a bare
                                // blocking call would wedge that one worker for the
                                // process lifetime — every later segment's hero +
                                // per-actor JPEG never runs, and inFlightSegmentMetadata
                                // stays elevated so every drain burns its 2s timeout.
                                // Run on a sacrificial daemon thread with a 5s budget,
                                // exactly as the final-segment path does (:6368).
                                writeFallbackHeroWithTimeout(segmentMp4, fallback, 5_000L);
                            }
                            if (fallback.exists()) {
                                logger.info("Hero thumbnail (fallback keyframe) ("
                                        + segmentMp4.getName() + "): " + fallback.getName());
                            }
                        } else {
                            logger.info("Hero thumbnail (" + segmentMp4.getName() + "): "
                                    + heroFile.getName());
                        }
                    } catch (Throwable t) {
                        logger.warn("Hero thumbnail async write failed for "
                                + segmentMp4.getName() + ": " + t.getMessage());
                    } finally {
                        // Idle cleanup gate: async hero/fallback JPEG bytes
                        // land after the save hook — one bump per batch.
                        try {
                            com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty();
                        } catch (Throwable ignored) {}
                        inFlightSegmentMetadata.decrementAndGet();
                        synchronized (segmentMetadataDrainLock) {
                            segmentMetadataDrainLock.notifyAll();
                        }
                    }
                });
            }

            // Step 3: per-actor JPEGs always async.
            if (!windowedSnap.isEmpty()) {
                inFlightSegmentMetadata.incrementAndGet();
                final ThumbnailBuffer bufFinal = bufferAtDispatch;
                segmentMetadataExecutor.execute(() -> {
                    try {
                        File parent = segmentMp4.getParentFile();
                        if (parent != null) {
                            String tmpBase = segmentMp4.getName();
                            if (tmpBase.endsWith(".mp4")) {
                                tmpBase = tmpBase.substring(0, tmpBase.length() - 4);
                            }
                            for (ThumbnailBuffer.Slot s : windowedSnap) {
                                // Static scenery keeps its slot for HERO ranking (so a
                                // clip whose only subject is stationary still gets a
                                // real bbox hero instead of a bare keyframe) but must
                                // NOT emit a per-actor thumbnail: thumb_<base>_a<id>.jpg
                                // is served to the events UI, and before the
                                // exclusion→demotion change these actors were dropped
                                // from the pool entirely and produced no such file.
                                // Preserves the prior user-visible behaviour here.
                                if (s.isStaticNonThreat()) continue;
                                try {
                                    Long relBoxed = relMap.get(s.actorId);
                                    long rel = relBoxed != null ? relBoxed : -1L;
                                    String jpegName = "thumb_" + tmpBase + "_a" + s.actorId
                                            + (rel >= 0 ? ("_" + rel) : "") + ".jpg";
                                    File jpeg = new File(parent, jpegName);
                                    bufFinal.writePerActorJpeg(s, jpeg);
                                } catch (Exception e) {
                                    logger.warn("Per-actor thumb write failed: " + e.getMessage());
                                }
                            }
                        }
                    } finally {
                        // Idle cleanup gate: per-actor JPEG bytes land after
                        // the save hook — one bump per batch.
                        try {
                            com.overdrive.app.storage.StorageManager.getInstance().markStorageDirty();
                        } catch (Throwable ignored) {}
                        inFlightSegmentMetadata.decrementAndGet();
                        synchronized (segmentMetadataDrainLock) {
                            segmentMetadataDrainLock.notifyAll();
                        }
                    }
                });
            }
        }

        // The JSON+SRT sidecar's stopAndWrite ALREADY routes file I/O
        // through its own writeExecutor — cheap to call inline here.
        // Calling it on the dispatch thread (rotation listener or
        // stopRecording) means the closed segment's spans are consumed
        // synchronously. NOTE (audit R11-5): the rotation listener MUST call
        // this flush BEFORE its startCollectingNoPreRing restart — the
        // inline stopAndWrite here consumes whatever collection state is
        // live, so a restart-first ordering hands it the NEXT segment's
        // just-reset state (zero spans) and flips the new collection off.
        // The stopAndWrite below is additionally generation-checked so a
        // stale stop racing a successor's restart no-ops (audit R11-4).
        try {
            // Build geo snapshots from the recorder's captured startGeo
            // fields plus the peak-actor's wall-clock instant. The end
            // snapshot is captured inside stopAndWrite at executor-dispatch
            // time so it reflects the moment of finalize.
            //
            // syncHero==true means "publish path / final segment" — read
            // the active startGeo*. syncHero==false means "rotation
            // listener / closed segment" — read closedStartGeo* which
            // rotateSegmentLocked stashed before refreshing the active
            // fields for the new segment. Without this distinction every
            // rotated segment's geo.start would carry the NEXT segment's
            // GPS, misattributing location on multi-segment recordings.
            com.overdrive.app.geo.GeoSnapshot startGeo =
                    com.overdrive.app.geo.GeoSnapshot.empty();
            com.overdrive.app.geo.GeoSnapshot peakGeo  = null;
            try {
                HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                if (enc != null) {
                    if (syncHero && enc.hasStartGeo()) {
                        startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                enc.getStartGeoLat(), enc.getStartGeoLng(),
                                enc.getStartGeoAccuracy(), enc.getStartGeoAgeMs(),
                                enc.getStartGeoCapturedAtMs(), 0L);
                    } else if (!syncHero && enc.hasClosedStartGeo()) {
                        startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                enc.getClosedStartGeoLat(), enc.getClosedStartGeoLng(),
                                enc.getClosedStartGeoAccuracy(), enc.getClosedStartGeoAgeMs(),
                                enc.getClosedStartGeoCapturedAtMs(), 0L);
                    }
                }
            } catch (Throwable t) {
                logger.warn("startGeo lookup failed: " + t.getMessage());
            }
            // Peak: the threat actor is the highest-severity non-static
            // entry in segmentActors. Use peakSeverityRelMs (already
            // renormalized to this segment's window earlier in this
            // method). Capturing GPS at THIS moment is the closest we'll
            // get to the threat-time location in real time, since we don't
            // log lat/lng per-frame in the engine. For "at-rest" parked
            // surveillance this collapses to startGeo, which is correct.
            if (segmentActors != null) {
                long peakRelMs = -1L;
                for (Actor a : segmentActors) {
                    // Timeline-static superset so a parked car doesn't anchor the
                    // threat-time GPS capture.
                    if (a == null || a.isStaticForTimeline) continue;
                    if (a.peakSeverityRelMs >= 0
                            && (peakRelMs < 0 || a.peakSeverityRelMs > peakRelMs)) {
                        peakRelMs = a.peakSeverityRelMs;
                    }
                }
                if (peakRelMs >= 0) {
                    peakGeo = com.overdrive.app.geo.GeoSnapshot.capture(peakRelMs);
                }
            }
            timelineCollector.stopAndWrite(segmentMp4, segmentActors,
                    expectedHeroName, startGeo, peakGeo, /* endGeo = capture inside */ null,
                    /* expectedGen (audit R11-4) = */ timelineGenIn);
        } catch (Exception e) {
            logger.warn("Timeline write failed for " + segmentMp4.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Wait until all queued segment-metadata writes have finished. Called
     * by the close path so a stopRecording → daemon-shutdown sequence
     * doesn't lose the last segment's hero thumbnail. Bounded by
     * timeoutMs; logs and returns false if we time out (the daemon will
     * still proceed with shutdown, the metadata may be corrupt — but the
     * partial state on disk is recoverable on next launch).
     */
    private boolean drainSegmentMetadata(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (segmentMetadataDrainLock) {
            while (inFlightSegmentMetadata.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    logger.warn("drainSegmentMetadata timed out with "
                            + inFlightSegmentMetadata.get() + " in flight");
                    return false;
                }
                try { segmentMetadataDrainLock.wait(remaining); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * FINAL-segment flush in {@link #stopRecording}. Snapshots
     * {@code lastActors} + the timeline collector's current start time, then
     * delegates to {@link #scheduleSegmentMetadataFlush} with
     * {@code syncHero=true} so the hero JPEG is written inline on this
     * thread — the publish path immediately after needs the file on disk
     * before sending notifications. The rotation-listener path passes
     * {@code syncHero=false}; rotated segments' heroes go to the
     * background executor so the GpuSegmentFinalizer-N thread returns fast.
     */
    private void flushSegmentMetadata(File segmentMp4) {
        if (segmentMp4 == null) return;
        // Publish path: hero MUST be on disk before stopRecording proceeds
        // to publishMotionFinal / sendFinalTelegramNotification, so write
        // it inline on this thread.
        //
        // (audit R8-2 / ExtC-3) NO LONGER CALLED by stopRecording: this
        // helper reads the LIVE lastActors / timelineCollector fields and
        // drains the LIVE thumbnailBuffer, which the stop path now clears
        // before its publish tail. stopRecording calls
        // scheduleSegmentMetadataFlushWithSnapshot directly with its
        // stop-time snapshots. Kept for any future caller that runs while
        // the fields are still live — do NOT reintroduce it on the stop tail.
        scheduleSegmentMetadataFlush(segmentMp4, lastActors,
                timelineCollector.getRecordingStartTimeMs(),
                /* syncHero = */ true);
    }
    
    /**
     * Enables surveillance (starts monitoring).
     */
    public void enable() {
        // ONE SERIALIZED TRANSITION (audit R13-2 / ExtE-3): the whole arm
        // sequence — resets, native init, epoch bump, active=true — runs
        // under the state-transition monitor, so a concurrent disable() or
        // second enable() can no longer observe (or mutate) the partially
        // initialized session between entry and the publication at the end.
        // See stateTransitionLock's field doc for the lock-order analysis.
        synchronized (stateTransitionLock) {
            enableLocked();
        }
    }

    private void enableLocked() {
        // RACE CONDITION FIX (defense in depth): Final guard at the engine level.
        // If ACC is ON, refuse to enable. This catches any edge case where the
        // higher-level guards in CameraDaemon/AccSentryDaemon were bypassed.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn(">>> Surveillance enable REJECTED at engine level — ACC is ON");
            return;
        }

        // IDEMPOTENCY GUARD (audit R4 ExtB-1): enable() while already armed
        // is a skip, not a re-arm. Most callers guard on !isActive() (the
        // API mode-switch bounce even documents doing so because "enable()
        // has no idempotency guard"), but POST /enable reached here
        // unguarded — re-running the session resets, baseline/tracker
        // clears, and native re-inits on the control thread WHILE the
        // engine thread is mid-tick (torn native POD state, a mid-event
        // actor/thumbnail wipe). Re-arm semantics are disable() → enable().
        if (active) {
            logger.warn(">>> Surveillance enable ignored — already armed (disable first to re-arm)");
            return;
        }

        // Sentry armed → publish surveillanceArmed=on and seed the event verdicts to
        // idle so the first real detection is a genuine transition. Both modes
        // (continuous / smart) flow through here after the ACC guard. try/catch inside
        // the helpers — never blocks arming.
        try {
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_ARMED, "on");
        } catch (Throwable ignored) {}
        resetSurveillanceAutomationState();

        // Latch ACC-OFF mode from unified config. forceReload because the
        // setter writes go through the app process; the daemon's UCM cache
        // would otherwise see a stale value on the first enable() after a
        // user toggle.
        try {
            org.json.JSONObject surv = com.overdrive.app.config.UnifiedConfigManager
                    .forceReload().optJSONObject("surveillance");
            String mode = (surv != null) ? surv.optString("accOffMode", "smart") : "smart";
            continuousMode = "continuous".equals(mode);
        } catch (Throwable t) {
            continuousMode = false;
        }

        if (continuousMode) {
            // Plain rolling 4-cam recording: skip motion + YOLO + baseline
            // entirely. NativeMotion is not required, so don't gate on it.
            logger.info("Enabling surveillance engine (CONTINUOUS — no motion, no AI)");
            armEpoch++;  // new arm session (R3b Ext-2/Ext-6)
            active = true;
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
            } catch (Exception e) {
                logger.warn("Could not set surveillance active state: " + e.getMessage());
            }
            startContinuousRecording();
            // BOUNDED RETRY on refusal (audit R3b Ext-6). The savedFormat
            // barrier can refuse the muxer on cold-boot encoder warmup —
            // exactly the boot-then-arm sequence of parking and walking
            // away. Pre-fix the engine stayed "armed" (active=true,
            // SURVEILLANCE_ARMED published) while ZERO video was written for
            // the whole parked session, and the refusal log's "will retry on
            // next event" was structurally impossible: continuous mode
            // bypasses all event processing AND the AI lane is never brought
            // up (PanoramicCameraGpu gates aiLaneNeeded on !continuous). No
            // watchdog exists — so retry here, bounded, epoch-guarded.
            if (!recording) {
                scheduleContinuousStartRetry(armEpoch, 1);
            }
            return;
        }

        // Check if native library is loaded
        if (!NativeMotion.isLibraryLoaded()) {
            logger.error(">>> Cannot enable surveillance: NativeMotion library not loaded! Error: " +
                NativeMotion.getLoadError());
            return;
        }

        logger.info("Enabling surveillance engine (pipelineV2=" + (pipelineV2 != null) +
            ", pipelineV2init=" + (pipelineV2 != null && pipelineV2.isInitialized()) + ")");

        // MOG2 stationary-revival channel setup — MUST complete BEFORE the
        // volatile `active` write below, for two independent reasons found
        // in audit:
        //  (1) NATIVE UAF: resetMOG2() reassigns the process-global cv::Ptr
        //      g_mog2 with no native lock. The engine (AiLaneWorker) thread
        //      starts calling computeMOG2Quadrants → g_mog2->apply() the
        //      instant `active` reads true, and disable() deliberately does
        //      not clear mog2ChannelEnabled/mog2Available — so on a re-arm,
        //      resetting AFTER active=true raced a concurrent apply() and
        //      could free the model mid-use (SIGSEGV while parked).
        //  (2) VISIBILITY: these are plain (non-volatile) fields written by
        //      the control thread. The volatile `active` write below is the
        //      publication barrier; writing them BEFORE it gives the engine
        //      thread a happens-before edge, so a reset can't be lost (a
        //      torn mog2CallCount reset would skip the learningRate=1.0
        //      background reseed AND pre-satisfy the warmup gate).
        // Kill switch read once per session; availability probed once.
        mog2CallCount = 0;
        lastMog2SampleMs = 0;
        mog2FracAtMs = 0;
        java.util.Arrays.fill(mog2QuadFrac, 0f);
        sequenceMotionQuadrant = -1;
        mog2RevivalStartMs = 0;
        sequenceMog2BaselineFrac = -1f;
        sequenceMog2AmbientValid = false;  // R3b Ext-9 ambient snapshot
        java.util.Arrays.fill(sequenceMog2AmbientQuad, 0f);
        sequenceMog2AmbientAtMs = 0;  // R5 horizon stamp
        java.util.Arrays.fill(mog2QuadFracPrev, 0f);  // R5 ambient shadow
        mog2FracPrevAtMs = 0;
        // Correlated-lighting guard state (I2: session reset).
        java.util.Arrays.fill(suppressionOnsetMs, 0L);
        java.util.Arrays.fill(brightnessPrevTick, false);
        firstMotionElapsedMs = 0;
        sequenceLightingCorrelated = false;  // R3 fresh-eyes #2 latch
        try {
            org.json.JSONObject sv = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            mog2ChannelEnabled = sv.optBoolean("stationaryRevivalEnabled", true);
        } catch (Throwable t) {
            mog2ChannelEnabled = false;  // adding-only channel: fail closed (I9)
        }
        try {
            mog2Available = mog2ChannelEnabled && NativeMotion.isMog2Available();
            if (mog2Available) NativeMotion.resetMOG2();
        } catch (Throwable t) {
            mog2Available = false;  // native probe failed → channel off (I9)
        }

        // NOTE (audit R3b Ext-1): `active = true` — the volatile publication
        // that lets AiLaneGl/AiLaneWorker start submitting frames — moved to
        // the END of this method. It used to be written HERE, which let the
        // engine thread race everything below: the plain-field session resets
        // (lost updates / stale first-tick reads), NativeMotion.initPipelineV2
        // and initTracker (the same unsynchronized-native-mutation hazard
        // disable()'s own ordering comment documents for teardown), the
        // baseline reset, and the ROI application (first ticks evaluated
        // against the previous session's mask). Writing it last makes the one
        // volatile store the publication barrier for the WHOLE arm sequence —
        // the same rationale the MOG2 block above already documents.
        frameCount = 0;
        motionDetections = 0;
        firstMotionTime = 0;  // Reset sustained motion timer
        deterrentFiredTime = 0;  // Reset deterrent suppression
        lastAiConfirmationElapsedMs = 0;  // Reset AI confirmation gate
        lastPersonConfirmationTimeMs = 0;  // Reset person-specific confirmation gate
        lastPersonConfirmationElapsedMs = 0;
        // Shadow-only multi-batch confirmation counters (I2: every latch
        // resets in enable()). Log-only state — hygiene, not correctness.
        aiConfirmShadow.reset();
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastRawDetectionElapsedMs.set(q, 0L);
        }
        peakThreatDuringSequence = 0;
        peakNearDuringSequence = false;  // Reset close-range fast-path latch
        peakCloseZoneDuringSequence = false;  // Reset close-zone (NEAR|MID) latch
        peakCloseZoneQuadrant = -1;  // Reset close-zone latch quadrant (I3 scoping)
        // (MOG2 stationary-revival session state is reset ABOVE the volatile
        // active=true write — see the audit-mandated ordering comment there.)
        cachedHighIsTrusted = false;  // Reset flag/shadow HIGH-trust latch
        cachedIncoherentLoiter = false;  // Reset confirmed-incoherent-loiter latch
        // Reset the motion-salience channel: sequence latch + the per-quadrant
        // consecutive-tick runs and their luma anchors. A run surviving an
        // arm/disarm cycle would let a pre-disable gust count toward a post-enable
        // trigger (invariant I2 — every latch resets in enable()).
        salienceConfirmedDuringSequence = false;
        salienceQuadrant = -1;
        salienceConfirmedAtMs = 0;
        // Post-park vigilance: fresh arm session → no latch, no spent budget (I2).
        // The ANCHORS deliberately survive (they live in DetectionBaseline and are
        // TTL'd by addedAtMs), but baseline.reset() on disable clears those too, and
        // the sentry-start seed never creates fromLiveEvent entries — so a re-arm
        // can never inherit a vigilance zone it didn't watch being created.
        vigilanceQuadrant = -1;
        vigilanceSeenAtMs = 0;
        vigilanceAssistStamps.clear();
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            salienceRunTicks[q] = 0;
            salienceLumaAnchor[q] = 0f;
        }
        
        // Reset post-suppression baseline refresh tracking
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            framesSinceSuppressionEnded[q] = 0;
            suppressionWasActive[q] = false;
            baselineRefreshQueued[q] = false;
            baselineRefreshDue[q] = false;
        }
        
        // Reset SOTA tracking variables
        lastTemporalBlocksCount = 0;
        lastMotionMinY = 0;
        lastMotionMaxY = 0;
        lastEstimatedDistance = 0;
        
        // SOTA: Notify StorageManager that surveillance is active (for periodic cleanup)
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
        } catch (Exception e) {
            logger.warn("Could not set surveillance active state: " + e.getMessage());
        }
        
        // V2: Re-initialize pipeline for clean start
        if (pipelineV2 != null) {
            try {
                NativeMotion.initPipelineV2();
                logger.info("V2 pipeline reset for new surveillance session");
            } catch (Exception e) {
                logger.warn("V2 pipeline reset failed: " + e.getMessage());
            }
        }
        
        // Reset cross-quadrant tracker for clean session
        crossQuadrantTracker.reset();

        // Reset Actor layer too — fresh ID space for each session
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();
        // MUST clear the thumbnail buffer here as well. actorTracker.reset()
        // restarts nextActorId at 1, so a NEW session's actor 1 would otherwise
        // inherit the PREVIOUS session's actor-1 slot and — worse — its
        // zero-motion-coverage run length. A stale run of >= STATIC_CONFIRM_OBS
        // would declare the fresh actor static on its very first observation,
        // which is the unsafe direction (a real mover demoted below scenery).
        // disable() only clears via stopRecording(), which runs only when an event
        // was in progress, so an arm/disarm cycle with no event left it populated.
        if (thumbnailBuffer != null) thumbnailBuffer.clear();
        
        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;
        // Clear ALL per-quadrant proximity-related state so a new session
        // doesn't inherit a 30-second-old prevLowestBlockY (which combined
        // with a fresh nowMs would compute a bogus APPROACHING/RECEDING
        // trend on the very first tick). See audit re-pass session-residual.
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloPublication.set(q, null);
            cachedTrend[q] = DistanceEstimator.Trend.UNKNOWN;
            prevLowestBlockY[q] = -1;
            prevLowestBlockYAtMs[q] = 0;
        }
        lastEventQuadrant = -1;

        // Foveated mailbox cleanup. Without this, a request flag set by the
        // FINAL frames of the previous surveillance session (a YOLO confirmer
        // that asked for a foveated crop ~ms before disable) survives the
        // ACC-OFF→ON→OFF window and fires on the next session's first tick —
        // dispatching a crop with the prior session's centroid coordinates
        // against this session's first frame. The per-poll 500ms staleness
        // check bounds the impact (consumers reject the result), but the
        // gl-thread service still runs the wasted readback. Cheap to clear.
        synchronized (foveatedRequestLock) {
            for (int q = 0; q < FOVEATED_NUM_QUADRANTS; q++) {
                foveatedRequested[q] = false;
                foveatedReqCentroidX[q] = 0f;
                foveatedReqCentroidY[q] = 0f;
                foveatedSlots[q].set(null);
            }
        }
        foveatedRoundRobin = 0;
        lastFoveatedServiceNs = 0L;

        // Apply per-quadrant ROI from persisted config (polygon AND block-tap masks).
        if (config != null) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                applyEffectiveRoi(q);
            }
        }
        
        // Initialize native texture tracker (YOLO + NCC hybrid VOT)
        try {
            NativeMotion.initTracker();
            logger.info("Texture tracker initialized (YOLO + NCC hybrid)");
        } catch (Exception e) {
            logger.warn("Texture tracker init failed: " + e.getMessage());
        }
        // Fresh session — no carried heartbeat misses (audit R14-1).
        java.util.Arrays.fill(heartbeatMissCount, 0);

        // Publication LAST (audit R3b Ext-1): every reset and native init
        // above happens-before the frame lane's first isActive()==true read.
        armEpoch++;  // new arm session (see armEpoch field doc — R3b Ext-2)
        active = true;

        logger.info("Surveillance enabled (V2 per-quadrant pipeline)");
    }
    
    /**
     * Disables surveillance (stops monitoring).
     */
    public void disable() {
        // ONE SERIALIZED TRANSITION (audit R13-2 / ExtE-3) — see enable().
        // The R11-3 epoch guards inside remain as belt-and-braces; under the
        // monitor they can only fire for a transition that completed
        // entirely before we entered (still the correct bow-out).
        synchronized (stateTransitionLock) {
            disableLocked();
        }
    }

    private void disableLocked() {
        // Sentry disarmed → publish surveillanceArmed=off (before the continuous-mode
        // early return so both modes emit it). try/catch — never blocks teardown.
        try {
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.SURVEILLANCE_ARMED, "off");
        } catch (Throwable ignored) {}
        // DISABLE-SESSION EPOCH (audit R11-3 / ExtD-4). active=false is
        // published at the head of each branch, long before the teardown tail
        // completes, and no lock spans enable()/disable(). A camera-reacquire
        // enable() (GSP onPostReacquire sees currentMode==SURVEILLANCE — IDLE
        // is set only after this method returns — and !isActive()) can
        // therefore arm a NEW session while THIS disable's tail is still
        // clearing state: worst cases were the new session running with its
        // MOG2 model released (revival channel silently dead for the whole
        // session) and a disable stopContinuousRecording() killing the new
        // session's just-committed recording. Capture the arm epoch here;
        // every destructive step below re-checks it and bows out once a new
        // enable() has advanced it (the new enable re-initializes everything
        // this tail would have cleared). A mid-arm enable that has not yet
        // bumped the epoch still loses a µs-scale race — accepted (LOW), the
        // common ACC-ON variant is separately blocked by enable()'s ACC guard.
        final int disableEpoch = armEpoch;
        if (continuousMode) {
            // Continuous-mode disable: no motion / YOLO state to drain and
            // no event-end baseline update to run. Just stop the recorder
            // and flip flags. continuousMode itself stays true so a stale
            // late-arriving processFrame still no-ops via active=false.
            //
            // ORDER + LOCK (audit R4 ExtB-4): active=false BEFORE the locked
            // stop decision, and the decision under recordingLifecycleLock —
            // pairing with startContinuousRecording's locked commit. Either
            // we see a committed recording here and stop it, or the
            // in-flight start's commit sees active==false and unwinds
            // itself. The old unlocked check-after-nothing let a retry-tick
            // start (blocked seconds in storage resolves) commit AFTER this
            // check and roll for the whole drive.
            // (audit R11-3 / ExtD-4) A fully-completed racing enable() owns
            // the session — do not disarm it.
            if (armEpoch != disableEpoch) {
                logger.info("disable(continuous) superseded by a new enable() — bowing out");
                return;
            }
            active = false;
            synchronized (recordingLifecycleLock) {
                // Epoch re-check under the lock (audit R11-3 / ExtD-4): a new
                // continuous enable() committing between our active=false and
                // this block owns the rolling recording — stopping it here
                // would kill the NEW session's recording, not ours.
                if (recording && armEpoch == disableEpoch) {
                    stopContinuousRecording();
                }
            }
            if (armEpoch != disableEpoch) {
                logger.info("disable(continuous) tail skipped — new enable() owns the session");
                return;
            }
            inActiveMode = false;
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
            } catch (Exception e) {
                logger.warn("Could not set surveillance inactive state: " + e.getMessage());
            }
            // Clear the latch so the next enable() reads the latest config
            // (user may have toggled back to smart while ACC was on).
            continuousMode = false;
            logger.info("Surveillance disabled (continuous)");
            return;
        }
        // (audit R11-3 / ExtD-4) A fully-completed racing enable() owns the
        // session — do not disarm it.
        if (armEpoch != disableEpoch) {
            logger.info("disable(smart) superseded by a new enable() — bowing out");
            return;
        }
        // Clear `active` FIRST so processFrame() early-returns (it gates on
        // !active at the top) — this stops the AiLaneWorker thread from
        // entering processFrame for NEW frames. (audit R11-6 / ExtD-6: this
        // gate alone does NOT stop a frame that was already admitted — the
        // worker checks isActive() only before entry — hence the
        // motion-lane drain below.)
        active = false;
        // Drain any in-flight aiExecutor YOLO lambda BEFORE stopRecording() so it
        // finishes its native tracker writes (trackerStartTrack/RefreshTemplate)
        // and clears isAiRunning before stopRecording()'s dropAllTrackerLocks()
        // mutates the unsynchronized global g_trackerState on this control thread.
        // active=false above makes the lambda's writes no-ops once it re-checks,
        // but the drain closes the concurrent-write window deterministically.
        // Bounded to 50ms — disable() is on the daemon thread; don't block the
        // caller for a full inference. (Mirrored drain below is now removed.)
        //
        // (audit R11-6 / ExtD-6) ALSO drain the MOTION lane: an admitted
        // processFrame runs processFrameV2 (native motion + MOG2 sampler +
        // trackerUpdate, ~20-50ms) regardless of the active flip above. Its
        // overlap with this teardown tail was individually bounded
        // (g_mog2_mutex, torn-POD tracker class, sampler catch-all), but a
        // straggler MOG2 apply() after releaseMOG2() below re-created the
        // ~18.7MB model for the whole disarm period. One 100ms budget covers
        // both lanes; a typical straggler finishes in well under half that.
        try {
            long drainDeadline = System.currentTimeMillis() + 100;
            while ((isAiRunning.get() || motionFramesInFlight.get() > 0)
                    && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(5);
            }
            if (motionFramesInFlight.get() > 0) {
                logger.warn("disable(): motion lane did not drain within budget — "
                        + "native side is mutex/latch-guarded, proceeding");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // STOP DECISION under the lifecycle lock (audit R3b Ext-3): pairs
        // with startRecording()'s locked commit. Either we see the committed
        // recording here and stop it, or the in-flight start sees
        // active==false at its locked commit and unwinds itself — no
        // interleaving leaves the recorder live with the engine dead.
        synchronized (recordingLifecycleLock) {
            // Epoch re-check under the lock (audit R11-3 / ExtD-4): if a new
            // enable() completed during the drain above, any live recording
            // belongs to the NEW session.
            if (recording && armEpoch == disableEpoch) {
                stopRecording();
            }
        }
        // (audit R11-3 / ExtD-4) Everything below clears state a racing
        // enable() has just re-initialized for ITS session — worst offender
        // releaseMOG2(), which left the new session's revival channel dead.
        // Bow out once superseded.
        if (armEpoch != disableEpoch) {
            logger.info("disable(smart) tail skipped — new enable() owns the session");
            return;
        }
        inActiveMode = false;

        // Tear down any in-progress screen deterrent. cancel() is non-blocking;
        // the render thread (in this same process) sees the flag, releases its
        // surface, and turns the backlight off in its finally block.
        try {
            ScreenDeterrent.getInstance().cancel();
        } catch (Throwable ignored) {}

        // P1 #15: cancel pending YOLO work and clear shared state BEFORE
        // resetting the baseline. Without this, an aiExecutor lambda already
        // mid-flight can still write lastActors / lastYoloPublication after
        // disable returns, polluting the next session's first frames.
        // Same set of arrays cleared as in enable() (audit re-pass
        // session-residual): YoloPublication, cachedTrend, prevLowestBlockY,
        // prevLowestBlockYAtMs.
        aiQuadrantQueueClear();
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloPublication.set(q, null);
            cachedTrend[q] = DistanceEstimator.Trend.UNKNOWN;
            prevLowestBlockY[q] = -1;
            prevLowestBlockYAtMs[q] = 0;
        }
        // Foveated mailbox cleanup — symmetric with enable(). A request flag
        // set in the last few frames before disable would otherwise persist
        // through ACC-ON and fire stale on the next session's first service
        // pass with the previous session's centroid coordinates.
        synchronized (foveatedRequestLock) {
            for (int q = 0; q < FOVEATED_NUM_QUADRANTS; q++) {
                foveatedRequested[q] = false;
                foveatedReqCentroidX[q] = 0f;
                foveatedReqCentroidY[q] = 0f;
                foveatedSlots[q].set(null);
            }
        }
        foveatedRoundRobin = 0;
        lastFoveatedServiceNs = 0L;
        // (AI drain moved earlier — before stopRecording() — so it serializes
        // against dropAllTrackerLocks(); see the drain above.)

        // SOTA: Notify StorageManager that surveillance is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
        } catch (Exception e) {
            logger.warn("Could not set surveillance inactive state: " + e.getMessage());
        }

        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;

        // MOG2 teardown (audit R3b Ext-14): release the ~18.7MB native
        // background model + the 0.92MB direct frame buffer — they stayed
        // resident across disarm (the driving majority of runtime) and
        // bought nothing, since enable() reseeds via resetMOG2() anyway.
        // Mutex-guarded natively against a straggler apply(); active=false
        // above stops new samples, and enable() reallocates mog2Frame.
        // Final epoch re-check (audit R11-3 / ExtD-4): releasing the model a
        // racing enable() just reseeded would silently kill the new
        // session's revival channel (native calls fail closed on a released
        // model until the next resetMOG2).
        if (armEpoch != disableEpoch) {
            logger.info("disable(smart) MOG2 release skipped — new enable() owns the model");
            return;
        }
        try {
            NativeMotion.releaseMOG2();
        } catch (Throwable ignored) {
            // Adding-only channel — a failed release just leaves the model
            // resident until the next session replaces it.
        }
        mog2Frame = null;

        logger.info("Surveillance disabled");
    }
    
    /**
     * Checks if surveillance is active.
     * 
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * @return true when surveillance is in CONTINUOUS (always-record) ACC-OFF
     * mode, where motion detection / YOLO / mosaic readback are NOT used —
     * recording is fed directly by the GL→encoder surface chain. The AI lane
     * has no consumer in this mode, so the pipeline skips bringing it up.
     */
    public boolean isContinuousMode() {
        return continuousMode;
    }

    /**
     * Repairs the engine↔recorder desync a camera yield leaves behind (audit
     * R7 ExtC-1). onPreYield stops the RECORDER directly (moov must be
     * finalized before the camera closes) but never tells this engine — so a
     * continuous session was left {@code active=true, recording=true} with a
     * dead recorder, no retry chain (it terminated at the first successful
     * start) and no tick loop to notice: silent whole-session video loss on
     * every AVM camera claim or HAL-error restart while parked.
     *
     * <p>Called from the camera pipeline's onPostReacquire. Runs under the
     * state-transition monitor (audit R13-2) so it cannot interleave with a
     * concurrent enable()/disable(); the flag repair + restart decision
     * additionally run under {@code recordingLifecycleLock} against the
     * recording-lifecycle paths; the restart itself is the standard
     * epoch-checked start + bounded retry chain.
     *
     * <p>SMART-MODE LEG (audit R13-4 / ExtE-1): pre-yield stops the recorder
     * in BOTH modes, but this method used to repair only continuous. A smart
     * session was left {@code recording=true} with a dead recorder until the
     * post-record clock expired or the hard ceiling fired (up to minutes) —
     * and since every trigger path gates on {@code if (!recording)}, real
     * events in that window were silently folded into the phantom recording
     * instead of starting clips. Now the desync is repaired immediately:
     * finalize the interrupted event via the normal stopRecording() snapshot
     * tail (it tolerates a dead recorder — the recorder stop no-ops — and
     * emits the truncated clip's final notification/metadata), returning the
     * engine to armed-idle so the next motion re-triggers a fresh clip.
     */
    public void resumeAfterCameraReacquire() {
        synchronized (stateTransitionLock) {  // audit R13-2 / ExtE-3
            resumeAfterCameraReacquireLocked();
        }
    }

    private void resumeAfterCameraReacquireLocked() {
        if (!active || recorder == null) return;
        if (!continuousMode) {
            // SMART-MODE DESYNC REPAIR (audit R13-4 / ExtE-1).
            synchronized (recordingLifecycleLock) {
                if (active && recording && !recorder.isRecording()) {
                    logger.warn("Camera yield desync (smart) — engine believed recording, "
                        + "recorder is stopped; finalizing the interrupted event");
                    // Full stop path: snapshot tail, discard/KEEP verdict,
                    // notification, state resets. Recorder stop inside is a
                    // no-op on the already-dead recorder. Leaves the engine
                    // armed-idle; the next motion event triggers normally.
                    stopRecording();
                }
            }
            return;
        }
        synchronized (recordingLifecycleLock) {
            if (!active) return;  // disable() won the race
            if (recording && !recorder.isRecording()) {
                // Desync: pre-yield stopped the recorder; drop our stale flag
                // so the restart below (and disable()'s stop decision) see
                // the truth.
                logger.warn("Camera yield desync detected — engine believed recording, "
                    + "recorder is stopped; repairing");
                recording = false;
            } else if (recording || recorder.isRecording()) {
                return;  // healthy (both live) or recorder busy elsewhere
            }
            // fall through: recording==false, recorder idle → (re)start
        }
        logger.info("Camera reacquired — restarting continuous recording");
        startContinuousRecording();
        if (!recording) {
            scheduleContinuousStartRetry(armEpoch, 1);
        }
    }

    /**
     * Checks if currently recording.
     *
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * @return true when surveillance has active motion in flight or is
     * recording — i.e. the camera should be at full fps, not the parked-idle
     * rate. Derived purely from the two existing single-writer fields so it is
     * automatically correct across ALL firstMotionTime reset sites (no separate
     * flag to keep in sync). Read cross-thread by RecordingModeManager to drive
     * the idle↔active fps ramp.
     */
    public boolean hasActiveMotion() {
        return firstMotionTime != 0 || recording;
    }

    /**
     * Milliseconds of SUSTAINED no-motion since the last active tick (issue #174).
     *
     * <p>Returns 0 while motion is active (or before the first frame has been
     * observed), and otherwise the wall-clock time since the engine last saw
     * {@link #hasActiveMotion()} true. RecordingModeManager reads this to decide
     * when to step the parked-idle AI cadence down into the quiet tier. The
     * baseline is maintained only while the idle throttle is enabled (it is
     * re-seeded on enable and pinned to now while active), so a stale value can
     * never trigger the tier after a disable/re-enable.
     *
     * <p>Read cross-thread by RMM; backed by a single volatile long written on
     * the motion thread. Uses the same {@code System.currentTimeMillis()} clock
     * as the motion-thread writer so the delta is consistent.
     */
    public long getQuietDurationMs() {
        long base = lastMotionActiveWallMs;
        if (base == 0 || hasActiveMotion()) return 0L;
        long dt = System.currentTimeMillis() - base;
        return dt > 0 ? dt : 0L;
    }

    /**
     * Notify RecordingModeManager that surveillance motion activity changed so it
     * can promptly ramp the camera fps between the parked-idle rate and full rate
     * (parked-idle throttle). Fetches RMM via the same static registry the engine
     * uses for the OEM pipeline. Fire-and-forget; fully guarded — a null RMM (not
     * yet wired) or any failure is a silent no-op, and reconcile is a no-op when
     * the throttle is off.
     */
    private void notifySurveillanceActivityChanged() {
        try {
            com.overdrive.app.recording.RecordingModeManager rmm =
                com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
            if (rmm != null) rmm.onSurveillanceActivityChanged();
        } catch (Throwable t) {
            logger.debug("Surveillance activity reconcile push failed: " + t.getMessage());
        }
    }

    /**
     * Checks if in active mode (heavy AI).
     * 
     * @return true if in active mode, false if idle
     */
    public boolean isInActiveMode() {
        return inActiveMode;
    }
    
    /**
     * Gets the current SAD threshold.
     * 
     * @return Threshold value (0.0-1.0)
     */
    public float getSadThreshold() {
        return config.getSensitivity();
    }
    
    /**
     * Gets the grid motion block sensitivity.
     * 
     * @return Sensitivity value (0.0-1.0, typically 0.04-0.10)
     */
    public float getBlockSensitivity() {
        return config.getSensitivity();
    }
    
    /**
     * Sets the grid motion block sensitivity.
     * Lower values detect more distant/subtle motion.
     * 
     * @param sensitivity Sensitivity value (0.01-0.20, default 0.04)
     */
    public void setBlockSensitivity(float sensitivity) {
        float clamped = Math.max(0.01f, Math.min(0.20f, sensitivity));
        config.setSensitivity(clamped);
        logger.info("Block sensitivity set to: " + clamped);
    }
    
    /**
     * Sets the unified motion sensitivity (0-100%).
     * 
     * This is the recommended API for controlling motion detection.
     * A single slider that intelligently adjusts:
     * - Density Threshold: How many pixels must change per block
     * - Alarm Threshold: How many blocks must trigger to start recording
     * 
     * Mapping:
     * - 0-30%:   LOW (large/close objects only)
     * - 31-60%:  MEDIUM (balanced, default)
     * - 61-80%:  HIGH (detects distant objects)
     * - 81-100%: VERY HIGH (any motion)
     * 
     * @param sensitivity 0-100 percentage
     */
    public void setUnifiedSensitivity(int sensitivity) {
        config.setUnifiedSensitivity(sensitivity);
        
        // Sync legacy fields for backward compatibility
        this.requiredActiveBlocks = config.getAlarmBlockThreshold();
        
        logger.info(String.format("Unified sensitivity set to: %d%% (alarm=%d blocks, density=%d pixels, shadow=%d)",
                sensitivity, config.getAlarmBlockThreshold(), config.getDensityThreshold(), config.getShadowThreshold()));
    }
    
    /**
     * Gets the unified motion sensitivity (0-100%).
     * 
     * @return Sensitivity percentage
     */
    public int getUnifiedSensitivity() {
        return config.getUnifiedSensitivity();
    }
    
    /**
     * Sets night mode (affects shadow threshold).
     * 
     * Night mode uses a higher shadow threshold (40 vs 25) to filter
     * out headlight reflections and other light artifacts.
     * 
     * @param enabled true for night mode
     */
    public void setNightMode(boolean enabled) {
        config.setNightMode(enabled);
        // Log the V2 pipeline's actual shadow threshold (not the legacy config's)
        int v2Shadow = pipelineV2Config != null ? pipelineV2Config.shadowThreshold : -1;
        int v2Filter = pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : -1;
        logger.info("Night mode set to: " + enabled + 
                " (V2 shadow threshold=" + v2Shadow + ", filter=" + v2Filter + ")");
    }
    
    /**
     * Gets night mode state.
     * 
     * @return true if night mode is enabled
     */
    public boolean isNightMode() {
        return config.isNightMode();
    }
    
    /**
     * Gets the required active blocks threshold.
     * 
     * @return Number of blocks required to trigger motion
     */
    public int getRequiredActiveBlocks() {
        return requiredActiveBlocks;
    }
    
    /**
     * Sets the required active blocks threshold.
     * Lower values are more sensitive to small/distant motion.
     * 
     * @param blocks Number of blocks (1-10, default 2)
     */
    public void setRequiredActiveBlocks(int blocks) {
        this.requiredActiveBlocks = Math.max(1, Math.min(10, blocks));
        // Sync with SOTA config
        config.setRequiredBlocks(this.requiredActiveBlocks);
        logger.info("Required active blocks set to: " + this.requiredActiveBlocks);
    }
    
    /**
     * Gets the flash immunity level.
     * 
     * @return Flash immunity level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
     */
    public int getFlashImmunity() {
        return flashImmunity;
    }
    
    /**
     * Gets the minimum object size for detection.
     * 
     * @return Minimum object size as fraction of frame (0.02 = 2% = ~15m, 0.20 = 20% = ~3m)
     */
    public float getMinObjectSize() {
        return minObjectSize;
    }
    
    /**
     * Sets the flash immunity level.
     * 
     * Uses edge-based detection to ignore light flashes (headlights, lightning, etc.)
     * while still detecting real object motion.
     * 
     * Levels:
     * - 0 = OFF: Legacy pixel differencing, sensitive to flashes
     * - 1 = LOW: Edge-based, some flash filtering
     * - 2 = MEDIUM: Edge-based + brightness normalization (default)
     * - 3 = HIGH: Edge-based + aggressive flash rejection
     * 
     * @param level Flash immunity level (0-3)
     */
    public void setFlashImmunity(int level) {
        this.flashImmunity = Math.max(0, Math.min(3, level));
        // Sync with SOTA config
        config.setFlashImmunity(this.flashImmunity);
        String[] levelNames = {"OFF", "LOW", "MEDIUM", "HIGH"};
        logger.info("Flash immunity set to: " + levelNames[this.flashImmunity] + " (" + this.flashImmunity + ")");
    }
    
    /**
     * Gets the total number of motion-grid blocks in ONE quadrant.
     *
     * @return 70 — a 320×240 quadrant gridded at 32 px (10 cols × 7 rows), the
     *         same geometry the native pipeline and the web ROI editor use.
     *         Previously reported 300 from a dead 640×480 constant, so the
     *         /api/surveillance config told the UI a block count that matched
     *         neither the mask it posts back (roiBlocks_Q* is length-70, and
     *         applyEffectiveRoi silently ignores any other length) nor the array
     *         the pipeline fills.
     */
    public int getTotalBlocks() {
        return MotionPipelineV2.TOTAL_BLOCKS;
    }
    
    /**
     * Gets the last active blocks count (for UI display).
     * 
     * @return Number of blocks that were active in the last frame
     */
    public int getLastActiveBlocksCount() {
        return lastActiveBlocksCount;
    }
    
    /**
     * Gets the baseline noise blocks count (deprecated - always returns 0).
     * 
     * @return Always 0 (baseline logic removed)
     */
    public int getBaselineNoiseBlocks() {
        return 0;  // Baseline logic removed for simplicity
    }
    
    /**
     * Sets the SAD threshold for motion detection.
     * 
     * @param threshold Threshold value (0.0-1.0, typically 0.05 for 5%)
     */
    public void setSadThreshold(float threshold) {
        config.setSensitivity(threshold);
        logger.info( "SAD threshold set to: " + threshold);
    }
    
    /**
     * Gets the pre-record duration in seconds.
     * 
     * @return Pre-record duration in seconds
     */
    public int getPreRecordSeconds() {
        return (int) (preRecordMs / 1000);
    }
    
    /**
     * Sets the pre-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 10 for 10 seconds before motion)
     */
    public void setPreRecordSeconds(int seconds) {
        this.preRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPreRecordSeconds(seconds);
        logger.info("Pre-record duration set to: " + seconds + " seconds");
        
        // Update the circular buffer size in the recorder's encoder
        if (recorder != null && recorder.getEncoder() != null) {
            recorder.getEncoder().setPreRecordDuration(seconds);
        }
    }
    
    /**
     * Gets the post-record duration in seconds.
     * 
     * @return Post-record duration in seconds
     */
    public int getPostRecordSeconds() {
        return (int) (postRecordMs / 1000);
    }
    
    /**
     * Sets the post-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 5 for 5 seconds after motion stops)
     */
    public void setPostRecordSeconds(int seconds) {
        int clampedSeconds = Math.max(1, Math.min(60, seconds));
        this.postRecordMs = clampedSeconds * 1000L;
        // Sync with SOTA config
        config.setPostRecordSeconds(clampedSeconds);
        if (clampedSeconds != seconds) {
            logger.warn("Post-record duration clamped from " + seconds
                    + " to " + clampedSeconds + " seconds");
        } else {
            logger.info("Post-record duration set to: " + clampedSeconds + " seconds");
        }
    }
    
    /**
     * Gets the frame count.
     * 
     * @return Total frames processed
     */
    public int getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the motion detection count.
     * 
     * @return Total motion events detected
     */
    public int getMotionDetections() {
        return motionDetections;
    }
    
    /**
     * Gets the latest cached mosaic frame (640×480 RGB) for snapshot API.
     * Returns null if no frame has been cached yet. Records the poll
     * timestamp so processFrame keeps refreshing while clients are active.
     */
    public byte[] getLatestMosaicFrame() {
        lastSnapshotPollMs = System.currentTimeMillis();
        return latestMosaicFrame;
    }

    /**
     * Latest JPEG-encoded mosaic snapshot (Option C side-output). Refreshed
     * every {@link #MOSAIC_JPEG_FRAME_MODULO} frames on the surveillance
     * worker thread WHILE clients are polling; idle-skipped otherwise.
     * Readers pay one volatile read + one volatile write (poll timestamp).
     * Null until the first encode lands or when surveillance hasn't started.
     */
    public byte[] getLatestMosaicJpeg() {
        lastSnapshotPollMs = System.currentTimeMillis();
        return latestMosaicJpeg;
    }

    /**
     * Encodes the 640×480 RGB mosaic into a JPEG byte[]. Always invoked from
     * {@link #mosaicJpegExecutor}'s single thread so the scratch buffers
     * ({@link #mosaicJpegPixelsScratch}, {@link #mosaicJpegBitmapScratch})
     * are accessed without a lock. Reusing them avoids ~5 MB/s of int[] +
     * Bitmap allocation that would otherwise hit the young-gen.
     *
     * Returns null on bad input or encode failure.
     */
    private byte[] encodeMosaicJpeg(byte[] mosaicRgb) {
        final int W = 640, H = 480;
        if (mosaicRgb == null || mosaicRgb.length < W * H * 3) return null;
        if (mosaicJpegPixelsScratch == null || mosaicJpegPixelsScratch.length < W * H) {
            mosaicJpegPixelsScratch = new int[W * H];
        }
        int[] pixels = mosaicJpegPixelsScratch;
        for (int y = 0; y < H; y++) {
            int rowBase = y * W * 3;
            int dstBase = y * W;
            for (int x = 0; x < W; x++) {
                int srcIdx = rowBase + x * 3;
                int r = mosaicRgb[srcIdx] & 0xFF;
                int g = mosaicRgb[srcIdx + 1] & 0xFF;
                int b = mosaicRgb[srcIdx + 2] & 0xFF;
                pixels[dstBase + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        // Reuse a mutable Bitmap and re-feed pixels via setPixels rather
        // than allocating a new immutable Bitmap each call.
        android.graphics.Bitmap bitmap = mosaicJpegBitmapScratch;
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() != W || bitmap.getHeight() != H) {
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); } catch (Exception ignored) {}
            }
            bitmap = android.graphics.Bitmap.createBitmap(
                    W, H, android.graphics.Bitmap.Config.ARGB_8888);
            mosaicJpegBitmapScratch = bitmap;
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
        // Bitmap NOT recycled — it's reused for the next encode.
    }

    /**
     * Releases all resources.
     */
    /**
     * Updates the V2 pipeline configuration.
     * Call this when user changes settings via IPC.
     */
    public void updateV2Config(MotionPipelineV2.Config newConfig) {
        if (pipelineV2 != null) {
            if (newConfig != null) {
                pipelineV2Config = newConfig;
            }
            if (pipelineV2Config != null) {
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 pipeline config updated");
            }
        }
    }
    
    /**
     * Apply a V2 environment preset (outdoor/garage/street).
     */
    public void applyV2EnvironmentPreset(String preset) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applyEnvironmentPreset(preset);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 environment preset applied: " + preset);
        }
    }
    
    /**
     * Apply V2 sensitivity level (1-5).
     */
    public void applyV2Sensitivity(int level) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applySensitivity(level);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 sensitivity set to " + level);
        }
    }
    
    /**
     * Set V2 loitering time in seconds.
     */
    public void setV2LoiteringTime(int seconds) {
        if (pipelineV2Config != null) {
            pipelineV2Config.loiteringFrames = seconds * 10;  // 10 FPS
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
        }
        // Also update Java-side sustained motion threshold.
        // THREAT_MEDIUM must persist for this duration before triggering.
        this.loiteringTimeMs = seconds * 1000L;
        logger.info("V2 loitering time set to " + seconds + "s (native=" + (seconds * 10) + " frames, java=" + loiteringTimeMs + "ms)");
    }

    /** Live-update the approach fast-path bar (0 = disabled). Records a
     *  YOLO-confirmed MEDIUM(approach) after this many seconds instead of the
     *  full loitering time. */
    public void setV2ApproachTrigger(int seconds) {
        this.approachTriggerMs = (seconds <= 0) ? 0L : seconds * 1000L;
        logger.info("V2 approach trigger set to " + seconds + "s (java=" + approachTriggerMs + "ms"
                + (approachTriggerMs == 0 ? ", DISABLED)" : ")"));
    }
    
    /**
     * Enable/disable a specific camera quadrant for V2 detection.
     * @param quadrant 0=front, 1=right, 2=left, 3=rear
     * @param enabled true to enable, false to disable
     */
    public void setV2QuadrantEnabled(int quadrant, boolean enabled) {
        if (pipelineV2Config != null && quadrant >= 0 && quadrant < 4) {
            pipelineV2Config.quadrantEnabled[quadrant] = enabled;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            // Invalidate in-flight inference (audit R11-8 / ExtD-9): same
            // epoch the class toggle bumps — a lambda already past its entry
            // guards must not re-seed the track the drop below removes.
            // Atomic pair publish (audit R13-5 / ExtE-5): filter unchanged,
            // epoch advanced.
            classConfig = new ClassConfig(classFilter, classConfigEpoch.incrementAndGet());
            // DROP-ON-TOGGLE (audit R7 ExtC-9): a disabled quadrant stopped
            // producing MOTION, but its existing NCC track kept getting
            // updated, kept holding recordings open (trackerHolding scans all
            // quadrants), and its heartbeats kept RE-CERTIFYING the global
            // person-recency stamps for the event's duration — a person
            // standing in a user-disabled camera extended the recording to
            // the hard ceiling. Drop the track at the moment of the toggle.
            if (!enabled) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(quadrant)) {
                        NativeMotion.trackerDropTrack(quadrant);
                        logger.info("Dropped Q" + quadrant
                            + " track — quadrant disabled by user toggle");
                    }
                } catch (Throwable ignored) {
                    // Fail-open: TTL/heartbeat lifecycle still bounds it.
                }
            }
            logger.info("V2 quadrant " + MotionPipelineV2.QUADRANT_NAMES[quadrant] + 
                    " " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Get V2 pipeline results (for heatmap overlay / debug).
     */
    public MotionPipelineV2.QuadrantResult[] getV2Results() {
        return pipelineV2 != null ? pipelineV2.getResults() : null;
    }
    
    /**
     * Set shadow filter mode for V2 pipeline.
     * @param mode 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public void setV2ShadowFilterMode(int mode) {
        if (pipelineV2Config != null && mode >= 0 && mode <= 3) {
            pipelineV2Config.shadowFilterMode = mode;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
            logger.info("V2 shadow filter mode set to " + modeNames[mode]);
        }
    }
    
    /**
     * Get current shadow filter mode.
     * @return 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public int getV2ShadowFilterMode() {
        return pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : 0;
    }
    
    /**
     * Enable/disable filter debug logging.
     */
    public void setFilterDebugEnabled(boolean enabled) {
        this.filterDebugEnabled = enabled;
        if (!enabled) {
            synchronized (filterLog) {
                filterLogCount = 0;
                filterLogIndex = 0;
            }
        }
        logger.info("Filter debug log " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Add an entry to the filter debug log ring buffer.
     */
    private void addFilterLogEntry(String entry) {
        if (!filterDebugEnabled) return;
        synchronized (filterLog) {
            filterLog[filterLogIndex] = entry;
            filterLogIndex = (filterLogIndex + 1) % FILTER_LOG_CAPACITY;
            if (filterLogCount < FILTER_LOG_CAPACITY) filterLogCount++;
        }
    }
    
    /**
     * Get recent filter log entries (newest first).
     */
    public String[] getFilterLogEntries() {
        synchronized (filterLog) {
            String[] entries = new String[filterLogCount];
            for (int i = 0; i < filterLogCount; i++) {
                int idx = (filterLogIndex - 1 - i + FILTER_LOG_CAPACITY) % FILTER_LOG_CAPACITY;
                entries[i] = filterLog[idx];
            }
            return entries;
        }
    }
    
    public void release() {
        disable();

        // Drain any in-flight segment-metadata writes BEFORE we shut
        // down the executor. Skipping this would discard hero JPEGs
        // mid-compress. 2s budget matches stopRecording's drain budget.
        drainSegmentMetadata(2_000);
        segmentMetadataExecutor.shutdown();
        try {
            if (!segmentMetadataExecutor.awaitTermination(500,
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                segmentMetadataExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            segmentMetadataExecutor.shutdownNow();
        }

        // SOTA FIX: Shutdown the executor
        aiExecutor.shutdownNow();
        // Cancel any pending staggered seed dispatches and stop the scheduler
        // thread so it doesn't outlive the engine.
        aiScheduler.shutdownNow();
        // Storage housekeeping thread. shutdownNow() interrupts an in-flight
        // directory walk; that is safe (the cleanup lock is a plain monitor, so it
        // releases on unwind, and partial deletes are swept by RecordingsIndex
        // reconcile + the daemon's own 30 s reclaim), and the work re-runs on the
        // next startRecording — so there is nothing to drain.
        storageMaintenanceExecutor.shutdownNow();

        // Shut down the mosaic JPEG encoder thread + recycle its scratch
        // Bitmap. shutdownNow() interrupts any in-flight encode; we await
        // termination with a bounded budget so we don't recycle the scratch
        // Bitmap mid-Bitmap.compress — recycling under a live JNI compress
        // crashes native code.
        boolean encoderTerminated = false;
        try {
            mosaicJpegExecutor.shutdownNow();
            // Bounded wait: 200ms × up to 10 attempts = 2s total. Bitmap
            // compress at 640×480 q=82 typically completes in <80ms so a
            // single 200ms wait is almost always sufficient; the loop
            // covers a worst-case GC stall on the BYD SoC.
            int attempts = 0;
            while (attempts++ < 10) {
                if (mosaicJpegExecutor.awaitTermination(200,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    encoderTerminated = true;
                    break;
                }
            }
            if (!encoderTerminated) {
                logger.warn("mosaicJpegExecutor did not terminate within 2s — "
                        + "skipping bitmap recycle to avoid native crash");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
        if (encoderTerminated && mosaicJpegBitmapScratch != null) {
            try {
                if (!mosaicJpegBitmapScratch.isRecycled()) mosaicJpegBitmapScratch.recycle();
            } catch (Exception ignored) {}
        }
        // Drop refs unconditionally — if we couldn't recycle (encoder still
        // running), the GC will reclaim once the lingering encode finishes
        // and releases its strong reference.
        mosaicJpegBitmapScratch = null;
        mosaicJpegPixelsScratch = null;
        latestMosaicJpeg = null;

        // Clean up YOLO detector
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }

        currentFrame = null;
        // ThreadLocal: clear this thread's scratch. Other threads' entries
        // (aiExecutor, drainer) will be reclaimed when those threads exit
        // or the next allocation replaces them.
        aiBufferTL.remove();

        logger.info("Released");
    }
    
}
