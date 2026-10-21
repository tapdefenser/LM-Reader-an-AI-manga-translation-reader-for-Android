package com.lmreader.ui.video.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.catalog.VideoThumbnail
import com.lmreader.ui.video.catalog.formatVideoBytes
import com.lmreader.ui.video.catalog.videoResolution

/**
 * Right-side playlist drawer for the player.
 *
 * It is deliberately not a full-screen dialog: the video stays partially visible, the
 * panel width follows the window (about 40% in landscape, 70% in portrait, never wider
 * than 360dp), and its scrim consumes taps so none of the player gestures fire while it
 * is open. Only rows composed by [LazyColumn] request thumbnails.
 */
@Composable
internal fun VideoPlaylistDrawer(
    visible: Boolean,
    playlist: List<VideoPlayerItem>,
    currentId: String,
    statusFor: (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (VideoPlayerItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val fraction = if (landscape) 0.40f else 0.70f
        val panelWidth = (maxWidth * fraction)
            .coerceAtMost(360.dp)
            .coerceAtLeast(minOf(240.dp, maxWidth * 0.9f))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(220),
            ) + fadeIn(tween(140)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(180),
            ) + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    PlaylistHeader(count = playlist.size, onClose = onDismiss)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(playlist, key = { it.videoId }) { item ->
                            PlaylistRow(
                                item = item,
                                current = item.videoId == currentId,
                                status = statusFor(item.videoId),
                                onSelect = { onSelect(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(count: Int, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "收起播放列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "播放列表",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            localize = false,
        )
    }
}

@Composable
private fun PlaylistRow(
    item: VideoPlayerItem,
    current: Boolean,
    status: String,
    onSelect: () -> Unit,
) {
    val background = if (current) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        VideoThumbnail(
            uri = item.uri,
            cacheKey = item.uri,
            durationMs = item.durationMs,
            accessible = item.accessible,
            modifier = Modifier.size(64.dp),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                localize = false,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${videoResolution(item.width, item.height)} | " +
                    "${formatVideoBytes(item.bytes)} | $status",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                localize = false,
            )
        }
        if (current) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前视频",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
