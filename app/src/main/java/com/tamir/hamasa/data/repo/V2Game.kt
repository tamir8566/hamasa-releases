package com.tamir.hamasa.data.repo

import com.tamir.hamasa.data.prefs.Focus
import kotlin.math.abs
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.coach.sleepBand
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.BadgeCatalog
import com.tamir.hamasa.domain.game.BossReturn
import com.tamir.hamasa.domain.game.CharacterNames
import com.tamir.hamasa.domain.game.LevelGates
import com.tamir.hamasa.domain.game.LevelTable
import com.tamir.hamasa.domain.game.SkillId
import com.tamir.hamasa.domain.game.Skills
import com.tamir.hamasa.domain.game.StoryChapters
import kotlinx.coroutines.withTimeoutOrNull

private const val FIELD = '\u001F'
private const val RECORD = '\u001E'

private fun cleanField(s: String): String = s.replace(FIELD, ' ').replace(RECORD, ' ')

// ====================================================================== "אימון מסופר"

/** A chapter of the story told by one workout. [lines] are revealed one by one as sets are completed. */
data class StoryChapter(val sessionId: Long, val title: String, val lines: List<String>)

/** Chapters kept in Prefs.storyLog ("ספר הקרבות" shows them in the session detail). */
object StoryLog {
    private const val MAX_ENTRIES = 60

    fun decode(raw: String): LinkedHashMap<Long, StoryChapter> {
        val out = LinkedHashMap<Long, StoryChapter>()
        if (raw.isEmpty()) return out
        for (rec in raw.split(RECORD)) {
            val parts = rec.split(FIELD)
            if (parts.size != 3) continue
            val id = parts[0].toLongOrNull() ?: continue
            val lines = parts[2].split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            out[id] = StoryChapter(id, parts[1], lines)
        }
        return out
    }

    private fun encode(map: Map<Long, StoryChapter>): String =
        map.values.joinToString(RECORD.toString()) { c ->
            "${c.sessionId}$FIELD${cleanField(c.title).replace('\n', ' ')}$FIELD" +
                c.lines.joinToString("\n") { cleanField(it).replace('\n', ' ') }
        }

    suspend fun chapterFor(sessionId: Long): StoryChapter? = decode(AppGraph.prefs.current().storyLog)[sessionId]

    /** Chapters of other sessions already told in the region [regionName] (titles end with ": <region>"). */
    fun countInRegion(map: Map<Long, StoryChapter>, regionName: String, exceptSessionId: Long): Int {
        val suffix = ": $regionName"
        return map.values.count { it.sessionId != exceptSessionId && it.title.endsWith(suffix) }
    }

    /** The chapter told before [sessionId] (the latest stored chapter of another session), or null. */
    fun previousOf(map: Map<Long, StoryChapter>, sessionId: Long): StoryChapter? =
        map.values.lastOrNull { it.sessionId != sessionId }

    /** Stores [c]. An existing chapter is kept unless [replace]. Returns the chapter that is stored. */
    suspend fun save(c: StoryChapter, replace: Boolean): StoryChapter {
        var stored = c
        AppGraph.prefs.update { p ->
            val map = decode(p.storyLog)
            val existing = map[c.sessionId]
            if (existing != null && !replace) {
                stored = existing
                return@update p
            }
            map[c.sessionId] = c
            while (map.size > MAX_ENTRIES) {
                val first = map.keys.firstOrNull() ?: break
                map.remove(first)
            }
            p.copy(storyLog = encode(map))
        }
        return stored
    }
}

