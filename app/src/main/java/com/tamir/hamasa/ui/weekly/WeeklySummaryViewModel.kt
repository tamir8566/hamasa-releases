package com.tamir.hamasa.ui.weekly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.repo.BossFights
import com.tamir.hamasa.data.repo.WeeklyBox
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.MuscleHeat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class WeeklySummaryState(
    val loading: Boolean = true,
    val weekStart: Long = 0L,
    val sessions: Int = 0,
    val gymSessions: Int = 0,
    val volumeKg: Double = 0.0,
    val prs: Int = 0,
    val xp: Int = 0,
    val gold: Int = 0,
    val streak: Int = 0,
    val bossNameHe: String = "",
    val bossHp: Int = 0,
    val bossMaxHp: Int = 1,
    val bossDefeated: Boolean = false,
    val bossLineHe: String = "",
    val heat: MuscleHeat.HeatResult = MuscleHeat.HeatResult.EMPTY,
    val previousHeat: MuscleHeat.HeatResult = MuscleHeat.HeatResult.EMPTY,
    val verdictHe: String = "",
    /** The week's calories against the daily goal (days up to today). */
    val calDays: List<com.tamir.hamasa.domain.food.CalDay> = emptyList(),
    val calGoal: Int = 0,
    /** What the body burned on each day of the week (BMR + movement + workouts + digestion), by epoch day. */
    val calBurn: Map<Long, Int> = emptyMap(),
    val calDirection: com.tamir.hamasa.domain.energy.GoalDirection = com.tamir.hamasa.domain.energy.GoalDirection.MAINTAIN,
    /** How many weeks back this is (0 = the current week). */
    val weeksBack: Int = 0,
    /** His averages per day over the last 30 finished days (0 days = nothing to show yet). */
    val avg: com.tamir.hamasa.data.repo.EnergyTotals = com.tamir.hamasa.data.repo.EnergyTotals(0, 0, 0, 0),
    /** Chests waiting sealed in the box. */
    val sealedCount: Int = 0,
    val canOpenBox: Boolean = false,
    /** Contents revealed by "פתח את הקופסה" (empty until opened). */
    val opened: List<ChestDrop> = emptyList(),
    val opening: Boolean = false
)

/** "סיכום שבועי" of a Sunday..Saturday week (the current one, or an earlier one), plus the end-of-week box. */
class WeeklySummaryViewModel : ViewModel() {
    private val _state = MutableStateFlow(WeeklySummaryState())
    val state: StateFlow<WeeklySummaryState> = _state

    init {
        viewModelScope.launch { load(0) }
    }

    /** Moves to an earlier (+1) or a later (-1) week; never past the current week. */
    fun shiftWeek(back: Int) {
        val cur = _state.value
        if (cur.loading) return
        val next = cur.weeksBack + back
        if (next < 0 || next > MAX_WEEKS_BACK) return
        _state.value = cur.copy(loading = true)
        viewModelScope.launch { load(next) }
    }

