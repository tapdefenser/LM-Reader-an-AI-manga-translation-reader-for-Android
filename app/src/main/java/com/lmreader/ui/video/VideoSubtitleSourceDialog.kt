package com.lmreader.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.lmreader.core.database.media.VideoSettingsRecord
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal suspend fun changeVideoSubtitleSource(container: AppContainer, id: String, change: JSONObject.() -> Unit) {
    val options = container.videoTasks.options(id)
    container.videoTasks.snapshot(id) { container.workDatabase.withTransaction {
        val dao = container.videoResults.results()
        val row = dao.settings(id) ?: VideoSettingsRecord(id, json = options.json().toString())
        val style = JSONObject(row.style)
        val override = style.optBoolean("overrideStyle", style.length() > 0)
        style.change()
        style.put("overrideStyle", override)
        dao.putSettings(row.copy(style = style.toString(), revision = UUID.randomUUID().toString()))
    } }
}

/** The playback menu chooses the displayed source. Cue editing lives in the bubble editor. */
@Composable
internal fun VideoSubtitleSourceDialog(container: AppContainer, id: String, onDismiss: () -> Unit) {
    val dao = remember(container, id) { container.videoResults.results() }
    val settings by dao.observeSettings(id).collectAsStateWithLifecycle(null)
    val cues by dao.observeCues(id).collectAsStateWithLifecycle(emptyList())
    val style = remember(settings?.style) { JSONObject(settings?.style ?: "{}") }
    val scope = rememberCoroutineScope()
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp.dp * .7f).coerceIn(240.dp, 520.dp)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun select(track: String?, enabled: Boolean) {
        if(busy) return
        busy = true
        scope.launch {
            try {
                changeVideoSubtitleSource(container, id) { put("enabled", enabled); if(track != null) put("trackId", track) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "字幕选择未保存" }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            busy = true
            scope.launch {
                try {
                    val track = container.videoAudio.importSrt(id, uri)
                    changeVideoSubtitleSource(container, id) { put("enabled", true); put("trackId", track) }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { error = e.message ?: "无法导入字幕" }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if(!busy) onDismiss() }, title = { Text("字幕") }, text = {
        Column(Modifier.heightIn(max = maxContentHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = style.optBoolean("enabled", true)
            val selected = resolvedVideoSubtitleTrack(style, cues)
            @Composable fun choice(label: String, selectedRow: Boolean, action: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = action).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedRow, onClick = action, enabled = !busy)
                    Text(label)
                }
            }
            choice("关", !enabled) { select(null, false) }
            choice("翻译产生的字幕", enabled && (selected == "generated" || selected.startsWith("speech:") || selected.startsWith("embedded:"))) {
                select("generated", true)
            }
            if(cues.none { !it.deleted && !it.trackId.startsWith("external") })
                Text("尚无生成字幕；翻译完成的句子会自动显示。", style = MaterialTheme.typography.bodySmall)
            val imported = cues.filter { !it.deleted && it.trackId.startsWith("external") }.map { it.trackId }.distinct()
            imported.forEachIndexed { index, track -> choice("导入字幕 ${index + 1}", enabled && selected == track) { select(track, true) } }
            TextButton(onClick = { picker.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream")) }, enabled = !busy) { Text("导入字幕…") }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            VideoSubtitleDisplaySettings(container, id)
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}