object StoryTeller {
    /** The weekly boss for the chapter: name, quote and the comeback line of a returning boss. Blank before LevelGates.BOSS (level 6). */
    private suspend fun bossBits(s: SessionEntity): Triple<String, String, String?> {
        val level = LevelTable.levelFor(AppGraph.prefs.current().xp)
        if (!LevelGates.bossOpen(level)) return Triple("", "", null)
        return runCatching {
            val (boss, progress) = BossFights.forDay(s.dateEpochDay)
            val times = BossFights.returningTimes(boss, progress.weekEpoch)
            Triple(Addr.x(boss.nameHe), Addr.x(boss.quoteHe), if (times > 0) BossReturn.lineHe(boss.id) else null)
        }.getOrDefault(Triple("", "", null))
    }

    /**
     * The chapter of [sessionId]: the stored one, or a new offline chapter (saved at once). [region]/[regionName]
     * come from the map (ui layer). Never null for an existing session.
     */
    suspend fun ensure(sessionId: Long, region: Int, regionName: String): StoryChapter? {
        StoryLog.chapterFor(sessionId)?.let { return it }
        val s = AppGraph.db.sessions().byId(sessionId) ?: return null
        val (bossName, bossQuote, comeback) = bossBits(s)
        val chapterNo = AppGraph.db.sessions().doneSessionsOnce().size + 1
        // the region's chapters are told in order: the next one after those already in the log
        val step = StoryLog.countInRegion(StoryLog.decode(AppGraph.prefs.current().storyLog), regionName, sessionId)
        val lines = StoryChapters.fallback(sessionId, region, bossName, bossQuote, comeback, step)
        return StoryLog.save(StoryChapter(sessionId, StoryChapters.title(chapterNo, regionName), lines), replace = false)
    }

    /**
     * Optional AI chapter, asked once at the start of a session. Replaces the offline chapter only while no set
     * is completed yet (nothing was revealed beyond the first line). Any failure keeps the offline chapter.
     */
    suspend fun tryAi(sessionId: Long, regionName: String) {
        if (!AiClient.enabled) return
        val current = StoryLog.chapterFor(sessionId) ?: return
        val s = AppGraph.db.sessions().byId(sessionId) ?: return
        val (bossName, bossQuote, comeback) = bossBits(s)
        val previous = StoryLog.previousOf(StoryLog.decode(AppGraph.prefs.current().storyLog), sessionId)
        val lines = withTimeoutOrNull(20_000L) { askAi(regionName, bossName, bossQuote, comeback, s.type, previous) } ?: return
        val sets = AppGraph.db.sessions().setsForOnce(sessionId)
        if (sets.any { it.completed }) return
        StoryLog.save(current.copy(lines = lines), replace = true)
    }

