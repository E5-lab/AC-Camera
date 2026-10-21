package com.e5lab.accamera.domain.contour

import com.e5lab.accamera.domain.interpolation.FieldResult

/**
 * A line segment in field-index coordinates (x in [0, resX-1], y in [0, resY-1]).
 */
data class Segment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

/**
 * Marching-squares iso-contour extraction.
 *
 * Produces equal-value contour line segments for a given level. Callers map the returned
 * field-index coordinates onto screen / bitmap pixels. Ambiguous saddle cases (5 and 10) are
 * resolved with a simple symmetric split.
 */
object ContourBuilder {

    fun contours(field: FieldResult, level: Double): List<Segment> {
        val (values, resX, resY) = field
        if (resX < 2 || resY < 2) return emptyList()
        val segs = mutableListOf<Segment>()

        for (gy in 0 until resY - 1) {
            for (gx in 0 until resX - 1) {
                val tl = values[gy * resX + gx]
                val tr = values[gy * resX + gx + 1]
                val br = values[(gy + 1) * resX + gx + 1]
                val bl = values[(gy + 1) * resX + gx]

                var idx = 0
                if (tl > level) idx = idx or 8
                if (tr > level) idx = idx or 4
                if (br > level) idx = idx or 2
                if (bl > level) idx = idx or 1
                if (idx == 0 || idx == 15) continue

                // Edge intersection points (field coords)
                // a: top edge (tl -> tr), b: right edge (tr -> br), c: bottom edge (bl -> br), d: left edge (tl -> bl)
                val aX = gx + frac(level, tl, tr); val aY = gy.toFloat()
                val bX = (gx + 1).toFloat();        val bY = gy + frac(level, tr, br)
                val cX = gx + frac(level, bl, br);  val cY = (gy + 1).toFloat()
                val dX = gx.toFloat();              val dY = gy + frac(level, tl, bl)

                when (idx) {
                    1 -> segs.add(Segment(dX, dY, cX, cY))
                    2 -> segs.add(Segment(cX, cY, bX, bY))
                    3 -> segs.add(Segment(dX, dY, bX, bY))
                    4 -> segs.add(Segment(bX, bY, aX, aY))
                    5 -> {
                        segs.add(Segment(aX, aY, dX, dY))
                        segs.add(Segment(cX, cY, bX, bY))
                    }
                    6 -> segs.add(Segment(aX, aY, bX, bY))
                    7 -> segs.add(Segment(aX, aY, dX, dY))
                    8 -> segs.add(Segment(aX, aY, dX, dY))
                    9 -> segs.add(Segment(aX, aY, dX, dY))
                    10 -> {
                        segs.add(Segment(aX, aY, bX, bY))
                        segs.add(Segment(cX, cY, dX, dY))
                    }
                    11 -> segs.add(Segment(aX, aY, bX, bY))
                    12 -> segs.add(Segment(aX, aY, bX, bY))
                    13 -> segs.add(Segment(bX, bY, cX, cY))
                    14 -> segs.add(Segment(cX, cY, dX, dY))
                }
            }
        }
        return segs
    }

    private fun frac(level: Double, v0: Double, v1: Double): Float {
        val denom = v1 - v0
        if (denom == 0.0) return 0.5f
        return ((level - v0) / denom).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Builds a sensible set of contour levels (every [step] dB) covering [min, max], with at least
     * [minLevels] lines even for small ranges.
     */
    fun defaultLevels(
        min: Double,
        max: Double,
        step: Double = 5.0,
        minLevels: Int = 4,
    ): List<Double> {
        if (max - min < 1e-6) return emptyList()
        val s = if ((max - min) / step < minLevels) (max - min) / minLevels else step
        val start = (min / s).toInt() * s
        val levels = mutableListOf<Double>()
        var l = start
        while (l <= max + 1e-6) {
            if (l >= min - 1e-6) levels.add(l)
            l += s
        }
        return levels
    }
}
