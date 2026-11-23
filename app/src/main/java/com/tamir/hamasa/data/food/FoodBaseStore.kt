package com.tamir.hamasa.data.food

import android.content.Context
import android.content.SharedPreferences
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.domain.food.FoodFact
import com.tamir.hamasa.domain.food.FoodIndex
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.FoodText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * "מאגר המאכלים" on the phone: the built-in foods ([FoodSeed]) plus HIS rows, the foods learned from the meals
 * he saved and the ones he wrote or corrected by hand. His rows live in SharedPreferences "food_base" and in his
 * own cloud profile row (like ChefStore), so a new phone gets them back. A meal text is answered from here when
 * every food in it is known; only an unknown food goes to the AI, and is then learned. Never throws.
 */
object FoodBaseStore {
    private const val PREFS = "food_base"
    private const val K_ROWS = "rows"
    private const val K_BACKFILL = "backfill_done"
    private const val CLOUD_KEY = "foodBase"
    /** His rows are capped; the oldest learned ones go first, a row he wrote by hand never goes. */
    const val MAX_USER = 800

    private val _user = MutableStateFlow<List<FoodFact>>(emptyList())
    @Volatile private var loaded = false
    @Volatile private var cached: FoodIndex? = null

    /** His rows, oldest first. */
    val user: StateFlow<List<FoodFact>>
        get() { ensureLoaded(); return _user }

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    private fun decode(raw: String): List<FoodFact> =
        raw.split('\n').mapNotNull { line ->
            if (line.length < 3) null else {
                val origin = if (line[0] == '2') FoodFact.ORIGIN_EDITED else FoodFact.ORIGIN_LEARNED
                FoodFact.fromRow(line.substring(2), origin)
            }
        }

    /** One row per line, the origin digit first: "1|name|kcal|...". */
    private fun encode(list: List<FoodFact>): String =
        list.joinToString("\n") { f -> (if (f.origin == FoodFact.ORIGIN_EDITED) "2" else "1") + "|" + f.toRow() }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val s = sp()
            if (s != null) _user.value = runCatching { decode(s.getString(K_ROWS, "") ?: "") }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun save(list: List<FoodFact>) {
        var kept = list
        if (kept.size > MAX_USER) {
            var extra = kept.size - MAX_USER
            kept = kept.filter { f ->
                if (extra > 0 && f.origin == FoodFact.ORIGIN_LEARNED) { extra--; false } else true
            }
        }
        _user.value = kept
        cached = null
        runCatching { sp()?.edit()?.putString(K_ROWS, encode(kept))?.apply() }
    }

    private fun pushSoon() {
        runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
    }

