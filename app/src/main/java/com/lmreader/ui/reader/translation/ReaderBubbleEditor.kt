package com.lmreader.ui.reader.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lmreader.R
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.video.editor.BubbleEditorFailure
import com.lmreader.ui.video.editor.BubbleEditorState
import com.lmreader.ui.video.editor.BubbleEditorToolbar

/** Reader wrapper around the neutral toolbar. Reader edit mode is still independent of the page. */
@Composable
fun ReaderBubbleEditor(state: ReaderTranslationUiState, onText: (String) -> Unit, onDelete: () -> Unit,
    onSave: () -> Unit, onUndo: () -> Unit, onExit: () -> Unit, onClearFailure: () -> Unit,
    onAdd: () -> Unit, onIncreaseFont: () -> Unit, onDecreaseFont: () -> Unit, modifier: Modifier = Modifier) {
    val draft = state.draft
    BubbleEditorToolbar(
        state = BubbleEditorState(
            editing = state.editing,
            saving = state.savingEdits,
            creating = state.creatingBubble,
            busy = state.progress != null,
            hasDraft = draft != null,
            dirty = draft?.dirty == true,
            regionCount = draft?.regions?.size ?: 0,
            hasSelection = draft?.selected != null,
            canUndo = draft?.undo?.isNotEmpty() == true,
            selectedSource = draft?.selected?.region?.sourceText.orEmpty(),
            selectedText = draft?.selected?.translatedText.orEmpty(),
            canAdd = true,
            failure = state.editFailure?.toBubbleEditorFailure(),
            showFailure = !state.confirmNavigation,
        ),
        onText = onText,
        onDelete = onDelete,
        onSave = onSave,
        onUndo = onUndo,
        onExit = onExit,
        onClearFailure = onClearFailure,
        onAdd = onAdd,
        onIncreaseFont = onIncreaseFont,
        onDecreaseFont = onDecreaseFont,
        modifier = modifier,
    )
}

@Composable
fun BubbleDraftNavigationDialog(state: ReaderTranslationUiState,onSave: ()->Unit,onDiscard: ()->Unit,onCancel:()->Unit) {
    if(!state.confirmNavigation) return
    AlertDialog(onDismissRequest={if(!state.savingEdits) onCancel()},title={Text(stringResource(R.string.reader_bubble_leave_title))},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reader_bubble_leave_message))
            state.editFailure?.let {Text(editFailureMessage(it),color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick=onSave,enabled=!state.savingEdits) {
        Text(stringResource(if(state.savingEdits) R.string.reader_bubble_saving else R.string.reader_bubble_save))
    }},dismissButton={Row {
        TextButton(onClick=onDiscard,enabled=!state.savingEdits) {Text(stringResource(R.string.reader_bubble_discard))}
        TextButton(onClick=onCancel,enabled=!state.savingEdits) {Text(stringResource(R.string.reader_bubble_cancel))}
    }})
}

private fun BubbleEditFailure.toBubbleEditorFailure(): BubbleEditorFailure = when(this) {
    BubbleEditFailure.STORAGE -> BubbleEditorFailure.STORAGE
    BubbleEditFailure.CHANGED -> BubbleEditorFailure.CHANGED
    BubbleEditFailure.OTHER -> BubbleEditorFailure.OTHER
}

@Composable
private fun editFailureMessage(failure: BubbleEditFailure) = stringResource(when(failure) {
    BubbleEditFailure.STORAGE -> R.string.reader_bubble_storage_error
    BubbleEditFailure.CHANGED -> R.string.reader_bubble_revision_error
    BubbleEditFailure.OTHER -> R.string.reader_bubble_other_error
})
