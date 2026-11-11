package com.overdrive.app.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-process owner of cabin-mic capture. A single process-wide instance
 * serves recording and remote-listener demand so two AudioRecord clients can
 * never contend for the cabin microphone.
 *
 * <h3>Why app process</h3>
 * The daemon runs as UID 2000 (shell). BYD AOSP's AudioPolicy denies
 * AudioRecord for that UID — verified via {@code /api/audio/probe-mic}
 * and {@code /api/audio/probe-mic-spoof}. The app process runs under
 * its own app UID with RECORD_AUDIO granted, so capture lives here
 * and the encoded AAC bitstream is shipped to the daemon over a TCP
 * socket on 127.0.0.1:19878. Same physical mic, different speaker.
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   AudioRecord (48kHz, or Di+ compatible 8kHz while listening)
 *      ├→ MediaCodec AAC-LC encoder (recording demand only)
 *      └→ PCM listener frames (listener demand only)
 *      → DataOutputStream length-prefixed frames to AacIngestServer
 * </pre>
 * Recording mode uses two threads:
 *   - capture thread: reads PCM, queues to encoder input
 *   - drain thread: pulls AAC frames, ships over TCP
 * Listener-only mode uses just the capture thread and does not create an AAC
 * encoder.
 *
 * <h3>PTS</h3>
 * Each emitted AAC frame is stamped from a SAMPLE-COUNT clock: AAC-LC is a
 * fixed 1024 samples/frame, so frame N's PTS is
 * {@code base + N*1024*1e6/SAMPLE_RATE} µs — a perfectly even,
 * strictly-monotonic cadence. {@code base} is captured once from
 * {@code System.nanoTime()/1000} at the first frame, so audio shares the
 * video PTS clock domain (the GL pipeline feeds {@code System.nanoTime()} to
 * {@code eglPresentationTimeANDROID}); the daemon's
 * {@code HardwareEventRecorderGpu.writeRebasedAudio} rebases both against one
 * {@code ptsOriginUs}. Stamping from sample count rather than reading
 * wall-clock at drain time is what keeps playback smooth: drain-thread
 * scheduling jitter (GC, encoder/Adreno contention, TCP stalls) used to make
 * consecutive frames' timestamps bunch or spread, which played back choppy and
 * tripped the daemon's monotonic-drop guard. A forward-only resync re-anchors
 * the cadence clock if {@code captureLoop} drops PCM under backpressure, so
 * audio-vs-video drift stays bounded. {@code info.presentationTimeUs} is
 * ignored — some BYD AOSP AAC encoders rewrite it to start at 0.
 */
public class AppAudioCaptureController {

    private static final String TAG = "AudioCapture";
    private static final Object OWNER_LOCK = new Object();
    private static AppAudioCaptureController owner;
    private static boolean recordingDemand;
    private static boolean listenerDemand;
    private static boolean listenerDiPlusCompatibility;
    private static com.overdrive.app.voice.audio.PcmSink wakeSink;
    // Microphone input wanted for the shared AudioRecord (voice assistant setting, experimental).
    // Changing it restarts a wake-only capture at once; while recording or the remote listener
    // own the capture it is applied on their next reopen instead, so neither is interrupted.
    private static volatile int requestedAudioSource = MediaRecorder.AudioSource.MIC;
    // Source of the capture currently open, or -1: read lock-free by the voice status report, so
    // a capture restart (socket connect, AudioRecord init) never stalls the engine thread.
    private static volatile int openAudioSource = -1;
    // A non-MIC input the head unit refused (its AudioRecord would not initialise, start or
    // deliver a first chunk while plain MIC opened fine), or -1. Captures open with MIC instead
    // until the setting changes: the shared capture also feeds dashcam audio, the remote listener
    // and the wake path, so a refused experimental input must never keep all of them down in a
    // retry loop, and a wake-only capture must not flap between the two sources every tick.
    private static volatile int failedAudioSource = -1;
    /** Not a MediaRecorder source: the car's four-channel input, served to the wake path by {@link CarArrayCapture}. */
    static final int CAR_ARRAY_SOURCE = -100;
    // Written under OWNER_LOCK, read lock-free by the status reports.
    private static volatile CarArrayCapture arrayCapture;
    // Effect probe of the open capture, for /api/voice/status: null until a session asked, and
    // null again once a capture without effects opens.
    private static volatile Boolean aecAvailable;
    private static volatile Boolean aecEnabled;

    // Capture parameters. Matched to the daemon's expected MediaFormat
    // in HardwareEventRecorderGpu.maybeAddAudioTrack — change here
    // means the daemon CSD-0 lookup needs the new value.
    private static final int SAMPLE_RATE = 48000;
    private static final int LISTENER_CAPTURE_RATE = 8000;
    private static final int WAKE_CAPTURE_RATE = 16000;
    private static final int CHANNEL_COUNT = 1;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AAC_BITRATE = 64000;
    private static final int BYTES_PER_SAMPLE = 2; // PCM_16BIT mono

    // PCM read chunk: 40 ms at 48 kHz mono PCM_16 = 1920 samples = 3840 B.
    // Matches a typical AAC frame's 1024-sample budget closely so the
    // encoder isn't starved or flooded.
    private static final int PCM_CHUNK_SAMPLES = 1920;
    private static final int PCM_CHUNK_BYTES = PCM_CHUNK_SAMPLES * BYTES_PER_SAMPLE;

    // Daemon ingest endpoint — keep in sync with AacIngestServer.PORT.
    private static final String INGEST_HOST = "127.0.0.1";
    private static final int INGEST_PORT = 19878;

    // Wire format constants — keep in sync with AacIngestServer.
    private static final int MSG_CONFIG = 1;
    private static final int MSG_DATA = 2;
    private static final int MSG_PCM = 3;
    private static final int MSG_LISTENER_ONLY = 4;

