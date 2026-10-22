package com.e5lab.accamera.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.domain.analysis.NoiseSourceAnalyzer
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.domain.cad.CadReader
import com.e5lab.accamera.domain.contour.ContourBuilder
import com.e5lab.accamera.domain.contour.HeatmapRenderer
import com.e5lab.accamera.domain.geom.PlotTransform
import com.e5lab.accamera.domain.interpolation.EnergyFieldInterpolator
import com.e5lab.accamera.domain.interpolation.FieldResult
import com.e5lab.accamera.domain.interpolation.PointValue
import java.io.File
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App(repo: DesktopRepository) {
    var currentProjectId by remember { mutableStateOf<Long?>(null) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (currentProjectId == null) {
                ProjectListScreen(repo) { currentProjectId = it }
            } else {
                ProjectDetailScreen(repo, currentProjectId!!) { currentProjectId = null }
            }
        }
    }
}

// ───────────────────────── Project list ─────────────────────────

// Material3's TopAppBar / TabRow are still marked @ExperimentalMaterial3Api, and opting in is an
// ERROR (not a warning) in this toolchain — without @OptIn the whole module fails to compile.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(repo: DesktopRepository, onOpen: (Long) -> Unit) {
    val projects by repo.projects.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AC-Camera · 项目列表") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = !showCreate }) { Text(if (showCreate) "收起" else "新建项目") }
                Button(onClick = {
                    // Runs inline on the AWT EDT; a background thread would deadlock on invokeAndWait.
                    val target = pickFileToSave("ac-camera-data.json")
                    if (target != null) {
                        runCatching { target.writeText(repo.exportJson()) }
                    }
                }) { Text("导出 JSON") }
                Button(onClick = {
                    val file = pickFileToOpen()
                    if (file != null) {
                        runCatching { repo.importJson(file.readText()) }
                    }
                }) { Text("导入 JSON") }
            }
            if (showCreate) {
                CreateProjectForm(repo) { showCreate = false; onOpen(it) }
            }
            Spacer(Modifier.size(12.dp))
            projects.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(
                            "网格 ${p.cols}×${p.rows} cell（${p.cols + 1}×${p.rows + 1} = ${p.pointCount} 个测点）· 间距 ${p.spacingX}×${p.spacingY} m · 底噪 ${"%.1f".format(p.backgroundNoiseDb)} dBA",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { onOpen(p.id) }) { Text("打开") }
                    Button(onClick = { repo.deleteProject(p.id) }) { Text("删除") }
                }
            }
            if (projects.isEmpty()) Text("还没有项目，点上方\"新建项目\"开始。", Modifier.padding(12.dp))
        }
    }
}

@Composable
fun CreateProjectForm(repo: DesktopRepository, onCreated: (Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cols by remember { mutableStateOf("8") }
    var rows by remember { mutableStateOf("6") }
    var sx by remember { mutableStateOf("2.0") }
    var sy by remember { mutableStateOf("2.0") }
    var bg by remember { mutableStateOf("0") }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("列数(cell)", cols) { cols = it }
            NumberField("行数(cell)", rows) { rows = it }
            NumberField("列间距(m)", sx) { sx = it }
            NumberField("行间距(m)", sy) { sy = it }
            NumberField("背景噪声(dBA)", bg) { bg = it }
        }
        Text(
            "列数/行数指网格单元数：10×10 = 10×10 个单元、11×11 = 121 个测点（测点位于网格线的交汇处）。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            val c = cols.toIntOrNull() ?: 8
            val r = rows.toIntOrNull() ?: 6
            val id = repo.createProject(
                name.ifBlank { "未命名项目" }, "", c, r,
                sx.toDoubleOrNull() ?: 2.0, sy.toDoubleOrNull() ?: 2.0, bg.toDoubleOrNull() ?: 0.0
            )
            onCreated(id)
        }) { Text("创建并打开") }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }) },
        label = { Text(label) },
        modifier = Modifier.size(120.dp, 64.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

// ───────────────────────── Project detail ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(repo: DesktopRepository, projectId: Long, onBack: () -> Unit) {
    val project = repo.getProject(projectId) ?: run { onBack(); return }
    val measurements by remember(projectId) { repo.measurements }.collectAsState()
    val ms = measurements[projectId] ?: emptyList()

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("数据录入", "等声压级图", "噪声源分析")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(project.name) }, navigationIcon = {
                Button(onClick = onBack) { Text("← 返回") }
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }) { Text(t) } }
            }
            when (tab) {
                0 -> DataEntryTab(repo, project, ms)
                1 -> ContourTab(repo, project, ms)
                2 -> AnalysisTab(repo, project, ms)
            }
        }
    }
}

