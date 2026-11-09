package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;

/**
 * Fixed-constant fisheye dewarp for YOLO inference crops (side mirror cams).
 *
 * <p><b>Why:</b> the BYD side mirror cameras are ultra-wide fisheye
 * (motion_pipeline_v2.cpp:764). Barrel curvature warps a close subject's
 * geometry away from anything a COCO-trained detector has seen, and the field
 * record shows whole side-cam events with {@code max_conf=0.000} on a real
 * subject. Straightening ONLY the bytes handed to the detector — never the
 * recording, never the crop the tracker/thumbnails consume — recovers those
 * detections with zero impact on any other pipeline stage.
 *
 * <p><b>The math</b> mirrors the division-model dewarp already shipped for
 * recordings and the blind-spot card (GpuMosaicRecorder.rectifyTile /
 * GpuStreamScaler.bsRectifyTile), byte-for-byte the same sampling formula:
 * for each OUTPUT pixel at centered aspect-corrected coord n, sample the
 * source at {@code n * zoom / (1 + k1*r^2 + k2*r^4)}, where
 * {@code zoom = 1 + k1*a^2 + k2*a^4} (a = tile aspect) keeps corners at the
 * tile edge. Identity when strength is 0.
 *
 * <p><b>NOT user-configurable, by design.</b> The blind-spot/recording
 * dewarp strength is a user *display* preference (0..100 slider, default 0).
 * Detection accuracy must not silently change because someone adjusted how
 * the on-screen card looks — and a slider default of 0 would keep this fix
 * permanently off. The constants below describe the LENS, not a preference,
 * so they are compiled in.
 *
 * <p><b>How the constants were chosen (2026-08-11):</b> offline sweep of
 * strengths {0, .2, .35, .5, .75, 1.0} over 30 real recorded mosaic frames
 * (event_20260106_184835), running the shipped yolo26n-DRQ model per tile.
 * Left mirror cam at t=0.5: 12 relevant detections / 3.37 summed confidence
 * vs 7 / 2.19 undewarped (+54% confidence, +71% detections); over-dewarp
 * (t>=0.75) collapsed to 4 then 2 detections. Front/rear tiles had no
 * subjects in the test footage → left at 0 until there is evidence
 * (fail-open: 0 = byte-identical to today). Right shares the left's mirror
 * camera hardware, so it inherits the same constant. DEVICE-UNVERIFIED for
 * the right cam specifically; re-run the sweep in dev/ if in doubt.
 *
 * <p><b>Scope:</b> ONLY full-tile 320×240 mosaic crops. Foveated 640×640
 * crops are re-centered sub-windows of the panorama — their crop center is
 * not the lens axis, so a tile-centered radial model would be geometrically
 * wrong there. The engine's close-subject wide-crop gate already forces the
 * NEAR subjects (the ones that matter most) onto the mosaic path.
 *
 * <p><b>Coordinate contract (invariant I5):</b> detection boxes returned by
 * YOLO live in DEWARPED crop space; {@link #mapDetectionsToSource} maps them
 * back to the original (warped) crop space immediately after inference, so
 * every downstream consumer — motion-overlap filter, baseline, ActorTracker,
 * ThumbnailBuffer, texture tracker — sees exactly the coordinate space it
 * always has. The transform is the same forward sampling map the pixels
 * used, evaluated at box corners + edge midpoints (the transform is radial,
 * so a box extreme can sit mid-edge, not only at a corner).
 *
 * <p><b>Failure policy (invariant I1):</b> any error → the original crop and
 * unmapped boxes (identity). This class can only ADD detector signal.
 *
 * <p>Threading: called on aiExecutor only (same single-lane discipline as
 * YoloDetector). The LUT cache is per-(strength,w,h), built once, read-only
 * afterwards; the synchronized build is uncontended steady-state.
 */
final class FisheyeDewarp {

    /**
     * Per-quadrant dewarp strength, indexed Q0..Q3 = front/right/rear/left
     * (MotionPipelineV2.QUADRANT_NAMES order). 0 = off (identity). See class
     * doc for the evidence behind 0.5 on the mirror cams.
     */
    private static final float[] QUADRANT_STRENGTH = {0f, 0.5f, 0f, 0.5f};

    /** Tile aspect (h/w): 240/320 mosaic tile = 0.75, same as the recorder's
     *  Seal default (1280×960). */
    private static final float ASPECT = 0.75f;

    private FisheyeDewarp() {}

    /** Strength for a quadrant (0 = this quadrant is never dewarped). */
    static float strengthFor(int quadrant) {
        return (quadrant >= 0 && quadrant < QUADRANT_STRENGTH.length)
                ? QUADRANT_STRENGTH[quadrant] : 0f;
    }

    // Cached LUT for the single (strength, w, h) combination in live use
    // (0.5 @ 320×240). Rebuilt only if dims change. lut[outIdx] = src RGB byte offset (pixel index * 3).
    private static int[] cachedLut = null;
    private static float cachedStrength = -1f;
    private static int cachedW = -1, cachedH = -1;
    private static final Object lutLock = new Object();

    // Reusable output scratch for dewarpForDetector (audit R2-perf#1):
    // allocating 230 KB per side-cam mosaic inference was ~0.92 MB/s of
    // large-object GC churn at the 250 ms close-subject cadence — the exact
    // pattern YoloDetector's reusable buffers exist to avoid, firing
    // precisely while recording. Safe to share: aiExecutor single-lane
    // contract (class doc) — the returned buffer is consumed synchronously
    // by detect() on the same thread and never retained.
    private static byte[] dewarpScratch = null;

