package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.WeakFacts

/**
 * Builds the [WeakFacts] of one session for BossWeakness (weekly boss and event boss). Read only.
 * Works for a finished session (finishSession) and for a live one (the workout bar, with the sets done so far).
 */
object BossFacts {

    private val STRENGTH_BRANCHES = setOf(Branch.CHEST, Branch.BACK, Branch.LEGS, Branch.SHOULDERS_ARMS)

    /** Protein goal used when Prefs.proteinGoal is not set: 1.6 g per kg of the latest weight. */
    private const val FALLBACK_PROTEIN_PER_KG = 1.6f

    suspend fun collect(s: SessionEntity, sets: List<SetLogEntity>, nowMillis: Long = System.currentTimeMillis()): WeakFacts {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val day = s.dateEpochDay

        val branchesInOrder = runCatching { BossFights.branchesOf(sets) }.getOrDefault(emptyList())
        val branches = branchesInOrder.filterNotNull().toSet()
        val strengthSets = branchesInOrder.count { it != null && it in STRENGTH_BRANCHES }
        val hasSets = sets.isNotEmpty()
        val allDone = hasSets && sets.all { it.completed }
        val onTarget = allDone && sets.all { it.targetReps <= 0 || it.reps >= it.targetReps }
        val record = sets.any { it.isPr }

        val minutes = when {
            s.durationSec > 0 -> s.durationSec / 60
            s.startedAtMillis != null -> (((s.endedAtMillis ?: nowMillis) - s.startedAtMillis) / 60_000L).toInt().coerceAtLeast(0)
            else -> 0
        }

        val close = p.lastCloseDay
        val closedDay = close > 0L && (close == day - 1 || close == day)

        val meals = runCatching { db.meals().forDayOnce(day) }.getOrDefault(emptyList())
        val photoMeal = meals.any { it.source == "photo" }
        val goal = if (p.proteinGoal > 0) p.proteinGoal.toFloat() else {
            val kg = runCatching { db.weights().latestOnce()?.kg }.getOrNull() ?: p.startWeightKg
            kg * FALLBACK_PROTEIN_PER_KG
        }
        val proteinToday = meals.sumOf { it.proteinG.toDouble() }.toFloat()
        val proteinYesterday = runCatching { db.meals().forDayOnce(day - 1) }.getOrDefault(emptyList()).sumOf { it.proteinG.toDouble() }.toFloat()
        val protein = goal > 0f && (proteinToday >= goal || proteinYesterday >= goal)

        val weekStart = Days.weekStartOf(day)
        val done = runCatching { db.sessions().doneSessionsOnce() }.getOrDefault(emptyList())
        val others = done.filter { it.id != s.id && it.dateEpochDay in weekStart..(weekStart + 6) }
        var strengthEarlier = false
        for (o in others) {
            if (o.type == SessionType.GYM) { strengthEarlier = true; break }
            val os = runCatching { db.sessions().setsForOnce(o.id) }.getOrDefault(emptyList())
            val ob = runCatching { BossFights.branchesOf(os) }.getOrDefault(emptyList())
            if (ob.count { it != null && it in STRENGTH_BRANCHES } >= 3) { strengthEarlier = true; break }
        }
        val yesterday = done.any { it.id != s.id && it.dateEpochDay == day - 1 }

        return WeakFacts(
            type = s.type.name,
            branches = branches,
            strengthSets = strengthSets,
            hasSets = hasSets,
            allSetsDone = allDone,
            allOnTarget = onTarget,
            record = record,
            minutes = minutes,
            sleepHours = s.sleepHours,
            dayOfWeek = Days.of(day).dayOfWeek.value,
            closedDay = closedDay,
            photoMeal = photoMeal,
            protein = protein,
            otherTypesThisWeek = others.map { it.type.name }.toSet(),
            strengthEarlierThisWeek = strengthEarlier,
            sessionYesterday = yesterday
        )
    }
}
