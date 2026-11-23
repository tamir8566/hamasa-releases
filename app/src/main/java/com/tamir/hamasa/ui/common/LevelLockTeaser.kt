package com.tamir.hamasa.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.domain.game.ModeVoice
import com.tamir.hamasa.ui.theme.HColors

/**
 * A level gate that is still closed: a dark veil over the (dimmed) art underneath, with one card in the middle
 * ("העץ נפתח ברמה 4. עוד 320 נקודות."). The veil swallows taps so nothing underneath opens by accident.
 */
@Composable
fun LevelLockTeaser(text: String, modifier: Modifier = Modifier) {
    val line = remember { ModeVoice.gateLocked() }
    val swallow = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxSize()
            .background(HColors.Ink.copy(alpha = 0.45f))
            .clickable(interactionSource = swallow, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
            border = BorderStroke(3.dp, HColors.Gold),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("נעול", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(com.tamir.hamasa.domain.game.Addr.x(text), color = HColors.Parchment, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(com.tamir.hamasa.domain.game.Addr.x(line), color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
