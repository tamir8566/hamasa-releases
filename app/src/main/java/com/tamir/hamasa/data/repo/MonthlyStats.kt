package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MeasurementEntity
import com.tamir.hamasa.data.db.ProgressPhotoEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.BossCatalog
import com.tamir.hamasa.domain.game.StreakRules
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One calendar month in numbers, for "סיכום חודש". Read-only, derived from existing tables. */
data class MonthSummary(
    val month: YearMonth,
    /** Weight at the end minus weight at the start of the month (negative = lost). Null = not enough weigh-ins. */
    val kgChange: Float?,
    val startKg: Float?,
    val endKg: Float?,
    val sessions: Int,
    val records: Int,
    val bossesDefeated: List<String>,
    val badges: List<String>,
    val bestStreak: Int,
    /** Average daily kcal over days with logged meals; 0 = no meals logged. */
    val kcalAvg: Int,
    val kcalDays: Int,
    val kcalGoal: Int
) {
    val isEmpty: Boolean get() = sessions == 0 && kgChange == null && kcalDays == 0 && badges.isEmpty()
}

/** One month column of "לפני ואחרי". */
data class MonthSnapshot(
    val month: YearMonth,
    val photo: ProgressPhotoEntity?,
    /** Last weigh-in of the month (or the last one before it when the month has none). */
    val kg: Float?,
    val measurement: MeasurementEntity?
)

object MonthlyStats {

    fun firstDay(m: YearMonth): Long = m.atDay(1).toEpochDay()
    fun lastDay(m: YearMonth): Long = m.atEndOfMonth().toEpochDay()
    fun monthOf(day: Long): YearMonth = YearMonth.from(LocalDate.ofEpochDay(day))

    suspend fun summary(m: YearMonth): MonthSummary {
        val db = AppGraph.db
        val from = firstDay(m)
        val to = lastDay(m)

        // weight: last weigh-in before the month (or the first inside it) -> last weigh-in inside the month
        val weights = db.weights().allOnce()
        val inside = weights.filter { it.dateEpochDay in from..to }
        val before = weights.lastOrNull { it.dateEpochDay < from }
        val startKg = before?.kg ?: inside.firstOrNull()?.kg
        val endKg = inside.lastOrNull()?.kg
        val kgChange = if (startKg != null && endKg != null && (before != null || inside.size >= 2)) endKg - startKg else null

        val sessions = db.sessions().doneSessionsOnce().filter { it.dateEpochDay in from..to }
        var records = 0
        for (s in sessions) records += db.sessions().setsForOnce(s.id).count { it.isPr }

        // bosses: weeks whose Sunday falls in the month
        val bosses = db.game().bossesOnce()
            .filter { it.defeated && it.weekEpoch in from..to }
            .mapNotNull { row -> BossCatalog.all.firstOrNull { it.id == row.bossId }?.nameHe?.let { com.tamir.hamasa.domain.game.Addr.x(it) } }

        val zone = ZoneId.systemDefault()
        val badges = db.game().badgesOnce()
            .filter { b -> Instant.ofEpochMilli(b.unlockedAtMillis).atZone(zone).toLocalDate().toEpochDay() in from..to }
            .sortedBy { it.unlockedAtMillis }
            .mapNotNull { BadgeCatalog.byId(it.id)?.nameHe?.let { n -> com.tamir.hamasa.domain.game.Addr.x(n) } }

        // best streak inside the month, with the same rule as the real streak (one free rest day a week, no shields)
        var best = 0
        var streak = 0
        var last = 0L
        var restWeek = 0L
        for (d in sessions.map { it.dateEpochDay }.distinct().sorted()) {
            val o = StreakRules.onActivity(last, d, streak, 0, restWeek)
            streak = o.streak; restWeek = o.restWeekUsed; last = d
            if (streak > best) best = streak
        }

        val meals = db.meals().allOnce().filter { it.dateEpochDay in from..to }
        val perDay = meals.groupBy { it.dateEpochDay }.mapValues { e -> e.value.sumOf { it.kcal } }.filter { it.value > 0 }
        val kcalAvg = if (perDay.isEmpty()) 0 else (perDay.values.sum() / perDay.size)
        val goal = AppGraph.prefs.current().kcalGoal

        return MonthSummary(m, kgChange, startKg, endKg, sessions.size, records, bosses, badges, best, kcalAvg, perDay.size, goal)
    }

