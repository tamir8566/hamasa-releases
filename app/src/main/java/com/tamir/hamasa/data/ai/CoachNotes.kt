package com.tamir.hamasa.data.ai

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.coach.CoachConstraints
import com.tamir.hamasa.domain.coach.Difficulty
import com.tamir.hamasa.domain.coach.ExerciseCatalog
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.domain.coach.MovementPatterns
import com.tamir.hamasa.domain.game.Addr
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * "הודעה לג'ים ברו" (stage 2 G): a free-text note ("השבוע הברך כואבת לי", "היום רק גומייה ומשקל גוף", "תן לי קשה")
 * becomes strict, validated limits / preferences with an expiry. Gemini reads the note with the full exercise list; every field it returns is checked on our side
 * (unknown ids / branches / patterns are dropped, numbers are clamped). Without a key, offline, or on a bad answer,
 * simple keyword rules run instead. The limits are stored in Prefs and read by WorkoutRepository.context().
 */
object CoachNotes {

    /** What the note produced. [constraints] null = nothing to change (only [replyHe] is shown). */
    data class Result(
        val constraints: CoachConstraints?,
        val replyHe: String,
        val needsDoctor: Boolean,
        val fromAi: Boolean
    )

    private val BRANCHES: Map<String, Branch> = mapOf(
        "CHEST" to Branch.CHEST, "BACK" to Branch.BACK, "LEGS" to Branch.LEGS,
        "SHOULDERS_ARMS" to Branch.SHOULDERS_ARMS, "CORE" to Branch.CORE
    )
    private val EQUIPMENT: Set<String> = setOf("barbell", "dumbbell", "machine", "cable", "body", "band")
    private val PLACES: Set<String> = setOf("home", "gym", "outside")
    private val SESSION_TYPES: Set<String> = setOf("GYM", "RUN", "YOGA", "WALK")
    private const val DEFAULT_DAYS = 7
    /** Preferences ("היום גומייה") are about today unless the note says otherwise. */
    private const val DEFAULT_PREF_DAYS = 1
    /** The model gets the whole exercise list; with thinking it can take longer than the old 20 s. */
    private const val AI_TIMEOUT_MS = 35_000L

    const val DOCTOR_LINE = "כאב שלא עובר, כאב חד, נפיחות או נימול: שווה לבדוק אצל רופא או פיזיותרפיסט. הג'ים ברו מתאים אימונים, לא מאבחן."

    // ------------------------------------------------------------------ storage

    /** The stored limits when they still hold on [today]; null otherwise. */
    fun active(p: Profile, today: Long): CoachConstraints? {
        if (p.coachConstraintsJson.isEmpty() || p.coachConstraintsUntil <= 0L || today > p.coachConstraintsUntil) return null
        return fromJson(p.coachConstraintsJson, p.coachConstraintsUntil)
    }

    /** Today's difficulty: an active note's override, else the coach-screen setting. */
    fun effectiveDifficulty(p: Profile, today: Long): Difficulty =
        active(p, today)?.difficultyOverride ?: Difficulty.fromName(p.coachDifficulty)

    suspend fun save(c: CoachConstraints) {
        val json = toJson(c)
        AppGraph.prefs.update { it.copy(coachConstraintsJson = json, coachConstraintsUntil = c.untilEpochDay) }
    }

    suspend fun clear() {
        AppGraph.prefs.update { it.copy(coachConstraintsJson = "", coachConstraintsUntil = 0L) }
    }

    /** "6.11" for the chip. */
    fun dayLabel(epochDay: Long): String {
        val d = Days.of(epochDay)
        return "${d.dayOfMonth}.${d.monthValue}"
    }

    /**
     * The chip on the coach screen: "מותאם לברך עד 6.11", "מותאם להיום: גומייה ומשקל גוף", "מותאם: קל עד 6.11".
     */
    fun chipLabel(c: CoachConstraints, today: Long): String {
        val prefs = c.preferenceLabelHe()
        val subject = c.subjectHe
        return when {
            subject.isNotEmpty() && prefs.isNotEmpty() -> "מותאם $subject, $prefs עד ${dayLabel(c.untilEpochDay)}"
            subject.isNotEmpty() -> "מותאם $subject עד ${dayLabel(c.untilEpochDay)}"
            prefs.isNotEmpty() && c.untilEpochDay <= today -> "מותאם להיום: $prefs"
            prefs.isNotEmpty() -> "מותאם: $prefs עד ${dayLabel(c.untilEpochDay)}"
            else -> "מותאם להודעה שלך עד ${dayLabel(c.untilEpochDay)}"
        }
    }

