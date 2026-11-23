package com.tamir.hamasa.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** State of the photo / text estimate flow (stage 2 F). */
sealed class PhotoPhase {
    object Idle : PhotoPhase()
    data class Loading(val line: String) : PhotoPhase()
    /** Gemini answered. [fromPhoto] = a photo (false = a text description). [aiKcal] = the AI's own total. */
    data class Review(
        val items: List<FoodItem>,
        val aiKcal: Int,
        val noteHe: String,
        val fromPhoto: Boolean,
        val descriptionHe: String,
        /** Optional dialog title (e.g. for a suggested meal); null = by source. */
        val titleHe: String? = null
    ) : PhotoPhase()
}

internal val FoodCardShape = RoundedCornerShape(20.dp)

internal fun fmtG(v: Float): String {
    val r = (v * 10f).roundToInt() / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

private fun digits(s: String, max: Int = 5) = s.filter { it.isDigit() }.take(max)
private fun decimal(s: String): String {
    val f = s.replace(',', '.').filter { it.isDigit() || it == '.' }
    val dot = f.indexOf('.')
    return (if (dot < 0) f else f.substring(0, dot + 1) + f.substring(dot + 1).replace(".", "")).take(6)
}

// ---------------------------------------------------------------- capture buttons

@Composable
fun MealCaptureButtons(onCamera: () -> Unit, onGallery: () -> Unit, onDescribe: () -> Unit, onManual: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCamera,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = FoodCardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { CaptureLabel(Icons.Filled.PhotoCamera, com.tamir.hamasa.domain.game.Addr.t("צלם ארוחה", "צלמי ארוחה"), bold = true) }
            OutlinedButton(
                onClick = onGallery,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = FoodCardShape,
                border = BorderStroke(2.dp, HColors.GoldDeep)
            ) { CaptureLabel(Icons.Filled.PhotoLibrary, "מהגלריה", color = HColors.Gold) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDescribe,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = FoodCardShape,
                border = BorderStroke(2.dp, HColors.SlateLight)
            ) { CaptureLabel(Icons.Filled.TextFields, com.tamir.hamasa.domain.game.Addr.t("תאר במילים", "תארי במילים"), color = HColors.Parchment) }
            OutlinedButton(
                onClick = onManual,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = FoodCardShape,
                border = BorderStroke(2.dp, HColors.SlateLight)
            ) { CaptureLabel(Icons.Filled.Edit, "הוספה ידנית", color = HColors.Parchment) }
        }
    }
}

