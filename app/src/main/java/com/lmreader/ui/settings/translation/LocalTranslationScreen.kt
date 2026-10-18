package com.lmreader.ui.settings.translation

import com.lmreader.ui.i18n.Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.R
import com.lmreader.core.model.*
import com.lmreader.core.translation.TranslationModelCatalog
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.api.ApiTopBar
import kotlinx.coroutines.launch
import java.util.Locale
import com.lmreader.ui.translation.languageLabel
import com.lmreader.ui.translation.platformLanguageNames

@Composable
fun LocalTranslationScreen(container:AppContainer,onBack:()->Unit) {
    val vm:LocalTranslationViewModel=viewModel(factory=viewModelFactory {initializer {LocalTranslationViewModel(container)}})
    val test by vm.state.collectAsStateWithLifecycle()
    val models=container.translationModels
    val states by models.states.collectAsStateWithLifecycle()
    val installed by models.installedPacks.collectAsStateWithLifecycle()
    val retained by models.retainedPacks.collectAsStateWithLifecycle()
    val catalogState by container.translationCatalogs.state.collectAsStateWithLifecycle()
    val catalog=catalogState.catalog
    val settings by container.translationSources.settings.collectAsStateWithLifecycle(initialValue=null)
    val engine = settings?.engine ?: TranslationModelEngine.BERGAMOT
    val installedForEngine = installed.filterValues { it.engine == engine }
    val available = catalog.packs.filter { it.engine == engine }
    val languages=(available.flatMap {listOf(it.source,it.target)}+installedForEngine.values.flatMap {listOf(it.source,it.target)}).distinct().sortedBy {it.tag}
    val route=runCatching {TranslationModelCatalog.fromPacks(installedForEngine.values.toList()).route(test.source,test.target,engine)}
        .recoverCatching {TranslationModelCatalog.fromPacks((installedForEngine.values+available).distinctBy {it.id}).route(test.source,test.target,engine)}.getOrNull()
    val missing=route.orEmpty().filterNot {installed.containsKey(it.id)}
    var sourcesOpen by remember {mutableStateOf(false)}
    var licenseOpen by remember {mutableStateOf(false)}
    var resultOpen by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<TranslationModelPack?>(null)}
    var importing by remember {mutableStateOf<TranslationModelPack?>(null)}
    var query by rememberSaveable {mutableStateOf("")}
    val context=LocalContext.current
    val cannotOpenMessage=stringResource(R.string.local_mt_cannot_open)
    val locale=LocalConfiguration.current.locales[0]
    val names=remember(languages,locale) {languages.associateWith {platformLanguageNames(it,locale)}}
    val columns=translationPackageColumns(available,retained.filter {it.engine==engine},installedForEngine,query,locale) {language ->names[language]?.let {listOf(it.first,it.second)} ?: emptyList()}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        importing?.let {pack ->if(uri!=null) models.importZip(pack) {
            context.contentResolver.openInputStream(uri) ?: error(cannotOpenMessage)
        }};importing=null
    }
    LaunchedEffect(test.result) {if(test.result!=null) resultOpen=true}
    Scaffold(topBar={ApiTopBar(stringResource(R.string.local_mt_title),onBack) {
        TextButton(onClick={sourcesOpen=true},enabled=settings!=null) {Text(stringResource(R.string.local_mt_sources))}
    }}) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TranslationModelEngine.entries.forEach { option -> FilterChip(selected=engine==option,onClick={vm.engine(option)},
                        enabled=settings!=null && !test.running,label={Text(if(option==TranslationModelEngine.OPUS_MT) "OPUS-MT" else "Bergamot")}) }
                }
                Text(stringResource(if(engine==TranslationModelEngine.OPUS_MT) R.string.local_mt_opus_backend else R.string.local_mt_backend),style=MaterialTheme.typography.titleMedium)
                if(engine==TranslationModelEngine.OPUS_MT) Text(stringResource(R.string.local_mt_opus_help),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.local_mt_current_source,settings?.let {sourceLabel(it.source)} ?: stringResource(R.string.local_mt_loading)),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.local_mt_download_lifetime),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={licenseOpen=true}) {Text(stringResource(R.string.local_mt_licenses))}
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.local_mt_text_test),style=MaterialTheme.typography.titleMedium)
                    LanguagePicker(stringResource(R.string.local_mt_source_language),test.source,languages,!test.running,vm::source)
                    LanguagePicker(stringResource(R.string.local_mt_target_language),test.target,languages,!test.running,vm::target)
                    Text(when {
                        route==null -> stringResource(R.string.local_mt_no_route)
                        route.isEmpty() -> stringResource(R.string.local_mt_identity)
                        else -> (listOf(route.first().source)+route.map {it.target}).map {languageLabel(it)}.joinToString(" → ")+if(route.size>1) stringResource(R.string.local_mt_pivot) else ""
                    },style=MaterialTheme.typography.bodySmall)
                    if(missing.isNotEmpty()) Text(stringResource(R.string.local_mt_missing,missing.joinToString {it.id}),style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={vm.sample(LocalTranslationLanguage.JAPANESE)},enabled=!test.running) {Text(stringResource(R.string.local_mt_sample_ja))}
                        TextButton(onClick={vm.sample(LocalTranslationLanguage.KOREAN)},enabled=!test.running) {Text(stringResource(R.string.local_mt_sample_ko))}
                        TextButton(onClick={vm.sample(LocalTranslationLanguage.ENGLISH)},enabled=!test.running) {Text(stringResource(R.string.local_mt_sample_en))}
                    }
                    OutlinedTextField(test.input,vm::text,Modifier.fillMaxWidth(),label={Text(stringResource(R.string.local_mt_input))},enabled=!test.running,minLines=3,maxLines=7)
                    if(test.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if(test.cancelling) stringResource(R.string.local_mt_cancelling) else test.progress)
                        TextButton(onClick=vm::cancel,enabled=!test.cancelling) {Text(stringResource(R.string.local_mt_cancel_test))}
                    } else Button(onClick=vm::run,enabled=route!=null && missing.isEmpty() && test.input.length<=4096) {Text(stringResource(R.string.local_mt_test))}
                    test.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    test.result?.let {TextButton(onClick={resultOpen=true}) {Text(stringResource(R.string.local_mt_view_result,it.elapsedMillis))}}
                }}
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.local_mt_packages),style=MaterialTheme.typography.titleMedium)
                    if(catalogState.fetching) TextButton(onClick=vm::cancelCatalog) {Text(stringResource(R.string.local_mt_cancel_fetch))}
                    else TextButton(onClick=vm::refreshCatalog,enabled=settings!=null) {Text(stringResource(R.string.local_mt_fetch))}
                }
                if(catalogState.fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(catalogState.fetched) stringResource(R.string.local_mt_catalog_summary,available.size,languages.size) else stringResource(R.string.local_mt_catalog_fallback),style=MaterialTheme.typography.bodySmall)
                catalogState.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=8.dp),label={Text(stringResource(R.string.local_mt_search))},singleLine=true,
                    trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text(stringResource(R.string.local_mt_clear))}})
            }
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("→ ${languageLabel(LocalTranslationLanguage.ENGLISH)}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                Text("${languageLabel(LocalTranslationLanguage.ENGLISH)} →",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            }}
            items(maxOf(columns.incoming.size,columns.outgoing.size)) {index ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf(columns.incoming.getOrNull(index),columns.outgoing.getOrNull(index)).forEach {pack ->
                        if(pack==null) Spacer(Modifier.weight(1f)) else PackageCard(pack,installed[pack.id],states[pack.id] ?: TranslationPackState(),
                            catalogState.fetched,catalog.packs.any {it.id==pack.id},settings!=null,!test.running,Modifier.weight(1f),
                            onDownload={models.download(pack)},onPause={models.pause(pack)},onDiscard={models.discard(pack)},
                            onImport={importing=pack;launcher.launch(arrayOf("application/zip","application/octet-stream"))},onDelete={deleting=installed[pack.id]})
                    }
                }
            }
            if(columns.other.isNotEmpty()) {
                item {Text(stringResource(R.string.local_mt_other_directions),style=MaterialTheme.typography.titleSmall)}
                items(columns.other.chunked(2)) {pair ->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    pair.forEach {pack ->PackageCard(pack,installed[pack.id],states[pack.id] ?: TranslationPackState(),catalogState.fetched,catalog.packs.any {it.id==pack.id},settings!=null,!test.running,Modifier.weight(1f),
                        {models.download(pack)},{models.pause(pack)},{models.discard(pack)},{importing=pack;launcher.launch(arrayOf("application/zip","application/octet-stream"))},{deleting=installed[pack.id]})}
                    if(pair.size==1) Spacer(Modifier.weight(1f))
                }}
            }
            if(columns.incoming.isEmpty() && columns.outgoing.isEmpty() && columns.other.isEmpty()) item {Text(stringResource(R.string.local_mt_no_packages))}
        }
    }
    if(sourcesOpen && settings!=null) SourceDialog(settings!!,container) {sourcesOpen=false}
    if(resultOpen && test.result!=null) AlertDialog(onDismissRequest={resultOpen=false},title={Text(stringResource(R.string.local_mt_result))},text={
        SelectionContainer {Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(test.result!!.items.joinToString("\n\n") {it.translatedText}, localize = false)
            Text("${test.result!!.backend} · ${test.result!!.elapsedMillis} ms",style=MaterialTheme.typography.labelSmall)
        }}
    },confirmButton={TextButton(onClick={resultOpen=false}) {Text(stringResource(R.string.local_mt_close))}})
    deleting?.let {pack ->AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.local_mt_delete_title))},
        text={Text(stringResource(R.string.local_mt_delete_message,pack.id,pack.version))},confirmButton={TextButton(onClick={vm.remove(pack);deleting=null}) {Text(stringResource(R.string.local_mt_delete))}},
        dismissButton={TextButton(onClick={deleting=null}) {Text(stringResource(R.string.local_mt_cancel))}})}
    if(licenseOpen) LicenseDialog {licenseOpen=false}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageCard(pack:TranslationModelPack,installed:TranslationModelPack?,state:TranslationPackState,fetched:Boolean,available:Boolean,
    configured:Boolean,canDelete:Boolean,modifier:Modifier,onDownload:()->Unit,onPause:()->Unit,onDiscard:()->Unit,onImport:()->Unit,onDelete:()->Unit) {
    OutlinedCard(modifier) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("${languageLabel(pack.source)} → ${languageLabel(pack.target)}",style=MaterialTheme.typography.titleSmall)
        Text(pack.id,style=MaterialTheme.typography.labelSmall)
        if(pack.repository.isNotBlank()) Text("${pack.repository} · ${pack.license}",style=MaterialTheme.typography.labelSmall,localize=false)
        Text(stringResource(R.string.local_mt_installed_version,installed?.version ?: stringResource(R.string.local_mt_not_installed)),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.local_mt_latest_version,if(!fetched) stringResource(R.string.local_mt_not_fetched) else if(available) pack.version else stringResource(R.string.local_mt_not_in_source)),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.local_mt_sizes,megabytes(pack.downloadBytes),megabytes((installed ?: pack).installedBytes)),style=MaterialTheme.typography.bodySmall)
        Text(when(state.status) {
            TranslationPackStatus.NOT_INSTALLED -> stringResource(R.string.local_mt_not_installed)
            TranslationPackStatus.DOWNLOADING -> stringResource(R.string.local_mt_downloading,megabytes(state.completedBytes))
            TranslationPackStatus.PAUSED -> stringResource(R.string.local_mt_paused)
            TranslationPackStatus.VERIFYING -> stringResource(R.string.local_mt_verifying)
            TranslationPackStatus.READY -> stringResource(R.string.local_mt_ready)
            TranslationPackStatus.FAILED -> stringResource(R.string.local_mt_failed,state.message.orEmpty())
        },style=MaterialTheme.typography.bodySmall,color=if(state.status==TranslationPackStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.status==TranslationPackStatus.DOWNLOADING) LinearProgressIndicator(progress={if(state.totalBytes>0) (state.completedBytes.toFloat()/state.totalBytes).coerceIn(0f,0.99f) else 0f},modifier=Modifier.fillMaxWidth())
        FlowRow {
            if(installed!=null) TextButton(onClick=onDelete,enabled=canDelete) {Text(stringResource(R.string.local_mt_delete))}
            else when(state.status) {
                TranslationPackStatus.DOWNLOADING,TranslationPackStatus.VERIFYING -> {
                    TextButton(onClick=onPause) {Text(stringResource(R.string.local_mt_pause))}
                    TextButton(onClick=onDiscard) {Text(stringResource(R.string.local_mt_cancel_download))}
                }
                else -> {
                    TextButton(onClick=onDownload,enabled=configured && available) {Text(stringResource(if(state.status==TranslationPackStatus.NOT_INSTALLED) R.string.local_mt_download else R.string.local_mt_resume))}
                    TextButton(onClick=onImport,enabled=available) {Text(stringResource(R.string.local_mt_import))}
                    if(state.status!=TranslationPackStatus.NOT_INSTALLED) TextButton(onClick=onDiscard) {Text(stringResource(R.string.local_mt_cancel_download))}
                }
            }
        }
    }}
}

