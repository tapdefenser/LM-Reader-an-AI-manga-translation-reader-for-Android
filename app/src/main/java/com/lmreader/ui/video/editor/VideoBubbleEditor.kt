package com.lmreader.ui.video.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import com.lmreader.ui.video.VideoTasks
import com.lmreader.ui.video.VideoFrameProof
import com.lmreader.ui.video.VideoSavedOverlays
import com.lmreader.ui.video.moveVideoRegion
import com.lmreader.core.model.ScreenTransform
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import com.lmreader.ui.video.formatVideoTimecode
import com.lmreader.ui.video.parseVideoTimecode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Frame-bound bubble editor. The original frame is decoded at the current playhead; when
 * a real player is supplied it is re-used (never replaced or re-created) for a TextureView
 * preview. Playback is paused before editing. This editor never invokes OCR/translation.
 */
@Composable
fun VideoBubbleEditor(
    container: AppContainer,
    project: VideoProject,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    player: Player? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dao = remember(container, project.id) { container.videoResults.results() }
    val initialPositionMs = remember(project.id, project.sha256) {
        positionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
    }
    var loaded by remember(project.id, project.sha256) { mutableStateOf<LoadedFrame?>(null) }
    var draft by remember(project.id, project.sha256) { mutableStateOf<VideoBubbleDraft?>(null) }
    var loadError by remember(project.id, project.sha256) { mutableStateOf<String?>(null) }
    var saveError by remember(project.id, project.sha256) { mutableStateOf<String?>(null) }
    var saveFailure by remember(project.id, project.sha256) { mutableStateOf<BubbleEditorFailure?>(null) }
    var saving by remember(project.id, project.sha256) { mutableStateOf(false) }
    var showExit by remember(project.id, project.sha256) { mutableStateOf(false) }
    var playing by remember(project.id, project.sha256) { mutableStateOf(false) }
    var frameLoading by remember(project.id, project.sha256) { mutableStateOf(true) }
    var playheadMs by remember(project.id, project.sha256) { mutableStateOf(initialPositionMs) }
    var requestedLoadMs by remember(project.id, project.sha256) { mutableStateOf(initialPositionMs) }
    var loadNonce by remember(project.id, project.sha256) { mutableStateOf(1) }
    var loadedAtMs by remember(project.id, project.sha256) { mutableStateOf<Long?>(null) }
    var explicitPlayerAction by remember(project.id, project.sha256) { mutableStateOf(false) }
    var observedPlayWhenReady by remember(project.id, project.sha256) { mutableStateOf(false) }
    var observedEnded by remember(project.id, project.sha256) { mutableStateOf(false) }
    var cueDirty by remember(project.id, project.sha256) { mutableStateOf(false) }
    var toolbarOffset by remember { mutableStateOf(IntOffset.Zero) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val cueHandle = remember(project.id, project.sha256) { VideoCurrentCueEditorHandle() }

    fun requestFrameLoad(targetMs: Long) {
        val target = targetMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        if (loadedAtMs == target && loaded != null && loadError == null) return
        requestedLoadMs = target
        loadNonce += 1
    }

    suspend fun persistDraft(): Boolean {
        val current = draft ?: return true
        val frame = loaded ?: return false
        if (saving) return false
        saving = true
        saveError = null
        saveFailure = null
        return try {
            val result = saveDraft(container, project, dao, current, frame)
            val selectedId = current.selectedId
            draft = VideoBubbleDraft(
                saved = result.saved,
                page = PageBubbleDraft(result.saved).select(selectedId),
                times = result.times,
                baselineTimes = result.times,
                intervals = result.intervals,
            )
            true
        } catch (cancelled: CancellationException) {
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
            false
        } finally {
            saving = false
        }
    }

    suspend fun persistDraftIfNeeded(): Boolean {
        val current = draft ?: return true
        if (!current.dirty) return true
        return persistDraft()
    }

    fun persist(exitAfter: Boolean) {
        if (saving) return
        scope.launch {
            if (!persistDraftIfNeeded()) return@launch
            if ((cueDirty || cueHandle.isDirty) && cueHandle.saveEdits?.invoke() == false) {
                saveError = "字幕修改未保存，请重试"
                if(exitAfter) showExit = true
                return@launch
            }
            if (exitAfter) {
                player?.pause()
                onDismiss()
            }
        }
    }

    fun requestExit() {
        if (saving) return
        player?.pause()
        playing = false
        observedPlayWhenReady = false
        if (draft?.dirty == true || cueDirty || cueHandle.isDirty) {
            showExit = true
        } else {
            onDismiss()
        }
    }

    fun pauseForEditing() {
        val target = player ?: return
        if (target.isPlaying || target.playWhenReady) {
            target.pause()
            playing = false
            observedPlayWhenReady = false
        }
    }

    fun seekTo(targetMs: Long) {
        if (saving || frameLoading) return
        val target = targetMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        scope.launch {
            explicitPlayerAction = true
            try {
                val targetPlayer = player
                if (targetPlayer != null && (targetPlayer.isPlaying || targetPlayer.playWhenReady)) {
                    targetPlayer.pause()
                }
                playing = false
                observedPlayWhenReady = false
                if (!persistDraftIfNeeded()) {
                    playheadMs = loadedAtMs ?: playheadMs
                    return@launch
                }
                targetPlayer?.seekTo(target)
                onSeek(target)
                playheadMs = target
                requestFrameLoad(target)
            } finally {
                explicitPlayerAction = false
            }
        }
    }

    fun startPlayback() {
        if (saving || frameLoading) return
        val targetPlayer = player ?: return
        scope.launch {
            explicitPlayerAction = true
            try {
                if (!persistDraftIfNeeded()) return@launch
                if((cueDirty || cueHandle.isDirty) && cueHandle.saveEdits?.invoke() == false) return@launch
                targetPlayer.seekTo(playheadMs)
                playing = true
                targetPlayer.play()
                observedPlayWhenReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                playing = false
                saveError = "播放失败：${error.message ?: "未知错误"}"
                saveFailure = BubbleEditorFailure.OTHER
            } finally {
                explicitPlayerAction = false
            }
        }
    }

    fun pausePlayback() {
        if (saving || frameLoading) return
        val targetPlayer = player ?: return
        scope.launch {
            explicitPlayerAction = true
            try {
                val target = targetPlayer.currentPosition.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
                if (targetPlayer.isPlaying || targetPlayer.playWhenReady) targetPlayer.pause()
                playing = false
                observedPlayWhenReady = false
                if (!persistDraftIfNeeded()) {
                    playheadMs = loadedAtMs ?: playheadMs
                    return@launch
                }
                playheadMs = target
                requestFrameLoad(target)
            } finally {
                explicitPlayerAction = false
            }
        }
    }

    fun togglePlayback() {
        if (playing) pausePlayback() else startPlayback()
    }

    LaunchedEffect(project.id, project.sha256, loadNonce) {
        val token = loadNonce
        val targetMs = requestedLoadMs
        frameLoading = true
        loadError = null
        try {
            val value = loadFrame(container, project, dao, context, targetMs)
            if (token != loadNonce) {
                value.close()
                return@LaunchedEffect
            }
            loaded = value
            draft = value.newDraft()
            loadedAtMs = value.targetUs / 1_000L
            playheadMs = loadedAtMs ?: targetMs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (token == loadNonce) {
                loaded = null
                draft = null
                loadedAtMs = null
                loadError = error.message ?: "无法读取视频画面"
            }
        } finally {
            if (token == loadNonce) frameLoading = false
        }
    }
    DisposableEffect(loaded) {
        val frame = loaded
        onDispose { frame?.close() }
    }

    LaunchedEffect(player, project.id) {
        val targetPlayer = player
        if (targetPlayer == null) {
            playing = false
            observedPlayWhenReady = false
            return@LaunchedEffect
        }
        targetPlayer.pause()
        playing = false
        observedPlayWhenReady = false
        observedEnded = false
        while (true) {
            val nowPlaying = targetPlayer.isPlaying
            val nowPlayWhenReady = targetPlayer.playWhenReady
            val nowEnded = targetPlayer.playbackState == Player.STATE_ENDED
            if (nowPlaying && !explicitPlayerAction) {
                playheadMs = targetPlayer.currentPosition
                    .coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
            }
            val justPaused = observedPlayWhenReady && !nowPlayWhenReady
            val justEnded = !observedEnded && nowEnded
            if ((justPaused || justEnded) && !explicitPlayerAction) {
                observedPlayWhenReady = false
                val target = targetPlayer.currentPosition
                    .coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
                scope.launch {
                    explicitPlayerAction = true
                    try {
                        if (!persistDraftIfNeeded()) {
                            playheadMs = loadedAtMs ?: playheadMs
                            return@launch
                        }
                        playheadMs = target
                        requestFrameLoad(target)
                    } finally {
                        explicitPlayerAction = false
                    }
                }
            }
            observedPlayWhenReady = nowPlayWhenReady
            observedEnded = nowEnded
            playing = nowPlaying
            delay(150)
        }
    }

    Dialog(onDismissRequest = { requestExit() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF202020),
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                var overlay by remember { mutableStateOf<BubbleOverlay?>(null) }
                val value = draft
                val frame = loaded
                if (value == null || frame == null) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loadError == null) {
                            CircularProgressIndicator()
                            Text("正在读取当前画面…")
                        } else {
                            Text(loadError.orEmpty())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { loadNonce += 1 }) { Text("重试") }
                                TextButton(onClick = {
                                    player?.pause()
                                    onDismiss()
                                }) { Text("关闭") }
                            }
                        }
                    }
                } else {
                    LaunchedEffect(value.regions, value.gestureBefore, frame) {
                        if (value.gestureBefore != null) return@LaunchedEffect
                        val pixels = frame.decoded.retain()
                        val built = try { withContext(Dispatchers.Default) {
                            runCatching {
                                val source = BubbleMaskRenderer().prepareSource(frame.analysis, value.regions.map { it.region })
                                source.layout(value.regions, value.saved.renderSettings, hideEmpty = true, includeEmptyForEditing = true)
                            }.getOrNull()
                        } } finally { pixels.close() }
                        overlay = built
                    }
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                                .onSizeChanged { canvasSize = it },
                        ) {
                            val previewPlayer = player
                            if (previewPlayer != null) {
                                AndroidView(
                                    factory = { viewContext ->
                                        TexturePlayerPreview(viewContext, project.width, project.height, project.rotation)
                                    },
                                    update = { view -> view.attach(previewPlayer) },
                                    modifier = Modifier.fillMaxSize(),
                                    onRelease = { view -> view.detach() },
                                )
                            }
                            key(frame) {
                                VideoBubbleCanvas(
                                    frame = frame,
                                    draft = value,
                                    overlay = overlay,
                                    visible = !playing && !frameLoading,
                                    interactive = !saving,
                                    onSelected = { draft = draft?.select(it) },
                                    onGesture = { gesture ->
                                        if (gesture is BubbleEditGesture.Begin) pauseForEditing()
                                        draft = draft?.apply(gesture)
                                    },
                                )
                            }
                            if(playing && previewPlayer != null) VideoSavedOverlays(container, project.id, playheadMs, previewPlayer, true)
                            if (frameLoading) {
                                Box(
                                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Text("正在读取播放位置的画面…", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            val maxX = (canvasSize.width - toolbarSize.width).coerceAtLeast(0)
                            val maxY = (canvasSize.height - toolbarSize.height).coerceAtLeast(0)
                            Box(
                                Modifier.align(Alignment.TopStart)
                                    .offset {
                                        IntOffset(
                                            toolbarOffset.x.coerceIn(0, maxX),
                                            toolbarOffset.y.coerceIn(0, maxY),
                                        )
                                    }
                                    .onSizeChanged { toolbarSize = it }
                                    .pointerInput(canvasSize, toolbarSize) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val limitX = (canvasSize.width - toolbarSize.width).coerceAtLeast(0)
                                            val limitY = (canvasSize.height - toolbarSize.height).coerceAtLeast(0)
                                            toolbarOffset = IntOffset(
                                                (toolbarOffset.x + dragAmount.x.roundToInt()).coerceIn(0, limitX),
                                                (toolbarOffset.y + dragAmount.y.roundToInt()).coerceIn(0, limitY),
                                            )
                                        }
                                    },
                            ) {
                                BubbleEditorToolbar(
                                    state = BubbleEditorState(
                                        editing = true,
                                        saving = saving,
                                        busy = playing || frameLoading,
                                        hasDraft = true,
                                        dirty = value.dirty || cueDirty || cueHandle.isDirty,
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
                                    onText = {
                                        pauseForEditing()
                                        draft = draft?.editText(it)
                                    },
                                    onDelete = {
                                        pauseForEditing()
                                        draft = draft?.deleteSelected()
                                    },
                                    onSave = { persist(false) },
                                    onUndo = { draft = draft?.undoChange() },
                                    onExit = { requestExit() },
                                    onClearFailure = {
                                        saveError = null
                                        saveFailure = null
                                    },
                                    onAdd = {
                                        pauseForEditing()
                                        draft = draft?.addBubble(playheadMs, project.durationMs)
                                    },
                                    onIncreaseFont = {
                                        pauseForEditing()
                                        draft = draft?.scaleFont(5)
                                    },
                                    onDecreaseFont = {
                                        pauseForEditing()
                                        draft = draft?.scaleFont(-5)
                                    },
                                    modifier = Modifier.padding(8.dp).widthIn(max = if (landscape) 300.dp else 260.dp),
                                    maxWidth = if (landscape) 300.dp else 260.dp,
                                    compact = true,
                                )
                            }
                        }
                        VideoBubbleBottomPanel(
                            landscape = landscape,
                            transport = {
                                val selectedTime = value.selected?.let { value.times[it.region.id] }
                                VideoBubbleTransport(
                                    durationMs = project.durationMs,
                                    playheadMs = playheadMs,
                                    playing = playing,
                                    hasPlayer = player != null,
                                    transportEnabled = !saving && !frameLoading,
                                    canClose = !saving,
                                    bubbleRange = selectedTime?.let { EditorTimeBandRange(it.startMs, it.endMs) },
                                    onTogglePlayback = ::togglePlayback,
                                    onSeek = ::seekTo,
                                    onExit = ::requestExit,
                                )
                            },
                            cueEditor = {
                                VideoCurrentCueEditor(
                                    container = container,
                                    project = project,
                                    positionMs = playheadMs,
                                    onSeek = ::seekTo,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !saving && !frameLoading && !playing,
                                    handle = cueHandle,
                                    onDirtyChange = { cueDirty = it },
                                )
                            },
                            regionTiming = {
                                VideoBubbleRegionTiming(
                                    draft = value,
                                    durationMs = project.durationMs,
                                    playheadMs = playheadMs,
                                    enabled = !saving && !frameLoading && !playing,
                                    onSetTime = { start, end ->
                                        draft = draft?.setTime(value.selectedId, start, end, project.durationMs)
                                    },
                                )
                            },
                        )
                    }
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
                    if (cueDirty || cueHandle.isDirty) Text("当前字幕也有未保存的修改，保存会一并写入。")
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

/** Compact playback controls: play/pause at the left edge of the first timeline bar. */
@Composable
private fun VideoBubbleTransport(
    durationMs: Long,
    playheadMs: Long,
    playing: Boolean,
    hasPlayer: Boolean,
    transportEnabled: Boolean,
    canClose: Boolean,
    bubbleRange: EditorTimeBandRange?,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onExit: () -> Unit,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var timeText by remember { mutableStateOf(formatVideoTimecode(playheadMs)) }
    var timeFocused by remember { mutableStateOf(false) }
    var timeError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playheadMs, playing) {
        if (!timeFocused || playing) timeText = formatVideoTimecode(playheadMs)
    }
    val safeDuration = durationMs.coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    onTogglePlayback()
                },
                enabled = transportEnabled && hasPlayer,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            Slider(
                value = (dragMs ?: playheadMs).toFloat().coerceIn(0f, durationMs.toFloat()),
                onValueChange = { dragMs = it.roundToLong() },
                onValueChangeFinished = {
                    focusManager.clearFocus()
                    dragMs?.let {
                        val value = it.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                        onSeek(value)
                    }
                    dragMs = null
                },
                valueRange = 0f..safeDuration.toFloat(),
                enabled = transportEnabled,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Text("总时长 ${formatVideoTimecode(durationMs)}", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = timeText,
                onValueChange = { if (it.length <= 16) timeText = it },
                label = { Text("当前位置") },
                singleLine = true,
                enabled = transportEnabled,
                readOnly = playing,
                modifier = Modifier.width(190.dp).onFocusChanged { timeFocused = it.isFocused },
            )
            TextButton(
                onClick = {
                    val parsed = parseVideoTimecode(timeText)
                    timeError = when {
                        parsed == null -> "时间格式应为秒 / 分:秒 / 时:分:秒"
                        parsed != null && parsed > (durationMs - 1).coerceAtLeast(0) -> "时间不能超过视频总时长"
                        else -> null
                    }
                    if (parsed != null && timeError == null) {
                        focusManager.clearFocus()
                        timeFocused = false
                        if (parsed == playheadMs) timeText = formatVideoTimecode(parsed)
                        onSeek(parsed)
                    }
                },
                enabled = transportEnabled,
            ) { Text("跳转") }
            Spacer(Modifier.weight(1f))
            if (!hasPlayer) Text("未连接播放器", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onExit, enabled = canClose) { Text("关闭") }
        }
        timeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        EditorTimeBand(
            durationMs = durationMs,
            playheadMs = (dragMs ?: playheadMs).coerceIn(0, (durationMs - 1).coerceAtLeast(0)),
            ranges = listOfNotNull(bubbleRange),
            selected = bubbleRange,
        )
    }
}

