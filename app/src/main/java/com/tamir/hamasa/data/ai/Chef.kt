package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.repo.SavedMeals
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.domain.game.Addr
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "השף" on the food screen: a short chat that helps decide what to cook with what is left of today.
 * Each answer carries up to 2 short recipes (ingredients with amounts, a few steps) and, when his message told
 * something lasting about his taste ("אני לא אוכל דגים"), that fact, which goes into his ChefStore.
 * Everything he taught the chef (facts, liked / cooked recipes, "לא בשבילי") goes into every prompt; that is
 * how the chef learns him. Never throws.
 */
object Chef {

    sealed class Reply {
        data class Ok(val textHe: String, val recipes: List<ChefRecipe>, val learnedHe: List<String>) : Reply()
        /** No AI at all on this phone. */
        object NoKey : Reply()
        /** Offline, timeout, bad answer. */
        object Failed : Reply()
    }

    private const val TIMEOUT_MS = 35_000L

    /** How long the last AI call took (for the log and the settings screen). */
    @Volatile var lastCallMs: Long = 0L

    /** Why the last call failed ("cheap model: Claude: HTTP 404 | main model: timeout"); null after a good answer. */
    @Volatile var lastFailure: String? = null

    /** Tests only: answers [ask] instead of the AI. */
    @Volatile internal var override: (suspend (String) -> Reply)? = null

    /**
     * The meal his message names ("לארוחת ערב", "משהו לצהריים") wins over the chip; null when it names none.
     * Checked in this order so "אחרי ארוחת הבוקר" style phrases stay simple: dinner, lunch, breakfast, snack.
     */
    fun kindFromText(messageHe: String): MealSuggester.MealKind? {
        val words = messageHe.split(Regex("[^\u05d0-\u05ea]+")).filter { it.isNotEmpty() }.toSet()
        fun any(vararg w: String) = w.any { it in words }
        return when {
            any("ערב", "לערב", "בערב", "הערב") -> MealSuggester.MealKind.DINNER
            any("צהריים", "צהרים", "לצהריים", "בצהריים", "הצהריים", "לצהרים") -> MealSuggester.MealKind.LUNCH
            any("בוקר", "לבוקר", "בבוקר") -> MealSuggester.MealKind.BREAKFAST
            any("ביניים", "נשנוש", "חטיף", "לנשנש") -> MealSuggester.MealKind.SNACK
            else -> null
        }
    }

    /** Dinner closes the day: each recipe between [dinnerRange].first and .second kcal (his rule, 2026-11-01). */
    fun dinnerRange(budgetKcal: Int): Pair<Int, Int> =
        Pair((budgetKcal * 0.85f).roundToInt(), budgetKcal + 30)

    /** Recipes that keep the meal's rules (dinner: inside [dinnerRange]); other meals: all. */
    fun fitKind(recipes: List<ChefRecipe>, kind: MealSuggester.MealKind, budgetKcal: Int): List<ChefRecipe> {
        if (kind != MealSuggester.MealKind.DINNER || budgetKcal < 250) return recipes
        val (lo, hi) = dinnerRange(budgetKcal)
        return recipes.filter { it.kcal in lo..hi }
    }

