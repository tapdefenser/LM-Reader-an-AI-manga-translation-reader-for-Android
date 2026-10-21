package com.lmreader.ui.video.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.media.VideoResultDao
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.VideoProject
import com.lmreader.ui.video.formatVideoTimecode
import com.lmreader.ui.video.parseVideoTimecode
import com.lmreader.ui.video.resolveVideoSubtitleStyle
import com.lmreader.ui.video.resolvedVideoSubtitleTrack
import com.lmreader.ui.video.videoSubtitleMatchesTrack
import com.lmreader.ui.video.generatedVideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Optional bridge used by the hosting bubble editor to flush cue text before it closes.
 *
 * The cue editor stays the owner of its draft; the host only asks it to save and reads
 * whether unsaved cue text exists. No machine task is submitted from this component.
 */
class VideoCurrentCueEditorHandle {
    internal var saveEdits: (suspend () -> Boolean)? = null
    internal var dirtyProvider: () -> Boolean = { false }

    val isDirty: Boolean get() = dirtyProvider()
}

/**
 * Compact editor for the single subtitle cue at [positionMs].
 *
 * Cue times remain on the raw source timeline. The previous/next triangles seek to the
 * START of the adjacent cue in stable time order; overlapping cues are never merged.
 * The text field defaults to the translated text and can be toggled to the source text.
 * Edits are real cue drafts saved through the existing revision-checked [persistCues].
 */
