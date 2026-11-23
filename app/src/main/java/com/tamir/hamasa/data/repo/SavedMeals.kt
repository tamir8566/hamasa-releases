package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.Nutrition
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One "ארוחה קבועה": a meal the user logs again with one tap. [kcal] and the macros are the base (×1) totals;
 * [multiplier] is the portion he picked (0.5, 1, 1.5, 2). [items] may be empty (a meal typed by hand).
 */
data class SavedMeal(
    val id: Long,
    val nameHe: String,
    val items: List<FoodItem>,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val multiplier: Float = 1f
) {
    val scaledKcal: Int get() = (kcal * multiplier).roundToInt()
    val scaledProtein: Float get() = r1(proteinG * multiplier)
    val scaledCarbs: Float get() = r1(carbsG * multiplier)
    val scaledFat: Float get() = r1(fatG * multiplier)

    /** Items at the chosen portion (grams and numbers scaled). */
    fun scaledItems(): List<FoodItem> {
        if (multiplier == 1f) return items
        return items.map { x ->
            x.copy(
                grams = (x.grams * multiplier).roundToInt().coerceIn(0, 5000),
                kcal = (x.kcal * multiplier).roundToInt(),
                proteinG = r1(x.proteinG * multiplier),
                carbsG = r1(x.carbsG * multiplier),
                fatG = r1(x.fatG * multiplier)
            )
        }
    }
}

private fun r1(v: Float): Float = (v * 10f).roundToInt() / 10f

/**
 * "ארוחות קבועות": stored in Prefs.savedMealsJson (synced through CloudSync's profile JSON; no Room table).
 * Nothing here throws to the caller.
 */
object SavedMeals {
    const val MAX = 16
    /** Same description logged this many times -> offer to save it once. */
    const val SUGGEST_AFTER = 3
    val MULTIPLIERS = listOf(0.5f, 1f, 1.5f, 2f)

    fun normalize(s: String): String = s.trim().replace(Regex("\\s+"), " ").lowercase()

    private val MULT_SUFFIX = Regex("\\s*×\\s*[0-9]+(\\.[0-9]+)?$")

    /** Key for the save offer: [normalize] without a portion suffix ("שקשוקה ×2" -> "שקשוקה"). */
    fun offerKey(s: String): String = normalize(s).replace(MULT_SUFFIX, "").trim()

    /** [items] scaled by [ratio] (grams, kcal and macros). */
    fun scaleItems(items: List<FoodItem>, ratio: Float): List<FoodItem> {
        if (ratio == 1f) return items
        return items.map { x ->
            x.copy(
                grams = (x.grams * ratio).roundToInt().coerceIn(0, 5000),
                kcal = (x.kcal * ratio).roundToInt().coerceAtLeast(0),
                proteinG = r1(x.proteinG * ratio),
                carbsG = r1(x.carbsG * ratio),
                fatG = r1(x.fatG * ratio)
            )
        }
    }

    // ------------------------------------------------------------ json

