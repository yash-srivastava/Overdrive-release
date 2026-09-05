package com.overdrive.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DashboardLayoutContractTest {

    @Test
    fun portraitAndLandscapeExposeTheSameIdContract() {
        val portrait = readResource("layout/fragment_dashboard.xml")
        val landscape = readResource("layout-land/fragment_dashboard.xml")

        assertEquals(ids(portrait), ids(landscape))
    }

    @Test
    fun bothLayoutsKeepBehaviorAndOnboardingAnchors() {
        val requiredIds = setOf(
            "heroCard",
            "metricVehicle",
            "metricVehicleValue",
            "quickLive",
            "metricRecordings",
            "metricTunnel",
            "cardDaemons",
            "tvDeviceToken",
            "btnToggleToken",
            "btnCopyToken",
            "btnRegenerateToken",
            "chipGroupTunnels",
            "ivQrCode",
            "vehicleSocValue",
            "vehicleRangeValue",
            "vehicleArt",
            "chargingCard",
            "recordingStorageProgress",
            "activityRow1",
            "activityRow2",
            "activityRow3",
            "activityItem1",
            "activityItem2",
            "activityItem3",
            "activityIcon1",
            "activityIcon2",
            "activityIcon3",
            "quickActionsCard",
            "remoteDetails",
            "btnExpandRemote",
        )

        listOf(
            readResource("layout/fragment_dashboard.xml"),
            readResource("layout-land/fragment_dashboard.xml"),
        ).forEach { layout ->
            assertTrue(ids(layout).containsAll(requiredIds))
            assertTrue(layout.contains("app:singleLine=\"false\""))
            assertTrue(layout.contains(
                "android:layout_width=\"@dimen/dashboard_modern_touch_target\""
            ))
            assertTrue(layout.contains(
                "android:layout_height=\"@dimen/dashboard_modern_touch_target\""
            ))
        }
    }

    @Test
    fun dashboardUsesCompactCardsAndQueuesLiveStatusFirst() {
        val resources = readResource("values/dashboard_modern_resources.xml")
        val source = readSource(
            "main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt"
        )
        val onResume = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
        val statusIndex = onResume.indexOf("refreshVehicleStatus(showLoading = true)")
        val metricsIndex = onResume.indexOf("refreshMetricsTiles()")
        val insightsIndex = onResume.indexOf("rebuildInsightsAsync()")

        assertTrue(resources.contains(
            "<dimen name=\"dashboard_modern_radius\">8dp</dimen>"
        ))
        assertTrue(statusIndex >= 0)
        assertTrue(metricsIndex >= 0)
        assertTrue(insightsIndex >= 0)
        assertTrue(statusIndex < metricsIndex)
        assertTrue(statusIndex < insightsIndex)
    }

    @Test
    fun optionalAiInsightCardIsHiddenAndSharedByBothLayouts() {
        val include = readResource("layout/include_dashboard_ai_insight.xml")

        assertTrue(include.contains("android:id=\"@+id/aiInsightCard\""))
        assertTrue(include.contains("android:visibility=\"gone\""))
        assertTrue(include.contains("android:id=\"@+id/aiInsightText\""))
        assertTrue(include.contains("android:id=\"@+id/aiInsightExpand\""))
        assertTrue(include.contains("android:id=\"@+id/aiInsightIconSurface\""))
        assertTrue(include.contains(
            "android:foreground=\"?attr/selectableItemBackground\""
        ))
        listOf(
            readResource("layout/fragment_dashboard.xml"),
            readResource("layout-land/fragment_dashboard.xml"),
        ).forEach { layout ->
            assertTrue(layout.contains(
                "<include layout=\"@layout/include_dashboard_ai_insight\""
            ))
        }

        val source = readSource(
            "main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt"
        )
        assertTrue(source.contains(
            "aiInsightExpanded = !aiInsightExpanded"
        ))
        assertTrue(source.contains(
            "if (aiInsightExpanded) Int.MAX_VALUE else AI_INSIGHT_PREVIEW_LINES"
        ))
        assertTrue(!source.contains(
            "aiInsightCard.setOnClickListener {\n" +
                "            findNavController().navigate(R.id.genAiFragment"
        ))
    }

    @Test
    fun quickActionTilesHaveEqualWidthAndHeightContracts() {
        listOf(
            readResource("layout/fragment_dashboard.xml"),
            readResource("layout-land/fragment_dashboard.xml"),
        ).forEach { layout ->
            val live = openingTagForId(layout, "quickLive")
            val services = openingTagForId(layout, "cardDaemons")

            listOf("layout_width", "layout_height", "layout_weight", "minHeight")
                .forEach { attribute ->
                    assertEquals(
                        attributeValue(live, attribute),
                        attributeValue(services, attribute),
                    )
                }
            assertEquals("0dp", attributeValue(live, "layout_width"))
            assertEquals("match_parent", attributeValue(live, "layout_height"))
            assertEquals("1", attributeValue(live, "layout_weight"))
            assertEquals(
                "@dimen/dashboard_modern_action_min_height",
                attributeValue(live, "minHeight"),
            )
        }
    }

    @Test
    fun chargingMetricTilesShareTheSameSizingContract() {
        listOf(
            readResource("layout/fragment_dashboard.xml"),
            readResource("layout-land/fragment_dashboard.xml"),
        ).forEach { layout ->
            listOf(
                "chargingPowerGroup",
                "chargingEtaGroup",
                "chargingSessionGroup",
            ).forEach { id ->
                val tag = openingTagForId(layout, id)
                assertEquals("0dp", attributeValue(tag, "layout_width"))
                assertEquals("match_parent", attributeValue(tag, "layout_height"))
                assertEquals("1", attributeValue(tag, "layout_weight"))
            }
        }
    }

    @Test
    fun chargingSessionEnergyKeepsApproximationMarker() {
        val source = readSource(
            "main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt"
        )

        assertTrue(source.contains("charging.sessionEnergyEstimated"))
        assertTrue(source.contains("charging.sessionEnergyIncomplete"))
        assertTrue(source.contains("\"~\$rendered\""))
    }

    @Test
    fun qrRenderingIsExpandedOnlyCachedAndBulkWritten() {
        val dashboard = readSource(
            "main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt"
        )
        val generator = readSource(
            "main/java/com/overdrive/app/ui/util/QrCodeGenerator.kt"
        )
        val renderQr = dashboard.substringAfter("private fun renderQr(url: String?)")
            .substringBefore("private fun showPlaceholder()")

        assertTrue(renderQr.contains("if (!dashboardState.remoteExpanded) return"))
        assertTrue(renderQr.contains("url == lastRenderedQrUrl"))
        assertTrue(generator.contains("bitmap.setPixels("))
        assertTrue(!generator.contains("bitmap.setPixel("))
    }

    private fun ids(xml: String): Set<String> =
        Regex("""android:id="@\+id/([^"]+)"""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

    private fun openingTagForId(xml: String, id: String): String {
        val marker = """android:id="@+id/$id""""
        val idIndex = xml.indexOf(marker)
        require(idIndex >= 0) { "Missing dashboard view id: $id" }
        return xml.substring(
            startIndex = xml.lastIndexOf('<', idIndex),
            endIndex = xml.indexOf('>', idIndex) + 1,
        )
    }

    private fun attributeValue(tag: String, attribute: String): String =
        Regex("""android:$attribute="([^"]+)"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?: error("Missing android:$attribute in $tag")

    private fun readResource(relative: String): String {
        val current = Paths.get("").toAbsolutePath()
        val candidates = listOf(
            current.resolve("src/main/res").resolve(relative),
            current.resolve("app/src/main/res").resolve(relative),
        )
        val path: Path = candidates.firstOrNull(Files::exists)
            ?: error("Could not locate dashboard resource: $relative")
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    private fun readSource(relative: String): String {
        val current = Paths.get("").toAbsolutePath()
        val candidates = listOf(
            current.resolve("src").resolve(relative),
            current.resolve("app/src").resolve(relative),
        )
        val path: Path = candidates.firstOrNull(Files::exists)
            ?: error("Could not locate dashboard source: $relative")
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }
}
