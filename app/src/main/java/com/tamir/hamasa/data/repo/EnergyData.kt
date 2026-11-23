package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.energy.ActivityLevel
import com.tamir.hamasa.domain.energy.AdaptiveTdee
import com.tamir.hamasa.domain.energy.Bmr
import com.tamir.hamasa.domain.energy.Confidence
import com.tamir.hamasa.domain.energy.EnergyModel
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.energy.Intensity
import com.tamir.hamasa.domain.energy.IntakeTarget
import com.tamir.hamasa.domain.energy.Sex
import com.tamir.hamasa.domain.energy.TdeeBreakdown
import com.tamir.hamasa.domain.energy.WorkoutBurn
import com.tamir.hamasa.domain.energy.WorkoutKind
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.data.health.DaySteps
import com.tamir.hamasa.data.health.WatchStore
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One day: what the body burned, what he ate, and the workouts' share of the burn.
 * [estimated]: nothing was logged that day and [intake] is his usual day (see WeekCalories.usualIntake).
 */
data class EnergyDay(val day: Long, val burn: Int, val intake: Int, val workouts: Int, val estimated: Boolean = false)

/** A stretch of days summed: the month's "burned against eaten". Only finished days that have an intake count. */
data class EnergyTotals(val days: Int, val estimatedDays: Int, val burned: Int, val eaten: Int) {
    /** eaten - burned: negative = a deficit. */
    val balance: Int get() = eaten - burned
    val avgBurn: Int get() = if (days > 0) burned / days else 0
    val avgEaten: Int get() = if (days > 0) eaten / days else 0
}

/** Everything the energy screen shows, computed once from prefs + tables (nothing new is stored). */
data class EnergySnapshot(
    val profile: Profile,
    val weightKg: Float,
    val hasWeight: Boolean,
    val bmr: Bmr,
    val neatToday: Int,
    val workoutsToday: Int,
    val intakeToday: Int,
    val tefToday: Int,
    val burnToday: Int,
    /** intake - burn; negative = deficit. */
    val balanceToday: Int,
    val model: TdeeBreakdown,
    val adaptive: AdaptiveTdee,
    /** TDEE the automatic target is built on. */
    val tdeeUsed: Int,
    val usingAdaptive: Boolean,
    val target: IntakeTarget,
    val ratePct: Float,
    /** Goals with the user's overrides (what the food screen shows). */
    val effective: MacroTargets,
    /** Goals with every field automatic, for the target kcal of the model. */
    val auto: MacroTargets,
    val week: List<EnergyDay>,
    // --- stage 3 (watch) ---
    /** Today's steps from Health Connect; null = no watch data today. */
    val stepsToday: Int? = null,
    /** Where today's daily activity number comes from, in Hebrew ("לפי הצעדים מהשעון: 8,412 צעדים"). */
    val neatSourceHe: String = "",
    /** True when the model TDEE uses the average step-based activity of recent days instead of the level. */
    val modelFromSteps: Boolean = false,
    /** epochDay of the weigh-in [weightKg] comes from; null = no weigh-in yet (start weight). */
    val weightDay: Long? = null,
    /** The direction in force ("הכיוון"): chosen by the user, or automatic. */
    val direction: GoalDirection = GoalDirection.CUT,
    /** True when [direction] was picked automatically (Prefs.goalDirection = -1). */
    val directionAuto: Boolean = true,
    /** Lean-gain rate, % of body weight per week. */
    val gainRatePct: Float = 0.25f
)

/** Energy calculator glue: reads prefs and tables, calls the pure EnergyModel. */
object EnergyData {
    /** Used only until the age is answered on the energy screen. */
    const val FALLBACK_AGE = 30
    /** Days with watch steps (of the last 14) needed before the model TDEE uses steps instead of the level. */
    const val MIN_STEP_DAYS = 3

    private fun fmtInt(v: Int): String = String.format(Locale.US, "%,d", v)

