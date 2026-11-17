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
 * Pins the sample-anchored post-wake listen budget in VoiceEngine: the silent wait counts from the
 * end of the wake chime measured in captured samples (never wall clock), the chime itself is never
 * muted on the wake path, and every per-turn field is reset in beginTurn so the turn log is honest.
 */
public class VoiceEngineListenWindowContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";

    private static final String[] RESET_LINES = {
            "cueBacklogSamples = 0;",
            "cueStartSample = 0;",
            "listenFromSample = -1;",
            "cueMs = 0;",
            "cueVolume = -1;",
            "cueFallback = null;",
            "cuePeakRms = 0f;",
            "listenPeakRms = 0f;",
            "vadSpeechSamples = 0;",
            "loudSamples = 0;",
            "turnWakeTail = 0;",
            "utteranceSkipSamples = 0;",
            "wakeTailPossible = false;",
            "preWarmFailed = null;",
    };

    @Test
    public void tickNeedsBothTheWakeAndTheListenBudgetToRunOut() throws Exception {
        String src = readProjectFile(ENGINE);
        String tick = block(src, "    private void tick(long now) {");
        assertTrue(tick.contains("heard > INITIAL_SILENCE_SAMPLES"));
        assertTrue(tick.contains("listened > INITIAL_SILENCE_SAMPLES"));
        assertTrue(tick.contains("listenFromSample >= 0 ? turnSamples - listenFromSample : heard"));
        assertTrue(src.contains("INITIAL_SILENCE_MS = 4500"));
        assertTrue(src.contains("CUE_SETTLE_MS = 120"));
    }

    @Test
    public void beginTurnSnapshotsTheBacklogBeforeTheCueAndMeasuresTheVolume() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void beginTurn(boolean manual, float gain) {");
        assertTrue(body.contains("cueMs = earcons.play("));
        assertTrue(body.contains("cueBacklogSamples = ring.available();"));
        assertTrue(body.contains("getStreamVolume("));
        assertTrue(body.contains("if (vad != null) vad.reset();"));
        int snapshot = body.indexOf("cueBacklogSamples = ring.available();");
        int cue = body.indexOf("cueMs = earcons.play(");
        assertTrue(snapshot >= 0 && snapshot < cue);
        // The volume read must not be able to abort a turn on an odd head unit.
        int tryAt = body.indexOf("try {");
        int volume = body.indexOf("getStreamVolume(");
        assertTrue(tryAt >= 0 && tryAt < volume);
        assertTrue(body.contains("mutedUntil = manual ? now + cueMs + CUE_SETTLE_MS : 0;"));
        // Channel names come from Earcons and MediaTurnPolicy, never literals in the engine.
        assertFalse(body.contains("\"voice\""));
    }

    @Test
    public void mutedCueStreamMovesTheWakeCueToAnAudibleStreamAndLogsIt() throws Exception {
        String src = readProjectFile(ENGINE);
        String body = block(src, "    private void beginTurn(boolean manual, float gain) {");
        int volume = body.indexOf("getStreamVolume(");
        int fallback = body.indexOf("cueFallback = cueVolume == 0 ? Earcons.audibleFallback(audioManager, channel()) : null;");
        int cue = body.indexOf("cueMs = earcons.play(Earcons.WAKE, cueFallback != null ? cueFallback : channel());");
        assertTrue(volume >= 0);
        assertTrue(fallback > volume);
        assertTrue(cue > fallback);
        // Only the WAKE cue moves; success/failure cues keep the configured channel.
        assertEquals(1, count(src, "cueFallback != null ? cueFallback : channel()"));
        assertEquals(2, count(src, "earcons.play(Earcons.SUCCESS, channel())")
                + count(src, "earcons.play(cue, channel())"));
        String note = block(src, "    private void noteTurn(String outcome) {");
        assertTrue(note.contains("if (cueFallback != null) t.put(\"cueFallback\", cueFallback);"));
        // The stream names live in Earcons, never as literals in the engine.
        assertFalse(body.contains("\"notification\""));
    }

    @Test
    public void beginTurnResetsEveryPerTurnFieldOnItsOwnLine() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void beginTurn(boolean manual, float gain) {");
        for (String line : RESET_LINES) {
            assertTrue("missing reset " + line, body.contains("\n        " + line + "\n"));
        }
    }

    @Test
    public void wakeCheckAnchorsTheListenWindowAfterTheReplayWithoutTouchingTheFrozenParts()
            throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void checkWake() {");
        assertTrue(body.contains("cueStartSample = turnSamples + cueBacklogSamples;"));
        assertTrue(body.contains("listenFromSample = cueStartSample +"));
        int replay = body.indexOf("onTurnAudio(verifyBuf, off, len, now, off >= speechFrom);");
        int anchor = body.indexOf("cueStartSample = turnSamples + cueBacklogSamples;");
        assertTrue(replay >= 0 && replay < anchor);
        assertEquals(2, count(body, "englishSpeech()"));
        assertFalse(body.contains("vad."));
    }

    @Test
    public void onAudioMeasuresCueAndListenPeaksOnlyOnUnmutedAudio() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void onAudio(short[] chunk, int n, long now) {");
        int muted = body.indexOf("if (muted) return;");
        int listen = body.indexOf("turnSamples >= listenFromSample");
        int cue = body.indexOf("turnSamples >= cueStartSample");
        assertTrue(muted >= 0);
        assertTrue(listen > muted);
        assertTrue(cue > muted);
        assertFalse(body.contains("vad."));
    }

    @Test
    public void listenBudgetIsNeverWallClockAnchored() throws Exception {
        String src = readProjectFile(ENGINE);
        assertFalse(src.contains("cueEndsAt"));
        String reArm = block(src, "    private void reArm(LocalCommandSpotter.Match wake) {");
        assertTrue(reArm.contains("listenFromSample = turnSamples"));
    }

    @Test
    public void trackSpeechCountsLoudAndVadSamplesBeforeTheEarlyReturn() throws Exception {
        String src = readProjectFile(ENGINE);
        String body = block(src, "    private void trackSpeech(short[] pcm, int off, int n, float rms, long now) {");
        int loud = body.indexOf("loudSamples += n");
        int vadCount = body.indexOf("vadSpeechSamples += n");
        int ret = body.indexOf("if (!speech) return;");
        assertTrue(loud >= 0 && loud < ret);
        assertTrue(vadCount >= 0 && vadCount < ret);
        assertEquals(1, count(src, "vad.speech("));
    }

    @Test
    public void turnLogCarriesTheListenWindowDiagnostics() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void noteTurn(String outcome) {");
        for (String key : new String[] {
                "\"listenMs\"", "\"micDb\"", "\"cueMs\"", "\"cueVol\"", "\"cueDb\"", "\"vadMs\"", "\"loudMs\"", "\"tail\""}) {
            assertTrue("missing " + key, body.contains(key));
        }
    }

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
