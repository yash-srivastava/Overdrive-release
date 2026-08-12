package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingReconcilePolicyTest {

    @Test
    public void deletesOnlyMissingRowsFromCompleteAvailableRoots() {
        assertTrue(RecordingReconcilePolicy.shouldDeleteMissingRow(true, true, false));
        assertFalse(RecordingReconcilePolicy.shouldDeleteMissingRow(true, true, true));
        assertFalse(RecordingReconcilePolicy.shouldDeleteMissingRow(false, true, false));
        assertFalse(RecordingReconcilePolicy.shouldDeleteMissingRow(true, false, false));
        assertFalse(RecordingReconcilePolicy.shouldDeleteMissingRow(false, false, false));
    }
}