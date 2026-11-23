package com.tamir.hamasa.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.food.FoodBaseStore
import com.tamir.hamasa.domain.food.FoodFact
import com.tamir.hamasa.ui.theme.HColors

/**
 * "מאגר המאכלים" on the food screen: how many foods the phone already knows, a search, and a small editor to
 * correct a food's values per 100 grams or to add a food by hand. Closed by default; no dialogs.
 */
@Composable
fun FoodBaseCard(startOpen: Boolean = false) {
    val mine by FoodBaseStore.user.collectAsState()
    var open by remember { mutableStateOf(startOpen) }
    var query by remember { mutableStateOf("") }
    var editingName by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.testTag("food_base_header"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("מאגר המאכלים", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                Text(if (open) "סגירה" else "פתיחה", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
            val total = FoodBaseStore.seedCount() + mine.size
            Text(
                if (mine.isEmpty()) "$total מאכלים מוכרים. מאכלים חדשים מהארוחות שלך יתווספו לבד."
                else "$total מאכלים מוכרים. ${mine.size} מהם שלך: נלמדו מהארוחות או נכתבו ביד.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            if (open) {
                Text(
                    "כשכותבים ארוחה, מאכל שנמצא כאן מחושב בטלפון, בלי בינה מלאכותית. מאכל חדש נשאל פעם אחת ונשמר כאן.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40); editingName = null },
                    label = { Text("חיפוש מאכל") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("food_base_search")
                )
                val results = remember(query, mine) { FoodBaseStore.search(query, 25) }
                when {
                    query.isBlank() && results.isEmpty() ->
                        Text("עוד אין מאכלים משלך. אפשר לחפש מאכל מוכר, או להוסיף אחד.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    query.isBlank() ->
                        Text("המאכלים שלך, מהחדש לישן:", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                    results.isEmpty() ->
                        Text("לא נמצא במאגר. אפשר להוסיף אותו כאן למטה.", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                }
                for (f in results) {
                    if (editingName == f.nameHe) {
                        FoodFactEditor(initial = f, startName = f.nameHe, onDone = { editingName = null })
                    } else {
                        FoodFactRow(f, onClick = { editingName = f.nameHe; adding = false })
                    }
                }
                if (adding) {
                    FoodFactEditor(initial = null, startName = query.trim(), onDone = { adding = false })
                } else {
                    OutlinedButton(
                        onClick = { adding = true; editingName = null },
                        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("food_base_add"),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("הוספת מאכל חדש", color = HColors.Parchment) }
                }
            }
        }
    }
}

private fun shortNumber(v: Float): String {
    val r = Math.round(v * 10f) / 10f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

private fun originHe(f: FoodFact): String = when (f.origin) {
    FoodFact.ORIGIN_LEARNED -> "נלמד מארוחה שלך"
    FoodFact.ORIGIN_EDITED -> "נכתב ביד"
    else -> "מאכל מובנה"
}

@Composable
private fun FoodFactRow(f: FoodFact, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.testTag("food_base_row")
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.nameHe, color = HColors.Parchment, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "${shortNumber(f.kcal100)} קלוריות במאה גרם",
                color = HColors.Gold, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "חלבון ${shortNumber(f.protein100)} · פחמימות ${shortNumber(f.carbs100)} · שומן ${shortNumber(f.fat100)}",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                (if (f.perUnit) "יחידה אחת: ${f.portionG} גרם" else "מנה רגילה: ${f.portionG} גרם") + " · " + originHe(f),
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun decimal(s: String): String = s.filter { c -> c.isDigit() || c == '.' }.take(6)

/** Editor of one food. [initial] null = a new food (the name can be typed). */
@Composable
private fun FoodFactEditor(initial: FoodFact?, startName: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(startName) }
    var kcal by remember { mutableStateOf(if (initial == null) "" else shortNumber(initial.kcal100)) }
    var protein by remember { mutableStateOf(if (initial == null) "" else shortNumber(initial.protein100)) }
    var carbs by remember { mutableStateOf(if (initial == null) "" else shortNumber(initial.carbs100)) }
    var fat by remember { mutableStateOf(if (initial == null) "" else shortNumber(initial.fat100)) }
    var portion by remember { mutableStateOf(if (initial == null) "" else initial.portionG.toString()) }
    var note by remember { mutableStateOf<String?>(null) }
    var sure by remember { mutableStateOf(false) }
    val mineRow = initial != null && FoodBaseStore.isMine(initial.nameHe)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Gold),
        modifier = Modifier.fillMaxWidth().testTag("food_base_editor")
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (initial == null) {
                Text("מאכל חדש", color = HColors.Gold, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("שם המאכל") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("food_base_name")
                )
            } else {
                Text(initial.nameHe, color = HColors.Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (!mineRow) Text("מאכל מובנה. מה שתשמור כאן יגבר עליו.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            }
            Text("הערכים הם למאה גרם.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(
                value = kcal, onValueChange = { kcal = decimal(it) }, label = { Text("קלוריות") }, singleLine = true,
                keyboardOptions = numbers, modifier = Modifier.fillMaxWidth().testTag("food_base_kcal")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = protein, onValueChange = { protein = decimal(it) }, label = { Text("חלבון") }, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.weight(1f).testTag("food_base_protein")
                )
                OutlinedTextField(
                    value = carbs, onValueChange = { carbs = decimal(it) }, label = { Text("פחמימות") }, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.weight(1f).testTag("food_base_carbs")
                )
                OutlinedTextField(
                    value = fat, onValueChange = { fat = decimal(it) }, label = { Text("שומן") }, singleLine = true,
                    keyboardOptions = numbers, modifier = Modifier.weight(1f).testTag("food_base_fat")
                )
            }
            OutlinedTextField(
                value = portion, onValueChange = { portion = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("כמה גרם במנה רגילה") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("food_base_portion")
            )
            val n = note
            if (n != null) Text(n, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val k = kcal.toFloatOrNull()
                        val p = protein.toFloatOrNull() ?: 0f
                        val c = carbs.toFloatOrNull() ?: 0f
                        val f = fat.toFloatOrNull() ?: 0f
                        val g = portion.toIntOrNull()
                        val cleanName = name.trim()
                        when {
                            cleanName.length < 2 -> note = "חסר שם למאכל."
                            k == null || k > 950f -> note = "קלוריות למאה גרם: מספר בין 0 ל-950."
                            p > 100f || c > 100f || f > 100f -> note = "חלבון, פחמימות ושומן: עד 100 גרם למאה גרם."
                            g == null || g < 1 || g > 2000 -> note = "מנה רגילה: בין 1 ל-2000 גרם."
                            else -> {
                                FoodBaseStore.put(
                                    FoodFact(
                                        cleanName, k ?: 0f, p, c, f, g ?: 100,
                                        perUnit = initial?.perUnit ?: false,
                                        aliases = initial?.aliases ?: emptyList(),
                                        origin = FoodFact.ORIGIN_EDITED
                                    )
                                )
                                onDone()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(46.dp).testTag("food_base_save"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("שמירה", fontWeight = FontWeight.Black) }
                TextButton(onClick = onDone, modifier = Modifier.testTag("food_base_cancel")) { Text("ביטול", color = HColors.Parchment) }
            }
            if (mineRow && initial != null) {
                if (!sure) {
                    TextButton(onClick = { sure = true }, modifier = Modifier.testTag("food_base_delete")) {
                        Text("הסרה מהמאגר שלי", color = HColors.ParchmentDim)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("להסיר את המאכל הזה?", color = HColors.Parchment, modifier = Modifier.weight(1f))
                        TextButton(onClick = { FoodBaseStore.remove(initial.nameHe); onDone() }, modifier = Modifier.testTag("food_base_delete_yes")) {
                            Text("כן, להסיר", color = HColors.Gold, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { sure = false }) { Text("לא", color = HColors.Parchment) }
                    }
                }
            }
        }
    }
}
