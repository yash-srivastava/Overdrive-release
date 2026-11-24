package com.overdrive.app.surveillance;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sidecar {@code lens} marker: the clip's pixels are raw strip tiles (CPU
 * engine) and players dewarp them at display time with the live lens slider.
 * {@link #STRIP}, when present, is the height in frame rows of the opaque
 * telemetry strip at the top, which players show as recorded. Captured when a
 * clip closes, from the encoder that wrote it; sidecar writers look it up by
 * the final mp4 path.
 */
public final class LensTag {

    public static final String STRIP = "strip";

    private static final int MAX_ENTRIES = 256;
    private static final Map<String, JSONObject> BY_PATH =
        new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    private LensTag() {}

    /** Records the tag (or its absence) for a clip whose identity was just captured. */
    public static void remember(File finalMp4, JSONObject tag) {
        if (finalMp4 == null) return;
        synchronized (BY_PATH) {
            if (tag == null) BY_PATH.remove(finalMp4.getAbsolutePath());
            else BY_PATH.put(finalMp4.getAbsolutePath(), tag);
        }
    }

    /** Tag captured at close for this clip, or null (GPU clip, overlay burned in, unknown). */
    public static JSONObject forFile(File mp4) {
        if (mp4 == null) return null;
        synchronized (BY_PATH) {
            JSONObject tag = BY_PATH.get(mp4.getAbsolutePath());
            if (tag == null) return null;
            try {
                return new JSONObject(tag.toString());
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Writes {@code {"version":3,"lens":...}} when the clip has a tag and no sidecar yet, so
     * players see it even when no other sidecar writer runs for the clip. Never replaces one.
     */
    public static boolean writeSidecarIfAbsent(File mp4) {
        JSONObject tag = forFile(mp4);
        if (tag == null || mp4.getParentFile() == null) return false;
        File json = new File(mp4.getParentFile(), mp4.getName().replace(".mp4", ".json"));
        boolean created = false;
        try {
            // O_EXCL create: another writer's sidecar is never replaced, theirs may replace this one.
            if (!RemovableStorageFs.createNewFile(json)) return false;
            created = true;
            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("lens", tag);
            try (java.io.Writer w = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(json), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            RemovableStorageFs.setReadable(json, true, false);
            return true;
        } catch (Throwable t) {
            if (created) RemovableStorageFs.delete(json);
            return false;
        }
    }

    /** The clip's tag: remembered at close in this process, else read from its sidecar (any thread but UI). */
    public static JSONObject tagFor(File mp4) {
        JSONObject tag = forFile(mp4);
        if (tag != null || mp4 == null || mp4.getParentFile() == null) return tag;
        File json = new File(mp4.getParentFile(), mp4.getName().replace(".mp4", ".json"));
        if (!RemovableStorageFs.isFile(json) || RemovableStorageFs.length(json) <= 0 || RemovableStorageFs.length(json) > 1_048_576) return null;
        try {
            byte[] bytes = RemovableStorageFs.readAllBytes(json);
            JSONObject lens = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .optJSONObject("lens");
            return lens != null && lens.optBoolean("raw", false) && "divide".equals(lens.optString("model"))
                ? lens : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Still frames cut from a raw clip (thumbnails, fallback hero, snapshots): the players'
     * per-tile 2x2 dewarp at {@code strength} (0..100). Returns {@code src} when there is
     * nothing to do or the frame can't be processed; never recycles {@code src}.
     */
    public static android.graphics.Bitmap dewarpFrame(android.graphics.Bitmap src, JSONObject tag, int strength) {
        if (src == null || tag == null || strength <= 0) return src;
        double aspect = tag.optDouble("aspect", 0.75);
        if (!(aspect >= 0.25 && aspect <= 1.0)) return src;
        int w = src.getWidth(), h = src.getHeight();
        int tw = w / 2, th = h / 2;
        if (tw < 2 || th < 2) return src;
        double t = Math.min(100, strength) / 100.0;
        double k1 = 0.30 * t, k2 = 0.10 * t;
        double a2 = aspect * aspect;
        double zoom = 1 + k1 * a2 + k2 * a2 * a2;
        int stripRows = (int) Math.round(stripFraction(tag) * h);
        try {
            android.graphics.Bitmap out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] in = new int[tw * th];
            int[] px = new int[tw * th];
            for (int ty = 0; ty < 2; ty++) {
                // Top tiles: the strip rows stay as recorded and are never sampled for the picture.
                int strip = ty == 0 ? Math.min(stripRows, th - 1) : 0;
                for (int tx = 0; tx < 2; tx++) {
                    src.getPixels(in, 0, tw, tx * tw, ty * th, tw, th);
                    for (int y = 0; y < th; y++) {
                        if (y < strip) {
                            System.arraycopy(in, y * tw, px, y * tw, tw);
                            continue;
                        }
                        double ny = ((y + 0.5) / th) * 2 - 1;
                        for (int x = 0; x < tw; x++) {
                            double nx = ((x + 0.5) / tw) * 2 - 1;
                            double ay = ny * aspect;
                            double r2 = nx * nx + ay * ay;
                            double inv = zoom / (1 + k1 * r2 + k2 * r2 * r2);
                            double u = Math.max(0, Math.min(1, nx * inv * 0.5 + 0.5)) * tw - 0.5;
                            double v = Math.max(0, Math.min(1, ny * inv * 0.5 + 0.5)) * th - 0.5;
                            px[y * tw + x] = bilinear(in, tw, th, u, Math.max(v, strip));
                        }
                    }
                    out.setPixels(px, 0, tw, tx * tw, ty * th, tw, th);
                }
            }
            return out;
        } catch (Throwable oom) {
            return src;
        }
    }

    /** Height of the tag's opaque top strip as a fraction of the frame, or 0 for none. */
    public static double stripFraction(JSONObject tag) {
        if (tag == null) return 0;
        int strip = tag.optInt(STRIP, 0);
        JSONObject frame = tag.optJSONObject("frame");
        int frameHeight = frame != null ? frame.optInt("height", 0) : 0;
        if (strip <= 0 || frameHeight <= 0 || strip * 2 >= frameHeight) return 0;
        return (double) strip / frameHeight;
    }

    /** {@link #dewarpFrame} at the live slider; recycles {@code src} when a new frame replaces it. */
    public static android.graphics.Bitmap dewarpFrameForClip(android.graphics.Bitmap src, File mp4) {
        if (src == null) return null;
        JSONObject tag = tagFor(mp4);
        if (tag == null) return src;
        int strength;
        try {
            strength = com.overdrive.app.config.UnifiedConfigManager.getRectifyStrength();
        } catch (Throwable t) {
            return src;
        }
        android.graphics.Bitmap out = dewarpFrame(src, tag, strength);
        if (out != src) src.recycle();
        return out;
    }

    private static int bilinear(int[] p, int w, int h, double u, double v) {
        u = Math.max(0, Math.min(w - 1, u));
        v = Math.max(0, Math.min(h - 1, v));
        int x0 = (int) u, y0 = (int) v;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double fx = u - x0, fy = v - y0;
        int c00 = p[y0 * w + x0], c10 = p[y0 * w + x1], c01 = p[y1 * w + x0], c11 = p[y1 * w + x1];
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((c00 >>> shift) & 0xff) * (1 - fx) + ((c10 >>> shift) & 0xff) * fx;
            double bot = ((c01 >>> shift) & 0xff) * (1 - fx) + ((c11 >>> shift) & 0xff) * fx;
            r |= ((int) Math.round(top * (1 - fy) + bot * fy) & 0xff) << shift;
        }
        return r;
    }

    /** Tag for a clip whose frames were raw grid tiles from this recorder (the encoder decides which clips). */
    static JSONObject build(GpuMosaicRecorder r) {
        if (r == null) return null;
        JSONObject root = new JSONObject();
        try {
            root.put("raw", true);
            root.put("model", "divide");
            JSONObject frame = new JSONObject();
            frame.put("width", r.getViewportWidth());
            frame.put("height", r.getViewportHeight());
            root.put("frame", frame);
            root.put("aspect", (double) r.getRectifyAspect());
            root.put(STRIP, r.cpuOverlayBandHeight());
        } catch (Throwable t) {
            return null;
        }
        return root;
    }
}
