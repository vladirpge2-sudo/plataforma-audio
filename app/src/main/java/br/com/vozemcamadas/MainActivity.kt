package br.com.vozemcamadas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity(), CaptureEvents.Listener {
    companion object {
        private const val APP_URL = "https://4b10ccf0544154c98f.v2.appdeploy.ai/"
        private const val REQUEST_RECORD_AUDIO = 5101
        private const val REQUEST_MEDIA_PROJECTION = 5102
        private const val REQUEST_FILE = 5103
        private const val TRANSFER_CHUNK_SIZE = 180_000
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        webView = WebView(this)
        configureWebView(webView)
        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (savedInstanceState == null) webView.loadUrl(APP_URL) else webView.restoreState(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        CaptureEvents.attach(this)
    }

    override fun onStop() {
        CaptureEvents.detach(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "$userAgentString VozEmCamadasAndroid/1.0"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.addJavascriptInterface(NativeBridge(), "VozNative")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }
        view.webChromeClient = AppWebChromeClient()
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isNativeCaptureAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        @JavascriptInterface
        fun startRecording(mode: String) {
            runOnUiThread { prepareCapture(mode) }
        }

        @JavascriptInterface
        fun stopRecording() {
            val intent = Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            startService(intent)
        }
    }

    private inner class AppWebChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
            }
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            }
            startActivityForResult(intent, REQUEST_FILE)
            return true
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
            val popup = WebView(this@MainActivity)
            configureWebView(popup)
            popupWebView = popup
            root.addView(popup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            window?.let { root.removeView(it); it.destroy() }
            popupWebView = null
        }
    }

    private fun prepareCapture(modeValue: String) {
        val mode = modeValue.takeIf { it in setOf("internal", "microphone", "both") } ?: "microphone"
        pendingMode = mode
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        beginCapture(mode)
    }

    private fun beginCapture(mode: String) {
        if (mode == "microphone") {
            startCaptureService(mode, Activity.RESULT_OK, null)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onCaptureStatus("error", "A captura interna exige Android 10 ou superior.")
            return
        }
        onCaptureStatus("permission", "Autorize a captura na tela do Android.")
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun startCaptureService(mode: String, resultCode: Int, resultData: Intent?) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_MODE, mode)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) putExtra(CaptureService.EXTRA_RESULT_DATA, resultData)
        }
        startForegroundService(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val mode = pendingMode ?: "microphone"
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginCapture(mode)
            else {
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                onCaptureStatus("error", "Permissão de microfone negada. Libere-a nas configurações do aplicativo.")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) runCatching { startActivity(settingsIntent) }
            }
        }
    }

    @Deprecated("Legacy activity result is used to keep the project dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                val mode = pendingMode ?: "internal"
                if (resultCode == Activity.RESULT_OK && data != null) startCaptureService(mode, resultCode, data)
                else onCaptureStatus("cancelled", "A autorização de captura foi cancelada.")
            }
            REQUEST_FILE -> {
                val result = if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
                fileCallback?.onReceiveValue(result)
                fileCallback = null
            }
        }
    }

    override fun onCaptureStatus(state: String, message: String) {
        runOnUiThread { callJs("window.vozNativeStatus?.(${JSONObject.quote(state)},${JSONObject.quote(message)});") }
    }

    override fun onCaptureFile(file: File, mode: String) {
        onCaptureStatus("transferring", "Preparando a gravação para a transcrição…")
        thread(name = "voz-file-transfer") {
            try {
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val chunks = encoded.chunked(TRANSFER_CHUNK_SIZE)
                val sessionId = UUID.randomUUID().toString()
                runOnUiThread {
                    callJs("window.vozNativeAudioStart?.(${JSONObject.quote(sessionId)},${chunks.size},'audio/mp4',${JSONObject.quote(file.name)},${JSONObject.quote(mode)});") {
                        sendChunk(file, sessionId, chunks, 0)
                    }
                }
            } catch (error: Throwable) {
                file.delete()
                onCaptureStatus("error", error.message ?: "Não foi possível entregar a gravação ao aplicativo.")
            }
        }
    }

    private fun sendChunk(file: File, sessionId: String, chunks: List<String>, index: Int) {
        if (index >= chunks.size) {
            callJs("window.vozNativeAudioComplete?.(${JSONObject.quote(sessionId)});") { file.delete() }
            return
        }
        val script = "window.vozNativeAudioChunk?.(${JSONObject.quote(sessionId)},$index,${JSONObject.quote(chunks[index])});"
        callJs(script) { sendChunk(file, sessionId, chunks, index + 1) }
    }

    private fun callJs(script: String, done: (() -> Unit)? = null) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(script) { done?.invoke() }
    }

    override fun onBackPressed() {
        val popup = popupWebView
        when {
            popup != null -> {
                root.removeView(popup)
                popup.destroy()
                popupWebView = null
            }
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
