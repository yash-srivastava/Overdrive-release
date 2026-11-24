package com.overdrive.app.roadsense.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SOTA temporal multi-sensor evidence-fusion classifier (D-038) — the answer to "make the
 * detector PRECISE on its own" (the user can't confirm/correct hazards while driving, so the
 * algorithm must stand alone) and to the user's physical model:
 *
 *   "slow down (brake) → gyro pitches UP then DOWN → speed recovers"  ⇒ SPEED BREAKER
 *   "gyro pitches DOWN then UP (drop into the hole, then climb out)"   ⇒ POTHOLE
 *
 * ## Why a fusion layer (not a new trigger)
 * Verified on 235 on-device labels: instantaneous AMPLITUDE cannot separate a real hazard from
 * a one-off false-positive thud, nor breaker from pothole — every amplitude/shape cut traded
 * false-alarms for genuine slow/gentle hazards ~1:1. The separating signal lives in (a) the
 * GYRO PITCH COUPLET (a real transverse feature pitches the body in a clean two-lobe sequence;
 * a lane seam / manhole / driver jolt does not), and (b) the BEHAVIORAL ENVELOPE around the
 * jolt (decelerate → cross → recover = a navigated hazard; a jolt at steady cruise with no
 * such envelope is far more likely noise). So this stays a TRIGGER → CLASSIFY split: the
 * [EventDetector] still finds the jolt; this fuses the surrounding evidence into a verdict.
 *
 * ## Soft fusion, not a hard AND-chain
 * [realnessScore] is a weighted sum of INDEPENDENT, each-normalized-to-[0,1] evidence terms,
 * minus a driver-artifact penalty. A pothole hit at steady speed (no brake/recover envelope)
 * still passes on pitch-couplet + biphasic alone — graceful degradation, never a brittle gate.
 *
 * ## Pitch-sign self-calibration
 * The signed pitch axis (GravityFrame.pitchRate) has an ambiguous absolute sign on a tilted
 * mount, so the breaker/pothole ORDER (+,− vs −,+) is only usable once we know which way is
 * "nose-up". We learn it ZERO-CONFIG from physics: during a confident braking event the nose
 * pitches DOWN, so the sign of pitchRate then anchors the convention ([PitchSignCalibrator]).
 * Until anchored, the couplet contributes magnitude/realness only, never a signed type vote —
 * exactly the "flat-highway cold start" fallback.
 *
 * ## Primary accept/type gate (D-040)
 * The controller's finalizeClassification runs [classify] on each completed event's full
 * ±window and uses [Verdict.accept] as THE store gate and [Verdict.type] as the authoritative
 * breaker/pothole call (SeverityClassifier supplies only severity/confidence). The weights +
 * [REALNESS_ACCEPT] remain PROVISIONAL physics-seeded priors — to be fit (logistic regression)
 * on the trustworthy (auto_accepted=0) label set; until then they're principled, not measured.
 *
 * Pure: no Android, no clock, no mutable shared state in [classify] (the sign calibrator is a
 * separate stateful helper the caller owns). Allocation-light; called once per completed event.
 * Every constant is PROVISIONAL — physics-seeded priors, to be fit (logistic regression) on the
 * trustworthy (auto_accepted=0) label set once available.
 */
class HazardClassifier {

    /**
     * One sample of rolling CONTEXT around an event, captured by the caller at ~20 Hz over a
     * ±[CONTEXT_WINDOW_MS] window. Decimated (not 100 Hz) — the envelope is a seconds-scale
     * behavioural signal, so 20 Hz is ample and keeps the ring tiny.
     */
    data class ContextSample(
        val tMs: Long,
        val speedKmh: Float,
        val brakePercent: Int,
        val accelPercent: Int,
        /** Signed pitch rate (rad/s) about the lateral axis (GravityFrame.pitchRate); +nose-up
         *  ONCE [PitchSignCalibrator] has anchored the sign, else raw/ambiguous. */
        val pitchRate: Float,
        /** True if the pitch axis was established for this sample (GravityFrame.pitchAxisReady). */
        val pitchValid: Boolean,
    )

