package com.tamir.hamasa.domain.energy

import com.tamir.hamasa.domain.food.MacroTargets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Energy model ("מחשבון אנרגיה"). Pure functions, no Android, no database.
 * Every formula, coefficient and source is documented in docs/energy_model.md.
 *
 * Units: kg, cm, years, minutes, kcal (per day unless the name says otherwise), beats per minute.
 *
 * Daily burn = BMR + NEAT (non-exercise activity) + EAT (workouts, NET of resting) + TEF (10% of intake).
 *
 * STAGE 3 HOOKS (Galaxy Watch): [neatWithSteps] replaces the activity-level NEAT when a day has a step count,
 * and [workoutKcal] switches to the Keytel heart-rate equation when an average heart rate is given for a
 * cardio-like session. Both take plain numbers so the watch layer only has to supply steps / avg HR.
 */

enum class Sex { MALE, FEMALE }

/** Non-exercise daily life. [neatFactor] = NEAT as a share of BMR (see docs: PAL without exercise / 0.9 TEF). */
enum class ActivityLevel(val neatFactor: Float, val nameHe: String, val descHe: String) {
    SEDENTARY(0.25f, "יושבני", "רוב היום בישיבה. מסך, רכב, ספה."),
    LIGHT(0.40f, "קל", "קצת הליכה כל יום. סידורים, עמידה פה ושם."),
    MODERATE(0.55f, "בינוני", "הרבה על הרגליים. עבודה בעמידה או הליכות ארוכות."),
    ACTIVE(0.70f, "פעיל", "עבודה פיזית, או הליכה רבה כל יום.");

    companion object {
        fun fromIndex(i: Int): ActivityLevel = values().getOrElse(i) { SEDENTARY }
    }
}

/** Kind of session for the burn estimate. */
enum class WorkoutKind { STRENGTH, YOGA, WALK, RUN }

enum class Intensity { LIGHT, MODERATE, VIGOROUS }

/** DISTANCE = his own rule for walks and runs (weight × km); DISTANCE_EST = the same with the km estimated from the time. */
enum class BurnMethod { MET, HEART_RATE, DISTANCE, DISTANCE_EST }

data class WorkoutBurn(val kcal: Int, val method: BurnMethod, val met: Float, val minutes: Float, val km: Float = 0f)

enum class BmrMethod { MIFFLIN, KATCH }

data class Bmr(val kcal: Float, val method: BmrMethod)

/** Model TDEE parts (per day). */
data class TdeeBreakdown(val bmr: Int, val neat: Int, val workouts: Int, val tef: Int) {
    val total: Int get() = bmr + neat + workouts + tef
}

enum class Confidence { NONE, LOW, GOOD }

/**
 * Real-data TDEE. [tdee] is meaningful only when [confidence] != NONE.
 * [slopeKgPerWeek] is the weight trend (negative = losing).
 */
data class AdaptiveTdee(
    val tdee: Int,
    val confidence: Confidence,
    val spanDays: Int,
    val loggedDays: Int,
    val weighIns: Int,
    val meanIntake: Int,
    val slopeKgPerWeek: Float
)

/** Where the weight should go: deficit, maintenance (TDEE) or a lean-bulk surplus. */
enum class GoalDirection(val nameHe: String) {
    CUT("ירידה"),
    MAINTAIN("שמירה"),
    GAIN("עלייה");

    companion object {
        /** Prefs.goalDirection: 0 cut, 1 maintain, 2 gain; anything else = null (automatic). */
        fun fromIndex(i: Int): GoalDirection? = when (i) {
            0 -> CUT
            1 -> MAINTAIN
            2 -> GAIN
            else -> null
        }
    }
}

data class IntakeTarget(
    val kcal: Int,
    /** Planned daily deficit after the floors (tdee - kcal). 0 for maintenance / gain. */
    val deficit: Int,
    /** Expected loss per week from that deficit, kg (first weeks are a bit faster: water and glycogen). */
    val weeklyLossKg: Float,
    /** True when a floor (minimum intake or the 1000 kcal cap) or the surplus cap changed the number. */
    val limited: Boolean,
    val direction: GoalDirection = GoalDirection.CUT,
    /** Planned daily surplus (kcal - tdee) for [GoalDirection.GAIN]; 0 otherwise. */
    val surplus: Int = 0,
    /** Expected gain per week from that surplus, kg (planning number; part of it is muscle, part fat). */
    val weeklyGainKg: Float = 0f
)

object EnergyModel {