    fun toJson(c: CoachConstraints): String = JSONObject().apply {
        put("avoid_exercises", JSONArray(c.avoidExercises.toList()))
        put("avoid_branches", JSONArray(c.avoidBranches.map { it.name }))
        put("avoid_patterns", JSONArray(c.avoidPatterns.toList()))
        put("max_rpe", c.maxRpe ?: JSONObject.NULL)
        put("reduce_sets_pct", c.reducePct)
        put("prefer_equipment", JSONArray(c.preferEquipment.toList()))
        put("reply_he", c.replyHe)
        put("needs_doctor", c.needsDoctor)
        put("note_he", c.noteHe)
        put("subject_he", c.subjectHe)
        put("difficulty_override", c.difficultyOverride?.name ?: JSONObject.NULL)
        put("only_equipment", JSONArray(c.onlyEquipment.toList()))
        put("place", c.place)
        put("max_minutes", c.maxMinutes ?: JSONObject.NULL)
        put("focus_branches", JSONArray(c.focusBranches.map { it.name }))
        put("session_type", c.sessionType)
    }.toString()

    /** Parses a stored JSON (our own format); ids are not re-checked here (a deleted custom id simply never matches). */
    fun fromJson(json: String, until: Long): CoachConstraints? = try {
        val o = JSONObject(json)
        CoachConstraints(
            avoidExercises = strings(o.optJSONArray("avoid_exercises")).toSet(),
            avoidBranches = strings(o.optJSONArray("avoid_branches")).mapNotNull { BRANCHES[it] }.toSet(),
            avoidPatterns = strings(o.optJSONArray("avoid_patterns")).filter { it in MovementPatterns.keys }.toSet(),
            maxRpe = if (o.has("max_rpe") && !o.isNull("max_rpe")) o.optInt("max_rpe", 10).coerceIn(6, 10) else null,
            reducePct = o.optInt("reduce_sets_pct", 0).coerceIn(0, 50),
            preferEquipment = strings(o.optJSONArray("prefer_equipment")).filter { it in EQUIPMENT }.toSet(),
            untilEpochDay = until,
            replyHe = o.optString("reply_he", ""),
            needsDoctor = o.optBoolean("needs_doctor", false),
            noteHe = o.optString("note_he", ""),
            subjectHe = o.optString("subject_he", ""),
            difficultyOverride = difficultyOf(if (o.isNull("difficulty_override")) "" else o.optString("difficulty_override", "")),
            onlyEquipment = strings(o.optJSONArray("only_equipment")).filter { it in EQUIPMENT }.toSet(),
            place = o.optString("place", "").takeIf { it in PLACES } ?: "",
            maxMinutes = if (o.has("max_minutes") && !o.isNull("max_minutes")) o.optInt("max_minutes", 0).takeIf { it > 0 }?.coerceIn(15, 120) else null,
            focusBranches = strings(o.optJSONArray("focus_branches")).mapNotNull { BRANCHES[it] }.toSet(),
            sessionType = o.optString("session_type", "").takeIf { it in SESSION_TYPES } ?: ""
        )
    } catch (e: Exception) {
        null
    }

    /** "EASY" / "NORMAL" / "HARD" (any case) -> the difficulty; anything else -> null. */
    private fun difficultyOf(v: String): Difficulty? = when (v.trim().uppercase()) {
        "EASY" -> Difficulty.EASY
        "NORMAL" -> Difficulty.NORMAL
        "HARD" -> Difficulty.HARD
        else -> null
    }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    // ------------------------------------------------------------------ interpret

