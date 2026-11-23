package com.tamir.hamasa.domain.tree

import com.tamir.hamasa.data.db.Branch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Branch level = how strong that muscle group is relative to body weight. Level 0..10.
 * Also produces the geometric layout of the living tree (pure math, no Compose types).
 */
object TreeModel {
    /**
     * ratio = the branch's best relative strength (see [relativeStrength]): estimated 1RM of the best set of ANY
     * exercise of the branch, converted to the branch's reference lift, / body weight.
     * CORE: max of hold seconds / 120 and weighted core work. ENDURANCE: non-gym sessions per 4 weeks / 10.
     */
    fun levelFromRatio(branch: Branch, ratio: Float): Int {
        val t = thresholds(branch)
        return step(ratio, t.first, t.second)
    }

    /** Ratio for level 0 -> 10 of each branch, on the scale of its reference lift. */
    fun thresholds(branch: Branch): Pair<Float, Float> = when (branch) {
        Branch.CHEST -> Pair(0.3f, 1.5f)            // bench press 1RM / bw
        Branch.BACK -> Pair(0.3f, 1.5f)             // lat pulldown 1RM / bw
        Branch.LEGS -> Pair(0.5f, 2.5f)             // leg press 1RM / bw
        Branch.SHOULDERS_ARMS -> Pair(0.25f, 1.0f)  // shoulder press 1RM (both hands) / bw
        Branch.CORE -> Pair(0.2f, 1.2f)             // plank seconds / 120
        Branch.ENDURANCE -> Pair(0.1f, 1.0f)        // sessions of yoga/walk/run per 4 weeks / 10
    }

    private fun step(r: Float, lo: Float, hi: Float): Int = (((r - lo) / (hi - lo)) * 10).toInt().coerceIn(0, 10)

    // ---------------------------------------------------------------- relative strength (all exercises of a branch)

    /** Reference lift of each branch, the one the ratio scale is written in. */
    fun referenceHe(branch: Branch): String = when (branch) {
        Branch.CHEST -> "לחיצת חזה במוט"
        Branch.BACK -> "משיכת פולי עליון"
        Branch.LEGS -> "לחיצת רגליים"
        Branch.SHOULDERS_ARMS -> "לחיצת כתפיים (שתי הידיים יחד)"
        Branch.CORE -> "פלאנק"
        Branch.ENDURANCE -> "יוגה, הליכה וריצה"
    }

    /**
     * How strong a lift typically is compared to the branch's reference lift (1.0 = same load).
     * A set is converted to the reference scale by dividing its 1RM by the factor, so a curl and a press are
     * comparable. Compounds are 1.0, isolation 0.5–0.7; a few compounds that move a very different load than the
     * reference get their own number (deadlift, squat vs leg press). 0 = not counted (assisted pull-up: more weight
     * means MORE help).
     */
    private val strengthFactors: Map<String, Float> = mapOf(
        // chest (reference: bench press)
        "bench_press" to 1.0f, "incline_bench_press" to 1.0f, "db_bench_press" to 1.0f, "incline_db_press" to 1.0f,
        "chest_press_machine" to 1.0f, "chest_fly" to 0.6f, "db_fly" to 0.5f, "cable_crossover" to 0.5f,
        // back (reference: lat pulldown)
        "lat_pulldown" to 1.0f, "cable_row" to 1.0f, "machine_row" to 1.0f, "barbell_row" to 1.0f, "db_row" to 1.0f,
        "t_bar_row" to 1.0f, "deadlift" to 1.6f, "assisted_pullup" to 0f, "straight_arm_pulldown" to 0.5f,
        // legs (reference: leg press, which moves about twice a squat)
        "leg_press" to 1.0f, "squat" to 0.5f, "hack_squat" to 0.7f, "goblet_squat" to 0.25f,
        "romanian_deadlift" to 0.5f, "db_romanian_deadlift" to 0.5f, "hip_thrust" to 0.7f,
        "lunges" to 0.3f, "bulgarian_split_squat" to 0.3f,
        "leg_extension" to 0.5f, "leg_curl" to 0.5f, "calf_raise" to 0.7f, "hip_abduction" to 0.5f,
        // shoulders + arms (reference: shoulder press, both hands)
        "shoulder_press" to 1.0f, "barbell_ohp" to 1.0f, "machine_shoulder_press" to 1.0f,
        "lateral_raise" to 0.5f, "cable_lateral_raise" to 0.5f, "lateral_raise_machine" to 0.5f, "front_raise" to 0.5f,
        "face_pull" to 0.6f, "reverse_fly" to 0.5f, "reverse_fly_machine" to 0.5f, "db_shrug" to 0.7f,
        "db_curl" to 0.6f, "hammer_curl" to 0.6f, "barbell_curl" to 0.6f, "cable_curl" to 0.6f, "preacher_curl" to 0.6f,
        "tricep_pushdown" to 0.6f, "tricep_machine" to 0.6f, "overhead_tricep_extension" to 0.6f, "skull_crusher" to 0.6f,
        // core, weighted (reference: plank seconds / 120; 0.85 bw cable crunch 1RM ~ a two-minute plank)
        "cable_crunch" to 0.85f, "crunch_machine" to 0.85f
    )

