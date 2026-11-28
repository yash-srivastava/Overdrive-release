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
    /** Sidecar key of the grid geometry block (see {@link #buildGrid}); readers ignore unknown keys. */
    public static final String GRID = "grid";
    static final int GRID_VERSION = 1;
    // A clip's frames may start this long before its segment start (pre-record history).
    static final long GRID_PRE_ROLL_MS = 60_000L;

    private static final int MAX_ENTRIES = 256;
    private static final Map<String, JSONObject> BY_PATH =
        new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    private static final Map<String, JSONObject> GRID_BY_PATH =
        new LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    private LensTag() {}

    /** Records the grid block (or its absence) for a clip whose identity was just captured. */
    public static void rememberGrid(File finalMp4, JSONObject grid) {
        if (finalMp4 == null) return;
        synchronized (GRID_BY_PATH) {
            if (grid == null) GRID_BY_PATH.remove(finalMp4.getAbsolutePath());
            else GRID_BY_PATH.put(finalMp4.getAbsolutePath(), grid);
        }
    }

    /** Grid block captured at close for this clip (a copy), or null. */
    public static JSONObject gridForFile(File mp4) {
        if (mp4 == null) return null;
        synchronized (GRID_BY_PATH) {
            JSONObject grid = GRID_BY_PATH.get(mp4.getAbsolutePath());
            if (grid == null) return null;
            try {
                return new JSONObject(grid.toString());
            } catch (Throwable t) {
                return null;
            }
        }
    }

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
     * Writes {@code {"version":3,"lens":...,"grid":...}} when the clip has a tag and no sidecar yet,
     * so players see it even when no other sidecar writer runs for the clip. Never replaces one.
     */
    public static boolean writeSidecarIfAbsent(File mp4) {
        JSONObject tag = forFile(mp4);
        JSONObject grid = gridForFile(mp4);
        if ((tag == null && grid == null) || mp4.getParentFile() == null) return false;
        File json = new File(mp4.getParentFile(), mp4.getName().replace(".mp4", ".json"));
        boolean created = false;
        try {
            // O_EXCL create: another writer's sidecar is never replaced, theirs may replace this one.
            if (!RemovableStorageFs.createNewFile(json)) return false;
            created = true;
            JSONObject root = new JSONObject();
            root.put("version", 3);
            if (tag != null) root.put("lens", tag);
            if (grid != null) root.put(GRID, grid);
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
        int stripRows = (int) Math.round(stripFraction(tag) * h);
        android.graphics.Bitmap out = null;
        try {
            PreviewLensWarp warp = PreviewLensWarp.forTile(tw, th, aspect, strength);
            out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] in = new int[tw * th];
            int[] px = new int[tw * th];
            for (int ty = 0; ty < 2; ty++) {
                // Top tiles: the strip rows stay as recorded and are never sampled for the picture.
                int strip = ty == 0 ? Math.min(stripRows, th - 1) : 0;
                for (int tx = 0; tx < 2; tx++) {
                    src.getPixels(in, 0, tw, tx * tw, ty * th, tw, th);
                    warp.apply(in, px, strip);
                    out.setPixels(px, 0, tw, tx * tw, ty * th, tw, th);
                }
            }
            return out;
        } catch (Throwable oom) {
            if (out != null && out != src) out.recycle();
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
    /**
     * Grid block for a clip of this recorder, or null when its frames are not (or not provably)
     * the 4-camera 2x2 grid. {@code since}: MIN_VALUE when the frames are proven grid, else the
     * wall ms the grid must predate (0 or less: unknown, no tag). Raw/strip come from {@link #finishGrid}.
     */
    static JSONObject buildGrid(GpuMosaicRecorder r, long since) {
        if (r == null || (since != Long.MIN_VALUE && !(since > 0 && r.gridSince(since)))) {
            return null;
        }
        try {
            JSONObject grid = new JSONObject();
            grid.put("v", GRID_VERSION);
            JSONObject frame = new JSONObject();
            frame.put("width", r.getViewportWidth());
            frame.put("height", r.getViewportHeight());
            grid.put("frame", frame);
            // Quadrant order TL, TR, BL, BR, as GpuMosaicRecorder composes it; no tile is mirrored.
            grid.put("tiles", new org.json.JSONArray().put("front").put("right").put("rear").put("left"));
            grid.put("aspect", round5(r.getRectifyAspect()));
            // What the recorded dashcam layout would use: top band = windshield cropped by cropY.
            JSONObject dashcam = new JSONObject();
            dashcam.put("split", round5(GpuMosaicRecorder.dashcamSplit()));
            dashcam.put("cropY", round5(r.windshieldCropY()));
            dashcam.put("srcAspect", round5(GpuMosaicRecorder.windshieldSourceAspect()));
            dashcam.put("cells", new org.json.JSONArray().put("left").put("rear").put("right"));
            grid.put("dashcam", dashcam);
            return grid;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Adds raw/strip from the clip's lens tag: raw tiles get the player dewarp, strip rows stay as recorded. */
    static void finishGrid(JSONObject grid, JSONObject lensTag) {
        try {
            grid.put("raw", lensTag != null);
            grid.put(STRIP, lensTag != null ? Math.max(0, lensTag.optInt(STRIP, 0)) : 0);
        } catch (Throwable ignored) {}
    }

    private static double round5(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}
