package com.tamir.hamasa.domain.game

/**
 * 60 gear items mapped onto the 4 gear sheets (res/drawable-nodpi gear_1..gear_4).
 *
 * Sheet geometry (cols x rows), index is 0-based row-major:
 *  1: 4x3  mixed starter set      (row 1 grey, row 2 blue, row 3 gold)
 *  2: 4x4  head items             (index 0 is a duplicate red cap and is not used)
 *  3: 6x3  body items
 *  4: 4x4  weapons + accessories  (index 0 is a duplicate staff and is not used)
 *
 * Rarity follows the glow drawn around each cell: grey = COMMON, blue = RARE, gold = LEGENDARY.
 * Rare items ask for 1-3 branch levels, legendary 3-5 (branch levels are 0..5).
 *
 * Buffs (passive, only while equipped, always additive): COMMON 1-1.5%, RARE 2.5-3%, LEGENDARY 5%.
 * Slot tendency: weapon -> XP, body -> GOLD, head -> CHEST_LUCK, accessory -> SHIELD_SAVE,
 * with a few exceptions so every buff type exists at every rarity.
 */
object GearCatalog {
    /** sheet number -> (columns, rows). Used by the UI to crop cells. */
    val sheetGrid: Map<Int, Pair<Int, Int>> = mapOf(
        1 to (4 to 3),
        2 to (4 to 4),
        3 to (6 to 3),
        4 to (4 to 4)
    )