@Composable
private fun CaptureLabel(icon: ImageVector, text: String, bold: Boolean = false, color: androidx.compose.ui.graphics.Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (color != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        if (color != null) Text(text, color = color, fontWeight = if (bold) FontWeight.Black else FontWeight.Bold)
        else Text(text, fontWeight = if (bold) FontWeight.Black else FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- loading

@Composable
fun MealLoadingDialog(line: String, onCancel: (() -> Unit)? = null) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(
            shape = FoodCardShape,
            colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
            border = BorderStroke(3.dp, HColors.GoldDeep)
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = if (onCancel != null) 8.dp else 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = HColors.Gold, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(line, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("ביטול", color = HColors.Gold) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- describe in text

@Composable
fun DescribeMealDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("מה אכלת") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("במילים פשוטות. כמויות עוזרות.", color = HColors.ParchmentDim)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) },
                    placeholder = { Text("שתי פיתות עם חומוס וסלט") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text.trim()) }, enabled = text.isNotBlank()) { Text(com.tamir.hamasa.domain.game.Addr.t("הערך", "הערכי")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

// ---------------------------------------------------------------- review sheet

/** [calcName]: the name the numbers belong to; a different name in the card offers "חישוב מחדש". [calc] bumps on a recalculation. */
internal data class ReviewRow(val id: Int, val item: FoodItem, val calcName: String = item.nameHe, val calc: Int = 0)

/**
 * Full-screen review of the estimate: edit names, portions (macros rescale), kcal; delete / add items; save.
 * [onSave] gets the items, the meal name and whether to keep it as a saved meal ("ארוחה קבועה").
 */
@Composable
fun MealReviewDialog(review: PhotoPhase.Review, onSave: (List<FoodItem>, String, Boolean) -> Unit, onCancel: () -> Unit) {
    var keepFixed by remember(review) { mutableStateOf(false) }
    val rows = remember(review) { mutableStateListOf<ReviewRow>().apply { review.items.forEachIndexed { i, x -> add(ReviewRow(i, x)) } } }
    var nextId by remember(review) { mutableStateOf(review.items.size) }
    var desc by remember(review) {
        mutableStateOf(review.descriptionHe.ifBlank { review.items.joinToString(", ") { it.nameHe } }.take(120))
    }
    var adding by remember(review) { mutableStateOf(false) }
    val current = rows.map { it.item }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Some phones report no navigation-bar inset inside a dialog window: keep at least 96 dp clear at the bottom.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomClear = if (navBottom + 40.dp > 96.dp) navBottom + 40.dp else 96.dp
        Surface(Modifier.fillMaxSize(), color = HColors.Slate) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomClear), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(review.titleHe ?: if (review.fromPhoto) "מה יש בצלחת" else "מה יש בתיאור", style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
                if (review.noteHe.isNotBlank()) Text(com.tamir.hamasa.domain.game.Addr.x(review.noteHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                Card(
                    shape = FoodCardShape,
                    colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                    border = BorderStroke(2.dp, HColors.GoldDeep)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${Nutrition.sumKcal(current)} קלוריות", style = MaterialTheme.typography.titleLarge, color = HColors.Gold)
                        Text(
                            "חלבון ${fmtG(Nutrition.sumProtein(current))} · פחמימות ${fmtG(Nutrition.sumCarbs(current))} · שומן ${fmtG(Nutrition.sumFat(current))} גרם",
                            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                        )
                        if (review.aiKcal > 0 && review.aiKcal != Nutrition.sumKcal(current)) {
                            Text("ההערכה המקורית: ${review.aiKcal}", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.id }) { row ->
                        ReviewItemCard(
                            row = row,
                            onChange = { changed ->
                                val i = rows.indexOfFirst { it.id == row.id }
                                if (i >= 0) rows[i] = rows[i].copy(item = changed)
                            },
                            onRecalculated = { fresh ->
                                val i = rows.indexOfFirst { it.id == row.id }
                                if (i >= 0) rows[i] = rows[i].copy(item = fresh, calcName = fresh.nameHe, calc = rows[i].calc + 1)
                            },
                            onDelete = { rows.removeAll { it.id == row.id } }
                        )
                    }
                    item {
                        if (adding) {
                            AddItemCard(
                                onAdd = { added -> rows.add(ReviewRow(nextId, added)); nextId += 1; adding = false },
                                onCancel = { adding = false }
                            )
                        } else {
                            TextButton(onClick = { adding = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = HColors.Gold)
                                Spacer(Modifier.width(4.dp))
                                Text(com.tamir.hamasa.domain.game.Addr.t("הוסף פריט", "הוסיפי פריט"), color = HColors.Gold)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it.take(120) }, label = { Text("שם הארוחה") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(
                    Modifier.fillMaxWidth().clickable { keepFixed = !keepFixed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = keepFixed, onCheckedChange = { keepFixed = it },
                        colors = CheckboxDefaults.colors(checkedColor = HColors.Gold, checkmarkColor = HColors.Ink, uncheckedColor = HColors.ParchmentDim)
                    )
                    Text(com.tamir.hamasa.domain.game.Addr.t("שמור כארוחה קבועה", "שמרי כארוחה קבועה"), color = HColors.Parchment)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(current, desc.trim(), keepFixed) },
                        enabled = current.isNotEmpty() && desc.isNotBlank(),
                        modifier = Modifier.weight(2f).height(52.dp),
                        shape = FoodCardShape,
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור ארוחה", "שמרי ארוחה"), fontWeight = FontWeight.Black) }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = FoodCardShape,
                        border = BorderStroke(2.dp, HColors.SlateLight)
                    ) { Text("ביטול", color = HColors.Parchment) }
                }
            }
        }
    }
}

@Composable
internal fun ReviewItemCard(row: ReviewRow, onChange: (FoodItem) -> Unit, onRecalculated: (FoodItem) -> Unit, onDelete: () -> Unit) {
    val item = row.item
    // the kcal text resets when the portion changes (the stepper rescales kcal) or after "חישוב מחדש"
    var kcalText by remember(row.id, item.grams, row.calc) { mutableStateOf(item.kcal.toString()) }
    val scope = rememberCoroutineScope()
    var recalcBusy by remember(row.id) { mutableStateOf(false) }
    var recalcFailed by remember(row.id) { mutableStateOf(false) }
    val renamed = item.nameHe.trim().isNotEmpty() && item.nameHe.trim() != row.calcName.trim()
    val step = if (item.grams >= 200) 25 else 10
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.nameHe, onValueChange = { onChange(item.copy(nameHe = it.take(40))) },
                    label = { Text("מאכל") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = com.tamir.hamasa.domain.game.Addr.t("מחק פריט", "מחקי פריט"), tint = HColors.Danger) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onChange(item.withGrams(item.grams - step)) }, enabled = item.grams > step) {
                    Icon(Icons.Filled.Remove, contentDescription = "פחות", tint = HColors.Gold)
                }
                Text("${item.grams} גרם", style = MaterialTheme.typography.titleMedium, color = HColors.Parchment)
                IconButton(onClick = { onChange(item.withGrams(item.grams + step)) }) {
                    Icon(Icons.Filled.Add, contentDescription = "יותר", tint = HColors.Gold)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = kcalText,
                    onValueChange = { v -> kcalText = digits(v); onChange(item.copy(kcal = kcalText.toIntOrNull() ?: 0)) },
                    label = { Text("קלוריות") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "חלבון ${fmtG(item.proteinG)} · פחמימות ${fmtG(item.carbsG)} · שומן ${fmtG(item.fatG)}",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            if (item.confidence < 0.5f) Text(com.tamir.hamasa.domain.game.CharacterNames.g("כאן אני פחות בטוח. כדאי לבדוק."), color = HColors.Ember, style = MaterialTheme.typography.labelSmall)
            // the name changed: the numbers still belong to the old name -> recalculate for the new one
            if (renamed || recalcBusy) {
                Button(
                    onClick = {
                        if (!recalcBusy) {
                            recalcBusy = true
                            recalcFailed = false
                            val name = item.nameHe.trim()
                            val grams = item.grams
                            scope.launch {
                                val fresh = runCatching { MealEstimator.forItem(name, grams) }.getOrNull()
                                if (fresh != null) onRecalculated(fresh) else recalcFailed = true
                                recalcBusy = false
                            }
                        }
                    },
                    enabled = !recalcBusy,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = FoodCardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text(if (recalcBusy) "מחשב..." else "חישוב מחדש של הקלוריות", fontWeight = FontWeight.Bold) }
                if (recalcFailed) Text("החישוב לא הצליח. אפשר לנסות שוב, או לתקן את המספרים ביד.", color = HColors.Ember, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AddItemCard(onAdd: (FoodItem) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    val k = kcal.toIntOrNull()
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("מאכל") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = grams, onValueChange = { grams = digits(it, 4) }, label = { Text("גרם") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = kcal, onValueChange = { kcal = digits(it) }, label = { Text("קלוריות") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
            }
            MacroFields(p, { p = it }, c, { c = it }, f, { f = it })
            Row {
                TextButton(onClick = {
                    if (name.isNotBlank() && k != null) onAdd(
                        FoodItem(
                            nameHe = name.trim(), grams = grams.toIntOrNull() ?: 0, kcal = k,
                            proteinG = p.toFloatOrNull() ?: 0f, carbsG = c.toFloatOrNull() ?: 0f, fatG = f.toFloatOrNull() ?: 0f,
                            confidence = 1f
                        )
                    )
                }, enabled = name.isNotBlank() && k != null) { Text(com.tamir.hamasa.domain.game.Addr.t("הוסף", "הוסיפי"), color = HColors.Gold) }
                TextButton(onClick = onCancel) { Text("ביטול", color = HColors.ParchmentDim) }
            }
        }
    }
}

/** Optional protein / carbs / fat grams, side by side. */
@Composable
fun MacroFields(
    protein: String, onProtein: (String) -> Unit,
    carbs: String, onCarbs: (String) -> Unit,
    fat: String, onFat: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = protein, onValueChange = { onProtein(decimal(it)) }, label = { Text("חלבון") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = carbs, onValueChange = { onCarbs(decimal(it)) }, label = { Text("פחמימות") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = fat, onValueChange = { onFat(decimal(it)) }, label = { Text("שומן") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------- manual add / edit

/** Values of the manual meal form. Macros are optional (blank = 0). */
data class MealForm(val description: String, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float)

/**
 * Manual add (also the fallback when the estimate failed: [reason] says why) and edit of an existing meal
 * ([onDelete] != null). Starts empty unless [initial] is given.
 */
@Composable
fun MealFormDialog(
    title: String,
    reason: String?,
    initial: MealForm?,
    onSave: (MealForm) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** Edit mode only: keeps the meal (as stored) as a saved meal. */
    onSaveFixed: (() -> Unit)? = null,
    /** Edit mode only: opens the item editor (calories and macros of every item). */
    onEditItems: (() -> Unit)? = null
) {
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    // add mode with a prefilled description only (kcal 0): the kcal field starts empty
    var kcal by remember { mutableStateOf(initial?.kcal?.takeIf { it > 0 || onDelete != null }?.toString() ?: "") }
    var p by remember { mutableStateOf(initial?.proteinG?.takeIf { it > 0f }?.let { fmtG(it) } ?: "") }
    var c by remember { mutableStateOf(initial?.carbsG?.takeIf { it > 0f }?.let { fmtG(it) } ?: "") }
    var f by remember { mutableStateOf(initial?.fatG?.takeIf { it > 0f }?.let { fmtG(it) } ?: "") }
    val k = kcal.toIntOrNull()
    val ok = k != null && desc.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reason != null) Text(reason, color = HColors.Ember, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = desc, onValueChange = { desc = it.take(120) }, label = { Text("מה אכלת") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = kcal, onValueChange = { kcal = digits(it) }, label = { Text("קלוריות") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                Text("גרמים, לא חובה", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                MacroFields(p, { p = it }, c, { c = it }, f, { f = it })
                if (onEditItems != null) TextButton(onClick = onEditItems) { Text("עריכת פריטים", color = HColors.Gold, fontWeight = FontWeight.Bold) }
                if (onSaveFixed != null) TextButton(onClick = onSaveFixed) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור כארוחה קבועה", "שמרי כארוחה קבועה"), color = HColors.Gold) }
                if (onDelete != null) TextButton(onClick = onDelete) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק ארוחה", "מחקי ארוחה"), color = HColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ok) onSave(
                        MealForm(
                            desc.trim(), (k ?: 0).coerceAtLeast(0),
                            p.toFloatOrNull() ?: 0f, c.toFloatOrNull() ?: 0f, f.toFloatOrNull() ?: 0f
                        )
                    )
                },
                enabled = ok
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

/** Goals editor: blank = automatic. */
@Composable
fun MacroGoalsDialog(
    auto: com.tamir.hamasa.domain.food.MacroTargets,
    protein: Int, carbs: Int, fat: Int,
    onSave: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var p by remember { mutableStateOf(if (protein > 0) protein.toString() else "") }
    var c by remember { mutableStateOf(if (carbs > 0) carbs.toString() else "") }
    var f by remember { mutableStateOf(if (fat > 0) fat.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("יעדי היום בגרמים") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("שדה ריק הוא חישוב אוטומטי מהמשקל ומיעד הקלוריות.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                GoalField("חלבון", p, auto.proteinG) { p = it }
                GoalField("פחמימות", c, auto.carbsG) { c = it }
                GoalField("שומן", f, auto.fatG) { f = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(p.toIntOrNull() ?: 0, c.toIntOrNull() ?: 0, f.toIntOrNull() ?: 0) }) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun GoalField(label: String, value: String, autoValue: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(digits(it, 4)) }, label = { Text(label) }, singleLine = true,
        placeholder = { Text("אוטומטי: $autoValue") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
    )
}
