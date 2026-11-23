package com.tamir.hamasa.ui.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors

private val CardShape = RoundedCornerShape(20.dp)

/** "האימונים שלי": the user's own workouts. Start, edit or delete each one, or build a new one. */
@Composable
fun TemplatesScreen(onStart: (Long) -> Unit, onEdit: (Long) -> Unit, onNew: () -> Unit) {
    val vm: TemplatesViewModel = viewModel()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HColors.Slate),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                com.tamir.hamasa.domain.game.Addr.t("אימונים שאתה בונה בעצמך. הסטים, החזרות והמשקלים שלך, והמשחק מתגמל בדיוק כמו באימון של הג'ים ברו.", "אימונים שאת בונה בעצמך. הסטים, החזרות והמשקלים שלך, והמשחק מתגמל בדיוק כמו באימון של הג'ים ברו."),
                style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
            )
        }

        item {
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("אימון חדש", fontSize = 20.sp, fontWeight = FontWeight.Black) }
        }

        if (templates.isEmpty()) {
            item {
                Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.Ink)) {
                    Text(
                        com.tamir.hamasa.domain.game.Addr.t("עדיין אין אימונים משלך. בנה אחד, והוא יופיע כאן וגם בבחירה לפני כל אימון.", "עדיין אין אימונים משלך. בני אחד, והוא יופיע כאן וגם בבחירה לפני כל אימון."),
                        Modifier.padding(16.dp),
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        items(templates, key = { it.id }) { t ->
            TemplateCard(
                t = t,
                count = counts[t.id] ?: 0,
                starting = starting,
                onStart = { vm.start(t.id, onStart) },
                onEdit = { onEdit(t.id) },
                onDelete = { vm.askDelete(t) }
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    val del = deleting
    if (del != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.ParchmentDim,
            title = { Text("למחוק את ${del.nameHe}?", fontWeight = FontWeight.Bold) },
            text = { Text("האימון יימחק מהרשימה. אימונים שכבר בוצעו ממנו נשארים בהיסטוריה.") },
            confirmButton = {
                Button(
                    onClick = { vm.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Danger, contentColor = HColors.Parchment)
                ) { Text("כן, למחוק") }
            },
            dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("ביטול", color = HColors.Gold) } }
        )
    }
}

private fun lastUsedText(t: WorkoutTemplateEntity): String {
    if (t.lastUsedAt <= 0L) return "עוד לא בוצע"
    val days = Days.today() - (t.lastUsedAt / 86_400_000L)
    return when {
        days <= 0L -> "בוצע היום"
        days == 1L -> "בוצע אתמול"
        else -> "בוצע לפני $days ימים"
    }
}

@Composable
private fun TemplateCard(
    t: WorkoutTemplateEntity,
    count: Int,
    starting: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.nameHe, style = MaterialTheme.typography.titleLarge, color = HColors.Parchment)
            Text(
                "$count תרגילים · ${lastUsedText(t)}",
                style = MaterialTheme.typography.bodyMedium, color = HColors.ParchmentDim
            )
            if (t.notes.isNotBlank()) {
                Text(t.notes, style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    enabled = !starting && count > 0,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("התחל", fontWeight = FontWeight.Black) }
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HColors.GoldDeep),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Gold)
                ) { Text("ערוך", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HColors.Danger),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Danger)
                ) { Text("מחק", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
