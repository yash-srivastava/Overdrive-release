package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;

import java.util.List;

/**
 * Distance / proximity inference for surveillance threats.
 *
 * <p><b>Why this class exists.</b> The retired geometric model computed metric
 * distance from the Y position of motion-block centroids assuming:
 *  <ul>
 *    <li>The image is a rectilinear pinhole projection (it isn't — BYD AVM
 *        cameras are fisheye, dewarped non-uniformly by the HAL)</li>
 *    <li>Y=0 of each quadrant is the optical horizon (it isn't — the
 *        horizon sits roughly mid-frame after dewarp)</li>
 *    <li>The motion-block centroid corresponds to the object's foot on
 *        the ground plane (it doesn't — it's the mean of all moving
 *        blocks, which for a walking person is the torso)</li>
 *    <li>Camera height is well-known (it isn't — values were estimates,
 *        not measured)</li>
 *  </ul>
 * The combined error stack produced "0.4–0.9 m" log lines for objects
 * actually 3–8 m away.
 *
 * <p><b>What this replaces it with.</b> Two independent estimates,
 * fused only when both are valid:
 *
 * <p><b>Technique A — class-conditional bbox-height inference (post-YOLO).</b>
 * Once YOLO has classified an object, the class implies a real-world
 * height with low variance (a person is 1.7 ± 0.15 m, a car 1.5 ± 0.2 m).
 * Combined with the bbox height in pixels and an effective focal length,
 * distance falls out of the standard pinhole equation
 * {@code d = (f_px × h_real) / h_pixels}. This is robust to the foot/torso
 * confusion that breaks the centroid-Y approach: bbox height is stable
 * across frames once the tracker locks, and varies slowly+monotonically
 * as the object approaches.
 *
 * <p><b>Technique B — discrete tier + trend (pre-YOLO).</b> Before YOLO
 * has fired (the sub-second window between motion-trigger and first
 * inference, or any time the motion pipeline fires without YOLO available),
 * we explicitly DO NOT produce a metric distance — we produce a discrete
 * tier ({@code NEAR/MID/FAR/UNKNOWN}) plus a trend
 * ({@code APPROACHING/STABLE/RECEDING}) computed from the lowest active
 * motion block's row and its time-derivative. Trigger logic operates on
 * tier+trend+dwell+class, all reliable in this window. User-facing copy
 * during pre-YOLO says "approaching from rear", not a fabricated number.
 *
 * <p><b>State machine.</b>
 * <pre>
 * pre-YOLO event:   tier+trend, metric=null, state=AWAITING_AI
 * YOLO confirms:    metric (Technique A), tier derived for cross-check
 * NCC tracker only: metric still computed from the tracked bbox height
 * </pre>
 *
 * <p>This class is stateless. Callers (V2 motion path, AI worker, the
 * Actor tracker) compose it as needed.
 */
public final class DistanceEstimator {

    private DistanceEstimator() {}

    // ===== Class-conditional real-world heights (meters) =====
    //
    // COCO class IDs that we care about. Heights are conservative averages
    // tuned for the surveillance use case (suburban / parking lot threats):
    // adult pedestrian, daily-driver vehicle, urban bicycle/motorcycle. The
    // YOLO confidence score lets the actor tracker discount uncertain
    // detections; we don't try to do per-frame variance bookkeeping here.
    private static final float HEIGHT_PERSON_M     = 1.70f;
    private static final float HEIGHT_BICYCLE_M    = 1.60f;  // includes rider
    private static final float HEIGHT_CAR_M        = 1.50f;
    private static final float HEIGHT_MOTORCYCLE_M = 1.60f;  // includes rider
    private static final float HEIGHT_BUS_M        = 3.20f;
    private static final float HEIGHT_TRUCK_M      = 2.50f;
    private static final float HEIGHT_DEFAULT_M    = 1.50f;  // unknown / animal

