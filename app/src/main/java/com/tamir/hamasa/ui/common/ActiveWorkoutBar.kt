package com.tamir.hamasa.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.ui.theme.HColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The strip shown at the bottom of every screen while a workout is active: "אימון פעיל, התחיל ב-18:05"
 * and a button back to it. It lives in the Scaffold bottom bar, so it stays put while content scrolls
 * and the content gets room under it (nothing is hidden).
 */
@Composable
fun ActiveWorkoutBar(startedAtMillis: Long?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val since = if (startedAtMillis != null && startedAtMillis > 0L)
        "התחיל ב-" + SimpleDateFormat("HH:mm", Locale.US).format(Date(startedAtMillis)) else "ממשיך"
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),   // the whole strip opens the workout, not only the button
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.Gold)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "אימון פעיל · $since",
                color = HColors.Gold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black
            )
            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text("חזרה לאימון", fontWeight = FontWeight.Black) }
        }
    }
}
