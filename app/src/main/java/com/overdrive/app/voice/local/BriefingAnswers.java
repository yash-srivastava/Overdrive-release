package com.overdrive.app.voice.local;

import com.overdrive.app.voice.TripBriefingAsk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The closed answer set of a Trip Briefing question ("Want directions?"): yes or no, and the
 * navigation app, in English and Chinese, from a speech-to-text transcript or from the keyword
 * spotter's tags. Anything that is not clearly an answer is null, so the card (or the AI, in
 * hybrid) decides. Pure Java so the rules are table-tested on the JVM.
 */
public final class BriefingAnswers {

    public static final class Answer {
        /** {@link TripBriefingAsk#ANSWER_YES}, {@link TripBriefingAsk#ANSWER_NO} or {@link TripBriefingAsk#ANSWER_LATER}. */
        public final String answer;
        /** The app the driver named when it is offered, else null. */
        public final String app;
        /** The app the driver named, offered or not, else null. */
        public final String named;

        Answer(String answer, String app, String named) {
            this.answer = answer;
            this.app = app;
            this.named = named;
        }

        @Override
        public String toString() {
            if (named == null) return answer;
            return answer + " " + named + (app == null ? " (not offered)" : "");
        }
    }

    private static final int YES = 1;
    private static final int NO = 2;
    private static final int APP = 3;
    /** "not", "don't", 不…: negates the app or the yes that follows, else it is a no. */
    private static final int NOT = 4;
    /** "I don't know", "maybe": no answer at all. */
    private static final int UNSURE = 5;
    private static final int CONN = 6;
    /** The assistant's own name: the OverDrive app only right after use/in/open…, else no answer. */
    private static final int WAKE = 7;
    /** Words with no say in the answer ("um", "please", 吧). */
    private static final int FILLER = 8;
    /** "to", 去: only in front of the destination's own words ("yes, to work"). */
    private static final int TO = 9;
    private static final int DEST = 10;
    private static final int UNKNOWN = 11;
    /** A spotted place (回家): the reply names where the driver is going. */
    private static final int PLACE = 12;


    private static final class Phrase {
        final String[] words;
        final int kind;
        final String app;
        final boolean strong;
        /** A likely mishearing ("ways" for Waze): it only counts in an utterance with no other words. */
        final boolean loose;

        Phrase(String[] words, int kind, String app, boolean strong, boolean loose) {
            this.words = words;
            this.kind = kind;
            this.app = app;
            this.strong = strong;
            this.loose = loose;
        }
    }

    private static final class Item {
        int kind;
        final String app;
        final boolean strong;
        final boolean loose;
        final int start;
        final int end;
        boolean negated;