@Composable
private fun LanguagePicker(label:String,value:LocalTranslationLanguage,options:List<LocalTranslationLanguage>,enabled:Boolean,onSelect:(LocalTranslationLanguage)->Unit) {
    var open by remember {mutableStateOf(false)}
    var manual by remember {mutableStateOf(false)}
    var code by remember {mutableStateOf(value.tag)}
    var invalid by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={open=true},enabled=enabled) {Text("$label：${languageLabel(value)}")}
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=360.dp)) {
            options.forEach {language ->DropdownMenuItem(text={Column {Text(languageLabel(language));Text(language.tag,style=MaterialTheme.typography.labelSmall)}},onClick={onSelect(language);open=false})}
            HorizontalDivider()
            DropdownMenuItem(text={Text(stringResource(R.string.local_mt_manual_language))},onClick={code=value.tag;invalid=false;open=false;manual=true})
        }
    }
    if(manual) AlertDialog(onDismissRequest={manual=false},title={Text(stringResource(R.string.local_mt_manual_language))},text={Column {
        OutlinedTextField(code,{code=it;invalid=false},label={Text(stringResource(R.string.local_mt_language_code))},singleLine=true,isError=invalid)
        Text(stringResource(if(invalid) R.string.local_mt_invalid_language else R.string.local_mt_language_code_help),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={try {onSelect(LocalTranslationLanguage.fromTag(code));manual=false} catch(_:IllegalArgumentException) {invalid=true}}) {Text(stringResource(R.string.local_mt_confirm))}},
        dismissButton={TextButton(onClick={manual=false}) {Text(stringResource(R.string.local_mt_cancel))}})
}

