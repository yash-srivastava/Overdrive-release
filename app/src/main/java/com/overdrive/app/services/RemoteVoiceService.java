package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.overdrive.app.R;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.communication.RemoteCommunicationPolicy;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.communication.RemoteVoiceJitterBuffer;
import com.overdrive.app.communication.RemoteVoicePcmConverter;
import com.overdrive.app.communication.RemoteVoiceProtocol;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.overlay.OverlayPermissionChecker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-process receiver for browser push-to-talk PCM.
 *
 * The daemon cannot own an audible AudioTrack on this firmware, so it launches
 * this exported service and streams PCM over an authenticated, loopback-only
 * ephemeral socket. The service also owns the compact non-focusable overlay.
 */
public final class RemoteVoiceService extends Service {

    private static final String TAG = "RemoteVoiceService";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "remote_voice";
    private static final int NOTIFICATION_ID = 9120;
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int FALLBACK_OUTPUT_SAMPLE_RATE_HZ = 48_000;
    private static final int OUTPUT_ROUTE_PRIME_MS = 160;
    private static final int PLAYBACK_QUEUE_MS = 200;
    private static final int KEEPALIVE_PCM_MS = 40;
    private static final int GRACEFUL_WORKER_DRAIN_MS =
            PLAYBACK_QUEUE_MS + KEEPALIVE_PCM_MS + 100;
    private static final int MIN_GRACEFUL_OUTPUT_DRAIN_MS =
            OUTPUT_ROUTE_PRIME_MS + KEEPALIVE_PCM_MS;
    private static final int OUTPUT_DRAIN_MARGIN_MS = 40;
    private static final int MAX_GRACEFUL_OUTPUT_DRAIN_MS = 750;
    private static final float ROAD_SENSE_DUCK_FACTOR = 0.12f;
    private static final int BYD_AUDIO_DEVICE_TYPE = 1002;
    private static final int BYD_PRIORITY_ROUTE_FEATURE = 0xAA000282;
    private static final int BYD_PRIORITY_ROUTE_RESTORE_FEATURE = 0xAA000283;
    private static final int BYD_PRIORITY_OUTPUT_STREAM = resolveIntConstant(
            AudioManager.class,
            "STREAM_MUTE",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? 15 : 13);
    private static final int FLAG_NAVI_UE = resolveIntConstant(
            AudioAttributes.class, "FLAG_NAVI_UE", 131_072);
    private static final int BYD_PRIORITY_GAIN_MB = 3_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object controlWriteLock = new Object();
    private final Object audioWriteLock = new Object();

    private WindowManager windowManager;
    private AudioManager audioManager;
    private volatile Socket bridgeSocket;
    private volatile DataOutputStream controlOutput;
    private volatile AudioTrack audioTrack;
    private MediaPlayer mediaRouteKeeper;
    private LoudnessEnhancer loudnessEnhancer;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private volatile RemoteVoiceJitterBuffer playbackBuffer;
    private volatile long playbackSession = -1L;
    private volatile Thread playbackThread;
    private View overlayView;
    private TextView elapsedView;
    private ImageButton muteButton;
    private Runnable elapsedTicker;
    private volatile boolean muted;
    private volatile boolean overlaySafe;
    private volatile boolean roadSenseDucked;
    private volatile int outputLevel =
            RemoteCommunicationPolicy.DEFAULT_OUTPUT_LEVEL;
    private volatile long startedAtMs;
    private String outputChannel =
            RemoteCommunicationSettings.AUDIO_CHANNEL_MEDIA;
    private int outputStream =
            MediaPlaybackService.streamForChannel(outputChannel);
    private int audioOutputSampleRate = FALLBACK_OUTPUT_SAMPLE_RATE_HZ;
    private int audioPrimeBytes;
    private int audioOutputDrainMs = MIN_GRACEFUL_OUTPUT_DRAIN_MS;
    private byte[] keepalivePcmBuffer = new byte[0];
    private byte[] outputPcmBuffer = new byte[0];
    private long keepaliveBytes;
    private long keepaliveChunks;
    private boolean firstPcmLogged;
    private boolean audioPlaybackStarted;
    private String playbackReadyDiagnostic = "";
    private boolean audioFocusGranted;
    private volatile boolean audioFocusDucked;
    private volatile boolean audioFocusMuted;
    private boolean bydPriorityRouteActive;

    private final PlaybackDuckCoordinator.Target roadSenseDuckTarget = ducked -> {
        roadSenseDucked = ducked;
        mainHandler.post(() -> {
            applyTrackVolume();
            updateOverlayVisibility();
        });
    };

