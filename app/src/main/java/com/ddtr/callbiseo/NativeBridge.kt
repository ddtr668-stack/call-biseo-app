package com.ddtr.callbiseo

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Everything the page can ask the phone to do. */
class NativeBridge(private val act: MainActivity) {
    private val ctx: Context = act.applicationContext
    private val prefs = Prefs.of(ctx)
    private val pool = Executors.newFixedThreadPool(3)
    private val fileLock = Any()

    private fun done(id: String, status: Int, body: String) {
        act.js("window.__native&&window.__native.resolve(${JSONObject.quote(id)},$status,${JSONObject.quote(body)})")
    }

    fun toast(msg: String) {
        act.js("window.__native&&window.__native.toast(${JSONObject.quote(msg)})")
    }

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME

    // ---------- page's own data (거래처 메모, 대화 기록) ----------
    @JavascriptInterface
    fun loadData(): String = synchronized(fileLock) {
        val f = File(ctx.filesDir, "data.json")
        if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    @JavascriptInterface
    fun saveData(json: String) {
        synchronized(fileLock) {
            val target = File(ctx.filesDir, "data.json")
            val tmp = File(ctx.filesDir, "data.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(target)) { target.writeText(json, Charsets.UTF_8); tmp.delete() }
        }
    }

    // ---------- calls ----------
    @JavascriptInterface
    fun calls(): String = Store.allJson(ctx)

    @JavascriptInterface
    fun patchCall(id: String, json: String): Boolean =
        try { Store.patch(ctx, id, JSONObject(json)) } catch (e: Exception) { false }

    @JavascriptInterface
    fun removeCall(id: String) { Store.remove(ctx, id) }

    /** Summarise these calls (again). */
    @JavascriptInterface
    fun queue(idsJson: String) {
        val ids = try { JSONArray(idsJson) } catch (e: Exception) { JSONArray() }
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            val c = Store.get(ctx, id) ?: continue
            if (c.optString("status") == "working") continue
            Store.patch(ctx, id, JSONObject().put("status", "queued").put("error", "").put("attempts", 0))
        }
        when (Processor.canWork(ctx)) {
            "nokey" -> toast("설정에서 Gemini API 키를 먼저 넣어 주세요.")
            "offline" -> toast("인터넷이 연결되면 요약할게요.")
            "wifi" -> toast("와이파이에 연결되면 요약할게요.")
        }
        Processor.kick(ctx)
    }

    @JavascriptInterface
    fun refresh(id: String) {
        pool.execute {
            val n = try { Processor.sync(ctx) } catch (e: Exception) { 0 }
            Processor.requeue(ctx)
            Processor.kick(ctx)
            done(id, 200, n.toString())
        }
    }

    @JavascriptInterface
    fun workState(): String = JSONObject()
        .put("current", Processor.current ?: "")
        .put("block", Processor.canWork(ctx) ?: "")
        .put("canRead", Recordings.canRead(ctx))
        .toString()

    @JavascriptInterface
    fun pickAudio() {
        act.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                act.startActivityForResult(Intent.createChooser(i, "녹음 파일 선택"), MainActivity.REQ_AUDIO)
            } catch (e: Exception) { toast("파일 선택 창을 열 수 없어요.") }
        }
    }

    fun importUris(uris: List<Uri>, summarize: Boolean) {
        pool.execute {
            var last = ""
            var n = 0
            for (u in uris.take(30)) {
                val c = Recordings.importUri(ctx, u) ?: continue
                if (summarize) c.put("status", "queued")
                Store.put(ctx, c)
                last = c.getString("id"); n++
            }
            if (n == 0) { toast("녹음 파일을 가져오지 못했어요."); return@execute }
            toast("녹음 ${n}개를 가져왔어요")
            Processor.kick(ctx)
            if (n == 1) act.js("window.__native&&window.__native.openCall(${JSONObject.quote(last)})")
        }
    }

    // ---------- settings ----------
    private val boolKeys = setOf("auto", "wifiOnly", "popup")

    @JavascriptInterface
    fun settings(): String {
        val o = JSONObject()
        o.put("apiKey", Prefs.apiKey(ctx))
        o.put("model", Prefs.model(ctx))
        o.put("auto", Prefs.auto(ctx))
        o.put("wifiOnly", Prefs.wifiOnly(ctx))
        o.put("popup", Prefs.popup(ctx))
        o.put("recPath", prefs.getString("recPath", Prefs.DEFAULT_PATHS))
        o.put("myInfo", Prefs.myInfo(ctx))
        o.put("calId", prefs.getString("calId", ""))
        o.put("remindMin", prefs.getString("remindMin", "30"))
        o.put("taskAccount", prefs.getString("taskAccount", ""))
        o.put("taskList", prefs.getString("taskList", ""))
        o.put("taskListName", prefs.getString("taskListName", ""))
        o.put("autoSince", Prefs.autoSince(ctx))
        return o.toString()
    }

    @JavascriptInterface
    fun setSetting(key: String, value: String) {
        val e = prefs.edit()
        if (key in boolKeys) e.putBoolean(key, value == "true") else e.putString(key, value.trim())
        e.apply()
        if (key == "recPath") pool.execute { Processor.sync(ctx) }
        if (key == "apiKey" || key == "wifiOnly") Processor.kick(ctx)
    }

    // ---------- AI ----------
    @JavascriptInterface
    fun aiModels(id: String) {
        pool.execute {
            try {
                done(id, 200, JSONArray(Gemini.listModels(Prefs.apiKey(ctx))).toString())
            } catch (e: AiException) { done(id, -3, e.message ?: "") } catch (e: IOException) { done(id, -1, "") } catch (e: Exception) { done(id, -3, e.message ?: "") }
        }
    }

    /** Free-form request for the assistant chat; body is a generateContent request. */
    @JavascriptInterface
    fun ai(id: String, body: String) {
        pool.execute {
            try {
                val key = Prefs.apiKey(ctx)
                if (key.isEmpty()) { done(id, -3, "설정에서 Gemini API 키를 먼저 넣어 주세요."); return@execute }
                val model = Gemini.model(ctx, key)
                val resp = Gemini.generate(key, model, JSONObject(body))
                done(id, 200, Gemini.textOf(resp))
            } catch (e: AiException) { done(id, -3, e.message ?: "") } catch (e: IOException) { done(id, -1, "") } catch (e: Exception) { done(id, -3, e.message ?: "") }
        }
    }

    // ---------- permissions ----------
    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun perms(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("audio", Recordings.canRead(ctx))
            .put("contacts", granted(Manifest.permission.READ_CONTACTS))
            .put("calendar", Cal.allowed(ctx))
            .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            .put("battery", pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)
            .put("overlay", Popup.allowed(ctx))
            .toString()
    }

    @JavascriptInterface
    fun askPerm(kind: String) {
        val list = when (kind) {
            "audio" -> arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
            "calendar" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            "notif" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            "battery" -> { askBattery(); return }
            "overlay" -> {
                act.runOnUiThread {
                    try { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
                    catch (e: Exception) { openAppSettings() }
                }
                return
            }
            else -> emptyArray()
        }
        if (list.isEmpty()) return
        // asked before and refused for good -> only the settings screen can turn it on
        val asked = prefs.getBoolean("asked_$kind", false)
        if (asked && list.none { act.shouldShowRequestPermissionRationale(it) } && list.any { !granted(it) }) {
            toast("설정 › 권한에서 직접 허용해 주세요.")
            openAppSettings()
            return
        }
        prefs.edit().putBoolean("asked_$kind", true).apply()
        act.runOnUiThread { act.requestPermissions(list, MainActivity.REQ_PERM) }
    }

    fun onPermResult() {
        act.js("window.__native&&window.__native.permsChanged()")
        if (Recordings.canRead(ctx)) pool.execute { Processor.sync(ctx); Processor.kick(ctx) }
    }

    @JavascriptInterface
    fun openAppSettings() {
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            } catch (e: Exception) { }
        }
    }

    private fun askBattery() {
        act.runOnUiThread {
            try {
                @Suppress("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                act.startActivity(i)
            } catch (e: Exception) {
                try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { }
            }
        }
    }

    // ---------- calendar ----------
    @JavascriptInterface
    fun calendars(): String = Cal.list(ctx)

    @JavascriptInterface
    fun addEvent(id: String, json: String) {
        pool.execute {
            val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
            val calId = o.optLong("calId", -1)
            if (calId < 0) { done(id, -3, "설정에서 캘린더를 먼저 골라 주세요."); return@execute }
            val ev = Cal.insert(
                ctx, calId, o.optString("title"), o.optString("desc"), o.optString("date"), o.optString("time"),
                o.optInt("minutes", 60), o.optInt("remind", 30)
            )
            if (ev < 0) done(id, -3, "캘린더에 넣지 못했어요. 권한과 날짜를 확인해 주세요.") else done(id, 200, ev.toString())
        }
    }

    @JavascriptInterface
    fun openEvent(eventId: String) {
        val ev = eventId.toLongOrNull() ?: return
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev)))
            } catch (e: Exception) { toast("캘린더 앱을 열 수 없어요.") }
        }
    }

    @JavascriptInterface
    fun eventExists(eventId: String): Boolean = eventId.toLongOrNull()?.let { Cal.exists(ctx, it) } ?: false

    // ---------- Google Tasks (구글 할 일) ----------
    @Volatile private var consentOpen = false

    @JavascriptInterface
    fun tasksConnect() {
        act.runOnUiThread {
            try {
                val i = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
                act.startActivityForResult(i, MainActivity.REQ_ACCOUNT)
            } catch (e: Exception) { toast("계정 선택 창을 열 수 없어요.") }
        }
    }

    @JavascriptInterface
    fun tasksDisconnect() {
        prefs.edit().remove("taskAccount").remove("taskList").remove("taskListName").apply()
        act.js("window.__native&&window.__native.tasksChanged()")
    }

    fun onAccountPicked(resultCode: Int, data: Intent?) {
        val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (resultCode != Activity.RESULT_OK || email.isNullOrEmpty()) return
        prefs.edit().putString("pendingAccount", email).apply()
        pool.execute { verify(email) }
    }

    fun onConsent(resultCode: Int) {
        consentOpen = false
        val email = prefs.getString("pendingAccount", "") ?: ""
        if (email.isEmpty()) return
        if (resultCode == Activity.RESULT_OK) pool.execute { verify(email) }
        else toast("구글 권한을 허용해야 구글 할 일에 넣을 수 있어요.")
    }

    private fun verify(email: String) {
        try {
            GoogleAuthUtil.getToken(ctx, Account(email, "com.google"), SCOPE)
            prefs.edit().putString("taskAccount", email).remove("pendingAccount").apply()
            toast("$email 계정의 구글 할 일과 연결했어요")
            act.js("window.__native&&window.__native.tasksChanged()")
        } catch (e: UserRecoverableAuthException) {
            askConsent(e)
        } catch (e: GoogleAuthException) {
            toast(authMessage(e))
        } catch (e: IOException) {
            toast("인터넷 연결을 확인하세요.")
        }
    }

    private fun askConsent(e: UserRecoverableAuthException) {
        val intent = e.intent ?: return toast("구글 권한 확인 창을 열 수 없어요.")
        if (consentOpen) return
        consentOpen = true
        act.runOnUiThread {
            try { act.startActivityForResult(intent, MainActivity.REQ_CONSENT) }
            catch (x: Exception) { consentOpen = false; toast("구글 권한 확인 창을 열 수 없어요.") }
        }
    }

    @JavascriptInterface
    fun gRequest(id: String, method: String, url: String, body: String) {
        pool.execute {
            val r = doRequest(method, url, body)
            done(id, r.first, r.second)
        }
    }

    private fun doRequest(method: String, url: String, body: String): Pair<Int, String> {
        if (!url.startsWith("https://tasks.googleapis.com/")) return Pair(-3, "허용되지 않은 주소예요.")
        val email = prefs.getString("taskAccount", "") ?: ""
        if (email.isEmpty()) return Pair(-3, "구글 할 일 계정을 먼저 연결해 주세요.")
        var retried = false
        while (true) {
            val token: String = try {
                GoogleAuthUtil.getToken(ctx, Account(email, "com.google"), SCOPE)
            } catch (e: UserRecoverableAuthException) {
                prefs.edit().putString("pendingAccount", email).apply()
                askConsent(e); return Pair(-2, "")
            } catch (e: GoogleAuthException) {
                return Pair(-3, authMessage(e))
            } catch (e: IOException) {
                return Pair(-1, "")
            }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 25000
                val m = method.uppercase()
                if (m == "PATCH") {
                    c.requestMethod = "POST"
                    c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else c.requestMethod = m
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/json")
                if (body.isNotEmpty()) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = try { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
                c.disconnect()
                if (code == 401 && !retried) {
                    retried = true
                    try { GoogleAuthUtil.clearToken(ctx, token) } catch (e: Exception) { }
                    continue
                }
                return Pair(code, text)
            } catch (e: IOException) {
                return Pair(-1, "")
            }
        }
    }

    private fun authMessage(e: GoogleAuthException): String {
        val m = e.message ?: ""
        return when {
            m.contains("UNREGISTERED_ON_API_CONSOLE", true) || m.contains("UnregisteredOnApiConsole", true) ->
                "구글 클라우드에 이 앱을 아직 등록하지 않았어요. 등록하고 5분쯤 뒤에 다시 연결해 주세요."
            m.contains("BadUsername") || m.contains("BadAuthentication") -> "이 휴대폰에 없는 구글 계정이에요."
            m.contains("NeedPermission") -> "구글 권한을 허용해야 해요."
            else -> "구글 로그인에 실패했어요 ($m)"
        }
    }

    // ---------- audio ----------
    @JavascriptInterface
    fun audioPlay(uri: String, fromMs: Int): Boolean = Player.play(ctx, uri, fromMs)

    @JavascriptInterface
    fun audioPause() = Player.pause()

    @JavascriptInterface
    fun audioSeek(ms: Int) = Player.seek(ms)

    @JavascriptInterface
    fun audioStop() = Player.stop()

    @JavascriptInterface
    fun audioSpeed(s: String) = Player.setSpeed(s.toFloatOrNull() ?: 1f)

    @JavascriptInterface
    fun audioState(): String = Player.state()

    // ---------- share, copy, links, backup ----------
    @JavascriptInterface
    fun shareText(text: String) {
        act.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                act.startActivity(Intent.createChooser(i, "보내기"))
            } catch (e: Exception) { toast("보낼 앱을 찾지 못했어요.") }
        }
    }

    @JavascriptInterface
    fun copyText(text: String) {
        act.runOnUiThread {
            try {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("통화 요약", text))
                toast("복사했어요")
            } catch (e: Exception) { toast("복사하지 못했어요.") }
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (url.startsWith("https://")) act.runOnUiThread { act.openExternal(Uri.parse(url)) }
    }

    @JavascriptInterface
    fun saveFile(name: String, content: String) {
        act.runOnUiThread {
            act.pendingSave = content
            try {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
                act.startActivityForResult(i, MainActivity.REQ_SAVE)
            } catch (e: Exception) {
                act.pendingSave = null
                toast("파일 저장 창을 열 수 없어요.")
            }
        }
    }

    @JavascriptInterface
    fun importBackup(json: String): Int = try { Store.importBackup(ctx, JSONArray(json)) } catch (e: Exception) { -1 }

    @Volatile var pendingOpen: String? = null

    @JavascriptInterface
    fun takeLaunch(): String { val v = pendingOpen ?: ""; pendingOpen = null; return v }

    companion object {
        const val SCOPE = "oauth2:https://www.googleapis.com/auth/tasks"
    }
}
