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
 * Draws an optional background image stretched across the plotting area, the grid lines, and a dot
 * per grid point coloured by its measured SPL (grey if unmeasured). Tapping near a point reports it
 * via [onPointTap].
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
    onPointTap: (col: Int, row: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pad = 24f
    val range = (maxValue - minValue).let { if (it <= 1e-6) 1.0 else it }

    Box(modifier.fillMaxSize().onGloballyPositioned { size = it.size }) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(cols, rows) {
                    detectTapGestures { offset ->
                        if (size == IntSize.Zero || cols <= 0 || rows <= 0) return@detectTapGestures
                        val availW = size.width - 2 * pad
                        val availH = size.height - 2 * pad
                        val cellW = if (cols > 1) availW / (cols - 1) else availW
                        val cellH = if (rows > 1) availH / (rows - 1) else availH

                        var best: Pair<Int, Int>? = null
                        var bestDist = Float.MAX_VALUE
                        for (c in 0 until cols) for (r in 0 until rows) {
                            val px = pad + c * cellW
                            val py = pad + r * cellH
                            val d =
                                (offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py)
                            if (d < bestDist) {
                                bestDist = d
                                best = c to r
                            }
                        }
                        val thr = ((cellW.coerceAtMost(cellH)) * 0.6f).let { it * it }
                        if (best != null && bestDist <= thr) {
                            onPointTap(best.first, best.second)
                        }
                    }
                }
        ) {
            if (size == IntSize.Zero) return@Canvas
            val availW = size.width - 2 * pad
            val availH = size.height - 2 * pad
            val cellW = if (cols > 1) availW / (cols - 1) else availW
            val cellH = if (rows > 1) availH / (rows - 1) else availH

            if (background != null) {
                drawImage(
                    background,
                    dstOffset = IntOffset(pad.toInt(), pad.toInt()),
                    dstSize = IntSize(availW.toInt(), availH.toInt()),
                )
            }

            val lineColor =
                if (background != null) Color.White.copy(alpha = 0.45f) else Color.LightGray
            for (c in 0 until cols) {
                val x = pad + c * cellW
                drawLine(lineColor, Offset(x, pad), Offset(x, pad + availH), strokeWidth = 1f)
            }
            for (r in 0 until rows) {
                val y = pad + r * cellH
                drawLine(lineColor, Offset(pad, y), Offset(pad + availW, y), strokeWidth = 1f)
            }

            for (c in 0 until cols) for (r in 0 until rows) {
                val px = pad + c * cellW
                val py = pad + r * cellH
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
        }
    }
}
