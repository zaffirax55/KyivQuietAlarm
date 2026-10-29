package com.kyivquiet.alarm
import android.app.*
import android.content.*
import android.media.*
import android.os.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AlarmService:Service(){
 private val executor=Executors.newSingleThreadExecutor()
 private val handler=Handler(Looper.getMainLooper())
 private var player:MediaPlayer?=null
 private var tone:ToneGenerator?=null
 private var lock:PowerManager.WakeLock?=null
 private var ringing=false
 override fun onBind(i:Intent?)=null
 override fun onCreate(){super.onCreate()
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("alarm","Сигнал будильника",NotificationManager.IMPORTANCE_HIGH))
 }
 private fun notification(msg:String):Notification{
  val stop=PendingIntent.getBroadcast(this,20,Intent(this,AlarmReceiver::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val open=PendingIntent.getActivity(this,21,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return Notification.Builder(this,"alarm").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
   .setContentTitle("Тихий будильник · Київ").setContentText(msg).setContentIntent(open)
   .setOngoing(true).setCategory(Notification.CATEGORY_ALARM).addAction(android.R.drawable.ic_media_pause,"Остановить",stop).build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(ringing)return START_NOT_STICKY
  val p=getSharedPreferences("alarm",MODE_PRIVATE)
  if(!p.getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY}
  startForeground(1,notification("Проверка статуса тревоги…"))
  executor.execute{
   val active=readState()
   handler.post{
    if(!p.getBoolean("enabled",false)){stopSelf();return@post}
    if(active==true){
     p.edit().putBoolean("pending",true).apply()
     if(AlarmPlan.scheduleAt(this,System.currentTimeMillis()+60000))stopSelf()
     else{p.edit().putBoolean("pending",false).apply();AlarmPlan.scheduleNext(this);ring("Ошибка повторной проверки — будильник звонит")}
    }else{
     p.edit().putBoolean("pending",false).apply()
     AlarmPlan.scheduleNext(this)
     ring(if(active==false)"Отбой — пора вставать!" else "Статус недоступен — сигнал вовремя")
    }
   }
  }
  return START_NOT_STICKY
 }
 private fun readState():Boolean?{
  var c:HttpURLConnection?=null
  return try{
   c=(URL("https://data.kyivcity.gov.ua/api/action/datastore_search?resource_id=e1216fe6-7cbd-41ad-b478-85983a2e2669&limit=1").openConnection() as HttpURLConnection)
   c.connectTimeout=6000;c.readTimeout=6000;c.useCaches=false;c.setRequestProperty("Accept","application/json")
   if(c.responseCode!=200)return null
   val json=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
   if(!json.optBoolean("success"))return null
   val arr=json.optJSONObject("result")?.optJSONArray("records")?:return null
   val rec=arr.optJSONObject(0)?:return null
   when(rec.opt("state")?.toString()?.trim()?.lowercase()){
    "1","true"->true
    "0","false"->false
    else->null
   }
  }catch(_:Exception){null}finally{c?.disconnect()}
 }
 private fun ring(message:String){
  if(ringing)return
  ringing=true
  startForeground(1,notification(message))
  lock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"KyivQuietAlarm:Ring").apply{acquire(600000L)}
  try{
   val uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?:RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
   player=MediaPlayer().apply{
    setDataSource(this@AlarmService,uri)
    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    isLooping=true;prepare();start()
   }
  }catch(_:Exception){
   player?.release();player=null
   tone=ToneGenerator(AudioManager.STREAM_ALARM,95).apply{startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)}
  }
  handler.postDelayed({stopSelf()},600000L)
 }
 override fun onDestroy(){
  try{player?.stop()}catch(_:Exception){}
  player?.release();tone?.stopTone();tone?.release()
  if(lock?.isHeld==true)lock?.release()
  handler.removeCallbacksAndMessages(null);executor.shutdownNow()
  super.onDestroy()
 }
}
