package com.overdrive.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Native UI reads the same ownership/triage copy as the web diagnostics API. */
class DaemonLogGuidanceNativeTest {

    @Test
    fun keysAlignWithDescriptions() {
        for (key in DaemonLogPaths.keys()) {
            assertNotNull(DaemonLogPaths.descriptionFor(key))
        }
    }

    @Test
    fun logDescriptionFieldsMatchJavaSource() {
        val camera = DaemonLogPaths.descriptionFor("camera")!!
        assertEquals("Camera", camera.name)
        assertEquals(
            "Core OverDrive service — WebUI, HTTP/API, camera feeds and streaming, plus shared vehicle/app integrations.",
            camera.component,
        )
    }
}