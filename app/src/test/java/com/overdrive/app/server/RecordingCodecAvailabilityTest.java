package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
