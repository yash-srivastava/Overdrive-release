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

    // Foot-point anchor match — a parked vehicle's wheels-on-ground point is
    // stable across YOLO localisation noise (bbox top jitters more than bbox
    // bottom because the top edge of a car is partially occluded by sky/sun
    // glare, while the wheels are anchored). When IoU drops below MATCH_IOU
    // due to a slight bbox reshape, fall back to "same class + foot-point
    // within FOOTPOINT_MATCH_DIST" as a secondary match path.
    //
    // 4% (was 6%): two cars parked side-by-side at typical urban spacing
    // are ~10% apart in foot-point; 6% tolerance was producing occasional
    // ping-pong matches where the entry's bbox would oscillate between
    // them frame-to-frame, leaving the spot of the car that left
    // unprotected when the other car was still present.
    private static final float FOOTPOINT_MATCH_DIST_NORM = 0.04f;
    // Additional bbox-area discriminator on the foot-point path: a true
    // same-physical-vehicle observation has bbox area within ±15%; two
    // adjacent vehicles often differ by 20%+ even when same class. Stops
    // the merge path 2 from incorrectly matching one car onto another.
    private static final float FOOTPOINT_AREA_RATIO_MAX = 1.15f;

    // Promotion threshold — how many consistent observations before an entry
    // is trusted as baseline. Single-frame promotions are noisy (fresh tracks,
    // YOLO hallucinations on glare). Three confirmations means the object has
    // been there for ~300 ms at 10 fps, which empirically eliminates one-off
    // phantoms while keeping the latency bounded.
    private static final int CONFIRMED_HIT_COUNT = 3;

    // Maximum time since last observation before an entry is considered a
    // "ghost" and removed. Handles the scenario: a car parks, gets added to
    // baseline, then leaves. Without expiry, the ghost entry persists and
    // suppresses the next car that parks in the same spot.
    //
    // We measure from {@code lastSeenMs}, not {@code addedAtMs}. A continuously
    // refreshed parked car keeps lastSeenMs fresh on every motion event near
    // it, so it never expires while genuinely present. Only when the car
    // physically leaves and matchAndRefresh stops updating it does the
    // lastSeenMs gap exceed this threshold and the entry get evicted.
    //
    // Two hours is the maximum gap between motion events we expect for a
    // genuinely-parked car (e.g. early morning, no nearby movement). Anything
    // longer and we're better off treating the spot as fresh.
    private static final long BASELINE_ENTRY_MAX_AGE_MS = 2 * 60 * 60 * 1000L;  // 2 hours

    // COCO class IDs that are NEVER promotable (living things). A living
    // thing must never become "static background", because — like a
    // motionless person — a motionless animal is still a subject of interest
    // and must keep triggering when detection is enabled. This covers
    // 0=person plus the full COCO animal range 14-23 (bird, cat, dog, horse,
    // sheep, cow, elephant, bear, zebra, giraffe). Note: when the user has
    // animal detection disabled (default), YOLO never returns 14-23, so the
    // 17-23 additions are a no-op for the default configuration.
    private static final int[] NEVER_PROMOTE_CLASSES = {0, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};

    /**
     * Map a COCO class ID to its canonical baseline class. YOLO occasionally
     * flip-flops between {car, bus, truck} (2/5/7) for the same physical
     * vehicle from frame to frame, and similarly between {bicycle, motorcycle}
     * (1/3). Strict classId matching would create twin unconfirmed entries
     * that never accumulate enough hits to confirm. Collapsing to one
     * canonical class — same way Actor.ClassGroup does — fixes that.
     */
    private static int canonicalClass(int classId) {
        if (classId == 5 || classId == 7) return 2;   // bus, truck → car
        if (classId == 3) return 1;                   // motorcycle → bicycle
        return classId;
    }

    /**
     * A single baseline entry: a known static object in the scene.
     *
     * `hitCount` and `lastSeenMs` are mutable to support the unconfirmed →
     * confirmed promotion path (an entry must be seen at least
     * {@link #CONFIRMED_HIT_COUNT} times before {@link #isInBaseline} treats
     * it as authoritative).
     */
    public static class Entry {
        /**
         * Process-wide monotonic identity. Entries are otherwise identified by
         * object reference only; observers (Parking Intelligence's neighbour
         * timeline) need a stable key that survives the copy they receive.
         * Purely additive — no baseline logic reads it.
         */
        public final long entryId = ENTRY_IDS.incrementAndGet();
        public final int classId;
        public float cx, cy, w, h;  // Normalized to quadrant dimensions
        public long addedAtMs;
        public long lastSeenMs;
        public int hitCount;
        public final int quadrant;
        /**
         * True iff this entry was CREATED by a live motion event (mid-event
         * {@link #promoteStaticActor} or event-end {@link #updateFromEventEnd})
         * — i.e. the object ARRIVED while sentry was watching. False for the
         * sentry-start seed and the lighting-transition refresh, whose objects
         * were already present when observation began. Consumed by the
         * post-park vigilance channel ({@link #hasFreshParkedVehicleNear}):
         * only a vehicle that was WATCHED arriving is a "fresh parker" whose
         * occupants are expected to exit shortly. Never flips true later —
         * refresh paths only bump hits on the existing entry.
         */
        public boolean fromLiveEvent = false;

        public Entry(int classId, float cx, float cy, float w, float h, int quadrant) {
            this.classId = classId;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.addedAtMs = System.currentTimeMillis();
            this.lastSeenMs = this.addedAtMs;
            this.hitCount = 1;
            this.quadrant = quadrant;
        }

        public boolean isConfirmed() {
            return hitCount >= CONFIRMED_HIT_COUNT;
        }

        /** Bottom-centre Y in normalized coords — the foot-point anchor. */
        public float footY() { return cy + h / 2.0f; }
        public float footX() { return cx; }
    }

    private static final java.util.concurrent.atomic.AtomicLong ENTRY_IDS =
            new java.util.concurrent.atomic.AtomicLong();

    // ==================== READ-ONLY OBSERVER (Parking Intelligence) ====================
    //
    // Purely additive. The baseline's own behaviour is byte-identical whether
    // or not a listener is installed: every fire site is a volatile null check
    // inside the existing monitor, and the observer only ever receives
    // immutable COPIES of entries. Because the mutators are synchronized (and
    // updateFromEventEnd already runs under the engine's recordingLifecycleLock),
    // a listener MUST hand off to its own executor and return immediately —
    // it is never allowed to block, take locks, or call back into this class.
    // Every dispatch is wrapped in catch(Throwable) so an observer defect can
    // never disturb suppression (DETECTION-INVARIANTS prime directive: this
    // path stays FP-neutral).

    /** Immutable copy of an {@link Entry} at the moment of the callback. */
    public static final class EntrySnapshot {
        public final long entryId;
        public final int quadrant;
        public final int classId;
        /** Coarse group of {@link #classId} (Actor.ClassGroup name). */
        public final String classGroup;
        public final float cx, cy, w, h;
        public final long addedAtMs;
        public final long lastSeenMs;
        public final int hitCount;
        public final boolean confirmed;
        public final boolean fromLiveEvent;

        EntrySnapshot(Entry e) {
            this.entryId = e.entryId;
            this.quadrant = e.quadrant;
            this.classId = e.classId;
            this.classGroup = Actor.groupOf(e.classId).name();
            this.cx = e.cx; this.cy = e.cy; this.w = e.w; this.h = e.h;
            this.addedAtMs = e.addedAtMs;
            this.lastSeenMs = e.lastSeenMs;
            this.hitCount = e.hitCount;
            this.confirmed = e.isConfirmed();
            this.fromLiveEvent = e.fromLiveEvent;
        }

        /** Synthetic snapshot (JVM tests / replay tools). No baseline logic uses this. */
        public EntrySnapshot(long entryId, int quadrant, int classId, String classGroup,
                             float cx, float cy, float w, float h,
                             long addedAtMs, long lastSeenMs, int hitCount,
                             boolean confirmed, boolean fromLiveEvent) {
            this.entryId = entryId;
            this.quadrant = quadrant;
            this.classId = classId;
            this.classGroup = classGroup;
            this.cx = cx; this.cy = cy; this.w = w; this.h = h;
            this.addedAtMs = addedAtMs;
            this.lastSeenMs = lastSeenMs;
            this.hitCount = hitCount;
            this.confirmed = confirmed;
            this.fromLiveEvent = fromLiveEvent;
        }
    }

    /** Non-blocking observer contract (see class comment above). */
    public interface Listener {
        /** A quadrant was (re)seeded at sentry start; {@code entries} are the new confirmed set. */
        void onEntrySeeded(int quadrant, List<EntrySnapshot> entries);
        /** {@code source}: "event_end" | "promote" | "lighting". Seeded entries use onEntrySeeded. */
        void onEntryAdded(EntrySnapshot entry, String source);
        /** An unconfirmed live-event entry just crossed the confirmation threshold. */
        void onEntryConfirmed(EntrySnapshot entry);
        /** {@code reason}: "expired" | "reseed" | "reset". */
        void onEntryRetired(EntrySnapshot entry, String reason);
    }

    private static volatile Listener globalListener;

    /** Install (or clear with null) the process-wide observer. */
    public static void setGlobalListener(Listener listener) {
        globalListener = listener;
    }

    private static void fireAdded(Entry e, String source) {
        Listener l = globalListener;
        if (l == null) return;
        try { l.onEntryAdded(new EntrySnapshot(e), source); } catch (Throwable ignored) {}
    }

    private static void fireConfirmed(Entry e) {
        Listener l = globalListener;
        if (l == null) return;
        try { l.onEntryConfirmed(new EntrySnapshot(e)); } catch (Throwable ignored) {}
    }

    private static void fireRetired(List<Entry> entries, String reason) {
        Listener l = globalListener;
        if (l == null || entries == null || entries.isEmpty()) return;
        for (Entry e : entries) {
            try { l.onEntryRetired(new EntrySnapshot(e), reason); } catch (Throwable ignored) {}
        }
    }

    private static void fireSeeded(int quadrant, List<Entry> entries) {
        Listener l = globalListener;
        if (l == null) return;
        try {
            List<EntrySnapshot> copies = new ArrayList<>(entries.size());
            for (Entry e : entries) copies.add(new EntrySnapshot(e));
            l.onEntrySeeded(quadrant, copies);
        } catch (Throwable ignored) {}
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
    public synchronized void seedFromDetections(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;

        // Observer: everything previously known in this quadrant is retired by
        // the reseed. Copy-then-fire so the list mutation below is unchanged.
        if (globalListener != null && !baselines[quadrant].isEmpty()) {
            fireRetired(new ArrayList<>(baselines[quadrant]), "reseed");
        }
        baselines[quadrant].clear();
        if (detections == null) {
            fireSeeded(quadrant, baselines[quadrant]);
            return;
        }

        for (Detection det : detections) {
            if (det.getConfidence() < MIN_BASELINE_CONFIDENCE) continue;
            if (isNeverPromoteClass(det.getClassId())) continue;

            float cx = (det.getX() + det.getW() / 2.0f) / quadW;
            float cy = (det.getY() + det.getH() / 2.0f) / quadH;
            float w = (float) det.getW() / quadW;
            float h = (float) det.getH() / quadH;

            // Symmetry with matchAndRefresh / isInBaseline: dedupe NMS leftovers
            // where YOLO emits two near-identical detections of the same physical
            // object. Without this, two near-identical entries get seeded and the
            // refresh path then plays merge-or-split games. The 4% foot-point +
            // 1.15× area discriminator is the same one used by event-time logic.
            int canonical = canonicalClass(det.getClassId());
            boolean duplicate = false;
            for (Entry existing : baselines[quadrant]) {
                if (canonicalClass(existing.classId) != canonical) continue;
                float dx = existing.cx - cx;
                float dy = existing.cy - cy;
                if (Math.sqrt(dx * dx + dy * dy) > FOOTPOINT_MATCH_DIST_NORM) continue;
                float existingArea = existing.w * existing.h;
                float detArea = w * h;
                float areaRatio = existingArea > 0 && detArea > 0
                        ? Math.max(detArea / existingArea, existingArea / detArea)
                        : 99f;
                if (areaRatio <= FOOTPOINT_AREA_RATIO_MAX) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;

            // Seeding is the only path that should produce immediately-confirmed
            // entries: at sentry start the user expects everything currently
            // visible to be considered "background" without waiting for hits to
            // accumulate. Pre-populate hitCount to CONFIRMED_HIT_COUNT.
            Entry e = new Entry(det.getClassId(), cx, cy, w, h, quadrant);
            e.hitCount = CONFIRMED_HIT_COUNT;
            baselines[quadrant].add(e);
        }

        logger.info("Baseline seeded for Q" + quadrant + ": " + baselines[quadrant].size() + " entries (confirmed)");
        fireSeeded(quadrant, baselines[quadrant]);
    }

    // ==================== FILTERING ====================

    /**
     * Checks if a detection matches an existing baseline entry.
     * Returns true if the detection is a known static object (should be suppressed).
     */
    public synchronized boolean isInBaseline(Detection det, int quadrant, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return false;

        float detCx = (det.getX() + det.getW() / 2.0f) / quadW;
        float detCy = (det.getY() + det.getH() / 2.0f) / quadH;
        float detW = (float) det.getW() / quadW;
        float detH = (float) det.getH() / quadH;
        float detFootX = detCx;
        float detFootY = detCy + detH / 2.0f;

        // Occlusion-aware tolerance: when a recent person detection overlaps
        // with this bbox, the foot-point and size of the underlying static
        // object can shift significantly because YOLO is now seeing a hybrid
        // shape (e.g. car bbox now ends at the person's feet instead of the
        // car's). Without compensation the foot-point match fails and the
        // car gets treated as a "new" object → unconfirmed → would trigger a
        // false recording on the next event. While a person is occluding,
        // relax the foot-point distance ~3× and skip the bbox-size sanity
        // check, since both signals are corrupted by the occluder.
        boolean occludedByPerson = anyRecentPersonOverlaps(quadrant, detCx, detCy, detW, detH);
        float footDistTolerance = occludedByPerson
                ? FOOTPOINT_MATCH_DIST_NORM * 3.0f
                : FOOTPOINT_MATCH_DIST_NORM;

        long now = System.currentTimeMillis();
        Iterator<Entry> iter = baselines[quadrant].iterator();
        while (iter.hasNext()) {
            Entry entry = iter.next();
            // Expire stale entries — measured from lastSeenMs so a continuously
            // refreshed parked car never times out while present.
            if (now - entry.lastSeenMs > BASELINE_ENTRY_MAX_AGE_MS) {
                iter.remove();
                if (globalListener != null) {
                    fireRetired(java.util.Collections.singletonList(entry), "expired");
                }
                continue;
            }
            // Unconfirmed entries don't suppress yet — they need to accrue
            // hits via observe()/updateFromEventEnd() first. This keeps the
            // baseline conservative: a single noisy YOLO frame can't put
            // something into "trust me, suppress this" state.
            if (!entry.isConfirmed()) continue;
            // Canonical class match — bus/truck/car all match each other,
            // bicycle/motorcycle likewise. Defeats YOLO frame-to-frame class
            // oscillation that would otherwise create twin unconfirmed entries.
            if (canonicalClass(entry.classId) != canonicalClass(det.getClassId())) continue;

            // Path 1: bbox IoU match (the original strict check).
            float iou = computeIoU(detCx, detCy, detW, detH, entry.cx, entry.cy, entry.w, entry.h);
            if (iou >= MATCH_IOU_THRESHOLD) return true;

            // Path 2: foot-point anchor match. A parked car whose top edge
            // jitters between events still has its wheels on the ground in
            // the same place. If the foot-point is within tolerance and the
            // bbox sizes are within ±30%, accept as the same physical object
            // even if IoU dropped. Occlusion path: relax distance + skip
            // size sanity (see occludedByPerson above).
            float dx = detFootX - entry.footX();
            float dy = detFootY - entry.footY();
            float footDist = (float) Math.sqrt(dx * dx + dy * dy);
            if (footDist <= footDistTolerance) {
                if (occludedByPerson) {
                    return true;
                }
                float widthRatio  = entry.w > 0 ? Math.max(detW / entry.w, entry.w / detW)  : 99f;
                float heightRatio = entry.h > 0 ? Math.max(detH / entry.h, entry.h / detH)  : 99f;
                // Area-ratio discriminator (FIX A10) — see matchAndRefresh.
                float areaRatio = (entry.w * entry.h) > 0
                        ? Math.max((detW * detH) / (entry.w * entry.h),
                                   (entry.w * entry.h) / (detW * detH))
                        : 99f;
                if (widthRatio <= 1.30f && heightRatio <= 1.30f
                        && areaRatio <= FOOTPOINT_AREA_RATIO_MAX) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether any recent person detection in this quadrant overlaps the given
     * bbox enough to corrupt YOLO's localisation of an underlying static
     * object. Uses a *much* lower IoU threshold than {@link #SPATIAL_VETO_IOU_THRESHOLD}
     * because here we're asking "is there any person near enough to throw the
     * bbox off", not "is this detection probably a person".
     */
    private boolean anyRecentPersonOverlaps(int quadrant, float cx, float cy, float w, float h) {
        // Tight time window: 2 seconds. The 60-second backlog in
        // recordPersonDetection() is for the spatial veto on baseline promotion
        // (a different decision). For relaxing match tolerance on a CURRENT
        // detection, we only want a person who is actively occluding RIGHT NOW
        // — otherwise a stale 30-second-old person record could relax the
        // tolerance enough to falsely suppress a brand-new car parking in the
        // same spot a previous car just left.
        long stalenessCutoff = System.currentTimeMillis() - 2000;
        for (PersonRecord p : recentPersons[quadrant]) {
            if (p.timestampMs < stalenessCutoff) continue;
            if (computeIoU(cx, cy, w, h, p.cx, p.cy, p.w, p.h) > 0.05f) {
                return true;
            }
        }
        return false;
    }

    // ==================== SHADOW DIAGNOSTICS (log-only) ====================

    /**
     * SHADOW-MODE diagnostic for the containment-suppression candidate
     * (parked-car split-box FP audit). For a detection that just BYPASSED or
     * PASSED the baseline, report the CONFIRMED entry that overlaps it most
     * by CONTAINMENT (intersection area / detection area) — any class,
     * because the two FP signatures under investigation differ:
     * <ul>
     *   <li><b>nonperson-pass</b>: a split/reshaped box of a parked car
     *       passes when IoU &lt; {@link #MATCH_IOU_THRESHOLD} and the
     *       foot-point path fails (centroid shift &gt; 4%, w/h ratio &gt;
     *       1.30, or area ratio &gt; 1.15) — but the fragment lies INSIDE
     *       the stored full-car box, so containment stays near 1.0.
     *       Candidate rule under validation: contain &gt;= 0.80 AND same
     *       canonical class =&gt; suppress.</li>
     *   <li><b>person-bypass</b>: part of a parked car misread as a person
     *       bypasses the baseline BY DESIGN (class 0 is never suppressed);
     *       high containment vs a DIFFERENT-class (vehicle) entry is the
     *       misclassification signature.</li>
     * </ul>
     * Purely observational: mutates nothing (stale entries are skipped, not
     * evicted), suppresses nothing. ALWAYS returns a line for a valid call
     * (review fix): a silent no-overlap case would right-censor the
     * containment distribution — "contain=0" and "no baseline to compare
     * against" are data, not noise. Two slots are reported:
     * <ul>
     *   <li>{@code same{...}} — the best SAME-canonical-class entry (by
     *       containment; among zero-overlap entries, nearest foot-point).
     *       This is the entry the candidate suppression rule would score, so
     *       it must never be masked by a larger cross-class overlap (review
     *       fix). Absent for person detections (class 0 is never promoted).</li>
     *   <li>{@code any{...}} / {@code bestOther{...}} — the best entry across
     *       ALL classes when it overlaps more than the same-class one (or
     *       when no same-class entry exists — the person-bypass signature is
     *       precisely a person box sitting on a stored vehicle entry).</li>
     * </ul>
     */
    public synchronized String shadowContainmentDiag(Detection det, int quadrant,
                                                     int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return null;
        if (quadW <= 0 || quadH <= 0) return null;

        float detCx = (det.getX() + det.getW() / 2.0f) / quadW;
        float detCy = (det.getY() + det.getH() / 2.0f) / quadH;
        float detW = (float) det.getW() / quadW;
        float detH = (float) det.getH() / quadH;
        float detFootX = detCx;
        float detFootY = detCy + detH / 2.0f;
        int detCanonical = canonicalClass(det.getClassId());

        long now = System.currentTimeMillis();
        int eligible = 0;
        Entry bestSame = null;
        float bestSameContain = -1f;           // -1 so a zero-overlap same-class entry still selects
        float bestSameFoot = Float.MAX_VALUE;
        Entry bestAny = null;
        float bestAnyContain = 0f;             // strictly-> so bestAny != null implies real overlap
        for (Entry entry : baselines[quadrant]) {
            // Mirror isInBaseline's eligibility (confirmed + not stale) but
            // WITHOUT the eviction side-effect — diagnostics must not mutate.
            if (!entry.isConfirmed()) continue;
            if (now - entry.lastSeenMs > BASELINE_ENTRY_MAX_AGE_MS) continue;
            eligible++;
            float contain = containmentFrac(detCx, detCy, detW, detH,
                    entry.cx, entry.cy, entry.w, entry.h);
            if (contain > bestAnyContain) {
                bestAnyContain = contain;
                bestAny = entry;
            }
            if (canonicalClass(entry.classId) == detCanonical) {
                float dxs = detFootX - entry.footX();
                float dys = detFootY - entry.footY();
                float foot = (float) Math.sqrt(dxs * dxs + dys * dys);
                // Selection: containment first; among equal (typically zero)
                // containment, nearest foot-point — "how far from the known
                // object was this box" is the useful datum when nothing
                // overlaps.
                if (contain > bestSameContain
                        || (contain == bestSameContain && foot < bestSameFoot)) {
                    bestSame = entry;
                    bestSameContain = contain;
                    bestSameFoot = foot;
                }
            }
        }

        StringBuilder sb = new StringBuilder(192);
        sb.append(String.format(java.util.Locale.US,
                "det{cls=%d conf=%.2f c=%.3f,%.3f wh=%.3fx%.3f}",
                det.getClassId(), det.getConfidence(), detCx, detCy, detW, detH));
        if (eligible == 0) {
            // Still a line — "no confirmed baseline here" must appear in the
            // distribution, not vanish from it.
            return sb.append(" entries=0").toString();
        }
        if (bestSame != null) {
            sb.append(" same").append(entryDiag(bestSame,
                    Math.max(bestSameContain, 0f),
                    detCx, detCy, detW, detH, detFootX, detFootY));
            // Masking guard (review fix): the same-class slot is what the
            // candidate rule scores, but a different-class entry may overlap
            // MORE — surface it so cross-class containment can't hide the
            // same-class value.
            if (bestAny != null && bestAny != bestSame
                    && bestAnyContain > bestSameContain) {
                sb.append(String.format(java.util.Locale.US,
                        " bestOther{cls=%d contain=%.2f}",
                        bestAny.classId, bestAnyContain));
            }
        } else if (bestAny != null) {
            // No same-class entries (always the case for person-bypass —
            // class 0 is never promoted): the best cross-class overlap IS the
            // misclassification signature, so report its full geometry.
            sb.append(" any").append(entryDiag(bestAny, bestAnyContain,
                    detCx, detCy, detW, detH, detFootX, detFootY));
        } else {
            sb.append(" any{noOverlap entries=").append(eligible).append('}');
        }
        return sb.toString();
    }

    /** One entry's geometry vs the detection, for the shadow log. */
    private static String entryDiag(Entry entry, float contain,
                                    float detCx, float detCy, float detW, float detH,
                                    float detFootX, float detFootY) {
        float iou = computeIoU(detCx, detCy, detW, detH,
                entry.cx, entry.cy, entry.w, entry.h);
        float dx = detFootX - entry.footX();
        float dy = detFootY - entry.footY();
        float footDist = (float) Math.sqrt(dx * dx + dy * dy);
        float wR = entry.w > 0 && detW > 0 ? Math.max(detW / entry.w, entry.w / detW) : 99f;
        float hR = entry.h > 0 && detH > 0 ? Math.max(detH / entry.h, entry.h / detH) : 99f;
        float aR = (entry.w * entry.h) > 0 && (detW * detH) > 0
                ? Math.max((detW * detH) / (entry.w * entry.h),
                           (entry.w * entry.h) / (detW * detH))
                : 99f;
        return String.format(java.util.Locale.US,
                "{cls=%d hits=%d contain=%.2f iou=%.2f footDist=%.3f wR=%.2f hR=%.2f aR=%.2f}",
                entry.classId, entry.hitCount, contain, iou, footDist, wR, hR, aR);
    }

    /** Fraction of the FIRST box's area covered by the second (both in
     *  center + dims form). 1.0 = the detection lies entirely inside the
     *  entry — the split-box signature computeIoU misses because the union
     *  in its denominator is dominated by the full-car entry. */
    private static float containmentFrac(float cx1, float cy1, float w1, float h1,
                                         float cx2, float cy2, float w2, float h2) {
        float l1 = cx1 - w1 / 2f, t1 = cy1 - h1 / 2f;
        float r1 = cx1 + w1 / 2f, b1 = cy1 + h1 / 2f;
        float l2 = cx2 - w2 / 2f, t2 = cy2 - h2 / 2f;
        float r2 = cx2 + w2 / 2f, b2 = cy2 + h2 / 2f;
        float iw = Math.min(r1, r2) - Math.max(l1, l2);
        float ih = Math.min(b1, b2) - Math.max(t1, t2);
        if (iw <= 0f || ih <= 0f) return 0f;
        float detArea = w1 * h1;
        return detArea > 0f ? (iw * ih) / detArea : 0f;
    }

    // ==================== EVENT-DRIVEN UPDATE ====================

    /**
     * Records a person detection for spatial veto tracking.
     * Called during YOLO processing whenever a person is detected.
     */
    public synchronized void recordPersonDetection(int quadrant, Detection det, int quadW, int quadH) {
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
    public synchronized void updateFromEventEnd(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;
        if (detections == null || detections.isEmpty()) return;

        int added = 0;
        int refreshed = 0;
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

            // Rule 3: Match-or-create.
            //   - If a matching entry exists (IoU OR foot-point anchor),
            //     refresh its position + bump hitCount.
            //   - Otherwise create a fresh unconfirmed entry. It needs
            //     CONFIRMED_HIT_COUNT observations before isInBaseline trusts it.
            if (matchAndRefresh(quadrant, det.getClassId(), cx, cy, w, h)) {
                refreshed++;
            } else {
                Entry e = new Entry(det.getClassId(), cx, cy, w, h, quadrant);
                e.fromLiveEvent = true;  // arrived while sentry was watching
                baselines[quadrant].add(e);
                added++;
                fireAdded(e, "event_end");
            }
        }

        if (added > 0 || refreshed > 0 || vetoed > 0) {
            logger.info("Baseline update Q" + quadrant + ": +" + added + " new, " + refreshed +
                    " refreshed, " + vetoed + " vetoed (spatial), " + skippedLiving +
                    " skipped (living), total=" + baselines[quadrant].size());
        }

        // Clear recent person records for this quadrant (event is over)
        recentPersons[quadrant].clear();
    }

    /**
     * Promote a confirmed-static Actor detection straight into the baseline
     * mid-event. The Actor layer's static gate kicks in after 2 frames for
     * vehicles / 8 frames for persons-bikes (which are NEVER promoted because
     * of the living-things rule). For static vehicles, the Actor layer
     * already says "this isn't moving", so we mirror that into the baseline
     * without waiting for {@link #updateFromEventEnd} at recording-stop —
     * the next event, even moments later, suppresses this object.
     *
     * Idempotent and cheap: if the entry already exists, just bumps hits.
     */
    public synchronized void promoteStaticActor(int quadrant, int classId,
                                   int x, int y, int bw, int bh,
                                   int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;
        if (isNeverPromoteClass(classId)) return;
        if (quadW <= 0 || quadH <= 0) return;

        float cx = (x + bw / 2.0f) / quadW;
        float cy = (y + bh / 2.0f) / quadH;
        float w  = (float) bw / quadW;
        float h  = (float) bh / quadH;

        // Same spatial veto as updateFromEventEnd — don't promote anything
        // that overlapped a recent person detection.
        if (overlapsRecentPerson(quadrant, cx, cy, w, h)) return;

        if (!matchAndRefresh(quadrant, classId, cx, cy, w, h)) {
            Entry e = new Entry(classId, cx, cy, w, h, quadrant);
            e.fromLiveEvent = true;  // arrived while sentry was watching
            baselines[quadrant].add(e);
            fireAdded(e, "promote");
        }
    }

    // ==================== POST-PARK VIGILANCE ANCHOR ====================

    /**
     * Whether a FRESH PARKER anchor exists near a point: a CONFIRMED vehicle
     * entry that was created BY A LIVE EVENT (watched arriving — never the
     * sentry-start seed or a lighting refresh) within {@code maxAgeMs}, whose
     * foot-point is within {@code radiusNorm} of ({@code nx},{@code ny}) in
     * normalized quadrant coordinates.
     *
     * <p>Consumed by SurveillanceEngineGpu's post-park vigilance channel: a
     * person exiting a just-parked car is a high-prior event, but the parked
     * car itself is now baseline-suppressed, so the exit sequence's only class
     * evidence is a YOLO person box that fisheye/occlusion frequently denies.
     * This anchor lets motion AT that specific spot earn a lowered trigger bar
     * and an exemption from the far-unconfirmed gate — evidence-scoped (I3):
     * only this spot, only this quadrant, only while the entry is young.
     *
     * <p>FP posture (I9 — an adding-only channel fails CLOSED): confirmed
     * entries only, so a single-frame YOLO hallucination can never open a
     * vigilance zone; a parking event supplies the hits naturally via
     * promoteStaticActor. Any error → false (no anchor, no lowered bar).
     *
     * <p>Coordinate-space note: callers pass QUADRANT-normalized coords. The
     * anchors they match are quadrant-space too, because vehicle entries only
     * reach confirmed status via the mosaic-space paths — promoteStaticActor
     * skips foveated frames outright, and a foveated event-end entry's
     * crop-window coords "don't refer to a stable physical region" (see the
     * promote call site), so it never accrues the hits to confirm. An
     * unconfirmable foveated entry is therefore inert here — fail-closed.
     */
    public synchronized boolean hasFreshParkedVehicleNear(int quadrant,
            float nx, float ny, float radiusNorm, long maxAgeMs) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return false;
        long now = System.currentTimeMillis();
        for (Entry e : baselines[quadrant]) {
            if (!e.fromLiveEvent || !e.isConfirmed()) continue;
            int canonical = canonicalClass(e.classId);
            if (canonical != 2 && canonical != 1) continue;  // vehicles only (car/bus/truck, bike/moto)
            long age = now - e.addedAtMs;
            if (age < 0 || age > maxAgeMs) continue;
            float dx = nx - e.footX();
            float dy = ny - e.footY();
            if (Math.sqrt(dx * dx + dy * dy) <= radiusNorm) return true;
        }
        return false;
    }

    /**
     * Find an existing entry that matches the given bbox (IoU primary, foot-
     * point fallback). On match: update its position to the freshest bbox,
     * stamp lastSeenMs, increment hitCount. Returns whether a match happened.
     *
     * Position is updated (not just refreshed) so the entry tracks slight
     * shifts in the same physical object's bbox over time without losing its
     * accumulated hitCount.
     */
    private boolean matchAndRefresh(int quadrant, int classId,
                                    float cx, float cy, float w, float h) {
        float footX = cx;
        float footY = cy + h / 2.0f;
        long now = System.currentTimeMillis();
        int canonicalIncoming = canonicalClass(classId);
        for (Entry entry : baselines[quadrant]) {
            if (canonicalClass(entry.classId) != canonicalIncoming) continue;
            boolean isMatch = false;
            // IoU path
            float iou = computeIoU(cx, cy, w, h, entry.cx, entry.cy, entry.w, entry.h);
            if (iou >= MATCH_IOU_THRESHOLD) {
                isMatch = true;
            } else {
                // Foot-point path
                float dx = footX - entry.footX();
                float dy = footY - entry.footY();
                if (Math.sqrt(dx * dx + dy * dy) <= FOOTPOINT_MATCH_DIST_NORM) {
                    float wRatio = entry.w > 0 ? Math.max(w / entry.w, entry.w / w) : 99f;
                    float hRatio = entry.h > 0 ? Math.max(h / entry.h, entry.h / h) : 99f;
                    // Additional area-ratio check (FIX A10) — discriminates
                    // same-physical-vehicle from adjacent-vehicle merge.
                    float areaRatio = (entry.w * entry.h) > 0
                            ? Math.max((w * h) / (entry.w * entry.h),
                                       (entry.w * entry.h) / (w * h))
                            : 99f;
                    if (wRatio <= 1.30f && hRatio <= 1.30f
                            && areaRatio <= FOOTPOINT_AREA_RATIO_MAX) {
                        isMatch = true;
                    }
                }
            }
            if (isMatch) {
                entry.cx = cx; entry.cy = cy; entry.w = w; entry.h = h;
                entry.lastSeenMs = now;
                boolean wasConfirmed = entry.isConfirmed();
                if (entry.hitCount < Integer.MAX_VALUE - 1) entry.hitCount++;
                if (!wasConfirmed && entry.isConfirmed()) fireConfirmed(entry);
                return true;
            }
        }
        return false;
    }

    // ==================== LIGHTING TRANSITION REFRESH ====================

    /**
     * Refresh a quadrant's baseline on a lighting transition (dawn/dusk).
     * 
     * MERGE strategy (not replace): Keep existing entries and add/update from
     * the fresh scan. This prevents losing entries that YOLO can't see under
     * the new lighting conditions (e.g., a dark car invisible at night that was
     * visible during the day). Lost entries would cause false triggers when
     * headlights later illuminate the "invisible" car.
     *
     * Rules:
     * - New detections not in baseline → add them
     * - Existing entries that match a fresh detection → refresh timestamp
     * - Existing entries with no match → keep (YOLO may not see them in new lighting)
     * - Never promote living things
     * - Expired entries (>2 hours) are cleaned up by isInBaseline() lazily
     *
     * Called when auto night/day mode switches (luma crosses threshold).
     * Cost: 1 inference per quadrant, happens 2-3 times per night.
     */
    public synchronized void refreshQuadrant(int quadrant, List<Detection> detections, int quadW, int quadH) {
        if (quadrant < 0 || quadrant >= NUM_QUADRANTS) return;

        if (detections == null || detections.isEmpty()) {
            // YOLO found nothing — keep existing baseline intact.
            // At night, YOLO often returns empty on dark scenes. Flushing the
            // baseline here would cause every previously-known static object to
            // trigger a false recording the next time it becomes visible.
            logger.info("Baseline refresh Q" + quadrant + ": YOLO returned empty, keeping " +
                    baselines[quadrant].size() + " existing entries");
            return;
        }

        int added = 0;
        int refreshed = 0;

        for (Detection det : detections) {
            if (det.getConfidence() < MIN_BASELINE_CONFIDENCE) continue;
            if (isNeverPromoteClass(det.getClassId())) continue;

            float cx = (det.getX() + det.getW() / 2.0f) / quadW;
            float cy = (det.getY() + det.getH() / 2.0f) / quadH;
            float w = (float) det.getW() / quadW;
            float h = (float) det.getH() / quadH;

            // FIX (A7): apply the same spatial veto used by updateFromEventEnd
            // and promoteStaticActor. Without it, a vehicle bbox at dawn that
            // overlaps a still-active person detection could be promoted to
            // confirmed status, and any future occlusion of that vehicle by
            // the same person would be silently suppressed.
            if (overlapsRecentPerson(quadrant, cx, cy, w, h)) continue;

            if (matchAndRefresh(quadrant, det.getClassId(), cx, cy, w, h)) {
                refreshed++;
            } else {
                // Lighting transitions are a trusted seeding moment; new entries
                // arriving in this path are immediately-confirmed (same as the
                // initial seedFromDetections path). Otherwise newly-revealed
                // objects at dawn would have to be observed K more times before
                // the next motion event would suppress them.
                Entry e = new Entry(det.getClassId(), cx, cy, w, h, quadrant);
                e.hitCount = CONFIRMED_HIT_COUNT;
                baselines[quadrant].add(e);
                added++;
                fireAdded(e, "lighting");
            }
        }

        logger.info("Baseline refresh Q" + quadrant + " (lighting transition): +" + added +
                " new, " + refreshed + " refreshed, " + baselines[quadrant].size() + " total");
    }

    // ==================== RESET ====================

    /**
     * Clears all baselines and person records. Called on sentry disable.
     */
    public synchronized void reset() {
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            if (globalListener != null && !baselines[q].isEmpty()) {
                fireRetired(new ArrayList<>(baselines[q]), "reset");
            }
            baselines[q].clear();
            recentPersons[q].clear();
        }
        logger.info("Baseline reset (all quadrants cleared)");
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
