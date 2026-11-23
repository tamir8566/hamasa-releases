package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.db.Branch

/**
 * "כל בוס נפגע משילוב אחר": every weekly boss (and every seasonal event boss) has a WEAKNESS made of two
 * ingredients. A session is graded against it:
 *  - FULL    both ingredients present            -> every hit deals double
 *  - PARTIAL one of the two                      -> every hit deals half (carried, so 2 hits of 1 = 1)
 *  - NONE    neither                             -> the weekly boss takes no damage
 * Event bosses are softer (short holiday windows): NONE = half, PARTIAL = normal, FULL = double.
 *
 * Phases (BossPhases: strength / endurance / stability) still decide the damage of each hit and which stat counts;
 * the weakness multiplies each hit on top of that. Order: phase damage per hit -> weakness multiplier -> warrior +1.
 *
 * Ingredients marked "השבוע" are satisfied by any finished session of the same week (Sunday..Saturday) including
 * this one, so a combo can be built across sessions (yoga on Monday + gym on Wednesday: the Wednesday gym hits
 * double). Unknown sleep counts as a full night (the old convention: null = 8 h).
 *
 * The weekly rotation (BossCatalog.forWeek) walks the list in order, and no two neighbours (14 -> 0 included)
 * share an ingredient, so consecutive weeks always ask for something different.
 * Pure logic. Nothing here touches the coach's weights, reps or plan.
 */
enum class WeakPart(val labelHe: String) {
    LEGS("רגליים"),
    BACK("גב"),
    CHEST("חזה"),
    SHOULDERS("כתפיים"),
    CORE("בטן"),
    /** This session is strength work: a gym session, or 3+ completed strength sets (chest/back/legs/shoulders). */
    STRENGTH("אימון כוח"),
    /** Strength work this session or earlier this week. */
    STRENGTH_WEEK("אימון כוח השבוע"),
    /** Yoga this session or earlier this week. */
    YOGA_WEEK("יוגה השבוע"),
    /** A walk or run of 20 minutes or more. */
    CARDIO_20("הליכה או ריצה של 20 דקות"),
    /** Yoga, walk, run or mini: anything but the gym. */
    NON_GYM("אימון שאינו מכון"),
    /** This week already has a finished session of another type. */
    VARIETY("סוג אימון שני השבוע"),
    /** A finished session yesterday too. */
    CONSECUTIVE("יום אחרי יום"),
    /** The session has planned sets and all of them were completed. */
    ALL_SETS("כל הסטים הושלמו"),
    /** A record, or every set completed at its target reps. */
    PUSH("שיא או כל הסטים ביעד"),
    /** 7 hours of sleep or more before the session. */
    FULL_SLEEP("שינה מלאה"),
    /** Sunday or Monday. */
    EARLY_WEEK("ראשון או שני"),
    /** Monday to Wednesday. */
    MIDWEEK("שני עד רביעי"),
    /** "סגירת היום" the evening before or earlier the same day. */
    CLOSED_DAY("סגרת את היום"),
    /** A photographed meal on the session's day. */
    PHOTO_MEAL("ארוחה מצולמת באותו יום"),
    /** The protein goal was hit yesterday, or is already hit today. */
    PROTEIN("יעד חלבון");
}

enum class WeakGrade { NONE, PARTIAL, FULL }

/** Everything a weakness can ask about one session. Built by data/repo/BossFacts (or by hand in tests). */
data class WeakFacts(
    /** SessionType name: "GYM" | "YOGA" | "WALK" | "MINI" | "RUN". */
    val type: String,
    /** Branches with at least one completed set in this session. */
    val branches: Set<Branch> = emptySet(),
    /** Completed sets of chest / back / legs / shoulders-arms. */
    val strengthSets: Int = 0,
    val hasSets: Boolean = false,
    val allSetsDone: Boolean = false,
    val allOnTarget: Boolean = false,
    val record: Boolean = false,
    val minutes: Int = 0,
    val sleepHours: Float? = null,
    /** java.time DayOfWeek value: 1 = Monday .. 7 = Sunday. */
    val dayOfWeek: Int = 1,
    val closedDay: Boolean = false,
    val photoMeal: Boolean = false,
    val protein: Boolean = false,
    /** Types of OTHER finished sessions of the same week. */
    val otherTypesThisWeek: Set<String> = emptySet(),
    /** Another finished session of the same week was strength work. */
    val strengthEarlierThisWeek: Boolean = false,
    val sessionYesterday: Boolean = false
)

