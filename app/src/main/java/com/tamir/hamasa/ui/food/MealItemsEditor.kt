package com.tamir.hamasa.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Editor of the items of a meal that was already logged: name, grams, kcal and macros of every item, delete / add items.
 * The meal totals are always the sum of the items (saved by FoodViewModel.updateItems).
 */

private const val ITEM_KCAL_MAX = 5000f
private const val ITEM_GRAMS_MAX = 5000f
private const val ITEM_MACRO_MAX = 500f

/** One editable item: every number is kept as typed (so "12," while typing stays), parsed on use. */
private data class MealEditRow(
    val id: Int,
    val name: String,
    val grams: String,
    val kcal: String,
    val protein: String,
    val carbs: String,
    val fat: String,
    val confidence: Float,
    /** The name the numbers were calculated for; a different name shows "חישוב מחדש של הקלוריות". */
    val calcName: String = "",
    /** Baseline for scaling by grams (0 = none yet): the numbers that belonged to [baseGrams]. */
    val baseGrams: Float = 0f,
    val baseKcal: Float = 0f,
    val baseP: Float = 0f,
    val baseC: Float = 0f,
    val baseF: Float = 0f
)

/**
 * The grams field changed: kcal and macros follow in proportion (from the baseline, so typing "479" digit by digit
 * does not pile up rounding). Typing in kcal / macros directly resets the baseline.
 */
private fun MealEditRow.withGramsText(text: String): MealEditRow {
    val g = mealEditNumText(text)
    val newG = mealEditNum(g)
    val haveBase = baseGrams > 0f
    val bg = if (haveBase) baseGrams else (mealEditNum(grams) ?: 0f)
    val bk = if (haveBase) baseKcal else (mealEditNum(kcal) ?: 0f)
    val bp = if (haveBase) baseP else (mealEditNum(protein) ?: 0f)
    val bc = if (haveBase) baseC else (mealEditNum(carbs) ?: 0f)
    val bf = if (haveBase) baseF else (mealEditNum(fat) ?: 0f)
    val r = copy(grams = g, baseGrams = bg, baseKcal = bk, baseP = bp, baseC = bc, baseF = bf)
    if (bg <= 0f || newG == null || newG <= 0f) return r
    val k = newG / bg
    return r.copy(
        kcal = (bk * k).roundToInt().toString(),
        protein = if (bp > 0f) fmtG(mealEditRound1(bp * k)) else "",
        carbs = if (bc > 0f) fmtG(mealEditRound1(bc * k)) else "",
        fat = if (bf > 0f) fmtG(mealEditRound1(bf * k)) else ""
    )
}

/** Keeps digits and one kind of separator characters; "12,5" and "12.5" are both accepted. */
private fun mealEditNumText(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }.take(7)

/** Parsed number, or null when blank / not a number. */
private fun mealEditNum(s: String): Float? {
    val t = s.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val v = t.toFloatOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite()) null else v
}

/** Required number within 0..max. */
private fun mealEditRequired(s: String, max: Float): Float? = mealEditNum(s)?.takeIf { it >= 0f && it <= max }

/** Optional number: blank = 0; otherwise within 0..max (null = invalid). */
private fun mealEditOptional(s: String, max: Float): Float? =
    if (s.isBlank()) 0f else mealEditNum(s)?.takeIf { it >= 0f && it <= max }

private fun mealEditRound1(v: Float): Float = (v * 10f).roundToInt() / 10f

private fun MealEditRow.nameOk(): Boolean = name.isNotBlank()
private fun MealEditRow.kcalOk(): Boolean = mealEditRequired(kcal, ITEM_KCAL_MAX) != null
private fun MealEditRow.gramsOk(): Boolean = mealEditOptional(grams, ITEM_GRAMS_MAX) != null
private fun MealEditRow.proteinOk(): Boolean = mealEditOptional(protein, ITEM_MACRO_MAX) != null
private fun MealEditRow.carbsOk(): Boolean = mealEditOptional(carbs, ITEM_MACRO_MAX) != null
private fun MealEditRow.fatOk(): Boolean = mealEditOptional(fat, ITEM_MACRO_MAX) != null
private fun MealEditRow.valid(): Boolean = nameOk() && kcalOk() && gramsOk() && proteinOk() && carbsOk() && fatOk()

