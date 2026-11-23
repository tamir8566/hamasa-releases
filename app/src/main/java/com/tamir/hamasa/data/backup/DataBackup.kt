package com.tamir.hamasa.data.backup

import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BadgeEntity
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.InventoryEntity
import com.tamir.hamasa.data.db.LedgerEntity
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.db.MeasurementEntity
import com.tamir.hamasa.data.db.ModeUnlockEntity
import com.tamir.hamasa.data.db.RewardEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WeightEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full JSON export of the local data, and an import that restores it into an EMPTY database only
 * (no finished sessions and no weigh-ins yet). Progress photo files are not part of the export.
 *
 * Format 2. Format 1 (sessions + weights + profile only) is still accepted by [importJson].
 */
object DataBackup {
    const val FORMAT = 2

    // ------------------------------------------------------------------ export

    suspend fun buildExportJson(): String {
        val db = AppGraph.db
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("exportedAt", System.currentTimeMillis())
        val p = AppGraph.prefs.current()
        root.put("profile", JSONObject().apply {
            put("heightCm", p.heightCm); put("startWeightKg", p.startWeightKg.toDouble()); put("goalWeightKg", p.goalWeightKg.toDouble())
            put("focus", p.focus.name)
            put("xp", p.xp); put("gold", p.gold); put("streak", p.streak); put("bestStreak", p.bestStreak); put("shields", p.shields)
            put("lastActiveDay", p.lastActiveDay); put("luckChain", p.luckChain); put("lifetimeKg", p.lifetimeKg)
            put("kcalGoal", p.kcalGoal); put("monsterPot", p.monsterPot)
            put("quietFrom", p.quietFrom); put("quietTo", p.quietTo)
            put("playerName", p.playerName); put("characterVariant", p.characterVariant); put("userGender", p.userGender)
            put("stageAnchorStage", p.stageAnchorStage.toDouble()); put("stageAnchorWeight", p.stageAnchorWeight.toDouble())
        })

        val sessions = JSONArray()
        for (s in db.sessions().doneSessionsOnce()) {
            val o = JSONObject()
            o.put("id", s.id); o.put("day", s.dateEpochDay); o.put("type", s.type.name); o.put("status", s.status.name)
            o.put("mode", s.modeId ?: JSONObject.NULL); o.put("dailyGoal", s.dailyGoal ?: JSONObject.NULL)
            o.put("startedAt", s.startedAtMillis ?: JSONObject.NULL); o.put("endedAt", s.endedAtMillis ?: JSONObject.NULL)
            o.put("durationSec", s.durationSec); o.put("xp", s.xpEarned); o.put("gold", s.goldEarned); o.put("rating", s.rating)
            o.put("sleepHours", s.sleepHours?.toDouble() ?: JSONObject.NULL); o.put("notes", s.notes)
            val sets = JSONArray()
            for (st in db.sessions().setsForOnce(s.id)) {
                sets.put(JSONObject().apply {
                    put("id", st.id); put("exercise", st.exerciseId); put("set", st.setIndex)
                    put("targetReps", st.targetReps); put("targetWeight", st.targetWeight.toDouble())
                    put("reps", st.reps); put("weight", st.weight.toDouble()); put("completed", st.completed); put("pr", st.isPr)
                })
            }
            o.put("sets", sets)
            sessions.put(o)
        }
        root.put("sessions", sessions)

        root.put("weights", JSONArray().apply {
            for (w in db.weights().allOnce()) put(JSONObject().apply { put("day", w.dateEpochDay); put("kg", w.kg.toDouble()) })
        })
        root.put("customExercises", JSONArray().apply {
            for (e in db.exercises().customsOnce()) put(JSONObject().apply {
                put("id", e.id); put("nameHe", e.nameHe); put("branch", e.branch.name); put("equipment", e.equipment)
                put("isCompound", e.isCompound); put("unit", e.unit); put("alternativesCsv", e.alternativesCsv)
                put("description", e.description); put("target", e.target); put("preferred", e.preferred)
            })
        })
        root.put("meals", JSONArray().apply {
            for (m in db.meals().allOnce()) put(JSONObject().apply {
                put("id", m.id); put("day", m.dateEpochDay); put("atMillis", m.atMillis); put("description", m.description); put("kcal", m.kcal)
                put("proteinG", m.proteinG.toDouble()); put("carbsG", m.carbsG.toDouble()); put("fatG", m.fatG.toDouble())
                put("source", m.source); put("aiKcal", m.aiKcal); put("itemsJson", m.itemsJson)
            })
        })
        root.put("badges", JSONArray().apply {
            for (b in db.game().badgesOnce()) put(JSONObject().apply { put("id", b.id); put("at", b.unlockedAtMillis) })
        })
        root.put("modes", JSONArray().apply {
            for (m in db.game().modesOnce()) put(JSONObject().apply { put("id", m.modeId); put("at", m.unlockedAtMillis) })
        })
        root.put("inventory", JSONArray().apply {
            for (i in db.game().inventoryOnce()) put(JSONObject().apply { put("id", i.itemId); put("at", i.acquiredAtMillis); put("equipped", i.equipped) })
        })
        root.put("bosses", JSONArray().apply {
            for (b in db.game().bossesOnce()) put(JSONObject().apply {
                put("id", b.bossId); put("week", b.weekEpoch); put("hp", b.hp); put("maxHp", b.maxHp); put("defeated", b.defeated)
            })
        })
        val items = db.templates().allItemsOnce()
        root.put("templates", JSONArray().apply {
            for (t in db.templates().templatesOnce()) put(JSONObject().apply {
                put("id", t.id); put("nameHe", t.nameHe); put("createdAt", t.createdAt); put("lastUsedAt", t.lastUsedAt); put("notes", t.notes)
                put("items", JSONArray().apply {
                    for (ti in items.filter { x -> x.templateId == t.id }) put(JSONObject().apply {
                        put("id", ti.id); put("orderIndex", ti.orderIndex); put("exercise", ti.exerciseId); put("sets", ti.sets)
                        put("reps", ti.reps); put("weightKg", ti.weightKg.toDouble()); put("restSec", ti.restSec); put("note", ti.note)
                        put("setsCsv", ti.setsCsv)
                    })
                })
            })
        })
        root.put("rewards", JSONArray().apply {
            for (r in db.rewards().allOnce()) put(JSONObject().apply {
                put("id", r.id); put("nameHe", r.nameHe); put("priceGold", r.priceGold); put("note", r.note)
                put("createdAt", r.createdAt); put("redeemedCount", r.redeemedCount)
            })
        })
        root.put("ledger", JSONArray().apply {
            for (l in db.game().ledgerOnce()) put(JSONObject().apply {
                put("id", l.id); put("at", l.atMillis); put("xp", l.xp); put("gold", l.gold); put("reason", l.reason)
            })
        })
        root.put("measurements", JSONArray().apply {
            for (m in db.progress().measurementsOnce()) put(JSONObject().apply {
                put("id", m.id); put("day", m.dateEpochDay); put("waist", m.waistCm.toDouble()); put("chest", m.chestCm.toDouble())
                put("arm", m.armCm.toDouble()); put("thigh", m.thighCm.toDouble()); put("hips", m.hipsCm.toDouble())
            })
        })
        return root.toString(2)
    }