        Item(Phrase p, int start, int end) {
            this.kind = p.kind;
            this.app = p.app;
            this.strong = p.strong;
            this.loose = p.loose;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Tok {
        final String w;
        final boolean han;
        final int clause;

        Tok(String w, boolean han, int clause) {
            this.w = w;
            this.han = han;
            this.clause = clause;
        }
    }

    private static final Map<String, List<Phrase>> PHRASES = new HashMap<>();
    /** No phrases that only put it off. */
    private static final java.util.Set<String> LATER = new java.util.HashSet<>(java.util.Arrays.asList(
            "later", "maybe later", "not now", "not today", "not yet", "not right now", "not at the moment",
            "another time", "some other time", "next time", "maybe another time"));

    static {
        String waze = TripBriefingAsk.APP_WAZE;
        String gmaps = TripBriefingAsk.APP_GMAPS;
        String car = TripBriefingAsk.APP_CAR;
        String od = TripBriefingAsk.APP_OVERDRIVE;
        en(YES, null, true, false, "yes", "yeah", "yea", "yep", "yup", "sure", "yes please", "yes please do", "yes do", "yeah sure",
                "yes sir", "sure thing", "of course", "absolutely", "definitely", "affirmative");
        en(YES, null, false, false, "ok", "okay", "okey", "okie", "alright", "all right", "go ahead",
                "lets go", "let us go", "do it", "please do", "sounds good", "start", "start it", "take me",
                "start navigation", "start directions", "navigate", "take me there", "go for it",
                "correct", "uh huh", "uhhuh", "mhm", "mm hmm", "mmhmm", "perfect", "great", "cool", "sounds great",
                "that would be great", "thatd be great", "that would be nice", "thatd be nice", "i would", "i do");
        // "Go home" names another destination: alone only.
        en(YES, null, false, true, "go");
        // Any of these makes the reply a no; a wrong "no" costs a tap, a wrong "yes" a drive.
        en(NO, null, true, false, "no", "nope", "nah", "no thanks", "no thank you", "not now", "no need", "no way",
                "nah thanks", "no no", "no problem");
        en(NO, null, false, false, "cancel", "cancel that", "stop", "skip", "skip it", "later", "maybe later",
                "not today", "not yet", "not right now", "not at the moment", "another time", "some other time",
                "next time", "maybe another time", "not really", "never mind", "nevermind", "not needed", "not interested",
                "dismiss", "all good", "i know the way", "im good", "i am good", "im fine", "i am fine", "im ok",
                "i am ok", "im okay", "i am okay", "im alright", "i am alright", "im all right", "i am all right");
        en(NOT, null, false, false, "not", "dont", "do not", "never", "without", "cant", "wont");
        en(UNSURE, null, false, false, "maybe", "unsure", "let me think", "hold on", "hang on", "hold up", "wait",
                "one second", "one sec", "in a bit", "in a minute", "i think so", "probably", "what", "sorry", "pardon",
                "huh", "minute", "minutes", "moment", "sec", "secs", "second", "seconds", "after", "first", "soon",
                "let me check", "let me see", "dunno", "i guess");
        en(APP, waze, false, false, "waze");
        // How on-device speech-to-text hears "Waze" (measured on synthetic voices).
        en(APP, waze, false, true, "ways", "waves", "weighs", "wades", "waise", "wase", "wayz", "waize", "wazes",
                "wasy", "wazee", "wazy");
        en(APP, gmaps, false, false, "google maps", "google map", "googlemaps", "googlemap", "gmaps", "g maps",
                "gee maps", "google navigation");
        // Everyday words too ("google it", "open maps"): only right after use/in or a yes.
        en(APP, gmaps, false, true, "google", "googles", "goggle", "gogle", "goggle maps", "google mabs",
                "google naps", "google mats", "goo maps");
        en(APP, car, false, false, "car navigation", "car nav", "car navi", "car navigator", "cars navigation",
                "built in navigation", "byd navigation", "in car navigation", "onboard navigation", "car system");
        en(APP, car, false, true, "con navigation", "cong navigation", "con nav", "cornav", "carnav");
        en(WAKE, od, false, false, "over drive");
        en(WAKE, od, false, false, WakePhraseMatcher.WAKE_SPELLINGS);
        en(CONN, null, false, false, "in", "on", "with", "use", "using", "via", "open", "through");
        en(FILLER, null, false, false, "um", "uh", "er", "erm", "ah", "oh", "hmm", "hm", "mm", "well",
                "please", "thanks", "thank", "you", "the", "a", "an", "app", "it", "lets", "let", "us", "just",
                "then", "and", "my", "our");
        en(TO, null, false, false, "to");
        zh(YES, null, true, false, "好的", "好啊", "好吧", "好呀", "好嘞", "是的", "对的", "對的", "可以", "当然", "當然",
                "没问题", "沒問題", "行啊", "需要", "嗯嗯");
        zh(YES, null, false, false, "开始", "開始", "开始导航", "開始導航", "导航", "導航", "走吧", "出发", "出發", "来吧",
                "來吧", "开始吧", "開始吧", "带我去", "帶我去");
        // Single syllables are also inside ordinary sentences (好冷, 要多久): alone only.
        zh(YES, null, false, true, "好", "是", "对", "對", "行", "要", "嗯", "走");
        zh(NO, null, true, false, "不用了", "不必", "不必了", "取消", "算了", "不了", "没必要", "沒必要", "暂时不用",
                "暫時不用", "现在不用", "現在不用", "不去", "不用谢谢", "不用謝謝", "不行", "不好");
        zh(NO, null, false, false, "停", "停止");
        zh(NOT, null, true, false, "不用", "不要", "不需要");
        zh(NOT, null, false, false, "不", "别", "別", "没", "沒");
        zh(UNSURE, null, false, false, "不确定", "不確定", "也许", "也許", "可能", "再说", "再說", "等一下", "等等", "想想");
        zh(APP, waze, false, false, "位智");
        zh(APP, gmaps, false, false, "谷歌地图", "谷歌地圖", "谷歌导航", "谷歌導航", "谷歌");
        zh(APP, car, false, false, "车机导航", "車機導航", "车载导航", "車載導航", "原车导航", "原車導航", "自带导航",
                "自帶導航", "比亚迪导航", "比亞迪導航", "车机", "車機", "车载", "車載", "汽车导航", "汽車導航",
                "本车导航", "本車導航");
        zh(CONN, null, false, false, "用", "使用", "在", "打开", "打開", "开", "開");
        zh(FILLER, null, false, false, "吧", "啊", "呀", "了", "的", "请", "請", "哦", "喔", "噢", "呃", "额", "一下",
                "那", "那就", "就", "谢谢", "謝謝", "帮我", "幫我", "给我", "給我");
        zh(TO, null, false, false, "去", "到");
    }

    /**
     * Keyword-spotter lines for the answer window, tokenized like tools/voice/gen_kws_keywords.py
     * (CMU phones; pinyin initials and toned finals, with the 不 sandhi variant). The tag prefix is
     * the meaning: y_, n_, or the app id. Two-phone English words (no, go, yeah) are left to
     * speech-to-text: as keywords they fire inside "know", "going", "yeah but…". A line that starts
     * another fires first and resets the stream, so only one of such a pair is kept (好 not 好的,
     * 谷歌地图 not 谷歌).
     */
    private static final String[] EN_LINES = {
            "Y EH1 S #0.30 @y_yes",
            "Y EH1 P #0.30 @y_yep",
            "Y AH1 P #0.30 @y_yup",
            "SH UH1 R #0.30 @y_sure",
            "OW2 K EY1 #0.30 @y_okay",
            "OW1 K EY1 #0.30 @y_ok",
            "P L IY1 Z #0.30 @y_please",
            "Y EH1 S P L IY1 Z #0.25 @y_yesplease",
            "L EH1 T S G OW1 #0.25 @y_letsgo",
            "D UW1 IH1 T #0.30 @y_doit",
            "G OW1 AH0 HH EH1 D #0.25 @y_goahead",
            "N OW1 #0.40 @n_no",
            "N AA1 T #0.35 @n_not",
            "D OW1 N T #0.35 @n_dont",
            "N OW1 P #0.30 @n_nope",
            "N OW1 TH AE1 NG K S #0.25 @n_nothanks",
            "N OW1 TH AE1 NG K Y UW1 #0.25 @n_nothankyou",
            "N AA1 T N AW1 #0.30 @n_notnow",
            "N AA1 T T AH0 D EY1 #0.25 @n_nottoday",
            "S T AA1 P #0.30 @n_stop",
            "W EY1 Z #0.30 @waze_waze",
            "G UW1 G AH0 L M AE1 P S #0.25 @gmaps_googlemaps",
            "G UW1 G AH0 L M AE1 P #0.25 @gmaps_googlemap",
            "M AE1 P S #0.30 @gmaps_maps",
            "K AA1 R N AE1 V AH0 G EY1 SH AH0 N #0.20 @car_carnavigation",
            "K AA1 R N AE2 V AH0 G EY1 SH AH0 N #0.20 @car_carnavigation",
            "K AA1 R N AE1 V #0.30 @car_carnav",
    };

    private static final String[] ZH_LINES = {
            "h ǎo #0.30 @y_hao",
            "sh ì d e #0.30 @y_shide",
            "k ě y ǐ #0.30 @y_keyi",
            "k āi sh ǐ #0.30 @y_kaishi",
            "d ǎo h áng #0.30 @y_daohang",
            "z ǒu b a #0.30 @y_zouba",
            "ch ū f ā #0.30 @y_chufa",
            "m éi w èn t í #0.25 @y_meiwenti",
            // A bare 不 or 别 makes the reply a no (不好, 不是的, 不可以, 别导航); 不 also covers 不用 and 不要.
            "b ù #0.35 @n_bu",
            "b ú #0.35 @n_bu",
            "b ié #0.35 @n_bie",
            "x iàn z ài b ù y òng #0.25 @n_xianzaibuyong",
            "x iàn z ài b ú y òng #0.25 @n_xianzaibuyong",
            "z àn sh í b ù y òng #0.25 @n_zanshibuyong",
            "z àn sh í b ú y òng #0.25 @n_zanshibuyong",
            "q ǔ x iāo #0.30 @n_quxiao",
            "s uàn l e #0.30 @n_suanle",
            "t íng zh ǐ #0.30 @n_tingzhi",
            // Hedges: no answer, and an AI's yes to one isn't taken either.
            "k ě n éng #0.30 @u_keneng",
            "y ě x ǔ #0.30 @u_yexu",
            "y é x ǔ #0.30 @u_yexu",
            "z ài sh uō #0.30 @u_zaishuo",
            "d ěng y í x ià #0.25 @u_dengyixia",
            "d ěng d ěng #0.30 @u_dengdeng",
            "d éng d ěng #0.30 @u_dengdeng",
            "x iǎng x iǎng #0.30 @u_xiangxiang",
            "x iáng x iǎng #0.30 @u_xiangxiang",
            "d ěng h uì #0.30 @u_denghui",
            "d ěng x ià #0.30 @u_dengxia",
            "y í h uì #0.30 @u_yihui",
            "w ǎn d iǎn #0.30 @u_wandian",
            "d āi h uì #0.30 @u_daihui",
            "g uò h uì #0.30 @u_guohui",
            "h uí t óu #0.30 @u_huitou",
            "w ǎn x iē #0.30 @u_wanxie",
            "sh āo h òu #0.30 @u_shaohou",
            // 好吗 asks back; a map app the car doesn't offer isn't a choice of these.
            "m a #0.35 @u_ma",
            "g āo d é #0.30 @u_gaode",
            "b ǎi d ù #0.30 @u_baidu",
            // Another place: no answer to these directions (hybrid's AI isn't told the destination).
            "h uí j iā #0.30 @p_huijia",
            // 位智 alone sounds like 位置 ("location"); bare 地图 is any map app.
            "y òng w èi zh ì #0.30 @waze_yongweizhi",
            "d ǎ k āi w èi zh ì #0.25 @waze_dakaiweizhi",
            "g ǔ g ē d ì t ú #0.25 @gmaps_gugeditu",
            "ch ē j ī d ǎo h áng #0.25 @car_chejidaohang",
            "ch ē z ài d ǎo h áng #0.25 @car_chezaidaohang",
            "y uán ch ē d ǎo h áng #0.25 @car_yuanchedaohang",
            // Said in English by Chinese speakers.
            "OW2 K EY1 #0.30 @y_okay",
            "OW1 K EY1 #0.30 @y_ok",
            "W EY1 Z #0.30 @waze_waze",
            "G UW1 G AH0 L M AE1 P S #0.25 @gmaps_googlemaps",
    };


    /** More speech than this before a reply's first hit, or after its last, makes it a sentence, not an answer. */
    static final long HIT_LEAD_MAX_MS = 170;
    /**
     * Speech the spotted lines don't account for: one particle (好的, 好吧) fits, a word the spotter has
     * no line for (好，带我去机场，导航 or 好热) doesn't.
     */
    static final long HIT_UNCOVERED_MAX_MS = 150;

    /** One keyword-spotter hit: speech before it, and after it (informational), in the reply; -1 when unknown. */
    public static final class Hit {
        public final String tag;
        public final long leadMs;
        public final long tailMs;

        public Hit(String tag, long leadMs, long tailMs) {
            this.tag = tag;
            this.leadMs = leadMs;
            this.tailMs = tailMs;
        }
    }

    private BriefingAnswers() {
    }

    /** The answer window's keyword lines for a command language ("en", "zh-CN", "zh-TW"). */
    public static List<String> keywordLines(String language) {
        boolean zh = language != null && language.toLowerCase(Locale.ROOT).startsWith("zh");
        return Collections.unmodifiableList(Arrays.asList(zh ? ZH_LINES : EN_LINES));
    }

    /** The answer a transcript gives, or null when it is not clearly one. */
    public static Answer fromTranscript(String text, List<String> offered) {
        return fromTranscript(text, offered, null);
    }

    /**
     * A wrong yes starts a drive, so a yes is only a short, plain reply: yes words and at most one app
     * ("Sure, in Waze", "Google Maps please"). Any no or negation is a no; anything else (another place,
     * a sentence, a hedge) is null, for the card or the AI. [destination]'s words may be echoed ("yes, to work").
     */
    public static Answer fromTranscript(String text, List<String> offered, String destination) {
        List<Tok> toks = tokenize(text);
        if (toks.isEmpty() || addressesOtherAssistant(toks)) return null;
        boolean[] covered = new boolean[toks.size()];
        List<Item> items = scan(toks, true, covered);
        // the first refusal decides: "not now", "maybe later" put it off, and so does a no that only says when ("no,
        // not now", "nah, later"); "no, I'm going to the gym later" is still a no
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.kind != NO && it.kind != NOT) continue;
            boolean later = it.kind == NO && (LATER.contains(words(toks, it)) || laterRightAfter(items, i, toks));
            return new Answer(later ? TripBriefingAsk.ANSWER_LATER : TripBriefingAsk.ANSWER_NO, null, null);
        }
        for (Item it : items) {
            if (it.kind == UNSURE) return null;
        }
        List<Item> seq = withUnknowns(items, toks, covered, destinationWords(destination));
        dropLeadingWake(seq);
        int answers = countAnswers(seq);
        boolean anyYes = false;
        String named = null;
        for (int i = 0; i < seq.size(); i++) {
            Item it = seq.get(i);
            Item next = nextNonFiller(seq, i);
            switch (it.kind) {
                case FILLER:
                case DEST:
                    break;
                case TO:
                    if (next == null || next.kind != DEST) return null;
                    break;
                case CONN:
                    if (next == null || (next.kind != APP && next.kind != WAKE && next.kind != CONN)) return null;
                    break;
                case WAKE:
                    // Only "use/in/open OverDrive" names the app; the name anywhere else isn't an answer.
                    if (!afterConn(seq, i)) return null;
                    if (named != null && !named.equals(it.app)) return null;
                    named = it.app;
                    break;
                case APP:
                    // A sound-alike or everyday word ("ways", "maps") only counts right after use/in or a yes.
                    if (it.loose && !afterConn(seq, i) && !afterYes(seq, i)) return null;
                    if (named != null && !named.equals(it.app)) return null;  // "Waze or Google Maps?"
                    named = it.app;
                    break;
                case YES:
                    if (it.loose && !alone(seq, it, toks) && !firmAnswer(seq, it)) return null;
                    anyYes = true;
                    break;
                default:
                    return null;
            }
        }
        if (!anyYes && named == null) return null;
        // "Okay, um…" trails off into something else; "okay thanks" / 好的谢谢 is as often "no need".
        Item last = seq.isEmpty() ? null : seq.get(seq.size() - 1);
        if (last != null && last.kind == FILLER && (HESITATION.contains(toks.get(last.start).w)
                || CONTINUES.contains(toks.get(last.start).w))) return null;
        // a named app is a choice, thanks or not ("Waze, thanks")
        if (saysThanks(toks) && !strongEnglishYes(seq, toks) && named == null) return null;
        return yes(named, offered);
    }

