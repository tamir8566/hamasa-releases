package com.tamir.hamasa.ui.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.ai.MealSuggester
import com.tamir.hamasa.data.repo.SavedMeal
import com.tamir.hamasa.data.repo.SavedMeals
import com.tamir.hamasa.ui.theme.HColors

/** State of the "מה לאכול עכשיו?" card. */
sealed class NextMealState {
    object Idle : NextMealState()
    object Loading : NextMealState()
    data class Ready(val result: MealSuggester.Result) : NextMealState()
}

private val ChipShape = RoundedCornerShape(16.dp)

// ---------------------------------------------------------------- saved meals strip

/** "הארוחות הקבועות שלי": tap logs for today, long-press edits the portion or deletes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedMealsStrip(saved: List<SavedMeal>, onLog: (SavedMeal) -> Unit, onEdit: (SavedMeal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("הארוחות הקבועות שלי", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(saved, key = { it.id }) { s ->
                Column(
                    Modifier
                        .border(BorderStroke(2.dp, HColors.GoldDeep), ChipShape)
                        .background(HColors.SlateLight, ChipShape)
                        .combinedClickable(onClick = { onLog(s) }, onLongClick = { onEdit(s) })
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        s.nameHe, color = HColors.Parchment, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp)
                    )
                    Text(
                        (if (s.multiplier == 1f) "" else "×${SavedMeals.multLabel(s.multiplier)} · ") +
                            "${s.scaledKcal} קלוריות · חלבון ${fmtG(s.scaledProtein)}",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall, maxLines = 1
                    )
                }
            }
        }
        Text("נגיעה: נרשם להיום. לחיצה ארוכה: גודל מנה או מחיקה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
    }
}

/** Long-press on a saved meal: portion multiplier (saved right away) or delete. */
@Composable
fun SavedMealEditDialog(s: SavedMeal, onMultiplier: (Float) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.nameHe) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${s.scaledKcal} קלוריות · חלבון ${fmtG(s.scaledProtein)} · פחמימות ${fmtG(s.scaledCarbs)} · שומן ${fmtG(s.scaledFat)}",
                    color = HColors.Parchment
                )
                Text("גודל מנה", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in SavedMeals.MULTIPLIERS) {
                        val on = m == s.multiplier
                        if (on) {
                            Button(
                                onClick = { onMultiplier(m) },
                                shape = ChipShape,
                                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                                modifier = Modifier.weight(1f)
                            ) { Text("×${SavedMeals.multLabel(m)}", fontWeight = FontWeight.Black) }
                        } else {
                            OutlinedButton(
                                onClick = { onMultiplier(m) },
                                shape = ChipShape,
                                border = BorderStroke(2.dp, HColors.GoldDeep),
                                modifier = Modifier.weight(1f)
                            ) { Text("×${SavedMeals.multLabel(m)}", color = HColors.Gold) }
                        }
                    }
                }
                TextButton(onClick = onDelete) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק מהארוחות הקבועות", "מחקי מהארוחות הקבועות"), color = HColors.Danger) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } }
    )
}

/** "לשמור כארוחה קבועה?" for a logged meal (long-press on its row, or the automatic offer). */
@Composable
fun SaveFixedDialog(description: String, kcal: Int, auto: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("לשמור כארוחה קבועה?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$description, $kcal קלוריות.", color = HColors.Parchment)
                Text(
                    if (auto) "זו כבר הפעם השלישית. אולי יותר. נגיעה אחת והיא נרשמת. בלי להקליד שוב."
                    else "נגיעה אחת בפס הארוחות הקבועות, והיא נרשמת שוב.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי"), color = HColors.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (auto) "לא עכשיו" else "ביטול") } }
    )
}

// ---------------------------------------------------------------- next meal suggestion

@Composable
fun NextMealCard(state: NextMealState, onAsk: (MealSuggester.MealKind) -> Unit, onLog: (MealSuggester.Suggestion) -> Unit, onClose: () -> Unit) {
    // which meal: defaults to the time of day, he can change it before asking (and for "עוד אפשרויות")
    var kind by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(MealSuggester.defaultKind()) }
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                NextMealState.Idle -> {
                    Text("איזו ארוחה?", style = MaterialTheme.typography.labelLarge, color = HColors.Gold)
                    MealKindChips(selected = kind, onSelect = { kind = it })
                    Button(
                        onClick = { onAsk(kind) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = FoodCardShape,
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                    ) { Text("מה לאכול עכשיו?", fontWeight = FontWeight.Black) }
                    Text(
                        when (kind) {
                            MealSuggester.MealKind.DINNER -> "ארוחת ערב סוגרת את היום: כל הקלוריות והחלבון שנשארו."
                            MealSuggester.MealKind.LUNCH -> "צהריים: הארוחה הגדולה, עם בשר או עוף."
                            MealSuggester.MealKind.BREAKFAST -> "בוקר: קל יותר. גבינות, לחם, ביצים."
                            MealSuggester.MealKind.SNACK -> "משהו קטן וחלבוני בין הארוחות."
                        },
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
                    )
                }
                NextMealState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = HColors.Gold, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(com.tamir.hamasa.domain.game.CharacterNames.g("בודק מה נשאר מהיום. ומה יש במקרר."), color = HColors.Parchment)
                    }
                }
                is NextMealState.Ready -> {
                    Text("מה לאכול עכשיו", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
                    Text(com.tamir.hamasa.domain.game.Addr.x(state.result.line), color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
                    for (s in state.result.options) SuggestionCard(s, onLog = { onLog(s) })
                    Row {
                        TextButton(onClick = { onAsk(kind) }) { Text("עוד אפשרויות", color = HColors.Gold) }
                        TextButton(onClick = onClose) { Text("סגור", color = HColors.ParchmentDim) }
                    }
                }
            }
        }
    }
}

/** בוקר / צהריים / ערב / ביניים as pills; the selected one is gold. */
@Composable
internal fun MealKindChips(selected: MealSuggester.MealKind, onSelect: (MealSuggester.MealKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in MealSuggester.MealKind.values()) {
            val on = k == selected
            Text(
                k.he,
                color = if (on) HColors.Ink else HColors.Parchment,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (on) HColors.Gold else HColors.Slate, RoundedCornerShape(50))
                    .clickable { onSelect(k) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun SuggestionCard(s: MealSuggester.Suggestion, onLog: () -> Unit) {
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Slate),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.nameHe, style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${s.kcal}", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
            }
            if (s.descriptionHe.isNotBlank()) Text(s.descriptionHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            Text(
                "חלבון ${fmtG(s.proteinG)} · פחמימות ${fmtG(s.carbsG)} · שומן ${fmtG(s.fatG)}",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            if (s.whyHe.isNotBlank()) Text(com.tamir.hamasa.domain.game.Addr.x(s.whyHe), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onLog) { Text(com.tamir.hamasa.domain.game.Addr.t("רשום את זה", "רשמי את זה"), color = HColors.Gold, fontWeight = FontWeight.Bold) }
        }
    }
}
