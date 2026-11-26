package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class TimelineJournalIntervalsTest {
    private static TimelineJournal.Entry row(long time, String kind, String state, String reason) {
        return new TimelineJournal.Entry(time, kind, state, reason, null);
    }

    private static List<String> describe(List<TimelineJournal.Interval> intervals) {
        List<String> result = new ArrayList<>();
        for (TimelineJournal.Interval interval : intervals) result.add(interval.toString());
        return result;
    }

    @Test
    public void earliestPowerReasonWinsIncludingClosedAndZeroLengthIntervals() {
        List<TimelineJournal.Entry> rows = Arrays.asList(
                row(30_000, "surveillance", "off", "user_off"),
                row(15_000, "power", "off", "first"),
                row(15_000, "power", "on", null),
                row(29_000, "power", "off", "nearer"),
                row(40_000, "surveillance", "on", null));
        assertEquals(Arrays.asList("power:off/nearer[29000..35000)",
                        "surveillance:off/first[30000..40000)"),
                describe(TimelineJournal.intervals(rows, Arrays.asList(35_000L, 29_000L, 20_000L))));
    }

    @Test
    public void tripStartsAreStrictAndCanCloseOpenTail() {
        List<TimelineJournal.Entry> rows = Arrays.asList(
                row(100, "power", "off", "one"),
                row(150, "power", "on", null),
                row(200, "power", "off", "two"),
                row(300, "power", "paused", "three"));
        assertEquals(Arrays.asList("power:off/one[100..120)", "power:off/two[200..300)",
                        "power:paused/three[300..400)"),
                describe(TimelineJournal.intervals(rows, Arrays.asList(400L, 300L, 120L, 100L, 100L))));
        assertEquals(Arrays.asList("power:off/one[100..150)", "power:off/two[200..300)",
                        "power:paused/three[300..null)"),
                describe(TimelineJournal.intervals(rows, Collections.singletonList(null))));
    }

    @Test
    public void futurePowerStartMatchesInclusiveWindowButNotBeyondIt() {
        List<TimelineJournal.Entry> rows = Arrays.asList(
                row(10_000, "surveillance", "off", "user_off"),
                row(11_000, "surveillance", "on", null),
                row(12_000, "surveillance", "off", "user_off"),
                row(13_000, "surveillance", "on", null),
                row(14_000, "surveillance", "off", "manual"),
                row(27_000, "power", "off", "acc_off"));
        assertEquals(Arrays.asList("surveillance:off/user_off[10000..11000)",
                        "surveillance:off/acc_off[12000..13000)",
                        "surveillance:off/manual[14000..null)", "power:off/acc_off[27000..null)"),
                describe(TimelineJournal.intervals(rows, null)));
    }

    @Test
    public void fragmentedHistoryKeepsAllIntervalsAndReasons() {
        List<TimelineJournal.Entry> rows = new ArrayList<>();
        List<Long> trips = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            long start = 100_000L + i * 100_000L;
            rows.add(row(start, "power", "off", "acc_off"));
            rows.add(row(start + 10, "surveillance", "off", "user_off"));
            rows.add(row(start + 40, "power", "on", null));
            rows.add(row(start + 50, "surveillance", "on", null));
            trips.add(start + 20);
            expected.add("power:off/acc_off[" + start + ".." + (start + 20) + ")");
            expected.add("surveillance:off/acc_off[" + (start + 10) + ".." + (start + 50) + ")");
        }
        Collections.shuffle(rows, new Random(35));
        Collections.shuffle(trips, new Random(36));
        assertEquals(expected, describe(TimelineJournal.intervals(rows, trips)));
    }

    @Test
    public void preservesLegacySignedOverflowMatchingForInvalidWallTimes() {
        List<TimelineJournal.Entry> rows = Arrays.asList(
                row(Long.MIN_VALUE, "power", "off", "old"),
                row(0, "surveillance", "off", "user_off"));
        assertEquals(Arrays.asList("power:off/old[" + Long.MIN_VALUE + "..null)",
                        "surveillance:off/old[0..null)"),
                describe(TimelineJournal.intervals(rows, null)));
    }
}
