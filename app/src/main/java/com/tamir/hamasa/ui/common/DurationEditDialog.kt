package com.tamir.hamasa.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors

private const val DUR_MIN = 1
private const val DUR_MAX = 300
private val DurationChips = listOf(30, 45, 60, 75, 90)

/** Small pencil next to a workout's duration; opens the edit dialog. */
@Composable
fun DurationEditPencil(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(Icons.Filled.Edit, contentDescription = "עריכת משך", tint = HColors.Gold, modifier = Modifier.size(20.dp))
    }
}

/**
 * Edit how long a workout took: hours and minutes steppers plus quick picks. Always 1..300 minutes.
 * [onSave] gets the total minutes.
 */
@Composable
fun DurationEditDialog(initialMinutes: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var total by rememberSaveable { mutableIntStateOf(initialMinutes.coerceIn(DUR_MIN, DUR_MAX)) }
    val hours = total / 60
    val mins = total % 60

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text("כמה זמן נמשך האימון?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    durationEditLabel(total),
                    color = HColors.Gold,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                DurationStepperRow(
                    label = "שעות",
                    value = hours,
                    canMinus = total - 60 >= DUR_MIN,
                    canPlus = total + 60 <= DUR_MAX,
                    onMinus = { total = (total - 60).coerceIn(DUR_MIN, DUR_MAX) },
                    onPlus = { total = (total + 60).coerceIn(DUR_MIN, DUR_MAX) }
                )
                DurationStepperRow(
                    label = "דקות",
                    value = mins,
                    canMinus = total - 1 >= DUR_MIN,
                    canPlus = total + 1 <= DUR_MAX,
                    onMinus = { total = (total - 1).coerceIn(DUR_MIN, DUR_MAX) },
                    onPlus = { total = (total + 1).coerceIn(DUR_MIN, DUR_MAX) }
                )
                Text("בחירה מהירה (דקות)", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DurationChips.forEach { m ->
                        FilterChip(
                            selected = total == m,
                            onClick = { total = m },
                            label = {
                                Text(
                                    m.toString(),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = HColors.Ink,
                                labelColor = HColors.Parchment,
                                selectedContainerColor = HColors.Gold,
                                selectedLabelColor = HColors.Ink
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    "ניסיון וזהב לא משתנים. רק השעון.",
                    color = HColors.ParchmentDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(total.coerceIn(DUR_MIN, DUR_MAX)) },
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור", "שמרי"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול", color = HColors.Gold) } }
    )
}

@Composable
private fun DurationStepperRow(
    label: String,
    value: Int,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus, enabled = canMinus) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = "פחות $label",
                tint = if (canMinus) HColors.Gold else HColors.ParchmentDim.copy(alpha = 0.4f)
            )
        }
        Text(
            value.toString(),
            color = HColors.Parchment,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        IconButton(onClick = onPlus, enabled = canPlus) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "יותר $label",
                tint = if (canPlus) HColors.Gold else HColors.ParchmentDim.copy(alpha = 0.4f)
            )
        }
    }
}

/** "45 דקות" / "שעה ו־15 דקות" style label of a total in minutes. */
fun durationEditLabel(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    val hPart = when (h) {
        0 -> ""
        1 -> "שעה"
        2 -> "שעתיים"
        else -> "$h שעות"
    }
    val mPart = when (m) {
        0 -> ""
        1 -> "דקה"
        else -> "$m דקות"
    }
    return when {
        hPart.isEmpty() -> mPart
        mPart.isEmpty() -> hPart
        else -> "$hPart ו־$mPart"
    }
}
