package com.tamir.hamasa.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.tamir.hamasa.domain.food.FoodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stage 2 F: meal photo / meal text -> list of food items with kcal and macros, through [AiClient] only (Claude or Gemini).
 * Never throws; every failure comes back as a [Result] the UI turns into the manual form.
 */
object MealEstimator {

    sealed class Result {
        data class Ok(val items: List<FoodItem>, val noteHe: String) : Result()
        /** No API key: the feature is off. */
        object NoKey : Result()
        /** The model answered, but found no food. */
        object NotFood : Result()
        /** Offline, quota, timeout, bad JSON. */
        object Failed : Result()
    }

    private const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 80

    private val SYSTEM = """
You are a nutrition estimator inside a Hebrew fitness app used in Israel.
Identify every food and drink in the meal (from a photo, or from a short Hebrew description) and estimate the portion
weight in grams and its calories and macros. Think of typical Israeli food and portions: pita, laffa, hummus, tahini,
falafel, shakshuka, schnitzel, Israeli salad (chopped cucumber and tomato), bourekas, sabich, jachnun, malawach,
majadra, ptitim, cottage cheese, labneh, white cheese (gvina levana 5%), bamba, rugelach, shawarma, kebab, chicken
breast, rice, Bulgarian cheese, tuna salad, pastrami sandwich, cornflakes with milk, energy drinks, coffee with milk.
Use plate, cutlery and hand size to judge portions. Count oil, tahini and sauces that are visible or usual for the dish.
Names must be short and in Hebrew (name_he), as an Israeli would write them. Numbers are numbers, never strings.
If there is no food at all, return an empty items array.
Answer ONLY with JSON of exactly this shape:
{"items":[{"name_he":"...","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"confidence":0.0}],
 "total":{"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0},
 "note_he":"one short Hebrew sentence about the estimate (what was hard to see), no emojis"}
confidence is 0..1 for how sure you are about the item and its portion.
""".trimIndent()

    /** Photo estimate. [jpeg] should already be downscaled (see [downscaleJpeg]). */
    suspend fun fromImage(jpeg: ByteArray): Result {
        if (!AiClient.enabled) return Result.NoKey
        val json = AiClient.generateJson(
            listOf(AiClient.Part.Image(jpeg, "image/jpeg"), AiClient.Part.Text("Estimate this meal.")),
            system = SYSTEM
        ) ?: return Result.Failed
        return parse(json)
    }

    /** Text estimate ("שתי פיתות עם חומוס"). */
    suspend fun fromText(descriptionHe: String): Result {
        if (!AiClient.enabled) return Result.NoKey
        val text = descriptionHe.trim()
        if (text.isEmpty()) return Result.NotFood
        val json = AiClient.generateJson(
            listOf(AiClient.Part.Text("Meal description (Hebrew): $text")),
            system = SYSTEM
        ) ?: return Result.Failed
        return parse(json)
    }

    /** Tests only: answers [forItem] instead of the AI. */
    @Volatile internal var itemOverride: (suspend (String, Int) -> FoodItem?)? = null

    /** Waiting limit of [forItem]; the proxy answers in 12-18 s on a slow day. */
    private const val ITEM_TIMEOUT_MS = 45_000L

    /**
     * "חישוב מחדש" in the review: kcal and macros of ONE renamed item at its portion ([grams]; 0 = let the model
     * pick a typical portion). The typed name and the portion stay as he set them; only the numbers change.
     * Null when it failed (no key, offline, timeout, no food).
     */
    suspend fun forItem(nameHe: String, grams: Int): FoodItem? {
        val fake = itemOverride
        if (fake != null) return fake(nameHe.trim(), grams)
        val name = nameHe.trim()
        if (name.isEmpty()) return null
        // the food base first: a food it knows is answered on the phone, with no AI call
        val known = com.tamir.hamasa.data.food.FoodBaseStore.find(name)
        if (known != null) return known.item(if (grams > 0) grams else known.portionG).copy(nameHe = name.take(40))
        if (!AiClient.enabled) return null
        val prompt = if (grams > 0) "One food item (Hebrew): $name. Portion: $grams grams. Return exactly one item with grams = $grams."
        else "One food item (Hebrew): $name. Typical single portion. Return exactly one item."
        val job = com.tamir.hamasa.data.AppGraph.scope.async {
            runCatching { AiClient.generateJson(listOf(AiClient.Part.Text(prompt)), SYSTEM, AiClient.Tier.LITE) }.getOrNull()
        }
        val root = withTimeoutOrNull(ITEM_TIMEOUT_MS) { job.await() }
        if (root == null) {
            job.cancel()
            return null
        }
        val r = parse(root)
        return if (r is Result.Ok) asOne(name, grams, r.items) else null
    }

