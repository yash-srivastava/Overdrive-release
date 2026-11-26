package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class PerformanceClientIdTest {
    @Test
    public void absentOrMalformedBodyHasNoClientId() {
        assertNull(PerformanceApiHandler.parseClientId(null));
        assertNull(PerformanceApiHandler.parseClientId(""));
        assertNull(PerformanceApiHandler.parseClientId("{"));
        assertNull(PerformanceApiHandler.parseClientId("{}"));
    }

    @Test
    public void suppliedIdIsNotTrimmedOrReplaced() {
        assertEquals("client-42", PerformanceApiHandler.parseClientId("{\"clientId\":\"client-42\"}"));
        assertEquals("", PerformanceApiHandler.parseClientId("{\"clientId\":\"\"}"));
        assertEquals("  client  ", PerformanceApiHandler.parseClientId("{\"clientId\":\"  client  \"}"));
    }

    @Test
    public void nonStringValuesRetainJsonOptStringSemantics() throws Exception {
        for (String body : new String[]{"{\"clientId\":42}", "{\"clientId\":true}", "{\"clientId\":null}"}) {
            assertEquals(new JSONObject(body).optString("clientId", null),
                    PerformanceApiHandler.parseClientId(body));
        }
    }
}
