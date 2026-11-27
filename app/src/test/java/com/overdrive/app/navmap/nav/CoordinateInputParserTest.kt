package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CoordinateInputParser]. No Android / network — it only
 * depends on the vendored [com.google.openlocationcode.OpenLocationCode] and
 * regex. The Plus-Code expectations use Google's OWN published test vectors
 * (test_data/decoding.csv and shortCodeTests.csv) so the offline decode/recover
 * is verified against the reference implementation, not our own re-derivation.
 */
class CoordinateInputParserTest {

    private val eps = 1e-5

    // ── Bare coordinates ──────────────────────────────────────────────────────

    @Test
    fun parsesCommaSeparatedCoords() {
        val r = CoordinateInputParser.parse("27.175063, 78.042188")
        assertNotNull(r)
        assertEquals(27.175063, r!!.lat, eps)
        assertEquals(78.042188, r.lng, eps)
    }

    @Test
    fun parsesSpaceSeparatedCoords() {
        val r = CoordinateInputParser.parse("27.175063 78.042188")
        assertNotNull(r)
        assertEquals(27.175063, r!!.lat, eps)
        assertEquals(78.042188, r.lng, eps)
    }

    @Test
    fun parsesNegativeCoords() {
        val r = CoordinateInputParser.parse("-41.273, 174.786")
        assertNotNull(r)
        assertEquals(-41.273, r!!.lat, eps)
        assertEquals(174.786, r.lng, eps)
    }

    @Test
    fun parsesHemisphereLetters() {
        val r = CoordinateInputParser.parse("28.6139° N, 77.2090° W")
        assertNotNull(r)
        assertEquals(28.6139, r!!.lat, eps)
        assertEquals(-77.2090, r.lng, eps) // W → negative
    }

    @Test
    fun parsesSouthHemisphere() {
        val r = CoordinateInputParser.parse("33.8688 S, 151.2093 E")
        assertNotNull(r)
        assertEquals(-33.8688, r!!.lat, eps)
        assertEquals(151.2093, r.lng, eps)
    }

    @Test
    fun rejectsOutOfRangeCoords() {
        assertNull(CoordinateInputParser.parse("91.0, 10.0"))   // lat > 90
        assertNull(CoordinateInputParser.parse("10.0, 181.0"))  // lng > 180
    }

    @Test
    fun rejectsPlainPlaceName() {
        assertNull(CoordinateInputParser.parse("Eiffel Tower"))
        assertNull(CoordinateInputParser.parse("123 Main Street"))
    }

    @Test
    fun blankReturnsNull() {
        assertNull(CoordinateInputParser.parse(""))
        assertNull(CoordinateInputParser.parse("   "))
        assertNull(CoordinateInputParser.parse(null))
    }

    // ── Plus Codes (Google's published decoding vectors) ──────────────────────
    // Expected center = midpoint of the published [latLo,lngLo,latHi,lngHi] box.

    @Test
    fun fullPlusCode_8FVC2222() {
        // 8FVC2222+22,10,47.0,8.0,47.000125,8.000125
        val r = CoordinateInputParser.parse("8FVC2222+22")
        assertNotNull(r)
        assertEquals((47.0 + 47.000125) / 2, r!!.lat, eps)
        assertEquals((8.0 + 8.000125) / 2, r.lng, eps)
        assertEquals("8FVC2222+22", r.label) // label is the normalised code
    }

    @Test
    fun fullPlusCode_negative_4VCPPQGP() {
        // 4VCPPQGP+Q9,10,-41.273125,174.785875,-41.273,174.786
        val r = CoordinateInputParser.parse("4VCPPQGP+Q9")
        assertNotNull(r)
        assertEquals((-41.273125 + -41.273) / 2, r!!.lat, eps)
        assertEquals((174.785875 + 174.786) / 2, r.lng, eps)
    }

    @Test
    fun fullPlusCode_isCaseInsensitive() {
        val lower = CoordinateInputParser.parse("8fvc2222+22")
        val upper = CoordinateInputParser.parse("8FVC2222+22")
        assertNotNull(lower)
        assertEquals(upper!!.lat, lower!!.lat, eps)
        assertEquals(upper.lng, lower.lng, eps)
    }

