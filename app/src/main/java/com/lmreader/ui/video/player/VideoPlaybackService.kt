package com.lmreader.ui.video.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lmreader.di.AppContainer
import com.lmreader.ui.video.catalog.VideoCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the only ExoPlayer instance used by the R3 video player.
 *
 * The player is created here, never in the activity, so notifications and hardware
 * media buttons keep working when the activity is recreated or backgrounded. The
 * activity talks to it through a `MediaController` built from a `SessionToken`.
 *
 * Audio focus and headphone unplug handling are delegated to ExoPlayer
 * (`setAudioAttributes(..., handleAudioFocus = true)` and
 * `setHandleAudioBecomingNoisy(true)`). Tracks, speed, repeat and shuffle are all
 * controlled through the MediaSession, so the notification and in-app controls observe
 * the same state.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var resourceLease: com.lmreader.core.model.ResourceGovernor.Lease? = null
    private lateinit var catalog: VideoCatalogRepository

    private var pauseAfterCurrent = false
    private var lastSavedMediaId: String? = null
    private var lastSavedPositionMs = -1L
    @Volatile private var presentedVideoId = ""

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(com.lmreader.core.model.TaskContext("playback", 0, 1000), start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            resourceLease = com.lmreader.core.model.ResourceGovernor.shared.acquire(mapOf("playback" to 1L))
            createSession()
        }
    }
    private fun createSession() {
        catalog = AppContainer.from(this).videoCatalog

        val speed = getSharedPreferences(VideoPlayerPreferences.NAME, Context.MODE_PRIVATE)
            .getFloat(VideoPlayerPreferences.KEY_SPEED, 1f)
            .coerceIn(MIN_SPEED, MAX_SPEED)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = false
                setPlaybackSpeed(speed)
                addListener(playbackListener)
            }
        player = exoPlayer
        exoPlayer.setVideoFrameMetadataListener { ptsUs, _, _, _ -> VideoFrameStamp.update(presentedVideoId, ptsUs) }
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                presentedVideoId = mediaItem?.mediaId.orEmpty(); VideoFrameStamp.clear()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if(reason == Player.DISCONTINUITY_REASON_SEEK) VideoFrameStamp.clear()
            }
        })

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(sessionCallback)
            .build()

        ticker = serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPosition(force = true)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Preserve the last position even when the system removes the task.
        saveCurrentPositionBlocking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveCurrentPositionBlocking()
        ticker?.cancel()
        ticker = null
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.release()
        player = null
        resourceLease?.close(); resourceLease = null
        super.onDestroy()
    }

    private val sessionCallback = object : MediaSession.Callback {
        /**
         * The default command set plus one private command used to tell the service
         * that "play then pause" is active. The mode is implemented in the service so
         * it also works when the activity is not alive (PiP/background playback).
         */
        @Suppress("DEPRECATION")
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_SET_PAUSE_AFTER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SET_PAUSE_AFTER) {
                pauseAfterCurrent = args.getBoolean(EXTRA_PAUSE_AFTER, false)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private val playbackListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (pauseAfterCurrent && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Repeat is OFF in this mode. Pausing here means the next item is
                // loaded and paused instead of playing over the user's stop point.
                player?.pause()
            }
            updateSessionActivity()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val oldId = oldPosition.mediaItem?.mediaId
            if (!oldId.isNullOrBlank() && oldId != newPosition.mediaItem?.mediaId) {
                savePositionAsync(oldId, oldPosition.positionMs)
            }
            updateSessionActivity()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                serviceScope.launch { saveCurrentPosition(force = true) }
            }
            updateSessionActivity()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                serviceScope.launch { saveCurrentPosition(force = true) }
            }
            updateSessionActivity()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updateSessionActivity()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateSessionActivity()
        }
    }

    private fun updateSessionActivity() {
        val session = mediaSession ?: return
        val pendingIntent = createSessionActivity() ?: return
        runCatching { session.setSessionActivity(pendingIntent) }
    }

    private fun createSessionActivity(): PendingIntent? {
        val current = player?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return null
        val playlist = player?.let { p ->
            (0 until p.mediaItemCount)
                .map { p.getMediaItemAt(it).mediaId }
                .filter { it.isNotBlank() }
        }.orEmpty()
        if (playlist.isEmpty()) return null
        val intent = VideoPlayerActivity.openIntent(this, current, playlist)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun savePositionAsync(mediaId: String?, positionMs: Long) {
        if (mediaId.isNullOrBlank() || positionMs < 0L) return
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { catalog.savePosition(mediaId, positionMs) }
            }
        }
    }

    private suspend fun saveCurrentPosition(force: Boolean) {
        val p = player ?: return
        val mediaId = p.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val positionMs = p.currentPosition
        if (positionMs < 0L) return
        if (!force && mediaId == lastSavedMediaId && positionMs == lastSavedPositionMs) return
        lastSavedMediaId = mediaId
        lastSavedPositionMs = positionMs
        withContext(Dispatchers.IO) {
            runCatching { catalog.savePosition(mediaId, positionMs) }
        }
    }

    private fun saveCurrentPositionBlocking() {
        if (!::catalog.isInitialized) return
        val p = player ?: return
        val mediaId = p.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val positionMs = p.currentPosition
        if (positionMs < 0L) return
        runBlocking {
            withTimeoutOrNull(1_500L) {
                withContext(Dispatchers.IO) {
                    runCatching { catalog.savePosition(mediaId, positionMs) }
                }
            }
        }
    }

    companion object {
        /** Private MediaSession command; only carries the pause-after mode bit. */
        const val ACTION_SET_PAUSE_AFTER = "com.lmreader.video.player.SET_PAUSE_AFTER"
        const val EXTRA_PAUSE_AFTER = "pause_after"

        private const val SESSION_ACTIVITY_REQUEST_CODE = 7411
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 4f
    }
}
