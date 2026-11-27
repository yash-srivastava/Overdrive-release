package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.GearMonitor;
import org.junit.Test;

public class CarViewStreamHoldTest {

    private static final long EVENT = 100_000L;
    private static final int R = GearMonitor.GEAR_R;
    private static final int D = GearMonitor.GEAR_D;

    @Test
    public void eventThenReverseHoldsUpToTheReverseCap() {
        // The car's view takes the stream ~0.45 s before the gear reads R; the stall fires ~4.5 s later.
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, R, EVENT + 4_500L));
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, R, EVENT + 60_000L));
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, R,
                EVENT + CarViewStreamHold.CAR_VIEW_R_MAX_MS - 1));
        assertFalse(CarViewStreamHold.shouldHold(true, EVENT, false, R,
                EVENT + CarViewStreamHold.CAR_VIEW_R_MAX_MS));
    }

    @Test
    public void eventInDriveHoldsUpToTheShortCap() {
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, D, EVENT + 4_800L));
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, D,
                EVENT + CarViewStreamHold.CAR_VIEW_MAX_MS - 1));
        assertFalse(CarViewStreamHold.shouldHold(true, EVENT, false, D,
                EVENT + CarViewStreamHold.CAR_VIEW_MAX_MS));
    }

    @Test
    public void leavingReversePastTheShortCapReleasesTheHold() {
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, R, EVENT + 50_000L));
        assertFalse(CarViewStreamHold.shouldHold(true, EVENT, false, D, EVENT + 50_000L));
    }

    @Test
    public void unknownGearUsesTheShortCap() {
        assertTrue(CarViewStreamHold.shouldHold(true, EVENT, false, Integer.MIN_VALUE, EVENT + 10_000L));
        assertFalse(CarViewStreamHold.shouldHold(true, EVENT, false, -1,
                EVENT + CarViewStreamHold.CAR_VIEW_MAX_MS));
    }

    @Test
    public void framesComingBackEndTheHold() {
        assertFalse(CarViewStreamHold.shouldHold(true, EVENT, true, R, EVENT + 5_000L));
        // A frame already in flight at the event does not count; a later one does.
        assertFalse(CarViewStreamHold.frameEndsHold(EVENT, EVENT + 100L));
        assertTrue(CarViewStreamHold.frameEndsHold(EVENT,
                EVENT + CarViewStreamHold.FRAME_RESUME_GRACE_MS));
        assertFalse(CarViewStreamHold.frameEndsHold(0L, EVENT));
    }

    @Test
    public void noEventOrOtherPlatformNeverHolds() {
        assertFalse(CarViewStreamHold.shouldHold(true, 0L, false, R, EVENT));
        assertFalse(CarViewStreamHold.shouldHold(false, EVENT, false, R, EVENT + 1_000L));
    }

    @Test
    public void eventAndStallAreWired() throws Exception {
        String coordinator = read("app/src/main/java/com/overdrive/app/camera/BydCameraCoordinator.java");
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        assertTrue(coordinator.contains(
                "eventType == CarViewStreamHold.EVENT_CAR_VIEW_TOOK_STREAM && yieldCallback != null"));
        assertTrue(coordinator.contains("yieldCallback.onCarViewTookStream();"));
        assertTrue(gpu.contains("public void onCarViewTookStream() {"));
        assertTrue(gpu.contains("noteCarViewTookStream();"));
        // The event is recorded only on BYD100F.
        int note = gpu.indexOf("private void noteCarViewTookStream() {");
        assertTrue(note > 0 && gpu.indexOf("if (!byd100fRecovery) return;", note) > note);
        // Real frames end the hold.
        assertTrue(gpu.contains(
                "if (carViewStreamEventMs != 0L && !carViewFramesResumed) noteFrameAfterCarView();"));
        // The car-view decision runs first in the stall hold, and a lapsed hold restarts at once.
        int hold = gpu.indexOf("private boolean holdStallRestartForReverseView() {");
        int carView = gpu.indexOf("int carView = carViewStallDecision(now);", hold);
        int reverse = gpu.indexOf("reverseStallHold.holdNow(", hold);
        assertTrue(hold > 0 && carView > hold && reverse > carView);
        assertTrue(gpu.indexOf("if (carView == CAR_VIEW_LAPSED) return false;", hold) < reverse);
    }

    private static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("..").resolve(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
