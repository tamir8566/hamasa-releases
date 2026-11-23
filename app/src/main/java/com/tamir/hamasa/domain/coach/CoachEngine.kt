package com.tamir.hamasa.domain.coach

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeRules
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure, deterministic, block-based coach. Same [CoachContext] -> same plan.
 *
 * Exercise selection is STABLE: every muscle has a slot with a fixed, ordered candidate list.
 * Main compound slots always take the first candidate the equipment allows. Accessory slots
 * rotate only between blocks (5 weeks). Inside a block the only ways an exercise changes are a
 * user swap (persisted in Prefs as an override for the rest of the block) or a mode that rules
 * the equipment out ("bodyweight" / "machines").
 *
 * Progression is double progression on the same movement: 5–8 reps, add weight when every set
 * of the last session reached 8. The rules and sources are in docs/coach_logic.md.
 */
object CoachEngine {

    private const val BLOCK_DAYS = 35L          // 5 weeks per block
    private const val MAX_SETS_PER_SESSION = 12 // Tamir: a session is at most 12 working sets
    private const val REPS_MIN = 5
    private const val REPS_MAX = 8
    private const val BW_REPS_MIN = 8
    private const val BW_REPS_MAX = 15
    private const val HOLD_MIN = 30
    private const val HOLD_MAX = 45
    private const val REST_COMPOUND = 150
    private const val REST_COMPOUND_STRENGTH = 180
    private const val REST_ACCESSORY = 75
    private const val REST_ACCESSORY_SHORT = 60

    // ----------------------------------------------------------------- slots

    /**
     * One muscle slot. [candidates] are ordered by preference (Tamir's familiar lifts first).
     * [rotates] = accessory slot that may move to the next candidate when the block changes.
     * [target] = the key custom exercises use to join this slot (see [ExerciseTargets]).
     */
    private enum class Slot(val branch: Branch, val target: String, val rotates: Boolean, val candidates: List<String>) {
        CHEST_MAIN(Branch.CHEST, ExerciseTargets.CHEST, false, listOf("bench_press", "db_bench_press", "chest_press_machine", "pushup")),
        CHEST_SECOND(Branch.CHEST, ExerciseTargets.UPPER_CHEST, true, listOf("incline_db_press", "chest_fly", "incline_bench_press", "cable_crossover", "db_fly", "decline_pushup", "dips")),
        BACK_VERT(Branch.BACK, ExerciseTargets.LATS, false, listOf("lat_pulldown", "assisted_pullup", "pullup")),
        BACK_HORIZ(Branch.BACK, ExerciseTargets.ROWS, false, listOf("cable_row", "machine_row", "db_row", "barbell_row", "t_bar_row", "inverted_row")),
        SHOULDER_PRESS(Branch.SHOULDERS_ARMS, ExerciseTargets.FRONT_DELT, false, listOf("shoulder_press", "machine_shoulder_press", "barbell_ohp", "pike_pushup")),
        SHOULDER_SIDE(Branch.SHOULDERS_ARMS, ExerciseTargets.SIDE_DELT, true, listOf("lateral_raise", "cable_lateral_raise", "lateral_raise_machine")),
        SHOULDER_REAR(Branch.SHOULDERS_ARMS, ExerciseTargets.REAR_DELT, true, listOf("face_pull", "reverse_fly", "reverse_fly_machine", "band_pull_apart")),
        BICEPS(Branch.SHOULDERS_ARMS, ExerciseTargets.BICEPS, true, listOf("db_curl", "hammer_curl", "cable_curl", "barbell_curl", "preacher_curl")),
        TRICEPS(Branch.SHOULDERS_ARMS, ExerciseTargets.TRICEPS, true, listOf("tricep_pushdown", "overhead_tricep_extension", "tricep_machine", "skull_crusher", "bench_dips")),
        ABS(Branch.CORE, ExerciseTargets.ABS, true, listOf("plank", "cable_crunch", "hanging_leg_raise", "crunch_machine", "lying_leg_raise", "dead_bug")),
        QUADS(Branch.LEGS, ExerciseTargets.QUADS, false, listOf("leg_press", "hack_squat", "squat", "goblet_squat", "bodyweight_squat")),
        HAMS(Branch.LEGS, ExerciseTargets.HAMSTRINGS, false, listOf("leg_curl", "romanian_deadlift", "db_romanian_deadlift", "glute_bridge")),
        GLUTES(Branch.LEGS, ExerciseTargets.GLUTES, true, listOf("hip_thrust", "hip_abduction", "lunges", "glute_bridge", "bulgarian_split_squat")),
        CALVES(Branch.LEGS, ExerciseTargets.CALVES, false, listOf("calf_raise", "bodyweight_calf_raise"))
    }

    /**
     * Band versions that join a slot only while the equipment is restricted (mode "bodyweight" or a note
     * "גומייה / משקל גוף / בבית"). Kept out of [Slot.candidates] so the regular rotation never changes.
     */
    private val restrictedExtras: Map<Slot, List<String>> = mapOf(
        Slot.CHEST_MAIN to listOf("band_chest_press"),
        Slot.BACK_VERT to listOf("band_lat_pulldown"),
        Slot.BACK_HORIZ to listOf("band_row"),
        Slot.SHOULDER_PRESS to listOf("band_shoulder_press"),
        Slot.SHOULDER_SIDE to listOf("band_lateral_raise"),
        Slot.BICEPS to listOf("band_curl"),
        Slot.TRICEPS to listOf("band_tricep_extension"),
        Slot.QUADS to listOf("band_squat"),
        Slot.HAMS to listOf("band_rdl"),
        Slot.GLUTES to listOf("band_hip_abduction")
    )

    /**
     * "אימון בבית" (HomeGear): the candidate list of every slot on a home plan, in preference order. Replaces the gym
     * list (plus the band extras) while [CoachConstraints.homeGear] is set; [HomeGear.allows] then keeps only what his
     * gear makes possible. Fixed slots take the first possible one, rotating slots rotate between blocks as usual.
     * Slots with no possible exercise are skipped (and their sets go to the partner slot, see [homePlan]).
     */
    private val homeCandidates: Map<Slot, List<String>> = mapOf(
        Slot.CHEST_MAIN to listOf("db_bench_press", "db_floor_press", "handles_pushup", "pushup", "band_chest_press"),
        Slot.CHEST_SECOND to listOf("incline_db_press", "db_fly", "decline_pushup", "dips"),
        Slot.BACK_VERT to listOf("pullup", "chin_up", "band_lat_pulldown"),
        Slot.BACK_HORIZ to listOf("db_row", "kb_row", "trx_row", "band_row", "table_row"),
        Slot.SHOULDER_PRESS to listOf("shoulder_press", "kb_press", "band_shoulder_press", "pike_pushup"),
        Slot.SHOULDER_SIDE to listOf("lateral_raise", "band_lateral_raise"),
        Slot.SHOULDER_REAR to listOf("reverse_fly", "band_pull_apart", "trx_rear_fly", "prone_t_raise"),
        Slot.BICEPS to listOf("db_curl", "hammer_curl", "band_curl", "trx_curl"),
        Slot.TRICEPS to listOf("overhead_tricep_extension", "band_tricep_extension", "bench_dips", "diamond_pushup"),
        Slot.ABS to listOf("plank", "lying_leg_raise", "dead_bug", "hanging_leg_raise", "bicycle_crunch", "ball_crunch"),
        Slot.QUADS to listOf("goblet_squat", "kb_goblet_squat", "bulgarian_split_squat", "bw_bulgarian_split_squat", "band_squat", "bodyweight_squat"),
        Slot.HAMS to listOf("db_romanian_deadlift", "kb_swing", "band_rdl", "ball_hamstring_curl", "glute_bridge"),
        Slot.GLUTES to listOf("lunges", "bulgarian_split_squat", "step_up", "reverse_lunge", "band_hip_abduction", "glute_bridge"),
        Slot.CALVES to listOf("bodyweight_calf_raise")
    )

    /** Home gear of today (the sheet or a note "בבית"); empty = not a home plan. */
    private fun homeGearOf(ctx: CoachContext): Set<String> = ctx.constraints?.homeGear ?: emptySet()

    /** Equipment is limited today (bands / body weight / one kind of equipment). */
    private fun equipmentRestricted(ctx: CoachContext): Boolean =
        ctx.modeId == "bodyweight" || (ctx.constraints?.allowedEquipment?.isNotEmpty() == true)

    /** Catalog ids of a slot: its candidates, plus the band versions while the equipment is restricted. */
    private fun catalogIdsOf(slot: Slot, ctx: CoachContext): List<String> = when {
        homeGearOf(ctx).isNotEmpty() -> homeCandidates[slot] ?: emptyList()
        equipmentRestricted(ctx) -> slot.candidates + (restrictedExtras[slot] ?: emptyList())
        else -> slot.candidates
    }

    // ------------------------------------------------------------ lookup

    /** Catalog first, then the user's custom exercises. Null for unknown ids. */
    private fun lookup(id: String, ctx: CoachContext): ExerciseEntity? {
        val e = ExerciseCatalog.byId(id)
        if (e != null) return e
        for (c in ctx.customExercises) if (c.id == id) return c
        return null
    }

    private fun customOf(id: String, ctx: CoachContext): ExerciseEntity? {
        for (c in ctx.customExercises) if (c.id == id) return c
        return null
    }

    /** Custom exercises that join [target]: preferred ones first, then by name (stable order). */
    private fun customsFor(target: String, ctx: CoachContext): List<ExerciseEntity> =
        ctx.customExercises.filter { it.target == target }.sortedWith(compareBy<ExerciseEntity>({ !it.preferred }, { it.nameHe }))

    /** A session template: Hebrew title + slots with their working-set count. */
    private class DayTemplate(val titleHe: String, val slots: List<Pair<Slot, Int>>)

    private val upperDay = DayTemplate(
        "אימון פלג גוף עליון",
        listOf(
            Slot.CHEST_MAIN to 2, Slot.CHEST_SECOND to 1,
            Slot.BACK_VERT to 2, Slot.BACK_HORIZ to 2,
            Slot.SHOULDER_PRESS to 3, Slot.SHOULDER_SIDE to 3, Slot.SHOULDER_REAR to 3,
            Slot.TRICEPS to 2, Slot.BICEPS to 1, Slot.ABS to 1
        )
    )

    private val lowerDay = DayTemplate(
        "אימון פלג גוף תחתון",
        listOf(Slot.QUADS to 2, Slot.HAMS to 2, Slot.GLUTES to 2, Slot.CALVES to 2, Slot.ABS to 1)
    )

    private val fullA = DayTemplate(
        "אימון גוף מלא א",
        listOf(
            Slot.CHEST_MAIN to 3, Slot.BACK_HORIZ to 2, Slot.QUADS to 2, Slot.HAMS to 1,
            Slot.SHOULDER_SIDE to 2, Slot.TRICEPS to 1, Slot.ABS to 1
        )
    )

    private val fullB = DayTemplate(
        "אימון גוף מלא ב",
        listOf(
            Slot.SHOULDER_PRESS to 3, Slot.BACK_VERT to 2, Slot.GLUTES to 2, Slot.QUADS to 1,
            Slot.SHOULDER_REAR to 2, Slot.BICEPS to 1, Slot.CALVES to 1, Slot.ABS to 1
        )
    )

