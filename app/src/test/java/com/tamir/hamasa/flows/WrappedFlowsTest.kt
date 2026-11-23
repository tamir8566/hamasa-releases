package com.tamir.hamasa.flows

/*
 * The "wrapped" stories of the weekly and monthly summaries (ui/common/WrappedStory, domain/game/WrappedText).
 *  1. wrappedWeekTaps     the weekly story: 10 slides, tapping the right half advances, the left half returns.
 *  2. wrappedMonthSlides  the monthly story: 9 slides, Hebrew only, the weight slide follows the goal direction.
 * Images: build/screenshots/flows/wrapped_week_01.png (cover), wrapped_week_02.png (sessions), wrapped_month_01.png.
 */

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.repo.MonthSummary
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.food.CalDay
import com.tamir.hamasa.domain.game.WrappedText
import com.tamir.hamasa.ui.common.WrappedStory
import com.tamir.hamasa.ui.progress.MonthlyWrapped
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.weekly.WeeklySummaryState
import com.tamir.hamasa.ui.weekly.WeeklyWrapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class WrappedFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun shot(name: String) = runCatching {
        val f = File("build/screenshots/flows/$name.png")
        f.parentFile?.mkdirs()
        composeRule.onRoot().captureRoboImage(f.path)
    }

    private fun hebrewOnly(s: String): Boolean = s.none { it in 'a'..'z' || it in 'A'..'Z' }

    @Test
    fun wrappedWeekTaps() {
        val flow = "wrappedWeekTaps"
        val st = WeeklySummaryState(
            loading = false, weekStart = 20_000L, sessions = 4, gymSessions = 3, volumeKg = 12_480.0, prs = 2,
            xp = 1_250, gold = 340, streak = 9, bossNameHe = "שומר השער", bossHp = 30, bossMaxHp = 100, bossDefeated = false,
            verdictHe = "ארבעה אימונים. זה כבר לא מקרה, זו מדיניות.",
            calDays = listOf(CalDay(20_000L, 2_400, true, false), CalDay(20_001L, 2_900, false, false), CalDay(20_002L, 2_450, true, false))
        )
        val slides = WeeklyWrapped.slides(st, "02/11", "08/11")
        assertEquals("[$flow] ten slides", 10, slides.size)
        // the story shows only on Friday and Saturday: 2026-11-08 is a Sunday
        val sunday = java.time.LocalDate.of(2026, 11, 8).toEpochDay()
        val shown = (0L..6L).filter { WeeklyWrapped.showOn(sunday + it) }
        assertEquals("[$flow] story only on friday and saturday", listOf(5L, 6L), shown)
        for (s in slides) {
            assertTrue("[$flow] hebrew only: ${s.kicker}", hebrewOnly(s.kicker) && hebrewOnly(s.big) && hebrewOnly(s.line) && hebrewOnly(s.sub))
            assertTrue("[$flow] every slide has a line: ${s.kicker}", s.line.isNotBlank())
        }
        assertEquals("[$flow] sessions slide", "4", slides[1].big)
        assertEquals("[$flow] volume formatted", "12,480", slides[2].big)
        assertTrue("[$flow] volume in elephants", slides[2].sub.contains("פילים"))
        assertEquals("[$flow] calories 2 of 3", "2/3", slides[6].big)
        assertTrue("[$flow] boss hp percent", slides[5].line.contains("70 אחוז"))

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme { Box { WrappedStory(slides, Modifier.fillMaxWidth().height(520.dp), testTag = "weekly_wrapped") } }
            }
        }
        idle()
        composeRule.onNode(hasText("השבוע שלך")).assertExists()
        composeRule.onNode(hasText("אימונים השבוע")).assertDoesNotExist()
        shot("wrapped_week_01")
        composeRule.onNode(hasTestTag("weekly_wrapped_next")).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        idle()
        composeRule.onNode(hasText("אימונים השבוע")).assertExists()
        shot("wrapped_week_02")
        composeRule.onNode(hasTestTag("weekly_wrapped_back")).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        idle()
        composeRule.onNode(hasText("השבוע שלך")).assertExists()
    }

    @Test
    fun wrappedMonthSlides() {
        val flow = "wrappedMonthSlides"
        val s = MonthSummary(
            month = YearMonth.of(2026, 10), kgChange = -1.8f, startKg = 101.2f, endKg = 99.4f, sessions = 14, records = 5,
            bossesDefeated = listOf("שומר השער", "המפלצת של יום שני"), badges = listOf("ברזל ראשון", "שבוע מלא"),
            bestStreak = 12, kcalAvg = 2_380, kcalDays = 26, kcalGoal = 2_300
        )
        val cut = MonthlyWrapped.slides(s, "אוקטובר", GoalDirection.CUT, "חודש טוב. לא מושלם. טוב מספיק כדי להמשיך.")
        assertEquals("[$flow] nine slides", 9, cut.size)
        for (x in cut) assertTrue("[$flow] hebrew only: ${x.kicker}", hebrewOnly(x.kicker) && hebrewOnly(x.big) && hebrewOnly(x.line) && hebrewOnly(x.sub))
        assertEquals("[$flow] weight big", "1.8", cut[1].big)
        assertTrue("[$flow] cutting: losing is good", cut[1].line.contains("בכיוון הנכון"))
        val gain = MonthlyWrapped.slides(s, "אוקטובר", GoalDirection.GAIN, "")
        assertTrue("[$flow] gaining: losing is not", gain[1].line.contains("לא הכיוון"))
        assertTrue("[$flow] two bosses", cut[4].big.contains("\n"))
        assertTrue("[$flow] kcal within 100 is exact", cut[7].line.contains("דייקת"))

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme { Box { WrappedStory(cut, Modifier.fillMaxWidth().height(520.dp), testTag = "monthly_wrapped") } }
            }
        }
        idle()
        composeRule.onNode(hasText("החודש שלך")).assertExists()
        composeRule.onNode(hasTestTag("monthly_wrapped_next")).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        idle()
        composeRule.onNode(hasText("המשקל")).assertExists()
        shot("wrapped_month_01")
    }
}
