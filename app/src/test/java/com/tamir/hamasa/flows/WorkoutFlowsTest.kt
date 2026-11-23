package com.tamir.hamasa.flows

/*
 * Workout group flows (the "clicking agent" for the workout / coach / templates requests of 2026-11-01).
 * Same runner and helpers as UserFlowsTest. Images go to build/screenshots/flows/<flow>_<step>.png (record mode).
 *
 * Flows:
 *  1. howToCatalogAndLive     every catalog exercise without ex_<id>_a/_b frames has a how-to text (male and female);
 *                             WorkoutScreen on a "band_row" session shows "איך עושים" with the female text.
 *  2. howToInCoachPlan        a note "היום רק גומייה ומשקל גוף" -> the coach plan card of an exercise with no frames
 *                             shows its how-to text on CoachScreen.
 *  3. coachMinutesChangePlan  CoachScreen: "יש לי 50 דקות" -> "שלח לג'ים ברו" -> the plan header changes to the new plan
 *                             (at most 50 minutes; more working sets when the plan was well short); then 90 minutes
 *                             -> longer plan (more sets and / or the walk finisher), at most 90 minutes.
 *  4. editSetRecord           a done set is edited through the dialog: 40 -> 50 kg becomes a record, lifetime kg moves
 *                             by the difference; back to 40 kg -> no record.
 *  5. templateSwapUpdate      a template workout with a swap after one done set -> the done screen card asks
 *                             "לעדכן את ...?" with "X במקום Y"; "עדכון האימון" (double tap) -> the same template, the
 *                             swapped exercise in place of the old one, no new template.
 *  6. templateNoChangeNoCard  a template workout without changes -> no update card and no "save" card.
 *  7. staleActiveClosed       an ACTIVE session from yesterday (started a day ago) is closed when a new one starts;
 *                             one started an hour ago (past midnight) is still the running workout.
 */

