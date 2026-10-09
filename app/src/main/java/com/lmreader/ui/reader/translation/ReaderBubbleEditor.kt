package com.lmreader.ui.reader.translation

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lmreader.R
import com.lmreader.core.model.PageTranslatedRegion

/** This toolbar remains visible across pages, including pages without translation. */
@Composable
fun ReaderBubbleEditor(state: ReaderTranslationUiState, onText: (String)->Unit, onDelete: ()->Unit,
    onSave: ()->Unit, onUndo: ()->Unit, onExit: ()->Unit, onClearFailure: ()->Unit,
    onAdd: ()->Unit, onIncreaseFont: ()->Unit, onDecreaseFont: ()->Unit, modifier: Modifier = Modifier) {
    if(!state.editing) return
    var editingText by remember { mutableStateOf<PageTranslatedRegion?>(null) }
    val draft=state.draft
    val enabled=!state.savingEdits && !state.creatingBubble && state.progress==null
    Surface(color=Color.Black.copy(alpha=.58f),contentColor=Color.White,shape=RoundedCornerShape(12.dp),
        modifier=modifier.windowInsetsPadding(WindowInsets.statusBars).widthIn(max=240.dp)) {
        Column(Modifier.padding(horizontal=10.dp,vertical=6.dp)) {
            Text(stringResource(if(draft?.dirty==true) R.string.reader_bubble_unsaved else R.string.reader_bubble_mode),
                style=MaterialTheme.typography.labelLarge)
            Text(stringResource(when {
                draft==null -> R.string.reader_bubble_no_translation
                draft.regions.isEmpty() -> R.string.reader_bubble_no_regions
                draft.selected==null -> R.string.reader_bubble_select
                else -> R.string.reader_bubble_selected
            }),style=MaterialTheme.typography.labelSmall)
            Row {
                ToolbarAction(if(state.creatingBubble) R.string.reader_bubble_creating else R.string.reader_bubble_add,enabled,onAdd)
                ToolbarAction(R.string.reader_bubble_text,enabled && draft?.selected!=null) { editingText=draft?.selected }
                ToolbarAction(R.string.reader_bubble_delete,enabled && draft?.selected!=null,onDelete)
            }
            Row {
                ToolbarAction(R.string.reader_bubble_font_increase,enabled && draft?.selected!=null,onIncreaseFont)
                ToolbarAction(R.string.reader_bubble_font_decrease,enabled && draft?.selected!=null,onDecreaseFont)
            }
            if(draft?.selected!=null) Text(stringResource(R.string.reader_bubble_handle_hint),style=MaterialTheme.typography.labelSmall)
            Row {
                ToolbarAction(R.string.reader_bubble_undo,enabled && draft?.undo?.isNotEmpty()==true,onUndo)
                ToolbarAction(if(state.savingEdits) R.string.reader_bubble_saving else R.string.reader_bubble_save,
                    enabled && draft?.dirty==true,onSave)
            }
            ToolbarAction(R.string.reader_bubble_exit,enabled,onExit)
        }
    }
    editingText?.let { item ->
        var text by remember(item) {mutableStateOf(item.translatedText)}
        AlertDialog(onDismissRequest={editingText=null},title={Text(stringResource(R.string.reader_bubble_text))},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(item.region.sourceText.isNotBlank()) Text(stringResource(R.string.reader_bubble_source,item.region.sourceText),
                    style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=text,onValueChange={if(it.length<=16384) text=it},
                    label={Text(stringResource(R.string.reader_bubble_translation))},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)
                Text(stringResource(R.string.reader_bubble_draft_hint),style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={onText(text);editingText=null}) {Text(stringResource(R.string.reader_bubble_apply))}},
            dismissButton={TextButton(onClick={editingText=null}) {Text(stringResource(R.string.reader_bubble_cancel))}})
    }
    if(state.editFailure!=null && !state.confirmNavigation) AlertDialog(onDismissRequest=onClearFailure,
        title={Text(stringResource(R.string.reader_bubble_save_failed))},text={Text(editFailureMessage(state.editFailure))},
        confirmButton={TextButton(onClick=onClearFailure) {Text(stringResource(R.string.local_mt_close))}})
}

@Composable
private fun ToolbarAction(label: Int, enabled: Boolean, onClick: ()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),
        colors=ButtonDefaults.textButtonColors(contentColor=Color.White,disabledContentColor=Color.White.copy(alpha=.35f))) {
        Text(stringResource(label),style=MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BubbleDraftNavigationDialog(state: ReaderTranslationUiState,onSave: ()->Unit,onDiscard: ()->Unit,onCancel: ()->Unit) {
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

@Composable
private fun editFailureMessage(failure: BubbleEditFailure) = stringResource(when(failure) {
    BubbleEditFailure.STORAGE -> R.string.reader_bubble_storage_error
    BubbleEditFailure.CHANGED -> R.string.reader_bubble_revision_error
    BubbleEditFailure.OTHER -> R.string.reader_bubble_other_error
})
