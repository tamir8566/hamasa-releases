package com.tamir.hamasa.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Home-screen widgets glue: refreshing every placed widget, and the one-shot "צלם ארוחה" request
 * from the big widget (MainActivity sets it, AppRoot navigates to the food screen, FoodScreen consumes it
 * and opens the camera once).
 */
object Widgets {
    /** Intent action of the big widget's "צלם ארוחה" button (explicit intent to MainActivity). */
    const val ACTION_SNAP_MEAL = "com.tamir.hamasa.ACTION_SNAP_MEAL"

    /** PendingIntent request codes; each tap target has its own. */
    const val RC_BIG_OPEN = 7
    const val RC_BIG_SNAP_MEAL = 8
    const val RC_LINE_OPEN = 9

    private const val DEBOUNCE_MS = 700L

    private val _snapMeal = MutableStateFlow(false)
    /** True while a "צלם ארוחה" tap waits for the food screen. */
    val snapMeal: StateFlow<Boolean> = _snapMeal

    fun requestSnapMeal() {
        _snapMeal.value = true
    }

    /** Returns true exactly once per request (so rotation / back never opens the camera again). */
    fun consumeSnapMeal(): Boolean = _snapMeal.compareAndSet(true, false)

    @Volatile
    private var pendingContext: Context? = null
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val refreshTask = Runnable {
        val ctx = pendingContext
        if (ctx != null) refreshNow(ctx)
    }

    /**
     * Asks both widgets to redraw, coalescing calls that come close together (a meal save touches several
     * tables). Cheap no-op when no widget is placed. Never throws.
     */
    fun refreshAll(context: Context) {
        try {
            pendingContext = context.applicationContext
            handler.removeCallbacks(refreshTask)
            handler.postDelayed(refreshTask, DEBOUNCE_MS)
        } catch (e: Throwable) {
            // widgets are optional
        }
    }

    /** Redraws both widgets right away (for background work that may end soon, e.g. the hourly worker). */
    fun refreshNow(context: Context) {
        try {
            val app = context.applicationContext
            send(app, HamasaWidgetProvider::class.java)
            send(app, DailyLineWidgetProvider::class.java)
        } catch (e: Throwable) {
            // widgets are optional
        }
    }

    private fun send(app: Context, cls: Class<out AppWidgetProvider>) {
        try {
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, cls))
            if (ids == null || ids.isEmpty()) return
            val intent = Intent(app, cls)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            app.sendBroadcast(intent)
        } catch (e: Throwable) {
            // widgets are optional
        }
    }
}
