package com.tamir.hamasa.ui.shop

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.InventoryEntity
import com.tamir.hamasa.data.db.LedgerEntity
import com.tamir.hamasa.data.db.RewardEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.GearItem
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.RewardCatalog
import com.tamir.hamasa.domain.game.Voice
import com.tamir.hamasa.domain.tree.TreeModel
import com.tamir.hamasa.ui.common.Assets
import com.tamir.hamasa.ui.common.cropMeasuredScaled
import com.tamir.hamasa.ui.common.drawableId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Prices and Shambiuluk's shop lines. Pure, no Android types. */
object ShopRules {
    const val SHIELD_PRICE = 250
    const val SHIELD_MAX = 3
    const val CHEST_PRICE = 120

    /** Rarity enum has three values (COMMON / RARE / LEGENDARY); there is no "epic" tier in this game. */
    fun gearPrice(rarity: Rarity): Int = when (rarity) {
        Rarity.COMMON -> 150
        Rarity.RARE -> 400
        Rarity.LEGENDARY -> 2000
    }

    /** Hebrew list of the stat requirements the player still misses, or null when the item can be bought. */
    fun missingHe(item: GearItem, s: TreeModel.Stats): String? {
        val parts = ArrayList<String>()
        if (s.upper < item.requiresUpper) parts.add("חזה/גב רמה ${item.requiresUpper}")
        if (s.lower < item.requiresLower) parts.add("רגליים רמה ${item.requiresLower}")
        if (s.stability < item.requiresStability) parts.add("יציבות רמה ${item.requiresStability}")
        if (s.endurance < item.requiresEndurance) parts.add("סיבולת רמה ${item.requiresEndurance}")
        if (parts.isEmpty()) return null
        return "דורש " + parts.joinToString(", ")
    }

    /** Hebrew summary of the requirements of an item (shown even when met). */
    fun requirementsHe(item: GearItem): String {
        val parts = ArrayList<String>()
        if (item.requiresUpper > 0) parts.add("חזה/גב ${item.requiresUpper}")
        if (item.requiresLower > 0) parts.add("רגליים ${item.requiresLower}")
        if (item.requiresStability > 0) parts.add("יציבות ${item.requiresStability}")
        if (item.requiresEndurance > 0) parts.add("סיבולת ${item.requiresEndurance}")
        return if (parts.isEmpty()) "ללא דרישות" else parts.joinToString(", ")
    }

    // ------------------------------------------------------------ Shambiuluk
    private val boughtReward = listOf(
        "הזהב הלך. נרשם. מקווה שזה היה שווה את זה.",
        "נרכש. {u:תיהנה|תיהני}. ליהנות אני לא יודע. לרשום כן.",
        "העסקה בוצעה. הזהב עבד קשה בשבילך. עכשיו תורך.",
        "קנית פרס. נרשם. {u:האיש והאגדה גם נח|האישה והאגדה גם נחה} לפעמים."
    )
    private val boughtShield = listOf(
        "מגן נרכש. הרצף מוגן. אני לא.",
        "מגן. יום אחד של חולשה ייסלח. יום אחד בלבד.",
        "ביטוח לרצף. חכם. מפתיע, אבל חכם."
    )
    private val boughtGear = listOf(
        "ציוד חדש. אני נראה טוב יותר ממך. זו עובדה.",
        "נרכש. עכשיו {u:תלבש|תלבשי} את זה עליי. מדוע {u:אתה מחכה|את מחכה}?",
        "הידד. ברזל על הגוף, לא רק ביד.",
        "ציוד. יפה. האגדה מתלבשת."
    )
    private val equipped = listOf(
        "לבוש. מרגיש כבד. מתאים לי.",
        "זה עליי עכשיו. {u:תסתכל ותלמד|תסתכלי ותלמדי}.",
        "לבוש. היכן המראה?"
    )
    private val noGold = listOf(
        "אין מספיק זהב. אינני בנק. {u:לך תתאמן|לכי תתאמני}.",
        "חסר זהב. הברזל משלם טוב. הספה לא.",
        "מדוע {u:אתה מנסה|את מנסה}? הכיס ריק. המכון מלא."
    )
    private val shieldFull = listOf(
        "שלושה מגנים. יותר מזה זו פחדנות.",
        "המלאי מלא. סגור. עכשיו רק צריך לא להשתמש בהם."
    )
    private val emptyName = listOf("פרס בלי שם זה לא פרס. זו מחשבה.")

    fun boughtReward(): String = com.tamir.hamasa.domain.game.CharacterNames.g(boughtReward.random())
    fun boughtShield(): String = com.tamir.hamasa.domain.game.CharacterNames.g(boughtShield.random())
    fun boughtGear(): String = com.tamir.hamasa.domain.game.CharacterNames.g(boughtGear.random())
    fun equipped(): String = com.tamir.hamasa.domain.game.CharacterNames.g(equipped.random())
    fun noGold(): String = com.tamir.hamasa.domain.game.CharacterNames.g(noGold.random())
    fun shieldFull(): String = com.tamir.hamasa.domain.game.CharacterNames.g(shieldFull.random())
    fun emptyName(): String = emptyName.random()
    fun chest(drop: ChestDrop): String = Voice.chest(drop.rarity)
}

