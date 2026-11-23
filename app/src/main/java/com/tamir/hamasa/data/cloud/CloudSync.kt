package com.tamir.hamasa.data.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.BadgeEntity
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.BossProgressEntity
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.InventoryEntity
import com.tamir.hamasa.data.db.LedgerEntity
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.db.MeasurementEntity
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.db.ModeUnlockEntity
import com.tamir.hamasa.data.db.RewardEntity
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionStatus
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.data.db.SetLogEntity
import com.tamir.hamasa.data.db.WeightEntity
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.data.prefs.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cloud backup / restore against Supabase (PostgREST). Many users, each with many devices: every request carries
 * the user's recovery code (header x-profile-code, see [Account]) and every row a device_id. A fresh install
 * restores from its own device_id or, failing that, from the device with the most sessions, and then ADOPTS that
 * device_id so later pushes land on the same rows.
 *
 * Multi-user (docs/multiuser.sql): once the server has the profile_code column ([serverSeparatesUsers]), every
 * pushed row also carries profile_code and every pull filters on it (defense in depth on top of the RLS policies).
 * Before that SQL runs pushes work as in the single-user days (the header is ignored), but [pullAll] refuses:
 * a cloud that cannot tell users apart could hand a fresh install another user's data.
 */
object CloudSync {
    private const val TAG = "CloudSync"
    const val PREFS = "cloud"
    const val KEY_DEVICE = "device_id"
    const val KEY_LAST_PUSH = "last_push"
    const val KEY_LAST_PULL = "last_pull"
    private const val WORK_PERIODIC = "cloud_sync"
    private const val WORK_ONCE = "cloud_push_once"

    // ---------------------------------------------------------------- identity / status

