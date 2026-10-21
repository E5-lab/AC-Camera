package com.e5lab.accamera.ui.screens

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
import androidx.compose.ui.graphics.Brush
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
import com.e5lab.accamera.domain.contour.ContourRenderer
import com.e5lab.accamera.domain.interpolation.EnergyFieldInterpolator
import com.e5lab.accamera.domain.interpolation.FieldResult
import com.e5lab.accamera.domain.interpolation.PointValue
import com.e5lab.accamera.ui.components.ContourPlot
import com.e5lab.accamera.ui.components.rememberFileImageBitmap
import com.e5lab.accamera.ui.components.splColor
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val COLOR_BAND_CHOICES = listOf(8, 16, 32, 64, 128, 256)

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

    val baseMap = rememberFileImageBitmap(project?.backgroundImagePath)
    // Local override survives project re-emissions (the DB flow re-emits on every table change),
    // so dragging the slider is never reset mid-interaction.
    var alphaOverride by remember { mutableStateOf<Float?>(null) }
    val overlayAlpha = alphaOverride ?: project?.overlayAlpha ?: 0.72f
    var bandsOverride by remember { mutableStateOf<Int?>(null) }
    val colorBands = bandsOverride ?: project?.contourColorBands ?: 32

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
                )
            }
            if (p.backgroundImagePath != null && baseMap == null) {
                Text(
                    "底图加载中（如长时间未显示请重新在网格设置中选择平面图）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Box(
                    modifier = Modifier.fillMaxWidth().height(16.dp).background(
                        Brush.horizontalGradient(
                            listOf(
                                splColor(0f), splColor(0.25f), splColor(0.5f),
                                splColor(0.75f), splColor(1f),
                            ),
                        ),
                    ),
                )
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
            val bmp = ContourRenderer.render(splField, levels, colorBands).asImageBitmap()
            result = splField to bmp
        }
    }
    return result
}
