package com.tamir.hamasa.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises ORDER BY nameHe") fun all(): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercises") suspend fun allOnce(): List<ExerciseEntity>
    @Query("SELECT * FROM exercises WHERE id = :id") suspend fun byId(id: String): ExerciseEntity?
    @Query("SELECT COUNT(*) FROM exercises") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ExerciseEntity>)

    // --- custom exercises (appended) ---
    @Query("SELECT * FROM exercises WHERE isCustom = 1 ORDER BY nameHe") fun customs(): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercises WHERE isCustom = 1 ORDER BY nameHe") suspend fun customsOnce(): List<ExerciseEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: ExerciseEntity)
    @Query("DELETE FROM exercises WHERE id = :id AND isCustom = 1") suspend fun deleteCustom(id: String)
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun byId(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id = :id") fun observe(id: Long): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE dateEpochDay = :day ORDER BY id DESC") fun forDay(day: Long): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE status = 'DONE' ORDER BY dateEpochDay DESC, id DESC") fun doneSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE status = 'DONE' ORDER BY dateEpochDay DESC, id DESC") suspend fun doneSessionsOnce(): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1") fun activeSession(): Flow<SessionEntity?>
    @Query("SELECT COUNT(*) FROM sessions WHERE status = 'DONE'") fun doneCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM sessions WHERE status = 'DONE' AND type = :type") suspend fun doneCountOfType(type: SessionType): Int
    @Query("SELECT COUNT(*) FROM sessions WHERE status='DONE' AND dateEpochDay BETWEEN :from AND :to") suspend fun doneBetween(from: Long, to: Long): Int
    @Query("SELECT DISTINCT modeId FROM sessions WHERE status='DONE' AND modeId IS NOT NULL") suspend fun distinctModesDone(): List<String>

    @Insert suspend fun insertSets(sets: List<SetLogEntity>)
    @Update suspend fun updateSet(s: SetLogEntity)
    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY id") fun setsFor(sessionId: Long): Flow<List<SetLogEntity>>
    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY id") suspend fun setsForOnce(sessionId: Long): List<SetLogEntity>
    @Query("SELECT * FROM set_logs WHERE exerciseId = :exerciseId AND completed = 1 ORDER BY id DESC LIMIT :limit") suspend fun recentSetsFor(exerciseId: String, limit: Int): List<SetLogEntity>
    @Query("SELECT MAX(weight) FROM set_logs WHERE exerciseId = :exerciseId AND completed = 1 AND reps >= :minReps") suspend fun bestWeight(exerciseId: String, minReps: Int): Float?
    /** Best earlier weight for [minReps]+ reps, ignoring the current session (a record needs something to beat). */
    @Query("SELECT MAX(weight) FROM set_logs WHERE exerciseId = :exerciseId AND completed = 1 AND reps >= :minReps AND sessionId != :sessionId") suspend fun bestWeightBefore(exerciseId: String, minReps: Int, sessionId: Long): Float?
    /** Completed sets of this exercise in OTHER sessions (0 = first time ever). */
    @Query("SELECT COUNT(*) FROM set_logs WHERE exerciseId = :exerciseId AND completed = 1 AND sessionId != :sessionId") suspend fun doneSetsBefore(exerciseId: String, sessionId: Long): Int
    @Query("SELECT COALESCE(SUM(weight*reps),0) FROM set_logs WHERE completed = 1") fun totalVolume(): Flow<Double>
    @Query("SELECT COALESCE(SUM(weight*reps),0) FROM set_logs WHERE completed = 1 AND sessionId = :sessionId") suspend fun sessionVolume(sessionId: Long): Double
    @Query("SELECT COUNT(*) FROM set_logs WHERE isPr = 1") suspend fun prCount(): Int

    // --- cloud sync (appended) ---
    @Query("SELECT COUNT(*) FROM sessions") suspend fun countAll(): Int
    @Query("SELECT * FROM sessions ORDER BY id") suspend fun allSessionsOnce(): List<SessionEntity>
    @Query("SELECT * FROM set_logs ORDER BY id") suspend fun allSetsOnce(): List<SetLogEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSessions(list: List<SessionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSets(list: List<SetLogEntity>)

    // --- session detail (appended) ---
    /** Best estimated one-rep max (Epley: weight * (1 + reps / 30)) of an exercise over all completed weighted sets. */
    @Query("SELECT MAX(weight * (1.0 + reps / 30.0)) FROM set_logs WHERE exerciseId = :exerciseId AND completed = 1 AND weight > 0 AND reps >= 1")
    suspend fun bestEpley(exerciseId: String): Double?

    // --- duration edit (appended) ---
    /** Edited workout length: new duration and end stamp (startedAt + duration). Rewards are not touched. */
    @Query("UPDATE sessions SET durationSec = :durationSec, endedAtMillis = :endedAtMillis WHERE id = :id")
    suspend fun updateDuration(id: Long, durationSec: Int, endedAtMillis: Long?)

    // --- one running workout at a time (appended) ---
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC") suspend fun activeSessionsOnce(): List<SessionEntity>
    @Query("SELECT * FROM set_logs WHERE id = :id") suspend fun setById(id: Long): SetLogEntity?
}

