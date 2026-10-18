package com.lmreader.screen

import com.lmreader.ui.translation.forWorkflow

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.PendingIntent
import android.os.Bundle
import android.os.LocaleList
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.core.storage.settings.AppThemeMode
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import com.lmreader.core.translation.TranslationModelCatalog
import com.lmreader.di.AppContainer
import com.lmreader.R
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.theme.LmReaderTheme
import com.lmreader.ui.translation.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

class ScreenTranslationSettingsActivity : ComponentActivity() {
    companion object {
        /** Keep external entry points in the dialog's own task, without raising MainActivity. */
        internal fun intent(context: Context) = Intent(context, ScreenTranslationSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        internal fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 3103, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val container by lazy { AppContainer.from(this) }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createConfigurationContext(android.content.res.Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(GeneralPreferences(newBase).effectiveLanguageTag))
        }))
    }
    override fun onStart() { super.onStart(); container.screenTranslation.setVisible(this, true) }
    override fun onStop() { container.screenTranslation.setVisible(this, false); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptRegionReturn(intent)
        setContent {
            val theme by container.generalPreferences.theme.collectAsStateWithLifecycle()
            LmReaderTheme(darkTheme = when (theme) { AppThemeMode.SYSTEM -> isSystemInDarkTheme(); AppThemeMode.LIGHT -> false; AppThemeMode.DARK -> true }) {
                Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge) {
                    ScreenTranslationSettingsScreen(container, ::finish)
                }
            }
        }
        setFinishOnTouchOutside(true)
        val density = resources.displayMetrics.density
        val available = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (metrics.bounds.width() - insets.left - insets.right) to (metrics.bounds.height() - insets.top - insets.bottom)
        } else resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        window.setLayout(minOf((available.first - 32 * density).toInt().coerceAtLeast(1), (560 * density).toInt()),
            (available.second * .85f).toInt().coerceAtLeast(1))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); acceptRegionReturn(intent)
    }
    private fun acceptRegionReturn(intent: Intent) {
        val token = intent.getStringExtra("region-return-token") ?: return
        val mode = runCatching { ScreenTranslationMode.valueOf(intent.getStringExtra("region-return-mode").orEmpty()) }.getOrNull()
        val selection = intent.getStringExtra("region-return-selection")?.let {
            runCatching { ScreenTranslationPreferences.decodeRegions(JSONObject(it)) }.getOrNull()
        }
        if (mode != null && mode != ScreenTranslationMode.MANUAL && selection != null)
            ScreenRegionEditorResults.latest.value = ScreenRegionEditorResult(token, selection, mode)
        // A later configuration recreation must not replay this editor return.
        intent.removeExtra("region-return-token"); intent.removeExtra("region-return-mode"); intent.removeExtra("region-return-selection")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScreenTranslationSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val controller = container.screenTranslation
    val config by controller.preferences.settings.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val pending by controller.pendingFrames.collectAsStateWithLifecycle()
    val accessibilityAvailable by controller.accessibilityAvailable.collectAsStateWithLifecycle()
    val machine by container.machineTranslationPreferences.settings.collectAsStateWithLifecycle()
    val workflows by container.translationWorkflows.workflows.collectAsStateWithLifecycle()
    val installed by container.translationModels.installedPacks.collectAsStateWithLifecycle()
    val profiles by container.apiProfiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutine = rememberCoroutineScope()
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var regionMode by rememberSaveable { mutableStateOf(ScreenTranslationMode.BROWSE.name) }
    var regionDraft by rememberSaveable { mutableStateOf("") }
    var regionToken by rememberSaveable { mutableStateOf("") }
    val editorResult by ScreenRegionEditorResults.latest.collectAsStateWithLifecycle()
    LaunchedEffect(editorResult) {
        editorResult?.takeIf { !it.consumed }?.let { result ->
            regionMode = result.mode.name; regionToken = result.token
            regionDraft = ScreenTranslationPreferences.encodeRegions(result.selection).toString(); showRegions = true
            ScreenRegionEditorResults.latest.compareAndSet(result, result.copy(consumed = true))
        }
    }
    fun update(change: (ScreenTranslationSettings) -> ScreenTranslationSettings) { coroutine.launch { controller.preferences.update(change) } }
    fun translation(change: (MangaTranslationSettings) -> MangaTranslationSettings) { update { it.copy(translation = change(it.translation)) } }
    val t = config.translation
    if (showRegions) {
        val selectedMode = ScreenTranslationMode.valueOf(regionMode)
        ScreenRegionsDialog(config, controller.preferences, selectedMode, regionDraft, regionToken, changeDraft = { regionDraft = it },
            apply = { selection -> controller.preferences.update { settings ->
                val applied = settings.withRegionSelection(selectedMode, selection)
                // Mode selections are copies. Only an existing library preset
                // is edited; applying a deleted copy must not resurrect it.
                if (settings.regionPresets.any { it.name == selection.name }) applied.copy(regionPresets =
                    settings.regionPresets.map { if (it.name == selection.name) selection else it }) else applied
            }; showRegions = false }, dismiss = { showRegions = false })
    }
    val workflow = workflows.firstOrNull { it.id == (t.workflowId ?: TranslationWorkflow.LOCAL_MACHINE_ID) }
    val local = workflow?.program?.uses(WorkflowKind.TRANSLATE) != false
    val catalog = remember(installed, workflow?.program) { TranslationModelCatalog.fromPacks(installed.values.toList()).forWorkflow(workflow?.program) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val languages = remember(locale) { translationLanguageChoices(java.util.Locale.getISOLanguages().asIterable())
        .sortedBy { it.localizedName(locale) } }
    val sources = if (local) catalog.availableSources() else prioritizeInstalledLanguages(languages, catalog.availableSources())
    val from = matchEngineLanguage(t.sourceLanguage, sources)
    val targets = if (local) from?.let(catalog::availableTargets).orEmpty() else prioritizeInstalledLanguages(languages.filterNot { it == from }, from?.let(catalog::availableTargets).orEmpty())
    var languagePicker by remember { mutableStateOf<Boolean?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openAccessibilitySettings() {
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.screen_translation_accessibility_settings_unavailable, Toast.LENGTH_SHORT).show() }
    }
    languagePicker?.let { source -> AlertDialog(onDismissRequest = { languagePicker = null }, title = { Text(if (source) "原文语言" else "目标语言") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            (if (source) sources else targets).forEach { language -> ListItem(headlineContent = { Text(languageLabel(language)) }, modifier = Modifier.clickable {
                translation { if (source) it.copy(sourceLanguage = language.tag, targetLanguage = it.targetLanguage.takeIf { target -> !local || catalog.availableTargets(language).any { l -> l.tag == target } }) else it.copy(targetLanguage = language.tag) }
                languagePicker = null
            }) }
        } }, confirmButton = { TextButton(onClick = { languagePicker = null }) { Text("关闭") } }) }
    Scaffold(topBar = { TopAppBar(title = { Text("屏幕翻译设置", style = MaterialTheme.typography.titleLarge) },
        actions = { TextButton(onClick = onBack) { Text("关闭") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.status.isNotBlank()) Text(state.status, modifier = Modifier.testTag("screen-translation-status"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.started) controller.stop() else controller.start(context) }) { Text(if (state.started) "□ 停止屏幕翻译" else "▷ 启动屏幕翻译") }
                OutlinedButton(onClick = controller::toggleEngine, enabled = state.started && state.engine != ScreenEngineState.STOPPING) {
                    Text(stringResource(if (state.engine in setOf(ScreenEngineState.LOADING, ScreenEngineState.READY, ScreenEngineState.STOPPING))
                        R.string.screen_translation_stop_engine else R.string.screen_translation_prepare_engine))
                }
            }
            if (pending > 0) OutlinedButton(onClick = controller::retryPendingFrame, enabled = state.started && state.engine == ScreenEngineState.READY && !state.busy) { Text("重试失败画面（$pending）") }
            Text("暂停翻译队列可降低屏幕翻译等待时间。屏幕翻译沿用队列选择的调度策略。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { container.translationQueue.pause() }) { Text("暂停翻译队列") }
            if (state.timing.isNotBlank()) {
                Text(state.timing, style = MaterialTheme.typography.bodySmall)
                Text("各步骤显示累计耗时，并行运行时不能直接相加。", style = MaterialTheme.typography.bodySmall)
            }
            Text("屏幕翻译模式", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ScreenTranslationMode.entries.forEach { mode ->
                FilterChip(config.mode == mode, { update { it.copy(mode = mode) } }, label = { Text(when (mode) { ScreenTranslationMode.MANUAL -> "手动截图"; ScreenTranslationMode.BROWSE -> "浏览"; ScreenTranslationMode.VIDEO -> "视频" }) })
            } }
            if (config.mode != ScreenTranslationMode.MANUAL) {
                OutlinedButton(onClick = {
                    regionMode = config.mode.name; regionToken = java.util.UUID.randomUUID().toString()
                    regionDraft = ScreenTranslationPreferences.encodeRegions(config.regionSelection()).toString(); showRegions = true
                }, modifier = Modifier.testTag("screen-region-select")) { Text("屏幕区域选定") }
                Text("浏览与视频独立保存翻译区；每个方案同时保存竖屏和横屏区域。", style = MaterialTheme.typography.bodySmall)
            }
            Text(when (config.mode) {
                ScreenTranslationMode.MANUAL -> "点一下应用外悬浮球，截图并翻译；翻译时按钮变暗，屏幕移动后清除遮罩。"
                ScreenTranslationMode.BROWSE -> "点一下应用外悬浮球开启连续翻译，再点一下停止。根据画面跟随平移和缩放，只识别新露出的区域。"
                ScreenTranslationMode.VIDEO -> "点一下应用外悬浮球开启连续翻译，再点一下停止。检测所选区域的画面变化，关闭时清除遮罩。"
            }, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.screen_translation_long_press_hint), style = MaterialTheme.typography.bodySmall)
            if (config.mode == ScreenTranslationMode.BROWSE) {
                ScreenChoice("跟随目标帧率", "${config.followFramesPerSecond} FPS", listOf(15, 30, 60).map { it.toString() to "$it FPS" }) { value -> update { it.copy(followFramesPerSecond = value.toInt()) } }
                Text("根据干净画面同时估计平移和缩放。匹配完成后再取下一帧；实际速度受设备性能与截图来源限制，已有译文的跟随独立于翻译。", style = MaterialTheme.typography.bodySmall)
            }
            if (config.requiresAccessibility) {
                Text("浏览和视频模式必须启用无障碍辅助。录屏授权时请选择单个应用；取消录屏授权可使用 Android 14 以上的窗口截图。", style = MaterialTheme.typography.bodySmall)
                Text("单应用录屏流持续接收画面，只在有处理空位时取出识别图；窗口截图受系统限频约束。两种来源均保留可见译文。", style = MaterialTheme.typography.bodySmall)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("优先使用无障碍辅助"); Switch(config.preferAccessibility, { enabled ->
                update { it.copy(preferAccessibility = enabled) }
                if (enabled && !ScreenAccessibilityService.isEnabled(context)) openAccessibilitySettings()
            }) }
            OutlinedButton(onClick = ::openAccessibilitySettings) { Text("打开系统无障碍设置") }
            if (config.requiresAccessibility && !accessibilityAvailable) Text("请先启用无障碍辅助，再启动连续翻译。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            ScreenChoice("翻译工作流", workflow?.name ?: "重新选择工作流", workflows.map { it.id to it.name }) { id -> translation { it.copy(workflowId = id) } }
            if (workflow?.program?.usesApi() == true) ScreenChoice("选择 API", profiles.firstOrNull { it.id == t.apiProfileId }?.name ?: "跟随工作流默认",
                listOf("" to "跟随工作流默认") + profiles.map { it.id to it.name }) { id -> translation { it.copy(apiProfileId = id.ifBlank { null }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { languagePicker = true }) { Text("原文：" + (from?.let { languageLabel(it) } ?: "未选")) }
                OutlinedButton(onClick = { languagePicker = false }) { Text("目标：" + (matchEngineLanguage(t.targetLanguage, targets)?.let { languageLabel(it) } ?: "未选")) }
            }
            if (local) TextButton(onClick = { context.startActivity(Intent(context, com.lmreader.MainActivity::class.java).putExtra("open-queue", "offline").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); onBack() }) { Text("下载离线翻译包") }
            OutlinedTextField(t.customStyle.orEmpty(), { value -> translation { it.copy(customStyle = value) } }, label = { Text("文风") }, modifier = Modifier.fillMaxWidth())
            Text("SEG 提取范围")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SegTextScope.entries.forEach { item -> FilterChip(t.segTextScope == item, { translation { it.copy(segTextScope = item) } }, label = { Text(item.label) }) } }
            if (t.segTextScope == SegTextScope.FREE_TEXT) Text("检测气泡内外的全部文字，以文字框绘制；不加载气泡 SEG 模型。", style = MaterialTheme.typography.bodySmall)
            if (t.segTextScope != SegTextScope.FREE_TEXT) ScreenSlider("Seg 判定阈值", t.effectiveSegThreshold() * 100, 5f..95f, "%") { value -> translation { it.copy(segThreshold = value / 100) } }
            ScreenSlider("文字检测置信度阈值", t.effectiveTextDetectionThreshold() * 100, 5f..95f, "%") { value -> translation { it.copy(textDetectionThreshold = value / 100) } }
            ScreenSlider("游离文字行间合并距离", t.effectiveFreeTextMergeGapRatio() * 100, 0f..200f, "%") { value -> translation { it.copy(freeTextMergeGapRatio = value / 100) } }
            val render = t.effectiveBubbleRender(BubbleRenderSettings())
            ScreenChoice("遮罩背景", if (render.fillMode == BubbleFillMode.WHITE) "纯白遮盖" else "自动取背景色", listOf("AUTO" to "自动取背景色", "WHITE" to "纯白遮盖")) { value -> translation { it.copy(bubbleFillMode = BubbleFillMode.valueOf(value)) } }
            ScreenSlider("遮盖不透明度", render.opacityPercent.toFloat(), 0f..100f, "%") { value -> translation { it.copy(bubbleOpacityPercent = value.toInt()) } }
            ScreenSlider("文字边距", render.textPaddingPercent.toFloat(), 0f..20f, "%") { value -> translation { it.copy(bubbleTextPaddingPercent = value.toInt()) } }
            ScreenSlider("游离文字遮罩扩张", render.freeTextMaskExpansionPercent.toFloat(), 0f..20f, "%") { value -> translation { it.copy(freeTextMaskExpansionPercent = value.toInt()) } }
            val fonts = BubbleFont.entries.map { it.name to when (it) { BubbleFont.SYSTEM -> "系统"; BubbleFont.SANS_SERIF -> "无衬线"; BubbleFont.SERIF -> "衬线"; BubbleFont.MONOSPACE -> "等宽" } }
            ScreenChoice("字体", fonts.first { it.first == render.font.name }.second, fonts) { value -> translation { it.copy(bubbleFont = BubbleFont.valueOf(value)) } }
            ScreenSlider("相对自动字号", render.fontScalePercent.toFloat(), 50f..150f, "%") { value -> translation { it.copy(bubbleFontScalePercent = value.toInt()) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("粗体"); Switch(render.bold, { value -> translation { it.copy(bubbleBold = value) } }) }
            HorizontalDivider(); Text("屏幕翻译独立资源", style = MaterialTheme.typography.titleMedium)
            Text("默认预处理下一画面，最多两张画面同时处理。上一张 SEG/OCR 就绪且提交间隔已到，才取下一张；两张占满时等待完成，缓存不足时暂停取图。", style = MaterialTheme.typography.bodySmall)
            Text("以下 SEG、OCR 和缓存设置只用于屏幕翻译。引擎已启动时，设置和已保存的工作流修改会自动生效，只更新有变化的资源。", style = MaterialTheme.typography.bodySmall)
            ScreenChoice("SEG 并行数", if (config.vision.segConcurrency == 0) "自动" else config.vision.segConcurrency.toString(), (0..8).map { it.toString() to if (it == 0) "自动" else it.toString() }) { value -> update { it.copy(vision = it.vision.copy(segConcurrency = value.toInt())) } }
            ScreenChoice("OCR 并行数", if (config.vision.ocrConcurrency == 0) "自动" else config.vision.ocrConcurrency.toString(), (0..8).map { it.toString() to if (it == 0) "自动" else it.toString() }) { value -> update { it.copy(vision = it.vision.copy(ocrConcurrency = value.toInt())) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SEG GPU 加速"); Switch(config.vision.segGpu, { value -> update { it.copy(vision = it.vision.copy(segGpu = value)) } }) }
            ScreenChoice("OCR 后端", config.vision.ocrBackend.name, OcrBackend.entries.map { it.name to it.name }) { value -> update { it.copy(vision = it.vision.copy(ocrBackend = OcrBackend.valueOf(value))) } }
            ScreenSlider("屏幕 SEG/OCR 缓存", config.cacheMegabytes.toFloat(), 32f..1024f, "MB") { value -> update { it.copy(cacheMegabytes = value.toInt()) } }
            Text("未提交的气泡和 OCR 结果保留完整；超出缓存预算时等待后续步骤消费结果。", style = MaterialTheme.typography.bodySmall)
            Text("机翻共享资源", style = MaterialTheme.typography.titleMedium)
            ScreenChoice("机翻并行数", if (machine.concurrency == 0) "自动" else machine.concurrency.toString(), (0..8).map { it.toString() to if (it == 0) "自动" else it.toString() }) { value -> coroutine.launch { container.machineTranslationPreferences.update { it.copy(concurrency = value.toInt()) } } }
            ScreenSlider("机翻资源预算", machine.memoryMegabytes.toFloat(), 128f..4096f, "MB") { value -> coroutine.launch { container.machineTranslationPreferences.update { it.copy(memoryMegabytes = value.toInt()) } } }
            Text("自动并行数结合 CPU、可用内存和中转路线模型大小计算。资源预算是估算值；单条路线过大时仍允许一个任务运行。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(); Text("响应与区域", style = MaterialTheme.typography.titleMedium)
            ScreenSlider(if (config.mode == ScreenTranslationMode.VIDEO) "截图延时" else "屏幕检测间隔", config.sampleIntervalMillis.toFloat(), 100f..2000f, "ms") { value -> update { it.copy(sampleIntervalMillis = value.toInt()) } }
            if (config.mode == ScreenTranslationMode.BROWSE) {
                ScreenChoice("跟踪算法", if (config.trackingAlgorithm == ScreenTrackingAlgorithm.GLOBAL) "整屏" else "整屏＋逐气泡", listOf(
                    ScreenTrackingAlgorithm.GLOBAL.name to "整屏", ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS.name to "整屏＋逐气泡")) {
                    value -> update { it.copy(trackingAlgorithm = ScreenTrackingAlgorithm.valueOf(value)) }
                }
                Text("逐气泡校验也包含游离文字框，区分固定界面与平移、缩放的正文；连续跟踪不等待画面稳定。", style = MaterialTheme.typography.bodySmall)
                ScreenSlider("识别前稳定等待", config.settleMillis.toFloat(), 0f..2000f, "ms") { value -> update { it.copy(settleMillis = value.toInt()) } }
                ScreenSlider("屏幕外保留边距", config.offscreenMarginPercent.toFloat(), 0f..200f, "%") { value -> update { it.copy(offscreenMarginPercent = value.toInt()) } }
                ScreenSlider("新增区域最小边长", config.minimumNewAreaPixels.toFloat(), 2f..128f, "px") { value -> update { it.copy(minimumNewAreaPixels = value.toInt()) } }
                Text("细小的新区域累积到此宽度后再识别，减少重复计算；单位为识别截图的像素。", style = MaterialTheme.typography.bodySmall)
                ScreenSlider("新旧文字区域合并间距", config.browseMergeDistancePixels.toFloat(), 0f..128f, "px") { value -> update { it.copy(browseMergeDistancePixels = value.toInt()) } }
                Text("新文字区域与已有原文区域的间距不超过此值时，合并原图裁块并重新 SEG，再提交 OCR。单位为识别截图像素；调大更容易合并，也会增加重识别范围。设为 0 时仅合并接触或重叠的区域。", style = MaterialTheme.typography.bodySmall)
                Text("气泡完全离开按屏幕宽、高分别外扩的区域后删除。", style = MaterialTheme.typography.bodySmall)
                Text("平移和缩放持续跟随。画面稳定后仅检测新增区域，按该帧光流后的位置排除已有文字。新 SEG 气泡接近旧气泡时才合并重 SEG；新遮罩接管背景，旧译文保留到对应新译文完成。", style = MaterialTheme.typography.bodySmall)
            }
            if (config.mode == ScreenTranslationMode.MANUAL) ScreenSlider("手动遮罩消失延迟", config.manualClearMillis.toFloat(), 0f..1000f, "ms") { value -> update { it.copy(manualClearMillis = value.toInt()) } }
            if (config.mode == ScreenTranslationMode.VIDEO) {
                Text("最多缓冲两张截图。SEG 完成后才允许下一张截图；延时从上一张 SEG 完成开始，与新一张 SEG 并行计时。缓冲区满时，等待最早一张完成回填再截图。新气泡填出前保留上一张译文。", style = MaterialTheme.typography.bodySmall)
                ScreenSlider("视频 SEG 区域相似度", config.videoSimilarityPercent.toFloat(), 1f..100f, "%") { value -> update { it.copy(videoSimilarityPercent = value.toInt()) } }
                Text("逐区域比较 SEG 形状和原文字纹理；相似度超过此值时复用旧气泡及仍在处理的译文，不重复识别或翻译。值越高越严格，100 时不复用。", style = MaterialTheme.typography.bodySmall)
            }
            ScreenSlider("截图最长边", config.maximumCaptureEdge.toFloat(), 480f..2560f, "px") { value -> update { it.copy(maximumCaptureEdge = value.toInt()) } }
            if (config.mode != ScreenTranslationMode.BROWSE) ScreenSlider("新增区域上下文", config.overlapPixels.toFloat(), 0f..256f, "px") { value -> update { it.copy(overlapPixels = value.toInt()) } }
            if (config.mode != ScreenTranslationMode.VIDEO) ScreenSlider("画面变化阈值", config.changeThresholdPercent.toFloat(), 1f..30f, "%") { value -> update { it.copy(changeThresholdPercent = value.toInt()) } }
            if (config.mode == ScreenTranslationMode.VIDEO) {
                ScreenSlider("视频识别区域上缘", config.videoTopPercent.toFloat(), 0f..(config.videoBottomPercent - 5).toFloat(), "%") { value -> update { it.copy(videoTopPercent = value.toInt()) } }
                ScreenSlider("视频识别区域下缘", config.videoBottomPercent.toFloat(), (config.videoTopPercent + 5).toFloat()..100f, "%") { value -> update { it.copy(videoBottomPercent = value.toInt()) } }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun ScreenRegionsDialog(config: ScreenTranslationSettings, preferences: ScreenTranslationPreferences, mode: ScreenTranslationMode,
    encoded: String, token: String, changeDraft: (String) -> Unit,
    apply: suspend (ScreenRegionSelection) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = remember(encoded) { ScreenTranslationPreferences.decodeRegions(JSONObject(encoded)) }
    var showNew by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf("") }
    var changing by remember { mutableStateOf(false) }
    var actionError by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val contentHeight = (windowHeight - 252.dp).coerceAtLeast(80.dp)
    val scrollColor = MaterialTheme.colorScheme.onSurfaceVariant
    fun edit(landscape: Boolean) {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.moveTaskToBack(true)
        context.startActivity(Intent(context, ScreenRegionEditorActivity::class.java)
            .putExtra("regions", encoded).putExtra("landscape", landscape).putExtra("token", token).putExtra("mode", mode.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    AlertDialog(onDismissRequest = { if (!changing) dismiss() }, title = { Text("屏幕区域选定") }, text = {
        Box(Modifier.fillMaxWidth().heightIn(max = contentHeight)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 14.dp).testTag("screen-region-scroll"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("浏览与视频独立保存翻译区；每个方案同时保存竖屏和横屏区域。")
                ScreenChoice("加载方案", draft.name.ifBlank { "全屏（无区域）" }, listOf("" to "全屏（无区域）") + config.regionPresets.map { it.name to it.name }) { name ->
                    val selected = config.regionPresets.firstOrNull { it.name == name } ?: ScreenRegionSelection()
                    changeDraft(ScreenTranslationPreferences.encodeRegions(selected).toString())
                }
                OutlinedButton(onClick = { edit(false) }, enabled = !changing, modifier = Modifier.testTag("screen-region-portrait")) { Text("编辑竖屏区域") }
                OutlinedButton(onClick = { edit(true) }, enabled = !changing, modifier = Modifier.testTag("screen-region-landscape")) { Text("编辑横屏区域") }
                Text("竖屏 ${draft.portrait.size}/8 · 横屏 ${draft.landscape.size}/8")
                Text("没有矩形框时翻译全屏")
                Text("点击“应用区域”会保存当前方案，并仅应用到本模式。", style = MaterialTheme.typography.bodySmall)
                Text("删除方案不会更改已经应用的翻译区。", style = MaterialTheme.typography.bodySmall)
                if (actionError.isNotEmpty()) Text(actionError, color = MaterialTheme.colorScheme.error)
            }
            // Keep a visible, draggable scrollbar on small landscape viewports.
            Box(Modifier.matchParentSize()) {
                Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp).testTag("screen-region-scrollbar")
                    .pointerInput(scroll) {
                        detectVerticalDragGestures { change, amount ->
                            if (scroll.maxValue > 0) {
                                change.consume()
                                val height = size.height.toFloat()
                                val thumb = maxOf(24.dp.toPx(), height * height / (height + scroll.maxValue)).coerceAtMost(height)
                                scope.launch { scroll.scrollTo((scroll.value + amount * scroll.maxValue / maxOf(1f, height - thumb)).roundToInt()) }
                            }
                        }
                    }) {
                    if (scroll.maxValue > 0) {
                        val width = 4.dp.toPx(); val x = (size.width - width) / 2
                        val thumb = maxOf(24.dp.toPx(), size.height * size.height / (size.height + scroll.maxValue)).coerceAtMost(size.height)
                        val top = (size.height - thumb) * scroll.value / scroll.maxValue
                        drawRoundRect(scrollColor.copy(alpha = .15f), Offset(x, 0f), Size(width, size.height))
                        drawRoundRect(scrollColor.copy(alpha = .7f), Offset(x, top), Size(width, thumb))
                    }
                }
            }
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.Start) {
                TextButton(onClick = { newName = ""; nameError = ""; showNew = true }, enabled = !changing,
                    modifier = Modifier.testTag("screen-region-new")) { Text("新建") }
                TextButton(onClick = {
                    changing = true; actionError = ""
                    scope.launch {
                        try {
                            preferences.update { it.copy(regionPresets = it.regionPresets.filterNot { preset -> preset.name == draft.name }) }
                            changeDraft(ScreenTranslationPreferences.encodeRegions(ScreenRegionSelection()).toString())
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            actionError = "方案更新失败，请重试"
                        } finally { changing = false }
                    }
                }, enabled = !changing && config.regionPresets.any { it.name == draft.name },
                    modifier = Modifier.testTag("screen-region-delete")) { Text("删除当前方案") }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    changing = true; actionError = ""
                    scope.launch {
                        try { apply(draft) }
                        catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            actionError = "方案更新失败，请重试"
                        } finally { changing = false }
                    }
                }, enabled = !changing) { Text("应用区域") }
                TextButton(onClick = dismiss, enabled = !changing) { Text("取消") }
            }
        }
    })
    if (showNew) AlertDialog(onDismissRequest = { if (!changing) showNew = false }, title = { Text("新建方案") }, text = {
        OutlinedTextField(newName, { newName = it.take(80); nameError = "" }, singleLine = true,
            label = { Text("方案名称") }, isError = nameError.isNotEmpty(),
            supportingText = { if (nameError.isNotEmpty()) Text(nameError) }, modifier = Modifier.testTag("screen-region-new-name"))
    }, confirmButton = {
        TextButton(onClick = {
            val name = newName.trim()
            if (name.isEmpty()) { nameError = "请输入方案名称"; return@TextButton }
            changing = true
            scope.launch {
                try {
                    var created = false
                    val preset = ScreenRegionSelection(name)
                    preferences.update { settings ->
                        if (settings.regionPresets.any { it.name == name }) settings else {
                            created = true; settings.copy(regionPresets = settings.regionPresets + preset)
                        }
                    }
                    if (created) {
                        changeDraft(ScreenTranslationPreferences.encodeRegions(preset).toString()); showNew = false
                    } else nameError = "同名方案已存在"
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    nameError = "方案更新失败，请重试"
                } finally { changing = false }
            }
        }, enabled = !changing, modifier = Modifier.testTag("screen-region-create")) { Text("新建") }
    }, dismissButton = { TextButton(onClick = { showNew = false }, enabled = !changing,
        modifier = Modifier.testTag("screen-region-create-cancel")) { Text("取消") } })
}

@Composable private fun ScreenChoice(title: String, selected: String, items: List<Pair<String, String>>, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(title)
    Box { OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("screen-choice:$title")) { Text(selected) }
        DropdownMenu(open, { open = false }) { items.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { choose(id); open = false }) } }
    }
}

@Composable private fun ScreenSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, save: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Text("$title ${draft.toInt()} $unit")
    Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { save(draft) }, modifier = Modifier.testTag("screen-setting:$title"))
}
