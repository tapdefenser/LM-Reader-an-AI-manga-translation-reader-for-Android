package com.lmreader.ui.reader

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingDirection
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.ui.reader.translation.ReaderTranslationUiState

/**
 * 分页阅读器：承载 Mihon 的三种 Pager 模式
 * （`Paged (left to right)` / `Paged (right to left)` / `Paged (vertical)`）。
 *
 * ## 三种方向为什么能用一套代码
 *
 * 关键事实（来自 Mihon `PagerViewerAdapter.setChapters` 的末行）：**右到左并没有反转
 * ViewPager 或设置 RTL 布局方向**，它只是把适配器里的项列表反转了。于是"索引更大的项
 * 画在右边"这条平台默认行为不变，而阅读顺序自然变成从右向左。
 *
 * 这里用同一手法：[reverseLayout] = true 让索引更大的项排在左侧。因此**不要**再额外
 * 翻转布局方向或翻转点按区域——Mihon 都不翻转，多翻一次就会反向。
 *
 * 竖向分页用 [VerticalPager]（整页吸附），与条漫的连续滚动是两件不同的事。
 *
 * ## 项列表里不只有页面
 *
 * [items] 还包含**章节过渡项**（Mihon `ChapterTransition`）：读者翻过末页之后进入过渡项，
 * 再往前就是下一章的页。所以这里按 [ReaderItem] 的密封类型分派渲染，
 * 而不是假定"每一项都是一页"——那正是之前多章节目录不通的表现。
 */
