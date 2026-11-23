package com.tamir.hamasa.ui.coach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.ai.CoachNotes
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.health.WatchPermissionContract
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.domain.coach.CoachConstraints
import com.tamir.hamasa.ui.common.CoachWhyLink
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.coach.sleepBand
import com.tamir.hamasa.ui.common.ExerciseAnim
import com.tamir.hamasa.ui.common.ExerciseHowToBox
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.roundToInt

private fun focusLabel(f: Focus): String = when (f) {
    Focus.LOSE_WEIGHT -> "ירידה במשקל"
    Focus.BUILD_MUSCLE -> "בניית שריר"
    Focus.STRENGTH -> "כוח"
    Focus.FLEXIBILITY -> "גמישות"
    Focus.ENDURANCE -> "סיבולת"
    Focus.BRANCH_CHEST -> "דגש חזה"
    Focus.BRANCH_BACK -> "דגש גב"
    Focus.BRANCH_LEGS -> "דגש רגליים"
    Focus.BRANCH_SHOULDERS_ARMS -> "דגש כתפיים וידיים"
    Focus.BRANCH_CORE -> "דגש ליבה"
}

private fun branchLabel(b: Branch): String = when (b) {
    Branch.CHEST -> "חזה"
    Branch.BACK -> "גב"
    Branch.LEGS -> "רגליים"
    Branch.SHOULDERS_ARMS -> "כתפיים וידיים"
    Branch.CORE -> "ליבה"
    Branch.ENDURANCE -> "סיבולת"
}

private fun kgText(v: Float): String =
    if (v == v.roundToInt().toFloat()) "${v.roundToInt()} ק\"ג" else String.format("%.1f ק\"ג", v)