    // Drain-loop bail-out: after running=false, allow this many
    // consecutive INFO_TRY_AGAIN_LATER timeouts before exiting so we
    // don't hang forever waiting for an EOS that the encoder may
    // never emit (e.g. it's already in error state).
    private static final int MAX_POST_STOP_TIMEOUTS = 5;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean listenerEnabled = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private final boolean recordForMuxing;
    private final int captureSampleRate;
    private final boolean hasIngest;
    private final int audioSource;
    private volatile com.overdrive.app.voice.audio.PcmSink pcmSink;
    private boolean loggedSinkFailure = false;
    private boolean loggedDeferredSource = false;
    // True when this capture's AudioRecord itself failed: it would not initialise or record, or it
    // died before delivering a first chunk. On a non-MIC source that is the "head unit refused
    // this input" signal behind failedAudioSource; a socket or encoder failure, a back-off skip or
    // a mic claimed mid-stream never counts.
    private volatile boolean recordFailed;
    // Capture thread only: set once the first chunk arrived, so a later read failure is a mic
    // claim (today's 5 s back-off) rather than a refused input.
    private boolean deliveredPcm;

    private ExecutorService executor;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private MediaCodec aacEncoder;
    private Socket socket;
    private DataOutputStream out;
    private byte[] normalizedPcmBuffer;
    // FIX H3: per-frame scratch buffer for AAC packet copies. AAC frames are
    // typically <1500 bytes (4-8 ms × 64 kbps mono ≈ 32-64 bytes for data,
    // plus ADTS header). Allocating a fresh byte[info.size] per packet at
    // ~50 Hz (20 ms AAC frames) churned ~3 KB/s through GC. Grows on the
    // rare oversized frame, never shrinks.
    private byte[] drainScratch;

    // ---- Sample-accurate audio PTS (drain thread only) ----------------------
    // AAC-LC emits a FIXED 1024 samples per frame; at SAMPLE_RATE that is a
    // constant 21.333 ms. We stamp each frame at that exact cadence instead of
    // reading wall-clock at drain time. The old drain-time System.nanoTime()
    // stamp inherited the drain thread's scheduling jitter (GC pauses, encoder /
    // Adreno contention, TCP write stalls): consecutive frames that are really
    // 21.3 ms apart got timestamps bunched together or spread out, so the muxed
    // audio track played back choppy, and any frame whose jittered pts landed
    // <= the previous one was DROPPED by the daemon's per-track monotonic guard
    // (writeRebasedAudio) — an audible gap. A sample-count clock is perfectly
    // even and strictly monotonic, eliminating both.
    //
    // Anchored ONCE to System.nanoTime()/1000 at the first emitted frame so the
    // audio timeline shares the same clock domain as the video PTS
    // (eglPresentationTimeANDROID uses System.nanoTime()), letting the daemon
    // rebase A/V against one origin.
    private static final int AAC_SAMPLES_PER_FRAME = 1024;
    private long audioPtsBaseUs = -1L;   // nanoTime/1000 captured at first frame
    private long emittedSamples = 0L;    // running total of samples emitted
    // If the even-cadence clock falls behind real wall-clock by more than this
    // — which happens when captureLoop drops PCM chunks under encoder
    // backpressure, so fewer AAC frames are emitted than real time elapsed —
    // re-anchor the cadence clock FORWARD to real time. This inserts a gap that
    // matches the real dropped-audio gap, bounding audio-vs-video drift to this
    // window while keeping spacing perfectly even in the common no-drop case.
    // Forward-only, so PTS stays strictly monotonic.
    private static final long AUDIO_PTS_RESYNC_THRESHOLD_US = 120_000L; // 120 ms
    // Mic-claim contention back-off. BYD voice-asst can grab the mic
    // mid-trip; AudioRecord then throws ERROR_INVALID_OPERATION on read.
    // We close + retry every 5 s instead of busy-looping or giving up.
    //
    // STATIC because the process-wide demand owner creates a fresh controller
    // on every retry when capture isn't running —
    // an instance-scoped back-off timer would reset to 0 every retry,
    // collapsing the 5 s gate into a 1 s fast-poll loop (~30 attempts in
    // 30 s while the mic is claimed). Process-scoped is correct: there's
    // exactly one user / one process / one mic so a single back-off clock
    // shared across ephemeral controllers is the desired behaviour.
    private static volatile long lastFailureNanos = 0;
    private static final long RETRY_BACKOFF_NANOS = 5_000_000_000L;
    // Per-instance flag so the "in back-off, skipping" log fires at most
    // once per controller. The static lastFailureNanos is shared, but each
    // recreated controller deserves one log line so the user can see why
    // start() returned false without spamming logcat at 1 Hz.
    private boolean loggedBackoffSkip = false;

    private AppAudioCaptureController(
            boolean recordForMuxing,
            boolean listenerEnabled,
            int captureSampleRate,
            boolean hasIngest,
            int audioSource) {
        this.recordForMuxing = recordForMuxing;
        this.listenerEnabled.set(listenerEnabled);
        this.captureSampleRate = captureSampleRate;
        this.hasIngest = hasIngest;
        this.audioSource = audioSource;
    }

    /**
     * {@link MediaRecorder.AudioSource} for a {@code VoiceAssistantConfig.micSource} value;
     * anything unknown is plain MIC, today's behaviour.
     */
    public static int audioSourceFor(String micSource) {
        if ("voice_recognition".equals(micSource)) return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        if ("voice_communication".equals(micSource)) return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        if ("car_array".equals(micSource)) return CAR_ARRAY_SOURCE;
        return MediaRecorder.AudioSource.MIC;
    }

    /** Inverse of {@link #audioSourceFor} for status reports. */
    public static String micSourceName(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "voice_recognition";
        if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "voice_communication";
        if (audioSource == CAR_ARRAY_SOURCE) return "car_array";
        return "mic";
    }

    /** Only VOICE_COMMUNICATION gets the platform echo canceller and noise suppressor attached. */
    static boolean wantsVoiceEffects(int audioSource) {
        return audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    }