    private const val SYSTEM = """
You are "the chef" inside a personal Hebrew fitness app in Israel: a practical home cook who knows nutrition.
You help the user decide what to make or eat NOW, inside what is left of today's calories and protein.
Rules, in order:
1. NEVER suggest anything that breaks his taste facts or his "not for me" list. They are hard rules.
2. Each recipe must fit the remaining calories of today (never above them) and be high in protein.
3. Follow the MEAL rules given in the facts exactly (breakfast, lunch, snack or dinner). For dinner, the calorie
   range given is a hard rule for every recipe.
4. Simple home food or something easy to buy in Israel; few ingredients; short.
5. VARIETY: the recipes in one answer must be clearly different from each other: a different main protein
   (chicken, turkey, beef, eggs, cheese and dairy, legumes, tofu...), a different cooking style (pan, oven, no
   cooking, sandwich or wrap, bowl) and a different flavor direction. Never repeat a dish from the "recently
   suggested" list. At most one recipe may be one he liked or cooked before; the others should be new to him
   but still inside his taste rules.
6. Answer his actual message: if he asks for something sweet, quick, or with what he has at home, do that.
7. A "Recipe library" may be given: good dishes for him. Base most recipes on it (keep the name when you keep the
   dish; scale the amounts to fit and recalculate kcal and macros honestly). His own liked dishes and taste rules
   still win, and you may still invent a dish when nothing there fits his message.
Recipes: up to 2 (fewer is fine; 0 when he only chats or asks a question). Keep every recipe SHORT, he is
 waiting on his phone: name_he: short dish name. ingredients_he: 2-6 items WITH amounts ("200 גרם חזה עוף").
 steps_he: 2-4 very short steps. minutes: total time. kcal, protein_g, carbs_g, fat_g, grams: numbers.
 why_he: at most 8 words why it fits now.
reply_he: 1-2 short, friendly, plain sentences to him (no recipe details there).
learned_he: lasting facts about his taste that HIS LAST MESSAGE stated (likes, dislikes, foods he does not eat,
 kitchen limits), each a short Hebrew line in third person like "לא אוכל דגים ומאכלי ים". Empty when none.
 Never put there what you suggested or guessed.
All text in Hebrew only: no English letters, no emojis. Numbers are numbers, never strings.
Answer ONLY with JSON of exactly this shape:
{"reply_he":"...","recipes":[{"name_he":"...","ingredients_he":["..."],"steps_he":["..."],"minutes":0,"grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"why_he":"..."}],"learned_he":["..."]}
"""

    /**
     * One turn: [messageHe] is what he typed (or a quick chip). [targets]/[meals] give what is left of today,
     * [kind] the meal he is about to eat.
     */
    suspend fun ask(
        messageHe: String,
        targets: MacroTargets,
        meals: List<MealEntity>,
        bodyweightKg: Float,
        kind: MealSuggester.MealKind
    ): Reply {
        val fake = override
        if (fake != null) return fake(messageHe)
        if (!AiClient.enabled) return Reply.NoKey
        val b = MealSuggester.budget(targets, meals)
        val prompt = buildPrompt(messageHe, targets, b, meals, bodyweightKg, kind)
        val first = call(prompt) ?: return Reply.Failed
        lastFailure = null
        val ok = parse(first, b.kcal)
        if (ok == null) {
            lastFailure = "answer had no usable recipe or text"
            return Reply.Failed
        }
        val fit = fitKind(ok.recipes, kind, b.kcal)
        val result = if (fit.size == ok.recipes.size) ok else {
            // dinner recipes outside the range: one more try with the exact numbers, else keep what fits
            val (lo, hi) = dinnerRange(b.kcal)
            val again = call(
                prompt + "\nYour previous recipes were " + ok.recipes.joinToString(", ") { "${it.kcal} kcal" } +
                    ". That breaks the dinner rule: EVERY recipe must be between $lo and $hi kcal. Answer again."
            )?.let { parse(it, b.kcal) }
            val fit2 = if (again != null) fitKind(again.recipes, kind, b.kcal) else emptyList()
            when {
                again != null && fit2.isNotEmpty() -> again.copy(recipes = fit2)
                fit.isNotEmpty() -> ok.copy(recipes = fit)
                else -> ok
            }
        }
        ChefStore.addRecent(result.recipes.map { it.nameHe })
        return result
    }

    /**
     * One AI call (more creative than the coach: temperature 0.8), null on timeout / failure. The cheap model
     * first; when it fails or times out, the main model once (the one the food photos use, known to work).
     */
    private suspend fun call(prompt: String): JSONObject? {
        val sys = SYSTEM.trimIndent() + if (Addr.isFemale()) "\nThe user is a woman: write any Hebrew that addresses her in female grammar, and the facts as \"לא אוכלת\" etc." else "\nThe user is a man: address him in male grammar."
        val started = System.currentTimeMillis()
        var root = callTier(prompt, sys, AiClient.Tier.LITE)
        if (root == null) {
            lastFailure = "cheap model: " + (AiClient.lastError ?: "timeout")
            root = callTier(prompt, sys, AiClient.Tier.MAIN)
            if (root == null) lastFailure += " | main model: " + (AiClient.lastError ?: "timeout")
        }
        lastCallMs = System.currentTimeMillis() - started
        android.util.Log.i("Chef", "call took ${lastCallMs} ms via ${AiClient.lastProvider}" + (if (root == null) " (failed: $lastFailure)" else ""))
        return root
    }

