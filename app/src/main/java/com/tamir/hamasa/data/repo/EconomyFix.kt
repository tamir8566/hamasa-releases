package com.tamir.hamasa.data.repo

import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.LedgerEntity
import com.tamir.hamasa.data.db.SetLogEntity

/**
 * One-time correction (approved by Tamir) of the first-workout bug where every exercise done for the first time
 * counted as a personal record. Re-derives records with the current rules (a record needs an earlier session to
 * beat, one per exercise per session, at most [WorkoutRepository.PR_REWARD_CAP] paid), takes back the extra
 * xp/gold, and removes record badges that no session earns anymore (with their rewards, and the gear item a
 * legendary badge handed out at the same moment, unless he bought it). Runs once; logs a ledger line.
 */
object EconomyFix {
    private const val TAG = "EconomyFix"
    private val PR_BADGES = mapOf(
        "first_trophy" to Pair(50, 10),
        "double_pr" to Pair(50, 10),
        "lightning" to Pair(150, 40),
        "five_pr" to Pair(400, 120)
    )

    suspend fun runOnce() {
        val prefs = AppGraph.prefs
        if (prefs.current().economyFix1Done) return
        try {
            fix()
        } catch (e: Exception) {
            Log.w(TAG, "fix failed", e)
        }
        prefs.update { it.copy(economyFix1Done = true) }
    }

    private suspend fun fix() {
        val db = AppGraph.db
        val sessions = db.sessions().doneSessionsOnce().sortedBy { it.dateEpochDay * 10_000_000L + it.id }
        if (sessions.isEmpty()) return

        // completed sets of earlier sessions, per exercise, built up session by session
        val earlier = HashMap<String, MutableList<SetLogEntity>>()
        var takeXp = 0
        var takeGold = 0
        var anyPr = false
        var maxPrsInSession = 0

        for (s in sessions) {
            val sets = db.sessions().setsForOnce(s.id)
            val oldPrCount = sets.filter { it.isPr }.map { "${it.exerciseId}:${it.weight.toInt()}" }.distinct().size
            val prExercises = HashSet<String>()
            for (st in sets.sortedBy { it.id }) {
                var isPr = false
                if (st.completed && st.weight > 0f && st.exerciseId !in prExercises) {
                    val before = earlier[st.exerciseId].orEmpty()
                    if (before.isNotEmpty()) {
                        val best = before.filter { it.reps >= st.reps.coerceAtLeast(1) }.maxOfOrNull { it.weight } ?: 0f
                        isPr = st.weight > best && st.reps >= st.targetReps
                    }
                }
                if (isPr) prExercises.add(st.exerciseId)
                if (isPr != st.isPr) db.sessions().updateSet(st.copy(isPr = isPr))
            }
            val newPaid = prExercises.size.coerceAtMost(WorkoutRepository.PR_REWARD_CAP)
            if (prExercises.isNotEmpty()) anyPr = true
            if (prExercises.size > maxPrsInSession) maxPrsInSession = prExercises.size
            val extra = (oldPrCount - newPaid).coerceAtLeast(0)
            if (extra > 0) {
                val dx = 25 * extra
                val dg = 5 * extra
                takeXp += dx
                takeGold += dg
                db.sessions().update(s.copy(xpEarned = (s.xpEarned - dx).coerceAtLeast(0), goldEarned = (s.goldEarned - dg).coerceAtLeast(0)))
            }
            for (st in sets) if (st.completed) earlier.getOrPut(st.exerciseId) { mutableListOf() }.add(st)
        }

        // record badges that no session earns under the corrected rules
        val owned = db.game().badgesOnce().associateBy { it.id }
        val stillEarned = mapOf(
            "first_trophy" to anyPr,
            "double_pr" to (maxPrsInSession >= 2),
            "lightning" to (maxPrsInSession >= 3),
            "five_pr" to (maxPrsInSession >= 5)
        )
        val remove = PR_BADGES.keys.filter { it in owned && stillEarned[it] != true }
        for (id in remove) {
            val (bx, bg) = PR_BADGES.getValue(id)
            takeXp += bx
            takeGold += bg
        }
        if (remove.isNotEmpty()) db.game().deleteBadges(remove)

        // the gear item the legendary "five_pr" handed out together with it (not one he bought)
        val five = owned["five_pr"]
        if (five != null && "five_pr" in remove) {
            val bought = db.game().ledgerOnce().filter { it.reason.startsWith("shop:gear:") }.map { it.reason.removePrefix("shop:gear:") }.toSet()
            val gift = db.game().inventoryOnce().filter { kotlin.math.abs(it.acquiredAtMillis - five.unlockedAtMillis) < 120_000L && it.itemId !in bought }
            for (g in gift) db.game().deleteItem(g.itemId)
        }

        if (takeXp == 0 && takeGold == 0) return
        AppGraph.prefs.update { it.copy(xp = (it.xp - takeXp).coerceAtLeast(0), gold = (it.gold - takeGold).coerceAtLeast(0)) }
        db.game().ledger(LedgerEntity(atMillis = System.currentTimeMillis(), xp = -takeXp, gold = -takeGold, reason = "fix:records"))
        Log.i(TAG, "took back xp=$takeXp gold=$takeGold badges=$remove")
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
    }
}
