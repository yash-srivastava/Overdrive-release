package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy.Confirmation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * Q12: the daemon confirms a dispatched command only on two DISTINCT target readbacks (different SDK
 * read times) inside the actuation rail's window; legacy (non-Di5) keeps today's two-reads-350-ms-apart
 * behaviour because each legacy read is stamped with its own read time.
 */
public class RemoteAccessApiHandlerConfirmationLoopTest {
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;

    /** Fake clock: {@code sleep} advances it; the loop never spins without sleeping. */
    private static final class FakeClock {
        long now = 100_000L;
        int sleeps;
        long asLong() { return now; }
        void sleep(long millis) { sleeps++; now += millis; }
    }

    private static BydDataCollector.DiLink5LockEvidence typed(int state, long observedAt) {
        return new BydDataCollector.DiLink5LockEvidence(state, observedAt, DiLink5LockRail.TYPED_CABIN, state == LOCK ? 1 : 2, 0L);
    }

    /** Scripted ledger: each poll returns the next evidence; the last one repeats. */
    private static Supplier<BydDataCollector.DiLink5LockEvidence> script(BydDataCollector.DiLink5LockEvidence... sequence) {
        Queue<BydDataCollector.DiLink5LockEvidence> queue = new ArrayDeque<>();
        for (BydDataCollector.DiLink5LockEvidence e : sequence) queue.add(e);
        BydDataCollector.DiLink5LockEvidence[] last = new BydDataCollector.DiLink5LockEvidence[]{sequence[sequence.length - 1]};
        return () -> {
            if (queue.size() > 1) last[0] = queue.remove();
            else if (queue.size() == 1) last[0] = queue.peek();
            return last[0];
        };
    }

    private static Confirmation run(FakeClock clock, Supplier<BydDataCollector.DiLink5LockEvidence> evidence,
                                    int target, long windowMs) throws InterruptedException {
        return DiLink5GateActuationPolicy.confirmByDistinctReadbacks(
                evidence, target, windowMs, clock::asLong, clock::sleep);
    }

    @Test
    public void theSameObservationSeenTwiceCountsOnceAndStaysUnconfirmed() throws Exception {
        FakeClock clock = new FakeClock();
        // One LOCK reading at t=99 000, never refreshed: every poll returns the same observation.
        Confirmation result = run(clock, script(typed(LOCK, 99_000L)), LOCK, 4_000L);
        assertEquals(Confirmation.UNCONFIRMED, result);
        assertEquals(100_000L + 4_000L + 200L, clock.now, 350L); // ran out the whole window
        assertTrue(clock.sleeps >= 11);
    }

    @Test
    public void twoDistinctTargetObservationsConfirm() throws Exception {
        FakeClock clock = new FakeClock();
        Confirmation result = run(clock, script(typed(LOCK, 99_000L), typed(LOCK, 100_200L)), LOCK, 4_000L);
        assertEquals(Confirmation.CONFIRMED, result);
        assertEquals(1, clock.sleeps); // confirmed on the second poll
        assertEquals(100_350L, clock.now);
    }

    @Test
    public void aNonTargetObservationResetsTheCount() throws Exception {
        FakeClock clock = new FakeClock();
        // target, non-target, target → only one distinct target after the reset → needs one more.
        Confirmation result = run(clock, script(
                typed(LOCK, 99_000L), typed(UNLOCK, 100_100L), typed(LOCK, 100_400L)), LOCK, 1_200L);
        assertEquals(Confirmation.UNCONFIRMED, result);
        // ... and with a fourth distinct target observation it confirms.
        clock = new FakeClock();
        result = run(clock, script(
                typed(LOCK, 99_000L), typed(UNLOCK, 100_100L), typed(LOCK, 100_400L), typed(LOCK, 100_800L)),
                LOCK, 4_000L);
        assertEquals(Confirmation.CONFIRMED, result);
        assertEquals(3, clock.sleeps);
    }

