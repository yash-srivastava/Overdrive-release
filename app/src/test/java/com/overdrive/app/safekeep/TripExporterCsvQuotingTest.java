package com.overdrive.app.safekeep;

import com.overdrive.app.trips.TripRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TripExporterCsvQuotingTest {

    @Test
    public void currencyWithLineBreaksIsQuoted() {
        assertCurrencyField("CHF\nlocal", "\"CHF\nlocal\"");
        assertCurrencyField("CHF\rlocal", "\"CHF\rlocal\"");
        assertCurrencyField("CHF\r\nlocal", "\"CHF\r\nlocal\"");
    }

    @Test
    public void quotesAndCommasRemainEscapedAlongsideLineBreaks() {
        assertCurrencyField("CHF,\"local\"\nrate", "\"CHF,\"\"local\"\"\nrate\"");
        assertCurrencyField("CHF,local", "\"CHF,local\"");
        assertCurrencyField("CHF\"", "\"CHF\"\"\"");
    }

    @Test
    public void ordinaryAndAbsentCurrenciesKeepExistingOutput() {
        assertCurrencyField("CHF", "CHF");
        assertCurrencyField("", "");
        assertCurrencyField(null, "");
    }

    private static void assertCurrencyField(String currency, String expected) {
        TripRecord trip = new TripRecord();
        trip.currency = "";
        String baseline = TripExporter.csvRow(trip);
        // The currency follows twelve fields, none of which contain commas or quotes.
        int start = 0;
        for (int i = 0; i < 12; i++) {
            start = baseline.indexOf(',', start) + 1;
        }
        assertEquals(',', baseline.charAt(start));
        trip.currency = currency;
        // Compare the whole row so that both adjoining delimiters and all other
        // columns, including the final record terminator, must remain unchanged.
        assertEquals(baseline.substring(0, start) + expected + baseline.substring(start),
                TripExporter.csvRow(trip));
    }
}
