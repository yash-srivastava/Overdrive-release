package com.overdrive.app.roadsense.detect

/**
 * Shared data contracts for the RoadSense detection pipeline.
 *
 * This is the integration seam every stage agrees on. Stages:
 *
 *   IMU(-iner) ─► GravityFrame ─► VerticalSample ─► EventDetector ─► DetectionCandidate
 *                                                                          │
 *                          RejectionFilter (gyro/speed) ──────────────────┤
 *                                                                          ▼
 *                          SeverityClassifier + VehicleCalibrator ──► Assessment
 *                                                                          │
 *                          GpsRingBuffer.backProject(t) ─► Pose            ▼
 *                                                              RoadSenseHazard (→ store)
 *
 * Keep these types dumb (data only) and Android-free so every stage stays
 * unit-testable. See dev/roadsense/03-ARCHITECTURE.md for the design.
 */

/** Sentinel for "GPS altitude not available" / "level unknown". NaN so it never
 *  accidentally compares close to a real altitude (every NaN comparison is false),
 *  which structurally forces the fail-open branch in every altitude gate. NOT 0.0 —
 *  a real sea-level fix exists and must not read as unknown. */
const val ALTITUDE_UNKNOWN: Double = Double.NaN

/** True when an altitude value is a real reading (not the unknown sentinel). */
fun altitudeKnown(a: Double): Boolean = !a.isNaN()

/** Max altitude delta (m) for two points to count as the "same level". GPS altitude
 *  on automotive GNSS is ±10–30 m noisy, so this is deliberately large: only a CLEAR
 *  delta beyond the noise envelope means "different deck" (tall flyover / stacked
 *  ramp). A low overpass (~5 m) won't be separated — that fails SAFE (we keep
 *  warning) rather than risk suppressing a real same-level hazard. PROVISIONAL. */
const val ALTITUDE_MATCH_M: Double = 20.0

/** Fail-OPEN altitude match: same level if EITHER altitude is unknown OR they're
 *  within [ALTITUDE_MATCH_M]. Only two KNOWN altitudes differing by more than the
 *  threshold count as different levels. This guarantees altitude noise / missing
 *  data can never SUPPRESS a real hazard — the only path to suppression is a
 *  confident, large mismatch (a genuine flyover/ramp). */
fun altitudeMatches(a: Double, b: Double): Boolean =
    !altitudeKnown(a) || !altitudeKnown(b) || kotlin.math.abs(a - b) <= ALTITUDE_MATCH_M

/**
 * Hazard kind. Separate axis from [Severity] (D-011).
 *
 * ROUGH_SECTION (D-032) is a SUSTAINED rough/washboard/cobble stretch — not a
 * single discrete feature but a continuous run of closely-spaced jolts. It is
 * emitted by the controller when the candidate rate stays in the washboard band
 * for long enough that the stretch is itself the hazard worth one warning, rather
 * than chiming on every cobble. Ordinals are APPEND-ONLY: BREAKER/POTHOLE/UNKNOWN
 * keep ordinals 0/1/2 because the ordinal is the value persisted in H2 and sent
 * to the cloud — reordering would silently re-label every stored + remote row.
 */
enum class HazardType { BREAKER, POTHOLE, UNKNOWN, ROUGH_SECTION }

/** 3-bucket severity (D-011). Ordinal 1..3 maps to the stored `severity` column. */
enum class Severity(val level: Int) { MINOR(1), MODERATE(2), SEVERE(3) }

/** One raw accelerometer sample from the -iner sensor (device frame, m/s²). */
data class ImuAccelSample(val tMs: Long, val ax: Float, val ay: Float, val az: Float)

/** One raw gyroscope sample from the -iner sensor (device frame, rad/s). */
data class ImuGyroSample(val tMs: Long, val gx: Float, val gy: Float, val gz: Float)

/**
 * Vehicle-dynamics snapshot from the BYD bus (F-011, D-018) — the SOTA rejection
 * + confidence lever. All fields are "latest known"; pedal/gear are coarser
 * (~poll) than speed/steering (push), so each carries its own sample age so the
 * [RejectionFilter] can discount stale values near a fast event.
 */
data class VehicleDynamics(
    val tMs: Long,
    val speedKmh: Float,
    val brakePercent: Int,        // 0..100; high during braking dive
    val accelPercent: Int,        // 0..100; high during launch squat
    val steeringAngleDeg: Float,  // ±780; large/changing ⇒ cornering body-roll
    val gearMode: Int,            // P=1 R=2 N=3 D=4 M=5 S=6
    val brakeAgeMs: Long,         // age of the brake/accel/gear poll vs tMs
) {
    val isForwardDrive: Boolean get() = gearMode == 4 || gearMode == 5 || gearMode == 6
}

