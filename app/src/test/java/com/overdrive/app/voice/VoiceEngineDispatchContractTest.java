package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins the clean-hit gate in VoiceEngine: a spotted phrase runs at once only when
 * CommandWindowGate says so, cancel is never gated, held phrases are validated against the full
 * transcript at window close, and the frozen wake check is untouched.
 */
public class VoiceEngineDispatchContractTest {

    private static final String ENGINE =
            "src/main/java/com/overdrive/app/voice/VoiceEngine.java";
    private static final String SPOTTER =
            "src/main/java/com/overdrive/app/voice/local/LocalCommandSpotter.java";

    @Test
    public void localMatchGatesDispatchAfterCancel() throws Exception {
        String body = block(readProjectFile(ENGINE),
                "    private void onLocalMatch(LocalCommandSpotter.Match m) {");
        int cancel = body.indexOf("\"assistant.cancel\"");
        int gate = body.indexOf("CommandWindowGate.decide(");
        int dispatch = body.indexOf("dispatch(m)");
        int hold = body.indexOf("held.add(m)");
        assertTrue(cancel >= 0);
        assertTrue(gate > cancel);
        assertTrue(dispatch > gate);
        assertTrue(hold > gate);
        // The cancel check returns before anything can be held.
        assertTrue(body.substring(0, gate).contains("cancelTurn();\n            return;"));
    }

    @Test
    public void leadingSpeechUsesSpotterTimestampWithHeuristicFallback() throws Exception {
        String src = readProjectFile(ENGINE);
        String body = block(src,
                "    private void onLocalMatch(LocalCommandSpotter.Match m) {");
        assertTrue(body.contains("m.phraseStartOffset >= 0"));
        assertTrue(body.contains("leadingMsOf(m, phraseStartSample)"));
        assertTrue(body.contains("lastLoudSample"));
        String leading = block(src,
                "    private long leadingMsOf(LocalCommandSpotter.Match m, long phraseStartSample) {");
        assertTrue(leading.contains("speechOnsetSample"));
        assertTrue(leading.contains("phraseStartSample >= 0 && onset >= 0"));
        assertTrue(leading.contains("(phraseStartSample - onset) * 1000 / RATE"));
        assertTrue(leading.contains("speechMsAtFirstMatch - firstMatchWords * WORD_MS"));
    }

    @Test
    public void windowCloseValidatesHeldHitsOrSaysNotUnderstood() throws Exception {
        String src = readProjectFile(ENGINE);
        String close = block(src, "    private void closeLocalWindow() {");
        assertTrue(close.contains("held.isEmpty()"));
        assertTrue(close.contains("validateHeld("));
        String validate = block(src, "    private void validateHeld(boolean hybrid) {");
        assertTrue(validate.contains("understandLocally()"));
        assertTrue(validate.contains("VoicePrompts.NOT_UNDERSTOOD"));
        assertTrue(validate.contains("noteTurn(\"held_unverified\")"));
        assertTrue(validate.contains("startCloud(true)"));
        assertTrue(validate.contains("turnBy = \"kwsasr\""));
    }

    @Test
    public void gateKnowsWhetherAHeldHitCouldEverBeValidated() throws Exception {
        String body = block(readProjectFile(ENGINE),
                "    private void onLocalMatch(LocalCommandSpotter.Match m) {");
        // The same guard understandLocally() applies, so a hit is only held when a transcript
        // can confirm it (English STT allowed and Moonshine loaded).
        assertTrue(body.contains(
                "boolean canValidate = transcriber != null && config.commandSttAllowed(locale());"));
        int flag = body.indexOf("canValidate = ");
        int gate = body.indexOf("CommandWindowGate.decide(");
        assertTrue(flag >= 0 && flag < gate);
        String call = body.substring(gate, body.indexOf(';', gate));
        assertTrue(call.contains("!held.isEmpty(), canValidate)"));
    }

    @Test
    public void unverifiedHeldHitsNeverSpeakAnErrorOverAnExecutedCommand() throws Exception {
        String validate = block(readProjectFile(ENGINE),
                "    private void validateHeld(boolean hybrid) {");
        int notTranscribed = validate.indexOf("if (!transcribed) {");
        int executedEmpty = validate.indexOf("if (executed.isEmpty()) {");
        int error = validate.indexOf("noteTurn(\"held_unverified\")");
        int complete = validate.indexOf("if (pendingResults == 0) completeLocalTurn();", error);
        assertTrue(notTranscribed >= 0);
        // The error path is reached only when nothing ran on the instant path ...
        assertTrue(executedEmpty > notTranscribed && error > executedEmpty);
        // ... otherwise the turn completes on the executed command's own result.
        assertTrue(complete > error);
        String errorPath = validate.substring(error, complete);
        assertTrue(errorPath.contains("VoicePrompts.NOT_UNDERSTOOD"));
        assertTrue(errorPath.contains("return;"));
    }

    @Test
    public void speechOnsetAndLoudChunksStayRmsBased() throws Exception {
        String body = block(readProjectFile(ENGINE),
                "    private void trackSpeech(short[] pcm, int off, int n, float rms, long now) {");
        assertTrue(body.contains("lastLoudSample = turnSamples + n"));
        assertTrue(body.contains("speechOnsetSample = Math.max(0, turnSamples - n)"));
    }

    @Test
    public void frozenWakeCheckStillUsesEnglishSpeechTwice() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void checkWake() {");
        assertEquals(2, count(body, "englishSpeech()"));
    }

    @Test
    public void sttWakesCarryTheirTranscriptInTheCheckLog() throws Exception {
        String body = block(readProjectFile(ENGINE), "    private void checkWake() {");
        assertTrue(body.contains("!\"stt\".equals(by) ? null : CommandTranscriber.logSafe(text, 40)"));
        assertEquals(2, count(body, "englishSpeech()"));
    }

    @Test
    public void spotterReportsPhraseStartWithoutTouchingVerifyWake() throws Exception {
        String src = readProjectFile(SPOTTER);
        assertTrue(src.contains("public final long phraseStartOffset;"));
        assertTrue(src.contains("result.getTimestamps()"));
        String decode = block(src, "    private Match decode(OnlineStream s, boolean isAhead) {");
        assertTrue(decode.contains("phraseStartOffset(result, fed)"));
        // After reset, timestamps restart at the consumed audio boundary. Retain the
        // unconsumed backlog instead of treating it as already decoded.
        int reset = decode.indexOf("spotter.reset(s)");
        int rebase = decode.indexOf("fed = Math.max(0L, fed - decodes * SAMPLES_PER_DECODE);");
        int resetDecodes = decode.indexOf("decodes = 0;", rebase);
        assertTrue(reset >= 0 && rebase > reset);
        assertTrue(resetDecodes > rebase);
        assertTrue(decode.indexOf("toMatch(", resetDecodes) > resetDecodes);
        String verify = block(src,
                "    private int verifyWith(short[] pcm, int offset, int length, String keywords) {");
        assertTrue(verify.contains("if (reset || times == null || times.length == 0) return WAKE_END_UNKNOWN;"));
        assertTrue(verify.contains("int end = Math.round(times[times.length - 1] * SAMPLE_RATE);"));
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