    private suspend fun load(weeksBack: Int) {
        val today = Days.today()
        val week = Days.weekStartOf(today) - 7L * weeksBack
        val end = week + 6
        val db = AppGraph.db
        val sessions = db.sessions().doneSessionsOnce().filter { it.dateEpochDay in week..end }
        var volume = 0.0
        var prs = 0
        for (s in sessions) {
            volume += db.sessions().sessionVolume(s.id)
            prs += db.sessions().setsForOnce(s.id).count { it.isPr }
        }
        val p = AppGraph.prefs.current()
        val (boss, progress) = BossFights.forDay(if (weeksBack == 0) today else end)
        val heat = AppGraph.workouts.heatForPeriod(week, end)
        val prevHeat = AppGraph.workouts.heatForPeriod(week - 7, week - 1)
        val sealed = WeeklyBox.decode(p.sealedChests)
        val eatenByDay = HashMap<Long, Int>()
        for (m in runCatching { db.meals().allOnce() }.getOrDefault(emptyList())) {
            if (m.dateEpochDay in week..end) eatenByDay[m.dateEpochDay] = (eatenByDay[m.dateEpochDay] ?: 0) + m.kcal.coerceAtLeast(0)
        }
        val kg = runCatching { db.weights().allOnce().lastOrNull()?.kg }.getOrNull() ?: p.startWeightKg
        val direction = com.tamir.hamasa.data.repo.EnergyData.directionOf(p, kg)
        // burn of each day of this week; a finished day with nothing logged counts as his usual day
        val energy = runCatching { com.tamir.hamasa.data.repo.EnergyData.days(week, end) }.getOrDefault(emptyList())
        val usualByDay = energy.filter { it.estimated }.associate { it.day to it.intake }
        val calDays = com.tamir.hamasa.domain.food.WeekCalories.week(week, today, eatenByDay, direction, p.kcalGoal, usualByDay)
        val avg = runCatching {
            com.tamir.hamasa.data.repo.EnergyData.totalsOf(
                com.tamir.hamasa.data.repo.EnergyData.days(today - com.tamir.hamasa.domain.food.WeekCalories.USUAL_WINDOW, today - 1), today
            )
        }.getOrDefault(com.tamir.hamasa.data.repo.EnergyTotals(0, 0, 0, 0))
        _state.value = WeeklySummaryState(
            calDays = calDays, calGoal = p.kcalGoal, calDirection = direction,
            calBurn = energy.associate { it.day to it.burn },
            weeksBack = weeksBack, avg = avg,
            loading = false,
            weekStart = week,
            sessions = sessions.size,
            gymSessions = sessions.count { it.type == com.tamir.hamasa.data.db.SessionType.GYM },
            volumeKg = volume,
            prs = prs,
            xp = sessions.sumOf { it.xpEarned },
            gold = sessions.sumOf { it.goldEarned },
            streak = p.streak,
            bossNameHe = boss.nameHe,
            bossHp = progress.hp,
            bossMaxHp = progress.maxHp.coerceAtLeast(1),
            bossDefeated = progress.defeated,
            bossLineHe = com.tamir.hamasa.domain.game.Addr.x(if (progress.defeated) boss.defeatedHe else boss.quoteHe),
            heat = heat,
            previousHeat = prevHeat,
            verdictHe = verdict(sessions.size),
            sealedCount = sealed.size,
            canOpenBox = WeeklyBox.canOpen(sealed, today)
        )
    }

    /** Opens the box: every sealed chest is applied at once and revealed together. */
    fun openBox() {
        val cur = _state.value
        if (cur.opening || !cur.canOpenBox) return
        _state.value = cur.copy(opening = true)
        viewModelScope.launch {
            val drops = runCatching { AppGraph.game.openSealedChests() }.getOrDefault(emptyList())
            CloudSync.pushSoon(AppGraph.appContext)
            // a failure midway leaves the unopened chests in the box (GameRepository applies first, then removes)
            val left = runCatching { AppGraph.game.sealedChests() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                opening = false, opened = drops, sealedCount = left.size,
                canOpenBox = WeeklyBox.canOpen(left, Days.today())
            )
        }
    }

    companion object {
        /** Two years of weeks is plenty. */
        const val MAX_WEEKS_BACK = 104
    }

    private fun verdict(n: Int): String = when {
        n <= 0 -> "שבוע בלי אימונים. המפלצת חגגה. בשבוע הבא היא מוזמנת רק לצפות."
        n == 1 -> "אימון אחד. התחלה. גם מגדל מתחיל מאבן אחת. קטנה."
        n == 2 -> "שני אימונים. סביר. סביר זו לא מילה גסה. עדיין."
        n == 3 -> "שלושה אימונים. שבוע של אדם רציני. הפתעת אותי לטובה."
        n == 4 -> "ארבעה אימונים. השבוע נכנע כמעט בלי קרב."
        else -> "$n אימונים בשבוע אחד. " + com.tamir.hamasa.domain.game.Addr.t("האיש", "האישה") + " והאגדה. " + com.tamir.hamasa.domain.game.CharacterNames.pick("אני רושם ומשתחווה", "אני רושמת ומשתחווה") + ". קצת."
    }
}
