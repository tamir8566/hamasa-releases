package com.tamir.hamasa.flows

/*
 * Heart rate zones of walks / runs (HrZones + WatchStore.zonesMin + the zone rows in SessionBurnCard).
 *
 * Flows:
 *  1. zonesCompute          pure: a synthetic sample list with a known number of minutes in every zone, HRmax 200
 *                           (age 20): zone 1 = 3 min, 2 = 12, 3 = 20, 4 = 6, 5 = 0, plus 4 minutes of rest that must
 *                           not appear. Also the gap cap (a 10-minute hole counts as one minute), the summary line and
 *                           the fallback HRmax.
 *  2. zonesStoreRoundTrip   WatchStore.putSession with zones -> the state carries them; the cloud JSON (putCloud /
 *                           restoreFromCloud) keeps the old "watchSessions" format and the zones ride in "watchZones".
 *  3. zonesSessionDetail    a DONE RUN session + watch data with zones -> SessionDetailScreen shows the "מהשעון" line
 *                           and the five "אזור N · M דק'" labels; screenshot.
 *
 * Every failure message names the flow and the step in plain English.
 */

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.health.SessionWatch
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.HrPoint
import com.tamir.hamasa.domain.energy.HrZones
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.ui.history.SessionDetailScreen
import com.tamir.hamasa.ui.theme.HamasaTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ZonesFlowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var flow: String = "flow"

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

    private fun seedBase() {
        runBlocking {
            val today = Days.today()
            AppGraph.workouts.seedIfNeeded()
            AppGraph.prefs.update { com.tamir.hamasa.data.prefs.Profile() }
            AppGraph.workouts.logWeight(82f, today - 7)
            AppGraph.prefs.update { p ->
                p.copy(
                    onboarded = true, heightCm = 180, startWeightKg = 85f, goalWeightKg = 78f, focus = Focus.BUILD_MUSCLE,
                    lastRollDay = today, rollModeId = "silence", rollGoal = "לעמוד בכל זמני המנוחה", rollBonus = "none",
                    age = 21, sex = "M", userGender = "M", characterVariant = "M", lifetimeKg = 0.0, kcalGoal = 2400
                )
            }
        }
        Addr.set("M")
        CharacterNames.set("M")
    }

    /** A finished 41-minute run of today, written straight to the db (no rewards needed here). */
    private fun seedDoneRun(): Long = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 60L * 60_000L
        val end = start + 41L * 60_000L
        AppGraph.db.sessions().insert(
            SessionEntity(
                dateEpochDay = Days.today(), type = SessionType.RUN, status = SessionStatus.DONE, modeId = "mini",
                startedAtMillis = start, endedAtMillis = end, durationSec = 41 * 60, xpEarned = 40, goldEarned = 12, rating = 4
            )
        )
    }

    // ================================================================== helpers

    private var flowStartedAt: Long = 0L

    private fun checkDeadline() {
        if (flowStartedAt == 0L) flowStartedAt = System.currentTimeMillis()
        if (System.currentTimeMillis() - flowStartedAt > 240_000L) {
            fail("flow timed out after 4 minutes (screen never settled)")
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
                val seen = runCatching {
                    composeRule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .take(60).joinToString(" | ") { n -> (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null })?.joinToString("/") ?: "" }
                }.getOrDefault("?")
                println("[flow-screen] $step :: $seen")
                fail("[$flow] step '$step': not reached within ${timeoutMs} ms. Screen: ${seen.take(1200)}")
            }
            Thread.sleep(50)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeBy(50)
            checkDeadline()
        }
    }

    private fun count(matcher: SemanticsMatcher): Int =
        try { composeRule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size } catch (t: Throwable) { 0 }

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

    /** [minutes] samples, one every 10 seconds, all at [bpm], starting at [from]. Returns the next free time. */
    private fun addSamples(out: MutableList<HrPoint>, from: Long, minutes: Int, bpm: Int): Long {
        var t = from
        val stepMs = 10_000L
        repeat(minutes * 6) {
            out.add(HrPoint(t, bpm))
            t += stepMs
        }
        return t
    }

    // ================================================================== 1. pure computation

    @Test
    fun zonesCompute() {
        flow = "zones_compute"
        // HRmax 200 (age 20): zone 1 = 100..119, 2 = 120..139, 3 = 140..159, 4 = 160..179, 5 = 180+, rest < 100.
        assertEquals("[$flow] step 'hrmax': 220 - age", 200, HrZones.hrMax(20))
        assertEquals("[$flow] step 'hrmax fallback': unknown age -> 190", 190, HrZones.hrMax(0))
        assertEquals("[$flow] step 'zone of 99': rest", 0, HrZones.zoneOf(99, 200))
        assertEquals("[$flow] step 'zone of 100': zone 1", 1, HrZones.zoneOf(100, 200))
        assertEquals("[$flow] step 'zone of 139': zone 2", 2, HrZones.zoneOf(139, 200))
        assertEquals("[$flow] step 'zone of 140': zone 3", 3, HrZones.zoneOf(140, 200))
        assertEquals("[$flow] step 'zone of 179': zone 4", 4, HrZones.zoneOf(179, 200))
        assertEquals("[$flow] step 'zone of 195': zone 5", 5, HrZones.zoneOf(195, 200))

        val samples = ArrayList<HrPoint>()
        var t = 1_700_000_000_000L
        t = addSamples(samples, t, 4, 90)    // rest, must not be counted
        t = addSamples(samples, t, 3, 110)   // zone 1
        t = addSamples(samples, t, 12, 130)  // zone 2
        t = addSamples(samples, t, 20, 150)  // zone 3
        t = addSamples(samples, t, 6, 170)   // zone 4
        // unsorted input must still work
        val shuffled = samples.shuffled(java.util.Random(7))
        val res = HrZones.compute(shuffled, 200)
        assertEquals("[$flow] step 'minutes per zone': 3/12/20/6/0", listOf(3, 12, 20, 6, 0), res.minutes)
        assertEquals("[$flow] step 'total': 41 minutes in the zones", 41, res.totalMinutes)
        assertEquals("[$flow] step 'rest': 4 minutes of rest, kept apart", 4, res.restMinutes)
        assertFalse("[$flow] step 'not empty'", res.isEmpty)

        // the same through the age helper
        assertEquals("[$flow] step 'computeForAge': same result", res.minutes, HrZones.computeForAge(shuffled, 20).minutes)

        // a hole in the recording: two samples 10 minutes apart count as ONE minute (gap cap), not ten
        val hole = listOf(HrPoint(0L, 150), HrPoint(10L * 60_000L, 150), HrPoint(10L * 60_000L + 10_000L, 150))
        val holeRes = HrZones.compute(hole, 200)
        // gaps: 60 s (capped), 10 s, last = median(60 s, 10 s) = 35 s -> 105 s -> 2 minutes
        assertEquals("[$flow] step 'gap cap': a 10-minute hole is one minute", listOf(0, 0, 2, 0, 0), holeRes.minutes)

        // empty / junk input
        assertTrue("[$flow] step 'empty': no samples -> empty", HrZones.compute(emptyList(), 200).isEmpty)
        assertTrue("[$flow] step 'junk': impossible bpm ignored", HrZones.compute(listOf(HrPoint(0L, 300)), 200).isEmpty)

        // Hebrew helpers
        assertEquals("[$flow] step 'label'", "אזור 3 · 20 דק'", HrZones.labelHe(3, 20))
        assertEquals("[$flow] step 'summary'", "אזורים: 1·3 2·12 3·20 4·6 5·0", HrZones.summaryHe(res.minutes))
        assertTrue("[$flow] step 'has'", HrZones.has(res.minutes))
        assertFalse("[$flow] step 'has: empty list'", HrZones.has(emptyList()))
        assertFalse("[$flow] step 'has: all zero'", HrZones.has(listOf(0, 0, 0, 0, 0)))
    }

    // ================================================================== 2. store round trip

    @Test
    fun zonesStoreRoundTrip() {
        flow = "zones_store"
        seedBase()
        val idA = 900_001L
        val idB = 900_002L
        WatchStore.putSession(idA, SessionWatch(avgHr = 150, maxHr = 176, kcal = 410, distanceM = 6200, zonesMin = listOf(3, 12, 20, 6, 0)))
        WatchStore.putSession(idB, SessionWatch(avgHr = 120, maxHr = 140, kcal = 200, distanceM = 0))
        val a = WatchStore.current().sessions[idA]
        val b = WatchStore.current().sessions[idB]
        assertTrue("[$flow] step 'put': session A has zones", a != null && a.hasZones && a.zonesMin == listOf(3, 12, 20, 6, 0))
        assertTrue("[$flow] step 'put': session B has no zones", b != null && !b.hasZones && b.zonesMin.isEmpty())

        // cloud profile JSON: the old key keeps the old 5-field format, zones ride in their own key
        val o = JSONObject()
        WatchStore.putCloud(o)
        val sessionsCsv = o.optString("watchSessions", "")
        val zonesCsv = o.optString("watchZones", "")
        assertTrue("[$flow] step 'cloud': sessions line has A in the old format", sessionsCsv.contains("$idA:150:176:410:6200"))
        assertTrue("[$flow] step 'cloud': zones line has A", zonesCsv.contains("$idA:3,12,20,6,0"))
        assertFalse("[$flow] step 'cloud': zones line has no B", zonesCsv.contains("$idB:"))

        // restore: a session that is missing locally comes back with its zones
        WatchStore.removeSession(idA)
        assertTrue("[$flow] step 'remove': A gone", WatchStore.current().sessions[idA] == null)
        WatchStore.restoreFromCloud(o)
        val back = WatchStore.current().sessions[idA]
        assertTrue("[$flow] step 'restore': A is back with its zones", back != null && back.avgHr == 150 && back.zonesMin == listOf(3, 12, 20, 6, 0))

        WatchStore.removeSession(idA)
        WatchStore.removeSession(idB)
    }

    // ================================================================== 3. session detail screen

    @Test
    fun zonesSessionDetail() {
        flow = "zones_session_detail"
        seedBase()
        val sessionId = seedDoneRun()
        WatchStore.putSession(sessionId, SessionWatch(avgHr = 152, maxHr = 178, kcal = 420, distanceM = 6400, zonesMin = listOf(3, 12, 20, 6, 0)))
        assertTrue("[$flow] step 'seed': session DONE", runBlocking { AppGraph.db.sessions().byId(sessionId) }?.status == SessionStatus.DONE)

        com.tamir.hamasa.data.health.WatchSync.stepsOverride = { id -> if (id == sessionId) 6686 else null }
        try {
        show { SessionDetailScreen(sessionId = sessionId, onStartWorkout = {}) }
        settle(800)

        waitFor("cardio card steps") { exists(hasText("6,686")) }
        waitFor("cardio card headers") { exists(hasText("צעדים")) && exists(hasText("קלוריות")) }
        waitFor("watch line on screen") { exists(hasText("מהשעון: ", substring = true)) }
        waitFor("zones header") { exists(hasText("אזורי דופק", substring = true)) }
        waitFor("zone 1 label") { exists(hasText("אזור 1 · 3 דק'")) }
        waitFor("zone 2 label") { exists(hasText("אזור 2 · 12 דק'")) }
        waitFor("zone 3 label") { exists(hasText("אזור 3 · 20 דק'")) }
        waitFor("zone 4 label") { exists(hasText("אזור 4 · 6 דק'")) }
        waitFor("zone 5 label") { exists(hasText("אזור 5 · 0 דק'")) }
        assertTrue("[$flow] step 'total': 41 measured minutes in the header", exists(hasText("41 דק' נמדדו", substring = true)))
        shot("01_zones")
        } finally {
            com.tamir.hamasa.data.health.WatchSync.stepsOverride = null
        }

        WatchStore.removeSession(sessionId)
    }
}