@Composable
fun VideoCurrentCueEditor(
    container: AppContainer,
    project: VideoProject,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    handle: VideoCurrentCueEditorHandle? = null,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val dao = remember(container, project.id) { container.videoResults.results() }
    val settings by dao.observeSettings(project.id).collectAsStateWithLifecycle(null)
    val globalStyle by container.preferences.videoSubtitleStyle.collectAsStateWithLifecycle("{}")
    val tasks by container.taskScheduler.tasks.collectAsStateWithLifecycle()
    val style = remember(settings?.style, globalStyle) { resolveVideoSubtitleStyle(globalStyle, settings?.style ?: "{}") }
    val offset = style.optLong("offsetMs")
    val sourcePosition = positionMs - offset
    val generatedTrack = generatedVideoTrack(project.id, tasks)
    var baseline by remember(project.id, project.sha256) { mutableStateOf<List<VideoCueRecord>>(emptyList()) }
    var draft by remember(project.id, project.sha256) { mutableStateOf<SubtitleEditorDraft?>(null) }
    var loading by remember(project.id, project.sha256) { mutableStateOf(true) }
    var loadError by remember(project.id, project.sha256) { mutableStateOf<String?>(null) }
    var reloadToken by remember(project.id, project.sha256) { mutableStateOf(0) }
    var saveError by remember(project.id, project.sha256) { mutableStateOf<String?>(null) }
    var addError by remember(project.id, project.sha256) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var fieldIsSource by remember { mutableStateOf(false) }
    var fieldText by remember { mutableStateOf("") }
    var fieldPending by remember { mutableStateOf(false) }
    var activeCueId by remember { mutableStateOf<String?>(null) }
    var pinnedCueId by remember { mutableStateOf<String?>(null) }
    var pendingPinId by remember { mutableStateOf<String?>(null) }
    var timingCueId by remember { mutableStateOf<String?>(null) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var timingError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(project.id, project.sha256, reloadToken) {
        loading = true
        loadError = null
        try {
            val rows = withContext(Dispatchers.IO) { dao.cues(project.id) }
                .filter { it.sourceHash == project.sha256 }
            val ordered = sortCues(rows)
            baseline = ordered
            draft = SubtitleEditorDraft(ordered, null)
            activeCueId = null
            pinnedCueId = null
            pendingPinId = null
            fieldPending = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "无法读取字幕"
        } finally {
            loading = false
        }
    }

    val selectedTrack = resolvedVideoSubtitleTrack(style, draft?.cues.orEmpty())
    val ordered = draft?.cues?.filter { videoSubtitleMatchesTrack(it.trackId, selectedTrack, generatedTrack) }?.let(::sortCues).orEmpty()
    val live = ordered.filterNot { it.deleted }
    val pinned = pinnedCueId?.let { id ->
        live.firstOrNull { cue -> cue.cueId == id && sourcePosition >= cue.startMs && sourcePosition < cue.endMs }
    }
    val current = pinned ?: live.lastOrNull { sourcePosition >= it.startMs && sourcePosition < it.endMs }
    val deletedHere = ordered.lastOrNull { it.deleted && sourcePosition >= it.startMs && sourcePosition < it.endMs }
    val currentIndex = current?.let { cue -> live.indexOfFirst { it.cueId == cue.cueId } } ?: -1
    val previous = when {
        currentIndex > 0 -> live[currentIndex - 1]
        current == null -> live.lastOrNull { it.startMs < sourcePosition }
        else -> null
    }
    val next = when {
        currentIndex in 0 until live.lastIndex -> live[currentIndex + 1]
        current == null -> live.firstOrNull { it.startMs > sourcePosition }
        else -> null
    }
    val dirty = draft != null && draft?.cues != baseline

    LaunchedEffect(dirty, fieldPending) {
        onDirtyChange(dirty || fieldPending)
    }

    LaunchedEffect(positionMs) {
        val wanted = pendingPinId
        if (wanted != null) {
            pinnedCueId = wanted
            pendingPinId = null
        } else {
            val pinnedId = pinnedCueId ?: return@LaunchedEffect
            val stillHolding = draft?.cues.orEmpty().any { cue ->
                cue.cueId == pinnedId && !cue.deleted &&
                    sourcePosition >= cue.startMs && sourcePosition < cue.endMs
            }
            if (!stillHolding) pinnedCueId = null
        }
    }

    fun commitText(cueId: String, value: String) {
        val currentDraft = draft ?: return
        val item = currentDraft.cues.firstOrNull { it.cueId == cueId } ?: return
        if (item.deleted) return
        val source = if (fieldIsSource) value else item.source
        val translation = if (fieldIsSource) item.translation else value
        if (source == item.source && translation == item.translation) return
        draft = currentDraft.select(cueId)
            .editSelected(source, translation, item.startMs, item.endMs)
    }

    fun commitField() {
        if (!fieldPending) return
        val id = activeCueId ?: current?.cueId ?: return
        commitText(id, fieldText)
        fieldPending = false
    }

    suspend fun saveEdits(): Boolean {
        commitField()
        val currentDraft = draft ?: return true
        if (saving) return false
        if (currentDraft.cues == baseline && !fieldPending) return true
        saving = true
        saveError = null
        return try {
            val saved = withContext(Dispatchers.IO) {
                persistCues(container, dao, project, currentDraft, baseline)
            }.filter { it.sourceHash == project.sha256 }
            baseline = saved
            val selected = currentDraft.selectedId?.takeIf { id -> saved.any { it.cueId == id } }
                ?: current?.cueId?.takeIf { id -> saved.any { it.cueId == id } }
            draft = SubtitleEditorDraft(saved, selected)
            fieldPending = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saveError = when (error) {
                is IOException -> "无法写入本地存储，字幕草稿已保留；请检查可用空间后重试"
                else -> error.message ?: "字幕保存失败，草稿已保留"
            }
            false
        } finally {
            saving = false
        }
    }

    fun updateField(value: String) {
        if (value.length > 20_000) return
        fieldText = value
        fieldPending = true
        saveError = null
    }

    fun toggleSource() {
        if (current == null) return
        commitField()
        fieldIsSource = !fieldIsSource
    }

    fun seekToCue(cue: VideoCueRecord) {
        if (saving) return
        commitField()
        pendingPinId = cue.cueId
        scope.launch {
            val currentDraft = draft
            if (currentDraft != null && currentDraft.cues != baseline) {
                if (!saveEdits()) {
                    pendingPinId = null
                    return@launch
                }
            }
            // Overlapping cues can have the same start. Select explicitly even when
            // seeking does not change the playhead and its effect therefore will not run.
            pinnedCueId = cue.cueId
            pendingPinId = null
            onSeek((cue.startMs + offset).coerceIn(0, (project.durationMs - 1).coerceAtLeast(0)))
        }
    }

    fun addCue() {
        val existingDraft = draft ?: return
        commitField()
        val currentDraft = draft ?: existingDraft
        if (currentDraft.cues.size >= 20_000) {
            addError = "字幕数量已达上限"
            return
        }
        val start = sourcePosition.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        val nextStart = currentDraft.cues
            .filterNot { it.deleted }
            .map { it.startMs }
            .filter { it > start }
            .minOrNull()
        val end = minOf(nextStart ?: Long.MAX_VALUE, start + 2_000L, project.durationMs)
        addError = when {
            start >= project.durationMs -> "当前位置已到视频结尾，没有可用的字幕时长"
            end <= start -> "当前位置没有可用的字幕时长，请调整播放位置后重试"
            else -> null
        }
        if (addError != null) return
        val cue = VideoCueRecord(
            videoId = project.id,
            cueId = "manual:${UUID.randomUUID()}",
            sourceHash = project.sha256,
            trackId = if(selectedTrack == "generated") generatedTrack ?: "manual" else selectedTrack,
            startMs = start,
            endMs = end,
            source = "",
            translation = "",
            deleted = false,
            manuallyEdited = true,
            revision = "",
        )
        draft = currentDraft.addCue(cue)
        pinnedCueId = cue.cueId
        pendingPinId = null
        fieldIsSource = false
        fieldPending = false
        saveError = null
    }

    fun deleteCurrent() {
        val cue = current ?: return
        commitField()
        draft = draft?.select(cue.cueId)?.deleteSelected()
        pinnedCueId = null
        pendingPinId = null
        fieldPending = false
    }

    fun restore(cueId: String) {
        draft = draft?.select(cueId)?.restoreSelected()
        pinnedCueId = cueId
        pendingPinId = null
        fieldPending = false
    }

    fun undo() {
        val undone = draft?.undoChange() ?: return
        draft = undone
        fieldPending = false
        val restored = activeCue(undone.cues.filter { videoSubtitleMatchesTrack(it.trackId, selectedTrack, generatedTrack) }, sourcePosition, pinnedCueId)
        if (pinnedCueId != null && undone.cues.none { it.cueId == pinnedCueId && !it.deleted }) {
            pinnedCueId = null
        }
        activeCueId = restored?.cueId
        fieldText = cueText(restored, fieldIsSource)
    }

    LaunchedEffect(current?.cueId, fieldIsSource) {
        val previousId = activeCueId
        if (fieldPending && previousId != null && previousId != current?.cueId) {
            commitText(previousId, fieldText)
        }
        activeCueId = current?.cueId
        fieldText = cueText(current, fieldIsSource)
        fieldPending = false
    }

    SideEffect {
        handle?.saveEdits = { saveEdits() }
        handle?.dirtyProvider = { (draft?.cues != baseline) || fieldPending }
    }
    DisposableEffect(handle) {
        onDispose { handle?.saveEdits = null }
    }

    Column(
        modifier.fillMaxWidth()
            .background(Color(0xFF151515), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("当前字幕", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    loading -> "读取中…"
                    current == null -> "当前位置无字幕"
                    dirty || fieldPending -> "有未保存的修改"
                    else -> "已保存"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (dirty || fieldPending) Color(0xFFFFB74D) else Color.White.copy(alpha = .7f),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { scope.launch { saveEdits() } },
                enabled = enabled && !saving && !loading && (dirty || fieldPending),
            ) { Text(if (saving) "保存中…" else "保存") }
            TextButton(
                onClick = { undo() },
                enabled = enabled && !saving && draft?.undo.isNullOrEmpty().not(),
            ) { Text("撤销") }
        }
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在读取当前视频的字幕…", style = MaterialTheme.typography.bodySmall)
            }
            loadError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { reloadToken += 1 }) { Text("重试") }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { previous?.let { seekToCue(it) } },
                        enabled = enabled && !saving && previous != null,
                    ) { Text("◁", fontSize = 20.sp) }
                    OutlinedTextField(
                        value = fieldText,
                        onValueChange = ::updateField,
                        modifier = Modifier.weight(1f),
                        enabled = enabled && !saving && current != null,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(if (fieldIsSource) "原文" else "译文") },
                        placeholder = { Text(if (current == null) "当前位置没有字幕" else "") },
                    )
                    TextButton(
                        onClick = { next?.let { seekToCue(it) } },
                        enabled = enabled && !saving && next != null,
                    ) { Text("▷", fontSize = 20.sp) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { toggleSource() },
                        enabled = enabled && !saving && current != null,
                    ) { Text(if (fieldIsSource) "切换译文" else "切换原文") }
                    TextButton(
                        onClick = { addCue() },
                        enabled = enabled && !saving && !loading,
                    ) { Text("新增") }
                    TextButton(
                        onClick = { deleteCurrent() },
                        enabled = enabled && !saving && current != null,
                    ) { Text("删除") }
                    current?.let {
                        TextButton(onClick = {
                            commitField(); timingCueId = it.cueId
                            startText = formatVideoTimecode(it.startMs); endText = formatVideoTimecode(it.endMs); timingError = null
                        }, enabled = enabled && !saving, modifier = Modifier.weight(1f)) { Text(
                            "${formatVideoTimecode(it.startMs)} → ${formatVideoTimecode(it.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                        ) }
                    } ?: Spacer(Modifier.weight(1f))
                    deletedHere?.takeIf { current == null }?.let { deleted ->
                        TextButton(
                            onClick = { restore(deleted.cueId) },
                            enabled = enabled && !saving,
                        ) { Text("恢复此处字幕") }
                    }
                }
                current?.let { cue ->
                    if (cue.manuallyEdited) {
                        Text("人工校对", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (current == null && deletedHere == null) {
                    Text(
                        "在字幕起点内停留会显示当前字幕；◀ ▶ 直接跳到上一段/下一段的起点。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = .7f),
                    )
                }
            }
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
    timingCueId?.let { id ->
        AlertDialog(onDismissRequest = { timingCueId = null }, title = { Text("字幕时间") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startText, { startText = it.take(16) }, label = { Text("开始") }, singleLine = true)
                OutlinedTextField(endText, { endText = it.take(16) }, label = { Text("结束") }, singleLine = true)
                Text("可填秒、分:秒或时:分:秒。")
                if(offset != 0L) Text("这里编辑原始时间；播放偏移仍单独生效。", style = MaterialTheme.typography.bodySmall)
                timingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            val start = parseVideoTimecode(startText); val end = parseVideoTimecode(endText)
            if(start == null || end == null || start < 0 || end <= start || end > project.durationMs)
                timingError = "需要满足 0 ≤ 开始 < 结束 ≤ 视频时长"
            else {
                val value = draft?.cues?.firstOrNull { it.cueId == id }
                if(value != null) draft = draft?.select(id)?.editSelected(value.source, value.translation, start, end)
                timingCueId = null
            }
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { timingCueId = null }) { Text("取消") } })
    }
}

