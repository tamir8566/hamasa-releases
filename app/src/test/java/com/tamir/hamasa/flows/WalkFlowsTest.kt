package com.tamir.hamasa.flows

/*
 * "הוספת הליכה" on the coach screen (ui/coach/ManualWalkCard + WorkoutRepository.logManualWalk).
 *  1. manualWalkAdds   type 40 minutes, tap "הוספת הליכה" -> a finished WALK session of 40 minutes exists today,
 *                      it has one completed set, and the card says "נרשם. הליכה של 40 דקות".
 *                      A value under 5 minutes is refused with a line and writes nothing.
 * Images: build/screenshots/flows/manualWalk_01_filled.png, manualWalk_02_done.png
 */

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.ui.coach.ManualWalkCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class WalkFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
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
            if (System.currentTimeMillis() - start > 20_000L) fail("[$flow] step '$step' not reached")
            pump(100)
        }
    }

    private fun shot(name: String) {
        pump(200)
        val f = File("build/screenshots/flows/$name.png")
        f.parentFile?.mkdirs()
        runCatching { composeRule.onRoot().captureRoboImage(f.path) }
    }

    @Test
    fun manualWalkAdds() {
        val flow = "manualWalkAdds"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column { ManualWalkCard() }
                }
            }
        }
        pump(300)
        val before = runBlocking { AppGraph.db.sessions().doneSessionsOnce().size }

        println("[flow-step] $flow: too short")
        composeRule.onNode(hasSetTextAction() and hasText("כמה דקות הלכת")).performTextReplacement("3")
        pump(200)
        composeRule.onAllNodes(hasText("הוספת הליכה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "refusal line") { count(hasText("דקות בין 5 ל-180.")) > 0 }
        assertEquals("[$flow] nothing written", before, runBlocking { AppGraph.db.sessions().doneSessionsOnce().size })

        println("[flow-step] $flow: 40 minutes")
        composeRule.onNode(hasSetTextAction() and hasText("כמה דקות הלכת")).performTextReplacement("40")
        composeRule.onNode(hasSetTextAction() and hasText("כמה קילומטרים", substring = true)).performTextReplacement("3.5")
        pump(200)
        shot("manualWalk_01_filled")
        composeRule.onAllNodes(hasText("הוספת הליכה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "done line") { count(hasText("נרשם. הליכה של 40 דקות", substring = true)) > 0 }
        shot("manualWalk_02_done")

        val done = runBlocking { AppGraph.db.sessions().doneSessionsOnce() }
        assertEquals("[$flow] one more finished session", before + 1, done.size)
        val walk = done.last { it.type == SessionType.WALK }
        assertEquals("[$flow] status", SessionStatus.DONE, walk.status)
        assertTrue("[$flow] duration about 40 minutes, was ${walk.durationSec}", walk.durationSec in 2380..2420)
        val sets = runBlocking { AppGraph.db.sessions().setsForOnce(walk.id) }
        assertEquals("[$flow] one completed set", 1, sets.count { it.completed })

        // his burn rule for walks: weight × km × 60%, from the km he typed (not the time)
        val kg = runBlocking { AppGraph.db.weights().latestOnce()?.kg ?: AppGraph.prefs.current().startWeightKg }
        val burn = runBlocking { com.tamir.hamasa.data.repo.EnergyData.sessionBurn(walk.id) }
        assertTrue("[$flow] burn computed", burn != null)
        assertEquals("[$flow] distance method", com.tamir.hamasa.domain.energy.BurnMethod.DISTANCE, burn!!.method)
        assertEquals("[$flow] weight × 3.5 km × 0.6", Math.round(kg * 3.5f * 0.6f), burn.kcal)
        assertTrue("[$flow] the done line shows the kcal", count(hasText("שרפת בערך ${burn.kcal} קלוריות", substring = true)) > 0)
    }

    /** The model itself: run = weight × km, walk = weight × km × 60%; no distance = km estimated from the minutes. */
    @Test
    fun distanceBurnRule() {
        val flow = "distanceBurnRule"
        val m = com.tamir.hamasa.domain.energy.EnergyModel
        val run = m.workoutKcal(com.tamir.hamasa.domain.energy.WorkoutKind.RUN, 30f, 100f, com.tamir.hamasa.domain.energy.Intensity.MODERATE, avgHr = 150, age = 21, sex = com.tamir.hamasa.domain.energy.Sex.MALE, bmrPerDay = 2000f, distanceM = 5000)
        assertEquals("[$flow] run 5 km at 100 kg = 500", 500, run.kcal)
        assertEquals("[$flow] run method", com.tamir.hamasa.domain.energy.BurnMethod.DISTANCE, run.method)
        val walk = m.workoutKcal(com.tamir.hamasa.domain.energy.WorkoutKind.WALK, 60f, 100f, com.tamir.hamasa.domain.energy.Intensity.MODERATE, avgHr = 110, age = 21, sex = com.tamir.hamasa.domain.energy.Sex.MALE, bmrPerDay = 2000f, distanceM = 4000)
        assertEquals("[$flow] walk 4 km at 100 kg = 240", 240, walk.kcal)
        val est = m.workoutKcal(com.tamir.hamasa.domain.energy.WorkoutKind.WALK, 60f, 100f, com.tamir.hamasa.domain.energy.Intensity.MODERATE, avgHr = null, age = 21, sex = com.tamir.hamasa.domain.energy.Sex.MALE, bmrPerDay = 2000f, distanceM = null)
        assertEquals("[$flow] no distance: estimated 5 km in an hour", com.tamir.hamasa.domain.energy.BurnMethod.DISTANCE_EST, est.method)
        assertEquals("[$flow] estimated walk = 100 × 5 × 0.6", 300, est.kcal)
        val gym = m.workoutKcal(com.tamir.hamasa.domain.energy.WorkoutKind.STRENGTH, 60f, 100f, com.tamir.hamasa.domain.energy.Intensity.MODERATE, avgHr = null, age = 21, sex = com.tamir.hamasa.domain.energy.Sex.MALE, bmrPerDay = 2000f, distanceM = 3000)
        assertEquals("[$flow] the gym keeps the old method", com.tamir.hamasa.domain.energy.BurnMethod.MET, gym.method)
    }

    /** "הוספת יוגה": a level, the minutes -> a finished yoga session whose burn follows the level. */
    @Test
    fun manualYogaAdds() {
        val flow = "manualYogaAdds"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column { com.tamir.hamasa.ui.coach.ManualYogaCard() }
                }
            }
        }
        pump(300)
        val before = runBlocking { AppGraph.db.sessions().doneSessionsOnce().size }

        println("[flow-step] $flow: hard, 45 minutes")
        composeRule.onAllNodes(hasText("קשה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        pump(200)
        assertTrue("[$flow] the hint follows the level", count(hasText("קשה: יוגה כוח", substring = true)) > 0)
        composeRule.onNode(hasSetTextAction() and hasText("כמה דקות יוגה")).performTextReplacement("45")
        pump(200)
        shot("manualYoga_01_filled")
        composeRule.onAllNodes(hasText("הוספת יוגה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "done line") { count(hasText("נרשם. יוגה קשה של 45 דקות", substring = true)) > 0 }
        shot("manualYoga_02_done")

        val done = runBlocking { AppGraph.db.sessions().doneSessionsOnce() }
        assertEquals("[$flow] one more finished session", before + 1, done.size)
        val yoga = done.last { it.type == SessionType.YOGA }
        assertEquals("[$flow] status", SessionStatus.DONE, yoga.status)
        assertEquals("[$flow] the level is kept", "yoga:hard", yoga.notes)
        assertTrue("[$flow] duration about 45 minutes, was ${yoga.durationSec}", yoga.durationSec in 2680..2720)
        val hard = runBlocking { com.tamir.hamasa.data.repo.EnergyData.sessionBurn(yoga.id) }!!.kcal

        // the same length at the light level burns less
        composeRule.onAllNodes(hasText("קלה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        pump(200)
        composeRule.onNode(hasSetTextAction() and hasText("כמה דקות יוגה")).performTextReplacement("45")
        pump(200)
        composeRule.onAllNodes(hasText("הוספת יוגה") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "second done line") { count(hasText("נרשם. יוגה קלה של 45 דקות", substring = true)) > 0 }
        val light = runBlocking {
            val s2 = AppGraph.db.sessions().doneSessionsOnce().last { it.type == SessionType.YOGA && it.notes == "yoga:light" }
            com.tamir.hamasa.data.repo.EnergyData.sessionBurn(s2.id)!!.kcal
        }
        assertTrue("[$flow] hard ($hard) burns more than light ($light)", hard > light && light > 0)
    }
}
