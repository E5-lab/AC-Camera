package com.e5lab.accamera.domain.contour

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.e5lab.accamera.domain.interpolation.FieldResult

/**
 * Renders an interpolated [FieldResult] into a heatmap [Bitmap] (resX x resY) using a
 * blue -> cyan -> green -> yellow -> red colormap, with iso-contour lines overlaid in dark grey.
 * The same bitmap is reused for on-screen preview and PNG export so they always match.
 */
object ContourRenderer {

    /**
     * Renders the field into a heatmap bitmap.
     *
     * @param colorBands number of quantized colour bands (e.g. 8/16/32/64/128/256). Values < 2
     *        produce the continuous gradient. Quantising makes the map show discrete colour zones
     *        whose "precision" the user can trade for readability.
     */
    fun render(field: FieldResult, levels: List<Double>, colorBands: Int = 0): Bitmap {
        val (values, resX, resY, _, _, min, max) = field
        val bmp = Bitmap.createBitmap(resX, resY, Bitmap.Config.ARGB_8888)
        val range = if (max - min > 1e-6) (max - min) else 1.0

        for (gy in 0 until resY) {
            for (gx in 0 until resX) {
                val v = values[gy * resX + gx]
                var t = ((v - min) / range).coerceIn(0.0, 1.0)
                if (colorBands >= 2) {
                    val band = (t * colorBands).toInt().coerceIn(0, colorBands - 1)
                    t = (band + 0.5) / colorBands
                }
                bmp.setPixel(gx, gy, colormap(t))
            }
        }

        if (levels.isNotEmpty()) {
            val canvas = Canvas(bmp)
            val paint = Paint().apply {
                color = Color.argb(200, 20, 20, 20)
                strokeWidth = 1.0f
                isAntiAlias = false
                style = Paint.Style.STROKE
            }
            for (level in levels) {
                for (s in ContourBuilder.contours(field, level)) {
                    canvas.drawLine(s.x1, s.y1, s.x2, s.y2, paint)
                }
            }
        }
        return bmp
    }

    /** t in [0,1] -> RGB int (blue -> cyan -> green -> yellow -> red). */
    fun colormap(t: Double): Int {
        val stops = arrayOf(
            0.00 to Triple(49.0, 54.0, 149.0),
            0.25 to Triple(69.0, 170.0, 200.0),
            0.50 to Triple(60.0, 180.0, 75.0),
            0.75 to Triple(245.0, 205.0, 50.0),
            1.00 to Triple(220.0, 50.0, 47.0),
        )
        val x = t.coerceIn(0.0, 1.0)
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (x >= p0 && x <= p1) {
                val f = if (p1 - p0 == 0.0) 0.0 else (x - p0) / (p1 - p0)
                val r = (c0.first + (c1.first - c0.first) * f).toInt()
                val g = (c0.second + (c1.second - c0.second) * f).toInt()
                val b = (c0.third + (c1.third - c0.third) * f).toInt()
                return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
        }
        return Color.rgb(220, 50, 47)
    }
}
