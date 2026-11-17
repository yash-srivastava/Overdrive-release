package com.overdrive.app.overlay.glass

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
import com.overdrive.app.util.AppLocaleContext
import java.util.Locale

/**
 * The widgets overlay's text, in the language picked in the app.
 *
 * The overlay is a bare Service, and a Service's resources follow the system locale, not the
 * per-app language set through AppCompatDelegate. So it keeps a Resources built for the app's
 * language (through AppLocaleContext, like the RoadSense and status overlays do) and every view reads its
 * labels from here, at draw time, so a language change shows on the next redraw.
 */
object GlassStrings {
    @Volatile private var res: Resources? = null
    @Volatile private var english: Resources? = null
    @Volatile private var warned = false

    fun attach(base: Context) {
        res = try { AppLocaleContext.of(base).resources } catch (t: Throwable) { base.resources }
        english = try {
            base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }).resources
        } catch (t: Throwable) { base.resources }
    }

    fun get(@StringRes id: Int): String =
        try { res?.getString(id) ?: "" } catch (t: Throwable) { fallback(t) { english?.getString(id) } }

    // Locale.US keeps ASCII digits, like the widgets' other numbers (ar would get Arabic-Indic ones).
    fun get(@StringRes id: Int, vararg args: Any): String =
        try { res?.getString(id)?.let { String.format(Locale.US, it, *args) } ?: "" }
        catch (t: Throwable) { fallback(t) { english?.getString(id)?.let { String.format(Locale.US, it, *args) } } }

    // A broken translation shows the English label, not a blank one.
    private inline fun fallback(t: Throwable, english: () -> String?): String {
        if (!warned) { warned = true; Log.w("GlassStrings", "label lookup failed; using English", t) }
        return try { english() ?: "" } catch (e: Throwable) { "" }
    }
}

internal fun gs(@StringRes id: Int): String = GlassStrings.get(id)

internal fun gs(@StringRes id: Int, vararg args: Any): String = GlassStrings.get(id, *args)
