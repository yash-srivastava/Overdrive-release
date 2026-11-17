package com.overdrive.app.voice.local;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * On-device English speech-to-text (Moonshine tiny, MIT) for the end of a command window in which
 * keyword spotting found no phrase; {@link TranscriptMatcher} maps the text onto the catalog.
 */
public final class CommandTranscriber implements AutoCloseable {

    private static final String TAG = "CommandTranscriber";
    static final String ASSET_DIR = "voice/asr/moonshine-tiny-en";
    private static final String ENCODER = "encoder_model.ort";
    private static final String DECODER = "decoder_model_merged.ort";
    private static final String TOKENS = "tokens.txt";
    private static final int SAMPLE_RATE = 16_000;

    private static final Pattern LETTERS = Pattern.compile("[^a-z0-9 ]");

    private final OfflineRecognizer recognizer;
    private final TranscriptMatcher matcher = new TranscriptMatcher();
    private boolean closed;

    private CommandTranscriber(OfflineRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    /** Every model asset is present. Loads nothing. */
    public static boolean isAvailable(Context ctx) {
        if (ctx == null) return false;
        try {
            String[] listed = ctx.getAssets().list(ASSET_DIR);
            if (listed == null) return false;
            Set<String> present = new HashSet<>(Arrays.asList(listed));
            return present.contains(ENCODER) && present.contains(DECODER) && present.contains(TOKENS);
        } catch (Throwable t) {
            return false;
        }
    }

    public static CommandTranscriber load(Context ctx) throws IOException {
        AssetManager assets = ctx.getAssets();
        // The native loader exits the process on a missing asset, so prove each one opens first.
        for (String name : new String[] {ENCODER, DECODER, TOKENS}) {
            try (InputStream ignored = assets.open(ASSET_DIR + "/" + name)) {
                // present
            }
        }
        try {
            OfflineMoonshineModelConfig moonshine = new OfflineMoonshineModelConfig();
            moonshine.setEncoder(ASSET_DIR + "/" + ENCODER);
            moonshine.setMergedDecoder(ASSET_DIR + "/" + DECODER);
            OfflineModelConfig model = new OfflineModelConfig();
            model.setMoonshine(moonshine);
            model.setTokens(ASSET_DIR + "/" + TOKENS);
            model.setNumThreads(2);
            model.setProvider("cpu");
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(model);
            return new CommandTranscriber(new OfflineRecognizer(assets, config));
        } catch (Throwable t) {
            throw new IOException("Speech-to-text failed to initialise: " + t, t);
        }
    }

    public static final class Understood {
        public final java.util.List<LocalCommandSpotter.Match> matches;
        /** Transcript words outside the matched phrases, fillers excluded. */
        public final int extraWords;
        /** What was heard, for the field log when nothing matched. */
        public final String text;
        /** 1 when a leading wake-word tail was dropped from the transcript, else 0. */
        public final int wakeTail;

        Understood(java.util.List<LocalCommandSpotter.Match> matches, int extraWords, String text, int wakeTail) {
            this.matches = matches;
            this.extraWords = extraWords;
            this.text = text;
            this.wakeTail = wakeTail;
        }
    }

    /** Transcribes 16 kHz mono PCM and matches it to the catalog; {@code matches} is empty if none. */
    public synchronized Understood understand(short[] pcm, int offset, int length) {
        return understand(pcm, offset, length, false);
    }

    /**
     * As {@link #understand(short[], int, int)}; {@code afterWakeWord} says the buffer may still start
     * with the end of "OverDrive", so one leading tail token is forgiven by the matcher.
     */
    public synchronized Understood understand(short[] pcm, int offset, int length, boolean afterWakeWord) {
        String text = transcribe(pcm, offset, length);
        TranscriptMatcher.Result r = text == null ? null : matcher.match(text, afterWakeWord);
        return new Understood(
                r == null ? java.util.Collections.emptyList() : r.matches,
                r == null ? 0 : r.extraWords, text, r == null ? 0 : r.wakeTail);
    }

    /** The raw transcript of 16 kHz mono PCM, or null when nothing was decoded. */
    public synchronized String transcript(short[] pcm, int offset, int length) {
        return transcribe(pcm, offset, length);
    }

    /** Whether a transcript says "don't", "not", "never", "no" or the like anywhere. */
    public static boolean saysNegation(String text) {
        return TranscriptMatcher.saysNegation(text);
    }

    /** Lowercase letters, digits and spaces only, capped — safe for the daemon log. */
    public static String logSafe(String text, int max) {
        if (text == null) return null;
        String clean = LETTERS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    synchronized String transcribe(short[] pcm, int offset, int length) {
        if (closed || pcm == null || length <= 0 || offset < 0 || offset > pcm.length - length) {
            return null;
        }
        OfflineStream s = null;
        try {
            float[] audio = new float[length];
            for (int i = 0; i < length; i++) audio[i] = pcm[offset + i] / 32768f;
            s = recognizer.createStream();
            s.acceptWaveform(audio, SAMPLE_RATE);
            recognizer.decode(s);
            return recognizer.getResult(s).getText();
        } catch (Throwable t) {
            Log.w(TAG, "Transcription failed: " + t);
            return null;
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Second-stage wake check; {@code heard[0]} is set when the transcript says "OverDrive". */
    public synchronized String hearsWakeWord(short[] pcm, int offset, int length, boolean[] heard) {
        String text = transcribe(pcm, offset, length);
        heard[0] = saysWakeWord(text);
        return text;
    }

    static boolean saysWakeWord(String text) {
        return WakePhraseMatcher.saysWakeWord(text);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            recognizer.release();
        } catch (Throwable t) {
            Log.w(TAG, "Recognizer release failed: " + t);
        }
    }
}
