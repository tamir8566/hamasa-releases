package com.tamir.hamasa.domain.game

import kotlin.random.Random

/**
 * Chest odds.
 *  base:      common 68%, rare 27%, legendary 5%
 *  luck:      +3% legendary per step of the luck chain (luckChain: +1 per session on consecutive days,
 *             back to 0 on a missed day or after a legendary), capped at 35%; rare grows a little too
 *  pity:      chain step 11 (12 consecutive days without a legendary) is guaranteed legendary
 *
 * Common  -> gold only (small).
 * Rare    -> a locked chest-mode (50% when any is left), else gold + a chance of a shield.
 * Legendary -> an unowned item: legendary-rarity ids from [GearCatalog] are preferred when any of
 *              them is in [lockedItems]; otherwise rare ids; otherwise anything in the list.
 *              Always big xp + gold, 25% also a joker.
 *
 * Common-weighted table ([commonWeighted], chests after non-gym sessions: yoga, walk, mini, run):
 *  legendary at 40% of the normal chance, rare 13% (+0.5% per chain step, up to 8). Pity and gear luck still apply.
 */
object ChestRoller {
    private const val BASE_LEGENDARY = 0.05
    private const val LUCK_STEP = 0.03
    private const val MAX_LEGENDARY = 0.35
    private const val PITY_CHAIN = 11          // luckChain >= 11 means this is the 12th chest

    fun legendaryChance(luckChain: Int): Double = (BASE_LEGENDARY + LUCK_STEP * luckChain.coerceAtLeast(0)).coerceAtMost(MAX_LEGENDARY)

    /**
     * [luckPercent] is the equipped-gear CHEST_LUCK buff (0..~20). It moves that many percent
     * points from common toward legendary AND from common toward rare, so it only ever helps.
     */
    fun rarityFor(luckChain: Int, rnd: Random, luckPercent: Float = 0f, commonWeighted: Boolean = false): Rarity {
        if (luckChain >= PITY_CHAIN) return Rarity.LEGENDARY
        val luck = (luckPercent.coerceAtLeast(0f) / 100f).toDouble()
        val legendary = (if (commonWeighted) legendaryChance(luckChain) * 0.4 else legendaryChance(luckChain)) + luck
        val rare = (if (commonWeighted) 0.13 + 0.005 * luckChain.coerceIn(0, 8) else 0.27 + 0.01 * luckChain.coerceIn(0, 8)) + luck
        val r = rnd.nextDouble()
        return when {
            r < legendary -> Rarity.LEGENDARY
            r < legendary + rare -> Rarity.RARE
            else -> Rarity.COMMON
        }
    }

    /** [rareOpen] = false below level 3 (see [LevelGates]): every chest is common until then. */
    fun roll(luckChain: Int, lockedModes: List<String>, lockedItems: List<String>, rnd: Random = Random.Default, luckPercent: Float = 0f, rareOpen: Boolean = true,
             commonWeighted: Boolean = false): ChestDrop {
        val rarity = if (rareOpen) rarityFor(luckChain, rnd, luckPercent, commonWeighted) else Rarity.COMMON
        return when (rarity) {
            Rarity.COMMON -> ChestDrop(Rarity.COMMON, 0, 5 + rnd.nextInt(11), null, null, false, false)

            Rarity.RARE -> {
                if (lockedModes.isNotEmpty() && rnd.nextBoolean()) {
                    ChestDrop(Rarity.RARE, 0, 20, lockedModes.random(rnd), null, false, false)
                } else {
                    ChestDrop(Rarity.RARE, 0, 25 + rnd.nextInt(16), null, null, rnd.nextInt(3) == 0, false)
                }
            }

            Rarity.LEGENDARY -> {
                val item = pickLegendaryItem(lockedItems, rnd)
                if (item != null) {
                    ChestDrop(Rarity.LEGENDARY, 100, 50 + rnd.nextInt(31), null, item, false, rnd.nextInt(4) == 0)
                } else {
                    // nothing left to give: xp + gold + shield + joker
                    ChestDrop(Rarity.LEGENDARY, 100, 80 + rnd.nextInt(41), null, null, true, true)
                }
            }
        }
    }

    /** Prefer legendary gear ids, then rare, then anything in [lockedItems]. */
    private fun pickLegendaryItem(lockedItems: List<String>, rnd: Random): String? {
        if (lockedItems.isEmpty()) return null
        val byRarity = lockedItems.groupBy { GearCatalog.byId(it)?.rarity }
        val legendary = byRarity[Rarity.LEGENDARY].orEmpty()
        if (legendary.isNotEmpty()) return legendary.random(rnd)
        val rare = byRarity[Rarity.RARE].orEmpty()
        if (rare.isNotEmpty()) return rare.random(rnd)
        return lockedItems.random(rnd)
    }
}
