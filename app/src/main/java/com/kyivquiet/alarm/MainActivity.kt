package com.kyivquiet.alarm
import android.Manifest
import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.widget.*
import java.time.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),2)
  render()
 }
 private fun render(){
  val p=getSharedPreferences("alarm",MODE_PRIVATE)
  val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32)}
  val title=TextView(this).apply{text="⏰ Тихий будильник · Київ";textSize=24f}
  val about=TextView(this).apply{text="При тревоге в Киеве сигнал откладывается до отбоя. Если нет связи — прозвучит вовремя. Экстренные уведомления не отключаются.";textSize=16f}
  val picker=TimePicker(this).apply{setIs24HourView(true);hour=p.getInt("hour",7);minute=p.getInt("minute",0)}
  val status=TextView(this).apply{text=if(p.getBoolean("enabled",false)) "Будильник включён: %02d:%02d".format(p.getInt("hour",7),p.getInt("minute",0)) else "Будильник выключен";textSize=18f}
  val enable=Button(this).apply{text="Включить ежедневно";setOnClickListener{
   val am=getSystemService(AlarmManager::class.java)
   if(Build.VERSION.SDK_INT>=31 && !am.canScheduleExactAlarms()){
    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));Toast.makeText(this@MainActivity,"Разрешите точные будильники, затем нажмите включить",Toast.LENGTH_LONG).show()
   }else{
    p.edit().putInt("hour",picker.hour).putInt("minute",picker.minute).putBoolean("enabled",true).putBoolean("pending",false).apply()
    if(AlarmPlan.scheduleNext(this@MainActivity)) render() else Toast.makeText(this@MainActivity,"Не удалось установить будильник",Toast.LENGTH_LONG).show()
   }
  }}
  val disable=Button(this).apply{text="Выключить";setOnClickListener{p.edit().putBoolean("enabled",false).putBoolean("pending",false).apply();AlarmPlan.cancel(this@MainActivity);stopService(Intent(this@MainActivity,AlarmService::class.java));render()}}
  val stop=Button(this).apply{text="Остановить звонок";setOnClickListener{sendBroadcast(Intent(this@MainActivity,AlarmReceiver::class.java).setAction("STOP"))}}
  listOf(title,about,picker,status,enable,disable,stop).forEach{layout.addView(it)}
  setContentView(ScrollView(this).apply{addView(layout)})
 }
}
object AlarmPlan{
 private fun pi(c:Context)=PendingIntent.getBroadcast(c,10,Intent(c,AlarmReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 fun scheduleAt(c:Context,millis:Long):Boolean=try{
  val a=c.getSystemService(AlarmManager::class.java)
  if(Build.VERSION.SDK_INT>=31 && !a.canScheduleExactAlarms()) false else {a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,millis,pi(c));true}
 }catch(_:Exception){false}
 fun scheduleNext(c:Context):Boolean{
  val p=c.getSharedPreferences("alarm",Context.MODE_PRIVATE);if(!p.getBoolean("enabled",false))return false
  val now=ZonedDateTime.now(ZoneId.of("Europe/Kyiv"))
  var next=now.withHour(p.getInt("hour",7)).withMinute(p.getInt("minute",0)).withSecond(0).withNano(0)
  if(!next.isAfter(now))next=next.plusDays(1)
  return scheduleAt(c,next.toInstant().toEpochMilli())
 }
 fun cancel(c:Context){c.getSystemService(AlarmManager::class.java).cancel(pi(c))}
}
class AlarmReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent?){
  if(i?.action=="STOP"){c.getSharedPreferences("alarm",Context.MODE_PRIVATE).edit().putBoolean("pending",false).apply();c.stopService(Intent(c,AlarmService::class.java));AlarmPlan.scheduleNext(c);return}
  if(!c.getSharedPreferences("alarm",Context.MODE_PRIVATE).getBoolean("enabled",false))return
  try{c.startForegroundService(Intent(c,AlarmService::class.java).setAction("CHECK"))}catch(_:Exception){AlarmPlan.scheduleAt(c,System.currentTimeMillis()+60000)}
 }
}
class BootReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent?){
  val p=c.getSharedPreferences("alarm",Context.MODE_PRIVATE)
  if(p.getBoolean("enabled",false)){
   if(p.getBoolean("pending",false))AlarmPlan.scheduleAt(c,System.currentTimeMillis()+60000)
   else AlarmPlan.scheduleNext(c)
  }
 }
}
