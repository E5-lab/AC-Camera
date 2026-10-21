package com.e5lab.accamera.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.e5lab.accamera.ACameraApplication
import com.e5lab.accamera.data.model.Project
import com.e5lab.accamera.ui.components.rememberFileImageBitmap
import com.e5lab.accamera.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    var calibration by remember { mutableStateOf("90") }
    var bgPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project) {
        project?.let {
            name = it.name
            description = it.description
            cols = it.cols.toString()
            rows = it.rows.toString()
            sx = it.spacingX.toString()
            sy = it.spacingY.toString()
            calibration = it.calibrationOffsetDb.toString()
            bgPath = it.backgroundImagePath
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val path = copyUriToInternal(context, uri, projectId)
                if (path != null) bgPath = path
            }
        }
    }

    val preview = rememberFileImageBitmap(bgPath)

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
                            calibrationOffsetDb = calibration.toDoubleOrNull() ?: p.calibrationOffsetDb,
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

            Text("声级校准", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                calibration,
                { calibration = it },
                label = { Text("校准偏移 (dB)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "用已校准的声级计在当前环境读取一个参考值，将“手机读数 + 此偏移”对齐到参考值。默认 90 dB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("厂区平面图（可选）", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Button(onClick = { imageLauncher.launch("image/*") }) {
                Text("选择平面图照片")
            }
            bgPath?.let {
                Text("已选择：$it", style = MaterialTheme.typography.bodySmall)
                preview?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "平面图预览",
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

private suspend fun copyUriToInternal(context: Context, uri: Uri, projectId: Long): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "backgrounds")
            dir.mkdirs()
            val out = File(dir, "project_$projectId.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.absolutePath
        }.getOrNull()
    }
