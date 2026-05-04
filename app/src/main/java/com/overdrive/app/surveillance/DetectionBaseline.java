package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Per-quadrant detection baseline for filtering static objects from YOLO output.
 *
 * Maintains a "living memory" of what objects are already in the scene so that
 * motion-triggered YOLO detections of static objects (parked cars, trash cans,
 * fire hydrants) are suppressed — only NEW or MOVED objects trigger recording.
 *
 * Update rules:
 * - Seeded once on sentry start (1 YOLO inference per quadrant)
 * - Updated event-driven: at the end of each motion event, the quadrant's
 *   baseline is refreshed from the YOLO detections already computed during
 *   the event. Zero extra inferences.
 * - Refreshed on lighting transitions (dawn/dusk) detected by Stage 1
 *   brightness shift. 1 inference per affected quadrant, 2-3 times per night.
 *
 * Safety rules:
 * - Living things (person, dog, cat, bird) are NEVER promoted to baseline.
 * - Spatial veto: if a detection overlaps with any person detection seen in
 *   the last 60 seconds of the event, it is NOT promoted regardless of class.
 *   This catches misclassification (crouched person labeled as fire hydrant).
 */
public class DetectionBaseline {
    private static final DaemonLogger logger = DaemonLogger.getInstance("DetectionBaseline");

    private static final int NUM_QUADRANTS = 4;

    // IoU threshold for matching a detection against a baseline entry
    private static final float MATCH_IOU_THRESHOLD = 0.7f;

    // IoU threshold for spatial veto (overlap with recent person detections)
    private static final float SPATIAL_VETO_IOU_THRESHOLD = 0.3f;

    // Minimum confidence to add to baseline
    private static final float MIN_BASELINE_CONFIDENCE = 0.4f;

    // Maximum age for a baseline entry before automatic expiry (2 hours).
    // Handles the "ghost car" scenario: a car parks, gets added to baseline,
    // then leaves. Without expiry, the ghost entry persists forever and
    // suppresses the next car that parks in the same spot.
    // 2 hours is long enough that a genuinely parked car gets re-added on
    // the next motion event, but short enough to clean up ghosts.
    private static final long BASELINE_ENTRY_MAX_AGE_MS = 2 * 60 * 60 * 1000L;  // 2 hours

    // COCO class IDs that are NEVER promotable (living things)
    // 0=person, 15=cat, 16=dog, 14=bird
    private static final int[] NEVER_PROMOTE_CLASSES = {0, 14, 15, 16};

    /**
     * A single baseline entry: a known static object in the scene.
     */
    public static class Entry {
        public final int classId;
        public final float cx, cy, w, h;  // Normalized to quadrant dimensions
        public final long addedAtMs;
        public final int quadrant;

        public Entry(int classId, float cx, float cy, float w, float h, int quadrant) {
            this.classId = classId;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.addedAtMs = System.currentTimeMillis();
            this.quadrant = quadrant;
        }
    }

    // Per-quadrant baseline lists
    private final List<Entry>[] baselines;

    // Per-quadrant recent person detections (for spatial veto)
    // Stores person detections from the last 60 seconds of each event
    private final List<PersonRecord>[] recentPersons;

    private static class PersonRecord {
        final float cx, cy, w, h;
        final long timestampMs;

        PersonRecord(float cx, float cy, float w, float h) {
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.timestampMs = System.currentTimeMillis();
        }
    }