    private suspend fun askAi(
        regionName: String,
        bossName: String,
        bossQuote: String,
        comeback: String?,
        type: SessionType,
        previous: StoryChapter?
    ): List<String>? {
        val female = CharacterNames.isFemaleNow()
        val hero = CharacterNames.heNow()
        val system = "אתה כותב פרק קצר בסיפור הרפתקאות מתמשך על $hero, גיבור של משחק כושר. " +
            "כתוב בעברית פשוטה ויומיומית, כמו ספר הרפתקאות לילדים בני 12. הקורא צריך להבין כל משפט כבר בקריאה הראשונה. " +
            "כל פרק הוא אירוע אחד קטן וברור, עם התחלה, אמצע וסוף: איפה הגיבור, מה הבעיה, מה הוא עשה, ואיך זה נגמר. " +
            "פעולות מוחשיות: מי עשה מה, איפה ולמה. כל משפט ממשיך את המשפט שלפניו. " +
            "אסור: חידות, אמרות חכמה, משפטים פילוסופיים, מטפורות מופשטות, משפטים שלא קשורים זה לזה, מילים גבוהות או נדירות. " +
            "הומור קל וקל להבנה: הגיבור יבש ומודע לעצמו, ובסוף הפרק מותרת בדיחה קטנה וברורה. " +
            "עברית בלבד, בלי מילים באנגלית ובלי סמלים רגשיים. אם מזכירים עייפות, היא נקראת המפלצת. " +
            "אל תשתמש במילים: " + StoryChapters.bannedWords.joinToString(", ") + ". " +
            "החזר JSON בלבד בצורה {\"lines\": [שש מחרוזות]}. כל מחרוזת: משפט אחד או שניים קצרים, עד 120 תווים. " +
            "שש המחרוזות יחד מספרות סיפור רציף אחד." +
            (if (female) " הגיבורה היא שמביולוקה: כתוב עליה בלשון נקבה (היא הלכה, היא הרימה), ואל תכתוב את השם בלשון זכר." else "") +
            (if (Addr.isFemale()) " השחקנית היא אישה: אם יש פנייה אליה, בלשון נקבה." else "")
        val bossPart = when {
            bossName.isBlank() -> "השבוע אין בוס. במקום זה, בשורות 3-4 הבעיה נעשית קצת יותר קשה."
            comeback != null -> "בוס השבוע: $bossName. היא דמות שכבר הפסידה פעם וחזרה חזקה יותר. " +
                "בשורה 3 הדמות מופיעה באירוע, בשורה 4 היא אומרת את המשפט שלה: \"$comeback\", והגיבור עונה במעשים ולא במילים."
            else -> "בוס השבוע: $bossName. בשורה 3 הדמות מופיעה באירוע ומפריעה, בשורה 4 היא אומרת את המשפט שלה: " +
                "\"$bossQuote\", והגיבור עונה במעשים ולא במילים."
        }
        val kind = when (type) {
            SessionType.GYM -> "אימון ברזל"
            SessionType.YOGA -> "יוגה"
            SessionType.WALK -> "הליכה"
            SessionType.RUN -> "ריצה"
            SessionType.MINI -> "אימון קצר"
        }
        val prevPart = if (previous == null) {
            "זה הפרק הראשון: הגיבור רק יוצא לדרך."
        } else {
            "הפרק הקודם (${previous.title}): " + previous.lines.joinToString(" ") + " " +
                "המשך ממה שקרה שם: השורה הראשונה מזכירה בקצרה את הפרק הקודם. אם האזור השתנה, ספר שהגיבור הגיע לאזור החדש."
        }
        val example = StoryChapters.styleExample(female).joinToString(" / ")
        val prompt = "האזור במפה: $regionName. סוג האימון היום: $kind. $prevPart $bossPart " +
            "כתוב את הפרק הבא בשש שורות, לפי הסדר: 1. איפה הגיבור ומה הוא מנסה לעשות. 2. הבעיה. " +
            "3-4. הבוס או הקושי. 5. מה הגיבור עושה כדי להצליח, במאמץ של הגוף. 6. איך זה נגמר, עם חיוך קטן. " +
            "כל סט באימון חושף שורה אחת, אז כל שורה מקדמת את האירוע. " +
            "דוגמה לסגנון בלבד, אל תעתיק אותה: $example"
        val parts = listOf(AiClient.Part.Text(prompt))
        val obj = AiClient.generateJson(parts, system, tier = AiClient.Tier.LITE)
            ?: AiClient.generateJson(parts, system, tier = AiClient.Tier.MAIN)
            ?: return null
        val arr = obj.optJSONArray("lines") ?: return null
        val lines = (0 until arr.length()).map { arr.optString(it).trim() }
        return if (StoryChapters.acceptable(lines)) lines.map { CharacterNames.g(it) } else null
    }
}

// ====================================================================== "צל של אתמול"

/** One set of the ghost: what he did last time. */
data class GhostSet(val reps: Int, val weight: Float)

object ShadowRun {
    /** Comparable score of a set: volume for weighted sets, reps (or seconds) otherwise. */
    private fun score(reps: Int, weight: Float): Float = if (weight > 0f) weight * reps else reps.toFloat()

    /** [outcome] when the sets cannot be compared (different weights and a goal that does not say how). */
    const val NOT_COMPARABLE = 2

