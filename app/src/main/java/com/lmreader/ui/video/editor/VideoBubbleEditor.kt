package com.lmreader.ui.video.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.room.withTransaction
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.lmreader.core.database.media.VideoFrameIndex
import com.lmreader.core.database.media.VideoRegionInterval
import com.lmreader.core.database.media.VideoResultDao
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.PageTranslatedRegion
import com.lmreader.core.model.PixelRect
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.BubbleOverlay
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.reader.TapAwareSubsamplingImageView
import com.lmreader.ui.reader.translation.BubbleEditGesture
import com.lmreader.ui.reader.translation.PageBubbleDraft
import com.lmreader.ui.reader.translation.PageTranslationRevisionConflict
import com.lmreader.ui.reader.translation.ReaderOverlayGeometry
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.video.DecodedVideoFrame
import com.lmreader.ui.video.VideoFrameDecoder
import com.lmreader.ui.video.VideoProject
import com.lmreader.ui.video.VideoTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Frame-bound bubble editor. Decoding is limited to the original video frame selected
 * by the caller and the player stays paused; this editor never invokes OCR/translation.
 */
@Composable
fun VideoBubbleEditor(
    container: AppContainer,
    project: VideoProject,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(container, project.id) { container.videoResults.results() }
    val initialPositionMs = remember(project.id, project.sha256) { positionMs }
    var loaded by remember { mutableStateOf<LoadedFrame?>(null) }
    var draft by remember { mutableStateOf<VideoBubbleDraft?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveFailure by remember { mutableStateOf<BubbleEditorFailure?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var playheadMs by remember {
        mutableStateOf(initialPositionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0)))
    }

    LaunchedEffect(project.id, project.sha256, initialPositionMs) {
        loadError = null
        loaded = null
        draft = null
        try {
            val value = loadFrame(container, project, dao, context, initialPositionMs)
            loaded = value
            draft = value.newDraft()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "无法读取视频画面"
        }
    }
    DisposableEffect(loaded) {
        val frame = loaded
        onDispose { frame?.close() }
    }

    fun requestExit() {
        if (saving) return
        if (draft?.dirty == true) showExit = true else onDismiss()
    }

    fun persist(exitAfter: Boolean) {
        val current = draft ?: return
        val frame = loaded ?: return
        if (saving) return
        saving = true
        saveError = null
        saveFailure = null
        scope.launch {
            try {
                val result = saveDraft(container, project, dao, current, frame)
                val selectedId = current.selectedId
                draft = VideoBubbleDraft(
                    saved = result.saved,
                    page = PageBubbleDraft(result.saved).select(selectedId),
                    times = result.times,
                    baselineTimes = result.times,
                    intervals = result.intervals,
                )
                if (exitAfter) onDismiss()            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError = when (error) {
                    is VideoEditorPartialSaveException -> error.message
                    is PageTranslationRevisionConflict -> error.message ?: "画面成果已更新，请重新载入"
                    is BubbleIntervalConflict -> error.message
                    is IOException -> "无法写入本地存储，草稿已保留；请检查可用空间后重试"
                    else -> error.message ?: "保存失败，草稿已保留"
                }
                saveFailure = when (error) {
                    is PageTranslationRevisionConflict, is BubbleIntervalConflict -> BubbleEditorFailure.CHANGED
                    is VideoEditorPartialSaveException -> BubbleEditorFailure.OTHER
                    is IOException -> BubbleEditorFailure.STORAGE
                    else -> BubbleEditorFailure.OTHER
                }
            } finally {
                saving = false
            }
        }
    }

    Dialog(onDismissRequest = { requestExit() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            var overlay by remember { mutableStateOf<BubbleOverlay?>(null) }
            val value = draft
            val frame = loaded
            if (value == null || frame == null) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loadError == null) CircularProgressIndicator()
                    Text(loadError ?: "正在读取当前画面…")
                    if (loadError != null) TextButton(onClick = onDismiss) { Text("关闭") }
                }
            } else {
                LaunchedEffect(value.regions, value.gestureBefore) {
                    if (value.gestureBefore != null) return@LaunchedEffect
                    val built = withContext(Dispatchers.Default) {
                        runCatching {
                            val source = BubbleMaskRenderer().prepareSource(frame.analysis, value.regions.map { it.region })
                            source.layout(value.regions, value.saved.renderSettings, hideEmpty = true, includeEmptyForEditing = true)
                        }.getOrNull()
                    }
                    if (built != null) overlay = built
                }
                Column(Modifier.fillMaxSize()) {
                    BubbleEditorToolbar(
                        state = BubbleEditorState(
                            editing = true,
                            saving = saving,
                            hasDraft = true,
                            dirty = value.dirty,
                            regionCount = value.regions.size,
                            hasSelection = value.selected != null,
                            canUndo = value.undo.isNotEmpty(),
                            selectedSource = value.selected?.region?.sourceText.orEmpty(),
                            selectedText = value.selected?.translatedText.orEmpty(),
                            canAdd = value.regions.size < 1000,
                            failure = saveFailure,
                            failureText = saveError,
                            showFailure = !showExit,
                        ),
                        onText = { draft = draft?.editText(it) },
                        onDelete = { draft = draft?.deleteSelected() },
                        onSave = { persist(false) },
                        onUndo = { draft = draft?.undoChange() },
                        onExit = { requestExit() },
                        onClearFailure = {
                            saveError = null
                            saveFailure = null
                        },
                        onAdd = { draft = draft?.addBubble(playheadMs, project.durationMs) },
                        onIncreaseFont = { draft = draft?.scaleFont(5) },
                        onDecreaseFont = { draft = draft?.scaleFont(-5) },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        maxWidth = 1000.dp,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                        VideoBubbleCanvas(
                            frame = frame,
                            draft = value,
                            overlay = overlay,
                            onSelected = { draft = draft?.select(it) },
                            onGesture = { gesture -> draft = draft?.apply(gesture) },
                        )
                    }
                    VideoBubbleTimingPanel(
                        draft = value,
                        durationMs = project.durationMs,
                        playheadMs = playheadMs,
                        enabled = !saving,
                        onPlayhead = { playheadMs = it },
                        onSeek = onSeek,
                        onSetTime = { start, end -> draft = draft?.setTime(value.selectedId, start, end, project.durationMs) },
                    )
                }
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { if (!saving) showExit = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.lmreader.R.string.reader_bubble_leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(androidx.compose.ui.res.stringResource(com.lmreader.R.string.reader_bubble_leave_message))
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
private fun VideoBubbleCanvas(
    frame: LoadedFrame,
    draft: VideoBubbleDraft,
    overlay: BubbleOverlay?,
    onSelected: (String?) -> Unit,
    onGesture: (BubbleEditGesture) -> Unit,
) {
    AndroidView(
        factory = { context ->
            TapAwareSubsamplingImageView(context, onSingleTap = { _, _ -> }).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                setImage(ImageSource.bitmap(frame.bitmap))
                showTranslation = true
                editing = true
            }
        },
        update = { view ->
            view.overlayGeometry = ReaderOverlayGeometry(
                frame.saved.width,
                frame.saved.height,
                PixelRect(0f, 0f, frame.saved.width.toFloat(), frame.saved.height.toFloat()),
            )
            view.overlay = overlay
            view.editableRegions = draft.regions
            view.selectedBubble = draft.selectedId
            view.onBubbleSelected = onSelected
            view.onBubbleGesture = onGesture
            view.invalidate()
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { frame.close() },
    )
}

@Composable
private fun VideoBubbleTimingPanel(
    draft: VideoBubbleDraft,
    durationMs: Long,
    playheadMs: Long,
    enabled: Boolean,
    onPlayhead: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSetTime: (Long, Long) -> Unit,
) {
    val selected = draft.selected
    val selectedTime = selected?.let { draft.times[it.region.id] }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val current = (dragMs ?: playheadMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    var startText by remember(selected?.region?.id, selectedTime?.startMs) {
        mutableStateOf(selectedTime?.let { formatBubbleTime(it.startMs) }.orEmpty())
    }
    var endText by remember(selected?.region?.id, selectedTime?.endMs) {
        mutableStateOf(selectedTime?.let { formatBubbleTime(it.endMs) }.orEmpty())
    }
    var rangeError by remember(selected?.region?.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            .background(Color(0xFF151515)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("当前位置 ${formatBubbleTime(current)}", style = MaterialTheme.typography.labelMedium)
            Text("总时长 ${formatBubbleTime(durationMs)}", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = (dragMs ?: playheadMs).toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { dragMs = it.roundToLong() },
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
            ranges = listOfNotNull(selectedTime?.let { EditorTimeBandRange(it.startMs, it.endMs) }),
            selected = selectedTime?.let { EditorTimeBandRange(it.startMs, it.endMs) },
        )
        if (selected == null || selectedTime == null) {
            Text("点选画面中的气泡以校对它的出现时间。", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("起点 ${formatBubbleTime(selectedTime.startMs)}", style = MaterialTheme.typography.labelSmall)
                Text("终点 ${formatBubbleTime(selectedTime.endMs)}", style = MaterialTheme.typography.labelSmall)
                Text("时长 ${formatBubbleTime(selectedTime.endMs - selectedTime.startMs)}", style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.take(16) },
                    label = { Text("起点") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it.take(16) },
                    label = { Text("终点") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val start = parseBubbleTime(startText)
                        val end = parseBubbleTime(endText)
                        rangeError = when {
                            start == null || end == null -> "时间格式必须是 HH:MM:SS.mmm"
                            !validBubbleTimeRange(start, end, durationMs) -> "必须满足 0 ≤ 起点 < 终点 ≤ 视频总时长"
                            else -> null
                        }
                        if (rangeError == null && start != null && end != null) {
                            onSetTime(start, end)
                            startText = formatBubbleTime(start)
                            endText = formatBubbleTime(end)
                        }
                    },
                    enabled = enabled,
                ) { Text("应用时间") }
                TextButton(
                    onClick = {
                        if (current < selectedTime.endMs) {
                            onSetTime(current, selectedTime.endMs)
                            rangeError = null
                        } else {
                            rangeError = "起点必须早于终点"
                        }
                    },
                    enabled = enabled,
                ) { Text("起点=当前") }
                TextButton(
                    onClick = {
                        if (current > selectedTime.startMs && current <= durationMs) {
                            onSetTime(selectedTime.startMs, current)
                            rangeError = null
                        } else {
                            rangeError = "终点必须晚于起点且不超过总时长"
                        }
                    },
                    enabled = enabled,
                ) { Text("终点=当前") }
            }
        }
        rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

private data class BubbleTime(val startMs: Long, val endMs: Long)

private data class VideoBubbleSnapshot(
    val regions: List<PageTranslatedRegion>,
    val times: Map<String, BubbleTime>,
    val selectedId: String?,
)

private data class VideoBubbleDraft(
    val saved: ReaderPageTranslation,
    val page: PageBubbleDraft,
    val times: Map<String, BubbleTime>,
    val baselineTimes: Map<String, BubbleTime>,
    val intervals: Map<String, VideoRegionInterval>,
    val undo: List<VideoBubbleSnapshot> = emptyList(),
    val gestureBefore: VideoBubbleSnapshot? = null,
) {
    val regions get() = page.regions
    val selectedId get() = page.selectedId
    val selected get() = page.selected
    val dirty get() = regions != saved.regions || times != baselineTimes

    fun select(id: String?) = copy(page = page.select(id))

    fun editText(text: String): VideoBubbleDraft {
        val before = snapshot()
        val next = page.editText(text)
        return if (next.regions == page.regions) this else changed(next, times, before)
    }

    fun scaleFont(delta: Int): VideoBubbleDraft {
        val before = snapshot()
        val next = page.scaleFont(delta)
        return if (next.regions == page.regions) this else changed(next, times, before)
    }

    fun addBubble(playheadMs: Long, durationMs: Long): VideoBubbleDraft {
        val before = snapshot()
        val next = page.addBubble()
        val id = next.selectedId ?: return this
        if (next.regions == page.regions) return this
        return changed(next, times + (id to defaultBubbleTime(playheadMs, durationMs)), before)
    }

    fun deleteSelected(): VideoBubbleDraft {
        val before = snapshot()
        val next = page.deleteSelected()
        return if (next.regions == page.regions) this else changed(next, times, before)
    }

    fun setTime(id: String?, startMs: Long, endMs: Long, durationMs: Long): VideoBubbleDraft {
        if (id == null || !validBubbleTimeRange(startMs, endMs, durationMs)) return this
        val previous = times[id] ?: return this
        if (previous.startMs == startMs && previous.endMs == endMs) return this
        val before = snapshot()
        return copy(
            times = times + (id to BubbleTime(startMs, endMs)),
            undo = (undo + before).takeLast(30),
            gestureBefore = null,
        )
    }

    fun beginTransform(id: String): VideoBubbleDraft {
        if (gestureBefore != null) return this
        val before = snapshot()
        return copy(page = page.beginTransform(id), gestureBefore = before)
    }

    fun transform(id: String, bounds: PixelRect, rotation: Float): VideoBubbleDraft =
        if (gestureBefore == null) this else copy(page = page.transform(id, bounds, rotation))

    fun finishTransform(): VideoBubbleDraft {
        val before = gestureBefore ?: return this
        val next = page.finishTransform()
        return if (next.regions == before.regions) copy(page = next, gestureBefore = null)
        else copy(page = next, undo = (undo + before).takeLast(30), gestureBefore = null)
    }

    fun apply(gesture: BubbleEditGesture): VideoBubbleDraft = when (gesture) {
        is BubbleEditGesture.Begin -> beginTransform(gesture.id)
        is BubbleEditGesture.Transform -> transform(gesture.id, gesture.bounds, gesture.rotation)
        BubbleEditGesture.End -> finishTransform()
    }

    fun undoChange(): VideoBubbleDraft {
        val before = undo.lastOrNull() ?: return this
        val selected = before.selectedId?.takeIf { id -> before.regions.any { it.region.id == id } }
        return copy(
            page = page.copy(regions = before.regions, selectedId = selected, undo = emptyList(), gestureStart = null),
            times = before.times,
            undo = undo.dropLast(1),
            gestureBefore = null,
        )
    }

    private fun snapshot() = VideoBubbleSnapshot(regions, times, selectedId)

    private fun changed(next: PageBubbleDraft, nextTimes: Map<String, BubbleTime>, before: VideoBubbleSnapshot) =
        copy(page = next, times = nextTimes, undo = (undo + before).takeLast(30), gestureBefore = null)
}

private class LoadedFrame(
    val decoded: DecodedVideoFrame,
    val bitmap: Bitmap,
    val saved: ReaderPageTranslation,
    val sourceHash: String,
    val analysis: Bitmap,
    val ownedAnalysis: Bitmap?,
    val ownedBitmapCopy: Bitmap?,
    val targetUs: Long,
    val durationMs: Long,
    val draftTimes: Map<String, BubbleTime>,
    val intervals: Map<String, VideoRegionInterval>,
    val frameIndex: VideoFrameIndex?,
) {
    private var closed = false

    fun newDraft(): VideoBubbleDraft {
        val playhead = targetUs / 1_000L
        val times = LinkedHashMap<String, BubbleTime>()
        for (item in saved.regions) {
            times[item.region.id] = draftTimes[item.region.id]
                ?: defaultBubbleTime(playhead, durationMs)
        }
        return VideoBubbleDraft(
            saved = saved,
            page = PageBubbleDraft(saved),
            times = times,
            baselineTimes = times.toMap(),
            intervals = intervals,
        )
    }

    fun close() {
        if (closed) return
        closed = true
        ownedAnalysis?.recycle()
        ownedBitmapCopy?.recycle()
        decoded.close()
    }
}

private suspend fun loadFrame(
    container: AppContainer,
    project: VideoProject,
    dao: VideoResultDao,
    context: android.content.Context,
    positionMs: Long,
): LoadedFrame = withContext(Dispatchers.IO) {
    val targetMs = positionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
    val targetUs = targetMs * 1_000L
    val decoder = VideoFrameDecoder(context, project.uri)
    var decoded: DecodedVideoFrame? = null
    try {
        val frame = decoder.frameAt(targetUs) ?: error("该时间点没有可读取的视频帧")
        decoded = frame
        var ownedBitmapCopy: Bitmap? = null
        val image = if (frame.bitmap.config == Bitmap.Config.HARDWARE) {
            frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { ownedBitmapCopy = it }
                ?: error("无法创建可编辑的视频帧")
        } else frame.bitmap
        val sourceHash = VideoTranslationEngine.bitmapHash(image)
        val index = dao.at(project.id, project.sha256, targetUs)
        val saved = if (index != null) {
            container.videoLibrary.artifacts(project.id).current(index.pageId)
                ?: error("视频画面成果缺失，请重新生成该画面")
        } else {
            ReaderPageTranslation(
                pageId = "video:${project.id}:manual:${UUID.randomUUID()}",
                revision = "",
                dataFile = File(""),
                source = LocalTranslationLanguage.fromTag(project.source),
                target = LocalTranslationLanguage.fromTag(project.target),
                sourceSha256 = sourceHash,
                width = image.width,
                height = image.height,
                regions = emptyList(),
                modelPacks = emptyList(),
                elapsedMillis = 0,
                renderSettings = BubbleRenderSettings(),
            )
        }
        val analysis = if (image.width == saved.width && image.height == saved.height) image
        else Bitmap.createScaledBitmap(image, saved.width, saved.height, true)
        val ownedAnalysis = analysis.takeIf { it !== image }
        val prepared = prepareIntervals(project, dao, saved, targetMs)
        LoadedFrame(
            decoded = frame,
            bitmap = image,
            saved = prepared.saved,
            sourceHash = sourceHash,
            analysis = analysis,
            ownedAnalysis = ownedAnalysis,
            ownedBitmapCopy = ownedBitmapCopy,
            targetUs = targetUs,
            durationMs = project.durationMs,
            draftTimes = prepared.times,
            intervals = prepared.intervals,
            frameIndex = index,
        )
    } catch (failure: Throwable) {
        decoded?.close()
        throw failure
    } finally {
        decoder.close()
    }
}

private data class PreparedIntervals(
    val saved: ReaderPageTranslation,
    val times: Map<String, BubbleTime>,
    val intervals: Map<String, VideoRegionInterval>,
)

private val INTERVAL_ORDER = compareBy<VideoRegionInterval>({ it.startMs }, { it.endMs }, { it.intervalId })

/**
 * A page can now hold several interval rows for one region (partial-clear splits). Choose
 * exactly one row per region deterministically: covering/nearest live row, then tombstone.
 * Regions that only have tombstones stay deleted and are removed from the editable artifact.
 */
private suspend fun prepareIntervals(
    project: VideoProject,
    dao: VideoResultDao,
    saved: ReaderPageTranslation,
    selectedMs: Long,
    pageRows: List<VideoRegionInterval>? = null,
): PreparedIntervals {
    val grouped = LinkedHashMap<String, MutableList<VideoRegionInterval>>()
    for (row in pageRows ?: dao.pageRegions(saved.pageId)) {
        if (row.videoId != project.id || row.sourceHash != project.sha256 ||
            row.pageId != saved.pageId) continue
        grouped.getOrPut(row.regionId) { mutableListOf() }.add(row)
    }
    val kept = ArrayList<PageTranslatedRegion>(saved.regions.size)
    val times = LinkedHashMap<String, BubbleTime>()
    val choices = LinkedHashMap<String, VideoRegionInterval>()
    for (item in saved.regions) {
        val regionId = item.region.id
        val group = grouped[regionId].orEmpty().sortedWith(INTERVAL_ORDER)
        val chosen = chooseRegionInterval(group, selectedMs)
        if (group.isNotEmpty() && (chosen == null || chosen.deleted)) continue
        kept += item
        if (chosen == null) {
            times[regionId] = defaultBubbleTime(selectedMs, project.durationMs)
            continue
        }
        choices[regionId] = chosen
        val time = if (chosen.manualTiming || chosen.deleted) {
            normalizeBubbleTime(chosen.startMs, chosen.endMs, selectedMs, project.durationMs)
        } else {
            val union = dao.trackRegions(project.id, chosen.trackId)
                .filter { it.sourceHash == project.sha256 && !it.deleted }
            if (union.isEmpty()) {
                normalizeBubbleTime(chosen.startMs, chosen.endMs, selectedMs, project.durationMs)
            } else {
                normalizeBubbleTime(
                    union.minOf { it.startMs },
                    union.maxOf { it.endMs },
                    selectedMs,
                    project.durationMs,
                )
            }
        }
        times[regionId] = time
    }
    return PreparedIntervals(saved.copy(regions = kept), times, choices)
}

private fun chooseRegionInterval(
    rows: List<VideoRegionInterval>,
    selectedMs: Long,
): VideoRegionInterval? {
    if (rows.isEmpty()) return null
    val ordered = rows.sortedWith(INTERVAL_ORDER)
    val covering = ordered.firstOrNull { !it.deleted && it.startMs <= selectedMs && it.endMs > selectedMs }
    if (covering != null) return covering
    val live = ordered.filter { !it.deleted }
    val pool = live.ifEmpty { ordered }
    return pool.minWithOrNull(
        compareBy<VideoRegionInterval>(
            { intervalDistance(it, selectedMs) },
            { it.startMs },
            { it.endMs },
            { it.intervalId },
        ),
    )
}

private fun intervalDistance(row: VideoRegionInterval, selectedMs: Long): Long = when {
    selectedMs < row.startMs -> row.startMs - selectedMs
    selectedMs >= row.endMs -> selectedMs - row.endMs + 1L
    else -> 0L
}

private class BubbleIntervalConflict(message: String) : IllegalStateException(message)

private class VideoEditorPartialSaveException(message: String) : IllegalStateException(message)

private data class VideoEditorSaveResult(
    val saved: ReaderPageTranslation,
    val times: Map<String, BubbleTime>,
    val intervals: Map<String, VideoRegionInterval>,
)

private suspend fun saveDraft(
    container: AppContainer,
    project: VideoProject,
    dao: VideoResultDao,
    draft: VideoBubbleDraft,
    frame: LoadedFrame,
): VideoEditorSaveResult = withContext(Dispatchers.IO) {
    val regions = draft.regions
    require(regions.size <= 1000) { "气泡数量过多" }
    val desired = LinkedHashMap<String, BubbleTime>()
    for (item in regions) {
        val time = draft.times[item.region.id] ?: defaultBubbleTime(frame.targetUs / 1_000L, project.durationMs)
        require(validBubbleTimeRange(time.startMs, time.endMs, project.durationMs)) {
            "必须满足 0 ≤ 起点 < 终点 ≤ 视频总时长"
        }
        desired[item.region.id] = time
    }
    var result: VideoEditorSaveResult? = null
    var journal: VideoEditJournal? = null
    var artifactWrittenByUs = false
    var journalSettled = false
    try {
        container.videoTasks.withEditBarrier(project.id) {
            try {
            container.videoResults.withTransaction {
                val pageRows = dao.pageRegions(draft.saved.pageId)
                val freshById = LinkedHashMap<String, VideoRegionInterval>()
                for (row in pageRows) freshById[row.intervalId] = row
                val regionIdsWithRows = pageRows
                    .filter { it.videoId == project.id && it.sourceHash == project.sha256 && it.pageId == draft.saved.pageId }
                    .map { it.regionId }
                    .toHashSet()
                for ((regionId, expected) in draft.intervals) {
                    val actual = freshById[expected.intervalId] ?: intervalConflict()
                    if (actual.regionId != regionId || actual.pageId != draft.saved.pageId ||
                        actual.videoId != project.id || actual.sourceHash != project.sha256 ||
                        actual.revision != expected.revision || actual.deleted) {
                        intervalConflict()
                    }
                }
                for (item in regions) {
                    if (draft.intervals[item.region.id] == null && item.region.id in regionIdsWithRows) {
                        intervalConflict()
                    }
                }

                val artifacts = container.videoLibrary.artifacts(project.id)
                val existingArtifact = artifacts.current(draft.saved.pageId)
                if (draft.saved.revision.isEmpty()) {
                    if (existingArtifact != null) throw PageTranslationRevisionConflict()
                } else if (existingArtifact?.revision != draft.saved.revision) {
                    throw PageTranslationRevisionConflict()
                }

                // The exact preimage must be durable before saveEdits can replace the artifact.
                val preparedJournal = VideoEditJournal.prepare(
                    container,
                    project.id,
                    project.sha256,
                    draft.saved.pageId,
                    draft.saved.revision,
                )
                journal = preparedJournal

                val chosenIds = LinkedHashMap<String, String>()
                for (item in regions) {
                    val regionId = item.region.id
                    val time = desired.getValue(regionId)
                    val expected = draft.intervals[regionId]
                    if (expected == null) {
                        val row = VideoRegionInterval(
                            intervalId = UUID.randomUUID().toString(),
                            videoId = project.id,
                            sourceHash = project.sha256,
                            pageId = draft.saved.pageId,
                            regionId = regionId,
                            startMs = time.startMs,
                            endMs = time.endMs,
                            observedStartMs = time.startMs,
                            observedEndMs = time.endMs,
                            manualTiming = true,
                            deleted = false,
                            revision = UUID.randomUUID().toString(),
                        )
                        check(dao.addRegion(row) != -1L) { "无法建立气泡时间区间" }
                        chosenIds[regionId] = row.intervalId
                    } else {
                        val actual = freshById[expected.intervalId] ?: intervalConflict()
                        chosenIds[regionId] = actual.intervalId
                        if (actual.manualTiming) {
                            val changed = actual.copy(
                                startMs = time.startMs,
                                endMs = time.endMs,
                                manualTiming = true,
                                deleted = false,
                            )
                            if (changed != actual) dao.saveRegion(changed, actual.revision)
                        } else {
                            collapseAutomaticTrack(dao, project, actual, time)
                        }
                    }
                }
                for ((regionId, expected) in draft.intervals) {
                    if (desired.containsKey(regionId)) continue
                    val actual = freshById[expected.intervalId] ?: intervalConflict()
                    if (actual.manualTiming) {
                        if (!actual.deleted) dao.saveRegion(actual.copy(deleted = true), actual.revision)
                    } else {
                        tombstoneAutomaticTrack(dao, project, actual.trackId)
                    }
                }

                val saved = artifacts.saveEdits(draft.saved, regions)
                artifactWrittenByUs = true
                preparedJournal.recordWrittenRevision(saved.revision)
                dao.updateFrameRevision(
                    saved.pageId,
                    saved.revision,
                    saved.regions.none { it.translatedText.isNotBlank() },
                )
                if (frame.frameIndex == null) {
                    dao.putFrame(manualFrameRow(project, frame, saved))
                }
                val finalRows = dao.pageRegions(saved.pageId).associateBy { it.intervalId }
                val finalTimes = LinkedHashMap<String, BubbleTime>()
                val finalIntervals = LinkedHashMap<String, VideoRegionInterval>()
                for (item in saved.regions) {
                    val regionId = item.region.id
                    val intervalId = chosenIds[regionId] ?: intervalConflict()
                    val row = finalRows[intervalId] ?: intervalConflict()
                    finalTimes[regionId] = BubbleTime(row.startMs, row.endMs)
                    finalIntervals[regionId] = row
                }
                result = VideoEditorSaveResult(saved, finalTimes, finalIntervals)
            }
            } catch (failure: Throwable) {
                val cleanupFailure = settleJournal(journal, artifactWrittenByUs)
                journalSettled = true
                if (cleanupFailure != null) {
                    throw VideoEditorPartialSaveException(partialSaveMessage(artifactWrittenByUs, cleanupFailure))
                }
                throw failure
            }
            val committedJournal = journal ?: error("视频编辑恢复日志缺失")
            try {
                committedJournal.commitAfterRoom()
            } catch (_: Throwable) {
                // The Room commit succeeded. Keep the journal for startup recovery instead
                // of reporting a false save failure and forcing a stale-draft retry.
            }
        }
        result ?: error("画面成果未保存")
    } catch (cancelled: CancellationException) {
        if (journalSettled) throw cancelled
        val cleanupFailure = settleJournal(journal, artifactWrittenByUs)
        if (cleanupFailure != null) {
            throw VideoEditorPartialSaveException(partialSaveMessage(artifactWrittenByUs, cleanupFailure))
        }
        throw cancelled
    } catch (failure: Throwable) {
        if (failure is VideoEditorPartialSaveException || journalSettled) throw failure
        val cleanupFailure = settleJournal(journal, artifactWrittenByUs)
        if (cleanupFailure != null) {
            throw VideoEditorPartialSaveException(partialSaveMessage(artifactWrittenByUs, cleanupFailure))
        }
        throw failure
    }
}

private fun intervalConflict(): Nothing =
    throw BubbleIntervalConflict("气泡时间已被其他任务更新，请退出后重新打开本画面")

private suspend fun settleJournal(journal: VideoEditJournal?, artifactWritten: Boolean): Throwable? =
    runCatching {
        if (artifactWritten) journal?.resolveAfterFailure() else journal?.abandonAfterAbortedWrite()
    }.exceptionOrNull()

private fun partialSaveMessage(artifactWritten: Boolean, failure: Throwable): String =
    if (artifactWritten) {
        "画面文件可能已部分写入且自动恢复失败，恢复日志已保留：${failure.message ?: "未知错误"}。请退出后重新打开本画面核对。"
    } else {
        "画面成果尚未写入，但恢复日志清理失败，恢复日志已保留：${failure.message ?: "未知错误"}。请退出后重新打开本画面核对。"
    }

private suspend fun collapseAutomaticTrack(
    dao: VideoResultDao,
    project: VideoProject,
    chosen: VideoRegionInterval,
    time: BubbleTime,
) {
    for (row in dao.trackRegions(project.id, chosen.trackId)) {
        if (row.sourceHash != project.sha256 || row.intervalId == chosen.intervalId ||
            row.deleted || row.manualTiming) continue
        dao.saveRegion(row.copy(deleted = true), row.revision)
    }
    val changed = chosen.copy(
        startMs = time.startMs,
        endMs = time.endMs,
        manualTiming = true,
        deleted = false,
    )
    if (changed != chosen) dao.saveRegion(changed, chosen.revision)
}

private suspend fun tombstoneAutomaticTrack(
    dao: VideoResultDao,
    project: VideoProject,
    trackId: String,
) {
    for (row in dao.trackRegions(project.id, trackId)) {
        if (row.sourceHash != project.sha256 || row.deleted || row.manualTiming) continue
        dao.saveRegion(row.copy(deleted = true), row.revision)
    }
}

private fun manualFrameRow(
    project: VideoProject,
    frame: LoadedFrame,
    saved: ReaderPageTranslation,
): VideoFrameIndex {
    val durationUs = project.durationMs * 1_000L
    val ptsUs = frame.decoded.ptsUs
    require(ptsUs >= 0L && ptsUs < durationUs) { "视频帧时间超出视频时长" }
    val untilUs = (frame.targetUs + 2_000_000L)
        .coerceAtMost(durationUs)
        .coerceAtLeast(ptsUs + 1L)
    require(untilUs > ptsUs && untilUs <= durationUs) { "视频帧时间超出视频时长" }
    return VideoFrameIndex(
        videoId = project.id,
        sourceHash = project.sha256,
        identity = frame.sourceHash,
        sampleIndex = 0L,
        targetUs = frame.targetUs,
        ptsUs = ptsUs,
        untilUs = untilUs,
        pageId = saved.pageId,
        resultRevision = saved.revision,
        noText = saved.regions.none { it.translatedText.isNotBlank() },
        producerTaskId = "",
    )
}

private fun normalizeBubbleTime(startMs: Long, endMs: Long, playheadMs: Long, durationMs: Long): BubbleTime =
    if (validBubbleTimeRange(startMs, endMs, durationMs)) BubbleTime(startMs, endMs)
    else defaultBubbleTime(playheadMs, durationMs)

private fun defaultBubbleTime(playheadMs: Long, durationMs: Long): BubbleTime {
    val start = playheadMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    val end = (start + 2_000L).coerceAtMost(durationMs).coerceAtLeast(start + 1L)
    return BubbleTime(start, end)
}
