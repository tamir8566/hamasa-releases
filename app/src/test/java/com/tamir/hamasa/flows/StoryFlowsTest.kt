package com.tamir.hamasa.flows

/*
 * Story flows ("אימון מסופר"): the offline chapters are a clear, continuous adventure, in both hero grammars,
 * and the chapter cards show them. Images: build/screenshots/flows/story_<step>.png (record mode only).
 *
 * Flows:
 *  1. offlineChaptersClean     every region x every chapter of its cycle x (no boss / boss / returning boss) x
 *                              (שמביולוק / שמביולוקה): 6 non-blank lines, no "%s", no raw "{u:" / "{loc}", the boss name
 *                              and quote inside the chapter, female chapters never use the male name, male never the female.
 *  2. chaptersFollowEachOther  consecutive chapters of a region differ (opening line too); the first village chapter is
 *                              the departure; StoryLog.countInRegion / previousOf read the log in order.
 *  3. historyCardMale          StoryChapterCard ("ספר הקרבות") shows the stored male chapter: title + all 6 lines.
 *  4. historyCardFemale        same for שמביולוקה: female lines on screen, no male name anywhere.
 *  5. liveCardShowsContext     the live StoryCard shows every revealed line (not only the last one) and the hint.
 */

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.StoryChapter
import com.tamir.hamasa.data.repo.StoryLog
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.domain.game.StoryChapters
import com.tamir.hamasa.ui.history.StoryChapterCard
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.workout.StoryCard
import com.tamir.hamasa.ui.workout.StoryLive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class StoryFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var flow: String = "story"

    private val maleName = Regex("שמביולוק(?!ה)")

    private val bossName = "מכשפת הסוכר"
    private val bossQuote = "עוגייה אחת. מה כבר יקרה."
    private val comeback = "מתכון חדש. בלי סוכר. משקרת, כמובן."

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
        runCatching { runBlocking { AppGraph.prefs.update { it.copy(userGender = "M", characterVariant = "M", storyLog = "") } } }
        Addr.set("M")
        CharacterNames.set("M")
    }

    /** The hero variant in the profile and in memory (the AppGraph collector follows the profile). */
    private fun hero(variant: String) {
        runBlocking { AppGraph.prefs.update { it.copy(characterVariant = variant) } }
        settleLooper()
        CharacterNames.set(variant)
    }

    private fun settleLooper() {
        repeat(5) {
            Thread.sleep(20)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
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

    /** Every text on screen (unmerged tree, all roots). */
    private fun allTexts(): List<String> {
        val out = ArrayList<String>()
        val nodes = try {
            composeRule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
        } catch (t: Throwable) { emptyList() }
        for (n in nodes) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out.addAll(it) }
        }
        return out
    }

    // ================================================================== chapter checks

    private fun checkChapter(where: String, lines: List<String>, female: Boolean) {
        assertEquals("[$flow] $where: exactly ${StoryChapters.SENTENCES} lines", StoryChapters.SENTENCES, lines.size)
        for ((i, line) in lines.withIndex()) {
            assertTrue("[$flow] $where line $i: not blank", line.isNotBlank())
            assertFalse("[$flow] $where line $i: no %s left: $line", line.contains("%s") || line.contains("%"))
            assertFalse("[$flow] $where line $i: no raw {u: token: $line", line.contains("{u:"))
            assertFalse("[$flow] $where line $i: no raw {loc}: $line", line.contains("{loc}") || line.contains("{"))
            assertTrue("[$flow] $where line $i: short and readable (${line.length})", line.length <= 130)
            if (female) {
                assertFalse("[$flow] $where line $i: female chapter uses the male name: $line", maleName.containsMatchIn(line))
                assertFalse("[$flow] $where line $i: female chapter has \"שמביולוק \": $line", line.contains("שמביולוק "))
            } else {
                assertFalse("[$flow] $where line $i: male chapter uses the female name: $line", line.contains("שמביולוקה"))
            }
        }
        val all = lines.joinToString(" ")
        assertTrue("[$flow] $where: the hero is named in the chapter", all.contains(if (female) "שמביולוקה" else "שמביולוק"))
    }

    // ================================================================== 1. offlineChaptersClean

    @Test
    fun offlineChaptersClean() {
        flow = "story_clean"
        for (variant in listOf("M", "F")) {
            hero(variant)
            val female = variant == "F"
            for (region in 0..10) {
                val n = StoryChapters.chaptersPerRegion(region)
                assertTrue("[$flow] region $region: at least 3 chapters", n >= 3)
                for (step in 0 until n) {
                    val where = "variant $variant region $region step $step"
                    val calm = StoryChapters.fallback(1000L + step, region, "", "", null, step)
                    checkChapter("$where no boss", calm, female)

                    val boss = StoryChapters.fallback(1000L + step, region, bossName, bossQuote, null, step)
                    checkChapter("$where boss", boss, female)
                    val bossText = boss.joinToString(" ")
                    assertTrue("[$flow] $where boss: the boss name is in the chapter", bossText.contains(bossName))
                    // the chapter drops the quote's closing punctuation so it sits inside the sentence
                    assertTrue("[$flow] $where boss: the boss quote is in the chapter", bossText.contains(bossQuote.trim().trimEnd('.', '!', '?', ',').trim()))
                    assertEquals("[$flow] $where: same opening with or without boss", calm[0], boss[0])
                    assertEquals("[$flow] $where: same ending with or without boss", calm[5], boss[5])

                    val back = StoryChapters.fallback(1000L + step, region, bossName, bossQuote, comeback, step)
                    checkChapter("$where returning boss", back, female)
                    assertTrue("[$flow] $where returning: the comeback line is in the chapter", back.joinToString(" ").contains(comeback.trim().trimEnd('.', '!', '?', ',').trim()))
                }
            }
            // the seed path (no step) is deterministic and clean too
            val a = StoryChapters.fallback(777L, 4, bossName, bossQuote)
            assertEquals("[$flow] variant $variant: same seed, same chapter", a, StoryChapters.fallback(777L, 4, bossName, bossQuote))
            checkChapter("variant $variant seed path", a, female)
            checkChapter("variant $variant negative seed", StoryChapters.fallback(-5L, 2, "", ""), female)
        }
    }

    // ================================================================== 2. chaptersFollowEachOther

    @Test
    fun chaptersFollowEachOther() {
        flow = "story_order"
        for (variant in listOf("M", "F")) {
            hero(variant)
            for (region in 0..10) {
                val n = StoryChapters.chaptersPerRegion(region)
                val chapters = (0 until n).map { StoryChapters.fallback(5L, region, "", "", null, it) }
                for (i in 1 until n) {
                    assertNotEquals("[$flow] variant $variant region $region: chapter $i differs from ${i - 1}", chapters[i - 1], chapters[i])
                    assertNotEquals("[$flow] variant $variant region $region: opening $i differs from ${i - 1}", chapters[i - 1][0], chapters[i][0])
                }
                // all chapters of the cycle are different events
                assertEquals("[$flow] variant $variant region $region: $n different openings", n, chapters.map { it[0] }.distinct().size)
            }
        }
        hero("M")
        val first = StoryChapters.fallback(1L, 0, "", "", null, 0)
        assertTrue("[$flow] the first village chapter is the departure", first[0].contains("לצאת למסע"))
        val second = StoryChapters.fallback(1L, 0, "", "", null, 1)
        assertTrue("[$flow] the second village chapter refers to the first (the gate)", second[0].contains("שער"))

        // the log is read in order: count per region and the previous chapter
        val map = LinkedHashMap<Long, StoryChapter>()
        map[1L] = StoryChapter(1L, StoryChapters.title(1, "הכפר"), first)
        map[2L] = StoryChapter(2L, StoryChapters.title(2, "הכפר"), second)
        map[3L] = StoryChapter(3L, StoryChapters.title(3, "היער הקפוא"), first)
        assertEquals("[$flow] two village chapters told", 2, StoryLog.countInRegion(map, "הכפר", 99L))
        assertEquals("[$flow] the session itself is not counted", 1, StoryLog.countInRegion(map, "הכפר", 2L))
        assertEquals("[$flow] \"היער\" does not count \"היער הקפוא\"", 0, StoryLog.countInRegion(map, "היער", 99L))
        assertEquals("[$flow] previous chapter = the latest other one", 3L, StoryLog.previousOf(map, 99L)?.sessionId)
        assertEquals("[$flow] previous skips the session itself", 2L, StoryLog.previousOf(map, 3L)?.sessionId)
    }

    // ================================================================== 3-4. history card

    private fun historyCard(variant: String, sessionId: Long) {
        hero(variant)
        val female = variant == "F"
        val lines = StoryChapters.fallback(sessionId, 3, bossName, bossQuote, null, 0)
        checkChapter("history card $variant", lines, female)
        val title = StoryChapters.title(7, "הנהר")
        runBlocking { StoryLog.save(StoryChapter(sessionId, title, lines), replace = true) }
        show { StoryChapterCard(sessionId) }
        waitFor("chapter title on screen") { exists(hasText(title)) }
        for ((i, line) in lines.withIndex()) {
            waitFor("line $i on screen") { exists(hasText(line)) }
        }
        val texts = allTexts()
        assertTrue("[$flow] no raw tokens on screen", texts.none { it.contains("{u:") || it.contains("%s") })
        if (female) assertTrue("[$flow] no male hero name on screen", texts.none { maleName.containsMatchIn(it) })
        else assertTrue("[$flow] no female hero name on screen", texts.none { it.contains("שמביולוקה") })
        shot(if (female) "history_female" else "history_male")
    }

    @Test
    fun historyCardMale() {
        flow = "story"
        historyCard("M", 9_001L)
    }

    @Test
    fun historyCardFemale() {
        flow = "story"
        historyCard("F", 9_002L)
    }

    // ================================================================== 5. live card

    @Test
    fun liveCardShowsContext() {
        flow = "story"
        hero("M")
        val lines = StoryChapters.fallback(42L, 2, "", "", null, 0)
        val live = StoryLive(StoryChapters.title(3, "היער"), lines, 3)
        show { StoryCard(live) }
        waitFor("title") { exists(hasText(StoryChapters.title(3, "היער"))) }
        for (i in 0 until 3) waitFor("revealed line $i shown") { exists(hasText(lines[i])) }
        assertFalse("[$flow] hidden line 4 not shown", exists(hasText(lines[3])))
        waitFor("hint for the hidden lines") { exists(hasText("עוד 3 שורות. כל סט מקדם את הסיפור.")) }
        shot("live_male")
    }
}
