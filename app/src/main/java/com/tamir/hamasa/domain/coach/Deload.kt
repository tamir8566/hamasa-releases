package com.tamir.hamasa.domain.coach

/**
 * "שבוע הורדת עומס" (deload week). Pure rules; the data side is data/repo/DeloadCenter, the engine side is
 * [CoachContext.deload] (CoachEngine: ~60% of the sets of every exercise, ~10% less weight, no increases, no
 * records-chasing notes). See docs/coach_logic.md.
 *
 * The coach PROPOSES; nothing happens until the player accepts. A deload week runs Sunday to Saturday.
 * Game rewards are not touched by a deload (nothing is taken away).
 */
object Deload {
    /** Sets kept per exercise, in tenths: 3 -> 2, 2 -> 1, 4 -> 2, 1 -> 1. */
    const val SETS_KEEP_TENTHS = 6
    const val WEIGHT_FACTOR = 0.9f
    const val SNOOZE_DAYS = 7L

    /** (a) consistency: a week with at least this many gym sessions counts as a training week. */
    const val CONSISTENT_WEEK_GYM = 2

    /** (b) fatigue window and thresholds. */
    const val FATIGUE_WINDOW_DAYS = 14L
    const val DROP_SESSIONS_SIGNAL = 2
    const val DROP_SESSIONS_ALONE = 3
    const val SHORT_SLEEP_HOURS = 6f
    const val SHORT_SLEEP_DAYS_SIGNAL = 3
    /** No fatigue proposal in the first weeks of the program or right after a deload. */
    const val MIN_WEEKS_FOR_FATIGUE = 3
    /** A main lift "dropped" when the best set's estimated 1RM fell by more than this share. */
    const val DROP_SHARE = 0.03f

    const val NOTE = "שבוע הורדת עומס: כ-10% פחות משקל, בלי עליות"
    const val NOTE_BODYWEIGHT = "שבוע הורדת עומס: אותן חזרות כמו בפעם הקודמת"

    fun sets(planned: Int): Int = ((planned * SETS_KEEP_TENTHS + 5) / 10).coerceAtLeast(1)

    /** The accepted deload week (Sunday [weekStart]) contains [day]. */
    fun activeOn(weekStart: Long, day: Long): Boolean = weekStart > 0L && day >= weekStart && day <= weekStart + 6

    /** A deload week was accepted for a week that has not started yet. */
    fun upcoming(weekStart: Long, day: Long): Boolean = weekStart > 0L && day < weekStart

    /** Sunday of the deload week when accepted on [today]: this week until Tuesday, otherwise next week. */
    fun weekToStart(today: Long, weekStartOfToday: Long): Long =
        if (today - weekStartOfToday <= 2L) weekStartOfToday else weekStartOfToday + 7L

    fun coachLine(sleepNote: String): String {
        val base = "שבוע הורדת עומס: אותם תרגילים, פחות סטים וכ-10% פחות משקל. השבוע לא רודפים אחרי שיאים, הגוף מתאושש ובא חזק יותר"
        return if (sleepNote.isNotEmpty()) "$base. $sleepNote." else "$base."
    }

    /** What the data side measured (data/repo/DeloadCenter). */
    data class Signals(
        /** Completed Sunday..Saturday weeks since the last deload ended (or since the first session). */
        val weeksSinceDeload: Int,
        /** Of the last 6 / 8 completed weeks (only weeks after the last deload), weeks with [CONSISTENT_WEEK_GYM]+ gym sessions. */
        val consistentLast6: Int,
        val consistentLast8: Int,
        /** Gym sessions in the last 14 days in which a main (compound) lift dropped against its previous session. */
        val dropSessions: Int,
        /** Days in the last 14 with a sleep report under [SHORT_SLEEP_HOURS]. */
        val shortSleepDays: Int,
        /** data/health/RestingHr: resting HR elevated against its baseline. */
        val restingHrElevated: Boolean
    )

    enum class Reason { CONSISTENCY, FATIGUE }

    /** A proposal with the serious, factual reasons the coach shows. */
    data class Proposal(val reason: Reason, val reasonsHe: List<String>)

    /**
     * (b) fatigue: at least two signals (drops in 2+ sessions, 3+ short nights, elevated resting HR), or drops in 3+
     * sessions alone; not before [MIN_WEEKS_FOR_FATIGUE] weeks since the last deload / the start.
     * (a) consistency: 6 weeks since the last deload with 5 of the last 6 being training weeks, or 8 weeks with 6 of 8.
     */
    fun propose(s: Signals): Proposal? {
        val fatigue = ArrayList<String>()
        if (s.dropSessions >= DROP_SESSIONS_SIGNAL) fatigue.add("ב-${s.dropSessions} אימונים בשבועיים האחרונים הביצוע בתרגיל מורכב ירד לעומת הפעם הקודמת")
        if (s.shortSleepDays >= SHORT_SLEEP_DAYS_SIGNAL) fatigue.add("${s.shortSleepDays} לילות עם פחות משש שעות שינה בשבועיים האחרונים")
        if (s.restingHrElevated) fatigue.add("דופק המנוחה מהשעון גבוה מהרגיל כמה ימים ברצף")
        val fatigueOn = s.weeksSinceDeload >= MIN_WEEKS_FOR_FATIGUE &&
            (fatigue.size >= 2 || s.dropSessions >= DROP_SESSIONS_ALONE)
        if (fatigueOn) return Proposal(Reason.FATIGUE, fatigue)
        val consistent = (s.weeksSinceDeload >= 6 && s.consistentLast6 >= 5) || (s.weeksSinceDeload >= 8 && s.consistentLast8 >= 6)
        if (consistent) {
            val weeks = s.weeksSinceDeload.coerceAtMost(12)
            return Proposal(Reason.CONSISTENCY, listOf("$weeks שבועות של אימונים עקביים בלי שבוע הורדת עומס"))
        }
        return null
    }
}