    /**
     * Real-world height prior (meters) for a YOLO COCO class. Returns
     * {@link #HEIGHT_DEFAULT_M} for classes we don't have a tight prior
     * for, which keeps the inference order-of-magnitude correct without
     * hard-coding a long table.
     */
    public static float realHeightForClass(int cocoClassId) {
        switch (cocoClassId) {
            case 0:  return HEIGHT_PERSON_M;
            case 1:  return HEIGHT_BICYCLE_M;
            case 2:  return HEIGHT_CAR_M;
            case 3:  return HEIGHT_MOTORCYCLE_M;
            case 5:  return HEIGHT_BUS_M;
            case 7:  return HEIGHT_TRUCK_M;
            default: return HEIGHT_DEFAULT_M;
        }
    }

    /**
     * Effective focal length in pixels for a given vertical FOV and frame
     * height. Standard pinhole inversion. The BYD AVM HAL hands us a
     * non-uniform fisheye-dewarped strip, so this is an approximation —
     * but a useful one near the bbox center, which is what bbox-height
     * inference uses.
     */
    public static float focalPxForFov(int frameHeightPx, float verticalFovDeg) {
        if (frameHeightPx <= 0 || verticalFovDeg <= 0) return 0f;
        float halfFovRad = (float) Math.toRadians(verticalFovDeg / 2.0);
        return (frameHeightPx / 2.0f) / (float) Math.tan(halfFovRad);
    }

    /**
     * <b>Technique A:</b> infer metric distance from bbox height and a
     * class-conditional height prior.
     *
     * @param cocoClassId    YOLO COCO class id
     * @param bboxHeightPx   Object bbox height in pixels (in whatever
     *                       coordinate space was passed to YOLO — mosaic
     *                       quadrant 320×240 or foveated 640×640)
     * @param frameHeightPx  Height of the frame YOLO ran on (240 mosaic
     *                       quadrant, 640 foveated)
     * @param verticalFovDeg Effective vertical FOV of THAT frame after
     *                       any dewarp / crop. For mosaic quadrants this
     *                       is roughly the camera's vertical FOV; for
     *                       foveated crops it's narrower (the crop
     *                       window's angular extent).
     * @return Distance estimate in meters, or 0 on insufficient input.
     */
    public static float metricFromBbox(int cocoClassId,
                                       int bboxHeightPx,
                                       int frameHeightPx,
                                       float verticalFovDeg) {
        if (bboxHeightPx <= 0 || frameHeightPx <= 0) return 0f;
        float realHeight = realHeightForClass(cocoClassId);
        float focalPx = focalPxForFov(frameHeightPx, verticalFovDeg);
        if (focalPx <= 0) return 0f;
        return (focalPx * realHeight) / bboxHeightPx;
    }

    // ===== Tier (pre-YOLO) =====

    /**
     * Discrete proximity tier emitted when no class identity is available.
     * Order is from far to near so {@code ordinal()} works as a "closer"
     * comparator (NEAR > MID > FAR > UNKNOWN).
     */
    public enum Tier {
        UNKNOWN, FAR, MID, NEAR
    }

    /**
     * Trend across frames (sign of d(lowestY)/dt or d(bboxHeight)/dt).
     * APPROACHING and RECEDING are the actionable states; STABLE means
     * the object isn't moving radially (could still be moving laterally,
     * but the surveillance trigger doesn't care about that).
     */
    public enum Trend {
        UNKNOWN, APPROACHING, STABLE, RECEDING
    }

