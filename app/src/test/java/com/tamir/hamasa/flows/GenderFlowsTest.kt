package com.tamir.hamasa.flows

/*
 * Gender flows ("the clicking agent" for the female player + שמביולוקה). A female player (Prefs.userGender = F) with the
 * female hero (Prefs.characterVariant = F) walks over Home, Adventure, Badges, Weight, Settings, the History hub and the
 * weekly summary. On every screen: no node text / content description contains a raw "{u:" token, none contains the
 * male hero name "שמביולוק" (without the final ה), and a few female forms are spot-checked. Images:
 * build/screenshots/flows/gender_<screen>_<step>.png (record mode only).
 *
 * Flows:
 *  1. homeFemale          Home with the warrior profession -> "לוחמת" in the status row, no tokens, no male name.
 *  2. adventureFemale     Adventure hub: no tokens, no male name.
 *  3. badgesFemale        pr_king unlocked -> "מלכת השיאים" shown, "מלך השיאים" never.
 *  4. weightFemale        Weight screen + a weigh-in through the real field and button: stored, no tokens.
 *  5. settingsFemale      Settings: "את" + "שמביולוקה" selected, male watch labels absent; switching the hero back
 *                         to שמביולוק stores "M" and clears the cached AI morning line.
 *  6. historyHubFemale    History hub: runs card says "פתחי", not "פתח".
 *  7. weeklyFemale        Weekly summary: no tokens, no male "פתח את הקופסה".
 *  8. femaleArtResolves   every body stage of "F" resolves to shf_anchor_N (not the male image); every badge name and
 *                         hint in female grammar has no raw token.
 */

