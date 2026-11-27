package com.ddtr.callbiseo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 전화가 올 때: 그 번호(또는 연락처 이름)와 했던 마지막 통화의 요약을 화면 위 팝업으로 띄운다.
 * 통화가 끝나면(IDLE) 팝업을 닫는다.
 */
class IncomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val ctx = context.applicationContext
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!Incoming.enabled(ctx) || !Popup.allowed(ctx)) return
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return // 번호 없는 첫 방송은 무시
                if (number.isBlank()) return
                val pending = goAsync()
                Thread {
                    try {
                        val c = Incoming.lastCallWith(ctx, number)
                        if (c != null) Popup.show(ctx, c, incoming = true)
                    } catch (e: Exception) {
                    } finally { pending.finish() }
                }.start()
            }
            // 전화를 받으면: 설정한 시간(바로·5초·10초·20초) 뒤에 닫기, '통화 끝날 때'면 그대로
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Prefs.of(ctx).edit().putLong("offhookAt", System.currentTimeMillis()).apply()
                val sec = Prefs.of(ctx).getString("incomingClose", "10") ?: "10"
                sec.toIntOrNull()?.let { Popup.hideIncomingAfter(ctx, it * 1000L) }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Popup.hideIncoming(ctx)
                // 통화를 했다가 끊은 경우(받은 전화·건 전화 모두): 녹음 파일이 저장되는 대로 바로 요약
                val p = Prefs.of(ctx)
                val at = p.getLong("offhookAt", 0L)
                if (at > 0L) {
                    p.edit().putLong("offhookAt", 0L).putLong("callEndAt", System.currentTimeMillis()).apply()
                    ScanJob.callEnded(ctx)
                }
            }
        }
    }
}

object Incoming {
    fun allowed(ctx: Context): Boolean =
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun enabled(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("incomingPopup", true) && allowed(ctx)

    /** 0으로 시작하는 국내 번호로 맞춘 숫자 (+82 10 → 010) */
    fun norm(n: String): String {
        var d = n.filter { it.isDigit() }
        if (d.startsWith("82") && d.length >= 11) d = "0" + d.substring(2)
        return d
    }

    private fun same(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return x.length >= 8 && y.length >= 8 && x.takeLast(8) == y.takeLast(8)
    }

    /** 이 번호와 했던 통화 중 요약이 끝난 가장 최근 것. 없으면 null */
    fun lastCallWith(ctx: Context, number: String): JSONObject? {
        val name = Recordings.contactName(ctx, number)
        // 차단 목록(요약 안 할 사람)에 있으면 팝업도 띄우지 않는다: 번호·연락처 이름 둘 다 확인
        if (Prefs.isSkipped(ctx, name ?: "", number) || Prefs.isSkipped(ctx, Recordings.formatNumber(norm(number)), number)) return null
        val arr = JSONArray(Store.allJson(ctx))
        var best: JSONObject? = null
        var count = 0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val hit = same(c.optString("number"), number) ||
                (name != null && c.optString("contact") == name) ||
                (Recordings.isNumber(c.optString("contact")) && same(c.optString("contact"), number))
            if (!hit) continue
            if (c.optString("status") == "skip" || Prefs.isSkipped(ctx, c.optString("contact"), c.optString("number"))) return null
            count++
            if (c.optString("status") != "done") continue
            if (best == null || c.optLong("ts") > best.optLong("ts")) best = c
        }
        return best?.let { JSONObject(it.toString()).put("_calls", count) }
    }
}
