package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.game.ChestDrop
import com.tamir.hamasa.domain.game.Rarity
import java.time.DayOfWeek

/**
 * "קופסת סוף השבוע": chests that drop from sessions on Sunday..Thursday are rolled at once but kept
 * sealed; the weekly summary opens them all together (Friday morning onwards). Friday/Saturday sessions
 * open their chest normally. The box is never emptied by time: an unopened box simply waits.
 *
 * Stored in Prefs.sealedChests as "week;RARITY;xp;gold;modeId;itemId;shield;joker" entries joined by '|'.
 */
object WeeklyBox {
    data class Sealed(val week: Long, val drop: ChestDrop)

    /** Sunday..Thursday sessions seal their chest. */
    fun sealsChest(day: Long): Boolean = !Days.isWeekend(day)

    /** The box can be opened from Friday 06:00 through Saturday, or any time it holds chests from an earlier week. */
    fun canOpen(sealed: List<Sealed>, today: Long = Days.today(), hour: Int = java.time.LocalTime.now().hour): Boolean {
        if (sealed.isEmpty()) return false
        if (sealed.any { it.week < Days.weekStartOf(today) }) return true
        val dow = Days.of(today).dayOfWeek
        return (dow == DayOfWeek.FRIDAY && hour >= 6) || dow == DayOfWeek.SATURDAY
    }

    /** Friday from 06:00 until the end of Saturday: the weekly summary is "waiting". */
    fun isSummaryTime(today: Long = Days.today(), hour: Int = java.time.LocalTime.now().hour): Boolean {
        val dow = Days.of(today).dayOfWeek
        return (dow == DayOfWeek.FRIDAY && hour >= 6) || dow == DayOfWeek.SATURDAY
    }

    fun encode(list: List<Sealed>): String = list.joinToString("|") { s ->
        val d = s.drop
        listOf(
            s.week.toString(), d.rarity.name, d.xp.toString(), d.gold.toString(),
            d.modeId ?: "", d.itemId ?: "", if (d.shield) "1" else "0", if (d.joker) "1" else "0"
        ).joinToString(";")
    }

    fun decode(raw: String): List<Sealed> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Sealed>()
        for (piece in raw.split('|')) {
            val f = piece.split(';')
            if (f.size < 8) continue
            val week = f[0].toLongOrNull() ?: continue
            val rarity = runCatching { Rarity.valueOf(f[1]) }.getOrNull() ?: continue
            val drop = ChestDrop(
                rarity = rarity,
                xp = f[2].toIntOrNull() ?: 0,
                gold = f[3].toIntOrNull() ?: 0,
                modeId = f[4].ifBlank { null },
                itemId = f[5].ifBlank { null },
                shield = f[6] == "1",
                joker = f[7] == "1"
            )
            out.add(Sealed(week, drop))
        }
        return out
    }
}

/**
 * "הקרב הקשה": the mid-week gym session. A GYM session on Sunday..Thursday that is the 2nd (or later)
 * finished gym session of its Sunday..Saturday week gets double XP, once per week.
 */
object HardBattle {
    suspend fun qualifies(session: SessionEntity): Boolean {
        if (session.type != SessionType.GYM) return false
        if (Days.isWeekend(session.dateEpochDay)) return false
        val week = Days.weekStartOf(session.dateEpochDay)
        if (AppGraph.prefs.current().hardBattleWeek == week) return false
        val gymBefore = AppGraph.db.sessions().doneSessionsOnce().count {
            it.id != session.id && it.type == SessionType.GYM && it.dateEpochDay in week..session.dateEpochDay
        }
        return gymBefore >= 1
    }

    suspend fun markUsed(day: Long) {
        val week = Days.weekStartOf(day)
        AppGraph.prefs.update { it.copy(hardBattleWeek = week) }
    }
}

/** "ספר הקרבות": one Shambiuluk line per finished session, kept in Prefs.battleLog. */
object BattleLog {
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'
    private const val MAX_ENTRIES = 400

    fun decode(raw: String): Map<Long, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, String>()
        for (rec in raw.split(RECORD)) {
            val i = rec.indexOf(FIELD)
            if (i <= 0) continue
            val id = rec.substring(0, i).toLongOrNull() ?: continue
            out[id] = rec.substring(i + 1)
        }
        return out
    }

    private fun encode(map: Map<Long, String>): String =
        map.entries.joinToString(RECORD.toString()) { "${it.key}$FIELD${clean(it.value)}" }

    private fun clean(line: String): String = line.replace(FIELD, ' ').replace(RECORD, ' ')

    /** Line of a session, or null. */
    suspend fun lineFor(sessionId: Long): String? = decode(AppGraph.prefs.current().battleLog)[sessionId]

    /** Stores the line once; an existing line is never replaced. Returns the line that is stored. */
    suspend fun record(sessionId: Long, line: String): String {
        var stored = line
        AppGraph.prefs.update { p ->
            val map = LinkedHashMap(decode(p.battleLog))
            val existing = map[sessionId]
            if (existing != null) {
                stored = existing
                return@update p
            }
            map[sessionId] = clean(line)
            while (map.size > MAX_ENTRIES) {
                val first = map.keys.firstOrNull() ?: break
                map.remove(first)
            }
            p.copy(battleLog = encode(map))
        }
        return stored
    }
}