@Composable
private fun sourceLabel(source:TranslationDownloadSource)=stringResource(when(source) {
    TranslationDownloadSource.MOZILLA->R.string.local_mt_source_mozilla
    TranslationDownloadSource.HUGGING_FACE->R.string.local_mt_source_hf
    TranslationDownloadSource.HF_MIRROR->R.string.local_mt_source_mirror
    TranslationDownloadSource.CUSTOM->R.string.local_mt_source_custom
})

@Composable
private fun SourceDialog(initial:TranslationSourceSettings,container:AppContainer,onDismiss:()->Unit) {
    var source by remember {mutableStateOf(initial.source)};var base by remember {mutableStateOf(initial.customBaseUrl)}
    var wifi by remember {mutableStateOf(initial.wifiOnly)};var error by remember {mutableStateOf<String?>(null)};var saving by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!saving) onDismiss()},title={Text(stringResource(R.string.local_mt_source_title))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        TranslationDownloadSource.entries.filter {initial.engine!=TranslationModelEngine.OPUS_MT || it!=TranslationDownloadSource.MOZILLA}.forEach {option ->Row(Modifier.fillMaxWidth().clickable(enabled=!saving) {source=option}) {
            RadioButton(source==option,{source=option},enabled=!saving);Text(sourceLabel(option),Modifier.padding(top=12.dp))
        }}
        if(source==TranslationDownloadSource.CUSTOM) OutlinedTextField(base,{base=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.local_mt_custom_base))},singleLine=true,enabled=!saving)
        Text(stringResource(R.string.local_mt_source_help),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.clickable(enabled=!saving) {wifi=!wifi}) {Checkbox(wifi,{wifi=it},enabled=!saving);Text(stringResource(R.string.local_mt_wifi_only),Modifier.padding(top=12.dp))}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!saving,onClick={try {
        if(source==TranslationDownloadSource.CUSTOM) TranslationModelCatalog.validateCustomBase(base)
        saving=true;scope.launch {try {container.translationSources.save(initial.copy(source=source,customBaseUrl=base,wifiOnly=wifi));onDismiss()} catch(failure:Exception) {error=failure.message;saving=false}}
    } catch(failure:Exception) {error=failure.message}}) {Text(stringResource(R.string.local_mt_save))}},
        dismissButton={TextButton(onClick=onDismiss,enabled=!saving) {Text(stringResource(R.string.local_mt_cancel))}})
}