    /**
     * Turns [note] into limits. Gemini first (when a key is set), then the keyword rules. Never throws.
     * [customs] are the user's own exercises (their ids are valid too).
     */
    suspend fun interpret(note: String, customs: List<ExerciseEntity>, today: Long): Result {
        val clean = note.trim().take(600)
        if (clean.isEmpty()) return Result(null, "", false, false)
        val ai = runCatching { fromGemini(clean, customs, today) }.getOrNull()
        if (ai != null) return ai
        return offline(clean, today)
    }

    private fun catalogFor(customs: List<ExerciseEntity>): List<ExerciseEntity> =
        ExerciseCatalog.gym + customs.filter { it.target != ExerciseTargets.MOBILITY }

    private suspend fun fromGemini(note: String, customs: List<ExerciseEntity>, today: Long): Result? {
        if (!AiClient.enabled) return null
        val list = catalogFor(customs)
        val sb = StringBuilder()
        for (e in list) {
            val patterns = MovementPatterns.of(e).joinToString(",")
            sb.append(e.id).append(" | ").append(e.nameHe).append(" | ").append(e.branch.name).append(" | ")
                .append(e.equipment).append(" | ").append(if (e.isCompound) "compound" else "accessory")
                .append(" | ").append(patterns).append('\n')
        }
        val patternsText = MovementPatterns.all.joinToString("\n") { "${it.first}: ${it.second}" }
        val system = """
You are the strength coach inside a personal Hebrew fitness app for one adult man (about 105 kg, beginner/intermediate,
goal: fat loss and strength). He writes a short free-text note to the coach in Hebrew. The note can be about pain or
fatigue (make training safer), OR a preference for today / the next days (equipment, place, available time, what to
train, difficulty), or both. Turn it into STRICT JSON for the next days. You never diagnose.

Return ONLY one JSON object with exactly these keys:
{"avoid_exercises":[ids], "avoid_branches":[...], "avoid_patterns":[...], "max_rpe": 6..10 or null,
 "reduce_sets_pct": 0..50, "prefer_equipment":[...], "days": 1..14, "reply_he": "...", "needs_doctor": true/false,
 "subject_he": "...", "difficulty": "EASY" | "NORMAL" | "HARD" | null, "only_equipment": [...],
 "place": "home" | "gym" | "outside" | "", "max_minutes": 15..120 or null, "focus_branches": [...],
 "session_type": "GYM" | "RUN" | "YOGA" | "WALK" | ""}

Rules:
- avoid_exercises: only ids from the exercise list below, copied exactly.
- avoid_branches: only CHEST, BACK, LEGS, SHOULDERS_ARMS, CORE. Use a whole branch only when the note really rules it out.
- avoid_patterns: only these keys:
$patternsText
- Prefer avoid_patterns over long id lists. Knee pain -> knee_flexion_loaded. Lower back pain -> spinal_load and hip_hinge.
  Shoulder pain -> overhead. Elbow -> elbow_load. Wrist -> wrist_load.
- max_rpe: lower it (7 or 8) for pain or fatigue, null when nothing hurts.
- reduce_sets_pct: 20..40 for fatigue, illness or very poor sleep, 0 otherwise. Never more than 50.
- prefer_equipment: only barbell, dumbbell, machine, cable, body, band. Machines and cables are usually gentler on joints.
- days: how long the limits should hold. "השבוע" = 7. Default 7.
- Never increase load or volume. Nothing in the JSON can add weight, reps or sets.
- Preferences (use them whenever the note asks for something, even with no pain at all):
  - only_equipment: what is really available, only barbell, dumbbell, machine, cable, body, band.
    "גומייה" / "גומיה" / "רצועה" -> band. "משקל גוף" / "בלי ציוד" -> body. "רק משקולות יד" -> dumbbell. "מכונות" -> machine.
    Example: "אני רוצה היום שנעשה אימון עם גומיה/משקל גוף" -> only_equipment ["band","body"], days 1.
  - place: "בבית" -> home, "במכון" -> gym, "בחוץ" / "בפארק" -> outside. Empty string when not said.
  - max_minutes: "יש לי 30 דקות" -> 30, "חצי שעה" -> 30. null when not said.
  - focus_branches: "רק רגליים" -> ["LEGS"], "אימון חזה" -> ["CHEST"]. Only CHEST, BACK, LEGS, SHOULDERS_ARMS, CORE.
  - session_type: "בא לי ריצה" -> RUN, "יוגה היום" -> YOGA, "הליכה" -> WALK, a gym wish -> GYM. Empty when not said.
  - difficulty: "תן לי קשה" / "מרגיש חזק" -> HARD, "קל השבוע" / "עייף, תן לי קל" -> EASY, "רגיל" -> NORMAL.
    Never HARD together with pain or illness; with pain or fatigue prefer EASY or null.
  - A preference about "היום" lasts 1 day; "השבוע" lasts until Saturday; default for a preference is 1 day,
    default for pain or fatigue is 7 days.
- reply_he: one or two short serious sentences in Hebrew only, no English letters, no emojis, no diagnosis.
  Say what changes in training. Example: "רשמתי. עד שבוע הבא בלי כיפופי ברך תחת עומס, הירך האחורית והישבן נשארים."
- needs_doctor: true for sharp pain, swelling, numbness, pain after a fall or injury, pain lasting over two weeks,
  chest pain, dizziness. Otherwise false.
- subject_he: what the PAIN / fatigue limits are about, 1 to 3 Hebrew words starting with the letter ל, like "לברך",
  "לגב התחתון", "לכתף", "לעייפות". Empty string for a preference-only note or when unclear.
- reply_he for a preference: say what the plan will be. Example: "סגור. היום אימון עם גומייה ומשקל גוף, אותם כללים."
- If the note asks for nothing to change, return empty lists, null, 0, empty strings and a short reply.

Exercise list (id | Hebrew name | branch | equipment | type | patterns):
$sb
""".trimIndent().let {
            if (Addr.isFemale()) it + "\n\nThe trainee is a woman (not the man described above). She writes in Hebrew female grammar (עייפה, מרגישה, תני לי). reply_he addresses her in Hebrew female grammar."
            else it
        }
        // the HTTP call blocks a thread: run it outside this coroutine and wait at most AI_TIMEOUT_MS, then the
        // offline rules (the old 20 s was too short for the long prompt with thinking: the answer was dropped)
        val job = AppGraph.scope.async { AiClient.generateJson(listOf(AiClient.Part.Text("ההודעה לג'ים ברו: $note")), system) }
        val obj = withTimeoutOrNull(AI_TIMEOUT_MS) { job.await() } ?: return null
        return validate(obj, note, list.map { it.id }.toSet(), today)
    }