    public static void startSpeakerTest(Context context) {
        Intent intent = new Intent(context, RemoteVoiceService.class)
                .putExtra("action", "test");
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stopNow(Context context) {
        try { context.stopService(new Intent(context, RemoteVoiceService.class)); }
        catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        startForegroundCompat();
        MediaPlaybackService.attachRoadSenseDuckTarget(roadSenseDuckTarget);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = stringExtra(intent, "action", "");
        if ("test".equals(action)) {
            startTestTone();
            return START_NOT_STICKY;
        }
        if (!"start".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = parseInt(stringExtra(intent, "port", ""), -1);
        String token = stringExtra(intent, "token", "");
        int level = RemoteCommunicationPolicy.clampOutputLevel(
                parseInt(stringExtra(intent, "outputLevel", ""),
                        RemoteCommunicationPolicy.DEFAULT_OUTPUT_LEVEL));
        if (port <= 0 || port > 65_535 || token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startRemoteSession(port, token, level);
        return START_NOT_STICKY;
    }

    private synchronized void startRemoteSession(
            int port, String token, int level) {
        long session = generation.incrementAndGet();
        stopSessionResources();
        removeOverlay();
        running.set(true);
        muted = false;
        overlaySafe = false;
        outputLevel = level;
        startedAtMs = System.currentTimeMillis();
        firstPcmLogged = false;

        Thread worker = new Thread(
                () -> runRemoteSession(session, port, token),
                "RemoteVoiceReceiver");
        worker.setDaemon(true);
        worker.start();
    }

    private void runRemoteSession(long session, int port, String token) {
        String failure = null;
        boolean drainAudio = false;
        Socket socket = null;
        boolean socketAttached = false;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port),
                    CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            DataOutputStream sessionControlOutput =
                    new DataOutputStream(socket.getOutputStream());
            if (!attachBridgeSocket(
                    session, socket, sessionControlOutput)) {
                return;
            }
            socketAttached = true;
            DataInputStream input = new DataInputStream(socket.getInputStream());

            RemoteCommunicationSettings.Snapshot settings =
                    RemoteCommunicationSettings.load();
            selectOutputChannel(settings.audioChannel);
            if (settings.emergencyDisabled) {
                failure = "Remote communication is emergency-disabled in the car";
            } else if (!settings.voiceEnabled) {
                failure = "Remote voice is disabled in the car settings";
            } else if (!OverlayPermissionChecker.isGranted(this)) {
                failure = "Display-over-other-apps permission is not granted in the car";
            }

            if (failure == null) {
                try {
                    if (!prepareRemoteAudio(session)) return;
                    if (!showOverlayBlocking(session)) {
                        failure = "The remote voice overlay could not be displayed";
                    }
                } catch (Throwable setupError) {
                    logger.warn(
                            "Remote voice setup failed: " + setupError.getMessage());
                    failure = "The car audio output could not start";
                }
            }

            if (!running.get() || generation.get() != session) return;
            sendHandshake(
                    sessionControlOutput,
                    token,
                    failure == null ? "READY" : "ERROR",
                    failure == null ? "" : failure);
            if (failure != null) return;
            sendDiagnostic(playbackReadyDiagnostic);

            while (running.get() && generation.get() == session) {
                RemoteVoiceProtocol.Packet packet = RemoteVoiceProtocol.read(input);
                if (!running.get() || generation.get() != session) break;
                if (packet == null) break;
                if (packet.type == RemoteVoiceProtocol.TYPE_END) {
                    if (packet.payload.length > 1) {
                        throw new IllegalStateException(
                                "Malformed remote voice end frame");
                    }
                    drainAudio = packet.payload.length == 1
                            && packet.payload[0] != 0;
                    break;
                }
                if (packet.type == RemoteVoiceProtocol.TYPE_OVERLAY_SAFE) {
                    if (packet.payload.length != 1) {
                        throw new IllegalStateException("Malformed overlay safety frame");
                    }
                    if (!applyOverlaySafety(
                            session, packet.payload[0] != 0)) {
                        break;
                    }
                    continue;
                }
                if (packet.type != RemoteVoiceProtocol.TYPE_PCM) continue;
                if (packet.payload.length == 0 || (packet.payload.length & 1) != 0) {
                    throw new IllegalStateException("Malformed PCM frame");
                }
                if (!muted) {
                    enqueueRemotePcm(session, packet.payload);
                }
            }
        } catch (Throwable error) {
            if (running.get() && generation.get() == session) {
                logger.warn(
                        "Remote voice session ended: " + error.getMessage());
            }
        } finally {
            if (!socketAttached && socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
            finishSession(session, drainAudio);
        }
    }

    private synchronized boolean attachBridgeSocket(
            long session,
            Socket socket,
            DataOutputStream sessionControlOutput) {
        if (!running.get() || generation.get() != session) return false;
        bridgeSocket = socket;
        controlOutput = sessionControlOutput;
        return true;
    }

    private synchronized boolean prepareRemoteAudio(long session) {
        if (!running.get() || generation.get() != session) return false;
        initStreamingAudio();
        if (!running.get() || generation.get() != session) return false;
        startAudioPlayback();
        if (!running.get() || generation.get() != session) return false;
        startPlaybackWorker(session);
        return true;
    }

    private synchronized boolean prepareTestAudio(long session) {
        if (!running.get() || generation.get() != session) return false;
        initStreamingAudio();
        if (!running.get() || generation.get() != session) return false;
        startAudioPlayback();
        return running.get() && generation.get() == session;
    }

    private void selectOutputChannel(String channel) {
        outputChannel = channel;
        outputStream = MediaPlaybackService.streamForChannel(channel);
    }

    private void initStreamingAudio() {
        if (!activateBydPriorityRoute()) {
            requestAudioFocus();
            startMediaRouteKeeper();
        }
        int outputRate = preferredOutputSampleRate();
        int minBuffer = AudioTrack.getMinBufferSize(
                outputRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("AudioTrack buffer unavailable");
        }
        int bytesPerSecond = outputRate * 2 * 2;
        int targetPrimeBytes =
                bytesPerSecond * OUTPUT_ROUTE_PRIME_MS / 1_000;
        int bufferBytes = Math.max(
                Math.max(minBuffer * 4, 32 * 1024),
                targetPrimeBytes);
        bufferBytes += (4 - bufferBytes % 4) % 4;
        audioPrimeBytes = Math.min(
                bufferBytes,
                Math.max(minBuffer, targetPrimeBytes));
        audioPrimeBytes -= audioPrimeBytes % 4;
        AudioAttributes.Builder attributesBuilder =
                new AudioAttributes.Builder().setLegacyStreamType(outputStream);
        if (bydPriorityRouteActive) {
            attributesBuilder.setFlags(FLAG_NAVI_UE);
        }
        AudioAttributes attributes = attributesBuilder.build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        audioTrack = new AudioTrack(
                attributes,
                format,
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack did not initialize");
        }
        attachBydLoudnessEnhancer();
        audioOutputSampleRate = audioTrack.getSampleRate();
        if (audioOutputSampleRate <= 0) audioOutputSampleRate = outputRate;
        audioOutputDrainMs = calculateOutputDrainMs(
                audioTrack, bufferBytes, audioOutputSampleRate);
        int keepaliveBytesPerSecond = audioOutputSampleRate * 2 * 2;
        int keepaliveBytes =
                keepaliveBytesPerSecond * KEEPALIVE_PCM_MS / 1_000;
        keepaliveBytes -= keepaliveBytes % 4;
        keepalivePcmBuffer = new byte[Math.max(4, keepaliveBytes)];
        this.keepaliveBytes = 0L;
        keepaliveChunks = 0L;
    }

    private int preferredOutputSampleRate() {
        try {
            int nativeRate =
                    AudioTrack.getNativeOutputSampleRate(outputStream);
            if (nativeRate >= 8_000 && nativeRate <= 192_000) return nativeRate;
        } catch (Throwable ignored) {}
        return FALLBACK_OUTPUT_SAMPLE_RATE_HZ;
    }

    private static int calculateOutputDrainMs(
            AudioTrack track, int requestedBufferBytes, int sampleRate) {
        int bufferFrames = Math.max(1, requestedBufferBytes / 4);
        try {
            int actualFrames = track.getBufferSizeInFrames();
            if (actualFrames > 0) bufferFrames = actualFrames;
        } catch (Throwable ignored) {}
        long durationMs =
                (bufferFrames * 1_000L + sampleRate - 1L) / sampleRate;
        long withMargin = durationMs + OUTPUT_DRAIN_MARGIN_MS;
        return (int) Math.max(
                MIN_GRACEFUL_OUTPUT_DRAIN_MS,
                Math.min(MAX_GRACEFUL_OUTPUT_DRAIN_MS, withMargin));
    }

    private void startAudioPlayback() {
        AudioTrack track = audioTrack;
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack is unavailable");
        }
        applyTrackVolume();
        int primedBytes = primeAudioTrack(track);
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            throw new IllegalStateException("AudioTrack did not enter playback state");
        }
        audioPlaybackStarted = true;
        int currentVolume = -1;
        int maximumVolume = -1;
        if (audioManager != null) {
            try {
                currentVolume =
                        audioManager.getStreamVolume(outputStream);
                maximumVolume =
                        audioManager.getStreamMaxVolume(outputStream);
            } catch (Throwable ignored) {}
        }
        playbackReadyDiagnostic =
                "playback-ready routeVolume=" + currentVolume + "/"
                        + maximumVolume + " trackGain=" + outputLevel
                        + "% channel=" + outputChannel
                        + " stream=" + outputStream
                        + " format=" + audioOutputSampleRate
                        + "Hz-stereo primeBytes=" + primedBytes
                        + " routeKeeper=" + (mediaRouteKeeper != null)
                        + " bydPriority=" + bydPriorityRouteActive
                        + " loudness=" + (loudnessEnhancer != null)
                        + " playState=" + track.getPlayState();
        logger.info("Remote voice " + playbackReadyDiagnostic);
    }

