package com.overdrive.app.monitor;

import android.os.SystemClock;

import com.overdrive.app.byd.CarSvcTelemetry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads vehicle power, both READY indicators and driver brake input from the same dump. */
public final class DiLink5IgnitionEvidence {
    private static final Pattern TARGET = Pattern.compile("lastEvent:Property:0x2140(500f|5065|5091|3a05)\\b");
    private static final Pattern EVENT = Pattern.compile(
            "[ \\t]*event count:[ \\t]*([0-9]+),[ \\t]*lastEvent:Property:0x2140(?:500f|5065|5091|3a05),"
            + "status:[ \\t]*0,timestamp:[ \\t]*([0-9]+),zone:0x0,"
            + "floatValues:[ \\t]*\\[\\],int32Values:[ \\t]*\\[([0-3])\\](?:,.*)?");
    // Two five-second heartbeat polls plus the bounded car_service dump time.
    static final long BRAKE_WINDOW_MS = 15_000L;
    private static final History HISTORY = new History();

    private DiLink5IgnitionEvidence() {}

    private static final class Event {
        final long count;
        final long timestamp;
        final int value;

        Event(long count, long timestamp, int value) {
            this.count = count;
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    public static final class Sample {
        public final boolean present;
        public final Boolean accOn;
        public final long oldestEventNanos;
        public final long newestEventNanos;
        public final String detail;
        public final String powerModeLine;
        private final Event[] events;
        private final Event brake;
        private final long sampledAtNanos;
        private long brakeActionNanos;
        private long brakeBaselineElapsedMs;

        private Sample(boolean present, Boolean accOn, String detail, String mode, Event[] events,
                Event brake, long sampledAtNanos) {
            this.present = present;
            this.accOn = accOn;
            this.detail = detail;
            this.powerModeLine = mode;
            this.events = events;
            this.brake = brake;
            this.sampledAtNanos = sampledAtNanos;
            this.oldestEventNanos = events == null ? 0 : Math.min(events[0].timestamp,
                    Math.min(events[1].timestamp, events[2].timestamp));
            this.newestEventNanos = events == null ? 0 : Math.max(events[0].timestamp,
                    Math.max(events[1].timestamp, events[2].timestamp));
        }

        /** An earlier opposing transition requires every constituent to have changed since. */
        public Boolean accOnAfter(long opposingEdgeElapsedMs) {
            if (opposingEdgeElapsedMs > 0 && oldestEventNanos / 1_000_000L <= opposingEdgeElapsedMs) {
                return null;
            }
            return accOn;
        }

        long brakeEvidenceAfter(long parkedAtElapsedMs) {
            if (brakeActionNanos <= 0 || brakeActionNanos > sampledAtNanos
                    || sampledAtNanos - brakeActionNanos > BRAKE_WINDOW_MS * 1_000_000L) {
                return 0L;
            }
            long brakeAtElapsedMs = brakeActionNanos / 1_000_000L;
            // A cold judge has no OFF edge. Only reuse recent input from this power episode.
            if (parkedAtElapsedMs == 0 && brakeActionNanos < events[0].timestamp) return 0L;
            if (brakeAtElapsedMs <= parkedAtElapsedMs || brakeBaselineElapsedMs < parkedAtElapsedMs) {
                return 0L;
            }
            return brakeAtElapsedMs;
        }
    }

    /** Serializes this process's reads so an older concurrent sample cannot roll history back. */
    public static synchronized Sample read() {
        // The telemetry reader already bounds dump time, single-flights concurrent callers and
        // captures the complete dump. Event rows may be far beyond an ordinary shell output cap.
        String dump = CarSvcTelemetry.INSTANCE.dumpsysText();
        if (dump == null) return unknown(false, "dump unavailable", null);
        String mode = CarSvcTelemetry.INSTANCE.parsePowerModeLineFromText(dump);
        Sample events = parse(dump, SystemClock.elapsedRealtimeNanos(), mode);
        CarSvcTelemetry.CachedDump fresh = CarSvcTelemetry.cachedDump();
        if (fresh == null || fresh.getText() != dump) {
            return unknown(events.present, "dump expired", null);
        }
        return HISTORY.admit(events, fresh.getObservedAtElapsedMs());
    }

    private static Sample unknown(boolean present, String detail, String mode) {
        return new Sample(present, null, detail, mode, null, null, 0L);
    }

    static Sample parse(String dump, long nowNanos, String mode) {
        Matcher targets = TARGET.matcher(dump);
        // dumpsys may print the timeout or Binder failure after partial output, even with exit 0.
        if (dump.contains("*** SERVICE 'car_service' DUMP TIMEOUT")
                || dump.contains("Error dumping service info:")
                || dump.contains("while dumping service car_service")) {
            return unknown(targets.find(), "car_service dump failed", null);
        }

        Event[] events = new Event[3];
        Event brake = null;
        boolean brakeSeen = false;
        int found = 0;
        while (targets.find()) {
            String id = targets.group(1);
            Event event = parseEvent(dump, targets.start(), nowNanos);
            if (id.equals("3a05")) { // BRAKE_PEDAL_STATE
                brake = !brakeSeen && event != null && event.value <= 1 ? event : null;
                brakeSeen = true;
                continue;
            }
            int slot = id.equals("500f") ? 0 : id.equals("5065") ? 1 : 2;
            if (events[slot] != null) return unknown(true, "duplicate ignition event", mode);
            if (event == null) return unknown(true, "invalid ignition event", mode);
            events[slot] = event;
            found++;
        }
        if (found != events.length) return unknown(found != 0, "missing ignition event", mode);

        int power = events[0].value;
        int ready = events[1].value;
        int evReady = events[2].value;
        Boolean accOn = null;
        if (power == 3 && ready == 1 && evReady == 1) accOn = true;
        if (power == 1 && ready == 0 && evReady == 0) accOn = false;
        String detail = "power=" + power + ", ready=" + ready + ", evReady=" + evReady;
        return new Sample(true, accOn, detail, mode, events, brake, nowNanos);
    }

    private static Event parseEvent(String dump, int offset, long nowNanos) {
        int start = dump.lastIndexOf('\n', offset) + 1;
        int end = dump.indexOf('\n', offset);
        if (end < 0) end = dump.length();
        if (end > start && dump.charAt(end - 1) == '\r') end--;
        Matcher row = EVENT.matcher(dump).region(start, end);
        if (!row.matches()) return null;
        try {
            long count = Long.parseLong(row.group(1));
            long timestamp = Long.parseLong(row.group(2));
            if (count == 0 || timestamp == 0 || timestamp > nowNanos) return null;
            return new Event(count, timestamp, Integer.parseInt(row.group(3)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static final class History {
        private Event[] previous;
        private Event previousBrake;
        private long previousBrakeObservedAtMs;
        private long lastBrakeActionNanos;
        private long lastBrakeBaselineMs;

        Sample admit(Sample sample) {
            return admit(sample, sample.sampledAtNanos / 1_000_000L);
        }

        private Sample admit(Sample sample, long observedAtElapsedMs) {
            if (sample.events == null) return sample;
            if (previous != null) {
                for (int i = 0; i < previous.length; i++) {
                    Event current = sample.events[i];
                    Event prior = previous[i];
                    if (current.timestamp < prior.timestamp
                            || (current.timestamp == prior.timestamp && current.value != prior.value)) {
                        return unknown(true, "ignition event regressed", sample.powerModeLine);
                    }
                }
            }
            // Timestamps are in the boot clock domain. Counts may restart with car_service.
            previous = sample.events;
            observeBrake(sample.brake, observedAtElapsedMs);
            sample.brakeActionNanos = lastBrakeActionNanos;
            sample.brakeBaselineElapsedMs = lastBrakeBaselineMs;
            return sample;
        }

        private void observeBrake(Event brake, long observedAtElapsedMs) {
            if (brake == null) return;
            Event prior = previousBrake;
            if (prior != null && (brake.timestamp < prior.timestamp
                    || (brake.timestamp == prior.timestamp && brake.value != prior.value))) return;

            // A counter reset starts a new baseline. One released row never proves a press.
            if (prior != null && brake.count < prior.count) {
                lastBrakeActionNanos = 0L;
                lastBrakeBaselineMs = 0L;
                prior = null;
            }
            if (prior == null || (brake.count > prior.count && brake.timestamp > prior.timestamp)) {
                if (brake.value == 1) {
                    lastBrakeActionNanos = brake.timestamp;
                    lastBrakeBaselineMs = brake.timestamp / 1_000_000L;
                } else if (prior != null && prior.value == 0) {
                    // This on-change row can hide a press and release between polls.
                    long countAdvance = brake.count - prior.count;
                    if (countAdvance >= 2 && countAdvance % 2 == 0) {
                        lastBrakeActionNanos = brake.timestamp;
                        lastBrakeBaselineMs = previousBrakeObservedAtMs;
                    }
                }
            }
            previousBrake = brake;
            previousBrakeObservedAtMs = observedAtElapsedMs;
        }
    }
}
