package com.overdrive.app.ui.fragment

/** Copy response headers for the intercept-to-WebView connection, not the upstream connection. */
internal fun webViewResponseHeaders(fields: Map<out String?, List<String>>?): MutableMap<String, String> {
    val headers = mutableMapOf<String, String>()
    fields?.forEach { (key, values) ->
        if (key == null || values.isEmpty()) return@forEach
        // HttpURLConnection may decode gzip; upstream encoding/length and hop-by-hop
        // headers no longer describe the stream handed to WebView.
        when (key.lowercase()) {
            "content-encoding", "content-length", "transfer-encoding", "connection",
            "keep-alive", "proxy-authenticate", "trailer", "te", "upgrade" -> return@forEach
        }
        headers[key] = values.last()
    }
    headers["Access-Control-Allow-Origin"] = "*"
    return headers
}
