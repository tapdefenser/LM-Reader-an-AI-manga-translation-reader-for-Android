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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.R
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskQueue
import kotlin.math.roundToInt

/** Home-only space for ongoing work and the future screen translation entry. */
@Composable
internal fun HomeTaskChrome(container: AppContainer, enabled: Boolean, onOpenQueue: () -> Unit,
    content: @Composable (Modifier) -> Unit) {
    val notices by container.taskNotifications.notices.collectAsStateWithLifecycle()
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
            var menu by remember { mutableStateOf(false) }
            var settings by remember { mutableStateOf(false) }
            Box(Modifier.offset { IntOffset((x * rangeX).roundToInt(), (y * rangeY).roundToInt()) }) {
                Surface(shape = CircleShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp).testTag("screen-translation-ball")
                        .pointerInput(rangeX, rangeY) { detectDragGestures(onDragStart = { menu = false }) { change, delta ->
                            change.consume()
                            if (rangeX > 0) x = (x + delta.x / rangeX).coerceIn(0f, 1f)
                            if (rangeY > 0) y = (y + delta.y / rangeY).coerceIn(0f, 1f)
                        } }.clickable { menu = true }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Translate,
                        stringResource(R.string.screen_translation_settings)) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.screen_translation_settings)) },
                        onClick = { menu = false; settings = true })
                }
            }
            if (settings) AlertDialog(onDismissRequest = { settings = false },
                title = { Text(stringResource(R.string.screen_translation_settings)) },
                text = { Text(stringResource(R.string.screen_translation_placeholder)) },
                confirmButton = { TextButton(onClick = { settings = false }) { Text(stringResource(R.string.local_mt_close)) } })
        }
    }
}