    /**
     * The model's items as one item named [nameHe]: numbers summed; grams = [grams] when set (the numbers are
     * scaled to it when the model used another portion), else the model's grams. Null for no items / no calories.
     */
    fun asOne(nameHe: String, grams: Int, items: List<FoodItem>): FoodItem? {
        if (items.isEmpty()) return null
        val g = items.sumOf { it.grams }
        val k = items.sumOf { it.kcal }
        if (k <= 0) return null
        var one = FoodItem(
            nameHe.take(40), g, k,
            r1(items.sumOf { it.proteinG.toDouble() }.toFloat()),
            r1(items.sumOf { it.carbsG.toDouble() }.toFloat()),
            r1(items.sumOf { it.fatG.toDouble() }.toFloat()),
            items.minOf { it.confidence }
        )
        if (grams > 0 && g > 0 && g != grams) one = one.withGrams(grams)
        else if (grams > 0) one = one.copy(grams = grams)
        return one
    }

    fun parse(root: JSONObject): Result {
        val arr = root.optJSONArray("items") ?: return Result.NotFood
        val items = ArrayList<FoodItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            itemFrom(o)?.let { items.add(it) }
        }
        if (items.isEmpty()) return Result.NotFood
        val note = root.optString("note_he", "").trim().take(160)
        return Result.Ok(items, note)
    }

    private fun num(o: JSONObject, key: String): Double {
        val v = o.optDouble(key, 0.0)
        return if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
    }

    private fun itemFrom(o: JSONObject): FoodItem? {
        val name = o.optString("name_he", "").trim().ifEmpty { o.optString("name", "").trim() }
        val grams = num(o, "grams").roundToInt().coerceIn(0, 5000)
        val p = num(o, "protein_g").toFloat()
        val c = num(o, "carbs_g").toFloat()
        val f = num(o, "fat_g").toFloat()
        var kcal = num(o, "kcal").roundToInt()
        if (kcal <= 0) kcal = (p * 4f + c * 4f + f * 9f).roundToInt()
        if (name.isEmpty() && kcal <= 0) return null
        val conf = o.optDouble("confidence", 0.5).let { if (it.isNaN()) 0.5 else it }.toFloat().coerceIn(0f, 1f)
        return FoodItem(name.ifEmpty { "מאכל" }.take(40), grams, kcal.coerceIn(0, 10000), r1(p), r1(c), r1(f), conf)
    }

    private fun r1(v: Float): Float = (v * 10f).roundToInt() / 10f

    // ------------------------------------------------------------ items <-> MealEntity.itemsJson

    fun encodeItems(items: List<FoodItem>): String {
        if (items.isEmpty()) return ""
        val arr = JSONArray()
        for (x in items) {
            arr.put(
                JSONObject()
                    .put("name_he", x.nameHe).put("grams", x.grams).put("kcal", x.kcal)
                    .put("protein_g", x.proteinG.toDouble()).put("carbs_g", x.carbsG.toDouble()).put("fat_g", x.fatG.toDouble())
                    .put("confidence", x.confidence.toDouble())
            )
        }
        return arr.toString()
    }

    fun decodeItems(json: String): List<FoodItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<FoodItem>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> itemFrom(o)?.let { out.add(it) } }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ image

    /**
     * Reads [file], applies the EXIF rotation, scales the long side down to 1024 px and re-encodes as JPEG q80.
     * Returns null when the file is not a readable image. Does NOT delete the file (the caller does, always).
     */
    suspend fun downscaleJpeg(file: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            val rotation = runCatching {
                when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            val longSide = max(decoded.width, decoded.height)
            val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            val out = if (scale < 1f || rotation != 0f) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            } else decoded
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            bos.toByteArray()
        } catch (e: Throwable) {
            null
        }
    }
}
