package com.tamir.hamasa.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Progress photos and body measurements (db version 6). */
@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress_photos ORDER BY dateEpochDay DESC, id DESC") fun photos(): Flow<List<ProgressPhotoEntity>>
    @Query("SELECT * FROM progress_photos ORDER BY id") suspend fun photosOnce(): List<ProgressPhotoEntity>
    @Query("SELECT COUNT(*) FROM progress_photos") suspend fun photoCount(): Int
    @Query("SELECT COUNT(*) FROM progress_photos WHERE dateEpochDay BETWEEN :from AND :to") suspend fun photosBetween(from: Long, to: Long): Int
    @Insert suspend fun insertPhoto(p: ProgressPhotoEntity): Long
    @Delete suspend fun deletePhoto(p: ProgressPhotoEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPhotos(list: List<ProgressPhotoEntity>)

    @Query("SELECT * FROM measurements ORDER BY dateEpochDay DESC, id DESC") fun measurements(): Flow<List<MeasurementEntity>>
    @Query("SELECT * FROM measurements ORDER BY id") suspend fun measurementsOnce(): List<MeasurementEntity>
    @Insert suspend fun insertMeasurement(m: MeasurementEntity): Long
    @Delete suspend fun deleteMeasurement(m: MeasurementEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMeasurements(list: List<MeasurementEntity>)
}
