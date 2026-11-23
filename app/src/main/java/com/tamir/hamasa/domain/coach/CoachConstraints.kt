package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity

/**
 * Temporary limits from a free-text note to the coach ("השבוע הברך כואבת לי"), stage 2 G.
 * Built by Gemini (or the offline keyword rules) in data/ai/CoachNotes, stored in Prefs as JSON with an expiry day.
 * The engine applies them deterministically: blocked exercises are skipped or replaced by an allowed candidate of
 * the same slot, [reduceSetsPct] trims accessory sets first, and load is never increased while a limit is active
 * (see [holdsLoad]).
 */
data class CoachConstraints(
    val avoidExercises: Set<String> = emptySet(),
    val avoidBranches: Set<Branch> = emptySet(),
    /** Movement pattern keys, see [MovementPatterns]. */
    val avoidPatterns: Set<String> = emptySet(),
    /** 6..10 or null. 8 or lower also stops weight increases. */
    val maxRpe: Int? = null,
    /** 0..50: share of today's working sets to remove (accessory sets first). */
    val reducePct: Int = 0,
    /** Equipment keys ("machine", "cable", "dumbbell", "barbell", "body", "band") that go first in every slot. */
    val preferEquipment: Set<String> = emptySet(),
    /** Last epoch day (inclusive) the limits hold. */
    val untilEpochDay: Long = 0L,
    /** Short serious reply of the coach, shown in the details. */
    val replyHe: String = "",
    val needsDoctor: Boolean = false,
    /** The user's own note, kept for the details dialog. */
    val noteHe: String = "",
    /** Short Hebrew subject for the chip: "לברך", "לגב התחתון", "לכתף" or "" (generic). */
    val subjectHe: String = "",
    // --- preferences (not pain): difficulty, equipment, place, time, focus, session type ---
    /** Temporary difficulty while the note holds; null = the setting on the coach screen. */
    val difficultyOverride: Difficulty? = null,
    /** Hard equipment filter for these days ("גומייה ומשקל גוף"); empty = no filter. Body weight is always allowed with it. */
    val onlyEquipment: Set<String> = emptySet(),
    /** "home" | "gym" | "outside" | "" (unknown). Home / outside without [onlyEquipment] = body weight and bands. */
    val place: String = "",
    /** Session length cap in minutes (15..120); null = none. Accessories are trimmed first. */
    val maxMinutes: Int? = null,
    /** "רק רגליים": today's gym plan keeps only these branches. */
    val focusBranches: Set<Branch> = emptySet(),
    /** Preferred session type: "GYM" | "RUN" | "YOGA" | "WALK" | "" (none). */
    val sessionType: String = "",
    // --- "אימון בבית" (HomeGear): set by the home sheet or mapped from a note "בבית"; never stored in the note JSON ---
    /** [HomeGear] keys available today; non-empty = the home equipment model decides what is possible. */
    val homeGear: Set<String> = emptySet(),
    /** Heaviest dumbbell he has (kg per hand); 0 = no cap. */
    val dumbbellMaxKg: Float = 0f,
    /** One fixed pair: every dumbbell exercise uses exactly [dumbbellMaxKg]. */
    val dumbbellFixed: Boolean = false,
    /** Kettlebell weight (always the weight of kettlebell exercises); 0 = not told. */
    val kettlebellKg: Float = 0f
) {
    fun activeOn(epochDay: Long): Boolean = untilEpochDay > 0L && epochDay <= untilEpochDay

    /** Whether anything at all is limited (an empty result is not worth storing). */
    val isEmpty: Boolean
        get() = avoidExercises.isEmpty() && avoidBranches.isEmpty() && avoidPatterns.isEmpty() &&
            maxRpe == null && reducePct <= 0 && preferEquipment.isEmpty() && !hasPreferences

    /** Any preference field (difficulty, equipment, place, time, focus, type) is set. */
    val hasPreferences: Boolean
        get() = difficultyOverride != null || onlyEquipment.isNotEmpty() || place.isNotEmpty() || maxMinutes != null ||
            focusBranches.isNotEmpty() || sessionType.isNotEmpty()

    /** Pain / fatigue fields are set (the "pain" part of a note). */
    val hasLimits: Boolean
        get() = avoidExercises.isNotEmpty() || avoidBranches.isNotEmpty() || avoidPatterns.isNotEmpty() ||
            maxRpe != null || reducePct > 0 || preferEquipment.isNotEmpty()

    /**
     * Equipment the plan may use while the note holds; empty = everything. Body weight is always part of a
     * restriction (it needs no equipment). Home / outside without an explicit list = body weight and bands.
     */
    val allowedEquipment: Set<String>
        get() = when {
            homeGear.isNotEmpty() -> HomeGear.equipmentOf(homeGear)
            onlyEquipment.isNotEmpty() -> onlyEquipment + "body"
            place == "home" || place == "outside" -> setOf("body", "band")
            else -> emptySet()
        }

    /** Pain / fatigue signals: no weight increases while they hold. */
    val holdsLoad: Boolean
        get() = (maxRpe != null && maxRpe <= 8) || reducePct > 0 || avoidPatterns.isNotEmpty() || avoidBranches.isNotEmpty()

    /**
     * Short Hebrew label of the preference part, for the chip and the coach line:
     * "גומייה ומשקל גוף, עד 30 דקות". "" when no preference is set.
     */
    fun preferenceLabelHe(): String {
        val parts = ArrayList<String>()
        if (onlyEquipment.isNotEmpty()) {
            val order = listOf("band", "body", "dumbbell", "machine", "cable", "barbell")
            val names = order.filter { it in onlyEquipment }.map { equipmentLabelHe(it) }
            parts.add(joinAnd(names))
        } else when (place) {
            "home" -> parts.add("בבית")
            "outside" -> parts.add("בחוץ")
            "gym" -> parts.add("במכון")
        }
        if (focusBranches.isNotEmpty()) {
            parts.add("רק " + joinAnd(focusBranches.sortedBy { it.ordinal }.map { ExerciseTargets.branchNameHe(it) }))
        }
        when (sessionType) {
            "RUN" -> parts.add("ריצה")
            "YOGA" -> parts.add("יוגה")
            "WALK" -> parts.add("הליכה")
        }
        val m = maxMinutes
        if (m != null) parts.add("עד $m דקות")
        val d = difficultyOverride
        if (d != null) parts.add(d.nameHe)
        return parts.joinToString(", ")
    }

    private fun joinAnd(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " ו" + names.last()
    }

    /** True when [e] must not be planned while these limits hold. */
    fun blocks(e: ExerciseEntity): Boolean {
        if (e.id in avoidExercises) return true
        if (e.branch in avoidBranches) return true
        if (avoidPatterns.isEmpty()) return false
        for (p in MovementPatterns.of(e)) if (p in avoidPatterns) return true
        return false
    }
}

