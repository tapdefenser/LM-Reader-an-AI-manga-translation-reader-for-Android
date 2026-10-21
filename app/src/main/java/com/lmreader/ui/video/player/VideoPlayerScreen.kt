package com.lmreader.ui.video.player

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.os.bundleOf
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.catalog.videoTaskState
import com.lmreader.ui.video.catalog.videoTranslationStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lifecycle/window callbacks supplied by [VideoPlayerActivity]. */
internal class VideoPlayerWindowActions(
    val onBack: () -> Unit,
    val onRetryController: () -> Unit,
    val onSavePosition: () -> Unit,
    val onEnterPip: () -> Unit,
    val onRotate: () -> Unit,
    val onTemporarySpeedStart: () -> Unit,
    val onTemporarySpeedEnd: () -> Unit,
    val onBrightnessChange: (Float) -> Unit,
    val currentBrightness: () -> Float,
    val onVolumeChange: (Int) -> Unit,
    val currentVolume: () -> Int,
    val maxVolume: () -> Int,
)

internal enum class PlayerPlaybackMode(val label: String) {
    LIST_LOOP("列表循环"),
    ONE_LOOP("单曲循环"),
    PAUSE_AFTER("播完暂停"),
    SHUFFLE("随机播放"),
}

@Composable
internal fun VideoPlayerScreen(
    container: AppContainer,
    viewModel: VideoPlayerViewModel,
    controller: MediaController?,
    playbackReady: Boolean,
    controllerError: String?,
    inPip: Boolean,
    active: Boolean,
    rotationGeneration: Int,
    actions: VideoPlayerWindowActions,
) {
    val loadState by viewModel.state.collectAsStateWithLifecycle()
    if (controllerError != null) {
        PlayerLoadError(
            title = "无法连接播放器服务",
            message = controllerError,
            onRetry = actions.onRetryController,
            onSkip = null,
            onBack = actions.onBack,
        )
        return
    }
    when (val state = loadState) {
        VideoPlaylistState.Loading -> PlayerLoading(onBack = actions.onBack)
        is VideoPlaylistState.Error -> PlayerLoadError(
            title = "无法播放此视频",
            message = state.message,
            onRetry = viewModel::load,
            onSkip = if (state.canSkip) viewModel::skipCurrent else null,
            onBack = actions.onBack,
        )
        is VideoPlaylistState.Ready -> {
            if (controller == null || !playbackReady) {
                PlayerLoading(onBack = actions.onBack)
            } else {
                PlayerSurface(
                    container = container,
                    viewModel = viewModel,
                    controller = controller,
                    state = state,
                    actions = actions,
                    inPip = inPip,
                    active = active,
                    rotationGeneration = rotationGeneration,
                )
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerSurface(
    container: AppContainer,
    viewModel: VideoPlayerViewModel,
    controller: MediaController,
    state: VideoPlaylistState.Ready,
    actions: VideoPlayerWindowActions,
    inPip: Boolean,
    active: Boolean,
    rotationGeneration: Int,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val snapshot = rememberPlayerSnapshot(controller, active)
    val currentVideoId = snapshot.currentMediaId?.takeIf { it.isNotBlank() } ?: state.currentId
    val currentItem = viewModel.itemFor(currentVideoId)
    val tasks by container.taskScheduler.tasks.collectAsStateWithLifecycle()
    val projects by container.videoLibrary.projects.collectAsStateWithLifecycle()
    val host = VideoPlayerToolRegistry.host
    val prefs = remember(context) {
        context.getSharedPreferences(VideoPlayerPreferences.NAME, Context.MODE_PRIVATE)
    }
    val pipSupported = remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var showTranslation by rememberSaveable { mutableStateOf(true) }
    var backgroundAllowed by rememberSaveable {
        mutableStateOf(prefs.getBoolean(VideoPlayerPreferences.KEY_BACKGROUND_PLAYBACK, false))
    }
    var mode by remember { mutableStateOf(PlayerPlaybackMode.LIST_LOOP) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var translateMenu by remember { mutableStateOf(false) }
    var playlistOpen by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(activity?.intent?.getStringExtra("initial-tool")?.let {
        VideoPlayerToolRequest(currentVideoId, snapshot.positionMs.coerceAtLeast(0), it)
    }) }
    LaunchedEffect(Unit) { activity?.intent?.removeExtra("initial-tool") }
    var notice by remember { mutableStateOf<String?>(null) }
    var gestureHint by remember { mutableStateOf<PlayerGestureHint?>(null) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var lastInteraction by remember { mutableIntStateOf(0) }

    val playbackError = snapshot.playbackError
    val errorActive = playbackError != null
    val durationMs = snapshot.durationMs.takeIf { it > 0L }
        ?: currentItem?.durationMs?.takeIf { it > 0L }
        ?: 0L
    val positionMs = dragPosition?.toLong() ?: snapshot.positionMs.coerceAtLeast(0L)

    fun touch() {
        lastInteraction += 1
        if (!locked) controlsVisible = true
    }

    fun openDialog(next: PlayerDialog) {
        actions.onTemporarySpeedEnd()
        gestureHint = null
        playlistOpen = false
        dialog = next
        touch()
    }

    fun openPlaylist() {
        actions.onTemporarySpeedEnd()
        gestureHint = null
        dialog = null
        translateMenu = false
        playlistOpen = true
        controlsVisible = true
        lastInteraction += 1
    }

    fun requestTool(action: String) {
        val installed = VideoPlayerToolRegistry.host
        if (installed == null) {
            notice = "翻译与字幕工具尚未安装，当前只能播放原视频"
            return
        }
        if (currentVideoId.isBlank()) {
            notice = "当前视频不可用"
            return
        }
        request = VideoPlayerToolRequest(
            videoId = currentVideoId,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            action = action,
        )
        controlsVisible = false
        translateMenu = false
        playlistOpen = false
        dialog = null
        actions.onTemporarySpeedEnd()
        lastInteraction += 1
    }

    fun changeTranslationVisibility() {
        val installed = VideoPlayerToolRegistry.host
        if (installed == null) {
            notice = "翻译与字幕工具尚未安装，无法切换原文/译文"
            return
        }
        showTranslation = !showTranslation
        translateMenu = false
        playlistOpen = false
        touch()
    }

    fun shareCurrent() {
        val item = currentItem
        if (item == null) {
            notice = "当前视频信息不可用"
            return
        }
        val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
        if (uri == null || item.uri.isBlank()) {
            notice = "视频地址无效，无法分享"
            return
        }
        val send = if (uri.scheme == "content" || uri.scheme == "file") {
            Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("video", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.uri)
            }
        }
        try {
            context.startActivity(Intent.createChooser(send, "分享视频"))
        } catch (_: ActivityNotFoundException) {
            notice = "没有可用的分享应用"
        } catch (error: Exception) {
            notice = "无法分享此视频：${error.message ?: "未知错误"}"
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = durationMs.takeIf { it > 0L } ?: return
        val target = (controller.currentPosition + deltaMs)
            .coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
        controller.seekTo(target)
        gestureHint = PlayerGestureHint(
            icon = if (deltaMs < 0) PlayerGestureIcon.SEEK_BACK else PlayerGestureIcon.SEEK_FORWARD,
            text = "${if (deltaMs < 0) "−" else "+"}10s · ${formatTime(target)} / ${formatTime(duration)}",
        )
        lastInteraction += 1
    }

    fun togglePlayback() {
        val playing = controller.isPlaying
        if (playing) controller.pause() else controller.play()
        gestureHint = PlayerGestureHint(
            icon = if (playing) PlayerGestureIcon.PAUSE else PlayerGestureIcon.PLAY,
            text = if (playing) "暂停" else "播放",
        )
        lastInteraction += 1
    }

    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        delay(3_000L)
        if (notice == current) notice = null
    }
    LaunchedEffect(gestureHint) {
        val current = gestureHint ?: return@LaunchedEffect
        if (current.persistent) return@LaunchedEffect
        delay(1_200L)
        if (gestureHint == current) gestureHint = null
    }
    LaunchedEffect(currentVideoId) {
        if(request?.videoId != currentVideoId) request = null
        if (!locked) controlsVisible = true
        lastInteraction += 1
    }
    LaunchedEffect(rotationGeneration) {
        actions.onTemporarySpeedEnd()
        gestureHint = null
        if (!locked) controlsVisible = true
        lastInteraction += 1
    }
    LaunchedEffect(active) {
        if (!active) {
            actions.onTemporarySpeedEnd()
            dialog = null
            translateMenu = false
            playlistOpen = false
            dragPosition = null
            gestureHint = null
            notice = null
        } else if (!locked) {
            controlsVisible = true
        }
    }
    LaunchedEffect(inPip) {
        if (inPip) {
            actions.onTemporarySpeedEnd()
            dialog = null
            translateMenu = false
            playlistOpen = false
            request = null
            gestureHint = null
            notice = null
            controlsVisible = false
        } else if (!locked) {
            controlsVisible = true
        }
    }
    LaunchedEffect(snapshot.isPlaying) {
        if (active && !snapshot.isPlaying && !locked && !inPip && !errorActive) {
            controlsVisible = true
        }
    }
    LaunchedEffect(
        active,
        controlsVisible,
        snapshot.isPlaying,
        locked,
        dialog,
        translateMenu,
        playlistOpen,
        request,
        inPip,
        dragPosition,
        lastInteraction,
        errorActive,
    ) {
        if (active && controlsVisible && snapshot.isPlaying && !locked &&
            dialog == null && !translateMenu && !playlistOpen && request == null &&
            !inPip && dragPosition == null && !errorActive
        ) {
            delay(3_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(snapshot.playbackError) {
        if (snapshot.playbackError != null) {
            actions.onTemporarySpeedEnd()
            locked = false
            dialog = null
            translateMenu = false
            playlistOpen = false
            request = null
            notice = null
            gestureHint = null
        }
    }
    LaunchedEffect(state.skipped) {
        if (state.skipped.isNotEmpty() && notice == null) {
            notice = "已跳过 ${state.skipped.size} 个无法读取的视频"
        }
    }
    DisposableEffect(controller, mode, active) {
        if (!active) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mode == PlayerPlaybackMode.PAUSE_AFTER &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                ) {
                    // Local fallback: the service also receives the private command,
                    // but the UI must behave correctly if it was connected late.
                    controller.pause()
                }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    LaunchedEffect(controller, mode, active) {
        if (active) applyPlaybackMode(controller, mode)
    }
    DisposableEffect(Unit) {
        onDispose { actions.onTemporarySpeedEnd() }
    }

    BackHandler(enabled = active && !inPip) {
        when {
            playlistOpen -> {
                playlistOpen = false
                lastInteraction += 1
            }
            locked -> {
                locked = false
                controlsVisible = true
                lastInteraction += 1
            }
            dialog != null -> {
                dialog = null
                lastInteraction += 1
            }
            translateMenu -> {
                translateMenu = false
                lastInteraction += 1
            }
            request != null -> {
                request = null
                if (!locked) controlsVisible = true
                lastInteraction += 1
            }
            else -> actions.onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    setUseController(false)
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view ->
                val wanted = if (!active || errorActive || request?.action == VideoPlayerToolActions.EDIT) null else controller
                if (view.player !== wanted) view.setPlayer(wanted)
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view -> view.setPlayer(null) },
        )

        Box(
            Modifier
                .fillMaxSize()
                .playerGestures(
                    player = controller,
                    enabled = active && !locked && !inPip && !errorActive &&
                        request == null && dialog == null && !translateMenu && !playlistOpen,
                    onTap = {
                        if (!errorActive) {
                            controlsVisible = !controlsVisible
                            lastInteraction += 1
                        }
                    },
                    onTogglePlayback = {
                        if (!errorActive) togglePlayback()
                    },
                    onSeekBy = ::seekBy,
                    onSeekPreview = { target, delta ->
                        gestureHint = PlayerGestureHint(
                            icon = if (delta < 0) PlayerGestureIcon.SEEK_BACK else PlayerGestureIcon.SEEK_FORWARD,
                            text = "${if (delta < 0) "−" else "+"}${abs(delta) / 1_000}s · " +
                                "${formatTime(target)} / ${formatTime(durationMs)} · " +
                                "${if (durationMs > 0L) (target * 100L / durationMs) else 0L}%",
                        )
                    },
                    onSeekCommit = { target ->
                        controller.seekTo(target)
                        gestureHint = PlayerGestureHint(
                            icon = PlayerGestureIcon.SEEK_FORWARD,
                            text = "跳转到 ${formatTime(target)}",
                        )
                    },
                    onTemporarySpeedStart = actions.onTemporarySpeedStart,
                    onTemporarySpeedEnd = actions.onTemporarySpeedEnd,
                    onHint = { gestureHint = it },
                    onBrightnessChange = { value ->
                        actions.onBrightnessChange(value)
                        gestureHint = PlayerGestureHint(
                            icon = PlayerGestureIcon.BRIGHTNESS,
                            text = "亮度 ${(value * 100f).roundToInt()}%",
                        )
                    },
                    currentBrightness = actions.currentBrightness,
                    onVolumeChange = { value ->
                        actions.onVolumeChange(value)
                        val max = actions.maxVolume().coerceAtLeast(1)
                        gestureHint = PlayerGestureHint(
                            icon = PlayerGestureIcon.VOLUME,
                            text = "音量 ${(value * 100f / max).roundToInt()}%",
                        )
                    },
                    currentVolume = actions.currentVolume,
                    maxVolume = actions.maxVolume,
                ),
        )

        if (active && host != null) {
            host.Content(
                container = container,
                videoId = currentVideoId,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                player = controller,
                request = request,
                showTranslation = showTranslation,
                onDismiss = {
                    request = null
                    if (!locked) controlsVisible = true
                    lastInteraction += 1
                },
            )
        }

        if (active && errorActive) {
            PlayerPlaybackError(
                message = playbackError?.message ?: "视频无法继续播放",
                canSkip = snapshot.mediaItemCount > 1,
                onRetry = {
                    runCatching {
                        controller.prepare()
                        controller.play()
                    }
                },
                onSkip = {
                    if (snapshot.mediaItemCount > 1) {
                        actions.onSavePosition()
                        runCatching {
                            controller.seekToNext()
                            controller.prepare()
                            controller.play()
                        }
                    }
                },
            )
        }

        if (active && !inPip && !errorActive && !locked && controlsVisible &&
            request == null && !playlistOpen
        ) {
            TopControls(
                title = currentItem?.name ?: "视频",
                onBack = {
                    touch()
                    actions.onBack()
                },
                onMenu = { openDialog(PlayerDialog.MENU) },
            )
            BottomControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = snapshot.isPlaying,
                onSliderStart = {
                    dragPosition = it
                    lastInteraction += 1
                    controlsVisible = true
                },
                onSliderChange = {
                    dragPosition = it
                    lastInteraction += 1
                },
                onSliderCommit = {
                    dragPosition?.let { value -> controller.seekTo(value.toLong()) }
                    dragPosition = null
                    touch()
                },
                onRotate = {
                    touch()
                    actions.onRotate()
                },
                onPrevious = {
                    actions.onSavePosition()
                    controller.seekToPrevious()
                    touch()
                },
                onPlayPause = {
                    togglePlayback()
                    touch()
                },
                onNext = {
                    actions.onSavePosition()
                    controller.seekToNext()
                    touch()
                },
                onPlaylist = { openPlaylist() },
            )
            LockButton(
                locked = false,
                onClick = {
                    actions.onTemporarySpeedEnd()
                    locked = true
                    controlsVisible = false
                    dialog = null
                    translateMenu = false
                    playlistOpen = false
                    request = null
                    notice = null
                    gestureHint = null
                    lastInteraction += 1
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            )
            RoundIconButton(
                icon = Icons.Filled.Translate,
                description = "翻译",
                onClick = {
                    actions.onTemporarySpeedEnd()
                    gestureHint = null
                    translateMenu = true
                    controlsVisible = true
                    lastInteraction += 1
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            )
        }

        if (active && !inPip && locked) {
            LockButton(
                locked = true,
                onClick = {
                    locked = false
                    controlsVisible = true
                    lastInteraction += 1
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            )
        }

        if (active) gestureHint?.let { hint ->
            PlayerGestureHintView(hint, Modifier.align(Alignment.Center))
        }

        if (active) notice?.let { message ->
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 112.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        if (active && !inPip) {
            when (dialog) {
                PlayerDialog.MENU -> PlayerMenuDialog(
                    currentSpeed = snapshot.playbackSpeed,
                    mode = mode,
                    backgroundAllowed = backgroundAllowed,
                    pipSupported = pipSupported,
                    onDismiss = {
                        dialog = null
                        lastInteraction += 1
                    },
                    onShare = {
                        dialog = null
                        shareCurrent()
                        lastInteraction += 1
                    },
                    onEnterPip = {
                        dialog = null
                        actions.onEnterPip()
                    },
                    onSpeed = { openDialog(PlayerDialog.SPEED) },
                    onAudio = { openDialog(PlayerDialog.AUDIO) },
                    onMode = { openDialog(PlayerDialog.MODE) },
                    onSubtitles = { requestTool(VideoPlayerToolActions.SUBTITLES) },
                    onToggleBackground = {
                        backgroundAllowed = !backgroundAllowed
                        prefs.edit()
                            .putBoolean(VideoPlayerPreferences.KEY_BACKGROUND_PLAYBACK, backgroundAllowed)
                            .apply()
                        lastInteraction += 1
                    },
                )
                PlayerDialog.SPEED -> SpeedDialog(
                    currentSpeed = snapshot.playbackSpeed,
                    onDismiss = {
                        dialog = null
                        lastInteraction += 1
                    },
                    onSelect = { speed ->
                        actions.onTemporarySpeedEnd()
                        controller.setPlaybackSpeed(speed)
                        prefs.edit().putFloat(VideoPlayerPreferences.KEY_SPEED, speed).apply()
                        dialog = null
                        lastInteraction += 1
                    },
                )
                PlayerDialog.AUDIO -> AudioTrackDialog(
                    options = snapshot.audioTracks,
                    autoSelected = snapshot.audioAutoSelected,
                    disabled = snapshot.audioDisabled,
                    onDismiss = {
                        dialog = null
                        lastInteraction += 1
                    },
                    onAuto = {
                        controller.trackSelectionParameters = controller.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .build()
                        dialog = null
                        lastInteraction += 1
                    },
                    onSelect = { option ->
                        controller.trackSelectionParameters = controller.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(
                                TrackSelectionOverride(option.group, option.index),
                            )
                            .build()
                        dialog = null
                        lastInteraction += 1
                    },
                    onOff = {
                        controller.trackSelectionParameters = controller.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                        dialog = null
                        lastInteraction += 1
                    },
                )
                PlayerDialog.MODE -> ModeDialog(
                    selected = mode,
                    onDismiss = {
                        dialog = null
                        lastInteraction += 1
                    },
                    onSelect = { selected ->
                        mode = selected
                        applyPlaybackMode(controller, selected)
                        dialog = null
                        lastInteraction += 1
                    },
                )
                null -> Unit
            }

            if (translateMenu) {
                TranslateMenu(
                    showTranslation = showTranslation,
                    onDismiss = {
                        translateMenu = false
                        lastInteraction += 1
                    },
                    onFrame = { requestTool(VideoPlayerToolActions.FRAME) },
                    onTranslate = { requestTool(VideoPlayerToolActions.TRANSLATE) },
                    onOptions = { requestTool(VideoPlayerToolActions.OPTIONS) },
                    onEdit = { requestTool(VideoPlayerToolActions.EDIT) },
                    onToggleSubtitles = { requestTool(VideoPlayerToolActions.TOGGLE_SUBTITLES) },
                    onToggleOriginal = ::changeTranslationVisibility,
                    onClear = { requestTool(VideoPlayerToolActions.CLEAR) },
                )
            }

            VideoPlaylistDrawer(
                visible = playlistOpen,
                playlist = state.items,
                currentId = currentVideoId,
                statusFor = { id ->
                    videoTranslationStatus(
                        taskState = videoTaskState(tasks, id),
                        hasProject = projects.any { it.id == id },
                    )
                },
                onDismiss = {
                    playlistOpen = false
                    lastInteraction += 1
                },
                onSelect = { item ->
                    playlistOpen = false
                    lastInteraction += 1
                    if (item.videoId != currentVideoId) {
                        actions.onSavePosition()
                        scope.launch {
                            val fresh = viewModel.freshPosition(item.videoId)
                            val index = (0 until controller.mediaItemCount)
                                .firstOrNull { controller.getMediaItemAt(it).mediaId == item.videoId }
                                ?: return@launch
                            controller.seekTo(index, startPosition(fresh, item.durationMs))
                            controller.play()
                        }
                    }
                },
            )
        }
    }
}

private enum class PlayerDialog {
    MENU,
    SPEED,
    AUDIO,
    MODE,
}

private data class AudioTrackUi(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

private data class PlayerSnapshot(
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val mediaItemCount: Int = 0,
    val currentMediaId: String? = null,
    val currentIndex: Int = 0,
    val playbackSpeed: Float = 1f,
    val audioTracks: List<AudioTrackUi> = emptyList(),
    val audioAutoSelected: Boolean = true,
    val audioDisabled: Boolean = false,
    val playbackError: PlaybackException? = null,
)

@Composable
private fun rememberPlayerSnapshot(player: MediaController?, active: Boolean = true): PlayerSnapshot {
    var snapshot by remember(player) {
        mutableStateOf(player?.let { playerSnapshot(it) } ?: PlayerSnapshot())
    }
    DisposableEffect(player, active) {
        if (!active) return@DisposableEffect onDispose {}
        val activePlayer = player ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot = playerSnapshot(activePlayer)
            }

            override fun onPlayerError(error: PlaybackException) {
                snapshot = playerSnapshot(activePlayer)
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                snapshot = playerSnapshot(activePlayer)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                snapshot = playerSnapshot(activePlayer)
            }
        }
        activePlayer.addListener(listener)
        snapshot = playerSnapshot(activePlayer)
        onDispose { activePlayer.removeListener(listener) }
    }
    LaunchedEffect(player, snapshot.isPlaying, active) {
        if (!active) return@LaunchedEffect
        val activePlayer = player ?: return@LaunchedEffect
        while (true) {
            snapshot = snapshot.copy(
                isPlaying = activePlayer.isPlaying,
                playbackState = activePlayer.playbackState,
                positionMs = activePlayer.currentPosition.coerceAtLeast(0L),
                durationMs = activePlayer.duration.takeIf { it > 0L } ?: 0L,
                bufferedPositionMs = activePlayer.bufferedPosition.coerceAtLeast(0L),
                mediaItemCount = activePlayer.mediaItemCount,
                currentMediaId = activePlayer.currentMediaItem?.mediaId,
                currentIndex = activePlayer.currentMediaItemIndex.coerceAtLeast(0),
            )
            delay(if (activePlayer.isPlaying) 250L else 1_000L)
        }
    }
    return snapshot
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun playerSnapshot(player: Player): PlayerSnapshot {
    val parameters = player.trackSelectionParameters
    val audioDisabled = parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
    val hasAudioOverride = parameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }
    val audio = buildList {
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                add(
                    AudioTrackUi(
                        group = group.mediaTrackGroup,
                        index = index,
                        label = audioTrackLabel(format, index),
                        selected = group.isTrackSelected(index),
                    ),
                )
            }
        }
    }
    return PlayerSnapshot(
        isPlaying = player.isPlaying,
        playbackState = player.playbackState,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it > 0L } ?: 0L,
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
        mediaItemCount = player.mediaItemCount,
        currentMediaId = player.currentMediaItem?.mediaId,
        currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
        playbackSpeed = player.playbackParameters.speed,
        audioTracks = audio,
        audioAutoSelected = !audioDisabled && !hasAudioOverride,
        audioDisabled = audioDisabled,
        playbackError = player.playerError,
    )
}

private fun audioTrackLabel(format: Format, index: Int): String {
    val base = format.label?.trim().orEmpty()
    val language = format.language?.let(::languageName).orEmpty()
    return when {
        base.isNotEmpty() && language.isNotEmpty() && !base.contains(language, ignoreCase = true) ->
            "$base · $language"
        base.isNotEmpty() -> base
        language.isNotEmpty() -> language
        else -> "音轨 ${index + 1}"
    }
}

private fun languageName(tag: String): String {
    if (tag.isBlank()) return ""
    return runCatching {
        Locale.forLanguageTag(tag).displayLanguage.ifBlank { tag }
    }.getOrDefault(tag)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun applyPlaybackMode(controller: MediaController, mode: PlayerPlaybackMode) {
    when (mode) {
        PlayerPlaybackMode.LIST_LOOP -> {
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.shuffleModeEnabled = false
        }
        PlayerPlaybackMode.ONE_LOOP -> {
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.shuffleModeEnabled = false
        }
        PlayerPlaybackMode.PAUSE_AFTER -> {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
        }
        PlayerPlaybackMode.SHUFFLE -> {
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.shuffleModeEnabled = true
        }
    }
    val command = SessionCommand(
        VideoPlaybackService.ACTION_SET_PAUSE_AFTER,
        Bundle.EMPTY,
    )
    if (controller.isSessionCommandAvailable(command)) {
        controller.sendCustomCommand(
            command,
            bundleOf(
                VideoPlaybackService.EXTRA_PAUSE_AFTER to
                    (mode == PlayerPlaybackMode.PAUSE_AFTER),
            ),
        )
    }
}

private fun startPosition(positionMs: Long, durationMs: Long): Long {
    if (positionMs <= 0L) return 0L
    if (durationMs > 0L && positionMs >= durationMs - 1_500L) return 0L
    return positionMs
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun PlayerLoading(onBack: () -> Unit) {
    BackHandler { onBack() }
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text("正在载入视频…", color = Color.White)
        }
    }
}

@Composable
private fun PlayerLoadError(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onSkip: (() -> Unit)?,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onRetry) { Text("重试", color = Color.White) }
                if (onSkip != null) {
                    TextButton(onClick = onSkip) { Text("跳过", color = Color.White) }
                }
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        }
    }
}

@Composable
private fun PlayerPlaybackError(
    message: String,
    canSkip: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("无法播放此视频", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onRetry) { Text("重试", color = Color.White) }
                if (canSkip) {
                    TextButton(onClick = onSkip) { Text("跳过", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TopControls(
    title: String,
    onBack: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .align(Alignment.TopCenter)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                ),
            ),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Text(
            text = title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            localize = false,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "菜单", tint = Color.White)
        }
    }
}

@Composable
private fun BoxScope.BottomControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSliderStart: (Float) -> Unit,
    onSliderChange: (Float) -> Unit,
    onSliderCommit: () -> Unit,
    onRotate: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlaylist: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .align(Alignment.BottomCenter)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)),
                ),
            ),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(positionMs),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                localize = false,
            )
            Text(
                text = if (durationMs > 0L) formatTime(durationMs) else "--:--",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                localize = false,
            )
        }
        Slider(
            value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(0L).toFloat()),
            onValueChange = {
                onSliderStart(it)
                onSliderChange(it)
            },
            onValueChangeFinished = onSliderCommit,
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.32f),
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRotate) {
                Icon(Icons.Filled.ScreenRotation, contentDescription = "旋转屏幕", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一视频", tint = Color.White)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一视频", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPlaylist) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "播放列表", tint = Color.White)
            }
        }
    }
}

