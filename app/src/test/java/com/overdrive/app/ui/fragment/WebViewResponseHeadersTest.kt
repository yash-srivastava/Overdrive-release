package com.overdrive.app.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewResponseHeadersTest {
    @Test
    fun removesTransportHeadersCaseInsensitively() {
        val upstream = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 206 Partial Content"),
            "Content-Encoding" to listOf("gzip"),
            "CONTENT-LENGTH" to listOf("23"),
            "Transfer-Encoding" to listOf("chunked"),
            "Connection" to listOf("keep-alive"),
            "Keep-Alive" to listOf("timeout=5"),
            "Proxy-Authenticate" to listOf("Basic"),
            "Trailer" to listOf("Digest"),
            "TE" to listOf("trailers"),
            "Upgrade" to listOf("websocket"),
            "Empty" to emptyList(),
            "Content-Range" to listOf("bytes 0-9/20"),
            "ETag" to listOf("old", "new"),
            "Cache-Control" to listOf("private"),
            "Access-Control-Allow-Origin" to listOf("https://example.com"),
        )
        assertEquals(mapOf(
            "Content-Range" to "bytes 0-9/20",
            "ETag" to "new",
            "Cache-Control" to "private",
            "Access-Control-Allow-Origin" to "*",
        ), webViewResponseHeaders(upstream))
    }

    @Test
    fun missingHeadersStillGetExistingCorsDefault() {
        assertEquals(mapOf("Access-Control-Allow-Origin" to "*"), webViewResponseHeaders(null))
    }

    @Test
    fun callerOverridesDoNotChangeUpstreamOrOtherResponses() {
        val upstream = mapOf("Content-Length" to listOf("23"))
        val local = webViewResponseHeaders(upstream)
        local["Content-Length"] = "10"
        local["Accept-Ranges"] = "bytes"
        assertEquals(mapOf("Content-Length" to listOf("23")), upstream)
        assertEquals(mapOf("Access-Control-Allow-Origin" to "*"), webViewResponseHeaders(upstream))
    }
}