/**
 * Movement patterns of the catalog exercises, used by "avoid_patterns". Custom exercises get their patterns
 * from their target key (quads -> knee flexion, front delt -> overhead).
 */
object MovementPatterns {
    const val KNEE_FLEXION_LOADED = "knee_flexion_loaded"
    const val OVERHEAD = "overhead"
    const val OVERHEAD_PULL = "overhead_pull"
    const val SPINAL_LOAD = "spinal_load"
    const val HIP_HINGE = "hip_hinge"
    const val WRIST_LOAD = "wrist_load"
    const val ELBOW_LOAD = "elbow_load"
    const val IMPACT = "impact"

    /** Every key with a Hebrew description (the prompt lists these so the model uses only them). */
    val all: List<Pair<String, String>> = listOf(
        KNEE_FLEXION_LOADED to "כיפוף ברך תחת עומס (סקוואט, לחיצת רגליים, מכרעים, פשיטת/כפיפת ברכיים)",
        OVERHEAD to "לחיצה או ידיים מעל הראש",
        OVERHEAD_PULL to "משיכה מעל הראש (מתח, פולי עליון, תלייה)",
        SPINAL_LOAD to "עומס צירי על עמוד השדרה (מוט על הגב, דדליפט, חתירה בכפיפה)",
        HIP_HINGE to "כפיפה מהירך עם משקל (דדליפט, דדליפט רומני, פשיטת גב)",
        WRIST_LOAD to "עומס על פרק כף היד (שכיבות סמיכה, פלאנק, מוט ישר)",
        ELBOW_LOAD to "עומס על המרפק (פשיטות מרפק, מקבילים, כפיפות במוט)",
        IMPACT to "קפיצות ונחיתות"
    )

    val keys: Set<String> = all.map { it.first }.toSet()