@Composable
private fun LockButton(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RoundIconButton(
        icon = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
        description = if (locked) "解锁" else "锁定控制",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.42f),
        modifier = modifier.size(48.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
    }
}

@Composable
private fun PlayerGestureHintView(
    hint: PlayerGestureHint,
    modifier: Modifier = Modifier,
) {
    val icon = when (hint.icon) {
        PlayerGestureIcon.PLAY -> Icons.Filled.PlayArrow
        PlayerGestureIcon.PAUSE -> Icons.Filled.Pause
        PlayerGestureIcon.SEEK_BACK -> Icons.Filled.Replay10
        PlayerGestureIcon.SEEK_FORWARD -> Icons.Filled.Forward10
        PlayerGestureIcon.BRIGHTNESS -> Icons.Filled.Brightness6
        PlayerGestureIcon.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
        PlayerGestureIcon.SPEED -> Icons.Filled.Speed
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.74f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(hint.text, color = Color.White, localize = false)
        }
    }
}

@Composable
private fun PlayerMenuDialog(
    currentSpeed: Float,
    mode: PlayerPlaybackMode,
    backgroundAllowed: Boolean,
    pipSupported: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onEnterPip: () -> Unit,
    onSpeed: () -> Unit,
    onAudio: () -> Unit,
    onMode: () -> Unit,
    onSubtitles: () -> Unit,
    onToggleBackground: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放菜单") },
        text = {
            ScrollableMenu {
                MenuAction(Icons.Filled.Share, "分享", onClick = onShare)
                if (pipSupported) {
                    MenuAction(
                        Icons.Filled.PictureInPictureAlt,
                        "画中画",
                        onClick = onEnterPip,
                    )
                }
                MenuAction(
                    Icons.Filled.Speed,
                    "播放速度",
                    trailing = "${formatSpeed(currentSpeed)}×",
                    onClick = onSpeed,
                )
                MenuAction(Icons.Filled.Audiotrack, "音轨", onClick = onAudio)
                MenuAction(
                    Icons.Filled.Repeat,
                    "播放模式",
                    trailing = mode.label,
                    onClick = onMode,
                )
                MenuAction(Icons.Filled.Subtitles, "字幕", onClick = onSubtitles)
                MenuAction(
                    if (backgroundAllowed) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "允许后台播放",
                    trailing = if (backgroundAllowed) "开" else "关",
                    onClick = onToggleBackground,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SpeedDialog(
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度") },
        text = {
            ScrollableMenu {
                speeds.forEach { speed ->
                    DialogChoice(
                        label = "${formatSpeed(speed)}×",
                        selected = abs(currentSpeed - speed) < 0.01f,
                        onClick = { onSelect(speed) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AudioTrackDialog(
    options: List<AudioTrackUi>,
    autoSelected: Boolean,
    disabled: Boolean,
    onDismiss: () -> Unit,
    onAuto: () -> Unit,
    onSelect: (AudioTrackUi) -> Unit,
    onOff: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音轨") },
        text = {
            ScrollableMenu {
                Text(
                    if (options.isEmpty()) {
                        "当前没有可用的音轨（可能是无声视频，或音轨尚未加载）。"
                    } else if (options.size == 1) {
                        "当前只有一条可用音轨；仍可手工选择或关闭声音。"
                    } else {
                        "仅列出当前媒体真实可用且受设备支持的音轨。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DialogChoice(
                    label = "自动（跟随媒体默认）",
                    selected = autoSelected,
                    onClick = onAuto,
                )
                options.forEach { option ->
                    DialogChoice(
                        label = option.label,
                        selected = option.selected,
                        onClick = { onSelect(option) },
                    )
                }
                DialogChoice(
                    label = "关闭声音",
                    selected = disabled,
                    onClick = onOff,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ModeDialog(
    selected: PlayerPlaybackMode,
    onDismiss: () -> Unit,
    onSelect: (PlayerPlaybackMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放模式") },
        text = {
            ScrollableMenu {
                PlayerPlaybackMode.entries.forEach { mode ->
                    DialogChoice(
                        label = mode.label,
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "随机播放使用 Media3 的随机顺序，在一轮列表走完前不会重复同一项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * Bounds dialog content to the short side of the current window and scrolls it, so a
 * landscape phone with hidden system bars can still reach every menu item.
 */
@Composable
private fun ScrollableMenu(content: @Composable () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 192.dp)
        .coerceAtLeast(72.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

@Composable
private fun TranslateMenu(
    showTranslation: Boolean,
    onDismiss: () -> Unit,
    onFrame: () -> Unit,
    onTranslate: () -> Unit,
    onOptions: () -> Unit,
    onEdit: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onToggleOriginal: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("画面翻译") },
        text = {
            ScrollableMenu {
                MenuAction(Icons.Filled.Translate, "翻译本画面", onClick = onFrame)
                MenuAction(Icons.Filled.PlayArrow, "开始 / 暂停翻译", onClick = onTranslate)
                MenuAction(Icons.Filled.Speed, "翻译选项", onClick = onOptions)
                MenuAction(Icons.Filled.Subtitles, "显示 / 关闭字幕", onClick = onToggleSubtitles)
                MenuAction(Icons.Filled.Subtitles, "编辑译文", onClick = onEdit)
                MenuAction(
                    Icons.Filled.Repeat,
                    if (showTranslation) "显示原文" else "显示译文",
                    onClick = onToggleOriginal,
                )
                MenuAction(Icons.Filled.ErrorOutline, "清除译文", onClick = onClear)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                localize = false,
            )
        }
    }
}

@Composable
private fun DialogChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}.0"
    } else {
        rounded.toString()
    }
}
