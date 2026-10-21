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
 * Draws the interpolated heatmap [bitmap] (resX x resY) stretched to the plotting area, then overlays
 * measured grid points. Points flagged as noise sources get a bold ring so they stand out on the
 * equal-SPL map.
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
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }

    Box(modifier.fillMaxSize().onGloballyPositioned { size = it.size }) {
        Canvas(Modifier.fillMaxSize()) {
            if (size == IntSize.Zero) return@Canvas
            val availW = size.width - 2 * pad
            val availH = size.height - 2 * pad
            val cellW = if (cols > 1) availW / (cols - 1) else availW
            val cellH = if (rows > 1) availH / (rows - 1) else availH

            if (bitmap != null) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(pad.toInt(), pad.toInt()),
                    dstSize = IntSize(availW.toInt(), availH.toInt()),
                )
            } else {
                drawRect(Color.LightGray.copy(alpha = 0.3f), Offset(pad, pad), Size(availW, availH))
            }

            // Faint grid for reference
            val grid = Color.White.copy(alpha = 0.25f)
            for (c in 0 until cols) {
                val x = pad + c * cellW
                drawLine(grid, Offset(x, pad), Offset(x, pad + availH), strokeWidth = 0.5f)
            }
            for (r in 0 until rows) {
                val y = pad + r * cellH
                drawLine(grid, Offset(pad, y), Offset(pad + availW, y), strokeWidth = 0.5f)
            }

            for (c in 0 until cols) for (r in 0 until rows) {
                val px = pad + c * cellW
                val py = pad + r * cellH
                val v = pointValues[c to r]
                val color = if (v != null) splColor(((v - minValue) / range).toFloat()) else Color.Gray.copy(alpha = 0.3f)
                val radius = if (v != null) 8f else 4f
                drawCircle(color, radius, Offset(px, py))
                if (flagged.contains(c to r)) {
                    drawCircle(Color.Black, radius + 7f, Offset(px, py), style = DrawStroke(width = 3f))
                    drawCircle(Color.White, radius + 2f, Offset(px, py), style = DrawStroke(width = 1.5f))
                }
            }
        }
    }
}
