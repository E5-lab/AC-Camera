package com.e5lab.accamera.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Shared view model for a single project (dashboard, setup, survey and contour screens all use it).
 */
class ProjectViewModel(
    application: Application,
    private val projectId: Long,
) : AndroidViewModel(application) {

    private val repo: ProjectRepository = (application as ACameraApplication).repository

    val project: Flow<Project?> = repo.getProjectFlow(projectId)
    val measurements: Flow<List<Measurement>> = repo.measurements(projectId)

    fun updateProject(project: Project) = viewModelScope.launch {
        repo.update(project)
    }

    fun upsertMeasurement(measurement: Measurement) = viewModelScope.launch {
        repo.upsertMeasurement(measurement)
    }

    fun deleteMeasurement(col: Int, row: Int) = viewModelScope.launch {
        repo.deleteMeasurement(projectId, col, row)
    }

    fun deleteProject(project: Project) = viewModelScope.launch {
        repo.delete(project)
    }

    suspend fun getProject(): Project? = repo.getProject(projectId)

    suspend fun exportCsv(): java.io.File? {
        val p = repo.getProject(projectId) ?: return null
        val ms = repo.getMeasurements(projectId)
        return repo.exportCsv(p, ms)
    }
}
