package com.ddtr.callbiseo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AiException(
    val code: Int, message: String, val retry: Boolean = false,
    /** 하루 무료 한도를 다 쓴 경우 */ val daily: Boolean = false,
    /** 서버가 알려준 대기 시간(초), 모르면 0 */ val waitSec: Int = 0
) : Exception(message)

/** Google Gemini API over plain HTTPS (generateContent + Files API). */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com"
    private const val INLINE_MAX = 14L * 1024 * 1024

    class Res(val code: Int, val body: String, val headers: Map<String, List<String>>)

    fun http(
        method: String, url: String, key: String, body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        extra: Map<String, String> = emptyMap(), readTimeout: Int = 300_000
    ): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = readTimeout
            c.requestMethod = method
            if (key.isNotEmpty()) c.setRequestProperty("x-goog-api-key", key)
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = try { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
            return Res(code, text, c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() })
        } finally {
            c.disconnect()
        }
    }

    fun errorOf(r: Res): AiException {
        var msg = ""
        var quotaId = ""
        var quotaValue = ""
        var waitSec = 0
        try {
            val err = JSONObject(r.body).optJSONObject("error")
            msg = err?.optString("message") ?: ""
            val det = err?.optJSONArray("details")
            if (det != null) for (i in 0 until det.length()) {
                val d = det.optJSONObject(i) ?: continue
                val v = d.optJSONArray("violations")?.optJSONObject(0)
                if (v != null && quotaId.isEmpty()) { quotaId = v.optString("quotaId"); quotaValue = v.optString("quotaValue") }
                val delay = d.optString("retryDelay")
                if (delay.endsWith("s")) waitSec = delay.removeSuffix("s").toDoubleOrNull()?.toInt() ?: 0
            }
        } catch (e: Exception) { }
        if (r.code == 429) {
            val free = quotaId.contains("FreeTier", true) || msg.contains("free", true)
            val daily = quotaId.contains("PerDay", true)
            val tokens = quotaId.contains("Token", true)
            val kind = if (free) "무료 " else ""
            val text = when {
                daily -> "오늘 쓸 수 있는 ${kind}AI 사용량${if (quotaValue.isNotEmpty()) "(하루 ${quotaValue}번)" else ""}을 다 썼어요. 매일 오후 5시쯤 다시 채워져요. 결제 정보를 등록하면 한도가 크게 늘어나요."
                tokens -> "1분 동안 AI에 보낼 수 있는 ${kind}양을 넘었어요. 1분쯤 뒤에 다시 해 주세요."
                else -> "1분 동안 쓸 수 있는 ${kind}AI 횟수${if (quotaValue.isNotEmpty()) "(분당 ${quotaValue}번)" else ""}를 넘었어요. 잠깐 뒤에 다시 해 주세요."
            }
            return AiException(429, text, true, daily, waitSec)
        }
        val friendly = when {
            r.code == 400 && msg.contains("API key", true) -> "API 키가 올바르지 않아요. 설정에서 다시 확인해 주세요."
            r.code == 403 -> "API 키에 권한이 없어요 ($msg)"
            r.code == 404 -> "AI 모델을 찾을 수 없어요. 설정에서 모델을 다시 골라 주세요."
            r.code >= 500 -> "AI 서버가 바빠요. 잠시 뒤 다시 시도해요."
            else -> "AI 오류 (${r.code}) $msg".trim()
        }
        return AiException(r.code, friendly, r.code >= 500)
    }

    /** 무료 한도가 다시 채워지는 때: 미국 태평양 시간 자정 */
    fun nextReset(): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 5)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Model names that can generateContent, newest first. */
    fun listModels(key: String): List<String> {
        val out = ArrayList<String>()
        var page = ""
        repeat(5) {
            val r = http("GET", "$BASE/v1beta/models?pageSize=200" + (if (page.isNotEmpty()) "&pageToken=$page" else ""), key)
            if (r.code != 200) throw errorOf(r)
            val o = JSONObject(r.body)
            val arr = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (!methods.contains("generateContent")) continue
                val name = m.optString("name").removePrefix("models/")
                if (name.startsWith("gemini")) out.add(name)
            }
            page = o.optString("nextPageToken", "")
            if (page.isEmpty()) return out.sortedWith(modelOrder)
        }
        return out.sortedWith(modelOrder)
    }

    private val VER = Regex("""^gemini-(\d+(?:\.\d+)?)-""")

    /** Plain "flash" models first (good + cheap for audio), then by version, newest first. */
    private val modelOrder = Comparator<String> { a, b ->
        val sa = score(a); val sb = score(b)
        if (sa != sb) sb.compareTo(sa) else b.compareTo(a)
    }

    private fun score(n: String): Double {
        val v = VER.find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val bad = listOf("tts", "image", "live", "embedding", "robotics", "audio", "transcribe", "computer", "exp", "native")
        if (bad.any { n.contains(it) }) return v - 100
        var s = v
        if (Regex("""^gemini-[\d.]+-flash$""").matches(n)) s += 50
        else if (n.contains("flash") && !n.contains("lite") && !n.contains("preview")) s += 40
        else if (n.contains("flash") && !n.contains("lite")) s += 30
        else if (n.contains("flash-lite")) s += 20
        else if (n.contains("pro")) s += 10
        return s
    }

    /** Model from settings, or the best one the key can use (remembered). */
    fun model(ctx: android.content.Context, key: String): String {
        val m = Prefs.model(ctx)
        if (m.isNotEmpty()) return m
        val found: String? = try {
            listModels(key).firstOrNull()
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val best = found ?: "gemini-flash-latest"
        Prefs.of(ctx).edit().putString("model", best).apply()
        return best
    }

    fun generate(key: String, model: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        var r = http("POST", "$BASE/v1beta/models/$model:generateContent", key, bytes)
        if (r.code == 429) {
            val e = errorOf(r)
            if (!e.daily && e.waitSec in 1..45) {
                Thread.sleep(e.waitSec * 1000L + 1500)
                r = http("POST", "$BASE/v1beta/models/$model:generateContent", key, bytes)
            }
        }
        if (r.code != 200) throw errorOf(r)
        return JSONObject(r.body)
    }

    fun textOf(resp: JSONObject): String {
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null) {
            val block = resp.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            throw AiException(0, if (block.isNotEmpty()) "AI가 이 녹음을 처리하지 않았어요 ($block)" else "AI 응답이 비어 있어요.")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    fun finishReason(resp: JSONObject): String =
        resp.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") ?: ""

    /** Audio as a request part: inline when small, otherwise through the Files API. Returns (part, uploadedFileName). */
    fun audioPart(key: String, bytes: ByteArray, mime: String): Pair<JSONObject, String?> {
        if (bytes.size <= INLINE_MAX) {
            val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return Pair(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)), null)
        }
        val up = upload(key, bytes, mime)
        return Pair(JSONObject().put("fileData", JSONObject().put("mimeType", mime).put("fileUri", up.first)), up.second)
    }

    private fun upload(key: String, bytes: ByteArray, mime: String): Pair<String, String> {
        val start = http(
            "POST", "$BASE/upload/v1beta/files", key,
            JSONObject().put("file", JSONObject().put("display_name", "call")).toString().toByteArray(),
            extra = mapOf(
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to bytes.size.toString(),
                "X-Goog-Upload-Header-Content-Type" to mime
            )
        )
        if (start.code !in 200..299) throw errorOf(start)
        val upUrl = start.headers["x-goog-upload-url"]?.firstOrNull()
            ?: throw AiException(0, "녹음 파일을 올리지 못했어요.", true)
        val fin = http(
            "POST", upUrl, key, bytes, contentType = mime,
            extra = mapOf("X-Goog-Upload-Offset" to "0", "X-Goog-Upload-Command" to "upload, finalize")
        )
        if (fin.code !in 200..299) throw errorOf(fin)
        val f = JSONObject(fin.body).optJSONObject("file") ?: throw AiException(0, "녹음 파일을 올리지 못했어요.", true)
        val name = f.optString("name")
        var state = f.optString("state")
        var tries = 0
        while (state == "PROCESSING" && tries < 60) {
            Thread.sleep(2000)
            val g = http("GET", "$BASE/v1beta/$name", key)
            if (g.code == 200) state = JSONObject(g.body).optString("state")
            tries++
        }
        if (state == "FAILED") throw AiException(0, "AI가 녹음 파일을 읽지 못했어요.")
        return Pair(f.optString("uri"), name)
    }

    fun deleteFile(key: String, name: String?) {
        if (name.isNullOrEmpty()) return
        try { http("DELETE", "$BASE/v1beta/$name", key) } catch (e: Exception) { }
    }

    /** JSON from model text, tolerating ```json fences or text around it. */
    fun parseJson(text: String): JSONObject? {
        val t = text.trim()
        try { return JSONObject(t) } catch (e: Exception) { }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) try { return JSONObject(t.substring(a, b + 1)) } catch (e: Exception) { }
        return null
    }
}