    /** Months that have a photo, a weigh-in or measurements, oldest first. */
    /** The month's progress per exercise (see [com.tamir.hamasa.domain.coach.MonthProgress]). */
    suspend fun exerciseProgress(m: YearMonth): List<com.tamir.hamasa.domain.coach.MonthProgress.Row> {
        val db = AppGraph.db
        val from = firstDay(m)
        val to = lastDay(m)
        val entries = HashMap<String, ArrayList<com.tamir.hamasa.domain.coach.MonthProgress.Entry>>()
        for (s in db.sessions().doneSessionsOnce()) {
            if (s.dateEpochDay < from || s.dateEpochDay > to) continue
            val byExercise = db.sessions().setsForOnce(s.id).groupBy { it.exerciseId }
            for ((id, sets) in byExercise) {
                entries.getOrPut(id) { ArrayList() }.add(com.tamir.hamasa.domain.coach.MonthProgress.Entry(s.dateEpochDay, sets))
            }
        }
        if (entries.isEmpty()) return emptyList()
        val known = HashMap<String, Pair<String, Boolean>>()
        for (id in entries.keys) {
            val e = db.exercises().byId(id) ?: com.tamir.hamasa.domain.coach.ExerciseCatalog.byId(id)
            known[id] = Pair(e?.nameHe ?: id, e?.unit == "kg")
        }
        return com.tamir.hamasa.domain.coach.MonthProgress.rows(
            entries,
            nameOf = { id -> known[id]?.first ?: id },
            weightedOf = { id -> known[id]?.second ?: false }
        )
    }

    /** What he burned against what he ate over the finished days of the month (see [EnergyData.days]). */
    suspend fun energy(m: YearMonth): EnergyTotals =
        EnergyData.totalsOf(EnergyData.days(firstDay(m), lastDay(m)), com.tamir.hamasa.domain.Days.today())

    suspend fun monthsWithData(): List<YearMonth> {
        val db = AppGraph.db
        val days = db.progress().photosOnce().map { it.dateEpochDay } +
            db.weights().allOnce().map { it.dateEpochDay } +
            db.progress().measurementsOnce().map { it.dateEpochDay }
        return days.map { monthOf(it) }.distinct().sorted()
    }

    suspend fun snapshot(m: YearMonth): MonthSnapshot {
        val db = AppGraph.db
        val from = firstDay(m)
        val to = lastDay(m)
        val photo = db.progress().photosOnce().filter { it.dateEpochDay in from..to }.maxWithOrNull(compareBy<ProgressPhotoEntity>({ it.dateEpochDay }, { it.id }))
        val kg = db.weights().latestOnOrBefore(to)?.kg
        val meas = db.progress().measurementsOnce().filter { it.dateEpochDay in from..to }.maxWithOrNull(compareBy<MeasurementEntity>({ it.dateEpochDay }, { it.id }))
        return MonthSnapshot(m, photo, kg, meas)
    }

    /** True when there is a progress photo dated in the current month. */
    suspend fun hasPhotoThisMonth(): Boolean {
        val today = Days.today()
        val m = monthOf(today)
        return AppGraph.db.progress().photosBetween(firstDay(m), today) > 0
    }

    /** Previous month had anything worth summarizing (a session, a weigh-in or a logged meal). */
    suspend fun hasAnyData(m: YearMonth): Boolean {
        val db = AppGraph.db
        val from = firstDay(m)
        val to = lastDay(m)
        if (db.sessions().doneBetween(from, to) > 0) return true
        if (db.weights().allOnce().any { it.dateEpochDay in from..to }) return true
        return db.meals().allOnce().any { it.dateEpochDay in from..to }
    }
}
