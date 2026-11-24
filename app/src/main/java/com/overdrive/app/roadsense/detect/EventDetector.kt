package com.overdrive.app.roadsense.detect

import kotlin.math.abs

/**
 * Streaming, sliding-window biphasic-pulse detector — the stage that turns the
 * `a_vert` residual stream from [GravityFrame] into raw [DetectionCandidate]s.
 *
 * ## What it keys on (the hardware truth — see dev/roadsense/05-FINDINGS.md)
 *
 * A real road feature at speed shows up on the vertical channel as a **biphasic
 * pulse**: the suspension loads one way then rebounds the other. F-007 measured a
 * textbook speed bump at ~30 km/h as `−2.0 m/s² (load) → +3.5 m/s² (crest) →
 * rebound`, total ≈ 180 ms, ≈ 18 samples at 100 Hz, peak 3.5 vs a driving noise
 * floor of ±0.18 m/s² stdev (F-005) ⇒ ~19× SNR. So the signal is clean and the
 * morphology is what we extract:
 *
 *   - **peakUp / peakDown** — the two excursions of the biphasic pulse.
 *   - **dipLeading** — down-first (pothole-ish) vs up-first/crest-first
 *     (breaker-ish). F-007 + 03-ARCHITECTURE §Severity: potholes are dip-leading
 *     with a sharper rise (<40 ms); breakers are crest-leading / symmetric.
 *   - **riseTimeMs** — zero-cross → first peak. Pothole ≪ breaker (the sharp-edge
 *     vs ramped-profile distinction the classifier downstream reasons on).
 *   - **durationMs** — total span; a real event is ~150–200 ms (~15–20 samples).
 *   - **axlePairGapMs** — if a second same-shape pulse arrives one wheelbase-time
 *     later (`Δt = wheelbase / v`), it's almost certainly a real transverse road
 *     feature (front axle then rear axle hit the same bump). High-confidence cue.
 *
 * ## Why streaming, not batch
 *
 * This runs inside the app-process foreground service at the sensor rate
 * (99.9 Hz, F-005). R-PERF-1/3: compute and memory are constrained. So:
 *   - one sample in via [onSample], at most one candidate out;
 *   - a **fixed-size primitive ring buffer** (FloatArray + parallel LongArray for
 *     timestamps), NOT a growing collection — no per-sample allocation, no boxing;
 *   - the ONLY allocation on the hot path is the returned [DetectionCandidate]
 *     when an event actually completes (rare — a handful per drive).
 *
 * ## Threading
 *
 * NOT thread-safe — by design. The owning [RoadSenseService] feeds it from a
 * single sensor-callback thread (same contract as [GravityFrame]). Do not call
 * [onSample] concurrently.
 *
 * ## What this stage does NOT do
 *
 * It does not reject (turns/braking/washboard — that's [RejectionFilter]), does
 * not classify severity/type, and does not localize. It is the dumb morphology
 * extractor. It DOES derive [DetectionCandidate.lateralAsymmetry] from the
 * gravity-free horizontal residual ([VerticalSample.aHoriz], supplied by
 * [GravityFrame.horizontalResidual]) paired with the vertical peak — a one-wheel
 * pothole rolls the body (horizontal-dominant) where a full-width breaker stays
 * vertical (R-DET-3). When the vertical peak is too weak to trust the ratio,
 * `asymmetryValid` is left false so downstream gates ignore it.
 */
