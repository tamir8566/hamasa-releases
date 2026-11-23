package com.tamir.hamasa.flows

/*
 * Meals for another day (FoodViewModel.setDay / shiftDay).
 *  1. mealsForYesterday  "יום קודם": a meal added now belongs to yesterday (noon), not today; the screen stays on
 *                        yesterday when the clock refresh runs; "חזרה להיום" (setDay(today)) -> a new meal is today's.
 *                        The future is never reachable.
 */

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.food.FoodViewModel
import com.tamir.hamasa.ui.food.MealForm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class FoodDayFlowsTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
    }

    private fun waitFor(flow: String, step: String, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > 15_000L) fail("[$flow] step '$step' not reached")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    private fun count(day: Long): Int = runBlocking { AppGraph.db.meals().forDayOnce(day).size }

    @Test
    fun mealsForYesterday() {
        val flow = "mealsForYesterday"
        val today = Days.today()
        val vm = FoodViewModel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val todayBefore = count(today)
        val yBefore = count(today - 1)

        vm.shiftDay(-1)
        assertEquals("[$flow] now on yesterday", today - 1, vm.day.value)
        vm.refreshDay()
        assertEquals("[$flow] the clock refresh keeps yesterday", today - 1, vm.day.value)
        vm.add(MealForm("ארוחת ערב אתמול", 700, 40f, 60f, 20f))
        waitFor(flow, "meal saved") { count(today - 1) == yBefore + 1 }
        assertEquals("[$flow] today untouched", todayBefore, count(today))
        val meal = runBlocking { AppGraph.db.meals().forDayOnce(today - 1) }.last()
        assertEquals("[$flow] description", "ארוחת ערב אתמול", meal.description)
        assertTrue("[$flow] past meals get noon", java.time.Instant.ofEpochMilli(meal.atMillis).atZone(java.time.ZoneId.systemDefault()).hour == 12)

        vm.shiftDay(1)
        assertEquals("[$flow] back on today", today, vm.day.value)
        vm.shiftDay(1)
        assertEquals("[$flow] the future is not reachable", today, vm.day.value)
        vm.add(MealForm("ארוחה של היום", 300, 10f, 30f, 5f))
        waitFor(flow, "today meal saved") { count(today) == todayBefore + 1 }
        assertEquals("[$flow] yesterday unchanged", yBefore + 1, count(today - 1))
    }
}
