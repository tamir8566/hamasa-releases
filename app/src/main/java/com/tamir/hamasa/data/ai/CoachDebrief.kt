package com.tamir.hamasa.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.repo.WorkoutRepository
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.Deload
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.coach.sleepBand
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.domain.game.Addr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * "הג'ים ברו אומר": the coach's short talk after a gym session (done screen + session detail).
 *
 * The FACTS are computed by the app: what improved against the previous time of each exercise (more weight, more reps
 * at the same weight, records), what stayed or dropped, how many sets were completed, the plan for next time from the
 * engine's own progression rule (CoachEngine.nextTarget, with the history that already includes this session), and
 * one tip picked by rule. The AI (AiClient tier LITE, then MAIN, 8 s, off the main thread) may only phrase the summary;
 * offline / slow / bad answer -> a template from the same facts. The "בפעם הבאה" lines are always the computed ones.
 * Cached per session (memory + local SharedPreferences "coach_debrief", last 60 sessions).
 */
object CoachDebrief {

    data class Debrief(
        /** Two to four serious sentences (Gemini or template). */
        val text: String,
        /** "לחיצת חזה 62.5 × 6" lines for the next session, computed (never from the model). */
        val nextLines: List<String>,
        val fromAi: Boolean
    )

    private val cache = ConcurrentHashMap<Long, Debrief>()
    private const val AI_TIMEOUT_MS = 8_000L
    private const val PREFS = "coach_debrief"
    private const val K_IDS = "ids"
    private const val KEEP = 60
    private const val MAX_NEXT_LINES = 5

    /** Null when the session is not a finished gym session with completed sets. */
    suspend fun forSession(sessionId: Long): Debrief? = withContext(Dispatchers.IO) {
        cache[sessionId]?.let { return@withContext it }
        val stored = load(sessionId)
        if (stored != null) {
            cache[sessionId] = stored
            return@withContext stored
        }
        val f = runCatching { facts(sessionId) }.getOrNull() ?: return@withContext null
        val text = phrase(f)
        val d = Debrief(text.first, f.nextLines, text.second)
        cache[sessionId] = d
        save(sessionId, d)
        d
    }

    // ------------------------------------------------------------------ facts

    class Facts(
        val lines: List<String>,
        val template: String,
        val nextLines: List<String>
    )

    private fun kg(v: Float): String =
        if (v == v.roundToInt().toFloat()) "${v.roundToInt()}" else String.format("%.1f", v)

    private fun e1rm(s: SetLogEntity): Float = s.weight * (1f + s.reps / 30f)

    /** Completed sets of [exerciseId] in the latest DONE session before [sessionId] (empty when none). */
    private suspend fun previousSets(exerciseId: String, sessionId: Long): List<SetLogEntity> {
        val db = AppGraph.db
        val earlier = db.sessions().recentSetsFor(exerciseId, 60).filter { it.sessionId < sessionId }
        for (sid in earlier.map { it.sessionId }.distinct()) {
            val s = db.sessions().byId(sid) ?: continue
            if (s.status != SessionStatus.DONE) continue
            return earlier.filter { it.sessionId == sid }.sortedBy { it.setIndex }
        }
        return emptyList()
    }

    /** No later finished session has this exercise (so "next time" really is next). */
    private suspend fun isLatestFor(exerciseId: String, sessionId: Long): Boolean {
        val db = AppGraph.db
        val later = db.sessions().recentSetsFor(exerciseId, 60).map { it.sessionId }.distinct().filter { it > sessionId }
        for (sid in later) if (db.sessions().byId(sid)?.status == SessionStatus.DONE) return false
        return true
    }