    val all: List<GearItem> = listOf(
        // ---------------- sheet 1 (4x3) ----------------
        GearItem("red_cap", "כובע סרוג אדום", "head", Rarity.COMMON, 1, 0, buff = GearBuff(BuffType.CHEST_LUCK, 1.0f)),
        GearItem("leather_vest", "אפוד עור", "body", Rarity.COMMON, 1, 1, buff = GearBuff(BuffType.GOLD, 1.0f)),
        GearItem("bronze_breastplate", "שריון חזה ברונזה", "body", Rarity.COMMON, 1, 2, buff = GearBuff(BuffType.GOLD, 1.5f)),
        GearItem("hooded_cloak", "גלימה עם ברדס", "body", Rarity.COMMON, 1, 3, buff = GearBuff(BuffType.SHIELD_SAVE, 1.0f)),
        GearItem("wind_cloak", "גלימת הרוח", "body", Rarity.RARE, 1, 4, requiresEndurance = 2, buff = GearBuff(BuffType.GOLD, 2.5f)),
        GearItem("bracers", "מגיני אמה", "accessory", Rarity.RARE, 1, 5, requiresUpper = 2, buff = GearBuff(BuffType.SHIELD_SAVE, 2.5f)),
        GearItem("gauntlets", "כפפות פלדה", "accessory", Rarity.RARE, 1, 6, requiresUpper = 3, buff = GearBuff(BuffType.XP, 3.0f)),
        GearItem("wooden_staff", "מטה עץ", "weapon", Rarity.RARE, 1, 7, requiresStability = 2, buff = GearBuff(BuffType.XP, 2.5f)),
        GearItem("golden_feather_armor", "שריון הנוצות המוזהב", "body", Rarity.LEGENDARY, 1, 8, requiresUpper = 4, requiresLower = 4, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("sandals", "סנדלי הדרך", "accessory", Rarity.LEGENDARY, 1, 9, requiresEndurance = 4, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("boots", "מגפי המסע", "accessory", Rarity.LEGENDARY, 1, 10, requiresLower = 4, requiresEndurance = 3, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("glowing_sword", "חרב זוהרת", "weapon", Rarity.LEGENDARY, 1, 11, requiresUpper = 5, requiresStability = 5, buff = GearBuff(BuffType.XP, 5.0f)),

        // ---------------- sheet 2 (4x4) head ----------------
        GearItem("straw_hat", "כובע קש", "head", Rarity.COMMON, 2, 1, buff = GearBuff(BuffType.CHEST_LUCK, 1.0f)),
        GearItem("leather_hood", "ברדס עור", "head", Rarity.COMMON, 2, 2, buff = GearBuff(BuffType.CHEST_LUCK, 1.5f)),
        GearItem("iron_helm", "קסדת ברזל", "head", Rarity.COMMON, 2, 3, buff = GearBuff(BuffType.XP, 1.0f)),
        GearItem("horned_helm", "קסדת הקרניים", "head", Rarity.RARE, 2, 4, requiresUpper = 2, buff = GearBuff(BuffType.CHEST_LUCK, 2.5f)),
        GearItem("laurel_crown", "זר הדפנה", "head", Rarity.LEGENDARY, 2, 5, requiresUpper = 3, requiresLower = 3, requiresStability = 3, buff = GearBuff(BuffType.CHEST_LUCK, 5.0f)),
        GearItem("bandana", "בנדנה", "head", Rarity.COMMON, 2, 6, buff = GearBuff(BuffType.CHEST_LUCK, 1.0f)),
        GearItem("wizard_hat", "כובע הקוסם", "head", Rarity.RARE, 2, 7, requiresStability = 2, buff = GearBuff(BuffType.CHEST_LUCK, 3.0f)),
        GearItem("feathered_cap", "כובע הנוצה", "head", Rarity.RARE, 2, 8, requiresEndurance = 2, buff = GearBuff(BuffType.CHEST_LUCK, 2.5f)),
        GearItem("gem_circlet", "נזר האבן", "head", Rarity.RARE, 2, 9, requiresStability = 3, buff = GearBuff(BuffType.GOLD, 3.0f)),
        GearItem("fox_hood", "ברדס השועל", "head", Rarity.RARE, 2, 10, requiresEndurance = 3, buff = GearBuff(BuffType.CHEST_LUCK, 2.5f)),
        GearItem("winged_helm", "קסדת הכנפיים", "head", Rarity.LEGENDARY, 2, 11, requiresEndurance = 4, requiresLower = 3, buff = GearBuff(BuffType.XP, 5.0f)),
        GearItem("crown_of_thorns", "כתר הקוצים", "head", Rarity.RARE, 2, 12, requiresStability = 2, requiresUpper = 1, buff = GearBuff(BuffType.SHIELD_SAVE, 2.5f)),
        GearItem("monk_circlet", "נזר הנזיר", "head", Rarity.COMMON, 2, 13, buff = GearBuff(BuffType.SHIELD_SAVE, 1.0f)),
        GearItem("sweatband", "סרט זיעה", "head", Rarity.COMMON, 2, 14, buff = GearBuff(BuffType.CHEST_LUCK, 1.5f)),
        GearItem("flaming_helm", "קסדת הלהבה", "head", Rarity.LEGENDARY, 2, 15, requiresUpper = 5, requiresLower = 4, buff = GearBuff(BuffType.CHEST_LUCK, 5.0f)),

        // ---------------- sheet 3 (6x3) body ----------------
        GearItem("linen_tunic", "טוניקת פשתן", "body", Rarity.COMMON, 3, 0, buff = GearBuff(BuffType.GOLD, 1.0f)),
        GearItem("gambeson", "מעיל מרופד", "body", Rarity.COMMON, 3, 1, buff = GearBuff(BuffType.GOLD, 1.5f)),
        GearItem("leather_jerkin", "מקטורן עור", "body", Rarity.COMMON, 3, 2, buff = GearBuff(BuffType.GOLD, 1.0f)),
        GearItem("chainmail", "שריון שרשראות", "body", Rarity.COMMON, 3, 3, buff = GearBuff(BuffType.GOLD, 1.5f)),
        GearItem("ornate_bronze_armor", "שריון הברונזה המעוטר", "body", Rarity.LEGENDARY, 3, 4, requiresUpper = 3, requiresStability = 3, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("green_plate", "שריון הירוק", "body", Rarity.LEGENDARY, 3, 5, requiresLower = 4, requiresStability = 3, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("muscle_cuirass", "שריון השרירים", "body", Rarity.RARE, 3, 6, requiresUpper = 3, buff = GearBuff(BuffType.GOLD, 3.0f)),
        GearItem("steel_plate", "שריון פלדה מלא", "body", Rarity.RARE, 3, 7, requiresUpper = 2, requiresLower = 2, buff = GearBuff(BuffType.GOLD, 2.5f)),
        GearItem("monk_robe", "גלימת הנזיר", "body", Rarity.RARE, 3, 8, requiresStability = 3, buff = GearBuff(BuffType.SHIELD_SAVE, 3.0f)),
        GearItem("bear_fur", "פרוות הדוב", "body", Rarity.RARE, 3, 9, requiresLower = 3, buff = GearBuff(BuffType.GOLD, 2.5f)),
        GearItem("spiked_armor", "שריון הקוצים", "body", Rarity.LEGENDARY, 3, 10, requiresUpper = 4, requiresLower = 3, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("golden_wing_plate", "שריון הכנף המוזהב", "body", Rarity.LEGENDARY, 3, 11, requiresUpper = 4, requiresEndurance = 4, buff = GearBuff(BuffType.CHEST_LUCK, 5.0f)),
        GearItem("brown_cloak", "גלימת הנודד", "body", Rarity.LEGENDARY, 3, 12, requiresEndurance = 5, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("embroidered_vest", "אפוד רקום", "body", Rarity.LEGENDARY, 3, 13, requiresStability = 4, requiresUpper = 3, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("scale_armor", "שריון קשקשים", "body", Rarity.LEGENDARY, 3, 14, requiresUpper = 4, requiresStability = 4, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("poncho", "הפונצ'ו", "body", Rarity.LEGENDARY, 3, 15, requiresEndurance = 3, requiresLower = 3, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("emerald_armor", "שריון הברקת", "body", Rarity.LEGENDARY, 3, 16, requiresUpper = 5, requiresLower = 5, buff = GearBuff(BuffType.XP, 5.0f)),
        GearItem("sun_armor", "שריון השמש", "body", Rarity.LEGENDARY, 3, 17, requiresUpper = 5, requiresLower = 5, requiresStability = 5, requiresEndurance = 5, buff = GearBuff(BuffType.GOLD, 5.0f)),

        // ---------------- sheet 4 (4x4) weapons + accessories ----------------
        GearItem("iron_sword", "חרב ברזל", "weapon", Rarity.COMMON, 4, 1, buff = GearBuff(BuffType.XP, 1.0f)),
        GearItem("war_hammer", "פטיש מלחמה", "weapon", Rarity.COMMON, 4, 2, buff = GearBuff(BuffType.XP, 1.5f)),
        GearItem("battle_axe", "גרזן קרב", "weapon", Rarity.COMMON, 4, 3, buff = GearBuff(BuffType.XP, 1.5f)),
        GearItem("bow", "קשת וחצים", "weapon", Rarity.RARE, 4, 4, requiresEndurance = 2, buff = GearBuff(BuffType.XP, 2.5f)),
        GearItem("nunchaku", "נונצ'קו", "weapon", Rarity.RARE, 4, 5, requiresStability = 3, buff = GearBuff(BuffType.XP, 3.0f)),
        GearItem("spiked_club", "אלת הקוצים", "weapon", Rarity.RARE, 4, 6, requiresUpper = 3, buff = GearBuff(BuffType.XP, 2.5f)),
        GearItem("flaming_sword", "חרב הלהבה", "weapon", Rarity.LEGENDARY, 4, 7, requiresUpper = 5, requiresLower = 3, buff = GearBuff(BuffType.XP, 5.0f)),
        GearItem("quarterstaff", "מוט הלוחם", "weapon", Rarity.LEGENDARY, 4, 8, requiresStability = 4, requiresEndurance = 3, buff = GearBuff(BuffType.XP, 5.0f)),
        GearItem("twin_daggers", "הפגיונות התאומים", "weapon", Rarity.LEGENDARY, 4, 9, requiresUpper = 3, requiresEndurance = 4, buff = GearBuff(BuffType.XP, 5.0f)),
        GearItem("tree_shield", "מגן העץ", "accessory", Rarity.LEGENDARY, 4, 10, requiresStability = 5, requiresLower = 3, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("lantern", "פנס הדרך", "accessory", Rarity.LEGENDARY, 4, 11, requiresEndurance = 4, buff = GearBuff(BuffType.CHEST_LUCK, 5.0f)),
        GearItem("ring", "טבעת הברזל", "accessory", Rarity.LEGENDARY, 4, 12, requiresUpper = 3, requiresStability = 3, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("sun_amulet", "קמע השמש", "accessory", Rarity.LEGENDARY, 4, 13, requiresStability = 4, requiresEndurance = 4, buff = GearBuff(BuffType.GOLD, 5.0f)),
        GearItem("bead_bracelet", "צמיד החרוזים", "accessory", Rarity.LEGENDARY, 4, 14, requiresStability = 3, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f)),
        GearItem("lightning_gauntlets", "כפפות הברק", "accessory", Rarity.LEGENDARY, 4, 15, requiresUpper = 5, requiresStability = 4, buff = GearBuff(BuffType.SHIELD_SAVE, 5.0f))
    )

    fun byId(id: String): GearItem? = all.firstOrNull { it.id == id }

    fun bySlot(slot: String): List<GearItem> = all.filter { it.slot == slot }

    fun byRarity(rarity: Rarity): List<GearItem> = all.filter { it.rarity == rarity }

    /** Hebrew label for a slot id. */
    fun slotNameHe(slot: String): String = when (slot) {
        "head" -> "ראש"
        "body" -> "גוף"
        "weapon" -> "נשק"
        "accessory" -> "אביזר"
        else -> slot
    }

    /** "1.5" for 1.5f, "5" for 5.0f. */
    private fun percentText(p: Float): String {
        val whole = p.toInt()
        return if (p == whole.toFloat()) whole.toString() else p.toString()
    }

    /** Hebrew label for a buff, e.g. "+1.5% נקודות", "+3% זהב", "5% סיכוי שהמגן לא נשרף", "+2.5% מזל בתיבות". */
    fun buffLabelHe(buff: GearBuff): String {
        val pct = percentText(buff.percent)
        return when (buff.type) {
            BuffType.XP -> "+$pct% נקודות"
            BuffType.GOLD -> "+$pct% זהב"
            BuffType.SHIELD_SAVE -> "$pct% סיכוי שהמגן לא נשרף"
            BuffType.CHEST_LUCK -> "+$pct% מזל בתיבות"
        }
    }

    /** True when the player's branch levels satisfy the item's requirements. Keys: UPPER/LOWER/STABILITY/ENDURANCE. */
    fun canEquip(item: GearItem, upper: Int, lower: Int, stability: Int, endurance: Int): Boolean =
        upper >= item.requiresUpper && lower >= item.requiresLower &&
            stability >= item.requiresStability && endurance >= item.requiresEndurance
}
