package com.tamir.hamasa.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tamir.hamasa.BuildConfig
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.game.Addr
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-wide update state (a plain object so the Home banner and the settings card share one
 * source of truth). Drives the periodic check on app start, the manual check / download, and the
 * self-update: with "עדכון אוטומטי" on, a newer build found while the app is open is downloaded and
 * installed through a [SilentInstaller] session without a tap; [UpdateWorker] does the same in the
 * background, but only once this app is already its own installer of record (no confirmation
 * possible without a screen). See docs/update.md.
 */
object UpdateCenter {
    private const val TAG = "UpdateCenter"
    private const val WORK_BACKGROUND = "selfUpdate"
    /** A committed session that reports nothing for this long is treated as finished. */
    private const val INSTALL_TIMEOUT_MS = 3L * 60L * 1000L
    /** Background checks (both workers together) at most about once per hour; periodic work drifts. */
    private const val BACKGROUND_MIN_GAP_MS = 55L * 60L * 1000L

    /** An update found while the app is open installs right away only in its first seconds (behind the "מתעדכן" screen). */
    private const val STARTUP_WINDOW_MS = 20_000L

    /** After a finished workout the done screen (rewards, "update the template?") stays safe from a forced install. */
    private const val AFTER_WORKOUT_QUIET_MS = 10L * 60L * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The newest release when it is newer than the installed build; null otherwise. */
    private val _available = MutableStateFlow<ReleaseInfo?>(null)
    val available: StateFlow<ReleaseInfo?> = _available

    private val _lastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = _lastCheck

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading

    /** A short Hebrew status line for the settings card. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Settings toggle "עדכון אוטומטי" (loaded in [onAppStart]; default ON). */
    private val _autoUpdate = MutableStateFlow(true)
    val autoUpdate: StateFlow<Boolean> = _autoUpdate

    /** Build being updated to right now (automatic download, or a committed session); 0 = none. */
    private val _updatingBuild = MutableStateFlow(0)
    val updatingBuild: StateFlow<Int> = _updatingBuild

    /** Set once on the first start of a version that this app installed itself; 0 = nothing to say. */
    private val _justUpdated = MutableStateFlow(0)
    val justUpdated: StateFlow<Int> = _justUpdated

    /** Hebrew line of the last failed install (persisted until the next version runs); null = none. */
    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError

    val progress: StateFlow<Float> = UpdateDownloader.progress

    val enabled: Boolean get() = UpdateChecker.enabled
    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    /** True between MainActivity.onResume and onPause. */
    @Volatile
    var isForeground: Boolean = false
        private set

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    /** After MainActivity.onStop, a known newer build installs only if he is still away this long. */
    private const val LEFT_APP_DELAY_MS = 20_000L

    /**
     * MainActivity.onStop: the app is no longer visible. A known newer build installs quietly once he has really
     * left: still not back after [LEFT_APP_DELAY_MS]. Not on onPause: a permission dialog, the camera for a meal
     * photo or the Spotify login only pause or briefly stop the app, and an install then would kill it under him.
     * Never during a workout or right after one ([backgroundUpdate] checks both).
     */
    fun onAppStopped() {
        scope.launch {
            delay(LEFT_APP_DELAY_MS)
            if (!isForeground) runCatching { backgroundUpdate(AppGraph.appContext, allowDownload = true, force = true) }
        }
    }

    @Volatile
    private var startedAt: Long = 0L

    /** True while an install runs with the app on screen: AppRoot shows the full "מתעדכן" screen. */
    private val _foregroundInstall = MutableStateFlow(false)
    val foregroundInstall: StateFlow<Boolean> = _foregroundInstall

    /** A workout is running: never replace the app under it (the process would die mid-rest). */
    private suspend fun workoutRunning(): Boolean =
        runCatching { AppGraph.db.sessions().activeSession().first() != null }.getOrDefault(false)

