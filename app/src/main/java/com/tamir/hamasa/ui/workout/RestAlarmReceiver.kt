package com.tamir.hamasa.ui.workout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * The end-of-rest alarm set by [RestAlerts.show]: beeps even when the workout screen's coroutine was frozen or the
 * process was killed meanwhile. [RestAlerts.ping] de-duplicates with the ViewModel's own ping. Never throws.
 */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val app = context.applicationContext
        // keep the process alive until the tone (released after ~0.9 s inside ping) is done
        val pending = runCatching { goAsync() }.getOrNull()
        runCatching { RestAlerts.cancelNotification(app) }
        runCatching { RestAlerts.ping(app) }
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { pending?.finish() } }, 1_200L)
    }

    companion object {
        const val ACTION = "com.tamir.hamasa.REST_END"
    }
}
