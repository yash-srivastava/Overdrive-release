package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.auth.AuthManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * App-process audio player for the "Play Audio" automation / key-mapping action.
 *
 * <p><b>Why this exists in the app process.</b> The daemon ({@code app_process},
 * UID 2000) cannot play a {@link MediaPlayer} — {@code prepare()} fails on this
 * firmware with {@code status=0x80000000} because the media extractor doesn't
 * service the headless process (see {@link com.overdrive.app.byd.AudioPlaybackController}).
 * A framework MediaPlayer prepares normally in the real app process, so the daemon
 * shells {@code am start-foreground-service} to reach this exported service and hands
 * it the sound to play.
 *
 * <p><b>File source.</b> A library sound arrives as {@code libName}; the app can't read
 * {@code /data/local/tmp/.overdrive/audio} directly (SELinux untrusted_app), so it
 * streams the bytes from the daemon's authenticated {@code /api/audio/library/raw}
 * endpoint (JWT cookie via {@link AuthManager}). An external-storage sound arrives as
 * {@code filePath} and is opened directly (the app can read shared storage).
 *
 * <p>Single-player, replace-on-play: a new play stops the previous one. One-shots
 * self-stop the service on completion; looping playback lives until an explicit stop
 * (service stop or the {@link #ACTION_STOP} broadcast). Uses async prepare with a real
 * Looper (the service main thread), so no blocking on the caller.
 */
public final class MediaPlaybackService extends Service {

    private static final String TAG = "MediaPlaybackService";
    private static final String CHANNEL_ID = "overdrive_media_playback";
    private static final int NOTIFICATION_ID = 9971;
    static final PlaybackDuckCoordinator ROAD_SENSE_DUCK = new PlaybackDuckCoordinator();
    public static void attachRoadSenseDuckTarget(PlaybackDuckCoordinator.Target target) {
        ROAD_SENSE_DUCK.attach(target);
    }
    public static void detachRoadSenseDuckTarget(PlaybackDuckCoordinator.Target target) {
        ROAD_SENSE_DUCK.detach(target);
    }
    /** Daemon base — same loopback the app's DaemonHttpClient uses. */
    private static final String DAEMON_BASE = "http://127.0.0.1:8080";
    /** Broadcast that stops playback (shared with the video activity + daemon stop()). */
    public static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";

    private MediaPlayer player;
    private boolean playerPrepared;
    private int latestStartId;
    private AudioManager audioManager;
    private Object audioFocusRequest; // AudioFocusRequest (API26+) or null
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean stopReceiverRegistered;
    // TextToSpeech engine, lazily initialised on the first speak request. Held for the
    // service lifetime and shut down in onDestroy. TTS init is async, so a speak request
    // that arrives before init completes is queued in pendingSpeak and flushed onInit.
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeak;
    private String pendingSpeakChannel;
    private int pendingSpeakStartId;
    private long pendingSpeakGeneration;
    private String activeSpeakText;
    private String activeSpeakChannel;
    private int activeSpeakStartId;
    private long activeSpeakGeneration;
    private boolean ttsPausedForRoadSense;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean roadSenseDucked;
    private final PlaybackDuckCoordinator.Target roadSenseDuckTarget = ducked -> {
        roadSenseDucked = ducked;
        mainHandler.post(this::applyRoadSenseDuck);
    };
    // TTS progress callbacks may arrive off the main Looper. This synchronized token makes
    // callbacks from a released player or cancelled utterance harmless when a newer request
    // has replaced it; callback cleanup is then marshalled back to the main Looper.
    private final PlaybackSessionGuard playbackSession = new PlaybackSessionGuard();

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i(TAG, "stop broadcast received");
            invalidatePlaybackSession();
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        try {
            ROAD_SENSE_DUCK.attach(roadSenseDuckTarget);
        } catch (Throwable t) {
            Log.w(TAG, "RoadSense duck coordinator attach failed: " + t.getMessage());
        }
        // NOTHING in here may throw. This service is started by an `am
        // start-foreground-service` from the daemon whenever an automation or a
        // key-mapping plays a sound, so an escaping Throwable is a user-visible
        // "OverDrive has stopped" in the REAL app process — triggered by pressing
        // a mapped button or firing an automation, with no obvious cause. Every
        // step below is optional relative to actually playing audio, so each
        // degrades independently instead of taking the process down.
        try {
            createChannel();
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed: " + t.getMessage());
        }
        startForegroundCompat();
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "AudioManager unavailable: " + t.getMessage());
        }
        try {
            registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));
            stopReceiverRegistered = true;
        } catch (Throwable t) {
            // Losing the stop receiver only costs remote-stop; playback and the
            // completion-driven stopSelf() still work.
            Log.w(TAG, "stop receiver registration failed: " + t.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // SECURITY (V60-11): exported service — in onStartCommand a binder call IS in
        // flight, so getCallingUid() is reliable here (unlike onCreate/onReceive).
        // Only our own app UID or the shell daemon (2000) may drive this service.
        final int callingUid = android.os.Binder.getCallingUid();
        final int myUid = android.os.Process.myUid();
        if (callingUid != myUid && callingUid != 2000) {
            android.util.Log.w(TAG, "Rejecting start from foreign UID " + callingUid);
            stopSelf();
            return android.app.Service.START_NOT_STICKY;
        }

        if (intent == null) return START_NOT_STICKY;
        latestStartId = startId;
        StartRequest request = readStartRequest(intent);
        if (request == null) {
            rejectStartWithoutInterruptingPlayback(startId, "malformed extras");
            return START_NOT_STICKY;
        }
        String action = request.action;
        if ("stop".equals(action)) {
            invalidatePlaybackSession();
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("speak".equals(action)) {
            String text = request.text;
            if (text == null || text.trim().isEmpty()) {
                rejectStartWithoutInterruptingPlayback(startId, "missing speech text");
                return START_NOT_STICKY;
            }
            String ch = orDefault(request.channel, "voice");
            long generation = beginPlaybackSession();
            releasePlayer();
            stopTts();
            speak(text, ch, startId, generation);
            return START_NOT_STICKY;
        }
        boolean hasLibrarySource =
                request.libName != null && !request.libName.trim().isEmpty();
        boolean hasFileSource =
                request.filePath != null && !request.filePath.trim().isEmpty();
        if (!hasLibrarySource && !hasFileSource) {
            rejectStartWithoutInterruptingPlayback(startId, "missing audio source");
            return START_NOT_STICKY;
        }
        long generation = beginPlaybackSession();
        String channel = orDefault(request.channel, "media");
        boolean loop = request.loop;
        String libName = request.libName;
        String filePath = request.filePath;

        Uri uri = null;
        Map<String, String> headers = null;
        if (hasLibrarySource) {
            // Stream from the daemon (app can't read the library dir). Authenticated.
            uri = Uri.parse(DAEMON_BASE + "/api/audio/library/raw?name=" + Uri.encode(libName));
            headers = authHeaders();
        } else if (hasFileSource) {
            uri = Uri.fromFile(new java.io.File(filePath));
        }
        // A play request replaces every prior audio mode. Invalidating the old generation
        // first means tts.stop() callbacks cannot stop this freshly started player.
        stopTts();
        startPlayback(uri, headers, channel, loop, startId, generation);
        // Not sticky: if the OS kills us mid-clip we don't silently resurrect a sound.
        return START_NOT_STICKY;
    }

    private StartRequest readStartRequest(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return new StartRequest(null, null, null, false, null, null);

            boolean hasAction = extras.containsKey("action");
            boolean hasText = extras.containsKey("text");
            boolean hasChannel = extras.containsKey("channel");
            boolean hasLoop = extras.containsKey("loop");
            boolean hasLibName = extras.containsKey("libName");
            boolean hasFilePath = extras.containsKey("filePath");
            String action = parseStringExtra(extras.get("action"), hasAction);
            String text = parseStringExtra(extras.get("text"), hasText);
            String channel = parseStringExtra(extras.get("channel"), hasChannel);
            Boolean loop = parseBooleanExtra(extras.get("loop"), hasLoop);
            String libName = parseStringExtra(extras.get("libName"), hasLibName);
            String filePath = parseStringExtra(extras.get("filePath"), hasFilePath);
            if ((hasAction && action == null)
                    || (hasText && text == null)
                    || (hasChannel && channel == null)
                    || loop == null
                    || (hasLibName && libName == null)
                    || (hasFilePath && filePath == null)) {
                return null;
            }
            return new StartRequest(action, text, channel, loop, libName, filePath);
        } catch (Throwable t) {
            // This component is exported to the shell UID. BadParcelableException and
            // adversarial extra types must reject the command, not crash the app process.
            Log.w(TAG, "could not parse playback extras: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static String parseStringExtra(Object raw, boolean present) {
        if (!present) return null;
        return raw instanceof String ? (String) raw : null;
    }

    static Boolean parseBooleanExtra(Object raw, boolean present) {
        if (!present) return Boolean.FALSE;
        return raw instanceof Boolean ? (Boolean) raw : null;
    }

    private void startPlayback(Uri uri, Map<String, String> headers,
                               String channel, boolean loop, int startId, long generation) {
        releasePlayer();
        requestFocus(channel, loop);
        // Constructor INSIDE a guard. `new MediaPlayer()` runs native_setup and
        // throws RuntimeException when mediaserver is dead or mid-restart — which
        // is reachable right after a malformed clip kills the native decoder. It
        // used to sit outside the try below, making it the only unguarded call on
        // the whole playback path, and an escape here crashes the REAL app process
        // (user sees "OverDrive has stopped" from pressing a mapped button).
        MediaPlayer mp;
        try {
            mp = new MediaPlayer();
        } catch (Throwable t) {
            Log.w(TAG, "MediaPlayer construction failed (mediaserver down?): " + t.getMessage());
            finishSession(startId, generation);
            return;
        }
        player = mp;
        playerPrepared = false;
        try {
            applyChannelRouting(mp, channel);
            if (headers != null) {
                mp.setDataSource(this, uri, headers);
            } else {
                mp.setDataSource(this, uri);
            }
            mp.setLooping(loop);
            mp.setOnPreparedListener(p -> {
                if (!isCurrentPlayer(p, generation)) return;
                playerPrepared = true;
                applyRoadSenseDuck(p);
                try { p.start(); Log.i(TAG, "playback started (loop=" + loop + ")"); }
                catch (Throwable t) {
                    Log.w(TAG, "start failed: " + t.getMessage());
                    finishPlayback(p, startId, generation);
                }
            });
            if (!loop) {
                mp.setOnCompletionListener(p -> finishPlayback(p, startId, generation));
            }
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                finishPlayback(p, startId, generation);
                return true;
            });
            // Async prepare — this service has a real Looper (main thread), so the
            // onPrepared callback fires normally (unlike the daemon's Looper-less
            // worker threads, which is exactly why playback moved here).
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "setup failed: " + t.getMessage());
            finishPlayback(mp, startId, generation);
        }
    }

    /**
     * Speak {@code text} via TextToSpeech on {@code channel}. TTS needs a real app
     * Context + Looper (the whole reason this runs in the app process, not the daemon).
     * Lazy-inits the engine; if it's still initialising, the request is stashed and
     * flushed from the init callback. The foreground notification keeps us alive for the
     * duration; we self-stop when the utterance finishes.
     */
    private void speak(String text, String channel, int startId, long generation) {
        if (text == null || text.trim().isEmpty()) {
            finishSession(startId, generation);
            return;
        }
        requestFocus(channel, false);
        if (tts != null && ttsReady) {
            speakNow(text, channel, startId, generation);
            return;
        }
        // Stash until init completes (last request wins — a newer speak supersedes).
        pendingSpeak = text;
        pendingSpeakChannel = channel;
        pendingSpeakStartId = startId;
        pendingSpeakGeneration = generation;
        if (tts == null) {
            // GUARDED. The TextToSpeech constructor synchronously resolves and
            // binds an engine, so on a ROM with no/broken TTS it can throw
            // (IllegalArgumentException / NPE / SecurityException from the package
            // resolver). speak() is called straight from onStartCommand with no
            // try/catch above it, so an escape here crashes the REAL app process —
            // the user presses a "Speak" automation or mapped button and sees
            // "OverDrive has stopped", with the foreground service leaked too.
            try {
                tts = new TextToSpeech(getApplicationContext(), status -> {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                    if (ttsReady) {
                        try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
                        String pend = pendingSpeak;
                        String pendCh = pendingSpeakChannel;
                        int pendStartId = pendingSpeakStartId;
                        long pendGeneration = pendingSpeakGeneration;
                        pendingSpeak = null;
                        if (pend != null && isCurrentSession(pendGeneration)) {
                            speakNow(pend, pendCh, pendStartId, pendGeneration);
                        }
                    } else {
                        Log.w(TAG, "TTS init failed (status=" + status + ")");
                        // Release the handle so a LATER speak retries init instead
                        // of wedging: with a non-null tts and ttsReady false, the
                        // `tts != null && ttsReady` fast path fails AND the
                        // `tts == null` init path is skipped, so every subsequent
                        // speak would silently drop for the service's lifetime.
                        int pendingStartId = pendingSpeakStartId;
                        long pendingGeneration = pendingSpeakGeneration;
                        try { tts.shutdown(); } catch (Throwable ignored) {}
                        tts = null;
                        pendingSpeak = null;
                        // A newer speak can arrive while this engine is still binding.
                        // It replaces pendingSpeak above, but it cannot create another
                        // engine until this callback releases the first one. Finish that
                        // newer request explicitly instead of leaving its foreground
                        // service alive with no TTS engine and no callback to stop it.
                        if (isCurrentSession(pendingGeneration)) {
                            finishSession(pendingStartId, pendingGeneration);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "TextToSpeech unavailable on this ROM: " + t.getMessage());
                tts = null;
                pendingSpeak = null;
                finishSession(startId, generation);
            }
        }
    }

    private void speakNow(String text, String channel, int startId, long generation) {
        if (!isCurrentSession(generation) || tts == null) return;
        activeSpeakText = text;
        activeSpeakChannel = channel;
        activeSpeakStartId = startId;
        activeSpeakGeneration = generation;
        if (roadSenseDucked) {
            ttsPausedForRoadSense = true;
            Log.i(TAG, "delaying TTS until RoadSense chime completes");
            return;
        }
        ttsPausedForRoadSense = false;
        try {
            android.os.Bundle params = new android.os.Bundle();
            // TextToSpeech can only route to a PUBLIC AudioManager stream — its
            // KEY_PARAM_STREAM has no equivalent of MediaPlayer.setAudioStreamType's
            // OEM-extended path. The default "voice" channel maps to STREAM_VOICE_OEM(16)
            // (see streamForChannel), which is NOT a valid TTS stream: passing it made the
            // utterance route to an invalid AudioTrack and produce NO sound (the reported
            // "text pronunciation doesn't work"). Clamp any OEM-extended stream to
            // STREAM_MUSIC — the same public stream the known-working AVAS TTS path uses.
            int ttsStream = streamForChannel(channel);
            if (isOemExtendedStream(ttsStream)) ttsStream = AudioManager.STREAM_MUSIC;
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, ttsStream);
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            String uttId = playbackSession.nextTtsUtteranceId();
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) {
                    finishTtsSessionFromCallback(startId, generation, uttId, id);
                }
                @Override public void onError(String id) {
                    finishTtsSessionFromCallback(startId, generation, uttId, id);
                }
            });
            // Truncate to the engine's limit. TextToSpeech.speak() REJECTS input
            // longer than getMaxSpeechInputLength() (4000) by returning ERROR
            // rather than throwing — so nothing is spoken, no utterance callback
            // ever fires, and this service holds audio focus plus its foreground
            // notification FOREVER. Truncating speaks what fits instead.
            String toSpeak = text;
            int maxLen;
            try { maxLen = TextToSpeech.getMaxSpeechInputLength(); }
            catch (Throwable t) { maxLen = 4000; }
            if (maxLen > 0 && toSpeak.length() > maxLen) {
                Log.w(TAG, "speak text " + toSpeak.length() + " chars exceeds engine max "
                        + maxLen + " — truncating");
                toSpeak = toSpeak.substring(0, maxLen);
            }
            int rc = tts.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, params, uttId);
            if (rc != TextToSpeech.SUCCESS) {
                // No utterance started ⇒ onDone/onError will never fire ⇒ nothing
                // would ever stop this service. Stop it ourselves.
                Log.w(TAG, "tts.speak returned " + rc + " — stopping service (no utterance)");
                finishSession(startId, generation);
                return;
            }
            Log.i(TAG, "speak (" + channel + "): "
                + (toSpeak.length() > 40 ? toSpeak.substring(0, 40) + "…" : toSpeak));
        } catch (Throwable t) {
            Log.w(TAG, "speak failed: " + t.getMessage());
            finishSession(startId, generation);
        }
    }

    private Map<String, String> authHeaders() {
        Map<String, String> h = new HashMap<>();
        try {
            if (AuthManager.getState() == null) AuthManager.initialize();
            String jwt = AuthManager.generateJwt();
            if (jwt != null) h.put("Cookie", "byd_session=" + jwt);
        } catch (Throwable t) {
            Log.w(TAG, "auth header build failed: " + t.getMessage());
        }
        // LOUD on empty. /api/audio/library/raw is NOT a public path — it is
        // dispatched after AuthMiddleware.checkAuth — so with no cookie the daemon
        // answers 401, MediaPlayer errors out, and playback silently does nothing
        // while every API call in the chain already reported success. That is
        // indistinguishable from "the sound file is broken" unless we say so here.
        if (h.isEmpty()) {
            Log.w(TAG, "NO AUTH COOKIE for the raw-media fetch — /api/audio/library/raw "
                    + "requires auth, so playback will 401 and silently do nothing. "
                    + "AuthManager could not mint a JWT in the app process.");
        }
        return h;
    }

    private void requestFocus(String channel, boolean loop) {
        if (audioManager == null) return;
        abandonFocus();
        try {
            int gain = loop ? AudioManager.AUDIOFOCUS_GAIN
                            : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
            focusListener = fc -> { /* best-effort; don't pause on transient loss */ };
            // Request focus with the DEPRECATED legacy form
            // requestAudioFocus(listener, streamType, gain) — passing the SAME target
            // stream the player uses. This is exactly what the OEM reference does
            // (requestAudioFocus(listener, targetStream, gain)). Building a modern
            // AudioFocusRequest with
            // AudioAttributes here would re-assert usage-based routing on the shared
            // AudioFlinger session and pull an OEM-extended-stream (nav=14/voice=16)
            // clip back onto the media amplifier — the "nav audio plays on media" bug.
            // The legacy stream-typed focus request keeps focus scoped to the same
            // stream setAudioStreamType targets, so routing stays put.
            int stream = streamForChannel(channel);
            audioManager.requestAudioFocus(focusListener, stream, gain);
            audioFocusRequest = Boolean.TRUE;
        } catch (Throwable t) {
            Log.w(TAG, "requestAudioFocus failed: " + t.getMessage());
        }
    }

    private void abandonFocus() {
        if (audioManager == null || audioFocusRequest == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest instanceof android.media.AudioFocusRequest) {
                audioManager.abandonAudioFocusRequest((android.media.AudioFocusRequest) audioFocusRequest);
            } else if (focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        } finally { audioFocusRequest = null; focusListener = null; }
    }

    private void releasePlayer() {
        playerPrepared = false;
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    private void applyRoadSenseDuck() {
        if (playerPrepared) applyRoadSenseDuck(player);
        if (roadSenseDucked) {
            pauseTtsForRoadSense();
        } else {
            resumeTtsAfterRoadSense();
        }
    }

    private void applyRoadSenseDuck(MediaPlayer target) {
        if (target == null) return;
        // -12 dB leaves automation audible but makes a short safety cue distinct.
        float volume = roadSenseDucked ? 0.25f : 1.0f;
        try {
            target.setVolume(volume, volume);
        } catch (Throwable ignored) {
        }
    }

    private void pauseTtsForRoadSense() {
        if (ttsPausedForRoadSense || activeSpeakText == null || tts == null) return;
        if (!playbackSession.hasActiveTtsUtterance(activeSpeakGeneration)) return;
        ttsPausedForRoadSense = true;
        playbackSession.invalidateTtsCallbacks();
        try {
            tts.stop();
        } catch (Throwable ignored) {
        }
    }

    private void resumeTtsAfterRoadSense() {
        if (!ttsPausedForRoadSense) return;
        String text = activeSpeakText;
        String channel = activeSpeakChannel;
        int startId = activeSpeakStartId;
        long generation = activeSpeakGeneration;
        ttsPausedForRoadSense = false;
        if (text == null || !isCurrentSession(generation)) {
            clearActiveTtsState();
            return;
        }
        if (tts == null || !ttsReady) {
            finishSession(startId, generation);
            return;
        }
        requestFocus(channel, false);
        speakNow(text, channel, startId, generation);
    }

    private void clearActiveTtsState() {
        playbackSession.invalidateTtsCallbacks();
        activeSpeakText = null;
        activeSpeakChannel = null;
        activeSpeakStartId = 0;
        activeSpeakGeneration = 0;
        ttsPausedForRoadSense = false;
    }

    private long beginPlaybackSession() {
        return playbackSession.begin();
    }

    private void invalidatePlaybackSession() {
        playbackSession.invalidate();
        pendingSpeak = null;
    }

    private boolean isCurrentSession(long generation) {
        return playbackSession.isCurrent(generation);
    }

    private boolean isCurrentPlayer(MediaPlayer expected, long generation) {
        return isCurrentSession(generation) && player == expected;
    }

    private void finishTtsSessionFromCallback(int startId, long generation, String expectedId,
                                              String callbackId) {
        if (!playbackSession.claimCurrentTtsCallback(
                generation, expectedId, callbackId)) return;
        mainHandler.post(() -> {
            if (isCurrentSession(generation)) finishSession(startId, generation);
        });
    }

    private void finishPlayback(MediaPlayer expected, int startId, long generation) {
        if (!isCurrentPlayer(expected, generation)) return;
        releasePlayer();
        stopSelfResult(latestStartId);
    }

    private void finishSession(int startId, long generation) {
        if (!isCurrentSession(generation)) return;
        clearActiveTtsState();
        releasePlayer();
        stopSelfResult(latestStartId);
    }

    private boolean hasActivePlayback() {
        return player != null || pendingSpeak != null || activeSpeakText != null;
    }

    private void rejectStartWithoutInterruptingPlayback(int startId, String reason) {
        Log.w(TAG, "rejected playback start: " + reason);
        // Retain the newest start ID while audio is active so its normal completion
        // consumes this rejected command too. If idle, stop the foreground service now.
        if (!hasActivePlayback()) stopSelfResult(startId);
    }

    private void stopTts() {
        pendingSpeak = null;
        clearActiveTtsState();
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    @Override public void onDestroy() {
        ROAD_SENSE_DUCK.detach(roadSenseDuckTarget);
        invalidatePlaybackSession();
        super.onDestroy();
        releasePlayer();
        if (tts != null) {
            clearActiveTtsState();
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
            tts = null; ttsReady = false;
        }
        abandonFocus();
        if (stopReceiverRegistered) {
            try { unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}
            stopReceiverRegistered = false;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class StartRequest {
        final String action;
        final String text;
        final String channel;
        final boolean loop;
        final String libName;
        final String filePath;

        StartRequest(String action, String text, String channel, boolean loop,
                     String libName, String filePath) {
            this.action = action;
            this.text = text;
            this.channel = channel;
            this.loop = loop;
            this.libName = libName;
            this.filePath = filePath;
        }
    }

    private static int usageForChannel(String channel) {
        if (channel == null) return AudioAttributes.USAGE_MEDIA;
        switch (channel.trim().toLowerCase()) {
            case "navigation": return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case "voice":
            case "assistant":  return AudioAttributes.USAGE_ASSISTANT;
            case "phone":
            case "call":       return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case "alarm":      return AudioAttributes.USAGE_ALARM;
            case "system":     return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case "media":
            default:           return AudioAttributes.USAGE_MEDIA;
        }
    }

    /**
     * Apply the proven OEM route recipe. Package-visible so the isolated RoadSense
     * player cannot drift from Automation Audio's channel behavior.
     */
    static void applyChannelRouting(MediaPlayer mediaPlayer, String channel) {
        int stream = streamForChannel(channel);
        // Set BOTH attributes and the deprecated direct stream type, in this order.
        // The direct setter is required for OEM-extended nav/voice streams, while the
        // attributes keep public streams aligned with the OEM reference player.
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(stream)
                .build());
        mediaPlayer.setAudioStreamType(stream);
    }

    static int streamForChannel(String channel) {
        if (channel == null) return AudioManager.STREAM_MUSIC;
        switch (channel.trim().toLowerCase()) {
            case "phone":
            case "call":       return AudioManager.STREAM_VOICE_CALL;
            case "alarm":      return AudioManager.STREAM_ALARM;
            case "system":     return AudioManager.STREAM_SYSTEM;
            case "ring":       return AudioManager.STREAM_RING;
            // Navigation / voice guidance ride the OEM-EXTENDED streams (STREAM_NAVI=14,
            // OEM voice=16), which is where the head unit's own nav prompts/TTS play and
            // which the "navigation volume" control adjusts (the OEM app setBroadcastVolume
            // uses stream 14, setVoiceVolume 16). These are reached via the DIRECT
            // MediaPlayer.setAudioStreamType path (see startPlayback + isOemExtendedStream);
            // the previous STREAM_MUSIC fallback made nav audio physically identical to
            // media (the reported "doesn't reach the nav channel" bug).
            case "navigation": return STREAM_NAVI;
            case "voice":
            case "assistant":  return STREAM_VOICE_OEM;
            default:           return AudioManager.STREAM_MUSIC;
        }
    }

    // OEM-extended (non-public) BYD stream ints. STREAM_NAVI(14) = navigation guidance,
    // STREAM_VOICE_OEM(16) = voice/assistant. Not part of the public AudioManager contract,
    // so they must be applied via the deprecated-but-working MediaPlayer.setAudioStreamType
    // (the secondary reference app's proven path), NOT AudioAttributes.setLegacyStreamType.
    //
    // Resolve the value from the BYD-modified AudioManager class by FIELD NAME first,
    // falling back to the known literal. the secondary reference app does exactly this (its C1569m reflects
    // AudioManager.STREAM_NAVI with a 14 fallback) rather than hardcoding, because the
    // int can differ by DiLink generation — a hardcoded 14 that doesn't match this
    // trim's real STREAM_NAVI is another way nav audio silently lands on media.
    // STREAM_NAVI is reflected by name (the secondary reference app's proven C1569m pattern); voice stays the
    // literal 16 that the OEM's voice-volume path uses (the secondary reference app does not reflect a voice
    // field, so there is no proven field name to look up — the literal is the known-good).
    private static final int STREAM_NAVI = resolveStreamConst("STREAM_NAVI", 14);
    private static final int STREAM_VOICE_OEM = 16;

    /** Reflect a (possibly OEM-added) {@code AudioManager.<name>} int constant; fall back
     *  to {@code def} when the field is absent (non-BYD build / different SDK). Mirrors
     *  the secondary reference app's C1569m.m1744a reflective stream-constant resolution. */
    private static int resolveStreamConst(String fieldName, int def) {
        try {
            return AudioManager.class.getField(fieldName).getInt(null);
        } catch (Throwable t) {
            return def;
        }
    }

    /** True for the OEM-extended stream ints that need the direct setAudioStreamType path. */
    static boolean isOemExtendedStream(int stream) {
        return stream == STREAM_NAVI || stream == STREAM_VOICE_OEM;
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s;
    }

    private void startForegroundCompat() {
        Notification n;
        try {
            n = buildNotification();
        } catch (Throwable t) {
            // buildNotification touches the notification channel and a drawable
            // resource; either can fail on an OEM ROM. It ran unguarded inside
            // onCreate(), so a failure here crashed the app process before a
            // single byte of audio was read. Without a notification we cannot
            // promote to foreground — but we CAN still play, so return and let
            // the service run unpromoted.
            Log.w(TAG, "buildNotification failed — continuing without foreground "
                    + "promotion: " + t.getMessage());
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t.getMessage());
            // RETRY WITHOUT the foreground-service-type arg — but guarded. The
            // bare retry used to sit outside any try, so when it ALSO failed the
            // Throwable escaped onCreate() and crashed the whole app process
            // (visible to the user as "OverDrive has stopped" the moment an
            // automation or key-mapping tried to play a sound). Both calls can
            // legitimately fail on this firmware: a missing/blocked notification
            // channel, or the OEM ROM rejecting FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK.
            // Playback itself does not depend on the foreground promotion, so
            // degrade rather than die: the service still starts, plays, and
            // stopSelf()s normally — it just risks being reaped earlier under
            // memory pressure.
            try {
                startForeground(NOTIFICATION_ID, n);
            } catch (Throwable t2) {
                Log.w(TAG, "startForeground retry failed too — continuing without "
                        + "foreground promotion: " + t2.getMessage());
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Playing audio")
                .setContentText("OverDrive automation")
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(true)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
