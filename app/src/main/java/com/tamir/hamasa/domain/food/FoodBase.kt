package com.tamir.hamasa.domain.food

import kotlin.math.roundToInt

/**
 * One food of the food base ("מאגר המאכלים"): its values per 100 grams and a usual portion.
 * The base answers "what is in 200 grams of chicken breast" on the phone, so the AI is asked only about
 * foods the base does not know yet (owner request 2026-11-22).
 */
data class FoodFact(
    val nameHe: String,
    val kcal100: Float,
    val protein100: Float,
    val carbs100: Float,
    val fat100: Float,
    /** Grams of a usual portion; for a countable food ([perUnit]) the weight of one piece. */
    val portionG: Int,
    val perUnit: Boolean = false,
    /** Other ways he may write the same food ("ביצים", "ביצה קשה"). */
    val aliases: List<String> = emptyList(),
    val origin: Int = ORIGIN_SEED
) {
    /** This food at [grams]. */
    fun item(grams: Int): FoodItem {
        val g = grams.coerceIn(0, 5000)
        val f = g / 100f
        return FoodItem(
            nameHe.take(40), g, (kcal100 * f).roundToInt(),
            round1(protein100 * f), round1(carbs100 * f), round1(fat100 * f), 1f
        )
    }

    /** "name|kcal|protein|carbs|fat|portion|unit|alias§alias": the stored form (seed rows and his own rows). */
    fun toRow(): String = listOf(
        clean(nameHe), num(kcal100), num(protein100), num(carbs100), num(fat100),
        portionG.toString(), if (perUnit) "1" else "0", aliases.joinToString("§") { clean(it) }
    ).joinToString("|")

    companion object {
        /** Built into the app (the American food tables and the Israeli dishes). */
        const val ORIGIN_SEED = 0
        /** Learned from a meal he saved. */
        const val ORIGIN_LEARNED = 1
        /** Written or corrected by him. */
        const val ORIGIN_EDITED = 2

        private fun clean(s: String): String = s.replace('|', ' ').replace('§', ' ').replace('\n', ' ').trim()

        private fun num(v: Float): String {
            val r = round1(v)
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }

        /** Null for a broken row. */
        fun fromRow(row: String, origin: Int): FoodFact? {
            val p = row.split('|')
            if (p.size < 7) return null
            val name = p[0].trim()
            val kcal = p[1].toFloatOrNull() ?: return null
            val pr = p[2].toFloatOrNull() ?: return null
            val c = p[3].toFloatOrNull() ?: return null
            val f = p[4].toFloatOrNull() ?: return null
            val portion = p[5].toIntOrNull() ?: return null
            if (name.isEmpty() || kcal < 0f || portion <= 0) return null
            val aliases = if (p.size > 7 && p[7].isNotBlank()) p[7].split('§').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            return FoodFact(name, kcal, pr, c, f, portion, p[6].trim() == "1", aliases, origin)
        }

        /** A food as he ate it once: per-100-gram values from the item. Null when it cannot be trusted. */
        fun fromItem(item: FoodItem, origin: Int): FoodFact? {
            val name = clean(item.nameHe)
            if (FoodText.norm(name).length < 2) return null
            if (item.grams < 5 || item.kcal < 1) return null
            val f = 100f / item.grams
            val kcal = item.kcal * f
            if (kcal > 950f) return null
            return FoodFact(name.take(40), round1(kcal), round1(item.proteinG * f), round1(item.carbsG * f), round1(item.fatG * f), item.grams, false, emptyList(), origin)
        }
    }
}

/** Hebrew text helpers of the food base: one spelling per food, and the amounts he writes. */
object FoodText {
    private val LONE_DOT = Regex("(?<!\\d)\\.|\\.(?!\\d)")
    private val PERCENT_WORD = Regex("\\s*אחוזים|\\s*אחוז")
    private val SPACE_PERCENT = Regex("\\s+%")
    private val SPACES = Regex("\\s+")
    private val DIGIT_THEN_LETTER = Regex("(\\d)([^\\d\\s.%])")
    private val LETTER_THEN_DIGIT = Regex("([^\\d\\s.%])(\\d)")

