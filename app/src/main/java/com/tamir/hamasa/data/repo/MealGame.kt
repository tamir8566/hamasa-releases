package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.domain.game.BadgeCatalog

/**
 * Stage 2 F game hooks of the food log:
 *  - saving a photo meal: "הניחוש הנכון" counter and one hit on the sly merchant (when he is the boss of the week);
 *  - closing the day (HomeViewModel.closeDay, or the 21:00 worker): "+15 צילום כל הארוחות" and "ציד קלוריות".
 * Every reward is given once per day through Prefs markers. Nothing here throws to the caller.
 */
object MealGame {

    const val MERCHANT_ID = "sly_merchant"

    /** What saving one meal did, for the food screen's short line. */
    data class SaveResult(val goodGuess: Boolean, val merchantDamage: Int, val merchantDefeated: Boolean)

    /** Call after a meal was inserted. Only source == "photo" counts. */
    suspend fun onMealSaved(meal: MealEntity): SaveResult {
        if (meal.source != "photo") return SaveResult(false, 0, false)
        val good = Nutrition.isGoodGuess(meal.aiKcal, meal.kcal)
        if (good) {
            AppGraph.prefs.update { it.copy(goodGuessMeals = it.goodGuessMeals + 1) }
            if (AppGraph.prefs.current().goodGuessMeals >= BadgeCatalog.RIGHT_GUESS_MEALS) {
                runCatching { AppGraph.game.unlockBadge("right_guess") }
            }
        }
        val hit = runCatching { hitMerchant(meal.dateEpochDay) }.getOrNull()
        return SaveResult(good, hit?.first ?: 0, hit?.second ?: false)
    }

    /**
     * One hit on the weekly boss when it is the sly merchant and still standing.
     * Returns (damage, defeatedNow) or null when there was nothing to hit. Same row and reward as BossFights.
     */
    suspend fun hitMerchant(day: Long): Pair<Int, Boolean>? {
        val (boss, progress) = BossFights.forDay(day)
        if (boss.id != MERCHANT_ID || progress.defeated) return null
        val dmg = Nutrition.MERCHANT_PHOTO_DAMAGE.coerceAtMost(progress.hp)
        if (dmg <= 0) return null
        val hp = progress.hp - dmg
        val defeated = hp == 0
        AppGraph.db.game().upsertBoss(progress.copy(hp = hp, defeated = defeated))
        if (defeated) {
            AppGraph.prefs.update { it.copy(bossesDefeatedTotal = it.bossesDefeatedTotal + 1) }
            AppGraph.game.grant(200, 50, "boss:${boss.id}")
        }
        return dmg to defeated
    }

    /** Is the merchant the boss of [day]'s week and still standing? (For the food screen hint.) */
    suspend fun merchantActive(day: Long): Boolean = runCatching {
        val (boss, progress) = BossFights.forDay(day)
        boss.id == MERCHANT_ID && !progress.defeated
    }.getOrDefault(false)

    /** Rewards of a closed day, each at most once per day. Returns short Hebrew lines of what was given. */
    suspend fun settleDay(day: Long = Days.today()): List<String> {
        val out = ArrayList<String>()
        val meals = AppGraph.db.meals().forDayOnce(day)
        if (meals.isEmpty()) return out
        val p = AppGraph.prefs.current()

        if (p.lastPhotoDayBonus != day && Nutrition.allPhotographed(meals.map { it.source })) {
            var first = false
            AppGraph.prefs.update { cur ->
                if (cur.lastPhotoDayBonus == day) cur else { first = true; cur.copy(lastPhotoDayBonus = day) }
            }
            if (first) {
                AppGraph.game.grant(Nutrition.PHOTO_DAY_XP, 0, "meal_photos:$day")
                out += "כל הארוחות צולמו. +${Nutrition.PHOTO_DAY_XP} ניסיון."
            }
        }

        val total = meals.sumOf { it.kcal }
        if (p.lastCalorieHuntDay != day && Nutrition.huntWon(total, p.kcalGoal, meals.size)) {
            var first = false
            AppGraph.prefs.update { cur ->
                if (cur.lastCalorieHuntDay == day) cur else { first = true; cur.copy(lastCalorieHuntDay = day) }
            }
            if (first) {
                AppGraph.game.grant(Nutrition.HUNT_XP, Nutrition.HUNT_GOLD, "calorie_hunt:$day")
                out += "ציד הקלוריות הצליח. +${Nutrition.HUNT_XP} ניסיון, +${Nutrition.HUNT_GOLD} זהב."
            }
        }
        if (out.isNotEmpty()) runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
        return out
    }
}
