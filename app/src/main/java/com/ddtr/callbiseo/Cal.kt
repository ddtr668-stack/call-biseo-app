package com.ddtr.callbiseo

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Adds events straight into the phone's calendars (the Google Calendar account on the phone syncs them),
 * so no extra Google sign-in is needed for the calendar.
 */
object Cal {
    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun list(ctx: Context): String {
        val out = JSONArray()
        if (!allowed(ctx)) return out.toString()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.put(
                        JSONObject().put("id", c.getLong(0)).put("name", c.getString(1) ?: "")
                            .put("account", c.getString(2) ?: "")
                            .put("color", String.format("#%06X", 0xFFFFFF and c.getInt(3)))
                            .put("primary", c.getInt(4) == 1)
                            .put("google", (c.getString(5) ?: "") == "com.google")
                    )
                }
            }
        } catch (e: Exception) { }
        return out.toString()
    }

    /** date YYYY-MM-DD, time HH:MM or "" for an all-day event. Returns the new event id or -1. */
    fun insert(ctx: Context, calId: Long, title: String, desc: String, date: String, time: String, minutes: Int, remind: Int): Long {
        if (!allowed(ctx)) return -1
        val parts = date.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return -1
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.DESCRIPTION, desc)
        val tm = time.split(":").mapNotNull { it.toIntOrNull() }
        if (tm.size == 2) {
            val tz = TimeZone.getDefault()
            val c = Calendar.getInstance(tz)
            c.clear()
            c.set(parts[0], parts[1] - 1, parts[2], tm[0], tm[1], 0)
            v.put(CalendarContract.Events.DTSTART, c.timeInMillis)
            v.put(CalendarContract.Events.DTEND, c.timeInMillis + minutes * 60_000L)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, tz.id)
        } else {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
            v.put(CalendarContract.Events.DTSTART, c.timeInMillis)
            v.put(CalendarContract.Events.DTEND, c.timeInMillis + 86_400_000L)
            v.put(CalendarContract.Events.ALL_DAY, 1)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1
            val id = ContentUris.parseId(uri)
            if (remind >= 0) {
                val r = ContentValues()
                r.put(CalendarContract.Reminders.EVENT_ID, id)
                r.put(CalendarContract.Reminders.MINUTES, if (tm.size == 2) remind else 15 * 60) // all-day: 9 a.m. the day before
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                try { ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r) } catch (e: Exception) { }
            }
            id
        } catch (e: Exception) { -1 }
    }

    fun exists(ctx: Context, eventId: Long): Boolean {
        if (!allowed(ctx)) return false
        return try {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.DELETED), null, null, null
            )?.use { c -> c.moveToFirst() && c.getInt(0) == 0 } ?: false
        } catch (e: Exception) { false }
    }
}
