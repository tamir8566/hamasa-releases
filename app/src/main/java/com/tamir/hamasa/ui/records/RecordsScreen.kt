package com.tamir.hamasa.ui.records

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One point of an exercise's timeline: the best value of one session. */
data class RecordPoint(val day: Long, val value: Float)

/**
 * One page of the records book. For weighted exercises [bestValue] is the best estimated 1RM
 * (Epley: weight × (1 + reps / 30)); for bodyweight / timed exercises it is the best reps / seconds.
 */
data class RecordPage(
    val exerciseId: String,
    val nameHe: String,
    val unit: String,
    val weighted: Boolean,
    val bestWeight: Float,
    val bestWeightReps: Int,
    val bestValue: Float,
    val bestDay: Long,
    val timeline: List<RecordPoint>
)

object RecordsMath {
    fun epley(weight: Float, reps: Int): Float = if (weight <= 0f || reps < 1) 0f else weight * (1f + reps / 30f)

    /** "82.5", "80" */
    fun fmt(v: Float): String {
        val whole = v.toInt()
        return if (v == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", v)
    }

    /** Builds every page from the database (completed sets of finished sessions only). */
    suspend fun load(): List<RecordPage> {
        val db = AppGraph.db
        val sessions = db.sessions().allSessionsOnce().filter { it.status == SessionStatus.DONE }.associateBy { it.id }
        val sets = db.sessions().allSetsOnce().filter { it.completed && it.sessionId in sessions && it.reps > 0 }
        val names = db.exercises().allOnce().associateBy { it.id }
        val out = ArrayList<RecordPage>()
        for ((exId, list) in sets.groupBy { it.exerciseId }) {
            val e = names[exId]
            val unit = e?.unit ?: "kg"
            val weighted = list.any { it.weight > 0f }
            fun valueOf(w: Float, r: Int): Float = if (weighted) epley(w, r) else r.toFloat()
            val bestSet = list.maxWithOrNull(compareBy<SetLogEntity>({ it.weight }, { it.reps })) ?: continue
            val bestBy = list.maxByOrNull { valueOf(it.weight, it.reps) } ?: continue
            val timeline = list.groupBy { it.sessionId }
                .mapNotNull { (sid, ss) ->
                    val day = sessions[sid]?.dateEpochDay ?: return@mapNotNull null
                    RecordPoint(day, ss.maxOf { valueOf(it.weight, it.reps) })
                }
                .sortedBy { it.day }
            out.add(
                RecordPage(
                    exerciseId = exId,
                    nameHe = e?.nameHe ?: exId,
                    unit = unit,
                    weighted = weighted,
                    bestWeight = bestSet.weight,
                    bestWeightReps = bestSet.reps,
                    bestValue = valueOf(bestBy.weight, bestBy.reps),
                    bestDay = sessions[bestBy.sessionId]?.dateEpochDay ?: 0L,
                    timeline = timeline
                )
            )
        }
        return out.sortedBy { it.nameHe }
    }
}

class RecordsViewModel : ViewModel() {
    private val _pages = MutableStateFlow<List<RecordPage>?>(null)
    val pages: StateFlow<List<RecordPage>?> = _pages

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _pages.value = runCatching { RecordsMath.load() }.getOrDefault(emptyList())
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)
private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

private fun dateHe(day: Long): String = if (day <= 0L) "" else Days.of(day).format(DateFmt)

private fun bestLine(p: RecordPage): String = when {
    p.weighted -> "${RecordsMath.fmt(p.bestWeight)} ק\"ג × ${p.bestWeightReps}"
    p.unit == "sec" -> "${p.bestWeightReps} שניות"
    else -> "${p.bestWeightReps} חזרות"
}

/** "ספר השיאים": a page for every exercise he did, with its best and the date. */
@Composable
fun RecordsScreen() {
    val vm: RecordsViewModel = viewModel()
    val pages by vm.pages.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val list = pages
    val open = list?.firstOrNull { it.exerciseId == openId }
    if (open != null) {
        BackHandler { openId = null }
        RecordDetail(open, onBack = { openId = null })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("ספר השיאים", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
        }
        when {
            list == null -> item { Text("פותח את הספר...", color = HColors.ParchmentDim) }
            list.isEmpty() -> item {
                Text("הספר ריק. דפים ריקים מחכים לשיאים. השיאים מחכים לך.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
            }
            else -> items(list, key = { it.exerciseId }) { p ->
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                    border = BorderStroke(2.dp, HColors.GoldDeep),
                    modifier = Modifier.fillMaxWidth().clickable { openId = p.exerciseId }
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("שיא: ${bestLine(p)}", color = HColors.Gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(dateHe(p.bestDay), color = HColors.ParchmentDim)
                        }
                        if (p.weighted) {
                            Text("מרבי משוער: ${RecordsMath.fmt(p.bestValue)} ק\"ג", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordDetail(p: RecordPage, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(HColors.Slate).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.nameHe, color = HColors.Gold, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("לכל הספר", color = HColors.Sky, fontWeight = FontWeight.Bold) }
        }
        Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.Parchment)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("השיא: ${bestLine(p)}", color = HColors.Ink, style = MaterialTheme.typography.titleLarge)
                if (p.weighted) Text("מרבי משוער: ${RecordsMath.fmt(p.bestValue)} ק\"ג", color = HColors.Ink)
                Text("נקבע ב-${dateHe(p.bestDay)}", color = HColors.Ink)
            }
        }
        Text(
            if (p.weighted) "מרבי משוער בכל אימון" else if (p.unit == "sec") "הכי הרבה שניות בכל אימון" else "הכי הרבה חזרות בכל אימון",
            color = HColors.ParchmentDim
        )
        if (p.timeline.size < 2) {
            Text("נקודה אחת זה עדיין לא קו. עוד אימון אחד.", color = HColors.ParchmentDim)
        } else {
            TimelineChart(p.timeline, Modifier.fillMaxWidth().height(200.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(dateHe(p.timeline.first().day), color = HColors.ParchmentDim, modifier = Modifier.weight(1f))
                Text(dateHe(p.timeline.last().day), color = HColors.ParchmentDim)
            }
        }
        Text("${p.timeline.size} אימונים בדף הזה", color = HColors.ParchmentDim)
    }
}

/** Small line chart, oldest point on the right (RTL reading order), with the best point highlighted. */
@Composable
private fun TimelineChart(points: List<RecordPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(HColors.SlateLight, RoundedCornerShape(16.dp)).padding(14.dp)) {
        val minV = points.minOf { it.value }
        val maxV = points.maxOf { it.value }
        val span = (maxV - minV).takeIf { it > 0.001f } ?: 1f
        val n = points.size
        fun pos(i: Int): Offset {
            // RTL: the first (oldest) point is drawn on the right edge.
            val x = size.width - size.width * i / (n - 1).toFloat()
            val y = size.height - (points[i].value - minV) / span * size.height
            return Offset(x, y)
        }
        val path = Path()
        for (i in 0 until n) {
            val o = pos(i)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, HColors.Gold, style = Stroke(width = 4.dp.toPx()))
        val bestIdx = points.indices.maxByOrNull { points[it].value } ?: 0
        for (i in 0 until n) {
            drawCircle(if (i == bestIdx) HColors.Ember else HColors.Parchment, radius = if (i == bestIdx) 7.dp.toPx() else 4.dp.toPx(), center = pos(i))
        }
    }
}
