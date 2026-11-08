package ru.neurostenographer

/** Диагностика bench APK: проверяет, что приложение видит и запускает OpenCL GPU. */
internal object TestOpenClProbe {
    init { System.loadLibrary("stenographer-opencl-probe") }
    @JvmStatic external fun run(): String
}
