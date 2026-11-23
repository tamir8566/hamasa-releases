package com.tamir.hamasa.screenshots

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.analysis.AnalysisScreen
import com.tamir.hamasa.ui.badges.BadgesScreen
import com.tamir.hamasa.ui.coach.CoachScreen
import com.tamir.hamasa.ui.coach.HomeWorkoutContent
import com.tamir.hamasa.ui.coach.HomeWorkoutUiState
import com.tamir.hamasa.domain.coach.HomeGear
import com.tamir.hamasa.domain.coach.HomeRequest
import com.tamir.hamasa.ui.exercises.CustomExercisesScreen
import com.tamir.hamasa.ui.food.FoodScreen
import com.tamir.hamasa.ui.energy.EnergyScreen
import com.tamir.hamasa.ui.history.HistoryScreen
import com.tamir.hamasa.ui.home.HomeScreen
import com.tamir.hamasa.ui.home.MorningRollScreen
import com.tamir.hamasa.ui.home.OnboardingScreen
import com.tamir.hamasa.ui.map.MapScreen
import com.tamir.hamasa.ui.settings.SettingsScreen
import com.tamir.hamasa.ui.shop.ShopScreen
import com.tamir.hamasa.ui.templates.TemplateEditorScreen
import com.tamir.hamasa.ui.templates.TemplatesScreen
import com.tamir.hamasa.ui.theme.HamasaTheme
import com.tamir.hamasa.ui.tree.TreeScreen
import com.tamir.hamasa.ui.weight.WeightScreen
import com.tamir.hamasa.ui.workout.RewardsHolder
import com.tamir.hamasa.ui.workout.WorkoutDoneScreen
import com.tamir.hamasa.ui.workout.WorkoutScreen
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Renders every screen of the app on the JVM (Robolectric, no emulator) and writes a PNG per
 * screen to app/build/screenshots/. CI zips that folder and attaches it to the GitHub Release.
 *
 * The images are only written when Gradle runs with -Proborazzi.test.record=true (the Roborazzi
 * plugin turns that into the record mode); without it captureRoboImage is a no-op.
 *
 * One @Test per screen: each test gets a fresh Activity (and a fresh Room database, because
 * Robolectric gives every test its own data directory), so one broken screen never affects
 * the others. A screen that throws is replaced by a placeholder PNG that carries the error text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "iw-ldrtl-w411dp-h915dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Ids of the sessions seeded in [seed]. */
    private var activeSessionId: Long = -1L
    private var doneSessionId: Long = -1L
    private var templateId: Long = -1L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // HamasaApp (declared in the manifest) already called AppGraph.init in onCreate; this is
        // only a safety net in case the Application class was not instantiated by Robolectric.
        val initialised = runCatching { AppGraph.workouts }.isSuccess
        if (!initialised) AppGraph.init(context)
        // WorkManager: the app removes the default initializer, so anything that enqueues work
        // (cloud push, reminders) needs a test instance; otherwise the screens would crash.
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
            )
        }
        runBlocking { seed() }
    }

    // ------------------------------------------------------------------ seed state

    private suspend fun seed() {
        val workouts = AppGraph.workouts
        val game = AppGraph.game
        val db = AppGraph.db
        val today = Days.today()
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L

        workouts.seedIfNeeded()

        // Body weight: three entries so the weight chart has a line (THE IRON RULE: body only from weights).
        workouts.logWeight(105f, today - 14)
        workouts.logWeight(104.2f, today - 7)
        workouts.logWeight(103.5f, today)

        // A few finished gym sessions on different days with completed sets -> history, heatmap, map, tree.
        val past = listOf(
            Triple(10L, "pr_day", listOf("bench_press" to 60f, "lat_pulldown" to 55f, "leg_press" to 120f, "db_curl" to 12f)),
            Triple(7L, "race_clock", listOf("squat" to 70f, "leg_press" to 125f, "plank" to 0f)),
            Triple(4L, "one_more", listOf("bench_press" to 62.5f, "shoulder_press" to 18f, "lat_pulldown" to 57.5f)),
            Triple(2L, "pr_day", listOf("db_bench_press" to 24f, "db_curl" to 12f, "plank" to 0f))
        )
        for ((daysAgo, modeId, exercises) in past) {
            val startedAt = now - daysAgo * dayMs
            val sessionId = db.sessions().insert(
                SessionEntity(
                    dateEpochDay = today - daysAgo, type = SessionType.GYM, status = SessionStatus.DONE,
                    modeId = modeId, dailyGoal = "לעמוד בכל זמני המנוחה", startedAtMillis = startedAt,
                    endedAtMillis = startedAt + 55L * 60L * 1000L, durationSec = 55 * 60,
                    xpEarned = 100, goldEarned = 10, rating = 4, sleepHours = 7f
                )
            )
            val sets = ArrayList<SetLogEntity>()
            for ((exerciseId, weight) in exercises) {
                val reps = if (exerciseId == "plank") 45 else 8
                for (i in 0 until 3) {
                    sets.add(
                        SetLogEntity(
                            sessionId = sessionId, exerciseId = exerciseId, setIndex = i,
                            targetReps = reps, targetWeight = weight, reps = reps, weight = weight,
                            completed = true, isPr = i == 0 && weight > 0f
                        )
                    )
                }
            }
            db.sessions().insertSets(sets)
        }
        // One yoga session so the endurance branch and the "two birds" style badges have data.
        db.sessions().insert(
            SessionEntity(
                dateEpochDay = today - 5, type = SessionType.YOGA, status = SessionStatus.DONE, modeId = null,
                startedAtMillis = now - 5L * dayMs, endedAtMillis = now - 5L * dayMs + 30L * 60L * 1000L,
                durationSec = 30 * 60, xpEarned = 50, goldEarned = 5, rating = 5
            )
        )

        // Badges, inventory (one equipped), a meal, a custom template.
        game.unlockBadge("first_blood")
        game.unlockBadge("fifth")
        game.addItem("red_cap")
        game.addItem("leather_vest")
        game.equip("leather_vest")
        // Stage 2 F: two manual meals with macros and one photo meal with recognized items (17_food.png).
        db.meals().insert(MealEntity(dateEpochDay = today, atMillis = now - 6L * 60L * 60L * 1000L, description = "שקשוקה עם לחם מלא", kcal = 540,
            proteinG = 26f, carbsG = 48f, fatG = 25f))
        db.meals().insert(MealEntity(dateEpochDay = today, atMillis = now - 3L * 60L * 60L * 1000L, description = "שניצל, אורז וסלט ישראלי", kcal = 780,
            proteinG = 48f, carbsG = 72f, fatG = 30f, source = "photo", aiKcal = 760,
            itemsJson = "[{\"name_he\":\"שניצל\",\"grams\":180,\"kcal\":430,\"protein_g\":40,\"carbs_g\":18,\"fat_g\":22,\"confidence\":0.8}," +
                "{\"name_he\":\"אורז לבן\",\"grams\":200,\"kcal\":260,\"protein_g\":5,\"carbs_g\":52,\"fat_g\":1,\"confidence\":0.7}," +
                "{\"name_he\":\"סלט ישראלי\",\"grams\":150,\"kcal\":90,\"protein_g\":3,\"carbs_g\":2,\"fat_g\":7,\"confidence\":0.9}]"))
        db.meals().insert(MealEntity(dateEpochDay = today, atMillis = now - 1L * 60L * 60L * 1000L, description = "יוגורט חלבון", kcal = 140,
            proteinG = 20f, carbsG = 9f, fatG = 2f))
        // Per-set values (setsCsv "kg:reps;...") so the editor shows a varied set table.
        templateId = workouts.saveTemplate(
            WorkoutTemplateEntity(nameHe = "חזה וכתפיים שלי", createdAt = now - 3L * dayMs, lastUsedAt = now - dayMs),
            listOf(
                TemplateItemEntity(templateId = 0L, orderIndex = 0, exerciseId = "bench_press", sets = 4, reps = 8, weightKg = 60f, restSec = 90,
                    setsCsv = "55:10;60:8;62.5:6;65:5"),
                TemplateItemEntity(templateId = 0L, orderIndex = 1, exerciseId = "shoulder_press", sets = 3, reps = 12, weightKg = 16f, restSec = 75,
                    setsCsv = "16:12;18:10;18:9"),
                TemplateItemEntity(templateId = 0L, orderIndex = 2, exerciseId = "db_curl", sets = 3, reps = 12, weightKg = 10f, restSec = 60,
                    setsCsv = "10:12;12:12;12:10")
            )
        )

        // Profile must be onboarded BEFORE Home is rendered (otherwise it navigates to onboarding
        // and HomeViewModel tries a cloud restore). Morning roll of today so the coach has a mode.
        AppGraph.prefs.update { p ->
            p.copy(
                onboarded = true, heightCm = 185, startWeightKg = 105f, goalWeightKg = 90f, focus = Focus.LOSE_WEIGHT,
                xp = 1500, gold = 420, streak = 4, bestStreak = 4, shields = 1,
                lastActiveDay = today - 1, lastRollDay = today, rollModeId = "pr_day",
                rollGoal = "לשבור שיא בתרגיל אחד", rollBonus = "none", lifetimeKg = 18_450.0, kcalGoal = 2200
            )
        }

        // A finished session of today (for the "workout done" screen) and an active one (for the workout screen).
        val donePlan = workouts.planFor(SessionType.GYM, 7f)
        doneSessionId = workouts.startSession(donePlan, 7f, "לשבור שיא בתרגיל אחד")
        for (set in db.sessions().setsForOnce(doneSessionId)) {
            workouts.completeSet(set, set.targetReps, set.targetWeight)
        }
        RewardsHolder.last = workouts.finishSession(doneSessionId, rating = 4, goalMet = true)

        val activePlan = workouts.planFor(SessionType.GYM, 7f)
        activeSessionId = workouts.startSession(activePlan, 7f, "לשבור שיא בתרגיל אחד")
        // Complete the first set so the workout screen shows a mix of done and pending sets.
        db.sessions().setsForOnce(activeSessionId).firstOrNull()?.let { workouts.completeSet(it, it.targetReps, it.targetWeight) }

        // Re-assert the headline numbers after finishSession/badges granted their own xp and gold.
        AppGraph.prefs.update { p -> p.copy(xp = 1500, gold = 420, streak = 4, bestStreak = 4, shields = 1, age = 21, sex = "M") }
    }

    // ------------------------------------------------------------------ capture helper

    /** Lets Room flows, produceState bitmaps and animations settle: idles the main looper between short sleeps. */
    private fun settle(totalMs: Long) {
        val step = 100L
        var waited = 0L
        while (waited < totalMs) {
            Thread.sleep(step)
            waited += step
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    /**
     * Renders [content] in the app theme, RTL, waits [settleMs] and writes build/screenshots/[name].png.
     * Any failure (unsupported Canvas API in Robolectric, missing data, ...) becomes a placeholder PNG
     * so the remaining screens are still captured by their own tests.
     */
    private fun capture(name: String, settleMs: Long = 600L, content: @Composable () -> Unit) {
        val file = File("build/screenshots/$name.png")
        file.parentFile?.mkdirs()
        try {
            composeRule.setContent {
                HamasaTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        // Same dark window background the real Activity theme gives the app.
                        androidx.compose.material3.Surface(color = com.tamir.hamasa.ui.theme.HColors.Slate) { content() }
                    }
                }
            }
            settle(settleMs)
            composeRule.onRoot().captureRoboImage(file.path)
        } catch (t: Throwable) {
            writePlaceholder(file, name, t)
        }
    }

    private fun writePlaceholder(file: File, name: String, t: Throwable) {
        try {
            val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
            // The text file is the useful part when debugging; the PNG is just a visible marker.
            File(file.parentFile, "$name.error.txt").writeText(trace)
            val lines = ArrayList<String>()
            lines.add("SCREEN FAILED: $name")
            lines.add(t.javaClass.name + ": " + (t.message ?: ""))
            lines.addAll(trace.lines().drop(1).take(30))
            val bmp = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.rgb(0x1F, 0x23, 0x28))
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(0xE9, 0xD9, 0xB8)
                textSize = 26f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            var y = 60f
            for (line in lines) {
                canvas.drawText(line.take(90), 30f, y, paint)
                y += 34f
            }
            java.io.FileOutputStream(file).use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        } catch (ignored: Throwable) {
            // Nothing else to do: never fail the run because of the placeholder itself.
        }
    }

    // ------------------------------------------------------------------ screens

    @Test fun home() = capture("01_home", 1200L) {
        HomeScreen(onRoll = {}, onStartWorkout = {}, onOpenWeight = {}, onOpenBadges = {}, onOpenHistory = {}, onOpenSettings = {}, onNeedOnboarding = {})
    }

    @Test fun morningRoll() = capture("02_morning_roll", 1200L) {
        MorningRollScreen(onStart = {}, onBack = {})
    }

    @Test fun coach() = capture("03_coach", 1200L) {
        CoachScreen(onStartWorkout = {}, onOpenCustomExercises = {}, onOpenTemplates = {})
    }

    @Test fun customExercises() = capture("04_custom_exercises") {
        CustomExercisesScreen(onBack = {})
    }

    @Test fun templates() = capture("05_templates") {
        TemplatesScreen(onStart = {}, onEdit = {}, onNew = {})
    }

    @Test fun templateEditorNew() = capture("06_template_editor_new") {
        TemplateEditorScreen(templateId = -1L, onDone = {})
    }

    @Test fun templateEditorEdit() = capture("06b_template_editor_edit", 1500L) {
        TemplateEditorScreen(templateId = templateId, onDone = {})
    }

    @Test fun workout() = capture("07_workout", 1500L) {
        WorkoutScreen(sessionId = activeSessionId, onFinished = {}, onAbandon = {})
    }

    @Test fun sessionDetail() = capture("19_session_detail", 2500L) {
        com.tamir.hamasa.ui.history.SessionDetailScreen(sessionId = doneSessionId, onStartWorkout = {})
    }

    @Test fun workoutDone() = capture("08_workout_done", 2500L) {
        WorkoutDoneScreen(sessionId = doneSessionId, onClose = {})
    }

    @Test fun history() = capture("09_history", 1500L) {
        HistoryScreen(onOpenAnalysis = {})
    }

    @Test fun analysis() = capture("10_analysis", 3500L) {
        AnalysisScreen()
    }

    @Test fun weight() = capture("11_weight", 1000L) {
        WeightScreen()
    }

    @Test fun tree() = capture("12_tree", 2000L) {
        TreeScreen()
    }

    @Test fun map() = capture("13_map", 2000L) {
        MapScreen()
    }

    @Test fun badges() = capture("14_badges", 2000L) {
        BadgesScreen()
    }

    @Test fun shop() = capture("15_shop", 2000L) {
        ShopScreen()
    }

    @Test fun settings() = capture("16_settings", 1000L) {
        SettingsScreen()
    }

    @Test fun food() = capture("17_food", 1000L) {
        FoodScreen()
    }

    @Test fun energy() = capture("20_energy", 2000L) {
        EnergyScreen()
    }

    @Test fun onboarding() = capture("18_onboarding") {
        OnboardingScreen(onDone = {})
    }

    /** New-user onboarding, step 1 (character) on a fresh profile. */
    @Test fun onboardingCharacter() {
        runBlocking { AppGraph.prefs.update { com.tamir.hamasa.data.prefs.Profile() } }
        capture("18b_onboarding_character", 1000L) {
            com.tamir.hamasa.ui.home.OnboardingFlow(startStep = 1, onDone = {})
        }
    }

    /** New-user onboarding, step 4 ("איפה אתה עכשיו?", the stage picker) on a fresh profile. */
    @Test fun onboardingStage() {
        runBlocking { AppGraph.prefs.update { com.tamir.hamasa.data.prefs.Profile() } }
        capture("18c_onboarding_stage", 1000L) {
            com.tamir.hamasa.ui.home.OnboardingFlow(startStep = 4, onDone = {})
        }
    }

    // ------------------------------------------------------------------ female player + שמביולוקה

    private fun female(on: Boolean) {
        val g = if (on) "F" else "M"
        runBlocking { AppGraph.prefs.update { it.copy(userGender = g, characterVariant = g) } }
        com.tamir.hamasa.domain.game.Addr.set(g)
        com.tamir.hamasa.domain.game.CharacterNames.set(g)
    }

    private fun femaleCapture(name: String, settleMs: Long, content: @Composable () -> Unit) {
        female(true)
        try { capture(name, settleMs, content) } finally { female(false) }
    }

    @Test fun homeFemale() = femaleCapture("40_home_female", 1200L) {
        HomeScreen(onRoll = {}, onStartWorkout = {}, onOpenWeight = {}, onOpenBadges = {}, onOpenHistory = {}, onOpenSettings = {}, onNeedOnboarding = {})
    }

    @Test fun coachFemale() = femaleCapture("41_coach_female", 1200L) {
        CoachScreen(onStartWorkout = {}, onOpenCustomExercises = {}, onOpenTemplates = {})
    }

    @Test fun settingsFemale() = femaleCapture("42_settings_female", 1000L) {
        SettingsScreen()
    }

    @Test fun badgesFemale() = femaleCapture("43_badges_female", 2000L) {
        BadgesScreen()
    }

    @Test fun onboardingStageFemale() {
        runBlocking { AppGraph.prefs.update { com.tamir.hamasa.data.prefs.Profile() } }
        femaleCapture("44_onboarding_stage_female", 1000L) {
            com.tamir.hamasa.ui.home.OnboardingFlow(startStep = 4, onDone = {})
        }
    }

    @Test fun historyHub() = capture("30_history_hub", 2000L) {
        com.tamir.hamasa.ui.history.HistoryHubScreen()
    }

    @Test fun adventure() = capture("31_adventure", 2000L) {
        com.tamir.hamasa.ui.adventure.AdventureScreen()
    }

    @Test fun bossBoard() = capture("32_boss_board", 2000L) {
        com.tamir.hamasa.ui.adventure.BossBoardScreen()
    }

    /** "אימון בבית": the equipment sheet before "בנה אימון" (dumbbells up to 10 kg, 30 minutes). */
    @Test fun homeWorkoutSheet() {
        val gear = setOf(HomeGear.BODY, HomeGear.BAND, HomeGear.DUMBBELL, HomeGear.BENCH, HomeGear.ROPE)
        val state = HomeWorkoutUiState(loaded = true, gear = gear, dumbbellKgText = "10", minutes = 30, focus = HomeGear.FOCUS_FULL)
        capture("33_home_workout", 1000L) { HomeSheet(state) }
    }

    /** The same sheet after "בנה אימון": the coach's plan from that gear (summary line, preview list, "התחל"). */
    @Test fun homeWorkoutPlan() {
        val req = HomeRequest(
            gear = setOf(HomeGear.BODY, HomeGear.BAND, HomeGear.DUMBBELL, HomeGear.BENCH, HomeGear.ROPE),
            dumbbellMaxKg = 10f, maxMinutes = 30, focus = HomeGear.FOCUS_FULL
        )
        val plan = runBlocking { AppGraph.workouts.homePlan(req, 7f) }
        val state = HomeWorkoutUiState(loaded = true, gear = req.gear, dumbbellKgText = "10", minutes = 30, focus = req.focus, plan = plan)
        capture("33b_home_workout_plan", 1000L) { HomeSheet(state) }
    }

    @Composable
    private fun HomeSheet(state: HomeWorkoutUiState) {
        HomeWorkoutContent(
            state = state,
            onToggle = {}, onDumbbellKg = {}, onDumbbellFixed = {}, onKettlebellKg = {},
            onMinutes = {}, onFocus = {}, onBuild = {}, onStart = {}, onClose = {},
            why = { _ -> "" }
        )
    }
}
