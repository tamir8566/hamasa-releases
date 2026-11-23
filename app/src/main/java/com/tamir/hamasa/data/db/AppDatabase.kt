package com.tamir.hamasa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ExerciseEntity::class, SessionEntity::class, SetLogEntity::class, WeightEntity::class,
        BadgeEntity::class, ModeUnlockEntity::class, InventoryEntity::class, LedgerEntity::class,
        MealEntity::class, BossProgressEntity::class, RewardEntity::class,
        WorkoutTemplateEntity::class, TemplateItemEntity::class,
        ProgressPhotoEntity::class, MeasurementEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exercises(): ExerciseDao
    abstract fun sessions(): SessionDao
    abstract fun weights(): WeightDao
    abstract fun game(): GameDao
    abstract fun meals(): MealDao
    abstract fun rewards(): RewardDao
    abstract fun templates(): TemplateDao
    abstract fun progress(): ProgressDao

    companion object {
        /** v1 -> v2: custom exercise columns on `exercises`. Sessions and sets are untouched. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN isCustom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercises ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exercises ADD COLUMN target TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exercises ADD COLUMN preferred INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3: shop rewards table. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `rewards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameHe` TEXT NOT NULL, `priceGold` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `redeemedCount` INTEGER NOT NULL)")
            }
        }

        /** v3 -> v4: user-built workout templates ("האימונים שלי"). Must match TemplateEntities.kt exactly. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `workout_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameHe` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `template_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `restSec` INTEGER NOT NULL, `note` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_template_items_templateId` ON `template_items` (`templateId`)")
            }
        }

        /** v4 -> v5: per-set targets on template items. Default must match @ColumnInfo(defaultValue = "") in TemplateEntities.kt. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE template_items ADD COLUMN setsCsv TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 -> v6: progress photos and body measurements. Must match ProgressEntities.kt exactly. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `progress_photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `filePath` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `measurements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `waistCm` REAL NOT NULL, `chestCm` REAL NOT NULL, `armCm` REAL NOT NULL, `thighCm` REAL NOT NULL, `hipsCm` REAL NOT NULL)")
            }
        }

        /**
         * v6 -> v7 (stage 2 F): macros and photo estimate on `meals`. Must match MealEntity in Entities.kt
         * (Float -> REAL, Int -> INTEGER, String -> TEXT, all NOT NULL; no @ColumnInfo defaults on the entity).
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN proteinG REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN carbsG REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN fatG REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE meals ADD COLUMN aiKcal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN itemsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "hamasa.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // Last resort only: every known version pair has an explicit migration above.
                .fallbackToDestructiveMigration()
                .build()
    }
}
