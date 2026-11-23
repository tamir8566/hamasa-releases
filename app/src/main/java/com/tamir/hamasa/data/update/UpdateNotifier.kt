package com.tamir.hamasa.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tamir.hamasa.BuildConfig
import com.tamir.hamasa.MainActivity
import com.tamir.hamasa.R
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames

/**
 * System notifications of the self-update flow, on their own channel "עדכונים":
 * - "יש גרסה חדשה: N" (tap opens the app, which checks again and installs / shows the banner),
 *   at most once per 12 hours for the same build, never while the app is on screen;
 * - "המסע הגדול עודכן לגרסה N", posted once by the new version itself (first start or
 *   MY_PACKAGE_REPLACED, whichever comes first).
 * Without the POST_NOTIFICATIONS permission (or with notifications off) nothing is posted.
 */
object UpdateNotifier {
    private const val TAG = "UpdateNotifier"
    const val CHANNEL_UPDATES = "updates"
    /** MainActivity intent action of the "new version" notification. */
    const val ACTION_OPEN_UPDATE = "com.tamir.hamasa.action.OPEN_UPDATE"
    private const val ID_AVAILABLE = 40
    private const val ID_UPDATED = 41
    private const val REPEAT_MS = 12L * 60L * 60L * 1000L

    /** Idempotent; called from HamasaApp.onCreate and before every post. */
    fun ensureChannel(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot create channel", e)
        }
    }

    /** Permission granted (Android 13+) and notifications not turned off for the app. */
    fun canPost(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            nm.areNotificationsEnabled()
        } catch (e: Exception) {
            false
        }
    }

    /** "יש גרסה חדשה: N". Skipped on screen, for old builds, and for a repeat within 12 hours. */
    fun notifyAvailable(context: Context, build: Int) {
        val app = context.applicationContext
        try {
            if (build <= BuildConfig.VERSION_CODE || UpdateCenter.isForeground) return
            val now = System.currentTimeMillis()
            if (UpdateStore.availableNotifiedBuild(app) == build &&
                now - UpdateStore.availableNotifiedAt(app) < REPEAT_MS
            ) return
            if (!canPost(app)) return
            val open = Intent(app, MainActivity::class.java)
                .setAction(ACTION_OPEN_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (post(app, ID_AVAILABLE, "יש גרסה חדשה: $build", Addr.t("לחץ כדי לעדכן", "לחצי כדי לעדכן"), open)) {
                UpdateStore.setAvailableNotified(app, build, now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "available notification failed", e)
        }
    }

    /** "המסע הגדול עודכן לגרסה N", once per version. */
    fun notifyUpdated(context: Context, build: Int) {
        val app = context.applicationContext
        try {
            cancelAvailable(app)
            if (UpdateStore.updatedNotifiedBuild(app) == build) return
            if (!canPost(app)) return
            val open = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (post(app, ID_UPDATED, "המסע הגדול עודכן לגרסה $build", CharacterNames.pick("אותו מסע. אותו שמביולוק. גרסה חדשה.", "אותו מסע. אותה שמביולוקה. גרסה חדשה."), open)) {
                UpdateStore.setUpdatedNotifiedBuild(app, build)
            }
        } catch (e: Exception) {
            Log.w(TAG, "updated notification failed", e)
        }
    }

    fun cancelAvailable(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_AVAILABLE) }
    }

    private fun post(app: Context, id: Int, title: String, text: String, open: Intent): Boolean {
        ensureChannel(app)
        val pi = PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return false
            nm.notify(id, n)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
            false
        }
    }
}
