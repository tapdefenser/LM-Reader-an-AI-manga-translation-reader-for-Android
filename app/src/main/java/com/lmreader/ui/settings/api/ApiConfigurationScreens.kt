package com.lmreader.ui.settings.api

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ApiProfile
import com.lmreader.core.model.ApiProfileKind
import com.lmreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigurationHomeScreen(onLlm: () -> Unit, onLocal: () -> Unit, onOcr: () -> Unit, onSeg: () -> Unit, onBack: () -> Unit, onAsr: () -> Unit = {}) {
    Scaffold(topBar = { ApiTopBar("API 与翻译引擎", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ApiEntry("LLM 配置", "模型连接、请求参数与流式测试", onLlm)
            ApiEntry("本地翻译引擎", "离线机翻、语言包与下载源", onLocal)
            ApiEntry("本地 ASR 模型", "离线语音识别、模型下载/导入与资源设置", onAsr)
            ApiEntry("OCR 配置", "本地 OCR 与远程模型配置", onOcr)
            ApiEntry("SEG 配置", "气泡分割、并发、GPU 加速与预处理缓存", onSeg)
        }
    }
}

@Composable
private fun ApiEntry(title: String, description: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (description.isBlank()) null else ({ Text(description) }),
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApiTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = actions)
}

@Composable
fun ApiProfilesScreen(container: AppContainer, kind: ApiProfileKind, onEdit: (String?) -> Unit, onBack: () -> Unit,
                      onLocalOcrTest: () -> Unit = {}, onLocalOcrConfig: () -> Unit = {}) {
    val vm: ApiProfilesViewModel = viewModel(factory = viewModelFactory {
        initializer { ApiProfilesViewModel(container.apiProfiles, kind) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    Scaffold(topBar = {
        ApiTopBar(if (kind == ApiProfileKind.LLM) "LLM 配置" else "OCR 配置", onBack) {
            IconButton(onClick = { onEdit(null) }, enabled = !state.loading && state.error == null) {
                Icon(Icons.Default.Add, "新增配置")
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == ApiProfileKind.OCR) item(key = "builtin-ocr") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本地 OCR 模型", style = MaterialTheme.typography.titleMedium)
                        Text("内置 · Paddle OCR · 中日韩英 · 完全离线", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = onLocalOcrConfig) { Text("配置") }
                            TextButton(onClick = onLocalOcrTest) { Text("测试本地 OCR") }
                        }
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (!state.loading && state.error == null && state.profiles.isEmpty()) item {
                Text("点击右上角 ＋ 添加 API 配置", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(state.profiles, key = { it.id }) { profile ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleMedium, localize = false)
                        Text(profile.model, style = MaterialTheme.typography.bodyMedium)
                        Text(profile.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${profile.format.label} · 超时 ${profile.timeoutSeconds}s · 并行 ${profile.parallelLimit}", style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onEdit(profile.id) }) { Text("修改") }
                            TextButton(onClick = { vm.duplicate(profile.id) }) { Text("复制") }
                            TextButton(onClick = { deleting = profile }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除配置") },
            text = { Text("删除“${profile.displayName}”？") },
            confirmButton = { TextButton(onClick = { vm.delete(profile.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