    /** Validates the model's JSON on our side. Null when the answer is unusable (then the offline rules run). */
    private fun validate(o: JSONObject, note: String, validIds: Set<String>, today: Long): Result? {
        val ids = strings(o.optJSONArray("avoid_exercises")).filter { it in validIds }.toSet()
        val branches = strings(o.optJSONArray("avoid_branches")).mapNotNull { BRANCHES[it.uppercase()] }.toSet()
        val patterns = strings(o.optJSONArray("avoid_patterns")).filter { it in MovementPatterns.keys }.toSet()
        val rpe: Int? = if (o.has("max_rpe") && !o.isNull("max_rpe")) {
            val v = o.optInt("max_rpe", 10)
            if (v in 6..9) v else null
        } else null
        val pct = o.optInt("reduce_sets_pct", 0).coerceIn(0, 50)
        val equip = strings(o.optJSONArray("prefer_equipment")).map { it.lowercase() }.filter { it in EQUIPMENT }.toSet()
        val days = o.optInt("days", DEFAULT_DAYS).coerceIn(1, 14)
        val doctor = o.optBoolean("needs_doctor", false) || doctorWords(note)
        val offlineGuess = offline(note, today)
        val subject = o.optString("subject_he", "").trim().takeIf { it.startsWith("ל") && isCleanHebrew(it, 24) }
            ?: offlineGuess.constraints?.subjectHe ?: ""
        // preferences (validated like everything else)
        val diffRaw = if (o.has("difficulty") && !o.isNull("difficulty")) o.optString("difficulty", "") else ""
        val onlyEq = strings(o.optJSONArray("only_equipment")).map { it.lowercase() }.filter { it in EQUIPMENT }.toSet()
        val place = o.optString("place", "").trim().lowercase().takeIf { it in PLACES } ?: ""
        val minutes: Int? = if (o.has("max_minutes") && !o.isNull("max_minutes")) {
            o.optInt("max_minutes", 0).takeIf { it > 0 }?.coerceIn(15, 120)
        } else null
        val focus = strings(o.optJSONArray("focus_branches")).mapNotNull { BRANCHES[it.uppercase()] }.toSet()
        val type = o.optString("session_type", "").trim().uppercase().takeIf { it in SESSION_TYPES } ?: ""
        val painOrFatigue = patterns.isNotEmpty() || branches.isNotEmpty() || ids.isNotEmpty() || pct > 0 || (rpe != null && rpe <= 8)
        // a preference the model left out but the keyword rules found clearly is kept (e.g. "גומיה" -> band)
        val og = offlineGuess.constraints
        var difficulty = difficultyOf(diffRaw) ?: og?.difficultyOverride
        // safety: never "קשה" while the same note limits pain or fatigue
        if (difficulty == Difficulty.HARD && painOrFatigue) difficulty = null
        var c = CoachConstraints(
            avoidExercises = ids, avoidBranches = branches, avoidPatterns = patterns, maxRpe = rpe, reducePct = pct,
            preferEquipment = equip, untilEpochDay = today + days - 1, replyHe = "", needsDoctor = doctor,
            noteHe = note, subjectHe = subject,
            difficultyOverride = difficulty,
            onlyEquipment = onlyEq.ifEmpty { og?.onlyEquipment ?: emptySet() },
            place = place.ifEmpty { og?.place ?: "" },
            maxMinutes = minutes ?: og?.maxMinutes,
            focusBranches = focus.ifEmpty { og?.focusBranches ?: emptySet() },
            sessionType = type.ifEmpty { og?.sessionType ?: "" }
        )
        // the model returned nothing usable but the keyword rules understood the note: keep the rules' result
        if (c.isEmpty && offlineGuess.constraints != null) {
            return offlineGuess.copy(fromAi = false)
        }
        // a reply that fails the Hebrew check is replaced by one built from the result (never the "not understood" text)
        val reply = o.optString("reply_he", "").trim().takeIf { isCleanHebrew(it, 240) }
            ?: if (c.isEmpty) "קיבלתי. אין מה לשנות בתוכנית." else defaultReply(c, today)
        c = c.copy(replyHe = reply)
        return Result(if (c.isEmpty) null else c, reply, doctor, true)
    }

