package com.overdrive.app.server;

final class RecordingReconcilePolicy {
    private RecordingReconcilePolicy() {}

    static boolean shouldDeleteMissingRow(boolean rootAvailable,
                                          boolean scanComplete,
                                          boolean rowDiscovered) {
        return rootAvailable && scanComplete && !rowDiscovered;
    }
}