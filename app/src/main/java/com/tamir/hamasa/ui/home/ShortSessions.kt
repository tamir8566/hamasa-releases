package com.tamir.hamasa.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors

/** Hours offered in "סגירת היום" for tomorrow's workout. */
val CLOSE_DAY_HOURS: IntRange = 5..21

private const val WALK_MIN_MINUTES = 10
private const val WALK_MAX_MINUTES = 60
private const val WALK_STEP_MINUTES = 5
private const val WALK_DEFAULT_MINUTES = 30

/** Compact secondary actions under the main home button: tiny home workout, walk, minimal 20-minute workout. */
@Composable
fun ShortSessionRow(enabled: Boolean, onMini: () -> Unit, onWalk: () -> Unit, onMinimal: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShortSessionButton("אימון זעיר", enabled, onMini, Modifier.weight(1f))
        ShortSessionButton("הליכה", enabled, onWalk, Modifier.weight(1f))
        ShortSessionButton("אימון מינימלי", enabled, onMinimal, Modifier.weight(1f))
    }
}

@Composable
private fun ShortSessionButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, HColors.GoldDeep),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Parchment)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** "הליכה": how long. 10–60 minutes in steps of 5; the real time is confirmed when the walk set is finished. */
@Composable
fun WalkDialog(enabled: Boolean, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(WALK_DEFAULT_MINUTES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text("הליכה", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("כמה זמן?", color = HColors.ParchmentDim)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        onClick = { minutes = (minutes - WALK_STEP_MINUTES).coerceAtLeast(WALK_MIN_MINUTES) },
                        enabled = minutes > WALK_MIN_MINUTES,
                        modifier = Modifier.size(52.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = BorderStroke(2.dp, HColors.GoldDeep),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                    ) { Text("−", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    Text("$minutes דקות", fontSize = 24.sp, fontWeight = FontWeight.Black, color = HColors.Parchment)
                    OutlinedButton(
                        onClick = { minutes = (minutes + WALK_STEP_MINUTES).coerceAtMost(WALK_MAX_MINUTES) },
                        enabled = minutes < WALK_MAX_MINUTES,
                        modifier = Modifier.size(52.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = BorderStroke(2.dp, HColors.GoldDeep),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                    ) { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                }
                Text("בסוף מאשרים כמה הלכת בפועל.", color = HColors.ParchmentDim, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(minutes) }, enabled = enabled) { Text("יוצאים", color = HColors.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול", color = HColors.ParchmentDim) } }
    )
}