// ───────────────────────── Data entry ─────────────────────────

@Composable
fun DataEntryTab(repo: DesktopRepository, project: Project, measurements: List<Measurement>) {
    val map = measurements.associateBy { "${it.col},${it.row}" }
    val corners = (project.cols + 1) * (project.rows + 1)
    // Lattice view: the numbers must sit on the grid-line CROSSINGS, not at the centre of a cell.
    var lattice by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "已测 ${measurements.size} / $corners 个测点。测点位于网格线的交汇处：" +
                "${project.cols + 1} 列 × ${project.rows + 1} 行，共 $corners 个。" +
                "背景噪声 = ${"%.1f".format(project.backgroundNoiseDb)} dBA。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { lattice = !lattice }) {
                Text(if (lattice) "切换为表格录入" else "切换为网格交汇录入")
            }
            Text(
                if (lattice) "每个输入框都落在网格线的交叉点上" else "表格行 r / 列 c 即交点编号 (c, r)",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(6.dp))
        if (lattice) {
            LatticeEntry(repo, project, map)
        } else {
            TableEntry(repo, project, map)
        }
    }
}

/**
 * Lattice-style entry: an evenly spaced matrix of inputs where each box represents ONE grid
 * intersection (c, r) — exactly like the measurement points on the plot. Crosshair lines are drawn
 * between the boxes so it is obvious the value belongs to a line crossing, not to a cell centre.
 */
