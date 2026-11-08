package ru.neurostenographer

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var ui: StenographerUi
    private var exportContent=""
    private var inputUri:Uri?=null
    private var inputName="audio.wav"
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var observedStatus=""
    private var benchTitle:TextView?=null
    private var benchStage:TextView?=null
    private var benchDuration:TextView?=null
    private var benchElapsed:TextView?=null
    private var benchServer:TextView?=null
    private var benchMetadata:TextView?=null
    private var benchJobId:TextView?=null
    private var benchProgress:ProgressBar?=null
    private var benchElapsedJobId=""
    private var benchElapsedFromServer=0L
    private var benchElapsedAnchor=0L
    private val benchScreenPoll=object:Runnable {
        override fun run() {
            runCatching { BenchIntegration.snapshot() }.getOrNull()?.let(::renderBenchSnapshot)
            handler.postDelayed(this,1000)
        }
    }
    private val poll=object:Runnable {
        override fun run() {
            if(::ui.isInitialized) {
                JobRunner.snapshot()?.let { state ->
                    ui.updateJob(state)
                    val signature=state.optString("job_id")+state.optString("status")
                    if(signature!=observedStatus) {
                        observedStatus=signature
                        if(state.optString("status") in listOf("COMPLETED","INTERRUPTED","FAILED")) ui.reloadJobs()
                    }
                }
            }
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            runCatching { File(filesDir,"last-crash.txt").writeText(error.stackTraceToString(),Charsets.UTF_8) }
            previous?.uncaughtException(thread,error)
        }
        super.onCreate(savedInstanceState)
        if(BuildConfig.APP_MODE=="bench") showBenchScreen() else showProductionScreen()
    }
    private fun showBenchScreen() {
        BenchIntegration.start(this)
        val background=benchColor(R.color.bench_background)
        val primary=benchColor(R.color.bench_text_primary)
        val secondary=benchColor(R.color.bench_text_secondary)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(background)
        }
        content.addView(benchText(getString(R.string.app_name),24f,primary,true),matchWrap())
        content.addView(benchText("Локальный прогон на телефоне",15f,secondary),matchWrap())
        addBenchSpace(content,18)
        benchServer=benchText("Проверяю сервер…",14f,benchColor(R.color.bench_accent),true)
        content.addView(benchServer,matchWrap())
        addBenchSpace(content,20)

        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(20),dp(20),dp(20))
            setBackground(GradientDrawable().apply {
                setColor(benchColor(R.color.bench_surface))
                cornerRadius=dp(20).toFloat()
            })
        }
        benchTitle=benchText("Ожидаем аудио",20f,primary,true)
        panel.addView(benchTitle,matchWrap())
        addBenchSpace(panel,8)
        benchStage=benchText("Отправь файл с компьютера — состояние появится здесь.",15f,secondary)
        panel.addView(benchStage,matchWrap())
        addBenchSpace(panel,20)
        benchProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100
            progress=0
            isIndeterminate=false
            progressTintList=ColorStateList.valueOf(benchColor(R.color.bench_accent))
            progressBackgroundTintList=ColorStateList.valueOf(benchColor(R.color.bench_progress_track))
            contentDescription="Подтверждённая доля обработанного аудио"
        }
        panel.addView(benchProgress,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8)))
        addBenchSpace(panel,14)
        benchDuration=benchText("Обработано: —",16f,primary,true)
        panel.addView(benchDuration,matchWrap())
        addBenchSpace(panel,6)
        benchElapsed=benchText("Время обработки: —",14f,secondary)
        panel.addView(benchElapsed,matchWrap())
        content.addView(panel,matchWrap())

        addBenchSpace(content,18)
        benchMetadata=benchText("Модель: GigaAM v3 · CPU",13f,secondary)
        content.addView(benchMetadata,matchWrap())
        addBenchSpace(content,6)
        benchJobId=benchText("Задание ещё не запускалось",12f,secondary)
        benchJobId?.setTextIsSelectable(true)
        content.addView(benchJobId,matchWrap())
        addBenchSpace(content,16)
        content.addView(
            benchText("Полоса показывает последний сохранённый checkpoint; таймер идёт непрерывно. При сворачивании появится уведомление.",13f,secondary),
            matchWrap(),
        )
        setContentView(ScrollView(this).apply {
            isFillViewport=true
            setBackgroundColor(background)
            addView(content)
        })
        handler.post(benchScreenPoll)
    }

    private fun renderBenchSnapshot(snapshot:JSONObject) {
        val modelReady=snapshot.optBoolean("model_available",false)
        val serverStatus=snapshot.optString("server_status")
        benchServer?.apply {
            text=if(serverStatus.contains("слушает")) "HTTP-сервер готов · 127.0.0.1:8765" else serverStatus
            setTextColor(benchColor(if(serverStatus.contains("слушает")) R.color.bench_accent else R.color.bench_text_secondary))
        }
        val job=snapshot.optJSONObject("job")
        if(job==null) {
            benchTitle?.text="Ожидаем аудио"
            benchStage?.text=if(modelReady) "Отправь файл с компьютера — состояние появится здесь." else "Модель ещё готовится к первому запуску."
            benchDuration?.text="Обработано: —"
            benchElapsed?.text="Время обработки: —"
            benchMetadata?.text=if(modelReady) "Модель: GigaAM v3 · CPU" else "Модель не готова"
            benchJobId?.text="Задание ещё не запускалось"
            benchProgress?.apply { isIndeterminate=false; progress=0 }
            benchElapsedJobId=""
            benchElapsedFromServer=0
            benchElapsedAnchor=SystemClock.elapsedRealtime()
            return
        }

        val progress=job.optJSONObject("progress")
        val processed=progress?.optLong("processed_ms") ?: 0L
        val total=progress?.optLong("audio_duration_ms") ?: 0L
        val activeElapsed=progress?.optLong("active_elapsed_ms") ?: 0L
        val jobId=job.optString("job_id")
        val status=job.optString("status")
        if(jobId!=benchElapsedJobId || activeElapsed!=benchElapsedFromServer) {
            benchElapsedJobId=jobId
            benchElapsedFromServer=activeElapsed
            benchElapsedAnchor=SystemClock.elapsedRealtime()
        }
        val isActive=status in listOf("QUEUED","RUNNING","CANCELLING")
        val elapsed=activeElapsed+if(status=="RUNNING") (SystemClock.elapsedRealtime()-benchElapsedAnchor) else 0L
        benchTitle?.text=when(status) {
            "QUEUED" -> "Задание в очереди"
            "RUNNING" -> "Идёт обработка"
            "COMPLETED" -> "Разбор завершён"
            "FAILED" -> "Ошибка обработки"
            "CANCELLING" -> "Останавливаем обработку"
            "CANCELLED" -> "Обработка остановлена"
            else -> status.ifBlank { "Состояние неизвестно" }
        }
        benchTitle?.setTextColor(benchColor(if(status=="FAILED") R.color.bench_error else R.color.bench_text_primary))
        val stageLabel=benchStageLabel(job.optString("stage"))
        val error=job.optJSONObject("error")?.optString("message").orEmpty()
        benchStage?.text=if(status=="FAILED" && error.isNotBlank()) error else "Этап: $stageLabel"
        benchProgress?.apply {
            isIndeterminate=total<=0
            if(total>0) setProgress(((processed.coerceIn(0,total)*100)/total).toInt())
        }
        benchDuration?.text=if(total>0) {
            "Подтверждено: ${formatBenchTime(processed)} из ${formatBenchTime(total)}"
        } else {
            "Подготовка аудио"
        }
        benchElapsed?.text=when(status) {
            "QUEUED" -> "Ожидание запуска"
            else -> "Время обработки: ${formatBenchTime(elapsed)}"
        }
        val model=job.optString("model_id","gigaam-v3-ctc-punct-int8")
        val preset=job.optString("preset_id","cpu-threads4-v1")
        benchMetadata?.text="Модель: $model · $preset"
        benchJobId?.text=if(jobId.isBlank()) "Идентификатор задания недоступен" else "ID задания: $jobId"
        if(!isActive && status !in listOf("COMPLETED","FAILED","CANCELLED")) {
            benchProgress?.isIndeterminate=false
        }
    }

    private fun benchStageLabel(stage:String)=when(stage) {
        "queued" -> "В очереди"
        "loading_model" -> "Загрузка модели"
        "decoding","reading_audio" -> "Подготовка и чтение аудио"
        "inference_started" -> "Запуск обработки"
        "diarization" -> "Определение голосов"
        "recognition" -> "Распознавание речи"
        "chunk_completed" -> "Фрагмент обработан"
        "completed" -> "Готово"
        "failed" -> "Сбой"
        "cancelling" -> "Остановка"
        "cancelled" -> "Остановлено"
        else -> stage.replace('_',' ').ifBlank { "Подготовка" }
    }

    private fun formatBenchTime(milliseconds:Long):String {
        val seconds=milliseconds.coerceAtLeast(0)/1000
        val hours=seconds/3600
        val minutes=(seconds%3600)/60
        val remainder=seconds%60
        return if(hours>0) "%d:%02d:%02d".format(hours,minutes,remainder) else "%02d:%02d".format(minutes,remainder)
    }

    private fun benchColor(id:Int)=resources.getColor(id,theme)
    private fun benchText(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=value
        textSize=size
        setTextColor(color)
        typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
        gravity=Gravity.START
        includeFontPadding=false
    }
    private fun addBenchSpace(parent:LinearLayout,height:Int) {
        parent.addView(View(this),LinearLayout.LayoutParams(1,dp(height)))
    }

    private fun showProductionScreen() {
        JobRunner.initialize(this)
        ui=StenographerUi(this,{ if(JobRunner.activeId==null) openAudioPicker() },{ model -> startTranscription(model) },{ uri,name ->
            inputUri=uri; inputName=name; ui.selected(name)
        }) { content,title,json ->
            exportContent=content
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type=if(json) "application/json" else "text/plain"
                putExtra(Intent.EXTRA_TITLE,title+if(json) ".json" else ".txt")
            },REQUEST_SAVE)
        }
        ui.importModel={ openModelPicker() }
        ui.resumeJob={ id -> runCatching { JobRunner.resume(this,id); ui.showProcessing("Продолжаем обработку") }.onFailure { ui.failed(it.message ?: "Не удалось продолжить") } }
        ui.reprocess={ record ->
            inputUri=Uri.parse(record.getString("audio_uri")); inputName=record.optString("file_name",record.optString("title"))
            ui.selected(inputName)
        }
        ui.show(); ui.reloadJobs(); handler.post(poll)
        if(android.os.Build.VERSION.SDK_INT>=33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
    }
    private fun openAudioPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="audio/*" },REQUEST_AUDIO)
    }
    private fun openModelPicker() {
        if(JobRunner.activeId!=null) { ui.failed("Дождитесь завершения обработки перед импортом модели"); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/zip","application/octet-stream")) },REQUEST_MODEL)
    }
    private fun startTranscription(model:String) {
        val uri=inputUri ?: return
        runCatching { JobRunner.start(this,uri,inputName,model) }.onFailure { ui.failed(it.message ?: "Не удалось начать обработку") }
    }
    private fun queryName(uri:Uri):String = runCatching {
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "audio"
    private fun inputSize(uri:Uri):Long = runCatching {
        contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            REQUEST_AUDIO -> {
                val size=inputSize(uri)
                if(size>JobRunner.MAX_INPUT_BYTES) { ui.failed("Файл больше 10 ГиБ"); return }
                if(size>0 && size+JobRunner.STORAGE_RESERVE>filesDir.usableSpace) { ui.failed("Для копии файла и работы нужно ещё ${((size+JobRunner.STORAGE_RESERVE-filesDir.usableSpace)/1024/1024)+1} МиБ свободного места"); return }
                inputUri=uri; inputName=queryName(uri)
                runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ui.selected(inputName,size)
            }
            REQUEST_MODEL -> {
                ui.modelImportStarted()
                JobRunner.importModel(applicationContext,uri) { result -> runOnUiThread { if(!isDestroyed) result.fold({ ui.modelImported(it.id,it.name) },{ ui.modelImportFailed(it.message ?: "Несовместимая модель") }) } }
            }
            REQUEST_SAVE -> runCatching {
                contentResolver.openOutputStream(uri,"w")!!.use { it.write(exportContent.toByteArray(Charsets.UTF_8)) }
                android.widget.Toast.makeText(this,"Результат сохранён",android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { ui.failed("Не удалось сохранить результат: ${it.message}") }
        }
    }
    private var lastBackAt=0L
    private fun handleBack() {
        if(BuildConfig.APP_MODE!="bench" && ui.back()) { lastBackAt=0; return }
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastBackAt<2000) finish() else { lastBackAt=now; android.widget.Toast.makeText(this,"Нажмите «Назад» ещё раз, чтобы выйти",android.widget.Toast.LENGTH_SHORT).show() }
    }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { handleBack() }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==501 && grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED) ui.startRecording()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if(::ui.isInitialized) ui.release(); super.onDestroy() }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun matchWrap()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
    companion object { private const val REQUEST_AUDIO=101; private const val REQUEST_SAVE=102; private const val REQUEST_MODEL=103 }
}