    private static final Set<String> HESITATION = new HashSet<>(Arrays.asList("um", "uh", "er", "erm", "hmm", "hm", "mm"));
    /** "Okay, and…", "Sure, just…": the reply was cut off mid-sentence. */
    private static final Set<String> CONTINUES = new HashSet<>(Arrays.asList("and", "just", "let", "us", "well",
            "the", "a", "an"));
    /** All a lone "go" (or 好, 对…) may come with. */
    private static final Set<String> ALONE_OK = new HashSet<>(Arrays.asList("please", "吧", "啊", "呀"));

    /** A lone "go" ("go please", "go to work"): "let it go" is not an answer. */
    private static boolean alone(List<Item> seq, Item yes, List<Tok> toks) {
        for (Item it : seq) {
            if (it == yes || it.kind == TO || it.kind == DEST) continue;
            if (it.kind != FILLER || !ALONE_OK.contains(toks.get(it.start).w)) return false;
        }
        return true;
    }

    /** "Yes, go", "go with Waze": another yes or an app makes one "go" part of the answer ("go Waze go" isn't). */
    private static boolean firmAnswer(List<Item> seq, Item yes) {
        boolean firm = false;
        for (Item it : seq) {
            if (it == yes) continue;
            if (it.loose && it.kind == YES) return false;
            if (!it.loose && (it.kind == YES || it.kind == APP)) firm = true;
        }
        return firm;
    }
    private static final Set<String> THANKS = new HashSet<>(Arrays.asList("thanks", "thank", "谢", "謝"));

