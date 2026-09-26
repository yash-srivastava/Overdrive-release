package com.overdrive.app.launcher;
import com.overdrive.app.util.ScratchPaths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ZrokRuntimeProbeTest {

    @Test
    public void overrideRequiresFixedZrokVersion() {
        assertFalse(ZrokRuntimeProbe.supportsOverrideVersion("zrok version v0.3.7"));
        assertTrue(ZrokRuntimeProbe.supportsOverrideVersion("zrok version v0.4.0"));
        assertTrue(ZrokRuntimeProbe.supportsOverrideVersion("zrok version 1.0.0"));
        assertFalse(ZrokRuntimeProbe.supportsOverrideVersion("unknown"));
    }

    @Test
    public void edgeFailuresRequireLocalOriginAndTwoRealGatewayErrors() {
        assertEquals(1, ZrokRuntimeProbe.nextEdgeFailureCount(true, 502, 0));
        assertEquals(2, ZrokRuntimeProbe.nextEdgeFailureCount(true, 503, 1));
        assertEquals(2, ZrokRuntimeProbe.nextEdgeFailureCount(true, null, 2));
        assertEquals(0, ZrokRuntimeProbe.nextEdgeFailureCount(false, 504, 2));
        assertEquals(0, ZrokRuntimeProbe.nextEdgeFailureCount(true, 200, 2));
        assertTrue(ZrokRuntimeProbe.isStaleStatus(504));
        assertFalse(ZrokRuntimeProbe.isStaleStatus(500));
    }

    @Test
    public void healthyShareRequiresARealNonGatewayResponse() {
        assertTrue(ZrokRuntimeProbe.isHealthyStatus(200));
        assertTrue(ZrokRuntimeProbe.isHealthyStatus(404));
        assertFalse(ZrokRuntimeProbe.isHealthyStatus(502));
        assertFalse(ZrokRuntimeProbe.isHealthyStatus(0));
        assertFalse(ZrokRuntimeProbe.isHealthyStatus(null));
    }

    @Test
    public void latestPublicShareUrlWins() {
        String log = "ready https://old-name.share.zrok.io then "
                + "https://new-name.share.zrok.io";
        assertEquals("new-name", ZrokRuntimeProbe.extractLastShareName(log));
        assertEquals("", ZrokRuntimeProbe.extractLastShareName(
                "https://old-name.share.zrok.io\nStarting zrok share..."));
        assertEquals("new-name", ZrokRuntimeProbe.extractLastShareName(
                "https://old-name.share.zrok.io\nStarting zrok share...\n"
                        + "https://new-name.share.zrok.io"));
        assertEquals("", ZrokRuntimeProbe.extractLastShareName("no share url"));
    }

    @Test
    public void shellQuoteKeepsHostileTokenInsideOneArgument() {
        assertEquals("'normal-token'", ZrokRuntimeProbe.shellQuote("normal-token"));
        assertEquals("'a'\\''b; $(id)'", ZrokRuntimeProbe.shellQuote("a'b; $(id)"));
    }

    @Test
    public void structuredEnableErrorWinsOverLeadingInfoLog() {
        String output =
                "Exit code 1: {\"level\":\"info\",\"msg\":\"contacting the zrok service...\"}\n"
                + "{\"level\":\"error\",\"msg\":\"the zrok service returned an error: "
                + "[POST /enable][401] enableUnauthorized \\\"bad token\\\"\"}";

        assertEquals(
                "the zrok service returned an error: "
                        + "[POST /enable][401] enableUnauthorized \"bad token\"",
                ZrokRuntimeProbe.extractErrorMessage(output));
        assertEquals(
                ZrokRuntimeProbe.extractErrorMessage(output),
                ZrokRuntimeProbe.summarizeFailure(output));
    }

    @Test
    public void plainTextFailureFallsBackWithoutMisclassifyingSuccess() {
        assertEquals(
                "permission denied",
                ZrokRuntimeProbe.extractErrorMessage(
                        "contacting the zrok service...\npermission denied"));
        assertEquals(
                "",
                ZrokRuntimeProbe.extractErrorMessage(
                        "{\"level\":\"info\",\"msg\":\"the zrok environment was successfully enabled...\"}"));
        assertEquals(
                "there was a problem enabling your environment!",
                ZrokRuntimeProbe.summarizeFailure(
                        "{\"level\":\"info\",\"msg\":\"contacting the zrok service...\"}\n"
                        + "there was a problem enabling your environment!"));
    }

    @Test
    public void commandRedactionDoesNotAlterNonZrokCommands() {
        String enable =
                "HOME=/data/local/tmp " + ScratchPaths.path("zrok") + " enable 'secret token' "
                        + "--headless 2>&1";
        String reserved =
                ScratchPaths.path("zrok") + " share reserved token-123 $ZROK_OVERRIDE --headless";

        assertEquals(
                "HOME=/data/local/tmp " + ScratchPaths.path("zrok") + " enable [REDACTED] "
                        + "--headless 2>&1",
                ZrokRuntimeProbe.redactCommand(enable));
        assertEquals(
                ScratchPaths.path("zrok") + " share reserved [REDACTED] "
                        + "$ZROK_OVERRIDE --headless",
                ZrokRuntimeProbe.redactCommand(reserved));
        assertEquals("echo harmless", ZrokRuntimeProbe.redactCommand("echo harmless"));
        assertFalse(ZrokRuntimeProbe.redactCommand(enable).contains("secret token"));
        assertEquals(
                "your reserved share token is '[REDACTED]'",
                ZrokRuntimeProbe.redactOutput(
                        "your reserved share token is 'secret-share-token'"));
    }
}
