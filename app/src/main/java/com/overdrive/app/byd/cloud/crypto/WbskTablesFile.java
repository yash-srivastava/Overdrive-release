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
 * Resolves a usable InputStream for the WBSK whitebox tables (China region).
 *
 * CN counterpart of {@link BangcleTablesFile}. Same asset→cache strategy:
 *   1. Validate the cached copy at /data/local/tmp/wbsk_tables.json. If valid,
 *      stream from disk.
 *   2. Otherwise, re-extract the canonical copy from APK assets via atomic
 *      rename, then stream from disk.
 *   3. Last-resort: stream directly from assets (no disk cache).
 *
 * The WBSK tables ship as a JSON object (not the Bangcle BGTB binary), so
 * validity is "starts with '{' and large enough to hold all 16 base64 tables"
 * rather than a magic check.
 */
public final class WbskTablesFile {

    public static final String CACHE_PATH = ScratchPaths.path("wbsk_tables.json");
    public static final String ASSET_PATH = "byd/wbsk_tables.json";

    // 8 byte tables (256B) + 8 u32 tables (1024B) = 10240 raw bytes;
    // base64 inflates ~4/3 plus JSON keys/quotes. A valid file is well over 12 KB.
    // Use a conservative floor to reject truncated/half-written caches.
    private static final long MIN_VALID_SIZE = 12000L;

    private WbskTablesFile() {}

    /**
     * Open a validated stream over the WBSK table file. Returns null if neither
     * the cache nor the APK asset is usable.
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
     * Whether the cached file at /data/local/tmp/wbsk_tables.json starts with
     * '{' and is at least the minimum legal size.
     */
    public static boolean isValid(File f) {
        if (f == null || !f.exists()) return false;
        if (f.length() < MIN_VALID_SIZE) return false;

        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int first;
            // skip a possible UTF-8 BOM / leading whitespace
            do {
                first = in.read();
            } while (first == 0xEF || first == 0xBB || first == 0xBF
                    || first == ' ' || first == '\n' || first == '\r' || first == '\t');
            return first == '{';
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Atomically replace the cache file from APK assets. Writes to a sibling
     * .tmp first to avoid leaving a half-written file behind if extraction is
     * interrupted.
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

        // POSIX rename is atomic and replaces dest if it exists.
        if (!tmp.renameTo(dest)) {
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
