package com.ddtr.callbiseo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Finds new recordings and turns each one into transcript + summary + actions with Gemini, one at a time. */
object Processor {
    @Volatile private var exec = Executors.newSingleThreadExecutor()
    /** 지금 요약 중인 통화를 언제 시작했는지 (멈춤 감지용) */
    @Volatile var busySince = 0L
    /** 대기열 작업이 마지막으로 움직인 때 (멈춤 감지용) */
    @Volatile var beat = 0L
    /** 작업 줄 번호: 멈춘 줄을 버리고 새 줄을 만들면 바뀐다 (버린 줄은 알아서 그만둠) */
    @Volatile private var gen = 0
    /** 하루 한도에 걸리면 그때까지는 대기열을 돌리지 않는다 */
    @Volatile var dailyLimitUntil = 0L
    /** 비서에게 질문하는 동안은 요약을 잠깐 쉬어서 한도를 비서에게 양보 */
    @Volatile var holdUntil = 0L
    /** 분당 한도·AI 서버 혼잡: 이때까지 쉬었다가 대기열을 이어서 돌린다 (오류로 표시하지 않음) */
    @Volatile var pauseUntil = 0L
    /** 월 지출 한도에 걸림: 이때까지 대기열을 쉬고, 그 뒤 한 번씩 다시 확인 (한도를 올리면 이어짐) */
    @Volatile var spendBlockUntil = 0L
    @Volatile var current: String? = null
        private set

    const val CHANNEL = "summary"
    const val CHANNEL_POP = "summary_pop"

    // ---------- finding recordings ----------

    /** Add recordings we have not seen yet. Returns how many were added. */
    @Synchronized
    fun sync(ctx: Context): Int {
        if (!Recordings.canRead(ctx)) return 0
        val recs = Recordings.scan(ctx)
        val known = Store.ids(ctx)
        val since = Prefs.autoSince(ctx) - 5 * 60_000L
        val auto = Prefs.auto(ctx)
        val fresh = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (r in recs) {
            seen.add(r.id)
            if (known.contains(r.id)) continue
            val call = Recordings.newCall(ctx, r, if (auto && r.ts >= since) "queued" else "new")
            if (Prefs.isSkipped(ctx, call.optString("contact"), call.optString("number"))) call.put("status", "skip")
            fresh.add(call)
        }
        Store.putAll(ctx, fresh)
        // recordings deleted from the phone: keep the summary, mark the audio as gone
        for (id in known) {
            if (!id.startsWith("ms_") || seen.contains(id)) continue
            val c = Store.get(ctx, id) ?: continue
            if (c.optBoolean("gone")) continue
            if (!Recordings.exists(ctx, c.optString("uri"))) Store.patch(ctx, id, JSONObject().put("gone", true))
        }
        return fresh.size
    }

