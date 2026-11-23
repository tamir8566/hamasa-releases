package com.tamir.hamasa.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Status callback of a [SilentInstaller] session (not exported; reached only through the explicit
 * PendingIntent the session was committed with).
 *
 * - STATUS_PENDING_USER_ACTION: we are not yet the installer of record (first update after the
 *   switch, or after a manual sideload). In the foreground the system confirmation is opened; in
 *   the background the session is abandoned and the foreground check retries later.
 * - STATUS_SUCCESS: nothing to do; the process is replaced by the new version.
 * - Failures: a Hebrew line for the settings card, and the next install uses the old intent flow.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SilentInstaller.ACTION_STATUS) return
        val app = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val build = intent.getIntExtra(SilentInstaller.EXTRA_BUILD, 0)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        Log.i(TAG, "status $status for build $build: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        try {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> onNeedsConfirmation(app, intent, sessionId)
                PackageInstaller.STATUS_SUCCESS -> UpdateCenter.onInstallSucceeded(app)
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateCenter.onInstallFailed(app, build, "ההתקנה בוטלה.", fallBack = false)
                else -> UpdateCenter.onInstallFailed(app, build, failureLine(status), fallBack = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "status handling failed", e)
        }
    }

    private fun onNeedsConfirmation(app: Context, intent: Intent, sessionId: Int) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null || !UpdateCenter.isForeground) {
            // No screen to ask on (a background install while we are not installer of record).
            // Drop the session; the next foreground check starts a fresh one and asks there.
            if (sessionId >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
            UpdateCenter.onConfirmationDeferred(app)
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(confirm)
            UpdateCenter.onConfirmationShown()
        } catch (e: Exception) {
            Log.w(TAG, "cannot open confirmation", e)
            if (sessionId >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
            UpdateCenter.onInstallFailed(app, intent.getIntExtra(SilentInstaller.EXTRA_BUILD, 0), "לא ניתן לפתוח את בקשת האישור.", fallBack = true)
        }
    }

    private fun failureLine(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "המערכת חסמה את ההתקנה."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "ההתקנה נכשלה. התנגשות עם הגרסה המותקנת."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "הגרסה החדשה לא מתאימה למכשיר."
        PackageInstaller.STATUS_FAILURE_INVALID -> "קובץ ההתקנה פגום."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "אין מספיק מקום פנוי."
        else -> "ההתקנה נכשלה."
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}
