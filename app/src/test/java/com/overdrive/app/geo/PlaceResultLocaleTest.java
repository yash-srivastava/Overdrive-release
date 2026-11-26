package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Locale;

import org.json.JSONObject;
import org.junit.Test;

/** Persisted place source identifiers must not depend on the device language. */
public class PlaceResultLocaleTest {

    @Test
    public void writesCanonicalSourceIdentifiersInTurkish() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("android-geocoder", place(PlaceResult.Source.ANDROID_GEOCODER).toJson().getString("source"));
            assertEquals("nominatim", place(PlaceResult.Source.NOMINATIM).toJson().getString("source"));
            assertEquals("safezone", place(PlaceResult.Source.SAFEZONE).toJson().getString("source"));
            assertEquals("cache", place(PlaceResult.Source.CACHE).toJson().getString("source"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void readsCanonicalSourcesAfterChangingDeviceLanguage() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            JSONObject android = place(PlaceResult.Source.ANDROID_GEOCODER).toJson();
            JSONObject nominatim = place(PlaceResult.Source.NOMINATIM).toJson();
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(PlaceResult.Source.ANDROID_GEOCODER, PlaceResult.fromJson(android).source);
            assertEquals(PlaceResult.Source.NOMINATIM, PlaceResult.fromJson(nominatim).source);
            assertEquals("Example district", PlaceResult.fromJson(android).district);
            assertEquals("en", PlaceResult.fromJson(android).locale);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void readsLegacyDotlessIdentifiersAndKeepsUnknownSourceFallback() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            JSONObject json = place(null).toJson();
            assertFalse(json.has("source"));
            assertEquals(PlaceResult.Source.CACHE, PlaceResult.fromJson(json).source);
            json.put("source", "andro\u0131d-geocoder");
            assertEquals(PlaceResult.Source.ANDROID_GEOCODER, PlaceResult.fromJson(json).source);
            json.put("source", "nom\u0131nat\u0131m");
            assertEquals(PlaceResult.Source.NOMINATIM, PlaceResult.fromJson(json).source);
            json.put("source", "future-source");
            assertEquals(PlaceResult.Source.CACHE, PlaceResult.fromJson(json).source);
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static PlaceResult place(PlaceResult.Source source) {
        return new PlaceResult("Example district, Example city", "Example district",
                "Example city", "", "", "en", source, 123L);
    }
}
