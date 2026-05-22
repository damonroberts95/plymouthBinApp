package com.plymouthbins.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.plymouthbins.app.data.AlarmLedger
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.BinCollection
import com.plymouthbins.app.data.NotifyPrefs
import com.plymouthbins.app.data.ScheduledAlarm
import com.plymouthbins.app.data.WasteType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object NotificationScheduler {

    private const val MAX_PENDING = 32

    fun fireImmediate(ctx: Context, title: String, body: String, wasteType: String = "") {
        val intent = android.content.Intent(ctx, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_FIRE_IMMEDIATE
            putExtra(NotificationReceiver.EXTRA_TITLE, title)
            putExtra(NotificationReceiver.EXTRA_BODY, body)
            if (wasteType.isNotBlank()) putExtra(NotificationReceiver.EXTRA_WASTE, wasteType)
        }
        ctx.sendBroadcast(intent)
    }

    fun reschedule(ctx: Context, collections: List<BinCollection>, prefs: NotifyPrefs) {
        cancelAll(ctx)
        if (!prefs.dayBefore && !prefs.sameDay) {
            AppLog.w("Scheduler: both day-before and same-day disabled, no alarms set")
            return
        }
        AppLog.i("Scheduler: scheduling alarms for ${collections.size} collections at ${"%02d:%02d".format(prefs.hour, prefs.minute)}")

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time = LocalTime.of(prefs.hour.coerceIn(0, 23), prefs.minute.coerceIn(0, 59))
        val today = LocalDate.now()
        var slot = 0

        collections.asSequence()
            .filter { !it.date.isBefore(today) }
            .filter { WasteType.category(it.wasteType).name !in prefs.disabledCategories }
            .forEach { c ->
                if (prefs.dayBefore) {
                    schedule(ctx, am, c, daysOffset = 1, time = time, slot = slot++, sameDay = false)
                }
                if (prefs.sameDay) {
                    schedule(ctx, am, c, daysOffset = 0, time = time, slot = slot++, sameDay = true)
                }
                if (slot >= MAX_PENDING) return
            }
    }

    private fun schedule(
        ctx: Context,
        am: AlarmManager,
        c: BinCollection,
        daysOffset: Long,
        time: LocalTime,
        slot: Int,
        sameDay: Boolean,
    ) {
        val fire = LocalDateTime.of(c.date.minusDays(daysOffset), time)
        val now = LocalDateTime.now()
        if (!fire.isAfter(now)) return
        val triggerAt = fire.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(ctx, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_FIRE
            putExtra(NotificationReceiver.EXTRA_WASTE, c.wasteType)
            putExtra(NotificationReceiver.EXTRA_DATE, c.dateString)
            putExtra(NotificationReceiver.EXTRA_SAME_DAY, sameDay)
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        AlarmLedger.add(
            ctx,
            ScheduledAlarm(
                fireAtEpochMs = triggerAt,
                title = if (sameDay) "Bins out today" else "Bins out tomorrow",
                waste = c.wasteType,
                collectionDate = c.dateString,
                sameDay = sameDay,
            ),
        )
    }

    private fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_PENDING) {
            val pi = PendingIntent.getBroadcast(
                ctx, i,
                Intent(ctx, NotificationReceiver::class.java).apply {
                    action = NotificationReceiver.ACTION_FIRE
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
        AlarmLedger.clear(ctx)
    }
}
