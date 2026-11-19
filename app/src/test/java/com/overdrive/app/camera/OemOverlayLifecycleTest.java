package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises resource ownership and concurrent events, not the text of source files. */
public class OemOverlayLifecycleTest {
    private static final class Collector {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger demands = new AtomicInteger();
    }

    private static final class Renderer {
        int starts, stops, releases, fieldUpdates;
        Collector rasterCollector;
    }

    private static final class FakeOperations
            implements OemOverlayLifecycle.Operations<Collector, Renderer> {
        int allocations;
        Renderer lastRenderer;

        @Override public Renderer createRenderer() {
            allocations++;
            lastRenderer = new Renderer();
            return lastRenderer;
        }
        @Override public void updateFields(Renderer renderer) { renderer.fieldUpdates++; }
        @Override public void updateDemand(Collector collector) { collector.demands.incrementAndGet(); }
        @Override public void acquirePolling(Collector collector) { collector.starts.incrementAndGet(); }
        @Override public void releasePolling(Collector collector) { collector.stops.incrementAndGet(); }
        @Override public void startRaster(Renderer renderer, Collector collector) {
            renderer.starts++;
            renderer.rasterCollector = collector;
        }
        @Override public void stopRaster(Renderer renderer) {
            renderer.stops++;
            renderer.rasterCollector = null;
        }
        @Override public void releaseRenderer(Renderer renderer) { renderer.releases++; }
    }

    @Test public void disabledOverlayAllocatesNothingButLateEnableWorks() {
        FakeOperations ops = new FakeOperations();
        OemOverlayLifecycle<Collector, Renderer> lifecycle = new OemOverlayLifecycle<>(ops);
        Collector source = new Collector();
        lifecycle.beginSession();
        assertFalse(lifecycle.reconcile(source, false, true, true));
        assertEquals(0, ops.allocations);
        assertTrue(lifecycle.reconcile(source, true, true, true));
        assertEquals(1, ops.allocations);
        assertEquals(1, source.starts.get());
        assertEquals(source, ops.lastRenderer.rasterCollector);
        assertFalse(lifecycle.reconcile(source, false, true, true));
        assertEquals(1, source.stops.get());
        assertTrue(lifecycle.reconcile(source, true, true, true));
        assertEquals(1, ops.allocations);
        assertEquals(2, source.starts.get());
        lifecycle.finishSession();
        assertEquals(2, source.stops.get());
        assertEquals(1, ops.lastRenderer.releases);
    }

    @Test public void collectorReplacementAndNullReleaseTheOriginalLease() {
        FakeOperations ops = new FakeOperations();
        OemOverlayLifecycle<Collector, Renderer> lifecycle = new OemOverlayLifecycle<>(ops);
        Collector first = new Collector();
        Collector second = new Collector();
        lifecycle.beginSession();
        lifecycle.reconcile(first, true, true, true);
        lifecycle.reconcile(first, true, true, true);
        assertEquals(1, first.starts.get());
        assertEquals(2, first.demands.get()); // live field updates are not lost
        lifecycle.reconcile(second, true, true, true);
        assertEquals(1, first.stops.get());
        assertEquals(1, second.starts.get());
        assertEquals(second, ops.lastRenderer.rasterCollector);
        assertFalse(lifecycle.reconcile(null, true, true, true));
        assertEquals(1, second.stops.get());
        lifecycle.finishSession();
        assertEquals(1, second.stops.get());
        lifecycle.beginSession();
        lifecycle.reconcile(first, true, true, true);
        assertEquals(2, ops.allocations);
        lifecycle.finishSession();
    }

    @Test public void simultaneousReconcilesCannotDuplicatePollingLease() throws Exception {
        FakeOperations ops = new FakeOperations();
        OemOverlayLifecycle<Collector, Renderer> lifecycle = new OemOverlayLifecycle<>(ops);
        Collector source = new Collector();
        lifecycle.beginSession();
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    if (start.await(5, TimeUnit.SECONDS)) {
                        lifecycle.reconcile(source, true, true, true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, ops.allocations);
        assertEquals(1, source.starts.get());
        lifecycle.finishSession();
        assertEquals(1, source.stops.get());
    }

    @Test public void stoppedSessionIgnoresStaleReconcile() {
        FakeOperations ops = new FakeOperations();
        OemOverlayLifecycle<Collector, Renderer> lifecycle = new OemOverlayLifecycle<>(ops);
        Collector source = new Collector();
        lifecycle.beginSession();
        lifecycle.reconcile(source, true, true, false);
        Renderer first = ops.lastRenderer;
        lifecycle.finishSession();
        assertFalse(lifecycle.reconcile(source, true, true, true));
        assertEquals(0, source.starts.get());
        lifecycle.beginSession();
        assertTrue(lifecycle.reconcile(source, true, true, true));
        assertNotSame(first, ops.lastRenderer);
        lifecycle.finishSession();
    }
}
