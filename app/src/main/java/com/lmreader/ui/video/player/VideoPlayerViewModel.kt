package com.lmreader.ui.video.player

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One playable catalog row, detached from the database entity type. */
data class VideoPlayerItem(
    val videoId: String,
    val uri: String,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val positionMs: Long,
    val accessible: Boolean,
    val bytes: Long,
    val playCount: Long,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .build(),
        )
        .build()
}

sealed interface VideoPlaylistState {
    data object Loading : VideoPlaylistState

    data class Ready(
        val items: List<VideoPlayerItem>,
        val currentId: String,
        /** Messages for other snapshot entries that could not be loaded. */
        val skipped: List<String>,
    ) : VideoPlaylistState

    data class Error(
        val message: String,
        val canSkip: Boolean,
    ) : VideoPlaylistState
}

/**
 * Owns playlist loading and the watch-session accounting for one player instance.
 *
 * The playlist is frozen once: either the small inline list or, for large libraries,
 * the persisted snapshot addressed by [playlistToken]. The snapshot is validated and
 * read on [Dispatchers.IO] before [originalIds] is populated; a missing/corrupt
 * snapshot fails closed with a visible error instead of quietly shrinking the list.
 *
 * Watch time is real elapsed time (not media-position delta), counted only while the
 * player is actually playing. Pausing, seeking, rotating and looping do not add time
 * twice. Once [VIEW_THRESHOLD_MS] or half of the duration (whichever is smaller) has
 * elapsed, `recordView` is called once per video. The counted set and elapsed map are
 * exported through the activity's saved state, so a process recreation does not
 * double-count the same session.
 */
