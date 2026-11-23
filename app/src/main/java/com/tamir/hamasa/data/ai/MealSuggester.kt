package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.repo.EnergyData
import com.tamir.hamasa.data.repo.SavedMeal
import com.tamir.hamasa.data.repo.SavedMeals
import com.tamir.hamasa.domain.energy.GoalDirection
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.domain.game.Addr
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * "מה לאכול עכשיו?": three options for the next meal that fit what is left of today's kcal and macros,
 * protein first. Gemini phrases realistic Israeli options from the facts (remaining budget, time of day, his saved
 * and frequent meals); offline / no key / slow / bad answer -> his saved meals that fit, else one factual line.
 * Never throws, never blocks longer than [AI_TIMEOUT_MS].
 */
object MealSuggester {

    // the proxy answers a 3-option JSON in ~10-25 s; 15 s cut it off and showed "no answer" while the server was fine
    private const val AI_TIMEOUT_MS = 45_000L
    /** Below this many kcal left there is nothing worth planning. */
    const val MIN_BUDGET = 120

    data class Suggestion(
        val nameHe: String,
        val descriptionHe: String,
        val grams: Int,
        val kcal: Int,
        val proteinG: Float,
        val carbsG: Float,
        val fatG: Float,
        val whyHe: String,
        /** Set when the option is one of his saved meals (offline fallback). */
        val savedMealId: Long? = null
    )

    /** [line] is a short Hebrew line above the options (or instead of them). */
    data class Result(val options: List<Suggestion>, val line: String, val fromAi: Boolean)

    /** What is left of today. Negative values are clamped to 0. */
    data class Budget(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int)

    fun budget(targets: MacroTargets, meals: List<MealEntity>): Budget = Budget(
        kcal = (targets.kcal - meals.sumOf { it.kcal }).coerceAtLeast(0),
        proteinG = (targets.proteinG - meals.sumOf { it.proteinG.toDouble() }.roundToInt()).coerceAtLeast(0),
        carbsG = (targets.carbsG - meals.sumOf { it.carbsG.toDouble() }.roundToInt()).coerceAtLeast(0),
        fatG = (targets.fatG - meals.sumOf { it.fatG.toDouble() }.roundToInt()).coerceAtLeast(0)
    )

    private const val SYSTEM = """
You are a practical sports nutritionist inside a personal Hebrew fitness app in Israel.
The user is a man who lifts weights. His current goal (cutting, maintaining or lean gaining) is given in the facts;
fit the options to it. Suggest exactly 3 options for his NEXT meal, based only on the facts given.
Rules, in order of priority:
1. Each option must fit the remaining calories of today (never above them). Use most of the budget only if it is the
   last meal of the day; otherwise a normal meal size for the time of day.
2. Protein first: each option should be high in protein and cover a large part of the remaining protein.
3. Realistic Israeli food he can make or buy now, fitting the time of day. Prefer foods he already eats (listed),
   but make the 3 options different from each other (for example: one he already eats, one quick with no cooking,
   one cooked or bought).
4. Stay inside the remaining carbs and fat when possible.
All text fields in Hebrew only: no English letters, no emojis. Numbers are numbers, never strings.
name_he: short dish name. description_he: the portions in grams or units, one short line.
why_he: one short factual sentence on why it fits now. grams: total weight of the portion.
Answer ONLY with JSON of exactly this shape:
{"options":[{"name_he":"...","description_he":"...","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"why_he":"..."}]}
"""

    private fun mealTimeEn(hour: Int): String = when (hour) {
        in 4..10 -> "breakfast"
        in 11..15 -> "lunch"
        in 16..18 -> "afternoon snack"
        in 19..21 -> "dinner"
        else -> "late night snack"
    }

    /** Which meal he is about to eat: picked by him before the suggestion (the time of day is only a default). */
    enum class MealKind(val he: String) { BREAKFAST("בוקר"), LUNCH("צהריים"), DINNER("ערב"), SNACK("ביניים") }

    /** The default chip for now: breakfast until 11, lunch until 16, snack until 18, then dinner. */
    fun defaultKind(hour: Int = LocalTime.now().hour): MealKind = when (hour) {
        in 4..10 -> MealKind.BREAKFAST
        in 11..15 -> MealKind.LUNCH
        in 16..17 -> MealKind.SNACK
        else -> MealKind.DINNER
    }

