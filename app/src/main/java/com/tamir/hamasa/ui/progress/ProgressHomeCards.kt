package com.tamir.hamasa.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tamir.hamasa.data.prefs.ProgressMarks
import com.tamir.hamasa.data.repo.MonthlyStats
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.progress.WeightForecast
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ProgressHomeViewModel : ViewModel() {
    /** Compact forecast line for Home; null = nothing to show. */
    val forecastLine: StateFlow<String?> =
        combine(AppGraph.workouts.allWeights, AppGraph.prefs.profile) { weights, p ->
            val latestKg = weights.lastOrNull()?.kg ?: p.startWeightKg
            val gain = com.tamir.hamasa.data.repo.EnergyData.directionOf(p, latestKg) == com.tamir.hamasa.domain.energy.GoalDirection.GAIN
            val r = WeightForecast.compute(weights.map { it.dateEpochDay to it.kg }, p.goalWeightKg, Days.today(), gain)
            WeightForecast.homeLine(r, p.goalWeightKg)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _photoCard = MutableStateFlow(false)
    val photoCard: StateFlow<Boolean> = _photoCard

    /** Name of the previous month when its summary card should show, else null. */
    private val _summaryCard = MutableStateFlow<String?>(null)
    val summaryCard: StateFlow<String?> = _summaryCard

    /** Re-checked whenever Home comes back (a photo may have been taken meanwhile). */
    fun refresh() {
        viewModelScope.launch {
            val date = LocalDate.now()
            val thisMonth = ProgressMarks.monthKey(date.year, date.monthValue)
            // "תמונת התקדמות חודשית": first 10 days of the month, until a photo is taken or the card is dismissed
            _photoCard.value = runCatching {
                AppGraph.prefs.current().onboarded && date.dayOfMonth <= 10 &&
                    ProgressMarks.photoCardDismissedMonth != thisMonth && !MonthlyStats.hasPhotoThisMonth()
            }.getOrDefault(false)
            // "סיכום חודש": first 7 days of the month, until opened or dismissed, only when last month had data
            val prev = previousMonth()
            val prevKey = ProgressMarks.monthKey(prev.year, prev.monthValue)
            _summaryCard.value = runCatching {
                if (date.dayOfMonth <= 7 && ProgressMarks.summarySeenMonth != prevKey && MonthlyStats.hasAnyData(prev))
                    WeightForecast.monthHe(prev.monthValue) else null
            }.getOrNull()
        }
    }

    fun dismissPhoto() {
        val d = LocalDate.now()
        ProgressMarks.photoCardDismissedMonth = ProgressMarks.monthKey(d.year, d.monthValue)
        _photoCard.value = false
    }

    fun dismissSummary() {
        val prev = previousMonth()
        ProgressMarks.summarySeenMonth = ProgressMarks.monthKey(prev.year, prev.monthValue)
        _summaryCard.value = null
    }
}

/** Home: the forecast line and the two monthly cards. Nothing at all when none applies. */
@Composable
fun ProgressHomeCards(onOpenWeight: () -> Unit, modifier: Modifier = Modifier) {
    val vm: ProgressHomeViewModel = viewModel()
    val forecast by vm.forecastLine.collectAsStateWithLifecycle()
    val photoCard by vm.photoCard.collectAsStateWithLifecycle()
    val summaryMonth by vm.summaryCard.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    if (forecast == null && !photoCard && summaryMonth == null) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        forecast?.let { line ->
            Text(
                line,
                color = HColors.ParchmentDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(onClick = onOpenWeight).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        summaryMonth?.let { name ->
            HomeCard(
                title = "סיכום חודש $name",
                body = "החודש במספרים. ושורה אחת ממני בסוף.",
                onOpen = { ProgressNav.open(ProgressNav.monthly(-1)) },
                onDismiss = { vm.dismissSummary() }
            )
        }
        if (photoCard) {
            HomeCard(
                title = "תמונת התקדמות חודשית",
                body = "אותה תנוחה, אותו מקום. ומידות, אם יש סרט מדידה. נשאר רק בטלפון.",
                onOpen = { ProgressNav.open(ProgressNav.PHOTOS) },
                onDismiss = { vm.dismissPhoto() }
            )
        }
    }
}

@Composable
private fun HomeCard(title: String, body: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Gold)
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(title, color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(body, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("לא עכשיו", color = HColors.ParchmentDim) }
                TextButton(onClick = onOpen) { Text(com.tamir.hamasa.domain.game.Addr.t("פתח", "פתחי"), color = HColors.Gold, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