    fun deviceId(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE, fresh).apply()
        return fresh
    }

    private fun setDeviceId(context: Context, id: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEVICE, id).apply()
    }

    /** Restore from another code: drop this install's device_id and sync times; the next pull adopts the cloud's. */
    fun forgetDevice(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DEVICE).remove(KEY_LAST_PUSH).remove(KEY_LAST_PULL).commit()
    }

    // ---------------------------------------------------------------- multi-user (docs/multiuser.sql)

    @Volatile private var separatesUsers: Boolean = false
    @Volatile private var separatesCheckedAt: Long = 0L

    /**
     * True when docs/multiuser.sql has run (the profile table has the profile_code column); false when it has not;
     * null when the cloud did not answer. "true" is remembered for the life of the process, "false" for 5 minutes.
     */
    suspend fun serverSeparatesUsers(force: Boolean = false): Boolean? {
        if (separatesUsers) return true
        val now = System.currentTimeMillis()
        if (!force && separatesCheckedAt > 0L && now - separatesCheckedAt < 5L * 60L * 1000L) return false
        val code = SupabaseClient.probe("profile", "select=profile_code&limit=1")
        return when {
            code in 200..299 -> { separatesUsers = true; separatesCheckedAt = now; true }
            code == 400 || code == 404 -> { separatesCheckedAt = now; false } // unknown column (or table)
            else -> null
        }
    }

    /** Does the cloud hold anything for [code]? null = no answer. Asked with that code's header and filter. */
    suspend fun cloudHasData(code: String): Boolean? {
        val f = "profile_code=eq.$code&limit=1"
        val sessions = SupabaseClient.selectOrNull("sessions", "select=device_id&$f", code) ?: return null
        if (sessions.length() > 0) return true
        val profile = SupabaseClient.selectOrNull("profile", "select=device_id&$f", code) ?: return null
        if (profile.length() > 0) return true
        val weights = SupabaseClient.selectOrNull("weights", "select=device_id&$f", code) ?: return null
        return weights.length() > 0
    }

    // ---------------------------------------------------------------- restore marker

    const val KEY_RESTORE_INCOMPLETE = "restore_incomplete"

    /**
     * True between the start of a restore that replaced the local data and the moment a fresh [pullAll] completed.
     * While set: pushes are blocked (a half-downloaded copy must never overwrite the cloud), the next pull runs the
     * fresh path, and Account.restoreFromCode does not take its "same code, fill missing" shortcut.
     */
    fun restoreIncomplete(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RESTORE_INCOMPLETE, false)

    fun setRestoreIncomplete(context: Context, value: Boolean) {
        // commit (not apply): must survive a process kill right after the wipe
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RESTORE_INCOMPLETE, value).commit()
    }

    /** Cancels a queued one-time push (restore is about to replace the local data). */
    fun cancelPendingPush(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_ONCE) }
    }

    /** profile_code for pushed rows; null while the server does not have the column yet (set at the start of pushAll). */
    @Volatile private var pushCode: String? = null

    /** Millis of the last successful full push, or 0. */
    fun lastPush(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_PUSH, 0L)

    private fun markPushed(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_PUSH, System.currentTimeMillis()).apply()
    }

    private fun markPulled(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()
    }

    // ---------------------------------------------------------------- push

    /** Pushes every table + the profile. Returns true only if every batch succeeded. */
    suspend fun pushAll(context: Context): Boolean {
        if (Account.restoring) {
            Log.i(TAG, "pushAll skipped: restore from code in progress")
            return false
        }
        if (restoreIncomplete(context)) {
            Log.i(TAG, "pushAll skipped: the last restore did not complete (local data is a partial copy)")
            return false
        }
        val db = AppGraph.db
        val dev = deviceId(context)
        val now = Instant.now().toString()
        val code = Account.profileCode(context)
        // Rows carry profile_code only once the server has the column (otherwise PostgREST rejects the unknown field).
        val multi = serverSeparatesUsers() == true
        pushCode = if (multi) code else null
        var ok = true
        try {
            ok = pushRows("sessions", db.sessions().allSessionsOnce().map { sessionJson(it, dev, now) }) && ok
            ok = pushRows("set_logs", db.sessions().allSetsOnce().map { setJson(it, dev, now) }) && ok
            ok = pushRows("weights", db.weights().allOnce().map { weightJson(it, dev, now) }) && ok
            ok = pushRows("meals", db.meals().allOnce().map { mealJson(it, dev, now) }) && ok
            ok = pushRows("badges", db.game().badgesOnce().map { badgeJson(it, dev, now) }) && ok
            ok = pushRows("unlocked_modes", db.game().modesOnce().map { modeJson(it, dev, now) }) && ok
            ok = pushRows("inventory", db.game().inventoryOnce().map { itemJson(it, dev, now) }) && ok
            ok = pushRows("boss_progress", db.game().bossesOnce().map { bossJson(it, dev, now) }) && ok
            // Non-fatal: the `rewards` table is newer than the others (docs/shop.sql); a missing table must not block the backup.
            if (!pushRows("rewards", db.rewards().allOnce().map { rewardJson(it, dev, now) })) Log.w(TAG, "rewards push failed (table missing? see docs/shop.sql)")
            // Gold / XP history: best effort like rewards (docs/stage1.sql creates the table). Backup only, never restored.
            if (!pushRows("ledger", db.game().ledgerOnce().map { ledgerJson(it, dev, now) })) Log.w(TAG, "ledger push failed (table missing? see docs/stage1.sql)")
            // Stage 1 D: templates, their items and body measurements. Best effort (docs/stage1.sql creates the tables).
            if (!pushRows("templates", db.templates().templatesOnce().map { templateJson(it, dev, now) })) Log.w(TAG, "templates push failed (table missing? see docs/stage1.sql)")
            if (!pushRows("template_items", db.templates().allItemsOnce().map { templateItemJson(it, dev, now) })) Log.w(TAG, "template_items push failed (table missing? see docs/stage1.sql)")
            if (!pushRows("measurements", db.progress().measurementsOnce().map { measurementJson(it, dev, now) })) Log.w(TAG, "measurements push failed (table missing? see docs/stage1.sql)")
            // Custom exercises: best effort, never fails the backup (the table may not exist yet, see docs/custom_exercises.sql).
            if (!pushRows("custom_exercises", db.exercises().customsOnce().map { customExerciseJson(it, dev, now) })) {
                Log.w(TAG, "custom_exercises push failed (table missing?)")
            }
            val profile = AppGraph.prefs.current()
            val profileRow = JSONObject().apply {
                put("device_id", dev)
                put("data", profileJson(profile))
                put("updated_at", now)
                if (multi) put("profile_code", code)
            }
            ok = SupabaseClient.upsert("profile", JSONArray().put(profileRow)) && ok
            if (multi) {
                // One row per user (docs/multiuser.sql "profiles"): who exists and when they last synced. Best effort.
                val who = JSONObject().apply {
                    put("profile_code", code)
                    put("last_seen", now)
                    put("app_build", com.tamir.hamasa.BuildConfig.VERSION_CODE)
                }
                if (!SupabaseClient.upsert("profiles", JSONArray().put(who))) Log.w(TAG, "profiles push failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushAll error", e)
            ok = false
        }
        if (ok) markPushed(context)
        Log.i(TAG, "pushAll ok=$ok device=$dev")
        return ok
    }

    suspend fun pushIncremental(context: Context): Boolean = pushAll(context)

    private suspend fun pushRows(table: String, rows: List<JSONObject>): Boolean {
        if (rows.isEmpty()) return true
        var ok = true
        val pc = pushCode
        rows.chunked(CloudConfig.BATCH).forEach { chunk ->
            val arr = JSONArray()
            chunk.forEach { row ->
                if (pc != null) row.put("profile_code", pc)
                arr.put(row)
            }
            if (!SupabaseClient.upsert(table, arr)) ok = false
        }
        return ok
    }

    // ---------------------------------------------------------------- pull

    /** Thrown inside [pullAll] when a required table could not be read (network dropped, HTTP error). */
    private class PullFailed(table: String) : Exception("select $table failed")

    /**
     * Restores from the cloud.
     * - Fresh install (no local sessions): the profile is pulled FIRST and the cloud device_id adopted, then every
     *   table is inserted. Counts as success only if every required table was read and the profile restored;
     *   otherwise the "restore incomplete" marker ([KEY_RESTORE_INCOMPLETE]) stays set, pushes are blocked, and the
     *   next pull / restore from code runs the fresh path again.
     * - Otherwise, only when [fillMissing] is true: rows absent locally are added (sessions/sets/meals are
     *   skipped because their auto-generated ids could collide); the profile is left untouched.
     * - [forceFresh] (Account.restoreFromCode, after it cleared the local data): treated as a fresh install.
     * Refuses (returns false) unless the server separates users ([serverSeparatesUsers]), so a fresh install can
     * never pull another user's rows.
     * Fresh path: returns true when the restore completed. Fill-missing path: true if anything was restored.
     */
    suspend fun pullAll(context: Context, fillMissing: Boolean = false, forceFresh: Boolean = false): Boolean {
        // Without docs/multiuser.sql the cloud cannot tell users apart: pulling could bring another user's data.
        if (serverSeparatesUsers() != true) {
            Log.i(TAG, "pullAll refused: server does not separate users (or did not answer)")
            return false
        }
        val db = AppGraph.db
        val incomplete = restoreIncomplete(context)
        // A device counts as "fresh" only if it has no sessions AND onboarding was never completed here.
        val localProfile = AppGraph.prefs.current()
        val fresh = forceFresh || incomplete ||
            (try { db.sessions().countAll() == 0 && !localProfile.onboarded } catch (e: Exception) { false })
        if (!fresh && !fillMissing) {
            Log.i(TAG, "pullAll skipped: local data exists")
            return false
        }
        val myId = deviceId(context)
        // Every query is also filtered by this user's code (the RLS policies do it anyway).
        val codeFilter = "profile_code=eq.${Account.profileCode(context)}&"
        val chosen: String? = try {
            chooseSourceDevice(myId, codeFilter)
        } catch (e: Exception) {
            Log.w(TAG, "pullAll: could not choose a source device", e)
            return false
        }
        if (chosen == null) {
            Log.i(TAG, "pullAll: nothing in cloud")
            return false
        }
        val source: String = chosen
        val q = "${codeFilter}device_id=eq.$source"
        var restored = 0
        var complete = false
        try {
            if (fresh) {
                // Profile FIRST, then adopt the device id: a drop later in the download never loses the profile.
                val prof = SupabaseClient.selectOrNull("profile", "$q&select=*") ?: throw PullFailed("profile")
                if (prof.length() > 0) {
                    val data = prof.getJSONObject(0).optJSONObject("data")
                    if (data != null) {
                        AppGraph.prefs.update { profileFrom(data, it) }
                        runCatching { com.tamir.hamasa.data.health.WatchStore.restoreFromCloud(data) }
                        runCatching { com.tamir.hamasa.data.ai.ChefStore.restoreFromCloud(data) }
                        runCatching { com.tamir.hamasa.data.food.FoodBaseStore.restoreFromCloud(data) }
                        restored += 1
                    }
                }
                if (source != myId) setDeviceId(context, source)

                val sessions = selectAll("sessions", q, strict = true).map { sessionFrom(it) }
                val sets = selectAll("set_logs", q, strict = true).map { setFrom(it) }
                // meals deleted in the app stay in the cloud with an older updated_at (stage 1 D)
                val meals = latestPushOnly(selectAll("meals", q, strict = true)).map { mealFrom(it) }
                // rewards have auto-generated ids too, so they are restored only on a fresh install (best effort: newer table)
                val rewards = selectAll("rewards", q).map { rewardFrom(it) }
                if (sessions.isNotEmpty()) db.sessions().upsertSessions(sessions)
                if (sets.isNotEmpty()) db.sessions().upsertSets(sets)
                if (meals.isNotEmpty()) db.meals().upsertAll(meals)
                if (rewards.isNotEmpty()) {
                    db.rewards().upsertAll(rewards)
                    // the cloud list replaces the default seed; never seed on top of it
                    context.applicationContext.getSharedPreferences("shop", Context.MODE_PRIVATE).edit().putBoolean("seeded", true).apply()
                }
                restored += sessions.size + sets.size + meals.size + rewards.size
                // Stage 1 D, best effort (auto-generated ids too, so fresh install only; a missing table yields nothing).
                // Rows deleted in the app stay in the cloud with an older updated_at; keep only the last push.
                val templates = runCatching { latestPushOnly(selectAll("templates", q)).map { templateFrom(it) } }.getOrDefault(emptyList()).filter { it.id > 0L }
                val templateItems = runCatching { latestPushOnly(selectAll("template_items", q)).map { templateItemFrom(it) } }.getOrDefault(emptyList()).filter { it.id > 0L }
                val measurements = runCatching { latestPushOnly(selectAll("measurements", q)).map { measurementFrom(it) } }.getOrDefault(emptyList()).filter { it.id > 0L }
                if (templates.isNotEmpty()) db.templates().upsertTemplates(templates)
                if (templateItems.isNotEmpty()) db.templates().upsertItems(templateItems)
                if (measurements.isNotEmpty()) db.progress().upsertMeasurements(measurements)
                restored += templates.size + templateItems.size + measurements.size
            }

            // required tables are strict only on the fresh path (fill-missing keeps its old best-effort behavior)
            val localWeightDays = db.weights().allOnce().map { it.dateEpochDay }.toSet()
            val weights = selectAll("weights", q, strict = fresh).map { weightFrom(it) }.filter { fresh || it.dateEpochDay !in localWeightDays }
            if (weights.isNotEmpty()) db.weights().upsertAll(weights)
            restored += weights.size

            val localBadges = db.game().badgesOnce().map { it.id }.toSet()
            val badges = selectAll("badges", q, strict = fresh).map { badgeFrom(it) }.filter { fresh || it.id !in localBadges }
            if (badges.isNotEmpty()) db.game().upsertBadges(badges)
            restored += badges.size

            val localModes = db.game().modesOnce().map { it.modeId }.toSet()
            val modes = selectAll("unlocked_modes", q, strict = fresh).map { modeFrom(it) }.filter { fresh || it.modeId !in localModes }
            if (modes.isNotEmpty()) db.game().upsertModes(modes)
            restored += modes.size

            val localItems = db.game().inventoryOnce().map { it.itemId }.toSet()
            val items = selectAll("inventory", q, strict = fresh).map { itemFrom(it) }.filter { fresh || it.itemId !in localItems }
            if (items.isNotEmpty()) db.game().upsertItems(items)
            restored += items.size

            val localBosses = db.game().bossesOnce().map { it.bossId }.toSet()
            val bosses = selectAll("boss_progress", q, strict = fresh).map { bossFrom(it) }.filter { fresh || it.bossId !in localBosses }
            if (bosses.isNotEmpty()) db.game().upsertBosses(bosses)
            restored += bosses.size

            // best effort: the table may not exist yet (docs/custom_exercises.sql)
            val localCustom = db.exercises().customsOnce().map { it.id }.toSet()
            val customs = selectAll("custom_exercises", q).map { customExerciseFrom(it) }.filter { it.id.isNotBlank() && (fresh || it.id !in localCustom) }
            if (customs.isNotEmpty()) db.exercises().upsertAll(customs)
            restored += customs.size

            complete = true
        } catch (e: Exception) {
            Log.w(TAG, "pullAll error", e)
        }
        if (fresh && complete) setRestoreIncomplete(context, false)
        if (restored > 0) markPulled(context)
        Log.i(TAG, "pullAll restored=$restored source=$source fresh=$fresh complete=$complete")
        return if (fresh) complete else restored > 0
    }

    /** Own device if it has sessions in the cloud; otherwise the device with the most sessions; null if cloud is empty. Throws if the cloud did not answer. */
    private suspend fun chooseSourceDevice(myId: String, codeFilter: String): String? {
        val rows = selectAll("sessions", "${codeFilter}select=device_id", strict = true)
        val counts = HashMap<String, Int>()
        for (i in 0 until rows.size) {
            val d = rows[i].optString("device_id", "")
            if (d.isNotBlank()) counts[d] = (counts[d] ?: 0) + 1
        }
        if ((counts[myId] ?: 0) > 0) return myId
        val best = counts.maxByOrNull { it.value }?.key
        if (best != null) return best
        // No sessions anywhere: fall back to any device that has a profile row (e.g. only weights were logged).
        val profiles = SupabaseClient.selectOrNull("profile", "${codeFilter}select=device_id") ?: throw PullFailed("profile")
        for (i in 0 until profiles.length()) {
            val d = profiles.getJSONObject(i).optString("device_id", "")
            if (d == myId) return myId
        }
        return if (profiles.length() > 0) profiles.getJSONObject(0).optString("device_id", "").ifBlank { null } else null
    }

    /**
     * Every push writes all current rows of a table with the same updated_at, so rows with an older value
     * were deleted in the app since. Keeps only the rows of the most recent push.
     */
    private fun latestPushOnly(rows: List<JSONObject>): List<JSONObject> {
        val latest = rows.maxOfOrNull { it.optString("updated_at", "") } ?: return rows
        return rows.filter { it.optString("updated_at", "") == latest }
    }

    /**
     * Pages through PostgREST results (default max-rows is 1000). A failed page reads as empty, unless [strict]:
     * then it throws [PullFailed] so a dropped download is never mistaken for "no rows".
     */
    private suspend fun selectAll(table: String, query: String, strict: Boolean = false): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var offset = 0
        while (true) {
            val base = if (query.contains("select=")) query else "$query&select=*"
            val url = "$base&limit=${CloudConfig.PAGE}&offset=$offset"
            val page = if (strict) {
                SupabaseClient.selectOrNull(table, url) ?: throw PullFailed(table)
            } else {
                SupabaseClient.select(table, url)
            }
            for (i in 0 until page.length()) out.add(page.getJSONObject(i))
            if (page.length() < CloudConfig.PAGE) break
            offset += CloudConfig.PAGE
        }
        return out
    }

    // ---------------------------------------------------------------- scheduling

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic push every 6 hours (network required). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<CloudSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** One-time push as soon as network is available (e.g. right after finishSession / logWeight). */
    fun pushSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    // ---------------------------------------------------------------- entity -> json

    private fun sessionJson(s: SessionEntity, dev: String, now: String) = JSONObject().apply {
        put("id", s.id); put("device_id", dev)
        put("date_epoch_day", s.dateEpochDay); put("type", s.type.name); put("status", s.status.name)
        put("mode_id", s.modeId ?: JSONObject.NULL); put("daily_goal", s.dailyGoal ?: JSONObject.NULL)
        put("started_at", s.startedAtMillis ?: JSONObject.NULL); put("ended_at", s.endedAtMillis ?: JSONObject.NULL)
        put("duration_sec", s.durationSec); put("xp", s.xpEarned); put("gold", s.goldEarned); put("rating", s.rating)
        put("sleep_hours", s.sleepHours?.toDouble() ?: JSONObject.NULL); put("notes", s.notes)
        put("updated_at", now)
    }

    private fun setJson(s: SetLogEntity, dev: String, now: String) = JSONObject().apply {
        put("id", s.id); put("device_id", dev); put("session_id", s.sessionId); put("exercise_id", s.exerciseId)
        put("set_index", s.setIndex); put("target_reps", s.targetReps); put("target_weight", s.targetWeight.toDouble())
        put("reps", s.reps); put("weight", s.weight.toDouble()); put("completed", s.completed); put("is_pr", s.isPr)
        put("updated_at", now)
    }

    private fun weightJson(w: WeightEntity, dev: String, now: String) = JSONObject().apply {
        put("date_epoch_day", w.dateEpochDay); put("device_id", dev); put("kg", w.kg.toDouble()); put("updated_at", now)
    }

    private fun mealJson(m: MealEntity, dev: String, now: String) = JSONObject().apply {
        put("id", m.id); put("device_id", dev); put("date_epoch_day", m.dateEpochDay); put("at_millis", m.atMillis)
        put("description", m.description); put("kcal", m.kcal); put("updated_at", now)
        // stage 2 F (docs/stage2.sql)
        put("protein_g", m.proteinG.toDouble()); put("carbs_g", m.carbsG.toDouble()); put("fat_g", m.fatG.toDouble())
        put("source", m.source); put("ai_kcal", m.aiKcal); put("items_json", m.itemsJson)
    }

    private fun badgeJson(b: BadgeEntity, dev: String, now: String) = JSONObject().apply {
        put("id", b.id); put("device_id", dev); put("unlocked_at", b.unlockedAtMillis); put("updated_at", now)
    }

    private fun modeJson(m: ModeUnlockEntity, dev: String, now: String) = JSONObject().apply {
        put("mode_id", m.modeId); put("device_id", dev); put("unlocked_at", m.unlockedAtMillis); put("updated_at", now)
    }

    private fun itemJson(i: InventoryEntity, dev: String, now: String) = JSONObject().apply {
        put("item_id", i.itemId); put("device_id", dev); put("acquired_at", i.acquiredAtMillis); put("equipped", i.equipped); put("updated_at", now)
    }

    private fun bossJson(b: BossProgressEntity, dev: String, now: String) = JSONObject().apply {
        put("boss_id", b.bossId); put("device_id", dev); put("week_epoch", b.weekEpoch); put("hp", b.hp); put("max_hp", b.maxHp)
        put("defeated", b.defeated); put("updated_at", now)
    }

    private fun customExerciseJson(e: ExerciseEntity, dev: String, now: String) = JSONObject().apply {
        put("id", e.id); put("device_id", dev); put("name_he", e.nameHe); put("branch", e.branch.name)
        put("equipment", e.equipment); put("is_compound", e.isCompound); put("unit", e.unit)
        put("description", e.description); put("target", e.target); put("preferred", e.preferred)
        put("updated_at", now)
    }

    private fun rewardJson(r: RewardEntity, dev: String, now: String) = JSONObject().apply {
        put("id", r.id); put("device_id", dev); put("name_he", r.nameHe); put("price_gold", r.priceGold)
        put("note", r.note); put("created_at", r.createdAt); put("redeemed_count", r.redeemedCount); put("updated_at", now)
    }

    private fun templateJson(t: WorkoutTemplateEntity, dev: String, now: String) = JSONObject().apply {
        put("id", t.id); put("device_id", dev); put("name_he", t.nameHe); put("created_at", t.createdAt)
        put("last_used_at", t.lastUsedAt); put("notes", t.notes); put("updated_at", now)
    }

    private fun templateItemJson(i: TemplateItemEntity, dev: String, now: String) = JSONObject().apply {
        put("id", i.id); put("device_id", dev); put("template_id", i.templateId); put("order_index", i.orderIndex)
        put("exercise_id", i.exerciseId); put("sets", i.sets); put("reps", i.reps); put("weight_kg", i.weightKg.toDouble())
        put("rest_sec", i.restSec); put("note", i.note); put("sets_csv", i.setsCsv); put("updated_at", now)
    }

    private fun measurementJson(m: MeasurementEntity, dev: String, now: String) = JSONObject().apply {
        put("id", m.id); put("device_id", dev); put("date_epoch_day", m.dateEpochDay)
        put("waist_cm", m.waistCm.toDouble()); put("chest_cm", m.chestCm.toDouble()); put("arm_cm", m.armCm.toDouble())
        put("thigh_cm", m.thighCm.toDouble()); put("hips_cm", m.hipsCm.toDouble()); put("updated_at", now)
    }

    private fun ledgerJson(l: LedgerEntity, dev: String, now: String) = JSONObject().apply {
        put("id", l.id); put("device_id", dev); put("at_millis", l.atMillis); put("xp", l.xp); put("gold", l.gold)
        put("reason", l.reason); put("updated_at", now)
    }

    private fun profileJson(p: Profile) = JSONObject().apply {
        put("onboarded", p.onboarded); put("heightCm", p.heightCm)
        put("startWeightKg", p.startWeightKg.toDouble()); put("goalWeightKg", p.goalWeightKg.toDouble())
        put("focus", p.focus.name); put("xp", p.xp); put("gold", p.gold); put("streak", p.streak); put("bestStreak", p.bestStreak)
        put("shields", p.shields); put("lastActiveDay", p.lastActiveDay); put("lastRollDay", p.lastRollDay)
        put("rollModeId", p.rollModeId); put("rollGoal", p.rollGoal); put("rollBonus", p.rollBonus)
        put("plannedTomorrowType", p.plannedTomorrowType); put("plannedTomorrowHour", p.plannedTomorrowHour)
        put("quietFrom", p.quietFrom); put("quietTo", p.quietTo); put("luckChain", p.luckChain)
        put("lifetimeKg", p.lifetimeKg); put("kcalGoal", p.kcalGoal)
        // stage 1 C
        put("restBrokenSessionId", p.restBrokenSessionId); put("hardBattleWeek", p.hardBattleWeek)
        put("sealedChests", p.sealedChests); put("chestsOpened", p.chestsOpened); put("battleLog", p.battleLog)
        put("mapBonusStations", p.mapBonusStations); put("mapStartDone", p.mapStartDone); put("stabilityShieldMonth", p.stabilityShieldMonth); put("bossesDefeatedTotal", p.bossesDefeatedTotal)
        // stage 1 B
        put("sessionsSinceEvent", p.sessionsSinceEvent); put("eventsAccepted", p.eventsAccepted); put("eventsCompleted", p.eventsCompleted)
        put("level5ShieldGiven", p.level5ShieldGiven); put("jokerPickDay", p.jokerPickDay)
        // stage 1 E
        put("jokers", p.jokers); put("kcalAskedFocus", p.kcalAskedFocus)
        put("branchMaxCsv", p.branchMaxCsv); put("seasonalMonthsCsv", p.seasonalMonthsCsv)
        put("treeStartSessionId", p.treeStartSessionId); put("treeStartDay", p.treeStartDay)
        // stage 2 H
        put("storyLog", p.storyLog)
        put("skillId", p.skillId); put("skillChosenDay", p.skillChosenDay); put("skillUsedDay", p.skillUsedDay)
        put("skillHintBadgeId", p.skillHintBadgeId); put("bossDefeatsCsv", p.bossDefeatsCsv)
        // "מקצוע לשמביולוק"
        put("professionId", p.professionId); put("professionChosenDay", p.professionChosenDay)
        // stage 2 G (the cached Gemini morning line is local only)
        put("coachConstraintsJson", p.coachConstraintsJson); put("coachConstraintsUntil", p.coachConstraintsUntil)
        // coach season: deload week, resting HR, weekly challenge, seasonal event boss
        put("deloadWeekStart", p.deloadWeekStart); put("deloadLastEnd", p.deloadLastEnd); put("deloadSnoozeUntil", p.deloadSnoozeUntil)
        put("restingHrCsv", p.restingHrCsv)
        put("challengeWeek", p.challengeWeek); put("challengeId", p.challengeId)
        put("challengeDoneWeek", p.challengeDoneWeek); put("challengesCompleted", p.challengesCompleted)
        put("eventBossState", p.eventBossState)
        // stage 1 D
        put("betStake", p.betStake); put("betDay", p.betDay); put("monsterPot", p.monsterPot)
        put("soundOn", p.soundOn); put("rewardGoalId", p.rewardGoalId); put("photoNudgeMonth", p.photoNudgeMonth)
        // stage 2 F
        put("proteinGoal", p.proteinGoal); put("carbsGoal", p.carbsGoal); put("fatGoal", p.fatGoal)
        put("lastPhotoDayBonus", p.lastPhotoDayBonus); put("lastCalorieHuntDay", p.lastCalorieHuntDay); put("goodGuessMeals", p.goodGuessMeals)
        put("savedMealsJson", p.savedMealsJson); put("savedMealAskedCsv", p.savedMealAskedCsv)
        // energy calculator
        put("age", p.age); put("sex", p.sex); put("bodyFatPct", p.bodyFatPct.toDouble()); put("activityLevel", p.activityLevel)
        put("goalRateX100", p.goalRateX100); put("kcalAuto", p.kcalAuto); put("useAdaptiveTdee", p.useAdaptiveTdee)
        put("goalDirection", p.goalDirection); put("gainRateX100", p.gainRateX100)
        // coach difficulty and the running coach (runWatchCheckedId stays local: session ids are per device)
        put("coachDifficulty", p.coachDifficulty)
        put("runWeek", p.runWeek); put("runDay", p.runDay); put("runLastDay", p.runLastDay)
        put("runLastSessionId", p.runLastSessionId); put("runLastResult", p.runLastResult)
        // AI provider choice (data/ai/AiClient)
        put("aiProvider", p.aiProvider)
        // "אימון בבית": the equipment he has at home and the last time / focus choice
        put("homeGearCsv", p.homeGearCsv); put("homeDumbbellMaxKg", p.homeDumbbellMaxKg.toDouble())
        put("homeDumbbellFixed", p.homeDumbbellFixed); put("homeKettlebellKg", p.homeKettlebellKg.toDouble()); put("homeMinutes", p.homeMinutes); put("homeFocus", p.homeFocus)
        // onboarding: name, character, body-stage calibration anchors, training days, watch
        put("playerName", p.playerName); put("characterVariant", p.characterVariant); put("userGender", p.userGender)
        put("stageAnchorStage", p.stageAnchorStage.toDouble()); put("stageAnchorWeight", p.stageAnchorWeight.toDouble())
        put("trainingDaysPerWeek", p.trainingDaysPerWeek); put("hasWatch", p.hasWatch)
        // stage 3 watch: daily steps, per-session heart rate, handled Health Connect ids (WatchStore, not Profile)
        runCatching { com.tamir.hamasa.data.health.WatchStore.putCloud(this) }
        // the chef's memory of this user (taste facts, recipe book, "לא בשבילי")
        runCatching { com.tamir.hamasa.data.ai.ChefStore.putCloud(this) }
        // his own rows of the food base (learned foods and hand corrections)
        runCatching { com.tamir.hamasa.data.food.FoodBaseStore.putCloud(this) }
    }

    // ---------------------------------------------------------------- json -> entity

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
    private fun JSONObject.lng(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.flt(key: String): Float? = if (isNull(key)) null else optDouble(key).toFloat()

    private fun sessionFrom(o: JSONObject) = SessionEntity(
        id = o.optLong("id"),
        dateEpochDay = o.optLong("date_epoch_day"),
        type = runCatching { SessionType.valueOf(o.optString("type")) }.getOrDefault(SessionType.GYM),
        status = runCatching { SessionStatus.valueOf(o.optString("status")) }.getOrDefault(SessionStatus.DONE),
        modeId = o.str("mode_id"),
        dailyGoal = o.str("daily_goal"),
        startedAtMillis = o.lng("started_at"),
        endedAtMillis = o.lng("ended_at"),
        durationSec = o.optInt("duration_sec"),
        xpEarned = o.optInt("xp"),
        goldEarned = o.optInt("gold"),
        rating = o.optInt("rating"),
        sleepHours = o.flt("sleep_hours"),
        notes = o.str("notes") ?: ""
    )

    private fun setFrom(o: JSONObject) = SetLogEntity(
        id = o.optLong("id"),
        sessionId = o.optLong("session_id"),
        exerciseId = o.optString("exercise_id"),
        setIndex = o.optInt("set_index"),
        targetReps = o.optInt("target_reps"),
        targetWeight = o.optDouble("target_weight", 0.0).toFloat(),
        reps = o.optInt("reps"),
        weight = o.optDouble("weight", 0.0).toFloat(),
        completed = o.optBoolean("completed"),
        isPr = o.optBoolean("is_pr")
    )

    private fun weightFrom(o: JSONObject) = WeightEntity(
        dateEpochDay = o.optLong("date_epoch_day"),
        kg = o.optDouble("kg", 0.0).toFloat()
    )

    private fun mealFrom(o: JSONObject) = MealEntity(
        id = o.optLong("id"),
        dateEpochDay = o.optLong("date_epoch_day"),
        atMillis = o.optLong("at_millis"),
        description = o.str("description") ?: "",
        kcal = o.optInt("kcal"),
        // stage 2 F: rows pushed before the new columns existed read as manual meals without macros
        proteinG = o.optDouble("protein_g", 0.0).let { if (it.isNaN()) 0f else it.toFloat() },
        carbsG = o.optDouble("carbs_g", 0.0).let { if (it.isNaN()) 0f else it.toFloat() },
        fatG = o.optDouble("fat_g", 0.0).let { if (it.isNaN()) 0f else it.toFloat() },
        source = o.str("source") ?: "manual",
        aiKcal = o.optInt("ai_kcal", 0),
        itemsJson = o.str("items_json") ?: ""
    )

    private fun badgeFrom(o: JSONObject) = BadgeEntity(id = o.optString("id"), unlockedAtMillis = o.optLong("unlocked_at"))
    private fun modeFrom(o: JSONObject) = ModeUnlockEntity(modeId = o.optString("mode_id"), unlockedAtMillis = o.optLong("unlocked_at"))
    private fun itemFrom(o: JSONObject) = InventoryEntity(itemId = o.optString("item_id"), acquiredAtMillis = o.optLong("acquired_at"), equipped = o.optBoolean("equipped"))
    private fun bossFrom(o: JSONObject) = BossProgressEntity(
        bossId = o.optString("boss_id"), weekEpoch = o.optLong("week_epoch"),
        hp = o.optInt("hp"), maxHp = o.optInt("max_hp"), defeated = o.optBoolean("defeated")
    )

    private fun customExerciseFrom(o: JSONObject) = ExerciseEntity(
        id = o.optString("id"),
        nameHe = o.str("name_he") ?: "",
        branch = runCatching { Branch.valueOf(o.optString("branch")) }.getOrDefault(Branch.CORE),
        equipment = o.str("equipment") ?: "machine",
        isCompound = o.optBoolean("is_compound"),
        unit = o.str("unit") ?: "kg",
        alternativesCsv = "",
        isCustom = true,
        description = o.str("description") ?: "",
        target = o.str("target") ?: "",
        preferred = o.optBoolean("preferred")
    )

    private fun templateFrom(o: JSONObject) = WorkoutTemplateEntity(
        id = o.optLong("id"),
        nameHe = o.str("name_he") ?: "",
        createdAt = o.optLong("created_at"),
        lastUsedAt = o.optLong("last_used_at"),
        notes = o.str("notes") ?: ""
    )

    private fun templateItemFrom(o: JSONObject) = TemplateItemEntity(
        id = o.optLong("id"),
        templateId = o.optLong("template_id"),
        orderIndex = o.optInt("order_index"),
        exerciseId = o.optString("exercise_id"),
        sets = o.optInt("sets", 3),
        reps = o.optInt("reps", 8),
        weightKg = o.optDouble("weight_kg", 0.0).toFloat(),
        restSec = o.optInt("rest_sec", 90),
        note = o.str("note") ?: "",
        setsCsv = o.str("sets_csv") ?: ""
    )

    private fun measurementFrom(o: JSONObject) = MeasurementEntity(
        id = o.optLong("id"),
        dateEpochDay = o.optLong("date_epoch_day"),
        waistCm = o.optDouble("waist_cm", 0.0).toFloat(),
        chestCm = o.optDouble("chest_cm", 0.0).toFloat(),
        armCm = o.optDouble("arm_cm", 0.0).toFloat(),
        thighCm = o.optDouble("thigh_cm", 0.0).toFloat(),
        hipsCm = o.optDouble("hips_cm", 0.0).toFloat()
    )

    private fun rewardFrom(o: JSONObject) = RewardEntity(
        id = o.optLong("id"),
        nameHe = o.str("name_he") ?: "",
        priceGold = o.optInt("price_gold"),
        note = o.str("note") ?: "",
        createdAt = o.optLong("created_at"),
        redeemedCount = o.optInt("redeemed_count")
    )

    /** A "start" marker where -1 means unset: the set one wins, and of two set ones the earlier (smaller). */
    private fun mergeMarker(local: Long, remote: Long): Long = when {
        local < 0L -> remote.coerceAtLeast(-1L)
        remote < 0L -> local
        else -> minOf(local, remote)
    }

    private fun profileFrom(d: JSONObject, cur: Profile) = cur.copy(
        // Onboarding is never undone by a restore.
        onboarded = cur.onboarded || d.optBoolean("onboarded", false),
        heightCm = d.optInt("heightCm", cur.heightCm),
        startWeightKg = d.optDouble("startWeightKg", cur.startWeightKg.toDouble()).toFloat(),
        goalWeightKg = d.optDouble("goalWeightKg", cur.goalWeightKg.toDouble()).toFloat(),
        focus = runCatching { Focus.valueOf(d.optString("focus")) }.getOrDefault(cur.focus),
        xp = d.optInt("xp", cur.xp),
        gold = d.optInt("gold", cur.gold),
        streak = d.optInt("streak", cur.streak),
        bestStreak = d.optInt("bestStreak", cur.bestStreak),
        shields = d.optInt("shields", cur.shields),
        lastActiveDay = d.optLong("lastActiveDay", cur.lastActiveDay),
        lastRollDay = d.optLong("lastRollDay", cur.lastRollDay),
        rollModeId = d.optString("rollModeId", cur.rollModeId),
        rollGoal = d.optString("rollGoal", cur.rollGoal),
        rollBonus = d.optString("rollBonus", cur.rollBonus),
        plannedTomorrowType = d.optString("plannedTomorrowType", cur.plannedTomorrowType),
        plannedTomorrowHour = d.optInt("plannedTomorrowHour", cur.plannedTomorrowHour),
        quietFrom = d.optInt("quietFrom", cur.quietFrom),
        quietTo = d.optInt("quietTo", cur.quietTo),
        luckChain = d.optInt("luckChain", cur.luckChain),
        lifetimeKg = d.optDouble("lifetimeKg", cur.lifetimeKg),
        kcalGoal = d.optInt("kcalGoal", cur.kcalGoal),
        // stage 1 C
        restBrokenSessionId = d.optLong("restBrokenSessionId", cur.restBrokenSessionId),
        hardBattleWeek = d.optLong("hardBattleWeek", cur.hardBattleWeek),
        sealedChests = d.optString("sealedChests", cur.sealedChests),
        chestsOpened = d.optInt("chestsOpened", cur.chestsOpened),
        battleLog = d.optString("battleLog", cur.battleLog),
        mapBonusStations = d.optInt("mapBonusStations", cur.mapBonusStations),
        mapStartDone = d.optInt("mapStartDone", cur.mapStartDone),
        stabilityShieldMonth = d.optInt("stabilityShieldMonth", cur.stabilityShieldMonth),
        bossesDefeatedTotal = maxOf(cur.bossesDefeatedTotal, d.optInt("bossesDefeatedTotal", 0)),
        // stage 1 B (per-session event bookkeeping is local only and kept as is)
        sessionsSinceEvent = d.optInt("sessionsSinceEvent", cur.sessionsSinceEvent),
        eventPlanSessionId = cur.eventPlanSessionId,
        eventPlanSetNo = cur.eventPlanSetNo,
        eventsAccepted = d.optInt("eventsAccepted", cur.eventsAccepted),
        eventsCompleted = d.optInt("eventsCompleted", cur.eventsCompleted),
        level5ShieldGiven = cur.level5ShieldGiven || d.optBoolean("level5ShieldGiven", false),
        jokerPickDay = d.optLong("jokerPickDay", cur.jokerPickDay),
        surpriseSwapSessionId = cur.surpriseSwapSessionId,
        // stage 1 E
        jokers = d.optInt("jokers", cur.jokers),
        kcalAskedFocus = d.optString("kcalAskedFocus", cur.kcalAskedFocus),
        branchMaxCsv = d.optString("branchMaxCsv", cur.branchMaxCsv),
        // tree marker: -1 = unset; when both are set the EARLIER start wins (more progress counts, nothing drops)
        treeStartSessionId = mergeMarker(cur.treeStartSessionId, d.optLong("treeStartSessionId", -1L)),
        treeStartDay = mergeMarker(cur.treeStartDay, d.optLong("treeStartDay", -1L)),
        seasonalMonthsCsv = d.optString("seasonalMonthsCsv", cur.seasonalMonthsCsv),
        // stage 2 H
        storyLog = d.optString("storyLog", cur.storyLog),
        skillId = d.optString("skillId", cur.skillId),
        skillChosenDay = d.optLong("skillChosenDay", cur.skillChosenDay),
        skillUsedDay = d.optLong("skillUsedDay", cur.skillUsedDay),
        skillHintBadgeId = d.optString("skillHintBadgeId", cur.skillHintBadgeId),
        bossDefeatsCsv = d.optString("bossDefeatsCsv", cur.bossDefeatsCsv),
        professionId = d.optString("professionId", cur.professionId),
        professionChosenDay = d.optLong("professionChosenDay", cur.professionChosenDay),
        // stage 2 G
        coachConstraintsJson = d.optString("coachConstraintsJson", cur.coachConstraintsJson),
        coachConstraintsUntil = d.optLong("coachConstraintsUntil", cur.coachConstraintsUntil),
        // coach season (counters and "last" days only grow)
        deloadWeekStart = maxOf(cur.deloadWeekStart, d.optLong("deloadWeekStart", 0L)),
        deloadLastEnd = maxOf(cur.deloadLastEnd, d.optLong("deloadLastEnd", 0L)),
        deloadSnoozeUntil = maxOf(cur.deloadSnoozeUntil, d.optLong("deloadSnoozeUntil", 0L)),
        restingHrCsv = if (cur.restingHrCsv.isNotEmpty()) cur.restingHrCsv else d.optString("restingHrCsv", ""),
        challengeWeek = d.optLong("challengeWeek", cur.challengeWeek),
        challengeId = d.optString("challengeId", cur.challengeId),
        challengeDoneWeek = maxOf(cur.challengeDoneWeek, d.optLong("challengeDoneWeek", 0L)),
        challengesCompleted = maxOf(cur.challengesCompleted, d.optInt("challengesCompleted", 0)),
        eventBossState = d.optString("eventBossState", cur.eventBossState),
        // stage 1 D (today's mood is local only)
        betStake = d.optInt("betStake", cur.betStake),
        betDay = d.optLong("betDay", cur.betDay),
        monsterPot = d.optInt("monsterPot", cur.monsterPot),
        mood = cur.mood,
        moodDay = cur.moodDay,
        soundOn = d.optBoolean("soundOn", cur.soundOn),
        rewardGoalId = d.optLong("rewardGoalId", cur.rewardGoalId),
        photoNudgeMonth = d.optInt("photoNudgeMonth", cur.photoNudgeMonth),
        // stage 2 F
        proteinGoal = d.optInt("proteinGoal", cur.proteinGoal),
        carbsGoal = d.optInt("carbsGoal", cur.carbsGoal),
        fatGoal = d.optInt("fatGoal", cur.fatGoal),
        lastPhotoDayBonus = maxOf(cur.lastPhotoDayBonus, d.optLong("lastPhotoDayBonus", 0L)),
        lastCalorieHuntDay = maxOf(cur.lastCalorieHuntDay, d.optLong("lastCalorieHuntDay", 0L)),
        goodGuessMeals = maxOf(cur.goodGuessMeals, d.optInt("goodGuessMeals", 0)),
        savedMealsJson = d.optString("savedMealsJson", cur.savedMealsJson),
        savedMealAskedCsv = d.optString("savedMealAskedCsv", cur.savedMealAskedCsv),
        // energy calculator
        age = d.optInt("age", cur.age),
        sex = d.optString("sex", cur.sex),
        bodyFatPct = d.optDouble("bodyFatPct", cur.bodyFatPct.toDouble()).toFloat(),
        activityLevel = d.optInt("activityLevel", cur.activityLevel),
        goalRateX100 = d.optInt("goalRateX100", cur.goalRateX100),
        kcalAuto = d.optBoolean("kcalAuto", cur.kcalAuto),
        useAdaptiveTdee = d.optBoolean("useAdaptiveTdee", cur.useAdaptiveTdee),
        // coach difficulty and the running coach (the last run day only grows)
        coachDifficulty = d.optString("coachDifficulty", cur.coachDifficulty),
        runWeek = d.optInt("runWeek", cur.runWeek).coerceIn(1, 10),
        runDay = d.optInt("runDay", cur.runDay).coerceIn(1, 3),
        runLastDay = maxOf(cur.runLastDay, d.optLong("runLastDay", 0L)),
        runLastSessionId = d.optLong("runLastSessionId", cur.runLastSessionId),
        runLastResult = d.optString("runLastResult", cur.runLastResult),
        aiProvider = d.optString("aiProvider", cur.aiProvider).let { if (it == "auto" || it == "claude" || it == "gemini") it else cur.aiProvider },
        goalDirection = d.optInt("goalDirection", cur.goalDirection),
        gainRateX100 = d.optInt("gainRateX100", cur.gainRateX100),
        homeGearCsv = d.optString("homeGearCsv", cur.homeGearCsv),
        homeDumbbellMaxKg = d.optDouble("homeDumbbellMaxKg", cur.homeDumbbellMaxKg.toDouble()).toFloat().coerceIn(0f, 100f),
        homeDumbbellFixed = d.optBoolean("homeDumbbellFixed", cur.homeDumbbellFixed),
        homeKettlebellKg = d.optDouble("homeKettlebellKg", cur.homeKettlebellKg.toDouble()).toFloat().coerceIn(0f, 100f),
        homeMinutes = d.optInt("homeMinutes", cur.homeMinutes).coerceIn(0, 120),
        homeFocus = d.optString("homeFocus", cur.homeFocus).let { if (it == "FULL" || it == "UPPER" || it == "LOWER" || it == "CORE") it else cur.homeFocus },
        // onboarding fields (a backup without them keeps the local values)
        playerName = d.optString("playerName", cur.playerName),
        characterVariant = d.optString("characterVariant", cur.characterVariant).let { if (it == "F") "F" else "M" },
        userGender = d.optString("userGender", cur.userGender).let { if (it == "F") "F" else "M" },
        stageAnchorStage = d.optDouble("stageAnchorStage", cur.stageAnchorStage.toDouble()).toFloat().let { if (it.isNaN()) cur.stageAnchorStage else it },
        stageAnchorWeight = d.optDouble("stageAnchorWeight", cur.stageAnchorWeight.toDouble()).toFloat().let { if (it.isNaN()) cur.stageAnchorWeight else it },
        trainingDaysPerWeek = d.optInt("trainingDaysPerWeek", cur.trainingDaysPerWeek),
        hasWatch = d.optBoolean("hasWatch", cur.hasWatch)
    )
}
