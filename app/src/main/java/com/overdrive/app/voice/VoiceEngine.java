package com.overdrive.app.voice;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.audio.AppAudioCaptureController;
import com.overdrive.app.util.DaemonHttpClient;
import com.overdrive.app.voice.audio.PcmSink;
import com.overdrive.app.voice.audio.Resampler;
import com.overdrive.app.voice.genai.VoiceSessionClient;
import com.overdrive.app.voice.local.CommandTranscriber;
import com.overdrive.app.voice.local.CommandVad;
import com.overdrive.app.voice.local.LocalCommandSpotter;
import com.overdrive.app.voice.local.PhoneDecoder;
import com.overdrive.app.voice.local.WakeEnrollment;
import com.overdrive.app.voice.ui.VoiceOrbView;
import com.overdrive.app.voice.ui.VoiceOverlay;
import com.overdrive.app.voice.wake.WakeWordDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * One ACC session of the voice assistant: wake detection, the command turn and its
 * feedback. Turn state is owned by the engine thread; other threads only post events.
 */
final class VoiceEngine implements PcmSink {

    interface Host {
        void onStatus(String state, String message);

        void onActivity();
    }

    private static final String TAG = "VoiceEngine";
    private static final int RATE = 16000;

    private static final int ARMED = 0;
    private static final int LOCAL = 1;
    private static final int CLOUD = 2;
    private static final int ENROLL = 3;

    private static final long INITIAL_SILENCE_MS = 4500;
    private static final long END_OF_SPEECH_MS = 700;
    private static final long END_OF_SPEECH_AFTER_MATCH_MS = 400;
    private static final long MAX_UTTERANCE_MS = 8000;
    private static final long TRAILING_SPEECH_FOR_CLOUD_MS = 700;
    private static final long LEADING_SPEECH_FOR_CLOUD_MS = 1600;
    private static final long WORD_MS = 350;
    private static final long CLOUD_NO_SPEECH_MS = 6000;
    private static final long CLOUD_STALL_MS = 12000;
    private static final long CLOUD_MAX_MS = 90_000;
    private static final long REFRACTORY_MS = 800;
    // Command-window timing runs on audio samples so a stalled engine never closes it early.
    private static final long INITIAL_SILENCE_SAMPLES = (INITIAL_SILENCE_MS + 500) * RATE / 1000;
    private static final long END_OF_SPEECH_SAMPLES = END_OF_SPEECH_MS * RATE / 1000;
    private static final long END_OF_SPEECH_AFTER_MATCH_SAMPLES = END_OF_SPEECH_AFTER_MATCH_MS * RATE / 1000;
    private static final long MAX_UTTERANCE_SAMPLES = MAX_UTTERANCE_MS * RATE / 1000;
    private static final long NO_AUDIO_CLOSE_MS = 2000;
    // Speech-to-text match with this many non-filler words left over goes to the cloud in hybrid.
    private static final int EXTRA_WORDS_FOR_CLOUD = 3;
    private static final long ECHO_TAIL_MS = 250;
    // Margin after the wake chime before captured audio counts as listening (the button mute uses it too).
    private static final long CUE_SETTLE_MS = 120;

    private final Context app;
    private final Host host;
    private final VoiceOverlay overlay;
    private final Earcons earcons = new Earcons();
    private final VoicePrompts prompts;
    private final java.util.ArrayDeque<String> promptQueue = new java.util.ArrayDeque<>();
    // Room for a slow stage-2 check on a loaded head unit without dropping post-wake audio.
    private final PcmRing ring = new PcmRing(RATE * 8);
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
    private final ExecutorService io;
    private final Thread thread;
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> { };
    private final Object captureLock = new Object();
    private volatile boolean running = true;
    private volatile VoiceAssistantConfig config;
    private volatile boolean wakeLoaded;
    private volatile boolean wantsCapture;

    final AtomicInteger triggers = new AtomicInteger();
    final AtomicInteger wakes = new AtomicInteger();
    final AtomicInteger localHits = new AtomicInteger();
    final AtomicInteger cloudTurns = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger rejectedWakes = new AtomicInteger();
    final AtomicInteger asrHits = new AtomicInteger();
    final AtomicInteger rewakes = new AtomicInteger();
    volatile long lastWakeAtMs;

    // Field diagnostics: each check and turn is queued once and drained by snapshot().
    private static final int MAX_QUEUED_CHECKS = 8;
    private static final int MAX_QUEUED_TURNS = 4;
    private final Object diagLock = new Object();
    private final java.util.ArrayDeque<JSONObject> checkLog = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<JSONObject> turnLog = new java.util.ArrayDeque<>();
    private int checksDropped;
    private JSONObject lastCheck;
    private JSONObject lastTurnInfo;
    private boolean turnManual;
    private String turnIntents;
    private float micPeakRms;
    private int micChunks;
    private int micSilentChunks;
    private long micDroppedReported;
    // Stage-1 wake probability for /api/voice/status: latest, highest since the last report, the
    // cutoff in force and whether media played (all guarded by diagLock).
    private float stage1Last;
    private float stage1Peak;
    /** Stage-1 probability of the trigger awaiting (or given) its check; engine thread only. */
    private float triggerPeak;
    private float stage1Cutoff;
    private boolean stage1Media;

    private Resampler resampler;
    private int resamplerSrc;
    private short[] capShorts = new short[0];
    private short[] capOut = new short[0];

    private WakeWordDetector wake;
    private LocalCommandSpotter spotter;
    private boolean spotterTried;
    // Command-window endpointing only; null means RMS endpointing (model absent or failed).
    private CommandVad vad;
    private int phase = ARMED;
    // Read by queued dispatches on the IO thread so a cancelled turn's commands never run.
    private volatile int turn;
    private boolean ducked;
    private long mutedUntil;
    private long refractoryUntil;
    private long turnStartedAt;
    // Media playing through the cabin speakers, per the daemon's media sessions (truthful while a
    // player is paused; AudioManager.isMusicActive() only when the daemon cannot see the sessions).
    // Polled on the io executor once a second while nothing of ours plays: gates the stage-2 check
    // (MediaWakeGate), lowers the stage-1 cutoff (wakeCutoffUnderMedia) and is logged with checks.
    private static final long MEDIA_POLL_MS = 1000;
    private boolean mediaActive;
    private long mediaPolledAt;
    /** A media-state call is on the io executor; at most one at a time. */
    private boolean mediaPollBusy;
    /** Last daemon media state (source, playing, when) for the turn's decision. */
    private String mediaSource = MediaTurnPolicy.SOURCE_UNKNOWN;
    private boolean mediaSessionPlaying;
    private long mediaStateAt;
    /** per-turn: media was playing when the turn began (read before the cue). */
    private boolean turnMedia;
    /** The daemon paused media for a turn and the engine still owes it a resume (MediaTurnPolicy). */
    private boolean mediaPaused;
    /** What the daemon paused, for the resume and the turn log: package ("" = unknown) and delivery. */
    private String mediaPkg;
    private String mediaPauseMethod;
    /** A turn holds the cabin audio (focus requested, maybe a pause) until unduck() releases it. */
    private boolean mediaHeld;
    /** The pause request is on the io executor and has not answered yet. */
    private boolean mediaPausePending;
    private long pauseRequestedAt;
    /** per-turn: ms from the pause request to the confirmed pause, or a MediaTurnPolicy.PAUSE_* code. */
    private int pauseMs = MediaTurnPolicy.PAUSE_NONE;
    // While the pause is being confirmed the command window does not count speech or silence:
    // live turn audio before mediaHoldUntilSample is not speech (MAX_VALUE until the end is known).
    private static final int MEDIA_HOLD_NONE = 0;
    private static final int MEDIA_HOLD_AWAIT_REPLY = 1;
    private static final int MEDIA_HOLD_CONFIRMING = 2;
    private int mediaHoldPhase = MEDIA_HOLD_NONE;
    private long mediaHoldUntilSample = -1;
    private long mediaHoldDeadline;
    private long mediaConfirmStartedAt;
    private long mediaConfirmDueAt;
    // The head unit's own assistant duck, held through the daemon (MediaDuckControl): it silences the
    // media stream for the turn, so it replaces focus and pause, and OverDrive's own sound moves off it.
    private static final long CABIN_DUCK_LEASE_MS = 15_000;
    private static final long CABIN_DUCK_RENEW_MS = 5_000;
    private static final long CABIN_DUCK_PROBE_RETRY_MS = 10_000;
    /** Whether the daemon can duck here; null until it answered. */
    private Boolean cabinDuckAvailable;
    private boolean cabinDuckProbeBusy;
    private long cabinDuckProbeAt;
    /** The current hold uses the cabin duck (decided before the cue, so the cue can use its channel). */
    private boolean cabinDuckTurn;
    /** The daemon was asked to duck for this hold and is owed the release. */
    private boolean cabinDucked;
    private long cabinDuckRenewedAt;
    /** Channel for OverDrive's own sound while the cabin duck holds media, else null (the configured one). */
    private String turnChannel;
    // Stage-1 near misses while media plays (field diagnostics; never a wake decision).
    private static final int MAX_QUEUED_NEAR_MISSES = 4;
    private final WakeNearMiss nearMiss = new WakeNearMiss();
    private final java.util.ArrayDeque<Float> nearMissLog = new java.util.ArrayDeque<>();

    private static final int VERIFY_SAMPLES = RATE * 22 / 10;
    // A rejected check gets one more look at its last 1.8 s at their own level: the verifier's
    // verdict depends on where its audio starts (offline: +3 points recall, no added false wakes).
    private static final int VERIFY_SHORT_SAMPLES = RATE * 18 / 10;
    // Stage 2 waits for the rest of the word: a permissive stage 1 can fire before "…drive" ends.
    private static final int VERIFY_DELAY_SAMPLES = RATE * 4 / 10;
    private static final int CANDIDATE_VERIFY_DELAY_SAMPLES = RATE / 10;
    // A weak candidate (below the model's own cutoff) quieter than this is cabin residue, not a voice.
    private static final float WEAK_CANDIDATE_MIN_LEVEL = 58f;
    // Command audio starts this far before the verifier's wake-word end (its timing is ≤ 70 ms late).
    private static final int COMMAND_LEAD_SAMPLES = RATE * 3 / 10;
    // Moonshine/cloud audio starts this far before the verifier's phrase end: the stray "…ive" cost an extra word.
    private static final int UTTERANCE_LEAD_SAMPLES = RATE * 8 / 100;
    // Speech timing ignores the wake word itself, so a pause after "OverDrive" keeps the window open.
    private static final int SPEECH_AFTER_WAKE_SAMPLES = RATE * 15 / 100;
    private static final int UNTIMED_WAKE_LEAD_SAMPLES = VERIFY_DELAY_SAMPLES + RATE * 6 / 10;
    private static final int MAX_PREROLL_SAMPLES = RATE * 12 / 10;
    // Both keyword models lose recall on quiet audio; speech frames are brought to about -17 dBFS.
    private static final float TARGET_SPEECH_RMS = 4630f;
    private static final float MIN_GAIN = 0.316f;
    private static final float MAX_GAIN = 31.6f;
    private static final int LEVEL_FRAME = 512;
    private final short[] history = new short[RATE * 3];
    private final short[] verifyBuf = new short[VERIFY_SAMPLES];
    private final short[] verifyShortBuf = new short[VERIFY_SHORT_SAMPLES];
    private final float[] frameLevels = new float[VERIFY_SAMPLES / LEVEL_FRAME + 1];
    private int historyPos;
    private boolean historyFull;
    private long streamPos;
    private long verifyDueAt = -1;
    // Verification CPU budget: a permissive stage 1 plus a talkative cabin must stay cheap.
    private static final long VERIFY_WINDOW_MS = 60_000;
    private static final int VERIFY_BUDGET_MS = 6000;
    private long verifyWindowStart;
    private int verifyMsUsed;
    private float turnGain = 1f;
    // Last gain measured on real speech; seeds a button turn, which has no wake word to measure.
    private float micGain = 1f;
    private boolean turnGainMeasured;
    private short[] gained = new short[1024];
    // The spotter loses recall when its stream starts long before the command, so a pause after
    // the wake word restarts it just ahead of the speech.
    private static final int PAUSE_RESTART_SAMPLES = RATE / 2;
    private final short[] recent = new short[RATE];
    private long wakeIgnoreUntilSample;
    private int recentPos;
    private boolean recentFull;
    private long turnSamples;
    private long wakeEndTurnSample;
    /** Turn audio still to drop before the Moonshine/cloud copy starts (the spotter sees all of it). */
    private long utteranceSkipSamples;
    /** The utterance may begin with the end of "OverDrive" (STT-timed or re-armed wake). */
    private boolean wakeTailPossible;
    /** Wake-word tail tokens the transcript matcher dropped this turn, for the turn log. */
    private int turnWakeTail;
    /**
     * Post-wake listen budget, anchored in CAPTURED samples so verification latency and ring backlog
     * never eat into it. Wake-path capture is NEVER muted during the cue (speech may already be
     * running into the command) — do not add a mute; the cue is measured instead so the turn log
     * can tell a silent cabin from a chime that drowned the command.
     */
    private long cueBacklogSamples;
    private long cueStartSample;
    private long listenFromSample = -1;
    private int cueMs;
    private int cueVolume = -1;
    /** per-turn: channel the wake cue was moved to because the selected stream was muted, else null. */
    private String cueFallback;
    private float cuePeakRms;
    private float listenPeakRms;
    private long vadSpeechSamples;
    private long loudSamples;
    /** per-turn: reason of a hybrid pre-warm that died before the cloud turn started; consumed by startCloud (G5). */
    private String preWarmFailed;

