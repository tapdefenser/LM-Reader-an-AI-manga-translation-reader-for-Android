package com.lmreader.ui.settings.api

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer
import com.lmreader.core.model.OcrBackend
import com.lmreader.core.model.InferenceEngineKind
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

@Composable
fun LocalVisionConfigurationScreen(container: AppContainer, seg: Boolean, onTest: () -> Unit, onBack: () -> Unit) {
    val prefs by container.visionExecutionPreferences.settings.collectAsStateWithLifecycle()
    val messages by container.localVision.accelerationMessages.collectAsStateWithLifecycle()
    val resources by container.localVision.loadedResources.collectAsStateWithLifecycle()
    val cacheLimit by container.translationCachePreferences.megabytes.collectAsStateWithLifecycle()
    val cacheUsed by container.translationQueue.cacheBytes.collectAsStateWithLifecycle()
    val cachedResults by container.localVision.preprocessingCache.bytes.collectAsStateWithLifecycle()
    val cacheHits by container.localVision.preprocessingCache.hits.collectAsStateWithLifecycle()
    var cacheDraft by remember(cacheLimit) { mutableFloatStateOf(cacheLimit.toFloat()) }
    val count = if (seg) prefs.segConcurrency else prefs.ocrConcurrency
    val actual = if (seg) container.localVision.segConcurrency else container.localVision.ocrConcurrency
    var menu by remember { mutableStateOf(false) }
    var backendMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = if (seg) "SEG 配置" else "本地 OCR 配置"
    Scaffold(topBar = { ApiTopBar(title, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (seg) "本地气泡分割与文字区域检测" else "本地文字检测与识别模型", style = MaterialTheme.typography.titleMedium)
            Text(if (seg) "SEG 检测气泡与游离文字区域，OCR 或视觉 API 将所选区域转为文本。"
                else "文字检测与文字识别共用并发数和加速方式；工作流中的文字检测由 SEG 步骤调用。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("并发数")
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(if (count == 0) "0（自动）" else "$count") }
                    DropdownMenu(menu, { menu = false }) { (0..8).forEach { option ->
                        DropdownMenuItem(text = { Text(if (option == 0) "0（自动）" else "$option") }, onClick = {
                            scope.launch { container.visionExecutionPreferences.update {
                                if (seg) it.copy(segConcurrency = option) else it.copy(ocrConcurrency = option)
                            } }; menu = false
                        })
                    } }
                }
            }
            Text("当前并发上限 $actual；自动模式根据 CPU 与可用内存决定。", style = MaterialTheme.typography.bodySmall)
            if (seg) {
                Text("预处理缓存上限 ${cacheDraft.toInt()} MB · 当前 ${cacheUsed / 1_048_576} MB")
                Text("结果缓存 ${cachedResults / 1_048_576} MB · 本次命中 $cacheHits 次", style = MaterialTheme.typography.bodySmall)
                Slider(cacheDraft, { cacheDraft = it }, valueRange = 32f..1024f, steps = 30,
                    onValueChangeFinished = { scope.launch { container.translationCachePreferences.setMegabytes(cacheDraft.toInt()) } })
                Text("缓存预算在所有漫画间共享。未提交的 SEG、文字检测与 OCR 结果完整保留，单页可超限，下一页 SEG 等待空位。等待 API 或机翻时释放空闲位图；已提交的结果缓存按原图、阈值和模型版本复用，满时清理最久未用的结果。模型自身内存不计入此上限。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (seg) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("气泡分割 GPU 加速")
                    Switch(prefs.segGpu, { checked -> scope.launch {
                        container.visionExecutionPreferences.update { it.copy(segGpu = checked) }
                    } })
                }
                Text("LiteRT GPU 后端，设备支持时使用 GPU，初始化或推理失败时回退 CPU。", style = MaterialTheme.typography.bodySmall)
                Text("文字检测共用本地 OCR 的并发上限与加速方式。", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("OCR 加速方式")
                    Box {
                        OutlinedButton(onClick = { backendMenu = true }, modifier = Modifier.testTag("ocr-backend-selector")) {
                            Text(prefs.ocrBackend.label())
                        }
                        DropdownMenu(backendMenu, { backendMenu = false }) {
                            OcrBackend.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label()) }, onClick = {
                                    scope.launch { container.visionExecutionPreferences.update { it.copy(ocrBackend = option) } }
                                    backendMenu = false
                                }, modifier = Modifier.testTag("ocr-backend-${option.name}"))
                            }
                        }
                    }
                }
                Text(when (prefs.ocrBackend) {
                    OcrBackend.AUTO -> "自动依次尝试 QNN → NNAPI → CPU。QNN 用于高通设备；NNAPI 取决于系统 GPU/NPU 驱动。"
                    OcrBackend.QNN -> "使用高通 QNN，优先尝试 NPU，再尝试 GPU；不可用或运行失败时回退 CPU。"
                    OcrBackend.NNAPI -> "使用系统 NNAPI GPU/NPU 驱动；不可用或运行失败时回退 CPU。"
                    OcrBackend.CPU -> "使用 ONNX Runtime CPU。"
                }, style = MaterialTheme.typography.bodySmall)
                Text("不支持的运算和超长文字行由 CPU 执行。首次使用硬件加速需要编译模型。", style = MaterialTheme.typography.bodySmall)
                val backends = resources.filter { it.kind == InferenceEngineKind.OCR }.map { it.backend }.distinct()
                Text(if (backends.isEmpty()) "实际后端：尚未加载" else "实际后端：${backends.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
            }
            Text("已加载引擎会保留；修改加速方式在全部暂停卸载后，下次加载时生效。", style = MaterialTheme.typography.bodySmall)
            messages.filter { it.startsWith(if (seg) "Seg" else "OCR") }.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onTest) { Text(if (seg) "测试 Seg" else "测试本地 OCR") }
        }
    }
}

private fun OcrBackend.label() = when (this) {
    OcrBackend.AUTO -> "自动"
    OcrBackend.QNN -> "QNN（高通）"
    OcrBackend.NNAPI -> "NNAPI（系统）"
    OcrBackend.CPU -> "CPU"
}
