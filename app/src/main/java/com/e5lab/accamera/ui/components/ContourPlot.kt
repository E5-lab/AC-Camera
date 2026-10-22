package com.e5lab.accamera.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Draws the interpolated SPL heatmap [bitmap] (resX x resY) stretched over the plot rectangle, on top
 * of an optional [background] floor-plan base map, then overlays measured grid points. Points flagged
 * as noise sources get a bold ring. [overlayAlpha] controls how transparent the coloured SPL layer is,
 * so the underlying equipment in the base map stays readable.
 */
@Composable
fun ContourPlot(
    cols: Int,
    rows: Int,
    bitmap: ImageBitmap?,
    pointValues: Map<Pair<Int, Int>, Double>,
    flagged: Set<Pair<Int, Int>>,
    minValue: Double,
    maxValue: Double,
    background: ImageBitmap? = null,
    overlayAlpha: Float = 0.72f,
    planReferenceMeters: Double? = null,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }
    // cols/rows are CELL counts -> (cols+1) x (rows+1) corner points.
    val nCols = cols.coerceAtLeast(1)
    val nRows = rows.coerceAtLeast(1)

    Box(modifier.fillMaxSize().onGloballyPositioned { size = it.size }) {
        Canvas(Modifier.fillMaxSize()) {
            if (size == IntSize.Zero) return@Canvas
            val rect = plotRect(size.width.toFloat(), size.height.toFloat(), pad, background)

            if (background != null) {
                drawImage(
                    background,
                    dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                )
            }

            if (bitmap != null) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                    dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                    alpha = overlayAlpha,
                )
            } else {
                drawRect(
                    Color.LightGray.copy(alpha = 0.3f),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                )
            }

            // Faint grid for reference (cell boundaries)
            val grid = Color.White.copy(alpha = 0.25f)
            for (c in 0..cols) {
                val x = rect.left + c * rect.width / nCols
                drawLine(grid, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 0.5f)
            }
            for (r in 0..rows) {
                val y = rect.top + r * rect.height / nRows
                drawLine(grid, Offset(rect.left, y), Offset(rect.right, y), strokeWidth = 0.5f)
            }

            for (c in 0..cols) for (r in 0..rows) {
                val px = rect.left + c * rect.width / nCols
                val py = rect.top + r * rect.height / nRows
                val v = pointValues[c to r]
                val color = if (v != null) splColor(((v - minValue) / range).toFloat()) else Color.Gray.copy(alpha = 0.3f)
                val radius = if (v != null) 8f else 4f
                drawCircle(color, radius, Offset(px, py))
                if (flagged.contains(c to r)) {
                    drawCircle(Color.Black, radius + 7f, Offset(px, py), style = DrawStroke(width = 3f))
                    drawCircle(Color.White, radius + 2f, Offset(px, py), style = DrawStroke(width = 1.5f))
                }
            }

            drawScaleBar(rect, planReferenceMeters)
        }
    }
}