    private static boolean saysThanks(List<Tok> toks) {
        for (Tok t : toks) {
            if (THANKS.contains(t.w)) return true;
        }
        return false;
    }

    private static boolean strongEnglishYes(List<Item> seq, List<Tok> toks) {
        for (Item it : seq) {
            if (it.kind == YES && it.strong && !toks.get(it.start).han) return true;
        }
        return false;
    }

    /** As {@link #fromHits(List, List, long)} for a reply with nothing but the hits in it. */
    // A later phrase straight after a plain no, and nothing but fillers around it ("no thanks, maybe later"; not
    // "no, not now, take me to the gym").
    private static boolean laterRightAfter(List<Item> items, int i, List<Tok> toks) {
        int end = items.get(i).end;
        boolean later = false;
        for (int j = i + 1; j < items.size(); j++) {
            Item n = items.get(j);
            if (n.start != end) return false;
            end = n.end;
            if (n.kind == FILLER) continue;
            if (later || n.kind != NO || !LATER.contains(words(toks, n))) return false;
            later = true;
        }
        return later && end == toks.size();
    }

    private static String words(List<Tok> toks, Item it) {
        StringBuilder b = new StringBuilder();
        for (int i = it.start; i < it.end && i < toks.size(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(toks.get(i).w);
        }
        return b.toString();
    }

    public static Answer fromHits(List<Hit> hits, List<String> offered) {
        return fromHits(hits, offered, 0L);
    }

    /**
     * The answer the keyword-spotter hits give (in spoken order), or null. [uncoveredMs] is the reply's
     * speech outside every hit (-1 when unknown): the spotter can't hear the words it has no line for.
     */
    public static Answer fromHits(List<Hit> hits, List<String> offered, long uncoveredMs) {
        if (hits == null || hits.isEmpty()) return null;
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) {
            if (h != null && tagPhrase(h.tag) != null) kept.add(h);
        }
        if (kept.isEmpty()) return null;
        // The spotter can't tell "不用导航" from "导航": any no heard makes it a no.
        for (Hit h : kept) {
            if (tagPhrase(h.tag).kind == NO) return new Answer(TripBriefingAsk.ANSWER_NO, null, null);
        }
        for (Hit h : kept) {
            int kind = tagPhrase(h.tag).kind;
            if (kind == UNSURE || kind == PLACE) return null;
        }
        // An answer is the whole reply: words the spotter didn't hear make it a sentence.
        long lead = kept.get(0).leadMs;
        if (lead < 0 || lead > HIT_LEAD_MAX_MS || uncoveredMs < 0 || uncoveredMs > HIT_UNCOVERED_MAX_MS) return null;
        if (kept.size() > 3) return null;
        for (Hit h : kept) {
            if (h.leadMs < 0) return null;
        }
        boolean anyYes = false;
        String named = null;
        for (Hit h : kept) {
            Phrase p = tagPhrase(h.tag);
            if (p.kind == YES) anyYes = true;
            if (p.kind == APP) {
                if (named != null && !named.equals(p.app)) return null;
                named = p.app;
            }
        }
        return anyYes || named != null ? yes(named, offered) : null;
    }

