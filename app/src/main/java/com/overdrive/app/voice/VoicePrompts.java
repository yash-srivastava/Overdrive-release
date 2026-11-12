package com.overdrive.app.voice;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Pre-generated spoken confirmations in assets/voice/prompts/{en,zh}; names come from
 * {@link #keyFor} and tools/voice/gen_voice_prompts.py. Languages with no recorded clips but a
 * translated {@code voice_prompt_<key>} string (Hebrew and Russian) speak that string through the system
 * text-to-speech instead; when no voice for the language is installed, or it is not ready yet,
 * the English clip plays as before. Engine-thread only.
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

    private static final String PROMPT_PREFIX = "voice_prompt_";
    // Spoken length estimate for text-to-speech, which reports no duration up front: the engine
    // keeps the microphone muted until the prompt ends, so err long.
    private static final int TTS_BASE_MS = 700;
    private static final int TTS_MS_PER_CHAR = 75;

    private final Context app;
    private MediaPlayer player;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private String ttsLang;
    private Resources speechResources;
    private String speechResourcesLang;

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

    /**
     * The language whose {@code voice_prompt_*} strings are spoken by text-to-speech, or null when
     * the recorded clips are used (English, Chinese and every language without a translation).
     */
    static String speechLanguage(String locale) {
        String lower = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
        if (lower.startsWith("he") || lower.startsWith("iw")) return "he";
        if (lower.startsWith("ru")) return "ru";
        return null;
    }

    /** Starts the text-to-speech engine early for a spoken-prompt language, so the first prompt is ready. */
    void prepare(String locale) {
        String lang = speechLanguage(locale);
        if (lang != null) ensureTts(lang);
    }

    /** Plays a clip and returns its duration in ms, or -1 when it is missing or cannot play. */
    @SuppressWarnings("deprecation")
    int play(String key, String locale, String channel) {
        stop();
        String speech = speechLanguage(locale);
        if (speech != null) {
            int ms = speak(key, speech, channel);
            if (ms >= 0) return ms;
        }
        String lang = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh" : "en";
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

    /** Speaks the translated prompt and returns its estimated length, or -1 to fall back to the clip. */
    @SuppressWarnings("deprecation")
    private int speak(String key, String lang, String channel) {
        String text = promptText(key, lang);
        if (text == null) return -1;
        TextToSpeech engine = ensureTts(lang);
        if (engine == null || !ttsReady) return -1;
        try {
            int stream = Earcons.streamFor(channel);
            engine.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(stream)
                    .build());
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream);
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, key) != TextToSpeech.SUCCESS) {
                return -1;
            }
            return TTS_BASE_MS + TTS_MS_PER_CHAR * text.length();
        } catch (Throwable t) {
            Log.w(TAG, "spoken prompt " + key + " failed: " + t.getMessage());
            return -1;
        }
    }

    /** The {@code voice_prompt_<key>} string in {@code lang}, or null when it has none. */
    private String promptText(String key, String lang) {
        try {
            if (speechResources == null || !lang.equals(speechResourcesLang)) {
                Configuration config = new Configuration(app.getResources().getConfiguration());
                config.setLocale(Locale.forLanguageTag(lang));
                speechResources = app.createConfigurationContext(config).getResources();
                speechResourcesLang = lang;
            }
            int id = speechResources.getIdentifier(PROMPT_PREFIX + key, "string", app.getPackageName());
            if (id == 0) return null;
            String text = speechResources.getString(id).trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable t) {
            return null;
        }
    }

    private TextToSpeech ensureTts(String lang) {
        if (tts != null && lang.equals(ttsLang)) return tts;
        shutdownTts();
        ttsLang = lang;
        ttsReady = false;
        final Locale target = Locale.forLanguageTag(lang);
        try {
            final TextToSpeech[] holder = new TextToSpeech[1];
            holder[0] = new TextToSpeech(app, status -> {
                TextToSpeech engine = holder[0];
                if (engine == null || status != TextToSpeech.SUCCESS) return;
                int available = engine.isLanguageAvailable(target);
                if (available < TextToSpeech.LANG_AVAILABLE) {
                    Log.i(TAG, "no text-to-speech voice for " + target + "; using recorded clips");
                    return;
                }
                engine.setLanguage(target);
                ttsReady = true;
            });
            tts = holder[0];
        } catch (Throwable t) {
            Log.w(TAG, "text-to-speech unavailable: " + t.getMessage());
            tts = null;
        }
        return tts;
    }

    private void shutdownTts() {
        TextToSpeech engine = tts;
        tts = null;
        ttsReady = false;
        if (engine == null) return;
        try {
            engine.shutdown();
        } catch (Throwable ignored) {
        }
    }

    void stop() {
        TextToSpeech engine = tts;
        if (engine != null && ttsReady) {
            try {
                engine.stop();
            } catch (Throwable ignored) {
            }
        }
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
        shutdownTts();
    }
}