    @Test
    public void invalidOrMissingEvidenceResetsTheCount() throws Exception {
        FakeClock clock = new FakeClock();
        Confirmation result = run(clock, script(
                typed(LOCK, 99_000L), BydDataCollector.DiLink5LockEvidence.unknown(), typed(LOCK, 100_400L)),
                LOCK, 1_200L);
        assertEquals(Confirmation.UNCONFIRMED, result);
        clock = new FakeClock();
        // ArrayDeque refuses null elements; a list indexed per poll can carry the missing reading.
        List<BydDataCollector.DiLink5LockEvidence> withNull = new ArrayList<>();
        withNull.add(typed(LOCK, 99_000L));
        withNull.add(null);
        withNull.add(typed(LOCK, 100_400L));
        int[] poll = {0};
        result = run(clock, () -> withNull.get(Math.min(poll[0]++, withNull.size() - 1)), LOCK, 1_200L);
        assertEquals(Confirmation.UNCONFIRMED, result);
        // INVALID with a fresh time stamp is not a readback either.
        clock = new FakeClock();
        result = run(clock, script(
                new BydDataCollector.DiLink5LockEvidence(0, 100_000L, DiLink5LockRail.TYPED_CABIN, 0, 0L),
                new BydDataCollector.DiLink5LockEvidence(0, 100_400L, DiLink5LockRail.TYPED_CABIN, 0, 0L)),
                LOCK, 1_200L);
        assertEquals(Confirmation.UNCONFIRMED, result);
    }

    @Test
    public void twoDistinctReadbacksOfTheOppositeStateNeverConfirmTheTarget() throws Exception {
        FakeClock clock = new FakeClock();
        Confirmation result = run(clock, script(typed(UNLOCK, 99_000L), typed(UNLOCK, 100_300L),
                typed(UNLOCK, 100_700L)), LOCK, 2_000L);
        assertEquals(Confirmation.UNCONFIRMED, result);
    }

    @Test
    public void confirmationWindowsFollowTheActuationRail() throws Exception {
        assertEquals(4_000L, DiLink5GateActuationPolicy.confirmationWindowMs(null));
        assertEquals(4_000L, DiLink5GateActuationPolicy.confirmationWindowMs(DiLink5LockRail.TYPED_CABIN));
        assertEquals(8_000L, DiLink5GateActuationPolicy.confirmationWindowMs(DiLink5LockRail.OTA));
        assertEquals(12_000L, DiLink5GateActuationPolicy.confirmationWindowMs(DiLink5LockRail.CARSVC));
        assertEquals(350L, DiLink5GateActuationPolicy.CONFIRMATION_POLL_MS);
        // The loop honours the window it is given: an observation arriving at +7 s confirms only
        // inside the 8 s (ota) and 12 s (carsvc) windows, not the typed 4 s one.
        for (DiLink5LockRail rail : DiLink5LockRail.values()) {
            FakeClock clock = new FakeClock();
            long window = DiLink5GateActuationPolicy.confirmationWindowMs(rail);
            Supplier<BydDataCollector.DiLink5LockEvidence> late = () -> clock.now < 107_000L
                    ? typed(LOCK, 99_000L) : typed(LOCK, 107_000L);
            Confirmation result = run(clock, late, LOCK, window);
            assertEquals(rail.name(), rail == DiLink5LockRail.TYPED_CABIN
                    ? Confirmation.UNCONFIRMED : Confirmation.CONFIRMED, result);
            assertTrue(rail.name(), clock.now <= 100_000L + window + 350L);
        }
    }

