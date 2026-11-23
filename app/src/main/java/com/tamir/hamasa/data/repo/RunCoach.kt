package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.RunPlan

/** How the run felt, asked at the end of a plan run. */
enum class RunFeel(val nameHe: String) { EASY("קל"), OK("סביר"), HARD("קשה") }

/** One RUN session for the runs page (app or watch). 0 / null = unknown. */
data class RunEntry(
    val sessionId: Long,
    val day: Long,
    val durationSec: Int,
    val distanceM: Int,
    val avgHr: Int,
    /** "שבוע 3 · אימון 2" for plan runs, "" for free runs. */
    val planLabelHe: String,
    val fromWatch: Boolean,
    /** Minutes in heart rate zones 1..5 from the watch (HrZones); empty when not measured. */
    val zonesMin: List<Int> = emptyList()
) {
    /** Seconds per km, or null without a distance. */
    val paceSecPerKm: Int?
        get() = if (distanceM >= 200 && durationSec > 0) (durationSec * 1000L / distanceM).toInt() else null
}

/** What the runs card / page shows about the plan. */
data class RunStatus(
    val week: Int,
    val day: Int,
    val finished: Boolean,
    /** A run today would be the second run day in a row (or he already ran today). */
    val restToday: Boolean,
    val doneSessions: Int,
    val lastResult: String
) {
    val labelHe: String get() = RunPlan.labelHe(week, day)
    val plannedToday: Boolean get() = !finished && !restToday
}

/**
 * The running coach's progress (domain/coach/RunPlan for the sessions). State lives in Prefs (runWeek, runDay,
 * runLastDay, runLastSessionId, runLastResult, runHrCheckSessionId, runWatchCheckedId) and goes to the cloud.
 *
 * Rules (docs/coach_logic.md, "מאמן הריצה"):
 *  - a plan run that was completed and felt "קל" / "סביר" moves to the next session (3 per week, 9 weeks);
 *  - felt "קשה", or the watch average heart rate above 85% of the max (Tanaka: 208 − 0.7 × age) -> the week repeats
 *    from session 1. The heart rate often arrives minutes later: the check then runs on the next [sync];
 *  - stopped before the end -> the same session again;
 *  - never two run days in a row: the day after a run the plan says rest (walks and the gym are fine);
 *  - a run imported from the watch ("hc:" / "sh:") is a free run; it moves the plan only when it lasted at least the
 *    whole planned session (warm-up and cool-down included) and the rest-day rule holds.
 */
object RunCoach {
    const val NOTES_PREFIX = "run:"
    private const val HR_LIMIT_PCT = 85
    private const val HR_WAIT_DAYS = 2L

    /** Session notes of a plan run: "run:<week>:<day>". A finished plan repeats its last session. */
    fun notesFor(week: Int, day: Int): String =
        if (week > RunPlan.WEEKS) "$NOTES_PREFIX${RunPlan.WEEKS}:${RunPlan.DAYS_PER_WEEK}" else "$NOTES_PREFIX$week:$day"

    /** (week, day) of a plan run's notes, or null. */
    fun parseNotes(notes: String): Pair<Int, Int>? {
        if (!notes.startsWith(NOTES_PREFIX)) return null
        val parts = notes.removePrefix(NOTES_PREFIX).split(':')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val d = parts[1].toIntOrNull() ?: return null
        if (w !in 1..RunPlan.WEEKS || d !in 1..RunPlan.DAYS_PER_WEEK) return null
        return Pair(w, d)
    }

    fun status(p: Profile, today: Long): RunStatus {
        val finished = p.runWeek > RunPlan.WEEKS
        val rest = p.runLastDay > 0L && today - p.runLastDay < 2L
        return RunStatus(
            week = p.runWeek.coerceIn(1, RunPlan.WEEKS),
            day = p.runDay.coerceIn(1, RunPlan.DAYS_PER_WEEK),
            finished = finished,
            restToday = rest,
            doneSessions = if (finished) RunPlan.TOTAL_SESSIONS else RunPlan.doneBefore(p.runWeek, p.runDay),
            lastResult = p.runLastResult
        )
    }

