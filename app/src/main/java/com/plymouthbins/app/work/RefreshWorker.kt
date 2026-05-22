package com.plymouthbins.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.BinApi
import com.plymouthbins.app.data.BinBootstrap
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.ScheduleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.i("Worker: refresh started")
        return try {
            val prefs = Prefs.current(applicationContext)
            if (prefs.uprn.isBlank() || prefs.collectiveKey.isBlank()) {
                AppLog.w("Worker: UPRN/key not set, skipping refresh")
                return Result.success()
            }
            val prev = ScheduleCache.read(applicationContext).associateBy { it.id() }
            val rows = fetchSchedule(applicationContext)
            ScheduleCache.write(applicationContext, rows)
            NotificationScheduler.reschedule(applicationContext, rows, prefs)
            notifyInProgressChanges(applicationContext, prev, rows)
            maybeEnqueueHourly(applicationContext, rows)
            AppLog.i("Worker: refresh done, ${rows.size} collections")
            Result.success()
        } catch (t: Throwable) {
            AppLog.e("Worker: refresh failed", t)
            Result.retry()
        }
    }

    private suspend fun notifyInProgressChanges(
        ctx: Context,
        prev: Map<String, BinCollection>,
        next: List<BinCollection>,
    ) {
        val today = LocalDate.now()
        val markedOut = Prefs.current(ctx).markedOut
        for (n in next) {
            if (n.date != today) continue
            if ("${n.dateString}|${n.wasteType}" in markedOut) continue
            val p = prev[n.id()]
            val justStarted = n.isInProgress && p?.isInProgress != true
            if (justStarted) {
                AppLog.i("Status change: ${n.wasteType} -> ${n.status}")
                NotificationScheduler.fireImmediate(
                    ctx,
                    title = "Bin truck active",
                    body = "${n.wasteType} — ${n.status}",
                    wasteType = n.wasteType,
                    date = n.dateString,
                )
            }
        }
    }

    private fun maybeEnqueueHourly(ctx: Context, rows: List<BinCollection>) {
        val today = LocalDate.now()
        val todayRows = rows.filter { it.date == today && !it.isCompleted }
        if (todayRows.isEmpty()) {
            AppLog.i("Hourly: no collection today, skipping cascade")
            return
        }
        if (todayRows.any { it.isInProgress }) {
            AppLog.i("Hourly: today in-progress detected, stopping cascade")
            return
        }
        val now = LocalTime.now()
        if (now.hour !in HOURLY_START..HOURLY_END) {
            AppLog.i("Hourly: outside ${HOURLY_START}:00-${HOURLY_END}:59 window, skipping")
            return
        }
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            HOURLY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req,
        )
        AppLog.i("Hourly: next refresh in 1h")
    }

    companion object {
        const val HOURLY_WORK_NAME = "bin_hourly_today"
        const val HOURLY_START = 6
        const val HOURLY_END = 14

        private const val CREDS_TTL_MS = 25L * 60_000L
        private const val EMPTY_THRESHOLD = 3
        private val fetchMutex = Mutex()

        suspend fun fetchSchedule(ctx: Context, forceBootstrap: Boolean = false): List<BinCollection> = withContext(Dispatchers.IO) {
            fetchMutex.withLock { fetchScheduleInner(ctx, forceBootstrap) }
        }

        private suspend fun fetchScheduleInner(ctx: Context, forceBootstrap: Boolean): List<BinCollection> {
            val prefs = Prefs.current(ctx)

            suspend fun fullBootstrap(): com.plymouthbins.app.data.BootstrapCreds {
                AppLog.i("Bootstrap: minimal WebView session capture")
                val creds = withContext(Dispatchers.Main) {
                    BinBootstrap.bootstrapMinimal(ctx)
                } ?: error("bootstrap failed")
                Prefs.setSavedCreds(ctx, creds.sid, creds.csrf, creds.cookieHeader)
                // Warm session by hitting LOOKUP_COLLECTIVE_KEY. This advances the form
                // server-side state so subsequent schedule POSTs return rows, and refreshes
                // the saved key if it rotated.
                if (prefs.uprn.isNotBlank()) {
                    runCatching { BinApi.fetchCollectiveKey(creds, prefs.uprn) }
                        .onSuccess { newKey ->
                            if (newKey.isNotBlank() && newKey != prefs.collectiveKey) {
                                AppLog.i("Bootstrap warmup: collectiveKey rotated, saving")
                                Prefs.setCollectiveKey(ctx, newKey)
                            } else {
                                AppLog.i("Bootstrap warmup: collectiveKey unchanged")
                            }
                        }
                        .onFailure { AppLog.w("Bootstrap warmup: key fetch failed (${it.message})") }
                }
                return creds
            }

            suspend fun tryFetch(creds: com.plymouthbins.app.data.BootstrapCreds): BinApi.FetchResult? = try {
                // Reload key in case warmup rotated it.
                val freshPrefs = Prefs.current(ctx)
                BinApi.fetchSchedule(
                    creds, prefs.uprn, freshPrefs.collectiveKey,
                    daysAhead = prefs.daysAhead,
                    cachedRelatedUprn = freshPrefs.cachedRelatedUprn,
                )
            } catch (t: Throwable) {
                AppLog.w("API call failed (${t.message})")
                null
            }

            val now = System.currentTimeMillis()
            val savedFresh = !forceBootstrap &&
                prefs.savedSid.isNotBlank() && prefs.savedCsrf.isNotBlank() &&
                prefs.savedCookieHeader.isNotBlank() &&
                (now - prefs.savedCredsAtMs) < CREDS_TTL_MS

            var result: BinApi.FetchResult? = null
            if (savedFresh) {
                val saved = com.plymouthbins.app.data.BootstrapCreds(
                    prefs.savedSid, prefs.savedCsrf, prefs.savedCookieHeader,
                )
                AppLog.i("Fast path: using saved creds aged ${(now - prefs.savedCredsAtMs)/1000}s")
                val fast = tryFetch(saved)
                if (fast != null && fast.rows.isNotEmpty()) {
                    // Persist any rotated csrf/cookies from saved fetch.
                    Prefs.setSavedCreds(ctx, saved.sid, saved.csrf, saved.cookieHeader)
                    result = fast
                } else {
                    AppLog.i("Fast path empty/failed, escalating to full bootstrap")
                    Prefs.clearSavedCreds(ctx)
                }
            }
            if (result == null) {
                val fresh = fullBootstrap()
                result = tryFetch(fresh) ?: error("API failed after full bootstrap")
            }

            val hasGenRecyc = result.rows.any {
                val s = it.wasteType.lowercase()
                "residual" in s || "general" in s || "recycl" in s
            }
            if (result.rows.isNotEmpty()) {
                Prefs.setLastRefreshAt(ctx, System.currentTimeMillis())
                // Cache resolved relatedUprn so future refreshes can skip the premise probe.
                if (result.relatedUprn.isNotBlank() && result.relatedUprn != prefs.cachedRelatedUprn) {
                    Prefs.setCachedRelatedUprn(ctx, result.relatedUprn)
                    AppLog.i("Cached relatedUprn=${result.relatedUprn}")
                }
                // Push new data to home-screen widgets.
                com.plymouthbins.app.data.ScheduleCache.write(ctx, result.rows)
                runCatching { com.plymouthbins.app.widget.updateWidgets(ctx) }
            }
            if (hasGenRecyc) {
                if (prefs.needsRecapture) Prefs.setNeedsRecapture(ctx, false)
                if (prefs.consecutiveEmpty != 0) Prefs.setConsecutiveEmpty(ctx, 0)
            } else if (prefs.uprn.isNotBlank()) {
                val next = prefs.consecutiveEmpty + 1
                Prefs.setConsecutiveEmpty(ctx, next)
                AppLog.w("Empty schedule fetch ($next consecutive)")
                if (next >= EMPTY_THRESHOLD && !prefs.needsRecapture) {
                    AppLog.w("$EMPTY_THRESHOLD consecutive empty fetches, flagging needs recapture")
                    Prefs.setNeedsRecapture(ctx, true)
                }
            }
            return result.rows
        }
    }
}
