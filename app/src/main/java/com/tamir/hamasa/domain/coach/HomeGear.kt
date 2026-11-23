package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.ExerciseEntity
import kotlin.math.roundToInt

/**
 * What he asked for on the "אימון בבית" sheet: the gear he has, the heaviest dumbbell / the kettlebell, the time
 * and the focus. Built by the sheet (ui/coach/HomeWorkoutSheet) or from Prefs ([HomeGear.requestOf]).
 */
data class HomeRequest(
    /** [HomeGear] keys; body weight is always added. */
    val gear: Set<String>,
    /** Heaviest dumbbell per hand; 0 = not told (no cap). */
    val dumbbellMaxKg: Float = 0f,
    /** true = one fixed pair (every dumbbell exercise uses exactly [dumbbellMaxKg]). */
    val dumbbellFixed: Boolean = false,
    /** Kettlebell weight; 0 = not told. */
    val kettlebellKg: Float = 0f,
    /** Session length cap in minutes; null = none. */
    val maxMinutes: Int? = null,
    /** [HomeGear.FOCUS_FULL] | [HomeGear.FOCUS_UPPER] | [HomeGear.FOCUS_LOWER] | [HomeGear.FOCUS_CORE]. */
    val focus: String = HomeGear.FOCUS_FULL
)

/**
 * The home equipment model. One set of keys for the sheet chips, the note to the coach ("אני בבית עם גומיות")
 * and the engine's filter ([allows]). An exercise is possible at home when its catalog equipment is there
 * (body weight always is) and every extra piece it needs ([needs]: a pull-up bar for "מתח", a bench or a stable
 * chair for "מקבילים על ספסל") is there too. Gym-only exercises (machines, cables, barbells) never are.
 */
object HomeGear {

    const val BODY = "body"
    const val BAND = "band"
    const val DUMBBELL = "dumbbell"
    const val KETTLEBELL = "kettlebell"
    const val PULLUP_BAR = "pullup_bar"
    const val BENCH = "bench"
    const val MAT = "mat"
    const val BALL = "ball"
    const val ROPE = "rope"
    const val TRX = "trx"
    const val PUSHUP_BAR = "pushup_bar"

    const val FOCUS_FULL = "FULL"
    const val FOCUS_UPPER = "UPPER"
    const val FOCUS_LOWER = "LOWER"
    const val FOCUS_CORE = "CORE"

    /** Rep range when the target weight is heavier than what he has at home. */
    const val CAP_REPS_MIN = 8
    const val CAP_REPS_MAX = 12

    /** Sheet chips in display order (body weight first, always on). */
    val chips: List<Pair<String, String>> = listOf(
        BODY to "משקל גוף",
        BAND to "גומיות התנגדות",
        DUMBBELL to "משקולות יד",
        KETTLEBELL to "קטלבל",
        PULLUP_BAR to "מוט מתח",
        BENCH to "ספסל או כיסא יציב",
        MAT to "מזרן",
        BALL to "כדור פיזיו",
        ROPE to "חבל קפיצה",
        TRX to "רצועות תלייה",
        PUSHUP_BAR to "בר לשכיבות סמיכה"
    )

    val focusOptions: List<Pair<String, String>> = listOf(
        FOCUS_FULL to "כל הגוף",
        FOCUS_UPPER to "עליון",
        FOCUS_LOWER to "תחתון",
        FOCUS_CORE to "בטן ויציבות"
    )

    val minuteOptions: List<Int> = listOf(20, 30, 45, 60)

    private val keys: Set<String> = chips.map { it.first }.toSet()

    fun labelHe(key: String): String = chips.firstOrNull { it.first == key }?.second ?: ""

    fun focusLabelHe(key: String): String = focusOptions.firstOrNull { it.first == key }?.second ?: "כל הגוף"

    fun parseCsv(csv: String): Set<String> =
        csv.split(',').map { it.trim() }.filter { it in keys }.toSet() + BODY

    fun toCsv(gear: Set<String>): String = chips.map { it.first }.filter { it in gear && it != BODY }.joinToString(",")

    /** Catalog equipment values that need a piece of home gear of the same name. */
    private val equipmentGear: Set<String> = setOf(BAND, DUMBBELL, KETTLEBELL, TRX)

    /** Extra gear an exercise needs beyond its equipment field (all of them). */
    private val needs: Map<String, Set<String>> = mapOf(
        "pullup" to setOf(PULLUP_BAR),
        "chin_up" to setOf(PULLUP_BAR),
        "hanging_leg_raise" to setOf(PULLUP_BAR),
        "handles_pushup" to setOf(PUSHUP_BAR),
        "db_bench_press" to setOf(BENCH),
        "incline_db_press" to setOf(BENCH),
        "db_fly" to setOf(BENCH),
        "bulgarian_split_squat" to setOf(BENCH),
        "bw_bulgarian_split_squat" to setOf(BENCH),
        "step_up" to setOf(BENCH),
        "bench_dips" to setOf(BENCH),
        "dips" to setOf(BENCH),
        "ball_hamstring_curl" to setOf(BALL),
        "ball_crunch" to setOf(BALL),
        "ball_back_extension" to setOf(BALL),
        "jump_rope" to setOf(ROPE)
    )

    /** Body-weight exercises of the catalog that need gym gear nobody has at home (a low bar, a wheel, a back bench). */
    private val gymOnly: Set<String> = setOf("inverted_row", "ab_wheel", "back_extension", "run_intervals", "yoga_session")

