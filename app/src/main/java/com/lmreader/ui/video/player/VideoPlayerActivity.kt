package com.lmreader.ui.video.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.UiTextTranslations
import com.lmreader.ui.theme.LmReaderTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen R3 video player.
 *
 * The activity owns only UI/lifecycle concerns. Playback and the MediaSession live in
 * [VideoPlaybackService]; a [MediaController] is built asynchronously from a
 * [SessionToken]. The player is attached to a controller-free `PlayerView` (FIT mode)
 * and a separate Compose layer draws the transparent controls, gestures and the host
 * integration slot. No translated pixels are ever captured here.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerActivity : ComponentActivity() {
    private val container by lazy { AppContainer.from(this) }
    private var initialVideoId: String = ""
    private var playlistIds: List<String> = emptyList()
    private var playlistToken: String? = null

    private val playerViewModel: VideoPlayerViewModel by viewModels {
        VideoPlayerViewModel.factory(container, initialVideoId, playlistIds, playlistToken, applicationContext)
    }

    private val controllerState = mutableStateOf<MediaController?>(null)
    private val playbackReady = mutableStateOf(false)
    private val controllerError = mutableStateOf<String?>(null)
    private val inPipState = mutableStateOf(false)
    private val startedState = mutableStateOf(false)
    private val rotationGeneration = mutableIntStateOf(0)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val destroyed = AtomicBoolean(false)
    private var playbackListenerAttached = false
    private var lastPositionSaveAt = 0L
    private var originalScreenBrightness = -1f
    private var temporarySpeedActive = false
    private var speedBeforeTemporary = 1f
    private var pipReceiverRegistered = false
    private var pipEntering = false

    private val audioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            newBase.createConfigurationContext(
                Configuration(newBase.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(GeneralPreferences(newBase).effectiveLanguageTag))
                },
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        originalScreenBrightness = window.attributes.screenBrightness

        initialVideoId = savedInstanceState?.getString(STATE_CURRENT_ID)
            ?: intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        // Large playlists are represented by an opaque token only. The token is saved
        // in the instance state so rotation/process recreation reloads the same file.
        playlistToken = savedInstanceState?.getString(STATE_PLAYLIST_TOKEN)
            ?: intent.getStringExtra(EXTRA_PLAYLIST_TOKEN)
        playlistIds = (
            savedInstanceState?.getStringArrayList(STATE_PLAYLIST)
                ?: intent.getStringArrayListExtra(EXTRA_PLAYLIST_IDS)
                ?: emptyList()
            ).filter { it.isNotBlank() }.distinct()
        if (initialVideoId.isBlank()) initialVideoId = playlistIds.firstOrNull().orEmpty()

        playerViewModel.restoreState(savedInstanceState?.getBundle(STATE_WATCH))
        configureWindow()
        registerPipReceiver()
        connectController()
        observePlaylist()
        startPositionTicker()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            LmReaderTheme(darkTheme = true) {
                VideoPlayerScreen(
                    container = container,
                    viewModel = playerViewModel,
                    controller = controllerState.value,
                    playbackReady = playbackReady.value,
                    controllerError = controllerError.value,
                    inPip = inPipState.value,
                    active = startedState.value || inPipState.value || pipEntering,
                    rotationGeneration = rotationGeneration.intValue,
                    actions = VideoPlayerWindowActions(
                        onBack = ::finishPlayer,
                        onRetryController = ::retryController,
                        onSavePosition = { saveCurrentPosition() },
                        onEnterPip = ::enterPip,
                        onRotate = ::toggleOrientation,
                        onTemporarySpeedStart = ::beginTemporarySpeed,
                        onTemporarySpeedEnd = ::endTemporarySpeed,
                        onBrightnessChange = ::setWindowBrightness,
                        currentBrightness = ::currentWindowBrightness,
                        onVolumeChange = ::setMediaVolume,
                        currentVolume = { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) },
                        maxVolume = { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode && !inPipState.value) pipEntering = false
        // If another player activity used the shared service in the meantime,
        // returning to this one must re-apply this activity's frozen playlist.
        val state = playerViewModel.state.value
        val player = controller
        if (player != null && state is VideoPlaylistState.Ready) {
            val current = player.currentMediaItem?.mediaId
            if (!playbackReady.value || state.items.none { it.videoId == current }) {
                playbackReady.value = applyPlaylistIfNeeded(state)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The service already owns the playlist when it sends a session-activity
        // intent. The controller is the source of truth for the current item.
    }


    override fun onStart() {
        super.onStart()
        // Avoid showing another activity's current frame for one composition while
        // onResume re-applies this activity's frozen playlist.
        val state = playerViewModel.state.value
        val player = controller
        if (playbackReady.value && player != null && state is VideoPlaylistState.Ready &&
            state.items.none { it.videoId == player.currentMediaItem?.mediaId }
        ) {
            playbackReady.value = false
        }
        startedPlayerActivities += 1
        startedState.value = true
    }

    override fun onPause() {
        saveCurrentPosition()
        super.onPause()
    }

    override fun onStop() {
        startedPlayerActivities = (startedPlayerActivities - 1).coerceAtLeast(0)
        startedState.value = false
        if (startedPlayerActivities == 0) {
            endTemporarySpeed()
        } else {
            // The newer player owns the shared speed from here on.
            temporarySpeedActive = false
        }
        if (startedPlayerActivities == 0) {
            saveCurrentPosition()
        }
        if (startedPlayerActivities == 0) {
            controller?.let { player ->
                if (playbackReady.value) playerViewModel.refreshWatch(player)
            }
        }
        val allowBackground = getSharedPreferences(VideoPlayerPreferences.NAME, Context.MODE_PRIVATE)
            .getBoolean(VideoPlayerPreferences.KEY_BACKGROUND_PLAYBACK, false)
        if (startedPlayerActivities == 0 && !isChangingConfigurations &&
            !isInPictureInPictureMode && !pipEntering && !allowBackground
        ) {
            controller?.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        destroyed.set(true)
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverRegistered = false
        }
        val connected = controller
        connected?.let { player ->
            if (playbackListenerAttached) player.removeListener(playbackListener)
        }
        playbackListenerAttached = false
        controllerState.value = null
        controller = null
        connected?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        val attributes = window.attributes
        attributes.screenBrightness = originalScreenBrightness
        window.attributes = attributes
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val token = playlistToken
        if (token != null) {
            outState.putString(STATE_PLAYLIST_TOKEN, token)
        } else {
            outState.putStringArrayList(STATE_PLAYLIST, ArrayList(playlistIds))
        }
        outState.putString(STATE_CURRENT_ID, currentVideoId() ?: initialVideoId)
        outState.putBundle(STATE_WATCH, playerViewModel.exportState())
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        endTemporarySpeed()
        saveCurrentPosition()
        rotationGeneration.intValue += 1
        updatePipParams(controller)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipEntering = false
        inPipState.value = isInPictureInPictureMode
        endTemporarySpeed()
        if (isInPictureInPictureMode) saveCurrentPosition()
        updatePipParams(controller)
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun connectController() {
        if (destroyed.get() || controller != null || controllerFuture != null) return
        controllerError.value = null
        val token = SessionToken(
            this,
            ComponentName(this, VideoPlaybackService::class.java),
        )
        val future = MediaController.Builder(this, token)
            .setListener(
                object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (destroyed.get()) return
                        if (this@VideoPlayerActivity.controller !== controller) return
                        this@VideoPlayerActivity.controller = null
                        controllerState.value = null
                        playbackReady.value = false
                        controllerError.value = "播放器服务已断开"
                    }
                },
            )
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (destroyed.get()) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    controllerFuture = null
                    controllerError.value = "无法连接播放器服务"
                    return@addListener
                }
                controllerFuture = null
                controller = connected
                controllerState.value = connected
                controllerError.value = null
                connected.addListener(playbackListener)
                playbackListenerAttached = true
                val state = playerViewModel.state.value
                if (state is VideoPlaylistState.Ready) {
                    playbackReady.value = applyPlaylistIfNeeded(state)
                }
                updatePipParams(connected)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun retryController() {
        if (destroyed.get()) return
        controllerError.value = null
        playbackReady.value = false
        val old = controller
        controller = null
        controllerState.value = null
        if (playbackListenerAttached) old?.removeListener(playbackListener)
        playbackListenerAttached = false
        old?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        connectController()
    }

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = controller ?: return
            if (playbackReady.value && shouldTrackWatch()) playerViewModel.refreshWatch(player)
            if (!isPlaying) saveCurrentPosition(player)
            updatePipParams(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = controller ?: return
            if (playbackReady.value && shouldTrackWatch()) {
                playerViewModel.onMediaItemTransition(player, mediaItem?.mediaId)
            }
            updatePipParams(player)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val player = controller ?: return
            if (playbackReady.value && shouldTrackWatch()) playerViewModel.refreshWatch(player)
            updatePipParams(player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = controller ?: return
            if (playbackState == Player.STATE_ENDED) {
                saveCurrentPosition(player)
                if (playbackReady.value && shouldTrackWatch()) playerViewModel.refreshWatch(player)
            }
            updatePipParams(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = controller ?: return
            saveCurrentPosition(player)
            if (playbackReady.value && shouldTrackWatch()) playerViewModel.refreshWatch(player)
            updatePipParams(player)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updatePipParams(controller)
        }
    }

    private fun observePlaylist() {
        lifecycleScope.launch {
            playerViewModel.state.collect { state ->
                when (state) {
                    VideoPlaylistState.Loading -> {
                        playbackReady.value = false
                        val playing = controller
                        if (playing != null &&
                            playing.currentMediaItem?.mediaId != playerViewModel.currentRequestedId()
                        ) {
                            playing.pause()
                        }
                    }
                    is VideoPlaylistState.Ready -> {
                        playbackReady.value = applyPlaylistIfNeeded(state)
                    }
                    is VideoPlaylistState.Error -> {
                        playbackReady.value = false
                        val player = controller
                        if (player != null) {
                            saveCurrentPosition(player)
                            player.pause()
                            runCatching { player.clearMediaItems() }
                        }
                    }
                }
            }
        }
    }

    private fun applyPlaylistIfNeeded(state: VideoPlaylistState.Ready): Boolean {
        val player = controller ?: return false
        val items = state.items
        if (items.isEmpty()) return false
        val targetIndex = items.indexOfFirst { it.videoId == state.currentId }
            .takeIf { it >= 0 }
            ?: 0

        val samePlaylist = player.mediaItemCount == items.size &&
            items.indices.all { index ->
                player.getMediaItemAt(index).mediaId == items[index].videoId
            }
        if (samePlaylist) {
            if (player.currentMediaItem?.mediaId != state.currentId) {
                player.seekTo(targetIndex, startPosition(items[targetIndex]))
            }
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            playbackReady.value = true
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()
            return true
        }

        // Preserve progress of the previous session/video before replacing its
        // playlist. The view is not attached until apply returns, so no old frame is
        // shown for the new request.
        saveCurrentPosition(player)
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
        player.setMediaItems(
            items.map(VideoPlayerItem::toMediaItem),
            targetIndex,
            startPosition(items[targetIndex]),
        )
        player.prepare()
        playbackReady.value = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()
        return true
    }

    private fun startPosition(item: VideoPlayerItem): Long {
        val position = item.positionMs
        if (position <= 0L) return 0L
        if (item.durationMs > 0L && position >= item.durationMs - 1_500L) return 0L
        return position
    }

    private fun shouldTrackWatch(): Boolean =
        startedPlayerActivities <= 1 &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun startPositionTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val player = controller
                    if (player != null) {
                        if (playbackReady.value && shouldTrackWatch()) {
                            playerViewModel.refreshWatch(player)
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (playbackReady.value && now - lastPositionSaveAt >= POSITION_SAVE_INTERVAL_MS) {
                            lastPositionSaveAt = now
                            saveCurrentPosition(player)
                        }
                    }
                    delay(1_000L)
                }
            }
        }
    }

    private fun saveCurrentPosition(player: MediaController? = controller) {
        val connected = player ?: return
        val mediaId = connected.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val position = connected.currentPosition
        if (position < 0L) return
        container.backgroundScope.launch(Dispatchers.IO) {
            runCatching { container.videoCatalog.savePosition(mediaId, position) }
        }
    }

    private fun finishPlayer() {
        saveCurrentPosition()
        controller?.let { player ->
            if (playbackReady.value && shouldTrackWatch()) playerViewModel.refreshWatch(player)
        }
        finish()
    }

    private fun currentVideoId(): String? =
        controller?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: playerViewModel.currentRequestedId()

    private fun beginTemporarySpeed() {
        val player = controller ?: return
        if (temporarySpeedActive) return
        speedBeforeTemporary = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        temporarySpeedActive = true
        runCatching { player.setPlaybackSpeed(2f) }
    }

    private fun endTemporarySpeed() {
        if (!temporarySpeedActive) return
        temporarySpeedActive = false
        runCatching { controller?.setPlaybackSpeed(speedBeforeTemporary) }
    }

    private fun setWindowBrightness(value: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attributes
    }

    private fun currentWindowBrightness(): Float =
        window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f

    private fun setMediaVolume(value: Int) {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            value.coerceIn(0, maximum),
            0,
        )
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val player = controller ?: return
        endTemporarySpeed()
        pipEntering = true
        val entered = runCatching {
            enterPictureInPictureMode(buildPipParams(player))
        }.getOrDefault(false)
        if (!entered) pipEntering = false
    }

    private fun updatePipParams(player: Player?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (player == null || !isInPictureInPictureMode) return
        runCatching { setPictureInPictureParams(buildPipParams(player)) }
    }

    private fun buildPipParams(player: Player): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        aspectRatio(player)?.let { builder.setAspectRatio(it) }
        builder.setActions(listOf(pipAction(player.isPlaying)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun aspectRatio(player: Player): Rational? {
        val videoSize = player.videoSize
        var width = videoSize.width
        var height = videoSize.height
        var pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        if (width <= 0 || height <= 0) {
            val item = playerViewModel.itemFor(player.currentMediaItem?.mediaId)
            width = item?.width ?: 0
            height = item?.height ?: 0
            pixelRatio = 1f
            if (width > 0 && height > 0 && (item?.rotation ?: 0) % 180 != 0) {
                val swap = width
                width = height
                height = swap
            }
        } else if (videoSize.unappliedRotationDegrees % 180 != 0) {
            val swap = width
            width = height
            height = swap
        }
        if (width <= 0 || height <= 0) return null
        val ratio = (width * pixelRatio / height).coerceIn(1f / 2.39f, 2.39f)
        return Rational((ratio * 1_000f).roundToInt().coerceAtLeast(1), 1_000)
    }

    private fun pipAction(isPlaying: Boolean): RemoteAction {
        val label = UiTextTranslations.translate(this, if (isPlaying) "暂停" else "播放")
        val icon = Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val intent = Intent(ACTION_PIP_TOGGLE).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            PIP_ACTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(icon, label, label, pendingIntent)
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_TOGGLE) return
            val player = controller ?: return
            if (player.isPlaying) player.pause() else player.play()
            updatePipParams(player)
        }
    }

    private fun registerPipReceiver() {
        if (pipReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter(ACTION_PIP_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pipReceiverRegistered = true
    }

    companion object {
        const val EXTRA_VIDEO_ID = "com.lmreader.video.player.VIDEO_ID"
        const val EXTRA_PLAYLIST_IDS = "com.lmreader.video.player.PLAYLIST_IDS"

        /** Opaque UUID token used instead of [EXTRA_PLAYLIST_IDS] above the inline limit. */
        const val EXTRA_PLAYLIST_TOKEN = "com.lmreader.video.player.PLAYLIST_TOKEN"

        private const val STATE_CURRENT_ID = "video_player_current_id"
        private const val STATE_PLAYLIST = "video_player_playlist"

        /** Saved-state token; only this crosses the Bundle for large playlists. */
        const val STATE_PLAYLIST_TOKEN = "video_player_playlist_token"
        private const val STATE_WATCH = "video_player_watch"
        private const val ACTION_PIP_TOGGLE = "com.lmreader.video.player.PIP_TOGGLE"
        private const val PIP_ACTION_REQUEST_CODE = 7412
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        /** Main-thread count; onStop of a covered player must not pause the new one. */
        private var startedPlayerActivities = 0

        /**
         * Opens the player for [id] with a frozen copy of [playlistIds]. The current
         * id is inserted if the caller did not include it. No later mutation of the
         * caller's list can change this session's previous/next order.
         */
        fun open(context: Context, id: String, playlistIds: List<String>) {
            val intent = openIntent(context, id, playlistIds)
            if (context !is ComponentActivity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** Intent form used by both [open] and the MediaSession notification. */
        fun openIntent(context: Context, id: String, playlistIds: List<String>): Intent {
            // LinkedHashSet keeps the caller's frozen order and removes the old O(n²)
            // contains scan. The current id is appended exactly once, if absent.
            val snapshot = VideoPlaylistSnapshots.deduplicate(playlistIds, id)
            val intent = Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, id)
            if (snapshot.size <= VideoPlaylistSnapshots.MAX_INLINE_IDS) {
                intent.putStringArrayListExtra(EXTRA_PLAYLIST_IDS, ArrayList(snapshot))
            } else {
                // The file write runs on the snapshot IO thread. Only the opaque token
                // travels through Binder and the Activity saved-state Bundle.
                intent.putExtra(
                    EXTRA_PLAYLIST_TOKEN,
                    VideoPlaylistSnapshots.persistBlocking(context, snapshot),
                )
            }
            return intent
        }
    }
}