    private suspend fun callTier(prompt: String, sys: String, tier: AiClient.Tier): JSONObject? {
        val job = AppGraph.scope.async {
            // 2 short recipes fit in far less than 3000 tokens; the cap keeps a rambling answer from running long
            runCatching { AiClient.generateJson(listOf(AiClient.Part.Text(prompt)), sys, tier, temperature = 0.8, maxOutputTokens = 3000) }.getOrNull()
        }
        val root = withTimeoutOrNull(TIMEOUT_MS) { job.await() }
        if (root == null) job.cancel()
        return root
    }

    private suspend fun buildPrompt(
        messageHe: String,
        t: MacroTargets,
        b: MealSuggester.Budget,
        meals: List<MealEntity>,
        bodyweightKg: Float,
        kind: MealSuggester.MealKind
    ): String {
        val s = ChefStore.current()
        val p = runCatching { AppGraph.prefs.current() }.getOrNull()
        val now = LocalTime.now()
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Time now: %02d:%02d.\n", now.hour, now.minute))
        if (p != null) sb.append(MealSuggester.goalLine(p, bodyweightKg))
        sb.append("Daily goals: ${t.kcal} kcal, protein ${t.proteinG} g.\n")
        sb.append("Remaining for today: ${b.kcal} kcal, protein ${b.proteinG} g, carbs ${b.carbsG} g, fat ${b.fatG} g.\n")
        sb.append("MEAL: ${kind.name}. ").append(MealSuggester.kindRules(kind, b)).append("\n")
        if (kind == MealSuggester.MealKind.DINNER && b.kcal >= 250) {
            val (lo, hi) = dinnerRange(b.kcal)
            sb.append("Dinner calorie range (hard rule for EVERY recipe): $lo to $hi kcal, and as much of the remaining ${b.proteinG} g protein as possible.\n")
        }
        if (meals.isNotEmpty()) {
            sb.append("Eaten today:\n")
            for (m in meals.takeLast(6)) sb.append("- ${m.description.take(60)}: ${m.kcal} kcal\n")
        }
        if (s.facts.isNotEmpty()) {
            sb.append("His taste facts (hard rules):\n")
            for (f in s.facts) sb.append("- $f\n")
        }
        if (s.nope.isNotEmpty()) {
            sb.append("Dishes that were NOT for him (never suggest them or close variants):\n")
            for (n in s.nope.takeLast(15)) sb.append("- $n\n")
        }
        val liked = s.book.take(12)
        if (liked.isNotEmpty()) {
            sb.append("Dishes he liked or cooked before:\n")
            for (r in liked) {
                val bits = ArrayList<String>()
                if (r.kcal > 0) bits.add("${r.kcal} kcal")
                if (r.cooked > 0) bits.add("cooked ${r.cooked} times")
                if (r.ingredientsHe.isNotEmpty()) bits.add((if (r.servings > 1) "ingredients for ${r.servings} portions: " else "ingredients: ") + r.ingredientsHe.take(8).joinToString(", "))
                sb.append("- ${r.nameHe}" + (if (bits.isNotEmpty()) " (" + bits.joinToString("; ") + ")" else "") + "\n")
            }
        }
        // a rotating slice of the built-in recipe book, so the answers vary (his own book above still comes first)
        val lib = RecipeLibrary.pick(
            kind, b.kcal,
            skip = s.recent + s.nope + s.book.map { it.nameHe },
            seed = System.currentTimeMillis() / 60_000L
        )
        if (lib.isNotEmpty()) {
            sb.append("Recipe library (tested home recipes he will like; one portion each, amounts can be scaled to fit):\n")
            for (r in lib) sb.append(RecipeLibrary.promptLine(r)).append("\n")
        }
        val frequent = runCatching { SavedMeals.frequentFoods(limit = 6) }.getOrDefault(emptyList())
        if (frequent.isNotEmpty()) {
            sb.append("Foods he logs often:\n")
            for (f in frequent) sb.append("- ${f.descriptionHe} (${f.times} times)\n")
        }
        if (s.recent.isNotEmpty()) {
            sb.append("Recently suggested (do NOT suggest these again): ").append(s.recent.takeLast(20).joinToString(", ")).append(".\n")
        }
        val chat = ChefStore.todayChat().takeLast(6)
        if (chat.isNotEmpty()) {
            sb.append("Today's conversation so far:\n")
            for (l in chat) {
                val who = if (l.fromChef) "Chef" else "User"
                val extra = if (l.recipes.isNotEmpty()) " [suggested: " + l.recipes.joinToString(", ") { it.nameHe } + "]" else ""
                sb.append("$who: ${l.textHe.take(160)}$extra\n")
            }
        }
        sb.append("His message now: ").append(messageHe.trim().take(300))
        return sb.toString()
    }

