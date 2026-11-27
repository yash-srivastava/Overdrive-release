package com.overdrive.app.monitor;

import android.os.SystemClock;

import com.overdrive.app.byd.CarSvcTelemetry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads on-change vehicle power and both READY indicators from the same car_service dump. */
public final class DiLink5IgnitionEvidence {
    private static final Pattern TARGET = Pattern.compile("lastEvent:Property:0x214050(0f|65|91)\\b");
    private static final Pattern EVENT = Pattern.compile(
            "[ \\t]*event count:[ \\t]*([0-9]+),[ \\t]*lastEvent:Property:0x214050(?:0f|65|91),"
            + "status:[ \\t]*0,timestamp:[ \\t]*([0-9]+),zone:0x0,"
            + "floatValues:[ \\t]*\\[\\],int32Values:[ \\t]*\\[([0-3])\\](?:,.*)?");
    private static final History HISTORY = new History();

    private DiLink5IgnitionEvidence() {}

    private static final class Event {
        final long timestamp;
        final int value;

        Event(long timestamp, int value) {
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

        private Sample(boolean present, Boolean accOn, String detail, String mode, Event[] events) {
            this.present = present;
            this.accOn = accOn;
            this.detail = detail;
            this.powerModeLine = mode;
            this.events = events;
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
        return HISTORY.admit(events);
    }

    private static Sample unknown(boolean present, String detail, String mode) {
        return new Sample(present, null, detail, mode, null);
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
        int found = 0;
        while (targets.find()) {
            int slot;
            switch (targets.group(1)) {
                case "0f": slot = 0; break; // POWER_LEVEL
                case "65": slot = 1; break; // READY indicator
                default:   slot = 2; break; // EV READY indicator
            }
            if (events[slot] != null) return unknown(true, "duplicate ignition event", mode);

            int start = dump.lastIndexOf('\n', targets.start()) + 1;
            int end = dump.indexOf('\n', targets.end());
            if (end < 0) end = dump.length();
            if (end > start && dump.charAt(end - 1) == '\r') end--;
            Matcher row = EVENT.matcher(dump).region(start, end);
            if (!row.matches()) return unknown(true, "invalid ignition event", mode);
            try {
                long count = Long.parseLong(row.group(1));
                long timestamp = Long.parseLong(row.group(2));
                if (count == 0 || timestamp == 0 || timestamp > nowNanos) {
                    return unknown(true, "invalid ignition counter or timestamp", mode);
                }
                events[slot] = new Event(timestamp, Integer.parseInt(row.group(3)));
            } catch (NumberFormatException invalid) {
                return unknown(true, "invalid ignition counter or timestamp", mode);
            }
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
        return new Sample(true, accOn, detail, mode, events);
    }

    static final class History {
        private Event[] previous;

        Sample admit(Sample sample) {
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
            return sample;
        }
    }
}