/**
 * Output of [GravityFrame] for one accel sample: the tilt-corrected vertical
 * residual plus the speed at that instant (carried for normalization downstream).
 */
data class VerticalSample(
    val tMs: Long,
    val aVert: Float,     // m/s²; +up (crest), -down (dip), ~0 flat
    val speedMps: Float,  // vehicle speed at this sample (from latest GPS/BYD)
    /** Gravity-free LATERAL (cross-track) residual magnitude (m/s²) at this sample,
     *  from [GravityFrame.lateralResidual]. The detector tracks its peak over the
     *  event window and pairs it with the vertical peak to derive lateral
     *  asymmetry (one-sided pothole rolls the body → larger lateral kick vs
     *  vertical; a full-width breaker hits both wheels → mostly vertical). The
     *  longitudinal (brake/accel) component is split out into [aLong] so a
     *  fore-aft jolt can't masquerade as one-sidedness (audit detection #6). Default
     *  0 so any caller/test that doesn't supply it simply yields asymmetryValid=false. */
    val aHoriz: Float = 0f,
    /** Gravity-free LONGITUDINAL (fore-aft, brake/accel) residual magnitude (m/s²)
     *  at this sample, from [GravityFrame.longitudinalResidual]. The detector tracks
     *  its peak so the asymmetry stage can refuse to validate one-sidedness when the
     *  horizontal energy is fore-aft (a stale-poll brake/launch transient, audit
     *  detection #6) rather than lateral body-roll. Default 0. */
    val aLong: Float = 0f,
)

/**
 * A raw event the [EventDetector] extracted from the a_vert stream, BEFORE
 * rejection/classification. Carries the morphology the later stages reason on.
 */
data class DetectionCandidate(
    val tMs: Long,            // timestamp of the event's defining peak
    val peakUp: Float,        // max positive a_vert in the window (m/s²)
    val peakDown: Float,      // min (most negative) a_vert in the window (m/s²)
    val riseTimeMs: Int,      // time from zero-cross to peak — pothole≪breaker
    val durationMs: Int,      // total event span
    val dipLeading: Boolean,  // true if down-first (pothole-ish), false if up-first
    val speedMps: Float,      // speed at the event
    /** Gap to a same-shape second pulse if an axle-pair was detected, else null.
     *  Δt ≈ wheelbase / v ⇒ high-confidence transverse road feature. */
    val axlePairGapMs: Int? = null,
    /** Lateral asymmetry 0..1: 0 = symmetric (full-width breaker), 1 = one-sided
     *  (single-wheel pothole). Derived from horizontal accel components. */
    val lateralAsymmetry: Float = 0f,
    /** Peak |pitch rate| (rad/s) over the event window — the body's nose-up/down
     *  rotation about the lateral axis (GravityFrame.pitchRate). RECORDED FEATURE: the
     *  live type call uses the SIGNED couplet extrema ([peakPitchUp]/[peakPitchDown]),
     *  not this unsigned peak; this magnitude is persisted to the ground-truth label
     *  store (peak_pitch_rate column) as a training feature for the offline weight fit.
     *  Gate on [pitchValid] — when false this is 0 ("not measured"), never a real zero. */
    val peakPitchRate: Float = 0f,
    /** Peak |roll rate| (rad/s) over the event window — body roll about the fore-aft
     *  axis (GravityFrame.rollRate). RECORDED FEATURE (peak_roll_rate label column),
     *  paired with [peakPitchRate]: high roll + low pitch ⇒ one-sided (pothole); high
     *  pitch + low roll ⇒ full-width (breaker). Gate on [pitchValid]. */
    val peakRollRate: Float = 0f,
    /** Whether the pitch/roll split was actually established for this event (the slow
     *  longitudinal-axis estimate had enough magnitude — GravityFrame.pitchAxisReady —
     *  for at least one gyro sample in the window). When false, [peakPitchRate] /
     *  [peakRollRate] are 0 (and the persisted features read as "not measured"), same
     *  posture as [asymmetryValid]. Default false so tests/callers without gyro stay inert. */
    val pitchValid: Boolean = false,
    /** SIGNED pitch-couplet extrema (rad/s, +nose-up convention once the sign is anchored),
     *  peak-held at FULL 100 Hz over the event window — the strongest nose-UP lobe
     *  [peakPitchUp] (≥0) and strongest nose-DOWN lobe [peakPitchDown] (≥0, magnitude) and
     *  their sensor times. The HazardClassifier couplet uses these true extrema instead of the
     *  16 Hz context ring (which aliases a fast breaker's ~150 ms lobes). Order of the two times
     *  gives breaker (up-first) vs pothole (down-first). Gate on [pitchValid]. Default 0. */
    val peakPitchUp: Float = 0f,
    val peakPitchUpTMs: Long = 0L,
    val peakPitchDown: Float = 0f,
    val peakPitchDownTMs: Long = 0L,
    /** Whether [lateralAsymmetry] is actually MEASURED for this event. The
     *  horizontal-accel stage IS built (GravityFrame splits the gravity-free
     *  horizontal residual into lateral/longitudinal; EventDetector pairs the
     *  lateral peak with the vertical peak). This is true when the vertical peak
     *  cleared the noise band AND the longitudinal (brake/accel) component didn't
     *  dominate the kick; false otherwise (e.g. a borderline blip or a stale-poll
     *  brake transient). Consumers MUST still gate on this — when false, treat
     *  [lateralAsymmetry] (0f) as "unknown", never as a real symmetric reading,
     *  so it can't bias type toward breaker or feed the curb/slam reject. The
     *  default is false so any test/caller that doesn't supply the horizontal
     *  channel stays safely inert. */
    val asymmetryValid: Boolean = false,
    /** False when the stream's sample period is too coarse for [riseTimeMs] to mean anything. */
    val riseResolved: Boolean = true,
)

