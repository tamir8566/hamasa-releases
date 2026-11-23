package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.Branch

/**
 * Finer muscle targets used as slot keys. A custom exercise carries one of these keys in
 * [com.tamir.hamasa.data.db.ExerciseEntity.target]; the coach appends it to the slot with the same key.
 * Keys are stable strings (stored in the db and in the cloud), names are Hebrew for the UI.
 */
object ExerciseTargets {

    class TargetDef(val key: String, val nameHe: String, val branch: Branch)

    const val CHEST = "chest"
    const val UPPER_CHEST = "upper_chest"
    const val LATS = "lats"
    const val ROWS = "rows"
    const val FRONT_DELT = "front_delt"
    const val SIDE_DELT = "side_delt"
    const val REAR_DELT = "rear_delt"
    const val BICEPS = "biceps"
    const val TRICEPS = "triceps"
    const val QUADS = "quads"
    const val HAMSTRINGS = "hamstrings"
    const val GLUTES = "glutes"
    const val CALVES = "calves"
    const val ABS = "abs"
    const val MOBILITY = "mobility"

    val all: List<TargetDef> = listOf(
        TargetDef(CHEST, "חזה", Branch.CHEST),
        TargetDef(UPPER_CHEST, "חזה עליון", Branch.CHEST),
        TargetDef(LATS, "גב רחב (משיכה אנכית)", Branch.BACK),
        TargetDef(ROWS, "חתירה (משיכה אופקית)", Branch.BACK),
        TargetDef(FRONT_DELT, "כתף קדמית (לחיצה)", Branch.SHOULDERS_ARMS),
        TargetDef(SIDE_DELT, "כתף צדית", Branch.SHOULDERS_ARMS),
        TargetDef(REAR_DELT, "כתף אחורית", Branch.SHOULDERS_ARMS),
        TargetDef(BICEPS, "יד קדמית", Branch.SHOULDERS_ARMS),
        TargetDef(TRICEPS, "יד אחורית", Branch.SHOULDERS_ARMS),
        TargetDef(QUADS, "ארבע ראשי", Branch.LEGS),
        TargetDef(HAMSTRINGS, "ירך אחורית", Branch.LEGS),
        TargetDef(GLUTES, "ישבן", Branch.LEGS),
        TargetDef(CALVES, "תאומים", Branch.LEGS),
        TargetDef(ABS, "בטן", Branch.CORE),
        TargetDef(MOBILITY, "תנועתיות ומתיחות", Branch.ENDURANCE)
    )

    private val byKey: Map<String, TargetDef> = all.associateBy { it.key }

    fun byKey(key: String): TargetDef? = byKey[key]

    fun forBranch(branch: Branch): List<TargetDef> = all.filter { it.branch == branch }

    fun nameOf(key: String): String = byKey[key]?.nameHe ?: ""

    /** First target of a branch, used as the default when the user picks a branch. */
    fun defaultFor(branch: Branch): String = forBranch(branch).firstOrNull()?.key ?: CHEST

    fun branchNameHe(b: Branch): String = when (b) {
        Branch.CHEST -> "חזה"
        Branch.BACK -> "גב"
        Branch.LEGS -> "רגליים"
        Branch.SHOULDERS_ARMS -> "כתפיים וידיים"
        Branch.CORE -> "ליבה"
        Branch.ENDURANCE -> "סיבולת ותנועתיות"
    }

    val equipmentKeys: List<String> = listOf("barbell", "dumbbell", "machine", "cable", "body", "band")

    fun equipmentNameHe(key: String): String = when (key) {
        "barbell" -> "מוט"
        "dumbbell" -> "משקולות יד"
        "machine" -> "מכונה"
        "cable" -> "כבל"
        "body" -> "משקל גוף"
        "band" -> "גומייה"
        "kettlebell" -> "קטלבל"
        "trx" -> "רצועות תלייה"
        else -> key
    }

    val unitKeys: List<String> = listOf("kg", "reps", "sec")

    fun unitNameHe(key: String): String = when (key) {
        "kg" -> "קילוגרם"
        "reps" -> "חזרות"
        "sec" -> "שניות"
        else -> key
    }

    // ------------------------------------------------------------ muscle heatmap (appended)