import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeUp
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.CoachNotes
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.ExerciseHowTo
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.WorkoutPlan
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.ui.coach.CoachScreen
import com.tamir.hamasa.ui.common.drawableId
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.workout.SaveWorkoutCard
import com.tamir.hamasa.ui.workout.WorkoutScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class WorkoutFlowsTest {

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

    private fun planned(id: String, sets: Int, reps: Int, kg: Float, rest: Int = 90): PlannedExercise {
        val e = ExerciseCatalog.byId(id) ?: error("no catalog exercise $id")
        return PlannedExercise(exerciseId = e.id, nameHe = e.nameHe, branch = e.branch, sets = sets, reps = reps, weightKg = kg, restSec = rest)
    }

    private fun customPlan(vararg ex: PlannedExercise): WorkoutPlan =
        WorkoutPlan(SessionType.GYM, "silence", "אימון בדיקה", ex.toList(), 30, "")

    private fun sets(sessionId: Long): List<SetLogEntity> = runBlocking { AppGraph.db.sessions().setsForOnce(sessionId) }
    private fun lifetimeKg(): Double = runBlocking { AppGraph.prefs.current().lifetimeKg }

    /** Working sets of a plan: no mobility holds, no cardio finisher. */
    private fun workingSets(p: WorkoutPlan): Int =
        p.exercises.filter { !ExerciseCatalog.isMobility(it.exerciseId) && it.exerciseId != CoachEngine.FINISHER_ID }.sumOf { it.sets }

    private fun hasAnim(id: String): Boolean {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ctx.drawableId("ex_${id}_a") != 0 && ctx.drawableId("ex_${id}_b") != 0
    }

    // ================================================================== helpers (copied from UserFlowsTest)

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
    private fun inDialog(m: SemanticsMatcher): SemanticsMatcher = m and hasAnyAncestor(isDialog())

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

    private fun declineEventIfOffered() {
        val m = inDialog(clickable("לא היום"))
        if (exists(m)) runCatching { composeRule.onAllNodes(m)[0].performClick() }
        settle(200)
    }

    // ================================================================== 1. how-to: catalog + live workout

    @Test
    fun howToCatalogAndLive() {
        flow = "howto_live"
        seedBase()
        // every catalog exercise without both demo frames has a text, in both grammars
        val missing = ArrayList<String>()
        for (e in ExerciseCatalog.all) {
            if (hasAnim(e.id)) continue
            Addr.set("M")
            val m = ExerciseHowTo.textFor(e.id)
            Addr.set("F")
            val f = ExerciseHowTo.textFor(e.id)
            if (m.isNullOrBlank() || f.isNullOrBlank()) missing.add(e.id)
        }
        Addr.set("M")
        assertTrue("[$flow] step 'catalog': exercises without frames and without a how-to: $missing", missing.isEmpty())
        assertTrue("[$flow] step 'catalog': band_row must have no frames for this flow", !hasAnim("band_row"))

        // female player: the live workout shows the female text under the current exercise
        runBlocking { AppGraph.prefs.update { it.copy(userGender = "F", characterVariant = "F") } }
        Addr.set("F")
        CharacterNames.set("F")
        val female = ExerciseHowTo.textFor("band_row") ?: ""
        assertTrue("[$flow] step 'female text': band_row female text is addressed to a woman", female.contains("קבעי"))
        val sessionId = runBlocking { AppGraph.workouts.startSession(customPlan(planned("band_row", 2, 12, 0f, 60)), 7f, null) }
        show { WorkoutScreen(sessionId = sessionId, onFinished = {}, onAbandon = {}) }
        settle(1500)
        declineEventIfOffered()
        node("live: how-to title", hasText("איך עושים"))
        node("live: female how-to text", hasText(female))
        shot("01_live_howto_female")
    }

    // ================================================================== 2. how-to in the coach plan card

    @Test
    fun howToInCoachPlan() {
        flow = "howto_coach"
        seedBase()
        val today = Days.today()
        val c = CoachNotes.offline("היום רק גומייה ומשקל גוף", today).constraints
        if (c == null) fail("[$flow] step 'note': the offline rules did not understand the band note")
        else runBlocking { CoachNotes.save(c) }
        val plan = runBlocking { AppGraph.workouts.planFor(SessionType.GYM, 7f) }
        val target = plan.exercises.firstOrNull { !hasAnim(it.exerciseId) && ExerciseHowTo.textFor(it.exerciseId) != null }
        if (target == null) fail("[$flow] step 'plan': the band plan has no exercise without frames: ${plan.exercises.map { it.exerciseId }}")
        else {
            show { CoachScreen(onStartWorkout = {}) }
            settle(1500)
            node("coach: how-to of ${target.exerciseId}", hasText(ExerciseHowTo.textFor(target.exerciseId) ?: ""), timeoutMs = 15_000L)
            shot("01_coach_howto")
        }
    }

    // ================================================================== 3. "יש לי 50 דקות" changes the plan

    @Test
    fun coachMinutesChangePlan() {
        flow = "coach_minutes"
        seedBase()
        val before = runBlocking { AppGraph.workouts.planFor(SessionType.GYM, 7f) }
        show { CoachScreen(onStartWorkout = {}) }
        settle(1500)
        node("plan before: header", hasText("${before.exercises.size} תרגילים, בערך ${before.estimatedMinutes} דקות"), timeoutMs = 15_000L)
        shot("01_before")

        bringIntoView(hasText("הודעה לג'ים ברו"))
        typeInto("note: type", hasSetTextAction() and androidx.compose.ui.test.hasTestTag("coach_note_field"), "יש לי 50 דקות")
        tap("note: send", clickable(Addr.t("שלח לג'ים ברו", "שלחי לג'ים ברו")) and isEnabled(), settleMs = 600L)
        waitFor("note: constraints stored with max_minutes 50", 50_000L) {
            val json = runBlocking { AppGraph.prefs.current() }.coachConstraintsJson
            json.isNotBlank() && JSONObject(json).optInt("max_minutes", 0) == 50
        }
        val after50 = runBlocking { AppGraph.workouts.planFor(SessionType.GYM, 7f) }
        assertTrue("[$flow] step '50 min': plan of ${after50.estimatedMinutes} min must fit 50", after50.estimatedMinutes <= 50)
        if (before.estimatedMinutes < 46) {
            assertTrue(
                "[$flow] step '50 min': a ${before.estimatedMinutes}-minute plan must grow (sets ${workingSets(before)} -> ${workingSets(after50)})",
                workingSets(after50) > workingSets(before) || after50.exercises.any { it.exerciseId == CoachEngine.FINISHER_ID }
            )
        }
        node("50 min: new header on screen", hasText("${after50.exercises.size} תרגילים, בערך ${after50.estimatedMinutes} דקות"), timeoutMs = 15_000L)
        shot("02_after_50")

        // much more time: more sets and / or the walk finisher, never past the time
        val c90 = CoachNotes.offline("יש לי 90 דקות", Days.today()).constraints
        if (c90 == null) fail("[$flow] step '90 min': offline rules did not read 90 minutes")
        else {
            assertEquals("[$flow] step '90 min': parsed minutes", 90, c90.maxMinutes)
            runBlocking { CoachNotes.save(c90) }
        }
        val after90 = runBlocking { AppGraph.workouts.planFor(SessionType.GYM, 7f) }
        assertTrue("[$flow] step '90 min': ${after90.estimatedMinutes} min must fit 90", after90.estimatedMinutes <= 90)
        assertTrue(
            "[$flow] step '90 min': the plan must be longer than the plain one (${before.estimatedMinutes} -> ${after90.estimatedMinutes})",
            after90.estimatedMinutes > before.estimatedMinutes
        )
        assertTrue(
            "[$flow] step '90 min': more sets or the finisher walk",
            workingSets(after90) > workingSets(before) || after90.exercises.any { it.exerciseId == CoachEngine.FINISHER_ID }
        )
        node("90 min: new header on screen", hasText("${after90.exercises.size} תרגילים, בערך ${after90.estimatedMinutes} דקות"), timeoutMs = 15_000L)
        shot("03_after_90")
    }

    // ================================================================== 4. edit a done set: record recomputed

    @Test
    fun editSetRecord() {
        flow = "edit_set_record"
        seedBase()
        // an earlier finished session: bench 40 x 8 is the baseline
        runBlocking {
            val first = AppGraph.workouts.startSession(customPlan(planned("bench_press", 1, 8, 40f, 150)), 7f, null)
            for (s in AppGraph.db.sessions().setsForOnce(first)) AppGraph.workouts.completeSet(s, 8, 40f)
            AppGraph.workouts.finishSession(first, rating = 4, goalMet = true)
        }
        val sessionId = runBlocking { AppGraph.workouts.startSession(customPlan(planned("bench_press", 2, 8, 40f, 150)), 7f, null) }
        show { WorkoutScreen(sessionId = sessionId, onFinished = {}, onAbandon = {}) }
        settle(1500)
        declineEventIfOffered()

        tap("set 1: done", clickable("סיימתי סט") and isEnabled(), settleMs = 600L)
        waitFor("set 1: stored as completed") { sets(sessionId).count { it.completed } == 1 }
        declineEventIfOffered()
        val done = sets(sessionId).first { it.completed }
        assertTrue("[$flow] step 'set 1': 40 kg equal to the baseline is not a record", !done.isPr || done.weight > 40f)

        // 40 -> 50 kg: a record now, lifetime kg grows by the difference
        val kgBefore = lifetimeKg()
        tap("edit: open", clickable(Addr.t("ערוך", "ערכי")))
        waitFor("edit: dialog open") { exists(inDialog(hasSetTextAction())) }
        typeInto("edit: weight 50", inDialog(hasSetTextAction()), "50", index = 1)
        shot("01_edit_50")
        tap("edit: save", inDialog(clickable("שמירה")) and isEnabled(), settleMs = 600L)
        waitFor("edit: 50 kg stored") { sets(sessionId).any { it.id == done.id && abs(it.weight - 50f) < 0.01f } }
        waitFor("edit: the set is a record") { sets(sessionId).first { it.id == done.id }.isPr }
        val delta = 50.0 * done.reps - done.weight.toDouble() * done.reps
        waitFor("edit: lifetime kg moved by $delta") { abs(lifetimeKg() - (kgBefore + delta)) < 0.5 }
        shot("02_record")

        // back to 40 kg: no record, lifetime kg back
        declineEventIfOffered()
        tap("edit again: open", clickable(Addr.t("ערוך", "ערכי")))
        waitFor("edit again: dialog open") { exists(inDialog(hasSetTextAction())) }
        typeInto("edit again: weight 40", inDialog(hasSetTextAction()), "40", index = 1)
        tap("edit again: save", inDialog(clickable("שמירה")) and isEnabled(), settleMs = 600L)
        waitFor("edit again: no record") { sets(sessionId).first { it.id == done.id }.let { !it.isPr && abs(it.weight - 40f) < 0.01f } }
        waitFor("edit again: lifetime kg back") { abs(lifetimeKg() - kgBefore) < 0.5 }
        assertEquals("[$flow] step 'edit again': still one completed set", 1, sets(sessionId).count { it.completed })
    }

    // ================================================================== 5. template workout with a swap -> update

    @Test
    fun templateSwapUpdate() {
        flow = "template_swap_update"
        seedBase()
        val name = "תבנית החלפה"
        val templateId = runBlocking {
            AppGraph.workouts.saveTemplate(
                WorkoutTemplateEntity(nameHe = name, createdAt = System.currentTimeMillis()),
                listOf(
                    TemplateItemEntity(templateId = 0L, orderIndex = 0, exerciseId = "bench_press", sets = 2, reps = 8, weightKg = 40f, restSec = 150),
                    TemplateItemEntity(templateId = 0L, orderIndex = 1, exerciseId = "lateral_raise", sets = 3, reps = 12, weightKg = 8f, restSec = 75),
                    TemplateItemEntity(templateId = 0L, orderIndex = 2, exerciseId = "lat_pulldown", sets = 2, reps = 10, weightKg = 45f, restSec = 120)
                )
            )
        }
        val sessionId = runBlocking { AppGraph.workouts.startFromTemplate(templateId) }
        runBlocking {
            // one set of the lateral raise, then he swaps it for the cable version
            val firstRaise = AppGraph.db.sessions().setsForOnce(sessionId).filter { it.exerciseId == "lateral_raise" }.minByOrNull { it.setIndex }
            if (firstRaise != null) AppGraph.workouts.completeSet(firstRaise, 12, 8f)
            AppGraph.workouts.swapExercise(sessionId, "lateral_raise", "cable_lateral_raise")
            for (s in AppGraph.db.sessions().setsForOnce(sessionId).filter { !it.completed }) {
                AppGraph.workouts.completeSet(s, s.targetReps, if (s.targetWeight > 0f) s.targetWeight else 10f)
            }
            AppGraph.workouts.finishSession(sessionId, rating = 4, goalMet = true)
        }
        val cableSets = sets(sessionId).count { it.exerciseId == "cable_lateral_raise" }
        assertTrue("[$flow] step 'seed': the swap moved the remaining sets", cableSets >= 1)
        assertTrue("[$flow] step 'seed': the done raise set stays", sets(sessionId).any { it.exerciseId == "lateral_raise" && it.completed })
        val templatesBefore = runBlocking { AppGraph.db.templates().templatesOnce() }.size

        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { SaveWorkoutCard(sessionId = sessionId, popup = false) }
        }
        settle(800)
        waitFor("card: update question") { exists(hasText("לעדכן את \"$name\"?")) }
        val line = (ExerciseCatalog.byId("cable_lateral_raise")?.nameHe ?: "") + " במקום " + (ExerciseCatalog.byId("lateral_raise")?.nameHe ?: "")
        assertTrue("[$flow] step 'card': line '$line' shown", exists(hasText(line)))
        assertTrue("[$flow] step 'card': the swap is not shown as an extra exercise", !exists(hasText("נוסף: " + (ExerciseCatalog.byId("cable_lateral_raise")?.nameHe ?: ""))))
        assertTrue("[$flow] step 'card': no plain save card", !exists(hasText("שמירה באימונים שלי")))
        shot("01_question")

        val update = node("card: find update", clickable("עדכון האימון") and isEnabled())
        try { update.performTouchInput { doubleClick() } } catch (t: Throwable) { fail("[$flow] step 'card: double tap update' failed: ${t.message}") }
        waitFor("update: card shows 'saved'") { exists(hasText("נשמר")) }
        settle(800)
        assertEquals("[$flow] step 'update': no new template", templatesBefore, runBlocking { AppGraph.db.templates().templatesOnce() }.size)
        val items = runBlocking { AppGraph.db.templates().itemsOnce(templateId) }.sortedBy { it.orderIndex }
        assertEquals(
            "[$flow] step 'update': template order with the swap in place",
            listOf("bench_press", "cable_lateral_raise", "lat_pulldown"), items.map { it.exerciseId }
        )
        val cable = items[1]
        assertEquals("[$flow] step 'update': the swapped exercise keeps the old rest", 75, cable.restSec)
        assertEquals("[$flow] step 'update': sets as done today", cableSets, cable.sets)
        assertEquals("[$flow] step 'update': untouched bench keeps its weight", 40f, items[0].weightKg, 0.01f)
        assertTrue("[$flow] step 'update': session marked handled", AppGraph.workouts.isSaveHandled(sessionId))
        shot("02_updated")
    }

    // ================================================================== 6. template workout without changes

    @Test
    fun templateNoChangeNoCard() {
        flow = "template_no_change"
        seedBase()
        val templateId = runBlocking {
            AppGraph.workouts.saveTemplate(
                WorkoutTemplateEntity(nameHe = "תבנית קבועה", createdAt = System.currentTimeMillis()),
                listOf(TemplateItemEntity(templateId = 0L, orderIndex = 0, exerciseId = "bench_press", sets = 2, reps = 8, weightKg = 40f, restSec = 150))
            )
        }
        val sessionId = runBlocking { AppGraph.workouts.startFromTemplate(templateId) }
        runBlocking {
            for (s in AppGraph.db.sessions().setsForOnce(sessionId)) AppGraph.workouts.completeSet(s, s.targetReps, s.targetWeight)
            AppGraph.workouts.finishSession(sessionId, rating = 5, goalMet = true)
        }
        val templatesBefore = runBlocking { AppGraph.db.templates().templatesOnce() }.size
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { SaveWorkoutCard(sessionId = sessionId, popup = false) }
        }
        settle(1500)
        assertTrue("[$flow] step 'card': no update question", !exists(hasText("לעדכן את \"תבנית קבועה\"?")))
        assertTrue("[$flow] step 'card': no save card for his own template", !exists(hasText("שמירה באימונים שלי")))
        assertEquals("[$flow] step 'card': templates untouched", templatesBefore, runBlocking { AppGraph.db.templates().templatesOnce() }.size)
        shot("01_nothing")
    }

    // ================================================================== 6b. template values follow what he did

    @Test
    fun templateValuesUpdate() {
        flow = "template_values_update"
        seedBase()
        val name = "תבנית משקלים"
        val templateId = runBlocking {
            AppGraph.workouts.saveTemplate(
                WorkoutTemplateEntity(nameHe = name, createdAt = System.currentTimeMillis()),
                listOf(TemplateItemEntity(templateId = 0L, orderIndex = 0, exerciseId = "bench_press", sets = 2, reps = 8, weightKg = 40f, restSec = 150))
            )
        }
        val sessionId = runBlocking { AppGraph.workouts.startFromTemplate(templateId) }
        runBlocking {
            val rows = AppGraph.db.sessions().setsForOnce(sessionId).filter { it.exerciseId == "bench_press" }.sortedBy { it.setIndex }
            AppGraph.workouts.completeSet(rows[0], 8, 42.5f)
            AppGraph.workouts.completeSet(rows[1], 7, 42.5f)
            AppGraph.workouts.finishSession(sessionId, rating = 4, goalMet = true)
        }
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { SaveWorkoutCard(sessionId = sessionId, popup = false) }
        }
        settle(800)
        waitFor("card: update question") { exists(hasText("לעדכן את \"$name\"?")) }
        assertTrue("[$flow] step 'card': the new values are shown", exists(hasText("עכשיו", substring = true)))
        shot("01_question")
        tap("card: update", clickable("עדכון האימון") and isEnabled())
        waitFor("update: card shows 'saved'") { exists(hasText("נשמר")) }
        val item = runBlocking { AppGraph.db.templates().itemsOnce(templateId) }.single()
        assertEquals("[$flow] step 'update': sets as done today", "42.5:8;42.5:7", item.setsCsv)
        assertEquals("[$flow] step 'update': rest kept", 150, item.restSec)
        shot("02_updated")
    }

    // ================================================================== 6c. suggestions only at the start

    @Test
    fun suggestionsOnlyAtStart() {
        flow = "suggestions_only_at_start"
        seedBase()
        // two earlier sessions where he topped the reps at 40 kg: the start of the next one gets a weight-up suggestion
        runBlocking {
            repeat(2) {
                val id = AppGraph.workouts.startSession(customPlan(planned("bench_press", 2, 8, 40f, 150)), 7f, null)
                for (x in AppGraph.db.sessions().setsForOnce(id)) AppGraph.workouts.completeSet(x, 12, 40f)
                AppGraph.workouts.finishSession(id, rating = 4, goalMet = true)
            }
        }
        val templateId = runBlocking {
            AppGraph.workouts.saveTemplate(
                WorkoutTemplateEntity(nameHe = "תבנית הצעות", createdAt = System.currentTimeMillis()),
                listOf(TemplateItemEntity(templateId = 0L, orderIndex = 0, exerciseId = "bench_press", sets = 3, reps = 8, weightKg = 40f, restSec = 150))
            )
        }
        val sessionId = runBlocking { AppGraph.workouts.startFromTemplate(templateId) }
        show { WorkoutScreen(sessionId = sessionId, onFinished = {}, onAbandon = {}) }
        settle(1500)
        declineEventIfOffered()
        val hadCard = exists(hasText("הג'ים ברו מציע"))
        shot("01_start")
        // the first set is done: whatever the Gym Bro had to say, the card is gone and does not come back
        tap("set 1: done", clickable("סיימתי סט") and isEnabled(), settleMs = 600L)
        waitFor("set 1: stored") { sets(sessionId).count { it.completed } == 1 }
        declineEventIfOffered()
        settle(600)
        assertTrue("[$flow] step 'set 1': no suggestions mid-workout", !exists(hasText("הג'ים ברו מציע")))
        // a top set at the planned weight used to pop a live weight-up: not any more
        tap("set 2: done", clickable("סיימתי סט") and isEnabled(), settleMs = 600L)
        waitFor("set 2: stored") { sets(sessionId).count { it.completed } == 2 }
        declineEventIfOffered()
        settle(600)
        assertTrue("[$flow] step 'set 2': still no suggestions", !exists(hasText("הג'ים ברו מציע")))
        shot("02_mid_workout")
        println("[$flow] card at start: $hadCard")
    }

    // ================================================================== 6d. the coach compares with last time

    @Test
    fun coachCompareCard() {
        flow = "coach_compare_card"
        seedBase()
        runBlocking {
            val first = AppGraph.workouts.startSession(customPlan(planned("bench_press", 2, 8, 40f, 150), planned("lat_pulldown", 2, 10, 45f, 120)), 7f, null)
            for (x in AppGraph.db.sessions().setsForOnce(first)) AppGraph.workouts.completeSet(x, x.targetReps, x.targetWeight)
            AppGraph.workouts.finishSession(first, rating = 4, goalMet = true)
        }
        val sessionId = runBlocking { AppGraph.workouts.startSession(customPlan(planned("bench_press", 2, 8, 42.5f, 150), planned("lat_pulldown", 2, 10, 45f, 120), planned("lateral_raise", 2, 12, 8f, 75)), 7f, null) }
        runBlocking {
            for (x in AppGraph.db.sessions().setsForOnce(sessionId)) {
                // bench up (42.5), pulldown down (8 reps instead of 10), raise first time
                val reps = if (x.exerciseId == "lat_pulldown") 8 else x.targetReps
                AppGraph.workouts.completeSet(x, reps, x.targetWeight)
            }
            AppGraph.workouts.finishSession(sessionId, rating = 4, goalMet = true)
        }
        val bench = sets(sessionId).filter { it.exerciseId == "bench_press" }
        val benchPrev = runBlocking { AppGraph.workouts.lastDoneSessionsSetsFor("bench_press", 3) }.first { it.first().sessionId != sessionId }
        val row = com.tamir.hamasa.domain.coach.ExerciseCompare.row("bench_press", "לחיצת חזה", true, bench, benchPrev)!!
        assertEquals("[$flow] bench went up", com.tamir.hamasa.domain.coach.ExerciseCompare.Verdict.UP, row.verdict)
        assertTrue("[$flow] bench line", com.tamir.hamasa.domain.coach.ExerciseCompare.lineHe(row).contains("שיפור"))
        val pull = sets(sessionId).filter { it.exerciseId == "lat_pulldown" }
        val pullPrev = runBlocking { AppGraph.workouts.lastDoneSessionsSetsFor("lat_pulldown", 3) }.first { it.first().sessionId != sessionId }
        val prow = com.tamir.hamasa.domain.coach.ExerciseCompare.row("lat_pulldown", "משיכת פולי", true, pull, pullPrev)!!
        assertEquals("[$flow] pulldown went down", com.tamir.hamasa.domain.coach.ExerciseCompare.Verdict.DOWN, prow.verdict)
        val raise = com.tamir.hamasa.domain.coach.ExerciseCompare.row("lateral_raise", "הרחקות", true, sets(sessionId).filter { it.exerciseId == "lateral_raise" }, null)!!
        assertEquals("[$flow] first time", com.tamir.hamasa.domain.coach.ExerciseCompare.Verdict.FIRST, raise.verdict)
        assertEquals("[$flow] headline", "1 השתפרו, 1 ירדו, מתוך 2 שהושוו.", com.tamir.hamasa.domain.coach.ExerciseCompare.headlineHe(listOf(row, prow, raise)))

        show { com.tamir.hamasa.ui.workout.WorkoutDoneScreen(sessionId = sessionId, onClose = {}) }
        settle(2500)
        waitFor("done: compare card") { exists(hasText("הג'ים ברו מסכם")) }
        composeRule.onNode(hasText("הג'ים ברו מסכם")).performScrollTo()
        settle(400)
        assertTrue("[$flow] step 'done': headline shown", exists(hasText("1 השתפרו, 1 ירדו, מתוך 2 שהושוו.")))
        shot("01_compare")
    }

    // ================================================================== 6e. moving a workout to another day

    @Test
    fun sessionDateEdit() {
        flow = "session_date_edit"
        seedBase()
        val sessionId = runBlocking {
            val id = AppGraph.workouts.startSession(customPlan(planned("bench_press", 2, 8, 40f, 150)), 7f, null)
            for (x in AppGraph.db.sessions().setsForOnce(id)) AppGraph.workouts.completeSet(x, x.targetReps, x.targetWeight)
            AppGraph.workouts.finishSession(id, rating = 4, goalMet = true)
            id
        }
        val before = runBlocking { AppGraph.db.sessions().byId(sessionId)!! }
        val today = com.tamir.hamasa.domain.Days.today()
        assertEquals("[$flow] starts today", today, before.dateEpochDay)
        // the future is refused, the same day too
        assertTrue("[$flow] tomorrow refused", !runBlocking { AppGraph.workouts.editDate(sessionId, today + 1) })
        assertTrue("[$flow] same day refused", !runBlocking { AppGraph.workouts.editDate(sessionId, today) })

        var saved: Long? = null
        show {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                com.tamir.hamasa.ui.history.SessionDateRow(day = today, canEdit = true, onSave = { saved = it })
            }
        }
        settle(400)
        tap("date: open", clickable("שינוי תאריך"))
        tap("date: day before", clickable("יום קודם"))
        tap("date: day before again", clickable("יום קודם"))
        shot("01_picking")
        tap("date: save", clickable("שמירת התאריך") and isEnabled())
        assertEquals("[$flow] step 'save': two days back", today - 2, saved)

        assertTrue("[$flow] moved", runBlocking { AppGraph.workouts.editDate(sessionId, today - 2) })
        val after = runBlocking { AppGraph.db.sessions().byId(sessionId)!! }
        assertEquals("[$flow] new day", today - 2, after.dateEpochDay)
        assertEquals("[$flow] start moved by two days", before.startedAtMillis!! - 2 * 86_400_000L, after.startedAtMillis)
        assertEquals("[$flow] the length is kept", before.durationSec, after.durationSec)
        assertEquals("[$flow] rewards kept", before.xpEarned, after.xpEarned)
        assertEquals("[$flow] the sets stay with it", 2, sets(sessionId).count { it.completed })
    }

    // ================================================================== 7. stale ACTIVE sessions

    @Test
    fun staleActiveClosed() {
        flow = "stale_active"
        seedBase()
        val today = Days.today()
        val now = System.currentTimeMillis()
        val stale = runBlocking {
            AppGraph.db.sessions().insert(
                SessionEntity(dateEpochDay = today - 1, type = SessionType.GYM, status = SessionStatus.ACTIVE, modeId = "silence",
                    startedAtMillis = now - 26L * 60L * 60L * 1000L)
            )
        }
        val fresh = runBlocking { AppGraph.workouts.startSession(customPlan(planned("bench_press", 1, 8, 40f)), 7f, null) }
        assertTrue("[$flow] step 'start': a new session, not yesterday's", fresh != stale)
        assertEquals("[$flow] step 'start': yesterday's session closed", SessionStatus.SKIPPED, runBlocking { AppGraph.db.sessions().byId(stale) }?.status)
        assertEquals("[$flow] step 'start': exactly one ACTIVE", 1, runBlocking { AppGraph.db.sessions().activeSessionsOnce() }.size)
        // a second start (another button) opens the same workout
        val again = runBlocking { AppGraph.workouts.startSession(AppGraph.workouts.walkPlan(30), 7f, null) }
        assertEquals("[$flow] step 'second start': same session", fresh, again)

        // past midnight: started an hour ago on "yesterday" -> still the running workout
        runBlocking { AppGraph.workouts.abandonSession(fresh) }
        val lateNight = runBlocking {
            AppGraph.db.sessions().insert(
                SessionEntity(dateEpochDay = today - 1, type = SessionType.GYM, status = SessionStatus.ACTIVE, modeId = "silence",
                    startedAtMillis = now - 60L * 60L * 1000L)
            )
        }
        val opened = runBlocking { AppGraph.workouts.startSession(customPlan(planned("bench_press", 1, 8, 40f)), 7f, null) }
        assertEquals("[$flow] step 'past midnight': the running workout is kept", lateNight, opened)
        assertEquals("[$flow] step 'past midnight': still ACTIVE", SessionStatus.ACTIVE, runBlocking { AppGraph.db.sessions().byId(lateNight) }?.status)
    }
}
