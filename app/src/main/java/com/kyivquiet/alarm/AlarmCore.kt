package com.kyivquiet.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.ZoneId

object AlarmConfig {
    const val PREFS = "alarm"
    const val ACTION_TRIGGER = "com.kyivquiet.alarm.TRIGGER"
    const val ACTION_STOP = "com.kyivquiet.alarm.STOP"
    const val ACTION_SNOOZE = "com.kyivquiet.alarm.SNOOZE"
    const val CHANNEL_ID = "quiet_alarm_v7"
    const val NOTIF_ID = 912
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object AlarmPlan {
    private const val REQUEST_ID = 42

    private fun triggerIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, REQUEST_ID,
            Intent(c, AlarmReceiver::class.java).setAction(AlarmConfig.ACTION_TRIGGER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun hasPermission(c: Context): Boolean {
        val am = c.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Exact user-facing alarm, even during device idle where system permits it. */
    fun scheduleAt(c: Context, at: Long): Boolean = try {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!hasPermission(c)) false
        else {
            val view = PendingIntent.getActivity(
                c, 55, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, view), triggerIntent(c))
            AlarmConfig.prefs(c).edit().putLong("next_at", at).apply()
            true
        }
    } catch (_: Exception) { false }

    fun scheduleNext(c: Context): Boolean {
        val p = AlarmConfig.prefs(c)
        if (!p.getBoolean("enabled", false)) return false
        val mask = p.getInt("days", 127)
        if (mask == 0) return false
        val now = ZonedDateTime.now(ZoneId.of("Europe/Kyiv"))
        for (d in 0..7) {
            val date = now.toLocalDate().plusDays(d.toLong())
            val dayIdx = date.dayOfWeek.value - DayOfWeek.MONDAY.value
            if (mask and (1 shl dayIdx) == 0) continue
            val at = date.atTime(p.getInt("hour", 7), p.getInt("minute", 0))
                .atZone(ZoneId.of("Europe/Kyiv")).toInstant().toEpochMilli()
            if (at > System.currentTimeMillis() + 1000L) return scheduleAt(c, at)
        }
        return false
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(triggerIntent(c))
        AlarmConfig.prefs(c).edit().remove("next_at").apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        val p = AlarmConfig.prefs(c)
        when(intent?.action) {
            AlarmConfig.ACTION_STOP -> {
                p.edit().putBoolean("pending", false).apply()
                c.stopService(Intent(c, AlarmService::class.java))
                AlarmPlan.scheduleNext(c)
                return
            }
            AlarmConfig.ACTION_SNOOZE -> {
                p.edit().putBoolean("pending", false).apply()
                c.stopService(Intent(c, AlarmService::class.java))
                if (p.getBoolean("enabled", false))
                    AlarmPlan.scheduleAt(c, System.currentTimeMillis() + p.getInt("snooze", 10) * 60000L)
                return
            }
            AlarmConfig.ACTION_TRIGGER -> {
                if (!p.getBoolean("enabled", false)) return
                try {
                    c.startForegroundService(Intent(c, AlarmService::class.java).setAction("CHECK"))
                } catch (_: Exception) {
                    p.edit().putBoolean("pending", true).apply()
                    AlarmPlan.scheduleAt(c, System.currentTimeMillis() + 60000L)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        if (!AlarmConfig.prefs(c).getBoolean("enabled", false)) return
        if (AlarmConfig.prefs(c).getBoolean("pending", false))
            AlarmPlan.scheduleAt(c, System.currentTimeMillis() + 60000L)
        else AlarmPlan.scheduleNext(c)
    }
}
