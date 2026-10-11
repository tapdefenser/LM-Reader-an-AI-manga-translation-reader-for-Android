package com.lmreader.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.R
import com.lmreader.di.AppContainer
import com.lmreader.screen.ScreenBallPosition
import com.lmreader.tasks.TaskQueue
import kotlin.math.roundToInt

/** Home-only space for ongoing work and the future screen translation entry. */
@Composable
internal fun HomeTaskChrome(container: AppContainer, enabled: Boolean, onOpenQueue: () -> Unit,
    content: @Composable (Modifier) -> Unit) {
    val notices by container.taskNotifications.notices.collectAsStateWithLifecycle()
    val screenState by container.screenTranslation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val step by container.translationQueue.currentStep.collectAsStateWithLifecycle()
    val notice = notices[TaskQueue.TRANSLATION]?.takeIf { enabled && it.working }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxWidth())
            if (notice != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenQueue).testTag("home-translation-progress")) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Column(Modifier.weight(1f)) {
                        Text(listOf(notice.item.manga, notice.item.chapter).filter(String::isNotBlank).joinToString(" · ")
                            .ifBlank { stringResource(R.string.lmreader_task_translation) },
                            style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text(listOfNotNull(step?.pageName?.takeIf(String::isNotBlank),
                            step?.rowLabel?.takeIf(String::isNotBlank),
                            if (notice.item.total > 0) "${notice.item.completed}/${notice.item.total}" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
        if (enabled) {
            val density = LocalDensity.current
            val size = with(density) { 48.dp.toPx() }
            val rangeX = (with(density) { maxWidth.toPx() } - size).coerceAtLeast(0f)
            val rangeY = (with(density) { maxHeight.toPx() } - size * 2).coerceAtLeast(0f)
            var x by rememberSaveable { mutableFloatStateOf(.94f) }
            var y by rememberSaveable { mutableFloatStateOf(.76f) }
            var tucked by rememberSaveable { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            val touchSlop = LocalViewConfiguration.current.touchSlop
            Box(Modifier.offset { IntOffset(ScreenBallPosition(x, y, tucked).offsetX(rangeX, size, size / 3).roundToInt(), (y * rangeY).roundToInt()) }) {
                Surface(shape = CircleShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp).testTag("screen-translation-ball")
                        .pointerInput(rangeX, rangeY, touchSlop) {
                            var start = ScreenBallPosition(x, y, tucked)
                            var dx = 0f; var dy = 0f
                            fun move(released: Boolean = false) {
                                val next = start.drag(dx, dy, rangeX, rangeY, touchSlop, released)
                                x = next.x; y = next.y; tucked = next.tucked
                            }
                            detectDragGestures(onDragStart = { menu = false; start = ScreenBallPosition(x, y, tucked); dx = 0f; dy = 0f },
                                onDragEnd = { move(released = true) }, onDragCancel = { tucked = false }) { change, delta ->
                                change.consume(); dx += delta.x; dy += delta.y; move()
                            }
                        }.clickable {
                            if (tucked) { tucked = false; return@clickable }
                            if (rangeY > 0) y = maxOf(y, (with(density) { 116.dp.toPx() } / rangeY).coerceAtMost(1f))
                            menu = true
                        }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Translate,
                        stringResource(R.string.screen_translation_settings)) }
                }
                if (menu) Popup(popupPositionProvider = remember { UpwardScreenMenuPosition() },
                    onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                    Surface(shape = MaterialTheme.shapes.small, shadowElevation = 8.dp) {
                        Column(Modifier.width(IntrinsicSize.Max)) {
                            DropdownMenuItem(text = { Text(stringResource(if (screenState.started) R.string.screen_translation_stop else R.string.screen_translation_start)) },
                                onClick = { menu = false; if (screenState.started) container.screenTranslation.stop() else container.screenTranslation.start(context) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_translation_settings)) },
                                onClick = { menu = false; container.screenTranslation.openSettings(context) })
                        }
                    }
                }
            }
        }
    }
}

internal class UpwardScreenMenuPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset((anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0))
}