    private boolean activateBydPriorityRoute() {
        int result = BydDeviceHelper.callManagerSetInt(
                BydDeviceHelper.withBydPermissionBypass(this),
                BYD_AUDIO_DEVICE_TYPE,
                BYD_PRIORITY_ROUTE_FEATURE,
                1);
        if (result != 0) {
            logger.warn(
                    "Remote voice BYD priority route refused (code="
                            + result + "); using configured audio channel");
            return false;
        }
        bydPriorityRouteActive = true;
        outputStream = BYD_PRIORITY_OUTPUT_STREAM;
        logger.info(
                "Remote voice BYD priority route enabled (stream="
                        + outputStream + ", flag=" + FLAG_NAVI_UE + ")");
        return true;
    }

    private void attachBydLoudnessEnhancer() {
        if (!bydPriorityRouteActive || audioTrack == null) return;
        LoudnessEnhancer enhancer = null;
        try {
            enhancer = new LoudnessEnhancer(audioTrack.getAudioSessionId());
            enhancer.setEnabled(true);
            enhancer.setTargetGain(BYD_PRIORITY_GAIN_MB);
            loudnessEnhancer = enhancer;
        } catch (Throwable error) {
            if (enhancer != null) {
                try { enhancer.release(); } catch (Throwable ignored) {}
            }
            logger.warn(
                    "Remote voice BYD loudness enhancer unavailable: "
                            + error.getMessage());
        }
    }