    /** A spotted no or hedge (不, 可能, "cancel"): an AI's yes after one isn't accepted. */
    public static boolean isHedge(String tag) {
        Phrase p = tagPhrase(tag);
        return p != null && (p.kind == NO || p.kind == UNSURE);
    }

    /** A spotted line that makes the reply a no. */
    public static boolean isNo(String tag) {
        Phrase p = tagPhrase(tag);
        return p != null && p.kind == NO;
    }

    /** A spotted place (回家). */
    public static boolean isPlace(String tag) {
        Phrase p = tagPhrase(tag);
        return p != null && p.kind == PLACE;
    }

    /**
     * "Take me to the gym", "yes, home": the reply names a place that isn't [destination], so it is
     * no yes to these directions, whatever an AI that wasn't told the destination made of it.
     */
    public static boolean namesPlace(String text, String destination) {
        List<Tok> toks = tokenize(text);
        if (toks.isEmpty()) return false;
        boolean[] covered = new boolean[toks.size()];
        List<Item> seq = withUnknowns(scan(toks, true, covered), toks, covered, destinationWords(destination));
        for (int i = 0; i < seq.size(); i++) {
            Item it = seq.get(i);
            String w = toks.get(it.start).w;
            if (it.kind == UNKNOWN && PLACE_WORDS.contains(w)) return true;
            Item next = nextNonFiller(seq, i);
            if ((it.kind == TO || PLACE_LEADS.contains(w)) && next != null && next.kind == UNKNOWN) return true;
        }
        return false;
    }

