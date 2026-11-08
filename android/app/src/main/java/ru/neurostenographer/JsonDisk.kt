package ru.neurostenographer

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal object JsonDisk {
    fun write(file: File, value: JSONObject) {
        file.parentFile?.mkdirs(); val atomic=AtomicFile(file); val output=atomic.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch(e: Exception) { atomic.failWrite(output); throw e }
    }
    fun read(file: File): JSONObject? = if(!file.exists() && !File(file.path+".bak").exists()) null else
        JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
}
