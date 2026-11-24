package com.overdrive.app.camera;

/**
 * Owns the OEM burn-in renderer and its single telemetry polling hold. Never called
 * from the encoder GL thread; finishSession() runs only after that thread stops drawing.
 */
final class OemOverlayLifecycle<C, R> {
    interface Operations<C, R> {
        R createRenderer();
        void updateFields(R renderer);
        void updateDemand(C collector);
        void acquirePolling(C collector);
        void releasePolling(C collector);
        void startRaster(R renderer, C collector);
        void stopRaster(R renderer);
        void releaseRenderer(R renderer);
    }

    private final Operations<C, R> operations;
    private R renderer;
    private C heldCollector;
    private boolean sessionActive;
    private boolean creationAttempted;

    OemOverlayLifecycle(Operations<C, R> operations) {
        this.operations = operations;
    }

    synchronized void beginSession() {
        if (sessionActive || renderer != null || heldCollector != null) {
            throw new IllegalStateException("OEM overlay session already active");
        }
        creationAttempted = false;
        sessionActive = true;
    }

    /** Reconcile after a lifecycle/config/recording transition (not from GL). */
    synchronized boolean reconcile(C collector, boolean enabled, boolean running,
            boolean recording) {
        if (!sessionActive) return false;
        if (enabled && running && collector != null && !creationAttempted) {
            creationAttempted = true;  // a failed allocation is not retried this session
            renderer = operations.createRenderer();
        }

        C desired = enabled && running && recording && renderer != null
                ? collector : null;
        if (heldCollector != desired) {
            if (heldCollector != null) {
                operations.releasePolling(heldCollector);
                heldCollector = null;
            }
            if (desired != null) {
                operations.updateDemand(desired);
                operations.acquirePolling(desired);
                heldCollector = desired;
            }
        } else if (desired != null) {
            // Changes to selected fields must update HAL demand mid-clip.
            operations.updateDemand(desired);
        }

        if (renderer != null) {
            operations.updateFields(renderer);
            if (desired != null) operations.startRaster(renderer, desired);
            else operations.stopRaster(renderer);
        }
        return desired != null;
    }

    /** Called only after encoder GL rendering is quiescent. */
    synchronized void finishSession() {
        try {
            if (heldCollector != null) {
                C held = heldCollector;
                heldCollector = null;
                operations.releasePolling(held);
            }
        } finally {
            // A failing collector must not leave the raster worker running.
            try {
                if (renderer != null) {
                    R r = renderer;
                    renderer = null;
                    operations.stopRaster(r);
                    operations.releaseRenderer(r);
                }
            } finally {
                sessionActive = false;
                creationAttempted = false;
            }
        }
    }
}
