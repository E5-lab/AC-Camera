package com.e5lab.accamera.data.repository

import android.content.Context
import android.os.Environment
import com.e5lab.accamera.data.local.AppDatabase
import com.e5lab.accamera.data.local.toMeasurementModels
import com.e5lab.accamera.data.local.toModel
import com.e5lab.accamera.data.local.toProjectModel
import com.e5lab.accamera.data.local.toProjectModels
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for projects and measurements.
 * All data is stored locally in Room (offline-first) so surveys keep working on the factory floor
 * with no network connection. The Room entities ([com.e5lab.accamera.data.local.ProjectEntity] /
 * [com.e5lab.accamera.data.local.MeasurementEntity]) are mapped to the shared, platform-agnostic
 * [Project] / [Measurement] models so the rest of the app (and the future Desktop client) never
 * sees Android-specific persistence details.
 */
class ProjectRepository(
    private val db: AppDatabase,
    private val context: Context,
) {

    // ---- Projects ----
    fun allProjects(): Flow<List<Project>> = db.projectDao().observeAll().toProjectModels()
    fun getProjectFlow(id: Long): Flow<Project?> = db.projectDao().observeById(id).toProjectModel()
    suspend fun getProject(id: Long): Project? = db.projectDao().getById(id)?.toModel()
    suspend fun create(project: Project): Long = db.projectDao().insert(project.toEntity())

    suspend fun update(project: Project) =
        db.projectDao().update(project.copy(updatedAt = System.currentTimeMillis()).toEntity())

    suspend fun delete(project: Project) = db.projectDao().delete(project.toEntity())

    // ---- Measurements ----
    fun measurements(projectId: Long): Flow<List<Measurement>> =
        db.measurementDao().observeByProject(projectId).toMeasurementModels()

    suspend fun getMeasurements(projectId: Long): List<Measurement> =
        db.measurementDao().getByProject(projectId).map { it.toModel() }

    suspend fun upsertMeasurement(measurement: Measurement) =
        db.measurementDao().upsert(measurement.toEntity())

    suspend fun deleteMeasurement(projectId: Long, col: Int, row: Int) =
        db.measurementDao().deleteOne(projectId, col, row)

    // ---- Export ----
    /**
     * Writes the project + all measurements to a CSV file under the app's Documents directory.
     * Returns the written file (the UI can share it through FileProvider).
     */
    suspend fun exportCsv(project: Project, measurements: List<Measurement>): File =
        withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeName = project.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val file = File(dir, "AC-Camera_${safeName}_$stamp.csv")

            val measured = measurements.associateBy { "${it.col},${it.row}" }

            file.bufferedWriter().use { w ->
                w.appendLine("AC-Camera 等声压级测量数据导出")
                w.appendLine("项目,${project.name}")
                w.appendLine("说明,${project.description}")
                w.appendLine("网格列数,${project.cols}")
                w.appendLine("网格行数,${project.rows}")
                w.appendLine("列间距(米),${project.spacingX}")
                w.appendLine("行间距(米),${project.spacingY}")
                w.appendLine("区域宽度(米),${"%.2f".format(project.widthMeters)}")
                w.appendLine("区域高度(米),${"%.2f".format(project.heightMeters)}")
                w.appendLine("背景噪声(dBA),${"%.1f".format(project.backgroundNoiseDb)}")
                w.appendLine("")
                w.appendLine("col,row,x_m,y_m,spl_db,note,remediation,after_spl_db,reduction_db")

                for (row in 0 until project.rows) {
                    for (col in 0 until project.cols) {
                        val m = measured["$col,$row"]
                        val x = "%.2f".format(project.xAt(col))
                        val y = "%.2f".format(project.yAt(row))
                        if (m == null) {
                            w.appendLine("$col,$row,$x,$y,,,,")
                        } else {
                            val reduction = m.reductionDb?.let { "%.1f".format(it) } ?: ""
                            w.appendLine(
                                "$col,$row,$x,$y," +
                                    "%.1f".format(m.spl) + "," +
                                    "\"${m.note.replace("\"", "'")}\"," +
                                    "\"${(m.remediationNote ?: "").replace("\"", "'")}\"," +
                                    (m.afterSpl?.let { "%.1f".format(it) } ?: "") + "," +
                                    reduction
                            )
                        }
                    }
                }
            }
            file
        }
}
