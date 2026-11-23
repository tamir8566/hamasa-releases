package com.tamir.hamasa.domain.game

import com.tamir.hamasa.domain.Days

/**
 * Streak rule (spec: "יום מנוחה מובנה אחד בשבוע"):
 * - Every Sunday–Saturday week has ONE free rest day: the first missed day of that week costs nothing.
 * - Any other missed day in the same week uses a shield.
 * - If there are not enough shields, the streak breaks (back to 1 on the day of the activity).
 * - A day a shield covered counts in the streak (Tamir): the streak grows by the shield days plus the day of the activity.
 * - The chain goes on across weeks: a new Sunday–Saturday week only brings its own free rest day.
 * Pure logic, no IO. Shields are only consumed when the streak survives.
 */
object StreakRules {
    data class Outcome(
        val streak: Int,
        /** Shields needed to cover the missed days (0 when the streak broke). */
        val shieldsUsed: Int,
        /** Week start (Sunday) of the latest week whose free rest day is now used. */
        val restWeekUsed: Long,
        val broken: Boolean
    )

    /** Longest gap we walk day by day; anything longer simply breaks the streak. */
    private const val MAX_GAP_DAYS = 30L

    fun onActivity(lastActiveDay: Long, day: Long, streak: Int, shields: Int, restWeekUsed: Long): Outcome {
        if (lastActiveDay == 0L) return Outcome(1, 0, restWeekUsed, false)
        // Same day, or the clock moved back: never break the streak for that.
        if (day <= lastActiveDay) return Outcome(streak, 0, restWeekUsed, false)
        if (day - lastActiveDay > MAX_GAP_DAYS) return Outcome(1, 0, restWeekUsed, true)
        var restWeek = restWeekUsed
        var used = 0
        var d = lastActiveDay + 1
        while (d < day) {
            val week = Days.weekStartOf(d)
            if (week != restWeek) {
                restWeek = week            // the free rest day of this week
            } else if (used < shields) {
                used++                     // second missed day in the same week: a shield
            } else {
                return Outcome(1, 0, restWeekUsed, true)
            }
            d++
        }
        return Outcome(streak + 1 + used, used, restWeek, false)
    }
}
