package com.tamir.hamasa.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.domain.coach.MuscleGroup
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Body chart renderer. The asset res/drawable-nodpi/muscle_map.png is an indexed PNG whose palette
 * colors encode a class per pixel: 0 transparent background, 1 body, 2 outline, 3..16 the muscle
 * groups ([MuscleGroup.chartIndex]). Android decodes it to ARGB, so the class is recovered by
 * nearest palette color once, then every heat map is painted from that class array.
 */
object MuscleMapRenderer {

    const val ASSET_NAME = "muscle_map"
    const val MAX_WIDTH = 1024

    /** Palette of the asset: class index -> packed RGB (index 0 is transparent and matched by alpha). */
    private val PALETTE = intArrayOf(
        0xFFFFFF, // 0 background
        0xD9DCE3, // 1 body
        0x787E8C, // 2 outline
        0xE53935, // 3 chest
        0xFB8C00, // 4 abs
        0xFDD835, // 5 front delt
        0x43A047, // 6 biceps
        0x00897B, // 7 forearms
        0x1E88E5, // 8 quads
        0x8E24AA, // 9 calves
        0xD81B60, // 10 upper back
        0x00ACC1, // 11 lats
        0xC0CA33, // 12 rear delt
        0x6D4C41, // 13 triceps
        0xAA00FF, // 14 glutes
        0x3949AB, // 15 hamstrings
        0xB71C1C  // 16 lower back
    )

    /** Colors of the non-muscle classes on the dark theme. */
    private val BODY_COLOR = Color(0xFF3C444E)
    private val OUTLINE_COLOR = HColors.Ink
    /** A muscle that was not worked at all. Slightly lighter than the body so the regions stay readable. */
    private val IDLE_COLOR = Color(0xFF4E5864)

    class Classes(val width: Int, val height: Int, val cls: ByteArray)

    @Volatile private var classes: Classes? = null
    private val loadLock = Any()

    /** Rendered bitmaps keyed by the rounded heat map; tiny LRU so the done screen and history do not re-paint. */
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 3
    }

    /** Aspect ratio (width / height) of the chart, a sane default until the asset is loaded. */
    fun aspectRatio(): Float {
        val c = classes ?: return 1024f / 856f
        return c.width.toFloat() / c.height.toFloat()
    }

    /** Stable key for a heat map (2 decimals, fixed group order). */
    fun keyOf(heat: Map<MuscleGroup, Float>): String {
        val sb = StringBuilder()
        for (g in MuscleGroup.entries) {
            val v = (heat[g] ?: 0f).coerceIn(0f, 1f)
            sb.append((v * 100f).roundToInt()).append(',')
        }
        return sb.toString()
    }

    fun cached(key: String): Bitmap? = synchronized(cache) { cache[key] }

    /** Color of a muscle at heat [t] (0..1): idle grey -> gold -> ember. Anything worked shows at least a little gold. */
    fun heatColor(t: Float): Color {
        if (t <= 0f) return IDLE_COLOR
        val v = t.coerceIn(0.18f, 1f)
        return if (v < 0.5f) lerp(IDLE_COLOR, HColors.Gold, v / 0.5f) else lerp(HColors.Gold, HColors.Ember, (v - 0.5f) / 0.5f)
    }

    /** Loads and classifies the asset once (call off the main thread). Null when the drawable is missing. */
    fun load(context: Context): Classes? {
        classes?.let { return it }
        synchronized(loadLock) {
            classes?.let { return it }
            val id = context.drawableId(ASSET_NAME)
            if (id == 0) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, id, bounds)
            var sample = 1
            while (bounds.outWidth > 0 && bounds.outWidth / sample > MAX_WIDTH) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inSampleSize = sample
            }
            val bmp = BitmapFactory.decodeResource(context.resources, id, opts) ?: return null
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val cls = ByteArray(w * h)
            for (i in px.indices) cls[i] = classify(px[i]).toByte()
            val c = Classes(w, h, cls)
            classes = c
            return c
        }
    }

    private fun classify(argb: Int): Int {
        if ((argb ushr 24) < 128) return 0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 1
        var bestD = Int.MAX_VALUE
        for (i in 1 until PALETTE.size) {
            val p = PALETTE[i]
            val dr = r - ((p shr 16) and 0xFF)
            val dg = g - ((p shr 8) and 0xFF)
            val db = b - (p and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        // a stray near-white pixel inside the body (anti-aliasing) counts as body, never as a hole
        return best
    }

    /** Paints the chart for [heat]. Cached by [keyOf]. Call off the main thread. Null when the asset is missing. */
    fun render(context: Context, heat: Map<MuscleGroup, Float>): Bitmap? {
        val key = keyOf(heat)
        cached(key)?.let { return it }
        val c = load(context) ?: return null
        val colors = IntArray(PALETTE.size)
        colors[0] = 0
        colors[1] = BODY_COLOR.toArgb()
        colors[2] = OUTLINE_COLOR.toArgb()
        for (i in 3 until colors.size) colors[i] = IDLE_COLOR.toArgb()
        for (g in MuscleGroup.entries) colors[g.chartIndex] = heatColor(heat[g] ?: 0f).toArgb()
        val n = c.width * c.height
        val px = IntArray(n)
        val cls = c.cls
        for (i in 0 until n) {
            val k = cls[i].toInt()
            px[i] = if (k in colors.indices) colors[k] else 0
        }
        val bmp = Bitmap.createBitmap(px, c.width, c.height, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }
}

/**
 * Front + back body chart with each muscle group shaded by [heat] (0..1 per group, missing = not worked).
 * The bitmap is painted off the main thread and cached, so passing the same map again is free.
 */
@Composable
fun MuscleHeatmap(heat: Map<MuscleGroup, Float>, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val key = remember(heat) { MuscleMapRenderer.keyOf(heat) }
    val image by produceState<ImageBitmap?>(initialValue = MuscleMapRenderer.cached(key)?.asImageBitmap(), key1 = key) {
        // Re-render whenever the heat changes (data often arrives after the first frame). The previous picture
        // stays on screen meanwhile, so there is no flicker; render() returns the cached bitmap when it has one.
        value = withContext(Dispatchers.Default) { MuscleMapRenderer.render(context, heat)?.asImageBitmap() } ?: value
    }
    val ratio = MuscleMapRenderer.aspectRatio()
    Box(modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = "מפת שרירים",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("טוען מפה...", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Small legend under a heatmap: idle -> gold -> ember. */
@Composable
fun MuscleHeatLegend(modifier: Modifier = Modifier) {
    // The brush is painted in absolute (left -> right) space; in RTL the "מעט" label sits on the right.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val stops = listOf(MuscleMapRenderer.heatColor(0.2f), HColors.Gold, HColors.Ember)
    val colors = if (rtl) stops.reversed() else stops
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("מעט", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier
                .width(12.dp)
                .height(1.dp)
        )
        Box(
            Modifier
                .width(120.dp)
                .height(10.dp)
                .background(Brush.horizontalGradient(colors), RoundedCornerShape(5.dp))
        )
        Box(
            Modifier
                .width(12.dp)
                .height(1.dp)
        )
        Text("הרבה", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall)
    }
}
