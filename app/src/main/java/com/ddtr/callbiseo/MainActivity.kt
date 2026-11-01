package com.ddtr.callbiseo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    lateinit var web: WebView
    private lateinit var bridge: NativeBridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    var pendingSave: String? = null
    @Volatile var shown = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        web.setBackgroundColor(Color.parseColor(if (night) "#121715" else "#F6F7F2"))
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false

        bridge = NativeBridge(this)
        web.addJavascriptInterface(bridge, "Biseo")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "file") return false
                openExternal(u)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try {
                    startActivityForResult(Intent.createChooser(pick, "백업 파일 선택"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        Processor.ensureChannel(this)
        ScanJob.schedule(this)
        instance = this
        web.loadUrl("file:///android_asset/index.html")
        askNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Player.stop()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra("open_call")?.let { id ->
            i.removeExtra("open_call")
            bridge.pendingOpen = id
            js("window.__native&&window.__native.launch&&window.__native.launch()")
        }
        if (i.action == Intent.ACTION_SEND) {
            val u = streamOf(i)
            i.action = Intent.ACTION_MAIN
            if (u != null) bridge.importUris(listOf(u), true)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bridge.onPermResult()
    }

    fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
    }

    fun js(code: String) {
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val p = Prefs.of(this)
            if (!p.getBoolean("asked_notif", false)) {
                p.edit().putBoolean("asked_notif", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shown = true
        if (::web.isInitialized) {
            web.evaluateJavascript("window.__native&&window.__native.resumed&&window.__native.resumed()", null)
        }
    }

    override fun onPause() {
        shown = false
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.__onBack&&window.__onBack())?'1':'0'") { r ->
            if (r != "\"1\"") moveTaskToBack(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            REQ_ACCOUNT -> bridge.onAccountPicked(resultCode, data)
            REQ_CONSENT -> bridge.onConsent(resultCode)
            REQ_AUDIO -> if (resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
                else data.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) bridge.importUris(uris, true)
            }
            REQ_SAVE -> {
                val text = pendingSave
                pendingSave = null
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null && text != null) {
                    val ok = try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        true
                    } catch (e: Exception) { false }
                    bridge.toast(if (ok) "백업 파일을 저장했어요" else "백업 파일을 저장하지 못했어요")
                }
            }
        }
    }

    companion object {
        const val REQ_FILE = 11
        const val REQ_ACCOUNT = 12
        const val REQ_CONSENT = 13
        const val REQ_SAVE = 14
        const val REQ_PERM = 15
        const val REQ_AUDIO = 16
        @Volatile var instance: MainActivity? = null
    }
}
