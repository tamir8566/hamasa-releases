package com.tamir.hamasa.ui.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.HomeGear
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.WorkoutPlan
import com.tamir.hamasa.ui.common.CoachWhyLink
import com.tamir.hamasa.ui.common.ExerciseAnim
import com.tamir.hamasa.ui.common.ExerciseHowToBox
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.roundToInt

/**
 * "אימון בבית" as a full-width dialog (coach screen and Home). [sleepHours] = the coach screen's slider; null on Home
 * (today's value in Prefs is used). [onStarted] gets the new session id.
 */
@Composable
fun HomeWorkoutDialog(sleepHours: Float?, onDismiss: () -> Unit, onStarted: (Long) -> Unit) {
    val vm: HomeWorkoutViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.open(sleepHours) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
            HomeWorkoutContent(
                state = state,
                onToggle = { vm.toggleGear(it) },
                onDumbbellKg = { vm.setDumbbellKg(it) },
                onDumbbellFixed = { vm.setDumbbellFixed(it) },
                onKettlebellKg = { vm.setKettlebellKg(it) },
                onMinutes = { vm.setMinutes(it) },
                onFocus = { vm.setFocus(it) },
                onBuild = { vm.build() },
                onStart = { vm.start { id -> onDismiss(); onStarted(id) } },
                onClose = onDismiss,
                why = { p -> vm.why(p) }
            )
        }
    }
}

/** "6 תרגילים · 11 סטים · כ-30 דקות" (the rope warm-up and mobility holds are not working sets). */
fun homeSummaryHe(plan: WorkoutPlan): String {
    val work = plan.exercises.filter { it.exerciseId != CoachEngine.HOME_WARMUP_ID && !ExerciseCatalog.isMobility(it.exerciseId) }
    val sets = work.sumOf { it.sets }
    return "${work.size} תרגילים · $sets סטים · כ-${plan.estimatedMinutes} דקות"
}

private fun homeKgText(v: Float): String =
    if (v == v.roundToInt().toFloat()) "${v.roundToInt()} ק\"ג" else String.format("%.1f ק\"ג", v)

@Composable
private fun homeChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = HColors.Gold,
    selectedLabelColor = HColors.Ink,
    containerColor = HColors.Slate,
    labelColor = HColors.Parchment
)

@Composable
private fun kgFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = HColors.Parchment,
    unfocusedTextColor = HColors.Parchment,
    focusedContainerColor = HColors.Ink,
    unfocusedContainerColor = HColors.Ink,
    focusedBorderColor = HColors.Gold,
    unfocusedBorderColor = HColors.GoldDeep,
    focusedLabelColor = HColors.Gold,
    unfocusedLabelColor = HColors.ParchmentDim,
    cursorColor = HColors.Gold
)

