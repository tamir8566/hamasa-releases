package com.tamir.hamasa.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RewardDao {
    @Query("SELECT * FROM rewards ORDER BY priceGold ASC, id ASC") fun rewards(): Flow<List<RewardEntity>>
    @Query("SELECT * FROM rewards ORDER BY id") suspend fun allOnce(): List<RewardEntity>
    @Query("SELECT COUNT(*) FROM rewards") suspend fun count(): Int
    @Query("SELECT * FROM rewards WHERE id = :id LIMIT 1") suspend fun byId(id: Long): RewardEntity?
    @Insert suspend fun insert(r: RewardEntity): Long
    @Update suspend fun update(r: RewardEntity)
    @Delete suspend fun delete(r: RewardEntity)

    // --- cloud sync ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<RewardEntity>)
}
