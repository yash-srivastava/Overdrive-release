package com.overdrive.app.roadsense.detect

import kotlin.math.sqrt

/**
 * Converts raw `-iner` accelerometer samples into a **vertical residual**
 * `a_vert` — the signal the detector actually keys on.
 *
 * Why this exists (F-005, F-008): the head-unit IMU is mounted ~9.2° off true
 * vertical, so raw `aZ` is not "up" — it mixes gravity with cornering/braking
 * horizontal g. We can't use TYPE_GRAVITY/LINEAR_ACCELERATION (fusion sensors
 * built on the stub accel, F-004), so we estimate gravity ourselves:
 *
 *   ĝ  = low-pass(accel)                      (the gravity unit vector, device frame)
 *   a_vert = dot(accel, ĝ) − |g|              (pure vertical residual, tilt-corrected)
 *
 * `a_vert` ≈ 0 on flat road, swings ±several m/s² on a bump/pothole. The 9.2°
 * tilt is corrected automatically because ĝ is *measured*, not assumed to be Z.
 *
 * Production note (vs the probe's whole-drive mean): ĝ is a SLOW exponential
 * moving average so it tracks slow attitude changes (hills, remount) but does
 * NOT absorb fast bump transients. Time constant ≈ several seconds.
 *
 * Pure math, no Android deps — unit-testable. Not thread-safe; the owning
 * service feeds it from a single sensor thread.
 */
