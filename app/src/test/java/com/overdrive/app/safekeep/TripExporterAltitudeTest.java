package com.overdrive.app.safekeep;

import com.overdrive.app.trips.TripRecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TripExporterAltitudeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File journal() throws Exception {
        File file = temp.newFile("altitude.jsonl.gz");
        String lines = "{\"t\":1000,\"la\":1,\"lo\":2,\"al\":\"Infinity\"}\n"
                + "{\"t\":2000,\"la\":2,\"lo\":3,\"al\":\"-Infinity\"}\n"
                + "{\"t\":3000,\"la\":3,\"lo\":4,\"al\":\"NaN\"}\n"
                + "{\"t\":4000,\"la\":4,\"lo\":5,\"al\":0}\n"
                + "{\"t\":5000,\"la\":5,\"lo\":6}\n"
                + "{\"t\":6000,\"la\":6,\"lo\":7,\"al\":-12.5}\n"
                + "{\"t\":7000,\"la\":7,\"lo\":8,\"al\":125.5}\n";
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(file))) {
            out.write(lines.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    public void gpxOmitsNonFiniteAltitudeWithoutDroppingTrackPoints() throws Exception {
        String gpx = TripExporter.gpx(new TripRecord(), journal());
        assertFalse(gpx.contains("Infinity"));
        int start = gpx.indexOf("<trkseg>") + "<trkseg>\n".length();
        assertEquals("  <trkpt lat=\"1.000000\" lon=\"2.000000\"><time>1970-01-01T00:00:01Z</time></trkpt>\n"
                + "  <trkpt lat=\"2.000000\" lon=\"3.000000\"><time>1970-01-01T00:00:02Z</time></trkpt>\n"
                + "  <trkpt lat=\"3.000000\" lon=\"4.000000\"><time>1970-01-01T00:00:03Z</time></trkpt>\n"
                + "  <trkpt lat=\"4.000000\" lon=\"5.000000\"><time>1970-01-01T00:00:04Z</time></trkpt>\n"
                + "  <trkpt lat=\"5.000000\" lon=\"6.000000\"><time>1970-01-01T00:00:05Z</time></trkpt>\n"
                + "  <trkpt lat=\"6.000000\" lon=\"7.000000\"><ele>-12.5</ele><time>1970-01-01T00:00:06Z</time></trkpt>\n"
                + "  <trkpt lat=\"7.000000\" lon=\"8.000000\"><ele>125.5</ele><time>1970-01-01T00:00:07Z</time></trkpt>\n ",
                gpx.substring(start, gpx.indexOf("</trkseg>")));
    }

    @Test
    public void kmlUsesExistingZeroFallbackForNonFiniteAltitude() throws Exception {
        String kml = TripExporter.kml(new TripRecord(), journal());
        assertFalse(kml.contains("Infinity"));
        int start = kml.indexOf("<coordinates>") + "<coordinates>".length();
        assertEquals("2.000000,1.000000,0 3.000000,2.000000,0 4.000000,3.000000,0 "
                + "5.000000,4.000000,0 6.000000,5.000000,0 "
                + "7.000000,6.000000,-12.5 8.000000,7.000000,125.5",
                kml.substring(start, kml.indexOf("</coordinates>")));
    }
}
