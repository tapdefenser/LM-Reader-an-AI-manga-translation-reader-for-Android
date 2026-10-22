package com.lmreader.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.lmreader.core.database.media.VideoResultDao
import com.lmreader.core.database.media.VideoSettingsRecord
import com.lmreader.di.AppContainer
import com.lmreader.ui.video.editor.SubtitleStyleCard
import com.lmreader.ui.video.editor.VideoEditorSubtitleStyle
import com.lmreader.ui.video.editor.persistStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Video subtitle appearance editor shown under the source list in the player's
 * top-right 三个点 → 字幕 dialog.
 *
 * The card reads the global default and the current per-video row through
 * [resolveVideoSubtitleStyle], then writes only [VideoSettingsRecord.style]. It never
 * submits, replaces or cancels a translation task, and it does not edit cue times or text.
 */
@Composable
fun VideoSubtitleDisplaySettings(container: AppContainer, videoId: String) {
    val dao = remember(container, videoId) { container.videoResults.results() }
    val settings by dao.observeSettings(videoId).collectAsStateWithLifecycle(initialValue = null)
    val globalStyle by container.preferences.videoSubtitleStyle.collectAsStateWithLifecycle(initialValue = "{}")
    val resolved = remember(globalStyle, settings?.style) {
        VideoEditorSubtitleStyle.parse(resolveVideoSubtitleStyle(globalStyle, settings?.style ?: "{}").toString())
    }
    val scope = rememberCoroutineScope()
    var style by remember(videoId) { mutableStateOf(resolved) }
    var baseline by remember(videoId) { mutableStateOf(resolved) }
    var initialized by remember(videoId) { mutableStateOf(false) }
    var saving by remember(videoId) { mutableStateOf(false) }
    var saveError by remember(videoId) { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId, resolved) {
        if (!initialized || style == baseline) {
            style = resolved
            baseline = resolved
            initialized = true
        }
    }

    fun save() {
        if (saving) return
        val pending = style
        saving = true
        saveError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ensureSettingsRow(container, dao, videoId)
                    persistStyle(container, dao, videoId, pending)
                }
                baseline = pending
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError = error.message ?: "显示设置未保存"
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SubtitleStyleCard(
            style = style,
            dirty = style != baseline,
            saving = saving,
            enabled = !saving,
            onChange = { style = it.copy(overrideStyle = true); saveError = null },
            onSave = { save() },
        )
        saveError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (style.overrideStyle) "此视频使用独立显示样式。" else "此视频跟随全局显示样式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A style-only write must not turn the project-derived fallback options into empty defaults. */
private suspend fun ensureSettingsRow(container: AppContainer, dao: VideoResultDao, videoId: String) {
    if (dao.settings(videoId) != null) return
    val options = container.videoTasks.options(videoId)
    container.workDatabase.withTransaction {
        if(dao.settings(videoId) == null) dao.putSettings(VideoSettingsRecord(videoId = videoId, json = options.json().toString()))
    }
}
