package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality;
import com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RecordingCodecAvailabilityTest {
    @Test
    public void missReturnsUnknownWithoutRunningProbeAndReusesCompletedSnapshot() {
        Queue<Runnable> work = new ArrayDeque<>();
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache(work::add);
        AtomicInteger probes = new AtomicInteger();
        RecordingCodecAvailability.Result expected = new RecordingCodecAvailability.Result(true, false);
        assertSame(RecordingCodecAvailability.Result.UNKNOWN, cache.get("first", () -> {
            probes.incrementAndGet();
            return expected;
        }));
        assertSame(RecordingCodecAvailability.Result.UNKNOWN, cache.get("first", () -> {
            throw new AssertionError("duplicate probe");
        }));
        assertEquals(0, probes.get());
        assertEquals(1, work.size());
        work.remove().run();
        assertEquals(1, probes.get());
        assertSame(expected, cache.get("first", () -> { throw new AssertionError(); }));
    }

    @Test
    public void staleCompletionIsDiscardedAndChangingSettingsDoesNotQueueMoreWork() {
        Queue<Runnable> work = new ArrayDeque<>();
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache(work::add);
        cache.get("old", () -> new RecordingCodecAvailability.Result(true, false));
        for (int i = 0; i < 50; i++) {
            assertSame(RecordingCodecAvailability.Result.UNKNOWN,
                    cache.get("changed:" + i, () -> { throw new AssertionError(); }));
        }
        assertEquals(1, work.size());
        work.remove().run();
        RecordingCodecAvailability.Result expected = new RecordingCodecAvailability.Result(false, true);
        assertSame(RecordingCodecAvailability.Result.UNKNOWN, cache.get("changed:49", () -> expected));
        work.remove().run();
        assertSame(expected, cache.get("changed:49", () -> { throw new AssertionError(); }));
    }

    @Test
    public void retainsLastAcceptedSnapshotUntilCurrentProbeCompletes() {
        Queue<Runnable> work = new ArrayDeque<>();
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache(work::add);
        RecordingCodecAvailability.Result first = new RecordingCodecAvailability.Result(false, null);
        cache.get("first", () -> first);
        work.remove().run();
        assertSame(first, cache.get("first", () -> { throw new AssertionError(); }));
        assertFalse(first.stale);

        RecordingCodecAvailability.Result stale = cache.get("restarting",
                () -> new RecordingCodecAvailability.Result(true, true));
        assertTrue(stale.stale);
        assertEquals(Boolean.FALSE, stale.h264);
        assertNull(stale.h265);
        RecordingCodecAvailability.Result next = new RecordingCodecAvailability.Result(true, false);
        assertSame(stale, cache.get("restarted", () -> next));
        assertEquals(1, work.size());
        work.remove().run(); // Superseded completion must not replace the last accepted result.
        assertSame(stale, cache.get("restarted", () -> next));
        assertSame(stale, cache.get("restarted", () -> { throw new AssertionError(); }));
        assertEquals(1, work.size());
        work.remove().run();
        assertSame(next, cache.get("restarted", () -> { throw new AssertionError(); }));
        assertFalse(next.stale);

        assertTrue(cache.get("unknown", () -> RecordingCodecAvailability.Result.UNKNOWN).stale);
        work.remove().run();
        assertSame(RecordingCodecAvailability.Result.UNKNOWN,
                cache.get("unknown", () -> { throw new AssertionError(); }));
        assertSame(RecordingCodecAvailability.Result.UNKNOWN,
                cache.get("another", () -> next));
    }

    @Test
    public void rejectedReprobeReturnsUnknownRatherThanAnIndefinitelyStaleResult() {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger submissions = new AtomicInteger();
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache(task -> {
            if (submissions.getAndIncrement() != 0) throw new java.util.concurrent.RejectedExecutionException();
            work.add(task);
        });
        cache.get("first", () -> new RecordingCodecAvailability.Result(true, false));
        work.remove().run();
        assertSame(RecordingCodecAvailability.Result.UNKNOWN,
                cache.get("second", () -> { throw new AssertionError(); }));
        assertSame(RecordingCodecAvailability.Result.UNKNOWN,
                cache.get("second", () -> { throw new AssertionError(); }));
        assertEquals(2, submissions.get());
    }

    @Test
    public void cachesUnknownWithoutConvertingItToUnsupported() {
        Queue<Runnable> work = new ArrayDeque<>();
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache(work::add);
        RecordingCodecAvailability.Result unknown = cache.get("settings", () -> RecordingCodecAvailability.Result.UNKNOWN);
        assertNull(unknown.h264);
        assertNull(unknown.h265);
        work.remove().run();
        assertSame(unknown, cache.get("settings", () -> { throw new AssertionError(); }));
        assertEquals(0, work.size());
    }

    @Test
    public void candidateBitratesUseSameFpsScalingAndOverrideAsRecorder() {
        for (VideoCodec codec : VideoCodec.values()) {
            GpuPipelineConfig config = new GpuPipelineConfig();
            config.setVideoCodec(codec);
            config.setRecordingQuality(RecordingQuality.HIGH);
            for (int override : new int[] {0, 3_000_000}) {
                config.setCustomBitrate(override);
                assertEquals(config.getEffectiveBitrate(25),
                        RecordingCodecAvailability.bitrate(codec, 25, RecordingQuality.HIGH, override));
                assertEquals(config.getEffectiveBitrateForQuality(RecordingQuality.ECONOMY, 15),
                        RecordingCodecAvailability.bitrate(codec, 15, RecordingQuality.ECONOMY, override));
            }
        }
    }
}
