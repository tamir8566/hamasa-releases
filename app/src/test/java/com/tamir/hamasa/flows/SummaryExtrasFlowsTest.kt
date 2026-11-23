package com.tamir.hamasa.flows

/*
 * Owner requests of 2026-11-23 around the weekly and the monthly summary.
 *
 *  1. usualDayLogic       a finished day with nothing logged counts as his usual day (the average of the days he
 *                         logged): it is listed as estimated, never judged against the goal, and it counts in the
 *                         balance against the burn. Today and the days before his first meal are never estimated.
 *  2. energyDaysFromDb    EnergyData.days over real rows: a logged day keeps its calories, the empty day between
 *                         two logged days gets the average, a day before the first meal stays empty; totalsOf sums
 *                         the finished days only.
 *  3. monthProgressLogic  the month's progress per exercise: one point per workout day, the change in percent from
 *                         the first to the last; an exercise done once does not show.
 *  4. summaryCards        the three new cards alone: the daily averages, "שרפת מול אכלת" and "ההתקדמות בתרגילים".
 *                         Images: build/screenshots/flows/summaryExtras_*.png
 *  5. weeklyEarlierWeeks  the weekly summary screen: "השבוע הקודם" moves a week back (the dates change), "השבוע הבא"
 *                         comes back, and the averages card is on top.
 */