    @SuppressWarnings("unchecked")
    public DetectionBaseline() {
        baselines = new List[NUM_QUADRANTS];
        recentPersons = new List[NUM_QUADRANTS];
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            baselines[q] = new ArrayList<>();
            recentPersons[q] = new ArrayList<>();
        }
    }

    // ==================== SEEDING ====================

    /**
     * Seeds the baseline for a quadrant from an initial YOLO scan.
     * Called once per quadrant on sentry start.
     */
    public void seedFromDetections(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;

        baselines[quadrant].clear();
        if (detections == null) return;

        for (Detection det : detections) {
            if (det.getConfidence() < MIN_BASELINE_CONFIDENCE) continue;
            if (isNeverPromoteClass(det.getClassId())) continue;

            float cx = (det.getX() + det.getW() / 2.0f) / quadW;
            float cy = (det.getY() + det.getH() / 2.0f) / quadH;
            float w = (float) det.getW() / quadW;
            float h = (float) det.getH() / quadH;

            baselines[quadrant].add(new Entry(det.getClassId(), cx, cy, w, h, quadrant));
        }

        logger.info("Baseline seeded for Q" + quadrant + ": " + baselines[quadrant].size() + " entries");
    }

    // ==================== FILTERING ====================

    /**
     * Checks if a detection matches an existing baseline entry.
     * Returns true if the detection is a known static object (should be suppressed).
     */
    public boolean isInBaseline(Detection det, int quadrant, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return false;

        float detCx = (det.getX() + det.getW() / 2.0f) / quadW;
        float detCy = (det.getY() + det.getH() / 2.0f) / quadH;
        float detW = (float) det.getW() / quadW;
        float detH = (float) det.getH() / quadH;

        long now = System.currentTimeMillis();
        Iterator<Entry> iter = baselines[quadrant].iterator();
        while (iter.hasNext()) {
            Entry entry = iter.next();
            // Expire old entries (ghost object cleanup)
            if (now - entry.addedAtMs > BASELINE_ENTRY_MAX_AGE_MS) {
                iter.remove();
                continue;
            }
            if (entry.classId == det.getClassId()) {
                float iou = computeIoU(detCx, detCy, detW, detH, entry.cx, entry.cy, entry.w, entry.h);
                if (iou >= MATCH_IOU_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== EVENT-DRIVEN UPDATE ====================

    /**
     * Records a person detection for spatial veto tracking.
     * Called during YOLO processing whenever a person is detected.
     */
    public void recordPersonDetection(int quadrant, Detection det, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;
        if (det.getClassId() != 0) return;  // Only person (class 0)

        float cx = (det.getX() + det.getW() / 2.0f) / quadW;
        float cy = (det.getY() + det.getH() / 2.0f) / quadH;
        float w = (float) det.getW() / quadW;
        float h = (float) det.getH() / quadH;

        recentPersons[quadrant].add(new PersonRecord(cx, cy, w, h));

        // Prune entries older than 60 seconds
        long cutoff = System.currentTimeMillis() - 60_000;
        recentPersons[quadrant].removeIf(p -> p.timestampMs < cutoff);
    }

    /**
     * Updates the baseline for a quadrant at the end of a motion event.
     * Uses the last YOLO detections from the event — zero extra inferences.
     *
     * Rules:
     * - Never promote living things (person, dog, cat, bird)
     * - Spatial veto: skip if bbox overlaps with any recent person detection
     * - Only update the specified quadrant, never touch other quadrants
     * - Only promote detections with confidence > 0.4
     */
    public void updateFromEventEnd(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;
        if (detections == null || detections.isEmpty()) return;

        int added = 0;
        int vetoed = 0;
        int skippedLiving = 0;

        for (Detection det : detections) {
            if (det.getConfidence() < MIN_BASELINE_CONFIDENCE) continue;

            // Rule 1: Never promote living things
            if (isNeverPromoteClass(det.getClassId())) {
                skippedLiving++;
                continue;
            }

            float cx = (det.getX() + det.getW() / 2.0f) / quadW;
            float cy = (det.getY() + det.getH() / 2.0f) / quadH;
            float w = (float) det.getW() / quadW;
            float h = (float) det.getH() / quadH;

            // Rule 2: Spatial veto — skip if overlaps with recent person detection
            if (overlapsRecentPerson(quadrant, cx, cy, w, h)) {
                vetoed++;
                continue;
            }

            // Rule 3: Don't add duplicates (already in baseline)
            boolean alreadyExists = false;
            for (Entry entry : baselines[quadrant]) {
                if (entry.classId == det.getClassId()) {
                    float iou = computeIoU(cx, cy, w, h, entry.cx, entry.cy, entry.w, entry.h);
                    if (iou >= MATCH_IOU_THRESHOLD) {
                        alreadyExists = true;
                        break;
                    }
                }
            }

            if (!alreadyExists) {
                baselines[quadrant].add(new Entry(det.getClassId(), cx, cy, w, h, quadrant));
                added++;
            }
        }

        if (added > 0 || vetoed > 0) {
            logger.info("Baseline update Q" + quadrant + ": +" + added + " added, " +
                    vetoed + " vetoed (spatial), " + skippedLiving + " skipped (living), " +
                    "total=" + baselines[quadrant].size());
        }

        // Clear recent person records for this quadrant (event is over)
        recentPersons[quadrant].clear();
    }

    // ==================== LIGHTING TRANSITION REFRESH ====================

    /**
     * Full refresh of a quadrant's baseline on a lighting transition.
     * Replaces the entire quadrant baseline with fresh detections.
     * Called when Stage 1 brightness shift is detected (dawn/dusk).
     */
    public void refreshQuadrant(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;

        baselines[quadrant].clear();
        if (detections == null) return;

        for (Detection det : detections) {
            if (det.getConfidence() < MIN_BASELINE_CONFIDENCE) continue;
            if (isNeverPromoteClass(det.getClassId())) continue;

            float cx = (det.getX() + det.getW() / 2.0f) / quadW;
            float cy = (det.getY() + det.getH() / 2.0f) / quadH;
            float w = (float) det.getW() / quadW;
            float h = (float) det.getH() / quadH;

            baselines[quadrant].add(new Entry(det.getClassId(), cx, cy, w, h, quadrant));
        }

        logger.info("Baseline refreshed Q" + quadrant + " (lighting transition): " +
                baselines[quadrant].size() + " entries");
    }

    // ==================== RESET ====================

    /**
     * Clears all baselines and person records. Called on sentry disable.
     */
    public void reset() {
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            baselines[q].clear();
            recentPersons[q].clear();
        }
        logger.info("Baseline reset (all quadrants cleared)");
    }

    /**
     * Gets the number of baseline entries for a quadrant.
     */
    public int getEntryCount(int quadrant) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return 0;
        return baselines[quadrant].size();
    }

    // ==================== INTERNAL ====================

    private boolean isNeverPromoteClass(int classId) {
        for (int cls : NEVER_PROMOTE_CLASSES) {
            if (cls == classId) return true;
        }
        return false;
    }

    private boolean overlapsRecentPerson(int quadrant, float cx, float cy, float w, float h) {
        for (PersonRecord p : recentPersons[quadrant]) {
            float iou = computeIoU(cx, cy, w, h, p.cx, p.cy, p.w, p.h);
            if (iou >= SPATIAL_VETO_IOU_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes IoU between two bounding boxes specified by center + dimensions.
     */
    private static float computeIoU(float cx1, float cy1, float w1, float h1,
                                     float cx2, float cy2, float w2, float h2) {
        float left1 = cx1 - w1 / 2, right1 = cx1 + w1 / 2;
        float top1 = cy1 - h1 / 2, bottom1 = cy1 + h1 / 2;
        float left2 = cx2 - w2 / 2, right2 = cx2 + w2 / 2;
        float top2 = cy2 - h2 / 2, bottom2 = cy2 + h2 / 2;

        float interLeft = Math.max(left1, left2);
        float interTop = Math.max(top1, top2);
        float interRight = Math.min(right1, right2);
        float interBottom = Math.min(bottom1, bottom2);

        if (interRight <= interLeft || interBottom <= interTop) return 0f;

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float area1 = w1 * h1;
        float area2 = w2 * h2;
        float unionArea = area1 + area2 - interArea;

        return unionArea > 0 ? interArea / unionArea : 0f;
    }
}
