package com.overdrive.app.voice.local;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * On-device command spotter (sherpa-onnx keyword spotting) over {@link CommandCatalog}. Nothing
 * native is touched until {@link #load}; {@link #close} frees the model.
 */
public final class LocalCommandSpotter implements AutoCloseable {

    private static final String TAG = "LocalCommandSpotter";

    static final String ASSET_DIR = "voice/kws";
    static final String ENCODER = "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx";
    static final String DECODER = "decoder-epoch-13-avg-2-chunk-8-left-64.onnx";
    static final String JOINER = "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx";
    static final String TOKENS = "tokens.txt";
    private static final String DEFAULT_KEYWORDS = "keywords-default.txt";
    private static final String NATIVE_LIB = "libsherpa-onnx-jni.so";

    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_ACTIVE_PATHS = 4;
    private static final float KEYWORDS_SCORE = 1.5f;
    private static final float KEYWORDS_THRESHOLD = 0.25f;
    // Fires two blank frames (~80 ms) after the last token; the catalog has no prefix conflicts.
    private static final int NUM_TRAILING_BLANKS = 1;

    private static final String WAKE_TAG = "overdrive";
    // "OverDrive" in rhotic, non-rhotic and shifted-stress forms; boost/threshold from the cascade eval.
    // The seven variant lines are near-neighbours of those base pronunciations (lost /r/, /dh/ for
    // /d/, /f/ for /v/, /t/ for /d/, "drave") at a stricter threshold (#0.15; higher = stricter in
    // sherpa-onnx) with the same boost, so accents the base lines miss can wake without loosening them.
    static final String WAKE_KEYWORDS = String.join("/",
            "OW1 V ER0 D R AY2 V :2.0 #0.10 @overdrive",
            "OW1 V ER0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V AH0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V AH0 D R AY2 V :2.0 #0.10 @overdrive",
            "AO1 V ER0 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V ER1 D R AY1 V :2.0 #0.10 @overdrive",
            "OW1 V ER0 D AH0 R AY1 V :2.0 #0.15 @overdrive",
            "OW1 V ER0 DH AH0 R AY1 V :2.0 #0.15 @overdrive",
            "OW1 V ER0 D R AY1 F :2.0 #0.15 @overdrive",
            "OW1 V AH0 D R AY1 F :2.0 #0.15 @overdrive",
            "OW1 V ER0 T R AY1 V :2.0 #0.15 @overdrive",
            "AO1 V AH0 D R AY1 V :2.0 #0.15 @overdrive",
            "OW1 V ER0 D R EY1 V :2.0 #0.15 @overdrive");

    private static final List<String> LOCALES = Collections.unmodifiableList(Arrays.asList(
            CommandCatalog.EN, CommandCatalog.ZH_CN, CommandCatalog.ZH_TW));

    public static final class Match {
        public final String intent;
        public final JSONObject slots;
        public final String phrase;
        /** Transcript word index where the phrase starts; -1 for a keyword-spotter hit. */
        public final int start;
        /**
         * Keyword-spotter hits only: samples between the start of the spoken phrase and the end of
         * the audio fed so far, so the engine can tell a bare command from one inside a sentence;
         * -1 when the spotter gave no usable timestamp.
         */
        public final long phraseStartOffset;
        /** The same for the phrase's last token (where its final syllable begins); -1 when unknown. */
        public final long phraseEndOffset;

        Match(String intent, JSONObject slots, String phrase) {
            this(intent, slots, phrase, -1);
        }

        Match(String intent, JSONObject slots, String phrase, int start) {
            this(intent, slots, phrase, start, -1);
        }

        Match(String intent, JSONObject slots, String phrase, int start, long phraseStartOffset) {
            this(intent, slots, phrase, start, phraseStartOffset, -1);
        }

        Match(String intent, JSONObject slots, String phrase, int start, long phraseStartOffset, long phraseEndOffset) {
            this.intent = intent;
            this.slots = slots;
            this.phrase = phrase;
            this.start = start;
            this.phraseStartOffset = phraseStartOffset;
            this.phraseEndOffset = phraseEndOffset;
        }

        @Override
        public String toString() {
            return intent + " " + slots + " \"" + phrase + "\"";
        }
    }

    private final KeywordSpotter spotter;
    private final Map<String, String> commandKeywords;
    private final Map<String, String> cancelKeywords;

    /** Pseudo-intent: "OverDrive" said again inside a command window. */
    public static final String INTENT_WAKE = "assistant.wake";
    /** Pseudo-intent of a {@link #beginPhrases} window: {@code phrase} is the spotted line's tag. */
    public static final String INTENT_PHRASE = "assistant.phrase";
    private static final String NEGATION_TAG = "neg";
    // {@link #hearsNegation}: "don't", "do not", "not", "never"; 不 / 别 alone and 不要 / 不用 / 不想 (each set is
    // its own stream: in one, the long lines drown out a short 不).
    private static final String[] NEGATION_CHECK_EN = {
            "D OW1 N T #0.15 @neg/D OW1 N #0.15 @neg/D UW1 N AA1 T #0.15 @neg/N AA1 T #0.15 @neg/N EH1 V ER0 #0.15 @neg"};
    private static final String[] NEGATION_CHECK_ZH = {
            "b ù #0.15 @neg/b ú #0.15 @neg/b ié #0.15 @neg",
            "b ú y ào #0.15 @neg/b ù y ào #0.15 @neg/b ú y òng #0.15 @neg/b ù y òng #0.15 @neg/b ù x iǎng #0.15 @neg/b ié #0.15 @neg"};
    /** Tag of the lines wake-word enrollment learned from the driver's own voice. */
    public static final String PERSONAL_WAKE_TAG = "overdrive_p";

    private final Set<String> tokenSet;
    // Enrolled lines ('/'-joined), added to every wake check and command window; empty when none.
    private String personalWake = "";
    private boolean lastWakePersonal;
    private OnlineStream stream;
    // A second stream started just ahead of speech that follows a pause; both are decoded.
    private OnlineStream ahead;
    private String windowKeywords = "";
    private String windowLanguage = CommandCatalog.EN;
    private boolean phraseWindow;
    private float[] samples = new float[0];
    // Samples fed to each stream since it was created or last reset: keyword timestamps count
    // from there, so this is what turns them into a position in the window's audio.
    private long fedMain;
    private long fedAhead;
    // Decodes since the stream was created or last reset. A reset restarts the timestamps at the decoder's
    // position, which lags the fed audio: each decode consumes one chunk of it.
    private int decodesMain;
    private int decodesAhead;
    /** Audio one decode consumes for the bundled chunk-8 model (measured: 25 decodes per 4.0 s). */
    private static final long SAMPLES_PER_DECODE = 2560;
    private boolean closed;
    private boolean streamFailureLogged;

    private LocalCommandSpotter(KeywordSpotter spotter, Map<String, String> commandKeywords,
                                Map<String, String> cancelKeywords, Set<String> tokenSet) {
        this.spotter = spotter;
        this.commandKeywords = commandKeywords;
        this.cancelKeywords = cancelKeywords;
        this.tokenSet = tokenSet;
    }

    /** Native library and every model asset are present. Loads nothing. */
    public static boolean isAvailable(Context ctx) {
        if (ctx == null) return false;
        try {
            if (!hasNativeLibrary(ctx)) return false;
            String[] listed = ctx.getAssets().list(ASSET_DIR);
            if (listed == null) return false;
            Set<String> present = new HashSet<>(Arrays.asList(listed));
            for (String name : requiredAssets()) {
                if (!present.contains(name)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> supportedLocales() {
        return LOCALES;
    }

    public static LocalCommandSpotter load(Context ctx) throws IOException {
        if (ctx == null) throw new IOException("No context");
        AssetManager assets = ctx.getAssets();
        // The native loader exits the process on a missing asset, so prove each one opens first.
        for (String name : requiredAssets()) {
            try (InputStream ignored = assets.open(ASSET_DIR + "/" + name)) {
                // present
            }
        }
        Map<String, String> commands = new HashMap<>();
        Map<String, String> cancels = new HashMap<>();
        for (String language : LOCALES) {
            List<String> lines = readLines(assets, ASSET_DIR + "/" + keywordFile(language));
            StringBuilder all = new StringBuilder();
            StringBuilder cancel = new StringBuilder();
            for (String line : lines) {
                CommandCatalog.Entry entry = CommandCatalog.find(language, tagOf(line));
                if (entry == null) continue;
                append(all, line);
                if (entry.isCancel()) append(cancel, line);
            }
            if (all.length() == 0) throw new IOException("No usable keywords for " + language);
            commands.put(language, all.toString());
            cancels.put(language, cancel.toString());
        }
        Set<String> tokens = new HashSet<>();
        for (String line : readLines(assets, ASSET_DIR + "/" + TOKENS)) {
            int space = line.lastIndexOf(' ');
            if (space > 0) tokens.add(line.substring(0, space).trim());
        }

        KeywordSpotter spotter;
        try {
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(ASSET_DIR + "/" + ENCODER);
            transducer.setDecoder(ASSET_DIR + "/" + DECODER);
            transducer.setJoiner(ASSET_DIR + "/" + JOINER);
            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(ASSET_DIR + "/" + TOKENS);
            model.setNumThreads(1);
            model.setProvider("cpu");
            model.setModelType("zipformer2");
            KeywordSpotterConfig config = new KeywordSpotterConfig();
            config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0f));
            config.setModelConfig(model);
            config.setMaxActivePaths(MAX_ACTIVE_PATHS);
            config.setKeywordsFile(ASSET_DIR + "/" + DEFAULT_KEYWORDS);
            config.setKeywordsScore(KEYWORDS_SCORE);
            config.setKeywordsThreshold(KEYWORDS_THRESHOLD);
            config.setNumTrailingBlanks(NUM_TRAILING_BLANKS);
            spotter = new KeywordSpotter(assets, config);
        } catch (Throwable t) {
            throw new IOException("Keyword spotter failed to initialise: " + t, t);
        }
        return new LocalCommandSpotter(spotter, commands, cancels, Collections.unmodifiableSet(tokens));
    }

    /** True when every token is one this model can spot (a keyword line with any other is rejected). */
    public boolean knowsTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return false;
        for (String t : tokens) {
            if (t == null || !tokenSet.contains(t) || "<blk>".equals(t) || t.startsWith("<")) return false;
        }
        return true;
    }

    /** Enrolled wake lines, '/'-joined with {@link #PERSONAL_WAKE_TAG}; null or empty removes them. */
    public synchronized void setPersonalWake(String lines) {
        personalWake = lines == null ? "" : lines.trim();
    }

    /** The last {@link #verifyWake} hit came from an enrolled line. */
    public synchronized boolean lastWakeWasPersonal() {
        return lastWakePersonal;
    }

    private String wakeKeywords() {
        return personalWake.isEmpty() ? WAKE_KEYWORDS : WAKE_KEYWORDS + "/" + personalWake;
    }

    private static boolean isWakeTag(String tag) {
        return WAKE_TAG.equals(tag) || PERSONAL_WAKE_TAG.equals(tag);
    }

    /** Opens a fresh command window with that locale's phrases (English when unsupported). */
    public synchronized void begin(String locale) {
        openWindow(resolveLanguage(locale), false);
    }

    /** Opens a window that only listens for the cancel phrases. */
    public synchronized void beginConfirmation(String locale) {
        openWindow(resolveLanguage(locale), true);
    }

    /**
     * Opens a window over the given tokenized lines ('/'-joined, each with its own {@code @tag})
     * plus "OverDrive"; every hit is an {@link #INTENT_PHRASE} match carrying its tag.
     */
    public synchronized void beginPhrases(String keywords) {
        if (closed) return;
        releaseStream();
        String lines = keywords == null ? "" : keywords.trim();
        windowKeywords = lines.isEmpty() ? wakeKeywords() : lines + "/" + wakeKeywords();
        windowLanguage = CommandCatalog.EN;
        phraseWindow = true;
        fedMain = 0;
        decodesMain = 0;
        try {
            stream = spotter.createStream(windowKeywords);
        } catch (Throwable t) {
            stream = null;
            Log.w(TAG, "Could not open a phrase window: " + t);
        }
    }

    /** Feeds 16 kHz mono PCM; returns the first phrase completed in this audio, or null. */
    public synchronized Match process(short[] pcm, int offset, int length) {
        if (closed || stream == null || pcm == null || length <= 0
                || offset < 0 || offset > pcm.length - length) {
            return null;
        }
        if (samples.length != length) samples = new float[length];
        for (int i = 0; i < length; i++) samples[i] = pcm[offset + i] / 32768f;
        Match first = null;
        try {
            first = decode(stream, false);
        } catch (Throwable t) {
            Log.w(TAG, "Keyword decode failed; closing the window: " + t);
            releaseStream();
            return null;
        }
        if (ahead != null) {
            try {
                Match m = decode(ahead, true);
                if (first == null) first = m;
            } catch (Throwable t) {
                releaseAhead();
            }
        }
        return first;
    }

    /**
     * Opens a second stream fed with {@code lead} (the audio just before speech that followed a
     * pause), because a stream that began long before a command spots it less reliably.
     */
    public synchronized void addAheadStream(short[] lead, int offset, int length) {
        if (closed || stream == null) return;
        releaseAhead();
        try {
            ahead = spotter.createStream(windowKeywords);
            fedAhead = 0;
            decodesAhead = 0;
            if (length > 0) {
                float[] audio = new float[length];
                for (int i = 0; i < length; i++) audio[i] = lead[offset + i] / 32768f;
                ahead.acceptWaveform(audio, SAMPLE_RATE);
                fedAhead = length;
                while (spotter.isReady(ahead)) {
                    spotter.decode(ahead);
                    decodesAhead++;
                }
            }
        } catch (Throwable t) {
            releaseAhead();
        }
    }

    public synchronized void end() {
        releaseStream();
    }

    /**
     * Restarts the window's timestamps during silence before a reply. The model does so by itself after
     * about 1.5 s of silence without saying, which would misplace the hits of a reply that starts late.
     */
    public synchronized void restartIdle() {
        if (closed || stream == null) return;
        try {
            spotter.reset(stream);
            fedMain = Math.max(0L, fedMain - decodesMain * SAMPLES_PER_DECODE);
            decodesMain = 0;
        } catch (Throwable t) {
            Log.w(TAG, "Keyword stream restart failed: " + t);
        }
    }

    private Match decode(OnlineStream s, boolean isAhead) {
        s.acceptWaveform(samples, SAMPLE_RATE);
        long fed = (isAhead ? fedAhead : fedMain) + samples.length;
        int decodes = isAhead ? decodesAhead : decodesMain;
        Match found = null;
        while (spotter.isReady(s)) {
            spotter.decode(s);
            decodes++;
            KeywordSpotterResult result = spotter.getResult(s);
            String tag = result.getKeyword();
            if (tag == null || tag.isEmpty()) continue;
            long offset = phraseStartOffset(result, fed);
            long endOffset = phraseLastTokenOffset(result, fed);
            spotter.reset(s);
            // The new timestamps start where the decoder was: only the audio it hadn't consumed is ahead of them.
            fed = Math.max(0L, fed - decodes * SAMPLES_PER_DECODE);
            decodes = 0;
            Match match = toMatch(tag.trim(), offset, endOffset);
            if (match != null && found == null) found = match;
        }
        if (isAhead) {
            fedAhead = fed;
            decodesAhead = decodes;
        } else {
            fedMain = fed;
            decodesMain = decodes;
        }
        return found;
    }

    /**
     * Samples from the start of the spotted phrase to the end of what the stream has been fed;
     * -1 when the keyword carried no timestamps or they do not fit inside the fed audio.
     */
    private static long phraseStartOffset(KeywordSpotterResult result, long fed) {
        float[] times = result.getTimestamps();
        if (times == null || times.length == 0) return -1;
        long offset = fed - Math.round(times[0] * SAMPLE_RATE);
        return offset < 0 || offset > fed ? -1 : offset;
    }

    /** As {@link #phraseStartOffset}, for the last token. */
    private static long phraseLastTokenOffset(KeywordSpotterResult result, long fed) {
        float[] times = result.getTimestamps();
        if (times == null || times.length == 0) return -1;
        long offset = fed - Math.round(times[times.length - 1] * SAMPLE_RATE);
        return offset < 0 || offset > fed ? -1 : offset;
    }

    /** {@link #verifyWake}: "OverDrive" was not heard. */
    public static final int WAKE_NOT_HEARD = -1;
    /** {@link #verifyWake}: heard, but the keyword carried no timestamps. */
    public static final int WAKE_END_UNKNOWN = -2;

    /**
     * Second-stage wake check over level-normalized audio ending shortly after the first-stage
     * trigger. Returns the sample offset (from {@code offset}) where "OverDrive" ended, or one of
     * the {@code WAKE_} codes. Zero false wakes in 5.4 h of LibriSpeech.
     */
    public synchronized int verifyWake(short[] pcm, int offset, int length) {
        return verifyWith(pcm, offset, length, wakeKeywords());
    }

    /**
     * Whether the audio said just before a command (16 kHz mono) holds a negation ("don't …", 不要…, 别…) that starts
     * by {@code latestStart} samples in, in the command language of {@code locale} (a later one is the command's own
     * first syllable: 把, 播, 玻 sound like 不). Its own fresh streams, so the open window is untouched.
     */
    public synchronized boolean hearsNegation(short[] pcm, int offset, int length, String locale, int latestStart) {
        if (closed || pcm == null || length <= 0 || offset < 0 || offset > pcm.length - length) return false;
        String[] sets = resolveLanguage(locale).startsWith("zh") ? NEGATION_CHECK_ZH : NEGATION_CHECK_EN;
        for (String lines : sets) {
            OnlineStream s = null;
            try {
                s = spotter.createStream(lines);
                float[] audio = new float[length];
                for (int i = 0; i < length; i++) audio[i] = pcm[offset + i] / 32768f;
                s.acceptWaveform(audio, SAMPLE_RATE);
                s.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
                s.inputFinished();
                // after a reset the timestamps count from where the decoder was: [base] carries them on
                long base = 0;
                int decodes = 0;
                while (spotter.isReady(s)) {
                    spotter.decode(s);
                    decodes++;
                    KeywordSpotterResult result = spotter.getResult(s);
                    String tag = result.getKeyword();
                    if (tag == null || tag.isEmpty()) continue;
                    float[] times = result.getTimestamps();
                    if (NEGATION_TAG.equals(tag.trim()) && times != null && times.length > 0
                            && base + Math.round(times[0] * SAMPLE_RATE) <= latestStart) {
                        return true;
                    }
                    // keywords-default.txt rides along in every stream; a late negation may be followed by an early one
                    spotter.reset(s);
                    base += (long) decodes * SAMPLES_PER_DECODE;
                    decodes = 0;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Negation check failed: " + t);
            } finally {
                if (s != null) {
                    try {
                        s.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** {@link #verifyWake} against the given keyword lines only (enrollment cross-checks its takes). */
    public synchronized boolean hearsWake(short[] pcm, int offset, int length, String keywords) {
        return keywords != null && !keywords.isEmpty()
                && verifyWith(pcm, offset, length, keywords) != WAKE_NOT_HEARD;
    }

    private int verifyWith(short[] pcm, int offset, int length, String keywords) {
        lastWakePersonal = false;
        if (closed || pcm == null || length <= 0 || offset < 0 || offset > pcm.length - length) {
            return WAKE_NOT_HEARD;
        }
        OnlineStream s = null;
        try {
            s = spotter.createStream(keywords);
            float[] audio = new float[length];
            for (int i = 0; i < length; i++) audio[i] = pcm[offset + i] / 32768f;
            s.acceptWaveform(audio, SAMPLE_RATE);
            s.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
            s.inputFinished();
            boolean reset = false;
            while (spotter.isReady(s)) {
                spotter.decode(s);
                KeywordSpotterResult result = spotter.getResult(s);
                String tag = result.getKeyword();
                if (tag == null || tag.isEmpty()) continue;
                if (!isWakeTag(tag.trim())) {
                    // keywords-default.txt rides along in every stream; it is not the wake word.
                    spotter.reset(s);
                    reset = true;
                    continue;
                }
                lastWakePersonal = PERSONAL_WAKE_TAG.equals(tag.trim());
                float[] times = result.getTimestamps();
                // Timestamps restart after a reset, so they only locate the word in a fresh stream.
                if (reset || times == null || times.length == 0) return WAKE_END_UNKNOWN;
                int end = Math.round(times[times.length - 1] * SAMPLE_RATE);
                return Math.max(0, Math.min(length, end));
            }
            return WAKE_NOT_HEARD;
        } catch (Throwable t) {
            Log.w(TAG, "Wake verification failed: " + t);
            return WAKE_NOT_HEARD;
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        releaseStream();
        try {
            spotter.release();
        } catch (Throwable t) {
            Log.w(TAG, "Keyword spotter release failed: " + t);
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private void openWindow(String language, boolean cancelOnly) {
        if (closed) return;
        releaseStream();
        String keywords = (cancelOnly ? cancelKeywords : commandKeywords).get(language);
        if (keywords == null) keywords = "";
        // A repeated "OverDrive" must re-arm the window instead of failing as an unknown phrase.
        if (!cancelOnly) keywords = keywords.isEmpty() ? wakeKeywords() : keywords + "/" + wakeKeywords();
        windowKeywords = keywords;
        windowLanguage = language;
        phraseWindow = false;
        fedMain = 0;
        decodesMain = 0;
        try {
            stream = spotter.createStream(keywords);
        } catch (Throwable t) {
            stream = null;
            if (!streamFailureLogged) {
                streamFailureLogged = true;
                Log.w(TAG, "Could not open a " + language + " keyword window: " + t);
            }
        }
    }

    private void releaseStream() {
        releaseAhead();
        OnlineStream s = stream;
        stream = null;
        if (s == null) return;
        try {
            s.release();
        } catch (Throwable ignored) {
        }
    }

    private void releaseAhead() {
        OnlineStream s = ahead;
        ahead = null;
        if (s == null) return;
        try {
            s.release();
        } catch (Throwable ignored) {
        }
    }

    private Match toMatch(String tag, long phraseStartOffset, long phraseEndOffset) {
        if (phraseWindow) return new Match(INTENT_PHRASE, new JSONObject(), tag, -1, phraseStartOffset, phraseEndOffset);
        // with its position: a re-arm needs where the word ended
        if (isWakeTag(tag)) return new Match(INTENT_WAKE, new JSONObject(), "OverDrive", -1, phraseStartOffset, phraseEndOffset);
        CommandCatalog.Entry entry = CommandCatalog.find(windowLanguage, tag);
        // keywords-default.txt (always active) carries the English cancel phrase.
        if (entry == null) entry = CommandCatalog.find(CommandCatalog.EN, tag);
        return matchOf(entry, -1, phraseStartOffset);
    }

    static Match matchOf(CommandCatalog.Entry entry) {
        return matchOf(entry, -1);
    }

    static Match matchOf(CommandCatalog.Entry entry, int start) {
        return matchOf(entry, start, -1);
    }

    private static Match matchOf(CommandCatalog.Entry entry, int start, long phraseStartOffset) {
        if (entry == null) return null;
        JSONObject slots = new JSONObject();
        try {
            for (int i = 0; i + 1 < entry.slots.length; i += 2) {
                slots.put((String) entry.slots[i], entry.slots[i + 1]);
            }
        } catch (Exception e) {
            return null;
        }
        return new Match(entry.intent, slots, entry.phrase, start, phraseStartOffset);
    }

    static String resolveLanguage(String locale) {
        if (locale == null) return CommandCatalog.EN;
        String l = locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (!l.startsWith("zh")) return CommandCatalog.EN;
        if (l.contains("tw") || l.contains("hk") || l.contains("mo") || l.contains("hant")) {
            return CommandCatalog.ZH_TW;
        }
        return CommandCatalog.ZH_CN;
    }

    private static String keywordFile(String language) {
        return "keywords-" + language + ".txt";
    }

    /** The transducer and its token table, shared with {@link PhoneDecoder}. */
    static List<String> modelAssets() {
        return Arrays.asList(ENCODER, DECODER, JOINER, TOKENS);
    }

    private static List<String> requiredAssets() {
        String[] base = {ENCODER, DECODER, JOINER, TOKENS, DEFAULT_KEYWORDS};
        String[] all = Arrays.copyOf(base, base.length + LOCALES.size());
        for (int i = 0; i < LOCALES.size(); i++) all[base.length + i] = keywordFile(LOCALES.get(i));
        return Arrays.asList(all);
    }

    private static boolean hasNativeLibrary(Context ctx) {
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        if (dir != null && new File(dir, NATIVE_LIB).isFile()) return true;
        // Uncompressed-in-APK installs keep the library inside the APK.
        String apk = ctx.getApplicationInfo().sourceDir;
        if (apk == null) return false;
        try (ZipFile zip = new ZipFile(apk)) {
            return zip.getEntry("lib/arm64-v8a/" + NATIVE_LIB) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> readLines(AssetManager assets, String path) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.indexOf('/') < 0) lines.add(line);
            }
        }
        return lines;
    }

    private static String tagOf(String line) {
        int at = line.lastIndexOf(" @");
        return at < 0 ? null : line.substring(at + 2).trim();
    }

    private static void append(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append('/');
        sb.append(line);
    }
}
