package com.kyivquiet.alarm

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Offline, device-local notification filter. Never reads Telegram credentials or sends
 * notification data to a server. Only a new "light restored" alert can arm the alarm.
 */
internal object SvitloMessage {
    private val onMessage = Regex(
        """(?im)^\s*🟢\s*\d{1,2}:\d{2}\s+світло\s+з['’]явилося(?:\s|$)"""
    )

    fun isLightRestored(packageName: String, title: String, body: String): Boolean {
        if (packageName != "org.telegram.messenger" &&
            packageName != "org.telegram.messenger.web" &&
            packageName != "org.thunderdog.challegram") return false
        val t = title.lowercase(Locale.ROOT)
        if (!t.contains("світлобот") || !t.contains("здановськ") ||
            !t.contains("52/3")) return false
        return onMessage.containsMatchIn(body.lowercase(Locale.ROOT))
    }
}

class LightNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val p = AlarmConfig.prefs(this)
        if (!p.getBoolean("light_armed", false) ||
            p.getBoolean("light_pending", false)) return
        if (sbn.postTime < p.getLong("light_armed_at", 0L)) return

        val e = sbn.notification.extras ?: return
        val title = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_SUB_TEXT
        ).mapNotNull { e.getCharSequence(it)?.toString() }.joinToString(" | ")
        val body = (
            listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .mapNotNull { e.getCharSequence(it)?.toString() } +
                (e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.map { it.toString() } ?: emptyList())
        ).joinToString("\n")
        if (!SvitloMessage.isLightRestored(sbn.packageName, title, body)) return

        // A new alert is pending. Ignore duplicate Telegram notification updates.
        p.edit().putBoolean("light_pending", true)
            .putLong("light_detected_at", System.currentTimeMillis()).apply()
        if (!AlarmPlan.scheduleLightRetry(this, System.currentTimeMillis() + 1_500L)) {
            p.edit().putString("light_last_error", "Не удалось запланировать сигнал").apply()
        }
    }
}
