package com.tamir.hamasa.ui.history

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.repo.BattleLog
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.ui.common.ExerciseAnim
import com.tamir.hamasa.ui.common.HebrewDates
import com.tamir.hamasa.ui.common.MuscleHeatLegend
import com.tamir.hamasa.ui.common.MuscleHeatmap
import com.tamir.hamasa.ui.common.DurationEditDialog
import com.tamir.hamasa.ui.common.DurationEditPencil
import com.tamir.hamasa.ui.common.calendarTypeName
import com.tamir.hamasa.ui.theme.HColors
import java.util.Locale

private val DetailCardShape = RoundedCornerShape(20.dp)

/** Title for the WithBack header: "יום ו׳, 30 באוקטובר" once the session is read. */
@Composable
fun rememberSessionTitle(sessionId: Long): String {
    val title by produceState(initialValue = "פרטי אימון", key1 = sessionId) {
        val s = AppGraph.db.sessions().byId(sessionId)
        if (s != null) value = HebrewDates.longTitle(s.dateEpochDay)
    }
    return title
}

@Composable
fun SessionDetailScreen(sessionId: Long, onStartWorkout: (Long) -> Unit) {
    val vm: SessionDetailViewModel = viewModel(key = "session_detail_$sessionId") { SessionDetailViewModel(sessionId) }
    val st by vm.state.collectAsStateWithLifecycle()
    // "ספר הקרבות": Shambiuluk's line for this session (written by the done screen)
    val battleLine by produceState<String?>(initialValue = null, key1 = sessionId) { value = BattleLog.lineFor(sessionId) }
    var editingDuration by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val sessionOrNull = st.session
        when {
            st.loading -> Text("טוען...", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
            st.missing || sessionOrNull == null -> Text(
                "האימון הזה לא נמצא. אולי הוא בכלל היה חלום.",
                color = HColors.ParchmentDim,
                style = MaterialTheme.typography.bodyLarge
            )
            else -> {
                val session = sessionOrNull ?: return@Column
                // Type + mode line
                val modeName = session.modeId?.let { ModeCatalog.byId(it)?.nameHe }
                Text(
                    calendarTypeName(session.type) + (if (modeName != null) " · $modeName" else ""),
                    color = HColors.Gold,
                    style = MaterialTheme.typography.headlineMedium
                )
                // the day of the workout, and moving it to another day (not for watch-imported sessions)
                SessionDateRow(
                    day = session.dateEpochDay,
                    canEdit = session.status == SessionStatus.DONE && !WatchSync.isWatchSession(session) && !st.busy,
                    onSave = { vm.editDate(it) }
                )
                battleLine?.let { line ->
                    Card(
                        shape = DetailCardShape,
                        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
                        border = BorderStroke(2.dp, HColors.GoldDeep)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ספר הקרבות", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                            Text(com.tamir.hamasa.domain.game.CharacterNames.g(line), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                // "אימון מסופר": the chapter of this session
                StoryChapterCard(sessionId)

                // Header cards
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // duration: editable for finished app sessions; watch imports ("hc:" / "sh:") stay read-only
                    val canEditDuration = session.status == SessionStatus.DONE && !WatchSync.isWatchSession(session)
                    StatCard(
                        "משך",
                        durationLabel(session.durationSec),
                        HColors.Parchment,
                        Modifier.weight(1f),
                        onEdit = if (canEditDuration && !st.busy) ({ editingDuration = true }) else null
                    )
                    StatCard("משקל גוף (ק\"ג)", st.bodyWeightKg?.let { fmtNum(it) } ?: "—", HColors.Parchment, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tons = st.volumeKg >= 10000.0
                    StatCard(
                        if (tons) "נפח כולל (טון)" else "נפח כולל (ק\"ג)",
                        volumeValue(st.volumeKg),
                        HColors.Gold,
                        Modifier.weight(1f)
                    )
                    StatCard("ניסיון", session.xpEarned.toString(), HColors.Sky, Modifier.weight(1f))
                    StatCard("זהב", session.goldEarned.toString(), HColors.Gold, Modifier.weight(1f))
                }

                // energy calculator: net burn of this session (computed on the fly)
                com.tamir.hamasa.ui.energy.SessionBurnCard(
                    sessionId = sessionId, refreshKey = session.durationSec,
                    showSteps = session.type != SessionType.WALK && session.type != SessionType.RUN
                )

                // "הג'ים ברו אומר": the coach's summary of this session (gym sessions only, cached per session)
                com.tamir.hamasa.ui.common.CoachSaysCard(sessionId = sessionId)

                // Muscle heatmap
                val heat = st.heat
                if (heat != null && !heat.isEmpty) {
                    Card(
                        shape = DetailCardShape,
                        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                        border = BorderStroke(2.dp, HColors.GoldDeep)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("שרירים שעבדו", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                            MuscleHeatmap(heat = heat.heat, modifier = Modifier.fillMaxWidth())
                            MuscleHeatLegend()
                            val lines = heat.regionLines()
                            if (lines.isNotEmpty()) {
                                Text(lines.joinToString(", "), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                // Exercises
                if (st.exercises.isEmpty() && session.type != SessionType.WALK && session.type != SessionType.RUN) {
                    Text("אין סטים רשומים לאימון הזה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
                }
                if (session.type == SessionType.WALK || session.type == SessionType.RUN) {
                    CardioCard(sessionId, session, st.exercises.firstOrNull())
                } else {
                    st.exercises.forEach { ex -> ExerciseCard(ex) }
                }

                if (st.exercises.any { it.unit == "kg" && it.sessionBest1Rm != null }) {
                    Text(
                        "מרבי = משקל משוער לחזרה אחת. הערכה, לא הבטחה.",
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Message line (copy / start results)
                val msg = st.message
                if (msg != null) {
                    Card(shape = DetailCardShape, colors = CardDefaults.cardColors(containerColor = HColors.Ink), border = BorderStroke(2.dp, HColors.Moss)) {
                        Text(msg, Modifier.fillMaxWidth().padding(14.dp), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Actions
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.copyToTemplates() },
                        enabled = !st.busy,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = DetailCardShape,
                        border = BorderStroke(2.dp, HColors.Gold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                    ) { Text(com.tamir.hamasa.domain.game.Addr.t("העתק לאימון שלי", "העתיקי לאימון שלי"), fontWeight = FontWeight.Black, fontSize = 16.sp, textAlign = TextAlign.Center) }
                    Button(
                        onClick = { vm.startAgain(onStartWorkout) },
                        enabled = !st.busy,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = DetailCardShape,
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text("התחל שוב", fontWeight = FontWeight.Black, fontSize = 18.sp) }
                }
                Spacer(Modifier.height(16.dp))

                if (editingDuration) {
                    DurationEditDialog(
                        initialMinutes = (session.durationSec / 60).coerceAtLeast(1),
                        onDismiss = { editingDuration = false },
                        onSave = { minutes ->
                            editingDuration = false
                            vm.editDuration(minutes)
                        }
                    )
                }
            }
        }
    }
}

/**
 * "יום ד', 11 בנובמבר" with "שינוי תאריך": opens two buttons that step the day back and forward (never past
 * today) and a save. Inline on purpose, no dialog.
 */
@Composable
internal fun SessionDateRow(day: Long, canEdit: Boolean, onSave: (Long) -> Unit) {
    var editing by rememberSaveable(day) { mutableStateOf(false) }
    var picked by rememberSaveable(day) { mutableStateOf(day) }
    val today = com.tamir.hamasa.domain.Days.today()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                HebrewDates.longTitle(if (editing) picked else day),
                color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("session_date")
            )
            if (canEdit && !editing) {
                androidx.compose.material3.TextButton(onClick = { picked = day; editing = true }) {
                    Text("שינוי תאריך", color = HColors.Gold, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (editing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { picked -= 1 }, modifier = Modifier.weight(1f)) {
                    Text("יום קודם", color = HColors.Parchment)
                }
                androidx.compose.material3.OutlinedButton(onClick = { if (picked < today) picked += 1 }, enabled = picked < today, modifier = Modifier.weight(1f)) {
                    Text("יום הבא", color = HColors.Parchment)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { editing = false; if (picked != day) onSave(picked) },
                    enabled = picked != day,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("שמירת התאריך", fontWeight = FontWeight.Black) }
                androidx.compose.material3.TextButton(onClick = { editing = false; picked = day }, modifier = Modifier.weight(1f)) {
                    Text("ביטול", color = HColors.ParchmentDim)
                }
            }
            Text("השעה נשארת. הנקודות, הזהב והרצף לא משתנים.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier, onEdit: (() -> Unit)? = null) {
    Card(
        shape = DetailCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.GoldDeep),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1)
                Text(label, color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            if (onEdit != null) {
                DurationEditPencil(onClick = onEdit, modifier = Modifier.align(Alignment.TopStart))
            }
        }
    }
}

/** Walk / run: one card with duration, steps and burned calories instead of the set table. */
@Composable
private fun CardioCard(sessionId: Long, session: SessionEntity, ex: DetailExercise?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val steps by produceState<Int?>(initialValue = null, key1 = sessionId, key2 = session.durationSec) {
        value = runCatching { WatchSync.stepsForSession(context.applicationContext, sessionId) }.getOrNull()
    }
    val burn by produceState<Int?>(initialValue = null, key1 = sessionId, key2 = session.durationSec) {
        value = runCatching { com.tamir.hamasa.data.repo.EnergyData.sessionBurn(sessionId)?.kcal }.getOrNull()
    }
    Card(
        shape = DetailCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep.copy(alpha = 0.6f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ex != null) ExerciseAnim(exerciseId = ex.exerciseId, modifier = Modifier.size(64.dp))
                Text(
                    ex?.nameHe ?: calendarTypeName(session.type),
                    color = HColors.Parchment, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
                )
            }
            TableRow(cells = listOf("משך", "צעדים", "קלוריות"), color = HColors.Gold, bold = true, star = false)
            HorizontalDivider(color = HColors.GoldDeep.copy(alpha = 0.5f))
            val st = steps
            val kc = burn
            TableRow(
                cells = listOf(
                    durationLabel(session.durationSec),
                    if (st != null && st > 0) String.format(Locale.US, "%,d", st) else "—",
                    if (kc != null && kc > 0) String.format(Locale.US, "%,d", kc) else "—"
                ),
                color = HColors.Parchment, bold = false, star = false
            )
        }
    }
}

@Composable
private fun ExerciseCard(ex: DetailExercise) {
    Card(
        shape = DetailCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep.copy(alpha = 0.6f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExerciseAnim(exerciseId = ex.exerciseId, modifier = Modifier.size(64.dp))
                Text(ex.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }

            if (ex.rows.isEmpty()) {
                Text("לא בוצע באימון הזה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            val weighted = ex.unit == "kg"
            val repsHeader = if (ex.unit == "sec") "שניות" else "חזרות"
            // Header row (first column is on the right in RTL)
            TableRow(
                cells = listOf("סט", "ק\"ג", repsHeader, "מרבי"),
                color = HColors.Gold,
                bold = true,
                star = false
            )
            HorizontalDivider(color = HColors.GoldDeep.copy(alpha = 0.5f))
            ex.rows.forEach { r ->
                TableRow(
                    cells = listOf(
                        r.number.toString(),
                        if (weighted && r.weight > 0f) fmtNum(r.weight) else "—",
                        r.reps.toString(),
                        r.oneRm?.let { fmtNum(it) } ?: "—"
                    ),
                    color = HColors.Parchment,
                    bold = false,
                    star = r.isPr
                )
            }
            if (ex.plannedSets > ex.rows.size) {
                val skipped = ex.plannedSets - ex.rows.size
                Text(
                    if (skipped == 1) "סט אחד לא בוצע." else "$skipped סטים לא בוצעו.",
                    color = HColors.ParchmentDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val best = ex.sessionBest1Rm
            val allTime = ex.allTimeBest1Rm
            if (best != null) {
                HorizontalDivider(color = HColors.GoldDeep.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("הטוב באימון: ${fmtNum(best)}", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (allTime != null) {
                        Text(
                            "שיא כל הזמנים: ${fmtNum(allTime)}",
                            color = if (best >= allTime) HColors.Gold else HColors.ParchmentDim,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (allTime != null && best >= allTime) {
                    Text("זה השיא. כאן. ביום הזה.", color = HColors.Gold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, color: Color, bold: Boolean, star: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c ->
            Box(Modifier.weight(if (i == 0) 0.7f else 1f), contentAlignment = Alignment.Center) {
                Text(
                    c,
                    color = color,
                    fontSize = if (bold) 15.sp else 18.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (star) Icon(Icons.Filled.Star, contentDescription = "שיא", tint = HColors.Gold, modifier = Modifier.size(20.dp))
        }
    }
}

private fun fmtNum(v: Float): String {
    val whole = v.toInt()
    return if (v == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", v)
}

private fun durationLabel(sec: Int): String {
    val m = sec / 60
    return if (m < 60) "$m דק'" else "${m / 60}:${String.format(Locale.US, "%02d", m % 60)} ש'"
}

private fun volumeValue(kg: Double): String =
    if (kg >= 10000.0) String.format(Locale.US, "%.1f", kg / 1000.0)
    else String.format(Locale.US, "%,d", kg.toLong())