private fun cueText(cue: VideoCueRecord?, source: Boolean): String =
    cue?.let { if (source) it.source else it.translation }.orEmpty()

private fun activeCue(
    cues: List<VideoCueRecord>,
    positionMs: Long,
    pinnedId: String?,
): VideoCueRecord? {
    val ordered = sortCues(cues)
    val live = ordered.filterNot { it.deleted }
    val pinned = pinnedId?.let { id ->
        live.firstOrNull { it.cueId == id && positionMs >= it.startMs && positionMs < it.endMs }
    }
    return pinned ?: live.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
}

/** Stable time order; overlapping cues stay as independent entries. */
internal fun sortCues(cues: List<VideoCueRecord>) =
    cues.sortedWith(
        compareBy<VideoCueRecord> { it.startMs }
            .thenBy { it.endMs }
            .thenBy { it.trackId }
            .thenBy { it.cueId },
    )

internal data class SubtitleEditorSnapshot(val cues: List<VideoCueRecord>, val selectedId: String?)

internal data class SubtitleEditorDraft(
    val cues: List<VideoCueRecord>,
    val selectedId: String?,
    val undo: List<SubtitleEditorSnapshot> = emptyList(),
) {
    val selected get() = cues.firstOrNull { it.cueId == selectedId }

    fun select(id: String?) = copy(selectedId = id?.takeIf { candidate -> cues.any { it.cueId == candidate } })

    fun addCue(cue: VideoCueRecord): SubtitleEditorDraft =
        change(cues + cue, cue.cueId)

    fun editSelected(source: String, translation: String, startMs: Long, endMs: Long): SubtitleEditorDraft {
        val item = selected ?: return this
        return change(cues.map {
            if (it.cueId == item.cueId) it.copy(
                source = source,
                translation = translation,
                startMs = startMs,
                endMs = endMs,
                manuallyEdited = true,
                deleted = false,
            ) else it
        }, item.cueId)
    }

    fun deleteSelected(): SubtitleEditorDraft {
        val item = selected ?: return this
        return change(cues.map { if (it.cueId == item.cueId) it.copy(deleted = true, manuallyEdited = true) else it }, item.cueId)
    }

    fun restoreSelected(): SubtitleEditorDraft {
        val item = selected ?: return this
        return change(cues.map { if (it.cueId == item.cueId) it.copy(deleted = false, manuallyEdited = true) else it }, item.cueId)
    }

    fun clearTranslation(): SubtitleEditorDraft {
        val item = selected ?: return this
        return change(cues.map { if (it.cueId == item.cueId) it.copy(translation = "", manuallyEdited = true) else it }, item.cueId)
    }

    fun undoChange(): SubtitleEditorDraft {
        val before = undo.lastOrNull() ?: return this
        val selected = before.selectedId?.takeIf { id -> before.cues.any { it.cueId == id } }
        return copy(cues = before.cues, selectedId = selected, undo = undo.dropLast(1))
    }

    private fun change(next: List<VideoCueRecord>, selection: String?): SubtitleEditorDraft {
        val sorted = sortCues(next)
        if (sorted == cues) return this
        return copy(
            cues = sorted,
            selectedId = selection?.takeIf { id -> sorted.any { it.cueId == id } },
            undo = (undo + SubtitleEditorSnapshot(cues, selectedId)).takeLast(50),
        )
    }
}

