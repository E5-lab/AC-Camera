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
     * @param quantizeStep colour levels used to *draw* the bands. High band counts only make a
     *        visible difference when the palette colour ramp is quantised as well, so the renderer
     *        snaps each band colour to at least [MIN_PALETTE_STEPS] steps. Defaults to [colorBands].
     *
     * The band colour is the colour at the BAND CENTRE, so increasing the band count visibly
     * increases the number of distinguishable colours (8 → 2-step staircase zones,
     * 256 → a near-continuous gradient).
     */
    fun renderColorGrid(
        field: FieldResult,
        levels: List<Double>,
        colorBands: Int = 0,
        quantizeStep: Int = colorBands,
    ): IntArray {
        val (values, resX, resY, _, _, min, max) = field
        val out = IntArray(resX * resY)
        val range = if (max - min > 1e-6) (max - min) else 1.0
        val bands = if (colorBands >= 2) colorBands else 0
        val steps = if (quantizeStep >= 2) quantizeStep else 0

        for (gy in 0 until resY) {
            for (gx in 0 until resX) {
                val v = values[gy * resX + gx]
                val t = ((v - min) / range).coerceIn(0.0, 1.0)
                out[gy * resX + gx] = if (bands == 0) {
                    colormap(t)
                } else {
                    val band = (t * bands).toInt().coerceIn(0, bands - 1)
                    bandColor(band, bands, steps)
                }
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

    /**
     * Packed ARGB colour of quantised band [band] (0-based) out of [bands] total.
     *
     * The sample point is the band centre, then optionally snapped to a palette step so adjacent
     * band counts produce visibly different colouring. Steps are clamped to at least
     * [MIN_PALETTE_STEPS]: at 8 bands a 1/8 step would collapse the dark end of the ramp into two
     * identical blues, so fine counts stay distinguishable while coarse counts still show steps.
     */
    fun bandColor(band: Int, bands: Int, steps: Int): Int {
        if (bands < 2) return colormap(0.0)
        val b = band.coerceIn(0, bands - 1)
        var t = (b + 0.5) / bands
        if (steps >= 2) {
            val st = steps.coerceIn(MIN_PALETTE_STEPS, MAX_PALETTE_STEPS)
            t = kotlin.math.round(t * st) / st
        }
        return colormap(t.coerceIn(0.0, 1.0))
    }

    /** Finest palette resolution we allow when quantising band colours (keeps the ramp smooth). */
    const val MIN_PALETTE_STEPS = 64

    /** Coarsest palette resolution — every band gets its own colour up to 256 bands. */
    const val MAX_PALETTE_STEPS = 256


    /** t in [0,1] -> packed ARGB int (full alpha). Blue -> cyan -> green -> yellow -> red. */
    fun colormap(t: Double): Int {
        val x = t.coerceIn(0.0, 1.0)
        val n = STOPS.size - 1
        val pos = x * n
        val i = pos.toInt().coerceIn(0, n - 1)
        val f = pos - i
        val c0 = STOPS[i]
        val c1 = STOPS[i + 1]
        val r = (c0[0] + (c1[0] - c0[0]) * f + 0.5).toInt().coerceIn(0, 255)
        val g = (c0[1] + (c1[1] - c0[1]) * f + 0.5).toInt().coerceIn(0, 255)
        val b = (c0[2] + (c1[2] - c0[2]) * f + 0.5).toInt().coerceIn(0, 255)
        return argb(r, g, b)
    }

    /**
     * Colormap stop positions (0..1) and RGB triples. Kept as parallel arrays (not data classes)
     * so the renderer stays allocation-free inside the per-pixel loop.
     */
    private val STOPS = arrayOf(
        intArrayOf(49, 54, 149),    // 0.00 deep blue
        intArrayOf(69, 170, 200),   // 0.25 cyan
        intArrayOf(60, 180, 75),    // 0.50 green
        intArrayOf(245, 205, 50),   // 0.75 yellow
        intArrayOf(220, 50, 47),    // 1.00 red
    )

    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Representative colours of the quantised bands, for drawing a legend that matches the map
     * exactly. Band [i] of [bands] covers value range
     * `min + (max-min)*i/bands  ..  min + (max-min)*(i+1)/bands`.
     */
    fun bandColors(bands: Int, steps: Int = bands): IntArray {
        val n = if (bands >= 2) bands else 1
        return IntArray(n) { i -> if (bands >= 2) bandColor(i, bands, steps) else colormap(0.0) }
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