    /** "רשמתי. עד 6.11: ..." / "סגור. היום: גומייה ומשקל גוף." built from the stored result. */
    private fun defaultReply(c: CoachConstraints, today: Long): String {
        val parts = ArrayList<String>()
        val pain = describe(c, emptyList()).filter { !it.startsWith("העדפה") }
        val prefs = c.preferenceLabelHe()
        if (prefs.isNotEmpty()) parts.add(prefs)
        if (c.hasLimits) parts.addAll(pain.take(2))
        val whenText = if (c.untilEpochDay <= today) "היום" else "עד ${dayLabel(c.untilEpochDay)}"
        return if (c.hasLimits) "רשמתי. $whenText: ${parts.joinToString(", ")}."
        else "סגור. $whenText: ${parts.joinToString(", ")}. אותם כללים."
    }

    /** Hebrew text with no Latin letters, no emojis, within [maxLen]. */
    fun isCleanHebrew(s: String, maxLen: Int): Boolean {
        if (s.isEmpty() || s.length > maxLen) return false
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') return false
            if (Character.isSurrogate(ch)) return false
            val type = Character.getType(ch)
            if (type == Character.OTHER_SYMBOL.toInt()) return false
        }
        return s.any { it in 'א'..'ת' }
    }

    // ------------------------------------------------------------------ offline rules

    private fun doctorWords(note: String): Boolean {
        val words = listOf("נפיחות", "נפוח", "נימול", "הירדמות", "כאב חד", "דקירה", "נפלתי", "נקע", "שבועיים", "לא עובר", "חזה לוחץ", "סחרחורת")
        return words.any { note.contains(it) }
    }

    /** Hebrew words of [note] (letters only), for whole-word checks like "קל" (so "קלוריות" never matches). */
    private fun words(note: String): Set<String> =
        note.split(Regex("[^\\p{L}]+")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** A word, also with a one-letter Hebrew prefix (ה, ב, ל, ו, ש, מ). */
    private fun hasWord(ws: Set<String>, w: String): Boolean =
        w in ws || listOf("ה", "ב", "ל", "ו", "ש", "מ").any { (it + w) in ws }

    /** Minutes asked for: "30 דקות", "יש לי 45 דק'", "חצי שעה", "רבע שעה", "שעה". Null = not said. */
    private fun minutesOf(note: String): Int? {
        val m = Regex("(\\d{1,3})\\s*(דקות|דקה|דק)").find(note)
        if (m != null) return m.groupValues[1].toIntOrNull()?.coerceIn(15, 120)
        if (note.contains("רבע שעה")) return 15
        if (note.contains("חצי שעה")) return 30
        if (note.contains("שלושת רבעי שעה")) return 45
        if (note.contains("שעה וחצי")) return 90
        if (note.contains("יש לי שעה") || note.contains("רק שעה")) return 60
        return null
    }

    /**
     * Keyword rules. Pain / fatigue: knee / lower back / shoulder / elbow / wrist / fatigue.
     * Preferences: difficulty (קל / קשה), equipment (גומייה, משקל גוף, משקולות יד, מכונות), place (בבית / במכון /
     * בחוץ), minutes, session type (ריצה / יוגה / הליכה), "רק רגליים" style focus, and how long ("היום", "השבוע").
     */
    fun offline(note: String, today: Long): Result {
        val ws = words(note)
        val patterns = LinkedHashSet<String>()
        val subjects = ArrayList<String>()
        val changes = ArrayList<String>()
        if (note.contains("ברך") || note.contains("ברכיים")) {
            patterns.add(MovementPatterns.KNEE_FLEXION_LOADED)
            subjects.add("לברך")
            changes.add("בלי כיפופי ברך תחת עומס")
        }
        if (note.contains("גב תחתון") || note.contains("גב התחתון") || note.contains("מותן") || note.contains("עמוד השדרה")) {
            patterns.add(MovementPatterns.SPINAL_LOAD)
            patterns.add(MovementPatterns.HIP_HINGE)
            subjects.add("לגב התחתון")
            changes.add("בלי עומס על עמוד השדרה")
        }
        // "אימון כתפיים" / "רק כתפיים" is a wish, not pain
        val shoulderWish = note.contains("רק כתפיים") || note.contains("אימון כתפיים") || note.contains("בא לי כתפיים")
        if ((note.contains("כתף") || note.contains("כתפיים")) && !shoulderWish) {
            patterns.add(MovementPatterns.OVERHEAD)
            subjects.add("לכתף")
            changes.add("בלי לחיצות מעל הראש")
        }
        if (note.contains("מרפק")) {
            patterns.add(MovementPatterns.ELBOW_LOAD)
            subjects.add("למרפק")
            changes.add("פחות עומס על המרפק")
        }
        if (note.contains("שורש כף היד") || note.contains("פרק כף היד") || note.contains("שורש היד") || note.contains("פרק היד")) {
            patterns.add(MovementPatterns.WRIST_LOAD)
            subjects.add("לפרק היד")
            changes.add("פחות עומס על פרק היד")
        }
        val tired = listOf("עייף", "עייפות", "חולה", "מצונן", "שפעת", "גמור", "מותש", "לא ישנתי").any { note.contains(it) }
        val pct = if (tired) 30 else 0
        if (tired) {
            if (subjects.isEmpty()) subjects.add("לעייפות")
            changes.add("פחות סטים")
        }
        val pain = patterns.isNotEmpty()

        // ---- preferences
        var difficulty: Difficulty? = null
        val hardPhrases = listOf("מרגיש חזק", "מרגישה חזקה", "תן לי קשה", "תני לי קשה", "תעלי רמה", "קשה היום", "קשה השבוע", "אימון קשה", "רמה קשה", "תעלה רמה", "אינטנסיבי", "בכל הכוח")
        if (hardPhrases.any { note.contains(it) } || (hasWord(ws, "קשה") && !note.contains("קשה לי") && !pain && !tired)) difficulty = Difficulty.HARD
        if (hasWord(ws, "קל") || hasWord(ws, "קלה") || hasWord(ws, "בקלות") || note.contains("בעדינות") || note.contains("תוריד רמה") || note.contains("תורידי רמה")) difficulty = Difficulty.EASY
        if (difficulty == null && (note.contains("רמה רגילה") || note.contains("קושי רגיל"))) difficulty = Difficulty.NORMAL
        // safety: never "קשה" together with pain or fatigue
        if (difficulty == Difficulty.HARD && (pain || tired)) difficulty = null

        val equipment = LinkedHashSet<String>()
        if (note.contains("גומי") || note.contains("רצוע")) equipment.add("band")
        if (note.contains("משקל גוף") || note.contains("משקל הגוף") || note.contains("בלי ציוד") || note.contains("ללא ציוד")) equipment.add("body")
        if (note.contains("משקולות יד") || note.contains("דאמבל") || note.contains("משקולות בלבד") || note.contains("רק משקולות")) equipment.add("dumbbell")
        if (note.contains("מכונות") || note.contains("רק מכונה")) equipment.add("machine")
        if (note.contains("כבלים")) equipment.add("cable")

        val place = when {
            hasWord(ws, "בבית") || note.contains("מהבית") -> "home"
            hasWord(ws, "בחוץ") || note.contains("בפארק") -> "outside"
            note.contains("במכון") || note.contains("בחדר כושר") || note.contains("בחדר הכושר") -> "gym"
            else -> ""
        }
        val minutes = minutesOf(note)
        // a session type only as a wish ("בא לי ריצה", "יוגה היום"), never from "אחרי הריצה אתמול כואב"
        val wishWords = listOf("בא לי", "רוצה", "היום", "תן לי", "תני לי", "מחר", "השבוע")
        val wishes = wishWords.any { note.contains(it) } && !pain
        val type = when {
            !wishes -> ""
            note.contains("ריצה") || note.contains("לרוץ") || note.contains("ריצת") -> "RUN"
            note.contains("יוגה") -> "YOGA"
            note.contains("הליכה") || note.contains("ללכת ברגל") -> "WALK"
            else -> ""
        }
        val focus = LinkedHashSet<Branch>()
        fun wish(word: String): Boolean = listOf("רק ", "אימון ", "בא לי ", "יום ").any { note.contains(it + word) }
        if (wish("רגליים") || wish("רגל")) focus.add(Branch.LEGS)
        if (wish("חזה")) focus.add(Branch.CHEST)
        if (wish("גב") && !note.contains("גב תחתון") && !note.contains("גב התחתון")) focus.add(Branch.BACK)
        if (wish("כתפיים") || wish("ידיים")) focus.add(Branch.SHOULDERS_ARMS)
        if (wish("בטן") || wish("ליבה")) focus.add(Branch.CORE)

        val hasPrefs = difficulty != null || equipment.isNotEmpty() || place.isNotEmpty() || minutes != null || type.isNotEmpty() || focus.isNotEmpty()
        val rpe: Int? = when {
            pain -> 8
            tired -> 7
            else -> null
        }
        val doctor = doctorWords(note)
        if (changes.isEmpty() && !hasPrefs) {
            val reply = "לא זיהיתי מה להתאים. אפשר לכתוב כאב או עייפות (ברך, גב תחתון, כתף, מרפק, פרק היד), " +
                "או העדפה: גומייה, משקל גוף, בבית, יש לי 30 דקות, רק רגליים, ריצה, יוגה, קל או קשה."
            return Result(null, reply, doctor, false)
        }
        val nDays = Regex("(\\d{1,2})\\s*ימים").find(note)?.groupValues?.get(1)?.toIntOrNull()
        val until = when {
            nDays != null -> today + nDays.coerceIn(1, 14) - 1
            note.contains("היום") -> today
            note.contains("השבוע") -> Days.weekEndOf(today)
            note.contains("מחר") -> today + 1
            changes.isNotEmpty() -> today + DEFAULT_DAYS - 1
            else -> today + DEFAULT_PREF_DAYS - 1
        }
        var c = CoachConstraints(
            avoidPatterns = patterns, maxRpe = rpe, reducePct = pct, untilEpochDay = until,
            replyHe = "", needsDoctor = doctor, noteHe = note,
            subjectHe = if (subjects.size == 1) subjects[0] else "",
            difficultyOverride = difficulty, onlyEquipment = equipment, place = place, maxMinutes = minutes,
            focusBranches = focus, sessionType = type
        )
        val whenText = if (until <= today) "היום" else "עד ${dayLabel(until)}"
        val prefs = c.preferenceLabelHe()
        val reply = if (changes.isNotEmpty()) {
            val all = if (prefs.isNotEmpty()) changes + prefs else changes
            "רשמתי. $whenText: ${all.joinToString(", ")}. בלי עליות משקל בינתיים."
        } else {
            "סגור. $whenText: $prefs. אותם כללים, הג'ים ברו בונה את האימון מזה."
        }
        c = c.copy(replyHe = reply)
        return Result(c, reply, doctor, false)
    }

    // ------------------------------------------------------------------ details text

    /** Hebrew lines for the details dialog: what is limited, in plain words. */
    fun describe(c: CoachConstraints, customs: List<ExerciseEntity>): List<String> {
        val out = ArrayList<String>()
        if (c.avoidPatterns.isNotEmpty()) {
            val names = MovementPatterns.all.filter { it.first in c.avoidPatterns }.map { it.second }
            out.add("בלי: " + names.joinToString("; "))
        }
        if (c.avoidBranches.isNotEmpty()) out.add("בלי ענף: " + c.avoidBranches.joinToString(", ") { ExerciseTargets.branchNameHe(it) })
        if (c.avoidExercises.isNotEmpty()) {
            val names = c.avoidExercises.map { id -> ExerciseCatalog.byId(id)?.nameHe ?: customs.firstOrNull { it.id == id }?.nameHe ?: id }
            out.add("בלי התרגילים: " + names.joinToString(", "))
        }
        if (c.maxRpe != null) out.add("מאמץ עד ${c.maxRpe} מתוך 10")
        if (c.reducePct > 0) out.add("${c.reducePct}% פחות סטים, קודם בתרגילי העזר")
        if (c.preferEquipment.isNotEmpty()) out.add("עדיפות ל" + c.preferEquipment.joinToString(", ") { equipmentHe(it) })
        if (c.holdsLoad) out.add("בלי עליות משקל עד ${dayLabel(c.untilEpochDay)}")
        // preferences
        val eq = c.allowedEquipment
        if (eq.isNotEmpty()) out.add("העדפה: רק " + eq.sortedBy { listOf("band", "body", "dumbbell", "machine", "cable", "barbell").indexOf(it) }.joinToString(", ") { equipmentHe(it) })
        if (c.focusBranches.isNotEmpty()) out.add("העדפה: רק " + c.focusBranches.sortedBy { it.ordinal }.joinToString(", ") { ExerciseTargets.branchNameHe(it) })
        val m = c.maxMinutes
        if (m != null) out.add("העדפה: אימון של עד $m דקות, קודם יורדים תרגילי העזר")
        when (c.sessionType) {
            "RUN" -> out.add("העדפה: ריצה")
            "YOGA" -> out.add("העדפה: יוגה")
            "WALK" -> out.add("העדפה: הליכה")
            "GYM" -> out.add("העדפה: מכון")
        }
        val d = c.difficultyOverride
        if (d != null) out.add("העדפה: רמת קושי ${d.nameHe} במקום ההגדרה")
        return out
    }

    private fun equipmentHe(key: String): String = when (key) {
        "barbell" -> "מוט"
        "dumbbell" -> "משקולות יד"
        "machine" -> "מכונות"
        "cable" -> "כבלים"
        "body" -> "משקל גוף"
        "band" -> "גומייה"
        "kettlebell" -> "קטלבל"
        "trx" -> "רצועות תלייה"
        else -> key
    }
}
