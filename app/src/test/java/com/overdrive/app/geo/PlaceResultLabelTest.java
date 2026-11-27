package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class PlaceResultLabelTest {

    @Test
    public void countryOnlyCachedResultHasLabelsAfterJsonRoundTrip() throws Exception {
        JSONObject json = new JSONObject().put("country", "Switzerland")
                .put("countryCode", "CH").put("locale", "en");
        PlaceResult result = PlaceResult.fromJson(json);
        assertNotNull(result);
        assertEquals("Switzerland", result.shortLabel());
        assertEquals("Switzerland", result.mediumLabel());

        PlaceResult restored = PlaceResult.fromJson(result.toJson());
        assertNotNull(restored);
        assertEquals("Switzerland", restored.shortLabel());
        assertEquals("Switzerland", restored.mediumLabel());
    }

    @Test
    public void moreSpecificLabelsKeepTheirExistingPriority() {
        PlaceResult full = place("Full address", "District", "City", "Country");
        assertEquals("District", full.shortLabel());
        assertEquals("District, City", full.mediumLabel());

        PlaceResult sameDistrictAndCity = place("Full address", "City", "City", "Country");
        assertEquals("City", sameDistrictAndCity.shortLabel());
        assertEquals("City", sameDistrictAndCity.mediumLabel());

        PlaceResult cityOnly = place("Full address", "", "City", "Country");
        assertEquals("City", cityOnly.shortLabel());
        assertEquals("City", cityOnly.mediumLabel());

        PlaceResult addressOnly = place("Full address", "", "", "Country");
        assertEquals("Full address", addressOnly.shortLabel());
        assertEquals("Full address", addressOnly.mediumLabel());
    }

    @Test
    public void missingNamesRemainMissing() throws Exception {
        PlaceResult empty = place(null, null, null, null);
        assertEquals("", empty.shortLabel());
        assertEquals("", empty.mediumLabel());
        assertNull(PlaceResult.fromJson(new JSONObject().put("countryCode", "CH")));
    }

    private static PlaceResult place(String displayName, String district, String city, String country) {
        return new PlaceResult(displayName, district, city, country, "", "en",
                PlaceResult.Source.CACHE, 123L);
    }
}
