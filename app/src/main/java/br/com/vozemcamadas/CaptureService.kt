package br.com.vozemcamadas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "br.com.vozemcamadas.START_CAPTURE"
        const val ACTION_STOP = "br.com.vozemcamadas.STOP_CAPTURE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "voz_capture"
        private const val NOTIFICATION_ID = 4817
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 2_048
        private const val MAX_DURATION_MS = 45L * 60L * 1_000L
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var internalRecord: AudioRecord? = null
    private var microphoneRecord: AudioRecord? = null
    private var activeMode = "microphone"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> if (!running.get()) startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        activeMode = intent.getStringExtra(EXTRA_MODE).orEmpty().takeIf { it in setOf("internal", "microphone", "both") } ?: "microphone"
        startCaptureForeground(activeMode)
        running.set(true)
        worker = thread(name = "voz-native-capture") { captureLoop(intent) }
    }

    private fun startCaptureForeground(mode: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 9, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(if (mode == "microphone") "Microfone ativo" else if (mode == "both") "Áudio interno e microfone ativos" else "Áudio interno ativo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Encerrar", stopPendingIntent)
            .build()
        val serviceType = when (mode) {
            "microphone" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "both" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIFICATION_ID, notification, serviceType)
    }

    private fun captureLoop(intent: Intent) {
        val recordings = File(filesDir, "recordings").apply { mkdirs() }
        val output = File(recordings, "voz_${System.currentTimeMillis()}.m4a")
        var peak = 0
        try {
            if (activeMode != "microphone") {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: error("Autorização de captura ausente")
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error("Não foi possível iniciar a captura interna")
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (running.getAndSet(false)) CaptureEvents.status("stopping", "A autorização do Android foi encerrada. Finalizando o áudio…")
                    }
                }, Handler(Looper.getMainLooper()))
                mediaProjection = projection
                internalRecord = buildInternalRecord(projection)
            }
            if (activeMode != "internal") microphoneRecord = buildMicrophoneRecord()

            val internalQueue = internalRecord?.let { startReader(it, "internal") }
            val microphoneQueue = microphoneRecord?.let { startReader(it, "microphone") }
            CaptureEvents.status("recording", "Gravação Android em andamento. Reproduza o áudio e volte para encerrar.")
            val startedAt = System.currentTimeMillis()

            AacEncoder(output, SAMPLE_RATE).use { encoder ->
                while (running.get() && System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                    val internal = internalQueue?.poll(600, TimeUnit.MILLISECONDS)
                    val microphone = microphoneQueue?.poll(600, TimeUnit.MILLISECONDS)
                    val samples = when (activeMode) {
                        "internal" -> internal
                        "microphone" -> microphone
                        else -> if (internal != null && microphone != null) mix(internal, microphone) else internal ?: microphone
                    } ?: continue
                    for (sample in samples) peak = maxOf(peak, abs(sample.toInt()))
                    encoder.encode(samples)
                }
            }

            if (output.length() < 1_024 || peak < 12) {
                output.delete()
                CaptureEvents.status("error", "Não encontrei áudio capturável. O aplicativo que estava tocando pode ter bloqueado a gravação.")
            } else {
                CaptureEvents.status("transferring", "Gravação concluída. Enviando para a transcrição…")
                CaptureEvents.file(output, activeMode)
            }
        } catch (error: Throwable) {
            output.delete()
            CaptureEvents.status("error", error.message ?: "Não foi possível gravar o áudio do Android.")
        } finally {
            running.set(false)
            releaseRecords()
            runCatching { mediaProjection?.stop() }
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startReader(record: AudioRecord, label: String): ArrayBlockingQueue<ShortArray> {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Fonte $label indisponível" }
        val queue = ArrayBlockingQueue<ShortArray>(12)
        record.startRecording()
        thread(name = "voz-reader-$label") {
            while (running.get()) {
                val buffer = ShortArray(CHUNK_SAMPLES)
                val read = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrDefault(0)
                if (read > 0) {
                    val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (!queue.offer(data)) {
                        queue.poll()
                        queue.offer(data)
                    }
                }
            }
        }
        return queue
    }

    private fun buildInternalRecord(projection: MediaProjection): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferSize())
            .setAudioPlaybackCaptureConfig(capture)
            .build()
    }

    private fun buildMicrophoneRecord(): AudioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(audioFormat())
        .setBufferSizeInBytes(bufferSize())
        .build()

    private fun audioFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferSize(): Int = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK_SAMPLES * 8)

    private fun mix(first: ShortArray, second: ShortArray): ShortArray {
        val size = minOf(first.size, second.size)
        return ShortArray(size) { index -> ((first[index].toInt() + second[index].toInt()) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    private fun requestStop() {
        if (running.getAndSet(false)) CaptureEvents.status("stopping", "Finalizando e preparando a gravação…")
        releaseRecords()
    }

    private fun releaseRecords() {
        listOfNotNull(internalRecord, microphoneRecord).forEach { record ->
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        internalRecord = null
        microphoneRecord = null
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }
}
