package com.tamir.hamasa.ui.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.ui.theme.HColors

private val CardShape = RoundedCornerShape(20.dp)

/** "התרגילים שלי": list, add, edit and delete the user's own exercises. */
@Composable
fun CustomExercisesScreen(onBack: () -> Unit) {
    val vm: CustomExercisesViewModel = viewModel()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()

    // Back closes the form first, then the screen.
    BackHandler(enabled = form != null) { vm.cancel() }

    val openForm = form
    if (openForm != null) {
        ExerciseForm(form = openForm, vm = vm)
    } else {
        ExerciseList(
            exercises = exercises,
            voice = voice,
            onBack = onBack,
            onAdd = { vm.startNew() },
            onEdit = { vm.startEdit(it) },
            onDelete = { vm.askDelete(it) },
            onDismissVoice = { vm.dismissVoice() }
        )
    }

    val del = deleting
    if (del != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.ParchmentDim,
            title = { Text("למחוק את ${del.nameHe}?", fontWeight = FontWeight.Bold) },
            text = { Text("התרגיל יוסר מהתוכניות ומהחלופות. אימונים שכבר נרשמו נשארים.") },
            confirmButton = {
                Button(
                    onClick = { vm.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Danger, contentColor = HColors.Parchment)
                ) { Text("כן, למחוק") }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("ביטול", color = HColors.Gold) } }
        )
    }
}

// --------------------------------------------------------------------------------- list

@Composable
private fun ExerciseList(
    exercises: List<ExerciseEntity>,
    voice: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ExerciseEntity) -> Unit,
    onDelete: (ExerciseEntity) -> Unit,
    onDismissVoice: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("התרגילים שלי", style = MaterialTheme.typography.headlineLarge, color = HColors.Gold, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("חזרה", color = HColors.ParchmentDim, fontWeight = FontWeight.Bold) }
            }
            Text(
                com.tamir.hamasa.domain.game.Addr.t("תרגילים שאתה מוסיף נכנסים לתוכנית של הג'ים ברו לפי השריר שבחרת, ומופיעים בחלופות.", "תרגילים שאת מוסיפה נכנסים לתוכנית של הג'ים ברו לפי השריר שבחרת, ומופיעים בחלופות."),
                style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
            )
        }

        if (voice != null) {
            item { VoiceBanner(text = voice, onDismiss = onDismissVoice) }
        }

        item {
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("הוסף תרגיל", "הוסיפי תרגיל"), fontSize = 20.sp, fontWeight = FontWeight.Black) }
        }

        if (exercises.isEmpty()) {
            item {
                Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Ink)) {
                    Text(
                        "עדיין אין תרגילים משלך. הג'ים ברו עובד עם הקטלוג הקבוע.",
                        Modifier.padding(16.dp),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        items(exercises, key = { it.id }) { e ->
            CustomExerciseCard(e = e, onEdit = { onEdit(e) }, onDelete = { onDelete(e) })
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun VoiceBanner(text: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HColors.Ink)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = HColors.Gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("סגור", color = HColors.ParchmentDim) }
        }
    }
}

@Composable
private fun CustomExerciseCard(e: ExerciseEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (e.preferred) HColors.Gold else HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.nameHe, style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                if (e.preferred) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HColors.Gold)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("מועדף", style = MaterialTheme.typography.labelSmall, color = HColors.Ink, fontWeight = FontWeight.Bold) }
                }
            }
            val meta = buildString {
                append(ExerciseTargets.branchNameHe(e.branch))
                val t = ExerciseTargets.nameOf(e.target)
                if (t.isNotEmpty()) append(" · ").append(t)
                append(" · ").append(ExerciseTargets.equipmentNameHe(e.equipment))
                append(" · ").append(ExerciseTargets.unitNameHe(e.unit))
                if (e.isCompound) append(" · מורכב") else append(" · בידוד")
            }
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
            if (e.description.isNotBlank()) {
                Text(e.description, style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HColors.GoldDeep),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                ) { Text("עריכה", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HColors.Danger),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Danger)
                ) { Text("מחיקה", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// --------------------------------------------------------------------------------- form

@Composable
private fun ExerciseForm(form: CustomExerciseForm, vm: CustomExercisesViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (form.isNew) "תרגיל חדש" else "עריכת תרגיל",
            style = MaterialTheme.typography.headlineLarge, color = HColors.Gold
        )

        OutlinedTextField(
            value = form.nameHe,
            onValueChange = { v -> vm.update { it.copy(nameHe = v.take(60)) } },
            label = { Text("שם התרגיל") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = form.description,
            onValueChange = { v -> vm.update { it.copy(description = v.take(600)) } },
            label = { Text("איך מבצעים (תיאור חופשי)") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("קבוצת שריר")
        PickerField(
            label = "קבוצה",
            value = ExerciseTargets.branchNameHe(form.branch),
            options = Branch.values().map { it.name to ExerciseTargets.branchNameHe(it) },
            onPick = { key -> vm.setBranch(Branch.valueOf(key)) }
        )
        PickerField(
            label = "שריר מדויק",
            value = ExerciseTargets.nameOf(form.target),
            options = ExerciseTargets.forBranch(form.branch).map { it.key to it.nameHe },
            onPick = { key -> vm.setTarget(key) }
        )
        Text(
            "השריר המדויק קובע לאיזו משבצת בתוכנית התרגיל נכנס, ולאילו תרגילים הוא יוצע כחלופה.",
            style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
        )

        SectionTitle("ציוד ומדידה")
        PickerField(
            label = "ציוד",
            value = ExerciseTargets.equipmentNameHe(form.equipment),
            options = ExerciseTargets.equipmentKeys.map { it to ExerciseTargets.equipmentNameHe(it) },
            onPick = { key -> vm.update { it.copy(equipment = key) } }
        )
        PickerField(
            label = "יחידה",
            value = ExerciseTargets.unitNameHe(form.unit),
            options = ExerciseTargets.unitKeys.map { it to ExerciseTargets.unitNameHe(it) },
            onPick = { key -> vm.update { it.copy(unit = key) } }
        )

        SwitchRow(
            title = "תרגיל מורכב",
            hint = "מורכב = כמה מפרקים, מנוחה ארוכה, המשחק לא מוסיף לו חזרות. בידוד = שריר אחד.",
            checked = form.isCompound,
            onChange = { on -> vm.update { it.copy(isCompound = on) } }
        )
        SwitchRow(
            title = "מועדף",
            hint = "מועדף נכנס ראשון במשבצת שלו, לפני תרגילי הקטלוג. בלי זה הוא מופיע בחלופות וברוטציה בין בלוקים.",
            checked = form.preferred,
            onChange = { on -> vm.update { it.copy(preferred = on) } }
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.save() },
            enabled = form.canSave,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CardShape,
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
        ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי"), fontSize = 20.sp, fontWeight = FontWeight.Black) }
        OutlinedButton(
            onClick = { vm.cancel() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CardShape,
            border = BorderStroke(1.dp, HColors.GoldDeep),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.ParchmentDim)
        ) { Text("ביטול", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
}

/** A labelled dropdown: a button that shows the current value and opens a menu of [options] (key to Hebrew name). */
@Composable
private fun PickerField(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
            ) { Text(value, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(key) })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