    fun sexOf(p: Profile): Sex = if (p.sex == "F") Sex.FEMALE else Sex.MALE
    fun ageOf(p: Profile): Int = if (p.age in 14..100) p.age else FALLBACK_AGE
    fun activityOf(p: Profile): ActivityLevel = ActivityLevel.fromIndex(p.activityLevel)
    fun ratePctOf(p: Profile): Float = (p.goalRateX100.coerceIn(10, 100)) / 100f
    fun gainRatePctOf(p: Profile): Float = (p.gainRateX100.coerceIn(10, 50)) / 100f

    /**
     * "הכיוון" in force. A choice of the user (Prefs.goalDirection 0..2) wins. Automatic (-1):
     * above the goal weight (by more than 0.5 kg) -> cut; below it -> gain (or maintain when the focus is weight loss);
     * at the goal -> gain for the muscle-building focus, maintain otherwise.
     */
    fun directionOf(p: Profile, kg: Float): GoalDirection {
        GoalDirection.fromIndex(p.goalDirection)?.let { return it }
        return autoDirection(p, kg)
    }

    fun autoDirection(p: Profile, kg: Float): GoalDirection = when {
        kg > p.goalWeightKg + 0.5f -> GoalDirection.CUT
        kg < p.goalWeightKg - 0.5f -> if (p.focus == Focus.LOSE_WEIGHT) GoalDirection.MAINTAIN else GoalDirection.GAIN
        p.focus == Focus.BUILD_MUSCLE -> GoalDirection.GAIN
        else -> GoalDirection.MAINTAIN
    }

    fun bmrOf(p: Profile, kg: Float): Bmr = EnergyModel.bmr(kg, p.heightCm.toFloat(), ageOf(p), sexOf(p), p.bodyFatPct)

    fun kindOf(type: SessionType): WorkoutKind = when (type) {
        SessionType.GYM, SessionType.MINI -> WorkoutKind.STRENGTH
        SessionType.YOGA -> WorkoutKind.YOGA
        SessionType.WALK -> WorkoutKind.WALK
        SessionType.RUN -> WorkoutKind.RUN
    }

    /** Minutes of a finished session: stored duration, else start/end stamps; 0 = unknown. */
    fun minutesOf(s: SessionEntity): Float {
        if (s.durationSec > 0) return s.durationSec / 60f
        val a = s.startedAtMillis
        val b = s.endedAtMillis
        if (a != null && b != null && b > a) return ((b - a) / 60000f).coerceAtMost(300f)
        return 0f
    }

    /**
     * Net burn of one session, computed on the fly. Strength intensity comes from training density
     * (completed sets per minute); yoga / walk / run use their usual Compendium value.
     * [avgHr] is the stage 3 hook (watch heart rate); null today.
     */
    fun burnFor(s: SessionEntity, completedSets: Int, kg: Float, p: Profile, avgHr: Int? = null, distanceM: Int? = null): WorkoutBurn? {
        val minutes = minutesOf(s)
        if (minutes <= 0f) return null
        val kind = kindOf(s.type)
        val intensity = when {
            kind == WorkoutKind.STRENGTH -> EnergyModel.strengthIntensity(completedSets, minutes)
            // a yoga class he logged by hand carries its level in the notes ("yoga:light" / "yoga:hard")
            kind == WorkoutKind.YOGA && s.notes == "yoga:light" -> Intensity.LIGHT
            kind == WorkoutKind.YOGA && s.notes == "yoga:hard" -> Intensity.VIGOROUS
            else -> Intensity.MODERATE
        }
        val bmr = bmrOf(p, kg)
        return EnergyModel.workoutKcal(kind, minutes, kg, intensity, avgHr, ageOf(p), sexOf(p), bmr.kcal, distanceM = distanceM)
    }

    /** The measured distance of a session (watch, or typed by him), null when none. */
    fun distanceOf(sessionId: Long): Int? = WatchStore.current().sessions[sessionId]?.distanceM?.takeIf { it > 0 }