    // ---------------------------------------------------------------- constants (sources in docs/energy_model.md)

    /** Energy density of body-weight change used for planning and for the adaptive estimate (≈ 3500 kcal/lb). */
    const val KCAL_PER_KG = 7700f
    /** Thermic effect of food: share of intake. */
    const val TEF_SHARE = 0.10f
    /** Largest planned deficit per day. */
    const val MAX_DEFICIT = 1000
    /** Minimum intake for weight loss (AHA/ACC/TOS 2013 lower ends). */
    const val FLOOR_MALE = 1500
    const val FLOOR_FEMALE = 1200
    /** A logged day counts for the adaptive estimate only from this many kcal (partial logs are skipped). */
    const val MIN_LOGGED_DAY_KCAL = 800
    /** Allowed goal rates, % of body weight per week. */
    val GOAL_RATES_PCT = listOf(0.25f, 0.5f, 0.75f, 1.0f)
    /** Lean-bulk rates, % of body weight per week (Iraki et al. 2019: ~0.25-0.5% for novice / intermediate). */
    val GAIN_RATES_PCT = listOf(0.25f, 0.5f)
    /** Planned surplus range per day for a lean bulk. */
    const val MIN_SURPLUS = 150
    const val MAX_SURPLUS = 500

    // ---------------------------------------------------------------- BMR

    /** Mifflin-St Jeor (1990): 10·kg + 6.25·cm − 5·age + 5 (men) / − 161 (women). */
    fun bmrMifflin(weightKg: Float, heightCm: Float, age: Int, sex: Sex): Float {
        val base = 10f * weightKg + 6.25f * heightCm - 5f * age
        return base + if (sex == Sex.MALE) 5f else -161f
    }

    /** Katch-McArdle: 370 + 21.6·lean mass (kg). */
    fun bmrKatch(leanMassKg: Float): Float = 370f + 21.6f * leanMassKg

    /** Lean mass from body-fat %, or null when the % is unknown / implausible. */
    fun leanMass(weightKg: Float, bodyFatPct: Float): Float? =
        if (bodyFatPct in 3f..60f) weightKg * (1f - bodyFatPct / 100f) else null

    /** Katch-McArdle when body fat is known, else Mifflin-St Jeor. */
    fun bmr(weightKg: Float, heightCm: Float, age: Int, sex: Sex, bodyFatPct: Float): Bmr {
        val lean = leanMass(weightKg, bodyFatPct)
        return if (lean != null) Bmr(bmrKatch(lean), BmrMethod.KATCH)
        else Bmr(bmrMifflin(weightKg, heightCm, age, sex), BmrMethod.MIFFLIN)
    }

    // ---------------------------------------------------------------- daily non-exercise activity

    /** NEAT from the self-reported activity level (a share of BMR). */
    fun neatFromLevel(bmr: Float, level: ActivityLevel): Float = bmr * level.neatFactor

    /**
     * STAGE 3 HOOK. Net kcal of walking [steps]: steps × step length × 0.0005 kcal/kg/m × kg.
     * 0.0005 = ACSM walking equation horizontal term (0.1 mL O2/kg/m) × 5 kcal per litre O2.
     * Step length ≈ 0.414 × height (rough; 0.75 m when height is unknown).
     */
    fun neatFromSteps(steps: Int, weightKg: Float, heightCm: Float = 0f): Float {
        if (steps <= 0) return 0f
        val stepM = if (heightCm in 120f..230f) 0.414f * heightCm / 100f else 0.75f
        return steps * stepM * 0.0005f * weightKg
    }

    /** Non-walking part of NEAT (standing, fidgeting, chores) when steps are known. An assumption, see docs. */
    const val NON_WALK_NEAT_SHARE = 0.10f

    /**
     * STAGE 3 HOOK. NEAT for a day with a step count from the watch: walking steps + a small non-walking base.
     * Steps that belong to a logged WALK/RUN session should be removed by the caller (they are already in EAT).
     */
    fun neatWithSteps(bmr: Float, steps: Int, weightKg: Float, heightCm: Float): Float =
        bmr * NON_WALK_NEAT_SHARE + neatFromSteps(steps, weightKg, heightCm)

    /** Thermic effect of food: 10% of what was eaten. */
    fun tef(intakeKcal: Float): Float = max(0f, intakeKcal) * TEF_SHARE

    // ---------------------------------------------------------------- workouts

