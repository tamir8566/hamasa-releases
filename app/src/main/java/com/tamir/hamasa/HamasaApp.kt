package com.tamir.hamasa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import com.tamir.hamasa.data.AppGraph

class HamasaApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, getString(R.string.notif_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
        )
        com.tamir.hamasa.data.update.UpdateNotifier.ensureChannel(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    companion object { const val CHANNEL_REMINDERS = "reminders" }
}
