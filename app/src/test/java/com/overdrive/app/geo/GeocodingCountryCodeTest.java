package com.overdrive.app.geo;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class GeocodingCountryCodeTest {

    @Test
    public void countryCodesAreIndependentOfDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("tr", "TR")}) {
                Locale.setDefault(locale);
                assertEquals("FI", code("fi"));
                assertEquals("IN", code("in"));
                assertEquals("IS", code("is"));
                assertEquals("IT", code("it"));
                assertEquals("CH", code("ch"));
                assertEquals("FI", code("FI"));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void missingAndEmptyCodesStayEmpty() throws Exception {
        assertEquals("", GeocodingResolver.nominatimCountryCode(new JSONObject()));
        assertEquals("", code(""));
    }

    private static String code(String value) throws Exception {
        return GeocodingResolver.nominatimCountryCode(new JSONObject().put("country_code", value));
    }
}
