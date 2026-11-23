package com.tamir.hamasa.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource

/**
 * Two-frame "animation" of Shambiuluk performing an exercise: frame A (start position) and
 * frame B (end position), drawables `ex_<id>_a` / `ex_<id>_b` in res/drawable-nodpi.
 * Loop: A 900ms -> crossfade 250ms -> B 900ms -> crossfade 250ms (2300ms total).
 * Renders nothing when either drawable is missing, so screens stay safe for ids without art.
 */
@Composable
fun ExerciseAnim(exerciseId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val idA = remember(exerciseId) { context.drawableId("ex_${exerciseId}_a") }
    val idB = remember(exerciseId) { context.drawableId("ex_${exerciseId}_b") }
    if (idA == 0 || idB == 0) return

    val painterA = painterResource(idA)
    val painterB = painterResource(idB)

    val transition = rememberInfiniteTransition(label = "exerciseAnim")
    val bAlpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2300
                0f at 0        // hold A
                0f at 900      // start fade to B
                1f at 1150     // hold B
                1f at 2050     // start fade back to A
                0f at 2300
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bAlpha"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterA,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alpha = 1f - bAlpha
        )
        Image(
            painter = painterB,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alpha = bAlpha
        )
    }
}
