package com.tamir.hamasa.ui.run

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.tamir.hamasa.data.repo.RunCoach
import com.tamir.hamasa.data.repo.RunEntry
import com.tamir.hamasa.data.repo.RunStatus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.RunPlan
import com.tamir.hamasa.domain.energy.HrZones
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.workout.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunsUiState(
    val status: RunStatus? = null,
    val runs: List<RunEntry> = emptyList(),
    val loading: Boolean = true,
    val starting: Boolean = false
) {
    /** Longest run by time. */
    val longest: RunEntry? get() = runs.maxByOrNull { it.durationSec }
    /** Farthest run (only runs with a distance from the watch). */
    val farthest: RunEntry? get() = runs.filter { it.distanceM > 0 }.maxByOrNull { it.distanceM }
    /** Fastest average pace over at least 1 km. */
    val fastest: RunEntry? get() = runs.filter { it.distanceM >= 1000 && it.paceSecPerKm != null }.minByOrNull { it.paceSecPerKm ?: Int.MAX_VALUE }

    fun monthMeters(today: Long): Int {
        val d = Days.of(today)
        return runs.filter { val r = Days.of(it.day); r.year == d.year && r.monthValue == d.monthValue }.sumOf { it.distanceM }
    }

    fun monthCount(today: Long): Int {
        val d = Days.of(today)
        return runs.count { val r = Days.of(it.day); r.year == d.year && r.monthValue == d.monthValue }
    }
}

class RunsViewModel : ViewModel() {
    private val _state = MutableStateFlow(RunsUiState())
    val state: StateFlow<RunsUiState> = _state

    init {
        viewModelScope.launch {
            AppGraph.prefs.profile.collect { p ->
                _state.update { it.copy(status = RunCoach.status(p, Days.today())) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { RunCoach.sync() }
            val runs = runCatching { RunCoach.history() }.getOrDefault(emptyList())
            _state.update { it.copy(runs = runs, loading = false) }
        }
        // a run finished elsewhere (or imported from the watch) refreshes the list
        viewModelScope.launch {
            var first = true
            AppGraph.workouts.doneSessions.collect {
                if (!first) {
                    val runs = withContext(Dispatchers.IO) { runCatching { RunCoach.history() }.getOrDefault(emptyList()) }
                    _state.update { s -> s.copy(runs = runs, loading = false) }
                }
                first = false
            }
        }
    }

    fun start(onStarted: (Long) -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching { RunCoach.startNext() }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(starting = false) }
                if (id != null) onStarted(id)
            }
        }
    }
}

/** "ריצות": the 9-week plan and every run (app and watch). The route is added by the navigation (AppRoot). */
@Composable
fun RunsScreen() {
    RunsScreenContent(onStartRun = null)
}

/**
 * The runs page. [onStartRun] opens the live workout of a started run; null (as in [RunsScreen], which has no
 * navigation) hides the start button and points to the coach screen instead.
 */