    private fun num(o: JSONObject, key: String): Double {
        val v = o.optDouble(key, 0.0)
        return if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
    }

    private fun r1(v: Float): Float = (v * 10f).roundToInt() / 10f

    private fun strings(o: JSONObject, key: String, max: Int, maxLen: Int): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i, "").trim()
            if (CoachNotes.isCleanHebrew(s, maxLen)) out.add(s)
            if (out.size == max) break
        }
        return out
    }

    /**
     * Validates the model's answer: Hebrew only, numbers clamped, kcal consistent with the macros, nothing above
     * the remaining [budgetKcal] (+80), no dish he marked "לא בשבילי". Null = nothing usable.
     */
    fun parse(root: JSONObject, budgetKcal: Int): Reply.Ok? {
        val reply = root.optString("reply_he", "").trim().let { if (CoachNotes.isCleanHebrew(it, 300)) it else "" }
        val recipes = ArrayList<ChefRecipe>()
        val arr = root.optJSONArray("recipes")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name_he", "").trim()
                if (!CoachNotes.isCleanHebrew(name, 40)) continue
                if (ChefStore.isNope(name)) continue
                val ingredients = strings(o, "ingredients_he", 10, 60)
                val steps = strings(o, "steps_he", 6, 160)
                if (ingredients.isEmpty()) continue
                val p = num(o, "protein_g").toFloat().coerceAtMost(150f)
                val c = num(o, "carbs_g").toFloat().coerceAtMost(250f)
                val f = num(o, "fat_g").toFloat().coerceAtMost(100f)
                val fromMacros = (p * 4f + c * 4f + f * 9f).roundToInt()
                var kcal = num(o, "kcal").roundToInt()
                if (kcal <= 0 || (fromMacros > 0 && abs(kcal - fromMacros) > fromMacros * 0.35f)) kcal = fromMacros
                kcal = kcal.coerceIn(0, 2000)
                if (kcal < 40) continue
                if (budgetKcal > 0 && kcal > budgetKcal + 80) continue
                val why = o.optString("why_he", "").trim().let { if (CoachNotes.isCleanHebrew(it, 160)) it else "" }
                recipes.add(
                    ChefRecipe(
                        nameHe = name, kcal = kcal, proteinG = r1(p), carbsG = r1(c), fatG = r1(f),
                        grams = num(o, "grams").roundToInt().coerceIn(0, 2000),
                        minutes = num(o, "minutes").roundToInt().coerceIn(0, 240),
                        ingredientsHe = ingredients, stepsHe = steps, whyHe = why
                    )
                )
                if (recipes.size == 3) break
            }
        }
        val learned = strings(root, "learned_he", 5, ChefStore.MAX_FACT_LEN)
        if (reply.isEmpty() && recipes.isEmpty()) return null
        return Reply.Ok(reply.ifEmpty { "הנה מה שמתאים עכשיו." }, recipes, learned)
    }
}
