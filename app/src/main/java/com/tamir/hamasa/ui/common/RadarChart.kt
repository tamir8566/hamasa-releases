package com.tamir.hamasa.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.domain.coach.MuscleRegion
import com.tamir.hamasa.ui.theme.HColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Axis order around the hexagon, starting at the top and going clockwise. */
private val RadarOrder: List<MuscleRegion> = listOf(
    MuscleRegion.CHEST, MuscleRegion.SHOULDERS, MuscleRegion.ARMS,
    MuscleRegion.LEGS, MuscleRegion.CORE, MuscleRegion.BACK
)

/** Values below this still show as a tiny bump so the polygon never collapses to a dot. */
private const val MIN_VALUE = 0.04f

/**
 * Hexagon radar of the six body regions. [current] is drawn as a filled gold polygon,
 * [previous] (when given) as a dashed parchment outline. Values are 0..1.
 */
@Composable
fun RadarChart(
    current: Map<MuscleRegion, Float>,
    previous: Map<MuscleRegion, Float>? = null,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HColors.Parchment)
    val labels = remember(measurer) { RadarOrder.map { measurer.measure(AnnotatedString(it.nameHe), labelStyle) } }

    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) / 2f * 0.68f
        val gridColor = HColors.ParchmentDim.copy(alpha = 0.28f)
        val thin = 1.dp.toPx()

        fun point(i: Int, r: Float): Offset {
            val angle = -PI / 2.0 + i * (2.0 * PI / RadarOrder.size)
            return Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
        }

        fun ring(r: Float): Path {
            val p = Path()
            for (i in RadarOrder.indices) {
                val pt = point(i, r)
                if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
            p.close()
            return p
        }

        fun polygon(values: Map<MuscleRegion, Float>): Path {
            val p = Path()
            for (i in RadarOrder.indices) {
                val v = (values[RadarOrder[i]] ?: 0f).coerceIn(0f, 1f).coerceAtLeast(MIN_VALUE)
                val pt = point(i, radius * v)
                if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
            p.close()
            return p
        }

        // grid rings and spokes
        for (step in 1..4) drawPath(ring(radius * step / 4f), color = gridColor, style = Stroke(width = thin))
        for (i in RadarOrder.indices) drawLine(gridColor, Offset(cx, cy), point(i, radius), strokeWidth = thin)

        // previous period: dashed outline
        if (previous != null) {
            drawPath(
                polygon(previous),
                color = HColors.ParchmentDim,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            )
        }

        // current period: filled + stroked
        val cur = polygon(current)
        drawPath(cur, color = HColors.Gold.copy(alpha = 0.35f))
        drawPath(cur, color = HColors.Gold, style = Stroke(width = 3.dp.toPx()))
        for (i in RadarOrder.indices) {
            val v = (current[RadarOrder[i]] ?: 0f).coerceIn(0f, 1f).coerceAtLeast(MIN_VALUE)
            drawCircle(color = HColors.Gold, radius = 4.dp.toPx(), center = point(i, radius * v))
        }

        // labels just outside the outer ring
        for (i in RadarOrder.indices) {
            val pt = point(i, radius * 1.22f)
            val layout = labels[i]
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(pt.x - layout.size.width / 2f, pt.y - layout.size.height / 2f)
            )
        }
    }
}

/** Legend row colors for the radar (exposed so screens can draw matching swatches). */
object RadarColors {
    val current: Color = HColors.Gold
    val previous: Color = HColors.ParchmentDim
}