    /** The comparison key of a name: no niqqud, no quote marks, no punctuation, single spaces, "5 אחוז" = "5%". */
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch in '֑'..'ׇ' -> Unit
                ch == '׳' || ch == '״' || ch == '\'' || ch == '"' || ch == '`' || ch == '’' -> Unit
                ch == '%' || ch == '.' || ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        var t = sb.toString()
        t = LONE_DOT.replace(t, " ")
        t = PERCENT_WORD.replace(t, "%")
        t = SPACE_PERCENT.replace(t, "%")
        return SPACES.replace(t, " ").trim()
    }

    /** The words of a meal text; a comma, a new line or a plus sign becomes the token ",". */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val parts = text.replace('\n', ',').replace(';', ',').replace('+', ',').split(',')
        for ((i, part) in parts.withIndex()) {
            var n = norm(part)
            n = DIGIT_THEN_LETTER.replace(n) { m -> m.groupValues[1] + " " + m.groupValues[2] }
            n = LETTER_THEN_DIGIT.replace(n) { m -> m.groupValues[1] + " " + m.groupValues[2] }
            for (w in n.split(' ')) if (w.isNotEmpty()) out.add(w)
            if (i < parts.size - 1) out.add(",")
        }
        return out
    }

    private val NUMBER_WORDS: Map<String, Float> = mapOf(
        "חצי" to 0.5f, "רבע" to 0.25f, "שליש" to 0.33f,
        "אחד" to 1f, "אחת" to 1f,
        "שני" to 2f, "שתי" to 2f, "שניים" to 2f, "שתיים" to 2f, "שתים" to 2f, "שנים" to 2f,
        "שלוש" to 3f, "שלושה" to 3f, "ארבע" to 4f, "ארבעה" to 4f,
        "חמש" to 5f, "חמישה" to 5f, "חמשה" to 5f, "שש" to 6f, "שישה" to 6f, "ששה" to 6f,
        "שבע" to 7f, "שבעה" to 7f, "שמונה" to 8f, "תשע" to 9f, "תשעה" to 9f, "עשר" to 10f, "עשרה" to 10f
    )

    /** Null when [w] is not a number. */
    fun number(w: String): Float? {
        NUMBER_WORDS[w]?.let { return it }
        if (w.isEmpty() || !w[0].isDigit()) return null
        val v = w.toFloatOrNull() ?: return null
        return if (v > 0f && v <= 5000f) v else null
    }

    /** Grams of one of this weight / volume word ("גרם" = 1, "כף" = 15); null when [w] is not one. */
    fun gramsOf(w: String): Float? = when (w) {
        "גרם", "גר", "ג", "גרמים", "g", "gr", "מל", "מיליליטר" -> 1f
        "קילו", "קג", "קילוגרם", "ליטר" -> 1000f
        "כף", "כפות" -> 15f
        "כפית", "כפיות" -> 5f
        else -> null
    }

    /** "כוס", "פרוסה", "יחידה": words that mean "one usual portion of what follows". */
    val PORTION_WORDS: Set<String> = setOf(
        "יחידה", "יחידות", "מנה", "מנות", "כוס", "כוסות", "פרוסה", "פרוסות", "חתיכה", "חתיכות",
        "קערה", "קערות", "צלחת", "צלחות", "פחית", "פחיות", "בקבוק", "בקבוקים", "כדור", "כדורים",
        "משולש", "משולשים", "שקית", "שקיות", "קופסה", "קופסת", "קופסאות"
    )

    /** Words between foods that carry no food. */
    val SKIP: Set<String> = setOf("עם", "ו", "וגם", "גם", "אכלתי", "שתיתי", "ועוד", "עוד", "בתוספת", "פלוס", "ליד", "של")

    private fun finalForm(stem: String): String {
        if (stem.isEmpty()) return stem
        val last = when (stem.last()) {
            'מ' -> 'ם'
            'נ' -> 'ן'
            'כ' -> 'ך'
            'פ' -> 'ף'
            'צ' -> 'ץ'
            else -> return stem
        }
        return stem.dropLast(1) + last
    }

    /** Singular guesses for a name whose first word is plural ("פיתות" -> "פיתה", "מלפפונים" -> "מלפפון"). */
    fun singulars(key: String): List<String> {
        val sp = key.indexOf(' ')
        val first = if (sp < 0) key else key.substring(0, sp)
        val rest = if (sp < 0) "" else key.substring(sp)
        if (first.length < 4) return emptyList()
        val out = ArrayList<String>()
        if (first.endsWith("ות")) {
            val stem = first.dropLast(2)
            out.add(stem + "ה" + rest)
            out.add(stem + "ת" + rest)
            out.add(finalForm(stem) + rest)
        } else if (first.endsWith("ים")) {
            val stem = first.dropLast(2)
            out.add(finalForm(stem) + rest)
            out.add(stem + "ה" + rest)
        }
        return out
    }
}

/**
 * The searchable food base: [facts] in order, a later fact wins over an earlier one with the same name
 * (his own rows come after the built-in ones). Pure, no Android.
 */
class FoodIndex(val facts: List<FoodFact>) {
    private val byKey = HashMap<String, FoodFact>()
    /** The latest fact of each food name: his corrected row answers for the built-in food's aliases too. */
    private val byName = HashMap<String, FoodFact>()