    /**
     * <b>Technique B:</b> derive a discrete tier from V2 motion-block
     * statistics. Used pre-YOLO when class identity is unknown and any
     * metric distance would be a guess.
     *
     * <p>The signal is the <b>lowest active block's row index</b> in the
     * quadrant: blocks lower in the camera FOV correspond to objects
     * physically closer to the car (after dewarp, the bottom of each AVM
     * quadrant looks nearly straight down). Block density is a tie
     * breaker — a single low block is weaker evidence than ten clustered
     * low blocks.
     *
     * @param activeBlocks  Total number of confirmed motion blocks in the
     *                      quadrant
     * @param lowestBlockY  Y row of the LOWEST active block (highest row
     *                      index = closest), or -1 if none
     * @param totalRows     Quadrant grid row count (typically 7-8)
     * @return Tier classification.
     */
    public static Tier tierFromMotion(int activeBlocks, int lowestBlockY, int totalRows) {
        if (lowestBlockY < 0 || activeBlocks <= 0 || totalRows <= 0) return Tier.UNKNOWN;

        // Convert "row from top" to "fraction down the quadrant"
        float depthFrac = (float) lowestBlockY / (float) (totalRows - 1);

        // NEAR: lowest active row is in the bottom 25% of the quadrant
        //       OR motion fills a big swath of the frame
        if (depthFrac >= 0.75f || activeBlocks >= 20) return Tier.NEAR;
        // MID:  lowest active row in the bottom ~half
        if (depthFrac >= 0.45f || activeBlocks >= 8)  return Tier.MID;
        return Tier.FAR;
    }

    /**
     * Trend from a sequence of two block-Y samples. Negative dy means the
     * lowest active block is moving downward in the image (toward the
     * bottom of the FOV) which on AVM dewarp = approaching the car.
     */
    public static Trend trendFromBlockY(int lowestYThen, int lowestYNow,
                                        long elapsedMs) {
        if (lowestYThen < 0 || lowestYNow < 0 || elapsedMs <= 0) return Trend.UNKNOWN;
        int dy = lowestYNow - lowestYThen;
        if (dy >= 1)  return Trend.APPROACHING;
        if (dy <= -1) return Trend.RECEDING;
        return Trend.STABLE;
    }

    // ===== Composite estimate =====

    /**
     * Combined proximity estimate. EXACTLY ONE of two states:
     * <ul>
     *   <li>{@code metricMeters > 0} — Technique A succeeded (we have a
     *       YOLO classification + bbox height). {@code tier} is derived
     *       from the metric for cross-checking; consumers can use either.</li>
     *   <li>{@code metricMeters == 0} — pre-YOLO state. {@code tier} +
     *       {@code trend} are the only signals. UI / log copy MUST NOT
     *       fabricate a meters value.</li>
     * </ul>
     */
    public static final class ProximityEstimate {
        public final float metricMeters;   // 0 = unavailable
        public final Tier  tier;
        public final Trend trend;
        public final boolean hasMetric;

        private ProximityEstimate(float metricMeters, Tier tier, Trend trend) {
            this.metricMeters = metricMeters;
            this.tier = tier != null ? tier : Tier.UNKNOWN;
            this.trend = trend != null ? trend : Trend.UNKNOWN;
            this.hasMetric = metricMeters > 0f;
        }

        public static ProximityEstimate metric(float meters, Trend trend) {
            return new ProximityEstimate(meters, tierFromMeters(meters), trend);
        }

        public static ProximityEstimate tierOnly(Tier tier, Trend trend) {
            return new ProximityEstimate(0f, tier, trend);
        }

        /**
         * Format suitable for log lines and notification copy. Honest
         * about uncertainty: emits a number only when we have one,
         * otherwise emits the discrete tier + trend.
         */
        public String describe() {
            if (hasMetric) {
                return String.format(java.util.Locale.US, "%.1fm %s",
                        metricMeters, trendLabel(trend));
            }
            return tierLabel(tier) + " " + trendLabel(trend);
        }
    }

    /**
     * Quantize a metric distance to the discrete tier scale, for
     * cross-checking against the motion-block tier.
     */
    public static Tier tierFromMeters(float meters) {
        if (meters <= 0) return Tier.UNKNOWN;
        if (meters <  2f) return Tier.NEAR;
        if (meters <  6f) return Tier.MID;
        return Tier.FAR;
    }

    /** Detections below this YOLO confidence are too jittery for distance
     *  inference (bbox-h variance grows large below ~0.45 confidence), so
     *  we treat them as if YOLO didn't fire and let the caller fall back
     *  to tier+trend (audit L2). */
    private static final float MIN_CONFIDENCE_FOR_DISTANCE = 0.45f;

