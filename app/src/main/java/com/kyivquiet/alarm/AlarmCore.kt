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
    const val ACTION_LIGHT_RETRY = "com.kyivquiet.alarm.LIGHT_RETRY"
    const val CHANNEL_ID = "quiet_alarm_v9"
    const val NOTIF_ID = 912
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun waitForConfirmedClear(c: Context) = prefs(c).getBoolean("wait_confirmed_clear", true)
}

object AlarmPlan {
    private const val REQUEST_ID = 42
    private const val LIGHT_REQUEST_ID = 43

    private fun triggerIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, REQUEST_ID,
            Intent(c, AlarmReceiver::class.java).setAction(AlarmConfig.ACTION_TRIGGER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun lightIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, LIGHT_REQUEST_ID,
            Intent(c, AlarmReceiver::class.java).setAction(AlarmConfig.ACTION_LIGHT_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Separate alarm channel: must never overwrite a scheduled time-based alarm. */
    fun scheduleLightRetry(c: Context, at: Long): Boolean = try {
        val am = c.getSystemService(AlarmManager::class.java)
        if (hasPermission(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, lightIntent(c))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, lightIntent(c))
        }
        AlarmConfig.prefs(c).edit().putLong("light_next_at", at).apply()
        true
    } catch (_: Exception) { false }

    fun cancelLightRetry(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(lightIntent(c))
        AlarmConfig.prefs(c).edit().remove("light_next_at").apply()
    }

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

    fun scheduleRetry(c: Context, at: Long): Boolean = try {
        val am = c.getSystemService(AlarmManager::class.java)
        if (hasPermission(c)) scheduleAt(c, at)
        else {
            // Inexact fallback if Android revoked exact-alarm permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, triggerIntent(c))
            AlarmConfig.prefs(c).edit().putLong("next_at", at).apply()
            true
        }
    } catch (_: Exception) { false }

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
                p.edit().putBoolean("pending", false)
                    .putBoolean("light_pending", false)
                    .putBoolean("light_armed", false).apply()
                AlarmPlan.cancelLightRetry(c)
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
            AlarmConfig.ACTION_LIGHT_RETRY -> {
                if (!p.getBoolean("light_armed", false) ||
                    !p.getBoolean("light_pending", false)) return
                try {
                    c.startForegroundService(
                        Intent(c, AlarmService::class.java).setAction(AlarmConfig.ACTION_LIGHT_RETRY)
                    )
                } catch (_: Exception) {
                    AlarmPlan.scheduleLightRetry(c, System.currentTimeMillis() + 60_000L)
                }
                return
            }
            AlarmConfig.ACTION_TRIGGER -> {
                if (!p.getBoolean("enabled", false)) return
                try {
                    c.startForegroundService(Intent(c, AlarmService::class.java).setAction("CHECK"))
                } catch (_: Exception) {
                    p.edit().putBoolean("pending", true).apply()
                    AlarmPlan.scheduleRetry(c, System.currentTimeMillis() + 60000L)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        val p = AlarmConfig.prefs(c)
        if (p.getBoolean("light_armed", false) && p.getBoolean("light_pending", false))
            AlarmPlan.scheduleLightRetry(c, System.currentTimeMillis() + 60_000L)
        if (!p.getBoolean("enabled", false)) return
        if (p.getBoolean("pending", false))
            AlarmPlan.scheduleRetry(c, System.currentTimeMillis() + 60_000L)
        else AlarmPlan.scheduleNext(c)
    }
}
