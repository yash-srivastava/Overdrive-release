package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.GearMonitor;
import org.junit.Test;

public class ReverseViewCameraGateTest {

    private static final long MAX = ReverseViewCameraGate.MAX_HOLD_MS;

    @Test
    public void holdsOnlyInReverseOnByd100f() {
        assertTrue(ReverseViewCameraGate.shouldHold(true, GearMonitor.GEAR_R, 0L, 1_000L));
        assertFalse(ReverseViewCameraGate.shouldHold(false, GearMonitor.GEAR_R, 0L, 1_000L));
        assertFalse(ReverseViewCameraGate.shouldHold(true, GearMonitor.GEAR_D, 0L, 1_000L));
        assertFalse(ReverseViewCameraGate.shouldHold(true, GearMonitor.GEAR_P, 0L, 1_000L));
    }

    @Test
    public void unknownOrStaleGearNeverHolds() {
        assertFalse(ReverseViewCameraGate.shouldHold(true, -1, 0L, 1_000L));
        assertFalse(ReverseViewCameraGate.shouldHold(true, Integer.MIN_VALUE, 0L, 1_000L));
    }

    @Test
    public void holdIsBounded() {
        assertTrue(ReverseViewCameraGate.shouldHold(true, GearMonitor.GEAR_R, 1_000L, 1_000L + MAX - 1));
        assertFalse(ReverseViewCameraGate.shouldHold(true, GearMonitor.GEAR_R, 1_000L, 1_000L + MAX));
    }

    @Test
    public void holdStartsOnFirstReverseAndLapsesWhileStillInReverse() {
        ReverseViewCameraGate.Hold hold = new ReverseViewCameraGate.Hold(true);
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 5_000L));
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 5_000L + MAX - 1));
        assertEquals(MAX - 1, hold.heldMs(5_000L + MAX - 1));
        // Bounded: a long reverse manoeuvre gets its restart after MAX.
        assertFalse(hold.holdNow(GearMonitor.GEAR_R, 5_000L + MAX));
        assertFalse(hold.holdNow(GearMonitor.GEAR_R, 5_000L + 10 * MAX));
    }

    @Test
    public void leavingReverseClearsTheHoldSoTheNextReverseHoldsAgain() {
        ReverseViewCameraGate.Hold hold = new ReverseViewCameraGate.Hold(true);
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 1_000L));
        assertFalse(hold.holdNow(GearMonitor.GEAR_D, 2_000L));
        assertEquals(0L, hold.heldMs(2_000L));
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 100_000L));
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 100_000L + MAX - 1));
    }

    @Test
    public void otherPlatformsNeverHold() {
        ReverseViewCameraGate.Hold hold = new ReverseViewCameraGate.Hold(false);
        assertFalse(hold.holdNow(GearMonitor.GEAR_R, 1_000L));
    }

    @Test
    public void holdTreatsAZeroClockAsAStartedHold() {
        ReverseViewCameraGate.Hold hold = new ReverseViewCameraGate.Hold(true);
        assertTrue(hold.holdNow(GearMonitor.GEAR_R, 0L));
        assertFalse(hold.holdNow(GearMonitor.GEAR_R, MAX + 1));
    }

    @Test
    public void callersAreWired() throws Exception {
        String rmm = read("app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // Cold open: the bounded R wait runs before the warmup on the activation worker.
        int wait = rmm.indexOf("ReverseViewCameraGate.awaitLeaveReverse(");
        int warm = rmm.indexOf("avcWarmup.warmupAndWait(stillRequired)");
        assertTrue(wait > 0 && warm > wait);
        // Stall restart: both legacy restart sites go through the hold.
        int a = gpu.indexOf("if (!holdStallRestartForReverseView(now)) {");
        int b = gpu.indexOf("if (!holdStallRestartForReverseView(now)) {", a + 1);
        assertTrue(a > 0 && b > a);
    }

    private static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("..").resolve(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