    private val pushDay = DayTemplate(
        "אימון דחיפה",
        listOf(
            Slot.CHEST_MAIN to 2, Slot.CHEST_SECOND to 1, Slot.SHOULDER_PRESS to 3,
            Slot.SHOULDER_SIDE to 3, Slot.TRICEPS to 2, Slot.ABS to 1
        )
    )

    private val pullDay = DayTemplate(
        "אימון משיכה",
        listOf(Slot.BACK_VERT to 2, Slot.BACK_HORIZ to 2, Slot.SHOULDER_REAR to 3, Slot.BICEPS to 1, Slot.ABS to 1)
    )

    private val legsDay = DayTemplate(
        "אימון רגליים",
        listOf(Slot.QUADS to 2, Slot.HAMS to 2, Slot.GLUTES to 2, Slot.CALVES to 2, Slot.ABS to 1)
    )

    private enum class Split { FULL, UPPER_LOWER, PPL }

    private fun daysOf(split: Split): List<DayTemplate> = when (split) {
        Split.FULL -> listOf(fullA, fullB)
        Split.UPPER_LOWER -> listOf(upperDay, lowerDay)
        Split.PPL -> listOf(pushDay, pullDay, legsDay)
    }

    // ----------------------------------------------------------------- block

    /** Block id: 5-week windows since the first finished session. Public so the repository can stamp overrides. */
    fun blockId(ctx: CoachContext): Int {
        if (ctx.todayEpochDay <= 0L || ctx.programStartEpochDay <= 0L) return 0
        val days = (ctx.todayEpochDay - ctx.programStartEpochDay).coerceAtLeast(0L)
        return (days / BLOCK_DAYS).toInt()
    }

    private fun daysSinceStart(ctx: CoachContext): Long {
        if (ctx.todayEpochDay <= 0L || ctx.programStartEpochDay <= 0L) return 0L
        return (ctx.todayEpochDay - ctx.programStartEpochDay).coerceAtLeast(0L)
    }

    /**
     * Tamir's rule: at most [MAX_SETS_PER_SESSION] working sets per session. Upper-body and full-body
     * days would need 13–20 sets to honour his per-muscle counts, so the program is always
     * push / pull / legs (12 / 9 / 9 sets), rotating by finished gym sessions regardless of frequency.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun splitFor(ctx: CoachContext): Split = Split.PPL

    /**
     * Enforces the per-session cap after focus bonuses and mode effects: trims one set at a time from the
     * accessory exercise that currently has the most sets (never below 1; compounds and mobility holds are
     * left alone), then, if still over, drops the last accessory. Mobility holds don't count as working sets.
     * The cap is [MAX_SETS_PER_SESSION], or 10 at difficulty "קל" ([Difficulty.maxSets]).
     */
    private fun capSets(exercises: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        fun counts(list: List<PlannedExercise>) = list.filter { !isMobility(it.exerciseId, ctx) }.sumOf { it.sets }
        val cap = minOf(MAX_SETS_PER_SESSION, ctx.difficulty.maxSets)
        var guard = 0
        while (counts(exercises) > cap && guard < 40) {
            guard++
            val idx = exercises.indices
                .filter { i -> val e = exercises[i]; !isCompound(e.exerciseId, ctx) && !isMobility(e.exerciseId, ctx) && e.sets > 1 }
                .maxByOrNull { i -> exercises[i].sets }
            if (idx != null) {
                exercises[idx] = exercises[idx].copy(sets = exercises[idx].sets - 1)
                continue
            }
            val drop = exercises.indexOfLast { !isCompound(it.exerciseId, ctx) && !isMobility(it.exerciseId, ctx) }
            if (drop < 0) break
            exercises.removeAt(drop)
        }
        return exercises
    }

    private fun focusBranch(focus: Focus): Branch? = when (focus) {
        Focus.BRANCH_CHEST -> Branch.CHEST
        Focus.BRANCH_BACK -> Branch.BACK
        Focus.BRANCH_LEGS -> Branch.LEGS
        Focus.BRANCH_SHOULDERS_ARMS -> Branch.SHOULDERS_ARMS
        Focus.BRANCH_CORE -> Branch.CORE
        else -> null
    }

    private fun fmtKg(v: Float): String =
        if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else String.format("%.1f", v)

    // ------------------------------------------------------------- equipment

    /** Which exercises the mode allows (equipment availability). */
    private fun allowedByMode(modeId: String): (ExerciseEntity) -> Boolean = when (modeId) {
        "bodyweight" -> { e: ExerciseEntity -> e.equipment == "body" || e.equipment == "band" }
        "machines" -> { e: ExerciseEntity -> e.equipment == "machine" || e.equipment == "cable" }
        else -> { _: ExerciseEntity -> true }
    }

    /**
     * Equipment filter of today: a note's equipment ("גומייה ומשקל גוף", "בבית") says what is really available,
     * so it replaces the mode's equipment rule; otherwise the mode decides.
     */
    private fun allowedBy(ctx: CoachContext): (ExerciseEntity) -> Boolean {
        // at home: the home equipment model (a pull-up needs a bar, a bench press needs a bench)
        val gear = homeGearOf(ctx)
        if (gear.isNotEmpty()) return { e: ExerciseEntity -> HomeGear.allows(e, gear) }
        val eq: Set<String> = ctx.constraints?.allowedEquipment ?: emptySet()
        if (eq.isEmpty()) return allowedByMode(ctx.modeId)
        return { e: ExerciseEntity -> e.equipment in eq }
    }

    /**
     * Candidates of a slot the mode allows, in preference order:
     * preferred custom exercises, then the catalog list, then the other custom exercises of the target.
     */
    private fun candidatesOf(slot: Slot, ctx: CoachContext): List<ExerciseEntity> {
        val ok = allowedBy(ctx)
        val customs = customsFor(slot.target, ctx)
        val preferred = customs.filter { it.preferred }
        val rest = customs.filter { !it.preferred }
        val catalog = catalogIdsOf(slot, ctx).mapNotNull { ExerciseCatalog.byId(it) }
        val list = (preferred + catalog + rest).filter(ok).filter { !blockedBy(it, ctx) }
        return preferEquipmentFirst(list, ctx)
    }

    // ------------------------------------------------------- note constraints (stage 2 G)

    /** The exercise is ruled out by the active note constraints. */
    private fun blockedBy(e: ExerciseEntity, ctx: CoachContext): Boolean = ctx.constraints?.blocks(e) == true

    /** Public check for callers outside the engine (emergency plan, live swaps). Unknown ids are never blocked. */
    fun isBlocked(exerciseId: String, ctx: CoachContext): Boolean {
        val e = lookup(exerciseId, ctx) ?: return false
        return blockedBy(e, ctx)
    }

    /** Stable reorder: candidates with a preferred equipment first, the rest keep their order. */
    private fun preferEquipmentFirst(list: List<ExerciseEntity>, ctx: CoachContext): List<ExerciseEntity> {
        val pref = ctx.constraints?.preferEquipment ?: return list
        if (pref.isEmpty()) return list
        return list.sortedBy { if (it.equipment in pref) 0 else 1 }
    }

    /** Entity of an id (catalog or the user's own), for callers that explain the plan. */
    fun exerciseOf(exerciseId: String, ctx: CoachContext): ExerciseEntity? = lookup(exerciseId, ctx)

    /**
     * reduce_sets_pct: removes that share of today's working sets (rounded), accessory sets first (largest first,
     * never below 1, then whole accessories from the end), compounds only after that and never below 1 set.
     * Never touches weight or reps.
     */
    private fun reduceSets(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        val pct = (ctx.constraints?.reducePct ?: 0).coerceIn(0, 50)
        if (pct <= 0) return input
        val exercises = input
        fun total(): Int = exercises.filter { !isMobility(it.exerciseId, ctx) }.sumOf { it.sets }
        val start = total()
        val remove = (start * pct + 50) / 100
        if (remove <= 0) return exercises
        val goal = (start - remove).coerceAtLeast(1)
        var guard = 0
        while (total() > goal && guard < 60) {
            guard++
            val acc = exercises.indices
                .filter { i -> val e = exercises[i]; !isCompound(e.exerciseId, ctx) && !isMobility(e.exerciseId, ctx) && e.sets > 1 }
                .maxByOrNull { i -> exercises[i].sets }
            if (acc != null) {
                exercises[acc] = trimOneSet(exercises[acc])
                continue
            }
            val dropAcc = exercises.indexOfLast { !isCompound(it.exerciseId, ctx) && !isMobility(it.exerciseId, ctx) }
            if (dropAcc >= 0 && exercises.size > 1) {
                val gone = exercises.removeAt(dropAcc).exerciseId
                for (k in exercises.indices) if (exercises[k].supersetWith == gone) exercises[k] = exercises[k].copy(supersetWith = null)
                continue
            }
            val comp = exercises.indices
                .filter { i -> val e = exercises[i]; isCompound(e.exerciseId, ctx) && e.sets > 1 }
                .maxByOrNull { i -> exercises[i].sets }
            if (comp == null) break
            exercises[comp] = trimOneSet(exercises[comp])
        }
        return exercises
    }

