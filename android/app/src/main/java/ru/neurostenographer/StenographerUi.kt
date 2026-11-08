package ru.neurostenographer

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.media.MediaPlayer
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Нативные экраны приложения. Результаты приходят из того же локального движка, что и bench. */
class StenographerUi(
    private val activity: Activity,
    private val importAudio: () -> Unit,
    private val transcribe: (String) -> Unit,
    private val recordedAudio: (Uri, String) -> Unit,
    private val export: (String, String, Boolean) -> Unit
) {
    var importModel: () -> Unit = {}
    var resumeJob: (String) -> Unit = {}
    var reprocess: (JSONObject) -> Unit = {}
    private var modelImporting=false
    private var jobState: JSONObject?=null
    private var stageLabel: TextView?=null
    private var timerLabel: TextView?=null
    private var progressLabel: TextView?=null
    private var jobProgress: ProgressBar?=null
    private var partialBox: LinearLayout?=null
    private var lastPartialCount=-1
    private var terminalSignature=""
    private var transcriptRecordId:Long?=null
    private var transcriptPageIndex=0
    private val prefs = activity.getSharedPreferences("interface", 0)
    private val recordsFile = File(activity.filesDir, "recordings.json")
    private val records = mutableListOf<JSONObject>()
    private var tab = 0
    private var filter = 0
    private var detail: JSONObject? = null
    private var processing = false
    private var processingName = ""
    private var sheet: View? = null
    private var player: MediaPlayer? = null
    private var playingId: Long? = null
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var nav: WaveNavigation
    private var query = ""
    private var collectionName: String? = null
    private var participantsPage = false
    private var processingPage = false
    private var onboardingIndex: Int? = null
    private var sheetBox: View? = null
    private var dropDown: PopupWindow? = null
    private val envelopeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val envelopesLoading = mutableSetOf<Long>()
    private var released = false
    private val navHeight = 70
    private var systemBottomInset = 0
    private val expandedRecords = mutableSetOf<Long>()
    @Volatile private var recording = false
    @Volatile private var paused = false
    private var recorder: android.media.AudioRecord? = null
    private var recordingFile: File? = null
    private var recordingThread: Thread? = null
    @Volatile private var recordingBytes = 0L
    private val dark get() = when (prefs.getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val bg get() = Color.parseColor(if (dark) "#18181B" else "#FAF8F5")
    private val surface get() = Color.parseColor(if (dark) "#242427" else "#FFFFFF")
    private val ink get() = Color.parseColor(if (dark) "#F4F4F5" else "#252326")
    private val muted get() = Color.parseColor(if (dark) "#A5A5AD" else "#68656A")
    private val line get() = Color.parseColor(if (dark) "#39393E" else "#E6E2E3")
    private val apricot = Color.rgb(231, 160, 123)
    private val accentInk = Color.rgb(37, 35, 38)
    private fun d(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 24, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = d(radius).toFloat(); if (stroke) setStroke(d(1), line)
    }
    private fun text(value: String, size: Float = 16f, bold: Boolean = false, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(d(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun lp(height: Int = -2) = LinearLayout.LayoutParams(-1, if (height >= 0) d(height) else height)
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(activity), lp(height)) }
    private fun action(label: String, primary: Boolean = true, block: () -> Unit) = text(label, 16f, true, if (primary) accentInk else ink).apply {
        gravity = Gravity.CENTER; background = shape(if (primary) apricot else surface, 28, !primary)
        minHeight = d(56); isClickable = true; isFocusable = true; setOnClickListener { block() }
    }
    private fun icon(name: String, color: Int = ink, block: () -> Unit) = Glyph(activity, name, color).apply {
        contentDescription = when(name) { "back" -> "Назад"; "share" -> "Экспорт"; "more" -> "Ещё"; "play" -> "Воспроизвести"; else -> name }
        isFocusable = true; setOnClickListener { block() }
    }

    init {
        runCatching { val array = JSONArray(android.util.AtomicFile(recordsFile).openRead().use { it.readBytes().toString(Charsets.UTF_8) }); for (i in 0 until array.length()) records.add(array.getJSONObject(i)) }
    }

    fun show() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = object:FrameLayout(activity) {
            private val insetPaint=Paint()
            init { setWillNotDraw(false); setBackgroundColor(bg) }
            override fun onDraw(canvas:Canvas) {
                super.onDraw(canvas)
                insetPaint.color=if(detail==null && onboardingIndex==null && !recording) surface else bg
                canvas.drawRect(0f,(height-systemBottomInset).toFloat(),width.toFloat(),height.toFloat(),insetPaint)
            }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            systemBottomInset=bars.bottom
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); view.invalidate(); insets
        }
        activity.setContentView(root)
        activity.window.statusBarColor = bg; activity.window.navigationBarColor = bg
        activity.window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        if (!prefs.getBoolean("introduced", false)) onboarding(0) else render()
    }

    private fun render() {
        sheet = null; sheetBox = null; onboardingIndex = null; processingPage = false; root.removeAllViews(); root.setBackgroundColor(bg)
        updateSystemBottom()
        val layout = column()
        root.addView(layout, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }
        page = column().apply { setPadding(d(22), d(24), d(22), d(24)) }
        scroll.addView(page); layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (detail != null) { document(detail!!, participantsPage); return }
        when(tab) { 0 -> library(); 1 -> collections(); 2 -> search(); 3 -> settings() }
        nav = WaveNavigation().apply { contentDescription = "Записи, Коллекции, Добавить, Поиск, Настройки" }
        layout.addView(nav, lp(navHeight))
    }

    private fun heading(title: String, trailing: String? = "more", block: () -> Unit = { menu() }) {
        val header = row()
        header.addView(text(title, 28f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) header.addView(icon(trailing, ink, block), LinearLayout.LayoutParams(d(44), d(48)))
        page.addView(header, lp()); gap(page, 18)
    }

    private fun library() {
        heading("Записи")
        val filters = row()
        listOf("Все", "Готовые", "В обработке").forEachIndexed { index, label ->
            filters.addView(text(label, 14f, color = if(index == filter) accentInk else ink).apply {
                gravity = Gravity.CENTER; background = shape(if(index == filter) apricot else bg, 22, index != filter)
                setPadding(d(14), d(11), d(14), d(11)); setOnClickListener { filter = index; render() }
                isSelected = index == filter; isFocusable = true
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8) })
        }
        page.addView(filters); gap(page, 26)
        if (processing && filter != 1) {
            page.addView(text("В обработке", 15f, true, muted)); gap(page, 12)
            val card = card(); card.addView(text(processingName.substringBeforeLast('.'), 18f, true)); gap(card, 12)
            card.addView(text("Создаём стенограмму на устройстве", 14f, color = muted)); gap(card, 16)
            card.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(apricot) }, lp(5))
            card.setOnClickListener { showProcessing(processingName) }; page.addView(card, lp()); gap(page, 12)
        }
        if (filter != 2 && records.isNotEmpty()) {
            page.addView(text("Ваши записи", 15f, true, muted)); gap(page, 12)
            records.forEach { recordingCard(page, it) }
        } else if (!processing || filter == 1) {
            empty(page, "wave", if(filter == 2) "Всё готово" else "Сохраните важные слова", if(filter == 2) "Здесь появятся записи во время обработки." else "Добавьте аудиофайл или запишите разговор.\nСтенограмма останется на вашем устройстве.")
            if (filter != 2) { gap(page, 24); page.addView(action("Добавить запись") { addSheet() }, lp(56)) }
        }
    }

    private fun card() = column().apply { background = shape(surface, 24, true); setPadding(d(18), d(18), d(18), d(18)) }
    private fun updateSystemBottom() {
        activity.window.navigationBarColor=if(detail==null && onboardingIndex==null && !recording) surface else bg
        activity.window.isNavigationBarContrastEnforced=false
        root.invalidate()
    }
    private fun recordingCard(parent: LinearLayout, record: JSONObject) {
        val card = card().apply { setPadding(d(16),d(12),d(16),d(12)) }; val top = row()
        top.addView(text(record.optString("title"), 18f, true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        val id=record.optLong("id")
        val expanded=expandedRecords.contains(id)
        val expand=icon(if(expanded) "up" else "down") {}
        expand.contentDescription=if(expanded) "Свернуть запись" else "Развернуть запись"
        top.addView(expand,LinearLayout.LayoutParams(d(40),d(40)))
        top.addView(icon("more") { recordMenu(record) }, LinearLayout.LayoutParams(d(32), d(32))); card.addView(top)
        gap(card, 3)
        val metadata=row()
        metadata.addView(text("${date(record.optLong("id"))} · ${duration(record.optJSONObject("timing")?.optLong("audio_duration_ms") ?: 0)}",13f,color=muted),LinearLayout.LayoutParams(0,-2,1f))
        val collection=record.optString("collection")
        if(collection.isNotBlank()) metadata.addView(text(collection,12f,color=muted).apply {
            maxLines=1; maxWidth=d(128); ellipsize=android.text.TextUtils.TruncateAt.END
            contentDescription="Коллекция: $collection"; background=shape(bg,10)
            setPadding(d(8),d(3),d(8),d(3))
        },LinearLayout.LayoutParams(-2,-2).apply { leftMargin=d(8) })
        card.addView(metadata,lp())
        if(record.optString("status") in listOf("INTERRUPTED","FAILED")) { gap(card,6); card.addView(text("Обработка прервана · можно продолжить",12f,color=apricot)) }
        val body=column()
        val snippet = record.optString("raw_text")
        if(snippet.isNotBlank()) { gap(body, 10); body.addView(text(snippet, 14f, color = muted).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        gap(body, 12); audioControls(body, record); gap(body, 12)
        body.addView(action("Открыть стенограмму",false) { detail=record; participantsPage=false; render() },lp(48))
        body.visibility=if(expanded) View.VISIBLE else View.GONE
        card.addView(body,lp())
        val toggle={
            val opening=body.visibility!=View.VISIBLE
            if(opening) { expandedRecords.add(id); loadEnvelope(record) } else expandedRecords.remove(id)
            android.transition.TransitionManager.beginDelayedTransition(parent,android.transition.AutoTransition().apply { duration=200 })
            body.visibility=if(opening) View.VISIBLE else View.GONE
            expand.name=if(opening) "up" else "down"
            expand.contentDescription=if(opening) "Свернуть запись" else "Развернуть запись"; expand.invalidate()
        }
        expand.setOnClickListener { toggle() }
        card.contentDescription="Запись: ${record.optString("title")}. Нажмите, чтобы свернуть или развернуть"
        card.isFocusable=true; card.setOnClickListener { toggle() }
        parent.addView(card, lp().apply { bottomMargin = d(10) })
    }
    private fun empty(parent: LinearLayout, glyph: String, title: String, body: String) {
        gap(parent, 58)
        parent.addView(Glyph(activity, glyph, apricot), LinearLayout.LayoutParams(d(76),d(76)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        gap(parent, 22)
        parent.addView(text(title, 20f, true).apply { gravity = Gravity.CENTER }, lp())
        gap(parent, 12)
        parent.addView(text(body, 15f, color = muted).apply { gravity = Gravity.CENTER }, lp())
    }

    private fun addSheet() {
        bottomSheet("Добавить запись", "Выберите, с чего начать") { box ->
            option(box, "mic", "Записать разговор", "Встреча, лекция или идея") { closeSheet(); startRecording() }
            option(box, "file", "Добавить аудиофайл", "Выбрать с устройства") { closeSheet(); importAudio() }
        }
        nav.open = true; nav.invalidate()
    }
    private fun bottomSheet(title: String, subtitle: String? = null, content: (LinearLayout) -> Unit) {
        closeSheet(false)
        val overlay = FrameLayout(activity).apply { isClickable = true; setOnClickListener { closeSheet() } }
        val box = DragSheet().apply {
            background = GradientDrawable().apply { setColor(bg); cornerRadii = floatArrayOf(d(30).toFloat(),d(30).toFloat(),d(30).toFloat(),d(30).toFloat(),0f,0f,0f,0f) }
            setPadding(d(20),0,d(20),d(if(detail == null && !recording) navHeight + 12 else 28)); isClickable = true
        }
        val handle = FrameLayout(activity)
        handle.addView(View(activity).apply { background = shape(muted,3) },FrameLayout.LayoutParams(d(40),d(4),Gravity.CENTER))
        box.addView(handle,lp(42)); box.addView(text(title,24f,true))
        if(subtitle != null) { gap(box,8); box.addView(text(subtitle,15f,color=muted)) }; gap(box,20)
        content(box)
        overlay.addView(box,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1)); sheet = overlay; sheetBox = box
        if(detail == null && !recording) overlay.addView(WaveNavigation().apply { open=true },FrameLayout.LayoutParams(-1,d(navHeight),Gravity.BOTTOM))
        box.visibility = View.INVISIBLE
        box.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
            box.viewTreeObserver.removeOnPreDrawListener(this)
            if(sheet !== overlay) return true
            box.translationY = box.height.toFloat(); box.visibility=View.VISIBLE
            box.animate().translationY(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            android.animation.ValueAnimator.ofInt(0,102).apply { duration=260; addUpdateListener { overlay.setBackgroundColor(Color.argb(it.animatedValue as Int,0,0,0)) }; start() }
            return true
            }
        })
    }
    private inner class DragSheet: LinearLayout(activity) {
        private var startY=0f
        private var startX=0f
        private var dragging=false
        init { orientation=VERTICAL }
        private fun scrollConsumesDrag(view:View,event:MotionEvent):Boolean {
            val bounds=Rect()
            if(!view.getGlobalVisibleRect(bounds) || !bounds.contains(event.rawX.toInt(),event.rawY.toInt())) return false
            if(view.canScrollVertically(-1)) return true
            if(view is ViewGroup) for(i in 0 until view.childCount) if(scrollConsumesDrag(view.getChildAt(i),event)) return true
            return false
        }
        override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY=event.rawY; startX=event.rawX; dragging=false }
                MotionEvent.ACTION_MOVE -> if(event.rawY-startY > ViewConfiguration.get(activity).scaledTouchSlop && event.rawY-startY > kotlin.math.abs(event.rawX-startX) && !scrollConsumesDrag(this,event)) { animate().cancel(); dragging=true; return true }
            }
            return false
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_MOVE -> { translationY=(event.rawY-startY).coerceAtLeast(0f); sheet?.setBackgroundColor(Color.argb((102*(1-translationY/height)).toInt().coerceIn(0,102),0,0,0)) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if(event.actionMasked == MotionEvent.ACTION_UP && translationY > minOf(d(100).toFloat(),height*.24f)) closeSheet()
                    else { animate().translationY(0f).setDuration(200).start(); sheet?.setBackgroundColor(0x66000000) }
                    dragging=false
                }
            }; return true
        }
    }
    private fun closeSheet(animated: Boolean = true) {
        val overlay=sheet; val box=sheetBox
        sheet=null; sheetBox=null
        (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)
        if(overlay != null) {
            if(animated && box != null) {
                box.animate().cancel(); box.animate().translationY(box.height.toFloat()).setDuration(220).withEndAction { root.removeView(overlay) }.start()
                overlay.isClickable=false
            } else root.removeView(overlay)
        }
        if(::nav.isInitialized) { nav.open=false; nav.invalidate() }
    }
    private fun editSheet(title:String, value:String, hint:String, saveLabel:String = "Сохранить", save:(String)->Unit) {
        bottomSheet(title) { box ->
            val input=EditText(activity).apply {
                setText(value); this.hint=hint; setTextColor(ink); setHintTextColor(muted); textSize=16f
                background=shape(surface,18,true); setPadding(d(16),0,d(16),0); setSingleLine(); selectAll()
            }
            box.addView(input,lp(56)); gap(box,20)
            val submit=action(saveLabel) { val entered=input.text.toString().trim(); if(entered.isNotEmpty()) { closeSheet(); save(entered) } else input.error="Введите название" }
            box.addView(submit,lp(56)); gap(box,8); box.addView(action("Отмена",false) { closeSheet() },lp(48))
            input.setOnEditorActionListener { _,_,_ -> submit.performClick(); true }
        }
    }
    private fun confirmSheet(title:String, message:String, confirm:String, block:()->Unit) {
        bottomSheet(title,message) { box -> box.addView(action(confirm) { closeSheet(); block() },lp(56)); gap(box,10); box.addView(action("Отмена",false) { closeSheet() },lp(48)) }
    }
    private fun option(parent: LinearLayout, glyph: String, title: String, subtitle: String, block: () -> Unit) {
        val view = row().apply { background = shape(surface,20,true); setPadding(d(14),d(14),d(12),d(14)); isFocusable = true; setOnClickListener { block() } }
        view.addView(Glyph(activity,glyph,apricot),LinearLayout.LayoutParams(d(32),d(36)))
        val labels = column(); labels.addView(text(title,16f,true)); gap(labels,5); labels.addView(text(subtitle,13f,color=muted))
        view.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin = d(14) }); view.addView(Glyph(activity,"next",muted),LinearLayout.LayoutParams(d(24),d(24)))
        parent.addView(view,lp().apply { bottomMargin=d(10) })
    }

    fun selected(name: String, size:Long=-1) {
        if(modelImporting) { Toast.makeText(activity,"Дождитесь проверки модели",Toast.LENGTH_SHORT).show(); return }
        val models=ModelCatalog(activity).list()
        var chosen=models.firstOrNull { it.id==prefs.getString("stt_model",ModelCatalog.BUILTIN) } ?: models.first()
        bottomSheet("Подготовка к разбору", "Обработка полностью на телефоне") { box ->
            box.addView(text(name,17f,true)); gap(box,12)
            val selector=action("Модель: ${chosen.name} ▾",false) {}
            selector.setPadding(d(12),d(8),d(12),d(8))
            selector.setOnClickListener {
                val options=column().apply { background=shape(surface,20,true); setPadding(d(8),d(8),d(8),d(8)) }
                val popup=PopupWindow(options,box.width.coerceAtLeast(d(260)),-2,true).apply { setBackgroundDrawable(shape(surface,20,true)); elevation=d(8).toFloat() }
                models.forEach { model -> options.addView(text(model.name,15f,color=if(chosen.id==model.id) apricot else ink).apply {
                    setPadding(d(12),d(16),d(12),d(16)); setOnClickListener { chosen=model; selector.text="Модель: ${model.name} ▾"; popup.dismiss() }
                },lp()) }
                dropDown=popup; popup.showAsDropDown(selector)
            }
            box.addView(selector,lp()); gap(box,12)
            val sizeText=if(size>=0) "Размер: %.1f МиБ\n".format(size/1048576.0) else "Размер будет проверен при чтении.\n"
            box.addView(text(sizeText+"WAV PCM16/RF64, MP3, M4A и форматы системного декодера. До 10 ГиБ, без лимита длительности.",13f,color=muted))
            gap(box,12)
            box.addView(text(if(size>=512L*1024*1024) "Большой файл: потребуется время и свободное место для копии. Рекомендуем подключить зарядку. Готовые фрагменты сохраняются." else "Долгая обработка продолжается в фоне. Готовые фрагменты сохраняются; для больших записей рекомендуем зарядку.",13f,color=muted))
            gap(box,18)
            box.addView(action("Создать стенограмму") {
                if(JobRunner.activeId!=null) { Toast.makeText(activity,"Уже идёт обработка",Toast.LENGTH_SHORT).show(); return@action }
                prefs.edit().putString("stt_model",chosen.id).apply()
                if(android.os.Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),502)
                closeSheet(); jobState=null; showProcessing(name); transcribe(chosen.id)
            },lp(56))
        }
    }
    fun showProcessing(name: String) {
        processing=true; processingName=name; detail=null; render(); processingPage=true
        page.removeAllViews(); heading("", "back") { render() }
        page.addView(text("Создаём стенограмму",25f,true)); gap(page,12)
        page.addView(text(name,15f,color=muted)); gap(page,20)
        stageLabel=text("Подготавливаем аудио",16f,true); page.addView(stageLabel,lp()); gap(page,12)
        timerLabel=text("Время обработки · 00:00",15f,color=muted); page.addView(timerLabel,lp()); gap(page,12)
        progressLabel=text("Готовые реплики появятся здесь по мере обработки",14f,color=muted); page.addView(progressLabel,lp()); gap(page,16)
        jobProgress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; isIndeterminate=true; progressTintList=android.content.res.ColorStateList.valueOf(apricot) }
        page.addView(jobProgress,lp(5)); gap(page,20)
        page.addView(action("К списку записей",false) { render() },lp(48)); gap(page,10)
        page.addView(action("Сохранить и остановить",false) { JobRunner.requestPause(); stageLabel?.text="Останавливаем после текущего вызова модели…" },lp(48)); gap(page,20)
        page.addView(text("Предварительная стенограмма",16f,true)); gap(page,8)
        page.addView(text("Последние 12 реплик. Полный текст сохранится в записи. Предварительное разделение по голосам может быть неточным.",13f,color=muted)); gap(page,16)
        partialBox=column(); page.addView(partialBox,lp()); lastPartialCount=-1
        jobState?.let { updateJob(it) }
    }
    fun updateJob(state:JSONObject) {
        jobState=state
        val status=state.optString("status"); processing=status=="RUNNING"
        processingName=state.optString("file_name",processingName)
        if(processingPage) {
            val stage=when(state.optString("stage")) { "copying" -> "Копируем аудио · ${state.optLong("copied_bytes")/1048576} МиБ"; "reading_audio" -> "Декодируем фрагмент"; "diarization" -> "Определяем говорящих"; "recognition" -> "Распознаём речь"; "chunk_completed" -> "Фрагмент сохранён"; "completed" -> "Готово"; "stopped" -> "Обработка остановлена"; else -> "Подготавливаем аудио" }
            stageLabel?.text=stage
            timerLabel?.text="Время обработки · ${duration(state.optLong("active_elapsed_ms"))}"
            val total=state.optLong("audio_duration_ms"); val done=state.optLong("processed_ms")
            progressLabel?.text=if(total>0) "Обработано ${duration(done)} из ${duration(total)}" else "Определяем длительность записи"
            jobProgress?.isIndeterminate=total<=0; if(total>0) jobProgress?.progress=(done*1000/total).toInt()
            val pieces=state.optJSONArray("transcript") ?: JSONArray()
            if(pieces.length()!=lastPartialCount) {
                lastPartialCount=pieces.length(); partialBox?.removeAllViews()
                for(i in maxOf(0,pieces.length()-12) until pieces.length()) {
                    val v=pieces.getJSONObject(i); partialBox?.addView(text("${duration(v.optLong("start_ms"))} · ${speakerName(state,v.optString("speaker_id"))}",13f,true,muted)); partialBox?.let { gap(it,6); it.addView(text(v.optString("text"),16f)); gap(it,16) }
                }
            }
        }
        val signature=state.optString("job_id")+status
        if(status in listOf("COMPLETED","INTERRUPTED","FAILED") && terminalSignature!=signature) {
            terminalSignature=signature; val wasProcessingPage=processingPage
            reloadJobs()
            if(wasProcessingPage) { detail=records.firstOrNull { it.optString("job_id")==state.optString("job_id") }; render() }
        }
    }
    fun reloadJobs() {
        for(state in JobRunner.history(activity)) {
            val id=state.getString("job_id")
            if(id in prefs.getStringSet("deleted_jobs",emptySet()).orEmpty()) continue
            if(state.optString("status")=="RUNNING") continue
            val index=records.indexOfFirst { it.optString("job_id")==id }
            if(index>=0) {
                for(key in listOf("title","collection","speaker_names")) if(records[index].has(key)) state.put(key,records[index].get(key))
                records[index]=state
            } else records.add(state)
        }
        records.sortByDescending { it.optLong("id") }; persist()
        if(!processingPage && onboardingIndex==null) render()
    }
    fun modelImportStarted() { modelImporting=true; bottomSheet("Проверяем модель","Сверяем файлы, SHA-256 и выполняем пробный запуск на телефоне") { box -> box.addView(ProgressBar(activity),lp(40)) } }
    fun modelImported(id:String,name:String) { modelImporting=false; prefs.edit().putString("stt_model",id).apply(); closeSheet(); bottomSheet("Модель добавлена",name) { box -> box.addView(action("Готово") { closeSheet(); render() },lp(56)) } }
    fun modelImportFailed(message:String) { modelImporting=false; failed(message) }
    private fun modelsSheet() {
        bottomSheet("Модели распознавания","Выбор модели доступен перед каждым разбором") { box ->
            val content=column(); val scroll=ScrollView(activity); scroll.addView(content); box.addView(scroll,lp(210))
            ModelCatalog(activity).list().forEach { model ->
                option(content,"wave",model.name,"${model.manifest.optString("language","ru")} · Nemo CTC · ${model.manifest.optString("model_sha256").take(12)}…") {
                    if(model.id==ModelCatalog.BUILTIN) Toast.makeText(activity,"Встроенная модель всегда доступна",Toast.LENGTH_SHORT).show()
                    else if(JobRunner.activeId==null) confirmSheet("Удалить модель?","Сохранённые стенограммы останутся. Для повторного запуска этой моделью потребуется импорт.","Удалить") { ModelCatalog(activity).delete(model.id); modelsSheet() }
                }
            }
            gap(box,12); box.addView(text("Импорт ZIP: manifest.json + model.onnx + tokens.txt. Только совместимые GigaAM/Nemo CTC ONNX; произвольные модели не поддерживаются.",13f,color=muted)); gap(box,12)
            box.addView(action("Импортировать модель") { closeSheet(); importModel() },lp(56))
        }
    }
    fun completed(result: JSONObject, name: String, uri: Uri?) {
        processing = false
        result.put("id",System.currentTimeMillis()).put("title",name.substringBeforeLast('.')).put("audio_uri",uri?.toString().orEmpty()).put("collection","")
        records.add(0,result); persist(); detail=result; render()
    }
    fun failed(message: String) { processing=false; detail=null; render(); bottomSheet("Не удалось выполнить действие",message) { box -> box.addView(action("Понятно") { closeSheet() },lp(56)) } }
    private fun persist() {
        val atomic=android.util.AtomicFile(recordsFile); val output=atomic.startWrite()
        try { output.write(JSONArray(records).toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) } catch(e:Exception) { atomic.failWrite(output); throw e }
    }

    private fun document(record: JSONObject, participants: Boolean = false) {
        if(transcriptRecordId!=record.optLong("id")) { transcriptRecordId=record.optLong("id"); transcriptPageIndex=0 }
        participantsPage = participants
        val header = row()
        header.addView(icon("back") { back() },LinearLayout.LayoutParams(d(44),d(48)))
        header.addView(View(activity),LinearLayout.LayoutParams(0,1,1f)); header.addView(icon("share") { exportSheet(record) },LinearLayout.LayoutParams(d(44),d(48)))
        page.addView(header,lp()); gap(page,14)
        page.addView(text(record.optString("title"),24f,true)); gap(page,10)
        page.addView(text("${date(record.optLong("id"))} · ${duration(record.optJSONObject("timing")?.optLong("audio_duration_ms") ?: 0)}",14f,color=muted)); gap(page,20)
        record.optString("started_at").takeIf { it.isNotBlank() }?.let {
            fun stamp(value:String)=runCatching { SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(Date.from(java.time.Instant.parse(value))) }.getOrDefault(value)
            page.addView(text("Начало: ${stamp(it)}\nОкончание: ${record.optString("finished_at").takeUnless { v -> v.isBlank() }?.let { v -> stamp(v) } ?: "не завершено"}\nОбработка: ${duration(record.optLong("active_elapsed_ms"))}\nМодель: ${record.optJSONObject("model")?.optString("name") ?: record.optString("model_id")}",13f,color=muted)); gap(page,16)
        }
        if(record.optString("status") in listOf("INTERRUPTED","FAILED")) {
            page.addView(text(record.optString("error",record.optString("interruption_reason","Обработка прервана")),14f,color=muted)); gap(page,12)
            page.addView(action("Продолжить обработку") { if(JobRunner.activeId==null) resumeJob(record.getString("job_id")) },lp(52)); gap(page,16)
        }
        val tabs = row().apply { background=shape(if(dark) Color.rgb(48,48,52) else Color.rgb(238,235,232),16); setPadding(d(3),d(3),d(3),d(3)) }
        listOf("Стенограмма","Участники").forEachIndexed { index,label -> tabs.addView(text(label,14f,color=if(participants == (index==1)) accentInk else ink).apply {
            gravity=Gravity.CENTER; if(participants == (index==1)) background=shape(apricot,14)
            setOnClickListener { page.removeAllViews(); document(record,index==1) }
        },LinearLayout.LayoutParams(0,d(40),1f)) }; page.addView(tabs,lp()); gap(page,26)
        val segments=record.optJSONArray("transcript") ?: JSONArray()
        if(participants) {
            val ids=(0 until segments.length()).map { segments.getJSONObject(it).optString("speaker_id","speaker_1") }.distinct()
            ids.forEachIndexed { index,id -> option(page,"person",speakerName(record,id),"${(0 until segments.length()).count { segments.getJSONObject(it).optString("speaker_id") == id }} реплик · нажмите, чтобы переименовать") { renameSpeaker(record,id) } }
        } else if(record.optString("raw_text").isBlank()) { empty(page,"wave","Речь не распознана","Попробуйте другую запись с более чёткой речью.") }
        else {
            val pageCount=((segments.length()+79)/80).coerceAtLeast(1)
            transcriptPageIndex=transcriptPageIndex.coerceIn(0,pageCount-1)
            val first=transcriptPageIndex*80; val last=minOf(segments.length(),first+80)
            if(pageCount>1) { page.addView(text("Реплики ${first+1}–$last из ${segments.length()}",13f,color=muted)); gap(page,16) }
            for(i in first until last) {
            val segment=segments.getJSONObject(i); if(segment.optString("text").isBlank()) continue
            val who=row(); val id=segment.optString("speaker_id","speaker_1")
            who.addView(text("●",17f,color=if(i%2==0) apricot else Color.rgb(186,158,231)),LinearLayout.LayoutParams(d(24),-2))
            who.addView(text(speakerName(record,id),16f,true),LinearLayout.LayoutParams(0,-2,1f)); who.addView(text(duration(segment.optLong("start_ms")),13f,color=muted))
            page.addView(who,lp()); gap(page,10); page.addView(text(segment.optString("text"),16f).apply { setTextIsSelectable(true) },lp()); gap(page,28)
            }
            if(pageCount>1) {
                val controls=row()
                if(transcriptPageIndex>0) controls.addView(action("Предыдущие",false) { transcriptPageIndex--; page.removeAllViews(); document(record) },LinearLayout.LayoutParams(0,d(48),1f))
                if(transcriptPageIndex<pageCount-1) controls.addView(action("Следующие",false) { transcriptPageIndex++; page.removeAllViews(); document(record) },LinearLayout.LayoutParams(0,d(48),1f))
                page.addView(controls,lp()); gap(page,16)
            }
        }
        gap(page,12); loadEnvelope(record); audioControls(page,record)
    }
    private fun speakerName(record: JSONObject,id:String) = record.optJSONObject("speaker_names")?.optString(id)?.takeIf { it.isNotBlank() } ?: if(id.isBlank() || id=="null") "Говорящий не определён" else "Голос ${id.substringAfterLast('_')}"
    private fun renameSpeaker(record:JSONObject,id:String) {
        editSheet("Имя участника",speakerName(record,id),"Имя") { value ->
            val names=record.optJSONObject("speaker_names") ?: JSONObject()
            names.put(id,value); record.put("speaker_names",names); persist(); render()
        }
    }
    private fun exportSheet(record:JSONObject) {
        bottomSheet("Экспорт стенограммы","Сохраните текст на устройстве") { box ->
            option(box,"file","Текст · TXT","Участники, время и реплики") {
                val pieces=record.optJSONArray("transcript") ?: JSONArray()
                val content=(0 until pieces.length()).joinToString("\n\n") { val s=pieces.getJSONObject(it); "${speakerName(record,s.optString("speaker_id"))} · ${duration(s.optLong("start_ms"))}\n${s.optString("text")}" }
                closeSheet(); export(content,record.optString("title"),false)
            }
            option(box,"file","Для жюри · JSON","Только реплики, голоса и времена по формату задания") {
                runCatching { ResultJson.jury(record) }.onSuccess { closeSheet(); export(it.toString(2),record.optString("title"),true) }.onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() }
            }
            option(box,"file","Полные данные · JSON","Модель, время обработки, реплики и диагностика") { closeSheet(); export(record.toString(2),record.optString("title")+"-full",true) }
        }
    }
    private fun collections() {
        collectionName?.let { name ->
            val header=row()
            header.addView(icon("back") { collectionName=null; render() },LinearLayout.LayoutParams(d(44),d(48)))
            header.addView(text(name,24f,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
            header.addView(icon("plus") { addToCollection(name) }.apply { contentDescription="Добавить записи в коллекцию" },LinearLayout.LayoutParams(d(44),d(48)))
            page.addView(header,lp()); gap(page,18)
            val selected=records.filter { it.optString("collection")==name }
            if(selected.isEmpty()) { empty(page,"folder","Пока пусто","Нажмите + сверху, чтобы выбрать записи."); gap(page,20); page.addView(action("Добавить записи") { addToCollection(name) },lp(48)) } else selected.forEach { recordingCard(page,it) }
            return
        }
        heading("Коллекции","plus") { createCollection() }
        val names=prefs.getStringSet("collections",emptySet()).orEmpty().sorted()
        if(names.isEmpty()) { empty(page,"folder","Всё на своих местах","Соберите встречи, лекции и идеи\nв отдельные коллекции."); gap(page,24); page.addView(action("Создать коллекцию") { createCollection() },lp(56)) }
        names.forEach { name -> option(page,"folder",name,"${records.count { it.optString("collection")==name }} записей") { collectionName=name; render() } }
    }
    private fun createCollection() {
        editSheet("Новая коллекция","","Название коллекции","Создать") { name ->
            prefs.edit().putStringSet("collections",prefs.getStringSet("collections",emptySet()).orEmpty()+name).apply(); render()
        }
    }
    private fun addToCollection(name:String) {
        val selectedIds=mutableSetOf<Long>()
        bottomSheet("Добавить записи","Выберите из общей библиотеки. Записи из других коллекций будут перемещены сюда.") { box ->
            if(records.isEmpty()) {
                box.addView(text("Пока нет записей. Сначала добавьте аудио в разделе «Записи».",15f,color=muted),lp())
                gap(box,20); box.addView(action("Понятно") { closeSheet() },lp(48))
            } else {
                val list=column()
                val submit=action("Выберите записи") {
                    if(selectedIds.isNotEmpty()) {
                        records.filter { selectedIds.contains(it.optLong("id")) }.forEach { it.put("collection",name) }
                        persist(); closeSheet(false); render()
                    }
                }.apply { isEnabled=false; alpha=.5f }
                records.forEach { record ->
                    val id=record.optLong("id"); val source=record.optString("collection"); val existing=source==name
                    val entry=row().apply { background=shape(surface,18,true); setPadding(d(8),d(10),d(12),d(10)) }
                    val check=CheckBox(activity).apply {
                        isChecked=existing; isEnabled=!existing
                        buttonTintList=android.content.res.ColorStateList.valueOf(apricot)
                        contentDescription=record.optString("title")
                    }
                    entry.addView(check,LinearLayout.LayoutParams(d(44),d(48)))
                    val labels=column(); labels.addView(text(record.optString("title"),15f,true).apply { maxLines=2 })
                    gap(labels,5); labels.addView(text(when { existing -> "Уже в этой коллекции"; source.isBlank() -> "Без коллекции"; else -> "Из коллекции «$source»" },12f,color=muted))
                    entry.addView(labels,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=d(8) })
                    check.setOnCheckedChangeListener { _,checked ->
                        if(checked) selectedIds.add(id) else selectedIds.remove(id)
                        submit.text=if(selectedIds.isEmpty()) "Выберите записи" else "Переместить · ${selectedIds.size}"
                        submit.isEnabled=selectedIds.isNotEmpty(); submit.alpha=if(submit.isEnabled) 1f else .5f
                    }
                    entry.isFocusable=!existing; if(!existing) entry.setOnClickListener { check.isChecked=!check.isChecked }
                    list.addView(entry,lp().apply { bottomMargin=d(8) })
                }
                val scroll=ScrollView(activity).apply { addView(list); isFillViewport=false }
                box.addView(scroll,LinearLayout.LayoutParams(-1,minOf(d(280),d(records.size*90),(activity.resources.displayMetrics.heightPixels*.34f).toInt())))
                gap(box,16); box.addView(submit,lp(52))
            }
        }
    }
    private fun search() {
        heading("Поиск",null)
        val input=EditText(activity).apply { hint="Найти в записях"; textSize=16f; setTextColor(ink); setHintTextColor(muted); background=shape(surface,20,true); setPadding(d(18),0,d(18),0); setSingleLine(); setText(query) }
        page.addView(input,lp(56)); gap(page,24)
        val results=column(); page.addView(results,lp())
        fun update() { results.removeAllViews(); val found=records.filter { query.isNotBlank() && (it.optString("title")+" "+it.optString("raw_text")).contains(query,true) }; if(found.isEmpty()) empty(results,"search",if(query.isBlank()) "Нужные слова рядом" else "Ничего не найдено",if(query.isBlank()) "Ищите по названию или тексту стенограммы." else "Попробуйте другое слово или часть названия.") else found.forEach { recordingCard(results,it) } }
        input.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {} ; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString(); update() }; override fun afterTextChanged(s:Editable?) {} }); update()
    }
    private fun settings() {
        heading("Настройки",null)
        page.addView(text("Оформление",14f,true,muted)); gap(page,12)
        val modes=listOf("system" to "Как на устройстве","light" to "Светлая","dark" to "Тёмная")
        val select = row().apply { background=shape(surface,20,true); setPadding(d(18),0,d(18),0); isFocusable=true }
        select.addView(text(modes.first { it.first==prefs.getString("theme","system") }.second,16f),LinearLayout.LayoutParams(0,-2,1f))
        select.addView(Glyph(activity,"down",muted),LinearLayout.LayoutParams(d(24),d(24)))
        select.setOnClickListener {
            val options=column().apply { background=shape(surface,20,true); setPadding(d(8),d(8),d(8),d(8)) }
            val popup=PopupWindow(options,select.width,-2,true).apply { setBackgroundDrawable(shape(surface,20,true)); elevation=d(8).toFloat() }
            modes.forEach { (key,label) -> options.addView(text(label,16f,color=if(prefs.getString("theme","system")==key) apricot else ink).apply { setPadding(d(14),d(16),d(14),d(16)); setOnClickListener { popup.dismiss(); prefs.edit().putString("theme",key).apply(); show() } },lp()) }
            dropDown=popup; popup.showAsDropDown(select,0,d(8))
        }
        page.addView(select,lp(60))
        gap(page,22); option(page,"wave","Модели распознавания","Встроенная модель и импорт своих пакетов") { modelsSheet() }; page.addView(text("Ваши данные",14f,true,muted)); gap(page,12)
        option(page,"file","Хранилище записей","${records.size} записей · только на этом устройстве") { Toast.makeText(activity,"Аудио и стенограммы хранятся локально",Toast.LENGTH_SHORT).show() }
        option(page,"wave","Как это работает","Повторить знакомство с приложением") { onboarding(0) }
        gap(page,24); page.addView(text("Мой стенограф",16f,true)); gap(page,8); page.addView(text("Распознавание без отправки записей в облако.\nВерсия ${BuildConfig.VERSION_NAME}",14f,color=muted))
    }
    private fun menu() { bottomSheet("Записи") { box -> option(box,"file","Добавить аудиофайл","Создать новую стенограмму") { closeSheet(); importAudio() }; option(box,"settings","Настройки","Оформление и данные") { closeSheet(); tab=3; render() } } }
    private fun recordMenu(record:JSONObject) {
        bottomSheet(record.optString("title")) { box ->
            option(box,"file","Переименовать","Изменить название записи") { editSheet("Название записи",record.optString("title"),"Название") { value -> record.put("title",value); persist(); render() } }
            option(box,"folder","В коллекцию","Выберите папку для записи") {
                val names=prefs.getStringSet("collections",emptySet()).orEmpty().sorted()
                if(names.isEmpty()) createCollection() else bottomSheet("Выберите коллекцию") { list -> names.forEach { name -> option(list,"folder",name,"Переместить запись") { record.put("collection",name); persist(); closeSheet(); render() } } }
            }
            option(box,"wave","Разобрать другой моделью","Создать отдельную версию стенограммы") { closeSheet(); if(JobRunner.activeId==null) reprocess(record) }
            option(box,"share","Экспорт","Сохранить TXT или JSON") { exportSheet(record) }
            option(box,"close","Удалить запись","Стенограмма будет удалена с устройства") {
                if(record.optString("job_id")==JobRunner.activeId) { Toast.makeText(activity,"Сначала остановите обработку этой записи",Toast.LENGTH_SHORT).show(); return@option }
                confirmSheet("Удалить запись?",record.optString("title"),"Удалить") {
                    record.optString("job_id").takeIf { it.isNotEmpty() }?.let { id ->
                        prefs.edit().putStringSet("deleted_jobs",prefs.getStringSet("deleted_jobs",emptySet()).orEmpty()+id).apply()
                        JobRunner.forget(activity,id)
                    }
                    records.remove(record); persist(); detail=null; render()
                }
            }
        }
    }
    private fun play(record:JSONObject) {
        try {
            val id=record.optLong("id")
            if(playingId != id || player == null) {
                player?.release()
                player=MediaPlayer().apply {
                    setDataSource(activity,Uri.parse(record.getString("audio_uri"))); prepare()
                    setOnCompletionListener { it.seekTo(0) }
                }
                playingId=id
            }
        player?.let { if(it.isPlaying) it.pause() else it.start() }
        } catch(_:Exception) { player?.release(); player=null; playingId=null; Toast.makeText(activity,"Аудиофайл недоступен. Возможно, он был перемещён.",Toast.LENGTH_LONG).show() }
    }
    private fun audioControls(parent:LinearLayout, record:JSONObject) {
        val controls=row()
        val playButton=FrameLayout(activity).apply { background=shape(if(dark) apricot else Color.rgb(249,233,224),24); contentDescription="Воспроизведение / пауза"; isFocusable=true; setOnClickListener { play(record) } }
        val symbol=Glyph(activity,"play",accentInk); playButton.addView(symbol,FrameLayout.LayoutParams(-1,-1))
        controls.addView(playButton,LinearLayout.LayoutParams(d(44),d(44)))
        val wave=Waveform(activity,apricot).apply {
            contentDescription="Позиция воспроизведения. Нажмите на волну для перемотки"; isFocusable=true
            seek={ fraction ->
                if(playingId != record.optLong("id") || player==null) { play(record); player?.pause() }
                player?.let { it.seekTo((it.duration*fraction).toInt()); showSeek(fraction, it.duration.toLong(), it.isPlaying) }
            }
        }
        controls.addView(wave,LinearLayout.LayoutParams(0,d(44),1f).apply { leftMargin=d(14) }); parent.addView(controls,lp())
        val times=row(); val elapsed=text("00:00",12f,color=muted); val total=text(duration(record.optJSONObject("timing")?.optLong("audio_duration_ms") ?: 0),12f,color=muted)
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f)); times.addView(total); gap(parent,8); parent.addView(times,lp())
        val poller=object:Runnable { override fun run() {
            if(!wave.isAttachedToWindow || released) return
            if(!wave.isShown) { wave.postDelayed(this,300); return }
            val data=record.optJSONArray("waveform")
            if(data!=null && data.length()>0) wave.samples=FloatArray(data.length()) { data.optDouble(it).toFloat() }
            val active=playingId==record.optLong("id") && player!=null
            val position=if(active) runCatching { player!!.currentPosition.toLong() }.getOrDefault(0) else 0
            val length=if(active) runCatching { player!!.duration.toLong() }.getOrDefault(0) else record.optJSONObject("timing")?.optLong("audio_duration_ms") ?: 0
            val playing=active && player?.isPlaying==true
            wave.updatePlayback(position,length,playing); elapsed.text=duration(position); total.text=duration(length)
            symbol.name=if(playing) "pause" else "play"; symbol.invalidate(); wave.postDelayed(this,80)
        } }
        // View.post также сработает, если страница уже прикреплена к окну до создания аудиоконтролов.
        wave.post(poller)
    }
    private fun loadEnvelope(record:JSONObject) {
        val id=record.optLong("id")
        val cached=record.optJSONArray("waveform")
        if((cached?.length() ?: 0)>=16 || !envelopesLoading.add(id) || released) return
        envelopeWorker.execute {
            val values=runCatching {
                AudioEnvelope.read(activity,Uri.parse(record.getString("audio_uri"))) { partial ->
                    activity.runOnUiThread { if(!released && partial.isNotEmpty()) record.put("waveform",JSONArray(partial.toList())) }
                }
            }.onFailure { android.util.Log.e("AudioEnvelope","Не удалось построить огибающую для записи $id",it) }.getOrNull()
            activity.runOnUiThread { envelopesLoading.remove(id); if(values!=null && values.isNotEmpty() && !released) { record.put("waveform",JSONArray(values.toList())); persist() } }
        }
    }
    fun startRecording() {
        if(JobRunner.activeId!=null) { Toast.makeText(activity,"Дождитесь завершения обработки",Toast.LENGTH_SHORT).show(); return }
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),501); return
        }
        player?.release(); player=null; playingId=null
        val size=android.media.AudioRecord.getMinBufferSize(16000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        try {
            val audio=android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,16000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,size)
            if(audio.state != android.media.AudioRecord.STATE_INITIALIZED) { audio.release(); throw IllegalStateException("Микрофон недоступен") }
            recorder=audio
            val directory=File(activity.filesDir,"audio").apply { mkdirs() }
            val name="Запись ${SimpleDateFormat("d MMM HH-mm-ss-SSS",Locale.forLanguageTag("ru")).format(Date())}.wav"
            val file=File(directory,name); recordingFile=file; recordingBytes=0; paused=false
            audio.startRecording(); recording=true; updateSystemBottom()
            val box=column().apply { setPadding(d(24),d(28),d(24),d(32)); setBackgroundColor(bg) }
            root.removeAllViews(); root.addView(box,FrameLayout.LayoutParams(-1,-1))
            box.addView(text("Запись\nразговора",28f,true)); gap(box,24)
            box.addView(text(name.removeSuffix(".wav"),18f,true).apply { background=shape(surface,20); setPadding(d(16),d(16),d(16),d(16)) },lp())
            gap(box,30)
            val timer=text("00:00",52f,true); box.addView(timer,lp()); gap(box,14)
            val state=text("● Идёт запись",16f,color=apricot); box.addView(state,lp())
            val art=FrameLayout(activity); val wave=Waveform(activity,apricot).apply { live=true }; art.addView(wave,FrameLayout.LayoutParams(-1,d(120),Gravity.CENTER)); box.addView(art,LinearLayout.LayoutParams(-1,0,1f))
            val controls=row()
            val pause=action("Пауза",false) {}
            pause.setOnClickListener { paused=!paused; pause.text=if(paused) "Продолжить" else "Пауза"; state.text=if(paused) "Запись на паузе" else "● Идёт запись" }
            controls.addView(pause,LinearLayout.LayoutParams(0,d(60),1f).apply { rightMargin=d(12) })
            val finish=action("Завершить") {
                recording=false
                timer.text="Сохраняем…"; pause.isEnabled=false
            }
            controls.addView(finish,LinearLayout.LayoutParams(0,d(60),1.5f)); box.addView(controls,lp())
            val captured=java.io.RandomAccessFile(file,"rw"); captured.setLength(0); captured.write(ByteArray(44))
            recordingThread=Thread({
                var failure:String?=null
                try {
                    val buffer=ByteArray(size)
                    while(recording && recordingBytes < 0xffff0000L) {
                        val count=audio.read(buffer,0,buffer.size)
                        if(count < 0) throw IllegalStateException("Ошибка чтения микрофона: $count")
                        if(count>0 && !paused) {
                            captured.write(buffer,0,count); recordingBytes+=count
                            var sum=0.0
                            for(i in 0 until count-1 step 2) { val sample=((buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8)).toShort().toDouble()/32768.0; sum+=sample*sample }
                            val volume=(kotlin.math.sqrt(sum/(count/2).coerceAtLeast(1))*4).toFloat().coerceIn(0f,1f)
                            activity.runOnUiThread { timer.text=duration(recordingBytes/32); wave.append(volume) }
                        }
                    }
                } catch(error:Exception) { failure=error.message ?: "Не удалось сохранить запись" }
                finally {
                    recording=false; runCatching { audio.stop() }; audio.release(); recorder=null
                    val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((recordingBytes+36).toInt()); put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(recordingBytes.toInt())
                    }.array()
                    runCatching { captured.seek(0); captured.write(header); captured.close() }
                }
                activity.runOnUiThread {
                    if(activity.isDestroyed) return@runOnUiThread
                    if(failure!=null || recordingBytes==0L) { file.delete(); render(); failed(failure ?: "Запись пустая. Попробуйте ещё раз.") }
                    else { render(); recordedAudio(Uri.fromFile(file),name) }
                }
            },"microphone-pcm-writer").apply { start() }
        } catch(error:Exception) { failed(error.message ?: "Микрофон недоступен") }
    }

    private fun onboarding(index:Int) {
        onboardingIndex=index; root.removeAllViews()
        updateSystemBottom()
        val box=column().apply { setPadding(d(26),d(20),d(26),d(24)) }; root.addView(box,FrameLayout.LayoutParams(-1,-1))
        val skip=text(if(index==0) "" else "Пропустить",14f,color=muted).apply {
            gravity=Gravity.END or Gravity.CENTER_VERTICAL
            if(index>0) setOnClickListener { finishOnboarding() }
        }; box.addView(skip,lp(48)); gap(box,16)
        if(index==0) {
            box.addView(Glyph(activity,"wave",apricot),LinearLayout.LayoutParams(d(64),d(64)))
            gap(box,24); box.addView(text("Мой\nстенограф",44f,true),lp())
            box.addView(OnboardingArt(0),LinearLayout.LayoutParams(-1,0,1f))
            box.addView(text("Сохраняйте\nразговоры",24f,true)); gap(box,26)
        } else {
            val titles=listOf("","Разговор\nстановится текстом","У каждой реплики —\nсвой голос","Ваши записи.\nНа вашем устройстве.")
            val bodies=listOf("","Запишите встречу или добавьте аудиофайл","Слушайте, читайте и исправляйте детали","Распознавание работает локально.\nБез отправки записей в облако.")
            box.addView(text(titles[index],27f,true),lp()); gap(box,12)
            box.addView(OnboardingArt(index),LinearLayout.LayoutParams(-1,0,1f))
            box.addView(text(bodies[index],16f,color=muted),lp()); gap(box,22)
            val dots=row().apply { gravity=Gravity.CENTER }
            repeat(3) { n -> dots.addView(View(activity).apply { background=shape(if(n==index-1) apricot else line,4) },LinearLayout.LayoutParams(d(if(n==index-1) 20 else 7),d(7)).apply { leftMargin=d(5); rightMargin=d(5) }) }
            box.addView(dots,lp(20)); gap(box,18)
        }
        box.addView(action(if(index==0) "Познакомиться" else if(index==3) "Начать" else "Далее") { if(index<3) onboarding(index+1) else finishOnboarding() },lp(56))
        var downX=0f; var downY=0f
        box.setOnTouchListener { _,event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN) { downX=event.x; downY=event.y; true }
            else if(event.actionMasked==MotionEvent.ACTION_UP) {
                val delta=event.x-downX
                if(kotlin.math.abs(delta)>d(60) && kotlin.math.abs(delta)>kotlin.math.abs(event.y-downY)) {
                    if(delta<0 && index<3) onboarding(index+1) else if(delta>0 && index>0) onboarding(index-1)
                }; true
            } else true
        }
        box.alpha=0f; box.translationX=d(12).toFloat(); box.animate().alpha(1f).translationX(0f).setDuration(260).start()
    }
    private fun finishOnboarding() { prefs.edit().putBoolean("introduced",true).apply(); onboardingIndex=null; render() }
    private inner class OnboardingArt(private val scene:Int):View(activity) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas) {
            val scale=minOf(width/320f,height/300f); c.save(); c.translate((width-320*scale)/2,(height-300*scale)/2); c.scale(scale,scale)
            fun bubble(x:Float,y:Float,label:String) {
                p.style=Paint.Style.FILL; p.color=surface; c.drawRoundRect(x,y,x+236,y+48,24f,24f,p)
                p.style=Paint.Style.STROKE; p.strokeWidth=1f; p.color=line; c.drawRoundRect(x,y,x+236,y+48,24f,24f,p)
                p.style=Paint.Style.FILL; p.color=ink; p.textSize=16f; p.typeface=Typeface.create("sans-serif",Typeface.NORMAL); c.drawText(label,x+18,y+30,p)
            }
            fun wave(x:Float,y:Float,w:Float) {
                p.color=apricot; p.strokeWidth=4f; p.strokeCap=Paint.Cap.ROUND
                for(i in 0..31) { val h=8f+((i*13+7)%31); c.drawLine(x+i*w/32,y-h/2,x+i*w/32,y+h/2,p) }
            }
            when(scene) {
                0 -> wave(8f,150f,304f)
                1 -> {
                    wave(8f,60f,304f)
                    p.color=apricot; p.strokeWidth=2f; p.style=Paint.Style.STROKE
                    val path=Path().apply { moveTo(20f,100f); lineTo(20f,196f); quadTo(20f,220f,44f,220f) }; c.drawPath(path,p)
                    bubble(64f,116f,"Обсудим план?"); bubble(64f,196f,"Давайте начнём.")
                }
                2 -> {
                    p.style=Paint.Style.STROKE; p.color=apricot; p.strokeWidth=2f
                    c.drawRoundRect(9f,58f,44f,234f,18f,18f,p)
                    p.style=Paint.Style.FILL; p.color=apricot; c.drawCircle(42f,55f,14f,p)
                    p.color=ink; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); p.textSize=16f; c.drawText("Участник 1",68f,61f,p)
                    bubble(58f,78f,"Обсудим план?")
                    p.color=Color.rgb(186,158,231); c.drawCircle(42f,192f,14f,p)
                    p.color=ink; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); c.drawText("Участник 2",68f,198f,p)
                    bubble(58f,215f,"Давайте начнём.")
                }
                3 -> {
                    p.style=Paint.Style.STROKE; p.strokeWidth=1f; p.color=line
                    c.drawCircle(160f,148f,139f,p); c.drawCircle(160f,148f,112f,p)
                    p.strokeWidth=3f; p.color=muted; c.drawRoundRect(108f,48f,212f,252f,22f,22f,p); c.drawLine(147f,63f,173f,63f,p)
                    c.save(); c.translate(128f,114f); drawGlyph(c,"wave",apricot,64f); c.restore()
                    p.color=apricot; p.style=Paint.Style.FILL; c.drawCircle(207f,240f,23f,p)
                    c.save(); c.translate(195f,228f); drawGlyph(c,"lock",accentInk,24f); c.restore()
                }
            }; c.restore()
        }
    }
    fun back():Boolean {
        if(dropDown?.isShowing==true) { dropDown?.dismiss(); return true }
        if(sheet!=null) { closeSheet(); return true }
        if(recording) { confirmSheet("Завершить запись?","Аудио будет сохранено на устройстве","Завершить") { recording=false }; return true }
        onboardingIndex?.let { if(it>0) onboarding(it-1) else { onboardingIndex=null; render() }; return true }
        if(detail!=null) { if(participantsPage) participantsPage=false else detail=null; render(); return true }
        if(processingPage) { render(); return true }
        if(collectionName!=null) { collectionName=null; render(); return true }
        if(tab!=0) { tab=0; render(); return true }
        return false
    }
    fun release() { released=true; recording=false; player?.release(); player=null; envelopeWorker.shutdownNow() }
    private fun duration(ms:Long)=if(ms>=3600000) "%d:%02d:%02d".format(ms/3600000,ms/60000%60,ms/1000%60) else "%02d:%02d".format(ms/60000,ms/1000%60)
    private fun date(ms:Long)=SimpleDateFormat("d MMM · HH:mm",Locale.forLanguageTag("ru")).format(Date(ms))

    private inner class WaveNavigation:FrameLayout(activity) {
        var open=false
            set(value) { field=value; if(childCount>2) getChildAt(2).contentDescription=if(value) "Закрыть панель" else "Добавить запись"; invalidate() }
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            setWillNotDraw(false)
            val labels=listOf("Записи","Коллекции","Добавить запись","Поиск","Настройки")
            repeat(5) { index -> addView(View(activity).apply {
                contentDescription=if(open && index==2) "Закрыть панель" else labels[index]; isFocusable=true
                isSelected=index!=2 && (if(index<2) index else index-1)==tab
                setOnClickListener {
                    if(index==2) { if(sheet!=null) closeSheet() else addSheet() }
                    else { closeSheet(false); tab=if(index<2) index else index-1; detail=null; collectionName=null; participantsPage=false; render() }
                }
            },FrameLayout.LayoutParams(0,-1)) }
        }
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            for(i in 0..4) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec((i+1)*measuredWidth/5-i*measuredWidth/5,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
            for(i in 0..4) getChildAt(i).layout(i*width/5,0,(i+1)*width/5,height)
        }
        override fun onDraw(canvas:Canvas) {
            val w=width.toFloat(); val h=height.toFloat(); val u=w/5f; val top=d(22).toFloat(); val mid=w/2
            val path=Path().apply { moveTo(0f,h); lineTo(0f,top+d(25)); quadTo(0f,top,d(30).toFloat(),top); lineTo(mid-d(86),top); cubicTo(mid-d(44),top,mid-d(40),d(3).toFloat(),mid,d(3).toFloat()); cubicTo(mid+d(40),d(3).toFloat(),mid+d(44),top,mid+d(86),top); lineTo(w-d(30),top); quadTo(w,top,w,top+d(25)); lineTo(w,h); close() }
            paint.color=surface; paint.style=Paint.Style.FILL; canvas.drawPath(path,paint); paint.color=line; paint.style=Paint.Style.STROKE; paint.strokeWidth=d(1).toFloat()
            canvas.save(); canvas.clipRect(0,0,width,height-d(1)); canvas.drawPath(path,paint); canvas.restore()
            val names=listOf("wave","folder","plus","search","settings")
            for(i in 0..4) {
                val cx=u*(i+.5f)
                if(i==2) {
                    paint.style=Paint.Style.FILL; paint.color=apricot; canvas.drawCircle(cx,d(40).toFloat(),d(24).toFloat(),paint)
                    canvas.save(); canvas.translate(cx-d(12).toFloat(),d(28).toFloat()); if(open) canvas.rotate(45f,d(12).toFloat(),d(12).toFloat()); drawGlyph(canvas,"plus",accentInk,d(24).toFloat()); canvas.restore()
                } else {
                    val selected=(if(i<2) i else i-1)==tab; canvas.save(); canvas.translate(cx-d(12),d(36).toFloat()); drawGlyph(canvas,names[i],if(selected) apricot else muted,d(24).toFloat()); canvas.restore()
                }
            }
        }
    }
    private inner class Glyph(context:android.content.Context,var name:String,private val tint:Int):View(context) {
        override fun onDraw(canvas:Canvas) { val size=minOf(width,height)*.58f; canvas.save(); canvas.translate((width-size)/2,(height-size)/2); drawGlyph(canvas,name,tint,size); canvas.restore() }
    }
    private fun drawGlyph(c:Canvas,name:String,color:Int,size:Float) {
        c.save(); c.scale(size/24,size/24)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; style=Paint.Style.STROKE; strokeWidth=1.8f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        fun path(vararg xy:Float) { val path=Path(); path.moveTo(xy[0],xy[1]); for(i in 2 until xy.size step 2) path.lineTo(xy[i],xy[i+1]); c.drawPath(path,p) }
        when(name) {
            "plus" -> { c.drawLine(12f,3f,12f,21f,p); c.drawLine(3f,12f,21f,12f,p) }
            "close" -> { path(5f,5f,19f,19f); path(19f,5f,5f,19f) }
            "back" -> path(15f,3f,6f,12f,15f,21f)
            "next" -> path(9f,5f,16f,12f,9f,19f)
            "search" -> { c.drawCircle(10.5f,10.5f,7f,p); c.drawLine(16f,16f,22f,22f,p) }
            "folder" -> { val path=Path().apply { moveTo(5f,5f); lineTo(9f,5f); quadTo(10f,5f,11f,7f); lineTo(19f,7f); quadTo(22f,7f,22f,10f); lineTo(22f,18f); quadTo(22f,21f,19f,21f); lineTo(5f,21f); quadTo(2f,21f,2f,18f); lineTo(2f,8f); quadTo(2f,5f,5f,5f); close() }; c.drawPath(path,p) }
            "down" -> path(5f,9f,12f,16f,19f,9f)
            "up" -> path(5f,15f,12f,8f,19f,15f)
            "lock" -> { c.drawRoundRect(5f,10f,19f,22f,3f,3f,p); c.drawArc(8f,2f,16f,16f,180f,180f,false,p); c.drawLine(12f,14f,12f,17f,p) }
            "file" -> { path(5f,2f,14f,2f,20f,8f,20f,22f,5f,22f,5f,2f); path(14f,2f,14f,8f,20f,8f) }
            "mic" -> { c.drawRoundRect(8f,2f,16f,16f,4f,4f,p); c.drawArc(4f,7f,20f,21f,0f,180f,false,p); path(12f,21f,12f,24f) }
            "play" -> { p.style=Paint.Style.FILL; val path=Path().apply { moveTo(8f,4f); lineTo(20f,12f); lineTo(8f,20f); close() }; c.drawPath(path,p) }
            "pause" -> { p.strokeWidth=4f; path(8f,5f,8f,19f); path(16f,5f,16f,19f) }
            "more" -> { p.style=Paint.Style.FILL; for(i in 0..2) c.drawCircle(5f+i*7,12f,1.5f,p) }
            "share" -> { path(5f,10f,5f,22f,20f,22f,20f,10f); path(12f,16f,12f,2f); path(7f,7f,12f,2f,17f,7f) }
            "person" -> { c.drawCircle(12f,7f,4f,p); c.drawArc(3f,13f,21f,29f,180f,180f,false,p) }
            "theme" -> { c.drawCircle(12f,12f,9f,p); p.style=Paint.Style.FILL; c.drawArc(3f,3f,21f,21f,90f,180f,true,p) }
            "settings" -> { val path=Path(); for(i in 0 until 32) { val angle=i*Math.PI/16; val radius=if(i%4==0 || i%4==3) 10.5 else 8.5; val x=(12+radius*kotlin.math.cos(angle)).toFloat(); val y=(12+radius*kotlin.math.sin(angle)).toFloat(); if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }; path.close(); c.drawPath(path,p); c.drawCircle(12f,12f,3.5f,p) }
            else -> { for(i in 0..4) { val h=floatArrayOf(6f,13f,21f,12f,6f)[i]; c.drawLine(3f+i*4.5f,12f-h/2,3f+i*4.5f,12f+h/2,p) } }
        }; c.restore()
    }
    private class Waveform(context:android.content.Context,private val tint:Int):View(context) {
        var samples=FloatArray(0)
        var live=false
        var progress=0f
        private var playbackPosition=0L
        private var playbackDuration=0L
        private var playbackAt=0L
        private var playbackRunning=false
        private var lastFrameAt=0L
        private var seekHoldUntil=0L
        fun updatePlayback(position:Long,duration:Long,running:Boolean) {
            val now=android.os.SystemClock.uptimeMillis()
            if(now<seekHoldUntil) return
            playbackPosition=position; playbackDuration=duration; playbackAt=now; playbackRunning=running
            if(!running) progress=if(duration>0) position.toFloat()/duration else 0f
            invalidate()
        }
        fun showSeek(fraction:Float,duration:Long,running:Boolean) {
            val now=android.os.SystemClock.uptimeMillis()
            progress=fraction; playbackPosition=(duration*fraction).toLong(); playbackDuration=duration
            playbackAt=now; playbackRunning=running; seekHoldUntil=now+200; lastFrameAt=now; invalidate()
        }
        var seek:((Float)->Unit)?=null
        private val history=java.util.ArrayDeque<Float>()
        private var smoothed=0f
        private var lastAppend=0L
        private var interval=100f
        fun append(value:Float) {
            val now=android.os.SystemClock.uptimeMillis()
            if(lastAppend>0) interval=(now-lastAppend).toFloat().coerceIn(40f,300f)
            smoothed += (value-smoothed)*(if(value>smoothed) .65f else .28f)
            if(history.size>=80) history.removeFirst(); history.addLast(smoothed); lastAppend=now; invalidate()
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(seek==null) return super.onTouchEvent(event)
            parent.requestDisallowInterceptTouchEvent(true)
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_MOVE) { seek?.invoke((event.x/width).coerceIn(0f,1f)); if(event.actionMasked==MotionEvent.ACTION_UP) performClick() }; return true
        }
        override fun performClick():Boolean { super.performClick(); return true }
        override fun onDraw(canvas:Canvas) {
            val now=android.os.SystemClock.uptimeMillis()
            if(!live && playbackRunning && playbackDuration>0) {
                val target=((playbackPosition+now-playbackAt).toFloat()/playbackDuration).coerceIn(0f,1f)
                val dt=if(lastFrameAt==0L) 16L else (now-lastFrameAt).coerceIn(0,64)
                progress+=(target-progress)*(1f-kotlin.math.exp(-dt/40f))
            }
            lastFrameAt=now
            val density=resources.displayMetrics.density
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint; strokeWidth=3*density; strokeCap=Paint.Cap.ROUND }
            val count=(width/(6*density)).toInt().coerceAtLeast(1); val step=width.toFloat()/count
            val values=if(live) history.toFloatArray() else samples
            val max=if(live) 1f else (values.maxOrNull() ?: 1f).coerceAtLeast(.015f)
            val shift=if(live && lastAppend>0) ((android.os.SystemClock.uptimeMillis()-lastAppend)/interval).coerceIn(0f,1f) else 0f
            canvas.save(); canvas.clipRect(0,0,width,height)
            for(i in 0 until count) {
                val value=if(live) values.getOrNull(values.size-count+i) ?: 0f else {
                    val from=i*values.size/count; val to=((i+1)*values.size/count).coerceAtLeast(from+1)
                    var peak=0f; for(index in from until to.coerceAtMost(values.size)) peak=maxOf(peak,values[index]); peak/max
                }
                val amplitude=maxOf(2*density,height*.86f*kotlin.math.sqrt(value.coerceIn(0f,1f)))
                paint.alpha=if(live || (i+.5f)/count<=progress) 255 else 70
                val x=step*(i+.5f-shift)
                canvas.drawLine(x,(height-amplitude)/2,x,(height+amplitude)/2,paint)
            }
            if(!live && progress>0f) { paint.alpha=255; paint.strokeWidth=2*density; canvas.drawLine(width*progress,0f,width*progress,height.toFloat(),paint) }
            canvas.restore()
            if(isAttachedToWindow && ((live && lastAppend>0 && shift<1f) || (!live && playbackRunning))) postInvalidateOnAnimation()
        }
    }
}
