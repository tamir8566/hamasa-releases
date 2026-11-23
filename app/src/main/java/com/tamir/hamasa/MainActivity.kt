package com.tamir.hamasa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.data.update.UpdateNotifier
import com.tamir.hamasa.ui.AppRoot
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.widget.Widgets
import com.tamir.hamasa.work.ReminderScheduler
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Android 13+: the lock-screen rest countdown needs POST_NOTIFICATIONS (asked in onboarding; again once here). */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderScheduler.ensureScheduled(this)
        CloudSync.schedule(this)
        UpdateCenter.onAppStart(this)
        UpdateCenter.scheduleBackground(this)
        askNotificationsAtFirstWorkout()
        // widget "צלם ארוחה": only a fresh launch (a recreated activity keeps the old intent)
        if (savedInstanceState == null) {
            handleWidgetIntent(intent)
            handleUpdateIntent(intent)
            handleChefIntent(intent)
        }
        setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }

    /** Returning to the app (e.g. from the phone's home screen) also looks for a newer build. */
    override fun onResume() {
        super.onResume()
        UpdateCenter.setForeground(true)
        UpdateCenter.checkIfDue(this)
        // stage 3: steps / watch workouts / heart rate from Health Connect (runs on start too; at most every 5 min)
        WatchSync.syncSoon(this)
        // energy calculator: an automatic calorie goal follows the latest weigh-in on every open (at most once a minute)
        com.tamir.hamasa.data.repo.EnergyData.syncAutoGoalSoon()
        // home-screen widgets: new day, new line; meals and goals may have changed elsewhere
        Widgets.refreshAll(this)
    }

    override fun onPause() {
        UpdateCenter.setForeground(false)
        super.onPause()
    }

    /** No longer visible: a known newer build may install once he has really left (UpdateCenter.onAppStopped). */
    override fun onStop() {
        super.onStop()
        UpdateCenter.onAppStopped()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
        handleUpdateIntent(intent)
        handleChefIntent(intent)
    }

    /**
     * The rest timer on the lock screen is a notification. Onboarding asks for the permission once; when it was not
     * given (declined, or onboarding happened before the timer existed), it is asked one more time the moment a
     * workout starts, before the first rest. Once per install; a later "no" is final (the system stops asking anyway).
     */
    private fun askNotificationsAtFirstWorkout() {
        runCatching {
            if (Build.VERSION.SDK_INT < 33) return
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
            val sp = getSharedPreferences(NOTIF_PREFS, MODE_PRIVATE)
            if (sp.getBoolean(KEY_ASKED_AT_WORKOUT, false)) return
            lifecycleScope.launch {
                runCatching {
                    AppGraph.db.sessions().activeSession().filterNotNull().first()
                    lifecycle.withResumed {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                            !sp.getBoolean(KEY_ASKED_AT_WORKOUT, false)
                        ) {
                            sp.edit().putBoolean(KEY_ASKED_AT_WORKOUT, true).apply()
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
        }
    }

    /** The "יש גרסה חדשה" notification: check now; the result installs (auto-update) or shows the banner. */
    private fun handleUpdateIntent(i: Intent?) {
        if (i == null || i.action != UpdateNotifier.ACTION_OPEN_UPDATE) return
        if ((i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        UpdateCenter.onUpdateNotificationOpened(this)
    }

    /** "השף ענה" notification: open the food screen, where the chef card shows the answer. */
    private fun handleChefIntent(i: Intent?) {
        if (i == null || i.action != com.tamir.hamasa.data.ai.ChefRunner.ACTION_OPEN_CHEF) return
        if ((i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        com.tamir.hamasa.ui.progress.ProgressNav.open(com.tamir.hamasa.ui.Routes.FOOD)
    }

    /** The big widget's "צלם ארוחה" button: AppRoot opens the food screen, which starts the camera once. */
    private fun handleWidgetIntent(i: Intent?) {
        if (i == null || i.action != Widgets.ACTION_SNAP_MEAL) return
        if ((i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        Widgets.requestSnapMeal()
    }

    private companion object {
        const val NOTIF_PREFS = "rest_alerts"
        const val KEY_ASKED_AT_WORKOUT = "notifAskedAtWorkout"
    }
}
