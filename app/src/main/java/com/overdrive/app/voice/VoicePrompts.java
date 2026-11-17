package com.overdrive.app.voice;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Pre-generated spoken confirmations in assets/voice/prompts/{en,zh}; names come from
 * {@link #keyFor} and tools/voice/gen_voice_prompts.py. Engine-thread only.
 */
final class VoicePrompts {

    static final String DONE = "status_done";
    static final String BLOCKED_MOVING = "status_blocked_moving";
    static final String REFUSED = "status_refused";
    static final String FAILED = "status_failed";
    static final String NOT_UNDERSTOOD = "status_not_understood";
    static final String OFFLINE = "status_offline";
    static final String BUSY = "status_busy";

    private static final String TAG = "VoicePrompts";
    private static final String DIR = "voice/prompts/";

    private final Context app;
    private MediaPlayer player;

    VoicePrompts(Context context) {
        app = context.getApplicationContext();
    }

    /** Clip key for an executed intent; slot order matches the generator. */
    static String keyFor(String intent, JSONObject slots) {
        StringBuilder k = new StringBuilder(intent);
        if (slots != null) {
            if (slots.has("seat")) k.append('_').append(slots.optString("seat"));
            if (slots.has("goal")) k.append('_').append(slots.optString("goal"));
            if (slots.has("celsius")) k.append('_').append(slots.optInt("celsius"));
            if (slots.has("level")) k.append('_').append(slots.optInt("level"));
            if (slots.has("delta")) {
                int d = slots.optInt("delta");
                k.append(d > 0 ? "_up" : "_down");
                if (Math.abs(d) > 1) k.append(Math.abs(d));
            }
            if (slots.has("on")) k.append(slots.optBoolean("on") ? "_on" : "_off");
            if (slots.has("open")) k.append(slots.optBoolean("open") ? "_open" : "_close");
        }
        return k.toString().replace('.', '_');
    }

    /** Plays a clip and returns its duration in ms, or -1 when it is missing or cannot play. */
    @SuppressWarnings("deprecation")
    int play(String key, String locale, String channel) {
        stop();
        String lang = langOf(locale);
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor fd = app.getAssets().openFd(DIR + lang + "/" + key + ".m4a")) {
            int stream = Earcons.streamFor(channel);
            // Attributes then the direct stream type: OEM nav/voice streams need both.
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(stream)
                    .build());
            mp.setAudioStreamType(stream);
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            mp.prepare();
            int duration = mp.getDuration();
            mp.start();
            player = mp;
            return Math.max(0, duration);
        } catch (FileNotFoundException e) {
            mp.release();
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "prompt " + key + " failed: " + t.getMessage());
            mp.release();
            return -1;
        }
    }

    /** Whether the clip for [key] exists in this locale's set. */
    boolean has(String key, String locale) {
        try (AssetFileDescriptor fd = app.getAssets().openFd(DIR + langOf(locale) + "/" + key + ".m4a")) {
            return fd.getLength() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String langOf(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh" : "en";
    }

    void stop() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        mp.release();
    }

    void release() {
        stop();
    }
}