    /** 2024 Adult Compendium of Physical Activities. Codes in the comments. */
    fun metFor(kind: WorkoutKind, intensity: Intensity): Float = when (kind) {
        WorkoutKind.STRENGTH -> when (intensity) {
            Intensity.LIGHT -> 3.5f      // 02054 resistance training, multiple exercises, 8-15 reps
            Intensity.MODERATE -> 5.0f   // 02052 squats / deadlifts; 02035 circuit, moderate
            Intensity.VIGOROUS -> 6.0f   // 02050 free weights / machines, vigorous
        }
        WorkoutKind.YOGA -> when (intensity) {
            Intensity.LIGHT -> 2.3f      // 02150 hatha
            Intensity.MODERATE -> 2.7f   // 02185 vinyasa
            Intensity.VIGOROUS -> 4.0f   // 02160 power yoga
        }
        WorkoutKind.WALK -> when (intensity) {
            Intensity.LIGHT -> 2.8f      // 17152 2.0-2.4 mph
            Intensity.MODERATE -> 3.8f   // 17190 2.8-3.4 mph
            Intensity.VIGOROUS -> 4.8f   // 17200 3.5-3.9 mph, brisk
        }
        WorkoutKind.RUN -> when (intensity) {
            Intensity.LIGHT -> 6.5f      // 12028 4.0-4.2 mph
            Intensity.MODERATE -> 7.5f   // 12020 jogging, self-selected pace
            Intensity.VIGOROUS -> 9.3f   // 12050 6.0-6.3 mph
        }
    }

    /** Strength intensity from training density (completed sets per minute, rest included). */
    fun strengthIntensity(completedSets: Int, minutes: Float): Intensity {
        if (minutes <= 0f || completedSets <= 0) return Intensity.LIGHT
        val perMin = completedSets / minutes
        return when {
            perMin >= 0.55f -> Intensity.VIGOROUS  // circuit-like: almost no rest
            perMin >= 0.40f -> Intensity.MODERATE
            else -> Intensity.LIGHT                // classic sets with 2-3 min rest
        }
    }

    /**
     * NET kcal of a MET activity: (MET − 1) × 3.5 × kg / 200 per minute.
     * One MET is subtracted because BMR is already counted for the whole day.
     */
    /** His rule (2026-11-05): a run burns weight × km; a walk weight × km × [WALK_SHARE]. */
    const val WALK_SHARE = 0.6f
    /** Typical speeds used only when no distance was measured: the km are estimated from the minutes. */
    const val WALK_KMH = 5f
    const val RUN_KMH = 9f

    fun distanceKcal(kind: WorkoutKind, km: Float, weightKg: Float): Float = when (kind) {
        WorkoutKind.RUN -> max(0f, km) * weightKg
        WorkoutKind.WALK -> max(0f, km) * weightKg * WALK_SHARE
        else -> 0f
    }

    /** Km a walk / run of [minutes] probably covered, when nothing measured it. */
    fun estimatedKm(kind: WorkoutKind, minutes: Float): Float =
        max(0f, minutes) / 60f * (if (kind == WorkoutKind.RUN) RUN_KMH else WALK_KMH)

    fun metNetKcal(met: Float, minutes: Float, weightKg: Float): Float =
        max(0f, met - 1f) * 3.5f * weightKg / 200f * max(0f, minutes)

    /**
     * Keytel et al. 2005, GROSS kcal per minute from heart rate (kJ/min ÷ 4.184).
     * Without VO2max: men −55.0969 + 0.6309·HR + 0.1988·kg + 0.2017·age;
     *                 women −20.4022 + 0.4472·HR − 0.1263·kg + 0.074·age.
     * With VO2max (mL/kg/min): men −95.7735 + 0.634·HR + 0.404·VO2max + 0.394·kg + 0.271·age;
     *                          women −59.3954 + 0.45·HR + 0.380·VO2max + 0.103·kg + 0.274·age.
     */
    fun keytelKcalPerMin(avgHr: Float, weightKg: Float, age: Int, sex: Sex, vo2max: Float? = null): Float {
        val kj = if (vo2max == null || vo2max <= 0f) {
            if (sex == Sex.MALE) -55.0969f + 0.6309f * avgHr + 0.1988f * weightKg + 0.2017f * age
            else -20.4022f + 0.4472f * avgHr - 0.1263f * weightKg + 0.074f * age
        } else {
            if (sex == Sex.MALE) -95.7735f + 0.634f * avgHr + 0.404f * vo2max + 0.394f * weightKg + 0.271f * age
            else -59.3954f + 0.45f * avgHr + 0.380f * vo2max + 0.103f * weightKg + 0.274f * age
        }
        return max(0f, kj / 4.184f)
    }