    /** Burn of a finished session for the done / detail screens; null while unknown. */
    suspend fun sessionBurn(sessionId: Long): WorkoutBurn? {
        val db = AppGraph.db
        val s = db.sessions().byId(sessionId) ?: return null
        val p = AppGraph.prefs.current()
        val kg = db.weights().latestOnOrBefore(s.dateEpochDay)?.kg ?: db.weights().latestOnce()?.kg ?: p.startWeightKg
        val sets = db.sessions().setsForOnce(sessionId).count { it.completed }
        return burnFor(s, sets, kg, p, avgHr = WatchStore.hrFor(s.id)?.avgHr, distanceM = distanceOf(s.id))
    }

    /** Builds the whole picture (today, last 7 days, model and real-data TDEE, target, macros). */
    suspend fun snapshot(): EnergySnapshot {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val today = Days.today()
        val weights = db.weights().allOnce()
        val latest = weights.lastOrNull()
        val kg = latest?.kg ?: p.startWeightKg
        val bmr = bmrOf(p, kg)
        val level = activityOf(p)
        val neat = EnergyModel.neatFromLevel(bmr.kcal, level)

        // intake per day
        val intakes = HashMap<Long, Int>()
        for (m in db.meals().allOnce()) intakes[m.dateEpochDay] = (intakes[m.dateEpochDay] ?: 0) + m.kcal.coerceAtLeast(0)

        // workout burn per day, last 28 days (for today, the chart and the 14-day average)
        val workoutsByDay = HashMap<Long, Int>()
        for (s in db.sessions().doneSessionsOnce()) {
            if (s.dateEpochDay < today - 27 || s.dateEpochDay > today) continue
            val sets = db.sessions().setsForOnce(s.id).count { it.completed }
            val sKg = weights.lastOrNull { it.dateEpochDay <= s.dateEpochDay }?.kg ?: kg
            val b = burnFor(s, sets, sKg, p, avgHr = WatchStore.hrFor(s.id)?.avgHr, distanceM = distanceOf(s.id)) ?: continue
            workoutsByDay[s.dateEpochDay] = (workoutsByDay[s.dateEpochDay] ?: 0) + b.kcal
        }
        val avgWorkouts = (today - 13..today).sumOf { (workoutsByDay[it] ?: 0) } / 14f

        // stage 3: a day with a step count from the watch uses the steps instead of the activity level.
        // Steps inside logged walk/run sessions are left out (they are already in the workout burn).
        val stepDays: Map<Long, DaySteps> = runCatching { WatchStore.current().steps }.getOrDefault(emptyMap())
        val heightCm = p.heightCm.toFloat()
        fun stepNeat(d: Long): Float? = stepDays[d]?.let { EnergyModel.neatWithSteps(bmr.kcal, it.outside, kg, heightCm) }
        val pastStepNeat = (today - 14 until today).mapNotNull { stepNeat(it) }
        val typicalStepNeat: Float? = if (pastStepNeat.size >= MIN_STEP_DAYS) pastStepNeat.average().toFloat() else null
        val typicalNeat = typicalStepNeat ?: neat
        val todaySteps = stepDays[today]
        val todayStepNeat = stepNeat(today)
        // Today is a forecast for the whole day: the steps so far, never below a usual day.
        val neatToday = if (todayStepNeat != null) max(todayStepNeat, typicalNeat) else neat
        fun neatOn(d: Long): Float = if (d == today) neatToday else (stepNeat(d) ?: neat)
        val stepsTodayText = fmtInt(todaySteps?.total ?: 0)
        val todayStepNeatValue: Float = todayStepNeat ?: 0f
        val neatSourceHe = when {
            todaySteps == null || todayStepNeat == null -> "לפי רמת הפעילות (${level.nameHe})"
            todayStepNeatValue >= typicalNeat -> "לפי הצעדים מהשעון: $stepsTodayText צעדים"
            typicalStepNeat != null -> "לפי הצעדים מהשעון: $stepsTodayText צעדים עד עכשיו. עד סוף היום, לפי ממוצע הצעדים שלך."
            else -> "לפי הצעדים מהשעון: $stepsTodayText צעדים עד עכשיו. עד סוף היום, לפי רמת הפעילות."
        }

        val model = EnergyModel.tdeeEstimate(bmr.kcal, level, avgWorkouts, neatOverride = typicalStepNeat)
        val adaptive = EnergyModel.adaptiveTdee(weights.map { it.dateEpochDay to it.kg }, intakes, today)
        val usingAdaptive = p.useAdaptiveTdee && adaptive.confidence != Confidence.NONE
        val tdeeUsed = if (usingAdaptive) adaptive.tdee else model.total
        val rate = ratePctOf(p)
        val gainRate = gainRatePctOf(p)
        val direction = directionOf(p, kg)
        val target = EnergyModel.intakeFor(direction, tdeeUsed.toFloat(), kg, rate, gainRate, bmr.kcal, sexOf(p))

        val intakeToday = intakes[today] ?: 0
        val workoutsToday = workoutsByDay[today] ?: 0
        val burnToday = EnergyModel.dayBurn(bmr.kcal, neatToday, workoutsToday.toFloat(), intakeToday.toFloat())
        val tefToday = EnergyModel.tef(intakeToday.toFloat()).roundToInt()

        val week = (today - 6..today).map { d ->
            val eat = workoutsByDay[d] ?: 0
            val intake = intakes[d] ?: 0
            EnergyDay(d, EnergyModel.dayBurn(bmr.kcal, neatOn(d), eat.toFloat(), intake.toFloat()), intake, eat)
        }

        val lean = EnergyModel.leanMass(kg, p.bodyFatPct)
        val kcalForGoals = if (p.kcalAuto) target.kcal else p.kcalGoal
        val effective = EnergyModel.macroTargets(kcalForGoals, kg, p.heightCm.toFloat(), lean, p.proteinGoal, p.carbsGoal, p.fatGoal, direction)
        val auto = EnergyModel.macroTargets(target.kcal, kg, p.heightCm.toFloat(), lean, direction = direction)

        return EnergySnapshot(
            profile = p, weightKg = kg, hasWeight = latest != null, bmr = bmr,
            neatToday = neatToday.roundToInt(), workoutsToday = workoutsToday, intakeToday = intakeToday,
            tefToday = tefToday, burnToday = burnToday, balanceToday = intakeToday - burnToday,
            model = model, adaptive = adaptive, tdeeUsed = tdeeUsed, usingAdaptive = usingAdaptive,
            target = target, ratePct = rate, effective = effective, auto = auto, week = week,
            stepsToday = todaySteps?.total, neatSourceHe = neatSourceHe, modelFromSteps = typicalStepNeat != null,
            weightDay = latest?.dateEpochDay, direction = direction,
            directionAuto = GoalDirection.fromIndex(p.goalDirection) == null, gainRatePct = gainRate
        )
    }

