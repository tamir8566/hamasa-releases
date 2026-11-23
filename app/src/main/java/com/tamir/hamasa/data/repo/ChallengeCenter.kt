package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.Deload
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.domain.game.SeasonVoice
import com.tamir.hamasa.domain.game.WeeklyChallenges
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Data side of "אתגר שבועי": assigns the week's challenge once (Prefs.challengeWeek / challengeId, so it never
 * changes mid-week), counts the week from the database, and pays the reward once per week
 * (Prefs.challengeDoneWeek) through GameRepository.grant with a ledger reason "challenge:<week>:<id>".
 * Badges "challenge_4" / "challenge_10" from Prefs.challengesCompleted (also in BadgeCatalog through GameFacts).
 */
object ChallengeCenter {

    data class State(
        val weekStart: Long,
        val challenge: WeeklyChallenges.Challenge,
        val progress: Int,
        val done: Boolean,
        val daysLeft: Int,
        /** Shambiuluk's line for the card. */
        val lineHe: String
    )

    /** Completion that happened while a session was being finished (for the done screen). */
    private val completedBySession = ConcurrentHashMap<Long, String>()

    fun completedIn(sessionId: Long): String? = completedBySession[sessionId]

    /** The week's challenge with its progress; pays the reward when it was just reached. Null only on failure. */
    suspend fun refresh(today: Long = Days.today(), sessionId: Long? = null): State? = runCatching {
        val weekStart = Days.weekStartOf(today)
        val c = ensureAssigned(weekStart, today)
        val counts = countWeek(weekStart, today)
        val progress = WeeklyChallenges.progress(c, counts)
        var justDone = false
        if (progress >= c.target) {
            AppGraph.prefs.update { p ->
                if (p.challengeDoneWeek == weekStart) p
                else { justDone = true; p.copy(challengeDoneWeek = weekStart, challengesCompleted = p.challengesCompleted + 1) }
            }
            if (justDone) {
                AppGraph.game.grant(WeeklyChallenges.REWARD_XP, WeeklyChallenges.REWARD_GOLD, "challenge:$weekStart:${c.id}")
                val total = AppGraph.prefs.current().challengesCompleted
                if (total >= 4) AppGraph.game.unlockBadge(WeeklyChallenges.BADGE_FOUR)
                if (total >= 10) AppGraph.game.unlockBadge(WeeklyChallenges.BADGE_TEN)
                if (sessionId != null) completedBySession[sessionId] = c.titleHe
            }
        }
        val done = AppGraph.prefs.current().challengeDoneWeek == weekStart
        val line = when {
            done -> SeasonVoice.challengeDone(weekStart)
            progress > 0 -> SeasonVoice.challengeProgress(weekStart)
            else -> SeasonVoice.challengeNew(weekStart)
        }
        State(weekStart, c, progress, done, (weekStart + 6 - today + 1).toInt().coerceIn(1, 7), line)
    }.getOrNull()

    /** A records / volume challenge does not belong in a deload week: an unfinished one is replaced. */
    suspend fun onDeloadAccepted(deloadWeekStart: Long) {
        val today = Days.today()
        val weekStart = Days.weekStartOf(today)
        if (deloadWeekStart != weekStart) return
        val p = AppGraph.prefs.current()
        if (p.challengeWeek != weekStart || p.challengeDoneWeek == weekStart) return
        val cur = WeeklyChallenges.byId(p.challengeId) ?: return
        if (!cur.pushesLoad) return
        val pick = WeeklyChallenges.pick(weekStart, eligibility(weekStart, today), cur.id)
        AppGraph.prefs.update { it.copy(challengeId = pick.id) }
    }

    private suspend fun ensureAssigned(weekStart: Long, today: Long): WeeklyChallenges.Challenge {
        val p = AppGraph.prefs.current()
        if (p.challengeWeek == weekStart) {
            val c = WeeklyChallenges.byId(p.challengeId)
            if (c != null) return c
        }
        val pick = WeeklyChallenges.pick(weekStart, eligibility(weekStart, today), p.challengeId)
        AppGraph.prefs.update { cur ->
            if (cur.challengeWeek == weekStart && WeeklyChallenges.byId(cur.challengeId) != null) cur
            else cur.copy(challengeWeek = weekStart, challengeId = pick.id)
        }
        return WeeklyChallenges.byId(AppGraph.prefs.current().challengeId) ?: pick
    }