    /**
     * 1 = beat the ghost, 0 = tie, -1 = the ghost was ahead, [NOT_COMPARABLE] = no fair comparison.
     * By his goal (Tamir): STRENGTH = the higher weight wins (same weight: more reps); BUILD_MUSCLE = reps x weight;
     * any other goal: same weight compares reps, different weights are not compared.
     */
    fun outcome(reps: Int, weight: Float, ghost: GhostSet, focus: Focus = Focus.BUILD_MUSCLE): Int {
        val bothWeighted = weight > 0f && ghost.weight > 0f
        val sameWeight = abs(weight - ghost.weight) < 0.01f
        val a: Float
        val b: Float
        when {
            !bothWeighted || sameWeight -> { a = reps.toFloat(); b = ghost.reps.toFloat() }
            focus == Focus.STRENGTH -> { a = weight; b = ghost.weight }
            focus == Focus.BUILD_MUSCLE -> { a = score(reps, weight); b = score(ghost.reps, ghost.weight) }
            else -> return NOT_COMPARABLE
        }
        return when {
            a > b -> 1
            a < b -> -1
            else -> 0
        }
    }

    /** The previous finished session of the same type in the same mode ("באותו מצב"), not [session] itself. */
    private suspend fun sameModeSession(session: SessionEntity): SessionEntity? =
        AppGraph.db.sessions().doneSessionsOnce().firstOrNull {
            it.id != session.id && it.type == session.type && it.modeId == session.modeId
        }

    /** Completed sets of the last finished session (not [excludeSessionId]) that had [exerciseId]. */
    private suspend fun lastDoneSetsBefore(exerciseId: String, excludeSessionId: Long): List<SetLogEntity> {
        val recent = AppGraph.db.sessions().recentSetsFor(exerciseId, 80)
        val ids = LinkedHashSet<Long>()
        for (r in recent) if (r.sessionId != excludeSessionId) ids.add(r.sessionId)
        for (sid in ids) {
            val s = AppGraph.db.sessions().byId(sid) ?: continue
            if (s.status != SessionStatus.DONE) continue
            return recent.filter { it.sessionId == sid }.sortedBy { it.setIndex }
        }
        return emptyList()
    }

    /**
     * The ghost of every exercise in [exerciseIds]: its sets in the last session of the same mode, or (when that
     * session did not have it) the last time the exercise was done. Exercises never done before have no ghost.
     */
    suspend fun ghostsFor(session: SessionEntity, exerciseIds: Collection<String>): Map<String, List<GhostSet>> {
        val same = sameModeSession(session)
        val sameSets = same?.let { AppGraph.db.sessions().setsForOnce(it.id).filter { st -> st.completed } } ?: emptyList()
        val out = HashMap<String, List<GhostSet>>()
        for (id in exerciseIds) {
            var rows = sameSets.filter { it.exerciseId == id }.sortedBy { it.setIndex }
            if (rows.isEmpty()) rows = lastDoneSetsBefore(id, session.id)
            if (rows.isNotEmpty()) out[id] = rows.map { GhostSet(it.reps, it.weight) }
        }
        return out
    }

    /** (sets that beat the ghost, sets that had a ghost) of a session; null when nothing could be compared. */
    suspend fun summary(sessionId: Long): Pair<Int, Int>? {
        val s = AppGraph.db.sessions().byId(sessionId) ?: return null
        val sets = AppGraph.db.sessions().setsForOnce(sessionId)
        val groups = sets.groupBy { it.exerciseId }
        val ghosts = ghostsFor(s, groups.keys)
        val focus = AppGraph.prefs.current().focus
        var wins = 0
        var compared = 0
        for ((id, rows) in groups) {
            val g = ghosts[id] ?: continue
            rows.sortedBy { it.setIndex }.forEachIndexed { i, st ->
                if (!st.completed || i >= g.size) return@forEachIndexed
                val o = outcome(st.reps, st.weight, g[i], focus)
                if (o == NOT_COMPARABLE) return@forEachIndexed
                compared++
                if (o > 0) wins++
            }
        }
        return if (compared > 0) Pair(wins, compared) else null
    }