    /** Exercises whose logged weight is ONE dumbbell (per hand): the load is doubled before comparing. */
    private val perHandIds: Set<String> = setOf(
        "db_bench_press", "incline_db_press", "db_fly", "db_row", "db_romanian_deadlift", "lunges", "bulgarian_split_squat",
        "shoulder_press", "lateral_raise", "front_raise", "reverse_fly", "db_shrug", "db_curl", "hammer_curl"
    )

    /** Strength factor of an exercise; custom exercises: compound 1.0 (legs 0.6), isolation 0.6. */
    fun strengthFactor(id: String, branch: Branch, isCompound: Boolean): Float {
        strengthFactors[id]?.let { return it }
        return when {
            isCompound && branch == Branch.LEGS -> 0.6f
            isCompound -> 1.0f
            else -> 0.6f
        }
    }

    /** One logged dumbbell = one hand. Catalog list, or any custom dumbbell exercise that is not a single-weight goblet. */
    fun isPerHand(id: String, equipment: String): Boolean =
        if (strengthFactors.containsKey(id)) id in perHandIds else equipment == "dumbbell"

    /** Epley estimated one-rep max. Reps above 15 are capped (the formula drifts on long sets). */
    fun epley1Rm(weightKg: Float, reps: Int): Float {
        if (weightKg <= 0f || reps <= 0) return 0f
        if (reps == 1) return weightKg
        return weightKg * (1f + reps.coerceAtMost(15) / 30f)
    }

    /**
     * One weighted set on the branch's reference scale / body weight. 0 when the exercise does not count
     * (unit not kg, factor 0, no weight, no body weight).
     */
    fun relativeStrength(id: String, branch: Branch, isCompound: Boolean, equipment: String, unit: String,
                         weightKg: Float, reps: Int, bodyWeightKg: Float): Float {
        if (unit != "kg" || bodyWeightKg <= 0f) return 0f
        val factor = strengthFactor(id, branch, isCompound)
        if (factor <= 0f) return 0f
        val hands = if (isPerHand(id, equipment)) 2f else 1f
        return epley1Rm(weightKg, reps) * hands / factor / bodyWeightKg
    }

    /** Hold seconds on the CORE scale (plank 1.0, side plank counts 1.5× because it is per side). */
    fun holdRatio(id: String, seconds: Int): Float = when (id) {
        "plank" -> seconds / 120f
        "side_plank" -> seconds * 1.5f / 120f
        else -> 0f
    }

    /** "CHEST:3,LEGS:2" -> map. Malformed pieces are ignored. */
    fun parseLevels(csv: String): Map<Branch, Int> {
        val out = mutableMapOf<Branch, Int>()
        for (piece in csv.split(',')) {
            val kv = piece.split(':')
            if (kv.size != 2) continue
            val b = runCatching { Branch.valueOf(kv[0].trim()) }.getOrNull() ?: continue
            val v = kv[1].trim().toIntOrNull() ?: continue
            out[b] = v
        }
        return out
    }

