package com.tamir.hamasa.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.common.WorkoutCalendar
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.max

/** Totals of one period (this week / this month / all time). */
data class PeriodStats(
    val sessions: Int = 0,
    val minutes: Int = 0,
    val sets: Int = 0,
    val volumeKg: Double = 0.0,
    val records: Int = 0,
    /** Net workout burn (EnergyData.burnFor, the same number the session screens show). */
    val kcal: Int = 0
)

data class HistoryHubStats(
    val week: PeriodStats,
    val month: PeriodStats,
    val all: PeriodStats,
    /** Last 12 weeks, oldest first: (Sunday epochDay, finished sessions). */
    val weekly: List<Pair<Long, Int>>,
    val runs: Int,
    /** Kilometres of the runs that came with a watch distance. */
    val runKm: Float
)

/** The history tab's summary: computed from the finished sessions and their sets, nothing is stored. */
class HistoryHubViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val stats: StateFlow<HistoryHubStats?> = AppGraph.workouts.doneSessions
        .map { list -> runCatching { compute(list) }.getOrNull() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private class Acc {
        var sessions = 0
        var minutes = 0f
        var sets = 0
        var volume = 0.0
        var records = 0
        var kcal = 0
        fun add(minutes: Float, sets: Int, volume: Double, records: Int, kcal: Int) {
            this.sessions += 1
            this.minutes += minutes
            this.sets += sets
            this.volume += volume
            this.records += records
            this.kcal += kcal
        }
        fun out() = PeriodStats(sessions, minutes.toInt(), sets, volume, records, kcal)
    }

    private suspend fun compute(sessions: List<SessionEntity>): HistoryHubStats {
        val db = AppGraph.db
        val p = AppGraph.prefs.current()
        val today = Days.today()
        val weekStart = Days.weekStartOf(today)
        val monthStart = Days.of(today).withDayOfMonth(1).toEpochDay()
        val ids = sessions.map { it.id }.toHashSet()
        val setsBySession = db.sessions().allSetsOnce().filter { it.sessionId in ids }.groupBy { it.sessionId }
        val weights = db.weights().allOnce()
        val latestKg = weights.lastOrNull()?.kg ?: p.startWeightKg
        val watch = runCatching { WatchStore.current().sessions }.getOrDefault(emptyMap())

        val week = Acc()
        val month = Acc()
        val all = Acc()
        var runs = 0
        var runM = 0
        for (s in sessions) {
            val sets = setsBySession[s.id].orEmpty().filter { it.completed }
            val volume = sets.sumOf { it.weight.toDouble() * it.reps.toDouble() }
            val records = sets.count { it.isPr }
            val minutes = EnergyData.minutesOf(s)
            // weight of that day (the latest weigh-in on or before it), like EnergyData.sessionBurn
            val kg = weights.lastOrNull { it.dateEpochDay <= s.dateEpochDay }?.kg ?: latestKg
            val hr = watch[s.id]?.takeIf { it.hasHr }?.avgHr
            val kcal = runCatching { EnergyData.burnFor(s, sets.size, kg, p, avgHr = hr, distanceM = EnergyData.distanceOf(s.id))?.kcal ?: 0 }.getOrDefault(0)
            all.add(minutes, sets.size, volume, records, kcal)
            if (s.dateEpochDay >= monthStart) month.add(minutes, sets.size, volume, records, kcal)
            if (s.dateEpochDay >= weekStart) week.add(minutes, sets.size, volume, records, kcal)
            if (s.type == SessionType.RUN) {
                runs += 1
                runM += (watch[s.id]?.distanceM ?: 0).coerceAtLeast(0)
            }
        }
        val counts = sessions.groupingBy { Days.weekStartOf(it.dateEpochDay) }.eachCount()
        val weekly = (11 downTo 0).map { back ->
            val ws = weekStart - 7L * back
            Pair(ws, counts[ws] ?: 0)
        }
        return HistoryHubStats(week.out(), month.out(), all.out(), weekly, runs, runM / 1000f)
    }
}

