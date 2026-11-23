package com.tamir.hamasa.flows

/*
 * "מאגר המאכלים" (domain/food/FoodBase, data/food/FoodBaseStore + FoodSeed, ui/food/FoodBaseCard).
 *
 * Flows:
 *  1. foodBaseSeed     every built-in row parses; Hebrew only; sane numbers; no two foods share a name or alias.
 *  2. foodBaseParse    pure: a meal text whose every food is known becomes items with the right grams and kcal
 *                      ("200 גרם חזה עוף ואורז", "2 ביצים, כף טחינה גולמית, פרוסת לחם", "חצי כוס אורז"); a text with
 *                      an unknown food or an unclear amount gives null, so the AI is asked.
 *  3. foodBaseLearn    the store: a saved meal teaches the unknown food only; the learned food then answers a text
 *                      and the "חישוב מחדש" of one item with no AI; a hand correction wins over the built-in food
 *                      (also through its aliases); AI items take the base numbers; the cloud JSON round trip keeps
 *                      his rows; the meals already logged are learned once.
 *  4. foodBaseDescribe the food view model: a known text goes straight to the review, with no AI and no key.
 *  5. foodBaseCard     the card alone: search a built-in food, open it, correct its calories, save -> the store
 *                      has his value; add a new food by hand -> it is found. Screenshots on the way.
 */

