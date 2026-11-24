package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig
import com.overdrive.app.launcher.dilink5.cluster.DiLink5ProjectionConfig.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiLink5CustomProjectionParityTest {
    private val cast = File("src/main/java/com/overdrive/app/launcher/DiLink5ClusterCast.kt").readText()

    @Test fun customLaunchUsesTheNormalTransportAndTargetSelection() {
        // The selectors themselves already worked in 63.3.2. Its custom launch bypassed
        // them at these three call sites, so exercising the selectors alone misses the bug.
        val start = method("startSession")
        val mini = start.substringAfter("val factoryMiniMap =").substringBefore("val projectionTarget")
        assertTrue(mini.contains("Transport.FACTORY_MINI"))
        assertFalse(mini.contains("SessionKind.CUSTOM"))
        val fallback = start.substringBefore("shouldFallbackToHostedAfterDirectFailure(")
            .substringAfterLast("if (")
        assertTrue(start.contains("shouldFallbackToHostedAfterDirectFailure("))
        assertFalse(fallback.contains("SessionKind.CUSTOM"))

        val resolve = method("enableAndAwaitProjectionDisplay")
        assertTrue(resolve.contains("configureProjectionTarget("))
        assertFalse(resolve.contains("directOnly"))
        val transport = method("configureProjectionTarget")
        assertTrue(transport.contains("config.initialTransport(appDisplayVisible)"))
        assertFalse(transport.contains("directOnly"))
        assertTrue(resolve.substringAfter("val preferFull =").lineSequence().first()
            .contains("config.target == DiLink5ProjectionConfig.Target.FULL"))
        val remembered = resolve.substringAfter("val preferredName =").substringBefore("val reclaimed =")
        assertTrue(remembered.contains("readSharedDisplayPreference()"))
        assertFalse(remembered.contains("SessionKind.CUSTOM"))
    }

    @Test fun recordedStandardAndFullTargetsRespectSavedChoicesAtEachTransport() {
        val standard = "shared_fission_bg_XDJAScreenProjection_1"
        val full = "shared_fission_bg_XDJAScreenProjection_0"
        val names = listOf(full, standard)
        for (transport in Transport.entries) {
            for (target in DiLink5ProjectionConfig.Target.entries) {
                val cfg = DiLink5ProjectionConfig.from(JSONObject()
                    .put(DiLink5ProjectionConfig.KEY_TRANSPORT, transport.wire)
                    .put(DiLink5ProjectionConfig.KEY_TARGET, target.wire))
                val preferFull = cfg.target == DiLink5ProjectionConfig.Target.FULL
                if (transport == Transport.FACTORY_MINI) {
                    val records = names.mapIndexed { index, name ->
                        """mOverrideDisplayInfo=DisplayInfo{"$name", displayId ${3 + index}, real 1920 x 660, state ON}"""
                    }
                    assertEquals(DiLink5ProjectionDisplayRef(4, standard, 1920, 660),
                        resolveFactoryMiniMapDisplay(records))
                } else {
                    assertEquals(if (preferFull) full else standard,
                        selectDiLink5ProjectionDisplayName(names, preferFull, requireShared = true))
                }
                assertEquals(if (transport == Transport.HOSTED) Transport.HOSTED else Transport.DIRECT,
                    cfg.initialTransport(appDisplayVisible = true))
                assertEquals(Transport.DIRECT, cfg.initialTransport(appDisplayVisible = false))
                assertEquals(transport == Transport.AUTO, shouldFallbackToHostedAfterDirectFailure(
                    "direct", transport == Transport.AUTO, succeeded = false, definiteFailure = true))
                assertFalse(shouldFallbackToHostedAfterDirectFailure(
                    "direct", transport == Transport.AUTO, succeeded = false, definiteFailure = false))
            }
        }
    }

    @Test fun customLayoutsRequireDiLink5EvenWhenOrdinaryProjectionSupportsHdmi() {
        val entry = cast.substringAfter("fun startCustomLayouts(owner: Long): Long {")
            .substringBefore("return startTarget(")
        assertTrue(entry.contains("!ClusterTransport.isDiLink5()"))
        val hdmi = method("enableAndAwaitProjectionDisplay")
            .substringAfter("if (ClusterTransport.isAaosHdmi()) {")
            .substringBefore("configureHostedProjectionTarget(")
        assertTrue(hdmi.contains("check(kind != SessionKind.CUSTOM)"))
    }

    private fun method(name: String): String {
        val signature = "private fun $name("
        assertTrue("Missing $signature", cast.contains(signature))
        return cast.substringAfter(signature).substringBefore("\n    private fun ")
    }
}
