package com.tamir.hamasa.ui.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.ui.theme.HColors

/**
 * "מה מתאמנים היום?": the coach's workout or one of the user's own templates.
 * Shown only when at least one template exists; the callers start directly otherwise.
 */
@Composable
fun WorkoutChooserDialog(
    templates: List<WorkoutTemplateEntity>,
    coachLabel: String = "אימון הג'ים ברו",
    enabled: Boolean = true,
    onCoach: () -> Unit,
    onTemplate: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** Optional short sessions (stage 1 A). A null callback hides its button. */
    onMini: (() -> Unit)? = null,
    onWalk: (() -> Unit)? = null,
    onMinimal: (() -> Unit)? = null,
    /** "אימון בבית" (equipment sheet); null hides it. */
    onHome: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text("מה מתאמנים היום?", fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCoach,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text(coachLabel, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                if (onHome != null) {
                    OutlinedButton(
                        onClick = onHome,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, HColors.GoldDeep),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                    ) { Text("אימון בבית", fontWeight = FontWeight.Bold) }
                }
                Text("או אחד מהאימונים שלך:", color = HColors.ParchmentDim)
                templates.forEach { t ->
                    OutlinedButton(
                        onClick = { onTemplate(t.id) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, HColors.GoldDeep),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Parchment)
                    ) { Text(t.nameHe, fontWeight = FontWeight.Bold) }
                }
                val shortChoices = listOfNotNull(
                    onMini?.let { "אימון זעיר" to it },
                    onWalk?.let { "הליכה" to it },
                    onMinimal?.let { "אימון מינימלי" to it }
                )
                if (shortChoices.isNotEmpty()) {
                    Text("או משהו קצר:", color = HColors.ParchmentDim)
                    shortChoices.forEach { (label, action) ->
                        OutlinedButton(
                            onClick = action,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, HColors.GoldDeep),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Parchment)
                        ) { Text(label, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול", color = HColors.ParchmentDim) } }
    )
}
