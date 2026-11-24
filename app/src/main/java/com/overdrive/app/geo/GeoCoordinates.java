package com.overdrive.app.geo;

/** Shared validation for GPS fixes used by recording sidecars and place lookups. */
final class GeoCoordinates {

    private GeoCoordinates() {}

    /**
     * Reject missing/non-finite/out-of-range coordinates and the project's (0, 0)
     * no-fix sentinel. The geographic origin is deliberately not used as a fix.
     */
    static boolean isUsable(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0
                && (lat != 0.0 || lng != 0.0);
    }
}
