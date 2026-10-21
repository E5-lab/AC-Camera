package com.e5lab.accamera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.domain.analysis.NoiseSourceAnalyzer
import com.e5lab.accamera.ui.navigation.Routes
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(nav: NavHostController, projectId: Long) {
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

    val minSpl = measurements.minOfOrNull { it.spl } ?: 40.0
    val maxSpl = measurements.maxOfOrNull { it.spl } ?: 100.0

    var threshold by remember { mutableStateOf(70.0) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(minSpl, maxSpl) {
        if (!initialized && measurements.isNotEmpty()) {
            threshold = (maxSpl - 3.0).coerceIn(minSpl, maxSpl)
            initialized = true
        }
    }

    val sources = if (project != null) {
        NoiseSourceAnalyzer.detect(project, measurements, threshold, project.backgroundNoiseDb)
    } else {
        emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("噪声源分析") },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (measurements.isEmpty()) {
                Text(
                    "暂无测点数据。请先在「现场测量」中采集数据。",
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("判定阈值：${"%.1f".format(threshold)} dB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { threshold = it.toDouble() },
                    valueRange = minSpl.toFloat()..maxSpl.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "高于该阈值的局部极大值被判定为疑似噪声源。拖动滑块可调整灵敏度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "共识别 ${sources.size} 个疑似噪声源",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        val sourceKeys = sources.map { it.col to it.row }.toSet()
                        measurements.forEach { m ->
                            val isSrc = sourceKeys.contains(m.col to m.row)
                            vm.upsertMeasurement(m.copy(isNoiseSource = isSrc))
                        }
                    }) { Text("保存噪声源标记") }
                    Button(onClick = { nav.navigate(Routes.contour(projectId)) }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("查看等声压级图")
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(sources) { index, s ->
                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("噪声源 #${index + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("网格 (列 ${s.col}, 行 ${s.row})", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "坐标 x=${"%.1f".format(s.x)}m, y=${"%.1f".format(s.y)}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "%.1f".format(s.spl) + " dB",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
