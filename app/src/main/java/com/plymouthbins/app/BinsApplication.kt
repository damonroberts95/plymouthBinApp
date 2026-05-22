package com.plymouthbins.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.plymouthbins.app.work.RefreshWorker
import java.util.concurrent.TimeUnit

class BinsApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        schedulePeriodicRefresh()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_desc)
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun schedulePeriodicRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    companion object {
        const val CHANNEL_ID = "bin_reminders"
        const val REFRESH_WORK_NAME = "bin_refresh_periodic"
    }
}
