package com.tamir.hamasa.data

import android.content.Context

/**
 * Which notifications the app sends. His rule (2026-11-05): only two must reach the watch, "the app was updated"
 * and "the rest is over", so that the watch is not cluttered. With [essentialOnly] on (the default) every other
 * notification is skipped: the hourly reminders, the silent "next set" card, the rest countdown, "השף ענה".
 * The foreground notice "השף חושב" stays: Android requires it while the chef keeps working in the background, and
 * it is a low-importance notice that watches do not mirror.
 */
object NotifPolicy {
    private const val PREFS = "notif_policy"
    private const val K_ESSENTIAL = "essential_only"

    @Volatile private var cached: Boolean? = null

    /** True = only "updated" and "rest over". */
    fun essentialOnly(context: Context? = null): Boolean {
        cached?.let { return it }
        val ctx = context?.applicationContext ?: runCatching { AppGraph.appContext }.getOrNull() ?: return true
        val v = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ESSENTIAL, true) }.getOrDefault(true)
        cached = v
        return v
    }

    fun setEssentialOnly(context: Context, on: Boolean) {
        cached = on
        runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ESSENTIAL, on).apply() }
    }

    /** Tests only. */
    internal fun resetForTest() { cached = null }
}
