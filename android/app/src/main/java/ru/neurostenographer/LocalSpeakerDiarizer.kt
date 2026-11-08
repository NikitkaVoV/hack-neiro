package ru.neurostenographer

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import java.io.File

internal data class SpeakerTurn(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String,
    val confidence: Float,
)

/** Local offline speaker segmentation, embedding, and clustering through sherpa-onnx. */
internal class LocalSpeakerDiarizer(context: Context, private val provider:String="cpu", private val debug:Boolean=false,
    private val windowShift:Float=0.1f, private val segmentationThreads:Int=1, private val embeddingThreads:Int=2) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val modelDirectory = File(appContext.filesDir, "models/speaker-diarization")
    private val useNemotron = BuildConfig.FLAVOR in setOf("mnnPrototypeNemotron", "production")
    @Volatile private var diarizer: OfflineSpeakerDiarization? = null

    fun modelAssetsAvailable(): Boolean {
        if (useNemotron) {
            val model = File(modelDirectory, NEMOTRON_MODEL_RELATIVE_PATH)
            if (model.isFile && model.length() > 0) return true
            return runCatching { assets.open(NEMOTRON_ASSET_PATH).use { it.read() >= 0 } }.getOrDefault(false)
        }
        val segmentation = File(modelDirectory, SEGMENTATION_FILE)
        val embedding = File(modelDirectory, EMBEDDING_FILE)
        if (segmentation.isFile && segmentation.length() > 0 && embedding.isFile && embedding.length() > 0) return true
        return runCatching {
            val names = assets.list(ASSET_DIRECTORY)?.toSet().orEmpty()
            SEGMENTATION_FILE in names && EMBEDDING_FILE in names
        }.getOrDefault(false)
    }

    @Synchronized
    private fun getDiarizer(): OfflineSpeakerDiarization {
        diarizer?.let { return it }
        if (!modelAssetsAvailable()) throw ModelNotReadyException("Модели диаризации не найдены")
        modelDirectory.mkdirs()
        val segmentation = if (useNemotron) {
            copyAssetIfNeeded(NEMOTRON_ASSET_PATH, File(modelDirectory, NEMOTRON_MODEL_RELATIVE_PATH))
        } else {
            copyAssetIfNeeded("$ASSET_DIRECTORY/$SEGMENTATION_FILE", File(modelDirectory, SEGMENTATION_FILE))
        }
        val embedding = copyAssetIfNeeded(EMBEDDING_FILE)
        val segmentationConfig = OfflineSpeakerSegmentationModelConfig(
            pyannote = if (useNemotron) OfflineSpeakerSegmentationPyannoteModelConfig() else
                OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentation.absolutePath,
                    windowShiftRatio = windowShift,
                ),
            numThreads = segmentationThreads,
            provider = provider,
            debug = debug,
        )
        if (useNemotron) {
            // Keep the base flavors source-compatible with sherpa 2.0.1, which
            // does not yet expose the Sortformer config type.
            val sortformer = Class.forName(SORTFORMER_CONFIG_CLASS).getDeclaredConstructor().newInstance()
            sortformer.javaClass.getDeclaredField("model").apply {
                isAccessible = true
                set(sortformer, segmentation.absolutePath)
            }
            sortformer.javaClass.getDeclaredField("threshold").apply {
                isAccessible = true
                setFloat(sortformer, BuildConfig.NEMOTRON_THRESHOLD)
            }
            OfflineSpeakerSegmentationModelConfig::class.java.getDeclaredField("sortformer").apply {
                isAccessible = true
                set(segmentationConfig, sortformer)
            }
        }
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = segmentationConfig,
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embedding.absolutePath,
                numThreads = embeddingThreads,
                provider = provider,
                debug = debug,
            ),
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = CLUSTER_THRESHOLD,
                computeConfidence = true,
            ),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        )
        return OfflineSpeakerDiarization(config = config).also { diarizer = it }
    }

    @Synchronized
    fun diarize(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): List<SpeakerTurn> {
        if (sampleRate != SAMPLE_RATE) throw AudioInputException("Для диаризации требуется PCM 16 кГц")
        if (samples.size < sampleRate / 2) return emptyList()
        val active = getDiarizer()
        if (active.sampleRate() != sampleRate) {
            throw IllegalStateException("Диаризатор ожидает ${active.sampleRate()} Гц, получено $sampleRate Гц")
        }
        val raw = active.process(samples)
        val orderedSpeakers = raw.sortedBy { it.start }
            .map { it.speaker }
            .distinct()
            .withIndex()
            .associate { it.value to "speaker_${it.index + 1}" }
        return raw.asSequence()
            .filter { it.end > it.start && it.start >= 0f }
            .map { turn ->
                SpeakerTurn(
                    startMs = (turn.start * 1000).toLong().coerceAtLeast(0),
                    endMs = (turn.end * 1000).toLong().coerceAtLeast(0),
                    speakerId = orderedSpeakers.getValue(turn.speaker),
                    confidence = turn.confidence,
                )
            }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .toList()
    }

    fun matchSpeakers(samples: FloatArray, turns: List<SpeakerTurn>, matcher: SpeakerMatcher): List<SpeakerTurn> {
        // Отдельный extractor загружается после освобождения segmentation/embedding диаризатора.
        release()
        if(turns.isEmpty()) return turns
        val extractor=SpeakerEmbeddingExtractor(config=SpeakerEmbeddingExtractorConfig(model=copyAssetIfNeeded(EMBEDDING_FILE).absolutePath,numThreads=embeddingThreads,provider=provider,debug=debug))
        try {
            val vectors=linkedMapOf<String,FloatArray>()
            for((id,regions) in turns.groupBy { it.speakerId }) {
                val pieces=regions.sortedByDescending { it.endMs-it.startMs }.take(4)
                val length=pieces.sumOf { ((it.endMs-it.startMs)*16).toInt() }.coerceAtMost(16000*20)
                val audio=FloatArray(length); var cursor=0
                for(turn in pieces) {
                    val start=(turn.startMs*16).toInt().coerceIn(0,samples.size)
                    val end=(turn.endMs*16).toInt().coerceIn(start,samples.size)
                    val n=minOf(end-start,length-cursor)
                    samples.copyInto(audio,cursor,start,start+n); cursor+=n
                    if(cursor==length) break
                }
                val stream=extractor.createStream()
                try {
                    stream.acceptWaveform(audio,16000); stream.inputFinished()
                    vectors[id]=if(extractor.isReady(stream)) extractor.compute(stream) else FloatArray(extractor.dim())
                } finally { stream.release() }
            }
            val ids=matcher.match(vectors, turns)
            if (BuildConfig.SPEAKER_EMBEDDING_DIAGNOSTICS) {
                logTurnEmbeddingSimilarities(samples, turns, ids, extractor)
            }
            return turns.map { it.copy(speakerId=ids.getValue(it.speakerId)) }
        } finally { extractor.release() }
    }

    private fun logTurnEmbeddingSimilarities(
        samples: FloatArray,
        turns: List<SpeakerTurn>,
        speakerIds: Map<String, String>,
        extractor: SpeakerEmbeddingExtractor,
    ) {
        val samplesByTurn = turns.sortedBy { it.startMs }.take(MAX_DIAGNOSTIC_TURNS).mapNotNull { turn ->
            val start = (turn.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, samples.size)
            val end = (turn.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(start, samples.size)
            if (end - start < MIN_DIAGNOSTIC_SAMPLES) return@mapNotNull null
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(start, end), SAMPLE_RATE)
                stream.inputFinished()
                if (!extractor.isReady(stream)) return@mapNotNull null
                val embedding = extractor.compute(stream)
                val norm = SpeakerMatcher.normalize(embedding)
                Triple(turn, speakerIds.getValue(turn.speakerId), norm)
            } finally {
                stream.release()
            }
        }
        val turnsJson = org.json.JSONArray().apply {
            samplesByTurn.forEachIndexed { index, (turn, stableId, _) ->
                put(org.json.JSONObject()
                    .put("index", index)
                    .put("start_ms", turn.startMs)
                    .put("end_ms", turn.endMs)
                    .put("speaker_id", stableId))
            }
        }
        val cosineJson = org.json.JSONArray().apply {
            samplesByTurn.forEach { (_, _, left) ->
                put(org.json.JSONArray().apply {
                    samplesByTurn.forEach { (_, _, right) ->
                        put(left.indices.sumOf { (left[it] * right[it]).toDouble() })
                    }
                })
            }
        }
        val diagnostic = org.json.JSONObject()
            .put("turns", turnsJson)
            .put("cosine_similarity", cosineJson)
        val line = diagnostic.toString()
        Log.i(TAG, "TURN_EMBEDDING_DIAGNOSTIC $line")
        runCatching {
            File(appContext.filesDir, "turn-embedding-diagnostics.jsonl")
                .appendText("$line\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Не удалось сохранить диагностику эмбеддингов", it) }
    }

    private fun copyAssetIfNeeded(name: String): File =
        copyAssetIfNeeded("$ASSET_DIRECTORY/$name", File(modelDirectory, name))

    private fun copyAssetIfNeeded(assetPath: String, target: File): File {
        if (target.isFile && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile ?: modelDirectory, ".${target.name}.tmp")
        assets.open(assetPath).use { input ->
            temporary.outputStream().buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024) }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Не удалось установить файл модели диаризации ${target.name}")
        }
        return target
    }

    fun release() = synchronized(this) {
        runCatching { diarizer?.release() }
            .onFailure { Log.w(TAG, "Failed to release speaker diarizer", it) }
        diarizer = null
    }

    companion object {
        private const val TAG = "LocalDiarizer"
        private const val ASSET_DIRECTORY = "models/speaker-diarization"
        private const val SEGMENTATION_FILE = "model.int8.onnx"
        private const val EMBEDDING_FILE = "speaker-embedding.onnx"
        private const val NEMOTRON_ASSET_PATH = "models/speaker-diarization/nemotron-3/model.int8.onnx"
        private const val NEMOTRON_MODEL_RELATIVE_PATH = "nemotron-3/model.int8.onnx"
        private const val SORTFORMER_CONFIG_CLASS = "com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationSortformerModelConfig"
        private const val CLUSTER_THRESHOLD = 0.5f
        private const val SAMPLE_RATE = 16_000
        private const val MIN_DIAGNOSTIC_SAMPLES = SAMPLE_RATE * 3 / 2
        private const val MAX_DIAGNOSTIC_TURNS = 24
    }
}