    private static int[] lutFor(float strength, int w, int h) {
        synchronized (lutLock) {
            if (cachedLut != null && cachedStrength == strength
                    && cachedW == w && cachedH == h) {
                return cachedLut;
            }
            float k1 = 0.30f * strength;
            float k2 = 0.10f * strength;
            float a2 = ASPECT * ASPECT;
            float zoom = 1f + k1 * a2 + k2 * a2 * a2;
            int[] lut = new int[w * h];
            for (int y = 0; y < h; y++) {
                float ny = ((y + 0.5f) / h) * 2f - 1f;
                float nya = ny * ASPECT;
                for (int x = 0; x < w; x++) {
                    float nx = ((x + 0.5f) / w) * 2f - 1f;
                    float r2 = nx * nx + nya * nya;
                    float inv = 1f / (1f + k1 * r2 + k2 * r2 * r2);
                    float sx = nx * inv * zoom;
                    float sy = (nya * inv * zoom) / ASPECT;
                    int px = clampi((int) ((sx * 0.5f + 0.5f) * w), 0, w - 1);
                    int py = clampi((int) ((sy * 0.5f + 0.5f) * h), 0, h - 1);
                    lut[y * w + x] = (py * w + px) * 3;
                }
            }
            cachedLut = lut;
            cachedStrength = strength;
            cachedW = w;
            cachedH = h;
            return lut;
        }
    }

    /**
     * Dewarp an RGB888 crop for detector input, or return {@code null} when
     * this quadrant/crop is not dewarped (caller then feeds the original —
     * the null return makes "no change" impossible to confuse with a copy).
     *
     * @param rgb  packed RGB888, length {@code w*h*3}
     */
    static byte[] dewarpForDetector(byte[] rgb, int w, int h, int quadrant) {
        try {
            float strength = strengthFor(quadrant);
            if (strength <= 0f || rgb == null || rgb.length < w * h * 3) return null;
            int[] lut = lutFor(strength, w, h);
            byte[] out = dewarpScratch;
            if (out == null || out.length != w * h * 3) {
                out = new byte[w * h * 3];
                dewarpScratch = out;
            }
            for (int i = 0; i < lut.length; i++) {
                int s = lut[i];
                int d = i * 3;
                out[d] = rgb[s];
                out[d + 1] = rgb[s + 1];
                out[d + 2] = rgb[s + 2];
            }
            return out;
        } catch (Throwable t) {
            return null;  // I1: fail open — original crop, identity boxes.
        }
    }

    /**
     * Map detections from DEWARPED crop space back to the ORIGINAL (warped)
     * crop space, preserving order and every non-geometric field. Uses the
     * same forward sampling transform as the pixels, evaluated at the box's
     * 4 corners + 4 edge midpoints (radial transform ⇒ extremes can lie
     * mid-edge), then takes the bounding box of the mapped points.
     */
    static java.util.List<Detection> mapDetectionsToSource(
            java.util.List<Detection> dets, int w, int h, int quadrant) {
        try {
            float strength = strengthFor(quadrant);
            if (strength <= 0f || dets == null || dets.isEmpty()) return dets;
            float k1 = 0.30f * strength;
            float k2 = 0.10f * strength;
            float a2 = ASPECT * ASPECT;
            float zoom = 1f + k1 * a2 + k2 * a2 * a2;
            java.util.List<Detection> mapped = new java.util.ArrayList<>(dets.size());
            float[] px = new float[8];
            float[] py = new float[8];
            for (Detection d : dets) {
                float x1 = d.getX(), y1 = d.getY();
                float x2 = x1 + d.getW(), y2 = y1 + d.getH();
                float mx = (x1 + x2) * 0.5f, my = (y1 + y2) * 0.5f;
                // corners + edge midpoints
                px[0] = x1; py[0] = y1;  px[1] = x2; py[1] = y1;
                px[2] = x1; py[2] = y2;  px[3] = x2; py[3] = y2;
                px[4] = mx; py[4] = y1;  px[5] = mx; py[5] = y2;
                px[6] = x1; py[6] = my;  px[7] = x2; py[7] = my;
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int i = 0; i < 8; i++) {
                    float nx = (px[i] / w) * 2f - 1f;
                    float nya = ((py[i] / h) * 2f - 1f) * ASPECT;
                    float r2 = nx * nx + nya * nya;
                    float inv = 1f / (1f + k1 * r2 + k2 * r2 * r2);
                    float sxp = ((nx * inv * zoom) * 0.5f + 0.5f) * w;
                    float syp = (((nya * inv * zoom) / ASPECT) * 0.5f + 0.5f) * h;
                    if (sxp < minX) minX = sxp;
                    if (sxp > maxX) maxX = sxp;
                    if (syp < minY) minY = syp;
                    if (syp > maxY) maxY = syp;
                }
                int bx = clampi(Math.round(minX), 0, w - 1);
                int by = clampi(Math.round(minY), 0, h - 1);
                int bw = clampi(Math.round(maxX) - bx, 0, w - bx);
                int bh = clampi(Math.round(maxY) - by, 0, h - by);
                mapped.add(new Detection(d.getClassId(), d.getConfidence(), bx, by, bw, bh));
            }
            return mapped;
        } catch (Throwable t) {
            return dets;  // I1: fail open — better an unmapped box than a dropped one.
        }
    }

    private static int clampi(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
