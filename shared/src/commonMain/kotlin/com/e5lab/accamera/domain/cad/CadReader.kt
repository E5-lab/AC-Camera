package com.e5lab.accamera.domain.cad

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2-D polyline in CAD drawing units (millimetres for practically every DXF export).
 *
 * [closed] marks rectangles / circles / solids so the renderer can stroke them as a loop.
 */
data class CadPolyline(
    val xs: FloatArray,
    val ys: FloatArray,
    val closed: Boolean,
)

/**
 * Vector geometry extracted from a CAD (DXF) drawing, normalised to 0..1 on both axes with the
 * aspect ratio preserved.
 *
 * The DXF y axis points up while screen y points down, so the points are already flipped — callers
 * simply scale by the plot's pixel width / height.
 */
class CadDrawing(
    val polygons: List<CadPolyline>,
    val boundsMinX: Float,
    val boundsMinY: Float,
    val boundsMaxX: Float,
    val boundsMaxY: Float,
) {
    /** Width / height of the drawing in its native units (used for scale-bar calibration). */
    val nativeWidth: Float get() = boundsMaxX - boundsMinX
    val nativeHeight: Float get() = boundsMaxY - boundsMinY

    val isEmpty: Boolean get() = polygons.isEmpty()

    val vertexCount: Int get() = polygons.sumOf { it.xs.size }

    val aspectRatio: Float
        get() = if (nativeHeight > 1e-6f) nativeWidth / nativeHeight else 1f
}

/**
 * Minimal, dependency-free DXF reader — enough to turn a factory CAD floor plan into a base map.
 *
 * Supported geometry: LINE, LWPOLYLINE, POLYLINE/VERTEX/SEQEND, CIRCLE, ARC, ELLIPSE, POINT,
 * SOLID, 3DFACE, TRACE, SPLINE (drawn as its control polygon) and INSERT block references
 * (recursively expanded with rotation + scale applied).
 *
 * Text, dimensions, hatches and attributes are intentionally ignored — a base map only needs the
 * visible line work. Malformed groups are skipped rather than throwing, so a slightly broken
 * export still renders whatever is readable.
 *
 * Pure Kotlin (no third-party dependency) so both the Android and the JVM desktop client can use
 * it; it lives in the shared KMP module.
 */
object CadReader {

    /** Upper bound on total vertices, so a pathological drawing can never freeze the UI. */
    private const val MAX_VERTICES = 400_000

    /** Maximum INSERT nesting depth (blocks inside blocks). */
    private const val MAX_DEPTH = 6

    /** Segments used to approximate a full circle. */
    private const val CIRCLE_SEGMENTS = 64

    private const val DEG = Math.PI / 180.0

    private class Tag(val code: Int, val value: String)

    private class Entity(val type: String) {
        val tags = mutableListOf<Tag>()
        val verts = mutableListOf<Entity>()

        fun first(code: Int): String? = tags.firstOrNull { it.code == code }?.value
        fun all(code: Int): List<String> = tags.filter { it.code == code }.map { it.value }

        fun double(code: Int, def: Double = 0.0): Double =
            first(code)?.trim()?.toDoubleOrNull() ?: def

        fun int(code: Int, def: Int = 0): Int =
            first(code)?.trim()?.toDoubleOrNull()?.toInt() ?: def

        fun string(code: Int): String? = first(code)?.trim()

        fun x(): Float = double(10).toFloat()
        fun y(): Float = double(20).toFloat()
        fun gx(code: Int): Float = double(code).toFloat()
        fun gy(code: Int): Float = double(code + 10).toFloat()
    }

    private class Block(val name: String, val baseX: Float, val baseY: Float, val entities: List<Entity>)

    /** Affine transform: x' = a*x + b*y + e, y' = c*x + d*y + f. */
    private class Xform(
        val a: Float = 1f, val b: Float = 0f,
        val c: Float = 0f, val d: Float = 1f,
        val e: Float = 0f, val f: Float = 0f,
    ) {
        fun mapX(x: Float, y: Float) = a * x + b * y + e
        fun mapY(x: Float, y: Float) = c * x + d * y + f
    }

