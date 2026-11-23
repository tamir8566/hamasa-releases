package com.tamir.hamasa.ui.workout

import android.content.Context
import android.util.Log
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.repo.BossResult
import com.tamir.hamasa.data.repo.SessionRewards
import com.tamir.hamasa.domain.game.BossPhase
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.Rarity
import com.tamir.hamasa.domain.game.WeakGrade
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide handoff between WorkoutScreen (which calls finishSession) and WorkoutDoneScreen
 * (which animates the rewards). Navigation only carries the session id, so the rewards live here.
 *
 * The rewards are also written to SharedPreferences (small JSON), so the done screen still shows them after the
 * process was killed in the background. [last] keeps its old API: setting it stores the rewards (session id
 * unknown), reading it returns the in-memory value or, after process death, the stored one.
 * [save] / [forSession] do the same with the session id checked.
 */
object RewardsHolder {
    private const val TAG = "RewardsHolder"
    private const val PREFS = "last_rewards"
    private const val KEY_JSON = "json"
    private const val KEY_SESSION = "session_id"

    @Volatile private var memory: SessionRewards? = null
    @Volatile private var memorySession: Long = -1L

    var last: SessionRewards?
        get() = memory ?: load(null)
        set(value) = save(-1L, value)

    /** Stores [rewards] for [sessionId] (-1 = unknown) in memory and on disk. */
    fun save(sessionId: Long, rewards: SessionRewards?) {
        memory = rewards
        memorySession = sessionId
        runCatching {
            val sp = AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (rewards == null) {
                sp.edit().clear().apply()
            } else {
                // commit: the done screen opens right after this, and the process may die there
                sp.edit().putString(KEY_JSON, toJson(rewards).toString()).putLong(KEY_SESSION, sessionId).commit()
            }
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    /** The rewards of [sessionId], or null if the stored ones belong to another session. Unknown (-1) ids match. */
    fun forSession(sessionId: Long): SessionRewards? {
        val m = memory
        if (m != null && (memorySession == sessionId || memorySession < 0L)) return m
        return load(sessionId)
    }

    private fun load(sessionId: Long?): SessionRewards? = runCatching<SessionRewards?> {
        val sp = AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_JSON, null) ?: return@runCatching null
        val stored = sp.getLong(KEY_SESSION, -1L)
        if (sessionId != null && stored >= 0L && stored != sessionId) return@runCatching null
        val r = fromJson(JSONObject(raw))
        memory = r
        memorySession = stored
        r
    }.onFailure { Log.w(TAG, "load failed", it) }.getOrNull()

    // ---------------------------------------------------------------- json

    private fun toJson(r: SessionRewards): JSONObject = JSONObject().apply {
        put("xp", r.xp); put("gold", r.gold); put("newLevel", r.newLevel ?: JSONObject.NULL)
        put("chest", r.chest?.let { chestJson(it) } ?: JSONObject.NULL)
        put("newBadges", JSONArray(r.newBadges)); put("prs", JSONArray(r.prs))
        put("streak", r.streak); put("bossHit", r.bossHit)
        put("gearXp", r.gearXp); put("gearGold", r.gearGold); put("shieldSaved", r.shieldSaved)
        put("betWonGold", r.betWonGold); put("potReleasedGold", r.potReleasedGold)
        put("hardBattle", r.hardBattle)
        put("boss", r.boss?.let { bossJson(it) } ?: JSONObject.NULL)
        put("chestSealed", r.chestSealed); put("mapStations", r.mapStations)
        put("levelFrom", r.levelFrom); put("raceCompared", r.raceCompared)
        put("raceDeltaSec", r.raceDeltaSec ?: JSONObject.NULL)
        put("luckyModeId", r.luckyModeId ?: JSONObject.NULL)
        put("silenceBonusXp", r.silenceBonusXp); put("sleepNoteHe", r.sleepNoteHe)
        put("professionXp", r.professionXp)
    }

    private fun fromJson(o: JSONObject): SessionRewards = SessionRewards(
        xp = o.optInt("xp"),
        gold = o.optInt("gold"),
        newLevel = if (o.isNull("newLevel")) null else o.optInt("newLevel"),
        chest = o.optJSONObject("chest")?.let { chestFrom(it) },
        newBadges = strings(o.optJSONArray("newBadges")),
        prs = strings(o.optJSONArray("prs")),
        streak = o.optInt("streak"),
        bossHit = o.optBoolean("bossHit"),
        gearXp = o.optInt("gearXp"),
        gearGold = o.optInt("gearGold"),
        shieldSaved = o.optBoolean("shieldSaved"),
        betWonGold = o.optInt("betWonGold"),
        potReleasedGold = o.optInt("potReleasedGold"),
        hardBattle = o.optBoolean("hardBattle"),
        boss = o.optJSONObject("boss")?.let { bossFrom(it) },
        chestSealed = o.optBoolean("chestSealed"),
        mapStations = o.optInt("mapStations", 1),
        levelFrom = o.optInt("levelFrom"),
        raceCompared = o.optBoolean("raceCompared"),
        raceDeltaSec = if (o.isNull("raceDeltaSec")) null else o.optInt("raceDeltaSec"),
        luckyModeId = if (o.isNull("luckyModeId")) null else o.optString("luckyModeId"),
        silenceBonusXp = o.optInt("silenceBonusXp"),
        sleepNoteHe = o.optString("sleepNoteHe", ""),
        professionXp = o.optInt("professionXp")
    )

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    private fun chestJson(c: ChestDrop): JSONObject = JSONObject().apply {
        put("rarity", c.rarity.name); put("xp", c.xp); put("gold", c.gold)
        put("modeId", c.modeId ?: JSONObject.NULL); put("itemId", c.itemId ?: JSONObject.NULL)
        put("shield", c.shield); put("joker", c.joker)
    }

    private fun chestFrom(o: JSONObject): ChestDrop = ChestDrop(
        rarity = runCatching { Rarity.valueOf(o.optString("rarity")) }.getOrDefault(Rarity.COMMON),
        xp = o.optInt("xp"),
        gold = o.optInt("gold"),
        modeId = if (o.isNull("modeId")) null else o.optString("modeId"),
        itemId = if (o.isNull("itemId")) null else o.optString("itemId"),
        shield = o.optBoolean("shield"),
        joker = o.optBoolean("joker")
    )

    private fun bossJson(b: BossResult): JSONObject = JSONObject().apply {
        put("bossId", b.bossId); put("nameHe", b.nameHe); put("damage", b.damage); put("hits", b.hits)
        put("rounds", b.rounds); put("hpBefore", b.hpBefore); put("hpAfter", b.hpAfter); put("maxHp", b.maxHp)
        put("defeatedNow", b.defeatedNow); put("phaseBefore", b.phaseBefore.name); put("phaseAfter", b.phaseAfter.name)
        put("returningTimes", b.returningTimes); put("grade", b.grade.name); put("weaknessHe", b.weaknessHe)
    }

    private fun bossFrom(o: JSONObject): BossResult = BossResult(
        bossId = o.optString("bossId"),
        nameHe = o.optString("nameHe"),
        damage = o.optInt("damage"),
        hits = o.optInt("hits"),
        rounds = o.optInt("rounds"),
        hpBefore = o.optInt("hpBefore"),
        hpAfter = o.optInt("hpAfter"),
        maxHp = o.optInt("maxHp"),
        defeatedNow = o.optBoolean("defeatedNow"),
        phaseBefore = runCatching { BossPhase.valueOf(o.optString("phaseBefore")) }.getOrDefault(BossPhase.STRENGTH),
        phaseAfter = runCatching { BossPhase.valueOf(o.optString("phaseAfter")) }.getOrDefault(BossPhase.STRENGTH),
        returningTimes = o.optInt("returningTimes"),
        grade = runCatching { WeakGrade.valueOf(o.optString("grade")) }.getOrDefault(WeakGrade.FULL),
        weaknessHe = o.optString("weaknessHe", "")
    )
}