    /** What each meal kind is in his kitchen (the owner's own rules, 2026-11-01). */
    internal fun kindRules(kind: MealKind, b: Budget): String = when (kind) {
        MealKind.BREAKFAST -> "This is BREAKFAST: lighter food built around cheeses, bread, eggs, yogurt, vegetables, spreads. No meat dishes. " +
            "A normal breakfast size, not the whole remaining budget."
        MealKind.LUNCH -> "This is LUNCH: the main meal of the day, with meat / chicken / fish and a side (rice, potatoes, pasta, legumes) and vegetables. " +
            "The biggest meal of the day, but leave room for dinner."
        MealKind.SNACK -> "This is a SNACK between meals: small and quick, protein-leaning (yogurt, cottage, fruit with nuts, a protein shake), 150-350 kcal."
        MealKind.DINNER -> "This is DINNER, the LAST meal of the day: lighter food built around cheeses, bread, eggs, omelette, shakshuka, yogurt, salads. No heavy meat dishes. " +
            "MOST IMPORTANT: dinner must CLOSE the day: each option should land close to ALL the remaining ${b.kcal} kcal (within about 10% below, never above) " +
            "and cover as much as possible of the remaining ${b.proteinG} g protein. Carbs and fat are secondary: exceeding their remaining amounts is fine if that is what it takes to hit the calories and protein."
    }

    /** Main entry. [meals] are today's meals; [saved] his saved meals; [kind] the meal he picked. */
    suspend fun suggest(targets: MacroTargets, meals: List<MealEntity>, saved: List<SavedMeal>, bodyweightKg: Float, kind: MealKind = defaultKind()): Result {
        val b = budget(targets, meals)
        if (b.kcal < MIN_BUDGET) {
            return Result(emptyList(), "נשארו ${b.kcal} קלוריות להיום. היעד כמעט סגור. אם יש רעב: ירקות, מים, או משהו חלבוני וקל.", false)
        }
        if (!AiClient.enabled) return offline(b, saved, noKey = true)

        val frequent = SavedMeals.frequentFoods()
        val profile = runCatching { AppGraph.prefs.current() }.getOrNull()
        val prompt = buildPrompt(targets, b, meals, saved, frequent, bodyweightKg, profile) + "\n" + kindRules(kind, b) + "\n"
        // The HTTP call blocks a thread, so it runs outside this coroutine and we only wait [AI_TIMEOUT_MS] for it.
        val job = AppGraph.scope.async {
            val sys = if (Addr.isFemale()) SYSTEM.trimIndent() + "\nCorrection: the user is a woman (not a man). Write any Hebrew that addresses her in female grammar." else SYSTEM.trimIndent()
            runCatching { AiClient.generateJson(listOf(AiClient.Part.Text(prompt)), sys, AiClient.Tier.LITE) }.getOrNull()
        }
        val root = withTimeoutOrNull(AI_TIMEOUT_MS) { job.await() }
        if (root == null) {
            job.cancel()
            return offline(b, saved, noKey = false)
        }
        val options = parse(root, b)
        if (options.isEmpty()) return offline(b, saved, noKey = false)
        val head = when (kind) {
            MealKind.DINNER -> "ארוחת ערב שסוגרת את היום: נשארו ${b.kcal} קלוריות ו-${b.proteinG} גרם חלבון."
            MealKind.BREAKFAST -> "ארוחת בוקר. נשארו להיום ${b.kcal} קלוריות ו-${b.proteinG} גרם חלבון."
            MealKind.LUNCH -> "ארוחת צהריים. נשארו להיום ${b.kcal} קלוריות ו-${b.proteinG} גרם חלבון."
            MealKind.SNACK -> "משהו קטן בין הארוחות. נשארו להיום ${b.kcal} קלוריות ו-${b.proteinG} גרם חלבון."
        }
        return Result(options, "$head שלוש אפשרויות:", true)
    }

    private fun buildPrompt(
        t: MacroTargets, b: Budget, meals: List<MealEntity>, saved: List<SavedMeal>,
        frequent: List<SavedMeals.Frequent>, bodyweightKg: Float, p: Profile?
    ): String {
        val now = LocalTime.now()
        val dir = if (p != null) EnergyData.directionOf(p, bodyweightKg) else GoalDirection.CUT
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Time now: %02d:%02d (%s).\n", now.hour, now.minute, mealTimeEn(now.hour)))
        sb.append(String.format(Locale.US, "Body weight now: %.1f kg.\n", bodyweightKg))
        if (p != null) {
            sb.append(goalLine(p, bodyweightKg))
            if (p.age in 14..100) sb.append("Age: ${p.age}.\n")
        }
        sb.append("Daily goals: ${t.kcal} kcal, protein ${t.proteinG} g, carbs ${t.carbsG} g, fat ${t.fatG} g.\n")
        sb.append("Remaining for today: ${b.kcal} kcal, protein ${b.proteinG} g, carbs ${b.carbsG} g, fat ${b.fatG} g.\n")
        if (b.proteinG == 0) {
            if (dir == GoalDirection.GAIN) sb.append("The protein goal is already met today; fill the remaining calories with carbs and healthy fats.\n")
            else sb.append("The protein goal is already met today; keep the options light and lean.\n")
        }
        if (meals.isEmpty()) sb.append("Eaten today: nothing yet.\n")
        else {
            sb.append("Eaten today:\n")
            for (m in meals.takeLast(8)) sb.append("- ${m.description.take(60)}: ${m.kcal} kcal, protein ${m.proteinG.roundToInt()} g\n")
        }
        if (saved.isNotEmpty()) {
            sb.append("His saved regular meals:\n")
            for (s in saved.take(10)) sb.append("- ${s.nameHe}: ${s.scaledKcal} kcal, protein ${s.scaledProtein.roundToInt()} g\n")
        }
        if (frequent.isNotEmpty()) {
            sb.append("Foods he logs often (last weeks):\n")
            for (f in frequent) sb.append("- ${f.descriptionHe} (${f.times} times, about ${f.avgKcal} kcal, protein ${f.avgProtein.roundToInt()} g)\n")
        }
        sb.append("Give 3 options for the next meal now.")
        return sb.toString()
    }

