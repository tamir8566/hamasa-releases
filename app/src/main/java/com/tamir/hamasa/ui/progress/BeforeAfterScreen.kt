package com.tamir.hamasa.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MeasurementEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.MonthSnapshot
import com.tamir.hamasa.data.repo.MonthlyStats
import com.tamir.hamasa.domain.game.BodyStage
import com.tamir.hamasa.domain.progress.WeightForecast
import com.tamir.hamasa.ui.common.CharacterFigure
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.YearMonth
import kotlin.math.abs

data class BeforeAfterState(
    val loading: Boolean = true,
    val months: List<YearMonth> = emptyList(),
    val before: MonthSnapshot? = null,
    val after: MonthSnapshot? = null
)

class BeforeAfterViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _state = MutableStateFlow(BeforeAfterState())
    val state: StateFlow<BeforeAfterState> = _state

    init {
        viewModelScope.launch {
            val months = runCatching { MonthlyStats.monthsWithData() }.getOrDefault(emptyList())
            val before = months.firstOrNull()?.let { runCatching { MonthlyStats.snapshot(it) }.getOrNull() }
            val after = months.lastOrNull()?.let { runCatching { MonthlyStats.snapshot(it) }.getOrNull() }
            _state.value = BeforeAfterState(false, months, before, after)
        }
    }

    fun pickBefore(m: YearMonth) {
        viewModelScope.launch {
            val snap = runCatching { MonthlyStats.snapshot(m) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(before = snap)
        }
    }

    fun pickAfter(m: YearMonth) {
        viewModelScope.launch {
            val snap = runCatching { MonthlyStats.snapshot(m) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(after = snap)
        }
    }
}

private val BaShape = RoundedCornerShape(20.dp)

private fun monthLabel(m: YearMonth): String = WeightForecast.monthHe(m.monthValue) + " " + (m.year % 100).toString().padStart(2, '0')

/**
 * "לפני ואחרי": two chosen months side by side. Photo, weight, measurements and Shambiuluk's body stage for that
 * month's weight (THE IRON RULE: the figure comes only from the weight via BodyStage).
 */
@Composable
fun BeforeAfterScreen() {
    val vm: BeforeAfterViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val p = profile

    Column(
        Modifier.fillMaxSize().background(HColors.Slate).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("לפני ואחרי", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
        if (st.loading || p == null) return@Column
        if (st.months.isEmpty()) {
            Text("עדיין אין חודשים להשוות. שקילה אחת ותמונה אחת, ובעוד חודש יש מה לראות.", color = HColors.ParchmentDim)
            return@Column
        }

        MonthPicker("לפני", st.months, st.before?.month) { vm.pickBefore(it) }
        MonthPicker("אחרי", st.months, st.after?.month) { vm.pickAfter(it) }

        val b = st.before
        val a = st.after
        if (b != null && a != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnapshotColumn("לפני", b, p, Modifier.weight(1f))
                SnapshotColumn("אחרי", a, p, Modifier.weight(1f))
            }
            DeltaCard(b, a)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MonthPicker(label: String, months: List<YearMonth>, selected: YearMonth?, onPick: (YearMonth) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = HColors.ParchmentDim, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            months.forEach { m ->
                FilterChip(selected = m == selected, onClick = { onPick(m) }, label = { Text(monthLabel(m)) })
            }
        }
    }
}

@Composable
private fun SnapshotColumn(title: String, s: MonthSnapshot, p: Profile, modifier: Modifier) {
    Card(modifier = modifier, shape = BaShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title · ${monthLabel(s.month)}", color = HColors.Gold, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val photo = s.photo
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(14.dp)).background(HColors.Ink),
                contentAlignment = Alignment.Center
            ) {
                if (photo != null) {
                    AsyncImage(model = File(photo.filePath), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("אין תמונה בחודש הזה", color = HColors.ParchmentDim, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                }
            }
            val kg = s.kg
            Text(if (kg != null) "${WeightForecast.fmt1(kg)} ק\"ג" else "אין שקילה", color = HColors.Parchment, fontSize = 20.sp, fontWeight = FontWeight.Black)
            // THE IRON RULE: body stage from this month's weight only
            if (kg != null) {
                val blend = BodyStage.forProfile(kg, p)
                Box(
                    Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(HColors.Ink)
                        .border(2.dp, HColors.SlateLight, RoundedCornerShape(14.dp))
                ) {
                    CharacterFigure(blend = blend, variant = p.characterVariant, modifier = Modifier.fillMaxSize().padding(6.dp))
                }
            }
        }
    }
}

private val MeasureNames = listOf("מותן", "חזה", "יד", "ירך", "אגן")
private fun values(m: MeasurementEntity?): List<Float> =
    if (m == null) List(5) { 0f } else listOf(m.waistCm, m.chestCm, m.armCm, m.thighCm, m.hipsCm)

@Composable
private fun DeltaCard(b: MonthSnapshot, a: MonthSnapshot) {
    Card(shape = BaShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.Gold)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ההפרש", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            val bk = b.kg
            val ak = a.kg
            if (bk != null && ak != null) Text("משקל: ${deltaText(ak - bk, "ק\"ג")}", color = HColors.Parchment, fontWeight = FontWeight.Bold)
            else Text("משקל: חסרה שקילה באחד החודשים.", color = HColors.ParchmentDim)
            val bv = values(b.measurement)
            val av = values(a.measurement)
            var any = false
            for (i in MeasureNames.indices) {
                if (bv[i] > 0f && av[i] > 0f) {
                    any = true
                    Text("${MeasureNames[i]}: ${deltaText(av[i] - bv[i], "ס\"מ")}", color = HColors.Parchment)
                }
            }
            if (!any) Text("מידות: צריך מדידה בשני החודשים כדי להשוות.", color = HColors.ParchmentDim)
        }
    }
}

private fun deltaText(d: Float, unit: String): String = when {
    abs(d) < 0.05f -> "ללא שינוי"
    d < 0f -> "פחות ${WeightForecast.fmt1(d)} $unit"
    else -> "יותר ${WeightForecast.fmt1(d)} $unit"
}
