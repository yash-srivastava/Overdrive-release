package com.overdrive.app.overlay.glass

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.overdrive.app.util.AppLocaleContext

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

    fun attach(base: Context) {
        res = try { AppLocaleContext.of(base).resources } catch (t: Throwable) { base.resources }
    }

    fun get(@StringRes id: Int): String = try { res?.getString(id) ?: "" } catch (t: Throwable) { "" }

    fun get(@StringRes id: Int, vararg args: Any): String =
        try { res?.getString(id, *args) ?: "" } catch (t: Throwable) { "" }
}

internal fun gs(@StringRes id: Int): String = GlassStrings.get(id)

internal fun gs(@StringRes id: Int, vararg args: Any): String = GlassStrings.get(id, *args)
