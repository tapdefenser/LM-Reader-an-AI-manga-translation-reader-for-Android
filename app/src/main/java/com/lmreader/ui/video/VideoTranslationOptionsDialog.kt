package com.lmreader.ui.video

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.MainActivity
import com.lmreader.core.model.ApiProfile
import com.lmreader.core.model.ApiProfileKind
import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.BubbleFont
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.SegTextScope
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.core.model.VideoSamplingPlan
import com.lmreader.core.model.VideoSamplingRate
import com.lmreader.core.model.WorkflowInputScenario
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.LabeledSlider
import com.lmreader.ui.i18n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Per-video options editor. Reads VideoTasks.options / videoCatalog only; the draft is persisted
 * solely by 保存 through VideoTasks.saveOptions. Saving never submits, replaces or requeues work:
 * already queued/running tasks keep their captured config and a future task uses the saved draft.
 * Subtitle source is fixed to local ASR: the model is resolved from Settings when work starts, so
 * the saved options keep asrModelId=null and carry the legacy audioTrack unseen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoTranslationOptionsDialog(container: AppContainer, videoId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val workflows by container.translationWorkflows.workflows.collectAsStateWithLifecycle()
    val apiProfiles by container.apiProfiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val installedPacks by container.translationModels.installedPacks.collectAsStateWithLifecycle()
    val catalogs by container.translationCatalogs.state.collectAsStateWithLifecycle()

    var savedOptions by remember(videoId) { mutableStateOf<VideoWorkOptions?>(null) }
    var durationMs by remember(videoId) { mutableStateOf(0L) }
    var videoFound by remember(videoId) { mutableStateOf(true) }
    var rootLoaded by remember(videoId) { mutableStateOf(false) }
    var loadError by remember(videoId) { mutableStateOf<String?>(null) }
    var reloadKey by remember(videoId) { mutableStateOf(0) }

    var initialized by rememberSaveable(videoId) { mutableStateOf(false) }
    var source by rememberSaveable(videoId) { mutableStateOf("ja") }
    var target by rememberSaveable(videoId) { mutableStateOf("zh-Hans") }
    var mode by rememberSaveable(videoId) { mutableStateOf("BOTH") }
    var frameWorkflowId by rememberSaveable(videoId) { mutableStateOf(TranslationWorkflow.LOCAL_MACHINE_ID) }
    var subtitleWorkflowId by rememberSaveable(videoId) { mutableStateOf("builtin.subtitle-local") }
    var fpsKey by rememberSaveable(videoId) { mutableStateOf("1/1") }
    var startText by rememberSaveable(videoId) { mutableStateOf("00:00:00.000") }
    var endText by rememberSaveable(videoId) { mutableStateOf("") }
    var maxDimensionText by rememberSaveable(videoId) { mutableStateOf("1280") }
    var audioTrack by rememberSaveable(videoId) { mutableStateOf(-1) }
    var segTextScopeKey by rememberSaveable(videoId) { mutableStateOf(SegTextScope.ALL.name) }
    var frameApiProfileId by rememberSaveable(videoId) { mutableStateOf("") }
    var subtitleApiProfileId by rememberSaveable(videoId) { mutableStateOf("") }
    var frameSettingsJson by rememberSaveable(videoId) {
        mutableStateOf(VideoFrameSettings().json().toString())
    }
    val frameSettings = remember(frameSettingsJson) {
        runCatching { VideoFrameSettings.read(JSONObject(frameSettingsJson)) }.getOrDefault(VideoFrameSettings())
    }
    var frameExpanded by rememberSaveable(videoId) { mutableStateOf(false) }
    var rangeExpanded by rememberSaveable(videoId) { mutableStateOf(false) }

    var languagePickerForSource by remember { mutableStateOf<Boolean?>(null) }
    var manualForSource by remember { mutableStateOf<Boolean?>(null) }
    var manualText by remember { mutableStateOf("") }
    var workflowPicker by remember { mutableStateOf<String?>(null) }
    var apiPicker by remember { mutableStateOf<String?>(null) }
    var saving by remember(videoId) { mutableStateOf(false) }
    var saveError by remember(videoId) { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId, reloadKey) {
        rootLoaded = false
        loadError = null
        try {
            val loaded = container.videoTasks.options(videoId)
            val catalog = try {
                container.videoCatalog.completeMetadata(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            savedOptions = loaded
            videoFound = catalog != null
            durationMs = catalog?.durationMs?.coerceAtLeast(0L) ?: 0L
            if (!initialized) {
                val savedRate = try {
                    VideoSamplingRate(loaded.fpsNumerator, loaded.fpsDenominator)
                } catch (_: Exception) {
                    VideoSamplingRate.ONE_FPS
                }
                mode = loaded.mode.uppercase(Locale.ROOT).takeIf { it in VIDEO_MODES } ?: "BOTH"
                source = loaded.source
                target = loaded.target
                frameWorkflowId = loaded.frameWorkflowId
                subtitleWorkflowId = loaded.subtitleWorkflowId
                fpsKey = FPS_CHOICES.firstOrNull { it.rate == savedRate }?.key ?: "1/1"
                startText = formatTimecode(loaded.startMs)
                endText = (loaded.endMs.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 })?.let(::formatTimecode).orEmpty()
                maxDimensionText = loaded.maxDimension.coerceAtLeast(1).toString()
                audioTrack = loaded.audioTrack
                segTextScopeKey = loaded.segTextScope.name
                frameSettingsJson = (loaded.frameSettings ?: VideoFrameSettings()).json().toString()
                frameApiProfileId = loaded.frameApiProfileId.orEmpty()
                subtitleApiProfileId = loaded.subtitleApiProfileId.orEmpty()
                initialized = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "无法读取本视频的翻译选项"
        } finally {
            rootLoaded = true
        }
    }

    // Catalog languages plus all installed pack endpoints. No installed-only filtering.
    val languageChoices = remember(catalogs.catalog, installedPacks, locale) {
        (catalogs.catalog.languages + installedPacks.values.flatMap { listOf(it.source, it.target) })
            .distinctBy { it.tag }.sortedBy { it.localizedName(locale) }
    }
    val sourceChoices = remember(languageChoices, source) { withCurrentLanguage(languageChoices, source) }
    val targetChoices = remember(languageChoices, target) { withCurrentLanguage(languageChoices, target) }
    val imageWorkflows = remember(workflows) {
        workflows.filter { it.program.inputScenario == WorkflowInputScenario.IMAGE }
    }
    val subtitleWorkflows = remember(workflows) {
        workflows.filter { it.program.inputScenario == WorkflowInputScenario.TIMED_TEXT }
    }
    val frameWorkflow = imageWorkflows.firstOrNull { it.id == frameWorkflowId }
    val subtitleWorkflow = subtitleWorkflows.firstOrNull { it.id == subtitleWorkflowId }
    val frameUsesApi = frameWorkflow?.program?.usesApi() == true
    val subtitleUsesApi = subtitleWorkflow?.program?.usesApi() == true
    val llmProfiles = remember(apiProfiles) { apiProfiles.filter { it.kind == ApiProfileKind.LLM } }
    val frameNeeded = mode != "SUBTITLE"
    val subtitleNeeded = mode != "FRAME"
    val rate = FPS_CHOICES.firstOrNull { it.key == fpsKey }?.rate ?: VideoSamplingRate.ONE_FPS
    val segTextScope = SegTextScope.entries.firstOrNull { it.name == segTextScopeKey } ?: SegTextScope.ALL

    val sourceLanguage = parseLanguage(source)
    val targetLanguage = parseLanguage(target)
    val startMs = parseTimecode(startText)
    val endMs = parseTimecode(endText)
    val rangeError = when {
        startMs == null -> "开始时间格式无效，可填秒、分:秒或时:分:秒"
        endMs == null -> "结束时间格式无效，可填秒、分:秒或时:分:秒"
        endMs <= startMs -> "结束时间必须晚于开始时间"
        durationMs > 0 && endMs > durationMs -> "结束时间不能超过视频时长 ${formatTimecode(durationMs)}"
        else -> null
    }
    val samplePlan = if (durationMs > 0 && startMs != null && endMs != null && endMs > startMs) {
        runCatching { VideoSamplingPlan(durationMs, rate, startMs, endMs) }.getOrNull()
    } else null
    val maxDimension = maxDimensionText.trim().toIntOrNull()
    val maxDimensionError = if (frameNeeded && (maxDimension ?: -1) !in MAX_DIMENSION_RANGE) {
        "分析分辨率上限应为 ${MAX_DIMENSION_RANGE.first}～${MAX_DIMENSION_RANGE.last} 的整数"
    } else null
    val frameWorkflowError = if (frameNeeded && frameWorkflow == null) {
        if (imageWorkflows.isEmpty()) "没有可用的画面（IMAGE）工作流，请先打开工作流设置" else "所选画面工作流已删除，请重新选择"
    } else null
    val subtitleWorkflowError = if (subtitleNeeded && subtitleWorkflow == null) {
        if (subtitleWorkflows.isEmpty()) "没有可用的字幕（TIMED_TEXT）工作流，请先打开工作流设置" else "所选字幕工作流已删除，请重新选择"
    } else null
    val errors = buildList {
        if (!videoFound) add("视频不在目录中，请重新定位原视频")
        if (sourceLanguage == null) add("源语言不是有效的 BCP-47 语言代码")
        if (targetLanguage == null) add("目标语言不是有效的 BCP-47 语言代码")
        frameWorkflowError?.let(::add)
        subtitleWorkflowError?.let(::add)
        rangeError?.let(::add)
        maxDimensionError?.let(::add)
    }

    val draftOptions = VideoWorkOptions(
        source = sourceLanguage?.tag ?: source.trim(),
        target = targetLanguage?.tag ?: target.trim(),
        mode = mode,
        frameWorkflowId = frameWorkflowId,
        subtitleWorkflowId = subtitleWorkflowId,
        fpsNumerator = rate.numerator,
        fpsDenominator = rate.denominator,
        startMs = startMs ?: 0L,
        endMs = endMs ?: 0L,
        subtitleSource = "ASR_LOCAL",
        audioTrack = audioTrack,
        subtitleTrack = -1,
        asrModelId = null,
        audioApiProfileId = null,
        audioApiEndpoint = "",
        maxDimension = maxDimension ?: 1280,
        frameApiProfileId = frameApiProfileId.takeIf { frameUsesApi && it.isNotBlank() },
        subtitleApiProfileId = subtitleApiProfileId.takeIf { subtitleUsesApi && it.isNotBlank() },
        segTextScope = segTextScope,
        frameSettings = frameSettings,
    )
    val draftCanonical = canonicalOptions(draftOptions, durationMs)
    val savedCanonical = savedOptions?.let { canonicalOptions(it, durationMs) }
    val changed = savedCanonical != null && savedCanonical != draftCanonical

    fun requestSave() {
        if (saving) return
        saveError = null
        if (!rootLoaded || !initialized) {
            saveError = "正在读取现有选项，请稍候"
            return
        }
        if (errors.isNotEmpty()) {
            if (maxDimensionError != null) frameExpanded = true
            if (rangeError != null) rangeExpanded = true
            saveError = errors.first()
            return
        }
        saving = true
        scope.launch {
            try {
                val saved = canonicalOptions(draftOptions, durationMs)
                container.videoTasks.saveOptions(videoId, saved)
                savedOptions = saved
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = e.message ?: "无法保存视频选项"
            } finally {
                saving = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(!saving, !saving, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.96f).fillMaxHeight(0.92f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("视频翻译选项", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                }
                HorizontalDivider()
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())

                if (!rootLoaded) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在读取本视频的现有选项…")
                        }
                    }
                } else if (loadError != null) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { reloadKey++ }) { Text("重试") }
                        }
                    }
                } else {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("语言", style = MaterialTheme.typography.titleMedium)
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                ChoiceRow("源语言", languageLabel(source, locale), "video-option:source") {
                                    languagePickerForSource = true
                                }
                                HorizontalDivider()
                                ChoiceRow("目标语言", languageLabel(target, locale), "video-option:target") {
                                    languagePickerForSource = false
                                }
                            }
                        }
                        Text("选择源语言和目标语言，列表末尾可手动输入。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Text("翻译内容", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VIDEO_MODES.forEach { value ->
                                FilterChip(mode == value, { mode = value }, label = { Text(modeLabel(value)) })
                            }
                        }

                        if (frameNeeded) {
                            Text("画面", style = MaterialTheme.typography.titleMedium)
                            Text("画面识别范围", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SegTextScope.entries.forEach { scope ->
                                    FilterChip(segTextScope == scope, { segTextScopeKey = scope.name },
                                        label = { Text(segTextScopeLabel(scope)) })
                                }
                            }
                            Text("选择画面工作流要识别的文字范围；此设置只影响本视频后续的画面任务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    if (frameUsesApi) {
                                        ChoiceRow("画面 API", apiProfileLabel(frameApiProfileId, llmProfiles),
                                            "video-option:frame-api") { apiPicker = "frame" }
                                        HorizontalDivider()
                                    }
                                    ChoiceRow("画面翻译工作流", workflowLabel(frameWorkflowId, imageWorkflows),
                                        "video-option:frame-workflow") { workflowPicker = "frame" }
                                }
                            }
                            frameWorkflowError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (subtitleNeeded) {
                            Text("字幕", style = MaterialTheme.typography.titleMedium)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    if (subtitleUsesApi) {
                                        ChoiceRow("字幕 API", apiProfileLabel(subtitleApiProfileId, llmProfiles),
                                            "video-option:subtitle-api") { apiPicker = "subtitle" }
                                        HorizontalDivider()
                                    }
                                    ChoiceRow("字幕翻译工作流", workflowLabel(subtitleWorkflowId, subtitleWorkflows),
                                        "video-option:subtitle-workflow") { workflowPicker = "subtitle" }
                                }
                            }
                            subtitleWorkflowError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("字幕识别使用设置中当前的本地 ASR 模型；每句话作为一个字幕页，识别后由字幕工作流翻译。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { startSettingsActivity(context, "asr") }) { Text("本地 ASR 设置") }
                        }

                        if (frameNeeded) {
                            CollapsibleSection(
                                "画面高级选项",
                                "${rate.label} · 每 ${rate.interval} 秒 1 帧" +
                                    if (samplePlan != null) " · ${samplePlan.sampleCount} 个采样位置" else "",
                                frameExpanded,
                                { frameExpanded = !frameExpanded },
                            ) {
                                Text("采样帧率", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FPS_CHOICES.forEach { choice ->
                                        FilterChip(fpsKey == choice.key, { fpsKey = choice.key }, label = { Text(choice.label) })
                                    }
                                }
                                Text("等价于每 ${rate.interval} 秒分析 1 帧。" +
                                    if (samplePlan != null) "当前范围共 ${samplePlan.sampleCount} 个采样位置。"
                                    else "范围或时长尚不可计算，暂不显示采样数量。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("提高帧率可减少短暂文字遗漏，但会增加分析量；这里只统计采样位置，不估算耗时。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(
                                    value = maxDimensionText,
                                    onValueChange = { if (it.length <= 6) maxDimensionText = it },
                                    label = { Text("分析分辨率上限") },
                                    supportingText = {
                                        Text(maxDimensionError ?: "默认 1280，只限制送入工作流的最长边。",
                                            color = if (maxDimensionError != null) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    isError = maxDimensionError != null,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                HorizontalDivider()
                                FrameAdvancedSettings(frameSettings) { frameSettingsJson = it.json().toString() }
                            }
                        }

                        CollapsibleSection(
                            "处理时间范围",
                            if (rangeError == null) {
                                "${formatTimecode(startMs ?: 0L)} – ${formatTimecode(endMs ?: durationMs)}" +
                                    if (durationMs <= 0) " · 时长未知" else ""
                            } else rangeError,
                            rangeExpanded,
                            { rangeExpanded = !rangeExpanded },
                        ) {
                            Text(if (durationMs > 0) "视频时长：${formatTimecode(durationMs)}" else "视频时长未知，结束时间必须明确填写。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TimecodeField("开始时间", startText, { startText = it.take(16) }, startMs == null)
                            TimecodeField("结束时间", endText, { endText = it.take(16) },
                                endError(endText, endMs, startMs, durationMs))
                            Text(if (durationMs > 0) "结束时间必须填写且不超过视频时长；可填秒、分:秒或时:分:秒。"
                                else "视频时长未知，结束时间必须明确填写；可填秒、分:秒或时:分:秒。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (durationMs > 0) {
                                TextButton(onClick = {
                                    startText = formatTimecode(0)
                                    endText = formatTimecode(durationMs)
                                }) { Text("设为全片") }
                            }
                            rangeError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val footerMessage = saveError ?: errors.firstOrNull()
                        if (footerMessage != null) {
                            Text(footerMessage, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(if (changed) "保存只更新本视频选项；已排队或进行中的任务保持原配置，之后新建的任务使用新配置。"
                                else "当前与已保存配置相同。保存不会创建、替换或重新排队任务。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                            Button({ requestSave() }, enabled = rootLoaded && initialized && !saving,
                                modifier = Modifier.weight(1f, fill = false)) {
                                Text("保存", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    languagePickerForSource?.let { isSource ->
        PickerDialog(
            title = if (isSource) "源语言" else "目标语言",
            options = (if (isSource) sourceChoices else targetChoices).map { PickerOption(it.tag, languageLabel(it.tag, locale)) },
            selectedId = if (isSource) source else target,
            onSelect = { if (isSource) source = it else target = it; languagePickerForSource = null },
            onDismiss = { languagePickerForSource = null },
            manualTestTag = "video-language-manual",
            refreshing = catalogs.fetching,
            refreshError = catalogs.error,
            onRefresh = { scope.launch {
                try { container.translationCatalogs.refresh(container.translationSources.settings.first()) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { /* The catalog store exposes a retryable error and keeps the last list. */ }
            } },
            onManual = {
                manualText = if (isSource) source else target
                languagePickerForSource = null
                manualForSource = isSource
            },
        )
    }

    manualForSource?.let { isSource ->
        var manualError by remember(manualForSource) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { manualForSource = null },
            modifier = Modifier.testTag("video-option-picker"),
            title = { Text("手动输入语言代码") },
            text = {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it.take(80); manualError = null },
                    label = { Text(if (isSource) "源语言 BCP-47" else "目标语言 BCP-47") },
                    supportingText = { Text(manualError ?: "例如 ja、zh-Hans、pt-BR") },
                    isError = manualError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val language = parseLanguage(manualText)
                    if (language == null) {
                        manualError = "请输入有效的 BCP-47 语言代码"
                    } else {
                        if (isSource) source = language.tag else target = language.tag
                        manualForSource = null
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { manualForSource = null }) { Text("取消") } },
        )
    }

    apiPicker?.let { branch ->
        val isFrame = branch == "frame"
        PickerDialog(
            title = if (isFrame) "画面 API" else "字幕 API",
            options = listOf(PickerOption("", "跟随工作流默认")) + llmProfiles.map { PickerOption(it.id, apiProfileLabel(it)) },
            selectedId = if (isFrame) frameApiProfileId else subtitleApiProfileId,
            onSelect = { if (isFrame) frameApiProfileId = it else subtitleApiProfileId = it; apiPicker = null },
            onDismiss = { apiPicker = null },
        )
    }

    workflowPicker?.let { branch ->
        val isFrame = branch == "frame"
        PickerDialog(
            title = if (isFrame) "画面翻译工作流" else "字幕翻译工作流",
            options = (if (isFrame) imageWorkflows else subtitleWorkflows).map { PickerOption(it.id, it.name) },
            selectedId = if (isFrame) frameWorkflowId else subtitleWorkflowId,
            onSelect = { if (isFrame) frameWorkflowId = it else subtitleWorkflowId = it; workflowPicker = null },
            onDismiss = { workflowPicker = null },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, testTag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(testTag).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, localize = false)
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    manualTestTag: String? = null,
    onManual: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    refreshError: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("video-option-picker"),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) {
                    Text("没有可选项目，请先在设置中配置。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(option.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, Modifier.weight(1f), localize = false,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (option.id == selectedId) Text("✓")
                    }
                }
                if (onManual != null) {
                    HorizontalDivider()
                    TextButton(onClick = onManual,
                        modifier = (if (manualTestTag != null) Modifier.testTag(manualTestTag) else Modifier).fillMaxWidth()) {
                        Text("手动输入 BCP-47 代码…")
                    }
                }
            }
        },
        confirmButton = { Row {
            if(onRefresh != null) TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if(refreshing) "正在读取…" else "刷新语言列表") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        } },
        dismissButton = { refreshError?.let { Text("语言列表读取失败，可重试", color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (summary.isNotBlank()) {
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, localize = false)
                    }
                }
                Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameAdvancedSettings(
    settings: VideoFrameSettings,
    onChange: (VideoFrameSettings) -> Unit,
) {
    Text("识别与分组", style = MaterialTheme.typography.titleSmall)
    LabeledSlider(
        label = "Seg 判定阈值",
        value = (settings.segThreshold * 100f).coerceIn(5f, 95f),
        range = 5f..95f,
        steps = 0,
        display = "${(settings.segThreshold * 100f).roundToInt()}%",
    ) { onChange(settings.copy(segThreshold = it / 100f)) }
    LabeledSlider(
        label = "文字检测置信度阈值",
        value = (settings.textDetectionThreshold * 100f).coerceIn(5f, 95f),
        range = 5f..95f,
        steps = 0,
        display = "${(settings.textDetectionThreshold * 100f).roundToInt()}%",
    ) { onChange(settings.copy(textDetectionThreshold = it / 100f)) }
    LabeledSlider(
        label = "游离文字行间合并距离",
        value = (settings.freeTextMergeGapRatio * 100f).coerceIn(0f, 200f),
        range = 0f..200f,
        steps = 0,
        display = "${(settings.freeTextMergeGapRatio * 100f).roundToInt()}%",
    ) { onChange(settings.copy(freeTextMergeGapRatio = it / 100f)) }
    Text("阈值影响之后的新识别；行间合并距离 0 表示逐行独立，100% 表示允许一行字高的间隔。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LabeledSlider(
        label = "视频区域相似度",
        value = settings.similarityPercent.toFloat().coerceIn(1f, 100f),
        range = 1f..100f,
        steps = 0,
        display = "${settings.similarityPercent}%",
    ) { onChange(settings.copy(similarityPercent = it.roundToInt().coerceIn(1, 100))) }
    Text("用于把当前画面区域与上一帧的跟踪结果匹配；默认 60%。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    HorizontalDivider()
    Text("画面遮罩与文字", style = MaterialTheme.typography.titleSmall)
    Text("遮罩背景", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BubbleFillMode.entries.forEach { fill ->
            FilterChip(settings.render.fillMode == fill,
                { onChange(settings.copy(render = settings.render.copy(fillMode = fill))) },
                label = { Text(if (fill == BubbleFillMode.AUTO) "自动取背景色" else "纯白遮盖") })
        }
    }
    LabeledSlider(
        label = "遮盖不透明度",
        value = settings.render.opacityPercent.toFloat(),
        range = 0f..100f,
        steps = 0,
        display = "${settings.render.opacityPercent}%",
    ) { onChange(settings.copy(render = settings.render.copy(opacityPercent = it.roundToInt().coerceIn(0, 100)))) }
    LabeledSlider(
        label = "文字边距",
        value = settings.render.textPaddingPercent.toFloat(),
        range = 0f..20f,
        steps = 0,
        display = "${settings.render.textPaddingPercent}%",
    ) { onChange(settings.copy(render = settings.render.copy(textPaddingPercent = it.roundToInt().coerceIn(0, 20)))) }
    LabeledSlider(
        label = "游离文字遮罩扩张",
        value = settings.render.freeTextMaskExpansionPercent.toFloat(),
        range = 0f..20f,
        steps = 0,
        display = "${settings.render.freeTextMaskExpansionPercent}%",
    ) { onChange(settings.copy(render = settings.render.copy(freeTextMaskExpansionPercent = it.roundToInt().coerceIn(0, 20)))) }
    Text("字体", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BubbleFont.entries.forEach { font ->
            FilterChip(settings.render.font == font,
                { onChange(settings.copy(render = settings.render.copy(font = font))) },
                label = {
                    Text(when (font) {
                        BubbleFont.SYSTEM -> "系统"
                        BubbleFont.SANS_SERIF -> "无衬线"
                        BubbleFont.SERIF -> "衬线"
                        BubbleFont.MONOSPACE -> "等宽"
                    })
                })
        }
    }
    LabeledSlider(
        label = "相对自动字号",
        value = settings.render.fontScalePercent.toFloat(),
        range = 50f..150f,
        steps = 0,
        display = "${settings.render.fontScalePercent}%",
    ) { onChange(settings.copy(render = settings.render.copy(fontScalePercent = it.roundToInt().coerceIn(50, 150)))) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("粗体")
        Switch(settings.render.bold,
            { onChange(settings.copy(render = settings.render.copy(bold = it))) })
    }
    Text("这些值随本视频保存，只用于之后新建的画面任务。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TimecodeField(label: String, value: String, onValueChange: (String) -> Unit, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("例如 5、1:20、1:00:00", localize = false) },
        isError = isError,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class PickerOption(val id: String, val label: String)
private data class FpsChoice(val key: String, val label: String, val interval: String, val rate: VideoSamplingRate)

private val VideoSamplingRate.label: String
    get() = "${formatSamplingNumber(numerator.toDouble() / denominator)} 帧/秒"
private val VideoSamplingRate.interval: String
    get() = formatSamplingNumber(denominator.toDouble() / numerator)
private fun formatSamplingNumber(value: Double) =
    String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

private val FPS_CHOICES = listOf(
    FpsChoice("1/2", "0.5 帧/秒", "2", VideoSamplingRate.HALF_FPS),
    FpsChoice("1/1", "1 帧/秒", "1", VideoSamplingRate.ONE_FPS),
    FpsChoice("2/1", "2 帧/秒", "0.5", VideoSamplingRate.TWO_FPS),
    FpsChoice("5/1", "5 帧/秒", "0.2", VideoSamplingRate.FIVE_FPS),
)
private val VIDEO_MODES = listOf("FRAME", "SUBTITLE", "BOTH")
private val MAX_DIMENSION_RANGE = 128..2048

private fun modeLabel(value: String) = when (value.uppercase(Locale.ROOT)) {
    "FRAME" -> "仅画面"; "SUBTITLE" -> "仅字幕"; "BOTH" -> "两者"; else -> value
}
private fun segTextScopeLabel(scope: SegTextScope) = when (scope) {
    SegTextScope.BUBBLES -> "气泡"
    SegTextScope.FREE_TEXT -> "游离文字"
    SegTextScope.ALL -> "气泡和游离文字"
}
private fun languageLabel(value: String, locale: Locale): String {
    val language = parseLanguage(value) ?: return "无效代码 · $value"
    return "${language.localizedName(locale)} · ${language.tag}"
}
private fun withCurrentLanguage(choices: List<LocalTranslationLanguage>, value: String): List<LocalTranslationLanguage> {
    val parsed = parseLanguage(value) ?: return choices
    return if (choices.any { it.tag == parsed.tag }) choices else listOf(parsed) + choices
}
private fun workflowLabel(id: String, workflows: List<TranslationWorkflow>) =
    workflows.firstOrNull { it.id == id }?.name ?: if (workflows.isEmpty()) "无可用工作流" else "已删除，请重新选择"
private fun apiProfileLabel(id: String, profiles: List<ApiProfile>): String {
    if (id.isBlank()) return "跟随工作流默认"
    val profile = profiles.firstOrNull { it.id == id } ?: return "已删除，请重新选择"
    return apiProfileLabel(profile)
}
private fun apiProfileLabel(profile: ApiProfile) =
    profile.displayName + profile.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
private fun parseLanguage(value: String): LocalTranslationLanguage? =
    try { LocalTranslationLanguage.fromTag(value) } catch (_: Exception) { null }

private fun startSettingsActivity(context: Context, section: String) {
    val intent = Intent(context, MainActivity::class.java)
        .putExtra("open-settings", section)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: Exception) { /* keep the dialog open */ }
}

private fun parseTimecode(value: String): Long? = parseVideoTimecode(value)
private fun formatTimecode(value: Long): String = formatVideoTimecode(value)
private fun endError(value: String, endMs: Long?, startMs: Long?, durationMs: Long) = when {
    endMs == null -> true
    value.isBlank() -> true
    startMs != null && endMs <= startMs -> true
    durationMs > 0 && endMs > durationMs -> true
    else -> false
}

private fun canonicalOptions(value: VideoWorkOptions, durationMs: Long): VideoWorkOptions {
    fun tag(text: String) = try { LocalTranslationLanguage.fromTag(text).tag } catch (_: Exception) { text.trim() }
    return value.copy(
        source = tag(value.source),
        target = tag(value.target),
        mode = value.mode.uppercase(Locale.ROOT),
        subtitleSource = value.subtitleSource.uppercase(Locale.ROOT),
        endMs = value.endMs.takeIf { it > 0 } ?: durationMs.coerceAtLeast(0L),
        // The model is selected globally and resolved from Settings when work starts.
        asrModelId = null,
        audioApiProfileId = value.audioApiProfileId?.takeIf { it.isNotBlank() },
        audioApiEndpoint = value.audioApiEndpoint.trim(),
        frameApiProfileId = value.frameApiProfileId?.takeIf { it.isNotBlank() },
        subtitleApiProfileId = value.subtitleApiProfileId?.takeIf { it.isNotBlank() },
        // Recognition scope and frame settings are task semantics; keep them in the saved/draft comparison.
        segTextScope = value.segTextScope,
        frameSettings = value.frameSettings,
    )
}
