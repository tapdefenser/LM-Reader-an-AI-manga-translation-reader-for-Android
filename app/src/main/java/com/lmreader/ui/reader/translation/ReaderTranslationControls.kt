package com.lmreader.ui.reader.translation

import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.R
import com.lmreader.core.model.*
import com.lmreader.core.translation.TranslationModelCatalog
import com.lmreader.di.AppContainer
import com.lmreader.ui.translation.languageLabel

@Composable
fun ReaderTranslationButton(enabled: Boolean,hasTranslation: Boolean,showingOriginal: Boolean,running: Boolean,
    onTranslate: ()->Unit,onToggleOriginal: ()->Unit,onCancel: ()->Unit,onSettings: ()->Unit,
    editing: Boolean = false, onToggleEditing: () -> Unit = {}, onClearPage: () -> Unit = {}) {
    var open by remember {mutableStateOf(false)}
    Box {
        IconButton(onClick={open=true},enabled=enabled) {
            Icon(Icons.Filled.Translate,contentDescription=stringResource(R.string.reader_mt_menu),tint=if(enabled) Color.White else Color.Gray)
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            DropdownMenuItem(text={Text(stringResource(R.string.reader_mt_action))},onClick={open=false;onTranslate()})
            if(hasTranslation) DropdownMenuItem(text={Text(stringResource(if(showingOriginal) R.string.reader_mt_translated else R.string.reader_mt_original))},
                enabled=!editing,onClick={open=false;onToggleOriginal()})
            DropdownMenuItem(text={Text(stringResource(R.string.reader_mt_clear_page))},
                enabled=hasTranslation && !running, onClick={open=false;onClearPage()})
            DropdownMenuItem(text={Text(stringResource(if(editing) R.string.reader_bubble_exit else R.string.reader_bubble_edit))},
                enabled=!running,onClick={open=false;onToggleEditing()})
            if(running) DropdownMenuItem(text={Text(stringResource(R.string.reader_mt_cancel))},onClick={open=false;onCancel()})
            DropdownMenuItem(text={Text("翻译选项")},onClick={open=false;onSettings()})
        }
    }
}

@Composable
fun ReaderTranslationProgress(state: ReaderTranslationUiState,onCancel: ()->Unit,modifier: Modifier) {
    val progress=state.progress ?: return
    val stage=stringResource(when(progress.stage) {
        PageTranslationStage.READING->R.string.reader_mt_reading
        PageTranslationStage.SEGMENTING->R.string.reader_mt_segmenting
        PageTranslationStage.OCR->R.string.reader_mt_ocr
        PageTranslationStage.TRANSLATING->R.string.reader_mt_translating
        PageTranslationStage.SAVING->R.string.reader_mt_saving
    })
    Surface(color=Color.Black.copy(alpha=.85f),contentColor=Color.White,modifier=modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.reader_mt_progress,state.activePageName,stage),style=MaterialTheme.typography.bodySmall)
            if(progress.total>0) Text("${progress.completed} / ${progress.total}",style=MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=onCancel) {Text(stringResource(R.string.reader_mt_cancel))}
        }
    }
}

@Composable
fun PageTranslationDialog(container: AppContainer,initialSource: LocalTranslationLanguage?,initialTarget: LocalTranslationLanguage,
    onDismiss: ()->Unit,onTranslate: (LocalTranslationLanguage,LocalTranslationLanguage)->Unit, requiresLocalModels: Boolean = true,
    engines: Set<TranslationModelEngine> = setOf(TranslationModelEngine.BERGAMOT)) {
    val installed by container.translationModels.installedPacks.collectAsStateWithLifecycle()
    val catalog = remember(installed) { TranslationModelCatalog.fromPacks(installed.values.toList()) }
    val routesReady = initialSource != null && engines.all { engine -> runCatching { catalog.route(initialSource, initialTarget, engine) }.isSuccess }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reader_mt_action)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("使用这部漫画的翻译选项，重译当前页。")
            Text("原文：" + (initialSource?.let { languageLabel(it) } ?: "未选"))
            Text("目标：" + languageLabel(initialTarget))
            if (requiresLocalModels && !routesReady) Text("请在翻译选项中选择已下载模型支持的语言", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = initialSource != null && (!requiresLocalModels || routesReady), onClick = {
        onTranslate(initialSource!!, initialTarget)
    }) { Text(stringResource(R.string.reader_mt_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.local_mt_cancel)) } })
}
