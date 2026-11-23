package com.tamir.hamasa.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.domain.Days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One recipe from the chef (also what the recipe book keeps). */
data class ChefRecipe(
    val nameHe: String,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val grams: Int,
    val minutes: Int,
    /** "200 גרם חזה עוף" */
    val ingredientsHe: List<String>,
    /** short steps, in order */
    val stepsHe: List<String>,
    val whyHe: String = "",
    /** in the recipe book: liked or cooked */
    val liked: Boolean = false,
    val cooked: Int = 0,
    val lastDay: Long = 0L,
    /**
     * How many portions the ingredients make. kcal / macros / grams are always for ONE portion; the ingredient
     * amounts are for the whole recipe ([servings] portions), as he wrote or imported them (owner, 2026-11-08).
     */
    val servings: Int = 1
)

/** One line of today's talk with the chef. [recipes] only on chef lines. */
data class ChefLine(val fromChef: Boolean, val textHe: String, val recipes: List<ChefRecipe> = emptyList())

data class ChefState(
    /** What the chef knows about his taste, one short Hebrew fact per line ("לא אוכל דגים ומאכלי ים"). */
    val facts: List<String> = emptyList(),
    /** The recipe book: recipes he liked or cooked, newest first. */
    val book: List<ChefRecipe> = emptyList(),
    /** Dishes he marked "לא בשבילי", with the reason when he gave one ("שקשוקה: יותר מדי עבודה"). */
    val nope: List<String> = emptyList(),
    /** Today's conversation (cleared on a new day). */
    val chat: List<ChefLine> = emptyList(),
    val chatDay: Long = 0L,
    /** Names the chef suggested lately (newest last), so the next answers do not repeat them. Local only. */
    val recent: List<String> = emptyList()
)

/**
 * The chef's memory of ONE user (SharedPreferences "chef" on his phone, and in his own cloud profile row like
 * WatchStore): taste facts, the recipe book, the dishes that were not for him, today's chat. Each user has his
 * own; nothing here is shared between users. Never throws.
 */
object ChefStore {
    private const val PREFS = "chef"
    private const val K_FACTS = "facts"
    private const val K_BOOK = "book"
    private const val K_NOPE = "nope"
    private const val K_CHAT = "chat"
    private const val K_CHAT_DAY = "chatDay"
    private const val K_RECENT = "recent"
    private const val MAX_RECENT = 24
    const val MAX_FACTS = 30
    const val MAX_BOOK = 60
    const val MAX_NOPE = 30
    private const val MAX_CHAT = 16
    const val MAX_FACT_LEN = 80

    private val _state = MutableStateFlow(ChefState())
    @Volatile private var loaded = false

    val state: StateFlow<ChefState>
        get() { ensureLoaded(); return _state }

