package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writer-owned rotation: arm semantics, ROTATE-ticket queue admission, and
 * the writer-side generation guards.
 *
 * <p>What is deliberately NOT covered here: the actual muxer swap (MediaMuxer
 * is unmockable in the unit harness) and the timing-window races (close vs
 * in-flight writer commit), which are exercised by the on-device validation
 * plan. These tests pin the pure-JVM invariants the design depends on:
 * <ul>
 *   <li>the arm survives rotateSegment()'s return (ownership transfer — no
 *       finally-reset), and double-arms coalesce;</li>
 *   <li>a ROTATE ticket is admitted into a FULL queue only by evicting one
 *       audio packet, never video or a control entry, and admission failure
 *       is reported instead of silently dropping the ticket;</li>
 *   <li>offerMuxerPacket reports admission (the initial-live-keyframe gate
 *       must not clear on a refused IDR), never evicts queued video or
 *       control entries, and audio never displaces anything;</li>
 *   <li>a stale-generation ticket is discarded WITHOUT touching a successor
 *       recording's arm flags, while a same-generation ticket arriving after
 *       close clears its own arm.</li>
 * </ul>
 */
public class HardwareEventRecorderGpuWriterRotationTest {

    private DaemonLogger.Config originalLoggerConfig;
    private Class<?> packetClass;

    @Before
    public void setUp() throws Exception {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        packetClass = Class.forName(
                "com.overdrive.app.surveillance.HardwareEventRecorderGpu$MuxerPacket");
    }

    @After
    public void tearDown() {
        DaemonLogger.configure(originalLoggerConfig);
    }

    // ==================== arm semantics ====================

    /** Fake-clock subclass: android.jar's SystemClock stub throws in the
     *  JVM harness, so the arm test overrides the package-private seam.
     *  Unsafe allocation skips field initializers — the test sets fakeNow. */
    static class FakeClockRecorder extends HardwareEventRecorderGpu {
        long fakeNow;
        // Never invoked (Unsafe allocation); exists only so the implicit
        // super() requirement compiles.
        FakeClockRecorder() { super(1, 1, 1, 1); }
        @Override long rotationClockMs() { return fakeNow; }
    }

    @Test
    public void armSurvivesReturnAndDoubleArmCoalesces() throws Exception {
        FakeClockRecorder recorder = allocate(FakeClockRecorder.class);
        setField(recorder, "muxerWriteQueue", new LinkedBlockingDeque<Object>(4));
        setField(recorder, "rotationInFlight", new AtomicBoolean(false));
        recorder.fakeNow = 12_345L;
        setField(recorder, "isWritingToFile", true);
        setField(recorder, "savedFormat", allocate(MediaFormat.class));

        Method rotate = HardwareEventRecorderGpu.class
                .getDeclaredMethod("rotateSegment");
        rotate.setAccessible(true);
        rotate.invoke(recorder);

        AtomicBoolean gate = (AtomicBoolean) getField(recorder, "rotationInFlight");
        assertTrue("arm must survive rotateSegment()'s return (ownership "
                + "transfer — a finally-reset here disables rotation entirely)",
                gate.get());
        assertTrue("drainer cue must be raised",
                getBooleanField(recorder, "rotationAwaitingSplice"));
        assertEquals("arm clock must be stamped from the monotonic seam",
                12_345L, field(recorder, "rotationArmedAtMs").getLong(recorder));

        // Second arm while pending: coalesced, state unchanged.
        recorder.fakeNow = 99_999L;
        rotate.invoke(recorder);
        assertTrue(gate.get());
        assertEquals("deadline anchor must not move on a coalesced arm",
                12_345L, field(recorder, "rotationArmedAtMs").getLong(recorder));
    }

    // ==================== ROTATE ticket admission ====================

    @Test
    public void rotateTicketEvictsOneDataPacketNeverControl() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(3);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue =
                (LinkedBlockingDeque<Object>) getField(recorder, "muxerWriteQueue");

        Object history = makeFlushHistoryPacket();
        Object videoP = makeDataPacket(recorder, /*video=*/true, /*key=*/false);
        Object audio = makeDataPacket(recorder, /*video=*/false, /*key=*/false);
        queue.offer(history);
        queue.offer(videoP);
        queue.offer(audio);

        Object ticket = makeRotateTicket(recorder, 1L);
        assertTrue("full queue must admit the ROTATE ticket by eviction",
                (Boolean) invokeOffer(recorder, "offerControlToQueue", ticket));