    /** The classifier's verdict for one event. */
    data class Verdict(
        /** 0..1 — fused belief this is a REAL road hazard (precision lever). */
        val realnessScore: Float,
        /** True if [realnessScore] ≥ [REALNESS_ACCEPT] — THE primary store gate (D-040). */
        val accept: Boolean,
        /** Type from the fused vote (pitch couplet + accel morphology). */
        val type: HazardType,
        /** Whether the signed pitch couplet was usable (axis anchored + clean two lobes). */
        val pitchCoupletUsed: Boolean,
        /** Couplet order: +1 = up-then-down (breaker), −1 = down-then-up (pothole), 0 = none. */
        val pitchCoupletSign: Int,
        /** 0..1 sub-scores surfaced for the reject/accept log line (fit diagnostics). */
        val pitchScore: Float,
        val crossingScore: Float,
        val artifactScore: Float,
    )

    /**
     * Classify one completed event. [candidate] is the EventDetector's morphology; [context]
     * is the time-ordered ring of context samples around the event (caller-maintained);
     * [eventTMs] is the event's defining-peak time (to split pre/post envelope).
     */
    fun classify(
        candidate: DetectionCandidate,
        context: List<ContextSample>,
        eventTMs: Long,
    ): Verdict {
        // ---- A. Pitch couplet (gyro) ----
        // From the candidate's FULL-100Hz peak-held signed extrema (couplet-decimation fix), not
        // the 16 Hz context ring which aliases a fast breaker's ~150 ms lobes.
        val couplet = extractPitchCouplet(candidate)
        // realness contribution: a clean two-lobe couplet of ANY sign is strong evidence of a
        // real transverse feature (vs a one-sided wobble). Uses magnitude even before the sign
        // is anchored, so it helps on the flat-highway cold start too.
        val pitchScore = couplet.strength
        // signed type contribution only when the sign convention is anchored AND the couplet is clean.
        val pitchCoupletUsed = couplet.signed && couplet.strength >= COUPLET_MIN_STRENGTH
        val pitchCoupletSign = if (pitchCoupletUsed) couplet.sign else 0

        // ---- B. Vertical biphasic quality (accel) ----
        // A real load→rebound swings both ways; a lone spike is one-sided. min/max lobe ratio.
        val up = abs(candidate.peakUp); val down = abs(candidate.peakDown)
        val larger = max(up, down)
        val biphasicScore = if (larger < 1e-3f) 0f
            else (min(up, down) / larger / BIPHASIC_FULL).coerceIn(0f, 1f)

        // ---- B2. One-sidedness (lateral asymmetry) — the POTHOLE-defining cue (RECALL-3) ----
        // A single-wheel pothole rolls the body (lateral-dominant) and CANNOT produce a clean
        // balanced biphasic — penalizing it for failing the breaker-shaped biphasic test
        // structurally misses the most common pothole. So a measured one-sided hit earns its
        // OWN positive realness from lateralAsymmetry. Only when asymmetryValid (else 0 — never
        // spurious mass). This is the recall complement to the (breaker-shaped) biphasic term.
        val oneSidedScore = if (candidate.asymmetryValid)
            candidate.lateralAsymmetry.coerceIn(0f, 1f) else 0f

        // ---- C. Behavioural crossing envelope (speed + pedals) ----
        val crossingScore = crossingScore(context, eventTMs)

        // ---- D. Driver-artifact penalty ----
        // A jolt EXPLAINED by hard driver input WITHOUT a road-feature signature (no couplet,
        // no biphasic) is a brake-dive / launch-squat, not a hazard. Gated by ABSENCE of the
        // road cues so braking-INTO-a-real-breaker (which DOES have a couplet) isn't penalised.
        val artifactScore = artifactScore(context, eventTMs, pitchScore, biphasicScore)

        // ---- Fuse: EVIDENCE-PRESENT-renormalized weighted sum, minus artifact (RECALL-1) ----
        // CRITICAL (audit RECALL-1/2/3): divide ONLY by the weights of cues that are actually
        // PRESENT, not the full constant sum. A real isolated hazard at steady cruise has no
        // crossing envelope (crossingScore=0) and a single-wheel pothole has no axle-pair — with
        // a fixed denominator those MISSING-but-not-negative cues diluted the score and the
        // PRIMARY accept gate silently dropped genuine hazards (a clean pitch+biphasic normalized
        // to only 0.385 < 0.45). Renormalizing to present-evidence weights restores the doc's
        // "graceful degradation" contract: missing a cue neither helps nor hurts. pitch+biphasic
        // are ALWAYS measured (axis/lobes exist for any event) so they always count; crossing /
        // axle / one-sided count only when their evidence is genuinely present.
        var num = W_PITCH * pitchScore + W_BIPHASIC * biphasicScore
        var den = W_PITCH + W_BIPHASIC
        if (crossingScore > 0f) { num += W_CROSSING * crossingScore; den += W_CROSSING }
        if (candidate.axlePairGapMs != null) { num += W_AXLE; den += W_AXLE }
        val base = if (den > 1e-6f) num / den else 0f
        // One-sidedness is a POTHOLE-ONLY realness BONUS, added AFTER renormalization — NOT a
        // present-cue in the mean (audit RECALL-3-dilution). A breaker is structurally low-
        // asymmetry (~0.1) and asymmetryValid is its NORMAL state, so folding oneSidedScore into
        // the renormalized MEAN dilutes a clean breaker (0.70→0.51) and flips ~38% of breaker
        // shapes accept→reject just because the asymmetry channel validated. As a one-directional
        // bonus relative to the midpoint it can only LIFT a genuinely one-sided hit (the single-
        // wheel pothole RECALL-3 targets) and never SUBTRACTS from a balanced breaker.
        val oneSidedBonus = if (candidate.asymmetryValid)
            (W_ONESIDED * (oneSidedScore - ONESIDED_MIDPOINT)).coerceAtLeast(0f) else 0f
        val realness = (base + oneSidedBonus - W_ARTIFACT * artifactScore).coerceIn(0f, 1f)
        val realnessScore = realness

        // ---- Type vote (pitch couplet leads when usable; falls back to accel morphology) ----
        val type = classifyType(candidate, pitchCoupletUsed, pitchCoupletSign, oneSidedScore)

        return Verdict(
            realnessScore = realnessScore,
            accept = realnessScore >= REALNESS_ACCEPT,
            type = type,
            pitchCoupletUsed = pitchCoupletUsed,
            pitchCoupletSign = pitchCoupletSign,
            pitchScore = pitchScore,
            crossingScore = crossingScore,
            artifactScore = artifactScore,
        )
    }

