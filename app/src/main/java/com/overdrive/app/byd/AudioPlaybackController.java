package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.File;

/**
 * Plays a user-provided audio/video file (MP3 / WAV / MP4 / …) for the "Play
 * Audio" / "Play Video" automation + key-mapping actions.
 *
 * <p><b>Why this delegates to the app process.</b> This class runs inside the
 * {@code app_process} daemon (UID 2000, synthetic {@code PermissionBypassContext}).
 * A {@link android.media.MediaPlayer} created there cannot play: on this firmware
 * {@code prepare()} fails immediately with {@code status=0x80000000} (media-framework
 * UNKNOWN_ERROR) — the media extractor / mediaserver does not service the headless
 * daemon process, so preparation dies before any track exists. This was confirmed on
 * device: {@code ensureAudible} and {@code requestAudioFocus} both SUCCEED, then
 * {@code play: setup failed: Prepare failed.: status=0x80000000} on every attempt.
 * (The daemon <i>can</i> set volume — that's a privileged Binder settings call, not a
 * MediaPlayer track — which is why volume worked while playback never did.)
 *
 * <p>So playback runs in the REAL app process, where a framework MediaPlayer prepares
 * normally. The daemon reaches it with the SAME proven bridge it already uses for the
 * RoadSense IMU / Location sidecars and the Screen Deterrent: a shell
 * {@code am start-foreground-service} / {@code am start} exec against an exported
 * component (the daemon's synthetic context cannot {@code startForegroundService}
 * cross-process — that is a silent no-op).
 *
 * <ul>
 *   <li><b>Audio</b> → {@code MediaPlaybackService} (app-process foreground service).</li>
 *   <li><b>Video</b> (picture on screen) → {@code VideoPlaybackActivity} (app-process
 *       fullscreen player) — no daemon-owned SurfaceControl needed.</li>
 *   <li><b>Stop</b> → stop the service + broadcast a stop the video activity honours.</li>
 * </ul>
 *
 * <p><b>File transport.</b> Library sounds live under {@code /data/local/tmp/.overdrive/audio},
 * which the app UID (SELinux {@code untrusted_app}) cannot read directly — the locale /
 * device-id managers document the same cross-UID wall, and the app already reads daemon
 * files there only via a shell exec. So for a library file we pass its NAME and the app
 * streams the bytes from the daemon's authenticated {@code /api/audio/library/raw}
 * endpoint (the model the recordings player uses). An explicit {@code /storage} path
 * (the advanced escape hatch) is handed to the app as a path — the app CAN read shared
 * external storage directly.
 */
public final class AudioPlaybackController {

    private static final String TAG = "AudioPlayback";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Library dir whose files the app can't read directly (mirror of AudioApiHandler
    // / VehicleControlApiHandler). A path under here → stream by name; anything else
    // (e.g. /storage/emulated/0/Music/x.mp3) → the app opens it directly.
    private static final String AUDIO_LIBRARY_DIR = ScratchPaths.path(".overdrive/audio");

