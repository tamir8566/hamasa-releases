package com.tamir.hamasa.ui.workout

import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.coach.ExerciseCatalog

/**
 * "סט סודי": one accessory set per session whose target stays hidden ("?") until every earlier set of
 * that exercise is done. Then it shows a target [EXTRA_REPS] over the normal one; meeting it gives [XP] XP.
 * Never on compound lifts (the coach owns those), never on timed or mobility work.
 * The stored target in the set log is NOT changed, so the coach's progression is untouched.
 */
object SecretSet {
    const val EXTRA_REPS = 2
    const val XP = 10

    /**
     * Picks the secret set deterministically from the session id: among the eligible accessory exercises
     * (sorted by id), take exercise number sessionId % count and use its last planned set.
     * Returns null when nothing is eligible or exercise rows are not loaded yet.
     */
    fun pick(sessionId: Long, sets: List<SetLogEntity>, exercises: Map<String, ExerciseEntity>): Long? {
        if (sets.isEmpty()) return null
        val groups = sets.groupBy { it.exerciseId }
        if (groups.keys.any { it !in exercises }) return null
        val eligible = groups.keys.filter { id ->
            val e = exercises[id] ?: return@filter false
            !e.isCompound && e.unit != "sec" && !ExerciseCatalog.isMobility(id) && (groups[id]?.size ?: 0) >= 2
        }.sorted()
        if (eligible.isEmpty()) return null
        val chosen = eligible[Math.floorMod(sessionId, eligible.size.toLong()).toInt()]
        return groups[chosen]?.maxByOrNull { it.setIndex }?.id
    }

    /** True when every set of the same exercise before [secret] (by setIndex) is completed. */
    fun revealed(secret: SetLogEntity, exerciseSets: List<SetLogEntity>): Boolean =
        exerciseSets.filter { it.setIndex < secret.setIndex }.all { it.completed }

    /** Target reps shown once revealed. */
    fun targetReps(secret: SetLogEntity): Int = secret.targetReps + EXTRA_REPS
}
