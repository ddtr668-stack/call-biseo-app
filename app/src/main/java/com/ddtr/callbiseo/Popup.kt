package com.ddtr.callbiseo

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** "다른 앱 위에 표시": a summary card that pops up over whatever is on screen when a call has been summarised. */
object Popup {
    private var shownView: View? = null

    fun allowed(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, call: JSONObject) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try { build(app, call) } catch (e: Exception) { }
        }
    }

    fun hide(ctx: Context) {
        val v = shownView ?: return
        shownView = null
        try { ctx.applicationContext.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) { }
    }

    private fun build(ctx: Context, c: JSONObject) {
        if (!allowed(ctx)) return
        hide(ctx)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1A211E.toInt() else Color.WHITE
        val ink = if (night) 0xFFE4EBE7.toInt() else 0xFF1D2A26.toInt()
        val muted = if (night) 0xFF9AA8A1.toInt() else 0xFF66756E.toInt()
        val green = if (night) 0xFF5BBE9E.toInt() else 0xFF1F6B55.toInt()
        val rule = if (night) 0xFF2A3430.toInt() else 0xFFDCE3DC.toInt()
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 0) = TextView(ctx).apply {
            this.text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            setLineSpacing(0f, 1.15f)
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(14f))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), rule) }
            elevation = dp(12f).toFloat()
        }

        // drag handle: 끌어서 위치 옮기기
        val grip = View(ctx).apply { background = GradientDrawable().apply { setColor(rule); cornerRadius = dp(3f).toFloat() } }
        card.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8f))
            addView(grip, LinearLayout.LayoutParams(dp(40f), dp(5f)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -dp(6f) })

        // who + category
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(c.optString("contact"), 17f, ink, true, 1), LinearLayout.LayoutParams(0, -2, 1f))
        val cat = c.optString("category")
        if (cat.isNotEmpty()) {
            val tone = if (cat == "클레임") (if (night) 0xFFEB6A5E.toInt() else 0xFFC8372D.toInt()) else green
            head.addView(text(cat, 13f, tone, true).apply {
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = GradientDrawable().apply { cornerRadius = dp(7f).toFloat(); setStroke(dp(2f), tone) }
                rotation = -5f
            })
        }
        card.addView(head)
        val company = c.optString("company")
        card.addView(text(listOf(company, "통화 요약이 끝났어요").filter { it.isNotEmpty() }.joinToString(", "), 12.5f, muted, false, 1))

        card.addView(text(c.optString("title"), 16.5f, ink, true, 2).apply { setPadding(0, dp(10f), 0, dp(4f)) })

        val sum = c.optJSONArray("summary") ?: JSONArray()
        for (i in 0 until minOf(sum.length(), 4)) {
            card.addView(text("•  " + sum.optString(i), 14.5f, ink, false, 3).apply { setPadding(0, dp(3f), 0, 0) })
        }

        val acts = c.optJSONArray("actions") ?: JSONArray()
        val mine = (0 until acts.length()).mapNotNull { acts.optJSONObject(it) }.filter { it.optString("owner") != "상대" }
        if (mine.isNotEmpty()) {
            card.addView(View(ctx).apply { setBackgroundColor(rule) }, LinearLayout.LayoutParams(-1, dp(1f)).apply { topMargin = dp(12f); bottomMargin = dp(8f) })
            for (a in mine.take(3)) {
                val kind = if (a.optString("type") == "event") "약속" else "할 일"
                val whenTxt = whenLabel(a.optString("date"), a.optString("time"))
                card.addView(text("✓  " + a.optString("title") + (if (whenTxt.isNotEmpty()) "  ($kind, $whenTxt)" else "  ($kind)"), 14f, green, true, 2)
                    .apply { setPadding(0, dp(3f), 0, 0) })
            }
            if (mine.size > 3) card.addView(text("외 ${mine.size - 3}건", 13f, muted))
        }

        // buttons
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(12f), 0, 0) }
        fun btn(label: String, filled: Boolean, onTap: () -> Unit) = text(label, 15f, if (filled) Color.WHITE else ink, true).apply {
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                if (filled) setColor(green) else { setColor(Color.TRANSPARENT); setStroke(dp(1f), rule) }
            }
            setOnClickListener { onTap() }
        }
        row.addView(btn("닫기", false) { hide(ctx) })
        row.addView(View(ctx), LinearLayout.LayoutParams(dp(8f), 1))
        row.addView(btn("자세히 보기", true) {
            hide(ctx)
            val i = Intent(ctx, MainActivity::class.java).putExtra("open_call", c.optString("id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try { ctx.startActivity(i) } catch (e: Exception) { }
        })
        card.addView(row)

        val holder = LinearLayout(ctx).apply {
            setPadding(dp(14f), 0, dp(14f), 0)
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.y = 0
        holder.alpha = 0f
        wm.addView(holder, lp)
        shownView = holder

        val screenH = ctx.resources.displayMetrics.heightPixels
        fun clampY(y: Int) = y.coerceIn(dp(24f), maxOf(dp(24f), screenH - holder.height - dp(24f)))
        // 카드 높이를 안 뒤에 저장된 위치로 옮긴다
        holder.post {
            val prefs = Prefs.of(ctx)
            val h = holder.height
            lp.y = clampY(when (prefs.getString("popupPos", "bottom")) {
                "top" -> dp(48f)
                "center" -> (screenH - h) / 2
                "custom" -> (prefs.getFloat("popupY", 0.6f) * screenH).toInt()
                else -> screenH - h - dp(110f)
            })
            try { wm.updateViewLayout(holder, lp) } catch (e: Exception) { }
            holder.alpha = 1f
        }

        // 카드 빈 곳이나 손잡이를 끌면 위아래로 움직이고, 놓은 자리를 기억한다
        var downRaw = 0f
        var startY = 0
        var moved = false
        val slop = dp(6f)
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downRaw = e.rawY; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downRaw).toInt()
                    if (!moved && kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        lp.y = clampY(startY + dy)
                        try { wm.updateViewLayout(holder, lp) } catch (x: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) Prefs.of(ctx).edit().putString("popupPos", "custom")
                        .putFloat("popupY", lp.y.toFloat() / screenH).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun whenLabel(date: String, time: String): String {
        val p = date.split("-").mapNotNull { it.toIntOrNull() }
        var s = ""
        if (p.size == 3) {
            val today = Calendar.getInstance()
            val d = Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2]) }
            val t0 = Calendar.getInstance().apply { clear(); set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH)) }
            val diff = ((d.timeInMillis - t0.timeInMillis) / 86_400_000L).toInt()
            s = when (diff) { 0 -> "오늘"; 1 -> "내일"; 2 -> "모레"; else -> "${p[1]}/${p[2]}" }
        }
        val t = time.split(":").mapNotNull { it.toIntOrNull() }
        if (t.size == 2) s += (if (s.isEmpty()) "" else " ") + (if (t[0] < 12) "오전 " else "오후 ") + ((t[0] % 12).let { if (it == 0) 12 else it }) + (if (t[1] > 0) ":%02d".format(t[1]) else "시")
        return s
    }
}