    /** True when [e] can be done with [gear] at home. */
    fun allows(e: ExerciseEntity, gear: Set<String>): Boolean {
        if (e.id in gymOnly) return false
        val base = when (e.equipment) {
            BODY -> true
            in equipmentGear -> e.equipment in gear
            else -> false
        }
        if (!base) return false
        val extra = needs[e.id] ?: return true
        return gear.containsAll(extra)
    }

    /** The catalog equipment values that [gear] covers (body weight always). */
    fun equipmentOf(gear: Set<String>): Set<String> = gear.filter { it in equipmentGear }.toSet() + BODY

    /** Equipment keys of a note ("גומייה", "משקולות יד") -> home gear. */
    fun fromEquipment(eq: Set<String>): Set<String> = eq.filter { it == BAND || it == DUMBBELL }.toSet() + BODY

    /**
     * A note to the coach that means "at home": place home, or only bands / body weight with no place named.
     * Such a note is mapped onto this model, so "אני בבית עם גומיות" never plans pull-ups without a bar.
     */
    fun noteMeansHome(c: CoachConstraints): Boolean =
        c.place == "home" ||
            (c.place.isEmpty() && c.onlyEquipment.isNotEmpty() && c.onlyEquipment.all { it == BODY || it == BAND })

    /**
     * The note path: the gear the note names (bands, dumbbells), else what he saved on the sheet, else bands.
     * The note's own fields (time, focus, pain limits) stay as they are.
     */
    fun fromNote(c: CoachConstraints, saved: HomeRequest): CoachConstraints {
        val savedGear = saved.gear - BODY
        val gear = when {
            c.onlyEquipment.isNotEmpty() -> fromEquipment(c.onlyEquipment)
            savedGear.isNotEmpty() -> savedGear + BODY
            else -> setOf(BAND, BODY)
        }
        return c.copy(
            homeGear = gear, dumbbellMaxKg = saved.dumbbellMaxKg, dumbbellFixed = saved.dumbbellFixed,
            kettlebellKg = saved.kettlebellKg
        )
    }

    /**
     * The sheet path: today's constraints become "at home with [r]". Pain / fatigue limits of an active note stay
     * (safety first); its equipment, place, focus and type preferences are replaced by the sheet. A note's time cap
     * stays when the sheet sets none.
     */
    fun applyTo(c: CoachConstraints?, r: HomeRequest): CoachConstraints {
        val base = c ?: CoachConstraints()
        return base.copy(
            homeGear = r.gear + BODY,
            onlyEquipment = emptySet(),
            place = "home",
            focusBranches = emptySet(),
            sessionType = "",
            maxMinutes = r.maxMinutes ?: base.maxMinutes,
            dumbbellMaxKg = r.dumbbellMaxKg.coerceAtLeast(0f),
            dumbbellFixed = r.dumbbellFixed && r.dumbbellMaxKg > 0f,
            kettlebellKg = r.kettlebellKg.coerceAtLeast(0f)
        )
    }

    /** Saved sheet choices from the profile fields. */
    fun requestOf(gearCsv: String, dumbbellMaxKg: Float, dumbbellFixed: Boolean, kettlebellKg: Float, minutes: Int, focus: String): HomeRequest =
        HomeRequest(
            gear = parseCsv(gearCsv),
            dumbbellMaxKg = dumbbellMaxKg.coerceAtLeast(0f),
            dumbbellFixed = dumbbellFixed,
            kettlebellKg = kettlebellKg.coerceAtLeast(0f),
            maxMinutes = if (minutes > 0) minutes else null,
            focus = if (focusOptions.any { it.first == focus }) focus else FOCUS_FULL
        )

    /** "עד 12.5" style number for the kg field; "" for 0. */
    fun kgText(v: Float): String = when {
        v <= 0f -> ""
        v == v.roundToInt().toFloat() -> v.roundToInt().toString()
        else -> String.format("%.1f", v)
    }

    /** Parses the kg field ("12", "12.5", "12,5"); 0 for empty or bad input. Kept in 0..100. */
    fun parseKg(text: String): Float =
        text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.coerceAtMost(100f) ?: 0f

    // ------------------------------------------------------------ coach notes for the weight cap

    private fun kg(v: Float): String = kgText(v) + " ק\"ג"

    /** Target heavier than his dumbbells / kettlebell: more reps first, then tempo and a harder version. */
    fun capNote(equipment: String, limit: Float, reps: Int, repsMax: Int, atTop: Boolean, harderHe: String?): String {
        val what = if (equipment == KETTLEBELL) "הקטלבל שלך ${kg(limit)}" else "המשקולות שלך עד ${kg(limit)}"
        if (!atTop) return "$what. במקום להעלות משקל: $reps חזרות, עד $repsMax"
        val harder = if (harderHe != null) ". השלב הבא: $harderHe" else ""
        return "$what וכבר הגעת ל-$repsMax חזרות. ירידה איטית של שלוש שניות בכל חזרה$harder"
    }

    /** A fixed weight heavier than the target: the low end of the range, clean reps. */
    fun heavyFixedNote(equipment: String, limit: Float, reps: Int): String {
        val what = if (equipment == KETTLEBELL) "הקטלבל" else "זוג המשקולות"
        return "$what ${kg(limit)}, כבד מהיעד. $reps חזרות נקיות, לא יותר"
    }
}