    /** Heart rate counts only for cardio-like sessions and inside the range the equation was built on. */
    fun hrUsable(kind: WorkoutKind, avgHr: Int?): Boolean =
        avgHr != null && avgHr in 90..200 && (kind == WorkoutKind.WALK || kind == WorkoutKind.RUN)

    /**
     * NET kcal of one session. Heart rate (Keytel) for walk/run when [avgHr] is given (STAGE 3 HOOK);
     * otherwise the Compendium MET. Strength never uses heart rate (HR overestimates lifting).
     * [bmrPerDay] is used to remove the resting part from the gross heart-rate value.
     */
    fun workoutKcal(
        kind: WorkoutKind,
        minutes: Float,
        weightKg: Float,
        intensity: Intensity,
        avgHr: Int? = null,
        age: Int,
        sex: Sex,
        bmrPerDay: Float,
        vo2max: Float? = null,
        distanceM: Int? = null
    ): WorkoutBurn {
        val m = minutes.coerceIn(0f, 300f)
        val met = metFor(kind, intensity)
        if (kind == WorkoutKind.RUN || kind == WorkoutKind.WALK) {
            // walks and runs: his own rule, never the watch's calorie figure and not the heart rate
            val km = distanceM?.takeIf { it >= 100 }?.let { it / 1000f }
            return if (km != null) WorkoutBurn(distanceKcal(kind, km, weightKg).roundToInt(), BurnMethod.DISTANCE, met, m, km)
            else estimatedKm(kind, m).let { WorkoutBurn(distanceKcal(kind, it, weightKg).roundToInt(), BurnMethod.DISTANCE_EST, met, m, it) }
        }
        if (hrUsable(kind, avgHr)) {
            val gross = keytelKcalPerMin(avgHr!!.toFloat(), weightKg, age, sex, vo2max) * m
            val rest = bmrPerDay / 1440f * m
            return WorkoutBurn(max(0f, gross - rest).roundToInt(), BurnMethod.HEART_RATE, met, m)
        }
        return WorkoutBurn(metNetKcal(met, m, weightKg).roundToInt(), BurnMethod.MET, met, m)
    }

    // ---------------------------------------------------------------- TDEE

    /**
     * Model TDEE at maintenance: (BMR + NEAT + EAT) / (1 − 0.10), so TEF is 10% of the intake that keeps
     * the weight stable. [workoutsPerDay] = average net workout kcal per day (last 14 days).
     * [neatOverride] replaces the activity-level NEAT (stage 3: [neatWithSteps]).
     */
    fun tdeeEstimate(bmr: Float, level: ActivityLevel, workoutsPerDay: Float, neatOverride: Float? = null): TdeeBreakdown {
        val neat = neatOverride ?: neatFromLevel(bmr, level)
        val noTef = bmr + neat + max(0f, workoutsPerDay)
        val total = noTef / (1f - TEF_SHARE)
        return TdeeBreakdown(
            bmr = bmr.roundToInt(),
            neat = neat.roundToInt(),
            workouts = max(0f, workoutsPerDay).roundToInt(),
            tef = (total - noTef).roundToInt()
        )
    }

    /** Burn of one actual day: TEF from what was really eaten that day. */
    fun dayBurn(bmr: Float, neat: Float, workouts: Float, intake: Float): Int =
        (bmr + neat + max(0f, workouts) + tef(intake)).roundToInt()

