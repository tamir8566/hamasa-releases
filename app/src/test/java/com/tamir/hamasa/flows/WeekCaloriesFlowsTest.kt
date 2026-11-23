package com.tamir.hamasa.flows

/*
 * "הקלוריות של השבוע" (domain/food/WeekCalories + the card in the weekly summary).
 *  1. weekCaloriesLogic   cut: not above goal+100 is a success; gain: not below goal-100; maintain: within 100;
 *                         today is listed but never judged; days with nothing logged are not judged; totals add up.
 *  2. weekCaloriesCard    the card shows each day's difference (+ / -) and the week's total.
 *                         Image: build/screenshots/flows/weekCalories_01.png
 */

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.food.WeekCalories
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.weekly.CaloriesWeekCard
import com.tamir.hamasa.ui.weekly.WeeklySummaryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class WeekCaloriesFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val start = 20_000L  // an arbitrary week start

    @Test
    fun weekCaloriesLogic() {
        val flow = "weekCaloriesLogic"
        assertTrue("[$flow] cut: 100 over is fine", WeekCalories.dayOk(GoalDirection.CUT, 2600, 2500))
        assertTrue("[$flow] cut: 101 over is not", !WeekCalories.dayOk(GoalDirection.CUT, 2601, 2500))
        assertTrue("[$flow] cut: far under is fine", WeekCalories.dayOk(GoalDirection.CUT, 1500, 2500))
        assertTrue("[$flow] gain: far under is not", !WeekCalories.dayOk(GoalDirection.GAIN, 2000, 2500))
        assertTrue("[$flow] gain: 100 under is fine", WeekCalories.dayOk(GoalDirection.GAIN, 2400, 2500))
        assertTrue("[$flow] maintain: inside", WeekCalories.dayOk(GoalDirection.MAINTAIN, 2450, 2500))
        assertTrue("[$flow] maintain: outside", !WeekCalories.dayOk(GoalDirection.MAINTAIN, 2700, 2500))

        // Sunday..Wednesday logged, Thursday = today, Friday/Saturday not yet
        val eaten = mapOf(start to 2400, start + 1 to 2900, start + 2 to 0, start + 3 to 2000, start + 4 to 1000)
        val days = WeekCalories.week(start, start + 4, eaten, GoalDirection.CUT, 2500)
        assertEquals("[$flow] days up to today", 5, days.size)
        assertEquals("[$flow] sunday ok", true, days[0].ok)
        assertEquals("[$flow] monday over", false, days[1].ok)
        assertNull("[$flow] nothing logged is not judged", days[2].ok)
        assertEquals("[$flow] wednesday ok", true, days[3].ok)
        assertNull("[$flow] today is not judged", days[4].ok)
        assertTrue("[$flow] today flagged", days[4].isToday)
        assertEquals("[$flow] total over the judged days: -100 +400 -500", -200, WeekCalories.totalDiff(days, 2500))
        assertEquals("[$flow] ok count", 2, WeekCalories.okCount(days))
        assertEquals("[$flow] judged count", 3, WeekCalories.judgedCount(days))
    }

    @Test
    fun weekCaloriesCard() {
        val flow = "weekCaloriesCard"
        val eaten = mapOf(start to 2400, start + 1 to 2900, start + 3 to 2000, start + 4 to 1000)
        val st = WeeklySummaryState(
            loading = false, weekStart = start, calGoal = 2500, calDirection = GoalDirection.CUT,
            calDays = WeekCalories.week(start, start + 4, eaten, GoalDirection.CUT, 2500)
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme { Column { CaloriesWeekCard(st) } }
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("\u200E-100")).assertExists()
        composeRule.onNode(hasText("\u200E+400")).assertExists()
        composeRule.onNode(hasText("\u200E-200")).assertExists()
        composeRule.onNode(hasText("2 מתוך 3 ימים בטווח היעד.", substring = true)).assertExists()
        runCatching {
            val f = File("build/screenshots/flows/weekCalories_01.png")
            f.parentFile?.mkdirs()
            composeRule.onRoot().captureRoboImage(f.path)
        }
    }

    /** With the day's burn known: the big number is eaten minus burned, and the week sums the real deficit. */
    @Test
    fun weekCaloriesAgainstBurn() {
        val flow = "weekCaloriesAgainstBurn"
        val eaten = mapOf(start to 2400, start + 1 to 2900, start + 3 to 2000, start + 4 to 1000)
        val burn = mapOf(start to 3000, start + 1 to 3000, start + 2 to 2800, start + 3 to 3100, start + 4 to 2900)
        val days = WeekCalories.week(start, start + 4, eaten, GoalDirection.CUT, 2500)
        assertEquals("[$flow] sunday balance", -600, WeekCalories.balance(days[0], burn))
        assertEquals("[$flow] total: -600 -100 -1100", -1800, WeekCalories.totalBalance(days, burn))
        assertTrue("[$flow] a deficit is good when cutting", WeekCalories.balanceOk(GoalDirection.CUT, -600))
        assertTrue("[$flow] a deficit is not good when gaining", !WeekCalories.balanceOk(GoalDirection.GAIN, -600))
        assertNull("[$flow] no burn known", WeekCalories.balance(days[0], emptyMap()))
        val st = WeeklySummaryState(
            loading = false, weekStart = start, calGoal = 2500, calDirection = GoalDirection.CUT, calDays = days, calBurn = burn
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme { Column { CaloriesWeekCard(st) } }
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("\u200E-600")).assertExists()
        composeRule.onNode(hasText("אכלת 2400 · שרפת 3000 · מול היעד \u200E-100")).assertExists()
        composeRule.onNode(hasText("\u200E-1800")).assertExists()
        composeRule.onNode(hasText("גירעון של 1800 קלוריות בימים שנגמרו. שווה ערך לבערך 0.23 ק\"ג שומן.")).assertExists()
        composeRule.onNode(hasText("בסך הכול מול היעד")).assertExists()
        runCatching {
            val f = File("build/screenshots/flows/weekCalories_02_burn.png")
            f.parentFile?.mkdirs()
            composeRule.onRoot().captureRoboImage(f.path)
        }
    }
}
