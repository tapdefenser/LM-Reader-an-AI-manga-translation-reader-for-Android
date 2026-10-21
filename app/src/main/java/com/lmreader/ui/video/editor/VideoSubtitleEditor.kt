@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lmreader.ui.video.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.media.VideoResultDao
import com.lmreader.core.database.media.VideoSettingsRecord
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.VideoProject
import com.lmreader.ui.video.formatVideoTimecode
import com.lmreader.ui.video.parseVideoTimecode
import com.lmreader.ui.video.resolveVideoSubtitleStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Subtitle proofreading and display settings. Cue times stay on the raw source
 * timeline; the configured offset is a playback/export transform owned by style.
 */
@Composable
fun VideoSubtitleEditor(
    container: AppContainer,
    project: VideoProject,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dao = remember(container, project.id) { container.videoResults.results() }
    val observed by dao.observeCues(project.id).collectAsStateWithLifecycle(initialValue = emptyList())
    var initialized by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<List<VideoCueRecord>>(emptyList()) }
    var draft by remember { mutableStateOf<SubtitleEditorDraft?>(null) }
    var style by remember { mutableStateOf(VideoEditorSubtitleStyle()) }
    var baselineStyle by remember { mutableStateOf(VideoEditorSubtitleStyle()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var playheadMs by remember { mutableStateOf(positionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))) }

    LaunchedEffect(project.id) {
        try {
            val (rows, settings) = withContext(Dispatchers.IO) { dao.cues(project.id) to dao.settings(project.id) }
            val cues = sortCues(rows.filter { it.sourceHash == project.sha256 })
            baseline = cues
            draft = SubtitleEditorDraft(cues, null)
            style = VideoEditorSubtitleStyle.parse(resolveVideoSubtitleStyle(container.preferences.videoSubtitleStyle.first(), settings?.style ?: "{}").toString())
            baselineStyle = style
            initialized = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "无法读取字幕"
        }
    }
    LaunchedEffect(observed) {
        if (!initialized) return@LaunchedEffect
        val current = draft ?: return@LaunchedEffect
        if (current.cues == baseline) {
            val rows = sortCues(observed.filter { it.sourceHash == project.sha256 })
            if (rows != baseline) {
                val selected = current.selectedId?.takeIf { id -> rows.any { it.cueId == id } }
                baseline = rows
                draft = SubtitleEditorDraft(rows, selected)
            }
        }
    }

    fun requestExit() {
        if (saving) return
        val cuesDirty = draft?.let { it.cues != baseline } ?: false
        if (cuesDirty || style != baselineStyle) showExit = true else onDismiss()
    }

    fun persistSettings() {
        if (saving) return
        val pending = style
        saving = true
        saveError = null
        scope.launch {
            try {
                persistStyle(container, dao, project.id, pending)
                baselineStyle = pending
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError = error.message ?: "显示设置未保存"
            } finally {
                saving = false
            }
        }
    }

    fun persist(exitAfter: Boolean) {
        val current = draft ?: return
        if (saving) return
        val pendingStyle = style
        saving = true
        saveError = null
        scope.launch {
            try {
                val saved = persistCues(container, dao, project, current, baseline)
                baseline = saved
                val selected = current.selectedId?.takeIf { id -> saved.any { it.cueId == id } }
                draft = SubtitleEditorDraft(saved, selected)
                if (pendingStyle != baselineStyle) {
                    persistStyle(container, dao, project.id, pendingStyle)
                    baselineStyle = pendingStyle
                }
                if (exitAfter) onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError = when (error) {
                    is IOException -> "无法写入本地存储，草稿已保留；请检查可用空间后重试"
                    else -> error.message ?: "保存失败，草稿已保留"
                }
            } finally {
                saving = false
            }
        }
    }

    Dialog(onDismissRequest = { requestExit() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = draft
            if (current == null) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loadError == null) CircularProgressIndicator()
                    Text(loadError ?: "正在读取字幕…")
                    if (loadError != null) TextButton(onClick = onDismiss) { Text("关闭") }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(current.selectedId) {
                    if (current.selectedId != null) listState.animateScrollToItem(1)
                }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("字幕校对", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (current.cues != baseline || style != baselineStyle) "有未保存的修改" else "全部修改已保存",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { persist(false) }, enabled = !saving) {
                            Text(if (saving) "保存中…" else "保存")
                        }
                        TextButton(onClick = { requestExit() }, enabled = !saving) { Text("关闭") }
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "section:transport") {
                            SubtitleTransportCard(
                                draft = current,
                                videoId = project.id,
                                sourceHash = project.sha256,
                                durationMs = project.durationMs,
                                playheadMs = playheadMs,
                                offsetMs = style.offsetMs,
                                enabled = !saving,
                                onPlayhead = { playheadMs = it },
                                onSeek = onSeek,
                                onSelect = { draft = current.select(it) },
                                addCue = { cue -> draft = current.addCue(cue) },
                                onUndo = { draft = current.undoChange() },
                            )
                        }
                        current.selected?.let { selected ->
                            item(key = "section:detail") {
                                CueDetailCard(
                                    cue = selected,
                                    durationMs = project.durationMs,
                                    offsetMs = style.offsetMs,
                                    saving = saving,
                                    onSeek = onSeek,
                                    onApply = { source, translation, start, end ->
                                        draft = current.editSelected(source, translation, start, end)
                                    },
                                    onDelete = { draft = current.deleteSelected() },
                                    onRestore = { draft = current.restoreSelected() },
                                    onClearTranslation = { draft = current.clearTranslation() },
                                    onUndo = { draft = current.undoChange() },
                                    onError = { saveError = it },
                                )
                            }
                        }
                        if (current.cues.isEmpty()) {
                            item(key = "section:empty") {
                                Text("数据库中还没有字幕。可在播放器导入 SRT，或在此新增一条字幕后保存。")
                            }
                        }
                        items(sortCues(current.cues), key = { it.cueId }) { cue ->
                            CueRow(
                                cue = cue,
                                selected = cue.cueId == current.selectedId,
                                offsetMs = style.offsetMs,
                                durationMs = project.durationMs,
                                enabled = !saving,
                                onSelect = { draft = current.select(cue.cueId) },
                                onSeek = onSeek,
                                onDelete = {
                                    draft = current.select(cue.cueId).deleteSelected()
                                },
                                onRestore = {
                                    draft = current.select(cue.cueId).restoreSelected()
                                },
                            )
                        }
                    }
                    saveError?.let { message ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { saveError = null }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { if (!saving) showExit = false },
            title = { Text("保存字幕？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("字幕尚未保存。保存后继续、不保存退出，或取消继续校对。显示样式已移到视频翻译选项。")
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    persist(true)
                }, enabled = !saving) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showExit = false
                        onDismiss()
                    }, enabled = !saving) { Text("不保存") }
                    TextButton(onClick = { showExit = false }, enabled = !saving) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun SubtitleTransportCard(
    draft: SubtitleEditorDraft,
    videoId: String,
    sourceHash: String,
    durationMs: Long,
    playheadMs: Long,
    offsetMs: Long,
    enabled: Boolean,
    onPlayhead: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSelect: (String?) -> Unit,
    addCue: (VideoCueRecord) -> Unit,
    onUndo: () -> Unit,
) {
    val ordered = sortCues(draft.cues)
    val active = ordered.filterNot { it.deleted }
    val forward = active.firstOrNull { midpoint(it) + offsetMs > playheadMs }
    val backward = active.lastOrNull { midpoint(it) + offsetMs < playheadMs }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    val current = (dragMs ?: playheadMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("当前位置 ${formatVideoTimecode(current)}", style = MaterialTheme.typography.labelMedium)
                Text("总时长 ${formatVideoTimecode(durationMs)}", style = MaterialTheme.typography.labelSmall)
            }
            Slider(
                value = (dragMs ?: playheadMs).toFloat().coerceIn(0f, durationMs.toFloat()),
                onValueChange = { dragMs = it.toLong() },
                onValueChangeFinished = {
                    dragMs?.let {
                        val value = it.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                        onPlayhead(value)
                        onSeek(value)
                    }
                    dragMs = null
                },
                valueRange = 0f..durationMs.toFloat(),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            EditorTimeBand(
                durationMs = durationMs,
                playheadMs = current,
                ranges = active.map { EditorTimeBandRange((it.startMs + offsetMs).coerceIn(0, durationMs), (it.endMs + offsetMs).coerceIn(0, durationMs)) },
                selected = draft.selected?.let { EditorTimeBandRange((it.startMs + offsetMs).coerceIn(0, durationMs), (it.endMs + offsetMs).coerceIn(0, durationMs)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = backward != null && enabled,
                    onClick = {
                        backward?.let {
                            val target = (midpoint(it) + offsetMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                            onPlayhead(target)
                            onSeek(target)
                            onSelect(it.cueId)
                        }
                    },
                ) { Text("上一段") }
                TextButton(
                    enabled = forward != null && enabled,
                    onClick = {
                        forward?.let {
                            val target = (midpoint(it) + offsetMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                            onPlayhead(target)
                            onSeek(target)
                            onSelect(it.cueId)
                        }
                    },
                ) { Text("下一段") }
                TextButton(
                    enabled = enabled,
                    onClick = {
                        val start = (current - offsetMs).coerceAtLeast(0)
                        val nextStart = active.map { it.startMs }.filter { it >= start }.minOrNull()
                        val end = minOf(nextStart ?: Long.MAX_VALUE, start + 2_000L, durationMs)
                        addError = when {
                            start >= durationMs -> "当前位置已到视频结尾，没有可用的字幕时长；请先向前移动播放位置"
                            end <= start -> "当前位置没有可用的字幕时长，请调整播放位置后重试"
                            draft.cues.size >= 20_000 -> "字幕数量已达上限"
                            else -> null
                        }
                        if (addError == null) {
                            addCue(
                                VideoCueRecord(
                                    videoId = videoId,
                                    cueId = "manual:${UUID.randomUUID()}",
                                    sourceHash = sourceHash,
                                    trackId = "manual",
                                    startMs = start,
                                    endMs = end,
                                    source = "",
                                    translation = "",
                                    deleted = false,
                                    manuallyEdited = true,
                                    revision = "",
                                ),
                            )
                        }
                    },
                ) { Text("新增字幕") }
                TextButton(enabled = draft.undo.isNotEmpty() && enabled, onClick = onUndo) { Text("撤销") }
            }
            addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            Text("在下方列表选择字幕；重叠字幕各自保留，可逐条选择。", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CueDetailCard(
    cue: VideoCueRecord,
    durationMs: Long,
    offsetMs: Long,
    saving: Boolean,
    onSeek: (Long) -> Unit,
    onApply: (String, String, Long, Long) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onClearTranslation: () -> Unit,
    onUndo: () -> Unit,
    onError: (String?) -> Unit,
) {
    var source by remember(cue.cueId) { mutableStateOf(cue.source) }
    var translation by remember(cue.cueId) { mutableStateOf(cue.translation) }
    var startText by remember(cue.cueId) { mutableStateOf(formatVideoTimecode(cue.startMs)) }
    var endText by remember(cue.cueId) { mutableStateOf(formatVideoTimecode(cue.endMs)) }
    var validation by remember(cue.cueId) { mutableStateOf<String?>(null) }
    LaunchedEffect(cue.cueId, cue.startMs, cue.endMs, cue.source, cue.translation) {
        source = cue.source
        translation = cue.translation
        startText = formatVideoTimecode(cue.startMs)
        endText = formatVideoTimecode(cue.endMs)
    }
    val enabled = !saving && !cue.deleted
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字幕 ${cue.cueId}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (cue.deleted) {
                    Text("已删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                } else if (cue.manuallyEdited) {
                    Text("已校对", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (cue.deleted) {
                Text("这条字幕是删除墓碑，原文与译文仍然保留。恢复后会重新参与播放。", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onRestore, enabled = !saving) { Text("恢复字幕") }
                    TextButton(onClick = onUndo, enabled = !saving) { Text("撤销") }
                }
            } else {
                OutlinedTextField(
                    value = source,
                    onValueChange = { if (it.length <= 20_000) source = it },
                    label = { Text("原文") },
                    enabled = enabled,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { if (it.length <= 20_000) translation = it },
                    label = { Text("译文") },
                    enabled = enabled,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.take(16) },
                        label = { Text("起点") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.take(16) },
                        label = { Text("终点") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("时间格式：秒 / 分:秒 / 时:分:秒（留空为 0）；有效范围 0 ≤ 起点 < 终点 ≤ ${formatVideoTimecode(durationMs)}",
                    style = MaterialTheme.typography.labelSmall)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = enabled,
                        onClick = {
                            val start = parseVideoTimecode(startText)
                            val end = parseVideoTimecode(endText)
                            validation = when {
                                source.length > 20_000 || translation.length > 20_000 -> "原文或译文过长"
                                Regex("\\n\\s*\\n").containsMatchIn(source) || Regex("\\n\\s*\\n").containsMatchIn(translation) ->
                                    "字幕内不能包含空白行（会破坏 SRT 分段）"
                                start == null || end == null -> "时间格式应为秒 / 分:秒 / 时:分:秒"
                                !validBubbleTimeRange(start, end, durationMs) -> "必须满足 0 ≤ 起点 < 终点 ≤ 视频总时长"
                                else -> null
                            }
                            if (validation == null && start != null && end != null) {
                                onApply(source, translation, start, end)
                                onError(null)
                            } else {
                                onError(validation)
                            }
                        },
                    ) { Text("应用修改") }
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            val target = (cue.startMs + offsetMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                            onSeek(target)
                        },
                    ) { Text("跳转") }
                    TextButton(enabled = !saving, onClick = onUndo) { Text("撤销") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = enabled, onClick = onClearTranslation) { Text("清除译文（保留原文）") }
                    TextButton(enabled = enabled, onClick = onDelete) { Text("删除字幕（保留墓碑）") }
                }
            }
        }
    }
}

@Composable
private fun CueRow(
    cue: VideoCueRecord,
    selected: Boolean,
    offsetMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSelect: () -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatVideoTimecode(cue.startMs)} → ${formatVideoTimecode(cue.endMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    textDecoration = if (cue.deleted) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
                Text(cue.trackId, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                cue.source.ifBlank { "（无原文）" },
                textDecoration = if (cue.deleted) TextDecoration.LineThrough else null,
            )
            Text(
                cue.translation.ifBlank { "（无译文）" },
                color = if (cue.translation.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Row {
                if (cue.deleted) Text("已删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = enabled, onClick = {
                    val target = (cue.startMs + offsetMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                    onSeek(target)
                }) { Text("定位") }
                TextButton(enabled = enabled, onClick = if (cue.deleted) onRestore else onDelete) {
                    Text(if (cue.deleted) "恢复" else "删除")
                }
            }
        }
    }
}

@Composable
internal fun SubtitleStyleCard(
    style: VideoEditorSubtitleStyle,
    dirty: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onChange: (VideoEditorSubtitleStyle) -> Unit,
    onSave: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字幕显示设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            Text("这里的偏移、字号与颜色只影响播放/导出显示，不改写字幕时间或译文。", style = MaterialTheme.typography.labelSmall)
            if (expanded) {
                Text("显示内容", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StyleChoice("原文", style.display == "ORIGINAL", enabled) { onChange(style.copy(display = "ORIGINAL")) }
                    StyleChoice("译文", style.display == "TRANSLATED", enabled) { onChange(style.copy(display = "TRANSLATED")) }
                    StyleChoice("双语", style.display == "BILINGUAL", enabled) { onChange(style.copy(display = "BILINGUAL")) }
                }
                StyleSlider("字号 ${style.fontSizeSp}sp", style.fontSizeSp.toFloat(), 10f..72f, enabled) {
                    onChange(style.copy(fontSizeSp = it.roundToInt()))
                }
                StyleSlider("行距 ${"%.2f".format(Locale.US, style.lineSpacing)}", style.lineSpacing, .8f..2f, enabled) {
                    onChange(style.copy(lineSpacing = it))
                }
                StyleSlider("水平位置 ${"%.2f".format(Locale.US, style.x)}", style.x, 0f..1f, enabled) {
                    onChange(style.copy(x = it))
                }
                StyleSlider("垂直位置 ${"%.2f".format(Locale.US, style.y)}", style.y, 0f..1f, enabled) {
                    onChange(style.copy(y = it))
                }
                StyleSlider("背景透明度 ${"%.2f".format(Locale.US, style.backgroundAlpha)}", style.backgroundAlpha, 0f..1f, enabled) {
                    onChange(style.copy(backgroundAlpha = it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("对齐", style = MaterialTheme.typography.labelLarge)
                    StyleChoice("左", style.align == "LEFT", enabled) { onChange(style.copy(align = "LEFT")) }
                    StyleChoice("中", style.align == "CENTER", enabled) { onChange(style.copy(align = "CENTER")) }
                    StyleChoice("右", style.align == "RIGHT", enabled) { onChange(style.copy(align = "RIGHT")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = style.enabled, onCheckedChange = { onChange(style.copy(enabled = it)) }, enabled = enabled)
                    Text("启用字幕")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = style.smartColor, onCheckedChange = { onChange(style.copy(smartColor = it)) }, enabled = enabled)
                    Text("智能配色/描边（运行时）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("字体", style = MaterialTheme.typography.labelLarge)
                    StyleChoice("无衬线", style.fontFamily == "sans-serif", enabled) { onChange(style.copy(fontFamily = "sans-serif")) }
                    StyleChoice("衬线", style.fontFamily == "serif", enabled) { onChange(style.copy(fontFamily = "serif")) }
                    StyleChoice("等宽", style.fontFamily == "monospace", enabled) { onChange(style.copy(fontFamily = "monospace")) }
                }
                ColorChoiceRow("文字颜色", style.textColor, enabled) { onChange(style.copy(textColor = it)) }
                ColorChoiceRow("描边/背景色", style.outlineColor, enabled) { onChange(style.copy(outlineColor = it)) }
                var offsetText by remember(style.offsetMs) { mutableStateOf(style.offsetMs.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { offsetText = it.filter { ch -> ch == '-' || ch.isDigit() }.take(9) },
                        label = { Text("字幕偏移（毫秒）") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    val parsed = offsetText.toLongOrNull()
                    TextButton(
                        enabled = enabled && parsed != null && parsed in -3_600_000L..3_600_000L,
                        onClick = { parsed?.let { onChange(style.copy(offsetMs = it)) } },
                    ) { Text("应用偏移") }
                }
                Text("偏移只保存在显示设置中，不写入每一条字幕时间。", style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(enabled = dirty && !saving, onClick = onSave) { Text(if (saving) "保存中…" else "保存显示设置") }
                }
            }
        }
    }
}

@Composable
private fun StyleChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, enabled = enabled)
}

@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
        )
    }
}

private val subtitleColorPresets = intArrayOf(
    -1,
    -16_777_216,
    -2_176,
    -64_025,
    -33_024,
    -8_428_800,
    -16_711_936,
    -16_777_215,
)

@Composable
private fun ColorChoiceRow(label: String, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subtitleColorPresets.forEach { value ->
                val chosen = value == selected
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(value))
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .45f), CircleShape)
                        .clickable(enabled = enabled) { onSelect(value) },
                )
            }
        }
    }
}

