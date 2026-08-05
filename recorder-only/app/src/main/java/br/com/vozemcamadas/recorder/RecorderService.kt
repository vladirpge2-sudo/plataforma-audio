package br.com.vozemcamadas.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "br.com.vozemcamadas.recorder.START"
        const val ACTION_STOP = "br.com.vozemcamadas.recorder.STOP"
        const val ACTION_STATUS = "br.com.vozemcamadas.recorder.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_URI = "uri"

        private const val CHANNEL_ID = "internal_audio_capture"
        private const val NOTIFICATION_ID = 4107
        private const val SAMPLE_RATE = 48_000
        private const val CHUNK_SAMPLES = 4_800
        private const val MAX_DURATION_MS = 45L * 60L * 1_000L
    }

    private val running = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> if (running.compareAndSet(false, true)) startRecording(intent)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        startCaptureForeground()
        thread(name = "internal-audio-recorder") {
            captureLoop(intent)
        }
    }

    private fun startCaptureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, RecorderService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Gravando o áudio permitido pelo Android")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Encerrar", stopPendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun captureLoop(intent: Intent) {
        val tempFile = File(cacheDir, "internal-${System.currentTimeMillis()}.m4a")
        var peak = 0
        var totalSamples = 0L
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                ?: error("A autorização de captura não chegou ao gravador")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("O Android não iniciou a captura")
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (running.getAndSet(false)) {
                        sendStatus("stopping", "O Android encerrou a autorização. Finalizando o arquivo…")
                    }
                }
            }, Handler(Looper.getMainLooper()))
            mediaProjection = projection

            val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 8))
                .setAudioPlaybackCaptureConfig(configuration)
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) { "A fonte de áudio interno está indisponível" }
            audioRecord = record
            record.startRecording()
            sendStatus("recording", "Gravando. Abra outro aplicativo, reproduza o áudio e depois volte para encerrar.")
            val startedAt = System.currentTimeMillis()

            AacEncoder(tempFile, SAMPLE_RATE).use { encoder ->
                while (running.get() && System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                    val buffer = ShortArray(CHUNK_SAMPLES)
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    val samples = if (read == buffer.size) buffer else buffer.copyOf(read)
                    samples.forEach { peak = maxOf(peak, abs(it.toInt())) }
                    totalSamples += read
                    encoder.encode(samples)
                }
            }

            if (tempFile.length() < 2_048L || peak < 16 || totalSamples < SAMPLE_RATE / 2) {
                tempFile.delete()
                sendStatus(
                    "error",
                    "Nenhum áudio interno capturável foi encontrado. O aplicativo que tocava o som pode ter bloqueado a gravação.",
                )
            } else {
                val saved = saveToDownloads(tempFile)
                sendStatus(
                    "saved",
                    "Áudio salvo em Downloads/Voz em Camadas. Agora abra o Voz em Camadas e envie o arquivo.",
                    saved,
                )
            }
        } catch (cause: Throwable) {
            tempFile.delete()
            sendStatus("error", cause.message ?: "Não foi possível gravar o áudio interno")
        } finally {
            running.set(false)
            releaseResources()
            tempFile.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveToDownloads(source: File): Uri {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "audio-interno-$timestamp.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Voz em Camadas")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o arquivo em Downloads")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Não foi possível gravar o arquivo em Downloads")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (cause: Throwable) {
            contentResolver.delete(uri, null, null)
            throw cause
        }
    }

    private fun sendStatus(state: String, message: String, uri: Uri? = null) {
        val broadcast = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            if (uri != null) putExtra(EXTRA_URI, uri.toString())
        }
        sendBroadcast(broadcast)
    }

    private fun stopRecording() {
        if (running.getAndSet(false)) sendStatus("stopping", "Finalizando e salvando o áudio…")
        releaseAudioRecord()
    }

    private fun releaseAudioRecord() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun releaseResources() {
        releaseAudioRecord()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    override fun onDestroy() {
        running.set(false)
        releaseResources()
        super.onDestroy()
    }
}
