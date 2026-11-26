package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class AnalyticsQueryParamsTest {

    @Test
    public void mergesRawPairsAndPreservesExistingUnrelatedEntries() {
        Map<String, String> params = new HashMap<>();
        params.put("days", "30");
        params.put("limit", "50");
        AnalyticsQueryParams.parseQueryParams(
                "days=7&&days=14&empty=&raw=a+b%20c=x&%6B=v&flag&=ignored", params);
        assertEquals("14", params.get("days"));
        assertEquals("50", params.get("limit"));
        assertEquals("", params.get("empty"));
        assertEquals("a+b%20c=x", params.get("raw"));
        assertEquals("v", params.get("%6B"));
        assertFalse(params.containsKey("flag"));
        assertFalse(params.containsKey(""));
        assertEquals(5, params.size());
    }

    @Test
    public void nullAndEmptyQueriesLeaveMapUntouched() {
        Map<String, String> params = new HashMap<>();
        params.put("days", "7");
        AnalyticsQueryParams.parseQueryParams(null, params);
        AnalyticsQueryParams.parseQueryParams("", params);
        assertEquals(1, params.size());
        assertEquals("7", params.get("days"));
    }

    @Test
    public void integerParsingPreservesSignsBoundsAndFallbacks() {
        Map<String, String> params = new HashMap<>();
        params.put("positive", "+12");
        params.put("negative", "-12");
        params.put("max", "2147483647");
        params.put("min", "-2147483648");
        assertEquals(12, AnalyticsQueryParams.getIntParam(params, "positive", 7));
        assertEquals(-12, AnalyticsQueryParams.getIntParam(params, "negative", 7));
        assertEquals(Integer.MAX_VALUE, AnalyticsQueryParams.getIntParam(params, "max", 7));
        assertEquals(Integer.MIN_VALUE, AnalyticsQueryParams.getIntParam(params, "min", 7));
        assertEquals(7, AnalyticsQueryParams.getIntParam(params, "missing", 7));
        for (String invalid : new String[] {"", " 12", "12.0", "2147483648", "-2147483649"}) {
            params.put("invalid", invalid);
            assertEquals(7, AnalyticsQueryParams.getIntParam(params, "invalid", 7));
        }
    }

    @Test
    public void longParsingSupportsEpochMillisecondsAndRejectsOverflow() {
        Map<String, String> params = new HashMap<>();
        params.put("from", "1767225600000");
        params.put("max", "9223372036854775807");
        params.put("min", "-9223372036854775808");
        assertEquals(1767225600000L, AnalyticsQueryParams.getLongParam(params, "from", -1L));
        assertEquals(Long.MAX_VALUE, AnalyticsQueryParams.getLongParam(params, "max", -1L));
        assertEquals(Long.MIN_VALUE, AnalyticsQueryParams.getLongParam(params, "min", -1L));
        assertEquals(-1L, AnalyticsQueryParams.getLongParam(params, "missing", -1L));
        for (String invalid : new String[] {"", " 12", "12.0", "9223372036854775808", "-9223372036854775809"}) {
            params.put("invalid", invalid);
            assertEquals(-1L, AnalyticsQueryParams.getLongParam(params, "invalid", -1L));
        }
    }
}
