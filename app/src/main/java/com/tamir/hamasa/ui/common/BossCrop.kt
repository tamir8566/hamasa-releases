package com.tamir.hamasa.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crops one cell out of a horizontal sprite sheet (cells laid out left to right, equal width).
 * [heightFraction] keeps only the top part of the cell (the boss sheets carry an English caption
 * strip at the bottom that must not reach the Hebrew-only UI).
 */
fun cropSheetCell(bitmap: Bitmap, cols: Int, index: Int, heightFraction: Float = 1f): Bitmap {
    val c = cols.coerceAtLeast(1)
    val i = index.coerceIn(0, c - 1)
    val cellW = bitmap.width / c
    val cellH = (bitmap.height * heightFraction.coerceIn(0.1f, 1f)).toInt().coerceAtLeast(1)
    val x = (i * cellW).coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    val w = cellW.coerceAtMost(bitmap.width - x).coerceAtLeast(1)
    return Bitmap.createBitmap(bitmap, x, 0, w, cellH)
}

/** Decodes a drawable by name and crops one cell; null when the drawable is missing. */
fun loadSheetCell(context: Context, drawableName: String, cols: Int, index: Int, heightFraction: Float = 1f): Bitmap? {
    val id = context.drawableId(drawableName)
    if (id == 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val full = BitmapFactory.decodeResource(context.resources, id, opts) ?: return null
    return cropSheetCell(full, cols, index, heightFraction)
}

/** Compose helper: loads a sheet cell off the main thread and exposes it as state. */
@Composable
fun rememberSheetCell(drawableName: String, cols: Int, index: Int, heightFraction: Float = 1f): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, drawableName, cols, index, heightFraction) {
        value = withContext(Dispatchers.IO) {
            loadSheetCell(context, drawableName, cols, index, heightFraction)?.asImageBitmap()
        }
    }
}

/**
 * Hand-measured horizontal extents (fractions of sheet width) of each boss figure on bosses_1..3.
 * The figures are NOT evenly spaced (smoke, props and staffs spill into neighbours), so equal
 * 5-column cells cut heads and arms. Index = [sheet-1][bossIndex] → (x0, x1).
 */
private val BOSS_X: Array<Array<Pair<Float, Float>>> = arrayOf(
    arrayOf(0.005f to 0.27f, 0.26f to 0.475f, 0.39f to 0.67f, 0.58f to 0.80f, 0.78f to 0.995f),
    arrayOf(0.005f to 0.30f, 0.28f to 0.445f, 0.44f to 0.61f, 0.60f to 0.76f, 0.75f to 0.995f),
    arrayOf(0.005f to 0.265f, 0.25f to 0.47f, 0.43f to 0.63f, 0.60f to 0.82f, 0.80f to 0.995f)
)

/** Top fraction of the boss sheets that holds the figures (below it is the English caption strip). */
private const val BOSS_FIGURE_HEIGHT = 0.885f

/** Crops one boss figure (full height, caption strip removed) out of its sheet by the measured extents. */
fun cropBossFigure(bitmap: Bitmap, sheet: Int, index: Int): Bitmap {
    val row = BOSS_X[(sheet - 1).coerceIn(0, BOSS_X.size - 1)]
    val (f0, f1) = row[index.coerceIn(0, row.size - 1)]
    val x0 = (bitmap.width * f0).toInt().coerceIn(0, bitmap.width - 2)
    val x1 = (bitmap.width * f1).toInt().coerceIn(x0 + 1, bitmap.width)
    val h = (bitmap.height * BOSS_FIGURE_HEIGHT).toInt().coerceIn(1, bitmap.height)
    return Bitmap.createBitmap(bitmap, x0, 0, x1 - x0, h)
}

/** Compose helper: the whole boss figure, loaded off the main thread. */
@Composable
fun rememberBossFigure(drawableName: String, sheet: Int, index: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, drawableName, sheet, index) {
        value = withContext(Dispatchers.IO) {
            val id = context.drawableId(drawableName)
            if (id == 0) null else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeResource(context.resources, id, opts)?.let { cropBossFigure(it, sheet, index).asImageBitmap() }
            }
        }
    }
}
