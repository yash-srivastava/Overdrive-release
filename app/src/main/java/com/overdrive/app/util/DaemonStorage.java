package com.overdrive.app.util;

/**
 * Single source of truth for the daemon working directory.
 *
 * <p>Overdrive's daemons keep their locks, pids, logs, config and sidecar
 * scratch files under {@code /data/local/tmp}, which is shell-writable on the
 * DiLink 3/4 head units Overdrive originally targeted. BYD's DiLink 5.0
 * firmware (e.g. the Shark 6, built on Desay SV) labels {@code /data/local/tmp}
 * as {@code data_local} instead of {@code shell_data_file}, so the SELinux
 * {@code shell} domain cannot write there and every daemon dies at its first
 * lock/pid write.
 *
 * <p>This helper leaves the legacy path untouched for every existing vehicle
 * ({@link #rebase(String)} returns its argument verbatim) and only relocates to
 * the shell user's own data directory when relocation is active — either auto-detected on a
 * DiLink 5 platform or forced by the {@code diagnostics.daemonStorageRelocated}
 * toggle. Non-DiLink-5 behaviour is therefore byte-identical to upstream.
 */
public final class DaemonStorage {

    /** Legacy, shell-writable on DiLink 3/4. */
    public static final String LEGACY_BASE = "/data/local/tmp";

    /**
     * The shell user's own device-encrypted data directory. Labelled
     * {@code shell_data_file} like {@code /data/local/tmp} on DiLink 3/4, so the
     * shell domain can write and exec there, it is on {@code /data} (no
     * dependency on the FUSE external-storage mount coming up) and it survives
     * app reinstall. The app uid can read it — never write — once the parent is
     * traversable, which mirrors the legacy {@code /data/local/tmp} contract.
     */
    static final String SHELL_DATA_DIR = "/data/user_de/0/com.android.shell";

    /** Relocation target. */
    public static final String RELOCATED_BASE = SHELL_DATA_DIR + "/overdrive";

    /** Earlier relocation target, migrated once into {@link #RELOCATED_BASE}. */
    static final String PREVIOUS_RELOCATED_BASE = "/sdcard/overdrive";

    /**
     * Idempotent shell prelude for relocated platforms: create the base, make
     * the shell data dir traversable (it ships 0700) so the app can read daemon
     * files as it does under {@code /data/local/tmp}, and migrate the previous
     * external-storage base exactly once. Concurrent callers wait for the
     * migrating one so no daemon starts against a half-copied config.
     * TMPDIR is exported because mksh writes here-document temp files to
     * /data/local/tmp by default, which the shell domain cannot create on
     * DiLink 5; daemons launched by the command inherit it.
     */
    private static final String RELOCATED_PRELUDE =
            "mkdir -p " + RELOCATED_BASE + " 2>/dev/null; "
            + "export TMPDIR=" + RELOCATED_BASE + "; "
            + "chmod 711 " + SHELL_DATA_DIR + " 2>/dev/null; "
            + "chmod 771 " + RELOCATED_BASE + " 2>/dev/null; "
            + "if [ ! -e " + RELOCATED_BASE + "/.migrated ]; then "
            + "if mkdir " + RELOCATED_BASE + "/.migrating 2>/dev/null; then "
            + "[ -d " + PREVIOUS_RELOCATED_BASE + " ] && cp -R "
            + PREVIOUS_RELOCATED_BASE + "/. " + RELOCATED_BASE + "/ 2>/dev/null; "
            + "touch " + RELOCATED_BASE + "/.migrated; "
            + "rmdir " + RELOCATED_BASE + "/.migrating 2>/dev/null; "
            + "else i=0; while [ ! -e " + RELOCATED_BASE + "/.migrated ] && [ $i -lt 60 ]; "
            + "do sleep 1; i=$((i+1)); done; fi; fi; ";

    private static volatile Boolean relocated;
    private static final ThreadLocal<Boolean> RESOLVING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DaemonStorage() {}

    /**
     * Whether the daemon working directory has been relocated off the legacy
     * path. Cached after the first resolution (mode changes require a daemon
     * restart anyway). Never throws.
     *
     * <p>The platform signal is firmware-level and file-independent (the DiLink
     * 5 AIS camera utility lives at a fixed system path), so it is identical in
     * the app and the shell daemon and cannot deadlock against the very path
     * rewriting it drives. A re-entrancy guard keeps the optional config-toggle
     * read — whose own path is rewritten through {@link #rebase(String)} — from
     * recursing during bootstrap: nested calls resolve to the legacy base.
     */
    public static boolean isRelocated() {
        Boolean cached = relocated;
        if (cached != null) return cached;
        if (RESOLVING.get()) return false;
        RESOLVING.set(Boolean.TRUE);
        boolean value = false;
        try {
            // Auto: DiLink 5 firmware labels /data/local/tmp unwritable for the
            // shell domain. Detect the platform by its AIS utility (fixed path).
            try {
                if (com.overdrive.app.camera.dilink5.DiLink5Platform
                        .hasDiLink5CameraHardware()) {
                    value = true;
                }
            } catch (Throwable ignored) {
                // Platform helper unavailable this early — fall through to toggle.
            }
            // Explicit override for any other locked-storage platform.
            if (!value) {
                try {
                    org.json.JSONObject diagnostics =
                            com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                                    .optJSONObject("diagnostics");
                    if (diagnostics != null
                            && diagnostics.optBoolean("daemonStorageRelocated", false)) {
                        value = true;
                    }
                } catch (Throwable ignored) {
                    // Config not readable yet — keep the legacy default.
                }
            }
        } finally {
            RESOLVING.set(Boolean.FALSE);
        }
        relocated = value;
        return value;
    }

    /** The active daemon working directory base. */
    public static String base() {
        return isRelocated() ? RELOCATED_BASE : LEGACY_BASE;
    }

    /** {@code base()} joined with {@code name}. */
    public static String path(String name) {
        return base() + "/" + name;
    }

    /**
     * Rewrites a legacy {@code /data/local/tmp[/...]} path to the active base
     * when relocation is on; returns {@code path} unchanged otherwise (and
     * always for paths that are not under the legacy base). This is the wrapper
     * applied at every daemon path literal / {@code Safe.s(...)} call site, so
     * the default build is unaffected.
     */
    public static String rebase(String path) {
        if (path == null || !isRelocated()) return path;
        if (path.equals(LEGACY_BASE)) return RELOCATED_BASE;
        if (path.startsWith(LEGACY_BASE + "/")) {
            return RELOCATED_BASE + path.substring(LEGACY_BASE.length());
        }
        return path;
    }

    /**
     * Rewrites every legacy-base occurrence inside a shell command / script to
     * the active base when relocation is on; returns {@code command} unchanged
     * otherwise. Applied once at the shell-execution chokepoint so the many
     * daemon-launch and watchdog scripts that embed {@code /data/local/tmp}
     * inline keep working on relocated platforms without touching each literal.
     * No-op on DiLink 3/4, where the legacy base is writable. On relocated
     * platforms the command is also prefixed with {@link #RELOCATED_PRELUDE}
     * (silent, and the command's own exit status is preserved).
     */
    public static String rebaseCommand(String command) {
        if (command == null || !isRelocated()) return command;
        return RELOCATED_PRELUDE + command.replace(LEGACY_BASE, RELOCATED_BASE);
    }

    /** Test seam: drop the cached relocation decision. */
    static void resetCacheForTesting() {
        relocated = null;
    }
}
