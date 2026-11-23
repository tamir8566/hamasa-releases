package com.tamir.hamasa.ui.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.MuscleGroup
import com.tamir.hamasa.domain.coach.MuscleRegion
import com.tamir.hamasa.domain.game.MuscleHeat
import com.tamir.hamasa.ui.common.MuscleHeatLegend
import com.tamir.hamasa.ui.common.MuscleHeatmap
import com.tamir.hamasa.ui.common.RadarChart
import com.tamir.hamasa.ui.common.RadarColors
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Current period and the one right before it, same length. */
data class AnalysisState(
    val days: Int,
    val loading: Boolean,
    val current: MuscleHeat.HeatResult,
    val previous: MuscleHeat.HeatResult
)

class AnalysisViewModel : ViewModel() {
    private val _state = MutableStateFlow(AnalysisState(7, true, MuscleHeat.HeatResult.EMPTY, MuscleHeat.HeatResult.EMPTY))
    val state: StateFlow<AnalysisState> = _state

    init { load(7) }

    fun setDays(days: Int) {
        if (days == _state.value.days && !_state.value.loading) return
        load(days)
    }

    private fun load(days: Int) {
        _state.value = _state.value.copy(days = days, loading = true)
        viewModelScope.launch {
            val today = Days.today()
            val fromCur = today - days + 1
            val current = AppGraph.workouts.heatForPeriod(fromCur, today)
            val previous = AppGraph.workouts.heatForPeriod(fromCur - days, fromCur - 1)
            // ignore a late answer for a period the user already left
            if (_state.value.days == days) _state.value = AnalysisState(days, false, current, previous)
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun AnalysisScreen() {
    val vm: AnalysisViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PeriodSelector(days = state.days, onPick = { vm.setDays(it) })

        // Heatmap of the period
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("מפת שרירים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                Text(periodTitle(state.days), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                MuscleHeatmap(heat = state.current.heat, modifier = Modifier.fillMaxWidth())
                MuscleHeatLegend()
                if (!state.loading && state.current.isEmpty) {
                    Text(
                        "אין סטים בתקופה הזו. המפה ריקה. לא אני.",
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Radar of the six regions
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.GoldDeep)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("איזון בין אזורים", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                RadarChart(
                    current = state.current.regionScores,
                    previous = state.previous.regionScores,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LegendSwatch(color = RadarColors.current, filled = true)
                    Spacer(Modifier.width(6.dp))
                    Text("התקופה הנוכחית", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(18.dp))
                    LegendSwatch(color = RadarColors.previous, filled = false)
                    Spacer(Modifier.width(6.dp))
                    Text("התקופה הקודמת", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Sets per region, current vs previous
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("סטים לפי אזור", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth()) {
                    Text("אזור", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("עכשיו", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    Text("קודם", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                }
                for (region in MuscleRegion.entries) {
                    val now = state.current.setsByRegion[region] ?: 0
                    val before = state.previous.setsByRegion[region] ?: 0
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(region.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(now.toString(), color = if (now > 0) HColors.Gold else HColors.ParchmentDim, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                        Text(before.toString(), color = HColors.ParchmentDim, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    }
                }
                val weakest = weakestRegion(state.current)
                if (weakest != null) {
                    Text(
                        "הכי פחות עבודה: ${weakest.nameHe}. " + com.tamir.hamasa.domain.game.CharacterNames.pick("רק מציין.", "רק מציינת."),
                        color = HColors.Ember,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Top muscles of the period
        val top = topMuscles(state.current)
        if (top.isNotEmpty()) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("השרירים שעבדו הכי הרבה", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                    for ((group, value) in top) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            HeatBar(value)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun periodTitle(days: Int): String = when (days) {
    7 -> "7 הימים האחרונים"
    30 -> "30 הימים האחרונים"
    else -> "$days הימים האחרונים"
}

/** The region with the least work, only when at least one region was worked at all. */
private fun weakestRegion(result: MuscleHeat.HeatResult): MuscleRegion? {
    if (result.isEmpty) return null
    return MuscleRegion.entries.minByOrNull { result.regionScores[it] ?: 0f }
}

/** Up to five groups by heat, descending, only the worked ones. */
private fun topMuscles(result: MuscleHeat.HeatResult): List<Pair<MuscleGroup, Float>> =
    result.heat.entries
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(5)
        .map { it.key to it.value }

@Composable
private fun PeriodSelector(days: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PeriodButton(label = "7 ימים", selected = days == 7, modifier = Modifier.weight(1f)) { onPick(7) }
        PeriodButton(label = "30 ימים", selected = days == 30, modifier = Modifier.weight(1f)) { onPick(30) }
    }
}

@Composable
private fun PeriodButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = CardShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) HColors.Gold else HColors.SlateLight,
            contentColor = if (selected) HColors.Ink else HColors.Parchment
        ),
        border = BorderStroke(2.dp, if (selected) HColors.GoldDeep else HColors.GoldDeep.copy(alpha = 0.4f))
    ) { Text(label, fontWeight = FontWeight.Black, fontSize = 17.sp) }
}

@Composable
private fun LegendSwatch(color: Color, filled: Boolean) {
    Box(
        Modifier
            .size(14.dp)
            .background(if (filled) color.copy(alpha = 0.5f) else HColors.Ink, RoundedCornerShape(3.dp))
            .border(BorderStroke(2.dp, color), RoundedCornerShape(3.dp))
    )
}

/** A short gold bar proportional to [value] (0..1). */
@Composable
private fun HeatBar(value: Float) {
    val v = value.coerceIn(0f, 1f)
    Box(Modifier.width(120.dp).height(10.dp).background(HColors.Slate, RoundedCornerShape(5.dp))) {
        Box(
            Modifier
                .fillMaxWidth(v.coerceAtLeast(0.05f))
                .height(10.dp)
                .background(if (v < 0.5f) HColors.Gold else HColors.Ember, RoundedCornerShape(5.dp))
        )
    }
}
