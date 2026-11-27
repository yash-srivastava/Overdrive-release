package com.overdrive.app.briefing.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BriefingJsonLabelTest {
    @Test
    fun rejectsLabelsThatBecomeInvisibleAfterTruncation() {
        for (invisible in listOf("\u200B", "\u0301", "\u3164")) {
            assertNull(BriefingJson.cleanLabel(invisible.repeat(40) + "Home"))
        }
    }

    @Test
    fun keepsVisibleCharactersWithinLimit() {
        val label = "\u200B".repeat(39) + "H"
        assertEquals(label, BriefingJson.cleanLabel(label + "ome"))
        assertEquals("A".repeat(40), BriefingJson.cleanLabel("A".repeat(41)))
    }

    @Test
    fun truncatesByCodePointWithoutSplittingSurrogatePairs() {
        val emoji = "\uD83D\uDE80"
        val label = "A".repeat(39) + emoji
        assertEquals(label, BriefingJson.cleanLabel(label + "B"))
    }

    @Test
    fun preservesExistingCleaningRules() {
        assertEquals("Home", BriefingJson.cleanLabel("  H\u0000o\uD800me  "))
        assertNull(BriefingJson.cleanLabel("\u200B\u0301"))
        assertNull(BriefingJson.cleanLabel("   "))
        assertNull(BriefingJson.cleanLabel(null))
        assertNull(BriefingJson.cleanLabel(42))
    }
}
