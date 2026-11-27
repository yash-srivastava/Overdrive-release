package com.overdrive.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class RecordingScannerDateKeyTest {
    @Test
    fun indexKeysUseAsciiDigitsInEveryFormattingLocale() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ar-EG"),
                Locale.forLanguageTag("fa-IR"))) {
                Locale.setDefault(Locale.Category.FORMAT, locale)
                assertEquals("2026-01-05", RecordingScanner.dateKey(2026, Calendar.JANUARY, 5))
                assertEquals("2026-12-31", RecordingScanner.dateKey(2026, Calendar.DECEMBER, 31))
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }
}