    // ── Pitch couplet extraction ────────────────────────────────────────────────

    private data class Couplet(val sign: Int, val strength: Float, val signed: Boolean)

    /**
     * Classify the SIGNED pitch couplet from the candidate's FULL-100Hz peak-held extrema
     * (peakPitchUp / peakPitchDown + their times, in +nose-up convention). Using the true
     * peak-held extrema — not the 16 Hz context ring — means a fast breaker's brief ~150 ms
     * lobes can't be aliased away (couplet-decimation fix).
     *   up-first  (peakPitchUpTMs < peakPitchDownTMs) ⇒ breaker → sign +1
     *   down-first                                     ⇒ pothole → sign −1
     * strength = magnitude of the SMALLER lobe normalised to [PITCH_REF_RPS] (a clean two-lobe
     * couplet scores high; a one-sided wobble scores ~0). `signed` = pitchValid (axis anchored).
     * Both lobes must be non-trivial AND adequately separated in time to count as a couplet.
     */
    private fun extractPitchCouplet(candidate: DetectionCandidate): Couplet {
        val anyValid = candidate.pitchValid
        val posMag = candidate.peakPitchUp           // strongest nose-UP lobe (≥0)
        val negMag = candidate.peakPitchDown         // strongest nose-DOWN lobe magnitude (≥0)
        val posT = candidate.peakPitchUpTMs
        val negT = candidate.peakPitchDownTMs
        // need BOTH lobes, each above noise, adequately separated, to be a couplet
        if (posMag <= COUPLET_MIN_LOBE_RPS || negMag <= COUPLET_MIN_LOBE_RPS ||
            abs(posT - negT) < COUPLET_MIN_SEP_MS) {
            return Couplet(0, 0f, anyValid)
        }
        val smaller = min(posMag, negMag)
        val strength = (smaller / PITCH_REF_RPS).coerceIn(0f, 1f)
        val sign = if (posT < negT) +1 else -1   // +1 = up-then-down (breaker), -1 = down-then-up (pothole)
        return Couplet(sign, strength, anyValid)
    }

