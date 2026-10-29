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
  var connection:HttpURLConnection?=null
  return try{
   // The public Kyiv Digital API exposes the *current* city alert state.
   connection=(URL("https://kyiv.digital/open-api/air-alert/state").openConnection() as HttpURLConnection)
   connection.connectTimeout=8000
   connection.readTimeout=8000
   connection.useCaches=false
   connection.setRequestProperty("Accept","application/json")
   if(connection.responseCode!=200)return null
   val root=JSONObject(connection.inputStream.bufferedReader().use{it.readText()})
   val possibilities=listOfNotNull(root,root.optJSONObject("current_state"),root.optJSONObject("current"),root.optJSONObject("data"),root.optJSONObject("result"))
   for(obj in possibilities){
    val raw=obj.opt("state")
    val inner=if(raw is JSONObject)raw.opt("state") else raw
    when(inner?.toString()?.trim()?.lowercase()){
     "1","true" -> return true
     "0","false" -> return false
    }
   }
   null
  }catch(_:Exception){null}finally{connection?.disconnect()}
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
