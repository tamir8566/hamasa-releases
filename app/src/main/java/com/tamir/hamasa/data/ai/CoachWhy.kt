package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachContext
import com.tamir.hamasa.domain.coach.CoachEngine
import com.tamir.hamasa.domain.coach.ExerciseHistory
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.domain.coach.PlannedExercise
import com.tamir.hamasa.domain.coach.SleepBand
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.domain.game.ModeCatalog
import com.tamir.hamasa.domain.game.ModeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * "למה?" (stage 2 G): why this weight / reps / sets today. The facts are deterministic (last performance, the
 * progression rule the engine applied, focus, mode, sleep, active note limits). Gemini only phrases them in 2–3
 * serious Hebrew sentences; without a key, offline, slow or with a bad answer, a template sentence built from the
 * same facts is shown. Answers are cached in memory per (session or day, exercise, target).
 */
object CoachWhy {

    private val cache = ConcurrentHashMap<String, String>()
    private const val AI_TIMEOUT_MS = 9_000L

    /** One exercise's facts: [lines] go to the model, [template] is the offline answer. */
    class Facts(val lines: List<String>, val template: String)

    // ------------------------------------------------------------------ entry points

    /**
     * Coach screen: a planned exercise of today's preview, with the sleep the screen is set to. [home] = a preview of
     * "אימון בבית" with [homeRequest] (his weights at home decide the rule).
     */
    suspend fun forPlanned(
        p: PlannedExercise, sleepHours: Float?, home: Boolean = false,
        homeRequest: com.tamir.hamasa.domain.coach.HomeRequest? = null
    ): String = withContext(Dispatchers.IO) {
        val key = "plan:${Days.today()}:${p.exerciseId}:${p.sets}:${p.reps}:${p.weightKg}:$sleepHours:${if (home) "home" else ""}"
        cache[key] ?: plannedUncached(p, sleepHours, key, home, homeRequest)
    }

    private suspend fun plannedUncached(
        p: PlannedExercise, sleepHours: Float?, key: String, home: Boolean,
        homeRequest: com.tamir.hamasa.domain.coach.HomeRequest?
    ): String {
        val ctx = if (home) AppGraph.workouts.homeContext(sleepHours, homeRequest) else AppGraph.workouts.context(sleepHours)
        val e = CoachEngine.exerciseOf(p.exerciseId, ctx) ?: AppGraph.workouts.exerciseById(p.exerciseId)
        val facts = build(p, e, ctx, ctx.history[p.exerciseId], userPlan = false)
        val answer = phrase(facts)
        cache[key] = answer
        return answer
    }

    /** Live workout: the exercise as planned in [sessionId] (set targets), against history before this session. */
    suspend fun forLive(sessionId: Long, exerciseId: String): String = withContext(Dispatchers.IO) {
        val key = "live:$sessionId:$exerciseId"
        cache[key] ?: liveUncached(sessionId, exerciseId, key)
    }

    private suspend fun liveUncached(sessionId: Long, exerciseId: String, key: String): String {
        val db = AppGraph.db
        val session = db.sessions().byId(sessionId)
        val isHome = session?.notes == com.tamir.hamasa.data.repo.WorkoutRepository.HOME_NOTES
        val base = if (isHome) AppGraph.workouts.homeContext(session?.sleepHours) else AppGraph.workouts.context(session?.sleepHours)
        val e = CoachEngine.exerciseOf(exerciseId, base) ?: AppGraph.workouts.exerciseById(exerciseId)
        val before = historyBefore(exerciseId, sessionId, e)
        val history = HashMap(base.history)
        if (before == null) {
            history.remove(exerciseId)
        } else {
            history[exerciseId] = before
        }
        val ctx = base.copy(history = history, modeId = session?.modeId ?: base.modeId)
        val sets = db.sessions().setsForOnce(sessionId).filter { it.exerciseId == exerciseId }.sortedBy { it.setIndex }
        val first = sets.firstOrNull { !it.completed } ?: sets.firstOrNull()
        val planned = PlannedExercise(
            exerciseId = exerciseId, nameHe = e?.nameHe ?: exerciseId,
            branch = e?.branch ?: CoachEngine.branchOf(exerciseId),
            sets = sets.size, reps = first?.targetReps ?: 0, weightKg = first?.targetWeight ?: 0f,
            restSec = CoachEngine.liveRestSec(e, ctx.focus, session?.modeId, null, ctx.difficulty)
        )
        val userPlan = session?.notes?.startsWith("template:") == true
        val facts = build(planned, e, ctx, before, userPlan)
        val answer = phrase(facts)
        cache[key] = answer
        return answer
    }

