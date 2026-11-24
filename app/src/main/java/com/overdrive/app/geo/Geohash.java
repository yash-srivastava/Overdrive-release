package com.overdrive.app.geo;

/**
 * Geohash encoder. Cache key precision lives in {@link #PRECISION_DEFAULT}.
 *
 * <p>Geohash7 ≈ 153 m × 153 m at the equator — appropriate for "this clip
 * happened in this district" rather than "this clip happened on this street."
 * Coarser hashes raise cache hit rate dramatically; finer ones produce
 * misleadingly precise place names that can disagree as the GPS jitters
 * across a hash-cell boundary while the car is parked.
 */
public final class Geohash {

    public static final int PRECISION_DEFAULT = 7;

    private static final char[] BASE32 = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'b', 'c', 'd', 'e', 'f', 'g',
            'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r',
            's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private Geohash() {}

    public static String encode(double lat, double lng) {
        return encode(lat, lng, PRECISION_DEFAULT);
    }

    public static String encode(double lat, double lng, int precision) {
        if (precision < 1) precision = 1;
        if (precision > 12) precision = 12;

        double latLow = -90.0,  latHigh = 90.0;
        double lngLow = -180.0, lngHigh = 180.0;
        boolean even = true;
        int bit = 0, ch = 0;
        StringBuilder sb = new StringBuilder(precision);

        while (sb.length() < precision) {
            double mid;
            if (even) {
                mid = (lngLow + lngHigh) / 2.0;
                if (lng >= mid) { ch = (ch << 1) | 1; lngLow = mid; }
                else            { ch = (ch << 1);     lngHigh = mid; }
            } else {
                mid = (latLow + latHigh) / 2.0;
                if (lat >= mid) { ch = (ch << 1) | 1; latLow = mid; }
                else            { ch = (ch << 1);     latHigh = mid; }
            }
            even = !even;
            if (++bit == 5) {
                sb.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    /**
     * Whether a precision-7 cell may touch the circle: its centre lies within
     * radius + cell circumradius (great-circle, so parents and the date line don't matter).
     */
    static boolean mayOverlapRadius(String hash, double lat, double lng, double radiusM) {
        if (hash == null || hash.length() != PRECISION_DEFAULT
                || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0
                || !Double.isFinite(lng) || lng < -180.0 || lng > 180.0
                || !Double.isFinite(radiusM) || radiusM < 0.0) {
            return false;
        }

        double south = -90.0, north = 90.0;
        double west = -180.0, east = 180.0;
        boolean longitudeBit = true;
        for (int i = 0; i < hash.length(); i++) {
            int value = -1;
            for (int j = 0; j < BASE32.length; j++) {
                if (BASE32[j] == hash.charAt(i)) {
                    value = j;
                    break;
                }
            }
            if (value < 0) return false; // Ignore malformed persisted cache keys.
            for (int bit = 4; bit >= 0; bit--) {
                boolean upperHalf = (value & (1 << bit)) != 0;
                if (longitudeBit) {
                    double mid = (west + east) / 2.0;
                    if (upperHalf) west = mid;
                    else east = mid;
                } else {
                    double mid = (south + north) / 2.0;
                    if (upperHalf) south = mid;
                    else north = mid;
                }
                longitudeBit = !longitudeBit;
            }
        }

        double centerLat = (south + north) / 2.0;
        double centerLng = (west + east) / 2.0;
        double cellRadiusM = Math.max(
                Math.max(distanceMeters(centerLat, centerLng, south, west),
                         distanceMeters(centerLat, centerLng, south, east)),
                Math.max(distanceMeters(centerLat, centerLng, north, west),
                         distanceMeters(centerLat, centerLng, north, east)));
        return distanceMeters(lat, lng, centerLat, centerLng) <= radiusM + cellRadiusM;
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double sinLat = Math.sin(dLat / 2.0);
        double sinLng = Math.sin(dLng / 2.0);
        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * sinLng * sinLng;
        return 2.0 * 6_371_000.0 * Math.asin(Math.sqrt(Math.min(1.0, Math.max(0.0, a))));
    }
}