    /**
     * Real TDEE from logged intake and the weight trend (energy balance): mean intake − slope × 7700.
     * Window: the [windowDays] days that end yesterday (today's log is still open), starting at the first
     * logged day inside the window. Slope = least-squares line through the weigh-ins (filters water noise).
     * GOOD: ≥ 21 days, ≥ 80% of them logged, ≥ 8 weigh-ins. LOW: ≥ 14 days, ≥ 10 logged, ≥ 4 weigh-ins over ≥ 10 days.
     * [weights] = (epochDay, kg); [intakes] = epochDay -> kcal of that day.
     */
    fun adaptiveTdee(weights: List<Pair<Long, Float>>, intakes: Map<Long, Int>, today: Long, windowDays: Int = 28): AdaptiveTdee {
        val end = today - 1
        val start = end - windowDays + 1
        val logged = intakes.filter { (d, k) -> d in start..end && k >= MIN_LOGGED_DAY_KCAL }
        val none = AdaptiveTdee(0, Confidence.NONE, 0, logged.size, 0, 0, 0f)
        if (logged.isEmpty()) return none
        val effStart = logged.keys.minOrNull() ?: return none
        val span = (end - effStart + 1).toInt()
        val inWindow = weights.filter { it.first in effStart..end }
        val meanIntake = logged.values.average().toFloat()
        val slopePerDay = slope(inWindow)
        val base = AdaptiveTdee(0, Confidence.NONE, span, logged.size, inWindow.size, meanIntake.roundToInt(), (slopePerDay ?: 0f) * 7f)
        if (slopePerDay == null) return base
        val weighSpan = (inWindow.maxOf { it.first } - inWindow.minOf { it.first }).toInt()
        val tdee = (meanIntake - slopePerDay * KCAL_PER_KG).roundToInt()
        if (tdee < 1200 || tdee > 5500) return base   // inconsistent data: do not show a number
        val conf = when {
            span >= 21 && logged.size >= (span * 0.8f) && inWindow.size >= 8 && weighSpan >= 14 -> Confidence.GOOD
            span >= 14 && logged.size >= 10 && inWindow.size >= 4 && weighSpan >= 10 -> Confidence.LOW
            else -> Confidence.NONE
        }
        return base.copy(tdee = tdee, confidence = conf)
    }

