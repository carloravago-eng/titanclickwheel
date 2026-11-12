package com.orbit.clickwheel.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.orbit.clickwheel.playback.MediaHub

class Notif(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
    val clearable: Boolean,
)

/** Receives the phone's notifications so the sidebar can show them and badge apps. */
class NotifyService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotifyStore.attach(this)
        refresh()
        MediaHub.start(this, ComponentName(this, NotifyService::class.java))
    }

    override fun onListenerDisconnected() {
        NotifyStore.detach(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            null
        }
        NotifyStore.update(packageName, active.orEmpty())
    }
}

/** Current notifications and per-app counts. Main thread only. */
object NotifyStore {
    private var service: NotifyService? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    var items: List<Notif> = emptyList()
        private set
    private var counts: Map<String, Int> = emptyMap()

    val connected: Boolean get() = service != null

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, NotifyService::class.java)) == true

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    internal fun attach(s: NotifyService) {
        service = s
    }

    internal fun detach(s: NotifyService) {
        if (service === s) service = null
        items = emptyList()
        counts = emptyMap()
        listeners.toList().forEach { it() }
    }

    internal fun update(self: String, active: Array<out StatusBarNotification>) {
        items = active.asSequence()
            .filter { it.packageName != self }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                Notif(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = title.ifBlank { text },
                    text = if (title.isBlank()) "" else text,
                    time = sbn.postTime,
                    intent = sbn.notification.contentIntent,
                    autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
                    clearable = sbn.isClearable,
                )
            }
            .sortedByDescending { it.time }
            .toList()
        counts = items.groupingBy { it.packageName }.eachCount()
        listeners.toList().forEach { it() }
    }

    fun count(pkg: String): Int = counts[pkg] ?: 0
    val total: Int get() = items.size

    fun open(context: Context, n: Notif): Boolean {
        val pi = n.intent ?: return false
        return try {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(context, 0, null, null, null, null, opts.toBundle())
            if (n.autoCancel) dismiss(n)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dismiss(n: Notif) {
        try {
            service?.cancelNotification(n.key)
        } catch (_: Exception) {
        }
    }

    fun clearAll() {
        try {
            service?.cancelAllNotifications()
        } catch (_: Exception) {
        }
    }
}