@Composable
internal fun PagerReader(
    items: List<ReaderItem>,
    settings: ReaderSettings,
    currentIndex: Int,
    /**
     * 当前项的**身份**（见 `ReaderUiState.positionKey`）。
     *
     * 下标会随窗口前滚整体平移，身份不会。归位必须是"把分页器挪到 [positionKey] 所在的下标"，
     * 而不是"挪到某个记住的数字"。
     */
    positionKey: String,
    /**
     * 归位信号（见 `ReaderUiState.positionSyncToken`）：数值一变，就把分页器挪到
     * [positionKey] 所在的下标。
     *
     * **只在状态机主动移动读者、或项列表被替换时自增**——读者自己翻页不算。
     * 这一点是必须的：快滑时状态总是滞后于分页器，若按"位置变了就核对"来驱动，
     * 就会反过来把已经滑到前面的分页器拽回状态记得的旧位置。
     */
    positionSyncToken: Int,
    onItemSettled: (Int) -> Unit,
    /** 滚动状态上报：滚动中状态机不替换项列表（见 [ReaderViewModel.onScrollingChanged]）。 */
    onScrollingChanged: (Boolean) -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    onTransitionAction: (ReaderItem.Transition) -> Unit,
    /** 页面字节的预取缓存；命中时不必再过一次 SAF。 */
    prefetcher: PagePrefetcher? = null,
    translations: ReaderTranslationUiState = ReaderTranslationUiState(),
    renderSettings: BubbleRenderSettings = BubbleRenderSettings(),
    onBubbleSelected: (String, String?) -> Unit = { _, _ -> },
    onBubbleGesture: (String, com.lmreader.ui.reader.translation.BubbleEditGesture) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val horizontal = settings.readingMode.direction == ReadingDirection.HORIZONTAL

    /**
     * 视口外保留几页（横向与竖向分页共用）。
     *
     * 与「预载页数」挂钩：那个设置表达的是"我读多少页之内不想等"，因此它同时决定
     * 预读多少**字节**（[com.lmreader.ui.reader.PagePrefetcher]）与保留多少**已解码的
     * 页**。上限 2 是内存考量——每页解码后是整张位图，保留太多会把真机的 256MB 堆吃满。
     *
     * ## 为什么不能是 0
     *
     * `0` 会在滑动过程中把相邻页的组合销毁。落页时 Compose 新建一个引擎视图，而它的解码
     * 是异步的——于是有一帧什么都没有，看起来就是"闪一下"。保留页之后，相邻页在滑动期间
     * 就已经解码完成，落页直接是成品。
     *
     * 内存上界 = 存活视图数（可见 1 + 两侧各 [adjacentPagesAlive]）× 每页整图，与"预载
     * 页数"那个**格数预算**是两件事：后者只管磁盘预取与"加载哪几章"（见 [buildWindow]）。
     */
    val adjacentPagesAlive = (settings.preloadPages / 4).coerceIn(0, 2)

    // initialPage 只在首次组合时生效，因此换章与预载导致的下标平移要靠下面的
    // LaunchedEffect 同步。
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(items.indices),
        pageCount = { items.size },
    )

    /**
     * 分页器**自己**挪动到的位置。
     *
     * `settledPage` 分不清"读者翻到的位置"和"我们把它挪到的位置"，而这两者必须区别对待：
     * 把前者的回放当成读者翻页会让阅读器来回撞墙（真机上出现过：翻到过渡页 → 我们提升
     * 章节并落到目标章第一页 → 分页器滚过去回放同一位置 → 被当成"读者往回翻了一页" →
     * 把上一章又提升回来，于是永远进不了下一章）。
     *
     * 因此每次程序化滚动都记下目标位置：只有当分页器**落到别的位置**之后，同一个位置
     * 再次出现才算读者翻页。
     */
    var suppressSettleFrom by remember { mutableIntStateOf(-1) }

    // 滚动状态上报（闸①：滚动中状态机不替换项列表）。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect(onScrollingChanged)
    }

    // 落页：回报给状态机——跨章判定与进度落库都只有那一处。
    //
    // ⚠️ key **只有** `pagerState`。曾经把 `items.size` 也放进来，于是列表一变这个 effect
    // 就重启，而 `snapshotFlow` 会立刻把当前值重发一次——那一刻分页器记的往往还是**重排
    // 之前**的数字，而下标恰好因为窗口前滚平移了一整章，于是状态机把"另一个项的下标"
    // 当成了读者位置。真机症状：偏 3 / 偏 4 / 跳回上一章首页 / 过渡页重复 / 1 页章被漏。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page == suppressSettleFrom) {
                // 这是我们自己挪过去造成的回放：消费掉，并解除抑制，
                // 让读者之后再翻回同一页时仍能正常上报。
                suppressSettleFrom = -1
                return@collect
            }
            suppressSettleFrom = -1
            onItemSettled(page)
        }
    }

    // 归位：**只由 [positionSyncToken] 驱动**（状态机主动移动 / 项列表被替换）。
    //
    // 不要等"滚动停止"再动手：那样会把这次归位推迟到读者下一次滑动的中途执行，
    // 表现就是"滑到第 7 章又被拽回第 5 章"，而且读者不停手就会反复发生。
    // 闸①保证"列表被替换"这件事只发生在空闲时，因此这里几乎总是空闲的。
    LaunchedEffect(positionSyncToken) {
        if (positionSyncToken == 0) return@LaunchedEffect
        val target = currentIndex.coerceIn(items.indices)
        // 不变量自检：下标解出来的项必须就是位置身份本身。不成立说明状态机内部不自洽
        // （曾经的"陈旧窗口"就是这样被应用进来的：窗口里根本没有当前位置的项）。
        if (positionKey.isNotEmpty() && items.getOrNull(target)?.key != positionKey) {
            android.util.Log.w(
                "LMR-POS",
                "归位目标与位置身份不一致：idx=$target items=${items.size}" +
                    " key=${items.getOrNull(target)?.key?.takeLast(22)}" +
                    " stateKey=${positionKey.takeLast(22)}",
            )
        }
        if (pagerState.currentPage == target) return@LaunchedEffect
        suppressSettleFrom = target
        pagerState.scrollToPage(target)
    }
    // 外部位置变化（点按翻页、滑杆、恢复进度、换章落点、窗口重排）驱动分页器滚动。
    //
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val renderItem: @Composable (Int) -> Unit = { index ->
            when (val item = items[index]) {
                is ReaderItem.PageItem -> EnginePageView(
                    source = item.chapter.source,
                    page = item.page,
                    settings = settings,
                    onSingleTap = onTap,
                    prefetcher = prefetcher,
                    translation = translations.pages[item.page.pageId],
                    regions = translations.draft?.takeIf { it.saved.pageId==item.page.pageId }?.regions
                        ?: translations.pages[item.page.pageId]?.regions.orEmpty(),
                    renderSettings = renderSettings,
                    showingOriginal = item.page.pageId in translations.originals,
                    editing = translations.editing && translations.progress==null,
                    selectedBubble = translations.draft?.takeIf { it.saved.pageId==item.page.pageId }?.selectedId,
                    onBubbleSelected = { onBubbleSelected(item.page.pageId,it) },
                    onBubbleGesture = { onBubbleGesture(item.page.pageId, it) },
                )

                is ReaderItem.Transition -> ChapterTransitionView(
                    transition = item,
                    settings = settings,
                    onRetry = { onTransitionAction(item) },
                    onTap = onTap,
                )
            }
        }

        if (horizontal) {
            HorizontalPager(
                state = pagerState,
                modifier = modifier,
                // 右到左：索引更大的项排在左侧，与 Mihon 反转适配器列表等价。
                reverseLayout = settings.readingMode.isRightToLeft,
                beyondViewportPageCount = adjacentPagesAlive,
                key = { items[it].key },
            ) { index -> renderItem(index) }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = modifier,
                // 与横向用**同一个**值。这里曾经写死 0，于是竖向分页在落页那一刻会露出
                // 一张还没解码完的页（横向早就修过这个问题，注释见上）；保留页数的内存代价
                // 两向完全一样，没有任何理由不一致。
                beyondViewportPageCount = adjacentPagesAlive,
                key = { items[it].key },
            ) { index -> renderItem(index) }
        }
    }
}
