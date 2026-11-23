package com.tamir.hamasa.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.PI
import kotlin.math.sin

/** Brightness 0.7 color matrix (alpha untouched). */
private val sleepDimPaint: Paint by lazy {
    Paint().apply {
        colorFilter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.7f, 0f, 0f, 0f, 0f,
                    0f, 0.7f, 0f, 0f, 0f,
                    0f, 0f, 0.7f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}

/**
 * Dims everything drawn by this node (the home scene, Shambiuluk included) to 70% brightness while he sleeps.
 * No new art: the existing layers are just drawn through a color matrix.
 */
fun Modifier.sleepDim(on: Boolean): Modifier =
    if (!on) this
    else this.drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), sleepDimPaint)
            this@drawWithContent.drawContent()
            canvas.restore()
        }
    }

/**
 * "שמביולוק ישן": three letters "ז" floating up from near his head, fading in and out, on a loop.
 * Meant to be laid over the scene panel with matchParentSize().
 */
@Composable
fun SleepOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sleep")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "zzz"
    )
    BoxWithConstraints(modifier) {
        // Roughly above the head of the standing figure (top center of the panel, a bit to the side).
        val anchorX = maxWidth * 0.10f
        val anchorY = maxHeight * 0.20f
        Box(Modifier.align(Alignment.TopCenter).offset(x = anchorX, y = anchorY)) {
            for (i in 0 until 3) {
                val local = (phase + i / 3f) % 1f
                Text(
                    text = "ז",
                    color = HColors.Parchment,
                    fontSize = (16 + 10 * local).sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer {
                        translationX = local * 22.dp.toPx()
                        translationY = -local * 56.dp.toPx()
                        alpha = sin(local * PI).toFloat().coerceIn(0f, 1f)
                    }
                )
            }
        }
    }
}
