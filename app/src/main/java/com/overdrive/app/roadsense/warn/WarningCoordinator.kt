package com.overdrive.app.roadsense.warn

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.detect.Pose
import com.overdrive.app.roadsense.detect.Severity
import com.overdrive.app.roadsense.store.ApproachEngine
import com.overdrive.app.roadsense.store.GeoMath
import com.overdrive.app.roadsense.store.RoadSenseStore

/**
 * Decides WHEN to warn the driver about an upcoming hazard and HOW (audio chime /
 * visual cue / both), honoring every gate (D-010 distance, D-015 confidence,
 * R-SET-4 per-severity, R-EXT-4 direction).
 *
 * ## Where it sits
 * Driven by the daemon controller's periodic tick (NOT the 100 Hz IMU path). On
 * each tick the controller hands it the live [Pose] + the current config snapshot;
 * this queries the store for hazards ahead (via [ApproachEngine]), picks the most
 * imminent one that passes the gates, and fires at most one warning per hazard.
 *
 * ## The gates (all must pass)
 *  1. feature + warnings enabled, and severity/confidence pass [RoadSenseConfig.Snapshot.warnsFor]
 *  2. hazard is AHEAD within the forward cone (ApproachEngine already filtered this)
 *  3. range ≤ dynamic alert distance `d = max(v·t_w, floor)` (D-010)
 *  4. we haven't already warned for THIS hazard id on this approach (dedupe)
 *
 * ## Audio
 * Uses the injected [AudioCue]. The production controller installs
 * [BridgedAudioCue] to play the bundled severity chime through the selected OEM
 * audio channels; [ToneAudioCue] remains the standalone fallback. Visual cues
 * are delegated to a [VisualSink] the overlay implements, keeping this class
 * overlay-agnostic + unit-testable.
 *
 * Not thread-safe; called from the single controller tick thread.
 */
