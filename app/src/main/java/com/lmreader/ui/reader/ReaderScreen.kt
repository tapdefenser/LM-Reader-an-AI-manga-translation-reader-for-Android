package com.lmreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.R
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.WorkflowKind
import com.lmreader.ui.reader.translation.*
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.effectiveBubbleRender
import com.lmreader.core.model.translationSetupComplete
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapAction
import com.lmreader.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * 阅读器宿主（开发文档 12）。
 *
 * 三层结构：
 * 1. 本文件：状态、控制栏、点按遮罩、章节导航与错误处理；
 * 2. [PagerReader]：三种分页模式（左到右、右到左、竖向分页）；
 * 3. [StripReader]：两种连续模式（条漫、条漫带间隔）。
 *
 * 分派依据只有 [com.lmreader.core.model.ReaderSettings.readingMode]，UI 不自己判断
 * "是不是条漫"——Mihon 用 `ReadingMode.toViewer` 做同一件事。
 *
 * ## 页面渲染交给引擎
 *
 * 缩放、平移、分块解码与裁白边由 [EnginePageView] 内的 SubsamplingScaleImageView
 * 承担——那也是 Mihon 用的引擎。本文件**不含任何变换数学**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    mangaId: String,
    chapterId: String,
    /** 从哪一页开始；[ReaderViewModel.NO_START_PAGE] 表示没指定。 */
    startPage: Int = ReaderViewModel.NO_START_PAGE,
    onBack: () -> Unit,
    onOpenTranslationOptions: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        // key 里带上起始页：不同起始页是不同的阅读会话，复用同一个 ViewModel 会让
        // 后一次打开沿用前一次的页码。
        key = "$mangaId:$chapterId:$startPage",
        factory = ReaderViewModel.factory(container, mangaId, chapterId, startPage),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val translationViewModel: ReaderPageTranslationViewModel = viewModel(
        key="page-translation:$mangaId:$chapterId:$startPage",
        factory=viewModelFactory {initializer {ReaderPageTranslationViewModel(container,mangaId)}})
    val translations by translationViewModel.state.collectAsStateWithLifecycle()
    val legacyRender by container.bubbleRenderPreferences.settings.collectAsStateWithLifecycle(initialValue=BubbleRenderSettings())
    var mangaOptions by remember(mangaId) { mutableStateOf(MangaTranslationSettings()) }
    val optionScope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        optionScope.launch {
            mangaOptions = container.mangaRepository.translationSettings(mangaId)
        }
    }
    val renderSettings = mangaOptions.effectiveBubbleRender(legacyRender)
    val currentItem=state.items.getOrNull(state.currentPageIndex) as? ReaderItem.PageItem
    val nearbyPages=state.items.drop((state.currentPageIndex-3).coerceAtLeast(0)).take(7).filterIsInstance<ReaderItem.PageItem>()
    var translatingItem by remember {mutableStateOf<ReaderItem.PageItem?>(null)}
    LaunchedEffect(currentItem?.page?.pageId,nearbyPages.map {it.page.pageId}) {translationViewModel.showPage(currentItem,renderSettings,nearbyPages)}
    LaunchedEffect(currentItem?.page?.pageId, renderSettings) {
        container.translationQueue.pageUpdates.collect { pageId ->
            if (currentItem?.page?.pageId == pageId || nearbyPages.any { it.page.pageId == pageId }) {
                translationViewModel.showPage(currentItem, renderSettings, nearbyPages)
            }
        }
    }
    DisposableEffect(viewModel,translationViewModel) {
        viewModel.navigationGuard=translationViewModel::requestNavigation
        viewModel.navigationBlocked={translationViewModel.state.value.confirmNavigation || translationViewModel.state.value.savingEdits}
        onDispose {viewModel.navigationGuard=null;viewModel.navigationBlocked={false};translationViewModel.cancel()}
    }
    val context=LocalContext.current
    val cancelledMessage=stringResource(R.string.reader_mt_cancelled)
    LaunchedEffect(translations.cancelled) {if(translations.cancelled) {
        android.widget.Toast.makeText(context,cancelledMessage,android.widget.Toast.LENGTH_SHORT).show()
        translationViewModel.clearMessage()
    }}
    val completionMessage=translations.completedRegions?.let {count ->stringResource(
        if(count==0) R.string.reader_mt_empty else R.string.reader_mt_completed,count)}
    LaunchedEffect(completionMessage) {if(completionMessage!=null) {
        android.widget.Toast.makeText(context,completionMessage,android.widget.Toast.LENGTH_SHORT).show()
        translationViewModel.clearMessage()
    }}
    val measureHeightDp = rememberStripHeightMeasurer()

    val leave: () -> Unit = {
        translationViewModel.requestNavigation { viewModel.saveProgress(); onBack() }
    }
    BackHandler(onBack = leave)

    // 音量键翻页（Mihon `reader_volume_keys`，默认关）。
    //
    // 两个必须照搬的约束：
    // 1. **只在控制栏隐藏时生效**——控制栏可见时用户多半在看菜单，此时吃掉音量键会
    //    让"调音量"这个原始功能失灵；
    // 2. 只在 `KeyUp` 响应，否则按住不放会连续翻很多页。
    // 另外这里响应的是**阅读顺序**而不是物理键：`volumeKeysInverted` 交换两者。
    val volumeKeyModifier = if (state.settings.volumeKeys) {
        Modifier.onPreviewKeyEvent { event ->
            if (state.chromeVisible || event.type != KeyEventType.KeyUp) {
                return@onPreviewKeyEvent false
            }
            val forward = when (event.key) {
                Key.VolumeDown -> !state.settings.volumeKeysInverted
                Key.VolumeUp -> state.settings.volumeKeysInverted
                else -> return@onPreviewKeyEvent false
            }
            viewModel.move(if (forward) 1 else -1)
            true
        }
    } else {
        Modifier
    }

    // 显示效果（亮度/灰度/反色/全屏/常亮）包在最外层：它们都是**整屏**作用，
    // 必须覆盖页面、控制栏与遮罩全部内容，而不是只作用于页面。
    ReaderDisplayEffects(settings = state.settings, modifier = Modifier.fillMaxSize()) {
        var settingsOpen by remember { mutableStateOf(false) }
        ReaderChrome(
            state = state,
            viewModel = viewModel,
            onLeave = leave,
            volumeKeyModifier = volumeKeyModifier,
            measureHeightDp = measureHeightDp,
            prefetcher = container.pagePrefetcher,
            onOpenSettings = { settingsOpen = true },
            translations = translations,
            translationViewModel = translationViewModel,
            renderSettings = renderSettings,
            onBubbleSelected = {pageId,id ->
                if(pageId==currentItem?.page?.pageId) translationViewModel.selectBubble(pageId,id)
                else translationViewModel.requestNavigation {viewModel.focusPage(pageId);translationViewModel.selectBubble(pageId,id)}
            },
            onTranslate = { translationViewModel.requestNavigation {
                if (translationSetupComplete(mangaOptions.sourceLanguage, mangaOptions.targetLanguage,
                        mangaOptions.autoDetectSource)) translatingItem=currentItem
                else onOpenTranslationOptions()
            } },
            onToggleOriginal = {currentItem?.let {translationViewModel.toggleOriginal(it.page.pageId)}},
            onCancelTranslation = translationViewModel::cancel,
            onBubbleSettings = {translationViewModel.requestNavigation(onOpenTranslationOptions)},
        )

        if (settingsOpen) {
            ReaderSettingsDialog(
                state = state,
                onDismiss = { settingsOpen = false },
                onReadingMode = {mode ->translationViewModel.requestNavigation {viewModel.setReadingMode(mode)}},
                onOrientation = viewModel::setOrientation,
                onUpdateGlobal = viewModel::updateGlobalSettings,
            )
        }
        translatingItem?.let {item ->PageTranslationDialog(container,
            com.lmreader.ui.translation.matchEngineLanguage(mangaOptions.sourceLanguage, container.translationModels.installedCatalog().languages)
                ?: runCatching { LocalTranslationLanguage.fromTag(mangaOptions.sourceLanguage.orEmpty()) }.getOrNull(),
            com.lmreader.ui.translation.matchEngineLanguage(mangaOptions.targetLanguage, container.translationModels.installedCatalog().languages)
                ?: runCatching { LocalTranslationLanguage.fromTag(mangaOptions.targetLanguage.orEmpty()) }.getOrDefault(translations.target),
            onDismiss={translatingItem=null},onTranslate={source,target ->
                translatingItem=null;translationViewModel.translate(item,source,target,renderSettings)
            }, requiresLocalModels = container.translationWorkflows.find(mangaOptions.workflowId)?.program?.uses(WorkflowKind.TRANSLATE) != false)}
        translations.failure?.let {failure ->androidx.compose.material3.AlertDialog(onDismissRequest=translationViewModel::clearMessage,
            title={Text(stringResource(R.string.reader_mt_action))},text={Text(stringResource(R.string.reader_mt_failed,failure))},
            confirmButton={androidx.compose.material3.TextButton(onClick=translationViewModel::clearMessage) {Text(stringResource(R.string.local_mt_close))}})}
        BubbleDraftNavigationDialog(translations,translationViewModel::saveAndNavigate,
            translationViewModel::discardAndNavigate,translationViewModel::cancelNavigation)
    }

    // 点按区域提示：**每次切换阅读方式时显示一次**。
    //
    // 为什么不是"只在首次进入时显示"：分区表随阅读方式变化（默认布局下横向是左右两栏、
    // 竖向是 L 形），用户换了方式之后看到的就不是同一套分区了，此时不提示等于让他自己猜。
    // 为什么也不是"常驻"：它是解释性内容，一直在屏幕上会挡住页面。
    //
    // `modeHintShown` 从 null 开始，因此**第一次进入也会提示一次**（那时还没有"上一次的
    // 方式"可比）。`showTapZoneOverlayOnce` 关掉即完全不提示——那是给老用户的开关。
    var modeHintShown by remember { mutableStateOf<ReadingMode?>(null) }
    LaunchedEffect(state.items.isNotEmpty(), state.readingMode) {
        if (state.items.isEmpty()) return@LaunchedEffect
        if (!state.settings.showTapZoneOverlayOnce) return@LaunchedEffect
        if (modeHintShown == state.readingMode) return@LaunchedEffect
        modeHintShown = state.readingMode
        viewModel.showTapZoneOverlay()
        kotlinx.coroutines.delay(TAP_ZONE_OVERLAY_MILLIS)
        viewModel.hideTapZoneOverlay()
    }
}