    /**
     * The input a capture opens with: the requested one, or plain MIC once the head unit refused
     * exactly that input. A different request is always tried.
     */
    static int effectiveAudioSource(int requested, int failed) {
        return requested == failed ? MediaRecorder.AudioSource.MIC : requested;
    }

    private static int effectiveAudioSource() {
        return effectiveAudioSource(requestedAudioSource, failedAudioSource);
    }

    /**
     * Whether a capture that did not come up means the head unit refused its input: only when the
     * AudioRecord itself failed on a non-MIC source. The owner then opens plain MIC for the same
     * demands instead of retrying the refused input after every back-off. A failing MIC is the
     * microphone itself being claimed or denied, which keeps today's back-off.
     */
    static boolean sourceRefused(int source, boolean recordFailed) {
        return recordFailed && source != MediaRecorder.AudioSource.MIC;
    }

    /**
     * Sets the microphone input every capture opens with and reconciles: a wake-only capture
     * on another source is reopened now; a capture that recording or the remote listener own
     * keeps running and takes the new source on its next reopen (logged once).
     */
    public static boolean setCaptureSource(String micSource) {
        synchronized (OWNER_LOCK) {
            int source = audioSourceFor(micSource);
            if (source == requestedAudioSource) return true;
            requestedAudioSource = source;
            // A new request gets a fresh try, even of an input that was refused earlier.
            failedAudioSource = -1;
            return reconcileOwnerLocked();
        }
    }

    /**
     * The source the open capture uses (what the wake path hears), or the one the next capture
     * opens with when none is open: the requested source, or plain MIC after the head unit
     * refused it ({@link #micSourceFailed}). Lock-free: the voice engine reports it from its
     * audio thread.
     */
    public static String activeMicSource() {
        CarArrayCapture array = arrayCapture;
        if (array != null && array.isRunning()) return micSourceName(CAR_ARRAY_SOURCE);
        int open = openAudioSource;
        return micSourceName(open >= 0 ? open : effectiveAudioSource());
    }

    /**
     * True while the requested microphone input was refused by the head unit, so captures open
     * with plain MIC instead; false again as soon as the setting changes. Lock-free.
     */
    public static boolean micSourceFailed() {
        int failed = failedAudioSource;
        // -1 is "none"; the car array is a negative pseudo-source, so test for the sentinel itself.
        return failed != -1 && failed == requestedAudioSource;
    }

    /** Echo-canceller probe of the newest capture: null until a VOICE_COMMUNICATION capture asked. */
    public static Boolean echoCancelerAvailable() {
        return aecAvailable;
    }

    public static Boolean echoCancelerEnabled() {
        return aecEnabled;
    }

    /**
     * Recording and listener callers share this owner. Changing listener
     * demand never restarts a healthy recording capture; changing recording
     * demand preserves the existing start/stop boundary so stale AAC config
     * cannot leak into video-only recordings.
     */
    public static boolean setRecordingDemand(boolean wanted) {
        synchronized (OWNER_LOCK) {
            recordingDemand = wanted;
            return reconcileOwnerLocked();
        }
    }

    public static boolean setListenerDemand(boolean wanted) {
        return setListenerDemand(wanted, false);
    }

    public static boolean setListenerDemand(
            boolean wanted, boolean diPlusCompatibility) {
        synchronized (OWNER_LOCK) {
            listenerDemand = wanted;
            listenerDiPlusCompatibility =
                    wanted && diPlusCompatibility;
            return reconcileOwnerLocked();
        }
    }

    public static boolean isRecordingCaptureRunning() {
        synchronized (OWNER_LOCK) {
            return recordingDemand
                    && owner != null
                    && owner.recordForMuxing
                    && owner.isRunning();
        }
    }

    /**
     * Voice wake demand: a non-null sink receives every captured chunk at the capture
     * rate. Wake-only capture runs at 16 kHz with no ingest socket; null removes the demand.
     */
    public static boolean setWakeSink(com.overdrive.app.voice.audio.PcmSink sink) {
        synchronized (OWNER_LOCK) {
            wakeSink = sink;
            return reconcileOwnerLocked();
        }
    }

    public static boolean isWakeCaptureRunning() {
        synchronized (OWNER_LOCK) {
            if (wakeSink == null) return false;
            if (arrayCapture != null && arrayCapture.isRunning()) return true;
            return owner != null && owner.isRunning();
        }
    }

    /** A debug field capture is recording the car's input (see CarArrayCapture). Lock-free. */
    public static boolean fieldCaptureRunning() {
        CarArrayCapture array = arrayCapture;
        return array != null && array.capturing();
    }

    /** Per-channel levels of the car's four-channel input (mics, then references), or null. */
    public static int[] carArrayChannelsDb() {
        CarArrayCapture array = arrayCapture;
        return array == null ? null : array.channelsDb();
    }

    /**
     * Serves the wake sink from the car's four-channel input while it is requested and not
     * refused; true while it does. A refusal or a capture that died falls back to the shared
     * capture on plain MIC until the setting changes, as for any refused input.
     */
    private static boolean reconcileArrayLocked() {
        boolean wanted = wakeSink != null && effectiveAudioSource() == CAR_ARRAY_SOURCE;
        CarArrayCapture current = arrayCapture;
        if (current != null && (!wanted || current.sink() != wakeSink || !current.isRunning())) {
            boolean died = current.failed();
            current.stop();
            arrayCapture = null;
            if (died && wanted) {
                failedAudioSource = CAR_ARRAY_SOURCE;
                Log.w(TAG, "Car microphones stopped delivering — capturing with mic until the setting changes");
                return false;
            }
            current = null;
        }
        if (!wanted) return false;
        if (current != null) return true;
        CarArrayCapture next = new CarArrayCapture(wakeSink);
        if (!next.start()) {
            failedAudioSource = CAR_ARRAY_SOURCE;
            Log.w(TAG, "Car microphones refused by the head unit — capturing with mic until the setting changes");
            return false;
        }
        arrayCapture = next;
        return true;
    }