    // ------------------------------------------------------------------ import

    /** True when there is nothing to overwrite: no sessions and no weigh-ins. */
    suspend fun isDatabaseEmpty(): Boolean =
        AppGraph.db.sessions().countAll() == 0 && AppGraph.db.weights().allOnce().isEmpty()

    sealed class ImportResult {
        data class Ok(val sessions: Int) : ImportResult()
        object NotEmpty : ImportResult()
        object BadFile : ImportResult()
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.strOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
    private fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
    private inline fun JSONArray.objects(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    /** Restores a [buildExportJson] file. Refuses when the database already has data. */
    suspend fun importJson(text: String): ImportResult {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ImportResult.BadFile
        if (!root.has("sessions") && !root.has("weights")) return ImportResult.BadFile
        if (!isDatabaseEmpty()) return ImportResult.NotEmpty
        val db = AppGraph.db

        val customs = ArrayList<ExerciseEntity>()
        root.arr("customExercises").objects { o ->
            val branch = runCatching { Branch.valueOf(o.optString("branch")) }.getOrDefault(Branch.CORE)
            customs.add(ExerciseEntity(
                id = o.optString("id"), nameHe = o.optString("nameHe"), branch = branch, equipment = o.optString("equipment", "body"),
                isCompound = o.optBoolean("isCompound"), unit = o.optString("unit", "kg"), alternativesCsv = o.optString("alternativesCsv"),
                isCustom = true, description = o.optString("description"), target = o.optString("target"), preferred = o.optBoolean("preferred")
            ))
        }
        customs.filter { it.id.isNotEmpty() }.forEach { db.exercises().upsert(it) }

        val sessions = ArrayList<SessionEntity>()
        val sets = ArrayList<SetLogEntity>()
        root.arr("sessions").objects { o ->
            val id = o.optLong("id")
            if (id <= 0L) return@objects
            sessions.add(SessionEntity(
                id = id, dateEpochDay = o.optLong("day"),
                type = runCatching { SessionType.valueOf(o.optString("type")) }.getOrDefault(SessionType.GYM),
                status = runCatching { SessionStatus.valueOf(o.optString("status", "DONE")) }.getOrDefault(SessionStatus.DONE),
                modeId = o.strOrNull("mode"), dailyGoal = o.strOrNull("dailyGoal"),
                startedAtMillis = o.longOrNull("startedAt"), endedAtMillis = o.longOrNull("endedAt"),
                durationSec = o.optInt("durationSec"), xpEarned = o.optInt("xp"), goldEarned = o.optInt("gold"), rating = o.optInt("rating"),
                sleepHours = if (o.has("sleepHours") && !o.isNull("sleepHours")) o.optDouble("sleepHours").toFloat() else null,
                notes = o.optString("notes")
            ))
            o.arr("sets").objects { s ->
                val reps = s.optInt("reps")
                sets.add(SetLogEntity(
                    id = s.optLong("id"), sessionId = id, exerciseId = s.optString("exercise"), setIndex = s.optInt("set"),
                    targetReps = s.optInt("targetReps", reps), targetWeight = s.optDouble("targetWeight", s.optDouble("weight", 0.0)).toFloat(),
                    reps = reps, weight = s.optDouble("weight", 0.0).toFloat(), completed = s.optBoolean("completed", true), isPr = s.optBoolean("pr")
                ))
            }
        }
        if (sessions.isNotEmpty()) db.sessions().upsertSessions(sessions)
        if (sets.isNotEmpty()) db.sessions().upsertSets(sets)

        val weights = ArrayList<WeightEntity>()
        root.arr("weights").objects { o -> weights.add(WeightEntity(dateEpochDay = o.optLong("day"), kg = o.optDouble("kg", 0.0).toFloat())) }
        if (weights.isNotEmpty()) db.weights().upsertAll(weights.filter { it.kg > 0f })

        val meals = ArrayList<MealEntity>()
        root.arr("meals").objects { o ->
            meals.add(MealEntity(id = o.optLong("id"), dateEpochDay = o.optLong("day"), atMillis = o.optLong("atMillis"),
                description = o.optString("description"), kcal = o.optInt("kcal"),
                proteinG = o.optDouble("proteinG", 0.0).toFloat(), carbsG = o.optDouble("carbsG", 0.0).toFloat(),
                fatG = o.optDouble("fatG", 0.0).toFloat(), source = o.optString("source", "manual"),
                aiKcal = o.optInt("aiKcal", 0), itemsJson = o.optString("itemsJson", "")))
        }
        if (meals.isNotEmpty()) db.meals().upsertAll(meals)

        val badges = ArrayList<BadgeEntity>()
        root.arr("badges").objects { o -> badges.add(BadgeEntity(o.optString("id"), o.optLong("at"))) }
        if (badges.isNotEmpty()) db.game().upsertBadges(badges.filter { it.id.isNotEmpty() })

        val modes = ArrayList<ModeUnlockEntity>()
        root.arr("modes").objects { o -> modes.add(ModeUnlockEntity(o.optString("id"), o.optLong("at"))) }
        if (modes.isNotEmpty()) db.game().upsertModes(modes.filter { it.modeId.isNotEmpty() })

        val inventory = ArrayList<InventoryEntity>()
        root.arr("inventory").objects { o -> inventory.add(InventoryEntity(o.optString("id"), o.optLong("at"), o.optBoolean("equipped"))) }
        if (inventory.isNotEmpty()) db.game().upsertItems(inventory.filter { it.itemId.isNotEmpty() })

        val bosses = ArrayList<BossProgressEntity>()
        root.arr("bosses").objects { o ->
            bosses.add(BossProgressEntity(o.optString("id"), o.optLong("week"), o.optInt("hp"), o.optInt("maxHp"), o.optBoolean("defeated")))
        }
        if (bosses.isNotEmpty()) db.game().upsertBosses(bosses.filter { it.bossId.isNotEmpty() })

        val templates = ArrayList<WorkoutTemplateEntity>()
        val items = ArrayList<TemplateItemEntity>()
        root.arr("templates").objects { o ->
            val tid = o.optLong("id")
            if (tid <= 0L) return@objects
            templates.add(WorkoutTemplateEntity(id = tid, nameHe = o.optString("nameHe"), createdAt = o.optLong("createdAt"),
                lastUsedAt = o.optLong("lastUsedAt"), notes = o.optString("notes")))
            o.arr("items").objects { i ->
                items.add(TemplateItemEntity(id = i.optLong("id"), templateId = tid, orderIndex = i.optInt("orderIndex"),
                    exerciseId = i.optString("exercise"), sets = i.optInt("sets", 3), reps = i.optInt("reps", 8),
                    weightKg = i.optDouble("weightKg", 0.0).toFloat(), restSec = i.optInt("restSec", 90), note = i.optString("note"),
                    setsCsv = i.optString("setsCsv")))
            }
        }
        if (templates.isNotEmpty()) db.templates().upsertTemplates(templates)
        if (items.isNotEmpty()) db.templates().upsertItems(items)

        val rewards = ArrayList<RewardEntity>()
        root.arr("rewards").objects { o ->
            rewards.add(RewardEntity(id = o.optLong("id"), nameHe = o.optString("nameHe"), priceGold = o.optInt("priceGold", 100),
                note = o.optString("note"), createdAt = o.optLong("createdAt"), redeemedCount = o.optInt("redeemedCount")))
        }
        if (rewards.isNotEmpty()) db.rewards().upsertAll(rewards.filter { it.id > 0L && it.nameHe.isNotEmpty() })

        val ledger = ArrayList<LedgerEntity>()
        root.arr("ledger").objects { o ->
            ledger.add(LedgerEntity(id = o.optLong("id"), atMillis = o.optLong("at"), xp = o.optInt("xp"), gold = o.optInt("gold"), reason = o.optString("reason")))
        }
        if (ledger.isNotEmpty()) db.game().upsertLedger(ledger.filter { it.id > 0L })

        val measurements = ArrayList<MeasurementEntity>()
        root.arr("measurements").objects { o ->
            measurements.add(MeasurementEntity(id = o.optLong("id"), dateEpochDay = o.optLong("day"),
                waistCm = o.optDouble("waist", 0.0).toFloat(), chestCm = o.optDouble("chest", 0.0).toFloat(),
                armCm = o.optDouble("arm", 0.0).toFloat(), thighCm = o.optDouble("thigh", 0.0).toFloat(), hipsCm = o.optDouble("hips", 0.0).toFloat()))
        }
        if (measurements.isNotEmpty()) db.progress().upsertMeasurements(measurements.filter { it.id > 0L })

        root.optJSONObject("profile")?.let { o ->
            AppGraph.prefs.update { p ->
                p.copy(
                    onboarded = true,
                    heightCm = o.optInt("heightCm", p.heightCm),
                    startWeightKg = o.optDouble("startWeightKg", p.startWeightKg.toDouble()).toFloat(),
                    goalWeightKg = o.optDouble("goalWeightKg", p.goalWeightKg.toDouble()).toFloat(),
                    focus = runCatching { com.tamir.hamasa.data.prefs.Focus.valueOf(o.optString("focus")) }.getOrDefault(p.focus),
                    xp = o.optInt("xp", p.xp), gold = o.optInt("gold", p.gold),
                    streak = o.optInt("streak", p.streak), bestStreak = o.optInt("bestStreak", p.bestStreak),
                    shields = o.optInt("shields", p.shields), lastActiveDay = o.optLong("lastActiveDay", p.lastActiveDay),
                    luckChain = o.optInt("luckChain", p.luckChain), lifetimeKg = o.optDouble("lifetimeKg", p.lifetimeKg),
                    kcalGoal = o.optInt("kcalGoal", p.kcalGoal), monsterPot = o.optInt("monsterPot", p.monsterPot),
                    quietFrom = o.optInt("quietFrom", p.quietFrom), quietTo = o.optInt("quietTo", p.quietTo),
                    playerName = o.optString("playerName", p.playerName),
                    characterVariant = if (o.optString("characterVariant", p.characterVariant) == "F") "F" else "M",
                    userGender = if (o.optString("userGender", p.userGender) == "F") "F" else "M",
                    stageAnchorStage = o.optDouble("stageAnchorStage", p.stageAnchorStage.toDouble()).toFloat(),
                    stageAnchorWeight = o.optDouble("stageAnchorWeight", p.stageAnchorWeight.toDouble()).toFloat()
                )
            }
        }
        return ImportResult.Ok(sessions.size)
    }
}
