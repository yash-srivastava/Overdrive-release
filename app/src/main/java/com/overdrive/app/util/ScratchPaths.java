package com.overdrive.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Resolves the shared scratch directory used by the app, ADB shell, and
 * {@code app_process} daemons.
 *
 * <p>Sealion / DiLink units that can write {@link #LEGACY_DIR} keep that path.
 * Shark / DiLink 5 (SELinux {@code data_local}, no adb root) falls back to the
 * app's external files {@code daemon/} tree — writable by both the app UID and
 * shell UID 2000, but <b>not executable</b> ({@code media_rw}).
 *
 * <p>Call {@link #init(Context)} early from {@code OverdriveApplication}.
 * Standalone daemons call {@link #syncFromEnv()} + {@link #ensureDir()} at
 * {@code main} (they inherit {@code OVERDRIVE_SCRATCH} from the watchdog).
 */
public final class ScratchPaths {

    private static final String TAG = "ScratchPaths";

    public static final String LEGACY_DIR = "/data/local/tmp";
    public static final String ENV_SCRATCH = "OVERDRIVE_SCRATCH";

    private static final String PREFS = "scratch_paths";
    private static final String KEY_RESOLVED = "resolved_dir";
    private static final String PROBE_NAME = ".od_write_probe";

    private static final Object LOCK = new Object();

    private static volatile Context appContext;
    private static volatile String fallbackDir;
    /** Null until a probe (local or shell) or prefs/env sets it. */
    private static volatile String resolvedDir;
    private static volatile boolean probed;

    private ScratchPaths() {}

    /**
     * App-process init: set fallback, restore cache, sync env, try a local
     * legacy write probe. Does not force fallback on local failure — shell
     * UID may still be able to write legacy (Sealion); call
     * {@link #probeViaShell} after ADB connects.
     */
    public static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        appContext = app;

        File ext = app.getExternalFilesDir(null);
        if (ext != null) {
            fallbackDir = new File(ext, "daemon").getAbsolutePath();
        } else {
            fallbackDir = new File(app.getFilesDir(), "daemon").getAbsolutePath();
        }

        synchronized (LOCK) {
            loadCachedLocked();
            syncFromEnvLocked();
            if (!probed) {
                probeLocalWriteLocked();
            }
        }
        ensureDir();
        Log.i(TAG, "init: dir=" + getDir()
                + " legacy=" + usesLegacyDir()
                + " probed=" + probed
                + " fallback=" + fallbackDir);
    }

    /** Inherit {@code OVERDRIVE_SCRATCH} from the parent watchdog / shell. */
    public static void syncFromEnv() {
        synchronized (LOCK) {
            syncFromEnvLocked();
        }
    }

    /**
     * Try creating {@code LEGACY_DIR/.od_write_probe} from this process.
     * Success → resolve to legacy. Failure does <b>not</b> select fallback
     * (app UID often cannot write legacy even on Sealion).
     */
    public static boolean probeLocalWrite() {
        synchronized (LOCK) {
            return probeLocalWriteLocked();
        }
    }

    /**
     * Same probe via an already-connected ADB/shell runner. Success → legacy;
     * failure → fallback. Call after ADB connect.
     *
     * @param shellRunner executes a shell command; returns stdout, or null on failure
     */
    public static boolean probeViaShell(ShellRunner shellRunner) {
        if (shellRunner == null) return false;
        String probePath = LEGACY_DIR + "/" + PROBE_NAME;
        String cmd = "mkdir -p " + LEGACY_DIR
                + " && echo ok > " + probePath
                + " && cat " + probePath
                + " && rm -f " + probePath
                + " && echo PROBE_OK";
        try {
            String out = shellRunner.run(cmd);
            boolean ok = out != null && out.contains("PROBE_OK");
            synchronized (LOCK) {
                if (ok) {
                    setResolvedLocked(LEGACY_DIR);
                } else {
                    String fb = fallbackOrLegacy();
                    setResolvedLocked(fb);
                }
                probed = true;
            }
            ensureDir();
            Log.i(TAG, "probeViaShell: ok=" + ok + " dir=" + getDir());
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "probeViaShell failed: " + t.getMessage());
            synchronized (LOCK) {
                setResolvedLocked(fallbackOrLegacy());
                probed = true;
            }
            ensureDir();
            return false;
        }
    }

    public interface ShellRunner {
        /** Run {@code command} and return combined output, or null on failure. */
        String run(String command) throws Exception;
    }

    /** Resolved scratch directory. Env → resolved → legacy if never probed. */
    public static String getDir() {
        String env = getenvScratch();
        if (env != null && !env.isEmpty()) return trimTrailingSlash(env);

        String resolved = resolvedDir;
        if (resolved != null && !resolved.isEmpty()) return resolved;

        return LEGACY_DIR;
    }

    public static boolean usesLegacyDir() {
        return LEGACY_DIR.equals(getDir());
    }

    /**
     * Map a relative name or a legacy absolute path onto the resolved scratch
     * dir. Non-legacy absolute paths outside legacy are returned unchanged.
     */
    public static String path(String name) {
        if (name == null || name.isEmpty()) return getDir();

        String dir = getDir();
        if (name.startsWith("/")) {
            if (name.equals(LEGACY_DIR) || name.startsWith(LEGACY_DIR + "/")) {
                if (LEGACY_DIR.equals(dir)) return name;
                String rest = name.substring(LEGACY_DIR.length());
                if (rest.startsWith("/")) rest = rest.substring(1);
                return rest.isEmpty() ? dir : dir + "/" + rest;
            }
            return name;
        }
        return dir + "/" + name;
    }

    /** {@code export OVERDRIVE_SCRATCH=…} / {@code TMPDIR=…} / {@code mkdir -p}. */
    public static String[] scratchEnvLines() {
        String dir = shellSingleQuote(getDir());
        return new String[] {
                "export " + ENV_SCRATCH + "=" + dir,
                "export TMPDIR=" + dir,
                "mkdir -p " + dir + " 2>/dev/null"
        };
    }

    /** Prefix suitable for {@code sh -c} chaining (ends with {@code && }). */
    public static String shellPrefix() {
        String dir = shellSingleQuote(getDir());
        return "export " + ENV_SCRATCH + "=" + dir
                + "; export TMPDIR=" + dir
                + "; mkdir -p " + dir + " 2>/dev/null; ";
    }

    /**
     * Rewrite legacy absolute paths in a shell command to the resolved dir.
     * No-op when still on legacy.
     */
    public static String remapShell(String command) {
        if (command == null || command.isEmpty()) return command;
        if (usesLegacyDir()) return command;
        String dir = getDir();
        if (dir.equals(LEGACY_DIR)) return command;
        // Longest-first: avoid partial rewrites leaving a mixed path.
        return command.replace(LEGACY_DIR, dir);
    }

    /** Env exports + legacy→resolved remapping for a shell command. */
    public static String prepareShellCommand(String command) {
        if (command == null) return shellPrefix();
        return shellPrefix() + remapShell(command);
    }

    /**
     * Same as {@link #prepareShellCommand} — use when the payload launches
     * {@code app_process} / native children that must inherit scratch env.
     */
    public static String prepareExecShell(String command) {
        return prepareShellCommand(command);
    }

    public static void ensureDir() {
        String dir = getDir();
        try {
            File f = new File(dir);
            if (!f.exists() && !f.mkdirs()) {
                Log.w(TAG, "ensureDir: mkdirs failed for " + dir);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureDir: " + t.getMessage());
        }
    }

    // ==================== internals ====================

    private static void syncFromEnvLocked() {
        String env = getenvScratch();
        if (env != null && !env.isEmpty()) {
            setResolvedLocked(trimTrailingSlash(env));
            probed = true;
        }
    }

    private static boolean probeLocalWriteLocked() {
        File probe = new File(LEGACY_DIR, PROBE_NAME);
        FileOutputStream fos = null;
        try {
            File parent = probe.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                // May already exist but be unwritable — fall through to write.
            }
            fos = new FileOutputStream(probe);
            fos.write('o');
            fos.flush();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            setResolvedLocked(LEGACY_DIR);
            probed = true;
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "probeLocalWrite: legacy not writable (" + t.getMessage() + ")");
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void loadCachedLocked() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String cached = prefs.getString(KEY_RESOLVED, null);
            if (cached != null && !cached.isEmpty()) {
                resolvedDir = cached;
                probed = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadCached: " + t.getMessage());
        }
    }

    private static void setResolvedLocked(String dir) {
        if (dir == null || dir.isEmpty()) return;
        resolvedDir = trimTrailingSlash(dir);
        persistLocked();
    }

    private static void persistLocked() {
        Context ctx = appContext;
        String dir = resolvedDir;
        if (ctx == null || dir == null) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RESOLVED, dir)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist: " + t.getMessage());
        }
    }

    private static String fallbackOrLegacy() {
        String fb = fallbackDir;
        return (fb != null && !fb.isEmpty()) ? fb : LEGACY_DIR;
    }

    private static String getenvScratch() {
        try {
            return System.getenv(ENV_SCRATCH);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimTrailingSlash(String path) {
        if (path == null || path.length() <= 1) return path;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** Single-quote for shell, escaping embedded single quotes. */
    private static String shellSingleQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Debug / diagnostics. */
    public static String describe() {
        return String.format(Locale.US,
                "ScratchPaths{dir=%s legacy=%s probed=%s fallback=%s}",
                getDir(), usesLegacyDir(), probed, fallbackDir);
    }
}
