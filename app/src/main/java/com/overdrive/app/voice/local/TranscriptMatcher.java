package com.overdrive.app.voice.local;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a speech-to-text transcript onto {@link CommandCatalog} phrases. The transcript is cut into
 * clauses (punctuation, "and", "but", "then", "so"); inside a clause a phrase must appear word for
 * word, or (3+ words) within one edit in five where the edit is never a direction, amount or seat
 * word. Negations scope to their clause, corrections ("actually no") replace the previous clause,
 * and results come back in spoken order with their word positions.
 */
final class TranscriptMatcher {

    static final class Result {
        /** In spoken order; each match carries its {@code start} word index. */
        final List<LocalCommandSpotter.Match> matches;
        /** Transcript words outside every matched phrase, fillers and clause boundaries excluded. */
        final int extraWords;
        /** 1 when a leading wake-word tail ("…ive" heard as "life") was dropped, else 0. */
        final int wakeTail;

        Result(List<LocalCommandSpotter.Match> matches, int extraWords, int wakeTail) {
            this.matches = matches;
            this.extraWords = extraWords;
            this.wakeTail = wakeTail;
        }
    }

    private static final float FUZZY_MIN = 0.8f;
    private static final int FUZZY_MIN_WORDS = 3;
    private static final int MAX_MATCHES = 2;
    /** Phrase of the synthetic match for a seat the car has no voice control for. */
    static final String UNSUPPORTED_SEAT_PHRASE = "<unsupported seat>";

