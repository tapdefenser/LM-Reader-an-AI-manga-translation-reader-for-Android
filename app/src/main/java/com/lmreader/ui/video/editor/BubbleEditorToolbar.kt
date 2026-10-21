package com.lmreader.ui.video.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lmreader.R
import com.lmreader.ui.i18n.Text
import java.util.Locale
import kotlin.math.max

/**
 * Neutral editor state shared by the reader bubble editor and the video editors.
 * It deliberately carries no reader page/view-model type so a video frame can use
 * the very same toolbar and its existing behaviour.
 */
data class BubbleEditorState(
    val editing: Boolean = true,
    val saving: Boolean = false,
    val creating: Boolean = false,
    val busy: Boolean = false,
    val hasDraft: Boolean = true,
    val dirty: Boolean = false,
    val regionCount: Int = 0,
    val hasSelection: Boolean = false,
    val canUndo: Boolean = false,
    val selectedSource: String = "",
    val selectedText: String = "",
    val canAdd: Boolean = true,
    val failure: BubbleEditorFailure? = null,
    val failureText: String? = null,
    val showFailure: Boolean = true,
)

enum class BubbleEditorFailure { STORAGE, CHANGED, OTHER }

/** Existing floating bubble toolbar, retained for the reader and reused by video. */
@Composable
fun BubbleEditorToolbar(
    state: BubbleEditorState,
    onText: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onExit: () -> Unit,
    onClearFailure: () -> Unit,
    onAdd: () -> Unit,
    onIncreaseFont: () -> Unit,
    onDecreaseFont: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 240.dp,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    if (!state.editing) return
    var editingText by remember { mutableStateOf<Pair<String, String>?>(null) }
    val enabled = !state.saving && !state.creating && !state.busy
    Surface(
        color = Color.Black.copy(alpha = .58f),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars).widthIn(max = maxWidth),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                stringResource(if (state.dirty) R.string.reader_bubble_unsaved else R.string.reader_bubble_mode),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(
                    when {
                        !state.hasDraft -> R.string.reader_bubble_no_translation
                        state.regionCount == 0 -> R.string.reader_bubble_no_regions
                        !state.hasSelection -> R.string.reader_bubble_select
                        else -> R.string.reader_bubble_selected
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            Row {
                ToolbarAction(if (state.creating) R.string.reader_bubble_creating else R.string.reader_bubble_add, enabled && state.canAdd, onAdd)
                ToolbarAction(R.string.reader_bubble_text, enabled && state.hasSelection) {
                    editingText = state.selectedSource to state.selectedText
                }
                ToolbarAction(R.string.reader_bubble_delete, enabled && state.hasSelection, onDelete)
            }
            Row {
                ToolbarAction(R.string.reader_bubble_font_increase, enabled && state.hasSelection, onIncreaseFont)
                ToolbarAction(R.string.reader_bubble_font_decrease, enabled && state.hasSelection, onDecreaseFont)
            }
            if (state.hasSelection) {
                Text(stringResource(R.string.reader_bubble_handle_hint), style = MaterialTheme.typography.labelSmall)
            }
            Row {
                ToolbarAction(R.string.reader_bubble_undo, enabled && state.canUndo, onUndo)
                ToolbarAction(
                    if (state.saving) R.string.reader_bubble_saving else R.string.reader_bubble_save,
                    enabled && state.dirty,
                    onSave,
                )
            }
            extra()
            ToolbarAction(R.string.reader_bubble_exit, enabled, onExit)
        }
    }
    editingText?.let { (source, original) ->
        var text by remember(original) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editingText = null },
            title = { Text(stringResource(R.string.reader_bubble_text)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (source.isNotBlank()) {
                        Text(stringResource(R.string.reader_bubble_source, source), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= 16384) text = it },
                        label = { Text(stringResource(R.string.reader_bubble_translation)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                    Text(stringResource(R.string.reader_bubble_draft_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onText(text)
                    editingText = null
                }) { Text(stringResource(R.string.reader_bubble_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { editingText = null }) { Text(stringResource(R.string.reader_bubble_cancel)) }
            },
        )
    }
    if (state.failure != null && state.showFailure) {
        AlertDialog(
            onDismissRequest = onClearFailure,
            title = { Text(stringResource(R.string.reader_bubble_save_failed)) },
            text = {
                Text(state.failureText ?: stringResource(
                    when (state.failure) {
                        BubbleEditorFailure.STORAGE -> R.string.reader_bubble_storage_error
                        BubbleEditorFailure.CHANGED -> R.string.reader_bubble_revision_error
                        BubbleEditorFailure.OTHER -> R.string.reader_bubble_other_error
                    },
                ))
            },
            confirmButton = {
                TextButton(onClick = onClearFailure) { Text(stringResource(R.string.local_mt_close)) }
            },
        )
    }
}

@Composable
private fun ToolbarAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = .35f),
        ),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

/** One interval painted on the shared editor time band. */
data class EditorTimeBandRange(val startMs: Long, val endMs: Long)

/** Compact interval ruler used by both video editors. */
@Composable
fun EditorTimeBand(
    durationMs: Long,
    playheadMs: Long,
    ranges: List<EditorTimeBandRange>,
    modifier: Modifier = Modifier,
    selected: EditorTimeBandRange? = null,
    rangeColor: Color = Color(0xFF4FC3F7),
    selectedColor: Color = Color(0xFFFFA62D),
) {
    val safeDuration = durationMs.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val trackHeight = 4.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = .16f),
            topLeft = Offset(0f, size.height / 2 - trackHeight / 2),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2),
        )
        fun leftOf(value: Long) = (value.coerceIn(0, safeDuration).toFloat() / safeDuration) * size.width
        ranges.forEach { range ->
            val left = leftOf(range.startMs)
            val right = leftOf(range.endMs)
            drawRoundRect(
                color = rangeColor.copy(alpha = .55f),
                topLeft = Offset(left, size.height / 2 - 4.dp.toPx()),
                size = Size(max(1f, right - left), 8.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        selected?.let { range ->
            val left = leftOf(range.startMs)
            val right = leftOf(range.endMs)
            drawRoundRect(
                color = selectedColor,
                topLeft = Offset(left, size.height / 2 - 6.dp.toPx()),
                size = Size(max(2f, right - left), 12.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        val playhead = leftOf(playheadMs)
        drawLine(
            color = Color(0xFFFFA62D),
            start = Offset(playhead, 0f),
            end = Offset(playhead, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/** HH:MM:SS.mmm on the source video timeline. */
fun formatBubbleTime(milliseconds: Long): String {
    val value = milliseconds.coerceAtLeast(0)
    val hours = value / 3_600_000L
    val minutes = (value % 3_600_000L) / 60_000L
    val seconds = (value % 60_000L) / 1_000L
    val millis = value % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
}

private val bubbleTimePattern = Regex("""(\d{1,6}):([0-5]\d):([0-5]\d)[.,](\d{3})""")

/** Strict parsed boundary input; invalid or out-of-range text returns null. */
fun parseBubbleTime(text: String): Long? {
    val match = bubbleTimePattern.matchEntire(text.trim()) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    val millis = match.groupValues[4].toLong()
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

fun validBubbleTimeRange(startMs: Long, endMs: Long, durationMs: Long): Boolean =
    startMs >= 0 && endMs > startMs && endMs <= durationMs
