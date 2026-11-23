package com.tamir.hamasa.domain.food

import com.tamir.hamasa.domain.energy.GoalDirection
import kotlin.math.abs

/**
 * One day of the weekly calorie summary. [ok] = null when the day is not judged (nothing logged, or today is still open).
 * [estimated]: nothing was logged that day, and [eaten] is his usual day (the average of the days he did log);
 * such a day counts in the balance against the burn, never in the judgement against the goal.
 */
data class CalDay(val day: Long, val eaten: Int, val ok: Boolean?, val isToday: Boolean, val estimated: Boolean = false) {
    /** Calories above (+) or below (-) the goal. */
    fun diff(goal: Int): Int = eaten - goal
}

/** The week's judgement of calories against the daily goal. Pure. */
object WeekCalories {
    /** A day is a success: cut = not above the goal (+range), gain = not below it (-range), maintain = within the range. */
    fun dayOk(direction: GoalDirection, eaten: Int, goal: Int): Boolean {
        val diff = eaten - goal
        return when (direction) {
            GoalDirection.CUT -> diff <= Nutrition.HUNT_RANGE
            GoalDirection.GAIN -> diff >= -Nutrition.HUNT_RANGE
            GoalDirection.MAINTAIN -> abs(diff) <= Nutrition.HUNT_RANGE
        }
    }

    /**
     * [eatenByDay]: kcal per day of the week (missing = nothing logged). Days after today are left out.
     * Today is listed but not judged (the day is still open). [usualByDay]: for a finished day with nothing
     * logged, the calories of his usual day (owner request 2026-11-23: an empty day counts as an average day).
     */
    fun week(
        weekStart: Long, today: Long, eatenByDay: Map<Long, Int>, direction: GoalDirection, goal: Int,
        usualByDay: Map<Long, Int> = emptyMap()
    ): List<CalDay> =
        (weekStart..weekStart + 6).filter { it <= today }.map { d ->
            val eaten = eatenByDay[d] ?: 0
            val usual = if (eaten <= 0 && d < today) (usualByDay[d] ?: 0) else 0
            if (usual > 0) CalDay(d, usual, null, false, estimated = true)
            else {
                val judged = d < today && eaten > 0
                CalDay(d, eaten, if (judged) dayOk(direction, eaten, goal) else null, d == today)
            }
        }

    /** How many past days the average looks at. */
    const val USUAL_WINDOW = 30
    private const val USUAL_MIN_DAYS = 3

    /**
     * His usual day in calories: the average of the finished days he logged in the [USUAL_WINDOW] days up to
     * [upTo] (at least three of them), else of every finished day he ever logged. 0 = nothing to average.
     */
    fun usualIntake(eatenByDay: Map<Long, Int>, upTo: Long, today: Long): Int {
        val done = eatenByDay.filter { it.value > 0 && it.key < today }
        if (done.isEmpty()) return 0
        val recent = done.filter { it.key <= upTo && it.key > upTo - USUAL_WINDOW }.values
        val pool = if (recent.size >= USUAL_MIN_DAYS) recent else done.values
        return (pool.sum().toFloat() / pool.size).toInt()
    }

    /** Sum of (eaten - goal) over the judged days: positive = above the goal in total. */
    fun totalDiff(days: List<CalDay>, goal: Int): Int = days.filter { it.ok != null }.sumOf { it.eaten - goal }

    /** Kcal one kg of body fat stands for (the usual rule of thumb), for "the week's deficit is about X kg". */
    const val KCAL_PER_KG_FAT = 7700

    /**
     * The day's real balance: eaten minus what the body burned that day (negative = deficit). Null when the
     * burn of that day is not known.
     */
    fun balance(day: CalDay, burnByDay: Map<Long, Int>): Int? = burnByDay[day.day]?.takeIf { it > 0 }?.let { day.eaten - it }

    /** A balance in the direction of the goal: cut = a deficit, gain = a surplus, maintain = within 150 of even. */
    fun balanceOk(direction: GoalDirection, balance: Int): Boolean = when (direction) {
        GoalDirection.CUT -> balance < 0
        GoalDirection.GAIN -> balance > 0
        GoalDirection.MAINTAIN -> abs(balance) <= 150
    }

    /** Sum of the balances of the finished days (logged, or counted as a usual day) that have a known burn. */
    fun totalBalance(days: List<CalDay>, burnByDay: Map<Long, Int>): Int =
        days.filter { it.ok != null || it.estimated }.sumOf { balance(it, burnByDay) ?: 0 }

    fun estimatedCount(days: List<CalDay>): Int = days.count { it.estimated }

    fun okCount(days: List<CalDay>): Int = days.count { it.ok == true }
    fun judgedCount(days: List<CalDay>): Int = days.count { it.ok != null }
}
