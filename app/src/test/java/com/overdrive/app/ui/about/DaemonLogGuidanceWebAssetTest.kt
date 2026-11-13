package com.overdrive.app.ui.about

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonLogGuidanceWebAssetTest {
    @Test
    fun aboutExplainsDaemonOwnershipAndWhenToSendEachLog() {
        val html = readRepositoryFile("app/src/main/assets/web/local/about.html")
        val script = readRepositoryFile("app/src/main/assets/web/shared/send-logs.js")
        val api = readRepositoryFile("app/src/main/java/com/overdrive/app/server/LogsApiHandler.java")

        assertTrue(html.contains("send the tunnel you use"))
        assertTrue(html.contains("send-logs.js?v=4"))
        assertTrue(script.contains("res.descriptions"))
        assertTrue(script.contains("diag-component"))
        assertTrue(script.contains("diag-when"))
        assertTrue(script.contains("logs.when_to_send"))
        assertTrue(api.contains("r.put(\"descriptions\", descriptions)"))
        assertTrue(api.contains("description.component"))
        assertTrue(api.contains("description.sendWhen"))
    }

    private fun readRepositoryFile(relative: String): String {
        var current: Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            val direct = current.resolve(relative)
            if (Files.exists(direct)) {
                return String(Files.readAllBytes(direct), StandardCharsets.UTF_8)
            }
            val fromModule = current.resolve(relative.removePrefix("app/"))
            if (Files.exists(fromModule)) {
                return String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8)
            }
            current = current.parent
        }
        throw AssertionError("Could not locate $relative")
    }
}
