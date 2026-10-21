package com.lmreader.ui.video

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.editor.*
import com.lmreader.ui.video.player.*
import kotlinx.coroutines.*

fun openVideoPlayer(context: Context, id: String, ids: List<String>, action: String? = null) {
    VideoPlayerToolRegistry.host = VideoPlayerTools
    AppContainer.from(context).backgroundScope.launch(Dispatchers.IO) {
        try {
            val intent = VideoPlayerActivity.openIntent(context, id, ids)
            action?.let { intent.putExtra("initial-tool", it) }
            if(context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, com.lmreader.ui.i18n.UiTextTranslations.translate(context, "无法打开视频：${error.message}"), android.widget.Toast.LENGTH_LONG).show()
        } }
    }
}

@Composable
fun VideoActionDialog(container: AppContainer, id: String, action: String, positionMs: Long = 0,
    frameAtUs: Long? = null, onSeek: (Long) -> Unit = {}, onDismiss: () -> Unit) {
    var active by remember(id, action) { mutableStateOf(action) }
    var project by remember(id) { mutableStateOf<VideoProject?>(null) }
    var error by remember(id, action) { mutableStateOf<String?>(null) }
    var loading by remember(id, action) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var relocation by remember(id) { mutableStateOf<VideoProject?>(null) }
    val selectSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            loading = true
            try { relocation = container.videoTasks.inspectSource(uri) }
            catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message }
            finally { loading = false }
        }
    }
    val selectResults = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            try { container.videoResultImports.submit(id, uri); onDismiss() }
            catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(id, active) {
        if(active in setOf("EDIT", "SUBTITLES", "CLEAR", "EXPORT", "INFO")) {
            loading = true
            try { project = if(active in setOf("EDIT", "SUBTITLES")) container.videoTasks.ensureProject(id) else container.videoTasks.existingProject(id) }
            catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message }
            finally { loading = false }
        }
        if(active == "TRANSLATE") {
            try {
                if(container.videoResults.results().settings(id) == null) active = "OPTIONS"
                else { container.videoTasks.toggle(id); onDismiss() }
            } catch(e: Exception) { error = e.message }
        }
        if(active == "FRAME") {
            try {
                require(frameAtUs != null) { "画面尚未呈现，请在视频显示后重试翻译本画面" }
                val options = container.videoTasks.options(id).copy(mode = "FRAME")
                container.videoTasks.submit(id, options, frameAtUs); onDismiss()
            } catch(e: Exception) { error = e.message }
        }
    }
    if(error != null) AlertDialog(onDismissRequest = onDismiss, title = { Text("操作未完成") }, text = { Text(error.orEmpty()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    else when(active) {
        "OPTIONS" -> VideoTranslationOptionsDialog(container, id, onDismiss)
        "EDIT" -> project?.let { VideoBubbleEditor(container, it, positionMs, onSeek, onDismiss) }
        "SUBTITLES" -> project?.let { VideoSubtitleEditor(container, it, positionMs, onSeek, onDismiss) }
        "CLEAR" -> project?.let { p ->
            var frames by remember { mutableStateOf(true) }; var subtitles by remember { mutableStateOf(true) }
            var start by remember { mutableStateOf("00:00:00.000") }
            var end by remember { mutableStateOf(VideoSubtitlesTime.format(p.durationMs)) }
            AlertDialog(onDismissRequest = onDismiss, title = { Text("清除译文") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将清除所选范围的译文（包括人工校对）。原视频和原字幕保留；对应任务先暂停。")
                Row { Checkbox(frames, { frames = it }); Text("画面") }; Row { Checkbox(subtitles, { subtitles = it }); Text("字幕译文") }
                OutlinedTextField(start, { start = it }, label = { Text("开始 HH:MM:SS.mmm") })
                OutlinedTextField(end, { end = it }, label = { Text("结束 HH:MM:SS.mmm") })
            } }, confirmButton = { TextButton(onClick = { scope.launch {
                try { val from = VideoSubtitlesTime.parse(start); val to = VideoSubtitlesTime.parse(end)
                    require(frames || subtitles); require(to <= p.durationMs)
                    container.videoTasks.clear(id, frames, subtitles, from, to); onDismiss()
                } catch(e: Exception) { error = e.message }
            } }) { Text("确认清除") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
        } ?: run { if(!loading) NoResultsDialog(onDismiss) }
        "EXPORT" -> AlertDialog(onDismissRequest = onDismiss, title = { Text("导出成果") }, text = { Column {
            Text("SRT 保存文字和时间；可恢复成果包保存画面气泡、字幕与样式。")
            listOf("译文 SRT" to false, "双语 SRT" to true).forEach { (label, bilingual) -> TextButton(onClick = { scope.launch {
                try { container.videoExports.submit(id, "SRT", bilingual); onDismiss() } catch(e: Exception) { error = e.message }
            } }) { Text(label) } }
            TextButton(onClick = { scope.launch { try { container.videoExports.submit(id, "BUNDLE"); onDismiss() } catch(e: Exception) { error = e.message } } }) { Text("可恢复成果包（不是 MP4）") }
            TextButton(onClick = { selectResults.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("从成果包恢复（合并保留本机修订）") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
        "INFO" -> {
            var info by remember { mutableStateOf("") }
            LaunchedEffect(id, project?.revision) { container.videoCatalog.find(id)?.let { v ->
                val tracks = runCatching { container.videoAudio.tracks(v.uri) }.getOrDefault(emptyList())
                val resources = project?.let { container.videoLibrary.resources(id) }.orEmpty()
                info = "${v.name}\n${v.volume} · ${v.parentPath}\n${v.uri}\n${v.width}×${v.height} · 旋转 ${v.rotation}°\n${v.bytes} 字节 · ${v.durationMs} ms\n播放 ${v.playCount} 次 · 进度 ${v.positionMs} ms\n${if(v.accessible) "可读取" else v.accessReason}\n" +
                    tracks.joinToString("\n") { "${it.type} #${it.index} · ${it.mime} · ${it.language}" } + "\n" + resources.joinToString("\n") { "${it.kind} · ${it.bytes} 字节" }
            } }
            AlertDialog(onDismissRequest = onDismiss, title = { Text("视频信息") }, text = { Text(info, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                dismissButton = { TextButton(onClick = { selectSource.launch(arrayOf("video/*")) }) { Text("重新定位原文件") } })
        }
    }
    if(loading && error == null) AlertDialog(onDismissRequest = onDismiss, title = { Text("正在读取视频…") }, text = { CircularProgressIndicator() }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    relocation?.let { candidate ->
        val changed = project != null && project!!.sha256 != candidate.sha256
        AlertDialog(onDismissRequest = { relocation = null }, title = { Text(if(changed) "建立新源版本" else "绑定已核对文件") },
            text = { Text(if(changed) "所选视频内容与原视频不同。确认后使用新源版本；旧源的气泡、字幕和人工修订保留，任务保持暂停。"
                else "已完成内容校验。绑定所选文件并保留书架、播放统计及匹配的正式成果；原任务需更新配置后继续。") },
            confirmButton = { TextButton(onClick = {
                relocation = null; loading = true
                scope.launch { try { container.videoTasks.bindSource(id, candidate, allowNewVersion = true); project = container.videoTasks.existingProject(id) }
                    catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message } finally { loading = false } }
            }) { Text(if(changed) "确认建立新源版本" else "确认绑定") } },
            dismissButton = { TextButton(onClick = { relocation = null }) { Text("取消") } })
    }
}
@Composable private fun NoResultsDialog(onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("没有正式成果") }, text = { Text("这个视频尚无可清除或导出的译文。") }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }) }

/** Kept independent from SRT parser sequence numbers. */
object VideoSubtitlesTime {
    fun format(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    fun parse(value: String): Long {
        val match = Regex("(\\d{2,}):([0-5]\\d):([0-5]\\d)[.,](\\d{3})").matchEntire(value.trim()) ?: error("时间格式应为 HH:MM:SS.mmm")
        return Math.addExact(Math.multiplyExact(match.groupValues[1].toLong(), 3_600_000), match.groupValues[2].toLong() * 60_000 + match.groupValues[3].toLong() * 1000 + match.groupValues[4].toLong())
    }
}
