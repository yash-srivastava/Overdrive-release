package com.overdrive.app.byd.cloud.crypto;

import android.content.Context;
import android.content.res.AssetManager;
import com.overdrive.app.util.ScratchPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Resolves a usable InputStream for the Bangcle whitebox tables.
 *
 * Strategy:
 *   1. Validate the cached copy at /data/local/tmp/bangcle_tables.bin
 *      (magic + size). If valid, stream from disk.
 *   2. Otherwise, re-extract the canonical copy from APK assets via
 *      atomic rename, then stream from disk.
 *   3. Last-resort: stream directly from assets (no disk cache).
 *
 * Centralized to fix "Bad magic: expected BGTB" failures caused by
 * a stale/truncated /data/local/tmp file from older builds or
 * interrupted extractions: every previous call site trusted any
 * non-empty file at that path.
 */
public final class BangcleTablesFile {

    public static final String CACHE_PATH = ScratchPaths.path("bangcle_tables.bin");
    public static final String ASSET_PATH = "byd/bangcle_tables.bin";

    private static final byte[] MAGIC = { 'B', 'G', 'T', 'B' };

    // Minimum legal size: header + index + every table at its expected size.
    private static final long MIN_VALID_SIZE;
    static {
        long sum = 4 + 2 + 2 + 8L * (4 + 4); // magic + version + count + 8 index entries
        sum += BangcleTables.INV_ROUND_SIZE;
        sum += BangcleTables.INV_XOR_SIZE;
        sum += BangcleTables.INV_FIRST_SIZE;
        sum += BangcleTables.ROUND_SIZE;
        sum += BangcleTables.XOR_SIZE;
        sum += BangcleTables.FINAL_SIZE;
        sum += BangcleTables.PERM_DECRYPT_SIZE;
        sum += BangcleTables.PERM_ENCRYPT_SIZE;
        MIN_VALID_SIZE = sum;
    }

    private BangcleTablesFile() {}

    /**
     * Open a validated stream over the Bangcle table file. Returns null
     * if neither the cache nor the APK asset is usable.
     *
     * @param ctx app/daemon context (may be null — falls back to cache only)
     */
    public static InputStream openStream(Context ctx) {
        File cache = new File(CACHE_PATH);
        if (isValid(cache)) {
            try {
                return new FileInputStream(cache);
            } catch (IOException ignored) {}
        }

        if (ctx != null) {
            AssetManager am = ctx.getAssets();
            if (am != null) {
                if (extractFromAssets(am, cache)) {
                    try {
                        return new FileInputStream(cache);
                    } catch (IOException ignored) {}
                }
                try {
                    return am.open(ASSET_PATH);
                } catch (IOException ignored) {}
            }
        }

        return null;
    }

    /**
     * Whether the cached file at /data/local/tmp/bangcle_tables.bin
     * starts with "BGTB" and is at least the minimum legal size.
     */
    public static boolean isValid(File f) {
        if (f == null || !f.exists()) return false;
        if (f.length() < MIN_VALID_SIZE) return false;

        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] head = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(head, read, 4 - read);
                if (n < 0) return false;
                read += n;
            }
            for (int i = 0; i < 4; i++) {
                if (head[i] != MAGIC[i]) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Atomically replace the cache file from APK assets. Writes to a
     * sibling .tmp first to avoid leaving a half-written file behind
     * if extraction is interrupted.
     */
    public static boolean extractFromAssets(AssetManager am, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = am.open(ASSET_PATH);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            try { out.getFD().sync(); } catch (IOException ignored) {}
        } catch (IOException e) {
            tmp.delete();
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }

        if (!isValid(tmp)) {
            tmp.delete();
            return false;
        }

        // POSIX rename is atomic and replaces dest if it exists — don't
        // pre-delete, that would leave a window where readers see no file.
        if (!tmp.renameTo(dest)) {
            // Some filesystems still refuse cross-device replace; fall back.
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return false;
            }
        }
        dest.setReadable(true, false);
        return true;
    }

    /** Diagnostic string describing the cache state — useful in logs. */
    public static String describeCache() {
        File f = new File(CACHE_PATH);
        if (!f.exists()) return CACHE_PATH + " (missing)";
        return String.format(Locale.US, "%s (size=%d, valid=%b)",
                CACHE_PATH, f.length(), isValid(f));
    }
}