    /**
     * Burn and intake of every day in [from]..[to] (days after today are left out), for the weekly and monthly
     * summaries of any period. Each day uses the weight he had then, the watch steps of that day when there are
     * any, and the workouts of that day. A finished day with nothing logged, from his first logged meal on,
     * counts as his usual day ([EnergyDay.estimated]). Today's burn is the forecast of [snapshot].
     */
    suspend fun days(from: Long, to: Long): List<EnergyDay> {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val today = Days.today()
        val last = if (to > today) today else to
        if (last < from) return emptyList()
        val weights = db.weights().allOnce()
        val intakes = HashMap<Long, Int>()
        for (m in db.meals().allOnce()) intakes[m.dateEpochDay] = (intakes[m.dateEpochDay] ?: 0) + m.kcal.coerceAtLeast(0)
        val firstLogged: Long = intakes.filter { it.value > 0 }.keys.minOrNull() ?: Long.MAX_VALUE
        val usual = com.tamir.hamasa.domain.food.WeekCalories.usualIntake(intakes, last, today)
        val level = activityOf(p)
        val heightCm = p.heightCm.toFloat()
        val stepDays: Map<Long, DaySteps> = runCatching { WatchStore.current().steps }.getOrDefault(emptyMap())
        val workoutsByDay = HashMap<Long, Int>()
        for (s in db.sessions().doneSessionsOnce()) {
            if (s.dateEpochDay < from || s.dateEpochDay > last) continue
            val sets = db.sessions().setsForOnce(s.id).count { it.completed }
            val sKg = weights.lastOrNull { it.dateEpochDay <= s.dateEpochDay }?.kg ?: weights.firstOrNull()?.kg ?: p.startWeightKg
            val b = burnFor(s, sets, sKg, p, avgHr = WatchStore.hrFor(s.id)?.avgHr, distanceM = distanceOf(s.id)) ?: continue
            workoutsByDay[s.dateEpochDay] = (workoutsByDay[s.dateEpochDay] ?: 0) + b.kcal
        }
        val todayBurn: Int? = if (last >= today) runCatching { snapshot().burnToday }.getOrNull() else null
        val out = ArrayList<EnergyDay>()
        for (d in from..last) {
            val kg = weights.lastOrNull { it.dateEpochDay <= d }?.kg ?: weights.firstOrNull()?.kg ?: p.startWeightKg
            val bmr = bmrOf(p, kg)
            val logged = intakes[d] ?: 0
            val estimated = logged <= 0 && d < today && usual > 0 && d >= firstLogged
            val intake = if (estimated) usual else logged
            val steps = stepDays[d]
            val neat = if (steps != null) EnergyModel.neatWithSteps(bmr.kcal, steps.outside, kg, heightCm) else EnergyModel.neatFromLevel(bmr.kcal, level)
            val w = workoutsByDay[d] ?: 0
            val burn = if (d == today && todayBurn != null) todayBurn else EnergyModel.dayBurn(bmr.kcal, neat, w.toFloat(), intake.toFloat())
            out.add(EnergyDay(d, burn, intake, w, estimated))
        }
        return out
    }

