package com.e5lab.accamera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.domain.geom.PlotTransform
import kotlin.math.min

/**
 * Interactive grid plot used by the data-entry screen.
 *
 * The measurement points sit on the grid-line INTERSECTIONS: [cols] x [rows] are cell counts, so
 * there are (cols+1) x (rows+1) input points, each drawn exactly at a line crossing (never in the
 * middle of a cell). Tapping the neighbourhood of an intersection selects it via [onPointTap].
 *
 * Supports the same pinch-zoom / drag-pan transform as the contour plot, and can show either a
 * raster floor-plan photo or a vector CAD (DXF) drawing underneath, so the operator can see which
 * equipment each intersection corresponds to.
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
    transform: PlotTransform = PlotTransform.DEFAULT,
    onTransformChange: (PlotTransform) -> Unit = {},
    cadDrawing: CadDrawing? = null,
    cadScaleToGrid: Boolean = false,
    onPointTap: (col: Int, row: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }
    // cols/rows are CELL counts -> (cols+1) x (rows+1) corner points, cols x rows cells.
    val nCols = cols.coerceAtLeast(1)
    val nRows = rows.coerceAtLeast(1)

    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .pointerInput(cols, rows, transform) {
                detectTapGestures { offset ->
                    if (size == IntSize.Zero || cols <= 0 || rows <= 0) return@detectTapGestures
                    val viewport = plotViewport(size, nCols, nRows, pad)
                    val cell = min(viewport.width / nCols, viewport.height / nRows)
                    val field = worldRect(viewport, nCols, nRows, cell, transform.clamped())
                    val cw = field.width / nCols
                    val ch = field.height / nRows
                    var best: Pair<Int, Int>? = null
                    var bestDist = Float.MAX_VALUE
                    for (c in 0..cols) for (r in 0..rows) {
                        val px = field.left + c * cw
                        val py = field.top + r * ch
                        val d = (offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py)
                        if (d < bestDist) {
                            bestDist = d
                            best = c to r
                        }
                    }
                    // Only accept taps close to an intersection, so dragging the map never
                    // accidentally opens the input sheet.
                    val thr = (min(cw, ch) * 0.55f).let { it * it }
                    if (best != null && bestDist <= thr) onPointTap(best.first, best.second)
                }
            }
            .pointerInput(nCols, nRows) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val viewport = plotViewport(size, nCols, nRows, pad)
                    if (viewport.width <= 0f || viewport.height <= 0f) return@detectTransformGestures
                    var next = transform
                    if (zoom != 1f) next = next.zoomAt(0.5f, 0.5f, zoom)
                    if (pan != Offset.Zero) {
                        next = next.panBy(pan.x / viewport.width, pan.y / viewport.height)
                    }
                    if (next != transform) onTransformChange(next)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (size == IntSize.Zero) return@Canvas
            val viewport = plotViewport(size, nCols, nRows, pad)
            val cell = min(viewport.width / nCols, viewport.height / nRows)
            if (cell <= 0f) return@Canvas
            val t = transform.clamped()
            val field = worldRect(viewport, nCols, nRows, cell, t)

            clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
                drawBaseMap(viewport, nCols, nRows, cell, t, background, cadDrawing, cadScaleToGrid)

                // Grid line work: the lattice of cells.
                val lineColor = if (background != null || cadDrawing != null) {
                    Color.White.copy(alpha = 0.50f)
                } else {
                    Color.LightGray
                }
                for (c in 0..nCols) {
                    val x = field.left + c * field.width / nCols
                    drawLine(lineColor, Offset(x, field.top), Offset(x, field.bottom), strokeWidth = 0.8f)
                }
                for (r in 0..nRows) {
                    val y = field.top + r * field.height / nRows
                    drawLine(lineColor, Offset(field.left, y), Offset(field.right, y), strokeWidth = 0.8f)
                }

                // Input points exactly ON the intersections.
                for (c in 0..nCols) for (r in 0..nRows) {
                    val px = field.left + c * field.width / nCols
                    val py = field.top + r * field.height / nRows
                    val v = points[c to r]
                    val color = if (v != null) {
                        splColor(((v - minValue) / range).toFloat())
                    } else {
                        Color.Gray.copy(alpha = 0.35f)
                    }
                    val radius = if (v != null) 9f else 4.5f
                    drawCircle(color, radius, Offset(px, py))
                    if (v != null) {
                        drawCircle(
                            Color.White.copy(alpha = 0.65f), radius, Offset(px, py),
                            style = Stroke(width = 1.5f),
                        )
                    }
                    if (selected == (c to r)) {
                        drawCircle(Color.Black, radius + 6f, Offset(px, py), style = Stroke(width = 2.5f))
                    }
                }
            }

            drawScaleBar(viewport, planReferenceMeters, t.zoom)
        }
    }
}

/**
 * Shared base-map painter: a vector CAD drawing wins over a raster photo (a project has one or the
 * other), and both are pushed through the same [PlotTransform] as the grid.
 */
fun DrawScope.drawBaseMap(
    viewport: Rect,
    nCols: Int,
    nRows: Int,
    cell: Float,
    t: PlotTransform,
    background: ImageBitmap?,
    cadDrawing: CadDrawing?,
    cadScaleToGrid: Boolean,
) {
    val world = worldRect(viewport, nCols, nRows, cell, t)
    if (cadDrawing != null && !cadDrawing.isEmpty) {
        val dst = if (cadScaleToGrid) {
            world
        } else {
            containFit(world, cadDrawing.aspectRatio)
        }
        drawCadDrawing(cadDrawing, dst)
        return
    }
    if (background == null || background.width <= 0 || background.height <= 0) return
    val dst = containFit(world, background.width.toFloat() / background.height.toFloat())
    drawImage(
        background,
        dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
        dstSize = IntSize(
            dst.width.toInt().coerceAtLeast(1),
            dst.height.toInt().coerceAtLeast(1),
        ),
    )
}

/** Largest rectangle of [aspect] (w/h) centred inside [outer]. */
fun containFit(outer: Rect, aspect: Float): Rect {
    if (aspect <= 0f) return outer
    val w = min(outer.width, outer.height * aspect)
    val h = w / aspect
    val left = outer.left + (outer.width - w) / 2f
    val top = outer.top + (outer.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}
