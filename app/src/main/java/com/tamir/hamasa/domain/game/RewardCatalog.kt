package com.tamir.hamasa.domain.game

/**
 * Suggested real-life rewards for the shop (docs/shop_rewards.md has the pricing logic and the weekly gold estimate).
 * They are added ONCE to his own editable list (Room `rewards`, synced to Supabase like the rest) by
 * ShopViewModel, skipping any name he already has, so nothing is duplicated and his own rewards stay as they are.
 *
 * Weekly gold income at Tamir's schedule (2 gym + 1 yoga + 3 mini, chests, challenge, boss) is about 260.
 * Tiers relative to it: small = 1-2 days, medium = 1-2 weeks, big = 1-2 months, legendary = milestone gated.
 * A legendary reward opens when the LOWEST weigh-in ever reaches [Entry.maxWeightKg] or the level reaches
 * [Entry.minLevel] (whichever comes first). Using the lowest weigh-in ever means a gate never closes again.
 * Gates match the reward by name; if he renames it, it simply behaves like any of his own rewards.
 */
object RewardCatalog {

    enum class Tier(val nameHe: String) {
        SMALL("קטן"),
        MEDIUM("בינוני"),
        BIG("גדול"),
        LEGENDARY("אגדי")
    }

    data class Entry(
        val key: String,
        /** May carry {u:male|female} player tokens; read it through [nameHe]. */
        private val nameRaw: String,
        val priceGold: Int,
        val noteHe: String,
        val tier: Tier,
        /** Legendary gate: opens at this weight or lower (0 = no weight gate). */
        val maxWeightKg: Float = 0f,
        /** Legendary gate: or at this level (0 = no level gate). */
        val minLevel: Int = 0
    ) {
        /** The name in the player's grammar (Addr), as it is added to his list. */
        val nameHe: String get() = Addr.x(nameRaw)

        /** True when [normalized] is this entry's name in either grammar (a list seeded earlier may hold the other one). */
        fun matchesName(normalized: String): Boolean =
            RewardCatalog.norm(Addr.male(nameRaw)) == normalized || RewardCatalog.norm(RewardCatalog.femaleText(nameRaw)) == normalized
    }

    /** Estimated weekly gold at Tamir's schedule (see docs/shop_rewards.md). */
    const val WEEKLY_GOLD_ESTIMATE = 260

    val all: List<Entry> = listOf(
        // ----- small: 1-2 days of gold
        Entry("coffee_out", "קפה טוב בחוץ", 60, "בית קפה אמיתי, לא מכונה", Tier.SMALL),
        Entry("extra_episode", "פרק נוסף בסדרה בלי אשמה", 50, "אחד. לא עונה", Tier.SMALL),
        Entry("game_hour", "שעת משחק נוספת", 60, "שעה אחת, עם שעון", Tier.SMALL),
        Entry("protein_bar", "חטיף חלבון {u:שאתה אוהב|שאת אוהבת}", 50, "הטוב, לא הזול", Tier.SMALL),
        Entry("fruit_shake", "שייק פירות בחוץ", 70, "", Tier.SMALL),
        // ----- medium: 1-2 weeks
        Entry("training_shirt", "חולצת אימון חדשה", 350, "", Tier.MEDIUM),
        Entry("book", "ספר חדש", 300, "נייר או קורא, לבחירתך", Tier.MEDIUM),
        Entry("game_sale", "משחק בהנחה", 450, "", Tier.MEDIUM),
        Entry("training_shorts", "מכנסי אימון חדשים", 450, "", Tier.MEDIUM),
        Entry("gym_gloves", "כפפות או רצועות למכון", 300, "", Tier.MEDIUM),
        Entry("burger_out", "המבורגר טוב במסעדה", 400, "נרשם כפינוק, לא ככישלון", Tier.MEDIUM),
        Entry("cinema", "סרט בקולנוע עם חבר", 400, "", Tier.MEDIUM),
        // ----- big: 1-2 months
        Entry("running_shoes", "נעלי ריצה", 1800, "", Tier.BIG),
        Entry("steak_dinner", "סטייק במסעדה", 1400, "המסעדה הטובה, לא המהירה", Tier.BIG),
        Entry("sport_earbuds", "אוזניות ספורט", 2000, "", Tier.BIG),
        Entry("full_price_game", "משחק חדש במחיר מלא", 1500, "", Tier.BIG),
        Entry("new_hoodie", "קפוצ'ון או ג'קט חדש", 1300, "", Tier.BIG),
        // ----- legendary: milestone gated
        Entry("new_sport_month", "חודש בספורט חדש", 2500, "אגרוף, טיפוס או שחייה", Tier.LEGENDARY, maxWeightKg = 100f),
        Entry("smaller_clothes", "בגדים חדשים במידה קטנה", 3000, "המידה הישנה הולכת לתרומה", Tier.LEGENDARY, maxWeightKg = 95f, minLevel = 15),
        Entry("show_event", "הופעה או אירוע", 3000, "כרטיס למשהו שמחכים לו", Tier.LEGENDARY, maxWeightKg = 95f, minLevel = 15),
        Entry("watch_jewelry", "שעון או תכשיט", 3500, "משהו שנשאר. כמו התוצאה", Tier.LEGENDARY, maxWeightKg = 92f, minLevel = 18),
        Entry("chef_dinner_90", "ארוחת שף חגיגית ב־90 קילו", 4000, "היעד. כולו", Tier.LEGENDARY, maxWeightKg = 90f)
    )

    private fun norm(s: String): String = s.trim().replace("\\s+".toRegex(), " ")

    private val token = Regex("\\{u:([^|{}]*)\\|([^|{}]*)\\}")

    /** Every {u:male|female} token at its FEMALE text, whatever the current choice. */
    private fun femaleText(s: String): String = if (!s.contains("{u:")) s else token.replace(s) { m -> m.groupValues[2] }

    fun byName(nameHe: String): Entry? {
        val n = norm(nameHe)
        return all.firstOrNull { it.matchesName(n) }
    }

    /** Is the gate of [e] open? [minWeightKg] = lowest weigh-in ever (<= 0 = no weigh-in yet). */
    fun isOpen(e: Entry, minWeightKg: Float, level: Int): Boolean {
        if (e.tier != Tier.LEGENDARY) return true
        val byWeight = e.maxWeightKg > 0f && minWeightKg > 0f && minWeightKg <= e.maxWeightKg
        val byLevel = e.minLevel > 0 && level >= e.minLevel
        return byWeight || byLevel || (e.maxWeightKg <= 0f && e.minLevel <= 0)
    }

    /** "נפתח במשקל 95 ק"ג או ברמה 15" */
    fun gateHe(e: Entry): String {
        val parts = ArrayList<String>()
        if (e.maxWeightKg > 0f) parts.add("במשקל ${e.maxWeightKg.toInt()} ק\"ג")
        if (e.minLevel > 0) parts.add("ברמה ${e.minLevel}")
        return if (parts.isEmpty()) "" else "נפתח " + parts.joinToString(" או ")
    }

    /** Shambiuluk when a legendary reward is still locked. */
    fun lockedLineHe(e: Entry): String = gateHe(e) + ". המחיר לא בזהב בלבד. גם בקילוגרמים."
}
