package com.lmreader.ui.reader.translation

import com.lmreader.core.model.*
import java.io.File
import org.junit.Test
import org.junit.Assert.*

class PageBubbleDraftTest {
    private val region=PageTranslatedRegion(PageTextRegion("page:a",RegionKind.BUBBLE,PixelRect(10f,10f,100f,100f),
        emptyList(),"hello",listOf(PixelRect(30f,30f,80f,60f))),"你好")
    private val saved=ReaderPageTranslation("page","revision",File("page.json"),LocalTranslationLanguage.ENGLISH,
        LocalTranslationLanguage.CHINESE_SIMPLIFIED,"hash",200,300,listOf(region),emptyList(),1)
    @Test fun newBubbleOnUntranslatedPageHasEditableGeometryAndUndo() {
        val base = saved.copy(regions = emptyList(), revision = "")
        val added = PageBubbleDraft(base).addBubble()
        assertNotNull(added.selected); assertTrue(added.dirty)
        assertTrue(added.selected!!.region.id.startsWith("page:manual:"))
        assertFalse(added.editText("New text").undoChange().undoChange().dirty)
    }
    @Test fun aDragHasOneUndoEntryAndFontSizeIsPerBubble() {
        val draft = PageBubbleDraft(saved).beginTransform("page:a")
            .transform("page:a", PixelRect(20f,20f,130f,140f), 30f)
            .transform("page:a", PixelRect(30f,30f,160f,180f), 45f).finishTransform()
        assertEquals(1,draft.undo.size);assertEquals(45f,draft.selected!!.rotationDegrees)
        assertEquals(listOf(region),draft.undoChange().regions)
        val enlarged = draft.scaleFont(10)
        assertEquals(110,enlarged.selected!!.fontScalePercent)
        assertEquals(100,saved.regions.single().fontScalePercent)
    }
    @Test fun backwardPrefetchCannotEvictTheCurrentTranslation() {
        var pages = (1..8).associate { "$it" to it }
        for (id in (7 downTo 1).map(Int::toString)) {
            pages = retainReaderPage(pages,id,id.toInt(),id)
            for(neighbor in (id.toInt()-3..id.toInt()+3))
                pages = retainReaderPage(pages,"$neighbor",neighbor,id)
            assertEquals(id.toInt(),pages[id])
            assertTrue(pages.size <= 8)
        }
    }
    @Test fun selectionAndUnchangedTextDoNotMakeDraftDirty() {
        val draft=PageBubbleDraft(saved).select("page:a").editText("你好")
        assertFalse(draft.dirty);assertTrue(draft.undo.isEmpty());assertEquals(region,draft.selected)
    }
    @Test fun textIsPreviewOnlyUntilSavingAndUndoReturnsToSavedBaseline() {
        val draft=PageBubbleDraft(saved).select("page:a").editText("Edited 🙂\n第二行")
        assertTrue(draft.dirty);assertEquals("你好",saved.regions.single().translatedText)
        assertEquals("Edited 🙂\n第二行",draft.regions.single().translatedText)
        assertFalse(draft.undoChange().dirty)
    }
    @Test fun deleteRemovesMaskAndTextTogetherAndCanBeUndone() {
        val draft=PageBubbleDraft(saved).select("page:a").deleteSelected()
        assertTrue(draft.dirty);assertTrue(draft.regions.isEmpty());assertNull(draft.selected)
        assertEquals(listOf(region),draft.undoChange().regions);assertFalse(draft.undoChange().dirty)
    }
    @Test fun clearingTextRetainsBubbleAndDiscardRestoresTextAndSelection() {
        val draft=PageBubbleDraft(saved).select("page:a").editText("")
        assertEquals(1,draft.regions.size);assertTrue(draft.dirty)
        assertEquals(listOf(region),draft.discarded().regions);assertNull(draft.discarded().selected)
    }
    @Test fun staleSelectionCannotEditAnotherBubble() {
        val draft=PageBubbleDraft(saved).select("another-page:a").editText("wrong")
        assertEquals(saved.regions,draft.regions);assertFalse(draft.dirty)
    }
    @Test fun switchingPagesKeepsModeAndStartsFromThatPagesSavedData() {
        var editing=true
        var draft=PageBubbleDraft(saved).select("page:a").editText("Edited")
        val gate=DraftNavigationGate()
        val next=saved.copy(pageId="next",regions=emptyList())
        assertFalse(gate.request(draft.dirty,false) {draft=PageBubbleDraft(next)})
        assertEquals("page",draft.saved.pageId)
        draft=draft.discarded();gate.resume()
        assertEquals("next",draft.saved.pageId);assertTrue(editing);assertFalse(draft.dirty)
    }
}