    // ── Crossing envelope ───────────────────────────────────────────────────────

    /**
     * 0..1 score for the "decelerate → jolt → recover" behavioural signature of a navigated
     * hazard. PRE window: did speed drop and/or brake rise before the jolt? POST window: did
     * brake release / accel rise / speed recover after? Each half is 0..1; the score is their
     * average so PARTIAL envelopes (braked but didn't visibly recover, or vice-versa) still
     * contribute. Absent envelope ⇒ 0 (not negative — a hazard at steady speed is still real,
     * it just lacks the intent signal; realness leans on pitch+biphasic there).
     */
    private fun crossingScore(context: List<ContextSample>, eventTMs: Long): Float {
        // pre-window stats
        var preSpeedMax = Float.NEGATIVE_INFINITY; var preSpeedAtEdge = Float.NaN
        var preBrakeMax = 0
        // post-window stats
        var postSpeedMin = Float.POSITIVE_INFINITY; var postSpeedLate = Float.NaN
        var postAccelMax = 0; var postBrakeMin = 101
        var atJumpBrake = 0; var atJumpSpeed = Float.NaN
        for (s in context) {
            val dt = s.tMs - eventTMs
            if (dt in -ENVELOPE_MS..0L) {
                if (s.speedKmh > preSpeedMax) preSpeedMax = s.speedKmh
                if (s.brakePercent > preBrakeMax) preBrakeMax = s.brakePercent
                if (dt > -DECIMATE_MS) { preSpeedAtEdge = s.speedKmh }  // ~at the jolt
            }
            if (abs(dt) <= DECIMATE_MS) { atJumpBrake = max(atJumpBrake, s.brakePercent); atJumpSpeed = s.speedKmh }
            if (dt in 0L..ENVELOPE_MS) {
                if (s.speedKmh < postSpeedMin) postSpeedMin = s.speedKmh
                if (s.accelPercent > postAccelMax) postAccelMax = s.accelPercent
                if (s.brakePercent < postBrakeMin) postBrakeMin = s.brakePercent
                postSpeedLate = s.speedKmh
            }
        }
        // DECEL-before: speed fell by ≥ DECEL_KMH across the pre-window, OR brake was pressed.
        val speedDrop = if (preSpeedMax.isFinite() && !preSpeedAtEdge.isNaN()) preSpeedMax - preSpeedAtEdge else 0f
        val decelEvidence = max(
            (speedDrop / DECEL_KMH).coerceIn(0f, 1f),
            (preBrakeMax.toFloat() / BRAKE_REF).coerceIn(0f, 1f),
        )
        // RECOVER-after: brake released (drop from the at-jolt level), OR accel pressed, OR speed recovered.
        val brakeRelease = if (postBrakeMin <= 100) (atJumpBrake - postBrakeMin).toFloat() else 0f
        val speedRecover = if (postSpeedLate.isFinite() && postSpeedMin.isFinite()) postSpeedLate - postSpeedMin else 0f
        val recoverEvidence = maxOf(
            (brakeRelease / BRAKE_REF).coerceIn(0f, 1f),
            (postAccelMax.toFloat() / ACCEL_REF).coerceIn(0f, 1f),
            (speedRecover / DECEL_KMH).coerceIn(0f, 1f),
        )
        return ((decelEvidence + recoverEvidence) * 0.5f).coerceIn(0f, 1f)
    }

    // ── Driver-artifact penalty ─────────────────────────────────────────────────

