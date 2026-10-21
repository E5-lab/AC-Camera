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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.e5lab.accamera.domain.interpolation.FieldResult
import com.e5lab.accamera.domain.interpolation.IdwInterpolator
import com.e5lab.accamera.domain.interpolation.PointValue
import com.e5lab.accamera.ui.components.ContourPlot
import com.e5lab.accamera.ui.components.splColor
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    val (field, bitmap) = rememberContour(project, measurements)
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
                    Text("请先在「现场测量」中采集至少 3 个测点，再生成等声压级曲线。")
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
                )
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
                    "黑圈标记 = 已识别的疑似噪声源。等值线（等声压级曲线）由 IDW 插值 + 行进方块法生成。",
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
): Pair<FieldResult?, androidx.compose.ui.graphics.ImageBitmap?> {
    var result by remember {
        mutableStateOf(
            Pair<FieldResult?, androidx.compose.ui.graphics.ImageBitmap?>(null, null),
        )
    }
    LaunchedEffect(project, measurements) {
        if (project == null || measurements.isEmpty()) {
            result = null to null
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            val pts = measurements.map {
                PointValue(project.xAt(it.col), project.yAt(it.row), it.spl)
            }
            val field = IdwInterpolator.interpolate(pts, project.widthMeters, project.heightMeters)
            val levels = ContourBuilder.defaultLevels(field.min, field.max)
            val bmp = ContourRenderer.render(field, levels).asImageBitmap()
            result = field to bmp
        }
    }
    return result
}
