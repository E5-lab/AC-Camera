package com.e5lab.accamera.domain.interpolation

/**
 * A single sample used for interpolation: physical position (meters) + measured value.
 */
data class PointValue(val x: Double, val y: Double, val value: Double)

/**
 * A regular scalar field covering [worldW] x [worldH] meters, sampled at [resX] x [resY] points.
 * Values are stored row-major: index = gy * resX + gx.
 */
data class FieldResult(
    val values: DoubleArray,
    val resX: Int,
    val resY: Int,
    val worldW: Double,
    val worldH: Double,
    val min: Double,
    val max: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FieldResult
        if (!values.contentEquals(other.values)) return false
        return resX == other.resX && resY == other.resY &&
            worldW == other.worldW && worldH == other.worldH
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + resX
        result = 31 * result + resY
        return result
    }
}

/**
 * Inverse-distance-weighted (IDW) interpolation. A simple, robust scattered-data interpolator that
 * is well suited to the irregular, sparse sampling typical of a field grid survey.
 */
object IdwInterpolator {

    fun interpolate(
        points: List<PointValue>,
        worldW: Double,
        worldH: Double,
        resX: Int = 140,
        resY: Int = computeResY(worldW, worldH, resX),
        power: Double = 2.0,
    ): FieldResult {
        require(resX > 1 && resY > 1) { "Resolution must be > 1" }
        val values = DoubleArray(resX * resY)

        if (points.isEmpty()) {
            return FieldResult(values, resX, resY, worldW, worldH, 0.0, 0.0)
        }

        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE

        for (gy in 0 until resY) {
            val y = (gy + 0.5) / resY * worldH
            for (gx in 0 until resX) {
                val x = (gx + 0.5) / resX * worldW

                var exact = Double.NaN
                var wsum = 0.0
                var vsum = 0.0
                for (p in points) {
                    val dx = x - p.x
                    val dy = y - p.y
                    val d2 = dx * dx + dy * dy
                    if (d2 < 1e-9) {
                        exact = p.value
                        break
                    }
                    val w = 1.0 / Math.pow(d2, power / 2.0)
                    wsum += w
                    vsum += w * p.value
                }

                val v = if (!exact.isNaN()) exact else if (wsum > 0) vsum / wsum else 0.0
                values[gy * resX + gx] = v
                if (v < min) min = v
                if (v > max) max = v
            }
        }

        if (min == Double.MAX_VALUE) {
            min = 0.0
            max = 0.0
        }
        return FieldResult(values, resX, resY, worldW, worldH, min, max)
    }

    private fun computeResY(worldW: Double, worldH: Double, resX: Int): Int {
        val ratio = if (worldW > 0) worldH / worldW else 1.0
        return (resX * ratio).toInt().coerceAtLeast(2)
    }
}