    private static boolean reconcileOwnerLocked() {
        boolean array = reconcileArrayLocked();
        // With the car's four-channel input the shared capture serves only recording and the
        // remote listener, on plain MIC.
        com.overdrive.app.voice.audio.PcmSink ownerSink = array ? null : wakeSink;
        if (!recordingDemand && !listenerDemand) {
            if (ownerSink == null) {
                stopOwnerLocked();
                return true;
            }
        }

        AppAudioCaptureController current = owner;
        boolean needsIngest = recordingDemand || listenerDemand;
        // Recording quality takes priority over the remote listener's low-bandwidth
        // navigation compatibility mode. Never capture at 8 kHz while a recording
        // is active: repeating those samples to 48 kHz cannot restore lost detail.
        int desiredCaptureRate = recordingDemand
                ? SAMPLE_RATE
                : (listenerDemand && listenerDiPlusCompatibility
                        ? LISTENER_CAPTURE_RATE
                        : (needsIngest ? SAMPLE_RATE : WAKE_CAPTURE_RATE));
        // The requested input, or plain MIC once the head unit refused it (failedAudioSource):
        // a capture that already fell back to MIC is therefore not stale and is kept.
        int source = effectiveAudioSource();
        if (source == CAR_ARRAY_SOURCE) source = MediaRecorder.AudioSource.MIC;
        if (current != null
                && current.isRunning()
                && current.recordForMuxing == recordingDemand
                && current.captureSampleRate == desiredCaptureRate
                && current.hasIngest == needsIngest) {
            // A changed mic source reopens only a wake-only capture; recording and the remote
            // listener are never interrupted for it and pick it up on their next reopen.
            boolean sourceStale = current.audioSource != source;
            if (!sourceStale || needsIngest) {
                if (sourceStale && !current.loggedDeferredSource) {
                    current.loggedDeferredSource = true;
                    Log.i(TAG, "Mic source " + micSourceName(source)
                            + " deferred until the recording/listener capture reopens (now "
                            + micSourceName(current.audioSource) + ")");
                }
                current.listenerEnabled.set(listenerDemand);
                current.pcmSink = ownerSink;
                return true;
            }
        }

        // A capture on this input that died before its first chunk already showed the head unit
        // refuses it; otherwise the input gets its try here.
        boolean refused = current != null && !current.isRunning()
                && current.audioSource == source && sourceRefused(source, current.recordFailed);
        stopOwnerLocked();
        if (!refused) {
            AppAudioCaptureController replacement =
                    newCaptureLocked(needsIngest, desiredCaptureRate, source, ownerSink);
            if (replacement.start()) {
                owner = replacement;
                return true;
            }
            if (!sourceRefused(source, replacement.recordFailed)) return false;
        }
        // Fall back to plain MIC for the same demands right away: the back-off was armed by the
        // refused input, not by the microphone. If MIC fails too, the microphone itself is
        // unavailable (claimed or denied) — today's back-off stays armed and the next retry asks
        // for the requested input again rather than blaming it. Once MIC is up the refused input
        // is remembered (logged once per request) so the owner does not flap back to it.
        lastFailureNanos = 0L;
        AppAudioCaptureController fallback =
                newCaptureLocked(needsIngest, desiredCaptureRate, MediaRecorder.AudioSource.MIC, ownerSink);
        if (!fallback.start()) return false;
        failedAudioSource = source;
        Log.w(TAG, "Mic source " + micSourceName(source)
                + " refused by the head unit — capturing with mic until the setting changes");
        owner = fallback;
        return true;
    }

    private static AppAudioCaptureController newCaptureLocked(boolean needsIngest, int desiredCaptureRate,
            int source, com.overdrive.app.voice.audio.PcmSink sink) {
        AppAudioCaptureController capture =
                new AppAudioCaptureController(
                        recordingDemand, listenerDemand, desiredCaptureRate, needsIngest, source);
        capture.pcmSink = sink;
        return capture;
    }

    private static void stopOwnerLocked() {
        AppAudioCaptureController current = owner;
        owner = null;
        if (current != null) current.stop();
    }

