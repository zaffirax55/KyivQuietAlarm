package com.kyivquiet.alarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.Ringtone
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(10, 13, 27)
    private val panel = Color.rgb(24, 30, 53)
    private val accent = Color.rgb(128, 108, 255)
    private val mint = Color.rgb(111, 235, 204)
    private val muted = Color.rgb(158, 169, 191)
    private val white = Color.rgb(247, 249, 255)
    private val executor = Executors.newSingleThreadExecutor()
    private var ringtonePreview: Ringtone? = null
    private var builtInPreview: MediaPlayer? = null
    private var liveStatus = "Проверяем статус Киева…"
    private var statusView: TextView? = null
    private val p get() = AlarmConfig.prefs(this)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        render()
        checkAlert()
    }
    override fun onResume() { super.onResume(); if (statusView != null) { render(); checkAlert() } }
    override fun onDestroy() {
        ringtonePreview?.stop()
        try { builtInPreview?.stop() } catch (_: Exception) {}
        builtInPreview?.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun d(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    private fun bgShape(color: Int, radius: Int = 22, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = d(radius).toFloat()
            if (stroke != null) setStroke(d(1), stroke)
        }
    private fun gradient(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(48, 43, 100), Color.rgb(27, 37, 70), panel)).apply {
            cornerRadius = d(28).toFloat()
        }
    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            setTextColor(color)
            textSize = size
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horiz(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun verticalCard(): LinearLayout = column().apply {
        setPadding(d(20), d(20), d(20), d(20))
        background = bgShape(panel)
    }
    private fun spacer(parent: LinearLayout, h: Int = 14) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, d(h)))
    }
    private fun button(label: String, fill: Int = accent, ink: Int = white, radius: Int = 16): TextView =
        text(label, 15f, ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(d(16), d(15), d(16), d(15))
            background = bgShape(fill, radius)
            isClickable = true
            isFocusable = true
        }
    private fun chip(label: String, selected: Boolean): TextView =
        text(label, 13f, if(selected) white else muted, selected).apply {
            gravity = Gravity.CENTER
            setPadding(d(6), d(13), d(6), d(13))
            background = bgShape(if(selected) Color.rgb(81, 68, 168) else Color.rgb(37, 42, 65), 12,
                if(selected) accent else Color.rgb(51, 59, 85))
        }
    private fun title(parent: LinearLayout, value: String, subtitle: String? = null) {
        parent.addView(text(value, 18f, white, true))
        if(subtitle != null) {
            spacer(parent, 7)
            parent.addView(text(subtitle, 12f, muted))
        }
    }
    private fun topSpacing(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
        spacer(parent, 15)
    }

    private fun reschedule() {
        if(p.getBoolean("enabled", false)) {
            p.edit().putBoolean("pending", false).apply()
            stopService(Intent(this, AlarmService::class.java))
            AlarmPlan.cancel(this)
            if(!AlarmPlan.scheduleNext(this)) Toast.makeText(this, "Не удалось поставить будильник", Toast.LENGTH_LONG).show()
        }
    }
    private fun updateTime(h: Int, m: Int) {
        p.edit().putInt("hour",h).putInt("minute",m).apply()
        reschedule()
        render()
    }
    private fun nextText(): String {
        val t=p.getLong("next_at",0)
        if(t <= System.currentTimeMillis()) return "Время следующего звонка ещё не задано"
        val date=Instant.ofEpochMilli(t).atZone(ZoneId.of("Europe/Kyiv"))
        return "Следующий сигнал: " + date.format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale("ru")))
    }
    private fun render() {
        val root = column().apply {
            setBackgroundColor(bg)
            setPadding(d(20), d(25), d(20), d(38))
        }
        val head = horiz()
        val mark = text("◉", 32f, mint, true).apply { gravity=Gravity.CENTER; background=bgShape(Color.rgb(36,46,73),18) }
        head.addView(mark, LinearLayout.LayoutParams(d(49),d(49)))
        val headWords=column().apply { setPadding(d(13),0,0,0) }
        headWords.addView(text("ТИХО", 27f, white, true))
        headWords.addView(text("умный будильник · Киев", 12f, muted))
        head.addView(headWords)
        topSpacing(root,head)

        val hero=verticalCard().apply { background=gradient() }
        val headRow=horiz()
        headRow.addView(text("⏰   МОЁ УТРО",12f,mint,true))
        val status=p.getBoolean("enabled",false)
        val enabledText=text(if(status)"● АКТИВЕН" else "○ ВЫКЛЮЧЕН",11f,if(status)mint else muted,true).apply{gravity=Gravity.END}
        headRow.addView(enabledText,LinearLayout.LayoutParams(0,-2,1f))
        hero.addView(headRow)
        spacer(hero,22)

        val h=p.getInt("hour",7)
        val m=p.getInt("minute",0)
        val clock=text("%02d:%02d".format(h,m),64f,white,true).apply{
            gravity=Gravity.CENTER
            letterSpacing=-0.035f
            setPadding(0,d(4),0,d(6))
            background=bgShape(Color.rgb(31,35,74),19,Color.rgb(74,68,128))
            setOnClickListener {
                TimePickerDialog(this@MainActivity,{_,hour,minute->updateTime(hour,minute)},h,m,true).show()
            }
        }
        hero.addView(clock,LinearLayout.LayoutParams(-1,d(116)))
        spacer(hero,10)
        val clockHint=text("НАЖМИ, ЧТОБЫ ИЗМЕНИТЬ ЧАСЫ И МИНУТЫ",11f,muted,true).apply{gravity=Gravity.CENTER}
        hero.addView(clockHint)
        spacer(hero,13)
        val adjustRow=horiz()
        listOf(-5,-1,1,5).forEachIndexed { index,change ->
            val control=chip(if(change>0) "+${change} мин" else "${change} мин", false)
            adjustRow.addView(control,LinearLayout.LayoutParams(0,-2,1f).apply {
                if(index<3)rightMargin=d(6)
            })
            control.setOnClickListener {
                val total=(h*60+m+change+1440)%1440
                updateTime(total/60,total%60)
            }
        }
        hero.addView(adjustRow)
        spacer(hero,18)
        val next=text(if(status) nextText() else "Выбери время и включи будильник",13f,white).apply { gravity=Gravity.CENTER }
        hero.addView(next)
        spacer(hero,20)
        val power=button(if(status)"Выключить будильник" else "Включить будильник",if(status)Color.rgb(65,72,104) else accent)
        power.setOnClickListener {
            if(status) {
                p.edit().putBoolean("enabled",false).putBoolean("pending",false).apply()
                AlarmPlan.cancel(this)
                stopService(Intent(this,AlarmService::class.java))
                render()
            } else {
                if(!AlarmPlan.hasPermission(this)) {
                    Toast.makeText(this,"Разреши точные будильники, затем нажми включить",Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } else {
                    p.edit().putBoolean("enabled",true).putBoolean("pending",false).apply()
                    if(!AlarmPlan.scheduleNext(this)) {
                        p.edit().putBoolean("enabled",false).apply()
                        Toast.makeText(this,"Не удалось установить будильник",Toast.LENGTH_LONG).show()
                    } else {
                        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),200)
                    }
                    render()
                }
            }
        }
        hero.addView(power)
        topSpacing(root,hero)

        val daysCard=verticalCard()
        title(daysCard,"Дни недели","Нажми на дни, в которые нужно просыпаться")
        spacer(daysCard,16)
        val daysRow=horiz()
        val names=arrayOf("ПН","ВТ","СР","ЧТ","ПТ","СБ","ВС")
        val mask=p.getInt("days",127)
        names.forEachIndexed { index,label ->
            val chosen=mask and (1 shl index)!=0
            val day=chip(label,chosen)
            daysRow.addView(day,LinearLayout.LayoutParams(0,-2,1f).apply{
                if(index<6)rightMargin=d(5)
            })
            day.setOnClickListener {
                val nextMask=mask xor (1 shl index)
                if(nextMask==0) Toast.makeText(this,"Оставь хотя бы один день",Toast.LENGTH_SHORT).show()
                else {p.edit().putInt("days",nextMask).apply();reschedule();render()}
            }
        }
        daysCard.addView(daysRow)
        topSpacing(root,daysCard)

        val tuneCard=verticalCard()
        title(tuneCard,"Звук пробуждения","4 авторские мелодии + звуки телефона")
        spacer(tuneCard,14)
        val tuneRow=horiz()
        val tuneWords=column()
        tuneWords.addView(text("♫  "+p.getString("ring_name","Системный будильник"),15f,white,true))
        spacer(tuneWords,6)
        tuneWords.addView(text("Настроить мелодию",12f,mint))
        tuneRow.addView(tuneWords,LinearLayout.LayoutParams(0,-2,1f))
        val choose=button("Выбрать",Color.rgb(63,70,114))
        choose.setOnClickListener { showSoundMenu() }
        tuneRow.addView(choose)
        tuneCard.addView(tuneRow)
        spacer(tuneCard,15)
        val preview=button("▶  Послушать / остановить",Color.rgb(35,64,77),mint)
        preview.setOnClickListener { previewRingtone() }
        tuneCard.addView(preview)
        spacer(tuneCard,19)
        val volumeLabel=text("Громкость мелодии · ${p.getInt("volume",85)}%",14f,white,true)
        tuneCard.addView(volumeLabel)
        val slider=SeekBar(this).apply{max=100;progress=p.getInt("volume",85)}
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,fromUser:Boolean){
                if(fromUser){
                    val value=v.coerceAtLeast(20)
                    p.edit().putInt("volume",value).apply()
                    volumeLabel.text="Громкость мелодии · ${value}%"
                }
            }
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })
        tuneCard.addView(slider)
        tuneCard.addView(text("Громкость телефона для будильников тоже должна быть включена.",11f,muted))
        topSpacing(root,tuneCard)

        val snoozeCard=verticalCard()
        title(snoozeCard,"Отложить сигнал","Когда будильник прозвонит")
        spacer(snoozeCard,15)
        val snoozeRow=horiz()
        listOf(5,10,15).forEachIndexed { idx,mins->
            val b=chip("${mins} мин",p.getInt("snooze",10)==mins)
            snoozeRow.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{if(idx<2)rightMargin=d(8)})
            b.setOnClickListener{p.edit().putInt("snooze",mins).apply();render()}
        }
        snoozeCard.addView(snoozeRow)
        spacer(snoozeCard,18)
        val vibrateRow=horiz()
        vibrateRow.addView(text("Вибрация",15f,white,true),LinearLayout.LayoutParams(0,-2,1f))
        val vibrateSwitch=Switch(this).apply{
            isChecked=p.getBoolean("vibration",true)
            setOnCheckedChangeListener{_,checked-> p.edit().putBoolean("vibration",checked).apply()}
        }
        vibrateRow.addView(vibrateSwitch)
        snoozeCard.addView(vibrateRow)
        topSpacing(root,snoozeCard)

        val stateCard=verticalCard()
        title(stateCard,"Тревога в Киеве","Официальный статус · Київ Цифровий")
        spacer(stateCard,15)
        statusView=text(liveStatus,15f,mint,true)
        stateCard.addView(statusView)
        spacer(stateCard,12)
        val lastState = p.getString("last_state", "unknown")
        val isPending = p.getBoolean("pending", false)
        val lastMessage = when {
            isPending -> "⏳ Ожидаем подтверждённого отбоя — звонок отложен"
            lastState == "active" -> "Последняя проверка: воздушная тревога"
            lastState == "clear" -> "Последняя проверка: отбой"
            else -> "Последняя проверка: нет достоверных данных"
        }
        stateCard.addView(text(lastMessage, 12f, if (isPending) mint else muted))
        spacer(stateCard,18)
        val strictRow=horiz()
        val strictLabels=column()
        strictLabels.addView(text("Ждать подтверждённого отбоя",14f,white,true))
        spacer(strictLabels,6)
        strictLabels.addView(text("При ошибке связи не звонить, а проверять снова",12f,muted))
        strictRow.addView(strictLabels,LinearLayout.LayoutParams(0,-2,1f))
        val strictSwitch=Switch(this).apply {
            isChecked=AlarmConfig.waitForConfirmedClear(this@MainActivity)
            setOnCheckedChangeListener { _,checked ->
                p.edit().putBoolean("wait_confirmed_clear",checked).apply()
                render()
            }
        }
        strictRow.addView(strictSwitch)
        stateCard.addView(strictRow)
        spacer(stateCard,10)
        stateCard.addView(text(
            "Если интернет недоступен, в этом режиме будильник может вообще не прозвонить. " +
            "Для обязательного пробуждения оставь дополнительный системный будильник.",
            12f,Color.rgb(250,193,119)))
        spacer(stateCard,15)
        val check=button("↻  Проверить сейчас",Color.rgb(47,59,90),white)
        check.setOnClickListener{checkAlert()}
        stateCard.addView(check)
        spacer(stateCard,13)
        stateCard.addView(text(
            if (AlarmConfig.waitForConfirmedClear(this))
                "При тревоге и недоступном API ждём. Звоним только когда город подтвердил отбой."
            else
                "При тревоге ждём отбоя. Если API недоступен — звоним по расписанию.",
            12f,muted))
        topSpacing(root,stateCard)

        val help=verticalCard().apply{background=bgShape(Color.rgb(24,41,48),19)}
        help.addView(text("🛡  Важно для безопасности",14f,mint,true))
        spacer(help,8)
        help.addView(text("Это только обычный будильник. Он НЕ отключает сирены, официальные push-оповещения и предупреждения Android. Проверь разрешения будильника и батареи в HyperOS.",12f,white))
        root.addView(help)

        setContentView(ScrollView(this).apply{isFillViewport=true; addView(root)})
    }
    private fun checkAlert(){
        liveStatus="⏳  Проверяем…"
        statusView?.text=liveStatus
        executor.execute {
            val result=KyivAlertApi.fetchDetailed()
            runOnUiThread{
                if (isFinishing || isDestroyed) return@runOnUiThread
                liveStatus=when(result.active){
                    true -> "🔴  Сейчас воздушная тревога"
                    false -> "🟢  Сейчас отбой / тревоги нет"
                    null -> "⚪  Статус неизвестен: " + result.detail
                }
                statusView?.text=liveStatus
            }
        }
    }
    private fun showSoundMenu(){
        val names=arrayOf("🌅 Рассвет · нежные колокольчики", "🌙 Мечта · мягкий звук",
            "⚡ Импульс · бодрый сигнал", "✨ Космос · объёмная мелодия",
            "♫ Другие мелодии телефона…")
        val ids=arrayOf("sunrise","dream","pulse","cosmos")
        AlertDialog.Builder(this)
            .setTitle("Мелодия пробуждения")
            .setItems(names){_,index->
                ringtonePreview?.stop()
                try{builtInPreview?.stop()}catch(_:Exception){}
                builtInPreview?.release();builtInPreview=null
                if(index<4){
                    p.edit().putString("built_in",ids[index])
                        .putString("ring_name",names[index].substringAfter(" · ").trim()).apply()
                    render()
                }else{
                    p.edit().remove("built_in").apply()
                    pickRingtone()
                }
            }.show()
    }
    private fun pickRingtone(){
        val intent=Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply{
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,"Мелодия будильника")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,true)
            val uri=p.getString("ring_uri","")?.takeIf{it.isNotBlank()}?.let{Uri.parse(it)}
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,uri)
        }
        try{startActivityForResult(intent,700)}
        catch(_:Exception){Toast.makeText(this,"Не удалось открыть выбор мелодии",Toast.LENGTH_LONG).show()}
    }
    @Deprecated("Android ringtone picker uses onActivityResult")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==700 && resultCode==RESULT_OK){
            ringtonePreview?.stop()
            val uri=data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if(uri != null){
                val selected=try{RingtoneManager.getRingtone(this,uri)?.getTitle(this)}catch(_:Exception){null}
                p.edit().remove("built_in").putString("ring_uri",uri.toString()).putString("ring_name",selected?:"Выбранная мелодия").apply()
            }else{
                p.edit().remove("built_in").remove("ring_uri").putString("ring_name","Системный будильник").apply()
            }
            render()
        }
    }
    private fun previewRingtone(){
        try {
            if(builtInPreview?.isPlaying == true){
                builtInPreview?.stop();builtInPreview?.release();builtInPreview=null;return
            }
            if(ringtonePreview?.isPlaying == true){ringtonePreview?.stop();return}
            val chosen=p.getString("built_in","")
            val raw=when(chosen){
                "sunrise"->R.raw.sunrise
                "dream"->R.raw.dream
                "pulse"->R.raw.pulse
                "cosmos"->R.raw.cosmos
                else->0
            }
            if(raw!=0){
                builtInPreview=MediaPlayer().apply{
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(this@MainActivity,Uri.parse("android.resource://$packageName/$raw"))
                    isLooping=true
                    val v=p.getInt("volume",85).coerceIn(20,100)/100f
                    setVolume(v,v)
                    prepare();start()
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    try{builtInPreview?.stop()}catch(_:Exception){}
                    builtInPreview?.release();builtInPreview=null
                },6500)
                return
            }
            val saved=p.getString("ring_uri","")
            val uri=if(saved.isNullOrBlank())RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(saved)
            ringtonePreview=RingtoneManager.getRingtone(this,uri)
                ?: RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            ringtonePreview?.play()
            Handler(Looper.getMainLooper()).postDelayed({ringtonePreview?.stop()},7000)
        }catch(_:Exception){Toast.makeText(this,"Не удалось проиграть мелодию",Toast.LENGTH_LONG).show()}
    }
}
