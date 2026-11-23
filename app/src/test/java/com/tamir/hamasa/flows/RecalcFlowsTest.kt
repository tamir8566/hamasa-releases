package com.tamir.hamasa.flows

/*
 * "חישוב מחדש של הקלוריות" in the meal review (ui/food/MealPhotoFlow ReviewItemCard + MealEstimator.forItem).
 *
 * Flows:
 *  1. recalcAsOne        pure: two model items become one item with the typed name, numbers summed and scaled to
 *                        his portion.
 *  2. recalcAfterRename  the card of "אורז" (150 g, 195 kcal): no button at first; the name is changed to "קוסקוס"
 *                        -> the button shows; tap -> the numbers become the estimate for קוסקוס at 150 g
 *                        (fake estimator: 168 kcal), the kcal field shows 168, the button goes away. A failed
 *                        estimate shows the retry line. Screenshots before / after.
 *
 * The real AI is replaced by MealEstimator.itemOverride. Dialog-free: the card is rendered alone (Compose
 * dialogs never idle under Robolectric).
 */

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
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
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.ui.food.ReviewItemCard
import com.tamir.hamasa.ui.food.ReviewRow
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class RecalcFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
    }

    @After
    fun tearDown() {
        MealEstimator.itemOverride = null
    }

    @Test
    fun recalcAsOne() {
        val flow = "recalcAsOne"
        val one = MealEstimator.asOne(
            "קוסקוס עם עוף", 300,
            listOf(FoodItem("קוסקוס", 100, 112, 3.8f, 23f, 0.2f, 0.8f), FoodItem("עוף", 100, 165, 31f, 0f, 3.6f, 0.9f))
        )
        assertNotNull("[$flow] no item", one)
        assertEquals("[$flow] name", "קוסקוס עם עוף", one!!.nameHe)
        assertEquals("[$flow] grams kept", 300, one.grams)
        assertEquals("[$flow] kcal scaled 277 * 1.5", 416, one.kcal)
    }

    private var flowStartedAt = 0L

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
            if (System.currentTimeMillis() - start > 15_000L) fail("[$flow] step '$step' not reached")
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
    fun recalcAfterRename() {
        val flow = "recalcAfterRename"
        var answer: FoodItem? = FoodItem("x", 150, 168, 5.7f, 34.5f, 0.3f, 0.8f)
        MealEstimator.itemOverride = { name, grams -> answer?.copy(nameHe = name, grams = grams) }
        var latest: FoodItem? = null
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) {
                        var row by remember { mutableStateOf(ReviewRow(0, FoodItem("אורז", 150, 195, 4f, 42f, 0.5f, 0.9f))) }
                        latest = row.item
                        Column {
                            ReviewItemCard(
                                row = row,
                                onChange = { row = row.copy(item = it) },
                                onRecalculated = { row = row.copy(item = it, calcName = it.nameHe, calc = row.calc + 1) },
                                onDelete = { }
                            )
                        }
                    }
                }
            }
        }
        pump(300)
        val button = hasText("חישוב מחדש של הקלוריות")
        assertEquals("[$flow] button before a rename", 0, count(button))
        shot("${flow}_01_before")

        println("[flow-step] $flow: rename to קוסקוס")
        composeRule.onNode(hasSetTextAction() and hasText("אורז")).performTextReplacement("קוסקוס")
        waitFor(flow, "button shows after rename") { count(button) == 1 }
        shot("${flow}_02_renamed")

        println("[flow-step] $flow: tap recalculate")
        composeRule.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "numbers updated") { latest?.kcal == 168 }
        waitFor(flow, "button gone") { count(button) == 0 }
        assertEquals("[$flow] name kept", "קוסקוס", latest?.nameHe)
        assertEquals("[$flow] grams kept", 150, latest?.grams)
        assertEquals("[$flow] kcal field", 1, count(hasSetTextAction() and hasText("168")))
        shot("${flow}_03_recalculated")

        println("[flow-step] $flow: failed estimate")
        answer = null
        composeRule.onNode(hasSetTextAction() and hasText("קוסקוס")).performTextReplacement("בורגול")
        waitFor(flow, "button shows again") { count(button) == 1 }
        composeRule.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "failure line") { count(hasText("החישוב לא הצליח", substring = true)) == 1 }
        assertEquals("[$flow] numbers unchanged after failure", 168, latest?.kcal)
        shot("${flow}_04_failed")
    }

    @Test
    fun recalcInMealItemsEditor() {
        val flow = "recalcInMealItemsEditor"
        MealEstimator.itemOverride = { name, grams -> FoodItem(name, grams, 210, 9f, 30f, 6f, 0.8f) }
        val meal = com.tamir.hamasa.data.db.MealEntity(
            id = 1, dateEpochDay = com.tamir.hamasa.domain.Days.today(), atMillis = System.currentTimeMillis(), description = "ארוחה",
            kcal = 446, proteinG = 84f, carbsG = 0f, fatG = 10f, source = "photo", aiKcal = 446,
            itemsJson = MealEstimator.encodeItems(listOf(FoodItem("קרע עוף בתנור", 480, 446, 84f, 0f, 10f, 0.8f)))
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    com.tamir.hamasa.ui.food.MealItemsEditorBody(meal = meal, onSave = { _, _ -> }, onDismiss = { })
                }
            }
        }
        pump(300)
        val button = hasText("חישוב מחדש של הקלוריות")
        assertEquals("[$flow] no button before a rename", 0, count(button))
        shot("${flow}_01_before")

        println("[flow-step] $flow: rename to תפוחי אדמה")
        composeRule.onNode(hasSetTextAction() and hasText("קרע עוף בתנור")).performTextReplacement("תפוחי אדמה")
        waitFor(flow, "button shows after rename") { count(button) == 1 }
        shot("${flow}_02_renamed")

        println("[flow-step] $flow: tap recalculate")
        composeRule.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(flow, "button gone after recalculation") { count(button) == 0 }
        assertEquals("[$flow] kcal field 210", 1, count(hasSetTextAction() and hasText("210")))
        assertEquals("[$flow] grams kept 480", 1, count(hasSetTextAction() and hasText("480")))
        assertEquals("[$flow] total follows (210)", 1, count(hasText("210 קלוריות")))
        shot("${flow}_03_recalculated")
    }

    @Test
    fun gramsScaleInMealItemsEditor() {
        val flow = "gramsScaleInMealItemsEditor"
        val meal = com.tamir.hamasa.data.db.MealEntity(
            id = 1, dateEpochDay = com.tamir.hamasa.domain.Days.today(), atMillis = System.currentTimeMillis(), description = "ארוחה",
            kcal = 446, proteinG = 84f, carbsG = 0f, fatG = 10f, source = "photo", aiKcal = 446,
            itemsJson = MealEstimator.encodeItems(listOf(FoodItem("קרע עוף בתנור", 480, 446, 84f, 0f, 10f, 0.8f)))
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    com.tamir.hamasa.ui.food.MealItemsEditorBody(meal = meal, onSave = { _, _ -> }, onDismiss = { })
                }
            }
        }
        pump(300)
        println("[flow-step] $flow: grams 480 -> 240")
        composeRule.onNode(hasSetTextAction() and hasText("480")).performTextReplacement("240")
        pump(300)
        assertEquals("[$flow] kcal halves to 223", 1, count(hasSetTextAction() and hasText("223")))
        assertEquals("[$flow] protein halves to 42", 1, count(hasSetTextAction() and hasText("42")))
        assertEquals("[$flow] fat halves to 5", 1, count(hasSetTextAction() and hasText("5")))
        assertEquals("[$flow] total follows", 1, count(hasText("223 קלוריות")))
        shot("${flow}_01_halved")
    }
}
