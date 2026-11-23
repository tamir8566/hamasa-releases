package com.tamir.hamasa.ui.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.repo.BranchExerciseBest
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.GearItem
import com.tamir.hamasa.domain.tree.TreeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil

/** One gear item the branch helps to unlock, with its requirement in Hebrew and whether it is already reachable. */
data class GearUnlock(
    val item: GearItem,
    val requirementHe: String,
    val unlocked: Boolean
)

/** What the detail dialog shows after tapping a medallion. */
data class BranchDetail(
    val branch: Branch,
    val level: Int,
    val mainExerciseHe: String,
    val bestHe: String,
    /** Factual explanation of what the current level means (coach language). */
    val meaningHe: String,
    /** What is needed for the next level, in coach language. */
    val nextHintHe: String,
    /** Gear whose requirements this branch feeds, lowest requirement first. */
    val gear: List<GearUnlock>,
    /** Every exercise of the branch with its best set, strongest first. */
    val exercises: List<BranchExerciseBest> = emptyList(),
    /** One Hebrew line per exercise: best set, estimated one-rep max, date. Same order as [exercises]. */
    val exerciseLinesHe: List<String> = emptyList()
)

class TreeViewModel : ViewModel() {
    private val _levels = MutableStateFlow<Map<Branch, Int>>(emptyMap())
    val levels: StateFlow<Map<Branch, Int>> = _levels