    private void releaseBydPriorityRoute() {
        if (!bydPriorityRouteActive) return;
        bydPriorityRouteActive = false;
        Context bydContext = BydDeviceHelper.withBydPermissionBypass(this);
        int disableResult = BydDeviceHelper.callManagerSetInt(
                bydContext,
                BYD_AUDIO_DEVICE_TYPE,
                BYD_PRIORITY_ROUTE_FEATURE,
                0);
        int restoreResult = BydDeviceHelper.callManagerSetInt(
                bydContext,
                BYD_AUDIO_DEVICE_TYPE,
                BYD_PRIORITY_ROUTE_RESTORE_FEATURE,
                1);
        logger.info(
                "Remote voice BYD priority route released (disable="
                        + disableResult + ", restore=" + restoreResult + ")");
    }

    private void startMediaRouteKeeper() {
        releaseMediaRouteKeeper();
        MediaPlayer keeper = null;
        try {
            keeper = new MediaPlayer();
            MediaPlaybackService.applyChannelRouting(keeper, outputChannel);
            keeper.setDataSource(
                    this,
                    Uri.parse("android.resource://" + getPackageName()
                            + "/" + R.raw.roadsense_chime_minor));
            keeper.setLooping(true);
            keeper.setVolume(0f, 0f);
            keeper.prepare();
            keeper.start();
            mediaRouteKeeper = keeper;
            logger.info("Remote voice media route keeper started");
        } catch (Throwable error) {
            logger.warn(
                    "Remote voice media route keeper unavailable: "
                            + error.getMessage());
            if (keeper != null) {
                try { keeper.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void releaseMediaRouteKeeper() {
        MediaPlayer keeper = mediaRouteKeeper;
        mediaRouteKeeper = null;
        if (keeper == null) return;
        try {
            if (keeper.isPlaying()) keeper.stop();
        } catch (Throwable ignored) {}
        try { keeper.release(); } catch (Throwable ignored) {}
    }

    private int primeAudioTrack(AudioTrack track) {
        if (audioPrimeBytes <= 0) return 0;
        byte[] silence = new byte[audioPrimeBytes];
        int offset = 0;
        while (offset < silence.length) {
            int requested = silence.length - offset;
            int written = track.write(
                    silence, offset, requested, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException(
                        "AudioTrack prime failed: " + written);
            }
            if (written == 0) break;
            offset += written;
            if (written < requested) break;
        }
        if (offset == 0) {
            throw new IllegalStateException("AudioTrack could not be primed");
        }
        return offset;
    }

    private void startPlaybackWorker(long session) {
        int maxQueuedBytes =
                RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ
                        * 2 * PLAYBACK_QUEUE_MS / 1_000;
        maxQueuedBytes -= maxQueuedBytes % 2;
        RemoteVoiceJitterBuffer buffer =
                new RemoteVoiceJitterBuffer(maxQueuedBytes);
        playbackSession = session;
        playbackBuffer = buffer;

        Thread worker = new Thread(
                () -> runPlaybackWorker(session, buffer),
                "RemoteVoicePlayback");
        worker.setDaemon(true);
        playbackThread = worker;
        worker.start();
    }

    private void runPlaybackWorker(
            long session, RemoteVoiceJitterBuffer buffer) {
        try {
            while (running.get()
                    && generation.get() == session
                    && !buffer.isClosed()) {
                byte[] pcm = buffer.poll(KEEPALIVE_PCM_MS);
                if (!running.get()
                        || generation.get() != session
                        || buffer.isClosed()) {
                    break;
                }
                if (pcm != null) {
                    writeRemotePcm(session, pcm);
                    continue;
                }
                if (buffer.isFinished()) break;
                if (!running.get()
                        || generation.get() != session
                        || buffer.isClosed()) {
                    break;
                }
                writeKeepalivePcm(session);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (running.get() && generation.get() == session) {
                failPlaybackSession(session, error);
            }
        }
    }

    private synchronized void failPlaybackSession(
            long session, Throwable error) {
        if (!running.get() || generation.get() != session) return;
        logger.warn(
                "Remote voice playback worker failed: "
                        + error.getMessage());
        running.set(false);
        closeBridgeSocket();
    }

    private synchronized void enqueueRemotePcm(
            long session, byte[] monoPcm) {
        RemoteVoiceJitterBuffer buffer = playbackBuffer;
        if (!running.get()
                || generation.get() != session
                || playbackSession != session
                || buffer == null
                || !buffer.offer(monoPcm)) {
            throw new IllegalStateException(
                    "Remote voice playback queue is unavailable");
        }
    }

    private synchronized boolean applyOverlaySafety(
            long session, boolean safe) {
        if (!running.get() || generation.get() != session) return false;
        overlaySafe = safe;
        mainHandler.post(() -> {
            if (generation.get() == session && running.get()) {
                updateOverlayVisibility();
            }
        });
        return true;
    }

    private void writeRemotePcm(long session, byte[] monoPcm) {
        synchronized (audioWriteLock) {
            if (!running.get() || generation.get() != session) return;
            AudioTrack track = audioTrack;
            if (track == null) {
                throw new IllegalStateException(
                        "AudioTrack disappeared during playback");
            }
            int required = RemoteVoicePcmConverter.requiredStereoBytes(
                    monoPcm.length,
                    RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ,
                    audioOutputSampleRate);
            if (outputPcmBuffer.length < required) {
                outputPcmBuffer = new byte[required];
            }
            int converted = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                    monoPcm,
                    monoPcm.length,
                    RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ,
                    audioOutputSampleRate,
                    outputPcmBuffer);
            writeFully(track, outputPcmBuffer, converted);
            if (!firstPcmLogged) {
                firstPcmLogged = true;
                int peak = RemoteVoicePcmConverter.peakAbsoluteSample(
                        monoPcm, monoPcm.length);
                logger.info("First remote PCM written (inputBytes="
                        + monoPcm.length + ", outputBytes=" + converted
                        + ", inputPeak=" + peak + ")");
                sendDiagnostic(
                        "first-pcm inputBytes=" + monoPcm.length
                                + " outputBytes=" + converted
                                + " peak=" + peak);
                if (peak < 64) {
                    logger.warn("Remote microphone PCM is effectively silent");
                }
            }
        }
    }

    private void writeKeepalivePcm(long session) {
        synchronized (audioWriteLock) {
            if (!running.get() || generation.get() != session) return;
            AudioTrack track = audioTrack;
            if (track == null) {
                throw new IllegalStateException(
                        "AudioTrack disappeared during playback");
            }
            writeFully(
                    track, keepalivePcmBuffer, keepalivePcmBuffer.length);
            keepaliveBytes += keepalivePcmBuffer.length;
            keepaliveChunks++;
        }
    }

    private static void writeFully(
            AudioTrack track, byte[] pcm, int byteCount) {
        int offset = 0;
        int zeroWrites = 0;
        while (offset < byteCount) {
            int written = track.write(
                    pcm, offset, byteCount - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException(
                        "AudioTrack write failed: " + written);
            }
            if (written == 0) {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING
                        || ++zeroWrites >= 3) {
                    throw new IllegalStateException(
                            "AudioTrack stopped accepting PCM");
                }
                Thread.yield();
                continue;
            }
            offset += written;
            zeroWrites = 0;
        }
    }

    private boolean showOverlayBlocking(long session) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean shown = new AtomicBoolean(false);
        mainHandler.post(() -> {
            if (generation.get() == session && running.get()) {
                shown.set(showOverlay());
            }
            latch.countDown();
        });
        try {
            return latch.await(2, TimeUnit.SECONDS) && shown.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean showOverlay() {
        removeOverlay();
        Context context = themedContext();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int horizontal = dp(context, 14);
        root.setPadding(horizontal, dp(context, 9), dp(context, 8), dp(context, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202422);
        background.setCornerRadius(dp(context, 8));
        background.setStroke(dp(context, 1), 0x665DDBB6);
        root.setBackground(background);
        root.setVisibility(View.GONE);

        ImageView speaker = new ImageView(context);
        speaker.setImageResource(R.drawable.ic_volume_on);
        speaker.setColorFilter(Color.WHITE);
        root.addView(speaker, new LinearLayout.LayoutParams(
                dp(context, 22), dp(context, 22)));

        TextView label = new TextView(context);
        label.setText(R.string.remote_voice_overlay_title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(context, 10);
        root.addView(label, labelParams);

        elapsedView = new TextView(context);
        elapsedView.setText("00:00");
        elapsedView.setTextColor(0xFFC6CEC9);
        elapsedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        elapsedView.setGravity(Gravity.END);
        elapsedView.setMinWidth(dp(context, 54));
        LinearLayout.LayoutParams elapsedParams = new LinearLayout.LayoutParams(
                dp(context, 54), ViewGroup.LayoutParams.WRAP_CONTENT);
        elapsedParams.leftMargin = dp(context, 18);
        root.addView(elapsedView, elapsedParams);

        muteButton = new ImageButton(context);
        muteButton.setImageResource(R.drawable.ic_volume_on);
        muteButton.setColorFilter(Color.WHITE);
        muteButton.setContentDescription(
                getString(R.string.remote_voice_mute));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            muteButton.setTooltipText(getString(R.string.remote_voice_mute));
        }
        muteButton.setBackgroundColor(Color.TRANSPARENT);
        muteButton.setPadding(dp(context, 8), dp(context, 8),
                dp(context, 8), dp(context, 8));
        muteButton.setOnClickListener(v -> toggleMute());
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 40));
        muteParams.leftMargin = dp(context, 8);
        root.addView(muteButton, muteParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(context, 24);

        try {
            windowManager.addView(root, params);
            overlayView = root;
            startElapsedTicker();
            return true;
        } catch (Throwable error) {
            logger.warn(
                    "Could not add remote voice overlay: " + error.getMessage());
            overlayView = null;
            return false;
        }
    }

    private void toggleMute() {
        muted = !muted;
        if (muteButton != null) {
            muteButton.setImageResource(
                    muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            muteButton.setContentDescription(getString(
                    muted ? R.string.remote_voice_unmute : R.string.remote_voice_mute));
        }
        applyTrackVolume();
        sendControl("MUTE:" + (muted ? "1" : "0"));
    }

    private void sendHandshake(
            DataOutputStream sessionControlOutput,
            String token,
            String status,
            String reason) {
        synchronized (controlWriteLock) {
            try {
                sessionControlOutput.writeUTF(token);
                sessionControlOutput.writeUTF(status);
                sessionControlOutput.writeUTF(reason == null ? "" : reason);
                sessionControlOutput.flush();
            } catch (Throwable error) {
                logger.warn(
                        "Could not send receiver handshake: " + error.getMessage());
            }
        }
    }

    private void sendControl(String value) {
        synchronized (controlWriteLock) {
            try {
                if (controlOutput != null) {
                    controlOutput.writeUTF(value);
                    controlOutput.flush();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void sendDiagnostic(String value) {
        if (value == null || value.isEmpty()) return;
        sendControl("DIAG:" + value);
    }

    private void updateOverlayVisibility() {
        if (overlayView == null) return;
        boolean show = running.get() && overlaySafe && !roadSenseDucked;
        overlayView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startElapsedTicker() {
        stopElapsedTicker();
        elapsedTicker = new Runnable() {
            @Override public void run() {
                if (!running.get() || elapsedView == null) return;
                long seconds = Math.max(
                        0L, (System.currentTimeMillis() - startedAtMs) / 1000L);
                elapsedView.setText(String.format(
                        Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L));
                mainHandler.postDelayed(this, 1_000L);
            }
        };
        mainHandler.post(elapsedTicker);
    }

    private void stopElapsedTicker() {
        if (elapsedTicker != null) {
            mainHandler.removeCallbacks(elapsedTicker);
            elapsedTicker = null;
        }
    }

    private void applyTrackVolume() {
        AudioTrack track = audioTrack;
        if (track == null) return;
        float volume = muted || audioFocusMuted
                ? 0f : outputLevel / 100f;
        if (roadSenseDucked || audioFocusDucked) {
            volume *= ROAD_SENSE_DUCK_FACTOR;
        }
        try { track.setVolume(volume); } catch (Throwable ignored) {}
    }

    private void requestAudioFocus() {
        audioFocusGranted = false;
        audioFocusDucked = false;
        audioFocusMuted = false;
        if (audioManager == null) {
            logger.warn("Remote voice audio focus unavailable: no AudioManager");
            return;
        }
        long focusSession = generation.get();
        focusListener =
                change -> handleAudioFocusChange(focusSession, change);
        try {
            int result = audioManager.requestAudioFocus(
                    focusListener,
                    outputStream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            audioFocusGranted =
                    result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!audioFocusGranted) {
                logger.warn(
                        "Remote voice audio focus rejected (result="
                                + result + "); continuing without focus");
                focusListener = null;
            }
        } catch (Throwable error) {
            logger.warn(
                    "Remote voice audio focus request failed: "
                            + error.getMessage()
                            + "; continuing without focus");
            focusListener = null;
        }
    }

    private synchronized void handleAudioFocusChange(
            long focusSession, int change) {
        if (!running.get() || generation.get() != focusSession) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            logger.warn("Remote voice stopped after permanent audio focus loss");
            running.set(false);
            closeBridgeSocket();
            return;
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            audioFocusMuted = true;
            audioFocusDucked = false;
        } else if (change
                == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            audioFocusMuted = false;
            audioFocusDucked = true;
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            audioFocusMuted = false;
            audioFocusDucked = false;
        } else {
            return;
        }
        mainHandler.post(() -> {
            if (generation.get() == focusSession && running.get()) {
                applyTrackVolume();
            }
        });
    }

    private void abandonAudioFocus() {
        if (audioFocusGranted
                && audioManager != null
                && focusListener != null) {
            try { audioManager.abandonAudioFocus(focusListener); }
            catch (Throwable ignored) {}
        }
        audioFocusGranted = false;
        audioFocusDucked = false;
        audioFocusMuted = false;
        focusListener = null;
    }

    private synchronized void startTestTone() {
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        if (running.get()) return;
        if (settings.emergencyDisabled || !settings.voiceEnabled) {
            stopSelf();
            return;
        }
        selectOutputChannel(settings.audioChannel);
        long session = generation.incrementAndGet();
        stopSessionResources();
        removeOverlay();
        running.set(true);
        outputLevel = RemoteCommunicationPolicy.effectiveOutputLevel(
                settings.outputLevelOverrideEnabled, settings.outputLevel);
        firstPcmLogged = false;
        Thread test = new Thread(() -> {
            try {
                if (!prepareTestAudio(session)) return;
                int sampleCount =
                        RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ * 7 / 10;
                byte[] pcm = new byte[sampleCount * 2];
                for (int i = 0; i < sampleCount; i++) {
                    double hz = i < sampleCount / 2 ? 660.0 : 880.0;
                    short sample = (short) (Math.sin(
                            2.0 * Math.PI * hz * i
                                    / RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ)
                            * 9_000.0);
                    pcm[i * 2] = (byte) (sample & 0xFF);
                    pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
                }
                writeRemotePcm(session, pcm);
                Thread.sleep(audioOutputDrainMs);
            } catch (Throwable error) {
                logger.warn("Speaker test failed: " + error.getMessage());
            } finally {
                finishSession(session, false);
            }
        }, "RemoteVoiceTest");
        test.setDaemon(true);
        test.start();
    }

    private void finishSession(long session, boolean drainAudio) {
        if (generation.get() != session) return;
        if (drainAudio) {
            mainHandler.post(() -> {
                if (generation.get() == session) removeOverlay();
            });
            drainPlayback(session);
        }
        final long completedGeneration;
        synchronized (this) {
            if (generation.get() != session) return;
            completedGeneration = session + 1L;
            generation.set(completedGeneration);
            running.set(false);
            stopSessionResources();
        }
        mainHandler.post(() -> {
            if (generation.get() != completedGeneration || running.get()) return;
            removeOverlay();
            stopSelf();
        });
    }

    private void drainPlayback(long session) {
        RemoteVoiceJitterBuffer buffer = playbackBuffer;
        if (buffer == null) return;
        buffer.finish();

        Thread worker = playbackThread;
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(GRACEFUL_WORKER_DRAIN_MS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (worker != null && worker.isAlive()) {
            buffer.close();
            worker.interrupt();
            return;
        }
        if (generation.get() != session || !running.get()) return;
        try {
            Thread.sleep(audioOutputDrainMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void stopSessionResources() {
        running.set(false);
        closeBridgeSocket();
        RemoteVoiceJitterBuffer buffer = playbackBuffer;
        if (buffer != null) buffer.close();

        AudioTrack track = audioTrack;
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
        }

        Thread worker = playbackThread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try { worker.join(500L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        playbackThread = null;
        playbackBuffer = null;
        playbackSession = -1L;
        RemoteVoiceJitterBuffer.Stats bufferStats =
                buffer == null ? null : buffer.snapshot();

        synchronized (audioWriteLock) {
            audioTrack = null;
            LoudnessEnhancer enhancer = loudnessEnhancer;
            loudnessEnhancer = null;
            outputPcmBuffer = new byte[0];
            keepalivePcmBuffer = new byte[0];
            if (track != null) {
                if (audioPlaybackStarted) {
                    try {
                        long denominator =
                                Math.max(1L, audioOutputSampleRate * 2L * 2L);
                        long keepaliveMs =
                                keepaliveBytes * 1_000L / denominator;
                        logger.info("Remote voice playback ended (underruns="
                                + track.getUnderrunCount()
                                + ", keepaliveMs=" + keepaliveMs
                                + ", keepaliveChunks=" + keepaliveChunks
                                + ", droppedFrames="
                                + (bufferStats == null
                                        ? 0L : bufferStats.droppedFrames)
                                + ", droppedBytes="
                                + (bufferStats == null
                                        ? 0L : bufferStats.droppedBytes)
                                + ")");
                    } catch (Throwable ignored) {}
                }
                try { track.flush(); } catch (Throwable ignored) {}
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            if (enhancer != null) {
                try { enhancer.release(); } catch (Throwable ignored) {}
            }
            audioPlaybackStarted = false;
            playbackReadyDiagnostic = "";
            audioPrimeBytes = 0;
            audioOutputDrainMs = MIN_GRACEFUL_OUTPUT_DRAIN_MS;
            keepaliveBytes = 0L;
            keepaliveChunks = 0L;
        }
        releaseMediaRouteKeeper();
        abandonAudioFocus();
        releaseBydPriorityRoute();
    }

    private void closeBridgeSocket() {
        try { if (bridgeSocket != null) bridgeSocket.close(); }
        catch (Throwable ignored) {}
        bridgeSocket = null;
        controlOutput = null;
    }

    private void removeOverlay() {
        stopElapsedTicker();
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
        }
        overlayView = null;
        elapsedView = null;
        muteButton = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_channel_remote_voice), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.remote_voice_notification_title))
                .setContentText(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.remote_voice_notification_text))
                .setSmallIcon(R.drawable.ic_volume_on)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable error) {
            try { startForeground(NOTIFICATION_ID, notification); }
            catch (Throwable ignored) {}
        }
    }

    private Context themedContext() {
        try {
            int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            int night;
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                night = android.content.res.Configuration.UI_MODE_NIGHT_YES;
            } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                night = android.content.res.Configuration.UI_MODE_NIGHT_NO;
            } else {
                return this;
            }
            android.content.res.Configuration config =
                    new android.content.res.Configuration(
                            getResources().getConfiguration());
            config.uiMode = (config.uiMode
                    & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | night;
            return createConfigurationContext(config);
        } catch (Throwable ignored) {
            return this;
        }
    }

    private static String stringExtra(Intent intent, String key, String fallback) {
        try {
            Object value = intent.getExtras() == null
                    ? null : intent.getExtras().get(key);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int resolveIntConstant(
            Class<?> owner, String fieldName, int fallback) {
        try { return owner.getField(fieldName).getInt(null); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics());
    }

    @Override public void onDestroy() {
        synchronized (this) {
            generation.incrementAndGet();
            running.set(false);
            stopSessionResources();
        }
        removeOverlay();
        MediaPlaybackService.detachRoadSenseDuckTarget(roadSenseDuckTarget);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