    /** The last session of [exerciseId] before [sessionId] (accessory sets of distorting modes are skipped, like the engine). */
    private suspend fun historyBefore(exerciseId: String, sessionId: Long, e: ExerciseEntity?): ExerciseHistory? {
        val db = AppGraph.db
        val recent = db.sessions().recentSetsFor(exerciseId, 30).filter { it.sessionId != sessionId }
        if (recent.isEmpty()) return null
        val groups: List<List<SetLogEntity>> = recent.groupBy { it.sessionId }.values.toList()
        var pick: List<SetLogEntity> = groups.first()
        if (e != null && !e.isCompound) {
            for (g in groups) {
                val mode = db.sessions().byId(g.first().sessionId)?.modeId ?: ""
                if (mode !in ModeRules.distortsAccessoryHistory) { pick = g; break }
            }
        }
        val last = pick.sortedBy { it.setIndex }
        return ExerciseHistory(
            exerciseId = exerciseId,
            lastWeight = last.maxOf { it.weight },
            lastReps = last.first().reps,
            lastSets = last.size,
            allSetsHitTargetLastTwo = false,
            missedTargetLast = last.any { it.reps < it.targetReps },
            bestWeight = 0f,
            lastSetReps = last.map { it.reps },
            lastTargetReps = last.first().targetReps
        )
    }

    // ------------------------------------------------------------------ facts

    private fun kg(v: Float): String =
        if (v == v.roundToInt().toFloat()) "${v.roundToInt()} ק\"ג" else String.format("%.1f ק\"ג", v)

    private fun focusHe(f: Focus): String = when (f) {
        Focus.LOSE_WEIGHT -> "ירידה במשקל"
        Focus.BUILD_MUSCLE -> "בניית שריר"
        Focus.STRENGTH -> "כוח"
        Focus.FLEXIBILITY -> "גמישות"
        Focus.ENDURANCE -> "סיבולת"
        Focus.BRANCH_CHEST -> "דגש חזה"
        Focus.BRANCH_BACK -> "דגש גב"
        Focus.BRANCH_LEGS -> "דגש רגליים"
        Focus.BRANCH_SHOULDERS_ARMS -> "דגש כתפיים וידיים"
        Focus.BRANCH_CORE -> "דגש ליבה"
    }

    private fun ruleHe(rule: String): String = when (rule) {
        "first" -> "תרגיל חדש: משקל התחלתי לפי משקל הגוף, ומחפשים משקל עבודה"
        "up" -> "בפעם הקודמת כל הסטים הגיעו לקצה העליון של הטווח, לכן מוסיפים משקל וחוזרים לקצה התחתון"
        "down" -> "בפעם הקודמת סט אחד ירד מתחת לקצה התחתון, לכן יורדים בערך 5% ומסדרים טכניקה"
        "same" -> "בפעם הקודמת עבדת בתוך הטווח, לכן אותו משקל ויעד של חזרה אחת יותר מהסט החלש"
        "hold_constraint" -> "הגעת לקצה העליון, אבל ההודעה לג'ים ברו עוצרת עליות משקל עד שההתאמה נגמרת"
        "bw_first" -> "תרגיל במשקל גוף שעוד לא נעשה: יעד חזרות התחלתי"
        "bw_top" -> "הגעת ל-15 חזרות בכל הסטים, אפשר לעבור לגרסה קשה יותר"
        "bw_next" -> "תרגיל במשקל גוף: חזרה אחת יותר מהסט החלש של הפעם הקודמת"
        "hold_first" -> "החזקה ראשונה: 30 שניות"
        "hold_top" -> "הגעת ל-45 שניות, מחזיקים יציב"
        "hold_next" -> "החזקה: חמש שניות יותר מהפעם הקודמת"
        "deload" -> "שבוע הורדת עומס: כעשרה אחוזים פחות משקל מהפעם הקודמת, אותן חזרות, פחות סטים ובלי עליות"
        "easy_wait" -> "הגעת לקצה העליון, אבל ברמת קושי קלה מחכים לאימון שני ברצף בקצה העליון לפני שמוסיפים משקל"
        "up_hard" -> "ברמת קושי קשה מוסיפים משקל כבר כשכל הסטים הגיעו לחזרה אחת מתחת לקצה העליון והסט האחרון הגיע אליו"
        "home_cap" -> "משקל היעד כבד מהמשקל שיש לך בבית, לכן נשארים במשקל הזה ומוסיפים חזרות בטווח 8 עד 12. בקצה הטווח מאטים את הירידה או עוברים לגרסה קשה יותר"
        "home_fixed" -> "המשקל שיש לך בבית קבוע וכבד מהיעד, לכן עובדים בקצה התחתון של טווח החזרות, בטכניקה נקייה"
        else -> ""
    }

