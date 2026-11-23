package com.tamir.hamasa.domain.food

import kotlin.math.abs
import kotlin.math.roundToInt

/** Daily targets: kcal and grams of protein / carbs / fat. */
data class MacroTargets(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int)

/** One recognized (or manually added) food item of a meal. */
data class FoodItem(
    val nameHe: String,
    val grams: Int,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    /** 0..1 from the model; 1 for items typed by hand. */
    val confidence: Float = 1f
) {
    /** Same food, new portion: kcal and macros scale with the grams. Items with 0 g keep their numbers. */
    fun withGrams(newGrams: Int): FoodItem {
        val g = newGrams.coerceIn(0, 5000)
        if (grams <= 0) return copy(grams = g)
        val f = g.toFloat() / grams.toFloat()
        return copy(
            grams = g,
            kcal = (kcal * f).roundToInt(),
            proteinG = round1(proteinG * f),
            carbsG = round1(carbsG * f),
            fatG = round1(fatG * f)
        )
    }
}

/** Pure nutrition rules (stage 2 F). */
object Nutrition {
    /** Protein grams per kg of body weight for the automatic goal. */
    const val PROTEIN_PER_KG = 1.6f
    /** Share of the kcal goal that goes to fat in the automatic goal. */
    const val FAT_SHARE = 0.30f
    /** "ציד קלוריות": the day lands within this many kcal of the goal. */
    const val HUNT_RANGE = 100
    const val HUNT_MIN_MEALS = 2
    const val HUNT_XP = 20
    const val HUNT_GOLD = 5
    /** "+15 צילום כל הארוחות". */
    const val PHOTO_DAY_MIN_MEALS = 3
    const val PHOTO_DAY_XP = 15
    /** A photo estimate counts as a good guess when the saved total is within this share of the AI total. */
    const val GOOD_GUESS_SHARE = 0.10f
    /** Damage of one saved photo meal to the sly merchant. */
    const val MERCHANT_PHOTO_DAMAGE = 3

    /**
     * Goals from the energy model (domain/energy/EnergyModel.macroTargets): protein by (lean / reference)
     * weight and [direction] ("הכיוון", EnergyData.directionOf), fat 25-30% of kcal, carbs the rest. A goal stored as > 0 (edited by the user) wins over the automatic value.
     * [heightCm] and [bodyFatPct] (0 = unknown) refine the automatic protein.
     */
    fun targets(
        kcalGoal: Int, bodyweightKg: Float, proteinGoal: Int, carbsGoal: Int, fatGoal: Int,
        heightCm: Int = 0, bodyFatPct: Float = 0f,
        direction: com.tamir.hamasa.domain.energy.GoalDirection = com.tamir.hamasa.domain.energy.GoalDirection.CUT
    ): MacroTargets {
        val w = bodyweightKg.coerceAtLeast(30f)
        return com.tamir.hamasa.domain.energy.EnergyModel.macroTargets(
            kcal = kcalGoal,
            weightKg = w,
            heightCm = heightCm.toFloat(),
            leanMassKg = com.tamir.hamasa.domain.energy.EnergyModel.leanMass(w, bodyFatPct),
            proteinGoal = proteinGoal, carbsGoal = carbsGoal, fatGoal = fatGoal,
            direction = direction
        )
    }

    /** True when [savedKcal] is within 10% of the AI's [aiKcal]. */
    fun isGoodGuess(aiKcal: Int, savedKcal: Int): Boolean =
        aiKcal > 0 && abs(savedKcal - aiKcal) <= aiKcal * GOOD_GUESS_SHARE

    /** "ציד קלוריות" condition for a closed day. */
    fun huntWon(totalKcal: Int, kcalGoal: Int, meals: Int): Boolean =
        meals >= HUNT_MIN_MEALS && abs(totalKcal - kcalGoal) <= HUNT_RANGE

    /** "+15 צילום כל הארוחות" condition. [sources] are the day's MealEntity.source values. */
    fun allPhotographed(sources: List<String>): Boolean =
        sources.size >= PHOTO_DAY_MIN_MEALS && sources.all { it == "photo" }

    fun sumKcal(items: List<FoodItem>): Int = items.sumOf { it.kcal }
    fun sumProtein(items: List<FoodItem>): Float = round1(items.sumOf { it.proteinG.toDouble() }.toFloat())
    fun sumCarbs(items: List<FoodItem>): Float = round1(items.sumOf { it.carbsG.toDouble() }.toFloat())
    fun sumFat(items: List<FoodItem>): Float = round1(items.sumOf { it.fatG.toDouble() }.toFloat())
}

internal fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f