    fun summaryLineHe(wins: Int, compared: Int): String {
        val head = "ניצחת את הצל ב-$wins מתוך $compared סטים."
        val tail = when {
            wins == compared -> "הצל ביקש הפסקה. לא קיבל."
            wins * 2 >= compared -> "הצל מאחור. הוא יחזור בפעם הבאה. עם הסטים של היום."
            wins == 0 -> Addr.t("הצל ניצח היום. מחר הוא יהיה אתה של היום. נראה אותו.", "הצל ניצח היום. מחר הוא יהיה את של היום. נראה אותו.")
            else -> "הצל עוד מוביל. בקושי. הוא מתחיל לדאוג."
        }
        return "$head $tail"
    }
}

// ====================================================================== "כישורים מיוחדים"

object SkillBook {
    /** Open once the tree is open (level gate) and its branches add up to Skills.UNLOCK_TOTAL_LEVELS. */
    suspend fun unlocked(): Boolean {
        val level = LevelTable.levelFor(AppGraph.prefs.current().xp)
        if (!LevelGates.treeOpen(level)) return false
        val total = runCatching { AppGraph.game.branchLevels().values.sum() }.getOrDefault(0)
        return total >= Skills.UNLOCK_TOTAL_LEVELS
    }

    suspend fun chosen(): SkillId? = Skills.byName(AppGraph.prefs.current().skillId)

    /** Picks [id]. Changing an existing choice waits Skills.SWITCH_DAYS days. Returns Shambiuluk's line, or null. */
    suspend fun choose(id: SkillId, today: Long = Days.today()): String? {
        if (!unlocked()) return null
        var ok = false
        AppGraph.prefs.update { p ->
            val cur = Skills.byName(p.skillId)
            if (cur == id) return@update p
            if (cur != null && Skills.daysUntilSwitch(p.skillChosenDay, today) > 0) return@update p
            ok = true
            p.copy(skillId = id.name, skillChosenDay = today)
        }
        return if (ok) Skills.chosenLineHe(id) else null
    }

    /** "השתמש" on the active skill. Returns the line to show. Nothing is spent when it cannot work. */
    suspend fun use(today: Long = Days.today()): String {
        val p = AppGraph.prefs.current()
        val id = Skills.byName(p.skillId) ?: return "קודם בוחרים כישור."
        if (!id.active) return "ריכוז עובד לבד. בכל שיא."
        val wait = Skills.daysUntilReady(id, p.skillUsedDay, today)
        if (wait > 0) return "עוד $wait ימים. הכישור נח. גם לו מגיע."
        return when (id) {
            SkillId.PATIENCE -> {
                val max = AppGraph.game.maxShields()
                var added = false
                AppGraph.prefs.update { cur ->
                    if (cur.shields >= max) cur
                    else { added = true; cur.copy(shields = cur.shields + 1, skillUsedDay = today) }
                }
                if (added) Skills.shieldLineHe() else Skills.bagFullLineHe()
            }
            SkillId.CURIOSITY -> {
                val have = AppGraph.db.game().badgesOnce().map { it.id }.toSet()
                val expired = runCatching { AppGraph.game.expiredSeasonalIds() }.getOrDefault(emptySet())
                val month = Days.of(today).monthValue
                val cands = BadgeCatalog.all.filter {
                    it.hidden && it.id !in have && it.id !in expired && it.id != p.skillHintBadgeId &&
                        (it.seasonMonth == null || it.seasonMonth == month)
                }
                if (cands.isEmpty()) return Skills.noHintLineHe()
                val pick = cands[((today % cands.size) + cands.size).toInt() % cands.size]
                AppGraph.prefs.update { it.copy(skillHintBadgeId = pick.id, skillUsedDay = today) }
                "רמז לתג נסתר: " + CharacterNames.g(pick.hintHe)
            }
            SkillId.FOCUS -> "ריכוז עובד לבד. בכל שיא."
        }
    }

