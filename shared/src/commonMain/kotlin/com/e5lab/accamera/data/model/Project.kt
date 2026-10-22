package com.e5lab.accamera.data.model

import kotlinx.serialization.Serializable

/**
 * A survey project = one factory area divided into a measurement grid.
 *
 * This is the platform-agnostic data model shared between the Android and Desktop clients
 * (lives in the `shared` Kotlin Multiplatform module). Persistence is handled per-platform:
 * the Android client maps this to a Room entity, the Desktop client maps it to JSON.
 *
 * Grid layout (CELL-COUNT semantics):
 *  - [cols] / [rows] are the number of grid *cells* along each axis (the factory is divided into a
 *    cols × rows lattice of rectangles).
 *  - Measurement points are located at the cell *corners* (grid-line intersections): there are
 *    (cols+1) columns and (rows+1) rows of points, i.e. [(cols+1) * (rows+1)] in total.
 *  - Point (col, row) is located at physical coordinates
 *      x = col * spacingX  (meters),  col in 0..cols
 *      y = row * spacingY  (meters),  row in 0..rows
 *    so the surveyed area is cols*spacingX wide and rows*spacingY tall.
 *
 * @param backgroundImagePath optional factory floor-plan / site photo used as the base map
 *        for the equal-SPL contour plot and the survey backdrop.
 * @param planReferenceMeters reference scale of the base map: the real-world width (meters) that
 *        the plan image spans. Used to display the plan at its true proportions and to draw a
 *        distance scale bar so the engineer can tell which equipment a source sits on.
 * @param overlayAlpha opacity (0..1) of the coloured SPL overlay drawn on top of the base map.
 *        Lower = more transparent, so the underlying equipment in the plan stays visible.
 * @param baseMapOffsetX / @param baseMapOffsetY residual alignment offset of the base map,
 *        expressed as a fraction of the plot side. Used to slide a floor plan / CAD export until
 *        its equipment lines up with the measurement grid.
 * @param baseMapRotationDeg rotation (degrees) applied to the base map, for plans that are not
 *        drawn north-up.
 * @param backgroundNoiseDb ambient / background noise floor (dBA). The measured SPL at every point is
 *        treated as background + source excess; interpolation is performed on the excess and the
 *        background is added back, so quiet areas settle at the floor instead of falsely "radiating".
 *        Defaults to 0 dBA when the user provides no background measurement.
 */
@Serializable
data class Project(
    val id: Long = 0,
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

    // Fine alignment of the base map against the grid, applied on top of "fit to grid".
    // Scale follows from planReferenceMeters; these two correct any residual offset so the plan's
    // equipment lines up with the measured grid. Values are fractions of the plot side (-0.5..0.5).
    val baseMapOffsetX: Float = 0f,
    val baseMapOffsetY: Float = 0f,

    // Base map rotation in degrees (-180..180), for CAD exports that are not north-up.
    val baseMapRotationDeg: Float = 0f,

    // Independent base-map scale relative to the grid (1 = contain-fit into the surveyed area).
    // Lets the user enlarge / shrink the plan on its own so its equipment lines up with the grid.
    val baseMapScale: Float = 1f,

    // Ambient / background noise floor in dBA (0 = no background provided)
    val backgroundNoiseDb: Double = 0.0,

    // Number of quantized colour bands of the SPL heatmap (8/16/32/64/128/256)
    val contourColorBands: Int = 32,
) {
    /** Physical width of the surveyed area in meters (cols cells × spacingX). */
    val widthMeters: Double get() = (cols).coerceAtLeast(0) * spacingX

    /** Physical height of the surveyed area in meters (rows cells × spacingY). */
    val heightMeters: Double get() = (rows).coerceAtLeast(0) * spacingY

    /** Total number of measurement points in the grid ((cols+1) × (rows+1) corners). */
    val pointCount: Int get() = (cols + 1) * (rows + 1)

    /** Physical X coordinate (meters) of a grid column (corner), col in 0..cols. */
    fun xAt(col: Int): Double = col * spacingX

    /** Physical Y coordinate (meters) of a grid row (corner), row in 0..rows. */
    fun yAt(row: Int): Double = row * spacingY
}