class VideoPlayerViewModel(
    private val container: AppContainer,
    initialId: String,
    playlistIds: List<String>,
    private val playlistToken: String? = null,
    private val snapshotContext: Context? = null,
) : ViewModel() {
    private val initialId: String = initialId
    private val inlineIds: List<String> = playlistIds

    /** Filled only by [load], from the inline list or the persisted snapshot. */
    private var originalIds: List<String> = emptyList()

    private var requestedId: String = initialId

    private val loaded = LinkedHashMap<String, VideoPlayerItem>()
    private val failures = LinkedHashMap<String, String>()
    private val durations = HashMap<String, Long>()

    private val _state = MutableStateFlow<VideoPlaylistState>(VideoPlaylistState.Loading)
    val state: StateFlow<VideoPlaylistState> = _state.asStateFlow()

    private val watchedByVideo = HashMap<String, Long>()
    private val recordedVideos = LinkedHashSet<String>()
    private var loadJob: Job? = null
    private var watchVideoId: String? = null
    private var lastWatchRealtime = 0L
    private var wasPlaying = false

    init {
        load()
    }

    /** Reloads the frozen snapshot. Used by the visible Retry action. */
    fun load() {
        val requested = requestedId
        _state.value = VideoPlaylistState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ids = try {
                withContext(Dispatchers.IO) { resolveFrozenIds() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SnapshotUnavailableException) {
                _state.value = VideoPlaylistState.Error(
                    error.message ?: SNAPSHOT_ERROR,
                    canSkip = false,
                )
                return@launch
            } catch (error: Exception) {
                _state.value = VideoPlaylistState.Error(
                    error.message ?: "读取视频列表失败",
                    canSkip = false,
                )
                return@launch
            }

            originalIds = ids
            if (ids.isEmpty()) {
                _state.value = VideoPlaylistState.Error("播放列表为空", canSkip = false)
                return@launch
            }
            val target = requested.takeIf { it.isNotBlank() } ?: ids.first()
            requestedId = target

            // One bulk read; no per-id round trips. Cancellation escapes this block.
            val result = try {
                withContext(Dispatchers.IO) { loadRows(ids) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = VideoPlaylistState.Error(
                    error.message ?: "读取视频列表失败",
                    canSkip = false,
                )
                return@launch
            }

            loaded.clear()
            loaded.putAll(result.first)
            failures.clear()
            failures.putAll(result.second)
            durations.clear()
            durations.putAll(result.first.mapValues { it.value.durationMs })

            if (loaded[target] == null) {
                _state.value = VideoPlaylistState.Error(
                    message = failures[target] ?: MISSING,
                    canSkip = loaded.isNotEmpty(),
                )
            } else {
                _state.value = VideoPlaylistState.Ready(
                    items = loaded.values.toList(),
                    currentId = target,
                    skipped = failures.values.toList(),
                )
            }
        }
    }

    /**
     * Resolves the frozen ids on IO. A token that cannot be validated or read fails
     * closed; it must never fall back to just the current video or a partial list.
     */
    private fun resolveFrozenIds(): List<String> {
        val token = playlistToken
        if (token != null) {
            val context = snapshotContext ?: throw SnapshotUnavailableException()
            return VideoPlaylistSnapshots.load(context.applicationContext, token)
                ?: throw SnapshotUnavailableException()
        }
        return VideoPlaylistSnapshots.deduplicate(inlineIds, initialId)
    }

    /**
     * Bulk catalog load in snapshot order. Rows whose video no longer exists or is not
     * accessible are recorded as skipped entries, exactly as before, but only one
     * query batch set is issued.
     */
    private suspend fun loadRows(
        ids: List<String>,
    ): Pair<LinkedHashMap<String, VideoPlayerItem>, LinkedHashMap<String, String>> {
        val rowsById = container.videoCatalog.findMany(ids).associateBy { it.videoId }
        val items = LinkedHashMap<String, VideoPlayerItem>(ids.size)
        val problems = LinkedHashMap<String, String>()
        for (id in ids) {
            val entry = rowsById[id]
            if (entry == null) {
                problems[id] = MISSING
                continue
            }
            if (!entry.accessible) {
                problems[id] = entry.accessReason?.takeIf { it.isNotBlank() }
                    ?: "该视频当前不可访问"
                continue
            }
            if (entry.uri.isBlank()) {
                problems[id] = "视频地址为空"
                continue
            }
            items[id] = VideoPlayerItem(
                videoId = entry.videoId,
                uri = entry.uri,
                name = entry.name.ifBlank { "未命名视频" },
                durationMs = entry.durationMs,
                width = entry.width,
                height = entry.height,
                rotation = entry.rotation,
                positionMs = entry.positionMs,
                accessible = entry.accessible,
                bytes = entry.bytes,
                playCount = entry.playCount,
            )
        }
        return items to problems
    }

    /**
     * Moves past a snapshot entry that could not be opened. This is intentionally
     * explicit: a broken current item must not silently leave the previous video's
     * frame/translations visible.
     */
    fun skipCurrent() {
        val valid = loaded.values.toList()
        if (valid.isEmpty()) {
            _state.value = VideoPlaylistState.Error("没有可播放的视频", canSkip = false)
            return
        }
        val at = originalIds.indexOf(requestedId)
        val nextId = originalIds.drop((at + 1).coerceAtLeast(0))
            .firstOrNull { loaded.containsKey(it) }
            ?: originalIds.take(at.coerceAtLeast(0))
                .lastOrNull { loaded.containsKey(it) }
            ?: valid.first().videoId
        requestedId = nextId
        _state.value = VideoPlaylistState.Ready(
            items = valid,
            currentId = nextId,
            skipped = failures.values.toList(),
        )
    }

    fun itemFor(id: String?): VideoPlayerItem? = id?.let(loaded::get)

    fun currentRequestedId(): String = requestedId

    /** Reads the freshest saved position before selecting a playlist item. */
    suspend fun freshPosition(id: String): Long = withContext(Dispatchers.IO) {
        try {
            container.videoCatalog.find(id)?.positionMs ?: 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Adds the real interval since the previous call when playback was active. Safe to
     * call on every player event and every UI tick.
     */
    fun refreshWatch(player: Player, durationOverride: Long? = null) {
        val now = SystemClock.elapsedRealtime()
        val playerId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (watchVideoId == null) {
            watchVideoId = playerId ?: return
            lastWatchRealtime = now
            wasPlaying = player.isPlaying
            return
        }
        val id = watchVideoId ?: return
        if (playerId != null && playerId != id) {
            onMediaItemTransition(player, playerId)
            return
        }
        if (lastWatchRealtime == 0L) lastWatchRealtime = now
        val elapsed = (now - lastWatchRealtime).coerceAtLeast(0L)
        lastWatchRealtime = now
        if (wasPlaying && elapsed > 0L) {
            val total = (watchedByVideo[id] ?: 0L) + elapsed
            watchedByVideo[id] = total
            maybeRecord(id, durationOverride ?: durationFor(id, player), total)
        }
        wasPlaying = player.isPlaying
    }

    /** Adds the final segment to the old item before switching to [newId]. */
    fun onMediaItemTransition(player: Player, newId: String?) {
        val now = SystemClock.elapsedRealtime()
        val oldId = watchVideoId
        if (oldId != null && wasPlaying) {
            val elapsed = (now - lastWatchRealtime).coerceAtLeast(0L)
            if (elapsed > 0L) {
                val total = (watchedByVideo[oldId] ?: 0L) + elapsed
                watchedByVideo[oldId] = total
                maybeRecord(oldId, durationFor(oldId, player), total)
            }
        }
        watchVideoId = newId
        lastWatchRealtime = now
        wasPlaying = player.isPlaying
    }

    private fun durationFor(id: String, player: Player): Long {
        if (id == player.currentMediaItem?.mediaId) {
            val current = player.duration
            if (current > 0L) return current
        }
        return loaded[id]?.durationMs ?: durations[id] ?: 0L
    }

    private fun maybeRecord(id: String, durationMs: Long, watchedMs: Long) {
        if (id in recordedVideos) return
        val threshold = if (durationMs > 0L) {
            minOf(VIEW_THRESHOLD_MS, (durationMs / 2L).coerceAtLeast(1L))
        } else {
            VIEW_THRESHOLD_MS
        }
        if (watchedMs < threshold) return
        recordedVideos += id
        container.backgroundScope.launch(Dispatchers.IO) {
            runCatching { container.videoCatalog.recordView(id) }
        }
    }

    fun exportState(): Bundle = Bundle().apply {
        putStringArrayList(STATE_WATCH_IDS, ArrayList(watchedByVideo.keys))
        putLongArray(STATE_WATCH_MS, watchedByVideo.values.toLongArray())
        putStringArrayList(STATE_RECORDED, ArrayList(recordedVideos))
    }

    fun restoreState(bundle: Bundle?) {
        if (bundle == null) return
        val ids = bundle.getStringArrayList(STATE_WATCH_IDS).orEmpty()
        val values = bundle.getLongArray(STATE_WATCH_MS) ?: longArrayOf()
        for (index in ids.indices) {
            if (index < values.size) watchedByVideo[ids[index]] = values[index].coerceAtLeast(0L)
        }
        bundle.getStringArrayList(STATE_RECORDED)?.let { recordedVideos.addAll(it) }
        // Realtime is not meaningful across process death; start a fresh segment when
        // the new player reports its first playing state.
        watchVideoId = null
        lastWatchRealtime = 0L
        wasPlaying = false
    }

    private class SnapshotUnavailableException : Exception(SNAPSHOT_ERROR)

    companion object {
        private const val VIEW_THRESHOLD_MS = 30_000L
        private const val STATE_WATCH_IDS = "watched_ids"
        private const val STATE_WATCH_MS = "watched_ms"
        private const val STATE_RECORDED = "recorded_views"
        private const val MISSING = "视频库中找不到该视频"
        private const val SNAPSHOT_ERROR =
            "播放列表快照缺失或损坏，无法还原本次播放列表。请返回后重新打开视频。"

        /** Backward-compatible small-list factory. */
        fun factory(
            container: AppContainer,
            videoId: String,
            playlistIds: List<String>,
        ): ViewModelProvider.Factory = factory(container, videoId, playlistIds, null, null)

        fun factory(
            container: AppContainer,
            videoId: String,
            playlistIds: List<String>,
            playlistToken: String?,
            snapshotContext: Context?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VideoPlayerViewModel(
                    container,
                    videoId,
                    playlistIds,
                    playlistToken,
                    snapshotContext,
                )
            }
        }
    }
}