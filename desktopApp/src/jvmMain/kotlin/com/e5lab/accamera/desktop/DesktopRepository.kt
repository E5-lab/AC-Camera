package com.e5lab.accamera.desktop

import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop (JVM) persistence: a single JSON file holding every project + its measurements.
 *
 * This is the desktop counterpart of the Android client's Room database — both operate on the
 * SAME shared [Project] / [Measurement] models, so a project authored on the phone and one authored
 * on the PC are byte-for-byte compatible and can be handed over via the JSON export/import.
 */
@Serializable
private data class ProjectWithMeasurements(
    val project: Project,
    val measurements: List<Measurement>,
)

class DesktopRepository(private val dataFile: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val idGen = AtomicLong(System.currentTimeMillis() % 1_000_000)

    private var store: MutableMap<Long, ProjectWithMeasurements> = load()

    private val _projects = MutableStateFlow(store.values.map { it.project }.sortedByDescending { it.updatedAt })
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _measurements = MutableStateFlow<Map<Long, List<Measurement>>>(store.mapValues { it.value.measurements })
    val measurements: StateFlow<Map<Long, List<Measurement>>> = _measurements.asStateFlow()

    fun getProject(id: Long): Project? = store[id]?.project

    fun getMeasurements(projectId: Long): List<Measurement> = store[projectId]?.measurements ?: emptyList()

    fun createProject(
        name: String,
        description: String,
        cols: Int,
        rows: Int,
        spacingX: Double,
        spacingY: Double,
        backgroundNoiseDb: Double,
    ): Long {
        val id = idGen.incrementAndGet()
        val p = Project(
            id = id,
            name = name,
            description = description,
            cols = cols,
            rows = rows,
            spacingX = spacingX,
            spacingY = spacingY,
            backgroundNoiseDb = backgroundNoiseDb,
        )
        store[id] = ProjectWithMeasurements(p, emptyList())
        persist()
        return id
    }

    fun updateProject(project: Project) {
        val m = store[project.id]?.measurements ?: emptyList()
        store[project.id] = ProjectWithMeasurements(project, m)
        persist()
    }

    fun deleteProject(id: Long) {
        store.remove(id)
        persist()
    }

    fun upsertMeasurement(m: Measurement) {
        val entry = store[m.projectId] ?: return
        val list = entry.measurements.toMutableList()
        val idx = list.indexOfFirst { it.col == m.col && it.row == m.row }
        if (idx >= 0) list[idx] = m else list.add(m)
        store[m.projectId] = ProjectWithMeasurements(entry.project, list)
        persist()
    }

    fun deleteMeasurement(projectId: Long, col: Int, row: Int) {
        val entry = store[projectId] ?: return
        val list = entry.measurements.filterNot { it.col == col && it.row == row }
        store[projectId] = ProjectWithMeasurements(entry.project, list)
        persist()
    }

    /** Replace the whole store from an imported JSON string (export/import hand-over). */
    fun importJson(text: String) {
        val list = json.decodeFromString<List<ProjectWithMeasurements>>(text)
        store = list.associateBy { it.project.id }.toMutableMap()
        persist()
    }

    fun exportJson(): String = json.encodeToString(store.values.toList())

    private fun persist() {
        runCatching {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText(exportJson())
        }
        _projects.value = store.values.map { it.project }.sortedByDescending { it.updatedAt }
        _measurements.value = store.mapValues { it.value.measurements }
    }

    private fun load(): MutableMap<Long, ProjectWithMeasurements> {
        return runCatching {
            if (!dataFile.exists()) return mutableMapOf()
            json.decodeFromString<List<ProjectWithMeasurements>>(dataFile.readText())
                .associateBy { it.project.id }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    companion object {
        fun defaultFile(): File {
            val dir = File(System.getProperty("user.home"), ".ac_camera")
            return File(dir, "data.json")
        }
    }
}
