package com.e5lab.accamera.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Compute the rectangle (in canvas pixels) where the base map / heatmap should be drawn.
 *
 * If a base-map image is present, its true aspect ratio is preserved (contain-fit, centered) so the
 * plan is never stretched; this keeps the drawing aligned with the user-supplied reference scale and
 * solves the canvas / floor-plan proportion mismatch. Without a base map the whole padded area is used.
 */
fun plotRect(sizeW: Float, sizeH: Float, pad: Float, bg: ImageBitmap?): Rect {
    val availW = (sizeW - 2 * pad).coerceAtLeast(1f)
    val availH = (sizeH - 2 * pad).coerceAtLeast(1f)
    return if (bg != null && bg.width > 0 && bg.height > 0) {
        val planAspect = bg.width.toFloat() / bg.height.toFloat()
        val availAspect = availW / availH
        val (w, h) = if (planAspect > availAspect) {
            availW to (availW / planAspect)
        } else {
            (availH * planAspect) to availH
        }
        val left = pad + (availW - w) / 2f
        val top = pad + (availH - h) / 2f
        Rect(left, top, left + w, top + h)
    } else {
        Rect(pad, pad, pad + availW, pad + availH)
    }
}

/**
 * Draws a distance scale bar at the bottom-left of [rect], derived from [planReferenceMeters] (the
 * real-world width the base map spans). [zoom] keeps the printed length of the bar stable when the
 * user zooms, so the map stays readable as a measurement aid.
 */
fun DrawScope.drawScaleBar(rect: Rect, planReferenceMeters: Double?, zoom: Float = 1f) {
    val ref = planReferenceMeters ?: return
    if (ref <= 0) return
    val pxPerMeter = rect.width / ref.toFloat()
    if (pxPerMeter <= 0) return
    val trueScale = pxPerMeter / if (zoom > 0f) zoom else 1f
    val targetPx = 100f
    val nice = niceNumber(targetPx / trueScale)
    val barPx = nice * trueScale
    val d = density // DrawScope implements Density; this is the Float pixel density
    val x0 = rect.left + 12f * d
    val y = rect.bottom - 16f * d

    // White halo underneath so the bar stays legible on top of a coloured heatmap.
    drawLine(Color.White, Offset(x0, y), Offset(x0 + barPx, y), strokeWidth = 4.5f * d)
    drawLine(Color.Black, Offset(x0, y), Offset(x0 + barPx, y), strokeWidth = 2f * d)
    drawLine(Color.Black, Offset(x0, y - 6f * d), Offset(x0, y + 6f * d), strokeWidth = 2f * d)
    drawLine(Color.Black, Offset(x0 + barPx, y - 6f * d), Offset(x0 + barPx, y + 6f * d), strokeWidth = 2f * d)

    val label = if (nice >= 1f) "${nice.toInt()} m" else "%.1f m".format(nice)
    val paint = Paint().apply {
        color = AndroidColor.BLACK
        textSize = 13f * d
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * d, 2f * d, 2f * d, AndroidColor.WHITE)
    }
    drawContext.canvas.nativeCanvas.drawText(label, x0, y - 10f * d, paint)
}

private fun niceNumber(x: Float): Float {
    if (x <= 0) return 1f
    val exp = floor(log10(x))
    val base = 10f.pow(exp)
    val f = x / base
    val nf = when {
        f < 1.5f -> 1f
        f < 3.5f -> 2f
        f < 7.5f -> 5f
        else -> 10f
    }
    return nf * base
}
