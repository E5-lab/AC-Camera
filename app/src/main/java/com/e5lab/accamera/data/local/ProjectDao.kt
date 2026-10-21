package com.e5lab.accamera.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.e5lab.accamera.data.model.Project
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Insert
    suspend fun insert(project: Project): Long

    /**
     * Plain UPDATE — must NOT be INSERT OR REPLACE. SQLite's REPLACE deletes the conflicting
     * row before inserting, which fires the measurements ON DELETE CASCADE and silently wipes
     * every reading of the project (the "data disappears" bug).
     */
    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: Long): Flow<Project?>
}
