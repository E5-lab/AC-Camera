package com.e5lab.accamera.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ProjectListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo: ProjectRepository = (application as ACameraApplication).repository

    val projects: Flow<List<Project>> = repo.allProjects()

    suspend fun create(
        name: String,
        description: String,
        cols: Int,
        rows: Int,
        spacingX: Double,
        spacingY: Double,
    ): Long {
        val project = Project(
            name = name,
            description = description,
            cols = cols.coerceAtLeast(2),
            rows = rows.coerceAtLeast(2),
            spacingX = spacingX.coerceAtLeast(0.1),
            spacingY = spacingY.coerceAtLeast(0.1),
        )
        return repo.create(project)
    }

    fun delete(project: Project) = viewModelScope.launch {
        repo.delete(project)
    }

    /** Serialize the whole database to the shared JSON interchange format. */
    suspend fun exportAllJson(): String = repo.exportAllJson()

    /** Import a JSON file (chosen by the user) and merge it into Room. Returns #projects imported. */
    suspend fun importJsonUri(uri: Uri): Int = repo.importJsonUri(uri)
}
