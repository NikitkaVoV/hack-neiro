package ru.neurostenographer

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

internal data class AsrOutput(
    val text: String,
    val audioDurationMs: Long,
    val decodeMs: Long,
    val diarizationMs: Long,
    val diarizationStatus: String,
    val speakerTurns: List<SpeakerTurn>,
    val transcript: List<TranscriptPiece>,
    val engineWallMs: Long,
    val waveform: FloatArray? = null,
)

internal data class TranscriptPiece(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String?,
    val confidence: Float?,
    val text: String,
)

/** Shared local GigaAM v3 CTC runner used by both production UI and bench HTTP. */
internal class LocalAsrEngine(context: Context, private val asrProvider:String="cpu", diarizationProvider:String="cpu", private val debug:Boolean=false) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val modelDirectory = File(appContext.filesDir, "models/gigaam-v3-ctc-punct")
    // T18: ускоренный CPU-путь только в отдельном прототипе; эталонные flavors прежние.
    private val speakerDiarizer = LocalSpeakerDiarizer(appContext,diarizationProvider,debug,
        segmentationThreads=if(BuildConfig.FLAVOR in setOf("production", "mnnPrototype", "mnnPrototypeNemotron")) 4 else 1,
        embeddingThreads=if(BuildConfig.FLAVOR in setOf("production", "mnnPrototype", "mnnPrototypeNemotron")) 4 else 2)
    @Volatile private var recognizer: OfflineRecognizer? = null

    fun modelAssetsAvailable(): Boolean {
        val privateModel = File(modelDirectory, MODEL_FILE)
        val privateTokens = File(modelDirectory, TOKENS_FILE)
        if (privateModel.isFile && privateModel.length() > 0 && privateTokens.isFile && privateTokens.length() > 0) return true
        return runCatching {
        val names = assets.list(ASSET_DIRECTORY)?.toSet().orEmpty()
        MODEL_FILE in names && TOKENS_FILE in names
        }.getOrDefault(false)
    }

    private var activeModelId: String? = null
    private fun getRecognizer(model: SttModel): OfflineRecognizer {
        if(activeModelId == model.id) recognizer?.let { return it }
        recognizer?.release(); recognizer=null
        ModelCatalog(appContext).ensureFiles(model)
        return OfflineRecognizer(config=OfflineRecognizerConfig(
            modelConfig=OfflineModelConfig(nemo=OfflineNemoEncDecCtcModelConfig(model=model.modelFile.absolutePath),tokens=model.tokensFile.absolutePath,numThreads=2,provider=asrProvider,debug=debug),
            decodingMethod="greedy_search"
        )).also { recognizer=it; activeModelId=model.id }
    }
    fun validateModel(model: SttModel) { decode(getRecognizer(model),FloatArray(16000)) }

    fun transcribe(wavFile: File, model: SttModel = ModelCatalog(appContext).get(ModelCatalog.BUILTIN),
                   checkpoint: File? = null, cancelled: () -> Boolean = { false },
                   progress: (String, Long, Long, org.json.JSONObject?) -> Unit = { _,_,_,_ -> }): AsrOutput {
        val engineStarted=System.nanoTime()
        openAudioChunks(wavFile).use { source ->
            val saved=checkpoint?.let { JsonDisk.read(it) }
            require(saved==null || saved.getString("model_id")==model.id) { "Модель checkpoint не совпадает" }
            val transcript=mutableListOf<TranscriptPiece>(); val turns=mutableListOf<SpeakerTurn>()
            var decodeMs=saved?.optJSONObject("timing")?.optLong("decode_ms") ?: 0L
            var diarizationMs=saved?.optJSONObject("timing")?.optLong("diarization_ms") ?: 0L
            var status=saved?.optString("diarization","completed") ?: "completed"
            val matcher=SpeakerMatcher(
                saved?.optJSONArray("centroids"),
                BuildConfig.SPEAKER_MATCHER_THRESHOLD,
                BuildConfig.SPEAKER_MATCHER_ALLOW_MANY_TO_ONE,
                BuildConfig.SPEAKER_MATCHER_MANY_TO_ONE_THRESHOLD,
            )
            val sums=DoubleArray(160) { saved?.optJSONArray("envelope_sums")?.optDouble(it) ?: 0.0 }
            val counts=LongArray(160) { saved?.optJSONArray("envelope_counts")?.optLong(it) ?: 0L }
            saved?.optJSONArray("transcript")?.let { a -> for(i in 0 until a.length()) { val v=a.getJSONObject(i); transcript.add(TranscriptPiece(v.getLong("start_ms"),v.getLong("end_ms"),v.optString("speaker_id").takeUnless { it=="null" || it.isEmpty() },null,v.getString("text"))) } }
            saved?.optJSONArray("speaker_turns")?.let { a -> for(i in 0 until a.length()) { val v=a.getJSONObject(i); turns.add(SpeakerTurn(v.getLong("start_ms"),v.getLong("end_ms"),v.getString("speaker_id"),v.optDouble("confidence",-2.0).toFloat())) } }
            var offset=saved?.optLong("processed_ms") ?: 0L
            val total=source.durationMs
            fun output() = AsrOutput(transcript.joinToString(" ") { it.text },total,decodeMs,diarizationMs,status,turns.toList(),transcript.toList(),
                (saved?.optJSONObject("timing")?.optLong("engine_wall_ms") ?: 0L)+(System.nanoTime()-engineStarted)/1000000,
                FloatArray(160) { kotlin.math.sqrt(sums[it]/counts[it].coerceAtLeast(1)).toFloat() })
            fun checkCancelled() { if(cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("Обработка прервана; готовые фрагменты сохранены") }
            while(offset<total) {
                checkCancelled(); progress("reading_audio",offset,total,null)
                val decoded=source.read(offset,CHUNK_MS)
                require(decoded.isNotEmpty()) { "Декодер вернул пустой фрагмент" }
                val nominal=minOf(CHUNK_MS,total-offset)
                val chunkDuration=if(offset+nominal<total) AudioBoundary.endMs(decoded,0,nominal,10000) else nominal
                val samples=if(chunkDuration<nominal) decoded.copyOf((chunkDuration*16).toInt()) else decoded
                progress("diarization",offset,total,null)
                val diarizationStarted=System.nanoTime()
                // Освобождаем ASR перед загрузкой моделей диаризации.
                recognizer?.release(); recognizer=null; activeModelId=null
                var localTurns=try { speakerDiarizer.diarize(samples) } catch(e: Exception) {
                    Log.w(TAG,"Диаризация фрагмента не выполнена",e); status="failed"; emptyList()
                }
                checkCancelled()
                localTurns=try { speakerDiarizer.matchSpeakers(samples,localTurns,matcher) } catch(e:Exception) {
                    Log.w(TAG,"Сопоставление голосов не выполнено; сохраняем ASR без speaker ID",e)
                    status="failed"; emptyList()
                }
                diarizationMs+=(System.nanoTime()-diarizationStarted)/1000000
                checkCancelled(); progress("recognition",offset,total,null)
                val active=getRecognizer(model)
                val regions=if(localTurns.isEmpty()) listOf(TranscriptPiece(0,chunkDuration,null,null,"")) else localTurns.map {
                    TranscriptPiece(it.startMs.coerceIn(0,chunkDuration),it.endMs.coerceIn(0,chunkDuration),it.speakerId,it.confidence,"")
                }
                for(region in regions) {
                    var part=region.startMs
                    while(part<region.endMs) {
                        checkCancelled()
                        val nominalEnd=minOf(region.endMs,part+25000L)
                        val end=if(nominalEnd<region.endMs) AudioBoundary.endMs(samples,part,nominalEnd,3000) else nominalEnd
                        val from=((part-CONTEXT_MS).coerceAtLeast(0)*16).toInt()
                        val to=((end+CONTEXT_MS)*16).coerceAtMost(samples.size.toLong()).toInt()
                        if(to>from) {
                            val (text,elapsed)=decode(active,samples.copyOfRange(from,to)); decodeMs+=elapsed
                            if(text.isNotBlank()) transcript.add(TranscriptPiece(offset+part,offset+end,region.speakerId,region.confidence,text))
                        }
                        part=end
                    }
                }
                turns.addAll(localTurns.map { it.copy(startMs=offset+it.startMs.coerceIn(0,chunkDuration),endMs=offset+it.endMs.coerceIn(0,chunkDuration)) }.filter { it.endMs>it.startMs })
                for(i in samples.indices) {
                    val bin=(((offset*16+i)*160)/(total*16).coerceAtLeast(1)).toInt().coerceIn(0,159)
                    sums[bin]+=samples[i]*samples[i]; counts[bin]++
                }
                offset+=chunkDuration
                val partial=ResultJson.build(output(),model).put("processed_ms",offset).put("centroids",matcher.toJson())
                    .put("envelope_sums",org.json.JSONArray(sums.toList())).put("envelope_counts",org.json.JSONArray(counts.toList()))
                checkpoint?.let { JsonDisk.write(it,partial) }
                progress("chunk_completed",offset,total,partial)
            }
            return output()
        }
    }

    private fun decode(activeRecognizer: OfflineRecognizer, samples: FloatArray): Pair<String, Long> {
        val stream: OfflineStream = activeRecognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = TARGET_SAMPLE_RATE)
            val started = System.nanoTime()
            activeRecognizer.decode(stream)
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
            return activeRecognizer.getResult(stream).text.trim() to elapsed
        } finally {
            stream.release()
        }
    }

    fun release() {
        synchronized(this) {
            runCatching { recognizer?.release() }
            recognizer = null
        }
        speakerDiarizer.release()
    }

    companion object {
        private const val TAG = "LocalAsrEngine"
        private const val ASSET_DIRECTORY = "models/gigaam-v3-ctc-punct"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val CONTEXT_MS = 250
        private const val TARGET_SAMPLE_RATE = 16000
        const val CHUNK_MS = 120000L
    }
}
internal class ModelNotReadyException(message: String) : IllegalStateException(message)
internal class AudioInputException(message: String) : IllegalArgumentException(message)