    /** Deload week: every working exercise keeps ~60% of its sets (rounded, never below 1). Mobility holds stay. */
    private fun deloadSets(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> =
        input.map { p ->
            if (isMobility(p.exerciseId, ctx)) p
            else {
                val n = Deload.sets(p.sets)
                if (n >= p.sets) p else if (p.perSet.isEmpty()) p.copy(sets = n) else p.copy(sets = n, perSet = p.perSet.take(n))
            }
        }.toMutableList()

    private fun trimOneSet(p: PlannedExercise): PlannedExercise {
        val n = (p.sets - 1).coerceAtLeast(1)
        return if (p.perSet.isEmpty()) p.copy(sets = n) else p.copy(sets = n, perSet = p.perSet.take(n))
    }

    /**
     * The day of the rotation, moved forward only when note constraints leave less than half of its main slots
     * (everything but abs) with an allowed exercise. Deterministic; without constraints it is always the rotation day.
     */
    private fun pickDay(days: List<DayTemplate>, ctx: CoachContext): DayTemplate {
        val start = ctx.gymSessionsDone % days.size
        if (ctx.constraints == null) return days[start]
        for (k in days.indices) {
            val d = days[(start + k) % days.size]
            val main = d.slots.filter { it.first != Slot.ABS }
            val usable = main.count { candidatesOf(it.first, ctx).isNotEmpty() }
            if (main.isEmpty() || usable * 2 >= main.size) return d
        }
        return days[start]
    }

    /** "ההודעה לג'ים ברו" part of the coach line; "" without constraints. */
    private fun constraintLine(ctx: CoachContext, dayMoved: Boolean): String {
        val c = ctx.constraints ?: return ""
        val parts = ArrayList<String>()
        val prefs = c.preferenceLabelHe()
        parts.add(
            when {
                c.subjectHe.isNotEmpty() -> "התוכנית מותאמת ${c.subjectHe}"
                prefs.isNotEmpty() && c.untilEpochDay <= ctx.todayEpochDay -> "התוכנית מותאמת להיום: $prefs"
                prefs.isNotEmpty() -> "התוכנית מותאמת: $prefs"
                else -> "התוכנית מותאמת להודעה שלך"
            }
        )
        if (dayMoved) parts.add("היום מדלגים ליום הבא בסבב")
        if (c.holdsLoad) parts.add("בלי עליות משקל בינתיים")
        return parts.joinToString(", ")
    }

    /**
     * Deterministic choice for a slot: fixed slots take the first allowed candidate, rotating slots
     * take candidate [block % size]. A preferred custom exercise at the head of the list wins even in a
     * rotating slot (that is what "preferred" means). A user override for the chosen base id is honoured
     * when the override was recorded in this block and the mode allows it. Ids already taken are skipped.
     */
    private fun resolve(slot: Slot, ctx: CoachContext, block: Int, taken: Set<String>): ExerciseEntity? {
        val cands = candidatesOf(slot, ctx)
        if (cands.isEmpty()) return null
        val ok = allowedBy(ctx)
        val overrides = if (ctx.overridesBlock == block) ctx.exerciseOverrides else emptyMap()
        val headIsPreferred = cands[0].isCustom && cands[0].preferred
        val start = if (slot.rotates && !headIsPreferred) block % cands.size else 0
        var i = 0
        while (i < cands.size) {
            val base = cands[(start + i) % cands.size]
            val overrideId = overrides[base.id]
            val chosen = if (overrideId != null) lookup(overrideId, ctx) else null
            val e = if (chosen != null && ok(chosen) && !blockedBy(chosen, ctx)) chosen else base
            if (e.id !in taken) return e
            i++
        }
        return null
    }

    /** Slot of a catalog id (by candidate list) or of a custom id (by target key). */
    private fun slotOf(exerciseId: String, ctx: CoachContext): Slot? {
        val byCatalog = Slot.values().firstOrNull {
            exerciseId in it.candidates || restrictedExtras[it]?.contains(exerciseId) == true || homeCandidates[it]?.contains(exerciseId) == true
        }
        if (byCatalog != null) return byCatalog
        val custom = customOf(exerciseId, ctx) ?: return null
        return Slot.values().firstOrNull { it.target == custom.target }
    }

    // --------------------------------------------------------------- weights

    /** Step the equipment allows. */
    private fun stepOf(e: ExerciseEntity): Float = when (e.equipment) {
        "dumbbell" -> 1f
        "kettlebell" -> 4f // kettlebells come in 4 kg steps
        else -> 2.5f
    }

    /** Weight added after a full 5–8 progression: lower body +5, upper +2.5, dumbbells +1. */
    private fun increment(e: ExerciseEntity): Float = when {
        e.equipment == "dumbbell" -> 1f
        e.equipment == "kettlebell" -> 4f
        e.branch == Branch.LEGS || e.id == "deadlift" -> 5f
        else -> 2.5f
    }

    private fun roundTo(v: Float, step: Float): Float = ((v / step).roundToInt() * step).coerceAtLeast(step)

    /** Starting weight for an exercise never done, from body weight. */
    private fun defaultWeight(e: ExerciseEntity, bw: Float, female: Boolean = false): Float {
        if (e.unit != "kg") return 0f
        val ratio = when (e.id) {
            "bench_press" -> 0.40f
            "incline_bench_press" -> 0.32f
            "db_bench_press", "incline_db_press", "db_floor_press" -> 0.16f
            "kb_goblet_squat" -> 0.16f
            "kb_row" -> 0.16f
            "kb_press" -> 0.10f
            "chest_press_machine" -> 0.40f
            "chest_fly", "cable_crossover" -> 0.22f
            "db_fly" -> 0.08f
            "lat_pulldown" -> 0.45f
            "cable_row", "machine_row" -> 0.45f
            "barbell_row" -> 0.35f
            "db_row" -> 0.18f
            "t_bar_row" -> 0.30f
            "deadlift" -> 0.60f
            "assisted_pullup" -> 0.35f
            "straight_arm_pulldown" -> 0.18f
            "leg_press" -> 1.00f
            "squat" -> 0.45f
            "hack_squat" -> 0.50f
            "goblet_squat" -> 0.16f
            "romanian_deadlift" -> 0.40f
            "db_romanian_deadlift" -> 0.16f
            "hip_thrust" -> 0.50f
            "lunges", "bulgarian_split_squat" -> 0.10f
            "leg_extension", "leg_curl" -> 0.30f
            "calf_raise" -> 0.50f
            "hip_abduction" -> 0.35f
            "shoulder_press" -> 0.12f
            "barbell_ohp" -> 0.25f
            "machine_shoulder_press" -> 0.30f
            "lateral_raise", "front_raise", "reverse_fly" -> 0.06f
            "cable_lateral_raise" -> 0.05f
            "lateral_raise_machine", "reverse_fly_machine" -> 0.25f
            "face_pull" -> 0.18f
            "db_shrug" -> 0.20f
            "db_curl", "hammer_curl" -> 0.09f
            "barbell_curl" -> 0.18f
            "cable_curl", "preacher_curl" -> 0.18f
            "tricep_pushdown", "tricep_machine" -> 0.20f
            "overhead_tricep_extension" -> 0.12f
            "skull_crusher" -> 0.15f
            "cable_crunch", "crunch_machine" -> 0.25f
            else -> when (e.equipment) {
                "dumbbell" -> if (e.isCompound) 0.15f else 0.08f
                "barbell" -> if (e.isCompound) 0.35f else 0.15f
                else -> if (e.isCompound) 0.40f else 0.20f
            }
        }
        // the table is built on male ratios; a woman's upper body starts at about 60% of it (legs keep the table)
        val upper = e.branch == Branch.CHEST || e.branch == Branch.BACK || e.branch == Branch.SHOULDERS_ARMS
        val scaled = if (female && upper) ratio * 0.6f else ratio
        return roundTo(bw * scaled, stepOf(e))
    }

    /**
     * Weight, target reps and Hebrew note for one exercise. [rule] names the progression rule that decided it
     * (used by the "למה?" explanation): first, up, down, same, hold_constraint, bw_first, bw_top, bw_next,
     * hold_first, hold_top, hold_next.
     */
    private class Target(val weightKg: Float, val reps: Int, val note: String, val rule: String = "")

    /** The progression rule that sets today's target of [exerciseId] (see [Target]); "" for unknown ids. */
    fun progressionRule(exerciseId: String, ctx: CoachContext): String {
        val e = lookup(exerciseId, ctx) ?: return ""
        return cappedTarget(e, ctx).rule
    }

    /**
     * The (weight, target reps) the progression rule gives [exerciseId] in [ctx] (null for unknown ids). Used by the
     * post-workout coach summary ("בפעם הבאה: ..."): with the history that includes the session just finished, this is
     * exactly what the next plan will ask for. Weight is 0 for bodyweight and holds.
     */
    fun nextTarget(exerciseId: String, ctx: CoachContext): Pair<Float, Int>? {
        val e = lookup(exerciseId, ctx) ?: return null
        val t = cappedTarget(e, ctx)
        return Pair(t.weightKg, t.reps)
    }

    /** [target], then the home weight limit (dumbbells / kettlebell he has, see [homeCapped]). */
    private fun cappedTarget(e: ExerciseEntity, ctx: CoachContext): Target = homeCapped(e, ctx, target(e, ctx))

    /**
     * At home the weight is what he owns. When the progression asks for more than his heaviest dumbbell (or his
     * kettlebell), the weight stays at that limit and the work moves to reps: 8–12 (a 10–15 accessory range keeps
     * its own), one more than the weakest set of the last session at that weight. At the top of the range: a slow
     * three-second lowering, and a harder version is named when his gear allows one. A fixed pair / kettlebell
     * heavier than the target is used as it is, at the low end of the range. Rules "home_cap" / "home_fixed".
     */
    private fun homeCapped(e: ExerciseEntity, ctx: CoachContext, t: Target): Target {
        val c = ctx.constraints ?: return t
        if (c.homeGear.isEmpty() || e.unit != "kg") return t
        val limit = when (e.equipment) {
            "dumbbell" -> c.dumbbellMaxKg
            "kettlebell" -> c.kettlebellKg
            else -> 0f
        }
        if (limit <= 0f) return t
        val fixed = e.equipment == "kettlebell" || c.dumbbellFixed
        val tooHeavy = t.weightKg > limit + 0.01f
        if (!tooHeavy) {
            if (!fixed || abs(t.weightKg - limit) < 0.01f) return t
            val lo = repRange(e.isCompound, ctx.focus).first
            return Target(limit, lo, HomeGear.heavyFixedNote(e.equipment, limit, lo), "home_fixed")
        }
        val acc = repRange(false, ctx.focus)
        val capLo = if (!e.isCompound && acc.first > HomeGear.CAP_REPS_MIN) acc.first else HomeGear.CAP_REPS_MIN
        val capHi = if (!e.isCompound && acc.second > HomeGear.CAP_REPS_MAX) acc.second else HomeGear.CAP_REPS_MAX
        val h = ctx.history[e.id]
        val lastReps: List<Int> = when {
            h == null -> emptyList()
            h.lastSetReps.isNotEmpty() -> h.lastSetReps
            else -> listOf(h.lastReps)
        }
        val atLimit = h != null && lastReps.isNotEmpty() && abs(h.lastWeight - limit) < 0.01f
        val minLast = lastReps.minOrNull() ?: 0
        // a deload or a pain note never adds reps on top: same reps as last time at that weight
        val calm = ctx.deload || c.holdsLoad
        val reps = when {
            !atLimit -> capLo
            calm -> minLast.coerceIn(capLo, capHi)
            else -> (minLast + 1).coerceIn(capLo, capHi)
        }
        val atTop = atLimit && minLast >= capHi
        val harder = if (atTop) harderVariation(e, ctx) else null
        return Target(limit, reps, HomeGear.capNote(e.equipment, limit, reps, capHi, atTop, harder?.nameHe), "home_cap")
    }

    /** Harder home versions named when the weight is capped and the reps are at the top. */
    private val harderAtHome: Map<String, List<String>> = mapOf(
        "goblet_squat" to listOf("bulgarian_split_squat", "bw_bulgarian_split_squat"),
        "kb_goblet_squat" to listOf("bulgarian_split_squat", "bw_bulgarian_split_squat"),
        "lunges" to listOf("bulgarian_split_squat"),
        "db_bench_press" to listOf("decline_pushup"),
        "db_floor_press" to listOf("decline_pushup"),
        "shoulder_press" to listOf("pike_pushup"),
        "kb_press" to listOf("pike_pushup"),
        "db_row" to listOf("chin_up", "pullup"),
        "kb_row" to listOf("chin_up", "pullup")
    )

    private fun harderVariation(e: ExerciseEntity, ctx: CoachContext): ExerciseEntity? {
        val ok = allowedBy(ctx)
        for (id in harderAtHome[e.id] ?: emptyList()) {
            val h = lookup(id, ctx) ?: continue
            if (ok(h) && !blockedBy(h, ctx)) return h
        }
        return null
    }

    /** Double progression on the same movement, from the last session of that exercise. */
    private fun target(e: ExerciseEntity, ctx: CoachContext): Target {
        val h = ctx.history[e.id]
        val lastReps: List<Int> = when {
            h == null -> emptyList()
            h.lastSetReps.isNotEmpty() -> h.lastSetReps
            else -> listOf(h.lastReps)
        }
        val minLast: Int = lastReps.minOrNull() ?: 0
        return when (e.unit) {
            "sec" -> when {
                h == null || lastReps.isEmpty() -> Target(0f, HOLD_MIN, "", "hold_first")
                ctx.deload -> Target(0f, minLast.coerceIn(HOLD_MIN, HOLD_MAX), "", "deload")
                minLast >= HOLD_MAX -> Target(0f, HOLD_MAX, "שולטים בהחזקה, מחזיקים יציב", "hold_top")
                // difficulty "קל": smaller steps on holds (+3 s instead of +5 s)
                else -> Target(0f, (minLast + (if (ctx.difficulty == Difficulty.EASY) 3 else 5)).coerceIn(HOLD_MIN, HOLD_MAX), "", "hold_next")
            }
            "reps" -> when {
                h == null || lastReps.isEmpty() -> Target(0f, if (e.isCompound) 10 else 12, "", "bw_first")
                ctx.deload -> Target(0f, minLast.coerceIn(BW_REPS_MIN, BW_REPS_MAX), Deload.NOTE_BODYWEIGHT, "deload")
                minLast >= BW_REPS_MAX -> Target(0f, BW_REPS_MAX, "שולטים בתרגיל, אפשר להחליף לגרסה קשה יותר", "bw_top")
                else -> Target(0f, (minLast + 1).coerceIn(BW_REPS_MIN, BW_REPS_MAX), "", "bw_next")
            }
            else -> weightTarget(e, ctx, h, lastReps, minLast)
        }
    }

    /**
     * Rep range of a weighted exercise. Compounds always keep Tamir's 5–8. The focus changes the range of
     * ACCESSORY work only: weight loss / endurance 10–15, muscle 8–12, strength and the rest 5–8.
     */
    fun repRange(isCompound: Boolean, focus: Focus): Pair<Int, Int> {
        if (isCompound) return Pair(REPS_MIN, REPS_MAX)
        return when (focus) {
            Focus.LOSE_WEIGHT, Focus.ENDURANCE -> Pair(10, 15)
            Focus.BUILD_MUSCLE -> Pair(8, 12)
            else -> Pair(REPS_MIN, REPS_MAX)
        }
    }

    private fun weightTarget(e: ExerciseEntity, ctx: CoachContext, h: ExerciseHistory?, lastReps: List<Int>, minLast: Int): Target {
        val range = repRange(e.isCompound, ctx.focus)
        val lo = range.first
        val hi = range.second
        if (h == null || h.lastWeight <= 0f || lastReps.isEmpty()) {
            // the default weight table assumes 5–8 reps; a higher accessory range starts a little lighter
            val base = defaultWeight(e, ctx.bodyWeightKg, ctx.female)
            val w = when {
                lo >= 10 -> roundTo(base * 0.8f, stepOf(e))
                lo >= 8 -> roundTo(base * 0.9f, stepOf(e))
                else -> base
            }
            return Target(w, lo + 1, "אימון ראשון, מוצאים משקל עבודה", "first")
        }
        val inc = increment(e)
        val step = stepOf(e)
        // accepted deload week: ~10% under the last working weight, the reps he already owns, never an increase
        if (ctx.deload) {
            var dw = roundTo(h.lastWeight * Deload.WEIGHT_FACTOR, step)
            if (dw >= h.lastWeight && h.lastWeight > step) dw = h.lastWeight - step
            return Target(dw, minLast.coerceIn(lo, hi), Deload.NOTE, "deload")
        }
        val allHitTop = lastReps.all { it >= hi }
        val anyBelowMin = lastReps.any { it < lo }
        // a note to the coach about pain / fatigue: the weight stays where it was until the limits expire
        val holdLoad = ctx.constraints?.holdsLoad == true
        // difficulty (domain/coach/Difficulty): "קשה" goes up when every set reached hi-1 and the last set reached hi;
        // "קל" goes up only after a second session in a row at the top of the range with the same weight
        val d = ctx.difficulty
        val hardUp = d == Difficulty.HARD && !allHitTop && lastReps.all { it >= hi - 1 } && lastReps.last() >= hi
        val prevAtTop = h.prevSetReps.isNotEmpty() && h.prevSetReps.all { it >= hi } && abs(h.prevWeight - h.lastWeight) < 0.01f
        val easyWait = d == Difficulty.EASY && allHitTop && !prevAtTop
        return when {
            (allHitTop || hardUp) && holdLoad -> Target(h.lastWeight, hi, "אותו משקל, בלי עלייה עד שההתאמה נגמרת", "hold_constraint")
            easyWait -> Target(h.lastWeight, hi, "אותו משקל. ברמה קלה עולים אחרי אימון שני בקצה העליון", "easy_wait")
            allHitTop -> Target(roundTo(h.lastWeight + inc, step), lo, "מעלים ${fmtKg(inc)} ק\"ג, חוזרים ל-$lo חזרות", "up")
            hardUp -> Target(roundTo(h.lastWeight + inc, step), lo, "מעלים ${fmtKg(inc)} ק\"ג כבר עכשיו (רמה קשה), חוזרים ל-$lo חזרות", "up_hard")
            anyBelowMin -> {
                var w = roundTo(h.lastWeight * 0.95f, step)
                if (w >= h.lastWeight) w = (h.lastWeight - step).coerceAtLeast(step)
                Target(w, lo, "יורדים מעט ומסדרים טכניקה, $lo חזרות נקיות בכל סט", "down")
            }
            else -> Target(h.lastWeight, (minLast + 1).coerceIn(lo, hi), "אותו משקל, חזרה אחת יותר", "same")
        }
    }

    /** Suggested daily calorie goal for a focus, rounded to 50: weight loss ×22, muscle ×30, else ×26 per kg. */
    fun suggestedKcal(focus: Focus, bodyWeightKg: Float): Int {
        val perKg = when (focus) {
            Focus.LOSE_WEIGHT -> 22f
            Focus.BUILD_MUSCLE -> 30f
            else -> 26f
        }
        val raw = bodyWeightKg.coerceAtLeast(30f) * perKg
        return ((raw / 50f).roundToInt() * 50)
    }

    private fun restFor(e: ExerciseEntity, focus: Focus): Int =
        if (e.isCompound) (if (focus == Focus.STRENGTH) REST_COMPOUND_STRENGTH else REST_COMPOUND)
        else (if (focus == Focus.LOSE_WEIGHT || focus == Focus.ENDURANCE) REST_ACCESSORY_SHORT else REST_ACCESSORY)

    /** The coach's rest for [e] at today's difficulty (קל +30 s, קשה −30 s, floors 90 / 45 s). */
    private fun restFor(e: ExerciseEntity, ctx: CoachContext): Int =
        Difficulty.adjustRest(restFor(e, ctx.focus), e.isCompound, ctx.difficulty)

    /**
     * Intensity line of a working exercise: a note's effort cap wins, otherwise the difficulty's reps in reserve.
     * Holds and mobility get none.
     */
    private fun intensityNote(e: ExerciseEntity, ctx: CoachContext): String {
        if (isMobility(e.id, ctx) || e.unit == "sec") return ""
        val rpe = ctx.constraints?.maxRpe
        if (rpe != null && rpe < 10) return "עד מאמץ $rpe מתוך 10, ${10 - rpe} חזרות ברזרבה"
        return ctx.difficulty.rirNoteHe
    }

    private fun planned(e: ExerciseEntity, ctx: CoachContext, sets: Int): PlannedExercise {
        val t = cappedTarget(e, ctx)
        val note = listOf(t.note, intensityNote(e, ctx)).filter { it.isNotEmpty() }.joinToString(". ")
        return PlannedExercise(
            exerciseId = e.id, nameHe = e.nameHe, branch = e.branch,
            sets = sets, reps = t.reps, weightKg = t.weightKg, restSec = restFor(e, ctx), note = note
        )
    }

    /**
     * A note's time cap ("יש לי 30 דקות"): trims until the estimate fits. Accessory sets first (largest first, never
     * below 1), then whole accessories / holds from the end, and only then compound sets (never below 1).
     * Weight and reps never change.
     */
    private fun fitMinutes(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        val cap = ctx.constraints?.maxMinutes ?: return input
        val exercises = input
        var guard = 0
        while (estimateMinutes(exercises, ctx) > cap && guard < 60) {
            guard++
            val acc = exercises.indices
                .filter { i -> val e = exercises[i]; !isCompound(e.exerciseId, ctx) && !isMobility(e.exerciseId, ctx) && e.sets > 1 }
                .maxByOrNull { i -> exercises[i].sets }
            if (acc != null) {
                exercises[acc] = trimOneSet(exercises[acc])
                continue
            }
            val dropAcc = exercises.indexOfLast { !isCompound(it.exerciseId, ctx) }
            if (dropAcc >= 0 && exercises.size > 1) {
                val gone = exercises.removeAt(dropAcc).exerciseId
                for (k in exercises.indices) if (exercises[k].supersetWith == gone) exercises[k] = exercises[k].copy(supersetWith = null)
                continue
            }
            val comp = exercises.indices
                .filter { i -> exercises[i].sets > 1 }
                .maxByOrNull { i -> exercises[i].sets }
            if (comp == null) break
            exercises[comp] = trimOneSet(exercises[comp])
        }
        return exercises
    }

    /**
     * A note's time ("יש לי 50 דקות") is also the time he HAS: when the plan is much shorter, sets are added back
     * (accessories first, up to 4; then compounds, up to 5), never past the session set cap (12 / difficulty) and
     * never past the time. Not on a deload, a short night, pain / fatigue limits, or a "fewer sets" note.
     * Weight and reps never change. Exercises with per-set targets (mode shapes) are left as they are.
     */
    private fun fillMinutes(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        val c = ctx.constraints ?: return input
        val cap = c.maxMinutes ?: return input
        if (ctx.deload || ctx.sleep == SleepBand.SHORT || c.hasLimits || c.reducePct > 0) return input
        val exercises = input
        // he said how long he has: the session cap grows with that time (75 s of work + ~90 s of rest per set, at
        // most 18). The time check below is the real limit; with 120 s here a 50-minute note added only one set.
        val setCap = maxOf(minOf(MAX_SETS_PER_SESSION, ctx.difficulty.maxSets), minOf(18, (cap - 5) * 60 / (WORK_SEC_PER_SET + 90)))
        fun working() = exercises.filter { !isMobility(it.exerciseId, ctx) }.sumOf { it.sets }
        var guard = 0
        while (estimateMinutes(exercises, ctx) < cap - 4 && working() < setCap && guard < 40) {
            guard++
            fun pick(compound: Boolean, max: Int): Int? = exercises.indices
                .filter { i ->
                    val e = exercises[i]
                    e.perSet.isEmpty() && !isMobility(e.exerciseId, ctx) && isCompound(e.exerciseId, ctx) == compound && e.sets < max
                }
                .minByOrNull { i -> exercises[i].sets }
            // a medium night keeps its "one set less": the ceilings drop by one
            val less = if (ctx.sleep == SleepBand.MEDIUM) 1 else 0
            val idx = pick(false, 4 - less) ?: pick(true, 5 - less) ?: break
            val before = exercises[idx]
            exercises[idx] = before.copy(sets = before.sets + 1)
            if (estimateMinutes(exercises, ctx) > cap) {
                exercises[idx] = before
                break
            }
        }
        // still well short: easy cardio at the end fills the rest (conversation pace, never a heavy lift)
        val left = cap - estimateMinutes(exercises, ctx)
        if (left >= 8 && exercises.none { it.exerciseId == FINISHER_ID }) {
            val walk = lookup(FINISHER_ID, ctx)
            if (walk != null && !blockedBy(walk, ctx)) {
                val sec = ((left - 1).coerceAtMost(25)) * 60
                exercises.add(PlannedExercise(walk.id, walk.nameHe, walk.branch, sets = 1, reps = sec, weightKg = 0f, restSec = 0,
                    note = "סיום: הליכה מהירה בשיפוע או אופניים, קצב שעוד אפשר לדבר בו"))
            }
        }
        return exercises
    }

    /**
     * The easy cardio finisher fillMinutes adds at the end of a gym plan (planned rest 0). The live workout knows it
     * by this id inside a GYM session: no rest after it, never a surprise swap or a surprise event target.
     */
    const val FINISHER_ID = "walk"

    /** Time of one working set (not counting rest), measured by Tamir: about 1 to 1.5 minutes. */
    private const val WORK_SEC_PER_SET = 75

    /** One sentence about a non-default difficulty for the coach line; "" for "רגיל". */
    private fun difficultyLine(d: Difficulty): String = when (d) {
        Difficulty.EASY -> "רמה קלה: מנוחות ארוכות יותר, פחות סטים, ועלייה במשקל רק אחרי שני אימונים בקצה העליון."
        Difficulty.HARD -> "רמה קשה: מנוחות קצרות יותר, סטים קרובים לכישלון, ועלייה במשקל כבר כשהסט האחרון בקצה העליון."
        Difficulty.NORMAL -> ""
    }

    /**
     * "רק רגליים": the day of the rotation (counting forward from today's) with the most slots in [branches];
     * null when no day has any.
     */
    private fun bestDayFor(days: List<DayTemplate>, branches: Set<Branch>, ctx: CoachContext): DayTemplate? {
        val start = ctx.gymSessionsDone % days.size
        var best: DayTemplate? = null
        var bestScore = 0
        for (k in days.indices) {
            val d = days[(start + k) % days.size]
            val score = d.slots.count { it.first.branch in branches }
            if (score > bestScore) {
                best = d
                bestScore = score
            }
        }
        return best
    }

    private fun isCompound(id: String, ctx: CoachContext): Boolean = lookup(id, ctx)?.isCompound == true

    /** Catalog mobility holds plus custom exercises whose target is "mobility". */
    private fun isMobility(id: String, ctx: CoachContext): Boolean =
        ExerciseCatalog.isMobility(id) || customOf(id, ctx)?.target == ExerciseTargets.MOBILITY

    private fun withNote(p: PlannedExercise, extra: String): PlannedExercise =
        p.copy(note = listOf(p.note, extra).filter { it.isNotEmpty() }.joinToString(". "))

    /** Upper bound of the shown duration: 75, or the time he said he has when that is longer ("יש לי 90 דקות"). */
    private fun maxShownMinutes(ctx: CoachContext): Int = maxOf(75, ctx.constraints?.maxMinutes ?: 0)

    private fun estimateMinutes(ex: List<PlannedExercise>, ctx: CoachContext): Int {
        var sec = 5 * 60 // warm-up
        var i = 0
        while (i < ex.size) {
            val e = ex[i]
            // a working set takes him about a minute to a minute and a half (setup, reps, re-rack): 75 s
            val work = if (lookup(e.exerciseId, ctx)?.unit == "sec") e.reps else WORK_SEC_PER_SET
            val pairedRest = if (e.supersetWith != null) e.restSec / 2 else e.restSec
            sec += e.sets * (work + pairedRest)
            i++
        }
        return (sec + 30) / 60
    }

    // ------------------------------------------------------------------ plan

    fun plan(ctx: CoachContext): WorkoutPlan {
        // mode "perfect": today is rebuilt from his highest-rated session (normal progression on every exercise)
        if (ctx.modeId == "perfect") {
            val fav = perfectPlan(ctx)
            if (fav != null) return fav
        }
        val block = blockId(ctx)
        val days = daysOf(splitFor(ctx))
        var day = pickDay(days, ctx)
        var daySlots: List<Pair<Slot, Int>> = day.slots
        var onlyTitle = ""
        // a note "רק רגליים": the rotation day richest in those branches, keeping only their slots
        val onlyBranches = ctx.constraints?.focusBranches ?: emptySet()
        if (onlyBranches.isNotEmpty()) {
            val best = bestDayFor(days, onlyBranches, ctx)
            if (best != null) {
                day = best
                daySlots = best.slots.filter { it.first.branch in onlyBranches }
                if (daySlots.size < best.slots.size) onlyTitle = "אימון " + onlyBranches.sortedBy { it.ordinal }.joinToString(" ו") { ExerciseTargets.branchNameHe(it) }
            }
        }
        val dayMoved = day !== days[ctx.gymSessionsDone % days.size]
        val fb = focusBranch(ctx.focus)
        val easy = ctx.difficulty == Difficulty.EASY

        // 1) resolve every slot of the day, deterministically
        val taken = mutableSetOf<String>()
        val picked = mutableListOf<Triple<Slot, ExerciseEntity, Int>>()
        for ((slot, baseSets) in daySlots) {
            val e = resolve(slot, ctx, block, taken) ?: continue
            taken.add(e.id)
            var sets = if (fb != null && slot.branch == fb) baseSets + 1 else baseSets
            // difficulty "קל": the lower end of his ranges (legs 1 per muscle, one accessory set less)
            if (easy) sets = when {
                slot.branch == Branch.LEGS -> 1
                !e.isCompound && sets >= 2 -> sets - 1
                else -> sets
            }
            picked.add(Triple(slot, e, sets))
        }

        // 2) "stranger" mode: one accessory slot takes a candidate never done before
        if (ctx.modeId == "stranger") {
            val idx = picked.indexOfFirst { it.first.rotates && !it.second.isCompound }
            if (idx >= 0) {
                val slot = picked[idx].first
                val fresh = candidatesOf(slot, ctx).firstOrNull { !ctx.history.containsKey(it.id) && it.id !in taken }
                if (fresh != null) {
                    taken.remove(picked[idx].second.id)
                    taken.add(fresh.id)
                    picked[idx] = Triple(slot, fresh, picked[idx].third)
                }
            }
        }

        // 3) order: compounds and big muscles first, isolation after, core last
        val ordered = picked.sortedBy { t ->
            when {
                t.first == Slot.ABS -> 2
                t.second.isCompound -> 0
                else -> 1
            }
        }

        // 4) sleep: accessories lose a set on medium sleep; only compounds on short sleep
        var sleepNote = ""
        var work: List<Triple<Slot, ExerciseEntity, Int>> = ordered
        when (ctx.sleep) {
            SleepBand.MEDIUM -> {
                sleepNote = "שינה בינונית: סט אחד פחות בתרגילי העזר"
                work = ordered.map { t -> if (t.second.isCompound) t else Triple(t.first, t.second, (t.third - 1).coerceAtLeast(1)) }
            }
            SleepBand.SHORT -> {
                sleepNote = "שינה קצרה: רק התרגילים הכבדים"
                val compounds = ordered.filter { it.second.isCompound }
                work = if (compounds.isEmpty()) ordered.take(4) else compounds.take(5)
            }
            SleepBand.FULL -> {}
        }

        var exercises: MutableList<PlannedExercise> = work.map { t -> planned(t.second, ctx, t.third) }.toMutableList()

        // 5) flexibility focus: two mobility holds at the end, fixed for the block
        if (ctx.focus == Focus.FLEXIBILITY && ctx.sleep != SleepBand.SHORT) {
            val yoga = mobilityPool(ctx).filter { !blockedBy(it, ctx) }
            if (yoga.isNotEmpty()) {
                val a = yoga[block % yoga.size]
                val b = yoga[(block + 3) % yoga.size]
                val holds = if (a.id == b.id) listOf(a) else listOf(a, b)
                for (m in holds) {
                    exercises.add(PlannedExercise(m.id, m.nameHe, m.branch, sets = 2, reps = 40, weightKg = 0f, restSec = 30, note = "נשימה איטית, בלי כאב"))
                }
            }
        }

        // 6) modes: may change reps/sets/rest of ACCESSORY work only; heavy compounds stay as the coach set them
        var title = if (onlyTitle.isNotEmpty()) onlyTitle else day.titleHe
        val modeName = ModeCatalog.byId(ctx.modeId)?.nameHe ?: ""
        exercises = applyMode(exercises, ctx)
        if (modeName.isNotEmpty()) title = "$title, $modeName"

        // 7) hard cap: never more than MAX_SETS_PER_SESSION working sets, whatever focus/mode added
        exercises = capSets(exercises, ctx)

        // 7b) note to the coach: fewer sets (accessory first), never more load
        exercises = reduceSets(exercises, ctx)

        // 7c) accepted deload week: the same exercises, ~40% fewer sets each (never below 1)
        if (ctx.deload) exercises = deloadSets(exercises, ctx)

        // 8) per-set targets of the mode (ladder, pyramid, one more rep...), accessory work only, after the cap
        exercises = shapeSets(exercises, ctx)

        // 9) a note's time cap ("יש לי 30 דקות"): accessories go first; much time left: sets come back
        exercises = fitMinutes(exercises, ctx)
        exercises = fillMinutes(exercises, ctx)

        val minutes = estimateMinutes(exercises, ctx).coerceIn(if (ctx.constraints?.maxMinutes != null) 10 else 20, maxShownMinutes(ctx))
        val baseLine = if (ctx.deload) Deload.coachLine(sleepNote.substringAfter(": ")) else coachLine(exercises, ctx, sleepNote, day)
        val cLine = constraintLine(ctx, dayMoved)
        val dLine = difficultyLine(ctx.difficulty)
        val withC = if (cLine.isEmpty()) baseLine else "$cLine. $baseLine"
        val line = if (dLine.isEmpty()) withC else "$withC $dLine"
        return WorkoutPlan(SessionType.GYM, ctx.modeId, title, exercises, minutes, line)
    }

    /**
     * The mode effects of step 6: accessory-only changes to reps/sets/rest/notes/supersets, plus the two modes
     * that change WHICH accessory is done ("twin" splits one, "mirror" brings in a one-sided one). Those two are
     * skipped for a plan the user built ([userPlan]): his exercises stay his. Per-set targets come later, in [shapeSets].
     */
    private fun applyMode(input: MutableList<PlannedExercise>, ctx: CoachContext, userPlan: Boolean = false): MutableList<PlannedExercise> {
        var exercises = input
        when (ctx.modeId) {
            "twin" -> {
                if (!userPlan) exercises = twinSplit(exercises, ctx)
            }
            "mirror" -> {
                if (!userPlan) exercises = mirrorSwap(exercises, ctx)
                exercises = exercises.map { e -> if (e.exerciseId in ModeRules.unilateral) withNote(e, "מתחילים בצד החלש, הצד החזק עושה אותן חזרות") else e }.toMutableList()
            }
            "tut" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || isMobility(e.exerciseId, ctx)) e else withNote(e, "שלוש שניות למטה, שלוש למעלה, משקל מופחת") }.toMutableList()
            }
            "heavy_light" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || e.weightKg <= 0f) e else withNote(e, "סט כבד ואחריו סט קל עם יותר חזרות") }.toMutableList()
            }
            "backwards" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || isMobility(e.exerciseId, ctx)) e else withNote(e, "הסט הראשון הכבד ביותר, משם יורדים") }.toMutableList()
            }
            "reverse" -> exercises.reverse()
            "supersets" -> {
                val out = mutableListOf<PlannedExercise>()
                var i = 0
                while (i < exercises.size) {
                    val a = exercises[i]
                    val b = if (i + 1 < exercises.size) exercises[i + 1] else null
                    if (b != null && !isCompound(a.exerciseId, ctx) && !isCompound(b.exerciseId, ctx) && !isMobility(a.exerciseId, ctx) && !isMobility(b.exerciseId, ctx)) {
                        out.add(a.copy(supersetWith = b.exerciseId, restSec = 20))
                        out.add(b.copy(supersetWith = a.exerciseId))
                        i += 2
                    } else {
                        out.add(a)
                        i++
                    }
                }
                exercises = out
            }
            "pr_day" -> {
                var idx = exercises.indexOfFirst { isCompound(it.exerciseId, ctx) && ctx.history.containsKey(it.exerciseId) && it.weightKg > 0f }
                if (idx < 0) idx = exercises.indexOfFirst { isCompound(it.exerciseId, ctx) }
                // no "to failure" while a note to the coach about pain / fatigue holds
                if (idx >= 0 && ctx.constraints?.holdsLoad != true && !ctx.deload) exercises[idx] = withNote(exercises[idx], "שיא היום: סט אחרון עד כישלון טכני")
            }
            "one_more" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || isMobility(e.exerciseId, ctx)) e else withNote(e, "בסט האחרון חזרה אחת יותר") }.toMutableList()
            }
            "race_clock" -> {
                exercises = exercises.map { e ->
                    if (isCompound(e.exerciseId, ctx)) e.copy(restSec = (e.restSec - 30).coerceAtLeast(120))
                    else e.copy(restSec = (e.restSec - 30).coerceAtLeast(30))
                }.toMutableList()
            }
            "short_rest" -> {
                exercises = exercises.map { e ->
                    if (isCompound(e.exerciseId, ctx)) e.copy(restSec = (e.restSec / 2).coerceAtLeast(120))
                    else e.copy(restSec = (e.restSec / 2).coerceAtLeast(30))
                }.toMutableList()
            }
            "timer" -> {
                exercises = exercises.map { e ->
                    if (isCompound(e.exerciseId, ctx) || lookup(e.exerciseId, ctx)?.unit == "sec") e
                    else withNote(e.copy(reps = 40), "40 שניות עבודה במקום חזרות")
                }.toMutableList()
            }
            "marathon" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || isMobility(e.exerciseId, ctx)) e else e.copy(sets = e.sets + 1) }.toMutableList()
            }
            "ladder" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || e.weightKg <= 0f) e else withNote(e, "מתחילים קל עם יותר חזרות ועולים במשקל מסט לסט") }.toMutableList()
            }
            "pyramid" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || e.weightKg <= 0f) e else withNote(e, "פירמידה: עולים במשקל ואז יורדים חזרה") }.toMutableList()
            }
            "minimalist" -> {
                val keep = exercises.filter { isCompound(it.exerciseId, ctx) }.take(3).ifEmpty { exercises.take(3) }
                exercises = keep.map { e -> withNote(e.copy(sets = e.sets + 1), "סט נוסף, כל סט עד הסוף") }.toMutableList()
            }
            "fixed_weight" -> {
                exercises = exercises.map { e -> if (isCompound(e.exerciseId, ctx) || e.weightKg <= 0f) e else withNote(e, "המשקל של הפעם הקודמת בכל הסטים, מוסיפים חזרות") }.toMutableList()
            }
            "stranger" -> {
                exercises = exercises.map { e -> if (!ctx.history.containsKey(e.exerciseId) && !isCompound(e.exerciseId, ctx) && !isMobility(e.exerciseId, ctx)) withNote(e, "תרגיל חדש: משקל קל, טכניקה קודם") else e }.toMutableList()
            }
            else -> {}
        }
        return exercises
    }

    // ------------------------------------------------------------ home ("אימון בבית")

    /** The rope warm-up of a home plan (not a working set). */
    const val HOME_WARMUP_ID = "jump_rope"

    /** Stability work of the "בטן ויציבות" home day, after its one abs set (Tamir: abs 1 set per session). */
    private val homeStability: List<String> = listOf("side_plank", "bird_dog", "ball_back_extension", "glute_bridge", "band_pull_apart", "prone_t_raise")

    private val homeCoreDay = DayTemplate("בטן ויציבות", listOf(Slot.ABS to 1))

    /** A slot that takes over the sets of its empty partner (same muscle, so the per-muscle counts still hold). */
    private val partnerOf: Map<Slot, Slot> = mapOf(
        Slot.CHEST_SECOND to Slot.CHEST_MAIN,
        Slot.BACK_VERT to Slot.BACK_HORIZ,
        Slot.BACK_HORIZ to Slot.BACK_VERT
    )

    /** "גוף מלא" / "עליון, דחיפה" ... for the title. */
    private fun homeDayName(focus: String, day: DayTemplate): String = when (focus) {
        HomeGear.FOCUS_UPPER -> if (day === pushDay) "עליון, דחיפה" else "עליון, משיכה"
        HomeGear.FOCUS_LOWER -> "תחתון"
        HomeGear.FOCUS_CORE -> "בטן ויציבות"
        else -> "גוף מלא"
    }

    /** "משקל גוף, גומיות התנגדות ומשקולות יד". */
    private fun homeGearLine(gear: Set<String>): String {
        val names = HomeGear.chips.filter { it.first in gear || it.first == HomeGear.BODY }.map { it.second }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " ו" + names.last()
        }
    }

    /**
     * "אימון בבית": a session built ONLY from exercises his home gear allows ([HomeGear.allows]), with the same slots,
     * per-muscle set counts, 12-set cap, sleep / mode / deload / difficulty rules and progression as the gym plan.
     * Focus: כל הגוף = full-body A/B, עליון = push / pull, תחתון = legs (alternating by finished gym sessions, like the
     * rotation), בטן ויציבות = one abs set plus stability work. An empty slot (no bar for pull-ups) gives its sets to its
     * partner slot; the time cap trims accessories first. Weights never pass his dumbbells / kettlebell (see [homeCapped]).
     * The plan is a GYM session with today's mode, started through the normal startSession path (notes "home").
     */
    fun homePlan(base: CoachContext, req: HomeRequest): WorkoutPlan {
        val ctx = base.copy(constraints = HomeGear.applyTo(base.constraints, req))
        val block = blockId(ctx)
        val rot = ctx.gymSessionsDone
        val day: DayTemplate = when (req.focus) {
            HomeGear.FOCUS_UPPER -> if (rot % 2 == 0) pushDay else pullDay
            HomeGear.FOCUS_LOWER -> legsDay
            HomeGear.FOCUS_CORE -> homeCoreDay
            else -> if (rot % 2 == 0) fullA else fullB
        }
        val fb = focusBranch(ctx.focus)
        val easy = ctx.difficulty == Difficulty.EASY

        // 1) every slot of the day with his gear, deterministically
        val taken = mutableSetOf<String>()
        val resolved = LinkedHashMap<Slot, ExerciseEntity>()
        for (pair in day.slots) {
            val slot = pair.first
            if (resolved.containsKey(slot)) continue
            val e = resolve(slot, ctx, block, taken) ?: continue
            taken.add(e.id)
            resolved[slot] = e
        }
        // 1b) an empty slot gives its sets to its partner slot of the same day
        val setsBySlot = LinkedHashMap<Slot, Int>()
        for (pair in day.slots) {
            if (resolved.containsKey(pair.first)) setsBySlot[pair.first] = (setsBySlot[pair.first] ?: 0) + pair.second
        }
        val moved = ArrayList<Slot>()
        for (pair in day.slots) {
            val slot = pair.first
            if (resolved.containsKey(slot)) continue
            val partner = partnerOf[slot] ?: continue
            if (!resolved.containsKey(partner)) continue
            setsBySlot[partner] = (setsBySlot[partner] ?: 0) + pair.second
            moved.add(slot)
        }
        val picked = ArrayList<Triple<Slot?, ExerciseEntity, Int>>()
        for ((slot, e) in resolved) {
            val baseSets = setsBySlot[slot] ?: continue
            var sets = if (fb != null && slot.branch == fb) baseSets + 1 else baseSets
            if (easy) sets = when {
                slot.branch == Branch.LEGS -> 1
                !e.isCompound && sets >= 2 -> sets - 1
                else -> sets
            }
            picked.add(Triple(slot, e, sets))
        }
        // 1c) "בטן ויציבות": after the one abs set, up to four stability exercises his gear allows
        if (day === homeCoreDay) {
            val ok = allowedBy(ctx)
            var added = 0
            for (id in homeStability) {
                if (added >= 4) break
                val e = ExerciseCatalog.byId(id) ?: continue
                if (e.id in taken || !ok(e) || blockedBy(e, ctx)) continue
                taken.add(e.id)
                picked.add(Triple(null, e, if (easy) 1 else 2))
                added++
            }
        }

        // 2) order: compounds first, isolation after, core and stability last
        val ordered = picked.sortedBy { t ->
            when {
                t.first == Slot.ABS || t.first == null -> 2
                t.second.isCompound -> 0
                else -> 1
            }
        }

        // 3) sleep, same rules as the gym plan
        var sleepNote = ""
        var work: List<Triple<Slot?, ExerciseEntity, Int>> = ordered
        when (ctx.sleep) {
            SleepBand.MEDIUM -> {
                sleepNote = "שינה בינונית: סט אחד פחות בתרגילי העזר"
                work = ordered.map { t -> if (t.second.isCompound) t else Triple(t.first, t.second, (t.third - 1).coerceAtLeast(1)) }
            }
            SleepBand.SHORT -> {
                sleepNote = "שינה קצרה: רק התרגילים הכבדים"
                val compounds = ordered.filter { it.second.isCompound }
                work = if (compounds.isEmpty()) ordered.take(4) else compounds.take(5)
            }
            SleepBand.FULL -> {}
        }

        var exercises: MutableList<PlannedExercise> = work.map { t -> planned(t.second, ctx, t.third) }.toMutableList()
        if (exercises.isEmpty()) {
            val mini = miniPlan(ctx)
            return mini.copy(titleHe = "אימון בבית", coachLineHe = "אין מספיק ציוד לאימון מלא. שלושה תרגילים במשקל גוף, הרצף נשמר.")
        }

        // 4) mode (accessory work only), the 12-set cap, a note's limits, deload, per-set targets
        var title = "אימון בבית: " + homeDayName(req.focus, day)
        val modeName = ModeCatalog.byId(ctx.modeId)?.nameHe ?: ""
        exercises = applyMode(exercises, ctx)
        if (modeName.isNotEmpty()) title = "$title, $modeName"
        exercises = capSets(exercises, ctx)
        exercises = reduceSets(exercises, ctx)
        if (ctx.deload) exercises = deloadSets(exercises, ctx)
        exercises = shapeSets(exercises, ctx)

        // 5) the rope warm-up (not a working set, so after the cap); the time cap may still drop it last
        if (HomeGear.ROPE in req.gear && ctx.sleep != SleepBand.SHORT) {
            val rope = ExerciseCatalog.byId(HOME_WARMUP_ID)
            if (rope != null && !blockedBy(rope, ctx) && exercises.none { it.exerciseId == HOME_WARMUP_ID }) {
                exercises.add(0, PlannedExercise(rope.id, rope.nameHe, rope.branch, sets = 1, reps = 120, weightKg = 0f, restSec = 30,
                    note = "חימום: קצב קל, נחיתה רכה על כריות כף הרגל"))
            }
        }

        // 6) "כמה זמן יש לך?": accessories go first; much time left: sets come back
        exercises = fitMinutes(exercises, ctx)
        exercises = fillMinutes(exercises, ctx)

        val minutes = estimateMinutes(exercises, ctx).coerceIn(10, maxShownMinutes(ctx))
        val parts = ArrayList<String>()
        parts.add("בבית עם " + homeGearLine(ctx.constraints?.homeGear ?: req.gear))
        if (Slot.BACK_VERT in moved) parts.add("אין ציוד למשיכה מלמעלה, החתירה מקבלת את כל סטי הגב")
        if (Slot.BACK_HORIZ in moved) parts.add("אין ציוד לחתירה, המשיכה מלמעלה מקבלת את כל סטי הגב")
        if (Slot.CHEST_SECOND in moved) parts.add("תרגיל החזה הראשי מקבל את כל סטי החזה")
        val capped = exercises.any { p -> val e = lookup(p.exerciseId, ctx); e != null && cappedTarget(e, ctx).rule == "home_cap" }
        if (capped) parts.add("המשקל שיש לך בבית קל מהיעד, אז מוסיפים חזרות במקום משקל")
        if (day === homeCoreDay) parts.add("סט בטן אחד, כמו תמיד. השאר עבודת יציבות לגב ולאגן")
        val cap = ctx.constraints?.maxMinutes
        if (cap != null) parts.add("מותאם ל-$cap דקות")
        if (ctx.constraints?.holdsLoad == true) parts.add("ההודעה לג'ים ברו עדיין בתוקף, בלי עליות משקל")
        if (sleepNote.isNotEmpty()) parts.add(sleepNote.substringAfter(": "))
        if (ctx.deload) parts.add("שבוע הורדת עומס: אותם תרגילים, פחות סטים")
        val dLine = difficultyLine(ctx.difficulty)
        val line = parts.joinToString(". ") + "." + (if (dLine.isEmpty()) "" else " $dLine")
        return WorkoutPlan(SessionType.GYM, ctx.modeId, title, exercises, minutes, line)
    }

    // ------------------------------------------------------------ mode helpers (stage 1 B)

    /**
     * Mode "perfect": the exercises of his highest-rated session ([CoachContext.favoriteSession]) with the
     * normal double progression on each one (the coach still decides every weight). Same sleep rules and the
     * same 12-set cap as a regular plan. Null when there is nothing usable (then the regular plan is built).
     */
    private fun perfectPlan(ctx: CoachContext): WorkoutPlan? {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<Pair<ExerciseEntity, Int>>()
        for (pair in ctx.favoriteSession) {
            val raw = lookup(pair.first, ctx) ?: continue
            // note constraints: a blocked exercise takes the first allowed candidate of its slot, or is left out
            val e = if (!blockedBy(raw, ctx)) raw else {
                val slot = slotOf(raw.id, ctx)
                (if (slot == null) null else candidatesOf(slot, ctx).firstOrNull { it.id !in seen }) ?: continue
            }
            if (!seen.add(e.id)) continue
            items.add(Pair(e, pair.second.coerceIn(1, 4)))
        }
        if (items.isEmpty()) return null
        val ordered = items.sortedBy { p ->
            when {
                isMobility(p.first.id, ctx) -> 3
                p.first.branch == Branch.CORE && !p.first.isCompound -> 2
                p.first.isCompound -> 0
                else -> 1
            }
        }
        var sleepNote = ""
        val work: List<Pair<ExerciseEntity, Int>> = when (ctx.sleep) {
            SleepBand.MEDIUM -> {
                sleepNote = "סט אחד פחות בתרגילי העזר"
                ordered.map { p -> if (p.first.isCompound) p else Pair(p.first, (p.second - 1).coerceAtLeast(1)) }
            }
            SleepBand.SHORT -> {
                sleepNote = "רק התרגילים הכבדים"
                val compounds = ordered.filter { it.first.isCompound }
                if (compounds.isEmpty()) ordered.take(4) else compounds.take(5)
            }
            SleepBand.FULL -> ordered
        }
        var exercises: MutableList<PlannedExercise> = work.map { p -> planned(p.first, ctx, p.second) }.toMutableList()
        exercises = capSets(exercises, ctx)
        exercises = reduceSets(exercises, ctx)
        if (ctx.deload) exercises = deloadSets(exercises, ctx)
        exercises = fitMinutes(exercises, ctx)
        exercises = fillMinutes(exercises, ctx)
        val minutes = estimateMinutes(exercises, ctx).coerceIn(if (ctx.constraints?.maxMinutes != null) 10 else 20, maxShownMinutes(ctx))
        val baseLine = when {
            ctx.deload -> "התרגילים של האימון שדירגת הכי גבוה. " + Deload.coachLine(sleepNote)
            sleepNote.isNotEmpty() -> "התרגילים של האימון שדירגת הכי גבוה, $sleepNote."
            else -> "התרגילים של האימון שדירגת הכי גבוה, עם ההתקדמות הרגילה בכל אחד מהם."
        }
        val cLine = constraintLine(ctx, false)
        val line = if (cLine.isEmpty()) baseLine else "$cLine. $baseLine"
        val modeName = ModeCatalog.byId(ctx.modeId)?.nameHe ?: "האימון המושלם"
        return WorkoutPlan(SessionType.GYM, ctx.modeId, modeName, exercises, minutes, line)
    }

    /** A different exercise for the same muscle as [exerciseId] (same slot first, then catalog alternatives). Accessory only. */
    private fun twinOf(exerciseId: String, ctx: CoachContext, used: Set<String>): ExerciseEntity? {
        val ok = allowedBy(ctx)
        val slot = slotOf(exerciseId, ctx)
        if (slot != null) {
            val c = candidatesOf(slot, ctx).firstOrNull { it.id != exerciseId && it.id !in used && !it.isCompound }
            if (c != null) return c
        }
        for (id in ExerciseCatalog.alternativeIds(exerciseId)) {
            val e = lookup(id, ctx) ?: continue
            if (e.id != exerciseId && e.id !in used && !e.isCompound && !isMobility(e.id, ctx) && ok(e) && !blockedBy(e, ctx)) return e
        }
        return null
    }

    /** Mode "twin": every accessory with 2+ sets gives half of its sets to a second version for the same muscle. */
    private fun twinSplit(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        val out = mutableListOf<PlannedExercise>()
        val used = input.map { it.exerciseId }.toMutableSet()
        for (p in input) {
            val n = maxOf(p.sets, p.perSet.size)
            if (isCompound(p.exerciseId, ctx) || isMobility(p.exerciseId, ctx) || n < 2) {
                out.add(p)
                continue
            }
            val alt = twinOf(p.exerciseId, ctx, used)
            if (alt == null) {
                out.add(p)
                continue
            }
            used.add(alt.id)
            val first = (n + 1) / 2
            val second = n - first
            val keep = if (p.perSet.isEmpty()) p.copy(sets = first) else p.copy(sets = first, perSet = p.perSet.take(first))
            out.add(withNote(keep, "התאומים: הגרסה הראשונה"))
            out.add(withNote(planned(alt, ctx, second).copy(restSec = p.restSec), "התאומים: הגרסה השנייה לאותו שריר"))
        }
        return out
    }

    /** Mode "mirror": when the plan has no one-sided exercise, the first accessory that has a one-sided candidate takes it. */
    private fun mirrorSwap(input: MutableList<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        if (input.any { it.exerciseId in ModeRules.unilateral }) return input
        val used = input.map { it.exerciseId }.toSet()
        var i = 0
        while (i < input.size) {
            val p = input[i]
            if (!isCompound(p.exerciseId, ctx) && !isMobility(p.exerciseId, ctx)) {
                val slot = slotOf(p.exerciseId, ctx)
                val uni = if (slot == null) null else candidatesOf(slot, ctx).firstOrNull { it.id in ModeRules.unilateral && it.id !in used && !it.isCompound }
                if (uni != null) {
                    input[i] = planned(uni, ctx, p.sets).copy(restSec = p.restSec)
                    return input
                }
            }
            i++
        }
        return input
    }

    /** Modes that give accessory sets their own targets (perSet rows). */
    private val SHAPED_MODES: Set<String> = setOf("one_more", "ladder", "pyramid", "heavy_light", "backwards", "tut", "fixed_weight")

    /** Step 8: per-set targets of today's mode. Compounds and mobility holds are never touched. */
    private fun shapeSets(input: List<PlannedExercise>, ctx: CoachContext): MutableList<PlannedExercise> {
        if (ctx.modeId !in SHAPED_MODES) return input.toMutableList()
        return input.map { p -> shapeOne(p, ctx) }.toMutableList()
    }

    private fun shapeOne(p: PlannedExercise, ctx: CoachContext): PlannedExercise {
        if (isCompound(p.exerciseId, ctx) || isMobility(p.exerciseId, ctx)) return p
        val e = lookup(p.exerciseId, ctx)
        val unit = e?.unit ?: "kg"
        val step = if (e != null) stepOf(e) else 2.5f
        val n = maxOf(p.sets, p.perSet.size).coerceAtLeast(1)
        val base: List<Pair<Float, Int>> = (0 until n).map { i ->
            if (p.perSet.isEmpty()) Pair(p.weightKg, p.reps) else p.perSet[minOf(i, p.perSet.size - 1)]
        }
        val weighted = unit == "kg" && base.any { it.first > 0f }
        fun w(v: Float): Float = if (v <= 0f) 0f else roundTo(v, step)
        val rows: List<Pair<Float, Int>> = when (ctx.modeId) {
            "one_more" -> base.mapIndexed { i, r -> if (i == n - 1) Pair(r.first, r.second + (if (unit == "sec") 5 else 1)) else r }
            "ladder" -> if (!weighted || n < 2) base else base.mapIndexed { i, r ->
                val f = 0.85f + 0.15f * i.toFloat() / (n - 1).toFloat()
                Pair(w(r.first * f), r.second + 2 * (n - 1 - i))
            }
            "pyramid" -> if (!weighted || n < 2) base else {
                val peak = n / 2
                val maxDist = maxOf(peak, n - 1 - peak).coerceAtLeast(1)
                base.mapIndexed { i, r ->
                    val dist = abs(i - peak)
                    val f = 1f - 0.15f * dist.toFloat() / maxDist.toFloat()
                    Pair(w(r.first * f), r.second + (3 * dist) / maxDist)
                }
            }
            "heavy_light" -> if (!weighted || n < 2) base else base.mapIndexed { i, r ->
                if (i % 2 == 0) r else Pair(w(r.first * 0.7f), r.second + 4)
            }
            "backwards" -> when {
                n < 2 -> base
                weighted -> base.mapIndexed { i, r -> Pair(w(r.first * (1f - 0.1f * i)), r.second + 2 * i) }
                unit == "sec" -> base.mapIndexed { i, r -> Pair(0f, (r.second - 5 * i).coerceAtLeast(15)) }
                else -> base.mapIndexed { i, r -> Pair(0f, (r.second - 2 * i).coerceAtLeast(5)) }
            }
            "tut" -> when {
                weighted -> base.map { r -> Pair(w(r.first * 0.8f), r.second) }
                unit == "reps" -> base.map { r -> Pair(0f, (r.second * 3 / 4).coerceAtLeast(5)) }
                else -> base
            }
            "fixed_weight" -> {
                val h = ctx.history[p.exerciseId]
                if (!weighted || h == null || h.lastWeight <= 0f) base
                else {
                    val minLast = h.lastSetReps.minOrNull() ?: h.lastReps
                    val hi = repRange(false, ctx.focus).second
                    val reps = (minLast + 1).coerceIn(REPS_MIN, hi + 2)
                    base.map { Pair(h.lastWeight, reps) }
                }
            }
            else -> base
        }
        if (rows == base && p.perSet.isEmpty()) return p
        val first = rows[0]
        return p.copy(sets = rows.size, reps = first.second, weightKg = first.first, perSet = rows)
    }

    /**
     * Rest the live workout starts after a set: [baseRest] when the plan set one (a user template), otherwise
     * the coach's rest for [e]; then today's [difficulty] (קל +30 s, קשה −30 s, floors 90 / 45 s), then today's mode
     * ("race_clock" −30 s, "short_rest" half). A compound never drops below 120 s because of a mode (unless its own
     * base was already lower).
     */
    fun liveRestSec(e: ExerciseEntity?, focus: Focus, modeId: String?, baseRest: Int? = null, difficulty: Difficulty = Difficulty.NORMAL): Int {
        val compound = e?.isCompound ?: true
        val coachRest = if (e != null) restFor(e, focus) else REST_COMPOUND
        val raw = if (baseRest != null && baseRest > 0) baseRest else coachRest
        val base = Difficulty.adjustRest(raw, compound, difficulty)
        val floor = if (compound) minOf(base, 120) else minOf(base, 30)
        return when (modeId) {
            "race_clock" -> (base - 30).coerceAtLeast(floor)
            "short_rest" -> (base / 2).coerceAtLeast(floor)
            else -> base
        }
    }

    /**
     * Applies today's mode to a plan the USER built ("האימונים שלי"). Same accessory-only effects as a coach
     * plan (reps/sets/rest/notes/supersets on accessory work), with two guards because the plan is his choice:
     * his compound lifts keep exactly their sets, reps and weight (only rest/notes/superset may change), and no
     * exercise is ever dropped ("minimalist" becomes a note). The mode name is appended to the title.
     * Does not apply the 12-set cap: a template is the user's own call.
     */
    fun applyModeToPlan(plan: WorkoutPlan, ctx: CoachContext): WorkoutPlan {
        val original = plan.exercises
        val applied: List<PlannedExercise> = if (ctx.modeId == "minimalist") {
            original.map { e -> withNote(e, "מינימליסט: כל סט עד הסוף") }
        } else {
            shapeSets(applyMode(original.toMutableList(), ctx, userPlan = true), ctx)
        }
        val byId = original.associateBy { it.exerciseId }
        val guarded = applied.map { e ->
            val base = byId[e.exerciseId]
            if (base != null && isCompound(e.exerciseId, ctx)) e.copy(sets = base.sets, reps = base.reps, weightKg = base.weightKg) else e
        }
        val modeName = ModeCatalog.byId(ctx.modeId)?.nameHe ?: ""
        val title = if (modeName.isNotEmpty()) "${plan.titleHe}, $modeName" else plan.titleHe
        val minutes = estimateMinutes(guarded, ctx).coerceIn(10, 120)
        return plan.copy(modeId = ctx.modeId, titleHe = title, exercises = guarded, estimatedMinutes = minutes)
    }

    private fun coachLine(exercises: List<PlannedExercise>, ctx: CoachContext, sleepNote: String, day: DayTemplate): String {
        val parts = mutableListOf<String>()
        val up = exercises.firstOrNull { it.note.startsWith("מעלים") }
        val legsInHistory = ctx.history.keys.any { branchOf(it, ctx) == Branch.LEGS }
        val legsToday = exercises.firstOrNull { it.branch == Branch.LEGS }
        if (up != null) {
            val entity = lookup(up.exerciseId, ctx)
            val inc = if (entity != null) increment(entity) else 2.5f
            parts.add("היום מעלים ${fmtKg(inc)} ק\"ג ב${up.nameHe}")
        }
        if (!legsInHistory && legsToday != null) parts.add("הרגליים נכנסות לתוכנית עם ${legsToday.nameHe}")
        if (sleepNote.isNotEmpty()) parts.add(sleepNote.substringAfter(": "))
        if (parts.isEmpty()) {
            val first = exercises.firstOrNull { it.note.startsWith("אימון ראשון") }
            parts.add(
                if (first != null) "היום מוצאים משקל עבודה ב${first.nameHe}, בלי להתפשר על טכניקה"
                else "אותם תרגילים כמו בפעם הקודמת, היום מוסיפים חזרה או משלימים את כל הסטים"
            )
        }
        if (day === lowerDay || day === legsDay) parts.add("יום רגליים, הולכים כבד ומנוחות מלאות")
        return parts.joinToString(", ") + "."
    }

    // ---------------------------------------------------------- alternatives

    /**
     * Options for the user's "swap" button: the catalog alternatives, the other candidates of the same
     * slot, and the user's custom exercises of the same target (or, when the exercise has no slot, of
     * the same branch), filtered by the mode's equipment. Sets/rest are copied from the original by the caller.
     */
    fun alternatives(exerciseId: String, ctx: CoachContext): List<PlannedExercise> {
        val ok = allowedBy(ctx)
        val ids = mutableListOf<String>()
        for (id in ExerciseCatalog.alternativeIds(exerciseId)) if (id != exerciseId && id !in ids) ids.add(id)
        val slot = slotOf(exerciseId, ctx)
        if (slot != null) {
            for (id in catalogIdsOf(slot, ctx)) if (id != exerciseId && id !in ids) ids.add(id)
            for (c in customsFor(slot.target, ctx)) if (c.id != exerciseId && c.id !in ids) ids.add(c.id)
        } else {
            val self = lookup(exerciseId, ctx)
            if (self != null) {
                val sameTarget = ctx.customExercises.filter { it.target.isNotEmpty() && it.target == self.target }
                val pool = if (sameTarget.isNotEmpty()) sameTarget else ctx.customExercises.filter { it.branch == self.branch }
                for (c in pool) if (c.id != exerciseId && c.id !in ids) ids.add(c.id)
            }
        }
        // note constraints: blocked exercises are not offered as alternatives (unless nothing else is left)
        val known = ids.mapNotNull { lookup(it, ctx) }
        val allowed = known.filter { !blockedBy(it, ctx) }
        val entities = if (allowed.isNotEmpty()) allowed else known
        val byMode = entities.filter(ok)
        val list = if (byMode.isNotEmpty()) byMode else entities
        val sets = if (isCompound(exerciseId, ctx)) 2 else 1
        return list.map { planned(it, ctx, sets) }
    }

    // ------------------------------------------------------------- yoga/mini

    private fun seedOf(ctx: CoachContext): Int = ctx.modeId.hashCode() * 31 + ctx.daysSinceLastGym

    /** Catalog holds plus custom "mobility" exercises (custom ones first, stable order). */
    private fun mobilityPool(ctx: CoachContext): List<ExerciseEntity> =
        customsFor(ExerciseTargets.MOBILITY, ctx) + ExerciseCatalog.yoga

    fun yogaPlan(ctx: CoachContext): WorkoutPlan {
        val rnd = Random(seedOf(ctx) + 7)
        val pool = mobilityPool(ctx)
        val yoga = pool.filter { !blockedBy(it, ctx) }.ifEmpty { pool }.shuffled(rnd).take(6)
        val ex = yoga.map { e ->
            PlannedExercise(e.id, e.nameHe, e.branch, sets = 3, reps = 60, weightKg = 0f, restSec = 40, note = "נשימה איטית, בלי כאב")
        }
        val line = if (ctx.sleep == SleepBand.SHORT) "שינה קצרה, היום מגייסים את הגוף בעדינות ושומרים על הרצף." else "היום פותחים ירכיים וגב עליון, שלושים דקות של עבודה שקטה."
        return WorkoutPlan(SessionType.YOGA, "yoga", "יוגה ותנועתיות", ex, 30, line)
    }

    fun miniPlan(ctx: CoachContext): WorkoutPlan {
        val rnd = Random(seedOf(ctx) + 13)
        val pool = ExerciseCatalog.miniPool.filter { !blockedBy(it, ctx) }
        val push = pool.filter { it.branch == Branch.CHEST }
        val legs = pool.filter { it.branch == Branch.LEGS }
        val core = pool.filter { it.branch == Branch.CORE }
        val picks = listOfNotNull(
            push.getOrNull(rnd.nextInt(push.size.coerceAtLeast(1))),
            legs.getOrNull(rnd.nextInt(legs.size.coerceAtLeast(1))),
            core.getOrNull(rnd.nextInt(core.size.coerceAtLeast(1)))
        )
        val ex = picks.map { e ->
            val reps = if (e.unit == "sec") 30 else 12
            PlannedExercise(e.id, e.nameHe, e.branch, sets = 3, reps = reps, weightKg = 0f, restSec = 45)
        }
        return WorkoutPlan(SessionType.MINI, "mini", "אימון זעיר", ex, 13, "שלושה תרגילים, שלושה סטים, שתים עשרה דקות. הרצף נשמר.")
    }

    // ------------------------------------------------------------------ misc

    fun sessionTypeFor(sleep: SleepBand, planned: SessionType): SessionType = when (sleep) {
        SleepBand.FULL -> planned
        SleepBand.MEDIUM -> planned
        SleepBand.SHORT -> if (planned == SessionType.GYM) SessionType.YOGA else planned
    }

    fun branchOf(exerciseId: String): Branch = ExerciseCatalog.byId(exerciseId)?.branch ?: Branch.CORE

    /** Same, but also knows the user's custom exercises. */
    fun branchOf(exerciseId: String, ctx: CoachContext): Branch = lookup(exerciseId, ctx)?.branch ?: Branch.CORE
}
