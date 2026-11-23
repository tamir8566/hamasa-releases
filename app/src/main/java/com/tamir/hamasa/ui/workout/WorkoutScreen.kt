package com.tamir.hamasa.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import com.tamir.hamasa.ui.common.Sound
import com.tamir.hamasa.ui.common.Sfx
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeRules
import com.tamir.hamasa.ui.common.ExerciseAnim
import com.tamir.hamasa.ui.common.ExerciseHowToBox
import com.tamir.hamasa.ui.common.CoachWhyLink
import com.tamir.hamasa.data.ai.CoachWhy
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun WorkoutScreen(sessionId: Long, onFinished: () -> Unit, onAbandon: () -> Unit) {
    val vm: WorkoutViewModel = viewModel(key = "workout_$sessionId") { WorkoutViewModel(sessionId) }
    val session by vm.session.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val restRemaining by vm.restRemaining.collectAsStateWithLifecycle()
    val restEnded by vm.restEnded.collectAsStateWithLifecycle()
    val prBanner by vm.prBanner.collectAsStateWithLifecycle()
    val liveEvent by vm.event.collectAsStateWithLifecycle()
    val eventResult by vm.eventResult.collectAsStateWithLifecycle()
    val modeNote by vm.modeNote.collectAsStateWithLifecycle()
    val prAttemptSetId by vm.prAttemptSetId.collectAsStateWithLifecycle()
    val swapFrom by vm.swapFrom.collectAsStateWithLifecycle()
    val alternatives by vm.alternatives.collectAsStateWithLifecycle()
    val swapLoading by vm.swapLoading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val secretSetId by vm.secretSetId.collectAsStateWithLifecycle()
    val moodLine by vm.moodLine.collectAsStateWithLifecycle()
    val bossBar by vm.bossBar.collectAsStateWithLifecycle()
    val hardBattle by vm.hardBattle.collectAsStateWithLifecycle()
    val story by vm.story.collectAsStateWithLifecycle()
    val ghosts by vm.ghosts.collectAsStateWithLifecycle()
    val musicNote by vm.musicNote.collectAsStateWithLifecycle()
    val runUi by vm.run.ui.collectAsStateWithLifecycle()
    val difficultyLine by vm.difficultyLine.collectAsStateWithLifecycle()
    var suggestionsOpen by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current

    var showFinish by rememberSaveable { mutableStateOf(false) }
    var showAbandon by rememberSaveable { mutableStateOf(false) }

    // Back press = ask before abandoning the workout.
    BackHandler(enabled = !showAbandon) { showAbandon = true }

    // Opening sound, only for a session that just started (not when coming back to it).
    LaunchedEffect(session?.id) {
        val st = session?.startedAtMillis
        if (st != null && System.currentTimeMillis() - st < 60_000L) Sfx.play(context, Sound.START)
    }

    // Vibrate when a rest timer ends on its own, only while this screen is visible (in the background the rest
    // alarm already pinged and vibrated; a late buzz on return would be a second, wrong one).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(restEnded) {
        if (restEnded > 0 && lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) vibrate(context)
    }

    // Elapsed timer.
    var elapsedSec by remember { mutableIntStateOf(0) }
    val startedAt = session?.startedAtMillis
    LaunchedEffect(startedAt) {
        while (true) {
            val start = startedAt ?: System.currentTimeMillis()
            elapsedSec = ((System.currentTimeMillis() - start) / 1000L).toInt()
            delay(1000)
        }
    }

    val groups: List<Pair<String, List<SetLogEntity>>> = remember(sets) {
        sets.groupBy { it.exerciseId }.map { (id, list) -> id to list.sortedBy { it.setIndex } }
    }
    val currentExerciseId: String? = remember(groups) {
        groups.firstOrNull { g -> g.second.any { !it.completed } }?.first
    }
    val completedCount = sets.count { it.completed }

    // a plan run: screen on + vibration / sound at every interval switch (always composed, unlike the list items)
    RunCueEffects(vm.run)

    Column(Modifier.fillMaxSize().background(HColors.Slate)) {
        // Boss fight in rounds + "הקרב הקשה": pinned above the list so the HP drop is seen while training.
        BattleStrip(bar = bossBar, hardBattle = hardBattle)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WorkoutHeader(
                    modeName = session?.modeId?.let { ModeCatalog.byId(it)?.nameHe } ?: "אימון",
                    modeDesc = session?.modeId?.let { ModeCatalog.byId(it)?.descriptionHe }.orEmpty(),
                    goal = session?.dailyGoal,
                    elapsed = formatClock(elapsedSec),
                    done = completedCount,
                    total = sets.size
                )
            }
            // "אימון מסופר": the chapter of this workout, one more line with every completed set
            val storyNow = story
            if (storyNow != null) {
                item { StoryCard(storyNow) }
            }
            if (prBanner != null) {
                item { PrBanner(text = prBanner ?: "", onDismiss = { vm.dismissPr() }) }
            }
            val modeNoteText = modeNote
            if (modeNoteText != null) {
                item { ModeNoteCard(text = modeNoteText, onDismiss = { vm.dismissModeNote() }) }
            }
            // "מוזיקה שמגיבה": the boss entrance / last-set song just started (auto-hides)
            val musicNoteText = musicNote
            if (musicNoteText != null) {
                item { ModeNoteCard(text = musicNoteText, onDismiss = { vm.dismissMusicNote() }) }
            }
            val runningEvent = liveEvent
            if (runningEvent != null && runningEvent.accepted) {
                item { RunningEventCard(ev = runningEvent, onDone = { vm.completeEvent() }, onGiveUp = { vm.giveUpEvent() }) }
            }
            if (restRemaining > 0) {
                item { RestCard(remaining = restRemaining, onSkip = { vm.skipRest() }) }
            }
            // a plan run: the interval timer replaces the set list (its one timed set is completed by the timer)
            val isPlanRun = runUi != null
            if (isPlanRun) {
                item { RunLiveCard(vm.run) }
            }
            if (groups.isEmpty() && !isPlanRun) {
                item {
                    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
                        Text(
                            com.tamir.hamasa.domain.game.Addr.t("אין סטים מתוכננים באימון הזה. כשתסיים, לחץ על \"סיום אימון\".", "אין סטים מתוכננים באימון הזה. כשתסיימי, לחצי על \"סיום אימון\"."),
                            Modifier.padding(16.dp),
                            color = HColors.Parchment,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    CoachSuggestionsCard(
                        suggestions = suggestions,
                        open = suggestionsOpen,
                        onToggle = { suggestionsOpen = !suggestionsOpen },
                        onApply = { vm.applySuggestion(it) },
                        onDismiss = { vm.dismissSuggestion(it) }
                    )
                }
            }
            val mood = moodLine
            if (mood != null && completedCount == 0) {
                item {
                    Text(
                        mood,
                        Modifier.fillMaxWidth().background(HColors.Parchment, RoundedCornerShape(16.dp)).padding(12.dp),
                        color = HColors.Ink,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            val shownGroups: List<Pair<String, List<SetLogEntity>>> = if (isPlanRun) emptyList() else groups
            items(shownGroups, key = { it.first }) { (exerciseId, exerciseSets) ->
                val exEntity = exercises[exerciseId]
                ExerciseCard(
                    difficultyLine = if (exEntity != null && exEntity.unit != "sec" && !com.tamir.hamasa.domain.coach.ExerciseCatalog.isMobility(exerciseId)) difficultyLine else null,
                    ghost = ghosts[exerciseId],
                    secretSetId = secretSetId,
                    modeHint = ModeRules.liveHint(session?.modeId, exerciseId, exEntity?.isCompound ?: true, exEntity?.unit ?: "kg"),
                    prAttemptSetId = prAttemptSetId,
                    exercise = exercises[exerciseId],
                    exerciseId = exerciseId,
                    sets = exerciseSets,
                    isCurrent = exerciseId == currentExerciseId,
                    onCompleteSet = { set, reps, weight -> vm.completeSet(set, reps, weight) },
                    onEditSet = { set, reps, weight -> vm.editCompletedSet(set, reps, weight) },
                    onSwap = { vm.openSwap(exerciseId) },
                    onAddSet = { vm.addSet(exerciseId) },
                    whyLoad = { CoachWhy.forLive(sessionId, exerciseId) }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Bottom actions
        Row(
            Modifier.fillMaxWidth().background(HColors.Ink).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { showFinish = true },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("סיום אימון", fontWeight = FontWeight.Black, fontSize = 18.sp) }
            OutlinedButton(
                onClick = { showAbandon = true },
                enabled = !busy,
                modifier = Modifier.height(52.dp),
                shape = CardShape,
                border = BorderStroke(2.dp, HColors.Danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Danger)
            ) { Text("ביטול אימון", fontWeight = FontWeight.Bold) }
        }
    }

    val offeredEvent = liveEvent
    if (offeredEvent != null && !offeredEvent.accepted) {
        AlertDialog(
            onDismissRequest = { vm.declineEvent() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Gold,
            textContentColor = HColors.Parchment,
            title = { Text("אירוע", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(com.tamir.hamasa.domain.game.CharacterNames.g(offeredEvent.event.lineHe), style = MaterialTheme.typography.bodyLarge)
                    Text(com.tamir.hamasa.domain.game.Addr.x(offeredEvent.taskHe), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "קושי: ${offeredEvent.event.difficultyHe()} · +${offeredEvent.event.xp} ניסיון, +${offeredEvent.event.gold} זהב",
                        color = HColors.Gold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.acceptEvent() },
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("קיבלתי") }
            },
            dismissButton = { TextButton(onClick = { vm.declineEvent() }) { Text("לא היום", color = HColors.ParchmentDim) } }
        )
    }

    val eventResultText = eventResult
    if (eventResultText != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissEventResult() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Gold,
            textContentColor = HColors.Parchment,
            title = { Text("אירוע הושלם", fontWeight = FontWeight.Black) },
            text = { Text(com.tamir.hamasa.domain.game.Addr.x(eventResultText), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { vm.dismissEventResult() }) { Text("נו טוב", color = HColors.Gold, fontWeight = FontWeight.Bold) } }
        )
    }

    if (swapFrom != null) {
        SwapDialog(
            fromName = swapFrom?.let { exercises[it]?.nameHe ?: it } ?: "",
            loading = swapLoading,
            alternatives = alternatives.map { it.exerciseId to it.nameHe },
            onPick = { vm.swapTo(it) },
            onClose = { vm.closeSwap() }
        )
    }

    if (showFinish) {
        FinishDialog(
            busy = busy,
            onConfirm = { rating, kg ->
                vm.finish(rating, kg) {
                    showFinish = false
                    onFinished()
                }
            },
            onDismiss = { if (!busy) showFinish = false }
        )
    }

    if (showAbandon) {
        AlertDialog(
            onDismissRequest = { showAbandon = false },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.ParchmentDim,
            title = { Text("לבטל את האימון?", fontWeight = FontWeight.Bold) },
            text = { Text("הסטים שהושלמו יישמרו, אבל לא יהיו פרסים. אין טענות.") },
            confirmButton = {
                Button(
                    onClick = { vm.abandon { showAbandon = false; onAbandon() } },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Danger, contentColor = HColors.Parchment)
                ) { Text("כן, לבטל") }
            },
            dismissButton = { TextButton(onClick = { showAbandon = false }) { Text("להמשיך להתאמן", color = HColors.Gold) } }
        )
    }
}

@Composable
private fun WorkoutHeader(modeName: String, goal: String?, elapsed: String, done: Int, total: Int, modeDesc: String = "") {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modeName,
                    modifier = Modifier.weight(1f),
                    color = HColors.Gold,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(elapsed, color = HColors.Parchment, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            if (modeDesc.isNotBlank()) {
                Text(com.tamir.hamasa.domain.game.Addr.x(modeDesc), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
            if (!goal.isNullOrBlank()) {
                Text("מטרת היום: $goal", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            }
            if (total > 0) {
                Text("סטים: $done מתוך $total", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "הג'ים ברו מציע": advisory lines during a workout the user built himself. Collapsible; nothing is applied automatically. */
@Composable
private fun CoachSuggestionsCard(
    suggestions: List<CoachSuggestion>,
    open: Boolean,
    onToggle: () -> Unit,
    onApply: (CoachSuggestion) -> Unit,
    onDismiss: (CoachSuggestion) -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Sky)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("הג'ים ברו מציע", color = HColors.Sky, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${suggestions.size}", color = HColors.ParchmentDim, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onToggle) { Text(if (open) "הסתר" else "הצג", color = HColors.Sky, fontWeight = FontWeight.Bold) }
            }
            if (open) {
                Text("האימון שלך, ההחלטה שלך. ההצעות לא מופעלות לבד.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                suggestions.forEach { sg ->
                    Column(
                        Modifier.fillMaxWidth().background(HColors.Slate, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(sg.textHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (sg.canApply) {
                                Button(
                                    onClick = { onApply(sg) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Sky, contentColor = HColors.Ink)
                                ) { Text("ליישם", fontWeight = FontWeight.Black) }
                            }
                            OutlinedButton(
                                onClick = { onDismiss(sg) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, HColors.ParchmentDim),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.ParchmentDim)
                            ) { Text("לא תודה", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrBanner(text: String, onDismiss: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Gold),
        border = BorderStroke(3.dp, HColors.GoldDeep)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = HColors.Ink)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("שיא אישי!", color = HColors.Ink, style = MaterialTheme.typography.titleLarge)
                Text(text, color = HColors.Ink, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onDismiss) { Text("סגור", color = HColors.Ink, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RestCard(remaining: Int, onSkip: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Sky)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("מנוחה", color = HColors.ParchmentDim, style = MaterialTheme.typography.titleMedium)
            Text(
                formatClock(remaining),
                color = HColors.Parchment,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = onSkip,
                shape = CardShape,
                border = BorderStroke(2.dp, HColors.Sky),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Sky)
            ) { Text("דילוג על המנוחה", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ExerciseCard(
    /** Today's difficulty and reps in reserve (gym sessions); null = none. */
    difficultyLine: String? = null,
    /** "צל של אתמול": the ghost's sets of this exercise in order; null = no ghost. */
    ghost: List<com.tamir.hamasa.data.repo.GhostSet>? = null,
    secretSetId: Long? = null,
    modeHint: String? = null,
    prAttemptSetId: Long? = null,
    exercise: ExerciseEntity?,
    exerciseId: String,
    sets: List<SetLogEntity>,
    isCurrent: Boolean,
    onCompleteSet: (SetLogEntity, Int, Float) -> Unit,
    onEditSet: (SetLogEntity, Int, Float) -> Unit = { _, _, _ -> },
    onSwap: () -> Unit,
    onAddSet: () -> Unit,
    whyLoad: suspend () -> String
) {
    val allDone = sets.all { it.completed }
    val unit = exercise?.unit ?: "kg"
    val name = exercise?.nameHe ?: exerciseId
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = if (allDone) HColors.Ink else HColors.SlateLight),
        border = BorderStroke(if (isCurrent) 3.dp else 1.dp, if (isCurrent) HColors.Gold else HColors.GoldDeep.copy(alpha = 0.4f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        color = if (allDone) HColors.ParchmentDim else HColors.Parchment,
                        style = MaterialTheme.typography.titleLarge
                    )
                    val sub = buildString {
                        append("${sets.size} סטים")
                        if (exercise != null && exercise.isCompound) append(" · תרגיל מורכב")
                        if (allDone) append(" · הושלם")
                    }
                    Text(sub, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    if (exercise != null && exercise.isCustom && exercise.description.isNotBlank()) {
                        Text(exercise.description, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (modeHint != null && !allDone) {
                        Text(modeHint, color = HColors.Sky, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    if (difficultyLine != null && !allDone) {
                        Text(difficultyLine, color = HColors.Gold, style = MaterialTheme.typography.bodyMedium)
                    }
                    // stage 2 G: why this weight / reps / sets today
                    CoachWhyLink(title = name, load = whyLoad)
                }
                if (!allDone) {
                    TextButton(onClick = onSwap) { Text("החלפת תרגיל", color = HColors.Sky, fontWeight = FontWeight.Bold) }
                }
            }
            if (isCurrent && !allDone) {
                ExerciseAnim(exerciseId = exerciseId, modifier = Modifier.fillMaxWidth().height(140.dp))
                ExerciseHowToBox(exerciseId = exerciseId)
            }
            sets.forEach { set ->
                // "סט סודי": never on compounds (checked again here in case of a swap).
                val secret = set.id == secretSetId && exercise != null && !exercise.isCompound
                if (set.completed) {
                    CompletedSetRow(set = set, unit = unit, secret = secret, onEdit = { r, w -> onEditSet(set, r, w) })
                } else if (secret && !SecretSet.revealed(set, sets)) {
                    HiddenSecretSetRow()
                } else {
                    SetRow(set = set, unit = unit, enabled = true, secret = secret, prAttempt = !secret && set.id == prAttemptSetId, onComplete = { r, w -> onCompleteSet(set, r, w) })
                }
                // "צל של אתמול": the matching set of last time (informational only; never changes the target)
                if (ghost != null && !(secret && !set.completed)) {
                    ShadowLine(ghost = ghost.getOrNull(sets.indexOf(set)), set = set, unit = unit)
                }
            }
            if (sets.size < 12) {
                OutlinedButton(
                    onClick = onAddSet,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    border = BorderStroke(2.dp, HColors.GoldDeep),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                ) { Text("+ הוספת סט", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun HiddenSecretSetRow() {
    Row(
        Modifier.fillMaxWidth().background(HColors.Ink, RoundedCornerShape(12.dp))
            .border(2.dp, HColors.GoldDeep, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("סט סודי", color = HColors.Gold, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(12.dp))
        Text("יעד: ?", modifier = Modifier.weight(1f), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
        Text(com.tamir.hamasa.domain.game.Addr.t("נחשף כשתגיע אליו", "נחשף כשתגיעי אליו"), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompletedSetRow(set: SetLogEntity, unit: String, secret: Boolean = false, onEdit: (Int, Float) -> Unit = { _, _ -> }) {
    var editing by remember(set.id) { mutableStateOf(false) }
    if (editing) {
        EditDoneSetDialog(set = set, unit = unit, onSave = { r, w -> onEdit(r, w); editing = false }, onDismiss = { editing = false })
    }
    Row(
        Modifier.fillMaxWidth().background(HColors.Slate, RoundedCornerShape(12.dp))
            .clickable { editing = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (secret) "סט סודי" else "סט ${set.setIndex + 1}", color = if (secret) HColors.Gold else HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(12.dp))
        Text(
            describeSet(set.reps, set.weight, unit) + if (secret && set.reps >= SecretSet.targetReps(set)) " · +${SecretSet.XP} ניסיון" else "",
            modifier = Modifier.weight(1f),
            color = HColors.Parchment,
            style = MaterialTheme.typography.bodyLarge
        )
        if (set.isPr) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = HColors.Gold)
            Text(" שיא", color = HColors.Gold, fontWeight = FontWeight.Bold)
        } else {
            Text("הושלם", color = HColors.Moss, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(com.tamir.hamasa.domain.game.Addr.t("ערוך", "ערכי"), color = HColors.Sky, style = MaterialTheme.typography.labelLarge)
    }
}

/** Fix a done set: reps (or seconds) and weight. */
@Composable
private fun EditDoneSetDialog(set: SetLogEntity, unit: String, onSave: (Int, Float) -> Unit, onDismiss: () -> Unit) {
    var repsText by remember(set.id) { mutableStateOf(set.reps.toString()) }
    var weightText by remember(set.id) { mutableStateOf(formatKg(set.weight)) }
    val showWeight = unit == "kg"
    val reps = repsText.toIntOrNull()
    val weight = if (showWeight) weightText.toFloatOrNull() else set.weight
    // timed work: up to an hour, or the planned time when it is longer (a long walk)
    val maxReps = if (unit == "sec") maxOf(MAX_SET_SECONDS, set.targetReps) else MAX_SET_REPS
    val ok = reps != null && reps in 1..maxReps && weight != null && weight >= 0f && weight <= MAX_SET_KG
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        title = { Text("תיקון סט ${set.setIndex + 1}") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { v -> repsText = v.filter { it.isDigit() }.take(maxReps.toString().length) },
                    label = { Text(if (unit == "sec") "שניות" else "חזרות") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                if (showWeight) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { v -> weightText = cleanWeightInput(v) },
                        label = { Text("ק\"ג") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ok) onSave(reps ?: set.reps, weight ?: set.weight) }, enabled = ok) {
                Text("שמירה", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול", color = HColors.ParchmentDim) } }
    )
}

private fun describeSet(reps: Int, weight: Float, unit: String): String = when (unit) {
    "sec" -> "$reps שניות"
    "reps" -> "$reps חזרות"
    else -> if (weight > 0f) "$reps × ${formatKg(weight)} ק\"ג" else "$reps חזרות (משקל גוף)"
}

/** Upper limits of one set, the same as the template editor. */
private const val MAX_SET_KG = 500f
private const val MAX_SET_REPS = 999
private const val MAX_SET_SECONDS = 3600

/** Digits plus one decimal separator (',' becomes '.'), at most 6 characters. */
private fun cleanWeightInput(v: String): String {
    val sb = StringBuilder()
    var dot = false
    for (c in v) {
        if (c.isDigit()) sb.append(c)
        else if ((c == '.' || c == ',') && !dot) { sb.append('.'); dot = true }
        if (sb.length >= 6) break
    }
    return sb.toString()
}

@Composable
private fun SetRow(set: SetLogEntity, unit: String, enabled: Boolean, secret: Boolean = false, prAttempt: Boolean = false, onComplete: (Int, Float) -> Unit) {
    val shownReps = if (secret) SecretSet.targetReps(set) else set.targetReps
    var repsText by rememberSaveable(set.id, set.exerciseId, shownReps) { mutableStateOf(shownReps.toString()) }
    var weightText by rememberSaveable(set.id, set.exerciseId, set.targetWeight) { mutableStateOf(formatKg(set.targetWeight)) }
    val showWeight = unit == "kg"
    val repsLabel = if (unit == "sec") "שניות" else "חזרות"
    val target = describeSet(shownReps, set.targetWeight, unit)
    // same limits as the template editor: up to 500 kg, 999 reps (3600 seconds for timed work)
    val maxReps = if (unit == "sec") maxOf(MAX_SET_SECONDS, set.targetReps) else MAX_SET_REPS
    val typedReps = repsText.toIntOrNull() ?: 0
    val typedWeight = if (showWeight) (weightText.toFloatOrNull() ?: 0f) else 0f
    val valid = typedReps in 1..maxReps && typedWeight in 0f..MAX_SET_KG

    Column(
        Modifier.fillMaxWidth().background(HColors.Slate, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    secret -> "סט סודי"
                    prAttempt -> "ניסיון שיא"
                    else -> "סט ${set.setIndex + 1}"
                },
                color = HColors.Gold, style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    secret -> "יעד: $target · +${SecretSet.XP} ניסיון"
                    prAttempt -> "יעד: $target · ואז כמה שיותר חזרות נקיות"
                    else -> "יעד: $target"
                },
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = repsText,
                onValueChange = { v -> repsText = v.filter { it.isDigit() }.take(maxReps.toString().length) },
                label = { Text(repsLabel) },
                singleLine = true,
                isError = typedReps > maxReps,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            if (showWeight) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { v -> weightText = cleanWeightInput(v) },
                    label = { Text("ק\"ג") },
                    singleLine = true,
                    isError = typedWeight > MAX_SET_KG,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Column {
                    StepButton("+") {
                        val w = weightText.toFloatOrNull() ?: 0f
                        weightText = formatKg((w + 2.5f).coerceIn(0f, MAX_SET_KG))
                    }
                    StepButton("−") {
                        val w = weightText.toFloatOrNull() ?: 0f
                        weightText = formatKg((w - 2.5f).coerceAtLeast(0f))
                    }
                }
            }
        }
        Button(
            onClick = {
                if (valid) onComplete(typedReps, typedWeight)
            },
            enabled = enabled && valid,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
        ) { Text("סיימתי סט", fontWeight = FontWeight.Black, fontSize = 16.sp) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
        border = BorderStroke(1.dp, HColors.GoldDeep),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
    ) { Text(label, fontWeight = FontWeight.Black) }
}

@Composable
private fun SwapDialog(
    fromName: String,
    loading: Boolean,
    alternatives: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text("להחליף את $fromName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = HColors.Gold)
                    }
                    alternatives.isEmpty() -> Text("הג'ים ברו לא מצא חלופה מתאימה לתרגיל הזה.", color = HColors.ParchmentDim)
                    else -> alternatives.forEach { (id, name) ->
                        OutlinedButton(
                            onClick = { onPick(id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, HColors.GoldDeep),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Parchment)
                        ) { Text(name, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("ביטול", color = HColors.ParchmentDim) } }
    )
}

@Composable
private fun FinishDialog(busy: Boolean, onConfirm: (Int, Float?) -> Unit, onDismiss: () -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var weightText by rememberSaveable { mutableStateOf("") }
    // optional: empty = no weigh-in; otherwise a sane body weight
    val weightKg: Float? = weightText.toFloatOrNull()?.takeIf { it in 30f..300f }
    val weightOk = weightText.isBlank() || weightKg != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text("איך היה?", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(com.tamir.hamasa.domain.game.Addr.t("דרג את האימון", "דרגי את האימון"), color = HColors.ParchmentDim)
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    for (i in 1..5) {
                        IconButton(onClick = { rating = i }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "דירוג $i",
                                tint = if (i <= rating) HColors.Gold else HColors.ParchmentDim.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = cleanWeightInput(it) },
                    label = { Text(com.tamir.hamasa.domain.game.Addr.t("כמה אתה שוקל? (לא חובה)", "כמה את שוקלת? (לא חובה)")) },
                    suffix = { Text("ק\"ג") },
                    singleLine = true,
                    isError = !weightOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("נרשם כשקילה של היום, כמו במסך המשקל.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(rating, weightKg) },
                enabled = !busy && rating in 1..5 && weightOk,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = HColors.Ink, strokeWidth = 2.dp)
                else Text("סיום", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("עוד לא", color = HColors.ParchmentDim) } }
    )
}

/** Short double buzz when the rest timer ends. */
private fun vibrate(context: Context) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    try {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 300L, 150L, 300L), -1))
    } catch (e: Exception) {
        // vibration is a nicety; never crash the workout over it
    }
}

/** One-off line of today's mode (mode "surprise" swapped an exercise). */
@Composable
private fun ModeNoteCard(text: String, onDismiss: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Sky)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(com.tamir.hamasa.domain.game.Addr.x(text), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("סגור", color = HColors.Sky, fontWeight = FontWeight.Bold) }
        }
    }
}

/** A surprise event he accepted: the task stays on screen until he says it is done (or gives up). */
@Composable
private fun RunningEventCard(ev: LiveSurpriseEvent, onDone: () -> Unit, onGiveUp: () -> Unit) {
    Card(
        modifier = Modifier.alpha(0.6f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(3.dp, HColors.Gold)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("אירוע פעיל", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text(com.tamir.hamasa.domain.game.Addr.x(ev.taskHe), color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text("+${ev.event.xp} ניסיון, +${ev.event.gold} זהב", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDone,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("עשיתי", fontWeight = FontWeight.Black) }
                OutlinedButton(
                    onClick = onGiveUp,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HColors.ParchmentDim),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.ParchmentDim)
                ) { Text("לא עשיתי", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
