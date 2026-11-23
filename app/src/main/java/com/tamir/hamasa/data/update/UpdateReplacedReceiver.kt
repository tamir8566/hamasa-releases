package com.tamir.hamasa.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.work.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MY_PACKAGE_REPLACED: the system starts the NEW version right after an update (sent only to this
 * package; not exported, the system still delivers it). Posts "המסע הגדול עודכן לגרסה N" when the
 * update was our own self-update, and makes sure the periodic workers are scheduled
 * (all KEEP, so this is harmless when they already are).
 */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        // the "updated" notification names the hero: take the stored variant before it is built
        runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    val p = AppGraph.prefs.profile.first()
                    CharacterNames.set(p.characterVariant)
                    Addr.set(p.userGender)
                }
            }
        }.onFailure { Log.w(TAG, "profile", it) }
        UpdateCenter.onPackageReplaced(app)
        // the update ran while the app was on screen ("מתעדכן"): open it again right away
        runCatching {
            val at = UpdateStore.relaunchAt(app)
            if (at > 0L && System.currentTimeMillis() - at < 5L * 60L * 1000L) {
                UpdateStore.setRelaunchAt(app, 0L)
                val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
                if (open != null) {
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    app.startActivity(open)
                }
            }
        }.onFailure { Log.w(TAG, "relaunch", it) }
        runCatching { ReminderScheduler.ensureScheduled(app) }.onFailure { Log.w(TAG, "reminders", it) }
        runCatching { CloudSync.schedule(app) }.onFailure { Log.w(TAG, "cloud sync", it) }
        runCatching { UpdateCenter.scheduleBackground(app) }.onFailure { Log.w(TAG, "updates", it) }
    }

    private companion object {
        const val TAG = "UpdateReplacedReceiver"
    }
}