        assertTrue("control entry must never be evicted", queue.contains(history));
        assertTrue("queued video is never evicted (later P-frames reference it)",
                queue.contains(videoP));
        assertFalse("audio is the only legal victim", queue.contains(audio));
        assertTrue("ticket must be in the queue", queue.contains(ticket));
        assertEquals("audio drop counter must record the eviction", 1L,
                ((AtomicLong) getField(recorder, "audioDropCount")).get());
        assertEquals(0L, ((AtomicLong) getField(recorder, "muxerDropCount")).get());

        // No audio to displace: refused, queued video untouched.
        HardwareEventRecorderGpu recorder2 = newRecorder(2);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue2 =
                (LinkedBlockingDeque<Object>) getField(recorder2, "muxerWriteQueue");
        Object videoP2 = makeDataPacket(recorder2, true, false);
        queue2.offer(makeFlushHistoryPacket());
        queue2.offer(videoP2);
        assertFalse("ticket must be refused rather than evict video",
                (Boolean) invokeOffer(recorder2, "offerControlToQueue",
                        makeRotateTicket(recorder2, 1L)));
        assertTrue(queue2.contains(videoP2));
    }

    @Test
    public void rotateTicketRefusedOnAllControlQueueWithoutSilentDrop() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(1);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue =
                (LinkedBlockingDeque<Object>) getField(recorder, "muxerWriteQueue");
        Object history = makeFlushHistoryPacket();
        queue.offer(history);

        Object ticket = makeRotateTicket(recorder, 1L);
        assertFalse("all-control queue must REFUSE (caller keeps ticket + arm)",
                (Boolean) invokeOffer(recorder, "offerControlToQueue", ticket));
        assertTrue("control entry untouched", queue.contains(history));
        assertFalse("refused ticket must not be half-admitted",
                queue.contains(ticket));
    }

    // ==================== data-packet admission reporting ====================

    @Test
    public void offerMuxerPacketReportsAdmissionAndSparesControl() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(2);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue =
                (LinkedBlockingDeque<Object>) getField(recorder, "muxerWriteQueue");

        Object history = makeFlushHistoryPacket();
        Object keyframe = makeDataPacket(recorder, true, true);
        queue.offer(history);
        queue.offer(keyframe);

        // No audio queued: the incoming frame is refused and reported; queued
        // video and the control entry are untouched.
        Object incoming = makeDataPacket(recorder, true, false);
        assertFalse("refusal must be reported false",
                (Boolean) invokeOffer(recorder, "offerMuxerPacket", incoming));
        assertTrue("control survives", queue.contains(history));
        assertTrue("queued keyframe is never evicted", queue.contains(keyframe));
        assertFalse(queue.contains(incoming));
        assertEquals("video drop counter must record the refusal", 1L,
                ((AtomicLong) getField(recorder, "muxerDropCount")).get());

        // Audio never displaces video; video displaces the oldest audio.
        HardwareEventRecorderGpu recorder3 = newRecorder(2);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue3 =
                (LinkedBlockingDeque<Object>) getField(recorder3, "muxerWriteQueue");
        Object queuedP = makeDataPacket(recorder3, true, false);
        Object queuedAudio = makeDataPacket(recorder3, false, false);
        queue3.offer(queuedP);
        queue3.offer(queuedAudio);
        assertFalse("incoming audio is refused at a full queue",
                (Boolean) invokeOffer(recorder3, "offerMuxerPacket",
                        makeDataPacket(recorder3, false, false)));
        assertTrue(queue3.contains(queuedP));
        Object incomingP = makeDataPacket(recorder3, true, false);
        assertTrue("incoming video displaces the oldest audio",
                (Boolean) invokeOffer(recorder3, "offerMuxerPacket", incomingP));
        assertTrue(queue3.contains(queuedP));
        assertFalse(queue3.contains(queuedAudio));
        assertTrue(queue3.contains(incomingP));

        // Queue of ONLY control: nothing evictable — admission must report
        // false (the initial-live-keyframe gate must not clear on this).
        HardwareEventRecorderGpu recorder2 = newRecorder(1);
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue2 =
                (LinkedBlockingDeque<Object>) getField(recorder2, "muxerWriteQueue");
        queue2.offer(makeFlushHistoryPacket());
        assertFalse("refused admission must be reported false",
                (Boolean) invokeOffer(recorder2, "offerMuxerPacket",
                        makeDataPacket(recorder2, true, true)));
    }

    // ==================== writer-side generation guards ====================

    @Test
    public void staleGenerationTicketDoesNotTouchSuccessorArm() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(4);
        setField(recorder, "recordingGeneration", 7L);
        setField(recorder, "isWritingToFile", true);
        // Successor recording's own arm is live:
        ((AtomicBoolean) getField(recorder, "rotationInFlight")).set(true);
        setField(recorder, "rotationAwaitingSplice", true);

        Object staleTicket = makeRotateTicket(recorder, 6L);
        invokeHandler(recorder, staleTicket);

        assertTrue("stale ticket must NOT clear the successor's gate",
                ((AtomicBoolean) getField(recorder, "rotationInFlight")).get());
        assertTrue("stale ticket must NOT clear the successor's splice cue",
                getBooleanField(recorder, "rotationAwaitingSplice"));
    }

    @Test
    public void sameGenerationTicketAfterCloseClearsOwnArm() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(4);
        setField(recorder, "recordingGeneration", 7L);
        setField(recorder, "isWritingToFile", false);  // recording ended
        ((AtomicBoolean) getField(recorder, "rotationInFlight")).set(true);

        Object ownTicket = makeRotateTicket(recorder, 7L);
        invokeHandler(recorder, ownTicket);

        assertFalse("same-generation ticket after close abandons ITS OWN arm",
                ((AtomicBoolean) getField(recorder, "rotationInFlight")).get());
    }

    // ==================== finalizer callback fence ====================

    @Test
    public void lateFinalizerCallbackDroppedWhenOwnershipMovedOn() throws Exception {
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "listenerGeneration", 5L);
        // TWO-EPOCH DECOUPLING: close bumps recordingGeneration at ENTRY for
        // ticket invalidation. That bump must NOT suppress the closing
        // recording's own valid callbacks (single-counter regression: every
        // rotation-adjacent close silently lost the final segment's
        // surveillance metadata). Simulate close-entry state:
        setField(recorder, "recordingGeneration", 99L);

        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.SegmentListener listener =
                (closedSegment, newSegment) -> calls.incrementAndGet();

        Method dispatch = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "dispatchSegmentClosedFenced",
                HardwareEventRecorderGpu.SegmentListener.class,
                long.class, java.io.File.class, java.io.File.class);
        dispatch.setAccessible(true);

        // Ownership unchanged (finalizer completing during close's waits,
        // ticket epoch already bumped): MUST be delivered.
        dispatch.invoke(recorder, listener, 5L, null, new java.io.File("next.mp4"));
        assertEquals("callback owned by the closing recording must be "
                + "delivered even after the ticket epoch moved", 1, calls.get());

        // Ownership moved on (post-wait bump / successor trigger — the
        // bounded-wait overrun case): dropped, so the stale handler can't
        // mutate live engine state.
        dispatch.invoke(recorder, listener, 4L, null, new java.io.File("next.mp4"));
        assertEquals("stale-ownership callback must be dropped", 1, calls.get());

        // Null listener: no-op regardless of generation.
        dispatch.invoke(recorder, null, 5L, null, new java.io.File("next.mp4"));
        assertEquals(1, calls.get());
    }

    @Test
    public void finalizerCallbacksDispatchInSegmentOrder() throws Exception {
        // Rotations can be ~1.5 s apart (force + audio follow-up). Under the
        // old thread-per-rotation design, segment N+1's finalizer (fast)
        // could overtake segment N's (slow), delivering callbacks out of
        // segment order and rolling engine state backward. The single-worker
        // FIFO executor makes callback order == scheduling order.
        HardwareEventRecorderGpu recorder = newRecorder(2);

        final java.util.List<String> order =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        HardwareEventRecorderGpu.SegmentListener listener = (closed, next) -> {
            if (order.isEmpty()) {
                // First callback dawdles — a per-rotation thread design lets
                // the second finalizer overtake it here.
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
            order.add(next.getName());
        };

        Method fin = finalizeMethod();
        // oldMuxer/oldTemp null: the task skips all muxer/file I/O and goes
        // straight to the fenced dispatch — pure ordering exercise. Listener,
        // geo, and epoch ride as commit-point parameters.
        fin.invoke(recorder, null, null, "old-1", 1, 0, -1L, -1L, false,
                new java.io.File("after-seg1"), listener, null, 0L);
        fin.invoke(recorder, null, null, "old-2", 2, 0, -1L, -1L, false,
                new java.io.File("after-seg2"), listener, null, 0L);

        java.util.concurrent.atomic.AtomicInteger inFlight =
                (java.util.concurrent.atomic.AtomicInteger)
                        getField(recorder, "inFlightFinalizers");
        long deadline = System.currentTimeMillis() + 5_000;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("both finalizers must complete", 0, inFlight.get());
        assertEquals("callbacks must arrive in segment order even when the"
                        + " first finalizer is slow",
                java.util.Arrays.asList("after-seg1", "after-seg2"), order);
    }

    @Test
    public void stragglerCallbackDroppedAsSuperseded() throws Exception {
        // A finalizer that wedged past the order bound: a successor skipped
        // ahead and advanced the dispatch cursor beyond our seq. Delivering
        // late would roll engine state backward — must drop, without waiting.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "finalizerDispatchedUpTo", 5L);

        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.SegmentListener listener =
                (closed, next) -> calls.incrementAndGet();

        long start = System.currentTimeMillis();
        invokeDeliverInOrder(recorder, 3L, listener);
        assertEquals("superseded straggler must be dropped", 0, calls.get());
        assertTrue("superseded drop must not burn the order-wait budget",
                System.currentTimeMillis() - start < 2_000);
    }

    @Test
    public void dispatchSkipsAheadPastWedgedPredecessor() throws Exception {
        // Predecessor (seq 1-2) never dispatches (wedged in native stop()).
        // Our seq-3 delivery must not wait forever: the interrupt (standing
        // in for the bounded-wait expiry, so the test doesn't sleep 5 s)
        // breaks the wait, we skip ahead, deliver, and advance the cursor so
        // the stragglers get dropped later.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "finalizerDispatchedUpTo", 0L);

        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.SegmentListener listener =
                (closed, next) -> calls.incrementAndGet();

        Thread.currentThread().interrupt();
        try {
            invokeDeliverInOrder(recorder, 3L, listener);
        } finally {
            // Consume the flag so it can't leak into other tests.
            Thread.interrupted();
        }
        assertEquals("skip-ahead must still deliver our own callback", 1, calls.get());
        assertEquals("cursor must advance past our seq so stragglers drop",
                3L, field(recorder, "finalizerDispatchedUpTo").getLong(recorder));
    }

    @Test
    public void timeoutBurstDrainsReadyWaitersInOrderInsteadOfDroppingThem()
            throws Exception {
        // Seq 1 is wedged (never arrives). Seq 2 and seq 3 are both READY
        // and waiting. When the order wait expires, the gate must bridge the
        // cursor only up to the LOWEST ready waiter — under the old
        // skip-to-self logic, whichever waiter won the monitor race (often
        // seq 3) advanced the cursor past seq 2 and dropped a perfectly
        // healthy callback as superseded.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "finalizerDispatchedUpTo", 0L);
        setField(recorder, "finalizerDispatchOrderWaitMs", 200L);

        final java.util.List<String> delivered =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        HardwareEventRecorderGpu.SegmentListener recA =
                (closed, next) -> delivered.add("A");
        HardwareEventRecorderGpu.SegmentListener recB =
                (closed, next) -> delivered.add("B");

        Thread ta = new Thread(() -> {
            try { invokeDeliverInOrder(recorder, 2L, recA); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "waiter-seq2");
        Thread tb = new Thread(() -> {
            try { invokeDeliverInOrder(recorder, 3L, recB); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, "waiter-seq3");
        ta.start();
        tb.start();
        ta.join(5_000);
        tb.join(5_000);
        assertFalse("waiters must terminate", ta.isAlive() || tb.isAlive());

        assertEquals("BOTH ready callbacks must survive a timeout burst, in order",
                java.util.Arrays.asList("A", "B"), delivered);
        assertEquals("cursor must land past the highest delivered seq",
                3L, field(recorder, "finalizerDispatchedUpTo").getLong(recorder));
    }

    @Test
    public void newGenerationSupersedesDeadRecordingsSequenceRange() throws Exception {
        // A dead recording left seqs 3..5 unfinished (wedged finalizers) and
        // the cursor at 2. The successor recording's FIRST finalizer must
        // not queue behind that gap: scheduling under a new
        // listenerGeneration supersedes the old range immediately.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "finalizerSeqLast", 5L);
        setField(recorder, "finalizerDispatchedUpTo", 2L);
        setField(recorder, "finalizerLastScheduledGen", 1L);
        setField(recorder, "listenerGeneration", 2L);  // successor's epoch

        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.SegmentListener listener =
                (closed, next) -> calls.incrementAndGet();

        Method fin = finalizeMethod();
        long start = System.currentTimeMillis();
        fin.invoke(recorder, null, null, "old", 1, 0, -1L, -1L, false,
                new java.io.File("succ-seg"), listener, null, 2L);

        java.util.concurrent.atomic.AtomicInteger inFlight =
                (java.util.concurrent.atomic.AtomicInteger)
                        getField(recorder, "inFlightFinalizers");
        long deadline = System.currentTimeMillis() + 5_000;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("successor finalizer must complete", 0, inFlight.get());
        assertEquals("successor callback must be delivered", 1, calls.get());
        assertTrue("successor must NOT wait out the dead recording's gap",
                System.currentTimeMillis() - start < 3_000);
        assertEquals("cursor must land on the successor's seq", 6L,
                field(recorder, "finalizerDispatchedUpTo").getLong(recorder));
        assertEquals("scheduled-generation tracker must advance", 2L,
                field(recorder, "finalizerLastScheduledGen").getLong(recorder));
    }

    @Test
    public void writerAbortNotificationNeverRunsOnCallerThread() throws Exception {
        // Both abort-discovery sites run on worker threads (disk writer /
        // drainer) that the listener's stopEventRecording response must
        // JOIN. A synchronous callback self-joins, fails the stop deadline,
        // and falsely latches the terminal wedge — dispatch must always be
        // on a detached thread.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Thread> cbThread =
                new java.util.concurrent.atomic.AtomicReference<>();
        HardwareEventRecorderGpu.WriterAbortListener listener = reason -> {
            cbThread.set(Thread.currentThread());
            latch.countDown();
        };
        setField(recorder, "writerAbortListener", listener);

        Method m = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "notifyWriterAbortedAsync", String.class, long.class);
        m.setAccessible(true);
        m.invoke(recorder, "test-abort", 0L);

        assertTrue("abort callback must be dispatched",
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("abort callback must NOT run on the invoking thread",
                cbThread.get() != Thread.currentThread());
    }

    @Test
    public void duplicateAbortNotificationSuppressedPerGeneration() throws Exception {
        // Both discovery sites (disk writer's failure threshold + drainer's
        // abort-stop branch) fire for the SAME abort — the listener must see
        // exactly one delivery per recording generation.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "recordingGeneration", 7L);

        final java.util.concurrent.CountDownLatch first =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.WriterAbortListener listener = reason -> {
            calls.incrementAndGet();
            first.countDown();
        };
        setField(recorder, "writerAbortListener", listener);

        Method m = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "notifyWriterAbortedAsync", String.class, long.class);
        m.setAccessible(true);
        // Both sites pass the SAME latch-time generation stamp.
        m.invoke(recorder, "writer abort", 7L);   // disk-writer site
        m.invoke(recorder, "drainer abort", 7L);  // drainer's branch, same abort

        assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // Give a would-be duplicate a moment to (incorrectly) arrive.
        Thread.sleep(150);
        assertEquals("exactly one delivery per abort generation", 1, calls.get());
    }

    @Test
    public void duplicateAbortSuppressedEvenWhenCloseBumpsGenerationBetweenSites()
            throws Exception {
        // The abort generation is stamped ONCE at the failure latch. A close
        // bumping the live recordingGeneration between the writer's
        // notification and the drainer's must not make the second site look
        // like a brand-new abort (the live-read design did exactly that).
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "recordingGeneration", 5L);

        final java.util.concurrent.CountDownLatch first =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.WriterAbortListener listener = reason -> {
            calls.incrementAndGet();
            first.countDown();
        };
        setField(recorder, "writerAbortListener", listener);

        Method m = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "notifyWriterAbortedAsync", String.class, long.class);
        m.setAccessible(true);
        // Writer site fires with the latch stamp (5) while gen is live.
        m.invoke(recorder, "writer abort", 5L);
        assertTrue(first.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // Close bumps the live generation between the two sites...
        setField(recorder, "recordingGeneration", 6L);
        // ...and the drainer's site fires with the SAME latch stamp.
        m.invoke(recorder, "drainer abort", 5L);
        Thread.sleep(150);
        assertEquals("second site must be suppressed despite the interleaved"
                + " generation bump", 1, calls.get());
    }

    @Test
    public void staleAbortDeliveryDroppedAfterRecoveryCycled() throws Exception {
        // A delayed abort callback landing after RMM's recovery started a
        // successor recording must be dropped — the listeners read LIVE
        // encoder/recording state and would stop the healthy successor.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        setField(recorder, "recordingGeneration", 9L);

        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HardwareEventRecorderGpu.WriterAbortListener listener =
                reason -> calls.incrementAndGet();

        Method deliver = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "deliverWriterAbort",
                HardwareEventRecorderGpu.WriterAbortListener.class,
                long.class, String.class);
        deliver.setAccessible(true);

        // Abort stamped for generation 8; recovery already moved to 9.
        deliver.invoke(recorder, listener, 8L, "stale abort");
        assertEquals("stale abort must be dropped", 0, calls.get());

        // Same generation: delivered.
        deliver.invoke(recorder, listener, 9L, "live abort");
        assertEquals(1, calls.get());
    }

    @Test
    public void danglingAbortedMuxerQuarantinedBeforeSuccessorTrigger() throws Exception {
        // After a writer abort where the drainer cleared the flags first,
        // neither the owner's stop nor the abort listener's quarantine runs
        // (both short-circuit on the cleared flags) — the muxer handle and
        // the half-written tmp dangle. The trigger-side reclaim must release
        // the handle and quarantine the tmp as .broken so the user never
        // sees a corrupt file with a final .mp4 name.
        HardwareEventRecorderGpu recorder = newRecorder(2);
        java.io.File tmp = java.io.File.createTempFile("herg-abort", ".mp4.tmp");
        java.io.File finalPath = new java.io.File(
                tmp.getParentFile(), tmp.getName().replace(".mp4.tmp", ".mp4"));
        java.io.File broken = new java.io.File(finalPath.getAbsolutePath() + ".broken");
        setField(recorder, "muxer", allocate(android.media.MediaMuxer.class));
        setField(recorder, "muxerStarted", true);
        setField(recorder, "tempFile", tmp);
        setField(recorder, "outputPath", finalPath.getAbsolutePath());

        // Stale residue: the drainer's in-flight pass enqueued one more
        // old-recording packet AFTER the aborted writer's one-time drain.
        // The reclaim must purge it — otherwise the replacement writer
        // stamps the successor muxer's first sample with a dead recording's
        // PTS.
        @SuppressWarnings("unchecked")
        LinkedBlockingDeque<Object> queue =
                (LinkedBlockingDeque<Object>) getField(recorder, "muxerWriteQueue");
        queue.offer(makeDataPacket(recorder, true, false));

        Method m = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "quarantineAbortedMuxer");
        m.setAccessible(true);
        try {
            m.invoke(recorder);

            assertTrue("stale queued packets from the aborted recording must "
                    + "be purged", queue.isEmpty());
            assertTrue("muxer reference must be released and cleared",
                    getField(recorder, "muxer") == null);
            assertFalse("muxerStarted must clear",
                    getBooleanField(recorder, "muxerStarted"));
            assertFalse("half-written tmp must not remain under its tmp name",
                    tmp.exists());
            assertTrue("tmp must be quarantined as .broken", broken.exists());
            assertTrue("tempFile reference must clear",
                    getField(recorder, "tempFile") == null);
        } finally {
            tmp.delete();
            broken.delete();
        }
    }

    private static Method finalizeMethod() throws Exception {
        Method fin = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "finalizeOldSegmentAsync",
                android.media.MediaMuxer.class, java.io.File.class, String.class,
                int.class, int.class, long.class, long.class, boolean.class,
                java.io.File.class,
                HardwareEventRecorderGpu.SegmentListener.class,
                com.overdrive.app.geo.GeoSnapshot.class, long.class);
        fin.setAccessible(true);
        return fin;
    }

    private void invokeDeliverInOrder(HardwareEventRecorderGpu recorder,
                                      long seq,
                                      HardwareEventRecorderGpu.SegmentListener l)
            throws Exception {
        Method m = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "deliverSegmentClosedInOrder",
                long.class, HardwareEventRecorderGpu.SegmentListener.class,
                long.class, java.io.File.class, java.io.File.class);
        m.setAccessible(true);
        m.invoke(recorder, seq, l, 0L, null, new java.io.File("next.mp4"));
    }

    // ==================== harness ====================

    /** Recorder with just enough state installed for the queue/arm paths.
     *  Unsafe allocation skips final-field initializers, so every final the
     *  tested code touches is installed explicitly. Packets carry data=null
     *  so releaseMuxerPacket() is a no-op (no pool wiring needed). */
    private HardwareEventRecorderGpu newRecorder(int queueCapacity) throws Exception {
        HardwareEventRecorderGpu r = allocate(HardwareEventRecorderGpu.class);
        setField(r, "muxerWriteQueue", new LinkedBlockingDeque<Object>(queueCapacity));
        setField(r, "muxerDropCount", new AtomicLong());
        setField(r, "audioDropCount", new AtomicLong());
        setField(r, "rotationInFlight", new AtomicBoolean(false));
        setField(r, "muxerLock", new Object());
        setField(r, "finalizerDispatchLock", new Object());
        setField(r, "finalizerWaitingSeqs", new java.util.TreeSet<Long>());
        setField(r, "finalizerJoinLock", new Object());
        setField(r, "inFlightFinalizers",
                new java.util.concurrent.atomic.AtomicInteger(0));
        setField(r, "lastAbortNotifiedGen", new AtomicLong(Long.MIN_VALUE));
        // Unsafe allocation skips field INITIALIZERS, not just constructors:
        // without this, the order-wait bound silently becomes 0 and the gate
        // bridges/drops instantly whenever a higher seq enters first (a
        // thread-start race that let the ordering test pass by luck).
        setField(r, "finalizerDispatchOrderWaitMs", 5_000L);
        return r;
    }

    private Object makeDataPacket(HardwareEventRecorderGpu owner,
                                  boolean video, boolean keyframe) throws Exception {
        Object p = allocate(packetClass);
        MediaCodec.BufferInfo info = allocate(MediaCodec.BufferInfo.class);
        info.flags = keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        setPacketField(p, "info", info);
        setPacketField(p, "trackKind",
                getStaticInt(video ? "TRACK_KIND_VIDEO" : "TRACK_KIND_AUDIO"));
        return p;
    }

    private Object makeRotateTicket(HardwareEventRecorderGpu owner,
                                    long generation) throws Exception {
        Object p = allocate(packetClass);
        MediaCodec.BufferInfo info = allocate(MediaCodec.BufferInfo.class);
        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
        setPacketField(p, "info", info);
        setPacketField(p, "trackKind", getStaticInt("TRACK_KIND_ROTATE"));
        setPacketField(p, "rotateGeneration", generation);
        return p;
    }

    private Object makeFlushHistoryPacket() throws Exception {
        Object p = allocate(packetClass);
        MediaCodec.BufferInfo info = allocate(MediaCodec.BufferInfo.class);
        setPacketField(p, "info", info);
        setPacketField(p, "trackKind", getStaticInt("TRACK_KIND_VIDEO"));
        // historyAudio != null makes isFlushHistory() (and isControl()) true.
        setPacketField(p, "historyAudio", new java.util.ArrayList<Object>());
        return p;
    }

    private Object invokeOffer(HardwareEventRecorderGpu recorder,
                               String method, Object packet) throws Exception {
        Method m = HardwareEventRecorderGpu.class
                .getDeclaredMethod(method, packetClass);
        m.setAccessible(true);
        return m.invoke(recorder, packet);
    }

    private void invokeHandler(HardwareEventRecorderGpu recorder,
                               Object ticket) throws Exception {
        Method m = HardwareEventRecorderGpu.class
                .getDeclaredMethod("handleWriterRotatePacket", packetClass);
        m.setAccessible(true);
        m.invoke(recorder, ticket);
    }

    private int getStaticInt(String name) throws Exception {
        Field f = HardwareEventRecorderGpu.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private void setPacketField(Object packet, String name, Object value)
            throws Exception {
        Field f = packetClass.getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Integer) {
            f.setInt(packet, (Integer) value);
        } else if (value instanceof Long) {
            f.setLong(packet, (Long) value);
        } else {
            f.set(packet, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, type);
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = HardwareEventRecorderGpu.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field f = field(target, name);
        if (value instanceof Boolean) {
            f.setBoolean(target, (Boolean) value);
        } else if (value instanceof Long) {
            f.setLong(target, (Long) value);
        } else {
            f.set(target, value);
        }
    }

    private static boolean getBooleanField(Object target, String name)
            throws Exception {
        return field(target, name).getBoolean(target);
    }

    private static Object getField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }
}
