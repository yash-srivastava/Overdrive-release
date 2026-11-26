package com.overdrive.app.voice.local;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Second-stage wake check on a speech-to-text transcript: does the text say "OverDrive"? Pure
 * Java so the accent rules can be table-tested on the JVM. Rules run from the most to the least
 * literal spelling; the first hit names the rule for the field log.
 */
final class WakePhraseMatcher {

    private static final Pattern LETTERS = Pattern.compile("[^a-z0-9 ]");

    /** How the speech-to-text model spells "OverDrive" when it is heard, across accents. */
    static final String[] WAKE_SPELLINGS = {
            "overdrive", "overdrife", "overdryve", "overdrve", "overdrif", "overdrie", "overdive",
            "overdrove", "overdrave", "oferdrive", "ofordrive", "offerdrive", "everdrive",
            "oberdrive", "averdrive", "overtrive", "overdraiv", "overdriv"};
    /** First words of a two-word "over drive" split, rhotic and non-rhotic. */
    static final Set<String> WAKE_FIRST = set("over", "offer", "ofer", "ober", "ever", "aver", "overt", "or", "ow",
            "ova", "ower", "owa", "oba", "ovah", "ovar", "oh");
    /** Clear "over"-like first words that may carry a split "the" before the "-rive" tail. */
    static final Set<String> OVER_LIKE = set("over", "offer", "ofer", "ober", "aver", "ova", "ower", "overt", "oba", "ovar");
    static final Set<String> ARTICLE = set("the", "de", "da", "do", "a", "to", "ta");
    /** Articles speech-to-text inserts between "Over" and an intact "drive" ("oh the drive"). */
    static final Set<String> SPLIT_ARTICLE = set("the", "a", "de", "da");
    static final Pattern SECOND = Pattern.compile("^dr(a?[iy]|a)[vf]?e?s?$");
    static final Pattern ONSET_FIRST = Pattern.compile("^o[vbfhn][a-z]{1,3}r[dt]?$");
    static final Pattern SPLIT_THIRD = Pattern.compile("^r(a[a-z]e|ai[a-z]?|ay[a-z]?|i[a-z]e|y[a-z]{0,2})$");

    private WakePhraseMatcher() {
    }

    static boolean saysWakeWord(String text) {
        return rule(text) != null;
    }

    /**
     * "spelling" | "fused" | "pair" | "onset" | "split" | "article", or null when the transcript
     * does not say "OverDrive".
     */
    static String rule(String text) {
        if (text == null || text.isEmpty()) return null;
        String clean = LETTERS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (clean.isEmpty()) return null;
        String[] words = clean.split("\\s+");
        StringBuilder joined = new StringBuilder(clean.length());
        // A flat offset cannot exceed the original text length. Avoid boxing each boundary.
        boolean[] wordEnds = new boolean[clean.length() + 1];
        for (String w : words) {
            joined.append(w);
            wordEnds[joined.length()] = true;
        }
        String flat = joined.toString();
        // E: a known spelling anywhere; the short ones must end on a word boundary so "over
        // diverse" does not pass as "overdive".
        for (String s : WAKE_SPELLINGS) {
            int from = 0;
            int idx;
            while ((idx = flat.indexOf(s, from)) >= 0) {
                if (s.length() >= 9 || wordEnds[idx + s.length()]) return "spelling";
                from = idx + 1;
            }
        }
        // S: one fused word within two edits of "overdrive".
        int[] distanceRow = null;
        for (String w : words) {
            if (w.length() >= 7 && w.length() <= 10 && w.charAt(0) == 'o'
                    && (w.contains("dr") || w.contains("tr"))) {
                if (distanceRow == null) distanceRow = new int["overdrive".length() + 1];
                if (levenshtein(w, "overdrive", distanceRow) <= 2) return "fused";
            }
        }
        // T: "over" + "drive" split across two words.
        for (int i = 0; i + 1 < words.length; i++) {
            if (WAKE_FIRST.contains(words[i]) && SECOND.matcher(words[i + 1]).matches()) return "pair";
        }
        // F1: a mangled "over"-like onset ("onward", "ohver") before a "drive"-like word.
        for (int i = 0; i + 1 < words.length; i++) {
            if (ONSET_FIRST.matcher(words[i]).matches() && SECOND.matcher(words[i + 1]).matches()) return "onset";
        }
        // F2: "over the rive" — the d lost to a short article.
        for (int i = 0; i + 2 < words.length; i++) {
            if (OVER_LIKE.contains(words[i]) && ARTICLE.contains(words[i + 1])
                    && SPLIT_THIRD.matcher(words[i + 2]).matches()) {
                return "split";
            }
        }
        // A: "oh the drive" — an article between the "over"-like word and an intact "drive".
        for (int i = 0; i + 2 < words.length; i++) {
            if (WAKE_FIRST.contains(words[i]) && SPLIT_ARTICLE.contains(words[i + 1])
                    && SECOND.matcher(words[i + 2]).matches()) {
                return "article";
            }
        }
        return null;
    }

    /** Only the previous row and the previous diagonal are needed for edit distance. */
    private static int levenshtein(String a, String b, int[] row) {
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int above = row[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                row[j] = Math.min(Math.min(above + 1, row[j - 1] + 1), diagonal + cost);
                diagonal = above;
            }
        }
        return row[b.length()];
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