    private fun sleepHe(b: SleepBand): String = when (b) {
        SleepBand.FULL -> "שינה מלאה"
        SleepBand.MEDIUM -> "שינה בינונית: סט אחד פחות בתרגילי העזר"
        SleepBand.SHORT -> "שינה קצרה: רק התרגילים הכבדים"
    }

    fun build(p: PlannedExercise, e: ExerciseEntity?, ctx: CoachContext, h: ExerciseHistory?, userPlan: Boolean): Facts {
        val unit = e?.unit ?: "kg"
        val compound = e?.isCompound ?: false
        val lines = ArrayList<String>()
        lines.add("תרגיל: ${p.nameHe}, ${if (compound) "תרגיל מורכב" else "תרגיל עזר"}, ענף ${ExerciseTargets.branchNameHe(p.branch)}")
        val dose = when (unit) {
            "sec" -> "${p.sets} סטים של ${p.reps} שניות"
            "reps" -> "${p.sets} סטים של ${p.reps} חזרות"
            else -> "${p.sets} סטים של ${p.reps} חזרות ב-${kg(p.weightKg)}"
        }
        lines.add("היום: $dose, מנוחה ${p.restSec} שניות")
        val ruleKey = if (userPlan) "" else CoachEngine.progressionRule(p.exerciseId, ctx.copy(history = if (h == null) ctx.history - p.exerciseId else ctx.history + (p.exerciseId to h)))
        val homeCap = ruleKey == "home_cap"
        if (homeCap) {
            lines.add("טווח החזרות בבית: 8 עד 12, כי המשקל שיש לך קל מהיעד")
        } else if (unit == "kg") {
            val r = CoachEngine.repRange(compound, ctx.focus)
            lines.add("טווח החזרות: ${r.first} עד ${r.second}" + if (compound) " (תרגיל מורכב תמיד 5 עד 8)" else " (לפי המיקוד)")
        }
        if (h != null && h.lastSetReps.isNotEmpty()) {
            val w = if (unit == "kg" && h.lastWeight > 0f) "${kg(h.lastWeight)}, " else ""
            lines.add("בפעם הקודמת: ${w}חזרות בכל סט: ${h.lastSetReps.joinToString(", ")}")
        } else {
            lines.add("בפעם הקודמת: אין היסטוריה לתרגיל הזה")
        }
        val rule = ruleHe(ruleKey)
        if (userPlan) lines.add("האימון נבנה על ידך: המשקלים והחזרות מהתבנית שלך")
        if (rule.isNotEmpty()) lines.add("כלל ההתקדמות: $rule")
        lines.add("מיקוד: ${focusHe(ctx.focus)}")
        val diffWhy = difficultyHe(ctx)
        lines.add(diffWhy)
        val modeName = ModeCatalog.byId(ctx.modeId)?.nameHe
        if (modeName != null) lines.add("מצב האימון היום: $modeName (משנה רק תרגילי עזר)")
        lines.add("שינה: ${sleepHe(ctx.sleep)}")
        val c = ctx.constraints
        // a real note to the coach (the home sheet alone has no end day and is described by the rule above)
        if (c != null && c.untilEpochDay > 0L) {
            val what = CoachNotes.describe(c, ctx.customExercises)
            lines.add("התאמה פעילה עד ${CoachNotes.dayLabel(c.untilEpochDay)}: " + what.joinToString("; "))
        }
        if (p.note.isNotEmpty()) lines.add("הערת הג'ים ברו: ${p.note}")

        // offline template: rule, dose, one context sentence
        val s = ArrayList<String>()
        if (userPlan) s.add("זה אימון שבנית בעצמך, אז המשקל והחזרות מגיעים מהתבנית")
        else if (rule.isNotEmpty()) s.add(rule)
        s.add("לכן היום $dose")
        if (homeCap && p.note.isNotEmpty()) s.add(p.note)
        when {
            c != null && c.holdsLoad -> s.add("ההודעה לג'ים ברו בתוקף עד ${CoachNotes.dayLabel(c.untilEpochDay)}, אז בינתיים לא מעלים משקל")
            ctx.sleep == SleepBand.MEDIUM && !compound -> s.add("השינה הייתה בינונית, ולכן תרגילי העזר קיבלו סט אחד פחות")
            homeCap -> s.add("כשהמשקולות יגיעו למשקל היעד, חוזרים ל-5 עד 8 חזרות")
            compound && unit == "kg" && ruleKey != "home_fixed" -> s.add("זה תרגיל מורכב: 5 עד 8 חזרות ומנוחה מלאה, כי כאן נבנה הכוח")
            modeName != null && !compound -> s.add("מצב האימון \"$modeName\" נוגע רק בתרגילי העזר")
        }
        if (ctx.difficulty != com.tamir.hamasa.domain.coach.Difficulty.NORMAL && !userPlan) s.add(diffWhy)
        val template = s.joinToString(". ") + "."
        return Facts(lines, template)
    }

