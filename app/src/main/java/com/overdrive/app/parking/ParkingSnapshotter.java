package com.overdrive.app.parking;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Four-camera stills for a session ("arrived" ~90 s after switch-off,
 * "returned" at the first door-open / unlock / ACC-on), written as
 * {@code <sessionDir>/<prefix>_front.jpg …} plus a 2×2 composite
 * {@code <prefix>_mosaic.jpg} used as the notification image.
 *
 * <p>Capture goes through {@link ParkingEnvironment#captureQuadrantJpeg},
 * i.e. the same recording-safe shared-EGL sampler the camera-mapping dialog
 * uses. Each call may block ~150 ms (up to ~2.5 s worst case), so this only
 * ever runs on the parking worker thread, never on a camera or ACC thread.
 */
public final class ParkingSnapshotter {

    public static final String PREFIX_ARRIVED = "arrived";
    public static final String PREFIX_RETURNED = "returned";
    /** Composite tile size: each 1280×960 quadrant is downscaled 2× → 1280×960 mosaic. */
    static final int COMPOSITE_TILE_W = 640;
    static final int COMPOSITE_TILE_H = 480;
    static final int JPEG_QUALITY = 88;

    private final ParkingEnvironment env;

    public ParkingSnapshotter(ParkingEnvironment env) {
        this.env = env;
    }

    /** Result of one four-camera capture. */
    public static final class Result {
        public final int quadrantsOk;
        public final File compositeFile;   // may be null
        Result(int quadrantsOk, File compositeFile) {
            this.quadrantsOk = quadrantsOk;
            this.compositeFile = compositeFile;
        }
        public boolean ok() { return quadrantsOk > 0; }
    }

    /**
     * Extra passes over the quadrants that came back empty or black. The first
     * sample of a capture pays the sampler's cold start (shared-EGL thread,
     * FBO + shader allocation, first GPU sync against a busy encoder) and can
     * exceed the sampler's 1.5 s reply window — the front tile, always sampled
     * first, is the one that used to come out black. By the retry the sampler
     * is warm and the same call takes ~150 ms.
     */
    static final int RETRY_PASSES = 2;
    static final long RETRY_PAUSE_MS = 400L;

    /**
     * Capture all four quadrants into {@code sessionDir}. Never throws; a
     * quadrant that still fails after the retries is absent from the composite
     * (drawn as a marked placeholder tile, see the daemon's composer).
     */
    public Result capture(File sessionDir, String prefix) {
        if (sessionDir == null) return new Result(0, null);
        if (!ensureDir(sessionDir)) {
            env.log("Parking snapshot: cannot create " + sessionDir);
            return new Result(0, null);
        }
        ParkingEnvironment.ImageOps ops = env.imageOps();
        byte[][] tiles = new byte[4][];
        for (int pass = 0; pass <= RETRY_PASSES; pass++) {
            boolean missing = false;
            for (int q = 0; q < 4; q++) {
                if (tiles[q] != null) continue;
                byte[] jpeg = null;
                try {
                    jpeg = env.captureQuadrantJpeg(q);
                } catch (Throwable t) {
                    env.log("Parking snapshot q" + q + " failed: " + t.getMessage());
                }
                if (jpeg == null || jpeg.length == 0) {
                    missing = true;
                    continue;
                }
                boolean blank = false;
                try { blank = ops != null && ops.isBlank(jpeg); } catch (Throwable ignored) {}
                if (blank) {
                    // Rendered before the texture was valid: a flat black plane,
                    // not a dark scene. Treat like a miss and sample again.
                    env.log("Parking snapshot q" + q + " blank on pass " + pass + " — retrying");
                    missing = true;
                    continue;
                }
                tiles[q] = jpeg;
            }
            if (!missing) break;
            if (pass < RETRY_PASSES) {
                try { Thread.sleep(RETRY_PAUSE_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int ok = 0;
        StringBuilder lost = new StringBuilder();
        for (int q = 0; q < 4; q++) {
            if (tiles[q] == null) {
                lost.append(lost.length() == 0 ? "" : ",").append(ParkingNeighbour.sideName(q));
                continue;
            }
            File f = new File(sessionDir, prefix + "_" + ParkingNeighbour.sideName(q) + ".jpg");
            if (writeAtomic(f, tiles[q])) ok++;
        }
        if (lost.length() > 0) {
            env.log("Parking snapshot " + prefix + ": no frame for " + lost + " after "
                    + (RETRY_PASSES + 1) + " attempts (camera pipeline busy or tile unavailable)");
        }
        File composite = null;
        if (ok > 0) {
            if (ops != null) {
                try {
                    byte[] mosaic = ops.composeMosaic(tiles, COMPOSITE_TILE_W, COMPOSITE_TILE_H, JPEG_QUALITY);
                    if (mosaic != null && mosaic.length > 0) {
                        File m = new File(sessionDir, prefix + "_mosaic.jpg");
                        if (writeAtomic(m, mosaic)) composite = m;
                    }
                } catch (Throwable t) {
                    env.log("Parking snapshot composite failed: " + t.getMessage());
                }
            }
            if (composite == null) {
                // No composite: fall back to any tile we did get as the banner image.
                for (int q = 0; q < 4 && composite == null; q++) {
                    if (tiles[q] == null) continue;
                    File f = new File(sessionDir, prefix + "_" + ParkingNeighbour.sideName(q) + ".jpg");
                    if (f.isFile()) composite = f;
                }
            }
        }
        return new Result(ok, composite);
    }

    /**
     * Create a session asset directory, traversable by other UIDs (the Telegram
     * bot daemon reads the stills directly), like the base directory.
     */
    static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) return true;
        if (!dir.mkdirs() && !dir.isDirectory()) return false;
        try { dir.setReadable(true, false); dir.setExecutable(true, false); } catch (Throwable ignored) {}
        File nomedia = new File(dir, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (Throwable ignored) {}
        }
        return true;
    }

    /** Atomic write (tmp + rename), world-readable so the Telegram daemon (other UID) can read it. */
    static boolean writeAtomic(File target, byte[] bytes) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            try { fos.getFD().sync(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
        try { tmp.setReadable(true, false); } catch (Throwable ignored) {}
        // Replace in place: an existing good file is never deleted before the
        // new one is guaranteed to land.
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignored) {
            // Filesystem without atomic replace (some FUSE mounts): fall back.
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) { tmp.delete(); return false; }
        }
        return true;
    }
}
