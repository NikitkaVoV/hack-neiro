package ru.neurostenographer

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import org.json.JSONObject

/** Запускается отдельным test APK через ADB. В production APK этого класса нет. */
class LocalPipelineInstrumentation: Instrumentation() {
    private var arguments=Bundle()
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); this.arguments=arguments ?: Bundle(); start() }
    override fun onStart() {
        val report=Bundle()
        try {
            val context=targetContext
            val action=arguments.getString("action","run")
            val model=arguments.getString("model",ModelCatalog.BUILTIN)
            val output=File(context.filesDir,"pipeline-test-result.json")
            output.delete()
            val result=when(action) {
                "cleanup" -> {
                    val ids=arguments.getString("jobs","").split(',').filter { it.isNotBlank() }.toSet()
                    ids.forEach { JobRunner.forget(context,it) }
                    val file=File(context.filesDir,"recordings.json")
                    if(file.isFile) {
                        val old=org.json.JSONArray(android.util.AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                        val keep=org.json.JSONArray()
                        for(i in 0 until old.length()) { val v=old.getJSONObject(i); if(v.optString("job_id") !in ids) keep.put(v) }
                        val atomic=android.util.AtomicFile(file); val stream=atomic.startWrite()
                        try { stream.write(keep.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream); throw e }
                    }
                    for(name in listOf("test-input.audio","test-input.m4a","test-model.zip","pipeline-test-jury.json")) File(context.filesDir,name).delete()
                    JSONObject().put("status","OK").put("removed_test_jobs",ids.size)
                }
                "opencl-probe" -> JSONObject().put("status","OK").put("device_probe",TestOpenClProbe.run())
                "reader-large" -> checkLargeReader(context.filesDir)
                "encode-aac" -> encodeAacFixture(context.filesDir)
                "accelerator" -> {
                    val profile=arguments.getString("profile", "false")=="true"
                    if(profile) {
                        val tag=arguments.getString("profile_tag","run")
                        require(tag.matches(Regex("[a-z0-9-]{1,40}")))
                        val directory=File(context.filesDir,"accelerator-profiles/$tag")
                        directory.mkdirs()
                        android.system.Os.setenv("STENOGRAPHER_ORT_PROFILE_DIR",directory.absolutePath,true)
                    } else android.system.Os.unsetenv("STENOGRAPHER_ORT_PROFILE_DIR")
                    startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val asr=arguments.getString("asr_provider","cpu")
                    val diar=arguments.getString("diar_provider","cpu")
                    require(asr in listOf("cpu","nnapi") && diar in listOf("cpu","nnapi"))
                    val engine=LocalAsrEngine(context,asr,diar,true)
                    val chosen=ModelCatalog(context).get(model)
                    try {
                        val value=JobRunner.runFile(engine,File(context.filesDir,"test-input.audio"),chosen)
                        ResultJson.build(value,chosen).put("requested_asr_provider",asr).put("requested_diarization_provider",diar)
                    } finally { engine.release() }
                }
                "import" -> {
                    val value=ModelCatalog(context).import(Uri.fromFile(File(context.filesDir,"test-model.zip")))
                    JSONObject().put("model_id",value.id).put("manifest",value.manifest)
                }
                "run", "resume" -> {
                    startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    var jobId=""
                    runOnMainSync {
                        jobId=if(action=="resume") {
                            val id=arguments.getString("job")!!; JobRunner.resume(context,id); id
                        } else JobRunner.start(context,Uri.fromFile(File(context.filesDir,"test-input.audio")),"TEST · ${arguments.getString("name","запись")}",model)
                    }
                    val deadline=System.currentTimeMillis()+arguments.getString("timeout","600").toLong()*1000
                    val stopAt=arguments.getString("stop_after_ms","0").toLong()
                    var stopped=false
                    val samples=org.json.JSONArray()
                    while(JobRunner.activeId!=null && System.currentTimeMillis()<deadline) {
                        val state=JobRunner.snapshot()
                        if(state!=null) {
                            samples.put(JSONObject().put("stage",state.optString("stage")).put("processed_ms",state.optLong("processed_ms")).put("active_elapsed_ms",state.optLong("active_elapsed_ms")))
                            if(!stopped && stopAt>0 && state.optLong("processed_ms")>=stopAt) { JobRunner.requestPause(); stopped=true }
                        }
                        Thread.sleep(1000)
                    }
                    check(JobRunner.activeId==null) { "Test timeout" }
                    val state=JobRunner.history(context).first { it.optString("job_id")==jobId }
                    state.put("test_samples",samples)
                    if(state.optString("status")=="COMPLETED") {
                        JsonDisk.write(File(context.filesDir,"pipeline-test-jury.json"),ResultJson.jury(state))
                    }
                    state
                }
                else -> error("Неизвестный action")
            }
            JsonDisk.write(output,result); report.putString("result_file",output.absolutePath); report.putString("status",result.optString("status","OK"))
            finish(Activity.RESULT_OK,report)
        } catch(e:Throwable) { report.putString("error",e.stackTraceToString()); finish(Activity.RESULT_CANCELED,report) }
    }
}