    /** Short serious line about the last plan result ("" = nothing to say). */
    fun resultLineHe(result: String): String = when (result) {
        "advanced" -> "האימון הקודם הושלם. ממשיכים לאימון הבא."
        "repeat_hard" -> "האימון הקודם הרגיש קשה, לכן חוזרים על השבוע מהאימון הראשון."
        "repeat_hr" -> "הדופק הממוצע באימון הקודם היה גבוה מדי, לכן חוזרים על השבוע."
        "short" -> "האימון הקודם לא הושלם. אותו אימון שוב."
        "watch" -> "ריצה מהשעון באורך מלא נספרה כאימון בתוכנית."
        else -> ""
    }

    /** Max heart rate (Tanaka) and the "too high" average for an easy run/walk session. Age 0 = unknown (30). */
    fun hrLimit(age: Int): Int {
        val a = if (age in 12..90) age else 30
        val max = 208.0 - 0.7 * a
        return (max * HR_LIMIT_PCT / 100.0).toInt()
    }

    private fun advance(week: Int, day: Int): Pair<Int, Int> =
        if (day >= RunPlan.DAYS_PER_WEEK) Pair(week + 1, 1) else Pair(week, day + 1)

    /**
     * Called after a plan run was finished (WorkoutViewModel.finish). [completedAll] = the timer reached the end.
     * Only the session that IS the next plan session moves the plan.
     */
    suspend fun afterRun(sessionId: Long, feel: RunFeel?, completedAll: Boolean) {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return
        val wd = parseNotes(s.notes)
        val p0 = AppGraph.prefs.current()
        val hr = WatchStore.hrFor(sessionId)
        val limit = hrLimit(p0.age)
        AppGraph.prefs.update { p ->
            val lastDay = maxOf(p.runLastDay, s.dateEpochDay)
            if (wd == null || p.runWeek > RunPlan.WEEKS || wd.first != p.runWeek || wd.second != p.runDay) {
                return@update p.copy(runLastDay = lastDay)
            }
            val hrHigh = hr != null && hr.avgHr > limit
            when {
                !completedAll -> p.copy(runLastDay = lastDay, runLastSessionId = sessionId, runLastResult = "short", runHrCheckSessionId = 0L)
                feel == RunFeel.HARD -> p.copy(runLastDay = lastDay, runLastSessionId = sessionId, runLastResult = "repeat_hard",
                    runDay = 1, runHrCheckSessionId = 0L)
                hrHigh -> p.copy(runLastDay = lastDay, runLastSessionId = sessionId, runLastResult = "repeat_hr",
                    runDay = 1, runHrCheckSessionId = 0L)
                else -> {
                    val next = advance(p.runWeek, p.runDay)
                    p.copy(runLastDay = lastDay, runLastSessionId = sessionId, runLastResult = "advanced",
                        runWeek = next.first, runDay = next.second,
                        // the watch heart rate usually arrives later: check it on the next sync
                        runHrCheckSessionId = if (hr == null) sessionId else 0L)
                }
            }
        }
        runCatching { CloudSync.pushSoon(AppGraph.appContext) }
    }