    fun decode(json: String): List<SavedMeal> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<SavedMeal>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                val items = MealEstimator.decodeItems(o.optString("items", ""))
                val kcal = o.optInt("kcal", 0).coerceIn(0, 10000)
                val mult = o.optDouble("mult", 1.0).toFloat().let { m -> if (MULTIPLIERS.contains(m)) m else 1f }
                out.add(
                    SavedMeal(
                        id = o.optLong("id", i.toLong() + 1L),
                        nameHe = name.take(60),
                        items = items,
                        kcal = kcal,
                        proteinG = safeF(o.optDouble("p", 0.0)),
                        carbsG = safeF(o.optDouble("c", 0.0)),
                        fatG = safeF(o.optDouble("f", 0.0)),
                        multiplier = mult
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun safeF(v: Double): Float = if (v.isNaN() || v.isInfinite() || v < 0.0) 0f else r1(v.toFloat().coerceAtMost(1000f))

    fun encode(list: List<SavedMeal>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.nameHe).put("items", MealEstimator.encodeItems(s.items))
                    .put("kcal", s.kcal).put("p", s.proteinG.toDouble()).put("c", s.carbsG.toDouble()).put("f", s.fatG.toDouble())
                    .put("mult", s.multiplier.toDouble())
            )
        }
        return arr.toString()
    }

    suspend fun all(): List<SavedMeal> = decode(AppGraph.prefs.current().savedMealsJson)

    // ------------------------------------------------------------ edits

    /** Saves (or replaces, by the same name) a meal. Returns false when the list is full. */
    suspend fun save(nameHe: String, items: List<FoodItem>, kcal: Int, proteinG: Float, carbsG: Float, fatG: Float): Boolean {
        val name = nameHe.trim().take(60)
        if (name.isEmpty()) return false
        // The meal totals win: they are what the user saw and may have edited by hand. Items that no longer match
        // them (a stale itemsJson after a manual edit) are scaled to the kcal total, or dropped when that is impossible.
        val itemsDiffer = items.isNotEmpty() && (
            abs(Nutrition.sumKcal(items) - kcal) > 2 ||
                abs(Nutrition.sumProtein(items) - proteinG) > 0.5f ||
                abs(Nutrition.sumCarbs(items) - carbsG) > 0.5f ||
                abs(Nutrition.sumFat(items) - fatG) > 0.5f
            )
        val itemKcal = Nutrition.sumKcal(items)
        val useItems: List<FoodItem> = when {
            !itemsDiffer -> items
            itemKcal > 0 && kcal > 0 -> scaleItems(items, kcal.toFloat() / itemKcal)
            else -> emptyList()
        }
        val k = kcal
        val p = proteinG
        val c = carbsG
        val f = fatG
        var ok = true
        AppGraph.prefs.update { cur ->
            val list = decode(cur.savedMealsJson).toMutableList()
            val key = normalize(name)
            val i = list.indexOfFirst { normalize(it.nameHe) == key }
            val nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1L
            val meal = SavedMeal(
                id = if (i >= 0) list[i].id else nextId, nameHe = name, items = useItems,
                kcal = k.coerceIn(0, 10000), proteinG = p.coerceAtLeast(0f), carbsG = c.coerceAtLeast(0f), fatG = f.coerceAtLeast(0f),
                // replacing an existing meal keeps the portion the user picked for it
                multiplier = if (i >= 0) list[i].multiplier else 1f
            )
            if (i >= 0) list[i] = meal
            else if (list.size >= MAX) ok = false
            else list.add(meal)
            // a saved description is never offered again
            val asked = askedSet(cur.savedMealAskedCsv) + key + offerKey(name)
            cur.copy(savedMealsJson = encode(list), savedMealAskedCsv = asked.joinToString("\n"))
        }
        return ok
    }

    suspend fun saveFromMeal(m: MealEntity): Boolean =
        save(m.description, MealEstimator.decodeItems(m.itemsJson), m.kcal, m.proteinG, m.carbsG, m.fatG)

    suspend fun setMultiplier(id: Long, multiplier: Float) {
        val mult = if (MULTIPLIERS.contains(multiplier)) multiplier else 1f
        AppGraph.prefs.update { cur ->
            val list = decode(cur.savedMealsJson).map { if (it.id == id) it.copy(multiplier = mult) else it }
            cur.copy(savedMealsJson = encode(list))
        }
    }

    suspend fun delete(id: Long) {
        AppGraph.prefs.update { cur -> cur.copy(savedMealsJson = encode(decode(cur.savedMealsJson).filter { it.id != id })) }
    }

    /** The meal row a one-tap log inserts. Never "photo": no photo bonuses for a saved meal. */
    fun toMeal(s: SavedMeal, day: Long = Days.today()): MealEntity {
        val items = s.scaledItems()
        val label = if (s.multiplier == 1f) s.nameHe else "${s.nameHe} ×${multLabel(s.multiplier)}"
        return MealEntity(
            dateEpochDay = day, atMillis = System.currentTimeMillis(),
            description = label.take(120),
            kcal = s.scaledKcal.coerceAtLeast(0),
            proteinG = s.scaledProtein, carbsG = s.scaledCarbs, fatG = s.scaledFat,
            source = "manual",
            itemsJson = MealEstimator.encodeItems(items)
        )
    }

    fun multLabel(m: Float): String = when (m) {
        0.5f -> "0.5"
        1.5f -> "1.5"
        2f -> "2"
        else -> "1"
    }

    // ------------------------------------------------------------ "לשמור כארוחה קבועה?" offer

    private fun askedSet(csv: String): Set<String> = csv.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * True when [description] was logged at least [SUGGEST_AFTER] times, is not saved yet and was never offered.
     * Marks it as offered right away (so it is offered once).
     */
    suspend fun shouldOffer(description: String): Boolean = runCatching {
        // "שקשוקה ×2" (a saved meal logged at another portion) counts as "שקשוקה"
        val key = offerKey(description)
        if (key.isEmpty()) return@runCatching false
        val p = AppGraph.prefs.current()
        if (askedSet(p.savedMealAskedCsv).contains(key)) return@runCatching false
        if (decode(p.savedMealsJson).any { offerKey(it.nameHe) == key }) return@runCatching false
        val count = AppGraph.db.meals().allOnce().count { offerKey(it.description) == key }
        if (count < SUGGEST_AFTER) return@runCatching false
        AppGraph.prefs.update { cur -> cur.copy(savedMealAskedCsv = (askedSet(cur.savedMealAskedCsv) + key).joinToString("\n")) }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------ context for the next-meal suggestion

    /** A food he logs often: description, times logged, average kcal and protein. */
    data class Frequent(val descriptionHe: String, val times: Int, val avgKcal: Int, val avgProtein: Float)

    /** Most frequent meal descriptions of the last [days] days (at least twice). */
    suspend fun frequentFoods(limit: Int = 8, days: Int = 45): List<Frequent> = runCatching {
        val from = Days.today() - days
        AppGraph.db.meals().allOnce()
            .filter { it.dateEpochDay >= from && it.description.isNotBlank() }
            .groupBy { normalize(it.description) }
            .filter { it.value.size >= 2 }
            .map { (_, list) ->
                Frequent(
                    descriptionHe = list.last().description.take(60),
                    times = list.size,
                    avgKcal = list.map { it.kcal }.average().roundToInt(),
                    avgProtein = r1(list.map { it.proteinG.toDouble() }.average().toFloat())
                )
            }
            .sortedByDescending { it.times }
            .take(limit)
    }.getOrDefault(emptyList())
}
