package com.tamir.hamasa.domain.game

import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.domain.coach.MuscleGroup
import com.tamir.hamasa.domain.coach.MuscleRegion

/**
 * Pure muscle-heat computation for the body heatmap and the radar chart.
 * Input: completed working sets per exercise. Output: a 0..1 heat per muscle group,
 * where 1.0 means about [FULL_SETS] weighted sets in the period (or more).
 */
object MuscleHeat {

    /** Weighted sets that count as "fully worked" in one week. */
    const val FULL_SETS = 6f

    /** One exercise of the period: id, number of completed working sets, and the custom target key (empty for catalog ids). */
    data class WorkedExercise(val exerciseId: String, val completedSets: Int, val customTarget: String = "")

    data class HeatResult(
        /** 0..1 per group (groups that were not worked are absent or 0). */
        val heat: Map<MuscleGroup, Float>,
        /** Raw weighted sets per group, before normalisation. */
        val weightedSets: Map<MuscleGroup, Float>,
        /** Completed sets per radar region, counted by each exercise's primary muscle. */
        val setsByRegion: Map<MuscleRegion, Int>,
        /** 0..1 per radar region. */
        val regionScores: Map<MuscleRegion, Float>,
        val totalSets: Int
    ) {
        val isEmpty: Boolean get() = totalSets == 0

        /** "חזה 3 סטים, גב 4 סטים" style lines, biggest first, only regions that were worked. */
        fun regionLines(): List<String> =
            setsByRegion.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .map { "${it.key.nameHe} ${setsLabel(it.value)}" }

        companion object {
            val EMPTY = HeatResult(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)
        }
    }

    fun setsLabel(n: Int): String = when (n) {
        1 -> "סט אחד"
        2 -> "שני סטים"
        else -> "$n סטים"
    }

    /** Raw weighted sets per group: sum over exercises of completedSets * weight. */
    fun weightedSets(items: List<WorkedExercise>): Map<MuscleGroup, Float> {
        val out = HashMap<MuscleGroup, Float>()
        for (item in items) {
            if (item.completedSets <= 0) continue
            for ((group, w) in ExerciseTargets.musclesOf(item.exerciseId, item.customTarget)) {
                out[group] = (out[group] ?: 0f) + item.completedSets * w
            }
        }
        return out
    }

    /** Normalises raw weighted sets to 0..1 against [fullSets]. */
    fun normalise(weighted: Map<MuscleGroup, Float>, fullSets: Float = FULL_SETS): Map<MuscleGroup, Float> {
        val denom = if (fullSets > 0f) fullSets else FULL_SETS
        val out = HashMap<MuscleGroup, Float>()
        for ((g, v) in weighted) out[g] = (v / denom).coerceIn(0f, 1f)
        return out
    }

    /** Region score = heat of the most worked group in the region (so one well-trained muscle lights its region). */
    fun regionScores(heat: Map<MuscleGroup, Float>): Map<MuscleRegion, Float> {
        val out = HashMap<MuscleRegion, Float>()
        for (region in MuscleRegion.entries) {
            var best = 0f
            for (g in MuscleGroup.inRegion(region)) {
                val v = heat[g] ?: 0f
                if (v > best) best = v
            }
            out[region] = best
        }
        return out
    }

    /** Completed sets per region, each exercise counted once under its primary (heaviest-weighted) muscle. */
    fun setsByRegion(items: List<WorkedExercise>): Map<MuscleRegion, Int> {
        val out = HashMap<MuscleRegion, Int>()
        for (item in items) {
            if (item.completedSets <= 0) continue
            val primary = ExerciseTargets.musclesOf(item.exerciseId, item.customTarget).maxByOrNull { it.second }?.first ?: continue
            out[primary.region] = (out[primary.region] ?: 0) + item.completedSets
        }
        return out
    }

    /** Everything at once. [fullSets] should scale with the period (6 per week). */
    fun compute(items: List<WorkedExercise>, fullSets: Float = FULL_SETS): HeatResult {
        val weighted = weightedSets(items)
        val heat = normalise(weighted, fullSets)
        return HeatResult(
            heat = heat,
            weightedSets = weighted,
            setsByRegion = setsByRegion(items),
            regionScores = regionScores(heat),
            totalSets = items.sumOf { it.completedSets.coerceAtLeast(0) }
        )
    }

    /** Full-sets threshold for a period of [days] days (6 per 7 days, never below 6). */
    fun fullSetsFor(days: Long): Float = (FULL_SETS * days / 7f).coerceAtLeast(FULL_SETS)
}