/** Revision-checked cue persistence shared with the standalone compatibility editor. */
internal suspend fun persistCues(
    container: AppContainer,
    dao: VideoResultDao,
    project: VideoProject,
    draft: SubtitleEditorDraft,
    baseline: List<VideoCueRecord>,
): List<VideoCueRecord> = withContext(Dispatchers.IO) {
    require(draft.cues.size <= 20_000) { "字幕数量已达上限" }
    draft.cues.forEach { cue ->
        require(cue.startMs >= 0 && cue.endMs > cue.startMs && cue.endMs <= project.durationMs) {
            "字幕时间必须满足 0 ≤ 起点 < 终点 ≤ 视频总时长"
        }
        require(cue.source.length <= 4 * 1024 * 1024 && cue.translation.length <= 4 * 1024 * 1024) {
            "字幕文本过长"
        }
        require(!Regex("\\n\\s*\\n").containsMatchIn(cue.source) && !Regex("\\n\\s*\\n").containsMatchIn(cue.translation)) {
            "字幕内不能包含空白行（会破坏 SRT 分段）"
        }
    }
    val expected = baseline.associateBy { it.cueId }
    val changed = draft.cues.filter { current -> expected[current.cueId] != current }
    if (changed.isNotEmpty()) {
        container.videoTasks.withEditBarrier(project.id) {
            container.videoResults.withTransaction {
                for (current in changed) {
                    val previous = expected[current.cueId]
                    dao.saveCue(current, previous?.revision)
                }
            }
        }
    }
    dao.cues(project.id)
}