import android.os.Looper
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.food.FoodBaseStore
import com.tamir.hamasa.data.food.FoodSeed
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.FoodFact
import com.tamir.hamasa.domain.food.FoodIndex
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.FoodText
import com.tamir.hamasa.ui.food.FoodBaseCard
import com.tamir.hamasa.ui.food.FoodViewModel
import com.tamir.hamasa.ui.food.PhotoPhase
import com.tamir.hamasa.ui.theme.HamasaTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class FoodBaseFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
        MealEstimator.itemOverride = null
        FoodBaseStore.resetForTests()
    }

    @After
    fun tearDown() {
        FoodBaseStore.resetForTests()
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

    private fun click(tag: String) {
        composeRule.onAllNodes(hasTestTag(tag))[0].performSemanticsAction(SemanticsActions.OnClick)
        pump(200)
    }

    // ------------------------------------------------------------------ 1

    @Test
    fun foodBaseSeed() {
        val flow = "foodBaseSeed"
        val rows = FoodSeed.rows
        val facts = FoodSeed.facts
        assertEquals("[$flow] every row parses", rows.size, facts.size)
        assertTrue("[$flow] a big base, got ${facts.size}", facts.size >= 700)
        val owner = HashMap<String, String>()
        val clashes = ArrayList<String>()
        for (f in facts) {
            assertTrue("[$flow] kcal of ${f.nameHe}", f.kcal100 >= 0f && f.kcal100 <= 950f)
            assertTrue("[$flow] macros of ${f.nameHe}", f.protein100 in 0f..100f && f.carbs100 in 0f..100f && f.fat100 in 0f..100f)
            assertTrue("[$flow] portion of ${f.nameHe}", f.portionG in 1..2000)
            for (n in listOf(f.nameHe) + f.aliases) {
                assertTrue("[$flow] hebrew only: $n", n.none { it in 'a'..'z' || it in 'A'..'Z' })
                val key = FoodText.norm(n)
                assertTrue("[$flow] empty name in ${f.nameHe}", key.isNotEmpty())
                val had = owner[key]
                if (had != null && had != f.nameHe) clashes.add("$n: $had / ${f.nameHe}")
                owner[key] = f.nameHe
            }
        }
        assertTrue("[$flow] two foods share a name: $clashes", clashes.isEmpty())
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun foodBaseParse() {
        val flow = "foodBaseParse"
        val idx = FoodIndex(FoodSeed.facts)

        val a = idx.parse("200 גרם חזה עוף ואורז")
        assertNotNull("[$flow] chicken and rice understood", a)
        assertEquals("[$flow] two items", 2, a!!.size)
        assertEquals("[$flow] chicken name", "חזה עוף", a[0].nameHe)
        assertEquals("[$flow] chicken grams", 200, a[0].grams)
        assertEquals("[$flow] chicken kcal 165 * 2", 330, a[0].kcal)
        assertEquals("[$flow] rice is the base food", "אורז לבן", a[1].nameHe)
        assertEquals("[$flow] rice usual portion", 180, a[1].grams)
        assertEquals("[$flow] rice kcal 130 * 1.8", 234, a[1].kcal)

        val b = idx.parse("2 ביצים, כף טחינה גולמית, פרוסת לחם")
        assertNotNull("[$flow] eggs, tahini, bread understood", b)
        assertEquals("[$flow] three items", 3, b!!.size)
        assertEquals("[$flow] two eggs = 110 g", 110, b[0].grams)
        assertEquals("[$flow] a spoon = 15 g", 15, b[1].grams)
        assertEquals("[$flow] one slice = 30 g", 30, b[2].grams)

        assertEquals("[$flow] half a cup of rice", 90, idx.parse("חצי כוס אורז")!![0].grams)
        assertEquals("[$flow] a cup of rice", 180, idx.parse("כוס אורז")!![0].grams)
        assertEquals("[$flow] three slices of whole bread", 90, idx.parse("3 פרוסות לחם מלא")!![0].grams)
        assertEquals("[$flow] glued grams", 250, idx.parse("250גרם פסטה")!![0].grams)
        assertEquals("[$flow] a kilo", 1000, idx.parse("קילו אבטיח")!![0].grams)

        val c = idx.parse("קוטג׳ 5% ומלפפון")
        assertNotNull("[$flow] cottage and cucumber understood", c)
        assertEquals("[$flow] cottage and cucumber", 2, c!!.size)
        assertEquals("[$flow] cottage portion", 125, c[0].grams)
        assertEquals("[$flow] one cucumber", 100, c[1].grams)

        assertNotNull("[$flow] plural by rule", idx.find("מלפפונים"))
        assertEquals("[$flow] plural: pitas", "פיתה", idx.find("פיתות")!!.nameHe)
        assertEquals("[$flow] percent written as a word", "קוטג׳ 5%", idx.find("קוטג 5 אחוז")!!.nameHe)

        assertNull("[$flow] an unknown food sends the text to the AI", idx.parse("200 גרם חזה עוף ותבשיל של דודה"))
        assertNull("[$flow] an unclear amount sends the text to the AI", idx.parse("קצת אורז"))
        assertNull("[$flow] a big bare number is unclear", idx.parse("200 חזה עוף"))
        assertNull("[$flow] empty text", idx.parse("   "))
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun foodBaseLearn() {
        val flow = "foodBaseLearn"
        assertNull("[$flow] the dish is unknown at first", FoodBaseStore.find("פשטידת ברוקולי של סבתא"))
        val added = FoodBaseStore.learn(
            listOf(
                FoodItem("פשטידת ברוקולי של סבתא", 200, 400, 20f, 30f, 22f, 0.7f),
                FoodItem("אורז", 200, 300, 6f, 62f, 1f, 0.8f)
            ),
            push = false
        )
        assertEquals("[$flow] only the unknown food is learned", 1, added)
        val dish = FoodBaseStore.find("פשטידת ברוקולי של סבתא")
        assertNotNull("[$flow] learned", dish)
        assertEquals("[$flow] per 100 g", 200f, dish!!.kcal100, 0.01f)
        assertEquals("[$flow] origin", FoodFact.ORIGIN_LEARNED, dish.origin)
        assertEquals("[$flow] rice keeps the built-in value", 130f, FoodBaseStore.find("אורז")!!.kcal100, 0.01f)

        val text = FoodBaseStore.parse("300 גרם פשטידת ברוקולי של סבתא וסלט")
        assertNotNull("[$flow] the learned food answers a text", text)
        assertEquals("[$flow] 300 g of it", 600, text!![0].kcal)
        assertEquals("[$flow] and the salad", 2, text.size)

        val one = runBlocking { MealEstimator.forItem("פשטידת ברוקולי של סבתא", 50) }
        assertNotNull("[$flow] recalculation of one item needs no AI", one)
        assertEquals("[$flow] 50 g", 100, one!!.kcal)

        // learning the same meal again adds nothing
        assertEquals("[$flow] no duplicates", 0, FoodBaseStore.learn(listOf(FoodItem("פשטידת ברוקולי של סבתא", 100, 250, 1f, 1f, 1f)), push = false))
        // an item without grams or calories is not learned
        assertEquals("[$flow] no grams, not learned", 0, FoodBaseStore.learn(listOf(FoodItem("משהו בלי משקל", 0, 120, 1f, 1f, 1f)), push = false))

        // a hand correction wins over the built-in food, through its aliases too
        val rice = FoodBaseStore.find("אורז")!!
        FoodBaseStore.put(rice.copy(kcal100 = 140f, aliases = emptyList()))
        assertEquals("[$flow] corrected by name", 140f, FoodBaseStore.find("אורז לבן")!!.kcal100, 0.01f)
        assertEquals("[$flow] corrected through the alias", 140f, FoodBaseStore.find("אורז")!!.kcal100, 0.01f)
        assertEquals("[$flow] origin edited", FoodFact.ORIGIN_EDITED, FoodBaseStore.find("אורז")!!.origin)

        // AI items: known foods take the base numbers, unknown ones stay
        val fixed = FoodBaseStore.applyKnown(
            listOf(FoodItem("חזה עוף", 200, 999, 1f, 1f, 1f, 0.6f), FoodItem("מאכל שאף אחד לא מכיר", 100, 50, 1f, 1f, 1f, 0.5f))
        )
        assertEquals("[$flow] one known", 1, fixed.second)
        assertEquals("[$flow] chicken from the base", 330, fixed.first[0].kcal)
        assertEquals("[$flow] the confidence stays", 0.6f, fixed.first[0].confidence, 0.001f)
        assertEquals("[$flow] the unknown stays", 50, fixed.first[1].kcal)

        // cloud round trip
        val cloud = JSONObject()
        FoodBaseStore.putCloud(cloud)
        FoodBaseStore.resetForTests()
        assertNull("[$flow] gone after reset", FoodBaseStore.find("פשטידת ברוקולי של סבתא"))
        FoodBaseStore.restoreFromCloud(cloud)
        assertNotNull("[$flow] back from the cloud", FoodBaseStore.find("פשטידת ברוקולי של סבתא"))
        assertEquals("[$flow] the correction is back too", 140f, FoodBaseStore.find("אורז")!!.kcal100, 0.01f)
        assertEquals("[$flow] two rows of his", 2, FoodBaseStore.user.value.size)

        // the meals he already logged
        FoodBaseStore.resetForTests()
        runBlocking {
            AppGraph.db.meals().insert(
                MealEntity(
                    dateEpochDay = Days.today() - 3, atMillis = System.currentTimeMillis(), description = "ארוחה ישנה",
                    kcal = 640, proteinG = 30f, carbsG = 60f, fatG = 30f, source = "photo", aiKcal = 640,
                    itemsJson = MealEstimator.encodeItems(
                        listOf(
                            FoodItem("קציצות עדשים של אמא", 160, 320, 16f, 30f, 14f, 0.7f),
                            FoodItem("חזה עוף", 200, 320, 60f, 0f, 7f, 0.9f)
                        )
                    )
                )
            )
        }
        val first = runBlocking { FoodBaseStore.backfillOnce() }
        assertTrue("[$flow] the old meal taught at least its unknown food, got $first", first >= 1)
        assertEquals("[$flow] learned from the old meal", 200f, FoodBaseStore.find("קציצות עדשים של אמא")!!.kcal100, 0.01f)
        assertEquals("[$flow] runs once", -1, runBlocking { FoodBaseStore.backfillOnce() })
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun foodBaseDescribe() {
        val flow = "foodBaseDescribe"
        val vm = FoodViewModel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        vm.describe("200 גרם חזה עוף ואורז")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val phase = vm.phase.value
        assertTrue("[$flow] straight to the review, got $phase", phase is PhotoPhase.Review)
        val review = phase as PhotoPhase.Review
        assertEquals("[$flow] two items", 2, review.items.size)
        assertEquals("[$flow] no AI estimate behind it", 0, review.aiKcal)
        assertEquals("[$flow] total", 564, review.items.sumOf { it.kcal })
        assertTrue("[$flow] the note says where the numbers came from", review.noteHe.contains("מהמאגר"))
        vm.cancelReview()
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun foodBaseCard() {
        val flow = "foodBaseCard"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HamasaTheme {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) { FoodBaseCard(startOpen = true) }
                }
            }
        }
        pump(400)
        waitFor(flow, "card shown") { count(hasText("מאגר המאכלים")) > 0 }
        assertTrue("[$flow] the count line", count(hasText("מאכלים מוכרים", substring = true)) > 0)
        shot("foodBase_01_open")

        println("[flow-step] $flow: search")
        composeRule.onNode(hasTestTag("food_base_search")).performTextReplacement("חזה עוף")
        pump(300)
        waitFor(flow, "search result") { count(hasTestTag("food_base_row")) > 0 }
        assertTrue("[$flow] the built-in value is shown", count(hasText("165 קלוריות במאה גרם")) > 0)
        shot("foodBase_02_search")

        println("[flow-step] $flow: correct the calories")
        click("food_base_row")
        waitFor(flow, "editor open") { count(hasTestTag("food_base_editor")) > 0 }
        composeRule.onNode(hasTestTag("food_base_kcal")).performTextReplacement("170")
        pump(200)
        shot("foodBase_03_editor")
        click("food_base_save")
        waitFor(flow, "his value stored") { FoodBaseStore.find("חזה עוף")?.kcal100 == 170f }
        assertEquals("[$flow] origin edited", FoodFact.ORIGIN_EDITED, FoodBaseStore.find("חזה עוף")!!.origin)
        assertEquals("[$flow] the alias follows", 170f, FoodBaseStore.find("חזה עוף בגריל")!!.kcal100, 0.01f)
        waitFor(flow, "the row shows his value") { count(hasText("170 קלוריות במאה גרם")) > 0 }

        println("[flow-step] $flow: a new food")
        composeRule.onNode(hasTestTag("food_base_search")).performTextReplacement("עוגת הבית")
        pump(300)
        assertTrue("[$flow] not found line", count(hasText("לא נמצא במאגר", substring = true)) > 0)
        click("food_base_add")
        waitFor(flow, "new food editor") { count(hasTestTag("food_base_name")) > 0 }
        click("food_base_save")
        waitFor(flow, "missing calories refused") { count(hasText("קלוריות למאה גרם", substring = true)) > 0 }
        assertNull("[$flow] nothing stored yet", FoodBaseStore.find("עוגת הבית"))
        composeRule.onNode(hasTestTag("food_base_kcal")).performTextReplacement("350")
        composeRule.onNode(hasTestTag("food_base_protein")).performTextReplacement("5.5")
        composeRule.onNode(hasTestTag("food_base_portion")).performTextReplacement("80")
        pump(200)
        click("food_base_save")
        waitFor(flow, "new food stored") { FoodBaseStore.find("עוגת הבית") != null }
        val cake = FoodBaseStore.find("עוגת הבית")!!
        assertEquals("[$flow] kcal", 350f, cake.kcal100, 0.01f)
        assertEquals("[$flow] protein", 5.5f, cake.protein100, 0.01f)
        assertEquals("[$flow] portion", 80, cake.portionG)
        assertEquals("[$flow] a text with it needs no AI", 280, FoodBaseStore.parse("פרוסה עוגת הבית")!![0].kcal)
        waitFor(flow, "the new food is listed") { count(hasText("עוגת הבית")) > 0 }
        shot("foodBase_04_added")
    }
}
