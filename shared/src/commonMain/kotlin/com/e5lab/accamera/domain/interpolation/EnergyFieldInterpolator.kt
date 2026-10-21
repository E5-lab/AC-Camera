package com.e5lab.accamera.domain.interpolation

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-superposition propagation field ("radiation" model).
 *
 * Every measured point is treated as a small source whose excess level (above the background
 * noise floor) decays with distance:
 *
 *     E(p) = 10 · log10( Σ_i  10^((v_i − α·d(p,i)) / 10) )
 *
 * where v_i is the measured excess in dB, d(p,i) the distance in meters and α the attenuation
 * per meter (a typical default is 6 dB per grid spacing, i.e. α = 6 / spacing).
 *
 * Because sound energies superpose, HIGH-energy points dominate the field and decay outward
 * (matching the physical intuition of a source radiating and being absorbed by air), while
 * LOW-energy points contribute almost nothing — so quiet points no longer produce the false
 * "radiation" rings (local dips) that an exact interpolator like IDW creates.
 *
 * Callers add the background noise floor back to the returned excess field.
 */
object EnergyFieldInterpolator {

    fun interpolate(
        points: List<PointValue>,
        worldW: Double,
        worldH: Double,
        resX: Int = 140,
        resY: Int = computeResY(worldW, worldH, resX),
        attenuationPerMeter: Double = 0.6,
    ): FieldResult {
        require(resX > 1 && resY > 1) { "Resolution must be > 1" }
        val values = DoubleArray(resX * resY)

        if (points.isEmpty()) {
            return FieldResult(values, resX, resY, worldW, worldH, 0.0, 0.0)
        }

        // Precompute linear power of each point's excess and the exponential decay factor.
        val amp = DoubleArray(points.size) { i -> Math.pow(10.0, points[i].value / 10.0) }
        val k = attenuationPerMeter * 2.302585092994046 / 10.0 // α·ln(10)/10
        val px = DoubleArray(points.size) { i -> points[i].x }
        val py = DoubleArray(points.size) { i -> points[i].y }

        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE

        for (gy in 0 until resY) {
            val y = (gy + 0.5) / resY * worldH
            for (gx in 0 until resX) {
                val x = (gx + 0.5) / resX * worldW

                var energy = 0.0
                for (i in points.indices) {
                    val dx = x - px[i]
                    val dy = y - py[i]
                    val d = sqrt(dx * dx + dy * dy)
                    energy += amp[i] * exp(-k * d)
                }

                val v = if (energy > 0) 10.0 * log10(energy) else 0.0
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