    /** [days] summed over the finished days that have an intake (logged, or counted as a usual day). */
    fun totalsOf(days: List<EnergyDay>, today: Long): EnergyTotals {
        val counted = days.filter { it.day < today && it.intake > 0 }
        return EnergyTotals(counted.size, counted.count { it.estimated }, counted.sumOf { it.burn }, counted.sumOf { it.intake })
    }

    /**
     * When the calorie goal is automatic, writes the model's target into [Profile.kcalGoal], so every reader
     * (food screen, calorie hunt, day close) sees the same number. Manual goals are never touched.
     * Returns the snapshot it computed (or null when nothing was computed).
     */
    suspend fun syncAutoGoal(): EnergySnapshot? {
        val p = AppGraph.prefs.current()
        if (!p.kcalAuto) return null
        val snap = snapshot()
        val k = snap.target.kcal.coerceIn(800, 6000)
        if (abs(k - p.kcalGoal) >= 10) {
            AppGraph.prefs.update { cur -> if (cur.kcalAuto) cur.copy(kcalGoal = k) else cur }
            com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext)
        }
        return snap
    }

    /** Last time [syncAutoGoalSoon] actually ran (process memory only). */
    @Volatile private var lastAutoSyncAt = 0L
    private const val AUTO_SYNC_GAP_MS = 60_000L

    /**
     * App open / resume (MainActivity.onResume): refresh the automatic goal from the latest weigh-in, workouts and
     * steps, in the background, at most once a minute. Every failure is swallowed.
     */
    fun syncAutoGoalSoon() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt in 0 until AUTO_SYNC_GAP_MS) return
        lastAutoSyncAt = now
        AppGraph.scope.launch { runCatching { syncAutoGoal() } }
    }
}
