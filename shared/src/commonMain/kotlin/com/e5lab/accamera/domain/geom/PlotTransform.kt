package com.e5lab.accamera.domain.geom

/**
 * Interactive view transform for the survey / contour plot.
 *
 * The plot area is the surveyed rectangle (world coordinate space:
 * x in 0..worldW meters, y in 0..worldH meters). The user can zoom towards the cursor / pinch
 * centre ([zoom]) and drag the picture around ([panX], [panY] — fractions of the viewport).
 *
 * Two normalised coordinate spaces are used:
 *  - *world*:  (0,0) = top-left corner of the surveyed area, (1,1) = bottom-right corner.
 *  - *picture*: the same area after [zoom] and [pan]. (0,0) is the visible top-left corner.
 *
 *                 picture = world * zoom + pan
 *
 * Both are "unit" spaces, so a single transform works no matter how large the canvas is; the
 * renderer only has to multiply by the canvas size.
 *
 * Because zoom & pan are applied to the base map AND the heatmap AND the grid AND the measurement
 * dots with the exact same numbers, the base map always stays in perfect proportion with the
 * equal-SPL map — which is what the engineer needs to line a floor plan up with the grid.
 *
 * This class lives in the shared KMP module so the Android and Windows clients use identical maths.
 */
data class PlotTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    /** True when the picture is exactly the fitted, un-panned surveyed area. */
    val isIdentity: Boolean
        get() = zoom == 1f && panX == 0f && panY == 0f

    /** Zoom factor actually in use (guards against corrupt persisted values). */
    private val z: Float get() = if (zoom.isFinite() && zoom > 0f) zoom else 1f

    private fun px(): Float = if (panX.isFinite()) panX else 0f
    private fun py(): Float = if (panY.isFinite()) panY else 0f

    /** Map a world-normalised coordinate to picture-normalised space. */
    fun worldToPicture(wx: Float, wy: Float): Pair<Float, Float> =
        (wx * z + px()) to (wy * z + py())

    /** Map a picture-normalised coordinate back to world-normalised space. */
    fun pictureToWorld(px0: Float, py0: Float): Pair<Float, Float> =
        ((px0 - px()) / z) to ((py0 - py()) / z)

    /**
     * Zoom by [factor] keeping the picture point ([anchorX], [anchorY]) (picture-normalised, i.e.
     * the cursor position inside the viewport) pinned under the cursor.
     */
    fun zoomAt(anchorX: Float, anchorY: Float, factor: Float): PlotTransform {
        if (!factor.isFinite() || factor <= 0f) return this
        val (ax, ay) = pictureToWorld(anchorX, anchorY)
        val newZoom = (z * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return PlotTransform(newZoom, anchorX - ax * newZoom, anchorY - ay * newZoom).clamped()
    }

    /** Drag the picture by ([dx], [dy]) in picture-normalised units. */
    fun panBy(dx: Float, dy: Float): PlotTransform =
        PlotTransform(z, px() + dx, py() + dy).clamped()

    /**
     * Scale the base map / heatmap up or down (e.g. to make a CAD export fit the grid) while
     * keeping the centre of the viewport anchored.
     */
    fun scaled(factor: Float): PlotTransform = zoomAt(0.5f, 0.5f, factor)

    /**
     * Keep the picture covering the viewport: zoom may not go below 1, and pan may not expose
     * blank space around the surveyed area.
     */
    fun clamped(): PlotTransform {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val lo = 1f - nz
        val nx = px().coerceIn(lo, 0f)
        val ny = py().coerceIn(lo, 0f)
        return PlotTransform(nz, nx, ny)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 20f
        const val DEFAULT = PlotTransform()
    }
}