    /** Least-squares slope kg/day, or null with fewer than 2 weigh-ins / one day only. */
    fun slope(points: List<Pair<Long, Float>>): Float? {
        if (points.size < 2) return null
        val x0 = points.minOf { it.first }
        val xs = points.map { (it.first - x0).toDouble() }
        val ys = points.map { it.second.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        if (den <= 0.0) return null
        return (num / den).toFloat()
    }

    // ---------------------------------------------------------------- targets

    /** Planned deficit for a rate of [ratePctPerWeek] % body weight per week: rate × kg × 7700 / 7. */
    fun deficitForRate(weightKg: Float, ratePctPerWeek: Float): Float =
        ratePctPerWeek / 100f * weightKg * KCAL_PER_KG / 7f

    /**
     * Daily intake for weight loss: TDEE − deficit, the deficit capped at 1000, never under
     * max(1500 men / 1200 women, BMR).
     */
    fun targetIntake(tdee: Float, weightKg: Float, ratePctPerWeek: Float, bmr: Float, sex: Sex): IntakeTarget {
        val wanted = deficitForRate(weightKg, ratePctPerWeek)
        var limited = false
        var deficit = wanted
        if (deficit > MAX_DEFICIT) { deficit = MAX_DEFICIT.toFloat(); limited = true }
        val floor = max(if (sex == Sex.MALE) FLOOR_MALE.toFloat() else FLOOR_FEMALE.toFloat(), bmr)
        var kcal = tdee - deficit
        if (kcal < floor) { kcal = floor; limited = true }
        val kcalRounded = (kcal / 10f).roundToInt() * 10
        val realDeficit = max(0f, tdee - kcalRounded)
        return IntakeTarget(
            kcal = kcalRounded,
            deficit = realDeficit.roundToInt(),
            weeklyLossKg = realDeficit * 7f / KCAL_PER_KG,
            limited = limited && abs(wanted - realDeficit) > 15f
        )
    }

    /**
     * Planned surplus for a lean bulk of [ratePctPerWeek] % body weight per week: rate × kg × 7700 / 7,
     * kept inside [MIN_SURPLUS]..[MAX_SURPLUS] (≈ +250-500 kcal; Iraki 2019: hyper-energetic by ~10-20%).
     */
    fun surplusForRate(weightKg: Float, ratePctPerWeek: Float): Float =
        (ratePctPerWeek / 100f * weightKg * KCAL_PER_KG / 7f).coerceIn(MIN_SURPLUS.toFloat(), MAX_SURPLUS.toFloat())

    /** Maintenance: eat the TDEE (never under the same floors as a cut). */
    fun maintenanceIntake(tdee: Float, bmr: Float, sex: Sex): IntakeTarget {
        val floor = max(if (sex == Sex.MALE) FLOOR_MALE.toFloat() else FLOOR_FEMALE.toFloat(), bmr)
        val kcal = max(tdee, floor)
        return IntakeTarget(
            kcal = (kcal / 10f).roundToInt() * 10, deficit = 0, weeklyLossKg = 0f,
            limited = kcal > tdee + 15f, direction = GoalDirection.MAINTAIN
        )
    }

    /** Lean bulk: TDEE + surplus (see [surplusForRate]). */
    fun gainIntake(tdee: Float, weightKg: Float, ratePctPerWeek: Float): IntakeTarget {
        val raw = ratePctPerWeek / 100f * weightKg * KCAL_PER_KG / 7f
        val surplus = surplusForRate(weightKg, ratePctPerWeek)
        val kcalRounded = ((tdee + surplus) / 10f).roundToInt() * 10
        val real = max(0f, kcalRounded - tdee)
        return IntakeTarget(
            kcal = kcalRounded, deficit = 0, weeklyLossKg = 0f,
            limited = abs(raw - surplus) > 15f, direction = GoalDirection.GAIN,
            surplus = real.roundToInt(), weeklyGainKg = real * 7f / KCAL_PER_KG
        )
    }

    /** The daily target for [direction]: [targetIntake] (cut), [maintenanceIntake] or [gainIntake]. */
    fun intakeFor(
        direction: GoalDirection, tdee: Float, weightKg: Float, cutRatePct: Float, gainRatePct: Float, bmr: Float, sex: Sex
    ): IntakeTarget = when (direction) {
        GoalDirection.CUT -> targetIntake(tdee, weightKg, cutRatePct, bmr, sex)
        GoalDirection.MAINTAIN -> maintenanceIntake(tdee, bmr, sex)
        GoalDirection.GAIN -> gainIntake(tdee, weightKg, gainRatePct)
    }

    /** Body weight at BMI 27 for this height, used as the protein reference weight in higher body fat. */
    fun referenceWeight(weightKg: Float, heightCm: Float): Float =
        if (heightCm in 120f..230f) min(weightKg, 27f * (heightCm / 100f) * (heightCm / 100f)) else weightKg

    /** Protein g/kg of the reference weight per direction (deficit at the high end, surplus lower). */
    fun proteinPerKg(direction: GoalDirection): Float = when (direction) {
        GoalDirection.CUT -> 2.0f
        GoalDirection.MAINTAIN -> 1.8f
        GoalDirection.GAIN -> 1.7f
    }

    /** Protein g/kg of LEAN mass per direction (used when body fat is known). */
    fun proteinPerLeanKg(direction: GoalDirection): Float = when (direction) {
        GoalDirection.CUT -> 2.4f
        GoalDirection.MAINTAIN -> 2.2f
        GoalDirection.GAIN -> 2.0f
    }

    /** Fat share of kcal per direction: 25% in a cut and a bulk (room for protein / training carbs), 30% at maintenance. */
    fun fatShare(direction: GoalDirection): Float = when (direction) {
        GoalDirection.CUT -> 0.25f
        GoalDirection.MAINTAIN -> 0.30f
        GoalDirection.GAIN -> 0.25f
    }

    /**
     * Macro goals. A value > 0 (set by the user) always wins.
     * Protein by [direction]: cut 2.0 / maintain 1.8 / gain 1.7 g/kg of the reference weight, or 2.4 / 2.2 / 2.0 g/kg
     * lean mass when body fat is known (ISSN 1.4-2.0 g/kg; deficit + resistance training 1.6-2.4 g/kg;
     * Iraki 2019 off-season 1.6-2.2 g/kg). Never more than 40% of kcal.
     * Fat: 25% (cut, gain) / 30% (maintain) of kcal, at least 0.5 g/kg reference weight. Carbs: the rest.
     */
    fun macroTargets(
        kcal: Int,
        weightKg: Float,
        heightCm: Float = 0f,
        leanMassKg: Float? = null,
        proteinGoal: Int = 0,
        carbsGoal: Int = 0,
        fatGoal: Int = 0,
        direction: GoalDirection = GoalDirection.CUT
    ): MacroTargets {
        val k = kcal.coerceAtLeast(1)
        val ref = referenceWeight(weightKg.coerceAtLeast(30f), heightCm)
        val autoProtein = if (leanMassKg != null && leanMassKg > 20f) proteinPerLeanKg(direction) * leanMassKg else proteinPerKg(direction) * ref
        val protein = if (proteinGoal > 0) proteinGoal else min(autoProtein, k * 0.40f / 4f).roundToInt()
        val fat = if (fatGoal > 0) fatGoal else max(k * fatShare(direction) / 9f, 0.5f * ref).roundToInt()
        val carbs = if (carbsGoal > 0) carbsGoal else ((k - protein * 4 - fat * 9) / 4f).roundToInt().coerceAtLeast(0)
        return MacroTargets(k, protein, carbs, fat)
    }
}
