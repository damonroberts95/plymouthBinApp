package com.plymouthbins.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object ScheduleCache {
    private const val FILE = "schedule.json"

    fun write(ctx: Context, rows: List<BinCollection>) {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("date", r.dateString)
                put("waste", r.wasteType)
                put("status", r.status)
            })
        }
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(arr.toString().toByteArray()) }
    }

    fun read(ctx: Context): List<BinCollection> {
        return try {
            val raw = ctx.openFileInput(FILE).bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: continue
                    add(BinCollection(d, o.optString("waste"), o.optString("status")))
                }
            }.sortedBy { it.date }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
