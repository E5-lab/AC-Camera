package com.e5lab.accamera.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.e5lab.accamera.data.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface ProjectDao {

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    /** All projects (no flow) — used by the JSON export of the whole database. */
    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>

    /**
     * Plain UPDATE — must NOT be INSERT OR REPLACE. SQLite's REPLACE deletes the conflicting
     * row before inserting, which fires the measurements ON DELETE CASCADE and silently wipes
     * every reading of the project (the "data disappears" bug).
     */
    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: Long): Flow<ProjectEntity?>
}

// Flow<ProjectEntity> -> Flow<Project> mappers (kept here so repositories stay model-typed)
fun Flow<List<ProjectEntity>>.toProjectModels(): Flow<List<Project>> = map { list -> list.map { it.toModel() } }
fun Flow<ProjectEntity?>.toProjectModel(): Flow<Project?> = map { it?.toModel() }
