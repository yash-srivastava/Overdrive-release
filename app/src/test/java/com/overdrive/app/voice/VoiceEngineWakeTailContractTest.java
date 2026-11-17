package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins the wake-word tail fix: the Moonshine/cloud copy of a KWS-timed wake starts 80 ms before the
 * verifier's phrase end while the spotter keeps its full 0.3 s lead, and only STT-timed or re-armed
 * wakes let the transcript matcher forgive one leading tail token.
 */
public class VoiceEngineWakeTailContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";
    private static final String TRANSCRIBER =
            "src/main/java/com/overdrive/app/voice/local/CommandTranscriber.java";
    private static final String MATCHER =
            "src/main/java/com/overdrive/app/voice/local/TranscriptMatcher.java";

    @Test
    public void utteranceLeadIsShorterThanTheSpotterLead() throws Exception {
        String src = readProjectFile(ENGINE);
        assertTrue(src.contains("private static final int UTTERANCE_LEAD_SAMPLES = RATE * 8 / 100;"));
        assertTrue(src.contains("private static final int COMMAND_LEAD_SAMPLES = RATE * 3 / 10;"));
        int rate = 16_000;
        assertTrue(rate * 8 / 100 < rate * 3 / 10);
    }

    @Test
    public void wakeCheckSetsTheSkipAfterBeginTurnAndTheTailFlagOnlyForUntimedWakes() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void checkWake() {");
        int begin = body.indexOf("beginTurn(false, gain);");
        int skip = body.indexOf("utteranceSkipSamples = end == LocalCommandSpotter.WAKE_END_UNKNOWN");
        assertTrue(begin >= 0);
        assertTrue(skip > begin);
        assertTrue(body.contains("wakeTailPossible = end == LocalCommandSpotter.WAKE_END_UNKNOWN;"));
        // The frozen structure of the check is untouched.
        assertEquals(2, count(body, "englishSpeech()"));
        assertFalse(body.contains("vad."));
    }

    @Test
    public void turnAudioConsumesTheSkipOnceAndOnlyForTheUtteranceAndCloud() throws Exception {
        String body = block(readProjectFile(ENGINE),
                "    private void onTurnAudio(short[] pcm, int off, int n, long now, boolean countSpeech) {");
        assertEquals(1, count(body, "utteranceSkipSamples -= skip"));
        int uOff = body.indexOf("int uOff = off;");
        int local = body.indexOf("if (phase == LOCAL)");
        assertTrue(uOff >= 0);
        assertTrue(uOff < local);
        assertTrue(body.contains("System.arraycopy(pcm, uOff, utterance"));
        assertTrue(body.contains("session.sendPcm16k(pcm, uOff, uLen)"));
        // The ~80 ms instant dispatch path keeps the original offsets.
        assertTrue(body.contains("spotter.process(pcm, off, n)"));
    }

    @Test
    public void transcriptGuardIsConsultedOnlyForWakeTurnsThatMayCarryATail() throws Exception {
        String src = readProjectFile(ENGINE);
        assertTrue(block(src, "    private int understandLocally() {").contains("!turnManual && wakeTailPossible"));
        assertTrue(block(src, "    private void reArm(LocalCommandSpotter.Match wake) {")
                .contains("wakeTailPossible = true;"));
    }

    @Test
    public void beginTurnResetsTheTailState() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void beginTurn(boolean manual, float gain) {");
        assertTrue(body.contains("utteranceSkipSamples = 0;"));
        assertTrue(body.contains("wakeTailPossible = false;"));
        assertTrue(body.contains("turnWakeTail = 0;"));
    }

    @Test
    public void transcriberAndMatcherCarryTheAfterWakeWordFlag() throws Exception {
        assertTrue(readProjectFile(TRANSCRIBER).contains("boolean afterWakeWord)"));
        String matcher = readProjectFile(MATCHER);
        assertTrue(matcher.contains("TAIL = Pattern.compile"));
        assertTrue(matcher.contains("isWakeTail("));
        assertFalse(matcher.contains("isNumberToken"));
    }

    /** Returns the text between a method's opening line and its closing brace at the same indent. */
    private static String block(String src, String openingLine) {
        int start = src.indexOf(openingLine);
        assertTrue("missing " + openingLine.trim(), start >= 0);
        int bodyStart = start + openingLine.length();
        int end = src.indexOf("\n    }\n", bodyStart);
        assertTrue(end > bodyStart);
        return src.substring(bodyStart, end);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            n++;
            at = text.indexOf(needle, at + needle.length());
        }
        return n;
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