    private final short[] utterance = new short[RATE * 10];
    private int utteranceLen;
    private boolean utteranceFull;
    private long lastSpeechSample;
    private long lastAudioAt;
    private CommandTranscriber transcriber;
    private boolean transcriberLoading;
    private final java.util.concurrent.atomic.AtomicReference<CommandTranscriber> loadedTranscriber =
            new java.util.concurrent.atomic.AtomicReference<>();
    private int speechRun;
    private String turnBy;
    private String turnText;
    private int turnAsrMs;
    // Measurement only: every dispatched {intent, slots} and the wall-clock marks of the turn.
    // The daemon's status sanitizer drops them, so they only reach the debug harness.
    private JSONArray turnIntentDetail;
    private long speechEndAt;
    private long firstDispatchAt;
    private long firstReplyAt;
    private String utteranceId;
    private final List<LocalCommandSpotter.Match> matches = new ArrayList<>();
    private int dispatched;
    private int pendingResults;
    private boolean anySuccess;
    private int failureText;
    private String failureKey;
    private long promptEndsAt;
    private boolean unduckWhenQuiet;
    private boolean localWindowClosed;
    private long speechMs;
    private long speechMsAtFirstMatch = -1;
    private long speechMsAtLastMatch = -1;
    private int firstMatchWords;
    // Turn sample where speech began (the first of the two loud chunks) and where the last loud
    // RMS chunk started; both feed the clean-hit gate, so they stay RMS-based for one-chunk latency.
    private long speechOnsetSample = -1;
    private long lastLoudSample = -1;
    // Spotted phrases held back for utterance validation at window close instead of running now.
    private final List<LocalCommandSpotter.Match> held = new ArrayList<>();
    private int heldCount;

    private float noiseFloor = 250f;
    private long speechStartAt;
    private long lastSpeechAt;

    // Wake-word enrollment (WakeEnrollment): one chime, then the takes in a row; a silence of
    // ENROLL_WAIT_MS re-prompts. Listening starts once the chime's echo has died away.
    private static final long ENROLL_WAIT_MS = 6000;
    private static final long ENROLL_ECHO_GUARD_MS = 300;
    private static final int ENROLL_ARM_QUIET_CHUNKS = 2;
    private static final long ENROLL_REQUEST_MAX_AGE_MS = 30_000;
    private static final long ENROLL_STATUS_KEEP_MS = 180_000;
    private static final int ENROLL_LEAD_SAMPLES = RATE * 3 / 2;
    private static final int ENROLL_TAIL_SAMPLES = RATE * 3 / 10;
    private static final int ENROLL_END_QUIET_SAMPLES = RATE * 45 / 100;
    private static final int ENROLL_MIN_SPEECH_SAMPLES = RATE * 3 / 10;
    private static final int ENROLL_MAX_SPEECH_SAMPLES = RATE * 2;
    private static final int ENROLL_MAX_PROMPTS = 3;
    private final short[] enrollBuf = new short[ENROLL_LEAD_SAMPLES + ENROLL_MAX_SPEECH_SAMPLES + ENROLL_TAIL_SAMPLES];
    private final List<short[]> enrollTakes = new ArrayList<>();
    private final List<Float> enrollPeaks = new ArrayList<>();
    private String enrollId;
    private String enrollSeenId = "";
    private int enrollLen;
    private int enrollOnset;
    private int enrollLastLoud;
    private int enrollLoudRun;
    private int enrollQuietRun;
    private boolean enrollArmed;
    private float enrollTakePeak;
    private int enrollPrompts;
    private long enrollPromptAt;
    private long enrollWaitFrom;
    /** The over-music calibration: media keeps playing and only the media cutoff is measured. */
    private boolean enrollMusic;
    /** The retrained voice's current lines, offered back to the learner. */
    private List<String> enrollPrevious = java.util.Collections.emptyList();
    private DecoderLoad enrollDecoderLoad;

    /** The phone decoder loads while the takes are recorded; whoever finishes last closes it. */
    private static final class DecoderLoad {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        PhoneDecoder decoder;
        boolean abandoned;

        synchronized void abandon() {
            abandoned = true;
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }
    }
    /** Last enrollment state for the settings page (guarded by diagLock). */
    private JSONObject enrollStatus;
    private long enrollStatusAt;

    private VoiceSessionClient session;
    private int sessionGeneration;
    private boolean sessionStreaming;
    /** session-scoped (not per turn): the daemon reported the provider live, so a later silence is a stall, not a dead dial. */
    private boolean sessionLive;
    private boolean replySpeaking;
    private boolean turnComplete;
    /** per-turn: reply audio played in this cloud turn, so a later wait that runs out is no failure. */
    private boolean turnReplyHeard;
    private boolean thinkingShown;
    private long followUpUntil;
    private long cloudStartedAt;
    private long lastCloudActivityAt;
    private final CloudDeadline deadline = new CloudDeadline();

