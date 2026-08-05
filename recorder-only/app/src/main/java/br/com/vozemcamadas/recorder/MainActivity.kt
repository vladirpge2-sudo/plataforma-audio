package br.com.vozemcamadas.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 7001
        private const val REQUEST_PROJECTION = 7002
        private const val VOZ_URL = "https://4b10ccf0544154c98f.v2.appdeploy.ai/"
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var shareButton: Button
    private var lastAudioUri: Uri? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(RecorderService.EXTRA_STATE).orEmpty()
            val message = intent?.getStringExtra(RecorderService.EXTRA_MESSAGE).orEmpty()
            val uri = intent?.getStringExtra(RecorderService.EXTRA_URI)?.let(Uri::parse)
            if (uri != null) {
                lastAudioUri = uri
                getPreferences(MODE_PRIVATE).edit().putString("last_audio_uri", uri.toString()).apply()
            }
            updateUi(state, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastAudioUri = getPreferences(MODE_PRIVATE).getString("last_audio_uri", null)?.let(Uri::parse)
        setContentView(createContent())
        updateUi("idle", "Toque em iniciar. O Android pedirá autorização para capturar o áudio permitido.")
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(RecorderService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun createContent(): View {
        val background = Color.rgb(244, 240, 231)
        val darkGreen = Color.rgb(23, 51, 45)
        val green = Color.rgb(20, 92, 74)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(background)
        }
        val title = TextView(this).apply {
            text = "Gravador Interno"
            textSize = 34f
            setTextColor(darkGreen)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Grave o som permitido pelo Android, salve em M4A e envie ao Voz em Camadas."
            textSize = 17f
            setTextColor(Color.rgb(70, 91, 84))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(darkGreen)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.WHITE)
        }
        startButton = makeButton("Iniciar gravação", green) { beginPermissionFlow() }
        stopButton = makeButton("Encerrar e salvar", Color.rgb(166, 47, 47)) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        }
        shareButton = makeButton("Compartilhar último áudio", Color.rgb(73, 84, 81)) { shareLastAudio() }
        val openVozButton = makeButton("Abrir Voz em Camadas", darkGreen) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VOZ_URL)))
        }
        val instructions = TextView(this).apply {
            text = "Como usar:\n\n1. Toque em Iniciar gravação.\n2. Autorize a captura na tela do Android.\n3. Abra o aplicativo que vai reproduzir o áudio.\n4. Volte e toque em Encerrar e salvar.\n5. Abra o Voz em Camadas e selecione o M4A em Downloads/Voz em Camadas.\n\nAlguns aplicativos, chamadas e conteúdos protegidos podem bloquear a captura do próprio áudio."
            textSize = 15f
            setTextColor(Color.rgb(65, 82, 77))
            setPadding(0, dp(24), 0, 0)
        }

        column.addView(title, matchWrap())
        column.addView(subtitle, matchWrap())
        column.addView(statusText, matchWrap())
        column.addView(startButton, buttonParams())
        column.addView(stopButton, buttonParams())
        column.addView(shareButton, buttonParams())
        column.addView(openVozButton, buttonParams())
        column.addView(instructions, matchWrap())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    private fun beginPermissionFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateUi("error", "Este gravador exige Android 10 ou superior.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestProjection()
        } else {
            updateUi("error", "A permissão de áudio foi negada. Ela é exigida pelo Android para a captura interna.")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, "Libere a permissão nas configurações do aplicativo.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    @Deprecated("Usado para manter o aplicativo pequeno e sem bibliotecas extras")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            updateUi("cancelled", "A autorização foi cancelada. Nenhuma gravação começou.")
            return
        }
        val service = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecorderService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(service)
        updateUi("starting", "Iniciando a gravação…")
    }

    private fun shareLastAudio() {
        val uri = lastAudioUri
        if (uri == null) {
            Toast.makeText(this, "Ainda não existe um áudio salvo.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar áudio"))
    }

    private fun updateUi(state: String, message: String) {
        statusText.text = message
        val recording = state in setOf("starting", "recording", "stopping")
        startButton.isEnabled = !recording
        stopButton.isEnabled = state == "recording" || state == "starting"
        shareButton.isEnabled = !recording && lastAudioUri != null
    }

    private fun makeButton(label: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(58),
    ).apply { topMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