    private static final Set<String> PLACE_WORDS = new HashSet<>(Arrays.asList("home", "house", "work", "office",
            "gym", "school", "college", "university", "airport", "station", "mall", "store", "shop", "supermarket",
            "market", "hospital", "clinic", "restaurant", "cafe", "coffee", "park", "beach", "church", "temple",
            "mosque", "hotel", "downtown", "city", "town", "mom", "mum", "moms", "mums", "dad", "dads", "parents"));
    private static final Set<String> PLACE_LEADS = new HashSet<>(Arrays.asList("go", "take", "navigate", "drive",
            "directions", "route", "head", "heading", "going"));

    /** Hedges and refusals ("maybe", "I'm not sure", "no", a bare "hmm"): an AI's yes to such a reply isn't accepted either. */
    public static boolean hedges(String text) {
        List<Tok> toks = tokenize(text);
        if (toks.isEmpty()) return false;
        List<Item> items = scan(toks, true, new boolean[toks.size()]);
        boolean onlyHesitation = true;
        for (Tok t : toks) onlyHesitation &= HESITATION.contains(t.w);
        // "mm hmm" is a yes phrase
        for (Item it : items) onlyHesitation &= it.kind != YES;
        if (onlyHesitation) return true;
        for (Item it : items) {
            if (it.kind == NO || it.kind == NOT || it.kind == UNSURE) return true;
        }
        return false;
    }

