package com.plymouthbins.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        WorkManager.getInstance(ctx.applicationContext).enqueue(req)
    }
}