class EventDetector(
    /**
     * Sliding-window length in milliseconds. Must comfortably contain the
     * longest event we want to characterize. F-007's bump was ~180 ms; we want
     * headroom to capture both lobes of the biphasic pulse plus a little lead-in,
     * so the default is ~400 ms. At 100 Hz that's 40 samples — tiny.
     */
    windowMs: Int = DEFAULT_WINDOW_MS,
    /**
     * Nominal sample rate (Hz) used only to size the ring buffer. The detector's
     * timing math uses real sample timestamps ([VerticalSample.tMs]), so a small
     * error here only affects buffer capacity, never measured durations. F-005:
     * real rate is 99.9 Hz; 100 is the right nominal.
     */
    sampleRateHz: Int = NOMINAL_SAMPLE_RATE_HZ,
    /**
     * Speed-aware threshold base (m/s²). The detection threshold on |a_vert| is
     * `thresholdBase + thresholdSpeedSlope * speedKmh`. Base is set well above
     * the ±0.18 m/s² noise floor (F-005) so road texture never trips us; the
     * slope accounts for the same bump hitting harder at speed (F-007 was a
     * single-speed capture — point 4 of the findings' detector implications).
     * Provisional per the spec: 1.0 + 0.05·speedKmh.
     */
    private val thresholdBase: Float = DEFAULT_THRESHOLD_BASE,
    private val thresholdSpeedSlope: Float = DEFAULT_THRESHOLD_SPEED_SLOPE,
    /**
     * Minimum speed (km/h) to arm detection at all. Below this we gate OFF and
     * return null: at walking pace the vertical channel is dominated by
     * parking-lot manoeuvring, door slams, people getting in — not road hazards —
     * and a bump that slow carries no useful severity signal anyway.
     */
    private val minSpeedKmh: Float = DEFAULT_MIN_SPEED_KMH,
    /**
     * Assumed wheelbase (m) for the axle-pair check. BYD Seal ≈ 2.9 m; we use a
     * conservative ~2.7 m so the acceptance window straddles typical sedans. The
     * front and rear axle hit the same transverse feature `Δt = wheelbase / v`
     * apart; seeing that second matched pulse is a strong "real road feature" cue.
     */
    private val wheelbaseM: Float = DEFAULT_WHEELBASE_M,
    /**
     * Fractional tolerance on the axle-pair gap. We accept a second pulse whose
     * arrival gap is within ±this fraction of the predicted `wheelbase / v`.
     * ±30% absorbs wheelbase uncertainty, speed change across the feature, and
     * timing jitter without matching unrelated bumps.
     */
    private val axlePairTolerance: Float = DEFAULT_AXLE_PAIR_TOLERANCE,
    /**
     * Seed for the live detection-sensitivity multiplier on the speed-aware open
     * threshold (the user-facing "Detection Sensitivity" slider). 1.0 = the data-fit
     * default thresholds unchanged; <1 lowers the bar (MORE sensitive — catches
     * gentler features at the cost of more false positives); >1 raises it (LESS
     * sensitive). The live value is re-pushed at runtime via [setThresholdScale]
     * from RoadSenseController's ~2 Hz vehicle poll when the config changes, so this
     * constructor arg is only the initial value.
     */
    initialThresholdScale: Float = DEFAULT_THRESHOLD_SCALE,
) {
    /**
     * Live-tunable detection-sensitivity multiplier applied to the speed-aware open
     * threshold: `threshold = (base + slope·speedKmh) * thresholdScale`. @Volatile
     * because it is WRITTEN by the ~2 Hz vehicle-poll thread (via [setThresholdScale]
     * when [com.overdrive.app.roadsense.config.RoadSenseConfig] changes) and READ on
     * the 100 Hz IPC/sensor thread inside [onSample]. A single-field volatile
     * hand-off that NEVER touches the detector's stateful ring buffers / event
     * accumulators / debounce, so changing it mid-drive is safe and loses no in-flight
     * event (cf. rebuilding the detector, which would). Clamped to the supported band.
     */
    @Volatile private var thresholdScale: Float =
        initialThresholdScale.coerceIn(MIN_THRESHOLD_SCALE, MAX_THRESHOLD_SCALE)

    // Sample-count constants in effect; the 100 Hz values unless [setSamplePeriodMs] adapted them.
    // Written and read on the single IPC/sensor thread.
    private var quietSamples = QUIET_SAMPLES
    private var rearmQuietSamples = REARM_QUIET_SAMPLES
    private var sampleGapMs = GAP_MS
    private var riseResolvable = true

    // ---- Ring buffer (fixed-size, no allocation after construction) ----------
    // Parallel arrays: aVert[i] is the residual, tMs[i] its timestamp. We keep
    // raw samples so a completed event can report exact peaks and timings; the
    // window is small (tens of samples) so this is cheap.
    private val capacity: Int = maxOf(8, (windowMs.toLong() * sampleRateHz / 1000L).toInt())
    private val aVertBuf = FloatArray(capacity)
    private val tMsBuf = LongArray(capacity)
    private var head = 0          // index where the NEXT sample will be written
    private var filled = 0        // number of valid samples (<= capacity)

    // ---- Event-accumulation state (the "in an event" sliding window) ---------
    // We are "active" from the moment |a_vert| first crosses the threshold until
    // the signal settles back into the noise band for QUIET_SAMPLES in a row.
    private var active = false
    private var evtStartMs = 0L          // timestamp of the first supra-threshold sample
    private var evtPeakUp = 0f           // max a_vert seen during the event
    private var evtPeakDown = 0f         // min a_vert seen during the event
    private var evtPeakUpMs = 0L         // when peakUp occurred
    private var evtPeakDownMs = 0L       // when peakDown occurred
    private var evtFirstSign = 0         // +1 if the event opened upward, -1 if downward, 0 unset
    private var evtZeroCrossMs = 0L      // timestamp of the threshold crossing that started the event
    private var quietRun = 0             // consecutive in-noise-band samples while active
    private var evtSpeedMps = 0f         // speed at the defining peak (carried into the candidate)
    private var evtPeakHoriz = 0f        // peak gravity-free LATERAL residual over the event (m/s²)
    private var evtPeakLong = 0f         // peak gravity-free LONGITUDINAL residual over the event (m/s²)

    // ---- Intra-event dual-crest (axle-pair-WITHIN-one-event) tracking ----------
    // At low/typical breaker speeds the front and rear axles hit the same transverse
    // feature Δt = wheelbase/v apart (~650 ms @15 km/h, ~970 ms @10 km/h), and the
    // suspension rings continuously across that span so the signal never settles for
    // QUIET_SAMPLES between them — both axles land in ONE event. The inter-event pair
    // check (lastEmit*) therefore never fires (it needs two SEPARATE emitted candidates),
    // which is why the strongest geometric BREAKER cue was dead at exactly the speeds
    // people take breakers. This streaming crest counter recovers it WITHIN the single
    // merged event: it marks each distinct upward crest (front-axle crest, then rear-
    // axle crest) and, at close, pairs them if their spacing matches wheelbase/v. Purely
    // additive — a one-crest pothole/bump yields a single crest so nothing is set; the
    // geometric gap gate is the real guard against a false pair. O(1)/sample, no alloc.
    private var evtCrestOpen = false     // currently above the crest gate (in a crest)
    private var evtCrestPeakMag = 0f     // running peak a_vert of the in-progress crest
    private var evtCrestPeakMs = 0L      // timestamp of that in-progress crest's peak
    private var evtFirstCrestMs = 0L     // committed first crest peak time (0 = none yet)
    private var evtLastCrestMs = 0L      // committed most-recent crest peak time
    private var evtCrestCount = 0        // number of distinct committed crests

    // ---- Debounce / axle-pair memory -----------------------------------------
    // After emitting we must not re-emit the same physical event. We require the
    // signal to fall back below the noise floor and stay quiet for a gap before
    // arming again (R-DET: "MUST NOT emit two candidates for the same event").
    private var rearmQuietRun = 0
    private var armed = true

    // Memory of the just-emitted pulse so we can recognise the SECOND axle's
    // matching pulse and tag axlePairGapMs on it. Null when there's nothing to
    // pair against (or it has aged out of the plausible window).
    private var lastEmitPeakMs = 0L
    private var lastEmitDipLeading = false
    private var lastEmitValid = false

    // ---- Diagnostics ----------------------------------------------------------
    // Last sample timestamp seen, for the monotonic / gap guard. NOT cleared by the
    // in-stream guard (it sets the new baseline); cleared by full reset().
    private var lastTMs = 0L
    private var candidatesEmitted = 0L

    /** Total candidates emitted. Diagnostics only. */
    val totalCandidates: Long get() = candidatesEmitted

    /** True while accumulating a (not-yet-completed) event. Diagnostics only. */
    val inEvent: Boolean get() = active

    /**
     * Feed one vertical sample. Returns a [DetectionCandidate] at the instant an
     * event completes (signal has settled after a supra-threshold excursion),
     * otherwise null.
     *
     * Hot path: no allocation except the returned candidate on a completing event.
     */
    fun onSample(s: VerticalSample): DetectionCandidate? {
        // Time-jump / sensor-gap guard (audit detection #3): a non-monotonic or
        // large-gap timestamp makes durations go negative (MAX_EVENT_MS never trips,
        // event hangs) and produces garbage candidate timings. On a backward jump or
        // a gap, abandon the in-flight event/window so this sample starts clean.
        if (lastTMs != 0L && (s.tMs < lastTMs || s.tMs - lastTMs > sampleGapMs)) {
            resetEvent()
            armed = true
            rearmQuietRun = 0
            lastEmitValid = false
            head = 0; filled = 0
        }
        lastTMs = s.tMs

        // Always keep the ring buffer current so timings/peaks are exact, even
        // for samples we end up gating out — cheap, and keeps the buffer coherent.
        aVertBuf[head] = s.aVert
        tMsBuf[head] = s.tMs
        head = (head + 1) % capacity
        if (filled < capacity) filled++

        val speedKmh = s.speedMps * MPS_TO_KMH

        // Near-stationary guard (NOT a hazard speed floor). Session-34 directive + data:
        // the biggest speedbreakers/potholes are crossed SLOWLY, so a real speed FLOOR
        // drops exactly the hazards that matter most. Proven on-device: 12 confirmed-real
        // hazards in the recorded drive had raw-window speed <6 km/h (several at 0–2 km/h),
        // yet the OLD minSpeedKmh=6 gate would have discarded them. So minSpeedKmh is now a
        // tiny ~1 km/h NUMERICAL guard only — it keeps the speed-aware threshold defined and
        // skips a dead-stop idle vibration (bus speed ~0), nothing more. The actual
        // parking-lot/reverse/door-slam artefacts the old floor incidentally caught are
        // rejected by CONTEXT in RejectionFilter (Rule 4 not_forward_gear) + the
        // speed-aware threshold's own noise headroom, not by a speed cap. (Sub-1 km/h
        // behaviour is physics-reasoned; a fresh marked crawl drive should re-confirm.)
        if (speedKmh < minSpeedKmh) {
            if (active) resetEvent()
            // Let the re-arm / pair memory keep ageing so we don't get stuck
            // disarmed; treat a near-stop as quiet.
            ageRearmAndPairMemory(s.tMs)
            return null
        }

        // Speed-aware open threshold, scaled by the live detection-sensitivity knob.
        // Read the volatile once per sample so a mid-event config change can't shift
        // the bar between the magnitude test below and any later use this sample.
        val threshold = (thresholdBase + thresholdSpeedSlope * speedKmh) * thresholdScale

        val mag = abs(s.aVert)
        val settled = mag < (NOISE_FLOOR + NOISE_BAND_MARGIN)

        // ---- Re-arm logic ----------------------------------------------------
        // After an emit we sit disarmed until the signal returns to the noise
        // band for REARM_QUIET_SAMPLES consecutive samples. This is the debounce
        // that stops one physical bump (which rings/rebounds) emitting twice.
        if (!armed) {
            if (settled) {
                rearmQuietRun++
                if (rearmQuietRun >= rearmQuietSamples) {
                    armed = true
                    rearmQuietRun = 0
                }
            } else {
                rearmQuietRun = 0
            }
            // While disarmed we don't open new events, but we DO keep the
            // pair-memory alive so a genuine rear-axle pulse can still be matched
            // even though we suppress re-emitting. (We simply don't emit it; the
            // first-axle candidate already carries axlePairGapMs once we see it.)
            ageRearmAndPairMemory(s.tMs)
            return null
        }

        ageRearmAndPairMemory(s.tMs)

        if (!active) {
            // ---- Idle: look for the opening threshold crossing ----------------
            if (mag >= threshold) {
                active = true
                evtStartMs = s.tMs
                evtZeroCrossMs = s.tMs
                evtPeakUp = s.aVert
                evtPeakDown = s.aVert
                evtPeakUpMs = s.tMs
                evtPeakDownMs = s.tMs
                evtFirstSign = if (s.aVert >= 0f) +1 else -1
                evtSpeedMps = s.speedMps
                evtPeakHoriz = s.aHoriz
                evtPeakLong = s.aLong
                quietRun = 0
                trackCrest(s.aVert, s.tMs)
            }
            return null
        }

        // ---- Active: accumulate morphology until the event settles -----------
        if (s.aVert > evtPeakUp) { evtPeakUp = s.aVert; evtPeakUpMs = s.tMs }
        if (s.aVert < evtPeakDown) { evtPeakDown = s.aVert; evtPeakDownMs = s.tMs }
        if (s.aHoriz > evtPeakHoriz) evtPeakHoriz = s.aHoriz
        if (s.aLong > evtPeakLong) evtPeakLong = s.aLong
        trackCrest(s.aVert, s.tMs)

        if (settled) {
            quietRun++
            if (quietRun >= quietSamples) {
                // Event complete. Build the candidate, reset, possibly emit.
                return finishEvent(s.tMs)
            }
        } else {
            quietRun = 0
            // Guard against a pathological never-settling stretch (rough road /
            // washboard): cap the event span. Rejection handles washboard later,
            // but we must not accumulate unboundedly.
            if (s.tMs - evtStartMs > MAX_EVENT_MS) {
                return finishEvent(s.tMs)
            }
        }
        return null
    }

    /**
     * Close out the active event, compute the candidate, update debounce/pair
     * state, and return the candidate (or null if it degenerates to nothing).
     */
    private fun finishEvent(nowMs: Long): DetectionCandidate? {
        // The "defining peak" is the larger-magnitude excursion of the biphasic
        // pulse — that's the timestamp we localize on and the durationMs anchor.
        val upMag = abs(evtPeakUp)
        val downMag = abs(evtPeakDown)
        val peakMs: Long
        val definingPeakMs: Long
        if (downMag >= upMag) {
            peakMs = evtPeakDownMs
            definingPeakMs = evtPeakDownMs
        } else {
            peakMs = evtPeakUpMs
            definingPeakMs = evtPeakUpMs
        }

        // dipLeading: which excursion happened FIRST in time (temporal order), falling
        // back to the opening-sample sign on an exact tie. Down-first ⇒ pothole-ish.
        //
        // DATA NOTE (Session 32 — reverted a Session-31 change): a dominant-lobe rule was
        // tried (classify by which excursion is LARGER, motivated by F-007's single
        // load→crest breaker sample). The 97-label on-device set REFUTED it. The honest
        // read accounts for circularity — ~79/97 labels were AUTO-ACCEPTED (so they echo
        // the OLD temporal rule and can't fairly judge a new rule), but on the ~12 rows
        // the user EXPLICITLY corrected (the only non-circular type evidence) the temporal
        // rule committed 10/12 correctly vs the dominant-lobe rule's 8/12 (two strong-
        // rebound breakers fell to UNKNOWN). Real breakers at the median 17 km/h register
        // crest-first more often than not, so temporal order is the better cue; F-007 was
        // one unrepresentative 30 km/h capture. Rise-time is the LEAD type vote now
        // (SeverityClassifier RISE_TIME_VOTE=1.2); dipLeading is a supporting vote.
        val dipLeading: Boolean = when {
            evtPeakDownMs < evtPeakUpMs -> true
            evtPeakUpMs < evtPeakDownMs -> false
            else -> evtFirstSign < 0
        }

        // riseTimeMs: zero-cross (event open) → the FIRST (leading) lobe's peak — NOT the
        // larger/defining lobe (audit detection #9). For a dip-leading pothole the rebound
        // crest is often the larger lobe, so measuring to the defining peak would time to
        // the SECOND lobe (a long interval) and mis-vote it a slow-rise BREAKER. Leading-
        // edge sharpness is the time to the FIRST lobe — and this first-lobe definition is
        // exactly what made rise_ms the strongest type discriminator in the 97-label fit
        // (potholes 38/38 < 50 ms, median 8.5; breakers median 83 ms), so it MUST stay
        // first-lobe. The larger (defining) peak still anchors localization/durationMs.
        val firstLobeMs = if (evtPeakDownMs <= evtPeakUpMs) evtPeakDownMs else evtPeakUpMs
        val riseTimeMs = (firstLobeMs - evtZeroCrossMs).toInt().coerceAtLeast(0)

        // durationMs: open → settle. Real events ~150–200 ms (F-007).
        val durationMs = (nowMs - evtStartMs).toInt().coerceAtLeast(0)

        // Sanity: a real biphasic pulse must clear the noise band on BOTH lobes
        // meaningfully, or at least have a strong single excursion. If neither
        // peak is appreciable the "event" was a borderline blip — drop it.
        val strongest = maxOf(upMag, downMag)
        if (strongest < NOISE_FLOOR + NOISE_BAND_MARGIN) {
            resetEvent()
            return null
        }

        // Commit whatever crest is still in progress at close so a merged event's
        // SECOND (rear-axle) crest is counted before the intra-event pair test below.
        commitCrest()

        // ---- Axle-pair check -------------------------------------------------
        // Two independent ways to see the front-then-rear axle hitting one transverse
        // feature, both gated on the geometric Δt ≈ wheelbase / v (±axlePairTolerance):
        //   (A) INTRA-event: at breaker speeds both axles ring into ONE un-settled
        //       event, so we count distinct crests within it (trackCrest) and pair the
        //       first↔last crest here. This is the path that actually fires at the ~10–
        //       20 km/h speeds people take breakers — the inter-event path (B) below
        //       cannot, because the two axles never produce two separate candidates.
        //   (B) INTER-event: if the axles DID settle into two separate same-shape
        //       candidates (higher speed / sharper road), pair this one against the
        //       previously-emitted pulse.
        // Either match tags axlePairGapMs ⇒ SeverityClassifier's strong full-width
        // BREAKER vote. A one-crest pothole/bump never sets it (one crest in (A); shape
        // mismatch or no prior emit in (B)).
        var axlePairGapMs: Int? = null
        if (evtSpeedMps > 0.1f) {
            val expectedMs = (wheelbaseM / evtSpeedMps) * 1000f
            val low = expectedMs * (1f - axlePairTolerance)
            val high = expectedMs * (1f + axlePairTolerance)
            // (A) intra-event dual crest
            if (evtCrestCount >= 2) {
                val gapMs = evtLastCrestMs - evtFirstCrestMs
                if (gapMs in low.toLong()..high.toLong()) axlePairGapMs = gapMs.toInt()
            }
            // (B) inter-event pair (only if intra didn't already establish it)
            if (axlePairGapMs == null && lastEmitValid && lastEmitDipLeading == dipLeading) {
                val gapMs = peakMs - lastEmitPeakMs
                if (gapMs > 0 && gapMs >= low && gapMs <= high) axlePairGapMs = gapMs.toInt()
            }
        }

        // ---- Lateral asymmetry from the horizontal channel -------------------
        // A one-sided hit (single-wheel pothole, curb scuff) loads one corner of
        // the car and rolls the body → a large LATERAL kick relative to the
        // vertical jolt. A full-width breaker hits both wheels symmetrically →
        // energy stays mostly vertical, little lateral. So the ratio
        // latPeak / (latPeak + vertPeak) is a 0..1 "one-sidedness" (evtPeakHoriz now
        // carries only the LATERAL residual; the longitudinal part is split out):
        //   ~0  = vertical-dominant → symmetric / full-width (breaker)
        //   ~1  = lateral-dominant  → one-sided (pothole / curb)
        // We only mark it VALID when the vertical peak clears the noise band, so a
        // borderline blip doesn't produce a garbage ratio (consumers gate on
        // asymmetryValid — see RejectionFilter Rule 6 / SeverityClassifier).
        val vertPeakMag = maxOf(abs(evtPeakUp), abs(evtPeakDown))
        val denom = vertPeakMag + evtPeakHoriz
        // Longitudinal-dominance gate (audit detection #6): evtPeakHoriz is now the
        // LATERAL residual only, but a brake/launch transient that slipped past the
        // rejection filter (stale ~5 s pedal poll, F-011) still leaks some fore-aft
        // energy into the lateral remainder. If the LONGITUDINAL peak dominates the
        // horizontal kick, the one-sidedness reading is driver-caused, not road —
        // so we refuse to validate the asymmetry rather than feed a fake one-sided
        // vote / curb reject.
        val longDominates = evtPeakLong > evtPeakHoriz * LONG_DOMINANCE_RATIO
        val asymmetry: Float
        val asymmetryValid: Boolean
        if (vertPeakMag >= NOISE_FLOOR + NOISE_BAND_MARGIN && denom > 1e-3f && !longDominates) {
            asymmetry = (evtPeakHoriz / denom).coerceIn(0f, 1f)
            asymmetryValid = true
        } else {
            asymmetry = 0f
            asymmetryValid = false
        }

        val candidate = DetectionCandidate(
            tMs = peakMs,
            peakUp = evtPeakUp,
            peakDown = evtPeakDown,
            riseTimeMs = riseTimeMs,
            durationMs = durationMs,
            dipLeading = dipLeading,
            speedMps = evtSpeedMps,
            axlePairGapMs = axlePairGapMs,
            lateralAsymmetry = asymmetry,
            asymmetryValid = asymmetryValid,
            riseResolved = riseResolvable,
        )

        // Remember this pulse so the NEXT event (the rear axle) can pair against
        // it. Then disarm for debounce: require the signal to go quiet before we
        // open another event, so this physical bump's ringing doesn't re-fire.
        lastEmitPeakMs = peakMs
        lastEmitDipLeading = dipLeading
        lastEmitValid = true

        resetEvent()
        armed = false
        rearmQuietRun = 0
        candidatesEmitted++
        return candidate
    }

    /** Clear the in-event accumulator (does NOT touch debounce/pair memory). */
    private fun resetEvent() {
        active = false
        evtPeakUp = 0f
        evtPeakDown = 0f
        evtPeakHoriz = 0f
        evtPeakLong = 0f
        evtFirstSign = 0
        quietRun = 0
        evtCrestOpen = false
        evtCrestPeakMag = 0f
        evtCrestPeakMs = 0L
        evtFirstCrestMs = 0L
        evtLastCrestMs = 0L
        evtCrestCount = 0
    }

    /**
     * Streaming detector of distinct UPWARD crests within the active event, used to
     * recover the axle-pair (front-then-rear) signature when both axles merge into one
     * un-settled event at breaker speeds (the case the inter-event pair check misses).
     *
     * A "crest" opens when a_vert rises above [CREST_GATE] and closes when it drops back
     * below it; while open we hold the peak. On close we commit the crest (record its
     * peak time, bump the count). A separation gate ([CREST_MIN_SEPARATION_MS]) prevents
     * a single ringing crest's minor wobble from counting twice. A lone pothole/bump has
     * exactly one crest ⇒ count stays 1 ⇒ no intra-event pair, so this can never
     * fabricate a breaker; only a genuine two-axle hit (or two real bumps) reaches 2,
     * and finishEvent still gates the pair on the wheelbase/v geometry. Allocation-free.
     */
    private fun trackCrest(aVert: Float, tMs: Long) {
        if (aVert >= CREST_GATE) {
            // In a crest — track its running peak.
            if (!evtCrestOpen) { evtCrestOpen = true; evtCrestPeakMag = aVert; evtCrestPeakMs = tMs }
            else if (aVert > evtCrestPeakMag) { evtCrestPeakMag = aVert; evtCrestPeakMs = tMs }
        } else if (evtCrestOpen) {
            // Fell back below the gate → the crest just ended; commit it.
            commitCrest()
        }
    }

    /** Commit the in-progress crest (if any) as a distinct crest, honoring the minimum
     *  separation so one crest's jitter isn't double-counted. Idempotent when no crest
     *  is open. Called per-sample on crest-end and once at finishEvent for the trailing
     *  crest that hasn't fallen back below the gate yet. */
    private fun commitCrest() {
        if (!evtCrestOpen) return
        evtCrestOpen = false
        if (evtCrestCount == 0) {
            evtFirstCrestMs = evtCrestPeakMs
            evtLastCrestMs = evtCrestPeakMs
            evtCrestCount = 1
        } else if (evtCrestPeakMs - evtLastCrestMs >= CREST_MIN_SEPARATION_MS) {
            evtLastCrestMs = evtCrestPeakMs
            evtCrestCount++
        }
        evtCrestPeakMag = 0f
    }

    /**
     * Age out the axle-pair memory once it's too old to plausibly be the same
     * road feature's other axle (slowest credible speed ⇒ longest gap). Keeps the
     * pair check from matching an unrelated bump much later.
     */
    private fun ageRearmAndPairMemory(nowMs: Long) {
        if (lastEmitValid && nowMs - lastEmitPeakMs > MAX_AXLE_PAIR_GAP_MS) {
            lastEmitValid = false
        }
    }

    /**
     * Push a new live detection-sensitivity multiplier (the user's slider). Safe to
     * call from another thread at any time — it only assigns the @Volatile
     * [thresholdScale], never touching the stateful detector machinery, so an
     * in-flight event is unaffected and the new bar takes effect on the next sample.
     * Clamped to the supported band so a bad config value can't invert the threshold.
     * Idempotent. Returns the value actually applied (post-clamp).
     */
    fun setThresholdScale(scale: Float): Float {
        val clamped = scale.coerceIn(MIN_THRESHOLD_SCALE, MAX_THRESHOLD_SCALE)
        thresholdScale = clamped
        return clamped
    }

    /** Current live detection-sensitivity multiplier (diagnostics / change-detection). */
    val currentThresholdScale: Float get() = thresholdScale

    /** Open threshold on |a_vert| at [speedKmh] with the live sensitivity applied (diagnostics). */
    fun thresholdAt(speedKmh: Float): Float =
        (thresholdBase + thresholdSpeedSlope * speedKmh) * thresholdScale

    /**
     * Re-derive the sample-count constants for a stream that isn't ~100 Hz, keeping their
     * time spans; null restores the 100 Hz tuning. Call on the sample thread.
     */
    fun setSamplePeriodMs(periodMs: Float?) {
        if (periodMs == null) {
            quietSamples = QUIET_SAMPLES
            rearmQuietSamples = REARM_QUIET_SAMPLES
            sampleGapMs = GAP_MS
            riseResolvable = true
            return
        }
        val perNominal = NOMINAL_PERIOD_MS / periodMs
        quietSamples = maxOf(MIN_ADAPTED_QUIET_SAMPLES, Math.round(QUIET_SAMPLES * perNominal))
        rearmQuietSamples = maxOf(quietSamples, Math.round(REARM_QUIET_SAMPLES * perNominal))
        sampleGapMs = maxOf(GAP_MS, (periodMs * GAP_PERIODS).toLong())
        riseResolvable = periodMs <= MAX_RISE_RESOLVABLE_PERIOD_MS
    }

    val profileSummary: String
        get() = "quiet=$quietSamples rearm=$rearmQuietSamples gap=${sampleGapMs}ms " +
            "riseVote=${if (riseResolvable) "on" else "off"}"

    /** Drop all state — call on a sensor restart / large time gap. */
    fun reset() {
        head = 0; filled = 0
        resetEvent()
        armed = true
        rearmQuietRun = 0
        lastEmitValid = false
        lastEmitPeakMs = 0L
        lastEmitDipLeading = false
        candidatesEmitted = 0L
        lastTMs = 0L
    }

    companion object {
        /** m/s → km/h. */
        const val MPS_TO_KMH = 3.6f

        /** ~400 ms sliding window: comfortably contains the ~180 ms biphasic pulse (F-007) with lead-in. */
        const val DEFAULT_WINDOW_MS = 400

        /** F-005: real `-iner` accel runs at 99.9 Hz; 100 is the right nominal for buffer sizing. */
        const val NOMINAL_SAMPLE_RATE_HZ = 100

        /**
         * Speed-aware open threshold on |a_vert|: `base + slope·speedKmh` (m/s²).
         *
         * DATA-FIT (Session 32, 97 on-device labels — the RECALL fix for "genuine
         * breakers, no detection"): the OLD base 1.0 + slope 0.05 was the recall killer.
         * It made the threshold RISE with speed (1.5 @10 → 2.5 @30 → 3.5 @50 km/h), but
         * that is BACKWARDS — a bump's vertical jolt GROWS with speed, so the bar was
         * highest exactly where it should be lowest. Evidence: the weakest breaker we ever
         * captured was 1.55 m/s², and 6/16 detected breakers sat within 1.5× of the old
         * threshold (38/97 of ALL detections were within 0.5 of their threshold — right at
         * the cliff). Any genuine-but-gentle breaker below ~1.75 at 15–30 km/h fell under
         * the bar → no event, no row, the exact "slowed for a real breaker, nothing mapped"
         * symptom. FIX: base 1.0→0.8, slope 0.05→0.015 (near-flat — a tiny rise still tempers
         * highway texture at 80+ km/h without re-introducing the low-speed recall cliff). New
         * thresholds: 0.95 @10, 1.10 @20, 1.25 @30 km/h — still 2.4–3.1× the 0.40 settle band
         * and ≥5σ above the 0.18 noise floor, so false-positive headroom is preserved
         * (sustained texture/washboard is caught separately by the rate-based RejectionFilter).
         * PROVISIONAL — re-fit on a labeled drive with a raw-IMU recorder (recall is otherwise
         * unmeasurable: a missed bump leaves no row).
         */
        const val DEFAULT_THRESHOLD_BASE = 0.8f
        const val DEFAULT_THRESHOLD_SPEED_SLOPE = 0.015f

        /**
         * Detection-sensitivity multiplier band for the user-facing slider, applied to
         * the speed-aware open threshold (`(base + slope·v) * scale`).
         *
         * 1.0 = the data-fit default (Session 32) unchanged — the out-of-box behaviour.
         * The band is deliberately TIGHT (±30%): the base/slope were fit against the
         * only trustworthy on-device labels we have, and recall is otherwise
         * unmeasurable (a missed bump leaves no row), so a wide-open slider could
         * silently destroy precision (FP storm at the low end) or recall (missed
         * hazards at the high end) with no feedback. ±30% lets a user nudge toward
         * "catch more / nag less" while staying within the fit's headroom (the noise
         * floor + settle band still clear the scaled threshold across the band, since
         * the lowest scaled bar 0.7·0.95≈0.67 @10 km/h is still well above the 0.40
         * settle band). [MIN_THRESHOLD_SCALE] is the MOST sensitive end (lowest bar).
         */
        const val MIN_THRESHOLD_SCALE = 0.7f
        const val MAX_THRESHOLD_SCALE = 1.3f
        const val DEFAULT_THRESHOLD_SCALE = 1.0f

        /**
         * Near-stationary NUMERICAL guard — NOT a hazard speed floor (Session 34, per the
         * user's no-speed-cap directive). History: 10 km/h (orig) → 6 (Session 32, still a
         * floor) → 1.0 (now, effectively removed). On-device proof it had to go: 12
         * confirmed-real hazards in the recorded drive had raw-window speed <6 km/h (several
         * 0–2), exactly the "slow right down to cross a big breaker/pothole" case — the floor
         * was discarding the most important hazards. At 1 km/h this only skips a dead-stop
         * idle vibration and keeps the threshold defined; the parking-lot/reverse artefacts
         * the floor used to mask are caught by RejectionFilter Rule 4 (not_forward_gear, a
         * CONTEXT gate) instead. The speed-norm clamp (SeverityClassifier MIN_NORM_SPEED_MPS
         * =2.0) keeps crawl-severity sane (flat-clamped, no blow-up). Sub-1 km/h is physics-
         * reasoned (the data is censored at 6); re-confirm on a fresh marked crawl drive. */
        const val DEFAULT_MIN_SPEED_KMH = 1.0f

        /** Assumed wheelbase (m) for the axle-pair check. Conservative ~2.7 m so
         *  the gap window straddles typical sedans (BYD Seal ≈ 2.9 m). */
        const val DEFAULT_WHEELBASE_M = 2.7f

        /** ±fraction tolerance on the predicted axle-pair gap (wheelbase / v). */
        const val DEFAULT_AXLE_PAIR_TOLERANCE = 0.30f

        /** Driving vertical-residual noise floor, stdev (F-005). */
        const val NOISE_FLOOR = 0.18f

        /**
         * Extra margin above the noise floor that defines the "settled" band. The
         * signal must drop below `NOISE_FLOOR + margin` to count as quiet. A
         * couple of sigma keeps texture jitter from looking like an ongoing event.
         */
        const val NOISE_BAND_MARGIN = 0.22f // → settle band ≈ 0.40 m/s² (~2.2σ)

        /**
         * Consecutive in-band samples that close an active event. 6 @ 100 Hz ≈
         * 60 ms of quiet — long enough that the biphasic pulse has truly ended,
         * short enough not to swallow a fast-following second axle into one event.
         */
        const val QUIET_SAMPLES = 6

        /**
         * Consecutive in-band samples required to RE-ARM after an emit (debounce).
         * 8 @ 100 Hz ≈ 80 ms. Must be ≥ QUIET_SAMPLES; the post-bump rebound ring
         * has to fully die before we'll open a new event for a new feature.
         */
        const val REARM_QUIET_SAMPLES = 8

        /**
         * Hard cap on a single event's span. A SINGLE bump is ~180 ms, but a transverse
         * speed breaker rings continuously across BOTH axles for Δt = wheelbase/v before
         * settling — ~560 ms @17 km/h, ~970 ms @10 km/h. The old 600 ms cap force-closed
         * the event mid-ring at exactly those speeds, truncating the rear-axle crest so
         * the dual-crest axle-pair signature (the strong full-width BREAKER cue) could
         * never form — the documented 0/53-axle-pair / 34%-hit-cap failure. Sized so the
         * slowest credible axle pair (≤ MAX_AXLE_PAIR_GAP_MS) plus its trailing crest fits
         * in one event. Bumped 1300→1500 to stay consistent with the Session-32 recall fix
         * that lowered DEFAULT_MIN_SPEED_KMH to 6 (axle gap ~1215 ms @8 km/h, ~1620 @6) —
         * 1500 covers the common careful-crossing band down to ~8 km/h with margin. This
         * bounds the accumulator generously, not unboundedly; sustained washboard is still
         * caught downstream by the rate-based RejectionFilter washboard rule, and the
         * dominant-lobe peaks stay well-defined over the longer span. PROVISIONAL.
         */
        const val MAX_EVENT_MS = 1500

        /**
         * a_vert threshold (m/s²) that defines an upward CREST for intra-event axle-pair
         * counting (trackCrest). Set above the settle band (NOISE_FLOOR+NOISE_BAND_MARGIN
         * ≈ 0.40) so suspension ring between the two axle crests dips below it and the
         * crests register as distinct, but well below a real crest peak (F-007 crest
         * ~3.5). PROVISIONAL — tune with raw IMU. */
        const val CREST_GATE = 0.6f

        /** Minimum spacing (ms) between two committed crests, so a single crest's jitter
         *  around CREST_GATE can't double-count. ~120 ms is shorter than the closest
         *  credible axle pair (~560 ms @17 km/h) yet longer than within-crest wobble. */
        const val CREST_MIN_SEPARATION_MS = 120L

        /**
         * Oldest axle-pair gap we'll still match. `Δt = wheelbase / v`; ~2.7 m wheelbase.
         * Bumped 1200→1300 alongside the Session-32 recall fix (DEFAULT_MIN_SPEED_KMH 10→6):
         * the axle gap is ~1215 ms @8 km/h, so 1300 covers the careful-crossing band down to
         * ~8 km/h. We deliberately do NOT extend to the full 6 km/h gap (~1620 ms) — beyond
         * ~1.3 s a "second axle" is increasingly likely to be an unrelated feature, and the
         * axle-pair is only a BONUS breaker cue; at the very slowest crawl the rise-time +
         * dip-leading votes still classify the (single/merged) event. Kept ≤ MAX_EVENT_MS so
         * a matchable pair always fits in one event.
         */
        const val MAX_AXLE_PAIR_GAP_MS = 1300L

        /**
         * Longitudinal-vs-lateral dominance ratio for the asymmetry validity gate
         * (audit detection #6). If the peak longitudinal (fore-aft brake/accel)
         * residual exceeds the peak lateral residual by more than this factor, the
         * horizontal energy is driver-caused fore-aft, not road one-sidedness, so we
         * mark asymmetry invalid. 1.5 keeps a genuinely lateral kick (lateral ≥
         * longitudinal, or only modestly below) valid while vetoing a clear
         * brake/launch transient. PROVISIONAL — tune on the labeled set.
         */
        const val LONG_DOMINANCE_RATIO = 1.5f

        /** A sample gap longer than this means the sensor stalled / we paused; treat
         *  the next sample as a fresh start rather than spanning the gap. ~300 ms =
         *  30 missed samples at 100 Hz, well beyond normal jitter. */
        const val GAP_MS = 300L

        /** Sample period the sample-count constants were tuned at (F-005: 99.9 Hz). */
        const val NOMINAL_PERIOD_MS = 10f

        /** Floor for adapted quiet/re-arm runs: a single quiet sample is too noise-prone. */
        const val MIN_ADAPTED_QUIET_SAMPLES = 2

        /** Rise time needs ≥2 samples inside POTHOLE_RISE_MS (50 ms) to vote. */
        const val MAX_RISE_RESOLVABLE_PERIOD_MS = 25f

        /** An adapted stream tolerates this many missing samples before the gap reset. */
        const val GAP_PERIODS = 4
    }
}
