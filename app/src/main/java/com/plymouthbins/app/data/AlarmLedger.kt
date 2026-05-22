package com.plymouthbins.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledAlarm(
    val fireAtEpochMs: Long,
    val title: String,
    val waste: String,
    val collectionDate: String,
    val sameDay: Boolean,
)

object AlarmLedger {
    private const val FILE = "alarms_ledger.json"

    @Synchronized
    fun clear(ctx: Context) {
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write("[]".toByteArray()) }
    }

    @Synchronized
    fun add(ctx: Context, a: ScheduledAlarm) {
        val list = read(ctx).toMutableList()
        list += a
        write(ctx, list)
    }

    @Synchronized
    fun read(ctx: Context): List<ScheduledAlarm> {
        return try {
            val raw = ctx.openFileInput(FILE).bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        ScheduledAlarm(
                            fireAtEpochMs = o.optLong("fireAt"),
                            title = o.optString("title"),
                            waste = o.optString("waste"),
                            collectionDate = o.optString("date"),
                            sameDay = o.optBoolean("sameDay"),
                        )
                    )
                }
            }.sortedBy { it.fireAtEpochMs }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<ScheduledAlarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("fireAt", a.fireAtEpochMs)
                put("title", a.title)
                put("waste", a.waste)
                put("date", a.collectionDate)
                put("sameDay", a.sameDay)
            })
        }
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(arr.toString().toByteArray()) }
    }
}
