package com.lmreader.ui.video.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PlayerGestureIcon {
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    BRIGHTNESS,
    VOLUME,
    SPEED,
}

internal data class PlayerGestureHint(
    val icon: PlayerGestureIcon,
    val text: String,
    val persistent: Boolean = false,
)

/**
 * The single gesture recognizer for the video surface. It owns tap/double-tap,
 * long-press temporary speed, horizontal seek preview, left brightness and right
 * volume. Axis is locked after touch slop so a vertical volume drag can never seek.
 *
 * The layer that carries this modifier must stay below the Compose controls. Buttons,
 * the progress bar and the host editor therefore win hit testing before this
 * recognizer. The recognizer itself also ignores the system gesture edge and does
 * nothing while the player is locked.
 */
@Composable
internal fun Modifier.playerGestures(
    player: Player?,
    enabled: Boolean,
    onTap: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekPreview: (targetMs: Long, deltaMs: Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onTemporarySpeedStart: () -> Unit,
    onTemporarySpeedEnd: () -> Unit,
    onHint: (PlayerGestureHint?) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    currentBrightness: () -> Float,
    onVolumeChange: (Int) -> Unit,
    currentVolume: () -> Int,
    maxVolume: () -> Int,
): Modifier {
    val callbacks = rememberUpdatedState(
        GestureCallbacks(
            onTap = onTap,
            onTogglePlayback = onTogglePlayback,
            onSeekBy = onSeekBy,
            onSeekPreview = onSeekPreview,
            onSeekCommit = onSeekCommit,
            onTemporarySpeedStart = onTemporarySpeedStart,
            onTemporarySpeedEnd = onTemporarySpeedEnd,
            onHint = onHint,
            onBrightnessChange = onBrightnessChange,
            currentBrightness = currentBrightness,
            onVolumeChange = onVolumeChange,
            currentVolume = currentVolume,
            maxVolume = maxVolume,
        ),
    )
    return pointerInput(player, enabled) {
        if (!enabled || player == null) return@pointerInput
        val edgePx = 24.dp.toPx()
        var lastTapTime = 0L
        var lastTapZone = ZONE_NONE

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edgePx || down.position.y < edgePx ||
                down.position.x > size.width - edgePx || down.position.y > size.height - edgePx
            ) {
                return@awaitEachGesture
            }

            val width = size.width.toFloat().coerceAtLeast(1f)
            val height = size.height.toFloat().coerceAtLeast(1f)
            val startPosition = player.currentPosition.coerceAtLeast(0L)
            val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            var axis = AXIS_NONE
            var vertical = VERTICAL_NONE
            var startBrightness = 0.5f
            var startVolume = 0
            var maxVolumeValue = 1
            var seekTarget = -1L
            var longPress = false
            var tapCandidate = true
            var finished = false

            try {
                while (!finished) {
                    val timeout = if (axis == AXIS_NONE && !longPress) {
                        (longPressDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                    } else {
                        250L
                    }
                    val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }
                    if (event == null) {
                        if (axis == AXIS_NONE && !longPress) {
                            longPress = true
                            tapCandidate = false
                            callbacks.value.onTemporarySpeedStart()
                            callbacks.value.onHint(
                                PlayerGestureHint(
                                    icon = PlayerGestureIcon.SPEED,
                                    text = "2× 临时倍速",
                                    persistent = true,
                                ),
                            )
                        }
                        continue
                    }

                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        finished = true
                        break
                    }
                    val total = change.position - down.position

                    if (axis == AXIS_NONE && !longPress &&
                        SystemClock.uptimeMillis() >= longPressDeadline
                    ) {
                        longPress = true
                        tapCandidate = false
                        callbacks.value.onTemporarySpeedStart()
                        callbacks.value.onHint(
                            PlayerGestureHint(
                                icon = PlayerGestureIcon.SPEED,
                                text = "2× 临时倍速",
                                persistent = true,
                            ),
                        )
                    }

                    if (axis == AXIS_NONE && !longPress) {
                        if (total.getDistance() <= viewConfiguration.touchSlop) continue
                        tapCandidate = false
                        axis = if (abs(total.x) >= abs(total.y)) AXIS_HORIZONTAL else AXIS_VERTICAL
                        if (axis == AXIS_VERTICAL) {
                            vertical = if (down.position.x < width / 2f) {
                                VERTICAL_BRIGHTNESS
                            } else {
                                VERTICAL_VOLUME
                            }
                            if (vertical == VERTICAL_BRIGHTNESS) {
                                startBrightness = callbacks.value.currentBrightness().coerceIn(0.01f, 1f)
                            } else {
                                startVolume = callbacks.value.currentVolume()
                                maxVolumeValue = callbacks.value.maxVolume().coerceAtLeast(1)
                            }
                        }
                    }

                    if (longPress) continue

                    if (axis == AXIS_HORIZONTAL) {
                        val duration = player.duration
                        if (duration > 0L) {
                            val delta = (total.x / width) * duration * HORIZONTAL_SEEK_FRACTION
                            val target = (startPosition + delta)
                                .roundToLong()
                                .coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
                            seekTarget = target
                            callbacks.value.onSeekPreview(target, target - startPosition)
                        }
                        change.consume()
                    } else if (axis == AXIS_VERTICAL) {
                        val fraction = (-total.y / height).coerceIn(-1f, 1f)
                        if (vertical == VERTICAL_BRIGHTNESS) {
                            callbacks.value.onBrightnessChange(
                                (startBrightness + fraction).coerceIn(0.01f, 1f),
                            )
                        } else {
                            callbacks.value.onVolumeChange(
                                (startVolume + fraction * maxVolumeValue)
                                    .roundToLong()
                                    .coerceIn(0L, maxVolumeValue.toLong())
                                    .toInt(),
                            )
                        }
                        change.consume()
                    }
                }
            } finally {
                if (longPress) {
                    callbacks.value.onTemporarySpeedEnd()
                    callbacks.value.onHint(null)
                }
            }

            if (longPress) return@awaitEachGesture

            if (axis == AXIS_HORIZONTAL && seekTarget >= 0L) {
                callbacks.value.onSeekCommit(seekTarget)
                return@awaitEachGesture
            }
            if (axis == AXIS_VERTICAL) {
                // The hint remains visible briefly; the surface has no persistent
                // setting to commit for brightness/volume other than the system value.
                return@awaitEachGesture
            }
            if (!tapCandidate) return@awaitEachGesture

            val x = down.position.x
            val zone = when {
                x < width * 0.30f -> ZONE_LEFT
                x > width * 0.70f -> ZONE_RIGHT
                else -> ZONE_CENTER
            }
            val isDouble = lastTapZone == zone &&
                down.uptimeMillis - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis
            if (isDouble) {
                lastTapTime = 0L
                lastTapZone = ZONE_NONE
                when (zone) {
                    ZONE_CENTER -> callbacks.value.onTogglePlayback()
                    ZONE_LEFT -> callbacks.value.onSeekBy(-SEEK_STEP_MS)
                    ZONE_RIGHT -> callbacks.value.onSeekBy(SEEK_STEP_MS)
                }
            } else {
                lastTapTime = down.uptimeMillis
                lastTapZone = zone
                callbacks.value.onTap()
            }
        }
    }
}

private class GestureCallbacks(
    val onTap: () -> Unit,
    val onTogglePlayback: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSeekPreview: (Long, Long) -> Unit,
    val onSeekCommit: (Long) -> Unit,
    val onTemporarySpeedStart: () -> Unit,
    val onTemporarySpeedEnd: () -> Unit,
    val onHint: (PlayerGestureHint?) -> Unit,
    val onBrightnessChange: (Float) -> Unit,
    val currentBrightness: () -> Float,
    val onVolumeChange: (Int) -> Unit,
    val currentVolume: () -> Int,
    val maxVolume: () -> Int,
)

private const val AXIS_NONE = 0
private const val AXIS_HORIZONTAL = 1
private const val AXIS_VERTICAL = 2

private const val VERTICAL_NONE = 0
private const val VERTICAL_BRIGHTNESS = 1
private const val VERTICAL_VOLUME = 2

private const val ZONE_NONE = 0
private const val ZONE_LEFT = 1
private const val ZONE_CENTER = 2
private const val ZONE_RIGHT = 3

private const val HORIZONTAL_SEEK_FRACTION = 0.20f
private const val SEEK_STEP_MS = 10_000L
