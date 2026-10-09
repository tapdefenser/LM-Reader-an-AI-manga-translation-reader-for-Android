package com.lmreader.ui.detail

import com.lmreader.ui.i18n.showLocalizedSnackbar
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChapterTranslation
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.TranslationState
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.CoverImage
import com.lmreader.ui.common.CoverRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 点章节行时从哪一页打开。
 *
 * - 这一章**就是进度里正在读的那一章**且读到过非首页 → 从那一页继续；
 * - 否则返回 null，表示"从头开始"。
 *
 * 为什么用 null 而不是 0 表示"从头"：导航参数是 Int，而 0 是合法的第一页。
 * 用可空把"用户明确要第一页"与"没有指定"分开，避免以后加"跳到某页"时混淆。
 */
private fun resumePageFor(isCurrentChapter: Boolean, readPage: Int): Int? =
    if (isCurrentChapter && readPage > 0) readPage else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    container: AppContainer,
    mangaId: String,
    onBack: () -> Unit,
    onReadChapter: (chapterId: String?, startPage: Int?) -> Unit,
    onOpenTranslationOptions: (prompt: Boolean) -> Unit,
    onOpenGlossary: () -> Unit,
    viewModel: MangaDetailViewModel = viewModel(
        key = mangaId,
        factory = MangaDetailViewModel.factory(container, mangaId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportScope = rememberCoroutineScope()
    val enqueueExport: (List<String>) -> Unit = { ids ->
        exportScope.launch {
            runCatching { container.exportQueue.enqueue(mangaId, ids) }
                .onSuccess { count -> snackbarHostState.showLocalizedSnackbar(context, if (count > 0) "已加入导出队列：$count 章" else "所选章节已在导出队列中") }
                .onFailure { snackbarHostState.showLocalizedSnackbar(context, it.message ?: "加入导出队列失败") }
        }
    }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showLocalizedSnackbar(context, it)
            viewModel.consumeMessage()
        }
    }

    // 原文语言还没选时（用户口径）：把用户带到「翻译选项」页，提示语由**那一页自己**弹
    // （见 TranslationOptionsScreen.showSetupPrompt）——详情页的 snackbar 会随导航
    // 立刻消失。填完之后**不自动续跑**，让他自己再点一次翻译——见
    // MangaDetailViewModel.enqueue 的说明。
    //
    // 用一次性事件（openTranslationOptions）而不是持续状态：否则从那一页返回时
    // LaunchedEffect 会因为状态仍为 true 再弹一次，用户会觉得"怎么又跳走了"。
    LaunchedEffect(state.openTranslationOptions) {
        if (state.openTranslationOptions) {
            viewModel.consumeOpenTranslationOptions()
            onOpenTranslationOptions(true)
        }
    }

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("选择书架分类") },
            text = {
                LazyColumn {
                    items(state.categories, key = { it.categoryId }) { category ->
                        TextButton(
                            onClick = {
                                showCategoryDialog = false
                                viewModel.addToShelf(category.categoryId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(category.name, modifier = Modifier.fillMaxWidth(), localize = false)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("取消") }
            },
        )
    }

    if (showSortSheet) {
        ChapterSortSheet(
            setting = state.orderSetting,
            manual = state.orderManual,
            onPick = viewModel::applyChapterOrder,
            onDismiss = { showSortSheet = false },
        )
    }

    // 返回键：多选态下先退出多选，而不是直接离开详情页（与 Mihon 一致）。
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selection.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllChapters,
                    onInvert = viewModel::invertSelection,
                )
            } else {
                TopAppBar(
                    title = { Text(state.manga?.displayName ?: "漫画详情", maxLines = 1, localize = state.manga == null) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        DetailOverflowMenu(
                            onTranslateAll = viewModel::translateAll,
                            onExportAll = { enqueueExport(state.chapters.map { it.chapterId }) },
                            onOpenSettings = { onOpenTranslationOptions(false) },
                            onOpenGlossary = onOpenGlossary,
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.selectionMode) {
                ChapterSelectionBar(
                    count = state.selection.size,
                    onMarkRead = { viewModel.markSelectionRead(true) },
                    onMarkUnread = { viewModel.markSelectionRead(false) },
                    onTranslateSelected = viewModel::translateSelection,
                    onExportSelected = { enqueueExport(state.selection.toList()) },
                    onClearTranslations = viewModel::clearSelectionTranslations,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::reload) { Text("重试") }
                }
            }

            state.manga != null -> DetailContent(
                state = state,
                contentPadding = padding,
                onSync = viewModel::syncChapters,
                onShelfClick = {
                    if (state.inShelf) viewModel.removeFromShelf() else showCategoryDialog = true
                },
                onReadChapter = onReadChapter,
                // 更新章节期间拦住"进入阅读"的一切入口（用户口径：更新章节时进入阅读的行为要阻塞）。
                // 拦在这里而不是只 disable 按钮：章节行本身也是阅读入口，只关按钮等于留了个后门。
                onReadBlocked = {
                    // 用 message 通道而不是直接 showSnackbar：那条通道本来就在
                    // LaunchedEffect(state.message) 里等着弹提示，重入一次就够了。
                    viewModel.showReadBlockedHint()
                },
                onOpenSortSheet = { showSortSheet = true },
                onToggleSelection = viewModel::toggleSelection,
                onMoveChapter = viewModel::moveChapter,
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: MangaDetailUiState,
    contentPadding: PaddingValues,
    onSync: () -> Unit,
    onShelfClick: () -> Unit,
    onReadChapter: (chapterId: String?, startPage: Int?) -> Unit,
    onReadBlocked: () -> Unit,
    onOpenSortSheet: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onMoveChapter: (chapterId: String, toIndex: Int) -> Unit,
) {
    val manga = requireNotNull(state.manga)

    /**
     * 所有"进入阅读"的入口都走这里。
     *
     * 更新章节期间**必须阻塞**（用户口径）：那一刻章节表正在被原子替换（`MangaChapterSyncer`
     * 落库 → 重读详情 → 强取封面），此时进阅读器会拿着一份就要作废的章节清单去翻页。
     * 拦在唯一入口上，才不会出现"按钮变了灰、点章节行却还能进"这种半拦状态。
     */
    val startReading: (String?, Int?) -> Unit = { chapterId, startPage ->
        if (state.syncing) onReadBlocked() else onReadChapter(chapterId, startPage)
    }

    /**
     * 拖动排序的跨行状态。
     *
     * 放在列表这一层而不是行内部：让位是**跨行**的信息——被拖动那一行下方的每一行
     * 都要让开一格，行自己算不出来。之前放在行内部时被拖的行浮在上面、其它行不动，
     * 看起来就是几行文字叠在一起（用户实测截图确认过）。
     */
    var dragFromIndex by remember { mutableStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    // 行高实测值（所有行等高，取第一个测到的）。测量之前用 dp 常量兜底。
    var rowUnitPx by remember { mutableStateOf(0f) }
    val fallbackRowUnitPx = with(LocalDensity.current) { CHAPTER_ROW_HEIGHT.toPx() }
    val unitPx = if (rowUnitPx > 0f) rowUnitPx else fallbackRowUnitPx
    // 落点：手指拖过半行就算跨过一格。算法在 ChapterOrdering 里（纯函数，有单测）。
    val dragToIndex = ChapterOrdering.dragTarget(dragFromIndex, dragOffsetPx / unitPx, state.chapters.lastIndex)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    // 用 state.cover 而不是 manga.coverDocumentId：封面是进详情页后
                    // 异步取到的（或点「更新章节」强制重取），拿到之前显示占位图。
                    request = state.coverDocumentId?.let { documentId ->
                        state.sourceTreeUri?.let { CoverRequest(it, documentId) }
                    },
                    contentDescription = manga.displayName,
                    modifier = Modifier.size(width = 120.dp, height = 170.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        manga.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("作者：${manga.author ?: "未知"}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.sourceDisplayPath ?: "来源路径不可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            // 简介折叠：详情页的信息区很长（封面/作者/来源/简介/按钮/章节表头），
            // 一段长简介会把"阅读 / 继续阅读"顶到屏幕外。默认只显示 3 行。
            ExpandableSummary(manga.summary ?: "无简介", localize = manga.summary == null)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShelfClick) {
                    Icon(
                        if (state.inShelf) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.inShelf) "移出书架" else "加入书架")
                }
                Button(onClick = onSync, enabled = !state.syncing) {
                    if (state.syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.syncing) "更新中" else "更新章节")
                }
            }
        }
        item {
            Button(
                onClick = { startReading(null, null) },
                // 更新章节期间禁用（并就地说明原因）：这是最主要的阅读入口，
                // 变灰比"点了弹一句提示"更直观地表达"现在不能读"。
                enabled = state.chapters.isNotEmpty() && !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (state.syncing) "更新章节中…" else "阅读 / 继续阅读")
            }
        }
        // 单章节模式**不显示章节数**（用户口径："共一章的就不要显示章节数了"）：那种卡片下
        // "共 1 章"是纯噪音。整行一起省掉——这行的另一半（排序按钮）本来就只在多于一章时出现，
        // 而每章的阅读进度在它自己那一行上写着（"读到第 Y 页"），所以没有信息丢失。
        //
        // 判据用 layoutMode 而**不是** `chapters.size == 1`：多章节模式只扫到 1 章时，
        // "共 1 章"是**有用**的（还可能再扫出更多，未扫完时那句"已发现 N 章，更新中"尤其有用），
        // 不该被一起抹掉。
        //
        // 注意这只是显示层：章节页数依旧是"点进去才取"（`backfillPageCounts` 一行不动），
        // 不为了把这一行填满去枚举整部漫画。
        if (manga.layoutMode != LayoutMode.SINGLE_CHAPTER) {
            item {
                val countText = if (manga.chapterCountKnown) {
                    "共 ${state.chapters.size} 章"
                } else {
                    "已发现 ${state.chapters.size} 章，更新中"
                }
                // 表头：左边是章节数（有已读标记时附带"已读 N"），右边是排序按钮。
                // 图标不变，点了从屏幕下方弹出排序抽屉（用户口径）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (state.readCount > 0) "$countText · 已读 ${state.readCount}" else countText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.chapters.size > 1) {
                        IconButton(onClick = onOpenSortSheet) {
                            Icon(
                                // 图形不变，只是换成 RTL 感知的那一份（原来的会被弃用）。
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "章节排序",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        if (state.chapters.isEmpty()) {
            item { Text("尚无章节索引") }
        } else {
            itemsIndexed(state.chapters, key = { _, chapter -> chapter.chapterId }) { index, chapter ->
                // 拖动状态在**列表**这一层：让位是跨行的信息（被拖的行下方的每一行
                // 都要让开一格），行自己算不出来。
                val isDragging = index == dragFromIndex
                // 让位方向由列表统一算（跨行信息，行自己拿不到，见 ChapterOrdering.dragDisplacement）。
                val shiftTarget =
                    ChapterOrdering.dragDisplacement(index, dragFromIndex, dragToIndex) * unitPx
                // 只有**让位**要动画；被拖的那一行必须 1:1 跟手，动画会让它拖不动似的。
                val shift by animateFloatAsState(
                    targetValue = shiftTarget,
                    animationSpec = tween(durationMillis = MAKE_ROOM_ANIMATION_MS),
                    label = "chapter-row-make-room",
                )
                ChapterRow(
                    chapter = chapter,
                    selected = chapter.chapterId in state.selection,
                    selectionMode = state.selectionMode,
                    read = state.readMarks[chapter.chapterId] == true,
                    translation = state.translations[chapter.chapterId],
                    // 阅读进度只属于"当前正在读的那一章"，其它章节行不该跟着显示页码。
                    isCurrentChapter = state.progress?.chapterId == chapter.chapterId,
                    readPage = state.progress?.pageOrdinal ?: 0,
                    offsetY = if (isDragging) dragOffsetPx else shift,
                    dragging = isDragging,
                    onClick = {
                        // 多选态下点击 = 改选择（进阅读会让人误以为点错了）；
                        // 非多选态才打开阅读器。
                        if (state.selectionMode) {
                            onToggleSelection(chapter.chapterId)
                        } else {
                            // 走统一入口：更新章节期间这里也会被拦住（见 startReading）。
                            startReading(
                                chapter.chapterId,
                                resumePageFor(
                                    state.progress?.chapterId == chapter.chapterId,
                                    state.progress?.pageOrdinal ?: 0,
                                ),
                            )
                        }
                    },
                    onLongPress = { onToggleSelection(chapter.chapterId) },
                    onDragStart = {
                        // 读**当前**选择集合，而不是这一帧的快照。
                        if (chapter.chapterId in state.selection) {
                            dragFromIndex = index
                            dragOffsetPx = 0f
                            true
                        } else {
                            // 第一次长按：只选中，不进入拖动（用户口径）。
                            onToggleSelection(chapter.chapterId)
                            false
                        }
                    },
                    onDragBy = { dy -> dragOffsetPx += dy },
                    onDragEnd = {
                        val from = dragFromIndex
                        val to = dragToIndex
                        dragFromIndex = -1
                        dragOffsetPx = 0f
                        if (from >= 0 && to >= 0 && to != from) onMoveChapter(chapter.chapterId, to)
                    },
                    onDragCancel = {
                        dragFromIndex = -1
                        dragOffsetPx = 0f
                    },
                    onMeasuredHeight = { height ->
                        // 行高**实测**而不是写死：拖动位移要换算成"跨过几行"，
                        // 行高随字体缩放与内容行数变化。所有行等高，取第一个测到的值。
                        if (rowUnitPx <= 0f) rowUnitPx = height.toFloat()
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 简介：默认 3 行 + 「显示更多 / 收起」，正文可长按选择复制。
 *
 * 只在真的被截断时给按钮：`onTextLayout` 报 `hasVisualOverflow` 之前不显示，
 * 否则短简介下面会挂一个点了没反应的"显示更多"。
 */
@Composable
private fun ExpandableSummary(text: String, localize: Boolean) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else SUMMARY_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow },
                localize = localize,
            )
        }
        if (overflows || expanded) {
            Text(
                text = if (expanded) "收起" else "显示更多",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** 简介折叠时显示几行；与卡片预览的两行区分开，详情页可以多给一行。 */
private const val SUMMARY_COLLAPSED_LINES = 3

/**
 * 一行章节。
 *
 * ## 交互（用户口径，参考 Mihon）
 *
 * - **长按** → 选中该章（整行加一层容器色，不是行尾打勾）；
 * - **再次长按已选中的章** → 进入拖动排序。多选态下也能拖，但**一次只拖这一章**，
 *   其余选中项保持不动；
 * - 多选态下**单击** = 改选择；非多选态单击 = 打开阅读器。
 *
 * ## 为什么长按要分两种
 *
 * "长按选中"与"长按拖动"是同一个手势，只能靠**行是否已经选中**区分（用户明确要求
 * "再次长按某个已选中的章节时允许上下拖动"）。因此这里用
 * `detectDragGesturesAfterLongPress`：长按触发时若行未选中，就只选中、并把手势标记为
 * "不是拖动"；已选中才真的跟着手指移动。
 *
 * ## 这一行为什么是"笨"的
 *
 * 位移、让位、落点全部由列表（[DetailContent]）算好传进来，本行只负责画。
 * 让位必须由列表统一算：被拖动那一行下方的每一行都要向上让一格，而"哪些行要让"
 * 是**跨行**的信息，行自己拿不到。早期把拖动状态放在行内部时，被拖的行浮在上面、
 * 其它行一动不动，看起来就是几行文字叠在一起（用户实测截图确认）。
 *
 * 回调一律经 [rememberUpdatedState] 转发：`pointerInput` 的 lambda 只捕获首次组合
 * 时的那个实例，直接闭包会读到过期的状态（选择集合、拖动下标）。
 */
@Composable
private fun ChapterRow(
    chapter: ChapterRecord,
    selected: Boolean,
    selectionMode: Boolean,
    read: Boolean,
    /** 这一章的翻译记录；null = 未翻译（界面上不显示翻译状态）。 */
    translation: ChapterTranslation?,
    isCurrentChapter: Boolean,
    readPage: Int,
    /** 这一行要额外下移/上移多少像素（让位或跟随手指）。 */
    offsetY: Float,
    /** 这一行正在被拖动：加浮起色、抬到最上层、且位移不加动画。 */
    dragging: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    /** 返回 true = 本次手势进入拖动；false = 只是选中（或不属于本行）。 */
    onDragStart: () -> Boolean,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMeasuredHeight: (Int) -> Unit,
) {
    val currentClick by rememberUpdatedState(onClick)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragBy by rememberUpdatedState(onDragBy)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentDragCancel by rememberUpdatedState(onDragCancel)
    val currentMeasured by rememberUpdatedState(onMeasuredHeight)

    // 本次手势是否真的进入拖动（长按未选中的行时只选中，不拖动）。
    var armed by remember { mutableStateOf(false) }

    val background = when {
        dragging -> MaterialTheme.colorScheme.secondaryContainer
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    Surface(
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            // 拖动的行要盖住别人；LazyColumn 里后面的项默认画在上面，
            // 往下拖时（被拖的行在下标更小的位置）不加 zIndex 就会被压住。
            .zIndex(if (dragging) 1f else 0f)
            .onSizeChanged { currentMeasured(it.height) }
            .graphicsLayer { translationY = offsetY }
            .pointerInput(chapter.chapterId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        armed = currentDragStart()
                    },
                    onDrag = { change, amount ->
                        if (!armed) return@detectDragGesturesAfterLongPress
                        change.consume()
                        currentDragBy(amount.y)
                    },
                    onDragEnd = {
                        if (armed) currentDragEnd()
                        armed = false
                    },
                    onDragCancel = {
                        if (armed) currentDragCancel()
                        armed = false
                    },
                )
            }
            .pointerInput(chapter.chapterId) {
                // "短按 = 点击"必须**自己判定**，不能用 `detectTapGestures(onTap = ...)`：
                // 它在只给 onTap 时，**长按之后的抬手也会触发 onTap**。于是"长按选中、
                // 手指不动直接松开"会被当成一次点击 → 立刻把刚选中的那一章取消掉，
                // 表现为"多选模式莫名其妙退出了"；而手指稍微划一下反而正常，因为位移
                // 超过 touch slop 后 tap 被取消。真机反馈确认过这条路径。
                //
                // 这个检测器**不消费任何事件**：拖动检测器还要靠这些事件跟手。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    // 只有"没到长按"且"这批事件里手指已经抬起来"才算短按。
                    // 到长按（选中或开始拖动）时什么都不做——尤其不能当成点击。
                    if (longPress == null && currentEvent.changes.all { !it.pressed }) {
                        currentClick()
                    }
                }
            },
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = chapter.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                // 以前这里显示"图片目录"，但那是**物理形式**，用户看章节列表时
                // 想知道的是"这一章有多长、我读到哪了、翻译到哪了"。
                Text(
                    buildString {
                        chapter.pageCount?.let { append("共 $it 页") }
                        if (isCurrentChapter && readPage > 0) {
                            if(isNotEmpty()) append(" · ")
                            append("读到第 ${readPage + 1} 页")
                        }
                        // 翻译状态写在副标题里而不是行尾：行尾已经被"已读"与页码占着，
                        // 而这一行本来就短。
                        if(isNotEmpty()) append(" · ")
                        append(when {
                            translation == null -> "未翻译"
                            translation.state == TranslationState.DONE -> "已翻译"
                            translation.state == TranslationState.CANCELLED -> if (chapter.pageCount?.let { translation.translatedCount >= it && it > 0 } == true) "已翻译" else "未翻译"
                            translation.state == TranslationState.FAILED -> "翻译失败"
                            translation.state == TranslationState.INTERRUPTED -> "翻译中断"
                            translation.state == TranslationState.PAUSED -> "翻译暂停 ${translation.translatedCount}/${chapter.pageCount ?: "?"}"
                            translation.state == TranslationState.PENDING -> "等待翻译 ${translation.translatedCount}/${chapter.pageCount ?: "?"}"
                            else -> "翻译中 ${translation.translatedCount}/${chapter.pageCount ?: "?"}"
                        })
                    },
                )
            },
            trailingContent = {
                when {
                    // 已读标记优先于页码：多选时用户关心的是"哪些已经读过"。
                    read -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "已读",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )

                    // 已读过的章节显示进度，而不是总页数——总页数在标题下面。
                    isCurrentChapter && readPage > 0 -> Text(
                        "${readPage + 1}/${chapter.pageCount ?: "?"}",
                        color = MaterialTheme.colorScheme.primary,
                    )

                    else -> chapter.pageCount?.let { Text("$it 页") }
                }
            },
        )
    }
}

/** 章节行高度的兜底值（实测不到时用）；真实高度由 `onSizeChanged` 给出。 */
private val CHAPTER_ROW_HEIGHT = 84.dp

/**
 * 其它行让开一格用的动画时长。
 *
 * 取 120ms：短到"手指移到下一格时它已经让开了"，长到能看出是被挤开的而不是跳过去的。
 * 被拖动的那一行**不加动画**（1:1 跟手），否则会有拖不动的黏滞感。
 */
private const val MAKE_ROOM_ANIMATION_MS = 120

/**
 * 多选顶栏（对齐 Mihon）：`✕` + 已选数量 + 全选 / 反选。
 *
 * 数量就是一个数字，不加"已选"字样——Mihon 就是这样，且按钮本身已经说明了语境。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "全选")
            }
            IconButton(onClick = onInvert) {
                Icon(Icons.Filled.FlipToBack, contentDescription = "反选")
            }
        },
    )
}

/**
 * 多选底栏（浮动动作条）。
 *
 * 必须避开系统导航栏/手势区（`.windowInsetsPadding(WindowInsets.navigationBars)`）：
 * 贴到屏幕底边的控件会被导航栏压住一半——真机上「标记已读」就正好被吃掉，
 * 看得见点不到。这是与 `SourceFilterDrawer` / 路径表同一套约定。
 *
 * 底栏动作会实际改数据：标记已读/未读写 `chapter_read_state`；翻译所选/清除翻译文本
 * 写 `chapter_translation`（侧栏「翻译队列」的计数因此会变）。开发文档 17 的完成标准是
 * "不存在仅摆放未接线的核心控件"，所以没接上的功能宁可不出现在这里。
 */
@Composable
private fun ChapterSelectionBar(
    count: Int,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onTranslateSelected: () -> Unit,
    onExportSelected: () -> Unit,
    onClearTranslations: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(
                icon = Icons.Filled.Translate,
                label = "翻译所选",
                enabled = count > 0,
                onClick = onTranslateSelected,
            )
            SelectionAction(
                icon = Icons.Filled.FileDownload,
                label = "导出所选",
                enabled = count > 0,
                onClick = onExportSelected,
            )
            SelectionAction(
                icon = Icons.Filled.DeleteSweep,
                label = "清除翻译",
                enabled = count > 0,
                onClick = onClearTranslations,
            )
            SelectionAction(
                icon = Icons.Filled.CheckCircle,
                label = "标记已读",
                enabled = count > 0,
                onClick = onMarkRead,
            )
            SelectionAction(
                icon = Icons.Filled.RadioButtonUnchecked,
                label = "标记未读",
                enabled = count > 0,
                onClick = onMarkUnread,
            )
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 章节排序抽屉（屏幕下方弹出）。
 *
 * 三项：首字母 / 按修改时间 / 按自然数字。**同一项点一次正向、再点一次逆向**
 * （用户口径），因此每一项右侧显示当前方向箭头，点已选中的那一项就是反向。
 *
 * 手动拖过章节之后不再属于任何一种排序方式，抽屉顶部如实显示"当前：手动"——
 * 此时点任何一项都会**整表重排**（用户已确认这是期望行为：否则拖乱之后没法整理回来）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSortSheet(
    setting: ChapterOrdering.Setting,
    manual: Boolean,
    onPick: (ChapterOrdering.Mode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 先取到本地：`setting.mode` 是跨模块的公开属性，Kotlin 不允许对它做智能转换。
    val currentMode = setting.mode
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "章节排序",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(
                text = when {
                    manual -> "当前：手动（点下面任意一项会按它重排整个列表）"
                    currentMode == null -> "当前：按发现顺序（新章节追加到末尾）"
                    else -> "当前：${currentMode.label()}${if (setting.descending) "（逆向）" else "（正向）"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                text = "再点一次同一项就是反向；面板不会关，方便来回比较",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ChapterOrdering.Mode.entries.forEach { mode ->
                val active = !manual && currentMode == mode
                ListItem(
                    headlineContent = { Text(mode.label()) },
                    supportingContent = { Text(mode.hint(), style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        if (active) {
                            Icon(
                                imageVector = if (setting.descending) {
                                    Icons.Filled.ArrowDownward
                                } else {
                                    Icons.Filled.ArrowUpward
                                },
                                contentDescription = if (setting.descending) "逆向" else "正向",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        // 点完**不关面板**（用户要求）：排序不是一次性动作——同一项再点
                        // 一次就是反向，来回比较时每次都要重新打开抽屉太烦。
                        // 面板里的"当前：…"与箭头会跟着刷新，列表在身后实时重排。
                        onPick(mode)
                    },
                )
            }
        }
    }
}

private fun ChapterOrdering.Mode.label(): String = when (this) {
    ChapterOrdering.Mode.ALPHA -> "首字母排序"
    ChapterOrdering.Mode.MODIFIED -> "按修改时间排序"
    ChapterOrdering.Mode.NATURAL -> "按自然数字排序"
}

private fun ChapterOrdering.Mode.hint(): String = when (this) {
    ChapterOrdering.Mode.ALPHA -> "按名称字典序，不认数字大小"
    ChapterOrdering.Mode.MODIFIED -> "按目录或归档文件的修改时间"
    ChapterOrdering.Mode.NATURAL -> "认数字大小：第 1、2、10、11、100 章"
}

/**
 * 详情页右上角的 ⋮。
 *
 * 语言 / 文风 / 译名三个入口统一放在「翻译选项」中。
 *
 * 入口叫「翻译选项」而不是「翻译设置」（用户口径）：后者已经被应用级的翻译配置
 * （设置里的那一套）占用了，同名会让人分不清改的是这部作品还是全局。
 */
@Composable
private fun DetailOverflowMenu(
    onTranslateAll: () -> Unit,
    onExportAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGlossary: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("全部翻译") },
            onClick = {
                open = false
                onTranslateAll()
            },
        )
        DropdownMenuItem(
            text = { Text("译名管理") },
            onClick = { open = false; onOpenGlossary() },
        )
        DropdownMenuItem(
            text = { Text("全部导出") },
            onClick = { open = false; onExportAll() },
        )
        DropdownMenuItem(
            text = { Text("翻译选项") },
            onClick = {
                open = false
                onOpenSettings()
            },
        )
    }
}