    /**
     * Which muscle groups a target key loads, with relative weights (1.0 = primary mover).
     * Used by the heatmap only; the coach never reads this.
     */
    fun targetToMuscles(target: String): List<Pair<MuscleGroup, Float>> = when (target) {
        CHEST -> listOf(MuscleGroup.CHEST to 1.0f, MuscleGroup.TRICEPS to 0.3f, MuscleGroup.FRONT_DELT to 0.3f)
        UPPER_CHEST -> listOf(MuscleGroup.CHEST to 0.8f, MuscleGroup.FRONT_DELT to 0.5f, MuscleGroup.TRICEPS to 0.3f)
        LATS -> listOf(MuscleGroup.LATS to 1.0f, MuscleGroup.BICEPS to 0.4f, MuscleGroup.UPPER_BACK to 0.3f, MuscleGroup.FOREARMS to 0.2f)
        ROWS -> listOf(MuscleGroup.UPPER_BACK to 0.6f, MuscleGroup.LATS to 0.4f, MuscleGroup.BICEPS to 0.3f, MuscleGroup.REAR_DELT to 0.2f, MuscleGroup.FOREARMS to 0.2f)
        FRONT_DELT -> listOf(MuscleGroup.FRONT_DELT to 1.0f, MuscleGroup.TRICEPS to 0.4f, MuscleGroup.UPPER_BACK to 0.2f)
        // the chart has no side-delt region: split between the front and rear shoulder regions
        SIDE_DELT -> listOf(MuscleGroup.FRONT_DELT to 0.6f, MuscleGroup.REAR_DELT to 0.5f)
        REAR_DELT -> listOf(MuscleGroup.REAR_DELT to 1.0f, MuscleGroup.UPPER_BACK to 0.4f)
        BICEPS -> listOf(MuscleGroup.BICEPS to 1.0f, MuscleGroup.FOREARMS to 0.4f)
        TRICEPS -> listOf(MuscleGroup.TRICEPS to 1.0f)
        QUADS -> listOf(MuscleGroup.QUADS to 1.0f, MuscleGroup.GLUTES to 0.5f, MuscleGroup.HAMSTRINGS to 0.2f, MuscleGroup.LOWER_BACK to 0.2f, MuscleGroup.CALVES to 0.1f)
        HAMSTRINGS -> listOf(MuscleGroup.HAMSTRINGS to 1.0f, MuscleGroup.GLUTES to 0.5f, MuscleGroup.LOWER_BACK to 0.4f)
        GLUTES -> listOf(MuscleGroup.GLUTES to 1.0f, MuscleGroup.HAMSTRINGS to 0.4f, MuscleGroup.LOWER_BACK to 0.2f)
        CALVES -> listOf(MuscleGroup.CALVES to 1.0f)
        ABS -> listOf(MuscleGroup.ABS to 1.0f, MuscleGroup.LOWER_BACK to 0.1f)
        else -> emptyList()   // MOBILITY and unknown keys: no heat
    }