    // ── Rules ───────────────────────────────────────────────────────────

    private static Answer yes(String named, List<String> offered) {
        String app = named != null && offered != null && offered.contains(named) ? named : null;
        return new Answer(TripBriefingAsk.ANSWER_YES, app, named);
    }

    /** "Hey Google" / "OK Google" is for the phone's assistant ("OK, Google Maps" is an answer). */
    private static boolean addressesOtherAssistant(List<Tok> toks) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            String w = toks.get(i).w;
            if (!w.equals("hey") && !w.equals("ok") && !w.equals("okay")) continue;
            if (!toks.get(i + 1).w.equals("google")) continue;
            String next = i + 2 < toks.size() ? toks.get(i + 2).w : "";
            if (!next.equals("maps") && !next.equals("map") && !next.equals("navigation")) return true;
        }
        return false;
    }

    /** Every token as an item, in order: uncovered words are UNKNOWN, or DEST when they echo the destination. */
    private static List<Item> withUnknowns(List<Item> items, List<Tok> toks, boolean[] covered, Set<String> own) {
        List<Item> out = new ArrayList<>();
        int k = 0;
        for (int i = 0; i < toks.size(); ) {
            if (k < items.size() && items.get(k).start == i) {
                Item it = items.get(k++);
                out.add(it);
                i = it.end;
                continue;
            }
            if (!covered[i]) out.add(new Item(own.contains(toks.get(i).w) ? DEST_PHRASE : UNKNOWN_PHRASE, i, i + 1));
            i++;
        }
        return out;
    }

    /** "OverDrive, yes": the assistant's name said first (after any filler) is the driver addressing it. */
    private static void dropLeadingWake(List<Item> seq) {
        int i = 0;
        while (i < seq.size() && seq.get(i).kind == FILLER) i++;
        if (i >= seq.size() || seq.get(i).kind != WAKE) return;
        while (i < seq.size() && (seq.get(i).kind == WAKE || seq.get(i).kind == FILLER)) i++;
        seq.subList(0, i).clear();
    }

    private static Item nextNonFiller(List<Item> seq, int i) {
        for (int j = i + 1; j < seq.size(); j++) {
            if (seq.get(j).kind != FILLER) return seq.get(j);
        }
        return null;
    }

    private static Item prevNonFiller(List<Item> seq, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (seq.get(j).kind != FILLER) return seq.get(j);
        }
        return null;
    }

    private static boolean afterConn(List<Item> seq, int i) {
        Item p = prevNonFiller(seq, i);
        return p != null && p.kind == CONN;
    }

    private static boolean afterYes(List<Item> seq, int i) {
        Item p = prevNonFiller(seq, i);
        return p != null && p.kind == YES;
    }

    private static int countAnswers(List<Item> seq) {
        int n = 0;
        for (Item it : seq) {
            if (it.kind == YES || it.kind == APP || it.kind == WAKE) n++;
        }
        return n;
    }

    private static Set<String> destinationWords(String destination) {
        Set<String> out = new HashSet<>();
        for (Tok t : tokenize(destination)) out.add(t.w);
        // what drivers call Home and Work
        if (out.contains("work")) out.addAll(Arrays.asList("office", "job"));
        if (out.contains("home")) out.add("house");
        return out;
    }

    // ── Scanning ────────────────────────────────────────────────────────

    /** Longest phrase first at each token; marks every token a phrase (or connector) covers. */
    private static List<Item> scan(List<Tok> toks, boolean allowLoose, boolean[] covered) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < toks.size(); ) {
            List<Phrase> candidates = PHRASES.get(toks.get(i).w);
            Phrase best = null;
            if (candidates != null) {
                for (Phrase p : candidates) {
                    if (p.loose && !allowLoose) continue;
                    if (best != null && p.words.length <= best.words.length) continue;
                    if (matchesAt(toks, i, p)) best = p;
                }
            }
            if (best == null) best = overSplit(toks, i, allowLoose);
            if (best == null) {
                i++;
                continue;
            }
            int end = i + best.words.length;
            for (int k = i; k < end; k++) covered[k] = true;
            items.add(new Item(best, i, end));
            i = end;
        }
        return items;
    }

    private static final Phrase OVER_SPLIT = new Phrase(new String[] {"", ""}, WAKE,
            TripBriefingAsk.APP_OVERDRIVE, false, true);
    private static final Phrase DEST_PHRASE = new Phrase(new String[] {""}, DEST, null, false, false);
    private static final Phrase UNKNOWN_PHRASE = new Phrase(new String[] {""}, UNKNOWN, null, false, false);

    /** "offer drive", "ober drive": the split spellings the wake check accepts, as a loose OverDrive. */
    private static Phrase overSplit(List<Tok> toks, int i, boolean allowLoose) {
        if (!allowLoose || i + 1 >= toks.size() || toks.get(i).han || toks.get(i + 1).han) return null;
        return WakePhraseMatcher.OVER_LIKE.contains(toks.get(i).w)
                && WakePhraseMatcher.SECOND.matcher(toks.get(i + 1).w).matches() ? OVER_SPLIT : null;
    }

    private static boolean matchesAt(List<Tok> toks, int i, Phrase p) {
        if (i + p.words.length > toks.size()) return false;
        boolean han = isHan(p.words[0]);
        for (int k = 0; k < p.words.length; k++) {
            Tok t = toks.get(i + k);
            if (t.han != han || !t.w.equals(p.words[k])) return false;
        }
        return true;
    }


    /**
     * Lower-case words (apostrophes dropped: "let's" is "lets", "car's" is "cars") and single
     * Chinese characters; punctuation starts a new clause.
     */
    private static List<Tok> tokenize(String text) {
        List<Tok> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        int clause = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\'' || c == '’') continue;
            if (Character.isLetterOrDigit(c) && !isHan(c)) {
                word.appendCodePoint(c);
                continue;
            }
            if (word.length() > 0) {
                out.add(new Tok(word.toString(), false, clause));
                word.setLength(0);
            }
            if (isHan(c)) {
                out.add(new Tok(new String(Character.toChars(c)), true, clause));
            } else if (",.;!?，。；！？、".indexOf(c) >= 0) {
                clause++;
            }
        }
        if (word.length() > 0) out.add(new Tok(word.toString(), false, clause));
        return out;
    }

    private static boolean isHan(int c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static boolean isHan(String w) {
        return !w.isEmpty() && isHan(w.codePointAt(0));
    }

    private static Phrase tagPhrase(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (LocalCommandSpotter.PERSONAL_WAKE_TAG.equals(t) || "overdrive".equals(t)) return null;
        // keywords-default.txt rides along in every stream.
        if ("cancel".equals(t)) return new Phrase(new String[] {t}, NO, null, true, false);
        int u = t.indexOf('_');
        String kind = u < 0 ? "" : t.substring(0, u);
        switch (kind) {
            case "y":
                return new Phrase(new String[] {t}, YES, null, true, false);
            case "n":
                return new Phrase(new String[] {t}, NO, null, true, false);
            case "u":
                return new Phrase(new String[] {t}, UNSURE, null, false, false);
            case "p":
                return new Phrase(new String[] {t}, PLACE, null, false, false);
            case TripBriefingAsk.APP_WAZE:
            case TripBriefingAsk.APP_GMAPS:
            case TripBriefingAsk.APP_CAR:
                return new Phrase(new String[] {t}, APP, kind, false, false);
            default:
                return null;
        }
    }

    private static void en(int kind, String app, boolean strong, boolean loose, String... phrases) {
        for (String p : phrases) add(new Phrase(p.split(" "), kind, app, strong, loose));
    }

    private static void zh(int kind, String app, boolean strong, boolean loose, String... phrases) {
        for (String p : phrases) {
            String[] chars = new String[p.codePointCount(0, p.length())];
            for (int i = 0, k = 0; i < p.length(); k++) {
                int c = p.codePointAt(i);
                chars[k] = new String(Character.toChars(c));
                i += Character.charCount(c);
            }
            add(new Phrase(chars, kind, app, strong, loose));
        }
    }

    private static void add(Phrase p) {
        PHRASES.computeIfAbsent(p.words[0], k -> new ArrayList<>()).add(p);
    }
}
