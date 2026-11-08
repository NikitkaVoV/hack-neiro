package ru.neurostenographer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Один runner для UI/HTTP. Все native-вызовы, включая проверку импортируемой модели, сериализованы. */
internal object JobRunner {
    const val MAX_INPUT_BYTES=10L*1024*1024*1024
    const val STORAGE_RESERVE=512L*1024*1024
    private val inferenceLock=Any()
    private val lock=Any()
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile var activeId: String?=null; private set
    @Volatile private var pauseRequested=false
    private var current: JSONObject?=null
    private var startedElapsed=0L
    private var accumulated=0L
    private var initialized=false
    private fun root(context: Context)=File(context.filesDir,"processing-jobs").apply { mkdirs() }
    private fun stateFile(context: Context,id: String)=File(root(context),"$id/state.json")
    fun initialize(context: Context) = synchronized(lock) {
        if(initialized) return@synchronized
        root(context).listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            val file=File(directory,"state.json")
            val state=runCatching { JsonDisk.read(file) }.getOrNull() ?: return@forEach
            if(state.optString("status")=="RUNNING") {
                state.put("status","INTERRUPTED").put("stage","interrupted").put("interruption_reason","Процесс был остановлен. Готовые фрагменты сохранены.")
                JsonDisk.read(File(directory,"clock.json"))?.let { state.put("active_elapsed_ms",maxOf(state.optLong("active_elapsed_ms"),it.optLong("active_elapsed_ms"))) }
                JsonDisk.write(file,state)
            }
        }
        initialized=true
    }
    fun history(context: Context): List<JSONObject> = synchronized(lock) {
        initialize(context)
        root(context).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { runCatching { JsonDisk.read(File(it,"state.json")) }.getOrNull() }.sortedByDescending { it.optLong("id") }
    }
    fun snapshot(): JSONObject? = synchronized(lock) {
        current?.let { JSONObject(it.toString()).apply { if(activeId!=null) put("active_elapsed_ms",accumulated+SystemClock.elapsedRealtime()-startedElapsed) } }
    }
    fun start(context: Context, uri: Uri, name: String, modelId: String): String = synchronized(lock) {
        initialize(context); check(activeId==null) { "Дождитесь текущей обработки" }
        val id=UUID.randomUUID().toString()
        val state=JSONObject().put("job_id",id).put("id",System.currentTimeMillis()).put("audio_uri",uri.toString()).put("file_name",name)
            .put("title",name.substringBeforeLast('.')).put("collection","").put("model_id",modelId).put("status","INTERRUPTED")
            .put("started_at",Instant.now().toString()).put("active_elapsed_ms",0).put("processed_ms",0).put("audio_duration_ms",0)
        state.put("model",ModelCatalog(context).get(modelId).manifest)
        JsonDisk.write(stateFile(context,id),state)
        launch(context.applicationContext,state); id
    }
    fun resume(context: Context,id: String) = synchronized(lock) {
        check(activeId==null) { "Уже идёт обработка" }
        val state=JsonDisk.read(stateFile(context,id)) ?: error("Задание не найдено")
        require(state.optString("status") in listOf("INTERRUPTED","FAILED"))
        launch(context.applicationContext,state)
    }
    private fun launch(context: Context,state: JSONObject) {
        val id=state.getString("job_id"); activeId=id; pauseRequested=false; current=state
        state.put("status","RUNNING").put("stage","preparing").remove("error")
        accumulated=state.optLong("active_elapsed_ms"); startedElapsed=SystemClock.elapsedRealtime()
        JsonDisk.write(stateFile(context,id),state)
        try { context.startForegroundService(android.content.Intent(context,ProcessingService::class.java)) }
        catch(e: Exception) { activeId=null; state.put("status","INTERRUPTED").put("error",e.message); JsonDisk.write(stateFile(context,id),state); throw e }
        worker.execute { process(context,state) }
    }
    fun requestPause() { pauseRequested=true }
    fun persistClock(context:Context) = synchronized(lock) {
        val id=activeId ?: return@synchronized
        JsonDisk.write(File(stateFile(context,id).parentFile,"clock.json"),JSONObject().put("active_elapsed_ms",accumulated+SystemClock.elapsedRealtime()-startedElapsed))
    }
    fun forget(context:Context,id:String) = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9-]{36}")) && activeId!=id)
        val directory=stateFile(context,id).parentFile!!
        require(directory.canonicalFile.parentFile==root(context).canonicalFile)
        directory.deleteRecursively()
        if(current?.optString("job_id")==id) current=null
    }
    fun importModel(context: Context,uri: Uri,callback:(Result<SttModel>)->Unit) {
        worker.execute { callback(runCatching { synchronized(inferenceLock) { ModelCatalog(context).import(uri) } }) }
    }
    fun runFile(engine: LocalAsrEngine,file: File,model: SttModel,checkpoint: File?=null,
                cancelled:()->Boolean={false},progress:(String,Long,Long,JSONObject?)->Unit={_,_,_,_->}):AsrOutput = synchronized(inferenceLock) {
        engine.transcribe(file,model,checkpoint,cancelled,progress)
    }
    private fun process(context: Context,state: JSONObject) {
        val id=state.getString("job_id"); val dir=stateFile(context,id).parentFile!!
        val input=File(dir,"source.audio"); val checkpoint=File(dir,"checkpoint.json")
        val engine=LocalAsrEngine(context)
        try {
            val model=ModelCatalog(context).get(state.getString("model_id"))
            if(!input.isFile) {
                val partial=File(dir,"source.part"); val digest=MessageDigest.getInstance("SHA-256")
                try {
                    context.contentResolver.openInputStream(Uri.parse(state.getString("audio_uri")))!!.use { source ->
                        partial.outputStream().use { output ->
                            val buffer=ByteArray(131072); var total=0L; var last=0L
                            while(true) {
                                if(pauseRequested) throw InterruptedException()
                                val n=source.read(buffer); if(n<0) break; total+=n
                                require(total<=MAX_INPUT_BYTES) { "Файл больше 10 ГиБ" }
                                require(dir.usableSpace>STORAGE_RESERVE) { "Недостаточно свободного места: нужен резерв 512 МиБ" }
                                digest.update(buffer,0,n); output.write(buffer,0,n)
                                if(SystemClock.elapsedRealtime()-last>1000) {
                                    update(context,state,"copying",0,0,null); synchronized(lock) { state.put("copied_bytes",total) }; last=SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                    check(partial.renameTo(input)); synchronized(lock) {
                        state.put("audio_id",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                        JsonDisk.write(stateFile(context,id),state)
                    }
                } finally { partial.delete() }
            }
            val result=runFile(engine,input,model,checkpoint,{pauseRequested}) { stage,done,total,partial -> update(context,state,stage,done,total,partial) }
            if(pauseRequested) throw InterruptedException()
            val json=ResultJson.build(result,model)
            synchronized(lock) {
                state.put("status","COMPLETED").put("stage","completed").put("finished_at",Instant.now().toString())
                state.put("active_elapsed_ms",accumulated+SystemClock.elapsedRealtime()-startedElapsed)
                for(key in json.keys()) state.put(key,json.get(key))
                state.getJSONObject("timing").put("total_processing_ms",state.getLong("active_elapsed_ms"))
                state.put("processed_ms",result.audioDurationMs).put("audio_duration_ms",result.audioDurationMs)
                JsonDisk.write(stateFile(context,id),state)
            }
            input.delete(); checkpoint.delete()
        } catch(e: Exception) {
            synchronized(lock) {
                state.put("status",if(e is InterruptedException) "INTERRUPTED" else "FAILED").put("stage","stopped")
                state.put("active_elapsed_ms",accumulated+SystemClock.elapsedRealtime()-startedElapsed)
                state.put("last_stopped_at",Instant.now().toString()).put("error",e.message ?: "Не удалось обработать запись")
                JsonDisk.write(stateFile(context,id),state)
            }
        } finally {
            engine.release()
            synchronized(lock) {
                context.stopService(android.content.Intent(context,ProcessingService::class.java)); activeId=null
            }
        }
    }
    private fun update(context: Context,state: JSONObject,stage: String,done: Long,total: Long,partial: JSONObject?) = synchronized(lock) {
        state.put("stage",stage).put("processed_ms",done).put("audio_duration_ms",total)
            .put("active_elapsed_ms",accumulated+SystemClock.elapsedRealtime()-startedElapsed)
        partial?.let { for(key in listOf("transcript","raw_text","speaker_turns","diarization","timing","model","model_id","waveform")) state.put(key,it.get(key)) }
        JsonDisk.write(stateFile(context,state.getString("job_id")),state)
    }
}