/**
 * The sheet itself, stateless (the screenshot test renders it directly): gear chips, the dumbbell / kettlebell
 * weight, time and focus, "בנה אימון", then the summary, the preview list and "התחל".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeWorkoutContent(
    state: HomeWorkoutUiState,
    onToggle: (String) -> Unit,
    onDumbbellKg: (String) -> Unit,
    onDumbbellFixed: (Boolean) -> Unit,
    onKettlebellKg: (String) -> Unit,
    onMinutes: (Int) -> Unit,
    onFocus: (String) -> Unit,
    onBuild: () -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    why: suspend (PlannedExercise) -> String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(3.dp, HColors.Ink),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("אימון בבית", style = MaterialTheme.typography.headlineSmall, color = HColors.Gold, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("סגור", color = HColors.ParchmentDim, fontWeight = FontWeight.Bold) }
            }

            // ---- equipment
            Text("מה יש לך בבית?", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeGear.chips.forEach { (key, label) ->
                    val always = key == HomeGear.BODY
                    FilterChip(
                        selected = always || key in state.gear,
                        onClick = { if (!always) onToggle(key) },
                        label = { Text(label, fontSize = 15.sp) },
                        colors = homeChipColors()
                    )
                }
            }

            if (HomeGear.DUMBBELL in state.gear) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = state.dumbbellKgText,
                        onValueChange = onDumbbellKg,
                        label = { Text("עד כמה ק\"ג?") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(14.dp),
                        colors = kgFieldColors(),
                        modifier = Modifier.width(150.dp)
                    )
                    FilterChip(
                        selected = !state.dumbbellFixed,
                        onClick = { onDumbbellFixed(false) },
                        label = { Text("מתכווננות", fontSize = 14.sp) },
                        colors = homeChipColors()
                    )
                    FilterChip(
                        selected = state.dumbbellFixed,
                        onClick = { onDumbbellFixed(true) },
                        label = { Text("זוג קבוע", fontSize = 14.sp) },
                        colors = homeChipColors()
                    )
                }
                Text(
                    "המשקל הכבד ביותר ליד אחת. בלי מספר, הג'ים ברו לא מגביל.",
                    style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim
                )
            }
            if (HomeGear.KETTLEBELL in state.gear) {
                OutlinedTextField(
                    value = state.kettlebellKgText,
                    onValueChange = onKettlebellKg,
                    label = { Text("קטלבל, כמה ק\"ג?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    colors = kgFieldColors(),
                    modifier = Modifier.width(190.dp)
                )
            }

            // ---- time (optional) and focus
            Text("כמה זמן יש לך?", style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeGear.minuteOptions.forEach { m ->
                    FilterChip(
                        selected = state.minutes == m,
                        onClick = { onMinutes(m) },
                        label = { Text("$m דקות", fontSize = 15.sp) },
                        colors = homeChipColors()
                    )
                }
            }
            Text("על מה עובדים?", style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeGear.focusOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = state.focus == key,
                        onClick = { onFocus(key) },
                        label = { Text(label, fontSize = 15.sp) },
                        colors = homeChipColors()
                    )
                }
            }

            val plan = state.plan
            if (plan == null) {
                Button(
                    onClick = onBuild,
                    enabled = state.loaded && !state.building,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) {
                    Text(if (state.building) "הג'ים ברו בונה..." else com.tamir.hamasa.domain.game.Addr.t("בנה אימון", "בני אימון"), fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
            } else {
                // ---- what was built
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HColors.Ink)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(plan.titleHe), style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, fontWeight = FontWeight.Bold)
                        Text(homeSummaryHe(plan), style = MaterialTheme.typography.titleMedium, color = HColors.Gold, fontWeight = FontWeight.Bold)
                        Text(com.tamir.hamasa.domain.game.Addr.x(plan.coachLineHe), style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
                    }
                }
                plan.exercises.forEachIndexed { i, e ->
                    HomePreviewRow(index = i + 1, e = e, why = why)
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onStart,
                    enabled = !state.starting && plan.exercises.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) {
                    Text(if (state.starting) "פותחים אימון..." else "התחל", fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Text(
                    "שינוי בציוד, בזמן או במיקוד בונה את האימון מחדש.",
                    style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim
                )
            }
        }
    }
}

@Composable
private fun HomePreviewRow(index: Int, e: PlannedExercise, why: suspend (PlannedExercise) -> String) {
    val unit = ExerciseCatalog.byId(e.exerciseId)?.unit ?: "kg"
    val dose = if (unit == "sec") "${e.sets} × ${e.reps} שניות" else "${e.sets} × ${e.reps} חזרות"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Slate),
        border = BorderStroke(2.dp, HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$index", color = HColors.Gold, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(e.nameHe, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dose, style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
                    if (e.weightKg > 0f) Text(homeKgText(e.weightKg), style = MaterialTheme.typography.bodyMedium, color = HColors.Gold, fontWeight = FontWeight.Bold)
                    Text("מנוחה ${e.restSec} שניות", style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim)
                }
                if (e.note.isNotEmpty()) {
                    Text(com.tamir.hamasa.domain.game.Addr.x(e.note), style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim)
                }
                ExerciseHowToBox(exerciseId = e.exerciseId, compact = true)
                CoachWhyLink(title = e.nameHe, load = { why(e) })
            }
            // demo frames when the exercise has them; nothing at all otherwise
            ExerciseAnim(exerciseId = e.exerciseId, modifier = Modifier.width(64.dp).height(64.dp))
        }
    }
}
