package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "הוספת מתכון משלי לספר" from a source instead of typing: screenshots / photos of a recipe, a YouTube link
 * (Gemini watches the video), or text he pasted (an Instagram caption, a page). The AI reads it, translates it to
 * Hebrew and fills the add-recipe form; he checks it and saves. Nothing is saved here. Never throws.
 */
object RecipeImport {

    sealed class Result {
        data class Ok(val recipe: ChefRecipe) : Result()
        /** The source has no recipe in it. */
        object NotRecipe : Result()
        /** A link that is not YouTube (Instagram, TikTok...): those cannot be read, a screenshot or the text can. */
        object UnsupportedLink : Result()
        object NoKey : Result()
        object Failed : Result()
    }

    private const val TIMEOUT_MS = 90_000L

    /** Tests only: answers instead of the AI. */
    @Volatile internal var override: (suspend (String) -> Result)? = null

    private const val SYSTEM = """
You read a recipe from what the user gives you (photos or screenshots of a recipe, a cooking video, or text) and
write it for his personal recipe book in a Hebrew fitness app.
- Translate everything to plain everyday Hebrew (Israel). Hebrew letters only in the text fields: no English
  letters, no emojis. Units in Hebrew ("גרם", "כף", "כוס", "מ\"ל").
- Keep the ORIGINAL amounts: ingredients_he lists each ingredient with the amount for the whole recipe, exactly as
  the source gives it (1-15 items). steps_he: 1-10 short clear steps for the whole recipe.
- servings: how many portions the recipe makes. When the source does not say, ESTIMATE it from the amounts: a
  normal portion is 400-800 kcal and 150-250 g of meat, so 1.4 kg of chicken with rice is 4-6 portions, never 1.
- kcal, protein_g, carbs_g, fat_g, grams: your honest estimate for ONE portion (the whole recipe divided by servings).
- minutes: total time.
- name_he: a short Hebrew name of the dish.
- If there is no recipe in it at all, answer {"is_recipe":false}.
Answer ONLY with JSON of this shape:
{"is_recipe":true,"name_he":"...","servings":1,"ingredients_he":["..."],"steps_he":["..."],"minutes":0,"grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0}
"""

    /** "https://youtu.be/ID", ".../shorts/ID", "m.youtube.com/watch?v=ID" -> "https://www.youtube.com/watch?v=ID"; null when not YouTube. */
    fun youtubeUrl(raw: String): String? {
        val s = raw.trim()
        val m = Regex("(?:youtube\\.com/(?:watch\\?(?:[^ ]*&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{6,20})").find(s) ?: return null
        return "https://www.youtube.com/watch?v=" + m.groupValues[1]
    }

    /** Any http(s) link in [raw] (the first one), or null. */
    fun linkIn(raw: String): String? = Regex("https?://\\S+").find(raw)?.value

    suspend fun fromImages(images: List<ByteArray>): Result {
        if (images.isEmpty()) return Result.Failed
        override?.let { return it("images:" + images.size) }
        if (!AiClient.enabled) return Result.NoKey
        val parts = ArrayList<AiClient.Part>()
        parts.add(AiClient.Part.Text("The recipe is in these ${images.size} picture(s)."))
        for (b in images.take(4)) parts.add(AiClient.Part.Image(b))
        return ask { AiClient.generateJson(parts, SYSTEM.trimIndent(), AiClient.Tier.MAIN, temperature = 0.2) }
    }

    /** A YouTube link (watched by Gemini); any other link is [Result.UnsupportedLink]. */
    suspend fun fromLink(raw: String): Result {
        val yt = youtubeUrl(raw) ?: return Result.UnsupportedLink
        override?.let { return it("video:$yt") }
        if (!AiClient.enabled) return Result.NoKey
        val parts = listOf(
            AiClient.Part.Video(yt),
            AiClient.Part.Text("The recipe is in this cooking video (what is said and what is shown).")
        )
        return ask { AiClient.generateJsonVideo(parts, SYSTEM.trimIndent(), temperature = 0.2) }
    }