    /** "רמת קושי: קשה (מנוחה קצרה ב-30 שניות, 0 עד 1 חזרות במאגר)", with the source when a note set it. */
    private fun difficultyHe(ctx: CoachContext): String {
        val d = ctx.difficulty
        val fromNote = ctx.constraints?.difficultyOverride != null
        val what = when (d) {
            com.tamir.hamasa.domain.coach.Difficulty.EASY -> "מנוחה ארוכה ב-30 שניות, פחות סטים, עלייה במשקל אחרי שני אימונים בקצה העליון"
            com.tamir.hamasa.domain.coach.Difficulty.HARD -> "מנוחה קצרה ב-30 שניות, עלייה במשקל כבר כשהסט האחרון בקצה העליון"
            com.tamir.hamasa.domain.coach.Difficulty.NORMAL -> "הכללים הרגילים"
        }
        val src = if (fromNote) " (לפי ההודעה לג'ים ברו)" else ""
        return "רמת קושי ${d.nameHe}$src: $what, ${d.rirNoteHe}"
    }

    // ------------------------------------------------------------------ phrasing

    private const val SYSTEM = """
You are the serious, factual strength coach inside a personal Hebrew fitness app. Explain to the trainee, in Hebrew only,
why today's target for one exercise is what it is. Use ONLY the facts given. Do not invent numbers, do not add advice
that raises weight, reps or sets, do not diagnose. Two or three short sentences, plain words, no lists, no English
letters, no emojis, no greeting. Tone: calm and direct, like a good coach between sets.
"""

    private suspend fun phrase(f: Facts): String {
        if (!AiClient.enabled) return f.template
        val prompt = "העובדות:\n" + f.lines.joinToString("\n") { "- $it" }
        // The HTTP call blocks a thread, so it runs outside this coroutine and we only wait [AI_TIMEOUT_MS] for it.
        val job = AppGraph.scope.async {
            val sys = if (Addr.isFemale()) SYSTEM.trimIndent() + "\nThe trainee is a woman: address her in Hebrew female grammar (את, עבדת, תנסי)." else SYSTEM.trimIndent()
            AiClient.generate(listOf(AiClient.Part.Text(prompt)), sys, tier = AiClient.Tier.LITE, temperature = 0.3, maxOutputTokens = 300)
                ?: AiClient.generate(listOf(AiClient.Part.Text(prompt)), sys, tier = AiClient.Tier.MAIN, temperature = 0.3, maxOutputTokens = 300)
        }
        val ai = withTimeoutOrNull(AI_TIMEOUT_MS) { job.await() }?.trim()
        return if (ai != null && CoachNotes.isCleanHebrew(ai, 420)) ai else f.template
    }
}
