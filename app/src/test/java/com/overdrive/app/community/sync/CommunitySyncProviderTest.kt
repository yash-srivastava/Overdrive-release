package com.overdrive.app.community.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommunitySyncProviderTest {
    @Test
    fun publishAndUpdateKeepTheSamePayloadAndDistinctRoutes() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "POST") 201 else 200)
                .message("OK")
                .body("""{"id":"abc"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val provider = CommunitySyncProvider({ " https://example.invalid/ " }, { client })
        val rules = JSONObject().put("disabled", true)
        val groups = JSONObject().put("group1", JSONObject().put("name", "Example group"))
        val published = provider.publish("device", "author", "name", "description",
            "category", rules, 2, groups)
        val updated = provider.update("a/b", "device", "author", "name", "description",
            "category", rules, 2, groups)
        assertTrue(published.ok)
        assertEquals(201, published.httpCode)
        assertTrue(updated.ok)
        assertEquals(200, updated.httpCode)
        assertEquals(2, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("/automations", requests[0].url.encodedPath)
        assertEquals("PUT", requests[1].method)
        assertEquals("/automations/a%2Fb", requests[1].url.encodedPath)
        for (request in requests) {
            assertEquals("device", request.header("X-Device-Id"))
            val payload = payloadOf(request)
            assertEquals(8, payload.length())
            assertEquals("device", payload.getString("deviceId"))
            assertEquals("author", payload.getString("authorName"))
            assertEquals("name", payload.getString("name"))
            assertEquals("description", payload.getString("description"))
            assertEquals("category", payload.getString("category"))
            assertEquals(2, payload.getInt("schemaVersion"))
            assertTrue(payload.getJSONObject("rules").getBoolean("disabled"))
            assertEquals("Example group", payload.getJSONObject("actionGroups")
                .getJSONObject("group1").getString("name"))
        }
        provider.publish("device", "author", "name", "", "category", rules, 2)
        provider.update("abc", "device", "author", "name", "", "category", rules, 2, JSONObject())
        assertEquals(4, requests.size)
        assertFalse(payloadOf(requests[2]).has("actionGroups"))
        assertFalse(payloadOf(requests[3]).has("actionGroups"))
    }

    @Test
    fun responseMappingRetainsSuccessAndWorkerErrors() {
        val success = communityResponse("""{"id":"abc"}""", true, 201)
        assertTrue(success.ok)
        assertEquals(201, success.httpCode)
        assertEquals("abc", success.body!!.getString("id"))
        assertNull(success.error)
        val rejected = communityResponse("""{"error":"not allowed","detail":7}""", false, 403)
        assertFalse(rejected.ok)
        assertEquals(403, rejected.httpCode)
        assertEquals("not allowed", rejected.error)
        assertEquals(7, rejected.body!!.getInt("detail"))
    }

    @Test
    fun malformedAndEmptyResponsesKeepExistingFallbacks() {
        for (raw in listOf(null, "", "not JSON")) {
            val result = communityResponse(raw, false, 502)
            assertFalse(result.ok)
            assertNull(result.body)
            assertEquals("HTTP 502", result.error)
        }
        assertEquals("HTTP 400", communityResponse("""{"error":""}""", false, 400).error)
        val emptySuccess = communityResponse(null, true, 204)
        assertTrue(emptySuccess.ok)
        assertNull(emptySuccess.body)
        assertNull(emptySuccess.error)
    }

    @Test
    fun baseUrlNormalizationRetainsExistingBoundaryCases() {
        assertNull(communityBaseUrl(null))
        assertNull(communityBaseUrl("  "))
        assertEquals("https://example.invalid", communityBaseUrl(" https://example.invalid/// "))
        assertEquals("", communityBaseUrl("/"))
    }

    private fun payloadOf(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
