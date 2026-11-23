package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Focus

/** One planned exercise inside a workout plan. */
data class PlannedExercise(
    val exerciseId: String,
    val nameHe: String,
    val branch: Branch,
    val sets: Int,
    val reps: Int,                 // target reps (or seconds when unit == "sec")
    val weightKg: Float,           // 0 for bodyweight
    val restSec: Int,
    val note: String = "",         // coach note in Hebrew, e.g. "עלינו 2.5 ק\"ג"
    val supersetWith: String? = null,
    /** Per-set (kg, reps) targets from the user's own template, in set order. Empty = [sets] × ([weightKg], [reps]). */
    val perSet: List<Pair<Float, Int>> = emptyList()
)

data class WorkoutPlan(
    val type: SessionType,
    val modeId: String,
    val titleHe: String,
    val exercises: List<PlannedExercise>,
    val estimatedMinutes: Int,
    val coachLineHe: String        // one serious line from the coach
)

/** Sleep bands decided from last night's sleep. */
enum class SleepBand { FULL, MEDIUM, SHORT }

fun sleepBand(hours: Float?): SleepBand = when {
    hours == null -> SleepBand.FULL
    hours >= 7f -> SleepBand.FULL
    hours >= 5.5f -> SleepBand.MEDIUM
    else -> SleepBand.SHORT
}

/** A recent performance snapshot for one exercise, used for progression. */
data class ExerciseHistory(
    val exerciseId: String,
    val lastWeight: Float,
    val lastReps: Int,
    val lastSets: Int,
    val allSetsHitTargetLastTwo: Boolean,
    val missedTargetLast: Boolean,
    val bestWeight: Float,
    /** Reps of every completed set in the last session of this exercise, in set order. */
    val lastSetReps: List<Int> = emptyList(),
    /** Target reps that were planned in that last session (0 when unknown). */
    val lastTargetReps: Int = 0,
    /** Reps of every completed set in the session BEFORE the last one (difficulty "קל" waits for two at the top). */
    val prevSetReps: List<Int> = emptyList(),
    /** Top weight of that session before the last one; 0 when unknown. */
    val prevWeight: Float = 0f
)

/** Context the coach needs to build today's plan. */
data class CoachContext(
    val focus: Focus,
    val sleep: SleepBand,
    val modeId: String,
    val bodyWeightKg: Float,
    val history: Map<String, ExerciseHistory>,
    val branchLevels: Map<Branch, Int>,
    val recentModes: List<String>,
    val preferredMinutes: Int = 45,
    val daysSinceLastGym: Int = 0,
    val isWeekend: Boolean = false,
    /** Today as epoch day; 0 means unknown (block 0). */
    val todayEpochDay: Long = 0L,
    /** Epoch day of the first finished session; 0 means unknown (block 0). */
    val programStartEpochDay: Long = 0L,
    /** Number of finished gym sessions ever; drives the split rotation. */
    val gymSessionsDone: Int = 0,
    /** Finished gym sessions in the last 28 days; drives the split choice. */
    val gymSessionsLast28Days: Int = 0,
    /** User swaps "baseId -> chosenId" that hold for the current block. */
    val exerciseOverrides: Map<String, String> = emptyMap(),
    /** Block id the overrides were recorded in; -1 means none. */
    val overridesBlock: Int = -1,
    /** The user's own exercises (isCustom = true). Joined to slots by their `target` key. */
    val customExercises: List<ExerciseEntity> = emptyList(),
    /**
     * Mode "perfect": (exerciseId, completed sets) of his highest-rated finished gym session, in the order
     * he did them. Empty when he never rated a gym session.
     */
    val favoriteSession: List<Pair<String, Int>> = emptyList(),
    /** Active limits from a note to the coach (stage 2 G); null = none or expired. */
    val constraints: CoachConstraints? = null,
    /** An accepted deload week is running (domain/coach/Deload): same exercises, fewer sets, ~10% less weight, no increases. */
    val deload: Boolean = false,
    /** Effective difficulty today: a note's temporary override, else the coach-screen setting (domain/coach/Difficulty). */
    val difficulty: Difficulty = Difficulty.NORMAL,
    /** Profile sex is "F" (or unknown and the player is addressed as a woman): lighter upper-body starting weights. */
    val female: Boolean = false
)
