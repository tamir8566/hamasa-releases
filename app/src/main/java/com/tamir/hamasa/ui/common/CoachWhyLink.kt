package com.tamir.hamasa.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.ui.theme.HColors

/**
 * Small "למה?" link (stage 2 G). Tap opens a dialog that shows a spinner until [load] returns the coach's
 * explanation. [load] must never throw for long (CoachWhy falls back to a template); an exception shows a short line.
 */
@Composable
fun CoachWhyLink(title: String, load: suspend () -> String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text("למה?", color = HColors.Sky, fontWeight = FontWeight.Bold)
    }
    if (open) {
        LaunchedEffect(Unit) {
            if (text == null) {
                text = try {
                    load()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    com.tamir.hamasa.domain.game.Addr.t("אין הסבר כרגע. נסה שוב בעוד רגע.", "אין הסבר כרגע. נסי שוב בעוד רגע.")
                }
            }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = HColors.SlateLight,
            titleContentColor = HColors.Parchment,
            textContentColor = HColors.Parchment,
            title = { Text("למה $title?") },
            text = {
                val t = text
                if (t == null) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = HColors.Gold)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(com.tamir.hamasa.domain.game.Addr.x(t), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("סגור", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            }
        )
    }
}