    @Test
    fun paddedFullPlusCodesResolveWithoutReference() {
        // Padding represents progressively larger cells, not missing reference digits.
        val coarse = CoordinateInputParser.parse("8F000000+")
        assertNotNull(coarse)
        assertEquals(40.0, coarse!!.lat, eps)
        assertEquals(10.0, coarse.lng, eps)
        assertEquals("8F000000+", coarse.label)

        val degree = CoordinateInputParser.parse("8FVC0000+")
        assertNotNull(degree)
        assertEquals(47.5, degree!!.lat, eps)
        assertEquals(8.5, degree.lng, eps)
        assertEquals("8FVC0000+", degree.label)

        val finer = CoordinateInputParser.parse("8FVC2200+")
        assertNotNull(finer)
        assertEquals(47.025, finer!!.lat, eps)
        assertEquals(8.025, finer.lng, eps)
        assertEquals("8FVC2200+", finer.label)
    }

    @Test
    fun paddedFullPlusCodeNormalizesCaseAndAcceptsLocality() {
        val r = CoordinateInputParser.parse("  8fvc0000+ Region", -41.0, 174.0)
        assertNotNull(r)
        assertEquals(47.5, r!!.lat, eps)
        assertEquals(8.5, r.lng, eps)
        assertEquals("8FVC0000+", r.label)
    }

    @Test
    fun malformedPaddingAndEmbeddedPaddedCodesAreRejected() {
        assertNull(CoordinateInputParser.parse("8FVC0000+22"))
        assertNull(CoordinateInputParser.parse("8FVC0200+"))
        assertNull(CoordinateInputParser.parse("8FVC2000+"))
        assertNull(CoordinateInputParser.parse("8F00+", 47.0, 8.0))
        assertNull(CoordinateInputParser.parse("8FVC0000+text"))
        assertNull(CoordinateInputParser.parse("Cafe near 8FVC0000+"))
    }

    @Test
    fun shortPlusCode_recoversWithReference() {
        // shortCodeTests.csv: 9C3W9QCJ+2VX,51.3701125,-1.217765625,CJ+2VX,B
        // Recover "CJ+2VX" against the reference → full 9C3W9QCJ+2VX, whose center
        // is ~ the published lat/lng of that row.
        val r = CoordinateInputParser.parse("CJ+2VX", 51.3701125, -1.217765625)
        assertNotNull(r)
        // 11-digit code → ~3.5m precision; allow a loose tolerance for the box center.
        assertEquals(51.3701125, r!!.lat, 1e-3)
        assertEquals(-1.217765625, r.lng, 1e-3)
    }

    @Test
    fun shortPlusCode_withoutReferenceReturnsNull() {
        // Without a reference a short code cannot be recovered → fall through to geocoder.
        assertNull(CoordinateInputParser.parse("CJ+2VX"))
    }

    @Test
    fun plusCodeEmbeddedInPlaceNameIsNotHijacked() {
        // REGRESSION (audit blocker): a code-shaped substring buried in a real place
        // name must NOT be parsed offline — it must fall through (null) to the geocoder,
        // else the car routes to the wrong place. The code is only honoured when it LEADS.
        assertNull(CoordinateInputParser.parse("Cafe near 8FVC2222+22"))
        assertNull(CoordinateInputParser.parse("Meet at 849VCWC8+R9 downtown")) // code not leading
    }

    @Test
    fun leadingPlusCodeWithLocalitySuffixStillParses() {
        // The canonical Google paste form "CODE Locality" (code LEADS) must still resolve.
        val r = CoordinateInputParser.parse("8FVC2222+22 Zurich, Switzerland")
        assertNotNull(r)
        assertEquals((47.0 + 47.000125) / 2, r!!.lat, eps)
    }

    @Test
    fun shortPlusCode_withNonFiniteReferenceReturnsNull() {
        // REGRESSION: a NaN/Infinite reference must NOT recover to a bogus in-range
        // coordinate — it must return null so the input falls through to the geocoder.
        assertNull(CoordinateInputParser.parse("CJ+2VX", Double.NaN, -1.21))
        assertNull(CoordinateInputParser.parse("CJ+2VX", 51.37, Double.POSITIVE_INFINITY))
    }

