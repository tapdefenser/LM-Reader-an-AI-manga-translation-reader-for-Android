package com.lmreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lmreader.core.model.LibraryDisplayMode
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.SourceKind
import com.lmreader.di.AppContainer
import com.lmreader.ui.common.GridScrollbar
import com.lmreader.ui.common.ListScrollbar
import com.lmreader.ui.common.CoverRequest
import com.lmreader.ui.common.EndSideDrawer
import com.lmreader.ui.common.LoadingState
import com.lmreader.ui.common.MangaCardItem
import com.lmreader.ui.common.MangaGridItem
import com.lmreader.ui.common.MessageState
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.lmreader.ui.common.ScreenState
import com.lmreader.ui.common.SearchField
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 图库页（开发文档 8.1）。
 *
 * 「40 项增长」由 [com.lmreader.ui.paging.PagingState] 与 ViewModel 负责，这里只做
 * 触底检测：列表滚到接近末端时请求下一批。扫描条显示"已发现 N 项"而不是百分比
 * （开发文档 8.1「不伪造总百分比」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenMenu: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 封面 URI 需要"来源树 URI + documentId"组合（开发文档 4.1），
    // 因此这里维护 sourceId → treeUri 的映射，由卡片投影里的 sourceId 反查。
    val treeUris = rememberSourceTreeUris(container)

    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }

    if (showCategoryDialog && state.selectionMode) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("选择书架分类") },
            text = {
                LazyColumn {
                    items(state.categories, key = { it.categoryId }) { category ->
                        TextButton(
                            onClick = {
                                showCategoryDialog = false
                                viewModel.addSelectionToShelf(category.categoryId)
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

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    /**
     * 从详情页返回时，把**刚看过的那张卡片**重新读一遍（用户口径：退出详情页要在图库页
     * 更新这一对应卡片）。
     *
     * 详情页会改这张卡片的数据：「更新章节」把章节清单与 `chapterCountKnown` 落成准确值、
     * 强取封面与简介、按需回填页数。用户在详情页里看到的是新数据，返回图库却还看着旧卡片，
     * 会以为"更新章节没生效"。
     *
     * 用 ON_RESUME 而不是"导航回调"：返回是系统返回键、手势返回、导航栈弹出三条路径共用的
     * 结果，只有生命周期事件能一次覆盖。只查一行、只替换一行，不重建分页会话
     * （重建会把滚动位置打回第一页）。
     */
    val lastOpenedMangaId = remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastOpenedMangaId.value?.let { viewModel.refreshCard(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openManga: (String) -> Unit = { mangaId ->
        lastOpenedMangaId.value = mangaId
        onOpenManga(mangaId)
    }

    // 触底追加：只增加额度，不重扫（开发文档 8.1「底部加载」）。
    LaunchedEffect(listState, state.displayMode) {
        if (state.displayMode != LibraryDisplayMode.LIST) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to listState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_DISTANCE }
            .collect { viewModel.onLoadMore() }
    }
    LaunchedEffect(gridState, state.displayMode) {
        if (state.displayMode != LibraryDisplayMode.GRID) return@LaunchedEffect
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to gridState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (last, total) -> total > 0 && last >= total - PREFETCH_DISTANCE }
            .collect { viewModel.onLoadMore() }
    }

    // 封面懒加载：把**当前可见**的卡片下标报给 ViewModel（用户要求：扫描只写路径，
    // 封面在往下滚动时边加载边取，一次一批，取过就不再取）。
    //
    // 为什么要上报可见集合而不是"新加载的那一批"：一批 30 项里用户可能只看得到 12 张，
    // 其余 18 张取封面是白花的目录枚举；反过来，用户滚回去时那些卡片本来就会重新可见。
    LaunchedEffect(listState, state.displayMode) {
        if (state.displayMode != LibraryDisplayMode.LIST) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices -> viewModel.onCardsVisible(indices) }
    }
    LaunchedEffect(gridState, state.displayMode) {
        if (state.displayMode != LibraryDisplayMode.GRID) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices -> viewModel.onCardsVisible(indices) }
    }

    // 图源筛选栏：吸附在**右侧**的抽屉（用户要求）。点顶栏按钮可打开，
    // 也可以从屏幕右边缘向左滑打开。
    // 用自实现的 EndSideDrawer（Popup 覆盖层）而不是 ModalNavigationDrawer：
    // 后者只能吸附起始侧、会跑到左边；把子树设成 RTL 又会镜像面板内容。
    EndSideDrawer(
        open = state.sourceFilterOpen,
        onOpen = viewModel::openSourceFilter,
        onDismiss = viewModel::closeSourceFilter,
    ) {
        SourceFilterDrawer(
            sources = state.allSources,
            draftSelection = state.draftSourceFilter,
            discoveredBySource = state.discoveredBySource,
            onToggle = viewModel::toggleSourceFilter,
            onSelectAll = viewModel::selectAllSources,
            onClearAll = viewModel::clearAllSources,
            onConfirm = viewModel::confirmSourceFilter,
            onDismiss = viewModel::closeSourceFilter,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 选择态与普通态是两条不同的顶栏：选择态下"搜索/刷新/展示方式"都不适用，
            // 换成批量操作，避免用户在选中 20 部作品时误触刷新丢掉选择。
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selection.size,
                    loadedCount = state.items.size,
                    onCancel = viewModel::clearSelection,
                    onInvertSelection = viewModel::invertSelection,
                    onAddToShelf = { showCategoryDialog = true },
                )
            } else {
            TopAppBar(
                title = {
                    if (searchActive) {
                        // 胶囊形、比默认扁：见 [SearchField] 里"为什么不用 OutlinedTextField"。
                        SearchField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = "搜索漫画名或简介",
                            onSearch = { viewModel.onSearchOpened() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            // 生效筛选时在标题上写明范围，用户不必打开栏就知道在看什么。
                            text = state.effectiveSourceFilter?.let { filter ->
                                "图库（${filter.size} 个图源）"
                            } ?: "图库",
                        )
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
                            if (searchActive) viewModel.onSearchOpened() else viewModel.onQueryChange("")
                        },
                    ) {
                        Icon(
                            imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchActive) "退出搜索" else "搜索",
                        )
                    }
                    IconButton(onClick = viewModel::onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新（重新扫描变化）")
                    }
                    // 图源筛选入口：图标 + 生效数量，避免"有没有在筛"只能靠点开才知道。
                    IconButton(onClick = viewModel::openSourceFilter) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "筛选图源",
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = when (state.displayMode) {
                                LibraryDisplayMode.LIST -> Icons.Filled.ViewList
                                LibraryDisplayMode.GRID -> Icons.Filled.GridView
                            },
                            contentDescription = "展示方式",
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("封面列表") },
                            onClick = {
                                viewModel.setDisplayMode(LibraryDisplayMode.LIST)
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("紧凑网格") },
                            onClick = {
                                viewModel.setDisplayMode(LibraryDisplayMode.GRID)
                                menuExpanded = false
                            },
                        )
                    }
                },
            )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScanStatusBar(
                state = state,
                onCancel = viewModel::cancelScan,
                onOpenSettings = onOpenSettings,
            )

            when (val screen = state.screenState) {
                is ScreenState.Loading -> LoadingState()

                is ScreenState.Empty -> MessageState(
                    message = screen.message,
                    // "去设置图库路径"只在**真的没搜索**时给：搜索无结果时该做的是改关键词，
                    // 不是去改路径。判据用 query（输入框现状）而不是结果对应的查询。
                    actionLabel = if (state.query.isBlank()) "去设置图库路径" else null,
                    onAction = if (state.query.isBlank()) onOpenSettings else null,
                )

                is ScreenState.Error -> MessageState(
                    message = screen.reason,
                    actionLabel = "重试",
                    onAction = viewModel::onRefresh,
                )

                is ScreenState.Content -> {
                    // 滚动条浮在列表右侧，因此列表与它同处一个 Box。
                    // 用 overlay 而不是把列表变窄：变窄会让网格列数变化、封面尺寸跳动。
                    Box(modifier = Modifier.fillMaxSize()) {
                    if (state.displayMode == LibraryDisplayMode.LIST) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.items, key = { it.mangaId }) { card ->
                                MangaCardItem(
                                    card = card,
                                    coverRequest = card.coverRequest(treeUris),
                                    // 普通态单击进详情；选择态单击切换选中。
                                    onClick = {
                                        if (state.selectionMode) {
                                            viewModel.toggleSelection(card.mangaId)
                                        } else {
                                            openManga(card.mangaId)
                                        }
                                    },
                                    // 长按进入选择态并选中该卡片（用户要求）。
                                    onLongClick = { viewModel.startSelection(card.mangaId) },
                                    selected = card.mangaId in state.selection,
                                    selectionMode = state.selectionMode,
                                )
                            }
                            item { ListFooter(state) }
                        }
                        ListScrollbar(
                            state = listState,
                            modifier = Modifier,
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(112.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.items, key = { it.mangaId }) { card ->
                                MangaGridItem(
                                    card = card,
                                    coverRequest = card.coverRequest(treeUris),
                                    onClick = {
                                        if (state.selectionMode) {
                                            viewModel.toggleSelection(card.mangaId)
                                        } else {
                                            openManga(card.mangaId)
                                        }
                                    },
                                    onLongClick = { viewModel.startSelection(card.mangaId) },
                                    selected = card.mangaId in state.selection,
                                    selectionMode = state.selectionMode,
                                )
                            }
                            item { ListFooter(state) }
                        }
                        GridScrollbar(
                            state = gridState,
                            modifier = Modifier,
                        )
                    }
                    }
                }
            }
        }
    }
}

