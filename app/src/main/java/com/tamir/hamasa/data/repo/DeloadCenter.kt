package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.health.RestingHr
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.Deload

/**
 * Data side of "שבוע הורדת עומס": measures the signals for [Deload.propose] from the history, and stores the
 * player's answer (accept = a Sunday..Saturday deload week, "לא עכשיו" = hidden for 7 days) in Prefs.
 * The engine reads the active week through WorkoutRepository.context() (CoachContext.deload).
 */
object DeloadCenter {

    /** What the coach / home cards show. */
    data class View(
        /** Today is inside the accepted deload week. */
        val active: Boolean,
        /** The accepted week has not started yet. */
        val upcoming: Boolean,
        /** Sunday of the accepted week (0 when none). */
        val weekStart: Long,
        /** A proposal to show (null = nothing to propose, snoozed, or a week is already accepted). */
        val proposal: Deload.Proposal?
    )

    suspend fun view(today: Long = Days.today()): View {
        val p = AppGraph.prefs.current()
        val active = Deload.activeOn(p.deloadWeekStart, today)
        val upcoming = Deload.upcoming(p.deloadWeekStart, today)
        if (active || upcoming) return View(active, upcoming, p.deloadWeekStart, null)
        if (p.deloadSnoozeUntil > 0L && today < p.deloadSnoozeUntil) return View(false, false, 0L, null)
        val proposal = runCatching { Deload.propose(signals(today)) }.getOrNull()
        return View(false, false, 0L, proposal)
    }

    /** Accepts the proposal; returns the Sunday the deload week starts. */
    suspend fun accept(today: Long = Days.today()): Long {
        val start = Deload.weekToStart(today, Days.weekStartOf(today))
        AppGraph.prefs.update { it.copy(deloadWeekStart = start, deloadLastEnd = start + 6, deloadSnoozeUntil = 0L) }
        // a records challenge does not belong in a deload week
        runCatching { ChallengeCenter.onDeloadAccepted(start) }
        return start
    }

    suspend fun snooze(today: Long = Days.today()) {
        AppGraph.prefs.update { it.copy(deloadSnoozeUntil = today + Deload.SNOOZE_DAYS) }
    }

    /** Cancels an accepted (active or upcoming) week. The next proposal is snoozed for 7 days. */
    suspend fun cancel(today: Long = Days.today()) {
        AppGraph.prefs.update { p ->
            val wasActive = Deload.activeOn(p.deloadWeekStart, today)
            p.copy(
                deloadWeekStart = 0L,
                // a week already under way still counts as a deload (it ends today); one not started yet does not
                deloadLastEnd = if (wasActive) today else 0L,
                deloadSnoozeUntil = today + Deload.SNOOZE_DAYS
            )
        }
    }

    // ------------------------------------------------------------------ signals

    suspend fun signals(today: Long = Days.today()): Deload.Signals {
        val p = AppGraph.prefs.current()
        val db = AppGraph.db
        val done = db.sessions().doneSessionsOnce()
        val gym = done.filter { it.type == SessionType.GYM && !WatchSync.isWatchSession(it) }
        val thisWeek = Days.weekStartOf(today)
        // doneSessionsOnce is newest first: the last one is the first finished session
        val firstDay = done.lastOrNull()?.dateEpochDay ?: today
        val anchor = if (p.deloadLastEnd > 0L) p.deloadLastEnd + 1 else firstDay
        val anchorWeek = Days.weekStartOf(anchor)
        val weeksSince = ((thisWeek - anchorWeek) / 7L).toInt().coerceAtLeast(0)

        fun consistentIn(n: Int): Int {
            var c = 0
            for (k in 1..n) {
                val ws = thisWeek - 7L * k
                if (ws < anchorWeek) break
                if (gym.count { it.dateEpochDay in ws..(ws + 6) } >= Deload.CONSISTENT_WEEK_GYM) c++
            }
            return c
        }

        val windowStart = today - Deload.FATIGUE_WINDOW_DAYS + 1
        val recentGym = gym.filter { it.dateEpochDay >= windowStart && !Deload.activeOn(p.deloadWeekStart, it.dateEpochDay) }
        var drops = 0
        for (s in recentGym) if (runCatching { hadDrop(s) }.getOrDefault(false)) drops++

        val shortSleepDays = done
            .filter { it.dateEpochDay >= windowStart && (it.sleepHours ?: 99f) < Deload.SHORT_SLEEP_HOURS }
            .map { it.dateEpochDay }.distinct().size

        val hr = RestingHr.status(p.restingHrCsv, today)?.elevated == true
        return Deload.Signals(
            weeksSinceDeload = weeksSince,
            consistentLast6 = consistentIn(6),
            consistentLast8 = consistentIn(8),
            dropSessions = drops,
            shortSleepDays = shortSleepDays,
            restingHrElevated = hr
        )
    }

    private fun e1rm(s: SetLogEntity): Float = s.weight * (1f + s.reps / 30f)

    /** A compound lift of [s] whose best estimated 1RM fell by more than 3% against its previous finished session. */
    private suspend fun hadDrop(s: SessionEntity): Boolean {
        val db = AppGraph.db
        val sets = db.sessions().setsForOnce(s.id).filter { it.completed && it.weight > 0f && it.reps > 0 }
        for ((exId, list) in sets.groupBy { it.exerciseId }) {
            val e = db.exercises().byId(exId) ?: continue
            if (!e.isCompound || e.unit != "kg") continue
            val best = list.maxOf { e1rm(it) }
            val earlier = db.sessions().recentSetsFor(exId, 60).filter { it.sessionId < s.id && it.weight > 0f && it.reps > 0 }
            var prev: List<SetLogEntity> = emptyList()
            for (sid in earlier.map { it.sessionId }.distinct()) {
                val ps = db.sessions().byId(sid) ?: continue
                if (ps.status != SessionStatus.DONE) continue
                prev = earlier.filter { it.sessionId == sid }
                break
            }
            if (prev.isEmpty()) continue
            val prevBest = prev.maxOf { e1rm(it) }
            if (best < prevBest * (1f - Deload.DROP_SHARE)) return true
        }
        return false
    }
}