    /**
     * 0..1 — how strongly the jolt looks driver-caused WITHOUT a road feature. A hard brake or
     * launch AT the jolt, combined with LOW road-cue evidence (no clean couplet, weak biphasic),
     * means the vertical transient is brake-dive / launch-squat. The "AND low road cue" gating is
     * the key: braking INTO a real breaker has a couplet + biphasic, so it scores ~0 here and
     * isn't penalised — fixing the old RejectionFilter tension where any fresh brake>25% dropped
     * the bump. Steering/yaw cornering is left to RejectionFilter (unchanged).
     */
    private fun artifactScore(
        context: List<ContextSample>,
        eventTMs: Long,
        pitchScore: Float,
        biphasicScore: Float,
    ): Float {
        // Widen the pedal scan modestly (a single ±60 ms sample can miss the poll); use the
        // peak brake/accel within ±ARTIFACT_SCAN_MS of the jolt.
        var brakeAt = 0; var accelAt = 0
        for (s in context) {
            if (abs(s.tMs - eventTMs) <= ARTIFACT_SCAN_MS) {
                brakeAt = max(brakeAt, s.brakePercent); accelAt = max(accelAt, s.accelPercent)
            }
        }
        // RECALL-2 fix: ramp the pedal penalty only ABOVE an onset (~50%), not from 0. A
        // deliberate decel FOR a breaker is a moderate, sustained brake that should NOT be
        // treated as a hard brake-DIVE; only an aggressive press (a real dive/launch) ramps the
        // penalty. (brake−onset)/(REF−onset) so onset→0, REF→1.
        val brakePen = ((brakeAt - BRAKE_ARTIFACT_ONSET) / (ARTIFACT_REF_HI - BRAKE_ARTIFACT_ONSET)).coerceIn(0f, 1f)
        val accelPen = ((accelAt - ACCEL_ARTIFACT_ONSET) / (ARTIFACT_REF_HI - ACCEL_ARTIFACT_ONSET)).coerceIn(0f, 1f)
        val pedal = max(brakePen, accelPen)
        // road-cue presence suppresses the penalty: a strong couplet/biphasic ⇒ real feature even
        // if the driver was also braking. (Deliberately NOT crediting crossingScore here — a pure
        // brake-dive ALSO has a decel→recover envelope, so crediting it would reopen the FP hole.)
        val roadCue = max(pitchScore, biphasicScore)
        return (pedal * (1f - roadCue)).coerceIn(0f, 1f)
    }

    // ── Type vote ───────────────────────────────────────────────────────────────

    private fun classifyType(c: DetectionCandidate, coupletUsed: Boolean, coupletSign: Int, oneSidedScore: Float): HazardType {
        var potholeVote = 0f
        // Pitch couplet is the LEAD type cue when usable (geometric + speed-robust): up-then-down
        // (+1) ⇒ breaker, down-then-up (−1) ⇒ pothole.
        if (coupletUsed) potholeVote += if (coupletSign < 0) PITCH_TYPE_VOTE else -PITCH_TYPE_VOTE
        // Rise time (proven discriminator on the label set: potholes sharp <50 ms, breakers ramped).
        // Abstains when the stream is too coarse to resolve it (e.g. 10 Hz DiLink 5).
        if (c.riseResolved) {
            potholeVote += if (c.riseTimeMs in 0 until POTHOLE_RISE_MS) RISE_VOTE else -RISE_VOTE
        }
        // dip-leading (supporting, noisier on a single tilt-corrected channel).
        potholeVote += if (c.dipLeading) DIP_VOTE else -DIP_VOTE
        // axle-pair ⇒ full-width transverse feature ⇒ breaker (strong, geometric).
        if (c.axlePairGapMs != null) potholeVote -= AXLE_VOTE
        // one-sidedness (lateral-dominant) ⇒ single-wheel POTHOLE (only when measured).
        if (c.asymmetryValid) potholeVote += (oneSidedScore - ONESIDED_MIDPOINT) * ONESIDED_VOTE
        return when {
            potholeVote >= TYPE_MARGIN -> HazardType.POTHOLE
            potholeVote <= -TYPE_MARGIN -> HazardType.BREAKER
            else -> HazardType.UNKNOWN
        }
    }

