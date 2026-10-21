package com.e5lab.accamera.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A survey project = one factory area divided into a measurement grid.
 *
 * Grid layout:
 *  - [cols] measurement points per row, [rows] rows of points.
 *  - Point (col, row) is located at physical coordinates
 *      x = col * spacingX  (meters)
 *      y = row * spacingY  (meters)
 *    so the surveyed area is (cols-1)*spacingX wide and (rows-1)*spacingY tall.
 *
 * @param backgroundImagePath optional floor-plan / site photo used as a visual backdrop.
 *        The photo is assumed to cover the whole grid area; the grid is drawn on top of it.
 * @param calibrationOffsetDb offset (dB) added to the raw microphone estimate so the phone
 *        reading approximates real SPL. Calibrate against a reference sound level meter.
 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // Grid definition
    val cols: Int,
    val rows: Int,
    val spacingX: Double,   // meters between adjacent columns
    val spacingY: Double,   // meters between adjacent rows

    // Visual backdrop
    val backgroundImagePath: String? = null,

    // SPL calibration
    val calibrationOffsetDb: Double = 90.0,
) {
    /** Physical width of the surveyed area in meters. */
    val widthMeters: Double get() = (cols - 1).coerceAtLeast(0) * spacingX

    /** Physical height of the surveyed area in meters. */
    val heightMeters: Double get() = (rows - 1).coerceAtLeast(0) * spacingY

    /** Total number of measurement points in the grid. */
    val pointCount: Int get() = cols * rows

    /** Physical X coordinate (meters) of a grid column. */
    fun xAt(col: Int): Double = col * spacingX

    /** Physical Y coordinate (meters) of a grid row. */
    fun yAt(row: Int): Double = row * spacingY
}
