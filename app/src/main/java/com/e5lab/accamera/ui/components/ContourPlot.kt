package com.e5lab.accamera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.domain.geom.PlotTransform
import kotlin.math.min

/**
 * Renders the surveyed area as a 2-D "world" that can be zoomed and panned as one picture.
 *
 * World space is the surveyed rectangle: x in 0..[cols] cells, y in 0..[rows] cells, with the
 * measurement points sitting on the grid-line INTERSECTIONS (0..cols x 0..rows). Everything —
 * base map photo, CAD line work, SPL heatmap, grid lines and the measurement dots — goes through
 * the same [PlotTransform], so the base map can never drift out of proportion with the
 * equal-SPL map no matter how far the user zooms.
 *
 * Gestures: pinch to zoom (anchored at the pinch centre), drag to pan. The transform is hoisted so
 * the screen can also expose +/− and "fit to grid" buttons.
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
    transform: PlotTransform = PlotTransform.DEFAULT,
    onTransformChange: (PlotTransform) -> Unit = {},
    cadDrawing: CadDrawing? = null,
    cadScaleToGrid: Boolean = false,
    baseMapScale: Float = 1f,
    baseMapOffsetX: Float = 0f,
    baseMapOffsetY: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }
    // cols/rows are CELL counts -> (cols+1) x (rows+1) intersection points.
    val nCols = cols.coerceAtLeast(1)
    val nRows = rows.coerceAtLeast(1)
    // Gesture handlers capture whatever value `transform` had when pointerInput launched; without
    // rememberUpdatedState a drag after zooming reads the PRE-zoom transform and the map visibly
    // snaps back (bug report: "当前拖动会立马回到缩放前").
    val latestTransform by rememberUpdatedState(transform)
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)

    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .pointerInput(nCols, nRows) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val viewport = plotViewport(size, nCols, nRows, pad)
                    if (viewport.width <= 0f || viewport.height <= 0f) return@detectTransformGestures
                    // Gesture deltas are in pixels; the transform works in viewport fractions.
                    var next = latestTransform
                    if (zoom != 1f) next = next.zoomAt(0.5f, 0.5f, zoom)
                    if (pan != Offset.Zero) {
                        next = next.panBy(pan.x / viewport.width, pan.y / viewport.height)
                    }
                    if (next != latestTransform) latestOnTransformChange(next)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (size == IntSize.Zero) return@Canvas
            val viewport = plotViewport(size, nCols, nRows, pad)
            // Grid cell size in picture pixels — the aspect-correct base unit of the world.
            val cell = min(viewport.width / nCols, viewport.height / nRows)
            if (cell <= 0f) return@Canvas

            val t = transform.clamped()

            // ── clip everything to the viewport so zoomed content never spills over the controls
            clipRect(
                viewport.left, viewport.top, viewport.right, viewport.bottom,
            ) {
                // 1. base map: a raster plan photo or a vector CAD drawing
                drawBaseMap(
                    viewport, nCols, nRows, cell, t,
                    background, cadDrawing, cadScaleToGrid,
                    baseMapScale, baseMapOffsetX, baseMapOffsetY,
                )

                // 2. SPL heatmap stretched over the surveyed area (same transform -> aligned)
                val fieldRect = worldRect(viewport, nCols, nRows, cell, t)
                if (bitmap != null) {
                    drawImage(
                        bitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            fieldRect.left.toInt(), fieldRect.top.toInt(),
                        ),
                        dstSize = IntSize(
                            fieldRect.width.toInt().coerceAtLeast(1),
                            fieldRect.height.toInt().coerceAtLeast(1),
                        ),
                        alpha = overlayAlpha,
                    )
                } else {
                    drawRect(
                        Color.LightGray.copy(alpha = 0.3f),
                        topLeft = Offset(fieldRect.left, fieldRect.top),
                        size = Size(fieldRect.width, fieldRect.height),
                    )
                }

                // 3. grid lines on the point lattice
                val grid = Color.White.copy(alpha = 0.35f)
                for (c in 0..nCols) {
                    val x = fieldRect.left + c * fieldRect.width / nCols
                    drawLine(grid, Offset(x, fieldRect.top), Offset(x, fieldRect.bottom), strokeWidth = 0.6f)
                }
                for (r in 0..nRows) {
                    val y = fieldRect.top + r * fieldRect.height / nRows
                    drawLine(grid, Offset(fieldRect.left, y), Offset(fieldRect.right, y), strokeWidth = 0.6f)
                }

                // 4. measurement points ON the intersections (not inside the cells)
                for (c in 0..nCols) for (r in 0..nRows) {
                    val px = fieldRect.left + c * fieldRect.width / nCols
                    val py = fieldRect.top + r * fieldRect.height / nRows
                    val v = pointValues[c to r]
                    val color = if (v != null) {
                        splColor(((v - minValue) / range).toFloat())
                    } else {
                        Color.Gray.copy(alpha = 0.30f)
                    }
                    val radius = if (v != null) 8f else 4f
                    drawCircle(color, radius, Offset(px, py))
                    if (v != null) {
                        drawCircle(
                            Color.White.copy(alpha = 0.65f), radius,
                            Offset(px, py), style = DrawStroke(width = 1.5f),
                        )
                    }
                    if (flagged.contains(c to r)) {
                        drawCircle(Color.Black, radius + 7f, Offset(px, py), style = DrawStroke(width = 3f))
                        drawCircle(Color.White, radius + 2f, Offset(px, py), style = DrawStroke(width = 1.5f))
                    }
                }
            }

            drawScaleBar(viewport, planReferenceMeters, t.zoom)
        }
    }
}

/**
 * The largest aspect-correct rectangle of the surveyed area that fits the canvas, BEFORE the user
 * transform. This is the "fit to grid" baseline everything else is drawn relative to.
 */
fun plotViewport(size: IntSize, nCols: Int, nRows: Int, pad: Float): Rect {
    val availW = (size.width - 2 * pad).coerceAtLeast(1f)
    val availH = (size.height - 2 * pad).coerceAtLeast(1f)
    val cell = min(availW / nCols, availH / nRows)
    val w = cell * nCols
    val h = cell * nRows
    val left = pad + (availW - w) / 2f
    val top = pad + (availH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** The surveyed area after the user's zoom / pan, in canvas pixels. */
fun worldRect(viewport: Rect, nCols: Int, nRows: Int, cell: Float, t: PlotTransform): Rect {
    val (x0, y0) = t.let { it.worldToPicture(0f, 0f) }
    val (x1, y1) = t.let { it.worldToPicture(1f, 1f) }
    val left = viewport.left + x0 * nCols * cell
    val top = viewport.top + y0 * nRows * cell
    val right = viewport.left + x1 * nCols * cell
    val bottom = viewport.top + y1 * nRows * cell
    return Rect(left, top, right, bottom)
}