    companion object {
        // ── windows (ms) — physics/behaviour timescales, not fit ──
        const val CONTEXT_WINDOW_MS = 2_500L     // ± span the caller buffers
        const val ENVELOPE_MS = 1_500L           // pre/post window for the crossing envelope
        const val COUPLET_MIN_SEP_MS = 60L       // min separation of the two lobes (ignore ripple)
        /** Min lobe magnitude (rad/s) for a couplet half to count — above gyro noise (~1e-3) and
         *  small body wobble, so only a genuine pitch excursion forms a couplet. PROVISIONAL. */
        const val COUPLET_MIN_LOBE_RPS = 0.03f
        const val DECIMATE_MS = 60L              // ~20 Hz context sample spacing / "at the jolt" half-width

        // ── pitch ──
        /** Reference body-rotation rate (rad/s) that normalises the couplet's smaller lobe. F-006:
         *  a hard STEERING turn ramped to ~0.35; a breaker pitch transient is briefer/smaller, so
         *  ~0.12 marks "clearly pitching". PROVISIONAL. */
        const val PITCH_REF_RPS = 0.12f
        /** Min couplet strength (smaller-lobe / ref) to TRUST the signed type vote. */
        const val COUPLET_MIN_STRENGTH = 0.4f

        // ── biphasic ──
        /** min/max lobe ratio at/above which the vertical pulse reads fully biphasic. */
        const val BIPHASIC_FULL = 0.6f

        // ── envelope refs ──
        const val DECEL_KMH = 8f                 // speed drop that = full decel evidence
        const val BRAKE_REF = 30f                // brake% that = full ENVELOPE pedal evidence
        const val ACCEL_REF = 30f                // accel% that = full launch/recover envelope evidence

        // ── artifact penalty (RECALL-2: don't punish a deliberate decel-for-breaker) ──
        const val ARTIFACT_SCAN_MS = 120L        // ±window around the jolt to read peak pedal
        /** Pedal % below which there's NO artifact penalty — a moderate, deliberate brake for a
         *  breaker is normal driving, not a brake-DIVE. Only an aggressive press ramps the penalty
         *  (BRAKE_ARTIFACT_ONSET→0 … a hard press→1). */
        const val BRAKE_ARTIFACT_ONSET = 50f
        const val ACCEL_ARTIFACT_ONSET = 45f
        const val ARTIFACT_REF_HI = 90f          // pedal % at which the penalty saturates to 1
        // (BRAKE_REF/ACCEL_REF stay the envelope-evidence refs; the artifact ramp uses the ONSET→HI
        //  band below, computed in artifactScore.)

        // ── fusion weights (physics-seeded priors; geometric cues highest) — PROVISIONAL, to fit ──
        // Denominator is EVIDENCE-PRESENT-renormalized (RECALL-1): only present cues divide.
        const val W_PITCH = 0.30f
        const val W_BIPHASIC = 0.25f
        const val W_CROSSING = 0.25f
        const val W_AXLE = 0.20f
        /** One-sidedness (lateral asymmetry) — the single-wheel POTHOLE realness cue (RECALL-3),
         *  so a one-sided hit earns acceptance from its DEFINING signature instead of failing the
         *  breaker-shaped biphasic test. Only counts when asymmetryValid. */
        const val W_ONESIDED = 0.25f
        /** Artifact veto weight. Lowered 0.5→0.35 (RECALL-2) so a moderate-but-legitimate
         *  road-cue signature keeps a comfortable accept margin while a pure brake-dive
         *  (roadCue≈0, hard pedal) is still pushed negative. */
        const val W_ARTIFACT = 0.35f

        /** Accept threshold on realnessScore. With evidence-present renormalization (RECALL-1) a
         *  clean pitch+biphasic at steady cruise now normalizes to ~0.7, so 0.45 is a real bar
         *  again (not a recall trap). PROVISIONAL — fit so the accept set matches the trustworthy
         *  (auto_accepted=0) labels; this is now the PRIMARY accept gate, not shadow. */
        const val REALNESS_ACCEPT = 0.45f

        // ── type votes (mirror SeverityClassifier's data-fit lead = rise) ──
        const val PITCH_TYPE_VOTE = 1.0f         // lead when couplet usable
        const val RISE_VOTE = 1.2f               // proven strongest accel discriminator
        const val DIP_VOTE = 0.6f
        const val AXLE_VOTE = 1.0f
        /** One-sidedness type vote: lateral-dominant ⇒ pothole, centered on the midpoint so a
         *  symmetric (full-width) hit votes breaker. Mirrors SeverityClassifier's asymmetry vote. */
        const val ONESIDED_VOTE = 0.6f
        const val ONESIDED_MIDPOINT = 0.25f
        const val POTHOLE_RISE_MS = 50
        const val TYPE_MARGIN = 0.6f
    }
}

