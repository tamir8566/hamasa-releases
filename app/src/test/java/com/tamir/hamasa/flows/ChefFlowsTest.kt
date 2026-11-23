package com.tamir.hamasa.flows

/*
 * "השף" on the food screen (data/ai/Chef + ChefStore + ui/food/ChefCard).
 *
 * Flows:
 *  1. chefParse        pure: the model's JSON -> recipes; a recipe with English, one over today's budget and one he
 *                      marked "לא בשבילי" are dropped; kcal far from its macros is recomputed; learned facts kept.
 *  2. chefStore        facts dedupe and removal; like -> recipe book; nope with a reason -> out of the book, never
 *                      suggested; cooked counts; the cloud JSON round trip keeps everything.
 *  3. chefChat         the card alone with a fake chef: he types "אני לא אוכל דגים" and sends -> his line, the chef's
 *                      answer with the recipe (ingredients + steps) and "רשמתי לעצמי"; the fact is in the store and
 *                      under "מה השף יודע עליי"; "אהבתי" -> recipe book; a second answer -> "לא בשבילי" with a reason
 *                      -> remembered; "הכנתי את זה" -> the cook callback and cooked = 1. Screenshots on the way.
 *
 * The real AI is replaced by Chef.override. Every failure names the flow and the step.
 */

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.tamir.hamasa.data.ai.Chef
import com.tamir.hamasa.data.ai.ChefRecipe
import com.tamir.hamasa.data.ai.ChefStore
import com.tamir.hamasa.data.ai.MealSuggester.MealKind as K
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.ui.food.ChefCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ChefFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (runCatching { AppGraph.workouts }.isFailure) AppGraph.init(context)
        Addr.set("M")
        ChefStore.resetForTest()
    }

    @After
    fun tearDown() {
        Chef.override = null
        com.tamir.hamasa.data.ai.RecipeImport.override = null
        com.tamir.hamasa.data.NotifPolicy.resetForTest()
        ChefStore.resetForTest()
    }

    private fun omelette() = ChefRecipe(
        nameHe = "חביתה עם קוטג'", kcal = 420, proteinG = 38f, carbsG = 20f, fatG = 20f, grams = 350, minutes = 10,
        ingredientsHe = listOf("3 ביצים", "100 גרם קוטג' 5%", "פרוסת לחם מלא"),
        stepsHe = listOf("טורפים את הביצים עם הקוטג'.", "מטגנים במחבת עם מעט שמן.", "מגישים עם הלחם."),
        whyHe = "הרבה חלבון ונכנס במה שנשאר."
    )

    private fun shakshuka() = ChefRecipe(
        nameHe = "שקשוקה", kcal = 380, proteinG = 22f, carbsG = 18f, fatG = 24f, grams = 400, minutes = 20,
        ingredientsHe = listOf("רוטב עגבניות", "3 ביצים"), stepsHe = listOf("מחממים רוטב.", "שוברים ביצים ומכסים.")
    )

    // ================================================================== 1. parse

    @Test
    fun chefParse() {
        val flow = "chefParse"
        ChefStore.nope(shakshuka(), "")
        val json = JSONObject(
            """
            {"reply_he":"הנה כמה רעיונות.","learned_he":["לא אוכל דגים ומאכלי ים","Likes spicy"],
             "recipes":[
              {"name_he":"חביתה עם קוטג'","ingredients_he":["3 ביצים","100 גרם קוטג'"],"steps_he":["טורפים.","מטגנים."],"minutes":10,"grams":300,"kcal":9999,"protein_g":38,"carbs_g":20,"fat_g":20,"why_he":"חלבוני."},
              {"name_he":"Pasta","ingredients_he":["פסטה"],"steps_he":["מבשלים."],"kcal":400,"protein_g":15,"carbs_g":70,"fat_g":5},
              {"name_he":"סטייק ענק","ingredients_he":["600 גרם אנטריקוט"],"steps_he":["צולים."],"kcal":1500,"protein_g":120,"carbs_g":0,"fat_g":110},
              {"name_he":"שקשוקה","ingredients_he":["ביצים"],"steps_he":["מבשלים."],"kcal":380,"protein_g":22,"carbs_g":18,"fat_g":24}
             ]}
            """.trimIndent()
        )
        val r = Chef.parse(json, budgetKcal = 700)
        assertNotNull("[$flow] nothing parsed", r)
        assertEquals("[$flow] only the omelette survives", listOf("חביתה עם קוטג'"), r!!.recipes.map { it.nameHe })
        assertEquals("[$flow] kcal recomputed from macros (38*4+20*4+20*9)", 412, r.recipes[0].kcal)
        assertEquals("[$flow] learned: Hebrew only", listOf("לא אוכל דגים ומאכלי ים"), r.learnedHe)
        assertEquals("[$flow] reply", "הנה כמה רעיונות.", r.textHe)
    }

    // ================================================================== 2. store

    @Test
    fun chefStore() {
        val flow = "chefStore"
        assertTrue("[$flow] first fact added", ChefStore.addFacts(listOf("לא אוכל דגים ומאכלי ים")))
        assertFalse("[$flow] same fact again (period, spaces) not added", ChefStore.addFacts(listOf("לא  אוכל דגים ומאכלי ים.")))
        ChefStore.addFacts(listOf("שונא עגבנייה שלמה, רוטב עגבניות בסדר"))
        assertEquals("[$flow] two facts", 2, ChefStore.current().facts.size)
        ChefStore.removeFact("לא אוכל דגים ומאכלי ים")
        assertEquals("[$flow] one fact after removal", 1, ChefStore.current().facts.size)

        ChefStore.like(omelette())
        assertEquals("[$flow] liked -> in the book", true, ChefStore.inBook("חביתה עם קוטג'")?.liked)
        ChefStore.cooked(omelette())
        assertEquals("[$flow] cooked once", 1, ChefStore.inBook("חביתה עם קוטג'")?.cooked)
        ChefStore.like(shakshuka())
        ChefStore.nope(shakshuka(), "יותר מדי עבודה")
        assertEquals("[$flow] nope -> out of the book", null, ChefStore.inBook("שקשוקה"))
        assertTrue("[$flow] nope remembered", ChefStore.isNope("שקשוקה"))
        assertEquals("[$flow] nope line with reason", listOf("שקשוקה: יותר מדי עבודה"), ChefStore.current().nope)

        val cloud = JSONObject()
        ChefStore.putCloud(cloud)
        ChefStore.resetForTest()
        ChefStore.restoreFromCloud(cloud)
        val s = ChefStore.current()
        assertEquals("[$flow] cloud: facts", listOf("שונא עגבנייה שלמה, רוטב עגבניות בסדר"), s.facts)
        assertEquals("[$flow] cloud: book", listOf("חביתה עם קוטג'"), s.book.map { it.nameHe })
        assertEquals("[$flow] cloud: ingredients kept", 3, s.book[0].ingredientsHe.size)
        assertTrue("[$flow] cloud: nope", ChefStore.isNope("שקשוקה"))
    }

    // ================================================================== 1b. meal from the message, dinner range

    @Test
    fun chefKindAndDinnerRange() {
        val flow = "chefKindAndDinnerRange"
        assertEquals("[$flow] dinner", K.DINNER, Chef.kindFromText("מה להכין לארוחת ערב?"))
        assertEquals("[$flow] dinner 2", K.DINNER, Chef.kindFromText("משהו לערב"))
        assertEquals("[$flow] lunch", K.LUNCH, Chef.kindFromText("רעיון לצהריים"))
        assertEquals("[$flow] breakfast", K.BREAKFAST, Chef.kindFromText("ארוחת בוקר מהירה"))
        assertEquals("[$flow] snack", K.SNACK, Chef.kindFromText("בא לי נשנוש"))
        assertEquals("[$flow] 'מערבבים' is not dinner", null, Chef.kindFromText("סלט מעורב ומערבבים"))
        assertEquals("[$flow] nothing named", null, Chef.kindFromText("משהו מהיר"))

        assertEquals("[$flow] range for 650", Pair(553, 680), Chef.dinnerRange(650))
        val list = listOf(omelette().copy(kcal = 395), shakshuka().copy(nameHe = "עוף ופסטה", kcal = 564))
        assertEquals("[$flow] dinner keeps only the closing recipe", listOf("עוף ופסטה"), Chef.fitKind(list, K.DINNER, 650).map { it.nameHe })
        assertEquals("[$flow] lunch keeps both", 2, Chef.fitKind(list, K.LUNCH, 650).size)

        ChefStore.addRecent(listOf("עוף ופסטה", "חביתה"))
        ChefStore.addRecent(listOf("עוף ופסטה"))
        assertEquals("[$flow] recent: no duplicates, newest last", listOf("חביתה", "עוף ופסטה"), ChefStore.current().recent)
    }

    // ================================================================== 2b. answer away from the screen

    /** No chef card on screen: the question still runs; the answer is stored and "השף ענה" is posted. */
    @Test
    fun chefAnswersAway() {
        val flow = "chefAnswersAway"
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // the "השף ענה" notification exists only with the full notifications; his default rule skips it
        com.tamir.hamasa.data.NotifPolicy.setEssentialOnly(app, false)
        Chef.override = { _ -> Chef.Reply.Ok("יש לי רעיון.", listOf(omelette()), emptyList()) }
        com.tamir.hamasa.data.ai.ChefRunner.cardVisible = false
        com.tamir.hamasa.data.ai.ChefRunner.send(app, "מה להכין עכשיו?", MacroTargets(2400, 180, 240, 70), emptyList(), 100f,
            com.tamir.hamasa.data.ai.MealSuggester.MealKind.DINNER)
        val start = System.currentTimeMillis()
        while (com.tamir.hamasa.data.ai.ChefRunner.pending.value != null) {
            if (System.currentTimeMillis() - start > 15_000L) fail("[$flow] the question never finished")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val chat = ChefStore.todayChat()
        assertEquals("[$flow] his line + the answer", 2, chat.size)
        assertEquals("[$flow] the answer's recipe", "חביתה עם קוטג'", chat[1].recipes.firstOrNull()?.nameHe)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val n = Shadows.shadowOf(nm).allNotifications.lastOrNull { it.channelId == "chef_answers" }
        assertNotNull("[$flow] no 'השף ענה' notification", n)
        assertEquals("[$flow] title", "השף ענה", n!!.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("[$flow] opens the chef", com.tamir.hamasa.data.ai.ChefRunner.ACTION_OPEN_CHEF,
            Shadows.shadowOf(n.contentIntent).savedIntent.action)
    }

    /** The connection is cut while the app is left: the chef waits for him to come back and asks once more. */
    @Test
    fun chefRetryAfterReturn() {
        val flow = "chefRetryAfterReturn"
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var calls = 0
        Chef.override = { _ ->
            calls += 1
            if (calls == 1) Chef.Reply.Failed else Chef.Reply.Ok("הנה.", listOf(omelette()), emptyList())
        }
        com.tamir.hamasa.data.ai.ChefRunner.cardVisible = false
        com.tamir.hamasa.data.update.UpdateCenter.setForeground(false)
        com.tamir.hamasa.data.ai.ChefRunner.send(app, "מה להכין עכשיו?", MacroTargets(2400, 180, 240, 70), emptyList(), 100f,
            com.tamir.hamasa.data.ai.MealSuggester.MealKind.DINNER)
        val t0 = System.currentTimeMillis()
        while (calls < 1 && System.currentTimeMillis() - t0 < 10_000L) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        Thread.sleep(500)
        assertEquals("[$flow] still waiting while he is away", 1, calls)
        assertTrue("[$flow] still pending", com.tamir.hamasa.data.ai.ChefRunner.pending.value != null)
        com.tamir.hamasa.data.update.UpdateCenter.setForeground(true)
        val start = System.currentTimeMillis()
        while (com.tamir.hamasa.data.ai.ChefRunner.pending.value != null) {
            if (System.currentTimeMillis() - start > 15_000L) fail("[$flow] never finished after returning")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        com.tamir.hamasa.data.update.UpdateCenter.setForeground(false)
        assertEquals("[$flow] asked twice", 2, calls)
        val chat = ChefStore.todayChat()
        assertEquals("[$flow] the answer arrived", "חביתה עם קוטג'", chat.lastOrNull()?.recipes?.firstOrNull()?.nameHe)
    }

    // ================================================================== 3. the chat

    private val flowStart = System.currentTimeMillis()

    private fun pump(ms: Long) {
        var waited = 0L
        while (waited < ms) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(50)
            Thread.sleep(50)
            waited += 50
        }
        if (System.currentTimeMillis() - flowStart > 240_000L) fail("flow timed out after 4 minutes")
    }

    private fun count(m: SemanticsMatcher): Int = runCatching {
        composeRule.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false).size
    }.getOrDefault(0)

    private fun waitFor(flow: String, step: String, cond: () -> Boolean) {
        println("[flow-step] $flow: wait $step")
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > 15_000L) {
                shot("${flow}_00_failed")
                fail("[$flow] step '$step' not reached")
            }
            pump(100)
        }
    }

    private fun tap(flow: String, step: String, m: SemanticsMatcher, index: Int = 0) {
        println("[flow-step] $flow: $step")
        val nodes = composeRule.onAllNodes(m)
        if (count(m) <= index) { shot("${flow}_00_failed"); fail("[$flow] step '$step': nothing to tap") }
        nodes[index].performSemanticsAction(SemanticsActions.OnClick)
        pump(300)
    }

    private fun shot(name: String) {
        pump(200)
        val f = File("build/screenshots/flows/$name.png")
        f.parentFile?.mkdirs()
        runCatching { composeRule.onRoot().captureRoboImage(f.path) }
    }

    private fun clickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

    @Test
    fun chefChat() {
        val flow = "chefChat"
        var turn = 0
        Chef.override = { _ ->
            turn += 1
            if (turn == 1) Chef.Reply.Ok("רשמתי. הנה משהו בלי דגים שנכנס במה שנשאר.", listOf(omelette()), listOf("לא אוכל דגים ומאכלי ים"))
            else Chef.Reply.Ok("עוד רעיון לערב.", listOf(shakshuka()), emptyList())
        }
        val cooked = ArrayList<String>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ChefCard(
                                targets = MacroTargets(2400, 180, 240, 70),
                                meals = emptyList(),
                                bodyweightKg = 100f,
                                onCook = { r -> cooked.add(r.nameHe) }
                            )
                        }
                    }
                }
            }
        }
        pump(400)
        waitFor(flow, "card shown") { count(hasText("השף")) > 0 && count(hasText("נשארו להיום 2400 קלוריות", substring = true)) == 1 }
        shot("${flow}_01_empty")

        println("[flow-step] $flow: type and send")
        composeRule.onNode(hasSetTextAction() and hasText("הודעה לשף")).performTextReplacement("אני לא אוכל דגים")
        pump(300)
        tap(flow, "send", clickable("שליחה"))
        waitFor(flow, "recipe shown") { count(hasText("חביתה עם קוטג'")) > 0 && count(hasText("3 ביצים", substring = true)) > 0 }
        assertTrue("[$flow] his line shown", count(hasText("אני לא אוכל דגים")) > 0)
        assertTrue("[$flow] learned line shown", count(hasText("רשמתי לעצמי", substring = true)) > 0)
        assertEquals("[$flow] fact stored", listOf("לא אוכל דגים ומאכלי ים"), ChefStore.current().facts)
        shot("${flow}_02_answer")

        tap(flow, "like", clickable("אהבתי"))
        waitFor(flow, "in the book") { ChefStore.inBook("חביתה עם קוטג'")?.liked == true }
        waitFor(flow, "book label") { count(hasText("בספר המתכונים")) > 0 }

        tap(flow, "open facts", clickable("מה השף יודע עליי (1)"))
        waitFor(flow, "fact listed") { count(hasText("לא אוכל דגים ומאכלי ים", substring = true)) > 0 }
        shot("${flow}_03_facts")

        tap(flow, "cooked", clickable("הכנתי את זה"))
        assertEquals("[$flow] cook callback", listOf("חביתה עם קוטג'"), cooked)
        assertEquals("[$flow] cooked counted", 1, ChefStore.inBook("חביתה עם קוטג'")?.cooked)

        tap(flow, "ask again", clickable("משהו מהיר"))
        waitFor(flow, "second recipe") { count(hasText("שקשוקה")) > 0 }
        // "חשוב שוב": one tap asks the chef for another idea (no typing)
        val rethinks = count(clickable("חשוב שוב"))
        tap(flow, "rethink", clickable("חשוב שוב"), index = rethinks - 1)
        waitFor(flow, "rethink line sent") { count(hasText("תחשוב מחדש ותציע משהו אחר במקום שקשוקה")) > 0 }
        waitFor(flow, "third answer") { turn >= 3 && count(hasText("עוד רעיון לערב.")) >= 2 }
        shot("${flow}_03b_rethink")
        // the shakshuka card is the last recipe: its "לא בשבילי" is the last one
        val nopes = count(clickable("לא בשבילי"))
        tap(flow, "not for me", clickable("לא בשבילי"), index = nopes - 1)
        waitFor(flow, "reason field") { count(hasSetTextAction() and hasText("למה? (לא חובה)")) > 0 }
        composeRule.onNode(hasSetTextAction() and hasText("למה? (לא חובה)")).performTextReplacement("יותר מדי עבודה")
        pump(300)
        tap(flow, "save reason", clickable("שמירה"))
        waitFor(flow, "nope stored") { ChefStore.isNope("שקשוקה") }
        assertEquals("[$flow] nope with reason", listOf("שקשוקה: יותר מדי עבודה"), ChefStore.current().nope)
        waitFor(flow, "nope label") { count(hasText("רשמתי. לא אציע את זה שוב.")) > 0 }
        shot("${flow}_04_nope")
    }

    /** His own recipe goes straight into the book (and the chef's prompt learns from it). */
    @Test
    fun chefAddOwnRecipe() {
        val flow = "chefAddOwnRecipe"
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ChefCard(targets = MacroTargets(2400, 180, 240, 70), meals = emptyList(), bodyweightKg = 100f, onCook = { })
                        }
                    }
                }
            }
        }
        pump(400)
        waitFor(flow, "card shown") { count(hasText("נשארו להיום 2400 קלוריות", substring = true)) == 1 }
        tap(flow, "open book", clickable("ספר המתכונים (0)"))
        tap(flow, "open add form", clickable("הוספת מתכון משלי לספר"))
        waitFor(flow, "form shown") { count(hasSetTextAction() and hasText("שם המתכון")) > 0 }
        composeRule.onNode(hasSetTextAction() and hasText("שם המתכון")).performTextReplacement("פסטה של סבתא")
        composeRule.onNode(hasSetTextAction() and hasText("מצרכים", substring = true)).performTextReplacement("פסטה\nרוטב עגבניות")
        pump(300)
        shot("${flow}_01_form")
        tap(flow, "save", clickable("הוספה לספר"))
        waitFor(flow, "in the book") { ChefStore.inBook("פסטה של סבתא")?.liked == true }
        assertEquals("[$flow] ingredients kept", listOf("פסטה", "רוטב עגבניות"), ChefStore.inBook("פסטה של סבתא")?.ingredientsHe)
        waitFor(flow, "book row shown") { count(hasText("פסטה של סבתא", substring = true)) > 0 }
        shot("${flow}_02_added")
    }
    /** Pasted recipe text -> "קרא את המתכון" fills the form (name, ingredients, kcal, protein) -> saved with its macros. */
    @Test
    fun chefImportText() {
        val flow = "chefImportText"
        var asked = ""
        com.tamir.hamasa.data.ai.RecipeImport.override = { src ->
            asked = src
            com.tamir.hamasa.data.ai.RecipeImport.Result.Ok(
                ChefRecipe(
                    nameHe = "עוף בצ'ילי ודבש", kcal = 610, proteinG = 52f, carbsG = 60f, fatG = 16f, grams = 420, minutes = 25,
                    ingredientsHe = listOf("800 גרם חזה עוף", "4 כפות דבש"), stepsHe = listOf("צורבים את העוף.", "מוסיפים דבש."),
                    servings = 4
                )
            )
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ChefCard(targets = MacroTargets(2400, 180, 240, 70), meals = emptyList(), bodyweightKg = 100f, onCook = { })
                        }
                    }
                }
            }
        }
        pump(400)
        tap(flow, "open book", clickable("ספר המתכונים (0)"))
        tap(flow, "open add form", clickable("הוספת מתכון משלי לספר"))
        waitFor(flow, "import field shown") { count(hasSetTextAction() and hasText("קישור ליוטיוב או טקסט של מתכון")) > 0 }
        composeRule.onNode(hasSetTextAction() and hasText("קישור ליוטיוב או טקסט של מתכון"))
            .performTextReplacement("Honey chili chicken. 200g chicken breast, 1 tbsp honey. Sear the chicken, add honey.")
        pump(300)
        tap(flow, "read", clickable("קרא את המתכון"))
        waitFor(flow, "form filled") { count(hasText("עוף בצ'ילי ודבש")) > 0 }
        assertTrue("[$flow] the pasted text went to the import", asked.startsWith("text:"))
        assertTrue("[$flow] kcal filled", count(hasText("610")) > 0)
        assertTrue("[$flow] servings filled", count(hasSetTextAction() and hasText("4")) > 0)
        // the whole recipe is 4 × 610 = 2440 kcal; changing the servings re-divides it (2 portions: 1220 each)
        assertTrue("[$flow] whole recipe line", count(hasText("כל המתכון: 2440 קלוריות, 208 חלבון", substring = true)) > 0)
        composeRule.onNode(hasSetTextAction() and hasText("לכמה מנות המתכון")).performTextReplacement("2")
        pump(300)
        waitFor(flow, "kcal re-divided") { count(hasSetTextAction() and hasText("1220")) > 0 }
        assertTrue("[$flow] protein re-divided", count(hasSetTextAction() and hasText("104")) > 0)
        composeRule.onNode(hasSetTextAction() and hasText("לכמה מנות המתכון")).performTextReplacement("4")
        pump(300)
        waitFor(flow, "kcal back") { count(hasSetTextAction() and hasText("610")) > 0 }
        pump(300)
        shot("${flow}_01_filled")
        tap(flow, "save", clickable("הוספה לספר"))
        waitFor(flow, "in the book") { ChefStore.inBook("עוף בצ'ילי ודבש")?.liked == true }
        val saved = ChefStore.inBook("עוף בצ'ילי ודבש")!!
        assertEquals("[$flow] protein kept", 52f, saved.proteinG, 0.01f)
        assertEquals("[$flow] carbs kept from the import", 60f, saved.carbsG, 0.01f)
        assertEquals("[$flow] ingredients kept at the original amounts", listOf("800 גרם חזה עוף", "4 כפות דבש"), saved.ingredientsHe)
        assertEquals("[$flow] servings kept", 4, saved.servings)
        // the book shows the recipe with its portion card and the one-portion button
        tap(flow, "open in book", clickable("עוף בצ'ילי ודבש · 610 קלוריות למנה · אהבת"))
        waitFor(flow, "portion card") { count(hasText("המתכון מספיק ל-4 מנות")) > 0 }
        assertTrue("[$flow] portion line", count(hasText("במנה: 610 קלוריות, 52 חלבון.")) > 0)
        assertTrue("[$flow] one-portion button", count(clickable("הכנתי ואכלתי מנה אחת")) > 0)
        pump(300)
        shot("${flow}_02_portion")
    }

    /** An Instagram link cannot be read: the screen says so and the form stays empty. YouTube links are normalized. */
    @Test
    fun chefImportLinks() {
        val flow = "chefImportLinks"
        val r = kotlinx.coroutines.runBlocking { com.tamir.hamasa.data.ai.RecipeImport.fromText("https://www.instagram.com/reel/abc123/") }
        assertTrue("[$flow] instagram is unsupported", r is com.tamir.hamasa.data.ai.RecipeImport.Result.UnsupportedLink)
        assertEquals("[$flow] youtu.be", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", com.tamir.hamasa.data.ai.RecipeImport.youtubeUrl("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals("[$flow] shorts", "https://www.youtube.com/watch?v=abcDEF12345", com.tamir.hamasa.data.ai.RecipeImport.youtubeUrl("https://youtube.com/shorts/abcDEF12345"))
        var asked = ""
        com.tamir.hamasa.data.ai.RecipeImport.override = { src -> asked = src; com.tamir.hamasa.data.ai.RecipeImport.Result.NotRecipe }
        kotlinx.coroutines.runBlocking { com.tamir.hamasa.data.ai.RecipeImport.fromText("https://m.youtube.com/watch?v=abcDEF12345&t=3") }
        assertEquals("[$flow] youtube goes to the video reader", "video:https://www.youtube.com/watch?v=abcDEF12345", asked)
        // the parser: a recipe answer, and "no recipe"
        val ok = com.tamir.hamasa.data.ai.RecipeImport.parse(org.json.JSONObject(
            "{\"is_recipe\":true,\"name_he\":\"פסטה\",\"ingredients_he\":[\"80 גרם פסטה\"],\"steps_he\":[\"מבשלים.\"],\"kcal\":300,\"protein_g\":10,\"carbs_g\":58,\"fat_g\":2}"))
        assertTrue("[$flow] parsed", ok is com.tamir.hamasa.data.ai.RecipeImport.Result.Ok)
        val no = com.tamir.hamasa.data.ai.RecipeImport.parse(org.json.JSONObject("{\"is_recipe\":false}"))
        assertTrue("[$flow] not a recipe", no is com.tamir.hamasa.data.ai.RecipeImport.Result.NotRecipe)
    }

    /** The built-in book: every row parses, numbers agree, every meal has choices, his hard rules hold. */
    @Test
    fun recipeLibraryRows() {
        val flow = "recipeLibraryRows"
        val all = com.tamir.hamasa.data.ai.RecipeLibrary.all
        assertTrue("[$flow] about 90 recipes, got ${all.size}", all.size >= 85)
        for (r in all) {
            assertTrue("[$flow] ${r.nameHe}: kcal agrees with macros", com.tamir.hamasa.data.ai.RecipeLibrary.consistent(r))
            assertTrue("[$flow] ${r.nameHe}: has ingredients and steps", r.ingredientsHe.isNotEmpty() && r.stepsHe.isNotEmpty())
            val text = (listOf(r.nameHe) + r.ingredientsHe + r.stepsHe).joinToString(" ")
            val words = text.split(Regex("[^\u05d0-\u05ea']+")).toSet()
            assertTrue("[$flow] ${r.nameHe}: no fish", "דג" !in words && "דגים" !in words)
            for (w in listOf("סלמון", "טונה", "סושי", "שרימפס", "קוקוס", "עגבניות שרי", "עגבנייה שלמה"))
                assertTrue("[$flow] ${r.nameHe}: no '$w'", !text.contains(w))
            assertTrue("[$flow] ${r.nameHe}: Hebrew only", com.tamir.hamasa.data.ai.CoachNotes.isCleanHebrew(r.nameHe, 60))
        }
        for (k in com.tamir.hamasa.data.ai.MealSuggester.MealKind.values()) {
            val picked = com.tamir.hamasa.data.ai.RecipeLibrary.pick(k, 700, emptyList(), seed = 7L)
            assertTrue("[$flow] $k has choices", picked.size >= 8)
            val again = com.tamir.hamasa.data.ai.RecipeLibrary.pick(k, 700, emptyList(), seed = 8L)
            assertTrue("[$flow] $k rotates", picked.map { it.nameHe } != again.map { it.nameHe })
        }
        val first = com.tamir.hamasa.data.ai.RecipeLibrary.pick(com.tamir.hamasa.data.ai.MealSuggester.MealKind.LUNCH, 700, emptyList(), seed = 7L).first()
        val skipped = com.tamir.hamasa.data.ai.RecipeLibrary.pick(com.tamir.hamasa.data.ai.MealSuggester.MealKind.LUNCH, 700, listOf(first.nameHe), seed = 7L)
        assertTrue("[$flow] skip works", skipped.none { it.nameHe == first.nameHe })
    }
}
