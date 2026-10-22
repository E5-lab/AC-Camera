package com.e5lab.accamera.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.domain.cad.CadPolyline
import kotlin.math.max
import kotlin.math.min

/**
 * Strokes a parsed CAD (DXF) drawing into [dst].
 *
 * The [CadDrawing] coordinates are already normalised to 0..1 with the aspect ratio preserved and
 * Y flipped, so this only has to map unit space onto the destination rectangle.
 *
 * Rendered as stroked vector paths (not rasterised) so the plan stays crisp at any zoom level —
 * which matters because the user zooms in to line the CAD lines up with the measurement grid.
 */
fun DrawScope.drawCadDrawing(
    drawing: CadDrawing,
    dst: Rect,
    color: Color = Color(0xFF203A63),
    strokeWidth: Float = 1.1f,
) {
    if (drawing.isEmpty || dst.width <= 1f || dst.height <= 1f) return
    val sw = max(0.6f, strokeWidth)
    for (poly in drawing.polygons) {
        strokePolyline(poly, dst, color, sw)
    }
}

private fun DrawScope.strokePolyline(
    poly: CadPolyline,
    dst: Rect,
    color: Color,
    strokeWidth: Float,
) {
    val n = min(poly.xs.size, poly.ys.size)
    if (n < 2) return
    val path = Path()
    val x0 = dst.left + poly.xs[0] * dst.width
    val y0 = dst.top + poly.ys[0] * dst.height
    path.moveTo(x0, y0)
    // Draw only the segment if it is long enough to be visible at the current zoom: sub-pixel
    // strokes on a dense CAD drawing cost a lot and add nothing.
    for (i in 1 until n) {
        val x = dst.left + poly.xs[i] * dst.width
        val y = dst.top + poly.ys[i] * dst.height
        path.lineTo(x, y)
    }
    if (poly.closed) path.close()
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

/**
 * Rectangle the CAD drawing occupies, following the same contain-fit rule as the base map so a CAD
 * plan and a raster plan behave identically when the plot is resized.
 */
fun cadRect(sizeW: Float, sizeH: Float, pad: Float, drawing: CadDrawing): Rect {
    val availW = (sizeW - 2 * pad).coerceAtLeast(1f)
    val availH = (sizeH - 2 * pad).coerceAtLeast(1f)
    val aspect = drawing.aspectRatio
    return if (aspect > availW / availH) {
        val w = availW
        val h = availW / aspect
        Rect(pad, pad + (availH - h) / 2f, pad + w, pad + (availH - h) / 2f + h)
    } else {
        val h = availH
        val w = availH * aspect
        Rect(pad + (availW - w) / 2f, pad, pad + (availW - w) / 2f + w, pad + h)
    }
}

/** Convenience: draw a marker dot, used by both plots for unmeasured / measured points. */
fun DrawScope.dot(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, center)
}
