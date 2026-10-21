package com.e5lab.accamera.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project

@Database(
    entities = [Project::class, Measurement::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ac_camera.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
