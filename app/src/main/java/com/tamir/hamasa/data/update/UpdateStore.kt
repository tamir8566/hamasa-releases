package com.tamir.hamasa.data.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Small persistent state of the self-update flow, kept in the same "update" SharedPreferences file
 * as [UpdateChecker]'s last-check time (synchronous, so the background worker and the install
 * status receiver can read it without DataStore).
 */
object UpdateStore {
    private const val PREFS = "update"
    private const val KEY_AUTO = "autoUpdate"
    private const val KEY_RELAUNCH_AT = "relaunchAt"
    private const val KEY_PENDING_BUILD = "pendingBuild"
    private const val KEY_PENDING_AT = "pendingAt"
    private const val KEY_LAST_ERROR = "lastError"
    private const val KEY_USE_LEGACY = "useLegacyInstall"
    private const val KEY_FAILED_BUILD = "failedBuild"
    private const val KEY_LAST_SEEN_VERSION = "lastSeenVersion"
    private const val KEY_BG_CHECK_AT = "backgroundCheckAt"
    private const val KEY_AVAIL_NOTIF_BUILD = "availableNotifiedBuild"
    private const val KEY_AVAIL_NOTIF_AT = "availableNotifiedAt"
    private const val KEY_UPDATED_NOTIF_BUILD = "updatedNotifiedBuild"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Settings toggle "עדכון אוטומטי". Default ON. */
    fun autoUpdate(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    /** Build number of the last committed install session (0 = none). */
    fun pendingBuild(context: Context): Int = sp(context).getInt(KEY_PENDING_BUILD, 0)

    fun pendingAt(context: Context): Long = sp(context).getLong(KEY_PENDING_AT, 0L)

    /** Set right before an install that runs while the app is on screen: the new version reopens the app. */
    fun setRelaunchAt(context: Context, at: Long) {
        runCatching { sp(context).edit().putLong(KEY_RELAUNCH_AT, at).commit() }
    }

    fun relaunchAt(context: Context): Long = runCatching { sp(context).getLong(KEY_RELAUNCH_AT, 0L) }.getOrDefault(0L)

    /** Written with commit() (synchronous): the process is killed right after a self-update commits. */
    fun setPendingBuild(context: Context, build: Int) {
        sp(context).edit().putInt(KEY_PENDING_BUILD, build).putLong(KEY_PENDING_AT, System.currentTimeMillis()).commit()
    }

    fun clearPendingBuild(context: Context) {
        sp(context).edit().remove(KEY_PENDING_BUILD).remove(KEY_PENDING_AT).apply()
    }

    /** Hebrew error line of the last failed install, shown on the settings card; null = none. */
    fun lastError(context: Context): String? = sp(context).getString(KEY_LAST_ERROR, null)

    fun setLastError(context: Context, message: String?) {
        val e = sp(context).edit()
        if (message == null) e.remove(KEY_LAST_ERROR) else e.putString(KEY_LAST_ERROR, message)
        e.apply()
    }

    /** True after a session install failed: the next install uses the old intent flow. */
    fun useLegacy(context: Context): Boolean = sp(context).getBoolean(KEY_USE_LEGACY, false)

    fun setUseLegacy(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_USE_LEGACY, on).apply()
    }

    /** Build whose automatic install failed or was cancelled: it is not retried automatically. */
    fun failedBuild(context: Context): Int = sp(context).getInt(KEY_FAILED_BUILD, 0)

    fun setFailedBuild(context: Context, build: Int) {
        sp(context).edit().putInt(KEY_FAILED_BUILD, build).apply()
    }

    fun lastSeenVersion(context: Context): Int = sp(context).getInt(KEY_LAST_SEEN_VERSION, 0)

    /** A new version is running: every failure marker belongs to the old one. */
    fun markVersionSeen(context: Context, versionCode: Int) {
        sp(context).edit()
            .putInt(KEY_LAST_SEEN_VERSION, versionCode)
            .remove(KEY_USE_LEGACY)
            .remove(KEY_FAILED_BUILD)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_PENDING_BUILD)
            .remove(KEY_PENDING_AT)
            .apply()
    }

    /** Last background check (UpdateWorker or the hourly reminder worker); at most one per hour. */
    fun backgroundCheckAt(context: Context): Long = sp(context).getLong(KEY_BG_CHECK_AT, 0L)

    fun setBackgroundCheckAt(context: Context, atMillis: Long) {
        sp(context).edit().putLong(KEY_BG_CHECK_AT, atMillis).apply()
    }

    /** Build and time of the last "יש גרסה חדשה" notification (repeat throttle). */
    fun availableNotifiedBuild(context: Context): Int = sp(context).getInt(KEY_AVAIL_NOTIF_BUILD, 0)

    fun availableNotifiedAt(context: Context): Long = sp(context).getLong(KEY_AVAIL_NOTIF_AT, 0L)

    fun setAvailableNotified(context: Context, build: Int, atMillis: Long) {
        sp(context).edit().putInt(KEY_AVAIL_NOTIF_BUILD, build).putLong(KEY_AVAIL_NOTIF_AT, atMillis).apply()
    }

    /** Version whose "עודכן לגרסה N" notification was already posted. */
    fun updatedNotifiedBuild(context: Context): Int = sp(context).getInt(KEY_UPDATED_NOTIF_BUILD, 0)

    fun setUpdatedNotifiedBuild(context: Context, build: Int) {
        sp(context).edit().putInt(KEY_UPDATED_NOTIF_BUILD, build).apply()
    }
}
