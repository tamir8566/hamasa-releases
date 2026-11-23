package com.tamir.hamasa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.domain.coach.ExerciseHowTo
import com.tamir.hamasa.ui.theme.HColors

/** True when the exercise has both demo frames (ex_<id>_a / ex_<id>_b). */
@Composable
fun rememberHasExerciseAnim(exerciseId: String): Boolean {
    val context = LocalContext.current
    return remember(exerciseId) {
        context.drawableId("ex_${exerciseId}_a") != 0 && context.drawableId("ex_${exerciseId}_b") != 0
    }
}

/**
 * Short "how to" text for an exercise WITHOUT demo frames. Renders nothing when the exercise
 * has an animation or when no text exists (custom exercises show their own description).
 */
@Composable
fun ExerciseHowToBox(exerciseId: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (rememberHasExerciseAnim(exerciseId)) return
    val text = ExerciseHowTo.textFor(exerciseId) ?: return
    if (compact) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = HColors.Parchment,
            modifier = modifier.padding(top = 4.dp)
        )
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HColors.Ink)
            .border(1.dp, HColors.GoldDeep, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text("איך עושים", style = MaterialTheme.typography.labelLarge, color = HColors.Gold, fontWeight = FontWeight.Bold)
        Text(com.tamir.hamasa.domain.game.Addr.x(text), style = MaterialTheme.typography.bodyMedium, color = HColors.Parchment)
    }
}
