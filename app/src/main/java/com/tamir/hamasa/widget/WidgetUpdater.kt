package com.tamir.hamasa.widget

import android.content.Context

/** Asks every placed home-screen widget to redraw (both widgets, see [Widgets.refreshAll]). Never throws. */
object WidgetUpdater {
    fun refresh(context: Context) {
        Widgets.refreshAll(context)
    }
}
