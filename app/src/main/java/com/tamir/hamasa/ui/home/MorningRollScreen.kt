package com.tamir.hamasa.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.data.health.WatchStore
import kotlinx.coroutines.launch
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.sleepBand
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeVoice
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.ui.common.sessionTypeLabelHe
import com.tamir.hamasa.ui.templates.WorkoutChooserDialog
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(22.dp)

@Composable
fun MorningRollScreen(onStart: (Long) -> Unit, onBack: () -> Unit) {
    val vm: MorningRollViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile

    val hours = state.sleepHours
    // Yesterday's "סגירת היום" plan is the default for today (short sleep still turns GYM into yoga).
    val planned = p?.let { plannedForToday(it, Days.today()) }
    val suggested = CoachEngine.sessionTypeFor(sleepBand(hours), planned ?: SessionType.GYM)
    val band = sleepBand(hours)
    val voiceLine = remember(band) { Voice.morning(hours) }
    var chooserOpen by remember { mutableStateOf(false) }
    var jokerCardOpen by remember { mutableStateOf(false) }
    val jokerScope = rememberCoroutineScope()

    if (chooserOpen) {
        WorkoutChooserDialog(
            templates = state.templates,
            coachLabel = "אימון הג'ים ברו: ${sessionTypeLabelHe(suggested)}",
            enabled = !state.starting,
            onCoach = { chooserOpen = false; vm.start(suggested, onStart) },
            onTemplate = { id -> chooserOpen = false; vm.startTemplate(id, onStart) },
            onDismiss = { chooserOpen = false },
            onMini = { chooserOpen = false; vm.start(SessionType.MINI, onStart) },
            onWalk = { chooserOpen = false; vm.start(SessionType.WALK, onStart) },
            onMinimal = { chooserOpen = false; vm.startMinimal(onStart) }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה", tint = HColors.Parchment)
            }
            Text("הגרלת הבוקר", style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
        }

        Spacer(Modifier.height(8.dp))
        Text("כמה ישנת הלילה?", color = HColors.ParchmentDim, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = hours,
                onValueChange = { vm.setSleep((it * 2).roundToInt() / 2f) },
                onValueChangeFinished = { vm.sleepCommitted() },
                valueRange = 3f..10f,
                steps = 13,
                modifier = Modifier.weight(1f)
            )
            Text(formatHours(hours), color = HColors.Parchment, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        // last night from the watch (data/health/WatchSleep); moving the slider wins for today
        val watch by WatchStore.state.collectAsState()
        val today = Days.today()
        val sleepWatchLine = WatchSleep.sliderLine(p, watch.sleep[today], today)
        if (sleepWatchLine.isNotEmpty()) {
            Text(com.tamir.hamasa.domain.game.Addr.x(sleepWatchLine), color = HColors.Gold, fontSize = 14.sp)
        }
        Text(voiceLine, color = HColors.Parchment, fontSize = 15.sp)
        if (planned != null && p != null) {
            Text(plannedLabel(planned, p.plannedTomorrowHour), color = HColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text("הצעת הג'ים ברו להיום: ${sessionTypeLabelHe(suggested)}", color = HColors.ParchmentDim, fontSize = 14.sp)

        Spacer(Modifier.height(14.dp))
        MoodChips(p)

        Spacer(Modifier.height(16.dp))

        val modeName: String
        val modeDesc: String
        if (p == null || !state.rolled || p.rollModeId.isEmpty()) {
            modeName = "..."; modeDesc = ""
        } else {
            val m = ModeCatalog.byId(p.rollModeId)
            modeName = com.tamir.hamasa.domain.game.CharacterNames.g(m?.nameHe ?: p.rollModeId)
            modeDesc = com.tamir.hamasa.domain.game.CharacterNames.g(m?.descriptionHe ?: "")
        }
        val goal = com.tamir.hamasa.domain.game.CharacterNames.g(p?.rollGoal.orEmpty().ifEmpty { "..." })
        val bonus = when (p?.rollBonus) {
            "double_xp" -> "נקודות כפולות היום"
            "chest" -> "תיבה מובטחת"
            else -> "יום רגיל. גם זה חלק מהעניין."
        }

        FlipCard(index = 0, flipped = state.flipped[0], title = "המצב", front = modeName, sub = modeDesc, onFlip = { vm.flip(0) })
        if (state.modeChangedForSleep) {
            Spacer(Modifier.height(6.dp))
            Text(com.tamir.hamasa.domain.game.CharacterNames.g(ModeVoice.rollChangedForSleep()), color = HColors.ParchmentDim, fontSize = 14.sp)
        }
        if (p != null && p.rollModeId == "joker" && state.flipped[0]) {
            Spacer(Modifier.height(8.dp))
            JokerPicker(modes = state.availableModes.filter { it != "joker" }, onPick = { vm.pickJokerMode(it) })
        }
        // stage 1 E: a joker card kept from a chest replaces today's rolled mode with any open mode
        if (p != null && p.jokers > 0 && state.rolled && state.flipped[0] && p.rollModeId != "joker" && p.jokerPickDay != Days.today()) {
            Spacer(Modifier.height(8.dp))
            if (!jokerCardOpen) {
                Button(
                    onClick = { jokerCardOpen = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Ember, contentColor = HColors.Ink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(com.tamir.hamasa.domain.game.Addr.t("השתמש בג'וקר", "השתמשי בג'וקר") + " (${p.jokers})", fontWeight = FontWeight.Black)
                }
            } else {
                JokerPicker(
                    modes = state.availableModes.filter { it != "joker" && it != p.rollModeId },
                    onPick = { id ->
                        jokerCardOpen = false
                        jokerScope.launch { AppGraph.game.useJoker(id) }
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlipCard(index = 1, flipped = state.flipped[1], title = "המטרה", front = goal, sub = "", onFlip = { vm.flip(1) })
        Spacer(Modifier.height(12.dp))
        FlipCard(index = 2, flipped = state.flipped[2], title = "הבונוס", front = bonus, sub = "", onFlip = { vm.flip(2) })

        Spacer(Modifier.height(20.dp))
        val allFlipped = state.flipped.all { it }
        Button(
            // the roll no longer jumps straight into a workout: back to the home screen, he starts it himself
            onClick = onBack,
            enabled = state.rolled && !state.starting && p != null,
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(if (allFlipped) "חוזרים לבית" else "חוזרים בלי להפוך הכול", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatHours(h: Float): String {
    val whole = h.toInt()
    return if (h - whole >= 0.5f) "$whole וחצי" else "$whole"
}

/** Face-down card that flips around Y on tap. The front face is counter-rotated so its text is not mirrored. */
@Composable
private fun FlipCard(index: Int, flipped: Boolean, title: String, front: String, sub: String, onFlip: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = 0),
        label = "flip$index"
    )
    val showFront = rotation > 90f
    // At least 120 dp; grows when a mode description needs a third line (both faces share the height).
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clip(CardShape)
            .clickable(enabled = !flipped) { onFlip() },
        propagateMinConstraints = true
    ) {
        if (!showFront) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(HColors.GoldDeep, HColors.Gold, HColors.GoldDeep)))
                    .border(4.dp, HColors.Ink, CardShape),
                contentAlignment = Alignment.Center
            ) {
                Text("קלף ${index + 1}", color = HColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .background(HColors.Parchment)
                    .border(4.dp, HColors.Ink, CardShape)
                    .padding(14.dp)
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = HColors.GoldDeep, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(front, color = HColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    if (sub.isNotEmpty()) Text(sub, color = HColors.Ink, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Mode "joker": every mode he has open, two per row. Tapping one makes it today's mode. */
@Composable
private fun JokerPicker(modes: List<String>, onPick: (String) -> Unit) {
    val line = remember { ModeVoice.jokerPick() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HColors.SlateLight)
            .border(2.dp, HColors.Gold, CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(line, color = HColors.Parchment, fontSize = 15.sp)
        modes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(HColors.Ink)
                            .clickable { onPick(id) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(com.tamir.hamasa.domain.game.CharacterNames.g(ModeCatalog.byId(id)?.nameHe ?: id), color = HColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
