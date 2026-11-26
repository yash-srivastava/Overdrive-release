package com.overdrive.app.ui.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.overdrive.app.R
import java.util.Locale

/** Resolve the settings language label using the fragment's app-locale resources. */
internal fun Fragment.currentLocaleDisplay(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    val tag = if (!locales.isEmpty) locales[0]?.toLanguageTag() else null
    val displayLocale = resources.configuration.locales[0]
    return if (tag.isNullOrEmpty()) {
        getString(R.string.settings_theme_auto).substringBefore('(').trim()
            .ifEmpty { displayLocale.displayLanguage }
    } else {
        Locale.forLanguageTag(tag).getDisplayName(displayLocale)
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString()
            }
    }
}
