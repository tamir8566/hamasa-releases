package com.tamir.hamasa.ui.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * "הוספת הליכה": a walk he already did. Minutes (5..180) and, optionally, the time it ended today (default: now).
 * It is saved as a normal finished walk, so the calorie burn counts it like any other workout.
 */
@Composable
fun ManualWalkCard() {
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var kmText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("הוספת הליכה", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Text(
                "כבר הלכת? כתוב כמה דקות וכמה קילומטרים, והיא נרשמת כהליכה רגילה. השריפה לפי הכלל שלך: המשקל כפול הקילומטרים, כפול 60 אחוז.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = minutes, onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("כמה דקות הלכת") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = kmText, onValueChange = { kmText = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }.take(5) },
                label = { Text("כמה קילומטרים (לא חובה, משוער לפי הזמן אם ריק)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = endText, onValueChange = { endText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                label = { Text("באיזו שעה נגמרה (לא חובה, למשל 18:30)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (busy) return@Button
                    val m = minutes.toIntOrNull()
                    if (m == null || m < 5 || m > 180) { note = "דקות בין 5 ל-180."; return@Button }
                    val end = parseEnd(endText)
                    if (end == null) { note = "השעה לא ברורה. כתוב כמו 18:30, או השאר ריק."; return@Button }
                    if (end > System.currentTimeMillis()) { note = "השעה הזאת עוד לא הגיעה."; return@Button }
                    val km = if (kmText.isBlank()) 0f else kmText.toFloatOrNull()
                    if (km == null || km < 0f || km > 100f) { note = "המרחק לא ברור. כתוב למשל 3.5, או השאר ריק."; return@Button }
                    busy = true
                    note = null
                    scope.launch {
                        val id = runCatching { AppGraph.workouts.logManualWalk(m, end, distanceM = (km * 1000f).toInt()) }.getOrNull()
                        if (id == null) {
                            note = "יש אימון פעיל עכשיו, או שהרישום לא הצליח. סיים את האימון ונסה שוב."
                        } else {
                            val kcal = runCatching { EnergyData.sessionBurn(id)?.kcal }.getOrNull()
                            note = "נרשם. הליכה של $m דקות" + (if (kcal != null && kcal > 0) ", שרפת בערך $kcal קלוריות." else ".")
                            minutes = ""; endText = ""; kmText = ""
                        }
                        busy = false
                    }
                },
                enabled = !busy && minutes.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("הוספת הליכה", fontWeight = FontWeight.Black) }
            val n = note
            if (n != null) Text(n, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Blank = now; "H:mm" / "HH:mm" = that time today; anything else = null. */
internal fun parseEnd(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return System.currentTimeMillis()
    val parts = t.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val mi = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || mi !in 0..59) return null
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atTime(LocalTime.of(h, mi)).atZone(zone).toInstant().toEpochMilli()
}
