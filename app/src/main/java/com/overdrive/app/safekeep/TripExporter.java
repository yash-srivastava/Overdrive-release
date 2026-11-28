package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.trips.TelemetrySample;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/** GPX 1.1 / KML / CSV renderings of finished trips (on-demand; no background cost). */
public final class TripExporter {

    private static final String OD_NS = "urn:overdrive:trip:v1";
    private static final int MAX_POINTS = 200_000;

    private TripExporter() {}

    public static String gpx(TripRecord t, File telemetry) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"OverDrive\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
                .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\"")
                .append(" xmlns:od=\"").append(OD_NS).append("\">\n");
        // Formatters are export-local: no shared mutable state between requests.
        Formatter numbers = new Formatter(sb, Locale.US);
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date date = new Date(t.startTime);
        String name = title(t);
        sb.append(" <metadata><name>").append(esc(name)).append("</name><time>").append(utc.format(date))
                .append("</time><extensions>").append(odTrip(t)).append("</extensions></metadata>\n");
        sb.append(" <trk><name>").append(esc(name)).append("</name><trkseg>\n");
        forEachPoint(telemetry, (ts, lat, lon, alt, speedKmh) -> {
            numbers.format("  <trkpt lat=\"%.6f\" lon=\"%.6f\">", lat, lon);
            if (!Double.isNaN(alt)) numbers.format("<ele>%.1f</ele>", alt);
            date.setTime(ts);
            sb.append("<time>").append(utc.format(date)).append("</time>");
            if (speedKmh >= 0) {
                numbers.format("<extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>%.2f"
                        + "</gpxtpx:speed></gpxtpx:TrackPointExtension></extensions>", speedKmh / 3.6);
            }
            sb.append("</trkpt>\n");
        });
        sb.append(" </trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    public static String kml(TripRecord t, File telemetry) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        String name = esc(title(t));
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(name).append("</name>\n")
                .append("<Style id=\"route\"><LineStyle><color>ff2f80ed</color><width>4</width></LineStyle></Style>\n")
                .append("<Placemark><name>").append(name).append("</name><description>")
                .append(esc(String.format(Locale.US, "%.1f km · %d min", t.distanceKm, t.durationSeconds / 60)))
                .append("</description><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>");
        int start = sb.length();
        Formatter numbers = new Formatter(sb, Locale.US);
        // Append directly to the final document, not a second full-size coordinate buffer.
        forEachPoint(telemetry, (ts, lat, lon, alt, speed) -> {
            numbers.format("%.6f,%.6f,", lon, lat);
            if (Double.isNaN(alt)) sb.append('0');
            else numbers.format("%.1f", alt);
            sb.append(' ');
        });
        if (sb.length() > start) sb.setLength(sb.length() - 1); // trailing coordinate separator
        return sb.append("</coordinates></LineString></Placemark>\n</Document></kml>\n").toString();
    }

    public static String csvHeader() {
        return "date,start,end,duration_min,distance_km,avg_speed_kmh,max_speed_kmh,soc_start,soc_end,"
                + "kwh_used,energy_kwh_per_100km,cost,currency,odometer_start_km,odometer_end_km,"
                + "start_lat,start_lon,end_lat,end_lon\n";
    }

    public static String csvRow(TripRecord t) {
        double kwh = t.getResolvedEnergyKwh();
        return fmt("yyyy-MM-dd", t.startTime) + "," + fmt("HH:mm", t.startTime) + "," + fmt("HH:mm", t.endTime)
                + "," + (t.durationSeconds / 60) + "," + num(t.distanceKm, 2) + "," + num(t.avgSpeedKmh, 1)
                + "," + t.maxSpeedKmh + "," + num(t.socStart, 1) + "," + num(t.socEnd, 1) + "," + num(kwh, 2)
                + "," + num(t.energyPerKm * 100, 2) + "," + num(t.tripCost, 2) + "," + csv(t.currency)
                + "," + num(t.odometerStartKm, 1) + "," + num(t.odometerEndKm, 1)
                + "," + coord(t.startLat) + "," + coord(t.startLon) + "," + coord(t.endLat) + "," + coord(t.endLon) + "\n";
    }

    public static String csv(List<TripRecord> trips) {
        StringBuilder sb = new StringBuilder(csvHeader());
        for (TripRecord t : trips) sb.append(csvRow(t));
        return sb.toString();
    }

    public static String title(TripRecord t) {
        return fmt("yyyy-MM-dd HH:mm", t.startTime) + String.format(Locale.US, " · %.1f km", t.distanceKm);
    }

    private static String odTrip(TripRecord t) {
        return "<od:trip distanceKm=\"" + num(t.distanceKm, 3) + "\" durationS=\"" + t.durationSeconds
                + "\" socStart=\"" + num(t.socStart, 1) + "\" socEnd=\"" + num(t.socEnd, 1)
                + "\" kwhStart=\"" + num(t.kwhStart, 2) + "\" kwhEnd=\"" + num(t.kwhEnd, 2)
                + "\" cost=\"" + num(t.tripCost, 2) + "\" currency=\"" + esc(t.currency == null ? "" : t.currency)
                + "\"/>";
    }

    interface PointSink {
        void point(long ts, double lat, double lon, double alt, int speedKmh);
    }

    static void forEachPoint(File telemetry, PointSink sink) {
        if (telemetry == null || !RemovableStorageFs.isFile(telemetry)) return;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(RemovableStorageFs.openInput(telemetry)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && n < MAX_POINTS) {
                if (line.isEmpty() || line.charAt(0) != '{') continue;
                JSONObject j;
                try {
                    j = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                double lat = j.optDouble("la", Double.NaN), lon = j.optDouble("lo", Double.NaN);
                if (!TelemetrySample.hasGpsFix(lat, lon)) continue;
                double alt = j.has("al") ? j.optDouble("al", Double.NaN) : Double.NaN;
                // Zero is the recorder's unknown-altitude sentinel; non-finite values are also unknown.
                if (alt == 0.0 || !Double.isFinite(alt)) alt = Double.NaN;
                sink.point(j.optLong("t", 0), lat, lon, alt, j.has("s") ? j.optInt("s", -1) : -1);
                n++;
            }
        } catch (Exception ignored) {
            // A truncated .gz still yields the points read so far.
        }
    }

    private static String fmt(String p, long ts) {
        return new SimpleDateFormat(p, Locale.US).format(new Date(ts));
    }

    private static String coord(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static String num(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format(Locale.US, "%." + dp + "f", v);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\r") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