    /**
     * Parses DXF text into a normalised [CadDrawing].
     *
     * @return null when the text contains no usable geometry.
     */
    fun read(text: String): CadDrawing? {
        val tags = tokenize(text)
        if (tags.isEmpty()) return null

        val blocks = LinkedHashMap<String, Block>()
        val model = mutableListOf<Entity>()
        partition(tags, blocks, model)
        if (model.isEmpty()) return null

        val raw = ArrayList<CadPolyline>(256)
        val budget = intArrayOf(MAX_VERTICES)
        emit(model, Xform(), blocks, raw, 0, budget)
        if (raw.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in raw) {
            for (i in p.xs.indices) {
                if (p.xs[i] < minX) minX = p.xs[i]
                if (p.xs[i] > maxX) maxX = p.xs[i]
                if (p.ys[i] < minY) minY = p.ys[i]
                if (p.ys[i] > maxY) maxY = p.ys[i]
            }
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null
        if (maxX <= minX || maxY <= minY) return null

        // Normalise into 0..1 keeping the aspect ratio, and flip Y (CAD up -> screen down).
        val span = max(maxX - minX, maxY - minY)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val normalised = raw.map { p ->
            val xs = FloatArray(p.xs.size) { i -> 0.5f + (p.xs[i] - cx) / span }
            val ys = FloatArray(p.ys.size) { i -> 0.5f - (p.ys[i] - cy) / span }
            CadPolyline(xs, ys, p.closed)
        }
        return CadDrawing(normalised, minX, minY, maxX, maxY)
    }

    /** Cheap sniff test so the UI can verify a file really is a DXF before parsing. */
    fun looksLikeDxf(head: String): Boolean {
        val h = head.uppercase()
        return h.contains("SECTION") && (h.contains("AC10") || h.contains("ENTITIES") || h.contains("HEADER"))
    }

    // ───────────────────────── tokenizer ─────────────────────────

    /**
     * DXF is a flat stream of (code, value) pairs, one per line. A line that is not an integer
     * code desynchronises the stream, which happens when a value itself looks like a lone number —
     * in that case we re-sync by skipping to the next line that does start with a valid code.
     */
    private fun tokenize(text: String): List<Tag> {
        val out = ArrayList<Tag>(text.length / 16 + 16)
        val lines = text.lineSequence().iterator()
        var pendingCode: Int? = null
        while (lines.hasNext()) {
            val line = lines.next()
            val code = pendingCode ?: line.trim().toIntOrNull()
            if (code == null) continue
            if (!lines.hasNext()) break
            out.add(Tag(code, lines.next()))
            pendingCode = null
        }
        return out
    }

    /**
     * Splits the tag stream into a block table plus model-space entities.
     *
     * Kept deliberately forgiving: any structural oddity just closes the current entity, so a
     * hand-edited or truncated file still yields the geometry that parsed cleanly.
     */
    private fun partition(
        tags: List<Tag>,
        blocks: MutableMap<String, Block>,
        model: MutableList<Entity>,
    ) {
        var i = 0
        val current = arrayOfNulls<Entity>(1)
        var blockName: String? = null
        var blockBaseX = 0f
        var blockBaseY = 0f
        var blockEntities: MutableList<Entity>? = null
        var polyline: Entity? = null

        fun flush() {
            val c = current[0] ?: return
            current[0] = null
            if (c.type == "VERTEX" || c.type == "BLOCK") return
            // Local snapshot: smart-casting the captured `var blockEntities` directly is rejected
            // by the compiler ("captured by a changing closure").
            val block = blockEntities
            if (block != null) block.add(c) else model.add(c)
        }

        while (i < tags.size) {
            val t = tags[i]
            if (t.code != 0) {
                val c = current[0]
                if (c != null) {
                    c.tags.add(t)
                    if (c.type == "BLOCK") {
                        when (t.code) {
                            2 -> blockName = t.value.trim()
                            10 -> blockBaseX = t.value.trim().toFloatOrNull() ?: 0f
                            20 -> blockBaseY = t.value.trim().toFloatOrNull() ?: 0f
                        }
                    }
                }
                i++
                continue
            }

            val name = t.value.trim()
            when (name) {
                "SECTION" -> {
                    flush()
                    val next = tags.getOrNull(i + 1)
                    if (next?.code == 2) i++
                }

                "ENDSEC" -> {
                    flush()
                    blockName = null
                    blockEntities = null
                    polyline = null
                }

                "BLOCK" -> {
                    flush()
                    blockName = null
                    blockBaseX = 0f
                    blockBaseY = 0f
                    blockEntities = mutableListOf()
                    current[0] = Entity("BLOCK")
                }

                "ENDBLK" -> {
                    flush()
                    val n = blockName
                    val list = blockEntities
                    if (n != null && list != null) blocks[n] = Block(n, blockBaseX, blockBaseY, list)
                    blockName = null
                    blockEntities = null
                }

                "VERTEX" -> {
                    // Vertices belong to the open POLYLINE, not to the model space.
                    polyline?.let { pl ->
                        val v = Entity("VERTEX")
                        pl.verts.add(v)
                        current[0] = v
                    } ?: run { current[0] = null }
                }

                "SEQEND" -> {
                    current[0] = null
                    polyline = null
                }

                else -> {
                    flush()
                    if (name.isEmpty()) {
                        i++
                        continue
                    }
                    val e = Entity(name)
                    if (name == "POLYLINE") polyline = e
                    current[0] = e
                }
            }
            i++
        }
        flush()
    }

    // ───────────────────────── geometry emission ─────────────────────────

    private fun emit(
        entities: List<Entity>,
        xf: Xform,
        blocks: Map<String, Block>,
        out: MutableList<CadPolyline>,
        depth: Int,
        budget: IntArray,
    ) {
        if (depth > MAX_DEPTH) return
        for (e in entities) {
            if (budget[0] <= 0) return
            when (e.type) {
                "LINE" -> addSegment(
                    out, xf,
                    e.gx(10), e.gy(10), e.gx(11), e.gy(11),
                )

                "LWPOLYLINE" -> {
                    val xs = e.all(10).mapNotNull { it.trim().toFloatOrNull() }
                    val ys = e.all(20).mapNotNull { it.trim().toFloatOrNull() }
                    val n = min(xs.size, ys.size)
                    if (n >= 2) {
                        val flat = FloatArray(n * 2)
                        for (k in 0 until n) {
                            flat[k * 2] = xf.mapX(xs[k], ys[k])
                            flat[k * 2 + 1] = xf.mapY(xs[k], ys[k])
                        }
                        addPoly(out, flat, (e.int(70) and 1) != 0, budget)
                    }
                }

                "POLYLINE" -> {
                    val verts = e.verts
                    if (verts.size >= 2) {
                        val flat = FloatArray(verts.size * 2)
                        for (k in verts.indices) {
                            val x = verts[k].gx(10)
                            val y = verts[k].gy(10)
                            flat[k * 2] = xf.mapX(x, y)
                            flat[k * 2 + 1] = xf.mapY(x, y)
                        }
                        addPoly(out, flat, (e.int(70) and 2) != 0, budget)
                    }
                }

                "CIRCLE", "ARC" -> {
                    val cx = e.gx(10)
                    val cy = e.gy(10)
                    val r = e.gx(40)
                    if (r > 1e-9f) {
                        val isArc = e.type == "ARC"
                        val a0 = if (isArc) e.double(50) else 0.0
                        var a1 = if (isArc) e.double(51) else 360.0
                        if (isArc && a1 <= a0) a1 += 360.0
                        val sweep = (a1 - a0)
                        val steps = max(8, (CIRCLE_SEGMENTS * abs(sweep) / 360.0).toInt())
                        val flat = FloatArray((steps + 1) * 2)
                        for (k in 0..steps) {
                            val ang = (a0 + sweep * k / steps) * DEG
                            val px = cx + (r * cos(ang)).toFloat()
                            val py = cy + (r * sin(ang)).toFloat()
                            flat[k * 2] = xf.mapX(px, py)
                            flat[k * 2 + 1] = xf.mapY(px, py)
                        }
                        addPoly(out, flat, !isArc, budget)
                    }
                }

                "ELLIPSE" -> {
                    val cx = e.gx(10)
                    val cy = e.gy(10)
                    val mx = e.gx(11)
                    val my = e.gy(11)
                    val ratio = e.double(40, 1.0).toFloat()
                    val t0 = e.double(41, 0.0)
                    val t1 = e.double(42, 2 * Math.PI)
                    val majLen = sqrt(mx * mx + my * my)
                    if (majLen > 1e-9f) {
                        val minorX = -my * ratio
                        val minorY = mx * ratio
                        val full = abs(t1 - t0) >= 2 * Math.PI - 1e-6
                        val steps = max(12, (CIRCLE_SEGMENTS * abs(t1 - t0) / (2 * Math.PI)).toInt())
                        val flat = FloatArray((steps + 1) * 2)
                        for (k in 0..steps) {
                            val t = t0 + (t1 - t0) * k / steps
                            val ct = cos(t).toFloat()
                            val st = sin(t).toFloat()
                            val px = cx + mx * ct + minorX * st
                            val py = cy + my * ct + minorY * st
                            flat[k * 2] = xf.mapX(px, py)
                            flat[k * 2 + 1] = xf.mapY(px, py)
                        }
                        addPoly(out, flat, full, budget)
                    }
                }

                "SOLID", "3DFACE", "TRACE" -> {
                    val codes = listOf(10 to 20, 11 to 21, 12 to 22, 13 to 23)
                    val pts = codes.mapNotNull { (xc, yc) ->
                        val sx = e.first(xc)?.trim()?.toFloatOrNull()
                        val sy = e.first(yc)?.trim()?.toFloatOrNull()
                        if (sx != null && sy != null) sx to sy else null
                    }
                    if (pts.size >= 3) {
                        val flat = FloatArray(pts.size * 2)
                        for (k in pts.indices) {
                            flat[k * 2] = xf.mapX(pts[k].first, pts[k].second)
                            flat[k * 2 + 1] = xf.mapY(pts[k].first, pts[k].second)
                        }
                        addPoly(out, flat, true, budget)
                    }
                }

                "POINT" -> {
                    val x = e.gx(10)
                    val y = e.gy(10)
                    addSegment(out, xf, x, y, x, y)
                }

                "SPLINE" -> {
                    val xs = e.all(10).mapNotNull { it.trim().toFloatOrNull() }
                    val ys = e.all(20).mapNotNull { it.trim().toFloatOrNull() }
                    val n = min(xs.size, ys.size)
                    if (n >= 2) {
                        val flat = FloatArray(n * 2)
                        for (k in 0 until n) {
                            flat[k * 2] = xf.mapX(xs[k], ys[k])
                            flat[k * 2 + 1] = xf.mapY(xs[k], ys[k])
                        }
                        addPoly(out, flat, false, budget)
                    }
                }

                "INSERT" -> {
                    val name = e.string(2)
                    val block = if (name != null) blocks[name] else null
                    if (block != null) {
                        val ix = e.gx(10)
                        val iy = e.gy(10)
                        val sx = e.double(41, 1.0).toFloat()
                        val sy = e.double(42, 1.0).toFloat()
                        val rot = (e.double(50) * DEG).toFloat()
                        val cs = cos(rot) * sx
                        val sn = sin(rot) * sx
                        val cs2 = cos(rot) * sy
                        val sn2 = sin(rot) * sy
                        // translate(ins) * rotate * scale * translate(-base)
                        val a = cs
                        val b = -sn2
                        val c = sn
                        val d = cs2
                        val ee = ix - (a * block.baseX + b * block.baseY)
                        val ff = iy - (c * block.baseX + d * block.baseY)
                        val inner = Xform(
                            a = xf.a * a + xf.b * c,
                            b = xf.a * b + xf.b * d,
                            c = xf.c * a + xf.d * c,
                            d = xf.c * b + xf.d * d,
                            e = xf.a * ee + xf.b * ff + xf.e,
                            f = xf.c * ee + xf.d * ff + xf.f,
                        )
                        emit(block.entities, inner, blocks, out, depth + 1, budget)
                    }
                }
            }
        }
    }

    private fun addSegment(
        out: MutableList<CadPolyline>,
        xf: Xform,
        x1: Float, y1: Float, x2: Float, y2: Float,
    ) {
        out.add(
            CadPolyline(
                floatArrayOf(xf.mapX(x1, y1), xf.mapX(x2, y2)),
                floatArrayOf(xf.mapY(x1, y1), xf.mapY(x2, y2)),
                false,
            ),
        )
    }

    private fun addPoly(
        out: MutableList<CadPolyline>,
        flat: FloatArray,
        closed: Boolean,
        budget: IntArray,
    ) {
        val n = flat.size / 2
        if (n < 2 || n > budget[0]) return
        budget[0] -= n
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (k in 0 until n) {
            xs[k] = flat[k * 2]
            ys[k] = flat[k * 2 + 1]
        }
        out.add(CadPolyline(xs, ys, closed))
    }
}
