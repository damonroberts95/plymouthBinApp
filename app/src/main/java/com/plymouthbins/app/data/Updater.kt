package com.plymouthbins.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Updater {
    private const val REPO = "damonroberts95/plymouthBinApp"
    private const val URL = "https://api.github.com/repos/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Update(val tag: String, val htmlUrl: String, val apkUrl: String, val notes: String)

    /** Fetch latest release; returns Update if it's newer than [currentVersion], else null. */
    fun checkLatest(currentVersion: String): Update? {
        val req = Request.Builder()
            .url(URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PlymouthBinsApp")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                val tag = j.optString("tag_name", "").trimStart('v')
                val htmlUrl = j.optString("html_url", "")
                val notes = j.optString("body", "").take(300)
                if (tag.isBlank()) return null
                if (!isNewer(tag, currentVersion)) return null
                val assets = j.optJSONArray("assets")
                var apkUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                Update(tag, htmlUrl, apkUrl, notes)
            }
        }.getOrNull()
    }

    /** semver-lite comparator: split on '.', compare numerics. Non-numeric parts compared lex. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: -1 }
        val b = current.split(".").map { it.toIntOrNull() ?: -1 }
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return false
    }
}
