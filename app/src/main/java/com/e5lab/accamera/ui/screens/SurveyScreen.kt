package com.e5lab.accamera.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
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
import com.e5lab.accamera.audio.SoundMeter
import com.e5lab.accamera.data.model.Measurement
import com.e5lab.accamera.ui.components.GridPlot
import com.e5lab.accamera.ui.components.rememberFileImageBitmap
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(nav: NavHostController, projectId: Long) {
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

    val pointMap = remember(measurements) { measurements.associateBy({ it.col to it.row }, { it.spl }) }
    val minV = measurements.minOfOrNull { it.spl } ?: 0.0
    val maxV = measurements.maxOfOrNull { it.spl } ?: 100.0

    val bg = rememberFileImageBitmap(project?.backgroundImagePath)
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val calibration = project?.calibrationOffsetDb ?: 90.0
    val soundMeter = remember(calibration) { SoundMeter(calibration) }
    DisposableEffect(soundMeter) { onDispose { soundMeter.dispose() } }
    val liveDb by soundMeter.db.collectAsState()
    val isRunning by soundMeter.isRunning.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) soundMeter.start()
    }

    LaunchedEffect(selected) {
        if (selected != null) {
            if (soundMeter.hasPermission(context)) {
                if (!soundMeter.isRunning.value) soundMeter.start()
            } else {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            soundMeter.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("现场测量") },
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已测 ${measurements.size} / ${p.pointCount} 测点",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val prog = if (p.pointCount > 0) measurements.size.toFloat() / p.pointCount else 0f
            LinearProgressIndicator(
                progress = prog,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "点击格点测量该处声压级。格点颜色表示声级高低（蓝低→红高）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp),
            )
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                GridPlot(
                    cols = p.cols,
                    rows = p.rows,
                    points = pointMap,
                    background = bg,
                    selected = selected,
                    minValue = minV,
                    maxValue = maxV,
                    onPointTap = { c, r -> selected = c to r },
                )
            }
        }
    }

    if (selected != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val (c, r) = selected!!
        val existing = measurements.find { it.col == c && it.row == r }

        var captured by remember { mutableStateOf<Double?>(null) }
        var manualText by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }

        LaunchedEffect(selected) {
            captured = null
            manualText = existing?.spl?.toString() ?: ""
            noteText = existing?.note ?: ""
        }

        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "测点 (列 $c, 行 $r) · x=${"%.1f".format(project!!.xAt(c))}m y=${"%.1f".format(project!!.yAt(r))}m",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // Live meter
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "%.1f".format(liveDb),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("实时声压级 dB(A) 估测", style = MaterialTheme.typography.bodySmall)
                    if (isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    } else {
                        Text(
                            "麦克风未启动（可能无权限）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Button(
                    onClick = { captured = liveDb },
                    enabled = isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text(" 采集当前读数")
                }

                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    label = { Text("声压级 dB（手动输入）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注（设备 / 工况）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val value = captured ?: manualText.toDoubleOrNull() ?: existing?.spl ?: 0.0
                            vm.upsertMeasurement(
                                Measurement(
                                    projectId = projectId,
                                    col = c,
                                    row = r,
                                    spl = value,
                                    note = noteText,
                                    remediationNote = existing?.remediationNote,
                                    afterSpl = existing?.afterSpl,
                                    afterMeasuredAt = existing?.afterMeasuredAt,
                                    isNoiseSource = existing?.isNoiseSource ?: false,
                                ),
                            )
                            selected = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }

                    if (existing != null) {
                        TextButton(
                            onClick = {
                                vm.deleteMeasurement(c, r)
                                selected = null
                            },
                        ) { Text("清除") }
                    }
                }
            }
        }
    }
}
