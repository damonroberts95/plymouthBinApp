package com.plymouthbins.app.work

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.plymouthbins.app.BinsApplication
import com.plymouthbins.app.R
import com.plymouthbins.app.data.AppLog
import com.plymouthbins.app.data.WasteType

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val waste = intent.getStringExtra(EXTRA_WASTE) ?: "Bin collection"
                val date = intent.getStringExtra(EXTRA_DATE) ?: ""
                val sameDay = intent.getBooleanExtra(EXTRA_SAME_DAY, false)
                val pretty = WasteType.pretty(waste)
                val title = ctx.getString(
                    if (sameDay) R.string.notify_title_same_day else R.string.notify_title_day_before
                )
                val body = if (sameDay) "$pretty — today ($date)" else "$pretty — tomorrow ($date)"
                show(ctx, (date + waste + sameDay).hashCode(), title, body, waste, date, sameDay)
            }
            ACTION_FIRE_IMMEDIATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Plymouth Bins"
                val rawBody = intent.getStringExtra(EXTRA_BODY) ?: ""
                val waste = intent.getStringExtra(EXTRA_WASTE) ?: ""
                val body = if (waste.isNotBlank()) {
                    rawBody.replace(waste, WasteType.pretty(waste))
                } else rawBody
                show(ctx, (title + body).hashCode(), title, body, waste, "", false)
            }
            ACTION_SNOOZE -> {
                val nid = intent.getIntExtra(EXTRA_NID, 0)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(nid)
                val waste = intent.getStringExtra(EXTRA_WASTE) ?: ""
                val date = intent.getStringExtra(EXTRA_DATE) ?: ""
                val sameDay = intent.getBooleanExtra(EXTRA_SAME_DAY, false)
                val triggerAt = System.currentTimeMillis() + SNOOZE_MS
                val refire = Intent(ctx, NotificationReceiver::class.java).apply {
                    action = ACTION_FIRE
                    putExtra(EXTRA_WASTE, waste)
                    putExtra(EXTRA_DATE, date)
                    putExtra(EXTRA_SAME_DAY, sameDay)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, nid + SNOOZE_PI_SALT, refire,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                AppLog.i("Notification snoozed 30m for $waste")
            }
            ACTION_MARK_OUT -> {
                val nid = intent.getIntExtra(EXTRA_NID, 0)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(nid)
                val waste = intent.getStringExtra(EXTRA_WASTE) ?: ""
                AppLog.i("User marked bin out: $waste")
            }
        }
    }

    private fun show(ctx: Context, id: Int, title: String, body: String,
                     waste: String, date: String, sameDay: Boolean) {
        val iconRes = if (waste.isNotBlank()) WasteType.iconRes(waste) else R.drawable.ic_notification
        val accentArgb = if (waste.isNotBlank())
            WasteType.accentColor(waste, false).toArgb()
        else 0xFF1B6F3A.toInt()
        val large = runCatching { renderLargeBitmap(ctx, iconRes, accentArgb) }.getOrNull()
        val builder = NotificationCompat.Builder(ctx, BinsApplication.CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(accentArgb)
            .setAutoCancel(true)
        if (large != null) builder.setLargeIcon(large)
        if (waste.isNotBlank()) {
            val snoozeIntent = Intent(ctx, NotificationReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_NID, id)
                putExtra(EXTRA_WASTE, waste)
                putExtra(EXTRA_DATE, date)
                putExtra(EXTRA_SAME_DAY, sameDay)
            }
            val snoozePi = PendingIntent.getBroadcast(
                ctx, id + ACTION_SNOOZE_REQ, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val markIntent = Intent(ctx, NotificationReceiver::class.java).apply {
                action = ACTION_MARK_OUT
                putExtra(EXTRA_NID, id)
                putExtra(EXTRA_WASTE, waste)
            }
            val markPi = PendingIntent.getBroadcast(
                ctx, id + ACTION_MARK_REQ, markIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Snooze 30m", snoozePi)
            builder.addAction(0, "Mark out", markPi)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, builder.build())
    }

    private fun renderLargeBitmap(ctx: Context, iconRes: Int, accentArgb: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentArgb }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val drawable = ContextCompat.getDrawable(ctx, iconRes)!!.mutate()
        val pad = size / 5
        drawable.setBounds(pad, pad, size - pad, size - pad)
        DrawableCompat.setTint(drawable, Color.WHITE)
        drawable.draw(canvas)
        return bmp
    }

    companion object {
        const val ACTION_FIRE = "com.plymouthbins.app.ACTION_FIRE"
        const val ACTION_FIRE_IMMEDIATE = "com.plymouthbins.app.ACTION_FIRE_IMMEDIATE"
        const val ACTION_SNOOZE = "com.plymouthbins.app.ACTION_SNOOZE"
        const val ACTION_MARK_OUT = "com.plymouthbins.app.ACTION_MARK_OUT"
        const val EXTRA_WASTE = "waste"
        const val EXTRA_DATE = "date"
        const val EXTRA_SAME_DAY = "same_day"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_NID = "nid"
        const val SNOOZE_MS = 30L * 60_000L
        const val SNOOZE_PI_SALT = 50_000
        const val ACTION_SNOOZE_REQ = 60_000
        const val ACTION_MARK_REQ = 70_000
    }
}