class WarningCoordinator(
    private val store: RoadSenseStore,
    private val approach: ApproachEngine = ApproachEngine(),
    private val visualSink: VisualSink? = null,
    private val audio: AudioCue = ToneAudioCue(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Overlay-implemented visual cue. Kept minimal so the overlay owns rendering. */
    interface VisualSink {
        /** Show/refresh the approach cue for the zone led by [hazardId] at [rangeM]
         *  with the given [relativeBearingDeg] (−180..+180, 0=ahead) and [severity]
         *  (the zone's WORST). [typeOrdinal] is the lead hazard's type (for the
         *  icon). [zoneCount]/[zoneLengthM]/[zoneRough] describe the cluster (D-032):
         *  count=1 length=0 for a singleton; count>1 / rough=true for a cluster the
         *  overlay renders as "N bumps ahead" / "rough section, N m". */
        fun showApproach(
            hazardId: String,
            rangeM: Double,
            relativeBearingDeg: Double,
            severity: Severity,
            typeOrdinal: Int,
            zoneCount: Int,
            zoneLengthM: Int,
            zoneRough: Boolean,
        )
        /** Clear any visible approach cue (no hazard ahead). */
        fun clearApproach()
    }

    /** Pluggable audio so tests don't need a real ToneGenerator. */
    interface AudioCue {
        fun chime(severity: Severity)
        fun release()
    }

    // Dedupe: the id we last warned for, and when, so we warn once per approach
    // and re-arm only after the hazard leaves range / a cooldown passes.
    private var lastWarnedId: String? = null
    private var lastWarnedMs = 0L
    // GLOBAL last-audio-chime time — a single gate across ALL hazards/zones so a
    // burst of distinct bumps within the cool-off window yields ONE chime (issue C).
    // Set ONLY when a chime actually fires, independent of the per-zone dedupe above.
    private var lastAudioChimeMs = 0L

    // STICKY-VISUAL CONTINUATION latch (Session-35 fix). The card was vanishing
    // mid-approach because braking for the hazard drops speed below the heading-reliable
    // floor (RoadSenseController HEADING_RELIABLE_MPS=2.5 m/s ≈ 9 km/h), which makes
    // ApproachEngine.rank() return emptyList() → visualTarget null → clearApproach —
    // exactly the "shows 40m then disappears before I reach it" symptom (drivers crawl a
    // breaker at <9 km/h while still several m out). Issue-A's earlier fix only removed
    // the speed-shrinking alertDist gate; this upstream suppression still nuked the card.
    //
    // Fix: once a hazard's card has been SHOWN while heading WAS reliable (it already
    // passed the full R-EXT-4 cone + road-match gates at speed), cache it; on a later
    // tick where the approach list is empty ONLY because heading went unreliable (crawl),
    // RE-SHOW that same card with the distance recomputed from the live pose, until the
    // hazard is passed (range < minRangeM) or the latch ages out. This can only PROLONG
    // an already-direction-validated card — it can NEVER acquire a new one while heading
    // is unreliable, so the R-EXT-4 invariant (no wrong-road/omnidirectional warnings) is
    // intact. VISUAL ONLY — audio stays gated on real rank() so no chime fires on a stale
    // heading. Cleared when passed / aged out / a fresh reliable target supersedes it.
    private var stickyLead: ApproachEngine.Approach? = null
    private var stickyShownMs = 0L
    // Smallest live range seen while latched (RS-1). A hazard still AHEAD gets closer each tick
    // (range shrinks); once PASSED, range grows monotonically as we drive away. So we only keep
    // re-showing while the live range stays within a small epsilon of this running minimum —
    // the first tick range climbs clearly above it, the hazard is behind us → clear. This is the
    // direction check the range-only recompute lacked (can't otherwise tell 5 m ahead from 5 m
    // behind without a reliable bearing).
    private var stickyMinRangeM = Double.MAX_VALUE

    /**
     * Evaluate one tick. [pose] is the RAW (back-projected current) vehicle pose and
     * is the SOLE authority for every selection/geometry decision (queryAhead, rank /
     * forward-cone / road-match, the sticky-visual recompute, and the latched running
     * minimum) — so "is this hazard still ahead?" is always judged from the true fix.
     * [displayPose] is the forward-extrapolated DISPLAY pose used ONLY to smooth the
     * SHOWN distance so it counts down between the ~1–2 Hz fixes; it never decides
     * whether a hazard is ahead (a constant-velocity dead-reckon over-advances while
     * braking and could otherwise drop a not-yet-passed lead a tick early / skip the
     * next cluster member). When extrapolation is off (crawl/bad fix) displayPose == the
     * raw pose, so the shown range is just the raw range. [cfg] the current config
     * snapshot; [headingReliable] false at crawl / no-fix (ApproachEngine then
     * suppresses entirely — no direction-blind warnings).
     */
    fun onTick(pose: Pose, displayPose: Pose, cfg: RoadSenseConfig.Snapshot, headingReliable: Boolean) {
        if (!cfg.enabled || !cfg.warnEnabled) {
            visualSink?.clearApproach()
            return
        }

        // Pull nearby hazards (tile-scoped); ApproachEngine consumes StoredHazard
        // directly (queryAhead's return type) — no adapter needed.
        val nearby = store.queryAhead(pose.lat, pose.lng, pose.bearingDeg.toDouble(), MAX_CANDIDATES)
        if (nearby.isEmpty()) { visualSink?.clearApproach(); return }

        // Group ahead into zones (D-032). ApproachEngine.rank already bounded these to
        // [minRangeM .. maxRangeM] inside the forward cone + same-direction road match,
        // so EVERY zone here is a hazard genuinely AHEAD on our road and not yet passed.
        val zones = approach.zonesAhead(pose, nearby, headingReliable)

        // VISUAL target — the nearest zone within the WIDE, persistent visual band that
        // passes the severity/confidence gate. Deliberately INDEPENDENT of speed: it
        // persists from first detection until the hazard is passed (ApproachEngine drops
        // it once range < minRangeM), so braking on approach can't make it vanish early
        // (issue A). Previously this used the speed-shrinking alertDist, which cleared
        // the cue before reaching the hazard.
        val visualTarget = zones.firstOrNull { z ->
            z.lead.rangeM <= VISUAL_MAX_RANGE_M &&
                z.members.any { cfg.warnsFor(it.stored.hazard.severity.level, it.stored.hazard.confidence) }
        }

        if (visualTarget == null) {
            // No qualifying zone this tick. Before clearing, try the STICKY-VISUAL latch:
            // if the only reason there's nothing is that heading went unreliable (the
            // brake-induced crawl, ApproachEngine.rank → emptyList), keep showing the
            // already-acquired card so it doesn't vanish before the hazard is reached.
            if (!headingReliable &&
                cfg.warnMode != RoadSenseConfig.WarnMode.AUDIO &&
                tryShowStickyVisual(pose, nearby, now = clock())
            ) {
                return
            }
            // Genuinely nothing ahead (or latch expired / passed / audio-only) → clear.
            // A hazard now under the car was already dropped by ApproachEngine
            // (range < minRangeM), so a passed hazard lands here and the cue clears.
            stickyLead = null
            visualSink?.clearApproach()
            if (lastWarnedId != null && zones.none { it.lead.stored.id == lastWarnedId }) {
                lastWarnedId = null
            }
            return
        }

        val lead = visualTarget.lead
        // AUDIO chimes at the zone's WORST member (D-032): one chime must convey the
        // most serious thing in the cluster (a deep pothole 20 m past a gentle bump
        // still warrants the urgent tone). The VISUAL, however, now reveals the cluster
        // ONE HAZARD AT A TIME — closest first, advancing as each is passed — so the
        // card's severity (arrow colour, pulse urgency) must describe the LEAD hazard
        // actually being shown, not a worse one still further ahead. Colouring the
        // nearest gentle bump "severe" because of a later pothole misreads the card.
        // Split them: zone-worst drives audio, lead drives the visual.
        val sev = severityFromLevel(visualTarget.maxSeverityLevel)
        // A rough/washboard SECTION is shown as one continuous stretch (not advanced
        // hazard-by-hazard), so it keeps the zone-worst tint; a discrete cluster is
        // revealed one bump at a time, so its tint follows the lead bump being shown.
        val visualSev = if (visualTarget.isRoughSection) sev
            else severityFromLevel(lead.stored.hazard.severity.level)
        val now = clock()

        // (A) VISUAL: refresh every tick while the hazard is ahead in the interest band
        // — NOT gated on the dynamic alertDist. Shown continuously, distance counting
        // down, until passed. (visual modes = VISUAL or BOTH)
        if (cfg.warnMode != RoadSenseConfig.WarnMode.AUDIO) {
            // SHOWN distance smoothing: the lead range above is from the RAW fix (so the
            // ahead/passed decision is honest), but the raw fix is step-held flat between
            // the ~1–2 Hz pushes, so it freezes then jumps. Subtract the forward-advance
            // the DISPLAY pose dead-reckoned past the raw fix (how far we've travelled
            // since the fix) so the shown number counts down smoothly — clamped so it can
            // NEVER cross the hazard (>= minRange) and never below 0. displayPose == pose
            // when extrapolation is off, so this is a no-op (raw range) at crawl/bad fix.
            val forwardAdvanceM = GeoMath.haversineMeters(
                pose.lat, pose.lng, displayPose.lat, displayPose.lng
            ).coerceAtMost((lead.rangeM - approach.minRangeMeters).coerceAtLeast(0.0))
            val displayRangeM = lead.rangeM - forwardAdvanceM
            visualSink?.showApproach(
                lead.stored.id, displayRangeM, lead.relativeBearingDeg, visualSev,
                lead.stored.hazard.type.ordinal,
                visualTarget.count, visualTarget.lengthM.toInt(), visualTarget.isRoughSection,
            )
            // Cache for the sticky-visual latch ONLY when heading is reliable — that's
            // when the cone + road-match gates actually validated this hazard's direction.
            // We never cache (or re-show) a target acquired without a reliable bearing, so
            // the latch can only ever PROLONG a properly direction-validated card. Track the
            // running min range (RS-1) so the latch can detect "passed" (range climbing).
            if (headingReliable) {
                if (stickyLead?.stored?.id != lead.stored.id) stickyMinRangeM = Double.MAX_VALUE
                stickyLead = lead; stickyShownMs = now
                if (lead.rangeM < stickyMinRangeM) stickyMinRangeM = lead.rangeM
            }
        } else {
            // AUDIO-only: the user wants no hazard CARD, but the overlay still needs a
            // heartbeat or the app-side card goes stale ("Idle"/disconnected) after its
            // 4 s window — for the WHOLE approach, precisely while we're actively
            // chiming. Publishing idle here keeps the daemon-alive heartbeat + the
            // calibration dot flowing without rendering a hazard card (the right visual
            // for audio-only mode). Was previously a no-op → overlay stall.
            visualSink?.clearApproach()
        }

        // (B)(C) AUDIO: fire ONCE when a not-yet-chimed zone crosses into the NARROW,
        // speed-based alert distance, then a GLOBAL cool-off silences every chime for
        // AUDIO_COOLOFF_MS — so a burst of 3-4 bumps gets one chime while the visual
        // keeps updating. Two independent gates compose: per-zone dedupe (lastWarnedId)
        // + global quiet window (lastAudioChimeMs).
        val alertDist = maxOf(pose.speedMps * cfg.warnLeadSeconds, cfg.warnFloorMeters).toDouble()
        val withinAlert = lead.rangeM <= alertDist
        // newZone: a zone we haven't CHIMED for yet (different id), or the same zone
        // we're still approaching past the per-zone re-warn window.
        val newZone = lead.stored.id != lastWarnedId || (now - lastWarnedMs) >= REWARN_COOLDOWN_MS
        val cooledOff = (now - lastAudioChimeMs) >= AUDIO_COOLOFF_MS

        // (D) MINOR-consensus chime gate (Session-34 fit, D-037). On a real drive, 40% of
        // detections were explicit user-rejected false positives — and 50/55 were MINOR.
        // The verified analysis proved NO single-event signal (peak, shape, duration,
        // confidence) separates these one-off FP thuds from genuine MINOR hazards: only
        // REPETITION does (a real road feature recurs every pass / earns cross-device
        // consensus; a lane-seam/manhole/driver jolt does not). So for a MINOR zone we
        // defer the CHIME until the lead hazard is corroborated — seen on ≥2 passes
        // (observations≥2 → locally-confirmed, D-012), human-verified, or cloud-shared.
        // CRITICAL — this only gates AUDIO: the hazard is still detected, stored, mapped,
        // shown VISUALLY (above), and uploaded; we just don't beep on a first-ever unconfirmed
        // MINOR. MODERATE/SEVERE always chime on first encounter (a deep pothole can't wait
        // for pass 2). This is the one precision lever that touches NEITHER the detection
        // threshold NOR speed, so it cannot drop the slow/gentle hazards the recall fix
        // recovered — it only delays a low-severity beep by one pass. Severe-biased + speed-
        // agnostic by construction.
        val zoneSev = visualTarget.maxSeverityLevel
        val leadCorroborated = lead.stored.observations >= MIN_OBS_FOR_MINOR_CHIME ||
            lead.stored.humanVerified ||
            lead.stored.status >= com.overdrive.app.roadsense.detect.StoredHazard.STATUS_LOCALLY_CONFIRMED
        val chimeSeverityOk = zoneSev > Severity.MINOR.level || leadCorroborated

        if (withinAlert && newZone && chimeSeverityOk &&
            cfg.warnMode != RoadSenseConfig.WarnMode.VISUAL && cooledOff) {
            // Chime exactly once per zone, AND only when the global burst cool-off has
            // elapsed. We mark the zone announced (lastWarnedId/Ms) ONLY here — when a
            // chime actually fires — NOT unconditionally on withinAlert&&newZone. The
            // old unconditional mark "spent" a distinct later hazard's audio slot when
            // that hazard first appeared mid-cool-off: it was flagged announced without
            // ever chiming, so once the cool-off expired newZone was already false and
            // it stayed silent forever (the visual still showed). Marking inside the
            // chime keeps the burst collapse (cool-off still silences the rest of a
            // burst) while letting a genuinely separate hazard chime once the window
            // clears, and still prevents the SAME zone re-triggering the instant the
            // cool-off ends (it's now lastWarnedId until REWARN_COOLDOWN_MS).
            audio.chime(sev)
            lastAudioChimeMs = now   // global gate — silences the rest of the burst (C)
            lastWarnedId = lead.stored.id
            lastWarnedMs = now
            Log.i(TAG, "warn $sev zone lead=${lead.stored.id} n=${visualTarget.count} " +
                "len=${"%.0f".format(visualTarget.lengthM)}m rough=${visualTarget.isRoughSection} " +
                "@${"%.0f".format(lead.rangeM)}m (alertDist=${"%.0f".format(alertDist)}m " +
                "mode=${cfg.warnMode})")
        }
    }

    /**
     * STICKY-VISUAL continuation (Session-35). Re-show the already-acquired card through a
     * brake-induced crawl, so it doesn't vanish before the hazard is reached. Returns true
     * if it re-showed (caller then returns without clearing). Conditions (ALL must hold):
     *  - we have a cached lead from a reliable-heading tick ([stickyLead]);
     *  - that exact hazard id is STILL in the nearby set (not deleted / passed out of the
     *    tile query) — so we never show a hazard the store no longer has;
     *  - the latch hasn't aged out ([STICKY_LATCH_MS]);
     *  - the car is STILL ROLLING (speed > 0) — a full stop/park drops it;
     *  - the LIVE-recomputed range is in [minRangeM, VISUAL_MAX_RANGE_M] — once range
     *    falls under minRangeM the hazard is passed and we let the normal clear happen.
     * Distance is recomputed from the live pose so it keeps counting down truthfully; the
     * cached relative bearing/severity/type are reused (we can't re-derive bearing without
     * a reliable heading, but the arrow on a sub-9km/h crawl is cosmetic). AUDIO is never
     * triggered here. Cannot create a new warning — only prolong a validated one (R-EXT-4).
     */
    private fun tryShowStickyVisual(
        pose: Pose,
        nearby: List<com.overdrive.app.roadsense.detect.StoredHazard>,
        now: Long,
    ): Boolean {
        val lead = stickyLead ?: return false
        if (now - stickyShownMs > STICKY_LATCH_MS) { stickyLead = null; return false }
        if (pose.speedMps <= 0f) return false   // genuinely stopped → let it clear
        // The cached hazard must still exist in the current query (not passed/deleted).
        if (nearby.none { it.id == lead.stored.id }) { stickyLead = null; return false }
        val h = lead.stored.hazard
        val rangeM = GeoMath.haversineMeters(pose.lat, pose.lng, h.lat, h.lng)
        if (rangeM < approach.minRangeMeters || rangeM > VISUAL_MAX_RANGE_M) {
            // < minRange ⇒ passed (let normal clear run); > visual band ⇒ not approaching.
            stickyLead = null
            return false
        }
        // RS-1 DIRECTION CHECK: a still-AHEAD hazard gets closer (range shrinks toward the
        // running min); a PASSED hazard's range grows as we drive away. Without a reliable
        // bearing at crawl the recompute is range-only, so use this monotonicity: if the live
        // range has climbed clearly above the smallest range we ever saw for this hazard, it's
        // behind us → stop re-showing (don't present a passed hazard as "ahead, distance up").
        if (rangeM < stickyMinRangeM) stickyMinRangeM = rangeM
        else if (rangeM > stickyMinRangeM + STICKY_PASSED_EPSILON_M) {
            stickyLead = null   // range climbing past the approach minimum ⇒ passed
            return false
        }
        visualSink?.showApproach(
            lead.stored.id, rangeM, lead.relativeBearingDeg,
            severityFromLevel(h.severity.level), h.type.ordinal,
            1, 0, false,   // singleton presentation while latched (no fresh zone grouping)
        )
        return true
    }

    /** Map a stored severity level (1..3) back to [Severity], clamped. */
    private fun severityFromLevel(level: Int): Severity = when {
        level >= Severity.SEVERE.level -> Severity.SEVERE
        level == Severity.MODERATE.level -> Severity.MODERATE
        else -> Severity.MINOR
    }

    fun release() = audio.release()

    companion object {
        private const val TAG = "RoadSense/Warn"
        private const val MAX_CANDIDATES = 16
        /** Don't re-chime for the same zone lead within this window (one chime/approach). */
        private const val REWARN_COOLDOWN_MS = 20_000L
        /** GLOBAL audio quiet window: after ANY chime, suppress ALL chimes for this long
         *  regardless of how many distinct hazards/zones appear — collapses a burst of
         *  3-4 close bumps into a single chime while the visual keeps updating (issue C).
         *  12 s sits between a cluster traversal (~seconds) and REWARN_COOLDOWN_MS, so a
         *  genuinely separate hazard a block later still chimes. PROVISIONAL. */
        private const val AUDIO_COOLOFF_MS = 12_000L
        /** Wide, speed-INDEPENDENT band the VISUAL cue persists within — from first
         *  detection until the hazard is passed (issue A). Must stay below
         *  ApproachEngine.DEFAULT_MAX_RANGE_M (300m) so the cue clears with margin
         *  before the candidate even leaves the store query. PROVISIONAL. */
        private const val VISUAL_MAX_RANGE_M = 200.0
        /** Observations a MINOR hazard needs before its CHIME is allowed (D-037). 2 =
         *  seen on a second pass (the K=2 locally-confirmed bar, D-012), i.e. it's a real
         *  recurring road feature, not a one-off transient. MODERATE/SEVERE bypass this
         *  (chime on first encounter). Only gates audio — visual/map/upload are immediate. */
        private const val MIN_OBS_FOR_MINOR_CHIME = 2
        /** Sticky-visual continuation window (Session-35). After the last reliable-heading
         *  tick that showed a card, keep re-showing it through a brake-induced crawl for at
         *  most this long, so the card doesn't vanish before the hazard is reached. 8 s
         *  comfortably covers a slow-down → crawl → cross of a typical breaker; bounded so a
         *  genuine stop/park/turn doesn't keep a stale card alive. Also clears the instant
         *  the hazard is passed (range < minRange) or the car fully stops. PROVISIONAL —
         *  tune on a real crawl-over drive. */
        private const val STICKY_LATCH_MS = 8_000L
        /** How far (m) the live range may climb above the latched approach-minimum before we
         *  treat the hazard as PASSED and stop re-showing (RS-1). A few metres absorbs GPS
         *  jitter on the latched range without letting a behind-the-car hazard linger. */
        private const val STICKY_PASSED_EPSILON_M = 4.0
    }
}

/**
 * Default [WarningCoordinator.AudioCue] backed by [ToneGenerator] on STREAM_MUSIC
 * with a distinct tone for each severity so the chime itself conveys urgency.
 * The generator is created lazily and reused;
 * [release] frees it when the feature stops.
 */
class ToneAudioCue : WarningCoordinator.AudioCue {
    private var tg: ToneGenerator? = null

    private fun gen(): ToneGenerator? {
        if (tg == null) {
            tg = try { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) } catch (_: Throwable) { null }
        }
        return tg
    }

    override fun chime(severity: Severity) {
        val tone = when (severity) {
            Severity.MINOR -> ToneGenerator.TONE_PROP_BEEP          // single soft beep
            Severity.MODERATE -> ToneGenerator.TONE_PROP_BEEP2      // double beep
            Severity.SEVERE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD // urgent
        }
        val ms = if (severity == Severity.SEVERE) 600 else 300
        try { gen()?.startTone(tone, ms) } catch (_: Throwable) { /* never crash on a chime */ }
    }

    override fun release() {
        try { tg?.release() } catch (_: Throwable) {}
        tg = null
    }

    companion object { private const val VOLUME = 90 }
}