data class Weakness(val a: WeakPart, val b: WeakPart, /** Short line for bars and cards, e.g. "גב + רגליים באותו אימון". */ val labelHe: String)

object BossWeakness {

    private fun w(a: WeakPart, b: WeakPart, label: String) = Weakness(a, b, label)

    /** Weekly bosses, in BossCatalog order (the rotation order). Neighbours never share an ingredient. */
    private val weekly: Map<String, Weakness> = mapOf(
        "monster" to w(WeakPart.FULL_SLEEP, WeakPart.ALL_SETS, "שינה מלאה + כל הסטים הושלמו"),
        "midweek_king" to w(WeakPart.MIDWEEK, WeakPart.STRENGTH, "אימון כוח בין שני לרביעי"),
        "couch_siren" to w(WeakPart.CLOSED_DAY, WeakPart.CORE, "סגרת את היום + בטן באימון"),
        "thin_legs_giant" to w(WeakPart.BACK, WeakPart.LEGS, "גב + רגליים באותו אימון"),
        "sly_merchant" to w(WeakPart.PHOTO_MEAL, WeakPart.ALL_SETS, "ארוחה מצולמת באותו יום + כל הסטים הושלמו"),
        "snooze_dragon" to w(WeakPart.EARLY_WEEK, WeakPart.FULL_SLEEP, "אימון בראשון או שני + שינה מלאה"),
        "excuse_goblin" to w(WeakPart.NON_GYM, WeakPart.VARIETY, "אימון שאינו מכון + סוג אימון שני השבוע"),
        "sugar_witch" to w(WeakPart.PROTEIN, WeakPart.STRENGTH, "יעד חלבון + אימון כוח"),
        "mirror_knight" to w(WeakPart.CHEST, WeakPart.SHOULDERS, "חזה + כתפיים באותו אימון"),
        "rain_ogre" to w(WeakPart.CARDIO_20, WeakPart.STRENGTH_WEEK, "הליכה או ריצה של 20 דקות + אימון כוח השבוע"),
        "weight_golem" to w(WeakPart.LEGS, WeakPart.CHEST, "רגליים + חזה באותו אימון"),
        "plateau_sphinx" to w(WeakPart.PUSH, WeakPart.FULL_SLEEP, "שיא או כל הסטים ביעד + שינה מלאה"),
        "phone_siren" to w(WeakPart.YOGA_WEEK, WeakPart.STRENGTH_WEEK, "יוגה + אימון כוח באותו שבוע"),
        "stairs_guardian" to w(WeakPart.CORE, WeakPart.SHOULDERS, "בטן + כתפיים באותו אימון"),
        "old_self" to w(WeakPart.CONSECUTIVE, WeakPart.VARIETY, "יום אחרי יום + סוג אימון שני השבוע")
    )

    /** Seasonal event bosses (SeasonalEvents ids). */
    private val events: Map<String, Weakness> = mapOf(
        "hanukkah" to w(WeakPart.CONSECUTIVE, WeakPart.ALL_SETS, "יום אחרי יום, כמו נרות + כל הסטים הושלמו"),
        "purim" to w(WeakPart.VARIETY, WeakPart.CORE, "סוג אימון שני השבוע + בטן"),
        "pesach" to w(WeakPart.CARDIO_20, WeakPart.CONSECUTIVE, "הליכה או ריצה של 20 דקות + יום אחרי יום"),
        "independence" to w(WeakPart.LEGS, WeakPart.PROTEIN, "רגליים + יעד חלבון"),
        "tishrei" to w(WeakPart.YOGA_WEEK, WeakPart.FULL_SLEEP, "יוגה השבוע + שינה מלאה"),
        "summer" to w(WeakPart.CARDIO_20, WeakPart.FULL_SLEEP, "הליכה או ריצה של 20 דקות + שינה מלאה")
    )

    private val fallback = w(WeakPart.STRENGTH, WeakPart.FULL_SLEEP, "אימון כוח + שינה מלאה")

