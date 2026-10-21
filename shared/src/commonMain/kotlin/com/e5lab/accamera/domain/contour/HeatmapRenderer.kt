package com.e5lab.accamera.domain.contour

import com.e5lab.accamera.domain.interpolation.FieldResult

/**
 * Platform-agnostic heatmap renderer.
 *
 * Unlike the old Android-only [android.graphics.Bitmap] renderer, this returns a plain
 * [IntArray] of packed ARGB colours (one entry per field cell, row-major), which every platform
 * can turn into its own bitmap:
 *   - Android: `Bitmap.createBitmap(...).apply { for (i) setPixel(...) }`
 *   - Compose Desktop: `ImageBitmap` via Skia / `toAwtImage` etc.
 *
 * The blue -> cyan -> green -> yellow -> red colormap and the marching-squares iso-contour lines
 * are identical on every platform so the phone and the desktop client always agree.
 */
object HeatmapRenderer {

    /**
     * Renders the field into a packed-ARGB colour grid (resX x resY, row-major).
     *
     * @param colorBands number of quantized colour bands (e.g. 8/16/32/64/128/256). Values < 2
     *        produce the continuous gradient. Quantising makes the map show discrete colour zones
     *        whose "precision" the user can trade for readability.
     */
    fun renderColorGrid(field: FieldResult, levels: List<Double>, colorBands: Int = 0): IntArray {
        val (values, resX, resY, _, _, min, max) = field
        val out = IntArray(resX * resY)
        val range = if (max - min > 1e-6) (max - min) else 1.0

        for (gy in 0 until resY) {
            for (gx in 0 until resX) {
                val v = values[gy * resX + gx]
                var t = ((v - min) / range).coerceIn(0.0, 1.0)
                if (colorBands >= 2) {
                    val band = (t * colorBands).toInt().coerceIn(0, colorBands - 1)
                    t = (band + 0.5) / colorBands
                }
                out[gy * resX + gx] = colormap(t)
            }
        }

        if (levels.isNotEmpty()) {
            for (level in levels) {
                for (s in ContourBuilder.contours(field, level)) {
                    rasterLine(out, resX, resY, s.x1, s.y1, s.x2, s.y2)
                }
            }
        }
        return out
    }

    /** t in [0,1] -> packed ARGB int (full alpha). Blue -> cyan -> green -> yellow -> red. */
    fun colormap(t: Double): Int {
        val stops = arrayOf(
            0.00 to Triple(49, 54, 149),
            0.25 to Triple(69, 170, 200),
            0.50 to Triple(60, 180, 75),
            0.75 to Triple(245, 205, 50),
            1.00 to Triple(220, 50, 47),
        )
        val x = t.coerceIn(0.0, 1.0)
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (x >= p0 && x <= p1) {
                val f = if (p1 - p0 == 0.0) 0.0 else (x - p0) / (p1 - p0)
                val r = (c0.first + (c1.first - c0.first) * f).toInt().coerceIn(0, 255)
                val g = (c0.second + (c1.second - c0.second) * f).toInt().coerceIn(0, 255)
                val b = (c0.third + (c1.third - c0.third) * f).toInt().coerceIn(0, 255)
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return (0xFF shl 24) or (220 shl 16) or (50 shl 8) or 47
    }

    /** Rasterise a field-index line segment into [out] as dark-grey pixels (Bresenham). */
    private fun rasterLine(
        out: IntArray,
        w: Int,
        h: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        var x0 = x1.toInt().coerceIn(0, w - 1)
        var y0 = y1.toInt().coerceIn(0, h - 1)
        val xe = x2.toInt().coerceIn(0, w - 1)
        val ye = y2.toInt().coerceIn(0, h - 1)
        val dx = kotlin.math.abs(xe - x0)
        val dy = -kotlin.math.abs(ye - y0)
        val sx = if (x0 < xe) 1 else -1
        val sy = if (y0 < ye) 1 else -1
        var err = dx + dy
        val grey = (0xFF shl 24) or (20 shl 16) or (20 shl 8) or 20
        while (true) {
            if (y0 in 0 until h && x0 in 0 until w) out[y0 * w + x0] = grey
            if (x0 == xe && y0 == ye) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }
}