import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.ui.adventure.AdventureScreen
import com.tamir.hamasa.ui.badges.BadgesScreen
import com.tamir.hamasa.ui.common.CharacterArt
import com.tamir.hamasa.ui.history.HistoryHubScreen
import com.tamir.hamasa.ui.home.HomeScreen
import com.tamir.hamasa.ui.settings.SettingsScreen
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.weekly.WeeklySummaryScreen
import com.tamir.hamasa.ui.weight.WeightScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class GenderFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var flow: String = "gender"

    private val maleName = Regex("שמביולוק(?!ה)")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initialised = runCatching { AppGraph.workouts }.isSuccess
        if (!initialised) AppGraph.init(context)
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { runBlocking { AppGraph.prefs.update { it.copy(userGender = "M", characterVariant = "M") } } }
        Addr.set("M")
        CharacterNames.set("M")
    }

    // ================================================================== seeding

    /** A ready female player with שמביולוקה (weights, today's roll), as the screenshot test seeds Tamir. */
    private fun seedFemale(professionId: String = "", xp: Int = 0) {
        runBlocking {
            val today = Days.today()
            AppGraph.workouts.seedIfNeeded()
            AppGraph.prefs.update { com.tamir.hamasa.data.prefs.Profile() }
            AppGraph.workouts.logWeight(72f, today - 7)
            AppGraph.prefs.update { p ->
                p.copy(
                    onboarded = true, playerName = "דנה", heightCm = 165, startWeightKg = 74f, goalWeightKg = 64f, focus = Focus.BUILD_MUSCLE,
                    lastRollDay = today, rollModeId = "silence", rollGoal = "לעמוד בכל זמני המנוחה", rollBonus = "none",
                    age = 25, sex = "F", userGender = "F", characterVariant = "F", lifetimeKg = 0.0, kcalGoal = 1900,
                    professionId = professionId, xp = xp
                )
            }
        }
        Addr.set("F")
        CharacterNames.set("F")
    }

    // ================================================================== helpers (as in UserFlowsTest)

    /** One flow never runs longer than 4 minutes: a screen that never settles fails the flow instead of hanging CI. */
    private var flowStartedAt: Long = 0L
    private var lastBeat: Long = 0L

    private fun checkDeadline() {
        if (flowStartedAt == 0L) flowStartedAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (now - lastBeat > 15_000L) { lastBeat = now; println("[flow-beat] ${javaClass.simpleName} ${(now - flowStartedAt) / 1000}s") }
        if (System.currentTimeMillis() - flowStartedAt > 240_000L) {
            org.junit.Assert.fail("flow timed out after 4 minutes (screen never settled)")
        }
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) { content() }
                }
            }
        }
    }

    private fun settle(totalMs: Long) {
        val step = 100L
        var waited = 0L
        while (waited < totalMs) {
            Thread.sleep(step)
            waited += step
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(step)
            checkDeadline()
        }
    }

    private fun waitFor(step: String, timeoutMs: Long = 10_000L, cond: () -> Boolean) {
        println("[flow-step] wait: $step")
        val start = System.currentTimeMillis()
        while (true) {
            val ok = try { cond() } catch (t: Throwable) { false }
            if (ok) return
            if (System.currentTimeMillis() - start > timeoutMs) {
                // what is on screen right now, for the CI log
                val seen = runCatching {
                    composeRule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .joinToString(" || ") { r -> r.config.toString().take(80) + " roots" } + " :: " +
                    composeRule.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .take(60).joinToString(" | ") { n -> (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null })?.joinToString("/") ?: "" }
                }.getOrDefault("?")
                println("[flow-screen] $step :: $seen")
                fail("[$flow] step '$step': not reached within ${timeoutMs} ms. Last error: $lastCountError. Screen: ${seen.take(1200)}")
            }
            Thread.sleep(50)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(50)
            checkDeadline()
        }
    }

    private fun count(matcher: SemanticsMatcher): Int =
        try { composeRule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size } catch (t: Throwable) { lastCountError = t.toString().take(300); 0 }

    /** The last exception swallowed by [count] (shown when a wait fails: a hidden error looks like "nothing on screen"). */
    private var lastCountError: String = ""


    private fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    private fun bringIntoView(matcher: SemanticsMatcher) {
        if (!exists(matcher) && exists(hasScrollToIndexAction())) {
            // one bounded swipe per call (performScrollToNode can spin forever on a lazy grid that never shows the node)
            runCatching { composeRule.onAllNodes(hasScrollToIndexAction())[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 700f) } }
            settle(200)
        }
    }

    private fun node(step: String, matcher: SemanticsMatcher, index: Int = 0, timeoutMs: Long = 10_000L): SemanticsNodeInteraction {
        val start = System.currentTimeMillis()
        while (count(matcher) <= index) {
            bringIntoView(matcher)
            if (count(matcher) > index) break
            if (System.currentTimeMillis() - start > timeoutMs) fail("[$flow] step '$step': node not found within ${timeoutMs} ms")
            settle(100)
        }
        val n = composeRule.onAllNodes(matcher)[index]
        // no performScrollTo: its scroll animation needs the test clock, which these flows advance by hand
        return n
    }

    private fun tap(step: String, matcher: SemanticsMatcher, index: Int = 0, settleMs: Long = 400L) {
        println("[flow-step] $step")
        val n = node(step, matcher, index)
        // the click action itself (works off screen and needs no gesture / animation)
        try { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) } catch (t: Throwable) {
            try { n.performClick() } catch (t2: Throwable) { fail("[$flow] step '$step': click failed: ${t2.message}") }
        }
        settle(settleMs)
    }

    private fun typeInto(step: String, matcher: SemanticsMatcher, text: String, index: Int = 0) {
        val n = node(step, matcher, index)
        try {
            n.performTextClearance()
            n.performTextInput(text)
        } catch (t: Throwable) {
            try { n.performTextReplacement(text) } catch (t2: Throwable) { fail("[$flow] step '$step': typing failed: ${t2.message}") }
        }
        settle(250)
    }

    private fun clickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

    private fun shot(step: String) {
        settle(300)
        val file = File("build/screenshots/flows/${flow}_$step.png")
        file.parentFile?.mkdirs()
        runCatching {
            val roots = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size
            if (roots <= 1) composeRule.onRoot().captureRoboImage(file.path)
            else composeRule.onAllNodes(isRoot()).onLast().captureRoboImage(file.path)
        }
    }

    /** Every text, editable text and content description on screen (unmerged tree, all roots). */
    private fun allTexts(): List<String> {
        val out = ArrayList<String>()
        val nodes = try {
            composeRule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
        } catch (t: Throwable) { emptyList() }
        for (n in nodes) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { out.add(it.text) }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out.add(it) }
        }
        return out
    }

    /** No raw {u:..|..} token and no male hero name anywhere on screen. */
    private fun assertCleanFemale(step: String, allowExact: Set<String> = emptySet()) {
        val texts = allTexts().filter { it !in allowExact }
        assertTrue("[$flow] step '$step': the screen shows no text at all", texts.isNotEmpty())
        val tokens = texts.filter { it.contains("{u:") }
        assertTrue("[$flow] step '$step': raw {u: token on screen: $tokens", tokens.isEmpty())
        val male = texts.filter { maleName.containsMatchIn(it) }
        assertTrue("[$flow] step '$step': male hero name shown for שמביולוקה: $male", male.isEmpty())
    }

    private fun anyTextContains(part: String): Boolean = allTexts().any { it.contains(part) }

    // ================================================================== 1. home

    @Test
    fun homeFemale() {
        flow = "gender_home"
        seedFemale(professionId = "WARRIOR", xp = LevelTable.xpForLevel(Professions.UNLOCK_LEVEL) + 10)
        show {
            HomeScreen(
                onRoll = {}, onStartWorkout = {}, onOpenWeight = {}, onOpenBadges = {},
                onOpenHistory = {}, onOpenSettings = {}, onNeedOnboarding = {}
            )
        }
        settle(1500)
        shot("01_home")
        assertCleanFemale("home")
        waitFor("home: profession in female grammar (לוחמת)") { anyTextContains("לוחמת") }
    }

    // ================================================================== 2. adventure

    @Test
    fun adventureFemale() {
        flow = "gender_adventure"
        seedFemale()
        show { AdventureScreen() }
        settle(1500)
        shot("01_adventure")
        assertCleanFemale("adventure")
    }

    // ================================================================== 3. badges

    @Test
    fun badgesFemale() {
        flow = "gender_badges"
        seedFemale()
        runBlocking { AppGraph.game.unlockBadge("pr_king") }
        show { BadgesScreen() }
        settle(2000)
        shot("01_badges")
        assertCleanFemale("badges: grid top")
        // the grid is lazy: scroll to the unlocked badge, then open it
        tap("badges: open 'מלכת השיאים'", hasText("מלכת השיאים", substring = true) and hasClickAction(), settleMs = 800)
        shot("02_pr_king")
        assertCleanFemale("badges: pr_king")
        assertTrue("[$flow] step 'badges': female title missing", anyTextContains("מלכת השיאים"))
        assertTrue("[$flow] step 'badges': male badge title shown", !anyTextContains("מלך השיאים"))
    }

    // ================================================================== 4. weight

    @Test
    fun weightFemale() {
        flow = "gender_weight"
        seedFemale()
        show { WeightScreen() }
        settle(1500)
        shot("01_weight")
        assertCleanFemale("weight: open")
        typeInto("weight: type 71.4", hasText("משקל היום") and hasSetTextAction(), "71.4")
        tap("weight: 'רישום'", clickable("רישום"), settleMs = 800)
        waitFor("weight: 71.4 stored today") {
            runBlocking { AppGraph.db.weights().allOnce() }.any { it.dateEpochDay == Days.today() && abs(it.kg - 71.4f) < 0.05f }
        }
        settle(800)
        shot("02_logged")
        assertCleanFemale("weight: after weigh-in")
    }

    // ================================================================== 5. settings

    @Test
    fun settingsFemale() {
        flow = "gender_settings"
        seedFemale()
        runBlocking { AppGraph.prefs.update { it.copy(aiMorningDay = Days.today(), aiMorningLine = "שורה ישנה.") } }
        show { SettingsScreen() }
        settle(1500)
        shot("01_settings")
        // the hero picker itself offers "שמביולוק" as a choice: that one label is allowed
        assertCleanFemale("settings", allowExact = setOf("שמביולוק"))
        assertTrue("[$flow] step 'settings': male watch label shown", !anyTextContains("חבר את השעון"))
        assertTrue("[$flow] step 'settings': male install label shown", !anyTextContains("התקן את הלת'"))

        tap("settings: pick שמביולוק", clickable("שמביולוק"))
        waitFor("settings: characterVariant M stored") { runBlocking { AppGraph.prefs.current() }.characterVariant == "M" }
        assertEquals("[$flow] step 'settings: hero': cached AI line cleared", "", runBlocking { AppGraph.prefs.current() }.aiMorningLine)
        assertTrue("[$flow] step 'settings: hero': player address stays female", Addr.isFemale())
        shot("02_male_hero")
        tap("settings: back to שמביולוקה", clickable("שמביולוקה"))
        waitFor("settings: characterVariant F stored") { runBlocking { AppGraph.prefs.current() }.characterVariant == "F" }
        assertTrue("[$flow] step 'settings: hero F': CharacterNames female", CharacterNames.isFemaleNow())
    }

    // ================================================================== 6. history hub

    @Test
    fun historyHubFemale() {
        flow = "gender_history"
        seedFemale()
        show { HistoryHubScreen() }
        settle(1500)
        shot("01_hub")
        assertCleanFemale("history hub")
        node("history: runs card 'פתחי'", hasText("פתחי", substring = true))
        assertTrue("[$flow] step 'history': male 'פתח' label shown", count(hasText("פתח")) == 0)
        shot("02_runs_card")
    }

    // ================================================================== 7. weekly summary

    @Test
    fun weeklyFemale() {
        flow = "gender_weekly"
        seedFemale()
        show { WeeklySummaryScreen() }
        settle(2000)
        shot("01_weekly")
        assertCleanFemale("weekly")
        assertTrue("[$flow] step 'weekly': male box label shown", !anyTextContains("פתח את הקופסה"))
    }

    // ================================================================== 8. art + catalog texts

    @Test
    fun femaleArtResolves() {
        flow = "gender_art"
        seedFemale()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (i in 0 until BodyStage.ANCHORS) {
            val f = CharacterArt.anchorResId(context, CharacterNames.FEMALE, i)
            val m = CharacterArt.anchorResId(context, CharacterNames.MALE, i)
            val expected = context.resources.getIdentifier("shf_anchor_${i + 1}", "drawable", context.packageName)
            assertTrue("[$flow] stage ${i + 1}: shf_anchor_${i + 1} missing", expected != 0)
            assertEquals("[$flow] stage ${i + 1}: female variant must draw shf_anchor_${i + 1}", expected, f)
            assertNotEquals("[$flow] stage ${i + 1}: female and male images are the same", m, f)
        }
        for (def in BadgeCatalog.all) {
            val name = CharacterNames.g(def.nameHe)
            val hint = CharacterNames.g(def.hintHe)
            val vague = CharacterNames.g(BadgeCatalog.vagueHintHe(def.id))
            for (s in listOf(name, hint, vague)) {
                assertTrue("[$flow] badge ${def.id}: raw token in '$s'", !s.contains("{u:"))
                assertTrue("[$flow] badge ${def.id}: male hero name in '$s'", !maleName.containsMatchIn(s))
            }
        }
        assertEquals("[$flow] badge prodigal_son in female grammar", "חזרת הבת האובדת", CharacterNames.g(BadgeCatalog.byId("prodigal_son")!!.nameHe))
    }
}