    private suspend fun facts(sessionId: Long): Facts? {
        val db = AppGraph.db
        val s = db.sessions().byId(sessionId) ?: return null
        if (s.status != SessionStatus.DONE || s.type != SessionType.GYM) return null
        val sets = db.sessions().setsForOnce(sessionId)
        val done = sets.filter { it.completed }
        if (done.isEmpty()) return null
        val p = AppGraph.prefs.current()
        val userPlan = s.notes.startsWith("template:")
        val deloadThis = Deload.activeOn(p.deloadWeekStart, s.dateEpochDay)

        val order = done.map { it.exerciseId }.distinct()
            .filter { it != WorkoutRepository.WALK_EXERCISE_ID && !ExerciseCatalog.isMobility(it) }
        val entities = HashMap<String, ExerciseEntity>()
        for (id in order) {
            val e = db.exercises().byId(id) ?: ExerciseCatalog.byId(id) ?: continue
            entities[id] = e
        }

        val improved = ArrayList<String>()
        val stalled = ArrayList<String>()
        val firsts = ArrayList<String>()
        for (id in order) {
            val e = entities[id] ?: continue
            val mine = done.filter { it.exerciseId == id }.sortedBy { it.setIndex }
            val prev = previousSets(id, sessionId)
            val name = e.nameHe
            when (e.unit) {
                "kg" -> {
                    val top = mine.maxOf { it.weight }
                    if (top <= 0f) continue
                    if (prev.isEmpty() || prev.maxOf { it.weight } <= 0f) {
                        firsts.add("$name ${kg(top)} ק\"ג, חזרות: ${mine.joinToString(", ") { it.reps.toString() }}")
                        continue
                    }
                    val prevTop = prev.maxOf { it.weight }
                    val repsNow = mine.filter { it.weight == top }.sumOf { it.reps }
                    val repsPrev = prev.filter { it.weight == top }.sumOf { it.reps }
                    when {
                        top > prevTop -> improved.add("$name: מ-${kg(prevTop)} ל-${kg(top)} ק\"ג")
                        top == prevTop && repsNow > repsPrev -> improved.add("$name: אותו משקל (${kg(top)} ק\"ג), ${repsNow - repsPrev} חזרות יותר בסך הכול")
                        top < prevTop -> {
                            // a planned lighter week is not a drop
                            if (!deloadThis) {
                                val betterE1rm = mine.maxOf { e1rm(it) } > prev.maxOf { e1rm(it) }
                                if (betterE1rm) improved.add("$name: פחות משקל אבל יותר חזרות, הסט הטוב חזק יותר מהפעם הקודמת")
                                else stalled.add("$name: ירידה מ-${kg(prevTop)} ל-${kg(top)} ק\"ג")
                            }
                        }
                        repsNow < repsPrev -> stalled.add("$name: אותו משקל, ${repsPrev - repsNow} חזרות פחות מהפעם הקודמת")
                        else -> stalled.add("$name: אותו משקל ואותן חזרות כמו בפעם הקודמת")
                    }
                }
                else -> {
                    val unit = if (e.unit == "sec") "שניות" else "חזרות"
                    val best = mine.maxOf { it.reps }
                    if (prev.isEmpty()) {
                        firsts.add("$name: $best $unit")
                        continue
                    }
                    val prevBest = prev.maxOf { it.reps }
                    when {
                        best > prevBest -> improved.add("$name: מ-$prevBest ל-$best $unit")
                        best < prevBest && !deloadThis -> stalled.add("$name: $best $unit, בפעם הקודמת $prevBest")
                        best == prevBest -> stalled.add("$name: $best $unit, כמו בפעם הקודמת")
                    }
                }
            }
        }

        val records = done.filter { it.isPr }.groupBy { it.exerciseId }.mapNotNull { (id, list) ->
            val e = entities[id] ?: return@mapNotNull null
            "${e.nameHe} ${kg(list.maxOf { it.weight })} ק\"ג"
        }

        // ---- next time: the engine's own rule (coach plans) or the template suggestion rule (his own plans)
        val next = ArrayList<String>()
        // a home session: "בפעם הבאה" respects the weights he has at home
        val isHome = s.notes == WorkoutRepository.HOME_NOTES
        val ctx = runCatching { if (isHome) AppGraph.workouts.homeContext(null) else AppGraph.workouts.context(null) }.getOrNull()
        val ordered = order.sortedBy { if (entities[it]?.isCompound == true) 0 else 1 }
        for (id in ordered) {
            if (next.size >= MAX_NEXT_LINES) break
            val e = entities[id] ?: continue
            if (!isLatestFor(id, sessionId)) continue
            val mine = done.filter { it.exerciseId == id }
            if (userPlan) {
                if (e.unit != "kg") continue
                val top = mine.maxOf { it.weight }
                if (top <= 0f) continue
                val topReps = mine.filter { it.weight == top }.maxOf { it.reps }
                val ctxDeload = ctx?.deload == true
                next.add(
                    if (topReps >= 12 && !ctxDeload) {
                        val step = if (e.equipment == "dumbbell") 2f else 5f
                        "${e.nameHe} ${kg(top + step)} × 8 (הגעת ל-12 חזרות)"
                    } else "${e.nameHe} ${kg(top)} ק\"ג, לנסות חזרה אחת יותר"
                )
            } else {
                val c = ctx ?: continue
                val t = CoachEngine.nextTarget(id, c) ?: continue
                next.add(
                    when (e.unit) {
                        "kg" -> "${e.nameHe} ${kg(t.first)} × ${t.second}"
                        "sec" -> "${e.nameHe} ${t.second} שניות"
                        else -> "${e.nameHe} ${t.second} חזרות"
                    }
                )
            }
        }

        // ---- one tip, by rule
        val completed = done.size
        val planned = sets.size
        val band = sleepBand(s.sleepHours)
        val tip = when {
            p.restBrokenSessionId == sessionId -> "בתרגילים המורכבים מנוחה מלאה של שתיים וחצי עד שלוש דקות. מנוחה קצרה עולה בחזרות בסט הבא."
            band == SleepBand.SHORT || band == SleepBand.MEDIUM -> "שבע שעות שינה ומעלה הן חלק מהאימון. הכוח נבנה בלילה."
            planned > 0 && completed * 10 < planned * 8 -> "קודם משלימים את כל הסטים, ורק אחר כך מוסיפים משקל."
            deloadThis -> "שבוע הורדת עומס: לא רודפים אחרי משקל. תנועה נקייה ונשימה רגועה."
            stalled.size >= 2 -> "כשכמה תרגילים נתקעים יחד, בודקים קודם שינה ואוכל, לא את התוכנית."
            else -> {
                val bw = runCatching { db.weights().latestOnce()?.kg }.getOrNull() ?: p.startWeightKg
                val protein = runCatching {
                    Nutrition.targets(p.kcalGoal, bw, p.proteinGoal, p.carbsGoal, p.fatGoal, p.heightCm, p.bodyFatPct, com.tamir.hamasa.data.repo.EnergyData.directionOf(p, bw)).proteinG
                }.getOrDefault(0)
                if (protein > 0) "חלבון: כ-$protein גרם היום עוזרים לשריר להתאושש מהאימון."
                else "ארוחה עם חלבון בשעתיים הקרובות עוזרת לשריר להתאושש."
            }
        }

        // ---- facts for the model
        val lines = ArrayList<String>()
        lines.add(if (userPlan) Addr.t("אימון שהמתאמן בנה בעצמו", "אימון שהמתאמנת בנתה בעצמה") else "אימון של הג'ים ברו")
        if (deloadThis) lines.add("שבוע הורדת עומס: משקלים נמוכים בכוונה, פחות סטים")
        lines.add("סטים שהושלמו: $completed מתוך $planned")
        if (records.isNotEmpty()) lines.add("שיאים חדשים: " + records.joinToString("; "))
        if (improved.isNotEmpty()) lines.add("שיפור לעומת הפעם הקודמת: " + improved.joinToString("; "))
        if (stalled.isNotEmpty()) lines.add("נשאר במקום או ירד: " + stalled.joinToString("; "))
        if (firsts.isNotEmpty()) lines.add("פעם ראשונה (משקל בסיס): " + firsts.joinToString("; "))
        if (next.isNotEmpty()) lines.add("בפעם הבאה: " + next.joinToString("; "))
        lines.add("טיפ: $tip")

        // ---- offline template
        val t = ArrayList<String>()
        if (records.isNotEmpty()) t.add("שיא חדש: ${records.joinToString(", ")}.")
        if (improved.isNotEmpty()) t.add("התקדמות לעומת הפעם הקודמת: ${improved.joinToString("; ")}.")
        if (stalled.isNotEmpty()) t.add("נשאר במקום: ${stalled.joinToString("; ")}.")
        if (firsts.isNotEmpty()) t.add("פעם ראשונה: ${firsts.joinToString("; ")}. זה משקל הבסיס להמשך.")
        if (records.isEmpty() && improved.isEmpty() && stalled.isEmpty() && firsts.isEmpty()) t.add("אימון יציב. בסיס טוב להוסיף ממנו.")
        if (deloadThis) t.add("שבוע הורדת עומס: המשקלים נמוכים בכוונה.")
        if (planned > 0 && completed < planned) t.add("הושלמו $completed מתוך $planned סטים.")
        t.add("טיפ: $tip")
        return Facts(lines, t.joinToString(" "), next)
    }

