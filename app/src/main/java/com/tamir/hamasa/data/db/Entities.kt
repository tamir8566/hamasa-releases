package com.tamir.hamasa.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Muscle branches of the skill tree. Order matters (used for tree layout). */
enum class Branch { CHEST, BACK, LEGS, SHOULDERS_ARMS, CORE, ENDURANCE }

enum class SessionType { GYM, YOGA, WALK, MINI, RUN }
enum class SessionStatus { PLANNED, ACTIVE, DONE, SKIPPED }

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,          // stable slug, e.g. "bench_press"
    val nameHe: String,
    val branch: Branch,
    val equipment: String,               // "barbell","dumbbell","machine","cable","body","band"
    val isCompound: Boolean,
    val unit: String = "kg",             // "kg" | "sec" | "reps"
    val alternativesCsv: String = "",    // comma separated exercise ids
    // --- custom exercises (added in db version 2; catalog rows keep the defaults) ---
    val isCustom: Boolean = false,       // true = created by the user, never re-seeded from the catalog
    val description: String = "",        // free text: how to perform it (custom only)
    val target: String = "",             // finer slot key, see ExerciseTargets (e.g. "upper_chest", "rear_delt")
    val preferred: Boolean = false       // custom only: goes FIRST in its slot so the coach actually uses it
)

@Entity(tableName = "sessions", indices = [Index("dateEpochDay")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val type: SessionType,
    val status: SessionStatus,
    val modeId: String?,                 // workout mode of the day (null for yoga/walk)
    val dailyGoal: String? = null,       // today's small goal text
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val durationSec: Int = 0,
    val xpEarned: Int = 0,
    val goldEarned: Int = 0,
    val rating: Int = 0,                 // 1..5 after workout
    val sleepHours: Float? = null,
    val notes: String = ""
)

@Entity(tableName = "set_logs", indices = [Index("sessionId"), Index("exerciseId")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val targetReps: Int,
    val targetWeight: Float,
    val reps: Int = 0,
    val weight: Float = 0f,
    val completed: Boolean = false,
    val isPr: Boolean = false
)

@Entity(tableName = "weights", indices = [Index(value = ["dateEpochDay"], unique = true)])
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val kg: Float
)

@Entity(tableName = "badges")
data class BadgeEntity(
    @PrimaryKey val id: String,
    val unlockedAtMillis: Long
)

@Entity(tableName = "unlocked_modes")
data class ModeUnlockEntity(
    @PrimaryKey val modeId: String,
    val unlockedAtMillis: Long
)

@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey val itemId: String,
    val acquiredAtMillis: Long,
    val equipped: Boolean = false
)

@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val xp: Int,
    val gold: Int,
    val reason: String
)

@Entity(tableName = "meals", indices = [Index("dateEpochDay")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val atMillis: Long,
    val description: String,
    val kcal: Int,
    /** Legacy column, never written: meal photos are deleted right after the estimate. */
    val photoPath: String? = null,
    // --- stage 2 F (db version 7): macros and photo estimate ---
    val proteinG: Float = 0f,
    val carbsG: Float = 0f,
    val fatG: Float = 0f,
    /** "manual" | "photo". A meal described in text and estimated by Gemini stays "manual" (with aiKcal > 0). */
    val source: String = "manual",
    /** Gemini's original total kcal estimate; 0 for manual meals. */
    val aiKcal: Int = 0,
    /** Recognized items, JSON array of {name_he, grams, kcal, protein_g, carbs_g, fat_g, confidence}; "" = none. */
    val itemsJson: String = ""
)

@Entity(tableName = "boss_progress")
data class BossProgressEntity(
    @PrimaryKey val bossId: String,
    val weekEpoch: Long,                 // epochDay of the Sunday that starts the week
    val hp: Int,
    val maxHp: Int,
    val defeated: Boolean = false
)
