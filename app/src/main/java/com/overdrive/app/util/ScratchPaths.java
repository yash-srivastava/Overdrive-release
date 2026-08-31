package com.overdrive.app.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Resolves daemon scratch storage: legacy {@link #LEGACY_DIR} when shell can
 * write there (Sealion 7 etc.), otherwise the app external {@code files/daemon}
 * dir (Shark and other SELinux-restricted head units).
 *
 * <p>Shell commands are rewritten at {@code AdbShellExecutor} — callers may keep
 * literal {@code /data/local/tmp/...} strings. Java {@link File} access should use
 * {@link #path(String)}, {@link #file(String)}, {@link #firstExistingFile(String)},
 * or {@link #openWrite(String)} for read/write with migration/fallback.
 */
public final class ScratchPaths {

    private static final String TAG = "ScratchPaths";
    public static final String LEGACY_DIR = "/data/local/tmp";
    public static final String ENV_VAR = "OVERDRIVE_SCRATCH";
    private static final String PREFS = "scratch_paths";
    private static final String KEY_DIR = "resolved_dir";
    private static final String PACKAGE_FALLBACK =
            "/storage/emulated/0/Android/data/com.overdrive.app/files/daemon";

    private static volatile String fallbackDir;
    private static volatile String resolvedDir;
    private static volatile Context appContext;
    private static volatile boolean probed;

    private ScratchPaths() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        File ext = appContext.getExternalFilesDir(null);
        if (ext != null) {
            fallbackDir = new File(ext, "daemon").getAbsolutePath();
        }
        String cached = appContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DIR, null);
        if (cached != null && !cached.isEmpty()) {
            resolvedDir = cached;
        }
        syncFromEnv();
        probeLocalWrite();
    }

    /**
     * App-process probe: try creating a file under legacy tmp without dadb.
     * Shell daemons inherit {@link #ENV_VAR} from watchdog scripts instead.
     */
    public static void probeLocalWrite() {
        if (resolvedDir != null) {
            return;
        }
        synchronized (ScratchPaths.class) {
            if (resolvedDir != null) {
                return;
            }
            File probe = new File(LEGACY_DIR, ".od_write_probe");
            try {
                File parent = probe.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                if (probe.createNewFile()) {
                    probe.delete();
                    persistResolved(LEGACY_DIR);
                    return;
                }
            } catch (Exception ignored) {
            }
            forceFallback();
        }
    }

    /** Daemon child processes inherit {@link #ENV_VAR} from watchdog shell. */
    public static void syncFromEnv() {
        String env = System.getenv(ENV_VAR);
        if (env != null && !env.isEmpty()) {
            resolvedDir = env;
        }
    }

    public static String getDir() {
        syncFromEnv();
        String dir = resolvedDir;
        return dir != null ? dir : LEGACY_DIR;
    }

    public static String getFallbackDir() {
        String fb = fallbackDir;
        return fb != null ? fb : PACKAGE_FALLBACK;
    }

    public static boolean usesLegacyDir() {
        return LEGACY_DIR.equals(getDir());
    }

    public static String path(String name) {
        if (name == null || name.isEmpty()) {
            return getDir();
        }
        if (name.equals(LEGACY_DIR)) {
            return getDir();
        }
        if (name.startsWith(LEGACY_DIR + "/")) {
            return getDir() + name.substring(LEGACY_DIR.length());
        }
        if (name.startsWith(getFallbackDir())) {
            return name;
        }
        if (name.charAt(0) == '/') {
            return remapShell(name);
        }
        return getDir() + "/" + name;
    }

    public static File file(String name) {
        return new File(path(name));
    }

    public static File ensureDir() {
        File dir = new File(getDir());
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** Rewrite legacy tmp prefixes in a shell command or script body. */
    public static String remapShell(String command) {
        if (command == null || command.isEmpty() || usesLegacyDir()) {
            return command;
        }
        return command.replace(LEGACY_DIR, getDir());
    }

    public static String shellPrefix() {
        String dir = getDir();
        return "export " + ENV_VAR + "='" + shellQuote(dir) + "'\n"
                + "export TMPDIR='" + shellQuote(dir) + "'\n"
                + "mkdir -p \"$TMPDIR\"\n";
    }

    public static String prepareShellCommand(String command) {
        return shellPrefix() + remapShell(command);
    }

    /**
     * Probe shell writability once via dadb. Safe to call repeatedly — runs at most once
     * until {@link #resetForTests()}.
     */
    public static void probeViaShell(java.util.function.Function<String, String> shellRunner) {
        if (probed || resolvedDir != null) {
            probed = true;
            return;
        }
        synchronized (ScratchPaths.class) {
            if (probed || resolvedDir != null) {
                probed = true;
                return;
            }
            String legacyProbe = "touch '" + LEGACY_DIR + "/.od_write_probe' 2>/dev/null "
                    + "&& rm -f '" + LEGACY_DIR + "/.od_write_probe' 2>/dev/null "
                    + "&& echo legacy || echo fail";
            String out = shellRunner.apply(legacyProbe);
            if (out != null && out.contains("legacy")) {
                persistResolved(LEGACY_DIR);
                Log.i(TAG, "Shell scratch probe: using legacy " + LEGACY_DIR);
            } else {
                String fb = getFallbackDir();
                mkdirsLocal(fb);
                shellRunner.apply("mkdir -p '" + shellQuote(fb) + "' 2>/dev/null");
                persistResolved(fb);
                Log.i(TAG, "Shell scratch probe: legacy not writable, using " + fb);
            }
            probed = true;
        }
    }

    /** Paths to try when reading (resolved, then legacy, then fallback). */
    public static String[] readPaths(String legacyPathOrName) {
        String rel = toRelative(legacyPathOrName);
        if (rel == null) {
            return new String[]{legacyPathOrName};
        }
        String primary = path(rel);
        if (usesLegacyDir()) {
            return new String[]{primary};
        }
        return new String[]{primary, LEGACY_DIR + "/" + rel, getFallbackDir() + "/" + rel};
    }

    public static File firstExistingFile(String legacyPathOrName) {
        for (String p : readPaths(legacyPathOrName)) {
            File f = new File(p);
            if (f.exists()) {
                return f;
            }
        }
        return file(toRelative(legacyPathOrName) != null
                ? toRelative(legacyPathOrName) : legacyPathOrName);
    }

    public static String readTextFirstExisting(String legacyPathOrName) throws IOException {
        for (String p : readPaths(legacyPathOrName)) {
            File f = new File(p);
            if (!f.isFile()) {
                continue;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        }
        throw new FileNotFoundException(legacyPathOrName);
    }

    /** Write to resolved scratch; on failure force fallback and retry once. */
    public static FileOutputStream openWrite(String legacyPathOrName) throws IOException {
        String rel = toRelative(legacyPathOrName);
        if (rel == null) {
            throw new IOException("invalid path: " + legacyPathOrName);
        }
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            File target = file(rel);
            try {
                mkdirsLocal(target.getParent());
                return new FileOutputStream(target);
            } catch (IOException e) {
                last = e;
                if (attempt == 0 && LEGACY_DIR.equals(getDir())) {
                    forceFallback();
                }
            }
        }
        throw last != null ? last : new IOException("openWrite failed: " + legacyPathOrName);
    }

    public static void noteWriteFailure() {
        if (usesLegacyDir()) {
            forceFallback();
        }
    }

    static void forceFallback() {
        String fb = getFallbackDir();
        mkdirsLocal(fb);
        persistResolved(fb);
        if (appContext != null) {
            Log.w(TAG, "Write to legacy scratch failed; switched to " + fb);
        }
    }

    static void resetForTests() {
        resolvedDir = null;
        fallbackDir = null;
        appContext = null;
        probed = false;
    }

    private static void persistResolved(String dir) {
        resolvedDir = dir;
        Context ctx = appContext;
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DIR, dir)
                    .apply();
        }
    }

    private static String toRelative(String legacyPathOrName) {
        if (legacyPathOrName == null || legacyPathOrName.isEmpty()) {
            return null;
        }
        if (legacyPathOrName.startsWith(LEGACY_DIR + "/")) {
            return legacyPathOrName.substring(LEGACY_DIR.length() + 1);
        }
        if (legacyPathOrName.startsWith(getFallbackDir() + "/")) {
            return legacyPathOrName.substring(getFallbackDir().length() + 1);
        }
        if (legacyPathOrName.startsWith("/")) {
            return null;
        }
        return legacyPathOrName;
    }

    private static void mkdirsLocal(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File d = new File(path);
        if (!d.exists()) {
            d.mkdirs();
        }
    }

    private static String shellQuote(String value) {
        return value.replace("'", "'\\''");
    }
}
