package com.overdrive.app.byd;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

/**
 * The newest DiLink 3/4 SDK reading of a few dynamics signals, from any live read or HAL push. A poller
 * that accepts a value up to maxAgeMs old reuses it instead of making its own binder call; a miss means
 * "read directly", exactly as before. Nothing here blocks or reads the car, so a stuck getter only ever
 * holds the caller that made it. Each value carries the unit factor it was converted with, and a value
 * converted under another factor is never served.
 */
final class VehicleSignalHub {
    static final int SPEED_KMH = 0;
    static final int BRAKE_PERCENT = 1;
    static final int ENGINE_POWER_KW = 2;
    private static final int COUNT = 3;

    private static final class Sample {
        final double value;
        final double unit;
        final long observedAtMs;

        Sample(double value, double unit, long observedAtMs) {
            this.value = value;
            this.unit = unit;
            this.observedAtMs = observedAtMs;
        }
    }

    private final AtomicReferenceArray<Sample> slots = new AtomicReferenceArray<>(COUNT);
    private final LongSupplier elapsedMs;

    VehicleSignalHub(LongSupplier elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    long now() {
        return elapsedMs.getAsLong();
    }

    /** Records a reading observed at [observedAtMs] (taken before the read began); an older one never replaces a newer. */
    void offer(int signal, double value, double unit, long observedAtMs) {
        if (!Double.isFinite(value)) return;
        Sample next = new Sample(value, unit, observedAtMs);
        while (true) {
            Sample current = slots.get(signal);
            if (current != null && current.observedAtMs > observedAtMs) return;
            if (slots.compareAndSet(signal, current, next)) return;
        }
    }

    /** The shared reading, or NaN when there is none in this unit at most maxAgeMs old. */
    double fresh(int signal, double unit, long maxAgeMs) {
        Sample s = slots.get(signal);
        if (s == null || Double.compare(s.unit, unit) != 0) return Double.NaN;
        long age = now() - s.observedAtMs;
        return age >= 0 && age <= maxAgeMs ? s.value : Double.NaN;
    }
}
