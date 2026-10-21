package com.e5lab.accamera.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.e5lab.accamera.data.model.Measurement

/**
 * Android-only Room entity backing the shared [Measurement] model. The composite primary key
 * (projectId, col, row) is an upsert key; the FK CASCADE delete still applies so removing a project
 * wipes its readings — but the project is NEVER deleted via INSERT OR REPLACE (see ProjectDao), so
 * the cascade is never triggered accidentally.
 */
@Entity(
    tableName = "measurements",
    primaryKeys = ["projectId", "col", "row"],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MeasurementEntity(
    val projectId: Long,
    val col: Int,
    val row: Int,
    val spl: Double,
    val measuredAt: Long,
    val note: String,
    val remediationNote: String?,
    val afterSpl: Double?,
    val afterMeasuredAt: Long?,
    val isNoiseSource: Boolean,
)

fun Measurement.toEntity(): MeasurementEntity = MeasurementEntity(
    projectId = projectId,
    col = col,
    row = row,
    spl = spl,
    measuredAt = measuredAt,
    note = note,
    remediationNote = remediationNote,
    afterSpl = afterSpl,
    afterMeasuredAt = afterMeasuredAt,
    isNoiseSource = isNoiseSource,
)

fun MeasurementEntity.toModel(): Measurement = Measurement(
    projectId = projectId,
    col = col,
    row = row,
    spl = spl,
    measuredAt = measuredAt,
    note = note,
    remediationNote = remediationNote,
    afterSpl = afterSpl,
    afterMeasuredAt = afterMeasuredAt,
    isNoiseSource = isNoiseSource,
)
