package com.lmreader.ui.video

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.i18n.Icon
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

private class LocalVideoPicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoLibraryScreen(container: AppContainer, onOpenMenu: () -> Unit, onOpen: (String) -> Unit) {
    val projects by container.videoLibrary.projects.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var relocating by remember { mutableStateOf<VideoProject?>(null) }; var removing by remember { mutableStateOf<VideoProject?>(null) }
    val picker = rememberLauncherForActivityResult(LocalVideoPicker()) { uri ->
        val old = relocating; relocating = null
        if (uri != null) scope.launch {
            busy = true
            try {
                val value = VideoMediaSource.inspect(context, uri)
                val project = withContext(Dispatchers.IO) {
                    if (old == null) container.videoLibrary.add(value)
                    else { require(old.sha256 == value.sha256) { "文件内容与原视频不一致，已有成果已保留；请另行导入新文件" }
                        container.videoLibrary.change(old.id) { it.copy(uri = uri.toString()) } }
                }
                onOpen(project.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "视频导入失败" }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("本地视频") }, navigationIcon = {
        IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, "菜单") }
    }, actions = { TextButton(enabled = !busy, onClick = { relocating = null; picker.launch(arrayOf("video/*")) }) { Text("导入视频") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("播放原视频，画面译文与字幕分别保存。通过文件选择器保持读取授权，不复制或重新编码原视频。") }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取视频信息与文件身份…") }
            if (projects.isEmpty()) item { Text("从本地文件导入视频后开始观看。声音识别尚未接入，可先导入 SRT 字幕。") }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen(p.id) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, localize = false)
                        Text("${time(p.positionMs)} / ${time(p.durationMs)} · ${p.width}×${p.height}", localize = false)
                        Text("画面成果 ${p.frames.size} 段 · 字幕 ${p.cues.count { it.translation.isNotBlank() }}/${p.cues.size}")
                        FlowRow { TextButton(enabled = !busy, onClick = { onOpen(p.id) }) { Text("继续观看") }
                            TextButton(enabled = !busy, onClick = { relocating = p; picker.launch(arrayOf("video/*")) }) { Text("重新定位") }
                            TextButton(enabled = !busy, onClick = { removing = p }) { Text("移除") } }
                    }
                }
            }
        }
    }
    removing?.let { p -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除视频及应用内成果？") },
        text = { Text("将移除观看进度、画面译文和字幕校对。原视频文件保留。") }, confirmButton = {
            TextButton(onClick = { removing = null; scope.launch(Dispatchers.IO) {
                try { container.videoLibrary.remove(p.id) } catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message } }
            } }) { Text("移除") }
        }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
    error?.let { message -> ErrorDialog(message) { error = null } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerScreen(container: AppContainer, id: String, onBack: () -> Unit, onOpenModels: () -> Unit, onOpenWorkflows: () -> Unit) {
    val vm: VideoViewModel = viewModel(key = "video:$id", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = VideoViewModel(container, id) as T
    })
    val state by vm.state.collectAsStateWithLifecycle(); val p = state.project
    val workflows by container.translationWorkflows.workflows.collectAsStateWithLifecycle()
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<VideoPlaybackView?>(null) }
    var masks by rememberSaveable { mutableStateOf(true) }; var subtitles by rememberSaveable { mutableStateOf(true) }
    var display by rememberSaveable { mutableStateOf(VideoSubtitleDisplay.BILINGUAL) }
    var full by rememberSaveable { mutableStateOf(false) }; var config by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }
    var pendingSrt by remember { mutableStateOf<Uri?>(null) }; var export by remember { mutableStateOf<String?>(null) }
    var resources by remember { mutableStateOf(false) }
    val maskValue by rememberUpdatedState(masks)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) if (p?.cues?.isNotEmpty() == true) pendingSrt = uri else vm.importSubtitles(uri)
    }
    val relocate = rememberLauncherForActivityResult(LocalVideoPicker()) { uri -> if (uri != null && p != null) scope.launch {
        try { val actual = VideoMediaSource.inspect(context, uri); require(actual.sha256 == p.sha256) { "文件内容不匹配，原成果已保留" }
            withContext(Dispatchers.IO) { container.videoLibrary.change(id) { it.copy(uri = uri.toString()) } }; vm.verifySource()
        } catch (e: Exception) { vm.reportError(e.message ?: "重新定位失败") }
    } }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val captured = export; export = null
        if (uri != null && captured != null) scope.launch {
            try { VideoSubtitleFiles.save(context, uri, captured) }
            catch (e: Exception) { vm.reportError("字幕保存失败：${e.message}") }
        }
    }
    DisposableEffect(lifecycle, player) {
        vm.pausePlayback = { player?.pause(); player?.showOverlay(null) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            player?.let { vm.savePosition(it.positionMs); it.pause(); it.showOverlay(null) }; vm.stop(); vm.stopSubtitles()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pausePlayback = null }
    }
    DisposableEffect(id) { onDispose { player?.let { vm.savePosition(it.positionMs); it.release() }; vm.stop(); vm.stopSubtitles() } }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) {
            player?.let { view -> try { vm.tick(view, maskValue) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { vm.stop(); view.showOverlay(null); vm.reportError(e.message ?: "视频分析失败") } }
            delay(250)
        } }
    }
    BackHandler(full) { full = false }
    Scaffold(topBar = { if (!full) TopAppBar(title = { Text(p?.name ?: "本地视频", localize = false) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { TextButton(onClick = { config = true }, enabled = p != null) { Text("翻译设置") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 100.dp).background(Color.Black)) {
                if (p != null && state.ready) key(p.id, p.uri) {
                    AndroidView(factory = { VideoPlaybackView(it, p, vm::invalidate, vm::reportError).also { player = it } },
                        modifier = Modifier.fillMaxSize(), onRelease = { it.release(); if (player === it) player = null })
                }
                if (p != null && subtitles) {
                    val active = VideoSubtitles.active(p.cues, state.positionMs, p.offsetMs)
                    val text = active.joinToString("\n") { c -> when (display) {
                        VideoSubtitleDisplay.ORIGINAL -> c.source
                        VideoSubtitleDisplay.TRANSLATED -> c.translation.ifBlank { c.source }
                        VideoSubtitleDisplay.BILINGUAL -> if (c.translation.isBlank() || c.translation == c.source) c.source else c.source + "\n" + c.translation
                    } }
                    if (text.isNotBlank()) Text(text, Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color.Black.copy(alpha = .75f)).padding(8.dp),
                        color = Color.White, localize = false)
                }
                TextButton(onClick = { full = !full }, modifier = Modifier.align(Alignment.TopEnd)) { Text(if (full) "退出全屏" else "全屏") }
                if (full) Row(Modifier.align(Alignment.TopStart)) {
                    TextButton(onClick = { player?.let { if (it.playing) it.pause() else it.play() } }) { Text(if (state.playing) "暂停" else "播放") }
                    TextButton(onClick = { full = false }) { Text("控制") }
                }
            }
            if (!full && p != null) Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Text(state.status)
                if (p.subtitleIdentity == "stale") Text("字幕译文采用之前的设置，已保留；可先导出，再清空译文后重译。", color = MaterialTheme.colorScheme.error)
                if (state.busy || state.subtitleBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                var drag by remember { mutableStateOf<Float?>(null) }
                Slider(value = drag ?: state.positionMs.toFloat(), valueRange = 0f..p.durationMs.toFloat(), onValueChange = {
                    if (drag == null) { vm.invalidate(); player?.showOverlay(null) }; drag = it
                }, onValueChangeFinished = { drag?.let { player?.seek(it.toLong()) }; drag = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${time(state.positionMs)} / ${time(p.durationMs)}", localize = false)
                    TextButton(onClick = { player?.let { if (it.playing) it.pause() else it.play() } }, enabled = state.ready) { Text(if (state.playing) "暂停" else "播放") }
                    TextButton(onClick = { player?.let { it.setSpeed(when (it.speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> .5f; else -> 1f }) } }) { Text("${player?.speed ?: 1f}×", localize = false) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(masks, { masks = it; if (!it) { vm.stop(); player?.showOverlay(null) } }); Text("画面遮罩")
                    Checkbox(subtitles, { subtitles = it }); Text("字幕")
                    TextButton(onClick = { display = VideoSubtitleDisplay.entries[(display.ordinal + 1) % 3] }) {
                        Text(when (display) { VideoSubtitleDisplay.ORIGINAL -> "原文"; VideoSubtitleDisplay.TRANSLATED -> "译文"; VideoSubtitleDisplay.BILINGUAL -> "双语" }) }
                }
                FlowRow {
                    Button(enabled = state.ready && masks, onClick = { if (state.continuous) vm.stop() else vm.start(true) }) { Text(if (state.continuous) "停止画面翻译" else "连续画面翻译") }
                    TextButton(enabled = state.ready && masks && !state.busy, onClick = { vm.start(false) }) { Text("翻译当前画面") }
                }
                FlowRow {
                    TextButton(onClick = { picker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }) { Text("导入 SRT") }
                    TextButton(enabled = p.cues.isNotEmpty(), onClick = { if (state.subtitleBusy) vm.stopSubtitles() else vm.translateSubtitles() }) { Text(if (state.subtitleBusy) "停止字幕翻译" else "翻译字幕") }
                    TextButton(enabled = p.cues.isNotEmpty(), onClick = { track = true }) { Text("校对字幕") }
                }
                FlowRow {
                    TextButton(enabled = p.cues.isNotEmpty(), onClick = { export = VideoSubtitles.encode(p.cues, display, p.offsetMs); saver.launch("${safeName(p.name)}.srt") }) { Text("保存字幕") }
                    TextButton(enabled = p.cues.isNotEmpty(), onClick = { val captured = VideoSubtitles.encode(p.cues, display, p.offsetMs)
                        scope.launch { try { context.startActivity(Intent.createChooser(VideoSubtitleFiles.shareIntent(context, captured), null)) } catch (e: Exception) { vm.reportError("字幕分享失败：${e.message}") } } }) { Text("分享字幕") }
                    TextButton(onClick = { resources = true }) { Text("视频数据") }
                }
                if (!state.ready) TextButton(onClick = { relocate.launch(arrayOf("video/*")) }) { Text("重新定位原视频") }
            }
        }
    }
    if (config && p != null) VideoSettingsDialog(p, workflows, onDismiss = { config = false }, onSave = { a, b, c, d ->
        vm.settings(a, b, c, d); player?.showOverlay(null); config = false
    }, onModels = { config = false; onOpenModels() }, onWorkflows = { config = false; onOpenWorkflows() })
    if (track && p != null) SubtitleEditor(p, onDismiss = { track = false }, onEdit = vm::edit, onSeek = { player?.seek(it); track = false },
        onOffset = vm::offset, onClear = { clear = true })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空字幕译文与校对？") },
        text = { Text("原文和时间保留。请先保存或分享需要保留的译文。") },
        confirmButton = { TextButton(onClick = { vm.clearSubtitleTranslations(); clear = false }) { Text("清空译文") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    pendingSrt?.let { uri -> AlertDialog(onDismissRequest = { pendingSrt = null }, title = { Text("替换当前字幕轨？") },
        text = { Text("当前字幕、译文和校对将被替换。画面成果保留；请先导出需要保留的字幕。") },
        confirmButton = { TextButton(onClick = { pendingSrt = null; vm.importSubtitles(uri) }) { Text("替换字幕") } },
        dismissButton = { TextButton(onClick = { pendingSrt = null }) { Text("取消") } }) }
    if (resources && p != null) AlertDialog(onDismissRequest = { resources = false }, title = { Text("这部视频的数据") }, text = {
        Column { container.videoLibrary.resources(id).forEach { Text("${it.kind}：${it.bytes / 1024} KiB") }
            Text("原视频为外部引用。正式译文与校对不属于缓存。分析帧只在内存中保留；识别中间缓存可重建。") }
    }, confirmButton = { TextButton(onClick = { resources = false }) { Text("关闭") } }, dismissButton = {
        TextButton(onClick = { vm.clearCache(); player?.showOverlay(null); resources = false }) { Text("停止并清理分析缓存") }
    })
    state.error?.let { message -> AlertDialog(onDismissRequest = vm::clearError, title = { Text("未能完成") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = vm::clearError) { Text("关闭") } }, dismissButton = {
            TextButton(onClick = { vm.clearError(); onOpenModels() }) { Text("模型与 API 设置") }
        }) }
}

@Composable
private fun VideoSettingsDialog(p: VideoProject, workflows: List<TranslationWorkflow>, onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit, onModels: () -> Unit, onWorkflows: () -> Unit) {
    var from by remember { mutableStateOf(p.source) }; var to by remember { mutableStateOf(p.target) }
    var image by remember { mutableStateOf(p.workflowId) }; var text by remember { mutableStateOf(p.subtitleWorkflowId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("视频翻译设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(from, { from = it }, label = { Text("源语言代码") }, singleLine = true)
            OutlinedTextField(to, { to = it }, label = { Text("目标语言代码") }, singleLine = true)
            WorkflowPicker("画面工作流", image, workflows.filter { it.program.inputScenario == WorkflowInputScenario.IMAGE }) { image = it }
            WorkflowPicker("字幕工作流", text, workflows.filter { it.program.inputScenario == WorkflowInputScenario.TIMED_TEXT }) { text = it }
            Text("使用工作流中各节点的 API 绑定。纯本地流程无需 API；缺模型时可补齐后回到这里继续。")
            TextButton(onClick = onModels) { Text("模型与 API 设置") }
            TextButton(onClick = onWorkflows) { Text("编辑或绑定工作流") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(from, to, image, text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable private fun WorkflowPicker(label: String, selected: String, workflows: List<TranslationWorkflow>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }) { Text("$label：${workflows.firstOrNull { it.id == selected }?.name ?: "请选择"}") }
        DropdownMenu(expanded, { expanded = false }) { workflows.forEach { w -> DropdownMenuItem(text = { Text(w.name) }, onClick = { expanded = false; onSelect(w.id) }) } } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SubtitleEditor(p: VideoProject, onDismiss: () -> Unit, onEdit: (VideoSubtitleCue, String) -> Unit,
    onSeek: (Long) -> Unit, onOffset: (Long) -> Unit, onClear: () -> Unit) {
    var editing by remember { mutableStateOf<VideoSubtitleCue?>(null) }; var draft by remember { mutableStateOf("") }
    var offset by remember(p.offsetMs) { mutableStateOf(p.offsetMs.toString()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text("校对字幕") }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(offset, { offset = it }, label = { Text("字幕偏移（毫秒）") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(enabled = offset.toLongOrNull()?.let { it in -3600000..3600000 } == true, onClick = { offset.toLongOrNull()?.let(onOffset) }) { Text("应用") }
                }; TextButton(onClick = onClear) { Text("清空字幕译文") } }
                items(p.cues, key = { it.id }) { cue -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                    TextButton(onClick = { onSeek((cue.startMs + p.offsetMs).coerceAtLeast(0)) }) { Text("${time(cue.startMs)} → ${time(cue.endMs)}", localize = false) }
                    Text(cue.source, localize = false); Text(cue.translation.ifBlank { "—" }, localize = false)
                    TextButton(onClick = { editing = cue; draft = cue.translation }) { Text(if (cue.manuallyEdited) "继续校对" else "编辑译文") }
                } } }
            }
        }
        editing?.let { cue -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("编辑字幕译文") }, text = {
            OutlinedTextField(draft, { if (it.length <= 20000) draft = it }, minLines = 3, label = { Text("译文") })
        }, confirmButton = { TextButton(onClick = { onEdit(cue, draft); editing = null }) { Text("保存校对") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    }
}
@Composable private fun ErrorDialog(message: String, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss,
    title = { Text("未能完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
private fun time(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
private fun safeName(name: String) = name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
