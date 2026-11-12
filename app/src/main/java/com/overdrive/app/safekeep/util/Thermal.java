package com.overdrive.app.safekeep.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Reads one cached SoC thermal zone in the normal case. If no zone was readable during
 * startup, or the selected zone stops responding, retry discovery at a bounded interval.
 */
public final class Thermal {

    private static final Sampler SAMPLER = new Sampler(new File("/sys/class/thermal"),
            android.os.SystemClock::elapsedRealtime);

    private Thermal() {}

    /** Degrees Celsius, or NaN while no usable zone is available. */
    public static float readCelsius() {
        return SAMPLER.readCelsius();
    }

    /** Package-private test seam: no Android clock or /sys access is needed in JVM tests. */
    static final class Sampler {
        static final long RESCAN_INTERVAL_MS = 60_000L;

        private final File zonesDirectory;
        private final LongSupplier elapsedMs;
        private String zoneTempPath;
        private long lastScanAt;
        private boolean scanned;

        Sampler(File zonesDirectory, LongSupplier elapsedMs) {
            this.zonesDirectory = zonesDirectory;
            this.elapsedMs = elapsedMs;
        }

        /** Serializes rare discovery and normal reads without spawning threads or polling. */
        synchronized float readCelsius() {
            long now = elapsedMs.getAsLong();
            if (!scanned) scan(now);
            float temp = zoneTempPath == null ? Float.NaN : readTemp(zoneTempPath);
            if (Float.isNaN(temp) && (now < lastScanAt
                    || now - lastScanAt >= RESCAN_INTERVAL_MS)) {
                scan(now);
                temp = zoneTempPath == null ? Float.NaN : readTemp(zoneTempPath);
            }
            return temp;
        }

        private void scan(long now) {
            String best = null;
            float bestTemp = -1f;
            String anyBest = null;
            float anyTemp = -1f;
            File[] zones = zonesDirectory.listFiles();
            if (zones != null) {
                for (File z : zones) {
                    if (!z.getName().startsWith("thermal_zone")) continue;
                    String tempPath = new File(z, "temp").getAbsolutePath();
                    float t = readTemp(tempPath);
                    if (Float.isNaN(t)) continue;
                    String type = readLine(new File(z, "type").getAbsolutePath());
                    String ty = type == null ? "" : type.toLowerCase(Locale.ROOT);
                    boolean socLike = ty.contains("cpu") || ty.contains("soc") || ty.contains("apc")
                            || ty.contains("cluster") || ty.contains("tsens") || ty.contains("cpuss");
                    if (socLike && t > bestTemp) {
                        bestTemp = t;
                        best = tempPath;
                    }
                    boolean excluded = ty.contains("battery") || ty.contains("pa_") || ty.contains("xo");
                    if (!excluded && t > anyTemp) {
                        anyTemp = t;
                        anyBest = tempPath;
                    }
                }
            }
            zoneTempPath = best != null ? best : anyBest;
            lastScanAt = now;
            scanned = true;
        }
    }

    private static float readTemp(String path) {
        String s = readLine(path);
        if (s == null) return Float.NaN;
        try {
            float v = Float.parseFloat(s.trim());
            if (v > 1000f) v = v / 1000f;
            return (v > 0f && v < 150f) ? v : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }
}