    /** Put failed-but-retryable calls back in the queue once their wait is over. */
    fun requeue(ctx: Context) {
        val now = System.currentTimeMillis()
        val arr = JSONArray(Store.allJson(ctx))
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // 요약하다가 앱이 꺼져서 '요약하는 중'에 멈춘 통화: 다시 대기열로 (같은 통화가 3번 멈추면 포기)
            if (c.optString("status") == "working" && c.optString("id") != current) {
                val fields = if (c.optInt("starts") >= 3)
                    JSONObject().put("status", "error").put("retry", false)
                        .put("error", "요약하다가 여러 번 멈췄어요. 녹음이 너무 길거나 파일에 문제가 있을 수 있어요. '다시 요약'을 눌러 보세요.")
                else JSONObject().put("status", "queued")
                Store.patch(ctx, c.optString("id"), fields)
                continue
            }
            // 분당 한도·서버 혼잡으로 실패한 통화는 기다릴 필요 없이 바로 다시 대기열로 (예전 버전에서 남은 오류 포함)
            if (c.optString("status") == "error" && isBusyError(c.optString("error"))) {
                Store.patch(ctx, c.optString("id"), JSONObject().put("status", "queued").put("error", "").put("attempts", 0))
                continue
            }
            if (c.optString("status") == "error" && c.optBoolean("retry") &&
                c.optInt("attempts") < 4 && c.optLong("retryAt") <= now
            ) Store.patch(ctx, c.optString("id"), JSONObject().put("status", "queued"))
        }
    }

    fun isBusyError(msg: String): Boolean =
        msg.contains("1분 동안") || msg.contains("AI 서버가 바빠요")

    fun canWork(ctx: Context): String? {
        if (Prefs.apiKey(ctx).isEmpty()) return "nokey"
        if (System.currentTimeMillis() < dailyLimitUntil) return "daily"
        if (System.currentTimeMillis() < spendBlockUntil) return "spend"
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "offline"
        if (Prefs.wifiOnly(ctx) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "wifi"
        return null
    }

    /** Work through the queue in the background. */
    fun kick(ctx: Context): Future<*> {
        val app = ctx.applicationContext
        unstick(app)
        return exec.submit { drain(app) }
    }

    /**
     * 통화 하나가 15분 넘게 끝나지 않으면(응답 없는 연결 등) 그 통화는 뒤로 미루고
     * 새 작업 줄을 만들어 나머지 통화가 계속 요약되게 한다.
     */
    @Synchronized
    private fun unstick(ctx: Context) {
        val id = current ?: return
        if (busySince == 0L || System.currentTimeMillis() - busySince < 15 * 60_000L) return
        Store.patch(ctx, id, JSONObject().put("status", "queued").put("error", "")
            .put("notBefore", System.currentTimeMillis() + 30 * 60_000L)
            .put("waitNote", "요약이 너무 오래 걸려서 다른 통화 먼저 하고 다시 해요"))
        gen++
        try { exec.shutdown() } catch (e: Exception) { }
        exec = Executors.newSingleThreadExecutor()
        current = null
        busySince = 0L
    }

    private fun drain(ctx: Context) {
        val myGen = gen
        var guard = 0
        while (guard++ < 400 && myGen == gen) {
            if (canWork(ctx) != null) break
            beat = System.currentTimeMillis()
            while (System.currentTimeMillis() < holdUntil || System.currentTimeMillis() < pauseUntil) Thread.sleep(1000)
            val id = Store.nextQueued(ctx)
            if (id == null) {
                // 잠깐 미뤄 둔 통화가 곧 차례면 기다렸다가 이어서
                if (Store.soonestWait(ctx) in 1..6 * 60_000L) { Thread.sleep(15_000); continue }
                break
            }
            processOne(ctx, id, myGen)
            Thread.sleep(6000) // 분당 한도에 덜 걸리게 간격 두기
        }
    }

    // ---------- summarising ----------

    private fun processOne(ctx: Context, id: String, myGen: Int) {
        val call = Store.get(ctx, id) ?: return
        val key = Prefs.apiKey(ctx)
        current = id
        busySince = System.currentTimeMillis()
        Store.patch(ctx, id, JSONObject().put("status", "working").put("error", "")
            .put("starts", call.optInt("starts") + 1).put("workingAt", System.currentTimeMillis()))
        var uploaded: String? = null
        try {
            val bytes = Recordings.readBytes(ctx, call.optString("uri"))
                ?: throw AiException(0, "녹음 파일을 열 수 없어요. 파일이 지워졌거나 권한이 없어요.")
            if (bytes.size > 300 * 1024 * 1024) throw AiException(0, "녹음 파일이 너무 커요.")
            var model = Gemini.model(ctx, key)
            val mime = Recordings.geminiMime(call.optString("file"), call.optString("mime"))
            val part = Gemini.audioPart(key, bytes, mime)
            uploaded = part.second
            var result: JSONObject? = null
            var limitErr: AiException? = null
            var otherErr: AiException? = null
            var tried = 0
            var all429 = true
            var anyQuotaId = false
            for (m in listOf(model) + Gemini.backups(ctx, key, model)) {
                try {
                    result = ask(key, m, part.first, call, ctx, full = true)
                    model = m
                    break
                } catch (e: AiException) {
                    // 한도(429)·없는 모델(404)·그 모델이 못 받는 요청(400)이면 다음 모델로
                    if (e.spend) throw e // 지출 한도: 다른 모델도 똑같이 막힘
                    if (!Gemini.switchable(e)) throw e
                    tried++
                    if (e.code != 429) all429 = false
                    if (e.code == 429 && Regex("PerMinute|PerDay|PerProject|PerModel").containsMatchIn(e.detail)) anyQuotaId = true
                    if (e.code == 429) { if (limitErr == null) limitErr = e }
                    else if (otherErr == null) otherErr = e
                }
            }
            // 모두 실패: 한도 때문이면 한도 오류(하루 한도면 다시 채워진 뒤 자동 요약)를, 아니면 실제 오류를 보여 준다
            // 분당 한도는 모델마다 따로라서, 모든 모델이 이유 없이 429면 프로젝트 전체가 막힌 것(지출 한도·결제)
            if (result == null && tried >= 2 && all429 && !anyQuotaId)
                throw AiException(429, Gemini.SPEND_TEXT, true, spend = true, detail = limitErr?.detail ?: "")
            if (result == null) throw limitErr ?: otherErr ?: AiException(0, "요약하지 못했어요.", true)
            saveResult(ctx, id, result, model)
            notifyDone(ctx, id)
        } catch (e: AiException) {
            if (e.spend) {
                // 지출 한도: 오류로 띄우지 않고 대기열에 그대로, 30분마다 다시 확인
                Store.patch(ctx, id, JSONObject().put("status", "queued").put("error", "")
                    .put("waitNote", "지출 한도에 걸려 기다리는 중"))
                spendBlockUntil = System.currentTimeMillis() + 30 * 60_000L
            } else if (!e.daily && (e.code == 429 || e.code >= 500)) {
                // 분당 한도·서버 혼잡: 오류로 띄우지 않고 대기열에 두었다가 잠시 뒤 자동으로 다시 요약
                val hits = call.optInt("busyHits") + 1
                val waitMs = maxOf(e.waitSec * 1000L + 3000L, minOf(hits, 5) * 60_000L)
                if (hits >= 12) fail(ctx, id, call, "AI가 계속 바빠서 요약하지 못했어요. 나중에 자동으로 다시 해요.", true)
                else {
                    Store.patch(ctx, id, JSONObject().put("status", "queued").put("error", "").put("busyHits", hits)
                        .put("notBefore", System.currentTimeMillis() + waitMs)
                        .put("waitNote", "AI가 잠깐 바빠서 곧 이어서 요약해요" + (if (e.detail.isNotEmpty()) " (${e.detail.take(90)})" else "")))
                    pauseUntil = System.currentTimeMillis() + minOf(waitMs, 45_000L)
                }
            } else if (e.daily) {
                // 하루 한도: 다시 채워질 때까지 기다렸다가 자동으로 이어서 요약 (시도 횟수는 세지 않음)
                Store.patch(ctx, id, JSONObject().put("status", "error").put("error", e.message ?: "")
                    .put("retry", true).put("retryAt", Gemini.nextReset()))
                dailyLimitUntil = Gemini.nextReset()
            } else {
                // 원인 확인용으로 어느 모델에서 난 오류인지 붙인다
                val msg = (e.message ?: "오류") + (if (e.code == 400 && e.detail.isNotEmpty()) " [${e.detail}]" else "")
                fail(ctx, id, call, msg, e.retry || e.code == 400)
            }
        } catch (e: java.io.IOException) {
            fail(ctx, id, call, "인터넷 연결이 끊겼어요. 다시 시도할게요.", true)
        } catch (e: OutOfMemoryError) {
            fail(ctx, id, call, "녹음이 너무 길어서 처리하지 못했어요.", false)
        } catch (e: Exception) {
            fail(ctx, id, call, "처리 중 오류: ${e.message ?: e.javaClass.simpleName}", false)
        } catch (e: Throwable) {
            fail(ctx, id, call, "처리 중 오류: ${e.javaClass.simpleName}", false)
        } finally {
            Gemini.deleteFile(key, uploaded)
            if (myGen == gen) { current = null; busySince = 0L }
        }
    }

    private fun fail(ctx: Context, id: String, call: JSONObject, msg: String, retry: Boolean) {
        val attempts = call.optInt("attempts") + 1
        Store.patch(
            ctx, id, JSONObject().put("status", "error").put("error", msg)
                .put("retry", retry).put("attempts", attempts)
                .put("retryAt", System.currentTimeMillis() + attempts * 10 * 60_000L)
        )
    }

    private fun ask(key: String, model: String, audio: JSONObject, call: JSONObject, ctx: Context, full: Boolean): JSONObject {
        val prompt = prompt(ctx, call, full)
        // 이 모델에서 통했던 요청 모양을 기억해 두고 처음부터 그걸로 보낸다
        // (예전엔 매번 거절(400)당한 뒤 다시 보내서 요약이 두세 배 느렸다)
        // 1 = 응답 틀(schema) 빼기, 2 = 녹음 형식 이름 audio/aac, 4 = 생각(thinking) 설정 빼기
        val prefs = Prefs.of(ctx)
        val fmtKey = "fmt_$model"
        val alt = Gemini.altAudio(audio)
        val tries = listOf(0, 4, 5, 7).map { it or prefs.getInt(fmtKey, 0) }.distinct()
            .filter { (it and 2) == 0 || alt != null }
        var last: AiException? = null
        var resp: JSONObject? = null
        for (fmt in tries) {
            val cfg = JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json")
            if ((fmt and 1) == 0) cfg.put("responseSchema", schema())
            // 빠른 답: Gemini 3 이상은 생각을 짧게
            if ((fmt and 4) == 0 && Gemini.isThinker(model)) cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            val part = if ((fmt and 2) != 0 && alt != null) alt else audio
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part).put(JSONObject().put("text", prompt)))))
                .put("generationConfig", cfg)
            try {
                resp = Gemini.generate(key, model, body)
                if (fmt != prefs.getInt(fmtKey, 0)) prefs.edit().putInt(fmtKey, fmt).apply()
                break
            } catch (e: AiException) {
                if (e.code != 400 || e.message?.contains("API 키") == true) throw e
                last = e
            }
        }
        if (resp == null) throw last ?: AiException(0, "요약하지 못했어요.", true)
        val text = Gemini.textOf(resp)
        val json = Gemini.parseJson(text)
        if (json != null && json.has("summary")) return json
        if (full && Gemini.finishReason(resp) == "MAX_TOKENS") return ask(key, model, audio, call, ctx, false)
        throw AiException(0, "AI 답변을 읽지 못했어요. 다시 요약을 눌러 주세요.", true)
    }

    private fun prompt(ctx: Context, call: JSONObject, full: Boolean): String {
        val f = SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.KOREA)
        val callAt = f.format(Date(call.optLong("ts")))
        val today = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREA).format(Date())
        val me = Prefs.myInfo(ctx).ifEmpty { "영업 담당자" }
        val who = call.optString("contact").ifEmpty { "알 수 없음" }
        val transcriptRule = if (full)
            "- transcript: 통화 전체를 빠짐없이 받아쓰기. 말하는 사람이 바뀔 때마다 새 항목. s는 '나'(사용자) 또는 '상대'. 추임새·말더듬은 정리해도 되지만 숫자·금액·날짜·제품명은 들린 그대로."
        else
            "- transcript: 통화가 길어서 전체 대신 중요한 발언만 순서대로 30개 이내로 정리. s는 '나' 또는 '상대'."
        return """
너는 영업 담당자의 통화 비서야. 첨부한 통화 녹음을 듣고 정리해서 JSON으로만 답해.

[사용자(나)] $me
[통화 상대] $who (휴대폰 연락처 이름 또는 번호)
[통화 시각] $callAt
[오늘] $today

규칙
$transcriptRule
- title: 통화 핵심을 한 줄로 (25자 이내, 예: '3월 납품분 단가 재협의')
- category: 상담/견적/주문/납품/클레임/수금/일정/내부/개인/기타 중 하나
- company: 상대 회사·소속 (말에서 확인될 때만, 아니면 빈 문자열)
- counterpart: 상대 이름·직함 (확인될 때만)
- brief: 팝업에 띄울 아주 짧은 요약 정확히 2줄. 1줄은 무슨 얘기였는지, 2줄은 어떻게 하기로 했는지(결론). 각 줄 25자 안팎, 문장 끝맺음 없이 명사형·개조식으로(예: "선은 나중에, 판넬·공조기 고정 먼저 진행"). '나는/상대는' 같은 주어는 빼고, 숫자·날짜·제품명은 살린다.
- summary: 핵심 내용 3~6문장. 금액·수량·날짜·제품명·조건 같은 구체적인 숫자와 고유명사를 꼭 넣고, 누가 무엇을 요청/약속했는지 분명히.
- actions: 통화에서 나온 할 일과 약속.
  · type 'event' = 정해진 때의 약속(미팅·방문·통화 예정·납품일), 'todo' = 해야 할 일(견적서 보내기, 확인 후 회신 등)
  · owner: 해야 하는 사람 '나' / '상대' / '함께'
  · date: YYYY-MM-DD. '내일', '다음 주 화요일' 같은 말은 통화 시각 기준으로 계산. 날짜가 없으면 빈 문자열.
  · time: HH:MM (24시간). 없으면 빈 문자열.
  · title: 캘린더/할 일에 바로 넣을 수 있게 짧게 (예: 'OO상사 견적서 발송')
  · note: 필요한 세부 조건
  · 할 일이나 약속이 없으면 빈 배열.
- keywords: 나중에 찾기 좋은 단어 (제품명, 현장명, 금액 등) 6개 이내
- mood: 상대 반응 '긍정' / '보통' / '부정'
- 녹음에 대화가 거의 없으면 title을 '내용 없음'으로, summary는 한 문장으로.
- 모든 글은 한국어로.
""".trimIndent()
    }

    private fun s(desc: String? = null): JSONObject =
        JSONObject().put("type", "STRING").also { if (desc != null) it.put("description", desc) }

    private fun enumOf(vararg v: String): JSONObject = s().put("enum", JSONArray(v.toList()))

    private fun arr(items: JSONObject): JSONObject = JSONObject().put("type", "ARRAY").put("items", items)

    private fun obj(props: Map<String, JSONObject>, required: List<String>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "OBJECT").put("properties", p)
            .put("required", JSONArray(required)).put("propertyOrdering", JSONArray(props.keys.toList()))
    }

    private fun schema(): JSONObject = obj(
        linkedMapOf(
            "transcript" to arr(obj(linkedMapOf("s" to enumOf("나", "상대"), "t" to s()), listOf("s", "t"))),
            "title" to s(),
            "category" to enumOf("상담", "견적", "주문", "납품", "클레임", "수금", "일정", "내부", "개인", "기타"),
            "company" to s(),
            "counterpart" to s(),
            "summary" to arr(s()),
            "brief" to arr(s()),
            "actions" to arr(
                obj(
                    linkedMapOf(
                        "type" to enumOf("todo", "event"), "title" to s(), "owner" to enumOf("나", "상대", "함께"),
                        "date" to s(), "time" to s(), "note" to s()
                    ), listOf("type", "title", "owner", "date", "time")
                )
            ),
            "keywords" to arr(s()),
            "mood" to enumOf("긍정", "보통", "부정")
        ),
        listOf("transcript", "title", "category", "summary", "actions", "keywords")
    )

    private fun saveResult(ctx: Context, id: String, r: JSONObject, model: String) {
        val actions = JSONArray()
        val src = r.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until src.length()) {
            val a = src.optJSONObject(i) ?: continue
            if (a.optString("title").isBlank()) continue
            actions.put(
                JSONObject().put("id", "a${System.currentTimeMillis().toString(36)}$i")
                    .put("type", if (a.optString("type") == "event") "event" else "todo")
                    .put("title", a.optString("title").trim())
                    .put("owner", a.optString("owner", "나").ifEmpty { "나" })
                    .put("date", a.optString("date").trim().take(10))
                    .put("time", a.optString("time").trim().take(5))
                    .put("note", a.optString("note").trim())
                    .put("done", false)
            )
        }
        val fields = JSONObject()
            .put("status", "done").put("error", "").put("retry", false).put("attempts", 0).put("busyHits", 0).put("waitNote", "")
            .put("title", r.optString("title").trim())
            .put("category", r.optString("category", "기타"))
            .put("company", r.optString("company").trim())
            .put("counterpart", r.optString("counterpart").trim())
            .put("summary", r.optJSONArray("summary") ?: JSONArray())
            .put("brief", r.optJSONArray("brief") ?: JSONArray())
            .put("transcript", r.optJSONArray("transcript") ?: JSONArray())
            .put("actions", actions)
            .put("keywords", r.optJSONArray("keywords") ?: JSONArray())
            .put("mood", r.optString("mood"))
            .put("model", model)
            .put("processedAt", System.currentTimeMillis())
        Store.patch(ctx, id, fields)
    }

    // ---------- notifications ----------

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "통화 요약", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "통화 녹음을 요약하면 알려 드려요"
        nm.createNotificationChannel(ch)
        val pop = NotificationChannel(CHANNEL_POP, "통화 요약 (화면 위 알림)", NotificationManager.IMPORTANCE_HIGH)
        pop.description = "화면 위 팝업 권한이 없을 때 요약을 화면 위쪽에 띄워요"
        nm.createNotificationChannel(pop)
    }

    private fun notifyDone(ctx: Context, id: String) {
        val c = Store.get(ctx, id) ?: return
        if (MainActivity.instance?.shown == true) {
            // 앱을 보고 있으면 앱 안에서 팝업
            MainActivity.instance?.js("window.__native&&window.__native.summaryDone&&window.__native.summaryDone(${JSONObject.quote(id)})")
            return
        }
        ensureChannel(ctx)
        val wantPop = Prefs.popup(ctx)
        val overlay = wantPop && Popup.allowed(ctx)
        if (overlay) Popup.show(ctx, c)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val summary = c.optJSONArray("summary") ?: JSONArray()
        val lines = (0 until summary.length()).map { "• " + summary.optString(it) }
        val acts = c.optJSONArray("actions") ?: JSONArray()
        var mine = 0
        for (i in 0 until acts.length()) if (acts.optJSONObject(i)?.optString("owner") != "상대") mine++
        val big = lines.joinToString("\n") + (if (mine > 0) "\n\n✅ 할 일·약속 ${mine}건" else "")
        val title = c.optString("contact") + " · " + c.optString("title").ifEmpty { "통화 요약" }
        val open = Intent(ctx, MainActivity::class.java).putExtra("open_call", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val nid = 2000 + (id.hashCode() and 0xFFFF)
        val pi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, if (wantPop && !overlay) CHANNEL_POP else CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull()?.removePrefix("• ") ?: "")
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(nid, n) } catch (e: SecurityException) { }
    }
}
