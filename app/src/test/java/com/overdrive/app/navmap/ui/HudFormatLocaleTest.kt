package com.overdrive.app.navmap.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HudFormatLocaleTest {
    @Test
    fun arabicDigitsAreUsedForWholeAndFractionalDistances() {
        withLocale(Locale.forLanguageTag("ar-EG-u-nu-arab")) {
            assertDistance(180.0, false, HudFormat.M, "\u0661\u0668\u0660")
            assertDistance(6600.0, false, HudFormat.KM, "\u0666\u066b\u0666")
            assertDistance(12000.0, false, HudFormat.KM, "\u0661\u0662")
            assertDistance(91.44, true, HudFormat.FT, "\u0663\u0660\u0660")
            assertDistance(1609.344, true, HudFormat.MI, "\u0661\u066b\u0660")
            assertDistance(0.0, false, HudFormat.M, "\u0660")
        }
    }

    @Test
    fun usFormattingKeepsExistingRoundingAndUnits() {
        withLocale(Locale.US) {
            assertDistance(182.0, false, HudFormat.M, "180")
            assertDistance(975.0, false, HudFormat.KM, "1.0")
            assertDistance(6600.0, false, HudFormat.KM, "6.6")
            assertDistance(9950.0, false, HudFormat.KM, "10")
            assertDistance(12400.0, false, HudFormat.KM, "12")
            assertDistance(91.44, true, HudFormat.FT, "300")
            assertDistance(1609.344, true, HudFormat.MI, "1.0")
        }
    }

    @Test
    fun germanFormattingKeepsDecimalComma() {
        withLocale(Locale.GERMANY) {
            assertDistance(6600.0, false, HudFormat.KM, "6,6")
            assertDistance(1609.344, true, HudFormat.MI, "1,0")
            assertDistance(12000.0, false, HudFormat.KM, "12")
        }
    }

    private fun assertDistance(meters: Double, imperial: Boolean, unit: Int, number: String) {
        val key = HudFormat.distanceKey(meters, imperial)
        assertEquals(unit, HudFormat.unit(key))
        assertEquals(number, HudFormat.number(key))
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(original)
            Locale.setDefault(Locale.Category.DISPLAY, display)
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }
}
