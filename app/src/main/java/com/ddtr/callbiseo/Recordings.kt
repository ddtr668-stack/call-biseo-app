package com.ddtr.callbiseo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** Finds the call recordings the Galaxy phone app saves (Recordings/Call/) and turns them into call entries. */
object Recordings {

    fun canRead(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun canContacts(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    class Rec(
        val id: String, val uri: String, val name: String, val ts: Long,
        val dur: Long, val size: Long, val mime: String
    )

    fun scan(ctx: Context): List<Rec> {
        if (!canRead(ctx)) return emptyList()
        val paths = Prefs.recPaths(ctx)
        if (paths.isEmpty()) return emptyList()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Rec>()
        @Suppress("DEPRECATION")
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val sel = paths.joinToString(" OR ") { "$pathCol LIKE ?" }
        val args = paths.map { if (Build.VERSION.SDK_INT >= 29) "$it%" else "%/$it%" }.toTypedArray()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        try {
            ctx.contentResolver.query(base, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    val added = c.getLong(iAdded) * 1000L
                    val mod = c.getLong(iMod) * 1000L
                    val dur = c.getLong(iDur)
                    val size = c.getLong(iSize)
                    if (size in 1..2047) continue // empty / broken file
                    val ts = parseTime(name) ?: (if (mod > 0 && dur > 0) mod - dur else added)
                    out.add(Rec("ms_$id", ContentUris.withAppendedId(base, id).toString(), name, ts, dur, size, c.getString(iMime) ?: ""))
                }
            }
        } catch (e: Exception) {
            // permission revoked or provider error; try again next time
        }
        return out
    }

    // "통화 녹음 홍길동_241105_143012.m4a", "Call recording 01012345678_241105_143012.m4a"
    private val NAME_RE = Regex("""^(?:(?:통화\s*녹음|통화|call\s*recording|call|녹음)[\s_\-]+)?(.*?)_(\d{6})_(\d{6})(?:_\d+)?\.[A-Za-z0-9]+$""", RegexOption.IGNORE_CASE)

    val PREFIX_RE = Regex("""^(통화\s*녹음|통화|call\s*recording|녹음)[\s_\-]+""", RegexOption.IGNORE_CASE)

    private fun parseTime(file: String): Long? {
        val m = NAME_RE.find(file) ?: return null
        return try {
            SimpleDateFormat("yyMMddHHmmss", Locale.KOREA).parse(m.groupValues[2] + m.groupValues[3])?.time
        } catch (e: Exception) { null }
    }

    /** Who the call was with, from the file name: a saved contact name or a phone number. */
    fun parsePerson(file: String): String {
        val m = NAME_RE.find(file)
        val raw = (m?.groupValues?.get(1) ?: file.substringBeforeLast('.'))
            .replace(PREFIX_RE, "").trim()
        return raw.ifEmpty { "알 수 없음" }
    }

    fun isNumber(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() || it == '-' || it == '+' || it == ' ' } && s.count { it.isDigit() } >= 3

    fun contactName(ctx: Context, number: String): String? {
        if (!canContacts(ctx)) return null
        return try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    fun contactNumber(ctx: Context, name: String): String? {
        if (!canContacts(ctx)) return null
        return try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(name), null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null }
    }

    fun newCall(ctx: Context, r: Rec, status: String): JSONObject {
        val who = parsePerson(r.name)
        var contact = who
        var number = ""
        if (isNumber(who)) {
            number = who.filter { it.isDigit() || it == '+' }
            contact = contactName(ctx, number) ?: formatNumber(number)
        } else {
            number = contactNumber(ctx, who)?.filter { it.isDigit() || it == '+' } ?: ""
        }
        return JSONObject()
            .put("id", r.id).put("uri", r.uri).put("file", r.name)
            .put("contact", contact).put("number", number)
            .put("ts", r.ts).put("dur", r.dur / 1000L).put("size", r.size).put("mime", r.mime)
            .put("status", status).put("added", System.currentTimeMillis())
    }

    fun formatNumber(n: String): String {
        val d = n.filter { it.isDigit() }
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "${d.substring(0, 2)}-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            else -> n
        }
    }

    /** A recording shared from another app or picked by hand: copy it into the app so it stays readable. */
    fun importUri(ctx: Context, uri: Uri): JSONObject? {
        return try {
            var name = "녹음.m4a"
            var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size > 300L * 1024 * 1024) return null
            val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
            val ext = name.substringAfterLast('.', "m4a").take(5)
            val id = "f_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val f = File(dir, "$id.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return null
            if (f.length() < 2048) { f.delete(); return null }
            val dur = durationOf(f)
            val ts = parseTime(name) ?: System.currentTimeMillis()
            val mime = ctx.contentResolver.getType(uri) ?: ""
            val r = Rec(id, f.absolutePath, name, ts, dur, f.length(), mime)
            newCall(ctx, r, "new")
        } catch (e: Exception) { null }
    }

    private fun durationOf(f: File): Long = try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(f.absolutePath)
        val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release()
        d
    } catch (e: Exception) { 0L }

    fun readBytes(ctx: Context, uri: String): ByteArray? = try {
        if (uri.startsWith("/")) File(uri).readBytes()
        else ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    } catch (e: Exception) { null }

    fun exists(ctx: Context, uri: String): Boolean = try {
        if (uri.startsWith("/")) File(uri).exists()
        else ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    /** Gemini wants one of its own audio types. */
    fun geminiMime(fileName: String, mime: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m4a", "mp4", "3gp" -> "audio/m4a"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            "amr" -> "audio/amr"
            else -> if (mime.startsWith("audio/")) mime else "audio/m4a"
        }
    }
}
