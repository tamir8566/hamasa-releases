package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.SetSpec
import com.tamir.hamasa.data.db.formatSetKg

/**
 * End of a workout started from one of his templates: the template's written sets (weight × reps) follow what he
 * really did this time. Pure logic, used by [WorkoutRepository.templateChangeOf] and
 * [WorkoutRepository.applySessionToTemplate].
 */
object TemplateValues {

    /**
     * The template sets of one exercise after today's session.
     *  - set i done today (completed, reps > 0): today's reps; today's weight when [weighted] and the template
     *    set has its own weight (0 = "last known weight" stays 0, it already follows him);
     *  - set i not done today: the template's own set stays;
     *  - a set done today beyond the template's sets is added; template sets beyond today's rows stay.
     * Not weighted (bodyweight, seconds): the stored kg is kept, only reps / seconds change.
     */
    fun merge(old: List<SetSpec>, rows: List<SetLogEntity>, weighted: Boolean): List<SetSpec> {
        val sorted = rows.sortedBy { it.setIndex }
        val n = maxOf(old.size, sorted.size)
        val out = ArrayList<SetSpec>()
        for (i in 0 until n) {
            val o = old.getOrNull(i)
            val r = sorted.getOrNull(i)
            val done = r != null && r.completed && r.reps > 0
            if (done && r != null) {
                val kg = when {
                    o == null -> if (weighted) r.weight.coerceAtLeast(0f) else 0f
                    weighted && o.kg > 0f -> r.weight.coerceAtLeast(0f)
                    else -> o.kg
                }
                out.add(SetSpec(kg, r.reps))
            } else if (o != null) {
                out.add(o)
            }
        }
        return out
    }

    /** True when the two lists differ in any set (weights compared to 0.01 kg). */
    fun differs(a: List<SetSpec>, b: List<SetSpec>): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) {
            if (a[i].reps != b[i].reps) return true
            if (kotlin.math.abs(a[i].kg - b[i].kg) > 0.01f) return true
        }
        return false
    }

    /**
     * "40 ק"ג, חזרות 8, 8, 7" when every set has the same weight; "8 חזרות ב-62.5 ק"ג, 7 חזרות ב-60 ק"ג" otherwise.
     * Without weights: "חזרות 12, 10" (or "שניות 45, 40" for timed work). No "×": it breaks the order in RTL.
     */
    fun describe(specs: List<SetSpec>, timed: Boolean): String {
        if (specs.isEmpty()) return ""
        val unit = if (timed) "שניות" else "חזרות"
        val reps = specs.joinToString(", ") { it.reps.toString() }
        val kgs = specs.map { it.kg }
        if (kgs.all { it <= 0f }) return "$unit $reps"
        val same = kgs.all { kotlin.math.abs(it - kgs[0]) < 0.01f }
        return if (same) formatSetKg(kgs[0]) + " ק\"ג, " + unit + " " + reps
        else specs.joinToString(", ") { it.reps.toString() + " " + unit + " ב-" + formatSetKg(it.kg) + " ק\"ג" }
    }

    /** Three lines: the exercise, "היה: ...", "עכשיו: ...". */
    fun lineHe(nameHe: String, old: List<SetSpec>, new: List<SetSpec>, timed: Boolean): String =
        nameHe + "\nהיה: " + describe(old, timed) + "\nעכשיו: " + describe(new, timed)
}
