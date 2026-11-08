package ru.neurostenographer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

class ProcessingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val clockWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastClock = 0L

    private val refresh = object : Runnable {
        override fun run() {
            val benchActive = benchPreferences().getBoolean(KEY_BENCH_ACTIVE, false)
            if (JobRunner.activeId == null && !benchActive) {
                val latestBench = readBenchSnapshot()
                if (latestBench?.optString("status") in TERMINAL_STATUSES) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
                return
            }

            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            if (JobRunner.activeId != null && SystemClock.elapsedRealtime() - lastClock >= 5000) {
                lastClock = SystemClock.elapsedRealtime()
                clockWorker.execute { runCatching { JobRunner.persistClock(applicationContext) } }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обработка записей", NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification(), type)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stenographer:processing")
            .apply { acquire() }
        handler.post(refresh)
    }

    private fun notification(): Notification {
        val bench = readBenchSnapshot()
        if (bench != null) return benchNotification(bench)

        val state = JobRunner.snapshot()
        val done = state?.optLong("processed_ms") ?: 0
        val total = state?.optLong("audio_duration_ms") ?: 0
        val open = openAppIntent()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand)
            .setContentTitle("Создаём стенограмму")
            .setContentText(if (total > 0) "Обработано ${formatTime(done)} из ${formatTime(total)}" else "Подготавливаем аудио")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent(done, total), total <= 0)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Сохранить и остановить",
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, ProcessingService::class.java).setAction(ACTION_PAUSE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
            .build()
    }

    private fun benchNotification(job: JSONObject): Notification {
        val progress = job.optJSONObject("progress")
        val done = progress?.optLong("processed_ms") ?: 0
        val total = progress?.optLong("audio_duration_ms") ?: 0
        val status = job.optString("status")
        val stage = stageLabel(job.optString("stage"))
        val title = when (status) {
            "COMPLETED" -> "Разбор завершён"
            "FAILED" -> "Ошибка разбора"
            "CANCELLED" -> "Разбор остановлен"
            else -> "Разбираем аудио"
        }
        val line = if (total > 0) {
            "${formatTime(done)} из ${formatTime(total)} · $stage"
        } else {
            stage
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand)
            .setContentTitle(title)
            .setContentText(line)
            .setSubText(job.optString("model_id", "Локальная модель"))
            .setContentIntent(openAppIntent())
            .setOngoing(status in ACTIVE_STATUSES)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent(done, total), total <= 0)
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun readBenchSnapshot(): JSONObject? =
        benchPreferences().getString(KEY_BENCH_SNAPSHOT, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

    private fun benchPreferences() = getSharedPreferences(PREFS_BENCH, Context.MODE_PRIVATE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) JobRunner.requestPause()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        JobRunner.requestPause()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        clockWorker.shutdown()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 12
        private const val CHANNEL_ID = "processing"
        private const val PREFS_BENCH = "bench_foreground"
        private const val KEY_BENCH_ACTIVE = "active"
        private const val KEY_BENCH_SNAPSHOT = "snapshot"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_BENCH = "bench"
        private val ACTIVE_STATUSES = setOf("QUEUED", "RUNNING", "CANCELLING")
        private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")

        fun publishBenchJob(
            context: Context,
            snapshot: JSONObject,
            active: Boolean,
            startService: Boolean = false,
        ): Boolean {
            val preferences = context.getSharedPreferences(PREFS_BENCH, Context.MODE_PRIVATE)
            preferences.edit()
                .putString(KEY_BENCH_SNAPSHOT, snapshot.toString())
                .putBoolean(KEY_BENCH_ACTIVE, active)
                .apply()

            if (!startService) return true
            return try {
                context.startForegroundService(
                    Intent(context, ProcessingService::class.java).setAction(ACTION_BENCH)
                )
                true
            } catch (error: Exception) {
                preferences.edit().putBoolean(KEY_BENCH_ACTIVE, false).apply()
                Log.e("ProcessingService", "Could not start foreground service for bench job", error)
                false
            }
        }

        private fun progressPercent(done: Long, total: Long): Int =
            if (total <= 0) 0 else ((done.coerceIn(0, total) * 100) / total).toInt()

        private fun formatTime(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val remainder = seconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, remainder)
            } else {
                "%02d:%02d".format(minutes, remainder)
            }
        }

        private fun stageLabel(stage: String): String = when (stage) {
            "queued" -> "В очереди"
            "loading_model" -> "Загрузка модели"
            "decoding" -> "Подготовка аудио"
            "inference_started" -> "Начало обработки"
            "reading_audio" -> "Чтение аудио"
            "diarization" -> "Определение голосов"
            "recognition" -> "Распознавание текста"
            "chunk_completed" -> "Фрагмент готов"
            "completed" -> "Готово"
            "failed" -> "Сбой обработки"
            "cancelling" -> "Остановка"
            "cancelled" -> "Остановлено"
            else -> stage.replace('_', ' ').ifBlank { "Подготовка" }
        }
    }
}
