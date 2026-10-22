package com.e5lab.accamera.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.e5lab.accamera.data.model.Project

/**
 * Android-only Room entity backing the shared [Project] model. The shared model is kept free of
 * Room annotations so it can be compiled for the Desktop (JVM) target as well.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    val cols: Int,
    val rows: Int,
    val spacingX: Double,
    val spacingY: Double,
    val backgroundImagePath: String?,
    val planReferenceMeters: Double?,
    val overlayAlpha: Float,
    val backgroundNoiseDb: Double,
    val contourColorBands: Int,
    // Base-map alignment (fraction of plot side) + rotation in degrees.
    val baseMapOffsetX: Float = 0f,
    val baseMapOffsetY: Float = 0f,
    val baseMapRotationDeg: Float = 0f,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cols = cols,
    rows = rows,
    spacingX = spacingX,
    spacingY = spacingY,
    backgroundImagePath = backgroundImagePath,
    planReferenceMeters = planReferenceMeters,
    overlayAlpha = overlayAlpha,
    backgroundNoiseDb = backgroundNoiseDb,
    contourColorBands = contourColorBands,
    baseMapOffsetX = baseMapOffsetX,
    baseMapOffsetY = baseMapOffsetY,
    baseMapRotationDeg = baseMapRotationDeg,
)

fun ProjectEntity.toModel(): Project = Project(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cols = cols,
    rows = rows,
    spacingX = spacingX,
    spacingY = spacingY,
    backgroundImagePath = backgroundImagePath,
    planReferenceMeters = planReferenceMeters,
    overlayAlpha = overlayAlpha,
    backgroundNoiseDb = backgroundNoiseDb,
    contourColorBands = contourColorBands,
    baseMapOffsetX = baseMapOffsetX,
    baseMapOffsetY = baseMapOffsetY,
    baseMapRotationDeg = baseMapRotationDeg,
)
