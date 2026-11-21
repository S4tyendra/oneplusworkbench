package dev.satya.workbench.data

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** One notification as the bench shows it. */
data class Notif(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val smallIcon: Icon?,
    val largeIcon: Icon?,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
)

/** Live notifications, fed by [dev.satya.workbench.service.NotifListener]. */
object Notifs {
    val items = MutableStateFlow<List<Notif>>(emptyList())
    /** Unread count per package (what the strip badges show). */
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val connected = MutableStateFlow(false)

    /** Set by the listener while it's bound; used to dismiss a notification once it has been opened. */
    @Volatile var dismiss: ((String) -> Unit)? = null

    private val IGNORED = setOf("android", "com.android.systemui", "dev.satya.workbench")

    fun publish(all: Array<StatusBarNotification>) {
        val out = all.asSequence()
            .filter { it.packageName !in IGNORED && !it.isOngoing && it.isClearable }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { sbn ->
                val n = sbn.notification
                val ex = n.extras
                Notif(
                    key = sbn.key,
                    pkg = sbn.packageName,
                    title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString().orEmpty(),
                    text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                    time = sbn.postTime,
                    smallIcon = n.smallIcon,
                    largeIcon = n.getLargeIcon(),
                    intent = n.contentIntent,
                    autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
                )
            }
            .sortedByDescending { it.time }
            .toList()
        items.value = out
        counts.value = out.groupingBy { it.pkg }.eachCount()
    }

    fun component(ctx: Context) = ComponentName(ctx, "dev.satya.workbench.service.NotifListener")
}