/** The item as it would be saved; invalid numbers count as 0 (used for the live totals). */
private fun MealEditRow.toItem(): FoodItem = FoodItem(
    nameHe = name.trim().take(40),
    grams = (mealEditOptional(grams, ITEM_GRAMS_MAX) ?: 0f).roundToInt(),
    kcal = (mealEditRequired(kcal, ITEM_KCAL_MAX) ?: 0f).roundToInt(),
    proteinG = mealEditRound1(mealEditOptional(protein, ITEM_MACRO_MAX) ?: 0f),
    carbsG = mealEditRound1(mealEditOptional(carbs, ITEM_MACRO_MAX) ?: 0f),
    fatG = mealEditRound1(mealEditOptional(fat, ITEM_MACRO_MAX) ?: 0f),
    confidence = confidence
)

private fun mealEditRowOf(id: Int, x: FoodItem): MealEditRow = MealEditRow(
    id = id,
    name = x.nameHe,
    grams = if (x.grams > 0) x.grams.toString() else "",
    kcal = x.kcal.toString(),
    protein = if (x.proteinG > 0f) fmtG(x.proteinG) else "",
    carbs = if (x.carbsG > 0f) fmtG(x.carbsG) else "",
    fat = if (x.fatG > 0f) fmtG(x.fatG) else "",
    confidence = x.confidence,
    calcName = x.nameHe
)

/** The items of [m]; a meal without items (typed by hand) gets one item built from its totals and description. */
internal fun mealItemsForEdit(m: MealEntity): List<FoodItem> {
    val items = MealEstimator.decodeItems(m.itemsJson)
    if (items.isNotEmpty()) return items
    return listOf(
        FoodItem(
            nameHe = m.description.trim().ifBlank { "ארוחה" }.take(40),
            grams = 0, kcal = m.kcal.coerceAtLeast(0),
            proteinG = m.proteinG, carbsG = m.carbsG, fatG = m.fatG, confidence = 1f
        )
    )
}

/**
 * Full-screen editor of a logged meal's items. [onSave] gets the items (at least one, all valid) and the meal name.
 */
@Composable
fun MealItemsEditor(meal: MealEntity, onSave: (List<FoodItem>, String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MealItemsEditorBody(meal, onSave, onDismiss)
    }
}