@Composable
fun RunsScreenContent(onStartRun: ((Long) -> Unit)?) {
    val vm: RunsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val today = Days.today()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val status = state.status
        if (status != null) {
            item { PlanCard(status = status, starting = state.starting, onStart = if (onStartRun != null) ({ vm.start(onStartRun) }) else null) }
        }
        item { StatsCard(state, today) }
        item {
            Text("כל הריצות", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
        }
        if (!state.loading && state.runs.isEmpty()) {
            item {
                Text(
                    "עוד אין ריצות. הראשונה היא שמונה דקות של ריצה בסך הכול, עם הרבה הליכה ביניהן.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        items(state.runs, key = { it.sessionId }) { r -> RunRow(r) }
    }
}

@Composable
private fun PlanCard(status: RunStatus, starting: Boolean, onStart: (() -> Unit)?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, HColors.Ember)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("מאפס לחמישה קילומטרים", style = MaterialTheme.typography.titleLarge, color = HColors.Gold, modifier = Modifier.weight(1f))
                Text("${status.doneSessions}/${RunPlan.TOTAL_SESSIONS}", color = HColors.Parchment, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            // nine weeks: done, current, ahead
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (w in 1..RunPlan.WEEKS) {
                    val doneWeek = status.doneSessions >= w * RunPlan.DAYS_PER_WEEK
                    val current = !status.finished && w == status.week
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .background(if (doneWeek) HColors.Ember else HColors.SlateLight, RoundedCornerShape(8.dp))
                            .border(if (current) 2.dp else 0.dp, if (current) HColors.Gold else HColors.SlateLight, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$w", color = if (doneWeek) HColors.Ink else HColors.Parchment, fontWeight = FontWeight.Bold)
                    }
                }
            }
            LinearProgressIndicator(
                progress = { status.doneSessions.toFloat() / RunPlan.TOTAL_SESSIONS.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = HColors.Gold,
                trackColor = HColors.SlateLight
            )
            if (status.finished) {
                Text("התוכנית הושלמה. שלושים דקות ריצה רצופה. הידדדדד.", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
            } else {
                Text("האימון הבא: ${status.labelHe}", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    RunPlan.summaryHe(status.week, status.day) + ". " + RunPlan.totalSec(status.week, status.day) / 60 + " דקות עם חימום ושחרור.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            }
            val result = RunCoach.resultLineHe(status.lastResult)
            if (result.isNotEmpty()) Text(result, color = HColors.Sky, style = MaterialTheme.typography.bodyMedium)
            Text(
                "שלוש ריצות בשבוע, אף פעם לא שני ימים ברצף. ריצה שהרגישה קשה או עם דופק גבוה מדי: חוזרים על השבוע.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
            )
            if (status.restToday) {
                Text("היום מנוחה מריצה. מחר כן.", color = HColors.Gold, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            if (onStart != null) {
                Button(
                    onClick = onStart,
                    enabled = !starting && !status.restToday,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Ember, contentColor = HColors.Ink)
                ) { Text(com.tamir.hamasa.domain.game.Addr.t("התחל ריצה", "התחילי ריצה"), fontWeight = FontWeight.Black, fontSize = 18.sp) }
            } else {
                Text("מתחילים מכרטיס \"ריצה\" במסך הג'ים ברו.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun kmText(m: Int): String {
    val tenths = (m + 50) / 100
    return "${tenths / 10}.${tenths % 10} ק\"מ"
}

private fun paceText(secPerKm: Int): String = "${secPerKm / 60}:${(secPerKm % 60).toString().padStart(2, '0')} לק\"מ"

@Composable
private fun StatsCard(state: RunsUiState, today: Long) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatBox("החודש", kmText(state.monthMeters(today)), Modifier.weight(1f))
                StatBox("ריצות החודש", "${state.monthCount(today)}", Modifier.weight(1f))
                StatBox("סך הכול", "${state.runs.size}", Modifier.weight(1f))
            }
            val longest = state.longest
            if (longest != null) StatLine("הארוכה ביותר", "${formatClock(longest.durationSec)} · ${dateText(longest.day)}")
            val far = state.farthest
            if (far != null) StatLine("הרחוקה ביותר", "${kmText(far.distanceM)} · ${dateText(far.day)}")
            val fast = state.fastest
            val pace = fast?.paceSecPerKm
            if (fast != null && pace != null) StatLine("הקצב הטוב ביותר", "${paceText(pace)} · ${dateText(fast.day)}")
            if (state.runs.isNotEmpty() && state.runs.none { it.distanceM > 0 }) {
                Text("מרחק וקצב מגיעים מהשעון, כשהוא מחובר.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(HColors.Slate, RoundedCornerShape(14.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun dateText(day: Long): String {
    val d = Days.of(day)
    return "${Days.hebrewDayName(day)} ${d.dayOfMonth}.${d.monthValue}"
}

@Composable
private fun RunRow(r: RunEntry) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(1.dp, HColors.GoldDeep.copy(alpha = 0.4f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateText(r.day), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(formatClock(r.durationSec), color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            val kind = when {
                r.planLabelHe.isNotEmpty() -> "בתוכנית: ${r.planLabelHe}"
                r.fromWatch -> "ריצה חופשית מהשעון"
                else -> "ריצה חופשית"
            }
            Text(kind, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            val parts = ArrayList<String>()
            if (r.distanceM > 0) parts.add(kmText(r.distanceM))
            val pace = r.paceSecPerKm
            if (pace != null) parts.add(paceText(pace))
            if (r.avgHr > 0) parts.add("דופק ממוצע ${r.avgHr}")
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            // minutes per heart rate zone from the watch: "אזורים: 1·3 2·12 3·20 4·6 5·0"
            if (HrZones.has(r.zonesMin)) {
                Text(HrZones.summaryHe(r.zonesMin), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