    /** Text he pasted (a caption, a page). A pasted YouTube link goes to [fromLink]. */
    suspend fun fromText(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Failed
        if (youtubeUrl(t) != null && t.length < 200) return fromLink(t)
        if (linkIn(t) != null && t.length < 200) return Result.UnsupportedLink
        override?.let { return it("text:" + t.take(40)) }
        if (!AiClient.enabled) return Result.NoKey
        val parts = listOf(AiClient.Part.Text("The recipe text:\n" + t.take(6000)))
        return ask { AiClient.generateJson(parts, SYSTEM.trimIndent(), AiClient.Tier.MAIN, temperature = 0.2) }
    }

    /** The HTTP call may block a thread, so it runs in the app scope and we only wait [TIMEOUT_MS] for it. */
    private suspend fun ask(block: suspend () -> JSONObject?): Result {
        val job = AppGraph.scope.async { runCatching { block() }.getOrNull() }
        val root = withTimeoutOrNull(TIMEOUT_MS) { job.await() }
        if (root == null) {
            job.cancel()
            return Result.Failed
        }
        return parse(root)
    }

    private fun num(o: JSONObject, key: String): Double {
        val v = o.optDouble(key, 0.0)
        return if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
    }

    private fun lines(o: JSONObject, key: String, max: Int, maxLen: Int): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i, "").trim()
            if (CoachNotes.isCleanHebrew(s, maxLen)) out.add(s)
            if (out.size == max) break
        }
        return out
    }

    private fun r1(v: Float): Float = (v * 10f).roundToInt() / 10f

    /** Validates the answer: Hebrew only, one portion, kcal consistent with the macros. */
    fun parse(root: JSONObject): Result {
        if (root.has("is_recipe") && !root.optBoolean("is_recipe", true)) return Result.NotRecipe
        val name = root.optString("name_he", "").trim()
        if (!CoachNotes.isCleanHebrew(name, 60)) return Result.Failed
        val ingredients = lines(root, "ingredients_he", 15, 80)
        val steps = lines(root, "steps_he", 10, 220)
        if (ingredients.isEmpty() && steps.isEmpty()) return Result.NotRecipe
        val p = num(root, "protein_g").toFloat().coerceAtMost(200f)
        val c = num(root, "carbs_g").toFloat().coerceAtMost(300f)
        val f = num(root, "fat_g").toFloat().coerceAtMost(150f)
        val fromMacros = (p * 4f + c * 4f + f * 9f).roundToInt()
        var kcal = num(root, "kcal").roundToInt()
        if (kcal <= 0 || (fromMacros > 0 && abs(kcal - fromMacros) > fromMacros * 0.35f)) kcal = fromMacros
        kcal = kcal.coerceIn(0, 3000)
        return Result.Ok(
            ChefRecipe(
                nameHe = name, kcal = kcal, proteinG = r1(p), carbsG = r1(c), fatG = r1(f),
                grams = num(root, "grams").roundToInt().coerceIn(0, 3000),
                minutes = num(root, "minutes").roundToInt().coerceIn(0, 600),
                ingredientsHe = ingredients, stepsHe = steps,
                servings = num(root, "servings").roundToInt().coerceIn(1, 50)
            )
        )
    }

    /** The message under the import buttons for a result that did not fill the form. */
    fun messageHe(r: Result): String = when (r) {
        is Result.Ok -> "המתכון מולא למטה. כדאי לעבור עליו ואז לשמור."
        Result.NotRecipe -> "לא מצאתי שם מתכון."
        Result.UnsupportedLink -> "קישורים מאינסטגרם או טיקטוק אי אפשר לקרוא. אפשר צילום מסך של המתכון, או להעתיק את הטקסט שלו ולהדביק כאן."
        Result.NoKey -> "אין חיבור לבינה מלאכותית בגרסה הזאת."
        Result.Failed -> "הקריאה לא הצליחה. אפשר לנסות שוב, או צילום מסך במקום קישור."
    }
}
