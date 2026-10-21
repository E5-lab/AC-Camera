package com.e5lab.accamera.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Blue -> cyan -> green -> yellow -> red colormap, matching [com.e5lab.accamera.domain.contour.HeatmapRenderer].
 * @param t value in [0,1]
 */
private val STOPS = listOf(
    0.0f to Color(0.192f, 0.212f, 0.584f),
    0.25f to Color(0.271f, 0.667f, 0.784f),
    0.50f to Color(0.235f, 0.706f, 0.294f),
    0.75f to Color(0.961f, 0.804f, 0.196f),
    1.0f to Color(0.863f, 0.196f, 0.184f),
)

fun splColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    for (i in 0 until STOPS.lastIndex) {
        val (p0, c0) = STOPS[i]
        val (p1, c1) = STOPS[i + 1]
        if (x >= p0 && x <= p1) {
            val f = if (p1 - p0 == 0f) 0f else (x - p0) / (p1 - p0)
            return Color(
                c0.red + (c1.red - c0.red) * f,
                c0.green + (c1.green - c0.green) * f,
                c0.blue + (c1.blue - c0.blue) * f,
                1f,
            )
        }
    }
    return STOPS.last().second
}