    private boolean start() {
        // Fast-path: only one start() succeeds; concurrent starts noop.
        if (!started.compareAndSet(false, true)) {
            return running.get();
        }
        if (System.nanoTime() - lastFailureNanos < RETRY_BACKOFF_NANOS) {
            // In back-off after a recent failure — let the caller
            // retry on its next poll cycle. Log once per instance so
            // the user (or field log) sees the fast-fail path being
            // exercised; Log.d (not Log.w) keeps it quiet in normal
            // logcat filters while still being visible at -v debug.
            if (!loggedBackoffSkip) {
                Log.d(TAG, "Audio capture in back-off — skipping start");
                loggedBackoffSkip = true;
            }
            started.set(false);
            return false;
        }
        // Per-step diagnostics. Without these the start path is opaque —
        // a SecurityException/IllegalStateException with a null message
        // gives Log.e nothing to print, so the user sees "Audio capture
        // start failed:" with no cause and no way to triage which phase
        // (socket / AudioRecord / encoder) actually broke.
        String currentStep = "init";
        try {
            // Long blocking I/O (socket connect, AudioRecord init,
            // encoder configure) happens OUTSIDE the lifecycleLock so
            // a concurrent stop() isn't blocked by the 2 s connect.
            if (hasIngest) {
                currentStep = "ingest-socket";
                initIngestSocket();      // opens TCP, declares recording/listener mode
            }
            currentStep = "audio-record";
            initAudioRecord();       // opens AudioRecord (may throw on perm denial)
            if (recordForMuxing) {
                currentStep = "encoder";
                initEncoder();       // sets up MediaCodec AAC-LC encoder
            }

            synchronized (lifecycleLock) {
                running.set(true);
                // Reset the sample-accurate PTS clock for this session. The
                // drain thread captures audioPtsBaseUs from the first frame; a
                // stale base from a prior session would back-date the new
                // session's timeline. emittedSamples is read/written only on
                // the single drain thread after this point.
                audioPtsBaseUs = -1L;
                emittedSamples = 0L;
                executor = Executors.newFixedThreadPool(
                        recordForMuxing ? 2 : 1, r -> {
                    Thread t = new Thread(r);
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                    return t;
                });
                executor.submit(() -> {
                    Thread.currentThread().setName("AudioCapture");
                    captureLoop();
                });
                if (recordForMuxing) {
                    executor.submit(() -> {
                        Thread.currentThread().setName("AudioDrain");
                        drainLoop();
                    });
                }
            }

            openAudioSource = audioSource;
            if (!wantsVoiceEffects(audioSource)) {
                // The probe describes the open capture; this one has no effects attached, so a
                // stale "aecEnabled" from an earlier VOICE_COMMUNICATION capture must not linger.
                aecAvailable = null;
                aecEnabled = null;
            }
            Log.i(TAG, "Audio capture started (recording=" + recordForMuxing
                    + ", listener=" + listenerEnabled.get()
                    + ", captureRate=" + captureSampleRate
                    + ", source=" + micSourceName(audioSource)
                    + (pcmSink != null ? ", wake=true" : "") + ")");
            return true;
        } catch (Throwable t) {
            // Log the FULL stack — getMessage() is null for many of the
            // exceptions we hit (SecurityException, IllegalStateException
            // from AudioFlinger denial, etc.). Pass the Throwable as the
            // 3rd arg so logcat prints the trace.
            Log.e(TAG, "Audio capture start failed at step=" + currentStep
                + " cls=" + t.getClass().getSimpleName()
                + " msg=" + t.getMessage(), t);
            // Only the AudioRecord step can mean the input itself was refused (see sourceRefused).
            recordFailed = "audio-record".equals(currentStep);
            lastFailureNanos = System.nanoTime();
            running.set(false);
            cleanup();
            started.set(false);
            return false;
        }
    }

    private void stop() {
        if (!started.compareAndSet(true, false)) return;
        running.set(false);
        openAudioSource = -1;
        Log.i(TAG, "Audio capture stopping");
        cleanup();
        Log.i(TAG, "Audio capture stopped");
    }

    private boolean isRunning() {
        // `running` flips false the instant a worker self-exits OR stop()
        // is called. We deliberately surface "started but not running" as
        // !isRunning so the StatusOverlayService poll naturally calls
        // stop() to clean up and then retries start(). Without this, the
        // started CAS gate would silently reject every retry until a
        // gear/mode change forced an explicit stop() somewhere else.
        return running.get();
    }

    // ==================== INIT ====================

    private void initIngestSocket() throws IOException {
        socket = new Socket();
        // 2 s connect timeout. The daemon listens on the same host so
        // anything longer is a hung daemon — better to fail fast and
        // back off than block start() for the user.
        socket.connect(new InetSocketAddress(INGEST_HOST, INGEST_PORT), 2000);
        socket.setTcpNoDelay(true);
        out = new DataOutputStream(socket.getOutputStream());

        if (!recordForMuxing) {
            // Explicitly clear any cached AAC config left by a recording
            // client that this listener-only connection replaced.
            out.writeInt(4);
            out.writeInt(MSG_LISTENER_ONLY);
            out.flush();
            return;
        }

        // Send CONFIG immediately. The CSD-0 for 48 kHz mono AAC-LC is
        // {0x11, 0x88} per ISO/IEC 14496-3 §1.6.2.1:
        //   audioObjectType (5 bits) = 2 (AAC LC)
        //   samplingFrequencyIndex (4 bits) = 3 (48 kHz)
        //   channelConfiguration (4 bits) = 1 (mono)
        //   GASpecificConfig (3 bits) = 0
        // → 00010 0011 0001 000 = 0001 0001 1000 1000 = 0x11 0x88
        byte[] csd0 = new byte[] { 0x11, (byte) 0x88 };

        // CONFIG payload: [sampleRate][channels][bitrate][csd0Length][csd0]
        // Total payload bytes = 16 + csd0.length = 18.
        // Total frame = 4 (msgType) + 18 = 22.
        int payloadLength = 16 + csd0.length;
        out.writeInt(4 + payloadLength);   // totalLength
        out.writeInt(MSG_CONFIG);
        out.writeInt(SAMPLE_RATE);
        out.writeInt(CHANNEL_COUNT);
        out.writeInt(AAC_BITRATE);
        out.writeInt(csd0.length);
        out.write(csd0);
        out.flush();
    }

