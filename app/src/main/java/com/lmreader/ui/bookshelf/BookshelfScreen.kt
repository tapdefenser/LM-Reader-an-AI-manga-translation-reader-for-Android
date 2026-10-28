package com.lmreader.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.BookshelfSort
import com.lmreader.core.model.BookshelfSortMode
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.StyleMode
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.ListScrollbar
import com.lmreader.ui.common.CoverRequest
import com.lmreader.ui.common.EndSideDrawer
import com.lmreader.ui.common.LoadingState
import com.lmreader.ui.common.MangaCardItem
import com.lmreader.ui.common.MessageState
import com.lmreader.ui.common.SearchField
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 书架页（开发文档 8.2）。默认展示全部收藏，右上分类按钮打开右侧分类栏。
 *
 * 每次启动默认进入书架（除非尚未完成路径配置），因此这里也是"没有收藏"时
 * 最需要给出可操作指引的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    container: AppContainer,
    onOpenMenu: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val treeUris = rememberSourceTreeUris(container)

    var newCategoryDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var movingManga by remember { mutableStateOf<MangaCard?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    /** 搜索框是否展开；与图库同一套交互（图标切换、退出时清空）。 */
    var searchActive by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    /** 右边缘召出手势的累计位移（像素）。 */
    var dragAccumulated by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to listState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_DISTANCE }
            .collect { viewModel.onLoadMore() }
    }

    // 封面懒加载：上报当前可见的卡片（与图库同一套规则）。书架只有一种展示方式
    // （列表），因此不需要按 displayMode 分支。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices -> viewModel.onCardsVisible(indices) }
    }

    // 分类侧栏：吸附在**右侧**的抽屉（用户要求），与图库的图源筛选栏同一侧、同一套手势。
    // 点右上角筛选按钮可打开，也可以从屏幕右边缘向左滑打开。
    // 参考 EhViewer 的 DownloadsScreen：它的分类/筛选面板就是右侧面板。
    // 用自实现的 EndSideDrawer（Popup 覆盖层）而不是 ModalNavigationDrawer：
    // 后者只能吸附起始侧、会跑到左边；把子树设成 RTL 又会镜像面板内容。
    EndSideDrawer(
        open = state.sidePanelOpen,
        onOpen = { viewModel.setSidePanelOpen(true) },
        onDismiss = { viewModel.setSidePanelOpen(false) },
        drawerWidth = 300.dp,
    ) {
        CategorySidePanel(
            state = state,
            onSelect = viewModel::selectCategory,
            onCreate = { newCategoryDialog = true },
            onRename = { renaming = it.categoryId to it.name },
            onMove = viewModel::moveCategory,
            onDelete = viewModel::deleteCategory,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (showSortSheet) {
        BookshelfSortSheet(
            sort = state.sort,
            onPick = viewModel::applySort,
            onDismiss = { showSortSheet = false },
        )
    }
    Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (searchActive) {
                            // 与图库同一个组件：两处样式不会各走各的。见 [SearchField]。
                            SearchField(
                                value = state.query,
                                onValueChange = viewModel::onQueryChange,
                                placeholder = "搜索书架",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("书架 · ${state.selectedCategoryName}")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "主菜单")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                searchActive = !searchActive
                                // 退出搜索时清空关键词，否则列表会停在一个看不见的过滤条件上
                                // ——用户会以为书架里的书丢了。与图库同一处理。
                                if (!searchActive) viewModel.onQueryChange("")
                            },
                        ) {
                            Icon(
                                imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (searchActive) "退出搜索" else "搜索",
                            )
                        }
                        // 作用说清：这是"更新书架里这些漫画的章节"，不是"刷新书架列表"。
                        // 列表自己会跟着数据变，不需要用户手动刷。
                        IconButton(onClick = viewModel::onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "更新所有漫画的章节")
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "书架排序",
                            )
                        }
                        IconButton(onClick = { viewModel.setSidePanelOpen(true) }) {
                            Icon(Icons.Filled.FilterList, contentDescription = "筛选与分类")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // 一键更新章节的进度与结果。
                //
                // 它可能持续几十秒（每部漫画都要枚举一次目录），没有反馈的话用户只会
                // 以为按钮没反应、然后反复点。因此给出确定进度（第几部 / 共几部）而不是
                // 一个转圈。
                ChapterUpdateStatus(state = state, onDismiss = viewModel::dismissChapterUpdateMessage)
                if (state.error != null) {
                    MessageState(
                        message = state.error!!,
                        actionLabel = "重试",
                        // 「重试」重读列表，不是更新章节：这里失败的是"读书架"。
                        onAction = viewModel::reload,
                    )
                } else if (state.items.isEmpty() && state.loading) {
                    LoadingState()
                } else if (state.items.isEmpty()) {
                    // 三种"空"要说清是哪一种：搜索无结果时给"去图库"是帮倒忙
                    // （书确实收藏着，只是没匹配上）。
                    if (state.appliedQuery.isNotBlank()) {
                        MessageState(
                            message = "书架里没有匹配「${state.appliedQuery}」的收藏",
                            actionLabel = null,
                            onAction = null,
                        )
                    } else {
                        MessageState(
                            message = "书架还是空的。在库里长按漫画卡片即可加入书架，" +
                                "收藏不会复制原文件。",
                            actionLabel = "去库",
                            onAction = onOpenLibrary,
                        )
                    }
                } else {
                    // 滚动条浮在列表右侧，因此列表与它同处一个 Box。
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.items, key = { it.mangaId }) { card ->
                                MangaCardItem(
                                    card = card,
                                    coverRequest = card.coverRequest(treeUris),
                                    onClick = { onOpenManga(card.mangaId) },
                                    onLongClick = { movingManga = card },
                                )
                            }
                            if (state.exhausted) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "共 ${state.items.size} 部收藏",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        ListScrollbar(
                            state = listState,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    // 右边缘的召出手势：从屏幕右边缘向左滑打开筛选栏。
    // 之所以用一条**窄**（16dp）覆盖条而不是让整页参与拖动：
    // 早先版本用的是 24dp 宽的手势区，正好压住距边缘约 20dp 的顶栏按钮、把点击吃掉；
    // 16dp 落在按钮之外，同时仍然提供"从边缘滑出"这个习惯动作。
    // 它只在关闭时存在，因此不会妨碍面板内部的滑动。
    if (!state.sidePanelOpen) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(16.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, delta ->
                        // 向左拖（负值）累积到阈值即召出面板；
                        // 只累积不消费事件，因此不会影响同一位置的其它手势。
                        dragAccumulated += delta
                        if (dragAccumulated < -EDGE_SUMMON_THRESHOLD_PX) {
                            dragAccumulated = 0f
                            viewModel.setSidePanelOpen(true)
                        }
                    }
                },
        )
    }


    }

    if (newCategoryDialog) {
        NameInputDialog(
            title = "新建分类",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { newCategoryDialog = false },
            onConfirm = { name ->
                viewModel.createCategory(name) { errorMessage = it }
                newCategoryDialog = false
            },
        )
    }

    renaming?.let { (categoryId, currentName) ->
        NameInputDialog(
            title = "重命名分类",
            initial = currentName,
            confirmLabel = "保存",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.renameCategory(categoryId, name) { errorMessage = it }
                renaming = null
            },
        )
    }

    // 长按卡片：选择目标分类（开发文档 8.2「加入书架/已在书架」的更换分类入口）。
    movingManga?.let { card ->
        AlertDialog(
            onDismissRequest = { movingManga = null },
            title = { Text(card.displayName, localize = false) },
            text = {
                Column {
                    Text("移动到分类", style = MaterialTheme.typography.labelMedium)
                    state.categories.forEach { category ->
                        TextButton(
                            onClick = {
                                viewModel.moveToCategory(card.mangaId, category.categoryId)
                                movingManga = null
                            },
                        ) { Text(category.name, localize = false) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromShelf(card.mangaId)
                        movingManga = null
                    },
                ) { Text("移出书架") }
            },
            dismissButton = {
                TextButton(onClick = { movingManga = null }) { Text("取消") }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("无法完成操作") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
        )
    }
}

/**
 * 右侧分类栏（开发文档 8.2）。
 *
 * 显示数量，并提供重命名/文风、上移、下移、删除。文风设置对翻译有直接影响
 * （开发文档 8.2「分类文风解析」），因此这里显式标注当前文风来源，而不是留一个
 * 看不出作用的开关。
 */
@Composable
private fun CategorySidePanel(
    state: BookshelfUiState,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onRename: (com.lmreader.core.model.Category) -> Unit,
    onMove: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxHeight().width(300.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让出导航栏高度，避免分类列表底部被手势条压住。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = "新建分类")
                }
            }
            HorizontalDivider()
            CategoryRow(
                name = "全部",
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
            )
            state.categories.forEachIndexed { index, category ->
                CategoryRow(
                    name = category.name,
                    selected = state.selectedCategoryId == category.categoryId,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.categories.lastIndex,
                    description = styleDescription(category.styleMode),
                    onClick = { onSelect(category.categoryId) },
                    onRename = { onRename(category) },
                    onMoveUp = { onMove(category.categoryId, -1) },
                    onMoveDown = { onMove(category.categoryId, 1) },
                    // 内置「未分类」不可删（开发文档 1.3），因此没有删除项。
                    onDelete = if (category.categoryId == 0L) null else ({ onDelete(category.categoryId) }),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    description: String? = null,
    onRename: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
                Column {
                    Text(name, localize = false)
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onRename != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "分类操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名与文风") },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("上移") },
                            enabled = canMoveUp,
                            onClick = {
                                menuOpen = false
                                onMoveUp?.invoke()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("下移") },
                            enabled = canMoveDown,
                            onClick = {
                                menuOpen = false
                                onMoveDown?.invoke()
                            },
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除分类") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 文风来源说明；具体文风文本在漫画的「翻译选项」页（或应用级翻译设置）里编辑。 */
private fun styleDescription(mode: StyleMode): String = when (mode) {
    StyleMode.GLOBAL -> "文风：跟随全局默认"
    StyleMode.CATEGORY -> "文风：跟随类别（未设置时回退全局）"
    StyleMode.CUSTOM -> "文风：本分类自定义"
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    // 名称必填 1–40 字符（开发文档 8.2）；非法输入就地提示并禁用保存，
    // 不悄悄改成默认值。
    val valid = trimmed.isNotEmpty() && trimmed.length <= 40
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = value.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            text = when {
                                trimmed.isEmpty() -> "名称必填，1–40 个字符"
                                trimmed.length > 40 -> "名称最长 40 个字符"
                                else -> "${trimmed.length}/40"
                            },
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun MangaCard.coverRequest(treeUris: Map<String, String>): CoverRequest? {
    val documentId = coverDocumentId ?: return null
    val treeUri = treeUris[sourceId] ?: return null
    return CoverRequest(treeUri = treeUri, documentId = documentId)
}

/**
 * 一键更新章节的进度 / 结果提示。
 *
 * 进度用**确定值**（第几部 / 共几部）而不是转圈：这个操作每部漫画都要枚举一次目录，
 * 几十部就是几十秒，用户需要知道"还剩多少"，否则只会以为按钮没反应。
 * 更新范围写明"当前筛选"，因为用户勾了分类或搜索词时，更新的是那一部分，不是全部收藏。
 */
@Composable
private fun ChapterUpdateStatus(
    state: BookshelfUiState,
    onDismiss: () -> Unit,
) {
    if (state.updatingChapters) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            LinearProgressIndicator(
                progress = { state.chapterUpdateDone.toFloat() / state.chapterUpdateTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "正在更新章节 ${state.chapterUpdateDone} / ${state.chapterUpdateTotal}" +
                    "（仅当前筛选下的收藏）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }
    val message = state.chapterUpdateMessage ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
}

@Composable
private fun rememberSourceTreeUris(container: AppContainer): Map<String, String> {
    // 只有一张来源表（图片与归档由同一次扫描一起识别），所以只订阅一次。
    val sources by container.sourceRepository
        .observeSources()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(sources) {
        sources.associate { it.sourceId to it.treeUri }
    }
}

private const val PREFETCH_DISTANCE = 6

/**
 * 书架排序抽屉（屏幕下方弹出，与详情页的章节排序同一个形状）。
 *
 * 三项：名称 / 加入时间 / 最近阅读。**第一次点某一项用它自己的默认方向**（名称 A→Z、
 * 加入时间与最近阅读都是"新的在前"），再点同一项就反向——这样点一次就是用户想要的结果，
 * 不用先看到最老的一批再点第二次。
 *
 * 与章节排序一样，点完**不关面板**：排序经常要来回比几下，每次都重新打开抽屉太烦。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfSortSheet(
    sort: BookshelfSort,
    onPick: (BookshelfSortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "书架排序",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(
                text = "当前：${sort.mode.label()}${if (sort.descending) "（逆向）" else "（正向）"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                text = "只影响当前分类（或搜索）里的漫画顺序；再点一次同一项就是反向",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            BookshelfSortMode.entries.forEach { mode ->
                val active = sort.mode == mode
                ListItem(
                    headlineContent = { Text(mode.label()) },
                    supportingContent = {
                        Text(mode.hint(), style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        if (active) {
                            Icon(
                                imageVector = if (sort.descending) {
                                    Icons.Filled.ArrowDownward
                                } else {
                                    Icons.Filled.ArrowUpward
                                },
                                contentDescription = if (sort.descending) "逆向" else "正向",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable { onPick(mode) },
                )
            }
        }
    }
}

private fun BookshelfSortMode.label(): String = when (this) {
    BookshelfSortMode.NAME -> "按名称排序"
    BookshelfSortMode.ADDED -> "按加入时间排序"
    BookshelfSortMode.READ -> "按最近阅读排序"
    BookshelfSortMode.RECENT_CHAPTER -> "按最新章节更新时间排序"
}

private fun BookshelfSortMode.hint(): String = when (this) {
    BookshelfSortMode.NAME -> "作品名自然序，数字按数值（第 2 话在 第 10 话 之前）"
    BookshelfSortMode.ADDED -> "刚加入书架的排在前面"
    BookshelfSortMode.READ -> "刚读过的排在前面；从没读过的一律排在后面"
    BookshelfSortMode.RECENT_CHAPTER -> "所有章节里最晚的修改时间；刚更新过的排在前面（追更用）"
}

/** 右边缘向左滑多少像素才召出筛选面板。 */
private const val EDGE_SUMMON_THRESHOLD_PX = 24f