private fun doseText(e: PlannedExercise, unitIsSec: Boolean): String =
    when {
        unitIsSec && e.sets == 1 && e.reps >= 120 && e.reps % 60 == 0 -> "${e.reps / 60} דקות"
        unitIsSec -> "${e.sets} × ${e.reps} שניות"
        else -> "${e.sets} × ${e.reps} חזרות"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(onStartWorkout: (Long) -> Unit, onOpenCustomExercises: () -> Unit = {}, onOpenTemplates: () -> Unit = {}) {
    val vm: CoachViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val profile = state.profile
    val plan = state.plan
    val band = sleepBand(state.sleepHours)

    // last night's sleep from the watch (optional Health Connect permission, data/health/WatchSleep)
    val context = LocalContext.current
    val watch by WatchStore.state.collectAsState()
    val todayDay = com.tamir.hamasa.domain.Days.today()
    var sleepPermTick by remember { mutableIntStateOf(0) }
    // re-checked after every sleep read too (the permission may have been changed in Health Connect itself)
    val sleepGranted by produceState<Boolean?>(initialValue = null, watch.connected, sleepPermTick, watch.sleepDiagHe) {
        value = if (watch.connected) WatchSleep.hasPermission(context) else null
    }
    // opening the coach: a sync when due, else a quick sleep re-check while tonight's sleep is missing
    LaunchedEffect(Unit) { WatchSync.syncSoon(context) }
    val sleepContract = remember { WatchPermissionContract() }
    val sleepLauncher = rememberLauncherForActivityResult(sleepContract) { result ->
        WatchSync.onPermissionsResult(context, result)
        sleepPermTick++
    }
    val sleepWatchLine = WatchSleep.sliderLine(profile, watch.sleep[todayDay], todayDay)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("הג'ים ברו", style = MaterialTheme.typography.headlineLarge, color = HColors.Gold)
        }

        // ---- focus
        item {
            Text("מה המיקוד השבוע", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Focus.values().forEach { f ->
                    val selected = profile?.focus == f
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) vm.setFocus(f) },
                        label = { Text(focusLabel(f), fontSize = 15.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HColors.Gold,
                            selectedLabelColor = HColors.Ink,
                            containerColor = HColors.SlateLight,
                            labelColor = HColors.Parchment
                        )
                    )
                }
            }
        }

        // ---- difficulty (setting; a note may override it for a few days)
        item {
            CoachDifficultyCard(
                setting = com.tamir.hamasa.domain.coach.Difficulty.fromName(profile?.coachDifficulty),
                effective = state.difficulty,
                constraints = state.constraints,
                onPick = { vm.setDifficulty(it) }
            )
        }

        // ---- sleep
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                border = BorderStroke(2.dp, HColors.Ink)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("כמה ישנת הלילה", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                        Text(
                            "${formatHours(state.sleepHours)} שעות",
                            style = MaterialTheme.typography.titleLarge,
                            color = HColors.Gold
                        )
                    }
                    Slider(
                        value = state.sleepHours,
                        onValueChange = { vm.setSleep((it * 2f).roundToInt() / 2f) },
                        onValueChangeFinished = { vm.sleepCommitted() },
                        valueRange = 3f..10f,
                        steps = 13
                    )
                    if (sleepWatchLine.isNotEmpty()) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(sleepWatchLine), style = MaterialTheme.typography.bodyMedium, color = HColors.Gold)
                    } else if (sleepGranted == true && watch.connected) {
                        Text("השעון עוד לא שלח את הלילה. המחוון ידני בינתיים.", style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim)
                    }
                    if (sleepGranted == false) {
                        OutlinedButton(onClick = {
                            runCatching { sleepLauncher.launch(setOf(WatchSleep.PERMISSION)) }
                        }) { Text(com.tamir.hamasa.domain.game.Addr.t("קבל את השינה מהשעון", "קבלי את השינה מהשעון"), color = HColors.Parchment, fontWeight = FontWeight.Bold) }
                    }
                    val sleepHint = when (band) {
                        SleepBand.FULL -> "שינה מלאה. אימון רגיל."
                        SleepBand.MEDIUM -> "שינה בינונית. הג'ים ברו מוריד סט אחד מכל תרגיל."
                        SleepBand.SHORT -> "שינה קצרה. הג'ים ברו ממליץ על יוגה או אימון מקוצר של שלושה תרגילים."
                    }
                    Text(com.tamir.hamasa.domain.game.Addr.x(sleepHint), style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("יוגה במקום", style = MaterialTheme.typography.titleMedium, color = HColors.Parchment, modifier = Modifier.weight(1f))
                        Switch(checked = state.yogaInstead, onCheckedChange = { vm.setYoga(it) })
                    }
                }
            }
        }

        // ---- note to the coach (stage 2 G)
        item {
            CoachNoteCard(
                text = state.noteText,
                sending = state.noteSending,
                reply = state.noteReply,
                doctor = state.noteDoctor,
                onText = { vm.setNote(it) },
                onSend = { vm.sendNote() },
                onDismissReply = { vm.dismissNoteReply() }
            )
        }

        // ---- "אימון בבית": right under the note to the coach
        item {
            CoachHomeCard(onOpen = { vm.openHome() })
        }

        // ---- active limits chip
        val activeLimits = state.constraints
        if (activeLimits != null) {
            item {
                ConstraintsChip(c = activeLimits, onClick = { vm.openConstraints() })
            }
            // a note asked for a run / walk / yoga today
            if (activeLimits.sessionType == "RUN" || activeLimits.sessionType == "WALK" || activeLimits.sessionType == "YOGA") {
                item {
                    CoachTypeWishCard(
                        type = activeLimits.sessionType,
                        runAllowed = state.runStatus?.restToday == false,
                        starting = state.starting,
                        onRun = { vm.startRun(onStartWorkout) },
                        onWalk = { vm.startWalk(onStartWorkout) }
                    )
                }
            }
        }

        // ---- deload week: the coach's proposal, or the running / upcoming week (with cancel)
        if (profile != null) {
            item {
                com.tamir.hamasa.ui.home.DeloadPanel(p = profile, today = com.tamir.hamasa.domain.Days.today(), showShambiuluk = false)
            }
        }

        // ---- plan header
        item {
            if (state.loading || plan == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HColors.Gold)
                }
            } else {
                Column {
                    Text(com.tamir.hamasa.domain.game.Addr.x(plan.titleHe), style = MaterialTheme.typography.headlineMedium, color = HColors.Parchment)
                    Text(
                        "${plan.exercises.size} תרגילים, בערך ${plan.estimatedMinutes} דקות",
                        style = MaterialTheme.typography.bodyLarge, color = HColors.ParchmentDim
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HColors.Ink)
                            .padding(12.dp)
                    ) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(plan.coachLineHe), style = MaterialTheme.typography.bodyLarge, color = HColors.Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---- exercises
        if (plan != null && !state.loading) {
            // Key includes the position: a swap can bring in an id that already exists in the plan.
            itemsIndexed(plan.exercises, key = { i, e -> "$i:${e.exerciseId}" }) { _, e ->
                ExerciseCard(
                    e = e, custom = state.customExercises[e.exerciseId], customs = state.customExercises,
                    onClick = { vm.openAlternatives(e.exerciseId) },
                    whyLoad = { vm.why(e) }
                )
            }
            item {
                Text(
                    "לחיצה על תרגיל מציגה חלופות",
                    style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
                )
            }
        }

        // ---- the running coach: next session of the 9-week plan
        val runStatus = state.runStatus
        if (runStatus != null) {
            item {
                CoachRunCard(status = runStatus, starting = state.starting, onStart = { vm.startRun(onStartWorkout) })
            }
        }

        // ---- my exercises
        item {
            OutlinedButton(
                onClick = onOpenCustomExercises,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
            ) {
                Text(
                    if (state.customExercises.isEmpty()) "התרגילים שלי" else "התרגילים שלי (${state.customExercises.size})",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- my workouts (user-built templates)
        item {
            OutlinedButton(
                onClick = onOpenTemplates,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
            ) {
                Text("האימונים שלי", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- start
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.start(onStartWorkout) },
                enabled = plan != null && !state.loading && !state.starting && plan.exercises.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) {
                Text(if (state.starting) "פותחים אימון..." else "התחלת אימון", fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---- workouts he already did, added by hand: at the very end, so today's plan stays right under the note
        // (owner flow 2026-11-11: with two cards above it the plan was pushed two screens down)
        item { ManualWalkCard() }
        item {
            ManualYogaCard()
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- "אימון בבית" sheet
    if (state.homeOpen) {
        HomeWorkoutDialog(
            sleepHours = state.sleepHours,
            onDismiss = { vm.closeHome() },
            onStarted = onStartWorkout
        )
    }

    // ---- calorie goal suggestion after a focus change (once per focus, never silent)
    val kcalSuggestion = state.kcalSuggestion
    if (kcalSuggestion != null) {
        AlertDialog(
            onDismissRequest = { vm.answerKcal(false) },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.Parchment,
            title = { Text("יעד קלוריות") },
            text = {
                Text("המיקוד השתנה. לעדכן יעד קלוריות ל-$kcalSuggestion? היעד הנוכחי: ${profile?.kcalGoal ?: 0}.")
            },
            confirmButton = {
                TextButton(onClick = { vm.answerKcal(true) }) { Text(com.tamir.hamasa.domain.game.Addr.t("עדכן", "עדכני"), color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerKcal(false) }) { Text("להשאיר", color = HColors.ParchmentDim) }
            }
        )
    }

    // ---- active limits: details + "בטל"
    val limits = state.constraints
    if (state.constraintsOpen && limits != null) {
        val lines = CoachNotes.describe(limits, state.customExercises.values.toList())
        AlertDialog(
            onDismissRequest = { vm.closeConstraints() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.Parchment,
            title = { Text("התאמה עד ${CoachNotes.dayLabel(limits.untilEpochDay)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (limits.noteHe.isNotEmpty()) {
                        Text("כתבת: ${limits.noteHe}", style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                    }
                    if (limits.replyHe.isNotEmpty()) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(limits.replyHe), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    lines.forEach { line ->
                        Text("• ${com.tamir.hamasa.domain.game.Addr.x(line)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (limits.needsDoctor) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(CoachNotes.DOCTOR_LINE), style = MaterialTheme.typography.bodyMedium, color = HColors.Sky)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeConstraints() }) { Text("סגור", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearConstraints() }) { Text(com.tamir.hamasa.domain.game.Addr.t("בטל התאמה", "בטלי התאמה"), color = HColors.Danger) }
            }
        )
    }

    // ---- alternatives dialog
    val altFor = state.altFor
    if (altFor != null && plan != null) {
        val current = plan.exercises.firstOrNull { it.exerciseId == altFor }
        AlertDialog(
            onDismissRequest = { vm.closeAlternatives() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.Parchment,
            title = { Text("חלופות ל${current?.nameHe ?: ""}") },
            text = {
                if (state.alternatives.isEmpty()) {
                    Text("אין חלופות לתרגיל הזה.", color = HColors.ParchmentDim)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.alternatives.forEach { alt ->
                            val isSec = (ExerciseCatalog.byId(alt.exerciseId) ?: state.customExercises[alt.exerciseId])?.unit == "sec"
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(HColors.Slate)
                                    .clickable { vm.swap(altFor, alt) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ExerciseAnim(exerciseId = alt.exerciseId, modifier = Modifier.height(80.dp).width(80.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(alt.nameHe, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
                                    Text(
                                        if (alt.weightKg > 0f) "${doseText(alt, isSec)}, ${kgText(alt.weightKg)}" else doseText(alt, isSec),
                                        style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
                                    )
                                }
                                Text(com.tamir.hamasa.domain.game.Addr.t("החלף", "החליפי"), color = HColors.Gold, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeAlternatives() }) { Text("סגור", color = HColors.Gold) }
            }
        )
    }
}

private fun formatHours(h: Float): String =
    if (h == h.roundToInt().toFloat()) h.roundToInt().toString() else String.format("%.1f", h)

@Composable
private fun ExerciseCard(
    e: PlannedExercise,
    custom: ExerciseEntity?,
    customs: Map<String, ExerciseEntity>,
    onClick: () -> Unit,
    whyLoad: suspend () -> String
) {
    val isSec = (ExerciseCatalog.byId(e.exerciseId) ?: custom)?.unit == "sec"
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.nameHe,
                    style = MaterialTheme.typography.titleLarge,
                    color = HColors.Parchment,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, HColors.GoldDeep, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(branchLabel(e.branch), style = MaterialTheme.typography.labelSmall, color = HColors.Gold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(doseText(e, isSec), style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment)
                if (e.weightKg > 0f) {
                    Text(kgText(e.weightKg), style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
                }
                Text("מנוחה ${e.restSec} שניות", style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
            }
            if (custom != null && custom.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(custom.description, style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
            }
            if (e.supersetWith != null) {
                val other = (ExerciseCatalog.byId(e.supersetWith) ?: customs[e.supersetWith])?.nameHe ?: ""
                Text("סופר-סט עם $other", style = MaterialTheme.typography.bodyMedium, color = HColors.Sky)
            }
            if (e.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(com.tamir.hamasa.domain.game.Addr.x(e.note), style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
            }
            CoachWhyLink(title = e.nameHe, load = whyLoad)
            Spacer(Modifier.height(6.dp))
            ExerciseAnim(exerciseId = e.exerciseId, modifier = Modifier.fillMaxWidth().height(72.dp))
            ExerciseHowToBox(exerciseId = e.exerciseId)
        }
    }
}

/** "הודעה לג'ים ברו": free text -> the coach adapts the plan for a few days (stage 2 G). */
@Composable
private fun CoachNoteCard(
    text: String,
    sending: Boolean,
    reply: String?,
    doctor: Boolean,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onDismissReply: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("הודעה לג'ים ברו", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Text(
                "כאב, עייפות, או מה מתאים לך היום: ציוד, מקום, זמן, ריצה או יוגה, קל או קשה. הג'ים ברו מתאים את התוכנית.",
                style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
            )
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                enabled = !sending,
                placeholder = { Text("למשל: היום רק גומייה ומשקל גוף, יש לי 30 דקות", color = HColors.ParchmentDim) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = HColors.Parchment,
                    unfocusedTextColor = HColors.Parchment,
                    disabledTextColor = HColors.ParchmentDim,
                    focusedContainerColor = HColors.Ink,
                    unfocusedContainerColor = HColors.Ink,
                    disabledContainerColor = HColors.Ink,
                    focusedBorderColor = HColors.Gold,
                    unfocusedBorderColor = HColors.GoldDeep,
                    cursorColor = HColors.Gold
                ),
                modifier = Modifier.fillMaxWidth().testTag("coach_note_field")
            )
            Button(
                onClick = onSend,
                enabled = !sending && text.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (sending) "הג'ים ברו קורא..." else com.tamir.hamasa.domain.game.Addr.t("שלח לג'ים ברו", "שלחי לג'ים ברו"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            if (reply != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HColors.Ink)
                        .clickable { onDismissReply() }
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(reply), style = MaterialTheme.typography.bodyLarge, color = HColors.Gold, fontWeight = FontWeight.Bold)
                        if (doctor) {
                            Text(com.tamir.hamasa.domain.game.Addr.x(CoachNotes.DOCTOR_LINE), style = MaterialTheme.typography.bodyMedium, color = HColors.Sky)
                        }
                    }
                }
            }
        }
    }
}

/** "אימון בבית": a card that opens the equipment sheet. */
@Composable
private fun CoachHomeCard(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("אימון בבית", style = MaterialTheme.typography.titleLarge, color = HColors.Gold, fontWeight = FontWeight.Bold)
                Text(
                    "בוחרים מה יש בבית, והג'ים ברו בונה אימון רק מזה.",
                    style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
                )
            }
            Text(com.tamir.hamasa.domain.game.Addr.t("בחר", "בחרי"), color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
    }
}

/** "מותאם לברך עד 6.11" / "מותאם להיום: גומייה ומשקל גוף": the active note; tap -> details + "בטל". */
@Composable
private fun ConstraintsChip(c: CoachConstraints, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(2.dp, HColors.Sky, RoundedCornerShape(50))
            .background(HColors.Ink)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            com.tamir.hamasa.domain.game.Addr.x(CoachNotes.chipLabel(c, com.tamir.hamasa.domain.Days.today())),
            style = MaterialTheme.typography.titleSmall, color = HColors.Sky, fontWeight = FontWeight.Bold
        )
    }
}
