package com.tamir.hamasa.ui.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.ai.CoachNotes
import com.tamir.hamasa.data.repo.RunCoach
import com.tamir.hamasa.data.repo.RunStatus
import com.tamir.hamasa.domain.coach.CoachConstraints
import com.tamir.hamasa.domain.coach.Difficulty
import com.tamir.hamasa.domain.coach.RunPlan
import com.tamir.hamasa.ui.theme.HColors

/** "רמת קושי": קל / רגיל / קשה, plus a line when a note overrides it for now. */
@Composable
fun CoachDifficultyCard(setting: Difficulty, effective: Difficulty, constraints: CoachConstraints?, onPick: (Difficulty) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("רמת קושי", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Difficulty.values().forEach { d ->
                    val selected = setting == d
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) onPick(d) },
                        label = { Text(d.nameHe, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HColors.Gold,
                            selectedLabelColor = HColors.Ink,
                            containerColor = HColors.Slate,
                            labelColor = HColors.Parchment
                        )
                    )
                }
            }
            val line = when (effective) {
                Difficulty.EASY -> "מנוחה ארוכה ב-30 שניות, פחות סטים (עד 10), עלייה במשקל אחרי שני אימונים בקצה העליון. ${effective.rirNoteHe}."
                Difficulty.NORMAL -> "הכללים הרגילים שלך. ${effective.rirNoteHe}."
                Difficulty.HARD -> "מנוחה קצרה ב-30 שניות, עד 12 סטים, עלייה במשקל כבר כשהסט האחרון בקצה העליון. ${effective.rirNoteHe}."
            }
            Text(com.tamir.hamasa.domain.game.Addr.x(line), style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim)
            val override = constraints?.difficultyOverride
            if (override != null && constraints != null) {
                Text(
                    "ההודעה לג'ים ברו קובעת כרגע: ${override.nameHe} עד ${CoachNotes.dayLabel(constraints.untilEpochDay)}.",
                    style = MaterialTheme.typography.bodyMedium, color = HColors.Sky, fontWeight = FontWeight.Bold
                )
            }
            Text(
                "בתרגילים המורכבים היעד נשאר 5 עד 8 חזרות בכל רמה.",
                style = MaterialTheme.typography.bodySmall, color = HColors.ParchmentDim
            )
        }
    }
}

/** "ריצה": the next session of the 9-week plan with a start button (none on a rest day). */
@Composable
fun CoachRunCard(status: RunStatus, starting: Boolean, onStart: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ember)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("ריצה", style = MaterialTheme.typography.titleLarge, color = HColors.Parchment, modifier = Modifier.weight(1f))
                Text("${status.doneSessions} מתוך ${RunPlan.TOTAL_SESSIONS}", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
            }
            LinearProgressIndicator(
                progress = { status.doneSessions.toFloat() / RunPlan.TOTAL_SESSIONS.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = HColors.Ember,
                trackColor = HColors.Slate
            )
            if (status.finished) {
                Text("התוכנית הושלמה: שלושים דקות ריצה רצופה. אפשר לחזור על האימון האחרון.", color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("הבא: ${status.labelHe}", color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    com.tamir.hamasa.domain.game.Addr.x(RunPlan.summaryHe(status.week, status.day)) + ". בסך הכול ${RunPlan.totalSec(status.week, status.day) / 60} דקות עם החימום והשחרור.",
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            }
            val result = RunCoach.resultLineHe(status.lastResult)
            if (result.isNotEmpty()) Text(com.tamir.hamasa.domain.game.Addr.x(result), color = HColors.Sky, style = MaterialTheme.typography.bodyMedium)
            if (status.restToday) {
                Text("אתמול או היום כבר רצת. היום מנוחה מריצה, הליכה או מכון מותרים.", color = HColors.Gold, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onStart,
                enabled = !starting && !status.restToday,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Ember, contentColor = HColors.Ink)
            ) { Text("התחל ריצה", fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
    }
}

/** A note asked for a run / walk / yoga today: one line and the matching start button. */
@Composable
fun CoachTypeWishCard(type: String, runAllowed: Boolean, starting: Boolean, onRun: () -> Unit, onWalk: () -> Unit) {
    val text = when (type) {
        "RUN" -> if (runAllowed) "ביקשת ריצה. האימון הבא בתוכנית הריצה מחכה למטה." else "ביקשת ריצה, אבל אתמול כבר רצת. היום הליכה, ומחר ריצה."
        "WALK" -> "ביקשת הליכה. קצב שמאפשר לדבר."
        "YOGA" -> "ביקשת יוגה. המתג \"יוגה במקום\" דלוק."
        else -> return
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Sky)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (type == "RUN" && runAllowed) {
                Button(
                    onClick = onRun, enabled = !starting,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Ember, contentColor = HColors.Ink)
                ) { Text("התחל ריצה", fontWeight = FontWeight.Black) }
            }
            if (type == "WALK" || (type == "RUN" && !runAllowed)) {
                Button(
                    onClick = onWalk, enabled = !starting,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Sky, contentColor = HColors.Ink)
                ) { Text("התחל הליכה", fontWeight = FontWeight.Black) }
            }
        }
    }
}
