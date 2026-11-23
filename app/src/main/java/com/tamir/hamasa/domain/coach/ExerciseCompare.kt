package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.SetLogEntity
import java.util.Locale

/**
 * End of a workout: every exercise against its last time. The coach's summary ("הג'ים ברו מסכם"), factual:
 * the top set then and now, the total volume, and a verdict. Pure logic; the view model feeds it the sets.
 */
object ExerciseCompare {

    enum class Verdict { UP, DOWN, SAME, FIRST }

    data class Top(val kg: Float, val reps: Int)

    data class Row(
        val exerciseId: String,
        val nameHe: String,
        /** kg-based lift (false: bodyweight or seconds, only reps / seconds count). */
        val weighted: Boolean,
        val todayTop: Top,
        val todayVolume: Float,
        val prevTop: Top?,
        val prevVolume: Float,
        val verdict: Verdict
    )

    /** The heaviest set (by kg, then reps); for unweighted work the most reps. */
    fun topOf(sets: List<SetLogEntity>, weighted: Boolean): Top? {
        val done = sets.filter { it.completed && it.reps > 0 }
        if (done.isEmpty()) return null
        val best = if (weighted) done.maxWithOrNull(compareBy<SetLogEntity> { it.weight }.thenBy { it.reps }) else done.maxByOrNull { it.reps }
        return best?.let { Top(it.weight.coerceAtLeast(0f), it.reps) }
    }

    /** kg × reps summed; for unweighted work, the reps (or seconds) summed. */
    fun volumeOf(sets: List<SetLogEntity>, weighted: Boolean): Float =
        sets.filter { it.completed && it.reps > 0 }.sumOf { (if (weighted) (it.weight.coerceAtLeast(0f) * it.reps) else it.reps.toFloat()).toDouble() }.toFloat()

    /**
     * Verdict from the top set first (a heavier top set is UP even at lower volume: that is how he progresses),
     * then from the volume (±3%). No previous session = FIRST.
     */
    fun verdict(today: Top, todayVol: Float, prev: Top?, prevVol: Float, weighted: Boolean): Verdict {
        if (prev == null) return Verdict.FIRST
        if (weighted) {
            if (today.kg > prev.kg + 0.01f) return Verdict.UP
            if (today.kg < prev.kg - 0.01f) return Verdict.DOWN
            if (today.reps > prev.reps) return Verdict.UP
            if (today.reps < prev.reps) return Verdict.DOWN
        } else {
            if (today.reps > prev.reps) return Verdict.UP
            if (today.reps < prev.reps) return Verdict.DOWN
        }
        if (prevVol <= 0f) return Verdict.SAME
        val r = todayVol / prevVol
        return when {
            r >= 1.03f -> Verdict.UP
            r <= 0.97f -> Verdict.DOWN
            else -> Verdict.SAME
        }
    }

    fun row(exerciseId: String, nameHe: String, weighted: Boolean, todaySets: List<SetLogEntity>, prevSets: List<SetLogEntity>?): Row? {
        val today = topOf(todaySets, weighted) ?: return null
        val tv = volumeOf(todaySets, weighted)
        val prev = prevSets?.let { topOf(it, weighted) }
        val pv = prevSets?.let { volumeOf(it, weighted) } ?: 0f
        return Row(exerciseId, nameHe, weighted, today, tv, prev, pv, verdict(today, tv, prev, pv, weighted))
    }

    fun kg(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    fun topHe(t: Top, weighted: Boolean, timed: Boolean): String =
        if (weighted) "‎" + kg(t.kg) + " ק\"ג × " + t.reps else (if (timed) "${t.reps} שניות" else "${t.reps} חזרות")

    /** The coach's line for one exercise: factual, one sentence of judgement at the end. */
    fun lineHe(r: Row, timed: Boolean = false): String {
        val now = topHe(r.todayTop, r.weighted, timed)
        val prev = r.prevTop
        if (prev == null || r.verdict == Verdict.FIRST) return "${r.nameHe}: $now. פעם ראשונה, מכאן מודדים."
        val was = topHe(prev, r.weighted, timed)
        val volPct = if (r.prevVolume > 0f) Math.round((r.todayVolume / r.prevVolume - 1f) * 100f) else 0
        val vol = when {
            volPct >= 3 -> " הנפח עלה ב-$volPct אחוז."
            volPct <= -3 -> " הנפח ירד ב-${-volPct} אחוז."
            else -> ""
        }
        return when (r.verdict) {
            Verdict.UP -> "${r.nameHe}: $was ← $now. שיפור.$vol"
            Verdict.DOWN -> "${r.nameHe}: $was ← $now. ירידה.$vol" + (if (vol.isEmpty()) " יום כזה. הפעם הבאה מתקנת." else "")
            Verdict.SAME -> "${r.nameHe}: $now, כמו בפעם הקודמת. יציב.$vol"
            Verdict.FIRST -> "${r.nameHe}: $now. פעם ראשונה."
        }
    }

    /** The headline over the rows: how many went up / down. Serious coach voice. */
    fun headlineHe(rows: List<Row>): String {
        val up = rows.count { it.verdict == Verdict.UP }
        val down = rows.count { it.verdict == Verdict.DOWN }
        val first = rows.count { it.verdict == Verdict.FIRST }
        val compared = rows.size - first
        if (rows.isEmpty()) return "אין תרגילים להשוות."
        if (compared == 0) return "כל התרגילים היום הם פעם ראשונה. מהאימון הבא יש למה להשוות."
        return when {
            down == 0 && up > 0 -> "$up מתוך $compared תרגילים השתפרו מהפעם הקודמת. אף אחד לא ירד."
            up == 0 && down > 0 -> "$down מתוך $compared תרגילים ירדו מהפעם הקודמת. שינה, אוכל, ואז ננסה שוב."
            up == 0 && down == 0 -> "כל התרגילים כמו בפעם הקודמת. יציבות, ובשבוע הבא דוחפים."
            else -> "$up השתפרו, $down ירדו, מתוך $compared שהושוו."
        }
    }
}
