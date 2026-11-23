package com.tamir.hamasa.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.BattleLog
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.MuscleHeat
import com.tamir.hamasa.ui.common.MuscleHeatmap
import com.tamir.hamasa.ui.common.WorkoutCalendar
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val KG_PER_CAR = 1500.0

class HistoryViewModel : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> =
        AppGraph.workouts.doneSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalVolume: StateFlow<Double> =
        AppGraph.workouts.totalVolume.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** Which session is expanded (one at a time keeps the list readable). */
    private val _expandedId = MutableStateFlow<Long?>(null)
    val expandedId: StateFlow<Long?> = _expandedId

    /** sessionId -> its sets, loaded on first expand. */
    private val _setsBySession = MutableStateFlow<Map<Long, List<SetLogEntity>>>(emptyMap())
    val setsBySession: StateFlow<Map<Long, List<SetLogEntity>>> = _setsBySession

    private val _exercises = MutableStateFlow<Map<String, ExerciseEntity>>(emptyMap())
    val exercises: StateFlow<Map<String, ExerciseEntity>> = _exercises

    /** sessionId -> muscle heat of that session, loaded together with its sets. */
    private val _heatBySession = MutableStateFlow<Map<Long, MuscleHeat.HeatResult>>(emptyMap())
    val heatBySession: StateFlow<Map<Long, MuscleHeat.HeatResult>> = _heatBySession

    /** "ספר הקרבות": sessionId -> Shambiuluk's line for that session. */
    val battleLog: StateFlow<Map<Long, String>> = AppGraph.prefs.profile
        .map { BattleLog.decode(it.battleLog) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggle(sessionId: Long) {
        if (_expandedId.value == sessionId) {
            _expandedId.value = null
            return
        }
        _expandedId.value = sessionId
        if (_setsBySession.value.containsKey(sessionId)) return
        viewModelScope.launch {
            val sets = AppGraph.db.sessions().setsForOnce(sessionId)
            _heatBySession.value = _heatBySession.value + (sessionId to AppGraph.workouts.heatForSession(sessionId))
            val known = _exercises.value
            val missing = sets.map { it.exerciseId }.distinct().filter { it !in known }
            if (missing.isNotEmpty()) {
                val add = mutableMapOf<String, ExerciseEntity>()
                for (id in missing) AppGraph.db.exercises().byId(id)?.let { add[id] = it }
                if (add.isNotEmpty()) _exercises.value = _exercises.value + add
            }
            _setsBySession.value = _setsBySession.value + (sessionId to sets)
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)
private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

@Composable
fun HistoryScreen(onOpenAnalysis: () -> Unit = {}, onOpenSession: (Long) -> Unit = {}, onOpenRecords: () -> Unit = {}, onOpenWeekly: () -> Unit = {}) {
    val vm: HistoryViewModel = viewModel()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val volume by vm.totalVolume.collectAsStateWithLifecycle()
    val expandedId by vm.expandedId.collectAsStateWithLifecycle()
    val setsBySession by vm.setsBySession.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val heatBySession by vm.heatBySession.collectAsStateWithLifecycle()
    val battleLog by vm.battleLog.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // "סיכום שבועי" + the end-of-week box
            Button(
                onClick = onOpenWeekly,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Ember, contentColor = HColors.Ink)
            ) { Text("הסיכום השבועי", fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
        item { WorkoutCalendar(sessions = sessions, onOpenSession = onOpenSession) }
        item { TotalsHeader(count = sessions.size, volumeKg = volume) }
        item {
            Button(
                onClick = onOpenAnalysis,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("ניתוח שרירים", fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
        item {
            Button(
                onClick = onOpenRecords,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.GoldDeep, contentColor = HColors.Ink)
            ) { Text("ספר השיאים", fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
        if (sessions.isEmpty()) {
            item {
                Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
                    Text(
                        "עדיין אין אימונים בפרוטוקול. הראשון תמיד הכי קשה. השני גם.",
                        Modifier.padding(16.dp),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        items(sessions, key = { it.id }) { s ->
            SessionRow(
                session = s,
                expanded = expandedId == s.id,
                sets = setsBySession[s.id],
                exercises = exercises,
                heat = heatBySession[s.id],
                logLine = battleLog[s.id],
                onClick = { onOpenSession(s.id) },
                onTogglePeek = { vm.toggle(s.id) }
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TotalsHeader(count: Int, volumeKg: Double) {
    val tons = volumeKg / 1000.0
    val cars = (volumeKg / KG_PER_CAR).toInt()
    val tonsText = if (tons < 10) String.format(Locale.US, "%.1f", tons) else tons.toInt().toString()
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("היסטוריה", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(count.toString(), color = HColors.Parchment, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("אימונים", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tonsText, color = HColors.Parchment, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("טונות הורמו", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(carsLine(cars, volumeKg), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun carsLine(cars: Int, volumeKg: Double): String = when {
    volumeKg <= 0.0 -> com.tamir.hamasa.domain.game.CharacterNames.g("עדיין לא הרמת כלום. רק אומר: גם המכונית לא זזה לבד.")
    cars == 0 -> "הרמת עד עכשיו ${volumeKg.toInt()} ק\"ג. עוד קצת ותהיה לך מכונית משפחתית שלמה."
    cars == 1 -> "הרמת עד עכשיו מכונית משפחתית אחת. שלמה."
    cars == 2 -> "הרמת עד עכשיו שתי מכוניות משפחתיות."
    else -> "הרמת עד עכשיו $cars מכוניות משפחתיות."
}

private fun typeName(t: SessionType): String = when (t) {
    SessionType.GYM -> "חדר כושר"
    SessionType.YOGA -> "יוגה"
    SessionType.WALK -> "הליכה"
    SessionType.MINI -> "אימון זעיר"
    SessionType.RUN -> "ריצה"
}

private fun durationText(sec: Int): String {
    val m = sec / 60
    return if (m < 60) "$m דק'" else "${m / 60} ש' ${m % 60} דק'"
}

@Composable
internal fun SessionRow(
    session: SessionEntity,
    expanded: Boolean,
    sets: List<SetLogEntity>?,
    exercises: Map<String, ExerciseEntity>,
    heat: MuscleHeat.HeatResult?,
    logLine: String? = null,
    onClick: () -> Unit,
    onTogglePeek: () -> Unit
) {
    val date = Days.of(session.dateEpochDay)
    val dateLabel = "יום ${Days.hebrewDayName(session.dateEpochDay)} ${date.format(DateFmt)}"
    val modeName = session.modeId?.let { ModeCatalog.byId(it)?.nameHe }
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(if (expanded) 2.dp else 1.dp, if (expanded) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(dateLabel, color = HColors.Parchment, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Stars(session.rating)
            }
            val line = buildString {
                append(typeName(session.type))
                if (modeName != null) append(" · ").append(modeName)
            }
            Text(line, color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            // stage 3: imported from the watch (data/health/WatchSync)
            if (com.tamir.hamasa.data.health.WatchSync.isWatchSession(session)) {
                Text("מהשעון", color = HColors.Sky, style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(durationText(session.durationSec), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                Text("${session.xpEarned} ניסיון", color = HColors.Sky, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("${session.goldEarned} זהב", color = HColors.Gold, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            if (!session.dailyGoal.isNullOrBlank()) {
                Text("מטרה: ${com.tamir.hamasa.domain.game.Addr.x(session.dailyGoal)}", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
            if (!logLine.isNullOrBlank()) {
                Text("ספר הקרבות: ${com.tamir.hamasa.domain.game.CharacterNames.g(logLine)}", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClick) { Text("פרטים", color = HColors.Gold, fontWeight = FontWeight.Bold) }
                TextButton(onClick = onTogglePeek) {
                    Text(if (expanded) com.tamir.hamasa.domain.game.Addr.t("סגור הצצה", "סגרי הצצה") else "הצצה", color = HColors.ParchmentDim)
                }
            }
            if (expanded) {
                HorizontalDivider(color = HColors.GoldDeep.copy(alpha = 0.4f))
                when {
                    sets == null -> Text("טוען...", color = HColors.ParchmentDim)
                    sets.isEmpty() -> Text("אין סטים רשומים לאימון הזה.", color = HColors.ParchmentDim)
                    else -> {
                        if (heat != null && !heat.isEmpty) {
                            Text("שרירים שעבדו", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                            MuscleHeatmap(heat = heat.heat, modifier = Modifier.fillMaxWidth())
                            val lines = heat.regionLines()
                            if (lines.isNotEmpty()) {
                                Text(lines.joinToString(", "), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(color = HColors.GoldDeep.copy(alpha = 0.4f))
                        }
                        SetsDetail(sets = sets, exercises = exercises)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetsDetail(sets: List<SetLogEntity>, exercises: Map<String, ExerciseEntity>) {
    val groups = sets.groupBy { it.exerciseId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (exerciseId, list) ->
            val ex = exercises[exerciseId]
            Column(Modifier.fillMaxWidth().background(HColors.Slate, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(ex?.nameHe ?: exerciseId, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                list.sortedBy { it.setIndex }.forEach { set ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("סט ${set.setIndex + 1}", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            setText(set, ex?.unit ?: "kg"),
                            color = if (set.completed) HColors.Parchment else HColors.ParchmentDim.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (set.isPr) {
                            Icon(Icons.Filled.Star, contentDescription = "שיא", tint = HColors.Gold, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun fmtKg(kg: Float): String {
    val whole = kg.toInt()
    return if (kg == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", kg)
}

private fun setText(set: SetLogEntity, unit: String): String {
    if (!set.completed) return "לא בוצע"
    return when (unit) {
        "sec" -> "${set.reps} שניות"
        "reps" -> "${set.reps} חזרות"
        else -> if (set.weight > 0f) "${set.reps} × ${fmtKg(set.weight)} ק\"ג" else "${set.reps} חזרות"
    }
}

@Composable
private fun Stars(rating: Int) {
    Row {
        for (i in 1..5) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = if (i <= rating) HColors.Gold else HColors.ParchmentDim.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
