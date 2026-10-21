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
 * @param backgroundImagePath optional factory floor-plan / site photo used as the base map
 *        for the equal-SPL contour plot and the survey backdrop.
 * @param planReferenceMeters reference scale of the base map: the real-world width (meters) that
 *        the plan image spans. Used to display the plan at its true proportions and to draw a
 *        distance scale bar so the engineer can tell which equipment a source sits on.
 * @param overlayAlpha opacity (0..1) of the coloured SPL overlay drawn on top of the base map.
 *        Lower = more transparent, so the underlying equipment in the plan stays visible.
 * @param backgroundNoiseDb ambient / background noise floor (dBA). The measured SPL at every point is
 *        treated as background + source excess; interpolation is performed on the excess and the
 *        background is added back, so quiet areas settle at the floor instead of falsely "radiating".
 *        Defaults to 0 dBA when the user provides no background measurement.
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

    // Visual backdrop: optional factory floor-plan / site photo used as the base map
    val backgroundImagePath: String? = null,

    // Reference scale of the base map (real-world width in meters the plan image spans)
    val planReferenceMeters: Double? = null,

    // Opacity of the coloured SPL overlay on top of the base map (0.1 transparent .. 1 opaque)
    val overlayAlpha: Float = 0.72f,

    // Ambient / background noise floor in dBA (0 = no background provided)
    val backgroundNoiseDb: Double = 0.0,

    // Number of quantized colour bands of the SPL heatmap (8/16/32/64/128/256)
    val contourColorBands: Int = 32,
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
