package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.db.AppDatabase
import com.tamir.hamasa.data.db.BadgeEntity
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.InventoryEntity
import com.tamir.hamasa.data.db.LedgerEntity
import com.tamir.hamasa.data.db.ModeUnlockEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.prefs.Prefs
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.BadgeDef
import com.tamir.hamasa.domain.game.BuffType
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.ChestRoller
import com.tamir.hamasa.domain.game.GameFacts
import com.tamir.hamasa.domain.game.GearCatalog
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeRules
import com.tamir.hamasa.domain.game.ModeVoice
import com.tamir.hamasa.domain.game.Professions
import com.tamir.hamasa.domain.game.SurpriseEvent
import com.tamir.hamasa.domain.game.SurpriseEvents
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.StreakRules
import com.tamir.hamasa.domain.game.StatEffects
import com.tamir.hamasa.domain.tree.TreeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** Result of closing a workout: everything the end-of-workout screen animates. */
data class SessionRewards(
    val xp: Int,
    val gold: Int,
    val newLevel: Int?,
    val chest: ChestDrop?,
    val newBadges: List<String>,
    val prs: List<String>,
    val streak: Int,
    val bossHit: Boolean,
    val gearXp: Int = 0,              // xp added by equipped gear (already included in [xp])
    val gearGold: Int = 0,            // gold added by equipped gear (already included in [gold])
    val shieldSaved: Boolean = false, // a shield covered a missed day and gear kept it from burning
    val betWonGold: Int = 0,          // "הימור על עצמך": stake returned doubled (already granted, not in [gold])
    val potReleasedGold: Int = 0,     // "קופת המפלצת" released by a full week (already granted, not in [gold])
    // --- stage 1 C ---
    val hardBattle: Boolean = false,  // "הקרב הקשה": the xp of this session was doubled (already in [xp])
    val boss: BossResult? = null,     // the boss fight of this session (null = the session could not hit the boss)
    val chestSealed: Boolean = false, // a chest dropped but went into the weekly box (opened in the weekly summary)
    val mapStations: Int = 1,         // stations advanced on the map by this session
    // --- stage 1 B ---
    val levelFrom: Int = 0,           // level before this session ([newLevel] is the level after, when it went up)
    val raceCompared: Boolean = false,// mode "race_clock" was played
    val raceDeltaSec: Int? = null,    // previous duration of the same type minus this one (positive = faster); null = nothing to compare
    val luckyModeId: String? = null,  // a locked mode found by luck at the end of the session (already unlocked)
    val silenceBonusXp: Int = 0,      // mode "silence": bonus already included in [xp]
    // --- stage 1 E ---
    val sleepNoteHe: String = "",     // medium sleep (80%, no record bonus) or short sleep + gym anyway; "" = none
    // --- "מקצוע לשמביולוק" ---
    val professionXp: Int = 0         // xp added by the profession (already included in [xp])
)

/** One of this month's seasonal badges (stage 1 E). */
data class MonthlyBadge(val def: BadgeDef, val earned: Boolean, val daysLeft: Int)

/** Best set of one exercise for the tree (stage 1 E). [ratio] is on the branch's reference scale, 0 = does not count. */
data class BranchExerciseBest(
    val exerciseId: String,
    val nameHe: String,
    val branch: Branch,
    val unit: String,
    val weightKg: Float,
    val reps: Int,
    val oneRmKg: Float,
    val dateEpochDay: Long,
    val ratio: Float
)

/** "מצב שנמצא במזל": chance at the end of a session to open a mode he does not have yet. */
private const val LUCKY_MODE_CHANCE = 0.03

sealed class GameEvent {
    data class BadgeUnlocked(val id: String) : GameEvent()
    data class LevelUp(val level: Int) : GameEvent()
    data class ModeDiscovered(val id: String) : GameEvent()
    /** A legendary badge gave Shambiuluk a gear item. */
    data class GearFromBadge(val badgeId: String, val itemId: String) : GameEvent()
}

/** Jokers from chests that can wait on the morning-roll screen. */
private const val MAX_JOKERS = 3

class GameRepository(private val db: AppDatabase, private val prefs: Prefs) {
    private val LEVELS_V2 = "v2|"

