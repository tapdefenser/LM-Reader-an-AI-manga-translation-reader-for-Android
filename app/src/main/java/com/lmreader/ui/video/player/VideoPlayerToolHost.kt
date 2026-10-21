package com.lmreader.ui.video.player

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import com.lmreader.di.AppContainer

/**
 * Typed hand-off from the R3 player to the app-owned translation/subtitle workspace.
 *
 * The player deliberately never captures translated pixels. The root installs a
 * [VideoPlayerToolHost] in [VideoPlayerToolRegistry] before opening the player; the
 * host owns frame capture/analysis, saved-frame overlays, subtitle rendering and the
 * editors. If no host is installed every translation/subtitle entry point shows a
 * visible "unavailable" notice instead of pretending to work.
 *
 * The request is intentionally a snapshot. `positionMs` is the real player position
 * at the moment the menu item was pressed, not the position when the host starts.
 */
data class VideoPlayerToolRequest(
    val videoId: String,
    val positionMs: Long,
    val action: String,
    val presentationTimeUs: Long? = VideoFrameStamp.capture(videoId, positionMs),
)

/**
 * Public host contract. Implementations must be stateless callbacks: the player calls
 * [Content] on every recomposition, including when [request] is null. A null request is
 * the normal overlay slot (mask/subtitles); a non-null request is a one-shot action.
 *
 * The exact signature is part of the player API:
 *
 * ```
 * @Composable
 * fun Content(
 *     container: AppContainer,
 *     videoId: String,
 *     positionMs: Long,
 *     player: Player,
 *     request: VideoPlayerToolRequest?,
 *     showTranslation: Boolean,
 *     onDismiss: () -> Unit,
 * )
 * ```
 */
interface VideoPlayerToolHost {
    @Composable
    fun Content(
        container: AppContainer,
        videoId: String,
        positionMs: Long,
        player: Player,
        request: VideoPlayerToolRequest?,
        showTranslation: Boolean,
        onDismiss: () -> Unit,
    )
}

/**
 * Process-wide install point. Root installs the host before `VideoPlayerActivity.open`
 * is called. A null host is a supported standalone state: the player still plays and
 * every translation/subtitle action reports that the tooling is unavailable.
 */
object VideoPlayerToolRegistry {
    @Volatile
    var host: VideoPlayerToolHost? = null
}

/** The only action strings the player sends in [VideoPlayerToolRequest.action]. */
object VideoPlayerToolActions {
    const val FRAME = "FRAME"
    const val TRANSLATE = "TRANSLATE"
    const val OPTIONS = "OPTIONS"
    const val EDIT = "EDIT"
    const val CLEAR = "CLEAR"
    const val SUBTITLES = "SUBTITLES"

    val all: Set<String> = setOf(FRAME, TRANSLATE, OPTIONS, EDIT, CLEAR, SUBTITLES)
}

/**
 * Shared preferences used by the player. The name and key are stable because the
 * media service reads the remembered speed before any activity can connect.
 */
object VideoPlayerPreferences {
    const val NAME = "video-player"
    const val KEY_SPEED = "speed"
    const val KEY_BACKGROUND_PLAYBACK = "background_playback"
}
