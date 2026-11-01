package com.ddtr.callbiseo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import org.json.JSONObject

/** Plays a call recording inside the app. */
object Player {
    private var mp: MediaPlayer? = null
    private var cur: String = ""
    private var speed = 1f

    @Synchronized
    fun play(ctx: Context, uri: String, fromMs: Int): Boolean {
        try {
            if (uri != cur || mp == null) {
                stop()
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                if (uri.startsWith("/")) p.setDataSource(uri) else p.setDataSource(ctx, Uri.parse(uri))
                p.prepare()
                p.setOnCompletionListener { }
                mp = p
                cur = uri
            }
            val p = mp ?: return false
            if (fromMs >= 0) p.seekTo(fromMs)
            applySpeed(p)
            p.start()
            return true
        } catch (e: Exception) {
            stop()
            return false
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (e: Exception) { }
    }

    @Synchronized
    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.5f)
        val p = mp ?: return
        if (p.isPlaying) applySpeed(p)
    }

    @Synchronized
    fun pause() { try { mp?.pause() } catch (e: Exception) { } }

    @Synchronized
    fun seek(ms: Int) { try { mp?.seekTo(ms) } catch (e: Exception) { } }

    @Synchronized
    fun stop() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        cur = ""
    }

    @Synchronized
    fun state(): String {
        val p = mp ?: return JSONObject().put("uri", "").put("playing", false).toString()
        return try {
            JSONObject().put("uri", cur).put("playing", p.isPlaying)
                .put("pos", p.currentPosition).put("dur", p.duration).put("speed", speed.toDouble()).toString()
        } catch (e: Exception) {
            JSONObject().put("uri", "").put("playing", false).toString()
        }
    }
}