@Composable
fun LatticeEntry(
    repo: DesktopRepository,
    project: Project,
    map: Map<String, Measurement>,
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val box = 84.dp
    Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
        Column {
            // Column index header (the c value of each intersection column)
            Row {
                Box(Modifier.size(box, 26.dp))
                for (c in 0..project.cols) {
                    Box(Modifier.size(box, 26.dp), contentAlignment = Alignment.Center) {
                        Text("c$c", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            for (r in 0..project.rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(box, 62.dp), contentAlignment = Alignment.Center) {
                        Text("r$r", style = MaterialTheme.typography.labelSmall)
                    }
                    for (c in 0..project.cols) {
                        val m = map["$c,$r"]
                        Box(Modifier.size(box, 62.dp), contentAlignment = Alignment.Center) {
                            CellInput(
                                initial = m?.spl?.let { "%.1f".format(it) } ?: "",
                                onCommit = { txt ->
                                    val v = txt.toDoubleOrNull()
                                    if (v != null) {
                                        repo.upsertMeasurement(
                                            Measurement(project.id, c, r, v, note = m?.note ?: "")
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Plain table entry (compass layout), one row per intersection row. */
@Composable
fun TableEntry(
    repo: DesktopRepository,
    project: Project,
    map: Map<String, Measurement>,
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
        Column {
            Row {
                Box(Modifier.size(60.dp, 26.dp))
                for (c in 0..project.cols) {
                    Box(Modifier.size(80.dp, 26.dp), contentAlignment = Alignment.Center) {
                        Text("列 $c", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            for (r in 0..project.rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(60.dp, 56.dp), contentAlignment = Alignment.Center) {
                        Text("行 $r", style = MaterialTheme.typography.labelSmall)
                    }
                    for (c in 0..project.cols) {
                        val m = map["$c,$r"]
                        CellInput(
                            initial = m?.spl?.let { "%.1f".format(it) } ?: "",
                            onCommit = { txt ->
                                val v = txt.toDoubleOrNull()
                                if (v != null) {
                                    repo.upsertMeasurement(
                                        Measurement(project.id, c, r, v, note = m?.note ?: "")
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One grid-intersection input.
 *
 * Commits on every valid keystroke (not only Enter): switching tabs used to discard whatever was
 * typed last, which looked like "the data I entered disappeared".
 */
@Composable
fun CellInput(initial: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        text,
        onValueChange = { txt ->
            // Accept digits / one dot / leading minus only, so a stray key cannot poison the value.
            if (txt.isEmpty() || txt.toDoubleOrNull() != null || txt == "-" || txt == ".") {
                text = txt
                txt.toDoubleOrNull()?.let { onCommit(txt) }
            }
        },
        modifier = Modifier.size(76.dp, 56.dp)
            .onKeyEvent { if (it.key == Key.Enter) { onCommit(text); true } else false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

// ───────────────────────── Contour ─────────────────────────

private val BAND_CHOICES = listOf(8, 16, 24, 32, 64, 128, 256)

/**
 * Coloured bands only become visually distinct once the palette colour ramp is quantised, so the
 * band count also drives the palette step count (clamped to a 64-step floor by the renderer so
 * coarse counts keep all of their colours).
 */
private fun quantizeStep(bands: Int): Int =
    bands.coerceIn(HeatmapRenderer.MIN_PALETTE_STEPS, HeatmapRenderer.MAX_PALETTE_STEPS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContourTab(repo: DesktopRepository, project: Project, measurements: List<Measurement>) {
    // The project flows back from the repository after every persist; keep a local override so a
    // settings change is never visually reverted by a stale emission.
    var overlayAlpha by remember { mutableFloatStateOf(project.overlayAlpha) }
    var colorBands by remember { mutableIntStateOf(project.contourColorBands.coerceAtLeast(2)) }
    var baseMapPath by remember { mutableStateOf(project.backgroundImagePath) }
    var transform by remember { mutableStateOf(PlotTransform.DEFAULT) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val imageMap = rememberBaseMapImage(baseMapPath)
    val cad = rememberCadDrawing(baseMapPath)
    val field = remember(project, measurements) { computeField(project, measurements) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = {
                // Runs inline on the AWT EDT — no invokeAndWait, which used to throw
                // "Cannot call invokeAndWait from the event dispatcher thread".
                DesktopBackdrop.choose { file ->
                    if (file == null) return@choose
                    busy = true
                    message = null
                    error = null
                    // Launch on the Compose UI context (rememberCoroutineScope) and hop to IO only
                    // for the blocking work. NEVER touch Dispatchers.Main on Desktop: unlike Android,
                    // the JVM runtime has no Main dispatcher module — it throws
                    // "Module with the Main dispatcher is missing" at runtime, exactly the error
                    // dialog seen when importing a base map. After withContext(Dispatchers.IO)
                    // returns, we are back on the UI context automatically, so state writes are safe.
                    scope.launch {
                        try {
                            // Do all blocking work on IO, then land back on the UI context to
                            // update state and persist (see the Dispatchers.Main note above).
                            val (resolved, detail) = withContext(Dispatchers.IO) {
                                val stored = DesktopBackdrop.store(file, project.id)
                                val done = if (DesktopBackdrop.isPdf(stored.absolutePath)) {
                                    DesktopBackdrop.renderPdfFirstPage(stored)
                                } else {
                                    stored
                                }
                                val what = when {
                                    DesktopBackdrop.isCad(done.absolutePath) -> {
                                        val d = CadReader.read(done.readText())
                                            ?: error("DXF 中未找到可用的线图")
                                        "CAD 线图 ${d.polygons.size} 段 / 约 ${d.vertexCount} 个顶点"
                                    }
                                    DesktopBackdrop.isPdf(stored.absolutePath) ->
                                        "PDF 已渲染第 1 页为位图"
                                    else -> "图片已导入"
                                }
                                done to what
                            }
                            baseMapPath = resolved.absolutePath
                            message = detail
                            repo.updateProject(project.copy(backgroundImagePath = resolved.absolutePath))
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text(if (busy) "导入中…" else "选择底图") }
            Text("透明度")
            Slider(
                overlayAlpha,
                { value ->
                    overlayAlpha = value
                    repo.updateProject(project.copy(overlayAlpha = value))
                },
                valueRange = 0.05f..1f, modifier = Modifier.weight(1f),
            )
            Text("${"%.2f".format(overlayAlpha)}")
        }
        if (message != null || error != null) {
            Text(
                error?.let { "✗ $it" } ?: "✓ ${message!!}",
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("色阶")
            BAND_CHOICES.forEach { b ->
                val selected = colorBands == b
                OutlinedButton(
                    onClick = {
                        colorBands = b
                        repo.updateProject(project.copy(contourColorBands = b))
                    },
                    modifier = Modifier.size(58.dp, 36.dp),
                ) {
                    Text(if (selected) "[$b]" else "$b", fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("视图")
            TextButton(onClick = { transform = transform.scaled(1.25f) }) { Text("放大 +") }
            TextButton(onClick = { transform = transform.scaled(1f / 1.25f) }) { Text("缩小 −") }
            TextButton(onClick = { transform = PlotTransform.DEFAULT }) { Text("适应网格") }
            Text(
                "缩放 ${"%.1f".format(transform.zoom)}×  ·  滚轮缩放 / 拖动平移",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(6.dp))
        if (field != null) {
            ContourCanvas(
                project, field, imageMap, cad, overlayAlpha, colorBands,
                transform = transform, onTransformChange = { transform = it },
                measurements = measurements,
                modifier = Modifier.weight(1f),
            )
            BandLegend(colorBands, Modifier.fillMaxWidth().padding(top = 6.dp))
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("请先在\"数据录入\"中填入测点数据。")
            }
        }
    }
}

fun computeField(project: Project, measurements: List<Measurement>): FieldResult? {
    val bg = project.backgroundNoiseDb
    if (measurements.isEmpty()) return null
    val pts = measurements.map {
        PointValue(project.xAt(it.col), project.yAt(it.row), (it.spl - bg).coerceAtLeast(0.0))
    }
    val spacing = (project.spacingX + project.spacingY) / 2.0
    val attenPerMeter = if (spacing > 0) 6.0 / spacing else 0.6
    val excess = EnergyFieldInterpolator.interpolate(
        pts, project.widthMeters, project.heightMeters, attenuationPerMeter = attenPerMeter,
    )
    val splValues = DoubleArray(excess.values.size) { i -> excess.values[i].coerceAtLeast(0.0) + bg }
    val splMin = splValues.minOrNull() ?: bg
    val splMax = splValues.maxOrNull() ?: bg
    return excess.copy(values = splValues, min = splMin, max = splMax)
}

/**
 * Interactive plot: world space is the surveyed rectangle with measurement points on the
 * grid-line INTERSECTIONS. Base map, heatmap, grid and dots all share one [PlotTransform], so the
 * base map can never drift out of proportion with the equal-SPL map.
 *
 * Desktop gestures: mouse wheel = zoom at cursor, drag = pan.
 */
@Composable
fun ContourCanvas(
    project: Project,
    field: FieldResult,
    imageMap: ImageBitmap?,
    cad: CadDrawing?,
    overlayAlpha: Float,
    colorBands: Int,
    transform: PlotTransform,
    onTransformChange: (PlotTransform) -> Unit,
    measurements: List<Measurement> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val levels = remember(field) { ContourBuilder.defaultLevels(field.min, field.max) }
    val grid = remember(field, colorBands) {
        HeatmapRenderer.renderColorGrid(field, levels, colorBands, quantizeStep(colorBands))
    }
    val planRef = project.planReferenceMeters
    val nCols = project.cols.coerceAtLeast(1)
    val nRows = project.rows.coerceAtLeast(1)
    // Hoisted out of the draw scope: remember() must run during composition, not drawing.
    val pointValues = remember(measurements) {
        measurements.associate { (it.col to it.row) to it.spl }
    }
    val flagged = remember(measurements) {
        measurements.filter { it.isNoiseSource }.map { it.col to it.row }.toSet()
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragAccum by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier
            .fillMaxSize()
            .padding(8.dp)
            .onGloballyPositioned { size = it.size }
            .pointerInput(nCols, nRows) {
                detectDragGestures(
                    onDragStart = { dragAccum = Offset.Zero },
                    onDrag = { change, drag ->
                        change.consume()
                        dragAccum += drag
                        if (dragAccum.getDistance() < 2f) return@detectDragGestures
                        val viewport = plotViewport(size, nCols, nRows, PAD)
                        if (viewport.width <= 0f || viewport.height <= 0f) return@detectDragGestures
                        val d = dragAccum
                        dragAccum = Offset.Zero
                        onTransformChange(
                            transform.panBy(d.x / viewport.width, d.y / viewport.height)
                        )
                    },
                )
            }
            // Mouse wheel zooms around the cursor (Compose Desktop delivers it as a pointer scroll).
            .pointerInput(nCols, nRows) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val scroll = change.scrollDelta.y
                        if (scroll != 0f) {
                            change.consume()
                            val viewport = plotViewport(size, nCols, nRows, PAD)
                            if (viewport.width > 0f && viewport.height > 0f) {
                                val ax = ((change.position.x - viewport.left) / viewport.width)
                                    .coerceIn(0f, 1f)
                                val ay = ((change.position.y - viewport.top) / viewport.height)
                                    .coerceIn(0f, 1f)
                                val factor = if (scroll < 0) 1.15f else 1f / 1.15f
                                onTransformChange(transform.zoomAt(ax, ay, factor))
                            }
                        }
                    }
                }
            },
    ) {
        if (size == IntSize.Zero) return@Canvas
        drawSurveyWorld(
            size = size,
            nCols = nCols,
            nRows = nRows,
            transform = transform,
            imageMap = imageMap,
            cad = cad,
            grid = grid,
            field = field,
            project = project,
            overlayAlpha = overlayAlpha,
            planRef = planRef,
            pointValues = pointValues,
            flagged = flagged,
        )
    }
}

private val PAD = 24f

/** Shared painter so the on-screen plot and any future export cannot diverge. */
fun DrawScope.drawSurveyWorld(
    size: IntSize,
    nCols: Int,
    nRows: Int,
    transform: PlotTransform,
    imageMap: ImageBitmap?,
    cad: CadDrawing?,
    grid: IntArray,
    field: FieldResult,
    project: Project,
    overlayAlpha: Float,
    planRef: Double?,
    drawPoints: Boolean = true,
    pointValues: Map<Pair<Int, Int>, Double> = emptyMap(),
    flagged: Set<Pair<Int, Int>> = emptySet(),
) {
    val viewport = plotViewport(size, nCols, nRows, PAD)
    val cell = min(viewport.width / nCols, viewport.height / nRows)
    if (cell <= 0f) return
    val t = transform.clamped()
    val world = worldRect(viewport, nCols, nRows, cell, t)

    clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
        // 1. base map (vector CAD preferred over a raster photo)
        if (cad != null && !cad.isEmpty) {
            drawCad(cad, world)
        } else if (imageMap != null && imageMap.width > 0 && imageMap.height > 0) {
            val dst = containFit(world, imageMap.width.toFloat() / imageMap.height.toFloat())
            drawImage(
                imageMap,
                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                dstSize = IntSize(
                    dst.width.toInt().coerceAtLeast(1),
                    dst.height.toInt().coerceAtLeast(1),
                ),
            )
        }

        // 2. SPL heatmap over the surveyed area
        val cw = world.width / field.resX
        val ch = world.height / field.resY
        for (gy in 0 until field.resY) {
            for (gx in 0 until field.resX) {
                val color = Color(grid[gy * field.resX + gx]).copy(alpha = overlayAlpha)
                drawRect(
                    color,
                    Offset(world.left + gx * cw, world.top + gy * ch),
                    Size(cw + 1f, ch + 1f),
                )
            }
        }

        // 3. grid lattice + 4. points on the intersections
        val gridLine = Color.White.copy(alpha = 0.35f)
        for (c in 0..nCols) {
            val x = world.left + c * world.width / nCols
            drawLine(gridLine, Offset(x, world.top), Offset(x, world.bottom), strokeWidth = 0.6f)
        }
        for (r in 0..nRows) {
            val y = world.top + r * world.height / nRows
            drawLine(gridLine, Offset(world.left, y), Offset(world.right, y), strokeWidth = 0.6f)
        }
        if (drawPoints) {
            val range = (field.max - field.min).let { if (it <= 1e-6) 1.0 else it }
            for (c in 0..nCols) for (r in 0..nRows) {
                val px = world.left + c * world.width / nCols
                val py = world.top + r * world.height / nRows
                val v = pointValues[c to r]
                val color = if (v != null) {
                    Color(HeatmapRenderer.colormap(((v - field.min) / range).coerceIn(0.0, 1.0)))
                } else {
                    Color.Gray.copy(alpha = 0.28f)
                }
                val radius = if (v != null) 7f else 3.5f
                drawCircle(color, radius, Offset(px, py))
                if (v != null) {
                    drawCircle(Color.White.copy(alpha = 0.7f), radius, Offset(px, py), style = Stroke(1.5f))
                }
                if (flagged.contains(c to r)) {
                    drawCircle(Color.Black, radius + 6f, Offset(px, py), style = Stroke(3f))
                }
            }
            // Crosshair tick at every grid-line intersection: makes it visually obvious that the
            // measurement points sit ON the crossings, and where an unmeasured crossing is.
            val tick = Color.White.copy(alpha = 0.55f)
            for (c in 0..nCols) for (r in 0..nRows) {
                if (pointValues[c to r] != null) continue
                val px = world.left + c * world.width / nCols
                val py = world.top + r * world.height / nRows
                drawLine(tick, Offset(px - 4f, py), Offset(px + 4f, py), strokeWidth = 1f)
                drawLine(tick, Offset(px, py - 4f), Offset(px, py + 4f), strokeWidth = 1f)
            }
        }
    }
    drawScaleBar(viewport, planRef, t.zoom)
}

/** Draws a parsed DXF: coordinates are already normalised to 0..1 with Y flipped. */
fun DrawScope.drawCad(cad: CadDrawing, world: Rect) {
    val color = Color(0xFF1F3B66)
    for (poly in cad.polygons) {
        val n = min(poly.xs.size, poly.ys.size)
        if (n < 2) continue
        val path = Path()
        path.moveTo(world.left + poly.xs[0] * world.width, world.top + poly.ys[0] * world.height)
        for (i in 1 until n) {
            path.lineTo(world.left + poly.xs[i] * world.width, world.top + poly.ys[i] * world.height)
        }
        if (poly.closed) path.close()
        drawPath(path, color, style = Stroke(1.1f))
    }
}

fun containFit(outer: Rect, aspect: Float): Rect {
    if (aspect <= 0f) return outer
    val w = min(outer.width, outer.height * aspect)
    val h = w / aspect
    val left = outer.left + (outer.width - w) / 2f
    val top = outer.top + (outer.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

fun plotViewport(size: IntSize, nCols: Int, nRows: Int, pad: Float): Rect {
    val availW = (size.width - 2 * pad).coerceAtLeast(1f)
    val availH = (size.height - 2 * pad).coerceAtLeast(1f)
    val cell = min(availW / nCols, availH / nRows)
    val w = cell * nCols
    val h = cell * nRows
    val left = pad + (availW - w) / 2f
    val top = pad + (availH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

fun worldRect(viewport: Rect, nCols: Int, nRows: Int, cell: Float, t: PlotTransform): Rect {
    val (x0, y0) = t.worldToPicture(0f, 0f)
    val (x1, y1) = t.worldToPicture(1f, 1f)
    return Rect(
        viewport.left + x0 * nCols * cell,
        viewport.top + y0 * nRows * cell,
        viewport.left + x1 * nCols * cell,
        viewport.top + y1 * nRows * cell,
    )
}

@Composable
fun BandLegend(colorBands: Int, modifier: Modifier = Modifier) {
    val colors = remember(colorBands) {
        HeatmapRenderer.bandColors(colorBands, quantizeStep(colorBands)).map { Color(it) }
    }
    Column(modifier) {
        Text(
            "色阶 $colorBands 级（声压级由低到高：蓝 → 青 → 绿 → 黄 → 红）",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(Modifier.fillMaxWidth().size(0.dp, 14.dp)) {
            colors.forEach { c -> Box(Modifier.weight(1f).fillMaxSize().background(c)) }
        }
    }
}

fun loadBaseMap(path: String?): ImageBitmap? {
    if (path == null) return null
    if (DesktopBackdrop.isCad(path) || path.lowercase().endsWith(".pdf")) return null
    // PDFs are pre-rendered to a PNG next to the source by DesktopBackdrop.
    val file = File(path).takeIf { it.isFile } ?: return null
    return runCatching { loadImageBitmap(file.inputStream()) }.getOrNull()
}

/** Loads a raster base map on the IO dispatcher; a CAD file yields null and is drawn as paths. */
@Composable
fun rememberBaseMapImage(path: String?): ImageBitmap? {
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        image = if (path == null) null else withContext(Dispatchers.IO) { loadBaseMap(path) }
    }
    return image
}

@Composable
fun rememberCadDrawing(path: String?): CadDrawing? {
    var drawing by remember(path) { mutableStateOf<CadDrawing?>(null) }
    LaunchedEffect(path) {
        drawing = if (path == null || !DesktopBackdrop.isCad(path)) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { CadReader.read(File(path).readText()) }.getOrNull()
            }
        }
    }
    return drawing
}

fun DrawScope.drawScaleBar(rect: Rect, planReferenceMeters: Double?, zoom: Float = 1f) {
    val ref = planReferenceMeters ?: return
    if (ref <= 0) return
    val pxPerMeter = rect.width / ref.toFloat()
    if (pxPerMeter <= 0) return
    val trueScale = pxPerMeter / if (zoom > 0f) zoom else 1f
    val nice = niceNumber(100f / trueScale)
    val barPx = nice * trueScale
    val y = rect.bottom - 16f
    drawLine(Color.White, Offset(rect.left + 12f, y), Offset(rect.left + 12f + barPx, y), strokeWidth = 4.5f)
    drawLine(Color.Black, Offset(rect.left + 12f, y), Offset(rect.left + 12f + barPx, y), strokeWidth = 2f)
    drawLine(Color.Black, Offset(rect.left + 12f, y - 6f), Offset(rect.left + 12f, y + 6f), strokeWidth = 2f)
    drawLine(Color.Black, Offset(rect.left + 12f + barPx, y - 6f), Offset(rect.left + 12f + barPx, y + 6f), strokeWidth = 2f)
}

fun niceNumber(x: Float): Float {
    if (x <= 0) return 1f
    val exp = floor(log10(x))
    val base = 10f.pow(exp)
    val f = x / base
    val nf = when {
        f < 1.5f -> 1f
        f < 3.5f -> 2f
        f < 7.5f -> 5f
        else -> 10f
    }
    return nf * base
}

/** Open a save dialog and return the chosen destination file (null if cancelled). */
fun pickFileToSave(defaultName: String): File? {
    var result: File? = null
    val task = Runnable {
        val fc = javax.swing.JFileChooser().apply {
            dialogTitle = "导出 AC-Camera 数据 (JSON)"
            selectedFile = File(defaultName)
        }
        if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            result = fc.selectedFile
        }
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run()
    else javax.swing.SwingUtilities.invokeAndWait(task)
    return result
}

/** Open an open dialog and return the chosen source file (null if cancelled). */
fun pickFileToOpen(): File? {
    var result: File? = null
    val task = Runnable {
        val fc = javax.swing.JFileChooser().apply { dialogTitle = "导入 AC-Camera 数据 (JSON)" }
        if (fc.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            result = fc.selectedFile
        }
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run()
    else javax.swing.SwingUtilities.invokeAndWait(task)
    return result
}

// ───────────────────────── Analysis ─────────────────────────

@Composable
fun AnalysisTab(repo: DesktopRepository, project: Project, measurements: List<Measurement>) {
    val sources = remember(project, measurements) {
        NoiseSourceAnalyzer.detect(project, measurements, 0.0, project.backgroundNoiseDb)
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("噪声源分析（局部极大值，按声压级降序）", fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        if (sources.isEmpty()) {
            Text("暂无符合条件的噪声源。请先录入数据，或提高阈值。")
        } else {
            sources.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(6.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                    Text("(${s.col}, ${s.row})  @ (${"%.1f".format(s.x)} m, ${"%.1f".format(s.y)} m)  =  ${"%.1f".format(s.spl)} dBA")
                }
            }
        }
    }
}

