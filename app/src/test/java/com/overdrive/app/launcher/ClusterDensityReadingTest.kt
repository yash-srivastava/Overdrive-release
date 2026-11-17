package com.overdrive.app.launcher

import org.junit.Assert.*
import org.junit.Test

class ClusterDensityReadingTest {
    @Test fun distinguishesPhysicalAndUserOverride() {
        assertEquals(DensityReading(160, 240),
            DensityReading.parse("Physical density: 160\nOverride density: 240\n"))
        assertEquals(DensityReading(160, null),
            DensityReading.parse("Physical density: 160\n"))
    }

    @Test fun refusesUnsupportedOrAmbiguousCommandOutput() {
        assertNull(DensityReading.parse("Error: unknown option -d"))
        assertNull(DensityReading.parse("Physical density: 0\n"))
        assertNull(DensityReading.parse("Physical density: 160\nOverride density: 0\n"))
        assertNull(DensityReading.parse("Physical density: 160\nPhysical density: 240\n"))
    }

    @Test fun neverBuildsAHeadUnitDensityCommand() {
        assertNull(DensityReading.command(0, 240))
        assertNull(DensityReading.command(-1, 0))
        assertNull(DensityReading.command(1, -200))
        assertEquals(listOf("/system/bin/wm", "density", "reset", "-d", "7"),
            DensityReading.command(7, 0))
    }

    @Test fun privateDisplayWindowDumpKeepsInitialAndOverrideDensitySeparate() {
        val dump = "WINDOW MANAGER DISPLAY CONTENTS\n" +
            "Display: mDisplayId=0\n  init=1920x1080 240dpi cur=1920x1080\n" +
            "Display: mDisplayId=7\n  init=1920x720 160dpi base=1920x720 220dpi cur=1920x720\n"
        assertEquals(DensityReading(160, 220), DensityReading.parseWindowDump(dump, 7))
        assertNull(DensityReading.parseWindowDump(dump, 0))
        assertNull(DensityReading.parseWindowDump(dump, 1))
    }

    @Test fun sizeOverrideAloneDoesNotInventADensityOverride() {
        assertEquals(DensityReading(160, null), DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi base=1600x600 160dpi cur=1600x600\n", 7))
    }

    @Test fun unchangedPrivateDisplayDensityIsReadable() {
        assertEquals(DensityReading(160, null), DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi cur=1920x720\n", 7))
    }

    @Test fun defaultDensityIsAValidEffectiveRequestedValue() {
        assertEquals(160, DensityReading(160, null).effective)
        assertEquals(220, DensityReading(160, 220).effective)
        assertEquals(160, DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi base=1920x720 160dpi cur=1920x720\n", 7)?.effective)
    }

    @Test fun rejectsUnreadableOrAmbiguousWindowBlocks() {
        assertNull(DensityReading.parseWindowDump("Permission Denial", 7))
        assertNull(DensityReading.parseWindowDump(null, 7))
        assertNull(DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi\n" +
                "Display: mDisplayId=7\n  init=1920x720 220dpi\n", 7))
        assertNull(DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi\n  init=1920x720 220dpi\n", 7))
    }

    @Test fun doesNotBorrowDensityFromTheNextDisplay() {
        assertNull(DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  unavailable\n" +
                "Display: mDisplayId=8\n  init=1920x720 160dpi\n", 7))
    }

    @Test fun invalidBaseAndOverflowMustNotBecomeThePhysicalDensity() {
        for (density in listOf("0", "10001", "999999999999")) {
            assertNull(DensityReading.parseWindowDump(
                "Display: mDisplayId=7\n  init=1920x720 160dpi base=1920x720 ${density}dpi\n", 7))
        }
        assertNull(DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 0dpi\n", 7))
    }

    @Test fun multipleBaseDensitiesMustNotAuthorizeAWrite() {
        assertNull(DensityReading.parseWindowDump(
            "Display: mDisplayId=7\n  init=1920x720 160dpi base=1920x720 220dpi base=1920x720 240dpi\n", 7))
    }

    @Test fun onlyTheRecognizedPrivateDisplayQueryCanUseTheWindowFallback() {
        assertTrue(DensityReading.canUseWindowFallback("Physical density: -1\n"))
        assertTrue(DensityReading.canUseWindowFallback("\n  Physical density: -1  \n"))
        for (output in listOf(
            "Error: unknown option -d",
            "Error: bad number java.lang.NumberFormatException: -d",
            "Physical density: -1\nError: unknown option -d\n",
            "Physical density: 160\n",
            "Physical density: -1\nPhysical density: -1\n",
            "Permission Denial"
        )) assertFalse(DensityReading.canUseWindowFallback(output))
    }
}