private fun midpoint(cue: VideoCueRecord): Long = cue.startMs + (cue.endMs - cue.startMs) / 2

internal suspend fun persistStyle(
    container: AppContainer,
    dao: VideoResultDao,
    videoId: String,
    style: VideoEditorSubtitleStyle,
) = withContext(Dispatchers.IO) {
    var saved: VideoSettingsRecord? = null
    container.videoTasks.snapshot(videoId) {
        val row = dao.settings(videoId) ?: VideoSettingsRecord(videoId = videoId)
        val next = row.copy(style = style.encode(row.style), revision = UUID.randomUUID().toString())
        dao.putSettings(next)
        saved = next
    }
    saved ?: error("显示设置未保存")
}

internal data class VideoEditorSubtitleStyle(
    val fontSizeSp: Int = 22,
    val lineSpacing: Float = 1.1f,
    val x: Float = .5f,
    val y: Float = .9f,
    val align: String = "CENTER",
    val textColor: Int = -1,
    val outlineColor: Int = -16_777_216,
    val backgroundAlpha: Float = .35f,
    val display: String = "TRANSLATED",
    val smartColor: Boolean = false,
    val offsetMs: Long = 0L,
    val enabled: Boolean = true,
    val fontFamily: String = "sans-serif",
    val trackId: String = "all",
    val overrideStyle: Boolean = false,
) {
    fun encode(base: String?): String {
        val json = runCatching { JSONObject(base ?: "{}") }.getOrDefault(JSONObject())
        json.put("fontSizeSp", fontSizeSp)
        json.put("lineSpacing", lineSpacing.toDouble())
        json.put("x", x.toDouble())
        json.put("y", y.toDouble())
        json.put("align", align)
        json.put("textColor", textColor)
        json.put("outlineColor", outlineColor)
        json.put("backgroundAlpha", backgroundAlpha.toDouble())
        json.put("display", display)
        json.put("smartColor", smartColor)
        json.put("offsetMs", offsetMs)
        json.put("enabled", enabled)
        json.put("fontFamily", fontFamily)
        json.put("trackId", trackId).put("overrideStyle", overrideStyle)
        return json.toString()
    }

    companion object {
        fun parse(text: String?): VideoEditorSubtitleStyle {
            val json = runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
            fun number(key: String, fallback: Double): Float {
                val value = runCatching { json.getDouble(key) }.getOrDefault(fallback)
                return if (value.isFinite()) value.toFloat() else fallback.toFloat()
            }
            fun text(key: String, fallback: String) =
                json.optString(key, fallback).takeIf { it.isNotBlank() && it != "null" } ?: fallback
            val align = text("align", "CENTER").uppercase(Locale.US)
                .takeIf { it in setOf("LEFT", "CENTER", "RIGHT") } ?: "CENTER"
            val display = text("display", "TRANSLATED").uppercase(Locale.US)
                .takeIf { it in setOf("ORIGINAL", "TRANSLATED", "BILINGUAL") } ?: "TRANSLATED"
            val fontFamily = text("fontFamily", "sans-serif")
                .takeIf { it in setOf("sans-serif", "serif", "monospace") } ?: "sans-serif"
            return VideoEditorSubtitleStyle(
                fontSizeSp = number("fontSizeSp", 22.0).roundToInt().coerceIn(8, 96),
                lineSpacing = number("lineSpacing", 1.1).coerceIn(.5f, 3f),
                x = number("x", .5).coerceIn(0f, 1f),
                y = number("y", .9).coerceIn(0f, 1f),
                align = align,
                textColor = runCatching { json.getInt("textColor") }.getOrDefault(-1),
                outlineColor = runCatching { json.getInt("outlineColor") }.getOrDefault(-16_777_216),
                backgroundAlpha = number("backgroundAlpha", .35).coerceIn(0f, 1f),
                display = display,
                smartColor = json.optBoolean("smartColor", false),
                offsetMs = runCatching { json.getLong("offsetMs") }.getOrDefault(0L).coerceIn(-3_600_000L, 3_600_000L),
                enabled = json.optBoolean("enabled", true),
                fontFamily = fontFamily,
                trackId = text("trackId", "all"),
                overrideStyle = json.optBoolean("overrideStyle", false),
            )
        }
    }
}