    fun forBoss(bossId: String): Weakness = weekly[bossId] ?: fallback

    fun forEvent(eventId: String): Weakness = events[eventId] ?: fallback

    /** "חולשה: גב + רגליים באותו אימון" */
    fun lineHe(wk: Weakness): String = "חולשה: ${wk.labelHe}"

    fun has(part: WeakPart, f: WeakFacts): Boolean {
        val strengthNow = f.type == "GYM" || f.strengthSets >= 3
        return when (part) {
            WeakPart.LEGS -> Branch.LEGS in f.branches
            WeakPart.BACK -> Branch.BACK in f.branches
            WeakPart.CHEST -> Branch.CHEST in f.branches
            WeakPart.SHOULDERS -> Branch.SHOULDERS_ARMS in f.branches
            WeakPart.CORE -> Branch.CORE in f.branches
            WeakPart.STRENGTH -> strengthNow
            WeakPart.STRENGTH_WEEK -> strengthNow || f.strengthEarlierThisWeek
            WeakPart.YOGA_WEEK -> f.type == "YOGA" || "YOGA" in f.otherTypesThisWeek
            WeakPart.CARDIO_20 -> (f.type == "WALK" || f.type == "RUN") && f.minutes >= 20
            WeakPart.NON_GYM -> f.type != "GYM"
            WeakPart.VARIETY -> f.otherTypesThisWeek.any { it != f.type }
            WeakPart.CONSECUTIVE -> f.sessionYesterday
            WeakPart.ALL_SETS -> f.hasSets && f.allSetsDone
            WeakPart.PUSH -> f.record || (f.hasSets && f.allSetsDone && f.allOnTarget)
            WeakPart.FULL_SLEEP -> (f.sleepHours ?: 8f) >= 7f
            WeakPart.EARLY_WEEK -> f.dayOfWeek == 7 || f.dayOfWeek == 1
            WeakPart.MIDWEEK -> f.dayOfWeek in 1..3
            WeakPart.CLOSED_DAY -> f.closedDay
            WeakPart.PHOTO_MEAL -> f.photoMeal
            WeakPart.PROTEIN -> f.protein
        }
    }

    fun grade(wk: Weakness, f: WeakFacts): WeakGrade {
        val a = has(wk.a, f)
        val b = has(wk.b, f)
        return when {
            a && b -> WeakGrade.FULL
            a || b -> WeakGrade.PARTIAL
            else -> WeakGrade.NONE
        }
    }

    /** Multiplier of each hit as (numerator, denominator). Weekly boss. */
    fun weeklyMult(g: WeakGrade): Pair<Int, Int> = when (g) {
        WeakGrade.FULL -> 2 to 1
        WeakGrade.PARTIAL -> 1 to 2
        WeakGrade.NONE -> 0 to 1
    }

    /** Multiplier of each hit for a seasonal event boss (softer: the event lasts only a few days). */
    fun eventMult(g: WeakGrade): Pair<Int, Int> = when (g) {
        WeakGrade.FULL -> 2 to 1
        WeakGrade.PARTIAL -> 1 to 1
        WeakGrade.NONE -> 1 to 2
    }

    /** Which ingredients are present / missing, for the bar ("יש: גב. חסר: רגליים."). */
    fun statusHe(wk: Weakness, f: WeakFacts): String {
        val a = has(wk.a, f)
        val b = has(wk.b, f)
        return when {
            a && b -> "השילוב המלא. כל מכה כפולה."
            a -> "יש: ${wk.a.labelHe}. חסר: ${wk.b.labelHe}. בינתיים חצי נזק."
            b -> "יש: ${wk.b.labelHe}. חסר: ${wk.a.labelHe}. בינתיים חצי נזק."
            else -> "עדיין אין אף רכיב של החולשה. הוא לא מרגיש כלום."
        }
    }

    /** Line for the done screen. */
    fun doneLineHe(g: WeakGrade): String = when (g) {
        WeakGrade.FULL -> "פגעת בחולשה. נזק כפול."
        WeakGrade.PARTIAL -> "חצי מהחולשה. חצי מהנזק."
        WeakGrade.NONE -> "החולשה לא נפגעה. הוא אפילו לא הסתובב."
    }
}