    fun current(): ChefState { ensureLoaded(); return _state.value }

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val s = sp()
            if (s != null) {
                _state.value = ChefState(
                    facts = decodeStrings(s.getString(K_FACTS, "") ?: ""),
                    book = decodeRecipes(s.getString(K_BOOK, "") ?: ""),
                    nope = decodeStrings(s.getString(K_NOPE, "") ?: ""),
                    chat = decodeChat(s.getString(K_CHAT, "") ?: ""),
                    chatDay = s.getLong(K_CHAT_DAY, 0L),
                    recent = decodeStrings(s.getString(K_RECENT, "") ?: "")
                )
            }
            loaded = true
        }
    }

    private fun update(block: (ChefState) -> ChefState) {
        synchronized(this) {
            ensureLoaded()
            val n = block(_state.value)
            _state.value = n
            runCatching {
                sp()?.edit()
                    ?.putString(K_FACTS, encodeStrings(n.facts))
                    ?.putString(K_BOOK, encodeRecipes(n.book))
                    ?.putString(K_NOPE, encodeStrings(n.nope))
                    ?.putString(K_CHAT, encodeChat(n.chat))
                    ?.putLong(K_CHAT_DAY, n.chatDay)
                    ?.putString(K_RECENT, encodeStrings(n.recent))
                    ?.apply()
            }
        }
    }

    private fun pushSoon() {
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
    }

    // ---------------------------------------------------------------- facts

    /** Same fact = same text ignoring spaces and final period. */
    private fun key(s: String): String = s.trim().trimEnd('.').replace(Regex("\\s+"), " ")

    /** Adds facts (new ones go last; duplicates skipped; oldest dropped past [MAX_FACTS]). True when one was added. */
    fun addFacts(lines: List<String>): Boolean {
        val clean = lines.map { key(it).take(MAX_FACT_LEN) }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return false
        var added = false
        update { s ->
            val have = s.facts.map { key(it) }.toMutableList()
            for (c in clean) if (c !in have) { have.add(c); added = true }
            s.copy(facts = have.takeLast(MAX_FACTS))
        }
        if (added) pushSoon()
        return added
    }

    fun removeFact(fact: String) {
        update { s -> s.copy(facts = s.facts.filter { key(it) != key(fact) }) }
        pushSoon()
    }

    // ---------------------------------------------------------------- recipe book / feedback

    private fun sameDish(a: String, b: String): Boolean = key(a) == key(b)

    /** "אהבתי": into the recipe book (or marked liked there). */
    fun like(r: ChefRecipe) {
        update { s ->
            val old = s.book.firstOrNull { sameDish(it.nameHe, r.nameHe) }
            val kept = (old ?: r).copy(liked = true, lastDay = Days.today())
            s.copy(
                book = (listOf(kept) + s.book.filter { !sameDish(it.nameHe, r.nameHe) }).take(MAX_BOOK),
                nope = s.nope.filter { !it.startsWith(key(r.nameHe)) }
            )
        }
        pushSoon()
    }

    /** "הכנתי את זה": into the recipe book with one more cook. */
    fun cooked(r: ChefRecipe) {
        update { s ->
            val old = s.book.firstOrNull { sameDish(it.nameHe, r.nameHe) }
            val kept = (old ?: r).copy(cooked = (old?.cooked ?: 0) + 1, lastDay = Days.today())
            s.copy(book = (listOf(kept) + s.book.filter { !sameDish(it.nameHe, r.nameHe) }).take(MAX_BOOK))
        }
        pushSoon()
    }

    /** "לא בשבילי" with an optional reason: remembered (never suggested again) and out of the book. */
    fun nope(r: ChefRecipe, reasonHe: String) {
        val reason = reasonHe.trim().take(60)
        val line = if (reason.isEmpty()) key(r.nameHe) else key(r.nameHe) + ": " + reason
        update { s ->
            s.copy(
                nope = (s.nope.filter { !it.startsWith(key(r.nameHe)) } + line).takeLast(MAX_NOPE),
                book = s.book.filter { !sameDish(it.nameHe, r.nameHe) }
            )
        }
        pushSoon()
    }

    fun removeFromBook(name: String) {
        update { s -> s.copy(book = s.book.filter { !sameDish(it.nameHe, name) }) }
        pushSoon()
    }

    fun inBook(name: String): ChefRecipe? = current().book.firstOrNull { sameDish(it.nameHe, name) }

    fun isNope(name: String): Boolean = current().nope.any { it == key(name) || it.startsWith(key(name) + ":") }

    // ---------------------------------------------------------------- chat

    /** Today's chat (an older day's chat is dropped first). */
    fun todayChat(): List<ChefLine> {
        val s = current()
        return if (s.chatDay == Days.today()) s.chat else emptyList()
    }

    fun addLine(line: ChefLine) {
        val today = Days.today()
        update { s ->
            val base = if (s.chatDay == today) s.chat else emptyList()
            s.copy(chat = (base + line).takeLast(MAX_CHAT), chatDay = today)
        }
    }

    /** Remembers suggested dish names (for variety). */
    fun addRecent(names: List<String>) {
        if (names.isEmpty()) return
        update { s -> s.copy(recent = (s.recent.filter { r -> names.none { key(it) == key(r) } } + names.map { key(it) }).takeLast(MAX_RECENT)) }
    }

    fun clearChat() = update { it.copy(chat = emptyList(), chatDay = Days.today()) }

    /** Tests: forget everything. */
    internal fun resetForTest() = update { ChefState() }

    // ---------------------------------------------------------------- cloud (his own profile row)

    fun putCloud(o: JSONObject) {
        val s = current()
        o.put("chefFacts", encodeStrings(s.facts))
        o.put("chefBook", encodeRecipes(s.book))
        o.put("chefNope", encodeStrings(s.nope))
    }

    /** Fresh-install restore: adds what is missing locally, never replaces local values. */
    fun restoreFromCloud(d: JSONObject) {
        val facts = decodeStrings(d.optString("chefFacts", ""))
        val book = decodeRecipes(d.optString("chefBook", ""))
        val nope = decodeStrings(d.optString("chefNope", ""))
        if (facts.isEmpty() && book.isEmpty() && nope.isEmpty()) return
        update { s ->
            val haveBook = s.book.map { key(it.nameHe) }.toSet()
            s.copy(
                facts = (s.facts + facts.filter { f -> s.facts.none { key(it) == key(f) } }).takeLast(MAX_FACTS),
                book = (s.book + book.filter { key(it.nameHe) !in haveBook }).take(MAX_BOOK),
                nope = (s.nope + nope.filter { it !in s.nope }).takeLast(MAX_NOPE)
            )
        }
    }

    // ---------------------------------------------------------------- json

    private fun encodeStrings(l: List<String>): String = JSONArray().apply { l.forEach { put(it) } }.toString()

    private fun decodeStrings(s: String): List<String> = runCatching {
        if (s.isBlank()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    fun recipeJson(r: ChefRecipe): JSONObject = JSONObject().apply {
        put("n", r.nameHe); put("k", r.kcal); put("p", r.proteinG.toDouble()); put("c", r.carbsG.toDouble())
        put("f", r.fatG.toDouble()); put("g", r.grams); put("m", r.minutes)
        put("i", JSONArray().apply { r.ingredientsHe.forEach { put(it) } })
        put("s", JSONArray().apply { r.stepsHe.forEach { put(it) } })
        put("w", r.whyHe); put("l", r.liked); put("x", r.cooked); put("d", r.lastDay); put("v", r.servings)
    }

    fun recipeFrom(o: JSONObject): ChefRecipe? {
        val name = o.optString("n", "").trim()
        if (name.isEmpty()) return null
        fun list(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
        }
        return ChefRecipe(
            nameHe = name, kcal = o.optInt("k", 0), proteinG = o.optDouble("p", 0.0).toFloat(),
            carbsG = o.optDouble("c", 0.0).toFloat(), fatG = o.optDouble("f", 0.0).toFloat(),
            grams = o.optInt("g", 0), minutes = o.optInt("m", 0),
            ingredientsHe = list("i"), stepsHe = list("s"), whyHe = o.optString("w", ""),
            liked = o.optBoolean("l", false), cooked = o.optInt("x", 0), lastDay = o.optLong("d", 0L),
            servings = o.optInt("v", 1).coerceIn(1, 50)
        )
    }

    private fun encodeRecipes(l: List<ChefRecipe>): String = JSONArray().apply { l.forEach { put(recipeJson(it)) } }.toString()

    private fun decodeRecipes(s: String): List<ChefRecipe> = runCatching {
        if (s.isBlank()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> recipeFrom(o) } }
        }
    }.getOrDefault(emptyList())

    private fun encodeChat(l: List<ChefLine>): String = JSONArray().apply {
        l.forEach { line ->
            put(JSONObject().apply {
                put("c", line.fromChef); put("t", line.textHe)
                put("r", JSONArray().apply { line.recipes.forEach { put(recipeJson(it)) } })
            })
        }
    }.toString()

    private fun decodeChat(s: String): List<ChefLine> = runCatching {
        if (s.isBlank()) emptyList() else {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val r = o.optJSONArray("r")
                val recipes = if (r == null) emptyList() else (0 until r.length()).mapNotNull { j -> r.optJSONObject(j)?.let { recipeFrom(it) } }
                ChefLine(o.optBoolean("c", false), o.optString("t", ""), recipes)
            }
        }
    }.getOrDefault(emptyList())
}
