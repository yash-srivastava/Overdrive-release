package com.overdrive.app.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Free text spoken for a Trip Briefing turn. TextToSpeech renders each line to a WAV in the cache,
 * which then plays like the assistant's own clips (VoicePrompts) on the turn's channel: TTS can
 * only route to public streams, and the head unit's assistant duck silences the media one.
 * TTS calls run on the main thread; results come back on {@code engine}.
 */
final class BriefingSpeech {

    interface Events {
        void onRendered(int key, File wav);

        void onRenderFailed(int key);
    }

    private static final String TAG = "BriefingSpeech";

    private final Context app;
    private final String languageTag;
    private final Executor engine;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    // Main thread only.
    private TextToSpeech tts;
    private boolean ready;
    // Read on the engine thread too.
    private volatile boolean failed;
    private int pendingKey;
    private String pendingText;
    private volatile boolean released;
    private int nextKey;
    // Engine thread only.
    private MediaPlayer player;

    BriefingSpeech(Context context, String languageTag, Executor engine, Events events) {
        this.app = context.getApplicationContext();
        this.languageTag = languageTag;
        this.engine = engine;
        this.events = events;
        this.dir = app.getCacheDir();
        main.post(this::init);
    }

    /** Whether any TextToSpeech engine is installed (DiLink 3.0 head units have none). Unknown counts as yes. */
    static boolean engineInstalled(Context context) {
        try {
            List<ResolveInfo> engines = context.getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            return engines != null && !engines.isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The engine would not start: nothing this object renders will play. */
    boolean unavailable() {
        return failed;
    }

    /** Renders {@code text}; the returned key comes back in onRendered or onRenderFailed. Engine thread. */
    int render(String text) {
        final int key = ++nextKey;
        main.post(() -> {
            if (released) return;
            if (ready) {
                synthesize(key, text);
            } else if (failed) {
                fail(key);
            } else {
                if (pendingText != null) fail(pendingKey);
                pendingKey = key;
                pendingText = text;
            }
        });
        return key;
    }

    /** Plays a rendered line and returns its length in ms, or -1. Engine thread. */
    @SuppressWarnings("deprecation")
    int play(File wav, String channel) {
        stopPlayback();
        MediaPlayer mp = new MediaPlayer();
        try {
            int stream = Earcons.streamFor(channel);
            // Attributes then the direct stream type, as VoicePrompts: OEM nav/voice streams need both.
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(stream)
                    .build());
            mp.setAudioStreamType(stream);
            mp.setDataSource(wav.getPath());
            mp.prepare();
            int duration = mp.getDuration();
            mp.start();
            player = mp;
            return Math.max(0, duration);
        } catch (Throwable t) {
            Log.w(TAG, "play failed: " + t.getMessage());
            mp.release();
            return -1;
        }
    }

    /** Engine thread. */
    void stopPlayback() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        mp.release();
    }

    /** Stops playback and frees the engine and the rendered files. Engine thread; idempotent. */
    void release() {
        stopPlayback();
        if (released) return;
        released = true;
        main.post(() -> {
            pendingText = null;
            TextToSpeech t = tts;
            tts = null;
            if (t != null) {
                try {
                    t.stop();
                } catch (Throwable ignored) {
                }
                try {
                    t.shutdown();
                } catch (Throwable ignored) {
                }
            }
            for (int k = 1; k <= nextKey; k++) {
                File f = fileFor(k);
                if (f.exists() && !f.delete()) Log.d(TAG, "could not delete " + f.getName());
            }
        });
    }

    private void init() {
        if (released) return;
        try {
            // Guarded: on a ROM with no or a broken TTS the constructor itself can throw.
            tts = new TextToSpeech(app, status -> {
                if (released) return;
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    Log.w(TAG, "TTS init failed (status=" + status + ")");
                    failed = true;
                } else {
                    ready = true;
                    setLanguage();
                    tts.setOnUtteranceProgressListener(new Progress());
                }
                String text = pendingText;
                pendingText = null;
                if (text == null) return;
                if (ready) synthesize(pendingKey, text);
                else fail(pendingKey);
            });
        } catch (Throwable t) {
            Log.w(TAG, "TextToSpeech unavailable: " + t.getMessage());
            tts = null;
            failed = true;
        }
    }

    /** The app's language when the engine has a voice for it, else the engine's default. */
    private void setLanguage() {
        try {
            Locale want = Locale.forLanguageTag(languageTag == null ? "" : languageTag);
            if (want.getLanguage().isEmpty()) return;
            if (tts.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(want);
        } catch (Throwable t) {
            Log.w(TAG, "set language failed: " + t.getMessage());
        }
    }

    private void synthesize(int key, String text) {
        TextToSpeech t = tts;
        if (t == null) {
            fail(key);
            return;
        }
        try {
            int max = 4000;
            try {
                max = TextToSpeech.getMaxSpeechInputLength();
            } catch (Throwable ignored) {
            }
            String said = text.length() > max ? text.substring(0, max) : text;
            int rc = t.synthesizeToFile(said, new Bundle(), fileFor(key), utteranceId(key));
            if (rc != TextToSpeech.SUCCESS) fail(key);
        } catch (Throwable e) {
            Log.w(TAG, "synthesize failed: " + e.getMessage());
            fail(key);
        }
    }

    private void fail(int key) {
        if (released) return;
        engine.execute(() -> {
            if (!released) events.onRenderFailed(key);
        });
    }

    private File fileFor(int key) {
        return new File(dir, "voice_brief_" + key + ".wav");
    }

    private static String utteranceId(int key) {
        return "brief" + key;
    }

    private static int keyOf(String utteranceId) {
        try {
            return utteranceId != null && utteranceId.startsWith("brief")
                    ? Integer.parseInt(utteranceId.substring(5)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Binder thread. */
    private final class Progress extends UtteranceProgressListener {
        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onDone(String utteranceId) {
            int key = keyOf(utteranceId);
            if (key < 0 || released) return;
            File wav = fileFor(key);
            // A WAV header alone is a failed render.
            if (wav.length() <= 44) {
                fail(key);
                return;
            }
            engine.execute(() -> {
                if (!released) events.onRendered(key, wav);
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String utteranceId) {
            int key = keyOf(utteranceId);
            if (key >= 0) fail(key);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            onError(utteranceId);
        }
    }
}