/**
 * 选择态顶栏（用户要求的长按多选）。
 *
 * 只放与"已选中集合"有关的操作：选择分类加入书架，以及反选/取消。
 * 翻译与导出属于 P3/P4，本步不放按钮——开发文档 17 的完成标准是
 * "不存在仅摆放未接线的核心控件"，放一个点了没反应的翻译按钮比不放更糟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    loadedCount: Int,
    onCancel: () -> Unit,
    onInvertSelection: () -> Unit,
    onAddToShelf: () -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("已选 $selectedCount 项") },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
        },
        actions = {
            IconButton(onClick = onInvertSelection) {
                Icon(
                    // 反选：两个方向相反的箭头，比"全选"更能表达"选中状态取反"。
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = "反选已加载的 $loadedCount 项",
                )
            }
            IconButton(onClick = { actionsExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "批量操作")
            }
            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("加入书架") },
                    onClick = {
                        actionsExpanded = false
                        onAddToShelf()
                    },
                )
            }
        },
    )
}

/**
 * 扫描条（开发文档 8.1）。
 *
 * 只显示"已发现 N 项"与扫描/取消入口，**不显示百分比**：发现阶段没有总数，
 * 伪造进度条会让用户以为扫描卡住了。
 */
@Composable
private fun ScanStatusBar(
    state: LibraryUiState,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.scan.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.scan.statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.indexingHint) {
                Text(
                    text = "正在更新索引，结果可能不全",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.scan.running) {
                TextButton(onClick = onCancel) { Text("取消") }
            } else if (state.scan.lastFailure != null) {
                TextButton(onClick = onOpenSettings) { Text("查看路径") }
            }
        }
    }
}

