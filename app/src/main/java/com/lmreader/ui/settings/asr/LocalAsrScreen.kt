package com.lmreader.ui.settings.asr

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.translation.asr.AsrBackend
import com.lmreader.core.translation.asr.AsrModelDescriptor
import com.lmreader.core.translation.asr.AsrModelManager
import com.lmreader.core.translation.asr.AsrModelState
import com.lmreader.core.translation.asr.AsrModelStatus
import com.lmreader.core.translation.asr.AsrRuntimeCapabilities
import com.lmreader.core.translation.asr.AsrSettings
import com.lmreader.core.translation.asr.LoadedAsrResource
import com.lmreader.core.translation.asr.LocalAsrEngine
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.api.ApiTopBar
import kotlinx.coroutines.launch
import java.util.Locale

// Root container wiring: AppContainer.asrModels: AsrModelManager and
// AppContainer.localAsr: LocalAsrEngine. Navigation and the container own those members.
@Composable
fun LocalAsrScreen(container: AppContainer, onBack: () -> Unit) {
    val manager = container.asrModels
    val engine = container.localAsr
    val models by manager.models.collectAsStateWithLifecycle()
    val states by manager.states.collectAsStateWithLifecycle()
    val installed by manager.installed.collectAsStateWithLifecycle()
    val selectedId by manager.selectedId.collectAsStateWithLifecycle()
    val settings by manager.settings.collectAsStateWithLifecycle()
    val loaded by engine.loadedResources.collectAsStateWithLifecycle()
    val capabilities by engine.capabilities.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<AsrModelDescriptor?>(null) }
    var importCandidate by remember { mutableStateOf<AsrModelDescriptor?>(null) }

    LaunchedEffect(engine) {
        runCatching { engine.refreshCapabilities() }.onFailure { failure ->
            message = failure.message ?: "无法检测本地 ASR 后端"
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importCandidate
        importCandidate = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            message = "无法打开所选文件"
            return@rememberLauncherForActivityResult
        }
        runCatching { manager.import(target.id, input) }.onFailure { failure ->
            runCatching { input.close() }
            message = failure.message ?: "无法开始导入"
        }
    }

    Scaffold(topBar = { ApiTopBar("本地 ASR 模型", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AsrSettingsCard(
                    manager = manager,
                    engine = engine,
                    settings = settings,
                    loaded = loaded,
                    capabilities = capabilities,
                    onMessage = { message = it },
                )
            }
            message?.let { text ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { message = null }) { Text("清除") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Whisper 多语言 GGML 模型。下载或导入都必须匹配目录中的精确字节数与 SHA-256；" +
                            "校验通过前不会显示为已安装。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(models, key = { it.id }) { model ->
                val state = states[model.id] ?: AsrModelState(totalBytes = model.bytes)
                val installedModel = installed[model.id]
                val loadedModel = loaded.firstOrNull { it.id == model.id }
                AsrModelCard(
                    model = model,
                    state = state,
                    installedModel = installedModel,
                    selected = selectedId == model.id,
                    loaded = loadedModel,
                    onDownload = {
                        message = null
                        runCatching { manager.download(model.id) }
                            .onFailure { message = it.message ?: "无法开始下载" }
                    },
                    onPause = {
                        message = null
                        runCatching { manager.pause(model.id) }
                            .onFailure { message = it.message ?: "无法暂停" }
                    },
                    onDiscard = {
                        message = null
                        runCatching { manager.discard(model.id) }
                            .onFailure { message = it.message ?: "无法丢弃暂存文件" }
                    },
                    onImport = {
                        message = null
                        importCandidate = model
                        runCatching { importer.launch(arrayOf("*/*")) }
                            .onFailure {
                                importCandidate = null
                                message = it.message ?: "无法打开文件选择器"
                            }
                    },
                    onSelect = {
                        message = null
                        scope.launch {
                            runCatching { engine.selectModel(model.id) }
                                .onFailure { message = it.message ?: "无法选择模型" }
                        }
                    },
                    onDelete = { deleteCandidate = model },
                )
            }
            if (models.isEmpty()) {
                item { Text("没有可用的 ASR 模型元数据。") }
            }
            item {
                Text(
                    "模型权重保存在应用 noBackupFilesDir，不进入 Android 备份。删除应用数据会同时删除模型；" +
                        "下载和导入都经过暂存、fsync、SHA-256 校验和原子重命名。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    deleteCandidate?.let { model ->
        val isLoaded = loaded.any { it.id == model.id }
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除 ${model.label}？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会先停止并卸载本地 ASR 会话，再删除 ${megabytes(model.bytes)} 的模型文件。")
                    if (isLoaded) Text("该模型当前已加载；删除前会等待正在进行的原生调用返回。")
                    Text("正在使用该模型的转写任务会失败，排队任务不会自动改用其他模型；之后需要重新下载或导入并重新选择。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    message = null
                    manager.delete(model.id) { engine.stopAndAwait() }
                }) { Text("停止并删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AsrSettingsCard(
    manager: AsrModelManager,
    engine: LocalAsrEngine,
    settings: AsrSettings,
    loaded: List<LoadedAsrResource>,
    capabilities: AsrRuntimeCapabilities?,
    onMessage: (String?) -> Unit,
) {
    var threadsOpen by remember { mutableStateOf(false) }
    var backendOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("下载与运行", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("推理后端")
                    Text(
                        "自动优先真实 GPU，初始化失败才回退 CPU；显式选择不可用后端会报错。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    OutlinedButton(onClick = { backendOpen = true }) {
                        Text(settings.backend.label)
                    }
                    DropdownMenu(expanded = backendOpen, onDismissRequest = { backendOpen = false }) {
                        AsrBackend.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label)
                                        Text(option.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = {
                                    backendOpen = false
                                    onMessage(null)
                                    scope.launch {
                                        runCatching { engine.selectBackend(option) }
                                            .onFailure { onMessage(it.message ?: "无法切换 ASR 后端") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Text(
                backendStatus(capabilities, settings.backend),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("仅 Wi-Fi 下载")
                    Text(
                        "蜂窝网络下不会开始网络下载；本地文件导入不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(settings.wifiOnly, { manager.setWifiOnly(it) })
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("推理线程")
                    Text(
                        "范围 1–${manager.threadLimit}（当前设备可用核心数，最多 4）。切换后端或线程会停止当前本地会话，下次转写重新加载。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    OutlinedButton(onClick = { threadsOpen = true }) {
                        Text("${settings.threads} 线程")
                    }
                    DropdownMenu(expanded = threadsOpen, onDismissRequest = { threadsOpen = false }) {
                        (1..manager.threadLimit).forEach { count ->
                            DropdownMenuItem(
                                text = { Text("$count 线程") },
                                onClick = {
                                    threadsOpen = false
                                    onMessage(null)
                                    scope.launch {
                                        runCatching { engine.selectThreads(count) }
                                            .onFailure { onMessage(it.message ?: "无法修改推理线程") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (loaded.isNotEmpty()) {
                Text(
                    "已加载：\n" + loaded.joinToString("\n") { resource ->
                        loadedSummary(resource) + if (resource.inUse) " · 推理中" else " · 空闲"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("当前没有加载本地 ASR 会话；普通视频播放不会加载 ASR 模型。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun backendStatus(
    capabilities: AsrRuntimeCapabilities?,
    selected: AsrBackend,
): String {
    if (capabilities == null) return "正在检测本机后端能力…"
    if (selected == AsrBackend.AUTO) {
        val parts = listOf(AsrBackend.VULKAN, AsrBackend.OPENCL).map { backend ->
            val capability = capabilities[backend]
            val state = when {
                capability == null -> "未报告"
                !capability.compiled -> "未编译"
                capability.available -> "可用"
                else -> "不可用"
            }
            val device = capability?.devices?.firstOrNull()
                ?.let { it.description?.takeIf(String::isNotBlank) ?: it.name }
            val detail = when {
                capability == null -> null
                capability.available && device != null -> device
                !capability.available -> capability.reason
                else -> null
            }
            "${backend.label}：$state" + if (detail.isNullOrBlank()) "" else "（$detail）"
        }
        return "自动：" + parts.joinToString("；") + "。无可用 GPU 时回退 CPU。"
    }
    val capability = capabilities[selected]
    if (capability == null) return "${selected.label}：当前构建未报告该后端"
    if (!capability.compiled) {
        return "${selected.label}：未编译（${capability.reason ?: "当前构建不包含该后端"}）"
    }
    if (!capability.available) {
        return "${selected.label}：不可用（${capability.reason ?: "未检测到可用设备"}）"
    }
    val device = capability.devices.firstOrNull()
        ?.let { it.description?.takeIf(String::isNotBlank) ?: it.name }
    return "${selected.label}：可用" + if (device.isNullOrBlank()) "" else "（$device）"
}

private fun loadedSummary(resource: LoadedAsrResource): String {
    val device = resource.deviceDescription?.takeIf(String::isNotBlank) ?: resource.device
    return buildString {
        append(resource.label)
        append(" · ")
        append(resource.backend.label)
        if (!device.isNullOrBlank()) append(" · ").append(device)
        append(" · ").append(resource.threads).append(" 线程")
        if (!resource.fallbackReason.isNullOrBlank()) {
            append(" · 回退：").append(resource.fallbackReason)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsrModelCard(
    model: AsrModelDescriptor,
    state: AsrModelState,
    installedModel: AsrModelDescriptor?,
    selected: Boolean,
    loaded: LoadedAsrResource?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
    onImport: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, style = MaterialTheme.typography.titleMedium)
                    Text("${model.id} · ${model.version}", style = MaterialTheme.typography.labelSmall,
                        localize = false)
                }
                if (selected) Text("当前模型", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "大小 ${megabytes(model.bytes)} · 语言 ${languageSummary(model.languages)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (installedModel == null) "未安装"
                else "已安装版本 ${installedModel.version}（安装时已校验精确字节数与 SHA-256）",
                style = MaterialTheme.typography.bodySmall,
            )
            if (installedModel != null) {
                Text("转写加载前会再次校验文件哈希。", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                statusLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status == AsrModelStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.status) {
                AsrModelStatus.DOWNLOADING -> LinearProgressIndicator(
                    progress = {
                        if (state.totalBytes > 0L) {
                            (state.completedBytes.toFloat() / state.totalBytes).coerceIn(0f, 0.99f)
                        } else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AsrModelStatus.VERIFYING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
            if (loaded != null) {
                Text(
                    loadedSummary(loaded) + if (loaded.inUse) " · 推理中" else " · 空闲",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installedModel != null) {
                    if (!selected) TextButton(onClick = onSelect, enabled = !state.busy) { Text("设为当前") }
                    TextButton(onClick = onDelete, enabled = !state.busy) { Text("删除") }
                } else if (state.busy) {
                    TextButton(onClick = onPause) { Text("暂停") }
                    TextButton(onClick = onDiscard) { Text("取消并丢弃") }
                } else {
                    TextButton(onClick = onDownload) {
                        Text(if (state.status == AsrModelStatus.PAUSED) "继续下载" else "下载")
                    }
                    TextButton(onClick = onImport) { Text("导入文件") }
                    if (state.status == AsrModelStatus.PAUSED || state.status == AsrModelStatus.FAILED) {
                        TextButton(onClick = onDiscard) { Text("丢弃暂存") }
                    }
                }
            }
        }
    }
}

private fun statusLine(state: AsrModelState): String = when (state.status) {
    AsrModelStatus.NOT_INSTALLED -> "未安装"
    AsrModelStatus.DOWNLOADING ->
        "下载中 ${megabytes(state.completedBytes)} / ${megabytes(state.totalBytes)}"
    AsrModelStatus.PAUSED ->
        "已暂停 ${megabytes(state.completedBytes)} / ${megabytes(state.totalBytes)}，可继续下载"
    AsrModelStatus.VERIFYING -> state.message ?: "校验中"
    AsrModelStatus.READY -> "已安装并校验"
    AsrModelStatus.FAILED -> "失败：${state.message ?: "未知错误"}"
}

private fun languageSummary(languages: List<String>): String =
    if (languages.any { it.equals("multilingual", ignoreCase = true) }) {
        "多语言（Whisper multilingual）"
    } else {
        languages.joinToString("、")
    }

private fun megabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
