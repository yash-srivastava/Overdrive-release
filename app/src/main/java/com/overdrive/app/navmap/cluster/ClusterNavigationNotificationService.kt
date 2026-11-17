package com.overdrive.app.navmap.cluster

import android.app.Notification
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.HashMap

/**
 * Optional Maps/Waze input; Android's Notification access permission is required.
 * Unknown or non-guidance notifications leave no invented arrows on the driver display.
 */
class ClusterNavigationNotificationService : NotificationListenerService() {
    private val activeKeys = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { activeNotifications?.forEach(::onNotificationPosted) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val item = notification ?: return
        if (!ClusterGuidanceClient.enabled()) return
        val source = source(item.packageName) ?: return
        val value = item.notification ?: return
        // Nav notifications are ongoing. A saved-place suggestion from Maps is not guidance.
        if (value.flags and Notification.FLAG_ONGOING_EVENT == 0 && value.category != "navigation") {
            clearIfOwned(source, item.key)
            return
        }
        val extras = value.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() } ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val iconName = try {
            val icon = value.smallIcon
            if (icon?.type == Icon.TYPE_RESOURCE)
                createPackageContext(item.packageName, 0).resources.getResourceEntryName(icon.resId)
            else ""
        } catch (_: Exception) { "" }
        val frame = ClusterNavigationParser.parse(source, title, text, subText, iconName) ?: run {
            clearIfOwned(source, item.key)
            return
        }
        activeKeys[source] = item.key
        ClusterGuidanceClient.publish(frame)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        val item = notification ?: return
        val source = source(item.packageName) ?: return
        clearIfOwned(source, item.key)
    }

    override fun onListenerDisconnected() {
        clearOwned()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clearOwned()
        super.onDestroy()
    }

    private fun clearOwned() {
        activeKeys.keys.toList().forEach(ClusterGuidanceClient::clear)
        activeKeys.clear()
    }

    private fun clearIfOwned(source: String, key: String) {
        if (activeKeys[source] != key) return
        activeKeys.remove(source)
        ClusterGuidanceClient.clear(source)
    }

    private fun source(pkg: String): String? = when (pkg) {
        "com.google.android.apps.maps", "app.revanced.android.apps.maps" -> "maps"
        "com.waze" -> "waze"
        else -> null
    }
}
