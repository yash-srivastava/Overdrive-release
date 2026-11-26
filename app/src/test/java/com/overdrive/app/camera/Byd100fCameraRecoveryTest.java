package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Byd100fCameraRecoveryTest {

    private static final long SPACING = Byd100fCameraRecovery.ZERO_FRAME_REOPEN_SPACING_MS;
    private static final int FREE = Byd100fCameraRecovery.FREE_ZERO_FRAME_REOPENS;

    @Test
    public void reopensOfAStreamingCameraAreNeverDelayed() {
        Byd100fCameraRecovery.ReopenBudget b = new Byd100fCameraRecovery.ReopenBudget();
        for (int i = 0; i < 20; i++) {
            assertEquals(0L, b.delayBeforeReopen(false, 1_000L + i));
        }
        assertEquals(0, b.zeroFrameReopens());
    }

    @Test
    public void firstZeroFrameReopensRunBackToBackSoTheRebuildEscalationIsUnchanged() {
        Byd100fCameraRecovery.ReopenBudget b = new Byd100fCameraRecovery.ReopenBudget();
        for (int i = 0; i < FREE; i++) {
            assertEquals(0L, b.delayBeforeReopen(true, 7_000L * i));
        }
        assertEquals(FREE, b.zeroFrameReopens());
    }

    @Test
    public void laterZeroFrameReopensAreSpacedButNeverStopped() {
        Byd100fCameraRecovery.ReopenBudget b = new Byd100fCameraRecovery.ReopenBudget();
        long t = 0L;
        for (int i = 0; i < FREE; i++) {
            assertEquals(0L, b.delayBeforeReopen(true, t));
            t += 7_000L;
        }
        long last = t - 7_000L;
        long wait = b.delayBeforeReopen(true, t);
        assertEquals(last + SPACING - t, wait);
        // A denied attempt is not counted and does not move the clock.
        assertEquals(FREE, b.zeroFrameReopens());
        assertTrue(b.delayBeforeReopen(true, last + SPACING - 1) > 0L);
        assertEquals(0L, b.delayBeforeReopen(true, last + SPACING));
        assertTrue(b.delayBeforeReopen(true, last + SPACING + 1) > 0L);
        assertEquals(0L, b.delayBeforeReopen(true, last + 2 * SPACING));
    }

    @Test
    public void aRealFrameRestoresImmediateReopens() {
        Byd100fCameraRecovery.ReopenBudget b = new Byd100fCameraRecovery.ReopenBudget();
        for (int i = 0; i <= FREE; i++) b.delayBeforeReopen(true, 0L);
        assertTrue(b.delayBeforeReopen(true, 1L) > 0L);
        b.onGenuineFrame();
        assertEquals(0, b.zeroFrameReopens());
        assertEquals(0L, b.delayBeforeReopen(true, 2L));
    }

    @Test
    public void aReopenAfterStreamingResetsTheSpacing() {
        Byd100fCameraRecovery.ReopenBudget b = new Byd100fCameraRecovery.ReopenBudget();
        for (int i = 0; i <= FREE; i++) b.delayBeforeReopen(true, 0L);
        assertEquals(0L, b.delayBeforeReopen(false, 5L));
        assertEquals(0, b.zeroFrameReopens());
        assertEquals(0L, b.delayBeforeReopen(true, 6L));
    }

    @Test
    public void sleepIsWallTimeThatUptimeDidNotSee() {
        // 26 Nov log: a 95-minute sleep read as "GL thread blocked for 5696113ms".
        assertTrue(Byd100fCameraRecovery.crossedSleep(5_696_113L, 1_000L));
        assertTrue(Byd100fCameraRecovery.crossedSleep(7_001L, 1_000L));
        // A normal tick, and a genuinely blocked tick, advance both clocks together.
        assertFalse(Byd100fCameraRecovery.crossedSleep(1_000L, 1_000L));
        assertFalse(Byd100fCameraRecovery.crossedSleep(40_000L, 40_000L));
        assertFalse(Byd100fCameraRecovery.crossedSleep(6_000L, 1_000L));
        // A backwards wall-clock step is never treated as sleep.
        assertFalse(Byd100fCameraRecovery.crossedSleep(-60_000L, 1_000L));
    }

    @Test
    public void scopeIsTheReverseGateScopeWhichNowCoversSurfaceTextureMode() throws Exception {
        String rec = read("app/src/main/java/com/overdrive/app/camera/Byd100fCameraRecovery.java");
        assertTrue(rec.contains("return ReverseViewCameraGate.appliesHere();"));
        String gate = read("app/src/main/java/com/overdrive/app/camera/ReverseViewCameraGate.java");
        int body = gate.indexOf("public static boolean appliesHere()");
        String applies = gate.substring(body, gate.indexOf("}\n", gate.indexOf("catch", body)));
        assertTrue(applies.contains("DiLink100Platform.isAvmPathActive()"));
        assertTrue(applies.contains("AvmSurfaceTexturePlatform.usesByd100fTileOrder()"));
    }

    @Test
    public void everyNewBehaviourIsGatedOnByd100f() throws Exception {
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        assertTrue(gpu.contains(
            "private final boolean byd100fRecovery = Byd100fCameraRecovery.appliesHere();"));
        // HAL event 8: BYD100F checks frames; everyone else keeps the immediate restart.
        int check = gpu.indexOf("if (byd100fRecovery) {\n                        logger.error(\"CAMERA ERROR");
        int legacy = gpu.indexOf("postLegacyErrorRestart(errorOpenSeq);");
        assertTrue(check > 0 && legacy > check);
        assertTrue(gpu.indexOf("postByd100fErrorCheck(errorOpenSeq);", check) < legacy);
        // Stall restart spacing, frame-path reset, dead-slot holds, watchdog sleep.
        assertTrue(gpu.contains(
            "if (byd100fRecovery && deferZeroFrameReopen(\"stall restart\") > 0L) return;"));
        assertTrue(gpu.contains(
            "if (byd100fRecovery) Byd100fCameraRecovery.PROCESS_BUDGET.onGenuineFrame();"));
        assertTrue(gpu.contains(
            "if (byd100fRecovery && holdReopenForReverseView(\"dead-slot camera switch\")) {"));
        assertTrue(gpu.contains(
            "if (byd100fRecovery && holdReopenForReverseView(\"dead-slot camera retry\")) {"));
        assertTrue(gpu.contains("boolean crossedSleep = byd100fRecovery\n"));
        // The new hold is inert off BYD100F.
        assertTrue(gpu.contains("new ReverseViewCameraGate.Hold(byd100fRecovery);"));
    }

    @Test
    public void errorCheckKeepsAStreamingCameraAndHoldsBeforeSpacing() throws Exception {
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        int start = gpu.indexOf("private void runByd100fErrorCheck(");
        String body = gpu.substring(start, gpu.indexOf("\n    }\n", start));
        int stale = body.indexOf("cameraOpenSeq != openSeq) return;");
        int frames = body.indexOf("if (frameCounter != framesAtError) {");
        int hold = body.indexOf("holdReopenForReverseView(");
        int spacing = body.indexOf("deferZeroFrameReopen(");
        int restart = body.indexOf("restartCameraAfterError();");
        assertTrue(stale > 0 && frames > stale && hold > frames && spacing > hold
            && restart > spacing);
        // A deferred check always re-schedules itself, so the camera is never abandoned.
        assertTrue(body.contains(
            "handler.postDelayed(() -> runByd100fErrorCheck(openSeq, framesAtError), delay);"));
    }

    private static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("..").resolve(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