@Composable
private fun VideoBubbleRegionTiming(
    draft: VideoBubbleDraft,
    durationMs: Long,
    playheadMs: Long,
    enabled: Boolean,
    onSetTime: (Long, Long) -> Unit,
) {
    val selected = draft.selected
    val selectedTime = selected?.let { draft.times[it.region.id] }
    val current = playheadMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    var startText by remember(selected?.region?.id, selectedTime?.startMs) {
        mutableStateOf(selectedTime?.let { formatVideoTimecode(it.startMs) }.orEmpty())
    }
    var endText by remember(selected?.region?.id, selectedTime?.endMs) {
        mutableStateOf(selectedTime?.let { formatVideoTimecode(it.endMs) }.orEmpty())
    }
    var rangeError by remember(selected?.region?.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (selected == null || selectedTime == null) {
            Text("点选画面中的气泡以校对它的出现时间。", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("起点 ${formatVideoTimecode(selectedTime.startMs)}", style = MaterialTheme.typography.labelSmall)
                Text("终点 ${formatVideoTimecode(selectedTime.endMs)}", style = MaterialTheme.typography.labelSmall)
                Text("时长 ${formatVideoTimecode(selectedTime.endMs - selectedTime.startMs)}", style = MaterialTheme.typography.labelSmall)
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
                        val start = parseVideoTimecode(startText)
                        val end = parseVideoTimecode(endText)
                        rangeError = when {
                            start == null || end == null -> "时间格式应为秒 / 分:秒 / 时:分:秒"
                            !validBubbleTimeRange(start, end, durationMs) -> "必须满足 0 ≤ 起点 < 终点 ≤ 视频总时长"
                            else -> null
                        }
                        if (rangeError == null && start != null && end != null) {
                            onSetTime(start, end)
                            startText = formatVideoTimecode(start)
                            endText = formatVideoTimecode(end)
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

/** Portrait: cue editor directly below the two bars. Landscape: to the right of transport. */
@Composable
private fun VideoBubbleBottomPanel(
    landscape: Boolean,
    transport: @Composable () -> Unit,
    cueEditor: @Composable () -> Unit,
    regionTiming: @Composable () -> Unit,
) {
    val availableHeight = LocalConfiguration.current.screenHeightDp.dp
    Column(
        Modifier.fillMaxWidth().heightIn(max = availableHeight * if (landscape) .48f else .52f)
            .background(Color(0xFF0B0B0B)).navigationBarsPadding(),
    ) {
        if (landscape) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(
                    Modifier.weight(1.15f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    transport()
                    regionTiming()
                }
                Box(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) { cueEditor() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                transport()
                regionTiming()
                cueEditor()
            }
        }
    }
}

@Composable
private fun VideoBubbleCanvas(
    frame: LoadedFrame,
    draft: VideoBubbleDraft,
    overlay: BubbleOverlay?,
    visible: Boolean,
    interactive: Boolean,
    onSelected: (String?) -> Unit,
    onGesture: (BubbleEditGesture) -> Unit,
) {
    AndroidView(
        factory = { context ->
            TapAwareSubsamplingImageView(context, onSingleTap = { _, _ -> }).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                // Match the reader's zoom allowance so low-resolution frames fit the
                // available video area instead of being capped at their native pixels.
                setMinimumDpi(1)
                // The reader preserves zoom when its viewport changes. Here the
                // transport/cue panels can shrink a height-limited video after
                // the first draw. Keep a fitted frame fitted, while preserving
                // a deliberate user zoom during editing.
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (isReady && right > left && bottom > top && oldRight > oldLeft && oldBottom > oldTop &&
                        (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                    ) {
                        val oldFit = minOf((oldRight - oldLeft).toFloat() / sWidth, (oldBottom - oldTop).toFloat() / sHeight)
                        if (kotlin.math.abs(scale - oldFit) < .03f) resetScaleAndCenter()
                    }
                }
                setImage(ImageSource.bitmap(frame.bitmap))
                showTranslation = true
                editing = true
            }
        },
        update = { view ->
            view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            view.editing = interactive
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

/**
 * Re-uses the supplied player and renders it into a TextureView. The wrapped PlayerView
 * owns no player of its own beyond the supplied instance (no new ExoPlayer/session).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class TexturePlayerPreview(
    context: Context,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val videoRotation: Int,
) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var attached: Player? = null

    init {
        setBackgroundColor(AndroidColor.BLACK)
        addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                attached?.let { player ->
                    if (texture.isAvailable) player.setVideoTextureView(texture)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                attached?.clearVideoTextureView(texture)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
    }

    fun attach(player: Player) {
        if (attached === player) return
        detach()
        attached = player
        if (texture.isAvailable) player.setVideoTextureView(texture)
    }

    fun detach() {
        val player = attached ?: return
        attached = null
        player.clearVideoTextureView(texture)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val height = android.view.View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val (fittedWidth, fittedHeight) = fitSize(width, height)
        texture.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(fittedWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(fittedHeight, android.view.View.MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - texture.measuredWidth) / 2
        val y = (height - texture.measuredHeight) / 2
        texture.layout(x, y, x + texture.measuredWidth, y + texture.measuredHeight)
    }

    private fun fitSize(width: Int, height: Int): Pair<Int, Int> {
        val rotated = videoRotation % 180 != 0
        val actual = attached?.videoSize
        val sourceWidth = actual?.takeIf { it.width > 0 }?.let { (it.width * it.pixelWidthHeightRatio).roundToInt() }
            ?: if (rotated) videoHeight else videoWidth
        val sourceHeight = actual?.height?.takeIf { it > 0 } ?: if (rotated) videoWidth else videoHeight
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return width to height
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
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
    val sourceArtifacts: Map<String, ReaderPageTranslation> = emptyMap(),
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
        val image = frame.bitmap
        val sourceHash = VideoTranslationEngine.bitmapHash(image)
        val preferred = JSONObject(dao.settings(project.id)?.json ?: "{}").optString("frameIdentity")
        val candidates = dao.framesAt(project.id, project.sha256, targetUs)
        val index = candidates.firstOrNull { it.identity == preferred } ?: candidates.firstOrNull()
        val identity = preferred.ifBlank { index?.identity.orEmpty() }
        val active = dao.regionsAtIdentity(project.id, project.sha256, identity, targetMs)
            .sortedWith(compareByDescending<VideoRegionInterval> { it.manualTiming }.thenByDescending { it.startMs })
            .distinctBy { it.trackId }
        val artifacts = container.videoLibrary.artifacts(project.id)
        val originals = active.map { it.pageId }.distinct().associateWith { pageId ->
            artifacts.current(pageId) ?: error("视频气泡成果缺失，请重新生成对应画面")
        }
        val regions = ArrayList<PageTranslatedRegion>()
        val times = LinkedHashMap<String, BubbleTime>()
        val intervals = LinkedHashMap<String, VideoRegionInterval>()
        val manualPageId = "video:${project.id}:manual:${UUID.randomUUID()}"
        for(row in active) {
            val saved = originals.getValue(row.pageId)
            val original = saved.regions.firstOrNull { it.region.id == row.regionId } ?: continue
            val scale = ScreenTransform(image.width.toFloat() / saved.width)
            val scaled = original.copy(region = original.region.copy(bounds = scale.bounds(original.region.bounds),
                contour = original.region.contour.map(scale::point), textBounds = original.region.textBounds.map(scale::bounds)))
            val trackIdentity = saved.resultIdentity ?: identity
            val observation = dao.observationAt(project.id, project.sha256, trackIdentity, row.trackId, targetUs)
            val anchor = dao.observationAt(project.id, project.sha256, trackIdentity, row.trackId,
                row.anchorUs ?: (row.observedStartMs * 1000 + 999))
            val moved = if(observation == null) scaled else moveVideoRegion(scaled, anchor, observation, image.width, image.height)
            val regionId = "$manualPageId:retained:${VideoTasks.stableId(row.regionId)}"
            regions += moved.copy(region = moved.region.copy(id = regionId))
            val track = if(row.manualTiming) listOf(row) else dao.trackRegions(project.id, row.trackId)
                .filter { it.sourceHash == project.sha256 && !it.deleted }
            times[regionId] = normalizeBubbleTime(track.minOfOrNull { it.startMs } ?: row.startMs,
                track.maxOfOrNull { it.endMs } ?: row.endMs, targetMs, project.durationMs)
            intervals[regionId] = row
        }
        val template = originals.values.firstOrNull()
        // Publish one atomic manual snapshot; keep the original machine artifacts intact.
        val saved = ReaderPageTranslation(manualPageId, "", File(""),
            template?.source ?: LocalTranslationLanguage.fromTag(project.source),
            template?.target ?: LocalTranslationLanguage.fromTag(project.target), sourceHash, image.width, image.height,
            regions, template?.modelPacks.orEmpty(), 0,
            template?.renderSettings ?: container.bubbleRenderPreferences.settings.first(), resultIdentity = identity.takeIf { it.isNotBlank() })
        LoadedFrame(frame, image, saved, sourceHash, image, null, null, targetUs, project.durationMs,
            times, intervals, null, originals)
    } catch(failure: Throwable) {
        decoded?.close(); throw failure
    } finally { decoder.close() }
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
                for(expected in draft.intervals.values) {
                    dao.region(expected.intervalId)?.let { freshById[it.intervalId] = it }
                }
                val regionIdsWithRows = pageRows
                    .filter { it.videoId == project.id && it.sourceHash == project.sha256 && it.pageId == draft.saved.pageId }
                    .map { it.regionId }
                    .toHashSet()
                for ((regionId, expected) in draft.intervals) {
                    val actual = freshById[expected.intervalId] ?: intervalConflict()
                    if (actual.regionId != expected.regionId || actual.pageId != expected.pageId ||
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
                    for((pageId, source) in frame.sourceArtifacts) {
                        if(artifacts.current(pageId)?.revision != source.revision) throw PageTranslationRevisionConflict()
                    }
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
                            anchorUs = frame.targetUs,
                        )
                        check(dao.addRegion(row) != -1L) { "无法建立气泡时间区间" }
                        chosenIds[regionId] = row.intervalId
                    } else {
                        val actual = freshById[expected.intervalId] ?: intervalConflict()
                        chosenIds[regionId] = actual.intervalId
                        // Geometry now references the editor's raw frame, independently of
                        // when this track first appeared. A text/geometry edit does not
                        // turn unchanged machine timing into a human timing override.
                        val timingChanged = draft.baselineTimes[regionId] != time
                        val changed = actual.copy(pageId = draft.saved.pageId, regionId = regionId, anchorUs = frame.targetUs,
                            startMs = time.startMs, endMs = time.endMs,
                            manualTiming = actual.manualTiming || timingChanged, deleted = false)
                        if(changed != actual) dao.saveRegion(changed, actual.revision)
                        if(!actual.manualTiming) {
                            for(other in dao.trackRegions(project.id, actual.trackId)) {
                                if(other.intervalId != actual.intervalId && !other.manualTiming && !other.deleted)
                                    dao.saveRegion(other.copy(deleted = true), other.revision)
                            }
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
                VideoFrameProof.capture(frame.analysis, saved).save(container.videoLibrary.proofs(project.id), saved.pageId)
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
        identity = saved.resultIdentity ?: frame.sourceHash,
        sampleIndex = -com.lmreader.ui.video.VideoTasks.hash(saved.pageId).take(15).toLong(16) - 1,
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