    fun encodeLevels(levels: Map<Branch, Int>): String =
        levels.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}:${it.value}" }

    /** Derived stats shown on Shambiuluk. */
    data class Stats(val upper: Int, val lower: Int, val stability: Int, val endurance: Int)
    fun stats(levels: Map<Branch, Int>): Stats = Stats(
        upper = listOf(Branch.CHEST, Branch.BACK, Branch.SHOULDERS_ARMS).map { levels[it] ?: 0 }.average().toInt(),
        lower = levels[Branch.LEGS] ?: 0,
        stability = listOf(Branch.CORE, Branch.ENDURANCE).map { levels[it] ?: 0 }.let { (it[0] * 2 + it[1]) / 3 },
        endurance = levels[Branch.ENDURANCE] ?: 0
    )

    /** Hebrew names of the branches. */
    fun nameHe(branch: Branch): String = when (branch) {
        Branch.CHEST -> "חזה"
        Branch.BACK -> "גב"
        Branch.LEGS -> "רגליים"
        Branch.SHOULDERS_ARMS -> "כתפיים וידיים"
        Branch.CORE -> "ליבה"
        Branch.ENDURANCE -> "סיבולת"
    }

    /** Short explanation of what makes the branch grow ("הענף גדל כש..."). */
    /** Progress model: every 2 records in the branch = +1 level, every 4 sessions that trained it = +1. Max 10. */
    fun progressLevel(records: Int, sessions: Int): Int = (records / 2 + sessions / 4).coerceIn(0, 10)

    fun growsWhenHe(branch: Branch): String = when (branch) {
        Branch.CHEST -> "הענף גדל מההתקדמות שלך: כל שני שיאים בתרגילי החזה = רמה, וכל ארבעה אימונים שעבדו עליו = רמה"
        Branch.BACK -> "הענף גדל מההתקדמות שלך: כל שני שיאים בתרגילי הגב = רמה, וכל ארבעה אימונים שעבדו עליו = רמה"
        Branch.LEGS -> "הענף גדל מההתקדמות שלך: כל שני שיאים בתרגילי הרגליים = רמה, וכל ארבעה אימונים שעבדו עליו = רמה"
        Branch.SHOULDERS_ARMS -> "הענף גדל מההתקדמות שלך: כל שני שיאים בתרגילי הכתפיים והידיים = רמה, וכל ארבעה אימונים שעבדו עליו = רמה"
        Branch.CORE -> "הענף גדל מההתקדמות שלך: כל שני שיאים בתרגילי בטן = רמה, וכל ארבעה אימונים שעבדו עליה = רמה"
        Branch.ENDURANCE -> "הענף גדל עם כל יוגה, הליכה או ריצה בארבעת השבועות האחרונים"
    }

    // ---------------------------------------------------------------- layout

    /** A 2D point in canvas pixels (x to the right, y down). */
    data class Pt(val x: Float, val y: Float)

    data class BranchLayout(
        val branch: Branch,
        val level: Int,
        /** Polyline from the trunk outwards to the tip. */
        val points: List<Pt>,
        /** Stroke width at the base of the branch, in px. */
        val thickness: Float,
        /** Centres of leaf clusters along the branch, with their radius. */
        val leaves: List<Pair<Pt, Float>>,
        /** Rune medallion at the tip. */
        val medallion: Pt,
        val medallionRadius: Float,
        val glowing: Boolean
    )

    data class TreeLayout(
        val width: Float,
        val height: Float,
        /** Point where the trunk meets the ground. */
        val base: Pt,
        /** Top of the trunk. */
        val top: Pt,
        /** Trunk polyline from base upwards (slightly wavy). */
        val trunk: List<Pt>,
        val trunkThickness: Float,
        /** Root polylines starting at the base. */
        val roots: List<List<Pt>>,
        val branches: List<BranchLayout>,
        /** Average level 0..10. */
        val averageLevel: Float,
        /** 0 = sapling, 1 = full oak. */
        val growth: Float
    )

    private data class Slot(val branch: Branch, val side: Int, val heightFrac: Float, val angleDeg: Float)

    /**
     * Fixed slots: CHEST/BACK high, SHOULDERS_ARMS/CORE middle, LEGS/ENDURANCE low.
     * side = -1 left, +1 right. Angle measured from the horizontal, positive = upwards.
     */
    private val slots = listOf(
        Slot(Branch.CHEST, -1, 0.90f, 36f),
        Slot(Branch.BACK, +1, 0.86f, 32f),
        Slot(Branch.SHOULDERS_ARMS, -1, 0.68f, 22f),
        Slot(Branch.CORE, +1, 0.62f, 18f),
        Slot(Branch.LEGS, -1, 0.46f, 10f),
        Slot(Branch.ENDURANCE, +1, 0.40f, 6f)
    )

    /**
     * Computes the geometry of the tree inside a width x height box.
     * The whole tree scales with the average level: sapling at avg 0, full oak at avg >= 8.
     */
    fun layout(levels: Map<Branch, Int>, width: Float, height: Float): TreeLayout =
        layoutSmooth(levels.mapValues { it.value.toFloat() }, width, height)

    /** Same as [layout] but with fractional levels, so the UI can animate growth between levels. */
    fun layoutSmooth(levels: Map<Branch, Float>, width: Float, height: Float): TreeLayout {
        val safeW = if (width <= 0f) 1f else width
        val safeH = if (height <= 0f) 1f else height
        val avg = if (levels.isEmpty()) 0f else levels.values.map { it.coerceIn(0f, 10f) }.average().toFloat()
        val growth = (avg / 8f).coerceIn(0f, 1f)

        val groundY = safeH * 0.92f
        val treeHeight = safeH * (0.22f + 0.52f * growth)
        val base = Pt(safeW / 2f, groundY)
        val top = Pt(safeW / 2f, groundY - treeHeight)
        val trunkThickness = safeW * (0.035f + 0.085f * growth)

        // slightly wavy trunk
        val trunk = ArrayList<Pt>()
        val segments = 6
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val wobble = sin(t * 3.2f) * safeW * 0.012f * growth
            trunk.add(Pt(base.x + wobble, groundY - treeHeight * t))
        }

        // roots
        val roots = ArrayList<List<Pt>>()
        val rootLen = safeW * (0.08f + 0.14f * growth)
        // angle 0 = straight right, 180 = straight left; roots dip a little below the ground line
        val rootAngles = listOf(165f, 140f, 110f, 70f, 40f, 15f)
        for (a in rootAngles) {
            val rad = Math.toRadians(a.toDouble())
            val dx = (cos(rad) * rootLen).toFloat()
            val dy = (sin(rad) * rootLen * 0.35f).toFloat() + rootLen * 0.08f
            roots.add(listOf(base, Pt(base.x + dx * 0.5f, base.y + dy * 0.45f), Pt(base.x + dx, base.y + dy)))
        }

        val maxBranchLen = safeW * 0.36f
        val branches = ArrayList<BranchLayout>()
        for (slot in slots) {
            val levelF = (levels[slot.branch] ?: 0f).coerceIn(0f, 10f)
            val level = Math.round(levelF)
            val lf = levelF / 10f
            val origin = trunkPoint(trunk, slot.heightFrac)
            // length: bud at level 0, long at level 10 (also scaled by overall growth so a sapling stays small)
            val length = maxBranchLen * (0.06f + 0.94f * lf) * (0.45f + 0.55f * growth)
            val thickness = trunkThickness * (0.25f + 0.55f * lf)
            val rad = Math.toRadians(slot.angleDeg.toDouble())
            val dirX = (cos(rad) * slot.side).toFloat()
            val dirY = -sin(rad).toFloat()
            val pts = ArrayList<Pt>()
            val n = 4
            for (i in 0..n) {
                val t = i.toFloat() / n
                // gentle upward curve toward the tip
                val curve = t * t * 0.18f
                val px = origin.x + dirX * length * t
                val py = origin.y + dirY * length * t - length * curve
                pts.add(Pt(px, py))
            }
            val tip = pts.last()
            val leafR = safeW * (0.035f + 0.06f * lf) * (0.5f + 0.5f * growth)
            val leaves = ArrayList<Pair<Pt, Float>>()
            if (level >= 1) {
                val clusters = 1 + level / 2 // 1..6
                for (i in 0 until clusters) {
                    val t = 0.45f + 0.55f * (i.toFloat() / clusters.coerceAtLeast(1))
                    val p = pointAt(pts, t)
                    val offset = if (i % 2 == 0) -leafR * 0.5f else leafR * 0.3f
                    leaves.add(Pt(p.x, p.y + offset) to leafR * (0.8f + 0.2f * (i % 3)))
                }
                leaves.add(tip to leafR * 1.1f)
            }
            val medR = safeW * 0.045f
            val medallion = Pt(tip.x + dirX * medR * 0.9f, tip.y - medR * 0.4f)
            branches.add(
                BranchLayout(
                    branch = slot.branch, level = level, points = pts, thickness = thickness,
                    leaves = leaves, medallion = medallion, medallionRadius = medR, glowing = level >= 5
                )
            )
        }
        return TreeLayout(
            width = safeW, height = safeH, base = base, top = top, trunk = trunk, trunkThickness = trunkThickness,
            roots = roots, branches = branches, averageLevel = avg, growth = growth
        )
    }

    /** Point on the trunk at fraction f (0 = base, 1 = top). */
    private fun trunkPoint(trunk: List<Pt>, f: Float): Pt = pointAt(trunk, f)

    /** Linear interpolation along a polyline by parameter t in 0..1 (by segment index, not arc length). */
    fun pointAt(points: List<Pt>, t: Float): Pt {
        if (points.isEmpty()) return Pt(0f, 0f)
        if (points.size == 1) return points[0]
        val tt = t.coerceIn(0f, 1f)
        val scaled = tt * (points.size - 1)
        val i = scaled.toInt().coerceIn(0, points.size - 2)
        val frac = scaled - i
        val a = points[i]
        val b = points[i + 1]
        return Pt(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
    }
}
