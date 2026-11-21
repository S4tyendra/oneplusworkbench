package dev.satya.workbench.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.satya.workbench.data.Notifs

class NotifListener : NotificationListenerService() {
    private fun refresh() {
        runCatching { activeNotifications }.getOrNull()?.let(Notifs::publish)
    }

    override fun onListenerConnected() {
        Notifs.connected.value = true
        Notifs.dismiss = { key -> runCatching { cancelNotification(key) } }
        refresh()
    }

    override fun onListenerDisconnected() {
        Notifs.connected.value = false
        Notifs.dismiss = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
}
