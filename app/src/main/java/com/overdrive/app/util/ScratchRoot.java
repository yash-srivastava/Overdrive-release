package com.overdrive.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the directory shared by the app, the ADB shell, and the
 * {@code app_process} daemons.
 *
 * <p>{@link #LEGACY_DIR} is the only root on every shipped head unit. Firmware
 * that labels it so the shell cannot create there (BYD Shark 6, DiLink 5.0
 * {@code Di5.0_DXF}) selects {@link #ALT_DIR} instead, via an explicit user
 * opt-in — never a probe, never a migration.
 *
 * <p>While legacy is active every accessor returns the same literal strings the
 * callers hardcoded before, {@link #remap} and {@link #prepare} are identity,
 * {@link #shellPrefix} is empty and {@link #applyEnv} is a no-op, so command
 * text and process environments are byte-for-byte unchanged on every other
 * vehicle.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@link #ENV_SCRATCH} — set by {@link #shellPrefix()} on every ADB
 *       command, so daemons and their children inherit the live root.</li>
 *   <li>The stored app selection — authoritative in the app process (the app
 *       cannot delete the marker, so a stale marker must never outvote the
 *       user's toggle).</li>
 *   <li>{@link #ALT_MARKER} — daemons started without the environment (an old
 *       watchdog, a manual launch) read the choice from disk.</li>
 *   <li>{@link #LEGACY_DIR}.</li>
 * </ol>
 *
 * <p>Must stay free of {@code android.util.Log} and any other framework call
 * on the read path: the contract tests run the script generators that use
 * {@link #path} on a plain JVM.
 */
public final class ScratchRoot {

    public static final String LEGACY_DIR = "/data/local/tmp";

    /** Shell-owned DE data dir; 0700 by default, chmod 711 so the app can traverse. */
    public static final String ALT_PARENT = "/data/user_de/0/com.android.shell";

    /** Shell-owned, exec-capable, readable by the app once traversable. Not on FUSE. */
    public static final String ALT_DIR = ALT_PARENT + "/overdrive";

    /** Absolute in both modes, so any process can read the selection. */
    public static final String ALT_MARKER = ALT_DIR + "/.od_scratch_root";

    public static final String ENV_SCRATCH = "OVERDRIVE_SCRATCH";
    public static final String ENV_TMPDIR = "TMPDIR";

    private static final String PREFS = "scratch_root";
    private static final String KEY_ALTERNATE = "alternate_enabled";

    private static final Object LOCK = new Object();

    private static volatile String resolvedDir;
    /** Non-null only in the app process, after {@link #bootstrap}. */
    private static volatile Boolean prefAlternate;

    private ScratchRoot() {}

    // ==================== INIT ====================

    /**
     * App-process init. Loads the stored selection so {@link #dir()} is
     * correct before any class that caches a path is initialised. Call first
     * in {@code Application.onCreate}; the manifest declares no
     * {@code ContentProvider}, so nothing runs earlier.
     */
    public static void bootstrap(Context context) {
        if (context == null) return;
        Boolean stored = null;
        try {
            stored = prefs(context).getBoolean(KEY_ALTERNATE, false);
        } catch (Throwable ignored) {
            // No preference storage: fall through to marker/legacy resolution.
        }
        synchronized (LOCK) {
            if (stored != null) prefAlternate = stored;
            resolvedDir = null;
        }
    }

    /** Daemon init. Drops any cached root so the environment is re-read. */
    public static void bootstrapFromEnv() {
        synchronized (LOCK) {
            resolvedDir = null;
        }
    }

    // ==================== SELECTION ====================

    /**
     * Persist the selection. The caller owns stopping daemons, running
     * {@link #enableCommand()} or {@link #disableCommand()} over ADB, and
     * restarting the app process so no cached path outlives the flip.
     */
    public static boolean setAlternateEnabled(Context context, boolean enabled) {
        if (context == null) return false;
        try {
            boolean committed = prefs(context)
                    .edit()
                    .putBoolean(KEY_ALTERNATE, enabled)
                    .commit();
            if (!committed) return false;
            synchronized (LOCK) {
                prefAlternate = enabled;
                resolvedDir = null;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The stored app-side selection. Does not alter resolution state. */
    public static boolean isAlternateSelected(Context context) {
        Boolean cached = prefAlternate;
        if (cached != null) return cached;
        if (context == null) return false;
        try {
            return prefs(context).getBoolean(KEY_ALTERNATE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== RESOLUTION ====================

    public static String dir() {
        String cached = resolvedDir;
        if (cached != null) return cached;
        synchronized (LOCK) {
            if (resolvedDir == null) {
                resolvedDir = resolveLocked();
            }
            return resolvedDir;
        }
    }

    public static boolean usesLegacyDir() {
        return LEGACY_DIR.equals(dir());
    }

    /**
     * Maps a relative name, or an absolute legacy path, onto the live root.
     * Absolute paths outside {@link #LEGACY_DIR} are returned unchanged.
     */
    public static String path(String name) {
        String root = dir();
        if (name == null || name.isEmpty()) return root;
        if (name.charAt(0) != '/') {
            return root + "/" + name;
        }
        if (LEGACY_DIR.equals(root)) return name;
        if (name.equals(LEGACY_DIR)) return root;
        if (name.startsWith(LEGACY_DIR + "/")) {
            return root + name.substring(LEGACY_DIR.length());
        }
        return name;
    }

    /** {@link #path(String)} as a {@link File}. */
    public static File file(String name) {
        return new File(path(name));
    }

    /**
     * Maps {@code name} onto the counterpart scratch root ({@link #ALT_DIR}
     * when currently on {@link #LEGACY_DIR}, or {@link #LEGACY_DIR} when on
     * {@link #ALT_DIR}) so watchdogs and lifecycle commands can observe or
     * clear stop/parked sentinels across a mixed-root split-brain.
     */
    public static String counterpartPath(String name) {
        String root = dir();
        String other = LEGACY_DIR.equals(root) ? ALT_DIR : LEGACY_DIR;
        if (name == null || name.isEmpty()) return other;
        if (name.charAt(0) == '/') {
            if (name.equals(LEGACY_DIR) || name.equals(ALT_DIR)) return other;
            if (name.startsWith(LEGACY_DIR + "/")) {
                return other + name.substring(LEGACY_DIR.length());
            }
            if (name.startsWith(ALT_DIR + "/")) {
                return other + name.substring(ALT_DIR.length());
            }
            return name;
        }
        return other + "/" + name;
    }

    /**
     * Rewrites every legacy root inside a shell command onto the live root.
     * Identity while legacy, so existing command text is unchanged. Only
     * whole path components are rewritten: {@code /data/local/tmp/x} and
     * {@code /data/local/tmp} followed by a delimiter, never
     * {@code /data/local/tmpfs}.
     */
    public static String remap(String command) {
        if (command == null || command.isEmpty()) return command;
        String root = dir();
        if (LEGACY_DIR.equals(root)) return command;
        int idx = command.indexOf(LEGACY_DIR);
        if (idx < 0) return command;
        StringBuilder out = new StringBuilder(command.length() + 64);
        int from = 0;
        int legacyLength = LEGACY_DIR.length();
        while (idx >= 0) {
            int end = idx + legacyLength;
            boolean boundary = end >= command.length()
                    || !isPathWord(command.charAt(end));
            out.append(command, from, idx);
            out.append(boundary ? root : LEGACY_DIR);
            from = end;
            idx = command.indexOf(LEGACY_DIR, from);
        }
        out.append(command, from, command.length());
        return out.toString();
    }

    /**
     * Exports prepended to a shell command. Empty while legacy. {@code TMPDIR}
     * is required: mksh stages heredoc bodies there and adbd points it at
     * {@link #LEGACY_DIR}. The directory and its traversal bit are re-asserted
     * on every command so the root self-heals after an installd fixup.
     */
    public static String shellPrefix() {
        String root = dir();
        if (LEGACY_DIR.equals(root)) return "";
        String quoted = shellQuote(root);
        StringBuilder prefix = new StringBuilder(160);
        prefix.append("export ").append(ENV_SCRATCH).append('=').append(quoted)
                .append("; export ").append(ENV_TMPDIR).append('=').append(quoted)
                .append("; mkdir -p ").append(quoted).append(" 2>/dev/null");
        if (ALT_DIR.equals(root)) {
            prefix.append("; chmod 711 ").append(shellQuote(ALT_PARENT))
                    .append(' ').append(quoted).append(" 2>/dev/null");
        }
        prefix.append("; ");
        return prefix.toString();
    }

    /** {@link #shellPrefix()} plus {@link #remap(String)}. Identity while legacy. */
    public static String prepare(String command) {
        if (command == null) return shellPrefix();
        String root = dir();
        if (LEGACY_DIR.equals(root)) return command;
        return shellPrefix() + remap(command);
    }

    /**
     * Stamps the live root into a child's environment. No-op while legacy, so
     * a {@link ProcessBuilder} in legacy mode inherits exactly what it did
     * before. Children usually inherit these already; this covers a daemon
     * that was itself started without them.
     */
    public static void applyEnv(Map<String, String> environment) {
        if (environment == null) return;
        String root = dir();
        if (LEGACY_DIR.equals(root)) return;
        environment.put(ENV_SCRATCH, root);
        environment.put(ENV_TMPDIR, root);
    }

    /** Convenience for {@link #applyEnv(Map)} on a builder. */
    public static ProcessBuilder applyEnv(ProcessBuilder builder) {
        if (builder != null) applyEnv(builder.environment());
        return builder;
    }

    // ==================== TOGGLE COMMANDS ====================

    /**
     * Shell text the app runs over ADB when the user turns the toggle on:
     * creates the root, makes it traversable and publishes the marker. Prints
     * {@code OD_SCRATCH_ON} on success so the caller can verify.
     */
    public static String enableCommand() {
        String dir = shellQuote(ALT_DIR);
        String marker = shellQuote(ALT_MARKER);
        return "mkdir -p " + dir
                + " && chmod 711 " + shellQuote(ALT_PARENT) + " " + dir
                + " && : > " + marker
                + " && chmod 644 " + marker
                + " && echo OD_SCRATCH_ON";
    }

    /**
     * Shell text the app runs over ADB when the user turns the toggle off.
     * Removes only the marker; files in the alternate root are left for the
     * user, exactly as legacy files are never touched by opt-in.
     */
    public static String disableCommand() {
        return "rm -f " + shellQuote(ALT_MARKER) + " 2>/dev/null; echo OD_SCRATCH_OFF";
    }

    public static final String ENABLE_OK = "OD_SCRATCH_ON";
    public static final String DISABLE_OK = "OD_SCRATCH_OFF";

    // ==================== DIAGNOSTICS ====================

    public static String describe() {
        return String.format(Locale.US,
                "ScratchRoot{dir=%s legacy=%s pref=%s env=%s marker=%s}",
                dir(), usesLegacyDir(), prefAlternate, getenv(), markerPresent());
    }

    // ==================== INTERNAL ====================

    private static String resolveLocked() {
        String env = getenv();
        if (env != null) {
            String trimmed = trimSlashes(env);
            if (!trimmed.isEmpty() && trimmed.charAt(0) == '/') return trimmed;
        }
        Boolean pref = prefAlternate;
        if (pref != null) {
            return pref ? ALT_DIR : LEGACY_DIR;
        }
        return markerPresent() ? ALT_DIR : LEGACY_DIR;
    }

    private static boolean markerPresent() {
        try {
            return new File(ALT_MARKER).isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String getenv() {
        try {
            return System.getenv(ENV_SCRATCH);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimSlashes(String path) {
        String value = path.trim();
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isPathWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }

    static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Test seam: pins the resolved root (or clears it with {@code null} so the
     * next {@link #dir()} resolves again). Package-private; production code
     * never calls it.
     */
    static void setResolvedDirForTest(String dir) {
        synchronized (LOCK) {
            resolvedDir = dir;
        }
    }
}
