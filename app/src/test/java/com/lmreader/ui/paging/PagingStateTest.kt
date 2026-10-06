package com.lmreader.ui.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表分页状态机。
 *
 * 这里钉住的是用户明确要求的口径：**一次加载 30 项，而不是一股脑**——哪怕搜索结果
 * 有几千条，首屏也只申请一批；触底再加一批，绝不因为"总数已知"就一次读完。
 */
class PagingStateTest {

    @Test fun scanFinishingReconcilesInsertBeforeOffsetAndKeepsDistinctSameTitles() {
        data class Card(val id: String, val title: String)
        val paging = PagingState<Card>(idOf = { it.id })
        val old = Card("kanmanha/abc", "abc")
        val inserted = Card("mangalot/abc", "abc")
        paging.requestInitial()
        paging.append(PageSlice(listOf(old), 1, true), 1)
        paging.replaceWindow(PageSlice(listOf(inserted, old), 2, true), 2)
        assertEquals(listOf(inserted, old), paging.items)
        assertEquals(30, paging.capacity)
        assertEquals(2, paging.nextOffset)
        assertEquals(2, paging.totalKnown)
    }

    @Test fun reconciliationKeepsScrolledQuotaAndTheCorrectNextOffset() {
        val paging = state()
        paging.requestInitial()
        paging.append(page(1..30), 100)
        paging.requestNextBatch()
        paging.append(page(31..60), 100)
        val reordered = listOf("new") + (1..59).map { "m$it" }
        paging.replaceWindow(PageSlice(reordered, 60, false), 101)
        assertEquals(60, paging.capacity)
        assertEquals(reordered, paging.items)
        paging.requestNextBatch()
        paging.append(PageSlice((60..89).map { "m$it" }, 90, false), 101)
        assertEquals(90, paging.items.size)
        assertEquals(90, paging.capacity)
        assertEquals(90, paging.items.map { it }.distinct().size)
    }

    private fun state() = PagingState<String>(idOf = { it })

    private fun page(range: IntRange, exhausted: Boolean = false) = PageSlice(
        items = range.map { "m$it" },
        nextOffset = range.last + 1,
        exhausted = exhausted,
    )

    @Test
    fun `首次只申请一批 30 项`() {
        val paging = state()
        paging.requestInitial()

        assertEquals(30, paging.capacity)
        assertTrue(paging.needsMore())
    }

    @Test
    fun `取满一批后不再申请，触底才加一批`() {
        val paging = state()
        paging.requestInitial()
        paging.beginLoad()
        paging.append(page(1..30), totalKnown = 3000)
        paging.endLoad()

        // 库里还有 2970 条，但额度只有 30：不能因为总数已知就继续读。
        assertFalse(paging.needsMore())
        assertEquals(30, paging.items.size)

        paging.requestNextBatch()
        assertTrue(paging.needsMore())
        assertEquals(60, paging.capacity)
    }

    @Test
    fun `搜索结果几千条也只是一批一批地取`() {
        val paging = state()
        paging.requestInitial()
        var loaded = 0
        // 模拟"滚到底就加一批"：每批 30，取满 10 批也只加载 300 条。
        repeat(10) {
            paging.beginLoad()
            val next = loaded + 1
            val slice = (next..next + 29).map { "m$it" }
            paging.append(PageSlice(slice, next + 30, exhausted = false), totalKnown = 3000)
            paging.endLoad()
            loaded += 30
            paging.requestNextBatch()
        }
        assertEquals(300, paging.items.size)
        assertEquals(3000, paging.totalKnown)
    }

    @Test
    fun `扫描中重复出现的条目按 id 去重`() {
        val paging = state()
        paging.requestInitial()
        paging.beginLoad()
        paging.append(page(1..30), null)
        paging.endLoad()
        paging.beginLoad()
        // 扫描插入新行会让 LIMIT/OFFSET 分页重复吐出前几条（框架 9.2 的已知限制）。
        paging.append(page(25..54), null)
        paging.endLoad()

        assertEquals(54, paging.items.size)
        assertEquals(paging.items.distinct().size, paging.items.size)
    }

    @Test
    fun `就地替换一项不影响顺序与总额度`() {
        val paging = state()
        paging.requestInitial()
        paging.beginLoad()
        paging.append(page(1..30), null)
        paging.endLoad()

        // 封面异步取到后就地刷新那一张：不能因此重建会话（那会把滚动位置打回第一页）。
        assertTrue(paging.updateItem("m7") { "m7-updated" })
        assertEquals("m7-updated", paging.items[6])
        assertEquals(30, paging.items.size)
        // 列表已经换掉（换搜索词）时定位不到，返回 false 让调用方不要动界面。
        assertFalse(paging.updateItem("nope") { it })
    }

    @Test
    fun `重置会话清空已见集合与额度`() {
        val paging = state()
        paging.requestInitial()
        paging.beginLoad()
        paging.append(page(1..30, exhausted = true), null)
        paging.endLoad()
        assertTrue(paging.exhausted)

        paging.reset()

        assertEquals(0, paging.items.size)
        assertFalse(paging.exhausted)
        assertEquals(0, paging.nextOffset)
        assertEquals(30, paging.capacity)
    }
}
