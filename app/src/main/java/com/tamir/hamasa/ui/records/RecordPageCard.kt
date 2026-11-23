package com.tamir.hamasa.ui.records

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.ui.theme.HColors

/**
 * "דף חדש בספר השיאים": shown on the workout-done screen when the session set new records.
 * The page turns in around its right edge (the spine, in a Hebrew book) with graphicsLayer rotationY.
 */
@Composable
fun NewRecordPageCard(prs: List<String>, modifier: Modifier = Modifier) {
    if (prs.isEmpty()) return
    val angle = remember { Animatable(-100f) }
    LaunchedEffect(Unit) {
        angle.animateTo(0f, animationSpec = tween(durationMillis = 900, delayMillis = 500, easing = FastOutSlowInEasing))
    }
    Card(
        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.Parchment),
        border = BorderStroke(3.dp, HColors.GoldDeep),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = angle.value
                transformOrigin = TransformOrigin(1f, 0.5f)
                cameraDistance = 16f * density
                alpha = if (angle.value < -89f) 0f else 1f
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("דף חדש בספר השיאים", color = HColors.GoldDeep, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            prs.forEach { line ->
                Text(line, color = HColors.Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            Text("נרשם בדיו. דיו לא נמחקת.", color = HColors.Ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
