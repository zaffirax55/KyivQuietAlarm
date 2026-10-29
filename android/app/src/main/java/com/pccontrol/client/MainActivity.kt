package com.pccontrol.client

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 14, 28)
    private val panel = Color.rgb(22, 31, 53)
    private val blue = Color.rgb(109, 105, 244)
    private val mint = Color.rgb(104, 239, 200)
    private val white = Color.rgb(244, 248, 255)
    private val grey = Color.rgb(162, 175, 196)
    private val coral = Color.rgb(252, 155, 151)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var activeIp = ""
    private var activeKey = ""
    private lateinit var ipInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var macInput: EditText
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var batteryText: TextView
    private lateinit var memoryText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var refreshButton: TextView
    private lateinit var wakeState: TextView
    private var macChoices: List<Pair<String,String>> = emptyList()
    private var wakeMonitorActive = false
    private var wakeMonitorDeadline = 0L
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        render()
        SecurePrefs.load(this)?.let { (ip, secret) ->
            ipInput.setText(ip)
            keyInput.setText(secret)
            connect(ip, secret)
        }
    }
    override fun onResume() {
        super.onResume()
        polling = true
        handler.removeCallbacks(checker)
        handler.postDelayed(checker, 15000)
    }
    override fun onPause() {
        polling = false
        handler.removeCallbacks(checker)
        handler.removeCallbacks(wakeChecker)
        wakeMonitorActive = false
        super.onPause()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private val checker = object : Runnable {
        override fun run() { if (polling) { if (activeIp.isNotBlank() && activeKey.isNotBlank() && !wakeMonitorActive) refresh(); handler.postDelayed(this, 15000) } }
    }
    private val wakeChecker = object : Runnable {
        override fun run() {
            if (!polling || !wakeMonitorActive) return
            if (System.currentTimeMillis() > wakeMonitorDeadline) {
                wakeMonitorActive = false
                wakeState.text = "Нет ответа за 60 сек. Wake-on-LAN может быть недоступен на этом ASUS."
                return
            }
            if (activeIp.isNotBlank() && activeKey.isNotBlank()) {
                val ip = activeIp
                val key = activeKey
                executor.execute {
                    try {
                        val result = PcApi(ip,key).status()
                        runOnUiThread {
                            if (!wakeMonitorActive || isFinishing) return@runOnUiThread
                            wakeMonitorActive = false
                            showDevice(result)
                            setStatus(true)
                            wakeState.text = "✓ Ноутбук отвечает — подключение восстановлено"
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            if (wakeMonitorActive && !isFinishing) handler.postDelayed(this, 4500)
                        }
                    }
                }
            } else {
                wakeMonitorActive = false
                wakeState.text = "Нужны IP и ключ подключения, чтобы проверить пробуждение"
            }
        }
    }
    private fun d(n:Int) = (resources.displayMetrics.density*n+0.5f).toInt()
    private fun shape(color:Int, radius:Int=20, stroke:Int?=null) = GradientDrawable().apply {
        setColor(color); cornerRadius=d(radius).toFloat(); if(stroke!=null)setStroke(d(1),stroke)
    }
    private fun gradient() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(50,53,115),Color.rgb(29,44,76),Color.rgb(23,34,56))).apply { cornerRadius=d(28).toFloat() }
    private fun col() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun txt(value:String, size:Float=15f, color:Int=white, bold:Boolean=false) = TextView(this).apply {
        text=value; textSize=size; setTextColor(color);includeFontPadding=false
        if(bold)typeface=Typeface.create("sans-serif",Typeface.BOLD)
    }
    private fun gap(l:LinearLayout,height:Int=14) { l.addView(View(this),LinearLayout.LayoutParams(1,d(height))) }
    private fun card() = col().apply { setPadding(d(19),d(19),d(19),d(19)); background=shape(panel,23) }
    private fun button(label:String, color:Int=blue, ink:Int=white) = txt(label,14f,ink,true).apply {
        gravity=Gravity.CENTER; background=shape(color,15); setPadding(d(15),d(15),d(15),d(15));isClickable=true;isFocusable=true
    }
    private fun cardTitle(title:String, subtitle:String="") = col().apply {
        addView(txt(title,17f,white,true))
        if(subtitle.isNotEmpty()) { gap(this,6);addView(txt(subtitle,12f,grey)) }
    }
    private fun add(root:LinearLayout,v:View) {root.addView(v,LinearLayout.LayoutParams(-1,-2));gap(root,15)}
    private fun input(hint:String, secret:Boolean=false) = EditText(this).apply {
        setTextColor(white);setHintTextColor(grey);textSize=14f;setSingleLine(true)
        setPadding(d(14),d(11),d(14),d(11));background=shape(Color.rgb(35,46,74),13)
        this.hint=hint
        inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                  else InputType.TYPE_CLASS_PHONE
    }
    private fun render() {
        val root=col().apply {setBackgroundColor(bg); setPadding(d(20),d(25),d(20),d(36))}
        val titleBar=row()
        val symbol=txt("⌘",30f,mint,true).apply {gravity=Gravity.CENTER;background=shape(Color.rgb(35,46,79),18)}
        titleBar.addView(symbol,LinearLayout.LayoutParams(d(52),d(52)))
        val head=col().apply{setPadding(d(13),0,0,0)}
        head.addView(txt("PC CONTROL",25f,white,true))
        head.addView(txt("управляй ноутбуком с телефона",12f,grey))
        titleBar.addView(head)
        add(root,titleBar)

        val hero=card().apply{background=gradient()}
        val top=row()
        top.addView(txt("●  МОЙ НОУТБУК",12f,mint,true),LinearLayout.LayoutParams(0,-2,1f))
        statusText=txt("НЕ ПОДКЛЮЧЕНО",11f,grey,true)
        top.addView(statusText)
        hero.addView(top);gap(hero,18)
        deviceText=txt("ASUS · Windows",26f,white,true)
        hero.addView(deviceText);gap(hero,6)
        hero.addView(txt("Безопасное управление по Wi-Fi",12f,grey));gap(hero,22)
        val stats=row()
        fun statistic(name:String):TextView {
            val box=col().apply{setPadding(d(9),d(13),d(9),d(13));background=shape(Color.rgb(34,45,78),14)}
            val output=txt("—",18f,white,true).apply{gravity=Gravity.CENTER}
            box.addView(output)
            box.addView(txt(name,10f,grey).apply{gravity=Gravity.CENTER})
            stats.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(3),0,d(3),0)})
            return output
        }
        batteryText=statistic("Батарея")
        memoryText=statistic("ОЗУ")
        uptimeText=statistic("Работает")
        hero.addView(stats);gap(hero,15)
        val wakeHeroButton=button("⏻   ВКЛЮЧИТЬ НОУТБУК",Color.rgb(96,87,236))
        wakeHeroButton.setOnClickListener { wakePc() }
        hero.addView(wakeHeroButton)
        add(root,hero)

        val connectCard=card()
        connectCard.addView(cardTitle("Подключение", "IP и ключ смотри в PC Control на Windows"));gap(connectCard,13)
        ipInput=input("IP ноутбука · например 192.168.1.42")
        connectCard.addView(ipInput);gap(connectCard,10)
        keyInput=input("Секретный ключ · 64 символа",true)
        connectCard.addView(keyInput);gap(connectCard,10)
        val show=row()
        show.addView(txt("Показать ключ",13f,grey),LinearLayout.LayoutParams(0,-2,1f))
        val reveal=Switch(this)
        reveal.setOnCheckedChangeListener{_,checked->
            keyInput.inputType=InputType.TYPE_CLASS_TEXT or if(checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyInput.setSelection(keyInput.text.length)
        }
        show.addView(reveal);connectCard.addView(show);gap(connectCard,8)
        val linkButton=button("Подключиться  →")
        linkButton.setOnClickListener {
            val ip=ipInput.text.toString().trim()
            val key=keyInput.text.toString().trim()
            if(!PcApi.validIp(ip)||!PcApi.validSecret(key)){
                toast("Проверь IP и ключ (64 шестнадцатеричных символа)");return@setOnClickListener
            }
            val im=getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            im.hideSoftInputFromWindow(keyInput.windowToken,0)
            connect(ip,key)
        }
        connectCard.addView(linkButton)
        add(root,connectCard)

        val wakeCard=card().apply { background=shape(Color.rgb(26,42,62),23) }
        wakeCard.addView(cardTitle("Пробуждение ноутбука", "Wake-on-LAN · только в локальной сети"));gap(wakeCard,13)
        val savedMac=getSharedPreferences("pc_wol",MODE_PRIVATE).getString("mac","")?:""
        macInput=input("MAC сетевого адаптера · AA:BB:CC:DD:EE:FF")
        macInput.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        macInput.setText(savedMac)
        wakeCard.addView(macInput);gap(wakeCard,10)
        val selectAdapter=button("Выбрать MAC с ноутбука",Color.rgb(44,61,86),mint)
        selectAdapter.setOnClickListener{ chooseMac() }
        wakeCard.addView(selectAdapter);gap(wakeCard,11)
        val wakeButton=button("⚡  Отправить Wake-on-LAN",Color.rgb(44,104,104),white)
        wakeButton.setOnClickListener { wakePc() }
        wakeCard.addView(wakeButton);gap(wakeCard,11)
        wakeState=txt("После сигнала проверим, отвечает ли Windows, в течение 60 секунд.",13f,mint)
        wakeCard.addView(wakeState);gap(wakeCard,10)
        wakeCard.addView(txt("Работает только при поддержке адаптера/BIOS и в домашней сети. Если ASUS выключен полностью, пробуждение по Wi-Fi может не сработать. Мы не меняем BIOS.",12f,grey))
        add(root,wakeCard)

        val control=card()
        control.addView(cardTitle("Быстрые действия", "Кнопки работают только после подключения"));gap(control,16)
        val volumeLabel=txt("ГРОМКОСТЬ",12f,mint,true)
        control.addView(volumeLabel);gap(control,11)
        val volumes=row()
        fun actionButton(label:String, action:String, color:Int=Color.rgb(49,61,101), confirm:Boolean=false){
            val btn=button(label,color)
            volumes.addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(4),0,d(4),0)})
            btn.setOnClickListener{perform(action,confirm)}
        }
        actionButton("−  Тише","volume_down")
        actionButton("◌  Выкл. звук","mute")
        actionButton("+  Громче","volume_up")
        control.addView(volumes);gap(control,16)
        control.addView(txt("МУЗЫКА И ВИДЕО",12f,mint,true));gap(control,10)
        val media=row()
        fun mediaButton(label:String, action:String) {
            val btn=button(label,Color.rgb(40,72,92))
            media.addView(btn, LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(3),0,d(3),0)})
            btn.setOnClickListener{perform(action,false)}
        }
        mediaButton("⏮  Назад","media_prev")
        mediaButton("⏯  Пауза","media_play_pause")
        mediaButton("⏭  Далее","media_next")
        control.addView(media);gap(control,17)
        control.addView(txt("ЭКРАН И ПИТАНИЕ",12f,mint,true));gap(control,10)
        val power1=row()
        fun powerButton(r:LinearLayout,label:String,action:String,color:Int,confirm:Boolean=true){
            val btn=button(label,color)
            r.addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(4),0,d(4),0)})
            btn.setOnClickListener{perform(action,confirm)}
        }
        powerButton(power1,"🔒  Блокировка","lock",Color.rgb(42,68,91),false)
        powerButton(power1,"☾  Сон","sleep",Color.rgb(43,57,91))
        control.addView(power1);gap(control,9)
        val power2=row()
        powerButton(power2,"↻  Перезагрузка","restart",Color.rgb(99,71,85))
        powerButton(power2,"⏻  Выключение","shutdown",Color.rgb(105,59,71))
        control.addView(power2);gap(control,10)
        val abort=button("Отменить запланированное выключение",Color.rgb(44,55,80),grey)
        abort.setOnClickListener{perform("cancel_shutdown",false)}
        control.addView(abort);gap(control,10)
        val displayAndFind=row()
        powerButton(displayAndFind,"▣  Погасить экран","display_off",Color.rgb(41,64,81),true)
        powerButton(displayAndFind,"♫  Найти ноутбук","find_pc",Color.rgb(40,73,69),false)
        control.addView(displayAndFind)
        add(root,control)

        val slides=card()
        slides.addView(cardTitle("Пульт презентации","PowerPoint и другие программы, использующие Page Up / Page Down"))
        gap(slides,15)
        val pages=row()
        fun slideButton(r:LinearLayout,label:String,action:String){
            val b=button(label,Color.rgb(43,56,101))
            r.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(4),0,d(4),0)})
            b.setOnClickListener { perform(action,false) }
        }
        slideButton(pages,"←  Назад","slide_prev")
        slideButton(pages,"Дальше  →","slide_next")
        slides.addView(pages);gap(slides,10)
        val presentation=row()
        slideButton(presentation,"▶  Начать F5","presentation_start")
        slideButton(presentation,"■  Завершить","presentation_end")
        slides.addView(presentation)
        add(root,slides)

        val foot=card().apply{background=shape(Color.rgb(21,44,54),20)}
        foot.addView(txt("🛡  БЕЗОПАСНОСТЬ",13f,mint,true));gap(foot,8)
        foot.addView(txt("Команды подписываются HMAC. Ключ хранится в Android Keystore. Не открывай порт 43821 в интернет. Для удалённого доступа используй личную VPN.",12f,white))
        add(root,foot)
        refreshButton=button("↻  Обновить состояние",Color.rgb(34,47,76),white)
        refreshButton.setOnClickListener{refresh()}
        root.addView(refreshButton)
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(root)}
        setContentView(scroll)
    }
    private fun toast(s:String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun setStatus(online:Boolean,message:String?=null){
        connected=online
        statusText.text=if(online)"●  В СЕТИ" else "○  НЕТ СВЯЗИ"
        statusText.setTextColor(if(online)mint else coral)
        if(message!=null)toast(message)
    }
    private fun connect(ip:String,key:String){
        activeIp=ip;activeKey=key
        statusText.text="ПОДКЛЮЧАЕМСЯ…"
        executor.execute{
            try{
                val result=PcApi(ip,key).status()
                runOnUiThread{
                    try{SecurePrefs.store(this,ip,key)}catch(_:Exception){toast("Не удалось сохранить ключ безопасно")}
                    showDevice(result);setStatus(true,"Подключено к ноутбуку")
                }
            }catch(e:Exception){runOnUiThread{setStatus(false,"Нет связи: ${e.message?.take(90)?:"ошибка"}")}}
        }
    }
    private fun showDevice(result:JSONObject){
        val x=result.optJSONObject("device")?:return
        val adapters=x.optJSONArray("adapters")
        if(adapters != null){
            macChoices = (0 until adapters.length()).mapNotNull { idx ->
                val ob=adapters.optJSONObject(idx) ?: return@mapNotNull null
                val mac=ob.optString("mac")
                val name=ob.optString("name","Адаптер")
                if(WakeOnLan.normalizeMac(mac)==null) null else (name to mac)
            }
            // Prefer a physical wired adapter for WOL. Keep user's existing choice.
            if(macInput.text.toString().trim().isEmpty()){
                val recommended=macChoices.firstOrNull {
                    val label=it.first.lowercase()
                    (label.contains("ethernet") || label.contains("realtek pci") || label.contains("lan")) &&
                        !label.contains("wireless") && !label.contains("virtual")
                } ?: macChoices.firstOrNull()
                if(recommended!=null) {
                    macInput.setText(recommended.second)
                    getSharedPreferences("pc_wol",MODE_PRIVATE).edit().putString("mac",recommended.second).apply()
                }
            }
        }
        deviceText.text=x.optString("name","Windows")
        val b=x.optJSONObject("battery")
        val pct=b?.optInt("percent",-1)?:-1
        batteryText.text=if(pct>=0)"$pct%" else "—"
        val ram=x.optJSONObject("memory")
        val used=ram?.optInt("used_percent",-1)?:-1
        memoryText.text=if(used>=0)"$used%" else "—"
        val sec=x.optLong("uptime_seconds",-1)
        uptimeText.text=if(sec>=0)"${sec/3600} ч" else "—"
    }
    private fun refresh(){
        if(activeIp.isBlank() || activeKey.isBlank())return
        executor.execute{
            try{
                val result=PcApi(activeIp,activeKey).status()
                runOnUiThread{showDevice(result);setStatus(true)}
            }catch(e:Exception){runOnUiThread{setStatus(false)}}
        }
    }
    private fun chooseMac(){
        if(macChoices.isEmpty()){
            toast("Сначала подключись к запущенной Windows-программе — она передаст MAC-адреса")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Сетевой адаптер ноутбука")
            .setItems(macChoices.map { "${it.first}  ·  ${it.second}" }.toTypedArray()){_,selected->
                val mac=macChoices[selected].second
                macInput.setText(mac)
                getSharedPreferences("pc_wol",MODE_PRIVATE).edit().putString("mac",mac).apply()
                toast("MAC сохранён. Выбор адаптера не означает поддержку Wake-on-LAN.")
            }
            .setNegativeButton("Закрыть",null).show()
    }
    private fun wakePc(){
        val mac=macInput.text.toString().trim()
        val ip=ipInput.text.toString().trim()
        if(WakeOnLan.normalizeMac(mac)==null){
            toast("Укажи MAC. Пока ноутбук включён, подключись и нажми «Выбрать MAC с ноутбука»")
            return
        }
        getSharedPreferences("pc_wol",MODE_PRIVATE).edit().putString("mac",mac).apply()
        wakeState.text="Отправляем сигнал пробуждения…"
        executor.execute {
            try {
                val count=WakeOnLan.send(mac,ip)
                runOnUiThread {
                    if(isFinishing)return@runOnUiThread
                    wakeState.text="✓ Отправлено $count пакета(ов). Проверяем подключение до 60 секунд…"
                    wakeMonitorActive = true
                    wakeMonitorDeadline = System.currentTimeMillis()+60_000L
                    handler.removeCallbacks(wakeChecker)
                    handler.postDelayed(wakeChecker,4000)
                }
            }catch(e:Exception){
                runOnUiThread { if(!isFinishing){ wakeState.text="Ошибка отправки: ${e.message?.take(100)}";toast("Проверь домашний Wi-Fi") } }
            }
        }
    }
    private fun perform(action:String,confirm:Boolean){
        if(!connected){toast("Сначала подключись к Windows");return}
        val run={
            executor.execute{
                try{
                    val response=PcApi(activeIp,activeKey).action(action)
                    runOnUiThread{toast(response.optString("message","Команда отправлена"))}
                }catch(e:Exception){runOnUiThread{toast("Ошибка: ${e.message?.take(90)}");refresh()}}
            }
        }
        if(confirm){
            val msg=when(action){
                "sleep"->"Перевести ноутбук в режим сна? Управление будет недоступно до его пробуждения."
                "restart"->"Перезагрузить Windows? Перед этим сохрани файлы. Команда выполнится через 30 секунд."
                "shutdown"->"Выключить Windows? Несохранённые данные могут быть потеряны. Команда выполнится через 30 секунд."
                "display_off"->"Погасить только экран ноутбука? Компьютер и сеть продолжат работать."
                else->"Выполнить команду?"
            }
            AlertDialog.Builder(this).setTitle("Подтвердить действие").setMessage(msg)
                .setNegativeButton("Отмена",null).setPositiveButton("Выполнить"){_,_->run()}.show()
        }else run()
    }
}
