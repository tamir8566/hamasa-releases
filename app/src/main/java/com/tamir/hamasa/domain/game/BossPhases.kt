package com.tamir.hamasa.domain.game

import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.domain.tree.TreeModel

/**
 * "קרבות בוס בשלבים" (spec v2: "שלושה שלבים: כוח, סיבולת, יציבות. מכריח לאזן את העץ").
 *
 * The weekly boss fight in rounds keeps its HP and rounds; what changes is which stat decides the damage:
 *  - above 2/3 of the HP: STRENGTH  -> the old rule (upper strength for upper-body sets, lower for legs, 1 for the rest)
 *  - 2/3 .. 1/3:          ENDURANCE -> every hit deals 1 + endurance; endurance-branch sets hit double
 *  - 1/3 and below:       STABILITY -> every hit deals 1 + stability; core sets hit double
 * Beatable by design: a hit never deals less than 1 and never less than half of its strength damage,
 * so a tree that is strong only on top still finishes the boss, just slower than a balanced one.
 * Weakness ("כל בוס נפגע משילוב אחר", BossWeakness): the phase decides each hit; the weakness grade of the session
 * then multiplies every hit (x2 full combo, x1/2 partial, x0 none for the weekly boss), via battle(multNum, multDen).
 * Pure logic. Nothing here touches the coach's weights or reps.
 */
enum class BossPhase(val nameHe: String) {
    STRENGTH("שלב הכוח"),
    ENDURANCE("שלב הסיבולת"),
    STABILITY("שלב היציבות")
}

object BossPhases {

    fun phaseOf(hp: Int, maxHp: Int): BossPhase {
        if (maxHp <= 0) return BossPhase.STRENGTH
        return when {
            hp * 3 > maxHp * 2 -> BossPhase.STRENGTH
            hp * 3 > maxHp -> BossPhase.ENDURANCE
            else -> BossPhase.STABILITY
        }
    }

    /** Damage of one hit of [branch] in [phase]. Always at least 1. */
    fun damagePerHit(phase: BossPhase, branch: Branch?, s: TreeModel.Stats): Int {
        val strength = StatEffects.damagePerHit(branch, s)
        val floor = maxOf(1, (strength + 1) / 2)
        return when (phase) {
            BossPhase.STRENGTH -> strength
            BossPhase.ENDURANCE -> {
                val base = 1 + s.endurance.coerceAtLeast(0)
                maxOf(floor, if (branch == Branch.ENDURANCE) base * 2 else base)
            }
            BossPhase.STABILITY -> {
                val base = 1 + s.stability.coerceAtLeast(0)
                maxOf(floor, if (branch == Branch.CORE) base * 2 else base)
            }
        }
    }

    /** One session against the boss, hit by hit, the phase following the HP as it drops. */
    data class Battle(
        val damage: Int,
        val hits: Int,
        val rounds: Int,
        val phaseBefore: BossPhase,
        val phaseAfter: BossPhase
    )

    /**
     * [setBranches] = branch of every completed set in the order they were done. A session without planned sets
     * (walk, run, yoga without logging) counts as a full fight of branchless hits, like the round model.
     */
    /**
     * [multNum]/[multDen] = the weakness multiplier of every hit (BossWeakness: 2/1 full combo, 1/2 half, 0/1 none).
     * Halves are carried between hits, so two hits of 1 deal 1. The phase of each hit follows the HP as it drops.
     */
    fun battle(setBranches: List<Branch?>, s: TreeModel.Stats, startHp: Int, maxHp: Int, sessionHasNoSets: Boolean,
               multNum: Int = 1, multDen: Int = 1): Battle {
        val rounds = StatEffects.rounds(s)
        val before = phaseOf(startHp, maxHp)
        val hitsList: List<Branch?> = if (sessionHasNoSets) List(rounds) { null } else setBranches
        val num = multNum.coerceAtLeast(0)
        val den = multDen.coerceAtLeast(1)
        var hp = startHp
        var damage = 0
        var hits = 0
        var carry = 0
        for (b in hitsList) {
            if (hits >= rounds || hp <= 0) break
            val scaled = damagePerHit(phaseOf(hp, maxHp), b, s) * num + carry
            val d = scaled / den
            carry = scaled % den
            damage += d
            hp -= d
            hits++
        }
        val dealt = damage.coerceAtMost(startHp.coerceAtLeast(0))
        return Battle(dealt, hits, rounds, before, phaseOf((startHp - dealt).coerceAtLeast(0), maxHp))
    }

    /** What decides the damage right now (coach language, shown under the boss bar). */
    fun ruleHe(phase: BossPhase, s: TreeModel.Stats): String = when (phase) {
        BossPhase.STRENGTH -> "שלב הכוח: עליון ${1 + s.upper.coerceAtLeast(0)} נזק, רגליים ${1 + s.lower.coerceAtLeast(0)} נזק."
        BossPhase.ENDURANCE -> "שלב הסיבולת: ${1 + s.endurance.coerceAtLeast(0)} נזק למכה. סט סיבולת מכה כפול."
        BossPhase.STABILITY -> "שלב היציבות: ${1 + s.stability.coerceAtLeast(0)} נזק למכה. סט בטן מכה כפול."
    }

