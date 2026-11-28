package com.overdrive.app.safekeep;

import com.overdrive.app.trips.TripRecord;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TripExporterTest {
    private static final String POINTS = "{\"t\":0,\"la\":-1.2345678,\"lo\":2.3456789,\"al\":10.25,\"s\":36}\n"
            + "not json\n{\"la\":0,\"lo\":0}\n"
            + "{\"t\":1000,\"la\":3,\"lo\":-0.0000001,\"al\":0}\n"
            + "{\"t\":-1000,\"la\":4,\"lo\":5,\"s\":0}\n";

    @Test
    public void gpxKeepsPointOrderPrecisionUtcAndOptionalFields() throws Exception {
        File file = journal(POINTS);
        try {
            String gpx = TripExporter.gpx(new TripRecord(), file);
            assertTrue(gpx.contains("<time>1970-01-01T00:00:00Z</time><extensions><od:trip"));
            int start = gpx.indexOf("<trkseg>") + "<trkseg>\n".length();
            assertEquals("  <trkpt lat=\"-1.234568\" lon=\"2.345679\"><ele>10.3</ele>"
                    + "<time>1970-01-01T00:00:00Z</time><extensions><gpxtpx:TrackPointExtension>"
                    + "<gpxtpx:speed>10.00</gpxtpx:speed></gpxtpx:TrackPointExtension></extensions></trkpt>\n"
                    + "  <trkpt lat=\"3.000000\" lon=\"-0.000000\"><time>1970-01-01T00:00:01Z</time></trkpt>\n"
                    + "  <trkpt lat=\"4.000000\" lon=\"5.000000\"><time>1969-12-31T23:59:59Z</time>"
                    + "<extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>0.00</gpxtpx:speed>"
                    + "</gpxtpx:TrackPointExtension></extensions></trkpt>\n ",
                    gpx.substring(start, gpx.indexOf("</trkseg>")));
        } finally {
            file.delete();
        }
    }

    @Test
    public void kmlKeepsCoordinateOrderAndSeparators() throws Exception {
        File file = journal(POINTS);
        try {
            String kml = TripExporter.kml(new TripRecord(), file);
            assertTrue(kml.contains("<coordinates>2.345679,-1.234568,10.3 -0.000000,3.000000,0 "
                    + "5.000000,4.000000,0</coordinates>"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void emptyAndSinglePointDocumentsHaveNoTrailingSeparator() throws Exception {
        TripRecord trip = new TripRecord();
        assertTrue(TripExporter.kml(trip, null).contains("<coordinates></coordinates>"));
        assertTrue(TripExporter.gpx(trip, null).contains("<trkseg>\n </trkseg>"));
        File file = journal("{\"la\":1,\"lo\":2}\n");
        try {
            assertTrue(TripExporter.kml(trip, file).contains("<coordinates>2.000000,1.000000,0</coordinates>"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void routeExportsSkipInvalidFixesButKeepValidZeroCoordinatesAndBoundaries() throws Exception {
        File file = journal("{\"la\":0,\"lo\":12}\n"
                + "{\"la\":91,\"lo\":12}\n"
                + "{\"la\":-91,\"lo\":12}\n"
                + "{\"la\":12,\"lo\":181}\n"
                + "{\"la\":12,\"lo\":-181}\n"
                + "{\"la\":\"Infinity\",\"lo\":12}\n"
                + "{\"la\":12,\"lo\":\"-Infinity\"}\n"
                + "{\"la\":\"NaN\",\"lo\":12}\n"
                + "{\"la\":12,\"lo\":\"invalid\"}\n"
                + "{\"la\":12,\"lo\":null}\n"
                + "{\"lo\":23}\n{\"la\":23}\n{\"la\":0,\"lo\":0}\n"
                + "{\"la\":12,\"lo\":0}\n"
                + "{\"la\":90,\"lo\":180}\n"
                + "{\"la\":-90,\"lo\":-180}\n");
        try {
            TripRecord trip = new TripRecord();
            String gpx = TripExporter.gpx(trip, file);
            int start = gpx.indexOf("<trkseg>") + "<trkseg>\n".length();
            assertEquals("  <trkpt lat=\"0.000000\" lon=\"12.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"12.000000\" lon=\"0.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"90.000000\" lon=\"180.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"-90.000000\" lon=\"-180.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n ",
                    gpx.substring(start, gpx.indexOf("</trkseg>")));
            String kml = TripExporter.kml(trip, file);
            assertTrue(kml.contains("<coordinates>12.000000,0.000000,0 0.000000,12.000000,0 "
                    + "180.000000,90.000000,0 -180.000000,-90.000000,0</coordinates>"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void routeExportsTreatNonFiniteAltitudeAsUnknownWithoutDroppingPoints() throws Exception {
        File file = journal("{\"la\":1,\"lo\":2,\"al\":\"Infinity\"}\n"
                + "{\"la\":3,\"lo\":4,\"al\":\"-Infinity\"}\n"
                + "{\"la\":5,\"lo\":6,\"al\":\"NaN\"}\n"
                + "{\"la\":7,\"lo\":8,\"al\":0}\n"
                + "{\"la\":9,\"lo\":10,\"al\":-12.5}\n"
                + "{\"la\":11,\"lo\":12,\"al\":42.5}\n"
                + "{\"la\":13,\"lo\":14}\n");
        try {
            TripRecord trip = new TripRecord();
            String gpx = TripExporter.gpx(trip, file);
            int start = gpx.indexOf("<trkseg>") + "<trkseg>\n".length();
            assertEquals("  <trkpt lat=\"1.000000\" lon=\"2.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"3.000000\" lon=\"4.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"5.000000\" lon=\"6.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"7.000000\" lon=\"8.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"9.000000\" lon=\"10.000000\"><ele>-12.5</ele><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"11.000000\" lon=\"12.000000\"><ele>42.5</ele><time>1970-01-01T00:00:00Z</time></trkpt>\n"
                    + "  <trkpt lat=\"13.000000\" lon=\"14.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>\n ",
                    gpx.substring(start, gpx.indexOf("</trkseg>")));
            String kml = TripExporter.kml(trip, file);
            assertTrue(kml.contains("<coordinates>2.000000,1.000000,0 4.000000,3.000000,0 "
                    + "6.000000,5.000000,0 8.000000,7.000000,0 10.000000,9.000000,-12.5 "
                    + "12.000000,11.000000,42.5 14.000000,13.000000,0</coordinates>"));
        } finally {
            file.delete();
        }
    }

    private static File journal(String lines) throws Exception {
        File file = File.createTempFile("trip-export", ".jsonl.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(file))) {
            out.write(lines.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
