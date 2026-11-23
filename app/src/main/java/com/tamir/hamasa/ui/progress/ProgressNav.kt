package com.tamir.hamasa.ui.progress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Navigation requests from places whose screen signature is fixed (Home cards, the weekly summary link).
 * AppRoot collects [request], navigates, then calls [consumed]. Same idea as Widgets.snapMeal.
 */
object ProgressNav {
    const val PHOTOS = "progress"
    const val BEFORE_AFTER = "before_after"
    /** "monthly_summary/{ym}" with ym = year*12 + (month-1); -1 = previous month. */
    const val MONTHLY = "monthly_summary/{ym}"
    fun monthly(ym: Int = -1) = "monthly_summary/$ym"

    private val _request = MutableStateFlow<String?>(null)
    val request: StateFlow<String?> = _request

    fun open(route: String) { _request.value = route }
    fun consumed() { _request.value = null }
}