    private val _detail = MutableStateFlow<BranchDetail?>(null)
    val detail: StateFlow<BranchDetail?> = _detail

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _levels.value = AppGraph.game.branchLevels()
        }
    }

    fun select(branch: Branch) {
        viewModelScope.launch {
            val levels = _levels.value
            val level = levels[branch] ?: 0
            val bodyWeight = AppGraph.db.weights().latestOnce()?.kg ?: AppGraph.prefs.current().startWeightKg
            val stats = TreeModel.stats(levels)
            val gear = gearFor(branch, stats)
            // every exercise of the branch that has a completed set (catalog + custom), strongest first
            val bests = AppGraph.game.exerciseBests()
                .filter { it.branch == branch && !ExerciseCatalog.isMobility(it.exerciseId) && it.exerciseId != "walk" }
                .sortedWith(compareByDescending<BranchExerciseBest> { it.ratio }.thenByDescending { it.oneRmKg })
            val top = bests.firstOrNull { it.ratio > 0f }
            val progress = AppGraph.game.branchProgress()[branch] ?: Pair(0, 0)
            val detail = when (branch) {
                Branch.CHEST, Branch.BACK, Branch.LEGS, Branch.SHOULDERS_ARMS, Branch.CORE -> {
                    BranchDetail(
                        branch, level,
                        top?.nameHe ?: TreeModel.referenceHe(branch),
                        top?.let { setHe(it) } ?: "עדיין אין",
                        progressMeaning(level, progress.first, progress.second),
                        progressNext(level, progress.first, progress.second),
                        gear, bests
                    )
                }
                Branch.ENDURANCE -> {
                    // only sessions after the tree opened (level 4) count, like the branch level itself
                    val count = AppGraph.game.enduranceSessions()
                    val meaning = "$count אימוני סיבולת בארבעת השבועות האחרונים מתוך יעד של 10. ${levelBandHe(level)}"
                    val next = if (level >= 10) "הענף הגיע לשיאו: עשרה אימוני סיבולת בארבעה שבועות."
                    else {
                        val needed = ceil(1.0 + 0.9 * (level + 1)).toInt()
                        "לרמה הבאה: $needed אימוני יוגה, הליכה או ריצה בתוך ארבעה שבועות."
                    }
                    BranchDetail(branch, level, "יוגה, הליכה וריצה", "$count אימונים בארבעת השבועות האחרונים", meaning, next, gear, bests)
                }
            }
            val lines = detail.exercises.map { b ->
                listOf(setHe(b), oneRmHe(b), dateHe(b)).filter { it.isNotEmpty() }.joinToString(" | ")
            }
            _detail.value = detail.copy(exerciseLinesHe = lines)
        }
    }

    /** "60 ק\"ג × 6" for weights, "45 שניות" for holds, "12 חזרות" for bodyweight. */
    fun setHe(b: BranchExerciseBest): String = when (b.unit) {
        "sec" -> "${b.reps} שניות"
        "kg" -> "${kg(b.weightKg)} × ${b.reps}"
        else -> "${b.reps} חזרות"
    }

    fun oneRmHe(b: BranchExerciseBest): String = if (b.unit == "kg" && b.oneRmKg > 0f) "חזרה אחת משוערת: ${kg(b.oneRmKg)}" else ""

    fun dateHe(b: BranchExerciseBest): String {
        if (b.dateEpochDay <= 0L) return ""
        val d = Days.of(b.dateEpochDay)
        return "${d.dayOfMonth}.${d.monthValue}.${d.year}"
    }

    private fun meaningRatio(level: Int, top: BranchExerciseBest?, bodyWeight: Float): String {
        if (top == null) return "הענף רדום: עדיין לא נרשם סט עם משקל באף תרגיל של הענף. ${levelBandHe(level)}"
        val pct = (top.ratio * 100f).toInt()
        return "השיא החזק ביותר: ${top.nameHe}, שווה ערך ל-$pct% ממשקל הגוף (${kg(bodyWeight)}) בתרגיל הייחוס. ${levelBandHe(level)}"
    }

    /** Next level: estimated 1RM on the branch's reference lift, rounded up to 2.5 kg. */
    private fun nextRatioHint(branch: Branch, level: Int, bodyWeight: Float): String {
        if (level >= 10) return "הענף הגיע לשיאו. ממשיכים לשמור על הטכניקה והמשקל."
        val t = TreeModel.thresholds(branch)
        val ratio = t.first + (t.second - t.first) * (level + 1) / 10f
        val ref = TreeModel.referenceHe(branch)
        if (bodyWeight <= 0f) return "לרמה הבאה: $ref ביחס של ${String.format(Locale.US, "%.2f", ratio)} ממשקל הגוף."
        val target = ceil(ratio * bodyWeight / 2.5f) * 2.5f
        return "לרמה הבאה: חזרה אחת משוערת של ${kg(target.toFloat())} ב$ref (${(ratio * 100f).toInt()}% ממשקל הגוף), או המקבילה בתרגיל אחר של הענף."
    }

    private fun progressMeaning(level: Int, records: Int, sessions: Int): String =
        "מאז שהעץ נפתח: שיאים שנשברו בענף: $records. אימונים שעבדו עליו: $sessions. ${levelBandHe(level)}"

    /** What the next level needs: more records OR more sessions (whichever comes first). */
    private fun progressNext(level: Int, records: Int, sessions: Int): String {
        if (level >= 10) return "הענף הגיע לשיאו. ממשיכים לשמור עליו."
        var r = 0
        while (TreeModel.progressLevel(records + r, sessions) <= level && r < 40) r++
        var s = 0
        while (TreeModel.progressLevel(records, sessions + s) <= level && s < 80) s++
        return "לרמה הבאה: עוד $r שיאים בענף, או עוד $s אימונים שעובדים עליו."
    }

    fun clearSelection() {
        _detail.value = null
    }

    /** Which derived stats a branch feeds (see TreeModel.stats). */
    private fun gearFor(branch: Branch, stats: TreeModel.Stats): List<GearUnlock> {
        val feedsUpper = branch == Branch.CHEST || branch == Branch.BACK || branch == Branch.SHOULDERS_ARMS
        val feedsLower = branch == Branch.LEGS
        val feedsStability = branch == Branch.CORE || branch == Branch.ENDURANCE
        val feedsEndurance = branch == Branch.ENDURANCE
        val out = ArrayList<Pair<Int, GearUnlock>>()
        for (item in GearCatalog.all) {
            val req = when {
                feedsUpper && item.requiresUpper > 0 -> item.requiresUpper to "כוח עליון ${item.requiresUpper}"
                feedsLower && item.requiresLower > 0 -> item.requiresLower to "כוח תחתון ${item.requiresLower}"
                feedsEndurance && item.requiresEndurance > 0 -> item.requiresEndurance to "סיבולת ${item.requiresEndurance}"
                feedsStability && item.requiresStability > 0 -> item.requiresStability to "יציבות ${item.requiresStability}"
                else -> null
            } ?: continue
            val unlocked = GearCatalog.canEquip(item, stats.upper, stats.lower, stats.stability, stats.endurance)
            out.add(req.first to GearUnlock(item, req.second, unlocked))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private fun levelBandHe(level: Int): String = when {
        level <= 0 -> "רמה 0: הסף הראשון עדיין לא נחצה."
        level <= 3 -> "רמה $level מתוך 10: בסיס בבנייה."
        level <= 6 -> "רמה $level מתוך 10: כוח בינוני ויציב."
        level <= 9 -> "רמה $level מתוך 10: כוח מתקדם."
        else -> "רמה 10 מתוך 10: שיא הענף."
    }

    private fun kg(v: Float?): String {
        if (v == null || v <= 0f) return "עדיין אין"
        val whole = v.toInt()
        val s = if (v == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", v)
        return "$s ק\"ג"
    }
}
