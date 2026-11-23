package com.tamir.hamasa.ui.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.ui.common.ExerciseAnim
import com.tamir.hamasa.ui.theme.HColors

private val CardShape = RoundedCornerShape(20.dp)


/** Editor for one of the user's own workouts. [templateId] -1 (or 0) = new. [onDone] leaves the screen. */
@Composable
fun TemplateEditorScreen(templateId: Long, onDone: () -> Unit) {
    val vm: TemplateEditorViewModel = viewModel(key = "template_editor_$templateId")
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(templateId) { vm.load(templateId) }

    if (state.loading) {
        Box(Modifier.fillMaxSize().background(HColors.Slate), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = HColors.Gold)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = state.nameHe,
                onValueChange = { vm.setName(it) },
                label = { Text("שם האימון") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.notes,
                onValueChange = { vm.setNotes(it) },
                label = { Text("הערה (לא חובה)") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("תרגילים", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                Text("${state.items.size}", style = MaterialTheme.typography.titleLarge, color = HColors.Gold)
            }
            if (state.items.isEmpty()) {
                Text(
                    com.tamir.hamasa.domain.game.Addr.t("עדיין אין תרגילים. הוסף תרגיל מהקטלוג או מהתרגילים שלך.", "עדיין אין תרגילים. הוסיפי תרגיל מהקטלוג או מהתרגילים שלך."),
                    style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
                )
            }
        }

        items(state.items, key = { it.key }) { item ->
            val index = state.items.indexOfFirst { it.key == item.key }
            ItemCard(
                item = item,
                isFirst = index <= 0,
                isLast = index >= state.items.size - 1,
                onKg = { rowId, v -> vm.setKg(item.key, rowId, v) },
                onReps = { rowId, v -> vm.setReps(item.key, rowId, v) },
                onAddRow = { vm.addRow(item.key) },
                onRemoveRow = { rowId -> vm.removeRow(item.key, rowId) },
                onRest = { d -> vm.stepRest(item.key, d) },
                onUp = { vm.moveUp(item.key) },
                onDown = { vm.moveDown(item.key) },
                onRemove = { vm.remove(item.key) }
            )
        }

        item {
            OutlinedButton(
                onClick = { vm.openPicker() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CardShape,
                border = BorderStroke(2.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
            ) { Text("הוסף תרגיל", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.save() },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(if (state.saving) "שומר..." else "שמירה", fontSize = 20.sp, fontWeight = FontWeight.Black) }
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
                shape = CardShape,
                border = BorderStroke(1.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.ParchmentDim)
            ) { Text("ביטול", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.pickerOpen) {
        ExercisePickerDialog(
            exercises = state.exercises,
            filter = state.filter,
            onFilter = { vm.setFilter(it) },
            onPick = { vm.addExercise(it) },
            onClose = { vm.closePicker() }
        )
    }

    val voice = state.savedVoice
    if (voice != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissSaved(); onDone() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Gold,
            textContentColor = HColors.Parchment,
            title = { Text("נשמר", fontWeight = FontWeight.Black) },
            text = { Text(voice, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Button(
                    onClick = { vm.dismissSaved(); onDone() },
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("קיבלתי", fontWeight = FontWeight.Black) }
            }
        )
    }
}

// --------------------------------------------------------------------------------- item card

@Composable
private fun ItemCard(
    item: EditorItem,
    isFirst: Boolean,
    isLast: Boolean,
    onKg: (Long, String) -> Unit,
    onReps: (Long, String) -> Unit,
    onAddRow: () -> Unit,
    onRemoveRow: (Long) -> Unit,
    onRest: (Int) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.nameHe, style = MaterialTheme.typography.titleLarge, color = HColors.Gold)
                    val meta = buildString {
                        append(ExerciseTargets.branchNameHe(item.branch))
                        if (item.isCompound) append(" · מורכב")
                    }
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                }
                ExerciseAnim(exerciseId = item.exerciseId, modifier = Modifier.height(64.dp).width(64.dp))
            }

            SetTable(item = item, onKg = onKg, onReps = onReps, onRemoveRow = onRemoveRow)
            OutlinedButton(
                onClick = onAddRow,
                enabled = item.rows.size < 12,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(2.dp, HColors.GoldDeep),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
            ) { Text("+ הוספת סט", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Stepper(label = "מנוחה", value = "${item.restSec} שניות", onMinus = { onRest(-15) }, onPlus = { onRest(15) })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(text = "למעלה", enabled = !isFirst, color = HColors.Gold, onClick = onUp)
                SmallAction(text = "למטה", enabled = !isLast, color = HColors.Gold, onClick = onDown)
                Spacer(Modifier.weight(1f))
                SmallAction(text = "הסר", enabled = true, color = HColors.Danger, onClick = onRemove)
            }
        }
    }
}

// --------------------------------------------------------------------------------- per-set table

private val CellShape = RoundedCornerShape(12.dp)
private val CellHeight = 52.dp
private val SetColWidth = 34.dp
private val DeleteColWidth = 36.dp

/** "35 × 12", "12" (no weight), or "—" when there is nothing for this set index. */
private fun previousText(item: EditorItem, index: Int): String {
    val p = item.previous.getOrNull(index) ?: return "—"
    return if (item.unit == "kg" && p.kg > 0f) "${kgInputText(p.kg)} × ${p.reps}" else "${p.reps}"
}

/** One row per set: סט | הקודם | ק"ג | חזרות, and a small × to delete. RTL: סט is the rightmost column. */
@Composable
private fun SetTable(
    item: EditorItem,
    onKg: (Long, String) -> Unit,
    onReps: (Long, String) -> Unit,
    onRemoveRow: (Long) -> Unit
) {
    val isKg = item.unit == "kg"
    val repsLabel = if (item.unit == "sec") "שניות" else "חזרות"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("סט", Modifier.width(SetColWidth))
            HeaderCell("הקודם", Modifier.weight(1.1f))
            HeaderCell("ק\"ג", Modifier.weight(1f))
            HeaderCell(repsLabel, Modifier.weight(1f))
            Spacer(Modifier.width(DeleteColWidth))
        }
        item.rows.forEachIndexed { index, row ->
            key(row.id) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        modifier = Modifier.width(SetColWidth),
                        textAlign = TextAlign.Center,
                        color = HColors.Gold,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    Box(
                        Modifier
                            .weight(1.1f)
                            .height(CellHeight)
                            .clip(CellShape)
                            .background(HColors.Ink.copy(alpha = 0.55f))
                            .border(1.dp, HColors.GoldDeep.copy(alpha = 0.35f), CellShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(previousText(item, index), color = HColors.ParchmentDim, fontSize = 14.sp, maxLines = 1, softWrap = false)
                    }
                    if (isKg) {
                        NumberField(
                            value = row.kgText,
                            onValueChange = { onKg(row.id, it) },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(CellHeight)
                                .clip(CellShape)
                                .background(HColors.Ink.copy(alpha = 0.55f))
                                .border(1.dp, HColors.GoldDeep.copy(alpha = 0.25f), CellShape),
                            contentAlignment = Alignment.Center
                        ) { Text("—", color = HColors.ParchmentDim.copy(alpha = 0.6f), fontSize = 18.sp) }
                    }
                    NumberField(
                        value = row.repsText,
                        onValueChange = { onReps(row.id, it) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    val canDelete = item.rows.size > 1
                    IconButton(
                        onClick = { onRemoveRow(row.id) },
                        enabled = canDelete,
                        modifier = Modifier.size(DeleteColWidth)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "מחק סט",
                            tint = if (canDelete) HColors.Ember else HColors.ParchmentDim.copy(alpha = 0.25f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        color = HColors.Parchment,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = CellShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = HColors.Gold,
            unfocusedTextColor = HColors.Gold,
            focusedContainerColor = HColors.Ink,
            unfocusedContainerColor = HColors.Ink,
            focusedBorderColor = HColors.Gold,
            unfocusedBorderColor = HColors.GoldDeep,
            cursorColor = HColors.Gold
        ),
        modifier = modifier.heightIn(min = CellHeight)
    )
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = HColors.ParchmentDim, modifier = Modifier.width(72.dp))
        StepButton("−", onMinus)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = HColors.Parchment,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp),
        border = BorderStroke(1.dp, HColors.GoldDeep),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
    ) { Text(label, fontWeight = FontWeight.Black, fontSize = 18.sp) }
}

@Composable
private fun SmallAction(text: String, enabled: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(36.dp),
        border = BorderStroke(1.dp, if (enabled) color else HColors.ParchmentDim.copy(alpha = 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = HColors.ParchmentDim.copy(alpha = 0.4f))
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

// --------------------------------------------------------------------------------- picker

/** Searchable picker over catalog + custom exercises, grouped by branch in Hebrew. */
@Composable
private fun ExercisePickerDialog(
    exercises: List<ExerciseEntity>,
    filter: String,
    onFilter: (String) -> Unit,
    onPick: (ExerciseEntity) -> Unit,
    onClose: () -> Unit
) {
    val q = filter.trim()
    val filtered = remember(exercises, q) {
        if (q.isEmpty()) exercises else exercises.filter { it.nameHe.contains(q) || it.id.contains(q, ignoreCase = true) }
    }
    val groups: List<Pair<Branch, List<ExerciseEntity>>> = remember(filtered) {
        Branch.values().mapNotNull { b ->
            val list = filtered.filter { it.branch == b }
            if (list.isEmpty()) null else b to list
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text(com.tamir.hamasa.domain.game.Addr.t("בחר תרגיל", "בחרי תרגיל"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilter,
                    label = { Text("חיפוש") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text("לא נמצא תרגיל כזה.", color = HColors.ParchmentDim)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().height(380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { (branch, list) ->
                            item(key = "h_" + branch.name) {
                                Text(
                                    ExerciseTargets.branchNameHe(branch),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = HColors.Gold,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            items(list, key = { it.id }) { e ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(HColors.Slate)
                                        .clickable { onPick(e) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ExerciseAnim(exerciseId = e.id, modifier = Modifier.height(56.dp).width(56.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.nameHe, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
                                        val sub = buildString {
                                            append(ExerciseTargets.equipmentNameHe(e.equipment))
                                            append(" · ").append(ExerciseTargets.unitNameHe(e.unit))
                                            if (e.isCustom) append(" · שלי")
                                        }
                                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
                                    }
                                    Text("הוסף", color = HColors.Gold, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("סגור", color = HColors.Gold) } }
    )
}