    /** Target key of every catalog exercise (custom exercises carry their own [com.tamir.hamasa.data.db.ExerciseEntity.target]). */
    private val catalogTargets: Map<String, String> = mapOf(
        // chest
        "bench_press" to CHEST, "incline_bench_press" to UPPER_CHEST, "db_bench_press" to CHEST,
        "incline_db_press" to UPPER_CHEST, "chest_press_machine" to CHEST, "chest_fly" to CHEST,
        "db_fly" to CHEST, "cable_crossover" to CHEST, "pushup" to CHEST, "decline_pushup" to UPPER_CHEST,
        "dips" to CHEST,
        // back
        "lat_pulldown" to LATS, "cable_row" to ROWS, "machine_row" to ROWS, "barbell_row" to ROWS,
        "db_row" to ROWS, "t_bar_row" to ROWS, "deadlift" to HAMSTRINGS, "pullup" to LATS,
        "assisted_pullup" to LATS, "inverted_row" to ROWS, "straight_arm_pulldown" to LATS,
        "back_extension" to HAMSTRINGS,
        // legs
        "leg_press" to QUADS, "squat" to QUADS, "hack_squat" to QUADS, "goblet_squat" to QUADS,
        "bodyweight_squat" to QUADS, "romanian_deadlift" to HAMSTRINGS, "db_romanian_deadlift" to HAMSTRINGS,
        "hip_thrust" to GLUTES, "glute_bridge" to GLUTES, "lunges" to QUADS, "bulgarian_split_squat" to QUADS,
        "leg_extension" to QUADS, "leg_curl" to HAMSTRINGS, "calf_raise" to CALVES,
        "bodyweight_calf_raise" to CALVES, "hip_abduction" to GLUTES,
        // shoulders / arms
        "shoulder_press" to FRONT_DELT, "barbell_ohp" to FRONT_DELT, "machine_shoulder_press" to FRONT_DELT,
        "pike_pushup" to FRONT_DELT, "lateral_raise" to SIDE_DELT, "cable_lateral_raise" to SIDE_DELT,
        "lateral_raise_machine" to SIDE_DELT, "front_raise" to FRONT_DELT, "face_pull" to REAR_DELT,
        "reverse_fly" to REAR_DELT, "reverse_fly_machine" to REAR_DELT, "band_pull_apart" to REAR_DELT,
        "db_shrug" to ROWS, "db_curl" to BICEPS, "hammer_curl" to BICEPS, "barbell_curl" to BICEPS,
        "cable_curl" to BICEPS, "preacher_curl" to BICEPS, "tricep_pushdown" to TRICEPS,
        "tricep_machine" to TRICEPS, "overhead_tricep_extension" to TRICEPS, "skull_crusher" to TRICEPS,
        "bench_dips" to TRICEPS,
        // core
        "plank" to ABS, "side_plank" to ABS, "hanging_leg_raise" to ABS, "lying_leg_raise" to ABS,
        "cable_crunch" to ABS, "crunch_machine" to ABS, "dead_bug" to ABS, "bird_dog" to ABS,
        "ab_wheel" to ABS, "russian_twist" to ABS, "bicycle_crunch" to ABS, "mountain_climbers" to ABS,
        // bands
        "band_chest_press" to CHEST, "band_row" to ROWS, "band_lat_pulldown" to LATS, "band_shoulder_press" to FRONT_DELT,
        "band_lateral_raise" to SIDE_DELT, "band_curl" to BICEPS, "band_tricep_extension" to TRICEPS,
        "band_squat" to QUADS, "band_rdl" to HAMSTRINGS, "band_hip_abduction" to GLUTES,
        // home ("אימון בבית")
        "db_floor_press" to CHEST, "handles_pushup" to CHEST, "chin_up" to LATS, "table_row" to ROWS,
        "trx_row" to ROWS, "kb_row" to ROWS, "ball_back_extension" to HAMSTRINGS,
        "kb_goblet_squat" to QUADS, "kb_swing" to HAMSTRINGS, "bw_bulgarian_split_squat" to QUADS,
        "reverse_lunge" to QUADS, "step_up" to QUADS, "ball_hamstring_curl" to HAMSTRINGS,
        "kb_press" to FRONT_DELT, "prone_t_raise" to REAR_DELT, "trx_rear_fly" to REAR_DELT,
        "trx_curl" to BICEPS, "diamond_pushup" to TRICEPS, "ball_crunch" to ABS, "jump_rope" to CALVES,
        // endurance
        "run_intervals" to MOBILITY, "yoga_session" to MOBILITY,
        "walk" to MOBILITY, "burpees" to QUADS, "jumping_jacks" to CALVES, "high_knees" to QUADS,
        // yoga / mobility
        "cat_cow" to MOBILITY, "cobra" to MOBILITY, "thoracic_rotation" to MOBILITY, "child_pose" to MOBILITY,
        "downward_dog" to MOBILITY, "hip_flexor_stretch" to MOBILITY, "pigeon_pose" to MOBILITY,
        "hamstring_stretch" to MOBILITY, "worlds_greatest_stretch" to MOBILITY
    )

    /** Target key of a catalog exercise, or null for unknown / custom ids. */
    fun targetOf(exerciseId: String): String? = catalogTargets[exerciseId]