    /** Built-in foods first, his rows after them (his win on the same name). */
    fun index(): FoodIndex {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            ensureLoaded()
            val built = FoodIndex(FoodSeed.facts + _user.value)
            cached = built
            return built
        }
    }

    fun find(nameHe: String): FoodFact? = index().find(nameHe)

    /** A whole meal text from the base, or null when a word of it is not understood (see [FoodIndex.parse]). */
    fun parse(text: String): List<FoodItem>? = runCatching { index().parse(text) }.getOrNull()

    /** AI items with the numbers of the known foods taken from the base. Second value: how many were known. */
    fun applyKnown(items: List<FoodItem>): Pair<List<FoodItem>, Int> =
        runCatching { index().applyKnown(items) }.getOrDefault(Pair(items, 0))

    private fun sameName(a: String, b: String): Boolean = FoodText.norm(a) == FoodText.norm(b)

    /**
     * Learns the foods of a saved meal that the base does not know yet (per-100-gram values from the item as he
     * saved it). A known food is left alone: its values change only by hand. Returns how many were added.
     */
    fun learn(items: List<FoodItem>, push: Boolean = true): Int {
        var added = 0
        synchronized(this) {
            ensureLoaded()
            val idx = index()
            val fresh = ArrayList<FoodFact>()
            for (x in items) {
                if (idx.find(x.nameHe) != null) continue
                val f = FoodFact.fromItem(x, FoodFact.ORIGIN_LEARNED) ?: continue
                if (fresh.any { sameName(it.nameHe, f.nameHe) }) continue
                fresh.add(f)
            }
            if (fresh.isNotEmpty()) {
                save(_user.value + fresh)
                added = fresh.size
            }
        }
        if (added > 0 && push) pushSoon()
        return added
    }

    /** A food he wrote or corrected: replaces his row of the same name, and wins over a built-in one. */
    fun put(fact: FoodFact) {
        if (FoodText.norm(fact.nameHe).isEmpty()) return
        synchronized(this) {
            ensureLoaded()
            val mine = fact.copy(origin = FoodFact.ORIGIN_EDITED)
            save(_user.value.filter { !sameName(it.nameHe, mine.nameHe) } + mine)
        }
        pushSoon()
    }

    /** Removes HIS row of this name (a built-in food of the same name shows again). */
    fun remove(nameHe: String) {
        synchronized(this) {
            ensureLoaded()
            save(_user.value.filter { !sameName(it.nameHe, nameHe) })
        }
        pushSoon()
    }

    fun isMine(nameHe: String): Boolean {
        ensureLoaded()
        return _user.value.any { sameName(it.nameHe, nameHe) }
    }

    fun seedCount(): Int = FoodSeed.facts.size

    /**
     * Foods whose name or alias contains [query] (his rows first, newest first), at most [limit].
     * An empty query lists his rows only.
     */
    fun search(query: String, limit: Int = 30): List<FoodFact> {
        ensureLoaded()
        val q = FoodText.norm(query)
        val mine = _user.value.reversed()
        if (q.isEmpty()) return mine.take(limit)
        val out = ArrayList<FoodFact>()
        val seen = HashSet<String>()
        fun consider(f: FoodFact) {
            if (out.size >= limit) return
            val key = FoodText.norm(f.nameHe)
            if (key in seen) return
            if (key.contains(q) || f.aliases.any { FoodText.norm(it).contains(q) }) {
                seen.add(key)
                out.add(f)
            }
        }
        for (f in mine) consider(f)
        // names that start with the query before names that only contain it
        for (f in FoodSeed.facts) if (FoodText.norm(f.nameHe).startsWith(q)) consider(f)
        for (f in FoodSeed.facts) consider(f)
        return out
    }

    // ---------------------------------------------------------------- the meals he already logged

    /**
     * Once per install: every food of every meal he already logged (the item breakdown of the AI estimates,
     * already split into separate foods) goes through [learn]. Returns how many foods were added, or -1 when
     * it already ran.
     */
    suspend fun backfillOnce(): Int {
        val s = sp() ?: return -1
        if (s.getBoolean(K_BACKFILL, false)) return -1
        var added = 0
        val ok = runCatching {
            val meals = AppGraph.db.meals().allOnce()
            for (m in meals) {
                if (m.itemsJson.isBlank()) continue
                added += learn(MealEstimator.decodeItems(m.itemsJson), push = false)
            }
        }.isSuccess
        if (ok) {
            runCatching { s.edit().putBoolean(K_BACKFILL, true).apply() }
            if (added > 0) pushSoon()
        }
        return added
    }

    // ---------------------------------------------------------------- cloud (his own profile row)

    fun putCloud(o: JSONObject) {
        ensureLoaded()
        o.put(CLOUD_KEY, encode(_user.value))
    }

    /** Fresh-install restore: adds his rows that are missing locally, never replaces a local row. */
    fun restoreFromCloud(d: JSONObject) {
        val remote = runCatching { decode(d.optString(CLOUD_KEY, "")) }.getOrDefault(emptyList())
        if (remote.isEmpty()) return
        synchronized(this) {
            ensureLoaded()
            val have = _user.value.map { FoodText.norm(it.nameHe) }.toHashSet()
            val missing = remote.filter { FoodText.norm(it.nameHe) !in have }
            if (missing.isNotEmpty()) save(_user.value + missing)
        }
    }

    /** Tests only: forget everything of the user and read the prefs again. */
    internal fun resetForTests() {
        synchronized(this) {
            runCatching { sp()?.edit()?.clear()?.commit() }
            _user.value = emptyList()
            cached = null
            loaded = false
        }
    }
}
