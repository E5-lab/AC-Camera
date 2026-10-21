package com.e5lab.accamera.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.e5lab.accamera.data.model.Measurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MeasurementDao {

    /**
     * Insert or replace the reading for a grid point.
     * The composite primary key (projectId, col, row) makes this an upsert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(measurement: MeasurementEntity)

    @Delete
    suspend fun delete(measurement: MeasurementEntity)

    @Query("SELECT * FROM measurements WHERE projectId = :projectId ORDER BY row ASC, col ASC")
    fun observeByProject(projectId: Long): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE projectId = :projectId")
    suspend fun getByProject(projectId: Long): List<MeasurementEntity>

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

fun Flow<List<MeasurementEntity>>.toMeasurementModels(): Flow<List<Measurement>> =
    map { list -> list.map { it.toModel() } }