    @SuppressLint("MissingPermission")
    private void initAudioRecord() {
        // AudioRecord remains the authoritative permission/policy check because
        // BYD's audio policy can deny an otherwise granted app UID at runtime.
        int minBuf = AudioRecord.getMinBufferSize(
                captureSampleRate, CHANNEL_CONFIG_IN, PCM_FORMAT);
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            throw new IllegalStateException("AudioRecord.getMinBufferSize=" + minBuf);
        }
        // 4× minBuf gives ~80 ms of headroom — enough to absorb a
        // capture-thread stall without dropping samples, but not so
        // much that a transient pause shows up as audible latency on
        // subsequent recordings.
        int bufSize = Math.max(minBuf * 4, captureSampleRate / 5);
        // audioSource is MediaRecorder.AudioSource.MIC unless the voice assistant asked for
        // VOICE_RECOGNITION or VOICE_COMMUNICATION (see audioSourceFor).
        audioRecord = new AudioRecord(audioSource,
            captureSampleRate, CHANNEL_CONFIG_IN, PCM_FORMAT, bufSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            int state = audioRecord.getState();
            audioRecord.release();
            audioRecord = null;
            throw new IllegalStateException(
                "AudioRecord state=" + state + " (mic claimed or denied)");
        }
        if (wantsVoiceEffects(audioSource)) attachVoiceEffects(audioRecord.getAudioSessionId());
        audioRecord.startRecording();
        // Poll for RECORDING state up to 200ms — some BYD AOSP builds return
        // from startRecording() before AudioFlinger has actually flipped the
        // session state, and a single immediate check trips a false negative.
        int rs = audioRecord.getRecordingState();
        long deadline = System.nanoTime() + 200_000_000L;
        while (rs != AudioRecord.RECORDSTATE_RECORDING && System.nanoTime() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            rs = audioRecord.getRecordingState();
        }
        if (rs != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release();
            audioRecord = null;
            throw new IllegalStateException(
                "AudioRecord recordingState=" + rs + " — mic likely claimed by another client");
        }
    }

    /**
     * Attaches the platform echo canceller and noise suppressor to a VOICE_COMMUNICATION
     * session when the head unit offers them; the only path to hearing the wake word over music
     * without a playback reference. Availability and the enable result are logged once per
     * capture and kept for /api/voice/status. A failing effect never fails the capture.
     */
    private void attachVoiceEffects(int sessionId) {
        boolean available = false;
        boolean enabled = false;
        try {
            available = AcousticEchoCanceler.isAvailable();
            if (available) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) {
                    int result = echoCanceler.setEnabled(true);
                    enabled = result == AcousticEchoCanceler.SUCCESS && echoCanceler.getEnabled();
                    Log.i(TAG, "AcousticEchoCanceler available, setEnabled=" + result
                            + " enabled=" + enabled);
                } else {
                    Log.i(TAG, "AcousticEchoCanceler available but create() returned null");
                }
            } else {
                Log.i(TAG, "AcousticEchoCanceler not available on this head unit");
            }
        } catch (Throwable t) {
            Log.w(TAG, "AcousticEchoCanceler failed: " + t.getMessage());
        }
        aecAvailable = available;
        aecEnabled = enabled;
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                int result = noiseSuppressor == null ? -1 : noiseSuppressor.setEnabled(true);
                Log.i(TAG, "NoiseSuppressor available, setEnabled=" + result);
            } else {
                Log.i(TAG, "NoiseSuppressor not available on this head unit");
            }
        } catch (Throwable t) {
            Log.w(TAG, "NoiseSuppressor failed: " + t.getMessage());
        }
    }

    private void releaseVoiceEffects() {
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) {}
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) {}
            noiseSuppressor = null;
        }
    }

    private void initEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
        // KEY_MAX_INPUT_SIZE optional but stops the encoder allocating
        // a giant scratch buffer on devices that default to 1 MB.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES);

        aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aacEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aacEncoder.start();
    }

    // ==================== CAPTURE LOOP ====================

    private void captureLoop() {
        int captureChunkBytes =
                PCM_CHUNK_BYTES * captureSampleRate / SAMPLE_RATE;
        byte[] pcmBuf = new byte[captureChunkBytes];
        while (running.get()) {
            int n;
            try {
                n = audioRecord.read(pcmBuf, 0, captureChunkBytes);
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord.read threw: " + t.getMessage());
                lastFailureNanos = System.nanoTime();
                noteReadFailure();
                break;
            }
            if (n <= 0) {
                // Negative codes: ERROR_INVALID_OPERATION (-3) typically
                // means the mic was claimed by another client (BT call,
                // voice asst). Bail; the next poll cycle will retry
                // after the back-off elapses.
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read=" + n + " — releasing mic for retry");
                    lastFailureNanos = System.nanoTime();
                    noteReadFailure();
                    break;
                }
                continue;
            }
            deliveredPcm = true;

            com.overdrive.app.voice.audio.PcmSink sink = pcmSink;
            if (sink != null) {
                try {
                    sink.onPcm(pcmBuf, n, captureSampleRate);
                } catch (Throwable t) {
                    if (!loggedSinkFailure) {
                        loggedSinkFailure = true;
                        Log.w(TAG, "Wake sink failed: " + t.getMessage());
                    }
                }
            }
            if (!hasIngest) continue;

            byte[] consumerPcm = pcmBuf;
            int consumerLength = n;
            if (captureSampleRate != SAMPLE_RATE) {
                consumerLength = normalizePcmRate(pcmBuf, n);
                consumerPcm = normalizedPcmBuffer;
            }

            if (listenerEnabled.get()) {
                try {
                    sendListenerPcm(consumerPcm, consumerLength);
                } catch (java.net.SocketException se) {
                    Log.i(TAG, "Listener socket closed: " + se.getMessage());
                    running.set(false);
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "Listener PCM send failed: " + t.getMessage());
                    running.set(false);
                    break;
                }
            }

            if (!recordForMuxing) continue;

            // PTS handling: the input PTS is irrelevant here — the drain loop
            // assigns each output frame a PTS from a sample-count clock (see
            // the field doc on audioPtsBaseUs / the drainLoop stamp). We pass 0
            // to queueInputBuffer because (a) some BYD AOSP AAC encoders rewrite
            // input PTSs anyway, and (b) the output PTS is derived from the
            // emitted-frame count, not from this value.

            try {
                int inputIndex = aacEncoder.dequeueInputBuffer(20_000);
                if (inputIndex >= 0) {
                    ByteBuffer inBuf = aacEncoder.getInputBuffer(inputIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(consumerPcm, 0, consumerLength);
                        aacEncoder.queueInputBuffer(
                                inputIndex, 0, consumerLength, 0L, 0);
                    }
                }
                // dequeueInputBuffer returning negative just means the
                // encoder is busy — drop this PCM chunk rather than
                // block indefinitely. With 20 ms chunks at 64 kbps the
                // encoder catches up within milliseconds.
            } catch (IllegalStateException ise) {
                Log.w(TAG, "Encoder queue failed: " + ise.getMessage());
                running.set(false);
                break;
            }
        }
        // EOS is signalled from cleanup() (after capture-thread join),
        // not here — queueing EOS while the encoder may already be
        // in mid-shutdown races with stop()/release() and throws
        // IllegalStateException intermittently.
    }

    /**
     * A read that failed before the first chunk arrived means the input never delivered audio
     * (refused by the head unit on a non-MIC source, see {@link #sourceRefused}); a stop() in
     * progress has already cleared {@code running} and never counts. Flagged before
     * {@code running} flips so the owner sees both together.
     */
    private void noteReadFailure() {
        if (!deliveredPcm && running.get()) recordFailed = true;
        running.set(false);
    }

    private int normalizePcmRate(byte[] pcm, int length) {
        int inputLength = length & ~1;
        int repeat = SAMPLE_RATE / captureSampleRate;
        int required = inputLength * repeat;
        if (normalizedPcmBuffer == null
                || normalizedPcmBuffer.length < required) {
            normalizedPcmBuffer = new byte[required];
        }
        // ponytail: zero-order hold is enough for intelligible 8 kHz speech;
        // use a band-limited resampler only if cabin-listener fidelity matters.
        int output = 0;
        for (int input = 0; input < inputLength; input += 2) {
            byte low = pcm[input];
            byte high = pcm[input + 1];
            for (int copy = 0; copy < repeat; copy++) {
                normalizedPcmBuffer[output++] = low;
                normalizedPcmBuffer[output++] = high;
            }
        }
        return output;
    }

    private void sendListenerPcm(byte[] pcm, int length) throws IOException {
        // Frame format: [4 totalLength][4 msgType][N PCM bytes].
        // The daemon fan-out is bounded and lossy, so this loopback write
        // never waits on a slow browser.
        synchronized (this) {
            DataOutputStream o = out;
            if (o == null) throw new IOException("ingest socket unavailable");
            o.writeInt(4 + length);
            o.writeInt(MSG_PCM);
            o.write(pcm, 0, length);
        }
    }

    // ==================== DRAIN LOOP ====================

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int postStopTimeouts = 0;
        while (true) {
            try {
                int outputIndex = aacEncoder.dequeueOutputBuffer(info, 20_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!running.get()) {
                        // Bounded wait for the EOS marker after stop().
                        // If the encoder won't emit one (error state),
                        // we exit instead of hanging the join.
                        if (++postStopTimeouts >= MAX_POST_STOP_TIMEOUTS) {
                            Log.w(TAG, "Drain: no EOS after stop, exiting");
                            break;
                        }
                    }
                    continue;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The encoder may emit its CSD as the first output
                    // event. We hand-built our own CSD-0 in CONFIG so we
                    // explicitly DO NOT forward this one to the daemon —
                    // doing so would corrupt the AAC track. Just log and
                    // skip so a future BYD ROM negotiating a different
                    // sample rate (e.g. 44.1 kHz) is visible in logs.
                    Log.i(TAG, "AAC encoder output format: " + aacEncoder.getOutputFormat());
                    continue;
                } else if (outputIndex < 0) {
                    continue;
                }

                // Got a real output buffer — reset the post-stop timeout
                // counter so spurious INFO_TRY_AGAIN_LATERs interleaved
                // with real packets don't trip the bail-out early.
                postStopTimeouts = 0;

                ByteBuffer outBuf = aacEncoder.getOutputBuffer(outputIndex);
                if (outBuf == null || info.size <= 0) {
                    aacEncoder.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                    continue;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Same skip rationale as INFO_OUTPUT_FORMAT_CHANGED.
                    aacEncoder.releaseOutputBuffer(outputIndex, false);
                    // EOS may legally arrive in the same packet as CODEC_CONFIG
                    // per the MediaCodec spec (rare in practice). The bare
                    // `continue` below would skip the EOS break further down
                    // the loop body, leaving the drain hung until the
                    // post-stop timeout fires (MAX_POST_STOP_TIMEOUTS × 20 ms).
                    // Honour the EOS bit here too.
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                    continue;
                }

                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);

                // Sample-accurate PTS. AAC-LC = AAC_SAMPLES_PER_FRAME (1024)
                // samples/frame = a constant 21.333 ms at SAMPLE_RATE, so we
                // advance a sample counter rather than read wall-clock here.
                // This makes frame spacing perfectly even and strictly
                // monotonic, which is what fixes the choppy-audio + dropped-
                // frame symptoms (see the field doc on audioPtsBaseUs). The
                // base is captured once from System.nanoTime()/1000 so audio
                // shares the video clock domain; thereafter PTS is pure
                // arithmetic. We DELIBERATELY ignore info.presentationTimeUs —
                // some BYD AOSP AAC encoders rewrite it to start at 0.
                long nowUs = System.nanoTime() / 1000L;
                if (audioPtsBaseUs < 0) {
                    audioPtsBaseUs = nowUs;
                    emittedSamples = 0L;
                }
                long ptsUs = audioPtsBaseUs
                    + (emittedSamples * 1_000_000L) / SAMPLE_RATE;
                // Drift guard: if captureLoop dropped PCM chunks under encoder
                // backpressure, fewer frames were emitted than real time
                // elapsed, so the cadence clock lags wall-clock. When the lag
                // exceeds the threshold, jump the base FORWARD to real time so
                // the audio gap matches the real dropped-audio gap (bounded
                // drift) — forward-only keeps PTS monotonic.
                long realElapsedUs = nowUs - audioPtsBaseUs;
                long cadenceElapsedUs = (emittedSamples * 1_000_000L) / SAMPLE_RATE;
                if (realElapsedUs - cadenceElapsedUs > AUDIO_PTS_RESYNC_THRESHOLD_US) {
                    audioPtsBaseUs = nowUs - cadenceElapsedUs;
                    ptsUs = audioPtsBaseUs + cadenceElapsedUs;
                }
                emittedSamples += AAC_SAMPLES_PER_FRAME;

                // Frame format: [4 totalLength][4 msgType][8 ptsUs][N AAC bytes]
                // totalLength counts msgType + ptsUs + AAC = 4 + 8 + N = 12 + N
                int totalLength = 4 + 8 + info.size;
                synchronized (this) {
                    DataOutputStream o = out;
                    if (o == null) break;
                    o.writeInt(totalLength);
                    o.writeInt(MSG_DATA);
                    o.writeLong(ptsUs);
                    // FIX H3: reuse drainScratch instead of `new byte[info.size]`.
                    // AAC frames are <1500 bytes; the scratch starts at 8 KB
                    // and only grows on the rare oversize frame.
                    if (drainScratch == null || info.size > drainScratch.length) {
                        drainScratch = new byte[info.size];
                    }
                    outBuf.get(drainScratch, 0, info.size);
                    o.write(drainScratch, 0, info.size);
                    // FIX M1: drop per-frame flush(). The socket already has
                    // TCP_NODELAY set in initIngestSocket(), so the kernel
                    // emits the segment immediately on every write() — the
                    // explicit flush() was halving syscall throughput by
                    // forcing an extra round through DataOutputStream's
                    // (already empty) buffer drain at AAC frame rate
                    // (~50 Hz). Recovery from a half-shipped frame is
                    // identical with or without the flush; the receiver's
                    // length-prefixed framer handles partial sends.
                }

                aacEncoder.releaseOutputBuffer(outputIndex, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } catch (java.net.SocketException se) {
                Log.i(TAG, "Drain socket closed: " + se.getMessage());
                running.set(false);
                break;
            } catch (Throwable t) {
                Log.w(TAG, "Drain error: " + t.getMessage());
                running.set(false);
                break;
            }
        }
    }

    // ==================== CLEANUP ====================

    private void cleanup() {
        // Order matters here. Adreno + BYD AOSP audio HAL deadlocks
        // if AudioRecord is released while the capture thread is
        // mid-read; encoder stop() with pending input racing the
        // drain loop emits IllegalStateException.
        //
        // PREVIOUS ORDERING was wrong: it sent EOS, then awaitTermination,
        // then AudioRecord.stop(). But AudioRecord.read() is NOT
        // interruptible — Thread.interrupt() does not unblock it. When
        // the BYD audio HAL stalls (mic claimed by voice-asst / BT call),
        // the capture thread sits inside read() for seconds; awaitTermination
        // returns false; we then called audioRecord.stop() while the
        // capture thread was still mid-read → undefined behaviour on
        // Adreno (HAL crash, ANR, sticky mic indicator).
        //
        //   1. running=false (already set by stop())
        //   2. AudioRecord.stop() — this causes any in-flight read() to
        //      return -3 (ERROR_INVALID_OPERATION) IMMEDIATELY. The
        //      capture thread's loop then sees running=false and exits
        //      cleanly; the executor await below now actually completes.
        //   3. signal EOS to encoder so drain sees end-of-stream
        //   4. shutdownNow + awaitTermination(500ms) — drain sees EOS,
        //      capture has already exited (step 2 unblocked its read).
        //   5. AudioRecord.release()
        //   6. encoder.stop() + release()
        //   7. close socket

        // 2. AudioRecord.stop() FIRST so any blocked read() returns
        //    promptly. This is the fix for the cleanup deadlock — the
        //    inline comment in this file's earlier revision stated
        //    "stop AudioRecord first to make read() return promptly"
        //    while the code did it last. Now matches the intent.
        //
        //    Wrapped in try/catch(Throwable) because the AudioRecord
        //    may be in a state where stop() throws (already stopped,
        //    HAL session lost, etc.); we still want to proceed with
        //    encoder teardown regardless.
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Throwable ignored) {}
        }

        // 3. EOS to encoder. Must use queueInputBuffer with
        //    BUFFER_FLAG_END_OF_STREAM — signalEndOfInputStream() is
        //    surface-input only and throws IllegalStateException for
        //    byte-buffer encoders. Retry briefly because the input
        //    queue may be full from the last captureLoop iteration.
        //
        //    The capture thread (now unblocked by step 2) may still
        //    queue one final PCM chunk between the stop() above and
        //    the EOS below; that's fine — the encoder accepts both
        //    and the EOS arrives at the head of the queue eventually.
        MediaCodec enc = aacEncoder;
        if (enc != null) {
            for (int i = 0; i < 5; i++) {
                try {
                    int ix = enc.dequeueInputBuffer(10_000);
                    if (ix >= 0) {
                        enc.queueInputBuffer(ix, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }
                } catch (Throwable t) {
                    // Encoder may already be stopped/released by a
                    // failure path in start() — give up silently.
                    break;
                }
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        }

        // 4. Stop the executor; this interrupts both loops. The
        //    drain loop sees EOS; the capture loop sees running=false
        //    (and read() already returned -3 from step 2's stop()).
        //    await gives them up to 500 ms total.
        ExecutorService ex;
        synchronized (lifecycleLock) {
            ex = executor;
            executor = null;
        }
        if (ex != null) {
            ex.shutdownNow();
            try {
                ex.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // 5. AudioRecord.release() AFTER capture thread has joined.
        //    Releasing while a thread is still inside read() is the
        //    Adreno-deadlock case the ordering above is built to avoid.
        //    The session effects go first: they belong to the record's session.
        releaseVoiceEffects();
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Throwable ignored) {}
            audioRecord = null;
        }

        // 6. Encoder teardown.
        if (aacEncoder != null) {
            try { aacEncoder.stop(); } catch (Throwable ignored) {}
            try { aacEncoder.release(); } catch (Throwable ignored) {}
            aacEncoder = null;
        }

        // 7. Socket teardown.
        OutputStream o = out;
        out = null;
        if (o != null) {
            try { o.close(); } catch (Throwable ignored) {}
        }
        normalizedPcmBuffer = null;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }
}