    /** The hint "סקרנות" revealed last, or null. */
    suspend fun currentHintHe(): String? {
        val id = AppGraph.prefs.current().skillHintBadgeId
        if (id.isEmpty()) return null
        if (AppGraph.db.game().badgesOnce().any { it.id == id }) return null   // already earned
        return BadgeCatalog.byId(id)?.hintHe?.let { CharacterNames.g(it) }
    }
}

// ====================================================================== after a finished session

/** What the v2 ideas added to a finished session (the done screen reads it once). */
data class V2Finish(val sessionId: Long, val focusXp: Int)

object V2Results {
    @Volatile var last: V2Finish? = null
}

object V2Game {
    /**
     * Called once by WorkoutRepository.finishSession after the session row is DONE. "ריכוז": the record points
     * (25 per paid record, as in finishSession; none after a medium night) are paid a second time.
     */
    suspend fun afterFinish(sessionId: Long): V2Finish {
        var focusXp = 0
        runCatching {
            val p = AppGraph.prefs.current()
            if (Skills.byName(p.skillId) == SkillId.FOCUS && SkillBook.unlocked()) {
                val s = AppGraph.db.sessions().byId(sessionId)
                if (s != null) {
                    val sets = AppGraph.db.sessions().setsForOnce(sessionId)
                    val prs = sets.filter { it.isPr }.map { it.exerciseId }.distinct().size
                    val paid = prs.coerceAtMost(WorkoutRepository.PR_REWARD_CAP)
                    val medium = s.type == SessionType.GYM && sleepBand(s.sleepHours) == SleepBand.MEDIUM
                    if (paid > 0 && !medium) {
                        focusXp = 25 * paid
                        AppGraph.game.grant(focusXp, 0, "skill:focus:$sessionId")
                        AppGraph.db.sessions().update(s.copy(xpEarned = s.xpEarned + focusXp))
                    }
                }
            }
        }
        val out = V2Finish(sessionId, focusXp)
        V2Results.last = out
        return out
    }
}

// ====================================================================== "פינוק כפרס רשמי"

/**
 * Spec v2: "ארוחת פינוק שנקנתה בזהב נרשמת כפרס, לא ככישלון" (and the shop: "ארוחת פינוק (נרשמת ביומן הקלוריות)").
 * Buying it spends gold like any shop reward (ledger "reward:treat:<name>", so it shows in "יומן פרסים") and logs
 * the meal in today's food log with the [PREFIX], so the food screen can show it as a reward.
 */
object OfficialTreat {
    const val PREFIX = "פינוק רשמי · "
    /** Spec price guide: "סטייק או ארוחה טובה 150 זהב". */
    const val DEFAULT_PRICE = 150

    fun isTreat(description: String): Boolean = description.startsWith(PREFIX)

    fun boughtLineHe(): String = Addr.t("פינוק רשמי. נרשם ביומן האוכל כפרס, לא ככישלון. תאכל בנחת. הרווחת.", "פינוק רשמי. נרשם ביומן האוכל כפרס, לא ככישלון. תאכלי בנחת. הרווחת.")

    /** Returns false (nothing changes) when there is not enough gold or the name is blank. */
    suspend fun buy(name: String, kcal: Int, price: Int): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val full = PREFIX + n
        val ok = AppGraph.game.spendGold(price.coerceAtLeast(1), "reward:treat:$full")
        if (!ok) return false
        AppGraph.db.meals().insert(
            MealEntity(dateEpochDay = Days.today(), atMillis = System.currentTimeMillis(), description = full, kcal = kcal.coerceAtLeast(0))
        )
        runCatching { CloudSync.pushSoon(AppGraph.appContext) }
        com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext)
        return true
    }
}