class GravityFrame(
    /**
     * Low-pass smoothing factor per sample for the gravity estimate.
     * alpha is the weight given to the NEW sample. At 100 Hz, alpha=0.02 gives
     * a time constant of ~0.5 s; we want gravity to settle in a second or two
     * but ignore ~150 ms bumps, so a small alpha is correct. Lower = slower =
     * more bump-rejection in the gravity track but slower attitude tracking.
     */
    private val alpha: Float = DEFAULT_ALPHA,
) {
    // Running gravity estimate in device frame (m/s²). Lazily seeded on first sample.
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var seeded = false
    // Lazy pitch axis, valid until the next accelerometer update or reset.
    private var pitchAxisDirty = true
    private var pitchX = 0f; private var pitchY = 0f; private var pitchZ = 0f

    // Effective per-sample EMA weights; equal to the 100 Hz constants unless [setSamplePeriodMs] rescaled them.
    private var gravityAlpha = alpha
    private var longAxisAlpha = LONG_AXIS_ALPHA

    /** Rescale the EMA weights so their time constants match the 100 Hz tuning; null restores them. */
    fun setSamplePeriodMs(periodMs: Float?) {
        gravityAlpha = if (periodMs == null) alpha else rescaleAlpha(alpha, periodMs)
        longAxisAlpha = if (periodMs == null) LONG_AXIS_ALPHA else rescaleAlpha(LONG_AXIS_ALPHA, periodMs)
    }

    val profileSummary: String
        get() = String.format(java.util.Locale.ROOT, "gAlpha=%.4f lAlpha=%.4f", gravityAlpha, longAxisAlpha)

    /**
     * Horizontal-residual magnitude (m/s²) of the most recent [update] sample —
     * the component of acceleration ORTHOGONAL to gravity. Gravity lives entirely
     * along ĝ, so this is gravity-free by construction. ~0 on flat cruise; spikes
     * on a body-roll/lateral kick (a one-sided pothole loads one wheel and rolls
     * the body) AND on longitudinal brake/accel. The longitudinal part is split out
     * into [longitudinalResidual] below so the asymmetry stage uses only the lateral
     * part — a stale-poll hard-brake-and-bump (F-011: pedal polls ~5 s old) can
     * otherwise slip past the rejection filter and inflate asymmetry (audit
     * detection #6). The asymmetry stage in [EventDetector] pairs [lateralResidual]
     * against the vertical peak.
     */
    var horizontalResidual: Float = 0f
        private set

    // Slow EMA of the gravity-free horizontal acceleration VECTOR (device frame).
    // Braking dive / launch squat push the body along a FIXED device-frame axis
    // (forward/back) for ~seconds, so this persistent average approximates the
    // longitudinal (travel) axis without needing GPS heading or gyro integration.
    // A fast one-sided bump is a brief transient that barely moves this average, so
    // its kick lands mostly in the lateral (orthogonal) remainder. Same time
    // constant family as the gravity track.
    private var hax = 0f
    private var hay = 0f
    private var haz = 0f

    // LATCHED fore-aft (longitudinal) unit axis + the EFFECTIVE axis used by the
    // pitch/roll split. The live EMA (hax/hay/haz) decays back toward 0 within ~1–2 s
    // of horizontal accel returning to nil, so on the common "brake early, then coast
    // the last stretch to the breaker" approach the axis would be GONE by the time the
    // breaker is hit and the pitch vote would silently never fire. But the head unit is
    // RIGIDLY MOUNTED, so the device-frame fore-aft direction is effectively constant
    // for the whole drive — once we've seen it confidently we can hold it. So we LATCH
    // the unit direction whenever the live EMA is strongly established (≥ LONG_AXIS_
    // LATCH_MAG) and fall back to the latch when the live EMA has decayed below the
    // usable floor. effLx/effLy/effLz + longAxisAvail are recomputed once per accel
    // sample in update() (single IPC thread, same as the rest of the hot path → no
    // volatile needed) and read by pitchRate/rollRate/pitchAxisReady on the gyro path.
    private var latLx = 0f; private var latLy = 0f; private var latLz = 0f
    private var longAxisLatched = false
    private var effLx = 0f; private var effLy = 0f; private var effLz = 0f
    private var longAxisAvail = false

    /**
     * Longitudinal (brake/accel, fore-aft) component of the most recent sample's
     * horizontal residual (m/s², ≥0): the projection of the gravity-free horizontal
     * acceleration onto the slow longitudinal-axis estimate. Large during a
     * brake/launch transient. The asymmetry stage gates on this so a driver-caused
     * fore-aft jolt cannot read as road one-sidedness.
     */
    var longitudinalResidual: Float = 0f
        private set

    /**
     * Lateral (cross-track, body-roll) component of the most recent sample's
     * horizontal residual (m/s², ≥0): the part orthogonal to the longitudinal-axis
     * estimate. This is the intended one-sidedness signal for asymmetry.
     */
    var lateralResidual: Float = 0f
        private set

    /** Latest gravity magnitude (≈ 9.8 once seeded). Used internally by the
     *  gravity-projection helpers ([alongGravity], [pitchRate]). */
    var gravityMagnitude: Float = 0f
        private set

    /**
     * Fold one accelerometer sample in and return the vertical residual.
     *
     * @return a_vert in m/s²: positive = pushed up (bump crest), negative =
     *         dropped (pothole / dip). Near 0 on flat road.
     */
    fun update(ax: Float, ay: Float, az: Float): Float {
        pitchAxisDirty = true
        if (!seeded) {
            // Seed directly with the first sample so we don't spend the first
            // second ramping up from zero (which would emit a huge bogus a_vert).
            gx = ax; gy = ay; gz = az
            gravityMagnitude = sqrt(gx * gx + gy * gy + gz * gz)
            seeded = true
            return 0f
        }
        gx += gravityAlpha * (ax - gx)
        gy += gravityAlpha * (ay - gy)
        gz += gravityAlpha * (az - gz)

        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        gravityMagnitude = mag
        if (mag < 1e-3f) {
            horizontalResidual = 0f; longitudinalResidual = 0f; lateralResidual = 0f
            return 0f
        } // degenerate; avoid div-by-zero
        // Project the raw sample onto the gravity unit vector, subtract gravity
        // magnitude → the component of acceleration along "up", gravity removed.
        val dot = (ax * gx + ay * gy + az * gz) / mag
        // Horizontal residual = the part of accel orthogonal to ĝ. By Pythagoras
        // on the orthogonal decomposition: |a|² = a_along² + a_perp², where
        // a_along = dot (the full projection onto ĝ, INCLUDING gravity). So
        // a_perp = sqrt(max(0, |a|² − dot²)). This is gravity-free (gravity is
        // entirely along ĝ) and needs no extra state — it reuses `dot`/`mag`.
        val aSq = ax * ax + ay * ay + az * az
        val perpSq = aSq - dot * dot
        horizontalResidual = if (perpSq > 0f) sqrt(perpSq) else 0f

        // ---- Longitudinal / lateral split (audit detection #6) ----------------
        // Build the gravity-free horizontal acceleration VECTOR by subtracting the
        // along-gravity component from the raw sample: a_h = a − (a·ĝ)ĝ.
        val ux = gx / mag; val uy = gy / mag; val uz = gz / mag
        val hx = ax - dot * ux
        val hy = ay - dot * uy
        val hz = az - dot * uz
        // Slow EMA of that horizontal vector → the persistent longitudinal axis.
        hax += longAxisAlpha * (hx - hax)
        hay += longAxisAlpha * (hy - hay)
        haz += longAxisAlpha * (hz - haz)
        val laMag = sqrt(hax * hax + hay * hay + haz * haz)
        if (laMag < 1e-3f) {
            // No established fore-aft bias yet → treat the whole kick as lateral.
            longitudinalResidual = 0f
            lateralResidual = horizontalResidual
        } else {
            val lx = hax / laMag; val ly = hay / laMag; val lz = haz / laMag
            val longComp = hx * lx + hy * ly + hz * lz   // signed projection onto the axis
            longitudinalResidual = if (longComp < 0f) -longComp else longComp
            val latSq = horizontalResidual * horizontalResidual - longComp * longComp
            lateralResidual = if (latSq > 0f) sqrt(latSq) else 0f
        }

        // ---- Latched fore-aft axis for the PITCH/ROLL split (gyro path) -----------
        // (The longitudinal/lateral RESIDUAL split above intentionally keeps using the
        // LIVE EMA — it's about current brake/launch energy. Only the pitch/roll gyro
        // split wants a held axis so it survives coasting up to the breaker.) When the
        // live EMA is strongly established, refresh the latch to the current fore-aft
        // unit direction (the rigid mount makes this constant for the drive, so a stale
        // latch from earlier in the SAME drive is still valid). The EFFECTIVE axis is
        // the live EMA when it's usable, else the latch.
        if (laMag >= LONG_AXIS_LATCH_MAG) {
            latLx = hax / laMag; latLy = hay / laMag; latLz = haz / laMag
            longAxisLatched = true
            effLx = latLx; effLy = latLy; effLz = latLz
            longAxisAvail = true
        } else if (laMag >= LONG_AXIS_MIN_MAG) {
            // Live EMA still usable but not strong enough to (re)latch — use it as-is.
            effLx = hax / laMag; effLy = hay / laMag; effLz = haz / laMag
            longAxisAvail = true
        } else if (longAxisLatched) {
            // Live EMA decayed (coasting) → fall back to the held axis from this drive.
            effLx = latLx; effLy = latLy; effLz = latLz
            longAxisAvail = true
        } else {
            longAxisAvail = false
        }
        return dot - mag
    }

    /**
     * Signed component of the vector (x,y,z) ALONG the measured gravity unit vector
     * (i.e. about/along the true vertical), in the same units as the input.
     * Allocation-free (no array) so it's safe to call on the 100 Hz gyro path — used
     * to isolate the true YAW rate (rotation about vertical) from a bump's pitch/roll
     * transient, which the old largest-axis proxy mis-read as cornering. Falls back to
     * the raw Z component before the gravity estimate has seeded (degenerate magnitude),
     * matching the pre-tilt-correction behaviour during warm-up.
     */
    fun alongGravity(x: Float, y: Float, z: Float): Float {
        val m = gravityMagnitude
        return if (m < 1e-3f) z else (x * gx + y * gy + z * gz) / m
    }

    /**
     * True once the slow longitudinal-axis estimate has enough magnitude to define a
     * fore-aft direction — the precondition for separating PITCH (about the lateral
     * axis) from ROLL (about the longitudinal axis) in [pitchRate]/[rollRate]. The
     * longitudinal axis is the EMA of gravity-free horizontal accel, so it firms up
     * once the car brakes/accelerates (e.g. slowing for a breaker); before that the
     * fore-aft direction is ambiguous and pitch/roll can't be cleanly split. When
     * false, callers should treat the pitch reading as "not measured" (same posture as
     * asymmetryValid), never as a real zero.
     */
    val pitchAxisReady: Boolean
        get() = longAxisAvail

    /**
     * Signed PITCH rate (rad/s) of the gyro vector (x,y,z): its rotation about the
     * LATERAL axis ŝ = ĝ × l̂ (ĝ = measured gravity/vertical unit, l̂ = the EFFECTIVE
     * longitudinal unit — the live EMA when usable, else the latched fore-aft direction
     * held from earlier in the drive so it survives coasting up to the breaker). Pitch
     * is the nose-up→over→down rotation a transverse SPEED BREAKER taken head-on
     * produces, somewhat speed-robust and distinct from the body ROLL a one-sided
     * pothole causes — the signal RoadSense uses to corroborate breaker vs pothole (the
     * user's "up-down gyro movement" ask). Returns 0 when no fore-aft axis is available
     * ([pitchAxisReady] false). Allocation-free (hot 100 Hz gyro path). abs() by caller.
     */
    fun pitchRate(x: Float, y: Float, z: Float): Float {
        val gMag = gravityMagnitude
        if (gMag < 1e-3f || !longAxisAvail) return 0f
        // Gyro callbacks can outnumber accel updates. Recompute the same lateral
        // unit vector only when the gravity or longitudinal estimate has changed.
        if (pitchAxisDirty) {
            val ux = gx / gMag; val uy = gy / gMag; val uz = gz / gMag
            val sx = uy * effLz - uz * effLy
            val sy = uz * effLx - ux * effLz
            val sz = ux * effLy - uy * effLx
            val sMag = sqrt(sx * sx + sy * sy + sz * sz)
            if (sMag < 1e-3f) return 0f
            pitchX = sx / sMag; pitchY = sy / sMag; pitchZ = sz / sMag
            pitchAxisDirty = false
        }
        return x * pitchX + y * pitchY + z * pitchZ
    }

    /**
     * Signed ROLL rate (rad/s) of the gyro vector (x,y,z): its rotation about the
     * EFFECTIVE LONGITUDINAL (fore-aft) axis — the body roll a one-sided pothole/curb
     * hit causes. Returns 0 when no fore-aft axis is available ([pitchAxisReady] false).
     * Allocation-free; caller takes abs() for a peak.
     */
    fun rollRate(x: Float, y: Float, z: Float): Float {
        if (!longAxisAvail) return 0f
        return x * effLx + y * effLy + z * effLz
    }

    fun reset() {
        gx = 0f; gy = 0f; gz = 0f; seeded = false; horizontalResidual = 0f
        gravityMagnitude = 0f
        pitchAxisDirty = true
        hax = 0f; hay = 0f; haz = 0f
        longitudinalResidual = 0f; lateralResidual = 0f
        latLx = 0f; latLy = 0f; latLz = 0f; longAxisLatched = false
        effLx = 0f; effLy = 0f; effLz = 0f; longAxisAvail = false
    }

    companion object {
        /** ~0.5 s time constant at 100 Hz — settles fast, ignores ~150 ms bumps. */
        // ~0.004 ⇒ ~250-sample (~2.5 s at 100 Hz) time constant. The old 0.02
        // (~0.5 s) let a 2-3 s sustained brake/accel get absorbed into the gravity
        // estimate, leaking longitudinal g into a_vert during exactly the
        // braking/launch transients the rejection filter cares about (audit
        // detection #2). Slow enough to reject sustained maneuvers, still fast
        // enough to track hills/remount, and far slower than a ~150 ms bump.
        const val DEFAULT_ALPHA = 0.004f

        /** EMA weight for the longitudinal-axis estimate (the slow horizontal
         *  acceleration vector). ~0.01 ⇒ ~1 s time constant at 100 Hz: fast enough
         *  to settle onto the fore-aft brake/accel direction within a maneuver, slow
         *  enough that a ~180 ms bump transient barely perturbs the axis (so the
         *  bump's kick lands in the lateral remainder, not the longitudinal one). */
        const val LONG_AXIS_ALPHA = 0.01f

        /** Min magnitude (m/s²) of the slow longitudinal-axis EMA before it defines a
         *  trustworthy fore-aft direction for the pitch/roll split ([pitchRate]/
         *  [rollRate]/[pitchAxisReady]). Below this the horizontal accel has been ~nil
         *  (steady cruise, no braking/launch) so the axis is noise; ~0.3 m/s² is well
         *  above sensor jitter yet easily reached by the deceleration before a breaker.
         *  PROVISIONAL. */
        const val LONG_AXIS_MIN_MAG = 0.3f

        /** Min live-EMA magnitude (m/s²) to (re)LATCH the fore-aft direction for the
         *  pitch/roll split. Higher than [LONG_AXIS_MIN_MAG] so we only capture the axis
         *  from a CONFIDENT brake/accel (not marginal drift), then hold it for the drive
         *  (rigid mount ⇒ fore-aft is constant) so the pitch vote survives coasting to
         *  the breaker. ~0.6 m/s² ≈ a light-but-deliberate deceleration. PROVISIONAL. */
        const val LONG_AXIS_LATCH_MAG = 0.6f

        /** Sample period the per-sample constants were tuned at (F-005: 99.9 Hz). */
        const val NOMINAL_PERIOD_MS = 10f

        /** Per-sample EMA weight giving the same time constant at [periodMs] as [alpha] at 100 Hz. */
        fun rescaleAlpha(alpha: Float, periodMs: Float): Float =
            (1.0 - Math.pow(1.0 - alpha, (periodMs / NOMINAL_PERIOD_MS).toDouble())).toFloat()
    }
}
