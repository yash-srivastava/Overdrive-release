package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RecordingCodecAvailabilityTest {
    @Test
    public void reusesSnapshotUntilSettingsChange() {
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache();
        AtomicInteger probes = new AtomicInteger();
        RecordingCodecAvailability.Result first = cache.get("2560x1920:15:15:STANDARD:STANDARD", () -> {
            probes.incrementAndGet();
            return new RecordingCodecAvailability.Result(true, false);
        });
        assertSame(first, cache.get("2560x1920:15:15:STANDARD:STANDARD", () -> {
            probes.incrementAndGet();
            return RecordingCodecAvailability.Result.UNKNOWN;
        }));
        RecordingCodecAvailability.Result changed = cache.get("2560x1440:15:15:STANDARD:STANDARD", () -> {
            probes.incrementAndGet();
            return new RecordingCodecAvailability.Result(true, true);
        });
        assertEquals(2, probes.get());
        assertEquals(Boolean.TRUE, changed.h265);
    }

    @Test
    public void cachesUnknownWithoutConvertingItToUnsupported() {
        RecordingCodecAvailability.Cache cache = new RecordingCodecAvailability.Cache();
        RecordingCodecAvailability.Result unknown = cache.get("settings", () -> RecordingCodecAvailability.Result.UNKNOWN);
        assertNull(unknown.h264);
        assertNull(unknown.h265);
        assertSame(unknown, cache.get("settings", () -> new RecordingCodecAvailability.Result(false, false)));
    }
}