@Composable
private fun LicenseDialog(onDismiss:()->Unit) {
    val context=LocalContext.current;var selected by remember {mutableStateOf("NOTICE.txt")};var menuOpen by remember {mutableStateOf(false)};var text by remember {mutableStateOf("")}
    val names=remember {context.assets.list("translation/licenses")!!.sorted()}
    LaunchedEffect(selected) {text=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {context.assets.open("translation/licenses/$selected").bufferedReader().use {it.readText()}}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.local_mt_licenses))},text={LazyColumn(Modifier.heightIn(max=440.dp)) {
        item {Text(stringResource(R.string.local_mt_license_summary),style=MaterialTheme.typography.bodySmall)}
        item {Box {
            TextButton(onClick={menuOpen=true}) {Text(stringResource(R.string.local_mt_license_file,selected),style=MaterialTheme.typography.labelSmall)}
            DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false},modifier=Modifier.heightIn(max=240.dp)) {
                names.forEach {name ->DropdownMenuItem(text={Text(name,style=MaterialTheme.typography.labelSmall)},onClick={selected=name;menuOpen=false})}
            }
        }}
        item {SelectionContainer {Text(text,style=MaterialTheme.typography.bodySmall)}}
    }},confirmButton={TextButton(onClick=onDismiss) {Text(stringResource(R.string.local_mt_close))}})
}
private fun megabytes(bytes:Long)=String.format(Locale.ROOT,"%.1f MB",bytes/1_000_000.0)
