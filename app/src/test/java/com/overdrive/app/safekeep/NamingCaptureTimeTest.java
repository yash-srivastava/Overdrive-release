package com.overdrive.app.safekeep;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NamingCaptureTimeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private TimeZone originalZone;

    @Before public void setUp() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"));
    }

    @After public void tearDown() {
        TimeZone.setDefault(originalZone);
    }

    @Test public void validLeapDayUsesLocalCaptureTimeRatherThanMtime() throws Exception {
        File file = recording("cam1_20240229_123456.mp4");
        assertEquals(Instant.parse("2024-02-29T11:34:56Z").toEpochMilli(), Naming.captureTime(file));
    }

    @Test public void impossibleDatesAndClockFieldsFallBackToMtime() throws Exception {
        for (String timestamp : new String[] {
                "20230229_120000", "20240230_120000", "20240431_120000",
                "20241301_120000", "20240101_250000", "20240101_126000",
                "20240101_120060"
        }) {
            File file = recording("cam1_" + timestamp + ".mp4");
            assertEquals(timestamp, file.lastModified(), Naming.captureTime(file));
        }
    }

    @Test public void missingTimestampStillFallsBackToMtime() throws Exception {
        File file = recording("cam1_unknown.mp4");
        assertEquals(file.lastModified(), Naming.captureTime(file));
    }

    @Test public void localDaylightSavingGapRetainsExistingAdjustment() throws Exception {
        File file = recording("cam1_20240331_023000.mp4");
        // 02:30 is skipped in Zurich; lenient local parsing advances it to 03:30 CEST.
        assertEquals(Instant.parse("2024-03-31T01:30:00Z").toEpochMilli(), Naming.captureTime(file));
    }

    private File recording(String name) throws Exception {
        File file = temp.newFile(name);
        assertTrue(file.setLastModified(Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()));
        return file;
    }
}