    private static final Map<String, Integer> ONES = new java.util.HashMap<>();
    private static final Map<String, Integer> TENS = new java.util.HashMap<>();
    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList(
            "overdrive", "over", "drive", "hey", "hi", "ok", "okay", "please", "can", "could", "would",
            "will", "you", "the", "a", "an", "to", "me", "for", "and", "now", "just", "i", "want", "it",
            "is", "its", "so", "um", "uh", "thanks", "thank", "yeah", "yes"));
    // "don't turn on the AC" must not turn it on.
    private static final Set<String> NEGATIONS = new HashSet<>(Arrays.asList(
            "dont", "not", "never", "cant", "wont", "no"));
    // A fuzzy match may differ in filler words, never in direction or amount.
    private static final Set<String> POLARITY = new HashSet<>(Arrays.asList(
            "on", "off", "up", "down", "open", "close", "closed", "start", "stop", "enable", "disable",
            "increase", "decrease", "raise", "lower", "warmer", "cooler", "hotter", "colder", "higher",
            "more", "less", "max", "min"));
    // ...nor in which seat, side or level: "the seat heating" must not become "the passenger seat heating".
    static final Set<String> SLOT_WORDS = new HashSet<>(Arrays.asList(
            "driver", "passenger", "front", "rear", "back", "left", "right", "high", "low", "level",
            "one", "two", "1", "2", "max", "maximum", "min", "minimum"));
    private static final Set<String> SEAT_WORDS = new HashSet<>(Arrays.asList("driver", "passenger"));
    // Seats the car cannot control; said with a seat noun and a heat/vent word they become a refusal.
    private static final Set<String> UNSUPPORTED_SIDES = new HashSet<>(Arrays.asList(
            "rear", "back", "left", "right"));
    private static final Set<String> SEAT_NOUNS = new HashSet<>(Arrays.asList("seat", "seats"));
    private static final Set<String> HEAT_WORDS = new HashSet<>(Arrays.asList(
            "heat", "heating", "heater", "heated", "warm"));
    private static final Set<String> VENT_WORDS = new HashSet<>(Arrays.asList(
            "vent", "ventilation", "ventilate", "cooling", "cool"));
    // Clause boundaries; no catalog phrase contains one of these words.
    private static final Set<String> BOUNDARY_WORDS = new HashSet<>(Arrays.asList(
            "and", "but", "then", "so"));
    // At the start of a clause these replace the previous clause; longest first so "no actually" is one marker.
    static final List<String[]> CORRECTIONS = Collections.unmodifiableList(Arrays.asList(
            new String[] {"no", "actually"}, new String[] {"actually", "no"}, new String[] {"no", "wait"},
            new String[] {"scratch", "that"}, new String[] {"i", "mean"},
            new String[] {"actually"}, new String[] {"instead"}, new String[] {"wait"}, new String[] {"sorry"}));
    private static final Set<String> MARKER_WORDS = new HashSet<>();
    private static final Set<String> ANAPHORA_VERBS = new HashSet<>(Arrays.asList("turn", "switch", "put"));
    private static final Set<String> ANAPHORA_OBJECTS = new HashSet<>(Arrays.asList("it", "that"));
    private static final Pattern AC = Pattern.compile("\\ba\\.?\\s?c\\.?(?=\\W)");
    private static final Pattern DEGREES = Pattern.compile("(\\d+)\\s*°\\s*c?");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern PUNCTUATION = Pattern.compile("[,;.!?]+");
    // Shape of what the end of "OverDrive" becomes when it leaks into the command ("life", "right", "ive").
    private static final Pattern TAIL = Pattern.compile("^[a-z]{0,2}[aiy][a-z]{0,2}$");

    static {
        String[] ones = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
                "eighteen", "nineteen"};
        for (int i = 0; i < ones.length; i++) ONES.put(ones[i], i);
        ONES.put("oh", 0);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) TENS.put(tens[i], 20 + 10 * i);
        MARKER_WORDS.addAll(NEGATIONS);
        for (String[] c : CORRECTIONS) MARKER_WORDS.addAll(Arrays.asList(c));
    }

    /** A match plus the catalog entry it came from (null for the synthetic unsupported-seat match). */
    private static final class Found {
        final LocalCommandSpotter.Match match;
        final CommandCatalog.Entry entry;
        final String key;

        Found(LocalCommandSpotter.Match match, CommandCatalog.Entry entry) {
            this.match = match;
            this.entry = entry;
            this.key = entry != null ? entry.intent + Arrays.toString(entry.slots)
                    : match.intent + " " + match.slots.optString("seat");
        }
    }

    private final List<String[]> words = new ArrayList<>();
    private final List<Set<String>> wordSets = new ArrayList<>();
    private final List<CommandCatalog.Entry> entries = new ArrayList<>();
    /** Every word of every catalog phrase; a real command word is never a wake-word tail. */
    private final Set<String> vocabulary = new HashSet<>();

    /** English only: the catalog's other languages need a phonetic match, not a word match. */
    TranscriptMatcher() {
        Set<String> seen = new HashSet<>();
        for (CommandCatalog.Entry e : CommandCatalog.entries(CommandCatalog.EN)) {
            List<String> w = normalize(e.phrase);
            if (w.isEmpty() || !seen.add(w + " " + e.intent + " " + Arrays.toString(e.slots))) continue;
            words.add(w.toArray(new String[0]));
            wordSets.add(new HashSet<>(w));
            entries.add(e);
            vocabulary.addAll(w);
        }
    }

    /** Up to two non-overlapping phrases in spoken order; null when none is close enough. */
    Result match(String transcript) {
        return match(transcript, false);
    }

    /**
     * As {@link #match(String)}; with {@code afterWakeWord} the transcript may start with the end of
     * "OverDrive", and one such leading token is dropped instead of counted as an extra word.
     */
    Result match(String transcript, boolean afterWakeWord) {
        List<String> t = new ArrayList<>();
        List<int[]> clauses = segment(transcript == null ? "" : transcript, t);
        if (t.isEmpty()) return null;
        boolean[] used = new boolean[t.size()];
        for (int i = 0; i < t.size(); i++) used[i] = BOUNDARY_WORDS.contains(t.get(i));
        int wakeTail = 0;
        // Only when the buffer can still hold the end of "OverDrive" (STT-timed or re-armed wake): drop ONE leading,
        // out-of-vocabulary, tail-shaped token before it counts as an extra word.
        if (afterWakeWord && t.size() >= 3 && isWakeTail(t.get(0))) {
            used[0] = true;
            wakeTail = 1;
        }
        List<Found> out = new ArrayList<>();
        Set<String> meanings = new HashSet<>();
        // The last content clause: a correction drops it, "turn it off" refers to it.
        List<Found> previous = Collections.emptyList();
        int previousAt = 0;
        for (int[] clause : clauses) {
            int from = clause[0];
            int to = clause[1];
            int body = correctionEnd(t, from, to);
            boolean markerOnly = body == to || onlyMarkers(t, from, to);
            if (body > from || markerOnly) {
                int end = markerOnly ? to : body;
                for (int i = from; i < end; i++) used[i] = true;
                for (Found f : out.subList(previousAt, out.size())) meanings.remove(f.key);
                out.subList(previousAt, out.size()).clear();
                if (markerOnly) continue;
                from = body;
            }
            List<Found> found = matchClause(t, from, to, used, previous);
            previousAt = out.size();
            for (Found f : found) if (meanings.add(f.key)) out.add(f);
            previous = found;
        }
        if (out.isEmpty()) return null;
        out.sort((a, b) -> Integer.compare(a.match.start, b.match.start));
        List<LocalCommandSpotter.Match> matches = new ArrayList<>();
        for (Found f : out) if (matches.size() < MAX_MATCHES) matches.add(f.match);
        int extra = 0;
        for (int i = 0; i < t.size(); i++) if (!used[i] && !FILLERS.contains(t.get(i))) extra++;
        return new Result(Collections.unmodifiableList(matches), extra, wakeTail);
    }

    /** A short, vowel-centred token no catalog phrase, filler, marker or protected word uses. */
    private boolean isWakeTail(String w) {
        return !vocabulary.contains(w) && !FILLERS.contains(w) && !isProtected(w) && !MARKER_WORDS.contains(w)
                && !BOUNDARY_WORDS.contains(w) && w.length() <= 5 && TAIL.matcher(w).matches();
    }

    /** Words of the clause t[from, to) matched best-first; may be empty. */
    private List<Found> matchClause(List<String> t, int from, int to, boolean[] used, List<Found> previous) {
        List<Found> found = new ArrayList<>();
        int side = unsupportedSeatAt(t, from, to);
        if (side >= 0) {
            // The clause is about a seat the car cannot control: refuse it instead of guessing a front seat.
            Found refusal = negatedBefore(t, from, side) ? null : unsupportedSeat(t, from, to, side);
            if (refusal != null) found.add(refusal);
            for (int i = from; i < to; i++) used[i] = true;
            return found;
        }
        Set<String> seats = new HashSet<>();
        for (int i = from; i < to; i++) if (SEAT_WORDS.contains(t.get(i))) seats.add(t.get(i));
        while (found.size() < MAX_MATCHES) {
            int best = -1;
            float bestScore = 0f;
            boolean bestAgrees = false;
            int bestStart = 0;
            int bestLen = 0;
            boolean tied = false;
            Set<String> present = new HashSet<>();
            for (int i = from; i < to; i++) if (!used[i]) present.add(t.get(i));
            for (int p = 0; p < words.size(); p++) {
                String[] w = words.get(p);
                int overlap = 0;
                for (String s : wordSets.get(p)) if (present.contains(s)) overlap++;
                if (overlap * 2 < wordSets.get(p).size()) continue;
                float score = 0f;
                int start = -1;
                int span = w.length;
                for (int i = from; i + w.length <= to; i++) {
                    if (free(used, i, w.length) && equalAt(t, i, w)) {
                        score = 1f;
                        start = i;
                        break;
                    }
                }
                if (score < 1f) {
                    if (w.length < FUZZY_MIN_WORDS) continue;
                    for (int len = w.length - 1; len <= w.length + 1; len++) {
                        for (int i = from; len > 0 && i + len <= to; i++) {
                            if (!free(used, i, len)) continue;
                            float s = 1f - distance(t, i, len, w) / (float) Math.max(w.length, len);
                            if (s > score) {
                                score = s;
                                start = i;
                                span = len;
                            }
                        }
                    }
                    if (score < FUZZY_MIN || !protectedAgrees(t, start, span, w)) continue;
                }
                CommandCatalog.Entry e = entries.get(p);
                if (!e.isCancel() && negatedBefore(t, from, start)) continue;
                boolean agrees = slotAgrees(e, seats);
                int cmp = best < 0 ? 1 : compare(score, agrees, w.length, bestScore, bestAgrees, words.get(best).length);
                if (cmp > 0) {
                    best = p;
                    bestScore = score;
                    bestAgrees = agrees;
                    bestStart = start;
                    bestLen = span;
                    tied = false;
                } else if (cmp == 0 && !sameMeaning(e, entries.get(best))) {
                    tied = true;
                }
            }
            // Two different commands fit equally well: let the cloud or a retry decide.
            if (best < 0 || tied) break;
            for (int i = bestStart; i < bestStart + bestLen; i++) used[i] = true;
            CommandCatalog.Entry e = redirect(entries.get(best), seats);
            if (e == null) continue;
            LocalCommandSpotter.Match m = LocalCommandSpotter.matchOf(e, bestStart);
            if (m != null) found.add(new Found(m, e));
        }
        if (found.isEmpty()) {
            Found it = anaphora(t, from, to, used, previous);
            if (it != null) found.add(it);
        }
        return found;
    }

    /** Exact beats fuzzy, then a seat that matches the clause, then the shorter (less assumed) phrase. */
    private static int compare(float score, boolean agrees, int len, float bestScore, boolean bestAgrees, int bestLen) {
        if (score != bestScore) return score > bestScore ? 1 : -1;
        if (agrees != bestAgrees) return agrees ? 1 : -1;
        return Integer.compare(bestLen, len);
    }

    /**
     * Lower-cases, folds "a/c", cuts at punctuation, then number-folds each piece into {@code t} and
     * returns the [from, to) clause ranges; "and", "but", "then", "so" split clauses too.
     */
    private static List<int[]> segment(String text, List<String> t) {
        String s = " " + text.toLowerCase(Locale.ROOT).replace("a/c", " ac ") + " ";
        s = AC.matcher(s).replaceAll(" ac ");
        List<int[]> clauses = new ArrayList<>();
        for (String piece : PUNCTUATION.split(s)) {
            int from = t.size();
            for (String w : normalize(piece)) {
                if (BOUNDARY_WORDS.contains(w)) {
                    if (t.size() > from) clauses.add(new int[] {from, t.size()});
                    t.add(w);
                    from = t.size();
                } else {
                    t.add(w);
                }
            }
            if (t.size() > from) clauses.add(new int[] {from, t.size()});
        }
        return clauses;
    }

    static List<String> normalize(String text) {
        String s = " " + text.toLowerCase(Locale.ROOT).replace("a/c", " ac ") + " ";
        s = AC.matcher(s).replaceAll(" ac ");
        Matcher deg = DEGREES.matcher(s);
        s = deg.replaceAll("$1 degrees");
        s = NON_WORD.matcher(s.replace("'", "").replace("\u2019", "").replace("\u2018", "").replace("\u02bc", "")
                .replace("`", "")).replaceAll(" ");
        String[] raw = s.trim().split("\\s+");
        List<String> out = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            String w = raw[i];
            if (w.isEmpty()) continue;
            Integer tens = TENS.get(w);
            Integer ones = ONES.get(w);
            if (tens != null) {
                int v = tens;
                Integer next = i + 1 < raw.length ? ONES.get(raw[i + 1]) : null;
                if (next != null && next > 0 && next < 10) {
                    v += next;
                    i++;
                }
                out.add(Integer.toString(v));
            } else if (ones != null) {
                out.add(Integer.toString(ones));
            } else if (w.chars().allMatch(Character::isDigit)) {
                out.add(Integer.toString(Integer.parseInt(w.length() > 6 ? w.substring(0, 6) : w)));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    /** Index after the correction markers that open the clause; {@code from} when there are none. */
    private static int correctionEnd(List<String> t, int from, int to) {
        int i = from;
        boolean more = true;
        while (more) {
            more = false;
            for (String[] c : CORRECTIONS) {
                if (i + c.length <= to && equalAt(t, i, c)) {
                    i += c.length;
                    more = true;
                    break;
                }
            }
        }
        return i;
    }

    /** "no", "actually no", "no wait": a correction of what came before, not a negation of what follows. */
    private static boolean onlyMarkers(List<String> t, int from, int to) {
        boolean marker = false;
        for (int i = from; i < to; i++) {
            String w = t.get(i);
            if (MARKER_WORDS.contains(w)) marker = true;
            else if (!FILLERS.contains(w)) return false;
        }
        return marker;
    }

    /** Any negation word in the transcript ("don't", "not", "never", "no", ...). */
    static boolean saysNegation(String text) {
        if (text == null) return false;
        List<String> t = normalize(text);
        for (int i = 0; i < t.size(); i++) {
            if (!NEGATIONS.contains(t.get(i))) continue;
            // the emphatic "don't forget to ..."
            if ("dont".equals(t.get(i)) && i + 1 < t.size() && "forget".equals(t.get(i + 1))) continue;
            return true;
        }
        return false;
    }

    /** A negation word earlier in the clause, unless it is the emphatic "don't forget to ...". */
    private static boolean negatedBefore(List<String> t, int from, int start) {
        for (int i = from; i < start; i++) {
            String w = t.get(i);
            if (!NEGATIONS.contains(w)) continue;
            boolean dont = "dont".equals(w) || ("not".equals(w) && i > from && "do".equals(t.get(i - 1)));
            if (dont && i + 1 < start && "forget".equals(t.get(i + 1))) continue;
            return true;
        }
        return false;
    }

    /**
     * Index of the rear/back/left/right word naming a seat ("rear seat", "back seats") when the
     * clause also says heating or venting. The side word must sit right before the seat noun:
     * "back" in "turn the seat heating back on" is an adverb, not a seat.
     */
    private static int unsupportedSeatAt(List<String> t, int from, int to) {
        int side = -1;
        boolean climate = false;
        for (int i = from; i < to; i++) {
            String w = t.get(i);
            if (side < 0 && UNSUPPORTED_SIDES.contains(w) && i + 1 < to
                    && SEAT_NOUNS.contains(t.get(i + 1))) {
                side = i;
            }
            if (HEAT_WORDS.contains(w) || VENT_WORDS.contains(w)) climate = true;
        }
        return climate ? side : -1;
    }

    private static Found unsupportedSeat(List<String> t, int from, int to, int side) {
        boolean heat = false;
        boolean vent = false;
        boolean off = false;
        for (int i = from; i < to; i++) {
            String w = t.get(i);
            if (HEAT_WORDS.contains(w)) heat = true;
            if (VENT_WORDS.contains(w)) vent = true;
            if ("off".equals(w)) off = true;
        }
        String word = t.get(side);
        JSONObject slots = new JSONObject();
        try {
            slots.put("seat", "back".equals(word) ? "rear" : word);
            slots.put("level", off ? 0 : 2);
        } catch (JSONException e) {
            return null;
        }
        String intent = vent && !heat ? "seat.vent" : "seat.heat";
        return new Found(new LocalCommandSpotter.Match(intent, slots, UNSUPPORTED_SEAT_PHRASE, side), null);
    }

    /** The entry's seat must be the one the clause names; otherwise the same command for that seat. */
    private CommandCatalog.Entry redirect(CommandCatalog.Entry e, Set<String> seats) {
        int at = seatSlot(e);
        if (at < 0 || seats.size() != 1) return e;
        String spoken = seats.iterator().next();
        if (spoken.equals(e.slots[at])) return e;
        Object[] want = e.slots.clone();
        want[at] = spoken;
        return findEntry(e.intent, want);
    }

    private static boolean slotAgrees(CommandCatalog.Entry e, Set<String> seats) {
        int at = seatSlot(e);
        return at >= 0 && seats.contains(String.valueOf(e.slots[at]));
    }

    /** Index of the "seat" slot's value, or -1. */
    private static int seatSlot(CommandCatalog.Entry e) {
        for (int i = 0; i + 1 < e.slots.length; i += 2) if ("seat".equals(e.slots[i])) return i + 1;
        return -1;
    }

    /** "turn it off" after a clause about a boolean command: that command with the spoken polarity. */
    private Found anaphora(List<String> t, int from, int to, boolean[] used, List<Found> previous) {
        int at = -1;
        for (int i = from; i + 3 <= to; i++) {
            if (free(used, i, 3) && ANAPHORA_VERBS.contains(t.get(i)) && ANAPHORA_OBJECTS.contains(t.get(i + 1))
                    && ("on".equals(t.get(i + 2)) || "off".equals(t.get(i + 2)))) {
                at = i;
                break;
            }
        }
        if (at < 0 || previous.isEmpty() || previous.get(0).entry == null || negatedBefore(t, from, at)) return null;
        CommandCatalog.Entry ref = previous.get(0).entry;
        int bool = -1;
        for (int i = 1; i < ref.slots.length; i += 2) if (ref.slots[i] instanceof Boolean) bool = i;
        if (bool < 0) return null;
        Object[] want = ref.slots.clone();
        want[bool] = "on".equals(t.get(at + 2));
        CommandCatalog.Entry e = findEntry(ref.intent, want);
        if (e == null) return null;
        for (int i = at; i < at + 3; i++) used[i] = true;
        return new Found(LocalCommandSpotter.matchOf(e, at), e);
    }

    private CommandCatalog.Entry findEntry(String intent, Object[] slots) {
        for (CommandCatalog.Entry e : entries) {
            if (e.intent.equals(intent) && Arrays.equals(e.slots, slots)) return e;
        }
        return null;
    }

    /** Direction, number and seat words of the phrase and of the span must be the same multiset. */
    private static boolean protectedAgrees(List<String> t, int start, int len, String[] w) {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (String x : w) if (isProtected(x)) a.add(x);
        for (int i = start; i < start + len; i++) if (isProtected(t.get(i))) b.add(t.get(i));
        Collections.sort(a);
        Collections.sort(b);
        return a.equals(b);
    }

    private static boolean isProtected(String w) {
        return POLARITY.contains(w) || SLOT_WORDS.contains(w)
                || (!w.isEmpty() && w.chars().allMatch(Character::isDigit));
    }

    private static boolean sameMeaning(CommandCatalog.Entry a, CommandCatalog.Entry b) {
        return a.intent.equals(b.intent) && Arrays.equals(a.slots, b.slots);
    }

    private static boolean free(boolean[] used, int from, int len) {
        for (int i = from; i < from + len; i++) if (used[i]) return false;
        return true;
    }

    private static boolean equalAt(List<String> t, int at, String[] w) {
        for (int i = 0; i < w.length; i++) if (!t.get(at + i).equals(w[i])) return false;
        return true;
    }

    /** Word-level Levenshtein between t[at, at+len) and w. */
    private static int distance(List<String> t, int at, int len, String[] w) {
        int[] prev = new int[w.length + 1];
        int[] cur = new int[w.length + 1];
        for (int j = 0; j <= w.length; j++) prev[j] = j;
        for (int i = 1; i <= len; i++) {
            cur[0] = i;
            String a = t.get(at + i - 1);
            for (int j = 1; j <= w.length; j++) {
                int sub = prev[j - 1] + (a.equals(w[j - 1]) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[w.length];
    }
}
