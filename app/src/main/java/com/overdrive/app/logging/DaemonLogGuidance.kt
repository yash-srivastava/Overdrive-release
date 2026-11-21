package com.overdrive.app.logging

import android.content.Context
import com.overdrive.app.R

/**
 * Localized daemon-log triage copy for native UI. English falls back to
 * [DaemonLogPaths] (same source as the web About diagnostics API); Hebrew
 * (and other locales) can override per field via `logs_desc_<key>_<field>`.
 */
object DaemonLogGuidance {

    data class Text(
        val name: String,
        val component: String,
        val sendWhen: String,
    )

    fun textForKey(context: Context, key: String): Text? {
        val base = DaemonLogPaths.descriptionFor(key) ?: return null
        return Text(
            name = localizedField(context, key, "name", base.name),
            component = localizedField(context, key, "component", base.component),
            sendWhen = localizedField(context, key, "send_when", base.sendWhen),
        )
    }

    fun sectionHint(context: Context): String = context.getString(R.string.logs_section_hint)

    fun whenToSendPrefix(context: Context): String = context.getString(R.string.logs_when_to_send_prefix)

    private fun localizedField(
        context: Context,
        key: String,
        field: String,
        fallback: String,
    ): String {
        val resName = "logs_desc_${key}_$field"
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        return if (id != 0) context.getString(id) else fallback
    }
}