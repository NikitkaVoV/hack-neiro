package ru.neurostenographer

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class SttModel(val id: String, val name: String, val directory: File, val manifest: JSONObject) {
    val modelFile get() = File(directory, if(id == ModelCatalog.BUILTIN) "model.int8.onnx" else "model.onnx")
    val tokensFile get() = File(directory, "tokens.txt")
}

internal class ModelCatalog(private val context: Context) {
    private val root=File(context.filesDir,"imported-models").apply { mkdirs() }
    fun list(): List<SttModel> = listOf(builtin()) + root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull {
        runCatching { SttModel(it.name,JSONObject(File(it,"manifest.json").readText(Charsets.UTF_8)).getString("name"),it,JSONObject(File(it,"manifest.json").readText(Charsets.UTF_8))) }.getOrNull()
    }
    fun get(id: String): SttModel = list().firstOrNull { it.id==id } ?: throw ModelNotReadyException("Выбранная модель не найдена")
    private fun builtin() = SttModel(BUILTIN,"GigaAM v3 · встроенная",File(context.filesDir,"models/gigaam-v3-ctc-punct"),JSONObject().apply {
        put("name","GigaAM v3 e2e CTC INT8"); put("architecture","nemo_ctc"); put("language","ru")
        put("model_sha256","d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35")
        put("tokens_sha256","142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd")
    })
    fun ensureFiles(model: SttModel) {
        if(model.id != BUILTIN) { require(model.modelFile.isFile && model.tokensFile.isFile); return }
        model.directory.mkdirs()
        for(name in listOf("model.int8.onnx","tokens.txt")) {
            val target=File(model.directory,name)
            if(target.isFile && target.length()>0) continue
            val temp=File(model.directory,".$name.tmp")
            try {
                context.assets.open("models/gigaam-v3-ctc-punct/$name").use { source -> temp.outputStream().use { source.copyTo(it,131072) } }
                check(temp.renameTo(target)) { "Не удалось извлечь модель" }
            } finally { temp.delete() }
        }
    }
    fun import(uri: Uri): SttModel {
        val id=UUID.randomUUID().toString(); val temp=File(root,".$id").apply { mkdirs() }
        try {
            val names=mutableSetOf<String>(); var total=0L
            context.contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in setOf("manifest.json","model.onnx","tokens.txt") && names.add(entry.name)) { "В ZIP нужны только manifest.json, model.onnx, tokens.txt без вложенных папок" }
                    val limit=when(entry.name) { "manifest.json" -> 65536L; "tokens.txt" -> 16L*1024*1024; else -> 2L*1024*1024*1024 }
                    var size=0L; val buffer=ByteArray(131072)
                    File(temp,entry.name).outputStream().use { output ->
                        while(true) { val n=zip.read(buffer); if(n<0) break; size+=n; total+=n
                            require(size<=limit && total<=2L*1024*1024*1024) { "Пакет модели слишком большой" }
                            require(root.usableSpace>256L*1024*1024) { "Недостаточно места для модели" }
                            output.write(buffer,0,n)
                        }
                    }
                    require(size>0) { "Пустой файл в пакете" }
                }
            } }
            require(names.size==3) { "В пакете не хватает файлов" }
            val manifest=JSONObject(File(temp,"manifest.json").readText(Charsets.UTF_8))
            require(manifest.optInt("schema_version")==1 && manifest.optString("architecture")=="nemo_ctc") { "Поддерживаются пакеты schema_version=1, architecture=nemo_ctc (GigaAM CTC ONNX для sherpa-onnx)" }
            require(manifest.optString("runtime","sherpa-onnx-2.0.1")=="sherpa-onnx-2.0.1" && manifest.optInt("sample_rate",16000)==16000) { "Нужна модель для sherpa-onnx 2.0.1 и 16 кГц" }
            require(manifest.getString("name").length in 1..100)
            for((name,key) in listOf("model.onnx" to "model_sha256","tokens.txt" to "tokens_sha256")) {
                require(manifest.getString(key).matches(Regex("[a-fA-F0-9]{64}"))) { "Некорректный SHA-256 $name" }
                require(sha256(File(temp,name))==manifest.getString(key).lowercase()) { "Не совпал SHA-256 $name" }
            }
            File(temp,"manifest.json").writeText(manifest.put("imported_at",java.time.Instant.now().toString()).toString(2),Charsets.UTF_8)
            // Реальный smoke загрузки и decode выполняется до появления пакета в каталоге.
            val candidate=SttModel(id,manifest.getString("name"),temp,manifest)
            val engine=LocalAsrEngine(context)
            try { engine.validateModel(candidate) } finally { engine.release() }
            val target=File(root,id); check(temp.renameTo(target))
            return SttModel(id,candidate.name,target,manifest)
        } finally { if(temp.exists()) temp.deleteRecursively() }
    }
    fun delete(id: String) {
        require(id != BUILTIN)
        val directory=get(id).directory
        require(directory.canonicalFile.parentFile==root.canonicalFile)
        check(directory.deleteRecursively()) { "Не удалось удалить модель" }
    }
    companion object {
        const val BUILTIN="gigaam-v3-ctc-punct-int8"
        fun sha256(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(131072); while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