@Composable
private fun ListFooter(state: LibraryUiState) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.loading -> CircularProgressIndicator()
            state.exhausted && state.items.isNotEmpty() -> Text(
                text = "已显示全部 ${state.items.size} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Spacer(Modifier.width(1.dp))
        }
    }
}

/** 卡片封面定位：需要该卡片所属来源的树 URI。 */
private fun MangaCard.coverRequest(treeUris: Map<String, String>): CoverRequest? {
    val documentId = coverDocumentId ?: return null
    val treeUri = treeUris[sourceId] ?: return null
    return CoverRequest(treeUri = treeUri, documentId = documentId)
}

/** 订阅来源表，得到 sourceId → treeUri 映射。
 *
 * 封面 URI 必须是「授权树 URI + documentId」的组合（开发文档 4.1），因此卡片投影
 * 带上 sourceId，这里只做一次映射而不是为每张卡片查库。
 *
 * 只有一张来源表（图片与归档由同一次扫描一起识别），所以只订阅一次。
 */
@Composable
private fun rememberSourceTreeUris(container: AppContainer): Map<String, String> {
    val sources by container.sourceRepository
        .observeSources()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(sources) {
        sources.associate { it.sourceId to it.treeUri }
    }
}

/**
 * 距离列表末端还有几项时就预取下一批。
 *
 * 取值偏大是有意的：40 项一批的追加若等到用户真的滑到底才开始查询，
 * 中间会闪一次加载指示；提前 6 项让追加在滚动停止前完成。
 */
private const val PREFETCH_DISTANCE = 6