    @Test
    public void legacyAdapterKeepsTodaysTwoReadsThreeHundredFiftyMsApart() throws Exception {
        FakeClock clock = new FakeClock();
        // A legacy (non-Di5) readDoorLockState() returning the target on every poll: read-time stamps
        // make consecutive polls distinct, so the second poll confirms exactly as the old loop did.
        Confirmation result = run(clock,
                () -> DiLink5GateActuationPolicy.legacyReadback(LOCK, clock.now), LOCK, 4_000L);
        assertEquals(Confirmation.CONFIRMED, result);
        assertEquals(1, clock.sleeps);
        assertEquals(100_350L, clock.now);
        // Legacy non-target then target then target: reset, then two → confirmed on the third poll.
        FakeClock clock2 = new FakeClock();
        int[] reads = {UNLOCK, LOCK, LOCK};
        int[] i = {0};
        result = run(clock2, () -> DiLink5GateActuationPolicy.legacyReadback(
                reads[Math.min(i[0]++, reads.length - 1)], clock2.now), LOCK, 4_000L);
        assertEquals(Confirmation.CONFIRMED, result);
        assertEquals(2, clock2.sleeps);
        // Legacy INVALID forever: runs out the old 4 s window.
        FakeClock clock3 = new FakeClock();
        result = run(clock3, () -> DiLink5GateActuationPolicy.legacyReadback(0, clock3.now), LOCK, 4_000L);
        assertEquals(Confirmation.UNCONFIRMED, result);
        assertTrue(clock3.now >= 104_000L && clock3.now < 104_350L + 350L);
        BydDataCollector.DiLink5LockEvidence legacy = DiLink5GateActuationPolicy.legacyReadback(LOCK, 5L);
        assertEquals(LOCK, legacy.state);
        assertEquals(5L, legacy.observedAtElapsedMs);
        assertEquals(null, legacy.rail);
    }

    @Test
    public void performDecisionUsesOneFinalEvidenceReadAndTheDistinctReadbackLoop() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        int start = handler.indexOf("private static void performDecision(");
        int end = handler.indexOf("private static JSONObject bridge(String operation)", start);
        String body = handler.substring(start, end);
        // LOW-3: exactly one finalEvidence read; finalLock is derived from it on DI5.
        assertEquals(1, count(body, "finalEvidence =\n"
                + "                    DiLink5Platform.isSelected() ? collector.readDiLink5GateDoorLockEvidence() : null;"));
        assertEquals(1, count(body, "BydDataCollector.DiLink5LockEvidence finalEvidence ="));
        assertTrue(body.contains(
                "int finalLock = finalEvidence != null ? finalEvidence.state : collector.readDoorLockState();"));
        // The existing numeric checks on finalLock are untouched.
        assertTrue(body.contains("|| !decision.valid() || (finalLock != 1 && finalLock != 2)) {"));
        assertTrue(body.contains("if (finalLock == target) {"));
        // The old fixed loop is gone; the pure loop runs with the rail's window.
        assertFalse(body.contains("long deadline = SystemClock.elapsedRealtime() + 4000;"));
        assertFalse(body.contains("Thread.sleep(350);"));
        assertTrue(body.contains("DiLink5GateActuationPolicy.confirmByDistinctReadbacks("));
        assertTrue(body.contains("target, DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail),"));
        assertTrue(body.contains("SystemClock::elapsedRealtime, Thread::sleep);"));
        assertTrue(body.contains("? collector::readDiLink5GateDoorLockEvidence"));
        assertTrue(body.contains(": () -> DiLink5GateActuationPolicy.legacyReadback(\n"
                + "                                            collector.readDoorLockState(), SystemClock.elapsedRealtime()),"));
        // Outcomes keep today's texts.
        assertTrue(body.contains("if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {\n"
                + "                lastCommandConfirmed = true;\n"
                + "                decision.satisfied();\n"
                + "                String confirmed = driverDoorStateConfirmed(action);\n"
                + "                recordAction(action, \"confirmed\", confirmed);"));
        assertTrue(body.contains(
                "recordAction(action, \"unconfirmed\", \"Command accepted; driver-door change could not be confirmed\");"));
        // Case (C) burst only for the carsvc actuation rail; the app outcome is taken on the dispatch thread.
        assertTrue(body.contains(
                "if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(12_000L);"));
        assertEquals(1, count(body, "requestDiLink5CarSvcLockBurst("));
        assertTrue(body.contains(
                "lockOutcome = com.overdrive.app.byd.VehicleActuatorBridge.takeLastDiLink5LockOutcome();"));
        assertTrue(body.contains("DiLink5GateActuationPolicy.preferredActuationRail(finalEvidence, otaEvidence);"));
        assertTrue(body.contains("collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.OTA);"));
        // The scope carries the actuation rail's evidence.
        assertTrue(body.contains("DiLink5GateActuationPolicy.gateScope("));
        assertTrue(body.contains("decision.observed + decision.freshnessMs, actuationEvidence);"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
