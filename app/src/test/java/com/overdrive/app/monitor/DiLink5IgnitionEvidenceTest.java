package com.overdrive.app.monitor;

import org.junit.Test;

import static org.junit.Assert.*;

public class DiLink5IgnitionEvidenceTest {
    private final DiLink5IgnitionEvidence.History history = new DiLink5IgnitionEvidence.History();

    private DiLink5IgnitionEvidence.Sample observe(String dump, long now) {
        return history.admit(DiLink5IgnitionEvidence.parse(dump, now, "10=PowerMode Display on"));
    }

    private static String row(String id, int count, long timestamp, int value) {
        return "event count:" + count + ", lastEvent:Property:0x214050" + id
                + ",status: 0,timestamp:" + timestamp + ",zone:0x0,floatValues: [],int32Values: ["
                + value + "],int64Values: []\n";
    }

    private static String tuple(int power, int ready, int evReady, long timestamp) {
        return row("0f", 4, timestamp, power) + row("65", 9, timestamp + 1, ready)
                + row("91", 2, timestamp + 2, evReady);
    }

    @Test public void parkedShutdownAndRestartThroughDelayedReadyEvents() {
        String before = row("65", 386, 567257831035584L, 1)
                + row("0f", 68, 564747022455552L, 3) + row("91", 56, 567258695102407L, 1);
        assertEquals(Boolean.TRUE, observe(before, 567418400000000L).accOnAfter(0));

        String offBits = row("65", 387, 567437951939161L, 0) + row("91", 57, 567439305685879L, 0);
        String off = row("0f", 69, 567437913932130L, 1) + offBits;
        assertEquals(Boolean.FALSE, observe(off, 567440000000000L).accOn);
        assertNull(observe(before, 567441000000000L).accOn);

        String powerOn = row("0f", 70, 567600908870401L, 3);
        assertNull(observe(powerOn + offBits, 567602000000000L).accOn);
        String restarted = powerOn + row("65", 388, 567603883483838L, 1)
                + row("91", 58, 567604074632275L, 1);
        DiLink5IgnitionEvidence.Sample confirmed = observe(restarted, 567605000000000L);
        assertEquals(Boolean.TRUE, confirmed.accOnAfter(567440000L));
        assertEquals(567604074632275L, confirmed.newestEventNanos);
    }

    @Test public void unchangingReadyCanConfirmSameDriveButCannotOverruleLaterOff() {
        String ready = tuple(3, 1, 1, 7_000_000L);
        assertEquals(Boolean.TRUE, observe(ready, 10_000_000L).accOn);
        DiLink5IgnitionEvidence.Sample reread = observe(ready, 100_000_000_000_000L);
        assertEquals(Boolean.TRUE, reread.accOnAfter(0));
        assertNull(reread.accOnAfter(8));
        assertNull(observe(row("0f", 6, 7_000_000L, 3) + row("65", 10, 20_000_000L, 1)
                + row("91", 3, 21_000_000L, 1), 22_000_000L).accOnAfter(8));
    }

    @Test public void onlyTwoCoherentTuplesCarryAState() {
        long tick = 0;
        for (int power = 0; power <= 3; power++) {
            for (int ready = 0; ready <= 3; ready++) {
                for (int evReady = 0; evReady <= 3; evReady++) {
                    tick += 10;
                    Boolean actual = observe(tuple(power, ready, evReady, tick), tick + 2).accOn;
                    if (power == 3 && ready == 1 && evReady == 1) assertEquals(Boolean.TRUE, actual);
                    else if (power == 1 && ready == 0 && evReady == 0) assertEquals(Boolean.FALSE, actual);
                    else assertNull(actual);
                }
            }
        }
    }

    @Test public void malformedOrDuplicateTargetVetoesTheWholeSample() {
        String good = tuple(3, 1, 1, 100L);
        String power = row("0f", 4, 100L, 3);
        String[] invalid = {
                good + power, good + power.replace("status: 0", "status: 2"),
                good.replace("status: 0", "status: 2"), good.replace("zone:0x0", "zone:0x1"),
                good.replace("int32Values: [3]", "int32Values: [3, 1]"),
                good.replace("timestamp:100", "timestamp:100x"),
                good.replace("timestamp:100", "timestamp:0"),
                good.replace("timestamp:100", "timestamp:999"),
                good.replace("event count:4", "event count:0"),
                good.replace("event count:4", "event count:999999999999999999999"),
                good.replace("event count:4, ", ""), good.replace(power, "")
        };
        for (String dump : invalid) {
            DiLink5IgnitionEvidence.Sample sample = observe(dump, 200);
            assertTrue(sample.present);
            assertNull(dump, sample.accOn);
        }
        assertFalse(observe("no ignition properties", 200).present);
    }

    @Test public void partialDumpFailureLosesModeAndDoesNotAdvanceHistory() {
        String[] failures = {"*** SERVICE 'car_service' DUMP TIMEOUT (3000ms) EXPIRED ***",
                "Error dumping service info: (Broken pipe) car_service",
                "Failed to read while dumping service car_service: Connection reset"};
        for (String failure : failures) {
            DiLink5IgnitionEvidence.Sample sample = observe(tuple(3, 1, 1, 80) + failure, 100);
            assertTrue(sample.present);
            assertNull(sample.accOn);
            assertNull(sample.powerModeLine);
        }
        assertEquals(Boolean.FALSE, observe(tuple(1, 0, 0, 50), 110).accOn);
    }

    @Test public void unchangedTimestampCannotAcquireAnotherValue() {
        assertEquals(Boolean.FALSE, observe(tuple(1, 0, 0, 20), 25).accOn);
        assertNull(observe(tuple(3, 1, 1, 20), 30).accOn);
        assertEquals(Boolean.TRUE, observe(tuple(3, 1, 1, 31), 40).accOn);
    }
}
