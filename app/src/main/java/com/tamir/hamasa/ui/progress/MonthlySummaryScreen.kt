package com.tamir.hamasa.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.prefs.ProgressMarks
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.data.repo.MonthSummary
import com.tamir.hamasa.data.repo.MonthlyStats
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.domain.progress.WeightForecast
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.Locale

data class MonthlySummaryState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val summary: MonthSummary? = null,
    val closingHe: String = "",
    /** "הכיוון" in force (cut / maintain / gain): decides which weight change is the good one. */
    val direction: GoalDirection = GoalDirection.CUT,
    /** What he burned against what he ate over the finished days of the month. */
    val energy: com.tamir.hamasa.data.repo.EnergyTotals = com.tamir.hamasa.data.repo.EnergyTotals(0, 0, 0, 0),
    /** The month's progress per exercise that repeated. */
    val progress: List<com.tamir.hamasa.domain.coach.MonthProgress.Row> = emptyList()
)

class MonthlySummaryViewModel : ViewModel() {
    private val _state = MutableStateFlow(MonthlySummaryState())
    val state: StateFlow<MonthlySummaryState> = _state
    private var started = false

    /** [ym] = year*12 + (month-1); negative = the previous month. Runs once per screen. */
    fun start(ym: Int) {
        if (started) return
        started = true
        val m = if (ym < 0) YearMonth.now().minusMonths(1) else YearMonth.of(ym / 12, ym % 12 + 1)
        load(m)
    }

    fun shift(by: Long) {
        val next = _state.value.month.plusMonths(by)
        if (next.isAfter(YearMonth.now())) return
        load(next)
    }

    private fun load(m: YearMonth) {
        _state.value = _state.value.copy(loading = true, month = m)
        viewModelScope.launch {
            val s = runCatching { MonthlyStats.summary(m) }.getOrNull()
            val direction = runCatching {
                val p = AppGraph.prefs.current()
                EnergyData.directionOf(p, s?.endKg ?: p.startWeightKg)
            }.getOrDefault(GoalDirection.CUT)
            // seeing the previous month's summary retires the Home card for it
            if (m == YearMonth.now().minusMonths(1)) ProgressMarks.summarySeenMonth = ProgressMarks.monthKey(m.year, m.monthValue)
            _state.value = MonthlySummaryState(
                loading = false,
                month = m,
                summary = s,
                closingHe = if (s == null) "" else Voice.monthClosing(s.sessions, s.kgChange, s.records, gainGoal = direction == GoalDirection.GAIN),
                direction = direction,
                energy = runCatching { MonthlyStats.energy(m) }.getOrDefault(com.tamir.hamasa.data.repo.EnergyTotals(0, 0, 0, 0)),
                progress = runCatching { MonthlyStats.exerciseProgress(m) }.getOrDefault(emptyList())
            )
        }
    }
}

private val MonthCardShape = RoundedCornerShape(20.dp)

private fun n(v: Int): String = String.format(Locale.US, "%,d", v)

/** "סיכום חודש <שם>": the month in numbers, bosses and badges, and a closing line from Shambiuluk. */
@Composable
fun MonthlySummaryScreen(initialYm: Int) {
    val vm: MonthlySummaryViewModel = viewModel()
    LaunchedEffect(initialYm) { vm.start(initialYm) }
    val st by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("סיכום חודש ${WeightForecast.monthHe(st.month.monthValue)}", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.shift(-1) }, enabled = !st.loading) { Text("החודש הקודם", color = HColors.Gold) }
            Text(st.month.year.toString(), color = HColors.ParchmentDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            val canNext = !st.month.plusMonths(1).isAfter(YearMonth.now())
            TextButton(onClick = { vm.shift(1) }, enabled = !st.loading && canNext) { Text("החודש הבא", color = if (canNext) HColors.Gold else HColors.ParchmentDim) }
        }

        val s = st.summary
        if (st.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = HColors.Gold) }
            return@Column
        }
        if (s == null || s.isEmpty) {
            Card(shape = MonthCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
                Text("חודש בלי רישומים. הדף נשאר נקי. יש עוד חודשים.", Modifier.padding(16.dp), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
            }
            return@Column
        }

        // the story: one loud slide per number, tap to advance; the full details follow below
        com.tamir.hamasa.ui.common.WrappedStory(
            slides = MonthlyWrapped.slides(s, WeightForecast.monthHe(st.month.monthValue), st.direction, st.closingHe),
            modifier = Modifier.fillMaxWidth().height(520.dp),
            testTag = "monthly_wrapped"
        )
        Text("הפרטים המלאים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
        Card(shape = MonthCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Text(st.closingHe, Modifier.padding(16.dp), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
        }

        val kg = s.kgChange
        val (kgValue, kgLabel) = when {
            kg == null -> "-" to "שינוי במשקל"
            kg <= -0.05f -> WeightForecast.fmt1(kg) to "ק\"ג ירדו"
            kg >= 0.05f -> WeightForecast.fmt1(kg) to "ק\"ג עלו"
            else -> "0" to "ק\"ג, יציב"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // gold for a change toward the goal: down when cutting, up when gaining, nothing when maintaining
            val kgGood = kg != null && when (st.direction) {
                GoalDirection.CUT -> kg <= -0.05f
                GoalDirection.GAIN -> kg >= 0.05f
                GoalDirection.MAINTAIN -> false
            }
            MonthTile(kgLabel, kgValue, if (kgGood) HColors.Gold else HColors.Parchment, Modifier.weight(1f))
            MonthTile("אימונים", s.sessions.toString(), HColors.Parchment, Modifier.weight(1f))
            MonthTile("שיאים", s.records.toString(), HColors.Gold, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthTile("בוסים", s.bossesDefeated.size.toString(), HColors.Danger, Modifier.weight(1f))
            MonthTile("תגים", s.badges.size.toString(), HColors.Sky, Modifier.weight(1f))
            MonthTile("הרצף הטוב", s.bestStreak.toString(), HColors.Ember, Modifier.weight(1f))
        }
        if (kg != null && s.startKg != null && s.endKg != null) {
            Text(
                "מ-${WeightForecast.fmt1(s.startKg)} ל-${WeightForecast.fmt1(s.endKg)} ק\"ג.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
        }

        Card(shape = MonthCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("קלוריות", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                if (s.kcalDays == 0) {
                    Text("לא נרשמו ארוחות החודש.", color = HColors.ParchmentDim)
                } else {
                    Text("ממוצע יומי ${n(s.kcalAvg)} מול יעד ${n(s.kcalGoal)}.", color = HColors.Parchment, fontWeight = FontWeight.Bold)
                    Text("לפי ${s.kcalDays} ימים עם רישום.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        MonthEnergyCard(st.energy)
        MonthProgressCard(st.progress)

        if (s.bossesDefeated.isNotEmpty()) ListCard("בוסים שהובסו", s.bossesDefeated)
        if (s.badges.isNotEmpty()) ListCard("תגים שנפתחו", s.badges)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MonthTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ListCard(title: String, items: List<String>) {
    Card(shape = MonthCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            items.forEach { Text("· " + com.tamir.hamasa.domain.game.Addr.x(it), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

/** Key of the previous calendar month, for the Home card. */
fun previousMonth(): YearMonth = MonthlyStats.monthOf(Days.today()).minusMonths(1)
