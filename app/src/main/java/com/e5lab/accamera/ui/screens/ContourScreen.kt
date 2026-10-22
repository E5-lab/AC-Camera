package com.e5lab.accamera.ui.screens

import android.graphics.Bitmap as AndroidBitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.domain.contour.ContourBuilder
import com.e5lab.accamera.domain.contour.HeatmapRenderer
import com.e5lab.accamera.domain.geom.PlotTransform
import com.e5lab.accamera.domain.interpolation.EnergyFieldInterpolator
import com.e5lab.accamera.domain.interpolation.FieldResult
import com.e5lab.accamera.domain.interpolation.PointValue
import com.e5lab.accamera.ui.components.ContourPlot
import com.e5lab.accamera.ui.components.rememberCadDrawing
import com.e5lab.accamera.ui.components.rememberFileImageBitmap
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val COLOR_BAND_CHOICES = listOf(8, 16, 24, 32, 64, 128, 256)

/**
 * Coloured bands only become visually distinct once the palette colour ramp is quantised, so the
 * band count also drives the palette step count. HeatmapRenderer clamps the step floor to 64 so
 * coarse counts (8/16/24/32) keep their full set of distinct colours.
 */
private fun colorQuantizeStep(bands: Int): Int =
    bands.coerceIn(HeatmapRenderer.MIN_PALETTE_STEPS, HeatmapRenderer.MAX_PALETTE_STEPS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContourScreen(nav: NavHostController, projectId: Long) {
    val context = LocalContext.current
    val app = context.applicationContext as ACameraApplication
    val vm: ProjectViewModel = viewModel(
        key = projectId.toString(),
        factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ProjectViewModel(app, projectId) as T
        },
    )
    val project by vm.project.collectAsStateWithLifecycle(initialValue = null)
    val measurements by vm.measurements.collectAsStateWithLifecycle(initialValue = emptyList())

    // Base-map assets are keyed by PATH (not by the whole project): a project emission caused by a
    // slider drag must not re-decode a multi-megapixel plan or re-parse the DXF.
    val bgPath = project?.backgroundImagePath
    val baseMap = rememberFileImageBitmap(bgPath)
    val cadDrawing = rememberCadDrawing(bgPath)
    // Local overrides survive project re-emissions (the DB flow re-emits on every table change and
    // an in-flight update can still carry the PREVIOUS colour-band / alpha value), so a tap or a
    // slider drag is never silently reverted to the old value.
    var alphaOverride by remember { mutableStateOf<Float?>(null) }
    val overlayAlpha = alphaOverride ?: project?.overlayAlpha ?: 0.72f
    var bandsOverride by remember { mutableStateOf<Int?>(null) }
    val colorBands = bandsOverride ?: project?.contourColorBands ?: 32
    // Interactive zoom / pan of the plot (also scales the base map, keeping both in proportion).
    var transform by remember { mutableStateOf(PlotTransform.DEFAULT) }
    // Independent base-map alignment (overrides survive project re-emissions, like alpha/bands).
    // Lets the user resize / slide ONLY the plan so its equipment lines up with the grid.
    var bmScaleOverride by remember { mutableStateOf<Float?>(null) }
    var bmOffXOverride by remember { mutableStateOf<Float?>(null) }
    var bmOffYOverride by remember { mutableStateOf<Float?>(null) }
    val bmScale = (bmScaleOverride ?: project?.baseMapScale ?: 1f).coerceIn(0.2f, 5f)
    val bmOffX = (bmOffXOverride ?: project?.baseMapOffsetX ?: 0f).coerceIn(-0.5f, 0.5f)
    val bmOffY = (bmOffYOverride ?: project?.baseMapOffsetY ?: 0f).coerceIn(-0.5f, 0.5f)

    fun persistBaseMapAlign() {
        val p = project ?: return
        vm.updateProject(
            p.copy(baseMapScale = bmScale, baseMapOffsetX = bmOffX, baseMapOffsetY = bmOffY),
        )
    }

    val (field, bitmap) = rememberContour(project, measurements, colorBands)
    val pointValues = remember(measurements) { measurements.associateBy({ it.col to it.row }, { it.spl }) }
    val flagged = remember(measurements) { measurements.filter { it.isNoiseSource }.map { it.col to it.row }.toSet() }

    val minV = field?.min ?: measurements.minOfOrNull { it.spl } ?: 0.0
    val maxV = field?.max ?: measurements.maxOfOrNull { it.spl } ?: 100.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("等声压级图") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (project == null) {
            Text("加载中…", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val p = project!!
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (measurements.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请先在「数据输入」中录入至少 3 个测点，再生成等声压级曲线。")
                }
                return@Column
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                ContourPlot(
                    cols = p.cols,
                    rows = p.rows,
                    bitmap = bitmap,
                    pointValues = pointValues,
                    flagged = flagged,
                    minValue = minV,
                    maxValue = maxV,
                    background = baseMap,
                    overlayAlpha = overlayAlpha,
                    planReferenceMeters = p.planReferenceMeters,
                    transform = transform,
                    onTransformChange = { transform = it },
                    cadDrawing = cadDrawing,
                    cadScaleToGrid = p.planReferenceMeters != null,
                    baseMapScale = bmScale,
                    baseMapOffsetX = bmOffX,
                    baseMapOffsetY = bmOffY,
                )
            }
            // Zoom / pan controls — reaching here requires interactive plot gestures.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("缩放 ${"%.1f".format(transform.zoom)}×", style = MaterialTheme.typography.labelMedium)
                SmallButton("+") { transform = transform.scaled(1.25f) }
                SmallButton("−") { transform = transform.scaled(1f / 1.25f) }
                SmallButton("适应网格") { transform = PlotTransform.DEFAULT }
                Text(
                    "双指缩放 / 拖动平移",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Independent base-map alignment: resize / slide ONLY the plan against the grid so the
            // two layers line up. Changes persist to the project immediately.
            if (p.backgroundImagePath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "底图对齐",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SmallButton("底图+") {
                        bmScaleOverride = bmScale * 1.1f; persistBaseMapAlign()
                    }
                    SmallButton("底图−") {
                        bmScaleOverride = bmScale / 1.1f; persistBaseMapAlign()
                    }
                    SmallButton("←") { bmOffXOverride = bmOffX - 0.02f; persistBaseMapAlign() }
                    SmallButton("→") { bmOffXOverride = bmOffX + 0.02f; persistBaseMapAlign() }
                    SmallButton("↑") { bmOffYOverride = bmOffY - 0.02f; persistBaseMapAlign() }
                    SmallButton("↓") { bmOffYOverride = bmOffY + 0.02f; persistBaseMapAlign() }
                    SmallButton("重置") {
                        bmScaleOverride = 1f; bmOffXOverride = 0f; bmOffYOverride = 0f
                        persistBaseMapAlign()
                    }
                }
            }
            if (p.backgroundImagePath != null && baseMap == null && cadDrawing == null) {
                Text(
                    "底图未能显示：该文件可能不是受支持的图片 / PDF / DXF，请到「网格设置」重新导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Base-map / overlay controls
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("声场图层透明度", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("${"%.0f".format(overlayAlpha * 100)}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
                Slider(
                    value = overlayAlpha,
                    onValueChange = { alphaOverride = it },
                    onValueChangeFinished = {
                        vm.updateProject(p.copy(overlayAlpha = overlayAlpha))
                    },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "调低透明度可看清底图上的设备；调高则声场色彩更实。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        "色阶",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        COLOR_BAND_CHOICES.forEach { bands ->
                            FilterChip(
                                selected = colorBands == bands,
                                onClick = {
                                    bandsOverride = bands
                                    vm.updateProject(p.copy(contourColorBands = bands))
                                },
                                label = { Text("$bands", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                Text(
                    "色阶数量越少色块越大（便于分区读数），越多过渡越细腻。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                p.planReferenceMeters?.let { ref ->
                    Text(
                        "底图参照宽度 $ref m（已按真实比例显示并附比例尺）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (p.backgroundNoiseDb > 0) {
                    Text(
                        "底噪 ${"%.1f".format(p.backgroundNoiseDb)} dBA 已作为全图下限参与计算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Legend
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                Text("声压级 (dB)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                BandLegend(colorBands = colorBands, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("%.0f".format(minV), style = MaterialTheme.typography.labelSmall)
                    Text("%.0f".format((minV + maxV) / 2), style = MaterialTheme.typography.labelSmall)
                    Text("%.0f".format(maxV), style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "黑圈标记 = 已识别的疑似噪声源。声场按「测点声源能量叠加 + 距离衰减」模型计算，等值线（等声压级曲线）由行进方块法生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberContour(
    project: Project?,
    measurements: List<com.e5lab.accamera.data.model.Measurement>,
    colorBands: Int,
): Pair<FieldResult?, androidx.compose.ui.graphics.ImageBitmap?> {
    var result by remember {
        mutableStateOf(
            Pair<FieldResult?, androidx.compose.ui.graphics.ImageBitmap?>(null, null),
        )
    }
    LaunchedEffect(project, measurements, colorBands) {
        if (project == null || measurements.isEmpty()) {
            result = null to null
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            val bg = project.backgroundNoiseDb
            // Radiation / energy-superposition model: each reading contributes its excess over the
            // background floor, decaying with distance. High-energy points dominate and decay
            // outward; low-energy points contribute almost nothing, so quiet areas settle at the
            // floor instead of producing false "radiation" rings (the IDW exact-interpolation
            // artifact).
            val pts = measurements.map {
                PointValue(
                    project.xAt(it.col),
                    project.yAt(it.row),
                    (it.spl - bg).coerceAtLeast(0.0),
                )
            }
            // 6 dB decay per grid spacing keeps each source's influence a few cells wide.
            val spacing = (project.spacingX + project.spacingY) / 2.0
            val attenPerMeter = if (spacing > 0) 6.0 / spacing else 0.6
            val excess = EnergyFieldInterpolator.interpolate(
                pts,
                project.widthMeters,
                project.heightMeters,
                attenuationPerMeter = attenPerMeter,
            )
            // Clamp the excess at 0 so the background noise is the hard floor of the whole map.
            val splValues = DoubleArray(excess.values.size) { i -> excess.values[i].coerceAtLeast(0.0) + bg }
            val splMin = splValues.minOrNull() ?: bg
            val splMax = splValues.maxOrNull() ?: bg
            val splField = excess.copy(values = splValues, min = splMin, max = splMax)
            val levels = ContourBuilder.defaultLevels(splMin, splMax)
            // 256 colour bands only look different from 32 when the palette itself is quantised to
            // enough steps — pass the band count as the palette step count.
            val grid = HeatmapRenderer.renderColorGrid(
                splField, levels, colorBands, colorQuantizeStep(colorBands),
            )
            val bmp = AndroidBitmap.createBitmap(
                splField.resX, splField.resY, AndroidBitmap.Config.ARGB_8888
            ).apply {
                setPixels(grid, 0, width, 0, 0, width, height)
            }.asImageBitmap()
            result = splField to bmp
        }
    }
    return result
}

/**
 * Colour legend that matches the quantised map exactly: [bands] equal slices, each painted with the
 * colour the renderer used for that band (and therefore the actual palette step count).
 */
@Composable
private fun BandLegend(colorBands: Int, modifier: Modifier = Modifier) {
    val colors = remember(colorBands) {
        HeatmapRenderer.bandColors(colorBands, colorQuantizeStep(colorBands))
            .map { Color(it) }
    }
    Row(modifier = modifier.height(16.dp)) {
        colors.forEach { c ->
            Box(Modifier.weight(1f).height(16.dp).background(c))
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