    init {
        for (f in facts) {
            for (a in f.aliases) {
                val k = FoodText.norm(a)
                if (k.isNotEmpty() && (f.origin != FoodFact.ORIGIN_SEED || !byKey.containsKey(k))) byKey[k] = f
            }
        }
        // a food's own name always wins over another food's alias
        for (f in facts) {
            val k = FoodText.norm(f.nameHe)
            if (k.isNotEmpty()) {
                byKey[k] = f
                byName[k] = f
            }
        }
    }

    private fun latest(f: FoodFact): FoodFact = byName[FoodText.norm(f.nameHe)] ?: f

    /** The food called [name] (exact name or alias, or its plural), or null. Never a guess by similarity. */
    fun find(name: String): FoodFact? {
        val key = FoodText.norm(name)
        if (key.isEmpty()) return null
        byKey[key]?.let { return latest(it) }
        for (c in FoodText.singulars(key)) byKey[c]?.let { return latest(it) }
        return null
    }

    private fun findWords(words: List<String>): FoodFact? {
        if (words.contains(",")) return null
        find(words.joinToString(" "))?.let { return it }
        // "ואורז": the "and" glued to the first word
        val first = words[0]
        if (first.length > 2 && first[0] == 'ו') {
            return find((listOf(first.substring(1)) + words.drop(1)).joinToString(" "))
        }
        return null
    }

    /** Longest food name starting at [from]: the food and how many words it took, or null. */
    private fun longest(tokens: List<String>, from: Int): Pair<FoodFact, Int>? {
        val max = minOf(MAX_WORDS, tokens.size - from)
        for (len in max downTo 1) {
            val f = findWords(tokens.subList(from, from + len))
            if (f != null) return Pair(f, len)
        }
        return null
    }

    /**
     * A whole meal text ("200 גרם חזה עוף ואורז, שתי פיתות") as items, ONLY when every word of it is understood:
     * each food is in the base and each amount is clear. Anything unknown or unclear gives null, and the caller
     * asks the AI. A food without an amount gets its usual portion.
     */
    fun parse(text: String): List<FoodItem>? {
        val tokens = FoodText.tokens(text)
        if (tokens.isEmpty()) return null
        val out = ArrayList<FoodItem>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "," || t in FoodText.SKIP) { i++; continue }
            var amount: Float? = FoodText.number(t)
            // "ושתי פיתות": the "and" glued to the number word
            if (amount == null && t.length > 1 && t[0] == 'ו') amount = FoodText.number(t.substring(1))
            var j = i
            if (amount != null) {
                j = i + 1
                if (j < tokens.size && tokens[j] == "וחצי") { amount = amount + 0.5f; j++ }
            }
            if (j >= tokens.size) return null
            var grams: Float? = null
            val unit = FoodText.gramsOf(tokens[j])
            if (unit != null && (amount != null || unit > 1f) && j + 1 < tokens.size) {
                // "200 גרם ...", "כף ...", "2 כפות ..."
                grams = (amount ?: 1f) * unit
                j++
            }
            while (j < tokens.size && tokens[j] == "של") j++
            if (j >= tokens.size) return null
            var hit = longest(tokens, j)
            if (hit == null && grams == null && tokens[j] in FoodText.PORTION_WORDS && j + 1 < tokens.size) {
                // "כוס אורז", "2 פרוסות לחם מלא": the word only says "a portion"
                var k = j + 1
                while (k < tokens.size && tokens[k] == "של") k++
                if (k >= tokens.size) return null
                hit = longest(tokens, k)
                j = k
            }
            if (hit == null) return null
            val fact = hit.first
            val g = when {
                grams != null -> grams
                amount != null -> {
                    // a bare number is a count of portions; a big bare number is unclear (grams? pieces?)
                    if (amount > MAX_BARE_COUNT) return null
                    amount * fact.portionG
                }
                else -> fact.portionG.toFloat()
            }
            val gi = g.roundToInt()
            if (gi < 1 || gi > 5000) return null
            out.add(fact.item(gi))
            i = j + hit.second
        }
        return if (out.isEmpty() || out.size > MAX_ITEMS) null else out
    }

    /**
     * Items that came from the AI, with the numbers of every food the base knows taken from the base (same food,
     * same numbers, every time). The name, the grams and the confidence stay. Second value: how many were known.
     */
    fun applyKnown(items: List<FoodItem>): Pair<List<FoodItem>, Int> {
        var known = 0
        val out = items.map { x ->
            val f = if (x.grams > 0) find(x.nameHe) else null
            if (f == null) x else {
                known++
                f.item(x.grams).copy(nameHe = x.nameHe, confidence = x.confidence)
            }
        }
        return Pair(out, known)
    }

    companion object {
        const val MAX_WORDS = 6
        const val MAX_ITEMS = 12
        const val MAX_BARE_COUNT = 12f
    }
}
