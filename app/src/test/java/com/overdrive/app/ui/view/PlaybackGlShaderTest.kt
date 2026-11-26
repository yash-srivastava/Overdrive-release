package com.overdrive.app.ui.view

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class PlaybackGlShaderTest {
    @Test fun standaloneShaderMatchesOriginal() {
        assertEquals("5609318ee957f99f7aa8c8de6b8740ba3ff3339f8f11fc1a070ab7a6aa856f33", sha256(PlaybackGl.dewarpShader))
    }

    @Test fun timelineShaderMatchesOriginal() {
        assertEquals("a50f9c9d230985e1a7d0767ab81dadce6489340283038e38015de8b3d8f795ef", sha256(PlaybackGl.timelineDewarpShader))
    }

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
