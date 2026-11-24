package com.overdrive.app.geo;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Resolved place name + administrative components for a (lat, lng).
 *
 * <p>Immutable. Stored as-is in the v3 sidecar's {@code geo.place} block,
 * cached in {@link GeoCache} keyed by {@code (geohash7, locale)}.
 *
 * <p>The component fields ({@link #district}, {@link #city}, {@link #country})
 * are kept alongside {@link #displayName} so the UI can re-compose for
 * different contexts (chip / push / share-out) without re-parsing the prose
 * or hitting the network again.
 */
public final class PlaceResult {

    /** Where the result came from. Used for cache audit + UI badges. */
    public enum Source {
        SAFEZONE,         // SafeLocation overrode the geocode
        CACHE,            // Disk cache hit
        ANDROID_GEOCODER, // OS Geocoder (offline, framework-provided)
        NOMINATIM         // Online OpenStreetMap reverse-geocode
    }

    /** Long-form address ("Bandar Tun Razak, Cheras, Kuala Lumpur, Malaysia"). */
    public final String displayName;

    /** Suburb / district / neighborhood. May be empty. */
    public final String district;

    /** City / town. May be empty. */
    public final String city;

    /** Country in the resolved locale ("Malaysia"). May be empty. */
    public final String country;

    /** ISO 3166-1 alpha-2 ("MY"). May be empty. */
    public final String countryCode;

    /** BCP-47 locale tag the lookup was issued in ("en", "th", "zh-CN"). */
    public final String locale;

    /** Where the value originated. */
    public final Source source;

    /** Wall-clock ms of resolution. Used for TTL eviction. */
    public final long resolvedAtMs;

    public PlaceResult(String displayName, String district, String city,
                       String country, String countryCode, String locale,
                       Source source, long resolvedAtMs) {
        this.displayName = nz(displayName);
        this.district    = nz(district);
        this.city        = nz(city);
        this.country     = nz(country);
        this.countryCode = nz(countryCode);
        this.locale      = nz(locale);
        this.source      = source;
        this.resolvedAtMs = resolvedAtMs;
    }

    /** Empty-string instead of null so JSON serialization stays predictable. */
    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Best short label for compact UI surfaces (recording row chip, push body).
     * Falls back through district → city → displayName so we always emit
     * something useful.
     */
    public String shortLabel() {
        if (!district.isEmpty()) return district;
        if (!city.isEmpty())     return city;
        return displayName;
    }

    /**
     * Medium label (push body line). District + city when both are present.
     */
    public String mediumLabel() {
        if (!district.isEmpty() && !city.isEmpty() && !district.equals(city)) {
            return district + ", " + city;
        }
        return shortLabel();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (!displayName.isEmpty()) o.put("displayName", displayName);
            if (!district.isEmpty())    o.put("district", district);
            if (!city.isEmpty())        o.put("city", city);
            if (!country.isEmpty())     o.put("country", country);
            if (!countryCode.isEmpty()) o.put("countryCode", countryCode);
            if (!locale.isEmpty())      o.put("locale", locale);
            if (source != null)         o.put("source", source.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            if (resolvedAtMs > 0)       o.put("resolvedAtMs", resolvedAtMs);
        } catch (Throwable ignored) {}
        return o;
    }

    /** Parse from JSON. Returns null when no useful fields are present. */
    public static PlaceResult fromJson(JSONObject o) {
        if (o == null) return null;
        String d = o.optString("displayName", "");
        String dist = o.optString("district", "");
        String city = o.optString("city", "");
        String country = o.optString("country", "");
        String cc = o.optString("countryCode", "");
        String loc = o.optString("locale", "");
        if (d.isEmpty() && dist.isEmpty() && city.isEmpty() && country.isEmpty()) {
            return null;
        }
        Source src = Source.CACHE;
        try {
            String s = o.optString("source", "cache").toUpperCase(Locale.ROOT).replace('-', '_');
            src = Source.valueOf(s);
        } catch (Throwable ignored) {}
        long resolvedAt = o.optLong("resolvedAtMs", 0L);
        return new PlaceResult(d, dist, city, country, cc, loc, src, resolvedAt);
    }
}
