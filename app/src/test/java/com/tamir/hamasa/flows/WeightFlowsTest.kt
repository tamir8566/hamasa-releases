package com.tamir.hamasa.flows

/*
 * The weight screen (ui/weight/WeightScreen).
 *  1. weightLastTwoAndChartTap  two cards (last / previous weigh-in) with the difference; tapping a point of the
 *     chart shows that weigh-in's label.
 * Images: build/screenshots/flows/weight_cards_01.png, weight_cards_02_tapped.png
 */

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.weight.WeightScreen
import kotlinx.coroutines.runBlocking
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
class WeightFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun idle(ms: Long = 0L) {
        if (ms > 0) composeRule.mainClock.advanceTimeBy(ms)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun shot(name: String) = runCatching {
        val f = File("build/screenshots/flows/$name.png")
        f.parentFile?.mkdirs()
        composeRule.onRoot().captureRoboImage(f.path)
    }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun weightLastTwoAndChartTap() {
        val flow = "weightLastTwoAndChartTap"
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(app)
        val today = Days.today()
        runBlocking {
            AppGraph.workouts.seedIfNeeded()
            AppGraph.prefs.update { p -> p.copy(onboarded = true, startWeightKg = 103f, goalWeightKg = 90f, sex = "M", userGender = "M", characterVariant = "M") }
            AppGraph.workouts.logWeight(103f, today - 20)
            AppGraph.workouts.logWeight(102.2f, today - 12)
            AppGraph.workouts.logWeight(101.6f, today - 5)
            AppGraph.workouts.logWeight(102.0f, today - 2)
            AppGraph.workouts.logWeight(101.3f, today)
        }
        Addr.set("M"); CharacterNames.set("M")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { WeightScreen() } }
        }
        idle(1500)
        // the two cards and the difference (-0.7 toward the goal)
        assertTrue("[$flow] last card", exists("השקילה האחרונה"))
        assertTrue("[$flow] previous card", exists("השקילה שלפניה"))
        assertTrue("[$flow] last value", exists("101.3 ק\"ג"))
        assertTrue("[$flow] previous value", exists("102 ק\"ג"))
        assertTrue("[$flow] difference line", exists("ירידה של 0.7 ק\"ג בין שתי השקילות."))
        assertTrue("[$flow] difference number", exists("‎-0.7"))
        shot("weight_cards_01")
        // tap the left edge of the chart: the first weigh-in (103) is the nearest point there
        composeRule.onNode(hasTestTag("weight_chart")).performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, centerY)) }
        idle(400)
        shot("weight_cards_02_tapped")
    }
}
