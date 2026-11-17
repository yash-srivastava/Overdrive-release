package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Drives the AVAS (Acoustic Vehicle Alerting System) external / "pedestrian"
 * speaker on DiLink 3.0 head units.
 *
 * <h2>Why this exists (and why it's separate from BydDataCollector's AVAS methods)</h2>
 *
 * {@link BydDataCollector#setExteriorSpeakerState(int)} /
 * {@link BydDataCollector#setAVASSoundSource(int)} reach AVAS through the
 * {@code BYDAutoMultimediaDevice} named-method API. On DiLink 3.0 (head unit
 * {@code 6125f}) that device's {@code getInstance()} returns null — there is no
 * {@code bydauto.multimedia} binder registered — so those setters are no-ops.
 *
 * This controller instead uses the <b>legacy generic binder</b>:
 * {@code getSystemService("auto")} → {@code BYDAutoManager.setInt(device, featureId, value)}.
 * That's the SAME binder {@link com.overdrive.app.camera.BydApaViewpointHelper}
 * already drives successfully from the daemon (uid 2000) for panorama viewpoint,
 * so it is NOT subject to the uid-2000 ContentProvider wall that blocks the
 * {@code ICarPropertyService} path. Device id {@code 1002} is the audio/multimedia
 * feature space.
 *
 * <h2>What it can and cannot do</h2>
 *
 * <ul>
 *   <li><b>AVAH factory test tones</b> — audible beeps/chimes on the exterior
 *       speaker, built from the MCU's built-in tone generator (1/2/3 kHz pitches).
 *       The 8 patterns here are ported from a verified door-sound
 *       {@code PatternRunner}.</li>
 *   <li><b>Engine-sound simulator presets</b> — select among the MCU's stored
 *       "engine" sounds (the factory Boombox presets) and toggle the simulator.</li>
 *   <li><b>NOT custom audio.</b> The MCU DSP hard-separates the SoC I2S input
 *       (routed cabin-only) from the AVAH tone generator (routed AVAS-only). No
 *       CAN command bridges them, and AVAS volume is MCU-firmware-hardcoded. So
 *       arbitrary PCM / TTS / files can never play on the exterior speaker — only
 *       MCU-stored presets and the AVAH tone generator. This matches BYD's own
 *       audio architecture; do not try to route STREAM_MUSIC here.</li>
 * </ul>
 *
 * <p><b>Prerequisite:</b> AVAS must be enabled in the vehicle's
 * Settings &gt; Notification menu, otherwise the MCU rejects the tone writes.
 *
 * <p>All methods are safe to call when the {@code auto} service is unavailable
 * (car fully asleep / non-BYD build): they return false / {@code UNSUPPORTED}
 * rather than throwing.
 */
public final class AvasController {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AvasController");

    /** Audio/multimedia device id in the BYDAutoManager feature space. */
    private static final int DEVICE_AUDIO = 1002;

    // ── AVAH test-tone feature IDs (SOC→MCU writes, 0xAA / 0x6E prefix) ──
    // Enabler block — all must be set before the tone generator produces output.
    private static final int FID_PA_CONTROL   = 0xAA000148; // power amp for diagnostic path
    private static final int FID_MCU_SPEAK    = 0xAA000142; // MCU speaker test mode
    private static final int FID_FM_SPEAK     = 0xAA00011A; // FM speaker path
    private static final int FID_TEST_AVAS    = 0xAA000104; // AVAS test audio / pitch select
    private static final int FID_AVAS_CFG     = 0xAA000171; // AVAS test-mode config
    private static final int FID_CHANNEL_MUTE = 0xAA00011E; // channel mute (0 = unmute)
    private static final int FID_AVAH_TONE    = 0x6E970010; // AVAH tone: 0=stop,1=1kHz,2=2kHz,3=3kHz

    // Tone-generator pitch values written to FID_TEST_AVAS.
    private static final int SILENCE = 0;
    private static final int PITCH_A = 1;
    private static final int PITCH_B = 2;

    // ── Engine-sound simulator feature IDs (0x48F reads, 0x3E3 writes) ──
    private static final int FID_SIM_STATE_GET    = 0x48F0000A; // simulator on/off (read)
    private static final int FID_SIM_STATE_SET    = 0x3E300020; // simulator on/off (write)
    private static final int FID_SIM_SRCTYPE_GET  = 0x48F00010; // active preset index (read)
    private static final int FID_SIM_SRCTYPE_SET  = 0x3E300038; // active preset index (write)
    private static final int FID_SIM_HAS_SIM      = 0x48F00000; // capability probe: 2 == supported

    // ── Tone patterns (indices are the stable public API over HTTP/keymap) ──
    public static final int PATTERN_DING_DONG   = 0;
    public static final int PATTERN_DONG_DING   = 1;
    public static final int PATTERN_TRIPLE_BEEP = 2;
    public static final int PATTERN_RAPID_ALT   = 3;
    public static final int PATTERN_LONG_CHIME  = 4;
    public static final int PATTERN_SHOP_CHIME  = 5;
    public static final int PATTERN_ALARM       = 6;
    public static final int PATTERN_FANFARE     = 7;
    public static final int PATTERN_COUNT       = 8;

    private static final String[] PATTERN_NAMES = {
        "ding-dong", "dong-ding", "triple-beep", "rapid-alt",
        "long-chime", "shop-chime", "alarm", "fanfare"
    };

    public static String patternName(int i) {
        return (i >= 0 && i < PATTERN_NAMES.length) ? PATTERN_NAMES[i] : "unknown";
    }

    private static final Object LOCK = new Object();

    /** Reused BYDAutoManager ("auto" system service). Resolved lazily. */
    private static volatile Object autoManager;
    private static volatile Method setIntMethod;
    private static volatile Method getIntMethod;

    /** Guards a single active tone-playback thread. */
    private static volatile Thread playThread;
    private static volatile boolean playing;
    /**
     * Monotonic playback generation. Incremented by every {@link #stop()} /
     * {@link #playPattern(int)}. A tone thread captures its generation at start
     * and only touches shared state / the speaker while it is still the current
     * generation. This defends against the case where {@code stop()}'s
     * {@code join(600)} times out because a {@code setInt} binder call is stalled
     * (interrupt can't unblock a binder call): the superseded thread must NOT run
     * its cleanup after the next thread has started, or it would silence the new
     * pattern and clobber {@code playing}/{@code playThread}.
     */
    private static volatile int generation;

    private AvasController() {}

    // ────────────────────────────── wiring ──────────────────────────────

    /**
     * Resolve the {@code auto} manager + its {@code setInt}/{@code getInt}
     * reflective methods. Returns false if the service isn't available (car
     * asleep, non-BYD build). Cached after first success.
     */
    private static boolean ensureManager() {
        if (autoManager != null && setIntMethod != null) return true;
        synchronized (LOCK) {
            if (autoManager != null && setIntMethod != null) return true;
            Context ctx = CameraDaemon.getAppContext();
            if (ctx == null) {
                logger.warn("no app context — AVAS unavailable");
                return false;
            }
            try {
                Object svc = ctx.getSystemService("auto");
                if (svc == null) {
                    logger.warn("getSystemService(\"auto\") returned null — AVAS unavailable");
                    return false;
                }
                Method set = svc.getClass().getMethod("setInt", int.class, int.class, int.class);
                Method get = svc.getClass().getMethod("getInt", int.class, int.class);
                autoManager = svc;
                setIntMethod = set;
                getIntMethod = get;
                logger.info("AVAS auto manager acquired: " + svc.getClass().getName());
                return true;
            } catch (Throwable t) {
                logger.warn("AVAS manager resolve failed: " + t.getMessage());
                return false;
            }
        }
    }

    /** True if the {@code auto} service (hence AVAS control) is reachable. */
    public static boolean isAvailable() {
        return ensureManager();
    }

    /** setInt(1002, featureId, value); silently ignored if the service is down. */
    private static void si(int featureId, int value) {
        if (setIntMethod == null) return;
        try {
            setIntMethod.invoke(autoManager, DEVICE_AUDIO, featureId, value);
        } catch (Throwable ignored) {}
    }

    /** getInt(1002, featureId); returns {@code dflt} on any failure. */
    private static int gi(int featureId, int dflt) {
        if (getIntMethod == null) return dflt;
        try {
            Object r = getIntMethod.invoke(autoManager, DEVICE_AUDIO, featureId);
            return (r instanceof Integer) ? (Integer) r : dflt;
        } catch (Throwable t) {
            return dflt;
        }
    }

    // ─────────────────────── tone-generator plumbing ────────────────────

    /**
     * Generation-gated single write: only touches the speaker if this thread's
     * generation still owns it. Every write inside a pattern body goes through
     * this so a superseded thread — including one that outlives {@link #stop()}'s
     * {@code join(600)} because a prior {@code si()} binder call stalled — cannot
     * write onto the speaker once a newer generation has taken over. (At most the
     * single already-dispatched in-flight write can still land; it is a lone
     * enabler with the AVAH tone already 0, so it is inaudible and is cleared by
     * the next play/stop.)
     */
    private static void siG(int myGen, int featureId, int value) {
        if (myGen == generation) si(featureId, value);
    }

    /** Enable the full diagnostic-tone path (generation-gated, for pattern use). */
    private static void enableToneChain(int myGen) {
        siG(myGen, FID_PA_CONTROL, 1);
        siG(myGen, FID_MCU_SPEAK, 1);
        siG(myGen, FID_FM_SPEAK, 1);
        siG(myGen, FID_TEST_AVAS, 1);
        siG(myGen, FID_AVAS_CFG, 1);
        siG(myGen, FID_CHANNEL_MUTE, 0);
    }


    /**
     * Tear down the tone path. IMPORTANT: enablers must be cleared BEFORE the
     * AVAH tone is set to 0, otherwise the MCU latches the tone on and it can
     * only be cleared by toggling AVAS off/on in Vehicle Settings. (Documented
     * MCU quirk — see byd-audio-architecture.md.)
     */
    private static void fullStop() {
        si(FID_PA_CONTROL, 0);
        si(FID_MCU_SPEAK, 0);
        si(FID_FM_SPEAK, 0);
        si(FID_TEST_AVAS, 0);
        si(FID_AVAS_CFG, 0);
        si(FID_AVAH_TONE, 0);
    }

    // ─────────────────────────── public: tones ──────────────────────────

    /**
     * Play a tone pattern on the exterior speaker asynchronously. Any currently
     * playing pattern is stopped first. Returns false if AVAS is unreachable or
     * the pattern index is invalid.
     */
    public static boolean playPattern(int pattern) {
        if (pattern < 0 || pattern >= PATTERN_COUNT) return false;
        if (!ensureManager()) return false;
        stop();
        final int myGen;
        synchronized (LOCK) {
            myGen = ++generation;
            playing = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { runPattern(pattern, myGen); }
            }, "avas-tone");
            t.setDaemon(true);
            playThread = t;
            t.start();
        }
        logger.info("AVAS tone start: " + patternName(pattern));
        return true;
    }

    /** Stop any active tone and fully quiesce the exterior speaker. */
    public static void stop() {
        Thread t;
        synchronized (LOCK) {
            generation++;      // supersede any running thread
            playing = false;
            t = playThread;
            playThread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(600); } catch (InterruptedException ignored) {}
        }
        // Quiesce under LOCK so a superseded tone thread's finally (which also
        // quiesces under LOCK, gated on generation) can never interleave a
        // re-enable between our teardown writes.
        synchronized (LOCK) {
            if (setIntMethod != null) fullStop();
        }
    }

    public static boolean isPlaying() {
        return playing;
    }

    private static void runPattern(int pattern, int myGen) {
        try {
            switch (pattern) {
                case PATTERN_DING_DONG:   dingDong(myGen);   break;
                case PATTERN_DONG_DING:   dongDing(myGen);   break;
                case PATTERN_TRIPLE_BEEP: tripleBeep(myGen); break;
                case PATTERN_RAPID_ALT:   rapidAlt(myGen);   break;
                case PATTERN_LONG_CHIME:  longChime(myGen);  break;
                case PATTERN_SHOP_CHIME:  shopChime(myGen);  break;
                case PATTERN_ALARM:       alarm(myGen);      break;
                case PATTERN_FANFARE:     fanfare(myGen);    break;
                default: break;
            }
        } catch (InterruptedException ignored) {
            // stop() requested — fall through to cleanup
        } finally {
            // Hold LOCK across BOTH the generation recheck AND the quiescing
            // fullStop(): if a newer playPattern()/stop() has superseded us, the
            // newer caller owns the speaker, so we must not write to it at all.
            // Doing the recheck and fullStop() non-atomically (as an earlier
            // version did) let a naturally-completed thread be preempted between
            // the check and the fullStop() and then silence the next pattern.
            synchronized (LOCK) {
                if (myGen != generation) return;   // superseded — new owner cleans up
                fullStop();
                playing = false;
                playThread = null;
            }
        }
    }

    /** Still the current playback generation (not superseded by stop/replay). */
    private static boolean stillCurrent(int myGen) {
        return playing && myGen == generation;
    }

    private static void note(int myGen, int pitch, int ms) throws InterruptedException {
        siG(myGen, FID_TEST_AVAS, pitch);
        Thread.sleep(ms);
    }

    private static void rest(int myGen, int ms) throws InterruptedException {
        siG(myGen, FID_TEST_AVAS, SILENCE);
        Thread.sleep(ms);
    }

    private static void dingDong(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_TEST_AVAS, PITCH_A);
        siG(myGen, FID_AVAH_TONE, 1);
        Thread.sleep(400);
        siG(myGen, FID_TEST_AVAS, PITCH_B);
        Thread.sleep(600);
    }

    private static void dongDing(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_TEST_AVAS, PITCH_B);
        siG(myGen, FID_AVAH_TONE, 1);
        Thread.sleep(600);
        siG(myGen, FID_TEST_AVAS, PITCH_A);
        Thread.sleep(400);
    }

    private static void tripleBeep(int myGen) throws InterruptedException {
        for (int i = 0; i < 3 && stillCurrent(myGen); i++) {
            enableToneChain(myGen);
            Thread.sleep(50);
            siG(myGen, FID_TEST_AVAS, PITCH_A);
            siG(myGen, FID_AVAH_TONE, 1);
            Thread.sleep(200);
            // Between beeps, quiesce only if still ours — never touch the
            // speaker on behalf of a generation that has taken over.
            synchronized (LOCK) { if (myGen == generation) fullStop(); }
            Thread.sleep(200);
        }
    }

    private static void rapidAlt(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_AVAH_TONE, 1);
        for (int i = 0; i < 6 && stillCurrent(myGen); i++) {
            siG(myGen, FID_TEST_AVAS, (i % 2) + 1);
            Thread.sleep(200);
        }
    }

    private static void longChime(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_TEST_AVAS, PITCH_B);
        siG(myGen, FID_AVAH_TONE, 1);
        Thread.sleep(500);
        siG(myGen, FID_TEST_AVAS, PITCH_A);
        Thread.sleep(800);
    }

    private static void shopChime(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_AVAH_TONE, 1);
        note(myGen, PITCH_A, 200);
        rest(myGen, 120);
        note(myGen, PITCH_A, 200);
        rest(myGen, 120);
        note(myGen, PITCH_B, 300);
        rest(myGen, 120);
        note(myGen, PITCH_B, 400);
    }

    private static void alarm(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_AVAH_TONE, 1);
        for (int i = 0; i < 4 && stillCurrent(myGen); i++) {
            note(myGen, PITCH_A, 300);
            note(myGen, PITCH_B, 300);
        }
    }

    private static void fanfare(int myGen) throws InterruptedException {
        enableToneChain(myGen);
        Thread.sleep(100);
        siG(myGen, FID_AVAH_TONE, 1);
        note(myGen, PITCH_A, 150);
        rest(myGen, 80);
        note(myGen, PITCH_A, 150);
        rest(myGen, 80);
        note(myGen, PITCH_A, 150);
        rest(myGen, 80);
        note(myGen, PITCH_B, 300);
        rest(myGen, 100);
        note(myGen, PITCH_B, 600);
    }

    // ──────────────────── public: engine-sound simulator ────────────────

    /**
     * Whether this vehicle exposes the engine-sound simulator. The capability
     * probe {@code 0x48F00000} returns 2 when supported. Returns false if AVAS
     * is unreachable.
     */
    public static boolean isEngineSoundSupported() {
        if (!ensureManager()) return false;
        return gi(FID_SIM_HAS_SIM, -1) == 2;
    }

    /**
     * Read-only simulator state for automation telemetry. Null means that the
     * capability or state cannot be read; it must not be interpreted as Off.
     */
    public static Boolean readEngineSoundState() {
        if (!ensureManager() || gi(FID_SIM_HAS_SIM, -1) != 2) return null;
        int state = gi(FID_SIM_STATE_GET, -1);
        if (state == 0) return Boolean.FALSE;
        if (state == 1) return Boolean.TRUE;
        return null;
    }

    /** True if the engine-sound simulator is currently on. */
    public static boolean isEngineSoundOn() {
        if (!ensureManager()) return false;
        return gi(FID_SIM_STATE_GET, 0) == 1;
    }

    /** Current engine-sound preset index (1-based), or -1 if unavailable. */
    public static int getEngineSoundPreset() {
        if (!ensureManager()) return -1;
        return gi(FID_SIM_SRCTYPE_GET, -1);
    }

    /**
     * Turn the engine-sound simulator on and select {@code presetType}, or turn
     * it off. Returns false if AVAS/the simulator is unavailable.
     *
     * <p>Enabling mirrors the verified engine-sound sequence: bring up the
     * speaker path, then set the state + preset.
     */
    public static boolean setEngineSound(boolean on, int presetType) {
        if (!ensureManager()) return false;
        if (on) {
            si(FID_PA_CONTROL, 1);
            si(FID_MCU_SPEAK, 1);
            si(FID_FM_SPEAK, 1);
            si(FID_AVAS_CFG, 1);
            si(FID_SIM_STATE_SET, 1);
            if (presetType > 0) si(FID_SIM_SRCTYPE_SET, presetType);
            logger.info("engine sound ON preset=" + presetType);
        } else {
            si(FID_SIM_STATE_SET, 0);
            logger.info("engine sound OFF");
        }
        return true;
    }

    /**
     * Select an engine-sound preset without changing the on/off state. Reads
     * back the applied index (the MCU clamps to the valid range), or -1 on
     * failure.
     */
    public static int selectEngineSoundPreset(int presetType) {
        if (!ensureManager()) return -1;
        if (presetType < 1) return -1;
        si(FID_SIM_SRCTYPE_SET, presetType);
        return gi(FID_SIM_SRCTYPE_GET, -1);
    }
}