@Dao
interface WeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(w: WeightEntity)
    @Query("SELECT * FROM weights ORDER BY dateEpochDay DESC LIMIT 1") fun latest(): Flow<WeightEntity?>
    @Query("SELECT * FROM weights ORDER BY dateEpochDay DESC LIMIT 1") suspend fun latestOnce(): WeightEntity?
    @Query("SELECT * FROM weights ORDER BY dateEpochDay ASC") fun all(): Flow<List<WeightEntity>>

    // --- cloud sync (appended) ---
    @Query("SELECT * FROM weights ORDER BY dateEpochDay ASC") suspend fun allOnce(): List<WeightEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<WeightEntity>)

    // --- session detail (appended) ---
    @Query("SELECT * FROM weights WHERE dateEpochDay <= :day ORDER BY dateEpochDay DESC LIMIT 1") suspend fun latestOnOrBefore(day: Long): WeightEntity?
}

@Dao
interface GameDao {
    @Query("SELECT * FROM badges") fun badges(): Flow<List<BadgeEntity>>
    @Query("SELECT * FROM badges") suspend fun badgesOnce(): List<BadgeEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun unlockBadge(b: BadgeEntity): Long
    @Query("DELETE FROM badges WHERE id IN (:ids)") suspend fun deleteBadges(ids: List<String>)
    @Query("DELETE FROM inventory WHERE itemId = :itemId") suspend fun deleteItem(itemId: String)

    @Query("SELECT * FROM unlocked_modes") fun modes(): Flow<List<ModeUnlockEntity>>
    @Query("SELECT * FROM unlocked_modes") suspend fun modesOnce(): List<ModeUnlockEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun unlockMode(m: ModeUnlockEntity): Long

    @Query("SELECT * FROM inventory") fun inventory(): Flow<List<InventoryEntity>>
    @Query("SELECT * FROM inventory") suspend fun inventoryOnce(): List<InventoryEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addItem(i: InventoryEntity): Long
    @Update suspend fun updateItem(i: InventoryEntity)
    @Query("UPDATE inventory SET equipped = 0 WHERE itemId IN (:ids)") suspend fun unequip(ids: List<String>)

    @Insert suspend fun ledger(l: LedgerEntity)
    @Query("SELECT * FROM ledger ORDER BY id DESC LIMIT :limit") fun recentLedger(limit: Int): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM boss_progress WHERE weekEpoch = :week LIMIT 1") fun bossForWeek(week: Long): Flow<BossProgressEntity?>
    @Query("SELECT * FROM boss_progress WHERE weekEpoch = :week LIMIT 1") suspend fun bossForWeekOnce(week: Long): BossProgressEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBoss(b: BossProgressEntity)
    @Query("SELECT COUNT(*) FROM boss_progress WHERE defeated = 1") suspend fun bossesDefeated(): Int

    // --- cloud sync (appended) ---
    @Query("SELECT * FROM boss_progress") suspend fun bossesOnce(): List<BossProgressEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBadges(list: List<BadgeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertModes(list: List<ModeUnlockEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItems(list: List<InventoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBosses(list: List<BossProgressEntity>)

    // --- export / reward journal (appended, stage 1 D) ---
    @Query("SELECT * FROM ledger ORDER BY id") suspend fun ledgerOnce(): List<LedgerEntity>
    @Query("SELECT * FROM ledger WHERE reason LIKE 'reward:%' ORDER BY atMillis DESC, id DESC") fun rewardLedger(): Flow<List<LedgerEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLedger(list: List<LedgerEntity>)
}

@Dao
interface MealDao {
    @Insert suspend fun insert(m: MealEntity): Long
    @Query("SELECT * FROM meals WHERE dateEpochDay = :day ORDER BY atMillis") fun forDay(day: Long): Flow<List<MealEntity>>
    @Query("SELECT COUNT(DISTINCT dateEpochDay) FROM meals") suspend fun daysLogged(): Int

    // --- cloud sync (appended) ---
    @Query("SELECT * FROM meals ORDER BY id") suspend fun allOnce(): List<MealEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<MealEntity>)

    // --- edit / delete (appended, stage 1 D) ---
    @Update suspend fun update(m: MealEntity)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteById(id: Long)

    // --- stage 2 F: day close checks (photo bonus, calorie hunt) ---
    @Query("SELECT * FROM meals WHERE dateEpochDay = :day ORDER BY atMillis") suspend fun forDayOnce(day: Long): List<MealEntity>
}