    private val table: Map<String, Set<String>> = mapOf(
        // legs
        "leg_press" to setOf(KNEE_FLEXION_LOADED),
        "squat" to setOf(KNEE_FLEXION_LOADED, SPINAL_LOAD),
        "hack_squat" to setOf(KNEE_FLEXION_LOADED, SPINAL_LOAD),
        "goblet_squat" to setOf(KNEE_FLEXION_LOADED),
        "bodyweight_squat" to setOf(KNEE_FLEXION_LOADED),
        "lunges" to setOf(KNEE_FLEXION_LOADED),
        "bulgarian_split_squat" to setOf(KNEE_FLEXION_LOADED),
        "leg_extension" to setOf(KNEE_FLEXION_LOADED),
        "leg_curl" to setOf(KNEE_FLEXION_LOADED),
        "romanian_deadlift" to setOf(HIP_HINGE, SPINAL_LOAD),
        "db_romanian_deadlift" to setOf(HIP_HINGE, SPINAL_LOAD),
        "hip_thrust" to setOf(HIP_HINGE),
        // back
        "deadlift" to setOf(HIP_HINGE, SPINAL_LOAD),
        "barbell_row" to setOf(SPINAL_LOAD, HIP_HINGE),
        "t_bar_row" to setOf(SPINAL_LOAD, HIP_HINGE),
        "back_extension" to setOf(HIP_HINGE, SPINAL_LOAD),
        "lat_pulldown" to setOf(OVERHEAD_PULL),
        "pullup" to setOf(OVERHEAD_PULL),
        "assisted_pullup" to setOf(OVERHEAD_PULL),
        "hanging_leg_raise" to setOf(OVERHEAD_PULL),
        // shoulders / arms
        "shoulder_press" to setOf(OVERHEAD),
        "barbell_ohp" to setOf(OVERHEAD, SPINAL_LOAD),
        "machine_shoulder_press" to setOf(OVERHEAD),
        "pike_pushup" to setOf(OVERHEAD, WRIST_LOAD),
        "overhead_tricep_extension" to setOf(OVERHEAD, ELBOW_LOAD),
        "front_raise" to setOf(OVERHEAD),
        "skull_crusher" to setOf(ELBOW_LOAD, WRIST_LOAD),
        "tricep_pushdown" to setOf(ELBOW_LOAD),
        "tricep_machine" to setOf(ELBOW_LOAD),
        "bench_dips" to setOf(ELBOW_LOAD, WRIST_LOAD),
        "barbell_curl" to setOf(ELBOW_LOAD, WRIST_LOAD),
        "preacher_curl" to setOf(ELBOW_LOAD),
        "db_shrug" to setOf(SPINAL_LOAD),
        // chest
        "pushup" to setOf(WRIST_LOAD),
        "decline_pushup" to setOf(WRIST_LOAD),
        "dips" to setOf(ELBOW_LOAD, WRIST_LOAD),
        "bench_press" to setOf(WRIST_LOAD),
        // core / cardio
        "plank" to setOf(WRIST_LOAD),
        "side_plank" to setOf(WRIST_LOAD),
        "mountain_climbers" to setOf(WRIST_LOAD, IMPACT),
        "ab_wheel" to setOf(WRIST_LOAD, SPINAL_LOAD),
        "burpees" to setOf(IMPACT, WRIST_LOAD),
        "jumping_jacks" to setOf(IMPACT),
        "high_knees" to setOf(IMPACT),
        // bands
        "band_squat" to setOf(KNEE_FLEXION_LOADED),
        "band_rdl" to setOf(HIP_HINGE),
        "band_lat_pulldown" to setOf(OVERHEAD_PULL),
        "band_shoulder_press" to setOf(OVERHEAD),
        "band_tricep_extension" to setOf(ELBOW_LOAD),
        "run_intervals" to setOf(IMPACT),
        // home ("אימון בבית")
        "chin_up" to setOf(OVERHEAD_PULL),
        "kb_goblet_squat" to setOf(KNEE_FLEXION_LOADED),
        "bw_bulgarian_split_squat" to setOf(KNEE_FLEXION_LOADED),
        "reverse_lunge" to setOf(KNEE_FLEXION_LOADED),
        "step_up" to setOf(KNEE_FLEXION_LOADED),
        "ball_hamstring_curl" to setOf(KNEE_FLEXION_LOADED),
        "kb_swing" to setOf(HIP_HINGE),
        "ball_back_extension" to setOf(HIP_HINGE),
        "kb_row" to setOf(HIP_HINGE),
        "kb_press" to setOf(OVERHEAD),
        "diamond_pushup" to setOf(WRIST_LOAD, ELBOW_LOAD),
        "trx_curl" to setOf(ELBOW_LOAD),
        "jump_rope" to setOf(IMPACT)
    )

    fun of(e: ExerciseEntity): Set<String> {
        val known = table[e.id]
        if (known != null) return known
        if (!e.isCustom) return emptySet()
        return when (e.target) {
            ExerciseTargets.QUADS -> setOf(KNEE_FLEXION_LOADED)
            ExerciseTargets.HAMSTRINGS -> setOf(HIP_HINGE)
            ExerciseTargets.FRONT_DELT -> setOf(OVERHEAD)
            ExerciseTargets.LATS -> setOf(OVERHEAD_PULL)
            ExerciseTargets.TRICEPS -> setOf(ELBOW_LOAD)
            else -> emptySet()
        }
    }
}

/** Hebrew name of an equipment key ("band" -> "גומייה"). */
fun equipmentLabelHe(key: String): String = when (key) {
    "barbell" -> "מוט"
    "dumbbell" -> "משקולות יד"
    "machine" -> "מכונות"
    "cable" -> "כבלים"
    "body" -> "משקל גוף"
    "band" -> "גומייה"
    "kettlebell" -> "קטלבל"
    "trx" -> "רצועות תלייה"
    else -> key
}
