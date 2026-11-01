package com.ddtr.callbiseo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings kept on the phone. */
object Prefs {
    private const val NAME = "callbiseo"
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = of(ctx).getString("apiKey", "") ?: ""
    fun model(ctx: Context): String = of(ctx).getString("model", "") ?: ""
    fun auto(ctx: Context): Boolean = of(ctx).getBoolean("auto", true)
    fun wifiOnly(ctx: Context): Boolean = of(ctx).getBoolean("wifiOnly", false)
    fun recPaths(ctx: Context): List<String> =
        (of(ctx).getString("recPath", DEFAULT_PATHS) ?: DEFAULT_PATHS)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.endsWith("/")) it else "$it/" }
    fun myInfo(ctx: Context): String = of(ctx).getString("myInfo", "") ?: ""

    /** Recordings made after this moment are summarised automatically. */
    fun autoSince(ctx: Context): Long {
        val p = of(ctx)
        var t = p.getLong("autoSince", 0L)
        if (t == 0L) {
            t = System.currentTimeMillis()
            p.edit().putLong("autoSince", t).apply()
        }
        return t
    }

    const val DEFAULT_PATHS = "Recordings/Call/,Call/"
}

/**
 * All calls live in one JSON file (files/calls.json): [{id, uri, contact, ts, status, summary, transcript, actions, ...}]
 * Only this object writes it, always under the lock.
 */
object Store {
    private val lock = Any()
    private var cache: LinkedHashMap<String, JSONObject>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "calls.json")

    private fun load(ctx: Context): LinkedHashMap<String, JSONObject> {
        cache?.let { return it }
        val map = LinkedHashMap<String, JSONObject>()
        val f = file(ctx)
        if (f.exists()) {
            val arr = try { JSONArray(f.readText(Charsets.UTF_8)) } catch (e: Exception) { JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotEmpty()) map[id] = o
            }
        }
        cache = map
        return map
    }

    private fun save(ctx: Context, map: LinkedHashMap<String, JSONObject>) {
        val arr = JSONArray()
        map.values.forEach { arr.put(it) }
        val target = file(ctx)
        val tmp = File(ctx.filesDir, "calls.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun allJson(ctx: Context): String = synchronized(lock) {
        val arr = JSONArray()
        load(ctx).values.forEach { arr.put(it) }
        arr.toString()
    }

    fun get(ctx: Context, id: String): JSONObject? = synchronized(lock) {
        load(ctx)[id]?.let { JSONObject(it.toString()) }
    }

    fun has(ctx: Context, id: String): Boolean = synchronized(lock) { load(ctx).containsKey(id) }

    fun ids(ctx: Context): Set<String> = synchronized(lock) { HashSet(load(ctx).keys) }

    fun put(ctx: Context, o: JSONObject) {
        synchronized(lock) {
            val map = load(ctx)
            map[o.getString("id")] = o
            save(ctx, map)
        }
        changed(o.getString("id"))
    }

    fun putAll(ctx: Context, list: List<JSONObject>) {
        if (list.isEmpty()) return
        synchronized(lock) {
            val map = load(ctx)
            list.forEach { map[it.getString("id")] = it }
            save(ctx, map)
        }
        changed("")
    }

    /** Merge the given top-level fields into a call. A null value removes the field. */
    fun patch(ctx: Context, id: String, fields: JSONObject): Boolean {
        synchronized(lock) {
            val map = load(ctx)
            val o = map[id] ?: return false
            val keys = fields.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "id") continue
                if (fields.isNull(k)) o.remove(k) else o.put(k, fields.get(k))
            }
            save(ctx, map)
        }
        changed(id)
        return true
    }

    fun remove(ctx: Context, id: String) {
        synchronized(lock) {
            val map = load(ctx)
            map.remove(id)?.let { o ->
                val path = o.optString("uri")
                if (path.startsWith("/")) File(path).takeIf { f -> f.absolutePath.startsWith(ctx.filesDir.absolutePath) }?.delete()
            }
            save(ctx, map)
        }
        changed("")
    }

    /** Oldest call waiting to be summarised. */
    fun nextQueued(ctx: Context): String? = synchronized(lock) {
        load(ctx).values.filter { it.optString("status") == "queued" }
            .minByOrNull { it.optLong("ts") }?.optString("id")
    }

    /** Restore from a backup: keep what is already here, add or update the rest. */
    fun importBackup(ctx: Context, arr: JSONArray): Int {
        var n = 0
        synchronized(lock) {
            val map = load(ctx)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val cur = map[id]
                if (cur == null || (cur.optString("status") != "done" && o.optString("status") == "done")) {
                    map[id] = o
                    n++
                }
            }
            save(ctx, map)
        }
        changed("")
        return n
    }

    private fun changed(id: String) {
        MainActivity.instance?.js("window.__native&&window.__native.callsChanged(${JSONObject.quote(id)})")
    }
}