    VoiceEngine(Context context, VoiceAssistantConfig config, Host host) {
        this.app = context.getApplicationContext();
        this.config = config;
        this.host = host;
        this.overlay = new VoiceOverlay(app);
        this.prompts = new VoicePrompts(app);
        // Hebrew prompts are spoken by text-to-speech; start it now so the first reply is ready.
        this.prompts.prepare(locale());
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoiceIO");
            t.setDaemon(true);
            return t;
        });
        this.thread = new Thread(this::run, "VoiceEngine");
        this.thread.setDaemon(true);
        ring.bindConsumer(thread);
        overlay.setOnTap(() -> post(this::cancelTurn));
    }

    void start() {
        thread.start();
    }

    void updateConfig(VoiceAssistantConfig cfg) {
        VoiceAssistantConfig old = config;
        config = cfg;
        if (!cfg.sameRuntimeShape(old)) post(() -> applyConfig(cfg));
    }

    /** Called from the poll tick: re-asserts the mic demand while capture is wanted. */
    void ensureCapture() {
        if (wakeLoaded || wantsCapture) captureOn();
    }

    void pushToTalk() {
        post(() -> {
            if (phase == ARMED) {
                beginTurn(true, micGain);
            } else if (phase == CLOUD && replySpeaking && session != null) {
                session.stopPlayback();
            } else {
                cancelTurn();
            }
        });
    }

    /** Counters, the newest check/turn, and every check/turn and mic stat since the last call. */
    JSONObject snapshot() {
        JSONObject counters = new JSONObject();
        JSONObject out = new JSONObject();
        try {
            counters.put("triggers", triggers.get());
            counters.put("wakes", wakes.get());
            counters.put("rejectedWakes", rejectedWakes.get());
            counters.put("localHits", localHits.get());
            counters.put("cloudTurns", cloudTurns.get());
            counters.put("failures", failures.get());
            counters.put("asrHits", asrHits.get());
            counters.put("rewakes", rewakes.get());
            out.put("counters", counters);
            if (lastWakeAtMs > 0) out.put("lastWakeAt", lastWakeAtMs);
            synchronized (diagLock) {
                if (lastCheck != null) out.put("lastVerify", lastCheck);
                if (lastTurnInfo != null) out.put("lastTurn", lastTurnInfo);
                if (!checkLog.isEmpty()) out.put("checks", new org.json.JSONArray(checkLog));
                if (checksDropped > 0) out.put("checksDropped", checksDropped);
                if (!turnLog.isEmpty()) out.put("turns", new org.json.JSONArray(turnLog));
                if (!nearMissLog.isEmpty()) {
                    JSONArray misses = new JSONArray();
                    for (Float p : nearMissLog) misses.put(new JSONObject().put("p", round2(p)));
                    out.put("nearMisses", misses);
                }
                // Stage-1 sliding-window probability: the latest value and the highest since the
                // last report, with the cutoff in force (lower only while media plays).
                if (stage1Cutoff > 0f) {
                    JSONObject stage1 = new JSONObject()
                            .put("lastP", round2(stage1Last))
                            .put("peakP", round2(stage1Peak))
                            .put("cutoff", round2(stage1Cutoff));
                    if (stage1Media) stage1.put("media", true);
                    out.put("stage1", stage1);
                }
                if (enrollStatus != null
                        && SystemClock.elapsedRealtime() - enrollStatusAt < ENROLL_STATUS_KEEP_MS) {
                    out.put("enroll", enrollStatus);
                }
                stage1Peak = 0f;
                nearMissLog.clear();
                checkLog.clear();
                turnLog.clear();
                checksDropped = 0;
                long dropped = ring.droppedSamples();
                if (micChunks > 0) {
                    out.put("mic", new JSONObject()
                            .put("peakDb", Math.round(dbfs(micPeakRms)))
                            .put("silentPct", micSilentChunks * 100 / micChunks)
                            .put("droppedMs", (dropped - micDroppedReported) * 1000 / RATE));
                }
                micDroppedReported = dropped;
                micChunks = 0;
                micSilentChunks = 0;
                micPeakRms = 0f;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void close() {
        shutdown("close");
    }

    /**
     * Shared by close() and the fatal exit in run(); idempotent and safe from either thread.
     * Before this existed the fatal path only released models, so a crashed engine kept the
     * wake-only mic capture alive, left the io executor accepting work and let queued
     * dispatches run against a dead turn.
     */
    private void shutdown(String reason) {
        synchronized (captureLock) {
            running = false;
            wantsCapture = false;
            AppAudioCaptureController.setWakeSink(null);
        }
        // Queued dispatch runnables check turn, not running.
        turn++;
        LockSupport.unpark(thread);
        boolean onEngineThread = Thread.currentThread() == thread;
        if (!onEngineThread) {
            try {
                thread.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Model/session/UI release belongs to the engine thread, which owns the native handles.
        // From another thread, release only once that thread has exited; if it did not exit in
        // time it releases on its own way out.
        if (onEngineThread || !thread.isAlive()) releaseAll();
        io.shutdownNow();
        Log.i(TAG, "Voice engine shut down (" + reason + ")");
    }

    @Override
    public void onPcm(byte[] pcm, int length, int sampleRate) {
        if (!running) return;
        int n = length >> 1;
        if (capShorts.length < n) capShorts = new short[n];
        Resampler.bytesToShorts(pcm, length, capShorts);
        if (sampleRate == RATE) {
            ring.write(capShorts, 0, n);
            return;
        }
        if (resampler == null || resamplerSrc != sampleRate) {
            resampler = new Resampler(sampleRate, RATE);
            resamplerSrc = sampleRate;
        }
        int max = resampler.maxOutput(n);
        if (capOut.length < max) capOut = new short[max];
        int m = resampler.process(capShorts, 0, n, capOut, 0);
        ring.write(capOut, 0, m);
    }

    private boolean captureOn() {
        synchronized (captureLock) {
            return running && AppAudioCaptureController.setWakeSink(this);
        }
    }

    private void captureOff() {
        synchronized (captureLock) {
            if (running) AppAudioCaptureController.setWakeSink(null);
        }
    }

    private void post(Runnable r) {
        events.add(r);
        LockSupport.unpark(thread);
    }

    private void run() {
        try {
            loadModels();
            if (wake != null && spotter == null) {
                // The first stage alone wakes on ordinary speech; never run it unverified.
                Log.w(TAG, "Wake verifier unavailable; wake word disabled");
                wake.close();
                wake = null;
                wakeLoaded = false;
            }
            if (wake != null) {
                captureOn();
                host.onStatus("listening", null);
            } else {
                host.onStatus("push_to_talk", null);
            }
            short[] chunk = new short[1024];
            while (running) {
                Runnable r;
                while (running && (r = events.poll()) != null) r.run();
                if (!running) break;
                int n = ring.read(chunk, 0, chunk.length);
                long now = SystemClock.elapsedRealtime();
                if (n > 0) onAudio(chunk, n, now);
                tick(now);
                if (n == 0) ring.awaitData(20_000_000L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Voice engine stopped: " + t.getMessage(), t);
            host.onStatus("unavailable", t.getClass().getSimpleName());
        } finally {
            shutdown("engine_exit");
        }
    }

    private void loadModels() {
        VoiceAssistantConfig c = config;
        if (WakeWordDetector.isModelPresent(app, WakeWordDetector.DEFAULT_ASSET_BASE)) {
            try {
                wake = WakeWordDetector.load(app, WakeWordDetector.DEFAULT_ASSET_BASE);
                wake.setSensitivity(c.sensitivity);
                wakeLoaded = true;
            } catch (Throwable t) {
                Log.w(TAG, "Wake model failed to load: " + t.getMessage());
                wake = null;
            }
        }
        if (c.usesLocal() || wake != null) loadSpotter();
        if (spotter != null) startTranscriberLoad();
        applyPersonalWake(c);
        loadVad();
    }

    /** Command-window endpointing; the wake path never sees it. Absent model: RMS as before. */
    private void loadVad() {
        if (!CommandVad.isAvailable(app)) {
            Log.i(TAG, "VAD model absent; RMS endpointing");
            return;
        }
        try {
            vad = CommandVad.load(app);
        } catch (Throwable t) {
            Log.w(TAG, "VAD failed to load: " + t.getMessage());
            vad = null;
        }
    }

    /** Loads speech-to-text off the engine thread; until it is ready, unspotted speech skips it. */
    private void startTranscriberLoad() {
        if (transcriber != null || transcriberLoading || !CommandTranscriber.isAvailable(app)) return;
        transcriberLoading = true;
        try {
            io.execute(() -> {
                CommandTranscriber loaded = null;
                try {
                    loaded = CommandTranscriber.load(app);
                } catch (Throwable t) {
                    Log.w(TAG, "Speech-to-text failed to load: " + t.getMessage());
                }
                if (loaded == null) return;
                // Handed over through a reference so releaseAll() closes it even if never adopted.
                loadedTranscriber.set(loaded);
                if (!running) {
                    CommandTranscriber orphan = loadedTranscriber.getAndSet(null);
                    if (orphan != null) orphan.close();
                    return;
                }
                post(() -> {
                    CommandTranscriber ready = loadedTranscriber.getAndSet(null);
                    if (ready != null) transcriber = ready;
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            transcriberLoading = false;
        }
    }

    private void loadSpotter() {
        if (spotter != null || spotterTried) return;
        spotterTried = true;
        try {
            if (!LocalCommandSpotter.isAvailable(app)) return;
            spotter = LocalCommandSpotter.load(app);
        } catch (Throwable t) {
            Log.w(TAG, "Local command engine failed to load: " + t.getMessage());
            spotter = null;
        }
    }

    private void applyConfig(VoiceAssistantConfig cfg) {
        if (wake != null) wake.setSensitivity(cfg.sensitivity);
        if (cfg.usesLocal() || wake != null) {
            loadSpotter();
        } else if (spotter != null && phase != LOCAL) {
            spotter.close();
            spotter = null;
            spotterTried = false;
        }
        applyPersonalWake(cfg);
    }

    /** The enrolled lines whose every token this model knows, and the enrolled stage-1 cutoff. */
    private void applyPersonalWake(VoiceAssistantConfig cfg) {
        if (spotter != null) {
            List<String> usable = new ArrayList<>();
            for (String line : cfg.personalWakeLines) {
                int colon = line.indexOf(" :");
                List<String> tokens = java.util.Arrays.asList(line.substring(0, colon).trim().split("\\s+"));
                if (spotter.knowsTokens(tokens)) usable.add(line);
            }
            spotter.setPersonalWake(String.join("/", usable));
        }
        if (wake != null) {
            wake.setCutoffOverride(Double.isNaN(cfg.personalWakeCutoff) ? Float.NaN : (float) cfg.personalWakeCutoff);
        }
    }

    // ── Audio + timers ────────────────────────────────────────────────────

    private void onAudio(short[] chunk, int n, long now) {
        boolean muted = now < mutedUntil || replySpeaking;
        float rms = rms(chunk, 0, n);
        if (!muted) trackNoiseFloor(rms, n);
        noteMic(rms);
        streamPos += n;
        lastAudioAt = now;
        // Candidates wherever a lowered cutoff would fire mid-word, and for every enrollment take;
        // plain-mic media keeps fire-and-reset.
        if (wake != null) {
            wake.setCandidateMode(phase == ENROLL || (wake.cutoffLowered() && (!mediaActive || echoFree())));
        }
        if (phase == ARMED) {
            remember(chunk, n);
            if (muted) {
                verifyDueAt = -1;
                return;
            }
            boolean fired = false;
            if (wake != null && now >= refractoryUntil) {
                fired = wake.process(chunk, 0, n);
                noteStage1(fired, now);
            }
            if (fired && verifyDueAt < 0) {
                triggers.incrementAndGet();
                boolean candidate = wake.candidateMode();
                triggerPeak = candidate ? wake.lastCandidatePeak() : wake.lastChunkPeak();
                // A candidate is reported after its score peaked: the word is already complete.
                verifyDueAt = streamPos + (candidate ? CANDIDATE_VERIFY_DELAY_SAMPLES : VERIFY_DELAY_SAMPLES);
            } else if (verifyDueAt >= 0 && streamPos >= verifyDueAt) {
                verifyDueAt = -1;
                checkWake();
            }
            return;
        }
        if (phase == ENROLL) {
            onEnrollAudio(chunk, n, rms, now, muted);
            return;
        }
        if (muted) return;
        // Raw peaks of the cue span and of the listen span, so the turn log shows what the mic heard.
        if (phase == LOCAL && !localWindowClosed && listenFromSample >= 0) {
            if (turnSamples >= listenFromSample) {
                if (rms > listenPeakRms) listenPeakRms = rms;
            } else if (turnSamples >= cueStartSample && rms > cuePeakRms) {
                cuePeakRms = rms;
            }
        }
        if (gained.length < n) gained = new short[n];
        // The wake word may have been soft; back off if the command is loud enough to clip.
        if (applyGain(chunk, 0, n, turnGain, gained)) turnGain = Math.max(MIN_GAIN, turnGain * 0.7f);
        // Until the media pause is confirmed (plus speaker decay) the audio is still heard by the
        // spotter and kept for the cloud, but it is not speech: the window must not open on music.
        boolean mediaHold = turnSamples < mediaHoldUntilSample;
        onTurnAudio(gained, 0, n, now, !mediaHold);
    }

    /** Command-stage audio, already level-normalized. */
    private void onTurnAudio(short[] pcm, int off, int n, long now, boolean countSpeech) {
        float rms = rms(pcm, off, n);
        overlay.setLevel(Math.min(1f, rms / 3000f));
        if (countSpeech) {
            boolean first = speechStartAt == 0;
            trackSpeech(pcm, off, n, rms, now);
            if (first && speechStartAt != 0) onSpeechOnset();
        }
        rememberRecent(pcm, off, n);
        turnSamples += n;
        // The Moonshine/cloud copy starts 80 ms before the wake word's end; the spotter keeps its full lead.
        int uOff = off;
        int uLen = n;
        if (utteranceSkipSamples > 0) {
            int skip = (int) Math.min(uLen, utteranceSkipSamples);
            utteranceSkipSamples -= skip;
            uOff += skip;
            uLen -= skip;
        }
        if (phase == LOCAL) {
            if (localWindowClosed) return;
            // Once full it stays full, so the cloud replay never has a hole in it.
            if (uLen > 0 && !utteranceFull && utteranceLen + uLen <= utterance.length) {
                System.arraycopy(pcm, uOff, utterance, utteranceLen, uLen);
                utteranceLen += uLen;
            } else if (uLen > 0) {
                utteranceFull = true;
            }
            if (spotter != null) {
                LocalCommandSpotter.Match m = spotter.process(pcm, off, n);
                if (m != null) onLocalMatch(m);
            }
        } else if (phase == CLOUD) {
            if (uLen > 0 && session != null && sessionStreaming) session.sendPcm16k(pcm, uOff, uLen);
        }
    }

    private void tick(long now) {
        servicePrompts(now);
        if (phase == ARMED) {
            maybeStartEnroll();
        } else if (phase == ENROLL) {
            tickEnroll(now);
        }
        // Only when none of our own cues or prompts can be what is playing (the isMusicActive()
        // fallback would hear them); one daemon call at a time, on the io executor.
        if (phase == ARMED && !ducked && now >= mutedUntil && now - mediaPolledAt >= MEDIA_POLL_MS
                && !mediaPollBusy) {
            mediaPolledAt = now;
            pollMediaState(false);
        }
        tickMediaHold(now);
        if (cabinDuckAvailable == null && !cabinDuckProbeBusy && now >= cabinDuckProbeAt) {
            probeCabinDuck(now);
        }
        if (cabinDucked && now - cabinDuckRenewedAt >= CABIN_DUCK_RENEW_MS) {
            cabinDuckRenewedAt = now;
            submitMedia(() -> MediaSessionClient.duck(CABIN_DUCK_LEASE_MS));
        }
        if (phase == LOCAL && !localWindowClosed) {
            long heard = turnSamples - wakeEndTurnSample;
            // The silent-wait budget also counts from the cue end, so a slow check or a ring
            // backlog never shortens what the driver gets after the chime.
            long listened = listenFromSample >= 0 ? turnSamples - listenFromSample : heard;
            // Wall-clock backstop for a capture that stopped delivering audio mid-turn.
            boolean stuck = now - turnStartedAt > MAX_UTTERANCE_MS + 4000
                    || (lastAudioAt > 0 && now - lastAudioAt > NO_AUDIO_CLOSE_MS);
            if (speechStartAt == 0 && matches.isEmpty()) {
                if ((heard > INITIAL_SILENCE_SAMPLES && listened > INITIAL_SILENCE_SAMPLES) || stuck) closeLocalWindow();
            } else {
                long hang = matches.isEmpty() ? END_OF_SPEECH_SAMPLES : END_OF_SPEECH_AFTER_MATCH_SAMPLES;
                long quiet = turnSamples - Math.max(lastSpeechSample, wakeEndTurnSample);
                if (quiet > hang || heard > MAX_UTTERANCE_SAMPLES || stuck) closeLocalWindow();
            }
        } else if (phase == CLOUD) {
            tickCloud(now);
        }
    }

    private void remember(short[] chunk, int n) {
        for (int i = 0; i < n; ) {
            int run = Math.min(n - i, history.length - historyPos);
            System.arraycopy(chunk, i, history, historyPos, run);
            i += run;
            historyPos += run;
            if (historyPos == history.length) {
                historyPos = 0;
                historyFull = true;
            }
        }
    }

    /**
     * Second stage: the keyword spotter must also hear "OverDrive" in the last 2.2 s, brought to
     * speech level. On success the turn replays everything from just before the word's end, so
     * nothing said after it is lost to the check's latency.
     */
    private void checkWake() {
        long started = SystemClock.elapsedRealtime();
        if (started - verifyWindowStart > VERIFY_WINDOW_MS) {
            verifyWindowStart = started;
            verifyMsUsed = 0;
        }
        // Under media the keyword verifier alone decides: Moonshine would transcribe the podcast.
        // The car microphones cancel the speakers' own sound, so there the media is not heard.
        boolean media = mediaActive;
        boolean echoFree = echoFree();
        // A trigger only an enrolled or media cutoff let through is confirmed the same way.
        boolean weak = triggerPeak < wake.modelCutoff();
        boolean guarded = (media && !echoFree) || weak;
        int n = copyHistoryTail(verifyBuf);
        float level = speechLevel(verifyBuf, n);
        // Candidate mode spends the budget on weak candidates only (mostly residue and noise): a
        // near-silent one, or any over budget, is dropped, and a strong one is always checked in full.
        boolean candidate = wake.candidateMode();
        if (weak && candidate && (level < WEAK_CANDIDATE_MIN_LEVEL || verifyMsUsed > VERIFY_BUDGET_MS)) {
            return;
        }
        // Over budget: keep only the cheaper check rather than stopping wake detection.
        boolean sttOnly = MediaWakeGate.sttOnly(guarded, verifyMsUsed > VERIFY_BUDGET_MS)
                && transcriber != null && englishSpeech() && !candidate;
        float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_SPEECH_RMS / Math.max(level, 1f)));
        applyGain(verifyBuf, 0, n, gain, verifyBuf);
        int end = spotter == null || n == 0 || sttOnly
                ? LocalCommandSpotter.WAKE_NOT_HEARD : spotter.verifyWake(verifyBuf, 0, n);
        boolean heard = end != LocalCommandSpotter.WAKE_NOT_HEARD;
        String by = heard ? (spotter.lastWakeWasPersonal() ? "kwsp" : "kws") : null;
        String text = null;
        // Accents the keyword model misses are often transcribed correctly; either one is enough.
        if (!heard && n > 0 && MediaWakeGate.sttFallbackAllowed(guarded)) {
            CommandTranscriber t = transcriber;
            if (t != null && englishSpeech()) {
                boolean[] said = new boolean[1];
                text = t.hearsWakeWord(verifyBuf, 0, n, said);
                if (said[0]) {
                    heard = true;
                    by = "stt";
                    end = LocalCommandSpotter.WAKE_END_UNKNOWN;
                }
            }
        }
        if (!heard && n > 0 && spotter != null && !sttOnly && ((candidate && !weak)
                || verifyMsUsed + (SystemClock.elapsedRealtime() - started) <= VERIFY_BUDGET_MS)) {
            int longEnd = secondLook(n);
            if (longEnd != LocalCommandSpotter.WAKE_NOT_HEARD) {
                heard = true;
                by = spotter.lastWakeWasPersonal() ? "kwsaltp" : "kwsalt";
                end = longEnd;
            }
        }
        int tookMs = (int) (SystemClock.elapsedRealtime() - started);
        verifyMsUsed += tookMs;
        noteCheck(heard, by, level, gain, tookMs, heard && !"stt".equals(by) ? null : CommandTranscriber.logSafe(text, 40), media);
        if (!heard) {
            rejectedWakes.incrementAndGet();
            // Under media a rejected check is followed by a short refractory so the verifier is
            // not re-entered every 2-3 s by the media itself; without media nothing changes.
            long hold = MediaWakeGate.refractoryAfterRejectMs(guarded);
            // With the car microphones the music is cancelled; on 2026-10-14 its residue still
            // triggered a check every ~5 s and the hold after each one kept the driver unheard.
            if (echoFree) hold = 0;
            if (hold > 0) refractoryUntil = SystemClock.elapsedRealtime() + hold;
            host.onActivity();
            return;
        }
        // A field capture records repeated attempts over music; a turn would duck it under the next one.
        if (com.overdrive.app.audio.AppAudioCaptureController.fieldCaptureRunning()) {
            host.onActivity();
            return;
        }
        micGain = gain;
        turnGainMeasured = true;
        int start;
        int speechFrom;
        if (end == LocalCommandSpotter.WAKE_END_UNKNOWN) {
            start = n - UNTIMED_WAKE_LEAD_SAMPLES;
            speechFrom = n;
        } else {
            start = end - COMMAND_LEAD_SAMPLES;
            speechFrom = end + SPEECH_AFTER_WAKE_SAMPLES;
        }
        start = Math.max(Math.max(0, n - MAX_PREROLL_SAMPLES), Math.min(start, n));
        speechFrom = Math.max(start, Math.min(speechFrom, n));
        beginTurn(false, gain);
        wakeEndTurnSample = end == LocalCommandSpotter.WAKE_END_UNKNOWN
                ? speechFrom - start : Math.max(0, end - start);
        utteranceSkipSamples = end == LocalCommandSpotter.WAKE_END_UNKNOWN
                ? 0 : Math.max(0, Math.min(end, n) - UTTERANCE_LEAD_SAMPLES - start);
        wakeTailPossible = end == LocalCommandSpotter.WAKE_END_UNKNOWN;
        long now = SystemClock.elapsedRealtime();
        for (int off = start; off < n && phase != ARMED; ) {
            int limit = off < speechFrom ? speechFrom : n;
            int len = Math.min(1024, limit - off);
            onTurnAudio(verifyBuf, off, len, now, off >= speechFrom);
            off += len;
        }
        // The replay and the ring backlog were captured before the chime; listening starts after it.
        if (phase != ARMED) {
            cueStartSample = turnSamples + cueBacklogSamples;
            listenFromSample = cueStartSample + (cueMs + CUE_SETTLE_MS) * RATE / 1000;
        }
    }

    /**
     * The second look: the last 1.8 s of the history at their own speech level. Returns the wake
     * word's end on the {@code n} samples of {@link #verifyBuf} (they end at the same sample),
     * {@code WAKE_END_UNKNOWN} or {@code WAKE_NOT_HEARD}.
     */
    private int secondLook(int n) {
        int m = copyHistoryTail(verifyShortBuf);
        if (m <= 0 || m >= n) return LocalCommandSpotter.WAKE_NOT_HEARD;
        float level = speechLevel(verifyShortBuf, m);
        float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_SPEECH_RMS / Math.max(level, 1f)));
        applyGain(verifyShortBuf, 0, m, gain, verifyShortBuf);
        int end = spotter.verifyWake(verifyShortBuf, 0, m);
        if (end == LocalCommandSpotter.WAKE_NOT_HEARD || end == LocalCommandSpotter.WAKE_END_UNKNOWN) return end;
        return end + (n - m);
    }

    /**
     * A button turn has no wake word to measure, so its first speech sets the gain: everything
     * buffered is rescaled and the spotter restarts on the re-gained lead-in.
     */
    private void remeasureGain() {
        turnGainMeasured = true;
        short[] tail = recentTail(RATE / 2);
        if (tail.length < LEVEL_FRAME) return;
        float level = speechLevel(tail, tail.length) / Math.max(turnGain, 0.01f);
        float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_SPEECH_RMS / Math.max(level, 1f)));
        micGain = gain;
        float ratio = gain / turnGain;
        if (ratio > 0.71f && ratio < 1.41f) return;
        turnGain = gain;
        applyGain(utterance, 0, utteranceLen, ratio, utterance);
        applyGain(recent, 0, recent.length, ratio, recent);
        if (phase == LOCAL && spotter != null && !localWindowClosed && matches.isEmpty()) {
            spotter.begin(config.resolvedCommandLanguage(locale()));
            short[] lead = recentTail(COMMAND_LEAD_SAMPLES);
            LocalCommandSpotter.Match m = spotter.process(lead, 0, lead.length);
            if (m != null && !LocalCommandSpotter.INTENT_WAKE.equals(m.intent)) onLocalMatch(m);
        }
    }

    /**
     * After a pause, add a second spotter stream that starts 0.3 s before the speech; the first
     * stream keeps running in case the onset was detected late (quiet first word in noise).
     */
    private void onSpeechOnset() {
        if (phase != LOCAL || spotter == null || localWindowClosed || !matches.isEmpty()) return;
        if (wakeEndTurnSample < 0 || turnSamples - wakeEndTurnSample < PAUSE_RESTART_SAMPLES) return;
        short[] lead = recentTail(COMMAND_LEAD_SAMPLES);
        spotter.addAheadStream(lead, 0, lead.length);
    }

    /** The last {@code samples} of command-stage audio, oldest first. */
    private short[] recentTail(int samples) {
        int have = Math.min(samples, recentFull ? recent.length : recentPos);
        short[] out = new short[have];
        int from = recentPos - have;
        if (from < 0) from += recent.length;
        for (int i = 0; i < have; i++) out[i] = recent[(from + i) % recent.length];
        return out;
    }

    /**
     * "OverDrive" said again inside the window: restart the timers instead of failing. The spotter
     * streams keep running, because the phrase is reported up to ~0.5 s after the word and a
     * command run straight on has already started; speech-to-text keeps the last 1 s for the same reason.
     */
    private void reArm() {
        if (phase != LOCAL || localWindowClosed || spotter == null || !matches.isEmpty()) return;
        if (turnSamples < wakeIgnoreUntilSample) return;
        rewakes.incrementAndGet();
        speechStartAt = 0;
        lastSpeechAt = 0;
        lastSpeechSample = 0;
        speechMs = 0;
        speechRun = 0;
        speechOnsetSample = -1;
        wakeEndTurnSample = turnSamples;
        listenFromSample = turnSamples;
        wakeTailPossible = true;
        wakeIgnoreUntilSample = turnSamples + RATE;
        turnStartedAt = SystemClock.elapsedRealtime();
        short[] lead = recentTail(RATE);
        System.arraycopy(lead, 0, utterance, 0, lead.length);
        utteranceLen = lead.length;
        utteranceFull = false;
        overlay.show(VoiceOrbView.STATE_LISTENING, overlay.text(R.string.voice_overlay_listening));
    }

    private void rememberRecent(short[] pcm, int off, int n) {
        for (int i = 0; i < n; ) {
            int run = Math.min(n - i, recent.length - recentPos);
            System.arraycopy(pcm, off + i, recent, recentPos, run);
            i += run;
            recentPos += run;
            if (recentPos == recent.length) {
                recentPos = 0;
                recentFull = true;
            }
        }
    }

    private int copyHistoryTail(short[] dst) {
        int have = historyFull ? history.length : historyPos;
        int n = Math.min(dst.length, have);
        int start = historyPos - n;
        if (start >= 0) {
            System.arraycopy(history, start, dst, 0, n);
        } else {
            int head = -start;
            System.arraycopy(history, history.length - head, dst, 0, head);
            System.arraycopy(history, 0, dst, head, historyPos);
        }
        return n;
    }

    /** 95th-percentile 32 ms frame RMS (DC removed): the loud speech frames, not the pauses. */
    private float speechLevel(short[] pcm, int n) {
        int available = n / LEVEL_FRAME;
        int frames = Math.min(frameLevels.length, available);
        if (frames == 0) return rms(pcm, 0, n);
        // Longer buffers are sampled across their whole length, not just the first 2.2 s.
        int stride = Math.max(1, available / frames);
        for (int f = 0; f < frames; f++) {
            int base = f * stride * LEVEL_FRAME;
            double mean = 0;
            for (int i = 0; i < LEVEL_FRAME; i++) mean += pcm[base + i];
            mean /= LEVEL_FRAME;
            double sum = 0;
            for (int i = 0; i < LEVEL_FRAME; i++) {
                double d = pcm[base + i] - mean;
                sum += d * d;
            }
            frameLevels[f] = (float) Math.sqrt(sum / LEVEL_FRAME);
        }
        java.util.Arrays.sort(frameLevels, 0, frames);
        return frameLevels[(int) (0.95f * (frames - 1))];
    }

    /** The car microphones' input, whose echo canceller removes the cabin speakers' own sound. */
    private static boolean echoFree() {
        return VoiceAssistantConfig.MIC_SOURCE_CAR_ARRAY.equals(
                com.overdrive.app.audio.AppAudioCaptureController.activeMicSource());
    }

    /** Returns true when any sample had to be clipped. */
    private static boolean applyGain(short[] src, int off, int n, float gain, short[] dst) {
        if (gain == 1f) {
            if (src != dst) System.arraycopy(src, off, dst, 0, n);
            return false;
        }
        boolean clipped = false;
        for (int i = 0; i < n; i++) {
            int v = Math.round(src[off + i] * gain);
            if (v > Short.MAX_VALUE || v < Short.MIN_VALUE) {
                clipped = true;
                v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
            dst[i] = (short) v;
        }
        return clipped;
    }

    // ── Turn ──────────────────────────────────────────────────────────────

    private void beginTurn(boolean manual, float gain) {
        long now = SystemClock.elapsedRealtime();
        verifyDueAt = -1;
        turnManual = manual;
        turnIntents = null;
        if (!manual) {
            wakes.incrementAndGet();
            lastWakeAtMs = System.currentTimeMillis();
            host.onActivity();
        }
        if (wake == null) {
            wantsCapture = true;
            if (!captureOn()) {
                wantsCapture = false;
                noteTurn("mic_unavailable");
                overlay.finish(VoiceOrbView.STATE_ERROR, overlay.text(R.string.voice_overlay_failed), 1800);
                return;
            }
        }
        if (manual) ring.skipToLatest();
        promptQueue.clear();
        prompts.stop();
        promptEndsAt = 0;
        unduckWhenQuiet = false;
        turn++;
        utteranceId = UUID.randomUUID().toString();
        utteranceLen = 0;
        matches.clear();
        dispatched = 0;
        pendingResults = 0;
        anySuccess = false;
        failureText = 0;
        failureKey = null;
        localWindowClosed = false;
        speechMs = 0;
        speechMsAtFirstMatch = -1;
        speechMsAtLastMatch = -1;
        speechOnsetSample = -1;
        lastLoudSample = -1;
        held.clear();
        heldCount = 0;
        speechStartAt = 0;
        lastSpeechAt = 0;
        turnStartedAt = now;
        turnGain = gain;
        recentPos = 0;
        recentFull = false;
        turnSamples = 0;
        wakeEndTurnSample = 0;
        cueBacklogSamples = 0;
        cueStartSample = 0;
        listenFromSample = -1;
        cueMs = 0;
        cueVolume = -1;
        cueFallback = null;
        cuePeakRms = 0f;
        listenPeakRms = 0f;
        vadSpeechSamples = 0;
        loudSamples = 0;
        turnWakeTail = 0;
        utteranceSkipSamples = 0;
        wakeTailPossible = false;
        preWarmFailed = null;
        turnMedia = false;
        pauseMs = MediaTurnPolicy.PAUSE_NONE;
        mediaHoldPhase = MEDIA_HOLD_NONE;
        mediaHoldUntilSample = -1;
        turnReplyHeard = false;
        wakeIgnoreUntilSample = 0;
        lastSpeechSample = 0;
        utteranceFull = false;
        turnBy = null;
        turnText = null;
        turnAsrMs = 0;
        turnIntentDetail = null;
        speechEndAt = 0;
        firstDispatchAt = 0;
        firstReplyAt = 0;
        speechRun = 0;
        if (vad != null) vad.reset();
        turnGainMeasured = !manual;
        // Push-to-talk restarts the mic, so the last audio is from the previous turn.
        lastAudioAt = now;
        // The wake word starts silently so speech straight after "OverDrive" is heard; the
        // button keeps its cue and the short mute that hides it from the mic.
        // Both paths chime so the driver knows it is listening; only the button mutes the mic for
        // it, because wake-word speech may already be running into the command.
        // Read BEFORE the cue: the chime itself would count as active music on the media stream
        // (the isMusicActive() fallback, used only when the daemon cannot see the sessions).
        turnMedia = mediaPlayingForTurn(now);
        // A cabin duck still held from the previous turn keeps its channel (duck() keeps the hold).
        if (!cabinDucked) {
            cabinDuckTurn = MediaTurnPolicy.cabinDuck(cabinDuckAvailable,
                    config.pauseMediaWhileListening, config.resumeMediaAfterListening);
            turnChannel = cabinDuckTurn ? duckedChannel() : null;
        }
        try {
            cueVolume = audioManager == null ? -1 : audioManager.getStreamVolume(Earcons.streamFor(channel()));
        } catch (Throwable ignored) {
            cueVolume = -1;
        }
        // Snapshot BEFORE the cue is issued: every unread sample predates the chime.
        if (!manual) cueBacklogSamples = ring.available();
        // A muted stream swallows the chime (field log: vol 0, heard -69 dBFS), so the wake cue
        // moves to an audible stream for this turn; Earcons picks it, the turn log records it.
        cueFallback = cueVolume == 0 ? Earcons.audibleFallback(audioManager, channel()) : null;
        cueMs = earcons.play(Earcons.WAKE, cueFallback != null ? cueFallback : channel());
        mutedUntil = manual ? now + cueMs + CUE_SETTLE_MS : 0;
        if (manual) {
            cueStartSample = 0;      // skipToLatest() emptied the ring before the cue
            listenFromSample = 0;    // muted chunks never reach onTurnAudio, so the first counted chunk is post-cue
        }
        duck();
        overlay.show(VoiceOrbView.STATE_LISTENING, overlay.text(R.string.voice_overlay_listening));

        VoiceAssistantConfig c = config;
        boolean genai = VoiceAssistantConfig.MODE_GENAI.equals(c.mode);
        boolean hybrid = VoiceAssistantConfig.MODE_HYBRID.equals(c.mode);
        if (genai || (hybrid && spotter == null)) {
            openSession();
            startCloud(false);
        } else if (spotter != null) {
            spotter.begin(config.resolvedCommandLanguage(locale()));
            phase = LOCAL;
            if (hybrid) openSession();
        } else {
            noteTurn("no_engine");
            finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_failed, Earcons.FAILURE,
                    VoicePrompts.FAILED);
        }
    }

    private void onLocalMatch(LocalCommandSpotter.Match m) {
        if (LocalCommandSpotter.INTENT_WAKE.equals(m.intent)) {
            reArm();
            return;
        }
        if ("assistant.cancel".equals(m.intent)) {
            cancelTurn();
            return;
        }
        if (turnBy == null) turnBy = "kws";
        for (LocalCommandSpotter.Match prior : matches) {
            if (prior.intent.equals(m.intent)
                    && String.valueOf(prior.slots).equals(String.valueOf(m.slots))) {
                return;
            }
        }
        if (matches.isEmpty()) {
            speechMsAtFirstMatch = speechMs;
            firstMatchWords = m.phrase == null ? 2 : m.phrase.trim().split("\\s+").length;
        }
        speechMsAtLastMatch = speechMs;
        matches.add(m);
        if (!VoiceAssistantConfig.MODE_LOCAL.equals(config.mode)) return;
        // A bare phrase runs at once; one inside a sentence waits for the whole utterance, since
        // only the transcript can tell "turn on the AC" from "do not turn on the AC".
        long leadingMs;
        if (m.phraseStartOffset >= 0 && speechOnsetSample >= 0) {
            long phraseStartSample = turnSamples - m.phraseStartOffset;
            leadingMs = Math.max(0, (phraseStartSample - speechOnsetSample) * 1000 / RATE);
        } else {
            leadingMs = Math.max(0, speechMsAtFirstMatch - firstMatchWords * WORD_MS);
        }
        boolean trailingLoud = lastLoudSample >= 0
                && turnSamples - lastLoudSample < CommandWindowGate.TRAILING_LOUD_FOR_HOLD_SAMPLES;
        // Holding only pays off when understandLocally() can confirm the hit at window close;
        // otherwise a non-English UI (or a still-loading Moonshine) would turn every hit inside
        // a sentence into "not understood", so the gate falls back to the no-transcript rule.
        boolean canValidate = transcriber != null && config.commandSttAllowed(locale());
        CommandWindowGate.Decision gate =
                CommandWindowGate.decide(config.resolvedCommandLanguage(locale()), leadingMs,
                        trailingLoud, !held.isEmpty(), canValidate);
        if (gate == CommandWindowGate.Decision.DISPATCH_NOW) {
            dispatch(m);
        } else {
            held.add(m);
            heldCount++;
        }
    }

    private void closeLocalWindow() {
        localWindowClosed = true;
        if (speechStartAt != 0 && speechEndAt == 0) speechEndAt = System.currentTimeMillis();
        if (spotter != null) spotter.end();
        boolean hybrid = VoiceAssistantConfig.MODE_HYBRID.equals(config.mode);
        if (!held.isEmpty()) {
            validateHeld(hybrid);
            return;
        }
        int extraWords = -1;
        if (matches.isEmpty() && speechStartAt != 0) {
            extraWords = understandLocally();
            if (phase != LOCAL) return;
        }
        if (matches.isEmpty()) {
            if (speechStartAt == 0) {
                closeSessionQuietly();
                noteTurn("no_speech");
                finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_cancelled, Earcons.CLOSE,
                        null);
            } else if (hybrid) {
                startCloud(true);
            } else {
                noteTurn("not_understood");
                finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_not_understood,
                        Earcons.FAILURE, VoicePrompts.NOT_UNDERSTOOD);
            }
            return;
        }
        if (hybrid) {
            boolean cloud;
            if (extraWords >= 0) {
                cloud = extraWords >= EXTRA_WORDS_FOR_CLOUD;
            } else {
                long trailing = speechMs - speechMsAtLastMatch;
                long leading = Math.max(0, speechMsAtFirstMatch - firstMatchWords * WORD_MS);
                cloud = trailing > TRAILING_SPEECH_FOR_CLOUD_MS || leading > LEADING_SPEECH_FOR_CLOUD_MS;
            }
            if (cloud) {
                startCloud(true);
                return;
            }
            closeSessionQuietly();
            for (LocalCommandSpotter.Match m : matches) dispatch(m);
        } else if (extraWords >= 0) {
            // Local mode dispatches spotted phrases at once; transcribed ones only exist now.
            for (LocalCommandSpotter.Match m : matches) dispatch(m);
        }
        if (pendingResults == 0) completeLocalTurn();
    }

    /**
     * Phrases spotted inside a longer sentence run only once the whole utterance confirms them:
     * the transcript (ordered, negation- and correction-aware) decides what runs, in spoken order,
     * and a held phrase it does not support is dropped. Without a usable transcript nothing held
     * runs; the driver hears the not-understood prompt (or GenAI takes the turn in hybrid),
     * unless a clean hit already ran on the instant path, whose result the turn then reports.
     */
    private void validateHeld(boolean hybrid) {
        // The held hits leave the list; only what the transcript confirms comes back.
        List<LocalCommandSpotter.Match> executed = new ArrayList<>(matches);
        executed.removeAll(held);
        matches.clear();
        matches.addAll(executed);
        held.clear();
        boolean transcribed = understandLocally() >= 0;
        if (phase != LOCAL) return;
        turnBy = "kwsasr";
        if (!transcribed) {
            if (hybrid) {
                startCloud(true);
                return;
            }
            if (executed.isEmpty()) {
                noteTurn("held_unverified");
                finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_not_understood,
                        Earcons.FAILURE, VoicePrompts.NOT_UNDERSTOOD);
                return;
            }
            // A command already ran; ending the turn here would discard its result and speak
            // an error over it, so only the unverified hits are dropped.
            if (pendingResults == 0) completeLocalTurn();
            return;
        }
        if (hybrid) closeSessionQuietly();
        List<LocalCommandSpotter.Match> plan =
                new ArrayList<>(matches.subList(executed.size(), matches.size()));
        matches.subList(executed.size(), matches.size()).clear();
        for (LocalCommandSpotter.Match m : plan) {
            if (sameMeaningIn(executed, m)) continue;
            matches.add(m);
            dispatch(m);
        }
        if (pendingResults == 0) completeLocalTurn();
    }

    private static boolean sameMeaningIn(List<LocalCommandSpotter.Match> list,
                                         LocalCommandSpotter.Match m) {
        for (LocalCommandSpotter.Match prior : list) {
            if (prior.intent.equals(m.intent)
                    && String.valueOf(prior.slots).equals(String.valueOf(m.slots))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Speech-to-text over the turn's audio (English only): when no phrase was spotted, or to
     * validate held ones. Appends the matched phrases and returns the leftover word count, or -1
     * when nothing matched.
     */
    private int understandLocally() {
        CommandTranscriber t = transcriber;
        if (t == null || utteranceLen < RATE / 4 || !config.commandSttAllowed(locale())) return -1;
        // Speech-to-text is level-sensitive too, so the buffer is brought to speech level first.
        float level = speechLevel(utterance, utteranceLen);
        float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_SPEECH_RMS / Math.max(level, 1f)));
        if (gain < 0.91f || gain > 1.1f) applyGain(utterance, 0, utteranceLen, gain, utterance);
        long started = SystemClock.elapsedRealtime();
        CommandTranscriber.Understood u = t.understand(utterance, 0, utteranceLen, !turnManual && wakeTailPossible);
        turnAsrMs = (int) (SystemClock.elapsedRealtime() - started);
        turnWakeTail = u == null ? 0 : u.wakeTail;
        if (u == null || u.matches.isEmpty()) {
            // The transcript is the only way to tell "misheard" from "not in the catalog".
            turnText = u == null ? null : CommandTranscriber.logSafe(u.text, 60);
            return -1;
        }
        for (LocalCommandSpotter.Match m : u.matches) {
            if ("assistant.cancel".equals(m.intent)) {
                cancelTurn();
                return -1;
            }
        }
        matches.addAll(u.matches);
        turnBy = "asr";
        asrHits.incrementAndGet();
        return u.extraWords;
    }

    private void dispatch(LocalCommandSpotter.Match m) {
        final int index = dispatched++;
        final int turnAtDispatch = turn;
        final String uid = utteranceId + "#" + index;
        final String loc = locale();
        pendingResults++;
        if (firstDispatchAt == 0) firstDispatchAt = System.currentTimeMillis();
        try {
            if (turnIntentDetail == null) turnIntentDetail = new JSONArray();
            turnIntentDetail.put(new JSONObject().put("intent", m.intent).put("slots", m.slots));
        } catch (Exception ignored) {
        }
        io.execute(() -> {
            if (turnAtDispatch != turn) return;
            JSONObject res = executeIntent(m, uid, loc);
            String code = res == null ? "failed" : res.optString("code", "failed");
            post(() -> onIntentResult(turnAtDispatch, code, m));
        });
    }

    private void onIntentResult(int forTurn, String code, LocalCommandSpotter.Match m) {
        if (forTurn != turn) return;
        pendingResults--;
        if (firstReplyAt == 0) firstReplyAt = System.currentTimeMillis();
        if ("done".equals(code)) {
            boolean spoke = speak(VoicePrompts.keyFor(m.intent, m.slots));
            if (!spoke && !anySuccess) {
                mutedUntil = SystemClock.elapsedRealtime()
                        + earcons.play(Earcons.SUCCESS, channel()) + 120;
            }
            anySuccess = true;
            overlay.show(VoiceOrbView.STATE_SUCCESS, overlay.text(R.string.voice_overlay_done));
        } else if ("duplicate".equals(code)) {
            anySuccess = true;
        } else if (failureText == 0) {
            if ("blocked_moving".equals(code)) {
                failureText = R.string.voice_overlay_blocked_moving;
                failureKey = VoicePrompts.BLOCKED_MOVING;
            } else if ("refused".equals(code)) {
                failureText = R.string.voice_overlay_refused;
                failureKey = VoicePrompts.REFUSED;
            } else {
                failureText = R.string.voice_overlay_failed;
                failureKey = VoicePrompts.FAILED;
            }
        }
        if (localWindowClosed && pendingResults == 0) completeLocalTurn();
    }

    private void completeLocalTurn() {
        if (phase != LOCAL) return;
        StringBuilder intents = new StringBuilder();
        for (int i = 0; i < matches.size() && i < 3; i++) {
            if (i > 0) intents.append(',');
            intents.append(matches.get(i).intent);
        }
        turnIntents = intents.length() == 0 ? null : intents.toString();
        if (failureText != 0) {
            failures.incrementAndGet();
            noteTurn(failureKey == null ? "failed" : failureKey.replace("status_", ""));
            finishTurn(VoiceOrbView.STATE_ERROR, failureText, Earcons.FAILURE, failureKey);
        } else {
            localHits.incrementAndGet();
            noteTurn("done");
            long now = SystemClock.elapsedRealtime();
            overlay.finish(VoiceOrbView.STATE_SUCCESS, overlay.text(R.string.voice_overlay_done),
                    Math.max(900, promptEndsAt - now + 300));
            endTurn();
        }
    }

    /** Speaks a clip now or after the one playing; false when the clip is unavailable. */
    private boolean speak(String key) {
        long now = SystemClock.elapsedRealtime();
        if (now < promptEndsAt) {
            if (promptQueue.size() < 3) promptQueue.add(key);
            return true;
        }
        return speakNow(key, now);
    }

    private boolean speakNow(String key, long now) {
        int ms = prompts.play(key, locale(), channel());
        if (ms < 0) return false;
        promptEndsAt = now + ms + 150;
        mutedUntil = Math.max(mutedUntil, promptEndsAt);
        return true;
    }

    private void servicePrompts(long now) {
        if (promptEndsAt == 0 || now < promptEndsAt) return;
        prompts.stop();
        promptEndsAt = 0;
        while (!promptQueue.isEmpty()) {
            if (speakNow(promptQueue.poll(), now)) return;
        }
        if (unduckWhenQuiet && phase == ARMED) {
            unduckWhenQuiet = false;
            unduck();
        }
    }

    // ── Cloud ─────────────────────────────────────────────────────────────

    private void openSession() {
        if (session != null) return;
        final int gen = ++sessionGeneration;
        sessionStreaming = false;
        sessionLive = false;
        // Today's local rules apply until the daemon names the provider.
        deadline.setProviderEndpoints(false);
        session = VoiceSessionClient.open(app, locale(), channel(), config.uplinkFrameMs,
                new SessionEvents(gen));
    }

    private void startCloud(boolean replayUtterance) {
        // The hybrid pre-warm already died; replaying the utterance into a fresh dial would only
        // add another connect timeout, so fail the turn with the reason that session reported.
        if (session == null && preWarmFailed != null) {
            failCloudTurn(preWarmFailed);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        phase = CLOUD;
        turnBy = "cloud";
        cloudTurns.incrementAndGet();
        replySpeaking = false;
        turnComplete = false;
        thinkingShown = replayUtterance;
        followUpUntil = 0;
        cloudStartedAt = now;
        lastCloudActivityAt = now;
        deadline.reset();
        if (replayUtterance) deadline.awaitFrom(now);
        if (session == null) openSession();
        sessionStreaming = true;
        if (replayUtterance) {
            speechStartAt = 0;
            lastSpeechAt = 0;
            if (utteranceLen > 0) session.sendPcm16k(utterance, 0, utteranceLen);
            overlay.show(VoiceOrbView.STATE_THINKING, overlay.text(R.string.voice_overlay_thinking));
        }
    }

    private void tickCloud(long now) {
        if (session == null) return;
        if (replySpeaking) return;
        if (followUpUntil > 0) {
            if (lastSpeechAt > followUpUntil - config.followUpSeconds * 1000L) {
                followUpUntil = 0;
                turnComplete = false;
                thinkingShown = false;
                deadline.reset();
                lastCloudActivityAt = now;
            } else if (now > followUpUntil) {
                closeSession("cloud");
            }
            return;
        }
        boolean userSpoke = speechStartAt > 0;
        if (userSpoke && !thinkingShown && now - lastSpeechAt > END_OF_SPEECH_MS) {
            thinkingShown = true;
            deadline.localSpeechEnded(now);
            overlay.show(VoiceOrbView.STATE_THINKING, overlay.text(R.string.voice_overlay_thinking));
        }
        // Cabin noise can look like endless speech, so 8 s of it also starts the wait (Gemini
        // only; OpenAI anchors the wait on its own speech_stopped instead).
        if (userSpoke) deadline.speechContinues(now, speechStartAt);
        if (deadline.timedOut(now)) {
            // Field log 2026-10-11: Gemini answered (reply audio played, turnComplete arrived),
            // the follow-up window re-armed the wait on local "speech" the provider never
            // answered, and 8 s later the answered turn ended as cloud_no_response with the
            // offline prompt. A turn whose reply was heard ends as answered instead.
            if (turnReplyHeard) {
                closeSession("cloud");
                return;
            }
            failures.incrementAndGet();
            if (!sessionLive) {
                noteTurn("cloud_connect_failed");
                finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_offline, Earcons.FAILURE,
                        VoicePrompts.OFFLINE);
            } else {
                // The provider was live and heard the audio; it found nothing to answer.
                noteTurn("cloud_no_response");
                finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_not_understood,
                        Earcons.FAILURE, VoicePrompts.NOT_UNDERSTOOD);
            }
            return;
        }
        if (!userSpoke && utteranceLen == 0 && now - cloudStartedAt > CLOUD_NO_SPEECH_MS) {
            closeSession("cloud_no_speech");
        } else if (now - (deadline.responded() ? lastCloudActivityAt : Math.max(lastCloudActivityAt, lastSpeechAt))
                > CLOUD_STALL_MS
                || now - cloudStartedAt > CLOUD_MAX_MS) {
            closeSession("cloud_timeout");
        }
    }

    private void startFollowUp() {
        int seconds = config.followUpSeconds;
        if (seconds <= 0) {
            closeSession("cloud");
            return;
        }
        followUpUntil = SystemClock.elapsedRealtime() + seconds * 1000L;
        overlay.show(VoiceOrbView.STATE_LISTENING, overlay.text(R.string.voice_overlay_listening));
    }

    /** Ends the cloud turn now; the session's own onEnded arrives later and is ignored. */
    private void closeSession(String outcome) {
        closeSessionQuietly();
        if (phase == CLOUD) {
            noteTurn(outcome);
            overlay.hide();
            endTurn();
        }
    }

    /** Its later events are dropped by generation, so nothing it was playing may keep the mic muted. */
    private void closeSessionQuietly() {
        VoiceSessionClient s = session;
        session = null;
        sessionStreaming = false;
        sessionLive = false;
        replySpeaking = false;
        sessionGeneration++;
        if (s != null) s.close();
    }

    /** Ends the cloud turn as a failure; no new clips, so connect failures speak the offline prompt. */
    private void failCloudTurn(String reason) {
        failures.incrementAndGet();
        if ("busy".equals(reason)) {
            noteTurn("busy");
            finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_busy, Earcons.FAILURE,
                    VoicePrompts.BUSY);
        } else if ("connect_failed".equals(reason)) {
            noteTurn("cloud_connect_failed");
            finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_offline, Earcons.FAILURE,
                    VoicePrompts.OFFLINE);
        } else if ("setup_failed".equals(reason)) {
            // The provider socket opened but the session was never ready (setup rejected or
            // stalled): the daemon log names the cause, the turn log tells it from a dead dial.
            noteTurn("cloud_setup_failed");
            finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_offline, Earcons.FAILURE,
                    VoicePrompts.OFFLINE);
        } else {
            noteTurn("offline");
            finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_offline, Earcons.FAILURE,
                    VoicePrompts.OFFLINE);
        }
    }

    private void onSessionEnded(String reason) {
        session = null;
        sessionStreaming = false;
        replySpeaking = false;
        sessionLive = false;
        if (phase != CLOUD) {
            // A hybrid pre-warm that died is remembered so the replay fails at speech end instead
            // of re-dialling. A busy pre-warm is not: the other session may be gone by then.
            if ("unavailable".equals(reason) || "failed".equals(reason) || "connect_failed".equals(reason)
                    || "setup_failed".equals(reason)) {
                preWarmFailed = reason;
            }
            return;
        }
        if ("busy".equals(reason) || "unavailable".equals(reason) || "failed".equals(reason)
                || "connect_failed".equals(reason) || "setup_failed".equals(reason)) {
            failCloudTurn(reason);
        } else {
            noteTurn("cloud");
            overlay.hide();
            endTurn();
        }
    }

    private void onProposal(JSONObject proposal) {
        if (session == null) return;
        String kind = VoiceSessionClient.proposalKind(proposal);
        if ("climate_temperature".equals(kind) || "sunshade".equals(kind)
                || "run_automation".equals(kind)) {
            session.approveProposal(proposal);
            String seen = turnIntents;
            if (seen == null) {
                turnIntents = kind;
            } else if (seen.split(",").length < 3 && !("," + seen + ",").contains("," + kind + ",")) {
                turnIntents = seen + "," + kind;
            }
        } else {
            session.declineProposal(proposal);
        }
    }

    // ── Turn end ──────────────────────────────────────────────────────────

    private void cancelTurn() {
        if (phase == ARMED) return;
        if (phase == ENROLL) {
            endEnroll(null, "cancelled");
            return;
        }
        closeSessionQuietly();
        if (spotter != null && phase == LOCAL && !localWindowClosed) spotter.end();
        promptQueue.clear();
        prompts.stop();
        promptEndsAt = 0;
        noteTurn("cancelled");
        finishTurn(VoiceOrbView.STATE_ERROR, R.string.voice_overlay_cancelled, Earcons.CLOSE, null);
    }

    /** Ends the turn with a spoken clip when one exists, else the given cue. */
    private void finishTurn(int orbState, int textRes, int cue, String clipKey) {
        long now = SystemClock.elapsedRealtime();
        boolean spoke = clipKey != null && speak(clipKey);
        if (!spoke) mutedUntil = Math.max(mutedUntil, now + earcons.play(cue, channel()) + 120);
        overlay.finish(orbState, overlay.text(textRes), Math.max(1400, promptEndsAt - now + 300));
        endTurn();
    }

    private void endTurn() {
        long now = SystemClock.elapsedRealtime();
        phase = ARMED;
        turn++;
        followUpUntil = 0;
        utteranceLen = 0;
        matches.clear();
        held.clear();
        localWindowClosed = false;
        turnGain = 1f;
        verifyDueAt = -1;
        closeSessionQuietly();
        refractoryUntil = now + REFRACTORY_MS;
        if (wake != null) wake.reset();
        if (now < promptEndsAt || !promptQueue.isEmpty()) unduckWhenQuiet = true;
        else unduck();
        if (wake == null && wantsCapture) {
            wantsCapture = false;
            captureOff();
        }
        host.onActivity();
    }

    // ── Wake-word enrollment ──────────────────────────────────────────────

    /** A request from the settings page starts enrollment once the engine is idle (each id once). */
    private void maybeStartEnroll() {
        VoiceAssistantConfig c = config;
        String id = c.enrollRequestId;
        if (id.isEmpty() || id.equals(enrollSeenId)) return;
        enrollSeenId = id;
        long age = System.currentTimeMillis() - c.enrollRequestedAt;
        if (Math.abs(age) > ENROLL_REQUEST_MAX_AGE_MS) {
            postEnrollEnd(id, null, "expired", false);
            return;
        }
        if (wake == null || spotter == null) {
            noteEnroll(id, "failed", 0, "unavailable");
            postEnrollEnd(id, null, "unavailable", false);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        verifyDueAt = -1;
        phase = ENROLL;
        turn++;
        enrollId = id;
        enrollMusic = c.enrollRequestMusic;
        VoiceAssistantConfig.PersonalVoice prior = VoiceAssistantConfig.findVoice(c.personalVoices, c.enrollRequestVoice);
        enrollPrevious = prior == null ? java.util.Collections.<String>emptyList() : prior.lines;
        enrollTakes.clear();
        enrollPeaks.clear();
        enrollPrompts = 0;
        if (!enrollMusic) startDecoderLoad();
        // Takes are recorded with media held exactly as a command turn holds it, except over music.
        if (!enrollMusic) {
            turnMedia = mediaPlayingForTurn(now);
            if (!cabinDucked) {
                cabinDuckTurn = MediaTurnPolicy.cabinDuck(cabinDuckAvailable,
                        config.pauseMediaWhileListening, config.resumeMediaAfterListening);
                turnChannel = cabinDuckTurn ? duckedChannel() : null;
            }
            duck();
        }
        lastAudioAt = now;
        promptEnrollTake(now, true);
    }

    private void promptEnrollTake(long now, boolean first) {
        enrollPrompts++;
        enrollLen = 0;
        enrollOnset = -1;
        enrollLastLoud = -1;
        enrollLoudRun = 0;
        enrollQuietRun = 0;
        enrollArmed = false;
        int take = enrollTakes.size() + 1;
        overlay.show(VoiceOrbView.STATE_LISTENING, overlay.text(
                first ? R.string.voice_enroll_start : R.string.voice_enroll_say, take, WakeEnrollment.TAKES));
        ring.skipToLatest();
        mutedUntil = now + earcons.play(Earcons.WAKE, channel()) + CUE_SETTLE_MS;
        enrollPromptAt = mutedUntil;
        enrollWaitFrom = mutedUntil;
        noteEnroll(enrollId, "recording", take, null);
        host.onActivity();
    }

    /**
     * Raw take audio: up to 1.5 s before speech, the speech (≤ 2 s) and 0.3 s after it. The wake
     * model hears every chunk, so a take's stage-1 peak comes from a detector that never restarted.
     */
    private void onEnrollAudio(short[] chunk, int n, float rms, long now, boolean muted) {
        float p = 0f;
        if (wake != null) {
            wake.process(chunk, 0, n);
            p = wake.lastChunkPeak();
        }
        if (enrollLen < 0 || muted) return;
        boolean loud = rms > Math.max(noiseFloor * 3f, 150f);
        if (!enrollArmed) {
            // The chime's echo arrives after its playback ends: listen only once the cabin is quiet again.
            if (now < enrollPromptAt + ENROLL_ECHO_GUARD_MS) return;
            enrollQuietRun = loud ? 0 : enrollQuietRun + 1;
            if (enrollQuietRun < ENROLL_ARM_QUIET_CHUNKS) return;
            enrollArmed = true;
            enrollLen = 0;
        }
        if (enrollOnset < 0) {
            int keep = Math.max(0, ENROLL_LEAD_SAMPLES - n);
            if (enrollLen > keep) {
                System.arraycopy(enrollBuf, enrollLen - keep, enrollBuf, 0, keep);
                enrollLen = keep;
            }
            System.arraycopy(chunk, 0, enrollBuf, enrollLen, n);
            enrollLen += n;
            enrollLoudRun = loud ? enrollLoudRun + 1 : 0;
            if (enrollLoudRun >= 2) {
                enrollOnset = Math.max(0, enrollLen - 2 * n);
                enrollLastLoud = enrollLen;
                enrollTakePeak = p;
            }
            return;
        }
        int room = Math.min(n, enrollBuf.length - enrollLen);
        System.arraycopy(chunk, 0, enrollBuf, enrollLen, room);
        enrollLen += room;
        if (loud) enrollLastLoud = enrollLen;
        if (p > enrollTakePeak) enrollTakePeak = p;
        if (enrollLen - enrollLastLoud >= ENROLL_END_QUIET_SAMPLES
                || enrollLen - enrollOnset >= ENROLL_MAX_SPEECH_SAMPLES
                || enrollLen >= enrollBuf.length) {
            finishEnrollTake(now);
        }
    }

    private void finishEnrollTake(long now) {
        boolean speech = enrollLastLoud - enrollOnset >= ENROLL_MIN_SPEECH_SAMPLES
                && (!enrollMusic || enrollTakePeak >= WakeEnrollment.MEDIA_ATTEMPT_MIN_PEAK);
        if (speech) {
            int end = Math.min(enrollLen, enrollLastLoud + ENROLL_TAIL_SAMPLES);
            enrollTakes.add(java.util.Arrays.copyOf(enrollBuf, end));
            enrollPeaks.add(enrollTakePeak);
            enrollWaitFrom = now;
        }
        // A click or a cough is not a take: keep listening for the same one, no new chime.
        enrollLen = 0;
        enrollOnset = -1;
        enrollLastLoud = -1;
        enrollLoudRun = 0;
        if (!speech) return;
        if (enrollTakes.size() >= WakeEnrollment.TAKES) {
            learnEnroll();
            return;
        }
        int take = enrollTakes.size() + 1;
        overlay.show(VoiceOrbView.STATE_LISTENING,
                overlay.text(R.string.voice_enroll_next, take, WakeEnrollment.TAKES));
        noteEnroll(enrollId, "recording", take, null);
        host.onActivity();
    }

    private void tickEnroll(long now) {
        if (enrollId == null) return;
        if (lastAudioAt > 0 && now - lastAudioAt > NO_AUDIO_CLOSE_MS * 3) {
            endEnroll(null, "no_audio");
        } else if (enrollLen >= 0 && enrollOnset < 0 && now - enrollWaitFrom > ENROLL_WAIT_MS) {
            if (enrollPrompts < ENROLL_MAX_PROMPTS) {
                promptEnrollTake(now, false);
            } else if (enrollTakes.size() >= WakeEnrollment.MIN_TAKES) {
                learnEnroll();
            } else {
                endEnroll(null, "no_speech");
            }
        }
    }

    /**
     * Stage-1 peaks and levels here (the detector and the level buffer are this thread's), the
     * phone decoding and the cross-checks on the io executor; the result comes back to endEnroll.
     * enrollLen = -1 parks the capture meanwhile.
     */
    private void learnEnroll() {
        enrollLen = -1;
        overlay.show(VoiceOrbView.STATE_THINKING, overlay.text(R.string.voice_enroll_learning));
        noteEnroll(enrollId, "processing", enrollTakes.size(), null);
        host.onActivity();
        final List<short[]> raw = new ArrayList<>(enrollTakes);
        final float[] peaks = new float[enrollTakes.size()];
        for (int i = 0; i < peaks.length; i++) peaks[i] = enrollPeaks.get(i);
        if (enrollMusic) {
            WakeEnrollment.Result music = WakeEnrollment.overMusic(enrollPeaks);
            submitMedia(() -> dumpEnrollTakes(raw, peaks, music, true));
            endEnroll(music, music.reason);
            return;
        }
        final String id = enrollId;
        final List<short[]> normed = new ArrayList<>();
        for (int i = 0; i < enrollTakes.size(); i++) {
            short[] pcm = enrollTakes.get(i).clone();
            float level = speechLevel(pcm, pcm.length);
            applyGain(pcm, 0, pcm.length,
                    Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_SPEECH_RMS / Math.max(level, 1f))), pcm);
            normed.add(pcm);
        }
        final LocalCommandSpotter kws = spotter;
        final DecoderLoad load = enrollDecoderLoad;
        final List<String> previous = enrollPrevious;
        // Its own thread: the io executor keeps renewing the media duck meanwhile.
        Thread learn = new Thread(() -> {
            WakeEnrollment.Result result = null;
            try {
                if (load != null) load.done.await(30, java.util.concurrent.TimeUnit.SECONDS);
                PhoneDecoder decoder = null;
                if (load != null) {
                    synchronized (load) {
                        decoder = load.decoder;
                    }
                }
                if (decoder != null) {
                    List<WakeEnrollment.Take> takes = new ArrayList<>();
                    for (int i = 0; i < normed.size(); i++) {
                        short[] pcm = normed.get(i);
                        takes.add(new WakeEnrollment.Take(pcm, decoder.decode(pcm, 0, pcm.length), peaks[i]));
                    }
                    result = WakeEnrollment.learn(takes, previous,
                            (pcm, keywords) -> kws.hearsWake(pcm, 0, pcm.length, keywords), kws::knowsTokens);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Wake enrollment failed: " + t.getMessage());
            } finally {
                if (load != null) load.abandon();
            }
            dumpEnrollTakes(raw, peaks, result, false);
            final WakeEnrollment.Result done = result;
            post(() -> {
                if (phase == ENROLL && id.equals(enrollId)) {
                    endEnroll(done, done == null ? "unavailable" : done.reason);
                }
            });
        }, "VoiceEnrollLearn");
        learn.setDaemon(true);
        learn.start();
    }

    /**
     * Tuning aid: after `run-as com.overdrive.app touch files/voice_enroll.request` the next training
     * leaves its raw takes (16 kHz mono WAV) and result in files/voice_enroll/. Any thread.
     */
    private void dumpEnrollTakes(List<short[]> raw, float[] peaks, WakeEnrollment.Result result, boolean music) {
        java.io.File dir = app.getFilesDir();
        if (dir == null || !new java.io.File(dir, "voice_enroll.request").delete()) return;
        java.io.File out = new java.io.File(dir, "voice_enroll");
        if (!out.isDirectory() && !out.mkdirs()) return;
        try {
            for (int i = 0; i < raw.size(); i++) {
                short[] pcm = raw.get(i);
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length * 2)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length * 2)
                        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                        .putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                        .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
                for (short s : pcm) b.putShort(s);
                try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(out, "take" + (i + 1) + ".wav"))) {
                    f.write(b.array());
                }
            }
            JSONArray p = new JSONArray();
            for (float f : peaks) p.put(round2(f));
            JSONObject meta = new JSONObject().put("at", System.currentTimeMillis()).put("peaks", p)
                    .put("music", music)
                    .put("reason", result == null ? "unavailable" : String.valueOf(result.reason));
            if (result != null) meta.put("decodes", new JSONArray(result.decodes)).put("lines", new JSONArray(result.lines));
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(out, "result.json"))) {
                f.write(meta.toString(1).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Enrollment dump failed: " + e.getMessage());
        }
    }

    private void startDecoderLoad() {
        final DecoderLoad load = new DecoderLoad();
        enrollDecoderLoad = load;
        Thread t = new Thread(() -> {
            PhoneDecoder decoder = null;
            try {
                decoder = PhoneDecoder.load(app);
            } catch (Throwable e) {
                Log.w(TAG, "Phone decoder failed to load: " + e.getMessage());
            }
            synchronized (load) {
                if (load.abandoned) {
                    if (decoder != null) decoder.close();
                } else {
                    load.decoder = decoder;
                }
            }
            load.done.countDown();
        }, "VoicePhoneLoad");
        t.setDaemon(true);
        t.start();
    }

    /** Applies and reports a result (or a failure) and returns to listening for the wake word. */
    private void endEnroll(WakeEnrollment.Result result, String reason) {
        if (phase != ENROLL) return;
        String id = enrollId;
        long now = SystemClock.elapsedRealtime();
        boolean ok = result != null && result.ok();
        if (ok && !enrollMusic) {
            spotter.setPersonalWake(String.join("/", result.lines));
            wake.setCutoffOverride(Double.isNaN(result.cutoff) ? Float.NaN : (float) result.cutoff);
        }
        postEnrollEnd(id, result, ok ? null : reason, enrollMusic);
        int text = ok ? R.string.voice_enroll_done
                : "cancelled".equals(reason) ? R.string.voice_overlay_cancelled : R.string.voice_enroll_failed;
        overlay.finish(ok ? VoiceOrbView.STATE_SUCCESS : VoiceOrbView.STATE_ERROR, overlay.text(text), 2500);
        mutedUntil = Math.max(mutedUntil, now + earcons.play(
                ok ? Earcons.SUCCESS : "cancelled".equals(reason) ? Earcons.CLOSE : Earcons.FAILURE, channel()) + 120);
        noteEnroll(id, ok ? "saved" : "failed", enrollTakes.size(), ok ? null : reason);
        enrollTakes.clear();
        enrollPeaks.clear();
        enrollId = null;
        enrollMusic = false;
        if (enrollDecoderLoad != null) {
            enrollDecoderLoad.abandon();
            enrollDecoderLoad = null;
        }
        phase = ARMED;
        turn++;
        verifyDueAt = -1;
        if (wake != null) wake.reset();
        refractoryUntil = now + REFRACTORY_MS;
        ring.skipToLatest();
        unduck();
        host.onActivity();
    }

    private void noteEnroll(String id, String state, int take, String reason) {
        try {
            JSONObject s = new JSONObject()
                    .put("id", id == null ? "" : id)
                    .put("state", state)
                    .put("take", take)
                    .put("takes", WakeEnrollment.TAKES)
                    .put("at", System.currentTimeMillis());
            if (reason != null) s.put("reason", reason);
            synchronized (diagLock) {
                enrollStatus = s;
                enrollStatusAt = SystemClock.elapsedRealtime();
            }
        } catch (Exception ignored) {
        }
    }

    /** {@code save} with the learned lines, or {@code fail} with the reason, to the daemon. */
    private void postEnrollEnd(String id, WakeEnrollment.Result result, String reason, boolean music) {
        if (id == null || id.isEmpty()) return;
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            if (result != null && result.ok() && music) {
                body.put("action", "save")
                        .put("mediaCutoff", result.cutoff)
                        .put("takes", result.takes)
                        .put("hits", result.hits);
            } else if (result != null && result.ok()) {
                body.put("action", "save")
                        .put("lines", new JSONArray(result.lines))
                        .put("takes", result.takes)
                        .put("baseHits", result.baseHits)
                        .put("hits", result.hits);
                if (!Double.isNaN(result.cutoff)) body.put("cutoff", result.cutoff);
            } else {
                body.put("action", "cancelled".equals(reason) ? "cancel" : "fail")
                        .put("reason", reason == null ? "failed" : reason);
            }
            if (result != null) {
                JSONArray peaks = new JSONArray();
                for (Float f : result.peaks) peaks.put(round2(f));
                body.put("decodes", new JSONArray(result.decodes)).put("peaks", peaks);
            }
        } catch (Exception e) {
            return;
        }
        submitMedia(() -> {
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/voice/enroll", "POST", 1500, 5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
            } catch (Throwable t) {
                Log.w(TAG, "enrollment report failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Fast down, slow up, in audio time; exact-zero chunks (a silenced mic) say nothing about the cabin. */
    private void trackNoiseFloor(float rms, int n) {
        if (rms < 1f) return;
        float dt = n / (float) RATE;
        if (rms < noiseFloor) noiseFloor += (rms - noiseFloor) * Math.min(1f, dt / 0.18f);
        else noiseFloor += (rms - noiseFloor) * Math.min(1f, dt / 4f);
    }

    /**
     * {@code rms} is of turn-gained audio, so the raw noise floor is scaled to match. With the
     * VAD loaded it decides what is speech (steady cabin noise is not); without it, speech starts
     * on the second loud chunk in a row, so a single bump does not end the wait for a command.
     */
    private void trackSpeech(short[] pcm, int off, int n, float rms, long now) {
        float floor = noiseFloor * turnGain;
        boolean loud = rms > Math.max(Math.max(floor * 2.0f, floor + 400f), 350f);
        if (!loud) speechRun = 0;
        boolean loudRun = loud && (speechStartAt != 0 || ++speechRun >= 2);
        if (loudRun) {
            // This chunk has not been counted yet, so turnSamples is where it starts and the loud
            // run began one chunk earlier. The clean-hit gate must see these within one chunk of
            // a keyword hit, which a VAD with its own hangover cannot promise: they stay RMS-based.
            lastLoudSample = turnSamples + n;
            if (speechOnsetSample < 0) speechOnsetSample = Math.max(0, turnSamples - n);
        }
        boolean speech = vad != null ? vad.speech(pcm, off, n) : loudRun;
        if (loudRun) loudSamples += n;
        if (speech) vadSpeechSamples += n;
        if (!speech) return;
        boolean firstSpeech = speechStartAt == 0;
        if (firstSpeech) speechStartAt = now;
        lastSpeechAt = now;
        lastSpeechSample = turnSamples + n;
        speechMs += n * 1000L / RATE;
        if (firstSpeech && !turnGainMeasured) remeasureGain();
        // Once the provider has answered, cabin noise must not keep a stalled session alive.
        if (phase == CLOUD && !deadline.responded()) lastCloudActivityAt = now;
    }

    /**
     * Holds the cabin audio for the turn. Media that is playing (per the daemon's media sessions)
     * is paused when the user allows it: full transient focus, which well-behaved players treat as
     * pause, plus a pause of exactly the playing session through the daemon for players that
     * ignore focus. Until that pause is confirmed the command window does not count. Otherwise,
     * and when nothing plays, it is ducked as before. A hold still in force from the previous
     * turn is kept as it is.
     */
    private void duck() {
        if (cabinDucked) return;
        if (ducked) return;
        if (cabinDuckTurn) {
            holdCabinDuck();
            return;
        }
        MediaTurnPolicy.Focus focus = MediaTurnPolicy.forTurn(audioManager != null, turnMedia,
                config.pauseMediaWhileListening);
        if (focus == MediaTurnPolicy.Focus.NONE) return;
        boolean pause = focus == MediaTurnPolicy.Focus.PAUSE;
        mediaHeld = true;
        try {
            audioManager.requestAudioFocus(focusListener, Earcons.streamFor(config.audioChannel),
                    pause ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                            : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            ducked = true;
        } catch (Throwable t) {
            Log.w(TAG, "duck failed: " + t.getMessage());
        }
        if (pause && !mediaPaused && !mediaPausePending) requestMediaPause();
    }

    /**
     * The head unit's own assistant duck, as BYD's assistant holds it: no audio focus and no pause,
     * so nothing has to be resumed. The daemon releases it if the renewals stop.
     */
    private void holdCabinDuck() {
        cabinDucked = true;
        mediaHeld = true;
        cabinDuckRenewedAt = SystemClock.elapsedRealtime();
        submitMedia(() -> {
            MediaSessionClient.Duck reply = MediaSessionClient.duck(CABIN_DUCK_LEASE_MS);
            // Refused here (another allow-list): later turns pause media as before.
            if (Boolean.FALSE.equals(reply.supported)) post(() -> cabinDuckAvailable = false);
        });
    }

    /** Asks the daemon once whether the head unit has the assistant duck; no answer retries later. */
    private void probeCabinDuck(long now) {
        cabinDuckProbeBusy = true;
        cabinDuckProbeAt = now + CABIN_DUCK_PROBE_RETRY_MS;
        submitMedia(() -> {
            Boolean available = MediaSessionClient.duckState().supported;
            post(() -> {
                cabinDuckProbeBusy = false;
                if (available != null) cabinDuckAvailable = available;
            });
        });
    }

    /** OverDrive's own sound: the configured channel, or the one the cabin duck leaves audible. */
    private String channel() {
        String held = turnChannel;
        return held != null ? held : config.audioChannel;
    }

    private String duckedChannel() {
        return MediaTurnPolicy.duckedChannel(config.audioChannel,
                streamAudible(Earcons.CHANNEL_VOICE), streamAudible(Earcons.CHANNEL_NAVIGATION));
    }

    private boolean streamAudible(String channel) {
        try {
            return audioManager != null && audioManager.getStreamVolume(Earcons.streamFor(channel)) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Asks the daemon to pause the playing session; the window waits for it (bounded). */
    private void requestMediaPause() {
        long now = SystemClock.elapsedRealtime();
        mediaPausePending = true;
        pauseRequestedAt = now;
        mediaHoldPhase = MEDIA_HOLD_AWAIT_REPLY;
        mediaHoldUntilSample = Long.MAX_VALUE;
        mediaHoldDeadline = now + MediaTurnPolicy.PAUSE_REPLY_WAIT_MS;
        submitMedia(() -> {
            MediaSessionClient.Paused reply = MediaSessionClient.pause();
            post(() -> onMediaPaused(reply));
        });
    }

    private void onMediaPaused(MediaSessionClient.Paused reply) {
        mediaPausePending = false;
        if (!mediaHeld) {
            // The turn ended before the reply: nothing holds the media any more, so what this
            // late pause stopped is played again at once (when the user wants it back).
            if (MediaTurnPolicy.shouldResume(reply.paused, config.resumeMediaAfterListening)) {
                String pkg = reply.pkg;
                String method = reply.method;
                submitMedia(() -> MediaSessionClient.resume(pkg, method));
            }
            return;
        }
        if (!reply.paused) {
            // Nothing was playing after all (or the daemon could not pause it): the window opens now.
            releaseMediaHold();
            return;
        }
        mediaPaused = true;
        mediaPkg = reply.pkg;
        mediaPauseMethod = reply.method;
        if (mediaHoldPhase != MEDIA_HOLD_AWAIT_REPLY) {
            // The reply came after the wait ran out: the window already opened as before.
            pauseMs = MediaTurnPolicy.PAUSE_UNCONFIRMED;
            Log.i(TAG, "media pause reply after " + (SystemClock.elapsedRealtime() - pauseRequestedAt)
                    + " ms; window opened without it");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        mediaHoldPhase = MEDIA_HOLD_CONFIRMING;
        mediaConfirmStartedAt = now;
        mediaConfirmDueAt = now;
        mediaHoldDeadline = now + MediaTurnPolicy.CONFIRM_POLL_MS;
    }

    /**
     * Runs the bounded wait for the pause: its reply (PAUSE_REPLY_WAIT_MS), then the daemon state
     * saying it stopped (CONFIRM_POLL_MS). Past either bound the window opens as before; the
     * deadline is enforced here, so a poll stuck behind a slow io task cannot extend the hold.
     */
    private void tickMediaHold(long now) {
        if (mediaHoldPhase == MEDIA_HOLD_NONE) return;
        if (now > mediaHoldDeadline) {
            pauseMs = MediaTurnPolicy.PAUSE_UNCONFIRMED;
            Log.i(TAG, mediaHoldPhase == MEDIA_HOLD_AWAIT_REPLY
                    ? "media pause not answered within " + MediaTurnPolicy.PAUSE_REPLY_WAIT_MS
                            + " ms; window opens as before"
                    : "media pause not confirmed within " + MediaTurnPolicy.CONFIRM_POLL_MS
                            + " ms; window opens as before");
            releaseMediaHold();
        } else if (mediaHoldPhase == MEDIA_HOLD_CONFIRMING && now >= mediaConfirmDueAt
                && !mediaPollBusy) {
            pollMediaState(true);
        }
    }

    private void onMediaConfirmState(MediaSessionClient.State state) {
        if (mediaHoldPhase != MEDIA_HOLD_CONFIRMING || !mediaHeld) return;
        long now = SystemClock.elapsedRealtime();
        if (MediaTurnPolicy.pauseConfirmed(state.source, state.playing)) {
            pauseMs = (int) Math.min(60_000L, now - pauseRequestedAt);
            mediaHoldPhase = MEDIA_HOLD_NONE;
            // The window opens once the speakers have decayed, counted in captured samples: the
            // ones still unread in the ring were captured before this moment.
            long opensAt = turnSamples + ring.available()
                    + MediaTurnPolicy.SPEAKER_DECAY_MS * RATE / 1000;
            mediaHoldUntilSample = opensAt;
            if (listenFromSample >= 0 && listenFromSample < opensAt) listenFromSample = opensAt;
        } else {
            // Still playing (or the daemon cannot see it): poll again until tickMediaHold's deadline.
            mediaConfirmDueAt = now + MediaTurnPolicy.CONFIRM_POLL_INTERVAL_MS;
        }
    }

    private void releaseMediaHold() {
        mediaHoldPhase = MEDIA_HOLD_NONE;
        mediaHoldUntilSample = -1;
    }

    /**
     * Releases the hold. Only media the daemon paused for this engine is resumed, only while that
     * session is still paused (the daemon checks), and only when the user wants it back; never
     * the user's own pause.
     */
    private void unduck() {
        if (cabinDucked) {
            cabinDucked = false;
            submitMedia(MediaSessionClient::releaseDuck);
        }
        cabinDuckTurn = false;
        turnChannel = null;
        if (ducked && audioManager != null) {
            ducked = false;
            try {
                audioManager.abandonAudioFocus(focusListener);
            } catch (Throwable ignored) {
            }
        }
        mediaHeld = false;
        releaseMediaHold();
        if (MediaTurnPolicy.shouldResume(mediaPaused, config.resumeMediaAfterListening)) {
            String pkg = mediaPkg;
            String method = mediaPauseMethod;
            submitMedia(() -> MediaSessionClient.resume(pkg, method));
        }
        mediaPaused = false;
        mediaPkg = null;
        mediaPauseMethod = null;
    }

    /** One daemon media-state call on the io executor; {@code confirm} is the pause confirmation. */
    private void pollMediaState(boolean confirm) {
        mediaPollBusy = true;
        submitMedia(() -> {
            MediaSessionClient.State state = MediaSessionClient.state(confirm);
            boolean music = !state.known() && isMusicActive();
            post(() -> onMediaState(state, music, confirm));
        });
    }

    private void onMediaState(MediaSessionClient.State state, boolean musicActive, boolean confirm) {
        mediaPollBusy = false;
        if (confirm) {
            onMediaConfirmState(state);
            return;
        }
        // A turn began meanwhile: this read predates it (and may have heard our own cue).
        if (phase != ARMED || ducked) return;
        mediaSource = state.source;
        mediaSessionPlaying = state.playing;
        mediaStateAt = SystemClock.elapsedRealtime();
        mediaActive = MediaTurnPolicy.mediaPlaying(state.source, state.playing, musicActive);
        applyWakeCutoff();
    }

    /** Media playing right now, for the turn: the daemon's fresh state, else isMusicActive() as before. */
    private boolean mediaPlayingForTurn(long now) {
        boolean fresh = mediaStateAt > 0 && now - mediaStateAt <= MediaTurnPolicy.STATE_MAX_AGE_MS;
        String source = fresh ? mediaSource : MediaTurnPolicy.SOURCE_UNKNOWN;
        boolean unknown = MediaTurnPolicy.SOURCE_UNKNOWN.equals(source);
        return MediaTurnPolicy.mediaPlaying(source, mediaSessionPlaying, unknown && isMusicActive());
    }

    /**
     * The one change to stage-1 behaviour: while media genuinely plays, the wake model's cutoff
     * drops to wakeCutoffUnderMedia (stage 2 stays KWS-only with its refractory, MediaWakeGate);
     * without media it is the model's own cutoff, unchanged.
     */
    private void applyWakeCutoff() {
        if (wake != null) {
            VoiceAssistantConfig c = config;
            double underMedia = c.wakeCutoffUnderMedia;
            // High sensitivity lowers the media cutoff by the same step as the normal one.
            if (VoiceAssistantConfig.SENSITIVITY_HIGH.equals(c.sensitivity)
                    && underMedia < VoiceAssistantConfig.WAKE_CUTOFF_UNDER_MEDIA_MAX - 1e-6) {
                underMedia -= WakeWordDetector.HIGH_DELTA;
            }
            wake.setMediaCutoff(MediaWakeGate.stage1MediaCutoff(mediaActive, underMedia));
        }
    }

    /** Stage-1 diagnostics after each fed chunk: near misses under media, last/peak for the status. */
    private void noteStage1(boolean fired, long now) {
        float peak = wake.lastChunkPeak();
        float last = wake.lastProbability();
        float cutoff = wake.effectiveCutoff();
        // While a trigger waits for its stage-2 check the bump belongs to that hit, not a miss.
        float miss = nearMiss.onChunk(peak, last, cutoff, mediaActive, fired || verifyDueAt >= 0, now);
        synchronized (diagLock) {
            stage1Last = last;
            if (peak > stage1Peak) stage1Peak = peak;
            stage1Cutoff = cutoff;
            stage1Media = mediaActive;
            if (!Float.isNaN(miss)) {
                if (nearMissLog.size() >= MAX_QUEUED_NEAR_MISSES) nearMissLog.pollFirst();
                nearMissLog.addLast(miss);
            }
        }
        if (!Float.isNaN(miss)) host.onActivity();
    }

    /** Daemon calls run on the io executor; once the engine is closing, on a short-lived thread. */
    private void submitMedia(Runnable task) {
        if (running) {
            try {
                io.execute(task);
                return;
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                // Shut down between the check and the call: fall through to a thread.
            }
        }
        Thread t = new Thread(task, "VoiceMedia");
        t.setDaemon(true);
        t.start();
    }

    private boolean isMusicActive() {
        if (audioManager == null) return false;
        try {
            return audioManager.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private void releaseAll() {
        closeSessionQuietly();
        if (enrollDecoderLoad != null) {
            enrollDecoderLoad.abandon();
            enrollDecoderLoad = null;
        }
        if (spotter != null) {
            try { spotter.close(); } catch (Throwable ignored) {}
            spotter = null;
        }
        if (vad != null) {
            try { vad.close(); } catch (Throwable ignored) {}
            vad = null;
        }
        if (transcriber != null) {
            try { transcriber.close(); } catch (Throwable ignored) {}
            transcriber = null;
        }
        CommandTranscriber orphan = loadedTranscriber.getAndSet(null);
        if (orphan != null) {
            try { orphan.close(); } catch (Throwable ignored) {}
        }
        if (wake != null) {
            try { wake.close(); } catch (Throwable ignored) {}
            wake = null;
        }
        wakeLoaded = false;
        promptQueue.clear();
        prompts.release();
        unduck();
        overlay.release();
    }

    private JSONObject executeIntent(LocalCommandSpotter.Match m, String uid, String loc) {
        HttpURLConnection conn = null;
        try {
            JSONObject req = new JSONObject();
            req.put("intent", m.intent);
            req.put("slots", m.slots == null ? new JSONObject() : m.slots);
            req.put("source", "local");
            req.put("utteranceId", uid);
            req.put("locale", loc);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            conn = DaemonHttpClient.open("/api/voice/intent", "POST", 1500, 8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[2048];
            int r;
            while ((r = in.read(tmp)) > 0) buf.write(tmp, 0, r);
            in.close();
            return new JSONObject(buf.toString("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "intent " + m.intent + " failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The speech-to-text model is English; non-Latin UI languages keep keyword spotting only. */
    private static boolean englishSpeech() {
        String lang = locale().toLowerCase(java.util.Locale.ROOT);
        return !(lang.startsWith("zh") || lang.startsWith("ja") || lang.startsWith("ko")
                || lang.startsWith("th") || lang.startsWith("ru") || lang.startsWith("ar")
                || lang.startsWith("he") || lang.startsWith("vi"));
    }

    private static String locale() {
        try {
            androidx.core.os.LocaleListCompat list =
                    androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
            if (!list.isEmpty() && list.get(0) != null) return list.get(0).toLanguageTag();
        } catch (Throwable ignored) {
        }
        return java.util.Locale.getDefault().toLanguageTag();
    }

    private static float rms(short[] s, int off, int n) {
        if (n <= 0) return 0f;
        double sum = 0;
        for (int i = off; i < off + n; i++) sum += (double) s[i] * s[i];
        return (float) Math.sqrt(sum / n);
    }

    private static float dbfs(float rms) {
        return (float) (20 * Math.log10(Math.max(rms, 0.1f) / 32768f));
    }

    /** A 0..1 probability with two decimals, for the runtime report. */
    private static double round2(float p) {
        if (Float.isNaN(p)) return 0.0;
        return Math.round(Math.max(0f, Math.min(1f, p)) * 100f) / 100.0;
    }

    private void noteCheck(boolean heard, String by, float level, float gain, int ms, String text,
                           boolean media) {
        try {
            JSONObject check = new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("heard", heard)
                    .put("by", by == null ? "none" : by)
                    .put("levelDb", Math.round(dbfs(level)))
                    .put("gainDb", Math.round(20 * Math.log10(gain)))
                    .put("ms", ms);
            if (text != null) check.put("text", text);
            if (media) check.put("media", true);
            check.put("p", round2(triggerPeak));
            synchronized (diagLock) {
                lastCheck = check;
                if (checkLog.size() >= MAX_QUEUED_CHECKS) {
                    checkLog.pollFirst();
                    checksDropped++;
                }
                checkLog.addLast(check);
            }
        } catch (Exception ignored) {
        }
    }

    /** Records the outcome with how this turn started and what it matched. */
    private void noteTurn(String outcome) {
        try {
            JSONObject t = new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("outcome", outcome)
                    .put("via", turnManual ? "button" : "wake");
            if (turnIntents != null) t.put("intents", turnIntents);
            if (turnBy != null) t.put("by", turnBy);
            if (heldCount > 0) t.put("held", heldCount);
            if (turnAsrMs > 0) t.put("asrMs", turnAsrMs);
            if (turnWakeTail > 0) t.put("tail", turnWakeTail);
            if (cueMs > 0) t.put("cueMs", cueMs);
            if (cueVolume >= 0) t.put("cueVol", cueVolume);
            if (cueFallback != null) t.put("cueFallback", cueFallback);
            if (cuePeakRms > 0) t.put("cueDb", Math.round(dbfs(cuePeakRms)));
            if (listenFromSample >= 0 && localWindowClosed) {   // LOCAL windows only: genai-mode turns never close one, so listenMs/micDb stay absent there (design §6.2 a)
                long listenEnd = speechOnsetSample >= 0 ? speechOnsetSample : turnSamples;
                t.put("listenMs", Math.max(0L, (listenEnd - listenFromSample) * 1000 / RATE));
                if (listenPeakRms > 0) t.put("micDb", Math.round(dbfs(listenPeakRms)));
            }
            if (vadSpeechSamples > 0) t.put("vadMs", vadSpeechSamples * 1000 / RATE);
            if (loudSamples > 0) t.put("loudMs", loudSamples * 1000 / RATE);
            // Media state: playing at turn start, whether the daemon paused it for the turn, which
            // player (short package token) and how long the pause took to confirm (-1: it was not).
            if (turnMedia) t.put("media", true);
            if (cabinDuckTurn) t.put("mediaDucked", true);
            if (mediaPaused) t.put("mediaPaused", true);
            String pkg = mediaPaused ? MediaSessionClient.shortPackage(mediaPkg) : null;
            if (pkg != null) t.put("mediaPkg", pkg);
            if (pauseMs != MediaTurnPolicy.PAUSE_NONE) t.put("pauseMs", pauseMs);
            if (turnText != null && turnIntents == null) t.put("text", turnText);
            if (turnIntentDetail != null) t.put("intentDetail", turnIntentDetail);
            if (speechEndAt > 0) t.put("speechEndAt", speechEndAt);
            if (firstDispatchAt > 0) t.put("firstDispatchAt", firstDispatchAt);
            if (firstReplyAt > 0) t.put("firstReplyAt", firstReplyAt);
            synchronized (diagLock) {
                lastTurnInfo = t;
                if (turnLog.size() >= MAX_QUEUED_TURNS) turnLog.pollFirst();
                turnLog.addLast(t);
            }
        } catch (Exception ignored) {
        }
    }

    /** Exact-zero chunks mean a muted or policy-silenced capture, not a quiet cabin. */
    private void noteMic(float rms) {
        synchronized (diagLock) {
            micChunks++;
            if (rms < 1f) micSilentChunks++;
            if (rms > micPeakRms) micPeakRms = rms;
        }
    }

    private final class SessionEvents implements VoiceSessionClient.Listener {
        private final int gen;

        SessionEvents(int gen) {
            this.gen = gen;
        }

        private void deliver(Runnable r) {
            post(() -> {
                if (gen == sessionGeneration) r.run();
            });
        }

        @Override
        public void onConnected(String provider) {
            // OpenAI ends the user's turn itself, so its deadline starts at that report.
            deliver(() -> deadline.setProviderEndpoints("openai".equals(provider)));
        }

        @Override
        public void onLive() {
            deliver(() -> {
                long now = SystemClock.elapsedRealtime();
                lastCloudActivityAt = now;
                sessionLive = true;
                // A wait that began while the provider could not hear anything yet starts now.
                deadline.providerLive(now);
            });
        }

        @Override
        public void onUserSpeechStopped() {
            deliver(() -> {
                long now = SystemClock.elapsedRealtime();
                deadline.providerSpeechStopped(now);
                lastCloudActivityAt = now;
            });
        }

        @Override
        public void onResponding() {
            deliver(() -> {
                long now = SystemClock.elapsedRealtime();
                deadline.providerResponding(now);
                lastCloudActivityAt = now;
            });
        }

        @Override
        public void onSpeakingChanged(boolean speaking) {
            deliver(() -> {
                long now = SystemClock.elapsedRealtime();
                replySpeaking = speaking;
                lastCloudActivityAt = now;
                if (speaking && phase == CLOUD) turnReplyHeard = true;
                if (speaking) {
                    overlay.show(VoiceOrbView.STATE_SPEAKING, overlay.text(R.string.voice_overlay_speaking));
                } else {
                    mutedUntil = now + ECHO_TAIL_MS;
                    ring.skipToLatest();
                    if (turnComplete) startFollowUp();
                }
            });
        }

        @Override
        public void onTurnComplete() {
            deliver(() -> {
                turnComplete = true;
                lastCloudActivityAt = SystemClock.elapsedRealtime();
                if (!replySpeaking) startFollowUp();
            });
        }

        @Override
        public void onActionProposal(JSONObject proposal) {
            deliver(() -> onProposal(proposal));
        }

        @Override
        public void onContextRequest(JSONObject request) {
            deliver(() -> {
                // The location mode follows the location toggle, every other mode the live-status one.
                if (session != null) {
                    session.answerContext(request, config.sharesContext(request.optString("mode", "")));
                }
            });
        }

        @Override
        public void onEnded(String reason) {
            deliver(() -> onSessionEnded(reason));
        }
    }
}
