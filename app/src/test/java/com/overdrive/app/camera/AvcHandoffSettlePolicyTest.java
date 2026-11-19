package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Executable, Android-free assertions for the ACC prewarm receipt state machine. */
public class AvcHandoffSettlePolicyTest {
    private static final long START = TimeUnit.SECONDS.toNanos(100);

    @Test
    public void completedPrewarmCanBeUsedOnceForSamePidAfterCacheExpires() {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        receipt.issueFromCompletedPrewarm(START, 1234);
        assertTrue(receipt.consumeIfEligible(START + TimeUnit.SECONDS.toNanos(19),
                START, 1234, true, false));
        assertFalse(receipt.consumeIfEligible(START + TimeUnit.SECONDS.toNanos(20),
                START, 1234, true, false));
    }

    @Test
    public void noFullSettleProofNeverAdmitsEvenWithRunningPid() {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        assertFalse(receipt.consumeIfEligible(START, START, 1234, true, false));
    }

    @Test
    public void wrongPidCannotAdmitAndCannotBeRevived() {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        receipt.issueFromCompletedPrewarm(START, 1234);
        assertFalse(receipt.consumeIfEligible(START + 1, START, 9876, true, false));
        receipt.issueFromCompletedPrewarm(START + 2, 9876);
        assertFalse(receipt.consumeIfEligible(START + 3, START + 2, 9876, true, false));
    }

    @Test
    public void inconclusiveProbeFallsBackToColdOpenLaunchDecision() {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        receipt.issueFromCompletedPrewarm(START, 1234);
        assertFalse(receipt.consumeIfEligible(START + 1, START, -1, false, false));
        // A rejected receipt does not alter the existing UNKNOWN/ABSENT policy.
        assertTrue(AvcHandoffSettlePolicy.requiresLaunch(false, false));
        assertTrue(AvcHandoffSettlePolicy.requiresLaunch(true, true));
        assertFalse(AvcHandoffSettlePolicy.requiresLaunch(false, true));
    }

    @Test
    public void staleSupersededFutureOrRecoveryReceiptsAreRejected() {
        assertRejected(START + TimeUnit.SECONDS.toNanos(31), START, 1234, false);
        assertRejected(START - 1, START, 1234, false);
        assertRejected(START + 1, START + 2, 1234, false);
        assertRejected(START + 1, START, 1234, true);
    }

    @Test
    public void retryRetiresPrewarmReceiptEvenIfNothingConsumedIt() {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        receipt.issueFromCompletedPrewarm(START, 1234);
        receipt.retire();
        assertFalse(receipt.consumeIfEligible(START + 1, START, 1234, true, false));
    }

    private static void assertRejected(long now, long latest, int pid, boolean recovery) {
        AvcHandoffSettlePolicy.Receipt receipt = new AvcHandoffSettlePolicy.Receipt();
        receipt.issueFromCompletedPrewarm(START, 1234);
        assertFalse(receipt.consumeIfEligible(now, latest, pid, true, recovery));
    }
}
