package com.tamir.hamasa.ui.coach

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

/**
 * "הוספת יוגה": a yoga class he already did. Three levels (קלה / בינונית / קשה), the minutes (5..180) and,
 * optionally, the time it ended today (default: now). Saved as a normal finished yoga session, so the calorie
 * burn, the history and the streak count it like any other workout. The level sets the burn.
 */
@Composable
fun ManualYogaCard() {
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val levels = listOf("קלה", "בינונית", "קשה")
    val hints = listOf(
        "קלה: מתיחות ונשימה, קצב רגוע.",
        "בינונית: זרימה רציפה, מזיעים קצת.",
        "קשה: יוגה כוח, דופק גבוה ומאמץ אמיתי."
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("הוספת יוגה", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Text(
                "עשית יוגה? בחר רמה, כתוב כמה דקות, והיא נרשמת כאימון רגיל. הרמה קובעת כמה קלוריות נשרפו.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEachIndexed { i, name ->
                    val on = level == i
                    if (on) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("yoga_level_$i"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                        ) { Text(name, fontWeight = FontWeight.Black) }
                    } else {
                        OutlinedButton(
                            onClick = { level = i },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("yoga_level_$i"),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(name, color = HColors.Parchment) }
                    }
                }
            }
            Text(hints[level], color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = minutes, onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("כמה דקות יוגה") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = endText, onValueChange = { endText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                label = { Text("באיזו שעה נגמרה (לא חובה, למשל 07:30)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (busy) return@Button
                    val m = minutes.toIntOrNull()
                    if (m == null || m < 5 || m > 180) { note = "דקות בין 5 ל-180."; return@Button }
                    val end = parseEnd(endText)
                    if (end == null) { note = "השעה לא ברורה. כתוב כמו 07:30, או השאר ריק."; return@Button }
                    if (end > System.currentTimeMillis()) { note = "השעה הזאת עוד לא הגיעה."; return@Button }
                    busy = true
                    note = null
                    val lv = level
                    scope.launch {
                        val id = runCatching { AppGraph.workouts.logManualYoga(m, lv, end) }.getOrNull()
                        if (id == null) {
                            note = "יש אימון פעיל עכשיו, או שהרישום לא הצליח. סיים את האימון ונסה שוב."
                        } else {
                            val kcal = runCatching { EnergyData.sessionBurn(id)?.kcal }.getOrNull()
                            note = "נרשם. יוגה ${levels[lv]} של $m דקות" + (if (kcal != null && kcal > 0) ", שרפת בערך $kcal קלוריות." else ".")
                            minutes = ""; endText = ""
                        }
                        busy = false
                    }
                },
                enabled = !busy && minutes.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("הוספת יוגה", fontWeight = FontWeight.Black) }
            val n = note
            if (n != null) Text(n, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
