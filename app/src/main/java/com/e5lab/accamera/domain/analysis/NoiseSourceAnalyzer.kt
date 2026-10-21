package com.e5lab.accamera.domain.analysis

import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project

/**
 * A detected noise source: a grid point that is a strict local maximum and at/above the threshold.
 */
data class NoiseSource(
    val col: Int,
    val row: Int,
    val x: Double,    // meters
    val y: Double,    // meters
    val spl: Double,  // dB
)

/**
 * Back-tracks the most likely noise-source locations from the measured grid.
 *
 * Approach: a point is treated as a candidate source when its SPL is strictly greater than every
 * measured neighbour (8-connectivity) and exceeds [thresholdDb]. Detected sources are returned
 * ranked by loudness so the engineer can prioritise remediation.
 */
object NoiseSourceAnalyzer {

    fun detect(
        project: Project,
        measurements: List<Measurement>,
        thresholdDb: Double,
    ): List<NoiseSource> {
        if (measurements.isEmpty()) return emptyList()

        val grid = Array(project.rows) { DoubleArray(project.cols) { Double.NaN } }
        for (m in measurements) {
            if (m.col in 0 until project.cols && m.row in 0 until project.rows) {
                grid[m.row][m.col] = m.spl
            }
        }

        val sources = mutableListOf<NoiseSource>()
        for (m in measurements) {
            val v = m.spl
            if (v < thresholdDb) continue

            var isMax = true
            outer@ for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val r = m.row + dr
                    val c = m.col + dc
                    if (r in 0 until project.rows && c in 0 until project.cols) {
                        val nv = grid[r][c]
                        if (!nv.isNaN() && nv > v) {
                            isMax = false
                            break@outer
                        }
                    }
                }
            }
            if (isMax) {
                sources.add(
                    NoiseSource(
                        col = m.col,
                        row = m.row,
                        x = project.xAt(m.col),
                        y = project.yAt(m.row),
                        spl = v,
                    )
                )
            }
        }
        return sources.sortedByDescending { it.spl }
    }
}