    /**
     * A workout was finished less than [AFTER_WORKOUT_QUIET_MS] ago (newest DONE sessions' endedAtMillis): locking
     * the phone on the done screen must not replace the app under it. The next periodic check installs later.
     */
    private suspend fun workoutJustFinished(): Boolean = runCatching {
        val now = System.currentTimeMillis()
        AppGraph.db.sessions().doneSessionsOnce().take(3).any { s ->
            val end = s.endedAtMillis
            end != null && (now - end) >= 0L && (now - end) < AFTER_WORKOUT_QUIET_MS
        }
    }.getOrDefault(false)

    /**
     * Call from MainActivity.onCreate: loads the toggle and, on the first start of a new version,
     * clears the old version's failure markers and says "עודכן לגרסה N" when the update was ours.
     */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        startedAt = System.currentTimeMillis()
        try {
            _autoUpdate.value = UpdateStore.autoUpdate(app)
            handleNewVersion(app)
            _installError.value = UpdateStore.lastError(app)
        } catch (e: Exception) {
            Log.w(TAG, "start state failed", e)
        }
    }

    /**
     * MY_PACKAGE_REPLACED ([UpdateReplacedReceiver]): the new version was started by the system
     * right after the update, so the "עודכן לגרסה N" notification arrives without opening the app.
     */
    fun onPackageReplaced(context: Context) {
        val app = context.applicationContext
        try {
            handleNewVersion(app)
        } catch (e: Exception) {
            Log.w(TAG, "package replaced handling failed", e)
        }
    }

    /**
     * First run of a new version (whichever of [onAppStart] / [onPackageReplaced] comes first):
     * clears the old version's markers and, when the update was ours, says "עודכן לגרסה N"
     * (Home strip + system notification, once).
     */
    private fun handleNewVersion(app: Context) {
        val current = BuildConfig.VERSION_CODE
        if (UpdateStore.lastSeenVersion(app) == current) return
        val pending = UpdateStore.pendingBuild(app)
        val ours = pending > 0 && current >= pending
        UpdateStore.markVersionSeen(app, current)
        File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        if (_available.value?.isNewerThanInstalled == false) _available.value = null
        UpdateNotifier.cancelAvailable(app)
        if (ours) {
            _justUpdated.value = current
            UpdateNotifier.notifyUpdated(app, current)
        }
    }

    /** The "יש גרסה חדשה" notification was tapped: check now; the result installs or shows the banner. */
    fun onUpdateNotificationOpened(context: Context) {
        val app = context.applicationContext
        UpdateNotifier.cancelAvailable(app)
        if (enabled) check(app, manual = false)
    }

    fun dismissJustUpdated() {
        _justUpdated.value = 0
    }

    fun setAutoUpdate(context: Context, on: Boolean) {
        val app = context.applicationContext
        _autoUpdate.value = on
        runCatching { UpdateStore.setAutoUpdate(app, on) }
        if (on) maybeAutoInstall(app)
    }

    /** Periodic background check every ~2 hours on any network, mobile data included ([UpdateWorker]). */
    fun scheduleBackground(context: Context) {
        if (!enabled) return
        try {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(2, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_BACKGROUND, ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Exception) {
            Log.w(TAG, "cannot schedule background update", e)
        }
    }

    /** Call from MainActivity.onCreate / onResume: checks only when the interval has passed. */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        _lastCheck.value = UpdateChecker.lastCheck(app)
        if (UpdateChecker.isDue(app)) check(app, manual = false) else maybeAutoInstall(app)
    }

    /** Manual or automatic check. Failures are silent except for the status line. */
    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        if (!enabled) {
            _status.value = "עדכון אוטומטי לא מוגדר"
            return
        }
        if (_checking.value) return
        scope.launch {
            _checking.value = true
            if (manual) _status.value = "בודק..."
            val info = UpdateChecker.latest()
            if (info == null) {
                if (manual) _status.value = if (UpdateChecker.rateLimited) "יותר מדי בדיקות בזמן קצר. לנסות שוב בעוד שעה" else "לא ניתן לבדוק כרגע"
                Log.i(TAG, "check failed")
            } else {
                val now = System.currentTimeMillis()
                UpdateChecker.markChecked(app, now)
                _lastCheck.value = now
                if (info.isNewerThanInstalled) {
                    _available.value = info
                    if (_updatingBuild.value == 0) _status.value = "גרסה ${info.build} זמינה"
                } else {
                    _available.value = null
                    _status.value = "זו הגרסה העדכנית ביותר"
                }
            }
            _checking.value = false
            if (info != null && info.isNewerThanInstalled) maybeAutoInstall(app)
        }
    }

    /**
     * Manual install (banner tap, "הורד והתקן"). If the system does not yet allow this app to
     * install packages, opens that settings screen and asks the user to come back.
     */
    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val info = _available.value ?: return
        if (_downloading.value || _updatingBuild.value == info.build) return
        if (!UpdateDownloader.canInstall(app)) {
            _status.value = Addr.t("קודם יש לאשר למסע הגדול להתקין אפליקציות. אחרי האישור, לחץ שוב על \"הורדה והתקנה\".", "קודם יש לאשר למסע הגדול להתקין אפליקציות. אחרי האישור, לחצי שוב על \"הורדה והתקנה\".")
            UpdateDownloader.openInstallPermissionSettings(app)
            return
        }
        startInstall(app, info, auto = false)
    }

    /** Foreground automatic install: toggle on, screen open, a newer build known, not already tried. */
    private fun maybeAutoInstall(app: Context) {
        try {
            if (!enabled || !_autoUpdate.value || !isForeground) return
            val info = _available.value ?: return
            if (!info.isNewerThanInstalled || info.assetUrl == null) return
            if (_downloading.value || _updatingBuild.value != 0) return
            if (UpdateStore.failedBuild(app) == info.build) return
            if (recentlyCommitted(app, info.build)) return
            // in the middle of using the app: wait until he leaves it (setForeground(false) installs then)
            if (System.currentTimeMillis() - startedAt > STARTUP_WINDOW_MS) return
            if (!UpdateDownloader.canInstall(app)) {
                _status.value = Addr.t("לעדכון לבד צריך לאשר למסע הגדול להתקין אפליקציות. לחץ על \"הורדה והתקנה\" פעם אחת.", "לעדכון לבד צריך לאשר למסע הגדול להתקין אפליקציות. לחצי על \"הורדה והתקנה\" פעם אחת.")
                return
            }
            scope.launch {
                // never under a running workout, nor on the done screen right after one
                if (workoutRunning() || workoutJustFinished()) return@launch
                _foregroundInstall.value = true
                startInstall(app, info, auto = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "auto install skipped", e)
        }
    }

    private fun recentlyCommitted(app: Context, build: Int): Boolean =
        UpdateStore.pendingBuild(app) == build &&
            System.currentTimeMillis() - UpdateStore.pendingAt(app) < INSTALL_TIMEOUT_MS

    private fun startInstall(app: Context, info: ReleaseInfo, auto: Boolean) {
        scope.launch {
            if (auto) _updatingBuild.value = info.build
            _downloading.value = true
            val apk = cachedOrDownload(app, info)
            _downloading.value = false
            if (apk == null) {
                _status.value = "ההורדה נכשלה. אין רשת, או שהשרת אינו עונה."
                _updatingBuild.value = 0
                _foregroundInstall.value = false
                return@launch
            }
            if (!UpdateStore.useLegacy(app)) {
                _updatingBuild.value = info.build
                _status.value = "מתקין גרסה ${info.build}..."
                if (isForeground) UpdateStore.setRelaunchAt(app, System.currentTimeMillis())
                if (SilentInstaller.install(app, apk, info.build)) {
                    armTimeout(info.build)
                    return@launch
                }
                // The session could not even be committed: this time and next time, the old way.
                val line = "ההתקנה השקטה לא התחילה. בפעם הבאה ההתקנה תיפתח בדרך הרגילה."
                UpdateStore.setUseLegacy(app, true)
                UpdateStore.setLastError(app, line)
                _installError.value = line
                _updatingBuild.value = 0
                _foregroundInstall.value = false
                UpdateStore.setRelaunchAt(app, 0L)
            }
            legacyInstall(app, apk, info.build, auto)
        }
    }

    /** The old flow: the system installer screen through the FileProvider. Needs a screen. */
    private fun legacyInstall(app: Context, apk: File, build: Int, auto: Boolean) {
        // the system installer screen takes over from here: the "מתעדכן" screen must not stay behind it
        _foregroundInstall.value = false
        if (!isForeground) return
        // An automatic attempt opens the installer screen once per build, not every 10 minutes.
        if (auto) UpdateStore.setFailedBuild(app, build)
        _status.value = "ההורדה הושלמה. נפתח המתקין."
        if (!UpdateDownloader.install(app, apk)) {
            _status.value = "לא ניתן לפתוח את ההתקנה כרגע."
        }
    }

    private suspend fun cachedOrDownload(app: Context, info: ReleaseInfo): File? {
        val existing = UpdateDownloader.targetFile(app, info.build)
        if (existing.exists() && existing.length() > 0L) return existing
        if (_updatingBuild.value != info.build) _status.value = "מוריד גרסה ${info.build}..."
        return UpdateDownloader.download(app, info)
    }

    private fun armTimeout(build: Int) {
        scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            if (_updatingBuild.value == build) {
                _updatingBuild.value = 0
                _status.value = "ההתקנה לא הסתיימה. אפשר לנסות שוב."
            }
            if (_updatingBuild.value == 0) _foregroundInstall.value = false
        }
    }

    // ---- results from UpdateInstallReceiver (main thread) ----

    fun onConfirmationShown() {
        _status.value = "אישור אחד. מכאן והלאה העדכונים יותקנו לבד."
    }

    /** Confirmation needed but no screen: the session was dropped, the foreground flow asks later. */
    fun onConfirmationDeferred(context: Context) {
        runCatching { UpdateStore.clearPendingBuild(context.applicationContext) }
        _updatingBuild.value = 0
        _foregroundInstall.value = false
        val info = _available.value
        if (info != null) {
            _status.value = "גרסה ${info.build} תותקן כשהמסע הגדול ייפתח."
            // not on screen: the one-time confirmation needs a tap
            UpdateNotifier.notifyAvailable(context, info.build)
        }
    }

    fun onInstallSucceeded(context: Context) {
        runCatching { UpdateStore.setLastError(context.applicationContext, null) }
        _installError.value = null
        _status.value = "הותקן."
    }

    /**
     * [fallBack] = a real failure: remembered for the settings card, the cached APK is dropped and
     * the next install uses the old intent flow. A cancelled confirmation only stops automatic
     * retries of that build (the banner and the button still install it).
     */
    fun onInstallFailed(context: Context, build: Int, line: String, fallBack: Boolean) {
        val app = context.applicationContext
        _updatingBuild.value = 0
        // the "מתעדכן" screen cannot be closed by hand: a failed or cancelled install must take it away
        _foregroundInstall.value = false
        runCatching { UpdateStore.setRelaunchAt(app, 0L) }
        try {
            UpdateStore.clearPendingBuild(app)
            if (build > 0) UpdateStore.setFailedBuild(app, build)
            if (fallBack) {
                UpdateStore.setUseLegacy(app, true)
                if (build > 0) UpdateDownloader.targetFile(app, build).delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot record failure", e)
        }
        val message = if (fallBack) "$line בפעם הבאה ההתקנה תיפתח בדרך הרגילה." else line
        if (fallBack) {
            runCatching { UpdateStore.setLastError(app, message) }
            _installError.value = message
        }
        _status.value = message
        // A real failure off screen (background install): ask for a tap instead. A cancelled
        // confirmation (fallBack = false) was his own choice and is not nagged about.
        if (fallBack && build > 0) UpdateNotifier.notifyAvailable(app, build)
    }

    // ---- background (UpdateWorker, ReminderWorker) ----

    /**
     * Light check from the hourly [com.tamir.hamasa.work.ReminderWorker]: shares the once-per-hour
     * limit with [UpdateWorker]; downloads only on an unmetered network (otherwise a silent install
     * waits for UpdateWorker, which runs on Wi-Fi).
     */
    suspend fun lightBackgroundCheck(context: Context) {
        val app = context.applicationContext
        // the APK is ~30 MB now: mobile data is fine too, so the update does not wait for Wi-Fi
        backgroundUpdate(app, allowDownload = true)
    }

    private fun isMetered(app: Context): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm == null || cm.isActiveNetworkMetered
    } catch (e: Exception) {
        true
    }

    /**
     * Background check, at most once per hour across both workers, never while the app is on screen.
     * A newer build is installed silently when the toggle is on and the session can skip the
     * confirmation (we are installer of record and allowed to install); success is announced by the
     * new version. Otherwise (toggle off, confirmation needed, a failed build, a failed download or
     * commit) a "יש גרסה חדשה: N" notification asks for a tap.
     */
    suspend fun backgroundUpdate(context: Context, allowDownload: Boolean = true, force: Boolean = false) {
        val app = context.applicationContext
        if (!enabled || isForeground) return
        if (_downloading.value || _updatingBuild.value != 0) return
        if (workoutRunning()) return
        // just finished (done screen, possibly locked): wait for a later check
        if (workoutJustFinished()) return
        val now = System.currentTimeMillis()
        if (!force && now - UpdateStore.backgroundCheckAt(app) < BACKGROUND_MIN_GAP_MS) return
        // leaving the app with a build already known: no need to ask GitHub again
        val known = _available.value
        if (force && known != null && known.isNewerThanInstalled) {
            installKnownInBackground(app, known)
            return
        }
        UpdateStore.setBackgroundCheckAt(app, now)
        val info = UpdateChecker.latest() ?: return
        UpdateChecker.markChecked(app, now)
        _lastCheck.value = now
        if (!info.isNewerThanInstalled) {
            _available.value = null
            return
        }
        _available.value = info
        if (!allowDownload && !UpdateDownloader.targetFile(app, info.build).let { it.exists() && it.length() > 0L }) return
        installKnownInBackground(app, info)
    }

    /** Silent install of [info] with nothing on screen, or the "יש גרסה חדשה" notification when it cannot be silent. */
    private suspend fun installKnownInBackground(app: Context, info: ReleaseInfo) {
        if (recentlyCommitted(app, info.build)) return
        val silent = UpdateStore.autoUpdate(app) &&
            !UpdateStore.useLegacy(app) &&
            info.assetUrl != null &&
            UpdateStore.failedBuild(app) != info.build &&
            SilentInstaller.isSelfInstallerOfRecord(app) &&
            UpdateDownloader.canInstall(app)
        if (!silent) {
            UpdateNotifier.notifyAvailable(app, info.build)
            return
        }
        val existing = UpdateDownloader.targetFile(app, info.build)
        val cached = existing.exists() && existing.length() > 0L
        val apk = if (cached) existing else UpdateDownloader.download(app, info)
        if (isForeground) return
        // the download can take a while: a workout may have started meanwhile
        if (workoutRunning()) return
        if (apk == null) {
            UpdateNotifier.notifyAvailable(app, info.build)
            return
        }
        _updatingBuild.value = info.build
        if (SilentInstaller.install(app, apk, info.build)) {
            armTimeout(info.build)
        } else {
            _updatingBuild.value = 0
            UpdateNotifier.notifyAvailable(app, info.build)
        }
    }
}
