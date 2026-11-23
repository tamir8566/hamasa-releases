package com.tamir.hamasa.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.repo.WorkoutRepository
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

private val SaveCardShape = RoundedCornerShape(20.dp)

/**
 * End of a gym workout:
 *  - started from one of his templates and something changed (a swap, an added exercise, other weights or reps):
 *    "לעדכן את האימון?";
 *  - any other gym workout: "שמירה באימונים שלי" with a name.
 * Nothing at all for runs / walks / yoga.
 */
@Composable
fun SaveWorkoutCard(sessionId: Long, popup: Boolean = true) {
    val scope = rememberCoroutineScope()
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var isGym by remember(sessionId) { mutableStateOf(false) }
    var fromTemplate by remember(sessionId) { mutableStateOf(false) }
    var change by remember(sessionId) { mutableStateOf<WorkoutRepository.TemplateChange?>(null) }
    var defaultName by remember(sessionId) { mutableStateOf("") }
    var done by remember(sessionId) { mutableStateOf<String?>(null) }
    var saving by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var handled by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        runCatching {
            val s = AppGraph.db.sessions().byId(sessionId)
            if (s != null) {
                // answered before (saved, updated or kept): coming back to this screen offers nothing again
                handled = AppGraph.workouts.isSaveHandled(sessionId)
                isGym = s.type == SessionType.GYM
                fromTemplate = AppGraph.workouts.templateIdOf(s) != null
                change = AppGraph.workouts.templateChangeOf(sessionId)
                val d = java.time.LocalDate.ofEpochDay(s.dateEpochDay)
                defaultName = "אימון " + d.dayOfMonth + "." + d.monthValue
            }
        }
        loaded = true
    }
    val message = done
    if (message != null) {
        InfoCard(title = "נשמר", body = message)
        return
    }
    if (!loaded || !isGym || handled) return

    val c = change
    if (c != null) {
        val onUpdate: () -> Unit = {
            if (!saving) {
                saving = true
                failed = false
                scope.launch {
                    val ok = runCatching { AppGraph.workouts.applySessionToTemplate(sessionId, c.templateId) }.getOrDefault(false)
                    if (ok) done = "\"${c.templateName}\" עודכן לפי האימון של היום." else failed = true
                    saving = false
                }
            }
        }
        val onKeep: () -> Unit = {
            AppGraph.workouts.markSaveHandled(sessionId)
            change = null
            fromTemplate = true
        }
        if (popup) {
            // his wish (2026-11-08): the question pops up at the end of the workout, like the weigh-in does
            AlertDialog(
                onDismissRequest = { },
                containerColor = HColors.SlateLight,
                titleContentColor = HColors.Gold,
                textContentColor = HColors.Parchment,
                title = { Text("לעדכן את \"${c.templateName}\"?", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TemplateChangeLines(c, failed)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onUpdate, enabled = !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text("עדכון האימון", fontWeight = FontWeight.Black) }
                },
                dismissButton = {
                    TextButton(onClick = onKeep, enabled = !saving) { Text("להשאיר כמו שהיה", color = HColors.Parchment) }
                }
            )
        } else {
            Card(shape = SaveCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("לעדכן את \"${c.templateName}\"?", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
                    TemplateChangeLines(c, failed)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onUpdate, enabled = !saving,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                        ) { Text("עדכון האימון", fontWeight = FontWeight.Black) }
                        OutlinedButton(onClick = onKeep, enabled = !saving, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("להשאיר כמו שהיה", color = HColors.Parchment)
                        }
                    }
                }
            }
        }
        return
    }
    if (fromTemplate) return

    var name by remember(sessionId) { mutableStateOf("") }
    Card(shape = SaveCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("שמירה באימונים שלי", color = HColors.Gold, style = MaterialTheme.typography.titleLarge)
            Text(
                "אותם תרגילים, עם הסטים כמו שעשית היום. אפשר לערוך אחר כך.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("שם האימון") },
                placeholder = { Text(defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (failed) Text("השמירה לא הצליחה. אפשר לנסות שוב.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (!saving) {
                        saving = true
                        failed = false
                        val n = name.trim().ifEmpty { defaultName }
                        scope.launch {
                            val ok = runCatching { AppGraph.workouts.saveSessionAsTemplate(sessionId, n) }.isSuccess
                            if (ok) done = "\"$n\" נשמר באימונים שלי." else failed = true
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("שמירה", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun TemplateChangeLines(c: com.tamir.hamasa.data.repo.WorkoutRepository.TemplateChange, failed: Boolean) {
    c.linesHe.forEach { Text(it, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge) }
    if (failed) Text("העדכון לא נשמר. אפשר לנסות שוב.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
    Text(
        "בעדכון: המשקלים והחזרות נכתבים כמו שעשית היום, ותרגילים חדשים נכנסים במקום הישנים. זמני המנוחה וההערות נשארים כמו שקבעת.",
        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(shape = SaveCardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Moss)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = HColors.Moss, style = MaterialTheme.typography.titleLarge)
            Text(body, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