    /**
     * Exercises whose muscle split differs from their target key's generic split
     * (e.g. the deadlift is filed under hamstrings but is mostly a back/glute lift).
     */
    private val exerciseMuscleOverrides: Map<String, List<Pair<MuscleGroup, Float>>> = mapOf(
        "deadlift" to listOf(MuscleGroup.LOWER_BACK to 0.8f, MuscleGroup.GLUTES to 0.8f, MuscleGroup.HAMSTRINGS to 0.8f, MuscleGroup.UPPER_BACK to 0.6f, MuscleGroup.QUADS to 0.4f, MuscleGroup.FOREARMS to 0.4f),
        "back_extension" to listOf(MuscleGroup.LOWER_BACK to 1.0f, MuscleGroup.GLUTES to 0.4f, MuscleGroup.HAMSTRINGS to 0.4f),
        "ball_back_extension" to listOf(MuscleGroup.LOWER_BACK to 1.0f, MuscleGroup.GLUTES to 0.4f, MuscleGroup.HAMSTRINGS to 0.3f),
        "kb_swing" to listOf(MuscleGroup.GLUTES to 1.0f, MuscleGroup.HAMSTRINGS to 0.8f, MuscleGroup.LOWER_BACK to 0.5f, MuscleGroup.FOREARMS to 0.3f),
        "jump_rope" to listOf(MuscleGroup.CALVES to 0.6f, MuscleGroup.QUADS to 0.2f),
        "dips" to listOf(MuscleGroup.CHEST to 0.8f, MuscleGroup.TRICEPS to 0.8f, MuscleGroup.FRONT_DELT to 0.3f),
        "leg_extension" to listOf(MuscleGroup.QUADS to 1.0f),
        "leg_curl" to listOf(MuscleGroup.HAMSTRINGS to 1.0f),
        "hip_abduction" to listOf(MuscleGroup.GLUTES to 0.7f),
        "front_raise" to listOf(MuscleGroup.FRONT_DELT to 1.0f),
        "db_shrug" to listOf(MuscleGroup.UPPER_BACK to 1.0f, MuscleGroup.FOREARMS to 0.3f),
        "hammer_curl" to listOf(MuscleGroup.BICEPS to 0.8f, MuscleGroup.FOREARMS to 0.7f),
        "bird_dog" to listOf(MuscleGroup.ABS to 0.6f, MuscleGroup.LOWER_BACK to 0.6f, MuscleGroup.GLUTES to 0.3f),
        "mountain_climbers" to listOf(MuscleGroup.ABS to 0.8f, MuscleGroup.QUADS to 0.3f),
        "burpees" to listOf(MuscleGroup.QUADS to 0.6f, MuscleGroup.CHEST to 0.5f, MuscleGroup.ABS to 0.4f, MuscleGroup.FRONT_DELT to 0.3f),
        "jumping_jacks" to listOf(MuscleGroup.CALVES to 0.5f, MuscleGroup.FRONT_DELT to 0.3f),
        "high_knees" to listOf(MuscleGroup.QUADS to 0.5f, MuscleGroup.ABS to 0.4f, MuscleGroup.CALVES to 0.3f)
    )

    /**
     * Muscle split of an exercise: a per-exercise override when one exists, otherwise the split of
     * its catalog target, otherwise the split of [customTarget] (the custom exercise's own target key).
     */
    fun musclesOf(exerciseId: String, customTarget: String = ""): List<Pair<MuscleGroup, Float>> {
        exerciseMuscleOverrides[exerciseId]?.let { return it }
        val key = catalogTargets[exerciseId] ?: customTarget
        return targetToMuscles(key)
    }
}

/** The six regions of the radar chart. */
enum class MuscleRegion(val nameHe: String) {
    BACK("גב"),
    CHEST("חזה"),
    CORE("ליבה"),
    LEGS("רגליים"),
    SHOULDERS("כתפיים"),
    ARMS("ידיים")
}

/**
 * The 14 muscle groups of the body chart (res/drawable-nodpi/muscle_map.png).
 * [chartIndex] is the palette index of the group in that PNG (0 = transparent, 1 = body, 2 = outline, 3.. = groups).
 */
enum class MuscleGroup(val chartIndex: Int, val nameHe: String, val region: MuscleRegion) {
    CHEST(3, "חזה", MuscleRegion.CHEST),
    ABS(4, "בטן", MuscleRegion.CORE),
    FRONT_DELT(5, "כתף קדמית", MuscleRegion.SHOULDERS),
    BICEPS(6, "יד קדמית", MuscleRegion.ARMS),
    FOREARMS(7, "אמות", MuscleRegion.ARMS),
    QUADS(8, "ארבע ראשי", MuscleRegion.LEGS),
    CALVES(9, "תאומים", MuscleRegion.LEGS),
    UPPER_BACK(10, "גב עליון", MuscleRegion.BACK),
    LATS(11, "גב רחב", MuscleRegion.BACK),
    REAR_DELT(12, "כתף אחורית", MuscleRegion.SHOULDERS),
    TRICEPS(13, "יד אחורית", MuscleRegion.ARMS),
    GLUTES(14, "ישבן", MuscleRegion.LEGS),
    HAMSTRINGS(15, "ירך אחורית", MuscleRegion.LEGS),
    LOWER_BACK(16, "גב תחתון", MuscleRegion.BACK);

    companion object {
        /** First chart palette index that belongs to a muscle group. */
        const val FIRST_CHART_INDEX = 3

        fun byChartIndex(index: Int): MuscleGroup? = entries.firstOrNull { it.chartIndex == index }

        fun inRegion(region: MuscleRegion): List<MuscleGroup> = entries.filter { it.region == region }
    }
}
