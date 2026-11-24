package com.overdrive.app.logging

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.overdrive.app.R

/**
 * Localized daemon-log triage copy for native UI. Default strings mirror
 * [DaemonLogPaths] for lint/default-locale resolution; Hebrew overrides in
 * values-he. If a resource is missing at runtime, falls back to [DaemonLogPaths].
 */
object DaemonLogGuidance {

    data class Text(
        val component: String,
        val sendWhen: String,
    )

    fun textForKey(context: Context, key: String): Text? {
        val base = DaemonLogPaths.descriptionFor(key) ?: return null
        return Text(
            component = localizedField(context, key, "component", base.component),
            sendWhen = localizedField(context, key, "send_when", base.sendWhen),
        )
    }

    fun whenToSendPrefix(context: Context): String = context.getString(R.string.logs_when_to_send_prefix)

    private fun localizedField(
        context: Context,
        key: String,
        field: String,
        fallback: String,
    ): String {
        val id = descStringResId(key, field)
        if (id == 0) return fallback
        return try {
            context.getString(id)
        } catch (_: Resources.NotFoundException) {
            fallback
        }
    }

    @StringRes
    private fun descStringResId(key: String, field: String): Int = when (key to field) {
        "camera" to "component" -> R.string.logs_desc_camera_component
        "camera" to "send_when" -> R.string.logs_desc_camera_send_when
        "accsentry" to "component" -> R.string.logs_desc_accsentry_component
        "accsentry" to "send_when" -> R.string.logs_desc_accsentry_send_when
        "sentry" to "component" -> R.string.logs_desc_sentry_component
        "sentry" to "send_when" -> R.string.logs_desc_sentry_send_when
        "telegram" to "component" -> R.string.logs_desc_telegram_component
        "telegram" to "send_when" -> R.string.logs_desc_telegram_send_when
        "cloudflared" to "component" -> R.string.logs_desc_cloudflared_component
        "cloudflared" to "send_when" -> R.string.logs_desc_cloudflared_send_when
        "zrok" to "component" -> R.string.logs_desc_zrok_component
        "zrok" to "send_when" -> R.string.logs_desc_zrok_send_when
        "tailscale" to "component" -> R.string.logs_desc_tailscale_component
        "tailscale" to "send_when" -> R.string.logs_desc_tailscale_send_when
        "wireguard" to "component" -> R.string.logs_desc_wireguard_component
        "wireguard" to "send_when" -> R.string.logs_desc_wireguard_send_when
        "singbox" to "component" -> R.string.logs_desc_singbox_component
        "singbox" to "send_when" -> R.string.logs_desc_singbox_send_when
        else -> 0
    }
}
