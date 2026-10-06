package com.lmreader.ui.paging

/**
 * 图库/书架列表的「40 项增长」状态机（开发文档 6.4 / 8.1）。
 *
 * 为什么单独抽出：分页额度、扫描中补位、追加下一批这三条规则在扫描、搜索和
 * 刷新下都要成立，把它们放在 Compose 里会无法测试。这里只做纯状态计算，
 * 不接触数据库与协程调度。
 *
 * 本步限制（开发文档 6.4 的 P1 项）：后端使用 LIMIT/OFFSET 而不是会话顺序表，
 * 扫描中新增条目可能让同一项在两次翻页里出现，因此这里用已见过的 id 去重。
 */
class PagingState<T>(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val idOf: (T) -> String,
) {
    private val seen = LinkedHashSet<String>()
    private val mutableItems = mutableListOf<T>()

    val items: List<T> get() = mutableItems

    /** 已经取过的最大 offset，用于向后端请求下一批。 */
    var nextOffset: Int = 0
        private set

    /** 后端报告已无更多数据。 */
    var exhausted: Boolean = false
        private set

    /** 当前可用额度（已显示项数 + 目标额度）。 */
    val capacity: Int get() = maxOf(mutableItems.size, requestedCapacity)

    private var requestedCapacity = 0
    private var loading = false

    /** 是否应该再取一批：未耗尽、未在加载、已显示项数未达本次额度。 */
    fun needsMore(): Boolean = !exhausted && !loading && mutableItems.size < requestedCapacity

    fun beginLoad() {
        loading = true
    }

    fun endLoad() {
        loading = false
    }

    val isLoading: Boolean get() = loading

    /** 首次加载：申请一屏额度。 */
    fun requestInitial() {
        requestedCapacity = pageSize
    }

    /** 滚到底部：额度 +40（开发文档 8.1「底部加载」）。 */
    fun requestNextBatch() {
        requestedCapacity += pageSize
    }

    /**
     * 吸收一批结果。
     *
     * @param totalKnown 后端可给出的总数；null 表示未知，UI 不得伪造百分比。
     */
    fun append(page: PageSlice<T>, totalKnown: Int?) {
        page.items.forEach { item ->
            if (seen.add(idOf(item))) mutableItems += item
        }
        nextOffset = page.nextOffset
        exhausted = page.exhausted
        this.totalKnown = totalKnown
    }

    /** Reconcile the loaded window after inserts before an OFFSET cursor. Keep its quota. */
    fun replaceWindow(page: PageSlice<T>, totalKnown: Int?) {
        seen.clear(); mutableItems.clear()
        append(page, totalKnown)
    }

    var totalKnown: Int? = null
        private set

    /** 排序/筛选/搜索条件变化：全新会话，清空已见集合。 */
    fun reset() {
        seen.clear()
        mutableItems.clear()
        nextOffset = 0
        exhausted = false
        requestedCapacity = pageSize
    }

    /** 当前额度是否仍有空位——扫描继续时用它决定是否立即补入新条目。 */
    fun hasFreeSlot(): Boolean = !exhausted && !loading && mutableItems.size < requestedCapacity

    /**
     * 就地替换一项（按 id 定位）。
     *
     * 为什么需要它：封面是滚动时异步取到的，结果回来后必须让**这一张**卡片立刻显示
     * 封面，而不是重建整个分页会话——重建会把用户滚了很远的位置打回第一页。
     * 找不到该项时返回 false（列表已经因为换搜索词等原因被换掉了），调用方据此
     * 不做任何界面更新。
     */
    fun updateItem(id: String, transform: (T) -> T): Boolean {
        val index = mutableItems.indexOfFirst { idOf(it) == id }
        if (index < 0) return false
        val updated = transform(mutableItems[index])
        mutableItems[index] = updated
        return true
    }

    companion object {
        /**
         * 一批多少项。
         *
         * 30 而不是更大：图库是"边滚边加载"，一批越小首屏越快、封面探测的批次也越小；
         * 用户明确要求"搜索结果可能是几千条，但依然一次加载 30 项，而不是一股脑"。
         */
        const val DEFAULT_PAGE_SIZE = 30
    }
}

/** 后端返回的一页切片。偏移语义由后端保证。 */
data class PageSlice<T>(
    val items: List<T>,
    val nextOffset: Int,
    val exhausted: Boolean,
)
