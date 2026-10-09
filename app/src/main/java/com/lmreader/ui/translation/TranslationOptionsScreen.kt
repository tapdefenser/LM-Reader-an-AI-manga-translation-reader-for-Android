package com.lmreader.ui.translation

import com.lmreader.ui.i18n.showLocalizedSnackbar
import com.lmreader.ui.i18n.Icon

import com.lmreader.ui.i18n.Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.*
import com.lmreader.core.translation.TranslationModelCatalog
import com.lmreader.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationOptionsScreen(container: AppContainer, mangaId: String, onBack: () -> Unit,
    onDownloadOfflinePacks: () -> Unit = {},
    showSetupPrompt: Boolean = false,
    viewModel: TranslationOptionsViewModel = viewModel(key = "translation-options-$mangaId",
        factory = TranslationOptionsViewModel.factory(container, mangaId))) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val workflows by container.translationWorkflows.workflows.collectAsStateWithLifecycle()
    val installed by container.translationModels.installedPacks.collectAsStateWithLifecycle()
    val apiProfiles by container.apiProfiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalog = remember(installed) { TranslationModelCatalog.fromPacks(installed.values.toList()) }
    val workflow = workflows.firstOrNull { it.id == (state.settings.workflowId ?: TranslationWorkflow.LOCAL_MACHINE_ID) }
    val usesLocal = workflow?.program?.uses(WorkflowKind.TRANSLATE) != false
    val apiLanguages = remember { java.util.Locale.getISOLanguages().mapNotNull { runCatching { LocalTranslationLanguage.fromTag(it) }.getOrNull() } + listOf(LocalTranslationLanguage.CHINESE_SIMPLIFIED, LocalTranslationLanguage.CHINESE_TRADITIONAL) }
    val sources = remember(catalog, usesLocal, locale) { if(usesLocal) catalog.availableSources() else
        prioritizeInstalledLanguages(apiLanguages.distinct().sortedBy { it.localizedName(locale) }, catalog.availableSources()) }
    val source = matchEngineLanguage(state.settings.sourceLanguage, sources)
    val targets = remember(catalog, source, usesLocal, locale) { if(usesLocal) source?.let(catalog::availableTargets).orEmpty() else
        prioritizeInstalledLanguages(apiLanguages.distinct().filterNot { it == source }.sortedBy { it.localizedName(locale) },
            source?.let(catalog::availableTargets) ?: catalog.availableSources().flatMap(catalog::availableTargets).distinct()) }
    val target = matchEngineLanguage(state.settings.targetLanguage, targets)
    val render = state.settings.effectiveBubbleRender(state.legacyRender)
    var workflowMenu by remember { mutableStateOf(false) }
    var apiMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var prompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showSetupPrompt) {
        if (showSetupPrompt && !prompted) { prompted = true; snackbar.showLocalizedSnackbar(context, "请选择本地机翻的原文和目标语言，返回章节列表重新发起翻译") }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showLocalizedSnackbar(context, it); viewModel.consumeMessage() } }
    picking?.let { isSource ->
        AlertDialog(onDismissRequest = { picking = null }, title = { Text(if (isSource) "原文语言" else "目标语言") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                (if (isSource) sources else targets).forEach { language ->
                    ListItem(headlineContent = { Text(languageLabel(language)) }, modifier = Modifier.clickable {
                        if (isSource) {
                            viewModel.setSourceLanguage(language.tag)
                            if (matchEngineLanguage(state.settings.targetLanguage, if(usesLocal) catalog.availableTargets(language) else sources.filterNot { it == language }) == null)
                                viewModel.setTargetLanguage("")
                        } else viewModel.setTargetLanguage(language.tag)
                        picking = null
                    })
                }
            } }, confirmButton = { TextButton(onClick = { picking = null }) { Text("关闭") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("翻译选项") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) { CircularProgressIndicator(); return@Column }
            Text("文风", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.settings.customStyle.orEmpty(), viewModel::setStyle,
                label = { Text("这部作品的文风") }, placeholder = { Text("留空 = 自动使用分类的文风") },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            Text(state.effectiveStyleSource, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("翻译工作流", style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { workflowMenu = true }) {
                        Text(workflow?.name ?: "重新选择工作流", modifier = Modifier.widthIn(max = 180.dp), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(workflowMenu, { workflowMenu = false }) {
                        workflows.filter { !it.builtIn || it.id == TranslationWorkflow.LOCAL_MACHINE_ID }.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = {
                            viewModel.setWorkflow(if (item.builtIn) null else item.id); workflowMenu = false
                        }) }
                    }
                }
            }
            if (workflow?.program?.usesApi() == true) {
                Text("选择 API（适用于本漫画）", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { apiMenu = true }, modifier = Modifier.testTag("manga-api-choice")) {
                        Text(if (state.settings.apiProfileId == null) "跟随工作流默认" else
                            apiProfiles.firstOrNull { it.id == state.settings.apiProfileId }?.name ?: "API 配置已删除，请重新选择")
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(apiMenu, { apiMenu = false }) {
                        DropdownMenuItem(text = { Text("跟随工作流默认") }, onClick = {
                            viewModel.setApiProfile(null); apiMenu = false
                        })
                        apiProfiles.forEach { profile -> DropdownMenuItem(text = { Text("${profile.name} · ${profile.model}", localize = false) },
                            onClick = { viewModel.setApiProfile(profile.id); apiMenu = false }) }
                    }
                }
                Text("所选 API 应用于此漫画工作流的所有 API 步骤。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if(usesLocal) "本地机翻" else "API 翻译语言", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { picking = true }, enabled = sources.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("原文：" + (source?.let { languageLabel(it) } ?: "未选"))
                        }
                        OutlinedButton(onClick = { picking = false }, enabled = targets.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("目标：" + (target?.let { languageLabel(it) } ?: "未选"))
                        }
                    }
                    Text(if(!usesLocal) "使用系统语言列表；实际支持的语言由所选 API 模型决定。" else if (sources.isEmpty()) "请先在 API 与翻译引擎中下载语言包" else
                        "语言来自已下载模型；目标列表随原文变化，支持通过英语中转。", style = MaterialTheme.typography.bodySmall)
                    if (usesLocal) OutlinedButton(onClick = onDownloadOfflinePacks,
                        modifier = Modifier.testTag("translation-download-offline")) { Text("下载离线翻译包") }
                    if (source == null || target == null) Text("原文与目标语言必选", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            Text("Seg 判定与回填", style = MaterialTheme.typography.titleMedium)
            Text("SEG 提取范围", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SegTextScope.entries.forEach { scope ->
                    FilterChip(state.settings.segTextScope == scope, { viewModel.setSegTextScope(scope) }, label = { Text(scope.label) })
                }
            }
            Text("此选项只作用于这部漫画的新翻译任务。", style = MaterialTheme.typography.bodySmall)
            OptionSlider("Seg 判定阈值", state.settings.effectiveSegThreshold() * 100, 5f..95f) { viewModel.setSegThreshold(it / 100) }
            OptionSlider("文字检测置信度阈值", state.settings.effectiveTextDetectionThreshold() * 100, 5f..95f) { viewModel.setTextDetectionThreshold(it / 100) }
            OptionSlider("游离文字行间合并距离", state.settings.effectiveFreeTextMergeGapRatio() * 100, 0f..200f) { viewModel.setFreeTextMergeGap(it / 100) }
            Text("0 表示逐行独立；数值越大越容易合并。100 表示允许一行字高的间隔，竖排按列宽计算。修改后需重新识别。", style = MaterialTheme.typography.bodySmall)
            Text("文字检测默认 35%。漏检时降低，误检时提高；仅影响新翻译任务中的文字检测。", style = MaterialTheme.typography.bodySmall)
            Text("阈值影响新识别；回填样式和字体实时作用于已有译文。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BubbleFillMode.entries.forEach { fill -> FilterChip(render.fillMode == fill, { viewModel.setBubbleFill(fill) },
                    label = { Text(if (fill == BubbleFillMode.AUTO) "自动取背景色" else "纯白遮盖") }) }
            }
            OptionSlider("遮盖不透明度", render.opacityPercent.toFloat(), 0f..100f) { viewModel.setBubbleOpacity(it.toInt()) }
            OptionSlider("游离文字遮罩扩张", render.freeTextMaskExpansionPercent.toFloat(), 0f..20f) { viewModel.setFreeTextMaskExpansion(it.toInt()) }
            Text("默认 6%。原文边缘仍露出时增大；过大会遮住附近画面。已有译文会实时更新。", style = MaterialTheme.typography.bodySmall)
            OptionSlider("文字边距", render.textPaddingPercent.toFloat(), 0f..20f) { viewModel.setBubblePadding(it.toInt()) }
            Text("字体", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BubbleFont.entries.forEach { font -> FilterChip(render.font == font, { viewModel.setBubbleFont(font) },
                    label = { Text(when (font) { BubbleFont.SYSTEM -> "系统"; BubbleFont.SANS_SERIF -> "无衬线"; BubbleFont.SERIF -> "衬线"; BubbleFont.MONOSPACE -> "等宽" }) }) }
            }
            OptionSlider("相对自动字号", render.fontScalePercent.toFloat(), 50f..150f) { viewModel.setBubbleFontScale(it.toInt()) }
            Text("100% 自动适配气泡；继续放大可能裁切文字。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("粗体"); Switch(render.bold, viewModel::setBubbleBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, save: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Text("$label ${draft.toInt()}%")
    Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { save(draft) },
        modifier = Modifier.testTag("translation-option:$label"))
}