    /**
     * Produce a metric proximity estimate from a per-quadrant YOLO list.
     *
     * <p>Selection rule: the <b>closest predicted</b> object wins, not the
     * most-confident one (audit M2). Picking by confidence misranks a
     * confident parked car at 15 m above an uncertain person at 4 m,
     * producing user-facing copy that talks about the wrong threat. The
     * actor tracker's own threat-class scoring still controls the headline
     * notification — this getter just feeds log copy + the trigger summary.
     *
     * <p>Detections under {@link #MIN_CONFIDENCE_FOR_DISTANCE} are
     * skipped: their bbox-height variance is too high for the geometric
     * inference to be useful. Caller falls back to tier+trend.
     *
     * @return a metric ProximityEstimate, or null if no qualifying
     *         detection produced a positive distance.
     */
    public static ProximityEstimate fromYoloDetections(
            List<Detection> dets, int frameHeightPx, float verticalFovDeg, Trend trend) {
        if (dets == null || dets.isEmpty() || frameHeightPx <= 0) return null;
        float bestMeters = Float.MAX_VALUE;
        for (Detection d : dets) {
            if (d.getH() <= 0) continue;
            if (d.getConfidence() < MIN_CONFIDENCE_FOR_DISTANCE) continue;
            float meters = metricFromBbox(d.getClassId(), d.getH(),
                    frameHeightPx, verticalFovDeg);
            if (meters > 0 && meters < bestMeters) {
                bestMeters = meters;
            }
        }
        if (bestMeters == Float.MAX_VALUE) return null;
        return ProximityEstimate.metric(bestMeters, trend);
    }

    /**
     * As {@link #fromYoloDetections(List, int, float, Trend)}, but with {@code personsBySize}
     * (quadrant-space boxes) a PERSON is rated like the actor labels — box height plus the
     * low-feet rule ({@link ActorTracker#personProximity}) — instead of bbox-height metres,
     * which on these fisheye tiles read people closer than they are. Other classes keep metres;
     * the closer tier wins, the metric one on a tie.
     */
    public static ProximityEstimate fromYoloDetections(
            List<Detection> dets, int frameHeightPx, float verticalFovDeg, Trend trend,
            boolean personsBySize) {
        if (!personsBySize) return fromYoloDetections(dets, frameHeightPx, verticalFovDeg, trend);
        if (dets == null || dets.isEmpty() || frameHeightPx <= 0) return null;
        Tier personTier = Tier.UNKNOWN;
        List<Detection> others = new java.util.ArrayList<>(dets.size());
        for (Detection d : dets) {
            if (d.getClassId() != 0) {
                others.add(d);
                continue;
            }
            if (d.getH() <= 0 || d.getConfidence() < MIN_CONFIDENCE_FOR_DISTANCE) continue;
            Tier t = tierFromProximity(ActorTracker.personProximity(
                    (float) d.getH() / frameHeightPx, (float) (d.getY() + d.getH()) / frameHeightPx));
            if (t.ordinal() > personTier.ordinal()) personTier = t;
        }
        ProximityEstimate metric = fromYoloDetections(others, frameHeightPx, verticalFovDeg, trend);
        if (personTier == Tier.UNKNOWN) return metric;
        if (metric != null && metric.tier.ordinal() >= personTier.ordinal()) return metric;
        return ProximityEstimate.tierOnly(personTier, trend);
    }

    private static Tier tierFromProximity(Actor.Proximity p) {
        switch (p) {
            case VERY_CLOSE:
            case CLOSE: return Tier.NEAR;
            case MID:   return Tier.MID;
            case FAR:   return Tier.FAR;
            default:    return Tier.UNKNOWN;
        }
    }

    public static String tierLabel(Tier t) {
        switch (t) {
            case NEAR:    return "near";
            case MID:     return "mid";
            case FAR:     return "far";
            default:      return "unknown";
        }
    }

    public static String trendLabel(Trend t) {
        switch (t) {
            case APPROACHING: return "approaching";
            case RECEDING:    return "receding";
            case STABLE:      return "stable";
            default:          return "";
        }
    }
}