/**
 * 阅读器主体：内容 + 浮层控制栏 + 点按遮罩。
 *
 * 用**不透明的 [Surface]** 而不是 `Modifier.background` 铺底。这是加固而非已验证的修复：
 * 宿主 Activity 用了 `enableEdgeToEdge()`，窗口背景是透明的，而 `Modifier.background`
 * 只负责画这个节点的矩形；Surface 会真正承担背景绘制与裁剪，比一个 background 修饰符
 * 可靠——Mihon 也是用带主题背景的宿主 View 而不是裸布局。
 *
 * ⚠️ **未解决的缺陷**：MuMu 模拟器上出现过"阅读器打开后页面不画、下层页面内容整体透出"。
 * 已有的硬证据是：**语义树里只有阅读器节点（完整正确），截图却绝大部分是下层页面**。
 * 两者矛盾，因此怀疑是模拟器的合成/截图问题而不是应用逻辑，但**根因未确认**。
 * 上述 Surface 改动与诊断色实验（根布局染洋红、引擎视图染青）都没有改变截图结果，
 * 洋红与青的采样数都是 0。**需要一台可用真机来判定**。详见阶段交接文档。
 */
@Composable
private fun ReaderChrome(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onLeave: () -> Unit,
    volumeKeyModifier: Modifier,
    measureHeightDp: suspend (ReaderItem.PageItem, Float) -> Int?,
    prefetcher: PagePrefetcher?,
    onOpenSettings: () -> Unit,
    translations: ReaderTranslationUiState,
    translationViewModel: ReaderPageTranslationViewModel,
    renderSettings: BubbleRenderSettings,
    onBubbleSelected: (String,String?) -> Unit,
    onTranslate: () -> Unit,
    onToggleOriginal: () -> Unit,
    onCancelTranslation: () -> Unit,
    onBubbleSettings: () -> Unit,
) {
    Surface(
        color = backgroundFor(state.settings.theme),
        modifier = Modifier.fillMaxSize().then(volumeKeyModifier),
    ) {
        // 内层 Box 同时承担两件事：让阅读内容占满整屏，并为浮层提供对齐作用域。
        //
        // 内容占满整屏是必须的：若它被控制栏挤小，点按区域的归一化基准就不是屏幕，
        // Mihon 那套 0.33/0.66 分区会整体偏移。
        Box(modifier = Modifier.fillMaxSize()) {
            ReaderContent(
                state = state,
                viewModel = viewModel,
                onLeave = onLeave,
                measureHeightDp = measureHeightDp,
                prefetcher = prefetcher,
                translations = translations,
                renderSettings = renderSettings,
                onBubbleSelected = onBubbleSelected,
            )

            if (state.tapZoneOverlayVisible) {
                TapZoneOverlay(state = state, onDismiss = viewModel::hideTapZoneOverlay)
            }

            // 页码指示器（Mihon `ReaderPageIndicator`）：只在控制栏**隐藏**时显示。
            // 控制栏可见时它自己的滑杆已经给出页码，两者同时显示会互相干扰。
            if (!state.chromeVisible &&
                state.settings.showPageNumber &&
                state.error == null &&
                state.currentPageCount > 0
            ) {
                PageIndicator(
                    current = displayPageNumber(state.localPageIndex ?: 0, state.currentPageCount),
                    total = state.currentPageCount,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            if (state.chromeVisible && state.error == null && state.items.isNotEmpty()) {
                ReaderTopBar(state, onLeave, modifier = Modifier.align(Alignment.TopCenter))
                ReaderBottomBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    translations = translations,
                    onTranslate = onTranslate,
                    onToggleOriginal = onToggleOriginal,
                    onCancelTranslation = onCancelTranslation,
                    onBubbleSettings = onBubbleSettings,
                    onToggleEditing = translationViewModel::toggleEditing,
                    onClearPage = { (state.items.getOrNull(state.currentPageIndex) as? ReaderItem.PageItem)?.let(translationViewModel::clearPage) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            ReaderTranslationProgress(translations,onCancelTranslation,Modifier.align(Alignment.BottomCenter)
                .padding(start=16.dp,end=16.dp,bottom=if(state.chromeVisible) 164.dp else 40.dp).fillMaxWidth())
            ReaderBubbleEditor(translations,translationViewModel::editText,translationViewModel::deleteBubble,
                {translationViewModel.saveEdits()},translationViewModel::undoEdit,translationViewModel::toggleEditing,
                translationViewModel::clearEditFailure,Modifier.align(Alignment.TopEnd)
                    .padding(top=if(state.chromeVisible) 80.dp else 12.dp,end=12.dp))
        }
    }
}

/** 阅读内容本身：加载中 / 错误 / 分页或条漫。 */
@Composable
private fun ReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onLeave: () -> Unit,
    measureHeightDp: suspend (ReaderItem.PageItem, Float) -> Int?,
    prefetcher: PagePrefetcher?,
    translations: ReaderTranslationUiState,
    renderSettings: BubbleRenderSettings,
    onBubbleSelected: (String,String?) -> Unit,
) {
    when {
        state.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.error != null -> ReaderError(state.error.orEmpty(), viewModel::reload, onLeave)

        state.items.isNotEmpty() -> {
            // 只在**阅读方式**变化时重建阅读组件：分页器与条带是两套不同的实现。
            //
            // ⚠️ 这里**不要**再把 `currentChapterId` 放进 key。它是这个"跨章闪黑一帧"的根因：
            // 章与章的边界正好是 `currentChapterId` 变化的那一刻，于是整棵阅读组件树被销毁
            // 重建，所有已显示页面的引擎视图一起重建、解码从头再来，其中就包括读者正在看的
            // 那一页——那一帧什么都画不出来，露出底色。章内该 key 不变，所以章内不闪。
            //
            // 旧模型（分页器只覆盖当前章）确实需要换章重建；现在分页器覆盖**整条直线**、
            // 项按 `pageId` 做 key，因此它自己能正确处理跨章，不需要重建。
            key(state.readingMode) {
                // 阅读器销毁（退出 / 换模式）时强制解除"滚动中"，让挂起的窗口能被应用。
                DisposableEffect(Unit) {
                    onDispose { viewModel.onScrollingChanged(false) }
                }
                if (state.isContinuous) {
                    StripReader(
                        items = state.items,
                        mode = state.readingMode,
                        settings = state.settings,
                        currentIndex = state.currentPageIndex,
                        positionKey = state.positionKey,
                        positionSyncToken = state.positionSyncToken,
                        forcePositionSync = state.forcePositionSync,
                        onItemSettled = viewModel::onItemSettled,
                        onScrollingChanged = viewModel::onScrollingChanged,
                        onPageHeightMeasured = viewModel::onPageHeightMeasured,
                        measureHeightDp = measureHeightDp,
                        onTap = viewModel::onTap,
                        onTransitionAction = { viewModel.retryFailedChapters() },
                        prefetcher = prefetcher,
                        translations = translations,
                        renderSettings = renderSettings,
                        onBubbleSelected = onBubbleSelected,
                        onScrollDelta = { delta ->
                            // 只在控制栏可见时判断，避免已在隐藏状态下反复调用。
                            if (state.chromeVisible &&
                                kotlin.math.abs(delta) > state.settings.hideThreshold.thresholdPx
                            ) {
                                viewModel.toggleChrome()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PagerReader(
                        items = state.items,
                        settings = state.settings,
                        currentIndex = state.currentPageIndex,
                        positionKey = state.positionKey,
                        positionSyncToken = state.positionSyncToken,
                        onItemSettled = viewModel::onItemSettled,
                        onScrollingChanged = viewModel::onScrollingChanged,
                        onTap = viewModel::onTap,
                        onTransitionAction = { viewModel.retryFailedChapters() },
                        prefetcher = prefetcher,
                        translations = translations,
                        renderSettings = renderSettings,
                        onBubbleSelected = onBubbleSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 页码指示器（Mihon `ReaderPageIndicator`）。
 *
 * 文案格式照搬 Mihon：`当前页 / 总页数`，不含百分比——百分比在章节滑杆上。
 * 页码是**章内**页码，与滑杆同一套换算（[displayPageNumber]），否则两处会显示不同的数。
 *
 * 与 Mihon 的实现差异：它用"描边文字叠实心文字"保证任何背景上都可读；这里用半透明
 * 深色圆角底片。效果等价（都保证可读）而少一次文本测量与绘制，并且顺带避免了在纯白
 * 页面上白字看不见。
 */
@Composable
private fun PageIndicator(current: Int, total: Int, modifier: Modifier) {
    if (current <= 0 || total <= 0) return
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = "$current / $total",
            color = Color(0xEB, 0xEB, 0xEB),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(state: ReaderUiState, onBack: () -> Unit, modifier: Modifier) {
    TopAppBar(
        title = {
            Column {
                Text(state.mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // 显示当前模式：同一部漫画在不同模式下页码位置不同，用户需要一处能
                    // 确认"现在是哪个模式"的地方（Mihon `pref_show_reading_mode`）。
                    "${state.currentChapter?.title.orEmpty()} · ${state.readingMode.label}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.72f),
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onOpenSettings: () -> Unit,
    translations: ReaderTranslationUiState,
    onTranslate: () -> Unit,
    onToggleOriginal: () -> Unit,
    onCancelTranslation: () -> Unit,
    onBubbleSettings: () -> Unit,
    onToggleEditing: () -> Unit,
    onClearPage: () -> Unit,
    modifier: Modifier,
) {
    val pageCount = state.currentPageCount
    // 过渡页上"页码相关的一律置零置灰"（用户要求）：滑杆显示 0/0 且不可拖，
    // 页码文字隐藏。过渡页本身没有任何按钮——它就是夹在中间的一张图。
    val onTransition = state.currentItemIsTransition
    val localPage = state.localPageIndex
    var sliderValue by remember(state.chapters?.currentChapterId) {
        mutableFloatStateOf(pageFraction(localPage ?: 0, pageCount))
    }
    LaunchedEffect(localPage, pageCount, onTransition, state.positionSyncToken) {
        sliderValue = if (onTransition) 0f else pageFraction(localPage ?: 0, pageCount)
    }
    Surface(color = Color.Black.copy(alpha = 0.76f), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 贴屏幕底边会被系统导航栏/手势条压住：按钮恰好落在手势区内，
                // 点它反而触发"回到桌面"。必须留出导航栏高度。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
            Slider(
                modifier=Modifier.weight(1f),
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.jumpToPage(pageFromFraction(sliderValue, pageCount))
                },
                valueRange = 0f..1f,
                // 页数不足两页时滑杆没有可移动区间，禁用而不是让 steps 变成负数
                // （早前真机因为 -1 上限崩溃过）。过渡页上同样禁用。
                enabled = pageCount > 1 && !onTransition,
            )
            val pageId=(state.items.getOrNull(state.currentPageIndex) as? ReaderItem.PageItem)?.page?.pageId
            ReaderTranslationButton(pageId!=null && !translations.clearingPage && !translations.savingEdits,
                pageId in translations.pages,pageId in translations.originals,translations.progress!=null,
                onTranslate,onToggleOriginal,onCancelTranslation,onBubbleSettings,translations.editing,onToggleEditing,onClearPage)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.jumpToAdjacentChapter(forward = false) },
                    enabled = state.hasPreviousChapter,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一章",
                        tint = if (state.hasPreviousChapter) Color.White else Color.Gray,
                    )
                }
                Text(
                    // 过渡页上不显示页码：它不属于任何一页。
                    text = if (onTransition) "0 / 0" else "${displayPageNumber(localPage ?: 0, pageCount)} / $pageCount",
                    color = if (onTransition) Color.Gray else Color.White,
                )
                IconButton(
                    onClick = { viewModel.jumpToAdjacentChapter(forward = true) },
                    enabled = state.hasNextChapter,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一章",
                        tint = if (state.hasNextChapter) Color.White else Color.Gray,
                    )
                }
                // Mihon 底部栏的四个按钮里就有设置入口；没有它的话，所有阅读设置都只能
                // 在阅读器之外改，而"这部漫画"的覆盖又必须在阅读器里设。
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "阅读设置", tint = Color.White)
                }
            }
        }
    }
}

/**
 * 点按区域遮罩层（Mihon `ReaderNavigationOverlayView`）。
 *
 * 首次进入阅读器时短暂显示，让用户知道每一块点击区会做什么；任意点击即消退。
 * 区域矩形与颜色取自 [NavigationRegions]，与命中检测**共用同一份数据**，因此画出来的
 * 分区一定等于实际生效的分区——这正是 Mihon 把这个视图绑在 `ViewerNavigation` 上的原因。
 */
@Composable
private fun TapZoneOverlay(state: ReaderUiState, onDismiss: () -> Unit) {
    val regions = NavigationRegions.resolve(state.settings.tapZones, state.readingMode)
    val invert = state.settings.tapInvert
    // Paint 复用：Canvas 的绘制 lambda 会随滚动/重组频繁执行，每次 new 一个 Paint
    // 会产生可见的分配压力。
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 64f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInputTap(onDismiss),
    ) {
        if (regions.isEmpty()) {
            Text(
                text = "点按区域已禁用：点击任意位置显示/隐藏控制栏",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            for ((rect, action) in regions) {
                val mirrored = rect.invert(invert)
                val left = mirrored.left * size.width
                val top = mirrored.top * size.height
                val right = mirrored.right * size.width
                val bottom = mirrored.bottom * size.height
                drawRect(
                    color = action.overlayColor(),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                )
                val label = action.overlayLabel()
                val textWidth = labelPaint.measureText(label)
                val baseline = top + (bottom - top) / 2f -
                    (labelPaint.descent() + labelPaint.ascent()) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        label,
                        left + ((right - left) - textWidth) / 2f,
                        baseline,
                        labelPaint,
                    )
                }
            }
        }
        Text(
            text = "点击任意位置关闭",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}

/** 遮罩层的分区配色，照搬 Mihon `ViewerNavigation.NavigationRegion` 的 ARGB。 */
private fun TapAction.overlayColor(): Color = when (this) {
    TapAction.MENU -> Color(0xCC, 0x95, 0x81, 0x8D)
    TapAction.PREVIOUS -> Color(0xCC, 0xFF, 0x77, 0x33)
    TapAction.NEXT -> Color(0xCC, 0x84, 0xE2, 0x96)
    TapAction.PAN_LEFT -> Color(0xCC, 0x7D, 0x11, 0x28)
    TapAction.PAN_RIGHT -> Color(0xCC, 0xA6, 0xCF, 0xD5)
}

/** 遮罩层上的区域名，取自开发文档 12「点按」的用词。 */
private fun TapAction.overlayLabel(): String = when (this) {
    TapAction.MENU -> "菜单"
    TapAction.PREVIOUS -> "上一页"
    TapAction.NEXT -> "下一页"
    TapAction.PAN_LEFT -> "左移"
    TapAction.PAN_RIGHT -> "右移"
}

/** 遮罩层的"点击任意处关闭"，与区域命中无关。 */
private fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(onTap) { detectTapGestures { onTap() } })

@Composable
private fun ReaderError(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 阅读背景色（Mihon `pref_reader_theme_key`）。 */
private fun backgroundFor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.BLACK -> Color.Black
    ReaderTheme.GRAY -> Color(0xFF303030)
    ReaderTheme.WHITE -> Color.White
    ReaderTheme.AUTO -> Color.Black
}

/** 首次进入时点按区域遮罩的显示时长。 */
private const val TAP_ZONE_OVERLAY_MILLIS = 1800L