    /**
     * Late work, safe to call often (coach screen, runs page): the delayed heart-rate check of the last advanced
     * run, and watch-imported runs that may count as plan sessions.
     */
    suspend fun sync() {
        val today = Days.today()
        var changed = false
        // 1) heart rate that arrived after the run
        val p = AppGraph.prefs.current()
        val pending = p.runHrCheckSessionId
        if (pending > 0L) {
            val s = AppGraph.db.sessions().byId(pending)
            val hr = WatchStore.hrFor(pending)
            val wd = s?.let { parseNotes(it.notes) }
            val sessionDay = s?.dateEpochDay ?: 0L
            val weekOfRun = wd?.first ?: 0
            if (weekOfRun == 0) {
                AppGraph.prefs.update { it.copy(runHrCheckSessionId = 0L) }
                changed = true
            } else if (hr != null) {
                val high = hr.avgHr > hrLimit(p.age)
                AppGraph.prefs.update { cur ->
                    if (high && cur.runLastSessionId == pending) {
                        cur.copy(runWeek = weekOfRun, runDay = 1, runLastResult = "repeat_hr", runHrCheckSessionId = 0L)
                    } else cur.copy(runHrCheckSessionId = 0L)
                }
                changed = true
            } else if (today - sessionDay > HR_WAIT_DAYS) {
                AppGraph.prefs.update { it.copy(runHrCheckSessionId = 0L) }
                changed = true
            }
        }
        // 2) runs from the watch
        val done = AppGraph.db.sessions().doneSessionsOnce()
        val watchRuns = done.filter { it.type == SessionType.RUN && WatchSync.isWatchSession(it) }.sortedBy { it.id }
        val maxId = watchRuns.lastOrNull()?.id ?: 0L
        val checked = AppGraph.prefs.current().runWatchCheckedId
        if (checked <= 0L) {
            // first time: earlier watch runs never move the plan, they only count for the rest-day rule
            val lastDay = watchRuns.maxOfOrNull { it.dateEpochDay } ?: 0L
            AppGraph.prefs.update { it.copy(runWatchCheckedId = maxOf(1L, maxId), runLastDay = maxOf(it.runLastDay, lastDay)) }
            changed = true
        } else {
            for (s in watchRuns) {
                if (s.id <= checked) continue
                val hr = WatchStore.hrFor(s.id)
                AppGraph.prefs.update { cur ->
                    val restOk = cur.runLastDay <= 0L || s.dateEpochDay - cur.runLastDay >= 2L
                    val lastDay = maxOf(cur.runLastDay, s.dateEpochDay)
                    val inPlan = cur.runWeek <= RunPlan.WEEKS
                    val longEnough = inPlan && s.durationSec >= RunPlan.totalSec(cur.runWeek, cur.runDay)
                    if (inPlan && restOk && longEnough) {
                        val high = hr != null && hr.avgHr > hrLimit(cur.age)
                        if (high) cur.copy(runDay = 1, runLastDay = lastDay, runLastSessionId = s.id, runLastResult = "repeat_hr", runWatchCheckedId = s.id)
                        else {
                            val next = advance(cur.runWeek, cur.runDay)
                            cur.copy(runWeek = next.first, runDay = next.second, runLastDay = lastDay, runLastSessionId = s.id,
                                runLastResult = "watch", runWatchCheckedId = s.id)
                        }
                    } else cur.copy(runLastDay = lastDay, runWatchCheckedId = s.id)
                }
                changed = true
            }
        }
        if (changed) runCatching { CloudSync.pushSoon(AppGraph.appContext) }
    }

    /** "התחל ריצה": the next plan session as a live workout. Returns the session id. */
    suspend fun startNext(): Long {
        val p = AppGraph.prefs.current()
        val week = if (p.runWeek > RunPlan.WEEKS) RunPlan.WEEKS else p.runWeek
        val day = if (p.runWeek > RunPlan.WEEKS) RunPlan.DAYS_PER_WEEK else p.runDay
        val plan = AppGraph.workouts.runPlan(week, day)
        return AppGraph.workouts.startSession(plan, null, null, notes = notesFor(week, day))
    }

    /** Every finished RUN session, newest first, with the watch extras when known. */
    suspend fun history(): List<RunEntry> {
        val watch = WatchStore.current().sessions
        return AppGraph.db.sessions().doneSessionsOnce().filter { it.type == SessionType.RUN }.map { s -> entryOf(s, watch[s.id]) }
    }

    private fun entryOf(s: SessionEntity, w: com.tamir.hamasa.data.health.SessionWatch?): RunEntry {
        val wd = parseNotes(s.notes)
        return RunEntry(
            sessionId = s.id,
            day = s.dateEpochDay,
            durationSec = s.durationSec,
            distanceM = w?.distanceM ?: 0,
            avgHr = w?.avgHr ?: 0,
            planLabelHe = if (wd != null) RunPlan.labelHe(wd.first, wd.second) else "",
            fromWatch = WatchSync.isWatchSession(s),
            zonesMin = w?.zonesMin ?: emptyList()
        )
    }
}