/** The editor's content without the dialog window (tests render it alone: Compose dialogs never idle under Robolectric). */
@Composable
internal fun MealItemsEditorBody(meal: MealEntity, onSave: (List<FoodItem>, String) -> Unit, onDismiss: () -> Unit) {
    val initial = remember(meal.id) { mealItemsForEdit(meal) }
    val rows = remember(meal.id) { mutableStateListOf<MealEditRow>().apply { initial.forEachIndexed { i, x -> add(mealEditRowOf(i, x)) } } }
    var nextId by remember(meal.id) { mutableStateOf(initial.size) }
    var desc by remember(meal.id) { mutableStateOf(meal.description) }
    // a double tap on "שמירה" saves once
    var saving by remember(meal.id) { mutableStateOf(false) }

    val current = rows.map { it.toItem() }
    val total = Nutrition.sumKcal(current)
    val allValid = rows.isNotEmpty() && rows.all { it.valid() } && desc.isNotBlank()

    run {
        // same bottom clearance as the review screen (some phones report no navigation-bar inset inside a dialog)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomClear = if (navBottom + 40.dp > 96.dp) navBottom + 40.dp else 96.dp
        Surface(Modifier.fillMaxSize(), color = HColors.Slate) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomClear),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("פריטים בארוחה", style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
                Text("כל פריט בנפרד. הסכום מתעדכן לבד.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                Card(
                    shape = FoodCardShape,
                    colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
                    border = BorderStroke(2.dp, HColors.GoldDeep)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("$total קלוריות", style = MaterialTheme.typography.titleLarge, color = HColors.Gold, fontWeight = FontWeight.Bold)
                        Text(
                            "חלבון ${fmtG(Nutrition.sumProtein(current))} · פחמימות ${fmtG(Nutrition.sumCarbs(current))} · שומן ${fmtG(Nutrition.sumFat(current))} גרם",
                            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
                        )
                        if (total != meal.kcal) {
                            Text("לפני התיקון: ${meal.kcal}", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rows.forEachIndexed { index, row ->
                        androidx.compose.runtime.key(row.id) {
                            MealEditItemCard(
                                number = index + 1,
                                row = row,
                                onChange = { changed ->
                                    val i = rows.indexOfFirst { it.id == row.id }
                                    if (i >= 0) rows[i] = changed
                                },
                                onDelete = { rows.removeAll { it.id == row.id } }
                            )
                        }
                    }
                    if (rows.isEmpty()) {
                        Text("צריך לפחות פריט אחד.", color = HColors.Ember, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = {
                        rows.add(MealEditRow(nextId, "", "", "", "", "", "", 1f))
                        nextId += 1
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = HColors.Gold)
                        Spacer(Modifier.width(4.dp))
                        Text("הוספת פריט", color = HColors.Gold, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it.take(120) }, label = { Text("שם הארוחה") },
                    isError = desc.isBlank(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!saving && allValid) {
                                saving = true
                                onSave(rows.map { it.toItem() }, desc.trim())
                            }
                        },
                        enabled = allValid && !saving,
                        modifier = Modifier.weight(2f).height(52.dp),
                        shape = FoodCardShape,
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text("שמירה", fontWeight = FontWeight.Black) }
                    OutlinedButton(
                        onClick = onDismiss,
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
private fun MealEditItemCard(number: Int, row: MealEditRow, onChange: (MealEditRow) -> Unit, onDelete: () -> Unit) {
    val ok = row.valid()
    val scope = rememberCoroutineScope()
    var recalcBusy by remember(row.id) { mutableStateOf(false) }
    var recalcFailed by remember(row.id) { mutableStateOf(false) }
    val renamed = row.name.trim().isNotEmpty() && row.name.trim() != row.calcName.trim()
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (ok) HColors.Ink else HColors.Danger)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("פריט $number", style = MaterialTheme.typography.titleMedium, color = HColors.Gold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "מחיקת פריט", tint = HColors.Danger)
                }
            }
            OutlinedTextField(
                value = row.name, onValueChange = { onChange(row.copy(name = it.take(40))) },
                label = { Text("מאכל") }, singleLine = true, isError = !row.nameOk(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = row.kcal, onValueChange = { onChange(row.copy(kcal = mealEditNumText(it), baseGrams = 0f)) },
                    label = { Text("קלוריות") }, singleLine = true, isError = !row.kcalOk(),
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = row.grams, onValueChange = { onChange(row.withGramsText(it)) },
                    label = { Text("גרם") }, singleLine = true, isError = !row.gramsOk(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = row.protein, onValueChange = { onChange(row.copy(protein = mealEditNumText(it), baseGrams = 0f)) },
                    label = { Text("חלבון") }, singleLine = true, isError = !row.proteinOk(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = row.carbs, onValueChange = { onChange(row.copy(carbs = mealEditNumText(it), baseGrams = 0f)) },
                    label = { Text("פחמימות") }, singleLine = true, isError = !row.carbsOk(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = row.fat, onValueChange = { onChange(row.copy(fat = mealEditNumText(it), baseGrams = 0f)) },
                    label = { Text("שומן") }, singleLine = true, isError = !row.fatOk(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            // the name changed: the numbers still belong to the old name -> recalculate for the new one
            if (renamed || recalcBusy) {
                Button(
                    onClick = {
                        if (!recalcBusy) {
                            recalcBusy = true
                            recalcFailed = false
                            val name = row.name.trim()
                            val grams = (mealEditOptional(row.grams, ITEM_GRAMS_MAX) ?: 0f).roundToInt()
                            scope.launch {
                                val fresh = runCatching { MealEstimator.forItem(name, grams) }.getOrNull()
                                if (fresh != null) {
                                    onChange(
                                        row.copy(
                                            grams = if (fresh.grams > 0) fresh.grams.toString() else row.grams,
                                            kcal = fresh.kcal.toString(),
                                            protein = if (fresh.proteinG > 0f) fmtG(fresh.proteinG) else "",
                                            carbs = if (fresh.carbsG > 0f) fmtG(fresh.carbsG) else "",
                                            fat = if (fresh.fatG > 0f) fmtG(fresh.fatG) else "",
                                            confidence = fresh.confidence,
                                            calcName = name,
                                            baseGrams = 0f
                                        )
                                    )
                                } else recalcFailed = true
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
            if (!row.nameOk()) MealEditError("חסר שם מאכל.")
            if (!row.kcalOk()) MealEditError("קלוריות: מספר בין 0 ל-5000.")
            if (!row.gramsOk()) MealEditError("גרם: מספר בין 0 ל-5000, או ריק.")
            if (!row.proteinOk() || !row.carbsOk() || !row.fatOk()) MealEditError("חלבון, פחמימות ושומן: בין 0 ל-500 גרם, או ריק.")
        }
    }
}

@Composable
private fun MealEditError(text: String) {
    Text(text, color = HColors.Ember, style = MaterialTheme.typography.bodyMedium)
}