    val events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 16)

    val profile: Flow<Profile> get() = prefs.profile
    val level: Flow<Int> get() = prefs.profile.map { LevelTable.levelFor(it.xp) }
    val badges get() = db.game().badges()
    val inventory get() = db.game().inventory()
    val unlockedModes get() = db.game().modes()

    suspend fun grant(xp: Int, gold: Int, reason: String): Int? {
        var newLevel: Int? = null
        prefs.update { p ->
            val before = LevelTable.levelFor(p.xp)
            val after = LevelTable.levelFor(p.xp + xp)
            if (after > before) newLevel = after
            p.copy(xp = p.xp + xp, gold = p.gold + gold)
        }
        db.game().ledger(LedgerEntity(atMillis = System.currentTimeMillis(), xp = xp, gold = gold, reason = reason))
        newLevel?.let { lvl ->
            events.tryEmit(GameEvent.LevelUp(lvl))
            ModeCatalog.all.filter { it.unlockLevel == lvl }.forEach { unlockMode(it.id) }
            ensureLevelGifts()
        }
        return newLevel
    }

    /**
     * One-time gifts of level gates the player has passed (level 5: one more streak shield). Safe to call any
     * time: each gift is given once. When the shield bag is full the gift waits for a free slot (never lost).
     */
    suspend fun ensureLevelGifts() {
        val p = prefs.current()
        if (p.level5ShieldGiven || LevelTable.levelFor(p.xp) < LevelGates.SECOND_SHIELD) return
        val max = maxShields()
        prefs.update { cur ->
            if (cur.level5ShieldGiven || cur.shields >= max) cur
            else cur.copy(level5ShieldGiven = true, shields = cur.shields + 1)
        }
    }

    suspend fun spendGold(amount: Int, reason: String): Boolean {
        var ok = false
        prefs.update { p -> if (p.gold >= amount) { ok = true; p.copy(gold = p.gold - amount) } else p }
        if (ok) db.game().ledger(LedgerEntity(atMillis = System.currentTimeMillis(), xp = 0, gold = -amount, reason = reason))
        return ok
    }

    suspend fun unlockMode(id: String) {
        if (db.game().unlockMode(ModeUnlockEntity(id, System.currentTimeMillis())) != -1L) events.tryEmit(GameEvent.ModeDiscovered(id))
    }

    suspend fun availableModeIds(): List<String> {
        val unlocked = db.game().modesOnce().map { it.modeId }.toSet()
        val level = LevelTable.levelFor(prefs.current().xp)
        // a level mode is open once the level is reached, even if its unlock row was never written
        return ModeCatalog.all.filter { it.unlockedByDefault || it.id in unlocked || (it.unlockLevel != null && it.unlockLevel <= level) }.map { it.id }
    }

    suspend fun unlockBadge(id: String): Boolean {
        val def = BadgeCatalog.byId(id) ?: return false
        val inserted = db.game().unlockBadge(BadgeEntity(id, System.currentTimeMillis())) != -1L
        if (inserted) {
            val (xp, gold) = when (def.rarity) { Rarity.COMMON -> 50 to 10; Rarity.RARE -> 150 to 40; Rarity.LEGENDARY -> 400 to 120 }
            grant(xp, gold, "badge:$id")
            events.tryEmit(GameEvent.BadgeUnlocked(id))
            // "תג אגדי נותן לשמביולוק פריט ציוד": a random item he does not own yet.
            if (def.rarity == Rarity.LEGENDARY) {
                val owned = db.game().inventoryOnce().map { it.itemId }.toSet()
                val sealedItems = WeeklyBox.decode(prefs.current().sealedChests).mapNotNull { it.drop.itemId }.toSet()
                val candidates = GearCatalog.all.filter { it.id !in owned && it.id !in sealedItems }
                if (candidates.isNotEmpty()) {
                    val item = candidates.random()
                    addItem(item.id)
                    events.tryEmit(GameEvent.GearFromBadge(id, item.id))
                }
            }
        }
        return inserted
    }

    /** Item ids granted by legendary badges, newest first (from the inventory, matched by unlock time). */
    suspend fun gearFromBadge(badgeId: String): String? {
        val badge = db.game().badgesOnce().firstOrNull { it.id == badgeId } ?: return null
        val def = BadgeCatalog.byId(badgeId) ?: return null
        if (def.rarity != Rarity.LEGENDARY) return null
        // the item is added within the same call, a few milliseconds after the badge row
        return db.game().inventoryOnce()
            .filter { it.acquiredAtMillis >= badge.unlockedAtMillis && it.acquiredAtMillis - badge.unlockedAtMillis < 5_000L }
            .minByOrNull { it.acquiredAtMillis }?.itemId
    }

    suspend fun addItem(itemId: String) { db.game().addItem(InventoryEntity(itemId, System.currentTimeMillis())) }

    /**
     * Equips an owned item. Returns false (nothing changes) when the item is unknown, not owned, or the tree is
     * not strong enough yet (GearCatalog.canEquip on the current stats). Owned items are never taken away:
     * a legendary from a chest stays in the inventory, locked, until the requirements are met.
     */
    suspend fun equip(itemId: String): Boolean {
        val item = GearCatalog.byId(itemId) ?: return false
        val inv = db.game().inventoryOnce()
        if (inv.none { it.itemId == itemId }) return false
        val s = stats()
        if (!GearCatalog.canEquip(item, s.upper, s.lower, s.stability, s.endurance)) return false
        val sameSlot = inv.filter { GearCatalog.byId(it.itemId)?.slot == item.slot }.map { it.itemId }
        db.game().unequip(sameSlot)
        db.game().inventoryOnce().firstOrNull { it.itemId == itemId }?.let { db.game().updateItem(it.copy(equipped = true)) }
        return true
    }

    /**
     * Sum of the buff percents of all EQUIPPED inventory items, per buff type.
     * Buffs only add: a missing type simply maps to nothing (treat as 0).
     * "מקצוע לשמביולוק": gear of the profession's own theme counts x1.5, and the profession's own buffs
     * (the monk's shield save) are added here too (Professions.gearMultiplier / extraBuffs).
     */
    suspend fun equippedBuffs(): Map<BuffType, Float> {
        val out = mutableMapOf<BuffType, Float>()
        val profile = prefs.current()
        val profession = Professions.active(profile.professionId, profile.xp)
        for (inv in db.game().inventoryOnce()) {
            if (!inv.equipped) continue
            val item = GearCatalog.byId(inv.itemId) ?: continue
            val pct = item.buff.percent * Professions.gearMultiplier(profession, item)
            out[item.buff.type] = (out[item.buff.type] ?: 0f) + pct
        }
        for ((type, pct) in Professions.extraBuffs(profession)) {
            out[type] = (out[type] ?: 0f) + pct
        }
        return out
    }

    /**
     * Streak with shield: called once per day with activity.
     * Returns true when a shield covered a missed day AND the equipped SHIELD_SAVE buff kept it
     * from being consumed (the streak is saved either way; the shield just is not decremented).
     */
    suspend fun registerActivity(day: Long, rnd: Random = Random.Default): Boolean {
        val savePercent = equippedBuffs()[BuffType.SHIELD_SAVE] ?: 0f
        val saveRoll = savePercent > 0f && rnd.nextFloat() * 100f < savePercent
        var shieldSaved = false
        prefs.update { p ->
            if (p.lastActiveDay == day) return@update p
            // One free rest day per Sunday–Saturday week; any further missed day in that week uses a shield.
            val out = StreakRules.onActivity(p.lastActiveDay, day, p.streak, p.shields, p.restWeekUsed)
            // One gear roll can keep one of the used shields from burning.
            val saved = out.shieldsUsed > 0 && saveRoll
            shieldSaved = saved
            val burned = if (saved) out.shieldsUsed - 1 else out.shieldsUsed
            p.copy(
                streak = out.streak,
                bestStreak = maxOf(p.bestStreak, out.streak),
                lastActiveDay = maxOf(p.lastActiveDay, day),   // clock moved back: keep the later day
                shields = (p.shields - burned).coerceAtLeast(0),
                restWeekUsed = out.restWeekUsed
            )
        }
        return shieldSaved
    }

    /**
     * Rolls a chest and applies its contents right away (shop rolls, Friday/Saturday sessions).
     * [commonWeighted]: the common-weighted table of non-gym sessions (ChestRoller).
     */
    suspend fun rollChest(commonWeighted: Boolean = false): ChestDrop {
        val drop = rollOnly(commonWeighted)
        applyChest(drop)
        return drop
    }

    /**
     * Rolls the odds (and moves the luck chain) without applying anything. Modes and items that are
     * already waiting in the sealed weekly box are excluded, so two sealed chests never hold the same thing.
     */
    private suspend fun rollOnly(commonWeighted: Boolean = false): ChestDrop {
        val p = prefs.current()
        val sealed = WeeklyBox.decode(p.sealedChests)
        val sealedModes = sealed.mapNotNull { it.drop.modeId }.toSet()
        val sealedItems = sealed.mapNotNull { it.drop.itemId }.toSet()
        val lockedModes = ModeCatalog.all.filter { it.fromChest }.map { it.id } - db.game().modesOnce().map { it.modeId }.toSet() - sealedModes
        val owned = db.game().inventoryOnce().map { it.itemId }.toSet()
        val lockedItems = GearCatalog.all.filter { it.id !in owned && it.id !in sealedItems }.map { it.id }
        val luck = equippedBuffs()[BuffType.CHEST_LUCK] ?: 0f
        // level gate: rare and legendary chests enter the draw at level 3; before that the luck chain does not grow
        val rareOpen = LevelGates.rareChestsOpen(LevelTable.levelFor(p.xp))
        val drop = ChestRoller.roll(p.luckChain, lockedModes, lockedItems, luckPercent = luck, rareOpen = rareOpen, commonWeighted = commonWeighted)
        // the chain grows per consecutive-day session (advanceLuckChain), not per chest; a legendary resets it
        if (drop.rarity == Rarity.LEGENDARY) prefs.update { it.copy(luckChain = 0) }
        return drop
    }

    /**
     * "שרשרת מזל": call once per finished session, AFTER registerActivity, with the profile from before it.
     * Another session on an already active day changes nothing; a session that continued the streak adds 1
     * (only once rare chests are open, see LevelGates); a broken streak (missed day) resets the chain to 0.
     */
    suspend fun advanceLuckChain(day: Long, before: Profile) {
        if (before.lastActiveDay == day) return
        val after = prefs.current()
        val continued = after.streak > before.streak && before.lastActiveDay > 0L
        val rareOpen = LevelGates.rareChestsOpen(LevelTable.levelFor(after.xp))
        prefs.update { cur ->
            when {
                !continued -> cur.copy(luckChain = 0)
                rareOpen -> cur.copy(luckChain = cur.luckChain + 1)
                else -> cur
            }
        }
    }

    /** "השתמש בג'וקר" on the morning roll: spends one stored joker and sets today's mode to [modeId]. */
    suspend fun useJoker(modeId: String, day: Long = Days.today()): Boolean {
        if (modeId == "joker" || modeId !in availableModeIds()) return false
        var ok = false
        prefs.update { p ->
            if (p.jokers <= 0) p
            // jokerPickDay: the mode he picked himself stays (fitRollToSleep leaves it alone)
            else { ok = true; p.copy(jokers = p.jokers - 1, rollModeId = modeId, jokerPickDay = day) }
        }
        return ok
    }

    /** Gives the contents of a chest: xp/gold, mode, item, shield (up to the bag size). Counts it as opened. */
    suspend fun applyChest(drop: ChestDrop) {
        if (drop.xp > 0 || drop.gold > 0) grant(drop.xp, drop.gold, "chest")
        drop.modeId?.let { unlockMode(it) }
        drop.itemId?.let { addItem(it) }
        if (drop.joker) unlockMode("joker")   // the chest's joker card opens mode "joker" (no-op when already open)
        // ...and is kept as a card to play on the morning roll ("השתמש בג'וקר"), up to 3
        if (drop.joker) prefs.update { it.copy(jokers = (it.jokers + 1).coerceAtMost(MAX_JOKERS)) }
        if (drop.shield) {
            val max = maxShields()
            prefs.update { it.copy(shields = if (it.shields >= max) it.shields else it.shields + 1) }
        }
        prefs.update { it.copy(chestsOpened = it.chestsOpened + 1) }
        unlockBadge("first_chest")
    }

    /** "קופסת סוף השבוע": rolls a chest now but keeps it sealed until the weekly summary opens the box. */
    suspend fun rollSealedChest(day: Long, commonWeighted: Boolean = false): ChestDrop {
        val drop = rollOnly(commonWeighted)
        val week = Days.weekStartOf(day)
        prefs.update { p -> p.copy(sealedChests = WeeklyBox.encode(WeeklyBox.decode(p.sealedChests) + WeeklyBox.Sealed(week, drop))) }
        return drop
    }

    /** Sealed chests waiting in the box. */
    suspend fun sealedChests(): List<WeeklyBox.Sealed> = WeeklyBox.decode(prefs.current().sealedChests)

    /** Serializes [openSealedChests] so a double tap can never apply the same box twice. */
    private val openBoxLock = Mutex()

    /**
     * Opens the weekly box: applies each drop FIRST and only then removes that one chest from the box, so a
     * failure or a process kill midway never loses a chest (at worst the chest being applied stays in the box).
     * The lock keeps a double tap from applying anything twice.
     */
    suspend fun openSealedChests(): List<ChestDrop> = openBoxLock.withLock {
        val taken = WeeklyBox.decode(prefs.current().sealedChests)
        val opened = ArrayList<ChestDrop>()
        for (s in taken) {
            applyChest(s.drop)
            opened.add(s.drop)
            prefs.update { p ->
                val left = WeeklyBox.decode(p.sealedChests).toMutableList()
                val i = left.indexOf(s)
                if (i >= 0) left.removeAt(i)
                p.copy(sealedChests = WeeklyBox.encode(left))
            }
        }
        opened
    }

    /** Current stats of Shambiuluk (from the branch levels). */
    suspend fun stats(): TreeModel.Stats = TreeModel.stats(branchLevels())

    /** "התיק": how many streak shields can be held. 3 + lower strength / 3. */
    suspend fun maxShields(): Int = StatEffects.maxShields(stats())

    /**
     * Stability gives +1 streak shield per level at the start of each month (once per calendar month),
     * up to the bag size. Shields above the bag size (from older rules) are never taken away.
     * Returns how many were added.
     */
    suspend fun grantMonthlyShields(today: Long = Days.today()): Int {
        val date = Days.of(today)
        val month = date.year * 12 + date.monthValue
        if (prefs.current().stabilityShieldMonth == month) return 0
        val s = stats()
        val max = StatEffects.maxShields(s)
        val add = StatEffects.monthlyShields(s)
        var added = 0
        prefs.update { p ->
            if (p.stabilityShieldMonth == month) return@update p
            val target = if (p.shields >= max) p.shields else minOf(max, p.shields + add)
            added = (target - p.shields).coerceAtLeast(0)
            p.copy(shields = maxOf(p.shields, target), stabilityShieldMonth = month)
        }
        return added
    }

    /** Consecutive days with a weigh-in, ending on the latest weigh-in day. */
    suspend fun weighInStreak(): Int {
        val days = db.weights().allOnce().map { it.dateEpochDay }.toSortedSet()
        if (days.isEmpty()) return 0
        var d = days.last()
        var n = 0
        while (d in days) { n++; d-- }
        return n
    }

    /**
     * Best set of every exercise (catalog + custom) that has completed sets, by relative strength on its
     * branch's reference scale (TreeModel.relativeStrength / holdRatio). Exercises that cannot count
     * (bodyweight reps, mobility holds, assisted pull-up) are still listed with ratio 0.
     */
    suspend fun exerciseBests(): List<BranchExerciseBest> {
        val bw = db.weights().latestOnce()?.kg ?: prefs.current().startWeightKg
        val exercises = db.exercises().allOnce().associateBy { it.id }
        val dayOf = db.sessions().allSessionsOnce().associate { it.id to it.dateEpochDay }
        val out = ArrayList<BranchExerciseBest>()
        for ((id, sets) in db.sessions().allSetsOnce().filter { it.completed }.groupBy { it.exerciseId }) {
            val e = exercises[id] ?: continue
            var best: BranchExerciseBest? = null
            for (st in sets) {
                val ratio = if (e.unit == "sec") TreeModel.holdRatio(id, st.reps)
                else TreeModel.relativeStrength(id, e.branch, e.isCompound, e.equipment, e.unit, st.weight, st.reps, bw)
                val oneRm = if (e.unit == "kg") TreeModel.epley1Rm(st.weight, st.reps) else 0f
                val cand = BranchExerciseBest(id, e.nameHe, e.branch, e.unit, st.weight, st.reps, oneRm, dayOf[st.sessionId] ?: 0L, ratio)
                val b = best
                // best by ratio; for exercises that do not count, by load (then reps)
                val better = b == null || cand.ratio > b.ratio ||
                    (cand.ratio == b.ratio && (cand.oneRmKg > b.oneRmKg || (cand.oneRmKg == b.oneRmKg && cand.reps > b.reps)))
                if (better) best = cand
            }
            best?.let { out.add(it) }
        }
        return out
    }

    /**
     * Branch levels: each branch from the BEST relative strength across all its exercises (catalog + custom).
     * ENDURANCE from non-gym sessions in the last 4 weeks. A level once reached never goes down
     * (max-ever per branch kept in Prefs.branchMaxCsv).
     */
    /**
     * "העץ מתחיל ברמה 4" (like the map's mapStartDone): the tree counts only progress made after the player reached
     * LevelGates.TREE. The marker is the id of the latest finished session at that moment (that session itself does
     * not count, like map station 0). A player already at level 4+ without a marker gets it set to "now" once.
     * Branch levels already shown never drop: branchLevels keeps the cached max ("v2|" in Prefs.branchMaxCsv).
     * Returns the marker, or -1 while the tree is still closed.
     */
    suspend fun ensureTreeStart(): Long {
        val p = prefs.current()
        if (p.treeStartSessionId >= 0L) return p.treeStartSessionId
        if (!LevelGates.treeOpen(LevelTable.levelFor(p.xp))) return -1L
        val all = db.sessions().doneSessionsOnce()
        val lastId = all.maxOfOrNull { it.id } ?: 0L
        // Levels already shown live in the never-drop "v2|" cache (branchLevels writes it every time the tree is
        // computed), so the old progress may stop counting without anything visibly dropping. Safety net for an
        // install past the gate whose cache is missing: keep what the old progress earned before it stops counting.
        if (!p.branchMaxCsv.startsWith(LEVELS_V2) && LevelTable.levelFor(p.xp) > LevelGates.TREE) runCatching { keepLevelsOf(all) }
        prefs.update { cur ->
            if (cur.treeStartSessionId >= 0L) cur else cur.copy(treeStartSessionId = lastId, treeStartDay = Days.today())
        }
        return prefs.current().treeStartSessionId
    }

    /** Finished sessions that count for the tree (after the level-4 marker). Empty while the tree is closed. */
    suspend fun treeSessions(): List<com.tamir.hamasa.data.db.SessionEntity> {
        val start = ensureTreeStart()
        if (start < 0L) return emptyList()
        return db.sessions().doneSessionsOnce().filter { it.id > start }
    }

    /** Non-gym sessions in the last 4 weeks that count for the endurance branch (after the tree marker). */
    suspend fun enduranceSessions(today: Long = Days.today()): Int =
        treeSessions().count { it.dateEpochDay >= today - 28 && it.type != SessionType.GYM }

    /** Merges the levels that [sessions] earn into the "v2|" max cache (only raises). Used once, when the marker is set. */
    private suspend fun keepLevelsOf(sessions: List<com.tamir.hamasa.data.db.SessionEntity>) {
        val progress = progressOf(sessions)
        val today = Days.today()
        val earned = Branch.values().associateWith { b ->
            if (b == Branch.ENDURANCE) {
                TreeModel.levelFromRatio(Branch.ENDURANCE, sessions.count { it.dateEpochDay >= today - 28 && it.type != SessionType.GYM } / 10f)
            } else {
                val (pr, ses) = progress[b] ?: Pair(0, 0)
                TreeModel.progressLevel(pr, ses)
            }
        }
        prefs.update { p ->
            val curCsv = p.branchMaxCsv
            val cur = if (curCsv.startsWith(LEVELS_V2)) TreeModel.parseLevels(curCsv.removePrefix(LEVELS_V2)) else emptyMap()
            val merged = Branch.values().associateWith { b -> maxOf(earned[b] ?: 0, cur[b] ?: 0) }
            p.copy(branchMaxCsv = LEVELS_V2 + TreeModel.encodeLevels(merged))
        }
    }

    /** Per branch: (records broken, finished sessions that trained it) since the tree opened. Records only count real ones (isPr). */
    suspend fun branchProgress(): Map<Branch, Pair<Int, Int>> = progressOf(treeSessions())

    private suspend fun progressOf(list: List<com.tamir.hamasa.data.db.SessionEntity>): Map<Branch, Pair<Int, Int>> {
        val prs = HashMap<Branch, Int>()
        val sessions = HashMap<Branch, Int>()
        val branchCache = HashMap<String, Branch>()
        suspend fun branchOf(id: String): Branch = branchCache.getOrPut(id) {
            db.exercises().byId(id)?.branch ?: com.tamir.hamasa.domain.coach.CoachEngine.branchOf(id)
        }
        for (s in list) {
            val hit = HashSet<Branch>()
            for (st in db.sessions().setsForOnce(s.id)) {
                if (!st.completed) continue
                val b = branchOf(st.exerciseId)
                if (b == Branch.ENDURANCE) continue
                hit.add(b)
                if (st.isPr) prs[b] = (prs[b] ?: 0) + 1
            }
            for (b in hit) sessions[b] = (sessions[b] ?: 0) + 1
        }
        return Branch.values().associateWith { Pair(prs[it] ?: 0, sessions[it] ?: 0) }
    }

    /**
     * Branch levels grow from PROGRESS, starting at 0: every 2 records broken in the branch = +1, every 4 sessions
     * that trained it = +1 (max 10). Endurance: non-gym sessions in the last 4 weeks. Never goes down (max kept).
     * Stored max levels carry a "v2|" prefix; older stored values (absolute-strength model) are ignored.
     */
    suspend fun branchLevels(): Map<Branch, Int> {
        val progress = branchProgress()
        val raw = mutableMapOf<Branch, Int>()
        for (b in Branch.values()) {
            if (b == Branch.ENDURANCE) continue
            val (pr, ses) = progress[b] ?: Pair(0, 0)
            raw[b] = TreeModel.progressLevel(pr, ses)
        }
        val endurance = enduranceSessions()
        raw[Branch.ENDURANCE] = TreeModel.levelFromRatio(Branch.ENDURANCE, endurance / 10f)

        val storedCsv = prefs.current().branchMaxCsv
        val stored = if (storedCsv.startsWith(LEVELS_V2)) TreeModel.parseLevels(storedCsv.removePrefix(LEVELS_V2)) else emptyMap()
        val out = mutableMapOf<Branch, Int>()
        for (b in Branch.values()) out[b] = maxOf(raw[b] ?: 0, stored[b] ?: 0)
        if (!storedCsv.startsWith(LEVELS_V2) || out.any { (b, v) -> v > (stored[b] ?: 0) }) {
            prefs.update { p ->
                val curCsv = p.branchMaxCsv
                val cur = if (curCsv.startsWith(LEVELS_V2)) TreeModel.parseLevels(curCsv.removePrefix(LEVELS_V2)) else emptyMap()
                val merged = Branch.values().associateWith { b -> maxOf(out[b] ?: 0, cur[b] ?: 0) }
                p.copy(branchMaxCsv = LEVELS_V2 + TreeModel.encodeLevels(merged))
            }
        }
        return out
    }

    suspend fun buildFacts(lastSessionId: Long?): GameFacts {
        val p = prefs.current()
        val done = db.sessions().doneSessionsOnce()
        val last = lastSessionId?.let { db.sessions().byId(it) }
        val startHour = last?.startedAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour } ?: 12
        val sets = lastSessionId?.let { db.sessions().setsForOnce(it) } ?: emptyList()
        val prsThis = sets.count { it.isPr }
        val prevDone = done.drop(1).firstOrNull()
        val gapDays = if (last != null && prevDone != null) (last.dateEpochDay - prevDone.dateEpochDay).toInt() else 0
        val today = Days.today()
        val monthStart = Days.of(today).withDayOfMonth(1).toEpochDay()
        val levels = branchLevels()
        val exerciseIdsBefore = done.filter { it.id != lastSessionId }.take(30)
        return GameFacts(
            doneSessions = done.size,
            gymSessions = done.count { it.type == SessionType.GYM },
            yogaSessions = done.count { it.type == SessionType.YOGA },
            streak = p.streak, bestStreak = p.bestStreak,
            prCount = db.sessions().prCount(), prsThisSession = prsThis,
            lastSessionDurationSec = last?.durationSec ?: 0,
            lastSessionStartHour = startHour,
            lastSessionSleepHours = last?.sleepHours,
            lastSessionVolumeKg = lastSessionId?.let { db.sessions().sessionVolume(it) } ?: 0.0,
            lifetimeKg = p.lifetimeKg,
            distinctModesDone = db.sessions().distinctModesDone().size,
            bossesDefeated = maxOf(db.game().bossesDefeated(), prefs.current().bossesDefeatedTotal),
            mealsDaysLogged = db.meals().daysLogged(),
            returnedAfterDaysOff = gapDays,
            lastSessionIsWeekend = last?.let { Days.isWeekend(it.dateEpochDay) } ?: false,
            lastSessionDayOfWeek = last?.let { Days.of(it.dateEpochDay).dayOfWeek.value } ?: 1,
            branchLevels = levels.mapKeys { it.key.name },
            newExerciseInLastSession = newExerciseIn(lastSessionId, sets),
            allRestsKept = last != null && p.restBrokenSessionId != last.id && sets.count { it.completed } >= 2,
            monthlyYogaCount = done.count { it.type == SessionType.YOGA && it.dateEpochDay >= monthStart },
            level = LevelTable.levelFor(p.xp),
            lastSessionModeId = last?.modeId,
            modeCounts = modeCountsWithJoker(done, p.jokerPickDay),
            chestsOpened = p.chestsOpened,
            weighInStreak = weighInStreak(),
            progressPhotos = progressPhotosCount(),
            goodGuessMeals = p.goodGuessMeals,
            challengesCompleted = p.challengesCompleted
        )
    }

    /**
     * modeId -> finished sessions. A rolled "joker" is replaced by the mode he picked (Prefs.jokerPickDay),
     * so a finished session on that day also counts as a "joker" session.
     */
    private fun modeCountsWithJoker(done: List<com.tamir.hamasa.data.db.SessionEntity>, jokerPickDay: Long): Map<String, Int> {
        val counts = done.mapNotNull { it.modeId }.groupingBy { it }.eachCount().toMutableMap()
        if (jokerPickDay > 0L && done.any { it.dateEpochDay == jokerPickDay }) counts["joker"] = (counts["joker"] ?: 0) + 1
        return counts
    }

    /** Number of saved progress photos (stage 1 D table). Never fails the badge evaluation. */
    suspend fun progressPhotosCount(): Int = runCatching { db.progress().photoCount() }.getOrDefault(0)

    /** True when an exercise completed in the session never had a completed set in an earlier finished session. */
    private suspend fun newExerciseIn(sessionId: Long?, sets: List<com.tamir.hamasa.data.db.SetLogEntity>): Boolean {
        if (sessionId == null) return false
        val doneIds = db.sessions().doneSessionsOnce().map { it.id }.toSet()
        for (id in sets.filter { it.completed }.map { it.exerciseId }.distinct()) {
            val earlier = db.sessions().recentSetsFor(id, 500).any { it.sessionId != sessionId && it.sessionId in doneIds }
            if (!earlier) return true
        }
        return false
    }

    suspend fun evaluateBadges(lastSessionId: Long?): List<String> {
        val facts = buildFacts(lastSessionId)
        monthlyBadges() // records this month's seasonal badges as shown, so they belong to this month
        // seasonal badges of a month that already ended can never be earned again ("מה שלא נתפס, לא חוזר")
        val have = db.game().badgesOnce().map { it.id }.toSet() + expiredSeasonalIds()
        val fresh = BadgeCatalog.evaluate(facts, have)
        fresh.forEach { unlockBadge(it) }
        return fresh
    }

    // ------------------------------------------------------------ monthly badges (stage 1 E)

    /**
     * "תגי החודש": the seasonal badges of the current month. Each one is tied to the year+month it was first
     * shown (Prefs.seasonalMonthsCsv); that is recorded here. [daysLeft] counts today.
     */
    suspend fun monthlyBadges(today: Long = Days.today()): List<MonthlyBadge> {
        val date = Days.of(today)
        val key = date.year * 12 + date.monthValue
        val current = BadgeCatalog.all.filter { it.seasonMonth == date.monthValue }
        if (current.isNotEmpty()) {
            prefs.update { p ->
                val m = parseSeasonal(p.seasonalMonthsCsv).toMutableMap()
                var changed = false
                for (b in current) if (!m.containsKey(b.id)) { m[b.id] = key; changed = true }
                if (changed) p.copy(seasonalMonthsCsv = encodeSeasonal(m)) else p
            }
        }
        val months = parseSeasonal(prefs.current().seasonalMonthsCsv)
        val have = db.game().badgesOnce().map { it.id }.toSet()
        val daysLeft = date.lengthOfMonth() - date.dayOfMonth + 1
        return current
            .filter { months[it.id] == key || it.id in have }
            .map { MonthlyBadge(it, it.id in have, daysLeft) }
    }

    /** Seasonal badges whose month passed without being earned: gone forever (earned ones always stay). */
    suspend fun expiredSeasonalIds(today: Long = Days.today()): Set<String> {
        val date = Days.of(today)
        val key = date.year * 12 + date.monthValue
        val months = parseSeasonal(prefs.current().seasonalMonthsCsv)
        val have = db.game().badgesOnce().map { it.id }.toSet()
        return BadgeCatalog.all
            .filter { it.seasonMonth != null && it.id !in have && (months[it.id] ?: key) != key }
            .map { it.id }.toSet()
    }

    private fun parseSeasonal(csv: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (piece in csv.split(',')) {
            val kv = piece.split(':')
            if (kv.size != 2) continue
            val v = kv[1].trim().toIntOrNull() ?: continue
            if (kv[0].isNotBlank()) out[kv[0].trim()] = v
        }
        return out
    }

    private fun encodeSeasonal(m: Map<String, Int>): String = m.entries.joinToString(",") { "${it.key}:${it.value}" }

    /**
     * Today's mode: 20% pure random, otherwise weighted by the star ratings of past gym sessions done in each mode.
     * Never the last rolled mode or a mode done yesterday; modes that do not fit the week's focus or last night's
     * sleep ([band]) are left out (see [ModeRules.candidates]). The sleep can also be applied later with [fitRollToSleep].
     */
    private suspend fun chooseMode(day: Long, available: List<String>, p: Profile, band: SleepBand, rnd: Random): String {
        val done = db.sessions().doneSessionsOnce()
        val exclude = HashSet<String>()
        if (p.rollModeId.isNotEmpty()) exclude.add(p.rollModeId)
        for (s in done) {
            if (s.dateEpochDay == day - 1) s.modeId?.let { exclude.add(it) }
        }
        val ratings = HashMap<String, MutableList<Int>>()
        for (s in done) {
            val m = s.modeId ?: continue
            if (s.type == SessionType.GYM && s.rating in 1..5) ratings.getOrPut(m) { mutableListOf() }.add(s.rating)
        }
        val cands = ModeRules.candidates(available, p.focus, band, exclude)
        return ModeRules.pick(cands, ratings, rnd)
    }

    /**
     * Morning roll screen: when last night's sleep rules today's mode out (short sleep: no heavy or time-pressure
     * modes), re-picks a fitting one. Stable for the day and the sleep band, so moving the slider back and forth
     * cannot fish for modes. A mode he picked himself with the joker stays. Returns true when the mode changed.
     */
    suspend fun fitRollToSleep(day: Long, band: SleepBand): Boolean {
        val p = prefs.current()
        if (p.lastRollDay != day || p.rollModeId.isEmpty() || p.jokerPickDay == day) return false
        if (ModeRules.fitsSleep(p.rollModeId, band)) return false
        val mode = chooseMode(day, availableModeIds(), p, band, Random(day * 31L + band.ordinal))
        if (mode == p.rollModeId) return false
        prefs.update { it.copy(rollModeId = mode) }
        return true
    }

    /** Mode "joker": he picks today's mode himself, from every mode he has open. */
    suspend fun pickJokerMode(day: Long, modeId: String): Boolean {
        val p = prefs.current()
        if (p.lastRollDay != day || p.rollModeId != "joker") return false
        if (modeId == "joker" || modeId !in availableModeIds()) return false
        prefs.update { it.copy(rollModeId = modeId, jokerPickDay = day) }
        return true
    }

    /** "מצב שנמצא במזל": about 3% at the end of a session, a mode he does not have yet opens, whatever his level. */
    suspend fun rollLuckyMode(rnd: Random = Random.Default): String? {
        if (rnd.nextDouble() >= LUCKY_MODE_CHANCE) return null
        val open = availableModeIds().toSet()
        val locked = ModeCatalog.all.map { it.id }.filter { it !in open }
        if (locked.isEmpty()) return null
        val id = locked[rnd.nextInt(locked.size)]
        unlockMode(id)
        return id
    }

    // ------------------------------------------------------------ surprise events during a workout

    /** Decides once per session whether an event comes, and after which accessory set (1-based). */
    suspend fun planEvent(sessionId: Long, accessorySets: Int, rnd: Random = Random.Default) {
        val p = prefs.current()
        if (p.eventPlanSessionId == sessionId) return
        val chance = SurpriseEvents.sessionChance(p.sessionsSinceEvent)
        val at = if (accessorySets > 0 && rnd.nextDouble() < chance) 1 + rnd.nextInt(accessorySets) else -1
        prefs.update { it.copy(eventPlanSessionId = sessionId, eventPlanSetNo = at) }
    }

    /** True exactly once per session: when [doneAccessorySets] reaches the planned accessory set. */
    suspend fun takeEventIfDue(sessionId: Long, doneAccessorySets: Int): Boolean {
        var due = false
        prefs.update { p ->
            if (p.eventPlanSessionId == sessionId && p.eventPlanSetNo > 0 && doneAccessorySets >= p.eventPlanSetNo) {
                due = true
                p.copy(eventPlanSetNo = 0, sessionsSinceEvent = 0)
            } else p
        }
        return due
    }

    suspend fun eventAccepted() {
        prefs.update { it.copy(eventsAccepted = it.eventsAccepted + 1) }
    }

    /**
     * Grants a completed event (20/40/60 xp, 5/12/20 gold). A hard one has a 25% chance of a rare extra:
     * a streak shield (when the bag has room) or a chest. Returns the extra line ("" = none) and the chest, if any.
     */
    suspend fun completeEvent(ev: SurpriseEvent, rnd: Random = Random.Default): Pair<String, ChestDrop?> {
        prefs.update { it.copy(eventsCompleted = it.eventsCompleted + 1) }
        grant(ev.xp, ev.gold, "event:${ev.id}")
        if (!ev.isHard || rnd.nextDouble() >= SurpriseEvents.RARE_EXTRA_CHANCE) return Pair("", null)
        val max = maxShields()
        if (rnd.nextBoolean() && prefs.current().shields < max) {
            prefs.update { it.copy(shields = if (it.shields >= max) it.shields else it.shields + 1) }
            return Pair(ModeVoice.eventRareShield(), null)
        }
        val drop = rollChest()
        return Pair(ModeVoice.eventRareChest(), drop)
    }

    /** End of a gym session: one more session without an event, unless the event fired in it. */
    suspend fun countSessionForEvents(sessionId: Long) {
        prefs.update { p ->
            if (p.eventPlanSessionId == sessionId && p.eventPlanSetNo == 0) p
            else p.copy(sessionsSinceEvent = p.sessionsSinceEvent + 1)
        }
    }

    suspend fun rollMorning(day: Long, availableModes: List<String>, rnd: Random = Random.Default, band: SleepBand = SleepBand.FULL) {
        val p = prefs.current()
        if (p.lastRollDay == day) return
        ensureLevelGifts()
        val mode = chooseMode(day, availableModes, p, band, rnd)
        val goals = listOf("לשבור שיא בתרגיל אחד", "לסיים ב-5 דקות פחות מהפעם הקודמת", "חזרה נוספת בסט האחרון", "לעמוד בכל זמני המנוחה", "לנסות תרגיל שלא עשית מזמן")
        val bonus = when (rnd.nextInt(10)) { 0, 1 -> "double_xp"; 2 -> "chest"; else -> "none" }
        prefs.update { it.copy(lastRollDay = day, rollModeId = mode, rollGoal = goals.random(rnd), rollBonus = bonus) }
    }
}
