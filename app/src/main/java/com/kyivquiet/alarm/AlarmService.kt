package com.kyivquiet.alarm

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import java.util.concurrent.Executors

/**
 * Brief foreground check when alarm fires. If alert is active, arm the next exact
 * check for one minute later and stop this service. No continual background polling
 * during the whole night. Android may delay checks in certain power modes.
 */
class AlarmService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallbackTone: ToneGenerator? = null
    private var wake: PowerManager.WakeLock? = null
    private var checking = false
    private var ringing = false
    private var dead = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            AlarmConfig.CHANNEL_ID, "Тихий будильник", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Проверка статуса и сигнал будильника"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun intent(action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, request, Intent(this, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 60, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, AlarmConfig.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (ringing) "⏰ Пора вставать!" else "Тихий будильник · Киев")
            .setContentText(message)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
        if (ringing) {
            builder.addAction(android.R.drawable.ic_media_pause, "Отложить",
                intent(AlarmConfig.ACTION_SNOOZE, 63))
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
            if (ringing) "Остановить" else "Пропустить",
            intent(AlarmConfig.ACTION_STOP, 62))
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (dead || checking || ringing) return START_NOT_STICKY
        val p = AlarmConfig.prefs(this)
        if (!p.getBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        checking = true
        startForeground(AlarmConfig.NOTIF_ID, notification("Проверяем статус тревоги в Киеве…"))
        val checkLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KyivQuietAlarm:Check")
        checkLock.acquire(20000L)
        executor.execute {
            val state = KyivAlertApi.fetch()
            if (checkLock.isHeld) checkLock.release()
            main.post {
                if (dead) return@post
                checking = false
                if (!p.getBoolean("enabled", false)) {
                    stopSelf()
                    return@post
                }
                p.edit().putString("last_state",
                    if(state == true) "active" else if(state == false) "clear" else "unknown")
                    .putLong("last_checked", System.currentTimeMillis()).apply()
                if (state == true) {
                    p.edit().putBoolean("pending", true).apply()
                    val scheduled = AlarmPlan.scheduleAt(this, System.currentTimeMillis()+60_000)
                    if (scheduled) stopSelf()
                    else ring("Не удалось запланировать повторную проверку")
                } else {
                    p.edit().putBoolean("pending", false).apply()
                    AlarmPlan.scheduleNext(this)
                    ring(if(state == false) "Отбой тревоги — пора просыпаться"
                        else "Статус недоступен — обычный будильник")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ring(message: String) {
        if (dead || ringing) return
        ringing = true
        val p = AlarmConfig.prefs(this)
        p.edit().putBoolean("ringing", true).putBoolean("pending",false).apply()
        startForeground(AlarmConfig.NOTIF_ID, notification(message))
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KyivQuietAlarm:Ringing").apply {
                acquire(8 * 60 * 1000L)
            }
        val chosen=p.getString("ring_uri", "")
        val volume=(p.getInt("volume", 85).coerceIn(20,100) / 100f)
        val uris=listOfNotNull(
            chosen?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in uris) {
            try {
                player=MediaPlayer().apply {
                    setDataSource(this@AlarmService, uri)
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    isLooping=true
                    setVolume(volume, volume)
                    prepare()
                    start()
                }
                break
            } catch (_: Exception) {
                player?.release()
                player=null
            }
        }
        if(player==null) {
            try {
                fallbackTone=ToneGenerator(AudioManager.STREAM_ALARM,
                    (volume*100).toInt()).apply {
                    startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 8*60*1000)
                }
            } catch (_: Exception) {}
        }
        main.postDelayed({stopSelf()},8*60*1000L)
    }

    override fun onDestroy() {
        dead=true
        AlarmConfig.prefs(this).edit().putBoolean("ringing",false).apply()
        try { player?.stop() } catch (_: Exception) {}
        player?.release();player=null
        fallbackTone?.stopTone();fallbackTone?.release();fallbackTone=null
        if (wake?.isHeld==true) wake?.release()
        main.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