/** Verdict from [RejectionFilter]: is this candidate a real road hazard? */
data class RejectionVerdict(val rejected: Boolean, val reason: String? = null)

/**
 * Final per-event assessment from [SeverityClassifier] (after [VehicleCalibrator]
 * normalization). This is what gets persisted as a hazard.
 */
data class Assessment(
    val type: HazardType,
    val severity: Severity,
    val confidence: Float,     // 0..1 — drives warn (D-015) and upload (D-016) gates
    val normalizedPeak: Float, // speed+vehicle-normalized impulse (for re-bucketing)
)

/**
 * A vehicle pose sample (GPS or BYD speed bus fused). [GpsRingBuffer] keeps a
 * short history of these and interpolates to a past timestamp for back-projection.
 */
data class Pose(
    val tMs: Long,
    val lat: Double,
    val lng: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val accuracyM: Float,
    /** WGS-84 ellipsoidal altitude (m) from the GPS fix, or [ALTITUDE_UNKNOWN] (NaN)
     *  when the fix carried none. GPS altitude is noisy (±10–30 m), so it's used ONLY
     *  as a coarse level disambiguator (flyover vs road below), never fine geometry.
     *  Default unknown so any caller that omits it stays fail-open. */
    val altitudeM: Double = ALTITUDE_UNKNOWN,
)

/**
 * A fully-formed hazard ready to WRITE to the store (the measured fields from a
 * detection). The store mints id/tile and owns status/observations bookkeeping.
 * This is the INPUT shape (upsertDetection); reads come back as [StoredHazard].
 */
data class RoadSenseHazard(
    val lat: Double,
    val lng: Double,
    val type: HazardType,
    val severity: Severity,
    val headingDeg: Float,
    val confidence: Float,
    val speedKmh: Float,
    val aVertPeak: Float,
    val tMs: Long,
    /** Altitude (m, WGS-84) of the car when this hazard was detected, or
     *  [ALTITUDE_UNKNOWN] (NaN). Disambiguates a flyover hazard from one on the
     *  surface road directly below. Default unknown → fail-open match. */
    val altitudeM: Double = ALTITUDE_UNKNOWN,
)

/**
 * A hazard READ back from the store — the measured [hazard] plus the row identity
 * and bookkeeping the store owns. Consumers need these:
 *   - [id]: to address the exact row for Calibration-Mode `markHumanVerified`
 *     (the overlay confirm card, R-OVL-6) and to map server consensus responses
 *     back to a local row.
 *   - [status]: 0=candidate, 1=locally-confirmed, 2=shared/cloud-confirmed (D-012).
 *   - [observations]: self-confirm counter (K=2 → locally-confirmed, D-012).
 *   - [humanVerified]: this row carries a user ground-truth label (R-DET-7).
 *   - [createdMs]/[updatedMs]: detection time vs last-touch (diverge after a merge).
 * This is the seam fix for the store read paths having nowhere to return identity.
 */
data class StoredHazard(
    val id: String,
    val hazard: RoadSenseHazard,
    val status: Int,
    val observations: Int,
    val humanVerified: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
) {
    companion object {
        const val STATUS_CANDIDATE = 0
        const val STATUS_LOCALLY_CONFIRMED = 1
        const val STATUS_SHARED = 2
    }
}
