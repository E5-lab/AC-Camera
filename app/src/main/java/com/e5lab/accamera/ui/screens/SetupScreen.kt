package com.e5lab.accamera.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.backdrop.BackdropImporter
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.ui.components.drawCadDrawing
import com.e5lab.accamera.ui.components.rememberCadDrawing
import com.e5lab.accamera.ui.components.rememberFileImageBitmap
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(nav: NavHostController, projectId: Long) {
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

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cols by remember { mutableStateOf("10") }
    var rows by remember { mutableStateOf("10") }
    var sx by remember { mutableStateOf("5") }
    var sy by remember { mutableStateOf("5") }
    var planRef by remember { mutableStateOf("") }
    var bgNoise by remember { mutableStateOf("0") }
    var bgPath by remember { mutableStateOf<String?>(null) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project) {
        project?.let {
            name = it.name
            description = it.description
            cols = it.cols.toString()
            rows = it.rows.toString()
            sx = it.spacingX.toString()
            sy = it.spacingY.toString()
            planRef = it.planReferenceMeters?.toString() ?: ""
            bgNoise = it.backgroundNoiseDb.toString()
            bgPath = it.backgroundImagePath
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            importing = true
            importError = null
            importMsg = null
            scope.launch {
                BackdropImporter.import(context, uri, projectId)
                    .onSuccess { r ->
                        bgPath = r.path
                        importMsg = r.detail
                    }
                    .onFailure { e ->
                        importError = e.message ?: "底图导入失败"
                    }
                importing = false
            }
        }
    }

    val preview = rememberFileImageBitmap(bgPath)
    val cadPreview = rememberCadDrawing(bgPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网格设置") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val p = project ?: return@IconButton
                        val updated = Project(
                            id = p.id,
                            name = name.ifBlank { p.name },
                            description = description,
                            cols = cols.toIntOrNull()?.coerceAtLeast(2) ?: p.cols,
                            rows = rows.toIntOrNull()?.coerceAtLeast(2) ?: p.rows,
                            spacingX = sx.toDoubleOrNull()?.coerceAtLeast(0.1) ?: p.spacingX,
                            spacingY = sy.toDoubleOrNull()?.coerceAtLeast(0.1) ?: p.spacingY,
                            backgroundImagePath = bgPath,
                            planReferenceMeters = planRef.toDoubleOrNull(),
                            backgroundNoiseDb = bgNoise.toDoubleOrNull() ?: 0.0,
                            overlayAlpha = p.overlayAlpha,
                            contourColorBands = p.contourColorBands,
                            // Preserve the base-map alignment: this screen does not edit it, and
                            // dropping it here would silently reset a calibrated plan.
                            baseMapOffsetX = p.baseMapOffsetX,
                            baseMapOffsetY = p.baseMapOffsetY,
                            baseMapRotationDeg = p.baseMapRotationDeg,
                            baseMapScale = p.baseMapScale,
                            createdAt = p.createdAt,
                        )
                        vm.updateProject(updated)
                        nav.popBackStack()
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("测点网格", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("备注（厂名 / 区域）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cols, { cols = it }, label = { Text("列数") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(rows, { rows = it }, label = { Text("行数") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sx, { sx = it }, label = { Text("列间距 (m)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(sy, { sy = it }, label = { Text("行间距 (m)") }, singleLine = true, modifier = Modifier.weight(1f))
            }

            Text("底图与参照", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                planRef,
                { planRef = it },
                label = { Text("平面图参照宽度（米）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "填写平面图所表示的实际宽度（例如厂房东西向 40 m）。App 会按真实比例显示底图并绘制比例尺，便于判断噪声源对应哪台设备。留空则按画布拉伸。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                bgNoise,
                { bgNoise = it },
                label = { Text("背景噪声 / 底噪（dB，可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "厂区环境本底噪声。作为整张等声压级图的底噪下限：实测值视为「底噪 + 声源超额」，低能量区域稳定落在底噪、不会伪辐射。留空或 0 表示底噪为 0 dBA。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("厂区底图（可选）", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Button(
                onClick = { imageLauncher.launch(BackdropImporter.PICKER_MIME) },
                enabled = !importing,
            ) {
                Text(if (importing) "导入中…" else "导入底图（图片 / PDF / CAD）")
            }
            Text(
                "支持 JPG / PNG / WebP 照片、PDF 图纸（取第 1 页渲染）与 CAD 的 DXF 线图。导入后在「数据输入」「等声压级图」中可双指缩放、拖动平移，并用参照宽度把底图校准到真实比例。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            importMsg?.let {
                Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            importError?.let {
                Text("✗ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            bgPath?.let { path ->
                Text(
                    "已选择：" + path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    cadPreview != null -> CadPreview(cadPreview, Modifier.fillMaxWidth().height(200.dp))
                    preview != null -> Image(
                        bitmap = preview,
                        contentDescription = "底图预览",
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentScale = ContentScale.Fit,
                    )
                    else -> Text(
                        "底图解析中…（若长时间无预览，说明该文件格式不受支持，请换用 JPG / PNG / PDF / DXF）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Draws a parsed DXF so the user can confirm it is the right drawing before measuring. */
@Composable
private fun CadPreview(drawing: CadDrawing, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val pad = 8f
        val availW = (size.width - 2 * pad).coerceAtLeast(1f)
        val availH = (size.height - 2 * pad).coerceAtLeast(1f)
        val aspect = drawing.aspectRatio
        val w = minOf(availW, availH * aspect)
        val h = w / aspect
        val left = pad + (availW - w) / 2f
        val top = pad + (availH - h) / 2f
        drawCadDrawing(
            drawing,
            androidx.compose.ui.geometry.Rect(left, top, left + w, top + h),
            color = androidx.compose.ui.graphics.Color(0xFF1565C0),
        )
    }
}
