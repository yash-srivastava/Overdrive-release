package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public class AnalyticsResponsesTest {

    @Test
    public void sectionStripsOnlyTransportFieldsEvenForHandlerErrors() throws Exception {
        JSONObject payload = new JSONObject().put("success", false).put("_status", 404)
                .put("error", "not found").put("data", new JSONObject().put("success", true));
        JSONObject result = AnalyticsResponses.section(() -> payload, message -> fail(message));
        assertSame(payload, result);
        assertEquals(2, result.length());
        assertEquals("not found", result.getString("error"));
        assertTrue(result.getJSONObject("data").getBoolean("success"));
    }

    @Test
    public void missingAndThrowingSectionsReturnIndependentErrorStubs() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals("empty section", AnalyticsResponses.section(() -> null, warnings::add)
                .getString("error"));
        assertTrue(warnings.isEmpty());
        assertEquals("unavailable", AnalyticsResponses.section(() -> {
            throw new IllegalStateException("unavailable");
        }, warnings::add).getString("error"));
        assertEquals("section failed", AnalyticsResponses.section(() -> {
            throw new IllegalStateException();
        }, warnings::add).getString("error"));
        assertEquals(2, warnings.size());
        assertEquals("Bootstrap section failed: unavailable", warnings.get(0));
        assertEquals("Bootstrap section failed: null", warnings.get(1));
    }

    @Test
    public void bootstrapFailureRetainsPartialSectionsAndExistingFields() throws Exception {
        JSONObject bootstrap = new JSONObject().put("config", new JSONObject());
        JSONObject response = new JSONObject();
        AnalyticsResponses.completeBootstrapFailure(response, bootstrap, new Exception());
        assertFalse(response.getBoolean("success"));
        assertSame(bootstrap, response.getJSONObject("bootstrap"));
        assertEquals("bootstrap failed", response.getString("error"));
        JSONObject existing = new JSONObject();
        response.put("success", true).put("bootstrap", existing);
        AnalyticsResponses.completeBootstrapFailure(response, bootstrap, new Exception("late error"));
        assertTrue(response.getBoolean("success"));
        assertSame(existing, response.getJSONObject("bootstrap"));
        assertEquals("late error", response.getString("error"));
    }

    @Test
    public void errorEnvelopeKeepsStatusAndMessage() throws Exception {
        JSONObject error = AnalyticsResponses.error("Not found", 404,
                (message, cause) -> fail(message));
        assertEquals(3, error.length());
        assertFalse(error.getBoolean("success"));
        assertEquals("Not found", error.getString("error"));
        assertEquals(404, error.getInt("_status"));
    }
}