import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.data.repo.EnergyDay
import com.tamir.hamasa.data.repo.EnergyTotals
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.MonthProgress
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.food.WeekCalories
import com.tamir.hamasa.ui.progress.MonthEnergyCard
import com.tamir.hamasa.ui.progress.MonthProgressCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.weekly.AveragesCard
import com.tamir.hamasa.ui.weekly.WeeklySummaryScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class SummaryExtrasFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
    }

    /** A grown man's profile, so the burn of a day is a real number. */
    private fun seedProfile() {
        runBlocking {
            AppGraph.workouts.seedIfNeeded()
            AppGraph.prefs.update { p ->
                p.copy(onboarded = true, heightCm = 180, startWeightKg = 85f, goalWeightKg = 78f, age = 21, sex = "M", userGender = "M", kcalGoal = 2400)
            }
        }
    }

    private fun pump(ms: Long) {
        var waited = 0L
        while (waited < ms) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(50)
            Thread.sleep(50)
            waited += 50
        }
    }

    private fun count(m: SemanticsMatcher): Int = runCatching {
        composeRule.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false).size
    }.getOrDefault(0)

    private fun waitFor(flow: String, step: String, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > 25_000L) fail("[$flow] step '$step' not reached")
            pump(100)
        }
    }

    private fun shot(name: String) {
        pump(200)
        val f = File("build/screenshots/flows/$name.png")
        f.parentFile?.mkdirs()
        runCatching { composeRule.onRoot().captureRoboImage(f.path) }
    }

    // ------------------------------------------------------------------ 1

    @Test
    fun usualDayLogic() {
        val flow = "usualDayLogic"
        val start = 20_000L
        val today = start + 5
        // logged: days 0, 1, 3; day 2 and day 4 are empty; day 5 is today
        val eaten = mapOf(start to 2000, start + 1 to 2400, start + 3 to 2200)
        assertEquals("[$flow] the usual day is the average of the logged days", 2200, WeekCalories.usualIntake(eaten, today - 1, today))
        assertEquals("[$flow] nothing logged, nothing to average", 0, WeekCalories.usualIntake(emptyMap(), today - 1, today))
        // today's own meals never enter the average
        assertEquals("[$flow] today is not part of it", 2200, WeekCalories.usualIntake(eaten + (today to 9000), today, today))
        // three recent days win over an old one
        val old = eaten + (start - 100 to 5000)
        assertEquals("[$flow] the last 30 days decide", 2200, WeekCalories.usualIntake(old, today - 1, today))
        // fewer than three recent days: every logged day counts
        assertEquals("[$flow] too few recent days, all of them count", 3000, WeekCalories.usualIntake(mapOf(start to 1000, start - 100 to 5000), today - 1, today))

        val usual = mapOf(start + 2 to 2200, start + 4 to 2200)
        val days = WeekCalories.week(start, today, eaten, GoalDirection.CUT, 2500, usual)
        assertEquals("[$flow] six days up to today", 6, days.size)
        assertTrue("[$flow] the empty day is estimated", days[2].estimated)
        assertEquals("[$flow] with the usual calories", 2200, days[2].eaten)
        assertNull("[$flow] and not judged against the goal", days[2].ok)
        assertTrue("[$flow] a logged day is not estimated", !days[1].estimated)
        assertTrue("[$flow] today is never estimated", !days[5].estimated && days[5].isToday)
        assertEquals("[$flow] two estimated days", 2, WeekCalories.estimatedCount(days))
        assertEquals("[$flow] three judged days", 3, WeekCalories.judgedCount(days))

        // balance against a burn of 2500 a day: the two estimated days count, today does not
        val burn = (start..today).associate { it to 2500 }
        // (2000 + 2400 + 2200 + 2200 + 2200) - 5 * 2500 = -1500
        assertEquals("[$flow] the estimated days are in the balance", -1500, WeekCalories.totalBalance(days, burn))
        // the old call, without usual days, is unchanged
        val plain = WeekCalories.week(start, today, eaten, GoalDirection.CUT, 2500)
        assertEquals("[$flow] without usual days only the logged ones count", -900, WeekCalories.totalBalance(plain, burn))
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun energyDaysFromDb() {
        val flow = "energyDaysFromDb"
        seedProfile()
        val today = Days.today()
        // far in the past, so nothing another test wrote is in the way
        val base = today - 400
        runBlocking {
            for ((day, kcal) in listOf(base + 1 to 2000, base + 2 to 2400, base + 4 to 2200)) {
                AppGraph.db.meals().insert(
                    MealEntity(
                        dateEpochDay = day, atMillis = System.currentTimeMillis(), description = "ארוחת בדיקה",
                        kcal = kcal, proteinG = 100f, carbsG = 200f, fatG = 60f, source = "manual", aiKcal = 0, itemsJson = ""
                    )
                )
            }
        }
        val days: List<EnergyDay> = runBlocking { EnergyData.days(base, base + 4) }
        assertEquals("[$flow] five days", 5, days.size)
        val first = runBlocking { AppGraph.db.meals().allOnce() }.filter { it.kcal > 0 }.minOf { it.dateEpochDay }
        if (first == base + 1) {
            assertTrue("[$flow] the day before his first meal stays empty", days[0].intake == 0 && !days[0].estimated)
        }
        assertEquals("[$flow] a logged day keeps its calories", 2000, days[1].intake)
        assertTrue("[$flow] a logged day is not estimated", !days[1].estimated)
        assertTrue("[$flow] the empty day between logged days is estimated", days[3].estimated)
        assertEquals("[$flow] with the average of the logged days", 2200, days[3].intake)
        assertTrue("[$flow] every day has a real burn, got ${days.map { it.burn }}", days.all { it.burn > 1000 })

        val t = EnergyData.totalsOf(days, today)
        assertEquals("[$flow] one estimated day in the totals", 1, t.estimatedDays)
        assertEquals("[$flow] eaten: three logged days and one usual day", 2000 + 2400 + 2200 + 2200, t.eaten)
        assertEquals("[$flow] the balance is eaten minus burned", t.eaten - t.burned, t.balance)
        // a finished-days-only sum: the same days seen as "today is base + 4" drop the last one
        val open = EnergyData.totalsOf(days, base + 4)
        assertEquals("[$flow] the open day is left out", 2000 + 2400 + 2200, open.eaten)
    }

    // ------------------------------------------------------------------ 3

    private fun set(exercise: String, kg: Float, reps: Int, done: Boolean = true): SetLogEntity =
        SetLogEntity(sessionId = 1, exerciseId = exercise, setIndex = 0, targetReps = reps, targetWeight = kg, reps = reps, weight = kg, completed = done)

    @Test
    fun monthProgressLogic() {
        val flow = "monthProgressLogic"
        assertEquals("[$flow] estimate of 60 kg for 10", 80f, MonthProgress.strength(60f, 10), 0.01f)
        assertEquals("[$flow] reps above 12 count as 12", MonthProgress.strength(60f, 12), MonthProgress.strength(60f, 30), 0.01f)

        val entries = mapOf(
            "bench" to listOf(
                MonthProgress.Entry(10, listOf(set("bench", 60f, 10), set("bench", 60f, 8))),
                MonthProgress.Entry(24, listOf(set("bench", 66f, 10))),
                MonthProgress.Entry(17, listOf(set("bench", 62.5f, 10), set("bench", 100f, 10, done = false)))
            ),
            "pullup" to listOf(
                MonthProgress.Entry(11, listOf(set("pullup", 0f, 10))),
                MonthProgress.Entry(25, listOf(set("pullup", 0f, 8)))
            ),
            "curl" to listOf(MonthProgress.Entry(12, listOf(set("curl", 12f, 10))))
        )
        val rows = MonthProgress.rows(entries, nameOf = { "שם $it" }, weightedOf = { it != "pullup" })
        assertEquals("[$flow] an exercise done once does not show", 2, rows.size)
        val bench = rows[0]
        assertEquals("[$flow] the biggest improvement first", "bench", bench.exerciseId)
        assertEquals("[$flow] three workouts in day order", listOf(10L, 17L, 24L), bench.points.map { it.day })
        assertEquals("[$flow] 60 -> 66 at the same reps is +10%", 10f, bench.changePct, 0.05f)
        assertEquals("[$flow] an unfinished set is ignored", 62.5f, bench.points[1].kg, 0.01f)
        assertTrue("[$flow] bench is a weighted lift", bench.weighted)
        val pull = rows[1]
        assertEquals("[$flow] bodyweight goes by reps: 10 -> 8 is -20%", -20f, pull.changePct, 0.05f)
        assertTrue("[$flow] pull-ups are not weighted", !pull.weighted)
        assertTrue("[$flow] no rows without workouts", MonthProgress.rows(emptyMap(), { it }, { true }).isEmpty())
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun summaryCards() {
        val flow = "summaryCards"
        val entries = mapOf(
            "bench" to listOf(
                MonthProgress.Entry(10, listOf(set("bench", 60f, 10))),
                MonthProgress.Entry(17, listOf(set("bench", 62.5f, 10))),
                MonthProgress.Entry(24, listOf(set("bench", 66f, 10)))
            ),
            "pullup" to listOf(
                MonthProgress.Entry(11, listOf(set("pullup", 0f, 10))),
                MonthProgress.Entry(25, listOf(set("pullup", 0f, 8)))
            )
        )
        val names = mapOf("bench" to "לחיצת חזה", "pullup" to "מתח")
        val rows = MonthProgress.rows(entries, nameOf = { names[it] ?: it }, weightedOf = { it == "bench" })
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AveragesCard(EnergyTotals(days = 30, estimatedDays = 4, burned = 30 * 2850, eaten = 30 * 2300))
                        MonthEnergyCard(EnergyTotals(days = 30, estimatedDays = 4, burned = 85_500, eaten = 69_000))
                        MonthProgressCard(rows)
                    }
                }
            }
        }
        pump(400)
        waitFor(flow, "cards shown") { count(hasTestTag("month_energy")) > 0 }
        assertTrue("[$flow] the average burn", count(hasText("2850")) > 0)
        assertTrue("[$flow] the average food", count(hasText("2300")) > 0)
        assertTrue("[$flow] the average deficit", count(hasText("550")) > 0)
        assertTrue("[$flow] the month's burn", count(hasText("85,500")) > 0)
        assertTrue("[$flow] the month's food", count(hasText("69,000")) > 0)
        assertTrue("[$flow] the month's deficit", count(hasTestTag("month_balance") and hasText("16,500")) > 0)
        assertTrue("[$flow] in kilograms of fat: 16500 / 7700", count(hasText("2.14", substring = true)) > 0)
        assertTrue("[$flow] the estimated days are said", count(hasText("4 מהם בלי רישום", substring = true)) > 0)
        assertEquals("[$flow] two exercises", 2, count(hasTestTag("month_progress_row")))
        assertTrue("[$flow] bench +10%", count(hasText("+10%", substring = true)) > 0)
        assertTrue("[$flow] pull-ups -20%", count(hasText("-20%", substring = true)) > 0)
        assertTrue("[$flow] the counts line", count(hasText("1 עלו, 1 ירדו", substring = true)) > 0)
        shot("summaryExtras_01_cards")
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun weeklyEarlierWeeks() {
        val flow = "weeklyEarlierWeeks"
        seedProfile()
        val fmt = DateTimeFormatter.ofPattern("dd/MM")
        val week = Days.weekStartOf(Days.today())
        fun range(start: Long): String = "שבוע " + Days.of(start).format(fmt) + " עד " + Days.of(start + 6).format(fmt)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme { WeeklySummaryScreen() }
            }
        }
        pump(600)
        waitFor(flow, "this week shown") { count(hasText(range(week))) > 0 }
        assertTrue("[$flow] the averages card is there", count(hasTestTag("week_averages")) > 0)
        shot("summaryExtras_02_week_now")

        println("[flow-step] $flow: a week back")
        composeRule.onAllNodes(hasTestTag("week_prev"))[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "the earlier week shown") { count(hasText(range(week - 7))) > 0 }
        assertEquals("[$flow] the current week's dates are gone", 0, count(hasText(range(week))))
        assertTrue("[$flow] the calories card is there for the earlier week", count(hasText("הקלוריות של השבוע")) > 0)
        shot("summaryExtras_03_week_back")

        println("[flow-step] $flow: and forward again")
        composeRule.onAllNodes(hasTestTag("week_next"))[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "back to this week") { count(hasText(range(week))) > 0 }
    }
}