    private suspend fun eligibility(weekStart: Long, today: Long): WeeklyChallenges.Eligibility {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val done = db.sessions().doneSessionsOnce()
        val gym = done.filter { it.type == SessionType.GYM }
        val meals = runCatching { db.meals().allOnce() }.getOrDefault(emptyList())
        val mealDays14 = meals.filter { it.dateEpochDay > today - 14 }.map { it.dateEpochDay }.distinct().size
        val photo30 = meals.count { it.dateEpochDay > today - 30 && it.source == "photo" }
        val watch = WatchStore.current()
        val stepsRecent = watch.connected && watch.steps.keys.any { it > today - 7 }
        return WeeklyChallenges.Eligibility(
            hasMeals = mealDays14 >= 3,
            hasPhotoMeals = photo30 >= 5,
            hasWatchSteps = stepsRecent,
            gymSessionsEver = gym.size,
            gymLast28 = gym.count { today - it.dateEpochDay < 28L },
            sleepReportsLast28 = done.count { it.sleepHours != null && today - it.dateEpochDay < 28L },
            deloadWeek = Deload.activeOn(p.deloadWeekStart, weekStart) || Deload.activeOn(p.deloadWeekStart, today)
        )
    }

    private suspend fun countWeek(weekStart: Long, today: Long): WeeklyChallenges.WeekCounts {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val end = minOf(weekStart + 6, today)
        val week = db.sessions().doneSessionsOnce().filter { it.dateEpochDay in weekStart..end }
        val zone = ZoneId.systemDefault()
        var midweek = 0; var weekend = 0; var early = 0; var full = 0; var sleepGood = 0; var legs = 0
        var records = 0; var workingSets = 0
        val branchCache = HashMap<String, Branch?>()
        for (s in week) {
            val dow = Days.of(s.dateEpochDay).dayOfWeek.value   // 1 = Monday .. 7 = Sunday
            if (s.type == SessionType.GYM && dow in 1..3) midweek++
            if (Days.isWeekend(s.dateEpochDay)) weekend++
            val start = s.startedAtMillis
            if (start != null && Instant.ofEpochMilli(start).atZone(zone).hour < 9) early++
            if ((s.sleepHours ?: 0f) >= 7f) sleepGood++
            val sets = db.sessions().setsForOnce(s.id)
            records += sets.filter { it.isPr }.map { it.exerciseId }.distinct().size
            if (s.type == SessionType.GYM) {
                val done = sets.filter { it.completed }
                if (sets.size >= 3 && done.size == sets.size) full++
                workingSets += done.count { !ExerciseCatalog.isMobility(it.exerciseId) }
                var hasLegs = false
                for (st in done) {
                    val b = if (branchCache.containsKey(st.exerciseId)) branchCache[st.exerciseId]
                        else db.exercises().byId(st.exerciseId)?.branch.also { branchCache[st.exerciseId] = it }
                    if (b == Branch.LEGS) { hasLegs = true; break }
                }
                if (hasLegs) legs++
            }
        }
        val weighIns = runCatching { db.weights().allOnce() }.getOrDefault(emptyList()).count { it.dateEpochDay in weekStart..end }

        // food: protein counts as soon as it is reached; kcal and "all photographed" only for closed days
        var proteinDays = 0; var kcalDays = 0; var photoDays = 0; var mealDays = 0
        val bw = runCatching { db.weights().latestOnce()?.kg }.getOrNull() ?: p.startWeightKg
        val proteinGoal = runCatching {
            Nutrition.targets(p.kcalGoal, bw, p.proteinGoal, p.carbsGoal, p.fatGoal, p.heightCm, p.bodyFatPct).proteinG
        }.getOrDefault(0)
        for (day in weekStart..end) {
            val meals = runCatching { db.meals().forDayOnce(day) }.getOrDefault(emptyList())
            if (meals.isEmpty()) continue
            val closed = day < today || p.lastCloseDay == today
            if (meals.size >= 2) mealDays++
            if (proteinGoal > 0 && meals.sumOf { it.proteinG.toDouble() } >= proteinGoal) proteinDays++
            val kcal = meals.sumOf { it.kcal }
            if (closed && meals.size >= 2 && p.kcalGoal > 0 && abs(kcal - p.kcalGoal) <= p.kcalGoal / 10) kcalDays++
            if (closed && Nutrition.allPhotographed(meals.map { it.source })) photoDays++
        }

        // steps from the watch
        val steps = WatchStore.current().steps.filterKeys { it in weekStart..end }.values.map { it.total }
        return WeeklyChallenges.WeekCounts(
            gymSessions = week.count { it.type == SessionType.GYM },
            activeDays = week.map { it.dateEpochDay }.distinct().size,
            midweekGym = midweek,
            weekendSessions = weekend,
            yoga = week.count { it.type == SessionType.YOGA },
            walks = week.count { it.type == SessionType.WALK },
            earlySessions = early,
            distinctTypes = week.map { it.type }.distinct().size,
            fullGymSessions = full,
            sleepGoodSessions = sleepGood,
            legsSessions = legs,
            records = records,
            workingSets = workingSets,
            weighIns = weighIns,
            proteinDays = proteinDays,
            kcalDays = kcalDays,
            photoDays = photoDays,
            mealDays = mealDays,
            maxSteps = steps.maxOrNull() ?: 0,
            steps10kDays = steps.count { it >= 10_000 }
        )
    }
}