    /** Shambiuluk's line when the boss enters [phase] (null for the first phase). */
    fun enterLineHe(phase: BossPhase): String? = when (phase) {
        BossPhase.STRENGTH -> null
        BossPhase.ENDURANCE -> "הבוס מתנשף. שלב הסיבולת. מעכשיו מנצח מי שמחזיק, לא מי שמרים."
        BossPhase.STABILITY -> "שלב אחרון. הבוס מתנדנד. רק יציבות גומרת אותו. הבטן מתבקשת לבמה."
    }
}

/**
 * "אויב חוזר" (spec v2: "בוס שנוצח חוזר חזק יותר עם משפט משלו").
 * When a boss that was already beaten comes back in the weekly rotation, it has +20% HP per earlier defeat
 * (at most +60%) and says its own comeback line. Defeats are kept in Prefs.bossDefeatsCsv as "id:count:week".
 * HP of a week that already started is never raised (only a fresh row gets the stronger HP).
 */
object BossReturn {
    const val STEP_PERCENT = 20
    const val MAX_PERCENT = 60

    data class Record(val count: Int, val lastWeek: Long)

    fun parse(csv: String): Map<String, Record> {
        val out = LinkedHashMap<String, Record>()
        for (piece in csv.split(',')) {
            val p = piece.split(':')
            if (p.size != 3 || p[0].isBlank()) continue
            val c = p[1].trim().toIntOrNull() ?: continue
            val w = p[2].trim().toLongOrNull() ?: continue
            out[p[0].trim()] = Record(c, w)
        }
        return out
    }

    fun encode(map: Map<String, Record>): String = map.entries.joinToString(",") { "${it.key}:${it.value.count}:${it.value.lastWeek}" }

    /** Records one defeat of [bossId] in [week] (once per week). */
    fun recordDefeat(csv: String, bossId: String, week: Long): String {
        val m = LinkedHashMap(parse(csv))
        val cur = m[bossId]
        if (cur != null && cur.lastWeek == week) return csv
        m[bossId] = Record((cur?.count ?: 0) + 1, week)
        return encode(m)
    }

    /** Defeats of [bossId] in weeks before [week]. */
    fun timesBeatenBefore(csv: String, bossId: String, week: Long): Int {
        val r = parse(csv)[bossId] ?: return 0
        return (if (r.lastWeek >= week) r.count - 1 else r.count).coerceAtLeast(0)
    }

    fun extraPercent(times: Int): Int = (times.coerceAtLeast(0) * STEP_PERCENT).coerceAtMost(MAX_PERCENT)

    fun maxHpFor(boss: Boss, times: Int): Int {
        val base = StatEffects.scaledMaxHp(boss)
        return base + (base * extraPercent(times) + 99) / 100
    }

    /** The boss's own comeback line, in the player's grammar (Addr). */
    fun lineHe(bossId: String): String = Addr.x(rawLine(bossId))

    private fun rawLine(bossId: String): String = when (bossId) {
        "monster" -> "חזרתי. ישנת טוב בלעדיי? לא לאורך זמן."
        "midweek_king" -> "הכתר הודבק. יום שלישי שוב שלי."
        "couch_siren" -> "החלפתי ריפוד. רך יותר. רק {u:תבדוק|תבדקי}."
        "thin_legs_giant" -> "גם לי יש עכשיו ירכיים. בערך."
        "sly_merchant" -> "סחורה חדשה. אותה הבטחה. מחיר כפול."
        "snooze_dragon" -> "למדתי לנמנם בעיניים פקוחות. {u:גם אתה תלמד|גם את תלמדי}."
        "excuse_goblin" -> "אספתי תירוצים חדשים. יש לי מלאי לחורף."
        "sugar_witch" -> "מתכון חדש. בלי סוכר. משקרת, כמובן."
        "mirror_knight" -> "ליטשתי את המראה. עכשיו רואים הכול. גם את מה שאין."
        "rain_ogre" -> "הבאתי עננים מהעונה הבאה. מראש."
        "weight_golem" -> "הוספתי אבנים. גם {u:אתה|את} הוספת? נראה."
        "plateau_sphinx" -> "חידה חדשה. אותה תשובה. {u:אתה|את}."
        "phone_siren" -> "עדכון גרסה. הודעות חדשות. רק {u:תציץ|תציצי}."
        "stairs_guardian" -> "הוספתי קומה. לא שאלתי אותך."
        "old_self" -> "אני עדיין זוכר את הכתובת. באתי לבקר."
        else -> "חזרתי. הפעם התכוננתי."
    }
}
