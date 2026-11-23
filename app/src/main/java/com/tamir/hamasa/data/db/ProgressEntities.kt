package com.tamir.hamasa.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A monthly progress photo ("תמונת התקדמות"). The JPEG lives in the app files dir (files/photos/),
 * [filePath] is its absolute path. Added in db version 6 (AppDatabase.MIGRATION_5_6) — keep in sync with the SQL there.
 */
@Entity(tableName = "progress_photos")
data class ProgressPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val filePath: String
)

/**
 * Body measurements in centimeters. 0 = not measured this time.
 * Added in db version 6 (AppDatabase.MIGRATION_5_6) — keep in sync with the SQL there.
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val waistCm: Float = 0f,
    val chestCm: Float = 0f,
    val armCm: Float = 0f,
    val thighCm: Float = 0f,
    val hipsCm: Float = 0f
)