    // ------------------------------------------------------------------ phrasing

    private const val SYSTEM = """
You are the serious, factual strength coach inside a personal Hebrew fitness app, talking to the trainee right after
a gym session. Write 2 to 4 short sentences in Hebrew only: what improved, what stalled (if anything), and the tip.
Use ONLY the facts given, keep their numbers exactly, never invent numbers, never suggest more weight, reps or sets
than the facts say, do not diagnose. Do not list the next-session targets (they are shown separately). No lists,
no English letters, no emojis, no greeting, no jokes. Tone: calm, direct, encouraging without exaggeration.
"""

    /** [SYSTEM] for the current player; male: exactly as before. */
    private fun system(): String =
        if (Addr.isFemale()) SYSTEM.trimIndent() + "\nThe trainee is a woman: address her in Hebrew female grammar (את, עשית, תנסי, חזקה)."
        else SYSTEM.trimIndent()

    /** (text, fromAi). */
    private suspend fun phrase(f: Facts): Pair<String, Boolean> {
        if (!AiClient.enabled) return Pair(f.template, false)
        val prompt = "העובדות:\n" + f.lines.joinToString("\n") { "- $it" }
        // The HTTP call blocks a thread, so it runs outside this coroutine and we only wait [AI_TIMEOUT_MS] for it.
        val job = AppGraph.scope.async {
            val sys = system()
            AiClient.generate(listOf(AiClient.Part.Text(prompt)), sys, tier = AiClient.Tier.LITE, temperature = 0.3, maxOutputTokens = 400)
                ?: AiClient.generate(listOf(AiClient.Part.Text(prompt)), sys, tier = AiClient.Tier.MAIN, temperature = 0.3, maxOutputTokens = 400)
        }
        val ai = withTimeoutOrNull(AI_TIMEOUT_MS) { job.await() }?.trim()
        return if (ai != null && CoachNotes.isCleanHebrew(ai, 600)) Pair(ai, true) else Pair(f.template, false)
    }

    // ------------------------------------------------------------------ local cache

    private fun sp(): SharedPreferences? =
        runCatching { AppGraph.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    private fun load(sessionId: Long): Debrief? {
        val raw = sp()?.getString("s_$sessionId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("next") ?: JSONArray()
            val next = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            Debrief(o.optString("text"), next, o.optBoolean("ai", false))
        }.getOrNull()?.takeIf { it.text.isNotBlank() }
    }

    private fun save(sessionId: Long, d: Debrief) {
        val prefs = sp() ?: return
        runCatching {
            val o = JSONObject().put("text", d.text).put("next", JSONArray(d.nextLines)).put("ai", d.fromAi)
            val ids = (prefs.getString(K_IDS, "") ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableList()
            ids.remove(sessionId)
            ids.add(sessionId)
            val ed = prefs.edit()
            while (ids.size > KEEP) {
                val old = ids.removeAt(0)
                ed.remove("s_$old")
            }
            ed.putString("s_$sessionId", o.toString()).putString(K_IDS, ids.joinToString(",")).apply()
        }
    }
}
