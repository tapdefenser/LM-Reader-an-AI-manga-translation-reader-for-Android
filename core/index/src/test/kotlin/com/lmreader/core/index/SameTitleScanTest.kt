package com.lmreader.core.index

import com.lmreader.core.model.LayoutMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SameTitleScanTest {
    @Test fun differentPathsWithSameTitleRemainSeparateAndStableInBothModes() = runTest {
        listOf(LayoutMode.SINGLE_CHAPTER, LayoutMode.MULTI_CHAPTER).forEach { mode ->
            val page = if(mode == LayoutMode.MULTI_CHAPTER) "chapter/01.jpg" else "01.jpg"
            val paths = listOf("mangalot/abc/$page", "kanmanha/abc/$page")
            val first = ScanHarness("Download", paths, mode = mode)
            first.run()
            assertEquals(listOf("abc", "abc"), first.names)
            assertEquals(2, first.discoveries.map { it.manga.mangaId }.distinct().size)
            assertEquals(2, first.discoveries.map { it.manga.anchorDocumentId }.distinct().size)
            assertEquals(2, first.discoveries.flatMap { it.chapters }.map { it.chapterId }.distinct().size)
            val second = ScanHarness("Download", paths, mode = mode, generation = 8)
            second.run()
            assertEquals(first.discoveries.map { it.manga.mangaId }.toSet(), second.discoveries.map { it.manga.mangaId }.toSet())
        }
    }

    @Test fun aStrayArchiveInASiteFolderDoesNotSwallowTheMangaBesideIt() = runTest {
        // 真机形态（/Tachiyomi/downloads）：站点目录里同时躺着顺手下载的
        // `ジャイアントお嬢様.zip` 与真正的漫画目录 `ジャイアントお嬢様/`（224 章），
        // 另一个站点下还有同名的一部。旧实现在"目录直接含归档"时 emit 完就 return，
        // 于是整个站点被压成一张以站点命名的卡片，站点下所有漫画都不再被发现——
        // 搜索同名作品只剩一部。
        val harness = ScanHarness("downloads", listOf(
            "Sunday Web Every (JA)/ジャイアントお嬢様.zip",
            "Sunday Web Every (JA)/ジャイアントお嬢様/第1話/001.jpg",
            "Sunday Web Every (JA)/メガトンチルドレン/Chapter_ea48dd/001.jpg",
            "Dokiraw (JA)/ジャイアントお嬢様/第2話_2f7c54/001.jpg",
        ))
        harness.run()

        val sameTitle = harness.discoveries.filter { it.manga.displayName == "ジャイアントお嬢様" }
        assertEquals("两个站点下的同名漫画都要被发现", 2, sameTitle.size)
        assertEquals("同名但必须是两个身份", 2, sameTitle.map { it.manga.mangaId }.distinct().size)
        assertEquals(2, sameTitle.map { it.manga.anchorDocumentId }.distinct().size)
        assertTrue("同一站点的其它漫画也要继续被发现", harness.names.contains("メガトンチルドレン"))
        assertTrue("压缩包仍然自成一张卡片", harness.names.contains("Sunday Web Every (JA)"))
        assertTrue("混放要留诊断", harness.diagnostics.any { it.contains("压缩包") })
    }
}
