package com.overdrive.app.voice.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Table-driven pins for the accent-tolerant stage-2 wake rules: every field transcript, every
 * reviewer negative and the F2 collateral lists, asserting both the boolean and the rule name.
 */
public class WakePhraseMatcherTest {

    private static void accept(String text, String rule) {
        assertEquals(text, rule, WakePhraseMatcher.rule(text));
        assertTrue(text, WakePhraseMatcher.saysWakeWord(text));
    }

    private static void reject(String text) {
        assertNull(text, WakePhraseMatcher.rule(text));
        assertFalse(text, WakePhraseMatcher.saysWakeWord(text));
    }

    @Test
    public void field14() {
        reject("i m going to focus right at magena");
        reject("i m going to put it in the middle");
        reject("all right");
        reject("or worries");
        reject("what life");
        reject("what were the knights");
        reject("or the rain");
        reject("over the ninth");
        reject("so what race");
        accept("over the race", "split");
        reject("or where do they eat");
        accept("onward drive", "onset");
        reject("olatype");
        reject("you");
    }

    @Test
    public void mustAccept() {
        accept("over drive", "spelling");
        accept("overdrive", "spelling");
        accept("over dry", "pair");
        accept("ova drive", "pair");
        accept("overdrive s", "spelling");
        accept("onward drive", "onset");
        accept("over the race", "split");
    }

    @Test
    public void mustReject() {
        reject("overnight");
        reject("override");
        reject("or worries");
        reject("or the rain");
        reject("over the ninth");
        reject("all right");
        reject("so what race");
        reject("what were the knights");
        reject("olatype");
        reject("you");
        reject("or where do they eat");
    }

    @Test
    public void reviewerNegatives() {
        reject("other driver");
        reject("order drinks");
        reject("older driver");
        reject("owner drives");
        reject("oh dream");
        reject("oh drink");
        reject("the other driver");
    }

    @Test
    public void hypotheticalNegatives() {
        String[] rows = {
                "over there", "over the top", "over the road", "over the river", "over the right",
                "over the ring", "over the rim", "over the rest", "over the night", "over the line",
                "or drink", "or dress", "ow dress", "over drop", "over drink", "offer drove",
                "ever drove", "whoever drove", "oh try", "oh drama", "outer drive", "otter dry",
                "occur dry", "offered drive", "onwards drive", "oversee dry", "for the drive",
                "after the drive", "forty five", "all drive", "o drive", "of drive", "off drive",
                "oh right", "overdrawn", "outdrive", "overtrade", "overdress"};
        for (String row : rows) reject(row);
    }

    @Test
    public void hypotheticalPositives() {
        accept("ovar drive", "pair");
        accept("oh dry", "pair");
        accept("ower drive", "pair");
        accept("ovah dry", "pair");
        accept("oba drive", "pair");
        accept("offer drave", "pair");
        accept("or dry", "pair");
        accept("ow drive", "pair");
        accept("ever dry", "pair");
        accept("ohver drife", "onset");
        accept("onward dryve", "onset");
        accept("ofter drive", "onset");
        accept("overdribe", "fused");
        accept("ovadrive", "fused");
        accept("ovedrive", "fused");
        accept("overdrink", "fused");
        accept("offer the race", "split");
        accept("over da rise", "split");
        accept("ova the ride", "split");
        accept("over the rice", "split");
        accept("overdrive turn on", "spelling");
        accept("hey overdrive", "spelling");
        accept("over drove", "spelling");
    }

    @Test
    public void splitCollateralAccepted() {
        String[] thirds = {"race", "rate", "rake", "rave", "rare", "rage", "rain", "rail", "raid", "ray",
                "rays", "rise", "ride", "rice", "rife", "rive", "ripe", "rile", "rime", "rye"};
        for (String third : thirds) accept("over the " + third, "split");
    }

    @Test
    public void splitCollateralRejected() {
        String[] thirds = {"rim", "rib", "rid", "rig", "rip", "ring", "rich", "risk", "rink", "river",
                "right", "road", "rest", "roof", "rug"};
        for (String third : thirds) reject("over the " + third);
    }

    @Test
    public void shortSpellingsEndOnAWordBoundary() {
        reject("over diverse");
        reject("over diversified");
        reject("overdiving");
        reject("overdriving");
        accept("over dive", "spelling");
        accept("overdive", "spelling");
        accept("hey overdive", "spelling");
        accept("overdrives", "spelling");
    }

    @Test
    public void normalisationStripsPunctuationLikeTheLogDoes() {
        reject("I'm going to focus right at Magena.");
        accept("Over-drive!", "spelling");
        accept("OverDrive, turn on the AC", "spelling");
        reject("");
        reject(null);
    }

    @Test
    public void fastPathIsCheckedFirst() {
        accept("overdrive turn on", "spelling");
    }

    @Test
    public void legacyLoosenessIsGone() {
        reject("or drink");
        reject("ow dress");
        reject("ever drove");
    }
    @Test
    public void fusedCandidatesMatchFullMatrixReference() {
        java.util.Random random = new java.util.Random(0x49);
        for (int i = 0; i < 10_000; i++) {
            StringBuilder word = new StringBuilder("overdrive");
            int edits = random.nextInt(6);
            for (int j = 0; j < edits; j++) {
                int at = random.nextInt(word.length());
                char letter = (char) ('a' + random.nextInt(26));
                switch (random.nextInt(3)) {
                    case 0: word.setCharAt(at, letter); break;
                    case 1: word.insert(at, letter); break;
                    case 2: word.deleteCharAt(at); break;
                }
            }
            String candidate = word.toString();
            // A rejected distance candidate first also exercises scratch-row reuse.
            String prefix = "odrzzzzzz";
            String text = prefix + " " + candidate;
            String flat = text.replace(" ", "");
            String expected = null;
            for (String spelling : WakePhraseMatcher.WAKE_SPELLINGS) {
                for (int at = flat.indexOf(spelling); at >= 0;
                     at = flat.indexOf(spelling, at + 1)) {
                    int end = at + spelling.length();
                    if (spelling.length() >= 9 || end == prefix.length() || end == flat.length()) {
                        expected = "spelling";
                        break;
                    }
                }
                if (expected != null) break;
            }
            if (expected == null && candidate.length() >= 7 && candidate.length() <= 10
                    && candidate.charAt(0) == 'o'
                    && (candidate.contains("dr") || candidate.contains("tr"))
                    && fullMatrixDistance(candidate, "overdrive") <= 2) {
                expected = "fused";
            }
            assertEquals(text, expected, WakePhraseMatcher.rule(text));
        }
    }

    private static int fullMatrixDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
        }
        return d[a.length()][b.length()];
    }
}
