package com.overdrive.app.camera.dilink5;

import java.util.Locale;

/**
 * Fisheye → rectilinear un-warp for the raw DiLink 5 AVM cameras.
 *
 * <p><b>Why a separate model:</b> on DiLink 5 the AIS/QCarCam path hands us the
 * four cameras RAW: a full ~190° circular fisheye per camera. The legacy
 * {@code recording.rectifyStrength} division model (k1 ≤ 0.30) was built to
 * remove the small residual barrel left after the DiLink 3/4 HAL's own dewarp,
 * and it only runs on the legacy 4-strip layout anyway — so on DiLink 5 the
 * slider did nothing.
 *
 * <p><b>Geometry (measured on a Tang 2025 from live frames):</b> each 360
 * camera is a 1280x960 sensor that fast_cam_capture pads into its fixed
 * 1920x1300 buffer at the TOP-LEFT; the bridge then centre-crops rows
 * 110..1189 into the 1920x1080 mosaic tile. So inside a tile the picture is
 * the left 2/3 and the top 850 rows; the rest is grey padding. The fisheye
 * circle is centred on the sensor (tile px 640, 370) with radius = half the
 * sensor width (640 px).
 *
 * <p><b>The model:</b> equidistant fisheye ({@code r_src = R · θ / θmax})
 * re-projected to a pinhole view ({@code r_out ∝ tan θ}) that fills the WHOLE
 * tile (so the grey padding disappears whenever the correction is on). The
 * {@code rectifyStrength} slider (0..100) picks the output horizontal half-FOV:
 * 0 = off (bit-exact passthrough), 1 = widest ({@link #HALF_FOV_MILD_DEG}),
 * 100 = narrowest/straightest ({@link #HALF_FOV_STRONG_DEG}).
 *
 * <p>Coordinates: tile-local UV with v = 0 at the image BOTTOM (the DiLink 5
 * texture is bottom-origin, see {@link DiLink5MosaicLayout}). {@link #GLSL} is
 * the single source of the shader math; {@link #sourceUv} mirrors it on the
 * CPU for tests.
 */
public final class DiLink5FisheyeModel {
    private DiLink5FisheyeModel() {}

    // All lengths in tile-WIDTH units (tile = 1920x1080 → height 0.5625).
    static final double TILE_W_PX = 1920.0;
    /** Tile height / width. */
    public static final float TILE_ASPECT = (float) (1080.0 / TILE_W_PX);
    /** Fisheye circle centre, x from the left. */
    static final float CENTER_X = (float) (640.0 / TILE_W_PX);
    /** Fisheye circle centre, y from the TOP (sensor centre 480 − 110 crop). */
    static final float CENTER_Y_TOP = (float) ((480.0 - 110.0) / TILE_W_PX);
    /** Fisheye circle radius (half the 1280 px sensor width). */
    static final float CIRCLE_RADIUS = (float) (640.0 / TILE_W_PX);
    /** Last picture row from the top (850 of 1080 rows carry image). */
    static final float VISIBLE_Y_TOP = (float) (850.0 / TILE_W_PX);
    /** Half the lens FOV at the image-circle edge (≈190° lens). */
    public static final float LENS_HALF_FOV_DEG = 95f;
    /** Output horizontal half-FOV at slider 1 (widest). */
    public static final float HALF_FOV_MILD_DEG = 70f;
    /** Output horizontal half-FOV at slider 100 (straightest). */
    public static final float HALF_FOV_STRONG_DEG = 40f;

    /**
     * Shader uniform value for a slider strength: tan(output half-FOV), or 0
     * when off. 0 makes {@code dl5Unfish} return its input unchanged.
     */
    public static float tanHalfFov(float strength) {
        if (!(strength > 0f)) return 0f;
        float t = Math.min(100f, strength) / 100f;
        double deg = HALF_FOV_MILD_DEG + (HALF_FOV_STRONG_DEG - HALF_FOV_MILD_DEG) * t;
        return (float) Math.tan(Math.toRadians(deg));
    }

    private static final double RADIUS_PER_RAD =
            CIRCLE_RADIUS / Math.toRadians(LENS_HALF_FOV_DEG);

    /**
     * GLSL helper. Declares {@code uDl5FishTan} and
     * {@code vec2 dl5Unfish(vec2 t)}: tile-local output UV (0..1, v=0 bottom) →
     * tile-local source UV. Identity when {@code uDl5FishTan <= 0}.
     */
    public static final String GLSL = String.format(Locale.US,
            "uniform float uDl5FishTan;\n" +
            "vec2 dl5Unfish(vec2 t) {\n" +
            "    if (uDl5FishTan <= 0.0) return t;\n" +
            // output point relative to tile centre, width units, y down
            "    vec2 p = vec2(t.x - 0.5, (1.0 - t.y) * %1$.6f - %1$.6f * 0.5);\n" +
            "    float r = length(p);\n" +
            "    vec2 s = vec2(%2$.6f, %3$.6f);\n" +
            "    if (r > 0.000001) {\n" +
            "        float rs = atan(r * 2.0 * uDl5FishTan) * %4$.6f;\n" +
            "        s += p * (rs / r);\n" +
            "    }\n" +
            "    s = clamp(s, vec2(0.0), vec2(1.0, %5$.6f));\n" +
            "    return vec2(s.x, 1.0 - s.y / %1$.6f);\n" +
            "}\n" +
            // 2x2 mosaic helper: un-warp each quadrant independently.
            "vec2 dl5UnfishMosaic(vec2 uv) {\n" +
            "    if (uDl5FishTan <= 0.0) return uv;\n" +
            "    vec2 tile = min(floor(uv * 2.0), vec2(1.0));\n" +
            "    vec2 local = uv * 2.0 - tile;\n" +
            "    return (tile + dl5Unfish(local)) * 0.5;\n" +
            "}\n",
            TILE_ASPECT, CENTER_X, CENTER_Y_TOP, RADIUS_PER_RAD, VISIBLE_Y_TOP);

    /** CPU mirror of {@code dl5Unfish} (v = 0 at the bottom). Returns {u, v}. */
    public static float[] sourceUv(float strength, float u, float v) {
        float tan = tanHalfFov(strength);
        if (tan <= 0f) return new float[] {u, v};
        double px = u - 0.5;
        double py = (1.0 - v) * TILE_ASPECT - TILE_ASPECT * 0.5;
        double r = Math.hypot(px, py);
        double sx = CENTER_X, sy = CENTER_Y_TOP;
        if (r > 0.000001) {
            double rs = Math.atan(r * 2.0 * tan) * RADIUS_PER_RAD;
            sx += px * (rs / r);
            sy += py * (rs / r);
        }
        sx = Math.max(0.0, Math.min(1.0, sx));
        sy = Math.max(0.0, Math.min(VISIBLE_Y_TOP, sy));
        return new float[] {(float) sx, (float) (1.0 - sy / TILE_ASPECT)};
    }
}
