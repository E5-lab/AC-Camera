package com.e5lab.accamera

import android.app.Application
import com.e5lab.accamera.data.local.AppDatabase
import com.e5lab.accamera.data.repository.ProjectRepository

/**
 * Application-level container that owns the Room database and the repository.
 * ViewModels reach the repository through `(application as ACameraApplication).repository`.
 */
class ACameraApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val repository: ProjectRepository by lazy { ProjectRepository(database, this) }
}
