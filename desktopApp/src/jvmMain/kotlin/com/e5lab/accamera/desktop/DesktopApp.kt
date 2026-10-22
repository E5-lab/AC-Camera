package com.e5lab.accamera.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.domain.analysis.NoiseSourceAnalyzer
import com.e5lab.accamera.domain.contour.ContourBuilder
import com.e5lab.accamera.domain.contour.HeatmapRenderer
import com.e5lab.accamera.domain.interpolation.EnergyFieldInterpolator
import com.e5lab.accamera.domain.interpolation.FieldResult
import com.e5lab.accamera.domain.interpolation.PointValue
import java.awt.EventQueue
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class Screen { List }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(repo: DesktopRepository, onOpen: (Long) -> Unit) {
    val projects by repo.projects.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("AC-Camera · 项目列表") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = !showCreate }) { Text(if (showCreate) "收起" else "新建项目") }
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        pickFileToSave("ac-camera-data.json")?.let { file ->
                            runCatching { file.writeText(repo.exportJson()) }
                        }
                    }
                }) { Text("导出 JSON") }
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        pickFileToOpen()?.let { file ->
                            runCatching { repo.importJson(file.readText()) }
                        }
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
                        Text("网格 ${p.cols}×${p.rows}  ·  间距 ${p.spacingX}×${p.spacingY} m  ·  底噪 ${"%.1f".format(p.backgroundNoiseDb)} dBA",
                            style = MaterialTheme.typography.bodySmall)
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
            NumberField("列数", cols) { cols = it }
            NumberField("行数", rows) { rows = it }
            NumberField("列间距(m)", sx) { sx = it }
            NumberField("行间距(m)", sy) { sy = it }
            NumberField("背景噪声(dBA)", bg) { bg = it }
        }
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
        value, { onChange(it.filter { it.isDigit() || it == '.' || it == '-' }) },
        label = { Text(label) },
        modifier = Modifier.size(110.dp, 64.dp),
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
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("逐格点录入声压级(dB)。可粘贴/导入手机端数据。背景噪声 = ${"%.1f".format(project.backgroundNoiseDb)} dBA。",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
            Column {
                for (r in 0 until project.rows) {
                    Row {
                        for (c in 0 until project.cols) {
                            val key = "$c,$r"
                            val m = map[key]
                            CellInput(
                                initial = m?.spl?.let { "%.1f".format(it) } ?: "",
                                isSource = m?.isNoiseSource == true,
                                onCommit = { txt ->
                                    val v = txt.toDoubleOrNull()
                                    if (v != null) {
                                        repo.upsertMeasurement(
                                            Measurement(project.id, c, r, v, note = m?.note ?: "")
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CellInput(initial: String, isSource: Boolean, onCommit: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        text,
        onValueChange = { text = it },
        modifier = Modifier.size(76.dp, 56.dp)
            .onKeyEvent { if (it.key == Key.Enter) { onCommit(text); true } else false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

// ───────────────────────── Contour ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContourTab(repo: DesktopRepository, project: Project, measurements: List<Measurement>) {
    var overlayAlpha by remember { mutableFloatStateOf(project.overlayAlpha) }
    var colorBands by remember { mutableIntStateOf(project.contourColorBands) }
    var baseMapPath by remember { mutableStateOf(project.backgroundImagePath) }
    var baseMap by remember(baseMapPath) { mutableStateOf(loadBaseMap(baseMapPath)) }
    val bandOptions = listOf(8, 16, 32, 64, 128, 256)

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val path = pickImageFile()
                if (path != null) { baseMapPath = path; baseMap = loadBaseMap(path) }
            }) { Text("选择底图") }
            Text("透明度")
            Slider(overlayAlpha, { overlayAlpha = it; repo.updateProject(project.copy(overlayAlpha = it)) },
                valueRange = 0.1f..1f, modifier = Modifier.weight(1f))
            Text("${"%.2f".format(overlayAlpha)}")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("色阶")
            bandOptions.forEach { b ->
                Button(onClick = { colorBands = b; repo.updateProject(project.copy(contourColorBands = b)) },
                    modifier = Modifier.size(64.dp, 40.dp)) { Text("$b") }
            }
        }
        Spacer(Modifier.size(8.dp))
        val field = remember(project, measurements, overlayAlpha, colorBands) {
            computeField(project, measurements)
        }
        if (field != null) {
            ContourCanvas(project, field, baseMap, overlayAlpha, colorBands)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请先在\"数据录入\"中填入测点数据。") }
        }
    }
}

fun computeField(project: Project, measurements: List<Measurement>): FieldResult? {
    val bg = project.backgroundNoiseDb
    val pts = measurements.map { PointValue(project.xAt(it.col), project.yAt(it.row), (it.spl - bg).coerceAtLeast(0.0)) }
    if (pts.isEmpty()) return null
    val excess = EnergyFieldInterpolator.interpolate(pts, project.widthMeters, project.heightMeters)
    val splValues = DoubleArray(excess.values.size) { i -> excess.values[i] + bg }
    val splMin = splValues.minOrNull() ?: bg
    val splMax = splValues.maxOrNull() ?: bg
    return excess.copy(values = splValues, min = splMin, max = splMax)
}

@Composable
fun ContourCanvas(
    project: Project,
    field: FieldResult,
    baseMap: ImageBitmap?,
    overlayAlpha: Float,
    colorBands: Int,
) {
    val levels = remember(field) { ContourBuilder.defaultLevels(field.min, field.max) }
    val grid = remember(field, colorBands) { HeatmapRenderer.renderColorGrid(field, levels, colorBands) }
    val planRef = project.planReferenceMeters

    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val pad = 24f
        val rect = plotRect(size.width, size.height, pad, baseMap)
        if (baseMap != null) {
            drawImage(baseMap, IntOffset(rect.left.toInt(), rect.top.toInt()), IntSize(rect.width.toInt(), rect.height.toInt()))
        }
        val cw = rect.width / field.resX
        val ch = rect.height / field.resY
        for (gy in 0 until field.resY) for (gx in 0 until field.resX) {
            val color = Color(grid[gy * field.resX + gx]).copy(alpha = overlayAlpha)
            drawRect(color, Offset(rect.left + gx * cw, rect.top + gy * ch), Size(cw + 1f, ch + 1f))
        }
        // grid + points handled by caller data not available here; draw scale bar
        drawScaleBar(rect, planRef)
    }
}

fun DrawScope.plotRect(sizeW: Float, sizeH: Float, pad: Float, bg: ImageBitmap?): androidx.compose.ui.geometry.Rect {
    val availW = (sizeW - 2 * pad).coerceAtLeast(1f)
    val availH = (sizeH - 2 * pad).coerceAtLeast(1f)
    return if (bg != null && bg.width > 0 && bg.height > 0) {
        val planAspect = bg.width.toFloat() / bg.height.toFloat()
        val (w, h) = if (planAspect > availW / availH) availW to (availW / planAspect) else (availH * planAspect) to availH
        val left = pad + (availW - w) / 2f
        val top = pad + (availH - h) / 2f
        androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
    } else {
        androidx.compose.ui.geometry.Rect(pad, pad, pad + availW, pad + availH)
    }
}

fun DrawScope.drawScaleBar(rect: androidx.compose.ui.geometry.Rect, planReferenceMeters: Double?) {
    val ref = planReferenceMeters ?: return
    if (ref <= 0) return
    val pxPerMeter = rect.width / ref.toFloat()
    if (pxPerMeter <= 0) return
    val targetPx = 100f
    val nice = niceNumber(targetPx / pxPerMeter)
    val barPx = nice * pxPerMeter
    val y = rect.bottom - 16f
    drawLine(Color.Black, Offset(rect.left + 12f, y), Offset(rect.left + 12f + barPx, y), strokeWidth = 2.5f)
    drawLine(Color.Black, Offset(rect.left + 12f, y - 6f), Offset(rect.left + 12f, y + 6f), strokeWidth = 2.5f)
    drawLine(Color.Black, Offset(rect.left + 12f + barPx, y - 6f), Offset(rect.left + 12f + barPx, y + 6f), strokeWidth = 2.5f)
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

fun loadBaseMap(path: String?): ImageBitmap? {
    if (path == null) return null
    return runCatching {
        // loadImageBitmap (androidx.compose.ui.res) decodes an InputStream into a Compose
        // ImageBitmap using the bundled Skia backend — no extra skiko AWT dependency needed.
        loadImageBitmap(File(path).inputStream())
    }.getOrNull()
}

fun pickImageFile(): String? {
    var result: String? = null
    EventQueue.invokeAndWait {
        val fc = JFileChooser()
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = fc.selectedFile.absolutePath
        }
    }
    return result
}

/** Open a save dialog and return the chosen destination file (null if cancelled). */
fun pickFileToSave(defaultName: String): File? {
    var result: File? = null
    EventQueue.invokeAndWait {
        val fc = JFileChooser().apply {
            dialogTitle = "导出 AC-Camera 数据 (JSON)"
            selectedFile = File(defaultName)
        }
        if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = fc.selectedFile
        }
    }
    return result
}

/** Open an open dialog and return the chosen source file (null if cancelled). */
fun pickFileToOpen(): File? {
    var result: File? = null
    EventQueue.invokeAndWait {
        val fc = JFileChooser().apply { dialogTitle = "导入 AC-Camera 数据 (JSON)" }
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = fc.selectedFile
        }
    }
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
