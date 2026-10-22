package com.e5lab.accamera.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, MeasurementEntity::class],
    // v3: Project gained baseMapOffsetX/baseMapOffsetY/baseMapRotationDeg (base-map alignment).
    // NOTE: adding/removing an entity field REQUIRES bumping this version, otherwise installed
    // builds crash on launch. fallbackToDestructiveMigration() wipes data, so migration is done
    // in place with ADD COLUMN defaults below.
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 -> v3: adds the three base-map alignment columns. Defaults (0) reproduce the previous
         * behaviour exactly, so existing surveys keep their layout untouched.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN baseMapOffsetX REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN baseMapOffsetY REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN baseMapRotationDeg REAL NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ac_camera.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
