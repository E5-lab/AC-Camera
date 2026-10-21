package com.e5lab.accamera.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.e5lab.accamera.data.model.Measurement
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    /**
     * Insert or replace the reading for a grid point.
     * The composite primary key (projectId, col, row) makes this an upsert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(measurement: Measurement)

    @Delete
    suspend fun delete(measurement: Measurement)

    @Query("SELECT * FROM measurements WHERE projectId = :projectId ORDER BY row ASC, col ASC")
    fun observeByProject(projectId: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE projectId = :projectId")
    suspend fun getByProject(projectId: Long): List<Measurement>

    @Query(
        "UPDATE measurements SET isNoiseSource = :flag " +
            "WHERE projectId = :projectId"
    )
    suspend fun flagAllNoiseSources(projectId: Long, flag: Boolean)

    @Query("DELETE FROM measurements WHERE projectId = :projectId AND col = :col AND row = :row")
    suspend fun deleteOne(projectId: Long, col: Int, row: Int)

    @Query("DELETE FROM measurements WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}