    // Exported app-process components (see AndroidManifest). Our own package — the same
    // literal the Screen Deterrent / sidecars use in their `am` execs.
    private static final String AUDIO_SERVICE =
            "com.overdrive.app/.services.MediaPlaybackService";
    private static final String CHIME_SERVICE =
            "com.overdrive.app/.services.RoadSenseChimePlaybackService";
    private static final String VIDEO_ACTIVITY =
            "com.overdrive.app/.ui.VideoPlaybackActivity";
    /** Broadcast the audio service + video activity both stop on. */
    private static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";
    private static final String PKG = "com.overdrive.app";
    /** FIFO bridge so rapid Play/Stop edges cannot overtake each other in separate `am` processes. */
    private static final java.util.concurrent.ExecutorService MEDIA_COMMANDS =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "OverDriveMediaCommands");
                t.setDaemon(true);
                return t;
            });

    private AudioPlaybackController() {}

    /** Audio only, no loop. Kept for callers that don't need loop/video. */
    public static boolean play(String path, String channel) {
        return play(path, channel, false);
    }

    /**
     * Play {@code path} on {@code channel} (audio only), optionally looping. Returns
     * true if the play command was dispatched to the app process (the app reports the
     * real prepare/play result to its own log); false only if the path is empty/invalid.
     * Any current playback is replaced app-side (single-player).
     */
    public static boolean play(String path, String channel, boolean loop) {
        return dispatchPlay(path, channel, loop, false);
    }

    /**
     * Play a video with its PICTURE on the head-unit screen. Video audio always uses the
     * Media channel: the TextureView player's default media attributes are the only
     * configuration proven not to stall video frames on this DiLink build. Launches the
     * app-process {@code VideoPlaybackActivity}; the activity self-manages from there.
     *
     * <p><b>Return contract: true means DISPATCHED, not "playing".</b> The launch is
     * deliberately asynchronous — {@code am start} takes 2-3s on a cold app spawn, and
     * blocking the caller would stall an HTTP worker or the keymap fire thread — so this
     * cannot report the launch's real outcome, and callers that surface it (e.g.
     * {@code /api/vehicle/play-audio}'s {@code success} field) mean "queued". The actual
     * result is logged by {@link #execLogged} under this class's tag, and the activity
     * logs its own prepare/play result; that pair is the source of truth when a clip
     * doesn't appear. False is returned only for an unusable path.
     */
    public static boolean playVideoOnScreen(String path, boolean loop) {
        return dispatchPlay(path, "media", loop, true);
    }

    /**
     * Play a BUNDLED {@code res/raw} asset (by resource name, extension-agnostic) on
     * {@code channel} at {@code volumePercent} (1..100). Used by RoadSense approach chimes.
     *
     * <p>Why the chimes come through here rather than a daemon-side SoundPool: a
     * SoundPool/MediaPlayer in the daemon process can't decode (the {@code 0x80000000}
     * prepare failure documented above), and — the actual point — only an app-process
     * player knows how to reach the OEM-extended nav/voice streams (both
     * {@code setLegacyStreamType} and {@code setAudioStreamType}, plus a legacy
     * stream-typed focus request; see {@code MediaPlaybackService.applyChannelRouting}).
     * Routing a chime "to the navigation channel" is exactly that recipe, so the chime
     * uses the same proven routing recipe as Play Audio. It runs in a dedicated service
     * so a warning cannot replace looping Automation Audio (and vice versa).
     *
     * <p><b>Extras are DELIBERATELY limited to {@code --es}/{@code --ez}</b> — the exact
     * flag set the audible {@link #play} path uses. A chime dispatched with an additional
     * {@code --ei} extra was silent on device while {@code play} on the same channel was
     * audible, and an `am` option this firmware's {@code Intent.parseCommandArgs} doesn't
     * accept throws and aborts the WHOLE command before any extra is read — invisibly,
     * because we don't wait on it (the {@code --activity-new-task} failure mode). Volume
     * therefore rides as a STRING and is parsed app-side. Do not add typed extras here
     * without verifying on device.
     *
     * <p>Uses {@link #execLogged} rather than fire-and-forget {@code exec}: a chime that
     * never plays is otherwise indistinguishable from one that played silently, since the
     * daemon log only ever recorded "dispatched".
     */
    public static boolean playRawResource(String resName, String channel, int volumePercent) {
        if (resName == null || resName.trim().isEmpty()) {
            logger.warn("playRawResource: empty resource name");
            return false;
        }
        String ch = (channel == null || channel.trim().isEmpty()) ? "media" : channel.trim();
        int pct = Math.max(1, Math.min(100, volumePercent));
        execLogged("am start-foreground-service -n " + CHIME_SERVICE
                + " --es action chime"
                + " --es resName " + q(resName.trim())
                + " --es channel " + q(ch)
                + " --es volumePercent " + q(String.valueOf(pct))
                + " --ez loop false",
                "playRawResource");
        logger.info("playRawResource: dispatched " + resName.trim()
                + " (channel=" + ch + " vol=" + pct + "%)");
        return true;
    }

    /**
     * Speak {@code text} aloud via TextToSpeech on {@code channel}. Like playback, TTS
     * cannot run in the headless daemon (no usable TTS service binding), so this
     * dispatches to the app-process {@link #AUDIO_SERVICE} via the same `am` bridge.
     */
    public static boolean speak(String text, String channel) {
        if (text == null || text.trim().isEmpty()) {
            logger.warn("speak: empty text");
            return false;
        }
        String ch = (channel == null || channel.trim().isEmpty()) ? "voice" : channel.trim();
        exec("am start-foreground-service -n " + AUDIO_SERVICE
                + " --es action speak"
                + " --es text " + q(text)
                + " --es channel " + q(ch));
        logger.info("speak: dispatched to MediaPlaybackService (channel=" + ch + ")");
        return true;
    }

    /** Stop any audio or video started by a play above. Idempotent. */
    public static void stop() {
        // Stop user-started automation/keymap audio and video. RoadSense warning chimes
        // are isolated safety cues and intentionally do not share this cancellation path.
        exec("am stopservice -n " + AUDIO_SERVICE
                + "; am broadcast -a " + ACTION_STOP + " -p " + PKG);
        logger.info("stop: dispatched automation audio stop + video stop broadcast");
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Validate the file and shell the appropriate app-process launch. The app can't
     * read the library dir, so a library file rides as a name (streamed from the raw
     * endpoint); an external-storage file rides as a path (the app reads it directly).
     */
    private static boolean dispatchPlay(String path, String channel, boolean loop, boolean onScreen) {
        if (path == null || path.trim().isEmpty()) {
            logger.warn("play: empty path");
            return false;
        }
        File f = new File(path.trim());
        String requestedChannel = (channel == null || channel.trim().isEmpty())
                ? "media" : channel.trim();
        // The video player must use MediaPlayer's untouched default media attributes.
        // Enforce that here too, so a future API caller cannot reintroduce a channel that
        // the fullscreen player cannot safely honour.
        String ch = onScreen ? "media" : requestedChannel;

        // Decide transport: library name (streamed) vs direct file path. Keep the two as
        // discrete values (libName XOR filePath) so both the Intent path and the shell
        // fallback below can use them.
        String libName = null, filePath = null;
        try {
            String canon = f.getCanonicalPath();
            if (canon.startsWith(AUDIO_LIBRARY_DIR)) {
                libName = f.getName();
            } else {
                // Direct path — must exist and be readable when the app opens it. We
                // don't stat here (daemon UID differs from app UID); the app validates.
                filePath = canon;
            }
        } catch (Exception e) {
            logger.warn("play: path resolve failed: " + e.getMessage());
            return false;
        }
        String srcArgs = (libName != null) ? ("--es libName " + q(libName)) : ("--es filePath " + q(filePath));

        if (onScreen) {
            // Launch the fullscreen video player with `am start`, the bridge the daemon
            // (UID 2000) must use because its synthetic PermissionBypassContext is not
            // backed by a real ActivityThread record — startActivity there can throw or
            // silently no-op. Bare `am start` of an activity is NOT BAL-blocked for this
            // daemon (ServiceLauncher's MainActivity launch proves it).
            //
            // NO `--activity-*` FLAGS. This is the bug that made "Play Video do nothing":
            // the command used to pass `--activity-new-task`, which is NOT a valid `am`
            // option — {@code Intent.parseCommandArgs} has no case for it (only
            // --activity-clear-task / -no-history / -multiple-task / … exist), so its
            // `default:` branch throws IllegalArgumentException("Unknown option: …") and
            // ShellCommand.exec aborts the WHOLE command before the extras are even
            // parsed. No activity was ever started, and because the output is redirected
            // to /dev/null the exception was invisible while we still logged "dispatched".
            // NEW_TASK is unnecessary anyway: ActivityManagerShellCommand.runStartActivity
            // unconditionally adds FLAG_ACTIVITY_NEW_TASK itself. --activity-clear-task is
            // also dropped: the activity is singleTask and its onNewIntent already swaps
            // the clip in place, whereas clear-task would destroy the instance instead.
            // Extras carry the clip + loop. Video audio is intentionally fixed to Media, so
            // no channel extra is sent. MAIN (not VIEW), no --user 0 (am
            // defaults to the current user).
            //
            // Output is captured (not >/dev/null) so a future launch failure is
            // diagnosable from the daemon log instead of silently swallowed.
            execLogged("am start -n " + VIDEO_ACTIVITY
                    + " -a android.intent.action.MAIN"
                    + " " + srcArgs
                    + " --ez loop " + loop,
                    "playVideoOnScreen");
            logger.info("playVideoOnScreen: dispatched to VideoPlaybackActivity via am start (channel=" + ch + " loop=" + loop + ")");
        } else {
            // Foreground audio service (same `am start-foreground-service` bridge as the sidecars).
            exec("am start-foreground-service -n " + AUDIO_SERVICE
                    + " --es action play"
                    + " " + srcArgs
                    + " --es channel " + q(ch)
                    + " --ez loop " + loop);
            logger.info("play: dispatched to MediaPlaybackService (channel=" + ch + " loop=" + loop + ")");
        }
        return true;
    }

    /**
     * Shell-quote one `am` extra value (filenames may contain spaces). Wrap in single
     * quotes and escape embedded single quotes the POSIX way ('\'').
     */
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Queue media bridge commands on one daemon worker. The worker waits with a hard bound so
     * Play/Stop requests stay FIFO without blocking the automation or HTTP caller.
     */
    private static void exec(String cmd) {
        try {
            MEDIA_COMMANDS.execute(() -> runQuietCommand(cmd));
        } catch (Throwable t) {
            logger.warn("could not queue media command [" + cmd + "]: " + t.getMessage());
        }
    }

    private static void runQuietCommand(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "(" + cmd + ") >/dev/null 2>&1"});
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                logger.warn("media command timed out [" + cmd + "]");
            } else if (p.exitValue() != 0) {
                logger.warn("media command failed (exit=" + p.exitValue() + ") [" + cmd + "]");
            }
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) {} }
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }

    /**
     * Run an {@code am} command on a background thread and LOG its combined output plus
     * exit code. Unlike a fire-and-forget exec, this makes a failed launch visible: `am`
     * reports a bad option / unresolved component / BAL block on stderr and exits non-zero,
     * and swallowing that is what hid the invalid {@code --activity-new-task} flag (see
     * {@link #dispatchPlay}) — the daemon logged "dispatched" while nothing ever started.
     *
     * <p>All waiting happens on a short-lived daemon thread, never on the caller (an HTTP
     * worker or the keymap fire thread), because {@code am start} can take 2-3s on a cold
     * app spawn. Structure matters here: the drain runs on its OWN thread so the main wait
     * is {@code waitFor(timeout)} rather than a read-to-EOF — a read-to-EOF is unbounded
     * (a grandchild inheriting the pipe keeps it open even after `am` exits, which would
     * pin the thread indefinitely) whereas {@code waitFor} always returns. Draining
     * concurrently also means a chatty `am` can never block on a full pipe. The child is
     * force-killed on timeout, so nothing leaks. Output is capped — only the first lines
     * are needed to diagnose a failure.
     *
     * <p>Failure detection uses the OUTPUT TEXT, not just the exit code:
     * {@code ActivityManagerShellCommand.runStartActivity} returns 0 even when the launch
     * fails, printing {@code "Error: …"} (every failure branch) — while the benign
     * repeat-launch cases print {@code "Warning: …"} and are genuinely successful. So
     * matching on "Error" is both necessary and free of false positives.
     */
    private static void execLogged(String cmd, String tag) {
        Thread t = new Thread(() -> {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
                pb.redirectErrorStream(true);
                p = pb.start();
                final StringBuilder sb = new StringBuilder();
                final java.io.InputStream is = p.getInputStream();
                Thread drain = new Thread(() -> {
                    byte[] buf = new byte[1024];
                    try {
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            synchronized (sb) {
                                if (sb.length() < 1024) sb.append(new String(buf, 0, n));
                            }
                            // keep draining past the cap so the child never blocks
                        }
                    } catch (Throwable ignored) { /* stream closed on exit/kill */ }
                }, "am-" + tag + "-drain");
                drain.setDaemon(true);
                drain.start();

                boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    logger.warn(tag + ": am timed out");
                    return;
                }
                // The child has exited, so its end of the pipe is closed and the drain is
                // guaranteed to reach EOF and terminate — join with a bound purely as a
                // backstop against a grandchild that inherited the pipe. Crucially, we must
                // know WHETHER the drain finished: reporting "ok" off a buffer the drain
                // hadn't filled yet would silently swallow an `am` failure, which is the
                // exact bug this method exists to prevent (am exits 0 even when it prints
                // "Error: Activity class … does not exist"). So if the drain did NOT finish,
                // we say the outcome is unknown instead of claiming success.
                drain.join(2000);
                boolean drained = !drain.isAlive();
                try { is.close(); } catch (Throwable ignored) {}
                int code = p.exitValue();
                String out;
                synchronized (sb) { out = sb.toString().trim(); }
                if (code != 0 || out.contains("Error")) {
                    logger.warn(tag + ": am FAILED (exit=" + code + ") " + out);
                } else if (!drained) {
                    logger.warn(tag + ": am exit=" + code
                            + " but output not fully read — outcome unverified"
                            + (out.isEmpty() ? "" : (": " + out)));
                } else if (!out.isEmpty()) {
                    logger.debug(tag + ": am ok — " + out);
                }
            } catch (Throwable th) {
                if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) {} }
                logger.warn(tag + ": exec failed [" + cmd + "]: " + th.getMessage());
            }
        }, "am-" + tag);
        t.setDaemon(true);
        t.start();
    }
}
