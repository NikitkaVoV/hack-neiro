package ru.neurostenographer

import org.json.JSONArray
import org.json.JSONObject

internal object ResultJson {
    fun build(output: AsrOutput, model: SttModel) = JSONObject().apply {
        val isNemotron = BuildConfig.FLAVOR in setOf("mnnPrototypeNemotron", "production")
        val engineVersion = if (isNemotron) "sherpa-onnx@6a4ad68b / ${model.name}" else "sherpa-onnx-2.0.1 / ${model.name}"
        put("schema_version","0.2"); put("engine",engineVersion)
        put("model_id",model.id); put("model",JSONObject(model.manifest.toString()))
        if (isNemotron) put("diarization_model",JSONObject()
            .put("name","nvidia/Nemotron-3-Diarization")
            .put("revision","f667ed73aee57d40cc39428eb768b4fd87a0a29e")
            .put("quantization",BuildConfig.NEMOTRON_QUANTIZATION)
            .put("activity_threshold",BuildConfig.NEMOTRON_THRESHOLD.toDouble())
            .put("outer_window_ms",120000)
            .put("sortformer_cache_across_outer_windows",false)
            .put("speaker_count_mode","automatic")
            .put("cross_window_matcher",BuildConfig.SPEAKER_EMBEDDING_MODEL_LABEL))
        if(BuildConfig.FLAVOR in setOf("production", "mnnPrototype", "mnnPrototypeNemotron")) put("diarization_config",JSONObject()
            .put("preset_id","cpu-threads4-v1").put("provider","cpu").put("window_shift_ratio",0.1)
            .put("segmentation_threads",4).put("embedding_threads",4).put("clustering_threshold",0.5)
            .put("speaker_matcher_threshold",BuildConfig.SPEAKER_MATCHER_THRESHOLD.toDouble())
            .put("speaker_matcher_many_to_one_enabled",BuildConfig.SPEAKER_MATCHER_ALLOW_MANY_TO_ONE)
            .put("speaker_matcher_many_to_one_threshold",BuildConfig.SPEAKER_MATCHER_MANY_TO_ONE_THRESHOLD.toDouble())
            .put("nemotron_activity_threshold",BuildConfig.NEMOTRON_THRESHOLD.toDouble()))
        put("raw_text",output.text); put("diarization",output.diarizationStatus)
        output.waveform?.let { put("waveform",JSONArray(it.toList())) }
        put("timing_scope",if(output.transcript.isNotEmpty() && output.transcript.all { it.speakerId!=null }) "speaker_turn" else "audio_chunk")
        put("transcript",JSONArray().apply { output.transcript.forEach { segment -> put(JSONObject().apply {
            put("start_ms",segment.startMs); put("end_ms",segment.endMs); put("speaker_id",segment.speakerId ?: JSONObject.NULL)
            put("text",segment.text)
        }) } })
        put("speaker_turns",JSONArray().apply { output.speakerTurns.forEach { turn -> put(JSONObject().apply {
            put("start_ms",turn.startMs); put("end_ms",turn.endMs); put("speaker_id",turn.speakerId)
            if(turn.confidence in 0f..1f) put("confidence",turn.confidence)
        }) } })
        put("timing",JSONObject().apply {
            put("audio_duration_ms",output.audioDurationMs); put("decode_ms",output.decodeMs); put("diarization_ms",output.diarizationMs)
            put("engine_wall_ms",output.engineWallMs); put("rtf",if(output.audioDurationMs>0) output.engineWallMs.toDouble()/output.audioDurationMs else JSONObject.NULL)
        })
    }
    fun jury(record: JSONObject): JSONObject {
        require(record.optString("status","COMPLETED")=="COMPLETED") { "Для экспорта жюри завершите обработку. Промежуточные данные доступны в полном JSON." }
        val source=record.getJSONArray("transcript")
        return JSONObject().put("transcript",JSONArray().apply { for(i in 0 until source.length()) {
            val v=source.getJSONObject(i)
            require(!v.isNull("speaker_id") && v.getString("speaker_id").isNotBlank()) { "Не для всех реплик определён голос. Сохраните полный JSON с диагностикой или повторите разбор." }
            require(v.getLong("start_ms")>=0 && v.getLong("end_ms")>v.getLong("start_ms")) { "Некорректные границы реплики" }
            put(JSONObject().put("speaker_id",v.getString("speaker_id")).put("start_ms",v.getLong("start_ms")).put("end_ms",v.getLong("end_ms")).put("text",v.getString("text")))
        } })
    }
}