    @Test
    fun shortPlusCode_withLocalityNameStillParses() {
        // Users paste "CJ+2VX Reading" — the trailing locality must not break the match.
        val r = CoordinateInputParser.parse("CJ+2VX Reading", 51.3701125, -1.217765625)
        assertNotNull(r)
        assertEquals(51.3701125, r!!.lat, 1e-3)
    }

    // ── Map share URLs ────────────────────────────────────────────────────────

    @Test
    fun googleMapsPlacePin_3d4d_preferredOverViewport() {
        // The @-viewport and the !3d!4d pin differ; the pin (place) must win.
        val url = "https://www.google.com/maps/place/Foo/@40.0,-73.0,17z/data=!3d40.748817!4d-73.985428"
        val r = CoordinateInputParser.parse(url)
        assertNotNull(r)
        assertEquals(40.748817, r!!.lat, eps)
        assertEquals(-73.985428, r.lng, eps)
    }

    @Test
    fun googleMapsAtViewport() {
        val r = CoordinateInputParser.parse("https://www.google.com/maps/@48.8584,2.2945,17z")
        assertNotNull(r)
        assertEquals(48.8584, r!!.lat, eps)
        assertEquals(2.2945, r.lng, eps)
    }

    @Test
    fun googleMapsQueryParam() {
        val r = CoordinateInputParser.parse("https://maps.google.com/?q=19.0760,72.8777")
        assertNotNull(r)
        assertEquals(19.0760, r!!.lat, eps)
        assertEquals(72.8777, r.lng, eps)
    }

    @Test
    fun googleMapsQueryParam_urlEncodedComma() {
        val r = CoordinateInputParser.parse("https://maps.google.com/?q=19.0760%2C72.8777")
        assertNotNull(r)
        assertEquals(19.0760, r!!.lat, eps)
        assertEquals(72.8777, r.lng, eps)
    }

    @Test
    fun openStreetMapMarker() {
        val r = CoordinateInputParser.parse("https://www.openstreetmap.org/?mlat=52.5200&mlon=13.4050#map=12/52.52/13.40")
        assertNotNull(r)
        // mlat/mlon (the marker) beats the #map viewport hash.
        assertEquals(52.5200, r!!.lat, eps)
        assertEquals(13.4050, r.lng, eps)
    }

    @Test
    fun openStreetMapHashOnly() {
        val r = CoordinateInputParser.parse("https://www.openstreetmap.org/#map=15/40.7128/-74.0060")
        assertNotNull(r)
        assertEquals(40.7128, r!!.lat, eps)
        assertEquals(-74.0060, r.lng, eps)
    }

    @Test
    fun geoUri() {
        val r = CoordinateInputParser.parse("geo:37.7749,-122.4194")
        assertNotNull(r)
        assertEquals(37.7749, r!!.lat, eps)
        assertEquals(-122.4194, r.lng, eps)
    }

    @Test
    fun geoUriWithParams() {
        val r = CoordinateInputParser.parse("geo:37.7749,-122.4194?z=17")
        assertNotNull(r)
        assertEquals(37.7749, r!!.lat, eps)
    }

    @Test
    fun urlWithNoCoordsReturnsNull() {
        // A short-link / place URL with NO embedded coordinates must NOT be treated
        // as free text — it returns null so the caller can expand or geocode it.
        assertNull(CoordinateInputParser.parse("https://maps.app.goo.gl/abc123"))
        assertNull(CoordinateInputParser.parse("https://www.google.com/maps/place/Eiffel+Tower"))
    }

    @Test
    fun coordLabelIsLocaleNeutral() {
        // Label must use a dot decimal regardless of the JVM default locale so it
        // round-trips through the stores cleanly.
        val r = CoordinateInputParser.parse("12.5, 77.6")
        assertNotNull(r)
        assertTrue("label should use dot decimals: ${r!!.label}", r.label.contains("."))
        assertTrue("label should not use comma decimals", !r.label.matches(Regex(".*\\d,\\d.*")))
    }
}
