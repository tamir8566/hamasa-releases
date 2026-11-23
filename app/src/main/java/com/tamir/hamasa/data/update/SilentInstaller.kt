package com.tamir.hamasa.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a downloaded APK through a [PackageInstaller] session.
 *
 * On Android 12+ the session asks for USER_ACTION_NOT_REQUIRED (with the
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION permission in the manifest). The system honours it only when
 * this app is already the installer of record of itself, so the very first session install shows
 * the confirmation once (STATUS_PENDING_USER_ACTION, handled in [UpdateInstallReceiver]); every
 * later update installs silently. A manual sideload (Files app, adb) makes another package the
 * installer of record again, so the next update asks once more.
 *
 * Update ownership (SessionParams.setRequestUpdateOwnership, API 34) is deliberately NOT requested:
 * it needs the extra ENFORCE_UPDATE_OWNERSHIP permission and would make every manual sideload ask an
 * extra "change update owner" question, and silent self-updates work without it.
 */
object SilentInstaller {
    private const val TAG = "SilentInstaller"
    const val ACTION_STATUS = "com.tamir.hamasa.action.UPDATE_INSTALL_STATUS"
    const val EXTRA_BUILD = "com.tamir.hamasa.extra.UPDATE_BUILD"

    /**
     * True when this app installed its own current version (Android 11+), i.e. the next session
     * install can skip the confirmation. False on Android 10, on any error, and when another
     * package owns updates (Android 14+).
     */
    fun isSelfInstallerOfRecord(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val pkg = context.packageName
            val info = context.packageManager.getInstallSourceInfo(pkg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val owner: String? = info.updateOwnerPackageName
                if (owner != null && owner != pkg) return false
            }
            info.installingPackageName == pkg
        } catch (e: Exception) {
            Log.w(TAG, "install source unavailable", e)
            false
        }
    }

    /**
     * Writes [apk] into a new session and commits it. The result arrives in [UpdateInstallReceiver].
     * Returns true when the session was committed, false on any error (the session is abandoned).
     * [build] is stored first (synchronously) so the next start can say "עודכן לגרסה N".
     */
    suspend fun install(context: Context, apk: File, build: Int): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var sessionId = -1
        var session: PackageInstaller.Session? = null
        val installer: PackageInstaller = try {
            app.packageManager.packageInstaller
        } catch (e: Exception) {
            Log.w(TAG, "no package installer", e)
            null
        } ?: return@withContext false
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            params.setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            val s = installer.openSession(sessionId)
            session = s
            s.openWrite("base.apk", 0L, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                s.fsync(out)
            }
            // Explicit intent (component set), so a mutable PendingIntent is allowed on Android 14+.
            // It must be mutable on Android 12+: the system fills in EXTRA_STATUS / EXTRA_INTENT.
            val status = Intent(app, UpdateInstallReceiver::class.java)
                .setAction(ACTION_STATUS)
                .setPackage(app.packageName)
                .putExtra(EXTRA_BUILD, build)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, sessionId, status, flags)
            UpdateStore.setPendingBuild(app, build)
            s.commit(pending.intentSender)
            Log.i(TAG, "session $sessionId committed for build $build")
            true
        } catch (e: Exception) {
            Log.w(TAG, "session install failed", e)
            runCatching { session?.abandon() }
            if (session == null && sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdateStore.clearPendingBuild(app)
            false
        } finally {
            runCatching { session?.close() }
        }
    }
}