private fun checkLargeReader(root:File):JSONObject {
    val file=File(root,"test-large-rf64.wav")
    val data=8L*60*60*192000
    try {
        val header=java.nio.ByteBuffer.allocate(80).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RF64".toByteArray()); putInt(-1); put("WAVEds64".toByteArray()); putInt(28)
            putLong(data+72); putLong(data); putLong(data/4); putInt(0)
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(-1)
        }.array()
        val positions=listOf(0L,4L*60*60*1000,8L*60*60*1000-1000)
        java.io.RandomAccessFile(file,"rw").use { output ->
            output.write(header); output.setLength(80+data)
            positions.forEachIndexed { i,time ->
                output.seek(80+time*192)
                val value=(8192*(i+1)).toShort()
                val block=java.nio.ByteBuffer.allocate(48000*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                repeat(48000) { block.putShort(value); block.putShort(value) }; output.write(block.array())
            }
        }
        PcmChunks(file).use { source ->
            check(source.durationMs==28800000L)
            positions.forEachIndexed { i,time -> val audio=source.read(time,1000); check(audio.size==16000 && audio.all { it==(i+1)*0.25f }) }
            return JSONObject().put("status","OK").put("logical_size_bytes",file.length()).put("audio_duration_ms",source.durationMs)
                .put("checks","RF64 header and first/middle/last one-second reads; not full inference")
        }
    } finally { file.delete() }
}

private fun encodeAacFixture(root:File):JSONObject {
    val audio=PcmChunks(File(root,"test-input.audio")).use { source -> require(source.durationMs<=20000); source.read(0,source.durationMs) }
    val output=File(root,"test-input.m4a")
    val encoder=android.media.MediaCodec.createEncoderByType("audio/mp4a-latm")
    val muxer=android.media.MediaMuxer(output.absolutePath,android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started=false
    try {
        val format=android.media.MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE,64000)
        }
        encoder.configure(format,null,null,android.media.MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
        val info=android.media.MediaCodec.BufferInfo(); var at=0; var inputDone=false; var done=false; var track=-1
        val deadline=android.os.SystemClock.elapsedRealtime()+60000
        while(!done) {
            check(android.os.SystemClock.elapsedRealtime()<deadline)
            if(!inputDone) {
                val i=encoder.dequeueInputBuffer(10000)
                if(i>=0) {
                    val buffer=encoder.getInputBuffer(i)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN); buffer.clear()
                    val first=at; val n=minOf(audio.size-at,buffer.remaining()/2)
                    repeat(n) { buffer.putShort((audio[at++]*32767).toInt().coerceIn(-32768,32767).toShort()) }
                    inputDone=n==0
                    encoder.queueInputBuffer(i,0,n*2,first*1000000L/16000,if(inputDone) android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
            }
            val i=encoder.dequeueOutputBuffer(info,10000)
            if(i==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=muxer.addTrack(encoder.outputFormat); muxer.start(); started=true }
            else if(i>=0) {
                if(info.size>0 && info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                    val buffer=encoder.getOutputBuffer(i)!!; buffer.position(info.offset); buffer.limit(info.offset+info.size); muxer.writeSampleData(track,buffer,info)
                }
                done=info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                encoder.releaseOutputBuffer(i,false)
            }
        }
        return JSONObject().put("status","OK").put("aac_file",output.name).put("size_bytes",output.length())
    } finally {
        runCatching { encoder.stop() }; encoder.release()
        if(started) muxer.stop(); muxer.release()
    }
}
