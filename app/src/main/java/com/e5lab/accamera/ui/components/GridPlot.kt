package com.e5lab.accamera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Interactive grid plot used by the survey screen.
 *
 * Draws an optional base-map image fitted to its true aspect ratio across the plotting area, the grid
 * lines, and a dot per grid point coloured by its measured SPL (grey if unmeasured). Tapping near a
 * point reports it via [onPointTap].
 */
@Composable
fun GridPlot(
    cols: Int,
    rows: Int,
    points: Map<Pair<Int, Int>, Double>,
    background: ImageBitmap?,
    selected: Pair<Int, Int>?,
    minValue: Double,
    maxValue: Double,
    planReferenceMeters: Double? = null,
    onPointTap: (col: Int, row: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }
    // cols/rows are CELL counts -> (cols+1) x (rows+1) corner points, cols x rows cells.
    val nCols = cols.coerceAtLeast(1)
    val nRows = rows.coerceAtLeast(1)

    Box(modifier.fillMaxSize().onGloballyPositioned { size = it.size }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(cols, rows, background) {
                    detectTapGestures { offset ->
                        if (size == IntSize.Zero || cols <= 0 || rows <= 0) return@detectTapGestures
                        val rect = plotRect(size.width.toFloat(), size.height.toFloat(), pad, background)
                        val cw = rect.width / nCols
                        val ch = rect.height / nRows
                        var best: Pair<Int, Int>? = null
                        var bestDist = Float.MAX_VALUE
                        for (c in 0..cols) for (r in 0..rows) {
                            val px = rect.left + c * cw
                            val py = rect.top + r * ch
                            val d =
                                (offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py)
                            if (d < bestDist) {
                                bestDist = d
                                best = c to r
                            }
                        }
                        val thr = ((cw.coerceAtMost(ch)) * 0.6f).let { it * it }
                        if (best != null && bestDist <= thr) {
                            onPointTap(best.first, best.second)
                        }
                    }
                }
        ) {
            if (size == IntSize.Zero) return@Canvas
            val rect = plotRect(size.width.toFloat(), size.height.toFloat(), pad, background)

            if (background != null) {
                drawImage(
                    background,
                    dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                )
            }

            val lineColor =
                if (background != null) Color.White.copy(alpha = 0.45f) else Color.LightGray
            for (c in 0..cols) {
                val x = rect.left + c * rect.width / nCols
                drawLine(lineColor, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1f)
            }
            for (r in 0..rows) {
                val y = rect.top + r * rect.height / nRows
                drawLine(lineColor, Offset(rect.left, y), Offset(rect.right, y), strokeWidth = 1f)
            }

            for (c in 0..cols) for (r in 0..rows) {
                val px = rect.left + c * rect.width / nCols
                val py = rect.top + r * rect.height / nRows
                val v = points[c to r]
                val color = if (v != null) {
                    splColor(((v - minValue) / range).toFloat())
                } else {
                    Color.Gray.copy(alpha = 0.35f)
                }
                val radius = if (v != null) 9f else 4f
                drawCircle(color, radius, Offset(px, py))
                if (selected == c to r) {
                    drawCircle(
                        Color.Black,
                        radius + 6f,
                        Offset(px, py),
                        style = Stroke(width = 2.5f),
                    )
                }
            }

            drawScaleBar(rect, planReferenceMeters)
        }
    }
}
