package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.SetLogEntity

/**
 * The month's progress in every exercise he did more than once that month (owner request 2026-11-23): one point
 * per workout, and the change from the first workout of the month to the last, in percent. Pure logic.
 *
 * The number that is compared is the strength of the best set of the workout. For a lift in kilograms that is
 * the estimated one-repetition maximum (Epley: kg x (1 + reps / 30), reps counted up to 12), so both a heavier
 * bar and more repetitions show as progress. For bodyweight or timed work it is the most reps (or seconds).
 */
object MonthProgress {

    data class Point(val day: Long, val value: Float, val kg: Float, val reps: Int)

    data class Row(val exerciseId: String, val nameHe: String, val weighted: Boolean, val points: List<Point>) {
        val first: Point get() = points.first()
        val last: Point get() = points.last()
        /** Change from the first workout of the month to the last, in percent. */
        val changePct: Float get() = if (first.value > 0f) (last.value - first.value) / first.value * 100f else 0f
    }

    /** One workout's sets of one exercise. */
    data class Entry(val day: Long, val sets: List<SetLogEntity>)

    private const val MAX_REPS_FOR_ESTIMATE = 12

    /** Estimated one-repetition maximum of a set. */
    fun strength(kg: Float, reps: Int): Float = kg * (1f + reps.coerceIn(1, MAX_REPS_FOR_ESTIMATE) / 30f)

    /** The best set of a workout, or null when no set was completed. */
    fun pointOf(day: Long, sets: List<SetLogEntity>, weighted: Boolean): Point? {
        val done = sets.filter { it.completed && it.reps > 0 }
        if (done.isEmpty()) return null
        if (weighted && done.any { it.weight > 0f }) {
            val best = done.filter { it.weight > 0f }.maxByOrNull { strength(it.weight, it.reps) } ?: return null
            return Point(day, strength(best.weight, best.reps), best.weight, best.reps)
        }
        val best = done.maxByOrNull { it.reps } ?: return null
        return Point(day, best.reps.toFloat(), 0f, best.reps)
    }

    /**
     * [entries] by exercise id (any order). An exercise shows only with points on two different days or more.
     * Two workouts on the same day keep the better one. Sorted: the biggest improvement first.
     */
    fun rows(entries: Map<String, List<Entry>>, nameOf: (String) -> String, weightedOf: (String) -> Boolean): List<Row> {
        val out = ArrayList<Row>()
        for ((id, list) in entries) {
            val weighted = weightedOf(id)
            val byDay = HashMap<Long, Point>()
            for (e in list) {
                val p = pointOf(e.day, e.sets, weighted) ?: continue
                val had = byDay[e.day]
                if (had == null || p.value > had.value) byDay[e.day] = p
            }
            if (byDay.size < 2) continue
            val points = byDay.values.sortedBy { it.day }
            // a month that mixes weighted and unweighted points of one exercise cannot be compared
            if (points.any { it.kg > 0f } && points.any { it.kg <= 0f }) continue
            out.add(Row(id, nameOf(id), points.first().kg > 0f, points))
        }
        return out.sortedWith(compareByDescending<Row> { it.changePct }.thenBy { it.nameHe })
    }
}