/** A Shambiuluk line shown in a dialog after an action; [drop] is set after a chest roll. */
data class ShopMessage(val text: String, val drop: ChestDrop? = null)

class ShopViewModel(private val app: Application) : ViewModel() {
    private val dao get() = AppGraph.db.rewards()

    val profile: StateFlow<Profile> = AppGraph.prefs.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Profile())

    val rewards: StateFlow<List<RewardEntity>> = dao.rewards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inventory: StateFlow<List<InventoryEntity>> = AppGraph.game.inventory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lowest weigh-in ever (0 = none): opens the legendary rewards of RewardCatalog, and never closes them again. */
    private val _minWeightKg = MutableStateFlow(0f)
    val minWeightKg: StateFlow<Float> = _minWeightKg

    private val _stats = MutableStateFlow(TreeModel.Stats(0, 0, 0, 0))
    val stats: StateFlow<TreeModel.Stats> = _stats

    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons

    private val _message = MutableStateFlow<ShopMessage?>(null)
    val message: StateFlow<ShopMessage?> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** "יומן פרסים": redeemed rewards from the ledger (reason "reward:<id>:<name>"), newest first. */
    val rewardLog: StateFlow<List<LedgerEntity>> = AppGraph.db.game().rewardLedger()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Marks [r] as the "יעד" (gold progress bar), or clears it when it already is. */
    fun toggleGoal(r: RewardEntity) {
        viewModelScope.launch { AppGraph.prefs.update { it.copy(rewardGoalId = if (it.rewardGoalId == r.id) 0L else r.id) } }
    }

    init {
        viewModelScope.launch { runCatching { com.tamir.hamasa.data.repo.Bets.settleMissed() } }
        viewModelScope.launch { seedIfNeeded(); addCatalogOnce() }
        viewModelScope.launch {
            _minWeightKg.value = runCatching { AppGraph.db.weights().allOnce().minOfOrNull { it.kg } }.getOrNull() ?: 0f
        }
        viewModelScope.launch { _stats.value = TreeModel.stats(AppGraph.game.branchLevels()) }
        viewModelScope.launch(Dispatchers.IO) { loadIcons() }
    }

    // ------------------------------------------------------------ rewards

    private suspend fun seedIfNeeded() {
        val sp = app.getSharedPreferences("shop", Context.MODE_PRIVATE)
        if (sp.getBoolean("seeded", false)) return
        try {
            if (dao.count() == 0) {
                val now = System.currentTimeMillis()
                dao.insert(RewardEntity(nameHe = "ערב סרט", priceGold = 300, note = "עם פופקורן, בלי טלפון", createdAt = now))
                dao.insert(RewardEntity(nameHe = "פיצה בלי רגשות אשם", priceGold = 500, note = "", createdAt = now))
                dao.insert(RewardEntity(nameHe = "בוקר עצלן", priceGold = 400, note = "בלי שעון מעורר", createdAt = now))
                dao.insert(RewardEntity(nameHe = "פינוק קטן", priceGold = 150, note = "משהו מתוק אחד", createdAt = now))
            }
            sp.edit().putBoolean("seeded", true).apply()
        } catch (e: Throwable) {
            // the shop must open even if the seed fails
        }
    }

    /**
     * Adds the suggested rewards of [RewardCatalog] to his list once (v1). Names he already has are skipped
     * (merge by name: Room ids are auto-generated, so the name is the only stable key), his own rewards and
     * prices are never touched. Waits for onboarding so a cloud restore on a fresh install comes first.
     */
    private suspend fun addCatalogOnce() {
        val sp = app.getSharedPreferences("shop", Context.MODE_PRIVATE)
        if (sp.getBoolean("catalog_v1", false)) return
        if (!AppGraph.prefs.current().onboarded) return
        try {
            val have = dao.allOnce().map { it.nameHe.trim().replace("\\s+".toRegex(), " ") }.toSet()
            val now = System.currentTimeMillis()
            for (e in RewardCatalog.all) {
                if (e.nameHe in have) continue
                dao.insert(RewardEntity(nameHe = e.nameHe, priceGold = e.priceGold, note = e.noteHe, createdAt = now))
            }
            sp.edit().putBoolean("catalog_v1", true).apply()
            runCatching { com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext) }
        } catch (e: Throwable) {
            // the shop must open even if the catalog fails; it is tried again next time
        }
    }

    fun addReward(nameHe: String, priceGold: Int, note: String) {
        val name = nameHe.trim()
        if (name.isEmpty()) { _message.value = ShopMessage(ShopRules.emptyName()); return }
        viewModelScope.launch {
            dao.insert(RewardEntity(nameHe = name, priceGold = priceGold.coerceAtLeast(1), note = note.trim(), createdAt = System.currentTimeMillis()))
        }
    }

    fun updateReward(r: RewardEntity, nameHe: String, priceGold: Int, note: String) {
        val name = nameHe.trim()
        if (name.isEmpty()) { _message.value = ShopMessage(ShopRules.emptyName()); return }
        viewModelScope.launch { dao.update(r.copy(nameHe = name, priceGold = priceGold.coerceAtLeast(1), note = note.trim())) }
    }

    fun deleteReward(r: RewardEntity) {
        viewModelScope.launch { dao.delete(r) }
    }

    fun buyReward(r: RewardEntity) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                // legendary catalog rewards open at a weight or level milestone (RewardCatalog)
                val entry = RewardCatalog.byName(r.nameHe)
                if (entry != null && !RewardCatalog.isOpen(entry, _minWeightKg.value, LevelTable.levelFor(AppGraph.prefs.current().xp))) {
                    _message.value = ShopMessage(RewardCatalog.lockedLineHe(entry))
                    return@launch
                }
                val ok = AppGraph.game.spendGold(r.priceGold, "reward:${r.id}:${r.nameHe}")
                if (ok) {
                    dao.byId(r.id)?.let { dao.update(it.copy(redeemedCount = it.redeemedCount + 1)) }
                    _message.value = ShopMessage(ShopRules.boughtReward())
                } else {
                    _message.value = ShopMessage(ShopRules.noGold())
                }
            } finally { _busy.value = false }
        }
    }

    // ------------------------------------------------------------ shield

    fun buyShield() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val p = AppGraph.prefs.current()
                // the bag: 3 + lower strength / 3 (StatEffects.maxShields)
                val max = AppGraph.game.maxShields()
                if (p.shields >= max) { _message.value = ShopMessage(ShopRules.shieldFull()); return@launch }
                val ok = AppGraph.game.spendGold(ShopRules.SHIELD_PRICE, "shop:shield")
                if (ok) {
                    AppGraph.prefs.update { it.copy(shields = if (it.shields >= max) it.shields else it.shields + 1) }
                    _message.value = ShopMessage(ShopRules.boughtShield())
                } else {
                    _message.value = ShopMessage(ShopRules.noGold())
                }
            } finally { _busy.value = false }
        }
    }

    // ------------------------------------------------------------ gear

    fun buyGear(item: GearItem) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                if (inventory.value.any { it.itemId == item.id }) return@launch
                val missing = ShopRules.missingHe(item, _stats.value)
                if (missing != null) { _message.value = ShopMessage(missing); return@launch }
                val ok = AppGraph.game.spendGold(ShopRules.gearPrice(item.rarity), "shop:gear:${item.id}")
                if (ok) {
                    AppGraph.game.addItem(item.id)
                    _message.value = ShopMessage(ShopRules.boughtGear())
                } else {
                    _message.value = ShopMessage(ShopRules.noGold())
                }
            } finally { _busy.value = false }
        }
    }

    fun equip(item: GearItem) {
        viewModelScope.launch {
            val ok = AppGraph.game.equip(item.id)
            if (ok) {
                _message.value = ShopMessage(ShopRules.equipped())
            } else {
                // the tree is not strong enough yet: the item stays his, locked
                val missing = ShopRules.missingHe(item, AppGraph.game.stats())
                _message.value = ShopMessage(if (missing != null) "נדרש: " + missing.removePrefix("דורש ") + ". הפריט שלך. מחכה." else "עוד לא.")
            }
        }
    }

    // ------------------------------------------------------------ chest

    fun buyChest() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = AppGraph.game.spendGold(ShopRules.CHEST_PRICE, "shop:chest")
                if (ok) {
                    val drop = AppGraph.game.rollChest()
                    _message.value = ShopMessage(ShopRules.chest(drop), drop)
                } else {
                    _message.value = ShopMessage(ShopRules.noGold())
                }
            } finally { _busy.value = false }
        }
    }

    fun dismissMessage() { _message.value = null }

    // ------------------------------------------------------------ gear art

    private fun loadIcons() {
        val out = mutableMapOf<String, ImageBitmap>()
        for (sheet in 1..4) {
            val name = Assets.gearSheets.getOrNull(sheet - 1) ?: continue
            val resId = app.drawableId(name)
            if (resId == 0) continue
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp: Bitmap = try { BitmapFactory.decodeResource(app.resources, resId, opts) ?: continue } catch (e: Throwable) { continue }
            val (cols, rows) = GearCatalog.sheetGrid[sheet] ?: (1 to 1)
            for (item in GearCatalog.all.filter { it.sheet == sheet }) {
                try {
                    out[item.id] = cropMeasuredScaled(bmp, name, cols, rows, item.index, 256).asImageBitmap()
                } catch (e: Throwable) {
                    // a bad index must never crash the screen; the row simply shows no art
                }
            }
            bmp.recycle()
            _icons.value = out.toMap()
        }
        _icons.value = out.toMap()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShopViewModel(app) as T
    }
}