private val HubCard = RoundedCornerShape(20.dp)

private fun n(v: Int): String = String.format(Locale.US, "%,d", v)

private fun volumeText(kg: Double): String =
    if (kg >= 10_000.0) String.format(Locale.US, "%.1f טון", kg / 1000.0) else "${n(kg.toInt())} ק\"ג"

private fun minutesText(m: Int): String = if (m < 120) "${n(m)} דק'" else "${n(m / 60)} ש' ${m % 60} דק'"

/** Filter chips of the session list. */
private val FILTERS = listOf("הכול", "מכון", "ריצות", "הליכות", "יוגה", "מהשעון")

private fun matches(filter: Int, s: SessionEntity): Boolean = when (filter) {
    1 -> s.type == SessionType.GYM || s.type == SessionType.MINI
    2 -> s.type == SessionType.RUN
    3 -> s.type == SessionType.WALK
    4 -> s.type == SessionType.YOGA
    5 -> WatchSync.isWatchSession(s)
    else -> true
}

/**
 * The history tab: summary (week / month / all time), 12 weeks of sessions, the runs, links to the summaries,
 * the calendar and the filtered list of sessions (tap = session detail).
 */
@Composable
fun HistoryHubScreen(
    onOpenSession: (Long) -> Unit = {},
    onOpenRuns: () -> Unit = {},
    onOpenWeekly: () -> Unit = {},
    onOpenMonthly: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenRecords: () -> Unit = {}
) {
    val hub: HistoryHubViewModel = viewModel()
    val list: HistoryViewModel = viewModel()
    val stats by hub.stats.collectAsStateWithLifecycle()
    val profile by hub.profile.collectAsStateWithLifecycle()
    val sessions by list.sessions.collectAsStateWithLifecycle()
    val expandedId by list.expandedId.collectAsStateWithLifecycle()
    val setsBySession by list.setsBySession.collectAsStateWithLifecycle()
    val exercises by list.exercises.collectAsStateWithLifecycle()
    val heatBySession by list.heatBySession.collectAsStateWithLifecycle()
    val battleLog by list.battleLog.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = sessions.filter { matches(filter, it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SummaryCard(stats, period, streak = profile?.streak ?: 0, bestStreak = profile?.bestStreak ?: 0, onPeriod = { period = it })
        }
        item { WeeksChart(stats?.weekly.orEmpty()) }
        item { RunsCard(stats, onOpenRuns) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton("הסיכום השבועי", HColors.Ember, onOpenWeekly, Modifier.weight(1f))
                LinkButton("סיכום החודש", HColors.Gold, onOpenMonthly, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton("ניתוח שרירים", HColors.GoldDeep, onOpenAnalysis, Modifier.weight(1f))
                LinkButton("ספר השיאים", HColors.GoldDeep, onOpenRecords, Modifier.weight(1f))
            }
        }
        item { WorkoutCalendar(sessions = sessions, onOpenSession = onOpenSession) }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FILTERS.forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Card(shape = HubCard, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
                    Text(
                        if (sessions.isEmpty()) "עדיין אין אימונים. הראשון תמיד הכי קשה. השני גם."
                        else "אין כאן אימונים מהסוג הזה. עדיין.",
                        Modifier.padding(16.dp),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        items(shown, key = { it.id }) { s ->
            SessionRow(
                session = s,
                expanded = expandedId == s.id,
                sets = setsBySession[s.id],
                exercises = exercises,
                heat = heatBySession[s.id],
                logLine = battleLog[s.id],
                onClick = { onOpenSession(s.id) },
                onTogglePeek = { list.toggle(s.id) }
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LinkButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = HubCard,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = HColors.Ink)
    ) { Text(text, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1) }
}

@Composable
private fun SummaryCard(stats: HistoryHubStats?, period: Int, streak: Int, bestStreak: Int, onPeriod: (Int) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = HubCard,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("היסטוריה", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = period == 0, onClick = { onPeriod(0) }, label = { Text("השבוע") })
                FilterChip(selected = period == 1, onClick = { onPeriod(1) }, label = { Text("החודש") })
                FilterChip(selected = period == 2, onClick = { onPeriod(2) }, label = { Text("מאז ומעולם") })
            }
            val ps = when (period) {
                0 -> stats?.week
                1 -> stats?.month
                else -> stats?.all
            } ?: PeriodStats()
            Row(Modifier.fillMaxWidth()) {
                StatTile(n(ps.sessions), "אימונים", Modifier.weight(1f))
                StatTile(minutesText(ps.minutes), "זמן", Modifier.weight(1f))
                StatTile(n(ps.sets), "סטים", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(volumeText(ps.volumeKg), "נפח", Modifier.weight(1f))
                StatTile(n(ps.records), "שיאים", Modifier.weight(1f))
                StatTile(n(ps.kcal), "קלוריות", Modifier.weight(1f))
            }
            Text(
                "רצף: ${if (streak == 1) "יום אחד" else "$streak ימים"} · הכי ארוך: ${if (bestStreak == 1) "יום אחד" else "$bestStreak ימים"}",
                color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold
            )
            if (stats == null) {
                Text(com.tamir.hamasa.domain.game.CharacterNames.g("סופר. רגע."), color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
            } else {
                Text("קלוריות: שריפה נטו של האימונים, לפי המשקל של אותו יום.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = HColors.Parchment, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, textAlign = TextAlign.Center)
        Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
    }
}

/** 12 weeks of sessions as simple bars (RTL: the oldest week on the right, this week on the left). */
@Composable
private fun WeeksChart(weekly: List<Pair<Long, Int>>) {
    val top = max(1, weekly.maxOfOrNull { it.second } ?: 1)
    val area = 96f
    Card(
        Modifier.fillMaxWidth(),
        shape = HubCard,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("שתים עשרה שבועות: אימונים בשבוע", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            if (weekly.isEmpty()) {
                Text(com.tamir.hamasa.domain.game.CharacterNames.g("סופר. רגע."), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                weekly.forEachIndexed { i, (ws, count) ->
                    val current = i == weekly.size - 1
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (count > 0) count.toString() else "",
                            color = if (current) HColors.Gold else HColors.ParchmentDim, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        val h = if (count > 0) max(4f, area * count / top) else 0f
                        Box(Modifier.height(area.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height(h.dp)
                                    .background(if (current) HColors.Gold else HColors.Sky, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val d = Days.of(ws)
                        Text(
                            when {
                                current -> "השבוע"
                                i % 2 == 1 -> "${d.dayOfMonth}.${d.monthValue}"
                                else -> ""
                            },
                            color = if (current) HColors.Gold else HColors.ParchmentDim, fontSize = 10.sp, maxLines = 1
                        )
                    }
                }
            }
            val active = weekly.count { it.second > 0 }
            val avg = weekly.sumOf { it.second } / weekly.size.toFloat()
            Text(
                "שבועות עם אימון: $active מתוך ${weekly.size}. ממוצע ${String.format(Locale.US, "%.1f", avg)} בשבוע.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RunsCard(stats: HistoryHubStats?, onOpenRuns: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpenRuns),
        shape = HubCard,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, HColors.Ember)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ריצות", color = HColors.Ember, fontSize = 24.sp, fontWeight = FontWeight.Black)
                val runs = stats?.runs ?: 0
                val km = stats?.runKm ?: 0f
                Text(
                    when {
                        runs == 0 -> "עוד אין ריצות. מאמן הריצה מחכה עם תוכנית."
                        km > 0f -> "${if (runs == 1) "ריצה אחת" else "$runs ריצות"} · ${String.format(Locale.US, "%.1f", km)} ק\"מ"
                        else -> if (runs == 1) "ריצה אחת" else "$runs ריצות"
                    },
                    color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold
                )
                Text("התוכנית, האימון הבא והתוצאות.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            }
            Text(com.tamir.hamasa.domain.game.Addr.t("פתח", "פתחי"), color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}