/**
 * Zero-config pitch-sign self-calibrator (D-038). The signed [GravityFrame.pitchRate] axis has
 * an ambiguous absolute sign on a tilted mount; physics gives us the anchor: during a confident
 * braking event the nose pitches DOWN. So we observe the sign of pitchRate during clear braking
 * and latch which polarity = "nose-down". One-shot per drive, then the breaker/pothole couplet
 * ORDER is usable. Until anchored, callers treat couplets as magnitude-only.
 *
 * Stateful, single-threaded (daemon IPC/sensor thread, like the rest of the pipeline). The
 * caller feeds it pitchRate + brake% + a "speed is dropping" flag each accel sample; it latches
 * a small signed bias once it has seen enough consistent braking-pitch evidence.
 */
class PitchSignCalibrator {
    // accumulated signed pitch during confident braking; its sign, once stable, = "nose-down".
    private var brakingPitchSum = 0f
    private var brakingSamples = 0
    private var latched = false
    /** +1 if raw pitchRate>0 means nose-DOWN (so breaker up-first = pitchRate<0 first), −1 otherwise.
     *  Callers multiply raw pitchRate by [orientation] to get a +nose-UP convention. */
    private var orientationSign = 0   // 0 = not yet known

    /** True once the sign convention is anchored. */
    val anchored: Boolean get() = latched

    /** Multiply a raw signed pitchRate by this to get +nose-up. 0 until anchored. */
    val orientation: Int get() = orientationSign

    /**
     * Feed one sample. [pitchRate] raw signed (GravityFrame.pitchRate), [pitchValid] its axis
     * readiness, [brakePercent] current pedal, [speedDropping] whether speed is falling,
     * [yawRateRps] current |yaw rate| (cornering). Accumulates pitch ONLY during a confident,
     * STRAIGHT-LINE braking event (pedal high AND decelerating AND axis valid AND NOT cornering).
     *
     * RS-3 hardening: braking WHILE TURNING (or on a cambered/graded surface) superimposes roll/
     * grade coupling onto the braking-dive pitch, which could latch the WRONG polarity and then
     * invert breaker↔pothole for the whole process. Excluding high-yaw samples keeps the anchor
     * to clean straight-line braking dives, where the nose-down pitch is unambiguous. If a turn-
     * contaminated sample sneaks in mid-accumulation we also RESET the partial sum so a single
     * stretch must be consistently straight to latch.
     */
    fun onSample(pitchRate: Float, pitchValid: Boolean, brakePercent: Int, speedDropping: Boolean, yawRateRps: Float) {
        if (latched || !pitchValid) return
        if (yawRateRps > MAX_YAW_FOR_ANCHOR) {
            // cornering contamination — discard the in-progress accumulation, require a fresh
            // clean straight-braking stretch.
            brakingPitchSum = 0f; brakingSamples = 0
            return
        }
        if (brakePercent < BRAKE_FOR_ANCHOR || !speedDropping) return
        brakingPitchSum += pitchRate
        brakingSamples++
        if (brakingSamples >= MIN_BRAKING_SAMPLES && abs(brakingPitchSum) >= MIN_ABS_SUM) {
            // During braking the nose goes DOWN; the dominant pitch sign observed = "nose-down".
            // We want +nose-up, so orientation flips that: if braking pitch summed POSITIVE,
            // raw +pitch = nose-down → orientation = -1 (multiply to make nose-up positive).
            orientationSign = if (brakingPitchSum > 0f) -1 else +1
            latched = true
        }
    }

    /** Reset on sensor restart / regime reset (matches the rest of the pipeline's reset contract). */
    fun reset() {
        brakingPitchSum = 0f; brakingSamples = 0; latched = false; orientationSign = 0
    }

    companion object {
        const val BRAKE_FOR_ANCHOR = 30      // pedal% that counts as "confident braking"
        const val MIN_BRAKING_SAMPLES = 20   // ~ a fraction of a second of braking at 20 Hz context
        const val MIN_ABS_SUM = 0.10f        // ignore noise-level accumulations
        /** Max |yaw rate| (rad/s) during anchoring — above this the braking is during a TURN and
         *  roll/grade coupling can latch the wrong pitch sign (RS-3). F-006: a committed turn is
         *  ~0.25+ rad/s; 0.10 keeps anchoring to near-straight braking. */
        const val MAX_YAW_FOR_ANCHOR = 0.10f
    }
}