    /** The goal line of the prompt, from the direction in force (EnergyData.directionOf). */
    internal fun goalLine(p: Profile, bodyweightKg: Float): String = when (EnergyData.directionOf(p, bodyweightKg)) {
        GoalDirection.CUT -> String.format(Locale.US, "Goal: cutting (losing fat while keeping muscle), from %.0f kg toward %.0f kg.\n", p.startWeightKg, p.goalWeightKg)
        GoalDirection.MAINTAIN -> String.format(Locale.US, "Goal: maintaining the current weight (goal weight %.0f kg) while building strength.\n", p.goalWeightKg)
        GoalDirection.GAIN -> String.format(Locale.US, "Goal: lean gaining (building muscle with a small calorie surplus), from %.0f kg toward %.0f kg. Do not make the options artificially light.\n", p.startWeightKg, p.goalWeightKg)
    }

    private fun num(o: JSONObject, key: String): Double {
        val v = o.optDouble(key, 0.0)
        return if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
    }

    private fun r1(v: Float): Float = (v * 10f).roundToInt() / 10f

    /** Validates the model's answer: clean Hebrew names, numbers clamped, kcal consistent with macros and budget. */
    fun parse(root: JSONObject, b: Budget): List<Suggestion> {
        val arr = root.optJSONArray("options") ?: return emptyList()
        val out = ArrayList<Suggestion>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name_he", "").trim()
            if (!CoachNotes.isCleanHebrew(name, 40)) continue
            val desc = o.optString("description_he", "").trim().let { if (CoachNotes.isCleanHebrew(it, 140)) it else "" }
            val why = o.optString("why_he", "").trim().let { if (CoachNotes.isCleanHebrew(it, 160)) it else "" }
            val p = num(o, "protein_g").toFloat().coerceAtMost(150f)
            val c = num(o, "carbs_g").toFloat().coerceAtMost(250f)
            val f = num(o, "fat_g").toFloat().coerceAtMost(100f)
            val fromMacros = (p * 4f + c * 4f + f * 9f).roundToInt()
            var kcal = num(o, "kcal").roundToInt()
            // a kcal number far from its own macros is not trusted
            if (kcal <= 0 || (fromMacros > 0 && abs(kcal - fromMacros) > fromMacros * 0.35f)) kcal = fromMacros
            kcal = kcal.coerceIn(0, 2000)
            if (kcal < 40) continue
            // over the remaining budget (with a small margin) is not an option for today
            if (kcal > b.kcal + 80) continue
            val grams = num(o, "grams").roundToInt().coerceIn(0, 2000)
            out.add(Suggestion(name, desc, grams, kcal, r1(p), r1(c), r1(f), why))
            if (out.size == 3) break
        }
        return out
    }

    /** Offline: his saved meals that fit the remaining kcal, the most protein per kcal first. */
    fun offline(b: Budget, saved: List<SavedMeal>, noKey: Boolean): Result {
        val fit = saved
            .filter { it.scaledKcal in 1..(b.kcal + 50) }
            .sortedByDescending { it.scaledProtein / it.scaledKcal.coerceAtLeast(1) }
            .take(3)
            .map { s ->
                Suggestion(
                    nameHe = s.nameHe,
                    descriptionHe = if (s.multiplier == 1f) "ארוחה קבועה" else "ארוחה קבועה, מנה ×${SavedMeals.multLabel(s.multiplier)}",
                    grams = s.scaledItems().sumOf { it.grams },
                    kcal = s.scaledKcal, proteinG = s.scaledProtein, carbsG = s.scaledCarbs, fatG = s.scaledFat,
                    whyHe = "נכנס בתקציב של היום.",
                    savedMealId = s.id
                )
            }
        val head = if (noKey) "ההמלצה החכמה כבויה כרגע." else "אין תשובה מהשרת. אולי אין חיבור."
        return if (fit.isNotEmpty()) {
            Result(fit, "$head מהארוחות הקבועות שלך, אלה נכנסות ב-${b.kcal} הקלוריות שנשארו:", false)
        } else {
            Result(emptyList(), "$head נשארו ${b.kcal} קלוריות ו-${b.proteinG} גרם חלבון. כדאי משהו חלבוני שנכנס בזה.", false)
        }
    }
}
