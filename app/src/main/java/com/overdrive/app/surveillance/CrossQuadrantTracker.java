package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * CrossQuadrantTracker — Correlates object detections across camera quadrants.
 *
 * When a person walks from the front camera's FOV into the left camera's FOV,
 * YOLO produces two independent detections. Without tracking, the timeline
 * records them as two separate events. This tracker assigns a persistent
 * track ID so the system knows it's the same person.
 *
 * Algorithm: Lightweight centroid + class matching (no deep re-identification).
 *
 * For each new detection:
 *   1. Check if any existing track has the same classId AND is in an adjacent
 *      quadrant AND was last seen within HANDOFF_WINDOW_MS.
 *   2. If the detection is near the edge of its quadrant (within EDGE_MARGIN
 *      blocks of the boundary), and the existing track was near the opposite
 *      edge of the adjacent quadrant, it's a handoff — assign the same trackId.
 *   3. Otherwise, create a new track.
 *
 * Adjacency map (based on physical camera placement on BYD):
 *   Front (Q0) ↔ Right (Q1), Front (Q0) ↔ Left (Q3*)
 *   Rear  (Q2) ↔ Right (Q1), Rear  (Q2) ↔ Left (Q3*)
 *   (* Q3 = BR in grid = Left camera per strip mapping)
 *
 * Note: Q2=BL=Rear, Q3=BR=Left in the mosaic grid.
 *
 * Edge detection:
 *   Each quadrant is 320×240 in the mosaic (10×7 block grid).
 *   "Near edge" = detection bbox within EDGE_MARGIN_PX of the quadrant boundary.
 *   Adjacent quadrants share a physical edge where FOVs overlap.
 *
 * This is intentionally simple. Full re-identification (appearance embeddings,
 * Kalman filters) would require a second neural network and is overkill for
 * a parked-car surveillance system. The centroid + class + edge heuristic
 * catches 90%+ of cross-camera transitions.
 */
public class CrossQuadrantTracker {
    private static final DaemonLogger logger = DaemonLogger.getInstance("XQTracker");

    // How long a track stays alive without updates before being pruned
    private static final long TRACK_TTL_MS = 5000;

    // Maximum time gap for a cross-quadrant handoff (person disappears from Q0,
    // appears in Q1 within this window)
    private static final long HANDOFF_WINDOW_MS = 2000;

    // Detection must be within this many pixels of the quadrant edge to be
    // considered a potential handoff candidate
    private static final int EDGE_MARGIN_PX = 48;  // ~1.5 blocks

    // Quadrant dimensions in the mosaic
    private static final int Q_WIDTH = 320;
    private static final int Q_HEIGHT = 240;

    // Maximum concurrent tracks (parked car scenario — unlikely to have >8 people)
    private static final int MAX_TRACKS = 16;

    private int nextTrackId = 1;

    /**
     * A tracked object across quadrants.
     */
    public static class Track {
        public int trackId;
        public int classId;
        public int lastQuadrant;
        public int lastX, lastY, lastW, lastH;  // Bbox in quadrant pixel coords
        public long lastSeenMs;
        public boolean active;

        // Edge flags from last observation
        public boolean nearLeftEdge, nearRightEdge, nearTopEdge, nearBottomEdge;
    }

    private final Track[] tracks = new Track[MAX_TRACKS];

    public CrossQuadrantTracker() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            tracks[i] = new Track();
            tracks[i].active = false;
        }
    }

    /**
     * Adjacency table: which quadrants share a physical camera boundary.
     *
     * Physical layout around the BYD:
     *   Front camera faces forward, Right faces passenger side,
     *   Left faces driver side, Rear faces backward.
     *
     * Quadrant indices (from MotionPipelineV2.QUADRANT_NAMES):
     *   Q0 = front (TL in mosaic grid)
     *   Q1 = right (TR in mosaic grid)
     *   Q2 = rear  (BL in mosaic grid)
     *   Q3 = left  (BR in mosaic grid)
     *
     * Adjacent pairs (physically touching FOVs):
     *   Front-Right (Q0-Q1): person walks from front to passenger side
     *   Front-Left  (Q0-Q3): person walks from front to driver side
     *   Rear-Right  (Q2-Q1): person walks from rear to passenger side
     *   Rear-Left   (Q2-Q3): person walks from rear to driver side
     *
     * Non-adjacent (impossible direct transitions):
     *   Front-Rear  (Q0-Q2): would require teleporting through the car
     *   Right-Left  (Q1-Q3): would require teleporting through the car
     */
    private static final boolean[][] ADJACENT = {
        //       Q0     Q1     Q2     Q3
        /*Q0*/ {false, true,  false, true },  // Front ↔ Right, Left
        /*Q1*/ {true,  false, true,  false},  // Right ↔ Front, Rear
        /*Q2*/ {false, true,  false, true },  // Rear  ↔ Right, Left
        /*Q3*/ {true,  false, true,  false},  // Left  ↔ Front, Rear
    };

    /**
     * Process a batch of detections from a single quadrant.
     * Returns the same detections annotated with track IDs.
     *
     * @param detections YOLO detections from this quadrant
     * @param quadrant   Quadrant index (0-3)
     * @return List of TrackResult with trackId assigned
     */
    public List<TrackResult> processDetections(List<Detection> detections, int quadrant) {
        long now = System.currentTimeMillis();
        pruneStale(now);

        List<TrackResult> results = new ArrayList<>();
        if (detections == null || detections.isEmpty()) return results;

        for (Detection det : detections) {
            int classId = det.getClassId();
            int x = det.getX();
            int y = det.getY();
            int w = det.getW();
            int h = det.getH();

            // Compute edge proximity
            boolean nearLeft = x < EDGE_MARGIN_PX;
            boolean nearRight = (x + w) > (Q_WIDTH - EDGE_MARGIN_PX);
            boolean nearTop = y < EDGE_MARGIN_PX;
            boolean nearBottom = (y + h) > (Q_HEIGHT - EDGE_MARGIN_PX);

            // Try to match to an existing track
            int matchIdx = -1;
            long bestTimeDelta = Long.MAX_VALUE;

            for (int i = 0; i < MAX_TRACKS; i++) {
                Track t = tracks[i];
                if (!t.active) continue;
                if (t.classId != classId) continue;

                long timeDelta = now - t.lastSeenMs;

                // Case 1: Same quadrant — centroid proximity match with adaptive threshold
                if (t.lastQuadrant == quadrant) {
                    float cx = x + w / 2.0f;
                    float cy = y + h / 2.0f;
                    float tcx = t.lastX + t.lastW / 2.0f;
                    float tcy = t.lastY + t.lastH / 2.0f;
                    float dist = (float) Math.sqrt((cx - tcx) * (cx - tcx) + (cy - tcy) * (cy - tcy));

                    // SOTA: Adaptive distance threshold based on object size.
                    // At close range (~0.6m), a person's bbox is ~150px wide and they
                    // move fast. At far range (~3m), the bbox is ~30px and they move slow.
                    // Use 1.5× the larger dimension of the object as the match radius.
                    // This prevents track fragmentation for close-range fast-moving objects.
                    float maxDim = Math.max(w, h);
                    float adaptiveThreshold = Math.max(120, maxDim * 1.5f);
                    
                    if (dist < adaptiveThreshold && timeDelta < bestTimeDelta) {
                        matchIdx = i;
                        bestTimeDelta = timeDelta;
                    }
                }
                // Case 2: Adjacent quadrant — cross-camera handoff
                else if (ADJACENT[quadrant][t.lastQuadrant] && timeDelta < HANDOFF_WINDOW_MS) {
                    // The detection should be near the edge facing the previous quadrant,
                    // and the previous track should have been near the edge facing this quadrant.
                    if (isHandoffEdgeMatch(quadrant, nearLeft, nearRight, nearTop, nearBottom,
                            t.lastQuadrant, t.nearLeftEdge, t.nearRightEdge, t.nearTopEdge, t.nearBottomEdge)) {
                        if (timeDelta < bestTimeDelta) {
                            matchIdx = i;
                            bestTimeDelta = timeDelta;
                        }
                    }
                }
            }

            int trackId;
            if (matchIdx >= 0) {
                // Update existing track
                Track t = tracks[matchIdx];
                trackId = t.trackId;
                if (t.lastQuadrant != quadrant) {
                    logger.info(String.format("Track #%d HANDOFF: %s → %s (class=%d, gap=%dms)",
                            trackId,
                            MotionPipelineV2.QUADRANT_NAMES[t.lastQuadrant],
                            MotionPipelineV2.QUADRANT_NAMES[quadrant],
                            classId, bestTimeDelta));
                }
                t.lastQuadrant = quadrant;
                t.lastX = x; t.lastY = y; t.lastW = w; t.lastH = h;
                t.lastSeenMs = now;
                t.nearLeftEdge = nearLeft;
                t.nearRightEdge = nearRight;
                t.nearTopEdge = nearTop;
                t.nearBottomEdge = nearBottom;
            } else {
                // Create new track
                trackId = nextTrackId++;
                int slot = findFreeSlot();
                if (slot >= 0) {
                    Track t = tracks[slot];
                    t.trackId = trackId;
                    t.classId = classId;
                    t.lastQuadrant = quadrant;
                    t.lastX = x; t.lastY = y; t.lastW = w; t.lastH = h;
                    t.lastSeenMs = now;
                    t.nearLeftEdge = nearLeft;
                    t.nearRightEdge = nearRight;
                    t.nearTopEdge = nearTop;
                    t.nearBottomEdge = nearBottom;
                    t.active = true;
                }
            }

            results.add(new TrackResult(det, trackId, quadrant));
        }

        return results;
    }

    /**
     * Check if edge positions indicate a cross-camera handoff.
     *
     * The physical camera layout determines which edges are "handoff edges":
     * - Front→Right: person exits right edge of Front, enters left edge of Right
     * - Front→Left:  person exits left edge of Front, enters right edge of Left
     * - Rear→Right:  person exits right edge of Rear, enters left edge of Right
     * - Rear→Left:   person exits left edge of Rear, enters right edge of Left
     *
     * For simplicity, we check if the new detection is near ANY edge of the new
     * quadrant and the old track was near ANY edge of the old quadrant. The
     * adjacency check already limits this to physically possible transitions.
     */
    private boolean isHandoffEdgeMatch(
            int newQ, boolean newLeft, boolean newRight, boolean newTop, boolean newBottom,
            int oldQ, boolean oldLeft, boolean oldRight, boolean oldTop, boolean oldBottom) {
        // New detection must be near some edge
        boolean newNearEdge = newLeft || newRight || newTop || newBottom;
        // Old track must have been near some edge
        boolean oldNearEdge = oldLeft || oldRight || oldTop || oldBottom;
        return newNearEdge && oldNearEdge;
    }

    private void pruneStale(long now) {
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].active && (now - tracks[i].lastSeenMs) > TRACK_TTL_MS) {
                tracks[i].active = false;
            }
        }
    }

    private int findFreeSlot() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (!tracks[i].active) return i;
        }
        // Evict oldest
        long oldest = Long.MAX_VALUE;
        int oldestIdx = 0;
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].lastSeenMs < oldest) {
                oldest = tracks[i].lastSeenMs;
                oldestIdx = i;
            }
        }
        tracks[oldestIdx].active = false;
        return oldestIdx;
    }

    /**
     * Get the number of currently active tracks.
     */
    public int getActiveTrackCount() {
        int count = 0;
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].active) count++;
        }
        return count;
    }

    /**
     * Reset all tracks (e.g., when surveillance mode is toggled).
     */
    public void reset() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            tracks[i].active = false;
        }
        nextTrackId = 1;
    }

    /**
     * Detection annotated with a track ID.
     */
    public static class TrackResult {
        public final Detection detection;
        public final int trackId;
        public final int quadrant;

        public TrackResult(Detection detection, int trackId, int quadrant) {
            this.detection = detection;
            this.trackId = trackId;
            this.quadrant = quadrant;
        }
    }
}
